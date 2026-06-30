---
id: R370
title: "Record-backed parent with a nested backing class emits a non-compiling $-qualified cast"
status: Spec
bucket: bug
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-30
---

# Record-backed parent with a nested backing class emits a non-compiling $-qualified cast

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

- **`deriveAccessorRecordParentSource`** (`FieldBuilder.java:5443-5446`) ; the non-polymorphic R367
  accessor-derivation path. Builds `new AccessorRef(ClassName.bestGuess(parentFqClassName), ...,
  ClassName.bestGuess(accessorElementClass.getName()))`. `parentClass` is loaded at `:5381`,
  `accessorElementClass` at `:5438`. This is the producer that feeds the list-arm helpers
  `buildAccessorKeySingle` / `buildAccessorKeyMany`.
- **`derivePolymorphicHubSource`** (`FieldBuilder.java:5759-5762`) ; the polymorphic hub-discovery
  path. Same `bestGuess` pair; `parentClass` at `:5691`, `elementClass` at `:5741`. Feeds the
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

1. **`FieldBuilder.deriveAccessorRecordParentSource`** (`FieldBuilder.java:5443-5446`) — the
   non-polymorphic `AccessorRef` constructor (feeds the list-arm helpers). Replace
   `ClassName.bestGuess(parentFqClassName)` with `ClassName.get(parentClass)` and
   `ClassName.bestGuess(accessorElementClass.getName())` with `ClassName.get(accessorElementClass)`.
   Both reflected classes are local already (`:5381`, `:5438`).
2. **`FieldBuilder.derivePolymorphicHubSource`** (`FieldBuilder.java:5759-5762`) — the polymorphic
   hub `AccessorRef` constructor (feeds `buildScalarPerParentFetcher`). Same swap; reflected classes
   local at `:5691`, `:5741`. Neither site changes `methodName`, the `SourceKey`, the `LiftedHop`, or
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
  after, it is `((pkg.Outer.Nested) env.getSource())...` and compiles. The single arm is the
  irreducible core (smallest carrier shape, and the arm R367 deferred this from); the list arm
  (`buildAccessorKeyMany`) shares the same fixed `ClassName`, so a list-cardinality nested carrier is a
  cheap add but not strictly required to pin the defect.
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
