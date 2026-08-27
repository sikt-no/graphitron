---
id: R848
title: "Design the materialization cut set as a whole instead of accreting it one registration at a time"
status: Spec
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-27
---

# Design the materialization cut set as a whole instead of accreting it one registration at a time

Every materialization registration in the fact store was added on its own measurement, and each of
those measurements was sound. Nobody has ever evaluated the resulting set as a set. The register is
twenty registrations arranged twelve layers deep, which is a shape no author chose and no document
states; it is what a sequence of locally correct decisions happened to leave behind. A greedy descent
finds a local optimum, and the question this item asks is whether the store is sitting in one.

## What changes when this lands

**A figure nobody can state today gets stated, and then stops growing unnoticed.** Every capture,
every `mvn graphitron:validate` and every `graphitron:dev` start pays the whole register once before
it does anything else, and `graphitron:dev` does not bind its language server or MCP ports until that
pass returns. That is a wait a developer sits through on every run. R855 made the pass announce its
own duration; nobody has yet written down what it is, on any schema, and nothing fails when it grows.

So this item delivers two things a consumer can point at. The pass total, measured and recorded. And
a pin on the register's size and depth, so the twenty-first registration cannot land without somebody
editing a figure in the same commit and confronting what the set costs.

Whether the register also gets *smaller* is what the measurement decides, and both answers are
deliverable. If a smaller cut set is cheaper on the pass, this item removes the rows. If the twenty
are each worth their refresh, that is a positive finding with figures behind it, and the register
stops being unexamined even though it does not change. What is not acceptable is today's state,
where the answer is unknown and the set grows by default.

Nothing about what the store answers changes under any outcome. A registration preserves every row of
its rule by construction, which is what `MaterializeRegistryGateTest` closes structurally, so removing
one changes a cost and not an answer.

## What has changed since this item was filed

Four things, and the first two change what this item can plan.

**A scoring instrument was built for this item and refused.** R849 built a weighted re-evaluation
metric over the stored view definitions, ran it against the register, and failed its own
pre-committed gate: eight inversions across the seventeen scored registrations where the gate demanded
zero. Cardinality weighting made the decisive band worse rather than better, three
class-B-over-class-A inversions before weighting and five after, because a recursive term's weight is
a proxy for iterations the walk cannot see. The negative branch executed. `ReEvaluationMetric` and its
test are deleted; `ViewReferences`, the position-and-multiplicity parse under it, is kept on two named
consumers. That item has since reached Done and its file is gone, so its result lives in
`roadmap/changelog.md`, which is where a reader should be sent for the figures rather than to this
paragraph.

So the sentence in this item's original "Shape of the work", that candidate cut sets can be scored
without inventing a harness, is half wrong and it is the important half. The reachability walk and the
swap harness are there. The scoring function is not, it was attempted, and R849's conclusion is that a
static reading of the definitions cannot rank the current twenty and that the obstacle is not the
parse. This item plans around that result rather than repeating the attempt.

**The register now prices itself, for free.** R855 gave `Materializations.refresh` a `RefreshProgress`
observer that emits a pass total and, per registration, the delete and insert durations and both row
counts. A capture prints two pass-boundary lines by default and a line per registration at debug,
recipe in `docs/architecture/how-to/dev-loop-internals.adoc`:

```bash
mvn generate-sources -X | grep 'graphitron: *[0-9]*/'
```

The refresh half of this item's first unmeasured thing went from unmeasured to one build away while
the item sat in Backlog. That is slice 1 and it is nearly free.

**A priority-1 bug is about to add registrations twenty-one and twenty-two.** R856 has a consumer
capture that spends over an hour inside the refresh and has never been observed to finish; its Spec
names two registrations as the fix. That is this item's thesis arriving for the third time, and the
first two arrivals are already in this file. It is also why this item is not a moratorium, and why the
pin in slice 3 is prospective: see "What this item does not do".

**The census moved again, and part of it is now gated.** `DerivedReadCostTest` pins the view count by
equality at `READERS_IN_SCHEMA = 111` against the 107 recorded below, with 67 of those reaching a
registration and 178 cells in its matrix. Two views landed while R849 was in review and two more
since. Prefer naming where a figure is gated over restating it here: this file is not scanned by
anything and a copy in it rots in silence, which is what the two stale figures below already
demonstrate.

## Vocabulary

A **derived relation** is a view in the fact store: a rule stated once in SQL, evaluated whenever a
reader names it. A **registration** is a row of `meta_materialize` that keeps the rule in a view and
moves the canonical name every reader spells onto a table of the same shape, which the materializer
refills once per capture. The **cut set** is the set of relations chosen for registration: the points
along the derivation where evaluation stops and a stored answer starts. **Refresh depth** is how many
of those refills must run in sequence, a registration whose view reads another registration's target
being unable to start until that one has finished.

A registration is paid for twice and this item needs both halves named. **Read cost** is what the
registration buys: the evaluations of the rule that no longer happen because readers meet a table.
**Refresh cost** is what it charges: the one evaluation the materializer pays per capture to refill
the target. The lever ordering on `docs/architecture/explanation/fact-model.adoc` states the trade in
those terms, a refresh against the re-evaluations it avoids, so a relation read once between writes
gains nothing from a registration. No figure anywhere states either half for the register as a whole.

Two registrations are **substitutes** when registering one absorbs most of the other's value, so each
is worth much less against a register holding the other than it is alone.

## What the store says today

Measured against the store a build left on disk, not against a fixture. **These figures are from the
tree at filing and two of them have since moved**; slice 1 re-takes the set rather than this file
being corrected in place, and the two that are gated are gated elsewhere.

| Figure | Value at filing | Where it lives now |
|---|---|---|
| Views in the fact schema | 107 | 111, pinned by `DerivedReadCostTest.READERS_IN_SCHEMA` |
| Base tables | 168 | unchanged, ungated |
| Registrations in `meta_materialize` | 20 | unchanged, ungated: this is the figure slice 3 pins |
| Refresh depth (longest chain of registrations) | 12 | ungated, re-measure at pickup: this is the other figure slice 3 pins |
| Derivation depth as the rule composes | 24 relations | ungated |
| Derivation depth as actually evaluated | 7 relations | ungated |

The two depth figures were re-measured across both trees with a single instrument at the same time and
did not move; that instrument reads them one higher throughout, counting relations where the original
counted the steps between them, so the values above are left as they were first stated rather than
restated in a second instrument's units.

The last two are the pair that matters. Read as authored, the deepest rule in the store stands on
twenty-four relations. Read as the store actually evaluates it, with registered targets standing as
tables, the deepest read touches seven. The cut set is doing real work: it takes a twenty-four-deep
composition down to seven levels of live evaluation.

What it costs is the twelve. Every capture, and every language-server or MCP store open, pays twenty
view evaluations in twelve sequential stages, because `Materializations.refresh` walks the dependency
order and nothing in stage twelve can begin until stage eleven has finished.

## Why this looks like a local optimum

Three observations. The first two are readable off the register itself; the third is gated by a test.

The registrations were added in an order that never revisited an earlier one. Each row's reason argues
its own case against the tree as it stood, which was the right standard for that increment and means
no row is stated against the set. No registration has ever been removed. A cut set that only grows is
not a cut set anybody designed.

The write-payload family shows the descent explicitly. Its registrations occupy register layers nine
through twelve, and the as-composed depth histogram has exactly one view at each of depths sixteen
through twenty-four, which is to say that tail is a single file rather than a graph:

```
intent_input_field_carrier_role -> intent_mutation_payload_refusal
  -> intent_mutation_payload_column -> intent_mutation_matched_key
  -> intent_mutation_payload_key_membership -> intent_mutation_write_refusal
  -> intent_mutation_write_destination -> intent_mutation_write_agreement
```

Nine rungs, each one relation wide. `intent_mutation_write_destination`'s own reason records the loop
that produced them: rewrite the rung so it can be priced, register it, and find the next rung up is
now the expensive one. That loop terminates when the rungs run out, not when the pipeline is fast.

**And registrations demonstrably absorb each other, which is stated mechanically in a build-gated
place rather than being this item's inference.** `DerivedReadCostTest.CELLS`'s javadoc says why the
matrix moves down as well as up: "the reachability walk records a registration when it meets that
registration's target and stops there rather than descending, so registering a relation cuts every
reader's reach at it: each reader that reached a registration only through the newly registered
relation loses that cell". It names the worked instance, registering `intent_node_id_instruction`
being the first change to make the figure fall, by five on net. So the value of a registration is a
function of which other registrations exist, and the mechanism is a property of the derivation graph
rather than a reading of any metric.

R849's run met the same thing twice from the other side, and its figures are cited here as provenance
and not as magnitudes: registering `intent_field_scope_table` took `intent_argument_scope_table` from
the register's single largest lever to last place in the greedy order, and a leave-one-out reading put
that relation's value far higher against a register missing its neighbour than against the whole
register. Both readings are in the naming-count currency R849 deleted `ReEvaluationMetric` for being
unable to rank as cost, so they are evidence that substitution happens and evidence of nothing about
its size. What a pair of substitutes is actually worth is what slice 2 times.

## The twentieth registration, measured against the nineteenth

This item was filed in the morning and a twentieth registration landed the same afternoon, from an
increment whose subject was not materialization at all: the condition membership fold needed a read of
`intent_field_scope_table`, that relation was a 77-millisecond view sitting on the inner side of the
fold's final join, and it was registered. The two trees differ by that one change and nothing else, so
the arithmetic below is a clean before-and-after rather than an estimate.

```
before                                        after
  1 argmapping_pair, errors_field,              1 argmapping_pair, errors_field,
    spelled_table                                 spelled_table
  2 field_reference_step_hop                    2 field_reference_step_hop
  3 resolved_type_binding                       3 resolved_type_binding
  4 carrier_data_field, field_column_scope      4 carrier_data_field, field_column_scope
                                                5 field_scope_table              <- inserted
  5 argument_scope_table,                       6 argument_scope_table,
    mutation_write_payload                        mutation_write_payload
  6 argument_column_scope, …                    7 argument_column_scope, …
  …                                             …
 11 mutation_write_destination                 12 mutation_write_destination
```

**It did not go on top, and mid-stack is the less visible of the two failure modes.** The obvious
failure mode for a greedy cut set is a tower: each registration stacked above the last, each increment
climbing one rung, which is what the write-payload family above looks like. This one went in at stage
five with twelve registrations already above it, and moved every one of the twelve down a stage. Both
shapes cost the same single stage on the critical path, so the insertion is not the more expensive of
the two. It is the harder one to notice. A tower is legible from inside the family that builds it,
each rung visibly standing on the last; an insertion restages twelve registrations across four
families the increment never touched, none of whose files anybody edited and none of which got faster,
and nothing in the increment that caused it would show a reader that this had happened. **That
restaging is read straight off `Materializations.refreshOrder` and `meta_materialize_dependency`, so
it is a fact about the dependency graph and needs no metric at all.**

**The rule the family had written down was followed, and produced this anyway.** The rule is rewrite
before registering, on the ground that a registration prices a rule as it stands. One rewrite was
tried: reversing the outermost join so the scope table drives and the fold's contributor set is
probed. It measured 68349 milliseconds against the view's 6167, and the registration followed. The
rewrite that was not tried is resolving the table inside each contributor arm, so the fold has no
per-coordinate probe at the end to be slow. Trying one rewrite and then registering satisfies the
letter of the rule and is a greedy step; the rule as written does not say how hard to look, so it
cannot distinguish the two.

**The registration ritual is itself a ratchet.** A registration is now a table, a `_live` view, an
index chosen against a named reader, a comment on each, a `meta_materialize` row carrying the
measurement, and two `DerivedReadCostTest` entries. The index is not optional decoration: the same
fold measured 91045 milliseconds against an unkeyed target, fifteen times worse than the view it
replaced, because an inlined view can be evaluated restricted and a table can only be scanned. So the
ritual has grown as the register has, and a registration is now a larger schema commitment than one
made early in the register was. That raises the cost of ever taking one out, which is a mechanism for
a set that only grows, and it applies to this item's own removal arm as much as to anybody's addition.

**The discipline does sometimes say no, and it is worth recording how often.** Of the four increments
before this section was written, three added a registration and one refused, that one having measured
that the rule it was pricing was cheaply restrictable and that an unkeyed table would have been fifty
times worse than leaving it a view. The measurement is real and it does argue both ways. What it never
argues is against the set, because there is nothing in any single measurement that can.

## What is not known

Three things are unmeasured. The first has become cheap since filing; the other two have not.

**What the register costs as a whole.** Every row prices its own refresh against its own readers. No
figure anywhere states what the twenty refills cost together. Slice 1 takes the pass total and the
per-registration split, which R855 already emits. It does **not** close the whole of this: the pass is
serial by construction, so nothing R855 emits says what the twelve-stage ordering contributes over the
same twenty refills unordered, and that half stays unmeasured unless slice 1 finds a cheap way to it.
`RefreshProgress.Event.PassFinished`'s own javadoc scopes what the total covers, the statements and
the shape probes between them and not the registry reads that derived the order, and slice 1 adopts
that boundary rather than inventing one.

**Whether a smaller set would do as well.** The obvious alternative, three or four cuts at the widest
fan-out shoulders instead of twenty wherever a build hurt, has never been built or measured. It may be
worse; the point is that no one knows. This is slice 2.

**Whether the depth itself should fall.** Twenty-four is the number that forces the register to exist
at all, H2 inlining a view wherever it is named and eliminating no common subexpression, so depth is
what turns a rule named twice into a rule evaluated twice. Most of that depth reads as genuine
composition and is stated at the fine granularity this schema deliberately prefers. The linear tail
above is the part that does not, and this item does not fold it: see the fork below.

## The fork, decided

This item's original shape asked a Spec to choose between two framings before planning anything:
either the depth is essential and the cut set is an execution plan to be designed top-down and
pinned, or the depth is partly accidental and folding rungs removes the need for some registrations
outright. **The choice is the first, with one carve-out, and the work below is that arm's work.**

**The depth is mostly essential.** Twenty-four relations as composed against seven as evaluated is the
cut set doing real work, and the figures section above says so. Most of that depth is genuine
composition stated at the fine granularity this schema prefers, and the fact-model page's own rules
are why it is stated that way rather than by accident. There is no general case here for folding.

**The carve-out is the write-payload tail, and it is not this item's.** Nine rungs one relation wide,
each registered because rewriting the one below it made the one above expensive, is the part that
reads as accreted rather than composed. Folding it is a schema redesign across four write surfaces
with its own blast radius, and R841 already owns that family: it holds the measured history of the two
registrations in it, the three rewrites that were tried and refused, and the finding that the shape
which explained everything was a per-row probe into a derived relation. If slice 1 shows the tail is
where the register's cost actually is, this item files the fold as a Spec against R841's family rather
than absorbing it. That is the one place where the second framing applies, and naming it keeps the
decision honest instead of leaving "partly accidental" as a finding with no work attached.

So: this item prices the set, compares it against designed alternatives, takes whichever wins, and
pins the result so the next registration is argued against a set. The fold that would change the set's
shape is a separate item with a separate blast radius, and it is better informed after slice 1 than
before it.

## What can be measured here, and what cannot

Four instruments bear on a cut set and each answers one question. Naming them apart is what keeps this
item from repeating the error it is downstream of, which is a count that is real being read as a cost.

| Instrument | Answers | Cannot answer |
|---|---|---|
| `report-inline-multiplicity` (`InlineMultiplicityCheck`) | where breadth concentrates in the authored DDL | cost: it models one of three re-evaluation mechanisms, and its own javadoc says it reports rather than gates |
| `ViewReferences` | for one relation, which of its references re-evaluate and against what | any ranking built on those positions, which R849 built and refused |
| `DerivedReadCostTest`'s `scanCount` | that a registration does not make some other relation's read visit more rows | cost, and here least of all: a scan count stops tracking cost exactly when rows move between a view and a table, which is what every registration does |
| per-relation timing against a populated store | cost | nothing else; it is the only first-class evidence the `store-performance` skill admits |

So the instrument for this item is timing, and the two harnesses that deliver it exist.
`RefreshProgress` times the refresh half on any real capture with no code at all.
`UnregisteredRelation` reverses one registration inside a live store, which is what prices the read
half: the two shapes of one relation, in one process, with no DDL edit and no model rebuild.

**What this rules out, and it is the shape a reader will reach for first.** There is no scoring
function here and this item does not build one. Twenty registrations admit a million subsets and each
candidate costs a store, so a search is not merely expensive, it is the wrong shape: R849 established
that the thing a search would need, a score, is what could not be built. Slice 2 measures a handful of
cut sets somebody designed, against each other, on one named population.

**And nothing this item builds is a gate.** A cut-set price is wall clock, and wall clock can never be
a build assertion: that is exactly why `DerivedReadCostTest` holds a direction over scan counts
instead of a duration, and why it says no figure measured by a timing probe transfers into it. The
instrument slice 2 writes is on-demand research code that sits on no `verify` path. The only thing
this item adds to a build is slice 3's pin, which is a count and a depth rather than a time.

## Implementation

### Slice 1: price the register as it stands

Turn on what R855 already emits and write down what the register costs, per registration and as a
pass.

**Name the store rather than saying "a capture".** The build's own sakila capture at
`graphitron-maven-plugin/target/it-store` is the population to use: it carries the classpath census,
it is what R849's slice 3 priced against, so a figure taken there is comparable with the record, and
it needs no fixture. Say what it cannot see rather than leaving it to be found later:
`CapturedStore.ofCatalog` defaults the census to empty, and `DerivedReadCostTest`'s
`KNOWN_NON_MONOTONIC` carries cells that exist only because a census-reading arm holds no rows, so a
population question is the first thing to suspect when a figure does not reproduce.

**The consumer-scale population is the one that motivates the question, and this item cannot reach it
alone.** R856 establishes that no capture on that schema has been observed to finish at the current
register size, so the arm is gated on R856's work rather than the other way round. Take that
population if R856 makes it reachable and say plainly in the result if it did not, rather than
generalising from sakila.

Report four things, all unstated today:

- the pass total, adopting `RefreshProgress.Event.PassFinished`'s own scope boundary;
- the per-registration split, delete against insert, with both row counts;
- the distribution across the twelve stages, and the register's current size and depth, re-measured,
  since the figures in this file are from the tree at filing;
- whether the serial ordering can be priced against the same refills unordered at all. The pass is
  serial by construction and R855 emits nothing about the counterfactual, so this is a question slice
  1 answers yes or no to rather than a figure it promises.

This slice is cheap, it has no design content, and everything after it depends on it. If it shows the
pass is dominated by one family, slices 2 and 3 aim there and this item gets smaller.

### Slice 2: measure designed candidate cut sets

**Establish the mechanism before building anything on it, because the obvious one prices the wrong
half.** `UnregisteredRelation` swaps a target after a capture has run, so the refresh pass has already
paid for all twenty by the time the swap installs, and its javadoc rules out re-running the pass on a
swapped store: `refreshAll` would empty and refill a name that is now a view. A candidate set built
with the swap alone therefore prices reads only, and summing per-registration refresh figures to
recover the other axis is exactly the leave-one-out flattening R849 named as one of its three causes,
because a registration whose `_live` view reads a target the candidate removed gets *dearer*, not
cheaper.

The candidate has to be realised where the register states it, and in four steps whose **order is
the mechanism** rather than an implementation detail:

1. empty `meta_materialize_dependency` wholesale;
2. delete the excluded registrations' rows from `meta_materialize`;
3. `UnregisteredRelation.install` each excluded registration, so its canonical name resolves to the
   rule instead of to a table nothing will fill;
4. call `MaterializeDependencies.populate(dsl)`, and only then refresh.

`Materializations.registrations` reads `meta_materialize`, so step 2 is what makes the pass run the
candidate set and `RefreshProgress` price it directly.

**Step 1 exists because the schema forces it, and it is a clear rather than an authoring.**
`meta_materialize_dependency` declares a foreign key into `meta_materialize (source_view_name)` on
both of its columns, with no `ON DELETE` action, and H2 checks immediately with no deferral available.
Any excluded registration named by an edge on either side, which in a twelve-layer register is
practically every candidate, therefore makes step 2 throw on its own if the edges are still there.
The one statement the keys force is the wholesale delete, which is where `MaterializeDependencies`'
own transaction opens, so step 1 is that writer's first move run early rather than a second writer's
edit: it clears a derived cache that step 4 rewrites from the current census, and authors nothing.
Between the two the relation is empty and `refreshOrder` would degrade to the register's own key
order, which is why nothing may refresh inside the window; the harness's own setup is all that runs
there.

**Step 4 is not optional and the harness may not author the edges instead.**
`meta_materialize_dependency` is the family's one machine-written resident, its own comment saying a
hand edit is a bug the next boot undoes, and `MaterializeDependencies` opens by naming itself its one
writer. A harness deciding *which* rows survive there would be a second writer beside the one the tree
has, which the wholesale clear at step 1 is not. It would also settle on the wrong edges for exactly
the candidates this slice needs, and that is the deeper reason the choice may not be the harness's to
make. The rows that bite are a *retained* registration depending on an excluded one, and neither
answer is available: keep them and step 2 throws on the foreign key above, drop them alone and the
transitive constraint is silently lost, since the retained view now evaluates the excluded rule live
and so reaches the deeper retained targets it must still refresh after. Candidate C cannot avoid that
shape and most of 2a's stores meet it too, and in the dropping case nothing fails: the candidate's
price would simply be wrong on both axes. `populate` rewrites the relation from the current census
and recurses into unregistered views, which is what an excluded canonical name becomes at step 3, so
it derives those transitive edges rather than losing them.

**Which is why step 3 precedes step 4.** Before the swap the excluded canonical name is still a base
table, and the walk ends at a base table, so populating first derives no onward edge and reproduces
the silent-loss failure by a different route.

**Establish that this works before slice 2 plans around it, and state the fallback now:** if a
candidate cannot be realised in the harness ladder, slice 2 reduces to the read axis alone, with the
refresh axis coming from slice 1's per-registration events and its non-additivity disclosed in the
result. What must not ship is a table with two axes where one of them is an unstated sum.

**Type a candidate so it cannot name a relation the register does not hold.** A candidate is a
`Set<Materializations.Registration>` taken as a subset of `Materializations.registrations(dsl)`, never
a list of relation-name strings, which is the failure `UnregisteredRelation`'s javadoc already
designed against by taking a `Registration` rather than a name. Take the reader domain from
`MaterializeDependencies.registrationsReachedByView` rather than re-walking it: `DerivedReadCostTest`
takes both its axes off the booted store for exactly this reason, and a second walk on a second basis
is the disagreement R849 spent a review round on. Home it in `graphitron-model` test scope beside
`UnregisteredRelation` and `FactStores`, on the grounds R849 settled: the walk is main scope there,
and this is on-demand research code that belongs on no `verify` path.

**2a. Which registrations still earn their place.** For each of the twenty, realise the candidate
omitting it and time the readers whose derivation reaches it. One store per candidate, taken from
`FactStores` and closed, as that class's javadoc requires, and read through a reader minted after the
swap rather than through the writer surface that performed it. Timing follows the `store-performance`
procedure and not `System.nanoTime`: query statistics on with `OPTIMIZE_REUSE_RESULTS` off, sweeps
rather than adjacent repeats, and a spread reported with every figure.

This is a leave-one-out reading and R849 established that leave-one-out flattens a family of
substitutes, so it is used in the one direction it is sound in: a registration worth something under
leave-one-out certainly earns its place, and one worth nothing under it is a candidate that 2b must
confirm as a set.

**2b. Three candidate sets, priced against each other.** Candidate A is the current twenty. Candidate
B is A minus the registrations 2a found earn nothing, removed together rather than one at a time,
which is what tests whether 2a's zeros were real or were substitutes hiding each other. Candidate C is
the alternative this item named at filing and nobody has built: three or four cuts at the widest
fan-out shoulders instead of twenty wherever a build hurt. Identify C's shoulders from slice 1's
distribution and from `report-inline-multiplicity`'s breadth ranking, which is what that report is for
and the one job it does well.

Report the pass total and the read total separately for each candidate. Not a sum: a cut set that
halves the refresh and doubles the reads is a trade somebody has to make knowingly, and one number
would make that decision silently.

### Slice 3: the decision, and the pin that outlives it

**The decision rule is pre-committed here, before any figure exists, so it cannot be tuned to the
answer.** It is directional and carries no threshold, on the same grounds `DerivedReadCostTest` states
for its own claim: a threshold nobody can honestly set is a ritual.

> A registration is a removal candidate when the candidate set omitting it costs less than the set
> holding it, on the named population, counting refresh saved against read cost added. Where the two
> sets differ by less than the spread the instrument itself reports, the registration stands, and what
> gets recorded is that it was priced against the set.

Under that rule both arms are results a Done reviewer can audit. Arm A is positive: three designed
candidates were priced and each is worse than the twenty, with the figures. Arm B names the rows to
remove, and removing them is `meta_materialize` rows, the targets, their `_live` views, their indexes
and comments, and the ritual reversed for each.

**Where the answer goes.** `roadmap/changelog.md` under either arm, which is permanent where this file
is deleted at Done, following R849's precedent for a result worth more than the item that produced it.
Under arm A the set-relative figure also goes into the surviving registrations' own `reason` rows,
which is the register's gated surface for exactly this. Figures do not go into
`docs/architecture/explanation/fact-model.adoc`: that page is a statement of rules at altitude and a
census copied into it rots in silence, which is what the figures table above already demonstrates.

**The pin, which is the half that stops the accretion and lands under both arms.** The read side of a
registration is already ratcheted: a twenty-first necessarily moves `DerivedReadCostTest`'s
`READERS_IN_SCHEMA`, `READERS_WITH_CELLS` and `CELLS`, all equality-pinned, so it cannot land without
somebody editing figures and looking at what it costs a reader. **The refresh side is not ratcheted at
all.** Nothing fails when the register's size or its refresh depth grows, which is exactly how the
twentieth registration landed mid-stack, restaged twelve registrations across four families, and was
noticed by nobody.

So pin the registration count and the refresh depth by equality in `MaterializeRegistryGateTest`,
which already boots a store, already calls `Materializations.refreshOrder(dsl)` in
`theDerivedDependenciesAdmitARefreshOrder`, and already reads the dependency rows that depth is one
traversal of in `theRefreshOrderRespectsEveryDependencyRow`. Both figures are structural, both are
computable from what that test already holds, and neither is a time. `NO_INDEX` and `HAND_WRITTEN` in
that same test are the live precedents for an equality-pinned roster whose row has to go rather than
survive as a stale exemption.

**And one clause of prose beside the pin**, on `meta_materialize.reason`'s column comment, which is
where the register's own doctrine already lives: a reason states which existing registrations its
relation is a near-substitute for and why it is worth its refresh given them, not only what it was
worth against the tree as it stood. Prose there is a `NOT NULL` string and nothing parses it, which is
why it is the junior half; the pin is what forces the confrontation and the clause is what says what
to confront.

**The clause and the pin are prospective.** R856's two registrations land under the current standard,
on a priority-1 bug, and are the first test of the rule rather than the subject of a retroactive audit.

## Tests

Slices 1 and 2 are measurements and their output is figures. This plan does not invent a harness to
re-take them per build, and the `store-performance` skill's posture is why: a per-relation timing is
machine-dependent, and a tier that must not fail for being slow cannot hold one.

What slice 3 owes is two assertions and one ratchet already in place.

The two new assertions are the count and the depth in `MaterializeRegistryGateTest`, asserted by
equality in both directions so a registration cannot be added or removed without the figure being
edited in the same commit.

The ratchet already in place covers arm B. Removing a registration is gated by what gates the register
today: `MaterializeRegistryGateTest` closes the rows against the schema in both directions and fails
on a row whose relations went away, and `DerivedReadCostTest`'s pinned sets are asserted by equality,
so a removal fails that test until its cells and its `KNOWN_NON_MONOTONIC` rows go. Both fire on this
change without being edited for it, which is the property that makes a removal safe to attempt at all.

## What this item does not do

**It does not gate any individual registration and it is not a moratorium.** R856 is priority 1, has a
consumer capture that has never been observed to finish, and its Spec names two registrations as the
fix. Those land on their own merits and on R856's own timetable. This item measures the register it
finds, twenty rows or twenty-two, and an argument that a fix must wait for a whole-set design would be
this item doing more harm than the accretion it describes.

**It does not fold the linear tail.** Named in the fork above, scoped to R841's family, and filed as a
Spec against that family if slice 1 says the tail is where the cost is.

**It does not build a scoring function or search the subset space.** R849 established that the score
is the part that could not be built. This item prices designed candidates instead.

**It does not re-measure the twenty reason rows against today's bodies.** R849 named that as a
follow-up need and R831 files the same drift problem for the measured claims in ordinary relation
comments. Slice 2a's timings say what each registration is worth today, which is what this item needs;
correcting the historical reason text is R831's shape of work.

## Risks

**The candidate-realisation mechanism may not work, and slice 2 rests on it.** Clear-delete-swap-populate
before a refresh is the mechanism slice 2 needs, and while every step of it is an existing routine
called in an order the tree already supports, nothing in the tree composes them this way today.
Establish it first, on one candidate, before building the comparison on top of it, and take the stated
fallback to a read-only axis if it does not hold. This is R849's own risk-handling shape and it is
here for the same reason: the plan's most load-bearing assumption is a mechanism nobody has run.

The check that establishes it is cheap and specific rather than a general "does it work": realise one
candidate that removes a mid-chain registration, and assert that `Materializations.refreshOrder` still
places every retained registration after the retained prerequisites it now reaches through the
excluded rule. That is the failure the hand-deletion would have produced silently, so it is the one
worth an explicit check before any figure is taken.

**The populations may not agree, and the consumer-scale one may be unreachable.** A cut set is chosen
against a population, and sakila is the one every build has while the hour-long capture is the one
that motivates the question. If slice 1 reaches only sakila, slice 3's decision is explicitly
conditional on that population. What it must not do is generalise silently, which is the error the
`store-performance` skill records against a reason row carried onto a consumer schema where the branch
it blamed was empty and the term was elsewhere.

**Removing a registration is a larger commitment to reverse than it was to make.** The ritual is named
in the diagnosis above as a mechanism for a set that only grows, and it applies to this item's removal
arm: the work per removal is real. Slice 2b is what stops that cost being spent on a removal a
substitute would have made pointless.

**A pinned depth could be read as a budget nobody may exceed.** It is not. It is a figure that has to
be edited deliberately, on `NO_INDEX`'s model, and the twenty-first registration may raise it in the
same commit that argues for it. The failure it prevents is a registration changing the register's
shape while nobody looks, not a registration landing.

## Related

- **R849** built and refused the scoring instrument this item would have used, and its result is the
  reason slice 2 prices designed candidates instead of ranking members. Its `ViewReferences` parse
  survives and answers, for one relation, which of its references re-evaluate. It is Done and its
  file is deleted; the result is in `roadmap/changelog.md`.
- **R855** made the refresh emit its own per-registration and pass timings, which is slice 1.
- **R856** is the priority-1 bug this item must not block, and the only route to a consumer-scale
  population for slice 1's second arm.
- **R841** owns the write-payload family, and the nine-rung fold carved out of the fork above belongs
  to it rather than here.
- **R831** files the same drift problem for the measured claims written into ordinary relation
  comments, which is why this item does not re-measure the twenty reason rows.

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session 01WajEgSkL8dc4owJXtVbyui)

Verdict: withhold. One finding, on question 2, confined to slice 2's candidate-realisation
mechanism. Question 1 passes: what a consumer gets reads off the plan without reconstructing it
from the slices (the pass every capture and `graphitron:dev`/`validate` start pays gets measured
and recorded, the register either shrinks or is confirmed with figures behind it, and the
register's size and refresh depth become equality-pinned so the next registration is a deliberate
edit), and every checkable claim behind it verified against the tree, the details in this round's
commit message.

**The candidate-realisation mechanism hand-writes a machine-written relation, and derives the
wrong edges for exactly the candidates slice 2 needs.** The mechanism paragraph pins deleting the
excluded registrations' rows from `meta_materialize_dependency` by hand. Two things are wrong with
that, one architectural and one behavioral.

The architectural one: that table's own comment declares its rows the family's one machine-written
resident, "a hand edit is a bug the next boot undoes", and `MaterializeDependencies`' javadoc opens
by naming itself "the one writer of `meta_materialize_dependency`". A harness that deletes rows
there is a second writer standing beside the one the tree already has, which is the shape question
2 exists to refuse.

The behavioral one: the paragraph's "both tables is not a detail" justification covers only the
rows whose `source_view_name` is an excluded registration, the direction that NPEs `refreshOrder`.
The rows that bite are the other direction, a *retained* registration depending on an excluded
one, and the paragraph does not say what happens to them. Leave them and `refreshOrder` still dies
on the same null map entry, inside its cycle namer. Delete them and the transitive constraint is
silently lost: when the excluded registration was live, the retained view's stored definition
named the excluded *target table* and could not reach past it, so the only edges are retained ->
excluded and excluded -> deeper. After `UnregisteredRelation.install` the retained refill
evaluates the excluded rule live, which reads deeper retained targets, so the retained
registration must still refresh after them; with both hand-deletions no row says so, the refresh
can refill it against stale prerequisites, and the candidate's price is wrong on both axes with
nothing failing. Candidate C removes most of a twelve-layer register and cannot avoid this shape,
and most of 2a's twenty leave-one-out stores hit it too.

What would satisfy the finding: realise a candidate by deleting the excluded rows from
`meta_materialize` only, installing the swap for each excluded registration, and then calling
`MaterializeDependencies.populate(dsl)` before the refresh. That routine rewrites the relation
wholesale from the current census and descends through unregistered intermediate views, which is
what an excluded registration's canonical name becomes after the swap, so it derives the
transitive edges the hand deletion loses; the table comment names transitive reach through
unregistered intermediates as the very reason the relation is machine-written. The sequencing
belongs in the paragraph, because before the swap the excluded canonical name is a base table and
the walk ends at it, deriving no onward edge. The rest of slice 2 stands: the fallback, the
`Set<Registration>` typing, the reader-domain sourcing and the 2a/2b structure are untouched by
this, and the "establish the mechanism first" risk posture applies to the corrected mechanism as
written.

**Author response (2026-08-27, session 01Kid6RaCqPnzC2ZgeGQKy55).** Taken as stated, and both halves
of the finding were verified against the tree before the revision rather than accepted on the round's
word. `MaterializeDependencies`' javadoc opens "The one writer of `meta_materialize_dependency`" and
`meta_materialize_dependency`'s table comment carries "a hand edit is a bug the next boot undoes"
verbatim, so the hand deletion was a second writer beside an existing one. And `populate`'s own
javadoc settles the behavioral half in the round's favour twice over: it rewrites the relation
wholesale from the current census, and its walk "recurses into that view's definition" for an
unregistered view while "base tables end the walk", which is both why it derives the transitive edges
the hand deletion loses and why the swap has to precede it.

The mechanism paragraph is now the three ordered steps the round names, with the order stated as the
mechanism rather than as an implementation detail, the second-writer and silent-loss arguments given
as the reason step 3 is not optional, and a separate note on why step 2 precedes step 3. The risk
section's mechanism sentence is corrected to match, and it gains the specific check that would
establish the mechanism: one candidate removing a mid-chain registration, asserting `refreshOrder`
still places each retained registration after the prerequisites it now reaches through the excluded
rule. That is the failure the hand deletion would have produced silently, so it is worth checking
explicitly rather than leaving to a general "establish it first".

Nothing else in the plan body moved. The fallback, the `Set<Registration>` typing, the reader-domain
sourcing and the 2a/2b structure are as the round found them.

### Round 2 (2026-08-27, Spec -> Ready, reviewer session 01WajEgSkL8dc4owJXtVbyui)

Verdict: withhold. The revision does everything round 1 asked, states the corrected mechanism for the
right reasons, and adds the one establishing check worth having. Checking the corrected steps against
the DDL surfaced one new defect, narrow but in the same load-bearing paragraph, so it is a finding
rather than a note.

**Step 1 as written throws on the schema's own foreign keys.** `meta_materialize_dependency` declares
`FOREIGN KEY (source_view_name) REFERENCES meta_materialize (source_view_name)` and the same on
`depends_on`, with no `ON DELETE` action, and H2 checks immediately with no deferral. So "delete the
excluded registrations' rows from `meta_materialize`, and from that relation only" is refused by the
engine for any excluded registration that an edge names on either side, which in a twelve-layer
register is essentially every candidate. The failure is loud rather than silent, but the paragraph as
written leaves the implementer wedged: the FKs force a statement against `meta_materialize_dependency`
before the census delete, and the paragraph's own "step 3 is not optional and the harness may not
hand-write the edges instead" reads as forbidding exactly that statement.

What would satisfy the finding: the mechanism acknowledging the two foreign keys and gaining the one
statement they force, emptying `meta_materialize_dependency` wholesale before the census delete, with
the note that this is clearing a machine-written cache `populate` rewrites at step 3, not authoring
edges, so the one-writer doctrine holds; `populate`'s own transaction opens with the same wholesale
delete, so the clear is the existing writer's own first move rather than a new mechanism. Any
equivalent the author prefers also satisfies it, provided step 1 can actually execute against the DDL
and the no-hand-writing sentence is reconciled with whatever the FKs force.

#### Author response (round 2)

Verified against the DDL before revising rather than taken on the round's word: both foreign keys are
declared as stated, neither carries an `ON DELETE` action, and no other relation references
`meta_materialize`, so the wholesale clear is sufficient as well as necessary. `populate`'s
transaction does open with the same unqualified delete of the relation, computing its edges before the
transaction opens, so running that delete early is the existing writer's own first statement rather
than a new one.

Taken as offered. The mechanism is now four steps, the new first one emptying
`meta_materialize_dependency` wholesale, with its own paragraph naming the keys as the reason it
exists and distinguishing a clear of a derived cache from an authoring of edges. The old
no-hand-writing sentence is reconciled by narrowing what it forbids: a harness deciding *which* rows
survive is the second writer, which a wholesale clear is not. The dilemma beneath it reads truer for
the correction, since the retained-depends-on-excluded row now has no available answer at all rather
than one loud and one silent: keeping it throws on the foreign key, dropping it alone loses the
transitive constraint silently, and only `populate` re-derives it. One consequence the round did not
name is stated too: between the clear and `populate` the relation is empty and `refreshOrder` would
degrade to the register's key order, so nothing may refresh inside that window.

Renumbering follows from the inserted step, so the two order rationales now read as step 3 before step
4, and the risk section's mechanism name becomes clear-delete-swap-populate. The establishing check is
unchanged and still targets the silent failure, which the correction leaves in place as the dropping
case. Nothing else in the plan body moved, and the round 1 response note above is left at its original
numbering as the record of that round.
