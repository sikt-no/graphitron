---
id: R773
title: "The LSP's store reads answer inside a time budget, or fail"
status: Backlog
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The LSP's store reads answer inside a time budget, or fail

The language server answers every question an editor asks it by querying the fact store, and no
query it issues has a time limit. There is no query timeout anywhere in the reactor: no jOOQ
`Settings.queryTimeout`, no `Statement.setQueryTimeout`, no H2 `SET QUERY_TIMEOUT`. The only time
bounds the store has are *lock* budgets, which answer a different question (how long a writer waits
for a row another writer holds): `GraphitronModelStore.FILE_LOCK_MILLIS` on the connection URL, and
`FactCapture.ANCHOR_LOCK_MILLIS` narrowing it around the anchor upsert. A read that runs long runs
until it finishes, however long that is.

Two properties of the read path turn one slow query into a server that has stopped responding.
`StoreReader.read` is `synchronized` over a single connection, deliberately ("Reads serialize... the
honest cost of the single connection"), so a query that is still running is head-of-line blocking
every hover, completion and diagnostic queued behind it, not only its own request. And no handler in
`GraphitronTextDocumentService` can abandon the wait: all five are a bare
`CompletableFuture.supplyAsync`, with no deadline, and `CancelChecker` appears nowhere in the module,
so an editor's `$/cancelRequest` does not reach the work it is trying to cancel.

This is not a hypothetical shape. The `intent_class_assignable` relation took seventeen seconds on a
census holding no duplicates at all and did not terminate at all when one class name appeared under
two classpath entries, and `intent_authored_field_claim` carried the same defect under the same
`UNION ALL`. Both were found by hand and fixed by deletion and by deduplication. What the store
lacked in both cases, and still lacks, is any mechanism that would have turned an unbounded query
into a bounded failure: the discipline we do have is statement *counting* (the `*StatementCountTest`
tier, explicitly "an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail
for being slow"), which pins how many round trips a feature costs and says nothing about what any one
of them may spend.
