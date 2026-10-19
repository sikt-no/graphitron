---
id: R1063
title: "An empty after cursor reads as the first page, as v9 read it"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# An empty after cursor reads as the first page, as v9 read it

## Goal

A connection field given `after: ""` returns the first page, as it did under Graphitron 9,
instead of failing with "cursor is not valid". Consumers whose operations bind `after` to a
non-null `String!` variable send the empty string for the first page, and keep working across the
upgrade. `before: ""` reads the same way, so on both ends of a page the empty string means "no
cursor".

Take the sakila example's root connection, a *connection* being a field that returns one page of
rows plus cursors to fetch the neighbouring pages:

```graphql
filmsConnection(first: Int, last: Int, after: String, before: String): FilmsConnection!
    @defaultOrder(primaryKey: true)
```

These two queries return the same response once the item lands: the first two films,
`hasNextPage: true`, `hasPreviousPage: false`.

```graphql
{ filmsConnection(first: 2)            { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }
{ filmsConnection(first: 2, after: "") { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }
```

Today the second one fails with `cursor is not valid (was: "")`, and because `FilmsConnection!` is
non-null the null propagates and the whole `data` is null. A cursor that is genuinely malformed
(not Base64, wrong number of values, a value that does not fit its column) keeps failing exactly as
it does now.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19).
`studenter(filter: {eierOrganisasjonskode: "1234"}, first: 1, after: "")` returns the error
`cursor is not valid (was: "")`; on a root field typed `Connection!` the null propagates and the
whole `data` is null. In sis' production traffic 48 operations pass a `String!` variable as
`after`, so their first page most likely sends `""`.

The error itself comes from R479, which turned a malformed cursor into a clean client error instead
of a redacted server fault. That stays right for a genuinely malformed cursor; only the empty string
changes meaning.

What v9 did, read from the `v9.3.0` tag: `QueryHelper.getOrderByValues` (and its multi-table
sibling `getOrderByValuesForMultitableInterface`) returns "no seek" when
`token == null || token.isBlank()`, so v9 treated empty *and* whitespace-only `after` as absent.
v9 had no backward pagination (the `before` name is declared in `GraphQLReservedName` and read
nowhere in the code generator), so `before: ""` has no v9 precedent; this item extends the same
reading to it for symmetry.

## Behaviour

- An `after` or `before` value that is empty or blank (whitespace only) is treated exactly as if the
  argument were absent. Blank rather than only empty matches v9, and costs nothing: the Base64
  alphabet has no whitespace, so no cursor graphitron emits is blank.
- "Exactly as if absent" holds for everything downstream, not only the seek: a forward page fetched
  with `after: ""` reports `hasPreviousPage: false`, and a backward page fetched with `after: ""`
  reports `hasNextPage: false`, the same values the absent argument produces.
- The treatment is unconditional; there is no setting. The empty string names no position in any
  ordering a client could want, the Relay connection spec gives it no meaning, and a v9-compatibility
  switch would add configuration surface for a reading nobody wants the other way. The one edge
  where an emitted cursor *is* empty is below; it does not justify a switch either.
- Every other malformed cursor is rejected exactly as today, with the same message.

## Implementation

All of it is one site in the generated helper, `ConnectionHelperClassGenerator`, which emits the
`ConnectionHelper` class into the consumer's output package.

- **Normalise in the emitted `pageRequest`, before anything reads the cursors.** `pageRequest` is
  the shared pagination entry point: every connection fetcher reads `after`/`before` off the
  `DataFetchingEnvironment` and passes them straight in (root connections in
  `RootLauncherRenderer`, batched and `@splitQuery` connections in `BatchedRowsFragments`, whose
  per-parent `ConnectionResult`s `SplitRowsMethodEmitter` builds from the resulting `PageRequest`,
  and both arms of `MultiTablePolymorphicEmitter`). Its first statements, ahead
  of the `first`/`last` guards, become the equivalent of
  `if (after != null && after.isBlank()) after = null;` and the same for `before`. The normalised
  values then flow into both consumers: `decodeCursor` (which already returns a no-op seek for
  `null`) and the `PageRequest` record, whose `after()`/`before()` the page-taking `ConnectionResult`
  constructors forward as `afterCursor`/`beforeCursor`. That second path is why the site is
  `pageRequest` and not `decodeCursor`: `ConnectionResult.hasPreviousPage()` returns
  `afterCursor != null` for a forward page, so a `""` that only `decodeCursor` ignored would still
  reach `ConnectionResult` and report `hasPreviousPage: true` on the first page.
- **Confirm the funnel at pickup.** The claim above rests on no fetcher building a
  `ConnectionResult` from raw argument strings through its non-`PageRequest` constructor. A grep at
  the time of writing finds none (the polymorphic emitter's empty-result path passes `page`); the
  implementer re-runs it rather than trusting this sentence.
- **Prose.** The `ConnectionHelperClassGenerator` class javadoc ("When no cursor is present ...")
  and the generator comments on `pageRequest` and `decodeCursor` say "absent, empty, or blank"
  where they now say "absent" or "null".
- Out of scope: the MCP server's own paging cursor (`McpWire.decodeCursor`), which is a different
  wire format and already reads a malformed or absent cursor as offset 0.

### The one emitted empty cursor

`encodeCursor` Base64-encodes the sort-key values of the page's last row, joined by NUL. When the
effective order is a single text column and that row's value is the empty string, the joined string
is empty and so is the cursor. After this change, sending that cursor back as `after` returns the
first page rather than the page after it.

Reaching it takes an order of exactly one text column (`@defaultOrder(fields: [...])` or an
`@order` enum value naming one column, with no primary-key tiebreaker), a row whose key in that
column is `''`, and a page that ends on that row. A non-unique single column already breaks keyset
pagination on ties, so the realistic case is a unique text column. Under ascending order the `''`
row sorts first, so the page must be `first: 1`, and a client following `endCursor` then re-reads
page one. Under descending order the row sorts last, its page reports `hasNextPage: false`, and a
client that follows the flag never sends the cursor back. The plan accepts the edge and documents
it (below) rather than changing the cursor encoding; see *Other solutions*.

## Tests

Execution tier, `GraphQLQueryTest` in `graphitron-sakila-example`, next to the malformed-cursor
block (`filmsConnection_malformedBase64AfterCursor_surfacesClientError` and its siblings):

- `after: ""` on `filmsConnection(first: 2)` returns no errors, the same two titles as
  `filmsConnection_firstPage_returnsFirstNFilms` (`ACADEMY DINOSAUR`, `ACE GOLDFINGER`),
  `hasNextPage: true` and `hasPreviousPage: false`. The `hasPreviousPage` assertion is the one that
  pins the constraint above; a fix placed in `decodeCursor` passes every other assertion and fails
  this one.
- The sis shape itself: a `query($after: String!)` operation run through `execute(query, variables)`
  with `after` bound to `""`, same expectations.
- A blank cursor (`after: "   "`), same expectations.
- `before: ""` on `filmsConnection(last: 2)` returns the same page and flags as the existing
  `last: 2` test, and `after: ""` alongside `last: 2` reports `hasNextPage: false`.
- The nested `@splitQuery` connection, `filmById(film_id: ["1", "2"]) { actorsConnection(first: 1,
  after: "") ... }`, returns each parent's first actor with no errors. This covers the batched
  DataLoader path the root test does not.

The existing malformed-cursor tests stay unchanged; their staying green is what shows the carve-out
is no wider than empty and blank.

## Documentation

`docs/manual/how-to/connections.adoc`, section "Cursor encoding is part of the public contract",
gains a bullet:

> *An empty cursor is no cursor.* `after: ""` and `before: ""` (or a whitespace-only value) read as
> if the argument were omitted, so an operation that declares `$after: String!` can fetch the first
> page by sending the empty string. One cursor graphitron emits is itself empty: when the order is a
> single text column and the page's last row holds `''` in it. Give such an order a unique,
> non-empty tiebreaker column, such as the primary key, if clients page through it one row at a
> time.

## Other solutions we've considered

- **Normalise in `decodeCursor`.** Smallest diff, and wrong: the raw `""` still reaches
  `ConnectionResult` through `PageRequest` and the first page reports `hasPreviousPage: true`.
- **Normalise at each fetcher's `env.getArgument("after")`.** Correct, but three emitters and five
  call sites would each carry the same rule, and the next connection flavour would have to remember
  it. `pageRequest` is already the single funnel for the other client-mistake rules.
- **A v9-compatibility setting.** Covered under *Behaviour*: configuration for a reading nobody wants
  the other way.
- **Change the encoding so no emitted cursor is ever empty** (a version or framing byte). Removes
  the edge, but changes the wire format and so invalidates every outstanding cursor at deploy, to fix
  a corner that needs a unique single-text-column order, an empty key, and a one-row page.
