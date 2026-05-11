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
on top, a new sibling permit `SingleRecordTableField` for the data field (alongside
R38's DataLoader-batched `RecordTableField`) — and the DML emitter ships the rows back
through graphql-java's standard traversal without a carrier class on disk.

The reshape also lets the DML's transaction be tight. Today's emit pattern bundles
`INSERT...RETURNING $fields(table)` into a single statement that returns full row data
inside the DML transaction; if the surrounding code holds the transaction across
graphql-java's traversal, read-after-write errors during the response can roll back the
write. R75 shifts to two-statement emit: the DML returns PK columns only and commits in
its own short transaction; the data field's `SingleRecordTableField` fetcher runs the
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

R75 ships in two phases ordered by user-visible value. Phase 2 is a discrete
implementation track; it binds structurally to Phase 1 but is not schedule-coupled.

- **Phase 1: the reshape — record-returning DML mutations + tight transactions.**
  Replaces the earlier wire-format-unwrap design with the structural model the SDL
  implies: mutations with payload return types classify as record-returning
  carriers; the data field on the payload classifies as a new sibling permit
  `ChildField.SingleRecordTableField` (introduced in the Foundation section
  below — it does not implement `BatchKeyField`, since the single-record case is
  not DataLoader-batched); DML emit becomes two-step (PK-only RETURNING inside
  `dsl.transactionResult(...)`, then a follow-up SELECT for the response data
  outside the transaction). Direct-`@table`-return DML mutations get the same
  two-step emit. `DELETE` admits only `: ID` / `: [ID!]` returns (no payload-shaped
  data — the row is gone before the SELECT can read it).
- **Phase 2: `@service` mutations and record-element data.** Admits
  single-data-field carriers on `@service` mutations (where the consumer
  constructs the return value directly) and admits record-element data
  fields (in addition to `@table`-element). The element-type's
  record-backed `ResultType` classification is signal-agnostic: today the
  signal is `@record(record: {className: ...})`; after R96 Phase 3 it is
  `@service`-method-return-type introspection. R75 reads the model
  classification, not the producing signal. The work is at the
  mutation-classifier and `@service`-resolver layer.

Both phases are specified in implementation-ready detail below.
Promoting R75 from Spec → Ready means signing off on the entire body;
each phase ships as its own implementation cycle.

**Multi-field carriers** (payloads with one data field plus non-data slot fields
backed by `DataFetcherResult.localContext`) are out of R75's scope and tracked
under R128: `multi-field-mutation-carrier`. R128 depends structurally on R75 and
pivots on a `PropertyField.Reader` sub-axis (`ColumnRead | AccessorCall |
LocalContextRead`) so the slot-field classification reuses the existing
`PropertyField` permit rather than minting a new one.

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
this case; the single-record carrier's data field is plain `DataFetcher`, not
DataLoader-batched.

The data field's `SourceKey` for the single-record DML carrier case:

- `reader == ResultRowWalk` — distinguishes the single-record case from
  `ColumnRead` / `AccessorCall` / `SourceRowsCall` etc.
- `path == empty` — target-aligned. Target is the DML's input table.
- `wrap` is `Wrap.Record` — upstream DML emit returns `Record_N_<...>` rows.
  (`SourceKey.Wrap` was an enum in R38's draft and flipped to a sealed
  interface during Phase 3; the single-record DML case lands on the `Record`
  arm.)
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
classifier check the data-field's fetcher emit consumes when it types the
upstream `Result` shape via `SourceKey.wrap × columns`.

Table-equality between the data field's element table and the DML's input
table is a separate concern, enforced at the mutation classifier (see
"Mutation-field classification" below). The trigger itself is
mutation-agnostic (it sees only the payload type), so the table-equality
check rides on the classifier admission step, after the trigger returns
`Ok` and the surrounding `MutationDmlRecordField` admission has the
mutation's `tableInputArg` in hand. The retired
`passthrough-payload.data-table-equals-dml-target` check folds into that
admission-step rejection; mismatch (e.g. SDL author writing
`Mutation.x(in: FilmInput!): ActorPayload`) surfaces with a clear message
naming the SDL payload, its data field's element table, and the DML's
input table.

### New sibling permit: `ChildField.SingleRecordTableField`

R38's `ChildField.RecordTableField` implements the `BatchKeyField` capability,
whose contract is "a field that requires DataLoader setup"; every existing
producer pairs a non-null `SourceKey` with a non-null `LoaderRegistration` and
every consumer (notably `RecordTableField.emitsSingleRecordPerKey()`, which
reads `loaderRegistration().dispatch()` unconditionally) relies on that
invariant. R75's single-record DML carrier data field is structurally not
DataLoader-backed: the upstream DML produced a `Result<RecordN<...>>` that
graphql-java is now traversing, and the data field's fetcher reads
`env.getSource()` directly.

Rather than widen `RecordTableField.loaderRegistration` to nullable (which
would dissolve the `BatchKeyField` invariant and force every existing
consumer site to guard a "no-loader" arm), R75 introduces a sibling
`ChildField` permit:

```java
record SingleRecordTableField(
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
invariant inventory) is what pins the single-record shape at the model level.
It does *not* implement `BatchKeyField`; the dispatch-axes split between
`SourceKey` and `LoaderRegistration` (see
`graphitron-rewrite/docs/dispatch-axes.adoc`) is preserved at the field
record level. Every existing `RecordTableField` producer and consumer is
unchanged.

The name `SingleRecordTableField` mirrors `RecordTableField`'s
`<parent-shape><child-shape>Field` convention: "Single" signals the parent is
invoked once per request (the mutation root fetcher) and the field sees a
single source value, distinguishing it from `RecordTableField`'s
DataLoader-batched dispatch over multiple parent records.

`SingleRecordTableField` implements `TableTargetField` so polymorphic and
nested-`@table` consumers that read `target()` work uniformly. The data
fetcher emit dispatches on `SingleRecordTableField` at the same seam that
dispatches on `RecordTableField` (one new arm in
`FetcherEmitter.dataFetcherValue` or its sibling).

No new "has-a-`SourceKey`" capability is minted alongside the permit. Both
`RecordTableField` (`BatchKeyField`) and `SingleRecordTableField` carry
`SourceKey` as a record component, but no current generator site asks "does
this field have a `SourceKey`" uniformly across the two — every site forks
on the field-permit identity to decide DataLoader-vs-direct dispatch. Per
the "Capability interfaces and sealed switches serve different roles"
principle, a `SourceBoundField` capability would relocate exhaustiveness
bookkeeping without removing any per-site fork. If a future site does want
to read `sourceKey()` uniformly across both, that's when the capability
earns its place.

## Phase 1 conventions

A handful of decisions ride through both phases below. Stating them once
here so the phase bodies don't litter "we picked X over Y":

- **Single mutation-field permit, `DmlKind` discriminator.** `INSERT`,
  `UPDATE`, `UPSERT` carry identical components on the model side; per-kind
  variation lives in the SQL emit, not the type. R75 ships one
  `MutationDmlRecordField` permit with a `DmlKind kind` field. (R12's four
  `DmlTableField` permits remain as-is; folding them the same way is a
  sibling Backlog item.)
- **No day-1 `DataFetcherResult` wrap.** Phase 1's mutation fetcher returns
  `Result<RecordN<...>>` directly. The `DataFetcherResult` carriage (and the
  `slots: List<SlotDescriptor>` component on the carrier) lands with R128
  alongside multi-field admission, where it earns its keep. R128 will
  introduce the wrap additively; Phase 1 doesn't need to anticipate it.
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
  `NoBacking` is plain payload Objects promoted by the trigger. The
  consumers that today read `fqClassName` and check for `null` all migrate
  to exhaustive `switch (PojoResultType)`: (a) the trigger's condition #1,
  (b) `MutationInputResolver.validateReturnType`'s `ResultReturnType` arm,
  (c) the `FetcherRegistrationsEmitter` filter that admits `PojoResultType`
  for fetcher registration, and (d) the R12 authored-payload emit path that
  reads `className`. The split lifts the sentinel overload at the type
  level: every consumer knows which case it's looking at from the variant,
  not from a nullable.
- **Two-step emit uniformly.** Direct-`@table`-return DML mutations
  (`createFilm: Film`) get the same two-step shape as payload-returning
  mutations (PK-only RETURNING in a tight transaction, follow-up SELECT
  outside it). The transaction-durability win applies to both; one emit
  pattern across both reduces the surface to maintain. The existing
  `MutationInsertTableField` / `MutationUpdateTableField` /
  `MutationUpsertTableField` permits carry sufficient information for the
  two-step emit without component changes: `tableInputArg.inputTable()
  .primaryKeyColumns()` for the RETURNING clause, `returnType.tableRef()`
  for the follow-up SELECT. The reshape is an emit-pattern change inside
  existing arms, not a model-shape change.
- **Trigger function name.** The classify-time helper is
  `tryResolveSingleRecordCarrier`. The earlier `unwrapPassthroughPayload`
  name was residue from the retired wire-format-unwrap design;
  `tryResolvePayloadShape` from an earlier R75 draft borrowed the GraphQL
  community's "payload" term where the rewrite model uses `PojoResultType`
  / `ResultType` vocabulary instead.
- **Validator threads classifier rejections.** `MutationInputResolver
  .validateReturnType`'s `ResultReturnType` arm substitutes the trigger's
  `Rejected` reason when the return type is a single-record-carrier
  candidate that failed a per-condition check. The trigger is consulted at
  classify time and at validator time; consolidating call sites further is
  a separate concern.


## Phase 1: record-returning DML mutations + tight transactions

Phase 1 admits payload-returning DML mutations by reading the SDL
structurally: the mutation classifies as a record-returning DML carrier;
the data field on the payload classifies as the new sibling permit
`ChildField.SingleRecordTableField` (introduced in the Foundation section
above) with a `SourceKey` whose `reader == ResultRowWalk`; DML emit
becomes two-step — PK-only `RETURNING` inside a tight transaction,
follow-up `SELECT` outside it. Direct-`@table`-return DML mutations get
the same two-step shape per the convention above.

### Model: one `MutationDmlRecordField` permit

```java
record MutationDmlRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    DmlKind kind,                       // INSERT | UPDATE | UPSERT
    Optional<ErrorChannel> errorChannel
) implements MutationField {}

enum DmlKind { INSERT, UPDATE, UPSERT }
```

`returnType` is the single-record carrier's `ResultReturnType` (no unwrap —
the SDL's structural truth). `tableInputArg` carries the input `@table`
exactly like the existing `MutationInsertTableField` etc. arms. The `kind`
discriminator drives per-DML-kind emit variation; the model shape is one
permit since the components are identical across the three kinds. R128
adds the `slots: List<SlotDescriptor>` component when multi-field
admission lands.

No `MutationDeleteRecordField` permit: DELETE-with-payload-return is
rejected at classify time. Returning pre-deletion state is incorrect by
construction; the row is gone before the response SELECT can read it.

### Trigger function

`BuildContext.tryResolveSingleRecordCarrier` is a pure structural test
returning a sealed `SingleRecordCarrierResolution`
(`Ok(SingleRecordCarrierShape)` / `NotCandidate` / `Rejected(Reason)`). Four
conditions:

1. The type is registered as `PojoResultType.NoBacking`. No-`@record` plain
   SDL Objects promote to that arm at type-classification time per the
   conventions above; `PlainObjectType` never reaches the trigger.
   `PojoResultType.Backed` (authored carriers with `@record`) falls into
   `NotCandidate`.
2. The SDL Object declares exactly one field — the data field. (R128
   widens to "exactly one `@table`-element field plus zero or more
   non-`@table`-element slot fields".)
3. That single field's element type is registered as `TableBackedType`.
   (Phase 2 widens to admit record-backed `ResultType` elements.)
4. The data field carries no graphitron-domain directive (`@service`,
   `@sourceRows`, `@reference`, `@nodeId`, `@field`, `@asConnection`,
   `@splitQuery`, `@externalField`, `@condition`, `@lookupKey`,
   `@notGenerated`, `@tableMethod`, `@defaultOrder`, `@orderBy`,
   `@multitableReference`). Pure-metadata directives (`@deprecated`, custom
   non-graphitron directives) are off the list.

The trigger is consulted at the mutation classifier (admitting the carrier
return as `MutationDmlRecordField`) and at the schema-builder's per-type
pass (admitting the data field as `SingleRecordTableField`). The same
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
- *Table-equality admission check.* After `Ok` and the `DELETE` check, the
  classifier compares the trigger's resolved data-field element table
  against `tableInputArg.inputTable()`. Mismatch rejects with `"payload
  '<typeName>' data field element type '<elementName>' is bound to table
  '<dataTable>', which does not match @table input table '<inputTable>';
  payload-returning DML mutations require the data field's table to equal
  the DML's input table"`. This is the producer-side replacement for the
  retired `passthrough-payload.data-table-equals-dml-target` check; the
  validator threads the same rejection via the
  validator-mirrors-classifier path.

  *Load-bearing.* This producer wears
  `@LoadBearingClassifierCheck(key = "mutation-dml-record-field.data-table-
  equals-input-table", ...)`. Two consumer sites wear the paired
  `@DependsOnClassifierCheck`: (a) the mutation-fetcher emit that builds
  `RETURNING tableInputArg.inputTable().primaryKeyColumns()` (relies on
  the input-table's PK columns being the same shape the data field's
  follow-up SELECT will read), and (b) the data-field fetcher emit that
  builds `where(TABLE.PK.in(source.getValues(TABLE.PK)))` (relies on the
  upstream Result's row type matching the data-field's element table's
  PK columns). `LoadBearingGuaranteeAuditTest` will surface relaxation
  of this check as orphaned consumers, the same way it does for the
  three R38 `source-key.*` keys.

### Data-field classification: `SingleRecordTableField`

The schema-builder's per-type pass classifies the data field as
`ChildField.SingleRecordTableField` (the new sibling permit introduced in the
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
The `Reader.ResultRowWalk` arm on the `SourceKey` signals the single-record
shape; existing `RecordTableField` emit for DataLoader-batched parents
(`reader instanceof Reader.ColumnRead` etc., established in R38) stays on
the DataLoader path, unchanged.

### Generator emit

The mutation's fetcher runs the DML inside `dsl.transactionResult(tx ->
...)` and returns the `Result<RecordN<...>>` directly:

```java
public static Result<Record1<Integer>> createFilmsPayload(
        DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    List<Map<?, ?>> in = (List<Map<?, ?>>) env.getArgument("in");
    if (in.isEmpty()) {
        return DSL.using(dsl.configuration()).newResult(Tables.FILM.FILM_ID);
    }
    return dsl.transactionResult(tx -> DSL.using(tx)
        .insertInto(Tables.FILM, Tables.FILM.TITLE, Tables.FILM.LANGUAGE_ID)
        .valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
        .returningResult(Tables.FILM.FILM_ID)
        .fetch());
}
```

The transaction commits when `transactionResult` returns; the materialised
`Result<Record1<Integer>>` outlives it. R128 will widen the return shape to
`DataFetcherResult<Result<...>>` when multi-field admission ships, but
Phase 1 doesn't need it.

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
  `passthrough-payload.data-table-equals-dml-target`. Renamed and relocated
  rather than retired wholesale: the new key is
  `mutation-dml-record-field.data-table-equals-input-table`, produced at
  `FieldBuilder.classifyMutationField`'s admission step (see "Mutation-field
  classification" above) and consumed at the mutation-fetcher RETURNING
  emit + the data-field fetcher's WHERE-PK-IN emit. R75's new `SourceKey`
  invariant (`source-key.result-row-walk-cardinality-matches-upstream-
  result`) pins cardinality only — table-equality is a cross-component
  concern between the data field's `returnType` and the mutation's
  `tableInputArg`, which the classifier-admission step is the natural site
  for; the `@LoadBearingClassifierCheck` annotation pair preserves the
  audit discipline at the new location.

### Tests

**Pipeline-tier** (`graphitron/src/test/`), `SingleRecordCarrierPipelineTest`
(renamed from `PassthroughPayloadPipelineTest` to align with the model
vocabulary):

- Per-`DmlKind ∈ {INSERT, UPDATE, UPSERT}`:
  - Mutation field classifies as `MutationDmlRecordField` with the
    expected `kind` for single-record-carrier returns.
  - Data field classifies as `ChildField.SingleRecordTableField` (the new
    sibling permit) with the expected `SourceKey` shape:
    `reader instanceof Reader.ResultRowWalk`, `path` empty,
    `wrap instanceof Wrap.Record`, `cardinality` matching input arg,
    `columns == inputTable.primaryKeyColumns()`. The permit does not
    implement `BatchKeyField`; no `loaderRegistration` slot to assert.
- Carrier type promotes to `PojoResultType.NoBacking`; authored carriers
  with `@record(record: {className: ...})` continue to promote to
  `PojoResultType.Backed`. Exhaustive-switch assertion over the sealed
  sub-taxonomy on a small consumer (validator helper) confirms the split
  is load-bearing rather than cosmetic.
- DELETE-with-carrier return rejects with the per-mismatch message.
- Trigger rejections (multi-field, scalar element, interface element,
  graphitron-domain directives on the data field, `@table`-typed carrier,
  `@record`-with-`className`).
- Mutation-classifier table-equality rejection: SDL with
  `Mutation.x(in: FilmInput!): ActorPayload` (data field's element table
  differs from `tableInputArg.inputTable()`) rejects with the per-mismatch
  message.
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
  `SingleRecordTableField` arm.

**Compilation-tier** (`graphitron-sakila-example`): SDL fixture from the
shipped attempt survives — `FilmPayload` + the `create / update / upsert`
mutations — minus the DELETE-with-carrier variant. `mvn compile -pl
:graphitron-sakila-example -Plocal-db` verifies the two-step emit produces
compile-correct DSL on both carrier-return and direct-`@table`-return
mutations.

**Execution-tier**
(`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`),
`SingleRecordCarrierDmlTest` (renamed from `PassthroughPayloadDmlTest`):

- INSERT / UPDATE / UPSERT round-trip: row written, PK returned by the
  mutation's fetcher as a bare `Result<RecordN<...>>`, follow-up SELECT
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

**Audit-tier:** R75's contributions to the load-bearing-check inventory
are two new keys:

- `source-key.result-row-walk-cardinality-matches-upstream-result`
  (cardinality invariant on `SourceKey`'s compact constructor when
  `reader == Reader.ResultRowWalk`).
- `mutation-dml-record-field.data-table-equals-input-table` (table-equality
  produced at `FieldBuilder.classifyMutationField`'s admission step,
  consumed at the mutation-fetcher RETURNING emit and the data-field
  fetcher WHERE-PK-IN emit).

`LoadBearingGuaranteeAuditTest` walks the rewrite module's compiled output
and fails on any consumer whose key has no producer (or duplicate producer)
for both keys.

### Out of scope for Phase 1

- **Multi-field carriers (data field + non-data slots).** R128 territory.
- **`@service` mutations returning single-record carriers.** Phase 2
  territory.
- **Record-element data fields.** Phase 2 territory.
- **Restructuring R12's authored-carrier emit.** Tracked separately.
- **DELETE returning a carrier of pre-deletion data.** Rejected at classify
  time; not coming back.
- **Component-type narrowness alignment.** R75's `MutationDmlRecordField`
  uses narrow `ResultReturnType`; existing `MutationServiceRecordField`
  uses broad `ReturnTypeRef`. Aligning the existing carrier to the narrow
  shape is a sibling Backlog item.

## Phase 2: @service mutations and record-element data

Phase 1 covers `@mutation` (DML) single-record carrier returns with
`@table`-element data fields. Phase 2 extends along two orthogonal axes:

- **Consumer.** The trigger admits `@service` mutations whose return type is a
  single-record carrier. The `@service` consumer constructs the return value
  directly; graphitron registers the data-field fetcher on the carrier, and
  the existing `@service` wrapper unwrap (`Optional<T>`, `CompletableFuture<T>`,
  `Mono<T>`) handles wrapper composition.
- **Element kind.** The data field's element type may be a record-backed
  `ResultType` in addition to `TableBackedType`. Service methods may return a
  domain record (Java record or POJO); the data field's classification carries
  the record shape and graphql-java's per-field fetchers on the record element
  handle accessor reads. The record-backed `ResultType` classification today
  comes from `@record(record: {className: ...})`; after R96 Phase 3 it comes
  from `@service`-method-return-type introspection. R75's trigger reads
  `ResultType`, not the producing signal.

### Trigger extension

Condition #3 changes from "the data field's element type is registered as
`TableBackedType`" to "registered as `TableBackedType` or `ResultType`". The other
conditions are unchanged from Phase 1.

The trigger result's data-element descriptor becomes a sealed sub-taxonomy:

```java
sealed interface DataElement {
    String name();
    FieldWrapper wrapper();
    record Table(String name, TableRef table, FieldWrapper wrapper) implements DataElement {}
    record Record(String name, String fqClassName, FieldWrapper wrapper) implements DataElement {}
}
```

Phase 1 callers see `DataElement.Table`. Phase 2 introduces the `Record` arm.

### Mutation classification

`@mutation` (DML) admits only `@table`-element data — Phase 1 unchanged.
Record-element data on a DML mutation is out of scope (would require a
"DML row → domain record" conversion step at the emitter; tracked
separately).

`@service` mutations classify through the existing `MutationServiceRecordField`
permit when the SDL return is a single-record carrier type. The service
method's declared return type is matched against the data field's element kind:

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
`ChildField.SingleRecordTableField` classification as Phase 1, with the
single-record `SourceKey` (`reader == ResultRowWalk`). The upstream
`Result<RecordN<...>>` is the service method's return after wrapper unwrap;
the data-field fetcher reads it via `sourceKey.extractSourceRows(env)` and
runs the response SELECT — same shape as Phase 1.

For record-element data on a `@service` mutation: the data field classifies
as a new sibling permit `ChildField.SingleRecordIdentityField`. The service
method's returned domain record IS the carrier's source value; the data
field's value IS that source value verbatim. The fetcher emit is identity
passthrough — `env -> env.getSource()` — with no `SourceKey` synthesis (no
source rows to extract; the parent's value is the data field's value).

```java
record SingleRecordIdentityField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType
) implements ChildField {}
```

The permit declines `TableTargetField` (no table to target — the element
is record-backed) and `BatchKeyField` (no DataLoader); it carries the
resolved `ResultReturnType` for the element type. Naming-wise it parallels
`SingleRecordTableField`: same "Single" prefix signalling the
single-parent-per-request shape, "Identity" naming the read mechanism
(parent = value), in lieu of "Table" for the record-element case.

The fetcher emit dispatches on `SingleRecordIdentityField` at the same seam
as `SingleRecordTableField`: one new arm in `FetcherEmitter.dataFetcherValue`
or its sibling.

This is a deliberate spec-time fork rather than an implementer call.
Overloading `ConstructorField` with an identity-accessor variant would
compromise `ConstructorField`'s "read a property off a parent" framing
and bake the principles doc's "god accessor whose meaning depends on the
variant" smell into the taxonomy; a sibling permit keeps the read-mechanism
axis explicit at the type-system level.

### Wrapper composition

Service methods may return `T`, `Optional<T>`, `CompletableFuture<T>`,
`Mono<T>`. The existing `@service` unwrap strips these wrappers before
classifying the inner `T`. `DataFetcherResult<T>` is treated as another
wrapper layer: a method returning `DataFetcherResult<T>` admits with the
same trigger as one returning `T`.

The matrix of wrapper × element kind (4 wrappers × 2 element kinds = 8 combinations)
is verified at the execution tier.

### Out of scope for Phase 2

- Record-element data on `@mutation` (DML). Tracked separately.
- Multi-field carriers on `@service` mutations. R128 territory; the
  `@service` consumer-side `DataFetcherResult.localContext` carriage lands
  there alongside the trigger's multi-field extension.
- `@service` queries returning single-record carriers. The trigger is
  consumer-agnostic so the classification works, but the per-query fetcher
  emit hasn't been audited end-to-end. Treat as a follow-up.

### Tests

**Pipeline-tier** additions to `SingleRecordCarrierPipelineTest`:

- Record-element data field classifies as
  `ChildField.SingleRecordIdentityField` (the new sibling permit) carrying
  the resolved `ResultReturnType`. The permit declines `TableTargetField`
  and `BatchKeyField`; no `SourceKey` slot to assert.
- `@service` mutation returning a single-record carrier admits at the
  service classifier; no `UnclassifiedField` at the mutation site.
  Parameterised over `{Table, Record}`.
- Service-method-return-type mismatch rejects with the per-mismatch message.
  Parameterised over `{Table, Record}`.
- Wrapper composition: service method's `Optional<T>` /
  `CompletableFuture<T>` / `Mono<T>` / `DataFetcherResult<T>` unwraps to
  the inner `T` for trigger matching. Parameterised over
  `{T, Optional, CompletableFuture, Mono, DataFetcherResult} ×
  {Table, Record}`.
- `fetcherEmitter_singleRecordArms`: structural pin that
  `FetcherEmitter.dataFetcherValue` dispatches `SingleRecordTableField`
  and `SingleRecordIdentityField` as separate `instanceof` arms (no
  overload of `ConstructorField`).

**Execution-tier**
(`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`):
new `SingleRecordCarrierServiceTest` parameterising over
`(elementKind, wrapper)`:

- SDL: two carrier variants — `@table`-element via the existing sakila
  `Actor`; record-element via an `ActorRecord` Java record bound via the
  signal current at fixture-authoring time (today
  `@record(record: {className: "...sakila.ActorRecord"})` on the SDL
  type; post-R96 Phase 3, `@service`-method-return-type introspection
  on the same `ActorRecord` Java class).
- Per-wrapper drivers (`T`, `Optional<T>`, `CompletableFuture<T>`,
  `Mono<T>`) for each element kind: invoke the service mutation against
  the testcontainer, assert response shape and follow-up reads. 8 cases.

**Audit-tier:** no new load-bearing keys in Phase 2.

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
  `@record(className)` declaration on the SDL carrier type and no Java
  class on disk for the carrier.
- `SingleRecordCarrierPipelineTest`'s admission cases pass for INSERT /
  UPDATE / UPSERT; DELETE-with-carrier rejects with the per-mismatch
  message. The data-field classifies as `ChildField.SingleRecordTableField`
  (the new sibling permit, not implementing `BatchKeyField`) with the
  expected `SourceKey` shape (`reader instanceof Reader.ResultRowWalk`,
  `path` empty, `wrap instanceof Wrap.Record`, `cardinality` matching
  input arg, `columns == inputTable.primaryKeyColumns()`).
- The mutation classifier rejects table-equality mismatches with the
  per-mismatch message naming the SDL carrier, its data field's element
  table, and the DML's input table.
- `PojoResultType` ships split into `Backed(ClassName)` / `NoBacking()`;
  no-`@record` plain SDL Objects passing the trigger promote to
  `NoBacking`; authored `@record` carriers (with or without className)
  promote to `Backed`. Downstream consumers that previously read
  `fqClassName` and checked for `null` switch over the two arms
  exhaustively.
- `SingleRecordCarrierDmlTest`'s execution-tier driver passes against
  the sakila testcontainer for INSERT / UPDATE / UPSERT, verifying the
  two-step emit (DML in transaction + follow-up SELECT, both halves
  typed via `SourceKey`) compiles and runs end-to-end.
- The headline correctness pin (`dml_persists_when_followupSelect_throws`)
  passes for carrier-returning DML, *and* the direct-`@table`-return
  variant (`dml_persists_when_directReturnSelect_throws`) passes for
  `createFilm: Film` etc. — the durability invariant the reshape exists
  to establish holds across both surfaces the two-step emit applies to.
- The earlier wire-format-unwrap shipped attempt retires cleanly:
  `PassthroughDataField`, `IdentityPassthrough`, the
  `resolveReturnType` short-circuit, the `FetcherRegistrationsEmitter`
  filter widening, the `passthrough-payload.data-table-equals-dml-target`
  load-bearing key, and the `PASSTHROUGH_PAYLOAD_DATA_FIELD` fixture are
  all gone.
- Direct-`@table`-return DML mutations (`createFilm: Film` etc.) continue
  to work under the new two-step emit pattern; existing fixtures regress
  without changes; the structural two-step-emit pin guards against a
  silent revert to single-statement emit.
- Authored carriers with `@record(record: {className: ...})` are
  unaffected (R12 scope unchanged).
- R75's contributions to the load-bearing-check inventory —
  `source-key.result-row-walk-cardinality-matches-upstream-result` and
  `mutation-dml-record-field.data-table-equals-input-table` — declared,
  consumed, and clean under `LoadBearingGuaranteeAuditTest`.

**Phase 2:**

- `SingleRecordCarrierPipelineTest`'s Phase 2 admission cases pass for
  both element kinds (`{Table, Record}`) and all wrapper kinds
  (`{T, Optional, CompletableFuture, Mono, DataFetcherResult}`).
- `SingleRecordCarrierServiceTest`'s execution-tier matrix passes (8
  driver cases over 2 element kinds × 4 wrappers).
- `ServiceCatalog`'s `@service` mutation classifier admits
  single-record-carrier returns; the trigger's `DataElement.{Table |
  Record}` sealed sub-taxonomy distinguishes the two element kinds.
- Authored carriers with `@record(record: {className: ...})` remain
  unaffected across both phases (regression pin via the existing
  authored-carrier fixtures).
