---
id: R1112
title: "A NULL sort key sorts last in both directions, and keyset paging returns the rows that carry one"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: [single-table-connection-primary-key-tiebreaker]
created: 2026-10-27
last-updated: 2026-10-27
---

# A NULL sort key sorts last in both directions, and keyset paging returns the rows that carry one

## Goal

Paging through a connection returns every row exactly once, including rows whose sort key is `NULL`, and those rows always come at the end of the order, whether it runs ascending or descending. A *connection* is a paginated list field. Each page hands out a *cursor* holding the sort values of the page's last row, and the next page *seeks* from it: it keeps only the rows that sort strictly after those values. That comparison is never true for a `NULL`, so today the seek drops rows. Under an ascending order PostgreSQL puts `NULL`s last, and once a page has passed the non-null keys the `NULL` rows never come back. A cursor that itself carries a `NULL` returns an empty page. After this item every order entry on a column that admits `NULL` renders `NULLS LAST` in both directions, and the seek is null-aware to match: rows after the cursor's value and then the `NULL` rows, or only the `NULL` rows after the cursor's position among them when the cursor's key is `NULL`. Backward paging (`last`/`before`) and a runtime `direction:` flip keep `NULL`s at the end instead of mirroring them to the front.

Concretely, on the sakila `film` table, where `length` is nullable:

```graphql
type Query {
    films: [Film!]! @asConnection
        @defaultOrder(fields: [{name: "length", direction: DESC}])
}
```

Today this pages `length DESC, film_id DESC` (the trailing `film_id` is the key graphitron appends so that ties page in a fixed order), with the `NULL`-length films first; a page that ends on one of them hands out a cursor whose next page is empty, so everything after it, every film with a length included, is unreachable. After this item it pages `length DESC NULLS LAST, film_id DESC`: the films with a length come first, longest first, the `NULL`-length films follow in `film_id DESC` order, and paging forward or backward through the whole list visits every film once. A plain list (no cursor) gets the same `NULLS LAST`, so a field's rows come in one order whether or not it is paginated.

## Context

- **Who hits it today.** Any `@order`, `@defaultOrder` or `@orderBy` column that is nullable, on a single-table connection and on a multi-table polymorphic one. The sakila fixtures sort only on `NOT NULL` columns, so no test exercises it. Validity periods are the realistic case: sorting by an end date where `NULL` means "still valid". Graphitron 9 has the same defect (`QueryHelper.getOrderByValues` feeding `.seek(...)` at `v9.3.0`).
- **Who makes it common.** R1083 (`@order` by a column on a to-one related table, or by a method-supplied expression) produces a `NULL` key for every parent with no related row, so it depends on this item.
- **Why it depends on R1106.** A run of `NULL` keys is a run of ties, and a seek can only step through ties when the order identifies a row. R1106 (In Review) appends the table's *row key*, the primary key or another key that identifies a row, to a connection order that does not already contain one (`OrderByResolver.complete`). Its identifying-key set already leaves out unique keys with a nullable column (`OrderByResolver.rowIdentity`), which is exactly the property a `NULL` run needs: a unique key does not separate two rows that are both `NULL` in it. This item relies on that and changes nothing there. The same method takes the node key without a null check, since jOOQ reports every view column as nullable whatever the data holds; this item follows that decision rather than re-deriving it (see Implementation).
- **Views.** For the same reason, an authored order column on a view always reads as nullable, so every authored view-column order gains `NULLS LAST` and the expanded seek. That is correct (the seek is right whatever the data holds) and costs only the row-value seek's compactness on those orders.
- **Behaviour change.** A descending order on a nullable column puts its `NULL` rows first today, and they move to the end. The manual says so (see Documentation). Cursors keep their wire format and stay decodable; a client that was mid-way through a descending list across the upgrade can see the `NULL` rows again at the end.
- **Out of scope.** A per-entry `nulls: FIRST|LAST` argument on `FieldSort`. It can be added later if someone needs the other placement. A `NOT NULL` column's null placement is unobservable and is not rendered (see Implementation).

## What jOOQ already does

The cursor already encodes `NULL`: `ConnectionHelper.encodeCursor` writes it as `\u0001` and `decodeCursor` turns it back into `DSL.val((Object) null, col.getDataType())`. The seek goes through jOOQ's `.seek(page.seekFields())` (in `RootLauncherRenderer`, `BatchedRowsFragments` and `MultiTablePolymorphicEmitter`). jOOQ 3.20.11 renders that seek from the `SortField`s of the preceding `.orderBy(...)`, and it is null-aware as soon as a sort field states its null placement. Rendered for PostgreSQL with the pinned jar, a two-column order `x, id` with cursor `(5, 3)` or `(NULL, 3)`:

- `x ASC, id ASC` (today): `(x, id) > (5, 3)`; with a `NULL` cursor `(x, id) > (null, 3)`, which is never true.
- `x ASC NULLS LAST, id ASC`: `x > 5 or x is null or ((x = 5 or x is null) and id > 3)`; with a `NULL` cursor `x is null and id > 3`.
- `x DESC NULLS LAST, id DESC`: `x < 5 or x is null or ((x = 5 or x is null) and id < 3)`; with a `NULL` cursor `x is null and id < 3`.
- `x DESC NULLS FIRST, id DESC`, the backward flip of `ASC NULLS LAST`: `x < 5 or (x = 5 and id < 3)`; with a `NULL` cursor `x is not null or (x is null and id < 3)`.
- `x ASC NULLS FIRST, id ASC`, the backward flip of `DESC NULLS LAST`: `x > 5 or (x = 5 and id > 3)`; with a `NULL` cursor `x is not null or (x is null and id > 3)`.

Each line is the correct "strictly after" for its order, a nullable column in the middle of a longer order included (`y ASC, x ASC NULLS LAST, id ASC` with `(1, NULL, 3)` renders `y > 1 or (y = 1 and x is null and id > 3)`). So the defect is not in the seek predicate but in the sort fields graphitron hands jOOQ: they never state a null placement, and the backward flip would drop one if they did (`ConnectionHelper.reverseOrderBy` rebuilds each field with `sf.$field().sort(...)`, which discards it). The plan therefore writes no seek predicate of its own; it states `NULLS LAST` on the sort fields and carries it through every flip.

## Implementation

The rule, in one place: an order entry whose column admits `NULL` sorts `NULLS LAST` whatever its direction; an entry on a `NOT NULL` column renders as it does today. Restricting it to nullable columns is deliberate: jOOQ renders its compact row-value seek (`(a, b) > (?, ?)`) only when no entry states a null placement, and that form is what lets PostgreSQL serve a uniform-direction page from an index range scan (the reason `OrderByResolver.complete` appends the row key in the last entry's direction). An order on `NOT NULL` columns, which is every order in the tree today, therefore keeps its generated code and SQL unchanged.

- **`OrderBySpec.ColumnOrderEntry`** gains `boolean nullable`: whether the column admits `NULL`, as the catalog reports it. It is a fact about the entry, not a rendering choice; the emitters apply the rule above. `OrderByResolver` sets it at every construction site from the `JooqCatalog.ColumnEntry` it already holds (`findColumn` for `fields:`, `findIndexColumns` for `index:`, `findPkColumns` for `primaryKey:` and the primary-key fallback). The row-key columns `complete` appends carry `nullable = false` by rule, not by catalog read: `rowIdentity` has already decided they identify the row, and reading the catalog there would mark a view's node key nullable and give every view-backed node connection an expanded seek on its appended key. An authored entry that happens to name a row-key column keeps its catalog value, so the rule touches only what `complete` appends.
- **`PolymorphicOrdering.Slot`** gains `boolean nullable`: true when any participant's column in the slot admits `NULL`, since the union column is `NULL` wherever one participant's is. `FieldBuilder` sets it where it builds each declared slot, from the participants' `ColumnOrderEntry.nullable`; the key slots it builds for the primary-key positions set `false` explicitly, on the same rule as the single-table row key. The `__typename` tiebreaker is never `NULL`.
- **One sort-term rendering.** `OrderByFragments` gains the single helper every declared-column sort term renders through, `sortTerm(CodeBlock expr, SortDirection direction, boolean nullable, CodeBlock flip)`: `<expr>.<asc|desc>()`, followed by `.nullsLast()` when `nullable`; with a non-null `flip` (the runtime-descending condition, which the sites spell differently: `"DESC".equals(dir)` in the fetcher helpers, a `desc` local in the multitable emitter) it renders `<flip> ? <expr>.desc() : <expr>.asc()` with `.nullsLast()` on both arms when `nullable`. The sites that switch to it: `OrderByFragments.fixedSortParts` (which `OrderingBlock` reaches through); the inline nested-list order in `ProjectionUnitRenderer` (which currently repeats `fixedSortParts` against the terminal alias); the `@orderBy` helper bodies `TypeFetcherGenerator.buildSingleArgOrderByBody` and `buildListArgOrderByBody`, both the uniform-ASC flip arm and the direction-locked arm; and `MultiTablePolymorphicEmitter.sortTerm`, called with the slot's `nullable`. The remaining sort-term sites are key and `__typename` tiebreakers, never `NULL`, and stay as they are: the key and tie terms in `MultiTablePolymorphicEmitter.orderedParts` and its other fixed `.asc()` key orders, and `TypeFetcherGenerator.buildRowKeyCompletion`, which gains a javadoc `{@link}` to `OrderByResolver.rowIdentity` for why its `.sort(lastOrder)` needs no null placement.
- **`ConnectionHelperClassGenerator`'s `reverseOrderBy`** keeps the null placement and mirrors it with the direction: `NULLS LAST` becomes `NULLS FIRST` and back, and a field with no stated placement stays without one (`sf.$nullOrdering()` is `null` for it; `$nullOrdering(QOM.NullOrdering)` sets one). The backward query is then the exact reverse of the forward one, so `trimmedResult`'s re-reversal presents the page in forward order with the `NULL` rows still at the end. This is the one change to the generated runtime helper.
- **No new fact; one more field on the transitional surface.** Column nullability is already captured (`sql_column.nullable`, read through the catalog entries the resolver holds), so no relation is added. Carrying it on `ColumnOrderEntry` and `Slot` does extend the classification walk's model, which `docs/architecture/explanation/pipeline-overview.adoc` § The strangler frame names migration debt, and it rides into the command layer through `Ordering.Columns`, which borrows `OrderBySpec.Fixed`. It is the smallest extension that reaches every emitter: ordering has no command family of its own yet, and when one is lifted onto the store this flag becomes a join to `sql_column.nullable` there.

### Documentation

- `docs/manual/how-to/sort-results.adoc` gains a section "Where `NULL` sorts" beside "Stable cursors and tie-breakers": a nullable order column sorts its `NULL`s last in both directions, on lists and connections, single-table and multi-table alike; backward pages and a runtime `direction:` keep them last; the seek pages through them. It also carries the index note: `ASC NULLS LAST` is what a plain b-tree index serves, scanned forward for `first:` and backward for `last:`, while `DESC NULLS LAST` needs an index declared `DESC NULLS LAST` on a table large enough for the sort to matter. The backward-pagination paragraph in "Stable cursors and tie-breakers" changes from "reverses each `SortField`'s direction" to reversing its direction and its null placement.
- `docs/manual/how-to/migrating-from-legacy.adoc` gains a "Behavior divergence" entry: Graphitron 9 put `NULL`s first under a descending order and dropped `NULL`-keyed rows from later pages; the rewrite puts them last and pages them.
- The `@order` and `@defaultOrder` reference pages each get one sentence pointing at the new section.

## Tests

- **Pipeline tier, `OrderByResolver`:** a `fields:` entry on a nullable column resolves with `nullable = true` and one on a `NOT NULL` column with `false`; an `index:` order over a mixed index carries each column's own nullability; the row key `complete` appends carries `false`, on a table and on a view-backed node type alike; a multi-table declared slot is nullable when one participant's column is and not when none is, and a key slot is never nullable. Together with the single rendering helper, the `false` cases are what keeps today's `NOT NULL` orders on their row-value seek; the rendered SQL itself is not asserted, per the execution tier's ban on statement text in `docs/architecture/how-to/testing.adoc`.
- **Execution tier (sakila-example, PostgreSQL):** a new fixture table, in the manner of `paged_a`/`paged_b`, with a nullable `valid_to date` sort column seeded so that non-null values repeat, at least three rows are `NULL`, and a page boundary falls inside the `NULL` run at the default page size. Connections on it with `@defaultOrder` ascending and descending and an `@orderBy` with a runtime `direction:`, and a plain list. The cases:
  - forward paging over the whole list, ascending and descending, returns every row once with the `NULL` rows last, including the page whose cursor carries a `NULL`;
  - backward paging (`last`/`before`) from the end returns the same sequence, so a `before:` cursor taken inside the `NULL` run pages correctly; this is the case that pins `reverseOrderBy` mirroring the null placement, since dropping it puts the backward page's `NULL` rows on the wrong side;
  - the runtime `direction: DESC` flip keeps the `NULL` rows last;
  - the plain list puts the `NULL` rows last under a descending order.

  These are the cases the goal names; each fails on today's code (the ascending forward walk never reaches the `NULL` rows, the descending walk shows them first, and a `NULL` cursor returns an empty page).
- A multi-table polymorphic connection ordered on a nullable column is covered at the pipeline tier above; an execution case is added only if a participant table with a nullable sortable column already exists in the fixture, rather than seeding one for it.

## Other solutions we've considered

- **A hand-written null-safe seek predicate in `ConnectionHelper`** replacing `.seek(...)` with a `Condition` built from the decoded cursor. jOOQ already renders exactly that predicate once the sort fields state their null placement (see the table above), so writing our own would be a second implementation of jOOQ's, plus a change to the query-chain shape at every seek site.
- **`NULLS LAST` on every entry, nullable or not.** Simpler to emit and needs no nullability fact, but it turns every connection's seek, all of them on `NOT NULL` columns today, from the row-value form into the expanded `OR` form and churns every generated order. The nullability fact is cheap: the resolver already holds the catalog column.
- **`COALESCE` to a sentinel value.** Needs a per-type sentinel that can collide with real data, and defeats any index on the column.
