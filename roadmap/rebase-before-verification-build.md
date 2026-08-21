---
id: R787
title: "Rebase on trunk before the verification build, not at publish time"
status: In Review
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

Documentation and skill edits only; no code changes. All four steps shipped in one commit; per-step notes below.

Revision during In Review, from the first live run of the new flow: the shipped wording made a post-build rebase invalidate the verification build unconditionally, which forced a full rebuild even when the mid-build trunk movement was another item's roadmap metadata that plainly could not interact with the change being pushed. That absolutism was not the intent. The rule is now a coverage judgment, stated once in CLAUDE.md "Building and testing": every commit already on trunk passed its own verification build, so a rebase that brings in only plainly non-interacting changes (disjoint files, no shared build surface) carries the existing green build forward, with a cheap targeted check where one exists (a roadmap README regenerate after concurrent roadmap edits); incoming code, build configuration, or anything the change reads, or any doubt, still means a full rebuild. The Git Workflow exception path and the publish skill's step 2 recovery reference that judgment instead of demanding an unconditional rebuild.

One deviation from the plan text, flagged at the Ready sign-off: the "prefer it over targeted `-pl` builds" sentence lived at the end of "Common commands", not in "Building and testing". The definition landed at the top of "Building and testing" as planned (folded into the existing full-pipeline sentence, absorbing the `-pl` preference and the never-push-uncovered rule), and the "Common commands" sentence now points at that definition instead of restating it.

1. **CLAUDE.md, "Git Workflow" section.** Shipped, with all four consult findings honored: the flow reads `sync → work + commit → rebase on trunk → verification build → push own branch → fast-forward trunk` and says it names a build step the old flow left implicit; the rule is stated positively and references the definition instead of restating it; the exception path (trunk moves during the build: rebase again, re-verify, never push a tree the build did not cover) replaces the old escape hatch; the fallback command block encodes fetch + rebase, then the build, then the two pushes; the publish-skill sentence frames the divergence pre-check as the mid-build-race backstop; the `.claude/web-environment.md` pointer sits beside the rebase step.

2. **`.claude/skills/publish/SKILL.md`.** Shipped: caller expectation added above step 1 of the procedure; step 2 reworded to name the situation (trunk moved after your rebase, the build no longer covers what you would push) and the recovery (rebase, re-run the verification build, re-invoke the skill). The stop and all hard rules unchanged.

3. **`roadmap/workflow.adoc`, "Publishing" section.** Confirmed, no change, but for a more careful reason than "metadata-only": not every bracketed transition is metadata-only (`In Progress → In Review` is made by a code-bearing session, and `In Review → Done` carries the build-passes precondition). The file is consistent because its before-transition sync already sits upstream of any build the session runs; `In Progress → In Review` is the one transition where the two orderings meet, and there the pre-flip rebase precedes the verification build exactly as the new rule requires.

4. **Other skills.** Confirmed, no change. The `roadmap` and `srp` skills already sync-first (fetch + rebase before acting), matching the new ordering. The `classified-corpus` skill is the one whose documented sequence is verify-then-publish with no rebase in between; it stays unchanged because its verify step is scoped tests rather than the full install (so a publish-time rebase costs a cheap re-run, not a full rebuild) and because the reworded CLAUDE.md rule governs its sessions anyway. Named here so the sweep is a completed audit rather than a blanket claim.

## Notes and non-goals

- The web-sandbox caveat in `.claude/web-environment.md` (a mid-session rebase that moves `init.sql` reintroduces the jOOQ-catalog cascade because the session database keeps its seed schema) is unaffected by the ordering and stays as documented. The new ordering surfaces that failure in the mandatory verification build instead of in a surprise rebuild after publish, which is an improvement, but this item does not promise the rebase is free and does not touch that document.
- No change to what counts as verification (the full `mvn install -Plocal-db` remains the trusted check) and no change to trunk rules (fast-forward only, never force-pushed).
- No tooling or enforcement is added by this item; it is a workflow-ordering statement in the documents agents read. The "never push a tree the build did not cover" rule therefore lands without an enforcer, and nothing collects the signal that would tell us prose is not enough, so the deferral is filed rather than conditional: R788 (`publish-build-coverage-guard`) holds the mechanical check (the verification build records the `HEAD` it covered in a gitignored marker; publish compares and stops on mismatch). Publish's existing step 2 cannot substitute: it checks trunk divergence, and "did the build cover `HEAD`" is a different predicate that stays uncovered when a commit lands after the build.

## Acceptance criteria

- CLAUDE.md's session flow shows the rebase before the verification build and routes a post-build rebase through the coverage judgment (full rebuild by default, carried-forward coverage only for plainly non-interacting incoming changes) before push.
- The coverage judgment is defined once, in "Building and testing", and both the session-flow exception path and the publish skill reference it.
- "Verification build" is defined once, in CLAUDE.md's "Building and testing" section; the session flow and the publish skill reference the definition instead of restating it.
- CLAUDE.md's fallback command block encodes the new order (fetch + rebase, build, pushes), and the sentence describing the publish skill frames its divergence check as the mid-build-race backstop.
- The rebase step in the flow points at `.claude/web-environment.md` for the web-sandbox `init.sql` cascade.
- The publish skill states the caller expectation and frames its divergence stop as the exception path with the rebase → re-verify → re-invoke recovery.
- `roadmap/workflow.adoc` and the `roadmap`/`srp`/`classified-corpus` skills are confirmed consistent with the ordering per plan steps 3 and 4 (expected: no edits needed).
- R788 exists as the filed enforcer follow-up.
