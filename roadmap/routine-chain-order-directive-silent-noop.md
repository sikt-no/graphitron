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

Prose asserting the absent-ordering contract is spread across six sites, all of which the fix
falsifies. `ResultShape.RecordList`'s javadoc is the load-bearing one, because it is the stated
contract for a nullable slot in the command vocabulary:

* `ResultShape.RecordList` names "classified root `@routine` chains" as one of three populations
  with an absent ordering slot; after the fix there are two.
* `LauncherCommands.routineRow`'s javadoc: "No WHERE slot and no ordering".
* `FieldBuilder.classifyRootRoutineChain`'s "Ordering note" paragraph.
* `QueryField`'s class javadoc on the `Chain` read surface being constructor-pinned empty.
* `validateListRequiresOrdering`'s javadoc claims "`@orderBy` / `@defaultOrder` on `@routine`
  is a classify-time typed deferral". The `@defaultOrder` half is false today.
* `docs/manual/reference/directives/routine.adoc` lists `@orderBy` and `@condition` on
  routine-backed fields as reported deferred, and says nothing about `@defaultOrder` at root.

One of these is a string, not a comment: `orderOrConditionDeferral`'s message reads "no filter or
order surface ships for routine-backed fields", and it is emitted to authors. It becomes false
the moment the fix lands.

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

`RoutineDirectiveResolver.orderOrConditionDeferral` keeps deferring `@orderBy` (argument-driven,
needing the `Ordering.Helper` arm and a runtime sort surface over a routine result) and
`@condition` (a filter surface, not an ordering one). Both are honest deferrals that do report,
and neither is the reported bug. Only its message changes, since it stops being true.

### The shape that produced this bug

Worth stating, because a fix that leaves it in place invites the next instance. The reported
defect is not "the root classifier forgot a call". Four independent read-surface axes (filters,
fixed order, argument-driven order, lookup) are governed by one boolean predicate in
`orderOrConditionDeferral`, one four-way conjunction in `QueryTableField`'s pin, and one
`List.of()` literal at each chain classifier, with nothing binding the three. `@defaultOrder`
fell through because it was absent from the predicate and pinned by the conjunction. This is the
"duplicated hardcoded skip-lists that must agree, with nothing binding them" smell from
`development-principles.adoc`, and after the fix as scoped above the generator of holes is still
there: the constructor keeps pinning `filters` empty on the authority of a predicate a class
away, and the next read-surface directive falls through both again.

The stronger shape is one gate and one message per axis, with the pin asserting each slot
against the axis that owns it, so "fixed ordering supported, argument ordering deferred,
filtering deferred" reads as three axes each carrying its own verdict rather than an exception
carved out of a blob. Whether that lands here or as a follow-up is the reviewer's call: it is
strictly larger than the reported bug and touches arms this item otherwise leaves alone, but it
is also the difference between fixing an instance and fixing the class.

### Ordering target: measured, not assumed

Resolving against the terminus already means that for a `@routine` + `@reference` chain the
`ORDER BY` targets the joined catalog table, not the routine result. The question of whether
naming the catalog column is *faster* than naming the routine's column was raised and measured
rather than argued, on PostgreSQL 16 over a 500k-row synthetic pair, since the sakila seed is too
small to give the planner a choice.

The plans are byte-identical either way, both for an inlinable `LANGUAGE sql` function and for an
opaque `LANGUAGE plpgsql` one, and both with and without a `LIMIT` (the case where the sort node
actually disappears in favour of a merge join over the PK index). The reason is that the hop out
of a routine result is an equi-join on the ordering column, so the two columns sit in one
equivalence class and the planner picks from it freely: in the `LIMIT` case it sorts the function
output and merge-joins *even when the query names the catalog column*. Which side the generator
names is not a performance lever, and no ordering-target optimisation should be built on the
assumption that it is.

What the equivalence does not cover is a column outside the join key. `@defaultOrder(fields:)`
naming a terminus-only column (`film.title`) is expressible only against the terminus, and a
routine-result-only column only against the routine result. Terminus resolution is therefore the
correct target on expressiveness grounds, which is a stronger reason than performance and does
not depend on a planner detail.

That cuts both ways, and the other half is out of scope here: resolving against the terminus
means a column existing only on the routine's result is unreachable once a `@reference` hop
follows it, even though its alias is live in the emitted query. Widening ordering to name columns
from any chain node is filed as `roadmap/routine-chain-ordering-spans-nodes.md` (R662), which
depends on this item. Nothing in this item forecloses it: the terminus stays the default target
there too.

The real cost of this item is not which column is named but that an `ORDER BY` now exists where
none did. That is the price of the contract being true, and it is worth paying.

Note the pin should *not* be keyed on terminus kind instead, which was considered and rejected.
A routine terminus is perfectly orderable: `Actor.films` and `Film.castFilms` in the sakila
schema both terminate on a routine result, both carry `@defaultOrder(fields: [{name:
"film_id"}])`, and both work today. What a routine terminus lacks is a primary key, so terminus
kind governs only whether the PK fallback can fire. Pinning the `orderBy` slot on it would forbid
at root exactly what the child position ships, re-introducing the asymmetry this item exists to
remove.

## The rule bites existing schemas

Removing the exemption is a breaking change for consumer schemas, and deliberately so: every
schema it breaks is one currently shipping unsorted rows. The break is wider than the test
fixtures suggest, and the implementer should size it before starting.

`classifyRootRoutineChain` serves the degenerate single-node chain as well as the
routine-then-hops chain: a root `@routine` with no `@reference` application routes there with
`hops = []` and the routine result as its own terminus. Since `routineChainVerdict` requires the
terminus to denote the same jOOQ table as the `@table` return type, and the catalog hands back
the *function's* result table, a single-node root routine's ordering target is always a PK-less
TVF result table. So the population that breaks is every single-node root routine list in the
wild, which is the dominant documented shape and includes the manual's own canonical `@routine`
example. That example and the sakila schema both grow `@defaultOrder(fields: [...])` in the same
commit as the validator change; leaving the manual showing a form that no longer builds is not
an option.

The two terminus kinds land differently.

* **Catalog terminus**: the primary-key fallback in `OrderByResolver.resolveDefaultOrderSpec`
  applies, so these fields gain a deterministic `ORDER BY` with no schema edit.
  `Query.recentFilmsForActor` in the sakila example schema is this case and needs no change; it
  starts emitting `ORDER BY film.FILM_ID`.
* **Routine terminus**: a table-valued function's result table carries no primary key, so the
  fallback lands `None` and the author must write `@defaultOrder(fields: [...])` over the
  routine's own result columns. This is the same demand the child position already makes, and
  the manual already documents that spelling. `Query.tilganger` in the sakila example schema is
  this case; its function returns `(organisasjonskode, rollekode)`, so the fix is one directive.

Two author-facing messages are wrong for the routine terminus, and both are on the path the fix
forces authors down. Fixing them is in scope, not polish: an enforcement that tells the author to
do something impossible is worse than the silent no-op it replaces.

* **The validator's message.** "Add a primary key to the target table, or use `@defaultOrder` or
  `@orderBy`" is wrong on two of three counts for a routine terminus: the author cannot add a
  primary key to a function result, and `@orderBy` is deferred there. The routine arm needs its
  own message, naming the routine and pointing at `fields:`.
* **`@defaultOrder(primaryKey: true)` on a routine terminus.** This is literally what the field
  report wrote, and on a PK-less result table `OrderByResolver.resolveOrderEntries` returns
  `null` (the `findPkColumns` branch), so the caller lands `Rejected("could not resolve
  @defaultOrder columns in table 'X'")`. That says neither why nor what to write instead. It
  should say the result table has no primary key and that `fields:` is the surface, listing the
  routine's exposed result columns as candidates.

## Tests

* **Classification**: around 27 test methods in `GraphitronSchemaBuilderTest` declare a
  list-returning root `@routine` field with no `@defaultOrder`, and none of them break. The class
  builds through `TestSchemaHelper.buildSchema` (and `CatalogBuilder.buildSnapshot`), both of
  which classify without running `GraphitronSchemaValidator`; the validator runs only where a
  test calls it explicitly, and no routine fixture does. So the routine-terminus fixtures that
  classify clean, `queryRoutineProjectionCarriesRoutineCoordinates`,
  `rootSingleNodeRoutineDesugarsToRoutineSourcedTableFieldWithEmptyHops` and
  `routineDotPathArgMappingLandsPathExprChain`, keep landing `OrderBySpec.None` and keep passing;
  leave them alone rather than sprinkling `@defaultOrder` over fixtures that assert something
  else. Catalog-terminus fixtures such as `rootRoutineThenHopsChainClassifiesWithNameMatchedHop`
  silently gain the primary-key order; give that one an explicit slot assertion so the fallback
  is pinned rather than assumed. Add a case per terminus kind asserting the resulting
  `OrderBySpec.Fixed`. The rejection case for the list-shaped routine terminus with no directive
  belongs in the validation tier below, since the validator is what rejects it.
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
  as pure verdict. The existing `routine-table-valued-read` example is the routine-terminus root
  `Query.tilganger` with no `@defaultOrder`, and `ClassifiedHarness` classifies without
  validating, so it will not fail; it would quietly render an SDL shape the real build rejects
  into the code-generation-triggers documentation. Give it the directive in the same commit.

Row order is behaviour, not shape, so the pipeline-tier slot assertion and the execution-tier
row-order assertion are both load-bearing and neither substitutes for the other. The tempting
shortcut here is asserting on the generated `.orderBy(...)` string; that is banned at every tier
by `development-principles.adoc` and would prove nothing about the rows that come back.

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
* **The Mutation write path**, filed as `roadmap/routine-write-key-capture-unordered.md` (R660).
  The gap there is not an exemption but non-membership: `MutationRoutineWriteField` implements
  `MutationField` alone, not `SqlGeneratingField`, so it never reaches `validateListRequiresOrdering`
  at all. Its step 2 is a genuine keyed `SELECT ... .fetch()` with no `ORDER BY` and it is the
  field's visible result, so `Mutation.rentFilm` ships unordered list data today under exactly
  the thesis this item asserts.

  That reframing is what makes the split honest rather than convenient. "Never unsorted" is an
  invariant whose sole enforcer only sees `SqlGeneratingField` members, so a list-shaped root
  leaf outside that capability is a silent skip, which `development-principles.adoc` names as
  candidate roadmap material for a membership meta-test. Shipping this item's enforcement for
  Query chains while an equally unordered Mutation chain stays silent is the drift the axiom
  warns about. The split is acceptable because R660 exists and is named here; if the reviewer
  would rather close the class than the instance, the membership check belongs in this item
  instead.
* **LSP column resolution for the routine terminus.** `FieldClassification.lspColumnDispatch`
  places `RoutineBacked` in the `FallThrough` arm, so `@defaultOrder(fields: [{name: ...}])`
  resolves candidates and diagnostics against the enclosing type's `@table` rather than the
  routine's result table. This item makes that spelling mandatory for a routine terminus, so the
  authoring surface for the thing we are now requiring gives wrong completions and false
  diagnostics. A `Resolve(<result table>)` arm is the fix; file it alongside, and land it in the
  same cycle if it stays this small.

Related: `roadmap/routine-chain-residue.md` (R448) holds the root-ordering reconciliation as
non-gating residue and is discharged by this item.

