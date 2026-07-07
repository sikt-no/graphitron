---
id: R438
title: "Materialize the join-path facts: JoinStep as (tableExpr target, on)"
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-07-06
last-updated: 2026-07-07
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

## The destination shape (settled in R333)

R333 lines 839-908 fix the model; this item transcribes it into `JoinStep.java` and migrates the
readers. The day-one shape, sketched (names are implementer's latitude, the axes are not):

```java
public sealed interface JoinStep permits JoinStep.Hop, JoinStep.LiftedHop {
    record Hop(
        TableExpr target,          // the table node this step joins to
        On on,                     // how it joins; non-null on every shipped step (see below)
        TableRef originTable,      // denormalized traversal origin, kept mechanically
        JoinConditionRef filter,   // optional per-hop filter appended to the enclosing WHERE
        String alias               // query-local node name, fieldName + "_" + stepIndex
    ) implements JoinStep { }

    record LiftedHop(...) { }      // unchanged; transitional, retired by R431 (see below)
}

public sealed interface TableExpr permits TableExpr.Catalog {
    record Catalog(TableRef table) implements TableExpr { }   // the static generated reference
}

public sealed interface On permits On.ColumnPairs, On.Predicate {
    record ColumnPairs(ForeignKeyRef fk, List<JoinSlot.FkSlot> slots) implements On { }
    record Predicate(JoinConditionRef condition) implements On { }
}
```

- **`TableExpr`** materializes the node: day one only the `Catalog` arm is minted (the static
  generated reference, derivable from the node's table class). The `MethodCall` / `RoutineCall`
  arms are declared destinations that land with their pulling consumers (`@tableMethod` rewire,
  R435's `RoutineCall` + the `Lateral` `on`-arm); minting unpopulated arms up front would repeat
  the horizontal-vocabulary mistake R222 rejected.
- **`on` is non-null day one.** R333's model has `on` absent exactly for the start node, but the
  shipped path representation has no start-node entry: the source supplies the start, `path[0]`
  already joins. The absent-`on` start node becomes representable only when R435's root routine
  entry needs it; minting an optional now would be another unpopulated arm. Every shipped `Hop`
  carries exactly one `on`.
- **`ColumnPairs` keeps the FK as provenance.** Emitters emit `.onKey($T.$L)` from
  `fk().keysClass()` / `fk().constantName()` at five sites (`InlineTableFieldEmitter:164`,
  `InlineLookupTableFieldEmitter:207`, `InlineColumnReferenceFieldEmitter:104`,
  `SplitRowsMethodEmitter:404`, `TypeConditionsGenerator:241`, plus `FkTargetConditionEmitter:135`),
  and `CatalogBuilder` / `NodeIdLeafResolver` / `SourceRowDirectiveResolver` read `fk().sqlName()`
  / `constantName()` for classification and diagnostics. Day one every `ColumnPairs` is FK-derived,
  so `fk` is a non-null component; when R435 adds the PK/UK name-match derivation, the derivation
  becomes its own small seal on the arm and FK becomes one case of it. Not minted now.
- **New capability is a new arm, not a new step type**: a new target arm (`RoutineCall`), a new
  source-side provenance (`Lift`), or a new `on` derivation (PK/UK name-match), per R333.

## Component routing

Where every component of the two deleted variants lands:

| old | new |
|---|---|
| `FkJoin.fk` | `On.ColumnPairs.fk` (provenance, still non-null) |
| `FkJoin.slots` | `On.ColumnPairs.slots` |
| `FkJoin.targetTable` / `ConditionJoin.targetTable` | `Hop.target` (`TableExpr.Catalog.table`) |
| `FkJoin.originTable` | `Hop.originTable`, now on every hop (`parsePath` knows the current source; `ConditionJoin` simply never carried it). Denormalized: it duplicates the previous step's target / the source table. Kept mechanically because `ParentCorrelation:65`, `BuildContext:1776` and `FieldBuilder` read it; deleting it in favor of a path-position derivation is a follow-up, not this reshape. |
| `FkJoin.whereFilter` | `Hop.filter`, retyped `JoinConditionRef` (see R16 absorption) |
| `ConditionJoin.condition` | `On.Predicate.condition`, retyped `JoinConditionRef` |
| `FkJoin.alias` / `ConditionJoin.alias` | `Hop.alias` |

The capability interfaces reshape rather than survive verbatim: `HasTargetTable` (target table +
alias, uniformly true) stays, implemented by both permits. `WithTarget` (slot iteration) loses its
reason to exist as a cross-variant capability: `Hop` answers the pairable-slots question through
`on() instanceof ColumnPairs`, and `LiftedHop` keeps its slot list directly. Readers that today
dispatch `FkJoin | LiftedHop` through `WithTarget` at hop 0 (`SplitRowsMethodEmitter`,
`ParentCorrelation`) re-dispatch on `Hop`-with-`ColumnPairs` vs `LiftedHop`; whether a shared
slot-iteration accessor survives that is the implementer's call at the site.

## What stays: `LiftedHop` is R431's to retire

`LiftedHop` is not a join-path fact. `@reference` path parsing never produces it
(`BuildContext:1311` throws), and four emitters carry defensive unreachable arms for it
(`InlineTableFieldEmitter:167`, `InlineLookupTableFieldEmitter:210`,
`InlineColumnReferenceFieldEmitter:107`, `SplitRowsMethodEmitter:407`). It is produced only by
`FieldBuilder` (accessor / hub derivations) and `SourceRowDirectiveResolver` (leaf-PK shape) and
lives at hop 0 of `SourceKey.path`: its lifted slots are source-side key provenance, R333's `Lift`
arm, which is R431's decomposition. Moving it out of the `JoinStep` seal requires re-typing
`SourceKey.path`, which is exactly the surface R431 dissolves; doing it here would be the
scope-mixing this item exists to avoid. So: `LiftedHop` stays a permit, byte-identical, its javadoc
gaining one line naming R431 as its retirement. The payoff of pulling it out (those four
unreachable arms become type-level impossibilities) is real and belongs to R431; note it there.

## Absorbs R16 (`fkjoin-model-cleanup`)

R16's remaining scope is the type conflation: `FkJoin.whereFilter` and `ConditionJoin.condition`
are both bare `MethodRef` yet share a fixed `(srcAlias, tgtAlias)` calling convention
(`JoinPathEmitter.emitTwoArgMethodCall`), while `ConditionFilter` implements the same `MethodRef`
with a different convention. The reshape types the convention: a `JoinConditionRef` wrapper (final
name at implementer's discretion) carried by both `On.Predicate` and `Hop.filter`, and
`emitTwoArgMethodCall` takes the wrapper directly so call sites stop extracting raw `MethodRef`s.
R16's naming complaint dissolves structurally: the ON-clause condition and the WHERE-appended
filter become different components with accurate names (`on` vs `filter`) instead of one
overloaded `whereFilter`. R16 closes when this lands; its file is deleted in the same commit that
deletes the flat variants.

## Consumer inventory

The item's original list undercounted. Variant-dispatching code (`case` / `instanceof` on the
three permits) lives in 18 files, 62 sites: `BuildContext` (19), `FieldBuilder` (7),
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `InlineColumnReferenceFieldEmitter`,
`SplitRowsMethodEmitter` (4 each), `ParentCorrelation` (4), `CatalogBuilder` (3),
`TypeConditionsGenerator`, `FkTargetConditionEmitter`, `TypeBuilder` (2 each), and single sites in
`GraphitronSchemaValidator`, `NodeIdLeafResolver`, `SourceRowDirectiveResolver`, `ServiceCatalog`,
`MultiTablePolymorphicEmitter`, `TypeClassGenerator`, `TypeFetcherGenerator`. The `LiftedHop` arms
among these stay; the `FkJoin` / `ConditionJoin` arms migrate.

## Transition plan

Additive-then-cutover (R222 / R431's technique); the pipeline / execution tiers hold at every
intermediate commit. Slicing:

1. **Type the convention (R16 core).** Mint `JoinConditionRef`, retype `FkJoin.whereFilter` and
   `ConditionJoin.condition`, make `emitTwoArgMethodCall` take it. Self-contained, no seal change.
2. **Mint the axes.** Add `TableExpr` (`Catalog` only), `On`, and the two-axis `Hop` as a fourth
   permit. The compiler lists every exhaustive switch; new `Hop` arms initially delegate or throw
   `unreachable` (nothing produces a `Hop` yet), so this commit is inert.
3. **Cut producers over, reader by reader behind the compiler.** `BuildContext.parsePath` /
   `parsePathElement` / `synthesizeFkJoin` produce `Hop`; every `@reference`-path reader's
   `FkJoin` / `ConditionJoin` arms move onto the `Hop` arm (dispatching on `on`). `SourceKey`'s
   FK-composed paths get `Hop` lists for free (the `@sourceRow` resolver reuses `parsePath`).
   This can land as several commits, one reader cluster each, because dual-sourcing keeps the old
   arms alive until their last producer dies.
4. **Delete.** Remove `FkJoin` / `ConditionJoin`, the dead `WithTarget` plumbing, and the
   defensive arms the compiler now rejects. Delete R16's file, changelog both IDs.

## Acceptance

- **Generated output is byte-identical.** This is a model reshape with no behavior surface; the
  sakila golden files and the execution tier must not move. Any diff in generated code is a bug in
  the reshape.
- Pipeline and execution tiers green at every intermediate commit, not just the endpoint.
- `JoinStep.java`'s javadoc (the variant-contrast table, the cardinality invariant) survives the
  reshape rewritten against the axes, not deleted.

## Out of scope, and coordination

- `MethodCall` / `RoutineCall` target arms, the `Lateral` `on`-arm, PK/UK name-match derivation,
  the absent-`on` start node, and the `@oneOf` SDL path-element surface: all R435 (or its
  follow-ons), which lands them on these axes.
- `Lift` source-side provenance, `LiftedHop` retirement, `SourceKey.path` re-typing: R431.
- The R381 path-walker lift (`step(currentSource, hopElement)` over an `FkEdge` abstraction)
  touches the same seam; coordinate so the lifted stepper reads the two-axis step, not the flat
  seal.

Why eager rather than pulled by R435 itself: R435's design (routine table nodes, order-significant
directive composition) needs the target arm and the `on` axis to exist before it can add
`RoutineCall` and `Lateral`; folding this reshape into R435 would make one item carry both a
model-substrate pivot and a feature surface, the scope-mixing R431 was split out of R314 to avoid.
