---
id: R132
title: "Move leaf-coverage report verification off local builds"
status: Spec
bucket: cleanup
priority: 3
theme: structural-refactor
depends-on: []
---

# Move leaf-coverage report verification off local builds

The `verify-leaf-coverage-report` execution in `graphitron-rewrite/roadmap-tool/pom.xml` fires on every default `mvn verify` (the `leaf-coverage` profile in the parent pom is active unless `-Dleaf-coverage.skip` is set) and exits 1 whenever `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` differs from a freshly-regenerated report. Because the report mention-joins every sealed leaf simple-name against every roadmap `*.md` body (R107), drift is the normal state — any roadmap edit that names a leaf trips the gate. A contributor running `mvn install -Plocal-db` to test an unrelated change is then told to rerun a `roadmap-tool leaf-coverage` invocation and re-commit. The leaf-coverage report is a generated artifact whose value is "the doc site shows current data"; it does not encode a correctness invariant the local build should defend. Today's verify gate forces every contributor to own freshness of a publishing artifact they did not touch.

Target: `mvn verify` on a contributor machine never fails because of leaf-coverage drift. The committed `inference-axis-coverage.adoc` stops being data and becomes an explicit placeholder pointing at the live doc site; CI regenerates the real artifact during the existing `rewrite-build.yml` reactor run and `deploy-docs.yml` picks it up via a workflow-run artifact download. There is one canonical source for the data (the live site rendered from a CI-only artifact); the in-repo file is openly labeled non-data.

The single CI-derived guarantee being downgraded here: the verify gate today asserts the classifier-derived mention join matches the committed snapshot at build time. After R132, the assertion is replaced by "the live site reflects the latest successful trunk run". This is acceptable because the report is documentation, not a generator input — no generated code, schema, or runtime behavior reads from it. If a future item makes the report load-bearing for the generator, the gate should come back at that point.

Out of scope: changing the mention join itself (R107), changing what the report contains, the `leaf-coverage` profile's default activation (R133), auto-committing the regenerated report back to trunk, and PR-preview rendering.

## Implementation

1. **Drop the verify gate.** Remove the `verify-leaf-coverage-report` `<execution>` block from `graphitron-rewrite/roadmap-tool/pom.xml`. Keep the `--verify` flag handling in `no.sikt.graphitron.roadmap.Main` and `LeafCoverageReport` — it remains callable as a CLI assertion primitive (e.g. for R107 self-tests), just unbound from any Maven phase.

2. **Replace the committed report with a placeholder.** Overwrite `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` with a static placeholder that renders to a complete (if data-free) doc-site page: title preserved, body explaining "this page is generated only in CI; the latest data renders on `graphitron.sikt.no`. To inspect locally, run …" followed by the regen one-liner. The placeholder lives in git so the AsciiDoctor render finds the file for local doc builds and PR-render checks; CI overwrites it on the runner before upload. Committing a placeholder rather than `.gitignore`ing the path keeps `mvn package -pl :graphitron-docs -am` working out-of-the-box and avoids a per-developer "missing file" failure mode.

3. **Regenerate in `rewrite-build.yml`, upload as artifact.** After the existing `mvn verify -Plocal-db --batch-mode` step, add (guarded by `if: github.event_name == 'push' && github.ref == 'refs/heads/claude/graphitron-rewrite'`):
   - `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'` — overwrites the in-workspace `.adoc` with the regenerated report.
   - `actions/upload-artifact@v4` with `name: inference-axis-coverage`, `path: graphitron-rewrite/roadmap/inference-axis-coverage.adoc`, and `if-no-files-found: error`. A missing file at this point is a regen-failure bug; fail loud.

4. **Chain `deploy-docs.yml` to the reactor run.** Replace the existing `on: push` trigger with `on: workflow_run: workflows: ['Rewrite reactor CI'], types: [completed], branches: [claude/graphitron-rewrite]`. Add a top-level `if: github.event.workflow_run.conclusion == 'success'` so a failed reactor blocks the deploy. In the `build` job, before the AsciiDoctor render, add `actions/download-artifact@v4` with `name: inference-axis-coverage`, `run-id: ${{ github.event.workflow_run.id }}`, `github-token: ${{ secrets.GITHUB_TOKEN }}`, and `path: graphitron-rewrite/roadmap/` so the download overwrites the committed placeholder in place. The `paths:` trigger filter drops (irrelevant under `workflow_run`).

5. **Document the workflow coupling.** Add an HTML comment at the top of both `rewrite-build.yml` and `deploy-docs.yml` cross-referencing the other file by path and noting that the deploy chain matches on the reactor workflow's `name:` field. Renaming that field silently breaks the chain; the comment is the only guardrail.

6. **Update the placeholder one-liner.** The regen instruction inside the placeholder `.adoc` must include `-Pleaf-coverage` (R133's opt-in profile) once that item ships. Until R133 ships, the bare `mvn verify` is enough; ship the placeholder with the future-correct instruction and a parenthetical "after R133".

## Failure modes

- **Reactor workflow renamed.** `workflow_run` matches against the reactor's `name:` field as a string. A rename without updating the deploy trigger silently disconnects the chain (deploys stop, no error surfaces). Mitigation: the cross-reference comments in step 5. Worth re-checking on any workflow-name edit.
- **Artifact upload fails on the reactor.** With `if-no-files-found: error` on upload, the reactor step fails loud and `workflow_run.conclusion != 'success'`, so the deploy is correctly suppressed.
- **`download-artifact` finds nothing.** Can happen if `workflow_run.id` race conditions hit (rare; documented edge case in GitHub Actions). Default `actions/download-artifact@v4` behavior is to fail; keep that default.
- **PR builds skip the regen step.** Step 3's `if:` guard means a PR introducing a `roadmap-tool leaf-coverage` failure surfaces only on trunk merge, not pre-merge. This is a mild regression versus the current verify gate. Accepted because the failure mode is `roadmap-tool` bugs, not contributor edits to the report; if `roadmap-tool` tests pass in CI (they always run) the regen succeeds. If this becomes a real footgun, add a smoke step that runs the regen on PRs without uploading.

## Tests

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on a tree with the placeholder `.adoc` (i.e. the freshly-modified file) succeeds; docs render produces the placeholder page.
- `roadmap-tool leaf-coverage graphitron-rewrite --verify` against the placeholder fails (proves the assertion primitive still works, even though no Maven binding calls it).
- `roadmap-tool leaf-coverage graphitron-rewrite` overwrites the placeholder with a regenerated report (manual smoke; no unit test).
- The CI chain is verified end-to-end by the next push to trunk producing a fresh artifact and a deploy that picks it up. No reactor unit test added.

## Roadmap entries

R132 lands on Done with file deletion (no changelog entry; routine cleanup). R133 (separately filed, Backlog) is the follow-up that flips the `leaf-coverage` profile to opt-in.
