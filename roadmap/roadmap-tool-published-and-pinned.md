---
id: R748
title: "Publish the roadmap tool at its own version and pin it in the reactor"
status: Backlog
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Publish the roadmap tool at its own version and pin it in the reactor

The roadmap tool's change cadence is very different from the rest of graphitron, yet it is a reactor module at `10-SNAPSHOT`: every session builds it from source before it can run `create`, `status`, or `generate`, and every reactor build compiles and tests it again even when nothing in it changed. We should not have to build the tool to use it. Instead, publish it at its own version and pin that version where the reactor and the skills invoke it, bumping the pin only when the tool actually changes.

The repo already holds the precedent: `graphitron-tree-sitter-natives` is deliberately not a child of `graphitron-rewrite-parent`, carries its own version scheme, releases to Maven Central through its own workflow, and is omitted from the reactor's `<modules>` so `mvn install -Plocal-db` pays zero build cost for it. This item asks for the same cadence decoupling for `roadmap-tool` (currently `maven.deploy.skip=true`, so nothing is published today).

Known couplings the Spec has to resolve:

- **The `graphitron-model` dependency is a homing problem, not a model dependency.** Exactly two subcommands touch it: `render-schema-reference` (invoked by the docs module at process-resources) and `check-schema-identifiers` (a reactor verify gate). Both consume only `GraphitronModelStore.open()` plus `StoreCatalog.read()`: hand-written boot-and-read code that executes the fact schema DDL into in-memory H2 and reads the census back from `INFORMATION_SCHEMA` and the `meta_` relations the DDL itself declares. No generated model classes, no capture logic; the transitive baggage is just H2 and jOOQ. The census is derived by H2 from the DDL text, so the single source of truth is the DDL plus the engine, and the shared-reader rationale ("never two derivations") binds these two subcommands to the reader's home, not the whole tool to the reactor. Their change cadence also tracks the DDL, not the roadmap tool. The likely resolution is therefore to rehome the two schema-docs subcommands into the reactor (beside the store code whose cadence they share) rather than to pin or shade `graphitron-model`: duplicating the boot loop in a pinned tool would create a second boot implementation with H2-version-skew and statement-splitter drift risk, and pinning a published `graphitron-model` publishes a module that does not need publishing. After the rehoming, roadmap-tool's dependencies are snakeyaml and DuckDB only, every remaining input arrives as a file path, and nothing blocks publishing it.
- **The verify-phase gates.** Seven exec executions (`verify-roadmap-readme`, `check-adoc-tables`, `verify-supported-directives`, `check-transient-citations`, `check-module-enumeration`, `check-schema-identifiers`, `check-coverage-agent-wiring`) run in every reactor build. They must keep running: six against the pinned artifact (exec with a pinned dependency, or the tool becoming a proper Maven plugin with a pinned version), while `check-schema-identifiers` follows the rehomed store-coupled code (previous bullet) and stays reactor-cadenced with its inputs.
- **Lockstep changes get a second step.** Today a gate rule and the docs it audits change in one commit. With a pin, that becomes release-the-tool then bump-the-pin then land-the-docs-change. The Spec should weigh that cost; it is the price of the decoupling and acceptable exactly because tool changes are rare.
- **Invocation surface.** CLAUDE.md, `roadmap/workflow.adoc`, the `roadmap` skill, and `.github/workflows/rewrite-build.yml` all spell the invocation as `mvn -pl roadmap-tool exec:java`; each needs the pinned form, and the pin should live in one place (a root-pom property) so bumping it is a one-line change.