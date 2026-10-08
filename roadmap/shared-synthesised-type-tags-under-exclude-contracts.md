---
id: R997
title: "Generated pagination types take the wrong federation directives from their fields: every carrier's tags, and no @shareable"
status: Spec
bucket: bug
priority: 2
theme: pagination
depends-on: []
created: 2026-10-07
last-updated: 2026-10-08
---

# Generated pagination types take the wrong federation directives from their fields: every carrier's tags, and no @shareable

## Goal

The pagination types graphitron generates for a federated subgraph take the federation directives a contract and a composition need from the fields they were generated for. Two things change. A type generated for more than one field carries only the `@tag`s every one of those fields carries, rather than every tag any of them carries, so a subgraph whose Apollo contract variants are built by *excluding* a tag keeps its shared generated types in every contract that keeps a field returning them. That is the rule Apollo's contract reference asks authors to follow by hand: a tag on a type definition also belongs on every field that returns the type, or a contract can drop the type while keeping a field that returns it. And `@shareable` on a field reaches the types generated for it again, which it stopped doing when the published schema began to be built from the fact store, so a `PageInfo` two subgraphs both generate composes without the author declaring it. The tag half was reported on GitHub issue #555 by the tilgangsstyring subgraph in fs-plattform, after the separate regression there was fixed; the `@shareable` half was found while reproducing it.

The shape, with type names from the report and field names illustrative. A `stable`-tagged feature file and an `experimental`-tagged one each hold an `@asConnection` field. `@asConnection` marks a list field graphitron expands into a Relay connection; the field is the *carrier*, and the expansion *mints* (generates, because the author did not declare them) a Connection type, an Edge type and `PageInfo`. `<schemaInput tag>` is the Maven configuration that stamps one `@tag` on everything a schema file declares. Neither file declares `PageInfo`:

```graphql
# features/stable/... (<schemaInput tag>stable)
extend type Query { organisasjoner: [Organisasjon!]! @asConnection }
# features/experimental/... (<schemaInput tag>experimental)
extend type Query { miljoer: [Miljo!]! @asConnection }
```

Today the one minted `PageInfo` is emitted with the union of its carriers' tags, and a contract that excludes `experimental` removes it while keeping `QueryOrganisasjonerConnection.pageInfo`, so the contract schema is invalid:

```graphql
type PageInfo @tag(name: "experimental") @tag(name: "stable") { ... }   # today
type PageInfo { ... }                                                   # after
type QueryOrganisasjonerConnection @tag(name: "stable") { ... }         # unchanged
type QueryMiljoerConnection @tag(name: "experimental") { ... }          # unchanged
```

The `@shareable` half, reproduced on trunk at `1fa7915` through the generator's own output:

```graphql
extend type Query {
  stableFilms: [Film!]! @asConnection @defaultOrder(primaryKey: true) @shareable
}
```

emits `Query.stableFilms` with its `@shareable`, and `QueryStableFilmsConnection`, `QueryStableFilmsConnectionEdge` and `PageInfo` with `@tag` only. After this item all three carry `@shareable`, as the manual already says they do.

On tags, a type with one carrier, which is every Connection and Edge not shared through `connectionName:`, is unchanged. The cost falls on include-based contracts (a contract that keeps only what carries a listed tag) whose carriers disagree: an untagged shared type is absent from them. Those builds get a suppressible build warning naming the type and the fix, which is to declare the type in SDL with the tags wanted, since an author-declared type inherits nothing. That is the same escape hatch exclude-based subgraphs rely on today, moved to the side that has the warning to find it.

## The rule

Stated once, here and in the manual, as a set expression so that whoever later moves it into the store (see Out of scope) ports it rather than re-derives it:

> For a minted type `T`, let `C(T)` be the fields whose expansion minted `T` (its carriers) and `tags(c)` the `@tag` names applied to carrier `c`. `T` carries `⋂ { tags(c) : c ∈ C(T) }`.

Why intersection over the carriers is Apollo's rule over the fields that return `T`. A minted Connection is returned only by its carriers. A minted Edge is returned only by `edges` on its Connection, whose tags are the Connection's. A minted `PageInfo` is returned by `pageInfo` on every Connection, and the facet types (`<Connection>Facets`, and the schema-wide `<Scalar>FacetValue` that is shared exactly like `PageInfo`) by the `facets` field and the Facets type's own fields. Each returning field's effective tags are its enclosing type's, which are its carriers', and intersection is associative, so intersecting over carriers equals intersecting over returning fields wherever every link in the chain is minted.

One chain is not all minted: an author-declared Connection (the structural arm, where the SDL declares the Connection and Edge and graphitron reuses them) whose `PageInfo` is still minted. There the field returning `PageInfo` is the declared Connection's own `pageInfo`, whose effective tags are the declared Connection type's, so that is what the carrier contributes. The walk already reads it that way (`ConnectionPromoter.promotionFor`, structural arm, reads the declared Connection's `@tag`s); the store path must agree, and a test pins it (see Tests). The declared Connection and Edge themselves inherit nothing, as today.

Intersection, not "untagged when the carriers disagree": the two differ only when carriers share some tags and differ on others (a shared `public` beside a `stable` and `experimental` split), and there intersection keeps `public`, which Apollo's rule permits, where the alternative drops it.

`@shareable` is a different axis and does not follow the tag rule. It is a composition requirement (two subgraphs may both resolve the type), not a contract filter, so it is the union: `T` is shareable when any `c ∈ C(T)` is, which is what the walk does today and what the store path fails to do at all. The structural arm contributes the declared Connection's own `@shareable`, as the walk reads it. The two axes must be kept apart in code: the walk holds `@tag` and `@shareable` in one applied-directive list, and intersecting that list would drop `@shareable` whenever carriers disagree on it. An author-declared type inherits neither, as today.

## Implementation

- `EmittedRegistry.applyInheritedTags` (graphitron-model) becomes the one fold for both directives, renamed to say so (`applyInheritedFederationDirectives` or similar). Per minted type, over the same `graphitron_minted_coinage` rows joined to `graphitron_type_minted`, it computes the tag intersection and the shareable OR, and writes both onto the definition in one replacement. Where `@shareable` comes from is the same as where the tags come from: the patched registry, read at the carrier's coordinate by directive name `shareable`, the name the walk reads. A `@link` import that aliases `@shareable` is honoured by neither producer today and stays out of scope.
- The tag half of that fold intersects the carriers' tag sets instead of accumulating them. Order the result by the first carrier's tag order (coinage rows are already ordered by type name and coordinate), so emission stays deterministic. The structural arm contributes the declared Connection type's type-level tags, per The rule. Update the method javadoc, which currently states the union.
- `EmittedRegistry.tagsAt`: a coordinate that resolves to no field becomes an `IllegalStateException`. Under union a miss only lost that carrier's tags; under intersection it silently strips every tag from the type, which is the wrong failure to have quiet. Coinage is written from fields that exist, so a miss is a defect in this method's lookup, not an author error.
- The fold also returns the narrowings it made: each minted type whose intersection is strictly smaller than the union, with the dropped tags and the carriers that carried them. `GraphQLRewriteGenerator.emittedSchema` passes them out beside the schema, and report assembly folds them into the build-warning channel next to `SessionStateWarnings`.
- A new `LintRule` constant, `SHARED_TYPE_TAGS_NARROWED("shared-type-tags-narrowed", Source.CODEGEN)`, emitted as a `BuildWarning.LintFinding` located at the first carrier whose tag was dropped. Rule-tagged, so `<lint><disabledRules>` suppresses it, which is what an exclude-only subgraph does once. The message names the type, the tags kept and dropped, the carriers by coordinate, and both readings: an exclude-based contract wants this outcome and can disable the rule; an include-based one keeps the type only if the author declares it with the tags wanted.
- `TypeRegistry.mergeSynthesisedTags` (graphitron, the walk): intersect the `tag` directives and keep every other applied directive and the `shareable` flag as an OR. `unionDirectives` and `directiveKey` change accordingly, with the javadoc. The walk also gains tags on the facet types it mints (`ConnectionPromoter.buildSynthesisedFacets` / `buildSynthesisedFacetValue` take the carrier's tags today in no form, and `mergeSynthesisedTags` falls to `default -> incoming` for them), because the store path already tags them and a tagged faceted corpus document would otherwise disagree. Mirroring the walk rather than recording the new corpus document in `EmittedRegistryAgreementTest.KNOWN_DISAGREEMENTS` keeps the one comparison that exercises tags live; retiring the walk is not this item's.
- `ConnectionPromoter` javadoc that names the union (the class note on `ctx.typeRegistry.register` and the shared-`PageInfo` note above `promotionFor`) restated as the intersection.

## Tests

- `StoreEmittedFederationSchemaPipelineTest` (the published SDL, read off the file the generator writes): the reporter's shape, a `stable` input and an `experimental` input each with an `@asConnection` carrier and no declared `PageInfo`, emits `PageInfo` untagged while each Connection and Edge keeps its own carrier's tag. A second case shares a Connection through `connectionName:` between carriers tagged `{public, stable}` and `{public, experimental}` and asserts the shared Connection, Edge and `PageInfo` carry exactly `public`. A third keeps a declared Connection (structural arm) tagged `stable` beside a minted `PageInfo` reached only through it, and asserts `PageInfo` carries `stable`. A fourth is the `@shareable` reproduction above: a `@shareable` carrier and an unshareable one, and the carrier's Connection and Edge plus the shared `PageInfo` emit `@shareable` while the other carrier's Connection and Edge do not. The existing `aMintedPageInfoStillInherits` and `theStableFileEmitsAsWritten` stand unchanged: one carrier, and an author-declared `PageInfo` that keeps exactly its own `@shareable`.
- The warning: the reporter's shape raises exactly one `shared-type-tags-narrowed` finding, located at a carrier and naming `PageInfo`; a build with that rule in `<lint><disabledRules>` raises none; a single-carrier build raises none.
- `EmittedRegistryTest` § "The tags a minted type inherits": a unit case for the intersection, and one for the `tagsAt` miss raising.
- `ConnectionPromoterTest.sharedConnectionName_synthesisedTypesCarryTagUnion` becomes the intersection case under a name that says so; a new case pins `@shareable` surviving on the shared types when only one carrier is shareable. `TypeRegistryTest`'s shareable-OR case stays as is.
- `EmittedRegistryAgreementTest`: a new corpus document under `graphitron-model/src/test/resources/corpus/` with two carriers tagged differently, sharing a connection name, with a facet, one of them `@shareable`, so the comparison exercises the tag rule, the shareable rule, the shared Connection/Edge/`PageInfo`, and the facet types. It is not added to `KNOWN_DISAGREEMENTS`. Declaring `@tag` in a corpus document is the first one to do so; if the corpus prelude cannot carry the directive declaration, the document declares it itself as `EmittedRegistryTest` does.
- Acceptance outside the build is the reporter's exclude-based contract check passing on tilgangsstyring without a declared `PageInfo`. R298's GraphOS contract verification, when it lands, is where a standing exclude-based contract belongs; this item does not depend on it.

## User documentation (first-client check)

`docs/manual/reference/directives/asConnection.adoc`, replacing the paragraph that begins "Federation directives on the field":

> Federation directives on the field propagate onto the Connection, Edge, `PageInfo` and facet types graphitron generates for it. `@shareable` on any field makes the generated types shareable. `@tag` follows Apollo's contract rule that a type's tags also appear on every field returning it: a generated type carries the tags that every field it was generated for carries. A type generated for one field takes that field's tags. A type shared by several fields (`PageInfo`, a Connection and Edge shared through `connectionName:`, a facet value type) takes only the tags they have in common, so a contract that excludes one of the fields' tags keeps the shared type for the others.
>
> When the fields disagree, the shared type ends up with fewer tags than some of them, and the build reports `shared-type-tags-narrowed`. Contracts built by excluding tags want this outcome; disable the rule under `<lint><disabledRules>`. A contract built by including tags drops a type that carries none of them, so declare the type yourself with the tags it should have. A type you declare is used as written and inherits nothing.

`docs/manual/how-to/apollo-federation.adoc` § "The `<schemaInput tag>` flag" gains one sentence pointing at that paragraph, since a feature-file layout with per-input tags is exactly where shared types meet disagreeing carriers. `mojo-configuration.adoc` § "Silencing lint warnings" does not enumerate rule ids, so it needs no edit; the directive page documenting its own rule id follows `reference-path-fans-out` in `directives/reference.adoc`.

## Out of scope

- **Transcribing `<schemaInput tag>` tags into a relation.** `applyInheritedTags` reads tags from the patched registry because those tags reach no relation; its javadoc names that gap. The rule above is a relational division over (minted type, carrier, tag) and becomes a view once the relation exists. This item states the rule in that form and does not close the gap. `@shareable` is read the same way for the same reason, so that the one fold has one source; the `graphitron_link_entry` comment records that `@shareable` has no decoded relation.
- **A carrier's tags inherited from its enclosing type.** Apollo applies a type-level tag to every field of the type, so a carrier on a tagged type carries that tag; `tagsAt` reads field-level tags only. Pre-existing under union and unchanged in kind here.
- A configuration switch between union and intersection; see below.

## Other solutions we've considered

- **Keep the union and document declaring the shared type.** It is what exclude-based subgraphs do today, and it leaves the emitted schema violating Apollo's own authoring rule whenever carriers disagree. The known users of `<schemaInput tag>` (opptak, tilgangsstyring) build exclude-based contracts, and Apollo's usage guide treats exclude as the low-effort default, so this puts the workaround on the common side.
- **A build-level switch (`union` / `intersection`).** The filter style belongs to each contract, not to the build: one subgraph feeds several contract variants, which may mix include and exclude filters, so no single build setting is right for a mixed setup, and it adds a second rule to both producers. Declaring the type is the override that serves every contract, and the warning points at it.
- **"Untagged when the carriers disagree".** Simpler to say, and it drops tags every carrier agrees on; see The rule.
- **Tagging the shared type's fields instead of the type.** Apollo keeps an untagged type under an include filter if one of its fields carries an included tag, but an exclude filter removes every field carrying an excluded tag, so per-field union tags empty the type under exclusion. No placement of the union serves both filter styles.
- **Minting a separate `PageInfo` per carrier tag set.** Serves both styles, and renames a type clients hold fragments against.
