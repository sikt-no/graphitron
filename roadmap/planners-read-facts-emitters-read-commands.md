---
id: R682
title: "Planners read facts, emitters read commands: close the seam on both tiers"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [delivery-verdict-derives-from-the-store]
created: 2026-08-14
last-updated: 2026-08-16
---

# Planners read facts, emitters read commands: close the seam on both tiers

The intended architecture is one sentence. Capture writes facts; the classification walk's sealed
leaves dissolve into those facts rather than growing; planners read facts and produce commands; and
emitters render commands. Each tier reads only the tier below it, so a planner never reaches past
the facts into the walk that produced them, and an emitter never reaches past its command into the
thing that produced it.

That sentence is the functional-core / imperative-shell topology the development principles fix
(`docs/architecture/explanation/development-principles.adoc`), applied to the emit path: the
planners are the core, pure derivation from typed facts to command rows, and `render` is the shell
that encodes those rows outward. `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`
measured the tree's distance from that ideal; the emit-path share of closing it is this item, and
closing it is what dissolves the leaf zoo for the generator, because the plan and the emitters are
the zoo's largest remaining consumers.

This item owns getting there, on both tiers.

## Problem

Every store migration that has landed so far moved a *reader*. The authored-claim conflict detection
reads the claim views, the `diagnostic` surface serves the diagnostics stratum, and the language
server has re-sourced completion, hover and goto-definition arm by arm. All of them answer questions.
None of them emit code.

The emit half is still made entirely from the leaf model, at both of its tiers. `EmitPlan.produce`
takes a `GraphitronSchema` and joins its facts into the six command relations the run will render,
every one of those joins dispatching on sealed leaf variants. Below it, the un-migrated emitters
dispatch on leaves directly rather than folding over command rows. So the store has never been the
source of a single generated file, and the claim that it is the destination is, on the evidence of
the emitted output, unproven.

Those are one problem at two tiers, which is why one item owns them. Converting the plan without the
emitters leaves a store-derived command relation that a leaf-reading emitter can still bypass;
converting the emitters without the plan leaves commands that are complete rows derived from the
walk. Neither half alone makes the sentence at the top true.

## Where the line actually falls today

`no.sikt.graphitron.render` already lives under the rule. It contains no dispatch on a
classification leaf and imports none of the leaf hierarchies; `PackageImportDirectionTest` pins that
structurally, restricting the package to commands plus a named dial of pure-data model refs. A
renderer there cannot reach a leaf even by accident.

`no.sikt.graphitron.rewrite.generators` is the same job under none of the rules. It is outside that
guard, and it is where the un-migrated emitters live: `TypeFetcherGenerator` (around 6,000 lines)
and `FetcherEmitter` between them carry nearly all of the leaf dispatch, and `TypeFetcherGenerator`
still enumerates its coverage as a set of leaf classes (`IMPLEMENTED_LEAVES`, 36 entries) rather than as
rows of a command relation. Nothing prevents an emitter there from reading whatever it likes off a
leaf, which is not a hypothetical: it is how a recent design landed on the wrong carrier, because
"join the fact onto the command row" and "read it off the leaf the emitter already holds" are both
reachable and only one is right.

`no.sikt.graphitron.plan` reads leaves, not facts. `EmitPlan.produce` takes a `GraphitronSchema` and
dispatches on sealed variants to build the six command relations, so no generated file has ever been
produced from the store. That is the planner half, and it is the larger of the two.

## The instrument already exists and already declares the target

`CommandSeamRatchetTest` was installed by the `facts-and-commands` programme and measures this seam
on both tiers. Its own javadoc states the terminal condition in as many words: the generators-side
counts "ratchet down to zero", and the plan-side count is "expected to rise while producers are fed
by leaf dispatch and to ratchet back to zero when the fact-visitor engine re-sources them". Live
pins at filing:

[cols="3,1,4"]
|===
| Pin | Value | Tier

| `MODEL_TAKING_ENTRY_POINTS`
| 18
| emitters: entry points in `generators/` still taking the whole schema

| `GENERATOR_LEAF_INSTANCEOF_SITES`
| 69
| emitters: `instanceof` sites in `generators/` naming a leaf of the seven hierarchies

| `GENERATOR_LEAF_CASE_PATTERNS`
| 60
| emitters: the same for `case` patterns

| `PLAN_LEAF_REFERENCES`
| 125
| planners: leaf references in `plan/`, the pin that legitimately rose before it falls
|===

All four go to zero. That is most of the item stated numerically; the residue the pins cannot see
(a leaf taken as a parameter and never dispatched on) is what the guard extension in "The closer"
exists to catch, and the census in the emitter half names those files.

So it proposes no new architecture. The architecture is decided, the triangle is built, the guard
exists for one package and the counters exist for the rest. What is missing is an owner for driving
all four pins to zero and then extending the structural guard over the packages they measure, so the
rule stops being a ratchet and becomes the same build gate `render` already lives under.

The reason to own it explicitly rather than let it happen slice by slice: a ratchet with no owner is
a flat line. Each feature item that touches an emitter or a producer pays a little of this cost and
none of them is responsible for finishing, which is how the counts sat where they are. A stalled
relocation is precisely what the tertiary counter's comment says the instrument exists to make
visible.

## Why the plan is the narrow waist, and why the emitters still have to follow

The plan is where conversion buys the most. Commands are complete rows: the render shell folds over
them and hands each row to its renderer, and the fold enforces closure in both directions (a renderer
emitting an uncommitted unit fails the run, a committed unit nobody emitted fails it too). So
converting the plan makes every *already-migrated* renderer store-derived transitively, without
touching a renderer.

The doctrine is already written on `EmitPlan` itself: "the fact store carries what the schema means,
the plan carries what this run emits." Today the first half of that sentence is aspirational, because
what the plan reads to decide what this run emits is the walk's model rather than the store. The
planner half makes the sentence true.

Two further properties make the plan the right first producer. It sits after capture, so nothing
about the pipeline's stage order has to change; and its output is pinned harder than anything else in
the tree, because the emitted sources are compiled and executed against a real database, so a
conversion that changes a single command row cannot pass silently.

The transitive argument only reaches renderers that already consume commands, which is the render
package and nothing else. The emitters still dispatching on leaves are not downstream of the plan at
all, so no amount of planner conversion reaches them: they have to be migrated, one family at a time,
onto the command rows the plan produces. That is the emitter half, and it is why "convert the plan"
is not the whole job.

## The read surface, measured

The plan package is 3175 lines across six relations, and the command vocabulary it produces is a
further 2139 lines across 31 types. Inside it there are 100 leaf dispatch sites over 53 distinct
sealed variants, which is most of the zoo.

The line counts overstate the problem, though, because the plan reaches the model through a short and
enumerable set of accessors. Thirteen, in full:

[cols="3,1,3"]
|===
| Accessor | Sites | What it is

| `types()` / `type()`
| 14
| the type-grain classification verdicts

| `fieldsOf()`
| 7
| the field-grain verdicts, per type

| `operationMembersOf()`
| 6
| `OperationMemberRelation`, already relation-shaped

| `nestingReach()`
| 2
| `NestingReach`

| `joinedTableReprojectionOf()`
| 2
| `JoinedTableReprojection`

| `entitiesByType()`
| 2
| `EntityResolution`, the federation entity fold

| `deliveryOf()`
| 2
| `DeliveryFactRelation`, the declared dependency

| `tenantScopes()` / `tenantBindingOf()`
| 2
| the tenancy axis

| `sessionHooks()`
| 1
| the resolved session-hook carrier

| `connectionSynthesis()`
| 1
| `ConnectionSynthesisRelation`, already relation-shaped

| `argumentReachableInputs()`
| 1
| a name set
|===

Four of those are already relations in all but storage (`operationMembers`, `delivery`,
`connectionSynthesis`, `tenantBindings`); they were built as post-walk folds precisely because a
relation was the right shape, and moving them is transcription plus a view rather than new derivation.
Three producers additionally reach the jOOQ catalog directly (`ConditionCommands`,
`FetcherEdgeCommands`, `ProjectionCommands`), which the `sql_` family already covers.

The hard core is the first two rows: the per-coordinate classification verdicts.

## Planners share relations, not queries

The accessor table above is a census, not a blueprint. The conversion it invites, transcribing the
thirteen accessors into thirteen shared store readers that every producer calls, is banned: that
layer would be the model's read surface rebuilt one tier down, a consumer-shaped API between the
store and the planners that accretes columns the way the accessors did, and its existence would
mean the planners read the layer rather than the store, which is the current problem with the walk
wearing a new name.

Each producer formulates its own reads against the `StoreHandle`. The projections producer asks the
projection question of the claim views, the launcher producer asks the launcher question, and
neither goes through a shared shape even where the SQL comes out similar. What producers share is
the store's relations and derived views, never the query. The LSP migration stated this rule at the
grain it applies and it transfers verbatim: "What they share is the relations, not the query"
(`roadmap/lsp-reads-the-fact-store.md`, the catalog-shaped completion arms).

Duplicated query text across producers is the accepted cost, and it is cheap: the store schema is
the contract, so two producers reading the same view stay correct independently, while a shared
reader couples them on a helper whose signature is one consumer's convenience. When a read
genuinely belongs to everyone, that is the signal it is a missing derived view; it lands in the
store as one, at its own grain, per "What the store must provide" below. It does not land in the
plan as a shared helper.

Two things this rule does not forbid. A later relation referencing an earlier relation's rows by
glue key is the plan's own foreign keys, command referencing command, not a store query shared
between producers; the dependency order in the Scope section stays. And the scoping predicate
`StoreHandle.reads` stays shared, because it is the store's own contract for reaching source-keyed
families, not a consumer-shaped read.

## The facts to plan against are available

The planner half was previously sequenced behind the fact population it needed. That blocker has
largely cleared, and the distinction matters because it decides what remains:

* **The expensive population is there.** The per-coordinate classification stratum
  (`intent_authored_field_claim`, `intent_resolved_field_claim`, `intent_authored_type_claim`, and
  the demand and exemption rules beside them) is captured and derived, and the language server
  already reads it arm by arm. That was the population the planner half was waiting on and the
  reason it wanted a worked example first. The example shipped.
* **What remains is plumbing, not modelling.** Four relation-shaped folds have no home in the store
  yet: operation members, connection synthesis, tenant bindings, and delivery. None needs a new
  rule. Each was already built as a relation in the model and needs a capture-cadence writer and a
  view, which is the cheapest kind of work in this programme.
* **One live dependency remains** on the delivery verdict's own item, which derives that fold and
  stops deliberately short of flipping consumers.

The practical consequence: the two halves are no longer blocked on different things, which is what
justified keeping them apart. Both are now sequencing problems rather than modelling problems, which
is why one item owns them.

## Scope

Two success criteria, one per tier, and every deliverable is a step toward one of them:

1. `EmitPlan.produce` takes a `StoreHandle` and no `GraphitronSchema`.
2. No emitter reads a classification leaf, and `PackageImportDirectionTest` covers the emitters'
   package the way it already covers `render`.

### Planner half: the six relations, in dependency order

Later relations reference the earlier ones' rows, so the order is forced:

1. **Conditions** (`ConditionCommands`, 403 lines, 3 dispatch sites). The smallest surface and the
   one every other relation references by glue row, so it goes first and establishes the shape.
2. **Projections** (`ProjectionCommands`, 557 lines, 25 sites).
3. **Launchers** (`LauncherCommands`, 1047 lines, 10 sites). The largest producer, and the one whose
   rows the fetcher generator reads to decide between the launcher emission and the legacy builder.
4. **Fetcher edges** (`FetcherEdgeCommands`, 277 lines, 23 sites).
5. **Type units** (`TypeUnitCommands`, 188 lines, 29 sites). The highest dispatch density in the
   package, because it is the generator families' membership loops.
6. **Globals and the schema-level facts** (`EmitPlan` itself). `federationLink` and `usesOneOf`
   arrive today as `Bundle` components landed by the builder; they become store reads like the rest.

Each step is a complete unit: the relation's rows must be identical before and after, and the row
identity is directly assertable.

**Why per-relation increments are legitimate here.** An earlier plan for this work argued against a
half-converted resting state, and that argument was right for the classifier: `BuildContext.schema`
is one field, so as long as it exists every read site may use it and a partial migration is
invisible. The plan is not shaped that way. Each producer takes its inputs as parameters and writes
one relation, so "conditions and projections read the store, launchers do not yet" is a state the
signatures state plainly and a reviewer can see. The all-or-nothing argument does not transfer, and
pretending it does would make a 5000-line change land in one commit for no gain.

### Emitter half: family by family

The recipe per family: mint the command relation in `plan` from the leaves it covers, move the
emitters to `render` reading only that row, extend the borrow dial by the refs the row carries,
delete the leaf-reading bodies. Output is held byte-identical throughout, which is what makes each
family a verifiable unit with nothing to argue about.

The census is done (2026-08-16) and the order falls out of it. Six files carry all 129 dispatch
sites, 120 of them in the fetcher family: `TypeFetcherGenerator` 78, `FetcherEmitter` 30,
`FetcherRegistrationsEmitter` 12. The tail is `GeneratorUtils` 5 (all on `GraphitronType`'s
result-type arms; one result-Java-type fact on a command row retires them together),
`ObjectTypeGenerator` 3 (a `schemaType()` accessor fold on rows `SchemaShapeUnit` already
carries), and `TenantDslEmitter` 1 (the tenancy dial is already command-shaped as
`TenantStrategy`/`CarrierDsl` for migrated hosts). The other 51 files in the package have no leaf
dispatch; most of them are membership already decided by `TypeUnitCommand` and `GlobalCommand`
rows over fixed-text or carrier-driven bodies, and they are the guard extension's concern rather
than a migration's.

So the order: the three tail families first, because each is an afternoon and retires its sites
whole; then the fetcher family, which is the item's real weight and subsumes what remains of the
launchers. "The launcher family is done" was true at the body tier only: the rows methods render
through `RootLauncherRenderer` and its fragments, but `TypeFetcherGenerator` still emits the
`DataFetcher` entry points that wrap them, drains the per-class scatter helpers
(`SplitRowsMethodEmitter`) and the DataLoader registration wrappers (`RowsMethodCall`,
`DataLoaderFetcherEmitter`), and calls `LauncherCommands.produceWithoutSchema` mid-emission, an
emitter invoking a planner, which the tier rule forbids. That host tier is not a separate family;
it is part of the fetcher family's cutover. The fetcher family's membership rows exist
(`TypeUnitCommand.FetchersUnit`), but no relation says what a coordinate's fetcher method body is,
for the read entry points or the DML write statements alike, so the per-coordinate fetcher command
is the relation to mint, and the dispatch arms in `TypeFetcherGenerator` and `FetcherEmitter` are
exactly the derivation that moves into its producer. The routine-write slice of that family is
scoped as a worked example on another item (below). Alongside the leaf work, the schema-shape and
util generators' model-taking entry points (15 of the pin's 18) retire as their reads become store
reads or command columns, largely with the planner half's sixth step.

Two instrument corrections the census surfaced, each owed to the first increment that touches it:

* `MODEL_TAKING_ENTRY_POINTS` counts only methods literally named `generate`, so four public
  model-taking entry points are invisible to it: `FetcherRegistrationsEmitter.emit` (both
  overloads), `SchemaSdlEmitter.emit`, `ObjectTypeGenerator.generateFor`. Widen the counting rule
  and re-pin at the true number; that raise is a counting-rule fix, not a boundary move, and the
  pin's never-raise clause is to be read accordingly.
* The pins count dispatch, not reads. A file that takes a leaf as a parameter and folds over it
  without one `instanceof` scores zero on every pin yet still reads the hierarchy;
  `MultiTablePolymorphicEmitter` (2327 lines, 24 leaf references, zero dispatch sites) is the
  large case. The guard extension in "The closer" is what covers these files, because it forbids
  the import; the pins alone never would.

### The closer

Extend `PackageImportDirectionTest` over both packages once they are empty of leaf readers. The
two dials differ: `render` keeps its existing restriction to commands plus the named pure-data
refs, while `plan` gets store reads (`StoreHandle` and the generated store tables) plus the command
vocabulary it produces, with the seven leaf hierarchies forbidden by name. The ratchet pins retire
in the same commit that extends the guard over the package each pin measures: a zeroed pin the
guard makes unraisable is a second mechanism for one invariant, and two mechanisms for one
invariant drift apart.

### Sequencing between the halves: planner leads, per family

Settled: the planner half leads per family, not globally. A family's command relation becomes
store-derived first, then its emitter moves onto that row. That keeps both halves advancing on the
same family instead of two fronts crossing, and every emitter cutover verifies against a plan that
is not simultaneously changing its own inputs. Where a family has no command relation yet, minting
it from the leaves it covers is a legitimate intermediate step (the plan-side pin rises, as its
comment anticipates), but the re-sourcing follows within the same family's arc rather than being
deferred to a global second pass; a minted-from-leaves relation is transitional state, not a
resting place.

## What the store must provide

Do not model a relation at the plan's convenience; that is how a store accretes consumer-shaped
columns. Each fact lands at its own grain and every other consumer inherits it, which is the loop
`roadmap/lsp-reads-the-fact-store.md` ran four times and wrote down as doctrine. The three
populations are enumerated under "The facts to plan against are available" above.

## Risks

* **This is the largest item on the roadmap by surface.** The planner half alone is 5314 lines of
  plan and command code, 100 dispatch sites, 53 variants; the emitter half adds the generators'
  package on top. It is scoped as one item because it has one architecture and one end state, not
  because it is small. Expect it to run as long as the LSP migration has, or longer.
* **The per-coordinate verdict population is the schedule.** Everything else is plumbing. If the
  classification views turn out to need residues the way the demand stratum did, the honest response
  is to carry them as named residues and convert the relations whose verdicts are clean, not to widen
  the item.
* **Command rows are structured, not flat.** `LauncherCommand` carries nested sealed payloads
  (`LaunchSource`, `GlueCall`, `Invocation`, `TenantStrategy`, `ResultShape`). Relationally that is a
  row plus child relations, and choosing those grains badly is how the command vocabulary ends up
  transcribed into SQL rather than modeled. Some of it is deliberately not store-bound: the plan
  already refuses to hold javapoet types, and that boundary stays.
* **The closure invariant is the safety net and must not be weakened.** A converted producer that
  commits a row no renderer emits, or drops one a renderer needs, fails the fold. Keep that gate loud
  during the migration rather than relaxing it per increment.
* **The accessor census reads as an implementation plan.** The path of least resistance for whoever
  converts producer number two is to extract producer number one's store reads into a shared
  helper, and each extraction after that looks more natural than the last. "Planners share
  relations, not queries" above is the rule; the reviewer of every planner-half increment should
  check for it, because no ratchet counts this.
* **The two halves can deadlock on each other if sequenced globally.** Converting all six relations
  before any emitter moves leaves the emitters reading leaves for the whole programme; converting all
  emitters first means minting command relations from leaves that the planner half will then re-source.
  Per-family sequencing (settled above) is the way out; each increment's reviewer should hold the
  work to it.

## Out of scope

* **The classification walk itself.** It keeps producing the leaf model for its remaining consumers.
  This item removes the plan and the emitters from that list; the validator and the LSP projection are
  other items' work.
* **Reordering capture ahead of the walk.** The plan runs after capture already. An earlier plan for
  the planner half proposed the reorder plus a store-reading classifier; that was scaffolding for a
  walk being drained from the consumer end instead, and it is dropped rather than deferred. It becomes
  relevant again only if some axis has to migrate its walk-side mint rather than its consumers, which
  no axis has needed yet.

## Relationship to other items

* `roadmap/delivery-verdict-derives-from-the-store.md` is the declared dependency. It derives one
  verdict and deliberately stops short of flipping consumers, naming the planning-stage consumers as
  the eligible ones. Those consumers are this item. It is also the worked example the per-coordinate
  verdicts follow, so it lands first for the pattern as much as for the view.
* `roadmap/lsp-reads-the-fact-store.md` is the shape to copy: one item, many increments, each arm
  landing on its own commit with what it settled written down. It also owns `StoreHandle`, which the
  planner half's producers take. Not a declared dependency: the earlier edge existed because both
  restructured `buildOutput`, and this item no longer touches the pipeline order. Its cutover still
  matters as intelligence, being the first store-side projection of classification in the tree.
* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` owns the drain: the facts that replace
  the leaves, and the method graph the emit lowers onto. This item is a slice of it, the slice whose
  consumers emit code, and it is independently schedulable while carrying its own `depends-on` edge.
  It is the **consumption** side and must not redesign facts. The two meet at the plan tier: that
  item decides what a planner reads, this one decides that a planner is the only thing that reads
  it.
* The `facts-and-commands` programme (Done, see `roadmap/changelog.md`) built the
  `command` / `plan` / `render` triangle, `EmitPlan`, the command relations and these ratchets. This
  item is that programme's completion condition, not a re-run of it. Its slice logs are the
  reference for how a family migrates and what holding output identical costs.
* `roadmap/nodeid-key-projection-on-routine-params.md` carries the routine-write family's migration
  as a stage, because a feature there needed a carrier and the leaf was the wrong one. That stage is
  the worked example the emitter half generalises from. If it lands first, this item inherits a
  proven recipe and one fewer family; if this item is picked up first, that stage should be lifted
  onto it.
* The former decompose-`TypeFetcherGenerator` item (R7, see `roadmap/changelog.md`) asked how to
  break up that file and offered decomposing along the field taxonomy as its leading option. It was
  discarded in this item's favour: the file does not get decomposed along the leaves, it empties
  into `render` as the families migrate.

## Retired vocabulary

Provisional; the Done-gate sweep greps for these, and the list grows as increments land.

* `EmitPlan.produce`'s `GraphitronSchema` parameter, and the `Bundle` components it threads
  (`federationLink`, `usesOneOf`).
* Whichever post-walk folds lose their last reader as their relation moves store-side:
  `OperationMemberRelation`, `ConnectionSynthesisRelation`, `TenantBindingIndex`,
  `DeliveryFactRelation`, `NestingReach`, `JoinedTableReprojection`. Each retires only when the plan
  was its last consumer; name them individually as they go rather than as a block.
* `TypeFetcherGenerator.IMPLEMENTED_LEAVES` and the leaf-keyed coverage vocabulary around it, once
  membership is a command relation's rows rather than a set of leaf classes.
* The `CommandSeamRatchetTest` pins, each retired in the same commit that extends the structural
  guard over the package it measures (settled in "The closer" above).

## Coverage

* **Row identity, per increment.** Each converted relation's rows must equal the leaf-derived rows on
  the whole classified corpus. Assert it directly rather than inferring it from a green build; this is
  the shadow-agreement discipline the demand and column-match sweeps set, applied per relation.
* **Output identity, per emitter family.** The emitter half changes no generated source, so the
  assertion is that it changes none: the family's existing pipeline-tier expectations hold verbatim
  across the cutover.
* **The compile and execution tiers are the real gate.** `graphitron-sakila-example` compiles the
  emitted sources and runs them against PostgreSQL, so a command row that changed shows up as
  behaviour, not just as a diff. `GeneratorDeterminismTest` and `IdempotentWriterTest` cover ordering.
* **The fold's closure invariant** stays as-is and is the per-increment backstop.
* **The registered agreement anchor** for every new relation, through `FactCaptureAgreementTest`'s
  mechanical driver, which has no skip list, so a relation added for this item cannot arrive
  unchecked.
* **The ratchet pins** move down in the same commit as the work that lowers them, never raised on the
  generators side, and the plan-side pin falls rather than rises once the producers re-source.

## Provenance

The planner half was filed separately and specified three times before landing here. The first plan
took a pipeline reorder plus a store-reading classification walk; the second kept that target after an
owner correction sharpened it. Both were dropped when the owner observed that the drain is working
from the consumer end: the LSP migration has moved nearly all of its surface, the MCP is close, and a
store-reading classifier is scaffolding for a walk that is being demolished. The reorder had no
consumer without the classifier work behind it, so the item was repointed onto the gap that survey
exposed: no slice had yet moved working generation code onto the store.

The emitter half was filed after a spec review found a feature item routing a fact into
`LaunchSource.RoutineChain`, a carrier its own motivating case never reaches, because the emitter it
actually needed reads a leaf directly and nothing forbids that. The two halves were briefly separate
items on the reasoning that the planner half was blocked on facts that did not exist yet; measuring
the DDL showed the expensive population had landed and only four relation-shaped folds were missing,
so they merged at the owner's direction, the planner half's body absorbed whole rather than restated.
