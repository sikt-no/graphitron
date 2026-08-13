---
id: R659
title: "@defaultOrder on a root routine chain is silently dropped"
status: Spec
bucket: bug
theme: routine
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# @defaultOrder on a root routine chain is silently dropped

Field report (Sikt tilgangsstyring). A root `@routine` + `@reference` chain terminating on a
catalog table, carrying `@defaultOrder(primaryKey: true)`, classifies clean and reports nothing:

```graphql
mineApplikasjonsAdminOrganisasjoner: [Organisasjon]
    @routine(name: "mine_applikasjons_admin_organisasjoner")
    @reference(path: [{table: "organisasjon"}])
    @defaultOrder(primaryKey: true)
```

The generated SQL carries no `ORDER BY` and rows arrive in hash order. The schema declares a
sorting contract the runtime does not honour, and nothing warns the author. The consumer found
it only because a test happened to assert list order; the workaround was deleting the directive
and documenting "order is undefined, the client sorts".

## Diagnosis

`RoutineDirectiveResolver.orderOrConditionDeferral` is the deferral that fires for the
composition surfaces, and it checks exactly three things: `@condition` on the field,
`@condition` on an argument, `@orderBy` on an argument. `@defaultOrder` is absent from that
set, so it passes through.

The two chain classifiers then diverge on what they do with it:

* `FieldBuilder.classifyChildRoutineChain` calls `OrderByResolver.resolve` against the chain's
  terminus table and carries the resulting `OrderBySpec`. `@defaultOrder` on a child routine
  list works, and the manual's `recentFilms` example is a child field, so the documented shape
  is the one that happens to be honoured.
* `FieldBuilder.classifyRootRoutineChain` never consults `OrderByResolver` at all; it passes a
  literal `new OrderBySpec.None()` into `QueryField.QueryTableField`, whose compact constructor
  pins the `RoutineResolution.Chain` read surface empty. The directive is discarded between
  parse and model with no diagnostic.

`GraphitronSchemaValidator.validateListRequiresOrdering` cannot catch the fallout either: it
exempts the `Chain` arm outright, so the list-shaped-plus-`None` signal that protects every
other list field is switched off precisely where the drop happens.

Two documentation surfaces overstate what ships today:

* `docs/manual/reference/directives/routine.adoc` lists `@orderBy` and `@condition` on
  routine-backed fields as reported deferred, and says nothing about `@defaultOrder`.
* `validateListRequiresOrdering`'s javadoc claims "`@orderBy` / `@defaultOrder` on `@routine`
  is a classify-time typed deferral". The `@defaultOrder` half of that sentence is false.

## Position

Deferring the directive is not the outcome. An unsorted list result is a defect regardless of
which directive the author wrote, so this item makes `@defaultOrder` work at the root position
and turns the residual hole into a build error. The deterministic-order rule that already
guards every other list field is the enforcement; the routine arm simply stops being exempt
from it.

This discharges R448's "root ordering reconciliation" bullet.

## Implementation

The command vocabulary needed for this already exists and is exercised by the non-routine root
arms: `ResultShape.RecordList` carries a nullable `Ordering`, `Ordering.Columns` borrows
`OrderBySpec.Fixed` outright, `LauncherCommands.orderingOf` projects one from the other, and
`RootLauncherRenderer.orderByStatement` / `OrderByFragments.fixedSortParts` render it. Nothing
new is minted; five sites stop hardcoding "unordered".

* **`FieldBuilder.classifyRootRoutineChain`** calls `orderByResolver.resolve(List.of(),
  fieldDef, <terminus table>)` and carries the resulting spec, exactly as
  `classifyChildRoutineChain` already does. Drop the literal `new OrderBySpec.None()` and the
  javadoc's "the chain root carries no ordering surface" paragraph. A `Resolved.Rejected` lands
  `UnclassifiedField`, matching the child arm.
* **`QueryField.QueryTableField`** relaxes the `RoutineResolution.Chain` compact-constructor pin
  for the `orderBy` slot; `filters`, `pagination` and `lookup` stay pinned empty, and the
  message is restated so the surviving pin reads as one rule rather than a list with a hole in
  it.
* **`GraphitronSchemaValidator.validateListRequiresOrdering`** drops the `Chain` exemption, so a
  list-shaped root chain landing `OrderBySpec.None` becomes a build error naming the coordinate.
  Its javadoc's claim that `@defaultOrder` on `@routine` is a classify-time deferral goes with
  it; the claim is false today and would be doubly false after.
* **`LauncherCommands.routineRow`** passes `orderingOf(qtf, units)` into `ResultShape.RecordList`
  instead of the hardcoded `null`, and `ResultShape.RecordList`'s javadoc drops root `@routine`
  chains from its list of populations with an absent ordering slot.
* **`RootLauncherRenderer.routineBody`** emits `orderByStatement(ordering, terminal)` and
  `.orderBy(orderBy)` on the select builder. `terminal` is the local the projection already
  targets (the routine-call local for a hop-less chain, the last hop's alias otherwise), so the
  sort columns and the select list resolve against the same alias by construction.

`RoutineDirectiveResolver.orderOrConditionDeferral` is left alone. It covers `@orderBy`
(argument-driven, needing the `Ordering.Helper` arm and a runtime sort surface over a routine
result) and `@condition` (a filter surface, not an ordering one); both are honest deferrals that
do report, and neither is the reported bug. Fixed ordering is the surface that makes the
contract truthful, and it is the surface the child position already ships.

## The rule bites existing schemas

Removing the exemption is a breaking change for consumer schemas, and deliberately so: every
schema it breaks is one currently shipping unsorted rows. The break is narrower than it looks,
because the two terminus kinds land differently.

* **Catalog terminus**: the primary-key fallback in `OrderByResolver.resolveDefaultOrderSpec`
  applies, so these fields gain a deterministic `ORDER BY` with no schema edit.
  `Query.recentFilmsForActor` in the sakila example schema is this case and needs no change; it
  starts emitting `ORDER BY film.FILM_ID`.
* **Routine terminus**: a table-valued function's result table carries no primary key, so the
  fallback lands `None` and the author must write `@defaultOrder(fields: [...])` over the
  routine's own result columns. This is the same demand the child position already makes, and
  the manual already documents that spelling. `Query.tilganger` in the sakila example schema is
  this case; its function returns `(organisasjonskode, rollekode)`, so the fix is one directive.

The build error must say which of the two the author is in, because the remedies differ: a
catalog terminus that still lands `None` means the terminus table has no primary key, while a
routine terminus means the result columns have to be named. The existing message ("Add a primary
key to the target table, or use `@defaultOrder` or `@orderBy`") is wrong on both counts for a
routine terminus, since the author cannot add a primary key to a function result and `@orderBy`
is deferred there. Give the routine arm its own message naming the routine and pointing at
`fields:`.

## Tests

* **Classification**: around 27 test methods in `GraphitronSchemaBuilderTest` declare a
  list-returning root `@routine` field with no `@defaultOrder`, but most are rejection fixtures
  whose field lands `UnclassifiedField` before validation runs and are therefore untouched. The
  ones that need the directive added are the ones that classify clean on a *routine* terminus:
  `queryRoutineProjectionCarriesRoutineCoordinates`,
  `rootSingleNodeRoutineDesugarsToRoutineSourcedTableFieldWithEmptyHops` and
  `routineDotPathArgMappingLandsPathExprChain` are the ones to check first. Catalog-terminus
  fixtures such as `rootRoutineThenHopsChainClassifiesWithNameMatchedHop` keep compiling and
  silently gain the primary-key order; give that one an explicit slot assertion so the fallback
  is pinned rather than assumed. Add a case per terminus kind asserting the resulting
  `OrderBySpec.Fixed`, and a rejection case for the list-shaped routine terminus with no
  directive.
* **Validation**: a `ValidateListRequiresOrderingPipelineTest` case for the routine-terminus
  root, asserting the routine-specific message rather than the generic one.
* **Execution**: the reported bug is a wrong-order result, so it only closes at the execution
  tier. `RoutineFieldExecutionTest` gains an ordering assertion on `Query.tilganger` (a
  `@defaultOrder` over routine result columns, asserting exact row order rather than set
  membership) and on `Query.recentFilmsForActor` (the catalog-terminus PK fallback). The
  reporter's gap survived precisely because only one incidental test asserted order; assert it
  deliberately here.
* **Corpus**: the two terminus kinds are classification verdicts worth an entry in
  `ClassifiedCorpus` per the classified-corpus loop, retiring whatever the routine block holds
  as pure verdict.

## User documentation (first-client check)

`docs/manual/reference/directives/routine.adoc` currently lists `@orderBy` and `@condition` as
the deferred composition surfaces and says nothing about `@defaultOrder` at root, while its
`@defaultOrder` prose is written from the child position. The rewrite states one rule for both
positions: a routine-backed list orders like any other list, a catalog terminus falls back to the
terminus primary key, and a routine terminus must name its result columns because a function
result has none. The deferral sentence keeps `@orderBy` and `@condition` and loses any
implication that fixed ordering is deferred with them. If that rule does not read as one
sentence per terminus kind, the carving in the validator is wrong and should change first.

## Out of scope

* **`@orderBy` (argument-driven) and `@condition` on routine-backed fields** stay deferred, and
  both already report. Ordering that the client picks at query time is a different capability
  from a schema-declared default, and the `Ordering.Helper` arm it would use is untouched here.
* **The Mutation write path.** `MutationField.MutationRoutineWriteField` carries no ordering slot
  at all, and its step-1 key capture in `TypeFetcherGenerator` fetches the routine result with no
  `ORDER BY`. The payload data field's re-read is then exempted by `validateListRequiresOrdering`'s
  `requiresReFetch()` clause on the stated grounds that the scatter re-keys rows to the upstream
  source order, but for a routine write that upstream order is itself unordered, so the exemption
  rests on a premise the write path does not supply. `Mutation.rentFilm` in the sakila example
  schema is a live instance. Same defect class, different model seam (a write's key capture, not
  a read's order surface), so it is filed separately as
  `roadmap/routine-write-key-capture-unordered.md` (R660) rather than widening this item.
* **LSP column resolution for the routine terminus.** `FieldClassification.lspColumnDispatch`
  places `RoutineBacked` in the `FallThrough` arm, so `@defaultOrder(fields: [{name: ...}])`
  resolves candidates and diagnostics against the enclosing type's `@table` rather than the
  routine's result table. This item makes that spelling mandatory for a routine terminus, so the
  authoring surface for the thing we are now requiring gives wrong completions and false
  diagnostics. A `Resolve(<result table>)` arm is the fix; file it alongside, and land it in the
  same cycle if it stays this small.

Related: `roadmap/routine-chain-residue.md` (R448) holds the root-ordering reconciliation as
non-gating residue and is discharged by this item.

