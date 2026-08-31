---
id: R893
title: "A decoding @nodeId instruction with no installed decode fails the build"
status: Backlog
bucket: validation
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# A decoding @nodeId instruction with no installed decode fails the build

A `@nodeId` directive is an instruction with two halves that live apart: the client sends opaque base64 node-id strings on the wire, and the generated code must decode them into key-column values before anything consumes them. When the consumer is a Java method parameter (a `@service` or `@condition` signature), R728's guard holds: `intent_node_id_decode_defect` compares the node key against the declared parameter type and fails the build on a mismatch. When the consumer is a SQL predicate (a filter input field or a filter argument lowering to an `IN` inside a correlated `EXISTS`), no guard exists at all: the defect view is scoped to `site = 'ARGUMENT'`, `carrier = 'NAMED_PARAMETER'`, and both of its verdicts are parameter-typing comparisons. A decode dropped on the SQL rail therefore compiles clean and fails per request, either a `ClassCastException` at the cast the emitter falls back to, or base64 strings silently compared against key columns. That is exactly the failure mode [issue 536](https://github.com/sikt-no/graphitron/issues/536) reports; the issue's minimized SDL does not reproduce it on the RC35 commit (the decode is emitted there), but the report is only constructible because nothing on this rail would fail the build if a shape did slip through, and one hand-found member of the class is live today (R884's `argMapping` descent to a `@nodeId` input field).

The store already carries both sides of the ledger. `intent_node_id_instruction` states every authored decode instruction, and `intent_node_id_decode` models the installed decodes with their destinations (local tuple, correlated `EXISTS`, and the Java-slot destinations); its own comment records that "nothing on the build path reads it yet", the only readers being two capture tests. The work is one membership rule joining the two: a decoding instruction with no decode row at any destination is an instruction the generator dropped, projected as a located build error, plausibly a fifth component on `StoreDetections` beside `NodeIdDecodeDefects`, joined to `intent_type_domain` the way `NodeIdDecodeDefects.inDomain` already scopes its verdicts. That makes the RC34 promise ("an instruction the generator cannot execute fails the build instead of being dropped") hold for the SQL rail the way it already holds for parameter slots, for every present and future lowering path at once. Part of the item is establishing whether R884's shape then rejects for free at the new rule, and making sure the rule stays membership-shaped (instruction with no decode anywhere) rather than trying to re-derive per-destination correctness the emitters own.
