---
id: R133
title: "Flip leaf-coverage profile activation to opt-in"
status: Backlog
bucket: cleanup
priority: 5
theme: structural-refactor
depends-on: []
---

# Flip leaf-coverage profile activation to opt-in

The `leaf-coverage` profile in `graphitron-rewrite/pom.xml` is activated by negation (`<name>!leaf-coverage.skip</name>`), so every default contributor `mvn verify` truncates `target/leaf-coverage.jsonl` in `process-test-resources` and threads a `graphitron.classification.trace` system property into every surefire/failsafe. The traces are only consumed by `roadmap-tool leaf-coverage`, which after R132 runs only in the CI regeneration step. Every other build pays the antrun-truncate cost and writes JSONL nobody reads.

Flip the activation to opt-in: profile active when `-Pleaf-coverage` (or `-Dleaf-coverage`) is passed, dormant otherwise. CI workflows that need traces (`rewrite-build.yml` after R132 ships) opt in explicitly. The existing `-Dleaf-coverage.skip` opt-out becomes a no-op since the default is now dormant; remove it. Update the profile's existing comment block to describe the inverted contract, and audit Java docstrings and report descriptions in `roadmap-tool/src/main/java/no/sikt/graphitron/roadmap/Main.java` and `LeafCoverageReport.java` that currently say "regenerate with `mvn verify`" (the bare command no longer suffices).

Sibling cleanup to R132 (which moved verification off local builds but did not touch the profile activation). Keep separate because the user-visible pain in R132 was the failing build; this item is the performance/hygiene cleanup.

Out of scope: anything in R132's surface (verify gate removal, CI artifact ferry, docs-deploy chain).
