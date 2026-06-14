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
R290 delivered 48 live leaves (recorded in `changelog.md`, whose R290 entry notes "the appendix's 47 is
R305's post-collapse target"); 47 is that post-collapse target, and with this item still open the live
leaf set is **48**.

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
  dispatch arm. Live leaves 48 -> **47** (the post-collapse target R290 recorded in `changelog.md`).

## Acceptance

- **Corpus tier**: byte-identical. The `FilmPayload.film` (Source/Fetch/Table) coverage that
  classifies as `SingleRecordTableField` today reclassifies to `RecordTableField` with its tuple
  unchanged and stays green without edit. `everyGraphitronFieldLeafHasAKnownDispatchStatus` stays green
  with one fewer partition entry. The 48 -> 47 leaf-count drop is a documentation fact (no test pins the
  integer; `everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces the partition is exhaustive and
  disjoint, not a count) and is recorded in R305's own `changelog.md` entry on Done.
- **Execution tier**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk, DIRECT +
  OUTCOME_SUCCESS envelopes, the delete-then-echo shapes) is preserved end-to-end against PostgreSQL
  via the existing `SingleRecordPayloadDmlTest` / `SingleRecordTableFieldServiceProducerExecutionTest`
  fixtures. This is the load-bearing gate: the collapse must not change emitted SQL or runtime results.
- **Pipeline / TypeSpec tier**: the reclassified field's emit-shape (no-loader inline follow-up SELECT
  for the single-source case, DataLoader path otherwise) is pinned by structural `TypeSpec` assertions.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Assert vs derive: arrival is a derived predicate off the leaf's own loader shape (settled: derive)

The governing test for any new axis is R222's "assert what nothing else carries; derive what another
axis or slot already forces." Arrival is already forced by a slot the leaf holds. The single-arrival
shape is structurally `SourceKey.Reader.ResultRowWalk` with **no** `LoaderRegistration` (the inline
in-hand read); the many-arrival shape carries a `LoaderRegistration` (the batched rows-method). In main
today `Reader.ResultRowWalk` is constructed only at the three single-arrival sites (`FieldBuilder` lines
~4061 / ~4120 / ~4156) and is forbidden on the batched leaves by the `SingleRecordTableField` /
`SingleRecordIdField` compact constructors, while `RecordTableField` carries a `LoaderRegistration`
component and no `ResultRowWalk`. So arrival need not be walked to a producing field and need not be
stored as a fresh slot: it is the verdict already implied by which of the two payloads the leaf carries.

R290 Slice 4 set the precedent for exactly this family of fact: `OutputField.requiresReFetch()` is a
single-home predicate derived from `intent x mapping`, with `GraphitronSchemaValidator` as the mirror,
not a stored slot. This item mirrors it: a single derived predicate (working name
`OutputField.sourceArrivesSingle()`, or a method on the merged leaf) reads the leaf's own loader shape
(`LoaderRegistration` absence ⟺ single arrival), is consumed by the emit fork, and is mirrored by
`GraphitronSchemaValidator` so an emit/verdict disagreement fails at validate time. The verdict is set
where the leaf is constructed: the two collapsed `SingleRecordTableField` arms in
`classifyObjectReturnChildField` build the single-arrival (loader-free) shape; every other
`RecordTableField` construction builds the batched shape.

No stored `sourceCardinality` value is introduced. Storing arrival as a slot or enum would duplicate what
`LoaderRegistration` presence already encodes (the redundant-predicate smell "Generation-thinking" names)
and would be the enum-by-symmetry with the renamed `ValueCardinality` that the shapes rule out anyway:
the single-arrival arm carries the in-hand read contract (today's `Reader.ResultRowWalk`) and the
many-arrival arm carries the `LoaderRegistration`, which are *different payloads*. Whether those two
payloads live as nullable slots on one flat leaf or as a sealed sub-variant split is exactly the
merged-leaf-shape question (Q2 below); the arrival predicate derives off whichever shape that question
settles on, and the derive-not-store decision holds either way.

### Per-seat, set at construction (no reachability walk for this item)

The verdict is a property of the **field instance / SDL seat**, not of the target type, and for this
item it is fixed where the leaf is constructed: the two collapsed `SingleRecordTableField` arms produce
the loader-free single-arrival shape, every other `RecordTableField` produces the batched shape, so a
type reachable both as a single-arrival payload carrier and as a list-nested field gets two independent
shapes, one per construction site, with no conflict. Because arrival rides on the leaf's own loader
payload (above), reading it back is an `instanceof` / component read, not a walk; there is no per-type
memoisation that could let the two paths collide. "Default many" (over-batching) is never a correctness
bug, so any seat whose single arrival is not provable safely keeps the loader.

This item therefore does **not** depend on R279, and `depends-on: []` is correct. R279's field-first
reachability walk matters for the one seat R305 leaves untouched: a list-arriving `@service` carrier
whose data field misclassifies as no-loader single arrival today. Deriving the correct many-arrival
verdict *there* needs the producing field's list-ness in hand, which is what R279's walk supplies; that
derivation and its DataLoader emit are **R308**, which depends on R279. R305 reads arrival off the slot
the leaf already carries and needs no walk.

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

## Settled / open for Ready

1. **Assert vs derive ; settled (derive).** Arrival is a derived predicate off the leaf's own loader
   shape (`LoaderRegistration` absence ⟺ single arrival), set at the construction site and mirrored by
   `GraphitronSchemaValidator`, per the `OutputField.requiresReFetch()` precedent. No stored
   `sourceCardinality` slot, no `SourceKey` cardinality enum, and no R279 dependency (R308 owns the one
   walk-dependent seat). See §"Assert vs derive" above.
2. **Merged-leaf shape ; open.** Flat `RecordTableField` with a non-uniform `BatchKeyField`, or a sealed
   sub-variant split (single-arrival / batched) under it. The structural evidence sharpens the question:
   the two arms carry *different payloads* (one a `LoaderRegistration`, one a loader-free
   `Reader.ResultRowWalk` `SourceKey`), which points at the sealed split per "Sealed hierarchies over
   enums" and away from a flat leaf with a nullable `LoaderRegistration` + conditional `BatchKeyField`
   (the "capability forks on identity within one leaf" smell). Confirm against the actual `BatchKeyField`
   / `LoaderRegistration` / `Reader.ResultRowWalk` shapes with the `principles-architect` subagent before
   Ready.

