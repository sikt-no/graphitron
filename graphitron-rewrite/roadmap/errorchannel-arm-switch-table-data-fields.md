---
id: R268
title: Collapse the Outcome arm-switch to a binary fork over reused field resolution
status: In Review
bucket: structural
depends-on: []
created: 2026-06-01
last-updated: 2026-06-02
---

# Collapse the Outcome arm-switch to a binary fork over reused field resolution

R244's `@service` flip hands graphql-java a typed `Outcome<X>` source for every root `@service` outcome field, and its child fetchers "arm-switch" on the `Success` / `ErrorList` fork. But the way slice 1 built the arm-switch introduced a **second switch over the `ChildField` taxonomy**: `FetcherEmitter.armSwitchValueExpr` re-derives the per-variant read that `dataFetcherValueRaw` already knows how to emit, gated by an allow-list (`OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS`) and a validator pass (`validateOutcomeChildArmSwitch`) that rejects any outcome-type child whose variant isn't on the list. That is a duplicated hierarchy: the allow-list names nine variants, the emitter implements four, and they drift. The drift surfaces two ways: (1) a latent crash, four allow-listed variants (`ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`) would pass the validator and then throw `IllegalStateException` at `FetcherEmitter.java:111`; and (2) a false rejection, real consumer schemas (`opptak-subgraph`) put a `@table`-bound, DataLoader-resolved data field (`RecordTableField`) next to the errors field in nearly every `@service` mutation payload, and since that variant isn't on the list the build fails with an author error even though nothing is wrong with the schema.

The allow-list conflates two unrelated things: "this is an author mistake" and "the parallel emitter hasn't caught up on this variant." A variant that can't arm-switch is a generator limitation, not an author error. This slice removes the duplication at the root rather than reconciling the two switches.

## Implementation (In Progress -> In Review, landed)

All five seams shipped; the design below is the as-built contract. Per-seam landing:

- **Seam 1 (inline reads), `FetcherEmitter`.** `armSwitchValueExpr` / `armSwitchedDataFetcher`
  retired. `dataFetcherValue` now forks three ways under `sourceIsOutcome`: the errors field and
  method-backed DataLoader fields (`RecordTableField` / `RecordLookupTableField` /
  `RecordTableMethodField`) fall through to `dataFetcherValueRaw` (the method reference; the
  generated method owns its own arm-switch), and inline-resolved data fields
  (`ConstructorField` / `NestingField` / `PropertyField` / `RecordField`, via
  `isInlineArmSwitchedDataField`) arm-switch in place. `inlineSuccessRead` covers both inline-read
  backings the spec scopes in: the jOOQ-record column `get` (`((Record) success.value()).get(col)`,
  mirroring `ColumnFetcher`) and the `@record`-Java accessor read (the shared
  `recordBackedAccessorRead(backing, accessor, sourceExpr)`, called by both `propertyOrRecordValue`
  with `env.getSource()` and the arm-switch with `success.value()`, so the accessor switch lives in
  one place). A `NoBacking` parent reads via `PropertyDataFetcher` and is rejected by the validator
  (seam 5) before generation, so `inlineSuccessRead`'s final throw is a defensive backstop, not an
  author-reachable crash.
- **Seams 2+3 (DataLoader fields), `GeneratorUtils` + `DataLoaderFetcherEmitter` +
  `TypeFetcherGenerator`.** `buildRecordParentKeyExtraction` and the `buildFkRowKey` /
  `buildLifterRowKey` / `buildAccessorKeySingle` / `buildAccessorKeyMany` helpers take a source
  binding (`CodeBlock`, default `SOURCE_FROM_ENV`); the outcome path passes `success.value()`.
  `DataLoaderFetcherEmitter.build` gained a pre-registration-prelude overload, and
  `buildRecordBasedDataFetcher` emits, when `sourceIsOutcome`, the narrow + `completedFuture(null)`
  on the `ErrorList` arm *ahead of* the loader `computeIfAbsent` (the preferred seam-3 ordering, so
  the error arm neither registers nor dispatches).
- **Seam 4 (signal threading).** `hasWrapperArmErrors` moved to one home,
  `FetcherEmitter.hasWrapperArmErrors`, consulted by both `FetcherRegistrationsEmitter` (registration
  routing) and `TypeFetcherGenerator.generateTypeSpec` (DataLoader-method emission); the duplicated
  predicate is gone.
- **Seam 5 (validator), `GraphitronSchemaValidator`.** `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS`
  and the allow-list-membership rejection deleted. `validateOutcomeChildArmSwitch` now rejects only a
  sibling that would resolve through graphql-java's default `PropertyDataFetcher`: an
  `UnclassifiedField` (no registration) or a value whose emit is `PropertyDataFetcher.fetching` per the
  shared `FetcherEmitter.resolvesViaPropertyDataFetcher` (a `PayloadAccessor` errors field or a
  property/record read on a `NoBacking` parent). R270 stays moot (no list to reconcile).

Tests: execution-tier `GraphQLQueryTest.submitFilmReviewWithFilm_*` (new sakila fixture pairing a
`@table` DataLoader data field with the errors field under a root `@service` payload; both arms
round-trip); pipeline-tier `FetcherPipelineTest.outcomePayload_tableDataField_*` (RecordTableField
emission + method-reference wiring, not PropertyDataFetcher) and
`outcomePayload_columnDataField_armSwitchesInlineReadOnSuccessValue` (jOOQ-record column read
arm-switches as a lambda, not a bare ColumnFetcher or a generation throw); validation-tier
`OutcomeTypeValidationTest.outcomePayloadWithTableDataField_isNotRejected` (the false-rejection fix).

The four nested-method variants (`ServiceTableField` / `ServiceRecordField` / `TableMethodField` /
`RecordTableMethodField` nested-method emit path) remain out of scope and inventory-absent under
in-scope outcome types; retiring the allow-list removed their latent `IllegalStateException` crash
vector without implementing them. `ComputedField` (jOOQ-result-aliased read) is likewise absent under
a `@record`-backed `@service` payload, so it is not in the inline arm-switch set.

## The design: `Outcome` is binary; T-resolution is reused

`Outcome<T>` has exactly two arms, and the generator wires exactly three fetcher roles per outcome field:

1. **The Outcome producer** ; the outcome field's own fetcher (e.g. `Mutation.endreKvoteplasseringV2`). A **full `DataFetcher`**: it runs the `@service`, does the try/catch + error mapping, and returns `Outcome.Success(value)` or `Outcome.ErrorList(errors)`. It needs the full environment and never unwraps anything; it *creates* the wrapper. Unchanged by this slice (R244 commit 3b already built it).
2. **Each data field of the outcome type** ; **`LightDataFetcher` or full `DataFetcher`, following the field's resolution shape.** When the value is a source read (scalar column, `@record` accessor, constructor/nesting passthrough) the fetcher is a `LightDataFetcher`: `get(GraphQLFieldDefinition, Object sourceObject, Supplier<DataFetchingEnvironment>)` hands the source straight in as a parameter, so it pattern-matches `sourceObject instanceof Outcome.Success<?> success ? <read off success.value()> : null`. When the field's type is **`@table`-bound** (DataLoader-resolved: it needs the environment for the loader and returns a `CompletableFuture`) the fetcher is a **full `DataFetcher`** that narrows `env.getSource()` to `Success` itself, reads the correlation key off `success.value()`, and dispatches. Both shapes make the same *fork*, narrow to `Success`, resolve `null` on the `ErrorList` arm (graphql-java renders the data field null and does not descend, while the non-null `Outcome` source keeps the sibling errors field reachable). But they are not one shell: they substitute the source at structurally different seams (see "Per-shape mechanism"), the Light read swaps `success.value()` for `env.getSource()` inside a single value expression, while the `@table` fetcher prepends a narrow-and-early-return prelude to a multi-statement DataLoader key-extraction. The Light-vs-full split follows the field's resolution shape (the existing `ChildField` leaf), not a uniform wrapper.
3. **The errors field** ; a `LightDataFetcher` reading `ErrorList.errors()` off the source (empty list on the `Success` arm). This is R244's existing `Transport.WrapperArm` emit, unchanged.

The load-bearing move is in role 2: **`<the field's own read>` is the existing emitter for that field, pointed at `success.value()` instead of `env.getSource()`.** `Success.value()` holds exactly what the non-wrapped source would have been (the jOOQ record / Java backing; R244's invariant), so the substitution is type-identical. There is no second taxonomy: every variant resolves through its own `dataFetcherValueRaw` logic. What is uniform is not a single shell method but the **source binding** they read from, threaded as one value (the `Outcome.Success` narrow yielding `success.value()`; see seam 2), so "reuse the emitter" is literally a value-expression substitution for the inline reads and means "repoint the key-extraction prelude" for the DataLoader fields. This is a *generation-time* decision (the parent type's flipped status is known when its children are emitted), not the runtime env-rebuild delegation R244 commit 3a rejected: the unwrap is baked into each generated child fetcher (the Light ones narrow the `sourceObject` parameter, the full `@table` ones narrow `env.getSource()`), so there is no external wrapper to intercept and no env to rebuild.

## What retires, what stays

Retires:

- `FetcherEmitter.armSwitchValueExpr` (the parallel per-variant switch) and the four-shape `IllegalStateException` fallthrough.
- `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` (the allow-list) and `GraphitronSchemaValidator.validateOutcomeChildArmSwitch` (the author-error rejection). A child being un-arm-switchable was never an author concern.
- The need for R270 (reconcile allow-list with emitter) and R268's own former framing (add `RecordTableField` to the list). R270's whole job was reconciling the allow-list with the emitter; once the allow-list is retired there is no list to reconcile, so R270 is moot (discarded, see "Coordination"). R268 itself implements the *in-scope* shapes, the inline source-reads and the two `@table` DataLoader variants (`RecordTableField`, `RecordLookupTableField`). The four formerly-allow-listed nested-method variants are a separate emit path and are out of scope here (see "Per-shape mechanism" and "Out of scope"); retiring the allow-list removes their latent crash vector without R268 having to implement them.

Stays (these are genuine domain rules, not generator limitations, and R244 owns them):

- The single-errors-field rule (`ErrorChannelWalkerError.MultipleErrorsFields`).
- The nullable-success-projection rule (`NonNullableSuccessProjectionField`): a non-null data field still raises `NonNullableFieldWasNullError` on the error arm and drops the errors field, so it must stay rejected.

The "every outcome-type child actually arm-switches" guarantee does not vanish with the validator; it moves to where it belongs. Two pieces pin it, neither of which is the *author* rejection:

- A **build-time structural check** ; the analogue of R244's `validateOutcomeChildArmSwitch`, but asserting the structural invariant instead of allow-list membership: every immediate child of a `WrapperArm`-carrying outcome type resolves through a graphitron-emitted fetcher, never graphql-java's default `PropertyDataFetcher` (which R244 already bans). This is true by construction of `FetcherRegistrationsEmitter`, so it is a near-tautology, but it fails the build loudly if a future leaf ever escapes registration. Note this is *not* the global dispatch-coverage test `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`: R244's invariant section (lines 273-274) already proved that test blind here, it pins a global property (every leaf lands in one dispatch-status set), whereas "this leaf, *when an immediate child of an outcome type*, unwraps `Success`" is contextual. The replacement check is contextual by construction.
- The **execution-tier round-trip** (see Tests) pins that the unwrap is *correct* for each in-scope shape, behaviourally, rather than by inspecting the generated body.

An un-unwrapped child is then a generator bug caught at build, not a schema author error.

## Per-shape mechanism (two seams, one source binding)

- **Inline reads** (column / `@record`-Java-backed accessor): the read is emitted against `(($T) success.value())...` instead of `env.getSource()`. `ColumnFetcher` is already a `LightDataFetcher` reading `((Record) source).get(column)`; the registration supplies it the unwrapped source (or the field emits a thin Light wrapper that narrows then reads). This is the value-expression-substitution seam.
- **Constructor / nesting passthrough**: `success.value()` (same seam).
- **`@table`-bound DataLoader data fields** (`RecordTableField`, `RecordLookupTableField`, the consumer's case): a **full `DataFetcher`** (the field type is `@table`-bound, so it needs the env for the loader and returns a `CompletableFuture`). It narrows `env.getSource()` to `Success`, reads the correlation key off `success.value()`, and dispatches the DataLoader; on the `ErrorList` arm it returns `CompletableFuture.completedFuture(null)`. This is the key-extraction-prelude seam, see seam 3 on the loader-registration ordering, the early return must precede dispatch (and, ideally, registration). The generated method does its own unwrap, no env-rebuild.
- **The four nested-method variants** (`ServiceTableField`, `ServiceRecordField` via `buildServiceRowsMethod` / `buildServiceDataFetcher`; `TableMethodField`, `RecordTableMethodField` via the `@tableMethod` paths) are a **third emit path R268 does not touch**. R244 scoped `@tableMethod` out of the flip, and commit 3b inventoried that no in-scope (root-`@service`-flipped) outcome type carries a nested `@service` / `@tableMethod` data child; R268 widens scope only to the `@table` DataLoader fields, not these. So there is nothing for R268 to "cover by construction" here, and nothing to crash, the allow-list that listed them is gone. When the DML / `@tableMethod` migration slices flip those transports, each owns the arm-switch for its own variant through the same source-binding mechanism (see "Out of scope").

## Implementation seams

1. **`FetcherEmitter`** ; replace `dataFetcherValue`'s `sourceIsOutcome` branch (which routes to `armSwitchValueExpr`) with a uniform shell that wraps the field's own `dataFetcherValueRaw` read, source-bound to `success.value()`. Delete `armSwitchValueExpr`.
2. **`GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`** ; the DataLoader key-extraction reads the backing from `success.value()` under an `Outcome.Success<X>` narrowing instead of `(($T) env.getSource())`. Carry the source as *one* threaded value (a `SourceBinding` / `CodeBlock` prelude + expression computed once at the `buildSplitQueryDataFetcher` seam), not a per-helper `if`. Rename the `__elt` / `__k` dunder locals to readable names (`elt`, `key`) while here (incidental, per R271's note that the global sweep is its own job; R269 coordinates on the same lines).
3. **`TypeFetcherGenerator.buildSplitQueryDataFetcher`** (and the `buildRecordBasedDataFetcher` / `DataLoaderFetcherEmitter` path) ; emit the `Outcome.Success` narrow + `completedFuture(null)`-on-`ErrorList` + key off `success.value()`. **Ordering caveat:** `DataLoaderFetcherEmitter.build` registers the loader (`computeIfAbsent`) *before* it appends the caller-supplied key-extraction block. So fold the narrow into key-extraction and the error arm still registers the loader before returning `completedFuture(null)`, harmless (registration is idempotent) and consistent with the existing FK precedent `buildKeyExtractionWithNullCheck`, but then the honest wording is "returns before *dispatch*," not "without touching the loader." Preferred: emit the narrow + early return in `buildRecordBasedDataFetcher` *ahead of* the `DataLoaderFetcherEmitter.build` call (or add a pre-registration prelude hook to `build`), so the error arm neither registers nor dispatches. Pick one explicitly at In Progress rather than inheriting the post-registration ordering silently.
4. **`FetcherRegistrationsEmitter`** ; the per-type "parent is a flipped outcome type" signal (`hasWrapperArmErrors`) already exists; thread it to every child-fetcher emission (including the generated DataLoader methods), not just `FetcherEmitter.dataFetcherValue`.
5. **`GraphitronSchemaValidator`** ; delete `OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` and the allow-list-membership rejection inside `validateOutcomeChildArmSwitch`. Replace it (not delete outright) with the structural check from "What retires, what stays": every immediate child of a `WrapperArm` outcome type resolves through a graphitron-emitted fetcher, never `PropertyDataFetcher`. Same pass, contextual invariant instead of allow-list membership; it stays loud if a future leaf escapes registration, and it is explicitly not the global `GeneratorCoverageTest` (which cannot see the contextual property).

## Coordination with sibling items

- **R269** (null-guard, Spec) edits the same two `buildAccessorKey*` helpers: it adds the *success-arm* null-record short-circuit, while R268 changes the *source* they read from and renames the dunders. Logically independent (R268 is the error-arm fork before key extraction; R269 is the null read inside it); land in either order, second rebases onto the first's helper shape. See R269's "Coordination with R268".
- **R271** (generator-wide dunder retirement) owns the systematic sweep; R268 only renames `__elt` / `__k` incidentally as it rewrites those lines.
- **R270** (allow-list/emitter reconcile) is **discarded**: this collapse removes the allow-list it was reconciling, so there is nothing left to reconcile. No coverage tracking is lost, the four nested-method variants R270 worried about are inventory-confirmed absent under in-scope outcome types (R244 commit 3b) and their arm-switch is owned by whichever future slice flips their transport, not by a standalone reconciliation item.
- **R244** (error-channel slice 1, In Progress) owns the `Outcome` type, the producer, the `WrapperArm` errors transport, and the two surviving author rules; R268 is the slice that makes the child-side arm-switch uniform.

## Tests

- **Execution (`@ExecutionTier`) is the primary net, and R268 adds the fixture.** No sakila fixture pairs a `@table`-bound DataLoader data field with an errors field under a root `@service` payload (the gap R244's inventory missed). R268 adds one: a `@service` mutation whose payload has such a data field (nullable, per the success-projection rule) sibling to `[SomeError] errors`. The test asserts both arms round-trip against the real generated fetchers, the mapped-error path returns `{data: null, errors: [...]}` and does **not** raise the `__elt`-null NPE; the success path returns `{data: {...}, errors: []}`. Reproduces the `opptak-subgraph` `sakFinnesIkke` / `oppdaterIkkeFunnet` failures in-tree.
- **Pipeline (`@PipelineTier`)**: the data fields under a flipped `@service` payload classify (e.g. `RecordTableField`), and the structural check holds, every immediate child of the `WrapperArm` outcome type resolves through a graphitron-emitted fetcher (none falls through to `PropertyDataFetcher`). A structural assertion on the classified model and the registry, not a fetcher-body string (banned per `rewrite-design-principles.adoc`).
- **Compilation (`@CompilationTier`)**: `mvn install -Plocal-db` end-to-end green over `graphitron-sakila-example`, compiling the `CompletableFuture`-returning arm-switch and catching type-narrowing mismatches across the erased source seam.

## Out of scope

- **DML `SingleRecord*` data fields**: the `DmlTableField` permits and `SingleRecord*` carriers stay on the `LocalContext`/sentinel transport, retired by R244's deferred DML-migration slice.
- **The nested-method data-channel variants** (`ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`) and their `buildServiceRowsMethod` / `buildServiceDataFetcher` emit path: not in scope. Inventory-confirmed absent under in-scope outcome types; their arm-switch lands with the slice that flips their transport.
- **The success-arm null-record guard**: R269.
- **The generator-wide dunder sweep**: R271.
- **Carrier collapse / `Optional<ErrorChannel>` → non-Optional, the `MutationField` interface split**: bundled with the DML migration per R244.
