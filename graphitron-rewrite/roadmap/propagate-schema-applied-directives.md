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

The emitter shape mirrors `DirectiveDefinitionEmitter` exactly: a `survivorApplications(GraphQLSchema)` entry point returns the typed graphql-java values (`List<GraphQLAppliedDirective>`), filtered through `SchemaDirectiveRegistry.isSurvivor(...)` and sorted by name for deterministic output. The existing private `buildApplication(GraphQLAppliedDirective)` is lifted to package-private so callers reach a single `CodeBlock` per application without each call site re-deriving the shape. The existing `applicationsFor(GraphQLDirectiveContainer)` entry point stays as-is (it returns *pre-wrapped* `.withAppliedDirective(...)` blocks because the per-type containers want a one-call-per-item idiom); the new schema-level entry point is intentionally lower-level because the schema-level call site wants a single `.withSchemaAppliedDirectives(List.of(...))` rather than a per-item chain.

```java
// in AppliedDirectiveEmitter
public static List<GraphQLAppliedDirective> survivorApplications(GraphQLSchema schema) {
    return schema.getSchemaAppliedDirectives().stream()
        .filter(d -> SchemaDirectiveRegistry.isSurvivor(d.getName()))
        .sorted(Comparator.comparing(GraphQLAppliedDirective::getName))
        .toList();
}

static CodeBlock buildApplication(GraphQLAppliedDirective applied) { /* existing body, visibility widened */ }
```

Call site in `GraphitronSchemaClassGenerator`, immediately after the `survivors` loop (before `.codeRegistry(...)`):

```java
var schemaApplied = AppliedDirectiveEmitter.survivorApplications(assembled);
if (!schemaApplied.isEmpty()) {
    body.add("\n.withSchemaAppliedDirectives($T.of(", ClassName.get(List.class));
    var first = true;
    for (var applied : schemaApplied) {
        if (!first) body.add(", ");
        body.add(AppliedDirectiveEmitter.buildApplication(applied));
        first = false;
    }
    body.add("))");
}
```

`GraphQLSchema.Builder.withSchemaAppliedDirectives(List<GraphQLAppliedDirective>)` is the existing graphql-java API; no shim needed. Argument-value rendering routes through the same `ValuesResolver.valueToLiteral` + `AstPrinter.printAst` + `Parser.parseValue` chain that the per-type emitter already uses, so `@link`'s `import: ["@key", "@tag", ...]` argument round-trips through an AST list literal without any per-shape coding.

The `link__Import` scalar and `link__Purpose` enum referenced by the `@link` argument types are already registered on the runtime schema by the existing `.additionalType(...)` loop (R248's `ScalarTypeResolver` Synthesised arm for federation-namespace scalars; standard enum registration). `emitInputType`'s `GraphQLTypeReference.typeRef("link__Import")` resolves against those registrations at schema-build time. This is a load-bearing invariant — if a future change narrows the Synthesised arm or drops the enum registration, the emitted `schemaBuilder.build()` will fail at consumer runtime with `UnresolvedTypeReferenceException`. Step 2's pipeline-tier fixture pins this invariant: the fixture `@link` carries `import: ["@key"]` (forces `link__Import` resolution) and `for: EXECUTION` (forces `link__Purpose`).

### Step 2 — regression coverage

Two new assertions:

- **Unit tier** (`AppliedDirectiveEmitterTest` or sibling): build an `assembled` schema with a schema-applied `@link(url: "...federation/v2.10", import: ["@key"], for: EXECUTION)`, call `AppliedDirectiveEmitter.survivorApplications(assembled)`, and assert the returned `List<GraphQLAppliedDirective>` contains one application named `"link"` with the expected `url`, `import`, and `for` argument values. Structural assertion on the typed return — *not* a string match on rendered Java source. Mirrors `DirectiveDefinitionEmitterTest`'s shape (which also asserts on the survivors set, not the rendered chain).
- **Pipeline tier** (`FederationBuildSmokeTest`): assert that `Graphitron.buildSchema(b -> {}, fed -> {})`'s result, queried for `_service { sdl }`, returns SDL whose `schema {...}` block carries `@link(url : "https://specs.apollo.dev/federation/v2.10", import : ["@key"], for : EXECUTION)` (graphql-java printer style, space-around-colon). Locks the round-trip from consumer SDL through codegen through `Federation.transform` through `_service.sdl`, and exercises the `link__Import` + `link__Purpose` type-registration invariant at runtime.

To make Step 2's pipeline assertion exercise `for: EXECUTION`, the existing `federated-schema.graphqls` fixture's `@link` gets a `for: EXECUTION` argument added (currently `@link(url: "...", import: ["@key"])`). The argument is valid Fed2 SDL and doesn't change the existing test semantics.

### Files touched

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/AppliedDirectiveEmitter.java` — add `survivorApplications(GraphQLSchema)` entry point; widen `buildApplication` visibility from `private` to package-private.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGenerator.java` — call the new entry point in the `buildSchema` body emission; emit `.withSchemaAppliedDirectives(List.of(...))` before `.codeRegistry(...)`.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/AppliedDirectiveEmitterTest.java` — unit-tier structural assertion on `survivorApplications`.
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java` — pipeline-tier `_service.sdl` assertion carrying `@link(... for : EXECUTION)`.
- `graphitron-sakila-example/src/main/resources/graphql/federated-schema.graphqls` — add `for: EXECUTION` to the existing `@link`.

### Out of scope

- Re-evaluating whether `Federation.transform(base).setFederation2(true)` is the right runtime wrap. Separate concern; that path also injects `_Service` and `_entities`, and the entity-resolver wiring is independent.
- Multi-federation-`@link` consumer schemas. `FederationLinkApplier` already rejects more than one federation `@link` with a developer-readable error.
- Multi-file federation fixture coverage for R247's `schema.graphqls` file emission. Filed as a sibling Backlog item (R251); the parity gap with R247 is its own concern and the runtime-build path under R250 is exercised end-to-end by the pipeline-tier assertion above regardless of whether the input arrives as one file or many.
