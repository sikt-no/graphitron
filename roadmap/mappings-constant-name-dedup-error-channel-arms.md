---
id: R496
title: "Pin MappingsConstantNameDedup.groupKey javadoc to the current three ErrorChannel arms"
status: Backlog
bucket: docs
priority: 6
theme: error-channel
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Pin MappingsConstantNameDedup.groupKey javadoc to the current three ErrorChannel arms

`MappingsConstantNameDedup.groupKey` javadoc (and its "the two namespaces never collide" analysis) describes only two `ErrorChannel` arms, `PayloadClass` and `LocalContext`, but the switch in `groupKey`/`renameChannel` now handles a third arm, `ErrorChannel.Mapped`, which also groups by `mappingsConstantName`. The collision analysis predates the third arm and understates the arm set, so a reader trusts a two-namespace guarantee the code no longer provides. Rewriting the prose to cover three arms would be fresh unpinned prose (the hazard the audit avoids); the durable fix is to pin the arm set structurally, for example an exhaustive switch or a test that fails when an `ErrorChannel` arm carrying a `mappingsConstantName` is added without updating the dedup grouping.

Surfaced by the R483 javadoc drift audit.
