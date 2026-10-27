---
id: R469
title: "Enable @defer/incremental delivery on the owned-connection path"
status: Backlog
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-07-10
last-updated: 2026-10-27
---

# Enable @defer/incremental delivery on the owned-connection path

R429's owned-connection path releases the pinned connection at operation completion, so a deferred fetcher running after the initial result would use a closed connection. The V0 stance is therefore that incremental delivery stays off: the owned factory never opts in, and `GraphitronConnectionInstrumentation.beginExecuteOperation` rejects an execution with incremental support enabled outright (pinned by `ConnectionLifecycleExecutionTest`). Enabling `@defer`/`@stream` under owned connections must own the connection-lifetime story: when release happens relative to deferred delivery, and how that composes with the tenant-keyed carrier's lazy per-key acquisition. Session identity itself needs no per-fetcher story: the `<sessionState>` mount runs once at pin time as committed session state, so a late fetcher on a still-pinned connection sees it; the open question is purely when `releaseAll` (which unmounts) may run. Named as a follow-on in R429's `@defer` section; this item is its tracking home now that the R429 spec is deleted.

## Under a mutation: deferred work starts after the last field settles

R1101 runs each top-level mutation field in one transaction on the pinned connection, settled when the next field begins or, for the last field, in the operation callback. A deferred fragment under a mutation field is not part of that field's completed value: graphql-java's serial strategy waits only for the non-deferred part (`beginFieldCompletion` fires on it), and each deferred field resolves later through its own `beginDeferredField` context. Left to graphql-java's scheduling, field N's deferred fragment can run while field N+1 executes, on the same pinned connection and so inside N+1's transaction: it reads N+1's uncommitted writes, and on PostgreSQL a failing deferred read aborts N+1's transaction, failing N+1 for an error in N's payload.

Graphitron writes only at the mutation root: `@mutation`, `@routine` writes and `@service` mutations are classified on `Mutation` only (`FieldBuilder.classifyRootField`), and everything below the root is a read (a nested `@service` is arbitrary Java, which graphitron does not model as a write). So deferred work under a mutation is reads, and field N's writes are all in its non-deferred part, committed before its value is final. That makes the direction:

* **Deferred fragments of a mutation operation start only after the last top-level field has settled.** Every write is committed by then, deferred reads run on the same pinned connection in autocommit and see committed state, and no deferred read ever runs inside a field transaction. The initial payload's report that a field succeeded stays true, because the write committed before any deferred read could fail.
* **A deferred fragment's error does not fail its mutation field.** The field settled before the fragment ran; R1101's rule that an error under a field's path fails the field covers the non-deferred part only, and the spec here says so.
* **Release moves after the last deferred fragment**, for queries and mutations alike, which is this item's connection-lifetime question; on a mutation it sits after both the last settle and the last deferred fragment.

The plan has to find the seam that holds deferred work back until the mutation's serial fields are done, since graphql-java schedules deferred fragments itself; and it has to say what the client sees meanwhile (the initial payload goes out as soon as its non-deferred part is complete, the incremental payloads follow once the mutation has settled). Considered and set aside: running deferred reads on a second, separately mounted autocommit connection (a second pin and mount per request, and a lifecycle with two connections where the direction above needs one), and rejecting `@defer` under mutations while allowing it on queries (simplest, and the fallback if the seam turns out not to exist).

