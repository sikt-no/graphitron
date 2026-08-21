---
id: R770
title: "A skill that makes store slowness a database question first, not a Java one"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
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
* The harness ladder for getting a live store to poke at is documented for tests
  (`docs/architecture/how-to/testing.adoc`, "Where a store-backed test gets its store") but not
  framed as a debugging surface: `SeededStore` for "what does this view return given these rows"
  (with `SeededStore.derive` before any derived read), `CapturedStore` for real captured rows,
  `BuiltStore` for rows only a generator run writes, and the sakila example's own schema as the
  realistic population R728 probed against.
* The hard-won facts about H2's evaluation model exist only in item bodies that are deleted when
  their items reach Done: H2 inlines a view wherever it is named and eliminates no common
  subexpression; a non-recursive `WITH` is inlined too, so extracting one is not a fix; joining a
  derived relation on an expression rather than a column makes H2 evaluate that relation once per
  driving row (19.9 s against 0.13 s for the same 157 rows); a recursive step re-evaluates its
  whole input once per accumulated row; and what makes a relation expensive is being a view that
  something reads many times, not how the reader spells the read, which is why two predicate
  rewrites in R728 bought exactly nothing.
* The lever hierarchy is doctrine already (`docs/architecture/explanation/fact-model.adoc` and
  the `meta_materialize` registry): a captured fact beats a materialization because it has no
  refresh to pay for, a registration beats a rewrite when the rule is right and only slow, and a
  rewrite is the last resort because it usually changes nothing that the planner cares about.

A skill is the right container because the failure is a *reach* failure, not a knowledge-location
failure alone. The agent needs the methodology at the moment it forms the intent "debug why this
is slow", which is exactly what a skill's trigger description does and what a how-to page an
agent never opens does not.

## What the skill should carry

A `.claude/skills/` document, working name `store-performance` or `relational-debugging`, that
triggers on "why is this relation slow", "the store is slow", "the build got slower after my
view", "debug this query", and encodes:

1. **Posture first.** Slowness in a derived relation is diagnosed inside the database. Reactor
   wall-clock is not evidence (same-process per-relation timings reproduce; reactor totals sit
   inside a two-minute machine spread). Thread dumps of a killed build are guesswork and R728
   misread two of them. Writing a bespoke Java measurement program is the last resort, not the
   first move.
2. **Get a store.** The harness ladder above, ordered cheapest to most realistic, with the
   standing rules (`StoreFixtureGuardTest` means take a store from a harness rather than opening
   one; refresh materializations before reading a derived relation; always `-Plocal-db`).
3. **Measure relationally.** Time relations in isolation against a realistic population;
   `EXPLAIN ANALYZE` via `dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))`; read
   `scanCount`; use `report-inline-multiplicity` to rank suspects and per-relation timing to
   price them.
4. **Know the evaluation model.** The H2 facts listed above, stated as rules with the measured
   numbers that earned them.
5. **Controls before conclusions.** Every hypothesis gets a same-fixture control that would
   refute it, in the shape R728 settled into: snapshot the suspect into a table and re-run, join
   on the bare column, materialize in a `WITH`. Two of R728's three controls refuted the reading
   taken first.
6. **The lever hierarchy.** Captured fact, then `meta_materialize` registration, then rewrite,
   with the refresh-against-reads-avoided trade stated.
7. **Choose what to materialize, and push it down.** A registration is a shared investment, not a
   local patch: materialize the relation the cost multiplies *through*, low enough in the
   derivation tree that every reader above it benefits, not the relation that happened to look
   slow from where you stood. R728 measured both sides of this. Materializing
   `intent_node_id_decode_endpoint`, which three relations read, took each of them from 7.5 s to
   2.4 s at one 5.4 s refresh, because each had been paying for the whole shared subtree; the
   scope table's registration pays because four view bodies name it and its refresh is seventy
   milliseconds. The counter-case is the same discipline: the hop column relation had exactly one
   reader that no build-time consumer exercised yet, so every refresh bought nothing, and its
   registration moved to the stage that adds the consumer. R742's precedent is the same shape:
   the 24.5 s defect view was fixed by materializing two relations *underneath* it, not itself.
   The test the skill should state: count the readers of the candidate (the `meta_materialize`
   doctrine and `report-inline-multiplicity` both help), price its refresh, and prefer the
   deepest relation whose materialization removes re-evaluation for more readers than you.

## Fork to settle at Spec

Where the durable knowledge lives is a real fork. A skill document is agent-facing and not
build-gated; the H2 evaluation-model facts and the measurement discipline arguably belong in a
`docs/architecture/how-to/` page (gated by the schema-identifier drift check, citable from
javadoc), with the skill reduced to trigger plus procedure plus a pointer. R758 already covers
documenting the materialization registry in the architecture tree, so the how-to half of this
item should be scoped against it rather than duplicating it. The Spec decides the split; the
Backlog claim is only that the reach-for-it-first surface must be a skill, because no docs page
fires on intent.
