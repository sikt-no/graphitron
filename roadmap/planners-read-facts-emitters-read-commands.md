---
id: R682
title: "Planners read facts, emitters read commands: dissolve the walk and the leaf zoo"
status: In Progress
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-20
---

# Planners read facts, emitters read commands: dissolve the walk and the leaf zoo

The intended architecture is one sentence. Capture writes facts; planners read facts and produce
commands; emitters render commands; validation is questions asked of the facts. Each tier reads
only the tier below it, so a planner never reaches past the facts into anything that produced
them, and an emitter never reaches past its command into the thing that produced it.

That sentence is the functional-core / imperative-shell topology the development principles fix
(`docs/architecture/explanation/development-principles.adoc`), applied to the emit path: the
planners are the core, pure derivation from typed facts to command rows, and `render` is the shell
that encodes those rows outward. `roadmap/audits/2026-07-26-fcis-command-layer-distance.md`
measured the tree's distance from that ideal; this item owns closing it, and closing it is what
dissolves the leaf zoo, because the generator is the zoo's final consumer.

## Goal and strategy

**What changes for a consumer of graphitron when this lands: nothing, and that is the gate.** Every
half below holds behaviour exactly, byte-identical generated output for the emit tiers and identical
message, location and severity for the validator. A schema author sees the same files and the same
errors the day after as the day before. Anyone reading this item for a user-visible outcome should
stop here; the payoff is entirely internal, and stating it plainly is the only honest way to justify
the largest item on the roadmap.

The payoff is the cost of the next change. Today a fact about a coordinate can be reached two ways,
joined onto a command row or read off the leaf the emitter happens to be holding, and only one is
right. Both are reachable, nothing forbids the wrong one, and a recent design took it: a feature
routed a fact into `LaunchSource.RoutineChain`, a carrier its own motivating case never reaches,
because the emitter it actually needed reads a leaf directly. That is not a mistake anyone can be
careful enough to avoid at 131 dispatch sites; it is what a missing boundary produces. After this
item there is one way, because the other one does not compile. The same move retires roughly 50,000
lines of walk, deletes a taxonomy of 72 leaves that every new field kind currently has to be
threaded through, and puts the emit path under the same structural guard `render` already has.

The migration is systematic and component-at-a-time, not a strangler: the language server came off
the leaf model, then the MCP, and the generator is what remains. When it is done, the
classification walk and the sealed leaf hierarchies it mints are deleted. Four points fix the
strategy, and every section below serves one of them:

1. **The leaf zoo dissolves.** Not "stops growing", not "loses its largest consumer": the walk and
   the sealed hierarchies leave the tree, because after this item nothing reads them.
2. **Every fact the generator or the validator reads off a leaf lands as a normalized fact in the
   store first**, at its own grain, per the fact model's own law. Where a leaf carries a verdict no
   relation states yet, the relation is the deliverable; the leaf is never wrapped, adapted, or
   kept as a side channel.
3. **The generator is plan → command → emit, FCIS.** A planner reads the store, ideally as a single
   query on the relation's own grain, and derives a list of command rows; the render shell folds
   over the rows and emits. No planner reads a leaf, no emitter reads anything but its command row.
4. **Validation becomes views in the fact model** wherever a check is expressible as a relation
   over captured facts, which is most of them. A check that genuinely cannot be a view (it needs
   computation SQL cannot state) runs as a query over the store whose findings are inserted back
   into the fact model as rows, and the error surface reads those rows. Either way the walk stops
   being what validation reads.

## Problem

Every store migration that has landed so far moved a *reader*. The authored-claim conflict detection
reads the claim views, and the `diagnostic` surface serves the diagnostics stratum. Since this item
was filed, both external clients finished shedding the zoo: `graphitron-mcp` answers every tool from
the store and compiles against nothing in the reactor but `graphitron-model` plus jOOQ, with
`StoreClientBoundaryTest` forbidding the classification taxonomies by name (R642, Done, see
`roadmap/changelog.md`); and the language server, having re-sourced completion, hover,
goto-definition, inlay hints and diagnostics arm by arm, retired its last generator-side read when
the routine call surface became a relation and `Workspace` stopped holding a build snapshot at all.
All of them answer questions. None of them emit code.

The emit half is still made almost entirely from the leaf model, at both of its tiers.
`EmitPlan.produce` takes a `GraphitronSchema` and joins its facts into most of the command relations
the run will render, those joins dispatching on sealed leaf variants. Below it, the un-migrated
emitters dispatch on leaves directly rather than folding over command rows.

"Almost" is load-bearing, and it is the item's best evidence rather than a caveat. One producer has
already converted: `KeyProjectionCommands.produce` takes `ResolvedKeyProjections.Projections` and no
`GraphitronSchema`, `EmitPlan` hands it store rows, and `ProjectedKeyReads`, `ProjectedKeyHost` and
`ConditionGlueRenderer` emit from the relation, so part of the generated output is store-derived
today. It landed 2026-08-19 (`2ef0b57`) with a commit message that states this item's thesis
directly: a producer reads facts, the walked model is not a fact source, and a plan-tier join
against it leaves the walk alive one tier past where it was supposed to end. Three relations had to
land first for it (`sql_schema.tables_class_fqn`, the new `intent_resolved_node_type_id`, and
`StoreNodeTables` as the first store-sourced producer of a `TableRef`), which is exactly the
per-producer shape the rest of this half repeats.

So the recipe is proven and the remaining question is coverage, not feasibility. What the emitted
output does not yet demonstrate is a *whole* family produced from the store, which is what the
per-relation increments below deliver.

The validator is the same story one stage earlier. `GraphitronSchemaValidator` is 2,024 lines and
74 `validate*` methods reading nothing but the leaf model: it re-wraps the `Rejection` each
`Unclassified*` leaf carries, runs fourteen structural checks over the classified types and fields,
and drains `schema.diagnostics()`. It even reads *upward*: it calls two planners' collision checks
(`ProjectionCommands.addressCollisions`, `LauncherCommands.methodCollisions`) and imports two
emitters, a validator depending on planners and the shell. The successor channel already ships beside it: `StoreDetections.violations()` folds
store-derived detections (`AuthoredClaimConflicts`, `ArgmappingProjectionDefects`) into the same
`ValidationReport`, minting user-facing errors with no leaf anywhere in the derivation. And
validation runs after `captureFactsAndDetect` in the pipeline, so the store is available to every
check today; what is missing is the migration, not the plumbing.

Those are one problem at three surfaces, which is why one item owns them. Converting the plan
without the emitters leaves a store-derived command relation that a leaf-reading emitter can still
bypass; converting the emitters without the plan leaves commands that are complete rows derived from
the walk; and converting both without the validator keeps the whole walk alive for its smallest
consumer. None alone makes the sentence at the top true, and only all of them together let the walk
be deleted.

## Where the line actually falls today

`no.sikt.graphitron.render` already lives under the rule. It contains no dispatch on a
classification leaf and imports none of the leaf hierarchies; `PackageImportDirectionTest` pins that
structurally, restricting the package to commands plus a named dial of pure-data model refs. A
renderer there cannot reach a leaf even by accident.

`no.sikt.graphitron.rewrite.generators` is the same job under none of the rules. It is outside that
guard, and it is where the un-migrated emitters live: `TypeFetcherGenerator` (5,806 lines)
and `FetcherEmitter` between them carry nearly all of the leaf dispatch, and `TypeFetcherGenerator`
still enumerates its coverage as a set of leaf classes (`IMPLEMENTED_LEAVES`, 37 entries) rather than as
rows of a command relation. Nothing prevents an emitter there from reading whatever it likes off a
leaf, which is not a hypothetical: it is how a recent design landed on the wrong carrier, because
"join the fact onto the command row" and "read it off the leaf the emitter already holds" are both
reachable and only one is right.

`no.sikt.graphitron.plan` reads leaves where it has not been converted yet. `EmitPlan.produce` still
takes a `GraphitronSchema` and dispatches on sealed variants to build most of the command relations,
with the key-projection producer above as the one converted exception. That is the planner half, and
it is the largest single surface.

A census of leaf imports across main sources (re-taken against trunk `7f2ff35`) puts the rest of the
blast radius in a short list the terminal deletion has to see emptied: the validator (above), six
files in `generators.schema` (`GraphitronSchemaClassGenerator` the largest), four class generators
in `generators.util`, `schema.federation`'s `EntityResolutionBuilder`, `catalog`'s `CatalogBuilder`,
`compile`'s `PlanCompileGraph`, and one transitional writer in `derive`, `DemandResidue`. The
`diagnostics` package is already clear of the hierarchies: `RejectionFacts` still retires with the
walk, but it transcribes the `Rejection` axis rather than importing a leaf, so the terminal
deletion reaches it through its writer's input and not through this census. No `walk_`-shaped
projection appears here at all, R743 and R740 having drained that family between them, which is the
same boundary the "Relationship to other items" section states from the other end. `command` and
`render` (67 files) sit below the boundary, importing post-classification value records only, and
the MCP, the LSP and the maven plugin are clean.

## The instrument already exists and already declares the target

`CommandSeamRatchetTest` was installed by the `facts-and-commands` programme and measures this seam
on both tiers. Its own javadoc states the terminal condition in as many words: the generators-side
counts "ratchet down to zero", and the plan-side count is "expected to rise while producers are fed
by leaf dispatch and to ratchet back to zero when the fact-visitor engine re-sources them". Live
pins, read off the test on trunk `7f2ff35`:

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
| 62
| emitters: the same for `case` patterns

| `PLAN_LEAF_REFERENCES`
| 147
| planners: leaf references in `plan/`, the pin that legitimately rose before it falls
|===

Since filing (2026-08-16, at 18/69/60/125) the leaf-counting pins have risen on net: the routine
carrier and the discriminated interface child's batched half each landed as a new leaf with its own
dispatch sites, which the never-raise rule reads as new coverage rather than a boundary move. That
is the flat-line argument in live data, one notch worse: while nobody owns the drain, feature work
grows the zoo faster than incidental migration shrinks it.

All four go to zero. That is much of the item stated numerically; the residue the pins cannot see
(a leaf taken as a parameter and never dispatched on) is what the guard extension in "The closer"
exists to catch, and the census in the emitter half names those files. What the pins do not measure
at all is the validator half and the terminal deletion, which have their own sections below.

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

The plan package is 3,592 lines across eight producers (seven command relations plus `EmitPlan`'s own
globals), and the command vocabulary it produces is a further 2,543 lines across 34 types. The leaf
dispatch inside it is most of the zoo; `PLAN_LEAF_REFERENCES` is the live count and the census below
names the producers.

The line counts overstate the problem, though, because the plan reaches the model through a short and
enumerable set of accessors. Thirteen, in full:

[cols="3,1,3"]
|===
| Accessor | Sites | What it is

| `types()` / `type()`
| 14
| the type-grain classification verdicts

| `fieldsOf()`
| 8
| the field-grain verdicts, per type

| `operationMembersOf()`
| 6
| `OperationMemberRelation`, already relation-shaped

| `nestingReach()`
| 2
| `NestingReach`

| `joinedTableReprojectionOf()`
| 3
| `JoinedTableReprojection`

| `entitiesByType()`
| 2
| `EntityResolution`, the federation entity fold

| `deliveryOf()`
| 2
| `DeliveryFactRelation`, whose store derivation this item absorbed (see below)

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
No producer reaches jOOQ directly: `plan/` imports nothing from `org.jooq`, and the three producers
that need catalog facts (`ConditionCommands`, `FetcherEdgeCommands`, `ProjectionCommands`) take them
as `TableRef` and `TableExpr` value records, whose contents the `sql_` family already covers. That
is why `KeyProjectionCommands` needed `StoreNodeTables` as the first store-sourced producer of a
`TableRef`: the shape a converted producer wants is already the shape the plan holds, and only the
source moves.

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
(R638, Done, see `roadmap/changelog.md`, the catalog-shaped completion arms). The rule has since been
filed as store-wide doctrine on its own item (`roadmap/consumers-share-relations-not-queries.md`),
so this section is an application, not this item's invention; if the two ever read differently, the
doctrine item wins.

Duplicated query text across producers is the accepted cost, and it is cheap: the store schema is
the contract, so two producers reading the same view stay correct independently, while a shared
reader couples them on a helper whose signature is one consumer's convenience. When a read
genuinely belongs to everyone, that is the signal it is a missing derived view; it lands in the
store as one, at its own grain, per "What the store must provide" below. It does not land in the
plan as a shared helper.

Reader-count is the promotion trigger for *projections* only, and after the walk is gone it cannot
be the trigger for verdicts: the generator may then be a verdict's only reader, and a second-reader
test would license burying the derivation in one producer's `SELECT` text, the leaf-zoo failure
restated one tier down (the decision living in the consumer). The nature test is stronger: a
*verdict* (which arm fired, which table stands for the type, which resolution won) gets a named
view at its own grain even with one reader, while joining, filtering and shaping stay in the
producer's query, duplicated freely.

Two things this rule does not forbid. A later relation referencing an earlier relation's rows by
glue key is the plan's own foreign keys, command referencing command, not a store query shared
between producers; the dependency order in the Scope section stays. And the scoping predicate
`StoreHandle.reads` stays shared, because it is the store's own contract for reaching source-keyed
families, not a consumer-shaped read.

## Where a producer's SQL lives: `plan/` owns it, `derive/` shrinks

Two converted read paths already disagree about where store-reading code sits, and at one producer
converted out of eight the fork is cheap to settle and expensive to inherit. The key-projection
read lives in `rewrite/derive` (`ResolvedKeyProjections`, `StoreNodeTables`) with
`plan/KeyProjectionCommands` a pure shape transform beside it; this item's positive dial for
`plan/` (in "The closer") instead expects producers to query the store directly. Settled: **the
producer's own run-scoped derivation queries live beside the producer in `plan/`.** The
discriminator is the one "What the store must provide" already states, extended to package
geography: a fact about the schema is store material, so Java that assembles one is a missing view
wearing a jacket and gets pushed into the DDL rather than parked in `derive/`; a derivation of
what this run emits is the producer's own `SELECT` and lives with the producer. `rewrite/derive`
keeps what is neither: the store detections (`AuthoredClaimConflicts`,
`ArgmappingProjectionDefects`) and the transitional walk-shadow writers already scheduled to
retire. Under that rule `StoreNodeTables` and `ResolvedKeyProjections` get revisited by the
increment that fixes their read shape (named in the planner half): their content is schema-grain
fact assembly, so most of it becomes views.

Two consequences are named now because the dial would otherwise discover them late:

* **`StoreHandle` is the read handle, and bare `org.jooq.DSLContext` is deliberately not in the
  dial.** `StoreHandle`'s own javadoc states the invariant and its failure mode: one type for
  every consumer, because two handles are two conventions and nothing then makes a query site
  take the one its module's rows were written under. A positional `(DSLContext, graphName)` pair
  is that second convention, already in two files, and nothing fails when a producer forgets the
  `reads(...)` scoping on a source-keyed family; the answer just silently folds in another
  source's rows. Admitting `StoreHandle` and not `DSLContext` converts the store's scoping
  contract from a habit into a package-boundary rule, and it is the cheapest instrument the
  planner half carries. The routine-write reads need it immediately:
  `intent_name_matched_key_pair` is catalog-keyed, with no `graph_name` column at all.
* **Rows carry FQN strings; `render` lifts them.** `StoreNodeTables` today mints javapoet
  `ClassName`s from captured FQNs, and moved as-is into `plan/` that would put javapoet inside
  the plan's dial and falsify the Risks bullet that says the plan refuses to hold javapoet types.
  The boundary stays as stated: a command row carries the captured string, and the lift to a
  javapoet type happens in the shell, where the emit library is already the package's business.

## One statement per grain: the N+1 lesson lands before the first producer

Both finished migrations fell into the same trap on the way in, and both wrote the correction down,
so this item gets to inherit the rule instead of rediscovering it. The LSP's readers were each built
to own exactly one relation, which is right about facts, and nothing said where *composition* lives,
so it fell to Java one loop at a time: a hover cost four to seven statements with an N+1 in the
middle, and the overlay pass cost up to three statements per directive node. The durable fix was one
statement per capability, composed with `MULTISET`, and a counted test. The MCP paid the same
tuition in seconds: two derived views read as correlated subqueries under a per-field projection
cost twenty-four seconds where reading each view whole cost milliseconds, and the backing-class
closure's per-request recursive form measured 369 seconds before it moved to a capture-cadence
materialization. The corrections graduated to `docs/architecture/explanation/fact-model.adoc`
(the one-projection-per-grain paragraph, the window-function/recursive-view rule) and to
`roadmap/views-carry-keys-not-payloads.md`; this section is what they instruct the planner half to
do, stated here because the trap is exactly the shape of the code being converted.

* **A producer's read grain is the relation it derives, never the row.** Every producer being
  converted is today a dispatch loop over coordinates, so the path of least resistance is a store
  query per coordinate inside that loop, the walk's shape transcribed into N+1 SQL and called a
  migration. The converted form drives one statement (or a few, one per independent question) from
  the view that defines row existence, over the whole schema at once, and derives every row of its
  relation from that read. The plan runs at build cadence over every coordinate, so the per-row
  multiplier here is larger than any editor surface's, not smaller; the MCP's numbers are what an
  H2 round trip per coordinate costs at exactly this scale.
* **Nested payloads ride the driving statement as `MULTISET`.** Command rows are structured
  (`LauncherCommand` carries nested sealed payloads), and relationally that is a row plus child
  relations. jOOQ supports the shape well: `MULTISET` nests the child rows under the parent through
  the key the relations already declare, `Records.mapping` lands each level on the record it
  already has, and H2 serves the nesting via JSON aggregation. The alternative, several statements
  at several grains reassembled with accumulators in Java, is a relational join written by hand;
  fact-model.adoc names its failure modes (invented grouping keys, consistency argued rather than
  held, product row counts over JDBC) and they bind here as written. `MULTISET` belongs in the
  producer's `SELECT`, never in the store's DDL: a view carries keys and its own products, a
  projection denormalizes, and asking the store for a plan-shaped view is the same mistake as the
  shared-reader layer the previous section bans.
* **Deep derived views are read once and paired on their key.** A view carrying a window function
  or a recursive term cannot be pruned by an outer predicate, so correlating it per driving row
  pays its whole evaluation once per row; read it whole, filtered to the population, and join it in.
  The per-coordinate classification views the planner half reads are precisely this species.
* **Materialization is a measured escalation, not a default.** The MCP moved one closure to capture
  cadence after measuring the recursive form; everything else stayed views. A producer that finds a
  read slow says so with a number before anything moves cadence.
* **Nothing here sees the cost of reading one view from seven producers, and that is a real hole.**
  The two rules above compose badly and the item should say so rather than discover it. "Planners
  share relations, not queries" makes duplicated query text the accepted cost, so several producers
  legitimately read the same derived view; "deep derived views are read once and paired on their
  key" then says each of those reads evaluates the view whole. A view carrying a window function or
  a recursive term therefore gets evaluated once per producer that wants it, and the per-producer
  statement-count pin below is blind to it by construction: seven producers reading one view each is
  a count that is a function of each producer's arms and not of the corpus, so every pin stays
  green while the plan tier's wall clock multiplies. This is the same species of defect as the MCP's
  twenty-four seconds, one tier up and split across files so no single reviewer sees it. The
  per-producer pin is necessary and not sufficient; the aggregate instrument is a plan-tier wall
  clock, which `roadmap/build-wall-clock-guardrail.md` (R733) owns and which is still Backlog. Until
  that guardrail exists, each planner increment states its own contribution to plan-tier wall clock
  as a number in its commit message, and a producer that adds a read of a view another producer
  already reads says so explicitly, because that is the pairing no gate here can see.
* **The pin is a counted test, not a benchmark.** The LSP's enforcement is
  `DeclarationHoverStatementCountTest`: an execute listener asserting statement counts, no timing,
  no fixture scale, because the N+1 was invisible to every behavioural assertion (a fan-out into
  round trips returns exactly the rows one statement does, just slower). Each converted producer
  lands the same instrument in the same commit: a statement-count pin at producer grain, asserting
  the count is a function of the producer's arms and never of the corpus. A count that grows with
  the schema is the defect the pin exists to refuse, and no other gate in the Coverage section can
  see it.

## The facts to plan against are available

The planner half was previously sequenced behind the fact population it needed. That blocker has
largely cleared, and the distinction matters because it decides what remains:

* **The expensive population is there, with one deliberate hole at exactly the mutation root.**
  The per-coordinate classification stratum
  (`intent_authored_field_claim`, `intent_resolved_field_claim`, `intent_authored_type_claim`, and
  the demand and exemption rules beside them) is captured and derived, and the language server
  already reads it arm by arm. That was the population the planner half was waiting on and the
  reason it wanted a worked example first. The example shipped. One mask in that stratum matters
  to the emit path specifically: `intent_authored_field_claim`'s ROUTINE arm excludes `Mutation`
  and `Subscription` coordinates by design (a mutation root's `@routine` is the walk's own typed
  deferral, never a conflict slot, the view's own comment says), so `intent_resolved_field_claim`
  is structurally empty at every coordinate a mutation-family producer is a relation over. The
  verdicts those producers need are not late; they were never in the claim stratum's charter, and
  each mutation family's availability check has to look for them elsewhere.
* **For the launcher stratum, what remains is plumbing.** Four relation-shaped folds have no home
  in the store yet: operation members, connection synthesis, tenant bindings, and delivery. None
  needs a new rule. Each was already built as a relation in the model and needs a view over
  captured facts (or, where a view cannot state it, a capture-cadence writer), which is the
  cheapest kind of work in this programme.
* **All four folds are on the same footing, delivery included.** Delivery briefly had its own item
  (R666, discarded 2026-08-20, see `roadmap/changelog.md`), which specified the view plus a shadow
  test and a residue record while flipping no production read. That shape made the walk the oracle
  and put six Spec reviews' worth of corrections into a description of the walk's holes, which is
  the strangler pattern this item's strategy replaces. The fold is built here instead, inside the
  slice that consumes it, with the slice's own test as the specification; no `DeliveryShadowTest`,
  no `DeliveryResidue`. The discarded item's design analysis (the seven-arm table, the four
  predicate warnings about which relation each arm joins) survives in git history and is the
  starting point for whoever writes the delivery view, read as analysis rather than as contract.
* **Availability is a per-family measurement, not a standing fact, and slice one's measurement
  found three gaps.** The routine-write family's relation-availability check has been run
  (2026-08-20, against trunk `abaa666`; findings carried in the slice-one section). The four
  legacies R704 left are genuinely in place, and three producer inputs have no relation stating
  them: the seat verdict at the mutation coordinate (the mask above), the chain's ordered hop
  interior (only the terminus is a relation; the walk lives in recursive CTEs inside
  `intent_field_chain_terminus`), and the error channel (its carrier-field detection exists only
  as a CTE inside `intent_carrier_data_field`). The store's own comments acknowledge the first:
  `intent_routine_return_binding` excludes the payload-carrier seat "because the store holds no
  carrier fact yet". So the check is real work with three possible findings per fact: a relation
  exists; a relation exists as a CTE inside another view and gets promoted (the fact model's
  second-reader rule); or a relation is missing and is the slice's first deliverable.

The practical consequence: nothing blocks globally, but the earlier "every half is a sequencing
problem rather than a modelling problem" overstated it and is withdrawn. The launcher folds are
transcription; the mutation families open with a modelling step, and slice one prices it.

## Scope

Four success criteria, and every deliverable is a step toward one of them:

1. `EmitPlan.produce` takes a `StoreHandle` and no `GraphitronSchema`.
2. No emitter reads a classification leaf **and no emitter calls a planner**, with
   `PackageImportDirectionTest` covering the emitters' packages the way it already covers `render`.
   The second clause is not redundant: the tier rule in the opening sentence forbids both, the body
   counts fourteen live sites where an emitter invokes a producer, and a criterion about leaf
   *reads* alone would leave the inversion standing. The emitters' positive dial therefore excludes
   `plan`, which is also what makes the criterion checkable.

   **One carve-out has to be decided before that dial can be written, because the item pulls both
   ways on it.** Four emitters read `plan.GeneratedUnits` constants at five sites today
   (`ErrorRouterClassGenerator`, `ConnectionFetcherClassGenerator` twice,
   `ErrorTypeFetcherClassGenerator`, `ChannelCatchArmEmitter`), and the class as a whole is read at
   27 sites across ten files, the rest of them minting a `GeneratedUnits(outputPackage)` and calling
   it (`TypeFetcherGenerator` and `FetcherRegistrationsEmitter` at six each,
   `ErrorRouterClassGenerator` three, `ErrorMappingsClassGenerator`, `ConditionGlueCall`,
   `GraphitronSchemaClassGenerator` and `MultiTablePolymorphicEmitter` the remainder). The dial
   forbids the package and not the constant, so the wider figure is the one the guard extension
   meets. Those reads are the naming
   regime this item endorses rather than an inversion: one minting locus, read instead of restated,
   which is exactly why slice one moves the error-channel constant's formula *into* `GeneratedUnits`.
   So a dial that excludes `plan` wholesale forbids the very reads the single-mint rule requires.
   Two answers are available and the item does not need to pick one now, only to pick one before the
   guard lands: admit `plan.GeneratedUnits` into the emitters' dial by name (a minting locus is not a
   producer, and the dial is an enumerated allow-list, so naming it costs nothing structurally), or
   move the minting locus below the plan/emitter boundary so both tiers read it as pure data. The
   first keeps one home for the formula and weakens the criterion's one-line statement; the second
   keeps the criterion clean and relocates a class this item's own retired-vocabulary list does not
   otherwise touch. Both answers cover the class whole, so the count sizes the edit rather than
   moving the decision; it is stated here so the guard extension does not discover it.
3. Validation derives from the store: a view where SQL can state the check, a query-then-insert
   where it cannot, the error surface reading their rows either way, with the minted-name
   collision checks as the one stated exception (settled in the validator half).
   `GraphitronSchemaValidator` stops taking a `GraphitronSchema`.
4. The classification walk and the sealed leaf hierarchies are deleted, along with every resolver
   and transcription writer that existed only to feed or shadow them.

### What output identity is, and what it is not

Every half below holds behaviour across its increments: output byte-identical for the emit tiers,
message and location and severity for the validator. State once what that does and does not commit
to, because the two readings differ and only one is this item's.

Output identity is a **refactoring invariant**. It is asserted against checked-in pipeline-tier
expectations, not against a second live derivation, so nothing has to be kept alive to compare
against, no residue list accumulates, and it costs one thing for a reviewer of a 5,000-line
conversion to check: the derivation moved, the behaviour did not. That is why it is the gate.

It is **not** fidelity to the walk, and the difference decides what happens when a conversion turns
up a disagreement. Fidelity is not a goal here (`roadmap/retire-oracle-diff-shadow-tests.md` carries
the argument): a coordinate where the store-derived answer differs from the leaf-derived one is
either a walk bug the conversion fixes, in which case the expectation changes deliberately in the
converting commit with the requirement as its specification and the changed output named in the
commit message, or a conversion mistake, in which case it gets fixed. Reproducing a walk bug to keep
an expectation green is the one outcome this item refuses, at all three halves. Where the store
cannot yet state a verdict the consumer needs, the deliverable is the missing relation (strategy
point 2: the leaf is never wrapped, adapted, or kept as a side channel), never a record of where the
two derivations disagree.

### Planner half: the eight producers, in dependency order

`plan/` holds eight producers, not the six an earlier draft of this section listed. Two were missing
and they sit at opposite ends of the work:

* **Already done, and the example to copy: key projections** (`KeyProjectionCommands`, 2026-08-19).
  Takes no `GraphitronSchema`, reads store-derived `ResolvedKeyProjections`, emits through
  `ProjectedKeyReads` / `ProjectedKeyHost`. Its shape is what every step below repeats: read the
  relation, transform the shape, no lookup and no throw. Read its commit before starting the next
  producer, for the silhouette and not for the read shape: the producer is the target (a pure
  shape transform with no schema), but its read side predates this item's rules and breaks three
  of them. `StoreNodeTables.read` issues per-row follow-up statements (the N+1 the
  one-statement-per-grain section forbids), the readers take a bare `(DSLContext, graphName)`
  pair rather than `StoreHandle` (nothing under `graphitron/src/main` uses `StoreHandle` today;
  only the LSP does), and it composes nothing with `MULTISET`. The composition itself is not
  unproven in this tree, and slice one should not price it as if it were: eight files already read
  the store that way, four in the language server (`DeclarationFacts` alone at ten uses,
  `ClaimFacts`, `InlayFacts`, `DiagnosticFacts`) and four in the MCP (`SchemaQueries`,
  `CatalogQueries`, `CodeQueries`, `DirectivesResource`). `DeclarationFacts` is the exemplar to
  read for the read shape, being the parent-row-plus-child-relations composition at a keyed grain
  that this item's producers want; what is new in slice one is only that a *generator* producer
  reads that way. There is
  also a case fold duplicated across `StoreNodeTables.keyColumns` and
  `ResolvedKeyProjections.projectionOf`, the two-spellings-of-one-resolution defect the fact
  model names; the fix is a view yielding the catalog column's exact spelling at the
  projection's own grain, which also collapses the N+1 into the driving statement's join.
  Bringing this producer's read side up to the rules is a named increment of the planner half,
  after slice one has set the shape.
* **Inside the fetcher family, not beside it: routine writes** (`RoutineWriteCommands`, 134 lines,
  11 sites). Not converted: `produce(GraphitronSchema, String)` still takes the schema, with a
  `produceWithoutSchema` overload beside it, the same transitional pair `LauncherCommands` carries.
  An earlier draft sequenced this producer outside the order, on the ground that it moves with the
  routine-write family's emitter stage, which another item had scoped. That pointer is now dangling:
  the other item (R668) is Done, its stage landed the *emitter* half, and nothing in this item's own
  ordering schedules the producer. The tree settles it, and settles it in this producer's favour:
  `TypeFetcherGenerator` owns one of the two production-path inversion sites through
  `RoutineWriteCommands.produceWithoutSchema`, the renderer is already in `render`, and most of
  its facts are captured (the three that are not are slice one's opening deliverables), which is
  what makes this family slice one rather than an exception to the order. See "Slice one" below.

The six in between. Sites counted under `CommandSeamRatchetTest`'s own rule so they sum to
`PLAN_LEAF_REFERENCES` (3 + 38 + 17 + 48 + 29 + 1, plus routine writes' 11, is 147). This is an
inventory of the work, **not** a conversion order; what
actually constrains the order is the section after it, and the two are different:

1. **Conditions** (`ConditionCommands`, 403 lines, 3 dispatch sites). The smallest surface, and the
   one every other relation references by glue row. That reference is why an earlier draft put it
   first; the next section explains why it does not have to be.
2. **Projections** (`ProjectionCommands`, 731 lines, 38 sites).
3. **Launchers** (`LauncherCommands`, 1,114 lines, 17 sites). The largest producer, and the one whose
   rows the fetcher generator reads to decide between the launcher emission and the legacy builder.
4. **Fetcher edges** (`FetcherEdgeCommands`, 280 lines, 48 sites). The densest dispatch in the
   package by a wide margin: 280 lines carrying just over a third of the plan-side pin, which makes
   it the step where the one-statement-per-grain rule below is most likely to be violated by a
   mechanical read-by-read transcription.
5. **Type units** (`TypeUnitCommands`, 190 lines, 29 sites), the generator families' membership
   loops.
6. **Globals and the schema-level facts** (`EmitPlan` itself, 1 site). `federationLink` and
   `usesOneOf` arrive today as `Bundle` components landed by the builder; they become store reads
   like the rest.

Each step is a complete unit: the relation's rows are identical before and after, which the
pipeline-tier output expectations assert transitively. A direct row comparison against the
leaf-derived rows is a local aid while converting, never a shipped test (see Coverage).

### What actually constrains the order, measured rather than assumed

The chief risk to this item is not its size. It is that a wrong ordering story makes the work
chaotic, each increment blocked on another, the tree parked in a half-converted state nobody can
reason about. So the ordering claim has to be measured, and when it was, the constraint the earlier
drafts leaned on turned out not to exist.

**Command-relation dependencies impose no conversion order at all.** Read `EmitPlan.produce`: of the
seven producers, only `ConditionRelation` is consumed by others, and it feeds exactly three
(`LauncherCommands`, `ProjectionCommands`, `FetcherEdgeCommands`). `TypeUnitCommands` and
`RoutineWriteCommands` take no relation, and `KeyProjectionCommands` already takes store rows only.
More to the point, that dependency is a *parameter*, and it survives conversion untouched:
`ProjectionCommands.produce(schema, conditions, pkg)` becoming `produce(store, conditions, pkg)`
needs nothing whatever from `ConditionCommands`. The item says as much in "Planners share relations,
not queries", where command-referencing-command is explicitly the plan's own foreign keys and not a
shared read. Both statements cannot do work: if the reference survives conversion, it constrains
nothing. It does survive, so it constrains nothing, and "in dependency order" was a phantom. Any
producer may convert whenever its facts are ready.

**Most of the production-path tier inversion is two call sites, and the counting rule matters.** An
emitter calls a producer's `produce*` entry at thirteen sites across six files, which sounds like a
thicket and is not one. Eleven are convenience overloads whose own javadoc says so, five of them in
`generators.schema` ("derives the schema-shape rows through the producer so the rendered body set is
the flagged-row set production uses") and six in `TypeFetcherGenerator`'s test-facing `generate` and
`generateTypeSpec` entries. They retire by pointing their tests at the plan, which is mechanical and
orders nothing. The `produce*` production inversion is one line-pair in `TypeFetcherGenerator`'s
fetchers fold: `LauncherCommands.produceWithoutSchema` and `RoutineWriteCommands.produceWithoutSchema`,
the nesting-reached fallback.

**And that pair dissolves rather than blocking.** `produceWithoutSchema` exists for exactly one
reason, stated in its own javadoc: the fetcher generator reaches a nesting-reached type holding
fields but no schema. A producer reading the store has no such hole, because the store is available
everywhere the walk is not. So the converted plan produces the relation once and the emitter looks up
`rowFor(type, field)`, an API both relations already expose and which `renderRoutineWrite` already
uses for the row it renders. Converting a producer *deletes* its inversion site; the coupling that
looked like the hard ordering constraint is a symptom of the walk, and it goes when the walk does.

**One production-path inversion falls outside that rule and does not dissolve with it.**
`TypeFetcherGenerator.buildTableInterfaceReprojection` calls `LauncherCommands.discriminatedBranches`
mid-emission to mint a `LaunchSource.DiscriminatedTable` payload, and hands it to
`render.DiscriminatedTableFragments`; the fold is reached from `TypeFetcherGenerator`'s
discriminated-interface assembly and again from `MultiTablePolymorphicEmitter`, so it is production
emission, not a test overload. Being neither a `produce*` call nor a schema-taking one, it is
invisible to the census above and to all four ratchet pins, and neither retirement argument reaches
it: there is no nesting-reached hole to close and no test to repoint. What retires it is the branch
list arriving *on a command row* instead of being derived at the emitter, which needs the
discriminated-table reprojection to be part of the fetcher family's command relation. That is inside
this item's declared scope but is not yet named as a deliverable of any increment, and the family
that owns it should name it. It is also the item's own motivating anecdote in miniature: a
`LaunchSource` arm assembled where the emitter stands, because that is reachable and nothing forbids
it.

What is left is one real constraint, and it is per-producer rather than a chain: **a producer
converts when the facts it reads are relations.** That is a question asked once per producer, answered
independently, with no ordering between the answers. Which is what makes the vertical slice below
legitimate rather than wishful.

### Slice one: the routine-write vertical, then stop and reflect

Slice one is not the smallest producer. It is the smallest **complete vertical**: one family carried
through every tier this item touches, so that what the rest of the programme costs is measured
rather than projected. A horizontal first step (convert the cheapest producer, move on) would prove
only the half of the recipe that is already proven by `KeyProjectionCommands`, and would leave the
emitter cutover, the guard and the instruments unexercised until far too late to change course.

The routine-write family is that vertical. An earlier draft called it "unusually ready" on the
strength of a relation-availability check it had not yet run; the check has now been run
(2026-08-20, against trunk `abaa666`), and its findings replace the optimism. The four legacies
R704 left are genuinely in place, the emitter seam is as good as claimed, and three of the
producer's inputs have no relation stating them, so slice one opens with a modelling step, not a
plumbing step. The strategy's own escape hatch (the missing relation is slice one's first
deliverable) applies three times over. In order:

* **Store, first deliverable: promote the chain walk.** `RoutineChain` needs the ordered resolved
  hop sequence with each hop's join shape, and today only the *terminus* is a relation: the walk
  lives in recursive CTEs inside `intent_field_chain_terminus`. That view's own comment states
  the governing rule while asking for a different promotion (it asks the column-scope view's
  missing chain arm to "read this relation rather than grow a second copy of the walk"), so the
  rule is the store's and the promotion below is this slice's. Land a hop relation keyed
  `(graph, type, field, seq)`, one
  row asserting that the chain's nth node is this table, reached from the previous node this
  way, the join basis carried in the closed vocabulary `intent_field_reference_step_hop` already
  uses (`via`, `constraint_name`, `fk_on_from`) so the command's `On` arm is a decode rather
  than a re-derivation, and hop 0's join shape a column rather than a constructor throw.
  `intent_field_chain_terminus` then becomes a selection over it, mirroring the shipped
  step-hop/step-target split, so the recursive term keeps exactly one home. Do not add a second
  walk beside the first; that is the two-spellings-of-one-resolution defect the fact model names.
* **Store, second deliverable: the error channel.** No relation states which coordinate has an
  error channel, its arm, or which field of a carrier is the errors channel (the last exists
  only as the `errors_field` CTE inside `intent_carrier_data_field`; this slice is its second
  reader, so the promotion rule fires). The relation lands at the coordinate's own grain, total
  over `@error`-carrying coordinates, and carries the *inputs* to the channel: the payload class
  the channel routes through and the ordered mapped `@error` types. It must **not** carry
  `mappingsConstantName`: that is a minted generated identifier, and a column holding it makes
  the store a second mint of a naming formula, the exact drift the minted-name exception under
  the validator half exists to prevent. The formula's one home is `GeneratedUnits`, and the mint
  currently sitting in the walk (`FieldBuilder`'s screaming-snake fold) moves there in this
  slice rather than getting copied. The formula is spelled in three places, not one, so the move
  has to collect all three: `FieldBuilder`'s fold mints the name,
  `MappingsConstantNameDedup` applies the hash suffix that disambiguates a collision, and
  `ErrorMappingsClassGenerator` re-derives the grouping and throws when two channels share a
  constant without sharing a mapping list. One home means those arrive together; a slice that
  moves only the fold leaves the suffix pass as a second mint.
* **Store, third deliverable: the seat verdict, as a reduction and not a membership view.** No
  relation states which seat a mutation field's `@routine` occupies, and none can be derived
  from the claim stratum, which masks mutation roots by design. The wrong fix is a
  "routine-write membership" view: that names the producer's question, fails the fact model's
  one-sentence check, and absorbs the classifier's whole cascade (root-head rule, multi-node
  deferral, Connection refusal, hop-0 shape rule, carrier scan, terminus rules) as a procedure
  behind a relation. The right fix is the `intent_resolved_type_binding` shape: a *reduction
  over sibling relations*, most of which exist (the carrier arm is largely
  `intent_carrier_data_field` at `family='ROUTINE'` joined with `intent_carrier_routine_hop`;
  the chain arm reads the new hop relation), with a closed verdict vocabulary whose refusal arms
  carry message, location and severity. **The refusal arms land in the DDL with the relation,
  even though slice one reads only the emitting arms.** That is the slice's validator-tier
  deliverable: the admit and refuse halves of one predicate get one home from the start, instead
  of the admit half in SQL and the refuse half still in `FieldBuilder` with nothing binding
  them. The validator half's later routine-write check then reads rows that already exist. A
  smaller slice one omits *reading* the refusal arms; it does not omit stating them.
* **Command vocabulary: the tenancy acquisition axis, decided once for two families.** The one
  leaf read left in `renderRoutineWrite` is the coordinate's `TenantBinding`, resolved through
  `TenantDslEmitter` into two `CodeBlock` fragments the shell injects into
  `RoutineWriteFetcherRenderer.render`, whose own comment marks the injection as
  classification-side emission a command must not hold, which is the sentence this slice
  revises. Two shapes are refused before the right one. Widening `TenantStrategy` is out:
  that type is the *fan-out* axis, and its javadoc records the measurement that keeps it
  independent of acquisition (fusing the axes makes the fanned batched child unrepresentable or
  mints the cross-product arm). A routine-write-local carrier is also out: the same injected
  fragment feeds `BatchedRowsFragments`, `ReentryRowsFragments` and `ServiceRowsFragments`, so a
  family-local vocabulary gives one axis two spellings inside one programme. The right shape is
  a sealed *acquisition* carrier in `command/` (untenanted, argument-bound carrying its slot
  reads, the inherited-family reads), designed to serve the launcher family and the
  routine-write family alike, with the run-grain fact (is this build multi-tenant at all) riding
  the relation rather than every row, per `CarrierDsl`'s own stated rule. The
  `TenantBinding`-to-arms derivation gets one home in the producer, copying the rule
  `LauncherCommands.tenancyOf` already states for the fan-out axis. Slice one rewires only
  `RoutineWriteFetcherRenderer`, which drops its two `CodeBlock` parameters; the other three
  fragment hosts follow with their own families.
* **Command vocabulary: take the type lift the hop relation pays for.** `RoutineWriteCommand`
  today carries the walk's `RoutineChain` as a component, guarded by two compact-constructor
  throws and read back out through three casts. Rebuilding that carrier out of store rows just
  so the row can keep holding it would preserve the casts and the throws for nothing. With the
  hop relation landed, the row declares the narrowed shape directly: ordered hops at their own
  grain plus the anchor slots as components, the hop-0 invariant held by the store fact rather
  than asserted at construction. `RoutineChain` then retires with the walk instead of surviving
  as a value record the command still depends on.
* **Planner.** `RoutineWriteCommands.produce` takes the `StoreHandle`, the generator's first
  `StoreHandle` use (see "Where a producer's SQL lives"), reads one statement per grain with the
  hops riding `MULTISET`, and lands the statement-count pin in the same commit. It stays small:
  134 lines and 11 dispatch sites today, no command-relation parameter.
* **Emitter.** `renderRoutineWrite` reduces to a coordinate-plus-relation lookup; the
  acquisition arms render in `render/`, beside `BatchedRowsFragments`' existing fork on the
  fan-out axis. One claim from the earlier draft is withdrawn: `TenantDslEmitter` is not a tail
  file this slice retires. Its single counted dispatch site (the `TenantBinding` switch) serves
  roughly forty call sites across `TypeFetcherGenerator`, `MultiTablePolymorphicEmitter` and
  three other emitters, so slice one retires this *family's* read of it, and the file itself
  retires with the fetcher family's cutover.
* **Inversion.** It deletes `RoutineWriteCommands.produceWithoutSchema`, one of the two
  production inversion sites, by threading the plan's one relation into the nesting-reached
  fallback and the test-facing overload; `rowFor` returning empty is the whole behaviour there,
  because a nesting-reached type's children are never mutation roots. The membership predicate
  the overload existed to keep out of the generator moves into the store with the seat verdict.
* **Render.** `RoutineWriteFetcherRenderer` already exists, already renders from the row, and
  its javadoc already claims the endpoint state (takes no schema and no field leaf); the slice
  makes the claim true by removing the two injected fragments.
* **Instruments, with the arithmetic corrected.** The statement-count pin lands here first.
  `PLAN_LEAF_REFERENCES` drops by exactly the producer's 11; the earlier draft also claimed "the
  tenancy site", which was wrong twice over: the `TenantBinding` switch survives for its other
  callers, and the two `case` arms dispatching *to* `renderRoutineWrite` are counted generator
  pins that stay until the field switch itself goes with the fetcher family. The generator-side
  pins are expected to move barely or not at all in slice one, and a reviewer reading them as
  the slice's progress metric is reading the wrong dial: the slice's numeric story is plan-side
  minus 11, one new statement-count pin, and four new relations (see the pickup notes below) under
  the agreement anchor and the naming check. The emitters' positive dial still gets its dry run on
  one file before it is asked to cover a package.

**Five things pickup settled that the inventory above states too lightly.** Recorded here rather
than left to the implementer's session, because each one shapes work the rest of the programme
inherits.

* **The producer's `StoreHandle` is a lifecycle change, not a signature change, and the dev loop
  already shows what shape it takes.** Success criterion 1 reads as a parameter edit. It is not:
  the store is opened and closed entirely inside `FactCapture`'s capture pass, across three arms
  with an in-memory fallback whose rows vanish at close, and the pipeline order is capture,
  validate, plan, so by the time a producer runs the store is gone and the facts survive only as
  the `StoreDetections` value. `runWithDetections`' own javadoc states the invariant this breaks
  ("the store handle never escapes"), as does `CapturedStore`'s ("in the pipeline the store dies
  with the pass, because nothing is meant to read it yet"); both are prose the change has to
  revise. Store ownership therefore hoists out of the capture pass and into the pipeline, which
  opens once and hands the same handle to capture, detection and the plan. Producing the plan
  inside the capture window instead is refused: it would run producers against a schema that has
  not passed validation. Carrying the rows on `StoreDetections` is also refused, as the settled
  rule that a producer's run-scoped SQL lives beside the producer in `plan/`. The dev loop is what
  makes the hoist ordinary rather than novel: `DevMojo` already opens a session store for the whole
  session, refreshes materializations on it, mints the editor's and the MCP's readers off it, and
  runs generator passes inside that window, so the build path converges on a shape the dev path has
  had all along. Two consequences to hold: a second *process* opening the store is refused and
  falls back rather than waiting, so a wider build-side window costs a concurrent opener its warmth
  and never its correctness; and the plan tier now issues SQL on every dev round, which is the wall
  clock the reflection has to state a number for.
* **Promoting the walk costs four relations, not three, and each states one sentence.** The
  recursive term moves cleanly. The chain's *start* does not travel with it: the terminus view
  resolves the last `@routine` application to a FUNCTION-typed table in its own non-recursive
  terms, and its routine arm, a chain with no `@reference` tail, is that node alone, which a
  relation of hops has no row for. Folding the start into the hop relation as a zeroth row with the
  join basis null there would mix two sentences in one grain to save a naming it does not actually
  save, since the terminus would then name the hop relation anyway. So the start lands as its own
  small relation, the hop relation walks from it, and the terminus becomes a two-armed union of
  selections carrying no recursion at all.
* **Each relation states its inline multiplicity when it lands, and the walk's input is registered
  in this slice rather than left a view.** The static metric has a reporting gate in the roadmap
  tool, and the precedent that a stage states the number for the relations it adds rather than
  discovering it in a profile is already set. Two readings from that precedent bind here. The metric
  ranks breadth and never cost, so it is stated and not optimised against; the schema already
  carries a case where it ranked a relation first and the cost did not follow. And the one shape it
  cannot see is the one this slice mints: a recursive walk names its input in both seed and step, so
  a view input is evaluated once per accumulated row, which cost the decode family its whole reader
  and was fixed only by moving that input into the materialization register.

  The chain walk's input is `intent_field_reference_step_hop`, and the measured picture says to
  register it here rather than wait for a later stage to discover it. That relation expands to 20
  instantiations per read, and the two namings the chain terminus already makes of it are 40 of the
  terminus's own 48. Through the terminus and its siblings it is instantiated 48 times in one read
  of `intent_argmapping_projection_defect`, the schema's heaviest relation at 2267, and 30 times in
  `intent_node_id_decode` at 1597. Simulating the registration against the current DDL: the heaviest
  relation falls to 1355, the decode reduction to 1027, and this slice's own terminus from 48 to 10,
  with every other relation in the top eight falling too. That is the single highest-leverage
  registration the schema currently offers, and it is the input this slice's walk needs anyway. It
  is also the same trade the decode family's hop-column registration already made on the same
  recursive shape, under the rule that registration is a claim something reads the relation often,
  made in the increment that adds the reader. This slice adds that reader. Breadth is still not
  cost, so the registration's `reason` states a measured number and not a multiplicity, per the
  register's own doctrine that a row which cannot say why it is stored is not a registration.
* **The neighbourhood this lands in is a cleanup site, not a model to copy.** The materialization
  register, its derived refresh edges, the two structural gates over them and the argument-site
  siblings of the reference-hop pair all reached trunk while this item was in Spec, so the machinery
  slice one uses is now shipped rather than proposed. What arrived with it is a schema left heavier
  in one family than it was found: the argmapping projection defect went from 765 instantiations to
  2267 and its key-column candidate from 259 to 674, both from one new read added underneath them,
  which is how the defect view became the heaviest relation in the schema. None of that is this
  item's to fix, and the registration above happens to take 40 per cent off it as a side effect of
  work slice one wanted regardless. Two things in the neighbourhood are this item's to not make
  worse (a third, generator-side, is the bullet below). The chain terminus is one of two places
  still joining a type binding on a stripped type
  expression instead of on a column, named in a sibling relation's comment as a hazard that is
  survivable only because the terminus drives orders of magnitude fewer rows than the argument
  population does; the rewrite keeps the terminus at its current grain, so that stays true, and the
  expression itself is filed elsewhere. And the reduction the seat verdict copies its shape from
  sits upstream of that same heaviest relation at a leverage of 24, so a seat verdict that widened
  it would pay 24 times over. Neither is a reason to change the plan. Both are reasons to state the
  number when each relation lands, which the bullet above already requires.
* **The generator half of the same landing moved the plan-side pin the wrong way, and the nine
  references it added are a walk inside a planner.** `PLAN_LEAF_REFERENCES` went from 138 to 147,
  deliberately and with a justification recorded on the pin itself, all nine in the projection
  producer, which grew from 558 lines to 731. So the census above is restated: projections at 38
  sites, not 29, and the plan side sums to 147. Slice one's own arithmetic is unaffected, the
  routine-write producer being untouched at 11 sites and 134 lines, but it now lands the pin at 136
  rather than 127, and the reflection's "how far did one vertical move the number" is read against
  the new base. What the nine references actually are matters more than the count. The producer now
  narrows a type to the table-interface leaf, filters its fields to the child-field leaf, and
  recurses through nesting splices to find the participants' spliced subtrees: a tree walk
  performed by a planner, over the schema, which is the one shape this item says a planner must not
  have. Each reference earns its place under the reasoning that put it there, and that reasoning is
  about making a per-family statement explicit rather than implicit, which is a real improvement to
  the code that exists. It is still a census, and a census is a relation. Add it to the projection
  producer's conversion as a named sub-deliverable rather than discovering at that increment that
  the surface grew by a third; nothing about it changes slice one.
* **Two obligations the landing hands this item that the inventory never listed.** A new sealed
  value type carries the result-key alias namespace verdict, and its own javadoc states that the
  value is minted at capture and stamped onto the alias-minting leaves, with the write side and the
  read side both spelling the stamped value rather than re-deriving it. The discipline is exactly
  right and the home is the walk, so the mint needs re-homing when the walk goes, on the same terms
  and for the same reason as this slice's error-mappings constant. Second, that type is now on the
  command tier's borrowed-import allow-list beside the two entries slice one removes, so the
  entries are a live scoreboard rather than a static list and slice one's commit takes off its own
  two without disturbing it. Third and smallest, minted names now have two homes: the generated-unit
  holder this item names as the one home for a minted identifier, and a new reserved-alias holder in
  the command tier for the SELECT-alias namespace. Both are defensible and neither is wrong; what is
  wrong is this item's sentence asserting one home without qualification. The boundary to state, at
  the increment that first touches either, is that generated *type and member* names belong to the
  first and the *alias* namespace to the second, or that one folds into the other.

**Then stop.** Slice one ends at a written reflection, not at the next producer, and the reflection
is a deliverable with the same weight as the code. It answers, with numbers from the slice rather
than estimates: what did one vertical cost in wall clock and in review; how many statements does a
converted producer actually issue and did the grain rule hold; did output stay byte-identical or did
a walk bug surface, and which; per relation landed, was it a reduction over existing relations or a
new derivation, and how much of its cost was the refusal arms rather than the admit arm, because
the remaining families are unusually well covered on some arms and unusually thin on others and a
single aggregate figure would mispredict in both directions; is `rowFor` the right emitter-side
seam or did the cutover want something else. Multiply the answers by
the families remaining and the programme either has a credible shape or it does not.

The reflection may reorder everything after it, and is expected to. Nothing below slice one is a
commitment; the inventory above says what the work is, and slice one's numbers say in what order and
at what granularity to take it. An item this size earns the right to plan its second half only after
its first vertical has been measured.

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

The census owed a re-take (an earlier one totalled 129 against pins of 131, and carrying two totals
for one quantity is the same species of blind spot as the pin corrections below). Re-taken against
trunk `7f2ff35` under the pins' own rule, it totals 131 and reconciles exactly: 69 `instanceof` plus
62 `case`, no residual.

**Five** files carry all 131, 125 of them in the fetcher family: `TypeFetcherGenerator` 83,
`FetcherEmitter` 30, `FetcherRegistrationsEmitter` 12. The tail is `GeneratorUtils` 5 (all on
`GraphitronType`'s result-type arms; one result-Java-type fact on a command row retires them
together) and `TenantDslEmitter` 1. That last count is honest and misleading at once: the one
counted site is the `TenantBinding` switch, and it serves roughly forty call sites across five
emitters, so the file is not a tail to knock off early. The fan-out half of the tenancy dial is
already command-shaped as `TenantStrategy`/`CarrierDsl` for migrated hosts; the acquisition half
becomes command-shaped starting with slice one, and the file retires with the fetcher family when
its last caller moves.

The re-take moved two things, and neither moves the order. `ObjectTypeGenerator` is off the list
entirely: its three sites were a six-leaf `instanceof` chain over the form-carrying arms, and the
render-side form resolution folded them into one read of `CarriesObjectForm`, which the arms opt
into. That is the shape this item wants generalised, a declared capability read instead of a
restated arm list at each consumer, and it landed without this item owning it. The 129-to-131 gap is
therefore not the discriminated interface child's batched half, as the earlier text guessed;
`TypeFetcherGenerator` grew from 78 to 83 while `ObjectTypeGenerator` went to 0. The fetcher family
is heavier than the earlier census said and the tail is one file shorter, which sharpens rather than
changes the ordering argument below.

The other 52 files in the package have no leaf dispatch; most of them are membership already decided
by `TypeUnitCommand` and `GlobalCommand` rows over fixed-text or carrier-driven bodies, and they are
the guard extension's concern rather than a migration's.

So the order: the one true tail first, `GeneratorUtils` (the object generator having folded
already, and `TenantDslEmitter` having turned out not to be a tail: its switch retires last, with
its last caller), because it is an afternoon and retires its sites whole; then the
fetcher family, which is the item's real weight and subsumes what remains of the launchers. "The launcher family is done" was true at the body tier only: the rows methods render
through `RootLauncherRenderer` and its fragments, but `TypeFetcherGenerator` still emits the
`DataFetcher` entry points that wrap them, drains the per-class scatter helpers
(`SplitRowsMethodEmitter`) and the DataLoader registration wrappers (`RowsMethodCall`,
`DataLoaderFetcherEmitter`), and invokes planners mid-emission, which the tier rule forbids. That
host tier is not a separate family;
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
  large case, and the leaf-importing files in `generators.schema` and `generators.util`, plus
  `EntityResolutionBuilder`, `CatalogBuilder` and `PlanCompileGraph` outside the generators tree,
  are the same class. The guard extension in "The closer" and the terminal deletion are what cover
  these files, because they forbid the import; the pins alone never would.

### Validator half: views first, queries-then-inserts for the rest

The validator's three input channels map onto two migration moves, and the pattern for both already
ships in `rewrite/derive`.

* **A check expressible as a relation becomes a view.** Most of the fourteen structural checks and
  most of the per-leaf arms are joins and anti-joins over facts capture already holds, which is a
  detection view in the `intent_authored_claim_conflict` mould: the check lands at its own grain
  and its literals form a closed vocabulary declared where it is read.
* **A check that genuinely cannot be a view runs as a query whose findings are inserted back into
  the fact model as rows.** That is the `StoreDetections` shape `AuthoredClaimConflicts` and
  `ArgmappingProjectionDefects` already have: derivation in SQL plus Java where SQL cannot state
  it, findings landing as rows, `ValidationReport` assembled from rows. "Cannot be a view" is a
  claim to justify per check (a recursion H2 views cannot carry, a reflection probe), not a
  default to reach for.

Reaching the editor and the MCP is not free, and must not be claimed as such. The `diagnostic`
surface is a hand-written `UNION ALL` with one arm per source relation, and
`intent_argmapping_projection_defect` is *not* an arm of it today, so of the two exemplars above
only the claim conflict reaches the LSP squiggle. Each migrated check's commit therefore states
where its rows surface: joining `diagnostic` is part of the migrating commit wherever the defect is
author-facing, and the union's arm set gains a mechanical pin so an unjoined detection is a build
failure rather than a silent editor gap. And since this item retires `rejection_validation_error`,
the arm the editor's build diagnostics ride today, the migrated verdicts' stated permanent home
must exist before that transcription family can go.

**One class of check is the stated exception to success criterion 3, and it is two checks, not
one.** `validateProjectionUnitAddresses` and `validateLauncherMethodNames` (through
`ProjectionCommands.addressCollisions` and `LauncherCommands.methodCollisions`) assert that two
*minted unit names* do not collide after case folding. A store detection for those would put the
naming formula in SQL beside the producer that mints it, one invariant with two mints, which is
the drift shape the single-mint naming regime exists to prevent; and the alternative homes are
closed by this item's own rules (a check over committed command rows reads a record family upward,
and validation-after-planning is a pipeline reorder). So the collision invariant stays at the
mint, as the producer's hard failure, and any author-facing rejection for it derives from the
*inputs* to the formula, a folded-name agreement between captured SDL coordinates, which is a
schema-grain fact needing no minted name. The validator's upward reads dissolve either way: no
plan or emitter import survives in validation.

The classify-time rejections are the same two moves seen from the other end. Today a rule the
schema breaks demotes the coordinate to `Unclassified*` inside the walk, and the validator re-wraps
the carried `Rejection`; store-side, the rule that demoted it becomes a detection over the captured
facts that reports the same error at the same location. The `Rejection` hierarchy's vocabulary (16
leaves plus 9 error sub-seals) is re-expressed as those detections' closed literal vocabularies, the
way `rejection_validation_error.kind` already transcribes it for the editor.

Two properties keep this half honest. Each check migrates one at a time, behaviour held: message,
location and severity survive on the fixture that trips the check, and a check with no fixture gains
one in the migrating commit. And the ordering is already paid for: validation runs downstream of
capture today, so no pipeline change is needed for any of it.

**This half needs a counter of its own, on the item's own argument.** The four ratchet pins measure
`plan/` and `generators/` and, as stated above, do not see the validator at all, which leaves the
half with the least mechanical protection running on 2,024 lines and its `validate*` methods with
nothing to make a stall visible. "A ratchet with no owner is a flat line" applies here more than
anywhere: a validator check is easy to leave for later precisely because no count moves when it is.
Add a validator-side pin in the same `CommandSeamRatchetTest` mould with the first migrated check
(leaf references in `GraphitronSchemaValidator`, or its methods taking a leaf, whichever the scan can
state without ambiguity), never-raise on the same terms, retired when the terminal deletion makes it
unraisable.

### The terminal deliverable: delete the walk

When the plan, the emitters and the validator read the store, the walk's remaining readers are the
tail census above, and each either migrates inside the family that owns it (`EntityResolutionBuilder`
with federation's slice, `CatalogBuilder` and `PlanCompileGraph` with the planner half's last steps)
or retires with the walk outright (`RejectionFacts` and the `rejection_` relations, the `walk_`
family and its `derive/` projections; their DDL comments state that lifetime already). Then the
deletion lands: the sealed classification taxonomy in `rewrite.model`, `GraphitronSchemaBuilder`,
`TypeBuilder`, `FieldBuilder`, `BuildContext` and the walk-only resolvers around them, on the order
of tens of thousands of lines. Deletion is not a big bang; it falls out family by family as each
last reader moves, and the final commits remove what nothing references.

Four obligations are owed before the last cut, named now so nobody discovers them at the end. A
fifth is listed first and is already discharged, kept because it is the shape the other four are
read against:

* **Discharged: the already-migrated detection's domain gate; R743 did it.** This obligation is
  settled. `intent_authored_claim_conflict` is the one detection that had already moved, and
  its accept line was walk-derived: the view inner-joined two membership relations whose rows the
  capture-and-detect pass wrote off the walked model, so deleting the walk would have left them
  unwritable and the view's population would have silently emptied rather than failed. R743
  (`sdl-fact-gatherer-staged-pipeline`, Done, see `roadmap/changelog.md`; its body was deleted at
  the Done transition) removed the coupling instead of re-pointing it:
  the detection is now total over the authored claims, each consumer applies its own population join
  (the build-error surface joins `intent_type_domain`, the editor's diagnostic arm reads the view
  ungated), and both membership relations are gone. Nothing on this item's terminal path stands on
  them any more, so the sequencing constraint that used to sit here is discharged too.
* **The per-relation anchor gate needs a successor before it dissolves.** Coverage below leans on
  `FactCaptureAgreementTest`'s mechanical driver to anchor every new relation, and that class is
  premised on the walk: its arms compare captured rows against the walked model, and its own closing
  javadoc says the tests "retire as consumers migrate off `GraphitronSchema` piece by piece; they pin
  a shadow copy, and a shadow with a reader does not need one". So the gate this item relies on for
  dozens of new relations is dissolved by this item's own terminal deliverable, and its `ORACLE` arm
  loses its subjects with the `walk_` and `rejection_` families. Decide the successor while the
  relations are landing, not at the cut: a registration that says how each relation is pinned when
  there is no model to compare against (its own given-rows test in `graphitron-model`, its
  consumer's behaviour, or the emitted output), on the same no-skip-list terms. This is the same
  re-keying problem as the completeness gates below, one stratum up.

* **Confirm capture is walk-free, which it already essentially is.** The per-concern visitors
  under `no.sikt.graphitron.facts` import nothing of the tree and feed `BuildContext` *upstream*
  of the model, and the store's capture layer touches `rewrite.model` in exactly one place
  (`MacroCapture` importing `ConnectionNaming`, a naming helper). The real write-side coupling is
  the walk-side folds feeding `DeliveryFactRelation` and `OperationMemberRelation`, which are two
  of the four folds this item re-sources anyway. The audit at the last cut is a confirmation pass,
  not a re-sourcing project.
* **The completeness gates re-key before the last leaf reader moves, not with the deletion
  commit.** Output identity is only as strong as the corpus, and the corpus's completeness is
  today enforced by vocabularies this item deletes: `VariantCoverageTest` covers
  `ClassifiedCorpus.coveredLeaves()` against the sealed leaf sets, and `GeneratorCoverageTest`'s
  dispatch partition is closed by the compiler because the vocabulary is sealed. Rows of a command
  relation are closed by nothing, so after the deletion an unhandled shape would simply produce no
  command row, which output identity cannot see and nothing would refuse. The
  corpus-completeness and dispatch-partition obligations therefore re-key onto vocabularies that
  survive (each command relation's declared arm set, each detection's closed verdict vocabulary,
  the emitted-unit census), as a deliverable of the migration rather than of the deletion. The
  remaining verdict-anchored tests (`GraphitronSchemaBuilderTest`'s enum rows, the classification
  traces) recast onto store relations and emitted output or retire with the walk; the
  classified-corpus programme already moves verdict rows into spec-by-example documentation, and
  this item follows its lead rather than inventing a second mechanism.
* **The doc estate is a terminal-step deliverable beside the test estate.** The deletion
  invalidates named exemplars in the principle documents: the leaf-keyed dispatch partition under
  "validator mirrors classifier invariants" in
  `docs/architecture/explanation/development-principles.adoc` (a rewrite under that file's size
  budget, so a displacement decision), `GraphitronSchemaBuilder`'s top comment as the
  orientation-javadoc exemplar, the transitional-walk sentence in the fact-model page,
  `pipeline-overview.adoc`'s transitional classification stage, and `code-generation-triggers`
  with `index.adoc`'s pointer to it. Sweep them in the deletion's commits.

### The closer

Extend `PackageImportDirectionTest` over both packages once they are empty of leaf readers, giving
each the same *positive* dial `render` already has: an enumerated allow-list of what the package
may import, everything else a finding, the closure pinned by reflection the way
`borrowDialComponentClosureIsPinned` does it. `render` keeps its restriction to commands plus the
named pure-data refs; `plan` gets store reads (`StoreHandle` and the generated store tables,
deliberately not bare `org.jooq.DSLContext`; see "Where a producer's SQL lives") plus
the command vocabulary it produces. A deny-list of the seven leaf hierarchies is explicitly the
wrong shape: an eighth hierarchy, a relocated leaf, or a leaf taken as a parameter and never
dispatched on all pass it, which is the same blindness the pins have. The positive dial also makes
the guard permanent by construction: when the terminal deletion removes the hierarchies, nothing
about the dial changes. The ratchet pins retire in the same commit that extends the guard over the
package each pin measures: a zeroed pin the guard makes unraisable is a second mechanism for one
invariant, and two mechanisms for one invariant drift apart.

Prove each extended dial non-vacuous at the gate, the way the MCP's boundary guard was proven at
its Done review: plant a forbidden leaf reference, watch the build fail, remove it. A guard that
has never fired is a claim, not a gate, and the precedent for what an unproven needle misses is
live: the MCP guard shipped scanning one package prefix of the five the generator publishes
(filed as `roadmap/mcp-boundary-guard-generator-package-coverage.md`), which naming the forbidden
hierarchies and packages explicitly, then probing them, avoids.

### Sequencing between the halves: planner leads, per family

Settled: the planner half leads per family, not globally. A family's command relation becomes
store-derived first, then its emitter moves onto that row. That keeps both halves advancing on the
same family instead of two fronts crossing, and every emitter cutover verifies against a plan that
is not simultaneously changing its own inputs. Where a family has no command relation yet, minting
it from the leaves it covers is a legitimate intermediate step (the plan-side pin rises, as its
comment anticipates), but the re-sourcing follows within the same family's arc rather than being
deferred to a global second pass; a minted-from-leaves relation is transitional state, not a
resting place.

**The family is therefore the unit of work throughout, and the earlier drafts' three competing
decompositions collapse into it.** The planner half used to be ordered by relation, the emitter half
by file, and this section by family, with no mapping between them, which is what made "what is the
first commit" unanswerable. The inventory of producers stays an inventory; the schedule is families,
and a family is done when its facts are relations, its producer reads them, its emitters render its
rows, and its dispatch sites are gone. Conditions and projections are families whose emitter side
already sits in `render` (`ConditionGlueRenderer`, `ProjectionUnitRenderer`), so converting their
producers completes them outright. Launchers, fetcher edges, type units and routine writes all feed
the one large fetcher family, which is why that family is the item's real weight and why slice one
takes the corner of it that detaches cleanly.

The validator half is independent of the emit tiers and advances beside them, check by check; no
emit-family increment waits on it and it waits on none of them. The terminal deletion comes last by
construction, since it is defined as what happens when nothing reads the walk.

## What the store must provide

Do not model a relation at the plan's convenience; that is how a store accretes consumer-shaped
columns. Each fact lands at its own grain and every other consumer inherits it, which is the loop the
language server's migration (R638, Done, see `roadmap/changelog.md`) ran four times and wrote down as
doctrine. The missing folds
are enumerated under "The facts to plan against are available" above, and the validator half's
detections land under the same rule: a check's relation states the defect at the defect's grain,
never a validator-shaped payload.

The two halves carry opposite instructions about store views (the validator half adds them, the
planner half is banned from asking for them), and the discriminator is worth stating once so the
item reads as one architecture. A defect is a fact about the *schema*: permanent, many-consumer,
so it lives in the store as a detection relation. A command row is a fact about *this run*:
one-consumer, run-scoped, so the store never serves it, and `MULTISET` composition stays in the
producer's `SELECT`. That line, not the identity of the consumer, is what decides where a
derivation lives.

## Risks

* **This is the largest item on the roadmap by surface.** The planner half alone is 6,468 lines of
  plan and command code and 147 dispatch sites, over a taxonomy of 72 leaves across the seven
  hierarchies (`getPermittedSubclasses()` closure, trunk `7f2ff35`); the emitter half adds the
  generators' package on top; the validator half is another 2,000 lines of checks plus the
  `Rejection` hierarchy's vocabulary; and the terminal deletion removes a walk whose footprint is on
  the order of 50,000 lines. It is scoped as one item because it has one architecture and one end state, not
  because it is small. Expect it to run as long as the LSP migration has, or longer.
* **Classify-time rejections are user-facing contract.** A schema author's error text, location and
  severity must survive each check's migration; the rejection fixtures pin them, and a check whose
  detection fires on a different population than its walk arm did is a behaviour change to decide
  deliberately (the requirement is the specification), never to ship unnoticed.
* **The per-coordinate verdict population is the schedule.** Everything else is plumbing. Where a
  classification view turns out not to state what a producer needs, convert the producers whose
  verdicts are clean and file the missing relation as its own deliverable; do not widen the item,
  and do not carry the gap as a named residue. An earlier draft of this bullet prescribed exactly
  that residue, copying the demand stratum, which is the pattern the delivery item was discarded
  over and which "What output identity is" above rules out. A gap between what the store states and
  what a consumer needs is a missing fact, and the fact is the deliverable.
* **Command rows are structured, not flat.** `LauncherCommand` carries nested sealed payloads
  (`LaunchSource`, `GlueCall`, `Invocation`, `TenantStrategy`, `ResultShape`). Relationally that is a
  row plus child relations, and choosing those grains badly is how the command vocabulary ends up
  transcribed into SQL rather than modeled. Some of it is deliberately not store-bound: the plan
  already refuses to hold javapoet types, and that boundary stays.
* **The closure invariant is the safety net and must not be weakened.** A converted producer that
  commits a row no renderer emits, or drops one a renderer needs, fails the fold. Keep that gate loud
  during the migration rather than relaxing it per increment.
* **The mechanical conversion is the N+1.** Each producer is today a per-coordinate dispatch loop,
  so transcribing it read by read yields a store query per coordinate that passes every
  output-identity gate while multiplying round trips by the corpus. "One statement per grain"
  above is the rule and the statement-count pin is the only instrument that sees the defect; the
  reviewer of each planner-half increment checks the count the way they check output identity.
* **The accessor census reads as an implementation plan.** The path of least resistance for whoever
  converts producer number two is to extract producer number one's store reads into a shared
  helper, and each extraction after that looks more natural than the last. "Planners share
  relations, not queries" above is the rule; the reviewer of every planner-half increment should
  check for it, because no ratchet counts this.
* **The two halves can deadlock on each other if sequenced globally.** Converting every producer
  before any emitter moves leaves the emitters reading leaves for the whole programme; converting all
  emitters first means minting command relations from leaves that the planner half will then re-source.
  Per-family sequencing (settled above) is the way out; each increment's reviewer should hold the
  work to it.
* **A wrong ordering story is the chief risk to maintainability, above size.** This is the risk the
  item is shaped around, so it is stated as a risk and not only as a plan. A programme of this
  length is maintainable only while the tree is comprehensible at every intermediate commit, and the
  thing that destroys that is not the line count but a half-converted state whose rules nobody can
  state: some producers reading the store, some the walk, some both, and no principle saying which
  should be which. The mitigations are structural rather than diligence-based, because diligence is
  what the leaf zoo already exhausted.
  * The unit is the family, and a family is not left half-converted across increments. A producer
    converted while its emitters still dispatch on leaves is the resting state the item refuses.
  * The false ordering constraint is retired explicitly, above, with the measurement that retired
    it. Ordering lore that nobody can re-derive is how a plan becomes chaotic; the constraint graph
    is written down so a later session can check it rather than inherit it.
  * The signatures carry the state. A producer either takes a `GraphitronSchema` or it does not, so
    "how far has this got" is answered by reading `EmitPlan.produce`, never by archaeology. Keeping
    that legible is worth more than any prose status, and it is why transitional
    `produceWithoutSchema` pairs are deleted by their conversion rather than accumulating.
  * Slice one exists to test this risk before the programme commits to a shape, and its reflection
    is allowed to reorder everything after it.

## Out of scope

* **New validation rules, severities, or message improvements.** The validator half moves each
  existing check's source of truth and holds its behaviour; feature-level validator and diagnostics
  items stay their own work, landing against the store once their check has moved.
* **Reordering the pipeline.** The plan and the validator both run after capture already, so every
  migrated read is order-eligible today. An earlier plan proposed reordering capture ahead of the
  walk plus a store-reading classifier; that was scaffolding for a strangler-style drain of the
  walk's *mint*, and it stays dropped: the walk keeps its stage until nothing reads its output, and
  then the stage disappears with it.
* **Recording committed rows into the store.** A run-record stratum (the plan's committed command
  rows, the render's emitted-unit census) written *downward* at post-plan cadence on the `javac_`
  model is adjacent work, filed as `roadmap/run-record-families-for-commands-and-emitted-units.md`.
  This item's bans are about read direction and are not an argument against it: the store must not
  serve plan-shaped views as planner *inputs*, and no tier may read a record family back upward,
  but a record the plan writes after committing is neither. The per-family conversions here are
  what make that record cheap (a producer that derives its rows by SQL writes them back in one more
  statement), and it stays out of scope so the seam closes without growing a second deliverable.

## Relationship to other items

* R666 (`delivery-verdict-derives-from-the-store`, discarded 2026-08-20, see
  `roadmap/changelog.md`) was the declared dependency: it derived the delivery verdict as three
  views plus a shadow test and a residue record while flipping no production read. It was discarded
  in this item's favour, resolving the open question its own scope section recorded: the delivery
  fold is built here, inside the slice that consumes it, on the same footing as operation members,
  connection synthesis and tenant bindings, with the consuming slice's own test as the
  specification. Its design analysis, in particular which relation each delivery arm joins,
  survives in git history as the starting point for whoever writes that view.
* `roadmap/retire-oracle-diff-shadow-tests.md` (R740) is the doctrine this item's verification
  stance applies, stated in "What output identity is, and what it is not" above: no oracle-diff
  scaffolding is built here.
* **Two items still share the `walk_` and `rejection_` families, and the boundaries are worth
  stating** so neither cuts the other's relation. R743 already took the membership half: it deleted
  the two claim-domain relations with the gate that read them, and the `derive/` projection that
  wrote them went in the same change, so the pairing that used to hold the family's grains together
  is gone and `walk_type_backing_class` is the family's last resident. R740 drains that one, whose
  only reader is the shadow test it retires, and takes `TypeBackingClasses`, `TypeBackingClassRows`,
  `DemandResidue` and the `ClaimDomain` value the demand shadow still diffs against with it. So no
  `walk_`-shaped projection is left for this item's terminal deletion; what remains here is
  `SchemaReachability` and the walk itself, plus `RejectionFacts` and the `rejection_` relations once
  the migrated verdicts have a stated permanent home in the `diagnostic` union. Whichever order they
  land in, each family leaves with its last reader, and neither item deletes a relation the other
  still writes.
* R638 (`lsp-reads-the-fact-store`, Done, see `roadmap/changelog.md`; its 4,795-line body was
  deleted at the Done transition in `a5b667b` and is readable there) is the shape to copy: one item,
  many increments, each arm landing on its own commit with what it settled written down. It also
  owns `StoreHandle`, which the
  planner half's producers take. Not a declared dependency: the earlier edge existed because both
  restructured `buildOutput`, and this item no longer touches the pipeline order. Since this item
  was filed the LSP retired its last generator-side read: the routine call surface became
  `intent_field_routine_method`, and `Workspace` stopped holding a build snapshot at all. Its two
  read-shape corrections (one statement per capability; a projection composes with `MULTISET`, a
  view never embeds payloads) are the direct inputs to the one-statement-per-grain section above.
  One more of its gate lessons travels, to both re-keying obligations above: it *declined* a
  shadow-parity gate and built `graphitron-lsp`'s `TriggerDispatchMatrixTest` instead, a declared
  partition of sealed leaves against surfaces into answered / declared-no-answer / unimplemented,
  drawing its universe from `getPermittedSubclasses()` so a new arm fails the build until every
  surface says what it does with it. That is the shape a completeness gate takes when the answer is
  a declaration rather than a comparison, which is what both re-keyings need.
* R642 (`catalog-facts-readers-move-to-the-store`, Done, see `roadmap/changelog.md`) is the
  finished store-client precedent on the other flank: `graphitron-mcp` answers every tool from the
  store, and `StoreClientBoundaryTest` forbids the classification taxonomies by name. Beyond the
  measurements the read-shape section cites, two gate lessons travel: the boundary guard was proven
  non-vacuous at the Done review by planting a generator reference and watching the build fail,
  which "The closer" copies; and its needle still covered one generator package of five, filed as
  `roadmap/mcp-boundary-guard-generator-package-coverage.md`, which is why the extended dials name
  the hierarchies and packages explicitly.
* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333) is the data-model design this
  item executes against: the front-half fact vocabulary and the back-half method graph. This item
  now carries the dissolution itself, walk deletion included, so it is the consumption side grown
  to the whole job; it still must not redesign facts, and where a leaf carries a verdict no
  relation states yet, R333's fact vocabulary is the reference for what the normalized relation
  should say. Re-scope R333 at its pickup against what this item has landed; the method-graph half
  of its charter is untouched here.
* The `facts-and-commands` programme (Done, see `roadmap/changelog.md`) built the
  `command` / `plan` / `render` triangle, `EmitPlan`, the command relations and these ratchets. This
  item is that programme's completion condition, not a re-run of it. Its slice logs are the
  reference for how a family migrates and what holding output identical costs.
* R668 (`nodeid-key-projection-on-routine-params`, Done, see `roadmap/changelog.md`) carried the
  routine-write family's migration as a stage, because a feature there needed a carrier and the leaf
  was the wrong one. That stage is the worked example the emitter half generalises from. It covered
  the emitter half only: `RoutineWriteCommands` still takes a `GraphitronSchema` and still carries 11
  dispatch sites, so this item inherits a proven recipe but not one fewer family, and the producer is
  sequenced with the fetcher family's cutover (see the planner half). Landing first is still the win
  it was: this item lifts a proven recipe instead of inventing one.
* **The read-side twin of that stage is this item's, handed over at its author's request.** R704
  (`routine-composition-surface-from-facts`, Done, see `roadmap/changelog.md`) planned re-sourcing
  `LauncherCommands.routineRow` off facts as its own plan-tier pilot and then declined to keep it,
  on the ground that it is this item's launcher step seen from one family. It is a good first
  family for that step: the render half already goes through the command layer, the command row is
  about fifteen lines, and `LauncherRelationClosureTest` plus `CommandSeamRatchetTest`'s
  `PLAN_LEAF_REFERENCES` counter are a live oracle for it. R704 left the facts it would join
  already captured and derived (the routine catalog facts, the chain terminus, the routine return
  binding, and the two name-match keying relations). Slice one's availability check confirmed all
  four legacies in place and found the three gaps beside them; the slice-one section carries the
  findings. R668's stage 5 asked to land after this step
  rather than beside it, and now that R668 has shipped the constraint is this item's alone.
* `roadmap/list-ordering-invariant-enforcement.md` (R677) plans to enforce the never-unsorted-list
  invariant off the launcher relation's ordering slot, and lands after this item. Two constraints
  travel to the launcher step: the `ResultShape` ordering slot is load-bearing for that enforcement,
  so the launcher increment holds exactly the column a later rule keys on, and the re-sourced
  producer must not quietly change which coordinates take a launcher row at all, because that
  population *is* that item's blind spot (the multitable polymorphic root takes no launcher row,
  which slot-keyed enforcement cannot see; the cross-tier absence question belongs to the
  run-record item above, not to this one).
* The former decompose-`TypeFetcherGenerator` item (R7, see `roadmap/changelog.md`) asked how to
  break up that file and offered decomposing along the field taxonomy as its leading option. It was
  discarded in this item's favour: the file does not get decomposed along the leaves, it empties
  into `render` as the families migrate.

## Retired vocabulary

Provisional; the Done-gate sweep greps for these, and the list grows as increments land.

* `EmitPlan.produce`'s `GraphitronSchema` parameter, and the `Bundle` components it threads
  (`federationLink`, `usesOneOf`).
* With slice one: `RoutineWriteCommands.produceWithoutSchema`, the two tenancy `CodeBlock`
  parameters on `RoutineWriteFetcherRenderer.render`, and `RoutineChain` as a
  `RoutineWriteCommand` component (the type itself retires with the walk).
* Whichever post-walk folds lose their last reader as their relation moves store-side:
  `OperationMemberRelation`, `ConnectionSynthesisRelation`, `TenantBindingIndex`,
  `DeliveryFactRelation`, `NestingReach`, `JoinedTableReprojection`. Each retires only when the plan
  was its last consumer; name them individually as they go rather than as a block.
* `TypeFetcherGenerator.IMPLEMENTED_LEAVES` and the leaf-keyed coverage vocabulary around it, once
  membership is a command relation's rows rather than a set of leaf classes.
* The `CommandSeamRatchetTest` pins, each retired in the same commit that extends the structural
  guard over the package it measures (settled in "The closer" above).
* `GraphitronSchemaValidator`'s `GraphitronSchema` parameter and its per-leaf arms, as the checks
  move; `DeliveryFactPinTest`, when the delivery fold's consumer flips.
* `NodeTypeShadowTest` and `TypeBackingShadowTest`, at the terminal step. Both bind a Java spelling
  against a store relation (`NodeDeclaration#isNodeType` against `intent_node_type`;
  `RecordBindingResolver` against the backing derivation), so the deletion removes one side and
  leaves nothing to bind; `NodeTypeShadowTest`'s javadoc already states that as its own removal
  condition. `roadmap/retire-oracle-diff-shadow-tests.md` renames them meanwhile, which changes
  nothing here: whichever reaches them first, they end at the deletion.
* At the terminal step, the walk and its taxonomy wholesale: `GraphitronSchemaBuilder`,
  `TypeBuilder`, `FieldBuilder`, `BuildContext`, the sealed classification hierarchies
  (`GraphitronType`, `GraphitronField` and everything under them), and the walk-transcription
  writers (`RejectionFacts` with the `rejection_` relations, the `walk_` family and its `derive/`
  projections). `Rejection` and its error sub-seals are deliberately *not* on this line: they are
  the consumer-facing verdict axis, not walk scaffolding (a store detection with no leaf anywhere
  in its derivation decodes them today, the editor's `lsp_code` is sourced from their `lspCode()`,
  and a doc-coverage gate pins their permits), so they either survive below the boundary as the
  decode target or hand the code-declaration and doc-coverage duties to a named successor, a fork
  settled when the last classify-time rejection migrates rather than silently by the deletion.
  Enumerate the survivors, not the deletions, when the sweep runs: anything in `rewrite.model`
  still referenced is a value record that moved below the boundary, not a leaf that escaped.

## Coverage

* **Output identity, per planner increment.** A converted producer leaves the run's emitted output
  byte-identical over the whole classified corpus, which the pipeline-tier expectations assert
  directly. Comparing the converted relation's rows against the leaf-derived rows is a debugging
  aid while both derivations exist, never a shipped test: per
  `roadmap/retire-oracle-diff-shadow-tests.md`, a difference from the walk is either a walk bug
  the conversion fixes (then the expectation changes deliberately, with the requirement as its
  specification) or a conversion mistake, and neither earns a residue record or a standing shadow
  test.
* **Output identity, per emitter family.** The emitter half changes no generated source, so the
  assertion is that it changes none: the family's existing pipeline-tier expectations hold verbatim
  across the cutover.
* **Error parity, per validator check.** Each migrated check keeps its message, location and
  severity on the fixture that trips it, and a check with no fixture gains one in the migrating
  commit.
* **The compile and execution tiers are the real gate.** `graphitron-sakila-example` compiles the
  emitted sources and runs them against PostgreSQL, so a command row that changed shows up as
  behaviour, not just as a diff. `GeneratorDeterminismTest` and `IdempotentWriterTest` cover ordering.
* **The fold's closure invariant** stays as-is and is the per-increment backstop.
* **A statement-count pin per converted producer**, on the `DeclarationHoverStatementCountTest`
  model: an execute-listener count at producer grain, asserting the read count is a function of the
  producer's arms rather than of the corpus, landing in the same commit as the conversion. Output
  identity cannot see an N+1; this is the gate that can.
* **The registered agreement anchor** for every new relation, through `FactCaptureAgreementTest`'s
  mechanical driver, which has no skip list, so a relation added for this item cannot arrive
  unchecked. Two constraints on how a relation registers, both from the terminal deletion: an
  `ORACLE` registration is unavailable to anything landing here, that arm being for relations an
  oracle writer owns and this item deleting the oracle; and a `DERIVED` registration must name an
  anchor that survives the walk, which in practice is the relation's own given-rows test in
  `graphitron-model` beside its DDL. The registry's own successor is the pre-deletion obligation
  above.
* **The naming check, per new relation.** Each new fact or detection relation's commit states in
  one sentence what a single row asserts, without naming a consumer, a generator pass, or an
  existing class (`docs/architecture/explanation/fact-model.adoc`'s check). All four absorbed
  folds are named today for the plan's question, delivery most clearly, and the page predicts what
  the check finds in that situation: several facts where the question suggested one. Absorbing
  R666 removed the external design pass those relations would have had; this obligation, applied
  where the DDL lands, is its replacement.
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

Updated 2026-08-19, at the owner's direction, because the survey's trajectory has completed: the MCP
migration reached Done (R642) and the LSP retired its last generator-side read, so "the drain is
working from the consumer end" is no longer a forecast, and the emit path is the last unmigrated
consumer of size. The same pass re-measured the ratchet pins (all four had risen), folded the
finished migrations' read-shape lessons into the one-statement-per-grain section and its
statement-count pin, adopted the guard non-vacuity check from R642's Done gate, and repointed the
relations-not-queries rule at the doctrine item that has since been filed for it.

Updated 2026-08-20, at the owner's direction, restating the goal and the strategy after the
delivery item's discard. The migration is systematic and component-at-a-time (the LSP, then the
MCP, now the generator), not a strangler, so R666 was discarded and its fold absorbed on the same
footing as the other three; the validator half entered scope, since dissolving the zoo means its
last reader moves too, with most checks becoming views in the fact model and the rest
queries-then-inserts in the `StoreDetections` shape; and the walk's deletion became this item's
terminal deliverable rather than a state some other item inherits. The same pass re-measured the
ratchet pins, censused the leaf model's full blast radius (nine consuming packages outside the
walk; `command` and `render` already below the boundary), and replaced the row-identity coverage
obligation with output identity per R740's doctrine.

Reviewed and revised 2026-08-20 by a different session at the owner's direction, which then took
authorship, so the Spec review still owes this body an independent reader. Three corrections were
factual. The problem statement claimed in three places that the store had never produced a generated
file, which the tree contradicts: `KeyProjectionCommands` converted on 2026-08-19 (`2ef0b57`) and
its rows are emitted, so the claim became the item's worked example instead of its premise. The
planner half enumerated six producers where `plan/` holds eight, missing that converted one and
`RoutineWriteCommands`, which still takes a schema; both now appear in the dependency order as step
zero and step nine. And the emitter census's total (129) had fallen two behind the re-measured pins
(131), so the section said which sites moved and owed a re-take; the pass below discharged it.

Three more came from the shadow-test doctrine the delivery item's discard settled. "What output
identity is, and what it is not" states once, for all three halves, that behaviour is held as a
refactoring invariant against checked-in expectations and never as fidelity to the walk, because two
of the three halves previously stated behaviour parity with no provision for a walk bug the
conversion fixes while the planner half had one. A Risks bullet that prescribed carrying named
residues, copying the demand stratum, is replaced: a gap between what the store states and what a
consumer needs is a missing fact, and the fact is the deliverable. And two pre-deletion obligations
were added, taking the list from three to five: `intent_authored_claim_conflict`'s domain gate is
still walk-derived, making it the one already-migrated user-facing surface the deletion would move,
so it is repointed first; and `FactCaptureAgreementTest`, which Coverage leans on to anchor every
new relation, is itself premised on the walk by its own closing javadoc, so its successor is decided
while the relations land rather than at the cut. The validator half gained the ratchet the item's own
"a ratchet with no owner is a flat line" argument implies it needs, and three citations to the
language server's item were repointed at R638 in the changelog, its body having been deleted at its
Done transition.

A principles pass the previous day sharpened the rewrite in seven places: the minted-name collision
checks became the stated exception to success criterion 3 (a store detection there would give one
invariant two mints); the `diagnostic` union's growth story was made explicit, since joining it is
a per-check DDL edit rather than free and the item retires the arm the editor rides today; the
`Rejection` axis was pulled out of the terminal deletion line, being the consumer-facing verdict
vocabulary rather than walk scaffolding; the extended guards became positive dials rather than a
deny-list of hierarchies; the completeness gates' re-keying onto surviving vocabularies became a
migration deliverable, because output identity is only as strong as the corpus and the gates that
close the corpus are keyed on vocabularies this item deletes; the fact-model naming check became a
per-relation coverage obligation, replacing the external design pass the discard removed; and the
capture audit was corrected to a confirmation pass, capture being already essentially walk-free.

Re-measured against trunk `7f2ff35` before pickup, by a session that had not previously touched this
body. Every figure in the item is now read off that commit, and the pass is worth recording because
the drift arrived in a single day: R743's landing (`7c6d938`) and the commits around it moved most of
these numbers after the 2026-08-20 rewrite measured them, which is the item's own "a ratchet with no
owner is a flat line" argument showing up as measurement rot rather than as a stalled count.

Two corrections were substantive rather than arithmetic. The **emitter census's owed re-take is
discharged**: it reconciles exactly against the pins at 131 across *five* files, not six, because
`ObjectTypeGenerator`'s three sites folded into one `CarriesObjectForm` read while
`TypeFetcherGenerator` grew from 78 to 83. The earlier text guessed the 129-to-131 gap was the
discriminated interface child's batched half; it was not, and the tail-family ordering shortened from
three files to two. And the **leaf-import census no longer names a `walk_`-shaped projection**, which
had it contradicting this item's own "Relationship to other items" boundary: `derive` holds one
leaf-importing writer (`DemandResidue`) and `diagnostics` holds none, `RejectionFacts` transcribing
the `Rejection` axis rather than importing a leaf.

Three claims were corrected as false rather than stale. No plan producer reaches jOOQ directly (the
package imports nothing from `org.jooq`; catalog facts arrive as `TableRef` / `TableExpr` value
records, which is why `StoreNodeTables` was the enabling relation for the converted producer). The
per-producer dispatch counts in the dependency order did not sum to the plan-side pin under any
rule; they are re-counted under `CommandSeamRatchetTest`'s own regex so they now sum to 138, which
moved `FetcherEdgeCommands` from 23 sites to 48 and made it, not `TypeUnitCommands`, the package's
densest dispatch. And the taxonomy is 72 leaves across the seven hierarchies by
`getPermittedSubclasses()` closure, where the Risks bullet said 53.

Nothing in the architecture, the strategy, the four success criteria, or the sequencing changed. The
Spec review this body owes an independent reader is still owed, and this pass does not discharge it.

Measured again 2026-08-20, at the owner's direction, by the session planning slice one's pickup,
and this pass replaced slice one's optimism with findings. The relation-availability check the
slice was to open with has been run (against trunk `abaa666`, with a principles-architect pass on
the resulting design) and does not come up clean: three producer inputs have no relation (the seat
verdict, which the claim stratum masks at mutation roots by design; the chain's ordered hop
interior, which lives in recursive CTEs inside the terminus view; the error channel, whose
carrier-field detection is a CTE with this slice as its second reader), and the DDL's own comments
acknowledge the first. The slice-one section was rewritten around the three store deliverables,
the principles pass shaping each: the seat verdict lands as a reduction over sibling relations
with its refusal arms in the DDL from the start, not as a membership view with the classifier's
cascade behind it; the hop relation lands as a promotion of the terminus view's own CTEs, never a
second walk; the error-channel relation carries the naming formula's inputs and never the minted
constant, whose one home moves to `GeneratedUnits` in the same slice. The same pass settled two
structural forks the first conversion had left open, each at two occurrences before it got
expensive: a producer's run-scoped SQL lives in `plan/` beside the producer, with `StoreHandle`
and not bare `DSLContext` in the dial ("Where a producer's SQL lives" is new); and the tenancy
acquisition axis becomes one sealed command carrier serving the launcher and routine-write
families alike, rather than a widened `TenantStrategy` (the fan-out axis, kept independent on its
own recorded measurement) or a family-local mint (one axis, two spellings). Four factual claims
were corrected: the key-projection exemplar's read side is itself N+1-shaped and predates the
rules, so it is copied for its silhouette only and a named increment brings it up to them;
`TenantDslEmitter` is not a tail file, its one counted switch serving roughly forty call sites, so
it retires with the fetcher family; slice one's pin arithmetic dropped the phantom "plus the
tenancy site", the generator-side pins being expected to barely move in the slice; and "what
remains is plumbing, not modelling" was withdrawn in favour of a per-family availability
measurement. The reflection gained the question whose answer actually generalizes to the remaining
families: per relation, reduction versus new derivation, and admit-arm versus refusal-arm cost.
Slice one also gained its missing validator tier, as the verdict relation's refusal arms. Nothing
in the architecture, the strategy, or the four success criteria changed, and the Spec review this
body owes an independent reader is still owed.

Spec review 2026-08-20 by an independent session, against trunk `479515c`. Every figure in the
body was re-derived rather than read: all four ratchet pins, both censuses reproduced under
`CommandSeamRatchetTest`'s own regex (the emitter side at 83/30/12/5/1 across five files summing
to 131 = 69 + 62, the plan side at 3/29/17/48/29/1/11 summing to 138), the package line counts,
the leaf-import census file for file, the 74 `validate*` methods, the 37 `IMPLEMENTED_LEAVES`
entries, `TenantDslEmitter`'s 39 call sites across five emitters, and every quoted DDL comment
verbatim. They hold. So does slice one's read of the tree: `renderRoutineWrite` really does reduce
to `rowFor` plus one `TenantDslEmitter.resolve`, `ChainReread` really carries exactly the two
compact-constructor throws that the hop relation would retire and the renderer really reads it
back through three `(JoinStep.Hop)` casts, `intent_name_matched_key_pair` really has no
`graph_name`, `intent_argmapping_projection_defect` really is not an arm of `diagnostic`, and the
case-insensitive column fold really is duplicated verbatim between `StoreNodeTables.keyColumns`
and `ResolvedKeyProjections.projectionOf`.

Two claims did not survive the check, and both are recorded above rather than in a review note
because they change what an increment has to do. The emitter-to-planner census counts `produce*`
calls, which is a narrower rule than success criterion 2's "no emitter calls a planner" prices:
`LauncherCommands.discriminatedBranches` is a fourteenth site, on the production path through two
callers, and neither the nesting-reached argument nor the repoint-the-tests argument retires it, so
the family that owns it has to name the command row that does. And the emitters' positive dial
cannot simply exclude `plan`, because four emitters read `plan.GeneratedUnits` constants at five
sites and slice one's own third deliverable moves a naming formula *into* that class; the fork is
stated under criterion 2 for the guard extension to settle. Three smaller corrections: the claim
that no `MULTISET` store read exists in the tree was false in a way that mis-priced slice one
(eight files compose that way already, and `DeclarationFacts` is the exemplar), the
`mappingsConstantName` mint is spelled in three places rather than one, and R743's item path was
dangling after its Done-gate body deletion. Nothing in the architecture, the strategy, the four
success criteria, or slice one's shape changed. This pass leaves the item in Spec and its author is
now this reviewer, so the next Spec gate needs a third session.

Spec gate 2026-08-20, signed off by that third session against trunk `3437ef8`. The factual layer was
re-derived rather than read across again, and it holds: all four ratchet pins at their pinned values,
both censuses reproduced under `CommandSeamRatchetTest`'s own regex (emitters 83/30/12/5/1 across
five files summing to 131 = 69 + 62; plan 3/29/17/48/29/1/11 summing to 138), the per-producer line
counts, the leaf-import census reconciling package for package (six in `generators.schema`, four in
`generators.util`, `EntityResolutionBuilder`, `CatalogBuilder`, `PlanCompileGraph`, `DemandResidue`,
with `diagnostics`, `command`, `render` and the three external modules clear), the thirteen `produce*`
inversion sites across six files plus `discriminatedBranches` as the fourteenth, `plan/` importing
nothing from `org.jooq` and using no `StoreHandle`, `intent_name_matched_key_pair` carrying no
`graph_name`, `intent_argmapping_projection_defect` absent from the `diagnostic` union, the eight
`MULTISET` store readers, the three homes of the `mappingsConstantName` mint, the two `CodeBlock`
parameters and three `JoinStep.Hop` casts slice one retires, and every quoted DDL comment verbatim.
`KeyProjectionCommands.produce` really takes no schema while `EmitPlan.produce` still does, so the
worked example is what the item says it is. Both gate questions pass: the consumer-facing outcome is
stated plainly as no change at all, with the payoff priced as the cost of the next change, and every
mechanism the plan reaches for already exists in the tree, with three parallel mechanisms refused by
name (a shared reader layer, oracle-diff scaffolding, a deny-list guard). One correction was folded
into criterion 2 rather than left as a note: the carve-out counted `GeneratedUnits` constant reads
where the dial forbids the package, so the inventory the paragraph promises is 27 sites across ten
files rather than five across four. Neither answer to the fork changes, which is why it did not hold
the gate.
