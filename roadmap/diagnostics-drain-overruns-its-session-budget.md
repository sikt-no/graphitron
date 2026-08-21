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
database, in the order the `store-performance` skill sets out. Two items filed the same day already
did part of that work against these very relations, so the plan below starts from their measurements
rather than re-deriving them.

**Where the statement comes from.** `Diagnostics.Batch.judgeAll` groups the drain's questions by graph
and issues one `DiagnosticFacts.of(handle, questions)` per graph. That one statement is a select list
of roughly twenty correlated subqueries, one per population a diagnostic might need, and several of
them pair a base or census relation with a derived one. So the unit under test is one `DiagnosticFacts`
read, and the first move is attribution across its subqueries rather than a hunt for one expensive
relation.

**The leading hypothesis, and it is measured.**
`roadmap/goto-definition-navigation-budget.md` diagnosed the same shape one statement over:
`DeclarationFacts`'s `redirects` arm joins the census relation `sql_table` to the derived relation
`intent_type_backing` on the class name, H2 drives from the census side, and the derived relation is
therefore evaluated once per catalog table with its cheap filter applied after the expansion instead
of before it. That arm alone measured about 1.1 s where every other arm in the same statement
answered in 1 to 22 ms, and the shape that fixes it (drive from the filtered derived relation, reach
the census by `EXISTS`) measured 20 ms.

The drain's aborted statement contains that join, on that key:
`intent_type_backing join sql_table on sql_table.record_class_fqn = intent_type_backing.class_name`.
That makes this item the concrete answer to that item's own third open question, "whether other
single-statement readers join a derived relation from the census side the same way": one does, and it
is `DiagnosticFacts`. It also means the lever is likely already known, so the work here is attribution
and confirmation rather than a fresh diagnosis.

**What not to re-run.** `roadmap/field-column-table-inlining-cost.md` measured
`intent_field_column_table` at 151 s for 116 rows unfiltered, with every one of its nine children
cheap, and attributed the cost to view inlining rather than to anything underneath it. That relation is
in the drain's statement, which makes the drain a live reader of it, but the drain reads it filtered by
graph and by three explicit type-field pairs, and the navigation item's control puts a filtered derived
relation of this family at 22 ms. So the static multiplicity ranking is the wrong place to start here:
what matters is whether the drain's *filters* reach the relation before its expansion or after, which
is the same question the leading hypothesis asks.

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

**Coordination.** If the navigation item ships its fix to `DeclarationFacts` first, re-measure before
doing anything here: the lever may transfer to `DiagnosticFacts` unchanged, and the two statements
should not grow two different spellings of one correction. Whichever lands second should leave the
shape stated in one place.

**Acceptance.** The drain's statement, against that populated capture, finishes with headroom inside
the interactive budget rather than merely inside the session one, and the plan's scan counts say why.
The number does not become a test: the tiers assert statement counts and plan shape, never wall clock,
so what is pinned in-tree is the scan-count shape via the existing statement-count tier, and the
timings live in this item's write-up and in the `reason` column of any registration it produces.

**Not in scope.** Raising the budget, which converts a diagnosed defect into a longer silence. Making
the drain asynchronous, which is `roadmap/diagnostics-drain-leaves-the-triggering-thread.md` and is
worth doing whatever this item finds. Splitting the drain's read per file, which would trade away the
one-snapshot consistency the batch exists to give and multiply the statement count.

