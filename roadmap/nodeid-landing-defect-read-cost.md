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

`FactCapture.detect` issues eight reads per pass. Six of them, on that population:

| read | time |
|---|---|
| `intent_node_id_decode_defect` | 747 ms |
| `intent_authored_claim_conflict` | 262 ms |
| `intent_argmapping_projection_defect` | 108 ms |
| `intent_resolved_node_key_projection` | 69 ms |
| `intent_field_unlowerable_ordering` | 58 ms |
| `intent_reference_for_application` | 3 ms |

The seventh, `intent_node_id_decode_landing_defect`, did not return in 24 minutes. The eighth
arrived after these figures were taken, the polymorphic decode verdict joining the same pass, so
`intent_node_id_polymorphic_decode_defect` is unpriced on this population. Nothing in the
localisation below turns on it, the separation being four orders of magnitude, and it is one more
relation the guard under Tests carries a pinned row for.

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
`intent_input_field_reference_step_target`, each a recursive walk over its own hop relation, of
which `intent_field_reference_step_hop` is a registered target and
`intent_argument_reference_step_hop` an unregistered view, and an inline derived table computing
`COUNT(*) OVER (PARTITION BY ...)` across the whole of `sql_referential_constraint`; it then closes
with `MAX(position) OVER (PARTITION BY ...)` over its own rows. A naming on the inner side of a
join is evaluated once per driving row, which the `intent_node_id_decode_column_live`
registration's `reason` records as the shape no spelling escapes, and a window sees its whole
partition whatever the outer predicate says, so none of the four terms can be pruned by a reader's
join condition. Which of them carries the 41.5 s is not known: 377 rows under a window is not
seconds by itself, and a recursive walk or a catalog-wide window re-evaluated 377 times could be.
The plan's first step is that bisection, because the register records what happens when a rule is
priced with a re-evaluation still inside it: the `intent_mutation_payload_key_membership_live` row
says a rule with a re-evaluation inside it should be rewritten before it is priced, and names the
refresh figure that rule would have cost otherwise.

**Why this is a regression and not a pre-existing cost.** `intent_node_id_decode_hop` has been
expensive since well before the verdict landed. What changed is that nothing used to evaluate it at
read cadence. Its only other reader in the DDL is `intent_node_id_decode_hop_column_live`, the
source view of a registered target, so the hop relation was paid once per refresh, and
`intent_node_id_decode_column_live` sits above that target rather than above the hop itself.
`intent_node_id_decode_landing_defect` is the first reader to expand it live, it expands it more
than once, and it carries no registration. Confirmed from the DDL independently of the timings: the
landing-defect view has zero `FROM`/`JOIN` references anywhere, so `NodeIdLandingDefects.detect`
is its only reader in the tree, and no Java reader in main names the hop relation.
`NodeIdDecodeReachTest` reads it in the model test tier, which is a pin the registration outcome
keeps rather than a cadence a dev round pays.

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

**Precondition for pickup.** Steps 1 and 3 run against the `sis` store copy and the `sis`
workspace, neither of which is in this repository, so the session that takes this item to
In Progress is a local one on the machine that holds that consumer clone. A sandbox session cannot
take a single figure the plan turns on, and should not start the item.

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
  `intent_node_id_decode_hop`.X" form. That note stays in the `COMMENT ON` rather than moving
  anywhere less visible, because it is the warning at the point of misuse: the fact model requires
  a derived view to carry it there, the cost being invisible at the call site, and a reader who
  names the rule instead of the rows is committing this item's own defect one more time.
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
- No declaration for the `_live` view, and one gate edit that makes that true for every registration
  rather than for this one. `MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks` computes
  `undeclared` as the observed relations minus the declared ones, and all twenty `_live` views in
  the tree stand on the frozen roster in `undeclared-relations.txt` to satisfy it. The roster only
  shrinks and `meta_relation_family` is a census over `INFORMATION_SCHEMA.TABLES` with no
  declaration escape (its `exempted` column places a relation in a family rather than excusing it),
  so a twenty-first registration has nowhere to put its source view. Subtract the register instead:
  `undeclared` becomes observed minus declared minus `SELECT source_view_name FROM meta_materialize`,
  and the twenty `_live` lines leave the roster in the same commit, since the gate compares by
  equality. Net roster diff twenty lines out and none in, which is the only direction its own javadoc
  allows.

  This is the register stating a fact the roster was hand-listing, not a new exemption. The fact
  model's ownership rule already reads a materialized target *as* its rule, expanding through the
  register "so that a registration cannot hide a crossing underneath it", and
  `FactCaptureAgreementTest` already calls a materialization "two relations under one rule". So the
  relation of a registered pair is the target, which carries the canonical name every reader spells
  and the rule's own comment, and the `_live` view is the machinery that fills it. Declaring the
  machinery would put a second `grain_text` and `example` on rows that are the target's rows by
  construction, which the two-way `EXCEPT` proof is what establishes, and two spellings of one
  resolution agree exactly until one of them changes. It would also evict the `_live` note from the
  one surface a misuser meets, the echo gate joining `grain_text` and `example` and nothing else.

  The ratchet survives and tightens. The exemption is derived from the register rather than authored,
  so nobody can pad it, and there is no way to dodge a declaration through it: a registration needs a
  target, and a target under a name the roster does not already carry is an observed relation on no
  frozen roster. Registration twenty-two onward then owes no declaration work at all, which is what
  makes this an edit to the gate rather than a convention every future author restates.

  The canonical table keeps its roster line, which still matches once it is a table, and stays
  undeclared. Declaring it is a separate question with a real constraint behind it: a declared
  relation's visible comment is capped at 601 characters by the two `CHECK`s the echo gate joins,
  and this rule's comment is 3371. Where a multi-paragraph rule argument lives once its relation is
  declared is owed by whichever item drains the roster, not by this one.
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
- `MetaDeclarationGateTest`: `theUndeclaredRosterOnlyShrinks` gains the register subtraction and the
  roster loses its twenty `_live` lines, per the declaration bullet above. The seeded detection case
  beside it gains an arm holding the subtraction in both directions, that a registered source view
  is exempt and that a target under an unrostered name is still an offender, so the exemption cannot
  silently widen. No other case in that class moves: nothing new is declared, so the echo, grain,
  corpus and owner-read cases keep the population they have.
- `FactSchemaGateTest`: the column-comment gate, every table and every column carrying a
  `COMMENT ON`, over the new `_live` view and the table. The comment-echo gate is
  `MetaDeclarationGateTest`'s and binds declared relations only, which is why twenty registrations
  have never met it and why this one does not either.

`Tables.INTENT_NODE_ID_DECODE_HOP` is regenerated from the DDL as a table; no Java reader in main
names it, so nothing recompiles differently.

For the promoted-relation outcome the same gates apply to the new relation (a `meta_relation` row, a
grain, comments), and the hop's own text changes to join it, which is the one outcome where the
verdict's rows need the before-and-after population check under Tests rather than the agreement test
alone.

No docs change beyond the DDL comments. The fact-model page counts registrations as "twenty" and
"twenty-two" in measured narratives about specific passes, which are history rather than a live
roster, and stay. One of those counts is not a narrative and is wrong today: the refresh-observer
paragraph says "twenty-two registrations refreshed on every save" of a register holding twenty, so it
reads as a live claim and drifts with every registration. It is wrong before this item and wrong by
one more after it, and repairing it here would leave the next registration to repair it again, so it
belongs with whoever gives that sentence a count it cannot outlive.

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

**The roots are a set per component, not one relation per component.** The reads come from the
detection components rather than from a list in the test, and a component reads more than one
relation. Each component exposes every relation its own statements name as one public set its
statements take their table references from, and `StoreDetections` exposes the roster of those sets
beside its components, so a component swapped or repointed edits its set where the component sits and
the gate reads the roster. The set is stated over relations rather than over views, and the walk
stops at tables, so a component's reads of `intent_type_domain`, `intent_input_occurrence_path` and
the base families contribute nothing and need no curating: what survives the walk is exactly the
view bodies that component evaluates per pass. The roster is over components and not over a method
name, the eighth being `ResolvedKeyProjections.read` rather than a `detect`.

What that domain is today, which the gate computes and this list only starts the implementer from.
`AuthoredClaimConflicts` reads three views, `intent_authored_claim_conflict` at both grains,
`intent_authored_type_claim` in `typeClaims`, and `intent_authored_field_claim` in `claimsAt`, that
last once per violated field coordinate. `UnlowerableOrderings` reads three,
`intent_field_unlowerable_ordering` and `intent_field_navigated_type` in `read` and
`intent_field_participant_scope_table` in `participantsOf`. `ReferenceForParticipantDefects` reads
`intent_reference_for_application` and, per coordinate, `intent_field_participant_scope_table`.
`NodeIdPolymorphicDecodeDefects` reads `intent_node_id_polymorphic_decode_defect` and, once per
container in `memberNames`, `intent_node_container_member` and `intent_poly_member`.
`ArgmappingProjectionDefects` reads `intent_argmapping_projection_defect` and
`intent_resolved_node_key_projection`. `NodeIdDecodeDefects`, `NodeIdLandingDefects` and
`ResolvedKeyProjections` read one view each. So five of the eight components read a view this item's
own shape reaches, a body evaluated once per driving row, with the per-row loop in Java rather than
in a correlated subquery and no SQL text to notice it in; a roster of one relation per component
would have left those outside the pin while reporting a clean equality, which is the recurrence this
gate exists to refuse.

One residue, disclosed in the gate's javadoc rather than left to be found, on the precedent
`CollectionValuedColumnGateTest` sets for a gate that states its own gap. The set is authored, so a
component that names a relation without putting it in its set is a read the gate cannot see. Having
the statements take their references from the set is what makes that visible in review rather than
invisible, the bypass being a static import beside a declared set that does not carry it. Closing it
mechanically is a lexical scan of the eight components for a relation constant outside their sets,
which is cheap and idiomatic here, and it is deliberately not in this item: the gate's value is the
delta it forces an author to write down, and a scan that has never caught anything is scope this
item did not measure.

Scope is the detection pass, not every consumer read: the diagnostic surface the language server
reads has the scan-count ceilings in `graphitron-lsp`.

One thing the register subtraction above does not fix, named here so it is not mistaken for
something this item closed. `MetaDeclarationGateTest`'s owner-read case filters
`meta_relation_family.relation_type` to `VIEW`, so a declared relation that is a registered target
is a table and its body is never walked, while the `_live` view holding that body is exempt. The
ownership rule the fact model states wants a declared relation resolved through the register before
it is walked, which is a widening of that gate rather than a roster question, and it is R941. This
item takes the exemption as data and leaves the gate's reach where it is.

A wall-clock gate is explicitly *not* in scope. Nothing in this repository captures a consumer schema
of the size that exposes this, and a fixture that did would be a build wall-clock gate, which the
build-wall-clock item owns.

## Other solutions we've considered

**Registering `intent_node_id_decode_hop` first and bisecting later.** The registration is the
plan's default outcome, and taking it before the bisection was the draft's shape. Declined as an
order rather than as a lever: the register's own rows say a rule priced with a re-evaluation inside
it is priced wrong, and the DDL rename, the reason prose, the roster move and four gate edits would
all be built on an assumption one afternoon of slices settles.

**Declaring the new `_live` view instead of subtracting the register.** The other way to answer the
closed roster, and the one that touches no gate: give the source view a `meta_relation` row, a minted
grain and `derivation` as its owner, and put the standard `_live` note in `rationale` where the echo
gate does not reach. Declined on three counts, each from something the tree already states. The
grain sentence and example would describe rows that are the target's rows by construction, which is
two spellings of one resolution. The `_live` note would leave the `COMMENT ON`, which is the surface
the fact model requires the warning to sit on because the cost is invisible at the call site, and
losing it in this item of all items is the defect being re-committed. And it would declare the
machinery while leaving the canonical name every reader spells undeclared, which inverts the
ownership rule's own direction. It also buys the owner-read gate one pair's worth of reach, for the
reason under the guard below, which is a general fix filed separately rather than a side effect worth
paying for here, and it is R941.

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

## Reviewer findings

### Round 1 (2026-09-09, Spec -> Ready, reviewer session 836fbf3b-85c9-4fbb-ba99-5b2db94cae8d)

Verdict: withhold. One blocking finding on question two, plus three claims about code that are
wrong as written and that the author should fold into the same revision.

Question one passes, and without reconstruction from the phase list: today a `graphitron:dev`
session on the `sis` schema never becomes usable, because one of the reads the detection pass makes
on every pass, at boot and again after every save, re-expands a 23-second relation once per driving
row; after this lands that read costs seconds, the loop is usable at that schema size, and the
consumer's own validation gate can finally run over the fixes waiting on their migration branch,
with no change to which schemas are refused. The outcome is reachable here, and the structural
claims the measurement rests on hold: `intent_node_id_decode_landing_defect` has exactly zero
`FROM`/`JOIN` references in the DDL, `stopped` is a non-recursive `WITH` whose only reference from
arm 2 is inside a correlated `NOT EXISTS`, the hop names the endpoint once and carries the three
`LEFT JOIN`s and both windows the plan describes across twenty-one columns, `use_site`'s own comment
states the six-column key the plan proposes as the target's primary key, the register holds twenty
registrations of which five targets are keyed, `DerivedReadCostTest` prices the landing-defect cell
at 224 ms against 709 with one `KNOWN_NON_MONOTONIC` row spelling the hop, and the rung-1 seam is
real: the argument reference-target walk joins `intent_argument_scope_table`, a registered target,
and `FactCapture.capture` runs every hand-written producer before `Materializations.refresh`. The
guard is the right shape and cheaper than the plan claims: `registrationsReachedFrom` already
maintains the `walked` set the sibling would return, so exposing it is a return value rather than a
second walk.

**Finding 1 (question two, architecture fit). Every registration-shaped outcome makes its `_live`
view the first declared `_live` view in the tree, and the plan's comment instruction cannot satisfy
the gates that then bind it.**

The plan is right that the new view cannot go on the frozen roster and must carry a `meta_relation`
row. What it does not say is what that costs, because all twenty existing `_live` views are on that
roster and none of them is declared. `intent_node_id_decode_hop_live` would be the first, and three
gates bind a declared relation that have never bound a `_live` view:
`MetaDeclarationGateTest.theCommentEchoesTheDeclaration` requires the relation's `COMMENT ON` to
equal `grain_text` and `example` joined, verbatim, with `grain_text` the comment's first sentence by
`GrainSentence`'s rule; `meta_relation`'s own `CHECK`s cap each of those at 300 characters; and
`meta_relation.grain_text`'s column comment defines the sentence as the one saying what one row is.

The plan's diff instructs the opposite. It says the `_live` view's comment "becomes the standard
`_live` note (the `intent_node_id_decode_hop_column_live` comment beside it is the form)". That
exemplar is 457 characters, and it is the form it is precisely because it is exempt: its relation is
undeclared. Split at its first sentence it yields a `grain_text` of "This states the rule and is
evaluated on demand.", which is not a statement about a row, and an `example` of about 400
characters, which fails the `CHECK` outright. So an implementer who follows both bullets goes red,
and the fix is not local: it means authoring what a declared `_live` view's grain sentence and
example say when its rows are by construction identical to its target's, and where the `_live` note
then lives, which is a convention binding all twenty-one registrations rather than a line of DDL.
That is the design fork this gate exists to settle before an implementer meets it at a red build.

Two smaller consequences of the same discovery. The plan's "Gates a new registration moves" list
does not include `MetaDeclarationGateTest` at all, though the item is the first registration that
moves it. And the `FactSchemaGateTest` bullet attributes a "comment-echo" gate to that class; the
column-comment half is there ("every table and every column carries a `COMMENT ON`"), but the echo
gate is `MetaDeclarationGateTest`'s and binds declared relations only, which is exactly why no
registration has met it before.

The finding is scoped to registration-shaped outcomes, which is the plan's default and two of its
three named outcomes. It does not touch the `d`-promotion outcome, where a new ordinary derivation
relation is declared and the `intent_node_id_decode_landing_defect` row really is the exemplar.

What would satisfy question two: say what the new `_live` view's declaration holds, or say that the
registration outcome declares only the canonical table and state how the `_live` view then stays off
the observed-relation gate, or make the convention question an explicit first step of the
registration outcome with the decision recorded where the next registration will read it. Any of the
three is an answer; what the plan cannot do is assert a `meta_relation` row and the standard `_live`
note in the same breath.

*Author response, 2026-09-09.* Answered, but not by any of the three ways out as posed: the finding
is right that the plan cannot assert a `meta_relation` row and the standard `_live` note together,
and wrong to assume the row is the half that has to give. A `principles-architect` consult on the
proposed declaration turned it around against three things the tree already states. The ownership
rule reads a materialized target *as* its rule, expanding through the register "so that a
registration cannot hide a crossing underneath it", and `FactCaptureAgreementTest` calls a
materialization "two relations under one rule", so the relation of a registered pair is the target
and the `_live` view is machinery. Declaring the machinery would give one grain a second
`grain_text` and `example` over rows the two-way `EXCEPT` proves identical, and would evict the
`_live` note from the `COMMENT ON`, which the fact model requires precisely because the cost is
invisible at the call site, in the item whose whole finding is an invisible read cost.

So the declaration bullet now subtracts `meta_materialize.source_view_name` from the gate's
`undeclared` domain and deletes the twenty `_live` lines from the frozen roster, a net twenty out
and none in. Nothing is declared, the `_live` note stays where a misuser meets it, and registration
twenty-two onward owes no declaration work, which is what makes it a gate edit rather than a
convention. The second way out was checked and is unavailable in its own terms:
`meta_relation_family` is a census over `INFORMATION_SCHEMA.TABLES` and its `exempted` column places
a relation in a family rather than excusing it from declaration. The declaration shape is recorded
under "Other solutions we've considered" with why it lost, the comment-ceiling constraint behind
leaving the table undeclared is now stated as owed elsewhere, and the owner-read blind spot the
consult surfaced is filed as R941 and named in the guard rather than buried.

The scope judgment is the author's and the reviewer should weigh it: this puts a one-query edit to
`MetaDeclarationGateTest` inside a performance item. It is here because no registration can land
without it and because the edit replaces a hand-listed exemption with the register that already
states it, not because the item wanted the territory. The alternative, blocking this item on a
separate roster item, is a consumer waiting on a dev loop that does not start.

**Finding 2 (question one, a claim about code). The detection pass issues eight reads, not seven.**

R933 landed on trunk after this spec's last commit and added `NodeIdPolymorphicDecodeDefects.detect`
to the same switch, so `FactCapture.detect` now issues eight reads and
`intent_node_id_polymorphic_decode_defect` carries no figure in the table. Left to the author rather
than corrected here, because the count is load-bearing in three sentences of the measurement
section ("issues seven reads", "Six of them", "The seventh") and rewriting those is authoring, not
a number fix. Nothing about the finding changes: the six figures and the localisation stand, and the
new read is one more relation the guard's pin will carry a row for, which reads as a point in the
guard's favour.

*Author response, 2026-09-09.* Corrected to eight, with the polymorphic read named as unpriced on
this population and the localisation's four orders of magnitude stated as why nothing below turns on
it. It is also one more relation the guard's pin will carry, which the Tests section already gets
for free from taking its roots off the roster rather than a list.

**Finding 3 (question one, a claim about code). A Java reader does name the hop relation.**

"no Java reader names the hop relation at all" is false as written:
`NodeIdDecodeReachTest` imports and reads `Tables.INTENT_NODE_ID_DECODE_HOP` in the model tier. The
spec's own later sentence carries the right qualifier ("no Java reader *in main* names it"), and the
`store-performance` skill's step 7 asks specifically for Java readers to be counted, so the
qualified form is the one to keep. This does not disturb the regression argument, which is about
read cadence in a generator pass, and the test reader is a second pin the registration outcome gets
for free.

*Author response, 2026-09-09.* Corrected. The sentence now carries the `in main` qualifier and
names `NodeIdDecodeReachTest` as a model-tier pin the registration outcome keeps, rather than a
cadence a dev round pays.

**Finding 4 (question one, minor, non-blocking). Only one of the two reference-target views recurses
over a registered relation.**

"the two reference-target views ... each a recursive walk over a registered hop relation" holds for
`intent_input_field_reference_step_target`, which recurses over `intent_field_reference_step_hop`, a
registered target. `intent_argument_reference_step_target` recurses over
`intent_argument_reference_step_hop`, which is an unregistered view. Step 1 prices both standalone
either way, so nothing in the plan changes; if anything it raises the prior on `tg` being the term.


*Author response, 2026-09-09.* Corrected. The body now says which of the two hop relations is a
registered target and which is a view, which raises rather than lowers the prior on `tg` that
step 1 is testing.
### Round 2 (2026-09-09, Spec -> Ready, reviewer session 85825d23-2352-4bd7-b1bc-65822ef02f4f)

Verdict: withhold. One blocking finding on question two, and it is about the guard rather than the
lever. Round 1's four findings are answered, and the first one is answered by a better shape than
any of the three ways out it posed: the register subtraction lands as the revision describes, which
I checked against the gate rather than taking on the argument.

Question one passes. Stated without the phase list: a `graphitron:dev` session on a consumer schema
the size of `sis` never becomes usable, because one of the eight reads `FactCapture.detect` makes on
every pass, at boot and again after every save, re-expands a 23-second relation once per driving row
inside a correlated `NOT EXISTS`; when this lands that read costs seconds, the loop works at that
schema size, the consumer's own validation gate can run over the fixes waiting on their migration
branch, and no schema's verdict or message changes. Reachable here, and the structural claims hold
where I checked them, including every claim round 1's revision added.

Question two passes on the levers. The registration outcome, the promotion of the `d` derived table
to a catalog-family relation, and the marker-relation outcome each extend a shape the register and
the fact model already carry, the lever order is the page's own, and the declaration bullet is
sound: `theUndeclaredRosterOnlyShrinks` computes `undeclared` exactly as the plan says, compares by
equality, and the roster's twenty `_live` lines are its only consumer in the tree, so the
subtraction is twenty out and none in with nothing else to keep in step.

**Finding 1 (question two, architecture fit). The guard's domain and its roots are two different
sets. Five of the eight detection components read more than one relation, several of them views read
once per driving row, so the pin as specified misses the shape this item is a defect report about.**

The Tests section states the guard's domain as "for each relation the detection pass reads", then
states where the roots come from: "Each family class exposes the relation its `detect` reads as one
public constant used by the query itself, and `StoreDetections` exposes the roster of those
constants". One constant per component is not the set the first sentence names. What the components
read today:

- `AuthoredClaimConflicts.detect` reads three views. It drives `typeGrain` and `fieldGrain` from
  `intent_authored_claim_conflict`, joins `intent_authored_type_claim` in `typeClaims`, and calls
  `claimsAt` over `intent_authored_field_claim` once per violated field coordinate.
- `UnlowerableOrderings.detect` reads three. `read` drives from `intent_field_unlowerable_ordering`
  and joins `intent_field_navigated_type`; `participantsOf` reads
  `intent_field_participant_scope_table`.
- `ReferenceForParticipantDefects.detect` reads `intent_reference_for_application`,
  `intent_input_occurrence_path` and, in `participantsOf`, `intent_field_participant_scope_table`.
- `NodeIdPolymorphicDecodeDefects.detect` reads its verdict view, then `memberNames` reads
  `intent_node_container_member` and `intent_poly_member` once per container.
- `ArgmappingProjectionDefects.detect` reads `intent_argmapping_projection_defect` and
  `intent_resolved_node_key_projection`.

Every relation named above except `intent_input_occurrence_path` is a view, so the walk does not
stop at it. Only `NodeIdDecodeDefects`, `NodeIdLandingDefects` and `ResolvedKeyProjections` read one
`intent_` relation each, `intent_type_domain` beside them being a table the walk stops at anyway.

So a per-component single constant pins roughly half of the pass's read-cadence reach, and what it
leaves out is this item's own shape: a view body evaluated once per driving row. `claimsAt`,
`memberNames` and `consumersOf` are all per-row reads of views, from Java rather than from a
correlated subquery, which is the same cost with no SQL text to notice it in. A future reader that
adds one of those is outside the pin, which is exactly the recurrence the guard exists to make
impossible, and the guard would report a clean equality while it happened.

Two precisions from the same reading, both of which bear on what the roster has to be. Nothing in
the tree exposes such a constant today: the components static-import `Tables.INTENT_*` at their use
sites and `StoreDetections` carries components only, so this sentence is authoring work and not a
description of a seam already there. And the eighth component is `ResolvedKeyProjections.read`, not
a `detect`, so a roster keyed on "the relation its `detect` reads" has one member with no `detect`
to read.

What would satisfy question two: state the roots as the relations each component reads rather than
one per component, which is what makes the equality pin mean what the section claims for it; or
scope the pin explicitly to each component's driving relation, and say why the secondary per-row
reads are outside the guard's reach and what covers them instead. Either is an answer, and the first
looks cheap, the walk already taking a set of starts. What the section cannot do is state its domain
as every relation the pass reads and its roots as one constant per family in the same paragraph, and
leave the implementer to notice the gap.

Nothing else in the plan moves under this finding. The measurement, the localisation, the lever
hierarchy, the three outcomes and the declaration bullet stand as written, and the finding widens
the blind spot the guard is aimed at rather than questioning that it is there.

**Non-blocking, and neither is a revision this gate asks for.**

The hop's view comment measures 3371 characters as a string, 3387 as the escaped DDL literal, not
3436. Left to the author because the counting convention is the author's, and nothing turns on it:
the ceiling it is weighed against is 601 either way.

`fact-model.adoc`'s refresh-observer paragraph says "twenty-two registrations refreshed on every
save", which reads as a live claim about the register rather than one of the measured narratives the
plan's docs bullet exempts, and the register holds twenty. Pre-existing, not this item's, and noted
only because that bullet reasons about these counts by name.

*Author response, 2026-09-09.* Taken as the first of the two ways out, the roots being a set per
component. The guard's roots paragraph is rewritten: each component exposes every relation its own
statements name as one public set the statements take their references from, `StoreDetections`
carries the roster of those sets, and the set is stated over relations rather than over views so the
walk's stop at tables does the filtering instead of an author curating which reads matter. The
body now also states the domain as it stands today, five of the eight components reading a view once
per driving row from Java, which is this item's own shape with no SQL text to notice it in, so the
list the implementer starts from is checked rather than re-derived. The roster is over components
rather than over `detect`, which answers the eighth-component precision.

The finding's other precision, that no such constant exists in the tree today, is right and the
paragraph no longer reads as though one does; the sets are authoring work and the plan says so. That
leaves one residue the finding implies and does not name: an authored set can omit a read. It is
disclosed in the gate's javadoc on the precedent `CollectionValuedColumnGateTest` sets, with the
mechanical closure named as a lexical scan and deliberately left out of this item, a scan that has
caught nothing yet being scope this item has not measured.

*Author response to the non-blocking notes, 2026-09-09.* The character count is corrected to 3371,
the string measurement, since the ceiling it is weighed against is a `CHAR_LENGTH` on the same
string. The docs bullet now says the refresh-observer count is a live claim and wrong today rather
than history that stays, and why repairing it here would only leave the next registration to repair
it again.
