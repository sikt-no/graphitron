---
id: R309
title: "Descriptions for query-as-view projections"
status: Ready
bucket: feature
priority: 7
theme: docs
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Descriptions for query-as-view projections

> Let a query-as-view projection carry prose that renders as real SDL descriptions
> in the schema-scissors output. `QueryViewRenderer` regenerates pruned SDL from a
> projection query (`ClassifiedCorpus.Example.query()`) that names the coordinates
> to show, but the rendered closure has no descriptions: the query says *what* a
> doc example shows, never *why* a field or type exists. This item adds a carrier
> for that prose alongside the selection and stamps it onto the rendered
> `DescribedNode`s. The carrier for now is GraphQL **comments** on the selection
> AST (`# ...`, retained on `Field` / `InlineFragment` / `FragmentDefinition` via
> `getComments()`); the design leaves a one-method seam so native executable
> descriptions can be adopted as an additional source once graphql-java ships
> them (see [Future evolution](#future-evolution-the-upgrade-path)).

This is test-and-docs-tooling work under `graphitron-rewrite/graphitron/src/test`:
it touches the spec-by-example corpus renderer and its doc-bridge guard, not the
generator's production path. The rendered blocks land on
`docs/code-generation-triggers.adoc` via `ClassifiedDocTest`, so a description
authored on a projection becomes visible documentation.

---

## Problem

`QueryViewRenderer.render(fixtureSdl, selection)`
(`graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/QueryViewRenderer.java`)
parses the projection `selection`, walks it against the assembled corpus schema,
and reprints the touched closure pruned to the selected fields, with the test-only
`@classified` / `@classifiedType` directives stripped. The output is faithful SDL,
but it is exactly the authored fixture SDL minus the unselected coordinates. The
corpus fixtures (`ClassifiedCorpus.java`) deliberately carry no descriptions, their
job is to assert dimensional verdicts, not to read as documentation, so the
rendered doc blocks on `code-generation-triggers.adoc` are description-free.

The projection query is already the natural place to say what an example
demonstrates: it is per-example (unlike the shared fixture), and it names exactly
the coordinates the reader will see. What it cannot do today is attach prose to
those coordinates. We want a description authored next to a selected coordinate to
render as that coordinate's SDL `Description`.

## Design

### Output side: every emitted node is a `DescribedNode`

The injection target is settled by the type system. Every node `QueryViewRenderer`
emits, `ObjectTypeDefinition`, `InterfaceTypeDefinition`, `UnionTypeDefinition`,
`EnumTypeDefinition`, `InputObjectTypeDefinition`, `FieldDefinition`,
`InputValueDefinition`, is a `graphql.language.DescribedNode` (backed by
`AbstractDescribedNode`), so each carries an optional `Description` and each
`.transform(...)` builder takes `.description(...)`. `AstPrinter` prints a present
`Description` as the leading quoted string. So the emit step is uniform: where the
renderer already rebuilds a node in `prune` / `keptFields` / `stripInternalDirectives`,
it additionally sets `.description(new Description(text, null, multiLine))` when a
description was recorded for that coordinate. No new node handling, no per-type
special casing beyond what the existing `switch` already does.

### Source side now: comments on the selection AST

The carrier is GraphQL line comments. graphql-java's parser retains a `# ...`
comment on the node it precedes, reachable as `node.getComments()`, and this holds
for exactly the three selection nodes the `Walk` already visits:

[cols="1,1,2"]
|===
| Selection form | AST node | Renders the description of

| `# text` above a field
| `Field` (`visitField`)
| that field's `FieldDefinition`

| `# text` above `... on T`
| `InlineFragment`
| type `T`

| `# text` above top-level `fragment f on T`
| `FragmentDefinition`
| type `T` (the type-display form)
|===

This was verified empirically against the pinned graphql-java 25.0: the comments
parse and survive on the nodes, and a `FieldDefinition` built with a `Description`
prints as `"text"` above the field through `AstPrinter`. Crucially, comments attach
to `Field`, which is the one selection node that is *not* a `DescribedNode` and
never will be (see below), so comments are the only carrier that reaches per-field
prose. A worked shape:

```graphql
{
  # A single film, fetched by primary key.
  film {
    # The film's display title.
    title
  }
}
```

renders `film` and `title` with those lines as their SDL descriptions.

### What the `Walk` records

`Walk` (the inner pre-order walker) gains a side table from coordinate to
description text:

- in `visitField`, when the `Field` carries comments, record them under
  `(container.getName(), field.getName())`;
- in the `InlineFragment` branch and in `fromDocument`'s `FragmentDefinition`
  branch, record comments under the type-condition name.

`Touched` (or a parallel structure) carries these to the emit loop, which looks up
each coordinate as it rebuilds the node. Multiple `#` lines on one node come back as
separate `Comment` entries; they are joined (newline-joined; the `Description` is
emitted as a block string `"""..."""` when the joined text contains a newline,
otherwise a single-line `"..."`). Leading `#` and surrounding whitespace are
trimmed per line.

### The one boundary that does not move

`Field` has no `Description` in the executable grammar, in graphql-java (25.0 or
master), or in the GraphQL spec, only `OperationDefinition`, `FragmentDefinition`,
and `VariableDefinition` gained descriptions. So per-output-field prose has no
native description carrier in any version; comments are it. Type descriptions (via
fragments) and argument descriptions (via variable definitions) are the parts the
spec eventually covers natively; field descriptions are not. This asymmetry is why
comments are the durable primary carrier here rather than a stopgap.

---

## Future evolution: the upgrade path

The GraphQL spec makes descriptions first-class on executable definitions, the
`Section 2 — Language` grammar carries `Description?` on `OperationDefinition`,
`FragmentDefinition`, and `VariableDefinition`, and graphql-java's `master` has
implemented it (those rules begin with `description?`, and `OperationDefinition`
now `extends AbstractDescribedNode`). The pinned 25.0 has not: its grammar lacks
it, the executable nodes have no `getDescription()`, and the parser rejects a
leading description string. So native executable descriptions are a graphql-java
version bump away, not available today.

The design keeps that bump cheap by routing every source read through a single
seam:

```java
// today: comments only
private static String descriptionOf(Node<?> node) {
    return joinComments(node.getComments());
}
```

When graphql-java is bumped past 25.0, `descriptionOf` additionally consults
`getDescription()` on the `DescribedNode` executable definitions (preferring a
native description over a comment when both are present) on `FragmentDefinition`
(type descriptions) and `VariableDefinition` (argument descriptions). The output
side does not change at all, it is already `DescribedNode`-based. Field-level prose
stays comment-sourced regardless, since `Field` never becomes a `DescribedNode`.
The migration is therefore additive and localized to one method; this item does not
perform it and does not depend on the bump.

## Implementation sites

All under `graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/`:

- `QueryViewRenderer.java`: a `descriptionOf(Node)` helper joining `getComments()`;
  `Walk` records `(parent, field)` and type-name to description text; the emit loop
  and `prune` / `keptFields` / `stripInternalDirectives` set `.description(...)` on
  the rebuilt `DescribedNode`s when a description was recorded.
- `QueryViewRendererTest.java`: new cases (below).
- `ClassifiedCorpus.java`: optionally annotate one or two existing doc-example
  `query()` strings with comments so the rendered doc blocks gain descriptions
  (purely additive; the fixtures' SDL stays description-free).
- `docs/code-generation-triggers.adoc`: if a doc example's `query()` gains comments,
  paste the re-rendered block (now with descriptions) under its prose so
  `ClassifiedDocTest` stays green.

## Tests

Pipeline-tier (matching the existing `QueryViewRendererTest` placement):

- a field comment renders as the field's SDL description;
- an `... on T` inline-fragment comment renders as type `T`'s description;
- a top-level `fragment f on T` comment renders as type `T`'s description
  (the type-display form);
- a multi-line comment renders as a block-string description;
- a projection with no comments renders byte-for-byte as it does today (the
  no-regression pin).

`ClassifiedDocTest` continues to guard that every doc example's rendered block,
descriptions included, appears verbatim on `code-generation-triggers.adoc`.

## Out of scope

- The graphql-java version bump and the native-description source read
  (the `descriptionOf` extension above). Tracked as future evolution; this item
  ships the comment carrier and the seam only.
- Descriptions on the production generator output. This is corpus-renderer /
  documentation tooling under `src/test`; it does not touch how the plugin emits
  schemas for consumers.
- Operation-level (`query { ... }`) descriptions. The renderer projects onto types
  and fields, not the operation as a whole; an operation-level comment has no SDL
  coordinate to land on and is ignored.

## Open question for the reviewer

*Block-string threshold.* The plan emits a block string `"""..."""` only when the
joined comment text contains a newline, single-line otherwise. Alternative: always
emit single-line and let `AstPrinter` decide. Settled toward newline-triggers-block
for readability of multi-line prose; flag if the reviewer prefers the simpler
always-single-line rule.
