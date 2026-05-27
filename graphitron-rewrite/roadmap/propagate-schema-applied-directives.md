---
id: R250
title: "Propagate schema-applied directives (@link) from consumer SDL to generated buildSchema"
status: Backlog
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

**Sketch of the fix:** in `GraphitronSchemaClassGenerator`, alongside the existing `DirectiveDefinitionEmitter.survivors(assembled)` loop, iterate `assembled.getSchemaAppliedDirectives()` and emit equivalent `GraphQLAppliedDirective.newDirective().name(...).argument(...)` constructor calls, then attach via `schemaBuilder.withSchemaAppliedDirectives(...)`. The codegen-time `assembled` schema is the right source — `FederationLinkApplier` has already validated and parsed the consumer's `@link`, and graphql-java's `SchemaGenerator` writes it onto the assembled schema's applied-directive list. Sakila's `FederationBuildSmokeTest` should grow a `_service.sdl` assertion that the result carries `schema @link(url: "...federation/v2.10", ...)` so this can't regress silently.

**Sanity check before writing the fix:** confirm R247's `schema.graphqls` emission preserves the schema-level `@link` for the opptak-shaped input (consumer's `extend schema @link(...)` lives in one file among several, no explicit `schema { ... }` in any file). My standalone graphql-java repro with that exact shape preserved the `@link` end-to-end through `ServiceSDLPrinter.generateServiceSDLV2`, but if the actual `target/generated-resources/.../schema.graphqls` from opptak's build also lacks `@link`, then `assembled.getSchemaAppliedDirectives()` is empty earlier in the pipeline and the codegen fix above isn't enough on its own.
