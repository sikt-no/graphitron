---
id: R793
title: "The diagnostics drain overruns its 30 s session budget on a real workspace"
status: In Review
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
---

# The diagnostics drain overruns its 30 s session budget on a real workspace

Observed in a real dev session on 2026-08-21: `graphitron:dev` logged an out-of-budget warning for a
read on the session-wide reader, whose budget is `DevMojo.SESSION_READ_BUDGET`, 30 s. The aborted
statement is the diagnostics drain's, recognisable by its tail (a `diagnostic` read filtered to one
`file`) and by the relations it assembles ahead of that: `sql_table` and `sql_referential_constraint`
membership existence, `jvm_class` / `jvm_method` census reads, `intent_field_column_table`,
`intent_bound_table`, `intent_type_backing` and its seed, `intent_class_member_slot`,
`intent_carrier_data_field`, `graphql_type`, `graphql_directive` and its arguments, and
`graphql_field`. So the drain's one statement per graph spent 30 s and published nothing, which is the
posture that leaves whatever squiggles the last drain put on screen standing and unrefreshed.

Two things make this worth its own item rather than a footnote on the warning's wording. A drain that
cannot finish inside 30 s means diagnostics stop tracking the schema for as long as the condition
holds, silently from the author's side. And the budget is what turned an unbounded read into one lost
answer, so this is the mechanism working, not failing; what is missing is knowing which relation in
that statement is the expensive one.

The `store-performance` skill owns the method: time each relation in isolation against a populated
store, read the `EXPLAIN ANALYZE` scan counts, and run a same-fixture control before believing any
hypothesis. Nothing here presumes an answer, and in particular nothing here presumes the remedy is a
larger budget.

The observed statement is retained verbatim in the reporting session's transcript rather than pasted
here; the shape above is what identifies it.

## Plan

This is a database question about the relations behind one statement, so it is diagnosed inside the
database, in the order the `store-performance` skill sets out. Two items filed the same day already
did part of that work against these very relations, so the plan below starts from their measurements
rather than re-deriving them.

**Where the statement comes from.** `Diagnostics.Batch.judgeAll` groups the drain's questions by graph
and issues one `DiagnosticFacts.of(handle, questions)` per graph. That one statement is a select list
of roughly twenty correlated subqueries, one per population a diagnostic might need, and several of
them pair a base or census relation with a derived one. So the unit under test is one `DiagnosticFacts`
read, and the first move is attribution across its subqueries rather than a hunt for one expensive
relation.

**The leading hypothesis, and it is measured.** The language-server latency item, since shipped,
diagnosed the same shape one statement over:
`DeclarationFacts`'s `redirects` arm joins the census relation `sql_table` to the derived relation
`intent_type_backing` on the class name, H2 drives from the census side, and the derived relation is
therefore evaluated once per catalog table with its cheap filter applied after the expansion instead
of before it. That arm alone measured about 1.1 s where every other arm in the same statement
answered in 1 to 22 ms, and the shape that fixes it (drive from the filtered derived relation, reach
the census by `EXISTS`) measured 20 ms.

The drain's aborted statement contains that join, on that key:
`intent_type_backing join sql_table on sql_table.record_class_fqn = intent_type_backing.class_name`.
So the lever may already be known, which makes the work here attribution and confirmation rather than
a fresh diagnosis. That item also carries the scan-count signature to look for, about 3782 for the
slow driving order against roughly 60 for the rewrite, which is a sharper thing to grep a plan for
than "one large number".

**And the overrun is reproducible before any of this starts.** That item's probe drove the real
request methods over the sakila example's schema and recorded the drain, via `didOpen`, at 31310 ms
against the 30 s budget, publishing nothing. So this item no longer opens with a dev-session report it
has to reproduce: the fixture is stood up and the failure is in a harness.

**What not to re-run.** `roadmap/field-column-table-inlining-cost.md` measured
`intent_field_column_table` at 151 s for 116 rows unfiltered, with every one of its nine children
cheap, and attributed the cost to view inlining rather than to anything underneath it. That relation is
in the drain's statement, which makes the drain a live reader of it, but the drain reads it filtered by
graph and by three explicit type-field pairs, and the latency item's control puts a filtered derived
relation of this family at 22 ms.

The static multiplicity ranking is therefore the wrong place to start, and that is now measured rather
than argued: the latency item timed all six language-server surfaces and recorded that three of them
read relations in the fifteen heaviest while the slow one read none, so reading a heavy relation
predicted nothing about a surface's cost. `DiagnosticFacts` reading `intent_field_column_table` is one
of those three. What matters is whether this statement's filters reach a relation before its expansion
or after, which is the question the leading hypothesis asks.

**Steps.**

1. **Attribute the statement.** `EXPLAIN ANALYZE` over the drain's own statement, reduced to its
   `scanCount` lines, and read the scan nodes back to the subquery they belong to. The surface is the
   dev loop's `StoreConsole` against a live session, which needs neither a probe nor a rebuild and
   whose connect command is printed for pasting; `roadmap/store-query-mcp-tool.md` is the agent-side
   equivalent if it has landed by then. The two shapes to distinguish are one enormous scan count on
   a single node, a relation re-evaluated per driving row, against many nodes each carrying the same
   middling count, which is inlining.
2. **Price the suspect subqueries in isolation** against a populated store, one capture of the sakila
   example's schema against the sakila catalog with each subquery timed inside it. A subquery's own
   cost and the whole statement's cost are different numbers and the second is not informative until
   the first is in hand.
3. **Control before concluding**, on the same fixture, and the controls the navigation item already
   ran are the ones not to repeat: a derived table around the derived side and an `IN` subquery both
   left the plan unchanged there. What is worth running here is the driving-side swap on this
   statement, and removing a suspect subquery entirely to establish the floor.
4. **Pick a lever in the documented order**, captured fact before a `meta_materialize` registration
   before a rewrite. If the finding is the driving side, the lever is the join shape and neither a
   registration nor a materialization, which is what the navigation item's controls established for
   its own arm. If a registration does turn out to be the answer, its `reason` column carries the
   case in the relation's own arithmetic, and the refresh of a 151-second view is a cost to price
   rather than assume.

**Coordination.** The language-server latency item stated the boundary from its side and this
item holds the other half of it: that item does not touch this statement, and this item does not
restate the budget-per-grain question or ask for a larger budget. What the two must not do is grow two
spellings of one correction, so if that item lands its driving-side fix to `DeclarationFacts` first,
re-measure here before writing anything: the lever may transfer to `DiagnosticFacts` unchanged.

The enforcer currency is shared, and that item has already adopted this one's: a scan-count ceiling
over a surface's own statement, never a wall clock. Whichever of the two lands second should extend
that enforcer rather than introduce a second currency for the same property.

**Acceptance.** The drain's statement, against that populated capture, finishes with headroom inside
the interactive budget rather than merely inside the session one, and the plan's scan counts say why.
The number does not become a test: the tiers assert statement counts and plan shape, never wall clock,
so what is pinned in-tree is the scan-count shape via the existing statement-count tier, and the
timings live in this item's write-up and in the `reason` column of any registration it produces.

**Not in scope.** Raising the budget, which converts a diagnosed defect into a longer silence. Making
the drain asynchronous, which was the item beside this one and has since shipped, having been worth
doing whatever this item found. Splitting the drain's read per file, which would trade away the
one-snapshot consistency the batch exists to give and multiply the statement count.

## What the diagnosis found, and what was done

Steps 1 to 4 ran against a capture of the sakila example's schema through the LSP module's
`StoreFixture.ofCatalog`, on a machine whose one bare evaluation of `intent_field_column_table` cost
131 s against the 151 s the inlining item recorded, so the boxes are comparable. The statement was
taken verbatim off the real drain: `Diagnostics.Batch.judgeAll` run over the whole schema file with a
listener capturing the rendered SQL, then each of the statement's 22 select-list arms executed and
timed alone, with the walk's real question values inlined. One correction to this spec's own premise
fell out first: the 31310 ms the latency item recorded is the time until the budget aborted the
statement, not the statement's cost. Run to completion, the statement's expensive arms alone exceed
seven minutes on this box, so the budget was hiding most of the defect it reported.

**Attribution, before any change.**

[cols="3,2,2",options="header"]
|===
| Arm | Reads | Alone

| override arm
| `intent_field_column_table`
| 142 s

| redirect arm
| `intent_type_backing` joined to the census
| over 300 s, timed out

| backing arm, either population
| `intent_type_backing` / `intent_type_backing_seed`
| 1.9 s / 15 ms

| slot arm
| `intent_class_member_slot` joined to the backing
| 1.8 s

| every other arm, each alone
| censuses, spellings, base tables
| 0 to 33 ms
|===

**Two hypotheses died, and both refutations changed the plan.** The static multiplicity ranking
predicted nothing, as the plan expected: `intent_type_backing_seed`, the arm this session first
suspected from a mislabelled probe read, measures 15 ms. And the leading hypothesis named the right
mechanism on the wrong lever: the redirect arm's census-driven join is real and is the worst term,
but the driving-side rewrite the navigation item measured for `DeclarationFacts` turned out
unnecessary here, because the term that makes every driving order ruinous is one relation further
down. `intent_resolved_type_binding` carries a `COUNT(*) OVER`, so no outer predicate prunes it: the
backing arm filtered to one type costs 22 ms, the same arm filtered to a whole document's types is
re-evaluated once per filter element and costs 1.9 s, and the census-driven join re-evaluates it per
driving row and never finishes. That is `intent_spelled_table`'s registration case verbatim, on a
relation named fifteen times across thirteen view bodies whose one evaluation is 25 ms for 61 rows.

**The levers pulled are two `meta_materialize` registrations, and no Java changed.**

[cols="3,1,1",options="header"]
|===
| Read, on the same capture | Before | After

| redirect arm alone
| over 300 s
| 6 ms

| backing and slot arms, each
| 1.9 s
| 5 ms

| one evaluation of `intent_field_column_scope`
| 1.6 s
| 5 ms as a table; its refresh evaluates the rule once at about 170 ms

| one evaluation of `intent_field_column_table`
| 131 s
| 144 ms

| override arm alone
| 142 s
| 139 ms

| **the drain's whole statement**
| **aborted at 30 s; over seven minutes to completion**
| **191 ms**
|===

`intent_resolved_type_binding` fell first and removed everything but the override arm: the scan
attribution of the residual 9.5 s put 63758 of its scans on `sql_constraint_column` under the
reference-step machinery, re-entered per row by the correlated `NOT EXISTS` that
`intent_field_column_table`'s unresolved-path arm runs against `intent_field_column_scope`. So the
scope was the second registration, priced at one 170 ms evaluation per capture per graph against the
per-row re-evaluations it ends. Both `reason` rows carry the arithmetic. The refresh cost the plan
said to price lands at about 175 ms per capture for the pair, and the registration of the 131-second
view itself was rejected on exactly the refresh-cost ground the plan reserved for it.

**The pin.** `DiagnosticsStatementCountTest.theDrainsStatementStaysCollapsed` captures the drain's
own statement off the production read and `EXPLAIN ANALYZE`s it, asserting a total scan-count
ceiling. That is the enforcer currency the coordination section agreed with the latency item, whose
own `SurfaceScanCountTest` carries it across the six surfaces; that test was already on trunk when
this one landed, so this is the second use of the currency rather than the first.

The first cut of this pin did not discriminate and was sent back at the gate; what it asserted and
why it failed to are recorded in the reviewer findings below. It now stands up its own graph of forty
table-bound types, because the separation between the two shapes is a property of how many rows the
statement drives and is invisible at the four types the class's shared fixture carries. On that graph
the collapsed shape totals 6924 scans against the unregistered shape's 23983, and the ceiling is
15000, confirmed to fail with both registrations removed from the DDL before it was trusted.

**Hand-back to the latency item, since discharged.** Its step 2 prescribed a correlated-lookup
rewrite of `DeclarationFacts`'s redirects arm, measured against the binding as a view. The
registration landed here removes the term that rewrite works around, and this statement's identical
arm fell from timeout to 6 ms with no Java change, so the hand-back asked that item to re-measure
against the registered binding before writing anything. It did: that item shipped the rewrite and
re-ran its four affected suites against these registrations, on the ground that this change moves the
counts its own ceilings measure. Neither item grew a second spelling of the correction, which is what
the shared boundary existed to prevent.

The acceptance criterion holds with more headroom than it asked for: 191 ms against the 3 s
interactive budget, on a box slower than the one that filed the report, and the scan counts say why.


## Reviewer findings

### Round 1, In Review, rework requested

Question 1 passes. The diagnosis is the work this item asked for and the delivery is the change the
spec approved. The lever order was honoured: the plan's conditional said a driving-side finding takes
a join rewrite and not a registration, the measurement refuted the driving side and found an
unprunable term under it, and a registration is what that finding licenses. The two `reason` rows
carry the arithmetic the plan asked them to. Nothing was widened: no Java changed, and the plan's
reserved refusal (registering the 131-second view itself, on refresh cost) was applied rather than
quietly dropped. The `_live` pair, the canonical-name discipline and the `meta_materialize` row are
the established shape, not a parallel mechanism: three registrations already use it. Refresh
ordering, cycle-freedom, kind and column-shape agreement between each hand-written target table and
its view are all pre-existing build gates, and all six of `MaterializeRegistryGateTest` and all eight
of `MaterializationOrderTest` pass on the delivered tree, so the risks a view-to-table conversion
carries are enforced rather than argued. Full `mvn install -Plocal-db` is green.

Question 2 fails, on the one piece of evidence the spec named for itself.

**The pin cannot fail on the shape it exists to guard.** The acceptance said what is pinned in-tree is
the scan-count shape via the statement-count tier, so `theDrainsStatementStaysCollapsed` is this
item's completeness evidence and the gate turns on whether it holds. It does not discriminate.
Reverting the whole delivery, both `meta_materialize` rows and both view-to-table conversions, and
keeping the new test, leaves it green: the SQL resource was reverted to its parent state, the model
rebuilt, and the test passed in 4.2 s. Measured on the fixture the test actually runs, by lowering the
ceiling to 1 and reading the actual off the failure in each shape:

[cols="3,2",options="header"]
|===
| Shape, same fixture | Total `scanCount`

| unregistered, the shape this guards against
| 1291

| registered, as delivered
| 658

| the ceiling asserted
| 20000
|===

So the ceiling sits about fifteen times above the worst number the regression produces here, and the
guard would pass on a tree with the fix entirely removed. The delivered 658 corroborates the write-up,
which is worth saying: the measurement is right and the fix is real. It is the enforcer that is inert.

The javadoc explains the ceiling with a claim that is false on this fixture: that the guarded shape
"measures in the hundreds of thousands of scans on this fixture". Hundreds of thousands is the
sakila-scale number from the write-up's attribution work. `StoreFixture.ofCatalog(tmp, GRAPH_SDL,
...)` is a far smaller catalog, and on it the two shapes are 1291 against 658. That sentence is what
made a 20000 ceiling look like a generous margin over a catastrophe when it is fifteen times the
catastrophe.

What would satisfy the question: a ceiling that fails on the unregistered shape and passes on the
delivered one. The numbers above leave a factor of two to place it in, they are scan counts rather
than a clock so they are deterministic, and the tier's own reason for existing is that this residue
is the clock-free one. Retuning the constant is the whole change; whether the fixture should also be
grown so the separation is wider than 2x is the implementer's call and not something this gate asks
for. Correct the javadoc's fixture claim in the same pass, to the numbers actually measured here.

Not blocking, and not asking for anything: the hand-back to the latency item is the right shape and
its instruction to re-measure before writing is what keeps the two from growing two spellings of one
correction.

Reviewer rule: the implementation commits are `session_01Bh91SfEBTRb6MjmgfYG9Wm`; this session is
`session_01ArRUrte6WnVy19HnpRyvLM`, a different party. This session authored the spec body, which the
In Review gate's rule does not bar, and is disclosed here rather than left to be noticed.

### Round 1 corroboration, and the fixture-size question answered

A second In Review gate ran concurrently against the same tree
(`session_01Rxu8sAUqhx2sKc4urHo392`) and reached the finding above independently, by reverting the
SQL resource to the delivery's parent, rebuilding the model and re-running the class rather than by
lowering the ceiling. It reproduces both numbers exactly, 658 delivered and 1291 unregistered, and
adds that all twelve cases in the class stay green on the reverted tree, not only the new one. Two
methods, one answer, so the finding is not an artefact of how the actual was read.

What that pass adds is the measurement the finding above left to the implementer: whether growing the
fixture is worth it, and how far. The separation is a property of how many rows the statement drives,
so it widens with the graph and settles quickly. Same box and catalog, one file of N table-bound types
each carrying two column-bound fields and a reference:

[cols="2,2,2,1",options="header"]
|===
| Graph | Unregistered | Delivered | Ratio

| 4 types, the fixture the test uses
| 1291
| 658
| 2.0x

| 10 types
| 7407
| 2244
| 3.3x

| 40 types
| 23727
| 6924
| 3.4x
|===

So the ratio is about 3.4x once the driving side is big enough to show it, and it is not orders of
magnitude at any size a unit test would want; the sakila-scale catastrophe in the write-up comes from
a catalog and a schema this tier does not stand up. That bounds both repairs. Retuning the constant
on the present fixture has a factor of two to sit in, which works and is tight. Growing the fixture to
around forty types instead leaves the already-chosen 20000 exactly where it is and lets it do the job
it was written for: the unregistered shape breaches it at 23727 while the delivered shape keeps a
threefold margin at 6924. The second is the cheaper thing to defend later, since the number stops
being a constant somebody has to justify against a nearby failure.

Worth knowing either way: these are synthetic types all bound to one table, which is a lower bound on
the divergence rather than the worst case, so a fixture built from varied bindings would separate the
two shapes further at the same size.

Not blocking, and for the same pass. Three citations in this body now dangle:
`roadmap/lsp-surface-latency-budgets.md` is cited at the leading hypothesis, at Coordination and in
the write-up, and that item reached Done and deleted its file while this one was in review. The
write-up's sentence also has the landing order backwards, saying the scan-count enforcer landed first
here when that item's `SurfaceScanCountTest` was already on trunk. Restate what those citations carry
rather than repointing them, since the target is gone.
