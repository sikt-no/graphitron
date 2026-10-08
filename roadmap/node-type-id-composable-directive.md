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

The author's only act is the one federation already asks for to compose a custom directive: `@composeDirective(name: "@nodeType")` on the schema. That application is the opt-in, so there is no setting. Graphitron synthesises the rest, the `@nodeType` definition and one application per node type, the way it already synthesises `@key` on node types. Today `@node(typeId:)` is a build-time directive: the generator reads it and it never reaches the published schema. The minimal pair, from the sakila example's node types:

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

`@nodeType` is synthesised exactly the way the federation `@key` on a node type already is, so no new mechanism stands beside it. `@key` has three parts and `@nodeType` gets the same three. The macro family (`graphitron_minted_*`) is not the model: it mints types, fields and arguments and applies no directive, and its own charter names the key synthesis as the shape for a derived directive application over a second corpus, which is what the jOOQ-published `typeId` is.

- **The build-time synthesiser.** Add `NodeTypeIdSynthesiser` beside `KeyNodeSynthesiser` in `federation`, called from `AttributedRegistry.load` right after it, below the cut where `KeyNodeSynthesiser.apply` runs, so capture never sees its output and `AttributedRegistry.preSynthesisRegistry` is the handle `EmittedRegistry` starts from. For every node type (`NodeDeclaration.isNodeType`) it attaches `@nodeType(typeId: <effective id>)`, and it adds the definition `directive @nodeType(typeId: String!) on OBJECT` to the registry. The effective id is the one `graphitron_node.type_id` resolves: the author's `@node(typeId:)`, else what the jOOQ class publishes, else the type name; take it from the same function that fills that column so the two cannot differ. The definition's text lives once, in a constant both arms read.
- **The trigger is the author's `@composeDirective(name: "@nodeType")`**, the way `KeyNodeSynthesiser`'s is the federation `@link`: the orchestrator calls the synthesiser only when the registry's schema-level directives compose `@nodeType`. The check reads the raw registry (schema definition and extensions) for a `composeDirective` or `federation__composeDirective` application whose `name` is `"@nodeType"`. Spelling is the author's, and the spike below shows why it must not matter to us: the namespaced form builds only when `"@composeDirective"` is not imported. There is no setting. Graphitron writes nothing federation-side, no `@link` and no `@composeDirective`.
- **The store derivation.** Add `graphitron_synthesized_node_type_id` beside `graphitron_synthesized_federation_key`: one row per node type of a graph whose schema composes `@nodeType`, carrying `type_id` from `graphitron_node`, with rows that are their own provenance, exactly as the key view's are. Its condition needs the composed fact in the store, so capture gains a decode of schema-level `@composeDirective` applications over `graphql_ast_schema_directive_entry`, in the manner of `graphitron_ast_link_entry`, carrying the `name` argument's value. That is a corpus fact, captured, not read back out of the registry, and the decode ignores spelling. It reads upstream only (`graphitron_node` and the new decode). A view and not a table on the key view's own terms; settle that with the `adding-a-relation` skill at pickup, with the owner, grain sentence and `meta_relation` and `meta_gatherer_dependency` entries it requires.
- **Applying it.** `EmittedRegistry.of` gains an `applySynthesisedNodeTypeIds` step beside `applySynthesisedKeys`: it reads the view and adds `@nodeType(typeId: <row.type_id>)` to each row's type, and adds the definition from the shared constant, the name and id coming off the row and not restated at the site. A type already carrying the application is a generator defect, since the registry it patches is the pre-synthesis one, as the key step already treats it.
- **Author errors are refused as such, before assembly.** An authored `@nodeType` application on a node type, or an authored `@nodeType` declaration, collides with the synthesis. The refusal is an author-facing error naming it, not the generator-defect voice `GraphQLRewriteGenerator.emittedSchema` uses for an assembly failure of the emitted registry. Place it in the existing diagnostics at pickup. `@nodeType` must not be declared in Graphitron's own `directives.graphqls`, because `SchemaDirectiveRegistry` treats everything declared there as generator-only and would strip it.
- **The two producers agree, as they do for `@key`.** Because the walk side gains its synthesiser, `EmittedRegistryAgreementTest` sees the same schema from both and needs no `KNOWN_DISAGREEMENTS` entry; it needs a corpus document composing `@nodeType` so the gate covers it. This adds a producer on the walk side, which goes with `KeyNodeSynthesiser` when the walk does, and is what the key precedent costs.
- **Spiked against federation-jvm 6.2.0 (`Federation.transform` over SDL, then `_service { sdl }`).** A schema-level `@composeDirective(name: "@nodeType")` with a locally defined `@nodeType` builds and prints through unchanged, with no node spec `@link` and with one. The namespaced `@federation__composeDirective` builds only when `"@composeDirective"` is not imported (with it imported the library reports `tried to use an undeclared directive 'federation__composeDirective'`), which is the author's own `@link`. Not run: the generator's own path (programmatic schema, `Federation.transform(GraphQLSchema)`), which the execution test below covers, and Apollo composition itself.
- **Open at pickup, composition behaviour.** What Apollo composition does when two subgraphs apply `@nodeType` with different `typeId`s to the same entity type (a non-repeatable composed directive). Without a node spec `@link` the directive is identified by name alone, so whether to recommend one in the docs is part of this question.

## Tests

- Pipeline tier: a graph composing `@nodeType` over `@node(typeId: "46")`, a type with the id published by its jOOQ class, and a type with neither. The assembled SDL carries the definition and `@nodeType` with the effective id on each. Controls: a graph that does not compose `@nodeType`, and one composing it with no node types, each byte-identical to today. The spelling cases: composed through an imported `@composeDirective` and through `@federation__composeDirective`. Refusals: an authored `@nodeType` application on a type, and an authored declaration of it.
- Agreement gate: a corpus document composing `@nodeType`, and `KNOWN_DISAGREEMENTS` unchanged.
- Execution tier: the sakila federated example composes `@nodeType` and its `_service { sdl }` carries the definition and each application. This is also where the programmatic-schema path the spike did not run is shown.
- Store: pins on the decode (composed, not composed, both spellings) and on the view for each of the three `type_id_origin` values.

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
- **A new minted type in the macro family.** Rejected: the family mints types, fields and arguments only, and a derived directive application over the jOOQ-published id is the case its charter assigns to the key synthesis's shape.
- **Store side only, with the walk left alone and a `KNOWN_DISAGREEMENTS` entry.** Not taken: it is the lighter change, but it departs from how `@key` is done, and the walk-side producer it would skip is already scheduled to go with the key synthesiser.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01Dm8pThf7s3WudRiDie9JaJ, 2026-10-08)

Question 1 (goal and viability) passes: the goal is clear, the minimal pair is real, and every symbol the spec names exists as named. Question 2 (fit) fails on one seam, which makes the plan's central claim, "answerable from the store alone", unreachable as written.

1. **The opt-in cannot be derived from `graphitron_link_entry`.** The plan gates `applyNodeTypeIds` on a "`graphitron_link_entry` [holding] the Graphitron node spec link importing `@nodeType`, found the way `graphitron_synthesized_federation_key` finds the federation link". Two parts of that do not hold in the tree.
   - `graphitron_link_entry` carries `url` and no import list, so "importing `@nodeType`" is not a predicate over it. The relation is also marked Deprecated in its own comment, with `graphitron_ast_link_entry` as the replacement; a new reader should say which of the two it reads.
   - The synthesised case is not in the relation at all. `graphitron_synthesized_federation_key` does not find a Maven-synthesised federation link in `graphitron_link_entry`; it ORs in `graphql_assembly_synthesised_link`, a row `GraphQLAssemblyCapture.writeSynthesisedLink` writes precisely "because no document states it" (the capture walks the corpus as written). The setting is how the spec's own default path (`<nodeTypeDirective>true`) adds the link, so on that path the store would see no `@nodeType` opt-in and the step would not run. "Exactly as `<schemaInput tag>` adds the federation link" is the precedent, and the precedent needed a captured fact of its own.
   
   What would satisfy this: name the fact the store holds for the opt-in and where it comes from. That is a relation (or a column on an existing one) with an owner, a grain sentence, and a mark and sweep, written by the assembly capture for the synthesised arm and by a decode of the authored `@link` (including its import list) for the hand-written arm, per the `adding-a-relation` discipline; or, if the author prefers, a stated reason the step may read the registry for this one question and what that does to the "answerable from the store alone" sentence and to the store/generator agreement the surrounding code protects. Either is the author's call; the plan has to pick one.

   *Response:* the setting and the synthesised link are both gone. The opt-in is the author's own `@composeDirective(name: "@nodeType")`, captured as a decode of schema-level `@composeDirective` applications over `graphql_ast_schema_directive_entry`, a corpus fact with an owner. The view `graphitron_synthesized_node_type_id` reads that decode and `graphitron_node`, so neither `graphitron_link_entry` nor `graphql_assembly_synthesised_link` is read.

2. **The setting does not reach `LoadingRewrites` the way the tag does.** The plan says the boolean is "carried through the run context the way `<schemaInput tag>` reaches `LoadingRewrites`". The tag rides `SchemaInput.tag()`, a per-input value `LoadingRewrites.apply(registry, inputs)` receives. A graph-level boolean is not on any `SchemaInput`. `LoadingRewrites.apply` has two callers that must agree, `AttributedRegistry.load` (from the run context) and `GraphQLAssemblyCapture.capture` (from `reading.inputs()`), and its class comment says they exist as one function so the generator and the store cannot assemble different schemas. State how the setting reaches both, and what `Outcome.Applied` carries back so the capture can record the synthesised link, in place of the tag-shaped `synthesisedLink` boolean.

   *Response:* retired with the setting. The build-time synthesiser runs in `AttributedRegistry.load` beside `KeyNodeSynthesiser`, not in `LoadingRewrites`, so there is no `LoadingRewrites` change and no `Outcome.Applied` addition.

3. **Source-name tolerance in capture.** `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME`'s javadoc records that capture's stamp lookup must name each generator-injected source to tolerate the miss. `NodeTypeLinkSynthesiser` injects a second such source (and the injected `directive @nodeType` definition has none), so the plan should say how those two reach capture without tripping that lookup.

   *Response:* does not arise: the synthesiser runs below the cut, so nothing it injects is in the corpus capture reads.

Verdict: request revisions. Status stays Spec. The other open items the spec already names (the federation-jvm second-`@link` spike, composition behaviour on conflicting ids) are correctly scoped as pickup-time work and are not blocking.

### Round 2: Spec → Ready, request revisions (session_01Dm8pThf7s3WudRiDie9JaJ, 2026-10-08)

Round 1 is resolved: the setting, the synthesised link and the `LoadingRewrites` change are gone, and the opt-in as a captured decode of the author's `@composeDirective` is a sound corpus fact. Question 1 passes: the goal, the minimal pair and the first-client docs read simply, and the symbols the revision names exist (`graphql_ast_schema_directive_entry`, `graphitron_ast_link_entry`, `EmittedRegistryAgreementTest.KNOWN_DISAGREEMENTS`, `GraphQLRewriteGenerator.emittedSchema`). Question 2 fails: the plan places the mint in the macro family on the strength of a shape that family does not have, and the nearest precedent in the tree points the other way.

1. **The macro family mints elements, and a directive definition or application is not one.** The plan says the mint is "new minted relations in the family's shape: source coordinate leading the key, the cascade from it, an index on the minted coordinate", read by `EmittedRegistry` "through the anchors", with collisions the family "already knows how to say". In the tree the family is `graphitron_type_minted`, `graphitron_field_minted` and `graphitron_argument_minted` (views over per-macro candidate views, not `graphitron_minted_*`; only `graphitron_minted_coinage` and `graphitron_minted_conflict` carry that prefix), each feeding `graphitron_element_minted_*` and so `graphitron_element`, whose `element_kind` CHECK admits `NAMED_TYPE`, `FIELD`, `INPUT_FIELD` and `FIELD_ARGUMENT`. `graphitron_minted_conflict.element_kind` is limited to three of those. There is no element kind for a directive definition or an applied directive, and the anchors `EmittedRegistry` reads carry no applied directives at all: its own javadoc says directives are the one thing they do not carry, and `applyInheritedTags` states its fold "goes when the emitted population carries its own applied directives", which is a future state. So the "anchors-then-patch route" for applications does not exist, and "collision the family already knows how to say" does not follow for an authored `@nodeType` on a type or a declaration of it. The plan names this as "the first macro to mint a directive definition and directive applications" and then budgets it as an instance of the existing shape. It is a new element kind (or a new anchor for definitions and applications) plus changes to `graphitron_element`, the conflict relation and `EmittedRegistry`'s patch, and the plan has to say which.

   *Response:* accepted, and the macro framing is withdrawn. The plan no longer places the mint in the family; there is no new element kind, anchor, or change to `graphitron_element`, the conflict relation or the anchors' scope. Implementation now says why: the family mints types, fields and arguments only and applies no directive.

2. **The charter amendment reverses a precedent the plan has not answered.** The plan concedes the mint reads `graphitron_node`, whose `type_id` can come from the jOOQ class (`JOOQ_METADATA`), so it is not "a local function of one carrier's own declaration". The macro-provenance header in `graphitron-model.sql` names exactly this case: federation's key synthesis "conjoins the SDL claim with metadata a generated jOOQ class publishes, a second corpus, so it is a derivation (`graphitron_synthesized_federation_key`) whose rows are their own provenance". `@nodeType` is the same kind of fact, applied the same way (a directive added to an authored node type, which is what `EmittedRegistry.applySynthesisedKeys` already does by reading a derivation). The alternative the plan rejects, "a one-off relation read by a dedicated `EmittedRegistry` step", is that precedent, and its rejection ("it is what the macro family is for") contradicts the family's own written charter rather than answering it. I contest the amendment as the plan frames it. What would satisfy this: either justify, against the key-synthesis precedent, why this fact belongs in the macro family when the same charter excludes the one that matches it, including what the amendment does to the stated qualification rule; or switch to the derivation shape and say how the directive definition, which key synthesis never needed because federation-jvm supplies `@key`'s, reaches the emitted registry. The choice is the author's; the plan has to pick one and answer the other.

   *Response:* took the derivation shape, the second arm of the choice. `@nodeType` follows `@key` in all three parts: `NodeTypeIdSynthesiser` beside `KeyNodeSynthesiser` in `AttributedRegistry.load`, the view `graphitron_synthesized_node_type_id` beside `graphitron_synthesized_federation_key`, and an `applySynthesisedNodeTypeIds` step beside `applySynthesisedKeys`. No charter amendment is needed. The definition, which key synthesis never needed because federation-jvm supplies `@key`'s, reaches both producers from one shared constant: the build-time synthesiser adds it to the registry, and the `EmittedRegistry` step adds it to the emitted registry, so the two agree and `EmittedRegistryAgreementTest` needs no `KNOWN_DISAGREEMENTS` entry. The rejected alternative this finding names is now the plan.

3. **What is left is composed with what is claimed.** Whichever arm is chosen, the Implementation section's "Applying it" and "Author errors are refused as such" bullets (and the `KNOWN_DISAGREEMENTS` entry) hang off finding 1's answer, so the plan should state the arm before the tests are judged. The tests themselves are well aimed.

   *Response:* the arm is stated first in Implementation (derivation shape, three parts), and the applying, author-error and agreement bullets now hang off it. The `KNOWN_DISAGREEMENTS` entry is gone, with the lighter store-only variant recorded under the alternatives.

Verdict: request revisions. Status stays Spec. Not blocking: the composition behaviour on conflicting ids and the generator-path spike are correctly deferred to pickup.

### Round 3: Spec → Ready, request revisions (session_01Dm8pThf7s3WudRiDie9JaJ, 2026-10-08)

Round 2 is resolved. Following `@key`'s three parts (build-time synthesiser below the `AttributedRegistry.load` cut, a derivation view beside `graphitron_synthesized_federation_key`, an `EmittedRegistry` step beside `applySynthesisedKeys`) is the right fit, the macro-family detour is gone, and the symbols the revision names exist (`KeyNodeSynthesiser`, `NodeDeclaration.isNodeType`, `graphitron_node_type`, `graphql_ast_schema_directive_entry`). Question 1 passes. Question 2 is down to two claims about the synthesiser that do not hold in the tree, and both bear on the plan's own "the two producers agree" test.

1. **There is no function that fills `graphitron_node.type_id` for the synthesiser to call.** The plan says the synthesiser takes the effective id "from the same function that fills that column so the two cannot differ". The column is filled by `derive.Nodes.derive`, a SQL derivation (three `unionAll` arms ranked by precedence) run by the gatherer after capture, over `graphitron_node_entry`, `sql_node_metadata` and `graphitron_tabletype`. `NodeTypeIdSynthesiser` runs inside `AttributedRegistry.load`, before capture, on the raw registry and a `JooqCatalog`, so it cannot read that table and has no Java function to share. The generator's classifier resolves the id a third way, off `NodeIndex`/`TypeBuilder`. The three-tier rule (`@node(typeId:)`, else the class's `__NODE_TYPE_ID` with its defect exclusion, else the type name) would therefore be written a second time on the walk side, and "cannot differ" becomes a thing only `EmittedRegistryAgreementTest` can catch. State how the synthesiser gets the id (which existing reader of the catalog and the SDL, `JooqCatalog.nodeIdMetadata` and the `@node` argument, it composes, and whether the defect-carrying-table exclusion in `Nodes.derive` is mirrored) and drop or reword the "cannot differ" guarantee to what the design actually provides.

2. **The walk's node set and the view's node set differ, so the two producers do not agree "as they do for `@key`".** The synthesiser attaches to `NodeDeclaration.isNodeType`, which is true for any type carrying `@node`, with or without `@table`. The new view reads `graphitron_node`, whose key is `graphitron_tabletype`, so a `@node` on a type with no `@table` has no row there (its own comment says so). `@key`'s view avoids this by driving from `graphitron_node_type`, the union of the declared and resolved populations, whose comment records exactly this declaration-level shape; the new view cannot, because `type_id` exists only on the resolved side. A type in that shape would get `@nodeType` from the walk and none from the store, which is a disagreement the plan's "`KNOWN_DISAGREEMENTS` unchanged" does not allow for. Say what happens to that shape: the classifier rejecting it before emission is an answer, but the plan has to state it and show the synthesiser and the view cannot reach it, or else say the synthesiser restricts itself to the table-bound population.

Verdict: request revisions. Status stays Spec. Both findings are about what the synthesiser computes and over which population; neither reopens the architecture. Not blocking: whether the store's own assembly (which judges the corpus as written, without synthesis) tolerates a `@composeDirective(name: "@nodeType")` whose directive it has not yet been given is worth one line at pickup, since the spike defined `@nodeType` locally.
