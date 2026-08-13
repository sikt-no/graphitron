---
id: R469
title: "Enable @defer/incremental delivery on the owned-connection path"
status: Backlog
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-07-10
last-updated: 2026-08-13
---

# Enable @defer/incremental delivery on the owned-connection path

R429's owned-connection path releases the pinned connection at operation completion, so a deferred fetcher running after the initial result would use a closed connection. The V0 stance is therefore that incremental delivery stays off: the owned factory never opts in, and `GraphitronConnectionInstrumentation.beginExecuteOperation` rejects an execution with incremental support enabled outright (pinned by `ConnectionLifecycleExecutionTest`). Enabling `@defer`/`@stream` under owned connections must own the connection-lifetime story: when release happens relative to deferred delivery, and how that composes with the tenant-keyed carrier's lazy per-key acquisition. Session identity itself needs no per-fetcher story: the `<sessionState>` mount runs once at pin time as committed session state, so a late fetcher on a still-pinned connection sees it; the open question is purely when `releaseAll` (which unmounts) may run. Named as a follow-on in R429's `@defer` section; this item is its tracking home now that the R429 spec is deleted.
