---
id: R811
title: "Gate that a registration cannot make another relation's read worse, and attribute the decode's ten-times move"
status: Spec
bucket: store
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# Gate that a registration cannot make another relation's read worse, and attribute the decode's ten-times move

`intent_node_id_decode` is the fact schema's heaviest derived read. One read of it against a real
capture went from about five and a half seconds to about fifty in a few days, and back to about
thirteen when the step-hop registration landed beside this item. Nothing about the relation's answer
changed at any point: it returns the same 43 rows in all three measurements. What the number costs is
not a surface budget yet, no reader on the critical path having named it, which is exactly why it
moved ten times over without any gate saying so.

## What this item delivers

In one sentence: after this item, a `meta_materialize` registration that makes some *other*
relation's read more expensive fails the build instead of landing unremarked, and this relation's
ten-times move is attributed rather than suspected.

The gate is the deliverable and it does not wait on the attribution. Its claim is directional and
carries no number: for a registration and a relation whose derivation reaches that registration's
target, materializing must not cost that relation more scans than leaving it a view. That is this
item's defect stated as an invariant, and it is checkable today, before anyone knows which change
did it. Nothing in the tree makes that claim now. `report-inline-multiplicity` ranks breadth and
records that breadth is not cost; `SurfaceScanCountTest` holds seven ceilings across six *reader
surfaces* and none over a relation; `MaterializeRegistryGateTest` closes the register against the
schema and asks nothing about what a registration costs anybody.

The attribution is the second half, and it is the item's origin: which change made this relation ten
times more expensive, and by which mechanism. It is a deliverable rather than preparation because the
last attempt at it returned a confident wrong answer, for the reason recorded below. The instrument
that makes the control cheap and repeatable is therefore part of the work rather than a script
somebody throws away afterwards: no timing probe over this schema survives anywhere in the tree, so
every investigation of a relation's cost so far has rebuilt one from the store-performance skill's
recipe and deleted it again. The same instrument is what makes the gate's claim measurable, so it is
one piece of work serving both halves rather than two.

Explicitly not in scope: the lever. Whatever the attribution names, making this relation cheaper is a
change to the schema with its own trade to argue, and it belongs to an item that owns the relation
the lever touches, in the shape R781 owns `intent_field_column_table`'s lever. This item files that
item and hands it the measurement. What it lands instead is the gate, the recorded attribution, and
the cost warning in the relation's own `COMMENT ON`, where the fact model says such a warning belongs
because the cost is invisible at the call site.

What changes for a consumer of graphitron, stated plainly: nothing they can observe today. This
relation has no Java reader, and the claim is about the next one rather than today's. The reader the
build does have is one relation over: `intent_node_id_decode_defect`, read by
`NodeIdDecodeDefects.detect` in `FactCapture`'s detection pass, which runs over a freshly captured
store on any run carrying a classified model. That is a read and not a capture, the rule being wholly
the view's (it picks between its two verdicts on the node key's arity in one pass, and the class
writes nothing at all); what the Java adds is the build-error consumer's own filter and the prose.
The relevant fact for this item is what that view stands on, which is `intent_node_id_decode_slot`,
most of the decode's own subtree. So a consumer's build may already be paying a share of this move
through the sibling, and whether it is has never been measured. That measurement is part of the
control below, and it decides how urgent the lever item is rather than whether this item pulls it.

## What was measured

A timing probe over the sakila example's own schema captured against the sakila catalog
(`CapturedStore.ofCatalog`), timing `SELECT count(*)` per relation inside one capture. Two
passes per run, and each figure below reproduced across separate runs.

[cols="3,2,2"]
|===
| Tree | `intent_node_id_decode` | `intent_argmapping_projection_defect`

| trunk at `200fd26`
| 5054-6090 ms
| 761-1200 ms

| trunk at `424a0e4`
| 49728-50462 ms
| 107-199 ms

| `424a0e4` plus the step-hop registration (shipped at `37c5814`)
| 13215-13270 ms
| 93-189 ms
|===

Row counts are unchanged at every point: 43 for the decode, 0 for the defect relation. So
this is a cost change and not an answer change, and the second column is there to show that
the same window improved one heavy relation while the first regressed.

Two of the hashes in this section no longer resolve in this repository, `200fd26` above and
`272ef13` below, both lost to a rebase after the figures were taken. They are kept as written because
a hash that once named a tree is still the honest provenance of a measurement taken on it, and
inventing a nearby one that resolves would be worse. Nothing below depends on reaching either tree:
the control is per registration rather than per commit, and the suspect list those hashes belong to is
replaced on day one by the reachability walk.

## What was not established

Which commit did it. Three commits touch the fact schema DDL between those two trunk points:
`272ef13` (the two diagnostics-drain registrations), `ed424f6` (query relations carry facts
rather than rendered strings) and `42614bd` (the family-page metadata relations).

The registration of `intent_resolved_type_binding` in the first of them is the obvious
suspect, on two grounds that are suggestive and not evidence. It is the relation the decode
family reaches through, thirteen view bodies name it, and materializing it changes what every
one of those namings plans against. And the same two registrations are already on record
moving a second surface by two orders of magnitude in the *other* direction, the inlay-hint
read going from 10205 ms to 65 ms, attributed by a same-fixture control.

An attempt at that control for this relation was run and produced nothing usable: the script
that reverted the registrations corrupted the DDL, the model build failed, and the probe
silently measured the previously installed model instead. The failure is worth recording
because the run *looked* like a result, reproducing the un-reverted tree's figures to within
noise.

## The instrument, and the control it makes cheap

The control this item needs is "what did this relation cost before that registration existed", and
the obvious way to ask it is the way that already failed: check the DDL out at a parent commit,
rebuild `graphitron-model`, re-probe. That route has three defects and they are why the failed run
looked like a result. It reverts a whole commit rather than one registration, so it cannot attribute
to a registration when a commit carries two. It reverts everything else that commit did to the
schema alongside, and by now that is a schema several changes behind the one whose cost is in
question. And its failure mode is silent: a model build that does not execute the DDL leaves the
previous artifact installed and the probe reads that, reproducing the un-reverted tree's figures to
within noise.

Two cheaper routes exist and it is worth being clear about which question each one answers, because
one of them needs no instrument at all.

For a *registered relation's own* two shapes there is nothing to build. The registration keeps the
rule under `<relation>_live`, so reading the `_live` view is exactly "this rule evaluated on demand"
and reading the canonical name is the materialized shape. The register's own column comment says so.
Any question of the form "what did this relation cost before it was registered" is already answerable.

For *which registrations a relation even reaches*, there is a walk in the tree that answers it from
the catalog with no probe and no timing: `MaterializeDependencies.relationsReadBy` parses each stored
view definition through jOOQ's parser and recurses through unregistered views, which is how
`meta_materialize_dependency` gets its rows. Pointed at `intent_node_id_decode` it names the
registrations structurally inside the decode's subtree, which narrows the suspect set before any
measurement and narrows the gate's domain afterwards. The routine is `private static` today and wants
lifting, which is a smaller change than anything else here. Do this on day one: the three-commit
suspect list in the section above is a reference count over the whole schema, and this replaces it
with reachability.

What neither route answers is this item's actual question, which is what a registration costs a
*different* relation, and that needs the registration genuinely absent while the reader is read. So
the instrument is a test-only helper that reverses one registration inside a live store, with no DDL
edit and no model rebuild: rename the target table out of the way, then create a view of the
canonical name over the `_live` view, and every reader of the canonical name is reading the
unregistered shape. That is the move `RunawayRelation` already makes for a different purpose, one
relation swapped for a view of the same shape by rename-then-create, and this helper is its sibling in
the same module and test tree: `UnregisteredRelation.install(dsl, registration)`, taking a
`Materializations.Registration` rather than a name. Taking the name would make the helper reconstruct
the source view by appending `_live`, and nothing in the tree pins that suffix;
`MaterializeRegistryGateTest` checks kinds, column lists, acyclicity and refresh order and never the
naming convention. The register already holds the pair and `Materializations.registrations` already
hands it out, so the helper takes the pair and cannot be pointed at an unregistered relation at all.

The helper is one-way per store, like its sibling, but for its own reasons and the javadoc states
those rather than inheriting the sentence. `RunawayRelation` is one-way because its swap makes a
relation non-terminating. This swap preserves every answer; what makes it one-way is that two
mechanisms afterwards address the canonical name as a table. `Materializations.refreshAll`, which is
what `SeededStore.derive` calls, would empty and refill a name that is now a view, and
`ThreadConfinedStore`'s clear refuses outright, H2 declining to truncate a name the swap has turned
into a view. So a store that has been un-registered is spent,
and it follows that the measurement is one store per registration plus a baseline rather than one
store carrying every shape. That is the matrix this gate has to pay for, and pricing it is part of the
work rather than a surprise at the end.

Verify before building on it: H2 must re-resolve a dependent view against the renamed name.
`RunawayRelation` proves the direct read path and claims nothing about views that name the swapped
relation, and every relation worth un-registering is named by a dozen view bodies. Check this on the
first day of the work, against a registered relation with known readers, and if H2 holds a compiled
reference instead, the fallback is an arm on `FactStores` that boots a store from the schema resource
with one registration's three statements textually reversed. That is the same claim built one level
lower, still with no model rebuild, and it keeps `StoreFixtureGuardTest` satisfied because the store
still comes from a harness.

With the instrument in hand the control is:

. Price the family as it stands, against one sakila capture (`CapturedStore.ofCatalog` over the
  sakila example's own schema and the sakila catalog): `intent_node_id_decode`, and beside it
  `intent_node_id_decode_slot`, `intent_node_id_decode_column`, `intent_node_id_decode_endpoint`,
  `intent_resolved_node_key_shape` and `intent_node_id_decode_defect`. Row counts every time, so a
  cost change and an answer change cannot be confused. The defect view is the one with a reader on
  the build path today and it has never been timed at all.
. Price the refresh beside the reads. A registered relation's `count(*)` is a table scan and says
  nothing about the refresh that filled it, and that refresh is real build work:
  `Materializations.refresh` runs per graph inside `FactCapture`'s capture transaction. The trade a
  registration has to win is a refresh against the
  re-evaluations it avoids, so a control that prices only reads is answering half the question, and
  "what does a consumer's build pay" is the half it leaves out.
. Un-register `intent_resolved_type_binding` alone, in its own store, and re-measure the same list.
  This is the attribution the earlier attempt could not make, and it is now one relation rather than
  one commit.
. Read the scan-count shape as well as the duration, because the two mechanisms consistent with a
  registration making some *other* reader slower want different levers and the plan tells them apart.
  One enormous `scanCount` on a single node is per-driving-row re-evaluation, which is what a join
  order flipped by a relation acquiring row statistics looks like, the shape
  `SurfaceScanCountTest.theCensusLookupDoesNotTrackTheSchemasSize` already records for the catalog
  census. A few hundred nodes each carrying the same middling count is inlining, a term the drain
  does not reach being expanded once per naming down the decode's tree. The item's own prose assumes
  the second; the first is the only mechanism by which materializing a relation can *cost* a reader
  anything, so it is not the one to leave untested.
. If un-registering the binding refutes the suspicion, take the other two schema changes in the
  window the same way (`ed424f6` moving query relations onto facts rather than rendered strings is
  the next candidate, and it is a candidate for a stated reason: replacing a rendered string with
  facts is exactly how a join key stops being a column and starts being an expression, which the
  fact model prices at two orders of magnitude).

Report the controls that refuted, in the item body, not only the one that survived. Two of three
controls on the investigation that introduced this discipline refuted the reading taken first, and a
note recording only the survivor leaves the next reader to re-run the dead ends.

## Why this is not simply reverted

The registrations that are the leading suspect bought a diagnostics drain going from past
seven minutes to 191 ms against a 3 s interactive budget, and an inlay read going from four
times over its budget to inside it. Both are surfaces a developer waits on. If the control
confirms the attribution, the question is which term under `intent_resolved_type_binding`
the decode family reaches that the drain does not, and whether that term wants a registration
of its own, not whether to give the drain its minutes back.

The step-hop registration this item was found alongside already recovers most of the gap
without touching the suspect, which is some evidence that the residual is a distinct term
rather than the registration as such.

## Which lever, which this item hands on rather than pulls

The lever is chosen after the attribution and not before, and the ordering is the fact model's own:
a captured fact, then a registration, then a rewrite. Three outcomes are possible and each has a
different answer, so the plan names all three rather than pretending the first is the likely one. What
this item does with them is file the follow-up item and hand it the measurement; the analysis below is
what that item inherits, and it is written here because the control produces it.

If the term is a relation the decode family reaches and the drain does not, and it has more than one
reader, it is a registration candidate and the depth rule decides which relation gets registered:
the deepest one whose materialization removes the re-evaluation for more readers than the relation
that looked slow from here. Stopping short of that depth is measurably not a fix, and this family is
where that was measured, the endpoint materialized with the recursive step's input left a view being
no better off than before.

If the term is a join order flipped by the binding becoming a table, the lever is on the reader
rather than underneath it, because nothing beneath it is slow: the shape to look for is the one the
fact model prices, a derived relation met on an expression rather than on a column, and the fix is to
project the expression as a column in an inner derived table and join on that.

And if the control says the residual is inlining with no expensive child, the lever may be none of
them yet. `intent_node_id_decode` has no reader that pays this cost today, so a registration's
refresh would buy nothing per capture, which is exactly the counter-case the register already carries
in its own words: the hop-column registration was deliberately made in the increment that added its
reader rather than when the cost was first seen. A follow-up item whose answer is "wait for the
reader" is a real answer, and it is the one R781 already holds for its own relation.

What the pricing of the defect and slot views decides is that item's priority, not this item's
scope. If
`intent_node_id_decode_defect` or `intent_node_id_decode_slot` moved with the decode, the cost is on
`FactCapture`'s path in every consumer build today, the reader has already arrived, and the lever item
is filed at a priority that says so. If they did not move, it is filed as a latent cost with its
measurement attached. Either way it is a separate item, because a schema change with a trade to argue
is not something to append to a gate.

## The gate

One assertion, directional, with no number in it. Not the ceiling the Backlog body asked for, and the
reason is worth stating because it is the plan's least obvious correction: a ceiling of the kind
`SurfaceScanCountTest` holds cannot be written for this relation at all. That recipe works there
because the registration *helped*, so the ceiling sits between a lower registered figure and a higher
unregistered one, and reinstating the defect raises the number past it. Here the ordering is inverted,
which is the entire subject of this item: un-registering makes the decode cheaper. A ceiling above
today's figure can never be failed by that mutation, and a ceiling between the two figures fails on
the shipping tree. The two rules, discriminate and pass, are jointly unsatisfiable exactly when the
attribution lands. So the discriminating assertion is the direction itself, and the ceiling over this
relation is minted by the lever item from its post-fix figure, with the pre-fix figure as the shape it
discriminates against. That also takes the priced-relation roster out of this item, the roster's only
purpose having been to carry ceilings.

The claim, then: for every registration in `meta_materialize` and every relation whose derivation
reaches that registration's target, the registered shape costs no more scans than the unregistered
shape. A registration is a shared investment, and making some other reader ten times worse is a
regression whether or not anybody has named a budget for that reader. There is no magic number in it,
so there is nothing for a later contributor to raise; the only way past it is a row saying so.

Both axes of the domain come off the booted store rather than a hand-kept list. Registrations from
`Materializations.registrations`. Readers from the reachability walk lifted out of
`MaterializeDependencies`, since a registration can only affect a relation whose view subtree names
its target. Single-sourcing the second axis is what keeps the domain from rotting as views are added,
and it is the same rule `meta_materialize_dependency` is already built on: the universe of relations
comes from the booted store and never from a copy of the DDL. It also shrinks the matrix, which
matters for the reason below.

Known non-monotonic pairs are pinned as a set asserted by equality, not as a ceiling and not as a
free-floating allowlist. Equality is what gives the ratchet: adding a pair fails the build, and so
does *removing* one, so the day the lever lands the assertion fails until the row goes, rather than
the row surviving as a stale exemption nobody is forced to revisit. `MaterializeRegistryGateTest`'s
own `HAND_WRITTEN` set is the precedent for a pinned roster of deliberate exceptions in this family.
Expect exactly one row at landing, this item's own pair, with the control's finding as its comment.

The alternative is `ExemptionRegistry` in `graphitron`'s test tree, which is the repo's shipped
exemption mechanism and carries three legs (keys in-domain, keys still uncovered, domain fully
accounted for) plus a reflective discovery guard that fails the build on any static
`Map<..., Exemption>` that is not a registry row. Joining it is the right move for the second and
third such pair and it is not free for the first: `Obligation` is typed on `Class<?>` keys throughout
and a pair of relation names is not a class, so joining means generifying the row's key type, and the
`Exemption` arms are a coverage-triage taxonomy whose own javadoc argues against arms no population
confirms, so an accepted-cost-regression arm would have to be added. Two changes to a shared
mechanism for one row is the wrong trade, and the discovery guard does not force it, since it fires on
`Map<..., Exemption>` and an equality-pinned `Set<String>` is not one. State that trade in the test's
javadoc so the second pair's author knows where the line is rather than rediscovering it.

Module and fixture: `graphitron`'s test tree, over a `CapturedStore` capture, scaled. Module follows
harness and harness follows subject, which is `testing.adoc`'s rule in that order, and the subject
here is what a registration costs a reader over a realistic population. That is the `CapturedStore`
row of the harness table, and `CapturedStore` lives in `graphitron`. A seeded fixture was the earlier
choice and it is wrong for a stated reason rather than a preference: the mechanism this item names as
the only one by which materializing can *cost* a reader anything is a join order flipped by a relation
acquiring row statistics, and a dozen seeded rows cannot exhibit a statistics-driven plan flip. The
store-performance skill says it twice, that a seeded store of a dozen rows tells you nothing about
cost and that a plan over a dozen rows is a different plan. `graphitron-model` keeping the decode
family's algebra tests is not evidence against this, it is evidence for it: those tests read this
relation cheaply on seeded rows, which is what a fixture blind to cost looks like. The exemption
question above points the same way, `ExemptionRegistry`'s discovery guard walking `graphitron`'s own
`target/test-classes`.

Instrument is the `scanCount` H2 annotates each `EXPLAIN ANALYZE` plan node with, for the reason
`SurfaceScanCountTest` gives and `ReadBudget` states the other half of: a count of rows visited is the
same number on a fast machine and a loaded one, so a tier that must not fail for being slow can still
hold a cost claim. No duration is asserted anywhere in this test. Note that scan counts and durations
are not the same claim, so the control's timings do not transfer: the gate's figures are measured with
the gate's own instrument on the gate's own fixture.

### What the gate does when the unregistered side does not answer

Some cells cannot be measured, and the register says so in its own words. The hop-column
registration's `reason` records that the unregistered walk "does not finish inside a two-minute
timeout", and that walk is `intent_node_id_decode_column`, which `intent_node_id_decode` names, so the
family this item exists to price contains one. That is the register's only such cell, and the
distinction matters for what the arm has to cover. The binding's reason records a five-minute
non-completion too, but that one is a census-driven join inside the diagnostics drain, a Java reader's
cost rather than a view named by another view, so it is evidence that unregistered shapes reach these
durations rather than a second cell of this gate. One cell is enough to need an answer. Reachability
puts it in the domain rather than taking it out, and no honest carve-out removes it, since a
hand-kept exclusion list is exactly what deriving both axes from the store was for. So the gate needs
a stated answer, and here it is.

A cell whose unregistered side does not answer passes, and the gate records that it passed that way.
Non-termination is the strongest possible form of "materializing did not make this worse", so passing
is the only reading that is not a lie; what must not happen is passing *silently*, because a cell that
quietly stops being measured is a gate quietly getting weaker.

Two decisions make that concrete.

The budget is per cell and relative, not one global number. `ReadBudget.Bounded` is the mechanism, and
what bounds the unregistered side is a multiple of the wall clock the *registered* side of the same
cell just took, floored at a stated minimum for the cells where that figure is milliseconds. The
reason to make it relative is the objection `RunawayRelation`'s javadoc raises against a fixed
threshold, that one reliable on one machine is a flake on another: both sides
of a cell are timed in the same run on the same machine, so a loaded machine slows both and the ratio
holds where an absolute figure would not. Pick the multiple from the gap the register already
documents, which is seconds against never.

The exhausted cells are pinned by equality, exactly like the non-monotonic pairs and for the same
reason. A cell that starts timing out fails the build until somebody records it and says why; a
recorded cell that starts answering fails until the row goes. That is what keeps "passes on
exhaustion" from becoming "passes for reasons nobody is tracking", and it means the gate reports two
kinds of known exception rather than one, each with its own ratchet.

What this deliberately does not do is assert a duration. The budget decides whether a cell is measured
or recorded as unmeasurable; it never decides pass against fail. So slowness can make this gate more
permissive and can never make it red, which is the tier guarantee `ReadBudget`'s two arms exist to
protect, kept by pointing the timeout at the outcome that cannot fail a build.

And there is a stronger reason the cut is safe, which is the one that makes pass-on-exhaustion sound
rather than merely convenient. Read the failure mode in the right direction: the gate fails when the
*registered* shape costs more, so a regression is a cell whose *unregistered* side is the cheap one.
A cheap side finishes. So a budget on the unregistered side can only ever discard cells whose
unregistered side was slow, and a slow unregistered side is evidence for the registration rather than
against it. The arm cannot swallow the defect the gate hunts, because the defect arrives fast.

Stated with its one gap: scans and wall clock are different metrics, so a shape that visits fewer rows
while taking longer would divide the two, and the argument above is then a strong heuristic rather
than an identity. That is precisely what the pinned set of exhausted cells is for. A cell sitting in
it is a cell nobody has compared, named where somebody can see it, rather than a silent hole in the
domain.

If the implementation finds the ratio too tight to be stable, there is a structural excuse available
for part of the population rather than another number. The known cell's relation,
`intent_node_id_decode_hop_column`, is named inside `intent_node_id_decode_column`'s *recursive term*,
which is the shape whose whole cost is re-evaluated per accumulated row, and that property is
derivable from the same parsed view definitions the domain already comes from, so it would excuse such
a cell by its shape rather than by a list or a clock.

What that property does not reach is the binding. Its one appearance in a recursive view is the *seed*
of `intent_field_reference_step_target`, which that view's own comment states in those words, and a
seed is evaluated once, so the per-accumulated-row rationale does not describe its position and a
predicate written to that rationale would leave the binding's cells in. So the structural route is a
partial excuse by shape and not a replacement for the budget, which is the better reason for it being
the fallback than its cost as an extension of the parse walk. If the binding's cells turn out to need
excusing structurally too, the property that covers them has to be found rather than assumed; until
then they are the budget's business and, when they exhaust it, the pinned set's.

The shape that property would have is worth naming, so that the search starts from the register's own
account rather than from scratch. What the two cases share is not recursion, it is *position*: a
relation evaluated once per row of something else. A recursive term is one such position, per
accumulated row. An expression-keyed join is the other, per driving row, and that is precisely what
the binding's registration says about itself, a `COUNT(*) OVER` that no outer predicate prunes plus a
reader joining it on an expression rather than a column. The fact model carries the two as separate
rules for that reason.

What splits them is detection cost, and that is why this stays the fallback rather than becoming the
answer. Finding a recursive term is a partition of a parsed definition on its first top-level `UNION`.
Deciding that a join meets a relation on an expression means tracing a comparison's operands back to
their source relations through aliases, subqueries and CTEs, which is the predicate-analysis layer
R801 already scopes as the last and hardest of its three, over the same parsed definitions. So the
property that would cover the binding is not a small extension of this item's walk, and an implementer
who needs it should reach for that item's machinery rather than grow a second copy here.

### What the gate costs

The cost of the gate is real and this plan owns it rather than discovering it in review. Each
un-registration spends its store, so the matrix is one store per registration in the domain plus a
baseline, each store paying a capture, and `EXPLAIN ANALYZE` executes the statement rather than
estimating it, so a cell containing this relation pays its full evaluation. There is no second size
axis: two sizes are how `SurfaceScanCountTest` tells a bounded constant from the low end of a curve,
and a directional comparison between two shapes of one relation does not ask that question.

Three things keep the matrix bounded and all three are decisions rather than hopes. The reachability
walk restricts the reader axis to registrations actually in a relation's subtree, so most cells do not
exist. The gate asserts its own cell count against a figure stated in the test, so the matrix cannot
grow silently as views are added: a new view that puts a new cell in the domain fails the count until
somebody looks at it. And if the bounded matrix is still too expensive for the pipeline tier, the
answer is fewer relations in the domain, never a smaller fixture, because a smaller fixture is the one
change that would make the gate pass while seeing nothing.

That second leg is the gate stating its own number, which is what this tree already expects of a
module that starts counting store boots: `ThreadConfinedStore`'s boot budget is deliberately scoped to
`graphitron-model`, its own javadoc saying the other three modules boot per case by design and that
one adopting a funnel states its own figure. `graphitron` is one of those others, the class and its
accessor are package-private, and `CapturedStore` takes a store per capture by design, so this gate
cannot lean on that budget and does not: it counts what it opens and says how many that may be.

## Doctrine

One paragraph on `docs/architecture/explanation/fact-model.adoc`, under "Derived reads are views,
not stored facts", stating the rule and naming the gate that holds it. The page's depth rule already
says to materialize the relation the cost multiplies through and to count the candidate's readers;
what it does not say is that a registration's effect on relations *other* than its motivating reader
is not free, which is the whole content of this item. If the attribution lands, the paragraph names
the mechanism by which it happened.

What the paragraph does not do is restate the arithmetic, and `meta_materialize.reason` is not
widened to carry a list of the relations a registration was measured against. That was the item's
third candidate answer and the gate supersedes it: once the measured-against matrix is data the build
checks, a prose copy of it in a `reason` column is a second copy with no enforcer, and the column's
own comment defines it as *why* the relation is materialized rather than as a measurement log. The
same reasoning is why no measured number from this item's control is copied onto the page: the
control's findings live in this item, which dies at Done, and the numbers that must outlive it live
where a gate reads them.

## Tests

* `DerivedReadCostTest` (new, `graphitron`'s test tree, pipeline tier): the monotonicity assertion
  over the derived domain; the equality-pinned set of known non-monotonic pairs; the equality-pinned
  set of cells whose unregistered side exhausts its budget; and the assertion on its own cell count.
  Four assertions, one of which is a number the test states, and the domain of all four comes off the
  booted store.
* A case for the un-registration helper itself: install it on a registered relation and assert that
  the canonical name still answers the same rows, and that a view naming that relation does too.
  That is the claim every number taken through the helper rests on, and a helper that silently
  changed an answer would make all of them wrong in the same direction. It is also where the
  dependent-view question above stops being a risk and becomes an assertion. Note that
  `RunawayRelation`, the shape this helper follows, has no case of its own in the model tree at all:
  it is a test-jar helper exercised from `graphitron-lsp` and `graphitron-mcp` fixtures. This helper's
  case lives with its reader rather than repeating that gap.
* A case for the lifted reachability walk, if lifting it out of `MaterializeDependencies` widens its
  surface: the walk is already exercised through `meta_materialize_dependency`'s rows, and what a new
  caller adds is a second question asked of it, so the case pins the answer for one relation whose
  subtree is known by inspection.
* `MaterializeRegistryGateTest` gains nothing here. It closes the register against the schema, which
  is a different question; a cost claim belongs with the cost assertions.
* No execution-tier work, no generated-output change, no fixture regeneration.

## Acceptance

. The monotonicity assertion is in the tree, its domain derived from the booted store on both axes,
  and it has been seen to fail: removing this item's pair from the pinned set fails the build, and so
  does adding a pair nothing measured.
. The pass-on-exhaustion arm has fired on a real cell rather than being written against a
  hypothetical one, and its pinned set ratchets both ways: removing a recorded cell fails, and so does
  a cell timing out that nothing recorded. The decode family supplies the cell, the register's
  hop-column reason already naming the walk that does not finish unregistered.
. The control has run and its finding is recorded in this item, including the controls that refuted.
. `intent_node_id_decode_defect`, `intent_node_id_decode_slot` and the node-family refreshes are
  priced, so "does a consumer's build pay this today" has an answer rather than an assumption.
. The lever item is filed, at a priority those two figures decided, carrying the control's findings.
. `intent_node_id_decode`'s `COMMENT ON` carries its cost warning.

No user-visible surface, so the first-client user-docs draft does not apply. The only prose the item
ships is the contributor-facing fact-model page and the relation's own `COMMENT ON`.

## Roadmap entries

One item filed: the lever for `intent_node_id_decode`, per the section above, carrying the control's
findings and priced by the third measurement. It is filed as Backlog by this item's implementation
rather than pre-filed now, since its body is the control's output.

Three siblings touch this and none of them is absorbed by it. R781 prices
`intent_field_column_table` at 151 seconds and owns that relation's lever; it is the nearest sibling
to the item this one files, and the two are comparable because both will state their relation's two
shapes with the same instrument. R802 corrects `SurfaceScanCountTest`'s own account of what a ceiling
catches; this item takes the corrected reading as its rule, and its finding that a ceiling must be
seen to fail is what rules a ceiling out here at all, so R802 is a dependency in reasoning and not in
code.

R733's boundary is the one worth stating precisely, because the easy version of it is a dodge. R733
owns a guardrail over the reactor's wall clock. This gate asserts no duration, so it cannot conflict
with that guardrail's assertions, but it does *consume* the resource R733 owns: a matrix of captured
stores is build time. So the honest boundary is that the two do not overlap in what they assert and
do overlap in what they spend, which is why the cost paragraph above is part of this plan and why the
implementation reports the wall clock it added when it lands. If that number is large enough to matter
to R733, R733 is the item that decides what to do about it.

## Reviewer findings (Spec → Ready gate, 2026-08-23)

Independent reviewer session `session_01LQgCxRoQLiBENLLV6mgseJ`, status stays `Spec`. Two findings.
The first is on question 1, the viability half: the plan's outcome is not reachable as specified,
because part of the gate's own domain cannot be measured. The second is on question 2: one leg of the
cost argument names a mechanism that does not govern the module the plan puts the test in. Both live
in the plan's cost reasoning, which is otherwise the section that carries the most weight here, since
the gate's whole viability rests on it. Everything else checked out, including every symbol, relation
and test the spec names; the verification list is in the commit message.

1. **The gate's domain contains cells whose unregistered shape the register itself records as not
   completing, and the plan takes no position on them.** The claim is comparative ("the registered
   shape costs no more scans than the unregistered shape") and the instrument is the `scanCount` H2
   annotates an `EXPLAIN ANALYZE` plan node with, which the plan correctly notes executes the
   statement rather than estimating it. So every cell needs its unregistered side to *finish*. At
   least one cell cannot. `meta_materialize`'s own reason for the hop-column registration says of the
   unregistered shape: "the walk accumulates around twenty rows, and the walk does not finish inside
   a two-minute timeout, while the same walk over these rows as a table is a little over three
   seconds for the whole reader." That walk is `intent_node_id_decode_column`, which names
   `intent_node_id_decode_hop_column` directly and is named in turn by `intent_node_id_decode`, so
   the pair sits inside the exact family this item prices and the reachability restriction does not
   exclude it: the reachability axis is what *puts* it in the domain. The same territory holds the
   item's own primary control, `intent_resolved_type_binding`, whose reason records a read over the
   unregistered shape that "did not finish inside a five-minute timeout".

   The three decisions the cost paragraph offers do not reach this. Reachability shrinks the matrix
   but keeps this cell. A stated cell count prices a cell that terminates. "Fewer relations in the
   domain" is the one that would work and it is barred by the plan's own strongest property, that
   both axes come off the booted store and never a hand-kept list; carving out the cells that hang
   reintroduces exactly the hand list the plan rules out. Nor does the equality-pinned set cover it:
   that set is for pairs where materializing costs *more*, and a non-terminating unregistered shape
   is the strongest possible pass, just an unobservable one. The result is that an implementer
   reaches this on the first cell they run and has to invent the semantics.

   What would satisfy the question: a stated position on a cell whose unregistered side cannot be
   evaluated, decided here rather than in implementation, because it changes what the assertion
   means. `ReadBudget.Bounded` already exists to bound a read in this tree and would give the clean
   shape, an exhausted budget on the unregistered side passing the cell by construction with the
   reason recorded; that is a pass-on-timeout arm on the gate and it needs the author's argument, not
   the implementer's guess. Whatever the answer, it wants to say what the gate does when the
   unregistered side does not answer, and it wants to hold for the population the plan already knows
   about rather than for the one cell above.

   *Author, same day:* accepted, and the position is now stated in the gate section under "What the
   gate does when the unregistered side does not answer". A cell whose unregistered side does not
   answer passes, non-termination being the strongest form of the very claim the gate makes, and it is
   recorded as having passed that way so the gate cannot go quiet. Two decisions carry it. The budget
   is `ReadBudget.Bounded` at a multiple of the wall clock the registered side of the same cell just
   took rather than one global figure, because both sides are timed in the same run on the same machine
   and the ratio survives a loaded machine where an absolute number does not, which is that type's own
   objection to a bare threshold. And the exhausted cells are pinned by equality like the
   non-monotonic pairs, so a cell that starts timing out and a recorded cell that starts answering both
   fail until somebody looks. The finding's implicit worry about smuggling a wall clock into this tier
   is answered by direction: the budget decides measured against recorded-as-unmeasurable, never pass
   against fail, so slowness can only make the gate more permissive. A structural alternative is named
   as the fallback, both non-terminating cases in the register being relations inside another view's
   recursive term, which the existing parse walk could derive; it is the fallback because it extends
   that walk and the budget needs no new machinery.

   One argument the finding did not ask for but the arm needs, added in the same pass: the cut is safe
   in one direction, and that is why a timeout may pass. The gate fails when the registered shape costs
   more, so a regression is a cell whose unregistered side is the *cheap* one, and a cheap side
   finishes. A budget on the unregistered side therefore discards only cells whose unregistered side
   was slow, which are the passes. The plan says that, and says the one gap: scans and wall clock can
   diverge, which is what the pinned set is for.

2. **`ThreadConfinedStore`'s boot budget does not govern `graphitron`'s test tree, and cannot be read
   from it, so the leg that turns the cell count from discovered into checked is not there.** The
   cost paragraph offers three bounding decisions "rather than hopes", and the second is that "each
   store counts against `ThreadConfinedStore`'s boot budget, so the cell count is a number the plan
   states and the implementation checks against that budget rather than one it discovers". Three
   things in the tree contradict that. `ThreadConfinedStore` is a package-private `final class` in
   `no.sikt.graphitron.model.test` and its `bootBudget()` accessor is package-private too, so
   `DerivedReadCostTest` in `graphitron`'s test tree cannot name either. `verifyBootBudget` fires
   only on a funnel call, that is, from inside that class, in `graphitron-model`'s own surefire run.
   And most decisively, `BOOT_BUDGET`'s javadoc scopes itself deliberately away from this test's
   module: it is "stated here rather than on `FactStores` even though it is that counter's budget,
   because the harness is reached from four modules and only this one has adopted the funnel. The
   others boot per case by design and in the hundreds, so a number enforced down there would be this
   module's claim imposed on theirs. When one of them adopts a funnel it states its own."
   `graphitron` is one of those others, and `CapturedStore` takes `FactStores.inMemory()` per capture
   by design.

   This matters for question 2 rather than being a stale reference, because it is the leg that makes
   the matrix a checked number. Removing it leaves the plan's cost claim resting on reachability plus
   an unenforced assertion in prose, and the sentence that says the implementation "checks against
   that budget" directs an implementer at a mechanism they will find inaccessible on day one and then
   have to replace with a decision the plan says it already made. The `BOOT_BUDGET` javadoc names the
   shape of that decision precisely, that a module adopting a funnel states its own number, so it is
   a real choice with a figure attached and it belongs to the author.

   The same paragraph carries a smaller inconsistency worth settling in the same pass, because it
   changes the number: the cost is priced as "one store per (registration, size) cell plus a
   baseline", while the control section says "one store per registration plus a baseline". A size
   axis appears nowhere else in the plan and the gate does not seem to need one; the two-size
   discipline `SurfaceScanCountTest` runs exists to tell a bounded constant from the low end of a
   superlinear curve, which a directional comparison does not ask. As written the phrase doubles the
   matrix the plan claims to own.

   *Author, same day:* accepted on both halves. The boot-budget leg is gone, and what replaces it is
   the gate asserting its own cell count against a figure stated in the test, so a new view that puts
   a new cell in the domain fails the count rather than growing the matrix silently. That is the shape
   `BOOT_BUDGET`'s javadoc prescribes for a module that starts counting, a module adopting a funnel
   stating its own number, and the paragraph now says why this gate cannot lean on `graphitron-model`'s
   figure instead. The size axis is deleted rather than defended: it was a leftover from the seeded
   two-size fixture the previous revision replaced with a capture, and the plan now says explicitly
   that a directional comparison does not ask the question two sizes exist to answer. The cost is one
   store per registration in the domain plus a baseline, matching the control section.

### Non-blocking

* Two commit hashes cited in the measurement sections are not valid objects in this repository:
  `200fd26`, the baseline row of the "What was measured" table, and `272ef13`, the first of the three
  candidate commits in "What was not established". Rebases will do that. Nothing the implementer
  builds turns on either, the plan replacing the three-commit suspect list with the reachability walk
  on day one and the control being per-registration rather than per-commit, but a reader trying to
  reproduce the 5054-6090 ms baseline cannot get to the tree it was taken on. Worth a note in the
  table saying the hashes are pre-rebase, or re-resolving them.
* "`ThreadConfinedStore`'s clear would truncate one" reads as the clear succeeding. It refuses:
  `RunawayRelation`'s javadoc has H2 "declining to truncate" a name the DDL has turned into a view.
  The plan's conclusion, that the store is spent either way, is the same one that javadoc draws, so
  this is wording rather than substance.

*Author, same day:* both taken. The wording now says the clear refuses, H2 declining to truncate a
name the swap has turned into a view. On the hashes: `200fd26` and `272ef13` are confirmed missing
(`git cat-file -t` finds neither) while the other four resolve, and they are kept as written with a
note in the measurement section saying they are pre-rebase. Keeping them is deliberate: a hash that
once named a tree is the honest provenance of a figure taken on it, and substituting a nearby
resolvable one would make the provenance false rather than stale. The note also says why nothing
downstream needs those trees.

### Round 2 (Spec → Ready gate, 2026-08-23)

Same reviewer session `session_01LQgCxRoQLiBENLLV6mgseJ`, status stays `Spec`. Both round-1 findings
are properly answered and I am not reopening either. The design question I raised is settled: I
checked the mechanism the new arm rests on and it is reachable, `GraphitronModelStore.reader` being
public and taking a `ReadBudget`, readers minting per budget with nothing pooled, and
`StoreAnswer.OutOfBudget` giving the arm the clean signal it needs; `Bounded`'s constructor refusing a
non-positive figure is also exactly why the stated floor is needed, so that detail is right. The
safe-in-one-direction argument is the strongest thing added in this pass and it holds.

One finding, on question 2, and it is a factual repair rather than a design reopening. It is blocking
only because settling it requires a choice I am not the one to make.

1. **The structural fallback's stated property does not hold for the binding, which is this item's own
   primary control: the binding is named in a recursive view's *seed*, and in no recursive term
   anywhere in the schema.** The fallback paragraph says "both non-terminating cases in the register
   are relations named inside another view's *recursive term*, which is the shape whose whole cost is
   being re-evaluated per accumulated row", and offers that as a property derivable from the parsed
   definitions. Half of it holds and half does not. `intent_node_id_decode_hop_column` really is in
   the recursive term of `intent_node_id_decode_column`, so the hop-column case is covered.
   `intent_resolved_type_binding` is not: its one appearance in a recursive view is the seed of
   `intent_field_reference_step_target`, before the `UNION`, and that view's own comment states it in
   those words, "The seed reads intent_resolved_type_binding and not the @table population alone". I
   partitioned every `WITH RECURSIVE` view in the DDL on its first top-level `UNION` to check whether
   some other view names it in a recursive term; none does.

   The rationale is where this bites rather than the label. A seed is evaluated once, so "the shape
   whose whole cost is being re-evaluated per accumulated row" is not a description of the binding's
   position at all, and a predicate written to that property would exclude the hop-column cell while
   leaving the binding's in. That is the wrong way round for this item, the binding being the
   relation the whole attribution is aimed at. An implementer who reaches for the fallback because
   the ratio proved unstable derives the predicate, finds it does not cover the case they came for,
   and is back at a design question.

   What would satisfy it: either narrow the fallback to the population it actually covers, saying
   that the recursive-term shape reaches the hop-column case and that the binding's needs something
   else, or name a property that covers both. I am not choosing between those, because the second is
   a different predicate over the parse walk and the first changes what the escape hatch promises.

   Related and worth fixing in the same pass, since it is the same sentence's premise: the paragraph
   above it reads the register as recording two non-terminating *cells*. It records one. The
   hop-column reason's two-minute timeout is relation-to-relation and is genuinely a cell of this
   gate. The binding's five-minute timeout is a read "through the diagnostics drain", a
   census-driven join in that surface's own statement, so it is a Java reader's cost and not a view
   named by another view. The arm's known population at landing is one cell, not two. That does not
   weaken the arm, and the Acceptance criterion asking it to fire on a real cell rather than a
   hypothetical one is still satisfiable, the decode family supplying that cell; it only means the
   plan should not present the binding as the second instance of the same thing.

   *Reviewer, same day, at the user's explicit direction:* the user asked me to make the tweak and
   sign off rather than bounce the item a second time, so the three edits below are mine and not the
   author's, and this note exists because the findings-not-fixes split otherwise hides that. I took
   the narrowing arm of the two I offered, since it invents no new predicate. The fallback paragraph
   now says the recursive-term property excuses the hop-column cell, states that it does not reach the
   binding because the binding sits in `intent_field_reference_step_target`'s seed and a seed is
   evaluated once, and concludes that the structural route is a partial excuse by shape rather than a
   replacement for the budget, which is a better reason for it being the fallback than its cost as an
   extension of the parse walk. Finding a property that covers the binding's cells is left open and
   explicitly not assumed. The section's opening paragraph now presents the hop-column cell as the
   register's only cell of this gate and demotes the binding's five-minute figure to what it is,
   evidence from a Java reader. Nothing about the arm, the budget, the pinned set or the
   safe-in-one-direction argument changed. An implementer who disagrees with the narrowing should
   treat it as reviewer prose that no third session has read as a draft, which is the risk the split
   exists to price.

   *Author, same day:* the finding is right, the narrowing is accepted as written, and I checked the
   claim rather than taking it: in `intent_field_reference_step_target` the binding is joined at the
   seed's `FROM`, above the `UNION`, and the recursive term below it reads `chain` and
   `intent_field_reference_step_hop` and nothing else. My round-1 sentence was half true and stated as
   though it were whole, which is the worse failure of the two. The demotion of the binding's
   five-minute figure is right too: that reason describes a census-driven join inside the drain's own
   statement, so it is a Java reader's cost and not a cell of this gate.

   What I have added rather than changed: the paragraph now names the shape the missing property would
   have, because leaving it as "find one" starts the next reader from nothing. The two cases share a
   position rather than recursion, a relation evaluated once per row of something else, and the
   binding's own registration already says which position it occupies, an unprunable `COUNT(*) OVER`
   met on an expression rather than a column. The asymmetry that keeps the fallback narrow is then
   detection cost rather than taste: a recursive term is a partition on the first top-level `UNION`,
   while tracing a join's operands to their source relations through aliases, subqueries and CTEs is
   the predicate-analysis layer R801 already owns. So an implementer needing that property reaches for
   that item rather than growing a second copy here.

   On the process note: taking the narrowing was the right call and the risk it prices is real, so this
   response is the draft-reading the split asks for. I read the edit as the author, checked its factual
   claim against the DDL, and accept it. The repointed citation is correct as well; the cross-machine
   argument is `RunawayRelation`'s and the tier-guarantee argument is `ReadBudget`'s, and the plan now
   cites each where it belongs.

### Round 2 non-blocking

* The relative budget's motivating citation is pointed at the wrong javadoc. `ReadBudget`'s own
  objection to a bare number is that "a figure large enough to be safe" is "a wall-clock threshold
  smuggled into a test tier that refuses to fail for slowness", which is an argument about what may
  decide a build, not about machines. The cross-machine argument the plan attributes to it, a
  threshold "large enough to be safe on one machine is a flake on another", is `RunawayRelation`'s,
  which says a threshold "small enough to be reliable on one machine is a flake on another". Both
  texts are in the tree and both support making the budget relative, so nothing about the decision
  changes; only the attribution is off. Worth noting that the plan's *other* use of the type, that
  asserting no duration keeps "the tier guarantee `ReadBudget`'s two arms exist to protect", reads
  that javadoc correctly, so this is one citation to repoint rather than a misreading of the type.
  *Reviewer, repointed in the sign-off commit* at `RunawayRelation`, which is the javadoc that makes
  the cross-machine argument. This one is a citation repair of the kind the gate leaves to the
  reviewer, so it needed no direction.

### Round 2 verdict

Review resolved. Both gate questions are answered and I have no remaining findings. What a consumer
gets is nothing observable today and a build that refuses a `meta_materialize` registration which
makes some other relation's read more expensive, with this relation's ten-times move attributed rather
than suspected. The design extends shapes already here rather than standing new ones beside them:
`RunawayRelation`'s rename-then-create swap, `meta_materialize_dependency`'s rule that the universe of
relations comes off the booted store, `SurfaceScanCountTest`'s `scanCount` instrument, and
`MaterializeRegistryGateTest`'s pinned roster of deliberate exceptions. Every symbol, relation, count
and quoted javadoc the plan names was checked against the tree across the two passes and holds as
named, and the one claim that did not is corrected above. The pass-on-exhaustion arm's mechanism was
verified reachable rather than assumed. What remains open is named as open: the multiple for the
budget, and whether the binding's cells ever need a structural excuse of their own.

`status:` stays `Spec` in this commit, and not because anything in the plan is unresolved. Having
taken the plan-body tweak myself at the user's direction, this reviewer session is now the spec file's
last committer, so the Spec to Ready guard in `roadmap/workflow.adoc` reads reviewer equals last
committer and refuses the flip. That guard is doing its job rather than obstructing: the narrowing
above is reviewer prose no third party has read as a draft. The flip is one command for any other
party, this review having nothing further to raise.
