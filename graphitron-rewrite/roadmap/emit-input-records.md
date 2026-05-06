---
id: R94
title: "Emit SDL input types as graphitron-internal Java records (validation seam)"
status: Backlog
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
---

# Emit SDL input types as graphitron-internal Java records (validation seam)

Today the rewrite materialises every SDL `input` type as `Map<String, Object>`
end-to-end: graphql-java hands `env.getArgument("in")` back as a
`LinkedHashMap`, and DML emitters read it via `in.get("title")`
(`MutationFetchers.java:55, 75`). The map shape is an architectural seam — but
it's a *typeless* seam. Three concrete pain points pile up against it:

1. **R12 §5's pre-execution Jakarta validator pre-step is a no-op at execute
   tier.** The emitted code calls `validator.validate(env.getArgument(name))`,
   so the validator's input is a `Map` (or a raw scalar like `Integer`) —
   neither carries Jakarta annotations, and the validator returns no
   violations no matter what the consumer wires. Pinned at unit/pipeline tier
   (`TypeFetcherGeneratorTest:1033`, `FetcherPipelineTest:212+`); structurally
   unreachable end-to-end.
2. **R92 phase 3 has no input-side target.** R92's planned
   `mapping.type(InputBean.class).field(graphqlFieldName).constraint(...)`
   chain assumes the consumer's "SDL input bean class" exists. It doesn't —
   graphitron emits no class for any SDL input type today.
3. **R12 #5's "validator integration" execute-tier fixture has nothing to
   pin.** Without a real annotated bean to validate, hand-authored
   `@Min`/`@Max`/`@Pattern` coverage in sakila has no emission target.

This item lifts the seam: emit each SDL `input` type as a Java *record* (one
component per SDL field, names aligned with SDL field names) under
`<outputPackage>.inputs`, coerce `env.getArgument(...)` into the record at the
fetcher boundary, validate the record against the channel's
`ValidationHandler` if any, and only then proceed to the body. The map-based
DML and `@service` callsites flip to record-accessor reads. Hibernate
Validator 9.0.1 (already pinned) supports record validation: component
annotations propagate to accessors, `ConstraintViolation.getPropertyPath()`
returns the SDL component name verbatim, and programmatic
`ConstraintMapping.type(MyRecord.class).field("rating")` works exactly the
same as on a regular bean.

## Architectural principle

**Generated input records are graphitron-internal; services never see them.**
This is the load-bearing constraint: services are written in a domain
vocabulary that does not depend on graphitron, exactly as if graphitron did
not exist. A service signature like `submit(Integer filmId, Integer rating)`
keeps that exact shape — graphitron destructures the emitted record at the
call site and passes the components through to the existing scalar
arguments. Likewise for DML mutations: the emitter consumes the record's
accessors to bind jOOQ columns, but no jOOQ-record-side or service-side
type ever references the emitted input class.

Concretely, the call shape transforms like this. SDL today (scalar args):

```graphql
submitFilmReview(filmId: Int!, rating: Int!): FilmReviewPayload @service(...)
```
emits

```java
service.submit(env.getArgument("filmId"), env.getArgument("rating"));
```

SDL after R94 (input-type arg, **service signature unchanged**):

```graphql
submitFilmReview(input: SubmitReviewInput!): FilmReviewPayload @service(...)
```
emits

```java
SubmitReviewInput input = SubmitReviewInput.fromMap(env.getArgument("input"));
// validator pre-step (R12 §5) runs against `input` here, when the channel
// carries a ValidationHandler
service.submit(input.filmId(), input.rating());
```

The service author still writes `static FilmReviewPayload submit(Integer
filmId, Integer rating)`. The destructuring is the emitter's responsibility
and lives entirely on graphitron's side of the seam.

The record is a **fetcher-boundary receptacle**, not a public type. Two
direct consequences:

- The emitted records live under an `<outputPackage>.inputs` package and are
  not part of any consumer-facing API surface. Renames are graphitron's
  prerogative.
- Hand-rolled service classes can be written, tested, and refactored without
  pulling graphitron-emitted types onto the classpath. The seam stays one-way.

## What lifts cleanly off this seam

Once the records exist, four follow-on capabilities become trivial — each
already designed and waiting for the seam:

1. **R12 §5 wakes up at execute tier.** The pre-step's
   `validator.validate(__arg_input)` runs against a real annotated record and
   produces actual violations. R12 #5's "validator integration fixture"
   becomes a one-line addition to a sakila execute-tier test.
2. **R92 phase 3 unblocks.** The
   `mapping.type(InputRecord.class).field(componentName)...` chain has a real
   target. The CHECK-derived constraints land programmatically on the
   emitted record without touching SDL.
3. **Service ergonomics: typed scalar args, no `Map<?,?>` plumbing.** Today's
   `Map<?,?>`-handling service callsites already extract scalars at the
   fetcher boundary; this item lets the emitter destructure the record into
   exactly the same scalar args, eliminating the cast-and-`get` pattern.
   *Note this is a migration surface, not just a new capability:* every
   existing `@service` field whose argument is an SDL `input` type gets its
   fetcher callsite rewritten from `service.x(env.getArgument("in"))` to
   the destructured form. The spec should enumerate the migration surface
   so the implementer can size it; sakila has at least the four DML cases
   at `MutationFetchers.java:55, 75, 80-86` plus any `@service` field
   whose input arg is currently typed.
4. **A `@constraint` SDL directive (future work)**, if we ever want
   developer-driven Jakarta annotations alongside R92's CHECK-derived ones,
   has a place to land: SDL `@constraint(min: 1, max: 10)` becomes a
   `@Min(1) @Max(10)` annotation on the emitted record component. Tracked
   here only as the natural next step; not in scope for this item.

## Design forks the spec needs to settle

Drafted here so the spec author has the surface visible up front; not
prescriptive.

- **DML migration.** Today's DML emitters read `in.get("title")` against a
  `Map<?,?>`. The straight-line lift is to read `in.title()` from the record
  instead — a per-DML-emitter rewrite (insert / update / upsert / delete)
  but each call is mechanical. Alternative: keep the map alive for DML
  reads and emit the record purely for validation. The dual-emit shape is
  smaller scope but leaves two parallel input representations co-existing,
  which tends to drift. The spec should pick: single source of truth
  (cleaner, larger DML touch surface) vs. dual-emit (smaller diff,
  long-term drift cost).

- **Where does record component → service-param resolution land?** This is
  the *generation-thinking* fork: the same name-mapping is needed by (a)
  the fetcher emitter that destructures `input.filmId()` into the service
  call, and (b) R92 phase 3's
  `mapping.type(InputRecord.class).field(graphqlFieldName)...` chain.
  Resolving twice — once per emitter — duplicates the predicate. Per
  *Generation-thinking* (rewrite-design-principles.adoc:9, "if two consumers
  evaluate the same predicate over a model field, the branch belongs in
  the model"), the resolution belongs at classify time, producing a typed
  carrier (`ServiceCallShape`-style) that both emitters consume. R88's
  `ClassAccessorResolver` is the precedent: classify-time resolution
  yielding a sealed `Resolved | Rejected`, with each emitter switching on
  the variant. The spec should pin: (i) the carrier shape (likely a
  per-input-component tuple `(sdlFieldName, javaComponentName, returnType,
  …)`); (ii) the matching algorithm (by name with `-parameters`, by
  position, or directive-driven `@service(paramMap: …)` for divergent
  naming); (iii) the rejection shape (sealed `Rejected` arm carrying the
  candidate hint, surfacing as `UnclassifiedField` per *Validator mirrors
  classifier invariants*, rewrite-design-principles.adoc:103). Avoid
  committing to (ii) up front; the "by name with `-parameters`" default
  is convenient but should land via spec deliberation, not Backlog
  recommendation.

- **Map → record coercion.** graphql-java doesn't auto-coerce input
  `Map<String,Object>` to a Java POJO; the rewrite has to do it. Two
  candidate shapes: (a) emit a static `FilmInput.fromMap(Map<String,Object>)`
  factory per input type and call it once at the fetcher boundary; (b)
  register a per-input-type graphql-java `Coercing<FilmInput, Map, Map>`
  so `env.getArgument("in")` returns the record directly. Option (a) keeps
  coercion logic inside graphitron's emitted code (parse-boundary symmetry
  with `ConnectionHelper.encodeCursor` / `decodeCursor`); option (b)
  hands the contract to graphql-java's argument-binding pipeline.
  Per *Wire-format encoding is a boundary concern* (rewrite-design-principles.adoc:83),
  the DataFetcher boundary is the canonical decode site, so this is
  primarily about *who emits the decoder*. Spec should weigh; not pre-judged.

- **Emitted-record visibility (graphitron-internal enforcement).** The
  architectural principle says "services never see them"; today that's
  documentation. The type system can carry the constraint. Candidate
  shapes: (a) place the records in `<outputPackage>.inputs` and rely on
  package convention (no structural enforcement); (b) emit them under a
  sealed marker interface (`GraphitronInternalInput`) so consumer code
  can't extend or co-locate; (c) make the records package-private and
  expose only their `fromMap` factories via a generated facade. Option (a)
  is the lightest; (c) is the most defensive. Spec should pick — the
  R94 principle is load-bearing enough to justify a structural choice
  rather than a comment.

- **Reachable-closure scope.** SDL frequently has `input` types that are
  only referenced as nested children of other inputs, not directly as
  field arguments. Does the emitter walk every declared SDL `input` and
  emit a record per type, or only the reachable closure (every `input`
  reachable from a field argument)? The validator-mapping target set R92
  phase 3 walks depends on this answer. Recommended: emit the reachable
  closure only — non-reachable input types are dead schema and the
  emitter shouldn't carry them — but spec should confirm.

- **Mixed scalar + input service signatures.** SDL like
  `submitFilmReview(filmId: Int!, rating: Int!, metadata: ReviewMetadataInput)`
  mixes scalar args with an input arg. The "service signature unchanged"
  claim above implies the emitter destructures only the input arg
  (`metadata`) and passes the scalars through directly, not hoisting all
  three into a synthetic record. Spec should pin this — and decide whether
  the validator pre-step runs against each Arg-sourced parameter
  independently or whether mixed signatures get a synthetic compound
  validation root.

- **Nested input records.** SDL `input` types frequently nest other inputs
  (`input FilmsByPathInput { … films: [FilmIdItem!]! }`). The record
  components for these are themselves records; the coercer recurses. No
  conceptual issue — but the spec needs to nail the recursion shape and the
  null-handling rule for missing optional sub-objects.

- **Annotation source v1.** Emit records *without* Jakarta annotations
  initially; R92 phase 3 attaches them programmatically via
  `ConstraintMapping`. This avoids designing a `@constraint` SDL directive on
  day one and decouples the seam-emit from any annotation-source decision.
  A separate Backlog item can lift `@constraint` later.

- **Coercion failure shape (sealed result, not a wishy-washy fallthrough).**
  Bad input shape can fail the `fromMap` factory in three ways graphql-java
  may not catch: (a) wrong type for a component (graphql-java may have
  already coerced top-level scalars, but for nested inputs the depth at
  which graphql-java stops vs. graphitron starts is non-obvious); (b)
  missing required component; (c) extra unexpected keys (graphql-java
  rejects unknown variables, but for inline input literals the contract
  is fuzzier). Per *Builder-step results are sealed*
  (rewrite-design-principles.adoc:61), the right shape is a typed
  `FromMapResult.{Ok(record) | TypeMismatch(path, expected, actual) |
  MissingRequired(path) | UnknownKey(path)}` rather than throwing or
  returning null; that lets R12 §5's `ValidationHandler` pre-step
  distinguish coercion failures from validation failures, which it
  currently can't. Spec should pin both the sealed shape and how each
  arm surfaces (each maps to a `GraphQLError` with a stable
  `extensions.classification` distinct from `ConstraintViolation`).

## Non-goals

- Exposing emitted records to service signatures. The architectural principle
  rules this out unconditionally; the spec should not be tempted to "let the
  service take the record directly for convenience".
- **Service-side `validator.validate(...)` calls.** Validation is a
  fetcher-boundary concern; the service never sees the record and therefore
  cannot validate against it. R12 §5's `ConstraintViolations.toGraphQLError`
  derives `getPropertyPath()` from the validator's bean-walk against the
  emitted record at the fetcher boundary. A service author who programmatically
  re-validates a value and re-throws would produce a violation whose leaf
  node names a service-side type the emitter never registered, breaking the
  R12 §5 path-translation contract. The seam runs *only* at the fetcher.
- Designing the `@constraint` SDL directive. Tracked as future work; the seam
  this item ships is enough on its own.
- Replacing the `Map`-based input handling for non-`@table`-decorated SDL
  inputs that don't drive a DML or `@service` callsite. If there is no
  validator and no DML to feed, the record-emit is still useful for
  consistency, but the spec can scope what's in v1 vs deferred.

## Tier expectations (sketch — spec elaborates)

- Unit-tier: a per-input-type `InputRecordEmitterTest` over a small set of
  fixture SDL inputs covering scalar / nullable / list / nested cases.
- Pipeline-tier: a `FetcherPipelineTest` arm exercising the record-coercion
  callsite in the emitted fetcher body.
- Compilation-tier: existing sakila compile picks up the new package.
- Execution-tier: at least one sakila mutation exercises the full seam end
  to end (record coerced from map, validated by the channel's
  `ValidationHandler`, destructured into the existing service / DML
  callsite).
