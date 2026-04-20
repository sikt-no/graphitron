# Record-fields Phase 2 — `RecordLookupTableField` emission

> **Status:** Draft
>
> Lift `ChildField.RecordLookupTableField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` by combining record-fields Phase 1's key-extraction pattern (`GeneratorUtils.buildRecordKeyExtraction`) with argres Phase 2b's `SplitLookupTableField` rows-method shape. Covers G6 row 4 ("Result-mapped `LookupTableField`, `@splitQuery` + `@lookupKey`").

## Current state

- `ChildField.RecordLookupTableField` (`model/ChildField.java:303`) carries `LookupMapping` but **no `BatchKey` field** — the sealed hierarchy today is inconsistent with its DataLoader-backed siblings `SplitLookupTableField` and `RecordTableField`, both of which gained a `BatchKey` in their respective emission plans.
- `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` entry at `TypeFetcherGenerator.java:212` routes it to `stub(f)`.
- `FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:1482`) constructs `RecordLookupTableField` without a batch key. `deriveBatchKeyForResultType` already exists (Phase 1) and takes `(joinPath, parentResultType)` — reusable as-is.
- `GraphitronSchemaValidator.validateRecordLookupTableField` (`GraphitronSchemaValidator.java:460`) currently only runs the lookup-mapping-presence check; no intra-variant stubs.

## Not a blocker — `BatchKey.ObjectBased`

The original plan-record-fields.md Phase 2 section named "`BatchKey.ObjectBased` generator path decision" as a prerequisite. That was written before Phase 1 resolved the question: the Phase 1 implementation uses `BatchKey.RowKeyed` uniformly for all typed `ResultType` variants, with per-`ResultType` accessor dispatch in `GeneratorUtils.buildRecordKeyExtraction`. `ObjectBased` is only constructed by `ServiceCatalog.classifySourcesType` (reflection classification for `@service` `List<Sources>` parameters) and is irrelevant to `RecordLookupTableField`. The roadmap's `ObjectBased` Backlog item tracks emission for `ServiceTableField`'s `ObjectBased`-keyed case, which is orthogonal.

## Plan

Two sequential commits.

### C1 — Model + classifier

**Model.** Add `BatchKey batchKey` to `ChildField.RecordLookupTableField` and declare `implements TableTargetField, BatchKeyField, LookupField`. `rowsMethodName()` matches Phase 1's `RecordTableField`: `"rows" + capitalize(name())`.

**Classifier.** `FieldBuilder.classifyChildFieldOnResultType:1482` — before constructing `RecordLookupTableField`, call `deriveBatchKeyForResultType(objectPath.elements(), parentResultType)`. On `null` (empty/non-FK join path, or untyped `PojoResultType`), fall back to `UnclassifiedField` with the same reason string Phase 1 uses for `RecordTableField`. No changes to `deriveBatchKeyForResultType` itself.

**Validator.** Add a `SplitRowsMethodEmitter.unsupportedReason(RecordLookupTableField)` arm emitting the same three stubs Phase 1 established for `RecordTableField` (single cardinality, `ConditionJoin`-only path, empty `joinPath`). Wire it into `GraphitronSchemaValidator.validateVariantIsImplemented`. `validateRecordLookupTableField` keeps its existing lookup-mapping check.

Pipeline test: SDL with a `@record` parent and `@splitQuery` + `@lookupKey` child → `RecordLookupTableField` classifies with a populated `BatchKey.RowKeyed`; the field ends up with a DataLoader wiring entry and a `rowsXxx` method declaration.

### C2 — Emission

**Fetcher wiring.** Identical shape to Phase 1's `RecordTableField` DataLoader-registering fetcher — `buildDataLoaderName()` + explicitly-typed batch lambda; key extraction via `GeneratorUtils.buildRecordKeyExtraction(batchKey, resultType)`. The only divergence from Phase 1: the DataLoader key is the composite (parent-identifying FK, lookup-input key) — same `Row<N+M>` convention as argres Phase 2b uses for `SplitLookupTableField`.

**Rows method.** Delegate to `SplitRowsMethodEmitter.buildForRecordLookupTable` (new arm). The body reuses:
- `LookupValuesJoinEmitter.buildChildInputRowsMethod` for the lookup-input VALUES derived table,
- `JoinPathEmitter` for the FK join from lookup target back to parent table (same as `SplitLookupTableField`),
- the `idx` scatter convention and empty-input short-circuit.

Move `RecordLookupTableField` from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`.

Pipeline test: `RecordLookupTableField` classifier + generator produce the correct fetcher wiring and a `rowsXxx` method with the expected batched signature.

### C3 — Execution tests

Add a test-spec fixture: `@record` parent (e.g. `FilmDetails` backed by `FilmRecord`) with a `@splitQuery` + `@lookupKey` child navigating `film → film_actor → actor(actor_id in @lookupKey)`. Execute:

- Single parent, M lookup keys → N × M result rows, one SQL round-trip.
- Multiple parents, M lookup keys each → (N parents × M keys) rows, still one SQL round-trip.
- Empty `@lookupKey` list → empty result, no SQL emitted (empty-input short-circuit).

Compile gate: `mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **`ObjectBased`-keyed emission for `ServiceTableField`** — tracked as a separate Backlog item; no dependency here.
- **Single cardinality** — rejected at classify time (already enforced by argres Phase 2a for `LookupTableField`; Phase 2 inherits the same rule).
- **`@condition` on `@lookupKey` fields** — blocked by the lookup invariant (see G6 reference table); no change in this plan.
- **Non-FK join paths** — `ConditionJoin` only paths remain unsupported; routed through `unsupportedReason` like Phase 1.
