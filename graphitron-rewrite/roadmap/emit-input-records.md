---
id: R94
title: "Emit SDL input types as graphitron-internal Java records (validation seam)"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [record-accessor-validation]
---

# Emit SDL input types as graphitron-internal Java records (validation seam)

Today the rewrite materialises every SDL `input` type as `Map<String, Object>`
end-to-end: graphql-java hands `env.getArgument("in")` back as a
`LinkedHashMap`, and DML emitters read it via `in.get("title")`
(generated `MutationFetchers.updateFilm`, `MutationFetchers.upsertFilm`;
the emitter site is `TypeFetcherGenerator.buildMutationUpdateFetcher` /
`buildMutationUpsertFetcher` / `buildMutationDeleteFetcher` /
`buildMutationInsertFetcher`). The map shape is an architectural seam — but
it's a *typeless* seam. Three concrete pain points pile up against it:

1. **R12 §5's pre-execution Jakarta validator pre-step is a no-op at execute
   tier.** The emitted code calls `validator.validate(env.getArgument(name))`,
   so the validator's input is a `Map` (or a raw scalar like `Integer`) —
   neither carries Jakarta annotations, and the validator returns no
   violations no matter what the consumer wires. The emit shape itself is
   pinned at unit tier in
   `TypeFetcherGeneratorTest.mutationServiceRecordField_withValidationHandler_emitsValidatorPreStep`
   (`:991-1023`, asserting `body.contains("graphitronContext(env).getValidator(env)")`
   and that the call precedes the `try` block); execute-tier coverage of
   the violation-path round-trip is what this item unblocks.
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

The record is a **fetcher-boundary receptacle**, not a public type.
The same separation logic generalizes to outputs: consumer types belong
at the service-method signature, not at SDL type bindings. R96
([`deprecate-record-directive.md`](deprecate-record-directive.md))
tracks the output-side application of this principle. Two direct
consequences for R94's input-side scope:

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
3. **Service ergonomics: typed scalar args, no `Map<?,?>` plumbing.** Today
   `@service` fields with scalar args already destructure at the fetcher
   boundary (sakila's only example, `submitFilmReview(filmId: Int!, rating:
   Int!)`, emits `service.submit(env.getArgument("filmId"),
   env.getArgument("rating"))`). The map-handling pattern lives on the DML
   side: each of the four DML emitters does
   `Map<?,?> in = (Map<?,?>) env.getArgument("in");` then `in.get("title")`
   (see `MutationFetchers.updateFilm`, `MutationFetchers.upsertFilm`).
   This item collapses both to a single shape: the per-input record is
   coerced once at the fetcher boundary, then either the `@service`
   callsite destructures `input.filmId(), input.rating()` into the
   service's existing scalar parameters, or the DML emitter reads
   `in.title()` instead of `in.get("title")`. The migration surface is
   enumerated below under *Migration surface*; both shapes converge on
   the same record-coercion seam.
4. **A `@constraint` SDL directive (future work)**, if we ever want
   developer-driven Jakarta annotations alongside R92's CHECK-derived ones,
   has a place to land: SDL `@constraint(min: 1, max: 10)` becomes a
   `@Min(1) @Max(10)` annotation on the emitted record component. Tracked
   here only as the natural next step; not in scope for this item.

## Decisions settled in Spec

The forks below are pinned at Spec stage; each is the contract the
implementer builds against. Forks left open for the implementer (or for
a follow-on Spec pass) live in the next section.

### DML migration: single source of truth

The four DML emitters
(`TypeFetcherGenerator.buildMutationInsertFetcher`,
`buildMutationUpdateFetcher`, `buildMutationUpsertFetcher`,
`buildMutationDeleteFetcher`) flip from
`Map<?,?> in = (Map<?,?>) env.getArgument("in"); in.get("title")` to
`FilmInput in = FilmInput.fromMap(env.getArgument("in")); in.title()`.
The dual-emit alternative (record for validation, map for DML reads) was
considered and rejected: per *Stability through simplicity*
(`docs/graphitron-principles.adoc`), two parallel input representations
co-existing is exactly the long-term drift cost the principle warns
against, and the migration surface is small enough that single-source
ships in one phase. The migration surface is enumerated under
*Migration surface* below.

### Map → record coercion: emitted `fromMap` factory per input

Each emitted record gets a `static FilmInput fromMap(Map<String,Object>)`
factory; the fetcher boundary calls it once and hands the result to the
validator and the destructured callsite. Per *Wire-format encoding is a
boundary concern* (`rewrite-design-principles.adoc:83`), the boundary is
a typed adapter/composer pair, and graphitron's emitted code hosts both
halves — symmetric with `ConnectionHelper.encodeCursor` /
`decodeCursor` and `EntityFetcherDispatch.resolveByReps`. Registering
graphql-java `Coercing<FilmInput, Map, Map>` was the alternative; it
pushes decode into graphql-java's argument-binding pipeline and breaks
the boundary symmetry (the decode site moves out of graphitron's
emission), so it's rejected.

### Emitted-record visibility: sealed marker interface

Every emitted input record implements a graphitron-emitted sealed
marker interface, `<outputPackage>.inputs.GraphitronInternalInput`,
whose `permits` clause is generated alongside the records and lists
exactly the per-project emitted record types. Records are public (the
fetcher wrapper at `<outputPackage>.fetchers` calls
`FilmInput.fromMap(...)`, and the validator's reflection requires
public visibility) but the seal documents intent and prevents consumer
code from extending or co-locating in the same hierarchy. Combined
with a generated `package-info.java` `@apiNote DO NOT REFERENCE FROM
SERVICES` and a `LoadBearingClassifierCheck`-style audit (see
*Classifier invariants* below) that flags any service-side reference
to `<outputPackage>.inputs.*` from outside the emitted code, the
"graphitron-internal" principle is structurally carried.

The package-private + facade alternative (option c in the original
fork) was rejected: the records have to be reflectively visible to
the Hibernate Validator, which means at minimum the validator factory
needs cross-package access, and the resulting facade-only API
complicates the destructuring callsite without strengthening the
principle (consumers who want to bypass the principle do so via
reflection regardless).

## Forks open at Spec stage

Three forks below need to be settled before Spec → Ready. Three more
(reachable-closure, nested-input recursion, annotation source v1) are
implementer-confirmable.

### Open: where does record-component → service-param resolution land?

This is the *generation-thinking* fork: the same name-mapping is needed
by (a) the fetcher emitter that destructures `input.filmId()` into the
service call, and (b) R92 phase 3's
`mapping.type(InputRecord.class).field(graphqlFieldName)...` chain.
Resolving twice — once per emitter — duplicates the predicate. Per
*Generation-thinking* (`rewrite-design-principles.adoc:9`, "if two
consumers evaluate the same predicate over a model field, the branch
belongs in the model"), the resolution belongs at classify time,
producing a typed carrier that both emitters consume. R88's
`ClassAccessorResolver` (now on trunk at
`graphitron/src/main/java/no/sikt/graphitron/rewrite/ClassAccessorResolver.java`)
is the shape this fork reuses: classify-time resolution yielding a
sealed `Resolved | Rejected` with each emitter switching on the
variant. The thirteen `Resolved`-returning resolvers under
`FieldBuilder`, landed via R6, are sibling precedent for the same
pattern.

Three sub-decisions the spec must pin:

- **(i) Carrier shape.** Most likely a per-input-component tuple list:
  `record InputComponent(String sdlFieldName, String javaComponentName,
  TypeName javaType, boolean nullable)` plus a top-level
  `record InputRecordShape(ClassName recordClass, List<InputComponent> components)`.
  Sub-fork: should the carrier also carry the per-`@service`-callsite
  mapping (record-component → service-param), or is that a sibling
  carrier?
- **(ii) Matching algorithm (record-component → service-param).** Three
  candidates: (a) by name with `-parameters` on the consumer's compile;
  (b) by position; (c) directive-driven `@service(paramMap: ...)` for
  divergent naming. The recent
  `Recommend <parameters>true</parameters>` change on trunk leans toward
  (a); the spec should commit and document the failure mode when
  `-parameters` is off (build error vs. position fallback).
- **(iii) Rejection arm.** Sealed `Rejected` carrying a candidate-hint
  payload (per the typed-rejection patterns in
  `Rejection.AuthorError.UnknownName`), surfacing through
  `UnclassifiedField` per *Validator mirrors classifier invariants*
  (`rewrite-design-principles.adoc:103`).

### Open: mixed scalar + input service signatures

SDL like
`submitFilmReview(filmId: Int!, rating: Int!, metadata: ReviewMetadataInput)`
mixes scalar args with an input arg. The "service signature unchanged"
claim above implies the emitter destructures only the input arg
(`metadata`) and passes the scalars through directly, not hoisting all
three into a synthetic record. Spec must pin:

- The destructuring shape: only the SDL `input`-typed args become
  records; scalar args remain `env.getArgument(name)`.
- Whether the validator pre-step runs against each `Arg`-sourced
  parameter independently (two `validator.validate(...)` calls in the
  pre-step block: one against the synthetic-list of scalars, one
  against the record), or whether mixed signatures get a synthetic
  compound validation root.

### Open: coercion failure shape and surface

`fromMap` can fail in three ways graphql-java may not catch: (a) wrong
type for a component (graphql-java may have already coerced top-level
scalars, but for nested inputs the depth at which graphql-java stops
vs. graphitron starts is non-obvious); (b) missing required component;
(c) extra unexpected keys (graphql-java rejects unknown variables, but
for inline input literals the contract is fuzzier). Per *Builder-step
results are sealed* (`rewrite-design-principles.adoc:61`), the right
shape is a typed
`FromMapResult.{Ok(record) | TypeMismatch(path, expected, actual) |
MissingRequired(path) | UnknownKey(path)}` rather than throwing or
returning null; that lets R12 §5's `ValidationHandler` pre-step
distinguish coercion failures from validation failures, which it
currently can't.

The spec must pin both:

- **The sealed-result shape** (the four arms above are a starting
  point; the spec confirms or extends).
- **How each arm surfaces.** Symmetric with R12's
  `ConstraintViolations.toGraphQLError`: a graphitron-emitted
  `<outputPackage>.schema.CoercionFailures.toGraphQLError(...)`
  translates each arm into a `GraphQLError` with a stable
  `extensions.classification` distinct from `ConstraintViolation`
  (suggested string values: `"InputCoercion.TypeMismatch"`,
  `"InputCoercion.MissingRequired"`, `"InputCoercion.UnknownKey"`).
  Spec confirms the strings and the file shape.

## Forks the implementer can confirm during In Progress

These three are mechanical enough that an implementer can pick during
implementation, with the choice landing in the In Review diff for
review.

### Reachable-closure scope (recommended: closure only)

SDL frequently has `input` types that are only referenced as nested
children of other inputs, not directly as field arguments. The emitter
walks the reachable closure (every `input` reachable from a field
argument or transitively from another reachable input) and emits a
record per type. Non-reachable input types are dead schema and the
emitter ignores them. Spec confirms this default; implementer can flag
if a reachable-closure walk surfaces an unexpected input shape during
implementation.

### Nested input records (recommended: recurse the coercer)

SDL `input` types frequently nest other inputs (`input FilmsByPathInput
{ … films: [FilmIdItem!]! }`). The record components for these are
themselves records; the coercer recurses. The implementer pins the
null-handling rule for missing optional sub-objects (recommended:
absent key → `null` component; explicit `null` value → `null`
component; both surface to the validator the same way).

### Annotation source v1 (decided: no annotations on emitted records)

Emit records *without* Jakarta annotations in this item. R92 phase 3
attaches `@Pattern` / `@Min` / `@Max` / `@Size` programmatically via
`ConstraintMapping.type(InputRecord.class).field(...)`. This avoids
designing a `@constraint` SDL directive on day one and decouples the
seam-emit from any annotation-source decision. A separate Backlog item
can lift `@constraint` later (see *What lifts cleanly off this seam*
above).

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
- **`@record` on SDL `input` types.** R94 drops support for the
  `@record` directive on `INPUT_OBJECT`. R94's input-side seam (the
  emitted record + Layer 2 `Constructed` bindings off the service
  signature) makes the directive redundant: there's no information
  `@record` could carry that introspection or the existing directives
  don't already provide, and any class string the consumer writes on
  an input is just a drift source against the service-method
  signature. The output-side scope (`OBJECT`) is on the same
  deprecation path under R96
  ([`deprecate-record-directive.md`](deprecate-record-directive.md));
  R94's input-side drop *is* R96's Phase 1. The migration steps live
  under *Phasing → Phase 1* below; the broader argument for why
  `@record` carries no information graphitron can't get elsewhere
  lives in R96.
- Replacing the `Map`-based input handling for non-`@table`-decorated SDL
  inputs that don't drive a DML or `@service` callsite. If there is no
  validator and no DML to feed, the record-emit is still useful for
  consistency, but the spec can scope what's in v1 vs deferred.

## Migration surface

The map-handling pattern lives in two places today; both flip to record
reads in this item.

**DML callsites (4):**

- `MutationFetchers.createFilm` (emitted from
  `TypeFetcherGenerator.buildMutationInsertFetcher`).
- `MutationFetchers.updateFilm` (emitted from
  `TypeFetcherGenerator.buildMutationUpdateFetcher`).
- `MutationFetchers.upsertFilm` (emitted from
  `TypeFetcherGenerator.buildMutationUpsertFetcher`).
- `MutationFetchers.deleteFilm` (emitted from
  `TypeFetcherGenerator.buildMutationDeleteFetcher`).

Each currently does
`Map<?,?> in = (Map<?,?>) env.getArgument("in"); … in.get("title")` and
flips to
`FilmInput in = FilmInput.fromMap(env.getArgument("in")); … in.title()`.

**`@service` callsites with SDL `input` args:** zero in sakila today —
the only `@service` mutation (`submitFilmReview`) takes scalar args
(`filmId: Int!, rating: Int!`) directly. New `@service` shapes that
declare an SDL `input` arg after this item lands automatically pick up
the destructured callsite.

If the implementer adds a sakila fixture with an SDL `input` arg on a
`@service` field (e.g. as part of the execute-tier coverage in *Tests*
below), it joins this list and the emitter exercises the destructuring
path on it.

## Phasing

Three phases, each independently shippable. Phase 1 alone unblocks R12 §5's
execute-tier validator coverage and R92 phase 3's input-side mapping target;
phases 2 and 3 ship the migration surface.

### Phase 1: emit input records and the coercion seam

- New generator class
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
  emits one Java record per reachable SDL `input` type into
  `<outputPackage>.inputs`, plus the sealed marker
  `<outputPackage>.inputs.GraphitronInternalInput` whose `permits`
  clause lists every emitted record.
- Each emitted record carries a `static <Self> fromMap(Map<String,
  Object>)` factory returning a typed `FromMapResult` (sealed; see
  *Forks open at Spec stage* above).
- New emitted artifact
  `<outputPackage>.schema.CoercionFailures.toGraphQLError(...)`
  symmetric with `ConstraintViolationsClassGenerator` /
  `<outputPackage>.schema.ConstraintViolations`.
- Classifier produces the typed `InputRecordShape` carrier (see
  *Forks open at Spec stage*) on `GraphitronType.InputType`.
- `LoadBearingClassifierCheck` keys land on the producer side
  (see *Classifier invariants* below); consumers wear
  `DependsOnClassifierCheck` in phases 2 and 3.
- *Drop `@record` on input types* — this ships R96
  ([`deprecate-record-directive.md`](deprecate-record-directive.md))
  Phase 1 as part of R94's work. Concretely: narrow
  `directives.graphqls:247` from `on OBJECT | INPUT_OBJECT` to
  `on OBJECT`; remove the `@record`-driven arm in
  `TypeBuilder.buildNonTableInputType` (lines ~720-770) along with
  `JavaRecordInputType` and the `@table + @record` shadow rule;
  surface any SDL still using `@record` on an input as
  `UnclassifiedType` with a clear "use the service method's parameter
  type instead" message. Existing fixtures using `@record` on inputs
  migrate or delete: `GraphitronSchemaBuilderTest` cases at lines
  3102, 3113, 3124, 3137, 3152, 3163 specifically test
  `@record`-on-input behavior (delete or repurpose as rejection
  tests); LSP fixtures at `HoversTest.java:158`,
  `ClassNameCompletionsTest.java:57`, `DiagnosticsTest.java:209`,
  `DirectiveShapeSmokeTest.java:125` migrate to plain (non-`@record`)
  inputs.

Acceptance: every reachable SDL `input` type produces a compiling
record; sakila's compile picks up the `<outputPackage>.inputs` package
without warnings; pipeline-tier covers the SDL → record-emit shape; no
existing fetcher behavior changes; SDL with `@record` on inputs is
rejected at classify time.

### Phase 2: flip `@service` callsites with SDL input args

- `TypeFetcherGenerator` (whichever method emits the `@service`
  callsite — likely `buildServiceFetcherCommon` or its callers) reads
  the resolved `InputRecordShape` and emits
  `RecordType in = RecordType.fromMap(env.getArgument("in")); …
  service.x(in.componentA(), in.componentB())` instead of
  `service.x(env.getArgument("in"))`.
- The validator pre-step (R12 §5) consumes the coerced record instead
  of the raw map; pipeline-tier confirms `validator.validate(in)`
  appears with the typed local.
- Phase-2 acceptance is "every existing `@service` field with an SDL
  `input` arg destructures correctly". Sakila has zero such fields
  today; the phase still ships the emitter change and the
  pipeline-tier coverage that proves it works.

### Phase 3: flip DML callsites

- The four DML emitters under
  `TypeFetcherGenerator.buildMutation{Insert,Update,Upsert,Delete}Fetcher`
  flip from `in.get("title")` to `in.title()`. Per *Migration surface*
  above, each callsite is a mechanical rewrite.
- Compilation-tier: sakila compile must pass after the migration.
- Execution-tier: the existing sakila mutation tests
  (`MutationDmlNodeIdClassificationTest`, etc.) still pass; they're
  the regression cover for the `Map.get` → record-accessor flip.

Phase 3 acceptance: full sakila build green, all existing DML
mutation tests pass, no `Map<?,?>` casts in emitted DML bodies.

## Implementation surface (file-by-file)

**New files:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/CoercionFailuresClassGenerator.java`
  (symmetric with `ConstraintViolationsClassGenerator`)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputRecordShape.java`
  (the classifier carrier; sealed `Resolved | Rejected` per the
  resolution-fork decision)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/FromMapResult.java`
  (sealed coercion-failure result; spec confirms shape per
  *Forks open at Spec stage*)
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGeneratorTest.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/CoercionFailuresClassGeneratorTest.java`

**Files modified:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`
  (or wherever `InputType` classification produces the carrier) —
  populate `InputType.recordShape`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  — flip the four DML emitters and the `@service` emitter to consume
  the coerced record.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`
  — add `recordShape: InputRecordShape` to `InputType`.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java`
  — add 3-4 cases covering scalar / nullable / list / nested input
  shapes (see *Tests* below).

## Classifier invariants (`@LoadBearingClassifierCheck` keys)

Per *Validator mirrors classifier invariants*
(`rewrite-design-principles.adoc:103`), the classifier publishes the
following invariants the emitters rely on; emitters wear
`@DependsOnClassifierCheck` against each.

- `input-record.component-matches-service-param` — every emitted
  `<Input>.fromMap(map)` factory has a record component matching every
  service parameter the destructured callsite consumes. Producer:
  `InputRecordShape` resolution. Consumer: `TypeFetcherGenerator`'s
  `@service` destructuring callsite.
- `input-record.shape-matches-dml-bindings` — every record component a
  DML emitter reads (`in.title()`) corresponds to a column the DML's
  `@table`-decorated SDL input declares. Producer: `InputRecordShape`
  resolution + DML field-binding classifier. Consumer: the four DML
  emitters.
- `input-record.is-record-not-map` — the validator pre-step's input
  argument is a graphitron-emitted record (a `GraphitronInternalInput`
  permit), not a `Map` or scalar. Producer: phase 2's wiring. Consumer:
  R12 §5's pre-step (so `getPropertyPath()` returns SDL-named
  segments for `ConstraintViolations.toGraphQLError`).

`LoadBearingGuaranteeAuditTest` picks up orphans automatically.

## Tests

Pipeline-tier is the primary behavioural tier per
*Pipeline tests are the primary behavioural tier*
(`rewrite-design-principles.adoc:126`). Unit-tier covers structural
invariants pipeline coverage would make repetitive; compilation and
execution tiers are the integration cover.

### Pipeline-tier (3-4 cases — primary signal)

`FetcherPipelineTest` adds:

- `inputRecord_scalar_emitsFromMapAndDestructuredCall`: SDL with a
  single-scalar input (`input FilmIdInput { filmId: Int! }`) → emitted
  fetcher body uses `FilmIdInput.fromMap(env.getArgument("in"))` and
  calls `service.x(in.filmId())`.
- `inputRecord_nullable_handlesAbsentVsExplicitNull`: SDL with a
  nullable input field → emitted body distinguishes absent key from
  explicit null per the recursion-rule decision.
- `inputRecord_list_emitsListComponent`: SDL with a list input
  (`films: [FilmIdItem!]!`) → emitted body uses
  `List<FilmIdItem>` component reads.
- `inputRecord_nested_recursesCoercer`: SDL with a nested input
  (`FilmsByPathInput { films: [FilmIdItem!]! }`) → emitted
  `FilmIdItem.fromMap` is called recursively from
  `FilmsByPathInput.fromMap`.

Assertions are structural per "Code-string assertions on generated
method bodies are banned at every tier"
(`rewrite-design-principles.adoc:130`); use
`TypeSpecAssertions.wiringFor(...)` or token-kind walks on
`MethodSpec.code()`.

### Unit-tier (1-2 cases — structural invariants)

`InputRecordGeneratorTest` (new) covers the record-emit shape only:
the package is `<outputPackage>.inputs`, the record implements
`GraphitronInternalInput`, the `fromMap` factory has the expected
signature. Pipeline coverage subsumes assertions about what the
fetcher does with the record.

`CoercionFailuresClassGeneratorTest` (new) covers the four-arm sealed
result: each arm produces a `GraphQLError` with the expected
`extensions.classification` string.

### Compilation-tier

The existing `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`
builds sakila against real jOOQ classes; this picks up
`<outputPackage>.inputs` and verifies that every emitted
`InputRecord.fromMap` and every flipped DML callsite compiles. No new
test class.

### Execution-tier

`GraphQLQueryTest` adds (or extends) one mutation case:

- `submitFilmReview_invalidInput_routesThroughValidationError`: SDL
  changes `submitFilmReview` to take an SDL `input` type
  (`SubmitReviewInput { filmId: Int!, rating: Int! }`); the existing
  `FilmReviewValidationError` `@error(handlers: [{handler:
  VALIDATION}])` channel is wired in; an invalid `rating` value passes
  through `validator.validate(submitReviewInput)` and surfaces as a
  typed error in `payload.errors` with the expected
  `extensions.constraint`. This is the same fixture R12 #5's
  validator-integration bullet calls for, plus the live destructuring
  callsite. Acceptance: the round-trip violation surfaces as the
  typed error R12 §5 promised.
