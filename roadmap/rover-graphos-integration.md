---
id: R298
title: "Leverage Rover and GraphOS: composition checks in CI, contract verification, rover lsp in the dev loop"
status: Backlog
bucket: feature
priority: 3
theme: tooling
depends-on: [oneof-augment-defeated-by-descriptions]
created: 2026-06-10
last-updated: 2026-07-15
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

### 3. Federation intelligence in the dev loop (three features, three ownership answers)

The target features, in priority order (set 2026-07-14): composition warnings and errors; jump-to-definition into other subgraphs' schemas; autocomplete and documentation for federation directives. The architectural constant behind all three: developers author graphitron SDL, while rover's tooling (`rover lsp --supergraph-config`, confirmed in 0.41.0; needs the same downloaded `supergraph` plugin as `compose`) only understands the files named in `supergraph.yaml`, which for graphitron users is the *generated* federation SDL. So a raw LSP multiplex (one facade server forwarding to both) would faithfully deliver rover's features onto a build artifact nobody edits. The right shape is selective, per feature:

* **Composition warnings/errors: consume, remap, republish.** Run composition after each `graphitron:dev` regeneration (one-shot `rover supergraph compose --format json`, or a headless child `rover lsp` session if incrementality ever matters) and remap each error from the generated SDL onto the authored source at coordinate granularity: resolve the error's location in the generated file to a schema coordinate (type or field), look the coordinate up in the authored schema where every element carries its `SourceLocation`, and publish through the existing `Diagnostics` pipeline. The developer sees the composition error on the authored field that produced the offending generated element. Degrades gracefully (feature off, one hint) when the plugin binary is unavailable.
* **Cross-subgraph jump-to-definition: translate the request, pass through the response.** A definition request on an authored-file position translates authored to generated coordinate and can then be answered from the sibling subgraphs. The useful asymmetry: result locations point into *other* subgraphs' schema files, which are real files in the workspace, so only the request needs translation, never the response. Two routes, Spec-time choice: proxy the translated request to a headless rover lsp child (reuses Apollo's resolution, costs a managed child LSP session), or answer natively by loading the sibling subgraph SDLs from `supergraph.yaml` into the workspace model (plain SDL parse on machinery we already have; also enables hover docs for cross-subgraph types). The coordinate-translation layer is shared with the diagnostics remap; build it once.
* **Federation directive completion and docs: native only, rover cannot help.** Federation directives are authored directly in graphitron SDL (`@link`, `@key(fields:, resolvable:)` in `federated-schema.graphqls`) and parsed by graphitron itself (`FederationSpec`, `FederationKeyFieldsParser`), in files rover never reads. Extend the LSP vocabulary with the federation directive set and documentation keyed off the schema's `@link` spec version, including graphitron-specific constraints generic Apollo tooling cannot know (for example `FederationKeyFieldsParser` rejecting nested `@key` selections with its declare-on-inner-type guidance).

Full LSP multiplexing (one facade routing untranslated requests by URI to both servers) is deferred until a real workspace hand-authors sibling subgraphs or `supergraph.yaml` alongside graphitron SDL; that is the case rover's native features serve unmodified.

## Sequencing

Package 1 stands alone and is the natural first slice; its green state requires R480 (`oneof-augment-defeated-by-descriptions`) fixed, since the current fixture SDL fails composition. Package 2 rides on 1's SDL artifact, needs the fixture to gain `@tag` applications, and adds the GraphOS account dependency. Package 3 reuses the same artifact path and is independent of 2; within it, the diagnostics remap comes first (it shares the coordinate-translation layer the jump-to-definition feature needs, and its engine is package 1's), then directive completion/docs (pure graphitron-lsp work, no rover dependency at all), then cross-subgraph navigation. `rover contract` itself is registry-only (`describe` / `publish`); the filtering runs in GraphOS, so there is no offline contract build to wire.

## Out of scope

- Re-tagging the synthesised types' fields (`edges`, `nodes`, `pageInfo`, `totalCount`, `cursor`, `node`): only if package 2's contract build refutes the documented type-level-tag semantics, which is not expected.
- Any change to `TagApplier`'s field-level tagging behaviour.
- Operating a router or serving the supergraph; this item is schema-delivery tooling only.
