---
id: R305
title: Replace SingleRecordTableField with a Lookup-intent inline correlation (sourceCardinality One)
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Replace SingleRecordTableField with a Lookup-intent inline correlation (sourceCardinality One)

`SingleRecordTableField` (SRTF) is wrong by design and by implementation, and this item deletes it.

By design it is classified `Fetch` (`ChildField.java:53`) when its whole job is a `Lookup`: correlate an
in-hand source key-list to the looked-up `@table` rows and return them in source order. By implementation
it hand-rolls that correlation in Java (`FetcherEmitter.buildSingleRecordTableFetcherValue`, lines
635-680: fetch `WHERE pk IN (...)` unordered, build a `byPk` map, re-walk `source`) when the codebase
already does it in SQL everywhere else. The `Fetch` misclassification is the root of the ordering
complexity: a `Fetch` has no source-order, so SRTF needs `OrderingOwnedByProducer`, the overloaded
`OrderBySpec.None`, and a `validateListRequiresOrdering` exemption to paper over it.

Lineage: split out of **R290** / R222's leaf dissolution. This supersedes the earlier "merge into
`RecordTableField` + a `SourceArrival` slot" framing on this item, which solved the wrong problem by
accepting the `Fetch` classification.

## The correct model: Lookup intent + sourceCardinality

**`Lookup`** is the correlation primitive: build a `VALUES(idx, key…)` derived source table, join it to
the target `@table`, and `ORDER BY idx` so rows come back in source order. The codebase already emits
exactly this for lookups (`LookupValuesJoinEmitter:377-383, 422`; `InlineLookupTableFieldEmitter:228`)
and for batched record correlation (`SplitRowsMethodEmitter`, the `VALUES(idx, parent_pk)` derived table
+ `scatterSingleByIdx`). Ordering is a property of the intent: `Lookup` locks visible order to `idx`.
There is no ordering slot to set for it, no marker, no exemption.

**`sourceCardinality { One, Many }`** is the new axis: how the source arrives at the fetcher.

- **`Many`** , the source arrives across many fetcher invocations, batched via DataLoader scatter-gather.
  This is `RecordTableField` (RTF) and the batched lookups; already built.
- **`One`** , a single source element is in hand at fetch time, so the correlation runs inline, no
  DataLoader. This is the path this item builds, and what SRTF should have been.

Keep this distinct from **target cardinality** (how many `@table` rows each source element maps to), the
separate axis that drives within-group `orderBy`. The legacy `SourceKey.Cardinality {ONE, MANY}`, set
from `wrapper().isList()` (`FieldBuilder:4049/4102`), conflates the two: for SRTF a `[Table]` return
means *source*-many (a bulk mutation's record list, one row each), while for a nested `[Actor]` field it
means *target*-many (one parent, many children). One flag, two axes; disentangling source cardinality is
what lets the `One` path be expressed honestly, and it is why SRTF could not say "many source elements,
one target each, ordered by source idx".

## Scope: build the `One` path

`One` is the simpler path because the source is already in hand: it skips DataLoader registration, the
rows-method, and the scatter machinery entirely. Concretely:

- **Classify** the two current SRTF construction sites in `FieldBuilder.classifyObjectReturnChildField`
  (the R178 DML-carrier arm ~4062 and the R275 `@service`-carrier arm ~4121) as `Lookup` intent,
  `sourceCardinality = One`, keyed by the producer records. The existing `SourceKey.Reader.ResultRowWalk`
  (with its DIRECT / OUTCOME_SUCCESS envelope) becomes one key-reader among the lookup family's readers,
  reading the key off `env.getSource()` instead of a GraphQL argument.
- **Emit** the inline `VALUES(idx, key)` correlation, reusing `InlineLookupTableFieldEmitter`'s mechanism
  fed by the record-sourced key reader: build the rows from the in-hand source, `join … using(pk)`,
  `ORDER BY idx`, return. This replaces the `byPk` Java re-walk.
- **Delete** `SingleRecordTableField`, `OrderingOwnedByProducer`, and the `byPk` re-walk path; drop the
  `!(instanceof OrderingOwnedByProducer)` clause from `validateListRequiresOrdering` (the field is now an
  idx-ordered `Lookup`, deterministic by construction, so no exemption is needed).

This is a *correction*, not a behaviour-preserving reclassification: the emitted SQL changes (an
idx-ordered `VALUES`-join instead of a Java re-key) and the classification tuple changes
(`Fetch → Lookup`). What is preserved is the runtime *result*, the same rows in the same source order.

## Acceptance

- **Execution tier (load-bearing)**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk,
  DIRECT + OUTCOME_SUCCESS envelopes, the delete-then-echo `fjernSakTagg` shapes) is preserved end-to-end
  against PostgreSQL via `SingleRecordPayloadDmlTest` /
  `SingleRecordTableFieldServiceProducerExecutionTest`: same rows, same source order, now produced by an
  `ORDER BY idx` `VALUES`-join.
- **Classification**: the reclassified fields carry `intent = Lookup`; the R281/R299 corpus and the
  `instanceof SingleRecordTableField` assertions in `SingleRecordPayloadPipelineTest` are updated to the
  new leaf/intent. This is a deliberate tuple change, not byte-invariance.
- **Pipeline tier**: the inline `VALUES(idx, key)` + `ORDER BY idx` emit shape is pinned by structural
  `TypeSpec` assertions.
- **Dispatch**: `SingleRecordTableField` and `OrderingOwnedByProducer` leave the model;
  `everyGraphitronFieldLeafHasAKnownDispatchStatus` stays exhaustive and disjoint.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Out of scope

- **The `Many` path for the list-arriving `@service` carrier** (the live N+1): a `@service` carrier that
  itself arrives as a list is `sourceCardinality = Many` and needs the DataLoader path; deriving that
  verdict needs the producing field's wrapper (R279's walk). That is **R308**
  (`service-list-payload-arrival`), which builds the `Many` sibling on the same `Lookup` +
  `sourceCardinality` framework this item establishes.
- **Target-cardinality-many on the `One` path** (a single source element correlating to a list per key,
  needing within-group `orderBy`): the first cut matches today's PK-keyed one-row-per-record shape; the
  target-many composition is a follow-up if a shape needs it.
- **Disentangling `SourceKey.Cardinality` fully** beyond introducing `sourceCardinality`: the broader
  cleanup of the `wrapper().isList()` source-vs-target conflation rides the R222 dimensional work.
