---
id: R132
title: "Move leaf-coverage report verification off local builds"
status: In Review
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

## Outcome

Steps 1, 2, and 6 of the Implementation plan below shipped under R132 — the local `mvn verify` gate is gone, the committed `inference-axis-coverage.adoc` is now a clearly-labeled placeholder, and the regen one-liner is future-correct for R133. The contributor-facing target ("`mvn verify` never fails because of leaf-coverage drift") holds. Steps 3, 4, and 5 — the CI regen, the artifact upload, and the `workflow_run`-chained deploy — are deferred to R140: `workflow_run` listeners only fire when the listener file lives on the repo's default branch, which here is `main`, and the rewrite workflow files do not yet live there. The Spec→Ready review missed this constraint; R140 carries the gap as its own item rather than smuggling a partial fix in.

Under R132 alone, the doc-site page for this report is the placeholder; the "live site reflects the latest successful trunk run" guarantee from the framing above does not yet hold. R140 restores it.

## Implementation

1. **Drop the verify gate.** Remove the `verify-leaf-coverage-report` `<execution>` block from `graphitron-rewrite/roadmap-tool/pom.xml`. Keep the `--verify` flag handling in `no.sikt.graphitron.roadmap.Main` and `LeafCoverageReport` — it remains callable as a CLI assertion primitive (e.g. for R107 self-tests), just unbound from any Maven phase.

2. **Replace the committed report with a placeholder.** Overwrite `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` with a static placeholder that renders to a complete (if data-free) doc-site page: title preserved, body explaining "this page is generated only in CI; the latest data renders on `graphitron.sikt.no`. To inspect locally, run …" followed by the regen one-liner. The placeholder lives in git so the AsciiDoctor render finds the file for local doc builds and PR-render checks; CI overwrites it on the runner before upload. Committing a placeholder rather than `.gitignore`ing the path keeps `mvn package -pl :graphitron-docs -am` working out-of-the-box and avoids a per-developer "missing file" failure mode.

3. **Regenerate in `rewrite-build.yml`, upload as artifact.** *(deferred to R140.)* After the existing `mvn verify -Plocal-db --batch-mode` step, add (guarded by `if: github.event_name == 'push' && github.ref == 'refs/heads/claude/graphitron-rewrite'`):
   - `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'` — overwrites the in-workspace `.adoc` with the regenerated report.
   - `actions/upload-artifact@v4` with `name: inference-axis-coverage`, `path: graphitron-rewrite/roadmap/inference-axis-coverage.adoc`, and `if-no-files-found: error`. A missing file at this point is a regen-failure bug; fail loud.

4. **Chain `deploy-docs.yml` to the reactor run.** *(deferred to R140.)* Replace the existing `on: push` trigger with `on: workflow_run: workflows: ['Rewrite reactor CI'], types: [completed], branches: [claude/graphitron-rewrite]`. Add a top-level `if: github.event.workflow_run.conclusion == 'success'` so a failed reactor blocks the deploy. In the `build` job, before the AsciiDoctor render, add `actions/download-artifact@v4` with `name: inference-axis-coverage`, `run-id: ${{ github.event.workflow_run.id }}`, `github-token: ${{ secrets.GITHUB_TOKEN }}`, and `path: graphitron-rewrite/roadmap/` so the download overwrites the committed placeholder in place. The `paths:` trigger filter drops (irrelevant under `workflow_run`).

5. **Document the workflow coupling.** *(deferred to R140.)* Add an HTML comment at the top of both `rewrite-build.yml` and `deploy-docs.yml` cross-referencing the other file by path and noting that the deploy chain matches on the reactor workflow's `name:` field. Renaming that field silently breaks the chain; the comment is the only guardrail.

6. **Update the placeholder one-liner.** The regen instruction inside the placeholder `.adoc` must include `-Pleaf-coverage` (R133's opt-in profile) once that item ships. Until R133 ships, the bare `mvn verify` is enough; ship the placeholder with the future-correct instruction and a parenthetical "after R133".

## Failure modes (R140)

The four failure modes the original spec enumerated all relate to the `workflow_run`-chained deploy and ride with R140; that item carries them.

## Tests

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on the placeholder tree succeeds; `mvn -pl :graphitron-docs -am package -DskipTests` renders the placeholder to `docs/target/generated-docs/roadmap/inference-axis-coverage.html`. Both smoke-tested on this branch.
- `roadmap-tool leaf-coverage graphitron-rewrite` overwrites the placeholder with a regenerated report (manual smoke; covers steps 1 and the regen one-liner from step 6 — no unit test).
- The `--verify` CLI primitive short-circuits with `Skipping verify` when no `target/leaf-coverage.jsonl` traces exist, and asserts drift when they do; both branches survive R132 because `Main` and `LeafCoverageReport` are untouched. End-to-end coverage of the assertion path moves with R140 (where it'll be the gate on the CI regen).

## Roadmap entries

R132 lands on Done with file deletion (no changelog entry; routine cleanup). Two follow-ups: R133 (Backlog) flips the `leaf-coverage` profile to opt-in; R140 (Backlog) restores the CI regen + publish chain once the rewrite workflows reach `main`.
