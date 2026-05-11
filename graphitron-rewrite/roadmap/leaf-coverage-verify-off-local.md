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

Target: `mvn verify` on a contributor machine never fails because of leaf-coverage drift, and default-contributor builds stop paying for trace emission they don't consume. CI owns freshness: `rewrite-build.yml` already runs the full reactor with traces enabled; we ferry the regenerated `inference-axis-coverage.adoc` from that run into `deploy-docs.yml` as a workflow artifact. The committed `.adoc` becomes a manually-refreshed snapshot for offline reading; the published doc-site copy is always derived from the latest successful trunk CI run.

Out of scope: changing the mention join itself (R107), changing what the report contains, auto-committing the regenerated report back to trunk, and CI gating on report drift.

## Implementation

1. **Drop the verify gate.** Remove the `verify-leaf-coverage-report` `<execution>` block from `graphitron-rewrite/roadmap-tool/pom.xml`. The execution is the only consumer of the `leaf-coverage --verify` subcommand path; delete the `--verify` flag handling in `no.sikt.graphitron.roadmap.Main` and `LeafCoverageReport` once nothing references it. (The plain `leaf-coverage` subcommand stays — it's how the artifact gets regenerated, in CI and ad-hoc.)

2. **Flip the leaf-coverage profile to opt-in.** In `graphitron-rewrite/pom.xml`, invert the `<activation>` on the `leaf-coverage` profile from "active unless `-Dleaf-coverage.skip` is set" to "active only when `-Pleaf-coverage` (or `-Dleaf-coverage` property) is passed". Default `mvn verify` no longer truncates `leaf-coverage.jsonl` in `process-test-resources`, no longer passes the `graphitron.classification.trace` system property into surefire/failsafe, and no longer accumulates trace records nobody reads. Update the profile's existing comment block to reflect the inverted contract.

3. **Regenerate in `rewrite-build.yml`, upload as artifact.** After the existing `mvn verify -Plocal-db --batch-mode` step (which now needs `-Pleaf-coverage` added so traces emit), add two steps:
   - `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'` — overwrites the in-workspace `.adoc`.
   - `actions/upload-artifact@v4` with `name: inference-axis-coverage` and `path: graphitron-rewrite/roadmap/inference-axis-coverage.adoc`.
   Run these steps only on `push` to `claude/graphitron-rewrite` (a `pull_request` build does not need to publish artifacts and the docs-deploy chain does not consume them). Guard with `if: github.event_name == 'push' && github.ref == 'refs/heads/claude/graphitron-rewrite'`.

4. **Chain `deploy-docs.yml` to the reactor run.** Replace the existing `on: push` trigger with `on: workflow_run: workflows: ['Rewrite reactor CI'], types: [completed], branches: [claude/graphitron-rewrite]`. Add a top-level `if: github.event.workflow_run.conclusion == 'success'` so a failed reactor blocks the deploy (which is the desired behavior — a broken build should not publish docs). In the `build` job, add an `actions/download-artifact@v4` step with `run-id: ${{ github.event.workflow_run.id }}` and `github-token: ${{ secrets.GITHUB_TOKEN }}`, then move the downloaded file into `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` before the AsciiDoctor render. Drop the `paths:` filter — `workflow_run` ignores path filters anyway, and the reactor already gates on what changed.

5. **Snapshot caveat in the report's own description.** Edit the `:description:` line at the top of `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` to drop the "regenerate with mvn" instruction (which encoded the now-removed verify expectation) and replace it with: "Snapshot in repo for offline reading; freshest copy renders on `graphitron.sikt.no` from the latest trunk CI run. Regenerate locally with `mvn -f graphitron-rewrite/pom.xml verify -Plocal-db -Pleaf-coverage && mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'`."

## Trade-offs accepted

- **`deploy-docs` no longer fires on `push`.** It waits for `Rewrite reactor CI` to finish. Today's ~30-second post-push deploy becomes a multi-minute post-push deploy (reactor time + docs render). This is fine: docs are read asynchronously, and chaining lets us inherit the build's freshness guarantee.
- **Roadmap-only edits trigger a reactor build, then a docs deploy.** Already true today (rewrite-build has no `paths` filter); explicit here.
- **The committed `.adoc` in `claude/graphitron-rewrite` drifts.** Stale on `github.com`, fresh on `graphitron.sikt.no`. The new `:description:` line tells readers which is canonical.
- **A `pull_request` build cannot publish a preview report.** That's acceptable; PR reviewers reading the doc site see whatever last shipped to trunk. If we ever want PR previews, they belong in a separate item.

## Tests

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on a tree with deliberately-stale `inference-axis-coverage.adoc` (e.g. delete a row) must succeed (proves the local gate is gone).
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` does not produce `**/target/leaf-coverage.jsonl` files (proves the profile flip).
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db -Pleaf-coverage` does produce them, and `roadmap-tool leaf-coverage graphitron-rewrite` regenerates a non-empty `.adoc` (proves the opt-in path still works).
- No new unit test: this is plumbing. The CI chain is verified end-to-end by the next push to trunk producing a fresh artifact and a deploy that picks it up.

## Roadmap entries

R132 lands on Done with file deletion (no changelog entry; routine cleanup). The doc-site behaviour change is a CI-only matter, not user-visible.
