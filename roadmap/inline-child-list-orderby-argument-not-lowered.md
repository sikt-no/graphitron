---
id: R935
title: "Lower a client-supplied ordering onto an inline child list, or refuse the shape"
status: Backlog
bucket: architecture
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# Lower a client-supplied ordering onto an inline child list, or refuse the shape

## Goal

A client that asks an inline child list for an ordering gets it, instead of getting a build that
stops. Today this schema

```graphql
type Film @table(name: "film") {
    actorsInline(order: [ActorOrderBy] @orderBy): [Actor!]!
        @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
}
```

fails the build with a generator-bug throw from the plan tier. It used to generate silently and
serve the actors in whatever order the database returned them, which is the defect the throw
replaced: an inline child list is projected as a correlated multiset, the multiset command carries
the coordinate's whole resolved ordering, and the projection renderer lowers the fixed arm of that
ordering alone. A client-supplied order was therefore accepted and dropped. Lowering the argument
arm onto the multiset closes it and the throw's population empties on its own; refusing the shape at
its coordinate with an author-facing rejection is the smaller alternative, and which of the two
ships is this item's question. Whichever lands, the exemption-free invariant in
`LauncherCommands.requireResolvedOrderingsAreLowered` is what stops the silence returning.

The shape is not exercised anywhere in this reactor, which is why the throw ships rather than a
rejection or an exemption list: a consumer meets it, our corpus does not. The confirmation that the
ordering really was dropped is recorded on the item that filed this one, and
`LauncherOrderingClosureTest.anInlineChildListCarryingAnOrderByArgumentIsRefusedAtProduction` is the
case that pins today's behaviour and is the case to rewrite when the lowering lands.
