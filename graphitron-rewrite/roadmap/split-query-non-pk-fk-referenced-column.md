---
id: R338
title: "Split-query correlation projects parent node/PK columns instead of the FK referenced columns"
status: Ready
bucket: bug
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Split-query correlation projects parent node/PK columns instead of the FK referenced columns

A `@splitQuery` reference field (here a `@asConnection` list field) whose `@reference` foreign key targets a **non-primary unique key** on the parent silently returns zero rows for every parent. The split-rows fetcher builds the `parentInput` VALUES table from the parent's primary-key columns, but `SplitRowsMethodEmitter` builds the correlation predicate from the first hop's `JoinStep.WithTarget.sourceSideColumns()` (the FK's actual *referenced* columns, via `ForeignKey.getKeyFields()`). When the referenced columns are not the parent PK, the two disagree: the emitted predicate is `e0.<fkCol>.eq(parentInput.field("<referencedCol>", …))`, and jOOQ returns `null` from `parentInput.field(...)` for the absent column name, so the predicate degrades to `e0.<fkCol> = NULL` and matches nothing. No error is raised. Fields whose FK references the parent PK work only because PK == referenced columns by coincidence.

## Root cause

`FieldBuilder.deriveSplitQuerySource` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:4736`) chooses the `parentInput` entry columns as:

```java
List<ColumnRef> entryColumns =
    (!isList && !path.isEmpty() && path.get(0) instanceof JoinStep.FkJoin fk)
        ? fk.sourceSideColumns()
        : parentTable.primaryKeyColumns();
```

The Single (parent-holds-FK) branch already projects `fk.sourceSideColumns()` and works for arbitrary referenced columns. The List (child-holds-FK) branch falls through to `parentTable.primaryKeyColumns()`, baking in the assumption that the child's FK references the parent PK. `BuildContext.resolveFkSlots` orients a child-holds-FK first hop so each slot's `sourceSide()` is the parent's referenced column (`getKeyFields()` mapped onto the parent), which for a non-PK unique key is not in `primaryKeyColumns()`.

## Direction

Project the FK's first-hop referenced columns (`sourceSideColumns()`) into `parentInput` and key the DataLoader off those same columns, for **both** cardinalities, when the first hop is an `FkJoin`. Keep the `primaryKeyColumns()` fallback only for the non-FK first-hop shape (`ConditionJoin`, where `OnConditionJoin` already correlates on parent PK). The parent's read-side machinery already reads arbitrary FK source columns off the parent record (the Single branch proves this), so the unification should be small; the load-key element type and the parent-record column read must follow the same column set.

## Reproduction / coverage

Observed against `10.0.0-RC17` upgrading the opptak subgraph: `Sak.endringsloggV2` (`@asConnection @splitQuery @reference(path: [{key: "endringslogg_for_sak__endringslogg_for_sak_sak_fk"}])`), where the synthetic FK `endringslogg_for_sak_sak_fk` references `sak_endringslogg_key_uk` (`sak.endringslogg_key`, a non-PK unique key) rather than `sak_pk`. Pin with an execution-tier fixture: a `@table` parent with a non-PK unique key, a child whose FK references that unique key, and a `@splitQuery` list field that must return the child rows (not an empty list). Consumer workaround in the field: point the FK at the parent PK instead, which makes `parentInput` and the predicate agree.
