---
id: R65
title: "Tighten accessor-derived BatchKey model and emitter coordination"
status: In Review
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
---

# Tighten accessor-derived BatchKey model and emitter coordination

Six independent architectural cleanups surfaced during the R60 reviewer pass. All six landed in this branch.

## What shipped

1. **`AccessorRowKeyedMany.Container` slot dropped.** The enum and the `container` record component are gone. The for-loop in `GeneratorUtils.buildAccessorRowKeyMany` already iterates any `Iterable`, so no fork was load-bearing. `FieldBuilder.AccessorMatch.Many` no longer carries the container; the unit-tier `BatchKeyTest` cases and the pipeline-tier `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_*_ACCESSOR` cases dropped the enum-pinning assertions. The `Set<X>` vs `List<X>` declaration on the parent class is still exercised by the two pipeline-tier fixtures (`ListPayload`, `SetPayload`); the variant just doesn't preserve which side it came from.

2. **`BatchKeyField#emitsSingleRecordPerKey()` capability.** New default method on `BatchKeyField` returning `false`; overridden on `SplitTableField` (`!returnType().wrapper().isList()`) and `RecordTableField` (`batchKey() instanceof AccessorRowKeyedMany`). The two consumer sites — `TypeFetcherGenerator`'s `scatterSingleByIdx` helper-emission gate and `SplitRowsMethodEmitter.buildForRecordTable`'s routing decision into `buildSingleMethod` — both read the capability. Adding a future variant whose rows-method emits 1 record per key requires only an override, not a new disjunct at each site.

3. **`RecordParentBatchKey#preludeKeyColumns()` capability + prelude parameter tightening.** New abstract method on `RecordParentBatchKey`; `RowKeyed` delegates to `parentKeyColumns()`, the three other arms delegate to `targetKeyColumns()` (via `hop.targetColumns()`). `SplitRowsMethodEmitter.emitParentInputAndFkChain`'s parameter type tightened from `BatchKey` to `RecordParentBatchKey`; the `pkCols` switch with the `default -> throw` arm collapsed to `batchKey.preludeKeyColumns()`. Helper-method chain (`buildListMethod` / `buildSingleMethod` / `buildConnectionMethod`) tightened to `RecordParentBatchKey`. To carry the chain end-to-end, `SplitTableField.batchKey()` and `SplitLookupTableField.batchKey()` tightened from `BatchKey.ParentKeyed` to `BatchKey.RowKeyed` (which already implements both `ParentKeyed` and `RecordParentBatchKey`); `deriveSplitQueryBatchKey` return type matches. The two `@DependsOnClassifierCheck` annotations on the prelude collapsed into one (the JOIN-on side claim about `LiftedHop`); the BatchKey side claim is now load-bearing in the type system. `TypeClassGenerator.collectBatchKeyColumns`'s redundant `instanceof BatchKey.RowKeyed` checks became direct accessor reads.

4. **Container/element classifier walk lifted into `ServiceCatalog`.** New `ServiceCatalog.ContainerKind { SINGLE, LIST, SET }` enum + `ContainerSplit` record + `peelContainer(Type, Set<ContainerKind>)` helper. `classifySourcesType` (SOURCES path) accepts `LIST | SET`; `FieldBuilder.classifyAccessorReturn` (accessor path) accepts all three. The element-class check (`TableRecord` subtype, or `RowN`/`RecordN` parameterised raw on the SOURCES path) stays per-caller. Both call sites remain inside parse-boundary classes; the shape walk has one home.

5. **Typed `LoaderDispatch` projection.** New `BatchKey.LoaderDispatch { LOAD_ONE, LOAD_MANY }` enum and `RecordParentBatchKey#dispatch()` accessor; the three single-key arms return `LOAD_ONE`, `AccessorRowKeyedMany` returns `LOAD_MANY`. `TypeFetcherGenerator.buildRecordBasedDataFetcher` reads `batchKey.dispatch()` once and forks the loader value type and dispatch call shape on the projection (replacing the inline `instanceof AccessorRowKeyedMany`). The matching key local's name (`key` for `LOAD_ONE` arms, `keys` for `LOAD_MANY`) is still emitted by the per-arm helpers in `GeneratorUtils.buildRecordParentKeyExtraction`; the dispatch projection is now the single source of truth that the dispatcher reads. The `@DependsOnClassifierCheck` annotation on `buildRecordBasedDataFetcher` was rewritten to reference the `dispatch == LOAD_MANY` rule instead of `usesLoadMany` (the underlying classifier check `accessor-rowkey-cardinality-matches-field` is unchanged).

6. **Unused `ListAccessorOnSingleField` fixture deleted.** The record had javadoc noting it existed "for symmetry"; no test referenced it. The unused `FilmActorRecord` import dropped with it.

## Implementation deviations from the spec

- **Item 5 carrier shape.** Spec proposed a sealed `LoaderDispatch { LoadOne | LoadMany }`. Implemented as an enum since the two arms carry no per-arm data and consumers fork on identity, not on captured fields. Per `rewrite-design-principles.adoc` "Sealed hierarchies over enums": "When variants carry different data, use a sealed interface; an enum forces every variant to have the same shape." Both arms of the dispatch share the empty shape.

- **Item 3 `SplitTableField` / `SplitLookupTableField` typing.** The spec did not explicitly call for tightening these field components' `batchKey()` types, but doing so was necessary to type the prelude parameter as `RecordParentBatchKey` end-to-end (the alternative was a runtime cast at the call site). The change is a type-only narrowing: `deriveSplitQueryBatchKey` already only ever returned `RowKeyed`, so no behavioural change.

- **Item 5 / item 2 capability collapse.** Spec said "Both projections may belong on the same model, or one may collapse into the other once both are typed." Kept separate: `emitsSingleRecordPerKey` is a `BatchKeyField`-level question (rows-method shape; depends on field cardinality); `dispatch` is a `RecordParentBatchKey`-level question (loader call shape). They coincide for `RecordTableField` with `AccessorRowKeyedMany` (both `true`/`LOAD_MANY`) but diverge for single-cardinality `SplitTableField` (`emitsSingleRecordPerKey == true`, no dispatch projection — `SplitTableField` carries `RowKeyed` whose `dispatch()` is `LOAD_ONE` regardless of field cardinality).

## Verification

- `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` SUCCESS on Java 25.
- 1262 graphitron unit + pipeline tests pass; 238 graphitron-test compilation + execution tier tests pass; `LoadBearingGuaranteeAuditTest` passes (no orphan annotations).

## Out of scope (unchanged)

- **Renaming `AccessorRowKeyedSingle` / `AccessorRowKeyedMany`.** The names accurately reflect cardinality at the variant level; nothing the items above required a rename.
- **The `Single` permit's emitter wiring.** Already complete in R60; the execution-tier coverage gap is a separate concern tracked under the validator's Invariant #10 lift.
- **`RecordBatchKeyResolution` and the `AccessorDerivation` / `AccessorMatch` two-stage builder hierarchy.** Clean applications of the "Builder-step results are sealed" rule; no change needed.
