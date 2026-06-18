---
id: R327
title: "Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)

Split out of R317 slice 4 (the classify-and-emit collapse), which deferred this as the one
non-byte-identical change so the collapse could stay a pure structural delta.

Today an input type's table-boundness is decided *globally*: `TypeBuilder.buildInputType` consults
`@table` on the input, then the `findReturnTablesForInput(name)` aggregate over every field that
takes the input, and classifies it as table-bound only when that aggregate resolves to exactly one
table (it bails to non-table on zero or more than one). This is the wrong altitude: whether an input
is table-bound is a function of the *field's target table at the use site*, not a global property of
the input type. The aggregate's `> 1` bail means an input reused across two tables silently
classifies non-table everywhere, and `@table`-on-input is a manual override papering over the missing
field-relative derivation.

The R317 read-free work makes the field-relative model reachable: `lookAheadVerdict` already resolves
a field's target verdict registry-free at the edge, so the input arg can be classified *after* its
field's target, deriving table-boundness from the target's table rather than the global aggregate.
`@table`-on-input is then de-emphasised (eventually deprecated; the field-relative derivation
subsumes its common use).

This is **not** automatically byte-identical, which is why it is its own item: an input used across
more than one table classifies non-table today (the aggregate bails on `> 1`) but becomes table-bound
per field under the field-relative model. The change must be gated against the fixtures, and where a
verdict shifts, pinned as the intentional consequence (with execution-tier coverage proving the
per-field table binding generates correct SQL). Scope the `@table`-on-input deprecation path
(warn-then-remove vs. keep-as-override) when this moves to Spec.

