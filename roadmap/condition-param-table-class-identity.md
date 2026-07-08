---
id: R442
title: "Concrete condition-method table param must match by jOOQ class identity, not bare-vs-qualified name"
status: Ready
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

## Design decision: thread the hop's `TableRef` identity (shape 1)

Of the two candidate shapes the Backlog body listed, thread the resolved `TableRef` down instead of
name strings. Shape 2 (re-resolving `expectedSqlName` through `JooqCatalog.findTable` inside
`checkConcreteParamTable`) is rejected on principle: it recomputes from a string a fact every caller
already holds as a resolved type. All three call paths have (or trivially have) the `TableRef` in
hand:

- `validateWhereFilterParamTables` (`BuildContext.java:1815`) holds `hop.originTable()` /
  `hop.targetTable()` and today calls `.tableName()` on them, throwing the identity away.
- The condition-hop site (`BuildContext.java:1658`) passes `currentSourceSqlName` (the parent's
  possibly-qualified `@table` echo) as source; the same block already resolves that string to a
  `TableRef` via the schema-aware `catalog.findTable` (`conditionOrigin`, `:1646`-`1650`). The target
  is `r.target()`, already a `TableRef`.

Threading identity also dissolves a naming-worlds asymmetry the string plumbing hides: at the
condition-hop site the target ref is built from the method's own second-parameter class via
`toTableRef(entry.get().table().getName())` (`:1795`), so its `tableName()` is always bare and the
qualified-echo bug never fired on that operand; only the source operand did. On the where-filter
path both operands carry the author's echo and both can be qualified. Once the compare is class
identity, none of this matters; the call sites converge on one comparison.

## Implementation

**`TableRef` (`model/TableRef.java`): upgrade `denotesSameTableAs` in place.** Give the existing
`denotesSameTableAs(TableRef)` an identity body: when both sides carry a non-null `tableClass()`,
compare those `ClassName`s for equality; otherwise fall back to the current case-insensitive
name compare. Do *not* add a sibling predicate: two `TableRef`-vs-`TableRef` predicates differing
only in rigor is exactly the trap where a future consumer reaches for the collision-blind one.
Existing consumers (`FieldBuilder:6185`, `GraphitronSchemaValidator:846`, `TypeBuilder:773/824/905`)
are upgraded in place; for single-schema catalogs identity and name agree, and for multi-schema
catalogs identity is strictly more correct. The name fallback exists only for refs constructed
outside the catalog flow (hand-built test fixtures); every catalog-built ref carries a non-null
`tableClass` (`JooqCatalog.toTableRef`, `JooqCatalog.java:1204`). The fallback is the one arm that
can answer *wrongly* under a bare-name collision, so it needs an enforcer: the colliding-schema
pipeline test below proves catalog-built refs take the identity branch (a false green there means
the fallback decided). `sameTable(String)` stays untouched; a string operand has no class to compare.

**`BuildContext`: thread `TableRef` through the validator chain.**

- `validateConditionParamTables(MethodRef, String, String, errors)` →
  `(MethodRef, TableRef source, TableRef target, errors)`; likewise
  `checkConcreteParamTable(..., String expectedSqlName, ...)` → `(..., TableRef expected, ...)`.
  A null `TableRef` means "expected table unknown, skip", preserving the existing
  null-`expectedSqlName` skip.
- `validateWhereFilterParamTables` passes `hop.originTable()` / `hop.targetTable()` directly.
- The condition-hop site (`:1658`) passes the hoisted `conditionOrigin` `TableRef` (null when the
  source is not table-backed, which is the existing skip) and `r.target()`.
- Inside `checkConcreteParamTable`, the param side already resolves to a catalog entry via
  `Class.forName` + `catalog.findTableByClass(cls)`; build its ref via the entry's `toTableRef` and
  compare with `denotesSameTableAs`. Both sides are catalog-built, so the compare is class identity.
- Keep every existing skip (out-of-range position, null expected, wildcard `Table<?>`, concrete type
  not resolving to a catalog table) and keep erroring on genuine mismatch; the fix tightens the
  compare, it does not disable it.
- The error message keeps the expected side's verbatim (possibly qualified) echo via
  `expected.tableName()`. Since a mismatch can now pair two tables sharing a bare name
  (`event` vs `event`), render the declared side schema-qualified (the entry has the schema in hand)
  so the message stays actionable.

**Guards.** `TableNameComparisonCaseGuardTest` (R358) scans for textual `.tableName().equals*`
patterns and excludes `model/TableRef.java` (the predicate home), so it needs no change here; the
compare being deleted in `checkConcreteParamTable` is on `table().getName()`, which the guard never
matched. R358's case-folding contract is preserved by the fallback arm and, on the identity arm, by
`ClassName` equality being case-exact against jOOQ's own generated names (no author-echo casing on
either side).

## Coordinate the shared identity predicate with R441

R441 (`typed-accessor-schema-qualified-table-identity`, gap E) is the accessor-side sibling and
names this gap as its coordination partner: one identity predicate on `TableRef`, not two. R441's
own Spec (landed concurrently with this one) settles on the *identical* predicate design — upgrade
the body of `denotesSameTableAs` to `ClassName` identity with the name-compare fallback for
classless fixture refs — so the two specs agree by construction. Whichever item is implemented
first lands the predicate plus the same-commit audit of the existing `denotesSameTableAs`
consumers R441's plan enumerates; the second implementer reuses it verbatim and drops that part of
its own scope. That mutual ordering is why no `depends-on:` is set. R441's Spec also resolves the
Backlog note's case-guard question: no guard change is needed (the predicate home is excluded from
the scan), which matches the Guards analysis above.

## Tests

Pipeline tier, extending the R379 pattern (`ReferencePathConditionParamTest` + `TestConditionStub`)
with a multi-schema sibling: a stub class whose condition methods are typed with
`no.sikt.graphitron.rewrite.multischemafixture` table classes, and SDL built against the
multi-schema fixture context (as in `MultiSchemaPipelineTest`). The fixture's `event` table collides
across `multischema_a` / `multischema_b`, which is the discriminating shape: same bare name, only
class identity can tell the schemas apart.

- **False-rejection case (the bug):** a terminal `@condition` hop on a parent typed
  `@table(name: "multischema_a.event")`, condition method parameter 0 typed
  `multischema_a.tables.Event`. Today rejected (`event` vs `multischema_a.event`); must classify
  green without widening to `Table<?>`.
- **Genuine mismatch stays an error, now by identity:** same hop, parameter 0 typed
  `multischema_b.tables.Event` — identical bare name, wrong schema. Must still produce the author
  error. This is also the fallback enforcer: catalog-built refs on both sides means a pass here
  proves the identity branch decided.
- **Target position via the where-filter path.** The condition-hop site cannot carry a qualified
  echo on its target operand (see the asymmetry note above), so target-side coverage rides a
  `{table:, condition:}` where-filter hop whose target is the colliding table. The fixture currently
  has no FK touching `event`; add a small additive table to the multi-schema DDL section of
  `graphitron-sakila-db/src/main/resources/init.sql` (e.g. `multischema_a.event_log` with
  `event_id REFERENCES multischema_a.event`), then: parent on `event_log`,
  `{table: "multischema_a.event", condition: filter}`, filter parameter 1 typed
  `multischema_a.tables.Event` must pass, `multischema_b.tables.Event` must error. The same shape
  reversed (parent `@table(name: "multischema_a.event")`, hop to `event_log`) covers the qualified
  source operand on the where-filter path.
- **Existing coverage stays green:** `ReferencePathConditionParamTest` (single-schema sakila) must
  pass unchanged — identity and name compares agree when nothing collides — as must
  `TableRefSameTablePredicateTest`, whose hand-built refs carry no `tableClass` and exercise the
  documented fallback arm. Extend the latter with the identity arm: two refs sharing a bare
  `tableName` but differing in `tableClass` are not the same table; two refs with equal
  `tableClass` and differently-cased names are.

## Cross-links

- Same "compare by jOOQ class identity, not the verbatim name string" pattern and consumer wave as
  R396 (done, source-side FK predicate), R422 (Backlog, `@reference` return-type verdict), R440
  (`fk-join-endpoint-class-identity`, Backlog, FK-join endpoint resolution), and R441
  (`typed-accessor-schema-qualified-table-identity`, Backlog, typed-accessor matching): schema-qualified
  `@table` echoes vs. jOOQ's always-unqualified canonical names.
- Surfaced from a handoff on the `opptak` / `opptak_v2` multi-schema migration; gap D of that
  migration's `graphitron-qualified-names-gaps` report (R441 is the gap E sibling to coordinate the
  identity predicate with). Code references verified against current trunk.

