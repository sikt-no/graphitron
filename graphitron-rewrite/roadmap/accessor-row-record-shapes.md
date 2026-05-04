---
id: R74
title: "Row/Record return shapes for typed accessor batch keys"
status: Spec
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Row/Record return shapes for typed accessor batch keys

`FieldBuilder.deriveBatchKeyFromTypedAccessor` (`FieldBuilder.java:2817-2955`) currently auto-lifts a batch key from a typed zero-arg accessor on a `@record` parent's backing class only when the accessor returns `X`, `List<X>`, or `Set<X>` for some concrete `X extends org.jooq.TableRecord`. The classifier then projects each element into a `RecordN<...>` key via `__elt.into(Tables.X.PK1, ...)` in `GeneratorUtils.buildAccessorKeySingle`/`buildAccessorKeyMany` (`GeneratorUtils.java:257-316`). Schema authors whose parent backing classes already expose a `Row1<Integer>` / `Record1<Integer>` (or list/set thereof) accessor cannot reach this auto-lift: their only option is `@batchKeyLifter`, which forces a static method even when the parent class already has the right key in hand.

Structurally the contract is identical to the lifter contract enforced for `BatchKey.LifterRowKeyed`: arity equals the target table's PK arity, and per-position type-arg erasure equals each PK column's `columnClass()`. The hop is already constructed from `expectedTable.primaryKeyColumns()` (line 2919); only the classifier's element-type filter and the emitter's projection step need to extend.

## Decision: distinct sealed permits per shape, not an enum discriminator

The first-pass design (a `KeyShape` enum on the existing two permits) was rejected in conversation as a violation of three rewrite principles: "sealed hierarchies over enums for typed information" (the variants carry different data: TableRecord arms need an element class for the `__elt.into(...)` cast; Row/Record arms don't), "generation-thinking" (the enum forces every emitter that forks on shape to switch on the discriminator), and "narrow component types" (`AccessorRef.elementClass` would become semantically dead on the new arms).

This is the same call R61 (`@service` source-shape), R70 (`@service` typed-`TableRecord` source), and R71 (`@batchKeyLifter` return) made: variant identity tracks shape, distinct sealed permits per shape, no enum discriminator and no per-instance branching. R74 is the fourth application of the rule on the auto-lift accessor side.

### Rename existing arms for naming uniformity

R61 renamed `AccessorRowKeyed{Single,Many}` to `AccessorKeyed{Single,Many}` on the rationale that there was no shape distinction to encode (one shape: TableRecord-projected). R74 invalidates that premise: three accessor element shapes now coexist, so the variant name should encode shape uniformly across all six accessor arms. Rename the existing pair to `AccessorTableRecord{Single,Many}` and add `AccessorRow{Single,Many}` / `AccessorRecord{Single,Many}`. Result: a clean 3-shapes × 2-cardinalities grid where every accessor variant's identity tells the reader its element shape.

The rename is a model-internal refactor (no public API; `AccessorKeyed*` does not appear in generated source). Mechanical: rename the two records, update every `case AccessorKeyedSingle` / `case AccessorKeyedMany` site (the seal makes `javac` enumerate them), update class-level Javadoc on `BatchKey`, update `AccessorRef`'s Javadoc references.

### Permit grid

```
sealed interface RecordParentBatchKey extends BatchKey
    permits RowKeyed, LifterRowKeyed,
            AccessorTableRecordSingle,  // renamed from AccessorKeyedSingle: TableRecord X,    projects via __elt.into(...)
            AccessorTableRecordMany,    // renamed from AccessorKeyedMany:   List/Set<X>,      projects via __elt.into(...)
            AccessorRowSingle,          // new: RowN<...>,                    no projection
            AccessorRowMany,            // new: List/Set<RowN<...>>,          no projection
            AccessorRecordSingle,       // new: RecordN<...>,                 no projection
            AccessorRecordMany;         // new: List/Set<RecordN<...>>,       no projection
```

The four new permits carry `JoinStep.LiftedHop hop` plus a new `AccessorTupleRef(ClassName parentBackingClass, String methodName)`. The two `AccessorTableRecord*` permits keep the existing `AccessorRef` (which carries `elementClass` for the `__elt.into(...)` cast). Keeping two ref types, each narrow to its own shape family, preserves the existing principle on narrow component types.

`keyElementType()`'s switch grows two arms: `AccessorRow* -> rowNType(hop.targetColumns())`, `AccessorRecord* -> recordNType(hop.targetColumns())`. The `AccessorTableRecord*` arms continue to produce `recordNType(hop.targetColumns())` exactly as the existing `AccessorKeyed*` arms do post-R61. `dispatch()` and `preludeKeyColumns()` follow the same Single/Many pattern the existing arms already establish.

## Classifier

`classifyAccessorReturn` extends to recognize three element-type families:

1. `Class<?>` extending `org.jooq.TableRecord`: existing path, unchanged. Element table resolved via `svc.resolveTableByRecordClass(...)`.
2. `ParameterizedType` whose raw is `org.jooq.Row1..Row22` (or `RowN`): new. Type-args extracted from the parameterization.
3. `ParameterizedType` whose raw is `org.jooq.Record1..Record22` (or `RecordN`): new. Type-args extracted similarly.

For families 2 and 3 the element table cannot come from the element class (there is no record class). The field's `@table` return is the only anchor; the structural arity-and-types check then runs against `expectedTable.primaryKeyColumns()`. Mismatches surface as a new `AccessorDerivation.ShapeMismatch` arm with a message naming both sides (accessor's tuple shape vs. PK column shape).

The existing ambiguity rule (multiple matching accessors → `AccessorDerivation.Ambiguous`) generalizes naturally; the rejection message is updated to mention all three accepted shapes.

Classifier rejections to preserve:

- Raw `Row` / `Record` (no type-args): reject, as today's wildcard handling does.
- Wildcard type-args (`Row1<? extends Integer>`): reject; only invariant element types are supported.
- Same-type-arg PK swap that an arity-and-erasure check cannot detect (e.g. `Row2<Integer, Integer>` against PK `(actor_id Integer, film_id Integer)` declared in the opposite order): undetectable structurally; flag as a known limitation in a doc note next to the lifter contract's identical caveat.

## Emitter

### Key extraction

`GeneratorUtils.buildRecordParentKeyExtraction`'s sealed switch grows four new arms. Each is leaner than the existing two: no `__elt.into(...)`, just direct assignment:

```
RowN<...>    key = ((Backing) env.getSource()).accessor();          // AccessorRowSingle
RecordN<...> key = ((Backing) env.getSource()).accessor();          // AccessorRecordSingle
```

The list arms iterate the accessor's `Iterable` and add each element to `keys` directly, with no per-element projection. Each new emitter arm carries its own `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair so `LoadBearingGuaranteeAuditTest` covers the new shape contracts.

### Rows-method outer parent VALUES emission

`SplitRowsMethodEmitter` already forks two ways for the parent VALUES table emission (post-R61): RowN-keyed arms (`RowKeyed`, `LifterRowKeyed`) emit `k.field<N>()`; RecordN-keyed accessor arms (`AccessorKeyedSingle`, `AccessorKeyedMany`) emit `DSL.val(k.value<N>())`. The current `instanceof BatchKey.AccessorKeyed{Single,Many}` predicate on `SplitRowsMethodEmitter:220-221` extends naturally:

| Variant | Key DataLoader type | Parent VALUES cell |
|---|---|---|
| `AccessorTableRecordSingle/Many` (renamed) | `RecordN<...>` | `DSL.val(k.value<N>())` |
| `AccessorRowSingle/Many` (new) | `RowN<...>` | `k.field<N>()` |
| `AccessorRecordSingle/Many` (new) | `RecordN<...>` | `DSL.val(k.value<N>())` |

The predicate sweep replaces the `instanceof` pair with a sealed-switch arm that pivots on `keyElementType()`'s shape (or, equivalently, on whether the variant's `BatchKey.javaTypeName()` is `Row` or `Record`); avoid hand-listing the six accessor permits if a single shape predicate captures the same fork.

### DataLoader registration

The framework registers per-K type at runtime (`Loaders.computeIfAbsent(...)`). The new arms keep that registration shape: `AccessorRow*` → `K = RowN<...>`; `AccessorRecord*` → `K = RecordN<...>`; `AccessorTableRecord*` → `K = RecordN<...>` (unchanged). No new Loader machinery; the existing per-variant `keyElementType()` already drives the K-type generic into the loader signature.

## Validator dispatch coverage

`TypeFetcherGenerator`'s dispatch partition (`IMPLEMENTED_LEAVES` etc.) extends to include the four new arms in `IMPLEMENTED_LEAVES`. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces exhaustive partitioning, so any missed arm fails the build.

## Switch-exhaustiveness sweep

The seal on `BatchKey` (and its `RecordParentBatchKey` sub-seal) makes every `switch(batchKey)` in the codebase a `javac`-checked exhaustive site. Renaming `AccessorKeyed{Single,Many}` and adding four new permits forces every such switch to be walked. Mechanical, but central to the change: a missed site is a build break, not a runtime surprise.

Concretely, the sweep covers:

- `BatchKey.keyElementType()`, `BatchKey.javaTypeName()`, `BatchKey.dispatch()`, `BatchKey.preludeKeyColumns()`: the four switches on the `BatchKey` interface itself.
- `GeneratorUtils.buildRecordParentKeyExtraction`: emits the per-arm key extraction. Each new arm gets its own arm body; the rename touches the existing two.
- `SplitRowsMethodEmitter`: the `instanceof BatchKey.AccessorKeyedSingle || ...AccessorKeyedMany` predicate at line 220-221 (post-R61) becomes either a six-permit predicate or a shape-pivoted switch; either way every accessor permit must be reachable.
- `FieldBuilder.deriveBatchKeyFromTypedAccessor`: produces six possible `Ok(...)` arms now, not two.
- Any `case` site that pattern-matches on `AccessorKeyedSingle` / `AccessorKeyedMany` by name needs the rename. The seal makes these `javac`-checked once the records are renamed.

## Implementation

Ordered phases so each lands as a coherent slice. Phases 1 and 2 must land in the same trunk push: the sealed switches inside `BatchKey` (`keyElementType()`, `javaTypeName()`, `dispatch()`, `preludeKeyColumns()`) would otherwise reject incomplete coverage.

1. **Variants + ref carrier + classifier (model-first slice).**
   - Rename `AccessorKeyed{Single,Many}` → `AccessorTableRecord{Single,Many}` in `BatchKey.java`. Update `BatchKey`'s class-level Javadoc and the renamed permits' own docstrings. Update `AccessorRef.java` Javadoc references.
   - Add `AccessorTupleRef(ClassName parentBackingClass, String methodName)` next to `AccessorRef`. Keep it as a separate record from `LifterRef`: the carrier role differs semantically (instance accessor vs. static lifter) and component-narrowness keeps the two type-disjoint.
   - Add the four new permits with `keyElementType()` / `javaTypeName()` / `dispatch()` / `preludeKeyColumns()` arms. Walk every `switch(batchKey)` site for exhaustiveness; the rename and the four new arms together touch every site in one pass.
   - Extend `FieldBuilder.classifyAccessorReturn` to recognise `Row1..Row22` / `Record1..Record22` parameterized types on top of the existing `TableRecord` element-class path. `ServiceCatalog.peelContainer` already strips `List<...>` / `Set<...>` containers; only the element-type predicate widens.
   - Extend `FieldBuilder.deriveBatchKeyFromTypedAccessor` to construct `AccessorRow{Single,Many}` and `AccessorRecord{Single,Many}` for the new families, and to keep the existing `AccessorTableRecord{Single,Many}` (renamed) construction path unchanged.
   - Add `AccessorDerivation.ShapeMismatch` arm for arity/per-position type mismatches against `expectedTable.primaryKeyColumns()`. Existing `AccessorDerivation.Ambiguous` and `CardinalityMismatch` arms generalize unchanged.

2. **Emitter sweep (must ship with phase 1).**
   - `GeneratorUtils.buildRecordParentKeyExtraction` grows four arms (direct assign for Single, `Iterable` walk for Many).
   - `SplitRowsMethodEmitter` predicate at `:220-221` either expands to enumerate all six accessor permits or pivots on `BatchKey.javaTypeName()` shape. Either way, the parent VALUES emission keeps emitting `k.field<N>()` for `Row`-shaped keys and `DSL.val(k.value<N>())` for `Record`-shaped keys.
   - `TypeFetcherGenerator`'s dispatch partition (`IMPLEMENTED_LEAVES`) adds the four new arms. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces this.
   - Each new emitter arm carries `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` paired with the matching classifier site. `LoadBearingGuaranteeAuditTest` enforces presence.

3. **Fixture parity sweep.**
   - `TypeFetcherGenerator`-side fixtures and pipeline assertions add coverage for each new shape × cardinality combination on both single-PK (`film`) and composite-PK (`film_actor`) tables.
   - `graphitron-sakila-example` adds backing-class fixtures whose accessor returns each of the six shape × cardinality combinations, so the compilation tier exercises all six permits in one `mvn compile` run.
   - `BatchKey` class-level Javadoc and the `@batchKeyLifter` section of `rewrite-design-principles.adoc` get the same-type PK-swap limitation note.

## Tests

- **L1 (`BatchKeyTest`).** Parameterised test pins `keyElementType()`, `javaTypeName()`, `dispatch()` per variant. Six accessor permits: `AccessorTableRecord{Single,Many}` and `AccessorRecord{Single,Many}` → `RecordN<...>`; `AccessorRow{Single,Many}` → `RowN<...>`. Captures the full eight-permit shape map for `RecordParentBatchKey` (the existing `RowKeyed`, `LifterRowKeyed` rows stay).
- **L3 (validator).** New parameterised case in `RecordParentValidationTest` (or the closest existing accessor-derivation test) asserts that each of the six accessor element shapes (`X`, `List<X>`, `Set<X>`, `Row1<Integer>`, `List<Row1<Integer>>`, `Set<Record1<Integer>>`, etc.) classifies into the matching variant on the same field. Structural rejections covered: arity mismatch, per-position type mismatch, raw `Row` / `Record` (no type-args), wildcard type-args.
- **L4 (pipeline).** `RecordParentFetcherPipelineTest` (or the closest existing pipeline test for accessor-derived keys): assertions track variant shape, with `RowN` for `AccessorRow*` and `RecordN` for `AccessorTableRecord*` and `AccessorRecord*`. Includes the parent VALUES emission (`k.field<N>()` vs `DSL.val(k.value<N>())`) for at least one of each shape.
- **L5 (compile spec).** `graphitron-sakila-example` carries fixtures hitting all six shape × cardinality permutations, on both single-PK and composite-PK tables; `mvn compile -pl :graphitron-sakila-example -Plocal-db` passes for all of them.
- **L6 (execution).** Sakila execution test that exercises one `AccessorRow*` and one `AccessorRecord*` end-to-end, confirming the loader fans out keys correctly per the registered K-type and the parent VALUES table renders the right row shape.

## Acceptance criteria

- `BatchKey` adds `AccessorRowSingle`, `AccessorRowMany`, `AccessorRecordSingle`, `AccessorRecordMany` as new permits of `RecordParentBatchKey`. Each carries `JoinStep.LiftedHop` plus an `AccessorTupleRef(parentBackingClass, methodName)`.
- `BatchKey` renames `AccessorKeyedSingle` / `AccessorKeyedMany` to `AccessorTableRecordSingle` / `AccessorTableRecordMany`. The renamed pair keeps the existing `AccessorRef` (with `elementClass`).
- All `switch(batchKey)` sites are walked exhaustively: at minimum the four switches on `BatchKey` itself plus `GeneratorUtils.buildRecordParentKeyExtraction` and `SplitRowsMethodEmitter`'s parent VALUES emission. The seal makes this `javac`-checked.
- `FieldBuilder.deriveBatchKeyFromTypedAccessor` produces the new variants when the parent backing class's accessor returns `Row1..Row22<...>`, `Record1..Record22<...>`, or list/set wrappers thereof, with type-args matching the field's `@table` PK by arity and per-position erasure. Mismatched shapes produce a typed `AccessorDerivation.ShapeMismatch` rejection.
- `GeneratorUtils.buildRecordParentKeyExtraction` has emitter arms for all four new permits; arms skip the `__elt.into(...)` projection and assign / iterate directly.
- `SplitRowsMethodEmitter`'s parent VALUES emission keeps the post-R61 contract: `RowN`-keyed arms emit `k.field<N>()`; `RecordN`-keyed arms emit `DSL.val(k.value<N>())`. The new arms route by shape, not by hand-listed variant identity.
- `LoadBearingGuaranteeAuditTest` is satisfied: producer / consumer annotation pairs cover the new arms.
- Pipeline tests cover both single-PK (`film`) and composite-PK (`film_actor`) tables for all four new shape × cardinality combinations. Compilation tier (`mvn compile -pl :graphitron-sakila-example -Plocal-db`) passes with fixtures hitting each new arm.
- Classifier unit tests cover the structural rejections: arity mismatch, per-position type mismatch, raw `Row` / `Record`, wildcard type-args.
- Doc note in `BatchKey`'s class-level Javadoc (and a sibling note in the `@batchKeyLifter` section of `rewrite-design-principles.adoc`) flags the same-type PK-swap limitation that any structural-tuple contract carries.

## Roadmap entries (siblings / dependencies)

- **Sibling of** [R61 / `emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md), which added `RecordN<...>` source-shape support alongside `RowN<...>` on the `@service` classifier path. R61 established the variant-identity-tracks-shape encoding rule that R74 extends; R74 also reverses R61's `AccessorRowKeyed* → AccessorKeyed*` rename now that the shape distinction R61 said wasn't needed has materialised.
- **Mirrors design pattern from** [R70 / `service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md), which added `TableRecordKeyed` / `MappedTableRecordKeyed` for the typed-`TableRecord` source shape rather than folding onto `MappedRowKeyed`. Same principle, this time on the developer-facing source side.
- **Sibling of** [R71 / `recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md), which extends `@batchKeyLifter` to accept `RecordN` returns by splitting `LifterRowKeyed` into `LifterRowKeyed` / `LifterRecordKeyed`. R74 covers the auto-lift accessor path; R71 covers the explicit-lifter path. Independent, can ship in either order.
- **Encoding rule applied here for the fourth time.** R61 (source side, `@service`), R70 (source side, typed-`TableRecord`), R71 (consumer-supplied side, `@batchKeyLifter`), and R74 (auto-lift side, typed accessor) all encode source-or-return shape as variant identity rather than enum discriminator or per-instance branching. R74 is the auto-lift slice of that pattern.
