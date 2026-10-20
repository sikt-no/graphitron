---
id: R1076
title: "_entities answers a representation with an unknown __typename with an error, not a silent null"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# _entities answers a representation with an unknown __typename with an error, not a silent null

## Goal

A federation `_entities` request whose representation names a `__typename` this subgraph does not
resolve gets an error the client and the router can see, as it did under Graphitron 9 ("Cannot
resolve entity with __typename 'Foo'."), instead of a silent `null` in that slot. An unknown type in a
representation means the supergraph and the subgraph disagree, which is a deployment fault worth
surfacing.

## Evidence and decision

Surfaced by the sis v9 against v10 differential test (2026-10-19). Today the generated
`EntityFetcherDispatch.resolveByReps` groups representations by `__typename`, calls a handler for each
type it knows, and leaves every other slot `null` with no error. Nothing documents that for
`_entities`; `how-to/global-id.adoc` documents `null` for an unknown type id on `node(id:)`, which is a
client-supplied id and a different case. The user decided (relayed through the sis session,
2026-10-20) that the client must get an error. sis does not depend on either behaviour.

## For the plan

The shape is the plan's call: a field error located at the slot with that slot `null` and the rest
of the batch resolved, or Graphitron 9's whole-response failure. The first matches how `_entities`
already treats a representation that resolves to no row, and how the undecodable-key work treats a
bad key, so it is the likely default. The message names the type. A representation with no
`__typename` at all (skipped today in the same loop) belongs in the same decision. The error must
reach the client unredacted, so it goes through the generated client-exception marker rather than a
plain runtime exception.
