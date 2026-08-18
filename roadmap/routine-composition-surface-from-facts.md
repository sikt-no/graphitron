---
id: R704
title: "The @routine composition surface derives from facts"
status: Backlog
bucket: architecture
priority: 2
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The @routine composition surface derives from facts

Three field reports arrive as three unrelated complaints, and they are one defect. A `@routine`
field cannot declare its return type without also restating the routine as a `@table`; it cannot
declare a fixed sort order at the root position; and it cannot paginate. Each is refused, deferred
or silently dropped by its own hardcoded verdict in its own file, and no relation binds the three.

This item owns the frame: what a `@routine` coordinate composes with is a set of independent axes,
each axis resolves to its own verdict, and that verdict should be a derived fact rather than a
conjunction spread across four classes. It also owns the two capability changes that fall straight
out of the frame, and it is a deliberately small pilot for the plan tier of
`roadmap/planners-read-facts-emitters-read-commands.md` (R682).

## Vocabulary

* **Table-valued function (TVF)**: a database function declared `RETURNS TABLE(...)` or `SETOF`, so
  calling it yields rows. jOOQ models one as a first-class catalog `Table<R>` tagged
  `TableOptions.function()`, with a generated record class for its result row and a convenience
  method on the schema's `Routines` class. `@routine` accepts only this kind today.
* **Result table**: the catalog `Table<R>` jOOQ generates for such a function. It has columns and a
  record type. It has no primary key and no foreign keys, which is the fact most of the carve-outs
  below actually turn on.
* **Chain**: the ordered `@routine` and `@reference` applications on one field, walked as one
  running source (`FieldBuilder.walkRoutineChain`). Its **terminus** is the last node.
* **Composition axis**: one thing a field's read surface can carry, independent of the others.
  Filtering, fixed ordering, argument-driven ordering, pagination, lookup, and the return binding
  are six axes; a field carries any subset.

## The axis census

Every row is a separate refusal, written in a separate place, in a separate vocabulary. Read the
right-hand column as the census's actual finding: there is no shared seat.

[cols="2,3,3"]
|===
| Axis | Verdict today | Where it is stated

| Return binding
| The return type must carry `@table`, and it must name the chain terminus
| `RoutineDirectiveResolver.resolve` (the shape demand) and `FieldBuilder.routineChainVerdict` (the terminus rule)

| Fixed ordering (`@defaultOrder`)
| Honoured at a child position; silently discarded at root
| `FieldBuilder.classifyChildRoutineChain` calls `OrderByResolver`; `classifyRootRoutineChain` passes a literal `new OrderBySpec.None()`

| Argument ordering (`@orderBy`)
| Deferred, reported
| `RoutineDirectiveResolver.orderOrConditionDeferral`, an argument-directive scan

| Filtering (`@condition`)
| Deferred, reported
| the same predicate, same message

| Pagination (`@asConnection`)
| Rejected on a routine terminus, deferred on a catalog terminus
| `FieldBuilder.routineChainVerdict`, a wrapper check

| Lookup (`@lookupKey`)
| Deferred
| `roadmap/routine-chain-fetch-form-breadth.md` (R447), no classify-time seat at all
|===

Three of the six are additionally pinned shut a second time, by the four-way conjunction in
`QueryField.QueryTableField`'s compact constructor, which asserts the `RoutineResolution.Chain`
read surface is empty on the authority of a predicate a class away.

`roadmap/routine-chain-order-directive-silent-noop.md` (R659) reached this diagnosis from inside one
axis and named it exactly: one boolean predicate, one four-way conjunction, and one `List.of()`
literal per chain classifier, with nothing binding them. It then deferred the restructure on the
grounds that its own reported bug had a second home elsewhere, and said the shared enforcement
question "is nobody's rider". This item is where it lands, widened by the two axes R659 did not
look at.

## Why it reads as six gaps and is one

Every carve-out above is a different spelling of the same underlying fact: **a TVF result table has
no primary key and no foreign keys.** That single catalog property is what makes the PK fallback
unavailable to ordering, the FK machinery unavailable to hops (hence the name-matched join), the
default cursor unavailable to pagination, and the key tuple unavailable to lookup. It is one fact
about one catalog object, and the generator restates it six times as six unrelated verdicts, each
phrased as a property of `@routine` rather than of the terminus.

That is why the verdicts drift. The pagination refusal says "the routine result does not carry an
ordering contract", which conflates "carries no *default* ordering" with "cannot be ordered". The
same conflation is what made root `@defaultOrder` look exempt rather than broken. Meanwhile the
child position has shipped ordered routine lists since the chain work landed, which is the same
routine result under the same absence of a primary key, ordered fine because the author named
columns.

## What the report asked for: the return binding

The reported message is

```
Field 'Query.mineTilganger': @routine requires a @table-annotated return type
```

and it is correct for exactly one case. When the field carries `@reference` hops the chain lands on
a catalog table, and the return must name it. When the routine result is itself the terminus (the
single-node chain, at root or as a correlated child) the demand is ceremony: the resolver is holding
the result table already, since `JooqCatalog.resolveTableValuedFunction` returns it as a `TableRef`
on the same call that resolved the routine.

So the author writes the routine name twice:

```graphql
type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
    organisasjonskode: Int
    rollekode: String
}
```

which is the shape the sakila fixtures use (`Tilgang`, `ActorFilm`). Three costs. The two names must
agree or the terminus rule rejects. `@table` on a function result reads as a claim the type is a
stored table, and makes it usable as a plain root read against a function with required arguments.
And the type is welded to one routine, so two routines with the same row shape cannot share a
GraphQL type.

The wanted spelling drops the annotation and lets the producing field supply the binding, giving the
type the routine's own result record. The consumer side of that is already built:
`GraphitronType.JooqTableRecordType` with a resolved table and a null class name already means "the
runtime source is a projected row of this table", scalar fields under it already resolve to typed
column reads, and object fields under it already resolve to record-parent DataLoader reads, which is
the "launch a new query" half. `TypeBuilder.carrierVerdict` already mints exactly that stand-in for
a DML payload carrier from the producing edge rather than from the type's own directives.

Note the second syntactic reader of the same directive, because a fix that only moves the resolver
leaves it behind: `GraphitronSchemaBuilder.unsupportedFacetCarrierReason` reads
`elementObj.hasAppliedDirective(DIR_TABLE)` off the SDL directly. Any binding that is resolved
rather than written is invisible to it.

## Pagination: the premise expires

`@asConnection` over a routine terminus is refused as a `DirectiveConflict`, on the stated grounds
that keyset pagination needs an ordering contract the routine result does not carry. That premise is
a statement about the *default*, and it stops being true the moment root ordering resolves.

The command vocabulary already says so. `ResultShape.Connection` requires exactly one thing a
routine chain lacks today, an `Ordering`, and its own javadoc records that the ordering "serves both
views the page request needs, the sort fields and the cursor columns". `LauncherCommands.routineRow`
hands `ResultShape.RecordList(null)`; every other root arm hands `orderingOf(...)`. Nothing else
about the connection shape is routine-specific.

So the honest verdict is conditional, and it is the rule every other connection coordinate already
lives under: paginate when a total ordering resolves, refuse when one does not, with the refusal
naming the missing ordering rather than the directive. A routine terminus reaches it by
`@defaultOrder(fields:)` over its own result columns; a catalog terminus reaches it by the primary
key. Whether the resulting seek key is *unique* is the author's problem on a routine result exactly
as it is on a table with no unique index, and it is not a reason to refuse the axis wholesale.

This does not make pagination free. The seek predicate and cursor round-trip still have to be
rendered over a chain whose start is a lateral call, and `OrderByFragments.fixedColumnParts` carries
the same single-alias assumption `roadmap/routine-chain-ordering-spans-nodes.md` (R662) documents for
sort columns. What expires is the refusal's justification, not the work.

## Why this should not be built in the leaf zoo

The obvious implementation of the return binding is a new classified leaf: a routine field whose
return is a record rather than a table, with an emitter arm to match. That is the wrong move, and
the project already has the rule in writing.

`docs/architecture/explanation/fact-model.adoc`, under "Facts, not leaves": *a capability is added by
adding a fact relation, never a new leaf type, because facts add where leaf types multiply.*
`docs/architecture/explanation/pipeline-overview.adoc` states the same as the migration's standing
rule: the classification walk is *a surface being drained, not a place to extend*, and *new facts
land only in the store*.

The routine surface is the textbook instance of what that rule is about. Six independent axes, each
with its own verdict, times terminus kind, times wrapper, times root or child position. Welded into
leaves that is a cross-product, and the census above is what the cross-product looks like after only
part of it has been enumerated: the parts nobody enumerated are the silent drop and the stale
refusal. Held as facts it is six relations, each an independent functional dependency of a
coordinate, joined by whoever needs a combination.

Stated the way "Name the row, not the question" demands, without naming a consumer:

* This coordinate applies this routine at this position in its chain. (Captured:
  `graphitron_routine`.)
* This chain terminates on this catalog object.
* This catalog object is a function result rather than a stored table.
* This function takes these parameters, in this order, of these types.
* This coordinate's ordering resolves to these columns, in this order. (Authored half captured:
  `graphitron_default_order`, `graphitron_default_order_field`.)
* This coordinate's pagination resolves against this ordering.

The verdict per axis is then a view over those, and "supported, deferred with a reason, refused with
a reason" is a column rather than a rejection ladder in a classifier.

## What the store is missing

The authored half is already captured. `graphitron_routine` carries one row per application with the
written `argMapping` and `columnMapping` plus their decoded pair children, and its comment already
records that the chain interleaves it with `graphitron_field_reference` in written order.
`graphitron_default_order` and its `_field` child carry the ordering the author wrote.

The catalog half does not exist. The `sql_` family has `sql_schema`, `sql_table`, `sql_column`,
`sql_constraint`, `sql_primary_key`, `sql_referential_constraint`, `sql_index` and
`sql_index_column`, and no routine anywhere. Two consequences, and the first is a capture fidelity
gap independent of everything else in this item:

* **A TVF result table is captured as an ordinary `sql_table` row with nothing marking it as a
  function result.** Capture is total over `JooqCatalog.allTableEntries`, so the row is there, but
  `TableOptions.type().isFunction()` is dropped on the floor. The store therefore cannot answer the
  one catalog question every carve-out above turns on. `JooqCatalog.isTableValuedFunction` answers it
  from the live catalog, which is precisely the reach-past-the-facts the tier rule forbids.
* **A routine's call surface is not captured at all**: not its parameters, not their order, not
  their Java types, not the generated `Routines` method that returns the configured table. All of it
  is reflected live in `JooqCatalog.resolveTableValuedFunction`, inside the classification walk.

So the fact work is bounded and nameable: a function-ness discriminator on `sql_table` (or a
`sql_routine` relation beside it, if the parameters make it a subject of its own), and
`sql_routine_parameter` under it. Both are pure transcriptions of a catalog walk that already runs.

## Why this family is the right pilot

R682 owns driving the plan tier onto facts and names the difficulty honestly: the plan is the larger
half, and no generated file has ever been produced from the store. A pilot wants a family that is
small, has a live oracle, and does not need the emitter half moved at the same time. This one is all
three.

* **The render half is already done.** The root routine chain already renders through the command
  layer: `LauncherCommands.routineRow` produces a `LauncherCommand` with a `LaunchSource.RoutineChain`
  and `RootLauncherRenderer.routineBody` folds it. `render` already lives under the structural guard
  that forbids leaf dispatch. So converting this family exercises exactly the plan tier, with the
  tier below it already conformant.
* **The command row is small and its holes are visible.** `routineRow` is about fifteen lines, and it
  hands two literal `null`s (the WHERE slot and the ordering) whose justification is a leaf
  constructor's pin. Those two nulls *are* two of the six axes. Filling them from facts is the whole
  pilot, and the diff is legible.
* **The oracle exists.** `LauncherRelationClosureTest` pins the launcher relation in both directions,
  and `CommandSeamRatchetTest`'s `PLAN_LEAF_REFERENCES` counter measures exactly what this drives
  down.
* **The capability payoff is real, not a refactor.** Two of the axes change verdict, so the pilot
  ships user-visible behaviour rather than asking for a rewrite budget on equal output.

## Slices

Each is independently shippable and the order is the dependency order.

1. **Capture the routine catalog facts.** Function-ness on `sql_table`, plus the routine's call
   surface. No reader yet; `FactCaptureAgreementTest` registers them and pins them against the live
   catalog. This slice is worth landing regardless of the rest, because the store is currently
   lossy about a catalog object the generator depends on.
2. **The terminus and its kind as a derived view.** Where a coordinate's chain lands, and whether
   that landing is a function result. This is the fact all six axes read, and it is the one that
   makes the six verdicts comparable.
3. **The return binding.** With slice 2 in hand, the `@table` demand becomes "the terminus is
   resolvable", not "the author wrote a directive". The implied result-record binding falls out, and
   the reported message narrows to the hop-carrying chain where it is true.
4. **Ordering and pagination re-verdict.** Fill `routineRow`'s two nulls from facts; the pagination
   refusal becomes conditional on the resolved ordering. Sequencing against R659 is the open
   question below.
5. **Retire the scattered verdicts.** The predicate in `orderOrConditionDeferral`, the conjunction in
   `QueryTableField`'s constructor, and the literals in both chain classifiers all read the per-axis
   view instead. This is the slice that stops the next axis from falling through.

## The sequencing decision this item forces

R659 is at Spec and owns the root `@defaultOrder` fix. Its plan is entirely leaf-side: classifier
call, constructor pin, validator exemption, plus the one `LauncherCommands.routineRow` line. It is a
good plan for the bug it names and it is the opposite of the direction above.

Two honest options, and this is the user's call rather than a detail for whoever picks the item up.

* **Ship R659 first, as the instance fix.** The reported bug is unsorted rows in production and it
  should not wait behind an architecture pilot. R704 then re-sources what R659 hardcodes, and R659's
  own "the generator of holes is still there" paragraph is discharged here. Cost: one axis gets
  built twice, and R659's breaking-change work (every single-node root routine list in the wild
  needs `@defaultOrder(fields:)`) lands under the old frame.
* **Fold R659 into this item and re-spec on fact ground.** One pass over the axis, no rework. Cost:
  a reported production defect waits for slices 1 and 2, and R659's Spec review work is partly
  re-done.

Recommendation: ship R659 as written. Its breaking-change analysis, its message rewrites and its
execution-tier ordering assertions are load-bearing under either frame and do not become wrong, and
a production wrong-order defect is not a good hostage. But R659 should stop describing the axis
restructure as unowned residue and point here instead.

## Related items, and what stays with them

* `roadmap/routine-chain-order-directive-silent-noop.md` (R659): the root `@defaultOrder` fix. See
  above.
* `roadmap/routine-chain-ordering-spans-nodes.md` (R662): ordering naming columns from any chain
  node. Orthogonal to the axis frame and stays its own item; its single-alias finding about
  `OrderByFragments` applies to the cursor columns this item's pagination slice would need.
* `roadmap/routine-chain-fetch-form-breadth.md` (R447): multi-routine chains, `@lookupKey`
  composition, record-backed and interface parents. These are fetch *forms*, not composition axes,
  and stay there. The lookup row in the census above is the one overlap, and it stays with R447.
* `roadmap/routine-chain-residue.md` (R448): the root-ordering bullet is discharged by R659; the
  `DataType` binding and corpus bullets are unaffected.
* `roadmap/routine-write-result-shapes.md` (R454) and `roadmap/routine-write-key-capture-unordered.md`
  (R660): the Mutation side. R660 is a third home of the unordered-list class and is named by R659.
* `roadmap/split-query-child-list-drops-default-order.md` (R663): the second home of that class, at
  the command projection. Its shared-enforcement question (re-source the deterministic-order rule
  off the launcher relation's ordering slot) is answered by this item's slice 4 for the routine
  family and should be read together with it.
* `roadmap/planners-read-facts-emitters-read-commands.md` (R682): owns the seam this pilots. If R682
  is picked up first, this item becomes one of its families rather than a pilot for it, and the
  capability slices survive unchanged.
* `roadmap/delivery-verdict-derives-from-the-store.md` (R666): the nearest precedent for replacing a
  hand-maintained negative-space switch with a store derivation, which is structurally what slice 5
  does.

## Open questions

* **Grain of the verdict relation.** One relation per axis, or one relation keyed by (coordinate,
  axis) with a closed axis vocabulary? The latter is tempting and probably wrong: the axes carry
  different payloads (an ordering carries columns, a return binding carries a table), and a shared
  relation would either go wide and sparse or push the payload out to a side table per axis anyway.
* **Is `sql_routine` a subject, or is function-ness a column?** A TVF has both a table identity and a
  callable identity, and they are not the same object. The parameters belong to the callable. If
  non-table-valued routines are ever captured (R454's territory), they have a callable and no table
  at all, which argues for the separate relation.
* **Does the implied return binding need an opt-in marker,** or is a directiveless object type
  reached from a `@routine` field unambiguous enough? The directiveless-nesting look-ahead in
  `TypeBuilder` is the nearest precedent for deciding without a marker.
* **Two routines, one return type.** Under an edge-scoped binding this is two bindings and fine;
  under a type-scoped one it is a conflict. The answer follows from where the binding fact is keyed,
  which is slice 3's real decision.
* **Does `@table` on a routine return stay legal?** Assume yes, so existing schemas keep compiling.
  Confirm nothing starts flagging the now-redundant annotation, and decide whether the sakila
  fixtures migrate or keep one of each form.
