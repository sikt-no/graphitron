---
id: R175
title: "Tolerate empty <schemaInput> pattern matches (warning, not hard error)"
status: Backlog
bucket: dx
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Tolerate empty <schemaInput> pattern matches (warning, not hard error)

`SchemaInputExpander.expand` (`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/SchemaInputExpander.java:46-49`) throws `MojoExecutionException` ("matched no files") when any single `<schemaInput>` pattern resolves to zero files. Consumers commonly stub feature buckets ahead of having content: the opptak-subgraph pom declares `stable/`, `beta/`, and `experimental/` patterns, and an empty `beta/` (only a `description-suffix.md` and no `.graphqls`) wedges `graphitron:dev` and `graphitron:generate` with a hard error even though the overall schema set is well-formed. Proposal: per-pattern empty matches become a build warning via `Mojo#getLog().warn(...)` and processing continues; the hard error is preserved only for the aggregate-empty case (no entry matched anything). `SchemaInputExpanderTest` is updated to cover both shapes.
