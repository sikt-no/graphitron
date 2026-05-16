---
id: R94
title: "Emit SDL input types as graphitron-internal Java records (validation target)"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
---

# Emit SDL input types as graphitron-internal Java records (validation target)

Today the rewrite has no Java-level seam for "this is what an SDL input
type looks like": `env.getArgument("in")` returns a `LinkedHashMap` and
emitters consume it via `in.get("title")` at every callsite. R12 (Done)
shipped a Jakarta validator pre-step at the fetcher boundary, currently
emitted from `TypeFetcherGenerator.java:1602-1637` (test pin at
`TypeFetcherGeneratorTest.mutationServiceRecordField_withValidationHandler_emitsValidatorPreStep:1011+`),
but the pre-step calls `validator.validate(env.getArgument(name))`
against a raw `Map` (or scalar) with no annotations, so it produces no
violations no matter what the consumer wires. The execute-tier coverage
for that pre-step was split out to **R170**
(`validator-integration-execute-coverage`, Backlog), which is blocked
on this item.

R150 (Done) shipped the `@service`-input materialization story:
`InputBeanResolver` + `InputBeanInstantiationEmitter` instantiate the
*consumer-authored* Java record / JavaBean at the fetcher boundary via
generated `createBean(Map<String,Object>)` helpers (live in sakila as
`submitFilmReviewWithDetails(details: FilmReviewDetailsInput!)` against
the consumer-authored `FilmReviewDetails`). That solves the
service-ergonomics half of R94's earlier framing. It does *not*
provide a per-SDL-input-type Java class: the consumer bean is keyed on
consumer class, and multiple `@service`s can consume the same SDL
input via different beans.

This item emits one **graphitron-internal record per SDL input type**
under `<outputPackage>.inputs` and uses it solely as a **Jakarta
Validator walk target** at the fetcher boundary. The record carries
SDL-derived constraint annotations registered programmatically via
R98's merged `ConstraintSet` once R98 lands; today, registered empty.
It is materialized via a generated `fromMap` factory, validated, and
discarded. Value flow stays as it is today: DML keeps reading the
Map; `@service` keeps R150's consumer-bean path.

Hibernate Validator 9.0.1 (already pinned) supports record validation:
component annotations propagate to accessors,
`ConstraintViolation.getPropertyPath()` returns the SDL component name
verbatim, and programmatic
`ConstraintMapping.type(MyRecord.class).field("rating")` works exactly
the same as on a regular bean.

## Why a graphitron-emitted record (not the consumer bean)

R98 (`multi-source-input-validation`, Backlog, depends on this item)
unifies three constraint sources into one `ConstraintSet` per
`(InputType, field)`: DB CHECK constraints (R92), SDL validation
directives (`@Range`, `@Size`, etc.), and Jakarta annotations on
Layer-2 consumer record ctor params. The runtime emit fans out from
that set to two consumers: rendered SDL directives on the
`GraphQLInputObjectType` (frontend introspects), and programmatic
`ConstraintMapping.type(...).field(...).constraint(...)` for the
runtime validator pre-step.

The runtime consumer requires **one Java class per SDL input type** as
the `mapping.type(...)` target. R150's consumer bean is the wrong unit:

- Two `@service`s consuming the same SDL input via different beans
  would split into two `mapping.type(BeanA.class)` /
  `mapping.type(BeanB.class)` registrations, breaking R98's
  "per-input-type, no per-consumer override" rule.
- DML inputs have no consumer bean at all. Without a graphitron
  record, an SDL `@Range` declared on a DML input is unenforceable
  server-side: the rendered directive shows up on introspection but
  there is no annotated Java class for the validator to walk, so the
  contract degrades to a frontend hint. R12 (Done) routes DB CHECK
  violations through typed errors, but SDL-author-declared ranges
  stricter than the DB's CHECK (or covering fields with no CHECK at
  all) silently accept invalid input.

The graphitron-internal record is the per-SDL-input-type Java
identity that the validator walks. R98's `ConstraintSet` registers
against it; the rendered schema emit reads the same set independently.

## Architectural principle

**The emitted record is a validation target, not a value carrier.**
Once `validator.validate(record)` returns, the record is discarded.
Value flow stays as it is today:

- DML emitters keep reading `(Map<?,?>) env.getArgument("in")` and
  `in.get("title")`. The existing pattern at
  `TypeFetcherGenerator.java:2885-2895` and the four
  `buildMutation{Insert,Update,Upsert,Delete}Fetcher` methods at
  `:1736 / :1782 / :2025 / :2291` are unchanged. The new
  `buildMutationDmlRecordFetcher` / `buildMutationBulkDmlRecordFetcher`
  paths from R75 / R161 (Done) stay on the Map for the same reason.
- `@service` callsites keep the R150 path:
  `createFilmReviewDetails(env.getArgument("details"))` builds the
  consumer-authored bean; the service signature is unchanged.

This narrows R94 dramatically from its earlier framing. The
destructuring algorithm, the `Scalar`/`Constructed` `ParamBinding`
taxonomy, the six-arm `InputBindingRejection`, the
`-parameters`-compile-flag failure mode, the DML callsite migration
— all retired. R150 already owns `@service` value flow; DML never
needed a typed read because the existing Map.get pattern composes
correctly with R12's DB-violation routing.

The "no service-side reference to emitted records" rule remains.
Generated input records live under `<outputPackage>.inputs`, behind
a sealed `GraphitronInternalInput` marker; services consume R150's
consumer bean, never the graphitron record. The seam stays one-way.

## What lifts cleanly off this seam

Three follow-ons unblock once the record exists:

1. **R170 (`validator-integration-execute-coverage`, Backlog).** R12's
   pre-step gains a real annotated walk target. R170's sakila
   execute-tier fixture (an invalid input round-tripping through
   `validator.validate(...)` and surfacing as a typed
   `ConstraintViolation` via R12's already-shipped
   `<outputPackage>.schema.ConstraintViolations.toGraphQLError`)
   becomes addable.
2. **R98 (`multi-source-input-validation`, Backlog).** The merged
   `ConstraintSet`'s programmatic-registration consumer gets its
   `mapping.type(InputRecord.class).field(componentName)...` target.
   The rendered-directive consumer (R98 Phase 2) reads the same set
   independently and is not blocked by R94, but the two-consumer
   architecture is what R94's seam enables.
3. **R96 Phase 1 (drop `@record` on `INPUT_OBJECT`).** Bundled with
   this item. The input-side classifier rework needed to admit
   graphitron records subsumes R96 Phase 1's input-side narrowing,
   so R94 ships both deliverables together. R96's remaining phases
   (output-side `@record` removal) are independent.

## Decisions settled in Spec

The forks below are pinned at Spec stage; each is the contract the
implementer builds against. Forks left open for the implementer (or for
a follow-on Spec pass) live in the next section.

### Validate-only record, not value carrier

The record is materialized at the fetcher boundary, passed once to
`validator.validate(...)`, and discarded. DML emitters keep reading
the `Map<?,?>` for value extraction (existing `(Map<?,?>) env.getArgument(...)`
+ `in.get("title")` pattern); `@service` callsites keep R150's
`createBean(Map)` path. The dual-emit alternative — record reads for
DML, Map.get retired — was considered and rejected post-R150: R150 has
already taken the `@service` value-flow role and works against
consumer-authored beans, and DML never had a typed-read motivation
once R12 routed DB constraint violations through typed errors. Two
parallel representations are warranted here precisely because each
has a distinct role (Map = value carrier, record = validation
identity), unlike the original framing where the record was trying to
be both.

### Map → record coercion: emitted `fromMap` factory per input

Each emitted record gets a `static FromMapResult<FilmInput>
fromMap(Map<String,Object>)` factory; the fetcher boundary calls it
once and hands `Ok.record()` to the validator (or routes `TypeMismatch`
through `CoercionFailures.toGraphQLError`; see *Coercion failure*
below). Per *Wire-format encoding is a boundary concern*
(`rewrite-design-principles.adoc:83`), the decode site lives in
graphitron's emitted code, symmetric with
`ConnectionHelper.encodeCursor` / `decodeCursor` and
`EntityFetcherDispatch.resolveByReps`. Registering graphql-java
`Coercing<FilmInput, Map, Map>` was the alternative; it pushes decode
into graphql-java's argument-binding pipeline and breaks the boundary
symmetry (the decode site moves out of graphitron's emission), so
it's rejected.

### Emitted-record visibility: sealed marker interface

Every emitted input record implements a graphitron-emitted sealed
marker interface, `<outputPackage>.inputs.GraphitronInternalInput`,
whose `permits` clause is generated alongside the records and lists
exactly the per-project emitted record types. Records are public
(the fetcher boundary calls `FilmInput.fromMap(...)` from the
generated fetcher class, and the validator's reflection requires
public visibility) but the seal documents intent and prevents
consumer code from extending or co-locating in the same hierarchy.
Combined with a generated `package-info.java` `@apiNote DO NOT
REFERENCE FROM SERVICES` and a `LoadBearingClassifierCheck`-style
audit (see *Classifier invariants* below) that flags any
service-side reference to `<outputPackage>.inputs.*` from outside
the emitted code, the "graphitron-internal" principle is
structurally carried.

The package-private + facade alternative was rejected: the records
have to be reflectively visible to Hibernate Validator, which means
at minimum the validator factory needs cross-package access, and the
resulting facade-only API would not strengthen the principle
(consumers who want to bypass it do so via reflection regardless).

### Classify-time carrier: `InputRecordShape` only

The classifier produces one carrier per SDL input type. The
service-call / destructuring side that an earlier draft tied to this
item is owned by R150 (`CallSiteExtraction.InputBean`,
`InputBeanResolver`) and stays there; R94 does not introduce a
`ServiceCallShape` / `ParamBinding` taxonomy.

```java
// Type-level — owned by TypeBuilder, one per SDL input type
record InputRecordShape(
    ClassName recordClass,                  // e.g. com.example.inputs.SubmitReviewInput
    List<InputComponent> components
)
record InputComponent(
    String sdlFieldName,                    // e.g. "filmId"
    String javaComponentName,               // e.g. "filmId"
    TypeName javaType,                      // resolved via R101's ScalarTypeResolver
    boolean nullable                        // SDL `!` → false
)
```

`javaType` population delegates to `ScalarTypeResolver` (R101, Done),
which already pins the Coercing-derived Java type via its
`scalar-resolver.coercing-non-erased` `@LoadBearingClassifierCheck`
key. An SDL field whose scalar doesn't classify surfaces as
`UnclassifiedField` via the existing fail-mode and never reaches the
input-record emitter.

R98 (Backlog) will later extend `InputComponent` (or sibling-attach)
with `ConstraintSet`; that's R98's design fork, not this item's. The
validator pre-step in R94 walks the empty record (no constraints
yet); R98 then attaches programmatic `ConstraintMapping` entries and
violations start firing.

### Coercion failure: two-arm sealed `FromMapResult` + emitted `CoercionFailures`

graphql-java's runtime input coercion enforces SDL non-null
(`MissingRequired` is dead in practice) and rejects unknown fields
(`UnknownKey` is dead in practice). The remaining failure mode at the
`fromMap` boundary is a runtime type mismatch — typically a broken
custom-scalar `Coercing` implementation, or an edge case in nested
input materialization — where the coerced map value's runtime type
doesn't match the graphitron-record component's Java type. Rare but
not impossible, and a runtime failure deserves a typed wire surface
rather than redaction.

Per *Builder-step results are sealed*
(`rewrite-design-principles.adoc:61`), the runtime coercion produces a
sealed `FromMapResult`:

```java
sealed interface FromMapResult<R> permits Ok, TypeMismatch {
    record Ok<R>(R record) implements FromMapResult<R> {}
    record TypeMismatch<R>(
        List<String> path,        // dot-segment path into the input, e.g. ["input", "rating"]
        TypeName expectedType,    // e.g. ClassName.get("java.lang", "Integer")
        TypeName actualType       // e.g. ClassName.get("java.lang", "String")
    ) implements FromMapResult<R> {}
}
```

`MissingRequired` and `UnknownKey` are dropped from v1 since
graphql-java's upstream coercion makes them unreachable; they can be
added back as new arms if real cases surface (the seal forces
matching emitter handling, so adding an arm is a controlled change).

Symmetric with R12's already-shipped
`<outputPackage>.schema.ConstraintViolations.toGraphQLError`
(`ConstraintViolationsClassGenerator`), a new graphitron-emitted
artifact translates `TypeMismatch` to a `GraphQLError`:

```java
package <outputPackage>.schema;

public final class CoercionFailures {
    private CoercionFailures() {}

    /** Translate a TypeMismatch FromMapResult arm into a GraphQLError
     *  with stable extensions.classification = "InputCoercion.TypeMismatch". */
    public static GraphQLError toGraphQLError(
        FromMapResult.TypeMismatch<?> failure,
        DataFetchingEnvironment env,
        String argName
    ) { ... }
}
```

`extensions.classification` for `TypeMismatch`:
`"InputCoercion.TypeMismatch"`. Distinct from
`ConstraintViolation`'s classification (the constraint annotation's
simple name) so frontends can route the two kinds of input failures
separately.

The fetcher emit catches `TypeMismatch` from `fromMap` and dispatches
through `CoercionFailures.toGraphQLError` directly, joining the same
violation list R12's pre-step populates. Coercion failures and
validation violations both flow through R12's ErrorChannel-aware
catch arm and surface in the typed `errors` slot of the payload.

### Per-arg validator pre-step shape (unchanged from R12)

R12's already-shipped pre-step at
`TypeFetcherGenerator.java:1602-1637` iterates over each SDL arg
independently and emits one `validator.validate(...)` call per arg.
R94 only changes the *target* of that call for input-typed args:

- **Input-typed SDL args** materialize via `fromMap` to the
  graphitron record (`Ok` arm); the record is the validator target.
  `TypeMismatch` routes through `CoercionFailures.toGraphQLError`
  and joins the same violation list.
- **Scalar SDL args** stay unchanged: `env.getArgument(name)` raw
  coerced scalar, defensive no-op `validator.validate(...)` call.

Violations from all per-arg calls accumulate before short-circuit,
identical to R12's existing behavior. The graphitron record local
goes out of scope after the pre-step; downstream value reads route
through R150's consumer-bean path (for `@service`) or the existing
`Map.get` pattern (for DML), neither of which touches the record.

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

## Non-goals

- **Exposing emitted records to service signatures.** R150 owns
  `@service` value flow via consumer-authored beans. The graphitron
  record is a validation target only and is discarded after
  `validator.validate(...)` returns. Letting the service take the
  graphitron record "for convenience" would re-create the
  service-side-graphitron-coupling the R150 design rules out.
- **Replacing the `Map.get()` pattern in DML emitters.** The four
  `buildMutation{Insert,Update,Upsert,Delete}Fetcher` methods
  (`TypeFetcherGenerator.java:1736/1782/2025/2291`) and the
  R75/R161-shipped `buildMutationDmlRecordFetcher` /
  `buildMutationBulkDmlRecordFetcher` paths keep their current
  `(Map<?,?>) env.getArgument(...)` + `in.get("title")` shape. The
  validate-only record runs in parallel at the fetcher boundary;
  value reads stay on the Map.
- **Destructuring `@service` callsites.** R150 (Done) already
  materializes the consumer bean via `createBean(Map)` helpers; the
  destructuring algorithm, name-match / `argMapping` interplay, and
  `-parameters` failure mode that earlier R94 drafts owned are out
  of scope.
- **Service-side `validator.validate(...)` calls.** Validation is a
  fetcher-boundary concern; the service never sees the graphitron
  record and therefore cannot validate against it. R12's
  `ConstraintViolations.toGraphQLError` derives `getPropertyPath()`
  from the validator's walk against the graphitron record at the
  fetcher boundary; a service author who re-validates would produce
  a violation whose leaf node names a service-side type the emitter
  never registered, breaking R12's path-translation contract.
- **Designing the SDL validation directive set.** R98 owns the
  curated `@Range` / `@Size` / etc. directive set and the merged
  `ConstraintSet` machinery that attaches constraints to the
  emitted records. R94 only emits the record; constraints land on it
  via R98.
- **`@record` on SDL `input` types.** Dropped as part of Phase 2 of
  this item (which delivers R96 Phase 1); see *Phasing → Phase 2*
  below. The broader argument for why `@record` carries no
  information graphitron can't get elsewhere lives in R96.

## Phasing

Two phases, each independently shippable. Phase 1 emits the record
and rewires the validator pre-step to walk it. Phase 2 delivers R96
Phase 1 (drop `@record` on `INPUT_OBJECT`).

### Phase 1: emit input records and the validator-target seam

- New generator class
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
  emits one Java record per reachable SDL `input` type into
  `<outputPackage>.inputs`, plus the sealed marker
  `<outputPackage>.inputs.GraphitronInternalInput` whose `permits`
  clause lists every emitted record.
- Each emitted record carries a `static FromMapResult<Self>
  fromMap(Map<String, Object>)` factory.
- New emitted artifact
  `<outputPackage>.schema.CoercionFailures.toGraphQLError(...)`
  symmetric with R12's `ConstraintViolationsClassGenerator` /
  `<outputPackage>.schema.ConstraintViolations`.
- Classifier produces the typed `InputRecordShape` carrier on
  `GraphitronType.InputType` and its variants (currently
  `PojoInputType`, `JavaRecordInputType`, `JooqRecordInputType`,
  `JooqTableRecordInputType`).
- `TypeFetcherGenerator`'s validator pre-step (currently at
  `:1602-1637`, looping over each input arg and calling
  `validator.validate(env.getArgument(name))`) is rewired to:
  materialize the graphitron record via `fromMap`, branch on
  `FromMapResult`, route `TypeMismatch` through
  `CoercionFailures.toGraphQLError`, and pass `Ok.record()` to
  `validator.validate(...)`. The record local goes out of scope
  immediately after the pre-step.
- `LoadBearingClassifierCheck` keys land on the producer side (see
  *Classifier invariants* below); the pre-step wears
  `@DependsOnClassifierCheck`.
- No constraint annotations on the emitted records yet. R98
  (Backlog) attaches them programmatically once it lands. Today the
  validator pre-step produces no violations on the empty record;
  that's fine — Phase 1 pins the seam and unblocks R170's fixture
  the moment R98 ships its first SDL constraint.

Acceptance: every reachable SDL `input` type produces a compiling
record; sakila's compile picks up the `<outputPackage>.inputs`
package without warnings; pipeline-tier covers the SDL → record-emit
shape; the validator pre-step at fetcher emit calls
`validator.validate(<typed record>)` instead of
`validator.validate(<Map>)`; no value-flow behavior changes (DML
keeps Map.get; `@service` keeps R150's consumer-bean path).

### Phase 2: drop `@record` on `INPUT_OBJECT` (R96 Phase 1)

This is R96 Phase 1's deliverable, bundled here because the
input-side classifier rework that admits graphitron records
necessarily touches the `@record` branch in
`TypeBuilder.buildNonTableInputType`.

- Narrow the directive declaration at
  `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls:290`
  from `on OBJECT | INPUT_OBJECT` to `on OBJECT`.
- Remove the `@record`-driven arm in
  `TypeBuilder.buildNonTableInputType` (`:887-920`). The input-type
  classification collapses to a single shape (the graphitron record
  produced by `InputRecordGenerator`); the `JavaRecordInputType` /
  `JooqRecordInputType` / `JooqTableRecordInputType` variants of
  `GraphitronType.InputType` are removed.
- Remove the `@table + @record` shadow rule
  (`TypeBuilder.java:815-824`); the branch becomes unreachable once
  `INPUT_OBJECT` is off the directive's scope.
- Add a validator rule that rejects `@record` on `INPUT_OBJECT` at
  schema build time, fronted by a `@LoadBearingClassifierCheck` key
  so a future regression (someone re-adding the input scope) is
  caught by the validator that already mirrors classifier
  invariants.
- Migrate existing fixtures using `@record` on inputs:
  `GraphitronSchemaBuilderTest`'s `@record`-on-input cluster
  (currently at `:3443-3520`, six cases: `NO_CLASS`, `POJO_CLASS`,
  `JAVA_RECORD_CLASS`, `JOOQ_TABLE_RECORD_CLASS`, `UNKNOWN_CLASS`,
  `TABLE_PLUS_RECORD`) becomes a rejection cluster — assert each
  fixture now classifies as `UnclassifiedType` with the new
  "`@record` is not permitted on INPUT_OBJECT; use SDL fields"
  message. LSP fixtures (`HoversTest`, `ClassNameCompletionsTest`,
  `DiagnosticsTest`, `DirectiveShapeSmokeTest`) migrate to plain
  (non-`@record`) inputs.
- Update `docs/code-generation-triggers.adoc:139` (the
  `@record`-on-input row) to reflect the narrowed directive scope.

Acceptance: SDL with `@record` on an input rejects at classify time;
the four input-side `GraphitronType.InputType` variants collapse to
the post-R94 shape; sakila build green; the directive declaration
narrows to `on OBJECT` (R96's remaining phases cover the output-side
work and are unblocked by this phase's classifier rework).

## Implementation surface (file-by-file)

**New files:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/CoercionFailuresClassGenerator.java`
  (symmetric with R12's `ConstraintViolationsClassGenerator`)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputRecordShape.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/FromMapResult.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGeneratorTest.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/CoercionFailuresClassGeneratorTest.java`

**Files modified:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`
  — populate `InputType.recordShape` at the `buildNonTableInputType`
  site (currently line 887); Phase 2 also removes the `@record` arm
  (`:888-920`) and the `@table + @record` shadow rule (`:815-824`).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  — rewire the validator pre-step at `:1602-1637` to walk the
  materialized graphitron record (no other emit changes; DML at
  `:1736/1782/2025/2291` and the R75/R161 record-payload paths stay
  on the Map).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`
  — add `recordShape: InputRecordShape` to `InputType`; Phase 2
  collapses the input-side variant set.
- `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
  — Phase 2: narrow `@record` at line 290 from `on OBJECT |
  INPUT_OBJECT` to `on OBJECT`.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  — Phase 2: migrate the `:3443-3520` cluster from happy-path
  classification to rejection-cluster.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java`
  — add 3-4 cases covering scalar / nullable / list / nested input
  shapes (see *Tests* below).

## Classifier invariants (`@LoadBearingClassifierCheck` keys)

Per *Validator mirrors classifier invariants*
(`rewrite-design-principles.adoc:103`):

- `input-record.shape-from-input-type` — every classified SDL
  `input` type produces an `InputRecordShape` with components
  matching its SDL fields. Producer:
  `TypeBuilder.buildNonTableInputType`. Consumer:
  `InputRecordGenerator`,
  `TypeFetcherGenerator.validatorPreStep`.
- `input-record.is-record-not-map` — the validator pre-step's input
  argument is a graphitron-emitted record (a
  `GraphitronInternalInput` permit), not a `Map` or scalar. Producer:
  Phase 1 pre-step rewiring. Consumer: R12's pre-step (so
  `getPropertyPath()` returns SDL-named segments for
  `ConstraintViolations.toGraphQLError`, which R12 already shipped).
- `input-object.no-record-directive` (Phase 2) — `@record` does not
  appear on any `INPUT_OBJECT`. Producer: the schema-build
  validator. Consumer: every input-side classifier branch that
  removed its `@record` reading.

R98 will land additional keys when it attaches constraints to the
emitted records. R101 (`ScalarTypeResolver`, Done) already pins the
component-type derivation via its existing
`scalar-resolver.coercing-non-erased` key;
`InputComponent.javaType` recovers the concrete `I` type parameter
from the scalar's `Coercing<I, O>`.

`LoadBearingGuaranteeAuditTest` picks up orphans automatically.

## Tests

Pipeline-tier is the primary behavioural tier per
*Pipeline tests are the primary behavioural tier*
(`rewrite-design-principles.adoc:126`). Unit-tier covers structural
invariants pipeline coverage would make repetitive; compilation tier
is the integration cover. Execution-tier rests with R170.

### Pipeline-tier (3-4 cases — primary signal)

`FetcherPipelineTest` adds:

- `inputRecord_scalar_emitsFromMapAndValidatesAgainstRecord`: SDL
  with a single-scalar input (`input FilmIdInput { filmId: Int! }`)
  → emitted fetcher body calls
  `FilmIdInput.fromMap(env.getArgument("in"))` and passes
  `result.record()` to `validator.validate(...)`. Value reads on
  the Map remain unchanged; structural assertion confirms both
  shapes coexist.
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
signature.

`CoercionFailuresClassGeneratorTest` (new) covers the two-arm sealed
result: `Ok` and `TypeMismatch` each produce the expected output
shape, with `extensions.classification = "InputCoercion.TypeMismatch"`
on the failure arm.

### Compilation-tier

The existing `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`
builds sakila against real jOOQ classes; this picks up
`<outputPackage>.inputs` and verifies that every emitted
`InputRecord.fromMap` compiles. No new test class.

### Execution-tier

R170 (`validator-integration-execute-coverage`, Backlog, blocked on
R94) owns the execute-tier validator round-trip fixture once a
constraint exists to validate against. R94 itself ships the emit and
the rewired pre-step; the live invalid-input round-trip becomes
addable as soon as R98 ships its first SDL constraint. No new
execute-tier fixture in R94 — Phase 1's seam is verified at compile
and pipeline tiers.

Phase 2 reuses R96's planned validator-rule tests:
`GraphitronSchemaBuilderTest` rejects `@record` on `INPUT_OBJECT`
across the migrated `:3443-3520` cluster (now positive-rejection
cases) and continues to accept `@record` on `OBJECT` (negative
control).
