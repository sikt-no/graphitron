---
id: R298
title: "Leverage Rover and GraphOS: composition checks in CI, contract verification, rover lsp in the dev loop"
status: Backlog
bucket: feature
priority: 3
theme: tooling
created: 2026-06-10
last-updated: 2026-07-14
---

# Leverage Rover and GraphOS: composition checks in CI, contract verification, rover lsp in the dev loop

Pivoted 2026-07-14. This item was filed as a one-shot manual verification (build a real Apollo Federation contract once, confirm that the type-level-only `@tag` applications R295 puts on synthesised Connection/Edge/PageInfo declarations satisfy contract composition; see git history of `federation-tag-first-client-contract-check.md`). Two things changed the shape:

1. The core doubt is analytically resolved by Apollo's contract reference: when a tag is applied to the *definition* of an object or interface type, GraphOS automatically considers that tag applied to all of the type's fields. Type-level-only tags on the synthesised types are therefore sufficient under an includes-based contract, per the documented semantics. The reference also states the complementary authoring rule (a tag on a type should also appear on every field *returning* that type), which is the carrier field's tag, already applied field-level by `TagApplier`. What remains is empirical confirmation, and that is cheap once tooling exists.
2. Rather than a one-off check, the useful deliverable is standing Rover/GraphOS integration, which makes this check and every future federation regression a CI concern instead of a manual errand.

## Work packages

### 1. Local composition check in CI (no GraphOS account needed)

Compose the generated federated subgraph SDL from the `rewrite-generate-federated` fixture as a single-subgraph supergraph on every trunk push, catching composition-breaking emitter regressions (invalid `@key`/`@tag`/link-spec/directive-definition output). No exporter work is needed: `SchemaSdlEmitter.emit` already writes the federation SDL to `target/generated-resources/graphitron/<outputPackage>/schema.graphqls` at generate time.

Two interchangeable engines, decided at Spec time (hands-on findings, 2026-07-14, rover 0.41.0):

* `rover supergraph compose --config supergraph.yaml` is the CLI route, but rover itself does not contain composition: it downloads a separate `supergraph` plugin binary from `rover.apollo.dev` on first use (hangs in restricted networks; `--skip-update` reuses an installed plugin, so CI must pin and cache it). Rover installs cleanly from npm as `@apollo/rover` with per-platform binary packages, no postinstall downloads. It also phones telemetry home to `rover.apollo.dev` (set `APOLLO_TELEMETRY_DISABLED` in CI).
* `@apollo/composition` from npm is the same engine the plugin embeds, callable from a ~15-line Node script (`composeServices([{name, typeDefs, url}])`), with no plugin download at all. Verified working: it composed a hand-written tagged connection schema and correctly rejected the real fixture SDL (see below). Simpler moving part for CI; rover proper is only *required* where GraphOS enters (package 2).

Proof of value: the first-ever composition run over the real fixture SDL found a live bug, R480 (`oneof-augment-defeated-by-descriptions`): the emitted federated SDL carries the `@oneOf` application without its definition because `OneOfDirectiveSdl.augment`'s substring guard is defeated by a description quoting the definition text, so Apollo composition rejects the subgraph with exactly the error R283 set out to prevent. The graphql-java round-trip tests cannot see it. Acceptance for this package: the CI check exists and is green, which requires R480 fixed first.

### 2. GraphOS contract verification (closes the original R298 question empirically)

One gap first: the federated fixture currently has zero `@tag` applications (the generated SDL defines the directive but never applies it; the type-level-tag behaviour is only exercised by `ConnectionFederationTagPipelineTest`'s own SDL in the `graphitron` module). Extend `federated-schema.graphqls` with a tagged `@asConnection` carrier so the generated SDL exercises R295's type-level tag propagation end-to-end. Then publish the fixture schema to a GraphOS graph variant, create a contract variant whose filter *includes* that tag, and run `rover subgraph check` in CI: contract checks run automatically alongside composition and operation checks (Rover >= 0.8.2) and fail the check when a blocking downstream contract breaks. First green run closes the original question (do type-level-only tags on synthesised types survive a real contract build); thereafter it is a standing regression gate for the whole `@tag` surface. Open questions for Spec: contract checks are a GraphOS Enterprise-plan feature, so confirm the org's plan and which graph/variant to use; decide whether the check runs on trunk pushes or only when federation-relevant paths change; secrets handling for `APOLLO_KEY` in CI.

### 3. rover lsp in the dev loop

`rover lsp --supergraph-config supergraph.yaml` (confirmed present in rover 0.41.0; >= 0.27.0 for composition-based diagnostics, and it fetches the same `supergraph` plugin binary as `compose`) is Apollo's language server; it surfaces composition errors and hints against subgraph schema files on save, and powers the Apollo VS Code/JetBrains/vim integrations. For graphitron users the subgraph SDL is *generated output*, not the authored `.graphqls` source, so rover lsp complements rather than overlaps the graphitron LSP: graphitron's LSP diagnoses the authored schema; rover lsp diagnoses the generated federated artifact after each `graphitron:dev` regeneration. Deliverable: a documented (and, if it earns its keep, `graphitron:dev`-integrated) setup where the dev loop writes the federated SDL to a stable path and a checked-in `supergraph.yaml` points rover lsp at it, giving authors composition feedback seconds after saving graphitron source. Spec should evaluate whether this is docs-only (a how-to page) or worth automation.

## Sequencing

Package 1 stands alone and is the natural first slice; its green state requires R480 (`oneof-augment-defeated-by-descriptions`) fixed, since the current fixture SDL fails composition. Package 2 rides on 1's SDL artifact, needs the fixture to gain `@tag` applications, and adds the GraphOS account dependency. Package 3 reuses the same artifact path and is independent of 2. `rover contract` itself is registry-only (`describe` / `publish`); the filtering runs in GraphOS, so there is no offline contract build to wire.

## Out of scope

- Re-tagging the synthesised types' fields (`edges`, `nodes`, `pageInfo`, `totalCount`, `cursor`, `node`): only if package 2's contract build refutes the documented type-level-tag semantics, which is not expected.
- Any change to `TagApplier`'s field-level tagging behaviour.
- Operating a router or serving the supergraph; this item is schema-delivery tooling only.
