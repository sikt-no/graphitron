---
id: R1000
title: "An upsert into a table with a second unique key names its conflict target"
status: Backlog
bucket: cleanup
priority: 4
theme: tooling
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# An upsert into a table with a second unique key names its conflict target

## Goal

Every upsert in `graphitron-model` into a table that carries a `UNIQUE` key besides its primary key
says which key it conflicts on, and a guard test fails the build on one that does not. Today jOOQ
3.20 renders `onDuplicateKeyUpdate()` against such a table on H2 as
`MERGE … ON (pk match OR unique match)`. No index serves the `OR`, so every source row scans the
target. And where the second key is independent of the primary key, a collision on it silently
overwrites a different row. R998 converts the two hot sites the sis profile names, plus their twin
`EmittedAnchor.arguments`. This item converts the rest and adds the guard that stops new ones.

## Notes for the Spec

* The rule is "name the conflict target, with an argument per table", not "convert to the primary
  key". The ten tables with a second key are not alike:
  * `graphql_type`'s and `graphitron_tabletype`'s `UNIQUE` keys include the primary key, so the
    `OR` arm can never fire there. Converting to the primary key is free.
  * The coordinate-keyed tables (the `graphql_*_element` subtypes and their `graphitron_*` twins)
    take R998's shape: convert to the primary key and add `CHECK (coordinate = <spelling of the key>)`,
    which makes the coordinate a function of the key. This covers the family members R998 left
    alone: `graphql_directive_argument_element`, and the coordinate column of
    `graphitron_field_chain_application`. Weigh `GENERATED ALWAYS AS` for the coordinate against
    the `CHECK` here, family-wide, including whether H2 accepts a foreign key into
    `graphql_element` on a generated column.
  * `graphitron_field_chain_link`, `graphitron_field_chain_application` and `meta_gatherer` have
    second keys that are independent of the primary key. A collision there is reachable. Each needs
    a decision about which key is the identity and what a collision on the other one means.
* The guard is the deliverable that enforces the rule. It reads each table's keys from the jOOQ
  meta-model and flags any upsert into a multi-key table that does not name its target. Start
  from a frozen allowlist that only shrinks, in the `MetaDeclarationGateTest` roster shape.
* Until this lands, a regression of R998's converted sites back to `onDuplicateKeyUpdate()` is
  slow but not wrong, because the `CHECK`s make the `OR` arm dead.
