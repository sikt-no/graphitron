---
id: R299
title: "Intention classification dimension: assert operation kind on top of R281"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-12
last-updated: 2026-06-12
---

# Intention classification dimension: assert operation kind on top of R281

R281 settled two asserted classification dimensions, `producer` (the pipeline over `{Query, Service,
Dml}`, empty meaning inline-correlate) and `mapping` (`Table` / `TableConnection` / `Column` / `Record`
/ `Field`). Neither axis carries the field's *operation kind*, what the produced value semantically
*is*: a plain read, a lookup, an insert, an update, a non-mutating vs mutating service call. Today that
information is smuggled into leaf identity (`LookupTableField`, the `QueryService*` vs `MutationService*`
prefix), into the `DmlKind` discriminator the mutation fields carry, and into directives
(`@mutation(typeName:)`, `@lookupKey`). When R290 collapses the leaf cross-product into dimensional
slots, the operation kind loses its home unless a third axis exists to receive it. This item introduces
that axis, `intention`, and populates it across the `Query`, `Dml`, and `Service` producers on top of
R281's corpus and `LeafTupleAdapter`, while the leaves are still intact and the operation kind is still
reconstructable from leaf identity and the `DmlKind` slot.

## Direction, not contract

This item realizes one stage of the R222 dimensional pivot (`dimensional-model-pivot`); the umbrella's
"Direction, not contract" caveat applies, with a sharpened reading: the `Intention` *model* shipped here
is complete, the sealed hierarchy covers the full operation-kind vocabulary, but the current classifier
can only *populate* the subset reconstructable from the leaf set. That gap is deliberate; the model
leads the classifier. `Count` and `Facet` are modeled but unpopulated, no `OutputField` leaf exists for
them yet (connection `totalCount`/facets are generator-only emit, not classified leaves), so they stay
in the type as declared coverage gaps rather than being pruned from it. `LookupService` and the
`Inherited.Query` / `Inherited.Service` placement split are the parts genuinely left *out of the model*
for now, recorded on the extension path.

## The Intention model, and what the classifier populates

`intention` is the *operation kind* a producer step performs, modeled as a sealed hierarchy whose
sub-interface is fixed by the producer-step family, so the producer structurally constrains which
intentions are legal:

- **`ReadIntent`** (for `Query` producers and inline catalog reads): `Fetch` (the default catalog read),
  `Lookup` (the `@lookupKey` N x M case), `Count`, `Facet`.
- **`WriteIntent`** (for `Dml` producers): `Insert`, `Update`, `Delete`, `Upsert`, the existing
  `DmlKind`.
- **`ServiceIntent`** / polarity (for `Service` producers): `QueryService` (non-mutating),
  `MutationService` (may mutate).

The sealed type covers the model in full, including where the current classifier cannot complete it:

- `Count` and `Facet` are real read operations (connection `totalCount`/facets) with no classified
  `OutputField` leaf yet, so the adapter cannot produce them. They remain in the model, unpopulated, as
  declared known gaps, not pruned from the type.
- `LookupService` is *not* a model value. A lookup-service classifies as `QueryService`; its lookup
  shape is a signature-derived refinement deferred to a later capability (see the extension path), not a
  distinct intention.

What the classifier *populates* today, reconstructed from leaf identity and the `DmlKind` slot, is
`Fetch` / `Lookup` (`Fetch` the read floor that absorbs plain column/record projections and structural
passthroughs, so the coordinate is total over the leaves it classifies), `Insert` / `Update` / `Delete`
/ `Upsert` (off `DmlKind`), and `QueryService` / `MutationService`. The service polarity stays coarse for
an observability reason: graphitron generates the SQL for `Query` / `Dml` and could name fine kinds
there, but a `Service` is opaque user code, so the most it can both know and must know is whether it
mutates.

The service values are spelled `QueryService` / `MutationService` to name the parent context that fixes
the polarity (a service under a `Query` root is non-mutating; under a `Mutation` root it may mutate).
They are the *source* of derivations, transaction handling and a validation rule rejecting mutation in a
`QueryService`, that later slices realize; R299 records the value and enforces nothing.

## What is

`DimensionTuple` carries exactly `(producer, mapping)`. `ProducerStep` is `{Query, Service, Dml}`;
inline-correlate is the empty producer (`inline(...)`). `LeafTupleAdapter.toTuple` is an exhaustive
switch over the sealed `OutputField` hierarchy mapping each leaf to its tuple; the `@classified` corpus
asserts that tuple per fixture, and `VariantCoverageTest` guarantees every leaf is demonstrated.
Operation kind is present in the model only implicitly, in leaf names (`LookupTableField`,
`QueryService*` / `MutationService*`), in the `DmlKind` discriminator, and in directives, with no
asserted axis recording it.

## What's to be

**Documentation first.** Extend the `Field Classification` section of `code-generation-triggers.adoc`
with the `intention` axis: its purpose (operation kind, the third dimension beside producer and
mapping), the complete sealed value set, and the rule that the legal sub-interface is fixed by the
producer-step family (`ReadIntent` for `Query`/inline, `WriteIntent` for `Dml`, polarity for `Service`).
Mark the coverage gaps explicitly (`Count`/`Facet` modeled but not yet reconstructable; `LookupService`
not a model value) and note that the service polarity's derivations (transaction handling,
mutation-permission validation) are future work, not rules R299 enforces, per the "documentation names
only live tests/code" discipline. Authoring the vocabulary first is the point of
this slice; reading it back is how we confirm the intention language flows before any code adopts it.
The pre-pivot banner already added at the head of `Classification Vocabulary` stays; this extends the
canonical dimensional section.

**Then the corpus and adapter.** `DimensionTuple` gains an `intention` coordinate typed as the complete
sealed `Intention` hierarchy above. `LeafTupleAdapter` populates it for every leaf from the
reconstructable subset: a `ReadIntent` (`Fetch`, or `Lookup` for the `@lookupKey` leaves) for catalog
reads whether `[Query]` or inline, the leaf's `DmlKind` as a `WriteIntent` for `Dml` leaves, and
`QueryService` / `MutationService` for service leaves, with `Fetch` the floor for plain projections and
passthroughs. `Count` and `Facet` exist in the type but are produced by no leaf. The `@classified`
directive gains an `intention` argument over a single typed `Intention` enum surface (not a nested
wrapper), mirrored against the model in `ClassifiedDsl.PRELUDE`; every fixture sets it. No generator,
validator, or field-model change: intention is reconstructed from leaf identity and the `DmlKind` slot
exactly as `(producer, mapping)` already is, so this slice is corpus-and-docs only.

## Invariants pinned here

- **The `Intention` model is complete; the classifier's coverage is a partial projection.** The sealed
  `Intention` hierarchy covers the full operation-kind vocabulary, and the producer-step family fixes
  the legal sub-interface (`ReadIntent` for `Query`/inline, `WriteIntent` for `Dml`, polarity for
  `Service`). The classifier populates only what the leaf set permits; values it cannot yet produce
  (`Count`, `Facet`) stay in the model as declared gaps, never silently absent or pruned.
- **The populated coordinate is total over leaves.** Every leaf the adapter classifies carries a value;
  `Fetch` is the read floor for plain projections and passthroughs, so no classified leaf lacks an
  intention.
- **Mutation lives only at context-owning steps.** `WriteIntent` and `MutationService` are the only
  mutating intentions; every `ReadIntent` and `QueryService` is non-mutating. (Inheritance-is-read-only
  is recorded for the extension path, not exercised, since no `Inherited.*` step exists yet.)
- **`QueryService` never mutates.** The contract the value names; the enforcing validation rule is
  later-slice work, not part of R299.

## Sequencing and acceptance

R299 lands **before** R290 (`datafetcher-field-dimensional-slots`). The order matters: R299 enriches the
R281 corpus with the intention coordinate while `LeafTupleAdapter` can still reconstruct it from leaf
names; R290 then collapses the leaves into slots and must keep the now-three-coordinate corpus
byte-identical, so the intention coordinate becomes part of R290's acceptance baseline rather than
something R290 has to invent mid-collapse. R290's `depends-on` is updated to include this item. R290's
body still describes a two-coordinate `(producer, mapping)` field and the deletion of
`LeafTupleAdapter`; when R290 is picked up its text must be updated to expose `(producer, mapping,
intention)` and to *materialize* intention as a slot rather than drop it when the adapter is deleted.

Acceptance is the unit/corpus tier:

- model completeness is structural: the sealed `Intention` hierarchy is exhaustive by construction, and
  the `LeafTupleAdapter` switch stays exhaustive over `OutputField` (both compiler-enforced);
- a guard adapts R281's `everyDimensionValueIsExercised` so every `Intention` value is *either* exercised
  by a fixture *or* on an explicit known-gap list with a stated reason (`Count`, `Facet`: no classified
  leaf), following the `NO_CASE_REQUIRED` precedent in `VariantCoverageTest`, a gap is declared, never
  silent;
- the `Intention` surface in `ClassifiedDsl.PRELUDE` is mirrored against the model;
- the `@classified` fixtures assert their intention, `VariantCoverageTest` stays green, and the doc
  renders.

There is no pipeline or execution change to gate, because there is no emit change.

## Extension path (out of scope)

Recorded so the axis grows additively, none of this ships in R299:

- Populate `Count` and `Facet` (already in the model) once connection `totalCount`/facets become
  classified `OutputField` leaves rather than generator-only emit.
- `LookupService`, a signature-derived refinement of `QueryService` (the batch/positional N x M
  contract), inferred not declared, ambiguous signatures rejected; added to the model when that
  inference exists.
- The `Inherited.Query` / `Inherited.Service` placement split and the inheritance-is-read-only
  invariant, which materialize when the producer step model gains inline placements (R290 territory).
- `Filter` is intentionally *not* a peer intent: a filtered read is `Fetch`-with-conditions, conditions
  ride a separate query-shaping slot rather than an intention value.

## Non-goals

- No model materialization of intention onto the field (that is R290's slot work).
- No `LeafTupleAdapter` deletion, no new `ProducerStep` values, no `Inherited.*` steps.
- No change to generated output.
