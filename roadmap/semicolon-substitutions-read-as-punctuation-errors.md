---
id: R844
title: "Em-dash substitutions left a space-semicolon-space that reads as a typo"
status: Backlog
bucket: cleanup
priority: 8
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# Em-dash substitutions left a space-semicolon-space that reads as a typo

The house style bans em dashes in authored prose and asks for a comma, semicolon, colon, or a
restructured sentence instead. In practice the em dashes were often swapped one-for-one for a
semicolon, keeping the spaces around it: `*Derived source table* ; built from...`,
`Validation error ; build fails`, `Nothing yet ; rooted-at-parent NodeId reference`. A semicolon
with a space before it is not a punctuation mark English has, so it reads as a typo rather than as
the pause the em dash was carrying. About 75 sites across `docs/architecture` and `docs/manual`,
concentrated in table cells where the substitute is standing in for "which means" or simply for a
full stop.

The fix is per-site and needs a reading, not a regex: some want a colon, some a full stop and a new
sentence, and a few want the clause folded into the one before it. What makes it worth an item
rather than opportunistic cleanup is that partial fixing is worse than none, since a page with both
forms reads as inconsistent rather than as a page mid-repair.

Worth deciding at Spec: whether the style rule itself should say what to substitute, so the next
sweep of em dashes does not reproduce the same shape. The rule currently names the acceptable marks
without warning that a spaced semicolon is not one of them.

