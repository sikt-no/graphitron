---
id: R793
title: "The diagnostics drain overruns its 30 s session budget on a real workspace"
status: Backlog
bucket: bug
priority: 3
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

