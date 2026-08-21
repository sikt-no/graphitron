---
id: R770
title: "A skill that makes store slowness a database question first, not a Java one"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
---

# A skill that makes store slowness a database question first, not a Java one

The fact store is a relational database, and slowness in a derived relation is a database
question. Yet when an agent session hits one, its first instincts are Java instincts: read a
thread dump, compare reactor wall-clock totals, write a throwaway Java probe. R728's own body is
the record of what that costs. It took back four conclusions, two of them readings of thread
dumps of a killed build ("the recursion is re-entered per outer row": it was not; "the recursive
reference-target views are the term": they were not) and two of them differences read off reactor
runs that were not comparable (a 1:10 module run that three repeats put at 2:40). Every finding
that stood came from doing what a database person does first: time each relation in isolation
against a realistically populated store, form a hypothesis about the evaluation model, and run
controls on the same fixture to rule the alternatives out. The methodology exists; nothing puts
it in an agent's hands at the moment slowness appears.

When this lands, a session that forms the intent "this relation is slow" gets one document that
tells it what to measure, in what order, against which store, with the evaluation-model rules the
tree has already paid for and an index of where each one is written down. What changes for a
contributor is the first hour of a slowness investigation: it starts with a per-relation timing
and an `EXPLAIN ANALYZE` against a populated store rather than with a thread dump and two
non-comparable reactor runs. Nothing changes for a consumer of the generated code or the plugin;
the consumer here is the contributor.

## Why a skill, and why now

Everything the relational-first approach needs already exists in the tree, scattered:

* The one `EXPLAIN ANALYZE` recipe lives in a roadmap item body (`roadmap/build-wall-clock-guardrail.md`,
  the "which query, and why" paragraph): render the query inlined, prefix `EXPLAIN ANALYZE`, read
  H2's `scanCount` per plan node. It is the tool that turns "this read is slow" into "this view
  is expanded 469 times", and a JFR frame cannot say that because a frame names the call site and
  not the plan.
* The static breadth metric ships as `roadmap-tool report-inline-multiplicity`, with its standing
  already calibrated by R728: the metric ranks how often a relation is named, and cost is
  measured, because 2528 namings of a 0.4 s relation is fine where 83 namings of a 20 s one was
  not.
* The harness ladder for getting a live store to poke at is documented, per subject, in
  `docs/architecture/how-to/testing.adoc`'s "Where a store-backed test gets its store" table, and
  that table is the authority: it carries six rows, and the skill must point at it rather than
  restate a subset, because a partial ladder in a second document is a wrong answer the moment a
  harness is added. What the table does not do is frame any of it as a debugging surface. The two
  facts a debugging session needs on top of it are which row answers "a populated store to time
  against" (`CapturedStore` over a real fixture document, or `BuiltStore` where the rows only a
  build writes, with the sakila example's own schema as the realistic population R728 probed
  against) and that a derived read against a seeded store returns nothing until
  `SeededStore.derive` has run.
* The hard-won facts about H2's evaluation model are mostly durable already, and that is the
  correction this Spec makes to its own Backlog premise. They are not confined to item bodies
  awaiting deletion; they are spread across four habitats no session reads at the moment slowness
  appears. `InlineMultiplicityCheck`'s class javadoc states that H2 inlines a view wherever it is
  named and eliminates no common subexpression, and that the multiplicities compound down a tree.
  `meta_materialize`'s seeded `reason` rows each state one rule with the number that earned it: a
  relation joined on a coalesced coordinate rather than a column is evaluated once per driving row
  at seventy milliseconds an evaluation, and a recursive term joins its own accumulated output
  against a relation once per accumulated row, so a relation named in the step is evaluated as
  many times as the walk has rows. `intent_argument_scope_table`'s own `COMMENT ON` carries the
  expression-key rule and adds the fix that follows from it: projecting the expression as a column
  in an inner derived table first, which that comment calls load-bearing rather than a formatting
  choice, because collapsing it back into one join is a two-orders-of-magnitude regression. The
  clause a reader is likely to conflate with that one, that restructuring the coalesced join is no
  fix because H2 inlines the derived table straight back, is in this registration's
  `meta_materialize.reason` row and not in the comment. The two are about different joins, and
  which one they are about is the whole distinction: an extraction that leaves the join key an
  expression buys nothing, and an extraction that turns the key into a column is the 150-fold fix.
  And `docs/architecture/explanation/fact-model.adoc` already
  runs a sequence of measured H2 rules: recursive-view termination, the window-function and
  recursive-term rule that a predicate outside a view cannot prune it, and the materialized-view
  prohibition with its four traced defects.

  So the gap is assembly and reach, not durability. Three of the rules are stated per-registration
  or per-relation, where a reader has to already be standing at that relation to meet them, and
  the general form is nowhere: what makes a relation expensive is being a view that something
  reads many times, not how the reader spells the read, which is why two predicate rewrites in
  R728 bought exactly nothing. Three things are genuinely held only by item bodies and will go when
  those files do: the expression-key pair of 19.9 s against 0.13 s for the same 157 rows with its
  three controls (R765), R728's endpoint arithmetic quoted under point 7 below, and the top rung of
  the lever hierarchy, per the next bullet.
* The lever hierarchy is the one piece of doctrine this inventory does *not* find durably stated,
  and an earlier draft of this Spec claimed otherwise. Its three rungs are: a captured fact beats a
  materialization because it has no refresh to pay for, a registration beats a rewrite when the rule
  is right and only slow, and a rewrite is the last resort because it usually changes nothing that
  the planner cares about. The two habitats the draft named hold the middle rung and nothing else.
  `meta_materialize.reason`'s column comment ("this column is where half the materialization
  doctrine lives") states a two-way distinction between a hand-written derivation, which argues in
  its own table comment that no view could express its rule, and a registration, which argues that a
  view expresses the rule correctly and only too slowly. `Materializations`'s class javadoc states
  why a registration exists and forwards the contributor-facing rationale to `fact-model.adoc`.
  Neither ranks a captured fact above a materialization, and neither says a rewrite usually buys
  nothing. The top rung is stated twice in the tree and both times in a body that is deleted at Done:
  R765's ("a captured fact has no refresh to pay for at all", with the measured pair of one
  registration worth 1:23 against another costing 2:33, same mechanism and opposite signs) and
  R728's. `fact-model.adoc` carries only the refresh half of the trade, in the materialized-view
  ruling's closing note that a snapshot pays off only where a relation is read many times between
  writes, and its doctrine sentence still states the narrow pre-registry rule; that page not knowing
  the registry is exactly R758's subject. So the levers are not a pointer this item can simply
  sharpen later: this item writes them, onto the page, for the reason the next section gives.

A skill is the right container for the *reach* half: the agent needs the methodology at the moment
it forms the intent "debug why this is slow", which is what a skill's trigger description does and
what a page nobody opens does not. It is the wrong container for the durable half, and the
inventory above says why: three of the four rules are stated where a reader has to already be at
that relation to meet them, so what is missing is one statement of the general form in the place
this tree already puts measured H2 rules. The two halves are separated in "The fork, settled"
below.

## What the skill carries

`.claude/skills/store-performance/SKILL.md`, triggering on "why is this relation slow", "the store
is slow", "the build got slower after my view", "debug this query", and encoding:

1. **Posture first.** Slowness in a derived relation is diagnosed inside the database. Reactor
   wall-clock is not evidence (same-process per-relation timings reproduce; reactor totals sit
   inside a two-minute machine spread). Thread dumps of a killed build are guesswork and R728
   misread two of them. Writing a bespoke Java measurement program is the last resort, not the
   first move.
2. **Get a store.** A pointer into the testing page's table rather than a copy of it, plus the two
   debugging-specific facts named above and the standing rules: `StoreFixtureGuardTest` means take
   a store from a harness rather than opening one, a derived read against a seeded store needs
   `SeededStore.derive` first, materializations are refreshed before a derived relation is read,
   and every command carries `-Plocal-db`.
3. **Measure relationally.** Time relations in isolation against a realistic population;
   `EXPLAIN ANALYZE` via `dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))` beside
   the read under test, behind an environment-variable guard; read H2's `scanCount` per plan node;
   use `report-inline-multiplicity` to rank suspects statically and per-relation timing to price
   them, because the metric ranks how often a relation is named and only a timing says whether
   that matters. R772 is landing a `psql` line onto a live dev-loop store, which is a better
   surface for the same step than a guarded print; the skill states the print recipe now and the
   console becomes the first arm when it ships (Roadmap entries).
4. **Know the evaluation model.** Not a copy of the rules, which is what the docs half is for, but
   the index into them: which habitat states which rule, so a session can read the general form on
   the page and the per-registration arithmetic in `meta_materialize.reason` beside it.
5. **Controls before conclusions.** Every hypothesis gets a same-fixture control that would
   refute it, in the shape R728 settled into: snapshot the suspect into a table and re-run, join
   on the bare column, materialize in a `WITH`. Two of R728's three controls refuted the reading
   taken first.
6. **The lever hierarchy.** Captured fact, then `meta_materialize` registration, then rewrite,
   with the refresh-against-reads-avoided trade stated. The rungs themselves are stated on
   `fact-model.adoc` by this item, on the same terms as the evaluation-model rules; the skill's step
   is the pointer and the order of reach.
7. **Choose what to materialize, and push it down.** A registration is a shared investment, not a
   local patch: materialize the relation the cost multiplies *through*, low enough in the
   derivation tree that every reader above it benefits, not the relation that happened to look
   slow from where you stood. R728 measured both sides of this. Materializing
   `intent_node_id_decode_endpoint`, which three relations read, took each of them from 7.5 s to
   2.4 s at one 5.4 s refresh, because each had been paying for the whole shared subtree (R728's
   own in-flight measurement; the registry's shipped rows are the four in `meta_materialize`). The
   argument-scope registration pays for a different reason and its numbers say so: five namings
   across four view bodies, one of them joining on a coalesced coordinate so nothing prunes it,
   and seventy milliseconds is the cost of *one evaluation* against sixty-nine driving rows, not
   the refresh. The counter-case is the same discipline: the hop column relation had exactly one
   reader that no build-time consumer exercised yet, so every refresh bought nothing, and its
   registration moved to the stage that adds the consumer. R742's precedent is the same shape:
   the 24.5 s defect view was fixed by materializing two relations *underneath* it, not itself.
   The test the skill should state: count the readers of the candidate (the `meta_materialize`
   doctrine and `report-inline-multiplicity` both help), price its refresh, and prefer the
   deepest relation whose materialization removes re-evaluation for more readers than you.

## The fork, settled

The Backlog draft left one fork: whether the durable knowledge lives in the skill or in a docs
page, with the skill reduced to trigger plus procedure plus a pointer. It splits, and the line is
drawn by which surface the build can hold to account.

**The evaluation-model rules go on `fact-model.adoc`, not in the skill.** That page already runs a
sequence of measured H2 rules stated as rules for the next author, so this extends a shape in the
tree rather than standing a second one beside it, and there is no new page to justify. It is also
the only one of the two surfaces with a gate: `SchemaIdentifierDriftCheck` scans every `.adoc`
under `docs/architecture` and fails the build on a relation name that no longer exists in the
booted store, so a rule written there naming a relation cannot rot silently. `.claude/skills/` is
scanned by nothing (`TransientCitationCheck` excludes it deliberately), so the same sentence in a
skill document is a copy that drifts unobserved.

**The skill carries what a gate cannot check anyway:** posture, the order of operations, the
control discipline, the command recipes, and an index naming which habitat holds which rule. It
names tools and harnesses, which are Java symbols that a rename shows up as a compile error or a
grep hit, and it names no relation and quotes no per-relation number, which are exactly the things
that drift. That division is the reason the two documents will not diverge: they do not restate
each other.

**Not a new `how-to/` page.** The measurement discipline is a procedure an agent needs on trigger,
which is the skill; the rules are a contributor-facing explanation of the store, which is
`explanation/fact-model.adoc`. A third document holding half of each is the parallel mechanism this
project's principles reject, and it would be the surface nobody reads.

## The lever hierarchy lands on the page too

The inventory above found the top rung ("a captured fact beats a materialization, having no refresh
to pay for") stated only in R765's and R728's bodies, both of which are deleted at Done. That makes
it a third measurement this item is on the hook for, and deferring it to R758 does not cover it:
R758's body claims the registry narrative and the widened doctrine sentence, and says nothing about
ranking a captured fact above a registration or about a rewrite being the last resort. Deferring the
levers to R758 would defer them to nobody.

So the hierarchy is written onto `fact-model.adoc` here, and the R758 note stays narrow. The same
argument that put the evaluation-model rules on the page applies unchanged: the page is the surface
with a gate, the rungs name `meta_materialize`, and a backticked relation name in a rule there
cannot rot silently. It is one paragraph next to the four this item already adds, and it sits
naturally against the materialized-view ruling's existing refresh-cost note ("a snapshot only pays
off where a relation is read many times between writes"), which is half of the trade already. The
cost is that a fourth item now writes to that page, which the Roadmap entries section already
handles for three.

The two alternatives are named here because each was considered and each fails on this item's own
logic. Leaving the levers to the skill alone would put one durable rule in the surface nothing
checks, which is the arrangement "The fork, settled" rejects. Widening R758 to claim the paragraph
is coherent but makes this item's step 6 point at nothing until R758 lands, and R758 is Backlog with
no one on it.

The numbers behind the rung (the 1:23 against 2:33 pair) travel with it, on the same terms as R765's
expression-key figures in the Roadmap entries section.

## Implementation

### `.claude/skills/store-performance/SKILL.md`

New skill, the seven points above in that order, with these constraints on the writing:

* The trigger description carries the intent phrasings listed above, and says what the skill
  refuses as well as what it does, in the shape `explain`'s description already uses.
* Every relation name and every per-relation number is a pointer, never a copy: the reader is sent
  to `meta_materialize`'s `reason` rows, to a relation's own `COMMENT ON`, or to the page. The
  drift argument above is void if the skill quotes what the gate is watching.
* The harness step points at the testing page's table and states only the two debugging-specific
  facts, for the same reason.
* Commands are given verbatim and each carries `-Plocal-db`.
* The posture section states the four retracted R728 conclusions as the cost of the Java-first
  instinct, without naming the item: a session reading the skill in six months has no `roadmap/`
  directory to look them up in, and the retractions are the argument whether or not the item that
  produced them still exists.

### `docs/architecture/explanation/fact-model.adoc`

Extend the existing run of measured H2 rules with the general form the page does not yet state,
after the correlated-view paragraph and before the materialized-view prohibition, since the
prohibition is already the answer a reader reaches for after meeting these:

* **View inlining and no common-subexpression elimination**, with the compounding-down-a-tree
  consequence and a pointer to `report-inline-multiplicity` as the static ranking. Today this is
  javadoc on `InlineMultiplicityCheck` only.
* **A non-recursive `WITH` is inlined too**, so extracting a relation for tidiness is not a fix.
  Today this exists only as a measured control in R765's body, the same 19.6 s as the unextracted
  form. Write it beside the rule below rather than apart from it, and say what separates them, or
  the page states two rules that read as contradicting each other: extraction alone changes no join
  key and buys nothing, while extraction that projects the expression *as a column* and joins on
  that column is the 150-fold fix. `intent_argument_scope_table`'s comment is the live exemplar of
  the second, calling its inner derived table load-bearing, so it is the wrong citation for the
  first.
* **A derived relation joined on an expression rather than a column is evaluated once per driving
  row**, with the 19.9 s against 0.13 s pair for the same 157 rows and the three controls that
  isolated it. Today it is in R765's body and will be deleted with it.
* **A recursive term re-evaluates a relation named in its step once per accumulated row.** Today
  the hop-column registration's `reason` states it; the page should carry the general form.
* **The closing rule that ties the four together:** what makes a relation expensive is being a
  view something reads many times, not how the reader spells the read. This is the sentence that
  would have saved two of R728's rewrites, and it is nowhere today.

And one paragraph on the lever hierarchy, placed against the materialized-view ruling's refresh-cost
note rather than in the rules run above, since it is about which lever to reach for and not about
how H2 evaluates a relation:

* **Captured fact, then registration, then rewrite.** A captured fact beats a materialization
  because it has no refresh to pay for at all, with the measured pair of one registration worth 1:23
  against another costing 2:33, same mechanism and opposite signs. A registration beats a rewrite
  where the rule is right and only slow, which is the distinction
  `meta_materialize.reason`'s column comment already draws. A rewrite is the last resort because it
  usually changes nothing the planner cares about, which is the closing rule above seen from the
  other side. Today the top rung is stated only in item bodies that are deleted at Done.

The registry *narrative* is R758's and is not written here; the rungs name `meta_materialize` in
passing, which is what the drift gate wants and is not the same as explaining how to register a
relation.

## Tests

No build gate is added, and the reason is not laziness about gates. The skill is prose no test
parses, and a meta-test asserting that a skill document exists would pass on an empty file. What
the item does get is two existing gates and one named acceptance:

* The `fact-model.adoc` edit is covered by `SchemaIdentifierDriftCheck` (`check-schema-identifiers`
  in the roadmap-tool run), which fails the build on any relation named in the new paragraphs that
  the booted store does not hold. The rules name relations, so this is real coverage rather than a
  formality.
* The docs render runs in the `docs` module on every build, so a malformed block fails CI, and the
  roadmap-tool's `check-adoc-tables` step catches a markdown table if one is written.
* **Acceptance is a dry run, recorded in the item before it moves to In Review.** Take one relation
  the registry does *not* cover, follow the skill from its first step, and record the timing, the
  `EXPLAIN ANALYZE` scan counts, and the verdict. If the skill cannot be followed end to end from a
  cold session without reading anything it does not point at, it is not finished. This is the same
  standard the item applies to R728's findings: a methodology that has not been run once is a
  claim, not a procedure.

## Roadmap entries

* **R758** (`fact-model-page-learns-the-registry`, Backlog) edits the same page, for the registry
  narrative and the widened doctrine sentence. The two are separate paragraphs and either order
  works; whoever lands second rebases. The registry narrative is R758's and is not written here.
  The lever hierarchy is *this* item's paragraph, per the section above: R758's body does not claim
  it, so if this item does not write it, nobody has. Note also that `Materializations`'s javadoc already forwards a
  reader to this page for the registration rationale, so the pointer R758 exists to make land is
  live and currently dangling.
* **R765** (`expression-keyed-joins-into-derived-relations`, Backlog) holds the expression-key
  measurement and its three controls. This item lifts the rule and the numbers onto the page, which
  is what stops them dying with R765's file at Done. Neither item blocks the other, and if R765
  lands first its Done commit should leave the numbers behind in the page rather than deleting them
  with the body.
* **R772** (`dev-loop-store-sql-console`, Spec) turns "get a store to poke at" from a throwaway
  test into a `psql` prompt against the live dev-loop store. It is the right first arm of the
  measure step once it ships. This item does not wait for it: the guarded-print recipe works today,
  and the skill's step 3 is written so that adding the console arm is an edit to one paragraph.
* **R771** (`nested-jooq-grain-and-anchor-skill`, Ready) is the authoring-time sibling and already
  names this item as the cost half of the same instinct problem. Two skills, not one: the trigger
  moments differ (writing a query against being ambushed by a slow one), and a document that fires
  on both would be reached for at neither. Its Spec settled the same split this one does, doctrine
  into `fact-model.adoc` first and the skill curating after, and it leaves the H2 evaluation-model
  inventory to this item by name. So three items now write to that page: R758's registry narrative,
  R771's grain-and-drive-from section, and this item's evaluation-model rules. All three are
  separate paragraphs with no shared sentence, so any order works and whoever lands second rebases.
  If the page's rules run outgrows one page under the three of them, the split to reach for is a
  `how-to/` page on authoring store reads, which is the same fallback R771 named, and not a page
  per item.
* **R728** (`nodeid-effective-at-every-coordinate`, In Progress) is the source of the retracted
  conclusions and of the endpoint arithmetic under point 7. Its body is deleted at Done, so the
  facts this item quotes must be lifted before that happens. If R728 reaches Done first, harvest
  from its final commit's diff rather than trusting this body's paraphrase.

## What this item deliberately does not do

**It does not add a performance gate.** `report-inline-multiplicity` reports rather than gates on
purpose, its own javadoc explaining that a defensible ceiling does not exist yet, and a timing gate
over a machine-dependent number is the wall-clock mistake the posture section warns against. The
build-wall-clock question has its own item.

**It does not restate the harness ladder,** for the drift reason above. If the ladder is hard to
follow from a debugging standpoint, the fix is a sentence on the testing page, not a second copy in
a skill.

**It does not touch `graphitron-model.sql`'s comments or the registry's `reason` rows.** Those are
per-relation justifications and belong where they are; this item adds the general form above them
and an index to them, and deletes nothing.
