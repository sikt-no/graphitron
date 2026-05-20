---
id: R199
title: "Ready -> Spec workflow transition"
status: Backlog
bucket: cleanup
priority: 5
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Ready -> Spec workflow transition

The state machine does not let a `Ready` item return to `Spec` for re-review when implementation reveals the spec needs substantive redesign. R160 hit this in commit 00cf75b: the implementer had to hand-edit front-matter because the `status` subcommand rejected `Ready -> Spec`, and the commit message flagged "the workflow gap will be filed as its own Backlog item." This item is that filing.

Add `Ready -> Spec` to `ALLOWED_TRANSITIONS` in `Main.java`, the state diagram and a "reopening Ready" paragraph in `workflow.adoc`, the transition table in the `roadmap` SKILL, and a regression test in `RoadmapDateColumnTest`. The transition is unguarded: anyone (typically the implementer who discovered the issue) can flag a sign-off that no longer matches the work, and the existing `Spec -> Ready` reviewer-rule guard re-engages on the next round so a fresh independent sign-off is required before implementation resumes.
