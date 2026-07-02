---
id: R396
title: "@reference FK-connection validation rejects schema-qualified or case-mismatched @table base names"
status: In Review
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-29
last-updated: 2026-07-02
---

# @reference FK-connection validation rejects schema-qualified @table base names

## Problem (fixed)

A type whose `@table(name:)` carried a schema prefix (`@table(name: "multischema_a.signal")`) or a
case difference (`multischema_a.SIGNAL` over the real lowercase `multischema_a.signal`) could not
attach an `@reference(path: [{key: "<fk>"}])` field: schema validation rejected it with
`Author error: Field '<Type>.<field>': key '<fk>' does not connect to table '<name>'`. The same
`@reference` with an unqualified `@table(name: "signal")` validated fine. This was the `@reference`
sibling of R395; here the divergence surfaced at schema-validation time on the FK-connection-and-
orientation path. Reported against **10.0.0-RC21** (`opptak`), the same consumer wave as R395.

## Root cause

The FK source-side "which end of the FK is the source" decision was recomputed at multiple consumers
from raw strings: the verbatim, case-preserved `@table(name:)` echo (which may carry a schema prefix)
compared by bare `equalsIgnoreCase` against jOOQ FK endpoint names, which are always unqualified. The
schema prefix made `"multischema_a.signal".equalsIgnoreCase("signal")` false, so the FK read as "not
connected"; where a partial fix let it through, the same bare compare in the orientation predicate
silently mis-oriented the join (origin/target swapped, slot pairing inverted).

## Implemented — one catalog-side identity primitive

Adopted identity comparison, not input sanitization. The FK source-side predicate moved to two
primitives on `JooqCatalog` (the canonical holder of raw jOOQ `Table`/`ForeignKey`):

- `foreignKeyTouchesTable(fk, sourceSqlName)` — source-side membership (either endpoint).
- `foreignKeyOnSource(fk, sourceSqlName, selfRefHint)` — orientation (which end is the source);
  self-referential FKs fall to the caller's `selfRefHint`.

Both resolve `sourceSqlName` through the existing `findTable` (schema-aware, case-insensitive) and
compare FK endpoints by **jOOQ table class identity** (`endpoint.getClass() == resolvedSource.getClass()`),
falling back to the historical bare `equalsIgnoreCase` when the source does not resolve to a single
class (`Ambiguous` / `NotInCatalog`), so the diagnostic surface for genuinely-unknown names is
unchanged. Class identity also distinguishes `multischema_a.signal` from a same-named
`multischema_b.signal`, which a normalized bare-name compare cannot. The verbatim echo stays the
source name everywhere, so the author-error diagnostic still quotes what the user wrote; only the
comparison changed.

**Sites routed through the primitives (all six enumerated in the original spec):**

- **Phase 1 — reported `{key:}` path.** `parsePathElement` connection check → `foreignKeyTouchesTable`.
  Orientation decided once via `foreignKeyOnSource` in `synthesizeFkJoin` and threaded into
  `resolveFkSlots` as a precomputed `boolean fkOnSource` (signature changed from
  `(f, sourceSqlName, selfRefFkOnSource)`), so the FK-orientation predicate now lives in exactly one
  place. `resolveRecordFkTargetColumns` (site 5) uses the primitive for both its implicit-inference
  directional filter and its slot orientation.
- **Phase 2 — `{table:}` and empty-inference forms.** `findForeignKeysBetweenTables` resolves each
  argument to class identity (bare-name fallback per argument). `findUniqueFkToTable` (site 4) and
  `qualifierForFk` (site 6) re-filter their candidates through `foreignKeyOnSource`.

**Deviation from spec (surface to reviewer):** the spec's parenthetical suggested routing
`qualifierForFk` through `foreignKeyTouchesTable` ("source-side membership, not orientation"). That
would break the existing `qualifierForFk_wrongSourceTable_returnsEmpty` unit test (the target-side
`film` *touches* `inventory_film_id_fkey`), and it loosens the method's strictly source-side
semantics. Used `foreignKeyOnSource(fk, source, selfRefHint=true)` instead, which faithfully
reproduces the original `getTable().getName()==source` guard.

## Residual — Phase 3 split out to R422

The qualified **return-type** terminal-target verdict (`computeTerminalTargetVerdict` via
`TableRef.sameTable`) is a different author shape (qualified return type, not qualified source) with a
wider ripple across `parsePath`'s `String` return-name plumbing. Filed as **R422**
(`reference-terminal-verdict-return-type-identity`) per the spec's scope recommendation.

## Tests

- **Unit** (`JooqCatalogMultiSchemaTest`): `foreignKeyTouchesTable` / `foreignKeyOnSource` over
  `signal_widget_id_fkey` with schema-qualified and upper-case sources (true), the referenced side
  (touches true / onSource false), a non-endpoint table (false), and cross-schema same-name
  distinction; `findForeignKeysBetweenTables` with both args qualified; a `synthesizeFkJoin`
  qualified-source orientation guard pinning origin=signal / target=widget / slot orientation.
- **Pipeline** (`QualifiedSourceReferencePipelineTest`): all three `@reference` forms (`{key:}`,
  `{table:}`, empty `path: []`) plus the schema-qualified-and-upper-case spelling classify to a
  correctly-oriented `FkJoin` with no author error.
- **Execution** (`MultiSchemaQueryTest`): the R395 fixture is tightened from `@table(name: "signal")`
  to its originally-specified `@table(name: "multischema_a.SIGNAL")`; rows still route to the
  discriminated types and `AlertSignal.widgetName` populates through the now-validated cross-table
  `@reference`. R395's discriminator-qualifier coverage is preserved (FROM still renders
  `"multischema_a"."signal"`).

Full reactor green under `mvn install -Plocal-db`.

## On approval

Delete this file and add a one-line `changelog.md` entry citing the landing SHA and `R396`.
