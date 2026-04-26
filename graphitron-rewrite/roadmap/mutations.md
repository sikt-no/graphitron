---
title: "Mutation bodies"
status: Spec
priority: 9
---

# Mutation bodies

> Lift all six mutation leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`:
> `MutationField.MutationInsertTableField`, `MutationUpdateTableField`,
> `MutationDeleteTableField`, `MutationUpsertTableField`,
> `MutationServiceTableField`, `MutationServiceRecordField`.
> Closes generator stub #4 — the highest-aggregate production rejection (131 combined).

---

## Current state

### Model

`MutationField.java` (`model/MutationField.java`) declares six permits.
The four DML variants carry only `(parentTypeName, name, location, returnType)` — no classified
arguments, no target-table reference.  The two service variants additionally carry `MethodRef
method` (already populated by `classifyMutationField`).

### Classifier

`FieldBuilder.classifyMutationField` (`FieldBuilder.java:1661`) already routes mutation fields:
`@service` → service variants (constructed at `:1679-1688`); `@mutation(typeName: ...)` → INSERT /
UPDATE / DELETE / UPSERT switch at `:1696`.  For the four DML variants, the classifier resolves
`returnType` but does **not** classify the field's arguments.  The arguments carry the `@table`
input type that identifies the target table and the column bindings — this data is currently
discarded at classify time.

### Generator stubs

`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` has entries for all six variants
(`:224-235`); `generateTypeSpec` routes each to `stub(f)` (`:354-359`).

### Neighbouring references

- `ArgumentRef.InputTypeArg.TableInputArg` — carries `inputTable` (`TableRef`), `fieldBindings`
  (`List<InputColumnBinding>`, `@lookupKey`-bound columns), `fields` (`List<InputField>`, all
  input fields).  Already populated by `FieldBuilder.classifyArgument` (`FieldBuilder.java:779-783`)
  for `@table` input types.  `buildLookupBindings` (`FieldBuilder.java:906`) takes a mutable
  `List<String> errors` and pushes per-field `@lookupKey` validation errors onto it (e.g. lookupKey
  on a list-typed input field, lookupKey on a non-`ColumnField`).  Phase 1b's `classifyMutationInput`
  must thread these errors back into the `UnclassifiedField` reason — losing them would silently
  swallow schema-author mistakes.
- `InputField.ColumnField` — carries `name` (GraphQL field name), `column` (`ColumnRef` with
  `sqlName`, `javaName`, `columnClass`), and `nonNull`.  The `javaName` field is the jOOQ column
  constant (e.g. `CUSTOMER_ID`).
- `InputColumnBinding` — maps a `@lookupKey` input field name to a `ColumnRef` and a
  `CallSiteExtraction`.  This is the same binding the argres pipeline uses for query lookups;
  for mutations it identifies the WHERE-clause columns.
- `GeneratorUtils.ResolvedTableNames` — resolves `tablesClass`, `jooqTableClass`, `typeClass` from
  a `TableRef`.  Used by every SQL-generating emitter.
- `ArgCallEmitter.buildMethodBackedCallArgs(MethodRef, CodeBlock, String)` — declaration-order
  param-list emitter introduced by the service-root-fetchers work (now Done; see roadmap
  Done section). Service-mutation variants (Phases 2 and 5–6) reuse it directly.
- `FieldBuilder.validateRootServiceInvariants(ServiceResolution)` — shared classifier-time
  helper enforcing §1 (Connection rejection) and §2 (no `Sources` parameter at root) on
  both `@service` query and `@service` mutation arms. Already wired into `classifyMutationField`
  by service-root-fetchers (Done); the mutation-bodies plan inherits these checks for free.
- `MethodRef.Basic.returnType()` — structured javapoet `TypeName` captured at reflection time
  (was a string FQCN before service-root-fetchers landed). Mutation-`@service` strict-return
  validation already routes through `FieldBuilder.computeExpectedServiceReturnType` for the
  `parentPkColumns.isEmpty()` path; only the emitter side remains for Phase 6.

---

## Invariants

1. **Each DML mutation has exactly one `@table` input argument.**  This is the record that
   drives the DML statement.  If no `@table` input arg is found during classification, produce
   `UnclassifiedField` with a descriptive reason.  Multiple `@table` input args are also
   rejected at classifier time (`"@mutation field has more than one @table input argument"`).

2. **UPDATE and DELETE require at least one `@lookupKey` binding.**  `fieldBindings` must be
   non-empty; if empty, return `UnclassifiedField` with
   `"@mutation(typeName: UPDATE/DELETE) requires at least one @lookupKey field in the input type"`.

3. **UPSERT requires at least one `@lookupKey` binding** (the ON CONFLICT key), same gate as #2.

4. **UPDATE requires at least one non-`@lookupKey` field.**  If every `ColumnField` in `tia.fields()`
   has its name in `tia.fieldBindings()`, the SET clause would be empty and the statement degenerates.
   Gate at classifier time: `"@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"`.
   UPSERT is exempt: an UPSERT whose SET clause is empty is semantically `INSERT ... ON CONFLICT DO NOTHING`,
   which is a legitimate pattern; UPSERT's non-empty-SET check is therefore not enforced here.

5. **INSERT does not require `@lookupKey`.**  `fieldBindings` may be empty for INSERT.

6. **INSERT column coverage is a runtime contract, not a classifier invariant.**  The classifier
   does **not** verify that every NOT-NULL, no-default column of the target table is covered by an
   input field.  The target DB rejects the INSERT at execute time if coverage is incomplete; this
   is the declared contract.  Rationale: computing "NOT-NULL without default" from the jOOQ catalog
   at classify time is feasible but non-trivial (default metadata is not always preserved through
   the generator) and the DB error is actionable.  A follow-up item may promote this to a classifier
   gate once the catalog metadata is reliably available; tracked in Non-goals.

7. **Nested `@table` input fields (`InputField.NestingField`) are deferred.**  If any field in
   `tia.fields()` is a `NestingField`, emit an `UnclassifiedField` at classify time
   (`"nested input types in @mutation fields are not yet supported"`).  This keeps the initial
   scope flat; nesting can land in a follow-up.

8. **`InputField.NodeIdField` in a mutation input is deferred.**  Same gate:
   `"NodeIdField in @mutation inputs is not yet supported"`; the binding-time NodeId decode
   lands as part of argres Phase 3 (`InputColumnBinding` with a `NodeIdBinding` variant).

9. **`InputField.ColumnReferenceField` (cross-table reference) in a mutation input is deferred.**
   Gate: `"ColumnReferenceField in @mutation inputs is not yet supported"`.

10. **`InputField.NodeIdReferenceField` (cross-table NodeID reference) in a mutation input is
    deferred.**  Same rationale as #9 — cross-table refs would need a join-and-resolve step on the
    write side.  Gate: `"NodeIdReferenceField in @mutation inputs is not yet supported"`.  Full set
    of currently-allowed `InputField` variants in a mutation `@table` input is therefore
    `ColumnField` only; everything else (`NestingField`, `NodeIdField`, `NodeIdReferenceField`,
    `ColumnReferenceField`) is gated.

11. **Listed inputs (`in: [CustomerInputTable]`) are deferred for Phase 1.**  The initial
    implementation handles only single-record inputs.  Listed-input support is a follow-up
    (see Non-goals).

12. **`@condition` on a DML `@mutation` input argument is not supported.**  If
    `tia.argCondition().isPresent()` after `buildLookupBindings`, return `UnclassifiedField` with
    `"@condition on a @mutation field argument is not supported"`.  `@condition` is a query-side
    directive whose projection into WHERE clauses is mediated by condition-method call sites;
    DML mutations emit inline jOOQ predicates directly from `fieldBindings` and have no
    concept of a condition method to invoke.  Gate: `classifyMutationInput` Pass 2.

13. **Non-`TableInputArg` arguments on DML `@mutation` fields are rejected.**  `classifyMutationInput`
    (see Phase 1b) returns `UnclassifiedField` if any argument is anything other than a
    `TableInputArg`, including `PlainInputArg`, scalar args, pagination args, orderBy args.
    Mutations have no parent table context, so those argument shapes are nonsensical here and
    would mis-classify against a sentinel `TableRef` if fed through the generic classifier.
    Message: `"@mutation fields only accept @table input arguments; found '<argName>' of shape '<ArgumentRef variant>'"`.

    **Phase 1 compatibility check (gate before landing):** the legacy classifier silently skipped
    `PlainInputArg` on mutations (unless paired with `@condition`).  Before Phase 1 merges, grep
    production schemas for DML `@mutation` fields whose argument type is an input object without
    `@table` — e.g. a request-metadata struct carrying no column bindings.  If any exist, this
    is a breaking change, not a stub lift: either (a) admit a narrow `PlainInputArg` carve-out
    alongside the single `TableInputArg` (mirrors the legacy skip), or (b) migrate the schema
    authors to move those fields onto the `@table` input type.  Record the count and decision in
    the Phase 1 landing commit.

14. **DML `@mutation` return type must be `ID` (Single or List), `T` (Single of
    `TableBoundReturnType`), or `[T]` (List of `TableBoundReturnType`).**  Other shapes (`Int`,
    `Boolean`, any non-`ID` scalar, `PolymorphicReturnType`, `ResultReturnType`, or `Connection`
    wrapper) are rejected at classifier time with
    `"@mutation(typeName: INSERT|UPDATE|DELETE|UPSERT) return type '<T>' is not yet supported; use ID or a @table type"`.
    Rationale: `.returningResult(...)` cannot honestly express an affected-row count (the semantics
    users typically expect from `Int` / `Boolean` returns), and polymorphic / result-record returns
    would require projection bridges we have no fixture for.  Service-mutation variants (Phase 6)
    accept a wider return-type range via the developer-supplied method's return type; this
    invariant applies only to the four DML variants.  `buildMutationReturnExpression` in Phase 2
    assumes this gate is enforced.

    **Phase 1 numbers check (gate before landing):** the roadmap's 131-count aggregate comes from
    the legacy generator, which accepted `Boolean` / `Int` via affected-row semantics.  Before
    Phase 1 merges, sample the SDLs producing those rejections and confirm that the majority
    return `ID`, `[ID]`, `T`, or `[T]`.  If a material slice returns `Int` / `Boolean`, the stub
    lift does not close those rejections — it swaps them for a new rejection class, and the
    `.execute()`-based arm (see Non-goals) needs to land in the same pass instead of being
    deferred.  Record the sample-size and return-type breakdown in the Phase 1 landing commit.

---

## Plan

### Phase 1 — Model extension + classifier

**Goal:** populate the DML variants with the data the emitter needs.  No emission changes.

#### 1a. Extend the four DML `MutationField` variants and add a `DmlTableField` supertype

Add `TableInputArg tableInputArg` and `Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta` to each
record, and introduce a sealed intermediate `MutationField.DmlTableField` so Phase 2's
`buildMutationReturnExpression` can dispatch over a single supertype:

```java
sealed interface DmlTableField extends MutationField
        permits MutationInsertTableField, MutationUpdateTableField,
                MutationDeleteTableField, MutationUpsertTableField {
    ReturnTypeRef returnType();
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
    Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta();  // present only when returnType is ScalarReturnType(ID)
    SourceLocation location();
}

record MutationInsertTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta
) implements DmlTableField {}

// Same addition on MutationUpdateTableField, MutationDeleteTableField, MutationUpsertTableField.
```

The supertype is purely additive: no downstream code reads `MutationField` exhaustively today
that would care about a new sealed level.  Introducing it here (rather than in Phase 2 as the
earlier draft did) keeps Phases 2–5 wiring uniform from day one and avoids an interleaved record
edit during emission work.

#### 1b. Extend `classifyMutationField`

Inside the existing `if (typeName != null)` gate at `FieldBuilder.java:1693`, before the
`switch (typeName)` at `:1696`, add a `TableInputArg` extraction step.  Placement matters: a
missing or malformed `@mutation()` directive leaves `typeName == null` and must continue to
fall through to the existing "both absent" error at `:1707-1708`; do not run the extraction
outside this gate.

```java
// Find the single @table input argument; reject any other argument shape.
var tiaOrError = classifyMutationInput(fieldDef, typeName /* INSERT|UPDATE|DELETE|UPSERT */);
if (tiaOrError.error() != null) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
        RejectionKind.AUTHOR_ERROR, tiaOrError.error());
}
ArgumentRef.InputTypeArg.TableInputArg tia = tiaOrError.value();
```

(The `UnclassifiedField` constructor takes a `RejectionKind` between `fieldDef` and the
reason string; see the existing call at `FieldBuilder.java:1707-1708`.  Use `AUTHOR_ERROR` for
schema-author mistakes — missing/duplicate `@table`, deferred input variants, return-type
violations.  Use `INVALID_SCHEMA` only for hard schema-validity violations like the existing
`@service` + `@mutation` mutual-exclusion check.)

**`classifyMutationInput` is a purpose-built helper, not a caller of the existing
`classifyArgument`.**  `classifyArgument(FieldBuilder.java:749)` takes a `TableRef rt` and uses
it to bind un-directived scalar args against the parent table's columns (`:822-823`); a mutation
field has no parent `@table` (its parent is `Mutation`), so threading a sentinel would mis-bind.
Instead, `classifyMutationInput` walks `fieldDef.getArguments()` directly in two staged passes:

**Pass 1 — shape gate (Invariants #1, #11).**  For each argument, unwrap its GraphQL type to a
named type and look up `ctx.types.get(typeName)`.  If the resolved type is a
`GraphitronType.TableInputType`, build a `TableInputArg` via the same path `classifyArgument`
uses at `:781-782` (`buildLookupBindings` + `tit.inputFields()`).  Also populate `argCondition`
from the argument's SDL directives using the same `@condition` directive extraction that
`classifyArgument` applies; without this, Pass 2's `tia.argCondition().isPresent()` check for
Invariant #12 will never fire.  Allocate a fresh `List<String> bindingErrors` per call and pass
it into `buildLookupBindings`; if non-empty after the call, join them with `"; "`
(`String.join("; ", bindingErrors)`) as the rejection reason and return a `MutationInputResult`
error (don't drop them silently).  Collect the `TableInputArg` on success.  Otherwise, record a
rejection per Invariant #13.  At the end of Pass 1:
- Zero `TableInputArg` found → error "no `@table` input argument found on `@mutation` field".
- Two or more found → error per Invariant #1.
- Any non-`TableInputArg` argument → error per Invariant #13.

**Pass 2 — invariant checks on the single `tia`.**  Only reached when Pass 1 produced exactly one
`TableInputArg`:
1. `tia.list()` true → error per Invariant #11.
2. `tia.argCondition().isPresent()` → error per Invariant #12.
3. Any `tia.fields()` entry is `NestingField` / `NodeIdField` / `NodeIdReferenceField` /
   `ColumnReferenceField` → error per Invariants #7–10.  `ColumnField` is the only
   permitted variant in Phase 1.
4. UPDATE / DELETE / UPSERT: `tia.fieldBindings()` empty → error per Invariants #2–3.
5. UPDATE: every `ColumnField` in `tia.fields()` has its name in `tia.fieldBindings()` →
   error per Invariant #4.

Return shape: a file-private record inside `FieldBuilder`:
`record MutationInputResult(ArgumentRef.InputTypeArg.TableInputArg value, String error) {}`.
Carrying either a resolved `tia` with `null` error, or `null` value with a non-null error string.

**Return-type validation ordering.**  After resolving `tia`, validate `returnType` against
Invariant #14 before entering the `switch (typeName)`.  This ordering ensures that an
input-shape error surfaces ahead of a return-type error when both are present (input errors are
more actionable to the schema author).

**NodeId metadata resolution (scoped to `ScalarReturnType(ID)` only).**  After the return-type
check, if and only if `returnType` is `ScalarReturnType("ID")`, call
`ctx.catalog.nodeIdMetadata(tia.inputTable().sqlName())`.  `JooqCatalog.NodeIdMetadata` carries
`(String typeId, List<ColumnRef> keyColumns)` — the type identifier and all key columns, exactly
the data needed to call `NodeIdEncoder.encode(typeId, r.get(col1), r.get(col2), ...)` in the
emitter.  If the metadata is absent (the table carries no `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`
constants, i.e. it is not a `@node` type), reject with `UnclassifiedField` reason:
`"@mutation field '<name>' returns ID but table '<tableName>' is not a @node type; annotate the type with @node or use a @table return type"`.
Set `nodeIdMeta = Optional.of(meta)` on success.  For all other return types
(`TableBoundReturnType`, etc.), set `nodeIdMeta = Optional.empty()` — no catalog lookup is
performed and composite-key tables are fully supported via `$fields` projection.

Then the `switch (typeName)` passes `tia` and `nodeIdMeta` to each constructor.

#### 1c. Validator additions

`validateMutationInsertTableField`, `validateMutationUpdateTableField`,
`validateMutationDeleteTableField`, `validateMutationUpsertTableField` are all currently empty
stubs.  They stay empty after Phase 1 — the classifier rejects bad shapes via `UnclassifiedField`
before validation is invoked.

#### 1d. Pipeline tests

`GraphitronSchemaBuilderTest` additions (no emission yet, just classifier assertions):

| SDL shape | Expected outcome |
|---|---|
| `createFilm(in: FilmInput): ID @mutation(typeName: INSERT)` where `FilmInput @table(name: "film")` | `MutationInsertTableField(tableInputArg.inputTable = TableRef("film", "FILM", "Film", [...]))` |
| `updateFilm(in: FilmInput): ID @mutation(typeName: UPDATE)` with `@lookupKey` on `FilmInput.id` | `MutationUpdateTableField` with `fieldBindings` non-empty |
| `updateFilm` with no `@lookupKey` in input | `UnclassifiedField` with Invariant #2 message |
| `updateFilm` where every input field is `@lookupKey` | `UnclassifiedField` with Invariant #4 message |
| `deleteFilm(in: FilmInput): ID @mutation(typeName: DELETE)` with `@lookupKey` | `MutationDeleteTableField` |
| `upsertFilm(in: FilmInput): ID @mutation(typeName: UPSERT)` with `@lookupKey` | `MutationUpsertTableField` |
| Any DML variant with `NestingField` in input | `UnclassifiedField` with Invariant #7 message |
| Any DML variant with `NodeIdField` in input | `UnclassifiedField` with Invariant #8 message |
| Any DML variant with `NodeIdReferenceField` in input | `UnclassifiedField` with Invariant #10 message |
| Any DML variant with `ColumnReferenceField` in input | `UnclassifiedField` with Invariant #9 message |
| DML variant with two `@table` input arguments | `UnclassifiedField` with Invariant #1 message |
| DML variant with a plain (non-`@table`) input arg | `UnclassifiedField` with Invariant #13 message |
| DML variant with listed input (`in: [FilmInput]`) | `UnclassifiedField` with Invariant #11 message |
| DML variant whose `@table` input arg carries `@condition` | `UnclassifiedField` with Invariant #12 message |
| DML variant with `@lookupKey` on a list-typed input field (e.g. `ids: [ID!]! @lookupKey`) | `UnclassifiedField` whose reason includes the `buildLookupBindings` per-field error |
| DML variant with non-`ID`/non-`T` return (e.g. `: Int`, `: Boolean`, `Connection<T>`) | `UnclassifiedField` with Invariant #14 message |
| DML variant targeting a `@node` table with composite key and `ScalarReturnType(ID)` return | classified successfully; `nodeIdMeta.keyColumns()` carries both key columns |
| DML variant targeting a non-`@node` table with `ScalarReturnType(ID)` return | `UnclassifiedField` with "not a @node type" message |
| DML variant targeting a non-`@node` (or composite-key) table with `TableBoundReturnType` return | classified successfully (`nodeIdMeta` is `Optional.empty()`; no node restriction applies) |
| `@service` + `@mutation` together | `UnclassifiedField` (existing check at `FieldBuilder.java:1665-1668`) |
| `@mutation` with no `typeName` argument | `UnclassifiedField` with existing "both absent" message — Phase 1b's `classifyMutationInput` must not fire before this fallthrough |

---

### Phase 2 — INSERT emission

**Goal:** emit a real fetcher for `MutationInsertTableField`.

#### Shape of emitted method

```java
public static Object createFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .insertInto(Tables.FILM, Tables.FILM.FILM_ID, /* … more columns … */)
        .values(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType()), /* … */)
        .returningResult(/* return expression */)
        .fetchOne(r -> r.get(/* return column */));
}
```

- **Return type of the emitted Java method**: always `Object`.  This mirrors the existing
  `QueryServiceRecordField` / stubbed-mutation precedent and sidesteps the ID-coercion mismatch:
  graphql-java's `ID` scalar accepts any `Object` and coerces to `String` via `toString`, so an
  emitted `Long` PK flows through the `ID` scalar without a conversion.  `Record` /
  `Result<Record>` similarly flow without signature churn.  The Javadoc on each emitter should
  note that the concrete runtime type matches the declared SDL return shape.
- **Column list**: every `InputField.ColumnField` in `tia.fields()`, in declaration order.
  `@lookupKey` fields are included — INSERT does not treat them specially.
- **Values list**: parallel to the column list.  Each value is
  `DSL.val(in.get("<sdlFieldName>"), Tables.T.<col.javaName()>.getDataType())` where `sdlFieldName`
  is `InputField.ColumnField.name()` and `col.javaName()` is `ColumnRef.javaName()` (the jOOQ
  field-constant name, e.g. `FILM_ID`).  The two-argument form delegates coercion to the column's
  registered `Converter` at bind time — no Java-side cast, no explicit type check.  Enum-typed
  columns receive a `String` from graphql-java and are coerced by the column's `DataType`; `ID`-typed
  columns receive a `String` and are coerced to the PK Java type.  See the "Column value binding"
  convention in `rewrite-design-principles.md`.
- **Return expression** (see below).

#### Return expression: `buildMutationReturnExpression`

Shared helper used by Phases 2–5.  Signature (private static on `TypeFetcherGenerator`):

```java
private static ReturnBlock buildMutationReturnExpression(
    MutationField.DmlTableField f,   // sealed common supertype of the four DML variants; see note
    ResolvedTableNames names
);

record ReturnBlock(CodeBlock returningResult, CodeBlock terminalCall) {}
```

The caller splices `returningResult` into `.returningResult($L)` and `terminalCall` into the
final position (`.fetchOne($L)` or `.fetch($L)`).

Dispatch over `f.returnType()`:

| `ReturnTypeRef` | `returningResult` | `terminalCall` |
|---|---|---|
| `ScalarReturnType` with SDL type `ID`, wrapper `Single` | all `nodeIdMeta.keyColumns()` as varargs: `Tables.T.COL1, Tables.T.COL2, ...` | `.fetchOne(r -> NodeIdEncoder.encode(typeId, r.get(Tables.T.COL1), r.get(Tables.T.COL2), ...))` |
| `ScalarReturnType` with SDL type `ID`, wrapper `List` | same key-column varargs | `.fetch(r -> NodeIdEncoder.encode(typeId, r.get(Tables.T.COL1), ...))` |
| `ScalarReturnType` with SDL type `Int`/`Boolean`/other non-`ID` | **Reject at classifier time, Phase 1.** | (n/a) |
| `TableBoundReturnType`, wrapper `Single` | `<Type>.$fields(env.getSelectionSet(), table, env)` | `.fetchOne(r -> r)` |
| `TableBoundReturnType`, wrapper `List` | same | `.fetch(r -> r)` |
| `TableBoundReturnType`, wrapper `Connection` | **Reject at classifier time, Phase 1.** | (n/a) |
| `PolymorphicReturnType` | **Reject at classifier time, Phase 1.** | (n/a) |
| `ResultReturnType` | **Reject at classifier time, Phase 1.** | (n/a) |

The `[ID]` and `[T]` List rows are exercised mainly by UPSERT / batch UPDATE over `@lookupKey`
matching multiple rows.  Phase 1's listed-input gate (Invariant #11) still applies to the input
side; the return side is independent and does admit List wrappers today.

The four "reject at classifier time" rows are enforced by Invariant #14.  This supersedes
an earlier sentinel approach (emit `DSL.val(1)` for non-`ID` scalar returns): the sentinel's
semantics differ between INSERT (always 1) and UPDATE/DELETE (affected-row count), and
`returningResult(DSL.val(1))` cannot express either honestly.  Future demand for `Int` returns
will be met by extending this helper with an `.execute()`-based arm (see Non-goals).

Note on the `DmlTableField` supertype: introduced in Phase 1a (see above) so this helper has a
stable supertype to dispatch over from day one.  The four DML variants implement it with the
four accessors the helper reads (`returnType`, `tableInputArg`, `nodeIdMeta`, `location`).
`nodeIdMeta` is `Optional.of(meta)` for `ScalarReturnType(ID)` returns and `Optional.empty()`
otherwise; the `ScalarReturnType(ID)` arm calls `.orElseThrow()` (safe: the classifier
guarantees presence when this arm is reached).  New DML variants pick up the helper for free.

Scalar `ID` returns call the locally-emitted `NodeIdEncoder.encode(typeId, keyCol1Value, ...)`,
mirroring exactly how `FetcherEmitter` encodes child `NodeIdField`s.  `nodeIdMeta.keyColumns()`
may carry one column (simple key) or several (composite key); `NodeIdEncoder.encode` is varargs
so both shapes emit identical code patterns.  The `nodeIdMeta` is populated from
`JooqCatalog.nodeIdMetadata(tableSqlName)` at classification time using the same catalog path
that `ChildField.NodeIdField` uses.

**`RETURNING` with nested selections (`TableBoundReturnType`):** `Type.$fields` emits
`DSL.multiset(...)` for child `TableField`/`NestingField` selections, which run as correlated
subqueries.  PostgreSQL supports subqueries inside `RETURNING`, so this shape works in principle,
but no existing Graphitron fixture exercises `INSERT ... RETURNING (multiset)`, so Phase 2's
execution tests must include at least one case that selects a nested child on the returned record
(e.g. `createFilm { title, language { name } }`).  If PostgreSQL rejects the shape, fall back to
"RETURNING PK only, then a follow-up SELECT from the same `dsl`"; document the choice in the
phase's landing commit.

#### Implementation sites

- New `buildMutationInsertFetcher(MutationInsertTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm at `TypeFetcherGenerator.java:354` changes from `stub(f)` to the new emitter call.
- Remove `MutationInsertTableField` from `NOT_IMPLEMENTED_REASONS` (line 212) and add it to
  `IMPLEMENTED_LEAVES` (line 142); both maps must change in the same commit or `GeneratorCoverageTest`
  will fail the disjoint-partition check.

#### Tests

**Pipeline** (`GraphitronSchemaBuilderTest` or a dedicated `MutationInsertPipelineTest`):
- Emitted method is present and named after the field.
- Method signature is `public static <ReturnType> createFilm(DataFetchingEnvironment env)`.
- Body contains `insertInto` and `returningResult`.

**Execution** (new `GraphQLMutationTest` in `graphitron-test`):
- **Fixture gap**: `graphitron-test/src/main/resources/graphql/schema.graphqls` currently
  has no `Mutation` type.  Phase 2 adds the first one using `type Mutation { ... }`.  Phases 3–6
  add fields using `extend type Mutation { ... }` — not a second `type Mutation` declaration.
  This mirrors
  the fixture gap that the service-root-fetchers work (Done) closed for `@service` / `@tableMethod`
  via `SampleQueryService` and the three new Query fields.
- Fixture SDL: `type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT) }`
  with `input FilmInput @table(name: "film") { title: String!, languageId: Int!, rentalDuration: Int!, rentalRate: Float!, replacementCost: Float! }`.
  Sakila's `film` table has several NOT-NULL columns without defaults (`language_id`,
  `rental_duration`, `rental_rate`, `replacement_cost`); the fixture must cover them or the
  INSERT fails at execute time per Invariant #6.
- Test: calls `createFilm(in: {title: "Test", languageId: 1, rentalDuration: 3, rentalRate: 4.99, replacementCost: 19.99})`,
  asserts returned ID matches the inserted film's PK column value.
- Nested-selection test: `createFilm(...) { title, language { name } }` against a
  `TableBoundReturnType`-returning variant to exercise `RETURNING (multiset)` per the note above.
- Compile gate: `mvn compile -pl :graphitron-test -Plocal-db`.

(Negative `Invariant #14` cases — `Int` / `Boolean` / `Connection<T>` returns — are exercised at
the classifier level in Phase 1d's pipeline tests; no execution-test repeat is needed here.)

---

### Phase 3 — DELETE emission

DELETE is implemented before UPDATE because DELETE's emitter is a strict subset of UPDATE's shape (WHERE clause only, no SET clause); getting the simpler WHERE-only path working first reduces the surface area for Phase 4.

#### Shape of emitted method

```java
public static Object deleteFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .deleteFrom(Tables.FILM)
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> r.get(Tables.FILM.FILM_ID));
}
```

- **WHERE clause**: each `InputColumnBinding` in `tia.fieldBindings()` contributes one
  `.eq(value)` predicate, combined with `DSL.and(...)`.
- **Return expression**: same rules as INSERT above.

When `tia.fieldBindings()` has a single binding, emit `.where(col.eq(val))`.
When multiple, emit `.where(col1.eq(v1).and(col2.eq(v2)))`.
In both cases `val` is `DSL.val(in.get(binding.inputFieldName()), Tables.T.COL.getDataType())`
— the two-argument form that delegates coercion to the column's `Converter` (see "Values list"
description in Phase 2 and the "Column value binding" convention in `rewrite-design-principles.md`).

The same `buildMutationReturnExpression` helper (introduced in Phase 2) is reused.

#### Implementation sites

- New `buildMutationDeleteFetcher(MutationDeleteTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm changes from `stub(f)` to the new emitter call.
- Remove `MutationDeleteTableField` from `NOT_IMPLEMENTED_REASONS` (line 212) and add it to
  `IMPLEMENTED_LEAVES` (line 142) in the same commit.

#### Empty-match semantics

When the WHERE clause matches no row, `.fetchOne(...)` returns `null`.  The emitted method's
`Object` return flows that `null` through graphql-java unchanged.  For a nullable `ID` return
type, this surfaces as GraphQL null; for `ID!`, graphql-java raises a non-null violation at the
protocol layer.  This is the declared contract — schema authors using `ID!` on UPDATE/DELETE
are asserting that the WHERE clause always matches, and the protocol-layer error is the right
signal when it doesn't.  Same applies to Phase 4.

#### Tests

Pipeline and execution tests following the same pattern as Phase 2.
Execution test: `deleteFilm(in: {filmId: 1})` — asserts the film is gone from the DB afterward
and the returned ID matches.
Negative execution test: `deleteFilm(in: {filmId: 99999})` on a nullable `ID` return — asserts
`null` response.

---

### Phase 4 — UPDATE emission

#### Shape of emitted method

```java
public static Object updateFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .update(Tables.FILM)
        .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> r.get(Tables.FILM.FILM_ID));
}
```

- **SET clause**: every `InputField.ColumnField` in `tia.fields()` whose `name()` is **not** in
  any `tia.fieldBindings()` entry's `inputFieldName()` (i.e. not `@lookupKey`-bound) contributes
  one `.set(col, val)`.  Invariant #4 guarantees at least one such field.
- **WHERE clause**: `fieldBindings()` entries, same as DELETE.
- **Return expression**: `buildMutationReturnExpression` (Phase 2).  The empty-SET classifier
  gate lives in Phase 1 per Invariant #4.
- **Empty-match semantics**: same as DELETE (see Phase 3); `.fetchOne(...)` returns `null` when
  the WHERE clause matches no row.

#### Implementation sites

- New `buildMutationUpdateFetcher(MutationUpdateTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm changes from `stub(f)` to the new emitter call.
- Remove `MutationUpdateTableField` from `NOT_IMPLEMENTED_REASONS` (line 212) and add it to
  `IMPLEMENTED_LEAVES` (line 142) in the same commit.

#### Tests

Execution test: `updateFilm(in: {filmId: 1, title: "Updated"})` — asserts the title changed and
the returned ID is 1.

---

### Phase 5 — UPSERT emission

#### Shape of emitted method

jOOQ supports `INSERT ... ON CONFLICT ... DO UPDATE SET ...` via:

```java
dsl.insertInto(Tables.FILM, cols...)
   .values(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType()), /* … */)
   .onConflict(Tables.FILM.FILM_ID)   // @lookupKey columns
   .doUpdate()
   .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
   .returningResult(/* … */)
   .fetchOne(/* … */);
```

- **`onConflict` columns**: `fieldBindings()` entries.
- **Conflict action**: if there are non-`@lookupKey` fields (i.e. `tia.fields()` has at least one
  `ColumnField` whose `name()` is not in any `fieldBindings()` entry), emit `.doUpdate().set(...)`.
  If every field is `@lookupKey` (no SET clause fields), emit `.doNothing()` instead.  jOOQ does
  not accept a `doUpdate()` with zero `.set(...)` calls; `doNothing()` is the required jOOQ API
  path for `INSERT ... ON CONFLICT DO NOTHING`.  Invariant #4's exemption of UPSERT is the basis
  for this branch: an all-`@lookupKey` UPSERT is a legitimate `DO NOTHING` operation.
- **`doUpdate().set(...)` columns**: non-`@lookupKey` fields, same as UPDATE (only reached when
  the branch above chose `doUpdate()`).
- **INSERT columns**: every `InputField.ColumnField` in `tia.fields()`, in declaration order —
  identical to Phase 2's INSERT column list.  This explicitly includes any `@lookupKey` fields
  (they supply the user-provided PK on the "insert" branch of the UPSERT).  Auto-generated PKs
  that schema authors don't expose in the input type never produce a `ColumnField` and are
  therefore absent from the INSERT column list, which is the correct shape for `ON CONFLICT`.
- **Return expression**: same helper.

If the jOOQ table's dialect does not support `INSERT ... ON CONFLICT`, this will fail at runtime
on non-PostgreSQL databases; document this in the plan and accept it for now (the rewrite targets
PostgreSQL).

#### Implementation sites

- New `buildMutationUpsertFetcher(MutationUpsertTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm changes from `stub(f)` to the new emitter call.
- Remove `MutationUpsertTableField` from `NOT_IMPLEMENTED_REASONS` (line 212) and add it to
  `IMPLEMENTED_LEAVES` (line 142) in the same commit.

#### Tests

Execution test: `upsertFilm(in: {filmId: 1, title: "Upserted"})` — asserts correct insert-or-update
semantics.

---

### Phase 6 — Service mutations

**Prerequisite (satisfied).** Service-root-fetchers (Done) introduced
`ArgCallEmitter.buildMethodBackedCallArgs`, the declaration-order param-list helper this phase
reuses verbatim. It also wired `validateRootServiceInvariants` (§1 / §2) and the strict
`@service` return-type check into the mutation arm of `classifyMutationField`, so this phase
inherits classifier-time rejection for connection returns, batch-keyed parameters, and
mismatched return types without further work.

**Synchronous, no DataLoader.**  Both service-mutation variants emit synchronous methods — same
as root service queries.  Root mutation fields have no parent-batching context; per-request
concurrency is irrelevant, and the developer-supplied method owns any transaction scope.

#### `MutationServiceTableField`

Same shape as `QueryServiceTableField` from the service-root-fetchers plan.  The developer method
returns a `Result<Record>` (list) or `Record` (single).  The generator emits a synchronous
method identical to `buildQueryServiceTableFetcher` — there is no special write semantics because
the service owns all SQL, including any transactions:

```java
public static Result<Record> activeRentals(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    return RentalService.createRentals(
        dsl,
        env.getArgument("customerId")
    );
}
```

Implementation: add `buildMutationServiceTableFetcher(MutationServiceTableField, ...)` in
`TypeFetcherGenerator`.  Call `buildServiceFetcherCommon` directly (the same shared helper that
`buildQueryServiceTableFetcher` delegates to) rather than going through the query-flavoured
wrapper; the two variants have identical record shapes and the common helper already handles both.

#### `MutationServiceRecordField`

The classifier at `FieldBuilder.java:1682-1685` maps this variant to two distinct
`ReturnTypeRef` shapes, both of which this emitter must handle:

- **`ReturnTypeRef.ScalarReturnType`** — scalar (e.g. `Int`, `String`, `Boolean`), plain DTO,
  or any non-table non-record Java type.  Emitted body: `return SomeService.method(...);` with
  return type `Object`.  graphql-java coerces to the declared SDL type.
- **`ReturnTypeRef.ResultReturnType`** — a `@record`-annotated GraphQL type backed by a jOOQ
  `Record` subclass.  The service returns the record directly; graphql-java's registered
  property/record fetchers walk its fields.  No projection.

Both shapes share the same argument-list emission (`buildMethodBackedCallArgs` over
`method().params()`, with `DslContext` / `Arg` / `Context` expressions) and both compile against
`Object` as the method signature.  Add `buildMutationServiceRecordFetcher(MutationServiceRecordField, ...)`
with an internal switch on the `ReturnTypeRef` variant only if emission actually diverges (it
currently doesn't — the only observable difference is in graphql-java's coercion path, not the
generator output).

Both service variants: remove from `NOT_IMPLEMENTED_REASONS` (line 212) and add to
`IMPLEMENTED_LEAVES` (line 142) in the same commit (`GeneratorCoverageTest` enforces the
disjoint partition).  Both use the shared `buildMethodBackedCallArgs` helper.  Pass `null` for
`tableExpression` — root mutation
fields have no parent table context, so no `ParamSource.Table` slot will be present in the
method's param list.  The emitter already handles `null` there for root service queries.

#### Tests

Pipeline tests: one case per variant asserting the emitted method calls the service method and
does not emit `$fields`.
Execution tests: a fixture service method on the mutation type; assert the returned value flows
through graphql-java's registered fetchers.

---

## Non-goals

- **Listed inputs** (`in: [FilmInput]`): the `list` flag on `TableInputArg` causes a classifier
  gate (Invariant #11).  Unblocking listed inputs requires iterating over the input list and
  constructing a batch INSERT / batch UPDATE / DELETE / UPSERT.  The legacy code uses jOOQ
  `batchInsert` / `batchUpdate` / `batchDelete` / `batchStore` for this.  Tracked as a follow-up.
- **Nested input types** (`NestingField`): deferred per Invariant #7.
- **`InputField.NodeIdField` in mutation inputs**: deferred to argres Phase 3's
  `NodeIdBinding` variant of `InputColumnBinding` (Invariant #8).
- **`ColumnReferenceField` in mutation inputs**: deferred (Invariant #9).
- **Non-`TableInputArg` arguments on DML fields**: deferred (Invariant #13).  A future plan could
  admit scalar context arguments alongside the `@table` input (e.g. a `reason: String` audit field
  routed to a column not in the input type); today we reject them rather than invent a precedence
  story.
- **Build-time INSERT column-coverage validation** (Invariant #6): deferred until the jOOQ
  catalog reliably exposes NOT-NULL + default metadata through the generator's `JooqCatalog`
  layer.  Today the DB rejects incomplete INSERTs at execute time; the rewrite does not try
  to beat the DB to the punch.  Promote to Active if three or more production rejections trace
  back to runtime incomplete-INSERT errors that a classifier check would have caught cheaply.
- **Non-`ID` / non-`TableBoundReturnType` return types on DML fields** (Invariant #14): deferred.
  The previous position (emit `DSL.val(1)` as an affected-row sentinel) is withdrawn: the
  semantics differ between INSERT (always 1) and UPDATE/DELETE (actual affected count), and
  `returningResult(DSL.val(1))` cannot express either honestly.  When a consumer needs an `Int`
  return, a follow-up plan will extend `buildMutationReturnExpression` with an arm that uses
  `.execute()` (returns affected-row count) and skips `returningResult` entirely.
- **`ScalarReturnType(ID)` on non-`@node` tables**: gated at classifier time with a descriptive
  message.  A follow-up could add a raw-PK fallback (no NodeId encoding) for tables that lack
  `__NODE_TYPE_ID` metadata, but the common case is that mutation target tables are node types.
- **Transaction wrapping**: the emitted DML statements run within whatever transaction context
  the caller provides via `dsl`.  Explicit transaction wrapping (e.g. `dsl.transactionResult(...)`)
  is not added here; service variants already control their own transactions through the
  developer-supplied method.
- **Non-PostgreSQL dialects**: `RETURNING` and `ON CONFLICT DO UPDATE` are PostgreSQL-specific.
  The rewrite targets PostgreSQL; no cross-dialect abstraction is added.
- **`@mutation` + `@service` combination**: already rejected at classifier time
  (`FieldBuilder.java:1665-1668`).

---

## Open decisions

- **`RETURNING (multiset)` on PostgreSQL for `TableBoundReturnType` returns.** Phase 2's
  execution test must exercise at least one nested-selection return path.  If PostgreSQL rejects
  `INSERT ... RETURNING (multiset_subquery)`, fall back to "RETURNING PK, then a follow-up SELECT
  on the same `dsl`" within the emitted method; document the choice in the landing commit.  Both
  paths are indistinguishable from the caller's point of view.
- **Transactional integrity for multi-step mutations** (e.g. INSERT + immediate re-fetch):
  both calls share the same `dsl` obtained from `GraphitronContext`, which may or may not be
  inside an active transaction depending on the caller's setup.  Document this as the
  caller's responsibility; no wrapper is added in this plan.
