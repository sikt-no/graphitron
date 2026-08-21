---
id: R787
title: "Rebase on trunk before the verification build, not at publish time"
status: Spec
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

1. **CLAUDE.md, "Git Workflow" section.** Reword the session flow to `sync → work + commit → rebase on trunk → verification build → push own branch → fast-forward trunk`, and state the rule positively: rebase onto trunk before the verification build so the build verifies the exact tree that gets pushed. Keep the existing escape hatch, restated as the exception: if trunk moves after the rebase (i.e. during the build), rebase again and re-verify before pushing. A rebase after the verification build always invalidates it; never push a tree the build did not cover. Four consult findings sharpen this step:
   - This *introduces* a build step into the canonical flow rather than reordering an existing one; today's flow names no build at all. Say so in the rewording.
   - Define "verification build" exactly once, in the "Building and testing" section where the existing "prefer it over targeted `-pl` builds for anything you intend to trust as verification" sentence already lives, and have the session flow (and the publish skill, step 2 below) reference that definition rather than paraphrase it. Three hand-maintained restatements of one obligation is the drift shape the development principles warn about; the `srp` skill's "`mvn install -Plocal-db` passes" precondition is already a second spelling.
   - Update the fallback command block under the flow (the literal sequence agents copy when the publish skill is unavailable) to the same order: fetch + rebase, then build, then the two pushes. Also reword the sentence advertising the publish skill's "trunk-divergence pre-check", which currently frames publish as where the rebase normally happens; under the new ordering it is the backstop for the rare mid-build race.
   - Put a `see .claude/web-environment.md` pointer next to the rebase step. The mandatory rebase makes the documented web-sandbox cascade (a sync that moves `init.sql` while the session database keeps its seed schema) a routine encounter inside the canonical flow, and the flow should point at the recovery instead of leaving the agent to diagnose a build failure that looks like its own diff.

2. **`.claude/skills/publish/SKILL.md`.** Two edits:
   - Add a caller expectation near the top of the procedure: the caller is expected to have rebased onto trunk before running its verification build, so arriving at publish diverged should be rare.
   - Reword step 2 (the divergence stop) to frame the situation and the recovery: trunk moved after your rebase, so your verification build no longer covers what you would push; rebase, re-run the verification build, then re-invoke the skill. The stop itself, and all hard rules (fast-forward-only trunk, no force-push), stay as they are.

3. **`roadmap/workflow.adoc`, "Publishing" section.** No change, but for a more careful reason than "metadata-only": not every bracketed transition is metadata-only (`In Progress → In Review` is made by a code-bearing session, and `In Review → Done` carries the build-passes precondition). The file is consistent because its before-transition sync already sits upstream of any build the session runs; `In Progress → In Review` is the one transition where the two orderings meet, and there the pre-flip rebase precedes the verification build exactly as the new rule requires.

4. **Other skills.** No change. The `roadmap` and `srp` skills already sync-first (fetch + rebase before acting), matching the new ordering. The `classified-corpus` skill is the one whose documented sequence is verify-then-publish with no rebase in between; it stays unchanged because its verify step is scoped tests rather than the full install (so a publish-time rebase costs a cheap re-run, not a full rebuild) and because the reworded CLAUDE.md rule governs its sessions anyway. Named here so the sweep is a completed audit rather than a blanket claim.

## Notes and non-goals

- The web-sandbox caveat in `.claude/web-environment.md` (a mid-session rebase that moves `init.sql` reintroduces the jOOQ-catalog cascade because the session database keeps its seed schema) is unaffected by the ordering and stays as documented. The new ordering surfaces that failure in the mandatory verification build instead of in a surprise rebuild after publish, which is an improvement, but this item does not promise the rebase is free and does not touch that document.
- No change to what counts as verification (the full `mvn install -Plocal-db` remains the trusted check) and no change to trunk rules (fast-forward only, never force-pushed).
- No tooling or enforcement is added by this item; it is a workflow-ordering statement in the documents agents read. The "never push a tree the build did not cover" rule therefore lands without an enforcer, and nothing collects the signal that would tell us prose is not enough, so the deferral is filed rather than conditional: R788 (`publish-build-coverage-guard`) holds the mechanical check (the verification build records the `HEAD` it covered in a gitignored marker; publish compares and stops on mismatch). Publish's existing step 2 cannot substitute: it checks trunk divergence, and "did the build cover `HEAD`" is a different predicate that stays uncovered when a commit lands after the build.

## Acceptance criteria

- CLAUDE.md's session flow shows the rebase before the verification build and states that a post-build rebase requires re-verification before push.
- "Verification build" is defined once, in CLAUDE.md's "Building and testing" section; the session flow and the publish skill reference the definition instead of restating it.
- CLAUDE.md's fallback command block encodes the new order (fetch + rebase, build, pushes), and the sentence describing the publish skill frames its divergence check as the mid-build-race backstop.
- The rebase step in the flow points at `.claude/web-environment.md` for the web-sandbox `init.sql` cascade.
- The publish skill states the caller expectation and frames its divergence stop as the exception path with the rebase → re-verify → re-invoke recovery.
- `roadmap/workflow.adoc` and the `roadmap`/`srp`/`classified-corpus` skills are confirmed consistent with the ordering per plan steps 3 and 4 (expected: no edits needed).
- R788 exists as the filed enforcer follow-up.
