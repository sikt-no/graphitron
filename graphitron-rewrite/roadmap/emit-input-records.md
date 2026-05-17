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
  `buildMutation{Delete,Insert,Update,Upsert}Fetcher` methods at
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
all retired. R150 already owns `@service` value flow; DML never
needed a typed read because the existing Map.get pattern composes
correctly with R12's DB-violation routing.

The "no service-side reference to emitted records" rule remains.
Generated input records live under `<outputPackage>.inputs`, behind
a sealed `GraphitronInternalInput` marker; services consume R150's
consumer bean, never the graphitron record. The seam stays one-way.

## What lifts cleanly off this seam

Two follow-ons unblock once the record exists:

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

R96 (`Derive backing-class binding from reflection; warn on
redundant @record`, Spec on trunk) is an orthogonal item that owns
the `@record` deprecation: R96 migrates the SDL → backing-class
binding to reflection and warns when the directive is redundant.
R94 does not narrow, deprecate, or remove `@record`; the graphitron
record R94 emits lives at a separate Java identity
(`<outputPackage>.inputs.<InputName>`) from any consumer class
`@record` (or its reflected replacement) binds to. Both coexist on
`InputType`; R96 evolves the consumer-binding slot, R94 adds the
graphitron-emitted-record slot. The two slots answer different
questions (what consumer class does this input map to? vs. what
validation target does graphitron emit for this input?) and have
independent lifecycles.

## Decisions settled in Spec

The forks below are pinned at Spec stage; each is the contract the
implementer builds against. Forks left open for the implementer (or for
a follow-on Spec pass) live in the next section.

### Validate-only record, not value carrier

The record is materialized at the fetcher boundary, passed once to
`validator.validate(...)`, and discarded. DML emitters keep reading
the `Map<?,?>` for value extraction (existing `(Map<?,?>) env.getArgument(...)`
+ `in.get("title")` pattern); `@service` callsites keep R150's
`createBean(Map)` path. The dual-emit alternative (record reads for
DML, Map.get retired) was considered and rejected post-R150: R150 has
already taken the `@service` value-flow role and works against
consumer-authored beans, and DML never had a typed-read motivation
once R12 routed DB constraint violations through typed errors.

Two distinct boundary crossings share the same wire shape
(`Map<String,Object>` from `env.getArgument(...)`): the validator's
crossing reifies the SDL contract as a Java type so Hibernate
Validator's reflection can walk it; the service's crossing
materializes a typed value that flows into business logic. The
graphitron record answers "does this input conform to the SDL
contract?" and is discarded once answered; R150's bean answers "what
value does the service operate on?" and lives the lifetime of the
fetcher call. The SDL-named path segments in
`ConstraintViolation.getPropertyPath()` (R12) and the
`mapping.type(InputRecord.class).field(componentName)` registration
target (R98) need the record, not the bean; the service ergonomics
R150 owns need the bean, not the record. Pipeline-tier coverage on
each side pins against drift.

### Map → record coercion: emitted `fromMap` factory per input

Each emitted record gets a `static MyInput fromMap(Map<String,Object>)`
factory returning the populated record; the fetcher boundary calls it
once and hands the result to the validator. Per *Wire-format encoding
is a boundary concern* (`rewrite-design-principles.adoc:81`), the
decode site lives in graphitron's emitted code, symmetric with
`ConnectionHelper.encodeCursor` / `decodeCursor` and
`EntityFetcherDispatch.resolveByReps`. Registering graphql-java
`Coercing<FilmInput, Map, Map>` was the alternative; it pushes decode
into graphql-java's argument-binding pipeline and breaks the boundary
symmetry (the decode site moves out of graphitron's emission), so
it's rejected.

Runtime type mismatches at `fromMap` (typically a broken custom-scalar
`Coercing` returning a value whose runtime type doesn't match the
component's Java type) surface as a thrown `ClassCastException`,
handled by graphql-java's default error pipeline the same way a
broken `Coercing` already is. A typed wire surface for this case
(sealed `FromMapResult` plus a `CoercionFailures` translator
symmetric with R12's `ConstraintViolations`) is its own additive
item if the failure mode materialises in practice; graphql-java's
upstream coercion catches the common cases (missing-required,
unknown-key) before `fromMap` runs.

### Emitted-record visibility: sealed marker interface

Every emitted input record implements a graphitron-emitted sealed
marker interface, `<outputPackage>.inputs.GraphitronInternalInput`,
whose `permits` clause is generated alongside the records and lists
exactly the per-project emitted record types. Records are public
(the fetcher boundary calls `FilmInput.fromMap(...)` from the
generated fetcher class, and the validator's reflection requires
public visibility) but the seal documents intent and prevents
consumer code from extending or co-locating in the same hierarchy.
Combined with a generated `package-info.java` whose Javadoc states
"Graphitron-internal validation targets; do not reference from
service code" and a `LoadBearingClassifierCheck`-style audit (see
*Classifier invariants* below) that flags any service-side reference
to `<outputPackage>.inputs.*` from outside the emitted code, the
"graphitron-internal" principle is structurally carried.

The package-private + facade alternative was rejected: the records
have to be reflectively visible to Hibernate Validator, which means
at minimum the validator factory needs cross-package access, and the
resulting facade-only API would not strengthen the principle
(consumers who want to bypass it do so via reflection regardless).

### Classify-time carrier: `InputRecordShape` via capability interface

The classifier produces one carrier per SDL input type. The
service-call / destructuring side that an earlier draft tied to this
item is owned by R150 (`CallSiteExtraction.InputBean`,
`InputBeanResolver`) and stays there; R94 does not introduce a
`ServiceCallShape` / `ParamBinding` taxonomy.

```java
// Type-level: owned by TypeBuilder, one per SDL input type
record InputRecordShape(
    ClassName recordClass,                  // e.g. com.example.inputs.SubmitReviewInput
    List<InputComponent> components
) {
    public InputRecordShape {
        Objects.requireNonNull(recordClass);
        if (components.isEmpty()) {
            throw new IllegalStateException(
                "InputRecordShape must have at least one component");
        }
    }
}
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

The slot reaches consumers via a capability interface
`HasInputRecordShape` (per *Capability interfaces and sealed switches
serve different roles*, `rewrite-design-principles.adoc:43`) declared
on the four `InputType` leaves (`PojoInputType`, `JavaRecordInputType`,
`JooqRecordInputType`, `JooqTableRecordInputType`) and on
`TableInputType`. The capability is additive: it doesn't reshape the
existing sealed hierarchy. The slot-on-both-`InputType`-and-
`TableInputType` requirement is a signal that the sibling structure
between those two could fold into a sealed parent `InputLikeType`;
that's its own cleanup item, deferred from R94. The compact
constructor on `InputRecordShape` is the producer-side rejection
backing the LBCC key (see *Classifier invariants*); a `TypeBuilder`
site that fails to construct a shape surfaces as `UnclassifiedType`
via the existing fail-mode.

R98 (Backlog) will later extend `InputComponent` (or sibling-attach)
with `ConstraintSet`; that's R98's design fork, not this item's. The
validator pre-step in R94 walks the empty record (no constraints
yet); R98 then attaches programmatic `ConstraintMapping` entries and
violations start firing.

### Per-arg validator pre-step shape (unchanged from R12)

R12's already-shipped pre-step at
`TypeFetcherGenerator.java:1602-1637` iterates over each SDL arg
independently and emits one `validator.validate(...)` call per arg.
R94 only changes the *target* of that call for input-typed args:

- **Input-typed SDL args** materialize via `fromMap` to the
  graphitron record; the record is the validator target. Runtime
  type mismatch at `fromMap` throws (see *Map → record coercion*).
- **Scalar SDL args** stay unchanged: `env.getArgument(name)` raw
  coerced scalar, `validator.validate(...)` call retained as
  forward-compat for a hypothetical follow-on that attaches
  SDL-level validation directives to scalar args (R98 attaches to
  input types only). Today the call returns zero violations and is
  removable once that forward-compat is either ruled out or owned
  by a follow-on item.

Violations from all per-arg calls accumulate before short-circuit,
identical to R12's existing behavior. The graphitron record local
goes out of scope after the pre-step; downstream value reads route
through R150's consumer-bean path (for `@service`) or the existing
`Map.get` pattern (for DML), neither of which touches the record.

## Mechanical decisions (flag if a counterexample surfaces)

These two are pinned at Spec stage but mechanical enough that an
implementer hitting an unexpected SDL shape during In Progress can
revisit. Decided here so the In Review diff has the contract to
check against rather than discovering it ad hoc.

### Reachable-closure scope

The emitter walks the reachable closure (every SDL `input` type
reachable from a field argument or transitively from another reachable
input) and emits a record per type. Non-reachable input types are dead
schema and the emitter ignores them. The implementer flags only if a
non-reachable input has a real-world use case the closure walk hides.

### Nested input records recurse the coercer

SDL `input` types nest other inputs (`input FilmsByPathInput { films:
[FilmIdItem!]! }`). The record components for these are themselves
records; `fromMap` recurses. Null handling is symmetric: an absent key
and an explicit `null` value both materialize as a `null` component
and surface to the validator the same way. graphql-java's coercion
already collapses the two cases upstream of `fromMap`, so a wire-level
distinction has nowhere to ride; the spec pins symmetric handling to
keep the emitter's coerce-then-validate contract straight.

## Non-goals

- **Exposing emitted records to service signatures.** R150 owns
  `@service` value flow via consumer-authored beans. The graphitron
  record is a validation target only and is discarded after
  `validator.validate(...)` returns. Letting the service take the
  graphitron record "for convenience" would re-create the
  service-side-graphitron-coupling the R150 design rules out.
- **Replacing the `Map.get()` pattern in DML emitters.** The four
  `buildMutation{Delete,Insert,Update,Upsert}Fetcher` methods
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
- **Narrowing, deprecating, or removing the `@record` directive on
  `INPUT_OBJECT`.** R96 (Spec on trunk) owns the migration from
  `@record`-sourced backing-class binding to reflection-derived
  binding; the directive stays on `OBJECT | INPUT_OBJECT` after R94
  ships. R94's graphitron-emitted record at
  `<outputPackage>.inputs.<InputName>` lives at a separate Java
  identity from whatever consumer class `@record` (or its reflected
  replacement) binds the input type to; the two slots on
  `InputType` are independent.
- **Retiring the input-side `GraphitronType.InputType` variants
  (`PojoInputType`, `JavaRecordInputType`, `JooqRecordInputType`,
  `JooqTableRecordInputType`).** R96 keeps these as the produced
  classifications and reshapes how they get populated (reflection
  vs. directive). R94 adds a sibling slot on `InputType` for the
  graphitron-emitted record; the variants survive unchanged.
- **The `@table + @record` shadow rule at
  `TypeBuilder.java:815-824`.** Whatever drives that rule today
  drives it after R94. R96's out-of-scope is explicit on this
  point.

## Implementation

A single phase. R94 ships orthogonal to R96 and to the existing
input-side classification: it adds a sibling slot on `InputType`
carrying the graphitron-emitted record, emits one record per
reachable SDL input type, and rewires R12's validator pre-step at
`TypeFetcherGenerator:1602-1637` to walk the emitted record instead
of the raw `Map`. The four existing input-side variants
(`PojoInputType`, `JavaRecordInputType`, `JooqRecordInputType`,
`JooqTableRecordInputType`), the `@record` directive declaration,
and the `@table + @record` shadow rule are untouched. R96 (Spec on
trunk) reshapes how the variants get populated (reflection vs.
directive); the two items compose on `InputType`.

The shape of the emitted record is exercised end-to-end on day one
by the rewired pre-step: every fetcher whose SDL args include an
input type materializes the record via `fromMap` and walks it
through `validator.validate(...)`. The walk produces zero
violations until R98 (Backlog) attaches programmatic
`ConstraintMapping` entries, but the record's component shape, the
`fromMap` signature, and the validator walk-target are live at
compile and pipeline tiers from the moment R94 ships.

### Deliverables

**Type-side carrier slot.**

- Capability interface `HasInputRecordShape` declared on the four
  `InputType` leaves (`PojoInputType`, `JavaRecordInputType`,
  `JooqRecordInputType`, `JooqTableRecordInputType`) and on
  `TableInputType`, exposing `InputRecordShape recordShape()`.
  Additive; doesn't reshape the existing sealed hierarchy. Folding
  `InputType` ∪ `TableInputType` under a sealed `InputLikeType`
  parent is deferred to its own cleanup item.
- `InputRecordShape` carries a compact constructor that rejects
  null `recordClass` and empty `components`. That non-null
  guarantee at producer-side is the rejection backing the LBCC
  key.
- Populate the slot at every code path in `TypeBuilder` that
  produces an input-type classification: `buildNonTableInputType`
  (line 887), `buildTableInputType` (the `@table` branch), and the
  `@table + @record` shadow case at `:815-824`. The
  variant-selection and shadow-rule logic at those sites is
  unchanged; only the new slot is added.

**Emitted record + sealed marker.**

- New generator class
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
  emits one Java record per reachable SDL `input` type into
  `<outputPackage>.inputs`, plus the sealed marker
  `<outputPackage>.inputs.GraphitronInternalInput` whose `permits`
  clause lists every emitted record. A `package-info.java` carries
  the "Graphitron-internal validation targets; do not reference from
  service code" Javadoc.
- Each emitted record carries a `static Self fromMap(Map<String,
  Object>)` factory returning the populated record; nested input
  components recurse the same factory. Runtime type mismatches
  throw (handled by graphql-java's default error pipeline); a typed
  wire surface is its own additive item if the failure mode
  materialises in practice.

**Validator pre-step rewiring.**

- `TypeFetcherGenerator`'s validator pre-step (currently at
  `:1602-1637`, looping over each input arg and calling
  `validator.validate(env.getArgument(name))`) is rewired to
  materialize the graphitron record via `fromMap` for input-typed
  args and pass the record to `validator.validate(...)`. The
  record local goes out of scope immediately after the pre-step.
- The pre-step's consumer site wears `@DependsOnClassifierCheck`
  pointing at the producer's LBCC key (see *Classifier
  invariants*).
- No constraint annotations on the emitted records yet. R98
  (Backlog) attaches them programmatically once it lands; today the
  validator pre-step produces zero violations on the empty record.
  Acceptable: the shape of the record is fully exercised by the
  pre-step on every fetcher with an input arg, and R170 picks up
  the live invalid-input round-trip the moment R98 ships its first
  SDL constraint.

### Acceptance

- Every reachable SDL `input` type produces a compiling record at
  `<outputPackage>.inputs.<InputName>`; sakila's compile picks up
  the package without warnings.
- Every classified `InputType` leaf and the `TableInputType`
  variant implement `HasInputRecordShape` and return a populated
  `InputRecordShape`. Existing variants survive unchanged.
- Pipeline-tier covers the SDL → record-emit shape, including the
  unreachable-input no-emit case (see *Tests*).
- The validator pre-step at fetcher emit calls
  `validator.validate(<typed record>)` instead of
  `validator.validate(<Map>)` for input-typed args. The regression
  guard test pins this against drift back to the Map.
- No value-flow behavior changes: DML keeps Map.get; `@service`
  keeps R150's consumer-bean path; the four
  `buildMutation{Delete,Insert,Update,Upsert}Fetcher` methods at
  `TypeFetcherGenerator:1736/1782/2025/2291` and the R75/R161
  record-payload paths are untouched.
- The `@record` directive, its declaration at
  `directives.graphqls:290`, the `@table + @record` shadow rule
  (`TypeBuilder.java:815-824`), and existing fixtures in
  `GraphitronSchemaBuilderTest:3443-3520` are untouched. R96 owns
  any further evolution of those surfaces.

### Forward reference: R164 (`field-model-two-axis-pivot`, Backlog)

R164 reorganises the field model into three sealed dimensions
(`DataFetcherBuilder`, `QueryBuilder`, `ValidationBuilder`); the
validator pre-step that R94 rewires lives in `TypeFetcherGenerator`
today, and post-R164 the dispatch moves into
`ValidationBuilder.OnInput`-arm pattern matching. The substance of
the pre-step (call `<InputName>.fromMap(...)`, then
`validator.validate(record)`) is unchanged; R164 repoints the
dispatch site. R94 does *not* anticipate this in its own model:
`InputRecordShape` and `InputComponent` are *type-side* (attached to
`GraphitronType.InputType`), and `ValidationBuilder` is *field-side*
(attached to `Field`). The two axes don't compete. R164 supersedes
R162/R163 but not R94 or R98.

## Implementation surface (file-by-file)

**New files:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGenerator.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputRecordShape.java`
  (with sibling `InputComponent`; compact constructors enforce
  non-null `recordClass` and non-empty `components`)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/HasInputRecordShape.java`
  (capability interface)
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/InputRecordGeneratorTest.java`

**Files modified:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`:
  the four `InputType` leaves (`PojoInputType`, `JavaRecordInputType`,
  `JooqRecordInputType`, `JooqTableRecordInputType`) and the
  `TableInputType` variant declare `HasInputRecordShape`. Additive;
  the sealed hierarchy is not reshaped.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`:
  populate the new `recordShape` slot at every code path that
  produces an input-type classification. The relevant sites today
  are `buildInputType` (line 812, the top-level dispatch),
  `buildNonTableInputType` (line 887), and `buildTableInputType`
  (the `@table` branch). The variant-selection and shadow-rule
  logic at those sites is unchanged; only the new slot is added.
  R96's reflection-derived binding work at the same sites composes
  alongside.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:
  rewire the validator pre-step at `:1602-1637` to walk the
  materialized graphitron record for input-typed args (no other
  emit changes; the four
  `buildMutation{Delete,Insert,Update,Upsert}Fetcher` methods at
  `:1736/1782/2025/2291` and the R75/R161 record-payload paths stay
  on the Map).
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java`:
  add five cases covering scalar / list / nested / unreachable
  shapes and the validator-pre-step regression guard (see *Tests*).

## Classifier invariants (`@LoadBearingClassifierCheck` keys)

Per *Validator mirrors classifier invariants*
(`rewrite-design-principles.adoc:101`):

- `input-record.shape-from-input-type`: every classified SDL
  `input` type produces a non-null `InputRecordShape` whose
  `components` are non-empty and correspond to the SDL fields.
  Producer-side rejection is the compact constructor on
  `InputRecordShape` (rejects null `recordClass` and empty
  `components`); a `TypeBuilder` site that fails to construct a
  shape surfaces as `UnclassifiedType` via the existing fail-mode.
  Producer: `TypeBuilder.buildNonTableInputType` (and the
  `@table`-branch site at `:815-824`). Consumer:
  `InputRecordGenerator`, `TypeFetcherGenerator.validatorPreStep`,
  both via `HasInputRecordShape.recordShape()`.

The emitter invariant that the pre-step's `validator.validate(...)`
argument is the typed record local (not the raw `Map`) is *not* a
classifier key (no schema input rejects on its violation; only the
pre-step emitter can regress). It is pinned by the
`inputRecord_validatorPreStep_receivesTypedRecordNotMap` pipeline
case (see *Tests*).

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
(`rewrite-design-principles.adoc:124`). Unit-tier covers structural
invariants pipeline coverage would make repetitive; compilation tier
is the integration cover. Execution-tier rests with R170.

### Pipeline-tier (5 cases, primary signal)

`FetcherPipelineTest` adds:

- `inputRecord_scalar_emitsFromMapAndValidatesAgainstRecord`: SDL
  with a single-scalar input (`input FilmIdInput { filmId: Int! }`)
  → emitted fetcher body calls
  `FilmIdInput.fromMap(env.getArgument("in"))` and passes
  `result.record()` to `validator.validate(...)`. Value reads on
  the Map remain unchanged; structural assertion confirms both
  shapes coexist.
- `inputRecord_list_emitsListComponent`: SDL with a list input
  (`films: [FilmIdItem!]!`) → emitted body uses
  `List<FilmIdItem>` component reads.
- `inputRecord_nested_recursesCoercer`: SDL with a nested input
  (`FilmsByPathInput { films: [FilmIdItem!]! }`) → emitted
  `FilmIdItem.fromMap` is called recursively from
  `FilmsByPathInput.fromMap`.
- `inputRecord_unreachable_emitsNoRecord`: SDL declaring an `input`
  type that is *not* reachable from any field argument (nor
  transitively from any reachable input) → no record is emitted for
  that type. Pins the *Reachable-closure scope* decision; a future
  closure-walker regression that starts emitting (or stops emitting)
  the unreachable input fails this case.
- `inputRecord_validatorPreStep_receivesTypedRecordNotMap`: for
  every fetcher with an input-typed arg, the `validator.validate(...)`
  argument is the emitted record (`ClassName` matches the generated
  record), not the raw `Map`. Regression guard against drifting the
  pre-step back to `validator.validate(Map)`.

Assertions are structural per "Code-string assertions on generated
method bodies are banned at every tier"
(`rewrite-design-principles.adoc:128`); use
`TypeSpecAssertions.wiringFor(...)` or token-kind walks on
`MethodSpec.code()`.

### Unit-tier (1 case, structural invariants)

`InputRecordGeneratorTest` (new) covers the record-emit shape only:
the package is `<outputPackage>.inputs`, the record implements
`GraphitronInternalInput`, the `fromMap` factory has the expected
signature.

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
execute-tier fixture in R94: the seam is verified at compile and
pipeline tiers.

## Risk

- **R98 delay leaves the validator walk producing zero violations.**
  R94 ships the emitted record and the rewired pre-step; constraint
  annotations come from R98. The pre-step still runs against every
  input arg; it just produces zero violations on the empty record.
  Acceptable: the *shape* of the record (components, `fromMap`
  signature, validator walk-target) is exercised end-to-end on day
  one by the pre-step on every fetcher with an input arg, so R98's
  later content-attachment (adding programmatic `ConstraintMapping`
  entries) doesn't have to reshape the record. The empty walk is
  dead content, not wrong shape. R170 picks up the live execute-tier
  fixture the moment R98 lands its first SDL constraint.
- **Two decoders for one wire shape can drift.** R150's
  `createBean(Map)` and R94's `InputRecord.fromMap(Map)` both decode
  `env.getArgument(...)` (Map) but feed different consumers
  (service vs validator). Both share `ScalarTypeResolver` (R101) for
  component-type derivation, so the building blocks are common;
  edge cases (nested null handling, list-element coercion surfacing)
  live in `fromMap` and `createBean` independently and could
  diverge. Mitigation: pipeline-tier coverage on each side; if a
  real divergence ever surfaces, unifying the decoders is its own
  item.
- **Runtime type mismatch at `fromMap` surfaces untyped.** R94 v1
  throws on mismatch; graphql-java's default error pipeline handles
  it the same way a broken `Coercing` already is. A typed wire
  surface (sealed `FromMapResult` plus a `CoercionFailures`
  translator symmetric with R12's `ConstraintViolations`) is its own
  additive item if the failure mode materialises in practice.
  graphql-java's upstream coercion catches the common cases
  (missing-required, unknown-key) before `fromMap` runs.
- **Hibernate Validator record-walk behaviour changes between
  versions.** R94 leans on 9.0.1's record support: component
  annotations propagate to accessors, `getPropertyPath()` returns
  SDL component names verbatim, programmatic `ConstraintMapping`
  works the same on records as on beans. The version is pinned in
  `graphitron-rewrite/pom.xml`; a future major-version upgrade
  becomes a load-bearing review item rather than a routine bump.
