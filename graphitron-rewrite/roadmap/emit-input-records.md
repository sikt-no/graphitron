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
keeps that exact shape — graphitron destructures the emitted record and
passes the components through to the existing scalar arguments. Likewise for
DML mutations: the emitter consumes the record's accessors to bind jOOQ
columns, but no jOOQ-record-side or service-side type ever references the
emitted input class.

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
  instead. That's a per-DML-emitter rewrite (insert / update / upsert /
  delete) but each call is mechanical. Alternative: keep the map alive for
  DML reads and emit the record purely for validation. The dual-emit shape is
  smaller scope but leaves two parallel input representations co-existing,
  which tends to drift. Recommended default: single record source of truth.

- **Map → record coercion.** graphql-java doesn't auto-coerce input
  `Map<String,Object>` to a Java POJO; the rewrite has to do it. Two shapes:
  (a) emit a static `FilmInput.fromMap(Map<String,Object>)` factory per
  input type and call it once at the fetcher boundary; (b) register a
  per-input-type graphql-java `Coercing<FilmInput, Map, Map>` so
  `env.getArgument("in")` returns the record directly. Option (a) is simpler
  and keeps coercion failures inside graphitron's emitted code; option (b)
  pushes coercion into graphql-java's argument-binding pipeline. Recommended:
  (a) as v1.

- **Nested input records.** SDL `input` types frequently nest other inputs
  (`input FilmsByPathInput { ... films: [FilmIdItem!]! }`). The record
  components for these are themselves records; the coercer recurses. No
  conceptual issue — but the spec needs to nail the recursion shape and the
  null-handling rule for missing optional sub-objects.

- **Annotation source v1.** Emit records *without* Jakarta annotations
  initially; R92 phase 3 attaches them programmatically via
  `ConstraintMapping`. This avoids designing a `@constraint` SDL directive on
  day one and decouples the seam-emit from any annotation-source decision. A
  separate Backlog item can lift `@constraint` later.

- **Coercion failure shape.** Bad input shape (e.g., string where an int is
  expected) becomes a coercion failure inside the record's `fromMap` factory,
  before the validator runs. The emit has to decide whether to surface that
  as a `GraphQLError` (graphql-java already rejects most type mismatches at
  query parse / variable coercion) or as a `ConstraintViolation`-shaped error
  (so consumers see one consistent error shape). Graphql-java's pre-fetcher
  variable coercion likely handles most of this, but the spec should pin the
  contract.

## Non-goals

- Exposing emitted records to service signatures. The architectural principle
  rules this out unconditionally; the spec should not be tempted to "let the
  service take the record directly for convenience".
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
