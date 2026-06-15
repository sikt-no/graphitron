---
id: R305
title: Expand the carrier dimension with source-shape and cardinality; unify the service/DML to @table re-fetch as a source-keyed Lookup
status: In Progress
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-15
---

# Expand the carrier dimension with source-shape and cardinality; unify the service/DML to @table re-fetch as a source-keyed Lookup

`SingleRecordTableField` (SRTF) and `ChildField.ServiceTableField` (STF) are the same operation classified
two different ways. Both project a producer's record into a `@table`: a developer-`@service` method or a
DML write hands back a record, and the field re-projects the `@table` columns the selection asks for by
correlating the record's keys to the catalog rows. STF carries that honestly (`intent = QueryService`, so
the re-fetch derivation fires); SRTF is mislabeled `intent = Fetch` (`ClassifiedCorpus.java:651`), which
slips it out of the derivation even though its emitter runs the projecting SELECT anyway. The split shows
up downstream as `OrderingOwnedByProducer`, the overloaded `OrderBySpec.None`, and a
`validateListRequiresOrdering` exemption, all papering over a misclassification.

The fix is dimensional, not a leaf merge: assert the one fact the model is missing (what arrives at the
field's source), re-derive re-fetch from it, and the two operations become siblings on a single axis. The
re-projection is mechanically an idx-ordered `VALUES`-join, which is a `Lookup`; its ordering is owned by
the idx column, not by "the producer", so `OrderingOwnedByProducer` is a fiction that dissolves once the
classification is honest.

Lineage: split out of **R290** / **R222**'s leaf dissolution (R222 already names the SRTF collapse and the
derived re-fetch). This supersedes the earlier "merge into `RecordTableField` + a `SourceArrival` slot"
framing and the narrower "reclassify SRTF to Lookup, build the One path" framing on this item, both of which
under-modeled the missing source-shape axis and left STF and the re-fetch derivation untouched.

## The insight

`mapping` (R281) asserts the **output**: what the field's value *is*, on the mirror/reflect split
`Table : Column :: Record : Field`. Nothing asserts the **input**: what arrives at `env.getSource()`. That
fact lives implicitly today in leaf identity plus `SourceKey.Reader` / `SourceKey.Wrap`. The re-fetch
derivation needs it, and its absence is exactly why R222's `(Service | DML intent) x Table` rule fails on
SRTF: SRTF's honest intent is `Lookup` (a keyed read; the field itself does not write), so an intent-keyed
rule cannot see it. The distinguishing fact is not the intent, it is that **SRTF's source is a `Record`**
while a `@lookupKey` child lookup's source is a `Table` row. Same `Lookup` / `Table` on the asserted axes;
they diverge only on source-shape.

Asserting source as `Record | Table`, on the same mirror/reflect vocabulary as `mapping`, makes the edge's
two endpoints explicit. Re-fetch is the `Record` to `Table` crossing: a domain record that must become a
catalog table forces re-projecting the table from the record's keys. The re-projection is a
`VALUES(idx, key...)` join with `ORDER BY idx`, which is a `Lookup` by definition (`Intent.java:25`:
"a keyed read establishing a positional input-list / output-list correspondence"). The only difference from
a `@lookupKey` lookup is the key source: a producer record rather than a GraphQL argument.

The two siblings land at different carriers, and that is correct:

| field | carrier | intent | mapping | re-fetch |
|---|---|---|---|---|
| SRTF (inline, single source) | `Source{Record, One}` | `Lookup` | `Table` | yes (`Record` to `Table`) |
| `@lookupKey` child lookup | `Source{Table, *}` | `Lookup` | `Table` | no (inline join off the parent table) |
| STF (DataLoader-batched) | `Source{Table, Many}` | `QueryService` | `Table` | yes (service produces records mid-field) |
| `Query.externalFilm` (root service) | `Query` | `QueryService` | `Table` | yes (service produces; no source) |
| `Film.rating` (service scalar) | `Source{Table, *}` | `QueryService` | `Field` | no (reflect-side output) |

SRTF *receives* a `Record` (the parent mutation produced it); STF *produces* records itself (its rows-method
calls the service, keyed off the parent `Table` row). So the unified re-fetch trigger is **`Table` mapping
combined with the field holding records to project**, where "holds records" is *received a record*
(`Source{Record}`) or *produced one* (a `Service` / DML intent). The source-shape assertion supplies the
received half that intent structurally cannot. Both re-fetch, both re-project via an idx-ordered
correlation, and `OrderingOwnedByProducer` dissolves in both.

## Slice 1: Amend R222 (the carrier dimension)

Documentation only: amend `roadmap/dimensional-model-pivot.md` so the model doc is the source of truth before
any code moves. Three changes:

- **Expand the carrier dimension.** `Source` gains a source-shape (`Table | Record`) and a cardinality
  (`One | Many`). `Record` source-shape is the parent producer's `domainReturnType` (the reflect side);
  `Table` is a catalog-backed parent. `Query` / `Mutation` carriers have no source.
- **Re-derive re-fetch.** Replace `(Service | DML intent) x Table mapping` (pivot doc lines 116-118) with
  `Table mapping` combined with `holds-records`, where `holds-records` is `Source{Record}` (received) or a
  `Service` / DML intent (produced). This is the derivation that catches SRTF without lying about its intent.
- **Reverse the flat-enum decision.** `Carrier.java:12` deliberately made the carrier "a flat typed enum,
  carries no per-value payload". Source-shape and cardinality give it payload. The reversal is justified by
  the sealed-hierarchy principle: source-shape and cardinality are load-bearing forks (re-fetch, the
  DataLoader-skip optimisation, list-ordering determinism), so they earn type-system representation rather
  than staying smeared across leaf identity and `SourceKey`. Record the amendment in the pivot doc and note
  that the `@classified(carrier:)` corpus surface grows accordingly.

Cardinality here is the **source** cardinality (how many source objects arrive), kept distinct from target
cardinality (rows per source object), which continues to drive within-group `orderBy`. R222 line 143
("bulk is a slot, not an intent") already locates cardinality as a slot; this names which axis it sits on.

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

## Slice 2: Materialise `Source{shape, cardinality}` and assert it in the corpus

Foundation slice; SRTF stays a `Fetch` leaf and `OrderingOwnedByProducer` stays present, so the build is green
throughout and the new carrier values are observable on their own.

- Implement the enriched `Carrier` in the model (sealed shape carrying source-shape and cardinality on the
  `Source` arm). `OutputField.carrier()` returns it.
- Classify source-shape and cardinality at the parse boundary. Source-shape is the same fact as the parent
  producer's `OutputField.domainReturnType()`; assert it once and pin it against that, reusing the existing
  multi-producer grouping (`OutputField.java:17-21`) so there is a single source of truth, not two
  unsynchronised ones.
- Grow the corpus surface: the `@classified` directive, the `ClassifiedDsl` `Carrier` enum
  (`ClassifiedDsl.java:46`), `DimensionTuple`, and `ClassifiedHarness` extend to declare and compare
  source-shape and cardinality alongside the carrier. Existing rows acquire their source-shape value.

## Slice 3: Re-derive re-fetch; reclassify SRTF and STF; delete the leaf and the marker; unify emit

- **Re-fetch derivation.** `OutputField.requiresReFetch()` keys on the Slice 1 derivation (source-shape and
  intent against `Table` mapping) rather than intent alone. `GraphitronSchemaValidator.dispatchPerformsReFetch`
  and `ReFetchDerivationTest` move SRTF to the re-fetch side so the model finally agrees with the SELECT its
  emitter produces; today both sides hard-code SRTF to `false` and the mirror passes vacuously.
- **Reclassify.** SRTF becomes `Source{Record, One}` / `Lookup` / `Table`, keyed by the producer record (the
  existing `SourceKey.Reader.ResultRowWalk`, with its `DIRECT` / `OUTCOME_SUCCESS` envelope, becomes a
  record-sourced reader in the lookup family). STF stays `QueryService` but its re-fetch is now the same
  derived idx-ordered correlation, no longer a marker exemption.
- **Delete.** `SingleRecordTableField` (folding its two construction sites in
  `FieldBuilder.classifyObjectReturnChildField`, the R178 DML-carrier arm and the R275 `@service`-carrier
  arm, into the lookup family) and `OrderingOwnedByProducer` leave the model.
- **Validator.** Replace the `!(field instanceof OrderingOwnedByProducer)` clause in
  `validateListRequiresOrdering` (`GraphitronSchemaValidator.java:234`) with an idx-ordered-correlation
  exemption: a field whose visible order is locked to a correlation idx is deterministic. That set is
  `intent == Lookup` together with `requiresReFetch` (both emit an idx-ordered `VALUES`-join). This also fixes
  a latent bug: a PK-less idx-ordered lookup currently clears the check only by the incidental
  `OrderBySpec.Fixed(PK)` default from `OrderByResolver.java:133`, and would be wrongly rejected without a PK.
- **Emit.** The `byPk` Java re-walk in `FetcherEmitter.buildSingleRecordTableFetcherValue` is replaced by the
  `InlineLookupTableFieldEmitter` `VALUES(idx, key)` join + `ORDER BY idx` mechanism
  (`InlineLookupTableFieldEmitter.java:228`, `LookupValuesJoinEmitter.java:422`) fed by the record-sourced key
  reader. STF's `SplitRowsMethodEmitter.buildServiceTableLift` already emits exactly this shape
  (`ORDER BY seq`); no STF emit rewrite is required.
- `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` stays exhaustive and disjoint after
  the leaf deletion.

This is a *correction*, not a behaviour-preserving reclassification: SRTF's emitted SQL changes (an
idx-ordered `VALUES`-join instead of a Java re-key) and its classification changes
(`Fetch` to `Lookup`, with the re-fetch verdict flipping to `true`). What is preserved is the runtime result:
the same rows in the same source order.

## Acceptance

- **Execution tier (load-bearing)**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk,
  `DIRECT` + `OUTCOME_SUCCESS` envelopes, the delete-then-echo `fjernSakTagg` shapes) is preserved end-to-end
  against PostgreSQL via `SingleRecordPayloadDmlTest` /
  `SingleRecordTableFieldServiceProducerExecutionTest`: same rows, same source order, now produced by an
  `ORDER BY idx` `VALUES`-join.
- **Classification / corpus**: the carrier asserts `Source{shape, cardinality}`; the SRTF corpus row flips
  to `Source{Record, One}` / `Lookup` / `Table`; `requiresReFetch` derives `true` for both SRTF and STF;
  `ReFetchDerivationTest` gains an SRTF case asserting `true` and mirror agreement; the
  `instanceof SingleRecordTableField` assertions in `SingleRecordPayloadPipelineTest` retarget to the new
  leaf / intent. This is a deliberate tuple change, not byte-invariance.
- **Pipeline tier**: the inline `VALUES(idx, key)` + `ORDER BY idx` emit shape is pinned by structural
  `TypeSpec` assertions.
- **Dispatch and re-fetch mirror**: `SingleRecordTableField` and `OrderingOwnedByProducer` leave the model;
  `dispatchPerformsReFetch` agrees with `requiresReFetch`; `everyGraphitronFieldLeafHasAKnownDispatchStatus`
  stays exhaustive and disjoint.
- **Validator**: `validateListRequiresOrdering` exempts idx-ordered correlations; a PK-less idx-ordered
  lookup fixture validates rather than rejecting, guarding the latent-bug fix.
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
