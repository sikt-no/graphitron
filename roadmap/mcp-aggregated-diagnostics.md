---
id: R569
title: "Aggregated diagnostics commands for the MCP server"
status: Spec
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

- **`InvalidSchema.DirectiveConflict` carries `List<String> directives`**, which is a typed
  cluster key over exactly the retired-directive family the observed report's rows 2 and 12
  describe.
- **Forty-two `lspCode()` sites already exist**, declared independently by nine sub-seals of
  `AuthorError` (`ServiceMethodCallError`, `ReflectionError`, `UpdateRowsError`,
  `DeleteRowsError`, `MutationTableArgError`, `ErrorChannelWalkerError`, `WireCoercionError`,
  `ServiceCarrierShapeError`, `PivotError`; `PivotError` alone has fourteen). These are stable
  machine-readable ids of the exact kind this item wants, and the first draft of this item
  missed them entirely.

That leaves the message *within* the `AuthorError.Structural(String reason)` and
`InvalidSchema.Structural(String reason)` catch-alls as the only genuinely untyped axis. Some
retired-directive rejections live there (`@table` on an input type via `Rejection.structural`),
alongside the mutation-argument-shape and payload-classification rows.

## What the model owns but does not yet expose

Drafting this Spec against the principles surfaced four places where the model already knows a
fact and discards it, so a read-side projection would have to re-derive it. Each is small, each
independently improves the LSP and the watch formatter, and each removes a dimension from the
heuristic's domain:

1. **`lspCode()` is a capability declared nine times over.** The only way to reach it is
   `Diagnostics.lspCodeOf`, a nine-arm `instanceof` chain returning `null` for everything else.
   The MCP aggregate would have to copy that chain or forgo 42 stable codes and prose-cluster
   them instead. Lift it to one capability interface (`CodedRejection extends Rejection` with
   `String lspCode()`), implemented by the nine sub-seals; `lspCodeOf` collapses to a single
   `instanceof CodedRejection`, and the aggregate gets a typed cluster key for free. Pin
   membership with a partition meta-test so every `Rejection` leaf is declared coded or
   deliberately codeless, rather than defaulting silently into the prose bucket.
2. **`StubKey` is a one-permit sealed interface with a nullable component, and the producer
   already discriminates.** `Rejection.deferred(summary, fieldClass)` and
   `Rejection.deferred(summary)` are two factories, and the second explicitly constructs
   `new StubKey.VariantClass(null)`. So the producer knows which arm it is and throws the
   knowledge away, and the read side pays with a prose fallback on the deferred half, which the
   evidence says clusters *best*. Split `StubKey` into two permits (a non-null `VariantClass`
   plus an arm for inline-defer sites) and the deferred cluster key becomes an exhaustive
   two-arm switch with no fallback. The only production reference to `VariantClass` outside
   `Rejection` is a javadoc pointer in `TypeFetcherGenerator`.
3. **The actionable / deferred binary would be its third site.** `RejectionKind`'s javadoc
   declares itself the projection layer, yet `Diagnostics.severityOf` already had to answer
   "deferred versus the rest" and recorded the answer in a comment ("Deferred is Error rather
   than Warning: the actionable hint is the rejection's message") rather than in the model.
   Inventing the binary in the MCP layer makes "can the author fix this in the schema?" a prose
   comment in one view and code in another. It belongs next to `messageLabel()` as an exhaustive
   switch, pinned by the existing `RejectionKindProjectionTest`. Pick one word for it and use
   the same word on the wire and in the model, or the drift reopens at the vocabulary level.
4. **`coordinate` is a nullable `String` that consumers re-derive by dot-splitting.**
   `ValidationError.forType` / `forField` know the grain at construction and collapse it to a
   string plus `null` for schema-wide; `WatchErrorFormatter` re-derives it with `isTypeLevel` /
   `typeOf` dot-splits, and this item's `type` dimension would be the second site of the same
   predicate. `DiagnosticsTool`'s "warnings carry no coordinate, so a coordinate filter excludes
   them by construction" is a third fact about the same slot living only in a comment. A sealed
   `Coordinate { SchemaWide | TypeLevel | FieldLevel }` component makes two dimensions read slots
   instead of parsing, deletes the formatter's predicates, and turns the warnings invariant into
   a type fact.

Doing these first is what keeps the aggregate a projection rather than a shadow taxonomy. It
also reorders the work: see Phasing.

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

Every dimension name comes from a closed enum, each value backed by one extractor over a
diagnostic row: `severity`, `source`, `actionable`, `kind`, `variant`, `lspCode`, `attemptKind`,
`attempt`, `stubKey`, `directives`, `lintRule`, `coordinate`, `type`, `file`, `directory`,
`messageTemplate`. `where` filters on the same extractors that `groupBy` groups on, which
collapses this item's part 3 (drill-down filters) and part 1 (the aggregate) into a single
mechanism instead of two parallel filter implementations. A preset report is then literally a
named `(groupBy, where)` tuple, so a report and an ad-hoc pivot cannot drift in behaviour.

**An enum of labels is the right shape here, and the sealed switch belongs in the extractor
bodies.** This module already settled the question: `EdgeKind`'s javadoc calls itself
"legitimately an `enum` (a label), not a sealed hierarchy" because the varying-shape part lives
in `NodeRef`, so the enum "carries no kind-dependent nullability", and names that as the
resolution of the sealed-over-enum tension. A pivot dimension is the same: every value has the
identical shape `DiagnosticRow -> group key`, no value carries different data, so a sealed
hierarchy buys nothing. Two constraints keep it from smuggling in a stringly side-channel:

- Every extractor that reads a rejection is an **exhaustive sealed switch with no `default` and
  no `instanceof` chain**, so a new `Rejection` arm forces a decision in the aggregate the way
  `RejectionKind.of` and `Diagnostics.severityOf` already do.
- Extractors return a **uniform `Optional<String>`** for "not applicable on this row", never
  `null`, so `where` and `groupBy` cannot disagree about absence.
- The enum gets the coverage pin the module already established with `EdgeCoverageTest`: every
  dimension declared in exactly one bucket (typed-key / location-derived / prose-derived), which
  makes the wire contract's "which clusters are typed" claim a live partition instead of prose.

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
projected off it. It improves the existing tool independent of aggregation, but it must land
*after* the model lifts, not before: a row built with `String coordinate` / `String message`
components bakes in the wide shapes, and then narrowing them is a change to the row and to every
extractor at once.

**The prose fallback needs an enforcer, not a hedge.** Saying on the wire "these clusters are
prose-derived" is honest but nothing fails when a message reword silently re-partitions the
aggregate. Worse, the `@notGenerated` retirement sentence is currently emitted from three sites
with three *different* rejection identities: `Rejection.directiveConflict(...)` in `FieldBuilder`
(typed, carrying the directive list), `Rejection.structural(...)` elsewhere in `FieldBuilder`
(prose only), and a bare-string `InputFieldResolution.Unresolved` in `BuildContext`. A message
template fuses those three only *because the sentence is duplicated*, and splits them the day one
is reworded. So: add the `directives` dimension, route the stray retired-directive sites through
`Rejection.directiveConflict` so the cause has one identity, then measure what residue is left.
Whatever prose clustering survives gets a partition test over `Rejection` leaves, so no coded or
typed-key arm can reach the prose path and the heuristic's domain is a declared, shrinking set
rather than the default bucket new arms fall into.

**The one genuine cost to design around: group cardinality.** A composite `groupBy` over
high-cardinality dimensions (`type` crossed with `attempt`, say) can produce nearly as many
groups as there are entries, so an unbounded aggregate can be *larger* than the entry list it
replaces. `limit` plus `minCount` handle it, but the response has to state how many groups were
elided and their combined count, so the aggregate never reads as complete when it is truncated.
Worth pinning as a test, because it is the failure mode that would quietly defeat the purpose.

## Phasing

Ordered by dependency, not by module. The model lifts come first because building the row or the
extractors on today's wide shapes means doing them twice.

| # | What | Where | Size |
|---|---|---|---|
| 1 | `RejectionKind` gains the author-fixable projection; `Diagnostics.severityOf`'s comment becomes a read of it | `graphitron`, `graphitron-lsp` | small |
| 2 | `StubKey` splits into two permits, non-null `VariantClass` plus an inline-defer arm; the two `deferred` factories map to their own arm | `graphitron` | small |
| 3 | `CodedRejection` capability lift; `Diagnostics.lspCodeOf` collapses to one `instanceof`; membership partition meta-test | `graphitron`, `graphitron-lsp` | small-medium (42 sites, mechanical) |
| 4 | Sealed `Coordinate` component on `ValidationError`; `WatchErrorFormatter`'s `isTypeLevel` / `typeOf` delete | `graphitron`, `graphitron-maven-plugin` | medium |
| 5 | Retired-directive rejection identities converge on `Rejection.directiveConflict` | `graphitron` | small |
| 6 | `DiagnosticRow` union over the three channels; existing per-entry wire mapping projects off it | `graphitron-mcp` | small |
| 7 | Dimension enum + extractors + grouping + tail rule; the new counts-only tool; `where` shared with the widened `diagnostics` filters | `graphitron-mcp` | medium |

Steps 1 to 5 are each independently defensible and each improve the LSP or the watch formatter on
their own, so they can be carved into separate items if parallelism is wanted. Steps 6 and 7 must
not be split from each other: the shared `where` mechanism is the whole point, and splitting it is
how the two parallel filter implementations this design exists to prevent get built.

## Tests

Unit tier in `graphitron-mcp`, following `DiagnosticsToolCompileSourceTest`, which calls
`DiagnosticsTool.diagnosticsResult` directly with a hand-built `ValidationReport` and no live
server. The tests that carry weight are the invariant pins, not per-dimension unit tests:

- **Aggregate / drill-down parity.** Filtering `diagnostics` to a group's key returns exactly that
  group's count. This is the pin that makes the per-cluster examples a sample rather than a lossy
  summary, and it is the one test that would catch the two-filter-implementation drift.
  `LintSuppressionDiagnosticsParityTest` is the module's precedent for this cross-view shape.
- **Truncation honesty.** `minCount` / `limit` elision reports the elided group count and their
  combined count; a truncated aggregate never reads as complete.
- **Cardinality guard.** A high-cardinality composite `groupBy` over a large fixture does not
  return more groups than a stated cap. This is the failure mode that would quietly defeat the
  item's purpose by making the aggregate bigger than the entry list.
- **Dimension partition.** Every dimension declared in exactly one bucket (typed-key /
  location-derived / prose-derived), in the `EdgeCoverageTest` mould.
- **Rejection-leaf partition.** Every `Rejection` leaf declared coded or deliberately codeless
  (step 3), and no typed-key arm reachable from the prose path (step 5).
- **Rows with no coordinate are not silently lost.** Warnings and compile diagnostics carry no
  coordinate, so coordinate-reading dimensions yield a stated absent bucket rather than dropping
  rows out of the totals.
- Live-server tier: the `GraphitronMcpServerTest` tool-list assertion gains the new name, plus one
  end-to-end call asserting the structured shape and the snapshot axes.

Steps 1 to 4 pin at their own layers: `RejectionKindProjectionTest` for the actionable projection,
and the existing LSP and watch-formatter tests for the collapsed `lspCodeOf` and the deleted
coordinate predicates.

## Implementation sites

- `DiagnosticsTool.java`: the `DiagnosticRow` extraction; the three inline `LinkedHashMap`
  builders plus `addLocation` / `addCompileLocation` project off the row.
- A new class in `graphitron-mcp` for the dimension enum, extractors, grouping, and wire mapping.
- `GraphitronMcpServer.diagnosticsTool(...)`: widen the input schema with the shared filter axes.
- `GraphitronMcpServer`'s `tools` list: register the new tool. The `GraphitronMcpServerTest`
  tool-name assertion pins the list, so it fails until updated.
- Both tool descriptions: the dimension vocabulary has to be enumerated somewhere for discovery.
- `mcp/instructions.txt`: point at the aggregate when the diagnostic count is large.
- `ValidationReport.canonicalUri` is declared the single canonical URI site, but
  `addCompileLocation` puts `CompileDiagnostic.file()` into the `uri` slot raw. A `file` dimension
  spanning both channels would group two spellings of one path apart, so the compile channel has
  to pass through the same normalisation, and `directory` chops the normalised form.

## Open questions for the reviewer

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
- **Whether steps 1 to 5 belong in this item or in their own.** They are model lifts in
  `graphitron` and `graphitron-lsp` serving a read tool in `graphitron-mcp`, which is a wide blast
  radius for one item, and each stands on its own merits. Against splitting: the aggregate is what
  motivated finding them, and an item that ships step 7 on today's shapes ships the shadow taxonomy
  this Spec exists to avoid. Leaning one item with the phasing above, and carving out step 4
  (the `Coordinate` sub-taxonomy, the largest and the one with reach outside the diagnostics path)
  if the reviewer wants the blast radius smaller.
- **Whether a prose-derived `messageTemplate` dimension should ship at all.** After steps 3 and 5
  its domain is whatever `Structural` residue remains. If that residue turns out small, the
  honest move may be to omit the dimension entirely and let those rows cluster only on `variant`,
  rather than ship a heuristic whose group identity has no owner. Worth deciding on measured
  residue rather than up front, but the reviewer should say whether "measure then decide" is an
  acceptable thing for a Spec to defer.
- **Pure counts versus ranked guidance.** Ordering clusters by count already surfaces the
  leverage. Anything further ("this cluster is a bulk directive removal") would be the server
  asserting a fix strategy, which reads as the wrong layer. Leaning pure counts.

## Out of scope

- **No new rejection cause, and no change to what the build accepts or rejects.** This replaces
  the Backlog body's "no change to the rejection taxonomy", which was wrong as written: held
  strictly it *forces* the prose heuristic and ships an untyped side-channel with a hedge instead
  of an enforcer. Typed-key exposure, capability lifts, and sub-taxonomy splits over existing arms
  are in scope precisely so the aggregate can stay a projection; what stays out is inventing new
  causes or moving the accept / reject line. Step 5 converges the identity of an existing
  rejection, it does not add one.
- LSP-side bulk application of fixes. The workspace-scoped bulk-quick-fix tier is
  `nodeid-migration-quickfix`'s to decide.
- Aggregation over anything but the two channels `diagnostics` already unions (validator
  output and `graphitron:dev` compile diagnostics).

