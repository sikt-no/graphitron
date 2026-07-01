---
id: R252
title: "Multi-file federation fixture coverage for schema.graphqls emission"
status: Backlog
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Multi-file federation fixture coverage for schema.graphqls emission

R247's `SchemaSdlEmitter` writes `target/generated-resources/.../schema.graphqls` by calling `ServiceSDLPrinter.generateServiceSDLV2(assembled)` on the codegen-time schema. The only pipeline-tier coverage is `FederationBuildSmokeTest` against sakila's single-file federated fixture (`federated-schema.graphqls`, which carries `extend schema @link(...)` and types in the same file). Real-world consumer schemas often split across many files — one carrying `extend schema @link(...)`, others carrying types only, no explicit `schema { ... }` block anywhere — and that shape goes through a different code path in `RewriteSchemaLoader` (`MultiSourceReader` concatenates streams, single `Parser.parseDocument`, single `SchemaParser.buildRegistry`).

A standalone graphql-java repro with the multi-file shape preserved `@link` end-to-end through `ServiceSDLPrinter.generateServiceSDLV2`, so this is plausibly already fine; but no test exercises the actual graphitron pipeline through `RewriteSchemaLoader` for a multi-file federation fixture, so a future regression in the loader or any of the registry-stage appliers (`TagApplier`, `KeyNodeSynthesiser`, `DescriptionNoteApplier`, `FederationLinkApplier`) could silently break the round-trip without any test catching it.

**Sketch of the fix:** add a `federated-multi-file/` fixture under `graphitron-sakila-example/src/main/resources/graphql/` with the `@link` extension in one file and the types in another. Mirror the existing `FederationBuildSmokeTest` assertions onto it — specifically the `_service.sdl` round-trip and the `serviceSdlExposesCanonicalKeyDirectiveShape` assertion — and also assert that the emitted `target/generated-resources/.../schema.graphqls` for that fixture starts with `schema @link(...)`.

R250 covers the runtime-build path (`GraphitronSchema.java`'s `buildSchema` propagating schema-applied directives). This item covers the file-emission path (`SchemaSdlEmitter.emit` on a multi-file input).
