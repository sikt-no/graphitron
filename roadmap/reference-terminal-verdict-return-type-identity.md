---
id: R422
title: "@reference terminal-target verdict must compare return-type identity, not the verbatim @table echo"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# @reference terminal-target verdict must compare return-type identity, not the verbatim @table echo

## Problem

Split out of R396 (which fixed the FK source-side predicate for schema-qualified / case-mismatched
*source* `@table` names). This is the same bug class on a *different* author shape: a schema-qualified
**return-type** `@table`.

`BuildContext.computeTerminalTargetVerdict` (around `BuildContext.java:1307`) decides whether an
`@reference` path's terminal hop lands on the field's return-type table by comparing the canonical
resolved target against the **return type's** verbatim `@table` echo via `TableRef.sameTable`
(`tableName.equalsIgnoreCase(other)`). When the return type's `@table(name:)` carries a schema prefix
(e.g. `@table(name: "multischema_a.widget")` while the hop resolves to the canonical unqualified
`widget`), the bare-string compare returns false and the terminal verdict spuriously reports
`Mismatch` even though the hop lands correctly. R396 fixed the parent/source side by adopting jOOQ
table-class identity comparison on `JooqCatalog`; this item closes the return-type side by the same
principle.

## Root cause

`TableRef.sameTable` is a verbatim-echo `equalsIgnoreCase`. R396 deliberately scoped itself to the
source `@table` shape (one author shape across all three `@reference` directive forms, sharing one
catalog primitive) and left the return-type comparison point for a focused follow-up, because
`parsePath` threads return names as `String` across ~12 call sites, so an identity-based terminal
check touches that signature or adds a catalog resolve at the one comparison point.

## The fix (two candidate shapes, decide at Spec)

The terminal comparison happens in exactly one place (`computeTerminalTargetVerdict`). Two ways to
close it, smallest-ripple first:

1. **Resolve at the comparison point (recommended, no signature change).** Inside
   `computeTerminalTargetVerdict`, resolve `returnSqlTableName` through the catalog (or compare the
   already-resolved terminal `TableRef`'s class identity against the return type's resolved class)
   instead of `fk.targetTable().sameTable(returnSqlTableName)`. The `parsePath` callers already hold
   the return type's `TableRef` (which carries `tableClass`, a `ClassName`), so the identity is
   reachable without threading a new parameter.
2. **Thread the return-type identity** from the `parsePath` callers down to the verdict. Wider ripple
   across the `String` return-name plumbing; only worth it if a second consumer needs the identity.

Prefer (1): it keeps the change to the single comparison point and reuses R396's identity-comparison
principle without widening `BuildContext`'s jOOQ surface.

## Tests

- Pipeline tier: an `@reference` field whose **return** type carries a schema-qualified `@table`
  (e.g. `multischema_a.widget`) and whose terminal hop lands there must classify to a terminal verdict
  of `Match` (today: spurious `Mismatch`), with no author error. Pair with a genuine mismatch (hop
  lands on a different table than the qualified return type) that still reports `Mismatch`, so the fix
  tightens the compare without disabling it.

## Notes

- Same bug class and consumer wave as R395 / R396 (schema-qualified `@table` echoes vs. jOOQ's
  always-unqualified canonical names). Reachable only when the *return* type of an `@reference` field
  is schema-qualified, which is why R396 could ship its source-side fix without it.
