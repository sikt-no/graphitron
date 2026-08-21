---
id: R793
title: "The diagnostics drain overruns its 30 s session budget on a real workspace"
status: Spec
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
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
database, in the order the `store-performance` skill sets out. What follows fixes the order and the
acceptance criterion; it deliberately does not name a remedy, because the first three steps exist to
stop a remedy being chosen from a guess.

**Where the statement comes from.** `Diagnostics.Batch.judgeAll` groups the drain's questions by graph
and issues one `DiagnosticFacts.of(handle, questions)` per graph. So the unit under test is one
`DiagnosticFacts` read for one graph, and the suspects are the relations it names: `intent_field_column_table`,
`intent_bound_table`, `intent_type_backing` and its seed, `intent_class_member_slot`,
`intent_carrier_data_field`, alongside base-table reads of `sql_table`, `sql_referential_constraint`,
`sql_column`, `jvm_class`, `jvm_method`, `graphql_type`, `graphql_directive`, `graphql_field`,
`graphitron_node` and `diagnostic`.

**Steps.**

1. **Rank statically, to pick what to price first.** `report-inline-multiplicity` puts
   `intent_field_column_table` in the top ten relations by instantiations per read, and none of the
   drain's other derived relations in the top fifteen. That makes it the first suspect and nothing
   more: the report ranks breadth, and breadth is not cost.
2. **Price each relation in isolation against a populated store.** A capture of the sakila example's
   own schema against the sakila catalog, timed inside one capture rather than one per test method.
   This is the measurement every later step rests on, and it is also the control that most often
   refutes the hypothesis one starts from: a relation whose children each answer in milliseconds and
   which itself takes minutes has no expensive child, and its cost is the expansion.
3. **Read the plan.** `EXPLAIN ANALYZE` over the drain's own statement, reduced to its `scanCount`
   lines. The dev loop's `StoreConsole` is the surface for this against a live session, which needs
   neither a probe nor a rebuild; the paste-able connect command it prints is the entry point. The two
   shapes to distinguish are one enormous scan count on a single node, a relation re-evaluated per
   driving row, against a few hundred nodes each carrying a middling count, which is inlining down the
   tree.
4. **Control before concluding.** Whatever step 3 suggests, the refuting control on the same fixture
   is run before a line of code changes: snapshot the suspect into a table and re-run, or remove it
   from the statement entirely and see whether the cost was ever about it. A control that refutes the
   reading is recorded here, not discarded.
5. **Pick a lever in the documented order**, captured fact before a `meta_materialize` registration
   before a rewrite, and for a registration answer the three questions that make it a shared
   investment rather than a local patch: how many relations read the candidate, what its refresh
   costs, and whether a deeper relation is the one the cost multiplies through. Four registrations
   exist already and none is one of the drain's relations, so this is a fresh case to make in the
   `reason` column, in the relation's own arithmetic.

**Acceptance.** The drain's statement, against that populated capture, finishes with headroom inside
the interactive budget rather than merely inside the session one, and the plan's scan counts say why.
The number does not become a test: the tiers assert statement counts and plan shape, never wall clock,
so what is pinned in-tree is the scan-count shape via the existing statement-count tier, and the
timings live in this item's write-up and in the `reason` column of any registration it produces.

**Not in scope.** Raising the budget, which converts a diagnosed defect into a longer silence. Making
the drain asynchronous, which is `roadmap/diagnostics-drain-leaves-the-triggering-thread.md` and is
worth doing whatever this item finds. Splitting the drain's read per file, which would trade away the
one-snapshot consistency the batch exists to give and multiply the statement count.

