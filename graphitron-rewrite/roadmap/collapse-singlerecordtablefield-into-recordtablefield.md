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

## The load-bearing decision: the capability markers go slot-driven

Two capability markers today split `SingleRecordTableField` from `RecordTableField`, and both are
*static per-type interface membership*, which a single merged leaf cannot make conditional on a
per-instance slot:

- `BatchKeyField` (`BatchKeyField.java`): "this field is DataLoader-backed", requires `sourceKey()` +
  `loaderRegistration()`, implemented by six leaves including `RecordTableField`. A `SINGLE`-arrival
  `RecordTableField` is not DataLoader-backed.
- `OrderingOwnedByProducer` (sealed; permits `SingleRecordTableField` + `ServiceTableField`): the
  "list-requires-ordering" validator excludes its members by type. A `SINGLE`-arrival merged field needs
  that exclusion; a `MANY` one does not.

So dissolving into one leaf forces both markers, for the merged leaf, to become **reads off the
`SourceArrival` slot** rather than `instanceof` checks. This is the "emit-mechanism unification, not a
leaf merge" R290 flagged, and it is the bulk of the work. Recommended direction (consistent with R222's
plan to reorganise the mixin-interface overlay): the loader-discovering consumers
(`FetcherRegistrationsEmitter`, the `TypeFetcherGenerator` dispatch, `emitsSingleRecordPerKey()`'s sites)
and the ordering validator switch on `SourceArrival`; `GraphitronSchemaValidator` mirrors the slot
against the emit path so they cannot drift (the discipline `requiresReFetch` uses). Whether
`RecordTableField` keeps a (now slot-projected) `BatchKeyField` membership or sheds it is the precise
fork to settle against the actual consumer iteration, with the `principles-architect` subagent, before
Ready. Decide against the real `FetcherRegistrationsEmitter` / `emitsSingleRecordPerKey` sites, not in
the abstract.

### Interaction with R222 Stage 5

This overlay reorganisation is adjacent to R222 Stage 5 (the `LoaderRegistration` permit consolidation).
R305 does only the slot-local move it needs (the loader on the merged leaf's `Many` arm, the two markers
slot-driven for that leaf); it does not generalise the permit. Confirm the slot shape does not fight
Stage 5's planned consolidation; if it would, sequence the retirement step (below) after the relevant
Stage 5 slice rather than duplicating it. No hard `depends-on` today, but this is the scope edge to watch.

## Sequencing (additive cutover, then retirement)

Per R290's slice discipline ("additive cutover, then destructive retirement"). The first three steps are
behaviour-preserving and safe to land independently; step 4 is the destructive step entangled with the
capability decision and Stage 5.

1. **Rename** `SourceKey.Cardinality {ONE, MANY}` → `SourceKey.ValueCardinality` (mechanical, ~26 call
   sites, payload-free enum, no generated-output change). Independent; can land first.
2. **Add** the `SourceArrival` slot and assert it at the construction sites (the two formerly-SRTF arms
   `SINGLE`, nested `RecordTableField` `MANY`); `GraphitronSchemaValidator` mirrors it.
3. **Migrate** the emit fork and the two capability consumers to read `SourceArrival` instead of
   `instanceof SingleRecordTableField` / the markers (`FetcherEmitter`, `TypeFetcherGenerator` dispatch,
   `FetcherRegistrationsEmitter`, the ordering validator).
4. **Retire** `SingleRecordTableField`: the leaf record, its `intent()` / `mapping()` switch arms, its
   marker memberships, and the `SingleRecordPayloadPipelineTest` `instanceof SingleRecordTableField`
   assertions (retargeted to `RecordTableField` + `SourceArrival.Single`). 48 → **47**.

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
- **R222 Stage 5's** general `LoaderRegistration` permit consolidation (see interaction note above).
