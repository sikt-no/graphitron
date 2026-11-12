---
id: R1169
title: "Connection pagination on a routine-backed child field"
status: Spec
bucket: feature
priority: 5
theme: routine
depends-on: []
created: 2026-11-11
last-updated: 2026-11-12
---

# Connection pagination on a routine-backed child field

## Goal

A child field whose rows come from a table-valued function can be paged as a connection, filtered, and ordered by the client. `@asConnection` on a `@routine` child field that is fetched in a query of its own generates the same paged, filtered, ordered connection with `totalCount` that it generates on a table-backed child, with the parent row feeding the function through `$source`. Today the connection fails the build as deferred, and a filter or an order argument on the same field crashes the generator, so an author who needs the shape writes a `@service` that calls an array-taking function once per batch and rebuilds the connection, the filters, the ordering and the cursors by hand, and every new filter or sort key then has to be added in two places.

The shape, as a minimal pair on the sakila example's `Actor` type. `@splitQuery` is the directive that takes a child field out of its parent's SQL statement and fetches it for every parent in one batch statement; every child connection is written with it today.

```graphql
type ActorFilm {
    filmId: Int    @field(name: "FILM_ID")
    title:  String
}

type Actor @table(name: "actor") {
    # Generates today (Actor.filmsSplit in the sakila example): one batch statement for all
    # actors, a plain list for each.
    filmsSplit(minLength: Int!): [ActorFilm!] @splitQuery
        @routine(name: "films_for_actor", argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
        @defaultOrder(fields: [{name: "film_id"}])

    # This item: the same field paged, filtered on a result column, and ordered by the client.
    filmsSplitConnection(minLength: Int!, title: String, sort: ActorFilmOrderBy @orderBy): [ActorFilm!]
        @splitQuery @asConnection(defaultFirstValue: 10)
        @routine(name: "films_for_actor", argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
        @defaultOrder(fields: [{name: "film_id"}])
}
```

When this lands, each actor's `filmsSplitConnection` is a connection over that actor's function result. `first` and `after` page it. The `sort` argument, or the `@defaultOrder` when it is omitted, supplies the cursor columns; a function result has no primary key to fall back on, so one of the two must be there. `title` filters the function's rows like any argument naming a column of the result, and a `@condition` composes the same way. `totalCount` counts the function's rows for that actor after filtering. All actors' pages come from one batch statement, and all their counts from one more.

Two neighbouring outcomes ride the same change, because the same two guards hold them back:

* The unpaged form with a filter or an order argument, `filmsSplit(minLength: Int!, title: String, sort: ActorFilmOrderBy @orderBy)`, generates. Today it crashes the generator.
* `@asConnection` on a routine child with no `@splitQuery` reports what the same spelling reports on a table-backed child: add `@splitQuery`. Today it says the feature is deferred.

The request came from a consumer schema (GitHub issue 577) whose field is the paged shape with a filter input and an `@orderBy` argument, written without `@splitQuery`.

## Evidence

Measured on trunk at `1edfd5b` with a throwaway spike, none of which is committed. The spike made two edits: it deleted the Connection gate in `FieldBuilder.classifyChildRoutineChain`, and it reduced the routine-node surface pin in `ChildField.BatchedTableField`'s constructor to "exactly one routine node". It then added fixtures beside `Actor.filmsSplit` in the sakila example and ran them against the seeded database (`minLength: 50`; four actors, PENELOPE, NICK, ED, and JOAN who is cast in nothing).

| Query | Result per actor |
|---|---|
| connection, `first: 1` | nodes `[1]`, `[1]`, `[5]`, `[]`; `totalCount` 2, 2, 1, 0; `hasNextPage` true, true, false, false |
| connection, `first: 5, after:` PENELOPE's `endCursor` | nodes `[3]`, `[4]`, `[5]`, `[]`; `hasNextPage` false throughout |
| connection with `sort: {field: FILM_ID, direction: DESC}`, `first: 1`, `minLength: 0` | nodes `[3]`, `[4]`, `[5]`, `[]`; `totalCount` 3, 2, 2, 0 |
| connection with `title:` one title and a `sort` | one node for the one actor cast in that film, `totalCount` 1; 0 for the others |
| list with `title:` one title | the one row for the actor cast in it, empty lists for the others |
| list with `sort: {field: TITLE, direction: DESC}` | each actor's films in descending title order |

So downstream of those two guards the batched machinery already renders the whole shape: `BatchedRowsFragments.connectionTail` and `windowedPageTail` take the lateral call through the same `fromBridgeAndParentJoin` topology the list form uses, for the page statement and for the count source alike, and `LauncherCommands.batchedResultOf` maps any connection-wrapped `BatchedTableField` to `ResultShape.Connection`. No emitter change was needed. What the spike did not do: run the generator's own test tier, count statements, or exercise a chain with `@reference` hops.

What the tree does today, without the spike:

* The connection is refused by `FieldBuilder.classifyChildRoutineChain` with `Rejection.deferred("Connection pagination at a child-positioned routine-backed field is not yet supported; the root position paginates")`, pinned by `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat`.
* A filter or an `@orderBy` argument on a routine child, with or without `@splitQuery`, reaches the leaf constructors' surface pins and escapes as an uncaught `IllegalArgumentException`: the build fails with "TableField with a routine-node path must be the inline correlated chain shape (exactly one lateral routine node, no filters/argument-ordering/pagination, non-Connection); other routine chain shapes classify as typed Deferred" (or its `BatchedTableField` twin), with no field coordinate. Confirmed for the inline form with a `title: String` argument on a copy of `Actor.films`. The message's own premise, that the classifier defers those shapes first, stopped holding when the routine read surface opened to filters and ordering at the root.
* `docs/manual/reference/directives/routine.adoc` says pagination is the one axis that does not compose at a child position. It is one of three.
* The fact store already states the paged shape. A throwaway fact document gave the rows below. The third row is this item's shape and needs no store change. The fourth is the spelling this item refuses; what the store says about it belongs to R1178.

| Field | `graphitron_producer_claim` | `graphitron_occurrence_producer` | `graphitron_occurrence_producer_result` |
|---|---|---|---|
| `films` (inline list) | none | none | none |
| `filmsSplit` | `ROUTINE` | `ROUTINE`, `CHILD` | `RECORD_LIST`, `films_for_actor` |
| `@splitQuery @asConnection` | `ROUTINE` | `ROUTINE`, `CHILD` | `CONNECTION` |
| `@asConnection` alone | none | `SELECT`, `CHILD` | `CONNECTION` |

## Decisions

* **A routine child connection is written with `@splitQuery`, as every child connection is today.** The table-backed child arm of the classifier refuses `@asConnection` without `@splitQuery`, and `docs/manual/how-to/split-vs-inline.adoc` documents that as the one case where the directive is required. This item puts the routine child under that rule instead of under a deferral of its own. Whether the rule itself should go, so that a child connection is split with nothing written, is a question about every child shape at once, and the tree gives three different answers to it; R1178 carries it. If that ruling makes `@splitQuery` optional, this item's field keeps working unchanged and the refusal below dissolves there together with the table-backed one.
* **The batched leaf's surface pin lifts whole.** `ChildField.BatchedTableField` pins a routine-bearing path to no filters, no order argument, no pagination and no connection. The spike exercised all four through the existing emit, and the consumer's field carries three of them, so lifting one and leaving the others would hand the consumer the next crash.
* **The inline leaf keeps its pin.** A filter or an order argument on a routine child that is *not* split still cannot generate: an order argument on an inline child list is lowered for no child shape, and nobody has shown the inline lateral rendering a filter. That the pin fires as a crash instead of a located error is a defect of its own, R1179.
* **An uncorrelated routine child needs no new ruling.** A connection requires `@splitQuery`, and `@splitQuery` on a routine child with no parameter bound to a parent column is already a directive conflict, there being nothing to key the batch on.
* **Every single-routine chain position is in scope.** The gate and the pin are per leaf, not per chain shape, so the routine may end the path (rows are the function's result), be followed by `@reference` hops (rows are a catalog table's, ordered by its primary key), or follow hops. Chains with more than one routine node stay with R447.

## Implementation

* `FieldBuilder.classifyChildRoutineChain`: delete the Connection gate and its comment. With `@splitQuery` the method already reaches the `ChildField.BatchedTableField` arm and already passes `tfc.filters()`, `tfc.orderBy()` and `tfc.pagination()`; nothing is added there. Where the wrapper is a connection and `@splitQuery` is absent, yield the verdict the table-backed arm yields for the same spelling, `Rejection.directiveConflict(List.of("asConnection"), "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics")`, before the `TableField` mint, whose constructor would otherwise throw. The wording is the existing rule's, repeated at a second site on purpose: it is a transitional walk verdict that R1178 either dissolves or moves into the store as one defect, and a shared helper would only give it a second home in code that is leaving. Update the method's javadoc, and the paragraph in `routineChainVerdict`'s javadoc that says the child read chain carries its own Connection deferral.
* `ChildField.BatchedTableField`'s compact constructor: reduce the routine-node surface pin (the block commented "(5) leaf-specific surface pins") to its first clause, exactly one routine node, and reword its message and comment to say that. The `OnLateralArgs` empty-key check after it stays. `ChildField.TableField`'s twin pin is not touched.
* `GraphitronSchemaValidator.validateBatchedTableField`: an unordered routine child connection would, as the code reads, draw two errors, the cross-cutting "paginated fields must have ordering (add @defaultOrder or @orderBy)" and this method's "@splitQuery connections require a non-empty ORDER BY (add @defaultOrder, @orderBy, or a primary key on the target table)", the second offering a primary key on a function result. Confirm it first. If it holds, narrow this method's guard to the case it alone covers (a `Fixed` ordering with no entries), so the shape reports once and is not told to key a function. That also turns two errors into one for a table-backed split connection over a table with no primary key, which is the same duplicate.
* Nothing in `plan/` or `render/` is expected to change; the spike generated, compiled and executed the shape with those untouched. The generator's own test tier was not run under the spike, so a census, ratchet or pin test that counts leaves or shapes (`DeliveryFactPinTest`, `EmissionCensusTest`, `CommandSeamRatchetTest` are the likely ones) may need its expectation moved; that is expected fallout, not a design change.
* No store change. The store already claims the split, paged routine child as a `ROUTINE` producer starting at `CHILD` with a `CONNECTION` result.

## User documentation (first-client check)

`docs/manual/reference/directives/routine.adoc` changes in three places. In "The read surface", the paragraph that begins "At a child position, pagination is the one axis that does not compose yet" becomes:

> At a child position the read surface composes on the field that is fetched in a query of its
> own, the one carrying `@splitQuery` (see "Fetch forms and @splitQuery"): filters, an `@orderBy`
> argument and `@asConnection` all apply there as they do at the root. A child connection needs
> `@splitQuery`, as it does on a table-backed child. On the inline form a `@defaultOrder` is the
> whole read surface. On a `Mutation` field `@asConnection` is a build error, the write's
> post-commit re-read being keyed rather than paged.

"Fetch forms and @splitQuery" gains, after its two paragraphs:

> The split form is also where a routine child pages. Each parent gets its own page and its own
> `totalCount`, from one batch statement for the pages and one for the counts:
>
> ```graphql
> type Actor @table(name: "actor") {
>     filmsSplitConnection(minLength: Int!, title: String, sort: ActorFilmOrderBy @orderBy): [ActorFilm!]
>         @splitQuery @asConnection(defaultFirstValue: 10)
>         @routine(name: "films_for_actor", argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
>         @defaultOrder(fields: [{name: "film_id"}])
> }
> ```
>
> `minLength` is spent on the function's parameter. `title` names a column of the function's
> result and filters it. `sort` orders the page and is what the cursor encodes; without it the
> `@defaultOrder` does both.

In "Constraints", the last bullet's closing clause "at a child position `@asConnection` is reported as deferred" becomes "at a child position `@asConnection` needs `@splitQuery`, and filters and an `@orderBy` argument compose on the `@splitQuery` form only".

If those three read simply, the design holds: nothing new is asked of the author beyond the directive a child connection already takes. The example is the sakila fixture below, so the page can select it from the fact document instead of printing a snippet nothing checks.

## Tests

* **Fact document**, `scenarios/routine-child-split` under `graphitron-model/src/test/resources/facts/`: a routine child fetched in a query of its own, as a list and as a connection. It states `Actor.films` (inline), `Actor.filmsSplit`, the paged field of the Goal, a routine-then-hop chain and a hops-then-routine chain, both split and paged, and claims the rows of `graphitron_producer_claim`, `graphitron_occurrence_producer` and `graphitron_occurrence_producer_result` for each, the first three as measured above, with the inline field claimed absent. No existing document states a `$source` routine child with `@splitQuery`; `scenarios/producer-kinds` is the keystone for producer kinds and has no routine child, so the new document's header names that as its variation. Sitting under `scenarios/`, it is also generated by `GenerateTestSchemas` and compiled, which is the evidence that every chain position generates code that compiles.
* **Execution fixtures** in the sakila example beside `Actor.filmsSplit`: `Actor.filmsSplitConnection` as in the Goal (routine terminus, `title` filter, `sort` argument over an order input on `film_id` and `title`), and `Actor.recentFilmsSplitConnection`, the paged twin of `Actor.recentFilmsSplit` (the hop to `film` after the routine, primary-key order).
* **Execution tests**, in `RoutineFieldExecutionTest` or beside the `Film.actorsConnection` tests in `GraphQLQueryTest`, which already owns the statement counter:
  * first page per parent with `totalCount` and `hasNextPage`, the actor with no films included (an empty page counting 0), to the values in the Evidence table;
  * the `after` cursor pages every parent forward;
  * statement count for all four actors: two without `totalCount` (the root and one batch page statement), three with it. This is the proof of batching and the one thing the spike left unmeasured;
  * a descending `sort` changes the first page and the cursor, and `title` narrows both the page and `totalCount`;
  * on the catalog-terminus field, a column only `film` has (`description`) is returned and `totalCount` counts the joined chain.
* **The refusal**: `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat` is rewritten in place for the verdict that replaces the deferral (a directive conflict naming `asConnection`, the table-backed arm's message). It is the same case with a new answer, not a new case in a truth table that takes none.
* **The pin**: whichever test constructs a `BatchedTableField` over a routine path gains the one remaining refusal, two routine nodes.
* `FixtureWarningsGateTest` matches build warnings naming `films_for_actor`; the new fixtures may add to what it expects.

## Retired vocabulary

* The rejection text "Connection pagination at a child-positioned routine-backed field is not yet supported; the root position paginates".
* The manual's "pagination is the one axis that does not compose yet" and "at a child position `@asConnection` is reported as deferred".
* On the batched leaf, the surface description "no filters/argument-ordering/pagination, non-Connection" and the claim that "other routine chain shapes classify as typed Deferred". Both stay true of, and stay written on, the inline `TableField` pin until R1179.

## Roadmap entries

* R1178, filed with this spec: whether a child connection is fetched in a query of its own without `@splitQuery`. Not a dependency; this item lands under the rule as it stands.
* R1179, filed with this spec: a filter or an order argument on an inline routine child crashes the generator.
* R447's parked note on connection pagination over a catalog-terminus chain is replaced by a pointer here: the root form shipped with the routine read surface, and the child form is this item.
* R662 says `@asConnection` over a routine chain is rejected or deferred; when this lands that is true of neither position, and its note on cursor columns spanning two aliases becomes the live constraint.

## Other solutions we've considered

* **`@asConnection` alone implies the split.** The store's producer rule already reads that way, the consumer's SDL is written that way, and `@splitQuery` beside a connection carries no choice, there being no inline form of a child connection. It is not taken here because it is not a routine question: it changes what a table-backed child accepts, needs a delivery trigger the classifier's `DeliveryFact.Trigger` does not have, and has to reconcile three manual pages that disagree. Doing it for routine children alone would leave two child shapes answering the same question differently. R1178 is that decision, for every child shape at once.
* **Paging inside the parent's statement.** A correlated multiset cannot carry per-parent pagination: the cursor is an argument of the child field, and the page would have to be cut inside each parent's nested cell. This is why no child connection is inline.
* **List-shaped routine parameters**, so the function takes every parent's key at once, as the consumer's hand-written workaround does. The batch statement already calls a single-key function once per distinct parent key through the lateral join, so the array form buys nothing here and would need the routine binding to learn list parameters.
* **Folding this into R447.** That item is breadth of chain shapes (several routine nodes, `@lookupKey`, record-backed and interface parents). This is the read surface on a shape that already emits, and it has a consumer waiting.

## Architect consultation

Consulted at pickup and at the `@splitQuery` fork, before the spike. It found that the store's producer rule already treats every child connection as split, while the classifier refuses one without `@splitQuery` and three manual pages give three accounts, and that no architecture page states the rule; taken as R1178, with the accounts and the measured store rows recorded there, and named under Decisions and Other solutions. It argued that requiring `@splitQuery` writes the walk's answer into a new arm the store contradicts, and that a ruling should come first; partly taken: the plan keeps the rule every child connection is under today so the consumer's shape is not held behind a cross-shape ruling, states the refusal as transitional, and makes the item indifferent to how the ruling falls. It advised against a shared helper for the repeated verdict, the walk being transitional; taken. It predicted that the store states an unsplit routine connection child as a `SELECT` producer; confirmed by measurement and recorded under Evidence and in R1178. It said classification evidence belongs in a fact document and that the truth tables take no new cases; taken under Tests. It listed the manual pages a change of the general rule must move together (`asConnection.adoc`, `split-vs-inline.adoc`, `connections.adoc`, `code-generation-triggers.adoc`); recorded in R1178, since this item changes `routine.adoc` only. It did not see the leaf constructors' surface pins; the spike found them.
