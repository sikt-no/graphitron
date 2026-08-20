---
id: R748
title: "Decouple the roadmap tool from the generator reactor"
status: Backlog
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Decouple the roadmap tool from the generator reactor

The roadmap tool is the lowest-cadence module in the reactor, yet using it cold requires building `graphitron-model` first, which drags in the jOOQ codegen toolchain and effectively a full reactor build. It also ships DuckDB, a JNI-bundled artifact with a three-platform binary matrix, as its only other heavyweight dependency. Both entanglements are accidental homing, not real needs, and three moves remove them. The end state: roadmap-tool's sole main-scope dependency is snakeyaml, every input arrives as a file path, and a cold `mvn -pl roadmap-tool exec:java` compiles one small pure-Java module in seconds. Nothing about the CLI, the skills, the verify gates' coverage, or the docs site changes for users of any of them.

## The three moves

1. **`check-schema-identifiers` becomes a meta-test in `graphitron-model`.** The gate is a pure guard: boot the fact store, read `StoreCatalog`, scan the authored pages under `docs/architecture/`, fail on an identifier the schema no longer declares. The repo's idiom for exactly this shape is the meta-test (`RoadmapReferenceGuardTest`), and the store classes are already on `graphitron-model`'s own test classpath, so the exec execution in roadmap-tool's pom, its `Main` dispatch arm, and the exit-code-versus-exception dance all get deleted rather than moved. Accepted behavior change: as a test it is skipped under `-Pquick`, which is already true of the repo's other guard tests; CI's full build still gates trunk.

2. **`render-schema-reference` becomes a class in `graphitron-model`, consumed honestly by docs.** The renderer cannot be a test (it produces site pages into the Asciidoctor staging tree and must run when tests are skipped), so it keeps its exec slot in the docs pom at the same phase with the same arguments; only the `mainClass` and the classpath dependency change. The docs pom's dependency block then states a real consumption relationship (`graphitron-model` supplies the schema renderer) instead of the current ordering trick (a `provided` dependency on roadmap-tool whose half-job is forcing build order). The renderer's non-vacuity floors (empty catalog, relation on no page, blank comment text) move unchanged and keep gating `-P!docs` builds, since `process-resources` is base-build. The two module-local helpers the renderer shares with roadmap-tool's other renderers (`InertSpans`, `BuildFailure`) need homes on both sides; copies are acceptable at their size.

3. **`leaf-coverage` and `source-coverage` drop DuckDB for plain Java.** The only DuckDB-specific surface is ingestion: `read_json_auto` over the per-module classifier-trace JSONL files and `read_csv_auto` over the JaCoCo CSVs. Everything else is already staged through plain JDBC INSERTs and aggregated with dialect-neutral SQL. Parse the JSONL with snakeyaml (already on the classpath; YAML 1.2 is a JSON superset), parse the JaCoCo CSV with a trivial header-plus-values reader, and do the group-bys in Java streams; `TierVocabulary`'s tier ordering already exists as a comparator. The JNI artifact and its platform matrix leave the tool.

After the moves, removing the `graphitron-model` and `duckdb_jdbc` declarations from roadmap-tool's pom removes exactly the dependency edges whose consumers left; no remaining line of the tool touches H2, jOOQ, or DuckDB (verified: zero such imports in the module's sources; DuckDB is reached only via JDBC driver loading in the two coverage reports).

## Considered and set aside

- **Publish the tool at its own version and pin it in the reactor** (this item's original framing). Once the decoupling lands, pinning saves only the per-build compile of one small pure-Java module, and costs a release workflow, a version scheme, a pin property, rehosted verify gates, a reshaped CLI, and lockstep gate-plus-docs changes splitting into release-then-bump-then-land. `graphitron-tree-sitter-natives` earns that machinery through its C toolchain and four-platform binary matrix; a decoupled roadmap-tool needs `javac`. Revisit only if the module's build cost ever becomes real; the decoupled tool is trivially publishable then.
- **maven-site-plugin for the schema reference.** It renders and assembles; it does not generate domain content, so the generator survives unchanged and the site forks into a second (Doxia) pipeline outside the AsciiDoc xref graph.
- **AsciidoctorJ extensions (include processor or block macro) for the schema reference.** Extensions synthesize content inside documents but cannot add documents, and the reference's page set is data (one page per relation family), so authored stubs would reappear and drift. Generation would also move behind the profile-gated HTML render, un-gating `-P!docs` builds that the floors and `check-adoc-xrefs` currently cover from the staged sources.
