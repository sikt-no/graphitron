---
id: R305
title: Name the source-arrival-cardinality axis; keep SingleRecordTableField and RecordTableField as sibling leaves; rename SourceKey.Cardinality to ValueCardinality
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Name the source-arrival-cardinality axis; keep SingleRecordTableField and RecordTableField as sibling leaves; rename SourceKey.Cardinality to ValueCardinality

Split out of **R290** (`dimensional-model-pivot`, R222's slot-materialisation work) as its deferred
second leaf-retirement: collapsing `ChildField.SingleRecordTableField` into `ChildField.RecordTableField`.
On review the collapse is **declined as wrong-by-design** (see "Why the collapse is declined" below): the
two leaves carry disjoint payloads and dispatch through two different emit mechanisms, so merging them
trades a clean sealed split for a nullable `LoaderRegistration` and a conditional `BatchKeyField`, the
smells "Sealed hierarchies over enums" and "Capability interfaces and sealed switches serve different
roles" name. R290 itself flagged this when it split the work out: it "proved to be an emit-mechanism
unification, not a leaf merge."

What R290 actually wanted, the arrival fact ceasing to be carried redundantly, is **already in the type
system** as sealed leaf identity: `SingleRecordTableField` *is* the single-arrival case and
`RecordTableField` *is* the many-arrival case. This item keeps both leaves, names the axis the split
encodes so a future contributor does not mistake it for incidental and re-attempt the merge, and does the
one orthogonal cleanup the axis discussion surfaced: renaming the misnamed `SourceKey.Cardinality`. The
live leaf set stays **48**; no leaf is retired.

## The missing concept: source-arrival cardinality

R290 framed the DataLoader-skip as "single-source cardinality" without a precise name; this is that
name. **Source-arrival cardinality** is how many source objects the data fetcher is invoked against.
graphql-java completes a list parent by iterating it and invoking the child fetcher once per element,
each seeing a single `env.getSource()`. So a source that arrives singly needs no batching (the inline
in-hand read is correct and optimal), while a source that can arrive in plurality needs a DataLoader or
it incurs an N+1. `SingleRecordTableField` is exactly the provably-single-arrival case;
`RecordTableField` is the default many-arrival case. The DataLoader-skip is not an incidental
optimisation, it is the correct consequence of single arrival, and it is encoded as the
`SingleRecordTableField` / `RecordTableField` sealed distinction rather than a flag a merge could fold
away. Naming the axis records *why* there are two leaves, so the split is not mistaken for incidental.

This axis must not be confused with the existing `SourceKey.Cardinality {ONE, MANY}`, which despite its
name is **value multiplicity** (one vs many target rows per key), set everywhere from
`returnType.wrapper().isList()` and read to choose source-binding shape, null-vs-empty checks, scatter,
and return wrapping. It says nothing about the source's arrival; a `SingleRecordTableField` for a bulk
`List<Payload>` carrier is value-`MANY` yet still single-*arrival*. This item renames the enum to
`SourceKey.ValueCardinality` (mechanical churn across the `.cardinality()` / `Cardinality.ONE|MANY` call
sites; it stays a payload-free `enum`) so the generic name stops reading as the source-arrival axis.

## Why the collapse is declined: keep two sibling leaves

R290 framed the collapse as "the single-source-object DataLoader-skip becomes a derived detail." On
implementation the two leaves proved to use **different emit mechanisms over disjoint payloads**, not one
mechanism with a skip flag:

- `SingleRecordTableField` (`ChildField.java` ~134) is an **inline-bound** data field. Its compact
  constructor requires `SourceKey.Reader.ResultRowWalk`; it carries **no** `LoaderRegistration`, no
  `parentCorrelation`, and structurally-empty `joinPath` / `filters` / `orderBy` / `pagination`. It is
  `OrderingOwnedByProducer`, is **not** a `BatchKeyField`, and is a no-op arm in the
  `TypeFetcherGenerator` switch whose fetcher value `FetcherEmitter.bind` /
  `buildSingleRecordTableFetcherValue` (Wrap.Record + Wrap.TableRecord arms) reifies into an inline
  in-hand read. It carries the R141 / R158 / R275 payload-carrier execution coverage (the delete-then-echo
  `fjernSakTagg` shapes).
- `RecordTableField` (`ChildField.java` ~791) carries a `SourceKey` **and** a `LoaderRegistration` and a
  `parentCorrelation`, has real `filters` / `orderBy` / `pagination`, **is** a `BatchKeyField`, and emits
  a `buildRecordBasedDataFetcher` plus a `SplitRowsMethodEmitter` rows-method.

The two share no overlapping component set, dispatch through two emitters, and sit on opposite sides of
both `BatchKeyField` and `OrderingOwnedByProducer`. That is the textbook case *for* two sealed leaves
("Sealed hierarchies over enums", "Narrow component types over broad interfaces", "Sub-taxonomies for
resolution outcomes"), not for a merge. A flat merged leaf would need a nullable `LoaderRegistration` and
a conditional `BatchKeyField`, breaking the `BatchKeyField.loaderRegistration()` contract its other
implementers rely on; a sealed sub-variant split *under* `RecordTableField` would widen a concrete leaf
into an abstract root, re-introducing the broad-component problem one level up, and would not reduce the
leaf count anyway (the dispatch partition counts the two sub-variants). Either way the merge adds
redundancy rather than removing it.

The shared abstraction both leaves want already exists: `ChildField.TableTargetField` (`ChildField.java`
405-411 permits both). Consumers that need to treat them uniformly go through it; the consumers that fork
(`CatalogBuilder` ~202/261, `FieldClassification`, `GraphitronSchemaValidator`) switch on the two leaves
*separately*, which is correct, they emit different code.

## Arrival is sealed leaf identity, not a stored slot or a derived predicate

R222's governing test is "assert what nothing else carries; derive what another axis or slot already
forces." Source-arrival cardinality is carried by **leaf identity**: `SingleRecordTableField` is
single-arrival, `RecordTableField` is many-arrival, and the type tells every consumer which it is. So the
axis needs neither a stored `sourceCardinality` slot (it would duplicate leaf identity and the
`LoaderRegistration`-presence that tracks with it, the kind of redundant carrying R290 set out to remove)
nor a separate derived predicate such as `OutputField.sourceArrivesSingle()` (with two leaves that
predicate is a vacuous `instanceof` duplicating the sealed switch the dispatch already forks on, itself
the "predicate over pre-resolved data" smell). The cleanest expression of the axis is the one the model
already has.

Because arrival is leaf identity set at the construction site, this item does **not** depend on R279, and
`depends-on: []` is correct. R279's field-first reachability walk matters only for the seat this item
leaves untouched: a list-arriving `@service` carrier whose data field is built as
`SingleRecordTableField` (single-arrival, no loader) today but should batch. Constructing the
many-arrival `RecordTableField` *there* needs the producing field's list-ness in hand, which is R279's
walk; that reclassification and its DataLoader emit are **R308**.

## Scope

Behaviour-preserving and small. In scope:

- **Rename** `SourceKey.Cardinality {ONE, MANY}` to `SourceKey.ValueCardinality` (mechanical churn across
  the `.cardinality()` / `Cardinality.ONE|MANY` call sites; it stays a payload-free `enum`). Standalone
  clarity: the name reads as generic cardinality but means value multiplicity (target rows per key); the
  rename stops it from being read as the source-arrival axis. ~26 files, no generated-output change.
- **Name the axis** in the model: javadoc on `SingleRecordTableField` / `RecordTableField` (and the
  `TableTargetField` parent) recording that the split *is* source-arrival cardinality and why the two
  must not be merged (disjoint payloads, two emit mechanisms, opposite `BatchKeyField` /
  `OrderingOwnedByProducer` membership). The note describes facts already pinned by the compact
  constructors and the sealed permits, so it is not a free-floating invariant claim.

Out of scope:

- **Any leaf merge** (declined above). The live leaf set stays 48.
- **The list-`@service` carrier reclassification** (a live N+1 defect): a list-arriving `@service`
  carrier is accepted at the carrier (`checkServiceReturnMatchesPayload` admits `List<Payload>`) yet its
  data field is built as `SingleRecordTableField`, an unbatched single-arrival read. Fixing it means
  constructing `RecordTableField` at that site instead; that behavioural change is **R308**
  (`service-list-payload-arrival`), which depends on R279's walk for the carrier-arrival input.
- **R222 Stage 5's** `LoaderRegistration` permit consolidation, which operates on the batched leaf's
  payload only and does not touch the single-arrival leaf.

## Acceptance

- **Corpus / dispatch tier**: byte-identical and leaf-count-unchanged. The rename is model-internal and
  no leaf is added or removed, so the R281/R299 classified corpus and
  `everyGraphitronFieldLeafHasAKnownDispatchStatus` stay green without edit (the partition keeps all 48
  leaves).
- **Naming pinned, not claimed**: the model javadoc names the axis by pointing at the live structural
  facts (the `ResultRowWalk` / no-`LoaderRegistration` compact-constructor invariants, the `BatchKeyField`
  / `OrderingOwnedByProducer` sealed permits), per "Documentation names only live tests/code", no prose
  invariant a test does not already pin.
- **Rename mechanical**: `SourceKeyTest` and the other `Cardinality.ONE|MANY` call sites compile and pass
  against `ValueCardinality` with no behavioural edit.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

