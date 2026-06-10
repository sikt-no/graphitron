---
id: R295
title: "Synthesised connection/edge types must inherit the federation tags of the @asConnection carrier field"
status: Backlog
bucket: bug
priority: 3
theme: pagination
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Synthesised connection/edge types must inherit the federation tags of the @asConnection carrier field

## Problem

`ConnectionPromoter` promotes a carrier field annotated `@asConnection` into a synthesised `<Parent><Field>Connection` / `<Parent><Field>Edge` pair, plus a synthesised `PageInfo` when the SDL does not declare one. The promotion copies the carrier's `@shareable` onto the synthesised types (`promotionFor` reads it off the carrier; `buildSynthesisedConnection` / `buildSynthesisedEdge` / `buildSynthesisedPageInfo` apply it), but it drops every federation `@tag(name:)` the carrier holds. Tags reach the carrier either explicitly in the SDL or via `TagApplier` when `<schemaInput tag>` is configured; both forms survive on the carrier field itself (`rewriteCarrierField` passes applied directives through), yet the synthesised types come out untagged.

Apollo Federation contracts filter the graph by `@tag`. A contract that includes the carrier's tag keeps the field but not its untagged return type, so the kept field references a filtered-out type and contract composition breaks. The synthesised types exist only because of the tagged carrier field, so they must carry the same tags.

Legacy note: legacy `MakeConnections` stamped the carrier's source location onto the synthesised definitions, so the legacy `<schemaInput tag>` flow tagged the synthesised types' fields through source-file lookup. The rewrite synthesises programmatically after `TagApplier` has already run at registry stage, so nothing tags the synthesised types today.

## Expected behaviour

* Synthesised Connection and Edge object types inherit every `@tag` applied to the carrier field, value for value (the directive is repeatable; copy all of them).
* The synthesised `PageInfo` carries the union of tags across all promoted carriers, mirroring the existing `pageInfoShareable |=` fold: one `PageInfo` serves every connection, so a contract that keeps any tagged connection needs it in scope.
* Structural connections (SDL-declared Connection/Edge types) and an SDL-declared `PageInfo` stay untouched; the author owns the tags there.
* Carriers sharing one connection name through `@asConnection(connectionName:)` (possible until R208 retires the override): the synthesised type should carry the union of the carriers' tags. The `shareable` flag currently resolves first-write-wins on the type and only folds for `PageInfo`; for tags, prefer the union, since a missing tag is a composition break rather than a redundancy hint. If the union requires a pre-pass that feels disproportionate ahead of R208, first-write-wins consistent with `shareable` is an acceptable interim, recorded as a known gap.

## Open question for review

Type-level `@tag` on the synthesised types is the minimal shape that satisfies contract composition, and matches how the bug is framed. The legacy flow effectively tagged the synthesised types' fields instead. Decide whether type-level tags alone are sufficient for the contract configurations our subgraphs use, or whether fields (`edges`, `nodes`, `pageInfo`, `totalCount`, `cursor`, `node`) need the tags too.

## Implementation

* `ConnectionPromoter.promotionFor`: collect the carrier's `@tag` applied directives and carry them on `ConnectionPromotion` next to `shareable`.
* `buildSynthesisedConnection` / `buildSynthesisedEdge` / `buildSynthesisedPageInfo`: apply the collected tags beside the existing `shareable` arm.
* No model-record changes expected: nothing reads `shareable()` off `GraphitronType.ConnectionType` / `EdgeType` / `PageInfoType` today; emission goes through `schemaType()`. The tagged schema forms flow into both output surfaces: the assembled schema (`rebuildAssembledForConnections` registers them via `additionalType`, which is what the federation SDL emission prints) and the generated runtime schema (`ObjectTypeGenerator` emits from `schemaType()` and `AppliedDirectiveEmitter` walks applied directives generically). Thread tags onto the records only if a consumer turns up.

## Tests

* Pipeline tier, explicit tag: fixture with a carrier `field: [Item] @asConnection @tag(name: "x")`; assert the built schema's synthesised Connection, Edge, and PageInfo types carry `@tag(name: "x")` and the carrier keeps its own.
* Pipeline tier, schemaInput tag: drive `<schemaInput tag>` through `loadAttributedRegistry()` in the `TaggedInputsPipelineTest` shape with `@asConnection` on a field in the tagged source; assert the synthesised types inherit the tag.
* Repeatable tags: carrier with two `@tag` values; both appear on the synthesised types.
* Shared connection name: two carriers with the same `connectionName:` and different tags; assert whichever union/first-write semantics the review settles on.
* Emission: assert the federation SDL round-trip (the `FederationBuildSmokeTest` shape; see also R252) prints the tags on the synthesised types.

## Docs

`docs/manual/reference/directives/asConnection.adoc` says nothing about federation directive propagation today. Add one sentence (draft, first-client check): "Federation directives on the field (`@shareable`, `@tag`) propagate onto the synthesised Connection, Edge, and PageInfo types, so federation contracts that include the field's tags also include its pagination types."
