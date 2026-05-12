---
id: R140
title: "Publish leaf-coverage report from CI"
status: In Review
bucket: cleanup
priority: 3
theme: structural-refactor
depends-on: [leaf-coverage-verify-off-local]
---

# Publish leaf-coverage report from CI

R132 shipped the local-friction half of its plan: the `verify-leaf-coverage-report` gate is gone and the committed `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` is a labelled placeholder. The CI publish chain that would replace the gate (regenerate the real report on every successful trunk run, render it into the doc site, deploy to GitHub Pages) is still missing. The live doc site currently shows the placeholder. R140 lands the publish chain so the site reflects the latest successful trunk build.

R132's original sketch chained two workflow files via `workflow_run`: `rewrite-build.yml` regenerates the report and uploads it as an artifact; `deploy-docs.yml` listens for the reactor's completion via `workflow_run`, downloads the artifact, and renders + deploys the site. The Spec→Ready review missed that `workflow_run` listeners only fire when the listener workflow file lives on the repo's default branch. This repo's default branch is `main`, and `main` only carries the legacy `maven-build.yml` and `maven-publish.yml` workflows. With `deploy-docs.yml` absent from `main`, a `workflow_run` trigger on it never fires.

R140 sidesteps that constraint by folding the deploy work into `rewrite-build.yml` as downstream jobs in the same workflow run. Push triggers use the workflow file from the branch being pushed, regardless of what `main` contains, so the default-branch constraint never applies. `deploy-docs.yml` is deleted; one workflow file owns the build → regenerate → render → deploy chain end-to-end.

The structural alternatives we considered and rejected:

- **Mirror `rewrite-build.yml` and `deploy-docs.yml` to `main`** so the `workflow_run` constraint clears. Cheapest path technically, but the `main` push needs maintainer authorization (CLAUDE.md scopes AI work to the rewrite branch), and the copies on `main` and `claude/graphitron-rewrite` will drift the moment anyone edits the rewrite copies. The mitigation (a CI diff check, or a written convention that workflow edits land on both branches) trades the original problem for a smaller-but-permanent one.
- **Wait for the rewrite-to-`main` promotion** so `main` picks up the workflow files naturally. Zero work, but unbounded timeline; the live site stays placeholder-only in the meantime.

## Design

One workflow, `rewrite-build.yml`, with four jobs:

1. `build` (existing). Runs `mvn verify -Plocal-db` against the Postgres service, exactly as today. After verify succeeds, an additional step regenerates the leaf-coverage report and uploads it as a workflow artifact. Both new steps are gated `if: github.event_name == 'push' && github.ref == 'refs/heads/claude/graphitron-rewrite'` so PRs and `main` pushes are unaffected. The regen reads the `target/leaf-coverage.jsonl` traces the verify run produced; running inside the same job keeps that workspace state implicit.

2. `docs-build` (new). Gated on the same push-to-trunk condition. `needs: build`. Sets up JDK 25, checks out the repo, downloads the `inference-axis-coverage` artifact into `graphitron-rewrite/roadmap/` (overwriting the placeholder), runs `mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am package -DskipTests`, uploads the result as a `actions/upload-pages-artifact@v3` Pages artifact. The docs build does not need Postgres — it only renders AsciiDoc — so the Postgres service runs only in the `build` job.

3. `docs-deploy` (new). `needs: docs-build`, same push-to-trunk gate. Carries `permissions: pages: write, id-token: write`, the `pages` concurrency group with `cancel-in-progress: false`, and the `github-pages` environment. Runs `actions/deploy-pages@v4`.

All three new jobs share the push-to-trunk `if:` condition. PRs run only `build`; nothing about the rewrite-branch deploy chain leaks into PR runs or `main` pushes.

`deploy-docs.yml` is deleted in the same commit that adds the jobs. `preview-docs.yml` (PR docs preview) is independent of the deploy chain and unchanged.

### Downsides this design accepts

- **The docs build and deploy run on every successful trunk push,** including pushes that touch no docs files. Today's `deploy-docs.yml` `paths:` filter narrows this to docs-relevant pushes. That filter cannot move to a job-level `if:` inside the build workflow without computing a "did docs change?" boolean via a third-party action; the spec accepts the extra CI minutes rather than add that machinery. The framing target from R132 ("the live site reflects the latest successful trunk run") is consistent with deploying on every successful trunk run.
- **A deploy-step flake marks the whole CI run red.** Today, GitHub Pages outages affect `deploy-docs.yml` independently of `rewrite-build.yml`. After R140, a flaky deploy job lands on the same workflow run as the build job's green checkmark. The build/deploy distinction is still readable in the per-job status, but the top-level run status mixes them. Acceptable; not worth adding `continue-on-error: true` (which would hide real deploy failures too).
- **`rewrite-build.yml` grows from one job to four,** mixing "did the code build" with "did the docs ship". Readability cost is real; the file goes from ~60 lines to roughly twice that. Mitigated by job naming and the push-to-trunk gates being on every deploy job (the build job is obviously the one that's not gated).

## Implementation

1. **Edit `.github/workflows/rewrite-build.yml`.**
   - Add two steps at the end of the existing `build` job (both gated `if: github.event_name == 'push' && github.ref == 'refs/heads/claude/graphitron-rewrite'`):
     - "Regenerate leaf-coverage report": `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q -Dexec.args='leaf-coverage graphitron-rewrite'`. Overwrites `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` in the workspace with the freshly-regenerated report. The Maven cache already holds roadmap-tool's dependencies from the `verify` step; the regen is a sub-second exec invocation.
     - "Upload leaf-coverage artifact": `actions/upload-artifact@v4` with `name: inference-axis-coverage`, `path: graphitron-rewrite/roadmap/inference-axis-coverage.adoc`, `if-no-files-found: error`. The error mode is intentional: a missing file at this point is a regen-failure bug, not a routine condition.
   - Add a `docs-build` job: `needs: build`, push-to-trunk `if:`, checkout + JDK 25 setup, `actions/download-artifact@v4` with `name: inference-axis-coverage` and `path: graphitron-rewrite/roadmap/` (the path is the directory, not the filename, so the download lands at the expected location and overwrites the committed placeholder), `mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am package -DskipTests`, `actions/upload-pages-artifact@v3` with `path: docs/target/generated-docs`.
   - Add a `docs-deploy` job: `needs: docs-build`, push-to-trunk `if:`, `permissions: pages: write, id-token: write`, `concurrency: { group: pages, cancel-in-progress: false }`, `environment: { name: github-pages, url: ${{ steps.deployment.outputs.page_url }} }`, single step `actions/deploy-pages@v4` with `id: deployment`.

2. **Delete `.github/workflows/deploy-docs.yml`.** Its content is fully absorbed into `rewrite-build.yml` (with the trigger conversion: `on: push` filtered by `paths:` becomes the per-job push-to-trunk `if:` without a paths filter, by design). The `workflow_dispatch` trigger today's `deploy-docs.yml` carries does not survive the consolidation. If a manual re-deploy is needed before R140 ships its replacement mechanism, the operator can re-run the most recent successful trunk workflow run via the GitHub Actions UI (which replays all jobs including deploy); that is sufficient for the cases `workflow_dispatch` previously served.

3. **Update `graphitron-rewrite/roadmap/inference-axis-coverage.adoc`.** Replace the "publish chain deferred to R140" paragraph with prose explaining that the placeholder in git is intentionally non-data and the rendered doc site shows the latest CI-regenerated report. Keep the regen one-liner so contributors can inspect the report locally. The placeholder still lives in git (so local doc builds and PR-render checks find a file at the expected path); CI overwrites it on the runner before the docs-render step.

## Tests

- **Smoke-test the regen step on a feature branch run.** Push a no-op commit to a `wip/` branch with the workflow changes; the per-job push-to-trunk gate keeps the new jobs from firing on that branch, so the verify is "build still passes and PRs are unaffected". Cannot reach the deploy path without pushing to trunk.
- **Trunk-push verification.** Once the workflow changes land on `claude/graphitron-rewrite`, the next trunk push exercises the full chain: `build` succeeds, `regen` overwrites the placeholder, `docs-build` consumes the artifact, `docs-deploy` publishes. Verification is "the live `graphitron.sikt.no/roadmap/inference-axis-coverage.html` page shows data, not the placeholder text". This is end-to-end and only observable post-merge; the spec accepts that no pre-merge test exercises the deploy path.
- **Job-isolation check.** Confirm via the Actions UI that PR runs against `claude/graphitron-rewrite` execute only the `build` job (no `docs-build`, no `docs-deploy`). Same check for pushes to `main` — only `build` runs.

## Failure modes

The R132 sketch carried four failure modes for the `workflow_run` chain. Path 3 retains the analogous ones and drops two that were specific to `workflow_run`:

- **Reactor renamed (kept, weaker).** The `name:` field on `rewrite-build.yml` is no longer load-bearing for the deploy chain (no other workflow listens for it). It still appears in branch protection rules and Actions UI lookups; renaming it is fine for the deploy chain but may break unrelated automation.
- **Artifact upload fails (kept).** `if-no-files-found: error` on the upload step ensures a missing report fails the `build` job; `docs-build` then never starts. Loud failure, no silent placeholder publish.
- **Pages deploy flake (mitigated by acceptance, not by config).** GitHub Pages outages mark the workflow run red. Re-run the failed jobs to recover.
- **PR skip (no longer a concern).** Under `workflow_run`, PRs that skipped the reactor would not deploy — fine, but the conditional was non-obvious. Under path 3 the push-to-trunk `if:` is explicit at the job level; PRs never reach the deploy jobs.

A new failure mode specific to path 3:

- **Docs build or deploy fails on a trunk push that should not have triggered a deploy.** Today's `paths:` filter narrows deploys to docs-relevant pushes; path 3 deploys on every successful trunk push. A docs-render bug introduced in a non-docs PR (e.g. an AsciiDoctor extension changes behaviour) now fails the deploy on the next trunk merge instead of the next docs-touching merge. Net effect: regression surfaces earlier, against the actual change rather than against a later unrelated docs commit. Acceptable.

## Follow-up to flag at promotion time

When `claude/graphitron-rewrite` is promoted to `main`, the `workflow_run` constraint disappears and path 1 (split files, `workflow_run` chain) becomes available again. The path 3 → path 1 migration is mechanical CI work — extract the deploy jobs into a new `deploy-docs.yml`, switch trigger to `workflow_run`, swap intra-workflow artifact passing for cross-workflow download with `run-id`, re-add the `paths:` filter. Whether to do that migration is a decision for the promotion-time reviewer; this spec does not queue work for it, but the option exists and the cost is small. Flagging it here so the option is visible rather than forgotten by inertia.

## Out of scope

- Re-introducing a local verify gate on the report. R132's framing argued against; R140 inherits that posture.
- Changing what the report contains, the mention-join classification (R107), or the `leaf-coverage` profile's default activation (R133).
- Auto-committing the regenerated report back to trunk.
- PR-preview rendering of the report (separate workflow, separate concern).
