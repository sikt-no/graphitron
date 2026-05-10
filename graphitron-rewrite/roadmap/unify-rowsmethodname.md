---
id: R38
title: "Unify `rowsMethodName()` and the rows-method seam"
status: Ready
bucket: cleanup
priority: 1
theme: model-cleanup
depends-on: []
---

# Unify `rowsMethodName()` and the rows-method seam

The DataLoader-backed leaf taxonomy has six members, all implementing `BatchKeyField` and emitting a "rows-method" the registered DataLoader's batch lambda calls into. Four (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`) override `rowsMethodName()` with `"rows" + capitalize(name())`; two (`ServiceTableField`, `ServiceRecordField`) override with `"load" + capitalize(name())`. The prefix is a body-shape marker: `rows` for "this method emits SQL to shape rows", `load` for "this method delegates to a developer-supplied service". Underneath, every site that emits or invokes a rows-method handcrafts the same scaffolding: signature `(keys, env)`, optional empty-input gate, optional `DSLContext dsl = ...` line, and the BatchLoader lambda the DataLoader registers.

The end goal is hardening: when conceptually doing the same thing, share the emitter. After R38 every BatchKeyField rows-method declaration goes through one emitter, every BatchLoader lambda through one emitter, every DataLoader-registering DataFetcher through one emitter. The SQL-vs-service split survives only as a sealed `RowsMethodBody` permit plugged into the shared skeleton.

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
- **`RowsMethodSkeleton` + sealed `RowsMethodBody`.** `RowsMethodBody` is a sealed type with one variant per body shape: `SqlSplitTable`, `SqlSplitLookupTable`, `SqlRecordTable`, `SqlRecordLookupTable`, `Service`. Each permit carries only the data its body needs (the SQL permits carry the inputs `emitParentInputAndFkChain` consumes; `Service` carries the `MethodRef` and `callShape`). `RowsMethodSkeleton.build(BatchKeyField, returnTypeName, RowsMethodBody)` emits modifiers, parameters, and return type, then dispatches via exhaustive switch on the permit: the four SQL permits emit the empty-input gate and the `DSLContext dsl = ...` line; the `Service` permit emits the dsl line per `callShape` and omits the gate (matching today's service-path behaviour; see "Out of scope" below). No predicate accessors on `RowsMethodBody`: the type identity encodes which scaffolding the body needs.
- **`RowsMethodCall`.** New utility class with one factory: `batchLoaderLambda(BatchKeyField) -> CodeBlock` emits the `(keys, batchEnv) -> { dfe = ...; return CompletableFuture.completedFuture(rowsXxx(keys, dfe)); }` lambda. Single source of truth for the four pieces: containing-class name, method name (Phase 1's interface default), keys-container type (`Set` or `List` per `isMapped`), result type (via `RowsMethodShape.outerRowsReturnType`).
- **`DataLoaderFetcherEmitter`.** New utility class. `build(BatchKeyField, ReturnTypeRef, AsyncWrapTail) -> MethodSpec` emits the full DataFetcher: name resolution (`buildDataLoaderName`), `computeIfAbsent` registry call wrapping `RowsMethodCall.batchLoaderLambda(...)` (factory chosen by `isMapped`), `GeneratorUtils.buildKeyExtraction(...)`, `loader.load(key, env)` (or `loader.loadMany` per dispatch), async-wrap tail.

### Phase 2: flip

Migrate every consumer in one PR. The diff is delete-and-replace; emitted source diffs to zero.

- **Five rows-method emitters → `RowsMethodSkeleton`.** Each construct site builds the matching `RowsMethodBody` permit and hands it to the skeleton:
  - `SplitRowsMethodEmitter.buildForSplitTable` / `buildForSplitLookupTable` / `buildForRecordTable` / `buildForRecordLookupTable`: build the matching `Sql*` permit carrying the prelude inputs; the skeleton's switch invokes `emitParentInputAndFkChain` (Pattern 3, unchanged) and emits the SELECT body for that permit.
  - `TypeFetcherGenerator.buildServiceRowsMethod` (line 2823): build the `Service` permit carrying `MethodRef` + `callShape`; the skeleton emits `return <callTarget>.<methodName>(<args>);`.
- **Three BatchLoader-lambda call sites → `RowsMethodCall.batchLoaderLambda`.** Replace the inline lambda blocks at `TypeFetcherGenerator.java:2762`, `:2922`, `:3022`, each with one `RowsMethodCall.batchLoaderLambda(bkf)` call.
- **Three DataFetcher emitters → `DataLoaderFetcherEmitter`.** Migrate `buildServiceDataFetcher` (line 2733), `buildSplitQueryDataFetcher` (line 2888), `buildRecordBasedDataFetcher` (line 2995). Each shrinks from a multi-block builder to one `DataLoaderFetcherEmitter.build(...)` call.

### Phase 3: clean

Delete code Phase 2 orphans. Mechanical, no design.

- Four `rowsMethodName()` overrides on `ChildField.SplitTableField` (line 209), `ChildField.SplitLookupTableField` (line 247), `ChildField.RecordTableField` (line 488), `ChildField.RecordLookupTableField` (line 522): the default produces the same string.
- Per-emitter handcrafted skeleton fragments (signature builder, empty-input gate, DSL extraction line) and per-site lambda blocks orphaned by Phase 2.
- `String rowsMethodName = bkf.rowsMethodName();` locals at sites that no longer reference the name directly.

Verify by grep: no caller of any deleted symbol; no inline emit of patterns now owned by the new emitters.

End state: one place to look for each of naming, declaration, call site, and fetcher dance. The SQL-vs-service distinction lives only in the five `RowsMethodBody` permits.

## Out of scope

**Service-path empty-input gate.** Today the four SQL rows-methods short-circuit on `keys.isEmpty()`; the two service rows-methods don't, and `Service.method(emptySet, dsl)` runs as a wasted call when batch keys are empty. Adding the gate is a behaviour change visible to schema authors with side-effecting service methods, not a refactor; it lands as a separate Backlog item with its own pipeline test pinning the new shape. R38 preserves current per-variant gate behaviour (the `RowsMethodSkeleton` switch on the `Service` permit omits the gate) so Phase 2's flip is purely structural.

**`RowsMethodCall.directCall` for tests.** "Tests reach generated rows-methods through one emitter" was a secondary motivation in the Spec discussion but has no real consumer in R38. Today's analogous pattern is reflective: `ScatterSingleByIdxTest` reaches its target via `getDeclaredMethod`. Designing a `directCall` factory now without an actual test consumer is the kind of "anticipated future use" the principles tell us not to build for. A future Backlog item adding rows-method execution-tier tests adds the factory shaped to that test's actual call shape.
