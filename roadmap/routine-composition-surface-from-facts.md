---
id: R704
title: "The @routine read surface: unwire the carve-outs, then derive them from facts"
status: In Progress
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
being written. Yet a `@routine` field today cannot filter, cannot sort at the root position, and
cannot paginate. It cannot declare its return type without restating the routine as a `@table`, and
a child of that return type cannot reach its own `@table`-bound target without restating that too.
Five refusals, five different files, no shared seat.

This item owns the routine read surface end to end, on both sides of the routine result: the query
that runs the function, and the hops that leave its rows. It absorbs two items, both discarded into it and recorded in
`roadmap/changelog.md`: R659 (`routine-chain-order-directive-silent-noop`), which reported one of
the refusals and diagnosed the shape behind them, and R622
(`routine-carrier-explicit-data-field-path`), which owned the write seat's mirror of the hop gap.

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

| Hop to a `@table` child
| Requires an explicit `@reference` naming a table the child's return type already names
| Nothing. `synthesizeNameMatchedJoin` already resolves it; the element-less inference arm just lacks the gate that reaches it.

| Lookup (`@lookupKey`)
| Deferred
| Genuinely unbuilt; stays with `roadmap/routine-chain-fetch-form-breadth.md` (R447).
|===

**Status.** Track A has landed. The first six rows now read as capabilities rather than refusals,
and lookup is the one row that stays with R447. The census below is kept in the tense it was
diagnosed in, because it is what the two tracks are argued from; the slice list under Track A
carries what actually shipped.

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

Plus one that is not a hardcode but a missing gate: `BuildContext.parsePath`'s element-less FK
inference never asks whether its source is a routine result, though the `{table:}` element branch in
the same file does.

R659 named the first triple exactly, from inside the ordering axis, and deferred the restructure as
unowned. Widening the census changes the arithmetic: these are not five problems sharing a smell,
they are one catalog fact the generator restates as five unrelated refusals.

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

The unwirings and the return binding pull in different directions, and conflating them is what
would make this item unshippable. Track A deletes carve-outs; Track B adds a fact. Track A is
therefore *not* leaf-zoo expansion, which is why it can ship first without violating the drain rule:
it removes pins and literals from the transitional surface rather than adding a leaf to it.

**Delivery: one item, Track A first, then Track B.** Not two items, and not B first. Three reasons,
all of which a reviewer is free to overturn.

Sequencing A then B repeats no work, because the tracks act on the same sites in opposite
directions: A deletes hardcoded emptiness, B re-sources what survives the deletion. Doing B first
would mean deriving the four verdicts from facts while the pins that override them are still in
place, so the derivation would be unobservable until A landed anyway.

Splitting into two items would put the census, the vocabulary and the one-catalog-property diagnosis
in one item and the fix for half the census in another. That is the factoring this item was created
to undo, and it is what let R659 and R622 drift to opposite verdicts on the same relation between the
same two catalog objects.

Keeping one item does mean Track A's landing is a phase note rather than a Done transition. That is
the ordinary shape for a multi-phase item here, and it is worth the cost: the live defect stops
shipping unsorted rows at the end of Track A, without the item claiming completion while the
generator of the holes is still in place.

### Track A: unwire the read surface (landed)

One pass, because the axes touch the same handful of sites and splitting them means writing the pin
restatement, the deferral message and the manual's deferral sentence twice each, with the
intermediate version wrong.

1. **`@defaultOrder` at root.** Landed. Both chain classifiers resolve the whole read surface
   through `resolveTableFieldComponents` against the terminus, the same call the ordinary table
   arms make.
2. **`@condition` and `@orderBy`.** Landed. Two findings the plan did not have. The routine's own
   IN-parameter arguments have to be excluded from the read surface's argument classification, or
   they classify as unbound filters; the exclusion set is read off the resolved bindings rather
   than off the directive text, so `argMapping` and identity binding are excluded by one rule. And
   the seat gate is one seat wider than the plan said: `classifyMutationRoutineChain` reaches the
   resolver through `walkRoutineChain` too, so the deferral survives as
   `RoutineDirectiveResolver.writeSeatReadSurfaceDeferral` called from *both* Mutation routine
   classifiers rather than sitting on `resolveCarrierNode` alone. The write-side read surface stays
   with `roadmap/routine-write-result-shapes.md` (R454).
3. **Ordering becomes required where no primary key can supply it.** Landed. The `Chain` exemption
   is gone, and the routine arm carries its own message: the generic one's "add a primary key to
   the target table" is impossible on a function result, so the routine arm names the function and
   points at `@defaultOrder(fields:)`. `@defaultOrder(primaryKey: true)` over any table with no
   primary key now says the key is absent and lists the columns available.
4. **Pagination follows.** Landed, and it was more than the lost premise. `routineBody` had no
   Connection arm and `connectionBody` was typed to `LaunchSource.AnchorTable`, so the arm needed
   building rather than unblocking. What made it small was noticing that the hops belong in a
   joined table expression rather than in the select's join chain: one `Table<?>` local is then the
   FROM, the seek's source and the connection carrier's count source at once, which is what stops
   `totalCount` counting the terminus alone on a chain with hops. `connectionBody` now takes the
   FROM local and the projected alias separately; an anchor-sourced connection passes the same
   local twice. The seek predicate needed nothing, exactly as measured below.
5. **The implicit hop out of a routine result.** Landed. `parsePath`'s element-less arm has the
   function-ness gate the `{table:}` branch already had.
6. **Unpin.** Landed. `QueryTableField`'s four-way conjunction is down to the lookup axis and the
   terminus rule, and the Connection fork left `routineChainVerdict` for the three seats that still
   refuse it, each stating its own reason: the child read seat (a child connection rides the
   batched keyed re-query anchor), the Mutation chain write seat (the post-commit re-read is keyed,
   not paged), and nothing at the root read seat, which paginates.

### Track B: derive the surface from facts

Track A leaves the verdicts correct and still hardcoded. Track B removes the generator of holes, and
carries the one axis that cannot be done by deletion.

7. **Capture the routine catalog facts.** *Landed.* Two additions, because the census was lossy
   about two different objects. `sql_table.table_type` records jOOQ's `TableOptions.TableType` for
   every row, so the store can finally distinguish a base table from a view and, the value this
   track needs, a table-valued function's result from either; a reader asking whether a name is
   table-valued now asks a column instead of reaching back into the live catalog mid-walk.
   `sql_routine` and `sql_routine_parameter` capture the callable behind a function-typed table: the
   generated `Routines` class and the value-parameter method the parameters belong to, then those
   parameters in declaration order with their Java binding types. R668's four handovers were all
   honoured: the facts come off the resolved `Table<?>` through a new `JooqCatalog.routineCallFactsOf`
   sitting beside the name-keyed resolution the way `candidateKeys(Table<?>)` sits beside its own,
   the record carries type names rather than javapoet, `table_type` landed, and the parameter
   relation names its method and owns the `-parameters` dependency in the `jooq_name` column comment
   (the agreement test asserts the captured names are the database's own and not `arg0`, so losing
   the flag fails rather than degrades).

   Two findings the plan did not have. **jOOQ generates no `Routine` object for a table-valued
   function at all**, only the result-table class and the `Routines` convenience method; the
   per-routine classes in the `routines` sub-package are exactly the non-table-valued ones, which is
   what makes those a separate population rather than a subset. So R668's "decide at pickup" on the
   SQL-side parameter vocabulary resolved to omitting both columns: the database's parameter names
   survive only as jOOQ's camelCase transform of them, and the SQL types only as anonymous bind
   placeholders behind a protected `TableImpl` field that a module-path jOOQ would refuse to open.
   The relation comment carries the finding so the next reader does not re-derive it. **A routine
   with no generated call surface is a real arm**, `RoutineResolution.NoConvenienceMethod` already
   having one, so the class and method names are nullable and their nullness is what separates a
   routine that takes no parameters from one whose call surface is not exposed.
8. **The terminus and its kind as a derived view.** *Landed, together with slice 10's arm.*
   `intent_field_chain_terminus` answers, per coordinate, which table the chain lands on and what
   kind of table that is, carrying `sql_table.table_type` straight through so the whole read
   surface asks one column rather than six opinions. The seed is the last `@routine`
   application's result table; the tail is the `@reference` applications written after it, walked
   one element at a time through `intent_field_reference_step_hop`. Absence means "not reached",
   on the target view's terms.

   Three findings, one of which reordered the slice list. **Slice 10's hop arm had to land
   first**, because without it nothing departs a function result at all: every arm of the hop view
   joined `sql_referential_constraint`, so a routine-then-hop chain resolved to zero rows whichever
   way the terminus view was written, and a slice-8-alone landing would have meant a test pinning
   that hole and deleting it one slice later. The two are one commit for that reason. **Slice 10's
   coupling to slice 9 is narrower than the plan recorded**: the anchor problem is about a *type's*
   binding seeding `intent_field_reference_step_target` for child fields of a routine-result type,
   not about the chain, which seeds from `@routine(name:)` and needs no `@table` anywhere. So the
   arm and the chain walk are both independent of slice 9's keying decision, and what stays coupled
   is only the child-field population slice 10's second sentence is about. And **the spelling view
   was missing a site**: `intent_spelled_table`'s population enumerated every authored table name
   except `@routine(name:)`, though jOOQ models a function result as a catalog table and the view's
   own comment says the rule does not vary by site. Adding it is what lets the chain seed resolve
   like any other spelling instead of growing a routine-specific resolution.

   One overlap recorded rather than acted on. `intent_field_column_scope` is the existing relation
   answering "which table do the column names written at this field resolve against", which is the
   same question the ordering and filtering axes ask, and its three rules are disjoint by
   construction. A chain field falls through them today: its `PATH_TERMINAL` rule walks from the
   enclosing type's binding, which a chain does not depart from, and its `NAMED_TYPE_TABLE` rule
   answers a routine-terminal child from the `@table` ceremony slice 9 removes. A fourth
   `CHAIN_TERMINAL` rule reading `intent_field_chain_terminus` belongs there, and it has to arrive
   with guards on the other two, since a chain field can satisfy either. That is a change to a
   view with live consumers and its own anchor, so it is named here rather than folded in: it
   belongs with slice 12's retirement pass or beside slice 9, whichever reaches the column-scope
   readers first.

   *Narrowed by slice 9's store half, to two cases and one defect.* With both binding rules reading
   the resolution, a child-position single-node routine field now resolves through `NAMED_TYPE_TABLE`
   for free, its named type being bound by the return derivation. What a `CHAIN_TERMINAL` rule is
   still needed for is the root position, which that rule's root-parent guard masks, and the
   routine-then-hops chain, whose landing is not its named type's binding. The defect is
   `PATH_TERMINAL`: it walks from the *enclosing* type's binding, so on a child field carrying
   `@routine` plus `@reference` it can resolve the same elements out of the parent's table and name a
   destination the chain never visits. That is not new with this slice and it is not the chain arm's
   absence either; it is the first rule missing a guard for a field whose path does not depart the
   parent.
9. **The return binding.** With slice 7 in hand, the `@table` demand becomes "the terminus is
   resolvable", not "the author wrote a directive". See below. *Store half landed; the classifier
   half is what remains.*

   **The keying decision is type scope, and the read side is what settles it.** Every relation that
   holds a binding today is keyed by type, and every reader of one holds a type: the position-0 seed
   of the reference recursion, both rules of `intent_field_column_scope` that read a binding, the
   backing view's table arm. An edge-scoped binding feeds none of them. It would make each reader
   learn "or, where my type arrived through a routine edge, ask the edge", which is the carve-out
   shape spread over four relations instead of one directive, and a child field does not hold the
   edge it arrived through in the first place. What edge scope was for is two routines sharing one
   return type, and type scope does not actually forbid that: two routines landing differently are
   two rows with `candidates = 2`, which is `intent_bound_table`'s own discipline, and a reader may
   later accept an arity above one where every candidate exposes the column it needs. So the
   conflict is deferred to a reader's relaxation rather than bought, and the anchor problem is not
   bought at all.

   **Two relations, not an arm.** `intent_bound_table` is the `@table` population and its comment
   says so, so the routine population is its own relation and `intent_resolved_type_binding` is
   where they meet, on the stratum's stated provenance rule and on `intent_type_backing`'s
   precedent. The reduction declines the provenance column that precedent carries, and that is the
   one place the two differ: a type whose `@table` and whose routine return name the same table is
   one binding, and tagging the rule would hand `intent_field_column_scope` two rows at a site whose
   one-row-per-site property is what lets that view be a union with no collapse over it. Provenance
   is a join to the arm, both arms being residents.

   **The carrier is excluded by naming its seat.** A mutation root's `@routine` field with no
   `@reference` is where the payload carrier lives, and binding a carrier to the routine result
   would name a table for a type no table stands for. The store holds no carrier fact, so the
   exclusion names that seat, which is exactly the classifier's own fork; it costs the routine write
   chain nothing, that shape carrying `@reference` by construction, and it narrows to the carrier
   itself the day a carrier relation lands.

   **The classifier half has a shape constraint the plan did not have.** The derived binding cannot
   be minted inside `BuildContext.resolveReturnType`, because `classifyMutationRoutineCarrier`
   separates the direct shape from the carrier by asking whether the return is already
   `TableBoundReturnType`. A globally derived binding would answer yes for every carrier and collapse
   that fork. So the derivation is minted at the chain read seat and `resolveReturnType` keeps
   meaning "what the author declared", which is the same seat-locality the store's own exclusion has.
10. **The name-matched arm on the hop view.** *The arm landed with slice 8; the anchor half is what
    remains.* `via = 'NAME_MATCH'` is the third arm, gated on slice 7's `table_type` discriminator,
    enumerating as candidate departures every FUNCTION-typed table in the graph's sources that
    exposes all of the arrival's primary-key columns by name. It carries `constraint_name`,
    `fk_on_from` and `key_matched_by` NULL, which is the shape the open question below recommended,
    on a better reason than "no value for it": the constraint such a hop *does* key by is the
    arrival's primary key, and `sql_primary_key` is keyed by the table, so the arriving triple the
    row already carries reaches it directly and repeating it would be a denormalisation. The two
    table arms cannot produce one row, a function result declaring no foreign key for the other arm
    to discover. `intent_field_reference_step_target` picks the arm up with no edit, as predicted.

    One interaction with slice 9 belongs here rather than being discovered mid-slice. The target
    view's position-0 term seeds from `intent_bound_table`, which derives from `graphitron_table`,
    so its only rows are `@table` applications. A routine return type that drops the now-redundant
    `@table` under slice 9 has no anchor row, and the hop this slice adds then yields nothing for
    exactly the schemas slice 9 enables: the implicit hop would work for authors who kept the
    ceremony and silently not for authors who took slice 9 up on removing it, which is the same
    silent-shortfall shape Track A exists to delete. Two ways out. The derived binding feeds the
    anchor, as a second arm on `intent_bound_table` or as a sibling relation the recursion seeds
    from instead; or slice 9 keeps `@table` load-bearing for any type a `@reference` departs from.
    Recommend the first: the second reinstates a written-directive demand one slice after removing
    it, and narrows slice 9 to a capability it does not claim. Either way the choice is slice 9's
    keying decision seen from the read side, so settle the two together.

    *Closed by slice 9's store half, the first way.* The recursion's seed reads
    `intent_resolved_type_binding`, so a path departing a routine-return type resolves whether or
    not the author also wrote the directive, and nothing about the seed is routine-specific. Two
    readers beyond the seed took the same repoint for the same reason, both
    `intent_field_column_scope` rules that read a binding and `intent_type_backing`'s table arm.
    `intent_field_separate_fetch` deliberately did not: its two joins over a binding are the
    record-handed precedence question its own comment states, and whether a routine-return parent is
    a table row or a handed row is that question rather than a substitution to make in passing.
11. **The carrier's explicit data-field path**, single- and multi-hop, reading slice 10 rather than
    parsing at a grounding seat. Needs the residual-path correlation arm described under "What stays
    genuinely open" below, which is the one part of this slice the view does not hand over.
12. **Retire the duplicated derivations.** `synthesizeNameMatchedJoin` and
    `deriveRoutineCarrierPairs` both become reads of slice 10.
13. **Plan-tier pilot.** Re-source `routineRow` off facts rather than off the leaf. R668's stage 5
    joins its key-column projection into the same row and asks to land after this slice rather than
    beside it, so the two do not edit one method from opposite directions.

## The redundant `@reference`

Reaching a `@table`-bound child from a routine-result parent requires an explicit `@reference`
naming a table the child's own return type already names. Reported from a consumer schema, so the
type and table names below are theirs and resolve against no fixture in this repo:

```graphql
type Brukertilgang @table(name: "mine_tilganger") {
  tilgangsrolle: Tilgangsrolle @reference(path: [{table: "rolle"}])
  organisasjon:  Organisasjon  @reference(path: [{table: "organisasjon"}])
  miljo:         Miljo         @reference(path: [{table: "miljo"}])
}
```

`Tilgangsrolle` is `@table(name: "rolle")`. The path element restates it. On an ordinary table
parent no directive is needed at all, because FK auto-discovery finds the join; on a routine-result
parent there are no foreign keys, so auto-discovery fails and the author is told to write a path.

But the path element is not supplying a join either. It resolves through
`BuildContext.synthesizeNameMatchedJoin`, which ignores foreign keys entirely and keys the hop by
matching the target's primary-key column names against the routine result's exposed columns. The
only input it takes from the directive is the target table name, which the return type already
carries. So the author writes a directive whose entire content is derivable.

### Two seats, opposite gaps, one derivation written twice

The capability exists and is wired inconsistently.

[cols="3,2,2"]
|===
| Seat | Implicit (no `@reference`) | Explicit `{table:}`

| Read child on a routine-result parent
| rejected: "no foreign key found between tables ..."
| works, name-matched

| Mutation payload carrier's data field
| works, name-matched
| deferred (was R622, folded in here)
|===

Exactly inverted, for the same relation between the same two catalog objects. And the derivation
behind both cells that work is the same loop written twice:
`BuildContext.deriveRoutineCarrierPairs` and `BuildContext.synthesizeNameMatchedJoin` both walk the
target's primary-key columns, find the same-named column on the routine result, and pair them. They
differ in their rejection text and in whether they return pairs or append a `JoinStep.Hop`.

Both empty cells are this item's, which is why R622 folds in rather than sitting beside it. Closing
one and not the other would leave the inversion in place with the duplication still funding it.

### Why the implicit read case falls through

`BuildContext.parsePathElement`'s `{table:}` branch gates on the catalog fact before reaching the
FK machinery:

```java
if (catalog.isTableValuedFunction(currentSourceSqlName)) {
    synthesizeNameMatchedJoin(tableName.get(), currentSourceSqlName, ...);
    return;
}
var fks = catalog.findForeignKeysBetweenTables(currentSourceSqlName, tableName.get());
```

`parsePath`'s element-less inference arm, about 590 lines earlier in the same file, has no such
gate. It goes straight to `findForeignKeysBetweenTables`, finds zero, and emits
`fkCountMessage(..., directiveAbsent = true)`.

That message is not merely unhelpful here, it is wrong. It tells the author "the catalog has no FK
directly connecting these two tables, so a single-hop `@reference(path: [{key: ...}])` will not
resolve" and steers them toward an intermediate table or a `condition:` predicate, when the
single-hop `{table:}` form resolves fine through the name-match the author is not being told about.

The fix is to hoist the same gate into the inference arm. Both parent shapes route through
`parsePath`, so it covers the table-bound parent today and the result-record parent slice 9
introduces, with no second implementation.

### What is genuinely author-supplied, and stays

Dropping the directive is safe only where the name-match succeeds, which is the same precondition
the explicit form already has: the target's primary-key columns must be exposed, by SQL name, on the
routine result. A schema whose explicit `@reference` works today therefore keeps working with the
directive deleted, by construction. Where it does not hold, the author still needs a `condition:`
element, and the rejection should say so in those words rather than in the FK vocabulary. Explicit
`@reference` stays legal everywhere: this makes it optional, not wrong, and a multi-hop path or a
non-name-matched join has no implicit spelling.

## The carrier's explicit path, and why multi-hop is the architecture proof

R622 owned the write-side cell: a Mutation payload carrier's data field cannot declare
`@reference` at all, so a payload whose target is not directly name-matchable from the routine's
result columns has no spelling. R622 deferred it for a machinery reason rather than a semantic one,
and stated the reason precisely: the captured pairs derive at grounding, which runs before field
classification, so an explicit path would need either `@reference` parsing inside the grounding fold
(a parse seat with no rejection coordinate) or independent re-derivation at two classify seats,
which is the two-derivations defect the carrier item existed to remove.

That reason dissolves against the store, and R622 said so itself: the path is already
`graphitron_field_reference_step` rows from capture, one per element, phase-independent, so hop-0
pairs become a view join rather than a parse. What R622 could not know is how much further along the
derivation already is.

### The path walk is already a store derivation

`intent_field_reference_step_hop` enumerates every table-to-table hop a path element could express,
both orientations of its foreign key, and `intent_field_reference_step_target` walks them
recursively from the enclosing type's binding, an element's departure being the previous element's
arrival. Multi-hop path resolution, the part R622 called its real remaining work, is a shipped view.

What is missing is one arm. Both arms of the hop view join `sql_referential_constraint`, so a hop
departing from a function result yields zero rows: a TVF result declares no constraints. The hop
view needs a third arm, name-matched, joining the departing table's `sql_column` names against the
arriving table's `sql_primary_key` columns, gated on the function-ness discriminator slice 7
captures. The target view's recursion then carries multi-hop for free, because position 0 is the
only element whose departure is special.

That is what makes this the architecture proof rather than a feature with a store flavour. One
`UNION ALL` arm over relations that already exist serves three consumers that today run three
separate code paths:

* the explicit read-side `{table:}` element (`synthesizeNameMatchedJoin`),
* the implicit read-side hop (slice 5, which has no code path at all today),
* the carrier's implicit and explicit data-field paths (`deriveRoutineCarrierPairs`, plus the
  spelling R622 could not admit).

Three readers of one derivation is exactly the trigger the fact model names for promoting a
derivation to a relation, and the alternative here is not hypothetical: two of the three are already
the same loop copied.

### What stays genuinely open

Inherited from R622 as its real design work, and unchanged by the fold.
`ParentCorrelation.checkCarrierInvariant` pairs a non-empty `joinPath` only with a hop-anchored
correlation, while the carrier data field's correlation is the hop-less `OnLiftedSlots` over the
captured slots. A residual path therefore needs a correlation arm that anchors on the captured
record and walks onward from it, plus the post-commit query emit that rides it. This is model work
on the write path and does not come free from the view.

Inherited as decided, not reopenable here without arguing down the carrier item's two-statements
rule: the write transaction contains the routine call and a projection of its own result and
nothing else, at every hop count; residual hops run post-commit under the caller's identity, so read
policies apply to them; and a multi-hop data field legitimately resolving null with empty errors is
the carrier's documented success outcome, not a defect. In-transaction capture would not escape
row-level security either, so it buys only insulation from visibility that changes at commit.

## Keyset seek over a routine in the FROM

The previous draft flagged the seek predicate over a lateral routine call as the one Track A item
that might not be pure wiring. Measured on PostgreSQL 16 against a 200k-row fixture with an
inlinable `LANGUAGE sql` TVF and an opaque `LANGUAGE plpgsql` one; it is pure wiring.

The emitted shape is jOOQ's `.seek(page.seekFields())` over `.from(<routine local>)`, which is a
WHERE-clause row-value comparison. A function in the FROM is a table expression like any other, so
all four shapes return correct pages:

* root single-node chain, seeking on the routine's own result columns;
* the same over the opaque function;
* routine-then-hop chain, seeking on the terminus primary key;
* a mixed seek naming one column from the routine result and one from the hop terminus.

The correlated child form (`CROSS JOIN LATERAL <routine>(...)` with the seek in the outer WHERE)
returns correct pages too.

The plans are better than the caveat assumed. For the inlinable function PostgreSQL pushes the
row-value predicate *into* the function body, so the routine result is never materialised
(`Seq Scan on film`, `Filter: ... AND (ROW((film_id % 997), film_id) > ROW(5, 100000))`). For the
opaque function it is a `Function Scan` with the filter applied above, which is what any WHERE on
an opaque function gets and is not a pagination-specific cost.

Dialect note, since it came up: H2 is only the generator's internal fact store
(`GraphitronModelStore`) and never executes a generated query. Generated SQL runs against the
consumer's database, and graphitron already carries `SqlDialectFamily` for that. Both seek
spellings were checked anyway, the row-value form and the expanded
`a > ? OR (a = ? AND b > ?)` chain jOOQ emits where row values are unsupported, and both return the
same rows.

What this does **not** settle is cursor columns spanning two chain nodes, which
`OrderByFragments.fixedColumnParts` cannot render for the single-alias reason
`roadmap/routine-chain-ordering-spans-nodes.md` (R662) documents for sort columns. The database is
fine with it (shape 4 above); the renderer is the constraint, and it is R662's constraint, not a
new one.

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
("new facts land only in the store"). Hence slices 7 to 9.

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
* **Plan tier, existing pin to repoint**:
  `LauncherCommandsPipelineTest.routineRoot_sourceArmCarriesTheChainAndTheTerminusProjection` is
  the pin on exactly the two nulls slices 1 and 2 fill; it asserts `row.where()` and the
  `RecordList` ordering are both null, over a catalog-terminus fixture (`@reference(path:
  [{table: "film"}])`). It flips rather than breaks: the ordering becomes the PK fallback over
  `film`, which is the same slot assertion the classification tier gains, one tier up.
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
  scalar/void routines. Its call surface is the same one slice 7 would capture, so the two should be
  read together before that slice fixes a shape.
* `roadmap/nodeid-key-projection-on-routine-params.md` (R668) sits on top of this item and reads
  slice 7's capture. Three coordination points, all recorded at their slices above: its capture
  half folded into slice 7 (with the four inputs it handed over), its planning join wants to land
  after slice 13, and its stage 4 relocates the two routine-write emitters into `render`, which
  edits the neighbourhood of `classifyMutationRoutineChain` this item's Track A step 2 also
  touched. One interaction runs the other way and is free: Track A gives routine-backed reads a
  real `@condition` and `@orderBy` surface, so `argMapping` paths now resolve at coordinates that
  carried none, which is corpus population for R668's `site` arms rather than new work.
* `roadmap/planners-read-facts-emitters-read-commands.md` (R682) and
  `roadmap/delivery-verdict-derives-from-the-store.md` (R666): the architecture Track B pilots, and
  the nearest precedent for replacing a hand-maintained negative-space switch with a store
  derivation.

## Open questions

* **Which node does a filter target on a multi-node chain?** *Answered by Track A: terminus-only,
  and said so.* `@condition` resolves against the chain's last node, matching where the ordering
  resolves, and the classifier javadoc and the manual both state it as a rule rather than leaving
  it to be inferred. Both aliases are still live in the emitted query, so filtering the routine
  result before the hop remains expressible later; it is the same shape as R662's question, one
  clause over, and stays with R662.
* **Does the implicit hop compose past one element?** *Answered by Track A: one element, as a
  rule.* The implicit form supplies exactly what the child's return type carries, which is one
  target table name. A two-hop path out of a routine result keeps needing `@reference`, and so does
  a join the name-match cannot key; the manual states both as consequences of what the return type
  can supply rather than as a depth limit.
* **Does the name-matched arm belong on the hop view or beside it?** *Answered by slice 10's arm:
  on the view, as a third `via` value, and the precedent did extend.* What settled the three null
  columns was not that a name-matched hop has no use for them but that one of them is a
  denormalisation: the keying constraint is the arrival's own primary key, which `sql_primary_key`
  hands back from the arriving triple the row already carries. The recursion needed no edit. The
  original reasoning stands below.

  The hop view's two arms are
  both FK-shaped and carry `constraint_name` / `fk_on_from`, which a name-matched hop has no value
  for. A third arm means those columns go null on it, which the view's own comment discipline would
  have to state; a sibling relation unioned one level up keeps each relation's columns meaningful.
  Narrower than it looks, because the view has already answered a smaller version of the same
  question: it carries a `via` discriminator (`KEY` where the element named a constraint, `TABLE`
  where it named a table) and `key_matched_by` is already NULL on the `TABLE` arm, with the column
  comment saying so in those words. A `NAME_MATCH` value of `via`, with `constraint_name` and
  `fk_on_from` NULL and commented as such, is that same discipline one step further, and
  `intent_field_reference_step_target` selects `via` straight through, so the recursion needs no
  edit either way. So the work is confirming the precedent extends rather than choosing a shape.
  Still decide before writing the DDL, since the target view's recursion reads whichever wins.
* **Does the carrier's residual-path correlation arm generalise?** It anchors on a captured record
  and walks onward, which is close to what a record-backed parent needs in R447's
  `RecordTableField` bullet. Check whether one arm serves both before minting a carrier-specific
  one.
* **Is `sql_routine` a subject, or is function-ness a column on `sql_table`?** *Answered by slice 7:
  both, because they are two questions.* Function-ness is `sql_table.table_type`, jOOQ's
  `TableOptions.TableType` vocabulary, which the store recorded none of and which every derived view
  in this track reads. The callable is `sql_routine` plus `sql_routine_parameter`. The deciding
  argument was the one the family is named for: the standard separates `ROUTINES` from `TABLES`, and
  a routine with no `RETURNS TABLE` form has a callable and no table at all, so parameters hung off
  `sql_table` would have nowhere to go the moment a walk reads one. `sql_constraint` is the in-tree
  precedent it takes its shape from, a supertype discriminated by type with the forms an iteration
  does not read arriving as further type values. Table-valuedness is not a third column: it is the
  join, a `FUNCTION`-typed `sql_table` row at the routine's coordinate.
* **Grain of the verdict relation.** One relation per axis, or one keyed by (coordinate, axis) with a
  closed axis vocabulary? The latter is tempting and probably wrong: the axes carry different
  payloads, so a shared relation goes wide and sparse or pushes payload to a side table per axis.
* **Two routines, one return type.** Fine under an edge-scoped binding, a conflict under a
  type-scoped one. Follows from where the binding fact is keyed, which is slice 9's real decision.
  The read side constrains that decision and is the reason to answer both at once: an edge-scoped
  binding cannot feed the type-keyed `intent_bound_table` the hop recursion anchors on, so choosing
  edge scope entails the anchor work slice 10 describes. Type scope avoids that and buys the
  conflict instead.
* **Does `@table` on a routine return stay legal?** Assume yes so existing schemas keep compiling.
  Confirm nothing starts flagging the now-redundant annotation, and decide whether the sakila
  fixtures migrate or keep one of each form.
