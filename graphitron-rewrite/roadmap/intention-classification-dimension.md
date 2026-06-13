---
id: R299
title: Migrate the R281 corpus into the carrier x intent x mapping model
status: Ready
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-12
last-updated: 2026-06-13
---

# Migrate the R281 corpus into the carrier x intent x mapping model

R281 settled a two-axis corpus verdict, `(producer, mapping)`, reconstructed from each `OutputField`
leaf by the throwaway `LeafTupleAdapter`. The model design then moved on (see R222's **Field-side
dimensional model (refined 2026-06-13)**): the **producer dimension dissolves** and a field's
classification becomes `carrier x intent x mapping` plus a derived layer. This item moves the R281
corpus forward onto that model, while the leaves are still intact and every axis is still reconstructable
from leaf identity. It is the corpus-and-docs migration; **R290** is the sibling slice that materialises
the axes on the field itself and deletes the adapter, and it depends on this item so the corpus already
speaks the new model before the adapter goes.

## Direction, not contract

This realises R222's field-side refinement at the corpus layer; the umbrella's "Direction, not contract"
caveat applies. The full model is asserted (carrier, intent, mapping); the **derived layer**
(`FetchRelated` from join-path, re-fetch from intent x mapping, new-query from `@splitQuery`/limitations,
polarity from the intent family) is *computed, not asserted* in the corpus. The intent set is the
complete model; the classifier populates only what the current leaf set permits, so some intents stay
modeled-but-unpopulated (declared gaps, never silently absent).

## What is

`DimensionTuple` carries `(producer, mapping)`. `ProducerStep` is `{Query, Service, Dml}` (empty =
inline). `Mapping` is `{Table, TableConnection, Column, Record, Field}`. `LeafTupleAdapter.toTuple` is an
exhaustive switch over the sealed `OutputField` hierarchy; `@classified(producer:, mapping:)` asserts the
verdict per fixture; `VariantCoverageTest` guarantees every leaf is demonstrated and `everyDimensionValue\
IsExercised` that every enum value is hit.

## What's to be

**Documentation first.** Rewrite the `Field Classification` section of `code-generation-triggers.adoc`
to the new model: the three axes (`carrier` = `Query`/`Mutation`/`Source`; `intent`; `mapping` with
build-vs-consume), the derived layer, and the assert-vs-derive principle. The pre-pivot banner at the
head of `Classification Vocabulary` stays. Sounding out the vocabulary as prose is half the point of
doing this at the corpus layer first; reading it back is how we confirm it flows before the model
materialises it in R290.

**Then the corpus and adapter (reconstruction only, no field-model change).** `DimensionTuple` becomes
`(carrier, intent, mapping)`; `producer` retires from the verdict. `LeafTupleAdapter` reconstructs all
three from leaf identity:

- `carrier` from the leaf's parent-type category (`QueryField*` -> `Query`, `MutationField*` ->
  `Mutation`, `ChildField*` -> `Source`).
- `intent` from leaf identity + the `DmlKind` discriminator (`Fetch`/`Lookup`/`NodeResolve`/`Nesting`,
  `Insert`/`Upsert`/`Update`/`Delete`, `QueryService`/`MutationService`).
- `mapping` as today.

The `@classified` directive's args migrate from `(producer, mapping)` to `(carrier, intent, mapping)`;
`ClassifiedDsl.PRELUDE` gains `Carrier` and `Intent` enums (single typed enums, not nested wrappers),
each mirrored against the adapter's value set. The derived layer is *not* a `@classified` arg; if we want
it pinned, it rides a separate derived-facts assertion that recomputes `FetchRelated`/re-fetch/new-query
from the slots, never an asserted coordinate.

No generator, validator, or field-model change: the axes are reconstructed from leaf identity exactly as
`(producer, mapping)` already was, so this slice is corpus-and-docs only. Materialising the axes on the
field and deleting the adapter is R290.

## Invariants pinned here

- **Three asserted axes, derived layer computed.** The corpus asserts `(carrier, intent, mapping)` and
  nothing else; `FetchRelated`, re-fetch, new-query, and polarity are derivations, never asserted.
- **Carrier gates intent.** Write intents only on `Mutation`; `NodeResolve`/`EntityResolve` only on
  `Query`; `Nesting` only on `Source`. The adapter cannot emit an off-carrier intent.
- **Model complete, coverage partial.** The `Intent` enum is the full set; intents with no current leaf
  (`EntityResolve`, `Count`, `Facet`, `UpdateMatching`, `DeleteMatching`) stay in the enum as **declared
  gaps**, exercised-or-allowlisted (the `NO_CASE_REQUIRED` precedent), never silently absent or pruned.

## Sequencing and acceptance

R299 lands **before** R290 (`datafetcher-field-dimensional-slots`, which `depends-on` this item). R299
migrates the corpus vocabulary while `LeafTupleAdapter` can still reconstruct it; R290 then materialises
`(carrier, intent, mapping)` on the field, deletes the adapter, and must keep the corpus byte-identical.
So this slice sets R290's behaviour-preserving baseline.

Acceptance is the unit/corpus tier:

- the `LeafTupleAdapter` switch stays exhaustive over `OutputField` (compiler-enforced);
- a guard adapts `everyDimensionValueIsExercised` so every `Carrier` and `Intent` value is *either*
  exercised by a fixture *or* on an explicit known-gap list with a stated reason (the five gap intents),
  following `NO_CASE_REQUIRED`;
- `Carrier`/`Intent` in `ClassifiedDsl.PRELUDE` are mirrored against the adapter's value sets;
- the `@classified` fixtures assert `(carrier, intent, mapping)`, `VariantCoverageTest` stays green, and
  the doc renders.

No pipeline or execution change to gate; there is no emit change.

## Extension path (out of scope)

- Populate `Count`/`Facet` once the connection protocol roles (`totalCount`/facets) are classified
  leaves rather than generator-only emit (cracks the ConnectionType quarantine; separate item).
- Populate `EntityResolve` once Federation `_entities` is a classified leaf.
- Populate `UpdateMatching`/`DeleteMatching` once condition-matched writes are implemented.

## Non-goals

- No field-model materialisation of the axes (that is R290).
- No `LeafTupleAdapter` deletion, no leaf dissolution (`ConstructorField`), no `ChildField` -> `SourceField`
  rename (all R290).
- No derived-layer values asserted as corpus coordinates.
- No change to generated output.
