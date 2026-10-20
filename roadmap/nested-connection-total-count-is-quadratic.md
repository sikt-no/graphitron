---
id: R1070
title: "A nested connection totalCount runs one count per parent, each over every parent's rows"
status: Ready
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-20
---

# A nested connection totalCount runs one count per parent, each over every parent's rows

## Goal

Selecting `totalCount` on a connection nested under a list of N parents costs one grouped count
statement for the whole batch, as it did in Graphitron 9, instead of N statements that each scan the
rows of all N parents. A page of 400 parents with a nested `totalCount` returns in seconds rather
than timing out.

In the sakila example, the query below issues four statements today for two films (the root, the
batched page, one count per film), and for five films it issues seven. After this item it issues three
for any number of films: the root, the batched page, and one grouped count. Each film still reports
its own actor count, and a query that does not select `totalCount` issues no count at all.

```graphql
{ filmById(film_id: ["1", "2", "3", "4", "5"]) { filmId actorsConnection(first: 1) { totalCount } } }
```

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19).
`emnerV2(filter: {eierOrganisasjonskode: "1234"}, first: N) { nodes { kode vurderingsenheter(first: 1) { totalCount } } }`
returns identical data on both versions. Times, v9 against v10: N=50 1.4 s / 2.6 s; N=100 4.4 s /
13.8 s; N=200 3.9 s / 36 s; N=400 4.3 s / over 120 s (timeout). The same curve shows on two other
nested connections; nested `nodes` without `totalCount` is faster on v10 than on v9. Production
operations select nested `totalCount` this way (including one with three nested counts), and with no
`first` cap a request with `first: 1000` holds a database connection for minutes.

In the sis studieprogram area, `studieprogramV2(first: N) { nodes { campuser(first: 50) { totalCount } } }`,
v9 against v10: N=100 about 1 s against 9 s; N=200 1.4 s against 38 to 40 s. A scan of the
consumer's production log finds 139 documents with a nested `totalCount`, some of them among the
most frequent operations.

## Cause

A nested connection under a list of parents is fetched through a DataLoader: graphql-java collects
the parents that ask for the connection into one *batch* and hands their keys to one generated
*rows method*, which reads every parent's page in a single windowed statement. The rows method then
splits the result into one `ConnectionResult` per parent; `ConnectionResult` is the generated
*carrier* every field of the connection type (`edges`, `nodes`, `pageInfo`, `totalCount`) resolves
from.

Two producers build those per-parent carriers, and both bind the same per-parent count shape:

* `SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper` emits `scatterConnectionByIdx`, used
  by every `@splitQuery` connection. Each carrier gets the shared *count source* (a derived table,
  built in `BatchedRowsFragments.windowedPageTail`, projecting `__idx__`, the parent's position in
  the batch, for every row of every parent, with no ordering or cursor) plus the condition
  `__idx__ = i`.
* `MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod`, the nested connection over a
  union or interface. Each carrier gets the shared `pagesTable` (the per-branch `UNION ALL`, also
  cursor-independent, also projecting `__idx__`) plus `idxField.eq(i)`.

The generated `ConnectionHelper.totalCount` resolves once per carrier with
`dsl.selectCount().from(cr.table()).where(cr.condition())`. N parents issue N statements, and since
the derived table spans the whole batch, each statement evaluates it for every key before filtering
to one: the quadratic curve in the evidence. The existing test
`GraphQLQueryTest.splitQueryConnection_totalCount_isLazyOnSelection` pins this cost as "one count
statement per parent".

## Implementation

The batch already knows its peers, so the batch computes the counts once and each carrier reads its
own by index. This extends the shape already in the tree rather than replacing it: the rows method
already captures one batch-scoped count source and binds it on every carrier; what changes is that
the captured value becomes a lazily computed grouped count instead of a table each carrier filters
on its own. A shared *batch count* object is created by the rows method next to the carriers,
computes `select __idx__, count(*) from <count source> group by __idx__` the first time any of its
carriers resolves `totalCount`, and answers every later carrier from memory. It is the first piece of
per-batch shared state in the generated runtime (the carrier and the scatter helpers are otherwise
immutable); it is preferred to a second DataLoader for the reasons under Other solutions.

### `ConnectionResultClassGenerator`

* One count slot on the carrier. A new public nested interface `ConnectionResult.TotalCount` with
  `Integer count(DSLContext dsl)` replaces the resolver's reading of `(table, condition)`. It has
  two implementations, both nested in `ConnectionResult` and emitted in every build (Java 17 syntax
  only, it is generated output; dispatch is a method call, so no switch patterns are needed):
  * `Scoped(Table<?> table, Condition condition)`: today's root count,
    `dsl.selectCount().from(table).where(condition).fetchOne(0, Integer.class)`.
  * `BatchCounts.Slot`, obtained from `BatchCounts.slot(int index)`. `BatchCounts` is the shared
    memo, constructor `(Table<?> source, Field<Integer> idx, int keyCount)`. The producer passes
    `idx` in (the polymorphic path already holds `idxField`; the split path passes
    `countSource.field(IDX_COLUMN, Integer.class)`), so the windowed protocol keeps its one home in
    `BatchedRowsFragments.windowedPageTail` and the carrier never names `__idx__` itself.
* The memo, `synchronized` on the `BatchCounts`: on the first `count` call through any slot it runs
  `dsl.select(idx, DSL.count()).from(source).groupBy(idx).fetch()` and fills an `int[keyCount]`.
  An index with no group row counts 0, which is what today's per-parent `count(*)` returns for an
  empty connection. Later calls index the array. A slot never returns `null`, so a structural
  connection declaring `totalCount: Int!` stays safe on the batched path.
  * If the grouped statement throws, the exception is kept and rethrown on every later call, so each
    parent's `totalCount` surfaces the failure at its own path (as today) without re-running the
    statement N times.
  * `synchronized` because the carriers of one batch can resolve on different threads when the
    consumer runs graphql-java with an executor; the lock is uncontended in the default strategy.
  * `BatchCounts` is the first mutable, lazy object in the generated runtime; its javadoc says so
    and states the invariant the memo leans on (below), with `{@link}`s to the two producers.
* `ConnectionResult` gains the nullable `totalCount` slot with an accessor. Every existing public
  constructor keeps its signature: those taking `(table, condition)` wrap a non-null pair into
  `Scoped` (a null pair, the empty-participants defensive path, leaves the slot null), and keep
  storing `table` and `condition` as today, because `facets` gates on `cr.table()` and reads it as
  the facet source on root carriers. One new convenience constructor `(List<Record> result,
  PageRequest page, TotalCount totalCount)` (plus the trailing `DSLContext dsl` in a multi-tenant
  build) binds `table` and `condition` to `null`. Hand-written code constructing a carrier keeps
  compiling.
* The nested names reach the emitters through constants on `ConnectionResultClassGenerator`
  beside `CLASS_NAME`, the `FacetSpec` precedent, and the emitters build the class name from the
  run's generated-units handle (`GeneratedUnits.connectionResult()`) rather than another hard-coded
  `"ConnectionResult"` string such as the one `buildScatterConnectionByIdxHelper` carries today,
  which this item replaces in passing because it touches that helper anyway.
* The class javadoc's account of who binds what changes: root fetchers bind `Scoped` over the page
  query's own `(table, condition)`; batched producers bind a `BatchCounts` slot; the
  empty-participants defensive path binds nothing. The same claim ("the only remaining
  `(null, null)` producer is the empty-participants path") appears in the field comment above
  `tableField` and in `ConnectionHelperClassGenerator`'s comment above `totalCountMethod`; all three
  are rewritten.

### `ConnectionHelperClassGenerator`

`totalCount` becomes one null check and one call:

```java
ConnectionResult cr = env.getSource();
if (cr.totalCount() == null) return null;
DSLContext dsl = <as today>;
return cr.totalCount().count(dsl);
```

The `dsl` is resolved exactly as today: `cr.dsl()` in a multi-tenant build, `graphitronContext(env)
.getDslContext(env)` otherwise. For a batch this means whichever carrier resolves first supplies the
context the grouped count runs on. That is the page's context by construction in the multi-tenant
build, where the producer binds its own routed `dsl` on every carrier of the batch and
`TenantDslEmitter` keeps each loader batch to one tenant; in a single-tenant build there is one
context per request and DataLoaders are request-scoped. Capturing `dsl` in `BatchCounts` instead
would give the memo a second context path beside the carrier's `dsl()` and widen the single-tenant
scatter helper's signature for a fact the carrier already carries. `facets` is untouched; batched
carriers carry no facet plan today and still do not.

### The two producers

* `SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper`: the emitted helper keeps its signature
  (`countSource` stays a parameter, so `BatchedRowsFragments.windowedPageTail` does not change),
  creates one `BatchCounts counts = new BatchCounts(countSource, countSource.field("__idx__",
  Integer.class), keyCount)` before the carrier loop (the literal from `IDX_COLUMN`, as the helper
  already emits it), and builds each carrier with
  `new ConnectionResult(buckets.get(i), page, counts.slot(i)[, dsl])`. The helper's javadoc and the
  `windowedPageTail` javadoc sentence about the count source follow.
* `MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod`: the same change over
  `pagesTable` and its existing `idxField` (which still drives the `PARTITION BY`). The method
  javadoc's "N count queries when totalCount is selected" and the two inline comments about
  per-parent `__idx__.eq(i)` counting are rewritten to the grouped count.

Root connections bind `Scoped`, which issues the statement they issue today; the single-cardinality
polymorphic path and every non-connection batched path do not change.

## Tests

All execution tier, in `graphitron-sakila-example`, using `GraphQLQueryTest.QUERY_COUNT` (the
existing `ExecuteListener` counter) so the cost is pinned as a statement count, not a timing. The
batch-cost pins compare runs that differ only in the number of parents: equal totals prove both that
the count did not grow and that the parents shared one batch, since parents split across batches
would also multiply the page statement.

* `@splitQuery` path. `GraphQLQueryTest.splitQueryConnection_totalCount_isLazyOnSelection` (two
  films): the selected-case assertion changes from 4 ("one count statement per parent") to 3 ("one
  grouped count statement for the batch"); the unselected case stays at 2, which is the laziness
  pin. `splitQueryConnection_totalCount_isParentScoped`, which runs the Goal's five-film query and
  asserts the counts 2, 2, 1, 1, 1, gains a `QUERY_COUNT` assertion of 3. Together they are the
  two-against-five comparison. `_isCursorIndependent` stays as it is.
* Polymorphic batched path. `addressOccupantsConnection_dataLoaderBatchesAcrossParents` already
  compares three parents (`customers(active: true)`) against two (`active: false`) for the page;
  it gains a twin selection with `totalCount` and asserts that the three-parent and two-parent
  totals are equal with `totalCount` selected, and that selecting it adds exactly one statement in
  each run. `projectItemsConnection_totalCount_isParentScoped` (three projects, counts 4, 4, 2) stays
  as the per-index correctness pin on this path and gains a `QUERY_COUNT` assertion that its count
  costs one statement.
* The empty connection. No batched-connection fixture reaches a parent with an empty connection
  (films 1 to 5 all have actors, every project has items, a customer's address always has an
  occupant), and seeding one would perturb the film and project counts other tests assert. Both
  producers share one emitted `BatchCounts`, so the absent-group pin goes there: a new test in the
  same module constructs `ConnectionResult.BatchCounts` against the test database over a real
  source with a `keyCount` one larger than the groups it returns and asserts 0 for the extra slot
  and one statement for all slots.
* The retained exception, in the same new test: slots over a source that fails at execution (a
  derived table over a table that does not exist) throw on the first `count`, throw the same
  exception from a second slot, and the counter shows one statement.
* Multi-tenant. `TenantDivinedRoutingExecutionTest.nestedInheritedConnection_totalCountAggregatesOnTheDivinedTenantSource`
  stays green unchanged; with this item its carrier holds a `BatchCounts` slot, so it proves the
  grouped statement reads the routed `dsl` and runs against tenant 1's rows. Its batch has one
  parent and cannot have more: the fixture's tenant column is `film_id`, so every film is its own
  tenant and a loader batch never holds two films. Batch size is pinned on the single-tenant tests
  above; the multi-tenant change is only which context the memo receives, which this test covers.

## User documentation

The user manual describes `totalCount` wrongly today, independent of this item: it says
split-connection and multi-table polymorphic carriers return `null`. Both count (the batched ones per
parent, the root polymorphic one through the same separate `count(*)` the page's other bullet
describes). This item rewrites those passages to the behaviour it ships.

* `docs/manual/how-to/connections.adoc`, section "`totalCount` is lazy and orthogonal": the two
  "Returns `null`" bullets are replaced by one bullet, draft:

  > *One grouped count per batch on nested connections.* A connection nested under a list of parents,
  > such as `Film.actorsConnection` under `filmById`, or a connection over a union nested under a
  > list, is fetched for all parents in one batched page statement. Selecting `totalCount` there adds
  > one more statement for the batch, `count(*)` grouped by parent, however many parents the batch
  > holds; each parent reports its own count, independent of `first` and the cursor.

  The following "performance consequence" paragraph stays true and stays.
* The same page's later sentence "split-connection carriers return `null` for `totalCount` until
  per-parent count plumbing lands" and the matching parenthesis in
  `docs/manual/how-to/split-vs-inline.adoc` (the `@asConnection` bullet) point at the new bullet
  instead.

## Other solutions we've considered

* *A DataLoader for the count.* `totalCount` would load its parent's index through a second
  DataLoader whose batch function runs the grouped count. It reaches the same one statement, but
  turns `totalCount` into an asynchronous field that waits for another dispatch round, adds a
  registry entry and a key type per connection, and rebuilds a grouping the rows method already
  holds in hand. The shared object needs none of that.
* *Count eagerly in the rows method when `totalCount` is selected.* One `if` on
  `env.getSelectionSet().contains("totalCount")` and the counts ride along in the batch. It reads the
  selection off the batch's single `env`, which assumes every occurrence of the field in the batch
  selects the same sub-fields. The lazy memo makes no such assumption: a carrier that never resolves
  `totalCount` costs nothing, and one that does triggers the count for all.
* *Fold the count into the page statement* as a `count(*) over (partition by __idx__)` column. The
  page statement filters by cursor and `__rn__ <= limit` before the outer select, so the window would
  have to sit below the seek, and the count would then be paid on every query whether `totalCount`
  is selected or not, losing the laziness the manual promises.
