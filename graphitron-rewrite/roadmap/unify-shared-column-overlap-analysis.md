---
id: R356
title: "Unify the per-column shared-column overlap analysis across mutation write paths"
status: Spec
bucket: architecture
priority: 6
theme: nodeid
depends-on: []
created: 2026-06-22
last-updated: 2026-06-24
---

# Unify the per-column shared-column overlap analysis across mutation write paths

The "group writers by backing column, keep groups of two-or-more, an all-plain overlap is a
build-time reject and a decode-involving one needs a runtime value-agreement check" analysis is
hand-rolled in six places, accreted one per write surface as R322/R354/R342 closed the
agreement gap:

1. `JooqRecordInstantiationEmitter.analyzeOverlap` (`@service`, over `Writer` = `ColumnBinding | RecordKeyDecode`, returning `LinkedHashMap<String, List<SlotRef>>` filtered to size-two-or-more).
2. `TypeFetcherGenerator.insertColumnPlan` (INSERT, over flattened `InputField.SetField` leaves, returning the typed `List<InsertCol>` with `shared()`).
3. `MutationInputResolver.collectSetColumns` / `rejectPlainColumnCollision` (validate-time, over the `InputField` tree, building `pathsByColumn` + `columnsWithDecode`, rejecting size-two-or-more with no decode).
4. `TypeFetcherGenerator.emitSetAgreementPreamble` (single-row within-SET, over `List<SetGroup>`, an inline `LinkedHashMap<String, List<int[]{groupIndex, slot}>>`).
5. `TypeFetcherGenerator.emitKeySetAgreementPreamble` (R354's cross-partition WHERE ∩ SET intersection, over two `List<SetGroup>` partitions, recording `int[]{keyGi, keySlot, setGi, setSlot}`).
6. `setColumnPlan` (the bulk-SET dedup R342 shipped, over `List<SetGroup>`, mirroring `insertColumnPlan`).

Three carrier families feed these: `Writer` (`@service`), `InputField.SetField` leaves (INSERT and the
validator), and `SetGroup` (the three UPDATE-SET surfaces). Each exposes the same three things the
analysis reads: the list of target `ColumnRef`s, whether the carrier involves a `@nodeId` decode
(`nidk != null` / `extraction instanceof NodeIdDecodeKeys`), and a dotted access path used only for the
agreement message. R322's D1 and R354 both flagged this as a "same predicate, multiple consumers, drift
risk" smell and named R342 as the place to consider the lift; R342 deliberately declined to bundle
the refactor into a feature slice and filed it here.

## Design

This is the canonical Generation-thinking smell: "if two consumers evaluate the same predicate over a
model field, the branch belongs in the model. The same predicate evaluated by multiple consumers is a
sign the resolver is under-specified, and an opportunity for one site to drift from another." Six
consumers re-derive the same grouping, and because one of them is the validator (the all-plain reject)
while the rest are emitters (the decode-involving agreement), the lift also makes
*validator-mirrors-classifier* structural rather than hoped-for: the reject and the agreement read one
fact instead of two hand-rolled walks that can diverge.

### A shared typed builder primitive, not a model-carried fact

R356's Backlog framing called for lifting the column-overlap fact "into the model carrier." This spec
deliberately revises that. The grouping is a pure structural fold over `ColumnRef.sqlName()` values that
are *already* fully resolved; there is no under-resolved input the model fails to carry, so
Generation-thinking's "model carries what the generator needs" does not actually apply. The decisive
constraint is timing: `rejectPlainColumnCollision` runs at resolution time over the `InputField` tree
and produces a `Resolved.Rejected`, while the emit carriers (`SetGroup`, `JooqRecord`, the flattened
INSERT leaves) do not exist until emit time (`setGroupsOf` is called inside the fetcher build). A fact
stored on the emit carriers could not be read by the validator, which would then have to keep its own
hand-rolled walk: a worse split-brain than today, and exactly the divergence the lift exists to remove.

The honest altitude is therefore a shared, pure grouping *function* invoked once at each site, with a
typed result, governed by "Builder-step results are sealed" and "Sub-taxonomies for resolution
outcomes". Introduce, in a home importable by both `MutationInputResolver` and the generators (the
`model` package, alongside `ColumnRef`):

- `ColumnWriter`: a minimal read-only view exposing `List<ColumnRef> targetColumns()`, `boolean
  decode()`, and `String label()`. Each of the three carrier families adapts into it at its site
  (a non-invasive mapping, not a shared base class the private carrier records implement). Every
  consumer reads all three accessors, so the view carries no dead fields per consumer (the failure mode
  R342 rejected for a god-type).
- `Contributor(ColumnWriter writer, int slot, ColumnRef column)`: one writer's contribution to one
  column, the generalization of `analyzeOverlap`'s `SlotRef`, `insertColumnPlan`'s `InsertColWriter`, and
  R342's `setColumnPlan` `SetColWriter`. Typing this retires the raw `int[]{gi, slot}` tuples in sites 4
  and 5 (the "resolution outcome stored as a raw structure" smell).
- `OverlapColumn(ColumnRef column, List<Contributor> contributors)` with `shared()` (two-or-more
  contributors) and `allPlain()` (no contributor is a decode), the generalization of `InsertCol` and
  R342's near-identical clone `SetCol`.
- `List<OverlapColumn> groupByColumn(List<? extends ColumnWriter> writers)`: groups by `sqlName()` in
  writer-encounter order, keeps *every* column (size-one included, as `insertColumnPlan` already does),
  so each consumer filters by the predicate it forks on rather than the primitive pre-filtering.

Each consumer then reads off the plan: site 1 routes to its agreement emission when `anyMatch(shared)`
and iterates the `shared()` columns; sites 2 and 6 walk all columns for the column/cell list and the
`shared()` ones for agreement; site 3 rejects on `shared() && allPlain()`; site 4 emits the preamble for
`shared() && !allPlain()` columns.

### The load-bearing invariant: slot is the decode-record slot order

`slot` is the index of the column within the contributor's `targetColumns()`, and the agreement emit
reads it back as `value<slot+1>()` off a decode `Record<N>`. Nothing in the type system stops a carrier
adapter from returning columns in a different order than its decode-record slots, which would silently
misread the wrong slot. The `ColumnWriter` contract must state that `targetColumns()` ordering *is* the
decode-record slot order, and the unit test (below) pins it. This is the single invariant the whole lift
rests on.

### The per-contributor value-read seam (in scope)

Shipping the grouping alone would leave a second duplication intact: the `decode ? readSlot(value<slot+1>())
: readMapValue(path)` value-read, hand-rolled at `agreeValueExpr` (site 1), `insertWriterValue` (site 2),
inline in `emitSetAgreementPreamble` (site 4), and `appendAgreementValue` (site 5). That read forks on
the same `decode` flag the grouping already classifies, so by the same Generation-thinking rule it
belongs resolved once. R356 routes the remaining `@mutation` agreement sites through the value-read seam
R354 introduced and R342 already reused for the bulk path (`appendAgreementValue` /
`emitAgreementDecodeLocal`, today serving sites 5 and 6): site 2's `insertWriterValue` and site 4's
inline re-decode and present-value read flow through the one helper. The
`@service` site (site 1) reads from a different prepare-phase local model (`<base>Value` / `<base>Keys`
with a nullable-decode three-way), is the lone instance of that shape with no drift partner, and folding
it into the shared seam is the implementer's call at In Progress, not a requirement: site 1's universal
gain is the grouping primitive.

What R356 does *not* unify is the outer gather-and-pairwise loop (`List<Object>` gather, presence-guarded
adds, the `for` loop of pairwise `requireColumnAgreement` calls). That is the "unify the emit walks behind
a helper" move R356's Backlog body explicitly excludes; the loop is mechanical, identical across sites,
already low-drift, and lives wholly at emit. It is named in Out of scope so the residual is explicit, not
silent.

### Cross-partition site 5 stays a named sibling

`emitKeySetAgreementPreamble` is a different operation: it intersects two partitions (key groups and set
groups), carries a which-side dimension and two decode-group sets, and short-circuits on
`keyByColumn.get(...) == null` rather than materializing a full grouping. Routing it through
`OverlapColumn` would either widen `Contributor` with a nullable partition tag the within-clause sites
never set (the dead-field smell, relocated) or call `groupByColumn` twice and intersect, which is not
what it does. It shares the *leaf* `ColumnWriter` view (its key/set groups adapt into `ColumnWriter` for
the value-read seam) but keeps its bespoke intersection walk. Per the same family-resemblance logic R342
applied, a single site with no drift partner does not earn an extracted `intersectByColumn` primitive; it
stays a clearly-named sibling.

### Byte-identical when no overlap

Computing the grouping always is cheap metadata (a list allocation reading `sqlName()`) and cannot
perturb emission, *provided* each rewritten site keeps the `if (shared) coalesced-agreement-emit else
<verbatim pre-lift per-writer emit>` shape `buildPerCellValueListDeduped` already demonstrates. The
disjoint (size-one) branch must reuse the existing per-writer emitters (`emitColumnBinding` /
`emitKeyDecode`, `emitInsertCell`, the verbatim `cells.add` / `sets.put`), not a size-one special case of
the shared coalesce path (which would emit a gather-of-one where the old code emitted a bare value, and
regress the predecessors' "non-overlapping emission is byte-identical" guarantee). Pinned by compile +
execute, never a generated-body string assertion.

## Scope decisions (design forks, resolved with `principles-architect`)

- **Shared function, not a model-carried fact.** See Design. The validator runs before the emit carriers
  exist, so a per-carrier-family stored fact forces the validator to keep its own walk; and the grouping
  is a fold over already-resolved columns, so Generation-thinking's model-fact pattern misapplies. The
  governing principles are "Builder-step results are sealed" and "Sub-taxonomies for resolution
  outcomes": one `groupByColumn` over a typed `ColumnWriter` view returning a typed `OverlapColumn`.
- **(A) the grouping plus the per-contributor value-read seam; (B) the gather-loop deferred.** The
  value-read forks on the same `decode` flag the grouping classifies, so it is part of the same lift;
  leaving it out would leave the more drift-prone half of the duplication standing. The outer
  gather-and-compare loop is the emit-walk unification R356's body declines, and stays out.
- **Site 5 keeps its intersection walk.** Shares the leaf view, not the grouping. One site, no drift
  partner, so no extracted primitive.
- **`@service` value-read fold is optional.** Site 1 shares the grouping universally; whether its
  prepare-phase value-read joins the seam is decided at In Progress.

## Implementation

All under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`.

- **`model/` (new file, e.g. `ColumnOverlap`)**: the `ColumnWriter` view, `Contributor`, `OverlapColumn`
  (`shared()` / `allPlain()`), and `groupByColumn`. The `ColumnWriter` javadoc states the
  `targetColumns()`-is-decode-record-slot-order contract.
- **`generators/JooqRecordInstantiationEmitter.java`** (site 1): replace `analyzeOverlap` + the local
  `SlotRef` record with a `Writer`-to-`ColumnWriter` adapter feeding `groupByColumn`; `emitWithAgreement`
  reads `shared()` columns off the plan. `agreeValueExpr` (the prepare-phase value-read) stays unless the
  implementer folds it into the seam.
- **`generators/TypeFetcherGenerator.java`**: `insertColumnPlan` (site 2) and `setColumnPlan` (site 6)
  delegate to `groupByColumn`, retiring their near-identical typed plans (`InsertCol` / `InsertColWriter`
  and R342's clone `SetCol` / `SetColWriter`) onto `OverlapColumn` / `Contributor`; `emitSetAgreementPreamble`
  (site 4) replaces its inline `byColumn` map / `int[]` tuples with the primitive; the value-read seam
  (`appendAgreementValue` / `emitAgreementDecodeLocal`, already serving sites 5 and 6) absorbs site 2's
  `insertWriterValue` and site 4's inline read; `emitKeySetAgreementPreamble` (site 5) adopts the
  `ColumnWriter` leaf view, keeping its intersection walk. Correct the now-doubly-stale comment at
  `:2796-2801` ("the carrier-agnostic writer lift stays R342's"): R342 declined that lift and has since
  shipped, so the lift is R356's and R356 is what generalizes the two named helpers; the comment must
  describe what is pinned, not name the wrong item (per "Documentation names only live tests/code").
- **`MutationInputResolver.java`** (site 3): `collectSetColumns` / `rejectPlainColumnCollision` adapt the
  `InputField` tree into `ColumnWriter`s (recursing `NestingField` as today) and read `shared() &&
  allPlain()` for the reject; the reject message still names the first two offending paths via the
  contributors' labels.

No directive, model-carrier, wire-format, or behavior change. The dispatch partitions and `Rejection`
taxonomy are untouched.

## Tests

Per the tiers in `rewrite-design-principles.adoc`. This is a pure refactor: no behavior changes, so the
load-bearing net is the *inherited* coverage proving equivalence, plus one focused unit test for the new
primitive.

- **Unit (`@UnitTier`) for `groupByColumn`**: grouping by `sqlName()` in encounter order; every column
  kept (size-one included); `shared()` at two-or-more; `allPlain()` iff no contributor decodes; and the
  slot-ordering pin (a composite decode's contributors carry slots that index `targetColumns()` in
  decode-record order). This is the structural anti-drift assertion: the same `ColumnWriter` list yields
  the predicate the validator rejects on and the predicate the emitters trigger agreement on.
- **Regression net (inherited, unchanged)**: the existing execution and pipeline coverage is the proof of
  no behavior change: `NodeIdValueAgreementExecutionTest` (the `@service` / INSERT / single-row-SET
  agree/disagree/presence matrix), `SelfFkNodeIdInsertExecutionTest` / `SelfFkNodeIdUpdateExecutionTest`,
  the R342 bulk execution cases, `MutationDmlNodeIdClassificationTest` and `JooqRecordServiceParamPipelineTest`
  (the all-plain rejects and the decode-involving admits), `UpdateRowsWalkerTest`, and
  `RejectionSeverityCoverageTest`. All must stay green with no assertion edits; if any needs editing, the
  lift changed behavior and the change is wrong.
- **Byte-identity**: the bulk of existing mutation execution and `graphitron-sakila-example` compilation
  is no-overlap input, so the "disjoint branch emits verbatim" property is pinned by the full
  `mvn install -Plocal-db` reactor (compile + execute), never a generated-body string assertion.

## Out of scope

- **The gather-and-pairwise-loop emitter unification**: the outer `List<Object>` gather + presence-guarded
  adds + pairwise `requireColumnAgreement` loop. Mechanical, identical across sites, low-drift, and the
  emit-walk helper R356's body deliberately excludes. Named here so the residual is explicit; fileable as
  its own item only if it ever drifts.
- **The cross-partition grouping** (site 5's intersection): stays a bespoke named walk sharing only the
  leaf view.
- **Any behavior change**: agreement semantics, presence rules, null handling, the shared predicate
  `requireColumnAgreement`, the dispatch partitions, and the `Rejection` taxonomy are all untouched.
- **Non-Postgres dialects**, consistent with the rest of the DML surface.

## Relationship to other items

- **R342** (Done): shipped site 6 (`setColumnPlan`) as a disciplined clone of `insertColumnPlan`, the
  immediate predecessor that brought the site count to six. R356 folds all six onto the shared primitive
  in one pass rather than leaving a seventh clone to retire later.
- **R322** (Done): introduced the grouping in per-path instantiations and gave the agreement *predicate*
  its single home (`requireColumnAgreement`); named this structural-grouping lift as the residual.
- **R354** (Done): added site 5 (the cross-partition WHERE ∩ SET preamble) and the partial
  `appendAgreementValue` / `emitAgreementDecodeLocal` seam this item generalizes into the value-read.
- **R328** (Done): made the self-FK shared-column overlap reachable via a natural self-FK shape.
