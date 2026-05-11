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

Target: `mvn verify` on a contributor machine never fails because of leaf-coverage drift. CI owns freshness: the docs-deploy workflow regenerates `inference-axis-coverage.adoc` from a full reactor run with the `leaf-coverage` profile active, and renders the fresh copy into the GitHub Pages bundle. The committed file becomes a snapshot — possibly stale on `github.com`, always fresh on `graphitron.sikt.no`.

Out of scope: changing the mention join itself (R107), changing what the report contains, auto-committing the regenerated report back to trunk, and CI gating on report drift.

## Implementation

1. **Drop the verify gate.** Remove the `verify-leaf-coverage-report` `<execution>` block from `graphitron-rewrite/roadmap-tool/pom.xml`. The execution is the only consumer of the `leaf-coverage --verify` subcommand path; the subcommand itself stays so it can be invoked ad-hoc, but no Maven binding references it.

2. **Regenerate inside docs-deploy.** Restructure `.github/workflows/deploy-docs.yml` so the `build` job runs the full rewrite reactor (with the leaf-coverage profile active by default) before rendering docs. New shape:
   - `mvn -f graphitron-rewrite/pom.xml verify -Plocal-db --batch-mode -DskipITs=false` to populate `**/target/leaf-coverage.jsonl`.
   - `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'` to overwrite `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` in the workspace.
   - `mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am package -DskipTests` to render. The fresh `.adoc` is on disk; the AsciiDoctor render picks it up.
   - The docs-deploy job now needs a Postgres service container (mirrors `rewrite-build.yml`) and the same `psql init.sql` step. Lifted as-is.

3. **Snapshot caveat in the report's own description.** Edit the `:description:` line at the top of `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` to drop the "regenerate with mvn" sentence (which encoded the now-removed verify expectation) and add a one-line note: "Snapshot in repo; freshest copy renders on graphitron.sikt.no via CI."

## Tests

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on a tree with deliberately-stale `inference-axis-coverage.adoc` (e.g. delete a row) must succeed.
- No new unit test: removing a Maven execution does not need one, and the docs-deploy regen is verified by the workflow itself producing a fresh artifact on the next push to trunk.

## Roadmap entries

R132 lands on Done with file deletion (no changelog entry; routine cleanup). The doc-site behaviour change is a CI-only matter, not user-visible.
