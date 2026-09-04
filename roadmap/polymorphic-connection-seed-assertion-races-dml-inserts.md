---
id: R905
title: "A polymorphic-connection execution test asserts over an unfiltered root field and races sibling film inserts"
status: Spec
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-09-01
last-updated: 2026-09-04
---

# A polymorphic-connection execution test asserts over an unfiltered root field and races sibling film inserts

## Goal

A contributor who sees `ConnectionSharedResultKeyProjectionTest.polymorphicConnection_divergentNestedSelections_bothSidesResolve`
go red should learn exactly one thing from it: the shared-result-key projection fix regressed. Today
the same red carries two readings, because the case can equally fail on a `film` row a sibling test
class had in flight, and nothing at the point of failure distinguishes them. When this item lands
the case asserts over rows its own query names, so no concurrent writer can turn it red, and the
module carries an audited answer to the question the failure raised: which other readers here have
the same shape.

The one-line version of the defect: the case's comment states a premise about the *seed* ("all seed
films are 2006") and its assertion then quantifies over *the table*.

Terms, glossed once and then used freely. The *execution tier* is the test tier that runs generated
resolvers against a live PostgreSQL instance, as opposed to asserting on generated source. A *result
key bucket* is graphql-java's grouping of every occurrence of one response key in a flattened
selection set; a Relay connection produces two-occurrence buckets whenever the same key is selected
under both `edges { node { ... } }` and `nodes`, which is the projection behaviour this whole test
class exists to pin. The *`local-db` path* is the build arm that talks to a native PostgreSQL server
(`-Plocal-db`) instead of starting a container per test class.

## Why it fails today, stated as the premise the fix rests on

The case queries `searchConnection(first: 100)`, an unfiltered root field over the multi-table
`Searchable` interface (`Film` and `Actor`, heterogeneous tables, no shared discriminator), and
asserts that every returned `Film` has `summary.releaseYear` equal to 2006.

`graphitron-sakila-example/src/test/resources/junit-platform.properties` sets
`junit.jupiter.execution.parallel.config.fixed.parallelism=4` with
`mode.classes.default=concurrent`, and on the `local-db` path every class shares one PostgreSQL
instance. Eight test classes in `querydb` write `film` rows, either through jOOQ helpers named
`insertFilm` / `insertFilmWithYear` or through the `createFilm` / `createFilms` mutations. None of
them sets `release_year`, so every such row carries `release_year = NULL` for as long as it is
visible. When one is visible to this case's connection query, the assertion sees an extra `Film`
with a null `releaseYear` and fails with `expected: 2006 but was: null`.

The writers are not leaking. `DmlBulkMutationsExecutionTest` alone carries more delete sites than
insert sites, all scoped to rows it can name by id or by a UUID marker in the title. What this case
races is the visibility window while a sibling's row is live, which is why it fails intermittently,
passes on rerun, and has been observed on a tree whose diff could not produce a film row at all.

Two facts narrow the fix and are worth having before the implementer starts:

* `film` is the only seed table any test class in the module writes. Greps for
  `DSL.table("customer")`, `DSL.table("staff")`, `DSL.table("store")` and `DSL.table("address")`
  across `src/test/java` return nothing. So the exposure is specific to the film family of root
  fields, not general to the module's seed data.
* `releaseYear` is exposed because it is the one nullable column in the diverging selection. The
  sibling assertion on the `edges` side (`summary.title` is not null) survives a stray row, because
  `film.title` is `NOT NULL`. A regression to first-occurrence projection nulls the diverging side
  regardless, so the case's *sensitivity* does not depend on the stray rows either way.

## Implementation

Keep the fixture and make the query name its rows. Select `title` alongside the diverging bucket on
both paths, and assert only over the five seed titles:

```graphql
{ searchConnection(first: 100) {
    edges { node { __typename ... on Film { title summary { title } } } }
    nodes { __typename ... on Film { title summary { releaseYear } } }
} }
```

`title` is a single-occurrence key on neither path and a two-occurrence identical-selection bucket
across both, which the class already covers in
`referenceUnderBothPaths_identicalSelections_behaviourUnchanged`; it does not weaken the divergence
under test, which stays on the `summary` bucket (`title` on one path, `releaseYear` on the other).
The Java side then keys both paths by the outer `title`, looks up the five titles `init.sql` seeds
(`ACADEMY DINOSAUR`, `ACE GOLDFINGER`, `ADAPTATION HOLES`, `AFFAIR PREJUDICE`, `AGENT TRUMAN`),
and asserts `releaseYear` is 2006 on each of those and that all five were found. Rows a sibling
class has in flight carry UUID-marked titles and are never looked up.

The assertion stays as strong as it is today in the direction that matters: under a regression to
first-occurrence projection the `nodes` path yields a null `releaseYear` for the seed films
themselves, so the case still fails, and it now fails only for that reason.

One premise the implementer should confirm rather than assume: that the five seed films are inside
the `first: 100` window. They hold the lowest primary keys in `film` and concurrent inserts take
higher ones, so page ordering should keep them, but the polymorphic connection orders on the
`pages` derived table's `__sort__` rather than on a raw key, and that is worth reading before
relying on it. If it does not hold, drop to keying the lookup off whatever the page does return and
assert over the intersection with the seed titles, requiring a non-empty intersection.

The replacement comment on the case states the invariant it now rests on (the query names its rows,
so a concurrent writer cannot reach the assertion) without citing this item, per the transient
citation rule in `CLAUDE.md`.

## The sibling cases in the same class

Audit the other cases in `ConnectionSharedResultKeyProjectionTest` for the same shape while here.
The known one is `nonConnectionQuery_singleOccurrencePath_behaviourUnchanged`, which reads the
unfiltered `customers` root field and asserts `hasSize(5)`, an exact count over a table. It cannot
fail today because nothing writes `customer`, so this is a latent instance rather than a live one:
fix it by construction (assert over named rows, or drop the exact count) and say so, rather than
leaving the next contributor to rediscover that the count is load-bearing. The four
`assertStore1CustomersBothSides` cases read the unfiltered `stores` field and assert
`hasSize(2)` plus store 1's customer names; same reasoning, same treatment. The two fail-loud guards
assert only on errors and are not exposed.

## The module-wide audit

The failure's cost was diagnostic, so the deliverable includes knowing where else it can happen.
Scope it by the narrowing fact above: `film` is the only written seed table, so audit the readers of
the film-family root fields (`films`, `filmsFaceted`, `search`, `searchConnection`, `documents`,
`documentsConnection`). A grep for those keys across `src/test/java` currently names fourteen files,
several of which are the writers themselves or SQL-baseline tests that assert on emitted SQL rather
than on rows.

For each reader, classify the assertion shape: an exact count or a universally quantified property
over an unfiltered root field is exposed; an assertion over rows the case names, or over a column
that is `NOT NULL` by construction, is not. Fix the exposed ones the same way as the primary case.
Record the outcome in this item's body as the audit's result, so the Done gate can see what was
checked rather than inferring it from the diff.

If the audit turns up enough instances that fixing them all belongs elsewhere, split the remainder
into a follow-up Backlog item and say which readers it covers; do not silently narrow this item's
scope to the one case that failed.

## Tests

This item is a test fix, so the acceptance is not "a green build", which the case already produces
most of the time. It is:

* The primary case, run concurrently with a class that holds an uncommitted null-`release_year`
  film row, passes. The cheap form of this is to insert such a row directly against the local
  database, leave it there, run the case, and see it pass; then delete the row. That demonstrates
  the property the fix claims, which the ordinary suite run cannot.
* The primary case, run against a tree with the `$project` occurrence-list union reverted, still
  fails. This is the sensitivity check, and it is the one that proves the fix did not buy stability
  by weakening the assertion. Reverting is not required if the diverging column's absence can be
  demonstrated more cheaply.
* The full verification build (`mvn install -Plocal-db`) passes.

## Other solutions we've considered

**Move the case to a filterable polymorphic connection.** `occupantsByNameConnection(firstName: [String!])`
over `AddressOccupant` (`Customer | Staff`) is a multi-table union connection with a per-participant
`WHERE`, live and covered by `MultiTableFilterExecutionTest.connectionForm_filterApplied_returnsOnlyMatchingNodes`.
`Customer` carries a `location` nesting field to diverge on, and no test class writes `customer` or
`staff`, so the case would be scoped at the database rather than in Java, which is the module
convention's literal form. Rejected because it changes which generator path the case covers:
`searchConnection` is the heterogeneous-table polymorphic connection fixture this case was written
against, and swapping it for the shared-column filtered union is a coverage change wearing a flake
fix's clothes. Worth revisiting only if the page-window premise above fails.

**Diverge on a column that is `NOT NULL` by construction.** Have the `nodes` path ask for
`summary { originalTitle }` (which remaps to `TITLE`) instead of `releaseYear`, making the assertion
true of any film row the query can return, stray or seeded. Smallest possible diff and immune by
construction. Rejected as the primary plan because "is not null" is a weaker witness than a value
equality: it passes on a projection that reaches the right column for the wrong row. Keeping the
value assertion and naming the rows costs a few lines more and pins more.

**Serialise the classes.** Give the writers and this reader a shared JUnit resource lock, or mark
the case `@Isolated`. Rejected: the module is the reactor's terminal node, so its wall clock is
build wall clock, and the properties file's own prose already settles this by saying the fix is to
make the reader's query mean its own rows. Serialising trades the module's measured parallelism for
a defect that is local to one assertion.

## Reviewer findings
