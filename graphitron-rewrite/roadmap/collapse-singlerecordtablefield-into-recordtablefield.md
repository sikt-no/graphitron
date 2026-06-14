---
id: R305
title: Dissolve SingleRecordTableField; assert source-arrival cardinality as a dimensional slot; rename SourceKey.Cardinality to ValueCardinality
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Dissolve SingleRecordTableField; assert source-arrival cardinality as a dimensional slot; rename SourceKey.Cardinality to ValueCardinality

Split out of **R290** (`dimensional-model-pivot`, R222 Stage 3) as its deferred second leaf-retirement.
R222 §"Leaf dissolution and collapse" names the target directly: "`SingleRecordTableField` collapses ...
**No distinct leaf**"; the service/DML → `@table` two-step is "now *derived* rather than a distinct leaf";
and "**bulk is a slot** (cardinality), not an intent." This item lands that dissolution on top of the
dimensional model R290 materialised (the `OutputField.carrier()` / `intent()` / `mapping()` accessors and
the `requiresReFetch()` derivation): it adds **source-arrival cardinality** as the dimension that
distinguishes the inline in-hand read from the batched DataLoader read, folds `SingleRecordTableField`
into `RecordTableField`, and drives the emit fork off the slot instead of leaf identity.

This reverses an earlier "keep two sibling leaves" pass on this spec. That pass weighed "sealed
hierarchies over enums" as if the current leaf set were the end state, but R222 is deliberately
dissolving leaf identity into dimensional slots, and a leaf is exactly what this axis should stop being.
The reconciliation is in §"The slot shape" below: the disjoint emit payloads do not vanish, they move
into a sealed *slot* family rather than living as two leaves.

## Source-arrival cardinality is an asserted dimension

**Source-arrival cardinality** is how many source objects the data fetcher is invoked against. graphql-
java completes a list parent by iterating it and invoking the child fetcher once per element, each seeing
a single `env.getSource()`; so arrival is a property of the **producing/parent field's** list-ness, not
of the data field's own return type. A source that arrives singly needs no batching (the inline in-hand
read is correct and optimal); one that can arrive in plurality needs a DataLoader or it incurs an N+1.

It is **asserted, not derived**, per R222's governing rule ("assert what nothing else carries; derive
what another axis or slot already forces"). Nothing else on the field forces it:

- Not `intent()` × `mapping()`. A formerly-`SingleRecordTableField` payload carrier and a nested
  `RecordTableField` both classify `intent = Fetch`, `mapping = Table` (`ChildField` lines 48/53, 84/88),
  same tuple, opposite arrival.
- Not `requiresReFetch()`. That predicate lives on the *producer* (the `MutationField` /
  `ServiceTableField` that re-queries the table); the data field reading the re-fetched record is a plain
  `Fetch` either way.
- Not the field's own value-cardinality (the enum this item renames): a bulk `List<Payload>` DML
  carrier's data field is value-`MANY` yet still single-*arrival*.

R308 is the proof the axis is real and orthogonal: a list-arriving `@service` carrier shares the
`Fetch × Table` tuple of R305's single-`@service` carrier but is many-arrival. `intent × mapping` cannot
tell them apart; only a walk to the producing field's wrapper can, which is why deriving that seat's
verdict is **R308**'s job (depending on R279's field-first walk), not this item's. Within R305's scope
every formerly-`SingleRecordTableField` site is provably single-arrival and the slot is asserted `SINGLE`
at construction; the default for a nested `RecordTableField` is `MANY`.

## The slot shape: a sealed family, not a bare enum or a nullable field

The keep-two-leaves pass was right about one thing: the single-arrival and many-arrival arms carry
**disjoint payloads**. Single-arrival is a `SourceKey.Reader.ResultRowWalk` in-hand read with no
`LoaderRegistration`; many-arrival carries a `LoaderRegistration` and a batched rows-method. A bare
`sourceCardinality` enum plus a nullable `LoaderRegistration` would be the "enum forces every variant to
the same shape" smell. The dimensional shape keeps both honest at once:

- `SingleRecordTableField` is **dissolved**; its two construction sites in
  `FieldBuilder.classifyObjectReturnChildField` (~4062 / ~4121) build `RecordTableField` instead.
- `RecordTableField` becomes a single leaf carrying a **sealed source-arrival slot** whose arms hold the
  divergent payload, working shape `SourceArrival { Single | Many(LoaderRegistration) }` (the exact
  record/accessor names, and whether `SourceKey` stays a sibling component, the implementer pins; the
  load-bearing claim is *one leaf, sealed slot, payload-per-arm*). The `Single` arm carries the in-hand
  `ResultRowWalk` contract; the `Many` arm carries the `LoaderRegistration`.
- Because it is one leaf with a slot (not a sub-seal), the dispatch partition counts it once and the live
  leaf set drops 48 → **47** on retirement. A sealed *sub-variant* split under `RecordTableField` would
  keep two partition entries and not reduce the count; that is the shape to avoid, it re-encodes arrival
  as (sub-)leaf identity rather than as the slot R222 wants.

This is the same move R290 made for `carrier` / `intent` / `mapping` and `requiresReFetch`: a dimension
lands as model state the consumers read, and the leaf-identity switch dissolves.

## Resolving the two capability markers

Two markers today split `SingleRecordTableField` from `RecordTableField`, and both are *static per-type
interface membership*, which a single merged leaf cannot make conditional on a per-instance fact. They
resolve to two *existing* slots: `OrderingOwnedByProducer` is determinism logic that belongs in the
`OrderBySpec` ordering slot (it dissolves into an `OrderBySpec` arm); `BatchKeyField` (payload-carrying,
uniform across its other implementers) survives, with the merged leaf's loader riding the
`SourceArrival.Many` slot. This is the "emit-mechanism unification, not a leaf merge" R290 flagged, and it
is the bulk of the work.

### OrderingOwnedByProducer → an OrderBySpec arm (determinism belongs in the ordering slot)

`OrderingOwnedByProducer` (`OrderingOwnedByProducer.java`; payload-free sealed marker, permits
`SingleRecordTableField` + `ServiceTableField`) exists only so
`GraphitronSchemaValidator.validateListRequiresOrdering` (line 232) exempts those fields from the
"list-shaped + `OrderBySpec.None` ⇒ non-determinism" rejection. But determinism is `OrderBySpec`'s job:
`OrderBySpec` is the *authoritative resolved* ordering (its own doc), not author intent, its `Fixed` arm
is "derived from `@defaultOrder` **or the table's primary key**", and its `None` arm is already overloaded
("the return type is a single value" *and* "no primary key and no `@defaultOrder`"). The marker is an
escape hatch around an under-expressive slot: the producer-owned fields are **mislabelled `None`** (their
order is deterministic, just not a graphitron `ORDER BY`), and the marker only tells the validator to
ignore that `None`.

The fix is to stop mislabelling them. `OrderBySpec` gains an arm (working name `OwnedByProducer`) meaning
"deterministic order established upstream; graphitron emits no `ORDER BY`." The merged `RecordTableField`
`Single` arm and `ServiceTableField` carry it instead of `None`. `validateListRequiresOrdering` then
simply **drops** its `!(instanceof OrderingOwnedByProducer)` clause: a list field is rejected iff its
`OrderBySpec` is `None`, which now unambiguously means "unresolvable". The marker is deleted; there is no
`orderingOwnedByProducer()` predicate, and the validator never reads `SourceArrival`.

As an `OrderBySpec` arm this has *two* real consumers, the emitter (emit `ORDER BY` or not) and the
validator (determinism), so it is a genuine resolved-ordering value, not a single-consumer predicate. It
corrects two earlier missteps in this spec's history: dropping this arm (rejected on a false
"`OrderBySpec` is author-intent-only" premise) and then proposing a mirror-less
`orderingOwnedByProducer()` predicate.

`ServiceTableField` shows this is *not* the `SourceArrival` axis: it is many-arrival (a `BatchKeyField`)
yet producer-ordered. `SourceArrival { Single | Many }` (fetch mechanism) and `OrderBySpec` (resolved
ordering) are orthogonal, `ServiceTableField` is `Many` + `OwnedByProducer`; the merged single-arrival
field is `Single` + `OwnedByProducer`. Deriving the ordering exemption from arrival would have been wrong
for exactly this reason.

Lookup is untouched: it carries a real `Fixed` / `Argument` `OrderBySpec` and clears the validator
already (the orderBy resolver lands on `None` only for a PK-less table with no author ordering,
`validateListRequiresOrdering` doc lines 219-222). Its `idx` input-order preservation
(`LookupValuesJoinEmitter` line 37) is a separate scatter concern. Splitting `None` further
(single-value-N/A vs unresolvable) is a broader `OrderBySpec` cleanup for the R222 Stage-2 Ordering slice,
not R305; R305 adds only the one `OwnedByProducer` arm the dissolution needs.

### BatchKeyField → the loader lives on the SourceArrival.Many arm

`BatchKeyField` (`BatchKeyField.java`) is "this field is DataLoader-backed", requires `sourceKey()` +
`loaderRegistration()`, implemented by six leaves. Its consumers are the rows-method emit + scatter sites
in `TypeFetcherGenerator` (the `emitsSingleRecordPerKey` gate at line 666; the `loaderRegistration()`
reads at 4914 / 4959 / 5032 / 5102) and `SplitRowsMethodEmitter` (line 1253), all firing only for
DataLoader-backed fields. A single-arrival merged `RecordTableField` must not reach them.

R305 moves the loader to the `SourceArrival.Many` arm and routes the batched-path consumers off the
`SourceArrival` switch: a `Many` arm yields the `LoaderRegistration` + rows-method; a `Single` arm routes
to the inline `FetcherEmitter.bind` read. The five always-batched leaves (`SplitTableField`,
`SplitLookupTableField`, `ServiceTableField`, `RecordLookupTableField`, `ServiceRecordField`) are
uniformly many-arrival and keep implementing `BatchKeyField` directly; only `RecordTableField` becomes
bi-modal, so only it carries the `SourceArrival` slot. `GraphitronSchemaValidator` mirrors the slot
against the emit path so the verdict and the emitter cannot drift (the discipline `requiresReFetch` uses).
Whether `BatchKeyField`-as-marker survives R305 (the merged leaf satisfying it through its `Many` arm) or
retires into a general loader slot is the R222 Stage 5 question (the `LoaderRegistration` permit
consolidation); R305 does the slot-local move and coordinates sequence, it does not retire the marker.
Confirm the slot shape does not fight Stage 5's consolidation; if it would, sequence the retirement step
after the relevant Stage 5 slice. No hard `depends-on` today, but this is the scope edge to watch.

## Sequencing (additive cutover, then retirement)

Per R290's slice discipline ("additive cutover, then destructive retirement"). The first three steps are
behaviour-preserving and safe to land independently; step 4 is the destructive step entangled with the
capability decision and Stage 5.

1. **Rename** `SourceKey.Cardinality {ONE, MANY}` → `SourceKey.ValueCardinality` (mechanical, ~26 call
   sites, payload-free enum, no generated-output change). Independent; can land first.
2. **Add the slot and the ordering arm.** The `SourceArrival { Single | Many(LoaderRegistration) }` slot
   on the merged `RecordTableField`, asserted at the construction sites (the two formerly-SRTF arms →
   `SINGLE`; nested `RecordTableField` → `MANY`), mirrored by `GraphitronSchemaValidator`; and the
   `OrderBySpec.OwnedByProducer` arm, with the merged `Single`-arm sites and `ServiceTableField`
   constructed carrying it instead of `None`.
3. **Migrate the consumers.** The emit fork and loader consumers read `SourceArrival` (`FetcherEmitter`,
   `TypeFetcherGenerator` rows-method/scatter sites, `SplitRowsMethodEmitter`); the emitter treats
   `OwnedByProducer` like `None` for `ORDER BY` emission; and `validateListRequiresOrdering` drops its
   `!(instanceof OrderingOwnedByProducer)` clause (reject iff `OrderBySpec` is `None`).
4. **Retire the marker and the leaf.** Delete `OrderingOwnedByProducer` and `SingleRecordTableField`
   (the leaf record, its `intent()` / `mapping()` switch arms, its `OrderingOwnedByProducer` membership),
   and retarget the `SingleRecordPayloadPipelineTest` `instanceof SingleRecordTableField` assertions to
   `RecordTableField` + `SourceArrival.Single`. 48 → **47**.

## Acceptance

- **Execution tier (load-bearing)**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk,
  DIRECT + OUTCOME_SUCCESS envelopes, the delete-then-echo `fjernSakTagg` shapes) is preserved end-to-end
  against PostgreSQL via `SingleRecordPayloadDmlTest` /
  `SingleRecordTableFieldServiceProducerExecutionTest`. A `SINGLE`-arrival `RecordTableField` must emit
  exactly the inline in-hand read `SingleRecordTableField` emits today, no SQL or runtime-result change.
- **Corpus tier**: byte-identical. The formerly-SRTF fields keep `carrier × intent × mapping` =
  `Source × Fetch × Table`, so the R281/R299 classified corpus stays green (it reads the three dimension
  accessors, which are unchanged).
- **Pipeline tier**: the `SourceArrival` arm and the emit shape it selects (inline follow-up SELECT for
  `Single`, DataLoader path for `Many`) are pinned by structural `TypeSpec` assertions; the dispatch
  partition stays exhaustive/disjoint with one fewer leaf
  (`everyGraphitronFieldLeafHasAKnownDispatchStatus`).
- **Validator mirror**: an arrival-verdict / emit disagreement fails at validate time, per "validator
  mirrors classifier invariants".
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Out of scope

- **The list-`@service` carrier reclassification** (the live N+1): deriving `MANY` arrival for a
  list-arriving `@service` carrier needs the producing field's wrapper, which is **R308**
  (`service-list-payload-arrival`), depending on R279's walk. R308 sets `SourceArrival.Many` at that seat
  and emits the DataLoader; kept out so this item's corpus-byte-invariance proof stays clean.
- **R222 Stage 5's** general `LoaderRegistration` permit consolidation, and whether `BatchKeyField`-as-
  marker retires into a loader slot (see the BatchKeyField resolution above). R305 does only the
  slot-local loader move.
- **Lookup ordering.** Lookups carry a real `OrderBySpec` and clear the validator normally (see the
  ordering resolution above); R305 does not touch them. The keyed-scatter kinship between lookup's `idx`
  input-order and the producer re-key is an arrival/scatter-mechanism observation for the R222 Stage-2
  `Ordering`/scatter work, not an R305 change.
