---
id: R396
title: "@reference FK-connection validation rejects schema-qualified or case-mismatched @table base names"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# @reference FK-connection validation rejects schema-qualified @table base names

## Problem

A type whose `@table(name:)` carries a schema prefix (for example
`@table(name: "multischema_a.signal")` or `@table(name: "multischema_a.SIGNAL")` over the real
lowercase `multischema_a.signal`) cannot attach an `@reference(path: [{key: "<fk>"}])` field: schema
validation rejects it with `Author error: Field '<Type>.<field>': key '<fk>' does not connect to
table '<name>'`. The same `@reference` with an unqualified `@table(name: "signal")` validates and
resolves fine. This is the `@reference` sibling of R395 (which fixed the discriminator qualifier
comparing a directive string to the rendered jOOQ name); here the divergence surfaces at
schema-validation time as an author error, on the FK-connection-and-orientation path.

Discovered while implementing R395: the planned `multischema` execution fixture wanted a
schema-qualified `@table(name: "multischema_a.SIGNAL")` discriminated interface with a cross-table
`@reference`; the FK-connection check rejected the qualified form, so R395 worked around it with an
unqualified `@table(name: "signal")` (`signal` is unique to `multischema_a`, so it still resolves and
still renders the FROM token schema-qualified, exercising R395's discriminator bug). Fixing R396 lets
consumers author schema-qualified base tables with `@reference` fields, and lets the R395 execution
fixture be tightened to its originally-specified `multischema_a.SIGNAL` form.

## Root cause

The source table SQL name threaded through `@reference` path parsing in `BuildContext` is
`TableRef.tableName()` — the **verbatim, case-preserved `@table(name:)` echo** (so error messages quote
what the author wrote), which may carry a schema prefix. It is compared by bare-string
`equalsIgnoreCase` against jOOQ FK endpoint names (`f.getTable().getName()` /
`f.getKey().getTable().getName()`), which are always unqualified. The schema prefix makes
`"multischema_a.signal".equalsIgnoreCase("signal")` false, so the FK is treated as not connected.

Note the operative failing dimension is the **schema qualifier, not letter case**: the comparison is
already `equalsIgnoreCase`, so an unqualified `@table(name: "SIGNAL")` against real `signal` already
passes end-to-end. The Backlog item's "case-mismatched" example (`multischema_a.SIGNAL`) failed because
it was *also* schema-qualified. The fix below is identity-based and so covers both dimensions
uniformly, but the only shape that regresses today is the schema-qualified one.

The same source name feeds **three** source-derived comparison sites, all on the FK source-side
predicate "which end of the FK is the source":

1. `parsePathElement` FK-connection check (lines 1539-1545) — the reported author error.
2. `synthesizeFkJoin` join-direction (`sourceSqlName.equalsIgnoreCase(fkSideTable)`, line 1358).
3. `resolveFkSlots` join-direction (line 1415).

So fixing only site 1 is wrong: the schema-qualified name then flows into sites 2 and 3 where the same
bare compare returns false and **silently mis-orients the join** (`targetSqlName` becomes the source
table instead of the referenced table; the slot pairing inverts). The triplicated predicate is itself
the smell: the same FK source-side decision is recomputed from raw strings at three consumers, and that
is exactly why a partial fix drifts.

Two further sites carry the identical defect on the same `@table`-qualified author shape:

- `JooqCatalog.findForeignKeysBetweenTables(a, b)` (bare-`equalsIgnoreCase` on both args against
  endpoint names) backs the `@reference(path: [{table: "..."}])` form (BuildContext line 1574) and the
  empty-path FK inference (line 1232); `resolveFkSlots`' second caller `resolveRecordFkTargetColumns`
  (R315, line 2633) passes a verbatim echo too.
- `TableRef.sameTable` (verbatim-echo `tableName.equalsIgnoreCase(other)`) backs the R379 terminal-target
  verdict (`computeTerminalTargetVerdict`, line 1307), comparing the canonical resolved target against
  the **return type's** verbatim `@table` echo — so a schema-qualified *return* type spuriously reports
  `Mismatch` even when the hop lands correctly. This is the same bug class as sites 1-3, differing only
  in which `@table` carries the prefix (parent vs. return type).

## The fix: one catalog-side identity primitive

Adopt identity comparison, not input sanitization (the "Classification belongs at the parse boundary"
and "Generation-thinking" principles in `rewrite-design-principles.adoc`). The FK source-side predicate
moves to a single primitive on `JooqCatalog` (the canonical holder of raw jOOQ `Table`/`ForeignKey`;
`BuildContext`'s jOOQ surface is a narrow diagnostic carve-out we must not widen) that:

- resolves the source SQL name through the existing `findTable(String)` (already schema-aware and
  case-insensitive, returning `TableResolution.Resolved`), then
- compares FK endpoints to the resolved source by **jOOQ table class identity**
  (`fk.getTable().getClass() == sourceEntry.table().getClass()`), the same schema-unique class-identity
  comparison `findTableByClass` already relies on.

Class identity (not normalized bare names) fixes the schema-qualifier bug **and** the latent
cross-schema same-name collision in one stroke: a bare-name compare, even after normalization, cannot
distinguish `multischema_a.signal` from `multischema_b.signal`; class identity can.

Proposed catalog surface (exact names at implementer's discretion):

```java
/** True when sourceSqlName resolves to a catalog table that is an endpoint of fk (either side). */
boolean foreignKeyTouchesTable(ForeignKey<?,?> fk, String sourceSqlName);

/** Which end of fk the source sits on. Self-referential FKs cannot be told apart by identity,
 *  so the caller's existing selfRef hint decides; non-self-ref resolves by class identity. */
boolean foreignKeyOnSource(ForeignKey<?,?> fk, String sourceSqlName, boolean selfRefHint);
```

The source SQL name (`currentSourceSqlName`) stays the verbatim echo everywhere, so the author-error
diagnostic still quotes what the user wrote; only the *comparison* changes.

## Phase 1 — the reported `{key:}` path (unblocks the R395 fixture)

- **Connection check** (`parsePathElement`, 1539-1545): replace the two bare `equalsIgnoreCase` clauses
  with `!catalog.foreignKeyTouchesTable(f, currentSourceSqlName)`. The `candidateHint` still echoes the
  verbatim `currentSourceSqlName` and the bare endpoint names. Runs before orientation, so once it
  passes the source is guaranteed an endpoint.
- **Collapse the orientation predicate** (the Generation-thinking refinement): decide `fkOnSource`
  **once** via `catalog.foreignKeyOnSource(f, sourceSqlName, selfRefHint)` in `synthesizeFkJoin`, and
  change `resolveFkSlots`' signature to accept the precomputed `boolean fkOnSource` instead of
  recomputing it from `(sourceSqlName, selfRefFkOnSource)`. Update both `resolveFkSlots` callers
  (`synthesizeFkJoin` line 1377; `resolveRecordFkTargetColumns` line 2633 computes its orientation via
  the same catalog primitive and passes it in). After this the FK source-side predicate exists in
  exactly one place.

## Phase 2 — the `{table:}` and empty-inference forms (same author shape, shared primitive)

Route `JooqCatalog.findForeignKeysBetweenTables(a, b)` through the same resolve-to-class-identity step:
resolve each argument via `findTable`; match FKs whose endpoint classes equal the two resolved source
classes in either direction; fall back to the current bare `equalsIgnoreCase` only when an argument does
not resolve (preserving today's behaviour for genuinely-unknown names). This makes
`@reference(path: [{table: "..."}])` and bare-`@reference` inference accept a schema-qualified parent
`@table`, completing directive-form coverage rather than fixing one of three forms of one directive.

## Phase 3 — the return-type qualifier (R379 terminal verdict) — fold in or split out

The terminal-target verdict (`computeTerminalTargetVerdict`, 1307) compares the canonical resolved
target against the **return type's** verbatim `@table` echo via `TableRef.sameTable`, so a
schema-qualified *return* `@table` spuriously reports `Mismatch`. Fix: compare by identity instead of
`sameTable` over a possibly-qualified string — the `parsePath` callers already hold the return type's
`TableRef` (which carries `tableClass`, a `ClassName`), so threading that identity (or resolving
`returnSqlTableName` through the catalog before comparison) closes the gap.

**Scope decision (surface to reviewer).** Phase 3 is a *different author shape* (qualified return type,
not qualified source) and carries the widest ripple: `parsePath` threads return names as `String` across
~12 call sites, so an identity-based terminal check touches that signature or adds a catalog resolve at
the one comparison point. Recommendation: keep R396 to Phases 1-2 (one author shape — schema-qualified
source `@table` — across all three `@reference` directive forms, sharing one primitive, and exactly the
dimension the R395 fixture needs), and file Phase 3 as a focused follow-up (`@reference` terminal-target
verdict must compare return-type identity, not the verbatim echo). Folding Phase 3 in is defensible if
we want full qualifier-resilience in one item; the minimal-comparison-point variant (resolve
`returnSqlTableName` to identity inside `computeTerminalTargetVerdict`, no signature change) keeps the
ripple small enough to include. Defaulting to Phases 1-2 unless the reviewer prefers the fold-in.

## Tests

Per the design principles, behaviour is pinned at the pipeline and execution tiers; code-string
assertions are banned. Add a pinning test for each dimension actually fixed (no claiming coverage the
fixtures do not provide).

### Unit (`JooqCatalog`, `multischemafixture` catalog)

The fixture catalog already carries `signal` / `widget` / `signal_widget_id_fkey` (generated from
`init.sql`). Pin the new primitives directly:

- `foreignKeyTouchesTable(signal_widget_id_fkey, "multischema_a.signal")` and
  `... "multischema_a.SIGNAL"` → true; `... "multischema_a.widget"` → true; a non-endpoint table → false.
- `foreignKeyOnSource(signal_widget_id_fkey, "multischema_a.signal", false)` → true (FK sits on
  `signal`); from the `widget` side → false.
- (Phase 2) `findForeignKeysBetweenTables("multischema_a.signal", "multischema_a.widget")` returns the FK.

### Unit (`BuildContext.synthesizeFkJoin`, the `SynthesizeFkJoinReorderedKeysTest` pattern)

Drive `synthesizeFkJoin(signal_widget_id_fkey, "multischema_a.signal", ...)` against the
`multischemafixture` catalog and assert `Resolved` with origin = `signal`, target = `widget`, and slot
orientation `source = signal.widget_id` / `target = widget.widget_id`. This pins correct orientation
under a schema-qualified source — the silent-mis-orientation regression a site-1-only fix would leave.

### Pipeline tier

Classify a schema-qualified `@table` parent with `@reference(path: [{key: ...}])` and assert the model
carries an `FkJoin` with the correct origin/target `TableRef` identity and slot orientation, and **no**
author error. Add the `{table:}` and empty-inference variants for Phase 2. (If Phase 3 is folded in, a
schema-qualified *return* `@table` whose terminal verdict resolves to `Match`.)

### Execution tier (tighten the R395 fixture to its originally-specified form)

In `graphitron-sakila-example/src/main/resources/graphql/multischema.graphqls`, change the `Signal`
interface and its `AlertSignal` / `NoticeSignal` implementors from `@table(name: "signal")` to
`@table(name: "multischema_a.SIGNAL")` (schema-qualified + upper-case, R395's originally-specified
form, covering the schema and case dimensions together). `MultiSchemaQueryTest.signalsRouteToDiscriminatedTypesUnderNamedSchema`
must still route rows to the discriminated types **and** populate `AlertSignal.widgetName` through the
now-validated cross-table `@reference`; before R396 this fails at schema validation. The DDL/seed and
`jooq.codegen.schema.version` are unchanged (the table is untouched; only the directive spelling moves).
Confirm R395's discriminator-qualifier coverage is preserved: the qualified `@table` still renders FROM
as `"multischema_a"."signal"`, so the discriminator projection / IN filter / LEFT JOIN gate are still
exercised under a named schema (the fixture now strengthens R395 to its intended shape rather than
weakening it).

## Roadmap entries

On implementation: trim this file to its residual (the Phase 3 follow-up note, if Phase 3 is split out),
flip `status:` to `In Review`, regenerate the README. On approval: delete the file and add a one-line
`changelog.md` entry citing the landing SHA and `R396` (this closes a schema-validation gap with a named
root cause shared with R395, worth keeping in the changelog).

## Notes

- Reported against the released **10.0.0-RC21** line, same consumer wave as R395 (`opptak`); a
  schema-qualified `@table` base with `@reference` is the consumer's real shape.
- This item modifies the multischema fixture R395 introduced; that is expected and was anticipated by
  R395's deviation note. R395 should reach Done first (or land alongside) so the fixture change is not
  contested across two in-flight items.
