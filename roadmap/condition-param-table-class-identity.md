---
id: R442
title: "Concrete condition-method table param must match by jOOQ class identity, not bare-vs-qualified name"
status: Backlog
bucket: bug
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Concrete condition-method table param must match by jOOQ class identity, not bare-vs-qualified name

## Problem

Another member of the schema-qualified `@table` bug class (same as R396, done; R422, Backlog),
surfaced in the multi-schema `opptak` migration where two jOOQ-generated schemas share bare table
names (gap D). A concrete `@condition` method whose parameter is typed with the *correct* generated
jOOQ table class is rejected at build time:

```
condition method '…joinOpptakshendelseToType' parameter 0 is typed for table 'opptakshendelse'
but this hop's source table is 'opptak.opptakshendelse'
```

The author did everything right, the parameter names the right jOOQ class, yet the validator errors.
The only workaround is to widen the parameter to `Table<?>`, which throws away the type safety the
concrete parameter was buying. This is a wart, not a hard blocker.

## Root cause

`BuildContext.checkConcreteParamTable` (`BuildContext.java:1854`, added by R379 as Check 2 for
condition-method table parameters) compares two names that are drawn from different naming worlds:

```java
String declaredTable = entry.get().table().getName();     // BARE name from the param's jOOQ class
if (!declaredTable.equalsIgnoreCase(expectedSqlName)) {    // hop's SCHEMA-QUALIFIED name
    ...
}
```

`declaredTable` is the unqualified name jOOQ's generated table reports (`opptakshendelse`).
`expectedSqlName` is the hop's source/target table name threaded in from
`validateWhereFilterParamTables` / `validateConditionParamTables` as
`hop.originTable().tableName()` / `hop.targetTable().tableName()`, which is schema-qualified in a
multi-schema catalog (`opptak.opptakshendelse`). The bare-vs-qualified `equalsIgnoreCase` returns
false even when the parameter is typed with the exact resolved table class. Same failure shape as
R396's source-side FK predicate and R422's terminal-target verdict: a verbatim string compare
standing in for jOOQ table-class identity, which additionally cannot distinguish same-named tables
across schemas the way identity can.

## The fix (two candidate shapes, decide at Spec)

The comparison happens in exactly one place (`checkConcreteParamTable`), and it *already* holds the
param's resolved catalog table: `catalog.findTableByClass(cls)` yields `entry.get().table()`. What it
lacks is the hop table's identity, only the qualified name string is threaded in. Smallest-ripple
first:

1. **Thread the hop's `TableRef` identity (recommended).** `validateWhereFilterParamTables` /
   `validateConditionParamTables` currently pass `hop.originTable().tableName()` /
   `hop.targetTable().tableName()` (bare/qualified name strings) into `checkConcreteParamTable`. The
   hop's `originTable()` / `targetTable()` already carry the resolved table class, so pass the
   `TableRef` (or its `ClassName`) down instead and compare it against the param's resolved class via
   the shared identity predicate below. This is the same "the identity is right there and thrown away"
   shape R441 documents on the accessor side.
2. **Resolve the expected name at the comparison point (no upstream signature change).** Resolve
   `expectedSqlName` through the schema-aware `JooqCatalog.findTable` R396 introduced and compare the
   resolved table's class identity against `cls`, falling back to the existing bare
   `equalsIgnoreCase` when the expected name does not resolve to a catalog table, so the diagnostic
   surface for genuinely-unknown names is unchanged.

Either shape must keep the existing skip conditions (out-of-range position, unknown expected table,
wildcard `Table<?>`, non-catalog concrete type) and must still error on a genuine mismatch; the fix
tightens the compare, it does not disable it.

## Coordinate the shared identity predicate with R441

R441 (`typed-accessor-schema-qualified-table-identity`, gap E) is the accessor-side sibling of this
exact bug class and explicitly names this gap (`checkConcreteParamTable`) as its coordination partner:
both are consumers of the same bare-name compare, and both should route through **one** table-class
identity predicate on `TableRef` (R441 proposes giving `TableRef.sameTableAs(TableRef)` a real
`tableClass()`-comparison body instead of delegating to the bare-name `sameTable`), not two. Per
R441's framing, whichever item ships first introduces that predicate and the other reuses it; that
mutual ordering is why no `depends-on:` is set here. When wiring this in, expect the case-folding
guard R441 flags (`TableNameComparisonCaseGuardTest`, R358/R359) to need updating once `sameTable`
gains an identity route.

## Tests

- Pipeline tier: an `@condition` method whose parameter 0 is typed with a colliding table class (a
  bare table name shared across two schemas) validated against a schema-qualified hop source must
  pass with no author error and *without* widening the parameter to `Table<?>`. Pair with a genuine
  mismatch (parameter typed for a different table than the hop's) that still errors, so the fix
  tightens the compare without disabling it. Cover both parameter 0 (source) and parameter 1 (target)
  positions.

## Cross-links

- Same "compare by jOOQ class identity, not the verbatim name string" pattern and consumer wave as
  R396 (done, source-side FK predicate), R422 (Backlog, `@reference` return-type verdict), R440
  (`fk-join-endpoint-class-identity`, Backlog, FK-join endpoint resolution), and R441
  (`typed-accessor-schema-qualified-table-identity`, Backlog, typed-accessor matching): schema-qualified
  `@table` echoes vs. jOOQ's always-unqualified canonical names.
- Surfaced from a handoff on the `opptak` / `opptak_v2` multi-schema migration; gap D of that
  migration's `graphitron-qualified-names-gaps` report (R441 is the gap E sibling to coordinate the
  identity predicate with). Code references verified against current trunk.

