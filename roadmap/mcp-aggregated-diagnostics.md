---
id: R569
title: "Aggregated diagnostics commands for the MCP server"
status: Backlog
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Aggregated diagnostics commands for the MCP server

The `diagnostics` tool is entry-at-a-time only. It projects every validation error, build
warning, and generated-code compile diagnostic into a flat list, filters on exactly two axes
(`severity` and one exact `coordinate`), and pages 100 entries at a time. On a healthy schema
that is the right shape. On a large consumer schema mid-migration, where the error count runs
to the hundreds, it is the wrong shape: the first question an agent has is "what is broken,
in what proportion", and the tool can only answer it by handing over every entry and letting
the agent re-derive the shape in prose.

## Why it matters

Observed in a live session against a consumer schema: an agent spent a subagent and several
hundred lines of context paging the first 300 errors, then hand-clustered them into prose
categories ("`@table` on input types, roughly 95, concentrated in one file", "`column 'id'
could not be resolved` across dozens of types", "broken `@reference` join paths", "condition
method could not be resolved"), hand-separated genuine author errors from not-yet-supported
shapes, and was still paging the remainder when it reported. The clustering it produced was
correct and useful. Every dimension it clustered on was already present in the typed data the
tool projects and then discards, so the context spend bought an aggregation the server could
have computed and returned in one small result.

The cost is not only context. A count-free view gives an agent no way to see that one bulk
directive removal closes 95 errors while another cluster of five needs individual attention,
so it tends to fix in file order rather than in leverage order.

## What is already typed

Most of the aggregation needs no new validate-time arm, only a projection the tool does not
currently make:

- `ValidationError` carries the sealed `Rejection` variant, not just its message, so the
  variant class is a stable group key. `RejectionKind` projects it to the author-error /
  invalid-schema / deferred fork the session above derived by reading prose.
- `BuildWarning.LintFinding` carries a typed `LintRule` whose `id()` the tool already surfaces
  per entry, so per-rule counts are free.
- Locations carry a canonical URI, so per-file counts are free, and `coordinate` truncated at
  the `.` gives per-type counts.
- `CompileDiagnostic` carries its own severity and file, so the `compile` source aggregates on
  the same axes as `schema`.

The one dimension the observed clustering leaned on that is *not* typed is the shape of the
message *within* a variant. `Rejection.AuthorError.Structural(String reason)` and
`Rejection.InvalidSchema.Structural(String reason)` are free-text catch-alls, and they
dominate on a real schema, so grouping by variant class alone collapses most of the interesting
distinctions into one bucket.

## Direction

Three parts, each of which Spec should settle in shape and wire naming:

1. **A counts-only aggregate read.** Totals by `severity` x `source`, by `rejectionKind`, by
   lint rule, by file, and by cluster, with each cluster carrying its count, a couple of
   example coordinates, and the files it spans. It returns no diagnostic entries at all: the
   whole point is a result that stays small however broken the schema is. It reports the same
   snapshot availability / freshness axes as `diagnostics`, since an aggregate over stale
   data is as misleading as an entry over stale data.

2. **A cluster key that survives the `Structural` catch-all.** Proposed: the variant's simple
   name plus a normalised message template, where quoted literals and identifier-ish tokens
   collapse to placeholders, so `column 'id' could not be resolved` and `column 'name' could
   not be resolved` land in one cluster while remaining distinct from an unknown-name
   rejection. This is a read-side heuristic over prose and should be named as one.

3. **Drill-down filters on the existing tool.** An aggregate is only useful if the agent can
   then ask for one cluster's entries without paging the rest, so `diagnostics` gains the
   filter axes the aggregate groups on (rejection kind, lint rule, file, cluster key)
   alongside today's `severity` and exact `coordinate`.

`instructions.txt` should point an agent at the aggregate first when the diagnostic count is
large, or the capability will go undiscovered in exactly the sessions that need it.

## Forks for Spec

- **Separate tool versus a mode on `diagnostics`.** A separate tool gets its own description,
  which is how an agent discovers the capability at all, and keeps the counts-not-entries wire
  shape from being a conditional branch in one result schema. A mode keeps the polling surface
  singular. Leaning separate tool, on the discovery argument.
- **Whether the prose-normalising cluster key is acceptable, or whether the group identity
  belongs in the model.** The principled alternative is a typed reason code on the two
  `Structural` arms, so clusters come from the producing site rather than from a regex over
  its message. That is a larger change, lands in the generator rather than in the MCP module,
  and touches every `Structural` construction site. The read-side heuristic can ship first
  without foreclosing it, as long as the wire contract does not promise the key is stable.
- **Pure counts versus ranked guidance.** Ordering clusters by count already surfaces the
  leverage. Anything further ("this cluster is a bulk directive removal") would be the server
  asserting a fix strategy, which reads as the wrong layer. Leaning pure counts.

## Out of scope

- Any new validate-time arm or change to the rejection taxonomy. This is a read projection
  over already-classified data, matching how `diagnostics` is scoped today.
- LSP-side bulk application of fixes. The workspace-scoped bulk-quick-fix tier is
  `nodeid-migration-quickfix`'s to decide.
- Aggregation over anything but the two channels `diagnostics` already unions (validator
  output and `graphitron:dev` compile diagnostics).

