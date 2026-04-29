---
title: "Retire `graphitron-maven-plugin` + `graphitron-schema-transform`"
status: In Progress
priority: 11
theme: legacy-migration
depends-on: [graphitron-lsp]
---

# Retire `graphitron-maven-plugin` + `graphitron-schema-transform`

Umbrella tracker. Fold the remaining transform passes and the Maven surface into `graphitron-rewrite` so every schema pass has a single code-owner and consumers depend on `graphitron-maven` only. End state: `mvn install -f graphitron-rewrite/pom.xml` produces a self-contained plugin jar and the legacy modules delete.

Most of this umbrella has shipped; see [`changelog.md`](changelog.md) for the build surface (schema loading, tagged inputs, Maven plugin, aggregator-standalone, content-idempotent writes) and `@asConnection` emit-time synthesis.

## Remaining sub-items

- **Java LSP rewrite + `dev` goal** — own plan: [graphitron-lsp.md](graphitron-lsp.md). Successor to the Rust `graphitron-lsp`, the legacy `graphitron-maven-plugin:introspect` goal, and the existing `graphitron-rewrite:watch` Mojo.
- **`@notGenerated` directive removed** — own plan: [remove-notgenerated.md](remove-notgenerated.md). Directive dropped from the supported set.
- **Federation SDL integration** — bundled with the *Apollo Federation via federation-jvm transform* backlog item; tracked there.
- **Retire legacy + unnest the rewrite aggregator** — closing landing marker once every legacy consumer has migrated to the new plugin. Delete `graphitron-common`, `graphitron-codegen-parent/` (both submodules), `graphitron-maven-plugin`, `graphitron-schema-transform`, `graphitron-example`, `graphitron-servlet-parent` (if legacy-only), and the top-level `pom.xml`. Promote `graphitron-rewrite/` to the repo root: aggregator POM becomes root POM; modules relocate up one level; the duplicated javapoet copy becomes the only copy; the two parent POMs merge. One-time repo-topology change; no consumer-visible surface beyond git-history refs and CI path updates. Trigger: every legacy consumer migrated (cadence dictated by per-consumer feature work).

## Drive-bys this retirement closes

- **Paginated-fields transform coexistence.** Today, when `@asConnection` is stripped by the legacy `graphitron-schema-transform` before the rewrite builder sees the schema, `defaultPageSize` is lost. Folded here because the concern vanishes when `graphitron-schema-transform` retires alongside the legacy plugin: there is no precursor pass to lose the metadata to. Until that final retirement step, consumers running both pipelines should be aware that `defaultPageSize` only survives if the rewrite builder reads the un-stripped SDL.

## Architecture decisions that pruned scope

The programmatic-schema architecture (`Graphitron.buildSchema(...)`, see changelog) made several originally-planned migrations moot and they have been removed from the umbrella: type-extension merging (`MergeExtensions`) is a non-issue without the registry-level bridge; directive stripping (`DirectivesFilter`, `GenerationDirective`) is irrelevant because rewrite no longer emits a client SDL; the runtime no longer reads `schema.graphql` at bootstrap so neither client-SDL emission nor feature-flag SDL splits (`FeatureConfiguration`, `SchemaFeatureFilter`, `<outputSchemas>`) are needed.
