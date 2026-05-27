---
id: R250
title: Propagate schema-applied directives (@link) from consumer SDL to generated buildSchema
status: Spec
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Propagate schema-applied directives (@link) from consumer SDL to generated buildSchema

A consumer with `extend schema @link(url: "https://specs.apollo.dev/federation/v2.4", import: ["@tag", "@shareable", "@key", "@override", "@inaccessible", "@external"])` in their SDL hits this when feeding their subgraph schema to Apollo supergraph composition (`@apollo/federation-internals` 2.14.0): composition fails as Fed1 with `Invalid definition for directive "@key": argument "fields" should have type "_FieldSet!" but found type "federation__FieldSet!"` and an analogous `@tag` location mismatch. The composer's `completeSubgraphSchema` (federation.ts:1409) only takes the Fed2 path when the schema definition carries a federation `@link` applied directive; absent that, it falls through `completeFed1SubgraphSchema`, where R248's canonically Fed2-shaped `@key`/`@tag` declarations look invalid.

Reproduced by stripping the `schema @link(...) {...}` block from sakila's generated `schema.graphqls` and re-running compose: same error, bit for bit. Sakila itself composes fine because its `schema.graphqls` does carry `schema @link(...)` on the first line — R247's `SchemaSdlEmitter` round-trips `assembled.getSchemaAppliedDirectives()` correctly through `ServiceSDLPrinter.generateServiceSDLV2`.

The bug is in the runtime build path, not the file emitter. `GraphitronSchemaClassGenerator` (line ~213, the survivors/`additionalDirective` loop) constructs the runtime `schemaBuilder` via `GraphQLSchema.newSchema()` (the no-arg form), which starts with an empty schema-applied-directive list. `.additionalDirective(...)` only emits directive *definitions*, not applications. The consumer's `@link` sits on `assembled.getSchemaAppliedDirectives()` at codegen time but is never read back. At runtime, `Federation.transform(base).setFederation2(true).build()` is supposed to attach a fresh Fed2 `@link`, but in at least one consumer's deployment the runtime SDL (whether served via `_service.sdl` or written to disk) reaches supergraph-compose without it.

**Symptom file (opptak):**
```
schema {
  query: Query
  mutation: Mutation
}

directive @external on OBJECT | FIELD_DEFINITION
...
directive @key(fields: federation__FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
directive @link(as: String, for: link__Purpose, import: [link__Import], url: String!) repeatable on SCHEMA
...
```

Directive *declarations* are emitted (including `directive @link`), but no `@link(...)` is *applied* on the schema block.

## Plan

### Step 1 — propagate schema-applied directives in the runtime build

In `GraphitronSchemaClassGenerator.generate`, alongside the existing `DirectiveDefinitionEmitter.survivors(assembled)` loop at line 213, add a sibling emission that translates `assembled.getSchemaAppliedDirectives()` into `.withSchemaAppliedDirectives(...)` on the runtime `schemaBuilder`.

The existing `AppliedDirectiveEmitter` already does most of the work for per-type containers (`ObjectTypeGenerator`, `InputTypeGenerator`, `EnumTypeGenerator`); its `buildApplication(GraphQLAppliedDirective)` / `emitAstLiteralValue(...)` / `emitInputType(...)` helpers are name-level filtered by `SchemaDirectiveRegistry.isSurvivor(...)`. We extract a sibling entry point that takes a raw `List<GraphQLAppliedDirective>` (the schema-level list is not a `GraphQLDirectiveContainer` in graphql-java), reusing the private helpers verbatim. Sketch:

```java
// in AppliedDirectiveEmitter
public static List<CodeBlock> applicationsForSchema(GraphQLSchema schema) {
    var blocks = new ArrayList<CodeBlock>();
    for (var applied : schema.getSchemaAppliedDirectives()) {
        if (!SchemaDirectiveRegistry.isSurvivor(applied.getName())) continue;
        blocks.add(buildApplication(applied));
    }
    return blocks;
}
```

`buildApplication` is already private and produces a single `GraphQLAppliedDirective.newDirective()...build()` block; we just need it reachable from the new entry point.

Call site in `GraphitronSchemaClassGenerator`, immediately after the `survivors` loop (before `.codeRegistry(...)`):

```java
var schemaApplied = AppliedDirectiveEmitter.applicationsForSchema(assembled);
if (!schemaApplied.isEmpty()) {
    body.add("\n.withSchemaAppliedDirectives(java.util.List.of(");
    for (int i = 0; i < schemaApplied.size(); i++) {
        if (i > 0) body.add(", ");
        body.add(schemaApplied.get(i));
    }
    body.add("))");
}
```

`GraphQLSchema.Builder.withSchemaAppliedDirectives(List<GraphQLAppliedDirective>)` is the existing graphql-java API; no shim needed. Argument-value rendering routes through the same `ValuesResolver.valueToLiteral` + `AstPrinter.printAst` + `Parser.parseValue` chain that the per-type emitter already uses, so `@link`'s `import: ["@key", "@tag", ...]` argument round-trips through an AST list literal without any per-shape coding.

The `link__Import` scalar and `link__Purpose` enum referenced by the `@link` argument types are already registered on the runtime schema by the existing `.additionalType(...)` loop (R248's `ScalarTypeResolver` Synthesised arm for federation-namespace scalars; standard enum registration). `emitInputType`'s `GraphQLTypeReference.typeRef("link__Import")` resolves against those registrations at schema-build time. No new registration work needed.

### Step 2 — sanity-check R247's `schema.graphqls` emission for opptak-shaped input

Sakila's existing federated fixture is a single file (`federated-schema.graphqls`). Opptak's setup is multi-file: one file carries `extend schema @link(...)`, others carry types only, no explicit `schema { ... }` block anywhere. A standalone graphql-java repro with that exact shape preserved `@link` end-to-end through `ServiceSDLPrinter.generateServiceSDLV2`, so this is plausibly already fine; but it's not currently asserted in graphitron's tests, and the parity gap with the runtime-build side is what produced the bug. Two options:

1. Split the existing `federated-schema.graphqls` fixture into two files (one with `extend schema @link(...)`, one with the types) and rerun the existing assertions.
2. Add a second federated fixture (`federated-multi-file/`) with the split shape, then mirror the existing `FederationBuildSmokeTest` assertions onto it.

Option 2 is preferred — it keeps the existing single-file fixture as a separate axis and isolates the multi-file regression check. Pipeline-tier; lives next to `FederationBuildSmokeTest`.

### Step 3 — regression coverage

Two new assertions:

- **Unit tier** (`GraphitronSchemaClassGeneratorTest` or sibling): for an `assembled` schema carrying a schema-applied `@link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])`, assert the generated Java source contains `.withSchemaAppliedDirectives(java.util.List.of(GraphQLAppliedDirective.newDirective().name("link")...))` with the expected `import` list literal.
- **Pipeline tier** (`FederationBuildSmokeTest`): assert that `Graphitron.buildSchema(b -> {}, fed -> {})`'s result, queried for `_service { sdl }`, returns SDL whose `schema {...}` block carries `@link(url : "https://specs.apollo.dev/federation/v2.10", import : ["@key"])` (graphql-java printer style, space-around-colon). Locks the round-trip from consumer SDL through codegen through `Federation.transform` through `_service.sdl` against silent regression.

Optional but cheap: assert `Files.readString(...)` of `target/generated-resources/.../schema.graphqls` contains `schema @link(` for the federated sakila build. Closes the loop on R247's file artifact.

### Files touched

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/AppliedDirectiveEmitter.java` — add `applicationsForSchema(GraphQLSchema)` entry point.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGenerator.java` — call the new entry point in the `buildSchema` body emission; emit `.withSchemaAppliedDirectives(...)` before `.codeRegistry(...)`.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGeneratorTest.java` — unit-tier assertion on the generated source.
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java` — pipeline-tier assertion on `_service.sdl` containing `schema @link(...)`.
- Optional: add a `federated-multi-file/` fixture + sibling pipeline-tier test.

### Out of scope

- Re-evaluating whether `Federation.transform(base).setFederation2(true)` is the right runtime wrap. Separate concern; that path also injects `_Service` and `_entities`, and the entity-resolver wiring is independent.
- Multi-federation-`@link` consumer schemas. `FederationLinkApplier` already rejects more than one federation `@link` with a developer-readable error.
