---
id: R673
title: "A @nodeId argument on a polymorphic-returning field binds one node type per branch instead of dispatching on the decoded typeId"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# A @nodeId argument on a polymorphic-returning field binds one node type per branch instead of dispatching on the decoded typeId

A by-id lookup whose return type is a multitable interface classifies clean, generates without a diagnostic, and then accepts ids of only one implementation at runtime. Reported against 10.0.0-RC30 on

```graphql
type Query {
  applikasjon(id: ID! @nodeId): Applikasjon
}
```

where `Applikasjon` is implemented by three `@table` types, each carrying its own `@node(typeId: ...)`. An id belonging to any implementation but one fails the whole field with `Invalid node id 'MjAwMTE6LTE2' for this argument: decodes to type '20011', expected a FeideApplikasjon id`. The consumer workaround is one lookup query per implementation with an explicit `@nodeId(typeName: ...)`, which gives up the polymorphic lookup the schema was written to express.

## Why it happens

`FieldBuilder.buildNodeIdArgPlan` takes a single `TableRef`. On the multitable arms it is called once per participant from `FieldBuilder.lowerParticipantFilters`, each call handed that participant's own table, and `NodeIdLeafResolver.inferTypeName` then answers with the node type backing *that* table (`ctx.nodes.forTable(...)`). Each branch therefore carries its own expected typeId as a generation-time constant, baked into that branch's decode helper.

The mismatch policy compounds it. Every `@nodeId` argument filter resolves to `CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch`, a deliberate choice: on a single-table field a wrong-type id is a client mistake worth surfacing rather than silently degrading to "no row matches". Across participants that reasoning inverts. Exactly one branch can ever match a well-formed id, so a *correct* id necessarily mismatches every other branch, and the first branch to decode throws before any branch can succeed. The failure is structural, not a data problem: no id can satisfy the field except ids of whichever participant decodes first.

The node id already carries the discriminator the field needs. The generator decodes the typeId and then compares it against a constant, when the same value could select the branch.

## The two candidate outcomes

Both are defensible and the choice is the Spec's:

1. **Dispatch.** The emitted fetcher reads the decoded typeId and runs the matching participant's branch, pruning the rest. This is what the schema shape means and what a Relay client expects from a polymorphic node lookup.
2. **Reject at generate time.** If dispatch stays out of scope, the shape should fail the build with an author error naming the participants and their differing node types, rather than compiling clean and failing per-id at runtime. This is the reporter's own fallback ask.

Whichever ships, the mismatch policy needs revisiting for the multi-expected-type case specifically. Its justification, that a mismatch means the caller made a mistake, holds only when every branch expects the same node type.

## Notes for whoever specs this

- The shipped multitable `@nodeId` filter lifting was proven on `occupantsByAddress` over `AddressOccupant = Customer | Staff`, an FK-target filter where both participants expect the same node type (Address). Its execution test asserts the wrong-type-id client error as *desired* behaviour, which is correct for that shape and is exactly the assumption this item breaks. Any change here has to keep that test's meaning intact.
- The issue elides the interface's own directives, so it is not certain from the report whether the field lands on the per-participant `QueryInterfaceField` arm or the single-base-table interface arm. Both funnel through the same single-`TableRef` resolution, so the problem statement holds either way, but the Spec needs to pin which arms are in scope. Note `buildNodeIdArgPlan` has nine call sites; the polymorphic ones are the participant loop and the table-interface arm.
- An explicit `@nodeId(typeName:)` on the argument does not help: it pins one expected type for every branch, which is the same defect stated by hand.

Reported at https://github.com/sikt-no/graphitron/issues/526.
