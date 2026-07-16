---
id: R498
title: "Restore or repoint the missing getting-started quieting-warnings doc referenced by the RAG dev-warm hint"
status: Backlog
bucket: docs
priority: 6
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Restore or repoint the missing getting-started quieting-warnings doc referenced by the RAG dev-warm hint

`RagLogQuieting.incubatorHint` emits a runtime, user-facing hint string telling users to see 'getting-started, "Quieting startup warnings"', but no `getting-started.adoc` and no such section exist under `docs/`. The R483 audit removed the stale javadoc pointer to it, but the emitted message string still points at a nonexistent doc, so a user who follows the hint lands nowhere. Because it is a user-facing message string rather than a comment, the comment-drift tools do not apply; the fix is either to author the getting-started "Quieting startup warnings" section or to repoint the hint at an existing doc, a doc/behavior change that warrants its own item.

Surfaced by the R483 javadoc drift audit.
