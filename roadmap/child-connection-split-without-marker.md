---
id: R1178
title: "Is a child connection fetched in a query of its own without @splitQuery?"
status: Backlog
bucket: architecture
priority: 3
theme: pagination
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# Is a child connection fetched in a query of its own without @splitQuery?

## Goal

Settle one rule and write it down: is a child connection, a child field that carries `@asConnection` or returns a connection type, fetched in a query of its own whether or not the author writes `@splitQuery`? The tree gives three answers today, so the fact store, the classifier and the manual disagree about the same schema. A settled answer lets a child connection be stated once, in the store, and lets the classifier's refusal and two wrong manual sentences go, whichever way it falls. `@splitQuery` is the directive that takes a child field out of its parent's SQL statement and fetches it for every parent in one batch statement.

The schema in question, from the `scenarios/as-connection` fact document with the directive removed:

```graphql
type Film @table(name: "film") {
    # With @splitQuery this generates. Without it, as here, the three accounts below part ways.
    actorsConnection(first: Int, last: Int, after: String, before: String): ActorsConnection!
        @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        @defaultOrder(primaryKey: true)
}
```

## The accounts

* **The fact store: always split.** `graphitron_producer_rule` gives a child whose target is wrapped in a connection a producer of its own, in the branch commented "a child connection, which is always split", with or without the directive.
* **The classifier: refused.** `FieldBuilder`'s table-backed child arm yields a directive conflict, "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics", and `docs/manual/how-to/split-vs-inline.adoc` documents it as the one case where `@splitQuery` is required. R1169 puts the routine-backed child under the same refusal.
* **The manual, twice: paged across all parents.** `docs/manual/reference/directives/asConnection.adoc` and `docs/manual/how-to/connections.adoc` say `@asConnection` alone on a child list paginates across all parents in one pass. Nothing in the tree does that.
* **The architecture page: silent.** `docs/architecture/reference/code-generation-triggers.adoc`, under "Producer", lists what makes a child start a query of its own, and a connection is not on the list.

Two things already lean one way. A connection over a union or an interface is split with nothing written (`Address.occupantsConnection` in the same fact document). And where a shape leaves no choice of delivery, a child `@service` or a table-bound child under a record-backed parent, `@splitQuery` is implied and writing it draws a redundancy warning. A child connection has no inline form either.

## What each answer asks for

Measured while speccing R1169, for a `@routine` child: with `@splitQuery @asConnection` the store claims a `ROUTINE` producer starting at `CHILD` with a `CONNECTION` result, and with `@asConnection` alone it states a `SELECT` producer starting at `CHILD` and no routine claim, because `graphitron_producer_claim_rule` claims a child routine only under `@splitQuery`. So the store's own rule is half applied.

* **Always split.** The classifier's table-backed refusal and R1169's routine twin dissolve. The classifier needs a delivery trigger for a paged child (`DeliveryFact.Trigger`, `DeliveryFactRelation`), which it lacks. The routine claim rule reads the connection wrapper as it reads `@splitQuery`. A paged child with nothing to key a batch on (a same-table child with no key, a routine child with no parameter bound to a parent column) needs its refusal under the new spelling. And it has to be decided whether `@splitQuery` beside `@asConnection` then draws the redundancy warning, since every consumer schema writes both today.
* **Required.** The refusal becomes one stored defect for the rule, across child shapes, and the producer rule stops stating a producer for a field that is refused.
* **Either way**: the "Producer" section states the rule, and `asConnection.adoc`, `connections.adoc` and `split-vs-inline.adoc` say one thing.
