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

Measured on a live session against a consumer schema with roughly 700 diagnostics: an agent
spent a subagent, 3.8M cache-read tokens and 39k output tokens (about $6 at Opus rates) paging
the entry list, then hand-clustered it into a 13-row category table plus a four-row deferred
table, hand-separated fixable-in-the-schema from not-yet-supported, and hand-summarised where
the diagnostics lived. The output was correct and genuinely useful, which is the point: every
dimension it clustered on was already present in the typed data the tool projects and then
discards, so six dollars of context bought an aggregation the server could have computed and
returned in one small result.

Two consequences beyond the cost:

- **The hand-derived counts are approximate where precision matters most.** That report hedges
  its three largest categories (`~250+`, `~99`, `~45`) because it was extrapolating from
  partial pages, and its long tail collapses into a `~10` "Misc" row. Deciding what to fix
  first is exactly the decision those numbers drive, and they are the least reliable numbers
  in the table.
- **A count-free view gives no leverage ordering.** One bulk directive removal closes 142
  errors; another cluster of five needs individual attention. Without counts an agent fixes in
  file order rather than in leverage order.

## What is already typed

Most of the aggregation needs no new validate-time arm, only a projection the tool does not
currently make:

- `ValidationError` carries the sealed `Rejection` variant, not just its message, so the
  variant class is a stable group key. `RejectionKind` projects it to the author-error /
  invalid-schema / deferred fork.
- **`AuthorError.UnknownName` carries `(AttemptKind attemptKind, String attempt, List<String>
  candidates)`.** This is the significant one: the observed report's largest categories are
  precisely `(attemptKind, attempt)` pairs. Its dominant row, `column 'id' could not be
  resolved` at `~250+`, is exactly `COLUMN` + `"id"`; the unresolved condition methods are
  `SERVICE_METHOD` / `LIFTER_METHOD`; the `@reference` FK rows are `FOREIGN_KEY`; the
  unresolvable scalars are `TYPE_NAME`. Nine `AttemptKind` values cover the space, and
  `candidates` is already the "did you mean" payload the report quoted. Grouping this arm needs
  no prose inspection at all, and it yields an exact count rather than a hedged one.
- **`Rejection.Deferred` carries a `StubKey`.** The report's deferred section groups by variant
  class ("`ColumnBackedReferenceField` under `NestingField`", "`ComputedField` under
  `NestingField`"), which is exactly `StubKey.VariantClass.fieldClass`. So the whole
  not-yet-supported half clusters off a typed key too. One caveat to handle: `fieldClass` is
  nullable for inline-defer sites that name a feature shape rather than a stubbed leaf class,
  so those fall back to the `summary` string (the report's 57-count "generated column filters
  not supported" row is likely one of them).
- `BuildWarning.LintFinding` carries a typed `LintRule` whose `id()` the tool already surfaces
  per entry, so per-rule counts are free.
- Locations carry a canonical URI, so per-file counts are free, and `coordinate` truncated at
  the `.` gives per-type counts.
- `CompileDiagnostic` carries its own severity and file, so the `compile` source aggregates on
  the same axes as `schema`.

That leaves exactly one dimension untyped: the shape of the message *within* the
`AuthorError.Structural(String reason)` and `InvalidSchema.Structural(String reason)`
catch-alls. Those are where the retired-directive rejections live (`@table` on an input type,
`@notGenerated`, both constructed through `Rejection.structural`, so both classify as
`AUTHOR_ERROR`), along with the mutation-argument-shape and payload-classification rows.
Substantial, but a minority of the total and a much smaller surface for a heuristic than the
first draft of this item assumed.

## Direction

Three parts, each of which Spec should settle in shape and wire naming:

1. **A counts-only aggregate read.** It returns no diagnostic entries at all: the whole point
   is a result that stays small however broken the schema is. Shape, taking the observed report
   as the target output:

   - **Headline the actionable / deferred binary, not the three-way kind.** The observed report
     splits at the top into "fixable in the schema" and "graphitron not-yet-supported
     (workaround, not a bug in your schema)". That is `DEFERRED` versus everything else, and it
     is the split that changes what the agent does next. Report the `RejectionKind` three-way
     as a sub-count under it, not as the primary axis.
   - Per-cluster: count, a couple of example coordinates (the report quoted `Emne.id`,
     `Termin.id`, `Studieprogram.id` for its dominant row, and `Query.nodes`,
     `Query.megVedLarested` for a two-count row), and the files the cluster spans.
   - **Exact counts, and a stated tail rule.** The aggregate exists partly to replace hedged
     numbers, so no `~`. If small clusters fold into a tail bucket the way the report's "Misc"
     row did, the response says how many clusters folded and their combined count rather than
     silently truncating.
   - **A directory rollup alongside per-file counts.** The report's location summary is a
     common-prefix statement ("all under `sis/sis-graphql-spec/.../schema/features/`"), which
     is more useful than several dozen per-file rows.
   - The same snapshot availability / freshness axes as `diagnostics`: an aggregate over stale
     data is as misleading as an entry over stale data.

2. **A typed cluster key, with a prose fallback only where the model has none.** Given the
   findings above the key is a per-arm choice, not one heuristic:
   `UnknownName` clusters on `(attemptKind, attempt)`; `Deferred` clusters on `stubKey`
   (falling back to `summary` on the nullable-`fieldClass` inline sites); `LintFinding` clusters
   on `LintRule.id()`; everything else clusters on variant simple name plus a normalised message
   template, where quoted literals and identifier-ish tokens collapse to placeholders. The
   heuristic is then confined to the two `Structural` arms and the smaller typed arms, and the
   wire contract should say which clusters are typed and which are prose-derived, because only
   the former are stable across a message rewording.

3. **Drill-down filters on the existing tool.** An aggregate is only useful if the agent can
   then ask for one cluster's entries without paging the rest, so `diagnostics` gains the
   filter axes the aggregate groups on (rejection kind, attempt kind, stub key, lint rule,
   file, cluster key) alongside today's `severity` and exact `coordinate`. The drill-down is
   what makes the per-cluster example coordinates a sample rather than a lossy summary.

`instructions.txt` should point an agent at the aggregate first when the diagnostic count is
large, or the capability will go undiscovered in exactly the sessions that need it.

## Faceted aggregation, not a fixed set of reports

The reports above are the reports *we* predicted. An agent mid-migration wants pivots we did
not: "which types account for the unresolved `id` column", "which files carry deferred
diagnostics only", "attempt kinds crossed against directory". Shipping a fixed report set
means every unanticipated question falls back to paging entries, which is the failure this item
exists to remove. So the aggregate should be **faceted**, and the named reports should be
presets over the same mechanism rather than a parallel code path.

**A closed dimension set, not an expression language.** The request shape is a pivot-table
request:

```
diagnostics.aggregate(
  groupBy:  ["actionable", "attemptKind"],   // ordered, composite key
  where:    {source: "schema", file: "…/features/emne.graphqls"},
  minCount: 3,                                // tail threshold, in place of a silent "Misc"
  examples: 2,                                // example coordinates per group
  orderBy:  "count",                          // or "key"
  limit:    40
)
```

Every dimension name comes from a closed enum, each value backed by one extractor function over
a diagnostic row: `severity`, `source`, `actionable`, `kind`, `variant`, `attemptKind`,
`attempt`, `stubKey`, `lintRule`, `coordinate`, `type`, `file`, `directory`, `messageTemplate`.
`where` filters on the same extractors that `groupBy` groups on, which collapses this item's
part 3 (drill-down filters) and part 1 (the aggregate) into a single mechanism instead of two
parallel filter implementations. A preset report is then literally a named `(groupBy, where)`
tuple, so a report and an ad-hoc pivot cannot drift in behaviour.

**Why not a real query language.** A SQL-ish or CEL / JMESPath surface costs a grammar or a new
pinned dependency, unbounded semantics to validate, error messages good enough to recover from,
and a test surface that is the language rather than the data. It also spends the exact resource
this item is meant to save: an agent has to guess syntax, get a parse error, and retry, whereas
a closed enum is discoverable from the tool's input schema in one shot and cannot fail to parse.
For an agent consumer the closed set is not the compromise, it is the better interface. The
things a language would add that faceting does not (predicates over derived counts, arithmetic,
regex over messages) reduce to one or two scalar parameters: `minCount` covers the `having`
case, and a single optional `messageMatches` regex covers the rest if it proves necessary.

**Enabling refactor.** `DiagnosticsTool` currently builds wire `LinkedHashMap`s inline from
three sources (validator errors, build warnings, compile diagnostics) with no intermediate typed
row, so there is nothing for an extractor to read. Faceting wants a package-private
`DiagnosticRow` record unioning the three channels, with the existing per-entry wire mapping
projected off it. That is a small refactor and it improves the existing tool independent of
aggregation.

**The one genuine cost to design around: group cardinality.** A composite `groupBy` over
high-cardinality dimensions (`type` crossed with `attempt`, say) can produce nearly as many
groups as there are entries, so an unbounded aggregate can be *larger* than the entry list it
replaces. `limit` plus `minCount` handle it, but the response has to state how many groups were
elided and their combined count, so the aggregate never reads as complete when it is truncated.
Worth pinning as a test, because it is the failure mode that would quietly defeat the purpose.

## Forks for Spec

- **Separate tool versus a mode on `diagnostics`.** A separate tool gets its own description,
  which is how an agent discovers the capability at all, and keeps the counts-not-entries wire
  shape from being a conditional branch in one result schema. A mode keeps the polling surface
  singular. Leaning separate tool, on the discovery argument, and faceting strengthens that: the
  dimension enum has to be enumerated in a tool description somewhere, and it reads better as
  one tool's own vocabulary than as half of a two-mode tool's schema.
- **Whether the named presets are server-side or left to the agent.** Once faceting exists, a
  preset is just a `(groupBy, where)` tuple, and an agent that has read the dimension list can
  compose the triage view itself. Server-side presets still earn their place as the discoverable
  default (the zero-argument call should return the actionable / deferred headline without the
  agent having to know what to ask for), but the set should stay small; every preset is
  vocabulary an agent has to read past. Leaning one default preset plus faceting, rather than a
  catalogue of reports.
- **Whether the residual prose-normalising fallback is acceptable, or whether the group identity
  belongs in the model.** Now that `UnknownName` and `Deferred` are known to cluster off
  `attemptKind` and `stubKey`, the fallback covers only the two `Structural` arms, so the
  principled alternative (a typed reason code at the producing site) is a targeted change to one
  arm pair rather than a sweep. It still lands in the generator rather than the MCP module and
  touches every `Rejection.structural` call site, so it is plausibly its own item; the read-side
  fallback can ship first without foreclosing it, as long as the wire contract does not promise
  the prose-derived keys are stable. Spec should decide whether to carve the typed-reason-code
  half out now or leave it to follow-up evidence about how well the fallback clusters.
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

