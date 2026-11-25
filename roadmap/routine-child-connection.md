---
id: R1169
title: "Connection pagination on a routine-backed child field"
status: Ready
bucket: feature
priority: 5
theme: routine
depends-on: []
created: 2026-11-11
last-updated: 2026-11-25
---

# Connection pagination on a routine-backed child field

## Goal

A child field whose rows come from a table-valued function can be paged as a connection, filtered,
and ordered by the client, written as the consumer wrote it plus the `@defaultOrder` its rows need. `@asConnection` on a `@routine`
child field generates the same paged, filtered, ordered connection with `totalCount` that it
generates on a table-backed child, with the parent row feeding the function through `$source`, and
with no `@splitQuery` written: a child connection is always fetched in a query of its own, so the
directive is inferred (Alf, 2026-11-25). Today the connection fails the build as deferred, and a
filter or an order argument on the same field crashes the generator, so an author who needs the
shape writes a `@service` that calls an array-taking function once per batch and rebuilds the
connection, the filters, the ordering and the cursors by hand, and every new filter or sort key then
has to be added in two places.

The consumer is tilgangsstyring (GitHub issue 577):

```graphql
type FeideApplikasjon implements Node @table(name: "feide_applikasjon") {
  brukerdelegeringer(
    filter: BrukerdelegeringerFilterInput
    orderBy: BrukerdelegeringerOrderByInput @orderBy
  ): [Brukerdelegering!]!
    @routine(name: "auth.delegeringstak_for_applikasjon", argMapping: "pApplikasjon: $source.subjekt_id")
    @asConnection(defaultFirstValue: 10)
    @defaultOrder(fields: [{name: "..."}])  # added: Brukerdelegering is bound to no table, so no key orders it
}
```

`Brukerdelegering` reads the function's result columns, the filter input composes with `@condition`,
and the order is an `@orderBy` input. As filed the field has no `@defaultOrder`. The default order of a
list is the primary key of the table its target type is bound to, and `Brukerdelegering` is the
function's result type, bound to no table, so the consumer adds a `@defaultOrder`, naming the result
columns a client who leaves `orderBy` out is paged by. When this lands, each parent's
`brukerdelegeringer` is a connection over that parent's function result: `first` and `after` page
it, the filter and its `@condition` narrow it, `orderBy` orders it and is what the cursor encodes,
and `totalCount` counts the parent's rows after filtering. All parents' pages come from one batch
statement, and all their counts from one more.

Because the inference is one rule for every child connection, it lands for the table-backed child
too: `@asConnection` on a table-backed child list without `@splitQuery`, which the build refuses
today ("add @splitQuery"), generates the same per-parent connection. A `@splitQuery` written on a
child connection is admitted and warned redundant. Two neighbouring outcomes ride the same change,
because the same two guards hold them back: the unpaged split form with a filter or an order
argument generates, where it crashes today.

## Rulings this item lands under

* **The walk exception** (Alf, 2026-11-25; `pipeline-overview.adoc`, the paragraph on the walk being
  drained): paging a child connection is opened on the walk, as the one exception to the walk taking
  no new features. It covers exactly: the paged-child delivery trigger below, lifting
  `classifyChildRoutineChain`'s Connection deferral and the table-backed arm's directive conflict, and
  lifting `ChildField.BatchedTableField`'s routine surface pin. It covers no new walk verdict and no
  walk validator change. Anything else this shape turns up, R1179's inline filter crash included, is
  stated in the store. The item's fact-document and execution tests are the parity check the child
  flip ("A batched child's fetcher is emitted from the store") must pass unchanged.
* **A child connection is always split** (Alf, 2026-11-25; `code-generation-triggers.adoc`,
  "Producer"): `@splitQuery` is inferred on a child connection, and written there it is admitted with
  the `SPLIT_QUERY_REDUNDANT` warning. This answers R1178's question, and R1178 is discarded: the
  refusal for a paged child with nothing to key a batch on is this item's, and the store's claim for
  a child routine is the R876 node "A split child `@routine` is a path routine".
* **Land the warning** (Alf, 2026-11-25): the warning ships with a changelog line, and FS-plattform's
  sites are counted below.
* **`@defaultOrder` is required only where the target type's table has no primary key to stand in for
  it** (Alf, 2026-11-25: "We only require @defaultOrder if there is no PK we can use as the default
  @defaultOrder", and "we look at the pk of the target type, not all possible types"). The default
  order of a list or connection is the primary key of the table its target type is bound to. A target
  type bound to no table, as a function's result type is, or bound to a keyless table, has none, and
  with no `@defaultOrder` it is refused where an omitted order argument would leave it ordered by
  nothing. A target type bound to a table with a primary key keeps that key as its fallback, a routine
  child whose path hops to such a table included, and a non-null order argument can never be omitted.
  No key is inferred by matching a function's columns against other tables' keys. The remedy named
  is `@defaultOrder`.

## Evidence

**The split spelling generates and executes.** A throwaway spike on trunk at `1edfd5b` deleted the
Connection gate in `FieldBuilder.classifyChildRoutineChain` and reduced the routine surface pin in
`ChildField.BatchedTableField`'s constructor to "exactly one routine node", added fixtures beside
`Actor.filmsSplit` in the sakila example, and ran them against the seeded database (`minLength: 50`;
four actors, PENELOPE, NICK, ED, and JOAN who is cast in nothing):

| Query | Result per actor |
|---|---|
| connection, `first: 1` | nodes `[1]`, `[1]`, `[5]`, `[]`; `totalCount` 2, 2, 1, 0; `hasNextPage` true, true, false, false |
| connection, `first: 5, after:` PENELOPE's `endCursor` | nodes `[3]`, `[4]`, `[5]`, `[]`; `hasNextPage` false throughout |
| connection with `sort: {field: FILM_ID, direction: DESC}`, `first: 1`, `minLength: 0` | nodes `[3]`, `[4]`, `[5]`, `[]`; `totalCount` 3, 2, 2, 0 |
| connection with `title:` one title and a `sort` | one node for the one actor cast in that film, `totalCount` 1; 0 for the others |
| list with `title:` one title | the one row for the actor cast in it, empty lists for the others |
| list with `sort: {field: TITLE, direction: DESC}` | each actor's films in descending title order |

Downstream of those two guards the batched machinery already renders the whole shape:
`BatchedRowsFragments.connectionTail` and `windowedPageTail` take the lateral call through the same
`fromBridgeAndParentJoin` topology the list form uses, for the page statement and for the count
source alike, and `LauncherCommands.batchedResultOf` maps any connection-wrapped `BatchedTableField`
to `ResultShape.Connection`. No emitter change was needed.

**On trunk the store stands in its way nowhere.** Since the spike, the store's verdict stops
`validate` and `generate` before the walk classifies anything, so a stored defect on the shape would
refuse it whatever the walk does. A throwaway fact document captured on trunk at `43166204db` six
`Actor` fields over `films_for_actor` and read what the store holds for them:

| Field | Stored defect | `graphitron_occurrence_producer` |
|---|---|---|
| the consumer's shape with `@splitQuery` (filter input, `@orderBy` input, no `@defaultOrder`) | none | `ROUTINE`, `CHILD` |
| the consumer's shape as written, no `@splitQuery` | none | `SELECT`, `CHILD` |
| split, no order argument and no `@defaultOrder` | `LIST_ORDER_UNDETERMINED`, detail `public.films_for_actor` | `ROUTINE`, `CHILD` |
| the sakila spelling (split, `title`, `sort`, `@defaultOrder`) | none | `ROUTINE`, `CHILD` |
| an inline routine list with an `@orderBy` argument | `ORDER_CHOICE_ON_INLINE_LIST` at the argument | none |
| a table-backed child connection, no `@splitQuery` | none | `SELECT`, `CHILD` |

So no filter-family, depth or result-shape code fires on the paged routine child, split or not; an
unordered one is refused by the stored order rule, which names a primary key the function cannot
have; and the inline form's order argument is already refused in the store. The two spellings of one
field get different producer claims, `SELECT` unsplit and `ROUTINE` split, because
`graphitron_producer_claim_rule` claims a child routine only under `@splitQuery`. The R876 node "A
split child `@routine` is a path routine" makes the two converge: a child routine claims no `ROUTINE`
producer in either spelling, its producer being the split re-query and the routine the path's first
step. The unsplit spelling has no spike evidence: it needs the delivery
trigger below before the walk takes it.

**What the walk does today:**

* The connection is refused by `FieldBuilder.classifyChildRoutineChain` with
  `Rejection.deferred("Connection pagination at a child-positioned routine-backed field is not yet supported; the root position paginates")`,
  pinned by `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat`.
* A routine chain is batched only on the `@splitQuery` half of its markers
  (`DeliveryFactRelation`: "the @pivot and @routine chains batch on the @splitQuery half alone"), and
  a table-backed child connection without `@splitQuery` draws the directive conflict "@asConnection
  on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection
  semantics".
* A filter or an `@orderBy` argument on a routine child reaches the leaf constructors' surface pins
  and escapes as an uncaught `IllegalArgumentException` with no field coordinate.

**FS-plattform's exposure to the warning**, read-only on `fs-main` at `946fc4aa20`, over `.graphql`
and `.graphqls`: of 107 child fields carrying `@asConnection`, 106 also write `@splitQuery`, which the
old rule required, and each will draw `SPLIT_QUERY_REDUNDANT`: sis 84, tilgangsstyring 16, opptak 4,
utdanningsregisteret 1, fskode 1.

## Decisions

* **A child connection with nothing to key a batch on is refused in the store, before the walk.**
  Lifting the "add @splitQuery" conflict lets an unsplit child connection reach the batched arm, and
  the walk's keyless refusals fire only under a written `@splitQuery`, so without a stored refusal a
  keyless field would reach the batched leaf's `OnLateralArgs` check and throw. The store already
  refuses one of the two shapes and states the other under an existing code (Alf, 2026-11-25: the
  stored refusal moves here from R1178):
  * A table-backed child on its parent's own table with no path written is `NO_ROUTE_TO_TARGET`
    today, whose statement covers "a field with no path written [that] starts … on its target's own
    table, so there is no join to infer". It reports under that code, spelled with or without
    `@splitQuery`, and draws no second code.
  * A routine child connection none of whose parameters is bound to a column of the parent's row is
    `NO_KEY_FROM_PARENT`, whose rule is the same one at another kind of parent: a field fetching its
    own rows takes its key from its parent, and here nothing keys them. Its statement widens from
    "A field fetches its own rows under a parent holding an object, and nothing the parent holds, no
    column of a record and no member returning one, keys those rows." to "A field fetches its own rows
    in a query of its own, and nothing the parent holds keys those rows: under a parent holding an
    object, no column of a record and no member returning one; under a parent's table row, a routine
    none of whose parameters is bound to one of its columns." Its guidance gains a paragraph for the
    routine with its next step, bind a parameter to `$source.<column>`, and never mentions
    `@splitQuery`.
  The store's verdict stops `validate` and `generate` before the walk classifies anything, so for
  these fields neither `uncorrelatedSameTableSplitMessage`, the routine arm's `DIR_SPLIT_QUERY`
  refusal nor the `OnLateralArgs` `IllegalArgumentException` is reached, and the exception still adds
  no walk verdict. The changed statement is a changed message, so its commit is `feat`; it refuses
  nothing the walk accepts today.
* **One delivery trigger for every child connection.** The walk's delivery relation gains a
  paged-child trigger: a child field whose target is wrapped in a connection is batched, whatever
  markers it carries. It is stated once for every child shape, as the rule is one rule
  (`defects-as-facts.adoc`, "The supertype"); a routine-only trigger would state it per shape.
* **The batched leaf's surface pin lifts whole.** `ChildField.BatchedTableField` pins a routine path
  to no filters, no order argument, no pagination and no connection. The spike exercised all four
  through the existing emit, and the consumer's field carries three of them.
* **The inline leaf keeps its pin.** An inline routine list with a filter still cannot generate, and
  its crash is R1179's. Its order argument is already refused in the store
  (`ORDER_CHOICE_ON_INLINE_LIST`), so R1179 is left with the filter.
* **No new walk verdict.** The unsplit spelling takes the batched arm instead of being refused, and
  the missing order is the stored `LIST_ORDER_UNDETERMINED`, which stops the build before the walk's
  own ordering checks. `GraphitronSchemaValidator` is not touched.
* **The stored order refusal stops naming a primary key where there is none.** `LIST_ORDER_UNDETERMINED`
  includes the shared ordering guidance, whose next step offers "a column, index or primary key the
  table has". A target type bound to no table has none; the code gets a next step of its own: add
  `@defaultOrder` naming the result's columns.
* **An omittable order argument whose target type has no keyed table needs a `@defaultOrder`.** The
  consumer's field is this shape. Its order argument's runtime fallback is `OrderBySpec.None` (no
  `@defaultOrder`, no primary key), neither the store nor the walk refuses it today (an order argument
  counts as an order stated), and when a client leaves `orderBy` out the generated page orders by
  nothing and calls `seek()` with zero fields, which jOOQ 3.20 refuses. Under the ruling,
  `LIST_ORDER_UNDETERMINED` covers it, and the consumer adds a `@defaultOrder` naming result columns.
  The same hole on a list or connection whose target type is bound to a keyless table is refused by the
  same rule. A function returning `SETOF` a table needs no clause of its own: its result type is bound
  to that table only by its own `@table`, the store deriving no binding from the function, so with the
  directive written it orders by the table's key and without it it is refused like any other.
  It is a new refusal, so it lands as a breaking change. FS-plattform's reach, read-only at
  `946fc4aa20`: 17 fields carry a nullable `@orderBy` argument and no `@defaultOrder`, none of them
  routine-backed, and every one reads a table with a primary key in its module's jOOQ catalog (sis 13
  fields over 7 tables, opptak 2, utdanningsregisteret 2), so no FS field is refused. The consumer's
  field is not in FS yet; it is a hand-written `@service` there today.
* **Every single-routine chain position is in scope.** The trigger and the pin are per leaf, not per
  chain shape, so the routine may end the path, be followed by `@reference` hops, or follow hops.
  Chains with more than one routine node stay with R447.

## Implementation

The walk, under the exception:

* `DeliveryFact.Trigger` gains a paged-child arm, and `DeliveryFactRelation` produces
  `Batched(paged child)` for a child field whose target is connection-wrapped, before the marker
  reads. The classifier's batched-leaf arms agree with it, and `DeliveryFactPinTest` holds the two
  equal. `DeliveryFact.leafDerivedOf`, the crosswalk the pin compares against, tells the paged-child
  trigger from `Authored` by the leaf's target wrapper: a `BatchedTableField` over a connection
  derives the paged-child trigger whatever markers it carries, a `@pivot` connection included, so a
  connection written with `@splitQuery` pins as the paged child, not as authored.
* `FieldBuilder.classifyChildRoutineChain`: delete the Connection gate and its comment, and take the
  `ChildField.BatchedTableField` arm for a connection whether or not `@splitQuery` is written. The
  arm already passes `tfc.filters()`, `tfc.orderBy()` and `tfc.pagination()`. Update the method's
  javadoc and the paragraph in `routineChainVerdict`'s javadoc on the child chain's Connection
  deferral.
* The table-backed child arm (`classifyObjectReturnChildField`): the "add @splitQuery" directive
  conflict goes, and a connection-wrapped child takes the batched arm.
* `ChildField.BatchedTableField`'s compact constructor: reduce the routine surface pin (the block
  commented "(5) leaf-specific surface pins") to its first clause, exactly one routine node, and
  reword its message and comment to say that. The `OnLateralArgs` empty-key check after it stays.
  `ChildField.TableField`'s twin pin is not touched.
* Nothing in `plan/` or `render/` is expected to change. A census, ratchet or pin test that counts
  leaves or shapes (`DeliveryFactPinTest`, `EmissionCensusTest`, `CommandSeamRatchetTest`) may need
  its expectation moved.

The store:

* `graphitron_fetcher_entry_defect_rule` gains a second `SPLIT_QUERY_REDUNDANT` arm for a child
  connection, rather than a branch of the existing one, which inner-joins `graphitron_tabletype` and
  so never sees a target bound to no table (a function's result type, `ActorFilm` or
  `Brukerdelegering`). The new arm reads the connection off `graphitron_field_target.wrapper_type_name`
  at a child position and the target from `graphitron_field_target` alone, keeps the `@service`
  exclusion and drops `@routine` from it, and excludes the fields the first arm already reports, so
  the stage's statement never meets one position twice. The rule holds 7 arms nesting 6 deep, so it
  stays within the bound of seven.
* `graphitron_occurrence_depth_entry_defect_rule`, the keys family that already raises
  `NO_KEY_FROM_PARENT`, gains one arm: a routine child producer, a connection or written with
  `@splitQuery`, whose routine step binds no parameter to a column of the parent's table
  (`graphitron_join_path_step_parameter`), reported at the field, the detail naming the parent's table.
  The rule holds 7 arms nesting 6 deep, so it stays within seven. Its statement in
  `graphitron_defect_type` and `NO_KEY_FROM_PARENT.adoc` change as above.
* `LIST_ORDER_UNDETERMINED.adoc` gets its own next step for a function's result, as above.
* The order rule's `LIST_ORDER_UNDETERMINED` arm widens to a list or connection with no primary key
  to fall back on and no `@defaultOrder`, whose order argument may be omitted. Its commit is a
  breaking change (`feat!`), recording the FS count above.

Fixtures and examples follow the recommended spelling: `@splitQuery` comes off every child
connection in the sakila example, in `code-generation-triggers.adoc`'s examples and in the manual,
and exactly one sakila child connection keeps it as the warning's own fixture, which
`FixtureWarningsGateTest` expects. A `@condition` method on `films_for_actor`'s result is added to
`InputFieldConditionFixtures` for the consumer's filter shape.

## User documentation (first-client check)

* `docs/manual/reference/directives/routine.adoc`, "The read surface": the paragraph that begins "At
  a child position, pagination is the one axis that does not compose yet" becomes: at a child
  position the read surface composes on a field fetched in a query of its own, which a connection
  always is, and filters, an `@orderBy` argument and `@asConnection` apply there as at the root; on
  the inline list form a `@defaultOrder` is the whole read surface. "Fetch forms and @splitQuery"
  gains the consumer's shape as its paged example, written without `@splitQuery`, and "Constraints"
  drops "at a child position `@asConnection` is reported as deferred".
* `docs/manual/how-to/split-vs-inline.adoc` stops calling a child connection the one case
  `@splitQuery` is required: a child connection is always split, and writing the directive there
  draws a redundancy warning.
* `docs/manual/how-to/connections.adoc` and `docs/manual/reference/directives/asConnection.adoc` stop
  saying `@asConnection` alone on a child list "paginates across all parents in a single SQL pass":
  each parent pages, with or without the directive.
* `docs/architecture/reference/code-generation-triggers.adoc`, "Producer", states the rule (the
  ruling's own commit) and its examples drop `@splitQuery` from child connections.
* `roadmap/changelog.md`, in the feat commit that adds the warning: "@splitQuery on a child
  connection is now inferred; written there, it is redundant and warns."
* The ordering ruling is recorded where the order-determinism rule is documented: the shared
  guidance in `graphitron-model`'s `defects/_ordering.adoc` (the fallback is the primary key of the
  table the target type is bound to, and `@defaultOrder` is required where there is none), and the
  architecture page that owns list order, if one states it, in this item's docs commit.
* `docs/manual/reference/directives/defaultOrder.adoc`, "Constraints" (the bullets on
  `primaryKey: true` and on a list with no primary key), and `docs/manual/how-to/sort-results.adoc`,
  "Determinism gates", say a table-valued function's result "declares no key" or has "nothing to fall
  back to". That is made accurate rather than removed: the fallback is the primary key of the table
  the field's target type is bound to; a function's result type is bound to none, so it has no
  fallback unless the field hops to a table or its type writes `@table`, and then the key is that
  table's.

If those read simply, the design holds: the consumer writes the field as they did plus a
`@defaultOrder`, which the build names when it is missing, and nothing else is asked of an author.

## Tests

* **Fact document**, `scenarios/routine-child-connection` under `graphitron-model/src/test/resources/facts/`:
  the consumer's shape and the sakila one, each split and unsplit, a routine-then-hop chain and a
  hops-then-routine chain, and the inline list as a near miss. It claims the facts the child flip
  keeps: the producer's start `CHILD` and result `CONNECTION`, the path's steps and
  `graphitron_join_path_step_parameter` rows, the filter's `graphitron_occurrence_filter_term` rows and
  its `@condition`, the order source, and no defect. It also states both producer claims for one
  field, `SELECT` unsplit and `ROUTINE` split, and its header says those `ROUTINE` rows are what "A
  split child `@routine` is a path routine" replaces, so the parity the flip passes is behaviour, in
  the execution tests, not those rows.
* **Defects documents:** `SPLIT_QUERY_REDUNDANT` on a routine child connection and a table-backed one
  written with `@splitQuery`, and absent on the same fields written without it; one keyless case per
  arm, each spelled with and without `@splitQuery`: a table-backed child connection on its parent's
  own table with no `@reference` draws `NO_ROUTE_TO_TARGET` and no `NO_KEY_FROM_PARENT`, and a routine
  child connection with no parameter bound to `$source` draws `NO_KEY_FROM_PARENT`, while the
  correlated sakila fields draw neither;
  `LIST_ORDER_UNDETERMINED` on an unordered routine child connection, with the next step that names no
  primary key; on an omittable order argument with no `@defaultOrder` whose target type is the
  function's result type, and whose target type is bound to a keyless table; and absent where the
  argument is non-null, where a `@defaultOrder` is written, where the target type is bound to a table
  with a primary key, and on a routine child that hops to such a table (`Film` after
  `films_for_actor`), which orders by that table's key.
* **Execution fixtures** in the sakila example beside `Actor.filmsSplit`: the consumer's shape over
  `films_for_actor` written without `@splitQuery` (an input-object filter whose field carries a
  `@condition`, an `@orderBy` input, and the `@defaultOrder` the ruling asks of it), the sakila
  shape with `title`, `sort` and `@defaultOrder` written with `@splitQuery` as the warning's fixture,
  and the paged twin of `Actor.recentFilmsSplit`, the hop to `film` after the routine, ordered by its
  primary key.
* **Execution tests**, in `RoutineFieldExecutionTest` or beside the `Film.actorsConnection` tests in
  `GraphQLQueryTest`, which already owns the statement counter, for each fixture:
  * first page per parent with `totalCount` and `hasNextPage`, the actor with no films included (an
    empty page counting 0), to the values in the Evidence table;
  * the `after` cursor pages every parent forward;
  * statement count for all four actors, asserted: two without `totalCount` (the root and one batch
    page statement), three with it;
  * the order argument changes the first page and the cursor, and the filter, through its column and
    through its `@condition`, narrows both the page and `totalCount`;
  * the split and the unsplit spelling of the same field answer the same pages;
  * on the catalog-terminus field, a column only `film` has (`description`) is returned and
    `totalCount` counts the joined chain.
* **The refusal that goes:** `GraphitronSchemaBuilderTest.asConnectionOnAChildRoutineChainDefersOnTheChildSeat`
  and the table-backed arm's `@asConnection`-without-`@splitQuery` case are rewritten in place for the
  batched classification. Same cases with new answers; the walk's truth tables take no new case.
* **The pin:** whichever test constructs a `BatchedTableField` over a routine path gains the one
  remaining refusal, two routine nodes.

## Retired vocabulary

* The rejection text "Connection pagination at a child-positioned routine-backed field is not yet
  supported; the root position paginates".
* The directive conflict "@asConnection on inline (non-@splitQuery) TableField is not supported; add
  @splitQuery for batched connection semantics".
* The manual's "pagination is the one axis that does not compose yet", "at a child position
  `@asConnection` is reported as deferred", "the one case `@splitQuery` is required", and
  "paginates across all parents in a single SQL pass".
* On the batched leaf, the surface description "no filters/argument-ordering/pagination,
  non-Connection". It stays true of, and stays written on, the inline `TableField` pin until R1179.

## Roadmap entries

* R1178: discarded, answered by the ruling. Its walk half, its documentation and the stored refusal for a
  paged child with nothing to key a batch on are this item's; the store's claim for a child routine is
  "A split child `@routine` is a path routine", under which the two spellings converge.
* R1179: a filter on an inline routine child crashes the generator. Its order argument is now refused
  in the store; the filter is what is left, and it owes a stored defect, not a walk fix.
* R447's parked note on connection pagination over a catalog-terminus chain points here.
* R662 says `@asConnection` over a routine chain is rejected or deferred; when this lands that is true
  of neither position, and its note on cursor columns spanning two aliases becomes the live
  constraint.
* The R876 node "A batched child's fetcher is emitted from the store" takes this item's fact-document
  and execution tests as its parity check.

## Other solutions we've considered

* **Requiring `@splitQuery`, refused on the walk.** The first draft put the routine child under the
  rule every child connection was under and repeated the table-backed arm's conflict. The ruling that
  a child connection is always split removes the refusal for both shapes, and the consumer's field
  works as written.
* **Generating an order from the order input's first value** when a client omits it. It needs no
  author change, but it orders by something the author never stated, chosen by the accident of enum
  declaration order, against the rule that a list's order is stated.
* **Paging inside the parent's statement.** A correlated multiset cannot carry per-parent pagination:
  the cursor is an argument of the child field, and the page would have to be cut inside each
  parent's nested cell. This is why no child connection is inline.
* **List-shaped routine parameters**, so the function takes every parent's key at once, as the
  consumer's workaround does. The batch statement already calls a single-key function once per
  distinct parent key through the lateral join.
* **Folding this into R447.** That item is breadth of chain shapes; this is the read surface on a
  shape that already emits, and it has a consumer waiting.

## Architect consultation

Consulted at the first pickup and at the `@splitQuery` fork (see git history), and again at this
revision on 2026-11-25. At this revision it raised:

* The unsplit refusal is a debt the exception ruling names, and a routine-only stored code would
  split one rule per shape (`defects-as-facts.adoc`, "The supertype"); and the store emits the unsplit
  form as the `SELECT` child it already states once the child flip lands, settling the question by
  default. Overtaken by the ruling that a child connection is always split: there is no refusal.
* Whether the consumer, who writes no `@splitQuery`, is served by a refusal at all. Taken to Alf; the
  ruling is that they are served by the inference.
* The missing-order refusal the author meets is the stored one, so the "never a primary key" fix
  belongs in its guidance, not in the walk's validator. Taken: the validator is untouched, and the
  guidance is fixed.
* The fact document's planned `ROUTINE` claims are the rows the path-routine node rewrites, so the
  parity check must be behaviour plus the facts the flip keeps. Taken under Tests.
* The exception needs its limits written down. Taken under Rulings.
* The inline pin stays out of scope, and the trial should check whether the store already refuses the
  inline order argument. It does (`ORDER_CHOICE_ON_INLINE_LIST`); recorded under Decisions and in
  R1179.

Consulted a second time on 2026-11-25, on where a function result's default order would come from
once a primary key was ruled the fallback. It raised that a connection's order must identify a row
(`sort-results.adoc`, "Determinism gates"), which a key matched by name against other tables does
not: the consumer's function covers 17 tables' keys by name, ten on a column every row of one
parent's page shares. It also raised that the catalog-wide relation stating such matches
(`sql_name_matched_key_column`) is dissolving, and that a dissolving relation is not re-keyed or
extended to carry a new reader. Alf ruled that the key is the primary key of the target type's table,
not one inferred from other tables; recorded under Rulings and Decisions.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01WkW2JJP3u4Mqb5fJrjVnw4, 2026-11-25)

Question 1 passes. When this lands, a consumer writes `@asConnection` on a child field whose rows
come from a table-valued function, adds the `@defaultOrder` the build names when it is missing, and
gets the same paged, filtered, ordered connection with `totalCount` a table-backed child gets. No
`@splitQuery` is needed there, nor on any other child connection, where writing it now warns. The
outcome is reachable: every symbol, relation, defect code, test, fixture and doc path the spec names
exists as named, the two guards sit where the spec says, and the batched leaf already passes
filters, order and pagination through. Question 2 fails on one point: the plan removes the
refusal that is all that stands between a keyless child connection and the batched leaf today, and
it does not say what replaces it.

1. **A child connection with nothing to key a batch on has no stated outcome once the conflict goes
   (question 2).** Today an unsplit child connection never reaches the batched leaf. The table arm
   refuses it ("add @splitQuery"), and the routine arm defers it. The batched leaf's keyless guards
   run only when the authored marker is present. In `FieldBuilder.classifyObjectReturnChildField`
   the same-table, no-`@reference` refusal is gated on `forcesSplitDelivery`, and its message is
   built from the markers the field carries (`uncorrelatedSameTableSplitMessage`). In
   `classifyChildRoutineChain`, `splitSource` is only derived under `hasSplitQuery`, and the
   uncorrelated-routine refusal names `DIR_SPLIT_QUERY` and advises "drop @splitQuery". Once a
   connection takes the batched arm without the marker, the implementer has two choices. They can
   treat the connection as the marker, so both refusals fire with text about a directive the author
   never wrote; the same-table message then opens with an empty marker list. Or they can leave the
   gates as they are. Then the leaf is built with a null correlation, or with the empty key that
   `BatchedTableField`'s `OnLateralArgs` check throws on as an uncaught `IllegalArgumentException`,
   the crash class this item fixes for filters. R1178 keeps the stored refusal for this case. It is
   Backlog, and this item has an empty `depends-on`. "No new walk verdict" rules out the obvious fix
   in the walk. This is satisfied when the spec says what an author of a keyless unsplit child
   connection meets between this item and R1178, table-backed and routine alike. That means either
   ordering this item after R1178's stored defect, or naming the walk wording the exception covers.
   The Tests list should then carry one keyless case per arm.

2. **The `SPLIT_QUERY_REDUNDANT` arm cannot reach a routine child connection by the change the spec
   describes (question 2, minor).** The arm inner-joins `graphitron_tabletype`, which holds only types
   whose `@table` resolved to a catalog table. A function's result type (`ActorFilm`,
   `Brukerdelegering`) has no row there. So adding a connection branch and dropping `@routine` from
   the exclusion still leaves the routine child connection out, and the planned defects document
   "`SPLIT_QUERY_REDUNDANT` on a routine child connection" would fail. This is satisfied when the
   Implementation bullet says how the connection branch reaches a target bound to no table: the join
   loosened for that branch, or the target read some other way.

Non-blocking, for the author's revision: `DeliveryFactPinTest` compares `DeliveryFact.Batched`
records, trigger included. The new trigger is placed before the marker reads, so a child connection
written with `@splitQuery` changes provenance from `Authored` to the paged-child trigger. That
includes a `@pivot` connection if one reaches the relation. `DeliveryFact.leafDerivedOf` must then
tell the two apart from the leaf's target shape. The Implementation section names the relation and
the pin but not the crosswalk.

#### Author's reply (2026-11-25)

1. **A keyless child connection.** Taken, under Alf's ruling that the stored refusal moves here from
   R1178. The two shapes split by code. A table-backed child on its parent's own table with no path
   written is already `NO_ROUTE_TO_TARGET` in the store, whose statement covers a field with no path
   written starting on its target's own table; it reports under that code with or without
   `@splitQuery`, and draws no second one. A routine child with no parameter bound to the parent is
   `NO_KEY_FROM_PARENT`, its statement widened from a parent holding an object to a parent's table
   row as well, one rule stated once; one new arm in the keys family raises it. The store's verdict
   stops generation before the walk, so neither the empty-marker message nor the `OnLateralArgs`
   exception can be reached, and the exception adds no walk verdict. Recorded under Decisions,
   Implementation and Tests, which carry one keyless case per arm, each with and without
   `@splitQuery`; R1178 keeps only the claim rule.
2. **`SPLIT_QUERY_REDUNDANT` on a target bound to no table.** Taken: the connection case is a second
   arm, reading the connection off `graphitron_field_target.wrapper_type_name` and the target from
   `graphitron_field_target` alone, with no join to `graphitron_tabletype`, so a function's result type
   is reached. It excludes the fields the first arm reports.
3. **The `DeliveryFactPinTest` crosswalk** (non-blocking). Named under Implementation:
   `DeliveryFact.leafDerivedOf` derives the paged-child trigger from the leaf's connection wrapper
   whatever markers it carries, `@pivot` included.

### Round 2: Spec → Ready, signed off (session_01WkW2JJP3u4Mqb5fJrjVnw4, 2026-11-25)

All three round-1 findings are addressed, and the revision's new claims hold against the tree. Both
questions pass.

One precision for the implementer, not blocking because the spec's own tests already pin it: the
new `NO_KEY_FROM_PARENT` arm is worded as a routine step "binds no parameter to a column of the
parent's table". A hops-then-routine chain, which is in scope, has a routine step that departs the
hopped table, so its parameters bind there and never to the parent's. Read literally, the arm would
refuse that chain, although its batch is keyed by the first hop's correlation. The arm should apply
only where the routine is the path's first step (`position` 0 in `graphitron_join_path_step_parameter`).
That is the same domain as the walk's `lateralHead` refusal it replaces. The fact document's
hops-then-routine chain, which claims no defect, holds the implementer to it.
