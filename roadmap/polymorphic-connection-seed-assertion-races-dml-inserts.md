---
id: R905
title: "A polymorphic-connection execution test asserts over an unfiltered root field and races sibling film inserts"
status: Ready
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-09-01
last-updated: 2026-09-05
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

## Why it failed, stated as the premise the fix rests on

The case queries `searchConnection(first: 100)`, an unfiltered root field over the multi-table
`Searchable` interface (`Film` and `Actor`, heterogeneous tables, no shared discriminator), and
asserts that every returned `Film` has `summary.releaseYear` equal to 2006.

`graphitron-sakila-example/src/test/resources/junit-platform.properties` sets
`junit.jupiter.execution.parallel.config.fixed.parallelism=4` with
`mode.classes.default=concurrent`, and on the `local-db` path every class shares one PostgreSQL
instance. Eleven test classes in `querydb` write `film` rows, either through jOOQ helpers named
`insertFilm` / `insertFilmWithYear` or through the `createFilm` / `createFilms` mutations. All but
one of those write paths leaves `release_year` unset, so such a row carries `release_year = NULL`
for as long as it is visible; the exception is `insertFilmWithYear` in
`DmlBulkMutationsExecutionTest`, which writes 2125. When one is visible to this case's connection
query, the assertion sees an extra `Film` whose `releaseYear` is not 2006 and fails with
`expected: 2006 but was: null` (or `2125`).

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

## Implementation (delivered)

`ConnectionSharedResultKeyProjectionTest.polymorphicConnection_divergentNestedSelections_bothSidesResolve`
now selects the outer `title` alongside the diverging bucket on both paths and keys its assertions
to the five seed titles through a `seedFilmSummaryLeaf` helper, which reads one leaf out of each
seed film's `summary` and drops every film the case did not seed. The helper keeps nulls rather
than dropping them, so a regression that leaves the diverging leaf out of the SELECT fails an
assertion instead of a collector. The edges side is pinned as an equality (`summary.title` remaps
to the same `FILM.TITLE` column as the outer title) rather than as a non-null check, which is the
strength the rejected `NOT NULL` alternative below would have given up.

The page-window premise the plan flagged for confirmation did not need its fallback: the seed is
five films and four actors, so `first: 100` holds the whole population.

## The sibling cases in the same class (delivered)

Every case in the class that reached into a connection page by list position now locates its
subject by id through a `storeById` helper, and the two exact row counts are gone:

* `assertStore1CustomersBothSides` (four cases) dropped `hasSize(2)` on both paths and locates
  store 1 by `storeId` rather than at index 0.
* `deepNesting_divergenceOneLevelDown_bothSidesResolve` and
  `argumentAgreement_onArgConsumingArm_passesGuardAndResolves` now select `storeId` on both paths
  so they can do the same. In both, `location` and `customersFirstN` remain the only diverging
  buckets; the added `storeId` is a two-occurrence bucket with identical selections, the shape
  `referenceUnderBothPaths_identicalSelections_behaviourUnchanged` already pins.
* `nonConnectionQuery_singleOccurrencePath_behaviourUnchanged` dropped `hasSize(5)` on the
  unfiltered `customers` root field and keys to the five seed first names instead.

Store 1's own customer list stays an exact, ordered `containsExactly`. It is scoped to one named
store's children rather than to a table, and nothing in the module writes `customer`. Making that
assertion stray-immune too would mean giving up the value equality that is the case's whole point,
which is the wrong trade for an exposure that does not exist.

## The module-wide audit (delivered)

Scoped as the plan directed, to the readers of the film-family root fields, `film` being the only
seed table any test class writes. The result: the primary case was the outlier, and no live
exposure remains.

The polymorphic readers in `GraphQLQueryTest` were already swept before this item, and their
comments state the same convention this item applies: `search_returnsAllParticipantTypes` asserts
the film count as a floor and the actor count exactly, "because nothing in this module writes
`actor`"; `searchConnection_inlineFragmentsResolvePerTypeOnConnectionPath` and
`searchConnection_totalCount_returnsTotalRowCountAcrossParticipants` do the same, the latter
noting what a floor still discriminates (a count over one branch reports 3 or 5, an unwired count
reports null); `filmsFaceted_noFacetFilter_countsMatchPlainAggregates` and its siblings bound the
facet base with `extra: {lengthIs: [...]}` precisely so a film another class inserts falls outside
the bound. `ConnectionSharedResultKeyProjectionTest` sat in a different class and was missed by
that sweep.

The remaining readers are sound for reasons that do not depend on writer habits:
`documentsConnection_unionVariant_works` and `searchConnection_firstPage_...` assert a page size,
not a table count; `TenantDivinedRoutingExecutionTest` and `RootLauncherSqlBaselineTest` filter by
`filmId` or `rating`; `RoutineFieldExecutionTest` keys nested reads by `filmId`;
`AccessorDerivedSourceTest` reads a mutation payload's `films`, not the root field;
`ConnectionLifecycleExecutionTest` and `DevExecuteExecutionTest` use `{ films { filmId } }` as a
vehicle and assert on connection pinning and rejection messages rather than on rows; the SQL
baseline classes assert emitted SQL.

One candidate was tested and refuted rather than assumed.
`filmsFaceted_selectionGate_unselectedFacetContributesNoArm` asserts exactly three rating groups
over an unbounded `filmsFaceted`, which reads like the same defect. It is not: the non-null-element
facet appends `AND col IS NOT NULL` to its arm, so a stray film's NULL rating cannot open a fourth
group, and no write path in the module sets `rating` at all. Verified by inserting a
null-`release_year`, null-`rating` film and watching the case pass.

## Tests (delivered)

The acceptance was deliberately not "a green build", which the case already produced most of the
time. What was run:

* **The failure reproduces.** With a null-`release_year` film row left live in the local database,
  the pre-fix case fails exactly as reported: `expected: 2006 but was: null`, at the `allSatisfy`
  on the nodes side.
* **The fix holds against it.** The same stray row live, the delivered case passes, all ten cases
  in the class green.
* **The fix did not weaken the assertion.** With a seed film's `release_year` set to NULL, the
  delivered case fails on the nodes side, carrying its own description
  (`[nodes path: summary.releaseYear per seed film] expected: 2006 but was: null`). Stability came
  from naming the rows, not from asserting less.
* The seed was restored to its five 2006 films after each probe, and the full verification build
  (`mvn install -Plocal-db`) passes.

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

### Round 1 (2026-09-05, In Review -> Done, reviewer session 017tdhXkyu8F4FJ1UPekfr5L)

Verdict: withhold. One finding, on question four. Question three passes and is not in doubt: the
delivered change is the change the plan approved, and where it goes past the plan it goes in the
direction the plan argued for. `mvn install -Plocal-db` is green on the current head.

**Finding 1 (question four: how we know the item is complete). The audit's one
tested-and-refuted entry is refuted against a row shape no writer in this module can produce, so
it does not establish what it reports.**

The audit says of `filmsFaceted_selectionGate_unselectedFacetContributesNoArm`: "the
non-null-element facet appends `AND col IS NOT NULL` to its arm, so a stray film's NULL rating
cannot open a fourth group, and no write path in the module sets `rating` at all. Verified by
inserting a null-`release_year`, null-`rating` film and watching the case pass."

A stray film does not carry a NULL rating. `init.sql` declares `rating mpaa_rating DEFAULT 'G'`,
and `createFilm_omittedFieldUsesColumnDefault` pins that an omitted input field binds
`DSL.defaultValue()` rather than a typed null, so every film another class inserts without naming
a rating is G-rated. The `IS NOT NULL` scrub is real, `FilmFacetFilter.rating` has non-null
elements and the SDL comment beside it says exactly that, but it never fires on a row this module
writes; the probe reached the shape it tested only by explicitly overriding the column default.

The verdict is right. `G` is already one of the seed's three groups, so `hasSize(3)` holds. But it
is right for a reason the entry does not give, and the reason it does give generalises wrongly:
what protects this case is that no fixture names a rating, and the day one does with `R` or
`PG-13`, a fourth group opens and the case goes red. Nothing in the recorded reasoning would warn
that contributor.

This is the same error class the item exists to fix. The defect in the primary case is a comment
stating a premise about the seed under an assertion quantified over the table; the defect here is
an audit entry reasoning from a row shape the table cannot hold. An audit delivered as this
item's own goal ("the module carries an audited answer") should not repeat it.

`GraphQLQueryTest` already carries the form this entry should take, twice in the same file:
`filmsOrderedConnection_totalCount_underFilter_appliesSamePredicate` reasons from the default and
names the standing constraint out loud ("film.rating carries DEFAULT 'G' ... No fixture sets
rating explicitly, so PG bounds the count to the seed. A writer that starts inserting PG films has
to revisit this"), and `filmsFaceted_noFacetFilter_countsMatchPlainAggregates` bounds its base off
"film.length has no default". The audit entry is the odd one out against the module's own
established form.

What would satisfy it: state the governing fact, that `rating` carries a column default so a
stray film lands in a group the seed already has, and the standing constraint that leaves, that no
fixture names a rating; then either re-run the probe with a default-rating film, which is the
shape a writer actually produces, or drop the probe claim rather than let it stand as evidence for
something it did not test. Enforcing that constraint mechanically rather than documenting it would
be a fresh Backlog item, not this one.

Verified along the way and not in question: the primary case keys both paths to the five seed
titles through `seedFilmSummaryLeaf` and keeps nulls, so a missing diverging leaf fails an
assertion rather than a collector; the edges side is an equality against the outer title, which is
stronger than the `isNotNull` it replaced and stronger than the plan asked for; no writer in the
module uses a seed title, so the keying map cannot be collided; the sibling sweep reached both
cases the Spec round flagged as unlisted (`deepNesting_divergenceOneLevelDown_bothSidesResolve`
and `argumentAgreement_onArgConsumingArm_passesGuardAndResolves`), and both added `storeId` as an
identical-selection bucket that leaves their diverging bucket alone; every method the audit names
exists and behaves as described, including the floor-versus-exact split in
`search_returnsAllParticipantTypes` and the `extra: {lengthIs: [...]}` bound in
`filmsFaceted_noFacetFilter_countsMatchPlainAggregates`. No `docs/` changes, so the
user-facing-doc check does not apply; no `Retired vocabulary` section, so the retirement sweep
does not apply.
