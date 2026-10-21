---
id: R1081
title: "graphitron-jakarta-rest /graphql/schema serves SDL federation composition rejects"
status: Backlog
bucket: bug
priority: 3
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# graphitron-jakarta-rest /graphql/schema serves SDL federation composition rejects

## Goal

A federated subgraph served through `graphitron-jakarta-rest` answers `GET /graphql/schema` with SDL that Federation 2 composition accepts: the same SDL the generator already writes to `schema.graphqls`, carrying the `schema @link(url: ".../federation/v2.x", ...)` declaration and no definitions of the GraphQL built-in directives. A supergraph composed from schema URLs then works for every library consumer, and no consumer needs its own schema resource to get there.

## The defect

`GraphqlHttpHandler.schema()` prints the runtime schema with `new SchemaPrinter()` at default options. In graphql-java 25 those defaults print the `schema { ... }` block only when a root type has a non-standard name, so the block that carries the federation `@link` is dropped; and they print every directive definition, spec built-ins included. A composer reading that SDL (observed with `@theguild/federation-composition` 0.27.0 through the fs-plattform `supergraf-compose` tool) therefore:

- sees no `@link`, treats the subgraph as Federation 1, and rejects `@key`, `@requires` and `@provides` because their `fields` argument is `federation__FieldSet!` where Federation 1 expects `_FieldSet!` (`DIRECTIVE_DEFINITION_INVALID`);
- adds its own `@skip`, `@include` and, in Federation 1 mode, `@key`, then rejects the printed duplicates ("There can be only one directive named ...", `INVALID_GRAPHQL`).

Composition by introspection works, because the `{ _service { sdl } }` query is answered by federation-jvm's own printer. Composition from the schema URL is the path that fails, and it is the only path for a subgraph that does not answer introspection.

The correct SDL already exists. `SchemaSdlEmitter.printFederationServiceSdl` prints `schema.graphqls` with `includeSchemaDefinition(true)`, drops the spec built-in directive definitions, filters to surviving directives, and reinstates the `@oneOf` definition composition needs. The library endpoint re-derives the SDL from the runtime schema instead of serving that artifact, and the re-derivation loses what the emitter took care to keep.

## Observed in

fs-plattform, 2026-10-21. `utdanningsregisteret` and `kompetanse` extend `AbstractGraphitronApplication` and rely on the library route; both fail composition from their schema URL with the errors above. `opptak` (a hand-written resource, not the library) and `tilgangsstyring` (the library with `builtInEndpointEnabled()` returning `false` and its own `SchemaResource`) serve the generated `schema.graphqls` from the classpath and compose. Minimal repro: a composition config naming one library-served federated subgraph with a `"schema": "<base>/graphql/schema"` URL.

## Direction

Serve the generated SDL rather than printing a new one. The open question for Spec is how the library reaches the file, since it never names a per-subgraph type: a method on the `GraphitronApplication` SPI that returns the SDL, fed by an accessor the generated `Graphitron` facade exposes over its `schema.graphqls` resource, is the obvious shape. A non-federated schema keeps working: `schema.graphqls` is printed for it too (`SchemaSdlEmitter.printPlain`). The module compiles at release 17, and serving a resource adds no dependency, where printing through federation-jvm's `ServiceSDLPrinter` would add one.

Coverage wants a test that the served SDL is the emitted file, plus one that a federated fixture's served SDL starts with the `@link` schema declaration and carries no `@skip`/`@include` definitions, so the regression is pinned at the HTTP surface rather than only at the emitter.

The manual how-to for serving a schema over HTTP (`manual-howto-serve-schema-over-http`) should describe the `/graphql/schema` endpoint as composition-ready once this lands.
