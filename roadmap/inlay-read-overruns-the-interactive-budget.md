---
id: R799
title: "The inlay read reads the whole schema, and no enforcer sees it"
status: Ready
bucket: testing
priority: 4
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
---

# The inlay read reads the whole schema, and no enforcer sees it

One inlay-hint request costs the store a read of the whole captured schema, whatever window the
editor asked about. That is affordable today, at 170 ms and 20897 row scans for every hint in the
sakila example's 4222-line `schema.graphqls`, and it was not: the same read cost 10205 ms and 561851
row scans a day ago, which is four times the interactive budget and therefore no hints at all. What
closed the gap was two `meta_materialize` registrations landed by
`roadmap/diagnostics-drain-overruns-its-session-budget.md` for a different surface, and nothing in
the tree noticed either the defect or its removal.

So what lands for a consumer here is not a faster editor. It is that the surface cannot go back.
`SurfaceScanCountTest` was written to stop exactly this class of regression and it passes, green and
unmodified, against the shape that produced no annotations at all; this item is what fails there
instead. Nobody has to re-derive the measurement either, which took an afternoon twice: the write-up
below is the item's other half.

## The defect no longer reproduces, and here is what fixed it

The filing recorded 4797 ms, 13789 ms and 36695 ms for 10-, 50- and 200-line windows of the sakila
schema, at about 561700 row scans whatever the window. Driving the same request through
`InlayHints.compute` over a `StoreFixture.ofCatalog` capture of that schema on trunk today:

[cols="2,1,1,1",options="header"]
|===
| Request | Time | Hints | Rows scanned

| 50-line window
| 65 to 123 ms
| 1 to 12
| 8968 to 21065

| 200-line window
| 62 to 136 ms
| 16 to 28
| 20897

| the whole file, all 4222 lines
| 170 ms
| 450
| 20897
|===

Five windows spread down the file at each size, every axis of `InlayHintConfig` enabled, one
statement each as `InlayHintStatementCountTest` pins. The whole-file request is the ceiling an editor
could ask for and it is the cheapest per hint.

The control that attributes the change, because "it got faster between two runs" is not evidence.
Same probe, same fixture, same capture, only the two `meta_materialize` rows removed by checking out
the model DDL from the commit before them:

[cols="3,1,1",options="header"]
|===
| 50-line window at line 100 | Rows scanned | Time

| before the registrations
| 561851
| 10205 ms

| trunk today
| 21065
| 65 ms
|===

561851 reproduces the filing's 561746 to within the noise of a different window, so the item's own
measurement is confirmed rather than merely superseded, and the registrations are the whole
difference. That is one registration this item does not have to argue for and one it must not
undo: `intent_resolved_type_binding` carries a `COUNT(*) OVER` that no outside predicate prunes, and
every arm of this statement reaches it.

The registrations belong to an item that has since been sent back from In Review, and the finding
that sent it back is this item's own subject: its scan-count pin passes with the registrations
removed. The registrations themselves were not questioned. So this read's dependency on them is real
and unguarded, and if a later pass ever does remove them, what should say so is an assertion in this
class rather than a person remembering.

Where the boundary sits, so neither item waits on the other: that item's rework retunes its own pin,
`DiagnosticsStatementCountTest.theDrainsStatementStaysCollapsed`, and this item touches only
`SurfaceScanCountTest`. Neither changes a `meta_materialize` row.

## What the read still does, which is read the whole schema

The cost tracks the document rather than the window, and it always did. That is the filing's own
finding ("the region is not the lever") and it survives the fix: a 10-line window and the whole
4222-line file scan the same 20897 rows. Every arm reads a relation over the whole captured graph
and then filters, so the questions a window asks decide which rows come back rather than how many
are visited.

Measured against fixtures of `types` table-bound types each carrying `sites` fields whose `@field`
omits the name, one statement in every cell:

[cols="1,1,1,1",options="header"]
|===
| Types | Sites each | Rows scanned | Per declaration

| 30
| 1
| 1747
| 58

| 30
| 16
| 14648
| 31

| 60
| 1
| 3125
| 52

| 60
| 16
| 28686
| 30

| 120
| 1
| 5905
| 49

| 120
| 16
| 57146
| 30
|===

Linear in both dimensions with no cross term: doubling the types doubles the scans, quadrupling the
sites quadruples them, and the per-declaration figure settles at about 30 once the statement's fixed
floor stops dominating. A consumer's cost is therefore a constant times how much schema they wrote,
and at sakila's size that constant buys a 170 ms answer inside a three-second budget.

The scaling direction nothing here measured is the same one
`roadmap/lsp-surface-latency-budgets.md` named: the consumer's database catalog. Two arms reach the
catalog census, and this fixture family reuses thirteen sakila tables at every size, so the census
is held fixed by construction. That item measured a consumer's class census not to matter and the
catalog's row count to be the term that does, which is the finding to carry over rather than
re-derive.

## Why the enforcer that exists did not see any of this

`SurfaceScanCountTest` holds a scan-count ceiling per surface, inlay's being 1800, measured over a
three-type fixture. Run against the pre-registration DDL, the whole class passes: every ceiling,
including inlay's, including the diagnostics ceiling belonging to the surface the registrations were
made for. The inlay read cost 1544 scans at that fixture against its ceiling of 1800.

Two things are wrong there, and they want different repairs.

The narrow one is that the number does not discriminate. Inlay costs 482 scans at that fixture today
against 1544 for the defective shape, so the guarded shape sits three times *below* its own ceiling.
This is not a novel reading: the reviewer who sent
`roadmap/diagnostics-drain-overruns-its-session-budget.md` back from In Review found the identical
inertness in that item's own pin, measured the two shapes on its fixture at 1291 against 658 under a
ceiling of 20000, and asked for a ceiling that fails on the unregistered shape. The same remedy
applies here, to the surface this item owns, and the arithmetic is the same arithmetic.

The wider one is that a ceiling over a fixed small fixture cannot see a cost that grows with the
schema at all, because the fixture is where the growth is smallest: the defective shape cost 1544
scans over three types and 561851 over 239. Retuning the number to 800 catches this defect and says
nothing about the next one, which will be a term that is invisible at three types and dominant at
three hundred. `SurfaceScanCountTest`'s own javadoc reaches this conclusion about the defect it was
written for, in the same words, and answers it with `theCensusLookupDoesNotTrackTheSchemasSize`: a
differential measured at two schema sizes, where the difference is the property and neither size
alone is a claim.

So the item does both, which is the division that class already documents: the ceiling is the net and
it has to be tight enough to catch something, and the assertion beside it is the property.

## Implementation

Both changes are in `SurfaceScanCountTest`. No production code changes, and that is a finding rather
than an omission: the read is inside its budget on the largest schema the project has, its growth is
linear, and the levers that would reduce the constant further are a registration whose refresh every
capture pays and a rewrite of arms whose plans do not currently expand per driving row. Both would be
spent against no measured problem.

### The inlay ceiling is retuned to a number that fails on the defect

Lower the ceiling in `everySurfaceStaysUnderItsCeiling` from 1800 to about 800. Measured at that
fixture: 482 today, 1544 for the shape this guards, so 800 leaves a factor of about 1.7 on each side
of it. Re-measure both shapes at pickup and place the number in the window they actually leave.

State in the assertion's description, or beside it, what the number is for. A ceiling whose whole
value is that it discriminates cannot be raised on the strength of the current cost alone: raising it
because a new arm pushed today's number up is how it returns to being inert, and the honest move
there is to re-measure the guarded shape and see whether a window still exists.

### A second assertion states the property the ceiling cannot

`SurfaceScanCountTest` gains one test beside `theCensusLookupDoesNotTrackTheSchemasSize`, stating
what an inlay request costs per declaration of the schema it is pointed at, at two schema sizes.

The assertion is a level and not a growth ratio, and the measurement above is why. Both shapes are
linear in the schema, so a ratio assertion reads about 2 for the defective shape and about 2 for the
current one and catches nothing. What separates them is the size of the per-declaration constant,
about 209 scans against about 45 on the same fixture family. So the test asserts that constant, at
two sizes rather than one, because a single size cannot tell a bounded constant from the low end of a
superlinear curve.

Concretely:

* Extend the existing `scaledSdl` helper, or add one beside it, so a fixture carries a stated number
  of table-bound types each with a stated number of omitted-name `@field` sites. Sites matter as much
  as types, per the grid above, and one field per type understates the effect the registrations
  removed by about six times.
* Drive `InlayHints.compute` with every axis enabled over a fixed small region, through the same
  recording handle `scansFor` already uses, at two sizes. 60 and 240 types are the sizes measured
  here; each capture is about 1.5 s, against the 8 s a sakila-scale capture costs.
* Assert scans divided by declarations under one ceiling at both sizes. Measured at 45 per
  declaration, against 209 for the shape this guards; a ceiling near 90 fails the defect by better
  than two times and passes today with the same margin. Re-measure at pickup and set the number from
  what the chosen fixture actually costs, rather than carrying these figures across a fixture that is
  not the same one.

## Tests

The two assertions are the whole deliverable, so what "done" means is stated in their own terms:

* Both fail against the pre-registration DDL and pass on trunk. The DDL revert is a checkout of the
  model DDL from the commit before the two `meta_materialize` rows, followed by a rebuild of
  `graphitron-model`; the demonstration belongs in the implementation commit's message, since it is
  the only evidence either assertion is aimed at anything. An assertion that was not shown to fail is
  the defect this item is about.
* `InlayHintStatementCountTest` still reads one statement per request, unchanged. The retuned ceiling
  must not be reached by an inlay request over the existing fixture, which is the same statement
  `everySurfaceStaysUnderItsCeiling` already drives.
* The module's test wall clock is reported in the implementation commit. Two extra captures is the
  cost, and `roadmap/build-wall-clock-guardrail.md` is the item that cares.

## What this item does not do

* **It does not close the other five surfaces' gap, and the gap is real.** The pre-registration run
  passes every ceiling in the class, not just inlay's, so hover, definition, completion, code action
  and the diagnostics read are each guarded by a fixed-fixture ceiling that has not been shown to
  fail on anything. Naming that here rather than fixing it is a scope decision: this item was filed
  from an inlay measurement, and six retuned constants plus six per-declaration ones are twelve
  numbers to defend and as many shapes to measure a defect against. What the item does owe the next
  person is a helper written so a second surface is one more assertion rather than a second
  mechanism.
* **It does not make the read window-scoped.** Filtering earlier is the direction that would make
  the cost track the visible region instead of the document, and it is a question about how derived
  relations are read across the whole store rather than about this surface. The trigger for opening
  it is a consumer report or this assertion's own numbers rising, neither of which exists.
* **It does not touch a budget.** `INTERACTIVE_READ_BUDGET` stays the runaway guard that
  `roadmap/lsp-surface-latency-budgets.md` settled it as, and latency stays `LspTrace`'s slow-span
  tag. Worth recording, because it is a live reading of a real number: a whole-file inlay request
  over sakila at 170 ms trips that tag, which is off unless someone turned tracing on to investigate
  something. A tag firing on the slowest interactive read is the tag working.

## Retired vocabulary

None. Nothing here retires a symbol; the item adds a test and changes no production surface.
