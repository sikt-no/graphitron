---
id: R795
title: "No language-server surface blocks the editor"
status: Spec
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# No language-server surface blocks the editor

Jump-to-definition in the language server hangs. Putting the cursor on certain SDL
declaration names and asking the editor to navigate stalls for over a second, and
trying the feature in a few places in a row hits enough of those to read as a
broken feature. The language server is the most latency-sensitive frontend the
project has: it is the only one where a slow answer arrives into a blocked cursor,
so a slow answer and no answer are the same outcome to the person waiting.

The item is scoped to every surface rather than to the one that was reported,
because one surface's budget is the wrong unit. Six surfaces read the fact store
and they share a single three-second budget that was not chosen for any of them
in particular. Measuring all six is also what found that the reported surface is
not the worst one.

## What was measured

Against the sakila example's 4222-line `schema.graphqls`, captured through
`StoreFixture.ofCatalog`, driven through the real
`GraphitronTextDocumentService` request methods at seventeen cursor positions
spread down the file, under the production budgets (`INTERACTIVE_READ_BUDGET`
3 s, `SESSION_READ_BUDGET` 30 s):

[cols="2,1,1,3",options="header"]
|===
| Surface | Median | Max | Reading

| `hover`
| 20 ms
| 96 ms
| Comfortable.

| `definition`
| 15 ms
| 1397 ms
| Two of seventeen positions over 1.2 s. The reported hang.

| `completion`
| 12 ms
| 24 ms
| Comfortable.

| `codeAction`
| 4 ms
| 15 ms
| Comfortable.

| `inlayHint`, whole file
| 10 ms
| 10 ms
| Suspiciously cheap; see the caveat below.

| the diagnostics drain, via `didOpen`
| 31310 ms
| 31310 ms
| Overran its 30 s budget and published nothing.
|===

Two caveats on those figures, both of which make them floors rather than
estimates. The fixture's class census is empty, so every arm that resolves a
consumer class or method name does less work than in a real session. And the
inlay figure is too cheap to trust: inlay hints are configuration-gated through
`applyPulledInlayHintConfig`, the probe pulled no configuration, and a surface
that returned nothing is not a surface that was measured. Confirming that number
against an enabled configuration is the first task below.

The drain figure is **not this item's** to fix. R793 already owns the drain
overrun, filed from a real dev session, and states that what it is missing is
knowing which relation in that statement is expensive. The measurement above is
a reproduction of exactly that, in a harness, and belongs there rather than
here; see "What this hands to R793".

A guess this measurement refuted, recorded so it is not made again: three
surfaces read relations in `report-inline-multiplicity`'s fifteen heaviest
(`InlayFacts` reads `intent_resolved_field_claim` and
`intent_field_reference_discovery`, `DiagnosticFacts` reads
`intent_field_column_table`, `ClaimFacts` behind hover reads the first two), and
`DeclarationFacts` behind the slow surface reads none of them. Reading a heavy
relation predicted nothing about a surface's cost. The report ranks a relation's
own breadth, and what costs here is how a reader joins to one.

## The expensive read

Every jump whose cursor sits on a declaration name is answered by
`DeclarationDefinitions`, which asks the store one statement built by
`DeclarationFacts`. That statement has eleven arms. Timed individually, ten
answer in 1 to 22 ms and the `redirects` arm takes about 1.1 s of the 1.3 s
total.

That arm asks which catalog table jOOQ binds a candidate backing class to. It
joins the catalog census relation `sql_table` to the derived relation
`intent_type_backing` on the class name. H2 drives that join from the census
side, so the derived relation is evaluated once per catalog table rather than
once, and the filter that makes it cheap (this graph, this type name) is applied
after the expansion instead of before it. The arm returned no rows in every case
timed: the second is spent establishing that there is nothing to say.

### What the controls say

Recorded because three of them refuted a candidate fix:

[cols="3,1,3",options="header"]
|===
| Control | Cost | What it says

| The derived relation alone, same two predicates, no join
| 22 ms
| The relation is cheap once filtered. Its own cost is not the problem.

| The census relation alone, same scope
| 8 ms
| Neither is the other side.

| The two joined the way the arm joins them
| about 1.2 s
| The join is the whole cost.

| The join replaced by the resolved class names as literals
| 0 ms
| The cost is the join shape, not the rows it returns.

| The derived side wrapped in a derived table carrying its own predicates
| about 1.1 s
| *Refuted.* H2 inlines it and the plan does not change.

| The derived side as an `IN` subquery, census still the driver
| about 1.2 s
| *Refuted.* Same per-driving-row evaluation.

| Driving from the filtered derived relation, census reached by `EXISTS`
| 20 ms
| The shape that works, and the only one that does.
|===

So the lever is the join's driving side, not a `meta_materialize` registration
and not a rewrite of the view. What one row of this answer means is one
(backing class, bound table) pair, and the relation that owns that key is the
backing relation, not the catalog census; the arm departs from the wrong end.
The arm needs the census's columns and not merely its existence, so the working
shape has to project them while still driving from the filtered backing side.

`DeclarationHovers` reads the same arms, so this carries the declaration-name
hover with it. That is also why hover's 96 ms maximum should not be read as
hover being safe: the probe's positions happened to miss the coordinates that
reach this arm.

## What changes

### 1. Confirm the inlay figure

Pull an enabled inlay configuration in the harness and re-measure the whole-file
request. Until that number exists, the surface is unmeasured rather than fast,
and the budget below cannot be set for it. This is first because it can change
what the rest of the item has to cover.

### 2. The redirect arm departs from the backing side

Rewrite the arm so the filtered derived relation drives and the census is
reached per surviving class name. The measured `EXISTS` control is the shape;
the arm needs projection rather than existence, so the candidate is a correlated
lookup on the census keyed by the class name, in the grain the `nested-jooq`
skill describes: one row of the answer is one pair, driven from the relation
that owns the key.

The constraint is that the answer must not change. `DeclarationFacts` is one
statement by design, and both readers of these arms
(`DeclarationDefinitions` and `DeclarationHovers`) must keep the rows they have
today, including the ordering the arm states.

### 3. A budget per grain, not one for every keystroke

`DevMojo` holds three budgets today: `INTERACTIVE_READ_BUDGET` at 3 s covering
every request surface, `SESSION_READ_BUDGET` at 30 s for the drain and session
state, and `MCP_READ_BUDGET` at 60 s. The first is the one that is wrong, and it
is wrong by being one figure for six surfaces with different contracts. A
navigation request blocks a cursor; a hover popup arriving late is a popup
arriving late.

The measured medians are 4 to 20 ms and the comfortable maxima 15 to 96 ms, so
there is better than an order of magnitude of headroom under a budget in the low
hundreds of milliseconds. Setting it there turns a pathological read from a
three-second stall into an instant miss, which is the outcome a blocked cursor
wants.

Open for the reviewer: whether this is a new reader per grain or a tighter bound
on the existing interactive one. `StoreReader`'s javadoc argues a reader per
grain costs no statements and stops two grains serializing behind each other,
which is the argument the existing two readers were split on, and points the
same way here. Against it: three readers is three connections for a session that
had two, and the grains may not contend in practice.

### 4. Every surface is enforced, not spot-checked

This is the part that makes the item hold. Four surfaces have statement-count
enforcers (`InlayHintStatementCountTest`, `DeclarationHoverStatementCountTest`,
`DeclarationDefinitionStatementCountTest`, `DiagnosticsStatementCountTest`) and
R782 covers the two that do not. None of them can catch this defect:
`DeclarationDefinitionStatementCountTest` pins definition at *one* statement per
request, and that one statement is the bug. A count says nothing about what the
statement expands to.

`InlineMultiplicityCheck` cannot catch it either. It is computed from the DDL
alone, so it ranks relations rather than readers, `intent_type_backing` is
nowhere near its top fifteen, and the join that costs the second is in Java.

The project's tiers deliberately refuse to fail for slowness:
`DeclarationDefinitionStatementCountTest` says "no timing, no fixture scale,
nothing that could fail for being slow", and `ReadBudget`'s own javadoc calls a
wall-clock threshold in a test "a wall-clock threshold smuggled into a test tier
that refuses to fail for slowness". So a benchmark tier is the wrong answer and
the enforcer has to say the thing structurally.

Two candidates. R793 reached this same question from the drain's side and its
acceptance criterion already answers it: "the tiers assert statement counts and
plan shape, never wall clock, so what is pinned in-tree is the scan-count shape
via the existing statement-count tier". That settles the currency, and this item
should follow it rather than introduce a second one for the same property.

**A scan-count ceiling (recommended, and consistent with R793).** Assert a
ceiling on the `scanCount` H2 reports for each surface's statement against a
fixed fixture. Deterministic and clock-free, and it is the metric that actually
separated the two shapes here: about 3782 for the slow join against roughly 60
for the rewrite. The cost is an `EXPLAIN ANALYZE` per surface and a per-surface
number somebody has to defend, which is the objection
`InlineMultiplicityCheck` records against its own ceiling and the reason that
check reports rather than gates. The counter here is that a ceiling over one
surface's own statement is a far narrower claim than a ceiling over every
relation in the schema, so the number is defensible in a way that one is not.

**A budget-arm enforcer.** Drive every surface at a spread of coordinates over a
realistically captured schema with the reader's budget set to the production
navigation figure, and assert every answer is `StoreAnswer.Answered` rather than
`StoreAnswer.OutOfBudget`, which is `StoreOutOfBudgetTest`'s own trick run the
other way round. It encodes the wanted property most directly and imports the
budget as a production setting rather than inventing a threshold. Set against it:
a slow enough machine flips it, which is the wall clock the tiers refuse, and
adopting it here would leave the project with two enforcer currencies for one
question.

Either way the enforcer covers all six surfaces including the two R782 adds
counts for, so the two items should land in an order where the later one does
not re-enumerate the set.

## What this hands to R793

R793's plan step 2 prices each of the drain's relations against a capture of the
sakila example's schema, which is the fixture this item's probe already stood up.
What that probe adds is the reproduction R793 does not yet carry: `didOpen` over
that schema spends 31310 ms against the 30 s session budget and publishes
nothing, in a harness rather than in an observed dev session. So the overrun is
reproducible before step 2 begins, and the per-arm isolation that localised the
definition read here is the same move R793's step 2 describes.

Two boundaries, so neither item waits on the other. R795 does not touch the
drain's statement. R793 does not restate the budget-grain question, and its own
"not in scope" already refuses raising a budget, which is consistent with the
split. The asynchronous-drain item beside it removes the drain from the
triggering thread, which would stop `didOpen` blocking whatever this item
settles about budgets; it is worth doing either way and is not a substitute for
knowing why the statement costs what it does.

## Retired vocabulary

None. Nothing here retires a symbol; the budget constants keep their names and
gain a sibling.

## Reviewer findings

### Round 1, Spec → Ready, revisions requested (session_01MtzM82PqeYAJ1tBFafctX8, 2026-08-21)

Steps 1, 2 and 4 read as ready, and the diagnosis behind them is unusually well
controlled: the arm isolation, the seven controls with three refutations
recorded, and the refuted heavy-relation guess are exactly what the next person
needs and would not have re-derived. Question 1 is answered. What lands for a
consumer is legible without reading the phase list: putting the cursor on an SDL
declaration name and asking to navigate stops stalling over a second, the
declaration-name hover that shares those arms comes with it, and no surface can
later regress into the same shape without an enforcer failing.

Everything below is step 3.

**Finding 1 (question 2). Step 3 proposes to make `INTERACTIVE_READ_BUDGET` a
latency policy, which that constant's own javadoc refuses, and the spec does not
engage with the refusal.** `DevMojo`'s javadoc on the constant says the low-seconds
figure is "deliberately not a latency policy", that "the target is a query that
would otherwise never return", that "a threshold tight enough to police slowness
would start refusing correct answers on a loaded machine", and that
`LspTrace`'s own slow-span threshold is where latency gets reported. That
threshold is `graphitron.lsp.trace.slowMs`, default 100, which is inside the
range step 3 wants to move the budget into. So the spec's criticism of the
constant (one figure for surfaces with different contracts) is not the objection
the constant already answers, and the objection it already answers is the one
step 3 has to clear.

This is not a preference about numbers. Step 4 rejects the budget-arm enforcer
with the identical argument, "a slow enough machine flips it, which is the wall
clock the tiers refuse", so the spec accepts that argument in one section and
steps past it in the other. An implementer would open `DevMojo`, read the
javadoc telling them not to do the thing step 3 asks for, and have to settle it
themselves.

What would satisfy it: state why the recorded decision was wrong, or why a
navigation grain is the exception to it, and say what happens to the developer
whose machine is loaded when the budget fires. A budget miss is not a slow
answer, it is no answer, which is the same reading as the broken feature this
item exists to remove. If the answer is that latency policy belongs in
`LspTrace` and the budget stays a runaway guard, that is a coherent step 3 too,
and a smaller one.

**Finding 2 (question 2). The number rests on figures the spec itself calls
floors.** The fixture's class census is empty, so every arm resolving a consumer
class or method name does less work than in a real session, and the spec says
so. Picking a production threshold whose failure mode is a dropped answer, from
measurements the spec labels floors on a fixture it labels unrepresentative, is
the part I would not hand over. This compounds finding 1 rather than standing
apart from it: what makes a tight budget defensible is knowing the real ceiling,
and step 1 exists because the spec already applies that standard to the inlay
figure.

What would satisfy it: either a measurement with a populated class census behind
the number, or a budget chosen with margin against the unmeasured case and the
margin's reasoning stated.

**Finding 3 (question 2). The fork is stated at the wrong layer, and the layer
that owns it already documents this extension point.** Step 3's fork is "a new
reader per grain or a tighter bound on the existing interactive one", argued from
`StoreReader`'s javadoc and the connection count. But the class that routes
grains to readers is `StoreAccess`, whose javadoc names three doors
(`answering` for every interactive surface, `answeringAll` for the drain alone,
`readingSessionGraph` for session state), says the doors "partition the grains
exactly as they stand, which is what makes routing by door a delegation split
rather than a restructure", and closes with the sentence this item is the
occasion for: "If a second interactive caller of `answeringAll` ever appears,
the door is the thing to split." Step 3 mentions neither the class nor the doors.

So the shape is in the tree and the spec proposes beside it. Concretely, what an
implementer needs and cannot get from step 3: which surfaces move behind the new
door. Navigation alone, or navigation and the declaration hover that shares its
arms, and whether completion and code action keep the three-second guard. That
choice also decides what step 4's budget-arm candidate would import as "the
production navigation figure".

**Finding 4 (question 2). The fork is handed to the reviewer, and this gate
cannot take it.** "Open for the reviewer: whether this is a new reader per grain
or a tighter bound on the existing interactive one" asks for the one thing the
findings-not-fixes split exists to keep out of a spec. Naming both arms with
their arguments is the right preparation; the pick is the author's. Findings 1
through 3 are the material the pick needs.

**Finding 5 (question 2, smaller). Step 2's opening sentence describes a change
that is already textually present.** The arm reads
`.from(INTENT_TYPE_BACKING).join(SQL_TABLE).on(...)` with both cheap predicates
already in its `where`, so "the arm departs from the wrong end" and "rewrite the
arm so the filtered derived relation drives" name an edit an implementer would
find already made. The diagnosis is right and the spec knows it: H2 reorders,
which is why the derived-table control was refuted. But the instruction has to
be the structural one the controls actually measured, a correlated lookup on the
census keyed by the class name that H2 cannot reorder, not a driving-side
rewording. Stating it that way also keeps the refuted controls doing their job,
which is stopping the next person from spending the afternoon the author already
spent.

**Count correction, not a finding.** Five request surfaces share
`INTERACTIVE_READ_BUDGET`; the drain runs on `SESSION_READ_BUDGET`, as this
item's own measurement table records. "One figure for six surfaces" and "they
share a single three-second budget" overstate by one, and the five-surface
version is the accurate form of the same argument. Left for the author because
the sentences are being rewritten anyway.

**Non-blocking.** The claim that `DeclarationFacts` reads none of
`report-inline-multiplicity`'s fifteen heaviest is the one measurement here I
could not check without a build; R793 restates it independently, and the
mechanism claim behind it (`InlineMultiplicityCheck` computes from the DDL
alone, ranks relations, reports rather than gates, `TOP = 15`) is accurate as
written. The scan-count enforcer of step 4 is a new mechanism rather than an
extension of one in the tree (nothing reads H2's `scanCount` today), which the
spec is honest about pricing; R793's "whichever of the two lands second should
extend that enforcer" settles the ownership, so this is not a gap.
