---
id: R1139
title: "A field-level @condition(override: true) over an argument that matches no column is rejected as unbound"
status: Backlog
bucket: bug
priority: 4
theme: classification-model
depends-on: [argument-condition-override-rejected-as-unbound]
created: 2026-10-30
last-updated: 2026-10-30
---

# A field-level @condition(override: true) over an argument that matches no column is rejected as unbound

## Goal

A field-level `@condition(override: true)` replaces the implicit predicates of the field's arguments,
so an argument that matches no column is accepted when some parameter of the field's condition method
reads it, and is still rejected when none does. Today the argument is rejected as unbound first.

Minimal case from kreg's Graphitron 9 schema (`spraakkoder` matches no column; the column is
`SPRAAKKODE`):

```graphql
navn(spraakkoder: [String!]): [Navn!]!
  @splitQuery
  @reference(path: [{table: "UTDANNINGSSPESIFIKASJON_SPRAAK", condition: navnJoinCondition}])
  @condition(condition: navnFilterCondition, override: true)
```

The port had to move the condition onto the argument and add `@field(name: "SPRAAKKODE")` only to
obtain a binding the override then discards.

## Direction

The classifier already verifies that every slot a field-level override suppresses is read by some
parameter of some condition. Reuse that check to admit the unbound argument: admit when a parameter
of the field-level method binds it, reject otherwise, with the rejection naming the argument and the
method. Follows the argument-level override item's carrier (the author-owned argument shape) so the
two do not grow separate mechanisms; depends on it. Pipeline-tier cases: admitted when read, rejected
when no parameter reads it, and the same argument without `override` still rejected.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30, as review comments on the specs for R1134 and R1136.
