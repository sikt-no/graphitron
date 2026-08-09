---
id: R616
title: "Landed-note SHA citations die when a branch is rebased before its gate"
status: Backlog
bucket: process
priority: 1
theme: tooling
depends-on: []
created: 2026-08-09
last-updated: 2026-08-09
---

# Landed-note SHA citations die when a branch is rebased before its gate

Collapsing an `Implementation` section to "shipped at `<sha>`" notes is a gate obligation, but the
SHAs an implementer has in hand at that moment are their pre-rebase ones, and trunk-based development
guarantees the rebase. The grammar item's collapsed section cited `71f27d0`, `c97a8de` and `f4dbbe3`;
none resolve, because the branch was rebased when trunk moved and the commits became `b48b0f8`,
`7eb474f` and `dd77f66`. The citation is worthless to a later reader in exactly the case it exists
for. This is generic to the workflow rather than to one item: any item that writes its own landing
SHAs before its final trunk sync records numbers that will not survive.

Cheapest fix is probably guidance rather than machinery, since the spec file is deleted at the gate
anyway and the durable record is `roadmap/changelog.md`: say in `roadmap/workflow.adoc` that landing
SHAs are the *reviewer's* to write at the Done gate, from the post-sync history, and that a collapsed
`Implementation` section should name commits by subject line rather than by SHA. A mechanical check is
possible (`git cat-file -e` every `[0-9a-f]{7,}` in `roadmap/*.md`) but would false-positive on the
changelog's own historical SHAs from before any repository rewrite, so weigh it against the guidance
option rather than assuming the gate.

Filed at the grammar item's In Review -> Done gate, where the reviewer wrote the correct SHAs into the
changelog by hand.
