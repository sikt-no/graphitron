---
id: R98
title: "Multi-source input validation: SDL directives + DB CHECK + Jakarta on a unified rendered schema"
status: Backlog
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [emit-input-records, catalog-check-constraint-validation]
---

# Multi-source input validation: SDL directives + DB CHECK + Jakarta on a unified rendered schema

R94 emits an internal Java record per SDL input type. R92 phase 3
attaches programmatic Jakarta `ConstraintMapping` entries to those
records derived from PostgreSQL `CHECK` constraints. R12 §5's
pre-execution validator step runs against each input at the fetcher
boundary. Three pipes today; three different sources of truth for
"what does this input need to look like to be valid"; only one of
them (DB CHECK) is currently surfaced to consumers anywhere outside
of the runtime violation report.

This item lifts the validation contract to a first-class wire shape.
Three constraint sources merge into one per-(input-type, field)
constraint set. Two emit consumers walk the merged set: the
schema-class emitter applies SDL directives on the rendered
`GraphQLInputObjectType`, and R12 §5's pre-step continues to use
programmatic `ConstraintMapping` (now populated from the merged set,
not just CHECK-derived). The frontend introspects the rendered schema
and reads the same constraints the runtime enforces.

## Constraint sources

Three:

1. **DB CHECK constraints** — per-column, recognized by R92's
   `CheckRecognizer` into `Recognized.{StringOneOf, NumericRange,
   LengthBound, RegexMatch, NotNullCheck}`. Reachable from an SDL
   input field that maps to a column (via `@field(name: ...)` on
   the input field; the table comes from the consuming field's
   return type per R97).
2. **SDL validation directives on input fields** — directly
   declared by the schema author. We adopt a curated subset of
   [`graphql-java-extended-validation`](https://github.com/graphql-java/graphql-java-extended-validation)'s
   directive set, scoped to those that map 1:1 to Jakarta
   annotations. See *Adopted directive set* below.
3. **Jakarta annotations on consumer Layer 2 record components** —
   when a service signature has a parameter typed as a consumer's
   own record (e.g. `record SubmissionMetadata(@NotNull Integer
   filmId, @Min(1) @Max(10) Integer rating)`) and R94's classifier
   produces a `Constructed` binding from graphitron's input
   components into that consumer record's ctor params, the consumer's
   Jakarta annotations on those ctor params flow back to the SDL
   input field that supplies them.

Per-input-type, no per-consumer override. If two services consuming
the same input type need different validation, the schema author
splits into two input types.

## Architecture

```
DB CHECK (R92) ───────────┐
                          │
SDL @Size etc. (Phase 1) ─┼──> per-(InputType, field)
                          │      ConstraintSet (model carrier)
Jakarta on Layer 2        │             │
record components ────────┘             │
                                        ├──> rendered GraphQLInputObjectType
                                        │      .field.appliedDirective(...)
                                        │      (frontend introspection)
                                        │
                                        └──> programmatic ConstraintMapping
                                              (R12 §5 pre-step at runtime)
```

The model carrier is a single `ConstraintSet` per input field, holding
a list of constraints from all three sources. The set is order-stable
(each constraint records its source — `Source.CHECK | Source.SDL |
Source.JAKARTA` — for LSP-side display; not part of the wire format).
Both emit consumers walk the same set: the schema emitter rendering
each constraint as a `GraphQLAppliedDirective`, the runtime emitter
producing one `ConstraintMapping.type(...).field(...).constraint(...)`
chain per constraint.

## Conflict resolution: there is none

Each source contributes constraints; all of them apply at runtime and
all of them surface on the rendered schema. The runtime's
`validator.validate(...)` accumulates violations across every
constraint that fires. A field carrying both `@Range(1, 10)` from
SDL and a stricter DB CHECK on `rating <= 5` produces violations from
both sources when `rating = 7`, both producing typed errors via R12 §5.
The frontend introspecting the rendered schema sees both directives
and may dedupe / intersect at form-builder time if it cares — that's
frontend concern, not graphitron's.

This is *intentionally* simpler than detecting conflict and picking
a winner. The strictest constraint always wins because it's the one
that produces a violation; no merging logic is needed. The cost is
some redundancy at the wire layer (two directives expressing the same
or overlapping rule) which we accept.

## Adopted directive set

Adopt the subset of `graphql-java-extended-validation`'s directives
that map 1:1 to Jakarta annotations. The 1:1 mapping makes the
SDL→runtime translation mechanical; non-1:1 directives (e.g.
`@Email`, whose validator semantics vary across implementations) are
deferred. Initial set:

| SDL directive       | Jakarta annotation          | Notes                                   |
|---------------------|------------------------------|-----------------------------------------|
| `@Size(min, max)`   | `@Size(min, max)`            | Strings, collections, arrays            |
| `@Range(min, max)`  | composed: `@Min` + `@Max`    | Convenience over emitting two directives |
| `@Min(value)`       | `@Min(value)`                |                                         |
| `@Max(value)`       | `@Max(value)`                |                                         |
| `@NotEmpty`         | `@NotEmpty`                  |                                         |
| `@NotBlank`         | `@NotBlank`                  | Strings only                            |
| `@Pattern(regexp)`  | `@Pattern(regexp)`           |                                         |
| `@PositiveOrZero`   | `@PositiveOrZero`            |                                         |
| `@NegativeOrZero`   | `@NegativeOrZero`            |                                         |
| `@AssertTrue`       | `@AssertTrue`                | Booleans                                |
| `@AssertFalse`      | `@AssertFalse`               |                                         |

Deferred (case-by-case as production schemas surface a need):
`@Email`, `@Past`, `@Future`, `@Digits`, custom validators.

The directive declarations land in
`graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
alongside the existing graphitron directives, scoped to
`INPUT_FIELD_DEFINITION`.

## Layer 2 Jakarta-annotation flow

When R94's classifier produces a `ParamBinding.Constructed` from
graphitron's input components into a consumer record's ctor params,
the classifier walks the consumer record's components and surfaces
each Jakarta annotation onto the corresponding SDL input field's
`ConstraintSet`. This is *the rule*: a consumer record participating
in a Layer 2 binding contributes its annotations to the SDL input
field's constraint set, period. There's no per-consumer override and
no opt-out.

The implication for schema authors: if you want different validation
for two consumers of the same SDL input type, *declare two SDL input
types*. R94's seam cleanly supports this — each input type produces
its own emitted graphitron record, and each has its own
`ConstraintSet`. Reuse-with-divergence is what the SDL author gives
up; they reuse without divergence, or they split.

## What R92 phase 3 inherits

R92 phase 3 is the producer of the DB-CHECK source. Today its plan
emits programmatic `ConstraintMapping` directly. R98's restructuring
inserts the merge step: R92 phase 3 contributes to the
per-(InputType, field) ConstraintSet rather than emitting
`ConstraintMapping` directly. The runtime emit fans out from the
ConstraintSet (one path producing `ConstraintMapping`, one path
producing rendered SDL directives). R92 phase 3's existing plan body
should reference R98 once R98 is in Spec; the change to R92 is small
(swap the direct emit for a contribution-to-ConstraintSet).

## Phasing

Three phases, each independently shippable.

### Phase 1: SDL validation directives (parse + classify)

- Declare the curated directive set in `directives.graphqls`.
- Classifier reads applied directives on each SDL input field at
  classify time, producing a `Constraint` per directive application
  with `Source.SDL`.
- New model carrier: `record ConstraintSet(List<Constraint>)`,
  attached per-(InputRecordShape, InputComponent) — a sibling to
  R94's `InputComponent`.
- Sealed `Constraint` taxonomy mirroring the adopted directive set
  (`Constraint.Size`, `Constraint.Range`, `Constraint.Min`, ...,
  each carrying its parameters and its `Source` enum value).
- Pipeline-tier coverage: SDL with `@Size` / `@Range` / etc. → the
  classified `InputType` carries the matching `Constraint` arms.
- No emit changes yet.

Acceptance: applied directives are parsed and classified into a
typed `Constraint` per arm; round-trip property-based tests confirm
parse correctness.

### Phase 2: emit on rendered schema and runtime ConstraintMapping

- Schema-class emitter
  (`InputTypeGenerator` + `<InputName>InputType` emit) walks each
  input field's `ConstraintSet` and adds
  `withAppliedDirective(GraphQLAppliedDirective.newDirective().name("Range")
  .argument(...))` calls per `Constraint`.
- Runtime emitter (`GeneratedConstraintMappingGenerator` from R92,
  if it has shipped; otherwise its planned location) walks the same
  `ConstraintSet` and produces one
  `mapping.type(InputRecord.class).field(componentName).constraint(...)`
  chain per `Constraint`. The R92-derived constraints already
  contribute to the same set (after R92 phase 3 is restructured per
  the *inheritance* note above).
- New `@LoadBearingClassifierCheck`: every `Constraint` arm has a
  matching emit case in both consumers (rendered + runtime).
- Pipeline-tier: SDL with `@Size` → emitted `InputType` registration
  carries the directive application AND the runtime
  `ConstraintMapping` carries the corresponding constraint.
- Execution-tier: a sakila mutation with an SDL-declared `@Range`
  on its input field — invalid input surfaces as a typed
  `ConstraintViolation` via R12 §5; valid input passes through
  unchanged.

Acceptance: end-to-end SDL `@Range` validation works; introspection
returns the directive on the rendered field.

### Phase 3: Layer 2 Jakarta annotation flow

- R94's classifier extension: when producing a
  `ParamBinding.Constructed`, walk the consumer record's ctor params,
  read each parameter's Jakarta annotations, and surface each as a
  `Constraint` with `Source.JAKARTA` on the SDL input field's
  `ConstraintSet`.
- Mapping table: each Jakarta annotation type maps to its
  `Constraint` sealed-arm equivalent (mirroring the adopted directive
  set's reverse direction). Annotations outside the adopted set are
  surfaced as `Constraint.UnsupportedJakarta(annotationType)` with a
  build warning recommending the schema author either declare the
  equivalent on the SDL input or remove the unsupported annotation;
  no runtime behavior change beyond the warning.
- Pipeline-tier: SDL input + service signature with a `Constructed`
  parameter whose ctor carries `@Min(1)` → the SDL input field's
  `ConstraintSet` includes a `Constraint.Min(1, Source.JAKARTA)`,
  the rendered schema applies `@Min(1)`, and the runtime validator
  enforces it.
- Execution-tier: a sakila fixture exercising the
  consumer-record-Jakarta path end-to-end.

Acceptance: a Jakarta annotation on a Layer 2 consumer record
component "infects" the SDL input field's directives and runtime
validation; the rendered schema shows it.

## Out of scope (deferred or separate roadmap items)

- **Conflict detection between sources.** Both apply, the strictest
  wins at runtime by definition (any failing constraint produces a
  violation). No merger logic.
- **Source attribution on the wire.** Whether a directive came from
  SDL, CHECK, or Jakarta is not part of the rendered directive
  application. LSP can surface the source on hover / IDE tooltip
  (separate, smaller item).
- **Per-consumer override.** One `ConstraintSet` per input type, full
  stop. Reuse-with-divergence is the schema author's problem; they
  declare two input types.
- **`@Email`, `@Past`, `@Future`, custom validators.** Case-by-case
  as production schemas surface a need.
- **Surfacing the union on the legacy generator's rendered schema.**
  The legacy generator is out of scope for AI work
  (per `CLAUDE.md`); this item targets the rewrite only.
- **Frontend tooling.** What the frontend does with the rendered
  directives (form-builder, validation UI, etc.) is consumer concern.
  We render the directives; consumers introspect.

## Tests

- **Unit-tier (Phase 1):** parse-result tests over each adopted
  directive — each SDL applied directive produces the expected
  `Constraint` arm with the expected parameters.
- **Pipeline-tier (Phases 2-3, primary signal):** SDL → classified
  `ConstraintSet` → emitted `InputType` registration with directive
  applications + emitted `ConstraintMapping` chain. One pipeline test
  per source (SDL-only, CHECK-only, Jakarta-only) plus one combined.
- **Compilation-tier (Phase 2-3):** sakila compile picks up the new
  directive declarations and the emitted `ConstraintMapping` calls.
- **Execution-tier (Phase 2):** invalid input via SDL `@Range`
  surfaces as a typed `ConstraintViolation` end-to-end.
- **Execution-tier (Phase 3):** invalid input via Jakarta-on-Layer-2
  surfaces as a typed `ConstraintViolation` with the same wire shape
  as the SDL-source path.
- **Introspection-tier (new):** a query against the rendered schema's
  introspection endpoint returns the expected directive applications
  per input field. Pins the frontend contract.

## Risk

- **Directive-name collisions** with existing
  `graphql-java-extended-validation` users on the same schema.
  Mitigation: namespace check at directive registration; if a consumer
  already declares `@Size` for a different purpose, surface a build
  error naming both.
- **Bean Validation provider drift.** The Jakarta spec is stable, but
  Hibernate Validator's interpretation of `@Pattern` etc. may differ
  subtly from other providers. Mitigation: pin Hibernate Validator
  9.0.1 (already done); test the directive→annotation mapping against
  that version specifically.
- **Layer 2 Jakarta inflow surprises schema authors** — a service-side
  annotation appearing on the rendered SDL input is a non-obvious
  flow. Mitigation: LSP hover surfaces the source per constraint
  (`Constraint.Source` enum is in the model for this reason); doc the
  rule prominently in the `@service` documentation.
