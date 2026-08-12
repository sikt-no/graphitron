---
id: R637
title: "graphitron:dev reports schema-load failures as infrastructure"
status: Backlog
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# graphitron:dev reports schema-load failures as infrastructure

A `SchemaProblem` thrown by graphql-java at schema assembly is an author-correctable
failure, but `graphitron:dev` reports it as `failed (infrastructure)` with a full stack
trace. `DevMojo.runGeneratorPass` has author-facing catch arms only for
`ValidationFailedException` (validator verdicts, rendered by `WatchErrorFormatter`) and
`SchemaParseException` (mid-edit syntax, one attributed line); a `SchemaProblem` escaping
`GraphitronSchemaBuilder.buildBundle` (which only rewrites federation errors into
`ValidationFailedException` and rethrows the rest) falls into the generic
`RuntimeException` arm. The batch goal already gets this right: `AbstractRewriteMojo`
catches `SchemaProblem` and formats it through `SchemaProblemDiagnostic` into a clean
author-facing message.

The gap was latent until R570 removed `ExternalCodeReference.name`: a schema still using
`name:` in a code reference now fails at `makeExecutableSchema` ("Fields ['name'] not
present in type 'ExternalCodeReference'") instead of later in classification, so a plain
authoring mistake surfaces in the dev log as an infrastructure failure with a graphql-java
plus executor stack. Any directive-argument shape error hits the same path.

Fix: give `DevMojo.runGeneratorPass` a `SchemaProblem` catch arm that routes through
`SchemaProblemDiagnostic` the way the batch goal does, so both goals read alike, and reset
`previousErrorKeys` as the `SchemaParseException` arm does. Consider what the MCP
`diagnostics` surface should carry for this failure class while the pass is broken.
Sibling finding from the same R570 review: R578 covers the LSP severity side of the same
strictness inversion.
