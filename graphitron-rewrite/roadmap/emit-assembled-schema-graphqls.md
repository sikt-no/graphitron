---
id: R247
title: "Emit assembled schema.graphqls into generated-resources, federation-aware"
status: Backlog
bucket: feature
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Emit assembled schema.graphqls into generated-resources, federation-aware

Downstream CI pipelines (supergraph composition, schema publication, contract diffing) need a single resolved `schema.graphqls` artifact, but graphitron-rewrite emits Java only: `grep -rn "SchemaPrinter\|schema.graphqls" graphitron-rewrite/graphitron-maven-plugin/src/main` returns nothing, and the plugin's input-side `*.graphqls` files are pre-assembly fragments (per-file `extend type` declarations, no resolved federation `@link`s). Consumers either reconstruct the schema at runtime from `GraphitronSchema.build()` or hand-maintain a published copy alongside the generated code; neither composes cleanly into a CI step that needs the SDL as a build output.
This item adds a Mojo step that prints the assembled schema to `target/generated-resources/graphitron/schema.graphqls` (resource-root, so it ships in the jar) and is federation-aware: when the schema declares the Apollo Federation `@link` (`FederationSpec.URL`, see `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/schema/federation/FederationSpec.java:14-23`), use `federation-graphql-java-support`'s subgraph printer (already a runtime dep, pinned at `6.0.0` in `graphitron-rewrite/pom.xml:60-63`) so federation directives and the `@link` survive the round-trip; otherwise fall back to graphql-java's `SchemaPrinter` with a directive-aware configuration. The federation library is already on the classpath because `FederationLinkApplier` and friends use it at generate time; this item just adds a sink.
Open questions to resolve at Spec time:
- **Which schema to print.** The assembled `GraphQLSchema` (post-`SchemaTransformer`, the runtime view, which is what subgraph consumers actually need) vs a concatenation of the input `*.graphqls` fragments (cheaper, but loses connection synthesis and any directive rewriting). R10 (`drop-assembled-schema-rebuild`) is the tangent here: it proposes to retire the in-process rebuild; this item depends on whatever shape that rebuild leaves behind. R98 (`multi-source-input-validation`) names "unified rendered schema" as a substrate for validation; the same substrate is the printable artifact.
- **Where the output lands and which lifecycle phase writes it.** Resource root attached at `generate-resources`, or a configurable Mojo parameter? Sakila example's CI is the natural smoke-test consumer; pick the phase that lets `mvn install` produce the file without an extra goal invocation.
- **Which Mojo wires it.** Extend the existing `GenerateMojo` (one fewer step for consumers, but couples Java emission to SDL emission) or a sibling `EmitSchemaMojo` bound to its own phase. The former is simpler; the latter lets consumers turn off Java generation while still publishing SDL, which may matter for downstream-only repos.
- **Federation detection.** Inspect the assembled schema for an applied `@link(url: "https://specs.apollo.dev/federation/v2.X")` directive vs a config flag on the Mojo. Detection is more robust; flag is more explicit.
