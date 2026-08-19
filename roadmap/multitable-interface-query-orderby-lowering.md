---
id: R382
title: "Lower orderBy onto multitable-interface/union queries"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-08-19
---

# Lower orderBy onto multitable-interface/union queries

## Problem

A root query field returning a multitable interface or union (`QueryField.QueryInterfaceField` /
`QueryField.QueryUnionField`, and the `@asConnection` variant) cannot carry an ordering at all,
authored or user-supplied. Neither record declares an `orderBy` component, so neither implements
`SqlGeneratingField`, and the emitter orders results solely by the synthetic `__sort__` key (the
participant PK). A consumer asking for a specific order gets PK order regardless, and so does an
author who declared one with `@defaultOrder`.

That missing slot is also why nothing rejects the field. Both cross-cutting ordering checks,
`GraphitronSchemaValidator.validatePaginationRequiresOrdering` and `validateListRequiresOrdering`,
are gated on `field instanceof SqlGeneratingField` and read `sgf.orderBy()`, so they never see
these two arms; `validateQueryInterfaceField` and `validateQueryUnionField` check cardinality and
participants only. A paginated multitable root with no ordering is exactly the shape
`validatePaginationRequiresOrdering` exists to reject, and it passes.

Split off from R363, which deliberately scoped its day-one work to `@field` filter lowering (the
reported data-correctness bug) and left ordering to this item. The two are siblings: both lower a
per-field surface onto a polymorphic UNION, both must hold the "column present on every participant"
rule (lowered per participant against each participant's own table, so an absent or
type-incompatible column on one participant becomes that participant's classifier rejection), and
both thread their result into each UNION branch in `MultiTablePolymorphicEmitter`.

## Field report

Reported on 10.0.0-RC30 at https://github.com/sikt-no/graphitron/issues/523, filed 2026-08-12,
which is the headline half of that issue. The item predates the report by seven weeks; the report
adds four things it did not have.

**The authored half fails too, not just the argument.** Their field is
`Query.applikasjoner: [Applikasjon]` carrying `@asConnection(defaultFirstValue: 100)`,
`@defaultOrder(fields: [{name: "NAVN"}])`, and an `orderBy:` argument with `@orderBy` over an
`@order` enum. `Applikasjon` is a multitable interface with three `@table` implementations, and all
three carry `NAVN`, so the "column present on every participant" rule this item has to hold is
satisfied by their schema. At runtime `direction: ASC` and `direction: DESC` return byte-identical
result pages in `subjekt_id` order. The problem statement above framed this as user-specified
ordering being unavailable; the report shows the field-level declaration is discarded on the same
arm, which widens what the fix has to cover.

**A build-time rejection is an acceptable outcome to the reporter, and they name the precedent.**
They ask for ordering to work, "or, if multitable ordering is unsupported, an author-error at
generate time like the one `@condition`-overloads produce, so the schema author finds out at build
time rather than the client at runtime." That is worth taking seriously as a separable first
increment: the lowering design below is genuinely hard, the rejection is a few lines in
`validateQueryInterfaceField` / `validateQueryUnionField`, and shipping the rejection first turns a
silent wrong answer into a located author error without pre-committing the harder design. It does
break schemas that currently compile, which is the tradeoff to weigh, and the schemas it breaks are
the ones already getting wrong results.

**The report landed on the connection arm, which is the harder one.** `@asConnection` means
`__sort__` is the Relay cursor seek key, so their coordinate needs the whole cursor-codec half of
the design described below, not just the sort. Their interim workaround is client-side sorting of
each fetched page, which they note is imperfect across pagination, and that imperfection is the
cursor problem showing through.

**Filters on the same field work, which isolates the failure.** They confirm `@condition` filters
on this query behave correctly, so the input reaches the generated SQL and only the ordering is
lost. That corroborates R363's landed filter lowering on the same fields and rules out a general
argument-binding fault on the arm.

One docs consequence: they went looking for a stated limitation first. Neither the Sorting nor the
Polymorphic queries page says ordering is unwired for multitable, while the `@condition` section
explicitly documents multitable support, which reasonably reads as ordering being supported too.
Whichever way this item resolves, one of those pages needs the statement.

The same issue's follow-up comment reports a second, distinct coordinate, owned by
`roadmap/split-query-child-list-drops-default-order.md` (R663). The reporter reads the two as one
bug. They are not the same defect, but they share a consumer and a schema, so fixing either alone
leaves that schema unordered at the other end.

## Why this is harder than filter lowering

Filters AND into each branch's `.where(...)` and bind to that branch's alias with no effect on the
union's shape. Ordering is structural:

- `MultiTablePolymorphicEmitter.branchProjection` hardcodes `__sort__` as the participant PK
  (single-column PK projects directly; composite uses `DSL.jsonbArray(...)`).
- `buildStage1Block` / `buildMainFetcher` (non-connection root) order by `DSL.field(name("__sort__"))`.
- On the connection path (`buildRootConnectionFetcher` / `buildStage1ConnectionBlock`), `__sort__`
  **is the Relay cursor seek key**: it is projected as `sortField`, fed to `page.seekFields()`, and
  round-tripped through `ConnectionHelper.encodeCursor` / `decodeCursor`, with `__typename ASC` as a
  deterministic tiebreaker so identical PKs across participants page consistently.

So a user orderBy column has to be projected into every UNION branch, *replace* (or compose with)
`__sort__` as the sort and cursor seek key, keep a deterministic tiebreaker so cross-participant ties
still page consistently, and round-trip through the cursor codec (which today assumes the PK column
class, or JSONB for composite). Mixed sort directions and multi-key orderings compound this. This is
the design work the item owns.

## Cross-links

Sibling of R363 (per-participant `@field` filter lowering on the same fields); shares
`MultiTablePolymorphicEmitter`. The single-table discriminator interface
(`QueryTableInterfaceField`) already carries `OrderBySpec` and is unaffected; only the two multitable
polymorphic variants lack it.

* `roadmap/split-query-child-list-drops-default-order.md` (R663): the other coordinate in the same
  field report. Worth reading beside this one for the validator asymmetry, which is sharper than
  either item alone shows. On R663's coordinate the ordering slot is populated and
  `validateListRequiresOrdering` *compels* the `@defaultOrder` that emit then discards. Here there
  is no slot, so the same check cannot see the field and the directive is accepted in silence. One
  validator, two opposite failures, same lost `ORDER BY`.
* `roadmap/list-ordering-invariant-enforcement.md` (R677): the class behind both, an ordering the
  model resolved that never reaches the emitted SQL with nothing comparing the two ends. This
  coordinate is one of the leak sites in its census. Note that this one leaks a step earlier than
  the others: the model never resolves the ordering in the first place, so an invariant checked at
  the model-to-SQL boundary would not catch it. Whatever R677 lands has to notice a field with no
  ordering slot as well as one whose slot is dropped.
