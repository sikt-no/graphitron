---
id: R927
title: "Three of columnCompare four branches bind a value with no DataType"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Three of columnCompare four branches bind a value with no DataType

## Goal

A filter value compared against a converter-backed column produces a query the database accepts,
whichever of the four predicate shapes the coordinate lowers to. Today only one of the four binds the
value at a column's `DataType`, so a filter over a column whose jOOQ type comes from a `Converter` can
render a bind of the converted user type against the column's real SQL type. PostgreSQL answers a
`bigint` domain compared against a `varchar` bind with `operator does not exist`, at request time,
from a build that was green.

`ConditionGlueRenderer.columnCompare` is the one site that renders a generated filter's value
comparison, and it branches twice: single column against a tuple, equality against membership. The
single-column equality branch emits `alias.COL.eq(DSL.val(value, alias.COL))`, which binds the value at
that field's `DataType` so a registered `Converter` runs. The other three (single-column `IN`, and both
row forms) pass the bare Java local: `alias.COL.in(local)`, `DSL.row(...).eq(local)`,
`DSL.row(...).in(local)`. jOOQ then infers a bind type from the value's Java class, which for a
converter-backed column is the user type rather than the column's type.

The rule this needs is already written down. `ColumnComparison`'s javadoc states it as the companion
bind rule: *a value binds at the `DataType` of the column it was read from, and the comparison
coerces*, and it is the reason `LookupRows` spells its cells `DSL.val(v, table.COL.getDataType())`. The
`converter_campus` fixture in `init.sql` exists to pin exactly this hazard for the split-query rail,
where the untyped bind rendered `character varying` against `org_code_domain` and the join failed with
`operator does not exist: org_code_domain = character varying`. The generated-filter rail never picked
the rule up.

## Implementation

Route all four branches of `columnCompare` through one typed bind, so the shape of the predicate stops
deciding whether a converter applies. `ColumnComparison` is the natural home, both because it already
owns this rule and because it is where a future coercion of a diverged pair would be minted; the
alternative, a fourth spelling local to `ConditionGlueRenderer`, is what left the branches divergent in
the first place. The row forms need the bind per cell, since a `Row` is built from fields and each cell
carries its own column.

The single-column equality branch also binds against the *receiver's* column rather than the column the
value was read from. `ColumnComparison`'s javadoc argues the bind-then-coerce direction explicitly (a
receiver-typed bind routes the value through `Convert.convert` between two user types, which an
arbitrary converter does not guarantee), so confirm which column each branch should bind at while
unifying them, rather than propagating the existing choice.

## Tests

The failure is a runtime SQL error on a schema that compiles, so the tier is execution. A filter over a
converter-backed column in each of the four shapes, executed: `converter_campus.org_code` gives the
single-column shapes, and a composite node key over converter-backed columns gives the row shapes. The
fixture DDL exists; check whether a composite key over two converted columns does before assuming it.

A compilation-tier case adds nothing here: all four branches compile today.

## Provenance

Found while specifying the `@nodeId` landing checks in `roadmap/nodeid-key-landing-is-never-verified.md`,
which named the four-branch asymmetry as adjacent and out of its own scope. That item's refusals cannot
close this one: they reject a landing whose two columns disagree on Java type, and this fault needs the
two columns to *agree* (one `Converter`, on both ends) for the untyped bind to reach the database.
