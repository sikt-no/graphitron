---
id: R549
title: "Facts and commands: grain-first hierarchies and the three command relations"
status: Spec
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-07-27
last-updated: 2026-07-27
---

# Facts and commands: grain-first hierarchies and the three command relations

This item is a **programme**, not a single deliverable, in the sense R117 uses the word: it frames a
direction, states the invariants that make it falsifiable, and lists slices that each ship on their own.
It sits under R333, which owns the model itself; this item owns the reframing R333's dissolution implies
once you look at where the hierarchies actually sit. The measurements it rests on, with their method and
a re-derivation script, are in `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`.

## The reframing in one paragraph

There is no intermediary command model to design. Graphitron's model already contains its commands, as
sealed hierarchies, and the reason they do not read as commands is that four different *kinds* of
hierarchy are fused at one grain: the per-coordinate leaf. Separate them by how a row comes to exist and
by their cardinality against the field coordinate, and the emit turns out to be three relations, all
three of which already exist somewhere in the tree under a different name. The work is labelling,
re-homing, and grain repair, not construction.

## Four kinds of hierarchy, and the test that sorts them

The discriminator is not subject matter, it is **how a row comes to exist**: walked or minted.

| kind | test | examples |
|---|---|---|
| walked facts | read off the SDL or catalog by a traversal | `Source`, `Target`, `TargetShape`, `TenantBinding`, `ScalarResolution`, `ProducerBinding`, 19 of `GraphitronType`'s 24 permits |
| resolved views | a coalesce or inference over facts, with no walk of its own | `JoinStep` / `On`, the `reference` resolution, `resolvedTable` |
| commands | minted at emit grain from facts | `Operation` (19 permits), `BodyParam`, `DmlReturnExpression`, `CallSiteExtraction`, `OrderBySpec`, `RowsMethodShape` |
| the walk's error channel | neither fact nor command, the `Err` arm of classification | `Rejection` (14), `PivotError` (12), `UpdateRowsError`, `ServiceMethodCallError`, `ErrorChannelWalkerError` |

`Operation` is the proof that commands are already written: R333 describes its members as *minted by
triggers* (a table-bound return type mints `select`, pagination args mint `paginate`, `@condition` mints
`condition`, `join` is minted by the `reference` fact), which is derivation at emit grain, not a fact
anybody walks for. The error-channel kind is a genuine fourth: 43 permits across five seals, larger than
`Operation`, and neither walked nor minted at emit grain.

## Grain decides what a leaf can hold

A leaf is one row keyed by coordinate, so a hierarchy's cardinality against the coordinate decides
whether a leaf can hold it at all. Every strain point in the current model is a 1:N or type-grain family
stuffed into a per-coordinate row.

| grain vs coordinate | leaf-able | families | tell in the code |
|---|---|---|---|
| 1:1 | yes, and should stay a sealed record | source, target, tenancy binding, node identity | `Source`, `Target`, `TenantBinding`, `NodeMetadata` |
| 1:N | no, must be a relation | operations, join hops, conditions, arguments, pivot slots | `List<JoinStep> joinPath()`, `List<WhereFilter> filters()`, `PivotSpec.slots()`, `callParams()`, and ten more list accessors |
| type grain | belongs to the type, not the field | the `$fields` fold, input record shape | `$fields` is type-granular and a fold |
| 0:1, two populations | authored and inferred, resolved by a view | reference, defaults, ordering | `reference` is authored `@reference` *or* inferred unique FK |

This also explains why the leaf model reached "ok, but not 100%". A leaf names a point in a product
space, which is strictly *better* than a relation while the families co-vary (one name, compile-checked
exhaustiveness, cheap dispatch) and a cross-product bomb the moment they vary independently. R432
collapsing `SplitTableField` and `RecordTableField` (differing only by source shape, so correlated and
merely over-split) and R501 minting three pivot leaves (delivery varying independently) are the two
outcomes of the same rule. The corollary is conservative: **split a family out of a leaf on measured
independence or measured multiplicity, never on aesthetics.**

## The three command relations

| relation | key | where it lives today |
|---|---|---|
| global commands | unit kind | the roughly twenty `write(...)` calls in `GraphQLRewriteGenerator.runPipeline`, including an inline `federationLink && usesOneOf` gate |
| type-keyed commands | `(typeName, unitKind)` | 24 generator entry points that each loop the schema asking "should I emit my kind for this type", with the naming vocabulary already centralised as data in `compile/GeneratedUnits` (`typeClass`, `fetchers`, `conditions`, `inputRecord`, `schemaShape`, plus `singleton` / `rootUnit` for globals) |
| coordinate-keyed commands | `(coordinate, operation)` | `Operation`'s minted arms, plus `MethodCommandRegistry`'s four-string records minted during rendering |

The coordinate-keyed relation holds two kinds that must not be conflated. A **projection command** returns
the select list for one projection unit. A **launcher command** owns a query: it composes a projection
call with its own extras and adds the FROM, joins, WHERE, ordering, and windowing. The discriminator
between them is what a column's presence depends on:

> **Projection contributions are gated on client selection. Launcher extras are entailed by the
> mechanism.**

`__idx__` (scatter correlation), `__rn__` (the window ordinal), the seek columns cursors are built from,
and the `__typename` literal are all needed whenever their mechanism runs, so they belong to launchers,
which is where they already are. Everything a projection command emits is there because the client asked
for it.

Commands nest: a projection command calls other projection commands (nested units, inline table children),
so *complete* means the core decided everything, not that a command contains everything inline. R333's
closure invariant is the right test for that, and an inlining rule is not.

Three observations follow that the plan leans on. First, the emit's identity scheme is already data, in
`GeneratedUnits`, but it lives in `compile/` because the dependency graph was the first consumer to need
it that way. Second, that makes three separate copies of emit knowledge outside the emit
(`CompileDependencyGraphBuilder` duplicating the call graph, `MethodCommandRegistry` auditing the names,
`GeneratedUnits` holding the vocabulary), each built where its consumer sat rather than in the core.
Third, `GraphitronType`'s five synthesised permits (`ConnectionType`, `EdgeType`, `PageInfoType`,
`FacetsType`, `FacetValueType`) are command *outputs* stored in the fact map, which the model already
admits through the `NO_CASE_REQUIRED` exemptions stating that no SDL declaration exists to carry a
`@classifiedType` for them.

## The keystone: the projection command

The `$fields` method is the keystone, and designing its command first is what validates or breaks
everything above. Five properties make it so: it is the only command whose body aggregates contributions
from other keys; it is the hub of the emitted call graph (`fetchers.X` to `types.Y#$fields`, and type
classes calling each other); it straddles the static/runtime line, since the arm set is closed at build
time while which arms fire is a per-request selection value; its demand computation is already duplicated
by an independent checker that throws at generation time; and its grain is the one the model cannot
currently express.

```java
record ProjectionCommand(
    String unit,                       // types.Film, or a nesting type's own unit
    TableRef table,                    // the table whose columns the contributions name
    List<Contribution> contributions)  // every one gated on client selection
{}

sealed interface Contribution {
    record Column(String resultKey, ColumnRef column)                        implements Contribution {}
    record ReferencedColumn(String resultKey, ColumnRef column, JoinRef join) implements Contribution {}
    record NestedProjection(String resultKey, String calleeUnit)              implements Contribution {}
    record ChildProjection(String resultKey, String calleeUnit)               implements Contribution {}
    record PivotSlots(String resultKey, PivotRef pivot)                       implements Contribution {}
    record Literal(String resultKey, Object constant)                         implements Contribution {}
    record CorrelationKey(String resultKey, List<ColumnRef> keyColumns)       implements Contribution {}
}
```

**No unconditional rows.** The "always included" category in today's emit is an artifact, not a
requirement. The selection switch has arms for exactly the seven leaf kinds that project data of their
own; `BatchedTableField`, `BatchedLookupTableField` and `@service` children have **no arm at all**, because
from the switch's point of view they project nothing. When it turned out they do need their correlation key
in the parent SELECT, the only available home was an unconditional append at the end of the method. That is
the origin of the whole category, and of the chain that widened it (R425 force-included, R426 promised the
full row, R436 built the reserved-alias scheme, R516 narrows it back). `CorrelationKey` is the missing arm:
project the key when the child is selected, project nothing when it is not.

Consequences, all of them things that stop existing rather than things that get built:

- The required-projection walk (`TypeClassGenerator.collectRequiredProjection`) has nothing left to
  discover, since no demand crosses a key without travelling through a call.
- `ParentProjectionContainmentCheck` loses its subject. It throws `IllegalStateException` at generation
  time today to catch a demand omitted from an append; with demand co-located in arms there is no append.
- Over-projection goes away as a runtime effect. A query selecting only `title` on a type with three split
  children currently projects the union of all their keys.
- Node key columns need no forcing. Node-id-ness is a wrap applied at the fetcher value, not in the SELECT
  ("Compaction does not affect projection: the SELECT terms are the same columns in both cases"), so
  selecting `id` projects those columns through the ordinary column arm and not selecting it needs nothing.
- `@lookupKey` has no projection footprint at all. Its work is the VALUES join, emitted by
  `LookupValuesJoinEmitter`; a lookup *field* projects because it is a field, not because of the argument.

**Return, do not mutate.** A nested projection takes the scoped selection and returns its contributions;
the caller merges. Mutating a passed accumulator would make the callee's contract include the caller's
state and make call order significant, in the one place where nothing else is, and it would defeat the
independent assertability that motivates cutting the seam at all.

**Every projection unit is caller-parameterised.** A nesting type shares the parent's table alias by
definition: if it had its own table instance it would not be a nesting type. Since table-backed units also
take their instance from the caller (`$fields(sel, table, env)`), there is no "owns its alias" case and no
alias axis on the command. What distinguishes a nesting unit is that it has no key of its own.

**Nesting types become projection units.** Giving a nesting type its own unit collapses the contribution
key from `(host, pathFromHost)` to `typeName`, retires the depth-suffixed generated locals that exist only
to dodge JLS shadowing when everything inlines into one method, and hands the dependency graph a node and
edges for free, which is the grain R459 and R462 both stumbled on. This rests on a precondition:
nesting types are single-reach today (the first-wins `NestingType` registration guard, and
`MixedSourceReachIndex` treating a pure nesting target as single reach). `ColumnRef` carries no table, so
contributions name columns resolved against whatever instance the caller passes; the host binding lives in
the emitted parameter type, not in the projection. If the single-reach guard is ever lifted, the unit key
gains the host table and this collapse reverses.

**Polymorphic projection needs no folding in.** `MultiTablePolymorphicEmitter` already emits
`Type.$fields(PolymorphicSelectionSet.restrictTo(env.getSelectionSet(), "Film"), t, env)` per participant
and states that the discriminator's "real column is projected by the participant `$fields`". So the
polymorphic path is a launcher that consumes projection commands, and the only thing the contribution set
owes it is the `Literal` arm for `__typename`. Both scoping adapters (`restrictTo` by concrete type,
`SelectionOccurrences.mergeByResultKey` by depth) sit at the call site and converge on the same callee
input, which is also why the three `$fields` overloads collapse to one.

**Pagination is already outside `$fields`, and correctly so.** `ConnectionHelperClassGenerator` computes
`selectFields` as "selection ∪ extraFields, name-deduped", a runtime union of what the client selected with
what cursors need. Four sites already append to a projection's output this way (`__idx__` in the scatter
path, `__idx__` plus `__rn__` in the ROW_NUMBER envelope, `__typename` in the polymorphic path, `multiset`
wrapping in the inline paths). The runtime dedup is also evidence that overlap between the two sets is a
name collision to reconcile at runtime, not a build-time invariant to enforce, which is a second argument
against the demand walk.

## Invariants: what makes this falsifiable rather than believed

The current statements of the cut ("commands must be complete", "the shell assembles nothing") are not
checkable, which is why the boundary drifts. Under the labelling they become mechanical, and each one is
installable as a ratchet at its current value before any migration happens.

1. **The shell pattern-matches over command hierarchies only.** A generator that reaches for a walked-fact
   hierarchy is a leak, and it means the command it renders is incomplete. Today this fails roughly 100
   times (`instanceof ChildField.*` / `QueryField.*` / `MutationField.*` in `generators/`). Install the
   count as a ratchet, drive it to zero family by family. This replaces the aspirational law with a grep.
2. **Every hierarchy declares its grain and lives in exactly one relation at that key.** This is
   `VariantCoverageTest` generalised from "every leaf is demonstrated" to "every hierarchy has a declared
   grain and a home". It is the guard against the current bug class, where something exists that no
   coordinate names (`PivotSlotField` riding `PivotSpec.slots()`, R462's emitted methods with no
   coordinate).
3. **No emit-library vocabulary in the model** (R545). A command holding a `CodeBlock` is output the core
   already rendered; a fact holding a `TypeName` is comparable only through the renderer's equality.
   Landable immediately as an allowlisted guard over the roughly 30 current offenders, which converts a
   growing surface into a shrinking one.
4. **Closure stays green** (`MethodClosureOracleTest`): every callee name resolves to a committed command.
   This already exists and works for the load-bearing families; every slice below must hold it.
5. **Projection dispatch is exhaustive over the sealed set, with no default arm.** This is what closes
   R425's bug class rather than one of its instances: a leaf that demands a correlation key and has no arm
   becomes a compile error instead of a forgotten entry in a global append that silently nulls a DataLoader
   key at runtime under a federation `_entities` fetch.
6. **No unconditional columns in a projection command.** Every contribution is gated on client selection;
   anything a mechanism needs regardless belongs to a launcher. Checkable as an emit-shape property: a
   projection method's body has statements outside its selection switch only for the switch scaffolding.
7. **Concentration ratchet** (optional but recommended): share of package LOC in the top five files, and
   largest single file per package. Today 46% / 7,102 for `generators/` and 52% / 7,754 for the core.
   Totals are a poor discriminator, since they can stay flat while structure degrades; concentration is
   what actually tests the direction. It also constrains the regrowth that defeated R6 and R7, where
   `FieldBuilder` returned to being the largest file in the tree and `TypeFetcherGenerator` grew from
   1,646 to 7,102 lines while a decomposition item waited.

## Slices

Numbered because these are real seams: each ships to trunk on its own with the build green, and the
intermediate states are observable. Ordered by cost and independence, cheapest and least contested first.

| # | slice | why here | cost |
|---|---|---|---|
| 1 | Global command list: `runPipeline`'s `write(...)` sequence becomes data the core computes and the shell folds over | touches no leaf, no fact, no javapoet, no emitted output; makes "the core decides the entire emit" literally true for the one population where it is currently 20 lines of orchestrator decisions | very low |
| 2 | Label the hierarchies (walked / resolved / command / error) and install invariants 1 and 3 at their current counts | the labelling is the programme's vocabulary, and a ratchet installed before the migration is what stops the surface growing while the work proceeds | low |
| 3 | **The keystone: projection commands.** One method per projection unit (grouped selection in, select list out), nesting types promoted to units, `CorrelationKey` as the missing arm, exhaustive dispatch with no default, and the demand walk plus `ParentProjectionContainmentCheck` deleted. Depends on R516 landing first | designing this validates or breaks the whole model, and it is the only slice that deletes a build-time throw, a duplicated walk, and a runtime over-projection at once | medium |
| 3b | `GeneratedUnits` moves to the core; the type-keyed relation `(typeName, unitKind)` replaces the 24 generator predicates, one kind at a time | the vocabulary is already data, so this is a re-homing plus an inversion of "should I emit" from 24 loops into one relation; renderers barely move | medium |
| 4 | Fact-visitor engine: one shared traversal dispatching to per-fact visitors, on the `LintEngine` pattern, with the registry-coverage meta-test, one genuinely independent fact as beachhead | dissolves the central switch that made `FieldBuilder` the largest file in the tree, using an architecture that already shipped here | medium |
| 5 | Coordinate-keyed command relation: `Operation` rows become the command set the shell consumes; `MethodCommandRegistry`'s parallel four-string record retires into it | this is where the flow finally inverts from shell-asks-core to core-tells-shell | medium |
| 6 | Grain repair, worked from the exemption lists | 21 stated data points about where the grain is wrong, already written down with reasons | medium |
| 7 | The recompile graph becomes a projection over the command relation, retiring `CompileDependencyGraphBuilder`'s coarsening switch; R10's rebuild drop lands once connection synthesis is a relation | removes the largest duplicate derivation and with it a recurring bug class (R455, R459, R462) | high |
| 8 | The corpus asserts facts, then commands (R543) | the payoff that justifies the command half at all, and it wants the relations to exist first | medium |

Slice 4 owes one design decision before any code: **gather versus resolve.** Lint rules are independent
inspections, which is why their registry is clean, but facts interlock (`resolvedTable` is a coalesce over
three walked facts, `reference` mints `join`, the read-side facts gate on the source object). The
model-consistent split is R333's own: visitors gather only authored and inferred populations, and every
resolved value is a view computed after the walk with no traversal of its own. If that split holds the
engine is simple; if it does not, the design becomes a pass-ordering DAG, which is a materially heavier
thing and should be recognised as such before starting.

Slice 4 also carries a real safety regression to mitigate: `FieldBuilder`'s switch over sealed permits is
compile-checked today, and a visitor registry is not, so a forgotten registration is a silently missing
fact rather than a build break. The lint engine hit exactly this and answered it with
`LintRuleRegistryCoverageTest` (every rule registered exactly once; subscribed kinds union not-linted
kinds partition the node kinds with no overlap or gap). The equivalent must land with the first visitor,
not after.

## The exemption lists are the grain worklist

`VariantCoverageTest.NO_CASE_REQUIRED` (14 entries) and `ClassifiedDslTest.OPERATION_KNOWN_GAPS` (7) each
state why something the model declares cannot be reached at the grain a test walks. Read as a set rather
than one at a time, they should partition into (a) genuinely unimplemented behaviour, (b) synthesised
things with no SDL origin, and (c) things riding another row's list rather than their own key. Category (c)
is the direct worklist for slice 6, and (b) is the connection-promotion residue slice 7 clears. Nobody has
read them as a class yet, and doing so is cheap Spec-time work that would sharpen slices 6 and 7 before
either starts.

## Empirically deciding which families are independent

The corpus already records, per coordinate, which arm each axis lands on, and
`ClassifiedDslTest.everyDimensionValueIsExercised` tracks which arms are populated. Extend it from single
axes to **pairs**: for each pair of families, is the cross-product populated across the corpus, or only a
diagonal? A populated product means independence, so the families must separate; a diagonal means they
co-vary, so keep them fused and save the machinery. That turns "which families are real" from a judgment
call into a measurement, and it makes the corpus an instrument for designing the model rather than only
for pinning it.

## Relationship to existing items

| item | relationship |
|---|---|
| R333 (Ready) | governs. This item consumes its model and does not re-litigate it. The fourth-reader note in its consumers section is the corpus's stake in the re-sourcing |
| R545 (Backlog) | becomes slice 2's invariant 3. Stays as filed; it is a precondition, not an independent win |
| R546 (Discarded 2026-07-27) | absorbed here. It asked what shape `MethodCommand` should grow into, and this reframing answers "none": the hierarchies are the commands, so a parallel four-string record is exactly the intermediary model this programme says is unnecessary. Its flow-inversion scope became slice 5, its recompile-graph justification became slice 7 (full argument in the audit's gap 7), and its abandon condition became this item's |
| R543 (Backlog) | slice 8. Its fact half needs slice 4, its command half needs slice 5 |
| R544 (Backlog) | independent, and this reframing strengthens it: the error-channel hierarchies are a first-class fourth kind at 43 permits, so pinning them declaratively is model work, not only test hygiene |
| R541 (Spec) | first family to flip under slice 5, already spending the command registry |
| R516 (Ready, priority 2) | **dependency of slice 3.** It deletes the `reservedFullRow` axis and the reserved-alias scheme, which is the one demand no parent-owned fact can serve, and it ships independently as correctness work. Its force-include of PK plus node key is an interim expression that slice 3 converts to a gated `CorrelationKey` arm, and the node-key half is redundant once the `id` arm projects those columns; its scope item 5 (update `ParentProjectionContainmentCheck`) should be the minimum that keeps the check honest, since slice 3 deletes it |
| R462 (Spec) | fix by hand now, do not generalise; slice 7 dissolves its class. Advisory already noted on the item. Its Spec body cites `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES`, which no longer exists under that name anywhere in main or test, so the implementer must re-derive the current nested-leaf bound rather than trusting the citation |
| R10 (Backlog) | dependency of slice 7. Its own body says it wants "a concrete signal"; the fact engine making connection synthesis a relation is that signal |
| R7 (Backlog) | subsumed in effect. `TypeFetcherGenerator` splits along command kinds under slice 3 and slice 5 rather than by a decomposition pass that regrows |
| R25 (Backlog) | supplies the coverage half of the falsification baseline |
| R112 / R117 | unaffected, but the KB's "model as projection" framing gets easier once the relations exist |

## Abandon condition

The programme must be able to fail. The facts half of R333 has been paying its way slice by slice (R432
collapsed four leaves to two, R438 made join facts orthogonal, R435 shipped a user-facing feature off the
fact model), and the discipline that produced that record is the one to keep: **no slice that is purely a
migration payment.** Each slice above ships a simplification, a deletion, or a capability.

Baseline, measured 2026-07-26 and re-derivable from the audit's script: 1,641 branches in `generators/`,
roughly 100 leaf-naming `instanceof` sites, 29,837 generator LOC, 400 `ClassificationCase` constants,
top-five concentration 46% (`generators/`) and 52% (core), largest files 7,102 and 7,754, and R25's
emitter coverage figures (`JooqRecordInstantiationEmitter` 40.7%, `FetcherEmitter` 50.2%).

Re-run after slices 1 to 5. If the numbers have not moved in the right direction, stop: keep the facts
half, keep the labelling and the ratchets (which pay for themselves by constraining regrowth), and
abandon the rest rather than accept a half-migrated shell. A partial migration is the one outcome worse
than either endpoint, and R333 says so itself: leaves kept alive to feed one consumer is how the leaf zoo
returns as a second model.

## Non-goals

- A generic fact bus. No `Fact` interface, no `Map<String, Object>`, no dynamically registered fact kinds.
  This is a constrained domain of roughly ten to twelve typed relations, and the relational discipline is
  a design discipline, not a runtime, per R333's own resolution.
- A query-engine runtime for the model.
- Re-platforming the whole shell in one program. Slices ship independently; the shell's renderers mostly
  do not move, they stop deciding.
- Any change to emitted output. Slices 1, 3, and 5 should be byte-identical at the output, which is the
  cheapest acceptance test available for each of them.
- Changing what any directive means, or the user-facing surface. This is entirely internal.

## Acceptance

The programme is not "done"; individual slices are. What signals it worked: the shell contains no
reference to a walked-fact hierarchy, every hierarchy names its grain and has exactly one home, the
recompile graph is a projection rather than a prediction, and a contributor adding a schema shape
registers a fact visitor and mints commands rather than adding an arm to a central switch. What signals it
failed: the ratchets stall for two consecutive slices, or a slice lands whose only product is migration.
