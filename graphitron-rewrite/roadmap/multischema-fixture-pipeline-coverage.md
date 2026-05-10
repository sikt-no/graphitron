---
id: R83
title: "Multi-schema fixture: pipeline + compilation + execution tier coverage"
status: In Review
bucket: cleanup
priority: 4
theme: testing
depends-on: []
---

# Multi-schema fixture: pipeline + compilation + execution tier coverage

R78 shipped a generated multi-schema jOOQ fixture (`multischema_a` + `multischema_b` with a cross-schema FK `gadget → widget` and a shared `event` table) and proved the catalog-side typed accessors against it via `JooqCatalogMultiSchemaTest`. That test runs at the unit tier. The whole motivation for R78 was a *generated-code* bug ("imports emitted as `<jooqPackage>.tables.X`, dropping the schema segment"), but no test in this repo drives an SDL through `GraphitronSchemaBuilder` against the multi-schema fixture, no emitter is exercised over a cross-schema `TableRef` / `ForeignKeyRef`, and `graphitron-sakila-example` (the compilation tier) has no `multischema_*` consumer. The R78 changelog acknowledges this as deferred ("execution-tier coverage for cross-schema FK joins at runtime (Backlog stub)") but no item was filed.

Per *Pipeline tests are the primary behavioural tier* and *Compilation against real jOOQ is a test tier*, the typed `tableClass` / `recordClass` / `keysClass` wiring R78 introduced has no end-to-end signal today: a regression that re-derives a `ClassName` from a stale `jooqPackage` in some emitter would pass every test in the repo and fall over only when a multi-schema consumer points at the rewrite. The fixture R78 paid for is sitting unused at the tiers where the original bug actually lived.

## What this item covers

The three tiers are independent slices riding existing infrastructure; they do not gate each other.

### Pipeline tier

A new `MultiSchemaPipelineTest` (or a clearly-scoped block inside an existing pipeline test class) loads an SDL referencing multi-schema-fixture tables, runs `GraphitronSchemaBuilder` and the validator, and emits `TypeFetcher` / `TypeClass` / `Conditions` `TypeSpec`s. Assertions live at the pipeline-tier shape: classified `TableRef.tableClass()` is schema-segmented (`multischema_a.tables.Widget`, not the root); `ForeignKeyRef.keysClass()` on the cross-schema FK points at the FK-holder schema's `Keys` (`multischema_b.Keys`); the rendered `TypeSpec` imports the schema-segmented FQNs (no body-content scanning — the imports list is the structural fingerprint). The test annotates `@PipelineTier`. The fixture jOOQ classes are already on the test classpath via `graphitron-sakila-db`.

### Compilation tier

`graphitron-sakila-example`'s `pom.xml` already runs `graphitron-maven-plugin` with two executions (`rewrite-generate` for `schema.graphqls`, `rewrite-generate-federated` for `federated-schema.graphqls`), each with its own `outputPackage`. A third execution `rewrite-generate-multischema` is added with `jooqPackage=no.sikt.graphitron.rewrite.multischemafixture` and `outputPackage=no.sikt.graphitron.generated.multischema`, pointed at a new `src/main/resources/graphql/multischema.graphqls`. The `multischema-fixture` jOOQ codegen output already ships inside the `graphitron-sakila-db` jar (the existing dep), so no new module dependency is needed.

The new SDL exercises three shape cases the existing fixtures cannot:

- The unique-per-schema case: a type backed by `multischema_a.widget` (no schema qualifier needed; resolves to `multischema_a` because `widget` is unique across schemas).
- The shared-table case requiring qualification: a type backed by `multischema_a.event` (the `event` name collides; resolution must use the qualified `@table(name: "multischema_a.event")` form to disambiguate).
- The cross-schema FK traversal: a `Gadget` type with a `widget: Widget` field whose FK route lives in `multischema_b.Keys`. This is the case the R78 bug would silently miswire under a regression that re-derived `ClassName` from the root package.

The `mvn install -Plocal-db` invocation already builds `graphitron-sakila-example` in the standard pipeline; with the new execution wired, it catches "Field<RecordN<...>> doesn't line up with the emitted DSL call" class breakage that R78 was fundamentally about.

### Execution tier

`init.sql` already creates `multischema_a` + `multischema_b` in the `rewrite_test` database the execution tier hits, so no DDL or harness change is needed. A new `@QuarkusTest` (or a small slice in the existing `GraphQLQueryTest` shape) issues a query through the multischema slice's generated GraphQL endpoint that traverses the cross-schema FK (`{ gadgets { id widget { id } } }` or similar), asserting the row round-trips end-to-end. The data-side seed lives in the existing `init.sql` next to the schema DDL (or in a dedicated `INSERT` block scoped to the multischema schemas).

## Out of scope

- New multi-schema fixture content (additional tables, additional cross-schema FKs). Promote what R78 shipped before adding more.
- LSP directive-validation pass for the `schema.table` syntax (R78 changelog called this out as a separate one-line check, not a hard requirement).
- A dedicated `graphitron-multischema-example` module. The third graphitron-maven-plugin execution in `graphitron-sakila-example` writes to a disjoint `outputPackage`, so the existing module hosts the slice without contamination of the sakila-side codegen.

Principles cited: *Pipeline tests are the primary behavioural tier*; *Compilation against real jOOQ is a test tier*.

## Shipped

All three tiers landed:

- Pipeline tier: `graphitron/src/test/java/no/sikt/graphitron/rewrite/MultiSchemaPipelineTest.java`. Asserts at two typed surfaces — model (`TableRef.tableClass()` for the unique-per-schema, qualified, and cross-schema-FK-target cases; `JoinStep.FkJoin.fk().keysClass()` routes to `multischema_b.Keys`; `firstHop.targetTable().tableClass()` lands on `multischema_a.tables.Widget`) and structural emit (a typed walk over `TypeSpec.methodSpecs[].parameters[].type` / `returnType` / `fieldSpecs[].type` plus parsed imports). The negative-form sweep asserts every `ClassName` whose canonical name starts with the multischema-fixture root lives under a `multischema_a` / `multischema_b` sub-package — i.e. the bare-root R78 bug shape never appears anywhere reachable from a `TypeSpec`'s typed graph.
- Compilation tier: third `rewrite-generate-multischema` execution in `graphitron-sakila-example/pom.xml` consuming `src/main/resources/graphql/multischema.graphqls`, writing to `no.sikt.graphitron.generated.multischema`. The full `mvn install -Plocal-db` reactor compiles the multischema slice against the live multi-schema jOOQ catalog.
- Execution tier: `graphitron-sakila-example/src/test/java/.../MultiSchemaQueryTest.java` issues three queries (`gadgets { widget { ... } }` round-trip across the FK; `widgets`; `events`) against `rewrite_test`, loading the multischema slice's own `Graphitron.buildSchema(...)`. Seed rows added to `init.sql`.

Departures from the spec are minor: the pipeline test walks the typed `TypeSpec` graph + parsed imports rather than only the imports list, because JavaPoet inlines an FQN whenever its simple name collides with the enclosing TypeSpec's name (the `Widget` typeclass inlines `multischema_a.tables.Widget` rather than importing it). The typed walk is the principle-aligned form of "structural fingerprint" the spec sketched and stays well clear of code-string body scans.

A self-review pass with the principles-architect surfaced one in-scope refactor (the typed walk above, replacing an earlier rendered-text substring scan) and one out-of-scope follow-up: the R78 invariants (`TableRef.tableClass()` and `ForeignKeyRef.keysClass()`) fit the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pattern but are not yet annotated. R125 captures the annotation-pair sweep across the ten consumer emitter sites; deferring keeps R83's scope focused on "make the multi-schema fixture earn its keep at the tiers where the bug lives" rather than expanding into a broader principle-enforcement sweep.
