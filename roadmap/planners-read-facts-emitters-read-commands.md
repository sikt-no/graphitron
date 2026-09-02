---
id: R682
title: "Planners read facts, emitters read commands: dissolve the walk and the leaf zoo"
status: In Progress
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-09-02
---

# Planners read facts, emitters read commands: dissolve the walk and the leaf zoo

> **Citation redirect, 2026-08-28.** The R765 and R831 citations below name items dissolved into R876;
> both subjects live there now, R765's expression-keyed join as rung 2 of that item's lever order and
> R831's stale DDL claims as a slice. The evidence is in
> `roadmap/audits/2026-08-28-derived-read-cost-premise.md`.

The intended architecture is one sentence. Capture writes facts; planners read facts and produce
commands; emitters render commands; validation is questions asked of the facts. Each tier reads
only the tier below it, so a planner never reaches past the facts into anything that produced
them, and an emitter never reaches past its command into the thing that produced it.

That sentence is the functional-core / imperative-shell topology the development principles fix
(`docs/architecture/principles/development-principles.adoc`), applied to the emit path: the
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

### What "deleted, not migrated" forbids

The four points say what the new path is. This says what the condemned path is owed, because the
instinct runs the other way and did during slice one.

The walk is condemned, not serviced. `FieldBuilder`, `BuildContext`, `TypeBuilder`, the `walker/`
package and the classifier passes beside them are the thing being deleted, so three rules follow.

**Their size is not a metric.** `FieldBuilder` growing while relations land is evidence of nothing.
The number that measures this item is leaf dispatch inside `plan/`, which counts the new tree
reaching into the old model. A line count over condemned code measures the size of the thing being
removed, not the progress of removing it.

**They never read the store.** This holds by construction today: store reads under `rewrite/` are
confined to `capture/`, `derive/`, `diagnostics/` and `compile/`, which are the fact writers and the
new readers, while the walk itself holds no reference to any relation. Plumbing a relation into a
condemned pass buys one spelling in a file with no future, and reuse between the old path and the
new one is exactly what makes a cutover hard. When a derivation inside the walk looks like it wants
to be a relation, the relation lands when the *new* path needs it, stated from facts on its own
terms, and the old pass dies untouched rather than being rewritten to agree. The worked example is
the mapping-constant dedup pass: its grouping and its handler-list fingerprint are pure functions of
captured facts and could be a relation today, and making it read one would be the mistake, because
that pass is deleted rather than migrated.

**They are not deduplicated or improved.** A second spelling of a rule inside condemned code is not
debt. It is scheduled for deletion, and touching it spends effort on the wrong side of the line.

Two consequences worth stating because they read as problems and are not. A relation with no reader
is the normal state of a store-first slice: the store deliverables land ahead of the producers that
read them, and the old spelling is never reconciled with the new one, it goes away when the new path
covers the coordinate. The real finding is the opposite shape, a producer in `plan/` deriving
something the store could have stated. And the pins are not monotonic gates: `PLAN_LEAF_REFERENCES`
rises when a capability lands as leaf dispatch and falls when it dissolves onto facts, so a gate
demanding it fall every slice would block the first half of every cycle. What it makes visible is a
*stalled* relocation, which is a shape in the trend rather than a value in one slice.

The one thing both paths must share is what the output is called. Both emit into one output package
during the cutover, so a generated unit or constant has to carry the same name whichever path minted
it. That is why the naming vocabulary in `GeneratedUnits` is imported by condemned code and why that
does not violate the second rule: it is an output contract, not a derivation. Nothing else crosses.

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
projection appears here at all, R743 and R870 having drained that family between them, which is the
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

### What the counters can and cannot see, and the two numbers that bind

The four pins are trend telemetry, not the target. They are regexes over source text:

```
Pattern.compile("instanceof " + LEAF_HIERARCHIES + "[.A-Za-z]*")
Pattern.compile("case " + LEAF_HIERARCHIES + "[.A-Za-z]*")
```

So they count spelling. The instrument's own javadoc records a sixteen-point rise and explains it as
arms that started naming their leaves in import-short form "where the previous fully-qualified
spellings sat outside it; same dispatch, then visible to the count". Movement with no behaviour
change, in both directions: merging two `case` arms into one lowers the count and relocates nothing.

The deeper limit is the one "What deleted, not migrated forbids" sets up. Leaf dispatch inside
`plan/` is scaffolding that disappears by construction when the leaves are deleted, so the count is
a leading indicator of an event the terminal deletion guarantees anyway. Keep the pins for the one
thing they are good at, which is making a *stalled* relocation visible as a flat line across
several slices. Do not read a single slice's movement as progress or regress, and do not gate on
them.

Two numbers actually bind, and the first already exists at scale.

**Behaviour holds.** The gate this item opened with, byte-identical generated output and identical
message, location and severity. It is instrumented by 3794 tests in the generator module alone,
across 213 unit-tier and 177 pipeline-tier classes, the compilation and execution tiers above them,
and `graphitron-sakila-example` compiling emitted sources at Java 17 and running them against a real
database. Every slice runs the whole of it. Nothing else this item does is worth anything if that
goes red, and no leaf count substitutes for it.

**The condemned types become deletable.** For each type in the seven hierarchies, how many files
outside the condemned tree still name it. That is one grep, it cannot be moved by spelling, it only
falls, and reaching zero *is* the type being deletable rather than a proxy for it. All seven rows,
corrected: an earlier printing of this table listed the four largest and read as though the other
three were zero, when each was nonzero at the same reading and two of them still are.

[cols="3,1,1"]
|===
| Condemned type | Files at slice one's pickup | After slice one

| `GraphitronField`
| 6
| 5

| `ChildField`
| 4
| 4

| `GraphitronType`
| 3
| 3

| `MutationField`
| 3
| 2

| `QueryField`
| 2
| 2

| `OutputField`
| 2
| 2

| `InputField`
| 1
| 1

| *Total*
| *21*
| *19*
|===

Both readings are `grep -rlw <type> plan command render --include=*.java`, the pickup column taken
at `ed6964d1` and reproducing at `102181cc` and `200fd26` unchanged.

That census is what "The closer" should ratchet, and it is what a slice's reflection should report.
A slice that leaves it unchanged relocated nothing, whatever the leaf counters did.

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

The heading says `derive/` shrinks, and it does, but it does not empty, which is worth stating
because the name invites the opposite reading. Classified by what each file does, the package today
is 1078 lines projecting a view's closed `verdict` vocabulary into located rejections with their
prose, 420 lines reading the store into value objects, 392 lines of capture-cadence writers running
a fixpoint, and 351 lines of support records. The largest file is 439 lines of which 187 are
comments, and its only branching is a switch over the view's verdict plus a helper choosing "a" or
"an" by first letter. None of it is derivation that could have been SQL and was not.

Two of those jobs are permanent. Projecting a verdict into a message is rendering, and prose is not
a captured fact. A fixpoint writer exists because the closure is over a cyclic graph and H2 has no
safe recursive view for one, so the rule stays in the joins and only the loop is in Java. What
leaves is the third job, the store reads named above.

So the package is misnamed rather than misused, and the name is an attractor: call something
`derive` and the next contributor with derivation to place will reach for it, which is the exact
mistake the strategy section forbids. It also fuses two permanent jobs and one departing one under a
word that describes none of them. Splitting and renaming it is not this item's work and is filed
separately; what this item owes is not adding to it, and the rule above already says so.

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

1. **Conditions** (`ConditionCommands`, 399 lines, 3 dispatch sites). The smallest surface, and the
   one every other relation references by glue row. That reference is why an earlier draft put it
   first; the next section explains why it does not have to be.
2. **Projections** (`ProjectionCommands`, 731 lines, 38 sites).
3. **Launchers** (`LauncherCommands`, 1,181 lines, 17 sites). The largest producer, and the one whose
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
  carry location. **The refusal arms land in the DDL with the relation,
  even though slice one reads only the emitting arms.** That is the slice's validator-tier
  deliverable: the admit and refuse halves of one predicate get one home from the start, instead
  of the admit half in SQL and the refuse half still in `FieldBuilder` with nothing binding
  them. The validator half's later routine-write check then reads rows that already exist. A
  smaller slice one omits *reading* the refusal arms; it does not omit stating them.

  Delivered as `intent_mutation_routine_seat`. Four decisions the draft above did not settle, each
  taken against a rule the schema already states rather than invented here. **No message column
  and no severity column**, against the draft's own wording: `intent_node_id_decode_defect` settled
  that a derived verdict relation carries the closed vocabulary plus its witnesses and the prose
  belongs with the consumer that composes it, and a `message` column in the model appears only in
  the transcription families, where the walk authored the string and the store is copying it down.
  Severity would additionally be a function of the verdict and of nothing else, so which verdicts
  are the author's to fix and which are shapes the generator owes an emitter is stated per value in
  the column comment instead of denormalised into a column. **Seat and verdict are two columns, not
  one fourteen-value vocabulary crossing both.** Which shape the author wrote for is decided by one
  predicate (is an `@reference` written) and holds whether or not the seat holds, so a refused
  coordinate still names the shape it was aiming at, which is what a diagnostic about it needs.
  **One pass with a CASE rather than one UNION arm per verdict**, which is that same relation's rule
  for the same reason and one more that matters here: exactly one verdict per coordinate is the
  whole contract, and one driving row gives it by construction where a union has to rank arms to get
  it back. **The terminus rule reads `intent_bound_table` and not the reduction**, on
  `intent_inferred_node_type`'s stated grounds: the reduction's other arm is
  `intent_routine_return_binding`, which binds a chain field's return type to that same chain's
  terminus, so comparing the terminus against the reduction compares a value with itself.

  Two silences are disclosed on the relation rather than closed. A first hop joining by an authored
  condition *alone* resolves to no hop row at all, so it reads as `CHAIN_UNRESOLVED` where the walk
  calls it a shape owed an emitter; separating them needs the stalled step named, and the tail a
  chain walks is `intent_field_chain_node`'s own and not a relation. And an `ID`-element payload
  draws no `intent_carrier_data_field` row at all, that relation refusing an ID element for the
  routine family outright, so it reads as `NO_CARRIER`. The anchor is `MutationRoutineSeatTest`,
  17 pipeline-tier cases: one per verdict, plus the population edge (a `@routine` on a Query field
  is a read and draws nothing) and a totality case over a graph holding one coordinate of each seat.
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

  Delivered as `TenantAcquisition` (the three arms, plus a `SlotRead` vocabulary of its own) and
  `TenantRouting` (the run-grain axis), both in `command/`, rendered by
  `render/TenantAcquisitionFragments` and folded by `RoutineWriteCommands.tenancyOf`. Four
  decisions the draft above did not settle. **The axis rides the relation as an overlay index, not
  as a slot on each row**, which is what "the run fact rides the relation" costs once the arms are
  the three multi-tenant ones: a per-row slot would have to be nullable or carry a fourth
  single-tenant arm, and that arm would be the run fact stamped onto every coordinate. The
  classifier drew this line first, `TenantBindingIndex` being empty in a single-tenant build
  rather than uniformly untenanted, so `TenantRouting.Unrouted` states the absence once and
  `Routed` carries the carrier ref beside the per-coordinate arms. Coverage of the rows is then
  the relation's invariant and is checked in `RoutineWriteRelation`'s constructor. **The slot
  reads are restated in command vocabulary rather than borrowed**, because `command/`'s
  import-direction allowlist admits no `TenantBinding`; the fold that turns one into the other is
  the producer's, which is where the draft wanted it anyway. **The bound key's Java type rides a
  `ColumnRef`**, the primary bound slot's own tenant column, because the emitted local must be
  declared (generated sources never use `var`) and `ColumnRef` is on that allowlist where a
  javapoet `TypeName` is not; every co-bound column agrees with it by validation. **An uncovered
  coordinate under a routed axis is refused at both ends**, in the producer when a binding is
  missing and in the renderer when the index has no arm, rather than falling back to the
  request-context read the way `TenantDslEmitter` does: that fallback compiles, runs, and reads
  another tenant's rows.

  One thing the draft's parameter arithmetic missed. The two `CodeBlock` parameters go, but the
  renderer needs one more thing the row cannot hold: the host class's `graphitronContext(env)`
  seam, because the request-context read and the context-argument slot read both emit a call that
  only compiles if the same class also carries the helper. That is a per-class collector rather
  than a decision, so it is threaded as `render/RequestContextRead` beside the two collectors the
  renderer already takes, and both existing hosts satisfy it. The anchors are
  `TenantAcquisitionFragmentsTest` (7 unit-tier cases, each declaration pinned as exact text
  because a plausible-reading fragment that acquires the wrong source is the failure this axis
  exists to prevent) and `RoutineWriteTenancyPipelineTest` (3 pipeline-tier cases joining the
  producer's fold to the renderer's arms on a classified schema).
* **Command vocabulary: take the type lift the hop relation pays for.** `RoutineWriteCommand`
  today carries the walk's `RoutineChain` as a component, guarded by two compact-constructor
  throws and read back out through three casts. Rebuilding that carrier out of store rows just
  so the row can keep holding it would preserve the casts and the throws for nothing. With the
  hop relation landed, the row declares the narrowed shape directly: ordered hops at their own
  grain plus the anchor slots as components, the hop-0 invariant held by the store fact rather
  than asserted at construction. `RoutineChain` then retires with the walk instead of surviving
  as a value record the command still depends on.

  Delivered as two nested carriers on `RoutineWriteCommand`: a `RereadAnchor` (table, alias, the
  captured pairing) and an ordered list of `RereadHop` (table, alias, `on`, filter), with the
  routine call itself lifted to a component of its own. `RoutineChain` leaves the command tier
  and its entry leaves `PackageImportDirectionTest`'s borrow dial. Four decisions the draft above
  did not settle. **The anchor is its own component rather than index 0 of one flat hop list**,
  because it is not a join: the re-read departs from it, and a flat list makes every reader start
  at 1 and remember why. Split, the join loop has no index arithmetic, and the terminus derives
  from the pair (the anchor's alias where the chain hops no further). **A hop's table is a
  `TableRef` and not a `TableExpr`**, which is the narrowing that carries `RoutineChain`'s
  catalog-only pin across. The pin does not vanish with the carrier: a routine node at a hop
  position carries `On.Lateral` by `JoinStep.Hop`'s own invariant, so the renderer's refusal of a
  lateral join *is* that pin, and it now reads as one rather than as defensive code. **The two
  retired throws were second copies**, not checks: `MutationRoutineWriteField`'s own constructor
  already makes both (at least one hop, hop 0 joining by column pairs), and the command re-made
  them only because it held the wider carrier. What replaces them is structural, the anchor being
  a component and its pairing a slot list. **One narrowing check moves to the producer**
  (`anchorOf`), on the precedent `FkHop.narrow` already sets: the Java narrowing a wider type
  cannot express happens once where the row is minted, not at every read. Net on the counters the
  draft names: three casts and two command-tier throws out, one production-side narrowing in.

  Two things worth stating that the draft did not raise. The anchor keeps one construction check,
  a non-empty pairing, symmetric with the sibling arm's `capturedPairs`; it is not a third copy
  of the retired pair, being about a list the row itself declares rather than about the chain's
  shape, and once the producer reads the store nothing upstream will guarantee it. And the anchor
  carries no filter slot, which makes an existing silent drop structural instead of accidental:
  the renderer never emitted hop 0's `condition:` filter, and cannot, a filter being a
  two-argument method over departure and arrival whose departure is the routine result, which
  never appears in the post-commit `FROM`. The loop that starts at 1 was the whole of that rule;
  now the type says it. Whether an author can write such a `condition:` and have it silently
  ignored is a separate question, filed rather than answered here.
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
  the new base. That reading is now the weaker half of the reflection rather than its subject, per
  the counters' own limits stated above: what slice one is answerable for is the deletable-types
  census, and the pin landing at 136 is telemetry recorded beside it. What the nine references
  actually are matters more than the count. The producer now
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

### Slice one, measured

The reflection slice one stops at. Every figure is from the slice rather than estimated, and the
question order is the one "Then stop" set.

**What one vertical cost.** Eighteen commits between `ed6964d1` (Ready to In Progress, 2026-08-20)
and `9c31133` (the statement-count pin, 2026-08-24), four calendar days; fourteen of them touch the
tree and four amend the plan alone. 4,388 lines of Java added against 997 removed, and 505 lines of
DDL against 176. Of the Java, 2,146 added and 428 removed are test source, so the main-source figure
is 2,242 added against 569 removed and the tests are very nearly half of everything written.
Exactly one of the fourteen tree commits is net negative: `4b9ddce`,
the one that made the producer read the store, at +342 and -622. Every commit before it is pure
addition. So the shape of a conversion is not "replace old code with new" but ten commits of
scaffolding paying for one commit that deletes 280 net lines, and a reader who expects the diff to
shrink at any point before the last one will conclude the work is going badly.

Review cost is only half measurable. The Spec gate took one review round (`3a9024bd` found the
inversion census a site short and a carve-out unsettled, `102181cc` signed off), and the
implementation half has not been reviewed yet, so no figure for it exists to report. What the slice
can report instead is self-correction: four commits in the window amend the plan rather than the
tree, one of them (`bc39ce5e`) writing down what "deleted, not migrated" forbids because the slice
nearly got it wrong. A rule that has to be written down mid-slice is a review round that happened
without a reviewer.

**Statements, and whether the grain rule held.** It held. `RoutineWriteCommands.produce` issues
three statements at three grains: the coordinate, the chain hop's pairing, and the carrier's
captured pairing. Three is constant across a one-coordinate graph, a graph where every fan-out axis
the coordinate statement's correlated `MULTISET`s cross is populated more than once, and a graph
that writes through no routine at all, which is the case that catches a producer skipping a grain
because an earlier one came back empty. `RoutineWriteProducerStatementCountTest` pins it at the
producer's grain rather than the facts pass's, so the two folds that stay on the classified schema
are also held to costing the store nothing.

**Output identity, and what surfaced instead.** Emitted output stayed byte-identical throughout,
and the execution tier covers that claim rather than a string comparison standing in for it. No
walk bug surfaced in what the generator emits. Three pieces of walk vocabulary turned out to be
unreachable or inert once the same facts were stated relationally: a hop's join basis has no
lateral arm, so the renderer's refusal of one became a total switch with no throw; a routine
argument has one binding arm, because a routine write sits at a mutation root whose chain head has
no previous node; and the command-tier routine argument's resolved `PathExpr` carried per-segment
list-lifting that decided nothing at either of its two readers. None of the three changes output.
All three are the walk representing distinctions the facts show cannot occur.

What did surface, three times, is read cost. R811 and R819 were both filed as regressions found
beside this slice; and R733's guardrail case is what none of it being visible until trunk had run
eleven consecutive green builds at four times its normal wall clock argues for. The routine-write
hop pairing's `CASE` join against a window-carrying relation was a fourth candidate and is not one:
patched at `c702da2` on a thread dump of a stalled generate, it was reverted once measured, the
producer costing the same over the sakila schema with the patch and without it. The stall was
R819's regression rather than this join's, and the join's hazard is R765's to answer. The slice
found no defect in what the generator emits and three in what the store costs to read. That is the
finding that should reorder the programme: the risk in this conversion is not correctness, it is
read cost, and the gates around read cost are narrower than they look. `DerivedReadCostTest` holds
a registration monotonic against its readers and `MaterializeRegistryGateTest` holds an index to a
stated reader, both of which are claims about a *registration*. A newly authored relation that is
never registered is inside neither, so a producer moving from a walk to a store read moves from a
cost nothing measures to a cost nothing measures until somebody registers it, and only one of those
two has a fifteen-minute floor.

**Per relation.** 261 lines of view body across four relations, and an aggregate would mispredict
in both directions exactly as the plan warned.

[cols="3,1,3"]
|===
| Relation | View body | What it is

| `intent_field_chain_start`
| 17
| New derivation, small. A resolution through `intent_spelled_table` plus a FUNCTION-type
requirement plus a max-ordinal window. No refusal arms; absence is the refusal.

| `intent_field_chain_node`
| 69
| Relocation, not new derivation. The recursive walk moved out of `intent_field_chain_terminus`,
which became an eleven-line selection over it at `seq = last_seq`. Near-zero net derivation added;
what it bought is one home for the walk.

| `intent_field_error_channel`
| 28
| Reduction. Joins `intent_errors_field` and `intent_carrier_data_field`, both already standing.
No refusal arms.

| `intent_mutation_routine_seat`
| 147
| Reduction by the plan's definition, since every fact it turns on is an existing relation, and
still more than half the view body the slice added, on its own.
|===

The refusal question has one answer and it is the seat's. Its verdict vocabulary is fourteen
values: thirteen refusals and one admission. The producer reads exactly one of them. Of the
110-line verdict `CASE`, the two `ELSE 'ADMITTED'` clauses are two lines and the other 108 are
refusal predicates, and `MutationRoutineSeatTest`'s seventeen cases include thirteen for verdicts
nothing yet reads. Stating the refusals with the admissions was the right call and this is what it
costs: three quarters of the dearest relation in the slice, written for a validator that has not
been built.

It is not only lines, but it is less than the line count suggests, and the check is worth recording
because the first reading of it was wrong. Thirteen refusals is thirteen correlated `EXISTS`
evaluated per driving row and the seat's scan count is high accordingly, which invites the
conclusion that the refusal arms are a live cost problem. They are not. `DerivedReadCostTest` times
the registered read in milliseconds, and the static inline-multiplicity report ranks the seat
thirteenth rather than first, the node-id decode family and the argmapping projection defect being
the broad relations. Registering the seat would be the wrong lever besides: no view reads it, so a
registration would buy a single Java reader a refresh it has no use for, against the rule that a
registration is a shared investment.

Where the seat does cost something is a mechanism it did not introduce and does not own. It joins
the field census on a coordinate no key serves, which it shares with three sibling relations, and
that shows up in `DerivedReadCostTest`'s pinned findings rather than in anything slice one wrote.
R820 carries the lever and the measurements. The rule to hand forward is therefore narrower than
"price the refusal arms": state them, and put the relation through the read-cost gate in the same
increment, rather than reading a scan count out of a test that measured it for something else. Both
halves of that sentence are this slice's own error, made and caught here.

**Is `rowFor` the right emitter seam.** The shape is right and the implementation is not, and slice
one is where the implementation became a fork. `RoutineWriteRelation.rowFor` and
`LauncherRelation.rowFor` are now the same three lines, a linear
`rows.stream().filter(...).findFirst()`, and `LauncherRelation` additionally carries
`byCoordinate()`, which rebuilds a `LinkedHashMap` on every call. Two lookup shapes for one
question, wrong in opposite directions: the scan is linear per call, the map is linear per call and
allocates. `TypeFetcherGenerator` calls `launchers.rowFor` at eight sites, each inside a
per-coordinate emission, so the emitter is quadratic in coordinates and has stayed cheap only
because the relations are small.

The seam also drifted in its own javadoc within one slice. `RoutineWriteRelation.rowFor` says the
fetcher generator dispatches on its presence rather than restating the producer's membership
predicate; `renderRoutineWrite` calls `orElseThrow`, and the presence dispatch lives in
`EmitPlan.requireEveryProjectionIsReachable`. The sentence is true of `LauncherRelation`, whose
copy it is, and was inherited without the behaviour. So the answer is that the cutover wanted one
memoized coordinate-keyed lookup declared once on a shared carrier, not a method copied per
relation with its reasons attached. Two copies is a coincidence; the third family makes it a
pattern, and the third family is next.

**Multiplied by what remains.** The corrected census leaves nineteen references in six files:
`LauncherCommands` (5 types), `FetcherEdgeCommands` (5), `ConditionCommands` (3),
`ProjectionCommands` (3), `TypeUnitCommands` (2), `EmitPlan` (1). Five producers plus the plan
assembler, whose one `GraphitronType` reference no producer conversion removes; it goes with the
terminal deletion. Nineteen is not nineteen units of equal size, and the multiplication that
matters is not by references but by files: slice one converted the file naming two of the seven
types and spent about 4,000 lines doing it, and `LauncherCommands` names five.

Three things make the naive five-times-4,000 an overestimate. The chain relations are shared, so
the next family needing a chain reads one rather than promoting one; `TenantAcquisition` was built
for two families by design and the second pays nothing; and the seat's refusal arms are a one-off
for the routine-write vocabulary rather than a per-family tax. Two things make it an underestimate.
`LauncherCommands` is the largest file and names the most types, so it is last in difficulty as
well as in the order; and nothing in slice one paid down the read-cost problem it discovered, which
on current evidence costs a full item per family rather than a paragraph.

The programme has a credible shape. It does not have the shape the inventory above assumes, which
is a sequence of conversions punctuated by instruments. It is a sequence of conversions each of
which is likely to spawn a read-cost item, and the sequencing question the next slice should settle
is whether the store's read cost gets its own workstream instead of being discovered once per
family.

### The second half, replanned against slice one's numbers

Slice one earned the right to plan the rest, and this is that plan. It changes three things and
confirms one.

**Confirmed: the ordering constraint is still a phantom.** Routine-write converted against nothing.
No producer waited on it and it waited on no producer, the `ConditionRelation` parameter never came
up, and the `produceWithoutSchema` inversion dissolved exactly as "What actually constrains the
order" predicted it would. That section stands unedited. Any producer still converts whenever its
facts are relations.

**Changed: the size predictor was the wrong column.** The inventory sizes the six remaining
producers by dispatch sites, and slice one says that column predicts the wrong half of the work.
Routine-write carried 11 sites, under a twelfth of the plan-side pin, and the conversion itself was
one commit at +342 and -622. The other 4,000 lines were the three facts that had no relation.
Dispatch sites predict *emitter* work; missing relations predict *store* work; and in slice one the
store work was 2,484 added lines (the three relations plus the facts reader) against the producer's
own 342, a ratio above seven to one. So `FetcherEdgeCommands` at 48 sites is
not therefore the largest increment, and `ConditionCommands` at 3 is not therefore the smallest.
Neither is knowable from the inventory at all.

**Therefore the first deliverable of the second half is the availability check, run five times.**
Slice one opened by running one for the routine-write family, and its findings "replace the
optimism" in the words of the section that records them: three facts turned out to have no relation
where the draft had assumed the family was ready. Five unconverted producers is five such
optimisms. Run the check for conditions, projections, fetcher edges, type units and the schema-level
globals, and report per producer which of its inputs are relations, which are derivable from
relations, and which need one authored. Only then commit an order. The check is cheap, it is the
only thing that has ever moved this item's cost estimates, and doing it once for all five is
strictly cheaper than discovering the answer a producer at a time.

**Second deliverable, and it comes before any third family: unify the `rowFor` seam.**
`RoutineWriteRelation` and `LauncherRelation` now carry the same linear scan, one of them beside a
`byCoordinate()` that rebuilds its map per call, read from eight per-coordinate emission sites in
`TypeFetcherGenerator`. Two copies is a coincidence and the third makes it the pattern every later
family copies, so this lands while there are two. One memoized coordinate-keyed lookup, declared
once on a carrier both relations hold, and the javadoc drift goes with it: the sentence about
dispatching on presence is true of the launcher relation and false of the routine-write one, whose
caller throws and whose presence dispatch lives in `EmitPlan`. Small, fully evidenced, and it is the
seam every remaining producer will be read through.

**Third: read cost is a per-increment obligation, not a workstream.** The reflection left this open
and the evidence closes it against a separate workstream, in both directions. The three read-cost
findings slice one spawned were each about one relation and each was cheap once measured; what was
expensive was measuring them late, after trunk had run eleven consecutive green builds at four
times its normal wall clock. And the fourth candidate was expensive in the opposite way: the hop
pairing's `CASE` join was patched, shipped, cited in this item's own reflection, and reverted once
somebody ran a same-fixture control and found the producer costing the same either way. Diagnosing
late and diagnosing without a control are the same failure wearing two faces, and a workstream
inherits both by construction, since it runs behind the increment that authored the relation and
arrives after the shape is already in the tree.

So the obligation attaches to the authoring increment instead: **an increment that authors a
derived relation puts it through the read-cost gate before it ships, and records what it measured
on the relation's own comment.** This is narrow on purpose. It is not a budget and not a number; it
is the requirement that somebody looked, by the procedure the `store-performance` skill sets out,
at the time the relation was written. That procedure's control step is the load-bearing half:
between them, the reverted patch and this reflection's own retracted paragraph about the seat cost
two sessions and two commits, and both were hypotheses that a control on the same fixture would
have refused before either was written down.

It is worth naming why this cannot be delegated to the gates that exist. `DerivedReadCostTest`
holds a registration monotonic against its readers and `MaterializeRegistryGateTest` holds an index
to a stated reader; both are claims about a *registration*, and a newly authored relation that is
never registered is inside neither. Slice one authored four relations, registered none of them, and
walked through that gap without any gate noticing. The obligation above is what covers it until
something mechanical does.

**Fourth: `discriminatedBranches` gets an owner.** "What actually constrains the order" names
`TypeFetcherGenerator.buildTableInterfaceReprojection` as a production-path inversion invisible to
every pin, and says the family that owns it should name it as a deliverable. It is the launcher
family's: what retires it is the branch list arriving on a command row, and the branch list is
`LauncherCommands.discriminatedBranches`. It lands with the launcher conversion and not before, and
the launcher increment is now the one that carries it.

**A proposed order, revisable by the check above.** Conditions first, not for the dependency reason
an earlier draft gave and this item already refuted, but because it has the smallest dispatch
surface of the five and its relation is the parameter three other producers take: converting it
first means the remaining three convert against a parameter that is already a store read, and it is
the cheapest place to prove the unified seam on a second family. Then projections and type units in
whichever order the availability check ranks readier. Then fetcher edges, whose 48 sites make it the
step most exposed to a mechanical read-by-read transcription violating one statement per grain.
Then launchers, largest, naming the most condemned types, and carrying `discriminatedBranches`.
`EmitPlan`'s single reference is not a producer conversion and goes with the terminal deletion.

The census is the progress dial for all of it: nineteen references in six files, falling only when a
whole file stops naming the model. A second-half increment that leaves it unchanged relocated
nothing, whatever else it moved.

The check has since been run, and the order above is superseded from its second step onward; see
"The five availability checks, run" below.

### The five availability checks, run

Run 2026-08-24 against trunk `1e105c45`, by slice one's method: enumerate the facts the producer
reads, then ask of each whether the store states it, states it inside another view's CTE, or does
not state it at all. Absence below is a reading rather than a failure of recall. Every captured
population reported as underived was checked by scanning all 83 view bodies for a reader of it, and
every relation reported as present was read in the DDL.

[cols="3,1,1,4"]
|===
| Producer | Dispatch sites | Relations to author | What has to be authored

| `FetcherEdgeCommands`
| 48
| 0
| nothing

| `TypeUnitCommands`
| 29
| 2
| nesting reach, connection synthesis; both shared

| `ConditionCommands`
| 3
| 3
| argument column resolution, the condition membership fold, facets

| `ProjectionCommands`
| 38
| 5
| operation members, nesting reach, pivot resolution, ordering, connection synthesis

| `LauncherCommands`
| 17
| 5
| operation members, delivery's third trigger, tenancy, ordering, connection synthesis
|===

**The two columns do not merely fail to correlate; over these five they invert.** The producer with
the densest dispatch in the package needs no new relation, and the producer with the thinnest needs
a classifier arm. Slice one showed the inventory's column predicted the wrong half of the work.
This shows it predicts the wrong half in the wrong direction, which is worth more than the first
finding: it means an order taken off the inventory would have started with the family that costs
the most and ended with the one that costs the least.

**The finding underneath all five: the claim stratum is eight arms, not seventy-two.**
`intent_authored_field_claim` carries seven claim literals (`EXTERNAL_FIELD`, `INPUT_OBJECT`,
`LOOKUP_KEY`, `MUTATION`, `NODE_ID`, `ROUTINE`, `SERVICE`), `intent_resolved_field_claim` two
resolution arms, `intent_authored_type_claim` two (`ERROR`, `TABLE`), and the structural
`intent_column_match_claim` one verdict over five match tiers. The taxonomy these producers dispatch
over is 72 leaves. So the stratum this item has been calling "the expensive population, and it is
there" does not state a leaf kind and was never going to: a leaf is a *composition* of a claim, a
delivery arm, a wrapper, a binding and a polymorphic shape, and the store states the components.
That is not a gap. It is the reason the conversions are possible at all, because a producer needs
the answer its switch computes and not the name of the branch it took. But it means "are this
producer's facts relations?" cannot be answered by looking for the leaves, which is what makes the
question per-producer and why the five answers below differ as much as they do.

**Fetcher edges reads three facts, and the store states all three.** The 48 sites resolve to four
target shapes, and the producer's whole input is: the table-bound participants of a polymorphic
coordinate (`intent_poly_member` joined to `intent_bound_table`, the joined-table and unbound arms
contributing no target), the return type name of a routine write (`graphql_field.named_type`), and
the coordinate's glue classes, which it derives from the condition relation and not from the schema
at all. Nothing else. The 48 sites are a switch whose arms are overwhelmingly `null`, which is to
say they are the *declaration* that a family is outside the relation, and a declaration costs
emitter work rather than store work. This producer is the clean confirmation of the replan's
predictor claim, from the extreme end.

**Type units is second-readiest, and one of its two halves collapses on contact.** The schema-shape
kind dispatches over all 18 type permits to pick one of five graphql-java forms, and the mapping is
`graphql_type.kind` verbatim: object permits to `OBJECT`, both interface permits to `INTERFACE`,
union to `UNION`, input to `INPUT`, enum to `ENUM`, with two exclusions the store also states
(scalars, which register off a resolved constant, and `_`-prefixed federation internals). The
18-arm switch is a re-derivation of a captured column. The input-record half is already a relation
and nobody noticed: `argumentReachableInputs` is the transitive closure of input types reached from
field arguments, and `intent_input_occurrence_path`'s population is that walk keyed by occurrence,
so the fold is that relation's type projection. The `@error` population is
`intent_field_error_channel` and `intent_errors_field_member`, which slice one landed for the
routine-write family and which serve this one for free. What remains is the nesting reach fold and
the connection pair, both shared with projections.

**Conditions is small and carries the one genuinely new classifier arm.** Its authored half is
comprehensively there: the `@condition` capture with its context args and argMapping pairs,
normalised across sites by `intent_argmapping_pair`, resolved by `intent_argmapping_segment_binding`
and `intent_argmapping_bound_parameter_type`, with the method identity on
`intent_field_producer_method`; the input-surface expansion and its override cascade on
`intent_input_occurrence_path` and `intent_input_occurrence_override`; the FK-target reach on the
reference-step relations. The generated half is not. A generated condition filter is one predicate
per column-backed argument, and while `intent_argument_scope_table` answers which table an
argument's content binds against, nothing answers which *column* it resolves to. No view in the
store joins `graphql_argument` to `sql_column`. The relation to author is the argument-site twin of
`intent_column_match_claim`, and the pairing is already written down in the store's own comments:
`intent_argument_scope_table`'s comment calls itself "the argument-site counterpart of the reading
`intent_field_column_scope` makes at a field site", and the column half of that pair was never
built. This is modelling work of the seat verdict's kind, not a fold.

One thing dissolves here. The producer recurses into nesting fields because a nested coordinate has
no `fieldsOf` entry, deduplicates the rows a nesting type reused across parents produces, and fails
hard if two reuse sites disagree. In the store a nested coordinate is a coordinate: `graphql_field`
holds it once, the reuse multiplicity never arises, and the recursion, the dedup and the divergence
throw all go. The walk was paying for a shape the relation does not have.

**Projections and launchers are the expensive pair, and they are expensive together.** Four of the
five relations each needs are the same four. Both need the operation-member fold (projections read
the `SERVICE_CALL` and `LOOKUP` arms to pick a contribution; launchers join members with anchor-hood
to reach a verdict at all), both need connection synthesis, and launchers additionally need ordering
and tenancy, which projections need through the launcher rows they call into. Converting either one
first pays for most of the other's store work. Their own halves are in good shape: the correlated
chain's source facts, the reference paths and their foreign-key pairs, the column resolution for
column-backed fields, and the routine chain relations slice one landed are all present.

**Delivery is two thirds of a relation, and the missing third is documented.** This is the check's
one partial finding, and it is the shape slice one's chain deliverable had.
`intent_field_separate_fetch` states which fields are fetched by a statement of their own, in five
rule arms, and two of `DeliveryFact.Batched`'s three triggers are among them: the authored markers
(`@splitQuery`, `@tenantFanOut`) are the view's two marker arms, and the record-handing parent is
its own arm. The third trigger, the list-valued polymorphic fan-in, has no arm, and `DeliveryFact`'s
own javadoc says why: that trigger "was surfaced by this fact's materialization", after the view was
written. The view's remaining two arms (a non-root `@service`, a root operation field) are not
batched delivery at all, which is the reader-side care this promotion needs. So the deliverable is
an arm plus a projection, not a relation.

**Five captured populations have no derivation over them at all.** This is the cross-cutting result,
and it is a better statement of the gap than "four relation-shaped folds have no home in the store
yet", which is what this item has been saying. Ordering (`graphitron_order`, `graphitron_order_by`,
`graphitron_order_field`, and the two default-order relations) is read by no view. So are the facets
(`graphitron_facet`) and the tenant column (`store_graph_tenant_column`). The connection registry
(`graphitron_connection`) is read by exactly one view, and only to exempt connection types from
classification demand. `graphitron_pivot` is read by one view, and only as a column-scope input,
never resolved as a pivot. Each of these is captured, complete, and inert, and each is needed by
more than one of the five remaining producers. They are the shared cost, and they are what makes
projections and launchers a pair rather than two increments.

**The fold count is eight, not four.** `GraphitronSchema` carries `arrivals`,
`reachableSourceShapes`, `tenantScopes`, `tenantBindings`, `argumentReachableInputs`,
`connectionSynthesis`, `operationMembers` and `deliveryFacts`: eight post-walk folds, computed once
after the walk and read by the emit side, which is the exact shape of a derived relation living in
Java. The item named four. Of the eight, one is already a store relation
(`argumentReachableInputs`), one is two thirds of one (`deliveryFacts`), and two were never named
here at all (`arrivals`, `reachableSourceShapes`). Any future statement of what the store still owes
this item should count from this list.

**What this does to the order.** The proposal above stands on its first step and changes after it.
Conditions still goes first: it is the parameter three other producers take, it is the cheapest
place to prove the unified `rowFor` seam on a second family, and it owns the one new classifier arm,
which is better paid early while the family paying it is small. Fetcher edges then moves from
fourth to second, on the strength of needing nothing: it takes the condition relation as its only
non-store input, so conditions unblocks it completely, and it retires five of the census's nineteen
references, the largest single drop available. Type units third, smaller than the inventory
suggests. Then projections and launchers last, taken as one increment or as two adjacent ones,
because four of their five missing relations are shared and the pair's second half is nearly free
once the first is done. `EmitPlan`'s single reference still goes with the terminal deletion.

The dial says the same thing arithmetically: conditions 3, fetcher edges 5, type units 2,
projections 3, launchers 5, `EmitPlan` 1. The order above takes 8 of the 19 in its first two steps.

### The `rowFor` seam, unified

Delivered. The replan called this the second deliverable and said it lands while there are two
copies rather than three. Reading the tree to do it found the third was already there, which makes
the argument stronger than the one that justified the work: the duplication is not the lookup, it is
the *key*, and three relations were each restating it.

`LauncherRelation`, `RoutineWriteRelation` and `FetcherEdgeRelation` are keyed by the coordinate
alone, and each carried its own compact-constructor loop rejecting a coordinate that appeared twice,
in three near-identical spellings with three different messages. Two of them additionally carried a
linear `rowFor` scan and one of those a `byCoordinate()` that rebuilt a `LinkedHashMap` per call.
All of it now lives on `CoordinateIndex`, the row set a coordinate-keyed relation holds: the rows in
producer order, the rejection (which now names the offending coordinate, where none of the three did),
the lookup, and the coordinate list. The relations keep the invariants that are actually theirs,
which is the useful division the change surfaced: the launcher's case-folded method-name census and
the routine-write relation's tenancy-coverage check stay where they are, because neither is a
statement about the key.

`ConditionRelation` deliberately stays out. Its key is `(coordinate, table)`, so a coordinate maps to
several of its rows, and this index's contract is that it maps to at most one. That is the boundary
to hold when the conditions conversion lands: what that family wants is a coordinate-to-rows
multimap, not this.

Two things were fixed rather than moved. `RoutineWriteRelation.rowFor`'s javadoc claimed the fetcher
generator dispatches on its presence; it does not, `renderRoutineWrite` reads the row with
`orElseThrow` and the one presence read is `EmitPlan`'s key-projection reachability check. The
sentence was true of the launcher relation's twin and had been copied. And the seam is now pinned by
a test of its own rather than through three relations' delegation, including the ordering guarantee,
which is load-bearing: consumers fold these rows into emitted files, so a map reading out in hash
order would make generated output depend on coordinate names.

This change makes no performance claim, and the first version of this section made one it should not
have: that it was "better or equal by construction". Better or equal is a measurement, and there is no
honest instrument here for it. The relations hold dozens of rows, the repo has no microbenchmark
harness, and reactor wall-clock is not evidence. A map read replacing a scan and a per-call map
rebuild going away are descriptions of the diff, not results; the reason to make the change was that
three relations were restating one key, which is an argument about duplication and stands on its own.

The Java-side cost here that *is* measurable is the one deferred below: two producers scanning the
whole condition relation once per coordinate. That is the same shape the store's own comments warn
about, a relation re-evaluated per driving row, and it gets measured in the increment that fixes it.

One thing the reading turned up and this change does not address, recorded for the family that
inherits it: both `LauncherCommands.conditionRowOf` and `FetcherEdgeCommands.addConditionGlueTargets`
scan the whole condition relation once per coordinate. That is the multimap above, and it belongs
with the conditions conversion rather than here.

### Conditions, first increment: the argument-site column resolution

The availability check named one genuinely new classifier arm across all five remaining producers,
and this is it. Landed as store work alone: two relations and one registration, no producer converted
yet, which keeps the modelling question separate from the conversion that will consume it.

**What was missing, precisely.** A generated condition filter is one predicate per column-backed
argument. `intent_argument_scope_table` already answered which table an argument's content binds
against, and its own comment called itself "the argument-site counterpart of the reading
`intent_field_column_scope` makes at a field site". The column half of that pair had never been
built: no view in the store joined `graphql_argument` to `sql_column`, so the store could say where
an argument's predicate lands but not what it compares.

**Two relations, not one, and the layering is the field site's.** `intent_argument_column_scope`
resolves which table a column name written at an argument's site resolves against;
`intent_argument_column_match` resolves which column the name reaches on it. That is exactly the
`intent_field_column_scope` / `intent_column_match_claim` split, and it is warranted here for the
reason that split states: two consumers ask for the navigation. The match asks which column, and the
predicate binding asks whether the resolved table is the one the field already selects from or
somewhere a join away, which is the scope's `basis` read directly. Deriving the navigation once is
what stops those two disagreeing at a path that reaches two tables, where a presence test over the
captured elements says "moved" and the resolution says "nowhere".

The scope relation could not simply be a third rung on `intent_argument_scope_table`: that relation
is the *departure* `intent_argument_reference_step_target` walks from, so a path-terminal rung inside
it would close a cycle. Departure, landing, resolution, match is four relations in a line, and each
one is read by the next.

**One rule differs from the field site, and the difference belongs to the site.** A repeated
`@reference` on a field composes an ordered chain and the field-site scope takes the first
application. Repeated on an argument it is a conflict the resolver rejects outright, order
composition having no meaning there, so there is no first application to prefer and a site carrying
two resolves to nothing. Declining is what a site the validator must reject deserves; preferring one
would encode a precedence the site does not have. The count is over the applications rather than
their elements, so an empty `@reference(path: [])` written beside a real one is the same conflict,
which is the resolver's own reading of that pair. Everything else transcribes: the terminal must
reach exactly one table rather than exactly one row, an element-less application alone is inert and
leaves the scope rule standing, and the two rules are disjoint rather than ranked so the relation is
a plain union with no windowed collapse over it.

The match view carries the resolver's own gate on the argument's named type being `SCALAR` or `ENUM`.
An input-object argument expands into input fields that resolve at their own sites, and its name
collides with a column often enough that a spurious row here would be one a consumer acted on.

**The write path was the part that had to be found rather than designed.** The two-tier name match
compares folded spellings, so `graphql_argument` and `graphitron_argument_binding` needed the
`argument_name_upper` / `name_ref_upper` generated columns their field-site twins already carry. That
is what a generated column costs in this store: `FactSink.flush` renders a relation's insert from
`table.fields()`, which asserts every column writable, and H2 rejects an insert that so much as names
a computed one. `FactWrites` exists for exactly this and already documented it; both relations now
have a written statement there. The build found this rather than review did, which is the write path's
gate working as intended.

**Read cost, measured.** The first version of this section claimed the read cost was measured when
what had run was the monotonicity gate, which prices existing registrations against their readers and
had priced these two views only as readers. It said nothing about what they cost, and the decision not
to register them was an argument about reader count. Performance is measured here, so it was measured.

The shared read-cost fixture could not answer it: its arguments are node-id keys and routine
parameters, so it barely reaches the shape these relations exist for. The instrument is the same one
that gate uses, `EXPLAIN ANALYZE` with the `scanCount` annotations summed, pointed at a filter-heavy
fixture over the sakila catalog.

[cols="4,1,1,1"]
|===
| | 44 args | 132 args | 264 args

| `intent_argument_column_scope` as a view
| 234
| 2010
| 4002

| `intent_argument_column_match` as a view
| 1056
| 4472
| 8924

| either, read as a table
| 45
| 133
| 265
|===

Linear at scale, 15.2 and 33.8 scans per argument. Two controls decided the rest.

*Same fixture with and without an argument-site `@reference`.* The scope falls 4002 to 491, and
`intent_argument_reference_step_target` alone falls 3371 to 8. So 84% of the scope's headline is the
recursive walk it reads, a relation the store already had and which no reader had until now; the arm
authored here costs about two scans per argument.

*The registered field-site siblings, on the same store.* `intent_field_column_scope_live` costs 1052
over 48 field sites (22 per site) and `intent_column_match_claim` 1419 (30 per site), against this
pair's 15 and 34. The same order per site, and the field site registered its scope and left its match
a plain view.

*So register the scope, and measure that too.* With `intent_argument_column_scope` swapped for a table
in place: its own read falls 4002 to 265, and the match falls 8924 to 5187, 27 milliseconds to 5. The
no-reference control shows what that is: with no walk to re-derive, the match moves only 5125 to 4875.
The 3737 saved *is* the walk, derived once at refresh instead of once per reader. Refresh is one
evaluation per graph, the 4002 a single read already paid, and the fill measured 18 milliseconds.

The reader count was the other thing stated wrongly. "One prospective reader between them" counted
only the future producer, and missed that `intent_argument_column_match` reads the scope today, in the
store. That is the reader the registration is for, and the doctrine's requirement that a registration
be made in the increment carrying its reader is satisfied by it rather than by the producer to come.

Both index shapes the registry roster demands be tried were tried: the argument coordinate, and the
resolved-table triple. Neither moves any reader at all, to the scan. The one reader drives from the
relation first in its own FROM clause, so there is no per-row seek for an index to serve. That is a
roster row now, with the numbers in it.

The domain pin moves 83 to 85 views and 47 to 49 with cells; cells go 107 to 110 rather than to 111,
which is its own small confirmation: registering the scope cut the match's transitive reach through it,
the same mechanism the scan counts show from the other side.

**What this increment leaves owing.** Two of the three relations the availability check named: the
condition membership fold and the facets. The facets are the next section; the membership fold, the
coordinate-to-rows multimap the `rowFor` section handed forward, and the producer conversion itself
are what the section after that still lists.

### Conditions, second increment: the facets

The second of the three relations the availability check named, and one of the five captured
populations with no derivation over it at all. `graphitron_facet` held every `@asFacet` application
and no view read one. Landed as store work again: two relations, no registration, no producer
converted.

**The split is the resolver's own, and it is the same split as last time for a different reason.**
`FacetFieldValidation` exists in the tree precisely because the well-formedness of one `@asFacet`
application is decidable at the application and the rest is not; its javadoc names the axis
(definition-keyed against use-keyed) and says the use-keyed checks live elsewhere "because they need
the consuming-coordinate view the promoter's per-field walk does not have". So
`intent_facet_binding` states what one application binds, and `intent_connection_facet` resolves
those against the carriers that consume them. That the classifier had already drawn this line is
what makes it the store's line too, rather than a layering invented here and defended afterwards.

**The carrier population is where this would have gone wrong.** The obvious gate is
`graphitron_connection`, the `@asConnection` capture. It is the wrong population twice over. An
`@asConnection` on something that is not a bare list expands nothing, and a structural Connection
return type carries no directive at all and is deliberately never given a facets field, its shape
being the author's. The store already holds the right population and holds it exactly:
`graphitron_field_synthesis` gets a row where the CONNECTION macro rewrote a field's type
expression, which is the promoter's own directive-driven arm and nothing else. So the relation gates
on the expansion rather than on the directive that asked for it, and the "inert at the others" rule
the facet spec states holds by construction instead of by an arm. Reachability is one hop for the
same reason: the promoter reads the fields of the type an argument names, so a facet on a nested
input object is not the carrier's, and a transitive closure would have surfaced facets the
synthesised facets object has no field for.

The order is a column because it is output. A consumer folds these rows into emitted methods, so
`position` is dense per carrier and is argument order then field order; a reader that took them in
whatever order the query returned would make generated bytes depend on the plan. The first-wins
collapse on a repeated facet name transcribes the promoter's dedup, and it sits after the
definition-keyed gate so a malformed duplicate cannot consume a well-formed twin's name. Both are
backstops rather than rules: the duplicate is a rejection, so neither fires on a schema that
assembles, and the anchor cases are the only place either can be read.

**Read cost, measured, and the answer inverts the last increment's.** Same instrument: `EXPLAIN
ANALYZE` with the `scanCount` annotations summed. On the sakila example's own schema, the realistic
population, the pair costs 44 and 118 scans over four applications, against 914 for a single scan of
`graphql_field`. That settles nothing on its own, so the scaling and the registration control were
run on a facet-heavy synthetic fixture.

[cols="4,1,1,1"]
|===
| | 24 facets | 72 facets | 144 facets

| `intent_facet_binding` as a view
| 204
| 588
| 1164

| `intent_connection_facet` as a view
| 139
| 347
| 659

| `intent_facet_binding` as a table
| 25
| 73
| 145

| `intent_connection_facet` over that table
| 339
| 2163
| 7779
|===

Both views are linear, and the reader is *cheaper* than the relation it reads. The plan says why:
inlined, H2 drives from the carrier's own arguments, 49 of them at the largest point, and reaches
each one's facets by primary key. Registering the binding takes that plan away. The table becomes
the driving relation, and the reader's join to `graphql_argument` is on `named_type`, which no index
covers, so every argument in the graph is scanned once per binding row: 144 times 49 is the 7056-scan
node the plan shows. An index on the target's own coordinate moved neither figure, to the scan,
because the missing seek is on the other side of that join.

So: not registered, and the reason is written into `intent_facet_binding`'s own comment rather than
only here, an item's body being the wrong home for a fact that outlives it. The finding worth
carrying past this increment is that a registration's value can be negative. The doctrine already
says a registration must be argued rather than assumed; this is the first case in the store where
the argument comes out the other way on its own numbers, one increment after a case that came out
for it. What decides it is not the relation's size but whether its readers reach it by a key
something can seek.

The lever that would make the registration pay is an index on `graphql_argument (graph_name,
named_type)`. That is the second time an index on a captured relation has been the thing standing
between a plan and a seek, the first being `sql_referential_constraint` under the argument-site walk.
Both are declined here for the same reason: an index on a captured relation is a schema-wide
commitment, and `DerivedReadCostTest` already files that class of change as a discipline question not
to be settled on one reader's evidence. Two instances is not yet a case, but it is a pattern, and the
third should be taken as one.

**What this increment leaves owing.** The condition membership fold, the last of the three relations
the availability check named, is the next section. The coordinate-to-rows multimap the `rowFor`
section handed forward and the producer conversion itself are what the section after that still
lists.

### Conditions, third increment: which rule answers for an argument

The last of the three relations the availability check named is the condition membership fold, and
reading for it found that the fold cannot be stated before the thing it folds over is. Membership is
"this coordinate contributes filters against this table", and whether an argument contributes one at
all is a seven-way ordered fork the classifier runs and no relation stated. So this increment is that
fork: `intent_argument_filter_role`, one row per argument, saying which rule resolves what it
contributes.

**A ranked collapse, because the classifier's switch is ranked.** `@orderBy` first, then a
pagination-role name, then an input-object type, then the node-id decode, then the name match. The
store prefers disjoint unions and says so in several relation comments, but these arms are not
disjoint: an argument can carry `@orderBy` and `@lookupKey` and be named for a real column, and a
union would surface it three times. Transcribing the switch's order is the faithful statement;
inventing a precedence to make a union work would not be.

Two of the arms turned out not to be arms. `@lookupKey` and the `override` cascade are read *inside*
several of the classifier's branches rather than instead of them, so they are columns beside the
role. Folding `lookup_key` into the vocabulary would have split two roles into four, and folding
`suppressed` in would have been wrong outright: a suppressed argument carrying its own `@condition`
still contributes that authored filter, so suppression is not absence.

**The first draft restated a rule the store already had, and the anchor cases caught it.** The
implicit node-id reading, an `ID` argument literally named `id` on a field returning a node type, was
spelled out here in full: the node-type join, the synthesis-aware named type, the key arity. Four
cases failed at once, and the reason was that `intent_node_id_instruction` already carries that
reading as its `TARGET_ID_NAME` basis, at argument sites, with the node-type resolution applied. The
draft was wrong twice: it duplicated a rule, and its duplicate had narrower reach than the original.
The arm now reads that relation and the increment is smaller for it.

What this relation does add over the instruction is the *wiring*: an instruction says the decode
applies at a site, and whether the classifier can build a filter from it is a separate question with
three exits. A `@field(name:)` beside the directive names two binding axes at once and the site
resolves to nothing. On the implicit reading only, a column of that name shadows it into nothing, a
composite key without `@lookupKey` is unwired, and a list at arity one without `@lookupKey` falls
through to the name match. That last is the one exit landing on another role rather than on silence,
and it is why two cases here differ in a list wrapper alone.

**Read cost, measured, and one measurement changed the relation.** The first shape named its
node-id term twice, once to drive the decode arm and once to anti-join the name-match arm. On the
sakila example schema it cost 12267 scans and, repeatably, about 480 milliseconds, against inputs
totalling under 2000 scans and one to two milliseconds each. Restructuring so the term is named once,
by giving the fork an arm that resolves to nothing and letting the ranked collapse consume it, took
it to 8027 scans and 16 milliseconds.

That gap is worth recording for its own sake. The scan count fell 35% and the wall clock fell
thirtyfold, so on this shape the scan count badly under-predicted what a second naming costs: what
the second naming buys is not more rows visited but a whole view tree re-expanded and re-planned. The
store's own doctrine already says a relation is expensive by being a view something reads many times;
this is that rule biting inside a single reader, and the instrument that shows it is the clock rather
than the count. That is the same lesson the `store-performance` skill took from an unrelated
consumer-schema investigation in the same window, arriving there from the opposite direction: it had
a plan's largest scan count read as a cost, proposed an index to prune it, and the index changed
nothing. A scan count is a row count. It says a rule was expanded twice; the clock says what that
cost.

Then the registration, on the restructured shape:

[cols="4,1,1"]
|===
| sakila, 194 argument roles | ms | rows visited

| `intent_argument_filter_role`, both views
| 15
| 8027

| with `intent_argument_column_match` registered
| 6
| 4713

| `intent_argument_column_match`'s own read, before and after
| 1 to 0
| 1728 to 36
|===

So `intent_argument_column_match` is registered. Refresh is one evaluation per graph, which is the
1728 a single read already paid and which this reader was paying about twice over. The doctrine's
requirement that a registration be made in the increment carrying its reader is met exactly: before
this relation the match had no reader at all, and every refresh would have bought nothing.

The index question has a different answer here than in the two preceding increments, and it is the
first registration in the store whose reader genuinely probes in: the shadow test seeks the match by
argument coordinate, once per node-id instruction. So there was a seek for an index to serve, and it
was declared and measured: 4713 scans with it and 4713 without. At this population the probing side
is smaller than the table, so H2 reads the whole table either way. The roster row says that, and says
what would change the answer.

Taken with the facet increment's result, the registration question now has three answers in three
consecutive increments, all measured: register (the argument scope), do not register (the facet
binding, where it would have made the reader twelve times worse), and register (the argument match).
What separates them is not size and not reader count but how the reader reaches the relation, which
is the thing to measure and not to reason about.

**What this increment leaves owing.** The membership fold itself, which looked from here like a
reduction over this relation and the authored `@condition` population. The section below is what
happened when that reduction was drafted: it needs a table to name, and the relation that answers
which table is keyed per argument.

### Conditions, fourth increment: the coordinate's own scope table

Drafting the membership fold is what produced this increment, the same way drafting the fold produced
the section above it. Membership is one row per coordinate-and-table, so the fold has to name a table,
and the relation that answers which table a coordinate's generated SQL binds against turned out to
answer it only where an argument existed to carry the answer. `intent_argument_scope_table` states the
field's rule and keys it per argument: both its rungs read the field's named type or the field's
`@mutation(table:)`, nothing in either is an argument's, and every argument of one field carries the
same row. An authored `@condition` on a field with no arguments filters a table that relation cannot
name, and that is not an edge case, it is the ordinary static filter.

So the rule moves to the grain it was always about. `intent_field_scope_table` states it once, and
`intent_argument_scope_table` becomes the fan-out of that relation over the field's arguments and
nothing else: one join, no rungs, no window. Its own comment now says that is its whole content. The
rows are unchanged, which the argument relation's existing anchor test asserts without being edited
and the measurement below confirms at 171 rows either way, so the four readers of the registered
target see nothing.

**What the fold still owes, which drafting it is what found.** Three of its arms need relations the
store does not have, and none of the three is this increment's to add. An input-object argument
expands into its input type's fields, which resolve at *their* coordinates; the store has no
input-field-grain column resolution outside the facet gate, so whether such an argument contributes is
not askable yet. A multi-table polymorphic root fans its filter surface out over its participants'
tables, and `intent_argument_filter_role` has no name-matched row at such a coordinate at all, the
name match resolving against a scope that a union type by definition does not have; carrying the
fan-out through would mean fanning the argument column scope out too. And a mutation's input payload
argument is not a filter argument, which the role relation's input-expansion arm does not currently
distinguish. Stating the fold over what the store can answer today would have made it quietly wrong at
three shapes rather than incomplete at none, so the fold waits and the DDL says why.

**Read cost, and a hazard comment that had gone stale.** The dedup looked like it should cost
something: the field grain is a wider domain than the argument grain here, 918 fields against 268
arguments, and the new relation answers for every field rather than only for those carrying arguments.
Measured on the sakila example schema, that is exactly what the first shape did, taking the refresh
source from 28 milliseconds to 40.

The reason it does not cost that in the end is a correction. `intent_argument_scope_table`'s comment
carried an essay against joining the type binding onto the field's stripped type expression, and for
projecting that expression into an inner derived table first: joining a derived relation on an
expression makes H2 evaluate that relation once per driving row, and the essay priced the difference
at two orders of magnitude. It was true when it was written. What retired it was not a rewrite but the
registration of `intent_resolved_type_binding` some increments later: once the relation on the far
side of that join is a table, there is nothing to re-evaluate. Nobody re-measured, and the comment
went on steering the next author, which is this one.

[cols="4,1,1"]
|===
| sakila example, 918 fields and 268 arguments, 236 scope rows | ms | rows visited

| field grain, binding joined on the expression
| 13
| 62264

| field grain, expression projected into a derived table first
| 33
| 3014

| field grain, driving fields narrowed to non-scalar named types
| 65
| 4363

| argument grain, binding joined on the expression
| 6
| 19087

| argument grain, expression projected into a derived table first (the shape that shipped)
| 27
| 2022
|===

So the shape the comment steered away from is the fastest at either grain, and the increment takes it.
The refresh source ends at 14 milliseconds where the inline argument-grain rule cost 28, halved while
computing a rule over three and a half times the driving rows.

The scan counts are quoted because they are wrong in an instructive direction. The cheapest shape here
visits twenty times the rows of the dearest, and the middle shape, which prunes the driver before the
join, is the worst of the three while visiting a fourteenth of the rows the best one does. The
increment before this one found the clock moving thirtyfold where the count moved 35%; this one finds
the count and the clock ordering the candidates in opposite directions outright. A scan count says how
much a plan touched. It does not say what that cost, and here it does not even rank.

**What the conditions conversion still owes.** The membership fold, now blocked on three relations
named above: the input-field-grain column resolution, the polymorphic participant fan-out through the
argument column scope, and the read-versus-filter fork at a mutation's payload argument. Plus the
coordinate-to-rows multimap the `rowFor` section handed forward, which is where the deferred quadratic
gets measured, and the producer conversion itself, which is where the three leaf references in
`ConditionCommands` go.

**A third instance of the same refused case, and it is now a case.** Two earlier increments declined
to declare an index on a captured relation on the grounds that a schema-wide index is a discipline
question one reader's evidence cannot settle. This one adds a different repeat: a performance comment
in the DDL that was true when measured and was retired by a later registration nobody re-ran it
against. One is an anecdote; the store now has an instrument for exactly this, the read-cost gate,
and what it does not have is any check that a written measurement still holds. That is R831, filed here rather than
left as another paragraph.

### Conditions, fifth increment: the input surface's column resolution

The first of the three relations the fold turned out to need. An input-object argument contributes
whatever its input type's fields contribute, resolved at their coordinates rather than at the
argument's, and the store had no input-field-grain column resolution at all outside the facet gate.
So the membership fold could not ask whether such an argument contributes anything, which is the one
thing it needs from that arm.

**The grain question again, and it answers differently than it looks.** An input field does not own a
table. The classifier is handed one by whatever argument reached it, and the descent into a nested
input object carries that same table down unchanged, a `@table` on an input object being captured and
ignored. So the resolution's inputs are the input field's coordinate and the table it was classified
against, and that pair is the key. Not the occurrence path, which is where the store already holds the
input surface: an input type reached three ways under one argument is three paths and one
classification, and keying on the path would hand every consumer duplicates to fold away. Not the
coordinate alone either, which is the more tempting mistake: one input type reused under two arguments
whose fields select from different tables resolves to two different columns, and a relation answering
once per coordinate would be wrong at whichever site it did not pick. Both directions have a case
asserting them.

**Three relations, and the third walk is a target view rather than a fourth hop relation.** An input
field is a `graphql_field` row on an `INPUT_OBJECT` parent, so its `@reference` elements land in the
same step relation an output field's do and `intent_field_reference_step_hop` already enumerates their
candidate joins. What was missing is only the seed: the field-site walk departs from the enclosing
type's binding and the argument-site walk from the argument's scope, and an input object binds nothing,
so this one departs from the table the consuming field handed the expansion. That departure is part of
the key here where neither sibling needs such a column, and the case that earns it is one authored path
under two arguments, resolving under one and reaching nothing under the other.

**A hazard nobody in this file had met: a recursive anchor.** The walk cost 44 milliseconds for five
rows, against three for the field-site sibling. Bisecting it: the anchor join written as an ordinary
`SELECT` costs one millisecond, and the identical join as the walk's anchor costs 39. H2 evaluates a
recursive CTE's anchor term alongside the recursive term rather than once, so a view named in an anchor
is expanded on every iteration. Confirmed by substitution, the same walk with the departure relation
snapshotted into a table costing one millisecond, which is why `intent_input_field_resolving_table` is
registered rather than the plain view it was written as.

[cols="4,1,1"]
|===
| sakila example, 917 fields and 267 arguments | ms | rows visited

| the anchor join as a plain SELECT, departure a view
| 1
| 1103

| the same join as a recursive walk's anchor, departure a view
| 39
| 1184

| the same, departure a table
| 1
| 231

| `intent_input_field_reference_step_target`, before and after
| 44 to 4
| 1196 to 243

| `intent_input_field_column_scope`, before and after
| 45 to 4
| 2317 to 483

| `intent_input_field_column_match`, before and after
| 49 to 7
| 3975 to 2141
|===

The scan count points the right way this time, which is worth saying after three increments where it
did not. It still understates: the rows visited fall about fivefold where the clock falls about
fortyfold.

**The read-cost gate was pricing its own counter, and that is the increment's other finding.** The
registration read as a regression against all three of its own readers, which is the gate saying a
registered target visits more rows than the source view. The reason was the fixture. Its entire input
surface was two fields on two mutation payloads, neither scaled with the units and neither carrying a
`@reference`, so the three new relations held a couple of flat rows and their walk held none, and what
the gate was comparing was H2 charging a table visit one scan per naming against a view whose
evaluation short-circuits and is charged none. The fixture now grows a filter input per unit in three
shapes, a plain column name, a nested input object and a `@reference`-pathed field, because those are
the three forks the relations have. With the surface scaled, all three of the registration's own pairs
go monotonic.

Three pairs remain and are pinned as findings: each of the three relations against the
`intent_field_reference_step_hop` registration, at exactly four scans more registered than
unregistered, with the clock three to six times better registered. Four namings of that relation across
an anchor and a recursive term, one scan apiece, is the counter's floor and not a cost. A fourth pair
left the pinned set at the same time, the argmapping segment binding's, whose plan stopped being the
cheaper shape once the fixture carried more arguments. That is recorded rather than quietly deleted,
because a row leaving because the fixture moved and a row leaving because a lever landed are different
facts.

The same fixture change invalidated the gate's own record of how its pinned set varies with size, so
those three figures were re-taken rather than left standing: three pairs at four units, four at eight,
nine at twelve. That is R831's subject arriving in the same increment that filed it, and handled by
hand because nothing yet does it for us.

**What this increment leaves owing.** Two of the fold's three blockers: the polymorphic participant
fan-out through the argument column scope, and the read-versus-filter fork at a mutation's payload
argument. The second is now visibly narrow rather than vague, this increment having established that
both entry points into the input-field classifier hand it the consuming field's own table, so the fork
is about what the resolved column is for and never about where it resolves. The role relation over
these three, the input-field counterpart of `intent_argument_filter_role`, is what the fold will
actually read and is the natural next increment.

### Conditions, sixth increment: which rule answers for an input field

The relation the fold will actually read. The three resolution relations under it say where a name
resolves and which column it reaches; none of them says whether a name is what the site contributes at
all. `intent_input_field_filter_role` answers that, one rung up, the way
`intent_argument_filter_role` answers it at the argument site.

**The grain carries over, and now for a second reason.** The key is again the input field's coordinate
and the table it was classified against, and here it is forced by the fork itself rather than only by
the resolution below it: two of the classifier's arms ask the catalog a question about that table, one
asking whether the written name reaches a column on it and the other whether it backs exactly one node
type. The same declaration is a name match against a table that has its column and the unbound carrier
against one that does not, so a coordinate-keyed relation could state only one of those.

**Five roles and one modifier.** `NODE_ID`, `NESTING`, `NAME_MATCHED`, `CONDITION_OWNED`, `UNBOUND`,
with `authored_condition` for the `@condition` that composes rather than owns. The interesting one is
`UNBOUND`, because it is where this relation stops being the argument relation with different words.
There, absence is uniformly a rejection's population: an argument no rule answers for is an argument
the build refuses. Here the classifier has a resolved carrier for a field whose name reaches no column,
so a site that contributes nothing of its own is a role and only the refusals are silent. And the pair
of that role with the modifier is exactly a rejection the validator already mints: a
`@condition(override: false)` asks to compose with an implicit column predicate, and an unbound field
has no column to build one from. A rejection's population stated as two columns rather than as an
absence is the reason the carrier is worth naming.

**Two facts about a contribution are occurrence-grain, and both stay where they already live.** The
enclosing `@condition(override: true)` cascade is `intent_input_occurrence_override`'s, keyed by path,
because one input field reached under two paths can sit inside an override on one and not the other.
The circular-nesting cut is `intent_input_occurrence_path`'s, for the same reason: a field closing a
cycle on one path is an ordinary nesting on another. That second one is worth stating plainly rather
than glossing, because it is a limit and not a division of labour. It is the one arm of the
classifier's fork that reads path state instead of the definition and the table, so it is the one arm
this relation cannot transcribe, and a reader walking the input surface has to terminate on the
occurrence relation rather than on this one. A reader that only wants to know how a definition
classifies against a table needs neither.

**One asymmetry with the argument site, stated because it looks like an inconsistency and is not.** A
`@field(name:)` binding beside an implicit node id is a rejection at an argument, naming two binding
axes at once. At an input field it is a fall-through to the name match, the binding simply renaming
what the column lookup looks for. Beside an *authored* `@nodeId` it is neither, the resolver never
consulting it. Three sites, three answers, all three with a case.

**A correctness bug the work surfaced.** An authored `@nodeId` whose target resolves to no node type
was falling through to the arms below it and surfacing as a name match or as the unbound carrier. The
classifier enters that arm on the directive and the `ID` type alone and never returns to the column
lookup, so the site is a rejection. Found while restructuring rather than while writing the arms,
which is the ordinary way: the second shape had to answer the same question in one expression and the
gap showed.

**The restructure that was written, measured, and thrown away.** Three of the arms ask whether the
name reaches a column, so each names `intent_input_field_column_match`, and H2 inlines a view with no
common-subexpression elimination. Three expansions of a walk looks like an obvious fold into one pass:
left-join the match once, write the fork as an ordered `CASE` over its columns. That shape is worse by
an order of magnitude.

[cols="4,1,1"]
|===
| sakila example, 917 fields and 267 arguments, 107 roles | ms | rows visited

| the shipped ranked arms
| 16
| 9592

| one pass, the match left-joined once, the fork a CASE
| 166
| 10822

| `intent_input_field_column_match` alone, for scale
| 2
| 2141
|===

The arms are cheap for a reason a scan count does not show, and it is worth carrying forward. Each
names the match under a driver of its own: the name-match arm reads it once and sequentially, and the
other two correlate into it over the small populations of `@reference`-bearing and node-id-instructed
fields. The single pass joins it once per site, on six columns, with no index to reach a view by. So
"named three times" was the wrong thing to count.

**And the two fixtures disagreed.** `DerivedReadCostTest`'s scaled fixture, measured first, said the
one-pass shape was the better one: 3922 rows visited against 4830 for the ranked arms. Its units give
every input field a `@reference`, which is exactly what makes both correlated arms unselective, so the
property that makes the arms cheap on a real schema is the property that fixture removes. When a
synthetic fixture and the shipped schema disagree by an order of magnitude the shipped schema is the
measurement, and the sequence is the lesson: the gate's fixture is built to make registrations
visible, not to be representative, and reading a shape choice off it was a mistake caught only because
the sakila control was run afterwards.

The gate's own pinned pair for this relation is 4845 against 4833, twelve rows visited, which is the
same four-scan per-naming floor the three resolution relations sit at, counted three times over. The
clocks there are a wash rather than better, sixty milliseconds against fifty-nine, which is what a
quarter of one per cent looks like on a clock.

**What this increment leaves owing.** The fold's two remaining blockers, unchanged: the polymorphic
participant fan-out through the argument column scope, and the read-versus-filter fork at a mutation's
payload argument. With the role relation in place the fold itself is next, and what it needs from the
input surface is now one join rather than a walk.

### Conditions, seventh increment: the polymorphic participant fan-out

The second of the fold's three blockers, and the one that turned out to be a hole in the relation
every other one of these increments read. A field returning a union, or an interface carrying no
`@table` of its own, is not one statement. The classifier lowers its whole filter surface once per
table-bound participant, each against that participant's own table, and mints a condition method
named after the participant rather than after the container. So the coordinate has one root per
branch, and `intent_field_scope_table` could state none of them: its upper rung joins a binding on
the field's named type, a container binds nothing, and the relation's own comment closed with the
claim that a field with no bound named type and no `@mutation` reads no table at all. That
biconditional was false for every multi-table polymorphic root in the tree, eleven of them filtered
in the sakila example alone, and the falsehood was inherited by every relation below: no argument
scope, no column resolution, no `NAME_MATCHED` role. The blocker as the fourth increment stated it
was that the role relation "has no name-matched row at such a coordinate at all"; the cause was one
rung further up than that reading suggested.

**The arm is disjoint, which is why it is not a third rung.** `intent_field_participant_scope_table`
states the population, one row per participant whose type the container holds a certain binding for,
keyed by the participant and not only by the table: the unit name a consumer mints is the
participant's, so a row carrying a table alone could not tell it which. `intent_field_scope_table`
unions the distinct tables of that relation in under a `PARTICIPANT_TABLE` basis, and unions rather
than ranks because the arm contends with neither rung. Its own precondition is that the container
binds no table, which is exactly what the upper rung requires it to have; and it declines where a
resolving `@mutation(table:)` has already answered, which is what the lower rung reads. Ranking it
would have stated a precedence the site does not have, which is the objection the argument column
scope makes about a repeated `@reference` and it applies unchanged here. The `DENSE_RANK` is
untouched, and the two grains part on purpose: the participant relation keys on the participant
because a unit is named after one, the scope relation is distinct on the table because a statement
is rooted in one, so two participants backed by one table are two rows there and one here.

**What the fan-out cost in rules was almost nothing, and that was the point.** The argument scope is
a pure fan-out of the field scope and needed no edit. The argument column scope reads it, the column
match reads that, and the role relation's `NAME_MATCHED` arm reads the match, so the population
arrives at the relation the blocker named through four relations that each already state their site
as a coordinate paired with a table. The grain widening is not new vocabulary either: the fifth and
sixth increments already put the resolving table in the key at the input surface, on
`intent_input_field_column_scope`'s reading that a departure which is not a function of the
coordinate belongs in the key. The argument surface just had no shape that exercised it.

**One window did have to widen, and a test case found it rather than review.**
`intent_argument_column_match` collapses its candidates with a `ROW_NUMBER` to take the first match
in tier-then-ordinal order, and it partitioned by the argument alone. What that collapse is for is
two columns of one table answering one name; partitioned by the argument it also collapsed the
branches, keeping whichever branch sorted first and silently dropping the others. The first
two-branch case written against the new relation returned one row where it expected two, and the
resolved table joined the partition. Worth stating as a rule rather than as an incident: a relation
whose grain widens owes an audit of every window over it, and the one sibling window that was
checked and deliberately left alone is the role relation's, which stays partitioned by the argument
because the rule an argument falls under is the same on every branch.

**Where the fan-out is stated and not yet answered.**
`intent_argument_reference_step_target` gains the per-branch departure for free and its two arity
columns do not follow: they are counted per element and position rather than per departure, so two
branches walking one element at one position land in a single partition and their candidate counts
conflate. Putting the departure in that partition would close it and would also split the candidate
set at an ambiguous mid-chain landing, which changes what the arity means on a shape the tree does
exercise. No graph in the tree writes an argument-site `@reference` at a polymorphic root, and the
branch emitter's supported-extraction list does not name one, so this is an unexercised limit rather
than an answer anybody reads, and the relation's comment says so in those terms rather than claiming
the shape works.

**The node-id family picks the fan-out up, and both readings it produces are the resolver's.** The
two inference bases of `intent_node_id_instruction` resolve against the argument scope, so an
inferred instruction at a polymorphic root names one node type per branch. At a top-level argument
that is the per-branch decode the resolver supports outright. At a nested input field it is the
divergence the resolver rejects, one leaf meaning a different id on each branch, and the two rows
carrying two node types are what makes that rejection a detection over a relation instead of a walk
check with nothing behind it. Neither is a new basis; both are the departure's grain arriving.

**The absences, restated where they were wrong.** Seven relations' comments asserted a silence they
did not own, and each now says what it answers at a coordinate with branches: the field scope, the
argument scope, the argument column scope and its match, the role relation, the argument reference
step target, and the two input-surface relations that already carried a resolving table in their
keys and needed only the sentence saying which tables those now are. The correction that matters
most is the first one, because it is the one that made a hole look like a rule.

**Read cost, and an essay that turns out to be conditional rather than wrong.** Measured on the
sakila example schema, 917 fields and 267 arguments, 128 participant rows out, five interleaved
sweeps with result reuse off so every figure carries an execution count above one and a standard
deviation.

[cols="4,1,1"]
|===
| sakila example, 917 fields and 267 arguments | ms | sd

| the membership joined onto the stripped type expression
| 47
| 3.6

| the expression projected into a derived table first, measured and not taken
| 27
| 1.9

| the same projection, driving from the 52 membership rows
| 29
| 6.0

| the shipped shape with both exclusions removed, as a floor
| 42
| 2.7

| the two ranked rungs alone, which is the relation before this arm
| 16
| 0.7
|===

The projection is worth 42%, it is the shape the fourth increment kept an essay *against*, and it
does not ship. Taking those in order. The essay is conditional rather than wrong: that increment
retired it at the field grain once the relation on the far side of its join became a table, and a
stored far side has nothing to re-evaluate per driving row, where `intent_poly_member` is a view and
the original finding therefore stands here. Join a derived relation on an expression and the
projection pays; join a stored one and it does not. The relation's comment says so and points at the
essay, because read apart they look like a reversal.

**And then the gate refused the faster shape, which is the more useful half of this.** The projection
changes which plan H2 takes for this relation and for the two above it, and under that plan the
registered read of `intent_resolved_type_binding` visits more rows than reading its source view
would, for this relation, `intent_field_scope_table` and `intent_argument_scope_table_live` alike.
`DerivedReadCostTest` asserts the opposite over every such pair, so the projection fails the
verification build, deterministically: two full builds with it red on exactly those three pairs
against one full build green immediately before it. The shipped shape is therefore the slower one,
and the 47 against 27 stands in the relation's comment beside the reason.

Two things about how that was nearly got wrong, because the sequence is the lesson rather than the
verdict. The first reading was that the three pairs were the instrument's floor, single-digit scans
flipping on noise, which the four rostered sibling pairs at four to twelve scans made plausible.
Printing every cell instead of the flagged ones refuted it outright: those three are monotonic by
2334 and 3112 scans when they are monotonic at all, and a margin that size does not move on noise.
The second reading was that they flapped one run in four, and that came from averaging across two
regimes that are not comparable. Sorted by regime rather than by outcome, both full builds agree with
each other and the two runs that passed were single-class scoped runs. Believing a difference read
off runs that were not comparable is the opening item on the store-performance procedure's own list
of retracted conclusions, and this is that mistake, made while holding the document that names it.

What the refusal actually is, stated plainly because it is a fork somebody has to decide rather than
a defect to fix here: a scan count stops tracking cost exactly when a change moves rows between a
view and a table, which is the fact model's own caveat and is what this projection does. So the gate
is enforcing the metric its own doctrine says does not rank cost, and it is doing so against a
measured 42% improvement in the metric that does. Whether a wall-clock-justified scan inversion
belongs in that gate is a discipline question one relation's evidence should not settle, so this
increment takes the slower shape and leaves the question named. Rostering the three pairs was the
other candidate and is worse: they are regime-dependent where the ten existing rows are not, so
pinning them would fail the scoped run that developers actually use.

Two controls decided the rest, and both refuted something. Driving from 52 membership rows rather
than 917 fields reads like the obvious win and measured worse, with three times the spread, so the
smaller driver bought nothing; it is recorded as a measured loss rather than left for the next reader
to re-run. And the floor control kills the diagnosis anyone would reach for first: with both
anti-joins removed the shape costs 42 against 47, so the exclusions are a tenth of it. Both children
price under a millisecond, the membership at 0.3 and the binding at 0.04, which is what says there is
no expensive child and nothing to register underneath.

**Not registered, on reader count rather than on cost, and the regression stated rather than
buried.** One relation names this one and one names that, so a registration would pay one refresh to
save one evaluation. What the arm does cost is real: `intent_field_scope_table` goes from 16
milliseconds to 59, once per refresh of the argument scope it feeds, against the 14 the fourth
increment had got that refresh source down to. That is the price of a question the store could not
answer at all, and it is the kind of number this item has twice found stated once and never
re-taken, so the trade is written where the relation is and flagged for re-measurement when the
membership fold arrives as a second reader.

**A gate that could not see the shape.** `DerivedReadCostTest`'s scaled fixture carried no
multi-table polymorphic root, so the new relation and every branch multiplicity below it would have
priced as an empty relation while the gate reported a number. That is the fifth increment's finding
arriving from the other side: there, a fixture whose units gave every input field a `@reference` made
two correlated arms unselective and sent a shape choice the wrong way. A gate blind to a shape does
not price it conservatively. The fixture now carries a per-unit union of the cluster's existing types
with one filter argument over the key column one of their tables declares on the other, so it adds
branches to price without adding a table and the name resolves on both branches rather than one.

**What this increment leaves owing.** One of the fold's three blockers: the read-versus-filter fork
at a mutation's payload argument, which the role relation's input-expansion arm still does not
distinguish. With that closed the fold has every relation it named.

### Conditions, eighth increment: what identifies a row, and what a carrier points at

The last blocker turned out to be misnamed, and finding that out was most of the increment. A
mutation's payload argument is not the opposite of a filter argument. `DELETE ... WHERE` and
`UPDATE ... SET ... WHERE` are ordinary SQL, and the generator already writes both: `DeleteRows`
carries a `Broadcast` arm for `multiRow: true` with no key covered, whose worked example filters on a
non-key column, and its comment says in as many words that non-key filter columns are legitimate
extra predicates rather than orphans. So the question is not whether a payload contributes a
predicate. It is which of its fields do, and that is decided per DML kind: a delete's admitted
columns are all predicate with the covered key as a cardinality guard, an update partitions by the
matched key, an insert has no WHERE to contribute to at all.

**Two things read wrong before they were read right, and both are worth keeping.** The first was
upsert. Reading the emitter, it looked like a fourth partition with a conflict target of its own,
built from `@lookupKey` fields, and the vocabulary was nearly widened to carry it. It is dead code:
the classifier refuses the verb at its verb dispatch, and the message it refuses with names the
blocker as the conflict target's uniqueness. The emitter's own runtime message cites `@lookupKey` on
a mutation input field, which the input resolver rejects outright as no longer supported. Reading an
emitter before establishing that its classifier reaches it is how a retired mechanism gets modelled
as a live one, and the store would have carried a vocabulary for a verb no build performs. Upsert
stays deferred and this increment states nothing about it.

The second was the grain. An update's partition follows the carrier's role rather than its column
list: own columns partition whole and a straddle rejects, a self-FK routes wholly to the written half
whatever its key membership, and a cross-table FK partitions per column. That last one is why the
destination belongs to a column and not to an input field. A relation keyed by the field would have
had to pick one answer for a carrier the walker went to some trouble to split.

**What landed, which is the input side of that and not the destination itself.** Three relations,
because each turned out to be a prerequisite of the next.

`sql_constraint` gains the enumeration position. The write surface takes the first candidate key its
columns cover, walking the primary key and then the unique keys in the order the generated model
enumerates them, and nothing on a captured constraint recovers that order. `intent_table_key_candidate`
transcribes the walk including both its projections, the column-set dedup and keeping an empty
primary key where an empty unique key is dropped. Six tables in the test catalog carry two
candidates, which is the ambiguity that made the capture worth doing rather than a tiebreaker worth
inventing.

`intent_foreign_key_node_key_lift` says whether a decoded node id lands on the referencing table or
only across the join, which is what every write rail asks before admitting such a carrier. Stated as
the absent landing rather than the present translation, which is the resolver's framing and is what
makes a multi-hop reach fall out translated without an arm of its own.

`intent_input_field_carrier_role` is the four-way vocabulary the update partition switches on.

**Three corrections the verification found, each of which had looked right.** The scope basis does
not discriminate the carriers: a cross-table node id resolves on its own table, correctly, because
the columns it binds are the lifted foreign-key columns, and the translated carrier resolves the same
way while having no local columns at all. The lift belongs to the node type rather than to the
catalog, a node key being pinned in SDL where an author pins one; reading the catalog's own node
metadata answered for no type at all on every table carrying none, which was most of them. And the
carrier relation joined the filter role to ask whether a node id applied, so a site the role relation
states nothing for lost its carrier verdict too, which a plain reference path turns out to be.

All three were caught by pricing the relation against a real capture rather than by reading the SQL
again, and the second and third by cases written to disagree: the fixtures the codebase already
carries for the alternate-key reference sort correctly now and did not before.

**A registered read the gate refused, and was right to.** `DerivedReadCostTest` flagged two new
non-monotonic pairs. One is the input-field family's, which already carries it five times: reading
the registered reference hops costs more than reading their source view. The other was the carrier
relation reading the node-id instruction's registered table where the argument-grain sibling reads
its live view. That one is answered rather than rostered, the relation now spelling the view, which
is the distinction the roster is for: a row there is a regression that has been accepted, not one
that has been noticed.

**What this increment leaves owing.** The destination itself, at the column grain, and the matched
key it needs, computed over the identity columns the carrier role now admits rather than over every
admitted column. Then the fold.

### Conditions, ninth increment: the walk the store called unwalkable

The eighth increment shipped a carrier relation and this one replaces its derivation, which is worth
saying plainly before anything else. The relation was wrong three ways, the third of them the reason
the increment happened at all, and the answer to it turned out to be one rung lower than either
increment was looking.

**What was wrong.** It named a carrier at every site a column name would resolve at, which is every
input field, so a nested input object or an unbound field whose name happens to match a column of the
table read as a carrier. It could emit one site twice, the node-id instruction being keyed by
occurrence path where the carrier is keyed by the classification, so a shared input type reached
under two arguments doubled. And it decided the two foreign-key answers by asking the catalog whether
some key of the table lifted the node type's key directly, which is a fact about the catalog and not
about the site: a self-reference short-circuited past the question entirely, so a self-FK through a
key referencing anything but the node key read as a usable carrier where every write rail refuses it.

**What the resolver actually does**, which is what the relation says now: a same-table node id with no
reference is own-row identity, and everything else walks the path, one authored or one discovered,
and is local exactly when every position of the node type's key lands on a column of the departing
table. That is one relation's subject already, the decode's key-column child, and it is stated there
per position. So the carrier relation counts two aggregates over it rather than deriving anything of
its own, and the two cannot disagree about what local means.

**Why that was not available before.** The decode's endpoint relation named a fourth navigation,
`UNRESOLVED_PATH`, for an input field carrying its own `@reference`: the reference-target views of
the time departed from a field's own binding and from an argument's scope, and an input field has
neither, so no relation walked such a path and the value existed to keep that silence apart from a
chain that legitimately lifted nothing. The fifth increment authored the input-field walk. Nothing
went back to tell the decode, so the navigation stayed, the hop relation stayed empty for those
sites, and the carrier relation reached for the catalog because the site-level answer looked
unavailable. Closing it is one join: the hop relation gains an input-field arm beside its
argument-site one, each under its own site predicate with the columns read by `COALESCE` over the
pair, and the navigation drops to three values. The case that pinned the retired value is kept
pointed at the same seeding and now pins the walk, so what changed reads as a different answer to one
question rather than as a case that went away.

**A relation authored one increment ago, retired in this one.**
`intent_foreign_key_node_key_lift` was the per-key approximation of that per-site question, and once
the site could answer for itself the relation had no reader. It is deleted rather than left standing:
its rule is real and its grain is honest, and neither is a reason for the store to carry a relation
nothing asks. The lesson is about which rung a question belongs on. "Could a key of this table carry
that node type's id?" is a fact about a catalog, and the generator never asks it; it asks where the
decode this site performs put each position, and a relation keyed by the key cannot answer that
without a reader supplying the site.

**The cost, which the gate found and a measurement located.** Reading the decode made the carrier
relation cost about five seconds on the sakila example schema where it had cost tens of milliseconds,
and `DerivedReadCostTest` said so by refusing to compare one of its cells at all. Bisecting the body
put the whole of it in one operand: the relation names `intent_input_field_filter_role` from both
arms and correlates into it, so the nine-arm ranked union is evaluated per driving row rather than
per naming. Driving from that relation instead and joining the two cheap sides onto it measures the
same, H2 inlining whichever derived relation lands on the inner side. So the operand is registered,
which is the documented lever for a rule that is right as a view and only too expensive to evaluate
repeatedly: the reader falls to about fifty milliseconds and the refresh costs what one naming
already cost. Two notes for whoever meets this next. The number that mattered was a snapshot of the
suspect into a table, which prices a registration before you write one, and it took one run. And the
gate's refusal to compare a cell is not a number to raise: it is the roster whose whole content is
that it stays empty, and it emptied itself once the registration landed.

**What this increment leaves owing**, unchanged from the eighth except that the substrate under it is
now the resolver's own: the destination at the column grain, the matched key over the identity
columns, and then the fold.

### Conditions, tenth increment: the mutations the store could not see

This increment set out to author the destination and the matched key and found that the store had no
rows to compute either one from at the coordinates that matter most. The finding is the increment;
the two relations it was supposed to deliver are still owing.

**What was missing.** `intent_field_scope_table` answers where a field's own generated SQL is rooted,
and it had two rungs: the field's named type's own binding, then a written `@mutation(table:)`. The
classifier has three. Between those two sits the write payload's data channel: an
`@mutation(typeName: UPDATE)` or `INSERT` field returning a carrier the author wrote to wrap the
written row derives its write target from that carrier's single `@table`-element data field, and the
input fields under its argument are classified against exactly that table. With the rung missing such
a coordinate had no scope row at all, so its arguments had no scope, so every input field under them
had no resolving table, and the whole input-field family this chain has been building, the column
scope, the column match, the filter role, the carrier role and the decode's departure, was blank
there. Nine increments of relations, and the shape a write surface is most often written in was
outside all of them.

**Why nine increments of tests did not catch it.** An input type is usually shared. Where
`FilmUpdateInput` is reached both from `updateFilmPayload`, which returns a carrier, and from
`updateFilm`, which returns the bound type directly, its fields resolve against the table the second
coordinate supplies. Every field-grain relation therefore has rows and looks right; only the mutation
coordinate is invisible, and nothing was keyed by the mutation coordinate yet. The silence was
exactly the shape the ninth increment's participant arm had been: a population missing outright
rather than a rule answering wrongly, which no case about the rule can see.

**The rung, and where it had to go.** `PAYLOAD_TABLE`, ranked between the named type and the written
spelling, gated on the two verbs whose write target the classifier derives from the return. It reads
`intent_carrier_data_field`, which is where the payload scan is already stated, rather than restating
any of it: demanding one data channel of element kind `TABLE` is that relation's own arity refusal
transcribed, and it also makes the rest of the scan moot, a payload with one bound channel having no
second channel to be unrecognized and no ID channel to refuse.

That relation reaches the backing closure, so it is declared far below the scope family, and the file
is executed in order. Three blocks moved rather than one arm being written around the constraint: the
participant scope, the field scope, the argument scope and the three argument-site resolution
relations now sit after the carrier family. Nothing else moved and nothing changed shape; the refresh
order is derived from recorded dependency edges rather than from declaration order, so it recomputed
itself. The alternative was restating part of the payload scan early, which is the thing this whole
chain exists not to do.

**`intent_mutation_write_payload`**, the first relation in this family keyed by the mutation rather
than by an input field: which coordinate writes, with which verb, over which table, through which
argument, plus the two cardinalities. Two verbs, because UPDATE and DELETE are the pair whose input
the walkers admit identically; INSERT resolves through a different gate and UPSERT is refused at the
verb dispatch. The write table is read through the verb, which is where the scope relation alone
would mislead: an UPDATE takes whichever of the three rungs ranked first, and a DELETE has no
return-derived rung at all, so a DELETE returning a bound type and naming no table would otherwise
read as writing that table.

Three refusals are folded into the relation's absence and the rest deliberately are not, and the line
is whose property the refusal is. Exactly one argument, of input-object type, with no `@condition`,
and no `multiRow: true` on an UPDATE, are all facts about the argument: a coordinate failing one of
them is not a payload with something wrong in it. The per-field admissibility, a list-typed carrier,
an `@condition` on an input field, an unbound or condition-owned field, a carrier that reaches its
row only through a join, is five located diagnostics at coordinates an author can be pointed at, and
folding those into one silence at the mutation would be the store telling a worse story than the
walker does. They belong at the input-field grain beside the roles that name them, which is the next
piece rather than this one.

**The cost, where the gate's metric and the clock disagree outright.** The rung makes the scope
relation read a registered target, so `DerivedReadCostTest` records three new non-monotonic cells, the
scope relation and the two above it. Registered they visit around 40,700 rows apiece and unregistered
around 24,700, so by scan count all three are regressions by nearly a factor of two; the wall clocks
are 42, 41 and 83 milliseconds registered against 73, 73 and 160 unregistered, three runs each with
the spread inside two milliseconds. The shape visiting two thirds more rows takes half as long, on
every relation and every run. That is the case the fact model's own doctrine describes and it is
worth having measured rather than argued: a scan count is a row count. The rung's own price was taken
by removing the arm, 21,653 scans and 33 milliseconds without it against 40,608 and 42 with it, so
about nine milliseconds for a question the store could not answer at all, paid once per refresh
rather than per read.

**What this increment leaves owing**, the same two as the ninth plus one the finding added: the
per-input-field refusal relation, the destination at the column grain, and the matched key over the
identity columns. Then the fold.

### Conditions, eleventh increment: the five located refusals

The tenth increment promised these and named them as owing in one sentence. This is that sentence
turned into a relation: `intent_mutation_payload_refusal`, which says why a walker-driven write
refuses one input field, located at the occurrence that reaches it.

**Why a relation and not an absence.** `intent_mutation_write_payload` folds three refusals into its
own silence, and the argument owns all three: exactly one argument, of input-object type, carrying no
`@condition`. A coordinate failing one of those is not a payload with something wrong in it. The
per-field refusals are the other kind. Each of them is a fact about a coordinate an author wrote, at a
line and column a diagnostic can point at, and collapsing five of those into one silence at the
mutation would make the store tell a worse story than the walker already tells.

**The grain is the occurrence, not the input field.** One input type reached from two write surfaces
is refused once under each, and each row names both the mutation it broke and the step inside the
payload that broke it. That is the whole reason for the choice: a consumer rendering the diagnostic
needs the write surface, and the field alone does not carry it. The occurrence path already carries
the mutation coordinate as its root, so the coordinate columns beside it are a projection rather than
a widening of the key.

**Two gates, and the vocabulary keeps them apart.** `UNCLASSIFIED` is the first and is not a walker's
refusal at all: the classifier declined the field, the validator mirror lifts that decline into a
rejection on the mutation, and the walker never runs. Why it declined stays where the rule is stated,
in `intent_input_field_filter_role`'s absence and the relations under it. Including it as a cause
rather than leaving it out is the tenth increment's lesson applied without waiting to be bitten: a
payload whose fields the classifier declined would otherwise read here as a payload with nothing
wrong in it, which is a silence of exactly the kind that increment was about.

The other five are the walkers' own, and they are one set rather than two. The DELETE and UPDATE
flatteners refuse the same five shapes at the same two gates and differ only in which typed error
carries the message: `REMOTE_CARRIER`, `CONDITION_OWNED`, `UNBOUND`, `LIST_CARRIER`,
`AUTHORED_CONDITION`.

**Ranked, not unioned.** A field can be several of these at once and the build reports the first, so a
union would hand a consumer a diagnostic the build never mints. The order is the walkers' own: the
binding switch decides `REMOTE_CARRIER` ahead of every other test, the condition-owned and unbound
carriers are variants that never reach the shape gate below them, and that gate tests the list shape
before the condition. Two cases pin the ranking directly, a remote carrier that is also list-typed and
a list-typed field that also carries a condition.

**Two decisions worth recording.** The role sits beside the cause because two causes cover two sites
each: a list-typed field and a `@condition`-carrying field are refused whether they are leaf carriers
or nesting groupings, which the walkers report as four messages. Carrying the role instead of widening
the vocabulary to seven keeps a distinction the role relation already draws from being restated at this
grain. And `AUTHORED_CONDITION` is any `@condition` of either `override` value on a field whose role is
not `CONDITION_OWNED`, because what the walkers refuse is the directive on a shape they would otherwise
admit rather than one of its readings; an `override: true` condition beside a `@nodeId` is refused
exactly as a composing one is, the classifier having given that field its own arm. That is why the
cause is not read off the role relation's `authored_condition` column, which by construction says
nothing about the `override: true` case.

**The cut.** A refused nesting is never descended into, so nothing below it is classified and nothing
below it is refused. The relation states that by emitting no row at a path any strict prefix of which
carries a refusal of its own, whichever cause the prefix carries. The circular-nesting cut is not
restated at all, being the occurrence path's and already applied to the population this drives from.

**The cost.** Two new cells, both measured rather than rostered blind, and neither a new finding. The
`intent_carrier_data_field` cell is the payload rung's disagreement one relation further down:
registered 59,099 rows in 80 to 87 milliseconds, unregistered 43,111 in 149 to 157, three runs apiece
with the spread inside seven milliseconds. Everything built over that rung inherits the cell by
construction, so the question to ask of the next such reader is whether its clock agrees with the ones
above it rather than whether its counter does. The `intent_field_reference_step_hop` cell is the
instrument's own floor again, twelve scans out of fifty-nine thousand, reached because this relation
reads the input-field family.

**What this increment leaves owing**, now two rather than three: the destination at the column grain
and the matched key over the identity columns. Then the fold.

### Conditions, twelfth increment: what the payload puts on the table, and what pins the row

Two relations and a cost. `intent_mutation_payload_column` says which columns of the write table a
payload actually puts a value on, and `intent_mutation_matched_key` reduces over it to say whether the
payload pins the row it acts on and through which key.

**The substrate first.** Both remaining relations, the matched key and the write destination, need the
same thing: the columns a payload contributes, per occurrence, per decode slot. Writing that once is
the whole reason `intent_mutation_payload_column` exists rather than each of them re-deriving it. Two
arms, one per column-resolving role, differing in arity rather than in kind: a name match is one column
at slot zero, and a node id is one row per position of the decoded key. Position is a column rather than
an implicit ordering because the UPDATE partition splits a cross-table foreign key per column, and once
split neither half's ordering recovers which slot of the decode a column came from.

Admission is the refusal relation's complement and is read from it rather than restated: no step of this
occurrence is a refused site of this mutation. Testing the steps rather than the leaf is what applies the
cut, and testing the coordinate rather than the path is exact because a refusal under one mutation is a
property of the input field and the write table, both fixed for the whole payload.

The carrier role travels on the row because both consumers fork on it and neither should join back for
it. That is not a convenience: it is what the next paragraph is about.

**The matched key, and why the verb is on the row.** The match itself is verb-neutral and is the one
thing the two walkers genuinely share, which catalog key does this input cover. The candidates and their
order are `intent_table_key_candidate`'s, so the primary key winning a tie is that relation's ranking
read ascending rather than a preference invented here. What is not verb-neutral is the covered set. A
DELETE counts every admitted column, every one being a WHERE predicate. An UPDATE counts only carriers
that pin identity, which is every carrier except a self-referencing foreign key: a self-FK's column holds
a sibling row's identity, so keying an UPDATE on it would update the wrong row. One payload over one
table therefore answers differently under the two verbs, and both halves of that pair are pinned over one
fixture, because either half alone reads as a fact about the input rather than about the verb.

A vocabulary of three. IDENTIFIED names the winning key with its rank and whether it is the primary.
BROADCAST is the DELETE that covered nothing and opted in, an arm rather than a modifier because the
emitted statement has no key predicate at all. UNCOVERED is everything else and is a rejection's
population; an UPDATE has no broadcast reading, `multiRow: true` on one being refused before a write
surface exists.

A payload with any refusal has no verdict at all, because both walkers collect every per-field refusal
and return before matching a key. A coverage verdict there would be one the build never computed, and
wrong in the direction that reads as an author error stacked on top of a real one.

**The cost, which is this increment's second finding.** Written as a plain view the column relation cost
about four seconds a read on the read-cost gate's twelve-unit fixture, and the matched key inherited that
and added half a second, where the refusal relation they are built on costs eighty milliseconds. That is
a defect and not a price, and it was worth chasing properly rather than accepting: the read-cost gate's
own runtime went from under a minute to over twenty.

Three rewrites were measured and all three refused. The occurrence cut is not the cause, the admitted set
costing 1961 milliseconds with the anti-join and 2002 without. An index on the binding target cut rows
visited from 217,000 to 42,000 and moved the clock the wrong way, 3900 against 5071, which is the fact
model's own caveat arriving a third time. And driving the two column arms from their own views, which is
the lever that takes the carrier-role join from 2005 milliseconds to 74 in isolation, made the whole rule
an order of magnitude worse: an inlined common table expression is re-evaluated per driving row of
whatever sits outside it. That last one is not a new finding; `intent_input_field_filter_role`'s own
registration records it at its own site, and this is the second confirmation.

What the plan showed is that the rows go where no rewrite reaches them: 631 nodes, the largest being the
binding join inside `intent_field_scope_table`, re-expanded because H2 inlines a view wherever it is named
and eliminates no common subexpression. So the rule was registered, which is exactly what that shape is
for, and the read cost went: the target reads in two scans and no measurable time, and the matched key
fell from 417,491 rows in 4004 to 4396 milliseconds to 200,018 in 244 to 272.

That moved the cost onto the refresh and was not the end of it, which is the part worth remembering. A
refresh runs on every capture, the reactor's own included, and the sakila example build then did not
finish at all: twenty-three minutes of CPU with no output, where the gate's fixture had said four
seconds. The remaining expansion was the same shape one relation down. The column rule probes
`intent_mutation_payload_refusal` once per candidate occurrence, so it re-evaluated a view that names
the write payload and through it the whole scope family. Registering that relation too, with an index on
the coordinate the probe writes, turns each probe into a seek; the sakila capture finishes in under
three minutes.

So the finding is one shape rather than two incidents: a per-row probe into a derived relation costs the
same either way, and whether it surfaces as a slow read or a non-terminating refresh depends only on
which side of a registration it lands on. The gate's twelve-unit fixture understated it by as much as it
takes to turn four seconds into no termination. What remains is that this pair's refresh is three orders
of magnitude above every other registration on that fixture, and it is filed as its own item because the
first thing it needs is a measurement against a real schema rather than a synthetic one.

One thing the failed rewrites left behind and worth keeping: the carrier-role join is written with the
carrier role driving rather than correlated into, which is 2005 milliseconds against 74 in isolation.
Better-shaped either way, and the same lever the input-field role relation's own comment argues for.

**What this increment leaves owing**: the write destination at the column grain, which is the UPDATE
partition with its straddle rules, its agreement obligations and its empty-SET refusal. Then the fold.

### Conditions, thirteenth increment: where each column goes, and the four ways the partition refuses

Three relations, and the middle one is why there are three. `intent_mutation_write_destination` says what
each column a payload contributes is for. `intent_mutation_write_refusal` says why an UPDATE the walker
admitted field by field is refused anyway. Both reduce `intent_mutation_payload_key_membership`, which
says which of the contributed columns fall inside the key the payload matched and how each carrier as a
whole falls against that boundary.

**Why the substrate is a relation and not a step.** The two consumers need the per-column answer and the
per-carrier one together: where a column goes turns on where its carrier falls and not only on where the
column itself does. Writing it inside either consumer states it twice, and stating it once in whichever
consumer happened to be written first makes the other read a rule shaped for someone else's question. It
also carries a claim neither consumer makes: it is what the straddle diagnostic renders, that error
carrying exactly the two column lists this relation partitions one carrier into.

The vocabulary is three values and the third is what this increment is about. WHOLE is a carrier every
column of which is in the key and NONE one no column of which is; the answer is an all-of rather than a
majority, which is what makes the remaining value a straddle rather than a lean. STRADDLE is a carrier
with a column on each side, and it is the only shape whose columns may be dispositioned apart.

The population is the write payload's narrowed twice and both narrowings are the walkers'. The verb is
UPDATE alone: a DELETE matches a key too, but that key is a cardinality guard rather than a partition, so
measuring a DELETE here would answer a question no consumer of one asks and invite a partition to be read
into a statement that has none. And the verdict is IDENTIFIED alone, a payload that pins no key having no
boundary to be measured against.

**The refusals, and why the ranking is load-bearing.** Four causes in three stages. The walker collects a
stage's refusals without short-circuiting and returns at the end of it, so a cause from a later stage on
the same coordinate is not merely unreported: it would have been computed over a partition an earlier
refusal made the walker abandon. The relation keeps, per coordinate, only the causes of the first stage
that has any. That is the eleventh increment's shape one level up: there a precedence ranked six causes at
one site, here a whole stage is kept and the rest dropped.

The first stage carries both straddles and reports them together, an author fixing one meeting the other.
`MIXED_CARRIER_KEY_MEMBERSHIP` is a carrier of the row's own columns split by the key: half of it is the
identity the statement finds the row by and half is a value it writes, which is moving the row rather than
updating it. `NULLABLE_STRADDLING_REFERENCE` is a cross-table reference in the same position, where the
split is legitimate and the spelling is not, clearing a nullable pointer writing half a foreign key and
leaving the other half where the predicate put it. The two are pinned against each other over one
reference that differs only in its non-null wrapper, because either alone reads as a fact about straddling
rather than about the spelling. The second stage carries `PLAIN_COLUMN_COLLISION`, two plain carriers
assigning one column, which would silently last-write-win; an overlap one of whose writers decodes its
value is admitted and reconciled at runtime instead, which is why the cause is about plain writers rather
than about writers. The third carries `NO_SET_FIELDS`, an UPDATE every column of which is the key it
filters on.

Where a row is located varies by cause, and that is the shape of the underlying errors rather than an
unfilled slot. A straddle is a fact about one occurrence and carries no column. A collision is a fact
about one column and names every occurrence writing it; the walker's own diagnostic quotes two of them,
which is an artefact of the order it built the assignment half in rather than a fact about the payload, so
the relation names the contributors and leaves the choice of two to whoever renders one. An empty
assignment is a fact about the statement and names neither, its position being the `@mutation`
application's rather than any field's.

**The third destination.** PREDICATE and VALUE were the vocabulary this increment was scoped with and they
are not enough. Where a straddler's in-key column is already pinned by another carrier the straddler
neither filters nor writes it: the two decoded values are compared before any DML runs. That is a
contribution with no place in the statement, and leaving it as an absence would say the occurrence
contributes nothing to that column, which is false. So CHECKED, and the agreement obligations the walker
mints are then a reduction of this relation rather than a fact beside it: each is a PREDICATE row and a
non-PREDICATE row over one column of one statement.

**One thing nearly got wrong.** Where more than one straddler claims a key column and nothing else pins
it, the walker resolves in input-field order, and its own comment says the choice is observationally
irrelevant because the agreement check runs either way. True of the running program and false of the
artefact: which claim wins decides which field's decode the emitted WHERE clause reads. So the order is
transcribed rather than left to whatever order a row arrives in, and pinned in both directions over a
mirrored fixture with the two fields declared the other way round.

How it is transcribed is a second small finding. The obvious spelling is a sort key assembled from each
step's declaration ordinal along the path, and the fact schema's collection-valued column gate refuses it:
a key of that shape is a collection folded into one value. What replaced it is a pairwise precedence, two
occurrences comparing at the outermost step where they differ. More SQL, and better, because it is asked
only of the occurrences that contend, which is a handful wherever it is asked at all. The gate was right
and finding that out cost one test run.

**The cost, and the four places one defect was hiding.** Written as three plain views these took the
read-cost gate from about a minute to not finishing inside eleven, and the first diagnosis was wrong.
It read as breadth, a cheap rule named too often, and the answer taken from that reading was to
register the substrate. Registering it cost 326 seconds of refresh on every capture, which is a build
five and a half times slower to make one relation read faster, and that is what said the reading was
wrong: a rule whose refresh costs that is not a cheap rule.

What it actually was, found by timing each relation on its own against a store captured from the
example schema and then bisecting the bodies: in H2 a derived relation on the inner side of a join is
re-evaluated once per driving row, whatever the join is spelled as. Four relations in this family each
had one. The matched key joined its own ranked candidate set back to the surface it was derived from,
so the ranking ran once per write surface. The membership rule joined the matched key per payload
column. The refusal joined a derived written-column set per matched-key row. The destination
anti-joined the refusal per membership row. None is a correlated `EXISTS`; three are ordinary `LEFT
JOIN`s and one is an inner join, which is why the shape was not recognised from the earlier increments
that had met its correlated cousin.

In milliseconds, with row counts unchanged and every rewrite checked in both directions: the matched
key 596 to 22, the membership 1488 to 33, the refusal 1364 to 1.3, the destination 5275 to 52, and one
capture of the example schema 397 seconds to 9.2. The fixes are structural rather than tuned. Fold a
self-join into one ranked pass. Drive from the smaller derived side instead of joining it in. Replace a
derived-to-derived join with a window over one pass. Replace an anti-join with a union of the two sides
and a window over the mutation. Look a value up in a table rather than in a view.

**The order between a rewrite and a registration, which this chain had not had a case for.** The
substrate registration was the right lever and the wrong first move. Priced before the rewrites it cost
326 seconds a capture; priced after them, the identical registration costs 36 milliseconds and takes
the destination from 1410 to 52. A registration prices the rule as it stands, so a rule with a
re-evaluation inside it is rewritten before it is priced, and a refresh figure taken before that
measures the defect rather than the registration. That sentence is in the registration's own `reason`,
where the next person to price one will meet it.

Two other things were measured and refused. Pre-narrowing the matched key's key columns into their own
common table expression, which reads like an optimisation, is 2512 milliseconds against the 1488 it
replaces. And an index declared on the write payload turned out to serve a seek that the matched key
stopped performing once its ranking became one pass; it is dropped, and the registry gate's roster says
why rather than leaving the next reader to rediscover it.

Worth recording about method, because it is the difference between this increment and the last one. The
first two attempts at this were a bespoke timing probe and a twenty-minute Maven loop. Taking the store
a real capture had already written, letting H2's own query statistics do the timing over interleaved
sweeps with result reuse off, and bisecting each body a common table expression at a time turned that
into a loop measured in seconds, which is what made four separate diagnoses affordable rather than one
guess defended.

**What this increment leaves owing**: the agreement obligations as their own reduction over the
destination, and then the fold.

### Conditions, fourteenth increment: which two contributions must agree, and the order nobody states yet

The thirteenth increment left the destination saying where every column of a write payload goes and
not saying which pairs of columns have to be checked against each other before the statement runs.
`intent_mutation_write_agreement` says that, as a reduction over the destination rather than a fact
beside it. Every obligation is a PREDICATE row of that relation and a non-PREDICATE row of it over
one column of one statement, so the whole of the rule is a self-join and a tie-break, and the
vocabulary it reduces over is the one the previous increment already had to invent.

**Why an obligation rather than a refusal.** Where two input fields both decode a value for one
column, a foreign key forces the two equal for well-formed input and nothing forces the input to be
well formed: both values arrive on the wire independently, from a caller the generator does not
control. So a disagreement is discoverable only when the values are in hand, which makes it a
runtime error and can only make it a runtime error. The relation states an obligation to emit a
check, and the emitters lower each row to one call naming both fields.

**The two carriers that reach the reference side.** A self-referencing foreign key routes every
column it carries to the assignment half, its columns pointing at a sibling row rather than at this
one, so a key column among them is written and checked: its row here carries VALUE, and a consumer
emitting the assignment half already covers that column. A straddling cross-table reference whose
in-key column something else pins neither writes nor filters it: its row carries CHECKED, which is
the destination value the previous increment added and the one this relation exists to consume. The
two are not a distinction this relation makes; it reads them off the destination, which is the point
of the reduction.

**The predicate side is one occurrence per column, and this is where the choice arrives.** The
destination marks every whole carrier on a key column PREDICATE, and it is right to: the statement
filters on that column and each of them supplies it. It never has to say which one the WHERE clause
reads, because both say the same thing. An obligation does have to say, because the check names one
input field on each side and naming the second whole carrier instead of the first renders a
different call at a different field. So the pairwise precedence the destination settles a contested
straddler claim with is asked here of the predicate rows themselves: two occurrences compare at the
outermost step where they differ, on the declaration ordinal of the field each takes there, and the
one nothing precedes is the side the check names. It is the same shape one level over, which is the
third time in this chain that a rule the walker resolves by list order has had to be transcribed as
an order rather than left to whatever order a row arrives in.

**One rule transcribed rather than judged.** Two occurrences of one input field name produce no
obligation at all. The walker's carrier rejects a pair whose two sides name the same field, on the
ground that a field cannot be reported as disagreeing with itself, so a payload nesting two
same-named references onto one key column gets a predicate, a checked column, and no check between
them. Whether that is the right behaviour is a live question and not this increment's; the relation
transcribes what the generator does. The test that pins it asserts the destination's two rows beside
the obligation's absence, so the case cannot pass by producing nothing to pair, which is the failure
mode an emptiness assertion invites.

**What it does not carry, and the question that belongs one level down.** No source positions. A row
names two occurrences and the destination carries a position for each at a key this relation states
in full, so carrying one of the two would invite it to be read as the obligation's position, which
no author error attaches to. And no emission order, which is the more interesting omission. The
order the checks are emitted in is the reference occurrence's place in the flattener's descent and
then its decode slot, and that is the same order the assignment and predicate halves are themselves
emitted in. It is one question at the grain of an occurrence, not three at the grain of each
partition, and answering it here would state a third of it in a place the other two cannot read.
What would answer all three is an ordinal on the occurrence itself, a rank over
`intent_input_occurrence_path` in descent order, which would also retire the pairwise precedence now
written twice. That is the next thing this family wants and it is not this relation's to add.

**The cost, and the same defect a fifth time.** Written over the destination as a view, one
evaluation of this rule was 75741 milliseconds. The shape was the one the thirteenth increment found
four instances of: a derived relation on the inner side of a join, re-evaluated once per driving row,
nested three deep here so that the multiplication compounded. Reversing the outermost join, so the
small derived pin drives and the destination is probed, took it to 12983. Registering the destination
took it to 5.4, and declaring an index on the write coordinate and column took it to 1.8. The refresh
the registration installs is one evaluation of the destination rule, 56 milliseconds per graph. Every
figure is against a store captured from the example schema, 66 destination rows over 24 write
surfaces, with the row counts checked equal in both directions after each rewrite.

Two things about that sequence are worth keeping. The rewrite came before the registration, which is
the ordering the previous increment paid 326 seconds to learn and the first occasion since to apply
it deliberately: pricing the registration against the un-reversed join would have measured the defect
and called it the cost of a view. And the index was measured rather than reasoned about. It is the
first index in this family that a reader genuinely seeks, the reversed join being a probe by
coordinate, and the registry gate's rule that an index states its reader is what forced the
measurement rather than a plausible sentence.

**What this increment leaves owing**: the fold itself, unchanged, and the occurrence ordinal the
paragraph above names, which is a small relation with three readers waiting for it.

### Conditions, fifteenth increment: the descent order, stated once

The fourteenth increment left an ordinal owing, and it was owed because the same comparison had by
then been written twice. `intent_mutation_write_destination` compares two contending straddler
claims to decide which pins a key column; `intent_mutation_write_agreement` compares two predicate
occurrences to decide which the check names. Both comparisons are the flattener's descent, spelled
as a pairwise precedence over whichever pair happens to be contending, and each was some forty-five
lines of common table expressions doing the same thing to different rows.
`intent_input_occurrence_descent_order` states the order once, as a dense zero-based rank per
occurrence within its argument, and the two relations now read the rank and take the minimum: a
join and two words in an `ORDER BY`, each.

**Why a rank and not a comparison.** A pairwise precedence answers only the question it is asked:
of these two, which is earlier. That is enough for a tie-break between contenders and not enough
for anything that wants the whole payload in order, which is what the third reader wants. An
emitter rendering one UPDATE writes its assignments and its predicates in the flattener's order,
and it has to sort a set rather than pick a winner from a pair. Stating the order as a rank answers
both shapes, and stating it at the grain of the occurrence rather than inside either partition is
what stops the answer from being a third of itself in a place the other two cannot reach.

**The rule is still pairwise, and that is not a contradiction.** The rank is a count of
predecessors: for each pair of occurrences under one argument, the earlier is the one that is a
prefix of the other, or, where neither is, the one whose field is declared first at the outermost
step the two differ at. What changed is where the pairwise comparison lives, not that it exists. A
sort key assembled out of the path would fold a sequence of declaration ordinals into one value,
which is a collection in a column and is what the collection-valued column gate refuses, so the
comparison stays a relation and the rank is derived from it once rather than asked ad hoc.

**One predicate the rule does not need.** Both retired copies compared the container type as well as
the field name at each step, defensively. The rank compares field names alone, because two paths
under one argument that agree on every field name before their first difference agree on every
container too: the first container is the argument's own input type and each later one is the
previous step's named type, so equal fields force equal containers by induction. Dropping the
predicate is not a saving worth the sentence; noticing that it was never load-bearing is, because
the induction is what makes the order total, and a total order within the argument is what makes
the rank dense.

**The cost, and the inner-side defect's mirror.** One evaluation of this rule whole is 8.6
milliseconds against a store captured from the example schema, 338 occurrences over 106 arguments.
By the reasoning of the last three increments that should make it ruinous on the inner side of a
join, and both readers put it there. It is not, and why not is the finding. H2 pushes a probe's
equality down through the view it inlines, so what each probe evaluates is a slice of the rule
rather than the whole of it: the destination refresh is 38.7 milliseconds with this relation a view
and 35.8 with it snapshotted into a keyed table, and the agreement read is 1.0 against 0.2.
Snapshotted into a table with no key on the probe coordinate the same refresh is 1846.

So the doctrine has a mirror. A registration is worth taking when the rule is expensive and cannot
be evaluated in part; where a probe's key can be pushed into the rule, the inlined view is already
doing what a registration would sell you, and an unkeyed target is fifty times worse than the view
it replaced. On these figures a registration buys three milliseconds of refresh and eight tenths of
a millisecond of read for one evaluation of the rule per capture, so this relation stays a view, and
that is a measured refusal rather than an omission.

**What was proved and how.** The two rewritten rules were checked equal to their previous selves in
both directions over the captured store, 66 destination rows and 4 obligations, and their existing
cases, thirteen and ten, pass unchanged. Those cases are what actually covers the tie-break: the
example schema contains no contested key column at all, so the set comparison over real data proves
the uncontested path and nothing else, and it is the shelf fixtures declaring two straddlers in each
order that hold the ordering steady. The new relation's own seven cases separate the descent from
the orders it agrees with on a flat payload: declaration order against name order, a grouping's
subtree lying between it and its next sibling, and two deep occurrences whose leaves are declared
in the order that would reverse the answer if leaves were what was compared.

**What this increment leaves owing**: the fold itself, still unchanged, and the emitter-side reader
of this rank, which arrives with the write emitters rather than with the store.

### Conditions, sixteenth increment: the fold, and the diff that told it what it was missing

Fifteen increments named the membership fold as what they were building toward.
`intent_condition_membership` is it: one row per coordinate and table the coordinate contributes a
WHERE clause against, which is the key the condition command relation is keyed by. Membership is
presence and nothing else, so the relation is small and the whole of its content is what makes a
row exist.

**Five sources, three of which cannot be suppressed.** A `@condition` is a method the author wrote
and asked to have called, so one on the field, one on any argument whatever that argument's role,
and one on any input field the arguments reach all contribute unconditionally. The two generated
sources, an argument whose role resolves a predicate and an input field whose role does, are the
ones the modifiers act on. What the fold does with those modifiers is read them at the grain the
classifier reads them at, which for `@lookupKey` means propagating it: a marker on the argument
consumes the whole expansion beneath it, so an occurrence under such an argument contributes
nothing at any depth.

**Suppression never removes a row, and that is worth stating because it reads like it should.**
Whatever sets an override is itself an authored `@condition`, at the field, the argument or an
enclosing input field, so it contributes under one of the three authored sources and the
coordinate stays a member. The suppression predicates in the rule are therefore provably unable to
change the answer at this grain. They are kept anyway, so that each arm is independently a correct
statement of "does this contribute", and the relation's comment says they are redundant rather than
leaving a reader to wonder.

**Four exclusions, each its own reason.** A mutation's predicates come from the write partition; a
`@service` field generates no SQL; the relay node field resolves a node and never filters; a
`@lookupKey` argument is the VALUES-and-join path. They are written as four exclusions rather than
one predicate because a reader debugging a missing row wants to know which fired.

**The participant fan-out arrived for free, which was the point of an earlier increment.** The
table side is `intent_field_scope_table` at its own grain, and that relation already carries a
`PARTICIPANT_TABLE` row per participant table, so a multi-table polymorphic root gets one row per
branch without this relation deciding anything. Membership stays a property of the coordinate: a
name resolving on one participant and not another is a build failure at that participant rather
than a membership difference, so there is nothing here to fan out.

**What made this increment different: the fold was diffed against the producer.** Every previous
relation in this chain was anchored by fixtures alone. This one has a consumer that already exists,
so its rows were compared against the `(coordinate, table)` keys `ConditionCommands.produce`
actually yields for the sakila example schema, 91 of them. The first draft was 55 rows too many and
5 too few. Four fold-side rules closed the 55, and all four are rules the classifier applies that
reading the classifier had not made obvious: the mutation exclusion alone was 37 of them, because a
mutation's write payload argument reads as an input-object filter argument until something says it
is not. A fifth fix closed two of the five: reading `graphitron_field_condition` at the input
field's own coordinate rather than trusting `intent_input_field_filter_role.authored_condition`,
which is false on a nesting field that carries a condition.

The final three misses are not the fold's, and locating them is the diff's real yield. One is
`intent_field_scope_table` having no row for a field returning an author-declared connection type,
because the rule navigates a connection through `graphitron_field_synthesis` and only a
generator-synthesised connection has one. Two are arguments whose `@reference` path ends in a
condition hop resolving no column scope, that hop naming no foreign key for the step-target
relation to carry. Both are filed with their coordinates. Six coordinates of that schema contribute
nothing here that the generator does contribute, and the relation says so.

**The read cost, and a registration decided by the same mirror the previous increment found.** One
evaluation of the fold was 6167 milliseconds. Bisection put it on `intent_field_scope_table`, a
77-millisecond view on the inner side of the final join, evaluated once per contributing
coordinate. The rewrite was tried first, as this family's own rule says it must be: reversing the
join so the scope table drives and the fold's contributor set is probed measures 68349, because the
contributor set is the more expensive of the two derived sides and reversing only moves the
re-evaluation onto it. This is the case where the rewrite is not the answer, which is worth having
met once.

So `intent_field_scope_table` is registered, and the numbers are the previous increment's finding
arriving on a second relation and pointing the other way:

| the scope table as | one read of the fold |
|---|---|
| a view | 6167 ms |
| a table with no index on the coordinate | 91045 ms |
| a table with one | 342 ms |

The refresh is 77 milliseconds, one evaluation of the scope rule per graph. The index is not a
tuning of this registration, it is the registration: without it the target is fifteen times worse
than the view it replaced, because an inlined view can be evaluated restricted and a table can only
be scanned. Last increment that reasoning argued against a registration; here the same reasoning
argues for one with an index and against one without, which is what makes it a rule rather than a
result.

**What this increment leaves owing**: the producer conversion, which is now a matter of
`ConditionCommands.produce` reading these rows instead of walking the classified fields, and the
read-side refusal relation. The conversion is gated on the diff closing: while the fold misses a
coordinate the producer emits, driving production from these rows would drop that coordinate's glue
and leave a call to a method nobody generates. The next increment closes one of the three misses. The store has none: a coordinate whose argument classification fails is
refused whole and has no filter surface, where the write partition states its refusals in two
relations of its own. On a schema that builds that population is empty, every refusal being a build
failure, so it is the fold's one structural gap rather than a wrong answer anyone can observe.

### Conditions, seventeenth increment: the type a field navigates as, stated once

The fold's diff left three coordinates the producer emits and the store could not see. One of the
three was a rule that claims to navigate connections and navigated only half of them, and closing it
turned out not to be a fix to that rule but the minting of a fact five rules had each been spelling
for themselves.

**The silence, and why it read as a working rule.** A field returning a connection has its generated
SQL rooted in the connection's element table, not in the wrapper, which binds nothing. The rule that
did that read `graphitron_field_synthesis`, the record the generator writes when the `@asConnection`
macro rewrites a field's type expression, and took the author's pre-expansion spelling. A connection
type the author writes out in the SDL has no such record, so the rule fell through to the field's
own named type, that type bound no table, and the coordinate had no scope at all. Six coordinates of
the example schema are in that population; their generator-synthesised siblings all resolved
correctly, which is what hid it. It reads as "connections work" until you look for the ones the
author named.

**Five spellings, not one rule.** The same `COALESCE` over the synthesis record was written out at
`intent_routine_return_binding`, `intent_field_column_scope`, `intent_field_participant_scope_table`,
`intent_field_scope_table` and `intent_mutation_routine_seat`. All five read the same way and all
five carried the same silence, so fixing the one the diff found would have left four spellings of a
rule that had just been shown to be wrong. This is the two-spellings-of-one-resolution defect the
fact model names, at five, and finding it is what turned a bug fix into a relation.

**What the two relations state.** `intent_connection_element_type` answers what a connection type is
a connection over, at the type's own grain, by the classifier's own structural test transcribed
rather than reinvented: a field named `edges` whose element object declares a field named `node`.
`intent_field_navigated_type` answers which type a field's own SQL navigates as, in three ranked
rungs over it: the authored expression where a macro rewrote the field's type, else the structural
connection's element, else the field's own named type. It is total over `graphql_field`, so a
consumer joins it rather than left-joining it, and a `basis` column says which rung answered so a
test can pin the rule and not only the answer.

**The refusal that had to widen with it.** `intent_mutation_routine_seat` refuses a `@routine` whose
field returns a connection, and it named the macro to do so. Now that the seat's own
`return_type_name` navigates through both kinds of connection, that refusal reads the structural
shape instead, so a routine returning an author-declared connection is refused for the reason it
should be rather than admitted and then failing to bind. Verdict counts on the example schema are
unchanged, that shape being unexercised.

**What moved, measured on a store captured from the example schema at 928 fields.** The fold covers
89 coordinates where it covered 88, and its misses against the producer's 91 fall from three to two.
Both remaining are the `@reference`-path-ending-in-a-condition-hop silence, which is a different
item. `intent_field_scope_table` gains six rows, exactly the six `CONNECTION_ELEMENT` coordinates,
and `intent_argument_scope_table` gains their arguments. `intent_field_column_scope`,
`intent_field_participant_scope_table`, `intent_routine_return_binding` and the seat relation's
verdicts are row-identical, so the navigation relation changed one relation's content and four
relations' spelling.

**Four sites read it and the fifth could not, which is the finding worth keeping.** Repointing
`intent_field_column_scope`'s named-type arm makes that arm fifty times slower: 89 milliseconds
becomes 4308. The cause is not the navigation relation. That arm carries a correlated anti-join
against `intent_authored_field_claim`, a recursive view, and it is cheap only under the plan H2 picks
when the navigated type is a literal expression over base tables. Every other form was measured and
every one flipped the plan: reaching the navigation by scalar subquery, reaching the connection
relation by scalar subquery, and spelling the `edges`/`node` shape inline as a base-table subquery
each failed to finish inside 200 seconds, and materialising the navigation into an indexed table and
joining that measured 3952. So the arm is fast by luck of a plan rather than by construction, the
repointing exposed a defect it did not create, and the rewrite is filed as its own item rather than
taken in passing.

**The registration that shipped without an index, found the same way the last one was.** The
projection is the shape two earlier essays on this family measured and refused, on the ground that
it makes `DerivedReadCostTest` fail: with the navigated type a column rather than an expression, H2
stops probing `intent_resolved_type_binding` and starts scanning it. That registration had no index.
Adding one on the key its readers actually hold closed the regression for both scope relations and
removed three pairs the pinned set had carried since before this increment. It is the previous
increment's finding on a third relation, and it is now three for three: an unkeyed materialized
table is not a faster shape, it is a differently shaped one, and which of the two wins depends on
what the reader has in hand.

**Where the counter and the clock disagree, recorded rather than resolved.** Three readers in the
write family are still counted non-monotonic through that binding. Their wall clocks did not move:
the carrier role 14 milliseconds before and after, the payload column 157 against 154, the payload
refusal 8 against 9. The relation that did move is the one not in that set, the participant scope
table at 47 milliseconds becoming 63. The pinned set carries all of it, which is what that set is
for; nothing here proposes changing what the gate asserts.

### Conditions, eighteenth increment: the coordinate group, and the arity nobody had stated

The gate this family was blocked on is closed. The membership fold's last two missing coordinates
were the reference-path-ending-in-a-condition-hop silence, which shipped separately, and that
change also replaced the hand-run producer diff with a standing one: `ConditionMembershipShadowTest`
compares the fold's `(coordinate, table)` keys against what `ConditionCommands.produce` emits glue
for, by equality in both directions, and is written to retire with the walk. So the conversion is
unblocked, and this increment takes the piece the `CoordinateIndex` section parked for it.

**Three consumers were scanning the whole relation once per coordinate**, not the two that section
recorded. `LauncherCommands.conditionRowOf` and `FetcherEdgeCommands.addConditionGlueTargets` were
the known pair; the third is `EmitPlan.requireEveryProjectionIsReachable`, whose scan sits inside a
loop over the key-projection rows, so it is the one that multiplies. The index is built where the
duplicate-key rejection already walks the rows, which is why this costs nothing to add: the walk
was happening anyway and only the map it can build was missing.

**The relation holds its own index rather than joining `CoordinateIndex`.** That carrier's contract
is that a coordinate maps to at most one row, and this key is `(coordinate, table)`, so a coordinate
maps to a list. A shared multimap carrier would have one user, and the argument that justified
`CoordinateIndex` was three relations restating one key; the same argument declines a carrier here.
`ConditionRelation` stops being a record to hold the map, the way `CoordinateIndex` is a class for
the same reason, with equality on the rows alone.

**The finding is the arity, not the scan.** The launcher's read was `findFirst()` over a key that
maps to several rows. That is safe today only because polymorphic coordinates never reach it: the
launcher's `whereOf` call sites are table fields, batched table fields and the two single-table
interface arms, and a union or polymorphic root is not among them. Nothing stated that, and nothing
would have caught it changing. So the two reads are now different methods and the difference is the
point. `rowsFor` returns the whole group, which is what a consumer folding every participant's glue
target wants. `soleRowFor` returns the group asserted to hold at most one row and **fails** when it
holds more, because a consumer emitting one glue call cannot serve a participant-expanded
coordinate, and taking the first would emit a call against whichever table the producer happened to
mint first. The refusal was written as a hard failure deliberately rather than as a documented
first-row read: if the reasoning about the call sites was wrong, the generator suite says so. It
did not; 4015 generator tests pass with the assertion live.

**What this increment leaves owing** is the producer conversion itself, and one design fork it now
runs into that the availability check did not surface. The three relations that check named are all
present, so membership and the generated arm's payload are store-stated. The authored arm is not
finished: `Predicate.Authored` carries a `MethodRef`, which is reflection material (a javapoet
`returnType`, `params` each with a `javaType`), and the store's classfile side states the pieces
(`jvm_method`, `jvm_method_parameter`, `jvm_declared_type_ref`) without stating the
assembled reference. The condition-hop work added `intent_condition_method_route`, which routes a
method between tables but does not carry its signature. So the conversion's first question is
whether `Predicate.Authored` narrows off `MethodRef` onto the fields the emitters actually read, the
way `RoutineWriteCommand` was narrowed off `RoutineChain`, or whether the store grows the assembled
reference. The narrowing is the cheaper answer and the one this item's shape suggests; it is not yet
established, and it is where the next increment starts.

### Conditions, nineteenth increment: the authored call is two strings, and the extraction chain is the real gate

**The fork the eighteenth increment left open is closed by counting the readers.** `Predicate.Authored`
carried a `MethodRef`, and the question was whether to narrow it or to grow the store an assembled
method reference. Exactly one consumer downstream of the producer reads that component:
`ConditionGlueRenderer.authoredExpr`, through `authoredCall`, which emits
`Class.method(table, locals...)`. It reads the class name and the method name. Nothing downstream of
a condition row reads the return type, the parameter list, or the declared exceptions; those are
classification-time facts, read by the validator and the argument classifier, both of which sit
upstream of the plan. So the narrowing is not merely the cheaper answer, it is a two-component
answer, and the store already carries both components verbatim as the author wrote them
(`graphitron_argument_condition.class_name` and `.method`).

**The carrier is new rather than reused, because the two method references name different things.**
`UnitMethodRef` addresses a method on a unit *we* emit: it splits the class into package and simple
name because the write step needs a landing address, and it is minted from the plan's naming
vocabulary and never parsed back out of a string. An authored class is nobody's to mint and nobody's
to place. Its name rides as the one string the author supplied, and the call site resolves it, which
is what `ClassName.bestGuess` was already doing with the model reference's `className()`. So
`AuthoredMethodRef` is the authored counterpart and states that difference in its own javadoc rather
than leaving `UnitMethodRef` to carry a second meaning.

**The borrow dial does not lose a line, and that is the dial working as documented.**
`PackageImportDirectionTest`'s `BORROWED_MODEL_REFS` keeps `MethodRef`, because `SelectTerm.HelperCall`
still borrows it for the projection family's helper calls. The dial's own note says entries survive
on their other families' accounts, which is what it is for: it names what is still borrowed, so a
family's conversion is visible rather than claimed. What is visible here is `Predicate.java` dropping
its model import outright, leaving `command/` with one fewer file that reaches into the legacy tree.

**What this increment leaves owing is the gate the method question was hiding.** The producer still
builds its `ArgBinding`s from `cf.callParams()`, and `ArgBinding` borrows `CallParam` whole, by an
explicit decision its javadoc records: it is "not a cut `Binding` type that would copy the extraction
vocabulary". The glue renderer then reads that borrowed reference in full, not in part. It walks the
`CallSiteExtraction` chain to build the extraction expression, reads the Java type and the list axis
to declare the body local, and forks on the extraction arm to decide whether the local's declaration
carries an unchecked cast. So the authored arm's remaining distance from the store is the argument
extraction chain, and it is a wider surface than the method address was. How much wider is left to
the next increment to answer by counting the readers, rather than settled here from the type's
declaration. The store states where an
argument's content binds (`intent_argument_scope_table`) and which column a name reaches
(`intent_argument_column_match`) and which rule answers for an input field
(`intent_input_field_filter_role`), but nothing states an extraction chain in the shape the renderer
walks. Whether that narrows the same way the method address did, or whether the extraction chain is
the one thing this family genuinely needs the store to grow, is the next increment's question, and
it should be answered by counting the renderer's reads the same way this one was.

### Conditions, twentieth increment: the extraction vocabulary counted, and a guess retracted

**The previous increment guessed at this surface and guessed wrong.** It called the extraction chain
"a recursive shape with a javapoet type at its leaves", one paragraph after saying the way to answer
such a question is to count the renderer's reads. The guess came from `CallSiteExtraction`'s
declaration, where the nesting arm's leaf is typed as the whole seal and several arms carry
structural payloads. Counting the readers gives a different answer, and the sentence has been
corrected in place rather than left standing beside its correction.

**What a condition binding actually reads off a `CallParam`.** The record has five components and two
derived predicates. The glue renderer reads the name, the list axis, the Java type (as the structured
`javaType` when the declared type is parameterized, otherwise the string `typeName`), and the
extraction. The command tier reads the two predicates, `readsRequestContext()` and `decodesNodeId()`,
both of which are one-line tests over the extraction arm and exist precisely so no consumer
re-derives them.

**Six of the nine extraction arms are reachable in this family, and a seventh shape is not.** The
renderer throws outright on `InputBean`, `JooqRecord` and `NodeIdDecodeRecord`, each of which its
message names as a `@service` parameter concept that is never a condition binding. It also throws on
a nesting arm whose leaf is a request-context read, because the resolver keeps context params bare
when it rewraps a nested condition's value params, so that shape is unconstructable rather than
merely unhandled.

**Of the six reachable arms, four carry nothing or exactly one string.** `Direct` and `ContextArg`
carry no payload at all; the argument name does the work. `EnumValueOf` carries a fully-qualified
enum class name. `JooqConvert` carries a column's generated Java name.

**The nesting arm is one level deep by construction, not by luck.** `NestedInputField`'s compact
constructor rejects a `NestedInputField` leaf outright, alongside requiring a non-empty path. So the
arm is an outer argument name, a path of segment names, and a leaf drawn from the same small set. It
is not a recursive shape, and the renderer's handling of exactly three leaf shapes plus one throw is
total over what can be built rather than an accident that has held so far.

**The one structurally rich payload is `HelperRef.Decode`, and it is not developer reflection.** It
references a helper Graphitron itself emits, whose call-site signature is derived from a node type's
key columns rather than from a classpath scan; that is the stated difference between `HelperRef` and
`MethodRef`. The decode registry reads its method name, encoder class, return type, output column
shape (for arity), typeId and node type name.

**So the extraction vocabulary is store-shaped, and every fact in it is already captured.** The
argument name and the nesting path are SDL facts the store holds (`intent_argmapping_pair` for the
bound parameter names, `intent_input_occurrence_path` for the descent). The list axis is a captured
type modifier. The one fact that genuinely requires a classpath is the authored parameter's declared
Java type, generics included, and the classfile side already states it twice over:
`jvm_method_parameter.declared_parameter_type` carries the type as the source declared it, and
`jvm_declared_type_ref` decomposes that declared form position by position with variance. The
node-id leaf routes to the node-id relations rather than needing a helper reference restated.

**What that makes the next increment.** Not "grow the store a new kind of fact", which is what the
retracted guess implied, but derive one relation: the per-binding extraction, keyed by the condition
row's key plus the parameter position, carrying the argument name, the list axis, the declared Java
type, an extraction kind and that kind's one payload, with the nesting arm's path segments as an
ordered child relation. The kind column's domain is the six reachable arms, and the three the
renderer rejects are exactly the ones that must not appear in it, which gives the relation a
non-vacuity check with teeth: a captured `@service` parameter concept reaching this relation is the
defect it should refuse. (The six-value domain is corrected in the next increment: it is the count
over the whole condition family, and a binding-grain relation needs the count per predicate arm.)

**The method itself is the durable finding.** Two increments in a row asked how far a command carrier
sits from the store, and both times the type's declaration overstated the distance: `MethodRef`
suggested a reflected signature where two strings were read, and `CallSiteExtraction` suggested
recursion where a construction-time invariant forbids it. Counting the readers took minutes in both
cases and the guess was wrong in both. The remaining families in this item's order (fetcher edges,
type units, projections and launchers) should be scoped that way before their conversions are
planned, not after.

### Conditions, twenty-first increment: the count was taken at the wrong grain, and the one predicate the store cannot answer

**The previous increment counted correctly and counted the wrong population.** Six of nine
extraction arms are reachable in the condition family, which is true and is what the renderer's own
throws establish. But the relation that increment set out to plan is a relation over *bindings*, and
a condition row has two kinds of binding on two arms that were split apart three increments ago.
Counting the renderer's arms without splitting by arm answers "what can appear in this method body",
when the question the relation needs answered is "what can appear on this predicate". Those are
different populations, and the difference is most of the vocabulary.

**Split by arm, the authored side carries three kinds and the generated side carries the rest.**
Every `ConditionFilter` in the tree is built from one block: the reflect pass over a `@condition`
method's parameters. Three call sites reach it, the argument-site resolver, the field-site resolver
and the input-field arm, and all three hand its `params()` straight to the carrier. That block mints
exactly three parameter sources. A `Table<?>` parameter, which the call-param projection filters out
because it is the receiver rather than a bound value. A context parameter, which becomes
`ContextArg`. And an argument-bound parameter, whose extraction comes from one helper that returns
`EnumValueOf` when the declared Java type is an enum class and `Direct` otherwise. A dotted path
wraps whichever of those two in the nesting arm, and the input-field rewrap wraps it again. Nothing
else can reach an authored predicate's binding.

**The rich arms belong to the generated predicate, and they are live there.** `JooqConvert` and the
node-id decode arms are produced where implicit column predicates are built for `@table` input
fields, and those become the generated arm's column terms. That arm's terms each carry their own
binding, by the same reasoning the carrier states: a comparison without the value it compares
against is unrepresentable. So the six-arm vocabulary is real, it is simply the generated arm's, and
a single relation covering both arms is a relation whose kind column is mostly not applicable to
half its rows.

**One predicate decides the authored arm's whole vocabulary, and the store cannot answer it for the
population it was written for.** The predicate is "is this parameter's declared type an enum class".
The classfile census states class kind directly and its domain includes `ENUM`, so an author's own
enum resolves. A jOOQ-generated enum does not: the census excludes the generated jOOQ package by
design, which the schema says in as many words at the one column that reaches a generated class at
all. And the generated enum is precisely the case the predicate exists for, the helper that
implements it being documented as jOOQ enum detection. There is no catalog-side enum relation to
join instead; the catalog census carries schemas, tables, columns, constraints, indexes and
routines, and no enum type.

**So the next increment has a smaller, sharper job than the last one described.** For the authored
arm the relation is a kind in a closed three-value vocabulary, an optional enum class name, and the
nesting wrapper's outer argument plus its ordered path, all of which the argMapping family's
existing resolution already reaches. What it is blocked on is one captured fact: the generated enum
classes, on the terms `sql_table.class_fqn` already established for generated table classes, the
catalog walk being the only census that sees that package. Capture that and the authored arm's
extraction is derivable end to end. The generated arm's binding is a separate relation over a
separate population and should be planned separately rather than folded in for symmetry.

**The count also found a live defect, filed rather than fixed here.** The input-field rewrap
replaces a parameter's extraction with a nesting arm built through the convenience constructor,
whose documented behaviour is to default the leaf to `Direct`. An `EnumValueOf` is therefore
discarded on exactly the shape this increment was measuring, and the failure is a cast at request
time rather than a build error. It has its own Backlog item; it is not this item's to repair, and
the reason it is worth naming here is that the counting pass is what surfaced it. Reading a producer
to find out what a carrier can hold is the same act as reading it to find out what it drops.

**What the method is now three for three on.** Each of the last three increments asked how far a
command carrier sits from the store and got a different kind of wrong answer from the type's
declaration: a reflected signature where two strings were read, recursion where an invariant forbids
it, and now a vocabulary whose size was right for the file and wrong for the arm. The refinement to
the method is to name the population before counting it, not just to count instead of guessing.

### Conditions, twenty-second increment: the enum the census could not see

**The blocker named last increment is captured, in one relation.** `sql_enum_binding` holds every
Java enum class a column of a generated model binds to, keyed on the source and the class, with the
database schema and enum type name beside it. That closes the gap the previous increment measured:
the authored condition arm's whole extraction vocabulary turns on whether a parameter's declared
type is an enum, the classfile census answers that for an author's own enum and cannot answer it for
a generated one, and now the catalog side does.

**Three decisions in it are worth stating, because each was a fork with a wrong-looking cheaper
answer.** The population is every enum a column binds to, not every database enum type. A Java enum
reached through a configured converter satisfies the generator's predicate exactly as a generated
one does while naming no catalog type at all, so capturing only the database-typed half would have
answered no for it, silently, in the direction that changes emitted code. The grain is the class and
not the column, because the fact is about the class: the fixture binds one enum from three tables,
and a column-keyed relation would answer the predicate three times and leave a reader to check that
the three agreed. And the database coordinate is nullable rather than a foreign key into the schema
relation, because its absence is not a resolution that failed; for the converter-bound half no
schema is the right answer.

**What it cannot see is stated rather than left to be discovered.** A generated schema class
publishes its tables and not its enum types, so the column walk is the only route the catalog
offers, and an enum type no column binds to has no row here at all. A reader therefore takes absence
as not-known-to-be-an-enum, which is the posture the classpath census already declares for the
classes it filters out. An array-typed column is absent for the same kind of reason: its bound type
is the array and not the element.

**Coverage is four cases and one of them is about the grain.** The generated enum lands with its
schema and type name read off the class rather than off the column; an enum three columns bind is
one row, asserted against the column relation's own count so the case fails if the fixture stops
being multi-bound; the population contains the three declared enums and nothing else, which is what
makes the absence reading safe; and a second capture over the same source restates rather than
collides, which is the arm that would have caught the clear round missing this relation.

**The next increment is the derivation this unblocks.** Join the authored condition method's
declared parameter type against the two censuses, scoped through the graph's own sources as the
condition-method route relation already scopes them, and the extraction kind falls out: an enum
class either census names gives `EnumValueOf`, anything else gives `Direct`, and the nesting wrapper
rides on whether the bound path is dotted, which the argMapping family's segment relations already
state. That is the whole authored-arm vocabulary, and after it `ConditionCommands` can produce the
authored predicate from store rows.

### Conditions, twenty-third increment: the authored arm's vocabulary, and the question it turned out to be two of

**The derivation the last increment specified landed as two relations rather than one, and the split
is the point rather than a tidiness.** The extraction rule is a predicate over one type: is this
parameter's declared type an enum. That predicate has more than one asker already, the `@service`
call surface running the same test before its own wire-coercion gate, and the schema's standing rule
is that a resolution with two askers is a relation instead of a subquery repeated in each of them.
So `intent_java_enum_class` states the predicate on its own, over the two censuses that each answer
half of it, and `intent_condition_param_extraction` reads it. Written as one view the enum join
would have been buried inside a rule about condition parameters, and the `@service` reader would
have had to copy it.

**`intent_java_enum_class` is a union and deliberately carries no provenance column.** The classpath
census answers by class kind and the catalog arm answers through the columns that bind to an enum,
and a class both of them name, which is what an author's own enum reached through a converter is, is
one fact and therefore one row. A reader wanting to know which census answered, or wanting the
database coordinate the generated half carries, joins the arm it cares about; a tag nothing forks on
is inventory. Absence reads as not-known-to-be-an-enum and never as known-not-to-be-one, and the two
silences are the censuses' own: a nested or package-private enum has no classpath row, and a
generated enum type no column binds to has no catalog row.

**The extraction relation is keyed on the method and not on the site, which is the grain lesson from
two increments ago applied rather than restated.** The rule does not vary by site, so a signature
written at a field, at an argument and at a path element is one set of rows. Keying it by site would
also have smuggled a site fact into it: a path-element condition has no GraphQL slots in scope and
so binds no value parameter at all, and pruning those methods here would have made the relation
answer differently for a signature written at both kinds of site. The population is therefore every
parameter of every method a `@condition` names anywhere in the graph, the five spellings of the
directive folded into one.

**Nothing in it claims a parameter is bound, and saying so is what keeps the relation honest rather
than incomplete.** Which position receives an argument, which receives the source table and which
receives a context value is decided per directive application from the slots and the context keys in
scope. That is a site-keyed question and it lands with its own consumer, which is exactly how the
parameter census itself defers the same question in its own comment. So every position of a captured
method is a row here, including the table parameter, and a reader that knows the role applies the
extraction to the positions that have one.

**The agreement with the live rule on the awkward types is the content of the rule, not a
footnote.** The generator asks `Class.forName` of the declared type's own spelling, so a
parameterised type, an array, a primitive and a type variable all fail to load and all fall to the
plain extraction. The store cannot ask that question and reads the census's decomposition instead,
which arrives at the same four answers by a different route: a parameterised type names its raw head
at the root and no enum is generic; an array names nothing at the root, its component being the next
step down; and a primitive and a type variable name no class at all. Each of those is a case, because
two rules agreeing by different routes is worth pinning rather than assuming. The array one is the
case that would have gone wrong under the obvious shortcut: reading the component would answer the
enum extraction for a parameter the generator hands the array to verbatim.

**One silence remains and it falls in a single direction.** A nested or package-private enum is
outside the classpath census, so a parameter typed as one reads as the plain extraction here where
the generator, resolving through its codegen loader, emits the enum decode. That is the scan's own
disclosed rule and the same silence the route relation's defect vocabulary already names. What
changed is that a generated enum is no longer in that set, which was the whole blocker.

**Coverage is fifteen seeded cases and one over a real capture, and the second kind catches what the
first structurally cannot.** The seeded tier says what the relations return given rows. It cannot say
whether the two captures a real run performs actually meet, and the way they could fail to is silent:
both sides document the enum's spelling as the fully-qualified binary name, and a mismatch would look
exactly like a parameter that is honestly not an enum. So the capture-backed case reads the store a
real classpath scan and a real catalog walk wrote, asserts first that the classpath census does not
hold the generated enum, and then that the extraction is the enum one anyway. It needed a fixture to
stand on: the condition stub every other condition case uses is package-private and therefore outside
the census entirely, so the signature was added beside the two that already live on the public
carrier, with a generated enum parameter and a scalar one for contrast.

**The next increment is the role, which is what stands between these relations and the producer.**
`ConditionCommands.produce` needs, per parameter, not only the extraction but which of the three
sources it takes: the table, an argument, or a context value. All three rules are site-keyed. The
table test is the one the route defect relation already performs, a declared type that a generated
table class or the bare jOOQ interface answers for; the context test is a name in the directive's own
context-argument child; and the argument test is the slot names in scope at the site, widened by the
`argMapping` overrides the pair family already decodes and by the generator's type-unique inference,
which is the part with no relation behind it yet. That inference is the piece to count readers for
before planning it, since it is also the one place the role rule reads a GraphQL type against a Java
one.

### Conditions, twenty-fourth increment: the role's first arm, and the relation it turned out to be waiting on

**Counting readers for the piece the last increment flagged found a different blocker underneath
it.** The plan was to count readers for the generator's type-unique parameter inference, on the
grounds that it is the one place the role rule reads a GraphQL type against a Java one. That count
came out at two, both in the same class. The count that mattered was one the plan had not thought to
take: the role's *table* arm asks whether a declared type is assignable to the jOOQ table interface,
and that question, asked of a closed set of interfaces, has around sixty sites across the generator.
The store already held the fact base for it, `jvm_class_supertype`, captured and read by nothing. Its
own comment predicted the derivation that would read it and set two constraints on whatever did,
both measured rather than reasoned, against a closure that had been written, cost seconds on a real
census, and been taken out again. So the increment became that closure, plus the role arm that reads
it.

**A stated prediction was wrong and the population is what corrected it.** The last increment wrote
that all three role rules are site-keyed. Two are. The table rule is not: the generator decides it
from the parameter's declared type and never consults the site, so the same signature written at a
field, an argument and a path element gives the same answer three times. That makes it method-keyed,
the same grain as the extraction relation beside it, and it is the one of the three that can be
stated before the site key shape is settled. Naming the population before counting it is what caught
this; the earlier sentence had generalised from the two rules that do read the site.

**`intent_jvm_ancestor` is the reflexive transitive closure of the declared supertype edges, and it
honours both constraints.** The first is to recurse over the pairs the rows denote rather than over
the rows: two classpath entries declaring one class are duplicate rows that would double the frontier
at every hop, so the edge arm is distinct over the graph and the two names and drops the entry. The
second is to seed from the names a consumer asks about rather than to close every pair. A consumer
only ever asks about a class some captured signature named, so that is the seed: every class named at
any position of a parameter type, a return type or a record component type. Uniform across the three
relations and across every position within a type, deliberately, because an element type is asked
about as often as a root one and a rule that admitted only some positions would be a cost guess
written into the vocabulary. Reflexive because assignability is, and because it is what lets a reader
spell one existence test where two would otherwise be needed.

**What the closure cannot answer is the half a reader has to know first.** Names on both ends and not
census rows, so a chain stops at the first name the scan never opened, and a missing pair reads as
not-known-to-be-assignable rather than as not-assignable. Two consequences will be met immediately. A
generated jOOQ table class is outside the census entirely, so it has no edges at all and reaches the
table interface through nothing. And `java.lang.Object` is absent by the capture's own choice, so
nothing here is an ancestor of everything. Beyond that, what the relation covers is reference
supertypes and only those: boxing, primitive widening, array covariance and the generic argument
rules are all outside what an extends and an implements clause say, and a consumer whose question is
really Java assignability adds the rest itself.

**`intent_condition_table_parameter` is the role arm, and its type test needs two arms for the reason
the enum relation needed two.** A generated table class is in the catalog and nowhere else; anything
else an author writes is a census class and is in the closure and nowhere else. Neither census
subsumes the other, so the reader unions them. The closure arm carries the bare jOOQ interface for
free, being reflexive, and it is the arm that admits an author's own table supertype and jOOQ's own
`TableImpl`, which is the whole of what the live rule admits beyond a generated class. The test is
not lifted into a relation of its own even though the external-field arm will ask the same question,
because the relation it would be is the closure unioned with one join: what is worth stating once is
the closure, and it is.

**Membership is the whole fact and there are no columns beyond the key.** What the parameter is named
and what its declared type is are already stated at this exact key by the extraction relation, which
is total over a method's positions, so repeating either would be one fact in two places. Two table
parameters are two rows, the generator passing the alias to each rather than picking one. And the
refusal, a method declaring no table parameter at all, is absence rather than a verdict column: it is
a fact about a method the schema named, and a defect vocabulary for a population of one would say
nothing the absence does not.

**Coverage is twenty-one seeded cases and one over a real capture, and the capture case pins the
shape rather than the answer.** The seeded tier carries the closure's own properties, the seed
discipline in both directions, and each arm of the type test separately. The capture case exists
because the seeded tier cannot say whether the two real captures meet: the census has to spell the
parameter's declared class exactly as the catalog spells the class it generated, and a mismatch would
look exactly like a parameter that is honestly not a table. It asserts the whole shape rather than
only the answer, since the answer alone would pass for the wrong reason if the closure had somehow
reached the generated class, so it pins that the census does not hold that class, that the closure
therefore carries no path from it to the table interface, and that the catalog names it anyway.

**The next increment is the role's second and third arms, and the site key they force.** The context
arm is one join to the directive's own context-argument child and is trivial in itself; what is not
trivial is that both remaining arms are site-keyed, and the sites are four shapes with four different
key widths, a field, an input field, an argument and a path element. That key shape is the thing to
settle first, because both arms and the eventual producer all hang off it. The argument arm follows,
and it is where the four-branch inference finally has to be stated: identity by name, the `argMapping`
overrides the pair family already decodes, the arity-unique and type-unique pairings, and the depth-1
name-based descent, with a reachability search behind two of them that reads input-object structure
against the scalar fixed point. That is a larger population than anything the condition arm has
needed so far and it should be scoped on its own terms rather than folded in for symmetry.

### Conditions, twenty-fifth increment: the context role

**Shipped.** `intent_condition_context_parameter`, keyed on the site (`graph_name, site, use_site,
descriptor, position`): which of a condition method's parameters take a request-context value at one
application of the directive. The site key the previous increment left open was already settled by
the tree, so this increment spent nothing on it: `graphitron_method_reference` carries site plus its
own `use_site`, and every relation in the argMapping family already joins on that pair, so the four
site widths are one two-column key.

**The rule.** A declared context key names a parameter of the signature, minus two exclusions. A
parameter that receives the source table is out before any name is compared, which keeps the roles
disjoint at a position rather than merely ordered. And an argument binding wins, the generator
asking the binding map first; that map is an authored `argMapping` pair naming the parameter plus an
identity entry for every in-scope slot no pair has claimed, so a pair claiming a slot lets a same-
named parameter fall through to the context key it also is. What is in scope at each spelling is
the site's own rule, taken from `intent_argmapping_segment_binding`, which states it for the head of
an authored path.

**Two things deliberately not taken here.** The read cost is entirely the table exclusion, and the
lever is a materialization of `intent_condition_table_parameter` rather than of the recursive
closure beneath it; the register's own reader test declines the registration until something reads
this relation, and the general form went to `docs/architecture/explanation/fact-model.adoc`.
Separately, the two capture relations this view unions owe a supertype, now on the reconstruction
roster: it belongs at capture with the uniform site key, its set having a third member in the
service site whose parameter roles are a different rule.

**The read-cost obligation, corrected for the declaration regime.** A relation authored now is a
declared relation: `meta_relation` carries its grain, owner, one grain sentence and one example, and
a gate holds the comment equal to the last two. So the measurement goes to the architecture docs and
the shaping reasoning to `rationale`, which is where that column's own comment puts them. Every
remaining increment of this item inherits that split.

**Coverage: sixteen seeded cases, one over a real capture, four mutations.** The seeded tier carries
the rule, both exclusions, the claimed-slot exception, the three scope rules, the site grain against
two applications of one signature, the overload split and the partition. The capture case asserts
the whole position list, because only there do the three readings meet: the context key from the
directive text, the parameter name from the classpath census, the competing slot from the GraphQL
document. Dropping either exclusion, the exception or the `DISTINCT` fails exactly the cases that
name it.

**Next: the argument arm.** Four branches: identity by name, the `argMapping` overrides the pair
family already decodes, the arity-unique and type-unique pairings, and the depth-1 name-based
descent, with a reachability search behind two of them over input-object structure against the
scalar fixed point. Two are relations in all but name already, so the increment's subject is the
inference. Then the refusal the three arms leave over, then the `ConditionCommands` conversion.

### Conditions, twenty-sixth increment: the site's slot scope, as one relation

**Shipped.** `intent_condition_slot`, keyed on the site plus the slot name: which GraphQL slots a
condition method's parameters may bind at one application of the directive. Three arms, one per
spelling; a path-step condition draws no row, and neither does any non-condition site the method
reference also carries. The slot's declared type rides along. It comes before the argument arm
because every branch of that arm needs this set: identity by name is a slot in scope no pair
claimed, the arity and type pairings count the unclaimed ones, and the depth-1 descent walks those
that are input objects.

**The previous increment's claim, corrected.** It said the scope rule was read off
`intent_argmapping_segment_binding` rather than spelled a fourth time. It took the rule from there
and then spelled it inline as a CTE, which is the fourth spelling.
`intent_condition_context_parameter` reads the relation now, and the write-up above says what it
did.

**The population gate decided the input-field arm.** A view naming `graphql_field` owes the choice
the macro expansion created, the author's transcription or the population the generator works
against. A slot's type is what the pairing inference reads, so this arm wants the second and joins
`intent_expanded_field`; both condition views moved below it in the DDL, H2 resolving a view at
create time.

**Read cost: flat, and the reader is where it was.** Against captures scaled to 25, 100 and 400
applications of the directive, 50 to 800 slots, reading the relation whole costs under a millisecond
at every size. Its one reader, `intent_condition_context_parameter`, costs 32, 102 and 447
milliseconds over the same three, which is the anti-join the previous increment priced, unmoved by
the refactor. So the relation needs no registration of its own and its reader's lever is the one
already recorded. One qualification on where that store came from, because the procedure's cheapest
step did not produce it: the store a build leaves in the per-user cache holds one graph at a time
and a green build unmounts its own, so after a full green reactor the file was 549 MB of free pages
around one small graph. That step is about a *failing* build, where the graph is still mounted.
Saying so in the `store-performance` skill is a change outside this item.

**Coverage: nine seeded cases, one over a real capture, three mutations.** The seeded tier carries
the three arms, the two silences, an argument-less field, the site grain over two applications on
one field, the type payload and the partition. The capture case asserts all three arms' slot sets
over a real document, which is where the two readings meet: the site's coordinate from the
directive's own capture, the slots from the SDL walk. The mutations are an argument condition that
ignores which argument it sits on, a dropped input-field arm and a field arm admitting every site;
each fails exactly the cases that name it.

**Next** is the argument arm as the increment above states it, now that the slots it pairs against
are a relation.

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
  loses its subjects with the `rejection_` family, the `walk_` family having already gone. Decide the successor while the
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
  `CorpusDocuments.coveredLeaves()` against the sealed leaf sets, and `GeneratorCoverageTest`'s
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
  `docs/architecture/principles/development-principles.adoc` (a rewrite under that file's size
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
* **The `walk_` family is gone and only `rejection_` is still shared, so the boundary is narrower
  than it was.** R743 took the membership half, deleting the two claim-domain relations with the
  gate that read them and the `derive/` projection that wrote them. R870 then deleted the family's
  last resident, `walk_type_backing_class`, and the family with it: its writer is gone and its
  projection moved to test sources, where the backing differential still runs against the walk's
  answer in memory. R740 keeps that comparison's own cleanup, plus `DemandResidue` and the
  `ClaimDomain` value the demand shadow still diffs against. So no `walk_`-shaped projection is
  left for this item's terminal deletion; what remains here is `SchemaReachability` and the walk
  itself, plus `RejectionFacts` and the `rejection_` relations once the migrated verdicts have a
  stated permanent home in the `diagnostic` union. Each family leaves with its last reader, and
  neither item deletes a relation the other still writes.
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
* Retired already, in the conditions increments: the `intent_foreign_key_node_key_lift` relation and
  its `ForeignKeyNodeKeyLiftTest`, and the `UNRESOLVED_PATH` value of
  `intent_node_id_decode_endpoint.navigation`. Both are named here so the Done-gate sweep catches a
  reader that reappears rather than only a symbol that lingers.
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
  writers (`RejectionFacts` with the `rejection_` relations; the `walk_` family and its `derive/`
  projections are already gone, R743 and R870 having drained it). `Rejection` and its error sub-seals are deliberately *not* on this line: they are
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
