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

In a federated graph, every node type carries its wire `typeId` in the published schema, as a directive that survives composition, with nothing to configure. A node subgraph (one that answers `Query.node(id)` by finding the `__typename` an ID belongs to) can then read the `typeId` to type-name mapping off the supergraph and decode the ID itself, instead of fanning `node(id) { __typename }` out to every owning subgraph with the caller's credentials. That fan-out breaks for subgraphs that authenticate on a header the node subgraph must not forward, and it makes each owning subgraph say whether an ID exists before field resolution has authorized the caller (GitHub issue #557).

Today `@node(typeId:)` is a build-time directive: the generator reads it and it never reaches the published schema. The minimal pair, from the sakila example's node types:

```graphql
# Author writes (unchanged)
type Customer implements Node @table(name: "customer") @node(typeId: "46") { ... }

# _service { sdl } today
type Customer implements Node @key(fields: "id") { ... }

# _service { sdl } after
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
  @link(url: "<graphitron node spec url>/v1.0", import: ["@nodeType"])
  @federation__composeDirective(name: "@nodeType")

directive @nodeType(typeId: String!) on OBJECT

type Customer implements Node @key(fields: "id") @nodeType(typeId: "46") { ... }
```

The value is the *effective* `typeId`, the one the generator encodes into IDs: the author's `@node(typeId:)` where written, else the id the jOOQ class publishes, else the type name. A type that never wrote `@node(typeId:)` gets the directive too.

## Implementation

This extends two seams that already exist for the synthesised federation `@key`, so no new mechanism stands beside them.

- **The fact is already stored.** `graphitron_node.type_id` is the resolved wire id, one row per node type, with its origin. No new relation holds the `typeId`; the application is a rendering of that row.
- **Applying it.** `EmittedRegistry.of` already patches the emitted registry after capture (`applyInheritedTags`, `applySynthesisedKeys`). Add an `applyNodeTypeIds` step beside them that adds `@nodeType(typeId: <graphitron_node.type_id>)` to each node object type. The type name and id come off the row, not restated at the site, on `applySynthesisedKeys`' terms. Like `applySynthesisedKeys` it refuses a type that already carries `@nodeType` as a generator defect, since the registry it patches is the pre-synthesis one.
- **The trigger is the one the synthesised `@key` already uses**: the graph publishes federation directives (an authored federation `@link`, or the one `<schemaInput tag>` synthesises) and has at least one node type. No setting, no second flag. A graph with no federation `@link`, or with no node types, publishes exactly what it publishes today.
- **The fact the step reads is a new derived view, `graphitron_published_node_type`**: one row per node type of a graph that publishes federation directives, carrying `type_id` (from `graphitron_node`) and `compose_directive`, the directive name the graph's federation link makes available for `@composeDirective`. It reads upstream only: `graphitron_ast_link_entry` (federation URL prefix, the same spelling `FederationLinkPrefixPinTest` already pins) or `graphql_assembly_synthesised_link` for the federated condition, `graphitron_ast_link_import_entry` for the import list, and `graphitron_node`. The federated condition is today restated inside `graphitron_synthesized_federation_key` against the deprecated `graphitron_link_entry`; factor it into one view, `graphitron_federated_graph`, that both read, so the two rules cannot drift. A view and not a table because it restates a rule over captured rows rather than capturing a corpus, on `graphitron_synthesized_federation_key`'s own terms; settle that with the `adding-a-relation` skill at pickup, together with the owner, the grain sentence and the `meta_relation` and `meta_gatherer_dependency` entries it requires.
- **`compose_directive` is `@composeDirective`, under the author's alias if the import carries one, when the federation `@link` imports `"@composeDirective"`; otherwise `@federation__composeDirective`.** The namespaced form needs no import, but it is defined only when the name is not imported (spike below), so the spelling has to follow the import list. The `<schemaInput tag>`-synthesised link imports `"@tag"` alone, so it always takes the namespaced form. Federation below 2.1 is unsupported by Graphitron, so no version branch exists here; refusing such a `@link` with a validation error is separate work.
- **What the step adds**, in the same post-capture patch in `EmittedRegistry.of`, for each graph with rows: a schema extension carrying `@link(url: <node spec>, import: ["@nodeType"])` and the `@composeDirective` application spelled as above, the definition `directive @nodeType(typeId: String!) on OBJECT`, and the application on each node type. All of it lives in the emitted registry only, so nothing is injected into the corpus capture reads, `LoadingRewrites` is untouched, and no generator-injected source name reaches capture's stamp lookup. `@nodeType` must not be declared in Graphitron's own `directives.graphqls`, because `SchemaDirectiveRegistry` treats everything declared there as generator-only and would strip it from the output. Add the spec URL constant beside `FederationSpec.URL`.

- **Both output arms.** `SchemaSdlEmitter` prints the generated `schema.graphqls` and `GraphitronSchemaClassGenerator` builds the runtime schema whose `_service { sdl }` consumers actually serve; `AppliedDirectiveEmitter.emitSchemaApplications` already carries schema-level `@link` into the runtime build. Confirm both arms read the patched registry, so the directive, its definition and the `@composeDirective` application appear in both. The agreement is a test, not a belief.
- **Spiked against federation-jvm 6.2.0 (`Federation.transform` over SDL, then `_service { sdl }`).** A second `@link` to a non-federation URL, a schema-level `@composeDirective(name: "@nodeType")` with `"@composeDirective"` imported, and the namespaced `@federation__composeDirective(name: "@nodeType")` without that import all build, and all three print through unchanged, with the `@nodeType` definition and application kept. The library supplies the `@federation__composeDirective` definition itself, but only when `"@composeDirective"` is not imported: with it imported, the namespaced spelling fails with `tried to use an undeclared directive 'federation__composeDirective'`. Not yet shown: the same through the generator's own path (a programmatic schema, `Federation.transform(GraphQLSchema)`, `SchemaDirectiveRegistry.isSurvivor` filtering, then the runtime build via `AppliedDirectiveEmitter.emitSchemaApplications`); the agreement test below is where it is shown. Nor has Apollo composition itself been run over the result.
- **Open at pickup, composition behaviour.** What Apollo composition does when two subgraphs apply `@nodeType` with different `typeId`s to the same entity type (a non-repeatable composed directive). The spec URL should be a stable URL Graphitron owns; composition identifies the spec by it and does not fetch it. Choose a spec name other than `node`, so `@node` (generator-only) and the spec's namespace are never confused in an SDL reader's head.

## Tests

- Pipeline tier: a federated graph over `@node(typeId: "46")`, a type with the id published by its jOOQ class, and a type with neither. The assembled and printed SDL carries `@nodeType` with the effective id on each, the `directive @nodeType` definition, the node spec `@link` and the `@composeDirective` application. Two cases for its spelling: the author's federation `@link` imports `"@composeDirective"` (plain name) and does not (namespaced). Controls: a graph with no federation `@link`, and a federated graph with no node types, each byte-identical to today.
- Agreement: the generated `schema.graphqls` and the runtime `_service { sdl }` of the sakila federated example carry the same three pieces. The execution tier asserts on `_service { sdl }`, not on the print.
- Store: a pin on `graphitron_published_node_type` for the federated condition (authored link, synthesised link, no link) and for `compose_directive` (imported, imported under an alias, not imported, tag-synthesised), and a derivation pin that `graphitron_node.type_id` is what the directive carries for each of the three `type_id_origin` values.

## First-client docs draft

For `docs/manual/how-to/apollo-federation.adoc`, a new section after "The `<schemaInput tag>` flag", and a sentence on `node.adoc` and `global-id.adoc` saying the id can be published.

> **Publish typeIds to the supergraph.** A node subgraph that resolves `Query.node(id)` needs to know which type an ID belongs to. In a federated subgraph, every node type carries `@nodeType(typeId: "...")` in `_service { sdl }` and composition keeps it, so the supergraph holds the mapping from typeId to type name and the node subgraph decodes an ID without calling the owning subgraph. The value is the id Graphitron encodes into IDs for that type. There is nothing to configure: Graphitron adds the directive definition, the `@link` for it and the `@composeDirective` that lets it through composition.
>
> **Keep typeIds unique across subgraphs.** Composition checks neither that two types share a `typeId` nor that two subgraphs disagree about one. Pick the ids from one scheme for the whole supergraph. Within one subgraph the build already refuses a duplicate (`validateNodeTypeIdUniqueness`).

## Other solutions we've considered

- **Make `@node` itself survive** instead of a second directive. Rejected: `@node` also carries `keyColumns:` and `typeId:` is optional on it, so it would publish database column names and leave defaulted types unmapped. The applied `@nodeType` carries the one resolved fact consumers need.
- **An opt-in Maven setting** that adds the `@link`. Rejected: the synthesised `@key` already publishes a node-type fact in federated graphs with no flag, and the addition is a directive definition plus one application per node type, which a graph with no node subgraph in front of it ignores.
- **Editing the author's federation `@link` import list** to add `"@composeDirective"`. Rejected: Graphitron does not rewrite authored `@link`s (the `@tag` case refuses instead); the namespaced spelling needs no edit.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01Dm8pThf7s3WudRiDie9JaJ, 2026-10-08)

Question 1 (goal and viability) passes: the goal is clear, the minimal pair is real, and every symbol the spec names exists as named. Question 2 (fit) fails on one seam, which makes the plan's central claim, "answerable from the store alone", unreachable as written.

1. **The opt-in cannot be derived from `graphitron_link_entry`.** The plan gates `applyNodeTypeIds` on a "`graphitron_link_entry` [holding] the Graphitron node spec link importing `@nodeType`, found the way `graphitron_synthesized_federation_key` finds the federation link". Two parts of that do not hold in the tree.
   - `graphitron_link_entry` carries `url` and no import list, so "importing `@nodeType`" is not a predicate over it. The relation is also marked Deprecated in its own comment, with `graphitron_ast_link_entry` as the replacement; a new reader should say which of the two it reads.
   - The synthesised case is not in the relation at all. `graphitron_synthesized_federation_key` does not find a Maven-synthesised federation link in `graphitron_link_entry`; it ORs in `graphql_assembly_synthesised_link`, a row `GraphQLAssemblyCapture.writeSynthesisedLink` writes precisely "because no document states it" (the capture walks the corpus as written). The setting is how the spec's own default path (`<nodeTypeDirective>true`) adds the link, so on that path the store would see no `@nodeType` opt-in and the step would not run. "Exactly as `<schemaInput tag>` adds the federation link" is the precedent, and the precedent needed a captured fact of its own.
   
   What would satisfy this: name the fact the store holds for the opt-in and where it comes from. That is a relation (or a column on an existing one) with an owner, a grain sentence, and a mark and sweep, written by the assembly capture for the synthesised arm and by a decode of the authored `@link` (including its import list) for the hand-written arm, per the `adding-a-relation` discipline; or, if the author prefers, a stated reason the step may read the registry for this one question and what that does to the "answerable from the store alone" sentence and to the store/generator agreement the surrounding code protects. Either is the author's call; the plan has to pick one.

   *Response:* the setting is gone, so the opt-in question is replaced by a federation question the store can answer, and both halves are in the AST family. A new derived view `graphitron_published_node_type` is named in Implementation: one row per node type of a graph that publishes federation directives, reading `graphitron_ast_link_entry` (federation URL prefix) or `graphql_assembly_synthesised_link`, the same arms the synthesised key rule ORs, and `graphitron_ast_link_import_entry` for whether the author's link imports `"@composeDirective"` and under what name. Reading the AST relations also leaves the deprecated `graphitron_link_entry` alone.

2. **The setting does not reach `LoadingRewrites` the way the tag does.** The plan says the boolean is "carried through the run context the way `<schemaInput tag>` reaches `LoadingRewrites`". The tag rides `SchemaInput.tag()`, a per-input value `LoadingRewrites.apply(registry, inputs)` receives. A graph-level boolean is not on any `SchemaInput`. `LoadingRewrites.apply` has two callers that must agree, `AttributedRegistry.load` (from the run context) and `GraphQLAssemblyCapture.capture` (from `reading.inputs()`), and its class comment says they exist as one function so the generator and the store cannot assemble different schemas. State how the setting reaches both, and what `Outcome.Applied` carries back so the capture can record the synthesised link, in place of the tag-shaped `synthesisedLink` boolean.

   *Response:* retired with the setting. There is no `LoadingRewrites` change, no `NodeTypeLinkSynthesiser`, and no `Outcome.Applied` addition; the link, definition and applications are added to the emitted registry after capture.

3. **Source-name tolerance in capture.** `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME`'s javadoc records that capture's stamp lookup must name each generator-injected source to tolerate the miss. `NodeTypeLinkSynthesiser` injects a second such source (and the injected `directive @nodeType` definition has none), so the plan should say how those two reach capture without tripping that lookup.

   *Response:* does not arise for the same reason: nothing is injected into the corpus capture reads, so no generator-injected source name reaches its stamp lookup. The one injected schema extension and the injected definition live only in the emitted registry.

Verdict: request revisions. Status stays Spec. The other open items the spec already names (the federation-jvm second-`@link` spike, composition behaviour on conflicting ids) are correctly scoped as pickup-time work and are not blocking.
