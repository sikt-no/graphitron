# Plan: per-type `*Wiring` classes

> **Status:** Spec

Move the per-type `TypeRuntimeWiring` construction out of the aggregate `GraphitronWiring.build()` body (and out of `*Fetchers.wiring()`) into dedicated per-type `*Wiring` classes in a new `rewrite.wiring` subpackage. After the refactor `GraphitronWiring.build()` collapses to a pure aggregator (`.type(FooWiring.wiring())` per type), including for synthesized Connection / Edge / nested types whose wiring is currently inlined in the builder.

Roadmap reference: `docs/planning/rewrite-roadmap.md`, "Per-type `*Wiring` classes" in the Active table.

## Problem

Wiring is generated in two asymmetric shapes today:

1. **Regular object types** follow a per-type pattern: each `*Fetchers` class carries a `public static wiring()` method, and `GraphitronWiring.build()` calls `.type(FooFetchers.wiring())` (`GraphitronWiringClassGenerator.java:80-83`). A reader looking for "where does `Film`'s wiring live?" finds `FilmFetchers.wiring()` via file name.

2. **Synthesized types** (Connection, Edge, nested plain-object types reached through `NestingField`) have their wiring inlined in `GraphitronWiringClassGenerator.generate()` itself (`GraphitronWiringClassGenerator.java:90-117`). A reader looking for "where does `QueryFilmsConnection`'s wiring live?" has to know to open the generator of the aggregate class and scan its emitted `build()` body.

The asymmetry has two concrete costs:

- **Discovery.** File-name search (`*Connection*`, `FilmInfo*`, `*Wiring*`) reaches nothing for synthesized types. The reader has to read the aggregator generator to find the wiring for those types.
- **Class-level cohesion.** `GraphitronWiringClassGenerator` is doing two jobs: aggregating existing per-type wiring (trivial), and constructing per-type wiring inline for synthesized types (non-trivial; shares a code path with `TypeFetcherGenerator.buildWiringEntry`). Splitting the two jobs makes both simpler.

Every nested type without a fetcher class also has no obvious discovery anchor at all: `FilmInfo` doesn't appear in `*Fetchers.java` (because the class isn't emitted for nested types with only column leaves, per `TypeFetcherGenerator.collectNestedFetcherClasses:92-110`), doesn't appear in `GraphitronWiring.java` as anything other than a string literal, and has no `*Wiring.java` to find.

## Proposed design

One `<TypeName>Wiring` class per GraphQL object type, emitted to `<outputPackage>.rewrite.wiring`, each with a `public static TypeRuntimeWiring.Builder wiring()` method. Covers five categories:

1. **Regular object types** (Query, Film, Actor, …): `FilmWiring.wiring()` references the existing fetcher methods as `FilmFetchers::methodName`. `FilmFetchers` loses its `wiring()` method and becomes a pure fetcher-method library.
2. **Nested object types with `BatchKeyField` leaves** (narrow `*Fetchers` class already emitted per `TypeFetcherGenerator:92-110`): `FilmMetaWiring.wiring()` mixes `FilmMetaFetchers::rowsSomething` references with inline `ColumnFetcher` bindings, same as today's inlined form.
3. **Nested object types without `BatchKeyField` leaves** (no fetcher class emitted today): `FilmInfoWiring.wiring()` becomes the one file where `FilmInfo`'s wiring lives.
4. **Connection types**: `QueryFilmsConnectionWiring.wiring()` binds `edges` / `nodes` / `pageInfo` to `ConnectionHelper::edges` / `::nodes` / `::pageInfo`.
5. **Edge types**: `QueryFilmsEdgeWiring.wiring()` binds `node` / `cursor` to `ConnectionHelper::edgeNode` / `::edgeCursor`.

`GraphitronWiring.build()` after the refactor:

```java
public static RuntimeWiring.Builder build() {
    return RuntimeWiring.newRuntimeWiring()
        .type(ActorWiring.wiring())
        .type(FilmWiring.wiring())
        .type(FilmsConnectionWiring.wiring())
        .type(FilmsEdgeWiring.wiring())
        .type(FilmInfoWiring.wiring())
        ...;
}
```

No special-case loops, no `NestedTypeWiring` / `ConnectionWiring` records threaded through the top-level generator. The aggregator gets a flat sorted list of class names and emits one `.type()` line per name.

### Package choice

New `<outputPackage>.rewrite.wiring` subpackage, parallel to the existing `rewrite.fetchers`, `rewrite.conditions`, `rewrite.types`. Keeps the wiring artefacts grouped, visible as a single directory listing, and discoverable by IDE file-name search. Alternative considered: keep `*Wiring` classes alongside `*Fetchers` in `rewrite.fetchers`; rejected because the types aren't fetchers (they reference fetchers, but the artefact is graphql-java configuration, not a data fetcher implementation).

### Naming

`<GraphQLTypeName>Wiring`, matching `<GraphQLTypeName>Fetchers`. For Connection and Edge types the GraphQL type name already includes the `Connection` / `Edge` suffix (`QueryFilmsConnection`, `QueryFilmsEdge`), so the class names are `QueryFilmsConnectionWiring` and `QueryFilmsEdgeWiring`. Verbose but unambiguous; consistent with the existing `<TypeName>Fetchers` rule. When an author overrides the Connection type name via the `connectionName` directive (`FieldBuilder.java:395-396`), the `*Wiring` class name tracks the actual synthesized type name, not a derived one.

Interface and union object types are out of scope for this refactor: they are currently in the generator-stubs category (`rewrite-roadmap.md` stubs #3) and have no emitted wiring today. When they land, they will follow the same `<TypeName>Wiring` convention with graphql-java's `TypeResolver` registration instead of `DataFetcher` bindings; no change to the aggregator shape.

## Touch points

### New generator

- `WiringClassGenerator` (singular, in `no.sikt.graphitron.rewrite.generators`), emits one `TypeSpec` per GraphQL object type. Takes:
  - The `GraphitronSchema` (for regular and nested-via-NestingField types, and for iterating declared fields in SDL order).
  - Connection-type and Edge-type collections already derived in `GraphQLRewriteGenerator` (move the collection logic from `GraphQLRewriteGenerator:78-102` into this generator or keep it as caller-side input).
  - `TypeFetcherGenerator.buildWiringEntry` moves here and becomes the per-field entry point (it's already `static` and takes a `ChildField` plus optional `parentTable` / `resultType`); `TypeFetcherGenerator.buildWiringMethod` folds into this generator as its core `wiring()` emitter for the regular-type path.
- Single public entry point. **Open question 4** below covers the signature: the touch points here name `WiringClassGenerator.generate(GraphitronSchema, List<ConnectionWiring>, List<NestedTypeWiring>) → List<TypeSpec>`, but the `GraphQLRewriteGenerator` simplification snippet uses `WiringClassGenerator.generate(schema)` — a schema-only form that derives connection and nested types internally. The two are mutually exclusive; both code blocks must reflect the same choice before implementation. If the collection logic moves inside this generator, `ConnectionWiring` and `NestedTypeWiring` become private implementation records and are removed from the public signature. If they stay as caller-side inputs, the simplified caller snippet is wrong and both records stay public. Returns one `TypeSpec` per type across all five categories.

### `GraphitronWiringClassGenerator` shrinks

The aggregator collapses to:

```java
public static TypeSpec generate(List<String> wiringClassNames) {
    var body = CodeBlock.builder()
        .add("return $T.newRuntimeWiring()", RUNTIME_WIRING);
    body.indent();
    for (var name : wiringClassNames) {
        body.add("\n.type($T.wiring())", ClassName.get(wiringPackage, name));
    }
    body.add(";\n").unindent();
    // build() method, class spec
}
```

`ConnectionWiring` and `NestedTypeWiring` records move to `WiringClassGenerator` (they're implementation detail of per-type wiring emission, not of the aggregator). This applies if the schema-only entry point is chosen (open question 4); if the lists stay as caller-side inputs the records remain on the aggregator or become a shared data package.

The aggregator receives a flat list of `*Wiring` class names. **Open question 5** covers the ordering. See below.

### `TypeFetcherGenerator` trims

- `buildWiringMethod` (`:1406-1427`) deleted; `*Fetchers` classes no longer carry `wiring()`.
- `buildWiringEntry` (`:1333-1404`) moved to `WiringClassGenerator`. Still `static`; `TypeFetcherGenerator` becomes a pure fetcher-method emitter. The `className` parameter (used by the default fallback at `:1403` to emit `className::name`) must be the corresponding `*Fetchers` class name, not the `*Wiring` class name. For regular types and nested types with `BatchKeyField` leaves (categories 1 and 2), derive it as `<GraphQLTypeName> + "Fetchers"` — verified as correct given that `TypeFetcherGenerator` generates those classes as `PUBLIC` with `PUBLIC STATIC` method targets (`:285-294`, `:523`, `:590`, `:934`, `:970`, `:1013`, `:1058`, `:1091`, `:1161`, `:1238`). For categories 3, 4, and 5 (no `*Fetchers` class), the default fallback is unreachable, so `className` can be `null` or omitted for those paths.
- `buildPropertyOrRecordFetcherEntry` (`:1294-1325`) moves with `buildWiringEntry` (its sole caller).
- `emitWiring` parameter on `generateTypeSpec` (`:285-291`) goes away; fetcher classes never carry wiring anymore, so the gating variable is dead.
- The `fetcherClassNames.filter(hasWiring)` trick at `GraphQLRewriteGenerator:72-75` goes away with `emitWiring`.

### `GraphQLRewriteGenerator` simplifies

`generate()` loses the connection-type collection loop (`:78-93`), the `collectNestedTypes` helper (`:117-135`), the `fetcherClassNames` filter (`:72-75`), and passes the schema straight through to `WiringClassGenerator`. Reads as:

```java
var fetcherClasses = TypeFetcherGenerator.generate(schema);
var wiringClasses = WiringClassGenerator.generate(schema);
var aggregator = GraphitronWiringClassGenerator.generate(
    wiringClasses.stream().map(TypeSpec::name).toList());

write(fetcherClasses, "rewrite.fetchers");
write(wiringClasses,  "rewrite.wiring");
write(List.of(aggregator), "rewrite");
```

This snippet uses the schema-only form of `WiringClassGenerator.generate()`, which implies the collection logic moves inside `WiringClassGenerator`. See open question 4.

### Emitted-source surface

Net file count change: each type that already had a `*Fetchers` class gains a sibling `*Wiring` file. Connection, Edge, and nested-without-fetcher types each gain a `*Wiring` file where they had nothing. For the test fixtures today (Sakila): roughly 10 new files for regular types, 4-6 for nested / connection / edge types in the current test-spec SDL. Each `*Wiring` file is 5-25 lines; no measurable build-time cost.

## Test coverage

Per the rewrite test-tier conventions in `docs/rewrite-design-principles.md`, code-string assertions on generated bodies are discouraged; the refactor lands on existing coverage tiers:

- **Smoke test.** Extend `GeneratedSourcesSmokeTest.PKG_QUALIFIED_CLASSES` (`graphitron-rewrite-test-spec`) to include one `*Wiring` class from each of the five categories, confirming every expected `*Wiring` class is emitted and compiles.
- **Pipeline tests.** No new tests needed; existing `GraphitronSchemaBuilderTest` / `*PipelineTest` cases remain unchanged. The refactor is behaviour-preserving at the schema-builder / classifier / emitter seam.
- **Execution tests.** `GraphQLQueryTest` already uses `GraphitronWiring.build()` (`GraphQLQueryTest.java:75`); the existing end-to-end tests exercise every category of wiring at runtime. The refactor passes iff those tests stay green.
- **Lint ratchet.** A `GeneratedSourcesLintTest` assertion that `GraphitronWiring.java` contains no `newTypeWiring(` calls (only `.wiring()` references) locks in the aggregator-only contract. Cheap and directly enforces the invariant this refactor establishes; see open question 3 for whether to add it now or defer.

## Alternatives considered

- **Narrow scope: only extract the currently-inlined Connection / Edge / nested cases, leave `*Fetchers.wiring()` alone.** Addresses the stated discovery complaint, keeps `*Fetchers` cohesive. Rejected because the remaining asymmetry (ordinary types in `*Fetchers.wiring()`, synthesized in `*Wiring.wiring()`) is confusing on its own: a reader who learns the `*Wiring` pattern from seeing `QueryFilmsConnectionWiring` then has to learn that `FilmWiring` doesn't exist and they need `FilmFetchers.wiring()` instead. Full split pays a one-time cost for uniform discovery.
- **Keep wiring on `*Fetchers`, give nested-type fetcher classes a `wiring()` method, emit empty `*Fetchers` shells for the no-fetcher cases.** Minimal change to the generated layout (no new subpackage), symmetrizes the aggregator loop. Rejected because it keeps a class named `FilmInfoFetchers` for a type that has no fetcher methods, which is misleading; the file-name-search discovery remains confusing (reader looking for wiring opens a class called `Fetchers`).
- **Move wiring into the `*Type` record classes in `rewrite.types`.** Colocates wiring with the type's generated record; one file per type, same as the record. Rejected because not all wired types have a `*Type` record (e.g. Connection, Edge), and the record classes are deliberately pure data carriers today (introducing graphql-java dependencies into them would blur that separation).

## Risk

Low. The refactor is mechanical: every code path already exists (the fetcher-method references, the `ConnectionHelper` bindings, the `buildWiringEntry` logic for nested-type fields); the change is where they're emitted, not what they emit. The generator test suite (`GeneratedSourcesSmokeTest` + `GraphQLQueryTest`'s execution fixtures) covers every wiring category end-to-end, so a behavioural regression shows up immediately.

Single substantive risk: cross-class method references to `*Fetchers::methodName` need to reach public static methods. Verified at `TypeFetcherGenerator.java:285-294` (the generated `*Fetchers` class is `PUBLIC`) and `:523, :590, :934, :970, :1013, :1058, :1091, :1161, :1238` (the method-reference targets are `PUBLIC STATIC`). No access-modifier widening required.

Secondary note: the `collectNestedTypes` helper in `GraphQLRewriteGenerator` (`:123-135`) uses first-occurrence-wins semantics when a nested type is shared across multiple parents, capturing the first-seen `representativeParentTable`. The `ColumnField` branch in `buildWiringEntry` (`:1349-1356`) emits a typed `ColumnFetcher<>(Tables.X.COL)` using that table. This is correct as long as shared nested types always resolve against the same underlying jOOQ table — which holds for the current `NestingField` sharing semantics — but implementers should confirm the invariant still holds once the Active item "Multi-parent NestingField sharing — `TableField` arm" lands.

## Open questions

1. **Do we keep the `<outputPackage>.rewrite.wiring` subpackage name, or use something like `rewrite.runtimewiring` to avoid collision with the graphql-java `RuntimeWiring` concept?** The subpackage name is only visible in generated `import` statements; `rewrite.wiring` is shorter and the collision is weak (the package contains *producers of* `RuntimeWiring` fragments, not `RuntimeWiring` itself). Recommendation: `rewrite.wiring`.
2. **Do we extract `ConnectionHelper::edges` / `::edgeNode` into per-connection helper methods so `QueryFilmsConnectionWiring` calls `QueryFilmsConnectionWiring::edges` instead?** No; `ConnectionHelper` methods are type-agnostic by design (they operate on the `ConnectionResult` / `Edge` carriers, not on the specific Connection type). Keeping the cross-class method references is correct.
3. **Add the lint ratchet now, or defer?** The ratchet directly enforces the invariant this refactor establishes, and the one-line assertion is low maintenance. The objection is that a future wiring category that legitimately inlines into the aggregator would be blocked — but the design intent is that no such category should exist. Recommendation pending: resolve before implementation so the test plan is complete.
4. **Schema-only entry point, or caller passes the derived lists?** `WiringClassGenerator.generate(schema)` vs. `WiringClassGenerator.generate(schema, connections, nestedTypes)`. The "New generator" section and the `GraphQLRewriteGenerator` simplification snippet currently describe opposite choices. If the schema-only form is used, `ConnectionWiring` / `NestedTypeWiring` become private records inside `WiringClassGenerator` and the generator walks `schema.fields()` to collect Connection types (the `SqlGeneratingField` + `FieldWrapper.Connection` scan from `GraphQLRewriteGenerator:80-93`) and `schema.fields().values()` recursively via `collectNestedTypes` equivalent (`:117-135`); the simplified caller code is then correct. If the lists are kept as inputs, the simplified caller snippet needs a correction and the records remain public. Pick one.
5. **What ordering should the aggregator use for the flat list of `*Wiring` class names?** The current `fetcherClassNames` list is alphabetical by class name (`.sorted()` at `TypeFetcherGenerator.java:73` and `Map.Entry.comparingByKey()` at `:83`; verified against the generated `GraphitronWiring.java`: `Actor, Address, Category, Customer, Film, FilmActor, FilmDetails, Language, Query, Store`). Options: (a) alphabetical across all five categories, for stable diffs regardless of SDL order (matches the existing convention); (b) alphabetical for schema types, with synthesized types (Connection, Edge, nested-without-fetcher) interleaved alphabetically or grouped at the end; (c) SDL declaration order, with synthesized types inserted immediately after the type that introduces them, so related types stay adjacent. Recommendation: (a). The existing order is alphabetical, so matching it is the lowest-churn choice and stable-diff is the most defensible property for generated code.
