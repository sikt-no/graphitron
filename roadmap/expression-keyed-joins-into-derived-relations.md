---
id: R765
title: "One relation for a field's authored named type, so a wrapper-stripping expression stops being a join key"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# One relation for a field's authored named type, so a wrapper-stripping expression stops being a join key

Three places in the fact model ask the same question about a field: what type does it name, once list and non-null wrappers are stripped and once a macro's rewrite of the type expression is accounted for. All three spell it inline, identically, as

```sql
COALESCE(REPLACE(REPLACE(REPLACE(fs.authored_type_sdl, '[', ''), ']', ''), '!', ''), f.named_type)
```

over a `LEFT JOIN graphitron_field_synthesis fs`. That is one rule with three spellings, which is the shape this schema lifts into a relation everywhere else it appears. It should be a relation here too, stating a field's authored named type as a column.

## The cost, which is why this is more than tidiness

Two of the three sites use that expression as a *join key* against a derived relation: `intent_routine_return_binding` joins `graphql_type` on it, and `intent_field_column_scope`'s `NAMED_TYPE_TABLE` rule joins `graphql_type` on it and then the binding on the result.

Joining a derived relation on an expression rather than on a column makes H2 evaluate that relation once per driving row instead of once. Measured against the sakila example's schema and catalog, a rung shaped exactly that way over `intent_resolved_type_binding` cost 19.9 seconds for 157 rows; projecting the expression as a column in an inner derived table first and joining on the column cost 0.13 seconds for the same rows. Three controls on the same fixture: joining `f.named_type` directly, wrappers ignored, is 0.08 s, so the cost is the expression and not the row count; materialising the inner relation in a `WITH` clause first is still 19.6 s, H2 inlining a non-recursive `WITH`, so no relation extracted for tidiness alone would have helped; and routing the expression through `graphql_type` before joining the binding on its column, which is precisely how both existing sites spell it, is 19.6 s and no fix at all.

So both existing sites carry the hazard. They are fast today only because of what drives them: a chain terminus and a field's own scope are orders of magnitude fewer rows than, say, every argument in the graph. That is safety by accident of population size rather than by construction, and the accident stops holding the first time a wider population reads either relation.

## What the increment is

State a field's authored named type as a relation with a plain `named_type` column, keyed on the field coordinate, so the expression is written once. Then repoint the two sites at it, joining on the column. The kind filters both sites apply stay theirs, the new relation answering the name and not what the name is declared as.

The reduction is only real if the readers join a column. A view is inlined, so the relation has to *project* the expression rather than name it in a predicate, and each reader has to take the column from it rather than re-derive the expression. A test that pins the shape is worth more than one that pins a duration.

## Whether a gate can hold this

Worth considering, not yet decided. The rule "a join into a derived relation must be keyed on a column, not an expression" is mechanically checkable from the DDL alone: which relations are views is already derivable there, and the roadmap-tool has a family of steps that read the tree and fail the build (`check-adoc-tables`, `check-transient-citations`, `check-module-enumeration`). The prior art for reporting rather than gating is `report-inline-multiplicity`, whose own standing this measurement lowered: it counts how often a relation is named, and a naming's cost is not a function of how many there are, so it ranks breadth and cannot see this at all. A gate on join-key shape would see exactly what the multiplicity metric misses. Whether it can be written without flagging legitimate spellings is the open question, and it should not block the relation above.
