---
id: R714
title: "The assembled schema owns the composed SDL census"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: [three-strata-capture-derive-query]
created: 2026-08-18
last-updated: 2026-08-18
---

# The assembled schema owns the composed SDL census

`graphql_type`, `graphql_field`, `graphql_type_declaration` and the rest of the `graphql_` payload are
written today from `attributed.preSynthesisRegistry()`, a `TypeDefinitionRegistry`. A registry is the
uncomposed census: a type's base declaration and its extensions are separate entries, and capture
merges them itself into one coordinate-keyed row with a declaration-site back-link, deciding
collisions through `FactSink.claim`'s first-wins.

That merge is graphql-java's job and graphql-java already does it. `GraphQLSchema` is the composed
census: extensions merged, interface fields inherited, default values resolved, applied-directive
arguments filled in from their definitions. Reading it instead of the registry deletes a
reimplementation rather than moving one.

## Three censuses, three units, one producer each

The three stages `fact-model.adoc` already names are three censuses, and the item's whole content is
assigning the payload to the third:

[cols="1,1,2"]
|===
| Census | Unit | Owns

| parse
| one file
| source membership, `graphql_syntax_error`

| registry
| the file set
| `graphql_schema_error` at stage `REGISTRY`

| assembly
| the whole schema
| every `graphql_` payload relation, and `graphql_schema_error` at stage `ASSEMBLY`
|===

Change tempo follows the unit, which is the point: editing one file invalidates its own parse row and
the whole of the other two.

## The object is already in hand

`SchemaAssembly.of` runs on every pass, and its javadoc already says why: assembly "is the only place
the specification's structural rules get checked at all", so "assembly runs on every pass whether or
not the assembled schema is going to be used for anything, and its outcome is data rather than control
flow". It returns a sealed `Assembled(GraphQLSchema)` / `Rejected(errors, cause)`, and it wires with
`EchoingWiringFactory` plus fake scalars because "this schema is assembled to be read, never
executed", so a custom scalar does not fail assembly for want of an implementation.

An always-produced, read-only, availability-sealed composed schema is precisely a transcription
source. What is missing is that capture takes the registry instead.

Assembly also keeps the AST back-pointers, `getDefinition()` and `getExtensionDefinitions()` on a type
and `getDefinition()` on a field, so `graphql_type_declaration` and `graphql_field`'s site foreign key
remain writable from the composed object. Owning the declaration relation from assembly rather than
from the parse is deliberate: it gives the whole payload one producer, and leaves the parse census
answering only which sources exist and which refused.

## Which registry gets assembled

Today it is `SchemaAssembly.of(attributed.registry())`, the post-synthesis registry. Capture must
transcribe the document, so it needs the pre-synthesis one, and the snapshot is cut in the right
place for that: after the schema-level rewrites that inject declarations (`FederationLinkApplier`
among them) and before `KeyNodeSynthesiser` applies federation keys.

Verify rather than assume that the pre-synthesis registry assembles standalone. Those injected
declarations are what make a federated schema assemblable at all, and if the cut sits on the wrong
side of any of them the pre-synthesis assembly fails on schemas whose authors wrote nothing wrong.

A finding that falls out of the same question: today's `ASSEMBLY` verdicts judge the post-synthesis
registry, so a verdict can be caused by graphitron's own rewrite rather than by the author. Taking
the transcription and its verdict from one assembly removes that.

## The availability cliff, and why this is still an improvement

An assembly-owned payload has no rows when assembly fails, which reads like the failure
`fact-model.adoc` warns about: aborting "is what lets one freshly broken file blank every fact about
every file beside it, which is precisely when an author needs those facts most". In the dev loop a
half-typed edit is exactly the state that fails assembly.

The current arrangement is worse than it looks, though, and this is the argument for the change rather
than a cost to accept. Writing the payload from a registry that does not assemble publishes a
composition graphql-java would reject, as though it were current. The reader cannot tell. Under
assembly ownership plus a per-census currency status, a failed assembly leaves the previous composed
census in place and says so, which is a stale valid answer instead of a fresh invalid one. The
machinery is already there: `LspSchemaSnapshot` carries availability and then current-versus-previous,
and the editor "tolerates the previous snapshot and tags it".

So the deliverable includes the status, not just the source swap. Without it this trades a silent
wrong answer for a silent absence.

## Two decisions to make rather than discover

- **Introspection types.** Assembly adds `__Schema`, `__Type` and friends. Today's census holds the
  five built-in scalars (`Boolean`, `Float`, `ID`, `Int`, `String` are all present in the sakila
  example's twenty `graphql_type` rows) and no introspection types. Either filter them or accept them
  as facts, but decide it in the open, because the built-in scalars establish that "the author did not
  write it" is not by itself a reason to exclude a row.
- **Applied-directive ordinals.** `graphql_type_directive.ordinal` is "the owning application's
  ordinal" and is part of the key. Base-then-extension merge order has to survive the move, which
  wants a pinned test over a multi-extension fixture rather than an assumption about graphql-java's
  iteration order.

## What this deletes

- Capture's own extension merge, and with it the first-wins claim on a coordinate two files declare.
  A duplicate is then either graphql-java's merge or graphql-java's refusal, and the refusal is
  already a fact in `graphql_schema_error`.
- The argument, floated and withdrawn during design, that tier one for SDL should bottom out at
  declaration sites with composition as a derivation of ours. Recorded because it is the tempting
  wrong answer: it reimplements the merge instead of reading it.

## Out of scope

- The `graphitron_` decodes, which read the same corpus and move to reading rows in their own item.
  They are downstream of this: once the payload is composed by assembly, a decode reading those rows
  gets defaults already applied.
- Per-census transactions and the currency-status relation's own shape, beyond this census needing a
  status.
- Any change to what the pipeline assembles for code generation.
