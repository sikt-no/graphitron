---
id: R650
title: "Support @asConnection on a root field returning a single-table discriminated interface"
status: Backlog
bucket: feature
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Support @asConnection on a root field returning a single-table discriminated interface

## Problem

A root query field returning a single-table discriminated interface (`@table` + `@discriminate`,
implementers pinned by `@discriminator(value:)`, all sharing one jOOQ table) cannot be paginated.
`FieldBuilder` rejects the pair with a typed deferral, "`@asConnection` on a root field returning a
single-table discriminated interface ('X') is not yet supported; return the list shape instead", so
a consumer wanting a paginated `ContentConnection` has to fall back to an unbounded
`[Content!]!`. Reported by a consumer.

The list shape works and the multi-table polymorphic root already paginates
(`MultiTablePolymorphicEmitter.buildRootConnectionFetcher`), so the deferral is narrower than it
looks: it is one missing emission over an assembly that otherwise exists. Nothing pins the
rejection today (no test asserts the message), so lifting it un-asserts nothing.

## Why the emission is missing

`DiscriminatedTableFragments.assembly` populates a `LinkedHashSet<Field<?>> fields` (the
`__discriminator__` routing alias, each single-table branch's `$project`, the joined-table
participants' base slice, the selection-gated cross-table and detail aliases) and then, in the same
fragment, declares `SelectJoinStep<Record> step = dsl.select(new ArrayList<>(fields)).from(base)`
before chaining the discriminator-gated LEFT JOINs. The caller finishes the chain.

The connection arm cannot reuse that as-is: `ConnectionHelper.pageRequest(...)` takes the selection
list as an argument and returns the merged `page.selectFields()` (selection union cursor columns,
name-deduped), which is what `dsl.select(...)` must receive. The page request therefore has to be
emitted *between* the field-list population and the `step` declaration, and those two are currently
welded into one fragment.

## Shape of the work (to be confirmed at Spec)

* Split `DiscriminatedTableFragments.assembly` at the `step` declaration so the select-list
  expression is a parameter. The four existing call sites (root launcher, child twin, the service
  single-table-interface fetcher, the two DML discriminated follow-ups, the latter three routed via
  `TypeFetcherGenerator.buildTableInterfaceReprojection`) keep passing `new ArrayList<>(fields)`.
* `LauncherCommands.interfaceRow` gains the `FieldWrapper.Connection` fork that
  `resultShapeOf` already has for the plain table root, including its no-resolvable-ordering
  invariant throw.
* Drop the `(DiscriminatedTable, Connection)` half of the `LauncherCommand` constructor backstop;
  the single-tenant half stays.
* `RootLauncherRenderer`'s discriminated arm renders a connection body instead of throwing:
  the four fixed pagination argument reads, `pageRequest`, then
  `step.where(condition).orderBy(page.effectiveOrderBy()).seek(page.seekFields()).limit(page.limit()).fetch()`,
  then the carrier construction.

Ordering is not a blocker: the base table's PK gives `OrderBySpec.Fixed` by default,
`@orderBy` on this coordinate already lowers, and the order-by helper is already emitted for
`QueryTableInterfaceField`. `validatePaginationRequiresOrdering` already covers the leaf.
The thin entry point (`buildQueryTableFetcher`) reads the row's value type, and
`FetcherRegistrationsEmitter` binds `edges` / `nodes` / `pageInfo` / `totalCount` per connection
*type*, so neither needs work. The existing `TypeResolver` routes off `__discriminator__`, which the
assembly projects unconditionally, so no new resolver.

`totalCount` should come out correct for free: the assembly ANDs the discriminator `IN` into
`condition` before the caller binds it onto the carrier, so the lazy `count(*)` over
`(base table, condition)` counts the right rows without needing the joins.

## Open questions for Spec

* **Row fan-out under `limit`.** The gated LEFT JOINs are FK-equality joins. If any can match more
  than one row per base row, `.limit()` slices rows rather than entities and page boundaries
  corrupt (the same fan-out under the list shape only shows as duplicates). Either the FK
  direction check already guarantees one row per base row here, which wants proving, or the
  connection arm needs a floor.
* **Faceted search stays out.** `facetPlanOf` is computed for `QueryTableField` only; the honest
  day-one scope passes no facet plan.

## Coverage sketch

A `ClassifiedCorpus` example (which also refreshes the generated supported-schema-shapes doc), an
exact-SQL pin in `RootLauncherSqlBaselineTest`, and execution-tier coverage over the existing
sakila fixtures: `allContent` for the plain single-table case and `allSubjects` for the
joined-table participants, the latter being the one that exercises the base slice plus detail LEFT
JOIN under seek pagination.

## Provenance

Both `R405` (root `@service` return) and `R406` (DML return) explicitly recorded `@asConnection`
as out of scope for this interface family; this item picks up the root read half. Sibling to
`roadmap/child-connection-over-discriminated-interface.md`, which owns the child field and the
`@splitQuery` design question it depends on. Distinct axis from `R382` (ordering on *multitable*
interface and union queries).
