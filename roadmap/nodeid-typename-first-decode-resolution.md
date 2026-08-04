---
id: R581
title: "@nodeId(typeName:) resolves off the named type, not a reverse table lookup"
status: In Review
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# @nodeId(typeName:) resolves off the named type, not a reverse table lookup

Declaring a second `@node` type over a table broke every `@nodeId(typeName:)` leaf already
pointing at that table, including the leaves that named their type explicitly and were therefore
never ambiguous. Reported from the sis / utdanningsregisteret federation work: adding
`Organisasjon` (the shared entity, `typeId: "46"`) alongside the existing `URegOrganisasjon` over
`organisasjon.organisasjon` is exactly the shape the bug forbids, and a minimal probe type over an
unrelated table reproduced it with eight author-errors across four query fields and three input
types, plus one `@mutation` cascade. The second type needed no reference from any query, field or
input; declaring it was enough.

`NodeIdLeafResolver.resolve` settled the type name first, then threw it away: it derived the
target table from the named type and asked `BuildContext.resolveDecodeHelperForTable` to map that
table back to a NodeType. That reverse lookup answers "zero or multiple" whenever more than one
`@node` covers the table, and the resolver reported it as
`unable to resolve the NodeType backing table '<T>' (zero or multiple GraphQL types map to it)`.
The table-keyed helper is right for the call sites that genuinely hold only a table name (the
input-side synthesis shims), and wrong for the sites that hold a name the author supplied.

`BuildContext.resolveTargetKeys` had the same polarity on its first arm, reading the table's
`KjerneJooqGenerator` metadata ahead of the `NodeIndex` by-name entry. `TypeBuilder` has already
reconciled `@node` against that metadata per type, letting SDL win on `typeId` outright and on
`keyColumns` order, so the metadata-first read discarded the reconciliation: two `@node` types over
one table would both report the table's `typeId` (wrong wire prefix for a
`decodeValues(typeId, …)` call on the jOOQ-record input-bean path), and a `@node(keyColumns:)`
pinning a different order than the metadata would project its columns transposed against the order
its own decode helper returns values in.

This is R473's Phase 1, carved out and driven by the field report. R473 keeps the rest of its
grammar (rules 1-4 and the Phase 2 shim retirement).

## Implementation

Shipped.

* `BuildContext.resolveDecodeHelperForType` is the new name-first entry point: `NodeIndex.forName`
  when a NodeType carries the name, `resolveDecodeHelperForTable` only when none does (the
  orphan case that helper exists for, a `@table`-only type over a metadata-carrying table).
  `NodeIdLeafResolver.resolve` and `BuildContext.buildInputNodeIdReference` call it; the
  bare-scalar-ID arm in `FieldBuilder.classifyArgument` and the input-field synthesis shim keep the
  table-keyed helper, which is correct for them because they hold no name.
* `BuildContext.resolveTargetKeys` reorders to name-first: `NodeIndex.forName`, then the table's
  catalog metadata, then `@node` on the SDL with catalog PK columns.
* The resolver's remaining rejection is re-aimed at what is actually wrong at that point: the
  named type is not a `@node`, and the table has no unambiguous identity to fall back on.

## Tests

Shipped, in `NodeIdPipelineTest`:

* `InputCase.EXPLICIT_TYPENAME_DISAMBIGUATES_MULTI_NODE_TABLE` and
  `EXPLICIT_TYPENAME_PICKS_THE_NAMED_SIBLING`: two `@node` types over `bar`, an input-field
  `@nodeId(typeName:)` naming each in turn, resolving to that type's own decode helper. Sibling to
  the existing `R377_MULTI_NODE_REJECTS`, which keeps holding for the no-typeName leaf where the
  table-keyed lookup is the only source available.
* `InputCase.EXPLICIT_TYPENAME_TAKES_KEY_ORDER_FROM_THE_NAMED_NODE`: `@node(keyColumns:)` in an
  order the table's metadata does not use; the leaf projects the named type's order.
* `ArgumentSameTableNodeIdCase.SAME_TABLE_SCALAR_UNREFERENCED_SIBLING_NODE`: the argument-side
  path, with the sibling `@node` unreferenced from anywhere, pinning the report's "declaring it is
  enough" property against the all-declared `NodeIndex`.

## Documentation

Shipped. `docs/manual/reference/directives/node.adoc` gains a "Several node types over one table"
section covering the federation-entity and deprecation-window shapes and stating that every leaf
past the node's own `id:` names its type. `nodeId.adoc`'s `typeName:` row now says which axis the
backing table decides and which the named type does.

## Not in scope

* Phase 2 of R473 (bare `@nodeId` on inputs and arguments becoming an error, plain-`ID` columns
  losing their synthesized node semantics, deleting `resolveDecodeHelperForTable` with the shims).
* `GraphitronSchemaValidator`'s typeId-uniqueness check, which already permits several `@node`
  types over one table as long as their typeIds differ, and is what makes this shape legal at all.
