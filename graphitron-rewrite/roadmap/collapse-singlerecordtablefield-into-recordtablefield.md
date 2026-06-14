---
id: R305
title: Collapse SingleRecordTableField into RecordTableField (derive the DataLoader-skip from single-source cardinality)
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Collapse SingleRecordTableField into RecordTableField (derive the DataLoader-skip from single-source cardinality)

Split out of **R290** (`datafetcher-field-dimensional-slots`). R290 retired one of its two planned
leaves (`ConstructorField`) and landed the slot materialisation + the re-fetch derivation, but
deferred the second: collapsing `ChildField.SingleRecordTableField` into `ChildField.RecordTableField`.
R290's appendix inventory of **47** live leaves is the post-collapse target; with this item still open
the live leaf set is **48**.

## The missing concept: source-arrival cardinality

R290 framed the DataLoader-skip as "single-source cardinality" without a precise name; this is that
name. **Source-arrival cardinality** is how many source objects the data fetcher is invoked against.
graphql-java completes a list parent by iterating it and invoking the child fetcher once per element,
each seeing a single `env.getSource()`. So a source that arrives singly needs no batching (the inline
in-hand read is correct and optimal), while a source that can arrive in plurality needs a DataLoader or
it incurs an N+1. `SingleRecordTableField` is exactly the provably-single-arrival case;
`RecordTableField` is the default many-arrival case. The DataLoader-skip is not an incidental
optimisation, it is the correct consequence of single arrival. Naming the axis is what lets the collapse
keep one merged leaf whose emit mechanism is *derived* from arrival rather than switched on leaf
identity.

This axis must not be confused with the existing `SourceKey.Cardinality {ONE, MANY}`, which despite its
name is **value multiplicity** (one vs many target rows per key), set everywhere from
`returnType.wrapper().isList()` and read to choose source-binding shape, null-vs-empty checks, scatter,
and return wrapping. It says nothing about the source's arrival. This item renames it to
`SourceKey.ValueCardinality` (mechanical churn across the `.cardinality()` / `Cardinality.ONE|MANY` call
sites; it stays an `enum`, both arms being payload-free) so the two axes stop reading as the same thing,
and so "single-source cardinality off the `SourceKey` slot" stops sounding like it means the existing
slot.

## Why this is not a mechanical leaf merge

R290 framed the collapse as "the single-source-object DataLoader-skip becomes a derived detail
(computed from single-source cardinality off the slot)." On implementation the two leaves turned out
to use **different emit mechanisms**, not one mechanism with a skip flag:

- `SingleRecordTableField` is an **inline-bound** data field. `FetcherEmitter.bind` /
  `buildSingleRecordTableFetcherValue` (Wrap.Record and Wrap.TableRecord arms, ~340 lines) emit a
  DataFetcher that reads the producer's in-hand record(s) off `env.getSource()` and runs a follow-up
  SELECT inline. No DataLoader, no rows-method, no `loaderRegistration` / `parentCorrelation`; it is
  `OrderingOwnedByProducer`, and it carries the R141 / R158 / R275 payload-carrier execution coverage
  (the delete-then-echo `fjernSakTagg` shapes).
- `RecordTableField` is a **DataLoader / method-backed** field: it registers a loader, has a generated
  rows-method, batches across parent keys, and implements `BatchKeyField`.

Collapsing means teaching `RecordTableField`'s emit a second in-hand-source mode (no loader) selected
by a derived single-source predicate, and threading that mode through the registration emitter
(`FetcherRegistrationsEmitter`), the `TypeFetcherGenerator` dispatch + `IMPLEMENTED_LEAVES` partition,
the `OrderingOwnedByProducer` marker, `RecordBindingResolver` / `ProducerBinding`, and the validator.
That is a fetcher-emit refactor with execution-tier risk; it also overlaps R222 Stage 5's permit
consolidation, so it earns its own gated item rather than riding R290's diff.

## Scope

This item is the **behaviour-preserving collapse only**. Every shape that classifies as
`SingleRecordTableField` today is provably single-arrival, so it derives the single-arrival verdict and
keeps the exact inline in-hand emit it has now; no generated output changes. The companion **R308**
(`service-list-payload-arrival`) carries the one behavioural change the arrival axis unlocks: a
list-arriving `@service` carrier today is accepted at the carrier (`checkServiceReturnMatchesPayload`
admits `List<Payload>`) yet its data field still classifies no-loader, an unbatched N+1; R308 derives
many-arrival for it and emits a DataLoader. That fix depends on this item's merged leaf and arrival
verdict, and is kept out of here so this item's corpus-byte-invariance proof stays clean (per R290's
discipline of separating behaviour-preserving reclassification from a deliberate behavioural change).

- The two construction sites in `FieldBuilder.classifyObjectReturnChildField` (the R178 DML-carrier arm
  and the R275 `@service`-carrier arm, both today building `new ChildField.SingleRecordTableField(...)`)
  reclassify to `RecordTableField`, with the single-source / in-hand-source case encoded so the emitter
  can select the no-loader mode. The `(carrier, intent, mapping)` tuple is unchanged
  (`Source`/`Fetch`/`Table`), so the R281/R299 corpus stays byte-identical for the reclassified field.
- `RecordTableField`'s emitter gains the in-hand-source (no-DataLoader) follow-up-SELECT mode, derived
  from single-source cardinality off the `SourceKey` slot rather than from leaf identity.
- `SingleRecordTableField` is deleted: the leaf record, its `ChildField.intent()` / `mapping()` switch
  arms, its `OrderingOwnedByProducer` membership, its `TypeFetcherGenerator` / `FetcherEmitter` /
  validator dispatch, and the `SingleRecordPayloadPipelineTest` arm asserting it has a dedicated
  dispatch arm. Live leaves 48 -> **47** (R290's appendix target).

## Acceptance

- **Corpus tier**: byte-identical. The `FilmPayload.film` (Source/Fetch/Table) coverage that
  classifies as `SingleRecordTableField` today reclassifies to `RecordTableField` with its tuple
  unchanged and stays green without edit. `everyGraphitronFieldLeafHasAKnownDispatchStatus` stays green
  with one fewer partition entry; R290's appendix updates 48 -> 47 in the same change.
- **Execution tier**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk, DIRECT +
  OUTCOME_SUCCESS envelopes, the delete-then-echo shapes) is preserved end-to-end against PostgreSQL
  via the existing `SingleRecordPayloadDmlTest` / `SingleRecordTableFieldServiceProducerExecutionTest`
  fixtures. This is the load-bearing gate: the collapse must not change emitted SQL or runtime results.
- **Pipeline / TypeSpec tier**: the reclassified field's emit-shape (no-loader inline follow-up SELECT
  for the single-source case, DataLoader path otherwise) is pinned by structural `TypeSpec` assertions.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Assert vs derive: arrival is a derived verdict, not a stored slot (lead hypothesis)

The governing test for any new axis is R222's "assert what nothing else carries; derive what another
axis or slot already forces." Arrival is computed by walking to the source's producing field, which is
the language of a derived facet, not an asserted one. R290 Slice 4 set the precedent for exactly this
family of fact: `OutputField.requiresReFetch()` is a single-home predicate derived from `intent x
mapping`, with `GraphitronSchemaValidator` as the mirror, not a stored slot. The lead design mirrors it:
a single derived predicate (working name `OutputField.sourceArrivesSingle()`, or a method on the merged
leaf) computed once from the producing-field walk, consumed by the emit fork, and mirrored by
`GraphitronSchemaValidator` so an emit/verdict disagreement fails at validate time.

A stored value (sealed or enum) is materialised **only if** the walk's result carries information its
inputs cannot reconstruct at the consumer; the reachability case below is the only candidate that could
force it. If materialised it does not get an enum by symmetry with the renamed `ValueCardinality`: the
single-arrival arm carries the in-hand read contract (today's `Reader.ResultRowWalk`) and the
many-arrival arm carries the `LoaderRegistration`, which are different payloads, so it would be a sealed
split or, better, the derivation that *selects between* the existing `Reader` / `LoaderRegistration`
shapes already on the leaf.

### Per-seat derivation (reachability)

The verdict is a property of the **field instance / SDL seat**, not of the target type (R279's
field-first, reachability-driven walk). A type reachable both as a single-cardinality payload carrier
and as a list-nested field then has two independent verdicts, one per seat, with no conflict. Keying it
on the type (or memoising per type during the walk) would let the two paths collide and let the safe
"default many" demote the carrier seat to a loader it did not need: correctness-safe but
optimisation-losing drift no test would catch. "Default many" applies only within a single seat whose
single arrival is not provable; over-batching is never a correctness bug, so the default direction is
sound. Whether R279's walk reaches each seat with the producing-field context in hand decides the
assert-vs-derive question above: if yes, the predicate is pure and need not be stored.

### The merged-leaf emit fork (the surface R290 deferred)

Post-collapse, `RecordTableField` is the home of two emit mechanisms: an in-hand-read arm
(single-arrival, no `LoaderRegistration`) and a batched rows-method arm (many-arrival, with one). The
load-bearing decision of this item is how `BatchKeyField` membership resolves: today
`SingleRecordTableField` is *structurally forbidden* from implementing it and `RecordTableField`
requires it, so a flat merged leaf that conditionally implements the capability is the "capability forks
on identity within one leaf" smell. Either the single-arrival arm routes through a non-batch-key emit
path and the merged leaf stops claiming `BatchKeyField` uniformly, or the divergence is real enough that
a sealed sub-variant under the merged leaf (single-arrival / batched) is the honest shape rather than a
flat slot. Decide against the actual `BatchKeyField` / `LoaderRegistration` / `Reader.ResultRowWalk`
shapes, not in the abstract. The unification's blast radius (registration emitter, dispatch partition,
`OrderingOwnedByProducer` marker) should also be sized against R222 Stage 5's permit consolidation,
which may subsume part of it.

## Open questions for Ready (consult principles-architect)

1. **Assert vs derive, settled:** does R279's field-first walk reach every payload-carrier seat with its
   producing-field wrapper in hand? If yes, arrival is a pure predicate (no stored value); if no, the
   verdict materialises on the field, and its shape (sealed split, not enum) follows.
2. **Merged-leaf shape:** flat `RecordTableField` with a non-uniform `BatchKeyField`, or a sealed
   sub-variant split (single-arrival / batched) under it. Consult the `principles-architect` subagent on
   the emit-mode shape during the Spec draft.

