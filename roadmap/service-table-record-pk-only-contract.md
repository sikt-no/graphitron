---
id: R516
title: "Narrow SourceKey.Wrap.TableRecord contract to PK-only, revert full-row projection"
status: Spec
bucket: correctness
priority: 2
theme: service
depends-on: []
created: 2026-07-23
last-updated: 2026-07-23
---

# Narrow SourceKey.Wrap.TableRecord contract to PK-only, revert full-row projection

## Problem

`docs/manual/how-to/handle-services.adoc` currently documents (around the `@service`-on-a-child-field
sections) that a `@service` child field keyed via `SourceKey.Wrap.TableRecord` receives a
"fully-populated parent record... every column on the parent table." This promise is architecturally
wrong. The contract between Graphitron and service authors must be PK-only: the framework hands the
service a batch of parent PKs (as a typed `TableRecord` carrying only PK columns), and if the service
needs other columns it fetches them itself in one batched query via the injected `DSLContext`, using
the database the way it's supposed to be used, rather than relying on the framework to smuggle
arbitrary columns through the parent SELECT.

This wrong promise was built up across a chain of roadmap items, each treating a symptom rather than
the root premise:

- **R425** (legitimate, keep the bug fix, re-scope the shape): fixed a real bug — a missed
  pattern-match arm meant a `@service`/`@splitQuery` child's key columns weren't force-included in
  the parent projection, causing silent-null DataLoader keys under federation `_entities` fetches.
  The fix itself is correct and should be *kept*, but re-scoped: for `Wrap.TableRecord` it should
  force-include only the parent's PK column(s) as base-named columns (the same `baseColumns`
  mechanism already used for `Wrap.Row`/`Wrap.Record`), not a full-row projection.
- **R426** (revert): took R425's premise further and promised the *full* parent row is always
  projected for `Wrap.TableRecord` children, because an existing example
  (`FilmService.titleTitlecase`, `graphitron-sakila-service/src/main/java/no/sikt/graphitron/rewrite/test/services/FilmService.java:140-147`)
  read a non-PK column (`film.getTitle()`) off the parent record and happened to work only when the
  client's own selection coincidentally included `title`. The fix should have been to correct
  `titleTitlecase` to fetch its own data, the way `CityService.cityUppercase`
  (`graphitron-sakila-service/src/main/java/no/sikt/graphitron/rewrite/test/services/CityService.java:39-51`)
  already correctly does via an injected `DSLContext` — not to make the framework guarantee full
  rows. The two services demonstrate two different, inconsistent contracts for the same directive
  today.
- **R436** (revert): built the reserved `__src_<col>__` full-row aliasing scheme to avoid
  multiset-alias collisions — machinery that exists only to support R426's full-row premise.
- **R511** (revert/simplify): added a runtime `instanceof` fork in
  `GeneratorUtils.buildKeyExtraction` to reconcile two parent arrival shapes (SQL-projected generic
  `Record` vs. a typed record returned directly by a service) for full-row reconstruction. Once only
  the PK is ever read, the same field-identity read (`source.get(Tables.X.PK_COL)`) works uniformly
  across both arrival shapes — the SQL-projected row has the PK force-included as a base-named
  column, and a service-returned typed record always carries its own PK as a real column — so no
  runtime type fork should be needed at all.

## Design

### The core mechanism is a narrowing, not a rebuild

`TypeClassGenerator.collectRequiredProjection` (`TypeClassGenerator.java:569-616`) already computes
`bk.sourceKey().columns()` as the parent's PK columns for the `Wrap.TableRecord` case — identical to
every other wrap — and then discards it in favor of `reservedFullRow = true`:

```java
case BatchKeyField bk when bk.sourceKey() != null -> {
    if (bk.sourceKey().wrap() instanceof SourceKey.Wrap.TableRecord) {
        reservedFullRow = true;                       // <- discards sourceKey().columns()
    } else {
        columns.addAll(bk.sourceKey().columns());
    }
}
```

The fix deletes the special case: `Wrap.TableRecord` falls into the same `columns.addAll(...)` branch
as `Wrap.Row`/`Wrap.Record`. `RequiredProjection` collapses from its current two-axis
`(reservedFullRow, baseColumns)` shape back to a single `baseColumns` list — no separate axis, no
"regardless of the user's SDL selection, unconditionally emit the whole row" special case for this one
wrap.

`GeneratorUtils.buildKeyExtraction`'s `TableRecord` arm (`GeneratorUtils.java:580-648`) collapses from
the R511 runtime `instanceof` fork to one unconditional form: for each PK `ColumnRef`, read it off
`source` by field identity/base name and set it on the freshly constructed key record —

```java
Record source = (Record) env.getSource();
XRecord key = new XRecord();
for (ColumnRef col : parentTable.primaryKeyColumns()) {
    key.set(Tables.X.<COL>, source.get(Tables.X.<COL>));
}
```

— no `__src_<col>__` reserved aliases, no `instanceof` branch. This is safe because the PK is present
under its base name on both arrival shapes: force-included as a base-named column when the parent came
from `$fields`, and naturally present as a real column when a service hands back its own typed record
(a jOOQ-generated record always carries its declared columns, PK included). Reverting R436's
reserved-alias scheme does not reopen the multiset-alias-collision hazard it existed to dodge:
`into(Tables.X)` (the old by-name whole-row map that collided) is not reintroduced — reads stay
strictly field-identity, scoped to PK columns only, which is exactly the safety profile
`Wrap.Row`/`Wrap.Record` already ship with today.

`ParentProjectionContainmentCheck` (`ParentProjectionContainmentCheck.java:62-145`) updates in step:
the `Wrap.TableRecord` arm's guarantee becomes a PK-only `baseColumns` demand like the other wraps,
not a special-cased whole-row guarantee.

### PK-less parent tables: validation, not a silent fallback

A `@table` type with no primary key cannot support `@service`/`@splitQuery` via a `Set<XRecord>` /
`List<XRecord>` Sources parameter at all under a PK-only contract — there is no key to build. Today
this case (`primaryKeyColumns()` empty) falls through `ServiceCatalog.classifySourcesType` into a
generic arg-name-mismatch diagnostic (`ServiceCatalog.java:959-997`, `:269`, `:292`) that does not name
the real cause. This item adds a build-time rejection: when a `Set<XRecord>`/`List<XRecord>` Sources
shape is recognized on a parent whose table has empty `primaryKeyColumns()`, fire a dedicated
`Rejection`/`ServiceMethodCallError` variant naming the PK-less table as the cause, sited at the same
classifier decision point (`classifySourcesType` or its caller) so the classification fact and the
rejection are single-sourced, not re-derived.

### Node-key columns: union with PK in the required-projection walk

`nodeKeyColumns()` usually equals `primaryKeyColumns()` but can diverge: the column order can differ,
and — rarely — `nodeKeyColumns()` can be a subset of the PK, or a unique key (or subset of one) instead
of the PK. Wherever the required-projection walk force-includes a table's key columns as "must be
present regardless of client selection," it should force-include the *union* of `primaryKeyColumns()`
and `nodeKeyColumns()` for a `@node` table type, not PK alone — so that whichever consumer (a
`@service`/`Wrap.TableRecord` child's DataLoader key, or Node ID encoding) needs which columns, both
stay covered by the same force-inclusion computation rather than requiring two independently-verified
mechanisms that can silently drift apart.

Implementation note for whoever picks this up: `ChildField.SingleRecordIdField`
(`ChildField.java:255-277`) is the one existing `SourceKey.Wrap.TableRecord` consumer keyed on
`nodeKeyColumns()` rather than PK, but per its own javadoc it "declines `BatchKeyField` (no
DataLoader)" and is sourced from a `@service`/DML producer's own returned record (`SourceShape.Record`),
not from the type's own `$fields`-projected parent row — so it does not appear to route through
`collectRequiredProjection` at all today. Confirm at implementation time whether any *other* site
computes "required projection" for a `@node` type's own `id` field reading off its own `$fields` row
(as opposed to the mutation-payload/service-returned-record shape `SingleRecordIdField` models); if no
such site exists because node-key columns already end up selected for unrelated reasons in every
existing schema (a masked gap, not a proven non-issue), add the union at `collectRequiredProjection`
regardless so the general case is covered going forward, and note in the PR whether this closes a
latent gap or is confirmed a no-op.

## Scope

1. Fold `Wrap.TableRecord`'s key requirement into the existing `baseColumns` axis of
   `TypeClassGenerator.RequiredProjection` — delete the `reservedFullRow` axis and its unconditional
   whole-row append entirely; `RequiredProjection` becomes a single `baseColumns` list.
2. Force-include the union of `primaryKeyColumns()` and `nodeKeyColumns()` (for `@node` table types) in
   the required-projection walk, per the Design section above.
3. Remove the `__src_<col>__` reserved-alias scheme: `reservedSourceAlias`, the projection append it
   drives, and the allowlist entry in `GeneratedSourcesLintTest.java:219-236`.
4. Remove the runtime `instanceof` parent-shape fork in `GeneratorUtils.buildKeyExtraction`'s
   `TableRecord` arm; replace with the single direct field-identity PK read described above.
5. Update `ParentProjectionContainmentCheck` accordingly.
6. Add a build-time rejection for a `Set<XRecord>`/`List<XRecord>` Sources shape on a PK-less parent
   table (see Design section), sited in `ServiceCatalog`.
7. Rewrite `FilmService.titleTitlecase` using an idiomatic jOOQ batch-fetch (e.g.
   `dsl.selectFrom(FILM).where(FILM.FILM_ID.in(ids)).fetchMap(FILM.FILM_ID)`) rather than a manual
   loop — this is the manual's canonical teaching example, so it should demonstrate proper jOOQ usage,
   not just "any working code." Bring `CityService.cityUppercase` to the same idiom for consistency
   between the two examples the docs present side by side.
8. Correct `docs/manual/how-to/handle-services.adoc`: replace every "fully-populated parent record" /
   "every column on the parent table" passage with the corrected PK-only contract, using the (rewritten)
   `titleTitlecase`/`cityUppercase` pair as consistent canonical examples of the one supported pattern.
   Documentation correction is in scope for this item, not a follow-up.

## Test changes

- **Delete** `ServiceParentTableRecordKeyExtractionTest` outright. Its purpose was pinning the
  discriminator basis for R511's now-deleted `instanceof` fork; once that fork is gone there is nothing
  left for it to pin.
- **Delete** `FederationEntitiesDispatchTest.entities_tableRecordServiceChildOnly_nonKeyColumnReadResolvesNonNull`
  — it asserts the reverted behavior by name. **Add** a proper `_entities` test in its place covering
  the corrected contract: a `Wrap.TableRecord` `@service` child resolving correctly under a
  representations-driven `_entities` fetch when the service does its own batched fetch for non-PK data
  (the real federation scenario R425 was protecting).
- Update `GraphQLQueryTest`'s execution-tier tests pinning service-returned-typed-parent and
  colliding-multiset-sibling resolution for `titleTitlecase` to match the rewritten service body.
- Update `ServiceProjectionPipelineTest`'s R426/R511 shape-assertion groups and `TypeSpecAssertions`'s
  `appendsFullParentRow` / `serviceChildKeyExtractionForksOnTypedRecord` helpers to assert the new
  PK-only shape instead (or delete if the assertion no longer has a distinct shape to check once
  `Wrap.TableRecord` folds into the common `baseColumns` path).
- Add pipeline-tier coverage for the new PK-less-table rejection (Scope item 6).
- Add coverage for the node-key/PK union (Scope item 2), once its implementation site is confirmed.

## Retired vocabulary

Expected to retire at the Done gate: `reservedFullRow`, `reservedSourceAlias`, the `__src_<col>__`
alias scheme (including its `GeneratedSourcesLintTest` allowlist entry), the "fully-populated parent
record" / "every column on the parent table" framing in docs, and the `TableRecord`-arm `instanceof`
runtime fork.

## Migration / compatibility

None. This is an internal contract change with no deprecation window — accepted and owned by the user
requesting this item, independent of any downstream consumers who may currently rely on the reverted
full-row behavior.

## Open risks

- **Enforcement asymmetry.** The corrected invariant — "the PK is present under its base name on both
  arrival shapes" — is enforced by `ParentProjectionContainmentCheck` on the SQL-projected side, but on
  the service-returned-typed-record side it rests on the (true-by-jOOQ-codegen, but
  Graphitron-unenforced) convention that a generated record always carries its own PK. Acceptable, but
  should be stated explicitly in the implementation PR rather than left implicit.
- **Node-key union implementation site is unconfirmed** (see Design section) — resolve during
  implementation, not deferred silently.

## Note

This item corrects R426's premise and unwinds R436's and R511's downstream complexity. R425 remains
valid; this item is what actually completes R425's fix correctly, re-scoped to PK-only rather than
full-row.
