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

## Scope

1. Fold `Wrap.TableRecord`'s key requirement into the existing `baseColumns` axis of
   `TypeClassGenerator.RequiredProjection` (force-include the parent table's PK column(s),
   base-named) — remove the separate `reservedFullRow` axis and its unconditional whole-row append.
2. Remove the `__src_<col>__` reserved-alias scheme (`reservedSourceAlias`, the projection append it
   drives, and any associated docs/lint entries) — reads happen directly against base-named columns.
3. Remove the runtime `instanceof` parent-shape fork in `GeneratorUtils.buildKeyExtraction`'s
   `TableRecord` arm; replace with a single direct field-identity read of the PK column(s),
   constructing a key record with only the PK populated.
4. Update `ParentProjectionContainmentCheck` accordingly — it audits both `RequiredProjection` axes,
   and the `TableRecord` requirement becomes a PK-only `baseColumns` demand like the other wraps, not
   a special-cased whole-row guarantee.
5. Rewrite `FilmService.titleTitlecase` to match `CityService.cityUppercase`'s pattern: take only the
   PK off the parent record, inject `DSLContext`, batch-fetch the columns it needs itself in one
   query.
6. Correct `docs/manual/how-to/handle-services.adoc`: replace every "fully-populated parent record" /
   "every column on the parent table" passage with the corrected PK-only contract, using the (fixed)
   `titleTitlecase`/`cityUppercase` pair as consistent canonical examples of the one supported
   pattern rather than two divergent ones. Documentation correction is in scope for this item, not a
   follow-up.
7. Update or retire tests that currently pin the reverted full-row behavior:
   `ServiceParentTableRecordKeyExtractionTest`, the `GraphQLQueryTest` execution tests pinning
   service-returned-typed-parent and colliding-multiset-sibling resolution for `titleTitlecase`,
   `ServiceProjectionPipelineTest`'s R426/R511 shape-assertion groups, `TypeSpecAssertions`'s
   `appendsFullParentRow` / `serviceChildKeyExtractionForksOnTypedRecord` helpers, and
   `FederationEntitiesDispatchTest`'s non-key-column-read case — rewritten to the corrected contract
   where they still demonstrate real coverage, deleted where they no longer apply.

## Retired vocabulary

(To be moved/confirmed at the Done gate per the retirement-sweep convention.) Expected to retire:
`reservedFullRow`, `reservedSourceAlias`, the `__src_<col>__` alias scheme, the "fully-populated
parent record" / "every column on the parent table" framing in docs, and the `TableRecord`-arm
`instanceof` runtime fork.

## Note

This item corrects R426's premise and unwinds R436's and R511's downstream complexity. R425 remains
valid; this item is what actually completes R425's fix correctly, re-scoped to PK-only rather than
full-row.
