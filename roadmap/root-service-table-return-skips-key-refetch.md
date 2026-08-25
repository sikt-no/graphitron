---
id: R834
title: "A top-level @service returning a @table type reads columns off the returned record instead of refetching by key"
status: Backlog
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# A top-level @service returning a @table type reads columns off the returned record instead of refetching by key

A `@service` on a root field hands the operation to a Java method the author wrote. When that method
returns jOOQ table records bound to a `@table` GraphQL type, the generated root fetcher passes the
records straight to graphql-java and every column field reads its value off the record it was handed.
`QueryFetchers.filmsByService` is the whole body: call the service, wrap the result, return it. So a
service that populated only the key columns resolves the key correctly and resolves every other
selected field to `null`, for every row, with no build warning and nothing thrown. A whole column of
nulls is indistinguishable from a column that is genuinely empty, so it reaches production. A Sikt
subgraph has now hit this twice.

The behaviour is not wrong by its own contract. `docs/manual/how-to/handle-services.adoc` states it
twice, that the framework treats the returned records as already-projected rows and that the service
is therefore responsible for selecting every column the schema may ask for. The problem is that the
contract is the odd one out. A root `@service` returning a *polymorphic* `@table` type already lifts
each record's primary key, re-selects by key per participant table, and builds the payload from the
refetched rows; `QueryFetchers.searchManyService` is generated exactly that way, and
`PolymorphicSearchService`'s own javadoc states the contract as "set only the PK and leave the rest
for Graphitron to fetch, matching the legacy 9.3 contract". A root `@service` returning a
`@discriminate` interface does the same, and `ContentSearchService` says so in the same words. Both of
those refetch because they were forced to, one needing the record's Java class as the discriminator
and the other needing a live discriminator column. The plain single-table return is not forced, so it
never got the treatment, and it is the shape an author writes first. Reading the three shapes
together, the passthrough looks like an omission rather than a decision.

The proposal is to make the plain return refetch too, so the contract across every table-returning
root `@service` becomes one sentence: populate the key columns, and Graphitron fetches the rest. The
machinery is already emitted at neighbouring coordinates, including
`FilmCardWrapperFetchers.rowsFilm`, which lifts a producer-handed record's primary key, anchors the
keys in a `VALUES(idx, key...)` table, joins the target table, selects exactly the live selection set
through the type's `$project`, and scatters the rows back to input order by index. At the model level
the passthrough is one named clause: `OperationMembers.mintsReentry` declines to mint the reentry
member for a root service field, so `OutputField.emitsKeyedReQuery()` is false there while
`requiresReFetch()` is true. Its comment says the re-projection is "realized by the downstream child
fetchers", which holds for a child that runs its own keyed query and does not hold for a plain column
read, where there is no downstream query at all.

The reason to state a rule rather than add an opt-in is that the declaration already exists in the
schema. `@table` on a GraphQL type says the type is that table's rows, so its column values come from
that table. A type with no `@table` whose backing class happens to be a jOOQ record keeps the direct
read it has today: `FilmDetails` is exactly that shape, and `FilmDetailsFetchers.title` reads the
column straight off whatever the producer returned. An author who wants to return column values that
differ from the table therefore drops `@table` and names the columns with `@field(name:)`, keeping the
same Java signature. That escape hatch needs saying out loud in the docs, because under the new rule
the same record from the same method behaves differently depending on whether the type it binds to
carries `@table`, and someone who meets that cold will read it as the mirror-image bug.

Four things for the plan to settle. Whether the escape hatch reaches what it needs to: dropping
`@table` also drops the catalog-driven surface, and `@node` requires `@table` explicitly, so an author
who wants to mask one column on an otherwise ordinary entity would be giving up global IDs to do it.
If per-caller column redaction has a home on the condition or tenant-scoping surface then nothing is
lost and the plan can say so; if it does not, that is a follow-up item rather than a blocker. Second,
tables with no primary key have nothing to key a refetch on, so they need a classify-time rejection
naming the table, which is a new build failure on schemas that currently build; there is precedent in
the existing rejection of a keyless `@table` parent hosting a batched child `@service`. Third, a
service that already selects every column, which is what the docs have been telling people to write,
pays one extra batched key lookup until it is rewritten to select keys only. Fourth, the refetch would
run on the request's `DSLContext`, so a service that deliberately read from another schema or tenant
connection would be refetched from the request's instead; the polymorphic path already has this
property, so the check is whether the multi-schema and tenant fixtures agree.

Two gaps to close alongside the fix. No fixture anywhere returns a key-only record from a plain root
`@service`, which is why the tier never caught this: `SampleQueryService.filmsByService` runs
`selectFrom(FILM)` and both execution tests select fully populated rows. And
`handle-services.adoc` states the inbound and the outbound record contracts on one page with nothing
connecting them, the inbound one under an `IMPORTANT` admonition saying a record the framework hands
you carries the key columns and nothing else. The reporter read that and applied it to their return
value, which is the mistake the page currently invites.

Reported externally as GitHub issue 534 against 10.0.0-RC33, with the RC34 generated code inspected
and unchanged; confirmed against the rewrite on 2026-08-25 by reading the generated sources in
`graphitron-sakila-example/target/generated-sources/`.
