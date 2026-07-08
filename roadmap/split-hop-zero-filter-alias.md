---
id: R450
title: "Split-path hop-0 condition filter binds the same alias as source and target"
status: In Review
bucket: bug
theme: structural-refactor
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Split-path hop-0 condition filter binds the same alias as source and target

## Review feedback (In Review → Ready, 2026-07-08)

Independent review of `cf2c34c`. The behavioural delivery is complete and approved as-is: the
`OnParentJoin` rename with no `condition()` accessor, the single-producer reclassification in
`buildParentCorrelation`, the `parentKeyColumns()` grain projection, the consumer audit (three
inline emitters re-dispatch on `firstHop.on()`; the split-rows siblings anchor `parentAlias`;
`TypeFetcherGenerator` holds no `ParentCorrelation` switch, verified by sealed exhaustiveness and
a repo-wide grep), the `AuthorError.Structural` routing, and all four spec-named test tiers
including the execution-tier grain proof. Full reactor green under `-Plocal-db`. Do not rework any
of that.

**One item did not ship: Design item 6** (the R449-absorbed javadoc housekeeping). All three stale
pre-flip topology spots remain in `SplitRowsMethodEmitter` and still describe the retired
terminal-back walk while the code emits `.from(parentInput)` start-first:

1. `emitFromBridgeAndParentJoin`'s javadoc opener (~line 398): "`.from(terminalAlias)`, the
   bridging-hop chain back to step 0" — contradicting the R435 start-first paragraph later in the
   same javadoc.
2. The flat-SELECT comment in `buildListMethod` (~line 924): "Flat SELECT: FROM terminal, JOIN
   bridging hops back toward step 0".
3. `buildSingleMethod`'s javadoc (~lines 983-984): "The shared topology projects and FROMs off
   `terminalAlias` and bridges multi-hop paths back to step 0".

This cannot be dropped silently: R449's principles consult re-homed the repair here precisely
because of the same-file merge hazard, so if R450 goes Done without it no item carries it. The
rework pass is those three comment repairs only (plus collapsing the shipped design items below to
`shipped at cf2c34c` notes per plan-housekeeping convention), then back to In Review.

**Rework (2026-07-08):** the three javadoc repairs shipped at `1c0126d`; all three spots now
describe the R435 start-first topology (`FROM parentInput`, step-0 attach per correlation arm,
forward bridging hops out to the terminal), and a phrasing sweep found no further back-walk
residue in the file. The shipped design items below are collapsed per the plan-housekeeping
convention.

## Problem

`SplitRowsMethodEmitter.buildWhereCondition` emits a hop's `condition:` WHERE filter as
`method(srcAlias, tgtAlias)` with `srcAlias = i == 0 ? firstAlias : aliases.get(i - 1)`, but
`firstAlias` *is* `aliases.get(0)`: a filter on hop 0 of a `@splitQuery` path binds the hop-0
target alias to both parameters, `method(firstAlias, firstAlias)`. Latent since the file's
creation (predates R435; found during the R435 second-pass review) and unreachable today only
because no fixture authors a `{key:/table:, condition:}` element at position 0 of a split
path. All three cardinality siblings (list, single, connection) and the record-backed entry
points share `buildWhereCondition`, so every batched shape is affected.

The model already pins the correct contract, which makes this an emitter contradicting a
validated invariant rather than an open design question: `JoinStep.Hop.filter()` is documented
as a WHERE condition method called `(originTable, targetTable)`, hop 0's `originTable` is the
parent table, build-time Check 2 (`BuildContext.validateWhereFilterParamTables`) validates the
method's concretely-typed parameters against exactly that pair, and the inline emitters honour
it (`resolveSourceAlias` passes the parent alias at hop 0). The split emitter therefore
generates a guaranteed javac incompatible-types error when the author typed the source
parameter concretely (Check 2 promised them that alias), and silently wrong SQL when the
parameters are wildcard `Table<?>` or the path is self-referential.

## Why this is not a one-line alias swap

The split query's FROM anchor is `parentInput`, a `VALUES` table of batch-key columns. Under
`ParentCorrelation.OnFkSlots` the parent *table* is not in the query at all: `firstAlias`
joins directly against `parentInput` on the FK-slot columns, and the batch key
(`FieldBuilder.deriveSplitQuerySource`) is the FK source-side column tuple. Two consequences:

* There is no parent-typed alias to hand the filter's source parameter.
* The batch *grain* is wrong, independent of any alias: parents sharing FK-slot values share
  one key tuple and receive identical rows, but a hop-0 filter reads arbitrary parent columns,
  so two such parents can legitimately require different filter verdicts. A filter over the
  parent row makes the parent's identity part of the fetch's inputs; slot-tuple keying
  under-specifies it.

`ParentCorrelation.OnConditionJoin` already models the correct topology for its own case:
`parentInput` carries the parent PK, `.join(parentAlias)` on the PK pairs, then hop 0 attaches
off `parentAlias` via the two-arg condition method. A hop-0 filter needs exactly that
parent-join topology with the hop's own `On` doing the attach.

## Design

Shaped with the principles consult (2026-07-08); the load-bearing choice is that grain and
topology are *one* decision made at *one* producer, with the type enforcing their coherence.

1. **One arm, one decision (model + classifier).** Shipped at `cf2c34c`: hop-0 `filter()`
   lands the parent-anchor `OnParentJoin` arm (no `condition()` accessor; consumers dispatch
   the hop-0 attach on `firstHop.on()`), single producer `buildParentCorrelation`.
2. **Grain is a projection off the arm (classifier).** Shipped at `cf2c34c`:
   `parentKeyColumns()` accessor; `deriveSplitQuerySource` reads entry columns off the
   correlation.
3. **Same-commit consumer audit.** Shipped at `cf2c34c`: three inline emitters re-dispatch on
   `firstHop.on()`; split-rows siblings anchor `parentAlias`; `TypeFetcherGenerator` holds no
   `ParentCorrelation` switch.
4. **Emitter.** Shipped at `cf2c34c`: `buildWhereCondition` binds the parent-side alias as
   hop-0 source under the parent-anchor arm; classifier-unreachable throw under `OnFkSlots`.
5. **Non-table-backed split parents (record / service shapes).** Shipped at `cf2c34c`:
   rejection through `ParentCorrelationResolution.AuthorError`, landing
   `AuthorError.Structural` with the escape-hatch message.
6. **Housekeeping absorbed from R449.** Shipped at `1c0126d` (rework pass): the three stale
   pre-flip topology javadoc spots in `SplitRowsMethodEmitter` now describe the shipped
   `parentInput`-anchored start-first walk.

## Tests

Pipeline tier primary; no code-string assertions on generated method bodies.

* **Pipeline**: a `@splitQuery` field with a hop-0 `{key:, condition:}` element on a
  table-backed parent asserts the parent-PK `sourceKey` and the parent-anchor correlation arm
  (this is the coverage whose absence kept the bug latent); a sibling fixture with the filter
  on hop 1 asserts the slot-tuple key and `OnFkSlots` are unchanged; an inline fixture with a
  hop-0 filter asserts the parent-anchor arm lands there too (behaviour pinned by the existing
  inline execution tests).
* **Execution (PostgreSQL)**: the grain proof, non-negotiable — it is the behavioural enforcer
  of grain-topology coherence. Seed two parents sharing the same FK-slot value where the hop-0
  filter passes for one parent and fails for the other; assert the split form reproduces the
  inline form's per-parent rows exactly. A slot-keyed batch cannot pass this test (both
  parents would receive identical rows).
* **Rejection fixture**: hop-0 filter on a split path under a record-backed parent asserts the
  `Structural` arm and message.
* **Unit**: compact-constructor invariants of the parent-anchor `ParentCorrelation` arm and
  its key-columns projection.

## Out of scope

* The root-fetcher emission duplication in `TypeFetcherGenerator` (R449 carries that).
* Inline *behaviour* (already correct; inline emitters change only mechanically, re-dispatching
  the renamed arm on `firstHop.on()`) and non-hop-0 split filters (already correct; the sibling
  fixture pins them).
* Any new authoring surface: this item changes which query the existing surface generates,
  nothing about the SDL.
