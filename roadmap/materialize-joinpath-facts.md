---
id: R438
title: "Materialize the join-path facts: JoinStep as (tableExpr target, on)"
status: In Review
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
    record ColumnPairs(ForeignKeyRef fk, List<JoinSlot.FkSlot> slots)
            implements On, HasSlots { }
    record Predicate(JoinConditionRef condition) implements On { }
}
```

- **`TableExpr`** materializes the node: day one only the `Catalog` arm is minted (the static
  generated reference, derivable from the node's table class). The `MethodCall` / `RoutineCall`
  arms are declared destinations that land with their pulling consumers (`@tableMethod` rewire,
  R435's `RoutineCall` + the `Lateral` `on`-arm); minting unpopulated arms up front would repeat
  the horizontal-vocabulary mistake R222 rejected.
- **`on` is non-null day one, and stays non-null.** R333's model has `on` absent exactly for the
  start node, but the shipped path representation has no start-node entry: the source supplies the
  start, `path[0]` already joins. The forward contract for R435's root routine entry is a **sealed
  sibling, not an optional**: R333 line 839 models the path as a start node *followed by* join
  steps, so the start node arrives as its own variant or as a `(start, List<Hop>)` path carrier,
  never by widening `Hop.on` to `Optional<On>` (line 858's "absent" reading). Widening would
  reintroduce a null-in-exactly-one-case state and forfeit the every-`Hop`-joins certainty the
  non-null component buys.
- **`ColumnPairs` deliberately carries more than R333's literal sketch.** R333 line 860 writes
  `ColumnPairs(List<(sourceCol, targetCol)>)` with no FK, treating the FK as pure derivation. But
  emitters emit `.onKey($T.$L)` from `fk().keysClass()` / `fk().constantName()` at six sites
  (`InlineTableFieldEmitter:164`, `InlineLookupTableFieldEmitter:207`,
  `InlineColumnReferenceFieldEmitter:104`, `SplitRowsMethodEmitter:404`,
  `TypeConditionsGenerator:241`, `FkTargetConditionEmitter:135`), and a bare pair list would force
  `.on(col.eq(col))` instead, changing generated output against this item's own byte-identical
  acceptance. So `fk` is a required non-null component, not a deviation to correct back to R333's
  sketch: emitters read `fk` for `.onKey` legibility, correlation and split-rows readers read
  `slots` for key tuples; the two coexist by design. `CatalogBuilder` / `NodeIdLeafResolver` /
  `SourceRowDirectiveResolver` additionally read `fk().sqlName()` / `constantName()` for
  classification and diagnostics. When R435 adds the PK/UK name-match derivation, the derivation
  becomes its own small seal on the arm (FK-derived emits `.onKey`, name-matched emits explicit
  column equality) and FK becomes one case of it. Not minted now.
- **New capability is a new arm, not a new step type**: a new target arm (`RoutineCall`), a new
  source-side provenance (`Lift`), or a new `on` derivation (PK/UK name-match), per R333.

## Component routing

Where every component of the two deleted variants lands:

| old | new |
|---|---|
| `FkJoin.fk` | `On.ColumnPairs.fk` (provenance, still non-null) |
| `FkJoin.slots` | `On.ColumnPairs.slots` |
| `FkJoin.targetTable` / `ConditionJoin.targetTable` | `Hop.target` (`TableExpr.Catalog.table`) |
| `FkJoin.originTable` | `Hop.originTable`, now on every hop (`parsePath` knows the current source; `ConditionJoin` simply never carried it). Denormalized: it duplicates the previous step's target / the source table. Kept mechanically because `ParentCorrelation:65`, `BuildContext:1776` and `FieldBuilder` read it, and pre-resolved-over-re-derived is the generation-thinking default. Deleting it in favor of a path-position derivation is homed in R431, which re-types the path structure anyway (noted there). |
| `FkJoin.whereFilter` | `Hop.filter`, retyped `JoinConditionRef` (see R16 absorption) |
| `ConditionJoin.condition` | `On.Predicate.condition`, retyped `JoinConditionRef` |
| `FkJoin.alias` / `ConditionJoin.alias` | `Hop.alias` |

The capability interfaces reshape rather than survive verbatim, along the doctrine's own line
(capabilities express what is uniformly true, sealed switches what varies by identity):

- `HasTargetTable` (target table + alias, uniformly true of every step) stays, implemented by both
  permits.
- `WithTarget` dies as a `JoinStep`-level capability because slot *presence* now varies within
  `Hop` (`ColumnPairs` has slots, `Predicate` does not); presence is answered by `on() instanceof
  ColumnPairs`, a sealed dispatch.
- Slot *iteration* stays a capability, because it remains uniformly identical across the two
  slot-carrying types that coexist in the R438-to-R431 window: a small `HasSlots` interface (the
  current `WithTarget` helper body: `Iterable<? extends JoinSlot> slots()`, `slotCount()`,
  `sourceSideColumns()` / `targetSideColumns()` defaults) implemented by `On.ColumnPairs` and
  `JoinStep.LiftedHop`. The `Iterable`-not-`List` discipline (`JoinStep.java:90-95`, positional
  access as a compile error) transfers with it, not "implementer's call": `ColumnPairs` may store
  a `List` component but exposes iteration through the capability. `HasSlots` dies with
  `LiftedHop` in R431, when `ColumnPairs` becomes its only implementor. Readers that today
  dispatch `FkJoin | LiftedHop` through `WithTarget` at hop 0 (`SplitRowsMethodEmitter`,
  `ParentCorrelation`) re-dispatch on `Hop`-with-`ColumnPairs` vs `LiftedHop` and read slots
  through `HasSlots`.

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
overloaded `whereFilter`. Done: R16's file deleted in slice 4, changelog entry added.

## Consumer inventory

The item's original list undercounted. Variant-dispatching code (`case` / `instanceof` on the
three permits) lives in 18 files, 62 sites: `BuildContext` (19), `FieldBuilder` (7),
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `InlineColumnReferenceFieldEmitter`,
`SplitRowsMethodEmitter` (4 each), `ParentCorrelation` (4), `CatalogBuilder` (3),
`TypeConditionsGenerator`, `FkTargetConditionEmitter`, `TypeBuilder` (2 each), and single sites in
`GraphitronSchemaValidator`, `NodeIdLeafResolver`, `SourceRowDirectiveResolver`, `ServiceCatalog`,
`MultiTablePolymorphicEmitter`, `TypeClassGenerator`, `TypeFetcherGenerator`. The `LiftedHop` arms
among these stay; the `FkJoin` / `ConditionJoin` arms migrate.

## Transition plan (shipped)

Additive-then-cutover (R222 / R431's technique); full build green and generated output verified
byte-identical after every slice:

1. **Type the convention (R16 core)** — shipped at the slice-1 commit. `JoinConditionRef` minted;
   `whereFilter` / `condition` retyped; `emitTwoArgMethodCall` takes the wrapper.
2. **Mint the axes** — shipped at the slice-2 commit. `TableExpr` (`Catalog` only), `On`
   (`ColumnPairs` | `Predicate`), `Hop` as a fourth permit; the slot-iteration contract moved
   from `WithTarget` into a standalone `HasSlots` capability (`WithTarget` reduced to composing
   `HasTargetTable` + `HasSlots`), which sidestepped a default-method clash the spec's sketch
   (`WithTarget` and `HasSlots` coexisting with duplicate defaults) would have hit.
3. **Cut producers over, migrate every reader** — shipped at the slice-3 commit, as one commit
   rather than several reader clusters: the carrier types (`ParentCorrelation`,
   `ChildField.ParticipantColumnReferenceField`, `ParticipantRef.CrossTableField` /
   `JoinedTableBound`) retype in the same motion as the producers, so the intermediate
   dual-sourced states would not have compiled per-cluster. `FkJoinResolution.Resolved` carries
   the `Hop`; `ParentCorrelation.OnFkSlots` carries the slot-bearing step and exposes `slots()`
   via `HasSlots`; `OnConditionJoin` carries a `Predicate` hop and exposes `condition()`.
   Learning: the consumer inventory's `case`/`instanceof` census was the compile-forced subset;
   an equal-sized set of *silent* `instanceof` guards (behavioral, not compile-broken) had to be
   found by grep and migrated in the same commit, plus ~15 line-level test-source clusters
   (the spec's test-inventory note).
4. **Delete** — this commit. `FkJoin` / `ConditionJoin` / `WithTarget` removed; permit list is
   `Hop | LiftedHop`; dead arms deleted; `JoinStep` javadoc rewritten against the axes; R16's
   file deleted and changelogged; stale-reference sweep over main/test javadoc and the four
   doc-site mentions (`code-generation-triggers.adoc`, `multi-hop-nodeid-filter.adoc`,
   `join-with-references.adoc`).

## Acceptance

- **Generated output is byte-identical.** This is a model reshape with no behavior surface. There
  is no checked-in golden corpus for full generated output, so the gate is named concretely: diff
  `graphitron-sakila-example/target/generated-sources/graphitron` before and after each slice
  (generated fresh by `mvn install -Plocal-db`) and require an empty diff, alongside the
  pipeline-tier assertions on emitted bodies. Any diff in generated code is a bug in the reshape.
- Pipeline and execution tiers green at every intermediate commit, not just the endpoint.
- `JoinStep.java`'s javadoc (the variant-contrast table, the cardinality invariant) survives the
  reshape rewritten against the axes, not deleted.

## Out of scope, and coordination

- `MethodCall` / `RoutineCall` target arms, the `Lateral` `on`-arm, PK/UK name-match derivation,
  the start-node variant (a sealed sibling, never `Optional<On>`, see above), and the `@oneOf` SDL
  path-element surface: all R435 (or its follow-ons), which lands them on these axes.
- `Lift` source-side provenance, `LiftedHop` retirement (and with it `HasSlots` and the four
  defensive unreachable arms), `SourceKey.path` re-typing, and the `originTable`
  derivation-over-denormalization cleanup: R431. **Sequencing**: both items carry `depends-on: []`
  but they reshape the same seal; R431 reads R438's `Hop`, so R431 follows R438. Recorded as prose
  in both items rather than a hard edge because the dependency is on the landed shape, not on this
  item's completion ceremony.
- The R381 path-walker lift (`step(currentSource, hopElement)` over an `FkEdge` abstraction)
  touches the same seam; coordinate so the lifted stepper reads the two-axis step, not the flat
  seal.

Why eager rather than pulled by R435 itself: R435's design (routine table nodes, order-significant
directive composition) needs the target arm and the `on` axis to exist before it can add
`RoutineCall` and `Lateral`; folding this reshape into R435 would make one item carry both a
model-substrate pivot and a feature surface, the scope-mixing R431 was split out of R314 to avoid.
