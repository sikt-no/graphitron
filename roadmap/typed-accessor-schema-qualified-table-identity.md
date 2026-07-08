---
id: R441
title: "Typed-accessor match must compare jOOQ table identity, not bare @table name"
status: Spec
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

## Plan

This is a "carry the decision as a type" gap: `TableRef.tableClass` is the reified table identity,
resolved once at the parse boundary and carried on every catalog-constructed ref, yet the consumers
re-derive "same table?" from the `tableName` string, which is the verbatim `@table` echo and can be
schema-qualified or case-divergent. The fix routes the comparison through the carried identity.

### 1. `denotesSameTableAs` becomes the identity predicate (`model/TableRef.java`)

Upgrade the **body** of the existing `denotesSameTableAs(TableRef)` rather than adding a second
ref-vs-ref predicate. Two predicates answering the same conceptual question with different answers
is the drift smell the item exists to kill; the family (R396 done, R422, R440, gap D) should
converge on **one** ref-level predicate.

```java
/**
 * True when {@code other} denotes the same table as this ref. Compares the reified jOOQ
 * table-class identity ({@code tableClass}) when both sides carry one — this is what
 * distinguishes same-named tables across schemas and matches a schema-qualified @table echo
 * against jOOQ's unqualified canonical name. Falls back to the case-insensitive name compare
 * only when either side lacks a tableClass, which catalog-constructed refs never do
 * (JooqCatalog.TableEntry.toTableRef always populates it); the fallback exists for
 * fixture-built partial refs in unit tests. Null-safe: a null {@code other} is not this table.
 */
public boolean denotesSameTableAs(TableRef other) {
    if (other == null) return false;
    if (tableClass != null && other.tableClass() != null) {
        return tableClass.equals(other.tableClass());
    }
    return sameTable(other.tableName());
}
```

- `sameTable(String)` stays name-based, unchanged: it is the explicitly weaker path for callers
  that only hold a string, and the R358 case-folding contract it carries is still correct there.
- The null-class fallback is a deliberate, documented seam, keyed on fixture construction, not on
  any production-reachable state: `toTableRef` (`JooqCatalog.java:1204`) always populates
  `tableClass`, so production comparisons always take the identity arm. The unit test pins all
  three arms (below) so a silent regression to bare-name behaviour cannot pass.
- javapoet `ClassName.equals` is structural, so two refs built from the same jOOQ class compare
  equal regardless of construction path.

### 2. Thread the expected `TableRef` into `collectAccessorMatches` (`FieldBuilder.java`)

- Change `collectAccessorMatches(Class<?>, String, String, boolean, String expectedSqlName)`
  (`FieldBuilder.java:5561`) to take `TableRef expectedTable` (nullable) instead of the
  `String expectedSqlName`.
- The filter at `:5582` becomes
  `if (expectedTable != null && !elementTableRef.get().denotesSameTableAs(expectedTable)) continue;`.
  The accessor-side operand comes from `resolveTableByRecordClass` (`:5580`), which resolves by
  record-class identity in the catalog, so both operands carry real `tableClass`es and the compare
  is identity-vs-identity.
- Caller `deriveAccessorRecordParentSource` (`:5468`) passes `expectedTable` (it already holds the
  full `TableRef` as `tb.table()`; today it strips it to `.tableName()` at the call).
- Caller `derivePolymorphicHubSource` (`:5779`) keeps passing `null` (hub discovery, no expected
  table); no behaviour change on that path.
- Diagnostics are untouched: the ambiguity message (`:5485`) keeps quoting
  `expectedTable.tableName()`, the verbatim echo, so author errors still echo what the user wrote.
- Update the `collectAccessorMatches` javadoc paragraph that currently describes the
  case-insensitive SQL-name match to describe the identity match.

### 3. Same-commit audit of the other `denotesSameTableAs` consumers

Upgrading the predicate body silently switches five existing call sites to identity comparison.
Each compares two same-catalog-derived refs, so a true match stays true and any change of verdict
is a cross-schema false-positive becoming correctly false — strictly tightening — but the audit
must be recorded per site in the implementation commit (assert or argue, don't assume):

- `TypeBuilder.java:773` / `:824` / `:905` (interface/implementor table agreement and FK-target
  checks): both operands resolve through the catalog; a qualified `@table` echo on one side today
  produces a spurious mismatch here, so the identity route fixes the same latent bug class.
- `GraphitronSchemaValidator.java:846` (path terminal vs target table): same shape.
- `FieldBuilder.java:6185` (hop origin vs parent table): same shape.

No new tests are required per site if the same-catalog-operands argument holds on inspection; if
any site turns out to compare a fixture-built classless ref in an existing test, that test keeps
the name-fallback arm and stays green by construction.

### 4. Family coordination (gap D, R440, R422)

- **Gap D is filed as R442 (`condition-param-table-class-identity`, Backlog)** and stays **out of
  scope** here, but the predicate is shaped for it: `checkConcreteParamTable`
  (`BuildContext.java:1854`) holds a catalog entry (from `findTableByClass`) and the hop's
  `TableRef`s, so R442's recommended shape 1 becomes "thread `TableRef`s instead of `String`s and
  call `denotesSameTableAs`" once this item lands the predicate.
- The family now has two identity *homes* that must not drift into a third: R396's primitives on
  `JooqCatalog` compare raw jOOQ table classes (`endpoint.getClass() == resolvedSource.getClass()`)
  at the parse boundary, where raw `Table<?>` objects are still in scope; `denotesSameTableAs`
  compares the reified `ClassName` in the model, past the boundary. They agree by construction
  (both derive from the same generated jOOQ class at parse time). State this in the
  `denotesSameTableAs` javadoc so a future consumer picks by *where it stands* (boundary → catalog
  primitive, model → ref predicate) instead of adding a third mechanism.
- R440 (FK-join endpoint) and R422 (return-type terminal verdict) adopt whichever home matches
  their comparison point; neither is blocked by or blocks this item.

## Tests

- **Unit tier** — extend `TableRefSameTablePredicateTest` to pin all three predicate arms:
  1. same `tableClass`, divergent names/casing (e.g. `"multischema_a.event"` echo vs `"event"`)
     → true;
  2. **same bare name, different `tableClass`** (the cross-schema case) → false — without this arm
     a regression to bare-name behaviour passes silently;
  3. either side's `tableClass` null → name-compare fallback (the existing case-folding assertions
     already exercise this arm; add one explicit null-class-vs-class case).
- **Pipeline tier** — over the existing multischema fixture, which already has colliding `event`
  tables in `multischema_a` / `multischema_b` (`init.sql`):
  1. *Qualified match (the reported bug):* a free-form DTO payload parent (dummy class under
     `no.sikt.graphitron.codereferences.dummyreferences`) exposing a typed accessor returning
     `multischema_a`'s `EventRecord` (e.g. `List<EventRecord> getEvents()`), with the payload
     element type declared `@table(name: "multischema_a.event")` (the qualification is *forced*:
     bare `event` is ambiguous across the two schemas). Today the bare-name compare drops the
     accessor and the field rejects with the `RecordTableField … requires a typed accessor or
     @sourceRow` error (`FieldBuilder.java:5408`); after the fix it must classify green with the
     accessor-derived source.
  2. *Genuine mismatch (the tightening guard):* same shape but the accessor returns
     `multischema_b`'s `EventRecord` while the element type stays
     `@table(name: "multischema_a.event")` → the accessor is still dropped and the rejection still
     fires. This also pins the *latent false-match* direction: the two failure modes are distinct —
     the reported bug is a qualified echo matching *nothing* (false negative), while a bare-name
     compare over colliding schemas would match the *wrong* schema's record (false positive);
     identity comparison closes both.
- **Case-guard test (`TableNameComparisonCaseGuardTest`) — no change expected**, resolving the
  cross-links note below: the predicate body (including its name-fallback `equalsIgnoreCase`)
  lives in `model/TableRef.java`, the guard's excluded `PREDICATE_HOME`, and the identity arm
  compares `tableClass`, which the guard's `tableName()` patterns never match. If implementation
  finds otherwise, the guard update is mechanical and stays within the guard's stated scope.

Reactor stays green under `mvn install -Plocal-db`; no emitter behaviour change is expected
outside the newly-classifying payload shape.

## Cross-links / guards

- Same bug class and consumer wave as R396 (done, source-side FK predicate), R422 (Backlog,
  `@reference` return-type verdict), and R440 `fk-join-endpoint-class-identity` (Backlog, FK-join
  endpoint resolution) — schema-qualified `@table` echoes vs. jOOQ's always-unqualified canonical
  names. This is the typed-accessor sibling of that family; the identity predicate it introduces on
  `TableRef` is the same one R440 needs on the FK-join side, so coordinate the primitive across the
  family rather than growing one per consumer.
- R358 / R359 + `TableNameComparisonCaseGuardTest` are **case-folding** guards only: they enforce that
  bare-name compares route through `sameTable` for case-insensitivity. They do **not** address
  cross-schema collisions, and in fact codify the bare-name predicate this item supersedes with an
  identity path. Contrary to this item's original Backlog expectation, the plan does **not** touch the
  case-guard test: the identity route lands inside `denotesSameTableAs`'s body in `model/TableRef.java`,
  the guard's excluded predicate home (see Tests, last bullet).
- Author-side workarounds are why this needs a real fix: keeping `@table` unqualified is impossible
  when the bare name collides across schemas, and adding `@sourceRow` everywhere is invasive across
  many payload types.

## Surfaced from

Handoff from a sibling session working the `opptak` / `opptak_v2` multi-schema migration; gap E of
that migration's `graphitron-qualified-names-gaps` report (gap D is the `checkConcreteParamTable`
sibling to coordinate with). Code references verified against current trunk.
