---
id: R299
title: "Intention classification dimension: assert mutation polarity on top of R281"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-12
last-updated: 2026-06-12
---

# Intention classification dimension: assert mutation polarity on top of R281

R281 settled two asserted classification dimensions, `producer` (the pipeline over `{Query, Service,
Dml}`, empty meaning inline-correlate) and `mapping` (`Table` / `TableConnection` / `Column` / `Record`
/ `Field`). Neither axis carries the field's *operation kind*, what the produced value semantically *is*
(a plain read, a lookup, a count, an insert, a service mutation). Today that information is smuggled
into leaf identity (the `QueryService*` vs `MutationService*` prefix, `LookupTableField`,
`MutationBulkDmlRecordField`) and into directives (`@mutation(typeName:)`, `@lookupKey`). When R290
collapses the leaf cross-product into dimensional slots, the operation kind loses its home unless a
third axis exists to receive it. This item introduces that axis, `intention`, and lands its narrowest
useful slice, mutation polarity, on top of R281's corpus and `LeafTupleAdapter` while the leaves are
still intact and the polarity is still reconstructable from leaf identity.

## Direction, not contract

This item realizes one stage of the R222 dimensional pivot (`dimensional-model-pivot`); the umbrella's
"Direction, not contract" caveat applies. The wider intention vocabulary, read intents (`Fetch`,
`Lookup`, `Count`, `Facet`), DML intents (`Insert`, `Update`, `Delete`, `Upsert`), the `Inherited.Query`
/ `Inherited.Service` placement split, and finer service intents (`LookupService` divined from method
signature), was sounded out during this design conversation and is recorded in the extension path
below. None of it ships here. R299 introduces the axis and the two values whose classification is
certain and reconstructable today, and nothing more.

## Why service polarity is the right first slice

The intention axis is asserted only where it is not already derivable from the producer step. For
polarity that means `Service` alone: a `Dml` step always mutates and a `Query` or inline step never
does, so their polarity is a function of the producer and needs no separate assertion, but a `Service`
swings by parent context (non-mutating under a `Query` root, mutating under a `Mutation` root), so its
polarity is information the producer step does not carry. R299 therefore inhabits the intention axis on
the service-backed leaves only, with a closed two-value set the adapter reconstructs from leaf identity
without inference. This is the dimensional model's derive-don't-assert stance, not a half-populated
axis: polarity elsewhere is derived from the producer, not stored as an absent value.

That the service values stay *coarse* (polarity only, no finer read/write kind) is a separate,
observability-bound fact: graphitron generates the SQL for `Query` / `Dml` and could in principle name
fine kinds there, but a `Service` is opaque user code, and the most graphitron can both know and must
know about it is whether it mutates.

The two values are spelled `QueryService` and `MutationService`, naming the parent context that
determines the polarity. The names are explicit about the cause; the prose defines them as polarity,
and as the *source* of derivations that later slices, not R299, will realize:

- `QueryService`, non-mutating. A later slice (R290 onward) will give it no transaction boundary and a
  validation rule rejecting any mutation in this context.
- `MutationService`, may mutate. A later slice will derive a transaction boundary from it.

Transaction handling and mutation-permission are *derivations* of this single value rather than separate
slots, but they are realized only when a consumer materializes intention. R299 records the value; it
enforces nothing.

## What is

`DimensionTuple` carries exactly `(producer, mapping)`. `ProducerStep` is `{Query, Service, Dml}`;
inline-correlate is the empty producer (`inline(...)`). `LeafTupleAdapter.toTuple` is an exhaustive
switch over the sealed `OutputField` hierarchy mapping each leaf to its tuple; the `@classified` corpus
asserts that tuple per fixture, and `VariantCoverageTest` guarantees every leaf is demonstrated. The
service polarity is present in the model only as a leaf-name distinction (`QueryServiceTableField`,
`QueryServiceRecordField`, `ChildField.ServiceTableField`, `ChildField.ServiceRecordField` are
non-mutating; `MutationServiceTableField`, `MutationServiceRecordField` are mutating) with no asserted
axis recording it.

## What's to be

**Documentation first.** Extend the `Field Classification` section of `code-generation-triggers.adoc`
with the `intention` axis: its purpose (operation kind, the third dimension beside producer and
mapping), the two values `QueryService` / `MutationService` defined as polarity, the derive-don't-assert
rationale for asserting it on service leaves only, and the observability rationale for starting coarse.
The derivations it *implies* for later slices (transaction boundary, mutation-permission validation) are
described as future work, not as rules R299 enforces, per the "documentation names only live tests/code"
discipline. Authoring the vocabulary first is the point of this slice; reading it back is how we confirm
the intention language flows before any code adopts it. The pre-pivot banner already added at the head
of `Classification Vocabulary` stays; this extends the canonical dimensional section.

**Then the corpus and adapter.** `DimensionTuple` gains an `intention` coordinate, populated only for
the leaves where polarity is not derivable from the producer (the service-backed leaves); elsewhere it
is derived from the producer step and not carried. `LeafTupleAdapter` yields `QueryService` for the
non-mutating service-backed leaves (`QueryServiceTableField`, `QueryServiceRecordField`,
`ChildField.ServiceTableField`, `ChildField.ServiceRecordField`) and `MutationService` for the mutating
ones (`MutationServiceTableField`, `MutationServiceRecordField`). The `@classified` directive gains a
flat `intention: Intention` enum argument (a single typed enum, not a nested wrapper), mirrored by an
`Intention` enum added to `ClassifiedDsl.PRELUDE`; the service-backed fixtures set it. All other corpus
assertions are unchanged. No generator, validator, or model change: intention is reconstructed from leaf
identity exactly as `(producer, mapping)` already is, so this slice is corpus-and-docs only.

## Invariants pinned here

- **Mutation lives only at context-owning steps.** A `MutationService` is a standalone, context-owning
  service. (The corollary that inheritance is read-only, no inherited mutation, is recorded for the
  extension path but not exercised, since no `Inherited.*` step exists yet.)
- **`QueryService` never mutates.** This is the contract the value names; the validation rule that
  *enforces* it is later-slice work, not part of R299.
- **Intention is asserted only where the producer does not already fix it.** Polarity for `Query` /
  `Dml` / inline leaves is derived from the producer step, so it is not carried; only `Service`
  polarity, which the step does not determine, is asserted. Absence means "derived from the producer
  elsewhere," not an unclassified domain state.

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
- a live guard mirroring R281's `everyDimensionValueIsExercised` pins that both `QueryService` and
  `MutationService` are exercised by at least one fixture each, so the new values cannot sit
  present-but-unasserted;
- the `Intention` enum in `ClassifiedDsl.PRELUDE` is mirrored against the adapter's value set, the
  analog of R281's leaf-mirror guard;
- the service-backed `@classified` fixtures assert their polarity, `VariantCoverageTest` stays green,
  and the doc renders.

There is no pipeline or execution change to gate, because there is no emit change.

## Extension path (out of scope)

Recorded so the axis grows additively, none of this ships in R299:

- Read intents on `Query` / `Inherited.Query`: `{Fetch, Lookup, Count, Facet}`, with `Filter` folded
  into `Fetch`-with-conditions rather than a peer value.
- DML intents on `Dml`: `{Insert, Update, Delete, Upsert}`.
- Finer service intents: `LookupService` divined from method signature (the batch/positional N x M
  contract), inferred not declared, with ambiguous signatures rejected rather than guessed.
- The `Inherited.Query` / `Inherited.Service` placement split and the inheritance-is-read-only
  invariant, which materialize when the producer step model gains inline placements (R290 territory).

## Non-goals

- No model materialization of intention onto the field (that is R290's slot work).
- No `LeafTupleAdapter` deletion, no new `ProducerStep` values, no `Inherited.*` steps.
- No change to generated output.
