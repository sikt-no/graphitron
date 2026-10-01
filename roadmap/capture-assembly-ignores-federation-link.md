---
id: R985
title: "The capture assembles the schema without the federation @link, so every imported directive is reported undeclared"
status: Backlog
bucket: dx
theme: diagnostics
depends-on: []
created: 2026-10-01
last-updated: 2026-10-01
---

# The capture assembles the schema without the federation @link, so every imported directive is reported undeclared

## Goal

A federated schema reads as clean in the editor and over MCP when the generator finds it clean. A
federation `@link` is the schema extension that imports the Apollo federation spec's directives
(`@key`, `@shareable`, `@tag`, `@inaccessible`, `@override`) into an author's schema. The generator
honours it. The fact store's capture, the pass that records the corpus into the store the language
server and MCP read, does not, so every application of an imported directive is reported as
`tried to use an undeclared directive`. The outcome is one composition of the corpus, shared by both
paths, so a directive the generator accepts is never reported as undeclared by the store.

## What is true today

The generator reads the corpus through `AttributedRegistry.load`, which runs `TagLinkSynthesiser`
and then `FederationLinkApplier` before anything assembles a schema. `GraphQLAssemblyCapture.capture`
instead hands `SchemaLoader.merge` of the raw per-source registries to `SchemaAssembly.of`, which
calls graphql-java's `makeExecutableSchema` on a registry with no federation definitions in it. Each
`UndeclaredDirectiveError` that raises becomes a `graphql_schema_problem` row, and the `diagnostic`
view surfaces each row with no kind.

The gap predates the one-pass capture. The raw parse has been the input to the problem rows since the
assembly verdict was first captured, and `GraphQLAssemblyCapture` took its current shape when each
stage of reading the corpus got its own gatherer. What is not settled is whether the earlier two-pass
capture's graph-scoped clear was hiding these rows, which would make the move to one pass the change
that exposed them.

Observed on sis (2026-10-01): 108 undeclared-directive diagnostics, across `@tag`, `@shareable`,
`@key`, `@inaccessible` and `@link`, from one `extend schema @link(url:
"https://specs.apollo.dev/federation/v2.4", import: [...])`. The generator's own run reports none of
them. Because the assembly refuses, the store's freshness also plausibly stays at `Previous` (the
tools answering from the last clean facts) for any federated consumer. That is unverified and is the
first thing to check when this is picked up.

## Direction

Compose the corpus once. The federation rewrites belong on the path both the generator and the
capture take, so the assembly the capture records is the assembly the generator classifies. The
alternative, filtering the undeclared-directive message out of the problem rows the way
`GraphitronSchemaBuilder` filters it out of the generator's errors, would make the store report
nothing while still assembling a different schema from the one that gets generated, which is the
two-producers shape R876 is named for: two readings of one corpus that can disagree with nothing
comparing them.
