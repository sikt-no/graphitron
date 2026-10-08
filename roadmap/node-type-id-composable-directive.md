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

An author of a federated graph who composes `@nodeType` gets every node type annotated with its wire `typeId` in the published schema, and the supergraph carries the mapping. A node subgraph (one that answers `Query.node(id)` by finding the `__typename` an ID belongs to) can then read the `typeId` to type-name mapping off the supergraph and decode the ID itself, instead of fanning `node(id) { __typename }` out to every owning subgraph with the caller's credentials. That fan-out breaks for subgraphs that authenticate on a header the node subgraph must not forward, and it makes each owning subgraph say whether an ID exists before field resolution has authorized the caller (GitHub issue #557).

The author's only act is the one federation already asks for to compose a custom directive: `@composeDirective(name: "@nodeType")` on the schema. That application is the opt-in, so there is no setting. Graphitron mints the rest, the `@nodeType` definition and one application per node type, as a macro expansion. Today `@node(typeId:)` is a build-time directive: the generator reads it and it never reaches the published schema. The minimal pair, from the sakila example's node types:

```graphql
# Author writes, once per subgraph
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key", "@composeDirective"])
  @composeDirective(name: "@nodeType")

type Customer implements Node @table(name: "customer") @node(typeId: "46") { ... }

# _service { sdl } today
type Customer implements Node @key(fields: "id") { ... }

# _service { sdl } after
directive @nodeType(typeId: String!) on OBJECT

type Customer implements Node @key(fields: "id") @nodeType(typeId: "46") { ... }
```

The value is the *effective* `typeId`, the one the generator encodes into IDs: the author's `@node(typeId:)` where written, else the id the jOOQ class publishes, else the type name. A type that never wrote `@node(typeId:)` gets the application too. A graph that does not compose `@nodeType` publishes exactly what it publishes today.

## Implementation

`@nodeType` is a macro, so it takes the shape the macro family already has and no new mechanism stands beside it. The family's rows (`graphitron_minted_*`) lead with the coining coordinate, carry precedence, and are read by `EmittedRegistry` through the anchors; the emitted registry already derives from them. Today that family mints object types, fields and arguments, and applies no directive, so this is the first macro to mint a directive definition and directive applications. Two things follow, and both are for the `adding-a-relation` skill at pickup.

- **The carrier is the author's `@composeDirective(name: "@nodeType")`.** It is the coining coordinate, one per graph. The fact that it was written is a corpus fact, captured, not read back out of the registry: add a decode of schema-level `@composeDirective` applications, in the manner of `graphitron_ast_link_entry` over `graphql_ast_schema_directive_entry`, carrying the `name` argument's value. Spelling does not matter to the question: the federation `@link` may import `"@composeDirective"` or the author may write `@federation__composeDirective`, and the decode asks only which directive names a graph composes. The question asked of it is "does this graph compose `@nodeType`".
- **What it mints** is one directive definition, `nodeType(typeId: String!) on OBJECT`, and one application on each node type carrying `graphitron_node.type_id`, the resolved id. Write them as new minted relations in the family's shape: source coordinate leading the key, the cascade from it, an index on the minted coordinate. The family's charter qualifies a macro as a local function of one carrier's own declaration and names the key synthesis as one that does not, because it conjoins the SDL with a second corpus. The id here can come from the jOOQ class (`JOOQ_METADATA`), so the mint reads `graphitron_node`, an upstream anchored relation, rather than the carrier alone. The charter text has to say that reading upstream anchored facts is admitted, or the macro does not qualify; that amendment is part of this item, and the reviewer may contest it.
- **Applying it.** `EmittedRegistry.of` already patches the emitted registry after capture. Add the minted definition and applications to the registry through the same anchors-then-patch route, on the family's precedence rules, so an author's own `@nodeType` declaration or application is a collision the family already knows how to say. `SchemaDirectiveRegistry` treats everything declared in Graphitron's own `directives.graphqls` as generator-only, so `@nodeType` must not be declared there; it is minted, and survives like any custom directive.
- **Author errors are refused as such, before assembly.** The family's conflict rows say when an authored `@nodeType` declaration or application collides with the mint; the refusal is an author-facing error naming it, not the generator-defect voice `GraphQLRewriteGenerator.emittedSchema` uses for an assembly failure of the emitted registry. Place it in the existing diagnostics at pickup.
- **Graphitron writes nothing federation-side.** No `@link`, no `@composeDirective`: the node spec `@link` is optional (spike below), and without the author's `@composeDirective` the family mints nothing and the schema is as today. Whether composing without the opt-in earns a warning is separate work.
- **The old emitter and the new one agree only by recording the difference.** `EmittedRegistryAgreementTest` sweeps the whole corpus and fails on any new disagreement between the walk's schema and the store-derived one. A corpus document composing `@nodeType` prints the definition and applications on the store side only, and the walk is not taught to emit them: a second producer of one population is what that gate exists to remove. Add the document and list it in `KNOWN_DISAGREEMENTS` with the reasoning that the store is the sole producer here by design. At pickup, also confirm nothing downstream of the walk's classified model needs them.
- **Spiked against federation-jvm 6.2.0 (`Federation.transform` over SDL, then `_service { sdl }`).** A schema-level `@composeDirective(name: "@nodeType")` with a locally defined `@nodeType` builds and prints through unchanged with no node spec `@link` at all, and with one. The namespaced `@federation__composeDirective` builds only when `"@composeDirective"` is not imported (with it imported the library reports `tried to use an undeclared directive 'federation__composeDirective'`), which is the author's own `@link` and why the decode must not care about spelling. Not run: the generator's own path (programmatic schema, `Federation.transform(GraphQLSchema)`), which the execution test below covers, and Apollo composition itself.
- **Open at pickup, composition behaviour.** What Apollo composition does when two subgraphs apply `@nodeType` with different `typeId`s to the same entity type (a non-repeatable composed directive). Without a node spec `@link` the directive is identified by name alone, so whether to recommend one in the docs is part of this question.

## Tests

- Pipeline tier: a graph composing `@nodeType` over `@node(typeId: "46")`, a type with the id published by its jOOQ class, and a type with neither. The assembled SDL carries the minted definition and `@nodeType` with the effective id on each. Controls: a graph that does not compose `@nodeType`, and one composing it with no node types, each byte-identical to today. The spelling cases: composed through an imported `@composeDirective` and through `@federation__composeDirective`. Refusals: an authored `@nodeType` application on a type, and an authored declaration of it.
- Agreement gate: the new corpus document in `KNOWN_DISAGREEMENTS`, and the set still equals what differs.
- Execution tier: the sakila federated example composes `@nodeType` and its `_service { sdl }` carries the definition and each application. This is also where the programmatic-schema path the spike did not run is shown.
- Store: pins on the decode (composed, not composed, both spellings) and on the mint for each of the three `type_id_origin` values.

## First-client docs draft

For `docs/manual/how-to/apollo-federation.adoc`, a new section after "The `<schemaInput tag>` flag", and a sentence on `node.adoc` and `global-id.adoc` saying the id can be published.

> **Publish typeIds to the supergraph.** A node subgraph that resolves `Query.node(id)` needs to know which type an ID belongs to. Compose the directive Graphitron mints for it, in every subgraph that should publish its ids:
>
> ```graphql
> extend schema
>   @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key", "@composeDirective"])
>   @composeDirective(name: "@nodeType")
> ```
>
> Graphitron then defines `@nodeType` and puts `@nodeType(typeId: "...")` on every node type, the value being the id it encodes into IDs for that type, and composition keeps it, so the supergraph holds the mapping from typeId to type name and the node subgraph decodes an ID without calling the owning subgraph. Do not write `@nodeType` yourself; the build refuses it.
>
> **Keep typeIds unique across subgraphs.** Composition checks neither that two types share a `typeId` nor that two subgraphs disagree about one. Pick the ids from one scheme for the whole supergraph. Within one subgraph the build already refuses a duplicate (`validateNodeTypeIdUniqueness`).

## Other solutions we've considered

- **Make `@node` itself survive** instead of a second directive. Rejected: `@node` also carries `keyColumns:` and `typeId:` is optional on it, so it would publish database column names and leave defaulted types unmapped. The applied `@nodeType` carries the one resolved fact consumers need.
- **Graphitron also injects the `@link` and the `@composeDirective`.** Rejected: the spelling of `@composeDirective` depends on the author's own federation `@link` import list (the spike shows the wrong spelling fails to build), so injecting it means reading and working around an authored `@link`, and it changes the published federation surface of every existing federated graph on upgrade. The author's `@composeDirective` is also the right opt-in: it is the line federation already asks for to compose a custom directive.
- **A Maven setting** that turns the feature on. Rejected for the same reason: the composed directive is the opt-in and lives where federation looks for it.
- **Derive it beside the federation key synthesis** as a one-off relation read by a dedicated `EmittedRegistry` step. Rejected: it is what the macro family is for, and a second shape for minting into the emitted registry is the parallel implementation to avoid.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01Dm8pThf7s3WudRiDie9JaJ, 2026-10-08)

Question 1 (goal and viability) passes: the goal is clear, the minimal pair is real, and every symbol the spec names exists as named. Question 2 (fit) fails on one seam, which makes the plan's central claim, "answerable from the store alone", unreachable as written.

1. **The opt-in cannot be derived from `graphitron_link_entry`.** The plan gates `applyNodeTypeIds` on a "`graphitron_link_entry` [holding] the Graphitron node spec link importing `@nodeType`, found the way `graphitron_synthesized_federation_key` finds the federation link". Two parts of that do not hold in the tree.
   - `graphitron_link_entry` carries `url` and no import list, so "importing `@nodeType`" is not a predicate over it. The relation is also marked Deprecated in its own comment, with `graphitron_ast_link_entry` as the replacement; a new reader should say which of the two it reads.
   - The synthesised case is not in the relation at all. `graphitron_synthesized_federation_key` does not find a Maven-synthesised federation link in `graphitron_link_entry`; it ORs in `graphql_assembly_synthesised_link`, a row `GraphQLAssemblyCapture.writeSynthesisedLink` writes precisely "because no document states it" (the capture walks the corpus as written). The setting is how the spec's own default path (`<nodeTypeDirective>true`) adds the link, so on that path the store would see no `@nodeType` opt-in and the step would not run. "Exactly as `<schemaInput tag>` adds the federation link" is the precedent, and the precedent needed a captured fact of its own.
   
   What would satisfy this: name the fact the store holds for the opt-in and where it comes from. That is a relation (or a column on an existing one) with an owner, a grain sentence, and a mark and sweep, written by the assembly capture for the synthesised arm and by a decode of the authored `@link` (including its import list) for the hand-written arm, per the `adding-a-relation` discipline; or, if the author prefers, a stated reason the step may read the registry for this one question and what that does to the "answerable from the store alone" sentence and to the store/generator agreement the surrounding code protects. Either is the author's call; the plan has to pick one.

   *Response:* the setting and the synthesised link are both gone. The opt-in is the author's own `@composeDirective(name: "@nodeType")`, captured as a decode of schema-level `@composeDirective` applications over `graphql_ast_schema_directive_entry`, which is a corpus fact with an owner, and the mint is a macro in the existing `graphitron_minted_*` family, so neither `graphitron_link_entry` nor `graphql_assembly_synthesised_link` is read.

2. **The setting does not reach `LoadingRewrites` the way the tag does.** The plan says the boolean is "carried through the run context the way `<schemaInput tag>` reaches `LoadingRewrites`". The tag rides `SchemaInput.tag()`, a per-input value `LoadingRewrites.apply(registry, inputs)` receives. A graph-level boolean is not on any `SchemaInput`. `LoadingRewrites.apply` has two callers that must agree, `AttributedRegistry.load` (from the run context) and `GraphQLAssemblyCapture.capture` (from `reading.inputs()`), and its class comment says they exist as one function so the generator and the store cannot assemble different schemas. State how the setting reaches both, and what `Outcome.Applied` carries back so the capture can record the synthesised link, in place of the tag-shaped `synthesisedLink` boolean.

   *Response:* retired with the setting. There is no `LoadingRewrites` change, no synthesiser, and no `Outcome.Applied` addition.

3. **Source-name tolerance in capture.** `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME`'s javadoc records that capture's stamp lookup must name each generator-injected source to tolerate the miss. `NodeTypeLinkSynthesiser` injects a second such source (and the injected `directive @nodeType` definition has none), so the plan should say how those two reach capture without tripping that lookup.

   *Response:* does not arise: nothing is injected into the corpus capture reads. The minted definition and applications live in the emitted registry only.

Verdict: request revisions. Status stays Spec. The other open items the spec already names (the federation-jvm second-`@link` spike, composition behaviour on conflicting ids) are correctly scoped as pickup-time work and are not blocking.
