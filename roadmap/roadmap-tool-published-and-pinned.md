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

- **The `graphitron-model` dependency.** `render-schema-reference` and `check-schema-identifiers` boot the fact store through the shared catalog reader at `${project.version}`. A pinned tool either pins a published `graphitron-model`, shades what it needs, or leaves the store-coupled subcommands in-reactor while the CLI subcommands (create/status/generate/verify and the doc checks) move to the published artifact.
- **The verify-phase gates.** Seven exec executions (`verify-roadmap-readme`, `check-adoc-tables`, `verify-supported-directives`, `check-transient-citations`, `check-module-enumeration`, `check-schema-identifiers`, `check-coverage-agent-wiring`) run in every reactor build. They must keep running, but against the pinned artifact (exec with a pinned dependency, or the tool becoming a proper Maven plugin with a pinned version) instead of a freshly built module.
- **Lockstep changes get a second step.** Today a gate rule and the docs it audits change in one commit. With a pin, that becomes release-the-tool then bump-the-pin then land-the-docs-change. The Spec should weigh that cost; it is the price of the decoupling and acceptable exactly because tool changes are rare.
- **Invocation surface.** CLAUDE.md, `roadmap/workflow.adoc`, the `roadmap` skill, and `.github/workflows/rewrite-build.yml` all spell the invocation as `mvn -pl roadmap-tool exec:java`; each needs the pinned form, and the pin should live in one place (a root-pom property) so bumping it is a one-line change.