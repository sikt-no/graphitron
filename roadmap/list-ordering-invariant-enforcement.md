---
id: R677
title: "Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see"
status: In Progress
bucket: validation
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-09-08
---

# Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see

## Goal

A schema that asks graphitron for an ordering it cannot deliver stops building, instead of generating
without a word and serving rows in whatever order the database happened to return them. Graphitron
states an invariant, a list result is never unsorted, and the two build-time checks that enforced it
key on a population that misses most of the ways it breaks. Three things change over the item's
three phases; two of them have shipped.

**Phases 1 and 2 have shipped, and this item is in review for those two only.** Phase 3 remains and
waits on R682, so sign-off returns the item to `Ready` rather than taking it to `Done`; the file is
not deleted at this gate.

**An ordering the coordinate cannot honour fails the build.** Shipped in phase 1. Before it, this

```graphql
type Query {
    applikasjoner(order: [ApplikasjonOrderBy] @orderBy): [Applikasjon]
        @asConnection
        @defaultOrder(fields: [{name: "NAVN"}])
}
```

generated silently and served every page in participant primary-key order, whichever direction the
client asked for, because a field returning a multitable interface (one whose implementations each
live in their own table) is read as one statement per participant and no ordering is *lowered* onto
those statements, lowering being the step that carries a schema-level declaration down into emitted
SQL. That schema now fails the build with two located messages, one per declaration, each naming the
coordinate, the container's participants, and both remedies. This is the reporter's own fallback ask
on https://github.com/sikt-no/graphitron/issues/523, and it is a **breaking change** for any schema
that compiled with that declaration; see "Compatibility". The same rule sees a second shape, the
list-returning `@routine` write, where the ordering that goes undelivered is the target table's
primary-key fallback rather than anything the author wrote; that rejection is built and held, and
R660 turns it on with the fix, so no consumer meets it from this item.

**A resolved ordering the generator cannot render stops the build too**, with a generator-bug message
rather than a schema error. Shipped in phase 2. `CallWrap.Multiset`, the command value behind a child
list read as a correlated subquery, carries the whole resolved ordering while the renderer has a
branch for one arm of it, so a client-supplied order on an inline child list was accepted and then
dropped. The confirmation recipe found that shape live, and it is now refused at production; the
lowering that empties the refusal's population is R935. The same fold ratchets the launcher relation
(the command rows saying how each root read is launched) so the ordering projections that shipped in
August cannot regress. Nothing changes for a consumer whose schema carries no inline child list with
an `@orderBy` argument.

**The never-unsorted rule starts being keyed on the question it asks.** Phase 3, which remains.
It replaces the two capability-keyed validator checks with one fact-derived verdict per list-shaped
read coordinate, so the rule stops depending on a read resolving against exactly one table. For a
consumer this shows up as coordinates that used to slip past the check now failing it, each with a
message that names which remedy applies.

Phase 3 waits on R682. The `In Review` transition happens per phase, per the multi-phase convention
in `roadmap/workflow.adoc`.
## Why today's checks miss most of it

Two checks enforce the invariant today, `GraphitronSchemaValidator.validateListRequiresOrdering` and
its paginated sibling `validatePaginationRequiresOrdering`, and both key on the same pair of signals:
the field's resolved `OrderBySpec` landing on `None`, and the field being a member of
`SqlGeneratingField`. Most known violations produce neither signal, so the check passes and the rows
ship unsorted.

The membership half is not a forgotten declaration that a reminder would fix.
`SqlGeneratingField.returnType()` is typed `ReturnTypeRef.TableBoundReturnType`, so a read whose
target is not one table cannot implement the capability at all. The checks are keyed on "this read
resolves against one table" while the question they ask is "does this read return a list". Nothing
about a list needs one table, which is why the gap exists and why no amount of declaring membership
closes it. Whatever replaces these checks has to be keyed on the question.

## The census, and the three kinds of defect in it

Six sites are known: three closed, two live, and one found while speccing and not yet confirmed.
They do not divide the way a single enforcement mechanism would need them to, which is the reason
this item is three tracks rather than one.

* Root `@routine` chain: **closed**. R704 (`routine-composition-surface-from-facts`, Done, see
  `roadmap/changelog.md`) removed it. The escape was a carve-out rather than a capability gap: the
  leaf is a `SqlGeneratingField`, and the rule named the `RoutineResolution.Chain` arm as an explicit
  exemption. Removing the exemption closed the site, and it was the only one of the five that ever
  produced `None`.
* `@splitQuery` child list: **closed at the command tier** while this item sat in Backlog.
  `roadmap/split-query-child-list-drops-default-order.md` landed its delivery on 2026-08-31, and
  `LauncherCommands.batchedResultOf` now projects the coordinate's ordering where it passed `null`.
* `@lookupKey` child: **closed by the same delivery**. `LauncherCommands.batchedLookupRow` carries
  the ordering too; the split was agreed on that item and the rest of
  `roadmap/lookup-unrealized-co-members.md` (the inline `LookupMultiset` arm, pagination at lookup
  grain) stays open there.
* Mutation routine write path: unordered step 2
  (`roadmap/routine-write-key-capture-unordered.md`, R660). Live, and the only live instance anyone
  has found is `Mutation.rentFilm: [Rental!]!` in `graphitron-sakila-example`'s own schema.
* Inline child list carrying an `@orderBy` argument: **a sixth site, found while speccing this
  item, unfiled and unverified.** `ProjectionUnitRenderer` renders a multiset's ordering under one
  branch, `m.orderBy() instanceof OrderBySpec.Fixed`, and `CallWrap.Multiset`'s own javadoc says so
  outright ("only the `OrderBySpec.Fixed` arm renders inline"). Nothing rejects the `Argument` arm on
  an inline `ChildField.TableField`: `validateTableField` checks the lookup-connection pair only, and
  the leaf's constructor bars `Argument` on routine-node paths alone. So a client-supplied order on an
  inline child list appeared to be accepted and silently ignored. **Confirmed by phase 2's own
  recipe**: an inline child list carrying an `@orderBy` argument resolves `OrderBySpec.Argument` into
  the multiset and the emitted projection carries no ORDER BY at all. Refused at production since
  phase 2; the lowering that empties the refusal is R935
  (`roadmap/inline-child-list-orderby-argument-not-lowered.md`).
* Root query over a multitable interface or union: the arm carries no ordering component at all, so
  `@orderBy` and `@defaultOrder` are accepted and discarded and rows come back in participant
  primary-key order (`roadmap/multitable-interface-query-orderby-lowering.md`).
  `QueryInterfaceField` and `QueryUnionField` carry a `PolymorphicReturnType` and declare no
  `orderBy` or `pagination` component, which is the type-level reason both checks skip them: a
  paginated multitable root with no ordering passes `validatePaginationRequiresOrdering`, the check
  written to reject exactly that shape.

Sorted by what can decide them, the sites are three different defects. Two are live, and the three
closures do not retire the classes: each closed one coordinate and left the class it belonged to
unenforced, which is the whole reason this item exists.

**Class A, an ordering is available and the coordinate's read shape cannot honour it.** Available
means either side of the resolution: an ordering the author declared, or the target table's
primary-key fallback that `OrderByResolver.resolveDefaultOrderSpec` supplies where nothing is
declared. The multitable root is this at the declaration grain: `@orderBy` and `@defaultOrder`
classify clean, generate without a diagnostic, and produce participant-primary-key order at runtime,
while `@condition` filters on the same field work. The list-returning routine write is the same
comparison with the fallback standing in for the declaration, and it is why the class is stated as
availability rather than as declaration; without the generalisation that coordinate falls between all
three classes. Both sides of the comparison are facts: what is available is captured or one join
away, and what the read shape is comes off `intent_field_scope_table` and
`intent_mutation_routine_seat`.

**Class B, no ordering is available at all.** A list-shaped read whose coordinate carries no authored
ordering and whose target offers no primary-key fallback. This is what the current checks were
written for, and it is decidable from facts too: the authored side is captured, and the fallback is a
join. A and B partition the list-shaped read coordinates between them, which is what makes the pair
exhaustive rather than two rules with a gap between them.

**Class C, an ordering resolved and the lowering discarded it.** The `@splitQuery` child and the
`@lookupKey` child were this, and both are now closed at the command tier, which leaves the class
with no live site anyone has found. **No fact at any grain can see class C**, because at the fact
tier the ordering is present. These are generator defects, not schema defects. There is no schema
for an author to fix and no source location for a message to point at.

The routine write path is **class A**, and placing it took the generalisation above. Two readings
have to be refused before the third one lands. It is not class C: `MutationField.MutationRoutineWriteField`
"carries no ordering slot, so there is nowhere for a resolved `OrderBySpec` to live even if
`@defaultOrder` were honoured", as its own item states, so nothing resolved is ever discarded and a
mechanism keyed on "an ordering resolved" cannot see the coordinate. Nor is it class B under a
narrow reading of "no ordering resolves": `Mutation.rentFilm: [Rental!]!` navigates as `Rental`,
which binds `rental`, so the coordinate takes a `NAMED_TYPE_TABLE` row in `intent_field_scope_table`
and `rental` has a primary key. An ordering is available; the write's family has nowhere to put it,
because the visible rows are step 1's captured keys re-fetched by a keyed `SELECT` that sorts by
nothing. That is class A's comparison exactly, with availability where the declaration usually
stands, and it is why phase 3's population would admit the coordinate and then verdict it `ORDERED`.

The worst case already recorded on the `@splitQuery` item is what happens when A and C meet: for a
view-backed target with no primary key, the deterministic-order validator *compels* `@defaultOrder`
and emit then discards it. The build makes a class-A demand and commits a class-C violation in the
same run. Naming the two separately is what makes that combination stateable, and refusing it is a
requirement on whatever ships.

## Why the source is facts, and not the launcher relation

An earlier framing of this item proposed re-sourcing the rule off the launcher relation's ordering
slot, on the reasoning that `ResultShape.RecordList` with an absent `Ordering` makes the whole
population visible in one place. That anchor fails three independent tests, and the third is the one
the earlier framing had already half-found on its own.

**Pipeline position.** `GraphQLRewriteGenerator.runPipeline` pronounces the verdict and throws before
it calls `EmitPlan.produce`. The launcher relation does not exist until the build has already decided
the schema is valid. Worse, the plan never runs on the other two entry points at all: `validate()`
and `buildOutput()` are both load, classify, capture, validate, with no plan anywhere. A rule sourced
off commands would be absent from `graphitron:validate` and invisible in the editor. A store-derived
rule reaches all three, because the capture window runs ahead of the verdict on every path
(`FactCapture.detect` inside `GraphQLRewriteGenerator.captureAndRead`, whose continuation runs the
validator and fuses `StoreDetections.violations()` into the error stream) and the `diagnostic` view
already carries `intent_authored_claim_conflict` as a view arm beside the walk's own errors.

**Tier direction.** `PackageImportDirectionTest` pins `facts` below `command` below `plan` below
`render`, stating on the facts leg that "facts sit below commands; the corpus will read facts without
a plan". The validator lives in `no.sikt.graphitron.rewrite`. Making it read commands adds a consumer
of commands that is neither a planner nor a renderer, which is the seam
`roadmap/planners-read-facts-emitters-read-commands.md` exists to close. That item's closer names two
import dials, `plan` and `render`; a build-time rejection sourced from commands would need a third.

**Population.** The launcher relation excludes the multitable root by the same keying the current
checks use one tier down: `LauncherCommands.verdictOf` anchors on the target-axis fact
`TargetShape.Table`, and the multitable family carries `Interface` or `Union`, so it takes no launcher
row and its UNION-ALL stage belongs to the polymorphic-emit family. Re-sourcing off the launcher
relation would leave the site exactly as invisible as it is today while the framing read as having
closed it.

At the fact grain there is no carve-out to be outside of, because there is no capability interface.
Every ingredient is already captured: `graphql_field.is_list` for list-shapedness,
`graphitron_default_order_entry` and `graphitron_default_order_field_entry` and
`graphitron_order_by_entry` for the authored ordering, `intent_bound_table` joined to
`sql_primary_key` for the fallback, `intent_field_chain_terminus` for the routine terminus that has
no primary key to fall back on, and `intent_mutation_routine_seat` for the write shapes, whose
population is every mutation-root field carrying `@routine` and whose `verdict` column already sorts
its values into the ones an author fixes and the ones the generator owes an emitter. The polymorphic
root is an ordinary row of every one of those relations.

## Absence is not the complement's claim

The store forbids the shape a "population observable in one place" framing invites.
`intent_field_separate_fetch`'s relation comment ends: "A reader may say a field with a row is
separately fetched, and may not say a field without one is inlined." The never-unsorted invariant is
an absence check, so building class B as a `NOT EXISTS` against an ordering fact table would violate
that rule directly.

Class B has to be a positive population carrying a verdict, on the `intent_resolved_field_demand`
model: rows are the list-shaped read coordinates of the classification domain, one verdict per row
from a closed vocabulary, and a coverage gate counting resolved rows against the population so the
construction stays honest. That view is the item's one piece of genuine modelling work; everything
else it needs is already derived.

## Sequencing: one track left, and what it waits on

Deliberately no `depends-on`. Classes A and C shipped independently of each other and of everything
else, as phases 1 and 2; class B is what remains.

**Class B lands after the launcher step of
`roadmap/planners-read-facts-emitters-read-commands.md`**, or alongside it. Deciding "does an
ordering resolve for this coordinate" from facts is the same derivation `LauncherCommands` performs
today and that item moves store-side; doing it twice from two sources is how the two ends drift.
Whether class B can go green before every per-site fix lands, or needs a temporary exemption list,
is a Spec question. An exemption list is acceptable only if each entry names the item that removes
it.

Class C was not blocked on class B and was not sequenced behind it, which the delivery bore out. The
two read different things: class C compares a resolved ordering against the command row that was
supposed to carry it, which needed nothing new; class B asks whether an ordering is available at all,
which is the fact-tier question and the one that waits.

## Notes the plan holds to

Each one is a constraint the phases below are built against.


- **The three classes are three mechanisms and must not be merged.** The earlier framing's whole
  error was treating class B and class C as one re-sourcing. They read different signals, at
  different tiers, with different severities and different audiences: A and B are author-facing
  rejections with a coordinate and a location, C is an internal invariant whose failure is a bug
  report against the generator.
- **Class A is the same shape as the fan-out verdict** in `roadmap/reference-path-fanout-verdict.md`:
  a build-time verdict comparing what is available against what the pipeline can honour, decidable
  from captured facts rather than from a traversal. One difference to keep straight: the fan-out
  verdict is advisory, because a multiset may be what the author wanted, while this one is a hard
  rejection, because nothing can honour the ordering. Same derivation, different severity.
- **The polymorphic-emit family has no owner on either side of this item.** Site 5 lives in
  `MultiTablePolymorphicEmitter`, which `roadmap/planners-read-facts-emitters-read-commands.md`
  names as its large zero-dispatch leaf reader and does not give a command relation to in its
  family-by-family order. So class A covers the *declaration* at that coordinate and nothing yet
  owns the *lowering*. Whoever specs this should not assume that item will mint the relation; if the
  lowering matters on a schedule, say so on
  `roadmap/multitable-interface-query-orderby-lowering.md` rather than inheriting it here.
- `docs/manual` should say what the invariant guarantees and where it does not hold yet. Today the
  Sorting and polymorphic-query pages state no limitation, which is how the consumer arrived at a
  runtime surprise.
- One statement per grain applies to the class B view as it does to every store read: the population
  is derived by one statement over the whole schema, never a query per coordinate. The rule and its
  instrument live on `roadmap/planners-read-facts-emitters-read-commands.md`.

## Phase 1, class A: reject an available ordering the coordinate cannot lower

Shipped 2026-09-08, at the commit this note is being written in. `intent_field_unlowerable_ordering` is the rule, `UnlowerableOrderings` the
decode, `UnlowerableOrderingRejectionRows` the capture-cadence writer, and the `diagnostic` view
gains an eighth arm over the pair. Six things the delivery settled that the plan did not, each a
premise for phase 3 and for whoever picks up R660:

* **The view is keyed on availability exactly as planned, and the two inert pairings fall out of
  construction rather than out of a filter.** `PRIMARY_KEY_FALLBACK` cannot fire on the
  participant arm because that shape's own precondition is that the navigated container binds no
  table, and `ORDER_BY_ARGUMENT` cannot fire on the write arm because `READ_SURFACE_ON_WRITE`
  already refuses `@orderBy` there. So the availability side is stated once and joined against
  both read shapes, and neither arm carries a mask the other needs.
* **`PRIMARY_KEY_FALLBACK` locates on the `@routine` application, not on the field.** The plan said
  the field's own position, which meant reading `graphql_field`, and
  `ExpandedPopulationReaderGateTest` refuses a new view naming the transcription: that gate makes
  the authored-versus-expanded choice an author's rather than a reviewer's, and its roster only
  shrinks. The read-shape side supplies the position instead, `intent_mutation_routine_seat`
  carrying the write's own `@routine` position, and the route side reads `graphitron_field` for
  `is_list`. That is the better location as well as the permitted one: the fallback is written
  nowhere, so the nearest thing an author wrote is the directive that made the coordinate a write.
* **The rejection table carries `available_via` and `argument_name` beyond the plan's column
  list.** The view is keyed per availability route and the `diagnostic` arm joins these rows to it,
  so a rejection keyed on the coordinate alone would fan two locations onto one message.
* **The writer runs after the materialization refresh, not beside the flush.** The view reads
  `intent_field_scope_table`, which is materialized, so a call in `FactCapture`'s load transaction
  would render the previous capture's rows. It runs in a transaction of its own after both refresh
  cadences and after the analyse. The build path never reads these rows, minting the same value off
  the view directly, so a reader arriving in that window sees the diagnostic missing rather than
  wrong. That is a cadence no sibling writer has and the relation's comment says so.
* **Both relations are declared in `meta_relation` rather than added to the undeclared roster**,
  which is what `MetaDeclarationGateTest` requires of a new relation and what forces the short
  relation comments: a declared relation's `COMMENT ON` is exactly its grain sentence plus its
  example, and the essay lives in `meta_relation.rationale`. Two new `meta_grain` rows come with
  them. `MaterializeRegistryGateTest`'s hand-written roster gains the rejection table, and
  `FactCaptureAgreementTest` registers both on the claim-conflict pair's `DERIVED` arm.
* **The write arm's verdict case lives at the graphitron tier, not the model tier.** The plan put
  one case per verdict in `FieldUnlowerableOrderingTest`, and no seeded store can reach
  `intent_mutation_routine_seat`'s one emitting verdict: it turns on a chain landing on a catalog
  routine's own result. `UnlowerableOrderingsTest` states it against captured SDL and the test
  catalog, which is the tier `MutationRoutineSeatTest` states that relation itself at, and the
  model-tier file says so where a reader would look for the missing case.

## Phase 2, class C: pin the multiset's two ends, and ratchet the launcher relation

Shipped 2026-09-08, in the same commit as phase 1. `LauncherCommands.requireResolvedOrderingsAreLowered` is the fold, called from
`EmitPlan.produce` once both relations exist, with `LauncherCommands.orderIsEntailedBySource` as the
one home of the exemption set. Four things the delivery settled:

* **The sixth census site is real.** The confirmation recipe ran before the invariant was written:
  an inline child list carrying an `@orderBy` argument resolves `OrderBySpec.Argument`, hands it to
  `CallWrap.Multiset`, and the emitted projection carries no ORDER BY at all. It is refused at
  production now, and R935 (`roadmap/inline-child-list-orderby-argument-not-lowered.md`) is the
  lowering that empties the refusal's population. No exemption entry was needed, because the shape
  is not exercised anywhere in this reactor: a consumer meets the throw, our corpus does not, which
  is the whole reason the invariant is production rather than a test.
* **The launcher half was shown able to fail before it was trusted.** Reverting
  `batchedLookupRow`'s ordering projection to the `RecordList(null)` it passed before 2026-08-31
  makes the fold throw at `Film.actorsLookupSplit` naming `CorrelatedLookupChain`; restored, and the
  fixture coordinate that caught it is kept in `LauncherOrderingClosureTest`'s corpus.
* **The exemption switch has three arms, not four.** Round 2's non-blocking note was right that the
  schema-free unit-tier assemblies are no arm of `LaunchSource`: `serviceReentryRow`'s source is
  `ProjectedReentry` and already exempt. The switch names `KeyedLookup`, `ProjectedReentry` and
  `DiscriminatedReentry` and returns false for the other nine.
* **The corpus run found no empty-`Fixed` absent slot**, which round 2's other note asked the
  implementer to check rather than assume: `orderingOf` returns null on an empty `OrderBySpec.Fixed`
  as well as on `None`, and no non-exempt list row in the closure test's corpus or in the example
  schema's own build reaches the fold with one. So the four `RecordList(null)` sites are the whole
  absent-slot population, as round 2 read them.

## Phase 3, class B: the ordering-availability view

The one piece of genuine modelling work, and the only track that waits. It answers one question, "is
an ordering available at this list-shaped read coordinate", over a population that needs no capability
membership; whether an available ordering is then honoured is phase 1's question and not this one's.

**Shape.** A positive population carrying a verdict, on `intent_resolved_field_demand`'s model, because
absence is not the complement's claim (see the section of that name above):

* `intent_field_ordering_rule (graph_name, type_name, field_name, rule)`, one literal per arm, arms
  unmasked against each other: `DEFAULT_ORDER` where the coordinate carries `graphitron_default_order_entry`,
  `ORDER_BY_ARGUMENT` where an argument carries `graphitron_order_by_entry`, `PRIMARY_KEY_FALLBACK` where the
  read's target table has a `sql_primary_key`, `PARTICIPANT_KEY` where the read fans out per
  participant and the synthetic key orders it, `INPUT_SCATTER` where the visible order is the input
  order (the keyed shapes phase 2 exempts, stated here as a positive rule rather than an absence).
  `INPUT_SCATTER` carries the qualification `requiresReFetch()` lacks and that
  `roadmap/routine-write-key-capture-unordered.md` records against it: the rule holds only where the
  upstream order it scatters onto is itself defined, so a scatter onto a routine's unordered key
  capture is not an arm of it.
* `intent_resolved_field_ordering (graph_name, type_name, field_name, verdict, rule)`, the reduction:
  `ORDERED` with the winning rule in declared precedence order, or `UNORDERED` over the list-shaped read
  coordinates no rule covers. That second population is the rejection.
* A coverage gate counting resolved rows against the population, on the shadow agreement's terms, so
  the construction stays honest rather than quietly shrinking to what the rules happen to answer.

The population is "list-shaped read coordinate": `graphql_field.is_list`, in `intent_type_domain`, with
a row in `intent_field_scope_table` (any basis) or a routine terminus in
`intent_field_chain_terminus`. Note what that population does **not** need: membership in a capability
interface. That is the whole point of the track: `SqlGeneratingField` stops being the gate, so a
list-shaped read that resolves against no single table is inside the rule rather than invisible to it.

What the wider population does not buy is the routine write path, and the plan says so here rather
than letting the phase's test list imply otherwise. `Mutation.rentFilm` takes a `NAMED_TYPE_TABLE` row
on `rental`, `rental` has a primary key, so `PRIMARY_KEY_FALLBACK` fires and the reduction reads
`ORDERED`. That is the correct answer to the question this view asks; the coordinate's defect is that
the available ordering is not delivered, which is phase 1's `KEY_CAPTURE_SCATTER` verdict.

**The dependency, and the reopen trigger.** R682 (`planners-read-facts-emitters-read-commands`, In
Progress) moves the launcher relation's derivation store-side. Deciding "does an ordering resolve for
this coordinate" is the same derivation `LauncherCommands` performs today, and doing it twice from two
sources is how the two ends drift. So phase 3 does not start until R682's launcher step has landed, and
if that step lands the ordering derivation under a different name or grain than this section assumes,
**this item returns to `Spec`** before phase 3 is implemented rather than being reconciled in flight.
Phases 1 and 2 are unaffected by that trigger: neither reads a launcher fact.

**What phase 3 retires.** `validateListRequiresOrdering`, `validatePaginationRequiresOrdering` and
`listOrderingDiagnostic` are replaced, not kept beside the view: two rules with two populations is the
drift this item was filed against. The `requiresReFetch()` exemption goes with them, replaced by the
`INPUT_SCATTER` rule, which is the same carve-out stated positively and per coordinate rather than per
capability.

**Whether it can go green.** Open, and honestly so: it depends on how many coordinates the wider
population turns out to catch, which is measurable only once the view exists. An exemption list is
acceptable if each entry names the item that removes it, and unacceptable otherwise. Take the count
before writing the rejection, on the sakila example schema and on the fixture corpus.

## Tests

Per phase, and each named test is what answers "how do we know the item is complete".

**Phases 1 and 2, shipped.** Four files, and what each answers:

* `graphitron-model/src/test/java/no/sikt/graphitron/model/intent/FieldUnlowerableOrderingTest.java`,
  the view's own algebra over a seeded store, on `ArgmappingProjectionDefectTest`'s pattern: the two
  declaration routes, the both-declarations coordinate yielding two rows, two `@orderBy` arguments
  yielding two rows (the reason the argument is in the grain), the child coordinate returning a
  container, the interface arm beside the union arm, and four boundaries. The load-bearing boundary
  is the multitable read with nothing declared, which is quiet and where the fallback route's
  inertness on that arm is asserted rather than assumed: both participants have primary keys, so a
  rule keyed on "the read's target table has a primary key" would have fired.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/UnlowerableOrderingsTest.java`, the
  decode over captured SDL and the test catalog: the message and the directive-own location for the
  reported shape, the argument arm's own remedy, the union arm's wording, the domain narrowing (a
  violating coordinate outside `intent_type_domain` mints no build error while the view keeps its
  row), and the `KEY_CAPTURE_SCATTER` pair of assertions R660 flips, a view row on the
  `PRIMARY_KEY_FALLBACK` route and no `ValidationError`.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/UnlowerableOrderingRejectionPipelineTest.java`:
  the reported schema fails the build with both messages, the same paginated multitable root with no
  declaration builds clean, and the stored rejection reaches the `diagnostic` view as two rows with
  `actionable = FALSE`. The last is what the editor reads; without it the phase would ship a build
  error and an editor that stays silent.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/methodgraph/LauncherOrderingClosureTest.java`
  (beside `LauncherRelationClosureTest`, reading the carried plan and never a re-derivation): the
  multiset comparison over the corpus, the case at the shape the confirmation recipe turned up, the
  launcher ratchet, and the exemption's membership read off the production switch rather than
  restated. Its corpus carries one coordinate per list-returning launcher family, `Film.actorsLookupSplit`
  among them, which is the coordinate the revert-and-restore exercise threw at.

**Phase 3.**

* The view's unit-tier test, one case per rule arm plus the `UNORDERED` reduction, and the coverage
  gate as its own assertion.
* The coordinates today's checks cannot see, each as a case that fails before the phase and passes
  after. Which shapes those are comes out of the count below rather than being named here: the
  population the phase adds is the list-shaped reads outside `SqlGeneratingField`, and which of them
  have no ordering available at all is exactly what nobody has measured. The list-returning routine
  write is not among them, phase 1 owning it, and neither is the undeclared multitable root, which
  `PARTICIPANT_KEY` verdicts `ORDERED` and correctly so.
* Every existing case in `ListRequiresOrderingValidationTest` re-pointed at the new rule, with the
  message changes recorded. That file is the regression surface for the rule being replaced, and it
  is where a reviewer checks that the replacement covers what it replaced.
## User documentation (first-client check)

Shipped with phase 1, at both pages the draft named.

**`docs/manual/how-to/sort-results.adoc`, "Sort across polymorphism".** The section as written
described ordering across a multitable union as working and warned about a hazard that cannot arise
("every participating table must agree on the order's shape"), so a reader following it wrote a
schema that silently ignored their order. It now says what is true: the emitter sorts on a synthetic
key built from each participant's primary key, a declared ordering is not lowered, and declaring one
fails the build. The single-table paragraph beneath it was accurate and stays.

**`docs/manual/how-to/polymorphic-types.adoc`, "Constraints".** One bullet, in the register the
existing bullets use ("is rejected at build time as a deferred capability" was already the house
phrasing there), cross-linking the sort page.

Phase 3 adds no page: it changes which coordinates the existing deterministic-order rule catches, and
`sort-results.adoc`'s "Constraints and pitfalls" list already states the rule. Re-read that list at
phase 3 and correct any bullet the new population makes wrong. Phase 2 has no user surface, and
neither does phase 1's held `KEY_CAPTURE_SCATTER` verdict; the page that shape needs (the routine
write's list return and what it does or does not guarantee about order) belongs with R660's delivery,
which is what decides the sentence.
## Compatibility

Phase 1 breaks builds that passed before it. That is the intent, and it is what the reporting consumer asked
for, but it is a real upgrade cost for anyone who has `@defaultOrder` on a multitable field and has not
noticed it does nothing. Three consequences for the delivery:

* The `changelog.md` entry says so explicitly, in the "what a consumer has to do" register rather than
  as a feature note. It is owed at the Done gate, which this item does not reach until phase 3, so
  the entry when it comes has to carry a breaking change that shipped two phases earlier.
* The message must carry the remedy, not just the refusal. An author who hits this needs to know that
  removing the declaration loses them nothing they currently have.
* When `roadmap/multitable-interface-query-orderby-lowering.md` lands the lowering, the rejection's
  population empties on its own: the rule keys on the coordinate's read shape, so nothing has to be
  un-written. Said on that item, in a section of its own, so its implementer knows the rejection is
  theirs to retire.
* The `KEY_CAPTURE_SCATTER` verdict adds no upgrade cost while its rejection is held, and the whole
  of it when R660 flips the arm: any schema with a list-returning `@routine` write then stops
  building unless R660's fix gives that shape an order to deliver. Said on R660, so its implementer
  picks between the two deliberately rather than meeting it in a failing build.

## Cost

**Measured.** `intent_field_unlowerable_ordering` reads in 53 milliseconds and 25053 scans on
`DerivedReadCostTest`'s twelve-unit fixture. The seat relation is the whole of it, as the plan
suspected: `intent_mutation_routine_seat` reads in 55 milliseconds on its own, so the availability
side and the participant arm add nothing measurable on top of a read another consumer already makes.
The lever the plan held in reserve, keying the write arm off `graphitron_routine_entry` and the seat
verdict alone, is therefore not worth taking: it would be the same relation. What the read does cost
is two rows in `DerivedReadCostTest`'s pinned non-monotonic set, both inherited from the seat's own
row against `intent_spelled_table` and both recorded there with their figures; the second is the
`diagnostic` view, which reached no registration at all until it joined this rule.

The view is not registered, on the plan's own reasoning: two readers per build, so a registration
would pay a refresh to save two evaluations. Phase 2 is one pass over rows already in memory. Phase
3's cost is unknown until the population exists and is the phase's own measurement to take.

## Retired vocabulary

Phase 3 only; phases 1 and 2 retire nothing. Phase 1's own `KEY_CAPTURE_SCATTER` verdict retires with
its population if R660 lands the lowering rather than the rejection, and that retirement is R660's to
declare, not this item's.

* `validateListRequiresOrdering`
* `validatePaginationRequiresOrdering`
* `listOrderingDiagnostic`
* `routineResultTerminusOf`
* "paginated fields must have ordering" (the message fragment)
* "list fields must have a deterministic order" (the message fragment, both arms)

## Out of scope

* **Lowering an ordering onto a multitable read.** That is
  `roadmap/multitable-interface-query-orderby-lowering.md`, and phase 1 is the fallback its own field
  report asks for while it waits. Phase 1 must not grow into a partial lowering.
* **The routine write path's fix.** `roadmap/routine-write-key-capture-unordered.md` owns the seat
  question (mutation field or payload data field) and the `requiresReFetch()` census. Phase 1 gives
  the coordinate a cell that can reject it and holds the rejection; it does not decide where the
  order surface goes, and it does not choose between fixing the shape and refusing it.
* **The rest of the lookup census.** `roadmap/lookup-unrealized-co-members.md` keeps the inline
  `LookupMultiset` arm, pagination at lookup grain, and the one-record-per-key production throw.
* **The polymorphic-emit family's command relation.** Nothing owns the lowering at that coordinate on
  either side of this item, and this item does not mint the relation. If the lowering matters on a
  schedule, that belongs on R382's file.
* **A lint-severity variant.** Class A is a hard rejection because nothing can honour the declaration.
  The advisory register belongs to `roadmap/reference-path-fanout-verdict.md`, whose finding is
  advisory because a multiset may be what the author wanted.

## Why this is its own item

Three items independently reached the class and none carried it. The routine chain item stated it as
"an ordering the model resolved does not reach the emitted SQL, and no check compares the two ends",
noted that its own fix cannot make the invariant true, and said the shared enforcement question
should be "its own item rather than as a rider on either". The `@splitQuery` item repeated it. A
third (`roadmap/root-family-validator-mirror-gaps.md`) proposed a re-sourcing scoped to the
routine-chain membership gap rather than to the invariant, and that bullet has since been closed by
removing the carve-out, which left the invariant exactly where it was.

Note what all three were reaching for: a check that compares two ends. That is class C, and it is the
half none of them could site because the validator is the wrong place to stand. Splitting the class
three ways is what gives each half a home, and two of the three homes already exist. Siting class C
took one more step than splitting it: the comparison only earns its name where the two ends are
derived independently, which in today's command tier is the multiset alone.

## Relationship to other items

* `roadmap/planners-read-facts-emitters-read-commands.md` owns the launcher relation's move onto the
  store, which is what class B reads. It is also where the rule that a build-time rejection is not
  sourced from commands belongs, so the next item in this one's position does not re-run the
  argument.
* `roadmap/reference-path-fanout-verdict.md` is the shape class A copies, at a different severity.
* `roadmap/routine-write-key-capture-unordered.md` (R660) owns the list-returning routine write. This
  item classifies that coordinate and builds the cell that rejects it; R660 decides between lowering
  an order onto the write and turning the held rejection on, and flips the decode arm either way.
  Nothing here waits on R660: phase 1 ships its view row, its test and its `PARTICIPANT_FAN_OUT`
  rejection whatever R660 does.
* `roadmap/consumers-share-relations-not-queries.md` binds the class B view: it lands in the store at
  its own grain, and the launcher producer reads the same relation rather than a query shared with
  the rule.
* `roadmap/inline-child-list-orderby-argument-not-lowered.md` (R935) is phase 2's own finding, filed
  by this item's delivery: the sixth census site turned out to be real, and that item is the lowering
  (or the author-facing refusal) whose landing empties phase 2's production throw. This item does not
  wait on it and never did; the throw is what keeps the silence from returning meanwhile.
* The four per-site items (`roadmap/split-query-child-list-drops-default-order.md`,
  `roadmap/lookup-unrealized-co-members.md`, `roadmap/routine-write-key-capture-unordered.md`,
  `roadmap/multitable-interface-query-orderby-lowering.md`) fix the sites. Class C is what keeps them
  fixed; class A and class B are what find the next one.
* `roadmap/split-query-child-list-drops-default-order.md` is in review as of 2026-09-01 with its
  ordering projections landed on trunk, which is what closed two of the census's five sites and what
  phase 2 has to keep closed. Phase 2 does not wait for that item's Done gate: it reads the tree, and
  the tree carries the projections. If that review sends the item back and the projections change
  shape, phase 2's fold is where the change shows up.

## Provenance

Filed 2026-08-14 as a re-sourcing of the enforcement off the launcher relation's ordering slot,
after three items named the shared enforcement question and none took it. Rewritten 2026-08-19 at the
owner's direction, in light of the fact-oriented pivot and
`roadmap/planners-read-facts-emitters-read-commands.md` in particular: the launcher anchor put a
build-time rejection one tier above the verdict it had to fail, on a population that excluded the
site the item had already flagged against itself, and the same pass found the census splits three
ways rather than two. The honesty half survives unchanged as class A. The re-sourcing half becomes
class B at the fact tier, sequenced behind the launcher step. The comparison of two ends that all
three predecessor items were reaching for becomes class C, which needs no new source and no new
tier and can ship now.

The class A half is the reported half: https://github.com/sikt-no/graphitron/issues/523.

Specced 2026-09-01. Four things the spec pass changed rather than elaborated, each worth a reviewer's
attention because each is a departure from the body it was written against:

* **Two of the five census sites closed while the item sat in Backlog**, both by
  `roadmap/split-query-child-list-drops-default-order.md`'s delivery. The launcher family therefore
  has no live class-C site anyone has found, which is why phase 2 states that half as a ratchet and
  puts its case on the multiset half, where the sixth candidate site sits and where the two ends are
  derived independently.
* **The routine write path is class A, once class A is keyed on availability rather than on
  declaration.** Its leaf carries no ordering slot, so nothing resolved is ever discarded and class C
  cannot see it; its target table has a primary key, so class B as first drafted verdicts it
  `ORDERED`. The generalisation is what gives it a cell, and the cell's rejection is held while the
  only live instance is in our own example schema.
* **Class C lands in production, not in a test.** The track's stated purpose is finding the sites
  nobody has reported, and a test over our own fixtures cannot do that. The throw follows the
  precedent in the same class it lands in.
* **A sixth candidate site**, an inline child list with an `@orderBy` argument, found by reading the
  multiset renderer's single ordering branch. Unverified: three code reads say the ordering is silently
  ignored, and phase 2 carries the recipe that confirms or refutes it before the invariant is written.

Phases 1 and 2 delivered 2026-09-08. Three things the delivery changed rather than elaborated,
each recorded in the phase notes above with its reason: the fallback route's location moved from
the field to the `@routine` application, because a new view may not name the transcription; the
rejection writer's cadence moved after the materialization refresh, because the view reads a
materialized relation; and both relations are declared in `meta_relation`, which is what forces
their short comments and puts the essay in the declaration's own rationale. The confirmation recipe
found the sixth census site real, which is the one open question in the plan the delivery answered
by measurement rather than by argument.

## Reviewer findings

### Round 1, Spec -> Ready, 2026-09-08

Verdict: revisions requested. Phase 1 checks out end to end and I would hand it to an
implementer as written. Phases 2 and 3 each carry a mechanism-level problem that an
implementer would have to redesign in flight, so the item stays in `Spec`.

**1. Phase 2's assertion pair contradicts its own exemption set, and the contradiction is live
in this reactor (question 2, and question 1's viability half).** The two bullets under "What it
asserts" are stated independently: the first requires a present `Ordering` slot whenever the
leaf resolved a non-empty `Fixed` or an `Argument`, the second exempts an absent slot by
`LaunchSource` arm. A root `@lookupKey` list coordinate satisfies the first bullet's antecedent
and the second bullet's exemption at the same time. `OrderByResolver.resolveDefaultOrderSpec`
falls back to the target table's primary key, so `QueryField.QueryTableField` for
`filmById(film_id: [ID] @lookupKey): [Film]!` in `graphitron-sakila-example`'s schema resolves
`OrderBySpec.Fixed(FILM_ID)`, while `LauncherCommands.lookupRow` builds
`new ResultShape.RecordList(null)` unconditionally. A production fold reading the first bullet
literally throws on the verification build.

Resolving it the charitable way, letting the exemption gate both bullets, is where the finding
bites rather than where it ends. For every non-exempt arm the slot is computed by
`LauncherCommands.orderingOf` directly off the leaf's `OrderBySpec`, and a leaf resolving
`OrderBySpec.None` never reaches the plan because `validateListRequiresOrdering` rejects it
first. So the leaf-reading half asserts a total function against its own output, and the track
reduces to "every non-exempt list-shaped row carries an `Ordering`". That is a defensible
ratchet, but it is not the comparison of two ends that this item's own "Why this is its own
item" section says all three predecessor items were reaching for, and phase 2's name promises.
The four absent-slot sites are exactly the four the exemption list names (`lookupRow`,
`serviceReentryRow`, and `dmlRowOf`'s two projected-list arms), so there is no launcher-family
divergence left for the check to find.

What would satisfy this: state the launcher half as one assertion over non-exempt arms and say
plainly that it is a ratchet over a closed population rather than a two-ends comparison, or
site the two-ends comparison where the ends can actually diverge. The plan already identifies
that place, and it is the multiset half: `CallWrap.Multiset` carries the whole `OrderBySpec`
while `ProjectionUnitRenderer` renders one arm of it, so nothing there derives the command from
the render's capability. If phase 2 is worth shipping, that half is the reason, and the
confirmation recipe is already written for it.

**Response.** Taken as read, both halves. Phase 2 keeps shipping and is re-titled and re-ordered
around the split. The multiset half leads and carries the phase's case, stated as the two-ends
comparison and as the reason the phase exists; the launcher half follows, stated as one assertion
over non-exempt arms and called a ratchet over a closed population, with the four exempt arms named
as the whole of today's absent-slot population. The leaf-reading bullet is gone, and the two facts
that killed it are premises of the new text rather than history: the `@lookupKey` root list resolving
a primary-key `Fixed` against `lookupRow`'s unconditional `RecordList(null)`, and `orderingOf`
computing the slot off the leaf on every non-exempt arm. The phase-2 test list moves with it, the
per-arm negative pin becoming a membership pin over the exempt arms, since a keyed lookup's leaf does
resolve an ordering and the row is right to drop it.

**2. Phase 3's rule set verdicts the routine write path `ORDERED`, so the one live class-B site
the plan names is admitted to the population and then passed (question 1's viability half).**
`PRIMARY_KEY_FALLBACK` is stated as an unqualified fact join, "where the read's target table has
a `sql_primary_key`". `Mutation.rentFilm: [Rental!]!` navigates as `Rental`, which binds
`rental`, so it takes a `NAMED_TYPE_TABLE` row in `intent_field_scope_table` and `rental` has a
primary key. The reduction therefore reads `ORDERED` with `PRIMARY_KEY_FALLBACK` as the winning
rule, no `UNORDERED` row is minted, and the phase's own test list ("the routine write path ...
as a case that fails before the phase and passes after") cannot be satisfied.

The census's C-to-B reclassification is what opens the gap, and the reclassification's stated
reason is what closes the escape. `MutationField.MutationRoutineWriteField` carries no ordering
slot, so nothing resolved is discarded (correct, class C cannot see it), but an ordering is
available at the fact tier (a primary key exists), so class B as defined cannot see it either.
The defect is neither "no ordering resolves" nor "an ordering resolved and the lowering dropped
it" but a third thing: an ordering is available and the coordinate's family has nowhere to lower
it into. Class A is that comparison at the declaration grain; this is the same comparison with
the fallback standing in for the declaration.

What would satisfy this: either place the routine write path in a cell that can reject it (class
A generalised from "declared" to "available", which the plan is closer to than it reads, since
`intent_field_unlowerable_ordering`'s `verdict` column is already built for a second value), or
drop the claim that phase 3 catches it and say which mechanism does. What the revision should
not do is answer it with a fact about whether a family lowers an ordering, because that is a
command-tier property and the "Why the source is facts" section rules the sourcing out; the
class taxonomy is what has to move, not the tier.

**Response.** Class A is generalised, and the taxonomy moved with it. The rule now compares the
ordering **available** at a coordinate against the ordering its read shape **delivers**, so the
multitable root and the list-returning routine write are two arms of one comparison rather than two
classes; class B becomes "no ordering is available at all", and A and B partition the list-shaped
read coordinates between them. Both sides stay at the fact tier: the write arm reads
`intent_mutation_routine_seat` at `verdict = 'ADMITTED'` joined to `graphql_field.is_list`, which is
that relation's own read-shape fact and not a command family's slot inventory, exactly as the
multitable arm reads `intent_field_scope_table.basis`. `verdict` gets its second value,
`KEY_CAPTURE_SCATTER`, and `declared_via` becomes `available_via` with a `PRIMARY_KEY_FALLBACK` arm.
One thing the revision found while placing it, and the plan now carries it: the only live instance is
`Mutation.rentFilm` in our own example schema, so turning that rejection on reddens the verification
build until R660 gives the shape an order to deliver. The arm therefore mints its view row and holds
its `ValidationError`, under this item's own rule that an exemption names what removes it, and R660
flips it. Phase 3 drops the claim, in its own section and in its test list, and says where the
coordinate went.

**3. The phase-1 main-source path names a module that cannot hold the class the same section
describes (question 2).** `graphitron/src/main/java/no/sikt/graphitron/rewrite/derive/` holds
shadow-agreement scaffolding (`ClaimDomain`, `DemandResidue`), not the store-derived detection
families. `AuthoredClaimConflicts`, `AuthoredClaimRejectionRows`, `ArgmappingProjectionDefects`,
`StoreDetections` and `FactCapture` all live in `graphitron-model`, and `graphitron` depends on
`graphitron-model` one way, so a class under `no.sikt.graphitron.rewrite.derive` cannot be a
component of `StoreDetections` nor be called from `FactCapture.detect`, which the same bullet
list requires of it. Both test paths the plan names are already right by that precedent
(`ArgmappingProjectionDefectTest` in `graphitron-model`'s `model/intent`, its decode sibling in
`graphitron`'s `rewrite/derive`), which is what makes the main-source path read as the odd one
out. Left as a finding rather than corrected in place because there are two coherent answers:
move the class to `graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`, or keep it
in `graphitron` and reach the error stream by some route other than `StoreDetections`. The first
is what the precedent implies; the choice is the author's.

**Response.** Taken, the first way. Main sources move to
`graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`, and the Java-side section now says
why the placement is forced rather than chosen, so the next reader does not re-open it. Both test
paths are unchanged and the section says so, naming `ArgmappingProjectionDefectTest` and
`ArgmappingProjectionDefectsTest` as the split they follow. The sequencing section's `rewrite/derive/`
mention is corrected to the same path.

**4. No `## Goal` section (question 1, communication half).** `roadmap/workflow.adoc` puts the
goal first and says the `Spec -> Ready` reviewer answers the first gate question by reading its
opening paragraph. This body opens with an unlabelled problem statement and carries the goal's
substance under `## What changes for a consumer`, after four analysis sections and the
sequencing. The content is good, and I could state what changes for a consumer from it without
reconstructing anything from the phase list, so this is a placement finding rather than a
missing-goal one. Promoting that section to `## Goal` at the top would settle it.

**Response.** Promoted. `## Goal` is now the first section, opening with a paragraph that stands
alone, and the SDL example is lifted into a fenced block rather than run into the prose. Lowering is
glossed on first use, as is the multitable interface. The problem statement it displaced becomes
`## Why today's checks miss most of it`, the first plan section. The three consumer-facing paragraphs
are updated for this round's other two answers: phase 2's is now about the multiset's two ends with
the launcher ratchet beside it, and phase 3's drops the remedy count.

**Non-blocking.**

* The `verdict` column's justification names "a routine terminus with no primary key" as the
  next candidate value. After R704 that coordinate is not a class-A defect: a declared ordering
  over a routine terminus is lowered, and the undeclared case is the class-B rejection
  `validateListRequiresOrdering`'s routine arm already mints. Finding 2 above suggests a
  different second value; if that lands, this sentence is the one to replace rather than keep.
* `intent_field_unlowerable_ordering`'s stated column list carries no `argument_name`, while
  `graphitron_order_by_entry` is keyed at argument grain. Two `@orderBy` arguments on one coordinate
  would collide on the view's key. Probably unreachable (`OrderByResolver.resolve` takes the
  first `ArgumentRef.OrderByArg` it finds), but the view's key is worth one sentence either way.

**Response to both.** Both taken. The "next candidate" sentence is replaced by the verdict finding 2
put there, `KEY_CAPTURE_SCATTER`, so the column's justification now names a value the plan actually
delivers. `argument_name` joins the column list, NULL on the two non-argument arms and in the key,
with the reason stated: a view key is not the place to inherit a consumer's first-match precedence.

**What I verified against the tree.** Every symbol, relation, column, test class and document
path the plan names exists as named, including the four quoted comment fragments
(`intent_field_scope_table.basis`'s "several statements rather than one", `Ordering.Columns`'s
empty-spec throw, `CallWrap.Multiset`'s single-arm note, `diagnostic.actionable`'s `DEFERRED`
documentation). The pipeline-position argument holds: `Projection.VALIDATE` and
`Projection.BUILD_OUTPUT` both carry `emit = false`, so no plan is produced on either, and
`runPipeline` returns before `EmitPlan.produce` when the fused error stream is non-empty. The
population argument holds: `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`
declare no `orderBy` or `pagination` component and implement neither `SqlGeneratingField`, so
both current checks skip them, and nothing else rejects an `@orderBy` argument at that
coordinate. `@asConnection` over the multitable shape is admitted with a lint advisory, so the
reported schema does build today. The sort-results correction is warranted: the section as
written describes an ordering that is not lowered. The two closures the census records are real
in `LauncherCommands.batchedResultOf` and `batchedLookupRow`, and the sixth site's premise holds
as far as the validator goes, `validateTableField` checking only the reference path, the
lookup-connection pair and cardinality.

### Round 2, Spec -> Ready, 2026-09-08

Reviewer session: `https://claude.ai/code/session_01A782mxCy43Yi1jecwh5YGr`. Verdict: sign off.

Both round-1 findings that bore on the mechanisms are closed in the body as the responses say.
The write arm reads `intent_mutation_routine_seat` exactly as that relation is built (population is
every mutation-root `@routine`, `ADMITTED` is its one emitting verdict, `READ_SURFACE_ON_WRITE` keys
on `graphitron_order_by_entry` and the conditions and never on
`graphitron_default_order_entry`), the multiset half is sited where `ProjectionCommands` hands `ttf.orderBy()` to `CallWrap.Multiset` unfiltered and
`ProjectionUnitRenderer` lowers the `Fixed` arm alone, and the launcher ratchet's exempt arms are the
four `RecordList(null)` sites in `LauncherCommands` and no others. Phase 1 extends
`AuthoredClaimConflicts` / `StoreDetections` / `FactCapture.detect` as they stand, phase 2 follows
the production `IllegalStateException` precedent already in `LauncherCommands`, phase 3 follows
`intent_resolved_field_demand` and its coverage gate. Nothing here stands a parallel mechanism beside
an existing one. What was verified is in the commit message.

**Non-blocking.**

* Phase 2's exemption list names "the schema-free unit-tier assemblies" as a third exempt arm, but
  the switch is over `LaunchSource`, which has no such arm. The schema-free path's only absent-slot
  rows come from `serviceReentryRow`, whose source is `ProjectedReentry` and already exempt under the
  second bullet, so the entry is redundant rather than wrong; fold it into that bullet or drop it
  when the phase lands.
* `LauncherCommands.orderingOf` returns null on an empty `OrderBySpec.Fixed` as well as on `None`,
  while `validateListRequiresOrdering` rejects `None` alone. An empty `Fixed` reaching a non-exempt
  list row would trip the ratchet, which is what the ratchet is for, but the implementer should
  expect the corpus run to say whether that shape resolves today rather than assume the four sites
  are the whole absent-slot population.
* The `PARTICIPANT_TABLE` arm also covers child coordinates returning a multitable container:
  `ChildField.InterfaceField`, `UnionField`, `BatchedInterfaceField` and `BatchedUnionField` carry
  no ordering component, the same as the root leaves, so a declaration there is accepted and
  discarded today too. Correct behaviour for the rule; worth one case in
  `FieldUnlowerableOrderingTest` so the population's width is pinned.
