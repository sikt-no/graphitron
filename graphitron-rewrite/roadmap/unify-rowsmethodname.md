---
id: R38
title: "Unify `rowsMethodName()` and the rows-method seam"
status: Spec
bucket: cleanup
priority: 1
theme: model-cleanup
depends-on: []
---

# Unify `rowsMethodName()` and the rows-method seam

The DataLoader-backed leaf taxonomy has six members, all implementing `BatchKeyField` and emitting a "rows-method" the registered DataLoader's batch lambda calls into. Four (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`) override `rowsMethodName()` with `"rows" + capitalize(name())`; two (`ServiceTableField`, `ServiceRecordField`) override with `"load" + capitalize(name())`. The prefix is a body-shape marker: `rows` for "this method emits SQL to shape rows", `load` for "this method delegates to a developer-supplied service". Underneath, every site that emits or invokes a rows-method handcrafts the same scaffolding: signature `(keys, env)`, optional empty-input gate, optional `DSLContext dsl = ...` line, and the BatchLoader lambda the DataLoader registers.

The end goal is hardening: when conceptually doing the same thing, share the emitter. After R38 every BatchKeyField rows-method declaration goes through one emitter, every BatchLoader lambda through one emitter, every DataLoader-registering DataFetcher through one emitter. The SQL-vs-service split survives only as a body-emitter strategy plugged into the shared skeleton, and tests gain a programmatic way to invoke a generated rows-method directly without reconstructing the call shape.

## Patterns

Five distinct concepts live in the rows-method seam. R38 hardens four; one is already factored on the SQL side and stays where it is.

1. **Naming.** The `rowsMethodName()` string. Today: six overrides on `ChildField.java` at lines 209, 247, 488, 522 (the `rows<X>` group) and 419, 447 (the `load<X>` group). The default-vs-override split is encodable on `BatchKeyField` itself.
2. **Declaration scaffolding.** The MethodSpec skeleton: `public static`, parameters, return type, empty-input gate, optional DSL-context line. Today: the SQL siblings inside `SplitRowsMethodEmitter` share a prelude past the gate but each handcrafts the signature; `TypeFetcherGenerator.buildServiceRowsMethod` (line 2823) handcrafts the whole skeleton inline and skips the empty-input gate.
3. **Lift (keys → body-shaped input).** Transform `keys` into the form the body consumes. SQL paths build a typed `parentRows[]` array and wrap as a jOOQ VALUES table named `parentInput`; service paths consume the typed key container directly. Already factored on the SQL side: `SplitRowsMethodEmitter.emitParentInputAndFkChain` (line 148) produces a `PreludeBindings` record (line 120). Stays SQL-internal under R38; the asymmetry is the SQL-vs-service distinction itself, not a missing extraction.
4. **Call site (BatchLoader lambda).** The expression that invokes the rows-method given `(keys, env)`. Today: handcrafted three times in `TypeFetcherGenerator` at lines 2762, 2922, 3022, each inside a `(keys, batchEnv) -> { dfe = ...; return CompletableFuture.completedFuture(rowsXxx(keys, dfe)); }` block.
5. **DataFetcher dance.** The DataFetcher MethodSpec that registers the loader, extracts the per-parent batch key from `env`, calls `loader.load(key, env)` (or `loader.loadMany`), and async-wraps the result. Today: handcrafted in three emitters: `buildServiceDataFetcher` (line 2733), `buildSplitQueryDataFetcher` (line 2888), `buildRecordBasedDataFetcher` (line 2995).

## Phases

Three phases, each one job. Phase 1 is purely additive (new code, no consumers); Phase 2 is the single flip across all consumers; Phase 3 deletes the dead code Phase 2 orphans. Risk concentrates in Phase 2; Phases 1 and 3 are mechanically simple.

### Phase 1: additive

Land four new emitters and one interface default. Nothing routes through them yet. Build stays green; emitted source byte-identical. Each new emitter ships with unit tests against fixture call shapes that mirror what Phase 2 will feed it, so the new APIs have coverage independently of consumers and an API misfit surfaces here, not at the flip.

- **Interface default.** Add `default String rowsMethodName()` on `BatchKeyField` returning `"rows" + capitalize(name())`. Tighten the Javadoc: replace "naming convention is determined by each implementing type independently" (`BatchKeyField.java:17-18`) with "DataLoader-backed variants default to `rows<Name>`; service-backed variants override to `load<Name>` to mark the body as a service delegation." The four SQL leaves keep their (now redundant) overrides; the two service leaves keep their `load<X>` overrides as the documented exception.
- **`RowsMethodSkeleton`** + **`BodyEmitter` strategy.** New utility class. `RowsMethodSkeleton.build(BatchKeyField, returnTypeName, BodyEmitter)` emits the skeleton (modifiers, parameters, return type, empty-input short-circuit, optional `DSLContext dsl = ctx.getDslContext(env);` gated on `BodyEmitter.needsDsl()`) and delegates the body to a strategy. `BodyEmitter` exposes `CodeBlock emit(...)` and `boolean needsDsl()`.
- **`RowsMethodCall`.** New utility class. Two factories:
  - `batchLoaderLambda(BatchKeyField) -> CodeBlock` emits the `(keys, batchEnv) -> { dfe = ...; return CompletableFuture.completedFuture(rowsXxx(keys, dfe)); }` lambda.
  - `directCall(BatchKeyField, CodeBlock keysExpr, CodeBlock envExpr) -> CodeBlock` emits `ResultType rows = ContainingFetcher.rowsXxx(<keysExpr>, <envExpr>);` for tests. Single source of truth for the four pieces: containing-class name, method name (Phase 1's interface default), keys-container type (`Set` or `List` per `isMapped`), result type (via `RowsMethodShape.outerRowsReturnType`).
- **`DataLoaderFetcherEmitter`.** New utility class. `build(BatchKeyField, ReturnTypeRef, AsyncWrapTail) -> MethodSpec` emits the full DataFetcher: name resolution (`buildDataLoaderName`), `computeIfAbsent` registry call wrapping `RowsMethodCall.batchLoaderLambda(...)` (factory chosen by `isMapped`), `GeneratorUtils.buildKeyExtraction(...)`, `loader.load(key, env)` (or `loader.loadMany` per dispatch), async-wrap tail.

### Phase 2: flip

Migrate every consumer in one PR. The diff is delete-and-replace; emitted source diffs to zero modulo one documented behaviour change.

- **Five rows-method emitters → `RowsMethodSkeleton`.** Each becomes a `BodyEmitter`:
  - `SplitRowsMethodEmitter.buildForSplitTable` / `buildForSplitLookupTable` / `buildForRecordTable` / `buildForRecordLookupTable`: body strategy calls `emitParentInputAndFkChain` (Pattern 3, unchanged) then emits the SELECT body.
  - `TypeFetcherGenerator.buildServiceRowsMethod` (line 2823): body strategy emits `return <callTarget>.<methodName>(<args>);`.
- **Three BatchLoader-lambda call sites → `RowsMethodCall.batchLoaderLambda`.** Replace the inline lambda blocks at `TypeFetcherGenerator.java:2762`, `:2922`, `:3022`, each with one `RowsMethodCall.batchLoaderLambda(bkf)` call.
- **Three DataFetcher emitters → `DataLoaderFetcherEmitter`.** Migrate `buildServiceDataFetcher` (line 2733), `buildSplitQueryDataFetcher` (line 2888), `buildRecordBasedDataFetcher` (line 2995). Each shrinks from a multi-block builder to one `DataLoaderFetcherEmitter.build(...)` call.

**Documented behaviour change.** Service-path rows-methods gain the empty-input short-circuit they don't have today (the skeleton always emits the gate). Today `Service.method(emptySet, dsl)` is a wasted call when batch keys are empty; post-flip the rows-method short-circuits to `List.of()` before reaching the service. Inspect the diff for any test that pins the service-path output, update if necessary, and capture in the changelog entry.

### Phase 3: clean

Delete code Phase 2 orphans. Mechanical, no design.

- Four `rowsMethodName()` overrides on `ChildField.SplitTableField` (line 209), `ChildField.SplitLookupTableField` (line 247), `ChildField.RecordTableField` (line 488), `ChildField.RecordLookupTableField` (line 522): the default produces the same string.
- Per-emitter handcrafted skeleton fragments (signature builder, empty-input gate, DSL extraction line) and per-site lambda blocks orphaned by Phase 2.
- `String rowsMethodName = bkf.rowsMethodName();` locals at sites that no longer reference the name directly.

Verify by grep: no caller of any deleted symbol; no inline emit of patterns now owned by the new emitters.

End state: one place to look for each of naming, declaration, call site, and fetcher dance. The SQL-vs-service distinction lives only in the five `BodyEmitter` strategies. Tests reach generated rows-methods through `RowsMethodCall.directCall`, so per-permutation execution-tier coverage scales without re-deriving the call shape per test.
