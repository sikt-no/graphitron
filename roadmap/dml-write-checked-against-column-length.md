---
id: R1025
title: "A DML mutation write is not checked against the column length before the statement"
status: Ready
bucket: architecture
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A DML mutation write is not checked against the column length before the statement

## Goal

Some `@mutation(typeName: INSERT | UPDATE | UPSERT)` fields return a payload whose errors field is a union of `@error` types (the field's *error channel*: each member type's `handlers` say which failures land in it). On such a field, a string too long for the column it writes should come back as a typed error in that union, with a path to the offending input field, and the statement should never run. That happens once one member of the union declares `{handler: VALIDATION}`. Today the value reaches the database and comes back as a redacted "An error occurred" error. Graphitron 9 gave consumers this check through its `<recordValidation>` plugin setting. Graphitron 10 has no equivalent, so a consumer migrating from 9 loses client-visible input validation on every DML mutation and gets no build-time signal. After this item, migrating is one handler entry on the error type that 9's setting named. A build warning names every DML payload that writes length-bounded columns without declaring it.

From sis (Oracle). The mutation is unchanged; the error type gains one handler entry:

```graphql
type Mutation {
    registrerEsiForStudenter(input: [EsiInput!]!): RegistrerEsiForStudenterPayload
        @mutation(typeName: INSERT) @table(name: "ESI")
}

union RegistrerEsiForStudenterError = UgyldigInput

type UgyldigInput @error(handlers: [
    {handler: VALIDATION},              # new: receives the length check
    {handler: DATABASE, code: "1438"},  # existing vendor-code entries (two of four shown), unchanged
    {handler: DATABASE, code: "2290"}
]) {
    path: [String!]!
    message: String!
}
```

An `esiId` of 300 characters against a 256-character column then answers exactly as Graphitron 9 did:

```json
{"data":{"registrerEsiForStudenter":{"errors":[{"__typename":"UgyldigInput",
  "message":"size must be between 0 and 256","path":["registrerEsiForStudenter","input","0","esiId"]}]}}}
```

Without the `VALIDATION` entry the response is unchanged from today (the value reaches the database, `ORA-12899`, and the result is matched by a `DATABASE` handler if one names the code, redacted otherwise), and the build warns. sis has the same pattern on `STUDENTKORT.STUDENTKORTNR` and on `MERKNAD` (510 > 500) across its `registrerMeldingerOm*` mutations.

## What Graphitron 9 did, and what the rewrite already has

Graphitron 9 (`v9.3.0`): the plugin setting `<recordValidation><enabled>true</enabled><schemaErrorType>UgyldigInput</schemaErrorType></recordValidation>` made every generated mutation build its jOOQ record, then validate each property the client actually set (`RecordValidator.validatePropertiesAndGenerateGraphQLErrors`) with Jakarta Bean Validation. The constraints came from the consumer's jOOQ codegen: with `<validationAnnotations>` on, jOOQ puts `@Size(max = n)` on each record setter from the column's declared length. A violation landed in the named error type when the mutation's union contained it, and became a top-level error otherwise.

The rewrite has the routing half, under a different spelling:

- `{handler: VALIDATION}` is the per-error-type analogue of `schemaErrorType`. The emitted schema `TypeResolver` (`GraphitronSchemaClassGenerator`) resolves any `GraphQLError` source to the union member carrying it, and `<ErrorType>Fetchers` reads `message` and `path` straight off the `GraphQLError`. DML carrier channels already admit a `VALIDATION` handler: `FieldBuilder.detectStructuralDmlErrorChannel` runs the at-most-one rule over it. But nothing ever produces a violation on a DML carrier, so the entry is inert there.
- The only producer today is `TypeFetcherGenerator.validatorPreStep`, emitted for `@service` fetchers alone, gated on `hasValidationHandler`. It validates the per-input-type class `InputRecordGenerator` emits under `<outputPackage>.inputs`, which carries no constraints (R747). On a violation it returns `Outcome.ErrorList` through `ChannelEarlyReturnEmitter.emit`, ahead of the body.
- The emitted `ConstraintViolations.toGraphQLError` owns the wire shape of a violation: path is the execution path plus the argument name plus the property path, message is the violation's message, `extensions.constraint` is the annotation's simple name.

What is missing is the fact and its producer. A column's declared length is nowhere in the model: `ColumnRef` is names-only on purpose, and `JooqCatalog.ColumnFacts` (which `FieldBuilder.columnFacts` reads, and from which `JooqFactCapture.columns` writes `sql_column`) carries the SQL type name, binding type and nullability, not the length.

## Design

### The fact: a column's declared length

The fact comes from a corpus, the jOOQ catalog. Capture records what the source says, not one consumer's question about it:

- `JooqCatalog.ColumnFacts` gains `Integer declaredLength`, read in `columnFactsOf` as `getDataType().lengthDefined() ? getDataType().length() : null` (not `hasLength()`, which jOOQ answers true with `length()` 0 for `SQLDataType.CLOB`, PostgreSQL's `text`, and for an unbounded `varchar`). There is no `isString()` filter: whether a length bounds a *string write* is the classifier's question, answered with `binding_type`, not capture's.
- `sql_column` gains `declared_length INT` (nullable, `CHECK (declared_length > 0)`), written by `JooqFactCapture.columns` beside `sql_type`. Its `COMMENT ON COLUMN` (which `FactSchemaGateTest.commentCoverageIsTotal` requires) states the grain and the unit: "the length the column's type declares, as jOOQ reports it, for example 255 for film.title's varchar(255); in the dialect's own unit, characters on PostgreSQL and bytes or characters on Oracle by the column's length semantics, which the catalog does not say; null when the type declares no length".

This is a column rather than a separate relation. It is one value per column, read by the same walk on the same cadence as `sql_type`, so it shares `sql_column`'s owner, grain and `touched_at` mark and sweep. It goes in the store because new facts must land there while both models are live, and in `ColumnFacts` because that is the one projection both the capture and the classifier's column lookup read, so the walk and the store cannot disagree on the value. That lookup is `FieldBuilder.columnFacts` today, a private helper over `ctx.catalog`; it moves to `BuildContext.columnFacts`, where the input-field carriers are built (next section), and `FieldBuilder` calls it there.

The unit gap is real and stays open. Oracle's `VARCHAR2` defaults to byte semantics, and sis stores Norwegian text, where `æ`, `ø` and `å` take two bytes in UTF-8. The check counts characters (see Emit), so on a byte-semantics column it under-rejects. A value that fits in characters but not in bytes still reaches the database and fails with `ORA-12899` as it does today. The check never rejects a value the column would accept, so the gap costs only coverage, never correctness.

### Where the check attaches: the field's write plan, not the input type

A `@mutation` input type carries no table. The write target comes from the consuming field (its return's `@table`, or `@mutation(table:)`; see `docs/manual/reference/directives/mutation.adoc`), so one SDL input type can write `film.title` in one mutation and a differently sized column in another. A column bound reached through a consuming field's write target is therefore a *use-keyed* binding, a fact about one (mutation field, input field) pair, where R98's constraint set and the `<outputPackage>.inputs` class are *definition-keyed*, one per input type. Folding the bound into the per-input-type surface would mix the two keying axes, so it attaches where the classifier already resolves use-keyed bindings: the cells of the write plan.

**The bound lives on the written cell, decided where the input field is resolved.** Every written value cell starts as one of the two `InputField.SetField` permits, `InputField.ColumnBackedField` and `InputField.ColumnBackedReferenceField`. Both are resolved per use in `BuildContext.classifyInputField`, which takes the consuming field's `resolvedTable`, so both are use-keyed. INSERT and UPSERT write them straight from `TableInputArg.fields()`. UPDATE's `SetColumn` rows are derived from them by `UpdateRowsWalker`. Each of the two permits gains a component `ColumnWriteBound bound`, a sealed per-cell decision:

- `ColumnWriteBound.Length(int declaredLength)`: the cell writes the client's text unchanged into a column that declares a length.
- `ColumnWriteBound.Unbounded`: anything else.

The decision has one home, a private `BuildContext.writeBound(GraphQLInputObjectField field, TableRef table, List<ColumnRef> columns, CallSiteExtraction extraction)`, and every construction site of the two permits in `classifyInputFieldInternal` and `inputFieldFromNodeIdResolved` calls it. It answers `Length(n)` when all of these hold, and `Unbounded` otherwise:

- the extraction is `CallSiteExtraction.Direct`. That excludes every NodeId arm (`ThrowOnMismatch` on the implicit, same-table and FK-target paths), whose wire value is an encoded id rather than the column's text;
- the carrier has exactly one column (multi-column carriers are always NodeId-decoded today, which the constructors enforce);
- the field's named GraphQL type is not an enum. Enum inputs also arrive as `Direct`, because graphql-java does the wire-to-runtime translation, so the extraction alone cannot tell them apart. Their values are authored constants from a closed set, not client text;
- `columnFacts(table, column)` gives binding type `java.lang.String` and a non-null `declaredLength` (`n`).

For a `ColumnBackedReferenceField` this makes `Length` reachable only on a plain `@reference` with an empty path, the one arm that is `Direct` and binds `FilterBinding.Local` on the consuming table's own column. Every FK-target arm is NodeId-decoded and therefore `Unbounded`, and a `Remote` carrier is never written (the write rails reject it).

`SetColumn` gains the same component, but does not decide it. `UpdateRowsWalker` carries the walked carrier's `bound` on its private `Contribution` record into every `SetColumn` it mints from that carrier. The walker's `wrap` puts a `CallSiteExtraction.NestedInputField` around a nested leaf's extraction only after the decision was made on the leaf carrier, so a nested UPDATE string cell keeps its `Length`. The one other place a `SetColumn` is minted, `TypeFetcherGenerator.keyColumnsAsSetGroups`, projects WHERE-side `KeyColumn`s into the shared group shape for the agreement preamble; those are key cells, never written cells, and it passes `Unbounded`.

The emitter therefore never branches on extraction kind, binding type or GraphQL type. Both permits also serve query-side filters and lookups, where the component is carried and not read. That is harmless, and cheaper than write-only twins of the carriers.

**Which cells are written is stated once.** `OperationMember.Write.Dml` gains a `writtenCells()` default, the sibling of `whereKeyColumns()`. It returns one `WrittenCell(List<String> inputPath, ColumnRef column, ColumnWriteBound bound)` per written cell:

- INSERT and UPSERT: the `SetField` leaves of `TableInputArg.fields()`, with `NestingField` groups recursed into and their nested path kept (`["address", "line1"]`). `TableInputArg.setFields()` is not read: `TableInputArg.of` is its only producer and always leaves it empty. Were it populated, UPSERT's `DO UPDATE SET` binds each column to `EXCLUDED.<col>`, the value the insert arm's `fields()` cell already carried, so it would add no cell of its own.
- UPDATE: `UpdateRows.setColumns()`, with the path taken from the extraction (`NestedInputField.path()` for a nested leaf, `[sdlFieldName]` otherwise). A composite NodeId carrier yields several `SetColumn` rows on one path; they are all `Unbounded` and collapse to one cell by path.
- DELETE: the empty list, since it writes no values.

Key and WHERE cells are not written cells. An over-long key matches no row rather than failing, so there is no database error to pre-empt. The partition is the one `TableInputArg` and `UpdateRows` already made; `writtenCells()` reads it rather than re-deciding it.

**Check, warn or nothing is derived in one accessor.** `MutationDmlRecordField` and `MutationBulkDmlRecordField` gain a derived (never stored) `lengthCheck()` accessor. It follows the precedent of `DmlTableField.dialectRequirement()`, and returns a sealed `LengthCheck`:

- `Checked(List<WrittenCell> bounded)`: the write has `Length` cells and the channel carries a `ValidationHandler`.
- `Unrouted(List<WrittenCell> bounded)`: the write has `Length` cells and an errors union, but no `VALIDATION` member in it.
- `None`: no `Length` cells, or no errors union at all.

The emitter reads `Checked`, the advisory reads `Unrouted`, and neither re-evaluates `hasValidationHandler`. A carrier with no errors union is `None` rather than `Unrouted`. Giving it somewhere to route means adding an errors field to the payload, which is a bigger schema change than the warning can ask for, and a warning there would fire on nearly every payload over a `varchar` column. Direct-return DML (`MutationField.DmlTableField`) has no errors union and is out of scope.

**Migration debt.** The `ColumnWriteBound` component extends the classifier's transitional model, not a new leaf or walk-side registry. When code generation reads its bindings from the store, the bound becomes a derivation joining `sql_column.declared_length` to the write binding, and the component retires with the carrier it sits on.

### Emit

In `TypeFetcherGenerator.buildSingleRecordTwoStepFetcher` and `buildBulkRecordTwoStepFetcher`, when `lengthCheck()` is `Checked`, emit the check right after the argument is read and before `chainAndGuards.preGuard()`. That puts it ahead of the SET-map construction, the NodeId decode and the transaction:

```java
List<Object> violations = new ArrayList<>();
if (<nestedMapValueExpr(in, ["title"])> instanceof String s && s.codePointCount(0, s.length()) > 255)
    violations.add(ConstraintViolations.sizeExceeded(env, List.of("in", "title"), 255));
// one test per bound
if (!violations.isEmpty()) return <ChannelEarlyReturnEmitter.emit(...)>;
```

- The value read is `ArgCallEmitter.nestedMapValueExpr`, the same read the VALUES and SET emit use, so an absent or null field is not checked. This matches 9, which validated only the properties the client set.
- Bulk loops the rows by index, with path `List.of("in", i, "title")`, and checks every row before any statement runs. Any violation returns the whole list and writes nothing. That matches 9, and it matches the bulk statement's own all-or-nothing transaction.
- The check counts code points. PostgreSQL's `varchar(n)` and Oracle's CHAR-semantics columns both count characters, so a 255-character title containing characters outside the Basic Multilingual Plane is valid. Counting with `String.length()` (UTF-16 units, which is what `@Size` counts) would reject it. A value's code-point count never exceeds its UTF-8 byte count, so on a byte-semantics column the check is a lower bound (see the fact section) and never rejects a valid value.
- `ConstraintViolationsClassGenerator` emits one more public static beside `toGraphQLError`, `sizeExceeded(DataFetchingEnvironment env, List<Object> argPath, int max)`. Both delegate to one private builder that takes the response-path suffix, the message and the constraint name, and owns the execution-path prefix, the `extensions.constraint` entry and the `GraphqlErrorBuilder` call. So there is one copy of the wire shape of a validation error, and length errors cannot drift from Jakarta errors. `sizeExceeded` passes `"size must be between 0 and " + max` and `"Size"`. The message is a fixed English string rather than Hibernate's locale-dependent one.
- The early return reuses `ChannelEarlyReturnEmitter.emit`. Downstream, nothing changes: the `TypeResolver`, `ErrorTypeFetcher`'s `path`/`message` and the `Outcome` wrapper already handle a `GraphQLError` source.

The generated code needs no Bean Validation provider. It references only classes graphitron already emits and `java.lang`.

Later, DML carriers may also get a Jakarta pre-step (once R747 or R98 gives the input class something to check). When they do, both checks run and their errors are concatenated into one `violations` list ahead of the single early return, so the client sees every problem with its input at once. Neither check short-circuits the other: both run before the statement and they check independent sources.

### Build warning when the union cannot receive it

A new classifier advisory, `LintRule.DML_WRITE_LENGTH_UNROUTED` (`"dml-write-length-unrouted"`, `Source.CLASSIFIER`), fires for every carrier whose `lengthCheck()` is `Unrouted`. It is a typed `BuildWarning.LintFinding` keyed at the mutation field's coordinate, so it reaches the `build_warning_` facts and the LSP like the other classifier advisories, and is not prose formatted at the detection site. Its message names the field, up to three bounded columns with their lengths, and the fix:

> `Mutation.registrerEsiForStudenter` writes length-bounded columns (`ESI.ESI_ID` 256, ...) but its errors union has no `{handler: VALIDATION}` member, so an over-long value reaches the database. Add `{handler: VALIDATION}` to one `@error` type in the union to have it rejected before the statement. (Graphitron 9's `<recordValidation><schemaErrorType>` named that type.)

The check is gated rather than always on for one reason. If it always ran, with a top-level error as the fallback, it would change the outcome for any consumer who today catches the overflow in the union, as `{handler: DATABASE, sqlState: "22001"}` on PostgreSQL or `code: "12899"` on Oracle: their error would move out of the union into a top-level error. Gating keeps every existing schema's behaviour unchanged until it declares `VALIDATION`. The `@service` pre-step already follows the same rule. A schema that already declares `VALIDATION` on a DML carrier, where it is inert today, starts checking; that is the intended change.

### User documentation (first-client draft)

`docs/manual/how-to/error-channel.adoc`, in the section describing `VALIDATION` as a pre-execution step, a new paragraph:

> On a `@mutation` field that returns a payload, a `{handler: VALIDATION}` member also turns on a length check. Before the statement runs, every string the mutation writes to a column with a declared character length (`varchar(n)`, `char(n)`) is checked against that length. A value that is too long becomes one error in the union, with the message `size must be between 0 and n`, `path` pointing at the input field (`["createFilm", "in", "title"]`, or `["createFilms", "in", "1", "title"]` for the second element of a list input), and `extensions.constraint` set to `Size`. Nothing is written: in a list input, one over-long value rejects the whole call. Values are counted in characters. On an Oracle column declared with byte length semantics a value can fit in characters and still exceed the column's bytes, and the database rejects it as before. Without a `VALIDATION` member the value reaches the database unchecked, and the build warns (`dml-write-length-unrouted`).
>
> Coming from Graphitron 9: the error type `<recordValidation><schemaErrorType>` named is the one to give `{handler: VALIDATION}`. Graphitron 10 has no `<recordValidation>` setting, and does not need jOOQ's `<validationAnnotations>`.

`docs/manual/reference/directives/error.adoc`, `handler` row: "`VALIDATION` marks the channel for pre-execution validation: Bean Validation before a `@service` method runs, and the column-length check before a payload-returning `@mutation` writes."

`docs/manual/reference/directives/mutation.adoc`: one sentence in the payload section pointing at the error-channel paragraph above.

The lint rule's entry goes wherever the existing classifier advisories are documented (`docs/manual/reference/diagnostics-glossary.adoc`, if that is where `splitquery-redundant-on-record-parent` lives at pickup).

## Implementation

- `graphitron-model`: `JooqCatalog.ColumnFacts` + `columnFactsOf`; `graphitron-model.sql` (`sql_column.declared_length` and its comment); `JooqFactCapture.columns`; every other `ColumnFacts` constructor call (the `columnFacts` fallback, now in `BuildContext`, passes null); `LintRule.DML_WRITE_LENGTH_UNROUTED`.
- `graphitron`:
  - New `model/ColumnWriteBound` (sealed: `Length`, `Unbounded`), `WrittenCell` and `LengthCheck` (sealed: `Checked`, `Unrouted`, `None`).
  - The `bound` component on `InputField.ColumnBackedField` and `InputField.ColumnBackedReferenceField`, decided by `BuildContext.writeBound` at every construction site in `classifyInputFieldInternal` and `inputFieldFromNodeIdResolved`; `columnFacts` lifted from `FieldBuilder` into `BuildContext`; those are the only main-source construction sites of the two permits, and test fixtures that build them directly pass `Unbounded`.
  - The `bound` component on `SetColumn`, carried through `UpdateRowsWalker.Contribution` from the walked carrier; `TypeFetcherGenerator.keyColumnsAsSetGroups` passes `Unbounded`.
  - `OperationMember.Write.Dml.writtenCells()`, and `lengthCheck()` on `MutationDmlRecordField` and `MutationBulkDmlRecordField`.
  - The advisory, raised where those variants are built (beside `detectStructuralDmlErrorChannel`).
  - The `TypeFetcherGenerator` emit in the two two-step builders, and `ConstraintViolationsClassGenerator.sizeExceeded` with the shared private builder.
- `graphitron-sakila-example`: the fixture below.
- The docs above.

The invariant the emitter relies on is that `ColumnWriteBound.Length` sits only on a cell whose raw map value is the column's text: it emits an `instanceof String` length test on that value with no decode. A NodeId cell wrongly marked `Length` would compare the encoded id against the column. The invariant is held by construction, not by convention: `Length` is minted in exactly one place (`BuildContext.writeBound`), `SetColumn` only copies a decided value, and `Length` carries no extraction for the emitter to second-guess. Pipeline tests 5, 7 and 9 below pin the decision on every arm that could get it wrong.

## Tests

- **Unit**: `JooqCatalog.columnFactsOf` on the sakila catalog gives `film.title` 255, `film.description` (`text`) null, `film.film_id` null, and `language.name` (`char(20)`) 20. The capture test that asserts `sql_column` rows checks `declared_length` for the same columns.
- **Pipeline** (the primary behavioural tier, beside the existing DML carrier classification tests):
  1. An INSERT carrier whose union has a `VALIDATION` member classifies `title`'s cell as `Length(255)` and yields `lengthCheck()` `Checked` with the one cell.
  2. The same field without the member yields `Unrouted`, and exactly one `dml-write-length-unrouted` advisory at the field's coordinate.
  3. The same write on a payload with no errors field yields `None` and no advisory.
  4. An UPDATE carrier keyed on `filmId` and setting `title` has `title` in `writtenCells()` and `filmId` not in it.
  5. On a `varchar` column, each of these classifies as `Unbounded`: an implicit `id` NodeId cell, a same-table `@nodeId` cell, an FK-target `@nodeId` reference cell, and an enum-typed input field. A plain `@reference` with an empty path to a `varchar` column classifies as `Length`.
  6. A DELETE carrier has no written cells, so `None` and no advisory.
  7. A `title` leaf under a `NestingField` is `Length(255)` and carries its nested path, on INSERT (from `fields()`) and on UPDATE (from the `SetColumn`'s `NestedInputField` extraction).
  8. A bulk carrier yields the same `lengthCheck()` as its single-row twin.
  9. The UPDATE `SetColumn` for `title` carries the same `bound` as the `ColumnBackedField` it was derived from, and the key cells projected by `keyColumnsAsSetGroups` are `Unbounded`.
- **Execution** (sakila): add `type FilmWriteInvalid @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }` to `union FilmCreateError`, which `createFilmWithErrors`, `createFilmsWithErrors` and `updateFilmWithErrors` share. The existing tests for those three stay green unchanged. New cases:
  - `createFilmWithErrors` with a 256-character `title` returns one `FilmWriteInvalid` error with `message: "size must be between 0 and 255"` and `path: ["createFilmWithErrors", "in", "title"]`, and the `film` row count is unchanged.
  - `createFilmsWithErrors` with `title` too long on element 1 of 2 returns `path: ["createFilmsWithErrors", "in", "1", "title"]`, and neither row is inserted.
  - `updateFilmWithErrors` with an over-long `title` leaves the row's title unchanged.
  - A `title` of 255 code points outside the Basic Multilingual Plane inserts successfully. This pins the character count against PostgreSQL's own.
- Before the execution cases, re-check on trunk that the unfixed behaviour still matches the report: a 256-character `title` on `createFilmWithErrors` reaches PostgreSQL (`22001`) and comes back redacted. The provenance note says this has not been re-checked since the run that found it.

## Not in this item

- **Numeric precision.** `NUMBER(p, s)` overflow (ORA-1438, one of sis's handled codes) is the obvious sibling. It would add a numeric arm to `ColumnWriteBound`, and precision and scale to `ColumnFacts`. It is deferred until a consumer asks.
- **Direct-return DML and `@service` writes.** The first has no errors union to route into. The second builds its own records, and R747 and R98 cover what a service's inputs can be checked against.
- **Byte-length semantics** (see the fact section above).
- **Introspection.** The client cannot see the limit through the schema. R98's rendered directives are per input type, which is the wrong grain for a per-field column bound, so surfacing it is a separate question if a frontend asks for it.

## Other solutions we've considered

- **Column length as a fourth R98 constraint source.** R98's constraint set is definition-keyed: authored per input type, rendered onto the input type's schema and its `<outputPackage>.inputs` class. A column bound reached through the consuming field's write target is use-keyed, and folding it in would mix the two axes. An input type shared by two mutations against different tables would then get either the stricter limit applied to both or no single answer. (R98 also depends on R92 and is still in Backlog, but that is the weaker reason.) The routing and wire shape here are the ones R98's violations will use, so the two producers meet at the `VALIDATION` channel and the shared error builder, not at a shared model.
- **A `List<WriteLengthBound>` on the field variant.** This was simpler to emit, but the list would re-decide which cells are written, a partition `TableInputArg` and `UpdateRows` already make, and leave two lists that must agree with nothing binding them.
- **Validate a jOOQ record, as 9 did.** This needs the consumer's jOOQ codegen to have `<validationAnnotations>` on, and a Bean Validation provider on their classpath. The DML carriers do not build a record (they bind `DSL.val` cells straight from the argument map), so one would be built only to be validated. The violation would also name a record property, which then has to be mapped back to the SDL path.
- **Read `DataType.length()` at runtime in the emitted code.** This needs no capture, but the build then never knows the bound, so there is no warning and no pipeline-tier assertion, and the model does not carry a rule that the generated code enforces.
- **Always check, falling back to a top-level error without `VALIDATION`.** That is closer to 9's no-type fallback, but it moves errors that consumers catch today with a `DATABASE` handler out of their union (see the warning section).
- **Have consumers declare SDL `@Size` on each input field** (R98 phase 1). This works without this item, but it repeats a fact the catalog already holds, drifts silently when a column is widened, and is per input type.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"; consumer tests include `MutationValidationIT.mutationWithJavaBeanValidationViolationShouldReturnCustomValidationErr`). The run used a pre-R1019 snapshot. R1019 touched tenant binding, not the error channel, so the behaviour is expected to be the same on trunk, but that has not been re-checked.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01LX58XQJwzk4onc6gfDqx78, 2026-10-13)

Question 1 passes. The goal is clear: a payload-returning DML mutation whose errors union has a `VALIDATION` member rejects an over-long string before the statement, as a typed error with an input path, and the build warns where the union cannot receive it. It is reachable. The routing half (`hasValidationHandler`, `ChannelEarlyReturnEmitter.emit`, `ConstraintViolations.toGraphQLError`, rule 7 in `detectStructuralDmlErrorChannel`), the fact half (`JooqCatalog.ColumnFacts`, `sql_column`, `JooqFactCapture.columns`), the advisory surface (`LintRule`, `Source.CLASSIFIER`, `BuildWarning.LintFinding`) and the sakila fixture (`FilmCreateError` shared by the three `*WithErrors` mutations, `film.title varchar(255)`, `language.name char(20)`) all exist as described. The design also fits: bound on the use-keyed write cells, a derived `lengthCheck()` beside the `dialectRequirement()` precedent, reuse of the existing early return and wire shape. The round fails question 2 only in the Implementation notes, which an implementer would have to redesign on contact:

1. **The invariant pin names a retired mechanism.** `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` were deleted with their audit test (see `roadmap/changelog.md`, the entry retiring the annotation pair); nothing in the tree defines them. The invariant matters (a NodeId cell wrongly classified `Length` would length-check the base64 id against the column), so the plan needs to say how it is pinned under the layers that replaced the annotations: type narrowing at the producer, or a pipeline test. Pipeline test 5 may already be that pin; if so, say so and drop the annotation paragraph.

   *Response:* Annotation paragraph replaced. The invariant is now held by construction: `Length` is minted only in `BuildContext.writeBound`, `SetColumn` copies a decided value, and `Length` carries no extraction for the emitter to re-decide. Pipeline tests 5, 7 and 9 pin it, and test 5 now names every arm that could get it wrong (implicit, same-table and FK-target NodeId, enum, plain `@reference`).

2. **The carriers are not built where the plan resolves the bound.** `InputField.ColumnBackedField` is constructed in `BuildContext` (`classifyInputFieldInternal`, `inputFieldFromNodeIdResolved`), the input-field resolution that query-side filter arguments share, not in `FieldBuilder`. `SetColumn` is constructed in `UpdateRowsWalker` (two sites) and minted again at emit time from `KeyColumn` in `TypeFetcherGenerator.keyColumnsAsSetGroups`. `FieldBuilder.columnFacts` is private to `FieldBuilder`. The plan should name where the declared length is read at those sites (`classifyInputField` has the catalog and the `resolvedTable`), and what the emit-time `KeyColumn` projection passes for `bound`.

   *Response:* The decision moved to `BuildContext.writeBound`, called at every construction site of the two `SetField` permits in `classifyInputFieldInternal` and `inputFieldFromNodeIdResolved` (the only main-source sites), with `columnFacts` lifted from `FieldBuilder` into `BuildContext`. `SetColumn` no longer decides: `UpdateRowsWalker` carries the walked carrier's bound on `Contribution` into every `SetColumn` it mints, and `keyColumnsAsSetGroups` passes `Unbounded` because key cells are never written. Implementation list updated to match.

3. **The `Length` predicate and `writtenCells()` miss two carrier shapes in the tree.** (a) An UPDATE leaf under a `NestingField` carries `CallSiteExtraction.NestedInputField(..., leaf = Direct)` in `SetColumn.extraction()`, not `Direct`. As written, the predicate classifies every nested UPDATE string cell `Unbounded`, which defeats test 7. On UPDATE the nested path also has to come from that extraction, since `SetColumn` carries only `sdlFieldName`. (b) `InputField.ColumnBackedReferenceField` is the other `SetField` permit and is admitted as an INSERT value when its binding is `Local`. It gets no `bound` component, so `writtenCells()` has no stated answer for it. Say whether it is `Unbounded` and why, or give it the component. Relatedly, `TableInputArg.setFields()` is always empty today (`TableInputArg.of` is the only constructor call), so the clause "plus UPSERT's `setFields()`, de-duplicated by path" either needs a reason or should go.

   *Response:* (a) Because UPDATE's bound is now decided on the leaf carrier before `UpdateRowsWalker.wrap` adds the `NestedInputField`, a nested string cell keeps its `Length`. `writtenCells()` takes the UPDATE path from `NestedInputField.path()`, and test 7 now covers INSERT and UPDATE. (b) `ColumnBackedReferenceField` gets the component too. `Length` is reachable on it only for a plain `@reference` with an empty path (`Direct`, `Local`); every FK-target arm is NodeId-decoded and so `Unbounded` (test 5). Checking the code also showed that enum inputs arrive as `Direct`, so the enum exclusion now reads the field's GraphQL type rather than the extraction. The `setFields()` clause is gone, with the reason stated in place.

Non-blocking: the lint-docs pointer is hedged, and rightly. No classifier advisory id (`splitquery-redundant-on-record-parent` included) is documented under `docs/manual/` today; `mojo-configuration.adoc` § "Silencing lint warnings" is the closest home.
