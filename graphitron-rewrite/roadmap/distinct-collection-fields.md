---
id: R278
title: "Distinct (set semantics) for collection fields whose reference path fans out through a link table"
status: Backlog
bucket: feature
priority: 5
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# Distinct (set semantics) for collection fields whose reference path fans out through a link table

## Problem

A collection field whose `@reference` path crosses a to-many hop can list the same target entity more than once, and there is no way to ask Graphitron for set semantics on that field. The motivating request:

```graphql
miljoer: [Miljo] @splitQuery @reference(path: [
  {key: "feide_applikasjon__fk_feide_applikasjon_subjekt"},
  {key: "subjektrolletildeling__fk_subjektrolletildeling_subjekt"},
  {key: "subjektrolletildeling__fk_subjektrolletildeling_miljo"}
])
```

The path walks `feide_applikasjon -> subjekt -> subjektrolletildeling -> miljo`. The middle hop, `subjektrolletildeling` (a role-assignment link table), is one-to-many per subject: a subject can hold several role assignments, and two of them can point at the same `miljo`. Each matching link row produces a `miljo` row, so the same environment comes back two or more times. Conceptually `miljoer` is *the set of environments this application's subject has a role in*; the role-assignment hop is plumbing the API consumer never sees, and the duplication is an artifact of that plumbing, not of the field's meaning.

## Current behaviour

There is no `@distinct` directive and no `SELECT DISTINCT` anywhere in the generator. The only existing dedup is `LinkedHashSet<Field<?>>` in `TypeClassGenerator.$fields()`, which dedupes *which columns get projected* when two SDL arms emit the same jOOQ field; it does nothing to result rows.

For a `@splitQuery` field, `SplitRowsMethodEmitter` emits a separate batch SELECT (a VALUES table of parent keys joined down the FK chain with LEFT JOINs), then `scatterByIdx` buckets the flat rows back to the originating parent via an `__idx__` marker. Bucketing assigns each row to its parent; it does **not** collapse duplicate targets *within* a parent's bucket. So the duplicate `miljo` rows survive into the returned list. The cardinality invariant in `JoinStep` only rejects fan-out on single-valued (non-list) fields, so a `[Miljo]` list field accepts the fan-out and returns the duplicates.

## Why this is worth doing (and where the smell lives)

Two cases look identical at the directive but are not the same thing:

1. **M:N projection through a link table (the request above) is legitimate.** Deduping by the target entity's identity restores the cardinality the field already claims (`[Miljo]` = a set of environments). It is not hiding a modelling problem; the link table genuinely is an implementation detail of the path.
2. **Distinct over a row that still carries link-table columns is the smell.** If a field wants distinct *and* also selects columns off `subjektrolletildeling`, the duplication is meaningful and the honest fix is to model the field differently (expose the role assignments, not the environments).

The useful discriminator: distinct **on the target type's identity** is principled; `SELECT DISTINCT *` over a heterogeneous projection is the crutch. A design that can only express the first is hard to abuse, which is the property we want.

## Open design forks (to resolve at Spec)

- **Mechanism: in-memory set vs SQL `DISTINCT`.** Leaning toward in-memory dedup keyed on the target's jOOQ key, applied at the `scatterByIdx` bucketing point, reusing the existing `LinkedHashSet` discipline (order-preserving, dedup by jOOQ identity). SQL `DISTINCT` is the weaker option here: it forces every selected column into the distinct key, interacts badly with pagination (`DISTINCT`-then-`LIMIT` vs `LIMIT`-then-`DISTINCT` is a real correctness trap), and pushes cost to the DB for a problem we can solve where we already hold the rows. The in-memory route also structurally blocks case (2), since it dedups on target identity rather than the full row.
- **Surface: field-level set semantics vs a free-floating `@distinct`.** Framing it as a property of the field ("this collection is a set") documents intent and leans into the legitimate case, versus a `@distinct` knob that reads as a SQL escape hatch. Whether that is a new directive, an argument on `@reference`/`@splitQuery`, or something else is open. Resist *inferring* it silently when a path crosses a to-many hop: the duplicates are sometimes the thing the caller wants to see, so it should be explicit.
- **Pagination boundary.** Everything above assumes a plain list. If `miljoer` is ever a paginated connection, distinct stops being a tidy post-bucket operation and becomes a genuine cursor/SQL problem. That boundary may be exactly where we decline to support distinct and require better modelling instead. Spec should decide whether to scope this item to non-paginated list fields.
- **Non-`@splitQuery` paths.** The same fan-out can arise on a correlated-subquery reference (INNER JOIN form) rather than a split batch query. Spec should confirm whether the chosen mechanism covers both, or whether the item is scoped to `@splitQuery` first.

## Anchors

- `SplitRowsMethodEmitter` (`scatterByIdx`) -- where per-parent bucketing already happens; natural seam for in-memory dedup.
- `TypeClassGenerator.$fields()` -- existing `LinkedHashSet` dedup discipline to mirror.
- `BuildContext.parsePathElement` -- where `@reference path:` keys resolve to `FkJoin` steps.
- `JoinStep` -- cardinality invariant that admits fan-out on list fields.
- `directives.graphqls` -- where a new directive or argument would be declared.
