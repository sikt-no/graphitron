---
id: R469
title: "Enable @defer/incremental delivery on the owned-connection path"
status: Backlog
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Enable @defer/incremental delivery on the owned-connection path

R429's owned-connection path releases the pinned connection at operation completion, so a deferred fetcher running after the initial result would use a closed connection. The V0 stance is therefore that incremental delivery stays off: the owned factory never opts in, and `GraphitronConnectionInstrumentation.beginExecuteOperation` rejects an execution with incremental support enabled outright (pinned by `ConnectionLifecycleExecutionTest`). Enabling `@defer`/`@stream` under owned connections must own the connection-lifetime story: when release happens relative to deferred delivery, how session identity stays mounted (or remounts) for late fetchers, and how that composes with the per-settle re-fire fallback and the tenant-keyed carrier. Named as a follow-on in R429's `@defer` section; this item is its tracking home now that the R429 spec is deleted.
