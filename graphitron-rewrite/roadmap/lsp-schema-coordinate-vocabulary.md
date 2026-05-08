---
id: R119
title: "LSP completion / diagnostics keyed by schema coordinates"
status: Backlog
bucket: architecture
priority: 5
theme: lsp
depends-on: []
---

# LSP completion / diagnostics keyed by schema coordinates

The LSP's directive vocabulary today is keyed by ad-hoc string-typed
identifiers: `Diagnostics.VALIDATE_METHOD = Set.of("service", "condition", ...)`
is a manually curated `Set<String>`, `DirectiveDefinitions.ENTRIES` is a hand-
written list of `DirectiveDef(name, args)` records, and
`argsByInputType("ExternalCodeReference")` is a derived view but its key is
just a type-name string. Nothing in the type system stops a typo or a
schema-side rename from silently breaking the LSP. R110 demonstrated this:
`@batchKeyLifter` was removed from `directives.graphqls` and `@sourceRow` took
its place, but the hand-written LSP registry kept both wrong (still mentions
`@batchKeyLifter`, never grew a `@sourceRow` entry). The LSP build stays green
because the LSP's tests are self-consistent against its own copy. Drift is
invisible at CI and lands silently on the user as bad in-editor feedback.

The structural fix: move the LSP's behavior table to be keyed by GraphQL
**schema coordinates** (`ExternalCodeReference.className`,
`@service(service:)`, `@reference(path:)`, etc.), populated by parsing
`directives.graphqls` from the classpath at LSP startup. Every entry in the
behavior table resolves against the parsed schema or the LSP fails to start
with a coordinate-level error. R110-style drift becomes a startup-time loud
failure, not silent IDE breakage.

## What this buys

**Provability.** The LSP can't compile a behavior table that points at a
non-existent coordinate; mismatch fails fast at startup. The structural
guarantee replaces today's "remember to update the registry" discipline.

**Same functionality, multiple coordinates.** "Java FQCN; complete from
classpath" attaches once and applies to:

- `ExternalCodeReference.className`
- `ExternalCodeReference.name` (deprecated, with deprecation diagnostic)
- `@sourceRow(className:)`
- any future flat-args directive carrying an FQCN slot

"Static method on the resolved className" attaches to
`ExternalCodeReference.method` and `@sourceRow(method:)`. The hand-written
parallel paths (one for ECR-bound directives, one for `@sourceRow`'s flat
args) collapse into one table keyed by coordinate.

**SDL is canonical for shape.** `argMapping`, `nestedPath` (for
`@reference(path: [{condition: ...}])`), the `String! / [String!]!` type
literals on each arg, and the docstrings (which become hover content) all
fall out of the parsed schema. None of it has to be hand-mirrored.

## What stays out

The behavior table is the addressing scheme; the diagnostics that read it
stay scoped to what the LSP already does:

- The `argMapping` content-syntax validator (parsing
  `"javaParam: graphqlArg, ..."`) is a separate diagnostic, equally
  applicable before and after this work.
- The "structurally inert on `@externalField` / `@enum` / `@record`" rule for
  `argMapping` (documented inline in `directives.graphqls`) is its own
  coordinate-keyed diagnostic, not directive-shape vocabulary.
- The `className` → loaded-class → method-list resolution pipeline (today's
  `CompletionData` / `Diagnostics` walk) keeps its current shape; only the
  table that decides "this coordinate is FQCN-shaped" moves.
- Coordinates beyond the directive surface (e.g. `@table(name:)` SQL-name
  validation against the catalog, `@reference(key:)` validation against
  declared FKs) ride on the same coordinate-keyed mechanism but are filed
  under their own items (sibling LSP feature items already exist).

## Implementation sketch

1. Read `directives.graphqls` from the classpath at LSP startup with
   GraphQL-Java (already on the classpath transitively via the existing
   `graphitron` dependency in `graphitron-lsp/pom.xml`); fail to start with
   a clear error if the resource is missing or malformed.
2. Walk the parsed schema's directives and the input types they reference
   to populate the coordinate → behavior table. The today's `DirectiveDef`
   records become a derived view used by handlers that haven't yet migrated
   to coordinate lookup.
3. Replace `Diagnostics.VALIDATE_METHOD` and the
   `argsByInputType("ExternalCodeReference")` walk with coordinate-keyed
   lookups against the table.
4. Drop the three pinning tests' `@batchKeyLifter` references; they should
   read the parsed schema, not a hand-written registry mirror.
5. Wire the FQCN-aware completion and method-name validation through to
   `@sourceRow`'s flat args (the user-facing gap R110 left in place); same
   functionality, attached via a second coordinate.

## Surfaced from

R110 In Review → Done approval (commit `5176f2f8` on the rewrite trunk;
see the R110 changelog entry's "Findings noted at approval" section). The
narrower item originally filed as "LSP directive registry sourced from
directives.graphqls" was rescoped during a follow-up design discussion to
this structurally-grounded version; the registry replacement is the first
step of the migration above, not the goal.
