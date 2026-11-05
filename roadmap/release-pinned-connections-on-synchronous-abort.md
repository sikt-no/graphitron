---
id: R1158
title: "Release pinned connections when execution aborts synchronously"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Release pinned connections when execution aborts synchronously

## Goal

A connection the owned-connection path pinned is unmounted and returned to the pool however the operation ends, including when graphql-java aborts it synchronously. Today release happens in one place: the completion callback of the context `GraphitronConnectionInstrumentation.beginExecuteOperation` returns (settle the last mutation field, then `TenantConnections.releaseAll()`). graphql-java invokes that callback only when the execution strategy's future completes. An exception thrown synchronously out of the strategy's dispatch skips it: `Execution.executeOperation` catches only `NonNullableFieldWasNullException` around `strategy.execute(...)`, and `GraphQL` turns an escaping `AbortExecutionException` into a result through `handleAbortException`, which calls `instrumentExecutionResult` but never completes the operation context. A connection pinned by an earlier field stays mounted with the caller's identity and is never returned. Nothing reaches another borrower, but every such request permanently takes one connection out of the pool, which a consumer sees as pool exhaustion. When this lands, a cancelled request releases what it pinned wherever the cancel was noticed.

## Evidence

A spike against the generated single-tenant runtime (sakila example, a counting fake `DataSource`, graphql-java 25.0), running the query `{ a: films { title } b: films { title } }` with `ExecutionInput.cancel()` called at different points:

[cols="2,1,1,1"]
|===
| Cancel point | Borrowed | Returned | Unmounted

| none (control) | 1 | 1 | 1
| before `execute` | 0 | 0 | 0
| `beginFieldFetching` of `b` (inside `fetchField`, after its cancel check; noticed in `completeField`, inside the `try` that turns it into a failed future) | 1 | 1 | 1
| `beginFieldExecution` of `b` (noticed at the top of `fetchField`, outside that `try`) | 1 | 0 | 0
|===

The per-field check in `ExecutionStrategy.getAsyncFieldValueInfo` is a synchronous point too. Any other exception escaping the strategy's synchronous dispatch takes the same path; cancellation is the one a consumer triggers deliberately, for example by wiring a client disconnect to `cancel()`.

## Context

Found by the Spec → Ready review of R1153, whose in-flight cancel case relied on this release and would have hung on a pool of one. R1153 names this as a gap and, when this item lands, owes a case cancelling at `beginFieldExecution` of the second root field. A likely shape for the fix: `instrumentExecutionResult` also calls `releaseAll()`, which is idempotent, since `handleAbortException` reaches it on the abort path. The multi-tenant carrier releases from the same callback and needs the same check.

