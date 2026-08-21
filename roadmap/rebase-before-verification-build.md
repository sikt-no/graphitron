---
id: R787
title: "Rebase on trunk before the verification build, not at publish time"
status: Backlog
bucket: workflow
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Rebase on trunk before the verification build, not at publish time

## Problem

Agent sessions work trunk-based against `claude/graphitron-rewrite`, and many sessions land commits concurrently, so trunk usually moves while a session works. The documented session flow (CLAUDE.md, "Git Workflow") places the trunk sync at the ends of the session: "sync → work + commit → push own branch → fast-forward trunk", with "if trunk moved during work, rebase and repeat". The publish skill enforces this at push time: its step 2 detects that trunk has commits not reachable from `HEAD`, tells the agent to rebase, and stops.

That ordering makes rebuild churn structural. An agent finishes its work, runs the verification build (the full `mvn install -Plocal-db`), invokes publish, and only then discovers trunk moved. After the rebase, the verification build no longer covers the tree that would be pushed, so the agent must rebuild before pushing. Every trunk movement during a session costs a second full build, and the agents are behaving correctly when they pay it: a post-build rebase always invalidates the verification.

The fix is an ordering change, not a new mechanism: pick up upstream changes *before* the verification build. Rebase onto trunk, then build, then publish. The build then verifies the exact tree that gets pushed, and publish-time divergence shrinks from the routine case to the rare race where trunk moved during the build itself.

## Plan

Documentation and skill edits only; no code changes.

1. **CLAUDE.md, "Git Workflow" section.** Reword the session flow to `sync → work + commit → rebase on trunk → verification build → push own branch → fast-forward trunk`, and state the rule positively: rebase onto trunk before the verification build so the build verifies the exact tree that gets pushed. Keep the existing escape hatch, restated as the exception: if trunk moves after the rebase (i.e. during the build), rebase again and re-verify before pushing. A rebase after the verification build always invalidates it; never push a tree the build did not cover.

2. **`.claude/skills/publish/SKILL.md`.** Two edits:
   - Add a caller expectation near the top of the procedure: the caller is expected to have rebased onto trunk before running its verification build, so arriving at publish diverged should be rare.
   - Reword step 2 (the divergence stop) to frame the situation and the recovery: trunk moved after your rebase, so your verification build no longer covers what you would push; rebase, re-run the verification build, then re-invoke the skill. The stop itself, and all hard rules (fast-forward-only trunk, no force-push), stay as they are.

3. **`roadmap/workflow.adoc`, "Publishing" section.** No change. It already brackets every transition with a sync *before* the transition, which is the desired ordering, and its commits are roadmap-metadata-only, so no build is at stake.

4. **Other skills.** No change. The `roadmap` and `srp` skills already sync-first (fetch + rebase before acting), matching the new ordering.

## Notes and non-goals

- The web-sandbox caveat in `.claude/web-environment.md` (a mid-session rebase that moves `init.sql` reintroduces the jOOQ-catalog cascade because the session database keeps its seed schema) is unaffected by the ordering and stays as documented. The new ordering surfaces that failure in the mandatory verification build instead of in a surprise rebuild after publish, which is an improvement, but this item does not promise the rebase is free and does not touch that document.
- No change to what counts as verification (the full `mvn install -Plocal-db` remains the trusted check) and no change to trunk rules (fast-forward only, never force-pushed).
- No tooling or enforcement is added; this is a workflow-ordering statement in the documents agents read. If publish-time divergence remains common after this lands, adding a mechanical check would be a follow-up item.

## Acceptance criteria

- CLAUDE.md's session flow shows the rebase before the verification build and states that a post-build rebase requires re-verification before push.
- The publish skill states the caller expectation and frames its divergence stop as the exception path with the rebase → re-verify → re-invoke recovery.
- `roadmap/workflow.adoc` and the `roadmap`/`srp` skills are confirmed consistent with the ordering (expected: no edits needed).
