---
id: R268
title: "Collapse the Outcome arm-switch to a binary fork over reused field resolution"
status: Spec
bucket: structural
depends-on: [errorchannel-walker-carrier]
created: 2026-06-01
last-updated: 2026-06-02
---

# Collapse the Outcome arm-switch to a binary fork over reused field resolution

R244's `@service` flip hands graphql-java a typed `Outcome<X>` source for every root `@service` outcome field, and its child fetchers "arm-switch" on the `Success` / `ErrorList` fork. But the way slice 1 built the arm-switch introduced a **second switch over the `ChildField` taxonomy**: `FetcherEmitter.armSwitchValueExpr` re-derives the per-variant read that `dataFetcherValueRaw` already knows how to emit, gated by an allow-list (`OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS`) and a validator pass (`validateOutcomeChildArmSwitch`) that rejects any outcome-type child whose variant isn't on the list. That is a duplicated hierarchy: the allow-list names nine variants, the emitter implements four, and they drift. The drift surfaces two ways: (1) a latent crash, four allow-listed variants (`ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`) would pass the validator and then throw `IllegalStateException` at `FetcherEmitter.java:111`; and (2) a false rejection, real consumer schemas (`opptak-subgraph`) put a `@table`-bound, DataLoader-resolved data field (`RecordTableField`) next to the errors field in nearly every `@service` mutation payload, and since that variant isn't on the list the build fails with an author error even though nothing is wrong with the schema.

The allow-list conflates two unrelated things: "this is an author mistake" and "the parallel emitter hasn't caught up on this variant." A variant that can't arm-switch is a generator limitation, not an author error. This slice removes the duplication at the root rather than reconciling the two switches.

## The design: `Outcome` is binary; T-resolution is reused

`Outcome<T>` has exactly two arms, and the generator wires exactly three fetcher roles per outcome field:

1. **The Outcome producer** ; the outcome field's own fetcher (e.g. `Mutation.endreKvoteplasseringV2`). A **full `DataFetcher`**: it runs the `@service`, does the try/catch + error mapping, and returns `Outcome.Success(value)` or `Outcome.ErrorList(errors)`. It needs the full environment and never unwraps anything; it *creates* the wrapper. Unchanged by this slice (R244 commit 3b already built it).
2. **Each data field of the outcome type** ; **`LightDataFetcher` or full `DataFetcher`, following the field's resolution shape.** When the value is a source read (scalar column, `@record` accessor, constructor/nesting passthrough) the fetcher is a `LightDataFetcher`: `get(GraphQLFieldDefinition, Object sourceObject, Supplier<DataFetchingEnvironment>)` hands the source straight in as a parameter, so it pattern-matches `sourceObject instanceof Outcome.Success<?> success ? <read off success.value()> : null`. When the field's type is **`@table`-bound** (DataLoader-resolved: it needs the environment for the loader and returns a `CompletableFuture`) the fetcher is a **full `DataFetcher`** that narrows `env.getSource()` to `Success` itself, reads the correlation key off `success.value()`, and dispatches. Both shapes do the same thing, narrow to `Success`, resolve `null` on the `ErrorList` arm (graphql-java renders the data field null and does not descend, while the non-null `Outcome` source keeps the sibling errors field reachable); the Light-vs-full split is only about whether the inner read needs the full env.
3. **The errors field** ; a `LightDataFetcher` reading `ErrorList.errors()` off the source (empty list on the `Success` arm). This is R244's existing `Transport.WrapperArm` emit, unchanged.

The load-bearing move is in role 2: **`<the field's own read>` is the existing emitter for that field, pointed at `success.value()` instead of `env.getSource()`.** `Success.value()` holds exactly what the non-wrapped source would have been (the jOOQ record / Java backing; R244's invariant), so the substitution is type-identical. There is no second taxonomy: every variant resolves through its own `dataFetcherValueRaw` logic; the only addition is a uniform `Success`-narrow shell around it. This is a *generation-time* decision (the parent type's flipped status is known when its children are emitted), not the runtime env-rebuild delegation R244 commit 3a rejected: the unwrap is baked into each generated child fetcher (the Light ones narrow the `sourceObject` parameter, the full `@table` ones narrow `env.getSource()`), so there is no external wrapper to intercept and no env to rebuild.

## What retires, what stays

Retires:

- `FetcherEmitter.armSwitchValueExpr` (the parallel per-variant switch) and the four-shape `IllegalStateException` fallthrough.
- `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` (the allow-list) and `GraphitronSchemaValidator.validateOutcomeChildArmSwitch` (the author-error rejection). A child being un-arm-switchable was never an author concern.
- The need for R270 (reconcile allow-list with emitter) and R268's own former framing (add `RecordTableField` to the list). Both are subsumed: with no allow-list, every variant arm-switches by reusing its emitter, so the four nested-method variants and the two DataLoader variants are covered by construction, not by per-variant catch-up.

Stays (these are genuine domain rules, not generator limitations, and R244 owns them):

- The single-errors-field rule (`ErrorChannelWalkerError.MultipleErrorsFields`).
- The nullable-success-projection rule (`NonNullableSuccessProjectionField`): a non-null data field still raises `NonNullableFieldWasNullError` on the error arm and drops the errors field, so it must stay rejected.

The "every outcome-type child actually arm-switches" guarantee does not vanish with the validator; it moves to where it belongs. Instead of a schema-author rejection keyed on an allow-list, it becomes a **generation-coverage pin**: every immediate child of a flipped outcome type registers an unwrapping fetcher (Light or full, per the rule above; no fall-through to graphql-java's default `PropertyDataFetcher`, which R244 already bans). Pin it like the existing dispatch-coverage tests, an un-unwrapped child is a generator bug caught at build, not a schema author error.

## Per-shape mechanism (all through the one shell)

- **Inline reads** (column / `@record`-Java-backed accessor): the read is emitted against `(($T) success.value())...` instead of `env.getSource()`. `ColumnFetcher` is already a `LightDataFetcher` reading `((Record) source).get(column)`; the registration supplies it the unwrapped source (or the field emits a thin Light wrapper that narrows then reads).
- **Constructor / nesting passthrough**: `success.value()`.
- **`@table`-bound DataLoader data fields** (`RecordTableField`, `RecordLookupTableField`, the consumer's case): a **full `DataFetcher`** (the field type is `@table`-bound, so it needs the env for the loader and returns a `CompletableFuture`). It narrows `env.getSource()` to `Success`, reads the correlation key off `success.value()`, and dispatches the DataLoader; on the `ErrorList` arm it returns `CompletableFuture.completedFuture(null)` **without dispatching**. The generated method does its own unwrap, no env-rebuild.
- **The four nested-method variants** (`ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`): same shell; read parent keys off `success.value()`, then invoke the method/loader, null on the error arm. Covered by construction, this is what subsumes R270.

## Implementation seams

1. **`FetcherEmitter`** ; replace `dataFetcherValue`'s `sourceIsOutcome` branch (which routes to `armSwitchValueExpr`) with a uniform shell that wraps the field's own `dataFetcherValueRaw` read, source-bound to `success.value()`. Delete `armSwitchValueExpr`.
2. **`GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`** ; the DataLoader key-extraction reads the backing from `success.value()` under an `Outcome.Success<X>` narrowing instead of `(($T) env.getSource())`. Carry the source as *one* threaded value (a `SourceBinding` / `CodeBlock` prelude + expression computed once at the `buildSplitQueryDataFetcher` seam), not a per-helper `if`. Rename the `__elt` / `__k` dunder locals to readable names (`elt`, `key`) while here (incidental, per R271's note that the global sweep is its own job; R269 coordinates on the same lines).
3. **`TypeFetcherGenerator.buildSplitQueryDataFetcher`** (and the `buildRecordBasedDataFetcher` / `DataLoaderFetcherEmitter` path) ; emit the narrow + `completedFuture(null)`-on-`ErrorList` + key off `success.value()`.
4. **`FetcherRegistrationsEmitter`** ; the per-type "parent is a flipped outcome type" signal (`hasWrapperArmErrors`) already exists; thread it to every child-fetcher emission (including the generated DataLoader methods), not just `FetcherEmitter.dataFetcherValue`.
5. **`GraphitronSchemaValidator`** ; delete `validateOutcomeChildArmSwitch` and `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS`; add the generation-coverage pin in their place.

## Coordination with sibling items

- **R269** (null-guard, Spec) edits the same two `buildAccessorKey*` helpers: it adds the *success-arm* null-record short-circuit, while R268 changes the *source* they read from and renames the dunders. Logically independent (R268 is the error-arm fork before key extraction; R269 is the null read inside it); land in either order, second rebases onto the first's helper shape. See R269's "Coordination with R268".
- **R271** (generator-wide dunder retirement, Backlog) owns the systematic sweep; R268 only renames `__elt` / `__k` incidentally as it rewrites those lines.
- **R270** (allow-list/emitter reconcile) is **discarded**: this collapse removes the allow-list it was reconciling.
- **R244** (error-channel slice 1, In Progress) owns the `Outcome` type, the producer, the `WrapperArm` errors transport, and the two surviving author rules; R268 is the slice that makes the child-side arm-switch uniform.

## Tests

- **Execution (`@ExecutionTier`) is the primary net, and R268 adds the fixture.** No sakila fixture pairs a `@table`-bound DataLoader data field with an errors field under a root `@service` payload (the gap R244's inventory missed). R268 adds one: a `@service` mutation whose payload has such a data field (nullable, per the success-projection rule) sibling to `[SomeError] errors`. The test asserts both arms round-trip against the real generated fetchers, the mapped-error path returns `{data: null, errors: [...]}` and does **not** raise the `__elt`-null NPE; the success path returns `{data: {...}, errors: []}`. Reproduces the `opptak-subgraph` `sakFinnesIkke` / `oppdaterIkkeFunnet` failures in-tree.
- **Pipeline (`@PipelineTier`)**: the data fields under a flipped `@service` payload classify (e.g. `RecordTableField`) and the generation-coverage pin shows every child registers an unwrapping fetcher; a structural assertion, not a fetcher-body string (banned per `rewrite-design-principles.adoc`).
- **Compilation (`@CompilationTier`)**: `mvn install -Plocal-db` end-to-end green over `graphitron-sakila-example`, compiling the `CompletableFuture`-returning arm-switch and catching type-narrowing mismatches across the erased source seam.

## Out of scope

- **DML `SingleRecord*` data fields**: the `DmlTableField` permits and `SingleRecord*` carriers stay on the `LocalContext`/sentinel transport, retired by R244's deferred DML-migration slice.
- **The success-arm null-record guard**: R269.
- **The generator-wide dunder sweep**: R271.
- **Carrier collapse / `Optional<ErrorChannel>` → non-Optional, the `MutationField` interface split**: bundled with the DML migration per R244.
