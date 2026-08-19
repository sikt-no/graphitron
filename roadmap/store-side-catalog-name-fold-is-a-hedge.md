---
id: R724
title: "The store folds two catalog-produced names as a hedge; make the comparison exact"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# The store folds two catalog-produced names as a hedge; make the comparison exact

One view in the fact schema still folds case per row, and it is the only comparison the fold rule
declines to serve. `intent_node_metadata_defect`, in its `KEY_COLUMN_UNRESOLVED` arm, matches
`sql_column.jooq_name` and `sql_column.column_name` against `sql_node_key_column.column_name` under
`UPPER` on both sides. Every other case-insensitive comparison in the schema now reads two stored
folded columns, because a fold is minted where an authored spelling meets a catalog name and that
crossing is what a folded column bridges.

This comparison has no such crossing. Both operands are values the jOOQ crawler produced: the
candidate columns come from the catalog read, and the sought name comes from the generated table
class's key-columns constant. So the fold is not a semantic, it is the hedge
`roadmap/exact-catalog-name-comparisons.md` argues against for the generator's Java sites, arriving
in the store instead. That item's reasoning transfers without change, including why the hedge is not
harmless: on a database using quoted identifiers, two genuinely distinct columns can differ only by
case, and a folded comparison silently equates them, which here would report a key column as resolved
against the wrong column or as resolved when it is not.

Why it is filed separately rather than folded into that item: its census is Java call sites and its
fix is a per-site reachability call plus a sweep, where this is one view predicate in
`graphitron-model`'s DDL and the question is whether the constant's spelling is catalog-canonical in
the same sense the crawler's own reading is. If it is, the fix is deleting four `UPPER` calls and the
schema holds none outside the generated columns. If it is not, then the constant is an authored
spelling after all, the crossing is real, and `sql_node_key_column.column_name` earns a folded
companion under the ordinary rule. Either answer is small; which one is right is the whole item, and
guessing it is how the wrong convention lands.

The two items must not settle opposite conventions, for the reason that one records about its own
relationship to the filed case-drift defects. Neither depends on the other.
