---
id: R132
title: "Move leaf-coverage report verification off local builds"
status: Backlog
bucket: cleanup
priority: 3
theme: structural-refactor
depends-on: []
---

# Move leaf-coverage report verification off local builds

The `verify-leaf-coverage-report` execution in `graphitron-rewrite/roadmap-tool/pom.xml` fires on every default `mvn verify` (the `leaf-coverage` profile in the parent pom is active unless `-Dleaf-coverage.skip` is set) and exits 1 whenever `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` differs from a freshly-regenerated report. Because the report mention-joins every sealed leaf simple-name against every roadmap `*.md` body (R107), drift is the normal state — any roadmap edit that names a leaf trips the gate. A contributor running `mvn install -Plocal-db` to test an unrelated change is then told to rerun a `roadmap-tool leaf-coverage` invocation and re-commit. The leaf-coverage report is a generated artifact whose value is "the doc site shows current data"; it does not encode a correctness invariant the local build should defend. Today's verify gate forces every contributor to own freshness of a publishing artifact they did not touch.

Target: `mvn verify` on a contributor machine never fails because of leaf-coverage drift. CI owns regeneration: the report under `graphitron-rewrite/roadmap/inference-axis-coverage.adoc` (which already renders into the docs site under Phase 4 of R9) is rebuilt by a CI step and either committed back to trunk or its drift is surfaced as a CI-only check the contributor does not see locally.

Out of scope: changing the mention join itself (R107), changing what the report contains, and changing the docs-site deploy path.
