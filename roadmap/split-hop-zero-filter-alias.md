---
id: R450
title: "Split-path hop-0 condition filter binds the same alias as source and target"
status: Ready
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

1. **One arm, one decision (model + classifier).** The correlation arm choice moves to: a
   hop-0 `Hop` carrying a non-null `filter()` lands the parent-anchor arm regardless of its
   `On`; filter-less FK hops keep `OnFkSlots`. The parent-anchor arm is the renamed
   generalization of `OnConditionJoin` carrying only the topology payload
   (`firstHop`, `parentTable`): "`parentInput` joins the parent table on its PK; hop 0 then
   attaches off `parentAlias`". It exposes **no** `condition()` accessor (a partial accessor
   whose meaning depends on the occupant is the axis smell); consumers dispatch the hop-0
   attach on `firstHop.on()` per `JoinStep`'s own two-axis model: `ColumnPairs` renders the
   ordinary forward join, `Predicate` the existing two-arg condition call.
   `buildParentCorrelation` is the single shared producer, so the reclassification applies to
   inline carriers too; the inline `ColumnPairs`-occupant emission is behaviour-identical
   (parent already in scope, same slot correlation), pinned by the existing inline fixtures.
2. **Grain is a projection off the arm (classifier).** `ParentCorrelation` gains a
   key-columns accessor beside the existing `parentKeyOwnerTable()`; `deriveSplitQuerySource`
   builds the correlation first and reads entry columns off it instead of re-deriving from
   the path. Parent-PK grain iff parent-anchor topology becomes structurally impossible to
   violate; the alternative (a second `filter() != null` branch in `deriveSplitQuerySource`)
   is two producer sites evaluating one predicate with nothing binding them, exactly the
   R338 silent-zero-rows drift `deriveSplitQuerySource`'s own javadoc warns about.
3. **Same-commit consumer audit.** Every switch over `ParentCorrelation` (three inline
   emitters, `TypeFetcherGenerator`, the split-rows siblings) is audited in the same commit
   as the producer change; the previous `OnConditionJoin` arms re-dispatch on
   `firstHop.on()`. Sealed exhaustiveness makes the compiler surface each site.
4. **Emitter.** `buildWhereCondition` receives the parent-side alias and uses it as hop-0
   source under the parent-anchor arm. Under `OnFkSlots` a hop-0 filter is
   classifier-unreachable once (1) lands: throw tersely citing the classifier guarantee,
   matching the existing classifier-unreachable throws in the same file.
5. **Non-table-backed split parents (record / service shapes).** No parent table exists to
   anchor, and Check 2 silently skips when `originTable` is null, so the broken shape
   classifies unverified today. Route the rejection through the existing
   `ParentCorrelationResolution.AuthorError` pathway (the same channel that already rejects a
   `Predicate` hop-0 with no parent `@table`), landing `AuthorError.Structural`: the filter's
   source row is not a catalog table; the message names the escape hatch (filter on a later
   hop, or the terminal `@condition` surface).
6. **Housekeeping absorbed from R449** (its 2026-07-08 principles consult flagged the
   same-file merge hazard): repair the three stale pre-flip topology javadoc spots in
   `SplitRowsMethodEmitter` (`emitFromBridgeAndParentJoin`'s `.from(terminalAlias)` opener,
   the flat-SELECT comment, `buildSingleMethod`'s javadoc) while reworking the file — they
   describe the retired terminal-back walk and contradict the shipped `parentInput`-anchored
   start-first paragraphs in the same javadocs.

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
