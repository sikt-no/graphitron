---
id: R795
title: "No language-server surface blocks the editor"
status: Ready
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
---

# No language-server surface blocks the editor

Jump-to-definition in the language server hangs. Putting the cursor on certain SDL
declaration names and asking the editor to navigate stalls for over a second, and
trying the feature in a few places in a row hits enough of those to read as a
broken feature. The language server is the most latency-sensitive frontend the
project has: it is the only one where a slow answer arrives into a blocked cursor,
so a slow answer and no answer are the same outcome to the person waiting.

The item is scoped to every surface rather than to the one that was reported,
because one surface's budget is the wrong unit. Six surfaces read the fact store:
five request surfaces sharing one three-second budget that was not chosen for any
of them in particular, and the drain on its own thirty-second one. Measuring all
six is also what found that the reported surface is not the worst one.

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
| Not a measurement. Re-measured below at 11918 ms.

| the diagnostics drain, via `didOpen`
| 31310 ms
| 31310 ms
| Overran its 30 s budget and published nothing.
|===

One caveat stood on those figures, and confirming it inverted the item's picture
of which surface is worst. The inlay figure was too cheap to trust: inlay hints
are configuration-gated through `applyPulledInlayHintConfig`, the probe pulled no
configuration, and a surface that returned nothing is not a surface that was
measured. `InlayHintConfig.defaults()` has every axis off, so 10 ms was the cost
of an early return.

Measured again with every axis enabled, over the same schema: **11918 ms** for a
fifty-line window producing ten hints, and 2013 ms for a different fifty-line
window of the same file. So inlay is not the cheapest request surface but by far
the most expensive, and at four times the interactive budget it does not return
hints in a real session at all. What that changed in this item is step 3, which
that number decided; the read's own cost belongs to
`roadmap/inlay-read-overruns-the-interactive-budget.md`, filed from this
measurement, and step 3's note says why the two are different fixes.

A second caveat was checked and withdrawn, and what it turned into matters more
than the caveat did. The figures above came off a fixture with an empty class
census, so every arm resolving a consumer class or method name did less work than
in a real session. Re-measured against a census scanned from four real reactor
outputs, 1096 classes and 4073 methods:

[cols="3,1,1",options="header"]
|===
| Read | Empty census | Populated census

| One `DeclarationFacts` statement
| 1154 to 1475 ms
| 905 to 1184 ms

| The `redirects` arm alone
| 1109 ms
| 960 ms

| Every other arm, each alone
| 1 to 22 ms
| 1 to 28 ms
|===

The population does not move the cost, and the reason is the finding: the arm's
expense is `sql_table`'s row count times one evaluation of the view, and
`sql_table` is a function of the consumer's *database catalog*, not of their
class census. Sakila declares 61 tables. A consumer whose catalog declares
several hundred pays proportionally, which is the direction that puts this arm
past even the three-second guard, and it is the scaling dimension to state
because it is the one nothing here measured.

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

So the lever is the join's *shape*, not a `meta_materialize` registration and not
a rewrite of the view. It is specifically not the placement of the predicates or
of the `FROM`: the two refuted controls are both ways of asking H2 to filter
first, and H2 reordered past both. What separates the working control from the
others is that a correlated lookup gives the planner no driving side to choose.
What one row of this answer means is one (backing class, bound table) pair, and
the relation that owns that key is the backing relation, not the catalog census.
The arm needs the census's columns and not merely its existence, so the working
control is the evidence for the shape rather than the patch itself.

`DeclarationHovers` reads the same arms, so this carries the declaration-name
hover with it. That is also why hover's 96 ms maximum should not be read as
hover being safe: the probe's positions happened to miss the coordinates that
reach this arm.

## What changed

All four steps have landed. Each section below states what was built and what
measuring it said, since three of the four found something the plan did not know.

### 1. Confirm the inlay figure: done, and it inverted the picture

Measured with every axis enabled, above. Inlay is the most expensive request
surface rather than the cheapest, and it overruns the interactive budget by four
times on a fifty-line window. That settled step 3 in favour of the split and
raised a defect this item does not fix, filed as
`roadmap/inlay-read-overruns-the-interactive-budget.md`.

Nothing in the tree changed for this step: it was a measurement, and what it
produced is the two numbers above and the item beside it.

### 2. The redirect arm reaches the census through a correlated lookup: done

`DeclarationFacts.redirectArm` now selects from the filtered backing relation and
carries the census as a nested multiset correlated on the class name, flattened
back to one (backing class, bound table) row per pair. Measured on the sakila
schema: the arm **1098 ms to 21 ms**, and the goto-definition it is the expensive
part of **about 1300 ms to 45 ms**, returning the same row. The rows and their order are
unchanged, the sort restoring in Java the census order the flat join stated in
SQL, and the description is passed through unnormalised exactly as before.

What follows is the reasoning the change was made from, kept because it is what
stops the next person reinstating the join.

Not a driving-side rewrite. The arm already reads
`.from(INTENT_TYPE_BACKING).join(SQL_TABLE).on(...)` with both cheap predicates
already in its `where`, so an instruction to make the filtered relation drive
names an edit an implementer would find already made. H2 reorders the join
regardless, which is what the refuted derived-table and `IN`-subquery controls
measured: two ways of saying "filter first" that H2 was free to ignore, and did.

What changes is the shape, to one H2 has no freedom to reorder: the census is
reached as a correlated lookup keyed by the class name, evaluated per surviving
backing row, rather than as a join partner it can pick as the driving side. That
is the grain the `nested-jooq` skill describes, one row of the answer being one
(backing class, bound table) pair driven from the relation that owns the key.
Measured on the populated census at 15 ms against the production shape's 881 ms,
which is the same sixty-fold the `EXISTS` control showed and confirms the shape
rather than the predicate placement is what carries it.

The arm needs the census's columns, not merely its existence, so the `EXISTS`
control is the evidence and not the patch.

The constraint is that the answer must not change. `DeclarationFacts` is one
statement by design, and both readers of these arms
(`DeclarationDefinitions` and `DeclarationHovers`) must keep the rows they have
today, including the ordering the arm states.

### 3. The budget stays a runaway guard; the door is what splits: done

`StoreAccess` grew a third reader and a fourth door, `annotating`, which
`GraphitronTextDocumentService.inlayHint` now goes through; `Workspace` delegates
to it as it does to `answering`, and `DevMojo` mints the third reader. Hover,
definition, completion and code action stay on `answering`. No budget changed.

The third reader takes `INTERACTIVE_READ_BUDGET` rather than a constant of its
own. Step 3's whole finding is that the budget is not the lever, and a second
constant holding the same number would invite somebody to tune it into the
latency policy the budgets are deliberately not. What the split changes is which
queue an inlay request waits in.

Step 1's measurement made this step's case stronger than the draft's. A single
fifty-line inlay request does not finish inside the interactive budget, so before
the split it held the interactive reader for the full three seconds and was then
aborted: every hover and jump queued behind it waited three seconds for a request
that produced nothing. That is the mechanism behind trying the feature in a few
places and getting a lot of hangs, and no single surface's own cost explains it.
The split does not make that request answer, which is the other item's; it makes
it somebody else's queue.

The reasoning the step was decided on follows.

The budget is not the lever, and the earlier draft of this step was wrong about
it. `INTERACTIVE_READ_BUDGET`'s own javadoc records the decision: the low-seconds
figure is "deliberately not a latency policy", the target is "a query that would
otherwise never return", and "a threshold tight enough to police slowness would
start refusing correct answers on a loaded machine". That decision was right, and
this item is not the exception to it. A budget miss is not a slow answer, it is
*no* answer, rendered in the editor as a jump that does not happen: the same
thing the developer reported as a broken feature. A dev session routinely runs
beside a full reactor build, so a threshold in the low hundreds of milliseconds
would drop correct answers on exactly the machine state where a developer is most
likely to be navigating. Tightening it would trade a stall the next steps remove
for a silence they cannot.

It would also have been incoherent with step 4, which rejects the budget-arm
enforcer on the argument that a slow enough machine flips it. That argument does
not become weaker when the same clock is moved into production.

Latency already has an owner, and the javadoc names it: `LspTrace`'s slow-span
threshold, `graphitron.lsp.trace.slowMs`, default 100. After step 2 a definition
read is a few tens of milliseconds, which makes that tag meaningful for the first
time rather than a line every navigation prints. Nothing needs to move there;
what needed to change is this spec's claim that the budget should do that job.

What *is* wrong is that the five request surfaces share one reader, and
`StoreAccess` is where that is decided rather than `DevMojo`. Its three doors
partition the grains: `answering` for every interactive surface, `answeringAll`
for the drain alone, `readingSessionGraph` for session state. Reads on one reader
serialize, which `StoreAccess`'s javadoc gives as the whole reason there are two
readers and not one, and behind `answering` the same serialization is still
there: a whole-file inlay request and a cursor-blocking hover queue against each
other on one connection, with the cursor waiting on the request nobody is looking
at. That is the mechanism behind "trying it in different places gives a lot of
hangs" in a way no single surface's own cost explains.

So the split is by *who is waiting*, not by a number. `inlayHint` moves behind a
door of its own with a reader of its own: it is the one request surface whose cost
scales with the document rather than the cursor, it is re-requested on every
viewport change, and a hint arriving late is invisible where a cursor arriving
late is not. Hover, definition, completion and code action stay on `answering`,
all four being cursor-blocking and cheap once step 2 lands. Every reader keeps a
low-seconds runaway guard; no reader acquires a latency policy.

Two honest qualifications. `StoreAccess`'s javadoc closes by inviting a split
when "a second interactive caller of `answeringAll`" appears, which is not this
trigger, so this extends that javadoc's reasoning about head-of-line blocking
rather than cashing the invitation it actually wrote. And the split is worth a
third connection only if the contention is real; step 1 was ordered first to
settle that, and it did, at four times the budget.

### 4. Every surface is enforced, not spot-checked: done, with the metric refined

`SurfaceScanCountTest` covers all six surfaces, driving each through the same
provider seams the statement-count enforcers drive, over a handle that records
what it executes, and reading the `scanCount` H2 reports under `EXPLAIN ANALYZE`.
The scan-count currency is R793's, as this item said it would be.

One thing had to change, and it is the reason this section is longer than the
others. **A per-surface ceiling on its own would not have caught the defect this
item was filed for.** Measured at the enforcer's fixture, the join shape cost a
declaration read 121 extra scans, which against a definition read's 808 is 15%:
no ceiling anybody would defend catches that. Against the same fixture at sakila's
scale the excess is 3721 out of 28453, still 13%, and most of the number belongs
to a view this item does not own, so the ceiling would also move whenever R793
touches it.

So the enforcer states the property two ways. The ceilings are the broad net the
plan asked for, one per surface, each meant to fail on an order-of-magnitude
regression. The sharp assertion is `theCensusLookupDoesNotTrackTheSchemasSize`,
which takes the arm by the name it carries in the statement and measures what
reaching the census costs *over reading its driving relation alone*, at two
schema sizes. That difference is the lookup and nothing else: it excludes the
driving view's own cost, which is what swamped the totals and what this item does
not own. Written as a join the excess was 184 scans over a one-type schema and
3724 over a sixty-type one; as a correlated lookup it is 63 at both, which is the
census's own row count once. A per-row expansion cannot hide in that number at
any fixture size, which is also what lets the fixture stay small enough that the
tier's refusal of fixture scale still holds.

Both tests were confirmed to fail with the arm reverted and pass with it in
place.

The reasoning the currency was chosen on follows.

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
realistically captured schema and assert every answer is `StoreAnswer.Answered`
rather than `StoreAnswer.OutOfBudget`, which is `StoreOutOfBudgetTest`'s own
trick run the other way round. It encodes the wanted property most directly.
Set against it, and decisively now that step 3 has settled: the only budget it
could import is the low-seconds runaway guard, so it would pass a surface that
takes two and a half seconds, which is the defect this item was filed for. To
catch that it would need a threshold of its own, invented here, and a slow
enough machine then flips it. That is the wall clock the tiers refuse, and it
would leave the project with two enforcer currencies for one question.

Either way the enforcer covers all six surfaces: the five request surfaces,
including the two R782 adds counts for, plus the drain. So the two items should
land in an order where the later one does not re-enumerate the set.

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
split. The asynchronous-drain item beside it has since shipped, so the drain no
longer runs on the thread that triggered it and `didOpen` no longer blocks
whatever this item settles about budgets. That was worth doing either way and it
is not a substitute for knowing why the statement costs what it does, which is
still R793's.

## Retired vocabulary

None. Nothing here retires a symbol, and the budget constants keep both their
names and their number: step 3 gained a reader and a door, not a fourth budget.
`StoreAccess`'s constructor took a third reader, which is a signature change
rather than a retirement; its three call sites moved with it.

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

### Round 2, Spec → Ready, signed off (session_01MtzM82PqeYAJ1tBFafctX8, 2026-08-21)

All five of round 1's findings are closed, and two of them by more than was
asked. Finding 1 is settled in the direction round 1 named as the coherent
alternative: the budget stays a runaway guard, `LspTrace`'s slow-span threshold
keeps latency, and the reversal is argued rather than asserted. Finding 2 was
answered by going and measuring, which also produced the thing the spec was
missing: the arm's cost tracks `sql_table`'s row count, so the scaling dimension
is the consumer's catalog rather than their class census, and that is now stated
as the unmeasured direction. Finding 3 moved the step into `StoreAccess` and
finding 4's fork is picked. Finding 5's step 2 now instructs the shape the
controls actually measured, and the count correction landed in both places.

Verified for this pass: `StoreAccess.answering`, `answeringAll` and
`readingSessionGraph` exist as named, and `inlayHint` reaches the store through
`answering`, so step 3's premise is real rather than assumed: a document-scoped
inlay request and a cursor-blocking hover do serialize on one reader today.

Three observations, none blocking and none needing a reply. The revision put its
responses in the plan body and the commit message rather than as notes beneath
each finding, so this pass audited by re-reading rather than by reading a delta;
the convention is in `roadmap/workflow.adoc` under item file conventions. Step 3
is conditional on step 1's measurement with a qualitative criterion ("expensive
enough to block a cursor queued behind it"); the material for that call is
present, and `LspTrace`'s 100 ms tag is the obvious yardstick if the implementer
wants a number. And an `inlayHint` request carries a range rather than a
document, so "the whole-file request" is a ceiling rather than what an editor
sends; measuring the ceiling is the conservative direction for a should-we-split
decision, so the framing is loose in a way that does not mislead.

### Round 1, In Review → Done, rework requested (session_01E8fSaAUXqPaqyLagPFEs82, 2026-08-22)

One finding, and it is the only thing standing between this and Done. The code is
right and the evidence is real; the document that teaches the mechanism the item
changed still teaches the mechanism it replaced.

**What was verified, so the next pass does not re-derive it.** Question 1 is
answered. The arm rewrite is faithful column for column: the flat form's
`value2..value6` and the nested form's `value1..value5` land on the same
`CatalogTable` and `TableRow` components, the inner join's drop of a backing class
the census does not name is reproduced by an empty nested multiset flat-mapped to
nothing, and `CENSUS_ORDER` restores exactly the `ORDER BY TABLE_SCHEMA,
TABLE_NAME` the flat form stated, with no tie to break since one census row names
one record class. Both consumers of the arm (`Rows.scope`, `Rows.tables`) read it
through that order and are unaffected. Step 3 shipped as step 3 was approved: four
doors, `inlayHint` alone on `annotating`, hover, definition, completion and code
action still on `answering`, the drain still on `answeringAll`, and both budget
constants unchanged at 3000 and 30000.

Question 2 was checked by mutation rather than taken on the commit message's word.
With `redirectArm` reverted to the flat join, `theCensusLookupDoesNotTrackTheSchemasSize`
fails at the one-type schema and `everySurfaceStaysUnderItsCeiling` fails on the
type-declaration ceiling; both pass with the arm as delivered. So the enforcer
does catch the defect it was written for, and it catches it at the smaller of the
two schema sizes, which is what makes the fixture's size defensible. The door
split has an enforcer too, `eachDoorReachesTheReaderItsGrainStates`, added after
the step 3 diff and not mentioned in the body above. `mvn install -Plocal-db`
passes on the delivered tree, and the four affected suites still pass after
rebasing onto R793's `meta_materialize` registrations, which was worth checking
because that change moves scan counts the ceilings measure.

**Finding 1 (question 2). `docs/architecture/how-to/dev-loop-internals.adoc`
still describes the two-reader shape, and states as its splitting principle the
one this item reversed.** The paragraph beginning "The session mints three store
readers, not one" is wrong three ways after this item. The session mints four, not
three, and three of them go to the LSP rather than two. Its enumeration of the
doors names `answering`, `answeringAll` and `readingSessionGraph` and omits
`annotating`, so a contributor choosing a door for a new surface cannot learn from
this page that an annotation door exists. And it gives the reason for the split as
"per latency contract rather than per consumer", which is the argument step 3 set
out to replace: the annotation reader carries the interactive budget, so its
latency contract is identical, and a contributor applying the documented principle
would conclude that no third reader was warranted and route a new
document-scoped surface onto the cursor's reader. That is the head-of-line
blocking this item removed, reintroduced by following the documentation.

This is not a phrasing point. It is the completeness surface question 2 names
alongside the tests, on the item's own headline mechanism, and it matters more
once this file is deleted at Done: `StoreAccess`'s javadoc is excellent and will
carry the reasoning for anyone editing that class, but the dev-loop how-to is
where the reader topology is taught to somebody who is not editing it yet. The
precedent is one commit before this item's implementation: `1dbdd38`, R796's
rework round 1, made the same file's stale reading a blocking finding for an
analogous LSP change.

What would satisfy it: the paragraph updated to four readers, three of them the
LSP's, the four doors enumerated with `annotating` among them, and the splitting
principle restated as the one that shipped, which is who is waiting rather than
what budget they wait under. Three sentences, and the reasoning to draw on is
already written in `StoreAccess`'s class javadoc.

**Non-blocking, no reply needed.** The step sections were not collapsed to
one-line "shipped at `<sha>`" notes as the Publishing convention suggests, and
carry no SHAs at all; that convention also sanctions capturing learnings, the
learnings here are substantial, and they have a durable home in the javadoc of
`redirectArm`, `StoreAccess` and `SurfaceScanCountTest`, so nothing is lost when
the file goes. Step 4's claim that "no ceiling anybody would defend catches that"
is a little stronger than the delivery: the type-declaration ceiling of 260 does
catch the reverted arm, the 121-extra-scans arithmetic having been taken against
the member read's 808. Nothing pins that `GraphitronTextDocumentService.inlayHint`
routes through `workspace.annotating` rather than `workspace.answering`; the door
test pins that the door reaches its own reader, so a one-line edit at the call
site would put inlay back in front of the cursor with every test green, and
`TextDocumentServiceTest` already drives the service if that is worth closing.
And two sentences left over from the two-reader era read oddly now rather than
wrongly: `StoreAccess`'s "Every answer goes through {@link #answering}", which
`annotating` no longer does, and the two places that say "the head-of-line
blocking two readers exist to remove".
