---
id: R167
title: "Unify schema file extension handling between schemaInputs and graphitron:dev"
status: Backlog
bucket: feature
priority: 5
depends-on: []
created: 2026-05-15
last-updated: 2026-05-15
---

# Unify schema file extension handling between schemaInputs and graphitron:dev

`graphitron:dev` hard-codes a `.graphqls` filename suffix when filtering
`WatchService` events (`SchemaWatcher.GRAPHQLS_SUFFIX`, threaded through
`DevMojo.resolveSchemaRoots`), but the build's `<schemaInputs>` accepts
whatever glob the user writes, including `*.graphql`. Consumers with `.graphql`
files (e.g. Opptak's `regelverkMutations_exp.graphql`) have to duplicate
`<schemaInput>` blocks just to widen the glob, and `graphitron:dev` still
silently ignores edits to those files because the watcher filter does not
follow the configuration. The trailing `/*.graphqls` on each `<pattern>` also
conflates "where to look" with "what counts as a schema file", which is the
surface area that drifts when extensions vary; the orphan-scanner in
`SchemaProblemDiagnostic` hard-codes both extensions separately, a third
place the same list lives.

Proposal for Graphitron 10: introduce a top-level `<schemaFileExtensions>`
Mojo parameter, threaded through `RewriteContext`, driving (a) the post-scan
filter in `SchemaInputExpander`, (b) the `SchemaWatcher` suffix filter in
`graphitron:dev`, and (c) the orphan scan in `SchemaProblemDiagnostic`. The
filter is additive over the existing pattern: `<pattern>**/*.graphqls</pattern>`
keeps working unchanged (filter is a no-op on already-matching files), and a
follow-up cleanup can drop `/*.graphqls` from `<pattern>` so it describes
directories only. Open question for Spec: default to `[".graphqls"]` (boss's
suggestion) or `[".graphqls", ".graphql"]` (most consumers never need the
parameter).
