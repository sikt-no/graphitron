---
id: R438
title: "Materialize the join-path facts: JoinStep as (tableExpr target, on)"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-07-06
last-updated: 2026-07-06
---

# Materialize the join-path facts: JoinStep as (tableExpr target, on)

The shipped `JoinStep` is a flat `FkJoin | ConditionJoin | LiftedHop` seal; R333's join-path
resolution shows a step is really **two orthogonal facts**: a **target** (a table node materialized
by `tableExpr`) and an **`on`** (how the step joins: FK-derived column pairs or an authored
predicate), with `on` absent exactly for the start node. This item is the eager, mechanical
materialization of that corner of R333, the join-path twin of R431's source-side decomposition:
R431 decomposes what the *source* endpoint conflates, this decomposes what the *step* conflates.
Sequenced ahead of the consumers that extend the join path (R435's routine nodes) so they land on
decomposed facts instead of adding a fourth flat variant to the seal.

Destinations, settled in R333 (2026-06-26/2026-07-05 design sessions):

- **`JoinStep(target, on)`**: `target` is a table node; `on` is `ColumnPairs | Predicate`, absent
  only for the start node. The old flat variants are recovered as the axis product: `FkJoin` =
  (`Catalog` target, `ColumnPairs` from FK); `ConditionJoin` = (target, `Predicate`); `LiftedHop`'s
  lifted slots are source-side provenance (R431's territory), not a step kind.
- **`TableExpr`** materializes the node: day one only the `Catalog` arm is populated (the static
  generated reference, derivable from the node's table class). The `MethodCall` / `RoutineCall`
  arms are declared destinations that land with their pulling consumers (`@tableMethod` rewire,
  R435's `RoutineCall` + the `Lateral` `on`-arm); minting unpopulated arms up front would repeat
  the horizontal-vocabulary mistake R222 rejected.
- **New capability is a new arm, not a new step type**: a new target arm (`RoutineCall`), a new
  source-side provenance (`Lift`), or a new `on` derivation (PK/UK name-match), per R333.

**Absorbs R16** (`fkjoin-model-cleanup`): typing the `Predicate` arm *is* the `JoinConditionRef`
wrapper R16 wants; the `whereFilter`-misnomer rename happens as part of the reshape rather than as
a separate pass over the same call sites.

**Consumers to migrate** (the readers of the flat variants): `BuildContext.parsePath` /
`parsePathElement` / `synthesizeFkJoin`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`,
`SplitRowsMethodEmitter`, `JoinPathEmitter`, `TypeConditionsGenerator`, `FkTargetConditionEmitter`,
`NodeIdLeafResolver`. Transition technique: additive-then-cutover (R222 / R431's technique):
introduce the two-axis step alongside the flat seal, dual-source, migrate consumers behind the
compiler, delete the flat variants; the pipeline/execution tiers hold at every intermediate commit.

Why eager rather than pulled by R435 itself: R435's design (routine table nodes, order-significant
directive composition) needs the target arm and the `on` axis to exist before it can add
`RoutineCall` and `Lateral`; folding this reshape into R435 would make one item carry both a
model-substrate pivot and a feature surface, the scope-mixing R431 was split out of R314 to avoid.
The R381 path-walker lift (`step(currentSource, hopElement)` over an `FkEdge` abstraction) touches
the same seam; coordinate so the lifted stepper reads the two-axis step, not the flat seal.
