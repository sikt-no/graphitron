---
id: R140
title: "Publish leaf-coverage report from CI once rewrite workflows are on main"
status: Backlog
bucket: cleanup
priority: 3
theme: structural-refactor
depends-on: [leaf-coverage-verify-off-local]
---

# Publish leaf-coverage report from CI once rewrite workflows are on main

R132 shipped the local-friction half of its plan (verify-gate drop, placeholder `inference-axis-coverage.adoc`) but deferred the CI publish chain. The deferred half is: regenerate the report on every successful reactor run, upload it as a build artifact, and have `deploy-docs.yml` download the artifact before the AsciiDoctor render so the live doc site reflects the latest trunk classification snapshot. R132's spec laid out the mechanism (steps 3–5 of its Implementation section); this item resurrects those steps once the structural prerequisite is met.

The prerequisite missed in R132's Spec→Ready review: `workflow_run` event listeners only fire when the listener workflow file lives on the repo's default branch. This repo's default branch is `main`, which currently carries only the legacy `maven-build.yml` and `maven-publish.yml` workflows — `rewrite-build.yml` and `deploy-docs.yml` live only on `claude/graphitron-rewrite`. With deploy-docs.yml absent from `main`, a `workflow_run` trigger on it never fires, breaking the publish chain entirely. The `on: push` trigger R132 reverted to does not have this restriction because push events fire workflows defined in the branch being pushed.

R140 is gated on whether the rewrite workflow files are present on `main`. There are two paths to that gate, and the implementer should pick one in the Spec body rather than now:

1. **Mirror the two workflow files to `main`.** A one-shot cherry-pick (or equivalent) of the current `rewrite-build.yml` and `deploy-docs.yml` onto `main`. Cheap; needs maintainer authorization because pushing to `main` is normally out of bounds for AI work (CLAUDE.md). The mirrored files would then drift if the rewrite versions evolve, so this is best paired with a single update-`main` step at R140 implementation time and a note that future workflow edits also have to land on `main`.
2. **Wait for the rewrite-to-main merge.** If/when graphitron-rewrite is promoted to the production trunk and `main` adopts these workflow files as part of that promotion, R140 implementation is unblocked without a separate `main` push.

Once the prerequisite is met, the implementation is mechanical and follows R132's steps 3–5 verbatim (with the actions/checkout `ref` pin added during R132 self-review).

## Implementation sketch

1. **Add the regen + upload steps to `rewrite-build.yml`** (lifted from R132 step 3): trunk-guarded `mvn exec:java` followed by `actions/upload-artifact@v4` with `name: inference-axis-coverage`, `path: graphitron-rewrite/roadmap/inference-axis-coverage.adoc`, `if-no-files-found: error`.

2. **Replace `deploy-docs.yml`'s `on: push` trigger with `on: workflow_run`** chained to `Rewrite reactor CI` on `claude/graphitron-rewrite`. Add the conditional checkout ref (`github.event.workflow_run.head_sha` for the `workflow_run` path; `github.ref` for `workflow_dispatch`) and the `actions/download-artifact@v4` step. Add `actions: read` to permissions so the download can read another workflow's artifacts.

3. **Restore the cross-reference HTML comments** at the top of both files describing the `name:`-string coupling (the only guardrail against silent disconnection on a workflow rename).

4. **Update the placeholder's prose** in `inference-axis-coverage.adoc` to drop the "publish chain deferred to R140" language and point at the live doc site again.

## Failure modes carried over from R132

The failure modes R132 enumerated (reactor renamed, artifact upload fails, download race, PR skip) all still apply once the chain is reassembled; the mitigations don't change.

## New failure mode introduced by the prerequisite

- **`main` carries a stale copy of the workflow files.** If R140 takes path (1), the `main` copies drift the moment a later item edits `rewrite-build.yml` or `deploy-docs.yml`. The `workflow_run` trigger keeps firing against whatever `main` has, so the listener can diverge silently from the source. Mitigation options for R140 to evaluate: a CI step that diff-checks the two copies, or a settled policy that the rewrite trunk's copies are the source and `main`'s copies are cherry-picks updated by maintainer convention.

## Out of scope

- Whether to promote graphitron-rewrite to `main` outright (that's a much larger structural decision).
- Re-introducing a local verify gate on the report. R132's framing argued against, and R140 inherits that posture.
