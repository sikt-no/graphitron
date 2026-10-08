---
id: R1002
title: "Publish each node type's typeId as a composable directive so the supergraph can map type IDs to type names"
status: Spec
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Publish each node type's typeId as a composable directive so the supergraph can map type IDs to type names

## Goal

A federated graph can opt in to having every node type carry its wire `typeId` in the published schema, as a directive that survives composition. A node subgraph (one that answers `Query.node(id)` by finding the `__typename` an ID belongs to) can then read the `typeId` to type-name mapping off the supergraph and decode the ID itself, instead of fanning `node(id) { __typename }` out to every owning subgraph with the caller's credentials. That fan-out breaks for subgraphs that authenticate on a header the node subgraph must not forward, and it makes each owning subgraph say whether an ID exists before field resolution has authorized the caller (GitHub issue #557).

Today `@node(typeId:)` is a build-time directive: the generator reads it and it never reaches the published schema. The minimal pair, from the sakila example's node types:

```graphql
# Author writes (unchanged)
type Customer implements Node @table(name: "customer") @node(typeId: "46") { ... }

# _service { sdl } today
type Customer implements Node @key(fields: "id") { ... }

# _service { sdl } with the opt-in
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key", "@composeDirective"])
  @link(url: "<graphitron node spec url>/v1.0", import: ["@nodeType"])
  @composeDirective(name: "@nodeType")

directive @nodeType(typeId: String!) on OBJECT

type Customer implements Node @key(fields: "id") @nodeType(typeId: "46") { ... }
```

The value is the *effective* `typeId`, the one the generator encodes into IDs: the author's `@node(typeId:)` where written, else the id the jOOQ class publishes, else the type name. A type that never wrote `@node(typeId:)` gets the directive too.

## Implementation

This extends two seams that already exist for the synthesised federation `@key`, so no new mechanism stands beside them.

- **The fact is already stored.** `graphitron_node.type_id` is the resolved wire id, one row per node type, with its origin. No new relation holds the `typeId`; the application is a rendering of that row.
- **Applying it.** `EmittedRegistry.of` already patches the emitted registry after capture (`applyInheritedTags`, `applySynthesisedKeys`). Add an `applyNodeTypeIds` step beside them that adds `@nodeType(typeId: <graphitron_node.type_id>)` to each node object type. The type name and id come off the row, not restated at the site, on `applySynthesisedKeys`' terms. Like `applySynthesisedKeys` it refuses a type that already carries `@nodeType` as a generator defect, since the registry it patches is the pre-synthesis one.
- **The opt-in is the `@link`, not a second flag.** The step runs for a graph whose `graphitron_link_entry` holds the Graphitron node spec link importing `@nodeType`, found the way `graphitron_synthesized_federation_key` finds the federation link. The Maven setting (see below) is only what adds that link, exactly as `<schemaInput tag>` adds the federation link through `TagLinkSynthesiser`. An author may also write the `@link` by hand. Deriving from the link keeps the question "does this graph publish `@nodeType`" answerable from the store alone.
- **The link and the definition.** Add `NodeTypeLinkSynthesiser` beside `TagLinkSynthesiser`, run in the same `LoadingRewrites` sequence. It synthesises `extend schema @link(url: <node spec>, import: ["@nodeType"]) @composeDirective(name: "@nodeType")` when the author wrote none, and refuses with a fatal `ValidationError` naming the author's `@link` when the federation `@link` is missing `"@composeDirective"` in its `import:` or sits below v2.1. It also injects `directive @nodeType(typeId: String!) on OBJECT`: federation-jvm cannot supply a definition for a spec it does not know, and `@nodeType` must not be declared in Graphitron's own `directives.graphqls`, because `SchemaDirectiveRegistry` treats everything declared there as generator-only and would strip it from the output. Add the spec URL constant beside `FederationSpec.URL`. If the derivation spells that URL as a SQL literal, add a pin in the manner of `FederationLinkPrefixPinTest` so the literal and the Java constant cannot drift apart.
- **Maven setting.** One boolean plugin parameter (name to settle at pickup; `nodeTypeDirective` is the working name), bound in `AbstractRewriteMojo` and carried through the run context the way `<schemaInput tag>` reaches `LoadingRewrites`. Default off, since not every subgraph is federated and a non-federated graph has no use for the link. Turning it on for a graph with no federation `@link` synthesises one importing `@key` and `@composeDirective`, mirroring the tag case.
- **Both output arms.** `SchemaSdlEmitter` prints the generated `schema.graphqls` and `GraphitronSchemaClassGenerator` builds the runtime schema whose `_service { sdl }` consumers actually serve; `AppliedDirectiveEmitter.emitSchemaApplications` already carries schema-level `@link` into the runtime build. Confirm both arms read the patched registry, so the directive, its definition and the `@composeDirective` application appear in both. The agreement is a test, not a belief.
- **First verification at pickup (the one real risk).** Whether federation-jvm's `LinkDirectiveProcessor` and `Federation.transform` tolerate a second `@link` to a non-federation URL, and whether `_service.sdl` prints `@composeDirective` and the custom `@link` through unchanged. The rest of the plan assumes yes. Spike this first against `FederationLinkApplier`. If the library drops or rejects the link, the fallback is to print those two schema-level lines in `SchemaSdlEmitter` itself, as `OneOfDirectiveSdl.augment` already does for `@oneOf`, and to feed the runtime build the same text.
- **Open at pickup, composition behaviour.** What Apollo composition does when two subgraphs apply `@nodeType` with different `typeId`s to the same entity type (a non-repeatable composed directive). The spec URL should be a stable URL Graphitron owns; composition identifies the spec by it and does not fetch it. Choose a spec name other than `node`, so `@node` (generator-only) and the spec's namespace are never confused in an SDL reader's head.

## Tests

- Pipeline tier: a federated graph with the opt-in over `@node(typeId: "46")`, a type with the id published by its jOOQ class, and a type with neither. The assembled and printed SDL carries `@nodeType` with the effective id on each, the `directive @nodeType` definition, the custom `@link` and `@composeDirective(name: "@nodeType")`. The control: opt-in off, byte-identical SDL to today.
- Refusal cases for `NodeTypeLinkSynthesiser`: federation `@link` without `"@composeDirective"`, and a pre-2.1 federation URL, each failing with a message that names the author's `@link`.
- Agreement: the generated `schema.graphqls` and the runtime `_service { sdl }` of the sakila federated example carry the same three pieces. The execution tier asserts on `_service { sdl }`, not on the print.
- Store: a derivation pin that `graphitron_node.type_id` is what the directive carries for each of the three `type_id_origin` values.

## First-client docs draft

For `docs/manual/how-to/apollo-federation.adoc`, a new section after "The `<schemaInput tag>` flag", and a sentence on `node.adoc` and `global-id.adoc` saying the id can be published.

> **Publish typeIds to the supergraph.** A node subgraph that resolves `Query.node(id)` needs to know which type an ID belongs to. Set `<nodeTypeDirective>true</nodeTypeDirective>` on the plugin and every node type in the subgraph carries `@nodeType(typeId: "...")` in `_service { sdl }`; composition keeps it, so the supergraph holds the mapping from typeId to type name and the node subgraph decodes an ID without calling the owning subgraph. The value is the id Graphitron encodes into IDs for that type. Each subgraph must define the same directive, which the setting does for you; this needs Federation 2.1 or later, and the build tells you if your `@link` is older or does not import `"@composeDirective"`.
>
> **Keep typeIds unique across subgraphs.** Composition checks neither that two types share a `typeId` nor that two subgraphs disagree about one. Pick the ids from one scheme for the whole supergraph. Within one subgraph the build already refuses a duplicate (`validateNodeTypeIdUniqueness`).

## Other solutions we've considered

- **Make `@node` itself survive** instead of a second directive. Rejected: `@node` also carries `keyColumns:` and `typeId:` is optional on it, so it would publish database column names and leave defaulted types unmapped. The applied `@nodeType` carries the one resolved fact consumers need.
- **A separate flag read by the derivation** instead of reading the `@link`. Rejected: a second source of truth for one question, and an author who writes the `@link` by hand would get no directive.
- **Always on in a federated graph.** Rejected by the issue's own note: not every federated subgraph has a node subgraph in front of it, and the link adds a directive definition every subgraph must keep identical.
