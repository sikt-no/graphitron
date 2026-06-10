---
id: R291
title: "Strip Graphitron-internal directives and their supporting types from the published SDL"
status: Backlog
bucket: feature
priority: 2
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Strip Graphitron-internal directives and their supporting types from the published SDL

The SDL that `SchemaSdlEmitter` writes to `schema.graphqls` (the artefact a consumer publishes as its subgraph) still contains Graphitron's generate-time directive definitions and the supporting input/enum types they reference: `ErrorHandler`, `ErrorHandlerType`, `ExternalCodeReference`, `FieldSort`, `MutationType`, `ReferenceElement`, `ReferencesForType`, `SortDirection` (all defined in `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`). These are internal to the generator: they exist only so directive arguments type-check during classification, are fully consumed at generate time, and mean nothing to clients or to supergraph composition. Their presence currently **blocks subgraph publishing**: Apollo Studio linting flags them under `ENUM_USED_AS_INPUT_WITHOUT_SUFFIX`, `INPUT_TYPE_SUFFIX`, `TYPE_SUFFIX`, `ALL_ELEMENTS_REQUIRE_DESCRIPTION`, and `DEFINED_TYPES_ARE_UNUSED` (40+ violations observed against a real consumer schema). The published SDL should filter out Graphitron's directive definitions, their applications, and the supporting types that are reachable only from those directive definitions, while leaving consumer-authored schema untouched. Mind the parity invariant from R253 (`pipeline<->runtime SDL parity`): whatever filtering the emitter does, the runtime-rebuilt schema and the emitted SDL must keep describing the same client-visible contract, so the stripping likely belongs at a shared point or must be mirrored on both arms. Out of scope: lint violations on generated *client-facing* types (e.g. missing descriptions on generated `*Connection`/`*Edge` types); that is a separate concern about generated-type descriptions, not internal leakage.
