---
id: R83
title: "Multi-schema fixture: pipeline + compilation tier coverage"
status: Backlog
bucket: cleanup
priority: 4
theme: testing
depends-on: []
---

# Multi-schema fixture: pipeline + compilation tier coverage

R78 shipped a generated multi-schema jOOQ fixture (`multischema_a` + `multischema_b` with a cross-schema FK `gadget → widget` and a shared `event` table) and proved the catalog-side typed accessors against it via `JooqCatalogMultiSchemaTest`. That test runs at the unit tier. The whole motivation for R78 was a *generated-code* bug ("imports emitted as `<jooqPackage>.tables.X`, dropping the schema segment"), but no test in this repo drives an SDL through `GraphitronSchemaBuilder` against the multi-schema fixture, no emitter is exercised over a cross-schema `TableRef` / `ForeignKeyRef`, and `graphitron-sakila-example` (the compilation tier) has no `multischema_*` consumer. The R78 changelog acknowledges this as deferred ("execution-tier coverage for cross-schema FK joins at runtime (Backlog stub)") but no item was filed.

Per *Pipeline tests are the primary behavioural tier* and *Compilation against real jOOQ is a test tier*, the typed `tableClass` / `recordClass` / `keysClass` wiring R78 introduced has no end-to-end signal today: a regression that re-derives a `ClassName` from a stale `jooqPackage` in some emitter would pass every test in the repo and fall over only when a multi-schema consumer points at the rewrite. The fixture R78 paid for is sitting unused at the tiers where the original bug actually lived.

## What this item covers

1. **Pipeline tier.** A new `MultiSchemaPipelineTest` (or a clearly-scoped block inside an existing pipeline test class) that loads an SDL referencing multi-schema-fixture tables, runs `GraphitronSchemaBuilder` and the validator, and emits `TypeFetcher` / `TypeClass` / `Conditions` `TypeSpec`s. Assertions live at the pipeline-tier shape: classified `TableRef.tableClass()` is schema-segmented (`multischema_a.tables.Widget`, not the root); `ForeignKeyRef.keysClass()` on the cross-schema FK points at the FK-holder schema's `Keys` (`multischema_b.Keys`); the rendered `TypeSpec` imports the schema-segmented FQNs (no body-content scanning — the imports list is the structural fingerprint).
2. **Compilation tier.** A small slice in `graphitron-sakila-example` (or a dedicated `graphitron-multischema-example` if mixing schemas with sakila is awkward) whose `pom.xml` adds the `multischema-fixture` codegen jar and whose `.graphqls` references at least one cross-schema FK traversal. `mvn compile -Plocal-db` against this slice catches the "Field<Record4<...>> doesn't line up with the emitted DSL call" class of breakage that R78 was fundamentally about. The unique-table (`widget`/`gadget`), shared-table-must-qualify (`multischema_a.event`), and cross-schema-FK-join cases all want at least one schema field.
3. **Execution tier (deferred sub-question).** The R78 changelog mentions runtime coverage. Postgres can host multiple schemas in one DB, so the fixture's DDL already supports it; the question is whether the existing `Plocal-db` execution harness wants a multi-schema schema-search-path setup or a separate database. Resolve in this item if the cost is small; defer to a sibling item if it widens scope.

## Why this isn't part of R81

R81 (`catalog-resolution-sealed-outcomes`) is about *modelling* — lifting catalog results onto sealed types so callers can't be wrong. The fixture-promotion work here is *verification* — making sure the wiring R78 already lifted into typed `ClassName`s actually round-trips end-to-end. Different axis, different emitter-touchpoints, different test files. R81 explicitly scopes the fixture itself out ("this item rides the fixture R78 lands"); the corollary is that fixture-side test-tier work needs its own home.

## Out of scope

- Modelling changes from R81 (`TableResolution` sealed type, non-null `FkJoin.fk`, six-site rejection collapse). This item rides whatever shape lands there.
- New multi-schema fixture content (additional tables, additional cross-schema FKs). Promote what R78 shipped before adding more.
- LSP directive-validation pass for the `schema.table` syntax (R78 changelog called this out as a separate one-line check, not a hard requirement).

Principles cited: *Pipeline tests are the primary behavioural tier*; *Compilation against real jOOQ is a test tier*.
