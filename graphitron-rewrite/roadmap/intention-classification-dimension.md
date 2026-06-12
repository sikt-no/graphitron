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
"Direction, not contract" caveat applies. R299 lands the intention values reconstructable from the leaf
set today and defers the rest. Specifically deferred: the `Count` and `Facet` read intents (no
`OutputField` leaf exists for them, connection `totalCount`/facets are emitted by the connection
generators, not classified as leaves), the `LookupService` finer service intent (divined from method
signature, see the extension path), and the `Inherited.Query` / `Inherited.Service` placement split. The
full producer-step intention model sounded out in design is recorded in the extension path; only its
reconstructable core ships here.

## What ships now: operation kind across the three producers

`intention` is the *operation kind* a producer step performs. It is a total coordinate, every leaf
carries a value, drawn from a closed `Intention` enum whose legal subset is fixed by the producer-step
family:

- **`Query` producers, and inline catalog reads,** carry a *read intent*: `Fetch` (the default catalog
  read) or `Lookup` (the `@lookupKey` N x M leaves: `LookupTableField`, `SplitLookupTableField`,
  `RecordLookupTableField`, `QueryLookupTableField`). `Fetch` is the read floor and absorbs plain
  column/record projections and structural passthroughs, so the coordinate stays total rather than
  optional. `Count` and `Facet` are designed read intents but have no leaf to classify today, so they
  are deferred, not enumerated.
- **`Dml` producers** carry a *write intent*, the existing `DmlKind`: `Insert` / `Update` / `Delete` /
  `Upsert`, read straight off the mutation field's `DmlKind` discriminator (which already drives
  per-verb emit), so no new inference is needed.
- **`Service` producers** carry *polarity*: `QueryService` (non-mutating) or `MutationService` (may
  mutate). This is the deliberately coarse pair; `LookupService` is dropped from this slice. The
  coarseness is observability-bound: graphitron generates the SQL for `Query` / `Dml` and could name
  fine kinds there, but a `Service` is opaque user code, so the most graphitron can both know and must
  know is whether it mutates.

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
mapping), the closed value set, and the rule that the legal value is fixed by the producer-step family
(read intent for `Query`/inline, `DmlKind` for `Dml`, polarity for `Service`). Note the two deferrals
(`Count`/`Facet` lack a leaf; `LookupService` dropped) and that the service polarity's derivations
(transaction handling, mutation-permission validation) are future work, not rules R299 enforces, per the
"documentation names only live tests/code" discipline. Authoring the vocabulary first is the point of
this slice; reading it back is how we confirm the intention language flows before any code adopts it.
The pre-pivot banner already added at the head of `Classification Vocabulary` stays; this extends the
canonical dimensional section.

**Then the corpus and adapter.** `DimensionTuple` gains a total `intention` coordinate. `LeafTupleAdapter`
yields it for every leaf: a read intent (`Fetch`, or `Lookup` for the `@lookupKey` leaves) for catalog
reads whether `[Query]` or inline, the leaf's `DmlKind` (`Insert` / `Update` / `Delete` / `Upsert`) for
`Dml` leaves, and `QueryService` / `MutationService` for service leaves, with `Fetch` as the read floor
for plain projections and passthroughs. The `@classified` directive gains a flat `intention: Intention`
enum argument (a single typed enum, not a nested wrapper), mirrored by an `Intention` enum added to
`ClassifiedDsl.PRELUDE`; every fixture sets it. No generator, validator, or model change: intention is
reconstructed from leaf identity and the `DmlKind` slot exactly as `(producer, mapping)` already is, so
this slice is corpus-and-docs only.

## Invariants pinned here

- **Intention is total and fixed by the producer-step family.** Every leaf carries an operation kind
  from the closed `Intention` enum; the producer step determines the legal subset (read intents for
  `Query`/inline, `DmlKind` for `Dml`, polarity for `Service`). There is no absent/unclassified value,
  `Fetch` is the read floor.
- **Mutation lives only at context-owning steps.** `Dml` leaves and `MutationService` are the only
  mutating intentions; every read intent and `QueryService` is non-mutating. (The corollary that
  inheritance is read-only is recorded for the extension path but not exercised, since no `Inherited.*`
  step exists yet.)
- **`QueryService` never mutates.** This is the contract the value names; the validation rule that
  *enforces* it is later-slice work, not part of R299.

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

- the extended `LeafTupleAdapter` switch stays exhaustive (compiler-enforced over the sealed
  `OutputField`);
- a live guard mirroring R281's `everyDimensionValueIsExercised` pins that every enumerated `Intention`
  value (`Fetch`, `Lookup`, `Insert`, `Update`, `Delete`, `Upsert`, `QueryService`, `MutationService`)
  is exercised by at least one fixture, so no value sits present-but-unasserted;
- the `Intention` enum in `ClassifiedDsl.PRELUDE` is mirrored against the adapter's value set, the
  analog of R281's leaf-mirror guard;
- the `@classified` fixtures assert their intention, `VariantCoverageTest` stays green, and the doc
  renders.

There is no pipeline or execution change to gate, because there is no emit change.

## Extension path (out of scope)

Recorded so the axis grows additively, none of this ships in R299:

- `Count` and `Facet` read intents, once connection `totalCount`/facets are modeled as classified leaves
  rather than generator-only emit.
- `LookupService`, the finer non-mutating service intent, divined from method signature (the
  batch/positional N x M contract), inferred not declared, with ambiguous signatures rejected rather
  than guessed.
- The `Inherited.Query` / `Inherited.Service` placement split and the inheritance-is-read-only
  invariant, which materialize when the producer step model gains inline placements (R290 territory).
- `Filter` is intentionally *not* a peer intent: a filtered read is `Fetch`-with-conditions, conditions
  ride a separate query-shaping slot rather than an intention value.

## Non-goals

- No model materialization of intention onto the field (that is R290's slot work).
- No `LeafTupleAdapter` deletion, no new `ProducerStep` values, no `Inherited.*` steps.
- No change to generated output.
