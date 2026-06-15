---
id: R305
title: Expand the carrier dimension with source-shape and cardinality; separate re-fetch from intent and collapse SingleRecordTableField into RecordTableField
status: In Progress
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-15
---

# Expand the carrier dimension with source-shape and cardinality; separate re-fetch from intent and collapse SingleRecordTableField into RecordTableField

`SingleRecordTableField` (SRTF) and `RecordTableField` (RTF) are the same operation at different source
cardinalities. A producer (`@service` method or DML write) hands back a domain record, and the field
re-projects the `@table` columns the selection asks for by correlating the record's keys to the catalog rows.
RTF does this for many arriving source records (DataLoader-batched); SRTF is the single-source case, modeled
as a separate leaf only to skip the DataLoader. That skip is a derived emit detail, not a distinct operation.

The deeper defect is dimensional, and it is two things. The model never asserted **what arrives at the
field's source**; and it conflated **re-fetch with intent**. Intent is about the target and how arguments are
interpreted; re-fetch ("the target table must be re-projected from keys held at the source") is an orthogonal
derived axis. A field that re-fetches keeps its own intent, so SRTF stays `Fetch`; it does not become
`Lookup`. The missing source-shape axis plus the intent/re-fetch separation is what lets SRTF, RTF, RLTF,
RTMF, and the `@service`-batched STF sit on one re-fetch axis without lying about any of their intents.
`OrderingOwnedByProducer`, the overloaded `OrderBySpec.None`, and the `validateListRequiresOrdering` exemption
were all papering over the missing axis; they dissolve once re-fetch is derived honestly.

Lineage: split out of **R290** / **R222**'s leaf dissolution (R222 names the SRTF collapse and the derived
re-fetch). This supersedes two earlier framings on this item: the "merge into `RecordTableField` + a
`SourceArrival` slot" sketch, and the "reclassify SRTF to `Lookup`, build the One path" framing. The latter
overloaded intent with re-fetch; the corrected model (settled 2026-06-15) keeps intent about the target,
derives re-fetch as an orthogonal axis, and collapses SRTF into `RecordTableField` by source cardinality.

## The insight

Two facts the model was missing, and one it was overloading.

`mapping` (R281) asserts the **output**: what the field's value *is*, on the mirror/reflect split
`Table : Column :: Record : Field`. Nothing asserted the **input**: what arrives at `env.getSource()`. That
fact lived implicitly in leaf identity plus `SourceKey`. Asserting source-shape as `Record | Table`, on the
same catalog-vs-domain split `mapping` uses, makes the edge's two endpoints explicit.

**Re-fetch is orthogonal to intent.** Intent classifies the target and how arguments are interpreted;
re-fetch is the `Record`-to-`Table` crossing: a domain record that must become a catalog table forces
re-projecting the table from the record's keys, mechanically a `VALUES(idx, key...)` join with `ORDER BY idx`.
The earlier framing tried to call that crossing a `Lookup` and move SRTF's intent, which conflated the two
axes. A `@lookupKey` child lookup keys off a GraphQL argument, so its intent genuinely is `Lookup`; SRTF keys
off a producer record while its intent stays whatever the target dictates (`Fetch`). The re-fetch trigger is
**`Table` mapping combined with the field holding records to project**, where "holds records" is *received a
record* (`Source{Record}`) or *produced one* (a Service / DML intent).

The family on the re-fetch axis, intents left intact:

| field | source | intent | mapping | re-fetch |
|---|---|---|---|---|
| SRTF (inline, single source) | `Source{Record, One}` | `Fetch` | `Table` | yes (received record) |
| RTF `RecordTableField` (batched) | `Source{Record, Many}` | `Fetch` | `Table` | yes (received records) |
| RLTF `RecordLookupTableField` | `Source{Record, Many}` | `Lookup` | `Table` | yes (RTF's `@lookupKey` sibling) |
| `@lookupKey` child lookup | `Source{Table, *}` | `Lookup` | `Table` | no (inline join off the parent table) |
| STF `ServiceTableField` (batched) | `Source{Table, Many}` | `QueryService` | `Table` | yes (service produces records mid-field) |
| `Film.rating` (service scalar) | `Source{Table, *}` | `QueryService` | `Field` | no (reflect-side output) |

SRTF is RTF at cardinality `One`: the only model difference is the source cardinality, so the separate leaf
collapses (the DataLoader skip at `One` is a derived emit detail). And because a re-fetch field's source
record and target table are the same entity, **the source key is the target key**: the re-fetch key carries
the target table and its identifying columns once, which is the `SourceKey` cleanup this item takes.

## Slice 1: Amend R222 (the carrier dimension) — shipped at `b3f0f68`

Documentation-only amendment to `dimensional-model-pivot.md` §"Field-side dimensional model", four sections:
the `carrier` axis gains the `Source`-arm source-shape (`Table | Record`, the input-side mirror of
`mapping`'s `Table:Column :: Record:Field`, projected from the parent's `domainReturnType`) and source
cardinality (`One | Many`, the ancestor-cardinality product); the derived-layer re-fetch bullet re-derives to
`Table mapping × holds-records` (`Source{Record}` received or a Service/DML intent produced) with the "Why the
producer dimension dissolves" line following; the "bulk is a slot" sentence names the source/target cardinality
split (`SourceKey.Cardinality` is the target axis); and the `SingleRecordTableField` leaf-dissolution bullet
reframes to the source-shape unification with `ServiceTableField`. R222 stayed `Spec` (body-only edit).

Source cardinality is **the product of all ancestor field cardinalities** along the path from the operation
root to the field, over the `{One, Many}` semiring where `One` is the identity and `Many` absorbs
(`One x One = One`, `One x Many = Many`, `Many x Many = Many`). A field is `Source{One}` only when every
ancestor field is single-valued, and `Source{Many}` the moment any one ancestor is list-valued: a single
`Many` ancestor makes every descendant `Many`. It is therefore a path-accumulated property of *where the
field sits in the selection tree*, read off the field's ancestry (the reachability walk R279 gives each
field that ancestry), not a local property of the field's own return type or wrapper.

This is **very different from `SourceKey.Cardinality`** (`SourceKey.java:110`), which is the *per-key* count:
how many source rows the rows-method body yields for a single DataLoader key (`ONE` = one row per key, `MANY`
= a list / accessor walk per key). That is a local, target-side notion (rows per source object), the very
axis this slice keeps source cardinality distinct from. The implementer must not derive `Source{cardinality}`
from `SourceKey.cardinality()`: the two answer different questions ("how many source objects arrive" versus
"how many rows per key") and vary independently. A single-valued child under a list-valued parent is
`SourceKey.Cardinality.ONE` yet `Source{Many}` (many parents arrive, one row each); a list-valued child under
a single root is `SourceKey.Cardinality.MANY` yet `Source{One}` (one source object, many rows). The product
rule is the source axis; `SourceKey.Cardinality` stays the target axis, and the broader disentangling of the
`wrapper().isList()` source-vs-target conflation remains the out-of-scope `SourceKey.Cardinality` cleanup
below.

## Slice 2: Materialise `Source{shape, cardinality}` and assert it in the corpus — shipped at `355f9b5`

Foundation slice; SRTF stays a `Fetch` leaf and `OrderingOwnedByProducer` stays present, so the build is green
throughout and the new carrier values are observable on their own.

- `SourceShape{Table, Record}` and `SourceCardinality{One, Many}` are their own sub-taxonomies; `Carrier` is
  now sealed (`Query` / `Mutation` payload-less, `Source(SourceShape, SourceCardinality)`).
  `OutputField.carrier()` materialises `Source(sourceShape(), One)`.
- Source-shape is a leaf-exhaustive switch on `ChildField.sourceShape()`: the classifier already projects the
  parent's backing into leaf identity (`RecordTableField` vs `TableField`, `RecordField` vs `ColumnField`, the
  `SingleRecord*` / `Errors` payload fields), so the switch is that projection and a new leaf is forced to
  decide, mirroring `intent()` / `mapping()`. Source cardinality is constant `One` this slice (the
  ancestor-product `Many` arrival is R308).
- Corpus grown: the `@classified` directive gained optional `sourceShape` / `sourceCardinality` args
  (`sourceShape` defaults `Table`; the 12 record-source rows declare `Record`), `ClassifiedHarness` builds the
  sealed `Carrier` and exposes the arm / source-axis value sets, and `ClassifiedDslTest` retargets the
  `Carrier.values()` sites to the sealed-arm set, mirrors the two new SDL enums, exercises both source-shapes,
  and pins the `One`-only deferral.

## Slice 3: Separate re-fetch from intent; collapse SRTF into RecordTableField; clean the re-fetch key; unify emit

The governing correction (settled 2026-06-15): **intent and re-fetch are orthogonal.** Intent is about the
target and how arguments are interpreted (`Fetch` plain, `Lookup` the `@lookupKey` positional correspondence,
the writes, `QueryService`); it does not move because a field re-fetches. Re-fetch is the derived axis "the
target table must be re-projected from keys held at the source." SRTF keeps intent `Fetch`; it does not become
`Lookup`.

- **Re-fetch derivation (orthogonal to intent).** `OutputField.requiresReFetch()` derives from
  `Table` mapping combined with *holds-records* (`Source{Record}` received, or a Service / DML intent
  produced), not from intent alone. Applied honestly this catches the whole family: SRTF, `RecordTableField`
  (RTF), `RecordLookupTableField` (RLTF), `RecordTableMethodField` (RTMF), and `ServiceTableField` (STF).
  `GraphitronSchemaValidator.dispatchPerformsReFetch` and `ReFetchDerivationTest` move all of these to the
  re-fetch side (today the Record-source leaves sit at `false` and the mirror passes vacuously); the generator
  already emits a re-projecting SELECT for each, so the mirror stays honest.
- **Source-shape mirror (pin the projection).** Slice 2 implemented `ChildField.sourceShape()` as a
  leaf-exhaustive switch on the reasoning that the classifier already projects parent-backing into leaf
  identity; the javadoc claims it is "a projection of the parent producer's `domainReturnType`", but nothing
  mechanical pins that. Since this slice makes `requiresReFetch` consume source-shape (the `holds-records`
  half), a future leaf added with the wrong `sourceShape` arm would silently flip a re-fetch verdict with no
  failing test. Add a pipeline-tier test asserting, for every classified `ChildField`, that `sourceShape()`
  agrees with the sealed arm of its parent producer's `domainReturnType()` (`Record` / `TableRecord` →
  `SourceShape.Record`; catalog-`Table` parent → `SourceShape.Table`). This is the source-shape analogue of
  the `dispatchPerformsReFetch` mirror and converts the javadoc invariant into a pinned one.
- **Collapse SRTF into RTF (source cardinality is the only difference).** SRTF is RTF at source cardinality
  `One`. Delete the `SingleRecordTableField` leaf; its two construction sites (the R178 DML-carrier arm and the
  R275 `@service`-carrier arm in `FieldBuilder`) produce `RecordTableField` carrying source cardinality `One`.
  Intent stays `Fetch`. `RecordLookupTableField` is RTF's `@lookupKey` sibling (intent `Lookup`); both
  re-fetch. `One` re-projects inline with **no DataLoader**; `Many` keeps the DataLoader batch. The
  cardinality slot drives the DataLoader-skip, which is now a derived emit detail, not a leaf identity.
- **Clean the re-fetch key (`SourceKey` is `TargetKey`).** Because a re-fetch field's source record and its
  target `@table` are the same entity, the key read off the source *is* the target table's identifying key.
  The re-fetch family carries one key: the target table plus its identifying columns, the source-read shape
  (`DIRECT` / `OUTCOME_SUCCESS` envelope, `Record` / typed-`TableRecord` wrap), and source cardinality, with
  no source-vs-target column duality, FK-chain `path`, or lifter/accessor reader. This carves the re-fetch key
  out of today's general `SourceKey` (whose `path` / six-`Reader` / source-target-column apparatus is
  DataLoader-batch machinery the re-fetch case does not need). The broad `SourceKey` cleanup for the remaining
  non-re-fetch DataLoader fields (`SplitTableField` etc.) stays out of scope, riding the R222 work below.
- **RTMF realignment.** `RecordTableMethodField` becomes RTF with the target table instance obtained from the
  `@tableMethod` rather than the static jOOQ table. Fold into this slice if it falls out of the shared
  re-fetch path; split to a follow-up item if it needs its own emit work.
- **Delete `OrderingOwnedByProducer`.** The marker leaves the model. Replace the
  `!(field instanceof OrderingOwnedByProducer)` clause in `validateListRequiresOrdering`
  (`GraphitronSchemaValidator.java:234`) with a plain `requiresReFetch` exemption: a re-fetch field's visible
  order is locked to the source/target key correspondence (idx), so it is deterministic regardless of intent.
  This also fixes a latent bug: a PK-less idx-ordered re-fetch currently clears the check only by the
  incidental `OrderBySpec.Fixed(PK)` default from `OrderByResolver.java:133`, and would be wrongly rejected
  without a PK.
- **Emit.** The `One` path re-projects inline (no DataLoader): the `byPk` Java re-walk in
  `FetcherEmitter.buildSingleRecordTableFetcherValue` is replaced by the `InlineLookupTableFieldEmitter`
  `VALUES(idx, key)` join + `ORDER BY idx` mechanism (`InlineLookupTableFieldEmitter.java:228`,
  `LookupValuesJoinEmitter.java:422`) fed by the re-fetch key. The `Many` path keeps RTF's existing
  `SplitRowsMethodEmitter` DataLoader Split-rows; STF's `buildServiceTableLift` already emits the idx-ordered
  shape, no STF emit rewrite required.
- `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` stays exhaustive and disjoint after
  the leaf deletion.

This is a *correction*, not a behaviour-preserving reclassification: SRTF's emitted SQL changes (an
idx-ordered `VALUES`-join instead of a Java re-key) and its re-fetch verdict flips to `true`. Its **intent does
not change** (stays `Fetch`). What is preserved is the runtime result: the same rows in the same source order.

## Acceptance

- **Execution tier (load-bearing)**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk,
  `DIRECT` + `OUTCOME_SUCCESS` envelopes, the delete-then-echo `fjernSakTagg` shapes) is preserved end-to-end
  against PostgreSQL via `SingleRecordPayloadDmlTest` /
  `SingleRecordTableFieldServiceProducerExecutionTest`: same rows, same source order, now produced by an
  `ORDER BY idx` `VALUES`-join.
- **Classification / corpus**: the carrier asserts `Source{shape, cardinality}`; the former SRTF coordinate
  now classifies as `RecordTableField` with `Source{Record, One}`, intent `Fetch` unchanged (the corpus row
  keeps `intent: Fetch`); `requiresReFetch` derives `true` across the family (SRTF→RTF, RLTF, RTMF, STF);
  `ReFetchDerivationTest` gains cases asserting `true` for the Record-source leaves and mirror agreement; the
  `instanceof SingleRecordTableField` assertions in `SingleRecordPayloadPipelineTest` retarget to
  `RecordTableField` at cardinality `One`. This is a deliberate leaf change, not byte-invariance.
- **Pipeline tier**: the inline `VALUES(idx, key)` + `ORDER BY idx` emit shape is pinned by structural
  `TypeSpec` assertions.
- **Dispatch and re-fetch mirror**: `SingleRecordTableField` and `OrderingOwnedByProducer` leave the model;
  `dispatchPerformsReFetch` agrees with `requiresReFetch`; `everyGraphitronFieldLeafHasAKnownDispatchStatus`
  stays exhaustive and disjoint.
- **Source-shape mirror**: a pipeline-tier test pins `ChildField.sourceShape()` against the parent producer's
  `domainReturnType()` arm, so the leaf-switch cannot silently diverge from the projection it claims to be.
- **Validator**: `validateListRequiresOrdering` exempts `requiresReFetch` fields (regardless of intent); a
  PK-less re-fetch fixture validates rather than rejecting, guarding the latent-bug fix.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Out of scope

- **The `@service` carrier that itself arrives as a list** (the live N+1): a `@service` carrier arriving as a
  list is source cardinality `Many` and needs the DataLoader path; deriving that verdict needs the producing
  field's wrapper (R279's walk). That is **R308** (`service-list-payload-arrival`), which builds the `Many`
  carrier arm on the `Source{shape, cardinality}` + re-fetch framework this item establishes.
- **Target-cardinality-many on the `One` path** (a single source element correlating to a list per key,
  needing within-group `orderBy`): the first cut matches today's PK-keyed one-row-per-record shape; the
  target-many composition is a follow-up if a shape needs it.
- **Disentangling `SourceKey.Cardinality` fully** beyond the source/target split this item names: the broader
  cleanup of the `wrapper().isList()` source-vs-target conflation (`FieldBuilder` 4049 / 4102) rides the R222
  dimensional work.
