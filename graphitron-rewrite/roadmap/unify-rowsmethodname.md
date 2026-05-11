---
id: R38
title: "Reshape `BatchKey` into `SourceKey` + unify the rows-method seam"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
---

# Reshape `BatchKey` into `SourceKey` + unify the rows-method seam

`BatchKey` carries ten permits across two sub-hierarchies. A `Mapped*` family
doubles them on a container axis (`newDataLoader` vs `newMappedDataLoader`); a
`Lifter*` / `Accessor*` family doubles them again on a reader axis
(`@sourceRows` lifter vs `@record` accessor vs catalog-FK column read). The
rows-method seam — declaration scaffolding, BatchLoader lambda, DataFetcher
dance — is handcrafted across five emitter sites that all dispatch on the same
conflated permit shape, every one of them recovering an axis the type system
already knows about (through redundant permits) but doesn't expose as
orthogonal properties.

These are facets of the same root cause: `BatchKey` is the wrong shape for
what the system dispatches on. R38 reshapes the model so the dispatch axes
are first-class. The unified rows-method emitter falls out as a consequence
rather than as a parallel cleanup.

## The reshape

A field with a DataLoader-backed source side becomes three concepts:

- **`SourceKey`** — singular per-field metadata. The shape descriptor the
  classifier produces and consumers (validator, fetcher emitter, rows-method
  emitter) read off.
- **`SourceRow`** — plural runtime instance. A row of data flowing at
  execution time. Java type dictated by `SourceKey.wrap × columns`; not a
  generated type.
- **`LoaderRegistration`** — small per-field record. DataLoader identity and
  container kind.

### `SourceKey`

```java
record SourceKey(
    List<ColumnRef> columns,         // entry-point columns: match target's columns when path
                                     // empty; first-hop source-side when path non-empty
    List<JoinStep.FkJoin> path,      // empty = target-aligned; non-empty = FK chain to target
    Wrap wrap,                       // ROW | RECORD | TABLE_RECORD
    Cardinality cardinality,         // ONE | MANY (per source)
    Reader reader                    // sealed sub-level; see below
) {
    public TableRef target() {
        return path.isEmpty()
            ? columns.getFirst().table()
            : path.getLast().targetTable();
    }
}

enum Wrap { ROW, RECORD, TABLE_RECORD }
enum Cardinality { ONE, MANY }
```

`target` is derivable from path/columns and exposed as a method, not stored.
The full projection from today's ten `BatchKey` permits onto the new shape
lives inline below under "`BatchKey` → `SourceKey` projection".

### `Reader` (sealed sub-level)

```java
sealed interface Reader {
    record ColumnRead() implements Reader {}                                // catalog FK on parent record
    record AccessorCall(MethodRef accessor) implements Reader {}            // typed accessor on @record parent
    record SourceRowsCall(MethodRef lifter) implements Reader {}            // @sourceRows static lifter
    record ServiceTableRecord(Class<?> recordType) implements Reader {}     // service returns TableRecord<X>
    record ServiceUntypedRecord() implements Reader {}                      // service returns Record<>
}
```

Five permits in R38. R75 adds a sixth (`ResultRowWalk` — walk a
`Result<RecordN>` from upstream DML); that addition lives in R75's spec, not
R38's. R38's job is the foundation that makes R75's permit a one-line
addition rather than a 12th `BatchKey` permit.

The five permits describe a uniform axis: *the rows-method body's input
contract* — what data the body reads to produce its output. For SQL-side
bodies (`ColumnRead` / `AccessorCall` / `SourceRowsCall`), the input is
parent-side data; for service-side bodies (`ServiceTableRecord` /
`ServiceUntypedRecord`), the input is the service-return shape. Every
dispatch site (rows-method body, validator invariant, classifier projection)
treats `Reader` as "body input contract", not as "where the data comes from"
— the body emitter only ever needs the contract.

The projection is field-shape-driven, not `BatchKey`-permit-driven. The
implementing `BatchKeyField` permit is the determining axis for `Reader`; the
same `BatchKey.ParentKeyed` permit can land on any of the five Reader permits
depending on which field type carries it. Service-side fields classify on
`ServiceCatalog.reflectServiceMethod`'s existing return-type axis:
`ServiceTableField` (whose `ReturnType` is `TableBoundReturnType`) projects to
`ServiceTableRecord(recordType)` carrying the table's generated jOOQ Record
class; `ServiceRecordField` (whose `ReturnType` is `ResultReturnType` or
scalar) projects to `ServiceUntypedRecord`.

### `BatchKey` → `SourceKey` projection

Complete projection from today's ten `BatchKey` permits onto the new
`(SourceKey, Reader, LoaderRegistration)` triple. The implementing
`BatchKeyField` permit determines `Reader`; the `BatchKey` permit refines the
SQL-side cases and drives `LoaderRegistration.container` and `SourceKey.wrap`.

The Field × `BatchKey` axis is narrowly typed today: `SplitTableField` and
`SplitLookupTableField` declare `BatchKey.RowKeyed` directly (catalog-FK only);
`RecordTableField` and `RecordLookupTableField` declare
`BatchKey.RecordParentBatchKey` (four-permit sub-seal: `RowKeyed`,
`LifterLeafKeyed`, `LifterPathKeyed`, `AccessorKeyedSingle`,
`AccessorKeyedMany`); `ServiceTableField` and `ServiceRecordField` declare
`BatchKey.ParentKeyed` (six-permit sub-seal driven by the developer's
`@service`-source declaration).

| `BatchKeyField` permit (today) | `BatchKey` permit (today)                  | `Reader` (new)                                    |
| ------------------------------ | ------------------------------------------ | ------------------------------------------------- |
| `SplitTableField`              | `RowKeyed`                                 | `ColumnRead`                                      |
| `SplitLookupTableField`        | `RowKeyed`                                 | `ColumnRead`                                      |
| `RecordTableField`             | `RowKeyed`                                 | `ColumnRead`                                      |
| `RecordTableField`             | `LifterLeafKeyed`, `LifterPathKeyed`       | `SourceRowsCall(lifter)`                          |
| `RecordTableField`             | `AccessorKeyedSingle`, `AccessorKeyedMany` | `AccessorCall(accessor)`                          |
| `RecordLookupTableField`       | (same three rows as `RecordTableField`)    | (same)                                            |
| `ServiceTableField`            | (any `ParentKeyed` permit; six)            | `ServiceTableRecord(returnType.recordType())`     |
| `ServiceRecordField`           | (any `ParentKeyed` permit; six)            | `ServiceUntypedRecord`                            |

`LoaderRegistration.container` projects orthogonally on the `BatchKey` axis:
`MappedRowKeyed`, `MappedRecordKeyed`, `MappedTableRecordKeyed`, and
`AccessorKeyedMany` (the `loader.loadMany` contract) → `MAPPED_SET`; all other
permits → `POSITIONAL_LIST`.

`SourceKey.cardinality` follows the field's wrapper
(`returnType().wrapper().isList()` → `MANY`, scalar/single → `ONE`), with
`AccessorKeyedMany` forcing `MANY` (per-element walk against the parent record's
list).

`SourceKey.wrap` follows today's `BatchKey.keyElementType()` enumeration:
`RowN<…>` → `ROW` (`RowKeyed`, `MappedRowKeyed`, `LifterLeafKeyed`,
`LifterPathKeyed`); `RecordN<…>` → `RECORD` (`RecordKeyed`, `MappedRecordKeyed`,
`AccessorKeyedSingle`, `AccessorKeyedMany`); typed `TableRecord` subtype →
`TABLE_RECORD` (`TableRecordKeyed`, `MappedTableRecordKeyed`, plus the
service-side `ServiceTableRecord` Reader path).

`SourceKey.path` and `SourceKey.columns` derive from existing data: the
catalog-FK arms (`RowKeyed`) contribute `parentKeyColumns` as `columns` with
empty `path`; the lifter arms contribute the lifter's parent-side columns and
the lifted `JoinStep`s as `path`; the accessor arms contribute the accessor's
element-PK `targetKeyColumns` with the `LiftedHop` as a single-element `path`.
Service-side permits derive `path` and `columns` from the existing `joinPath`
and the service-source declaration's column shape.

### `SourceRow`

The runtime form of what `SourceKey` describes. Java type dictated by `wrap
× columns`:

- `SourceKey(wrap=ROW, columns=[FILM_ID])` → `Row1<Integer>`.
- `SourceKey(wrap=RECORD, columns=[FILM_ID, LANGUAGE_ID])` →
  `Record2<Integer, Integer>`.
- `SourceKey(wrap=TABLE_RECORD, reader=ServiceTableRecord(FilmRecord.class))`
  → `FilmRecord`.

One `SourceKey` per field; potentially many `SourceRow`s per source per
fetch. `SourceRow` is documentation of the type discipline, not a generated
type.

### `LoaderRegistration`

```java
record LoaderRegistration(
    String loaderName,           // existing buildDataLoaderName output
    boolean valueIsList,         // false → load(key)→Record; true → load(key)→List<Record>
    Container container          // POSITIONAL_LIST | MAPPED_SET
) {}

enum Container { POSITIONAL_LIST, MAPPED_SET }
```

`Container` drives the `newDataLoader` vs `newMappedDataLoader` choice. The
same `SourceKey` shape can be loaded into either container (positional from a
uniqueness invariant; mapped from a one-to-many or set-valued relation), so
container is a per-field decision that doesn't belong on `SourceKey`. The
`Mapped*` family in today's `BatchKey` collapses to `container = MAPPED_SET`.

The R75 rooted case has no `LoaderRegistration` — the DataFetcher reads
`env.getSource()` and uses `SourceKey` directly to extract `SourceRow`
instances. `LoaderRegistration` being a separate value (not a field on
`SourceKey`) is what makes that absence representable.

### Per-fetch dispatch

`load(sourceRow)` vs `loadMany(sourceRows)` reads `sourceKey.cardinality()`
at the call site. Today's `LoaderDispatch` enum on `RecordParentBatchKey`
becomes redundant after the reshape and is deleted in Phase 3.

### Cross-axis invariants

`SourceKey`'s compact constructor rejects shapes that violate Reader-specific
invariants:

- `SourceRowsCall` → `wrap == ROW`. The `@sourceRows` lifter contract pins
  output to entry-point columns shaped as `Row<...>`.
- `AccessorCall` returning `List<X>` → `cardinality == MANY` and
  `wrap == RECORD`.
- `ServiceTableRecord` with `recordType == target().recordType()` →
  `path.isEmpty()`. Walking past target is structurally redundant; the
  service already produced a target-aligned record.
- (R75 will add: `ResultRowWalk` cardinality matches the upstream Result's
  row count.)

These invariants are load-bearing. The rows-method emitter's `Reader`
dispatch relies on them; each gets a `@LoadBearingClassifierCheck`
declaration consumed by an `@DependsOnClassifierCheck` on the corresponding
emit site.

### `SourceKeyResolver`

Sibling to `OrderByResolver`, `PaginationResolver`, `LookupKeyDirectiveResolver`,
`SourceRowDirectiveResolver`, `ClassAccessorResolver`. Lives at
`no.sikt.graphitron.rewrite.SourceKeyResolver`. Single-concern projection: reads
the same classification context the existing field classifiers read (catalog FK,
`@record` accessor, `@sourceRows` lifter, service-return reflection) and produces
a `SourceKey`.

`LoaderRegistrationResolver` is the sibling that produces `LoaderRegistration`
from the same context plus the field's container decision.

## Rows-method seam, after the reshape

The narrow R38's five-pattern catalog re-grounds against the new model. The
seam shrinks to one entry per concern.

1. **Naming.** `default String rowsMethodName()` on `BatchKeyField` returning
   `"rows" + capitalize(name())`. The two service-backed leaves
   (`ServiceTableField`, `ServiceRecordField`) keep their `load<X>` overrides
   as documented exceptions; the four SQL leaves' overrides become redundant
   and delete in Phase 3.
2. **Declaration scaffolding.** `RowsMethodSkeleton.build(String methodName,
   TypeName outerReturnType, TypeName keysContainerType, CodeBlock
   graphitronContextCall, RowsMethodBody body)` emits modifiers, parameters,
   return type, and dispatches the body via exhaustive switch on
   `RowsMethodBody`. `RowsMethodBody` is a sealed type with one variant per
   body shape: `SqlSplitTable`, `SqlSplitLookupTable`, `SqlRecordTable`,
   `SqlRecordLookupTable`, `Service`. Each construct site projects from the
   field's `(SourceKey.reader(), LoaderRegistration.container())` pair to the
   matching `RowsMethodBody` permit. The four SQL permits emit the
   empty-input gate and the `DSLContext dsl = ...` line; the `Service` permit
   emits the dsl line per `callShape` and omits the gate (matching today's
   service-path behaviour; see "Out of scope").
3. **Lift.** SQL-side `parentRows[]` / VALUES-table assembly stays internal
   to the `Sql*` body permits via `SplitRowsMethodEmitter
   .emitParentInputAndFkChain` (line 148, `PreludeBindings` line 120);
   service-side consumes the typed key container directly. Unchanged.
4. **Call site (BatchLoader lambda).** `RowsMethodCall.batchLoaderLambda(
   String rowsMethodName, TypeName keyType, LoaderRegistration registration)
   -> CodeBlock` emits the `(keys, batchEnv) -> { dfe = ...; return
   CompletableFuture.completedFuture(rowsXxx(keys, dfe)); }` lambda once.
   Single source of truth for method name, keys-container type (`Set` or
   `List` per `LoaderRegistration.container()`), and result type.
5. **DataFetcher dance.** `DataLoaderFetcherEmitter.build(String fieldName,
   TypeName keyType, TypeName loaderValueType, TypeName outerReturnType,
   LoaderRegistration registration, CodeBlock graphitronContextCall, CodeBlock
   batchLoaderLambda, CodeBlock keyExtraction, CodeBlock asyncWrapTail) ->
   MethodSpec` emits the full DataFetcher: name resolution
   (`buildDataLoaderName`), `computeIfAbsent` registry call wrapping the
   pre-built `batchLoaderLambda` (factory chosen by `container()`),
   pre-built `keyExtraction` (the parent-side projection from `dfe.getSource()`
   to the per-fetch key value, supplied by the caller because the projection
   is parent-shape-specific), `loader.load(key, env)` (or `loader.loadMany`
   per `dispatch()`), then the pre-built `asyncWrapTail`.

Every dispatch in the seam reads off `SourceKey` and `LoaderRegistration`
rather than reconstructing the axis from `instanceof BatchKey.X`.

## Phasing

Three phases, each one job. Phase 1 is purely additive (new code, no
consumers); Phase 2 is the single flip across all consumers; Phase 3 deletes
the dead code Phase 2 orphans. Risk concentrates in Phase 2; Phases 1 and 3
are mechanically simple.

### Phase 1: additive

Land the new model alongside the existing one. Nothing routes through it
yet. Build stays green; nothing currently consumes the new types, so emit is
unchanged by construction. Each new emitter and resolver ships with unit
tests against fixture call shapes that mirror what Phase 2 will feed it, so
API misfit surfaces here, not at the flip.

- **`SourceKey`, `Reader`, `LoaderRegistration`** records in
  `no.sikt.graphitron.rewrite.model`. Compact-constructor invariants per
  `Reader`. Each invariant gets a `@LoadBearingClassifierCheck` declaration.
  *(Phase 1a, shipped: model types + `BatchKeyField.rowsMethodName()` interface
  default, `SourceKeyTest` + `LoaderRegistrationTest`.)*
- **`SourceKeyResolver` + `LoaderRegistrationResolver`** in
  `no.sikt.graphitron.rewrite`, alongside `OrderByResolver` etc. Each ships
  per-`Reader` projection tests from a fixture classification context.
  *(Phase 1b, shipped: both resolvers project from today's `BatchKey` onto
  the new `SourceKey` / `LoaderRegistration` shape; one fixture per
  `(BatchKeyField permit × BatchKey permit)` row in the projection table.
  Phase 3 re-grounds the resolvers against upstream classification primitives
  directly.)*
- **`RowsMethodSkeleton`** + sealed `RowsMethodBody` (five permits as
  enumerated above). The skeleton's exhaustive switch is the dispatch site;
  no predicate accessors on `RowsMethodBody`.
- **`RowsMethodCall.batchLoaderLambda`**, single factory.
- **`DataLoaderFetcherEmitter.build`**, single entry.
  *(Phase 1c, shipped: `RowsMethodBody` (model package) carries opaque
  `CodeBlock content()` per permit so the skeleton is decoupled from body
  construction; `RowsMethodSkeleton` (generators package) owns the
  declaration scaffolding (modifiers, parameters, return type, empty-input
  gate for SQL permits, `DSLContext dsl` resolution) and dispatches body
  framing per permit; `RowsMethodCall.batchLoaderLambda` folds the
  keys-container axis onto `LoaderRegistration.container()`;
  `DataLoaderFetcherEmitter.build` emits the unified DataFetcher reading
  `LoaderRegistration.dispatch()` (`LOAD_ONE` / `LOAD_MANY`) for the
  loader-dispatch shape. 15 unit tests pin framing, container choice, and
  dispatch.)*

  *(Phase 1 follow-up, shipped after architect self-review: dropped
  vestigial `LoaderRegistration.loaderName` (the runtime path-scoped name
  is computed at fetcher emit time from `env.getExecutionStepInfo().getPath()`
  and cannot be carried on a build-time record); lifted the load-vs-loadMany
  predicate from a `DataLoaderFetcherEmitter.build` parameter onto
  `LoaderRegistration.Dispatch` so the resolver projects it once instead of
  Phase 2's three callers each re-deriving the `AccessorKeyedMany` predicate;
  clarified the `keyExtraction` parameter contract that the block may
  short-circuit before reaching the dispatch line (legitimising
  `buildSplitQueryDataFetcher`'s null-FK case under the unified emitter).)*
- **Interface default** on `BatchKeyField`: `default String rowsMethodName()
  { return "rows" + capitalize(name()); }`. Javadoc tightened: replace
  "naming convention is determined by each implementing type independently"
  (`BatchKeyField.java:17-18`) with "DataLoader-backed variants default to
  `rows<Name>`; service-backed variants override to `load<Name>` to mark the
  body as a service delegation." The four SQL leaves keep their (now
  redundant) overrides; the two service leaves keep their `load<X>` overrides
  as the documented exception.

### Phase 2: flip

Migrate every consumer in one PR. The diff is delete-and-replace. The
contract is functional equivalence, not byte-identical emission: existing
compilation-tier and execution-tier tests pass unchanged, plus the new
structural pins (see Tests below). The new emitters are clean implementations
of the unified shape, free to drop per-site comment scaffolding and factor
duplication that exists today only because each site was written separately;
their structural correctness is enforced by the three-tier passing build,
not by text-level comparison.

- **Field classifiers populate `SourceKey` + `LoaderRegistration`** alongside
  today's `BatchKey`. Both values populate; `BatchKey` is computed for
  compatibility but no longer dispatched on past Phase 2.
- **Five rows-method emitters → `RowsMethodSkeleton`.** Each construct site
  reads `SourceKey.reader()` + `LoaderRegistration.container()`, builds the
  matching `RowsMethodBody` permit, hands it to the skeleton:
  - `SplitRowsMethodEmitter.buildForSplitTable` / `buildForSplitLookupTable`
    / `buildForRecordTable` / `buildForRecordLookupTable` build the matching
    `Sql*` permit; the skeleton's switch invokes `emitParentInputAndFkChain`
    (Pattern 3, unchanged) and emits the SELECT body.
  - `TypeFetcherGenerator.buildServiceRowsMethod` (line 2823) builds the
    `Service` permit carrying `MethodRef` + `callShape`; the skeleton emits
    `return <callTarget>.<methodName>(<args>);`.
- **Three BatchLoader-lambda call sites → `RowsMethodCall.batchLoaderLambda`.**
  Replace the inline lambda blocks at `TypeFetcherGenerator.java:2762`,
  `:2922`, `:3022`, each with one `RowsMethodCall.batchLoaderLambda(bkf)`
  call.
- **Three DataFetcher emitters → `DataLoaderFetcherEmitter`.** Migrate
  `buildServiceDataFetcher` (line 2733), `buildSplitQueryDataFetcher` (line
  2888), `buildRecordBasedDataFetcher` (line 2995). Each shrinks from a
  multi-block builder to one `DataLoaderFetcherEmitter.build(...)` call.
- **Per-fetch dispatch reads `SourceKey.cardinality()`** at the call site.
  `LoaderDispatch` enum is no longer consulted (deletion in Phase 3).

*(Phase 2, shipped: the field-classifier surface stayed unchanged; consumers
project to `LoaderRegistration` via `LoaderRegistrationResolver.resolve(bkf)`
at the call site (Phase 3 will store both projections on the field directly,
once `BatchKey` is gone). The three DataFetcher emitters in
`TypeFetcherGenerator` (`buildServiceDataFetcher`, `buildSplitQueryDataFetcher`,
`buildRecordBasedDataFetcher`) now route through `DataLoaderFetcherEmitter.build`
and `RowsMethodCall.batchLoaderLambda`. The five rows-method emitter sites
(`SplitRowsMethodEmitter`'s four entry points + `buildServiceRowsMethod`) now
route through `RowsMethodSkeleton.build` with the matching `RowsMethodBody`
permit (`SqlSplitTable` / `SqlSplitLookupTable` / `SqlRecordTable` /
`SqlRecordLookupTable` / `Service`); the prelude's empty-input gate and
`DSLContext` resolution moved out of `emitParentInputAndFkChain` and onto
the skeleton's SQL framing. Per-fetch dispatch reads
`LoaderRegistration.dispatch()` (LOAD_ONE vs LOAD_MANY) inside the unified
emitter.)*

*(Phase 2 spec correction, shipped alongside the flip: the spec's
`BatchKey → LoaderRegistration` projection table claimed
`AccessorKeyedMany → MAPPED_SET`, but the original
`buildRecordBasedDataFetcher` code emits `newDataLoader` (POSITIONAL_LIST) for
that arm and dispatches via `loader.loadMany`. Container (newDataLoader vs
newMappedDataLoader) and dispatch (load vs loadMany) are independent axes;
`AccessorKeyedMany` is POSITIONAL_LIST + LOAD_MANY, not MAPPED_SET. The
resolver and Phase 1b test now agree with the existing code's behaviour.)*

### Phase 3: clean

Delete the orphaned hierarchy and the orphaned scaffolding. Mechanical, no
design.

- **The ten `BatchKey` permits** in their two sub-hierarchies. Replaced by
  `SourceKey` + `Reader`.
- **`BatchKeyField.batchKey()` accessor.** Replaced by `sourceKey()` +
  `loaderRegistration()`.
- **`LoaderDispatch` enum** on `RecordParentBatchKey`. Replaced by
  `SourceKey.cardinality()`.
- **Four `rowsMethodName()` overrides** on `ChildField.SplitTableField` (line
  209), `ChildField.SplitLookupTableField` (line 247),
  `ChildField.RecordTableField` (line 488),
  `ChildField.RecordLookupTableField` (line 522).
- **Per-emitter handcrafted scaffolding fragments** (signature builder,
  empty-input gate, DSL extraction line) and per-site lambda blocks orphaned
  by Phase 2.
- **`String rowsMethodName = bkf.rowsMethodName();` locals** at sites that no
  longer reference the name directly.

Two design checkpoints surface during Phase 3, not deletions:

- **`@LoadBearingClassifierCheck` for container-axis independence.** Phase 2
  caught the `AccessorKeyedMany → MAPPED_SET` spec error at compile time
  precisely because `LoaderRegistration` separates container and dispatch as
  orthogonal axes. The resolver's `AccessorKeyedMany → POSITIONAL_LIST +
  LOAD_MANY` projection is now load-bearing for the emitter's `valueType =
  Record` choice in `buildRecordBasedDataFetcher`. Phase 3 is the natural
  moment to declare a check pinning the independence (the existing
  `accessor-rowkey-cardinality-matches-field` key covers cardinality, not
  container-axis independence).
- **Flat `SourceKey` vs sub-typing.** Three DataFetcher sites today narrow
  `bkf.batchKey()` to `BatchKey.ParentKeyed` / `BatchKey.RecordParentBatchKey`
  before reading parent-key columns. When the `BatchKey` cast goes away, Phase
  3 needs to confirm the new `SourceKey.columns()` + `SourceKey.path()`
  cleanly subsume what each cast gates today; if not, `SourceKey` may need a
  `(ParentKeyed | RecordParent)` sub-seal rather than staying flat. Current
  starting cut: flat. Test it against the four call-site narrowings.

Verify by grep: no caller of any deleted symbol; no inline emit of patterns
now owned by the new emitters.

End state: `SourceKey` + `Reader` is the source-side dispatch axis;
`LoaderRegistration` carries DataLoader identity; one place to look for each
of naming, declaration, call site, fetcher dance. The SQL-vs-service
distinction lives only in the five `RowsMethodBody` permits. Net type-identity
count goes from 10 to 1 + 5 = 6.

#### Phase 3 in-progress status

Phase 3 is shipping incrementally on `claude/graphitron-rewrite`. Items
already landed (prefix-tagged `R38 Phase 3 (N/N): ...`):

1. Drop the four redundant `rowsMethodName()` overrides; add the
   container-axis-independence `@LoadBearingClassifierCheck`.
2. `SourceKey.Wrap` enum → sealed (`Row` / `Record` / `TableRecord(ClassName)`);
   add `SourceKey.keyElementType()` derivation.
3. Default `sourceKey()` / `loaderRegistration()` on `BatchKeyField` (delegate
   to the existing resolvers; lets consumers migrate one site at a time
   without touching every producer simultaneously). The defaults swap out for
   abstract methods backed by record components once storage migrates.
4. `TypeFetcherGenerator`: four `LoaderRegistrationResolver.resolve(...)`
   calls → `bkf.loaderRegistration()`.
5. `TypeClassGenerator.collectBatchKeyColumns` → `sourceKey().columns()`.
6. `GeneratorUtils.buildKeyExtraction` and `buildKeyExtractionWithNullCheck`
   on `SourceKey`: the six-permit BatchKey switch collapses onto the
   three-permit `Wrap` switch (the container axis already lives on
   `LoaderRegistration`); the `TableRecord` arm picks up the developer's
   typed class through `Wrap.TableRecord`'s payload.
7. `RowsMethodShape.outerRowsReturnType` decoupled from BatchKey: takes the
   two derived primitives (`keyElementType`, `isMapped`) the function
   actually consumes, so each caller projects from whatever access path it
   already has.
8. `GeneratorUtils.buildRecordParentKeyExtraction` + four helpers on
   `SourceKey`: 4-permit `BatchKey.RecordParentBatchKey` switch collapses to
   `Reader × Cardinality` switch. `TypeFetcherGenerator.buildRecordBasedDataFetcher`
   threads `field.sourceKey()` through; service-side Readers are unreachable
   here and rejected eagerly.
9. `MultiTablePolymorphicEmitter`: project `parentKey → SourceKey` once at
   the public entry points (`emitMethods` / `emitConnectionMethods`) via a
   private transitional helper (`parentSourceKey`); all four internal
   builders (batched connection / list fetcher + rows method) take
   `SourceKey`. The projection mirrors `SourceKeyResolver.resolveRecordParent`;
   it goes away once `InterfaceField` / `UnionField` flip `parentKey`
   storage to `SourceKey`.
10. `SplitRowsMethodEmitter` internal helpers (`emitParentInputAndFkChain`,
    `buildListMethod`, `buildSingleMethod`, `buildConnectionMethod`,
    `buildRuntimeStub`) take `SourceKey`. Public entry points read
    `field.sourceKey()`. The two-permit accessor check collapses to a
    single `Reader.AccessorCall` `instanceof`.
11. Storage flip on all six `BatchKeyField` permits (`SplitTableField`,
    `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`,
    `ServiceTableField`, `ServiceRecordField`): replace the `batchKey`
    record component with `sourceKey: SourceKey` + `loaderRegistration:
    LoaderRegistration`. `BatchKeyField` interface drops the default
    `sourceKey()` / `loaderRegistration()` methods (record components shadow
    them as abstract). `RecordTableField.emitsSingleRecordPerKey` /
    `RecordLookupTableField.emitsSingleRecordPerKey` read
    `loaderRegistration().dispatch() == LOAD_MANY` rather than the deleted
    `batchKey().dispatch()`. `SourceKeyResolver` and
    `LoaderRegistrationResolver` refactor to value-based per-shape entry
    points (`resolveSplit`, `resolveRecordParent`, `resolveServiceTable`,
    `resolveServiceRecord`, `LoaderRegistrationResolver.resolve(BatchKey,
    ReturnTypeRef)`). Producers (`FieldBuilder` at ten constructor sites,
    `MappingsConstantNameDedup` at two reconstruction sites) compute
    `SourceKey + LoaderRegistration` at field-construction time via the
    new entry points; the no-Sources-param service path passes null for
    both (the validator's missing-Sources-param check remains the
    surfacing site). Tests on the projection surface (`SourceKeyResolverTest`,
    `LoaderRegistrationResolverTest`) move to the value-based API;
    classifier-side coverage in `GraphitronSchemaBuilderTest` switches to
    `f.sourceKey()` shape assertions; validation tests
    (`RecordTableFieldValidationTest`, `RecordLookupTableFieldValidationTest`,
    `SplitTableFieldValidationTest`, `SplitLookupTableFieldValidationTest`,
    `ServiceFieldValidationTest`) thread `SourceKey + LoaderRegistration`
    through their fixture builders. 1554 tests green.

After step 11 the generator package, the field-classifier producers, and
the validator's classifier-side pin all run off `sourceKey()` /
`loaderRegistration()`. Remaining `.batchKey()` reads live exclusively in
`MethodRef.Param.Sourced` consumers (`ServiceDirectiveResolver` lines 192,
323; `FieldBuilder` lines 187, 2754, 2757, 3104-3111) plus
`MultiTablePolymorphicEmitter.parentSourceKey()` (still projects from
`InterfaceField.parentKey` / `UnionField.parentKey`, both still
`BatchKey.RecordParentBatchKey`-typed). These ride on subsequent flips.

12. `InterfaceField.parentKey` and `UnionField.parentKey` flip from
    `BatchKey.RecordParentBatchKey` to `SourceKey`. The transitional
    `MultiTablePolymorphicEmitter.parentSourceKey()` helper deletes; its
    contents move to `SourceKeyResolver.resolveRecordParentForPolymorphic`
    (the polymorphic case is its own entry point because the field's
    return type is `PolymorphicReturnType` rather than table-bound, so it
    doesn't fit the `resolveRecordParent(BatchKey, TableBoundReturnType)`
    shape). Producers (FieldBuilder at four construction sites) project
    at field-construction time. `MultiTablePolymorphicEmitter.emitMethods`
    and `.emitConnectionMethods` signatures take `SourceKey` directly;
    the `null` parentSourceKey path (root queries) is preserved unchanged.
    `GraphitronSchemaValidator.validateChildMultiTableParentPk` reads
    `parentSourceKey().columns()` rather than `parentKey().preludeKeyColumns()`.
    Tests on the polymorphic surface (RecordParentMultiTablePolymorphicPipelineTest,
    InterfaceFieldValidationTest, UnionFieldValidationTest, plus
    TypeFetcherGeneratorTest's polymorphic helpers) thread `SourceKey`
    through their fixture builders.

After step 12 the generator package and the multi-table polymorphic
classifier surface are fully migrated. Remaining `.batchKey()` reads live
exclusively in `MethodRef.Param.Sourced` consumers
(`ServiceDirectiveResolver` lines 192, 323; `FieldBuilder` lines 187,
2754, 2757, 3104-3111). These ride on the `Sourced` storage flip.

13. `MethodRef.Param.Sourced` and `ParamSource.Sources` storage flip from
    `BatchKey.ParentKeyed` to the `(SourceKey.Wrap, List<ColumnRef>,
    LoaderRegistration.Container)` triple. The two records carry the same
    triple: `wrap` is the per-row shape (Row / Record / typed TableRecord),
    `columns` is the parent-side PK/FK column tuple, and `container` is the
    mapped/positional axis that used to live on `BatchKey` permit identity.
    `Sourced.typeName()` derives the Java type from the triple (using a
    private helper that mirrors the deleted `BatchKey.containerType` shape);
    `Sourced.source()` returns `new ParamSource.Sources(wrap, columns,
    container)`. `SourceKey` gains a static
    `keyElementType(Wrap, List<ColumnRef>)` so consumers holding the triple
    (no full `SourceKey`) derive the DataLoader key element type the same
    way `SourceKey#keyElementType()` does. `ServiceCatalog` projects from
    its internal `BatchKey.ParentKeyed` to the triple at the single
    construction site (via a new private `wrapFor(BatchKey.ParentKeyed)`
    helper). `FieldBuilder.extractBatchKey` becomes `extractSourced` and
    returns the Sourced directly; the four service call sites get three
    new private FB helpers (`buildServiceTableSourceKey`,
    `buildServiceRecordSourceKey`, `buildServiceLoaderRegistration`) that
    take a `Sourced` instead of routing through `SourceKeyResolver.resolveServiceTable/Record`
    and `LoaderRegistrationResolver.resolve(BatchKey, ...)` (which become
    deletable once the record-parent paths inline too).
    `ServiceDirectiveResolver`: line 192's typed-TableRecord parent-table
    consistency check forks on `sourced.wrap() instanceof
    SourceKey.Wrap.TableRecord` and compares `ClassName.get(expected)`
    against `tr.className()`; line 323's strict-return check reads
    `sourced.container() == MAPPED_SET` for the isMapped predicate and
    `SourceKey.keyElementType(sourced.wrap(), sourced.columns())` for the
    key element type. Test fixtures get a `TestFixtures.sourced(name,
    BatchKey.ParentKeyed)` adapter that does the projection mechanically;
    `ServiceCatalogTest`'s six `sourced.batchKey()` assertions become
    explicit `wrap` / `columns` / `container` triple-assertions. 1554
    tests green; the three pre-existing `MultiSchemaQueryTest` failures
    (R83 multi-schema fixture) are unchanged.

After step 13 the model-side `BatchKey` storage on `Sourced` / `Sources`
is gone. Remaining `BatchKey` references in production code live on the
classifier-side producers (`FieldBuilder.deriveSplitQueryBatchKey`,
`deriveBatchKeyForResultType`, `resolveRecordParentBatchKey`,
`deriveBatchKeyFromTypedAccessor`; `ServiceCatalog.classifySourcesType`;
`SourceRowDirectiveResolver`) and the three resolver helpers
(`SourceKeyResolver.resolve{Split,RecordParent,RecordParentForPolymorphic,ServiceTable,ServiceRecord}`;
`LoaderRegistrationResolver.resolve(BatchKey, ReturnTypeRef)`).
`MappingsConstantNameDedup` already migrated to `f.sourceKey()` /
`f.loaderRegistration()` in step 11.

14. `FieldBuilder` split-query and table-backed polymorphic-Row producers
    inline the resolver projections. `deriveSplitQueryBatchKey` (returning
    `BatchKey.RowKeyed`) becomes `deriveSplitQuerySource` returning a small
    `SplitQuerySource(SourceKey, LoaderRegistration)` pair record; the helper
    folds in the projection rules previously split between
    `SourceKeyResolver.resolveSplit` (target / Wrap.Row / ColumnRead / cardinality
    from return wrapper) and `LoaderRegistrationResolver.resolve`
    (POSITIONAL_LIST / LOAD_ONE / valueIsList from wrapper). The two
    `SplitTableField` / `SplitLookupTableField` construction sites read the
    pair components directly. The two table-backed polymorphic-Row sites
    (interface / union child on `@table` parent) drop the inline
    `BatchKey.RowKeyed → SourceKeyResolver.resolveRecordParentForPolymorphic`
    chain in favour of a new private helper
    `buildTableBackedPolymorphicParentSourceKey(pkCols)` that builds the
    `Wrap.Row + ColumnRead + Cardinality.ONE` projection inline. 1554 tests
    green; the three pre-existing `MultiSchemaQueryTest` failures unchanged.

15. `FieldBuilder` record-parent FK and typed-accessor producers inline the
    resolver projections. `deriveBatchKeyForResultType` (returning
    `BatchKey.RowKeyed | null`) becomes `deriveFkRecordParentSource` returning
    a `RecordParentSource(SourceKey, LoaderRegistration)` pair record or null;
    the helper folds the FK arm of `SourceKeyResolver.resolveRecordParent`
    (target / Wrap.Row / ColumnRead) and the constant
    `LoaderRegistrationResolver.resolve` projection
    (POSITIONAL_LIST / LOAD_ONE) onto the FK source-side columns.
    `deriveBatchKeyFromTypedAccessor` is renamed
    `deriveAccessorRecordParentSource`; its tail builds the
    `AccessorCall + Wrap.Record + (LOAD_ONE | LOAD_MANY)` projection directly
    instead of routing the AccessorMatch through `BatchKey.AccessorKeyedSingle /
    Many` and then projecting. `AccessorDerivation.Ok` carries
    `(SourceKey, LoaderRegistration, JoinStep.LiftedHop)` so the orchestrator
    `resolveRecordParentBatchKey` can stitch the `[hop]` joinPath without
    needing to re-derive it from a BatchKey permit. `RecordBatchKeyResolution
    .Resolved` carries `(SourceKey, LoaderRegistration, joinPath)`; the
    `RecordTableField` / `RecordLookupTableField` construction site reads the
    triple directly, and the `SourceKeyResolver.resolveRecordParent` +
    `LoaderRegistrationResolver.resolve` calls on the non-`@sourceRow`
    record-parent arm delete. 1553 tests green; the three pre-existing
    `MultiSchemaQueryTest` failures unchanged.

16. `FieldBuilder` polymorphic record-parent producers inline the resolver
    projection. `PolymorphicRecordParentResolution.Resolved` carries
    `(SourceKey parentSourceKey, TableRef hubTable)` instead of
    `(BatchKey.RecordParentBatchKey parentKey, TableRef hubTable)`;
    `resolvePolymorphicRecordParent`'s `JooqTableRecordType` arm builds
    `SourceKey(target=null, columns=pk, Wrap.Row, Cardinality.ONE, ColumnRead)`
    inline (the parent IS the source — no separate target — and cardinality is
    variant-derived rather than field-cardinality-derived);
    `deriveBatchKeyFromHubAccessor`'s tail builds
    `SourceKey(target=hubTable, columns=hub.PK, path=[hop], Wrap.Record, ONE|MANY,
    AccessorCall(ref))` directly. The
    `SourceKeyResolver.resolveRecordParentForPolymorphic` call at
    `classifyRecordParentPolymorphicChild`'s tail deletes; the consumer reads
    `resolved.parentSourceKey()` directly. 1553 tests green; the three
    pre-existing `MultiSchemaQueryTest` failures unchanged.

17. `SourceRowDirectiveResolver` inlines the resolver projections. `Resolved.Ok`
    carries `(SourceKey, LoaderRegistration, joinPath, tbReturnType)` instead
    of `(BatchKey.LifterKeyed, joinPath, tbReturnType)`. The leaf and path
    derivation arms in `resolve` build `SourceKey(target=leafTable,
    columns=expectedTuple, path=[hop]|steps, Wrap.Row, cardinality from
    wrapper, SourceRowsCall(lifter))` + the `@sourceRow` constant
    `LoaderRegistration(valueIsList, POSITIONAL_LIST, LOAD_ONE)` directly,
    folding in the lifter arms of `SourceKeyResolver.resolveRecordParent` and
    the constant `LoaderRegistrationResolver.resolve`. The consumer at
    `FieldBuilder.classifyChildFieldOnRecordType`'s `@sourceRow` arm reads
    `ok.sourceKey()` / `ok.loaderRegistration()` directly. Three classifier-
    check keys re-shaped from BatchKey-permit identity to SourceKey shape:
    `sourcerow-leafkey-batchkey-is-lifterleafkeyed` →
    `sourcerow-leafkey-sourcerows-singlehop` (path-size invariant on
    SourceRowsCall), `sourcerow-pathkey-batchkey-is-lifterpathkeyed` →
    `sourcerow-pathkey-sourcerows-fkchain` (multi-step FK-chain invariant on
    SourceRowsCall); the consumer-side `@DependsOnClassifierCheck` on
    `GeneratorUtils.buildLifterRowKey` updates to match. 1553 tests green;
    the three pre-existing `MultiSchemaQueryTest` failures unchanged.

18. `ServiceCatalog.classifySourcesType` inlines the source-shape projection.
    The method returns `Optional<SourcesShape>` (a new record carrying
    `(SourceKey.Wrap wrap, LoaderRegistration.Container container)`) instead of
    `Optional<BatchKey.ParentKeyed>`. The `Set` vs `List` container axis and
    the `RowN` / `RecordN` / `TableRecord` element-shape axis project directly
    onto the two values; the columns axis is the caller's `parentPkColumns`
    input and so isn't repeated on the result. The producer at line 254 reads
    `shape.wrap()` / `shape.container()` and constructs `MethodRef.Param.Sourced`
    with the same `parentPkColumns` directly. The transitional helpers
    `wrapFor(BatchKey.ParentKeyed)` and the inline `bk instanceof Mapped*Keyed
    ? MAPPED_SET : POSITIONAL_LIST` switch (added in step 13 to bridge the
    Sourced storage flip) both delete; `ServiceCatalog` no longer imports
    `BatchKey`. 1553 tests green; the three pre-existing `MultiSchemaQueryTest`
    failures unchanged.

19. `TestFixtures` grows direct-triple `sourced(name, wrap, columns, container)`
    overload plus convenience overloads `sourcedRow`, `sourcedRowMapped`,
    `sourcedRecord`, `sourcedRecordMapped`, `sourcedTableRecord`,
    `sourcedTableRecordMapped`, and resolver-replacement helpers
    (`splitSourceKey`, `recordParentRowSourceKey`,
    `polymorphicRowParentSourceKey`, `serviceTableSourceKey`,
    `serviceRecordSourceKey`, `loaderRegistration`) for the test sites that
    previously routed through `SourceKeyResolver` /
    `LoaderRegistrationResolver`. The transitional
    `sourced(name, BatchKey.ParentKeyed)` adapter stays for now alongside
    the new helpers; tests migrate in step 20 ahead of the `BatchKey`
    deletion. Production code that no longer needs `BatchKey` (FieldBuilder,
    ServiceDirectiveResolver) drops the import; only docstring `@link`
    references remain (cleaned up in step 20). 1553 tests green; the three
    pre-existing `MultiSchemaQueryTest` failures unchanged.

20. (a) The six validation tests (`SplitTableFieldValidationTest`,
    `SplitLookupTableFieldValidationTest`, `RecordTableFieldValidationTest`,
    `RecordLookupTableFieldValidationTest`, `UnionFieldValidationTest`,
    `InterfaceFieldValidationTest`) migrate off `SourceKeyResolver` /
    `LoaderRegistrationResolver` / `new BatchKey.X(...)` and onto the new
    `TestFixtures` helpers. `ServiceFieldValidationTest`'s
    `serviceField(batchKey)` / `buildServiceTableField(rt, batchKey)`
    helpers convert to the direct triple `(wrap, keyColumns, mapped)`
    shape. `BatchKeyTest`, `SourceKeyResolverTest`,
    `LoaderRegistrationResolverTest` (the three dedicated test files about
    the to-delete projections) delete; classifier-side projection coverage
    comes from the pipeline tests (`GraphitronSchemaBuilderTest` etc.).
    (b) `TypeFetcherGeneratorTest`'s 27 `BatchKey` references migrate;
    `ServiceCatalogTest`, `GraphitronSchemaBuilderTest`,
    `RecordParentMultiTablePolymorphicPipelineTest` drop the unused
    `BatchKey` import. (c) `TestFixtures.sourced(name,
    BatchKey.ParentKeyed)` transitional adapter deletes (all callers
    migrated). (d) `BatchKey.java`, `SourceKeyResolver.java`, and
    `LoaderRegistrationResolver.java` delete in `main`; the two stale
    `import no.sikt.graphitron.rewrite.model.BatchKey` declarations in
    `TypeFetcherGenerator` and `MultiTablePolymorphicEmitter` drop. The
    remaining `BatchKey` references in main src and tests are all in
    javadoc / comment prose (historical context, no compile linkage); they
    stay for now and can be scrubbed independently. 1523 tests green; the
    three pre-existing `MultiSchemaQueryTest` failures (R83 multi-schema
    fixture) are unchanged baseline.

R38 Phase 3 is **done**: `BatchKey` is gone from the production model and
the test surface that depended on it; `SourceKeyResolver` and
`LoaderRegistrationResolver` are deleted; the field-classifier producers
build `SourceKey` + `LoaderRegistration` directly; the consumer surface
(generators, validator, multi-table polymorphic emitter) reads off
`bkf.sourceKey()` / `bkf.loaderRegistration()` uniformly. The
`@LoadBearingClassifierCheck` keys re-shaped from BatchKey-permit identity
to SourceKey shape; the audit framework verifies the producer-consumer
links. Net type-identity count: down from the original 10 `BatchKey`
permits + 4 `RecordParentBatchKey` permits + 6 `ParentKeyed` permits to
1 `SourceKey` + 5 `Reader` sub-permits + 1 `LoaderRegistration` with
3 enum axes; the rows-method seam routes through `RowsMethodSkeleton` +
`RowsMethodCall.batchLoaderLambda` + `DataLoaderFetcherEmitter.build` as
the spec sets out.

Pattern conventions established during the incremental work:
- Consumer migration always reads off `bkf.sourceKey()` /
  `bkf.loaderRegistration()`; never re-derives via the resolvers at the
  consumer site.
- Switches that previously forked on `BatchKey` permit identity move
  onto `SourceKey.wrap()` (for parent-source shape) or `sourceKey.reader()`
  (for body input contract), with `SourceKey.cardinality()` carrying the
  single-vs-many fork on the accessor arm.
- Helpers that don't need the full `SourceKey` / `LoaderRegistration`
  objects take derived primitives (see `RowsMethodShape.outerRowsReturnType`),
  to stay independent of the storage shape and let callers project from
  whatever access path they have.

## Out of scope

**Service-path empty-input gate.** Today the four SQL rows-methods
short-circuit on `keys.isEmpty()`; the two service rows-methods don't, and
`Service.method(emptySet, dsl)` runs as a wasted call when batch keys are
empty. Adding the gate is a behaviour change visible to schema authors with
side-effecting service methods, not a refactor; it lands as a separate
Backlog item with its own pipeline test pinning the new shape. R38 preserves
current per-variant gate behaviour (the `RowsMethodSkeleton` switch on the
`Service` permit omits the gate) so Phase 2's flip is purely structural.

**`RowsMethodCall.directCall` for tests.** "Tests reach generated rows-methods
through one emitter" has no real consumer in R38. Today's analogous pattern
is reflective: `ScatterSingleByIdxTest` reaches its target via
`getDeclaredMethod`. Designing a `directCall` factory now without an actual
test consumer is the kind of "anticipated future use" the principles tell us
not to build for. A future Backlog item adding rows-method execution-tier
tests adds the factory shaped to that test's actual call shape.

**`ResultRowWalk` Reader permit.** R75's rooted DML payload data-field case
introduces it; lands as a one-Reader-permit addition on the foundation R38
establishes. R75's spec, not R38's.

**FK-on-X target-align optimization** for `ServiceTableRecord`. When `X`
carries an FK to `target`, the chain can shortcut to a single hop. R38 keeps
the conservative full-chain walk; the optimization is a sibling Backlog item.

**Args-side reshape.** `LookupMapping.ColumnMapping.LookupArg` already exists
as the args-side sealed type (three arms: `ScalarLookupArg`, `DecodedRecord`,
`MapInput`), driven by `@lookupKey` and projected by `LookupMappingResolver`.
The args/source split is intact today. R38 doesn't touch the args side.

## Tests

**Pipeline-tier:**

- **`SourceKeyResolverTest`** — projection from each classification context
  (catalog FK, `@record` accessor, `@sourceRows` lifter, service
  `TableRecord`, service untyped `Record`) produces the expected `SourceKey`
  shape. Cross-axis invariants reject malformed shapes with informative
  messages.
- **`LoaderRegistrationResolverTest`** — projection per field shape produces
  the expected container kind.
- **Classifier regression pin** — for each `(BatchKeyField permit, BatchKey
  permit)` row in the projection table above, a fixture asserts the new
  classifier produces the expected `(SourceKey, Reader, LoaderRegistration)`
  triple. Pin survives until Phase 3 deletes `BatchKey`.
- **Rows-method emit pin per `Reader` sub-permit.** Each `Reader` permit
  drives a structural assertion on the emitted method body (signature, gate
  presence, body shape).
- **`fetcherEmitter_unifiedDispatch`** — structural pin that the three
  DataFetcher sites all route through `DataLoaderFetcherEmitter.build(...)`
  post-flip.
- **`rowsMethodEmitter_unifiedSkeleton`** — structural pin that the five
  rows-method emit sites all route through `RowsMethodSkeleton.build(...)`
  post-flip.

**Compilation-tier:** existing `graphitron-sakila-example` fixtures compile
unchanged. With Phase 2's blast radius (model reshape + emitter consolidation
in one PR), classifier-projection fidelity is the failure mode; compilation
against real jOOQ catches a faulty projection as a `*Fetchers.java` compile
error rather than a runtime surprise. Functional equivalence (rather than
byte-identical emission) is the contract — body-string assertions are banned
by the principles, and the three-tier passing build is the structural safety
net for the new emitters' shape. The new emitters are free to drop per-site
comment scaffolding and emit the unified shape directly; what the build
enforces is that the resulting code compiles against real jOOQ and behaves
the same against the execution-tier fixtures.

**Execution-tier:** all existing execution-tier tests pass unchanged. The
reshape is an internal model refactor; user-visible behaviour is fixed.
Combined with compilation-tier, this is the safety net for any
classifier-projection drift the unit and structural-pin tests miss.

**Audit-tier:** new load-bearing classifier checks declared per `Reader`
invariant. The audit framework verifies each `@LoadBearingClassifierCheck` is
consumed by at least one `@DependsOnClassifierCheck` on an emit site.

## Success criteria

**Phase 1:**

- `SourceKey`, `Reader`, `LoaderRegistration` exist in
  `no.sikt.graphitron.rewrite.model`. `SourceRow` documented as the
  runtime-instance vocabulary (no generated type).
- `SourceKeyResolver` and `LoaderRegistrationResolver` exist alongside the
  existing `*Resolver` siblings; their tests pass.
- `RowsMethodSkeleton`, `RowsMethodCall`, `DataLoaderFetcherEmitter` exist
  consuming the new shape; their unit tests pass.
- `BatchKeyField.rowsMethodName()` interface default exists; the four SQL
  leaves' overrides survive (deleted in Phase 3).
- Build green; nothing currently consumes the new types, so emit is unchanged
  by construction.

**Phase 2:**

- Field classifiers produce `SourceKey` + `LoaderRegistration` for every
  DataLoader-backed field.
- All five rows-method emitter sites route through `RowsMethodSkeleton`.
- All three BatchLoader-lambda call sites use `RowsMethodCall.batchLoaderLambda`.
- All three DataFetcher emit sites use `DataLoaderFetcherEmitter.build`.
- `BatchKey` hierarchy still compiles but is no longer dispatched on
  downstream of the classifier.
- Build green; existing compilation-tier and execution-tier tests pass.

**Phase 3:**

- Ten `BatchKey` permits deleted. Net type-identity count: 1 `SourceKey` +
  5 `Reader` sub-permits = 6.
- `LoaderDispatch` enum deleted.
- Four `rowsMethodName()` overrides deleted.
- Per-emitter handcrafted scaffolding orphans deleted.
- Verify by grep: no caller of any deleted symbol.
- Build green; existing compilation-tier and execution-tier tests pass.
- R75's `ResultRowWalk` Reader permit lands as a one-permit addition
  (validated by R75's spec, not R38's).
