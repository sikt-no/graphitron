---
id: R370
title: "Record-backed parent with a nested backing class emits a non-compiling $-qualified cast"
status: In Review
bucket: bug
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-07-01
---

# Record-backed parent with a nested backing class emits a non-compiling $-qualified cast

## Review feedback (In Review → Ready, 2026-07-01)

Independent In Review → Done review found two **in-hand-category** `bestGuess`-over-binary-`fqClassName`
sites with the identical nested-class defect, both left unfixed and unnamed. Both hold a reflected
`Class<?>` / captured `TypeName` at the site, so they are the same one-for-one-swap shape as the four
sites this pass fixed, **not** the "no reflected `Class` at the site, needs a model-lift" category the
"Sibling latent bug" section defers to R412. The spec's completeness claim (R412 captures every
remaining hazard) is therefore false, and the "Scope note" absorbed exactly the two query-path in-hand
sites while missing the mutation-path and error-channel in-hand sites of the same category.

Aggravating factor: `checkServiceReturnMatchesPayload` (fixed in this pass) guards **both** query and
mutation service-record fields (`FieldBuilder.java:3822`). Before this pass its `bestGuess` mismatch
**spuriously rejected** every nested `@service` payload at classify time, masking these two downstream
emit bugs. This pass removes that rejection, so a nested `@service` payload now **reaches codegen** and
these two sites emit the non-compiling `Outer$Nested`. This pass therefore *unmasks* latent defects it
does not fix; for the mutation and error-channel paths the end-to-end result is now a broken build where
it was previously a classify-time author error.

1. **`FieldBuilder.resolveErrorChannel`** (`FieldBuilder.java:2569`) — the `@service` error-channel
   (Outcome) payload-construction resolver builds `payloadClassName = ClassName.bestGuess(result.fqClassName())`
   even though `payloadCls` (the reflected `Class<?>`) is already loaded at `:2549`. A nested `@service`
   payload carrying an `@error`/Outcome channel emits `Outer$Nested` in the ctor/bean construction arms
   (`buildErrorChannelCtorArm` / `buildErrorChannelBeanArm`) and fails `javac`. Fix: `ClassName.get(payloadCls)`.
2. **`TypeFetcherGenerator.computeMutationServiceRecordReturnType`** (`TypeFetcherGenerator.java:1739`) —
   the mutation twin of the `computeServiceRecordReturnType` this pass fixed. Its class-backed arm still
   rebuilds the type via `ClassName.bestGuess(r.fqClassName())`, so a nested mutation `@service` record
   payload emits `DataFetcherResult<Outer$Nested>` / `Outer$Nested result` and fails `javac`.
   `serviceMethodCall().javaReturnType()` is already read at `:1748` and the mutation path is validated by
   the same `checkServiceReturnMatchesPayload`, so the identical collapse-to-`javaReturnType()` applies.
   The method's own javadoc (`:1732`, "Mirrors `computeServiceRecordReturnType` ... Identical policy") is
   now **false** and must be corrected; this is the design-principles "one site drifts from another"
   (rewrite-design-principles.adoc line 17) and false-invariant-doc (line 141) hazard.

Next pass: apply the two one-for-one swaps (mirroring the query-side fixes already landed), add a
compilation witness that reaches each (a nested mutation `@service` record payload; a nested `@service`
payload with an `@error` channel) since both are latent precisely because no witness reaches them, and
correct the stale mirror javadoc. If instead the team wants to keep this item minimal, these two must at
least be explicitly named in scope and filed as their own item; they do **not** belong in R412 as scoped
(that is the no-reflected-`Class` category). Everything else in this pass verified sound: the two
`AccessorRef` producer swaps, the `checkServiceReturnMatchesPayload` and query-side
`computeServiceRecordReturnType` fixes, both nested compilation witnesses (verified emitting `Outer.Nested`
and compiling), the R412 filing, and a green `mvn install -Plocal-db` (after a local catalog re-seed).

## Second rework pass (In Progress, 2026-07-01): both swaps landed; one review premise corrected

The two one-for-one swaps are applied. Verifying them against the actual generated code turned up a
factual correction to the review's second witness suggestion:

1. **`TypeFetcherGenerator.computeMutationServiceRecordReturnType`** — collapsed the class-backed arm
   to `serviceMethodCall().javaReturnType()` (the mutation twin of the query-side fix), and corrected
   the now-true "Identical policy" javadoc. **Compile-witnessed** by a nested mutation `@service` record
   payload (`NestedFilmReviewPayload` / `NestedFilmReviewPayloadHolder.Payload`, an `Outer.Nested`
   carrier): `mvn install -Plocal-db` emits `DataFetcherResult<Outcome<NestedFilmReviewPayloadHolder.Payload>>`
   and compiles; reverting the swap reproduces the `Outer$Nested` `javac` break.

2. **`FieldBuilder.resolveErrorChannel`** — swapped `bestGuess(fqClassName)` → `ClassName.get(payloadCls)`
   (in-hand; `payloadCls` is already loaded). **The review's stated witness ("a nested `@service` payload
   with an `@error` channel emits `Outer$Nested` in the ctor/bean arms") does not hold in the current
   code**, and this is the correction: after the R244 Outcome flip, root `@service` outcome fields (query
   *and* mutation service-record/table) classify to `ErrorChannel.Mapped`, whose emit is
   `new Outcome.ErrorList<>(...)` — **no developer payload class is constructed**, so `resolveErrorChannel`'s
   `payloadClass` `ClassName` is never emitted on the `@service` path. Verified two ways: reverting the swap
   leaves the sakila-example generated output byte-identical, and `ErrorChannelClassificationTest` pins
   `@service` → `Mapped`. `resolveErrorChannel`'s `PayloadClass` arm is reached instead by a **child
   `@service`** field (via `buildMethodBackedWithChannel`); DML record fields resolve to `LocalContext`.
   The swap is therefore **witnessed at the classification tier**, not by a sakila compile fixture:
   `ErrorChannelClassificationTest.childServiceRecordField_nestedPayloadBacking_payloadClassIsStructurallyResolved`
   classifies a child `@service` returning the nested `AccessorPayloads.NestedErrorsPayload` and asserts
   (object-equality on the `TypeName`, no code-string match, no database) that the resolved
   `ErrorChannel.PayloadClass.payloadClass()` is the structural `Outer.Nested`, not `Outer$Nested`.
   Reverting the swap fails this test (`AccessorPayloads$NestedErrorsPayload`).

The consciously-scoped-out `FetcherEmitter` data-field cast sites (`propertyOrRecordBinding` /
`inlineSuccessRead`, which cast the payload's own scalar/property reads to the backing class via
`bestGuess`) remain **R412**: they hold no reflected `Class<?>`/`codegenLoader` at the site. The
error-channel witness above is deliberately errors-only (no scalar data field) so it does not depend on
those R412 sites; a scalar data field on a nested payload would trip them.

## Problem

`AccessorRef` carries the parent's backing class and the accessor's element class as javapoet
`ClassName`s, built via `ClassName.bestGuess(...)` over a *binary* class name
(`TypeBuilder.buildResultTypeFromClass` stores `cls.getName()`, so a nested class arrives as
`Outer$Nested`). `ClassName.bestGuess` splits only on `.`, so the trailing `Outer$Nested` becomes a
single simple name and is emitted verbatim. The resulting cast (`((pkg.Outer$Nested) env.getSource())
.<accessor>()` in the polymorphic fetchers, and the `(($T) src).<accessor>()` / `element.into(...)`
key extraction in `GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`) does not compile:
`javac` reads `Outer$Nested` as a top-level class name that does not resolve.

This affects **every `AccessorRef` consumer**, not one arm:

- the record-parent split-query key extraction `buildAccessorKeySingle` / `buildAccessorKeyMany`
  (reached via `buildRecordParentKeyExtraction` for any `AccessorCall`-keyed parent, polymorphic or
  not; see the two producers in Mechanism), and
- the single-cardinality polymorphic child fetcher (R367, `buildScalarPerParentFetcher`).

Both arms' execution/compilation tests use **top-level** carriers
(`CreateFilmsPayload`, `AddressOccupantCarrier`), so the nested case is only ever exercised at the
classifier/pipeline tier (`AccessorPayloads.SinglePayload` / `ListPayload`, which classify fine) and
never compiled. The defect is therefore latent: a consumer with a nested record/Pojo carrier and a
polymorphic child gets a generated file that fails `javac`, with no build-time rejection from
graphitron.

`AccessorRef`'s own javadoc claims the `ClassName`s are "resolved at the classifier boundary ... so
the emitter never re-parses the binary class name"; `bestGuess` over a binary string quietly violates
that stated contract, so this also brings the code back in line with the type's documentation.

## Mechanism

`AccessorRef` has **two** construction sites, both in `FieldBuilder`, both using `bestGuess` over
binary names, and both with the reflected `Class<?>` objects already in hand so the cast target can be
resolved structurally:

- **`deriveAccessorRecordParentSource`** (`FieldBuilder.java:5446-5449`) ; the non-polymorphic R367
  accessor-derivation path. Builds `new AccessorRef(ClassName.bestGuess(parentFqClassName), ...,
  ClassName.bestGuess(accessorElementClass.getName()))`. `parentClass` is loaded at `:5384`,
  `accessorElementClass` at `:5441`. This is the producer that feeds the list-arm helpers
  `buildAccessorKeySingle` / `buildAccessorKeyMany`.
- **`derivePolymorphicHubSource`** (`FieldBuilder.java:5762-5765`) ; the polymorphic hub-discovery
  path. Same `bestGuess` pair; `parentClass` at `:5694`, `elementClass` at `:5744`. Feeds the
  single-cardinality polymorphic emitter `buildScalarPerParentFetcher`.

The three consumers (`buildScalarPerParentFetcher`, `buildAccessorKeySingle`, `buildAccessorKeyMany`)
are therefore fed by **two** producers, not one. Both producers carry the same defect, so the
"fix at the construction boundary covers all consumers" story only holds if **both** sites are fixed.

## Plan

Construct the `AccessorRef` `ClassName`s from the reflected `Class<?>` via `ClassName.get(Class<?>)`
rather than from a binary-name string via `bestGuess`. `ClassName.get(Class)`
(`graphitron-javapoet/.../ClassName.java:185`) already walks `getEnclosingClass()` (`:195`, `:199`,
`:206`), so a nested class resolves to the structurally correct `Outer.Nested` and emits as
`Outer.Nested` (the JLS-legal form `javac` resolves), while a top-level class is unchanged. Do this at
**both** `AccessorRef` construction boundaries (see Mechanism) so the fix covers all three consumers;
leave `GraphitronType.fqClassName()`'s binary-name form intact (the `Class.forName(parentFqClassName,
...)` consumers in `FieldBuilder` depend on the binary name for nested-class loading; that string is
the classloader's contract, not the emitter's).

The reflected `Class<?>` objects are already in hand at both sites, so no new reflection or lookup is
introduced; this is a one-for-one swap of how two `ClassName`s are built per site:

- `parentClass` — loaded via `Class.forName(parentFqClassName, false, ctx.codegenLoader())` earlier in
  each method (`:5381` / `:5691`).
- the matched accessor's element class (`:5438` / `:5741`).

This also restores `AccessorRef`'s own javadoc contract, which asserts the `ClassName`s are "resolved
at the classifier boundary ... so the emitter never re-parses the binary class name." `bestGuess` over
a binary string is exactly the re-parse that javadoc says does not happen; `ClassName.get(Class)`
makes the contract true rather than aspirational.

### Why the construction boundary, not the consumers

There are three `AccessorRef` consumers (`buildScalarPerParentFetcher`, `buildAccessorKeySingle`,
`buildAccessorKeyMany`) and two producers (`deriveAccessorRecordParentSource`,
`derivePolymorphicHubSource`). Each consumer reads the carried `ClassName` and hands it straight to
javapoet's `$T`, which is the right altitude for them: a `ClassName` is supposed to already be
structurally correct, and a consumer re-deriving structure from a `ClassName` would be working around
a malformed model value rather than consuming a sound one. Fixing the producers fixes every consumer,
present and future, and makes the invariant `AccessorRef`'s own javadoc already asserts ("`ClassName`s
resolved at the classifier boundary ... so the emitter never re-parses the binary class name") true at
the type's boundary rather than aspirational. A per-consumer fix would leave that promise false at the
producers and force every current and future consumer to re-derive the structural name, which is the
"two consumers evaluate the same predicate over a model field" smell the design principles warn
against.

## Implementation seams

1. **`FieldBuilder.deriveAccessorRecordParentSource`** (`FieldBuilder.java:5446-5449`) — the
   non-polymorphic `AccessorRef` constructor (feeds the list-arm helpers). Replace
   `ClassName.bestGuess(parentFqClassName)` with `ClassName.get(parentClass)` and
   `ClassName.bestGuess(accessorElementClass.getName())` with `ClassName.get(accessorElementClass)`.
   Both reflected classes are local already (`:5384`, `:5441`).
2. **`FieldBuilder.derivePolymorphicHubSource`** (`FieldBuilder.java:5762-5765`) — the polymorphic
   hub `AccessorRef` constructor (feeds `buildScalarPerParentFetcher`). Same swap; reflected classes
   local at `:5694`, `:5744`. Neither site changes `methodName`, the `SourceKey`, the `LiftedHop`, or
   the cardinality split.
3. **No change to the three consumers.** `MultiTablePolymorphicEmitter.buildScalarPerParentFetcher`
   (`:588-590`), `GeneratorUtils.buildAccessorKeySingle` (`:318-347`), and `buildAccessorKeyMany`
   (`:349-397`) all read `accessor.parentBackingClass()` / `accessor.elementClass()` and emit them via
   `$T`; once the carried `ClassName` is structurally correct, every emitted cast is correct. The
   R370 hazard note in `buildScalarPerParentFetcher`'s javadoc (`:544-548`) should be removed (the
   hazard is gone), not just unreferenced.
4. **No model, classifier, or `fqClassName` change.** `AccessorRef`'s shape is untouched;
   `TypeBuilder.buildResultTypeFromClass` keeps storing `cls.getName()` (binary). The fix lives
   entirely at how each producer turns the reflected `Class<?>` into a `ClassName`.

## Tests

The test exists to pin a contract that is currently *false*: `AccessorRef`'s javadoc asserts the
`ClassName`s are resolved at the classifier boundary so the emitter never re-parses the binary name,
yet `bestGuess` re-parses it (badly) today. Frame the fixture as pinning that invariant ; "a
nested-class carrier compiles because the boundary resolved the enclosing structure" ; not merely as
"the nested cast compiles," so a future contributor who reintroduces `bestGuess` sees the test name
point at the contract it protects.

- **Compilation (`@CompilationTier`) is the primary and sufficient net.** The failure mode is "the
  generated cast does not compile," which the tier rubric routes to Compilation; the cast either
  resolves or it does not, and there is no row-content or batching behaviour specific to a nested
  carrier, so no database round-trip is warranted. Add a nested-record carrier to
  `graphitron-sakila-service` (mirror `AddressOccupantCarrier`, but as `Outer.Nested` rather than the
  deliberately top-level record it is today; see its `:11-13` "Top-level ... so its binary name has no
  `$` segment" note, which this fixture is the photo-negative of), wire it through a `@service`
  producer and a single-cardinality polymorphic `firstOccupant`-style child in
  `graphitron-sakila-example/schema.graphqls`, and let `mvn install -Plocal-db` compile the emitted
  fetcher. Before the fix the cast is `((pkg.Outer$Nested) env.getSource())...` and `javac` fails;
  after, it is `((pkg.Outer.Nested) env.getSource())...` and compiles.
- **Both producers must have a compiling nested witness; one fixture is not enough.** The
  single-cardinality polymorphic fixture above exercises `derivePolymorphicHubSource` →
  `buildScalarPerParentFetcher` (producer #2) only. `deriveAccessorRecordParentSource` →
  `buildAccessorKeySingle` / `buildAccessorKeyMany` (producer #1) is a *separate call site* with its
  own reflected `Class<?>` pair; compiling producer #2's output does not compile producer #1's, so a
  botched or later-reverted swap at producer #1 would still leave a nested carrier emitting
  `Outer$Nested` with no failing test. That is exactly the latency condition R370 exists to close
  (the defect survived because *no* nested carrier was ever compiled), and it directly undercuts this
  section's stated purpose ; that a future contributor who reintroduces `bestGuess` sees a red test.
  So a **second, required** compilation fixture: a nested-record carrier driving a list-cardinality
  (or otherwise non-polymorphic split-query) `AccessorCall`-keyed child that routes through
  `buildAccessorKeyMany` / `buildAccessorKeySingle`. Same `Outer.Nested` shape, same
  `mvn install -Plocal-db` compile gate; this is the only witness that guards producer #1. The
  single-cardinality polymorphic arm remains the irreducible core R367 deferred (smallest carrier
  shape), but the two producers are independent boundaries and each needs its own compiling witness ;
  the list-arm fixture is required, not a cheap optional add.
- **No execution-tier case.** Execution would add a Postgres round-trip for a defect that never
  reaches SQL: once the file compiles, the nested-backed parent threads its source through exactly as
  the top-level carrier already does (proven by the existing `AddressOccupantCarrierSingleCardinality`
  execution coverage), and a nested vs top-level carrier produces byte-identical runtime behaviour ;
  only the cast spelling differs. The compile is the honest primary; an execution assertion would
  re-prove behaviour that is already covered and is not what this fix changes.
- **No new pipeline test.** The classifier already handles nested carriers
  (`AccessorPayloads.SinglePayload` / `ListPayload` are themselves nested records and classify fine);
  that is precisely why the defect was latent. A pipeline assertion would re-pin the part that already
  works and miss the part that does not, because the producer fix changes a `ClassName`'s internal
  structure, not the classified model's shape. (Belt-and-suspenders option, *not* required: a
  pipeline assertion that the emitted cast's `TypeName` *equals* the `Outer.Nested` `ClassName` ; an
  object-equality check on a `TypeName`, not a code-string match ; would pin the shape without a
  database. The compile is preferred as the primary because it is the actual failure the user hits.)
- **No generated-body string assertion.** Per `rewrite-design-principles.adoc` ("Code-string
  assertions on generated method bodies are banned at every tier"), do **not** assert the emitted cast
  spells `Outer.Nested` by grepping the fetcher `toString()`. The cast's correctness is pinned by the
  compiler (it compiles), which is what actually matters; the exact source text is the compiler's
  concern, not a string-grep's.

## Scope note: two co-path sites absorbed (discovered during implementation)

The mandated compilation witness (a nested-record carrier wired through a `@service` producer, compiled
by `mvn install -Plocal-db`) cannot pass by fixing only the two `AccessorRef` producers. The identical
`bestGuess`-over-binary-name defect sits at two further sites that lie **directly on the nested-`@service`
path**, upstream of the `AccessorRef` cast, so a nested carrier never even reaches the cast until they
are fixed:

1. **`FieldBuilder.checkServiceReturnMatchesPayload`** (`FieldBuilder.java:3030`) — the `@service`
   return-type validator built the expected payload `ClassName` via `ClassName.bestGuess(fqClassName)`
   and compared it (`TypeName.equals`) against `method.returnType()`, which is
   `TypeName.get(genericReturnType)` (structurally correct). For a nested carrier the two never compare
   equal (`Outer$Nested` vs `Outer.Nested`), so the field is **rejected at classify time** with a
   spurious author-error before codegen runs. Fixed by loading the class
   (`Class.forName(fqClassName, false, ctx.codegenLoader())`; the binary name is the classloader's
   contract) and building the expected `ClassName` via `ClassName.get(Class)`.
2. **`TypeFetcherGenerator.computeServiceRecordReturnType`** (`TypeFetcherGenerator.java:1633`) — the
   `@service` fetcher's declared return type (and the `result` local it feeds) was built via
   `ClassName.bestGuess(fqClassName)`, emitting `DataFetcherResult<Outer$Nested>` / `Outer$Nested
   result = ...`, which fails `javac`. Fixed by sourcing the type from
   `ServiceMethodCall.javaReturnType()`, the structured `TypeName` already captured at walk time and
   already used by the sibling `PojoResultType` arm of the same method. `checkServiceReturnMatchesPayload`
   guarantees it equals the SDL payload type, so this is the same shape the `bestGuess` arm intended,
   only structurally correct.

These are the **same defect class and the same fix shape** as the two `AccessorRef` producers (use the
structural name already in hand), **not** the "no reflected `Class`/`TypeName` at the site, needs a
model-lift" category the Sibling-latent-bug section below defers. Both had a structurally-correct name
already available (a loadable binary name plus `codegenLoader`; a captured `javaReturnType`), so both
are one-for-one swaps at their site, the same altitude R370's thesis argues for. They are absorbed
because they sit on the critical path of the witness this item requires: without them the item would
ship a fix no consumer can reach (every nested `@service` carrier is rejected upstream), guarded by a
test that proves the producer is correct while the feature stays broken end-to-end. The four fixes
together make nested carriers compile and run end-to-end, which the two compilation fixtures now
demonstrate.

## Sibling latent bug (flag, do not absorb)

`GeneratorUtils.backingClassOf` (`:399-408`) has a related `ClassName.bestGuess(fqClassName)` hazard on
the **LifterRef** path (`buildLifterRowKey`, the developer-supplied static-lifter arm), but it is a
*different shape* from the `AccessorRef` fix and R370 deliberately does **not** absorb it. The lifter's
own *declaring* class is already sound (`LifterRef.declaringClass` is built via `ClassName.get(...)` at
`SourceRowDirectiveResolver.java:337`); the latent bug is narrower ; `backingClassOf` re-derives the
*parent backing class* from `GraphitronType.ResultType.fqClassName()` via `bestGuess` **inside the
emitter, with no reflected `Class<?>` in hand at that site**. That is the crucial asymmetry: the
`AccessorRef` fix is a one-for-one swap at a producer boundary that already holds the `Class<?>`,
whereas the `backingClassOf` fix has no such boundary to lift to ; the structural fix there is to carry
the parent backing class as a `ClassName` on the model (the way `AccessorRef` already carries
`parentBackingClass`), a model-shape change with its own blast radius that also touches the `@sourceRow`
/ DTO-parent-batching path. The same `bestGuess`-over-`fqClassName()` recurs at other emit sites
(`recordColumnReadArgs`, `FetcherEmitter`, several `ChildField` sites) for the same reason: no
`Class<?>` at the site. Absorbing one invites "why not all," and the answer ("they have no reflected
`Class` at the site, so they need a model-lift, not a call swap") is exactly the line that keeps R370
tight. Named here so it is not accreted silently; file it as its own Backlog item once R370 lands.

## Out of scope

- **The LifterRef-path `bestGuess` in `backingClassOf`**: separate model value and classifier path;
  follow-up Backlog stub (see above).
- **`GraphitronType.fqClassName()`'s binary-name form**: load-bearing for `Class.forName(...)`
  nested-class loading; unchanged.
- **`AccessorRef`'s shape / the `SourceKey` / cardinality split**: untouched; this is purely how the
  producer builds two `ClassName`s.

## Cross-links

Surfaced during R367 review (R367 shipped the single-cardinality arm at parity with the list arm for
top-level carriers and explicitly deferred nested backing classes here). Shares the `AccessorRef`
construction path with R366 (list cardinality). The null-guard work on the same two helpers
(`buildAccessorKeySingle` / `buildAccessorKeyMany`) is R269; R370 and R269 are orthogonal (R269 adds a
null short-circuit inside the helpers; R370 fixes the `ClassName` those helpers cast through), so
either can land first with no shared-line rebase.
