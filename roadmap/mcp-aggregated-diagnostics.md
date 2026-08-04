---
id: R569
title: "Aggregated diagnostics commands for the MCP server"
status: Spec
bucket: feature
priority: 5
theme: diagnostics
depends-on: [input-field-resolution-typed-rejections]
created: 2026-08-03
last-updated: 2026-08-04
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
  `ServiceCarrierShapeError`, `PivotError`; `PivotError` alone has twelve). These are stable
  machine-readable ids of the exact kind this item wants, and the first draft of this item
  missed them entirely.

That leaves the message *within* the `AuthorError.Structural(String reason)` and
`InvalidSchema.Structural(String reason)` catch-alls as the only genuinely untyped axis. Some
retired-directive rejections live there (`@lookupKey` on a mutation input field, from both
`FieldBuilder` and `MutationInputResolver.rejectInputFieldDirectives`, via `Rejection.structural`),
alongside the mutation-argument-shape and payload-classification rows. The draft cited `@table` on an
input type here; that example is stale, because the reopened `@table`-on-input deprecation window
(accept, ignore, warn) deleted the rejection arm, so the directive now produces a `BuildWarning`
rather than any `Rejection`. Nothing else in the design depended on the example, but it does mean the
`Structural` residue open question 4 measures is smaller than the draft implied.

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
4. **`coordinate` is a nullable `String` that consumers re-derive by dot-splitting.** (Carved out at
   review into `validation-error-coordinate-sealed`; the reasoning below stands but this item does not
   depend on it. See Phasing.)
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
large, or the capability will go undiscovered in exactly the sessions that need it. This item writes
that one routing sentence only; the file's structure, its diagnostics orientation, and the coverage pin
that keeps routing honest belong to `mcp-server-instruction-routing`.

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
aggregate. The sharpest case is the retired directives: `@notGenerated` and `@lookupKey` on a
mutation input field each carry *three* rejection identities today, only one of which is a typed
`Rejection.directiveConflict`. A message template fuses them only *because a sentence is
duplicated*, and splits them the day one is reworded, which for `@lookupKey` has already happened:
`MutationInputResolver.rejectInputFieldDirectives` words it differently from the other two, so a
template does not fuse those sites even now.

Converging those identities turned out to be a change to the input-field resolution path rather than
a rewrite of three call sites, so it is its own item and this one depends on it; see the carve-out
under Phasing for the measured inventory. `@multitableReference` needs no work: its retirement
already routes through `Rejection.directiveConflict` from a single site, which is the target shape.

So: add the `directives` dimension on top of the converged identities, then measure what residue is
left. Whatever prose clustering survives gets a partition test over `Rejection` leaves, so no coded or
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
| 4 | `DiagnosticRow` union over the three channels; existing per-entry wire mapping projects off it | `graphitron-mcp` | small |
| 5 | Dimension enum + extractors + grouping + tail rule; the new counts-only tool; `where` shared with the widened `diagnostics` filters | `graphitron-mcp` | medium |

Steps 1 to 3 are each independently defensible and each improve the LSP or the watch formatter on
their own, so they can be carved into separate items if parallelism is wanted. Steps 4 and 5 must
not be split from each other: the shared `where` mechanism is the whole point, and splitting it is
how the two parallel filter implementations this design exists to prevent get built.

**Carved out at review, as a dependency: the retired-directive identity convergence.** Routing
`@notGenerated` and `@lookupKey` through `Rejection.directiveConflict` so each cause has one identity
now lives in `input-field-resolution-typed-rejections`. It was sized "small" across three
files on the strength of the two `FieldBuilder` sites and `MutationInputResolver`, all of which already
hand a `Rejection` to their caller. The third site does not:
`BuildContext.classifyInputFieldInternal` returns `InputFieldResolution.Unresolved`, which carries prose
and no `Rejection` at all, and both of its consumers join many failures into one `Rejection.structural`
before one reaches a `ValidationError`. So the identity cannot move until the record and the fan-in
move: sixteen producers, three consumers, and a real fork inside it (several rejections per input type,
or one). That is a lift of a documented principle violation in its own right and does not belong inside
a diagnostics-read item.

Unlike the `Coordinate` carve-out, this one is a **dependency** rather than a separation. The
`directives` dimension counts only rejections carrying a typed directive list, so on today's tree it
would report one row for `@notGenerated` where three rejections concern it. Shipping the dimension
before the convergence would put a confidently wrong count in the very view this item builds to replace
hedged counts. Nothing else in the design touches it, so the two can be worked in parallel provided the
dependency lands first.

**Carved out at review: the sealed `Coordinate` component.** A sealed
`Coordinate { SchemaWide | TypeLevel | FieldLevel }` on `ValidationError`, deleting
`WatchErrorFormatter`'s `isTypeLevel` / `typeOf`, now lives in its own Backlog item,
`validation-error-coordinate-sealed`. It is the right lift and the reasoning in "What the model owns"
stands, but it is not a prerequisite here, and it was the only step reaching outside the diagnostics
path. Two reasons it separates cleanly:

- The aggregate does not need it. With `coordinate` still a nullable `String`, the `coordinate` /
  `type` / `directory` extractors do the dot-split in *one* place, inside the dimension enum. The
  spec's objection in "What the model owns" is to *duplicating* the predicate, and one extractor is
  not a duplicate until the `Coordinate` lift exists. The absent case is already uniform under the
  `Optional<String>` extractor contract, so "rows with no coordinate are not silently lost" pins
  identically either way.
- Its blast radius is the widest and the least related. Beyond `WatchErrorFormatter` it reaches
  `DiagnosticsTool`'s filter comparison and wire `putIfNotNull`, and six `graphitron` test files
  assert on coordinate strings (`GraphitronSchemaBuilderTest`, `ConditionCommandsPipelineTest`,
  `ConnectionTypeValidationTest`, `TenantScopeValidationTest`, `NodeIdPipelineTest`, and the typed-
  rejection pipeline test). Carrying that into this item would also pull in
  `graphitron-maven-plugin`, which nothing else here touches.

Dropping it leaves this item spanning `graphitron`, `graphitron-lsp`, and `graphitron-mcp`, which is
a defensible blast radius for one item given steps 1 to 3 all exist to keep the aggregate a
projection.

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
  location-derived / prose-derived), in the `EdgeCoverageTest` mould. Write it over whatever bucket
  set survives the `messageTemplate` decision rather than hard-coding three: omitting that dimension
  drops the prose bucket entirely. The same partition is the documentation's structure (see the
  first-client check), so pin it once and let the tool description read from it.
- **Rejection-leaf partition.** Every `Rejection` leaf declared coded or deliberately codeless
  (step 3), and no typed-key arm reachable from the prose path (which the directive-convergence
  dependency delivers; this pin is what stops it regressing afterwards).
- **Rows with no coordinate are not silently lost.** Warnings and compile diagnostics carry no
  coordinate, so coordinate-reading dimensions yield a stated absent bucket rather than dropping
  rows out of the totals.
- Live-server tier: the `GraphitronMcpServerTest` tool-list assertion gains
  `diagnostics.aggregate` (it pins the list with `containsExactlyInAnyOrder`, so it fails until
  updated), plus one end-to-end call asserting the structured shape and the snapshot axes.

Steps 1 to 3 pin at their own layers: `RejectionKindProjectionTest` for the actionable projection,
and the existing LSP tests (`RejectionSeverityCoverageTest` reads each arm's stable code today) for
the collapsed `lspCodeOf`.

## Implementation sites

- `DiagnosticsTool.java`: the `DiagnosticRow` extraction; the three inline `LinkedHashMap`
  builders plus `addLocation` / `addCompileLocation` project off the row.
- A new class in `graphitron-mcp` for the dimension enum, extractors, grouping, and wire mapping.
- `GraphitronMcpServer.diagnosticsTool(...)`: widen the input schema with the shared filter axes.
- `GraphitronMcpServer`'s `tools` list: register the new tool. The `GraphitronMcpServerTest`
  tool-name assertion pins the list, so it fails until updated.
- Both tool descriptions: the dimension vocabulary has to be enumerated somewhere for discovery.
- `mcp/instructions.txt`: one sentence pointing at the aggregate when the diagnostic count is large.
  The file carries no diagnostics guidance today (it routes only to the `catalog.*` tools), and writing
  that orientation is `mcp-server-instruction-routing`'s scope, not this item's. If that item has not
  landed, drop the sentence in wherever the file's structure then is and let it be folded in later.
- `docs/manual/how-to/mcp-agent-context.adoc`: the per-tool table gains a `diagnostics.aggregate`
  row beside the existing `diagnostics` one. This is the user-facing surface the shipped
  `docs.search` / `catalog.search` tools landed prose on, and the draft omitted it.
- `ValidationReport.canonicalUri` is declared the single canonical URI site, but
  `addCompileLocation` puts `CompileDiagnostic.file()` into the `uri` slot raw. A `file` dimension
  spanning both channels would group two spellings of one path apart, so the compile channel has
  to pass through the same normalisation, and `directory` chops the normalised form.

## Reviewer decisions

The draft's five open questions, settled at Spec review so the implementer inherits decisions rather
than leanings. Each takes the draft's leaning except where noted.

- **Separate tool, named `diagnostics.aggregate`.** The discovery argument carries: an agent finds
  the capability through the tool's own description, and the counts-not-entries result schema should
  not be a conditional branch inside `diagnostics`. The name follows the registered dotted
  convention (`catalog.tables`, `catalog.describe`, `catalog.search`, `docs.search`), and settling it
  here makes the `GraphitronMcpServerTest` tool-name assertion and the manual's tool table
  mechanical edits rather than open choices. `diagnostics` keeps its singular polling role and gains
  only the shared `where` filter axes.
- **One default preset plus faceting.** The zero-argument call returns the actionable / deferred
  headline, so an agent that has read nothing still gets the triage view; everything else is composed
  from the dimension list. No catalogue of named reports.
- **Steps 1 to 3 stay in this item; the `Coordinate` lift and the directive convergence are carved
  out.** Steps 1 to 3 each directly remove a prose fallback or an `instanceof` copy from the
  aggregate, so they genuinely precede the tool. The `Coordinate` lift is separable at a single
  extractor and was the only step reaching outside the diagnostics path. The directive convergence
  turned out to be a lift of the input-field resolution path, so it is a dependency rather than a
  step; see Phasing for both. Blast radius lands at three modules.
- **"Measure then decide" is acceptable for `messageTemplate`, with the rule stated up front.**
  Deferring is legitimate here because the measurement is cheap and the fallback is strictly smaller
  and safe. What a Spec may not defer is *who decides and on what basis*, so: once the
  directive-convergence dependency has landed, measure the surviving `AuthorError.Structural` /
  `InvalidSchema.Structural` residue over the reactor's own fixture corpus. Ship `messageTemplate` only if that residue does not read usefully off `variant`
  alone; otherwise omit the dimension and let those rows cluster on `variant`. Either way the
  implementer records the measurement in the In Review note, so the Done reviewer can check the call
  rather than re-derive it. Omitting is the default, not the exception.
- **Pure counts.** Ordering by count already surfaces the leverage; naming a fix strategy would be
  the server asserting the wrong layer.

## User documentation (first-client check)

The user surface is agent-facing: the tool description an agent reads from the input schema, the
server instructions, and the manual's tool table. Drafts below, to move into their real homes when
the feature ships. The design's central bet against a query language is that a closed dimension set
"is discoverable from the tool's input schema in one shot", so this draft is where that bet gets
tested rather than asserted.

**Tool description** (`GraphitronMcpServer.diagnosticsAggregateTool`), title "Aggregate schema
diagnostics":

> Counts diagnostics grouped by the dimensions you name, and returns no entries, so the result stays
> small however broken the schema is. Call it with no arguments for the triage view: how much of the
> schema you can fix yourself, and how much is shapes graphitron does not generate yet. Then set
> `groupBy` to pivot on your own question. Every group carries an exact count, a few example
> coordinates, and the files it spans. When `minCount` or `limit` elides groups, the response says how
> many were elided and their combined count, so a truncated aggregate never reads as complete. Filter
> with `where` on the same dimensions you group on, then hand a group's key to the `diagnostics` tool
> to read that group's entries without paging the rest.

**Dimension vocabulary**, as the `groupBy` / `where` enum documents it. Grouped in three, not listed
in sixteen:

> *Read off the diagnostic's own data* (stable across a message rewording): `severity`, `source`,
> `actionable` (can you fix this in the schema?), `kind`, `variant` (the rejection's own class),
> `lspCode`, `attemptKind` (which lookup space a name resolution failed in), `attempt` (the name the
> author wrote), `stubKey`, `directives`, `lintRule`.
>
> *Read off the location*: `coordinate` (a type or `Type.field`), `type`, `file`, `directory`. The
> pairs are coarse and fine grains of one axis; pick deliberately.
>
> *Derived from message text* (not stable across a rewording): `messageTemplate`.

**`mcp/instructions.txt`**, the one sentence this item contributes to the diagnostics orientation that
`mcp-server-instruction-routing` owns:

> When the schema has more than a page of diagnostics, call `diagnostics.aggregate` before
> `diagnostics`. It answers what is broken and in what proportion in one small result, and its group
> keys feed straight back into `diagnostics` to read a single cluster's entries.

**`docs/manual/how-to/mcp-agent-context.adoc`**, a new row after the `diagnostics` one:

> `diagnostics.aggregate`: Counts those same diagnostics grouped by dimensions you choose (what kind
> of error, which attempted name, which file or directory) instead of listing them. On a schema
> mid-migration this answers "what is broken, in what proportion" in one small result, and each
> group's key feeds back into `diagnostics` to read just that group.

### What writing it surfaced

Two things the check was for, both recommendations to the author rather than settled decisions:

1. **Sixteen dimensions in a flat list does not read simply; grouped in three it does.** The natural
   grouping turns out to be the *same* typed / location-derived / prose-derived partition the
   dimension-partition test already pins. So the partition is not only an invariant, it is the
   documentation's structure, and the two should be generated from or checked against one another
   rather than maintained twice. That is a cheap strengthening of the test's value.
2. **If `messageTemplate` is omitted, the third bucket disappears and the wire contract's
   "which clusters are typed" claim goes vacuous** (everything would be typed). That is an argument
   for the reviewer decision above defaulting to omission, and it means the partition test should be
   written to pin whatever bucket set survives rather than hard-coding three. Worth knowing before
   the test is written, not after.

Neither reads as a reason to shrink the dimension set. The grain pairs (`coordinate` / `type`,
`file` / `directory`, `attemptKind` / `attempt`) look redundant in a flat list and stop looking
redundant once the list is grouped and the pairs are named as grains, which is the outcome the check
is supposed to produce.

## Out of scope

- **No new rejection cause, and no change to what the build accepts or rejects.** This replaces
  the Backlog body's "no change to the rejection taxonomy", which was wrong as written: held
  strictly it *forces* the prose heuristic and ships an untyped side-channel with a hedge instead
  of an enforcer. Typed-key exposure, capability lifts, and sub-taxonomy splits over existing arms
  are in scope precisely so the aggregate can stay a projection; what stays out is inventing new
  causes or moving the accept / reject line. Step 4 converges the identity of an existing
  rejection, it does not add one.
- LSP-side bulk application of fixes. The workspace-scoped bulk-quick-fix tier is
  `nodeid-migration-quickfix`'s to decide.
- Aggregation over anything but the two channels `diagnostics` already unions (validator
  output and `graphitron:dev` compile diagnostics).

