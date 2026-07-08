---
id: R441
title: "Typed-accessor match must compare jOOQ table identity, not bare @table name"
status: Backlog
bucket: bug
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Typed-accessor match must compare jOOQ table identity, not bare @table name

## Problem

Same multi-schema migration as R422 (its sibling; both descend from gap E / gap D of the
`graphitron-qualified-names-gaps` report). Two jOOQ-generated schemas (`opptak`, `opptak_v2`) share
bare table names. Once an element type's `@table` is schema-qualified to disambiguate the collision,
mutation payload types that previously built green start failing:

```
RecordTableField on a free-form DTO parent requires a typed accessor or @sourceRow …;
the catalog has no FK metadata for the parent class
```

with cascading `WrapperArm errors transport` failures on the sibling `errors` field. The typed
accessor that used to satisfy the payload's parent stops matching, because the match is keyed on the
**bare** table name and a schema-qualified `@table` echo never equals it.

## Root cause

The accessor match is by bare SQL name, even though the class identity that would match correctly is
already in hand at the comparison point:

- `TableRef.sameTable` (`model/TableRef.java:72`) is `tableName.equalsIgnoreCase(other)` — a
  verbatim-echo compare. `TableRef` already carries a `tableClass` (`ClassName`) component
  (`model/TableRef.java:55`), but **no predicate uses it**; even the `sameTable(TableRef)` overload
  (`:78`) delegates back to the bare-name compare.
- `FieldBuilder.collectAccessorMatches` (`FieldBuilder.java:5582`) filters accessors via
  `elementTableRef.get().sameTable(expectedSqlName)`, so a schema-qualified `expectedSqlName` never
  matches the always-unqualified canonical `tableName`, and the accessor is silently dropped.
- The identity is right there and thrown away: two lines up (`FieldBuilder.java:5580`) the accessor's
  element table is resolved via `ServiceCatalog.resolveTableByRecordClass`, which finds the **right**
  table by class identity in the catalog (`ServiceCatalog.java:63`, `findTableByRecordClass`) — then
  returns a `TableRef` whose `tableName` is bare (`e.table().getName()`), so the surviving identity is
  discarded at the `:5582` compare.

## The fix (intent; Spec settles mechanism)

Match by jOOQ class identity rather than by SQL-name string. Add an identity-based predicate (e.g.
give `TableRef.sameTableAs(TableRef)` a real `tableClass()`-comparison body instead of delegating to
`sameTable`) and route `collectAccessorMatches` through it, threading the expected table's `TableRef`
/ `ClassName` rather than a bare `expectedSqlName` string. Same principle R396 (done) applied to the
FK source-side predicate and R422 (Backlog) will apply to the `@reference` return-type verdict:
adopt jOOQ table-class identity comparison on the catalog and stop comparing verbatim `@table` echoes.

**Coordinate the identity predicate with gap D (`checkConcreteParamTable`).** That gap is a second
consumer of the same bare-name compare; both should land on **one** identity predicate on `TableRef`,
not two. Whichever item ships first introduces the helper; the other reuses it.

## Tests

- Pipeline tier: a mutation payload whose element type resolves to a bare table name that collides
  across two schemas (`@table(name: "opptak.foo")` vs. an `opptak_v2.foo`) must still find its typed
  accessor — assert the accessor matches and the payload builds green, where today it errors with the
  `RecordTableField … requires a typed accessor or @sourceRow` cascade. Pair with a genuine mismatch
  (accessor's element table is a different table than the qualified expected one) that still drops the
  accessor, so the fix tightens the compare without disabling it.

## Cross-links / guards

- Same bug class and consumer wave as R396 (done, source-side FK predicate), R422 (Backlog,
  `@reference` return-type verdict), and R440 `fk-join-endpoint-class-identity` (Backlog, FK-join
  endpoint resolution) — schema-qualified `@table` echoes vs. jOOQ's always-unqualified canonical
  names. This is the typed-accessor sibling of that family; the identity predicate it introduces on
  `TableRef` is the same one R440 needs on the FK-join side, so coordinate the primitive across the
  family rather than growing one per consumer.
- R358 / R359 + `TableNameComparisonCaseGuardTest` are **case-folding** guards only: they enforce that
  bare-name compares route through `sameTable` for case-insensitivity. They do **not** address
  cross-schema collisions, and in fact codify the bare-name predicate this item must supersede with an
  identity path. Expect the case-guard test to need updating when `sameTable` gains an identity route.
- Author-side workarounds are why this needs a real fix: keeping `@table` unqualified is impossible
  when the bare name collides across schemas, and adding `@sourceRow` everywhere is invasive across
  many payload types.

## Surfaced from

Handoff from a sibling session working the `opptak` / `opptak_v2` multi-schema migration; gap E of
that migration's `graphitron-qualified-names-gaps` report (gap D is the `checkConcreteParamTable`
sibling to coordinate with). Code references verified against current trunk.
