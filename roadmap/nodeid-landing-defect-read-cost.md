---
id: R939
title: "A graphitron:dev round on a consumer schema answers in seconds again: the @nodeId landing verdict expands an unregistered expensive view per driving row"
status: Spec
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# A graphitron:dev round on a consumer schema answers in seconds again: the @nodeId landing verdict expands an unregistered expensive view per driving row

## Goal

A `graphitron:dev` round on a real consumer schema answers in seconds again. Today one costs
minutes, and on the schema that exposed it a round does not finish at all: a dev session on the
`sis` consumer schema ran 31 minutes without ever opening its port, and its predecessor died at
about 17 minutes having never opened one either. The whole cost is one read of one derived
relation, and it is paid on every round, at boot and again after every save, so the dev loop is
unusable at that schema size. The consumer feels it as a validation gate that cannot run: a
migration branch has 13 author-error fixes applied against the very verdict this relation
computes, and no way to check them.

Two terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and then answers its verdicts out of by SQL. An
`intent_` *relation* is one of those answers stated as a view over captured facts rather than as
Java walking a model. A *registration* is a row of `meta_materialize` that keeps such a rule in a
view under a `_live` name and moves the canonical name every reader spells onto a table the
capture refills once per pass, so readers meet stored rows instead of re-evaluating the rule. The
*register* is that table of rows. A cost paid at *refresh cadence* is paid once per capture, when
the register is refilled; one paid at *read cadence* is paid every time a reader names the relation.
The *detection pass* is the set of reads `FactCapture.detect` makes against a freshly captured store
to turn its verdict relations into build errors, and it runs on every generator pass, so it is the
read cadence a dev round feels.

When this lands, a dev round on a consumer schema of that size pays a bounded, measured cost for
the `@nodeId` landing verdict, and the verdict itself is unchanged: the same schemas are refused,
with the same messages, for the same reasons. Nothing about what the check *decides* is in scope
here. Only what it costs to ask. The item also leaves behind a build-time guard that names the
shape which made this defect invisible to the cost gate that was pointed at it, so the next reader
added to the detection pass has to write down what it reaches before it ships.

## What the measurement says

Measured against a copy of the real `sis` store, the population that exposed this, with H2 query
statistics rather than hand-rolled timing. The store copy came from the idle stamp directory, so
the live session was never perturbed. Numbers below are single-pass, which ranks rather than
measures; the separation is about four orders of magnitude, so the ordering is not in question
while the individual figures stay provisional. The implementer re-takes the two figures the plan
turns on with the repeated-run recipe in the `store-performance` skill before recording them
anywhere durable.

`FactCapture.detect` issues seven reads per pass. Six of them, on that population:

| read | time |
|---|---|
| `intent_node_id_decode_defect` | 747 ms |
| `intent_authored_claim_conflict` | 262 ms |
| `intent_argmapping_projection_defect` | 108 ms |
| `intent_resolved_node_key_projection` | 69 ms |
| `intent_field_unlowerable_ordering` | 58 ms |
| `intent_reference_for_application` | 3 ms |

The seventh, `intent_node_id_decode_landing_defect`, did not return in 24 minutes.

Bisecting that view's body against the same population localises it to one term:

| slice | time |
|---|---|
| child `intent_node_id_decode_hop` | 41.5 s for 377 rows |
| the `judged` CTE | 115 ms (310 rows) |
| the `judged` CTE with its correlated `branches` subquery removed | 88 ms |
| the `stopped` CTE, which is `judged` joined to the hop relation | 22.8 s |
| arm 1, `PATH_STOPS_SHORT` | 28.1 s |
| arm 2, `LANDING_TYPE_DISAGREEMENT` | did not return in 300 s |

Every other child answers in single-digit milliseconds. So the cost is not cardinality anywhere:
the driving populations are hundreds of rows.

**The shape.** `stopped` is a non-recursive `WITH`, which H2 inlines exactly like a view with no
common-subexpression elimination, and arm 2's only reference to it is inside a correlated
`NOT EXISTS`. A 23-second expansion re-evaluated per driving row is the whole story, and it is the
form the fact-model page already names under "Derived reads are views, not stored facts". The
correlated `branches` subquery in `judged` is *not* the term, which the third row above rules out;
it is the one hypothesis worth pre-empting because it is the thing a reader notices first.

**What the hop's own body names, unpriced.** `intent_node_id_decode_hop` drives from
`intent_node_id_decode_endpoint`, whose subtree answers in about a hundred milliseconds here (the
`judged` CTE reads it directly at 115 ms). On the inner side of three `LEFT JOIN`s it names the two
reference-target views, `intent_argument_reference_step_target` and
`intent_input_field_reference_step_target`, each a recursive walk over a registered hop relation,
and an inline derived table computing `COUNT(*) OVER (PARTITION BY ...)` across the whole of
`sql_referential_constraint`; it then closes with `MAX(position) OVER (PARTITION BY ...)` over its
own rows. A naming on the inner side of a join is evaluated once per driving row, which the
`intent_node_id_decode_column_live` registration's `reason` records as the shape no spelling
escapes, and a window sees its whole partition whatever the outer predicate says, so none of the
four terms can be pruned by a reader's join condition. Which of them carries the 41.5 s is not
known: 377 rows under a window is not seconds by itself, and a recursive walk or a catalog-wide
window re-evaluated 377 times could be. The plan's first step is that bisection, because the register
records what happens when a rule is priced with a re-evaluation still inside it: the
`intent_mutation_payload_key_membership_live` row says a rule with a re-evaluation inside it should
be rewritten before it is priced, and names the refresh figure that rule would have cost otherwise.

**Why this is a regression and not a pre-existing cost.** `intent_node_id_decode_hop` has been
expensive since well before the verdict landed. What changed is that nothing used to evaluate it at
read cadence. Its only other reader in the DDL is `intent_node_id_decode_hop_column_live`, the
source view of a registered target, so the hop relation was paid once per refresh, and
`intent_node_id_decode_column_live` sits above that target rather than above the hop itself.
`intent_node_id_decode_landing_defect` is the first reader to expand it live, it expands it more
than once, and it carries no registration. Confirmed from the DDL independently of the timings: the
landing-defect view has zero `FROM`/`JOIN` references anywhere, so `NodeIdLandingDefects.detect`
is its only reader in the tree, and no Java reader names the hop relation at all.

**Why the read-cost gate did not see it.** `DerivedReadCostTest` already carries a cell for the
landing-defect view and prices it at 709 ms unregistered against 224 ms registered on its
twelve-unit fixture. Its instrument is rows visited, its population is a fixture of a dozen node
clusters, and the term here is a per-row re-expansion whose cost is a function of the consumer's
population. The gate held a correct claim about the wrong population, which is why the guard under
Tests names a shape and prices nothing.

## Implementation

The plan is a measurement with three named outcomes, each landing on a shape the tree already has,
and the measurement comes before any DDL is written. The lever hierarchy the fact-model page states
is a captured fact, then an index on a stored column, then a rewrite, then a registration; the
`store-performance` skill adds that the relation to register is the deepest one whose
materialization stops re-evaluation for the most readers, not the one that looked slow from where the
reader stood. The landing-defect verdict is where the reader stood. The hop is one level down and its
own body has not been bisected, so registering it now would price a rule with a possible re-evaluation
still inside it and then blindfold the planner to whatever that was.

**Step 1: bisect the hop on the exposing population.** On the `sis` store copy, with
`SET OPTIMIZE_REUSE_RESULTS FALSE`, query statistics on and at least three runs per slice, per the
skill's step 3. The slices, each a standalone statement:

- `intent_node_id_decode_endpoint` alone (expected around 100 ms; the control that the driving rows
  are cheap).
- The inner `walked` select with the closing `MAX(position) OVER` removed: the hop without its window.
- `walked` with each `LEFT JOIN` removed in turn: without `tg`, without `itg`, without `d`.
- `intent_argument_reference_step_target` and `intent_input_field_reference_step_target` alone, and
  the `d` derived table alone.
- The hop whole, as the baseline the slices are read against.

Read the result as the skill's step 5 does: the term whose removal takes the baseline to the
endpoint's figure is the term. Every slice is a `SELECT` against the copy and none writes.

**Step 2: the lever the bisection names.** Three outcomes, and the default when the figures do not
separate:

- *One inner-side term carries it and it is a named relation* (`tg` or `itg`, the recursive
  reference-target walks). The lever is a registration of that relation, not of the hop: it is
  deeper, it is named from other view bodies as well, and a table there stops the per-row
  re-evaluation for every reader above it including the hop. Whether both reference-target views
  need it or one does is what the slices say.
- *The `d` derived table carries it.* It computes how many foreign keys connect each (table,
  referenced table) pair across the whole catalog, per hop row. That is a catalog-family fact with
  no relation stating it, which is the shape the shape-problem item calls a supertype the readers
  reconstruct, and its cheapest correct home is a relation the catalog family states once, keyed on
  the pair, that the hop joins on a column. Promote it to a named relation with a `COMMENT ON` saying
  what one row asserts, and join it; whether that relation then needs a registration is a second
  measurement, and the skill's step 7 says to record the split either way.
- *The window carries it, or the cost is spread across the endpoint tree with no single term
  standing out.* The window exists to carry `last_position`, a marker the hop's key-column child asked
  for so it would not name the hop a second time; if the window is the term, state the marker as its
  own relation keyed on the branch (`MAX(position)` grouped over the hop's key rather than windowed
  over its rows) and have the two readers join it, leaving the hop prunable. If nothing separates, the
  lever is the registration of `intent_node_id_decode_hop` as originally proposed: the rule is
  correct as a view and too expensive to evaluate per naming, and both of its readers gain.

Whichever outcome, the reader's arm 2 stays as written. Once the relation it names per row is a table
or a prunable view, a correlated `NOT EXISTS` over hundreds of driving rows is a seek per row, which
the fact-model page says nests freely.

**Rung 1 is unavailable for a seam reason, not a family reason.** A captured fact would be the
graphitron gatherer writing the hop rows in a stage before any view names them. The hop's subtree
reaches `intent_argument_scope_table` through the argument reference-target walk, and that is a
registered target the refresh itself fills; `FactCapture.capture` runs every hand-written producer
before `Materializations.refresh`, so a stage cannot see the rows it would need. The
`intent_node_id_decode_column_live` registration records the same seam for the same family. That the
hop crosses families is true and is what the `meta_relation` row below states as data; it is not what
blocks a stage.

**Step 3: measure the round before committing.** Two figures decide whether the chosen lever stands,
both taken on the `sis` workspace with the patched model rather than on a fixture:

1. *Refresh price in-transaction*, for every registration the outcome added or moved. The standalone
   figures from step 1 are rankings, taken outside the transaction that has just written the relations
   the rule reads. The price is the `done in` line under `mvn generate-sources -X` (recipe in
   `docs/architecture/how-to/dev-loop-internals.adoc`), taken once on a cold store, where the dev
   boot pays `refreshAnalysing`, and once on a warm one, where every later round pays `refresh` inside
   the capture transaction. Both cadences are what a dev session runs.
2. *The verdict read*, `NodeIdLandingDefects.detect`'s statement, timed the same way as step 1.

The lever stands if the round's total for this relation, added refresh plus read, is in low
single-digit seconds on that population. If it is not, the next outcome down the list is tried, and
every figure from a rung tried and lost goes in the winning registration's `reason` column, or in the
promoted relation's comment, so the next reader does not re-run it.

**The diff, for a registration-shaped outcome.** Written for the hop; the same list applies with the
names changed when the bisection points at a reference-target view.

`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`:

- `CREATE VIEW intent_node_id_decode_hop` becomes `CREATE VIEW intent_node_id_decode_hop_live`,
  text unchanged. Its view comment becomes the standard `_live` note (the
  `intent_node_id_decode_hop_column_live` comment beside it is the form), and each column comment
  becomes the standard "the X of a row of this rule, materialized into
  `intent_node_id_decode_hop`.X" form.
- `CREATE TABLE intent_node_id_decode_hop` with the same twenty-one columns in the same order, so
  `INSERT INTO target SELECT * FROM source` is the view's own rows (`MaterializeRegistryGateTest`
  checks the column lists match). The table inherits the view's long comment plus the standard
  materialization sentence, and every column comment moves onto it unchanged. Declare the primary
  key the `use_site` column comment already states in prose, `(graph_name, use_site,
  origin_source_name, origin_schema, origin_table, position)`, with those six `NOT NULL`. Keyed
  registered targets are the convention the model is moving to, five of the twenty already carrying
  one. Whether the key holds is checked in step 1 on the same store copy, `COUNT(*)` against
  `COUNT(DISTINCT ...)` over the six and a null count per column; a duplicate or a null there is a
  finding about the endpoint relation, filed on its own, and the table ships keyed on what holds with
  its comment saying what a row asserts and why the stated grain is narrower than the prose if it is.
- A `meta_materialize` row `('intent_node_id_decode_hop_live', 'intent_node_id_decode_hop', ...)`.
  The `reason` states, in this relation's own terms and with its own arithmetic: the two readers and
  which cadence each reads at; the term the bisection named and the figures of the slices that ruled
  the others out; that the verdict names the hop once textually and expands it per driving row
  through a correlated `NOT EXISTS` over an inlined CTE; the in-transaction refresh price from step 3
  with the population it was taken on; that the refresh is neutral because the hop-column position
  was already paying one evaluation of this rule; and that a standalone timing of this view is a
  ranking while the `-X` line is the price.
- A `meta_relation` row and, if none fits, a `meta_grain` row for `intent_node_id_decode_hop_live`.
  `MetaDeclarationGateTest` refuses a new observed relation with no `meta_relation` row, and the
  frozen roster in `undeclared-relations.txt` only shrinks, so the new view cannot be added there;
  the `intent_node_id_decode_landing_defect` row is the exemplar. The declared owner turns the
  crossing claim above into data, since that test's owner-read rule holds a declared view to the
  relations its owner may read. The table keeps the canonical name and its roster line still
  matches; declare it too and remove its line, which is the direction the roster ratchets.
- No index on the new table in this item. The verdict joins the hop on its five branch columns plus
  `position = last_position`, and the primary key's index serves that as a prefix. The
  `intent_node_id_decode_column_live` reason records an index bought for one reader losing on
  measurement once the key widened, so a second index is a measured follow-up in rows visited on the
  read-cost gate's fixture, not an assumption.

Gates a new registration moves, each in one pass and each confirmed by the verification build rather
than a scoped one:

- `MaterializeRegistryGateTest`: `REGISTRATIONS` 20 to 21; `REFRESH_STAGES` moves if the new
  position opens a stage of its own, which it will if nothing else already refreshes at the depth
  the hop needs, earlier than the hop-column position that reads it. Both figures are edited to what
  the booted store reports, per that test's rule that a registration may move them in the commit
  that argues for it.
- `FactCaptureAgreementTest`: `intent_node_id_decode_hop_live` joins the `Arm.DERIVED`
  registrations beside `intent_node_id_decode_hop_column_live`. This is the two-way
  `source EXCEPT target` proof the registration convention asks for, held on every build.
- `DerivedReadCostTest`: `READERS_IN_SCHEMA` holds, one view leaving and one arriving;
  `READERS_WITH_CELLS` and `CELLS` move by what the walk reports; the `KNOWN_NON_MONOTONIC` rows that
  spell `intent_node_id_decode_hop` re-spell as `intent_node_id_decode_hop_live`, on the precedent
  the carrier registration set in that set's comments; any new pair the twelve-unit fixture reports
  is answered as that set's javadoc demands, measured and either declined or pinned with its figures.
- `FactSchemaGateTest`: comment-echo and column-comment gates over the new `_live` view and the table.

`Tables.INTENT_NODE_ID_DECODE_HOP` is regenerated from the DDL as a table; no Java reader in main
names it, so nothing recompiles differently.

For the promoted-relation outcome the same gates apply to the new relation (a `meta_relation` row, a
grain, comments), and the hop's own text changes to join it, which is the one outcome where the
verdict's rows need the before-and-after population check under Tests rather than the agreement test
alone.

No docs change beyond the DDL comments. The fact-model page counts registrations as "twenty" and
"twenty-two" in measured narratives about specific passes, which are history rather than a live
roster, and stay.

## Tests

**The verdict is unchanged, and the existing cases are the pin.** Under a registration outcome the
landing-defect view's text does not change; it names `intent_node_id_decode_hop` and that name
resolves to a table holding the view's own rows. Every case in `NodeIdDecodeLandingDefectTest` (both
verdicts, the negative control beside each, the totality gate, the stopped-short exclusion, the
participant-route exclusion in both directions, the sibling-graph partition) and
`NodeIdLandingDefectsTest` (the same over a captured store, the argument and input-field leads, the
classification-domain gate) runs unchanged and passes. Those cases going green after the
registration is itself evidence the refresh order was derived correctly, because `SeededStore.derive`
fills every registered target through `Materializations.refreshAll` and a target refreshed before the
relations it reads would hand the verdict no rows. Under the promoted-relation outcome the hop's text
changes and `NodeIdDecodeReachTest`'s cases in the model tier are the pin for the hop's own rows, the
landing cases above for the verdict.

No `sis`-shaped fixture. A seeded store of that population's shape, 13 `PATH_STOPS_SHORT` and 6
`LANDING_TYPE_DISAGREEMENT` over 4 node types and 8 landing tables, would be dozens of rows, which the
`store-performance` skill says can tell nothing about cost, and its verdict coverage is already pinned
case by case above. The population check happens where the population is: on the `sis` store,
before and after, the implementer records the row count and verdict count
`NodeIdLandingDefects.detect` returns and the two-way `EXCEPT` between every `_live` view the outcome
added and its target, all empty. Those figures go in the Done-gate commit message; the `reason`
column carries the cost figures only.

**The guard: the detection pass's read-cadence reach, pinned by equality.** A new test in
`graphitron-model` beside `MaterializeRegistryGateTest`, structural over a booted schema with no
captured rows, asserting no duration and no scan count. For each relation the detection pass reads,
it walks the view's derivation through unregistered views, stopping at tables (registered targets
and base relations alike), the walk `MaterializeDependencies.registrationsReachedByView` already
performs, exposed as a sibling that returns the views walked rather than the registrations met. That
set is what the reader evaluates on every pass, and it is pinned per reader by equality, the ratchet
`DerivedReadCostTest` uses for its pinned sets: a relation entering a reader's reach fails the build
until the author edits the pin, and the javadoc on the pin says what a delta means, price the
relation on a populated store per the `store-performance` skill and register or restate it before
adding the row, or record why its cost at read cadence is acceptable. The pin is stated over tables
rather than over the register, so it keeps its meaning when the register dissolves: a relation a
gatherer stores is a table whatever schedules it, and the walk stops there either way. Where a
pinned relation is also paid at refresh cadence today, a comment on the row says through which
registration, which is the annotation that would have named the hop when the landing-defect reader
arrived: a relation already priced once per capture, appearing in a per-pass reach with nothing
between it and the reader. After this item the hop leaves the landing-defect reader's set under a
registration outcome, the walk stopping at the table, and `intent_node_id_decode_endpoint` stays in
it, read live by the `judged` CTE at 115 ms on the exposing population, which the pin records as
known.

The reader roots come from the detection families rather than from a list in the test. Each family
class exposes the relation its `detect` reads as one public constant used by the query itself, and
`StoreDetections` exposes the roster of those constants beside its components, so a family swapped or
repointed edits the roster where its component sits; the gate reads the roster. Scope is the
detection pass, not every consumer read: the diagnostic surface the language server reads has the
scan-count ceilings in `graphitron-lsp`.

A wall-clock gate is explicitly *not* in scope. Nothing in this repository captures a consumer schema
of the size that exposes this, and a fixture that did would be a build wall-clock gate, which the
build-wall-clock item owns.

## Other solutions we've considered

**Registering `intent_node_id_decode_hop` first and bisecting later.** The registration is the
plan's default outcome, and taking it before the bisection was the draft's shape. Declined as an
order rather than as a lever: the register's own rows say a rule priced with a re-evaluation inside
it is priced wrong, and the DDL rename, the reason prose, the roster move and four gate edits would
all be built on an assumption one afternoon of slices settles.

**Rewriting arm 2 so it stops correlating on `stopped`.** Removes the per-row re-expansion and
nothing else: each arm still expands the hop once, at 22.8 s a time on the exposing population, so
the round is bounded and still not seconds. It also touches the verdict's text, which puts its rows
in question where a lever below it does not. Not a step in the plan; if a lever below leaves the
correlated arm measurably dear, it is re-measured then.

**Reading the terminal hop off `intent_node_id_decode_hop_column`, which is already a table.**
Attractive because no new registration is needed. That relation carries no arriving-table columns, so
`stopped` could not compute its verdict from it without widening a registered target's shape, and it
joins the hop to the foreign-key column pairs, so a `NAME_MATCH` terminal hop, which carries no
constraint, has no row there. The verdict's population would move. Declined.

**Bounding the detection pass with a `ReadBudget`.** The dev session hung for 31 minutes with no
name on the console, and a budgeted read would have failed naming the relation. But the detection
pass runs on the capture's own writer-side context, which `ReadBudget`'s javadoc keeps unbounded on
purpose, and a budget that fires on the pathological case is a wall-clock threshold that also fires
on a slow machine at consumer scale, which the fact-model page's refresh-observer paragraph declines
for the same reason. A separate item if wanted; not this one's lever.

**Folding this into R876, which owns the expensive-derived-read narrative.** A real candidate, since
this is exactly its subject and it is In Progress at priority 1. Rejected on that item's own terms:
it files the threads it opens as separate items rather than carrying them, and it explicitly
disclaims consumer-scale wall-clock work as belonging elsewhere. This plan takes its lever order as
given; the `d` outcome above is that item's supertype finding arriving at one more relation, and is
reported to it if taken.

## Provenance

Filed after two sessions reached different answers, and the disagreement is the useful part. A
session investigating the same slowdown measured the diagnostic read across R677's landing commit and
its parent, got a clean 15x regression, and concluded R677. The instrument was sound and could not
see the cause: `intent_node_id_decode_landing_defect` has no SQL reader, so it never reaches the
diagnostic surface, and no measurement taken there could have seen it whatever it cost. R677 and this
verdict also added a detector to the same unconditional switch in `FactCapture.detect` within a day
of each other, which is most of why R677 fit every circumstantial test.

Two things follow, and they are why this section exists rather than the story living in a commit
message. Anyone re-deriving this from the read surface lands where that session landed, so the guard
under Tests is aimed at the blind spot and not at the relation. And the 15x on the diagnostic read is
real and still unfiled: it is roughly three orders of magnitude below this one, on the language-server
publish cadence rather than the dev round, and it wants its own item rather than a paragraph here.
The verdict's own item, R926, is Done and a Done item has no file to reopen, so a defect in shipped
work gets this fresh item, as R925 and R927 did before it.
