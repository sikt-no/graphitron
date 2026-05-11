---
id: R75
title: "Plain payload types for DML mutations"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
---

# Plain payload types for DML mutations

A `@mutation` whose return type is a plain SDL Object (no `@record(className)`, no
`@table`) wrapping a single `@table`-element data field should compile and serve
correctly without the consumer authoring a Java carrier. Today the rewrite forces an
authored class on every payload that isn't a bare `ID` or `@table`, which inverts legacy
graphitron's default and drags graphql-java's mapping concerns into the consumer's
source tree.

The fix is not to synthesize a Java class — generating transport DTOs is the legacy
mistake the rewrite already disowns under the "no DTOs, no TypeMappers" emitter
convention (`graphitron-rewrite/docs/rewrite-design-principles.adoc`, §"Return types").
Java payload classes earn their place only when the consumer needs to capture state or
behaviour on the carrier. The structural reading of the SDL is: the *mutation* returns a
record-shaped wire type; the data field *inside* that wire type is the table-bound field
that carries the rows. The model classifies them accordingly — record-returning mutation
on top, `RecordTableField` (already in the model) for the data field — and the DML
emitter ships the rows back through graphql-java's standard traversal without a carrier
class on disk.

The reshape also lets the DML's transaction be tight. Today's emit pattern bundles
`INSERT...RETURNING $fields(table)` into a single statement that returns full row data
inside the DML transaction; if the surrounding code holds the transaction across
graphql-java's traversal, read-after-write errors during the response can roll back the
write. R75 shifts to two-statement emit: the DML returns PK columns only and commits in
its own short transaction; the data field's `RecordTableField` fetcher runs the
follow-up `SELECT $fields(table)` outside that transaction. Read errors during traversal
become partial-response errors, never undo writes.

## Concrete friction

```graphql
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}

type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]
}

type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: OpprettKvotesporsmalPreutfyllingInput!
    ): KvotesporsmalPreutfyllingPayload @mutation(typeName: INSERT)
}
```

Pre-R75 this fails at `MutationInputResolver.validateReturnType` (the `ScalarReturnType`
arm rejecting non-`ID` returns) with *"return type 'KvotesporsmalPreutfyllingPayload' is
not yet supported; use ID or a @table type"*. The plain Object lands as
`PlainObjectType` in the type classifier (`TypeBuilder.buildTypes`, the no-domain-
directive fall-through), and `GraphitronSchemaBuilder.buildSchema` skips field
classification for `PlainObjectType` parents entirely. There is no machinery downstream
that would route the DML round-trip back to graphql-java for traversal.

R75 Phase 1's first attempt shipped at `3ac4996` under a wire-format-unwrap-at-boundary
design: a new `ChildField.PassthroughDataField` permit, a new `IdentityPassthrough`
capability sealed sub-interface, a `BuildContext.resolveReturnType` short-circuit that
collapsed the payload type into the data field's `TableBoundReturnType`, and the
existing `MutationInsertTableField` etc. permits used for both direct-`@table` returns
and unwrapped payloads. In review, two design tensions surfaced: (a) the unwrap pattern
re-derives passthrough state at four consumer sites instead of carrying the resolution
in the type system, and (b) the one-statement `INSERT...RETURNING $fields(table)` keeps
the DML transaction open across graphql-java's response traversal, so read-after-write
errors can undo writes. The reshape below supersedes the shipped attempt; the SDL
acceptance contract is unchanged from the consumer's view, but the model and the emit
pattern are different. Phase 1's added permits and capability retire as part of the
implementation.

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are
not schedule-coupled.

- **Phase 1: the reshape — record-returning DML mutations + tight transactions.**
  Replaces Phase 1's shipped wire-format-unwrap design with the structural model
  the SDL implies: mutations with payload return types classify as record-returning
  carriers; the data field on the payload classifies as the existing
  `ChildField.RecordTableField`; DML emit becomes two-step (PK-only RETURNING
  inside `dsl.transactionResult(...)`, then a follow-up SELECT for the response
  data outside the transaction). Direct-`@table`-return DML mutations get the same
  two-step emit. `DELETE` admits only `: ID` / `: [ID!]` returns (no payload-shaped
  data — the row is gone before the SELECT can read it).
- **Phase 2: multi-field payloads via `localContext` on `@mutation` (DML).** Extends
  the trigger to multi-field payloads where one field is the table-bound data field
  (per Phase 1) and the rest are non-data slot fields. Non-data fields carry state
  via graphql-java's `DataFetcherResult.localContext`. Phase 2 ships the wrap with
  an empty `localContext` map; the populator interface, registration mechanism, and
  per-slot trigger conditions land alongside the first real populator (errors, in
  its own roadmap item).
- **Phase 3: `@service` mutations and `@record`-element data.** Admits payload-
  returning shapes on `@service` mutations (where the consumer constructs
  `DataFetcherResult` directly) and admits `@record`-element data fields (in
  addition to `@table`-element). `RecordTableField` already covers the
  `@record`-parent / `@table`-child case at the data-field site; the work is at
  the mutation-classifier and `@service`-resolver layer.

All three phases are specified in implementation-ready detail below.
Promoting R75 from Spec → Ready means signing off on the entire body;
each phase ships as its own implementation cycle.

## Foundation: `SourceKey` via R38

R75 builds on R38, which established the `SourceKey` / `Reader` /
`LoaderRegistration` vocabulary as the source-side dispatch axis for
DataLoader-backed (and more generally, source-bearing) fields. R38 Phase 3
has shipped: `BatchKey` is gone, `BatchKeyField` permits store `sourceKey`
+ `loaderRegistration` as record components, field-classifier producers
build the pair inline at field-construction time, and `Reader` is a sealed
inner interface on `SourceKey` with five permits (`ColumnRead`,
`AccessorCall`, `SourceRowsCall`, `ServiceTableRecord`,
`ServiceUntypedRecord`). R75's contribution to that taxonomy is one new
`Reader` permit added on the same sealed interface:

```java
record ResultRowWalk() implements Reader {}
```

`ResultRowWalk` says: "source rows come from a `Result<RecordN<...>>` that
upstream DML emit produced and graphql-java is now traversing." The
DataFetcher reads `env.getSource()` typed by `SourceKey.wrap × columns` and
extracts `SourceRow` instances directly. There is no `LoaderRegistration` for
this case; the rooted data field is plain `DataFetcher`, not
DataLoader-batched.

The data field's `SourceKey` for the rooted DML payload case:

- `reader == ResultRowWalk` — distinguishes the rooted case from
  `ColumnRead` / `AccessorCall` / `SourceRowsCall` etc.
- `path == empty` — target-aligned. Target is the DML's input table.
- `wrap` is `Wrap.Record` — upstream DML emit returns `Record_N_<...>` rows.
  (`SourceKey.Wrap` was an enum in R38's draft and flipped to a sealed
  interface during Phase 3; the rooted DML case lands on the `Record` arm.)
- `cardinality` matches the mutation's input cardinality (single
  `TableInputArg` → `ONE`; list → `MANY`).
- `columns` = the input table's PK columns
  (`tableInputArg.inputTable().primaryKeyColumns()` via `TableRef`).

`SourceKey`'s compact constructor gains one cross-axis invariant for
`ResultRowWalk`: cardinality matches the upstream Result's row count
(`Record_N_<...>` → `ONE`, `Result<Record_N_<...>>` → `MANY`). This sits
alongside the three `source-key.*` invariants R38 ships
(`source-rows-call-wraps-row`, `accessor-call-wraps-record`,
`service-table-record-target-aligned-empty-path`) and is the load-bearing
classifier check that pins the data-field's target table to the DML's
input table. Mismatch (e.g. SDL author writing
`Mutation.x(in: FilmInput!): ActorPayload`) is rejected at classification
time with a per-mismatch message.

### New sibling permit: `ChildField.RootedPayloadDataField`

R38's `ChildField.RecordTableField` implements the `BatchKeyField` capability,
whose contract is "a field that requires DataLoader setup"; every existing
producer pairs a non-null `SourceKey` with a non-null `LoaderRegistration` and
every consumer (notably `RecordTableField.emitsSingleRecordPerKey()`, which
reads `loaderRegistration().dispatch()` unconditionally) relies on that
invariant. R75's rooted DML payload data field is structurally not
DataLoader-backed: the upstream DML produced a `Result<RecordN<...>>` that
graphql-java is now traversing, and the data field's fetcher reads
`env.getSource()` directly.

Rather than widen `RecordTableField.loaderRegistration` to nullable (which
would dissolve the `BatchKeyField` invariant and force every existing
consumer site to guard a "no-loader" arm), R75 introduces a sibling
`ChildField` permit:

```java
record RootedPayloadDataField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.TableBoundReturnType returnType,
    SourceKey sourceKey   // reader is Reader.ResultRowWalk by construction
) implements ChildField, TableTargetField {}
```

The permit carries `SourceKey` without `LoaderRegistration`; the
`source-key.result-row-walk-cardinality-matches-upstream-result` invariant
declared on `SourceKey`'s compact constructor (R75's contribution to R38's
invariant inventory) is what pins the rooted shape at the model level. It
does *not* implement `BatchKeyField`; the dispatch-axes split between
`SourceKey` and `LoaderRegistration` (see
`graphitron-rewrite/docs/dispatch-axes.adoc`) is preserved at the field
record level. Every existing `RecordTableField` producer and consumer is
unchanged.

`RootedPayloadDataField` implements `TableTargetField` so polymorphic and
nested-`@table` consumers that read `target()` work uniformly. The data
fetcher emit dispatches on `RootedPayloadDataField` at the same seam that
dispatches on `RecordTableField` (one new arm in
`FetcherEmitter.dataFetcherValue` or its sibling).

## Phase 1 conventions

A handful of decisions ride through all three phases below. Stating them
once here so the phase bodies don't litter "we picked X over Y":

- **Single mutation-field permit, `DmlKind` discriminator.** `INSERT`,
  `UPDATE`, `UPSERT` carry identical components on the model side; per-kind
  variation lives in the SQL emit, not the type. R75 ships one
  `MutationDmlRecordField` permit with a `DmlKind kind` field. (R12's four
  `DmlTableField` permits remain as-is; folding them the same way is a
  sibling Backlog item.)
- **`DataFetcherResult` wrap from day 1.** Phase 1 wraps the mutation
  fetcher's output in `DataFetcherResult.<Result<RecordN<...>>>newResult()
  .data(rows).build()` even when the carrier has no slots, so Phase 2's
  `localContext` attachment is purely additive. The carrier ships with a
  `slots: List<SlotDescriptor>` component (empty in Phase 1) for the same
  reason.
- **`PojoResultType` split into `Backed | NoBacking` sub-taxonomy.**
  No-`@record` plain SDL Objects passing the trigger promote to a
  `ResultType` arm at type-classification time, not at `resolveReturnType`
  call time, so the trigger's downstream consumers stay on one
  `ResultType` machinery and `resolveReturnType` is free of the
  short-circuit the pivot retired. Today's `PojoResultType` carries
  `String fqClassName` (nullable, with `null` meaning either "authored
  payload with no className declared" or "plain payload Object promoted"):
  R75 Phase 1 ships the sub-taxonomy split as part of the implementation
  rather than deferring it.
  ```java
  sealed interface PojoResultType extends ResultType
      permits PojoResultType.Backed, PojoResultType.NoBacking {
      record Backed(String name, SourceLocation location, ClassName className)
          implements PojoResultType {}
      record NoBacking(String name, SourceLocation location)
          implements PojoResultType {}
  }
  ```
  `Backed` is authored payloads with `@record(record: {className: ...})`;
  `NoBacking` is plain payload Objects promoted by the trigger. Consumers
  that today read `fqClassName` and check for `null` (the validator,
  emitter helpers) switch over the two arms exhaustively. The split lifts
  the sentinel overload at the type level: every consumer knows which case
  it's looking at from the variant, not from a nullable.
- **Two-step emit uniformly.** Direct-`@table`-return DML mutations
  (`createFilm: Film`) get the same two-step shape as payload-returning
  mutations (PK-only RETURNING in a tight transaction, follow-up SELECT
  outside it). The transaction-durability win applies to both; one emit
  pattern across both reduces the surface to maintain.
- **Trigger function name.** The classify-time helper is
  `tryResolvePayloadShape`. The earlier `unwrapPassthroughPayload` name was
  residue from the retired wire-format-unwrap design.
- **Validator threads classifier rejections.** `MutationInputResolver
  .validateReturnType`'s `ResultReturnType` arm substitutes the trigger's
  `Rejected` reason when the return type is a payload candidate that failed
  a per-condition check. The trigger is consulted at classify time and at
  validator time; consolidating call sites further is a separate concern.


## Phase 1: record-returning DML mutations + tight transactions

Phase 1 admits payload-returning DML mutations by reading the SDL
structurally: the mutation classifies as a record-returning DML carrier;
the data field on the payload classifies as the existing
`ChildField.RecordTableField` with a `SourceKey` whose `reader ==
ResultRowWalk`; DML emit becomes two-step — PK-only `RETURNING` inside a
tight transaction, follow-up `SELECT` outside it. Direct-`@table`-return
DML mutations get the same two-step shape per the convention above.

### Model: one `MutationDmlRecordField` permit

```java
record MutationDmlRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    DmlKind kind,                       // INSERT | UPDATE | UPSERT
    Optional<ErrorChannel> errorChannel,
    List<SlotDescriptor> slots          // empty in Phase 1; widened in Phase 2
) implements MutationField {}

enum DmlKind { INSERT, UPDATE, UPSERT }
```

`returnType` is the payload's `ResultReturnType` (no unwrap — the SDL's
structural truth). `tableInputArg` carries the input `@table` exactly like
the existing `MutationInsertTableField` etc. arms. The `kind` discriminator
drives per-DML-kind emit variation; the model shape is one permit since the
components are identical across the three kinds. `slots` is the empty list
in Phase 1; Phase 2 populates it.

No `MutationDeleteRecordField` permit: DELETE-with-payload-return is
rejected at classify time. Returning pre-deletion state is incorrect by
construction; the row is gone before the response SELECT can read it.

### Trigger function

`BuildContext.tryResolvePayloadShape` is a pure structural test returning a
sealed `PayloadResolution` (`Ok(PayloadShape)` / `NotCandidate` /
`Rejected(Reason)`). Four conditions:

1. The type is registered as `PojoResultType.NoBacking`. No-`@record` plain
   SDL Objects promote to that arm at type-classification time per the
   conventions above; `PlainObjectType` never reaches the trigger.
   `PojoResultType.Backed` (authored payloads with `@record`) falls into
   `NotCandidate`.
2. The SDL Object declares exactly one field — the data field. (Phase 2
   widens to "exactly one `@table`-element field plus zero or more
   non-`@table`-element slot fields".)
3. That single field's element type is registered as `TableBackedType`.
   (Phase 3 widens to admit `@record`-element data.)
4. The data field carries no graphitron-domain directive (`@service`,
   `@sourceRows`, `@reference`, `@nodeId`, `@field`, `@asConnection`,
   `@splitQuery`, `@externalField`, `@condition`, `@lookupKey`,
   `@notGenerated`, `@tableMethod`, `@defaultOrder`, `@orderBy`,
   `@multitableReference`). Pure-metadata directives (`@deprecated`, custom
   non-graphitron directives) are off the list.

The trigger is consulted at the mutation classifier (admitting the payload
return as `MutationDmlRecordField`) and at the schema-builder's per-type
pass (admitting the data field as `RootedPayloadDataField`). The same
resolution flows into `MutationInputResolver.validateReturnType`'s
`ResultReturnType` arm, which substitutes the `Rejected` reason on per-
condition failure; validator-mirrors-classifier discipline holds.

### Mutation-field classification

`FieldBuilder.classifyMutationField` admits payload returns as a sibling
branch to the existing direct-`@table` admission:

- The mutation has `@mutation(typeName: <KIND>)` and a registered
  `TableInputArg`.
- `ctx.resolveReturnType(rawReturn, wrapper)` returns `ResultReturnType`
  (the payload type having been promoted to `PojoResultType.NoBacking` at
  type-classification time).
- The classifier consults the trigger. On `Ok`, classify as
  `MutationDmlRecordField` with `kind` from the `@mutation` directive. For
  `kind == DELETE`, reject with `"@mutation(typeName: DELETE) returning a
  payload type is not supported; use ID or [ID!]"`. On `Rejected`, surface
  the reason. On `NotCandidate`, fall through to the existing
  `ResultReturnType`-arm rejection (the `@record`-with-`className`
  non-payload case is unchanged).

### Data-field classification: `RootedPayloadDataField`

The schema-builder's per-type pass classifies the data field as
`ChildField.RootedPayloadDataField` (the new sibling permit introduced in the
Foundation section) when the trigger returns `Ok`:

- `parentTypeName`: the payload type name.
- `name`: the data field's name.
- `returnType`: `TableBoundReturnType` carrying the data field's element
  type, resolved `TableRef`, and SDL `FieldWrapper` (single or list). Phase
  1's trigger forbids arguments and directives on the data field, so
  there's no `filters` / `orderBy` / `pagination` / `joinPath` slot to
  carry; rows filter by PK directly inside the fetcher's follow-up SELECT.
- `sourceKey`: constructed inline by the `FieldBuilder` producer from the
  upstream `MutationDmlRecordField`'s `tableInputArg` (R38 Phase 3 deleted
  the `SourceKeyResolver` indirection; producers `new SourceKey(...)`
  directly at field-construction time, mirroring
  `FieldBuilder.buildServiceTableSourceKey` and siblings):
  - `target = tableInputArg.inputTable()`
  - `columns = tableInputArg.inputTable().primaryKeyColumns()`
  - `path = List.of()`
  - `wrap = new SourceKey.Wrap.Record()`
  - `cardinality = tableInputArg.wrapper().isList() ? Cardinality.MANY
    : Cardinality.ONE`
  - `reader = new SourceKey.Reader.ResultRowWalk()`

The mutation is invoked exactly once per request and its result is the
singleton parent of the payload's traversal; the data field is fetched once.
The `Reader.ResultRowWalk` arm on the `SourceKey` signals the rooted shape;
existing `RecordTableField` emit for non-rooted parents
(`reader instanceof Reader.ColumnRead` etc., established in R38) stays on
the DataLoader path, unchanged.

### Generator emit

The mutation's fetcher runs the DML inside `dsl.transactionResult(tx ->
...)` and returns the result wrapped in `DataFetcherResult`:

```java
public static DataFetcherResult<Result<Record1<Integer>>> createFilmsPayload(
        DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    List<Map<?, ?>> in = (List<Map<?, ?>>) env.getArgument("in");
    Result<Record1<Integer>> rows = in.isEmpty()
        ? DSL.using(dsl.configuration()).newResult(Tables.FILM.FILM_ID)
        : dsl.transactionResult(tx -> DSL.using(tx)
            .insertInto(Tables.FILM, Tables.FILM.TITLE, Tables.FILM.LANGUAGE_ID)
            .valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
            .returningResult(Tables.FILM.FILM_ID)
            .fetch());
    return DataFetcherResult.<Result<Record1<Integer>>>newResult()
        .data(rows)
        .build();
}
```

The transaction commits when `transactionResult` returns; the materialised
`Result<Record1<Integer>>` outlives it. The `DataFetcherResult` wrap rides
on day 1 so Phase 2 attaches `localContext` purely additively.

The data field's fetcher reads the source typed by `SourceKey.wrap ×
columns` and runs the response SELECT:

```java
($T env) -> {
    Result<Record1<Integer>> source = sourceKey.extractSourceRows(env);
    if (source.isEmpty()) return source;
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    return dsl.select(Film.$fields(env.getSelectionSet(), Tables.FILM, env))
        .from(Tables.FILM)
        .where(Tables.FILM.FILM_ID.in(source.getValues(Tables.FILM.FILM_ID)))
        .fetch();
}
```

`sourceKey.extractSourceRows(env)` is the typed read — the wrap shape is
declared by the `SourceKey`, no untyped cast at the consumer site. The
generic-parameter shape (`Record1<Integer>` in this example) is derived
from `SourceKey.wrap × columns` at emit time. Composite-PK tables emit
`Record_N_<...>` with a row-tuple `IN` predicate; the pattern mirrors
today's bulk-DELETE / lookup-VALUES emitters.

Read errors during this SELECT, or during nested `@table` fetchers
traversing each Film, propagate as graphql-java field errors — they cannot
undo the DML, which committed when the mutation's fetcher returned.

### Direct-`@table` mutation return: same two-step emit

`Mutation.createFilm: Film` (direct `@table` return) keeps its existing
`MutationInsertTableField` etc. classification but its emit changes to the
two-step shape: PK-only `RETURNING` inside `transactionResult`, then a
follow-up SELECT inside the same fetcher returning a `Record` for
graphql-java's traversal of Film's children. DELETE-direct-return
(`Mutation.deleteFilm: ID`) and `: ID` returns generally keep
single-statement emit — `RETURNING` the PK column directly satisfies the
encoder, no follow-up needed.

### What about R12's authored payload (`@record(record: {className: ...})`)?

Out of R75's scope. R12's `DmlReturnExpression.Payload` arm continues
today's behaviour (authored carrier, single-statement emit). R75's pattern
is implementable on R12's emit too and would land any future deprecation
cleanly, but R12 is tracked elsewhere.

### Retirements from the shipped Phase 1 attempt

The following retire as part of Phase 1's implementation:

- `ChildField.PassthroughDataField` permit.
- `ChildField.IdentityPassthrough` capability sealed sub-interface.
  `FetcherEmitter.dataFetcherValue` reverts to the two pre-R75 `instanceof`
  arms (`ConstructorField`, `NestingField`).
- `BuildContext.resolveReturnType` short-circuit. Payload types resolve to
  `ResultReturnType` like any other record return.
- `FetcherRegistrationsEmitter.emit()` filter widening to `PlainObjectType`.
- `GraphitronSchemaValidator` switch-arm, `IMPLEMENTED_LEAVES` entry, and
  `TypeFetcherGenerator` no-op arm for `PassthroughDataField`.
- `NonTableParentCase.PASSTHROUGH_PAYLOAD_DATA_FIELD` fixture.
- `BuildContext.PASSTHROUGH_FORBIDDEN_DATA_FIELD_DIRECTIVES` set survives
  but moves inside the trigger function alongside the other classify-time
  conditions.
- The load-bearing classifier check
  `passthrough-payload.data-table-equals-dml-target`. Replaced by R75's
  contribution to R38's invariant inventory: `source-key.result-row-walk-
  cardinality-matches-upstream-result`, which structurally pins the
  data-field's target table to the DML's input table at `SourceKey`
  construction. The validator threads the rejection message; no separate
  cross-pair classifier check is needed.

### Tests

**Pipeline-tier** (`graphitron/src/test/`), `PayloadPipelineTest` (renamed
from `PassthroughPayloadPipelineTest` to align with the directive rename):

- Per-`DmlKind ∈ {INSERT, UPDATE, UPSERT}`:
  - Mutation field classifies as `MutationDmlRecordField` with the
    expected `kind` for payload returns.
  - Data field classifies as `ChildField.RootedPayloadDataField` (the new
    sibling permit) with the expected `SourceKey` shape:
    `reader instanceof Reader.ResultRowWalk`, `path` empty,
    `wrap instanceof Wrap.Record`, `cardinality` matching input arg,
    `columns == inputTable.primaryKeyColumns()`. The permit does not
    implement `BatchKeyField`; no `loaderRegistration` slot to assert.
- Payload type promotes to `PojoResultType.NoBacking`; authored payloads
  with `@record(record: {className: ...})` continue to promote to
  `PojoResultType.Backed`. Exhaustive-switch assertion over the sealed sub-
  taxonomy on a small consumer (validator helper) confirms the split is
  load-bearing rather than cosmetic.
- DELETE-with-payload return rejects with the per-mismatch message.
- Trigger rejections (multi-field, scalar element, interface element,
  graphitron-domain directives on the data field, `@table`-typed payload,
  `@record`-with-`className`).
- `@deprecated` on the data field admits.
- *Direct-`@table`-return two-step emit pin.* For each
  `DmlKind ∈ {INSERT, UPDATE, UPSERT}`, the existing `MutationInsertTableField`
  / `MutationUpdateTableField` / `MutationUpsertTableField` fetcher's emitted
  `MethodSpec` is structurally walked (not body-string compared) to assert
  the two-step shape: exactly one `dsl.transactionResult(...)` invocation
  inside the body, and a follow-up `SELECT` call site outside the
  transaction lambda. A regression to single-statement
  `INSERT…RETURNING $fields(table)` (compile-correct, but durability-
  defeating) fails this pin.
- `fetcherEmitter_revertedTwoArms`: structural pin that
  `FetcherEmitter.dataFetcherValue` has the two pre-R75 `instanceof` arms
  (`ConstructorField`, `NestingField`), plus the new
  `RootedPayloadDataField` arm.

**Compilation-tier** (`graphitron-sakila-example`): SDL fixture from the
shipped attempt survives — `FilmPayload` + the `create / update / upsert`
mutations — minus the DELETE-with-payload variant. `mvn compile -pl
:graphitron-sakila-example -Plocal-db` verifies the two-step emit produces
compile-correct DSL on both payload-return and direct-`@table`-return
mutations.

**Execution-tier**
(`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`),
`PayloadDmlTest` (renamed from `PassthroughPayloadDmlTest`):

- INSERT / UPDATE / UPSERT round-trip: row written, PK returned by
  mutation's fetcher inside a `DataFetcherResult` wrap, follow-up SELECT
  projects the requested columns, response shape matches.
- *Headline correctness pin* — `dml_persists_when_followupSelect_throws`: a
  mutation whose DML succeeds but whose data-field traversal throws
  (synthetic error from a `@service`-wired nested `@table` field, or an
  injected exception in the SELECT path). Asserts the row exists in the DB
  after the response, *and* the response carries the GraphQL field error.
  This is the durability invariant the reshape exists to make true.
- Direct-`@table`-return mutations (`createFilm: Film` etc.): same
  round-trip check confirms the existing carrier types work under the
  two-step emit.
- *Durability pin replayed for direct-`@table` return*
  (`dml_persists_when_directReturnSelect_throws`): the same synthetic-error
  injection as the headline pin, against `createFilm: Film` (and one
  per-`DmlKind` variant). Direct-`@table` DML is changing emit pattern from
  single-statement `INSERT…RETURNING $fields(table)` to two-step inside
  this work; the durability invariant the reshape exists to establish must
  hold across both surfaces, not just the payload one.

**Audit-tier:** R75's contribution to R38's invariant inventory is the
`source-key.result-row-walk-cardinality-matches-upstream-result`
load-bearing classifier check. The audit framework verifies the check is
consumed by the mutation-fetcher emit site and the data-field fetcher emit
site.

### Out of scope for Phase 1

- **Multi-field payloads.** Phase 2 territory.
- **`@service` mutations returning payload types.** Phase 3 territory.
- **`@record`-element data fields.** Phase 3 territory.
- **Restructuring R12's authored-carrier emit.** Tracked separately.
- **DELETE returning a payload of pre-deletion data.** Rejected at classify
  time; not coming back.
- **Component-type narrowness alignment.** R75's `MutationDmlRecordField`
  uses narrow `ResultReturnType`; existing `MutationServiceRecordField`
  uses broad `ReturnTypeRef`. Aligning the existing carrier to the narrow
  shape is a sibling Backlog item.

## Phase 2: multi-field payloads via localContext

Phase 1 admits single-data-field payloads. Phase 2 extends the trigger to multi-field
payloads where one field is the table-bound data field (per Phase 1) and the rest are
non-data slot fields. Slot fields render null until a per-slot populator wires up;
that work is its own roadmap item per slot family (errors, affected-row counts, etc.).

### Trigger extension

The trigger function's condition #2 changes from "exactly one field" to "exactly one
`@table`-element field (the data field) and zero or more non-`@table`-element fields
(slot fields)". Conditions #1, #3, and #4 (the directive guard, applied to the data
field only) carry over. Multi-`@table`-element payloads remain rejected — they form
the compound-mutation pattern tracked under R122.

The trigger result extends with a slot-descriptor list; Phase 1 callers continue to
see an empty list and behave identically.

```java
record SlotDescriptor(String name, String elementName, FieldWrapper wrapper) {}
```

Slot fields with `@record`-element types reject at trigger time (Phase 3 territory).

### Slot-field classification

Slot fields land on the same payload-type parent as the data field. A new
`ChildField.PassthroughSlotField` permit handles them; its fetcher reads from
`env.getLocalContext().get(name)`:

```java
if (field instanceof ChildField.PassthroughSlotField slot) {
    return CodeBlock.of(
        "($T env) -> env.getLocalContext() == null ? null "
            + ": (($T<$T,$T>) env.getLocalContext()).get($S)",
        DATA_FETCHING_ENV, MAP_CLASS, STRING_CLASS, OBJECT_CLASS, slot.name());
}
```

The null-guard accounts for the consumer-driven-population case where the localContext
map may not have been populated yet.

### Mutation carrier population

The Phase 1 `MutationDmlRecordField` carrier already carries `slots:
List<SlotDescriptor>` (empty in Phase 1 per the conventions above). Phase 2
populates that list when the trigger admits a multi-field payload.

The mutation's fetcher, which already wraps in `DataFetcherResult` from
Phase 1, attaches `localContext` when `slots` is non-empty:

```java
return DataFetcherResult.<Result<Record1<Integer>>>newResult()
    .data(rows)
    .localContext(slotsMap)   // emptyMap() in Phase 2; populated per slot family
    .build();
```

Phase 2 ships with `slotsMap = emptyMap()`. The populator framework lands
per-slot-family in its own roadmap item; the wrap structure is in place to
receive populated values without any further carrier change. No
`MutationFieldWithSlots` wrapper — the slots ride on the carrier itself.

### Slot population

Phase 2 ships the wrap mechanism and the slot fetcher emit; it does *not* ship a
populator framework. Designing the populator API against zero consumers would be the
"premature framework" anti-pattern. The first real populator (errors is the canonical
candidate) lands in its own roadmap item; its emit needs inform the contract surface.

A SDL author who writes a payload with `errors: [Error!]` or any other non-data slot
sees the data field render correctly and the slot fields render as null until the
populator's roadmap item ships. Usable intermediate state.

### Out of scope for Phase 2

- Slot populators. Tracked separately per slot family.
- `@service` multi-field payloads (Phase 3 — the consumer constructs `DataFetcherResult`
  directly, so the `localContext` carriage is the consumer's responsibility).
- Slot fields with `@table` element type (R122 / compound mutations).
- Slot fields with `@record` element type (Phase 3).

### Tests

**Pipeline-tier** additions to `PayloadPipelineTest` (parameterised over
`DmlKind ∈ {INSERT, UPDATE, UPSERT}`):

- Multi-field admission with scalar slots: data field classifies as
  `RecordTableField` with the rooted `SourceKey` (per Phase 1); slot
  fields classify as `PassthroughSlotField`; mutation carrier's `slots`
  list preserves declaration order.
- Multi-`@table`-element rejection (compound-mutation territory).
- Slot field with `@record`-element rejection (Phase 3 territory).
- Mutation fetcher emits `DataFetcherResult.localContext(emptyMap())` wrap
  when `slots` is non-empty; the Phase-1 wrap (no `localContext` call)
  survives unchanged when `slots` is empty.

**Execution-tier** (sakila): new fixture verifying multi-field admission
compiles and runs with slots rendering null. Per-`DmlKind` drivers assert
the data field renders the inserted/updated/upserted row and the slot
fields render null. Verifies the `DataFetcherResult.localContext(emptyMap())`
wrap composes through graphql-java's traversal correctly.

**Audit-tier:** no new load-bearing keys.

## Phase 3: @service mutations and @record-element data

Phases 1 and 2 cover `@mutation` (DML) payload returns with `@table`-element data
fields. Phase 3 extends along two orthogonal axes:

- **Consumer.** The trigger admits `@service` mutations whose return type is a payload.
  The `@service` consumer constructs the `DataFetcherResult` (or a record-shaped
  return) directly; graphitron registers the data-field fetcher and any slot fetchers
  on the payload, and the existing `@service` wrapper unwrap (`Optional<T>`,
  `CompletableFuture<T>`, `Mono<T>`) handles wrapper composition.
- **Element kind.** The data field's element type may be `@record`-mapped in addition
  to `@table`-mapped. Service methods may return a domain record (Java record or POJO
  bound via `@record(record: {className: ...})`); the data field's classification
  carries the `@record` shape and graphql-java's per-field fetchers on the `@record`
  element handle accessor reads.

### Trigger extension

Condition #3 changes from "the data field's element type is registered as
`TableBackedType`" to "registered as `TableBackedType` or `ResultType`". The other
conditions stay; multi-field admission and slot rejection from Phase 2 carry over.

The trigger result's data-element descriptor becomes a sealed sub-taxonomy:

```java
sealed interface DataElement {
    String name();
    FieldWrapper wrapper();
    record Table(String name, TableRef table, FieldWrapper wrapper) implements DataElement {}
    record Record(String name, String fqClassName, FieldWrapper wrapper) implements DataElement {}
}
```

Phase 1 / 2 callers see `DataElement.Table`. Phase 3 introduces the `Record` arm.

### Mutation classification

`@mutation` (DML) admits only `@table`-element data — Phase 1 / 2 unchanged.
`@record`-element data on a DML mutation is out of scope (would require a
"DML row → domain record" conversion step at the emitter; tracked
separately).

`@service` mutations classify through the existing `MutationServiceRecordField`
permit when the SDL return is a payload type. The service method's declared
return type is matched against the data field's element kind:

- `DataElement.Table(name, table, wrapper)`: inner type must be
  `Result<<TableRecord>>` (or a single `<TableRecord>` for non-list, or a
  `DataFetcherResult` wrapping either).
- `DataElement.Record(name, fqClassName, wrapper)`: inner type must be the
  class named `fqClassName` (or a list / optional thereof matching
  `wrapper`, or a `DataFetcherResult` wrapping it).

Mismatches reject at classify time with a per-mismatch message naming the
SDL data element, the method's return type, and the gap.
Validator-mirrors-classifier coverage: every classifier rejection surfaces
as a build-time error via the existing typed `Resolved` shapes and the
validator's `@service` arm.

### Data-field classification

For `@table`-element data on a `@service` mutation: same
`ChildField.RecordTableField` classification as Phase 1, with the rooted
`SourceKey` (`reader == ResultRowWalk`). The upstream `Result<RecordN<...>>`
is the service method's return after wrapper unwrap; the data-field
fetcher reads it via `sourceKey.extractSourceRows(env)` and runs the
response SELECT — same shape as Phase 1.

For `@record`-element data: the data field classifies under whichever
existing model shape covers "record parent → record child" cleanly. The
mechanism is graphql-java's per-field accessor traversal of the service
method's returned domain record; graphitron's emit is identity passthrough
via the existing `ConstructorField` shape, no `SourceKey` synthesis needed
(there are no source rows to extract — the parent's value is a domain
record, not a `Result<RecordN<...>>`). Phase 3 may extend `ConstructorField`'s
docstring to cover record-on-record use, or introduce a sibling permit if
structural clarity demands. Implementer call.

### Wrapper composition

Service methods may return `T`, `Optional<T>`, `CompletableFuture<T>`, `Mono<T>`. The
existing `@service` unwrap strips these wrappers before classifying the inner `T`.
`DataFetcherResult<T>` is treated as another wrapper layer: a method returning
`DataFetcherResult<T>` admits with the same trigger as one returning `T`. A method
returning bare `T` paired with a payload that has slot fields admits — the slots
render as null until the consumer wraps in `DataFetcherResult` and populates the
localContext map.

The matrix of wrapper × element kind (4 wrappers × 2 element kinds = 8 combinations)
is verified at the execution tier.

### Out of scope for Phase 3

- `@record`-element data on `@mutation` (DML). Tracked separately.
- Service-side slot populators (per-slot roadmap items per Phase 2).
- `@service` queries returning payload types. The trigger is consumer-agnostic so the
  classification works, but the per-query fetcher emit hasn't been audited end-to-end.
  Treat as a follow-up.

### Tests

**Pipeline-tier** additions to `PayloadPipelineTest`:

- `@record`-element data field admits and registers correctly.
- `@service` mutation returning a payload admits at the service classifier;
  no `UnclassifiedField` at the mutation site. Parameterised over
  `{Table, Record}`.
- Service-method-return-type mismatch rejects with the per-mismatch message.
  Parameterised over `{Table, Record}`.
- Wrapper composition: service method's `Optional<T>` /
  `CompletableFuture<T>` / `Mono<T>` / `DataFetcherResult<T>` unwraps to
  the inner `T` for trigger matching. Parameterised over
  `{T, Optional, CompletableFuture, Mono, DataFetcherResult} ×
  {Table, Record}`.
- `@service` payload with both data and slot fields: method returning
  `DataFetcherResult<T>` admits; slot fetchers read from the
  consumer-populated `localContext`.

**Execution-tier**
(`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`):
new `PayloadServiceTest` parameterising over `(elementKind, wrapper)`:

- SDL: two payload variants — `@table`-element via the existing sakila
  `Actor`, `@record`-element via an `ActorRecord` Java record bound
  through `@record(record: {className: "...sakila.ActorRecord"})`.
- Per-wrapper drivers (`T`, `Optional<T>`, `CompletableFuture<T>`,
  `Mono<T>`) for each element kind: invoke the service mutation against
  the testcontainer, assert response shape and follow-up reads. 8 cases.
- Additional driver per element kind exercising a multi-field payload
  over `DataFetcherResult<T>` to verify consumer-side slot population.
  2 more cases.

**Audit-tier:** no new load-bearing keys in Phase 3.

## Non-goals

- **Synthesizing Java payload classes.** Explicitly rejected. No
  `<outputPackage>.synthesized` package, no `SynthesizedPayloadClassGenerator`, no
  per-payload-type Java emission.
- **Setter-based errors injection.** Violates immutability and would have graphitron
  mutate consumer-produced objects. Legacy's `payload.setErrors(...)` shape is not
  coming back.
- **DELETE returning a payload of pre-deletion data.** Rejected at classify time —
  the row is gone before the response SELECT can read it. DELETE returns `: ID` /
  `: [ID!]` only.
- **Multi-mutation atomicity inside a single GraphQL request.** Mutations are
  sequential per the GraphQL spec, and federation breaks the illusion anyway.
  Generated DML mutations commit in their own tight transactions; consumers don't
  get a "transactionally atomic multi-mutation" knob.
- **Restructuring R12's authored-carrier emit.** Tracked separately. R12's eventual
  deprecation is informed by R75's emit pattern (PK-only RETURNING + follow-up
  SELECT), but R75 doesn't touch R12's classification or carrier shape.

## Success criteria

**Phase 1:**

- The reproduction case at the top compiles and serves correctly through
  `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, with no
  `@record(className)` declaration on the SDL payload type and no Java
  class on disk for the payload.
- `PayloadPipelineTest`'s admission cases pass for INSERT / UPDATE /
  UPSERT; DELETE-with-payload rejects with the per-mismatch message. The
  data-field classifies as `ChildField.RootedPayloadDataField` (the new
  sibling permit, not implementing `BatchKeyField`) with the expected
  `SourceKey` shape (`reader instanceof Reader.ResultRowWalk`, `path`
  empty, `wrap instanceof Wrap.Record`, `cardinality` matching input arg,
  `columns == inputTable.primaryKeyColumns()`).
- `PojoResultType` ships split into `Backed(ClassName)` / `NoBacking()`;
  no-`@record` plain SDL Objects passing the trigger promote to
  `NoBacking`; authored `@record` payloads (with or without className)
  promote to `Backed`. Downstream consumers that previously read
  `fqClassName` and checked for `null` switch over the two arms
  exhaustively.
- `PayloadDmlTest`'s execution-tier driver passes against the sakila
  testcontainer for INSERT / UPDATE / UPSERT, verifying the two-step emit
  (DML in transaction + follow-up SELECT, both halves typed via
  `SourceKey`) compiles and runs end-to-end.
- The headline correctness pin (`dml_persists_when_followupSelect_throws`)
  passes for payload-returning DML, *and* the direct-`@table`-return
  variant (`dml_persists_when_directReturnSelect_throws`) passes for
  `createFilm: Film` etc. — the durability invariant the reshape exists
  to establish holds across both surfaces the two-step emit applies to.
- Phase 1's shipped attempt retires cleanly: `PassthroughDataField`,
  `IdentityPassthrough`, the `resolveReturnType` short-circuit, the
  `FetcherRegistrationsEmitter` filter widening, the
  `passthrough-payload.data-table-equals-dml-target` load-bearing key,
  and the `PASSTHROUGH_PAYLOAD_DATA_FIELD` fixture are all gone.
- Direct-`@table`-return DML mutations (`createFilm: Film` etc.) continue
  to work under the new two-step emit pattern; existing fixtures regress
  without changes; the structural two-step-emit pin guards against a
  silent revert to single-statement emit.
- Authored payloads with `@record(record: {className: ...})` are
  unaffected (R12 scope unchanged).
- R75's contribution to R38's invariant inventory —
  `source-key.result-row-walk-cardinality-matches-upstream-result` —
  declared and consumed.

**Phase 2:**

- `PayloadPipelineTest`'s Phase 2 admission cases pass for INSERT /
  UPDATE / UPSERT.
- The classifier produces `MutationDmlRecordField` with non-empty `slots`
  when the trigger admits multi-field; Phase 1 single-field payloads
  continue to classify with empty `slots` (regression pin).
- The execution-tier multi-field fixture passes for the three DML kinds
  with slot fields rendering null (no populator wired in Phase 2; the
  `DataFetcherResult.localContext(emptyMap())` wrap composes through
  graphql-java).
- No populator framework or catalog ships; the populator contract
  surface lands per slot family in its own roadmap item.

**Phase 3:**

- `PayloadPipelineTest`'s Phase 3 admission cases pass for both element
  kinds (`{Table, Record}`) and all wrapper kinds
  (`{T, Optional, CompletableFuture, Mono, DataFetcherResult}`).
- `PayloadServiceTest`'s execution-tier matrix passes (8 driver cases
  over 2 element kinds × 4 wrappers + 2 multi-field variants over
  `DataFetcherResult`).
- `ServiceCatalog`'s `@service` mutation classifier admits payload-typed
  returns; the trigger's `DataElement.{Table | Record}` sealed
  sub-taxonomy distinguishes the two element kinds.
- Authored payloads with `@record(record: {className: ...})` remain
  unaffected across all three phases (regression pin via the existing
  authored-carrier fixtures).
