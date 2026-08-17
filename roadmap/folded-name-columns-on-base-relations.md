---
id: R697
title: "Case-folded name columns on the base relations, so matching views stop repeating UPPER()"
status: Backlog
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Case-folded name columns on the base relations, so matching views stop repeating UPPER()

## Problem

Every view that matches an authored name against a catalog or classpath name folds case inline, and
the folding is repeated per comparison rather than owned by the column being folded. Nineteen lines
of the DDL carry an inline `UPPER(` or `LOWER(`. `intent_column_match_claim` alone writes
`UPPER(COALESCE(fb.name_ref, f.field_name))` four times, in the `matched_by` CASE, in the
`ROW_NUMBER` ordering, and twice in the join predicate, alongside `UPPER(c.jooq_name)` and
`UPPER(c.column_name)`. `intent_spelled_table` and the constraint-resolution views fold the same way
over their own name columns.

The folding is a function of one base relation's own column, so it belongs on that base relation. The
`COALESCE` beside it is not: an effective name needs the join to `graphitron_field_binding`, so it
stays the view's own product. That split is the point of the item, and it is the case where the
existing earning test in `intent_column_match_claim.matched_name`'s comment ("the classifier's own
product rather than a projection of either input") points in a definite direction.

Not a single computed column exists in 4,492 lines of DDL, so this is an unused tool rather than an
inconsistently used one.

## Why it is worth doing rather than tolerating

Three reasons, in order of weight. A matching predicate written once per column cannot drift between
the join arm and the ordering arm of the same view, which is a live hazard in the four-repeat case
above. A folded column is indexable where an expression over a joined derived relation is not, and
`intent_column_match_claim`'s comment already records a measured seventy-times regression from getting
the shape of that view's evaluation wrong. And every future matching view stops restating the rule.

## The fork to settle at Spec

Two shapes, and the choice is not obvious:

* **An H2 generated column** (`GENERATED ALWAYS AS (UPPER(column))`) on each name-bearing base
  relation. The engine guarantees the invariant and nothing can write a stale value. Risk: capture
  writes these relations through jOOQ records and batch inserts, and a generated column must not
  appear in an insert's column list. Whether jOOQ's codegen marks it readonly in 3.20 and whether
  every writer respects that needs checking before the shape is chosen, not after.
* **A plain column written by capture.** No writer friction, at the cost of a derived value stored in
  a base relation, which is the shape the fact-model doctrine refuses. It would need the
  cannot-be-a-view argument the DDL requires of a materialization, and "the engine cannot enforce it"
  is a weaker argument than the doctrine's bar.

Preference is the generated column, contingent on the jOOQ check. If that check fails the honest
answer may be to do nothing and leave the repetition, so the check comes first in the Spec body.

## Care

Broad and mechanical: it touches name-bearing base relations across several families and every view
that matches on a name, so it conflicts with anything else editing those views. Sequencing agreed:
land it early, before the language-server recomposition writes new joins against these predicates,
rather than after, when the two would fight over the same view bodies.

The schema-identifier and family gates read the relation census, so new columns need their comments
in the same pass; a column without a comment fails the build rather than shipping undocumented.
