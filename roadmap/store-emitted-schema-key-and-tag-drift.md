---
id: R991
title: "The schema emitted from the store doubles synthesised @key and tags an author-declared PageInfo"
status: Spec
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-06
last-updated: 2026-10-06
---

# The schema emitted from the store doubles synthesised @key and tags an author-declared PageInfo

## Goal

A federated subgraph's published schema says exactly what its author wrote plus graphitron's synthesis, once. Today, since the generator began emitting the schema from the fact store (the database of facts graphitron captures about a schema, `GraphQLRewriteGenerator.emittedSchema`), two things go wrong, reported in GitHub issue #555 against 10.0.0-RC40. A `@node` type with no authored `@key` gets the synthesised key twice, and an author-declared `PageInfo` picks up the `@tag` of every `@asConnection` field in the schema, so a type in a `stable` feature file is published as `experimental` and Apollo contract variants filtered on `stable` fail their downstream check. The reporter is pinned to RC39 by the second. When this lands both come out as they did on RC39.

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

Both are in `EmittedRegistry` (graphitron-model), the class that builds the emitted registry from the registry capture transcribed plus the store's rows for what synthesis added. Both were reproduced on trunk by driving `GraphQLRewriteGenerator.generate()` over a captured store, and each disappears under the fix below with the other left in place.

**Doubled `@key`.** `GraphQLRewriteGenerator.emittedSchema` passes `attributed.registry()` to `EmittedRegistry.of`. That handle is post-synthesis: `KeyNodeSynthesiser` has already rewritten the node types in place to carry `@key(fields: "id", resolvable: true)`. `EmittedRegistry.of` documents its argument as the pre-synthesis registry, and `applySynthesisedKeys` appends every row of `graphitron_synthesized_federation_key` without looking, on the stated ground that those rows are disjoint from the registry's applications by construction. Handed the post-synthesis registry, they are not. Every test path (`TestSchemaHelper.deriveEmittedSchema`, `EmittedRegistryAgreementTest`, `EmittedRegistryTest`) hands it the capture's pre-synthesis registry, which is why production is the only caller that ever saw this.

**Tag on a declared `PageInfo`.** `applyInheritedTags` reads `graphitron_minted_coinage` (which carrier coordinate coined which minted type name) and stamps the coining field's `@tag`s onto each named type it finds in the registry. Capture writes a coinage row for `PageInfo` for every `@asConnection` carrier whether or not the mint wins, so the fold reaches an author-declared `PageInfo` and tags it. The store already knows the mint lost: `graphitron_type_minted` is "a type macro expansion adds, filling a name no author took", filtered against `graphql_type_element` and `graphitron_minted_conflict`, and the precedence comment in `graphitron-model.sql` names `PageInfo` as the mint that yields to an author. The fold just does not ask it. The walk (`ConnectionPromoter.registerPageInfo`) registers a declared `PageInfo` verbatim and untagged, which is the RC39 behaviour.

## Implementation

- **`EmittedRegistry`**: the public entry becomes `of(AttributedRegistry attributed, StoreHandle store)`, reading `attributed.preSynthesisRegistry()` itself; the registry-taking form becomes private. The choice of handle then lives with the class that states the contract, and production and tests reach the derivation through one input shape. A caller with no synthesis wraps its registry with the existing `new AttributedRegistry(registry, injectedNames)` constructor, whose two handles are the same object, which is honest for a registry nothing synthesised over.
- **`EmittedRegistry.applyInheritedTags`**: the coinage query semi-joins `graphitron_type_minted` on `(graph_name, type_name)`, so a tag is inherited only by a type the store says was minted. No Java-side "absent from the registry" set: that would restate the author-wins rule a second time, which the class javadoc already rejects for precedence, and would make the answer depend on which registry was passed, the first defect's failure mode. Revise the method's javadoc to say which types inherit and why.
- **`EmittedRegistry.applySynthesisedKeys`**: becomes the enforcer of its own disjointness claim. If the type already carries a `@key` whose `fields:` decodes (through `FederationKeyFieldsParser`, as `KeyNodeSynthesiser.hasIdKeyDirective` does) to the row's field set, throw `IllegalStateException` naming the type and saying this is a generator defect (synthesis applied twice). Not a silent skip: a skip would mask a wrong input rather than report it.
- **`GraphQLRewriteGenerator.emittedSchema`**: calls `EmittedRegistry.of(attributed, store)`.
- **Call sites**: `TestSchemaHelper.deriveEmittedSchema`, `EmittedRegistryAgreementTest` and `EmittedRegistryTest` move to the `AttributedRegistry` entry.
- **`AttributedRegistry` class javadoc**: the paragraph on `preSynthesisRegistry` says "The store does not take this handle". That is true of capture, which composes its own registry through `LoadingRewrites`, and not of emission, which patches this handle. Reword it to separate the two. `TagApplier` and `DescriptionNoteApplier` run above the pre-synthesis cut, so `<schemaInput tag>` tags on carrier fields and appended notes are still present on the handle emission now reads.

No DDL change and no new relation: both fixes read facts the store already holds. No user-facing docs change; the emitted schema returns to what the user manual already describes.

## Tests

- **Pipeline tier, `graphitron`** (the test that closes the production/test split): drive `GraphQLRewriteGenerator.generate()` over `GraphitronStore.captured(ctx)` with two tagged `SchemaInput`s, the reporter's shape. A `stable` file holds a federation `@link`, a declared `PageInfo @shareable` and a `@node @table` type with no `@key`; an `experimental` file holds an `@asConnection` field. Parse the emitted `schema.graphqls` and assert per type: the node type carries exactly one `@key(fields: "id")`; `PageInfo` carries `@shareable` and no `@tag`; the minted connection and edge types carry `@tag(name: "experimental")`. A second case without the declared `PageInfo` asserts the minted `PageInfo` does inherit `experimental`, so the fix is shown not to have switched inheritance off. Home: beside `ConnectionFederationTagPipelineTest`, whose existing `<schemaInput tag>` cases go through `GraphitronSchemaBuilder.buildBundle` (the walk) and so never reached the store path.
- **`EmittedRegistryTest`, `graphitron-model`**: a declared `PageInfo` with an explicitly `@tag`ged `@asConnection` carrier emits `PageInfo` untagged and the minted connection tagged; and an `AttributedRegistry` whose pre-synthesis handle already carries the synthesised `@key` makes `of` throw, pinning the enforcer.
- **`SchemaSdlEmissionTest`, `graphitron-sakila-example`**: over the federated SDL, every object type carries each distinct `@key` (by `fields:` and `resolvable:`) at most once. Stated as the property rather than as a count for `Customer`, `Address` and `Film`, which carry duplicates today.
- `EmittedRegistryAgreementTest` stays green, with `KNOWN_DISAGREEMENTS` unchanged.

## Other solutions we've considered

- **One-line call-site fix** (`attributed.preSynthesisRegistry()` at `emittedSchema`) plus javadoc. It fixes the reported symptom, and a scratch build of it does, but it leaves the registry-taking entry public, so the next caller can pass the wrong handle and every test keeps reaching the derivation through a different input from production. The overload costs eight mechanical test call sites.
- **Make `applySynthesisedKeys` idempotent.** It hides a wrong input instead of reporting it; the enforcer above is the same check with the opposite consequence.
- **Stop capture writing coinage for a mint that stands down.** Coinage is a true fact, which application would have minted what, and the family comment argues for writing it unconditionally. The stand-down is already a fact in `graphitron_type_minted`; the reader should join to it.

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
