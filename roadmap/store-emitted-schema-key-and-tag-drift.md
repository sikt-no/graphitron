---
id: R991
title: "The schema emitted from the store doubles synthesised @key and tags an author-declared PageInfo"
status: In Progress
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-06
last-updated: 2026-10-06
---

# The schema emitted from the store doubles synthesised @key and tags an author-declared PageInfo

## Goal

A federated subgraph's published schema says exactly what its author wrote plus graphitron's synthesis, once. Today, since the generator began emitting the schema from the fact store (the database of facts graphitron captures about a schema, `GraphQLRewriteGenerator.emittedSchema`), two things go wrong, reported in GitHub issue #555 against 10.0.0-RC40. A `@node` type with no authored `@key` gets the synthesised key twice, and an author-declared `PageInfo` picks up the `@tag` of every `@asConnection` field in the schema, so a type in a `stable` feature file is published as `experimental` and Apollo contract variants filtered on `stable` fail their downstream check. The reporter is pinned to RC39 by the second. When this lands both come out as they did on RC39, and a graph federated only through `<schemaInput tag>` keeps the one synthesised `@key` it gets today. Which graphs count as federated does not change.

The sakila example's own federated fixture shows the first today (`graphitron-sakila-example/src/main/resources/graphql/federated-schema.graphqls`):

```graphql
type Customer implements Node @table(name: "customer") @node { id: ID! @nodeId ... }
```

emits

```graphql
type Customer implements Node @key(fields : "id", resolvable : true) @key(fields : "id", resolvable : true) {
```

where it owes one `@key`. The reporter's schema shows the second. `features/stable/_root.graphqls`, configured with `<schemaInput tag>stable`, declares

```graphql
type PageInfo @shareable { hasPreviousPage: Boolean! hasNextPage: Boolean! startCursor: String endCursor: String }
```

and an `experimental`-tagged file holds an `@asConnection` field; the emitted `PageInfo` reads `type PageInfo @shareable @tag(name : "experimental")`, where it owes `type PageInfo @shareable`. An author-declared type takes no type-level tag from graphitron; only types graphitron itself mints (the connection, edge and page-info types an `@asConnection` field expands into when the author did not declare them) inherit the carrier field's tags.

## Causes

The two reported defects are in `EmittedRegistry` (graphitron-model), the class that builds the emitted registry from the registry capture transcribed plus the store's rows for what synthesis added; the third cause below sits between the store and the generator and is what the first fix uncovers. The two reported defects were reproduced on trunk by driving `GraphQLRewriteGenerator.generate()` over a captured store, and each disappears under the fix below with the other left in place.

**Doubled `@key`.** `GraphQLRewriteGenerator.emittedSchema` passes `attributed.registry()` to `EmittedRegistry.of`. That handle is post-synthesis: `KeyNodeSynthesiser` has already rewritten the node types in place to carry `@key(fields: "id", resolvable: true)`. `EmittedRegistry.of` documents its argument as the pre-synthesis registry, and `applySynthesisedKeys` appends every row of `graphitron_synthesized_federation_key` without looking, on the stated ground that those rows are disjoint from the registry's applications by construction. Handed the post-synthesis registry, they are not. Every test path (`TestSchemaHelper.deriveEmittedSchema`, `EmittedRegistryAgreementTest`, `EmittedRegistryTest`) hands it the capture's pre-synthesis registry, which is why production is the only caller that ever saw this.

**Two federation opt-in predicates, masked by the doubled key.** Fixing the doubled key makes the store the only source of a synthesised `@key`, and the store and the generator disagree about which graphs are federated. The generator synthesises whenever `LoadingRewrites` injected federation names (`AttributedRegistry.load`, `if (!injectedNames.isEmpty())`), and a federation `@link` reaches the registry two ways: the author writes one, or `<schemaInput tag>` is configured and `TagLinkSynthesiser` adds `extend schema @link(url: FederationSpec.URL, import: ["@tag"])`. `graphitron_synthesized_federation_key` sees only the first: its opt-in is a `graphitron_link_entry` row with a federation url, and that relation is decoded from `AssemblyReading.merged()`, the corpus as written, which no configuration rewrite reaches. A graph federated only through a configured tag therefore has no rows in the relation. Today the post-synthesis registry supplies its one key and hides the gap; a fix to the first defect alone would ship every node type in such a graph without its entity key.

There is a second, narrower split inside the tag arm. `TagLinkSynthesiser` asks whether any *matched* input carries a tag (`bySource`, the expansion's matches), while what the store holds about tags is the configured recipe (`store_graph_schema_input.tag`, one row per binding, matched or not). They differ when a tagged pattern matches no file, which the recipe tolerates as a warning while another entry matched (`SchemaRecipe.Expansion.Resolved.emptyPatterns`). So the store cannot answer the generator's question from the recipe rows; it has to record the generator's answer. And it can: `GraphQLAssemblyCapture` already runs `LoadingRewrites.apply`, `TagLinkSynthesiser` included, over the same inputs the generator does, and so already knows whether the composition gained the synthesised link. It records nothing about it.

**Tag on a declared `PageInfo`.** `applyInheritedTags` reads `graphitron_minted_coinage` (which carrier coordinate coined which minted type name) and stamps the coining field's `@tag`s onto each named type it finds in the registry. Capture writes a coinage row for `PageInfo` for every `@asConnection` carrier whether or not the mint wins, so the fold reaches an author-declared `PageInfo` and tags it. The store already knows the mint lost: `graphitron_type_minted` is "a type macro expansion adds, filling a name no author took", filtered against `graphql_type_element` and `graphitron_minted_conflict`, and the precedence comment in `graphitron-model.sql` names `PageInfo` as the mint that yields to an author. The fold just does not ask it. The walk (`ConnectionPromoter.registerPageInfo`) registers a declared `PageInfo` verbatim and untagged, which is the RC39 behaviour.

## Implementation

- **`EmittedRegistry`**: the public entry becomes `of(AttributedRegistry attributed, StoreHandle store)`, reading `attributed.preSynthesisRegistry()` itself; the registry-taking form becomes private. The choice of handle then lives with the class that states the contract, and production and tests reach the derivation through one input shape. A caller with no synthesis wraps its registry with the existing `new AttributedRegistry(registry, injectedNames)` constructor, whose two handles are the same object, which is honest for a registry nothing synthesised over.
- **`EmittedRegistry.applyInheritedTags`**: the coinage query semi-joins `graphitron_type_minted` on `(graph_name, type_name)`, so a tag is inherited only by a type the store says was minted. No Java-side "absent from the registry" set: that would restate the author-wins rule a second time, which the class javadoc already rejects for precedence, and would make the answer depend on which registry was passed, the first defect's failure mode. Give the method javadoc of its own saying which types inherit and why, moving the prose that now dangles above `tagDirective`'s javadoc onto it.
- **`EmittedRegistry.applySynthesisedKeys`**: becomes the enforcer of its own disjointness claim. If the type already carries a `@key` whose `fields:` decodes (through `FederationKeyFieldsParser`, as `KeyNodeSynthesiser.hasIdKeyDirective` does) to the row's field set, throw `IllegalStateException` naming the type and saying this is a generator defect (synthesis applied twice). Not a silent skip: a skip would mask a wrong input rather than report it.
- **`LoadingRewrites.Outcome.Applied`** carries whether `TagLinkSynthesiser` added the federation `@link`, a boolean component beside `injectedNames`. `TagLinkSynthesiser`'s predicate does not move: it stays the one place the tag arm of the opt-in is decided.
- **New relation `graphql_assembly_synthesised_link (graph_name)`**, owned by the `graphql-assembly` gatherer (`GraphQLAssemblyCapture`), one row per graph whose composition gained the tag-synthesised federation `@link`, presence being the fact. Grain: "one graph whose composed schema carries the federation `@link` that `<schemaInput tag>` synthesised, which its author did not write". Written from the `Applied` outcome; nothing when the composition refused, the assembly then judging the merged corpus. Marked and swept on the same per-graph terms the gatherer already keeps `graphql_schema_problem` on, so a graph that stops configuring a tag, or starts writing its own link, loses the row. `meta_relation` registration and `RelationRegistrationGateTest` arm as for its sibling. The DDL comment says why a configuration-varying row is admissible on the cross-graph read surface: it states a property of the schema the graph publishes, which is what that surface's "SDL-derived" rule protects, and the assembly's rows already vary with the configuration for the same reason (the `('graphql-assembly', 'configuration')` corpus row).
- **`graphitron_synthesized_federation_key`**: the federation opt-in becomes "a `graphitron_link_entry` row with a federation url, or a `graphql_assembly_synthesised_link` row". Authored and synthesised provenance stay in separate relations and the view joins them. The prefix literal stays, so `FederationLinkPrefixPinTest` is untouched. Declare the read edge `('graphitron', 'graphql-assembly')` in `meta_gatherer_dependency`; it points upstream, `ModelCapture` running the assembly capture before the graphitron decode. Revise the view's comment to name both arms.
- **`GraphQLRewriteGenerator.emittedSchema`**: calls `EmittedRegistry.of(attributed, store)`.
- **Call sites**: `TestSchemaHelper.deriveEmittedSchema`, `EmittedRegistryAgreementTest` and `EmittedRegistryTest` move to the `AttributedRegistry` entry.
- **`AttributedRegistry` class javadoc**: the paragraph on `preSynthesisRegistry` says "The store does not take this handle". That is true of capture, which composes its own registry through `LoadingRewrites`, and not of emission, which patches this handle. Reword it to separate the two. `TagApplier` and `DescriptionNoteApplier` run above the pre-synthesis cut, so `<schemaInput tag>` tags on carrier fields and appended notes are still present on the handle emission now reads.

DDL: one new relation, one dependency edge and the view's widened predicate; the two reported defects need none, reading facts the store already holds. No user-facing docs change; the emitted schema returns to what the user manual already describes.

## Tests

- **Pipeline tier, `graphitron`** (the test that closes the production/test split): drive `GraphQLRewriteGenerator.generate()` over `GraphitronStore.captured(ctx)` with two tagged `SchemaInput`s, the reporter's shape. A `stable` file holds a federation `@link`, a declared `PageInfo @shareable` and a `@node @table` type with no `@key`; an `experimental` file holds an `@asConnection` field. Parse the emitted `schema.graphqls` and assert per type: the node type carries exactly one `@key(fields: "id")`; `PageInfo` carries `@shareable` and no type-level `@tag` (its fields carry `stable` from `TagApplier`, which is correct); the minted connection and edge types carry `@tag(name: "experimental")`. A second case without the declared `PageInfo` asserts the minted `PageInfo` does inherit `experimental`, so the fix is shown not to have switched inheritance off. Home: beside `ConnectionFederationTagPipelineTest`, whose existing `<schemaInput tag>` cases go through `GraphitronSchemaBuilder.buildBundle` (the walk) and so never reached the store path.
- **Pipeline tier, `graphitron`, tag-only federation** (the reviewer's case): same harness, one `stable`-tagged `SchemaInput`, no authored `@link`, a `@node @table` type with no `@key`. Assert the emitted node type carries exactly one `@key(fields: "id")`. Without the view's second arm this case emits none, so it pins the arm through production's path.
- **`FederationKeyDerivationTest`, `graphitron`**: a capture configured with a tag and no authored `@link` yields a `graphql_assembly_synthesised_link` row and a `graphitron_synthesized_federation_key` row for the node type; the same corpus untagged and unlinked yields neither; the same corpus tagged and with an authored federation `@link` importing `@tag` yields the key row and no synthesised-link row.
- **Agreement, `graphitron`**: over the tagged and untagged pipeline fixtures, the store has a `graphql_assembly_synthesised_link` row exactly when `LoadingRewrites.apply` over the run's own `ctx.schemaInputs()` (what `AttributedRegistry.load` hands it) reports the synthesised link. One function decides both, so this pins that capture and the generator hand it the same inputs.
- **`EmittedRegistryTest`, `graphitron-model`**: a declared `PageInfo` with an explicitly `@tag`ged `@asConnection` carrier emits `PageInfo` untagged and the minted connection tagged; and an `AttributedRegistry` whose pre-synthesis handle already carries the synthesised `@key` makes `of` throw, pinning the enforcer.
- **`SchemaSdlEmissionTest`, `graphitron-sakila-example`**: over the federated SDL, every object type carries each distinct `@key` (by `fields:` and `resolvable:`) at most once. Stated as the property rather than as a count for `Customer`, `Address` and `Film`, which carry duplicates today.
- `EmittedRegistryAgreementTest` stays green, with `KNOWN_DISAGREEMENTS` unchanged.

## Other solutions we've considered

- **One-line call-site fix** (`attributed.preSynthesisRegistry()` at `emittedSchema`) plus javadoc. It fixes the reported symptom, and a scratch build of it does, but it leaves the registry-taking entry public, so the next caller can pass the wrong handle and every test keeps reaching the derivation through a different input from production. The overload costs eight mechanical test call sites.
- **Make `applySynthesisedKeys` idempotent.** It hides a wrong input instead of reporting it; the enforcer above is the same check with the opposite consequence.
- **Stop capture writing coinage for a mint that stands down.** Coinage is a true fact, which application would have minted what, and the family comment argues for writing it unconditionally. The stand-down is already a fact in `graphitron_type_minted`; the reader should join to it.
- **Federation opt-in from the composed `@link`**: decode the tag-synthesised link into `graphitron_link_entry`. That relation is the corpus as written and is deprecated in favour of `graphitron_ast_link_entry`, written from documents; a generator-produced row in it would make "as written" false.
- **Opt-in from the configuration rows**: a second view arm over `store_graph_schema_input.tag IS NOT NULL`. The `graphitron` gatherer has no read edge to configuration, and the recipe rows are not the generator's predicate (a tagged pattern that matched nothing). Closing that gap by moving `TagLinkSynthesiser` onto the recipe would change which graphs are federated, against the goal, and would state one rule twice, in Java over the recipe and in SQL over its rows, with nothing holding them together. Whether federation opt-in should be configuration-shaped (so a graph does not flip on the day the first file lands in a tagged directory) is a fair product question for its own item.
- **Opt-in from the sentinel source**: read a `store_source` row named `TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME`. Capture tolerates that row; nothing guarantees it, and a sentinel string is not a fact anyone states.

## Reviewer findings

### Round 1 (2026-10-06, Spec -> Ready, reviewer session 01QCg7kU9TcNVpYovihqLJ7E)

Verdict: withhold. One blocking finding on question one (viability). The goal reads cleanly on its
own: a federated subgraph's published SDL stops carrying a second synthesised `@key` on `@node`
types and stops stamping carrier tags onto an author-declared `PageInfo`, which unblocks contract
variants filtered on a tag. Both diagnoses hold against the tree, the doubled key is visible in the
built sakila federated `schema.graphqls` today, and the tag fix (semi-join coinage to
`graphitron_type_minted`) fits the store's existing author-wins fact rather than restating it. The
blocker is the key fix: switching emission to the pre-synthesis handle makes the store the only
source of a synthesised `@key`, and the store does not hold one for every build that gets one today.

**Finding 1 (question one: viability). A build that opts into federation only through
`<schemaInput tag>` loses its synthesised `@key` entirely under this plan.**

`KeyNodeSynthesiser` runs whenever `LoadingRewrites` injected anything
(`AttributedRegistry.load`, `if (!injectedNames.isEmpty())`). With `<schemaInput tag>` configured
and no author-written federation `@link`, `TagLinkSynthesiser` adds
`extend schema @link(url: FederationSpec.URL, import: ["@tag"])`, `FederationLinkApplier` injects
from it, and synthesis fires. `ConnectionFederationTagPipelineTest` already relies on that link
("<schemaInput tag> synthesises the federation @link, so emission takes the federation arm").

The store's rule does not see that link. `graphitron_synthesized_federation_key` requires a
`graphitron_link_entry` row whose url is the federation spec, and that relation is written by the
decode in `ModelCapture`, which walks `GraphQLAssemblyCapture.AssemblyReading.merged()`: "the
corpus as written ... neither the tag configuration nor the federation library reaches it." So for a
tag-only graph the relation has no rows. Today such a build emits exactly one `@key` per node type,
the one `KeyNodeSynthesiser` wrote into `attributed.registry()`; under the plan, `of` reads
`preSynthesisRegistry()`, `applySynthesisedKeys` finds nothing to apply, and every node type ships
without its entity key. That is a regression against RC39 and against the goal's own "both come out
as they did on RC39", and none of the planned tests would catch it: the reporter's shape and the
sakila federated fixture both carry an authored federation `@link`, and no sakila execution
configures `<schemaInput tag>`.

The two producers disagree about the federation opt-in predicate (the generator: anything injected,
tag-synthesised link included; the store: an authored federation `@link`), and the post-synthesis
handle currently masks that. What would satisfy this: the plan states how a tag-only build keeps its
key once emission reads the pre-synthesis handle, which means deciding where the opt-in fact comes
from (for example, the store capturing the configured-tag opt-in as a fact the relation's opt-in arm
reads, or some other arm the author prefers), and the Tests section adds a tag-only `@node` case
(no authored `@link`, one tagged `SchemaInput`) asserting exactly one `@key(fields: "id")` on the
emitted node type. If the author concludes tag-only builds should not get a synthesised key, that is
a behaviour change against RC39 and the goal needs to say so.

Corrected in passing: the "Other solutions" count of mechanical test call sites is eight, not seven
(six in `EmittedRegistryTest`, one each in `TestSchemaHelper` and `EmittedRegistryAgreementTest`).

Non-blocking: `applyInheritedTags` has no javadoc of its own today; its prose sits as a dangling
comment above `tagDirective`'s javadoc. The planned javadoc revision is the natural place to move it
onto the method. And the pipeline test's "PageInfo carries no `@tag`" should be read as type-level:
`TagApplier` tags the declared `PageInfo`'s fields with `stable` and never the type itself.

### Round 2 (2026-10-06, Spec -> Ready, reviewer session 01QQpyuwJ5Bx7ke5Aew8HUf5)

Verdict: sign off. The round-1 finding is resolved: the assembly capture already runs
`LoadingRewrites.apply` over the same recipe-expanded inputs the generator's
`AttributedRegistry.load` hands it, so recording the `TagLinkSynthesiser` outcome there and reading
it as a second opt-in arm of `graphitron_synthesized_federation_key` keeps a tag-only graph's one
`@key` without moving the predicate. The `('graphitron', 'graphql-assembly')` edge points upstream
(`ModelCapture` runs the assembly before the decode), and the tag-only pipeline case pins the arm on
production's path.

Non-blocking: the `graphql_` family charter (`meta_family`) says the family is a transcription with
exactly one non-transcription resident, `graphql_schema_problem`. `graphql_assembly_synthesised_link`
is a second one: a fact about the composition the charter already counts as "the toolchain this
graph is built with". When the DDL lands, amend that sentence so the charter keeps describing the
family.
