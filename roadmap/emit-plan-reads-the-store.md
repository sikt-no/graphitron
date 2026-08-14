---
id: R667
title: "The emit plan is built from the store, not from the leaf model"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [delivery-verdict-derives-from-the-store]
created: 2026-08-14
last-updated: 2026-08-14
---

# The emit plan is built from the store, not from the leaf model

## Problem

Every migration that has landed so far moved a *reader* onto the store. The authored-claim conflict
detection reads the claim views, the `diagnostic` surface serves the diagnostics stratum, and the
language server has re-sourced completion, hover and goto-definition arm by arm. All of them answer
questions. None of them emit code.

The generator's emit decisions are still made entirely from the leaf model. `EmitPlan.produce` takes
a `GraphitronSchema` and joins its facts into the six command relations the run will render, and
every one of those joins dispatches on sealed leaf variants. So the store has never yet been the
source of a single generated file, and the claim that it is the destination is, on the evidence of
the emitted output, unproven.

That is the gap this item closes. It is the first slice that moves working generation code onto the
store.

## Why the plan, and not some other producer

The plan is the narrow waist of the emit half. Commands are complete rows: the render shell folds
over them and hands each row to its renderer, and the fold enforces closure in both directions (a
renderer emitting an uncommitted unit fails the run, a committed unit nobody emitted fails it too).
Renderers consume commands, not the leaf model. So converting the plan makes the whole render half
store-derived transitively, without touching a renderer.

The doctrine is already written on `EmitPlan` itself: "the fact store carries what the schema means,
the plan carries what this run emits." Today the first half of that sentence is aspirational, because
what the plan reads to decide what this run emits is the walk's model rather than the store. This
item makes the sentence true.

Two further properties make it the right first producer. It sits after capture, so nothing about the
pipeline's stage order has to change; and its output is pinned harder than anything else in the tree,
because the emitted sources are compiled and executed against a real database, so a conversion that
changes a single command row cannot pass silently.

## The read surface, measured

The plan package is 3175 lines across six relations, and the command vocabulary it produces is a
further 2139 lines across 31 types. Inside it there are 100 leaf dispatch sites over 53 distinct
sealed variants, which is most of the zoo.

The line counts overstate the problem, though, because the plan reaches the model through a short
and enumerable set of accessors. Thirteen, in full:

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
| `DeliveryFactRelation`, this item's declared dependency

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
relation was the right shape, and moving them is transcription plus a view rather than new
derivation. Three producers additionally reach the jOOQ catalog directly (`ConditionCommands`,
`FetcherEdgeCommands`, `ProjectionCommands`), which the `sql_` family already covers.

The hard core is the first two rows: the per-coordinate classification verdicts. Those are what the
demand stratum began and what `roadmap/delivery-verdict-derives-from-the-store.md` continues one axis
at a time. This item is where that work acquires a consumer that emits.

## Scope

`EmitPlan.produce` takes a `StoreHandle` and no `GraphitronSchema`. That is the success criterion,
and every deliverable below is a step toward it.

The six command relations convert in dependency order, because the later ones reference the earlier
ones' rows:

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
identity is directly assertable. Land them as separate commits under this item, the way
`roadmap/lsp-reads-the-fact-store.md` lands its arms, rather than as separate items.

### Why per-relation increments are legitimate here

The last plan for this item argued against a half-converted resting state, and that argument was
right for the classifier: `BuildContext.schema` is one field, so as long as it exists every read site
may use it and a partial migration is invisible. The plan is not shaped that way. Each producer takes
its inputs as parameters and writes one relation, so "conditions and projections read the store,
launchers do not yet" is a state the signatures state plainly and a reviewer can see. The all-or-
nothing argument does not transfer, and pretending it does would make a 5000-line change land in one
commit for no gain.

## What the store must provide

Do not model a relation at the plan's convenience; that is how a store accretes consumer-shaped
columns. Each fact lands at its own grain and every other consumer inherits it, which is the loop
`roadmap/lsp-reads-the-fact-store.md` ran four times and wrote down as doctrine.

Concretely, expect three populations:

* **Already there.** The `sql_` catalog reads, the directive decodes behind most membership
  predicates, `graphql_field.is_list` and the structural facts.
* **Relation-shaped folds needing a home.** `operationMembers`, `connectionSynthesis`,
  `tenantBindings`, and `delivery` once its dependency lands. These were already built as relations;
  they need capture-cadence writers and views, not new rules.
* **Genuinely derived verdicts.** The per-coordinate classification the first two accessor rows
  carry. This is the expensive population and the reason the item is sequenced behind at least one
  worked example of the pattern.

## Risks

* **This is the largest item on the roadmap by surface.** 5314 lines of plan and command code, 100
  dispatch sites, 53 variants. It is scoped as one item because it has one success criterion, not
  because it is small. Expect it to run as long as the LSP migration has.
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
  commits a row no renderer emits, or drops one a renderer needs, fails the fold. Keep that gate
  loud during the migration rather than relaxing it per increment.

## Out of scope

* **The classification walk itself.** It keeps producing the leaf model for its remaining consumers.
  This item removes the plan from that list; the validator, the generators' own model reads and the
  LSP projection are other items' work.
* **Reordering capture ahead of the walk.** The plan runs after capture already. The previous plan for
  this item proposed the reorder plus a store-reading classifier; that was scaffolding for a walk that
  is being drained from the consumer end instead, and it is dropped rather than deferred. It becomes
  relevant again only if some axis has to migrate its walk-side mint rather than its consumers, which
  no axis has needed yet.
* **Renderers.** They consume commands and are already insulated. If a renderer reads the model
  directly, that is a finding to file, not to fix here.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` owns the drain. This is a slice of it, and
  the first slice whose consumer emits code.
* `roadmap/delivery-verdict-derives-from-the-store.md` is the declared dependency. It derives one
  verdict and deliberately stops short of flipping consumers, naming the planning-stage consumers as
  the eligible ones. Those consumers are this item. It is also the worked example this item's
  per-coordinate verdicts follow, so it lands first for the pattern as much as for the view.
* `roadmap/lsp-reads-the-fact-store.md` is the shape to copy: one item, many increments, each arm
  landing on its own commit with what it settled written down. It also owns `StoreHandle`, which this
  item's producers take. It is no longer a declared dependency: the earlier edge existed because both
  items restructured `buildOutput`, and this item no longer touches the pipeline order. Its cutover
  still matters as intelligence, being the first store-side projection of classification in the tree.

## Retired vocabulary

Provisional; the Done-gate sweep greps for these, and the list grows as increments land.

* `EmitPlan.produce`'s `GraphitronSchema` parameter, and the `Bundle` components it threads
  (`federationLink`, `usesOneOf`).
* Whichever post-walk folds lose their last reader as their relation moves store-side:
  `OperationMemberRelation`, `ConnectionSynthesisRelation`, `TenantBindingIndex`,
  `DeliveryFactRelation`, `NestingReach`, `JoinedTableReprojection`. Each retires only when the plan
  was its last consumer; name them individually as they go rather than as a block.

## Coverage

* **Row identity, per increment.** Each converted relation's rows must equal the leaf-derived rows on
  the whole classified corpus. Assert it directly rather than inferring it from a green build; this is
  the shadow-agreement discipline the demand and column-match sweeps set, applied per relation.
* **The compile and execution tiers are the real gate.** `graphitron-sakila-example` compiles the
  emitted sources and runs them against PostgreSQL, so a command row that changed shows up as
  behaviour, not just as a diff. `GeneratorDeterminismTest` and `IdempotentWriterTest` cover ordering.
* **The fold's closure invariant** stays as-is and is the per-increment backstop.
* **The registered agreement anchor** for every new relation, through `FactCaptureAgreementTest`'s
  mechanical driver, which has no skip list, so a relation added for this item cannot arrive
  unchecked.

## Provenance

Filed after a question during the delivery-verdict item's Spec pass about the pipeline's stage order,
and specified twice before this. The first plan took the reorder plus a store-reading classification
walk; the second kept that target after an owner correction sharpened it. Both were dropped when the
owner observed that the drain is working from the consumer end: the LSP migration has moved nearly all
of its surface, the MCP is close, and a store-reading classifier is scaffolding for a walk that is
being demolished. The reorder had no consumer without the classifier work behind it, so the item was
repointed rather than discarded, at the owner's direction, onto the gap that survey exposed: no slice
had yet moved working generation code onto the store. The plan is where that starts.
