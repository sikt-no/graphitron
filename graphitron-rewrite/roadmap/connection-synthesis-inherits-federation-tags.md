---
id: R295
title: Synthesised connection/edge types must inherit the federation tags of the @asConnection carrier field
status: Ready
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
* `PageInfo` follows one rule: if Graphitron synthesises it, it carries the union of the tags from all connection-promoted fields; if the schema declares `PageInfo`, it is not touched.
* The tag source per promotion arm mirrors how `shareable` is already read: the directive-driven arm reads the carrier field's tags; the structural arm reads the SDL-declared Connection type's own tags (an SDL-declared Connection tagged `@tag(name: "x")` with no SDL `PageInfo` hits the same composition break this item fixes). Both arms feed the synthesised PageInfo union, exactly as the `pageInfoShareable |=` fold does.
* Structural Connection/Edge types themselves stay untouched; the author owns the tags there.
* Carriers sharing one connection name through `@asConnection(connectionName:)` (possible until R208 retires the override): the synthesised type carries the union of the carriers' tags. This is pinned, not open: a missing tag is a composition break, and the union is cheap. At the dedupe site the existing registry entry is already in hand and `ctx.typeRegistry.enrich` exists, so the union is a transform of `existing.schemaType()` adding the missing `@tag` applications (plus the matching Edge); no pre-pass needed.

## Resolved: type-level tags now, field-level is a contingent follow-up

Decision (Spec → Ready review): the implementation tags the synthesised types at the **type level** only. That is the minimal shape that satisfies contract composition and matches how the bug is framed; the Expected behaviour, Implementation, Tests, and Docs sections all commit to it.

The field-level branch is deferred, not built. `TagApplier`'s emission scope (see its class javadoc: type declarations are never tagged, only fields, input fields, enum values, args, and unions) means that under `<schemaInput tag>` the synthesised types would carry the only type-level tags in the graph, while field-level is what legacy contracts were validated against. Whether type-level tags alone suffice for the contract configurations our subgraphs use cannot be decided at spec time; it needs a real contract build (the first-client check). The verification step belongs in In Review: build a real contract that includes the carrier's tag and confirm composition succeeds against the type-level tags. If, and only if, that build shows type-level tags are insufficient, file a follow-up (or extend in-scope if discovered during implementation) to tag the synthesised types' fields (`edges`, `nodes`, `pageInfo`, `totalCount`, `cursor`, `node`) as well. The implementer does not need to resolve this before starting.

## Implementation

* `ConnectionPromoter.promotionFor`: collect the arm-appropriate `@tag` applied directives (carrier field on the directive arm, Connection type declaration on the structural arm) and carry them on `ConnectionPromotion` next to `shareable`.
* `buildSynthesisedConnection` / `buildSynthesisedEdge` / `buildSynthesisedPageInfo`: apply the collected tags beside the existing `shareable` arm.
* Dedupe site (`promote`, the early-`continue` on an existing `ConnectionType`): instead of skipping, union the new carrier's tags into the existing entry via `ctx.typeRegistry.enrich` with a transformed `schemaType()` (and the matching Edge) when the new carrier brings tags the entry lacks.
* No model-record changes: nothing reads `shareable()` off `GraphitronType.ConnectionType` / `EdgeType` / `PageInfoType` today; emission goes through `schemaType()`. The tagged schema forms flow into both output surfaces: the assembled schema (`rebuildAssembledForConnections` registers them via `additionalType`, which is what the federation SDL emission prints) and the generated runtime schema (`ObjectTypeGenerator` emits from `schemaType()` and `AppliedDirectiveEmitter` walks applied directives generically). A `tags` record component would be a second representation of the same information that can diverge from `schemaType()`; do not add one.
* While in the file: the javadoc near the SDL-declared-PageInfo branch claims "the connection-emitter picks up the shareable flag", but the emitter reads `schemaType()`, never the flag; fix the stale claim. The `shareable` boolean on the three records is a collapse-audit candidate for the same reason (the PageInfo fold can read both shareable and tags uniformly off the schema forms, which also makes the dedupe-arm union symmetric); collapsing it is in scope if it falls out naturally, otherwise note it for a cleanup item.

## Tests

* Pipeline tier, explicit tag: fixture with a carrier `field: [Item] @asConnection @tag(name: "x")`; assert the built schema's synthesised Connection, Edge, and PageInfo types carry `@tag(name: "x")` and the carrier keeps its own.
* Pipeline tier, schemaInput tag: drive `<schemaInput tag>` through `loadAttributedRegistry()` in the `TaggedInputsPipelineTest` shape with `@asConnection` on a field in the tagged source; assert the synthesised types inherit the tag.
* Repeatable tags: carrier with two `@tag` values; both appear on the synthesised types.
* Shared connection name: two carriers with the same `connectionName:` and different tags; assert the synthesised Connection and Edge carry the union.
* Structural arm: SDL-declared tagged Connection with no SDL `PageInfo`; assert the synthesised PageInfo carries the Connection's tag.
* Negative pin: SDL-declared Connection/Edge and SDL-declared `PageInfo` pass through with their author-written tags unchanged (no synthesised additions).
* Emission: assert the federation SDL round-trip (the `FederationBuildSmokeTest` shape; see also R252) prints the tags on the synthesised types.

## Docs

`docs/manual/reference/directives/asConnection.adoc` says nothing about federation directive propagation today. Add one sentence (draft, first-client check): "Federation directives on the field (`@shareable`, `@tag`) propagate onto the synthesised Connection, Edge, and PageInfo types, so federation contracts that include the field's tags also include its pagination types."
