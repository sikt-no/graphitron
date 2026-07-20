---
id: R503
title: "Mixed-source nested types: classify both edges, dispatch on source shape"
status: In Review
bucket: architecture
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-19
last-updated: 2026-07-20
---

# Mixed-source nested types: classify both edges, dispatch on source shape

A directiveless output type reached both as a nesting projection of a `@table` parent and as a
field of a class-backed `@service` result cannot exist today: the mix is a deterministic
validate-time rejection. This item makes the mix classify, validate, and emit correctly, because
the rejection blocks legitimate value-type reuse and because R501's signed-off `@pivot` design
(`roadmap/pivot-projection-directive.md`) assumes exactly this coexistence and cannot be
implemented without it.

## Audit: the original premise, corrected

The Backlog body claimed first-edge-wins classification (`registerNestingTypesIn` vs
`registerProducerBackedCarrier`, both `contains`-guarded) leaving a single-arm child read that
throws `ClassCastException` at run time on the losing edge's source shape, with breakage dependent
on walk order. Code audit refutes each part:

- **The two register functions cannot race.** Both gate on the same sealed
  `TypeBuilder.CarrierBinding` disjunction: `registerNestingTypesIn` requires the target to be a
  directiveless nesting target (`isDirectivelessNestingTarget`, which requires
  `carrierBinding(name) instanceof NotACarrier`), `registerProducerBackedCarrier` fires only when
  `carrierVerdict(name)` is non-null (which requires the binding NOT to be `NotACarrier`). For any
  single type name at most one precondition can hold, under any schema.
- **Verdicts are walk-order-independent.** Every classification input (`classifyType`,
  `carrierBinding`, `isDirectivelessNestingTarget`) is a pure function of the
  `RecordBindingResolver` fixed point computed in `TypeBuilder.prepareForWalk`, before the single
  walk starts. When a class-backed `@service` result carries a field of type `T`,
  `RecordBindingResolver.propagateResultChildren` binds `T`'s class on the result axis pre-walk;
  `classifyType(T)` then returns a class-backed `ResultType` in every walk order.
- **The mix is loud, not silent.** Because `classifyType(T)` is non-null,
  `isDirectivelessNestingTarget(T)` is false, and the `@table` parent's plain field returning `T`
  is deterministically rejected in `FieldBuilder.classifyChildFieldOnTableType` ("a `@table` parent
  cannot construct a record-backed child from its own row"), surfaced as a build-time
  `ValidationError` and pinned by `ConstructorFieldValidationTest`. As a second backstop,
  `TypeRegistry.register` demotes any incompatible repeat registration to `UnclassifiedType`
  rather than keeping the first write.

So the defect the item described does not exist. What does exist is the inverse problem: the
rejection makes a plain output type unusable as both a nesting projection and a POJO property, and
the emission seam beneath it has a latent hazard that becomes live the moment classification
admits the mix (`FetcherRegistrationsEmitter.emit` is keyed by type name only, its nested-type
pass silently overwriting a same-named `ResultType` body; `TypeFetcherGenerator.generate` can emit
duplicate same-named `TypeSpec`s).

## Problem, reframed

graphql-java wires one datafetcher per `(type, field)` coordinate, so a type reachable from a jOOQ
`Record` parent row and from a class-backed `@service` result needs either one read that serves
both source shapes or a build-time rejection. Today the answer is rejection, with a message
written for a different intent ("carries no producer directive to build it") that tells the author
to add a producer when their actual fix is renaming or splitting the type. R501 commits to the
opposite answer for its projection type: a plain, context-free output type "freely reusable in
every mode", including as a field of a class-backed `@service` result, served by a run-time
`source instanceof org.jooq.Record` dispatch over the classifier-proven shape set. R501's Model
section, however, describes this seam wrongly (it claims mixed consumption "registers the same
`NestingType` twice, an idempotent repeat"; per the audit the class-backed reach wins pre-walk and
the nesting edge is rejected), so its three-way coexistence execution case is unimplementable on
the current classifier. This item supplies the seam: classify both edges, reify the per-coordinate
shape set as a model fact, and emit the dispatch. It supersedes the "a shape no production schema
relies on" rationale recorded in `ConstructorFieldValidationTest`'s javadoc: with `@pivot`
projection types (and plain value types like a translations record) deliberately reused across
source shapes, production schemas now do rely on the mix.

## Design

**The reachable-source-shape set is a reified, classified fact.** The union "which source shapes
can reach this coordinate" is a genuine cross-edge model fact; leaving each consumer to
reconstruct it (the emitter from `NestingField` edges plus the type registry, the validator
independently) is the two-consumers-of-one-predicate drift the model bans. A post-walk
reconciliation pass in `GraphitronSchemaBuilder` computes, per `(type, field)` coordinate of every
dual-reached type, the set of source shapes proven to reach it, derived from the `NestingField`
edges collected off fields (the generic-`Record` shape) and the type's class-backed `ResultType`
classification (the accessor shape). The fact is exposed on `GraphitronSchema` (a keyed lookup,
e.g. `reachableSourceShapes(coordinate)`); the dispatch emitter, the validator, and the pipeline
tests all read it, none re-derive it. Single-reach coordinates carry a singleton set, and the
emitters' behaviour on a singleton is today's single-arm emission, byte for byte.

**Classification: the nesting verdict tolerates a result-axis class binding.** A directiveless SDL
object (no `@table` / `@error` / root / interface / union / enum / scalar, not a producer-backed
carrier, no binding rejection) whose only competing classification is a class-backed `ResultType`
(`JavaRecordType` or `PojoResultType.Backed`) remains a valid nesting target: the embedding edge
builds the `NestingField` exactly as today, with its children classified against the embedding
parent's table. The embedding edge does NOT register a `NestingType` for such a target (the
complementary guard to today's `contains` check, decided registry-free off the same pre-walk
bindings): the type's own visit registers the class-backed `ResultType`, deterministically, in
every walk order, avoiding `TypeRegistry.register`'s incompatible-repeat demote. The type-level
winner is thus deterministic and carries no nesting information; the nesting facts live edge-side
on the `NestingField`s, which is where the emitters already read them
(`FetcherRegistrationsEmitter.collectNestedTypes` walks fields, not the type registry).

**FieldBuilder: de-fuse the two axes in the record-backed-child rejection.** The rejection in
`classifyChildFieldOnTableType` currently fires on the type-level fact (`elementType instanceof
ResultType`), fusing "this type is class-backed for some other consumer" with "this child is
unbuildable from the parent row". The relaxation narrows it to the edge-level fact: for a
class-backed target that is structurally nesting-shaped, build the `NestingField` and let
per-child column resolution decide buildability. A child column that fails to resolve surfaces as
the normal nesting rejection, enriched with a note naming the producer that bound the type
class-backed (mirroring the accessor-gate near-miss note in `rejectDanglingTypeReferences`), so
the author who meant "return the produced value" still gets intent-level guidance. The
`JooqRecordCarrier` mix (a nesting target that is also a jOOQ-`Record`-backed `ResultType`,
`JooqRecordType` / `JooqTableRecordType`) stays rejected in v1: both arms would be `Record` reads
with independently derived read names, and unifying those is a separate, smaller follow-up once
this seam exists. That rejection re-lands as a validator rule over the reified shape set (see
Validation) rather than the current fused type-level check.

**Emission: merge per name, dispatch per coordinate.** `FetcherRegistrationsEmitter.emit` merges
the `ResultType` body and the nested-type body for a shared type name into one registration set
(replacing the silent name-keyed overwrite); `TypeFetcherGenerator` merges into one `TypeSpec` per
fetcher class name, with `nestedTypeOwnsFetchers` and its `seen` set consulting the merged view. A
coordinate whose reified shape set is the dual `{generic jOOQ Record, class-backed accessor}`
emits a run-time boundary dispatch composed from the two existing single-arm emissions, unchanged
inside their arms: the `source instanceof org.jooq.Record` arm is the existing by-name/typed
column read (`bindRaw`'s `ColumnField` form against the representative parent table), the other
arm is the existing `recordBackedAccessorRead`. Statement-form, meaningfully named binding,
Java 17-valid (pattern `instanceof` is Java 16+). Two implementation notes: a dual-shape
coordinate must always register its fetcher, even where the accessor arm alone would have been
elided as equivalent to graphql-java's default `PropertyDataFetcher` (the default fetcher cannot
read a jOOQ `Record` column); and a dual-shape coordinate whose accessor arm is env-dependent
(`isEnvDependentAccessorRead`) keeps the env-dependent registration form for the merged fetcher.

**Acceptances audit.** Relaxing the classifier's rejection relaxes a shape guarantee ("a `@table`
parent never carries a `ResultType`-classified nesting child") that emitter sites may consume
implicitly. The implementation commit audits every consumer of that guarantee (the
`FetcherRegistrationsEmitter` / `TypeFetcherGenerator` merge sites above are the two known ones;
the audit confirms there are no others) in the same commit, per the acceptances rule.

## Validation

Validator mirrors classifier, reading the same reified shape set the emitter dispatches on:

- A coordinate whose shape set is outside the dispatch emitter's supported combinations is
  rejected at validate time, naming the coordinate and the shapes. Supported in v1: any singleton,
  and `{generic Record, class-backed accessor}`. The `JooqRecordCarrier` + nesting mix is the
  first unsupported combination (this re-lands the narrowed remainder of today's fused rejection,
  now over the same fact the emitter reads, so emitter and validator cannot drift).
- Per-arm validation is otherwise unchanged and sufficient: the nesting arm's children validate
  column resolution against every embedding parent (including the existing
  `validateNestingParentCompat` shape-divergence pass), the accessor arm validates against the
  backing class (`resolveRecordAccessor`). Coexistence therefore requires the type to be fully
  readable in both shapes; a field readable in only one surfaces as the existing per-edge
  rejection, enriched as described under Design.
- No new `GraphitronField` leaf is introduced, so
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` does not cover the new
  branch; instead a coverage meta-test partitions shape-set combinations into
  supported-by-dispatch / rejected-by-validator and fails on any combination in neither set, so a
  future third shape cannot silently fall through the emitter.

## Tests

Per the test-tier discipline (behaviour pinned at the pipeline tier and above):

- **Pipeline tier.** A mixed schema (class-backed `@service` result carrying a property of type
  `T`, plus a `@table` parent embedding `T` as a plain field) classifies both edges: the embedding
  field is a `NestingField`, the carrying field a `RecordField`, `T` registers as the class-backed
  `ResultType`, and `reachableSourceShapes` for `T`'s coordinates is the two-shape union. Assert
  the same outcome with the schema arranged so the walk reaches the edges in both orders, pinning
  that the registry winner and the shape set are order-independent typed facts (not emitted-code
  assertions). Cover both the direct producer variant (`@service` returning `T` itself, the
  `ConstructorFieldValidationTest` shape) and the two-hop accessor-chain variant
  (`propagateResultChildren` binding `T` through a parent composite). A single-reach schema's
  emitted `TypeSpec` is byte-identical to today's.
- **Compilation tier.** A `graphitron-sakila-example` fixture with the mix compiles under
  `<release>17</release>`.
- **Execution tier (PostgreSQL).** A `graphitron-sakila-service` fixture: a `@service` returns a
  POJO carrying `T`, a `@table` parent nests the same `T`; querying through both paths returns
  correct values through the one registered fetcher per coordinate (the dispatch's two arms each
  exercised on a live request).
- **Validation tier.** Negative fixtures: a mixed-reach type with a child readable only via
  accessor (missing column on the embedding parent) rejects with the enriched message naming the
  binding producer; readable only as column (missing accessor on the backing class) rejects via
  the existing accessor path; the `JooqRecordCarrier` mix rejects via the new shape-set rule.
- **Cutover.** `ConstructorFieldValidationTest`'s fixture becomes valid under this change
  (`FilmDetails { rating }` nests off `film.rating` while also being `@service`-produced). The
  test reworks to a true-negative fixture (a child that is not column-resolvable), and the
  now-positive case migrates to the classified corpus as a worked example of mixed-source reach.

## Interaction with R501 (`@pivot`)

This item inverts the sequencing note in the original Backlog body: R503 lands first, R501's pivot
slots then extend a working seam. R501's Model and Emission-surface paragraphs describe this seam
in ways the audit refutes (the "registers the same `NestingType` twice, an idempotent repeat"
claim, and the assumption that a plain nesting edge coexists with a class-backed reach today), and
its three-way coexistence execution case depends on this item. Recommended follow-up, the user's
or a reviewer's call: reopen R501 (`Ready → Spec` is unguarded) to repoint its coexistence
mechanism at the reified shape set introduced here and set its `depends-on: [R503]`, so the
dependency is a front-matter fact rather than prose.

## Alternative considered and not taken

Keep the rejection as the contract (one type name, one runtime source shape), improve its message
to name the producer that grounded the type, pin the two-hop variant with a fixture, and amend
R501 to drop its class-backed coexistence cases (authors split the type into two names). Cheaper
and dispatch-free, but it reverses R501's signed-off reusability goal, reintroduces the
type-duplication papercut the generator exists to absorb, and still leaves the emission seam's
name-keyed overwrite as a live trap for the next item that admits any mixed reach.

## Out of scope

- The `JooqRecordCarrier` + nesting mix (rejected in v1; unifying the two `Record`-shaped read-name
  derivations is a follow-up).
- Pivot slot coordinates (R501 extends this seam; nothing pivot-specific lands here).
- Interface / union targets of mixed reach, input types, and mutations.
- Shape sets with more than the two supported members (the coverage meta-test forces an explicit
  decision when one appears).
