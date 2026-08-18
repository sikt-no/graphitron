---
id: R704
title: "The @routine read surface: unwire the carve-outs, then derive them from facts"
status: Backlog
bucket: architecture
priority: 2
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The @routine read surface: unwire the carve-outs, then derive them from facts

`@routine` puts a table-valued function call in the FROM clause. `WHERE` and `ORDER BY` are
different clauses of the same statement, and nothing about a function in the FROM stops either from
being written. Yet a `@routine` field today cannot filter, cannot sort at the root position, cannot
paginate, and cannot declare its return type without restating the routine as a `@table`. Four
refusals, four different files, no shared seat.

This item owns the routine read surface end to end. It absorbs
`roadmap/routine-chain-order-directive-silent-noop.md` (R659), which reported one of the four and
diagnosed the shape behind all of them.

## Vocabulary

* **Table-valued function (TVF)**: a database function declared `RETURNS TABLE(...)` or `SETOF`, so
  calling it yields rows. jOOQ models one as a catalog `Table<R>` tagged `TableOptions.function()`,
  with a record class for its result row and a convenience method on the schema's `Routines` class.
  `@routine` accepts only this kind today.
* **Result table**: the catalog `Table<R>` jOOQ generates for such a function. Real columns, real
  record type, and no primary key and no foreign keys. That absence is the one fact behind every
  carve-out below.
* **Chain**: the ordered `@routine` and `@reference` applications on one field, walked as one
  running source (`FieldBuilder.walkRoutineChain`). Its **terminus** is the last node.
* **Read surface**: what a field's generated query carries besides its FROM. Filtering, fixed
  ordering, argument-driven ordering, pagination and lookup are five independent axes; the return
  binding is a sixth thing the field declares, adjacent to them.

## The census, corrected

The first draft of this item recorded the `@orderBy` and `@condition` refusals as deferred
capabilities. That was reading the diagnostic rather than the code, and it is wrong. Checking the
render layer, none of the four is a capability gap. They are unwired slots.

[cols="2,3,3"]
|===
| Axis | Refusal today | What the emit layer actually needs

| Fixed ordering (`@defaultOrder`)
| Honoured at a child position; silently discarded at root
| `RootLauncherRenderer.routineBody` calls `orderByStatement(ordering, terminal)` and chains `.orderBy(orderBy)`. The terminal local already exists.

| Argument ordering (`@orderBy`)
| Deferred, reported
| Same call. `OrderingBlock.declareSortView` is total over both `Ordering` arms, and the `Helper` arm's emitted `<field>OrderBy(env, table)` takes a table local, which the routine result table is.

| Filtering (`@condition`)
| Deferred, reported
| `conditionStatement(row, terminal)` already exists, already handles an absent slot, and `routineBody` already builds a `.where(...)` out of hop filters. AND the condition into it.

| Pagination (`@asConnection`)
| Rejected on routine terminus, deferred on catalog terminus
| `ResultShape.Connection` needs one thing a routine chain lacks: an `Ordering`. Everything else in the connection shape is source-agnostic.

| Return binding
| Must carry `@table`, and it must name the terminus
| A resolved terminus instead of a written directive. The only axis needing new model work.

| Lookup (`@lookupKey`)
| Deferred
| Genuinely unbuilt; stays with `roadmap/routine-chain-fetch-form-breadth.md` (R447).
|===

The render layer is already generic. Every fragment it would need takes `(thing, tableLocal)` and
switches on the command, never on the source: `OrderingBlock` is total over `Ordering.Columns` and
`Ordering.Helper`, `conditionStatement` treats an absent WHERE as data, and
`OperationMember.Condition.OnReturnTable` carries a plain `TableRef` with no key requirement
anywhere in the path. Nothing consults a primary key or a foreign key to build a WHERE or an
ORDER BY.

So the refusals do not live in the emitters. They live in four places upstream that hardcode
"empty":

* `RoutineDirectiveResolver.orderOrConditionDeferral`, one boolean over three directive spellings,
  emitting one message for two axes.
* `RoutineDirectiveResolver.resolve`, the return-shape demand.
* `FieldBuilder.routineChainVerdict`, the terminus rule and the Connection fork.
* `QueryField.QueryTableField`'s compact constructor, a four-way conjunction pinning the
  `RoutineResolution.Chain` read surface empty, plus a literal `List.of()` and a literal
  `new OrderBySpec.None()` at each chain classifier.

R659 named that triple exactly, from inside the ordering axis, and deferred the restructure as
unowned. Widening the census by three axes changes the arithmetic: the axes are not four problems
sharing a smell, they are one hardcode with four spellings.

## Why they all read as one refusal

Every carve-out is a restatement of the same catalog property: **a TVF result table has no primary
key and no foreign keys.** That is what removes the PK fallback from ordering, the FK machinery from
hops (hence the name-matched join), the default cursor from pagination, and the key tuple from
lookup. One property of one catalog object, phrased six times as a property of `@routine`.

Phrasing it as a property of the directive is what produced the drift. The pagination refusal says
the routine result "does not carry an ordering contract", conflating "carries no *default* ordering"
with "cannot be ordered". The same conflation made root `@defaultOrder` look exempt rather than
broken, while the child position has shipped ordered routine lists since the chain work landed, on
the same result table under the same absence of a primary key, ordered fine because the author
named columns.

## The rule the surface should state

One sentence, and it is the rule every other list field already lives under: **a list result is
ordered, by the terminus primary key when there is one and by an authored `@defaultOrder` when there
is not.** A routine terminus is the second case, so `@defaultOrder(fields:)` naming the routine's own
result columns is not optional there, it is the only spelling available and the build should say so.

That is not a routine-specific rule. It is `validateListRequiresOrdering` with the `Chain` exemption
removed, which is what R659 already specified.

## Two tracks

The four unwirings and the return binding pull in different directions, and conflating them is what
would make this item unshippable. Track A deletes carve-outs; Track B adds a fact. Track A is
therefore *not* leaf-zoo expansion, which is why it can ship first without violating the drain rule:
it removes pins and literals from the transitional surface rather than adding a leaf to it.

### Track A: unwire the read surface

One pass, because the four axes touch the same six sites and splitting them means writing the pin
restatement, the deferral message and the manual's deferral sentence twice each, with the
intermediate version wrong.

1. **`@defaultOrder` at root.** `classifyRootRoutineChain` calls `orderByResolver.resolve` the way
   `classifyChildRoutineChain` already does, instead of passing a literal `OrderBySpec.None`.
   `LauncherCommands.routineRow` passes `orderingOf(qtf, units)` instead of `null`.
   `RootLauncherRenderer.routineBody` emits `orderByStatement(ordering, terminal)` and
   `.orderBy(orderBy)`. `terminal` is the local the projection already targets, so sort columns and
   select list resolve against the same alias by construction.
2. **`@condition` and `@orderBy`.** Delete `orderOrConditionDeferral`. The chain classifiers stop
   passing `List.of()` for filters and route through `resolveTableFieldComponents` against the
   terminus, the same call the ordinary table arms make. `routineRow` gains its WHERE slot from the
   condition relation; `routineBody` declares the condition local and ANDs it with the hop filters
   it already composes.
3. **Ordering becomes required where no primary key can supply it.** Drop the `Chain` exemption in
   `validateListRequiresOrdering`. A list-shaped routine terminus with no `@defaultOrder` becomes a
   build error.
4. **Pagination follows.** With an `Ordering` resolving, the `DirectiveConflict` on a routine
   terminus loses its premise and becomes the ordinary pagination-requires-ordering rule. The
   remaining routine-specific work is the seek predicate over a lateral start; see the caveat under
   Track B's open questions.
5. **Unpin.** `QueryTableField`'s four-way conjunction goes. Whatever survives is stated per axis
   against the axis that owns it, so the next read-surface directive cannot fall through a
   conjunction and a predicate a class away.

### Track B: derive the surface from facts

Track A leaves the verdicts correct and still hardcoded. Track B removes the generator of holes, and
carries the one axis that cannot be done by deletion.

6. **Capture the routine catalog facts.** The store cannot answer any of this today. The `sql_`
   family has schema, table, column, constraint, primary key, referential constraint and index, and
   no routine anywhere. A TVF result table is captured as an ordinary `sql_table` row with
   `TableOptions.type().isFunction()` dropped on the floor, so the store cannot distinguish the one
   catalog property every carve-out turns on; `JooqCatalog.isTableValuedFunction` answers it from the
   live catalog inside the walk, which is the reach-past-the-facts the tier rule forbids. The
   routine's call surface (parameters, order, types, the generated `Routines` method) is not captured
   at all. Both are pure transcriptions of a catalog walk that already runs, and this slice is worth
   landing on its own merits: the store is currently lossy about a catalog object the generator
   depends on.
7. **The terminus and its kind as a derived view.** Where a coordinate's chain lands, and whether
   that landing is a function result. Every axis reads it, and it is what makes the verdicts
   comparable instead of six independent opinions.
8. **The return binding.** With slice 7 in hand, the `@table` demand becomes "the terminus is
   resolvable", not "the author wrote a directive". See below.
9. **Plan-tier pilot.** Re-source `routineRow` off facts rather than off the leaf.

## The return binding, in full

The reported message is

```
Field 'Query.mineTilganger': @routine requires a @table-annotated return type
```

correct for exactly one case. With `@reference` hops the chain lands on a catalog table and the
return must name it. When the routine result is the terminus, the demand is ceremony: the resolver
already holds the result table, since `JooqCatalog.resolveTableValuedFunction` returns it as a
`TableRef` on the same call that resolved the routine.

So the author writes the routine name twice, which is what the sakila fixtures do:

```graphql
type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
    organisasjonskode: Int
    rollekode: String
}
```

Three costs. The two names must agree or the terminus rule rejects. `@table` on a function result
reads as a claim the type is a stored table, and makes it usable as a plain root read against a
function with required arguments. And the type is welded to one routine, so two routines with the
same row shape cannot share a GraphQL type.

The consumer side of the implied binding already exists.
`GraphitronType.JooqTableRecordType` with a resolved table and a null class name already means "the
runtime source is a projected row of this table"; scalar fields under such a parent already resolve
to typed column reads (`FieldBuilder.resolveColumnOnJooqTableRecord`), and object fields already
resolve to record-parent DataLoader reads, which is the launch-a-new-query half.
`TypeBuilder.carrierVerdict` already mints exactly that stand-in for a DML payload carrier from the
producing edge rather than from the type's own directives.

This is the one axis that must not be done leaf-side. Adding a landing plus an emitter arm for
"routine field returning a record" is a new leaf type, which
`docs/architecture/explanation/fact-model.adoc` forbids in as many words ("a capability is added by
adding a fact relation, never a new leaf type") and which
`docs/architecture/explanation/pipeline-overview.adoc` restates as the migration's standing rule
("new facts land only in the store"). Hence slices 6 to 8.

One more syntactic reader to catch, because a fix that only moves the resolver leaves it behind:
`GraphitronSchemaBuilder.unsupportedFacetCarrierReason` reads `hasAppliedDirective(DIR_TABLE)` off
the SDL directly, so a resolved binding is invisible to it.

## Why this family is the right plan-tier pilot

`roadmap/planners-read-facts-emitters-read-commands.md` (R682) owns driving the plan tier onto facts
and names the difficulty honestly: the plan is the larger half, and no generated file has ever been
produced from the store. A pilot wants a family that is small, has a live oracle, and does not need
the emitter half moved at the same time. This one is all three.

* **The render half is already done.** The root routine chain already renders through the command
  layer, and `render` already lives under the structural guard forbidding leaf dispatch. Converting
  this family exercises exactly the plan tier with the tier below already conformant.
* **The command row is small and its holes are visible.** `routineRow` is about fifteen lines and
  hands two literal `null`s, the WHERE slot and the ordering. Those two nulls are two of the axes.
* **The oracle exists.** `LauncherRelationClosureTest` pins the launcher relation in both
  directions, and `CommandSeamRatchetTest`'s `PLAN_LEAF_REFERENCES` counter measures what this
  drives down.
* **The payoff is capability, not equal output.** Four axes change verdict.

If R682 is picked up first, this becomes one of its families rather than a pilot for it, and the
slices survive unchanged.

## Absorbed from R659

R659 reported the root `@defaultOrder` drop and was specced and Spec-reviewed before this item
widened the frame. Its analysis is load-bearing under the wider frame too, so it is carried here
rather than lost. What changed is only its central deferral claim: R659 kept `@orderBy` and
`@condition` deferred as "honest deferrals", and the census above shows they are not deferrals at
all, so a fix that ships R659's message and manual rewrites while keeping those two deferred would
publish text this item then deletes.

### The field report

```graphql
mineApplikasjonsAdminOrganisasjoner: [Organisasjon]
    @routine(name: "mine_applikasjons_admin_organisasjoner")
    @reference(path: [{table: "organisasjon"}])
    @defaultOrder(primaryKey: true)
```

Classifies clean, reports nothing, and the generated SQL carries no `ORDER BY`. The consumer found
it only because a test happened to assert list order; the workaround was deleting the directive and
documenting "order is undefined, the client sorts".

### The rule bites existing schemas

Making ordering required is a breaking change for consumer schemas, and deliberately so: every
schema it breaks is one currently shipping unsorted rows. Size it before starting.

`classifyRootRoutineChain` serves the degenerate single-node chain as well as the routine-then-hops
chain, and a single-node root routine's terminus is always the PK-less TVF result table. So the
population that breaks is **every single-node root routine list in the wild**, which is the dominant
documented shape and includes the manual's own canonical `@routine` example. That example and the
sakila schema grow `@defaultOrder(fields: [...])` in the same commit as the validator change.

The two terminus kinds land differently.

* **Catalog terminus**: the primary-key fallback in `OrderByResolver.resolveDefaultOrderSpec`
  applies, so these gain a deterministic `ORDER BY` with no schema edit.
  `Query.recentFilmsForActor` in the sakila example is this case and starts emitting
  `ORDER BY film.FILM_ID`.
* **Routine terminus**: no primary key, so the fallback lands `None` and the author must write
  `@defaultOrder(fields: [...])` over the routine's result columns. `Query.tilganger` is this case;
  its function returns `(organisasjonskode, rollekode)`, so the fix is one directive.

### Two author-facing messages are wrong on the path this forces authors down

Fixing them is in scope, not polish: an enforcement that tells the author to do something impossible
is worse than the silent no-op it replaces.

* **The validator's message.** "Add a primary key to the target table, or use `@defaultOrder` or
  `@orderBy`" is wrong on two of three counts for a routine terminus, since the author cannot add a
  primary key to a function result. (`@orderBy` becomes true here under Track A, which is one of the
  reasons to do the axes together.) The routine arm needs its own message, naming the routine and
  pointing at `fields:`.
* **`@defaultOrder(primaryKey: true)` on a routine terminus.** Literally what the field report wrote.
  On a PK-less result table `OrderByResolver.resolveOrderEntries` returns `null` and the caller lands
  `Rejected("could not resolve @defaultOrder columns in table 'X'")`, which says neither why nor what
  to write instead. It should say the result table has no primary key and that `fields:` is the
  surface, listing the routine's exposed result columns as candidates.

### Ordering target: measured, not assumed

Resolving ordering against the terminus means that for a `@routine` + `@reference` chain the
`ORDER BY` targets the joined catalog table, not the routine result. Whether naming the catalog
column is *faster* was measured rather than argued, on PostgreSQL 16 over a 500k-row synthetic pair
(the sakila seed is too small to give the planner a choice).

The plans are byte-identical either way, for an inlinable `LANGUAGE sql` function and an opaque
`LANGUAGE plpgsql` one alike, with and without a `LIMIT`. The hop out of a routine result is an
equi-join on the ordering column, so the two columns sit in one equivalence class and the planner
picks freely. **Which side the generator names is not a performance lever**, and no ordering-target
optimisation should be built on the assumption that it is.

Terminus resolution is the right default on expressiveness grounds instead: `@defaultOrder(fields:)`
naming a terminus-only column is expressible only against the terminus. The converse (a column
existing only on the routine result, unreachable once a hop follows) is
`roadmap/routine-chain-ordering-spans-nodes.md` (R662), which nothing here forecloses.

Note the pin should **not** be keyed on terminus kind instead, which was considered and rejected. A
routine terminus is perfectly orderable: `Actor.films` and `Film.castFilms` both terminate on a
routine result, both carry `@defaultOrder(fields: [{name: "film_id"}])`, and both work today. What a
routine terminus lacks is a primary key, so terminus kind governs only whether the PK fallback can
fire. Pinning on it would forbid at root exactly what the child position ships.

### Prose that the change falsifies

Every one of these asserts the absent read surface and must move in the same commit as the code:

* `ResultShape.RecordList`'s javadoc naming root `@routine` chains as a population with an absent
  ordering slot. This is the load-bearing one: it is the stated contract for a nullable slot in the
  command vocabulary.
* `LauncherCommands.routineRow`'s javadoc, "No WHERE slot and no ordering".
* `FieldBuilder.classifyRootRoutineChain`'s "Ordering note" paragraph.
* `QueryField`'s class javadoc on the `Chain` read surface being constructor-pinned empty.
* `validateListRequiresOrdering`'s javadoc claiming `@orderBy` / `@defaultOrder` on `@routine` is a
  classify-time typed deferral.
* `RootLauncherRenderer.routineBody`'s "No condition local: the leaf carries no filter surface".
* `orderOrConditionDeferral`'s message, "no filter or order surface ships for routine-backed
  fields". This one is a string emitted to authors, not a comment, and the whole method goes.
* `docs/manual/reference/directives/routine.adoc`, whose Constraints section states the `@table`
  demand, the deferral list and the Connection rules, and whose `@defaultOrder` prose is written
  from the child position.

### Tests

* **Classification**: around 27 test methods in `GraphitronSchemaBuilderTest` declare a
  list-returning root `@routine` field with no `@defaultOrder`, and none break, because the class
  builds through `TestSchemaHelper.buildSchema` which classifies without validating. Leave them
  alone rather than sprinkling `@defaultOrder` over fixtures asserting something else. Give
  `rootRoutineThenHopsChainClassifiesWithNameMatchedHop` an explicit slot assertion so the PK
  fallback is pinned rather than assumed, and add a case per terminus kind.
* **Validation**: a `ValidateListRequiresOrderingPipelineTest` case for the routine-terminus root,
  asserting the routine-specific message rather than the generic one.
* **Execution**: the reported bug is a wrong-order result, so it only closes at the execution tier.
  `RoutineFieldExecutionTest` gains exact-row-order assertions on `Query.tilganger` (routine-terminus
  `@defaultOrder`) and `Query.recentFilmsForActor` (catalog-terminus PK fallback), plus filtered and
  sorted cases once `@condition` and `@orderBy` wire up. The reporter's gap survived precisely
  because only one incidental test asserted order.
* **Corpus**: the existing `routine-table-valued-read` example is the routine-terminus root
  `Query.tilganger` with no `@defaultOrder`. `ClassifiedHarness` classifies without validating, so it
  will not fail; it would quietly render an SDL shape the real build rejects into the
  code-generation-triggers documentation. Give it the directive in the same commit.

Row order is behaviour, not shape, so the pipeline-tier slot assertion and the execution-tier
row-order assertion are both load-bearing and neither substitutes for the other. Asserting on the
generated `.orderBy(...)` string is banned at every tier by `development-principles.adoc` and would
prove nothing about the rows that come back.

### Documentation

`docs/manual/reference/directives/routine.adoc` currently lists `@orderBy` and `@condition` as
deferred and says nothing about `@defaultOrder` at root, and its `@defaultOrder` prose is written
from the child position. The rewrite states one rule for both positions and all four axes: a
routine-backed field filters, sorts and paginates like any other field, a catalog terminus falls
back to the terminus primary key, and a routine terminus must name its result columns because a
function result has none. If that does not read as one sentence per terminus kind, the carving is
wrong and should change first.

## Related items, and what stays with them

* `roadmap/routine-chain-ordering-spans-nodes.md` (R662): ordering naming columns from any chain
  node. Stays its own item; its `depends-on` moves from R659 to here. Its single-alias finding about
  `OrderByFragments.fixedColumnParts` is the same finding pagination's cursor columns will hit.
* `roadmap/routine-chain-fetch-form-breadth.md` (R447): multi-routine chains, `@lookupKey`
  composition, record-backed and interface parents. Fetch *forms*, not read-surface axes, and they
  stay there. Lookup is the one census row that stays with R447.
* `roadmap/routine-chain-residue.md` (R448): the root-ordering reconciliation bullet is discharged
  here; the `DataType` binding and corpus bullets are unaffected.
* `roadmap/root-family-validator-mirror-gaps.md` (R558): bullet (1) asks for a validate-time twin
  for exactly the skip Track A removes, and should be struck when this lands rather than implemented
  twice.
* `roadmap/list-ordering-invariant-enforcement.md` (R677): owns the never-unsorted-list invariant
  across all five known leak sites. This item closes the routine-root site and does **not** claim
  the invariant; R677 stays the cross-cutting owner.
* `roadmap/split-query-child-list-drops-default-order.md` (R663) and
  `roadmap/routine-write-key-capture-unordered.md` (R660): two other leak sites in R677's census,
  untouched here.
* `roadmap/routine-write-result-shapes.md` (R454): the Mutation write side, procedures and
  scalar/void routines. Its call surface is the same one slice 6 would capture, so the two should be
  read together before slice 6 fixes a shape.
* `roadmap/planners-read-facts-emitters-read-commands.md` (R682) and
  `roadmap/delivery-verdict-derives-from-the-store.md` (R666): the architecture Track B pilots, and
  the nearest precedent for replacing a hand-maintained negative-space switch with a store
  derivation.

## Open questions

* **Track split, or one delivery?** Track A is shippable without Track B and fixes a live production
  defect. Track B is the reason the defect existed. Sequencing A then B repeats no work (A deletes,
  B re-sources what remains), but a reviewer may prefer B first so A lands already store-derived.
* **Which node does a filter target on a multi-node chain?** Ordering resolves against the terminus
  and `@condition` should match, but both aliases are live in the emitted query and the author may
  reasonably want to filter the routine result before the hop. Same shape as R662's question, one
  clause over; decide the two together or state explicitly that filtering is terminus-only for now.
* **Pagination's seek predicate over a lateral start.** The connection shape is source-agnostic but
  the keyset predicate and cursor round-trip are not obviously so when the FROM is a lateral call.
  Establish that before promising the axis; it is the one Track A item that might not be pure
  wiring.
* **Is `sql_routine` a subject, or is function-ness a column on `sql_table`?** A TVF has a table
  identity and a callable identity and they are not the same object; the parameters belong to the
  callable. Non-table-valued routines (R454's territory) have a callable and no table at all, which
  argues for the separate relation.
* **Grain of the verdict relation.** One relation per axis, or one keyed by (coordinate, axis) with a
  closed axis vocabulary? The latter is tempting and probably wrong: the axes carry different
  payloads, so a shared relation goes wide and sparse or pushes payload to a side table per axis.
* **Two routines, one return type.** Fine under an edge-scoped binding, a conflict under a
  type-scoped one. Follows from where the binding fact is keyed, which is slice 8's real decision.
* **Does `@table` on a routine return stay legal?** Assume yes so existing schemas keep compiling.
  Confirm nothing starts flagging the now-redundant annotation, and decide whether the sakila
  fixtures migrate or keep one of each form.
