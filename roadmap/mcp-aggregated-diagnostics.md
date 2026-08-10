---
id: R569
title: "Aggregated diagnostics commands for the MCP server"
status: Spec
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-10
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
`InvalidSchema.Structural(String reason)` catch-alls as the only genuinely untyped axis, and that
residue has shrunk twice since the draft, both times by a shipped item rather than by anything this
one does. The draft cited `@table` on an input type; the reopened `@table`-on-input deprecation
window (accept, ignore, warn) deleted the rejection arm, so the directive produces a `BuildWarning`
and no `Rejection` at all. The revision then cited `@lookupKey` on a mutation input field as living
in `Structural` via both `FieldBuilder` and `MutationInputResolver.rejectInputFieldDirectives`; R585
moved both sites, and `@notGenerated` with them, onto `Rejection.directiveConflict`. On today's tree
no retired-directive rejection reaches the `Structural` arm: what is left there is the
mutation-argument-shape and payload-classification rows. Nothing in the design depended on either
example, but the residue reviewer decision 4 measures is smaller again, which is one more argument
for that decision's default of omitting `messageTemplate`.

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
that one routing line only. The file's structure, its diagnostics orientation, and the coverage pin
that keeps routing honest shipped with R584 (see `roadmap/changelog.md`), which also means the
routing line is not optional here: see the implementation site below.

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

Every dimension name comes from a closed enum, each value backed by one column of the
`diagnostic` view (the first-reader section below): `severity`, `source`, `actionable`,
`kind`, `variant`, `lspCode`, `attemptKind`, `attempt`, `stubKey`, `directives`, `lintRule`,
`coordinate`, `type`, `file`, `directory`, `messageTemplate`. `where` filters on the same
columns that `groupBy` groups on, which collapses this item's part 3 (drill-down filters) and
part 1 (the aggregate) into a single mechanism instead of two parallel filter implementations.
A preset report is then literally a named `(groupBy, where)` tuple, so a report and an ad-hoc
pivot cannot drift in behaviour.

**An enum of labels is the right shape here, and the sealed switches live in the bridge
loader.** This module already settled the question: `EdgeKind`'s javadoc calls itself
"legitimately an `enum` (a label), not a sealed hierarchy" because the varying-shape part lives
in `NodeRef`, so the enum "carries no kind-dependent nullability", and names that as the
resolution of the sealed-over-enum tension. A pivot dimension is the same: every value has the
identical shape, a mapping from dimension name to one column of the `diagnostic` view, no value
carries different data, so a sealed hierarchy buys nothing. The constraints that keep it from
smuggling in a stringly side-channel now hold at two sites, the loader that writes the columns
and the view that computes the derived ones (see the first-reader section below):

- Every typed column the loader fills from a rejection is written through an **exhaustive
  sealed switch with no `default` and no `instanceof` chain**, so a new `Rejection` arm forces
  a decision the way `RejectionKind.of` and `Diagnostics.severityOf` already do; columns are
  where the switch's answers land, and an unwritten column cannot be observed from stored
  strings, which is why the enforcer sits on the loader and not on the data.
- "Not applicable on this row" is a **SQL `NULL`, uniformly**, and every comparison in the
  shared `where` mechanism is null-safe (`IS NOT DISTINCT FROM`), so `where` and `groupBy`
  cannot disagree about absence.
- **Every dimension is a single-valued column, so every dimension groups at one row per
  diagnostic.** This is the constraint `directives` has to satisfy and the reason it groups on
  the set, rendered canonically as a view column over its ordered child relation; see the
  reviewer decision. Keeping the whole enum at one grain is what lets group counts sum to the
  row count, which the truncation-honesty and cardinality pins both read.
- The enum gets the coverage pin the module already established with `EdgeCoverageTest`: every
  dimension declared in exactly one bucket (typed-key / location-derived / prose-derived), which
  makes the wire contract's "which clusters are typed" claim a live partition instead of prose.

**Why not a real query language.** The first draft of this section priced a query surface at
a grammar or a new pinned dependency plus unbounded semantics to validate, and half of that
pricing is now stale: with the fact store shipped (R595), SQL arrives with H2 and
jOOQ for free, no grammar to build and no new dependency to pin. What remains is the case
that still decides it. A closed enum is discoverable from the tool's input schema in one
shot and cannot fail to parse, whereas a query surface spends the exact resource this item is
meant to save: an agent guesses syntax, gets a parse error, and retries. The server also owns
the result shape only under the closed contract: exact counts, the stated tail rule, and the
zero-argument triage preset are guarantees a raw query cannot be made to keep on the caller's
behalf. And the boundary principle points the same way: this is a wire boundary whose job is
to decode untrusted input into a typed closed vocabulary, and accepting a query string at it
admits an untyped side-channel at exactly the point the design exists to type. For an agent
consumer the closed set is not the compromise, it is the better interface. The things a
language would add that faceting does not (predicates over derived counts, arithmetic, regex
over messages) reduce to one or two scalar parameters: `minCount` covers the `having` case,
and a single optional `messageMatches` regex covers the rest if it proves necessary. A
read-only SQL surface over the fact store as a whole is a different tool with a different
job, deferred to its own item; see the first-reader section below.

**Enabling refactor.** `DiagnosticsTool` currently builds wire `LinkedHashMap`s inline from
three sources; the compile source now arrives typed (`CompileDiagnostic`, shipped with the
`javac_` family), while validator errors and build warnings still project straight off the
report with no intermediate typed representation. The union the earlier draft gave to a
package-private `DiagnosticRow` record is
now the `diagnostic` view (the first-reader section below), and both
tools read it: the existing per-entry wire mapping projects off the view's rows, and the widened
filters and the aggregate share one null-safe `where` translation. The sequencing constraint
survives the substrate change: the bridge DDL must land *after* the model lifts, not before,
because columns typed off today's wide shapes (`String coordinate`, prose-only stub keys) would
bake the wide shapes into the store and narrowing them later is a DDL change plus a loader
change at once.

**The prose fallback needs an enforcer, not a hedge.** Saying on the wire "these clusters are
prose-derived" is honest but nothing fails when a message reword silently re-partitions the
aggregate. The sharpest case used to be the retired directives: `@notGenerated` and `@lookupKey` on
a mutation input field each carried three rejection identities, only one of them a typed
`Rejection.directiveConflict`, so a message template fused them only *because a sentence was
duplicated* and split them the day one was reworded, which for `@lookupKey` had already happened.
R585 converged all of them (five spellings, not the three counted here), so the sharpest case is
gone from the tree and the `directives` pivot can trust its counts. `@multitableReference` needed no
work: its retirement already routed through `Rejection.directiveConflict` from a single site, which
is the target shape.

The argument the case was making survives its own example, and that is the reason to keep the
enforcer rather than drop it with the convergence. Nothing stopped those three identities from
diverging except an item noticing, and nothing stops the next set: add the `directives` dimension,
then measure what prose residue is left. Whatever prose clustering survives gets a partition test
over `Rejection` leaves, so no coded or typed-key arm can reach the prose path and the heuristic's
domain is a declared, shrinking set rather than the default bucket new arms fall into.

**The one genuine cost to design around: group cardinality.** A composite `groupBy` over
high-cardinality dimensions (`type` crossed with `attempt`, say) can produce nearly as many
groups as there are entries, so an unbounded aggregate can be *larger* than the entry list it
replaces. `limit` plus `minCount` handle it, but the response has to state how many groups were
elided and their combined count, so the aggregate never reads as complete when it is truncated.
Worth pinning as a test, because it is the failure mode that would quietly defeat the purpose.

## First reader of the fact store

Between this item's Spec review and now the substrate shipped and the strategy flipped:
`graphitron-model-captures-facts` (R595, Done; see `roadmap/changelog.md`) landed the
`graphitron-model` module, the fact-schema DDL, the capture loads, and a store that persists
between runs, and this item is now designated the store's first reader instead of building
its own evaluation engine. Everything the earlier draft hand-built is native SQL over a
relation: `groupBy` is `GROUP BY`, `where` is `WHERE`, `minCount` is `HAVING`, the tail
accounting is a second aggregate over the elided remainder, and the `DiagnosticRow` record
and the Java grouping engine are never built. The wire contract does not move: the closed
dimension set, the zero-argument triage preset, exact counts, tail honesty, and the
single-valued grain are fixed, and every invariant pin asserts on tool answers, so the
contract cannot tell which substrate answered. As first reader this item is also the cheapest
end-to-end validation of the store available: its whole workload is the query vocabulary the
classification migration will lean on (`GROUP BY`, `HAVING`, a union view), and a wrong
answer costs a bad triage rather than wrong generated code.

**One relation per writer, one view for the reader.** A `diagnostic` union view, with
`source` as a per-arm literal in the shipped `graphql_directive_site` mould, is what the
aggregate and the widened `diagnostics` filters read; nothing reads the base relations
directly. Behind it, one base relation per channel and writer cadence. The compile arm is
the shipped `javac_diagnostic` (R603, Done; see `roadmap/changelog.md`), read from day one:
that item's dovetail picked the fork where this item's compile bridge is never built, it
landed first as expected, so the fork is settled and its fallback is moot. Behind the
relation sits `CompileDiagnostic`, the single flattening at the javac boundary that the
console block, the current MCP tool, and `CompileFacts` all read; this item's view becomes
the fourth reader of the same spelling. The schema arm is this item's one bridge
relation: validator errors and build warnings, written per snapshot. The per-writer split is
what keeps ownership single: `validation-adds-facts` (R589) later lands its detection-minted
relation (the DDL header holds `intent_` in reserve for that stratum) and replaces the schema
arm, a dropped table and a one-line view edit, never a re-key. The split also keeps the
`source` boundary honest: for schema rows severity is a function of the rejection's kind, for
compile rows it is javac's independent verdict, and one relation holding both would give one
column two meanings.

**The store is shared and persistent now, and the bridge inherits both facts.**
R610 (shipped) moved the persisted store to a per-user cache shared by every module of a
workspace, keyed the SDL families by a leading `graph_name`, and made refresh ownership-scoped. The bridge relation inherits the dimension
on the same reasoning `javac_diagnostic` shipped with: `graph_name` leads its key
with the structural FK to `store_graph`, every loader statement is scoped to the session's
graph (an unscoped delete in a shared store is one module erasing another's diagnostics), and
the relation passes R610's schema gate without an exemption. The view's arms carry
`graph_name` through, and the MCP read site filters to the reading session's graph; a graph
dimension on the wire waits for a multi-graph workspace to want it. Persistence adds one
honesty clause, not a mechanism: bridge rows from a previous session survive a restart until
the first snapshot's graph-scoped delete-and-reload replaces them, so the tool's existing
snapshot availability and freshness axes are what keeps a stale aggregate from reading as
current. The compile arm needs no such clause because its lifecycle shipped with the
relation: rows exist only between a dev session's compile round and the graph's next
generation (capture clears the partition with the rest of its ownership scope), and a batch
run's partition stays empty rather than claiming anything it cannot know, so an empty
compile arm is honest emptiness, never staleness. Naming follows the landed vocabulary doctrine (a family is named for whose
vocabulary the row is written in, never its reader or role): the leaning is `validator_`,
the oracle whose verdicts the rows transcribe during the window, parallel to `javac_`; the
Spec review settles the word.

**The reader goes through the session's handle, never the file.** R610 opens the shared store
in mixed mode, falls back to a module-local in-memory store on any cache trouble, and stamps
the store's compatibility into the file path, so a reader that opened the persisted file
itself could be reading a different store than the one the session writes. The shipped
`CompileFacts` already holds this contract on the write side (it takes the dev session's
store handle); the
aggregate, the widened filters, and the schema-bridge loader hold the same contract on the
read side: one handle, owned by the workspace, shared by every writer and reader in the
session.

**The bridge is a load, and saying so is part of the design.** The schema bridge relation is
`ValidationReport`, loaded. A copy with exactly one writer, one source, and a graph-scoped
lifetime is a materialized view, not a second population; but it is also not capture, because
capture agrees with the model from an independent walk of a different source, while the
bridge's census check against the report it loaded from catches loader bugs only, and during
the bridge window the relation content has no independent enforcer. The agreement driver
therefore gains a `BRIDGED` registration arm, named as such, whose javadoc carries exactly
that caveat and the retirement condition (the arm going empty is the signal to delete it, so
the countdown lives in a test rather than a roadmap item); with the compile arm reading
`javac_diagnostic`, the schema bridge is its only registrant, and it sits beside the shipped
`ORACLE` arm (`javac_diagnostic`'s own registration, for relations a post-capture oracle
writer owns). Nothing the shipped driver pins resists this: the load-bearing property is
closure with no skip list, not the arm count, and its javadoc already says later strata land
as registrations. R610's rekey has landed, so the bridge relation is born with the partition
dimension rather than widened after the fact.

**Stored columns are facts; derived columns live in the view.** The schema bridge carries what
the channel's own typed data states: the rejection's kind and variant, `lspCode`,
`(attemptKind, attempt)`, the stub key, the lint rule, the location, and a nullable
`(type_name, field_name)` pair at the DDL's universal grain rather than a rendered coordinate
string. The loader reads that grain off `ValidationError`'s constructors instead of
dot-splitting a rendering back apart, which also gives the carved-out sealed-`Coordinate` item
a cheap landing later (a loader simplification, no column change). The view computes what is a
function of stored columns: `actionable` off kind (step 1's projection, read in one place),
`type` and `directory` as the declared coarse grains of the coordinate pair and `file`, the
rendered `coordinate`, and the canonical `directives` render over an ordered child relation
(the DDL's standing pattern for multi-valued decodes, which also gives the deferred
per-directive dimension a home with no re-key). `message` is a rendering column: display only,
never a dimension, never an agreement anchor, and expected to change text when detections take
over the schema arm, because the legacy report splices coordinates into prose at construction.
Two compile-arm consequences of the shipped relation belong here. The view derives the
compile rows' `severity` from `kind` mirroring `CompileDiagnostic.severity()`, the model's
one home for that projection (`ERROR` to error, every other `Kind` to warning); the shipped
`Diagnostic.Kind` partition pin already fails a build when javac grows a kind, and a one-row
parity assertion between the record's spelling and the view's keeps the two from drifting.
And compile-row absence is javac's own sentinels rather than `NULL` (`"(no source)"` for the
file, `-1` for a `NOPOS` position, the key admitting no `NULL`), so the view's location and
`file`/`directory` projections compare against the sentinels, never `IS NULL`.
Closed `CHECK`s cover only the small projections the model owns (`severity`, `source`,
`kind`); `variant` stays an open column, since a `CHECK` enumerating sealed-hierarchy leaves
would be a hand-maintained second copy of a taxonomy the compiler already enforces.

**The loader is the single exhaustive-switch site.** Deleting the extractors would otherwise
delete the property that a new `Rejection` arm forces a decision in the aggregate, so the
property moves: the bridge loader fills the typed columns through exhaustive sealed switches
with no `default`, at one site, and the rejection-leaf partition pin re-aims at the loader's
column population. Absence discipline moves with it: the extractors' uniform
`Optional<String>` becomes SQL `NULL`, and every comparison in the shared `where` mechanism
uses null-safe equality (`IS NOT DISTINCT FROM`), or the aggregate / drill-down parity pin
passes on the aggregate side and fails on the drill-down side.

**What stays out, so this item does not corner R589.** No generic provenance columns
(classifier, trigger, witness) and no occurrence-path key: R589 closed both against a
universal record, and baking them into a bridge relation would pre-build the shape it
rejected. The dimension set here is the read side's; every fact that survives a rejection
under R589 becomes a candidate dimension when its relation lands, not before.

Boundaries and placement, so they are reviewed rather than discovered. `unified-diagnostic-stream`
(R601) collapses the schema-side report channels, which under this design simplifies the
schema-bridge loader (one load instead of two slots plus a never-added third), not the
aggregate; it stays non-blocking. The aggregate's jOOQ queries live in `graphitron-mcp` beside
the tool (the generated classes are the containment, per the rejected-facade reasoning in
`validation-adds-facts`; `graphitron-model` hosts model code only), and the schema-bridge
loader lives with its cadence owner, the workspace layer that already holds the report,
mirroring where R603 put `CompileFacts` for the compile channel. A read-only SQL surface
over the whole fact store, which the H2 spike floats as an agent capability, stays a separate
future item: the derived stratum it would query barely exists yet, and its design question is
different in kind; see the query-language section above. The whole-board context is
`roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.

## Phasing

Ordered by dependency, not by module. The model lifts come first because bridge columns typed
off today's wide shapes mean re-doing the DDL and the loader both.

| # | What | Where | Size |
|---|---|---|---|
| 1 | `RejectionKind` gains the author-fixable projection; `Diagnostics.severityOf`'s comment becomes a read of it | `graphitron`, `graphitron-lsp` | small |
| 2 | `StubKey` splits into two permits, non-null `VariantClass` plus an inline-defer arm; the two `deferred` factories map to their own arm | `graphitron` | small |
| 3 | `CodedRejection` capability lift; `Diagnostics.lspCodeOf` collapses to one `instanceof`; membership partition meta-test | `graphitron`, `graphitron-lsp` | small-medium (42 sites, mechanical) |
| 4 | Bridge DDL: the graph-keyed schema bridge relation, its `directives` child relation, and the `diagnostic` union view (schema arm on the bridge, compile arm on the shipped `javac_diagnostic`), comments per the model conventions; the `BRIDGED` agreement arm with its one registration | `graphitron-model`, `graphitron` | small-medium |
| 5 | The schema-bridge loader at the workspace layer: the single exhaustive-switch site filling typed columns per snapshot, every statement scoped to the session's graph, writing through the session's store handle | `graphitron-lsp` | small-medium |
| 6 | The aggregate and the widened `diagnostics` filters as jOOQ over the view; the dimension enum as the wire-name-to-view-column mapping; tail rule via `HAVING` plus the elided-remainder aggregate; the new counts-only tool | `graphitron-mcp` | medium |

Steps 1 to 3 are each independently defensible and each improve the LSP or the watch formatter on
their own, so they can be carved into separate items if parallelism is wanted. Step 6 stays one
step: the widened filters and the aggregate share the view and one null-safe `where` translation,
and splitting them is how the two parallel filter implementations this design exists to prevent
get built.

**Carved out at review as a dependency, and since satisfied: the retired-directive identity
convergence.** This item once blocked on routing `@notGenerated` and `@lookupKey` through
`Rejection.directiveConflict` so each cause has one identity, because the `directives` dimension
counts only rejections carrying a typed directive list and would otherwise have reported one row
where three rejections concerned the same directive: a confidently wrong count in the very view this
item builds to replace hedged ones. R585 shipped that convergence (see `roadmap/changelog.md`),
converging five spellings rather than the three this item had counted, and it turned out to be a
lift of the input-field resolution path rather than a rewrite of three call sites, which is why it
was its own item. `InputFieldResolution.Unresolved` now carries `(fieldName, SourceLocation,
Rejection)`, and `BuildContext.classifyInputFieldInternal`, the site whose prose-only `Unresolved`
was the reason the identity could not move, mints `Rejection.directiveConflict` for both directives
directly. Nothing here waits on it: the dependency is discharged and `depends-on` is empty.

One adjacent hardening stays non-blocking: `directive-conflict-directives-contract-sweep` (R608,
Backlog) pins the `DirectiveConflict.directives` no-counterfactual-entries contract at every
producer site rather than the one site pinned so far. The pivot's counts are right on today's tree
either way; R608 is what keeps a future producer from quietly corrupting them.

**The dependency chain now: everything in front has shipped, R589 deliberately not.**
The store (R595), the graph partition dimension (R610), and the `javac_` oracle family (R603)
are all Done (see `roadmap/changelog.md`), which is why `depends-on` is empty: the bridge
relation is born with R610's dimension, and R603's dovetail resolved in the recommended
order, so the compile arm reads the shipped `javac_diagnostic` and the fallback (this item
carrying its own compile bridge) never activates. Every substrate this design leans on is now
tree, not plan.
R589 stays a relation, not a prerequisite: it makes classification a derivation over the
store and stops a failed coordinate from discarding what the classifier established, which
widens this dimension set on its own; every fact that survives a rejection becomes a
candidate dimension, and the pivot a measured session actually wanted ("the diagnostics on my
DELETE mutations") becomes expressible. Nothing here reads a classification today, and no
dimension already in the enum changes meaning when R589 lands: its detection-minted relation
replaces the view's schema arm and the schema-bridge loader retires, while the view, the wire
vocabulary, and every pin stay. Every count this item reports is true on today's model; it is
narrower, not wrong.

**Carved out at review: the sealed `Coordinate` component.** A sealed
`Coordinate { SchemaWide | TypeLevel | FieldLevel }` on `ValidationError`, deleting
`WatchErrorFormatter`'s `isTypeLevel` / `typeOf`, now lives in its own Backlog item,
`validation-error-coordinate-sealed`. It is the right lift and the reasoning in "What the model owns"
stands, but it is not a prerequisite here, and it was the only step reaching outside the diagnostics
path. Two reasons it separates cleanly:

- The aggregate does not need it. The schema bridge stores a nullable `(type_name,
  field_name)` pair at the DDL's universal grain, and the loader reads the grain off
  `ValidationError`'s constructors in *one* place; the rendered `coordinate` and coarse `type`
  are view columns computed from the pair, so no dot-split exists anywhere. The spec's
  objection in "What the model owns" is to *duplicating* the predicate, and one loader site is
  not a duplicate until the `Coordinate` lift exists; when that item lands, the loader reads a
  sealed switch instead of the constructors' string-plus-null and no column changes. The absent
  case is uniform under the SQL `NULL` discipline, so "rows with no coordinate are not silently
  lost" pins identically either way.
- Its blast radius is the widest and the least related. Beyond `WatchErrorFormatter` it reaches
  `DiagnosticsTool`'s filter comparison and wire `putIfNotNull`, and six `graphitron` test files
  assert on coordinate strings (`GraphitronSchemaBuilderTest`, `ConditionCommandsPipelineTest`,
  `ConnectionTypeValidationTest`, `TenantScopeValidationTest`, `NodeIdPipelineTest`, and the typed-
  rejection pipeline test). Carrying that into this item would also pull in
  `graphitron-maven-plugin`, which nothing else here touches.

Dropping it leaves this item spanning `graphitron`, `graphitron-model`, `graphitron-lsp`, and
`graphitron-mcp`, which is a defensible blast radius for one item given steps 1 to 3 all exist to
keep the aggregate a projection and step 4 is the first-reader DDL this item exists to consume.

## Tests

The earlier draft pinned at unit tier in `graphitron-mcp` with a hand-built `ValidationReport`;
a store-backed aggregate needs a booted store, so the fixture moves to an SDL schema run
through the real pipeline into the bootstrapped store, which "behaviour is pinned at the
pipeline tier and above" counts as an improvement, not a cost. The tests that carry weight are
the invariant pins, not per-dimension unit tests. One rule binds them all: every pin asserts on
tool answers, never on the engine's internals, and that rule is exactly what makes the tier
move and the later schema-arm swap (R589's detection-minted relation) invisible to this suite:

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
  first-client check), so pin it once and let the tool description read from it. No grain axis is
  needed while every dimension is single-valued; one arrives with the exploded per-directive dimension
  the reviewer decisions defer. Pin the `directives` canonical render here too, since an unsorted join
  is the one way a single-valued column can still split a group.
- **Rejection-leaf partition.** Every `Rejection` leaf declared coded or deliberately codeless
  (step 3), and no typed-key arm reachable from the prose path (which the directive-convergence
  dependency delivers; this pin is what stops it regressing afterwards). The pin aims at the
  bridge loader's column population, the single switch site, because an unwritten column cannot
  be observed from stored strings.
- **Rows with no coordinate are not silently lost.** Warnings and compile diagnostics carry no
  coordinate, so coordinate-reading dimensions yield a stated absent bucket rather than dropping
  rows out of the totals.
- Live-server tier: the `GraphitronMcpServerTest` tool-list assertion gains
  `diagnostics.aggregate` (it pins the list with `containsExactlyInAnyOrder`, so it fails until
  updated), plus one end-to-end call asserting the structured shape and the snapshot axes.
- `ServerInstructionsTest` needs no new case, but it gates this item's discovery surface and is the
  reason the routing line and the manual row are not optional: see Implementation sites. Its existing
  assertions cover the new tool by construction, since it derives the advertised surface from a booted
  server rather than a hand-written list.

Steps 1 to 3 pin at their own layers: `RejectionKindProjectionTest` for the actionable projection,
and the existing LSP tests (`RejectionSeverityCoverageTest` reads each arm's stable code today) for
the collapsed `lspCodeOf`.

## Implementation sites

- `graphitron-model.sql`: the graph-keyed schema bridge relation, its `directives` child
  relation, and the `diagnostic` union view (compile arm selecting from the shipped
  `javac_diagnostic`), commented per the model conventions (the comment-coverage gate reads
  them).
- The agreement driver in `graphitron`'s capture test root: the `BRIDGED` registration arm,
  its honesty javadoc, and the schema bridge's registration.
- A loader class at the workspace layer in `graphitron-lsp`: the single exhaustive-switch site,
  writing per snapshot (schema channel only; the compile channel's writer, `CompileFacts`,
  already ships), every statement graph-scoped, through the session's store handle.
- `DiagnosticsTool.java`: the three inline `LinkedHashMap` builders plus `addLocation` /
  `addCompileLocation` become a projection of the `diagnostic` view's rows.
- A new class in `graphitron-mcp` for the dimension enum (wire name to view column), the jOOQ
  aggregate, the null-safe `where` translation, and the wire mapping.
- `GraphitronMcpServer.diagnosticsTool(...)`: widen the input schema with the shared filter axes.
- `GraphitronMcpServer`'s `tools` list: register the new tool. The `GraphitronMcpServerTest`
  tool-name assertion pins the list, so it fails until updated.
- Both tool descriptions: the dimension vocabulary has to be enumerated somewhere for discovery.
- `mcp/instructions.txt`: one routing line pointing at the aggregate when the diagnostic count is
  large. R584 shipped the file's question-keyed routing table over all twelve tools plus the
  `directives` resource, so the line goes into that table's diagnostics entry rather than anywhere
  convenient, and it is written against a composed string carrying a 3600-character ceiling.
- `docs/manual/how-to/mcp-agent-context.adoc`: the per-tool table gains a `diagnostics.aggregate`
  row beside the existing `diagnostics` one. This is the user-facing surface the shipped
  `docs.search` / `catalog.search` tools landed prose on, and the draft omitted it.
- Neither of the two above is discretionary, and this is the sequencing fact most likely to surprise
  the implementer. `ServerInstructionsTest`'s coverage pin is bidirectional and derives the advertised
  surface from a booted server, asserting it against the ambient string per boot and against the
  manual's tool table. Registering `diagnostics.aggregate` therefore fails the forward direction until
  the routing line exists and the manual row exists, and writing either one early fails the reverse
  direction until the tool is registered. R584 mutation-checked exactly this case. Land the tool, the
  routing line, and the manual row in one commit.
- `ValidationReport.canonicalUri` is declared the single canonical URI site, and a `file`
  dimension spanning both channels would group two spellings of one path apart. The compile
  side is already settled in the tree: `CompileDiagnostic.from` normalises the file through
  that site once at the javac boundary, before any sink reads it. This item's remaining duty
  is the schema side plus a parity check that the two arms agree on spelling; `directory`
  chops the canonical form in the view.

## Reviewer decisions

The draft's five open questions, settled at Spec review so the implementer inherits decisions rather
than leanings. Each takes the draft's leaning except where noted. The first-reader substrate
re-decision postdates this review and re-homes the mechanics behind three of these (the
`directives` canonical render is a view column over a child relation, the shared `where` is a
null-safe jOOQ translation, drill-down filters read the view), but no decision below reverses:
the tool boundary, the preset, pure counts, the set-valued `directives` group, and the
`messageTemplate` default all survive with their reasoning intact.

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
  rather than re-derive it. Omitting is the default, not the exception. The fact-base direction adds
  a second, stronger argument for that default: rejections are facts rendered into views, never prose
  composed at the detection site, and a message-template dimension groups on the rendering rather
  than the fact, so its substrate is scheduled to shrink as the architecture deletes composed prose.
- **Pure counts.** Ordering by count already surfaces the leverage; naming a fix strategy would be
  the server asserting the wrong layer.
- **`directives` groups on the set, not on the individual directive.** The component is
  `List<String>`, the only multi-valued dimension in the enum, so it needs a rule and the rule is a
  canonical render: sort, then join, so `[routine, splitQuery]` and `[splitQuery, routine]` cannot
  split a group. One row, one group, counts still sum.

  The co-occurrence cases are where multi-element lists come from, and there the *pair* is the
  cause: `@splitQuery` alone is fine, and attributing `[splitQuery, routine]` to `routine` would
  name the wrong culprit. That reason stands on its own and is what settles the decision.

  The draft gave a second reason that no longer holds and is recorded here so it is not
  re-discovered as a live hazard: the component used to be looser than "the directives on this
  field", and one site (`FieldBuilder`'s `@asConnection` on an inline `TableField`) listed
  `@splitQuery`, a directive that is *absent*, because adding it is the remedy. A per-directive
  count would have reported `@splitQuery` as implicated in diagnostics where writing it is the fix.
  R585 stated the contract on `DirectiveConflict`'s javadoc (every listed directive is applied at
  the rejection's own declaration; a remedy belongs in the prose) and fixed that site, so the
  inversion is not reachable on today's tree.

  So the exploded per-directive dimension is deliberately deferred, not rejected, and on a narrower
  basis than the draft's. The contract is stated but pinned at one of eleven producer sites, which
  is a spot check rather than a contract; sweeping it is R608. Exploding also needs the response to
  declare its grain, since it makes group counts sum above the row count. Revisit as a second
  dimension alongside this one once R608 holds, which is the coarse/fine grain pair this design
  already uses elsewhere.

  Until the sweep lands, the wire gloss says "the directive names identifying this conflict", never
  "the directives on this field".

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
> author wrote), `stubKey`, `directives` (the directive names identifying a conflict, as one value),
> `lintRule`.
>
> *Read off the location*: `coordinate` (a type or `Type.field`), `type`, `file`, `directory`. The
> pairs are coarse and fine grains of one axis; pick deliberately.
>
> *Derived from message text* (not stable across a rewording): `messageTemplate`.

**`mcp/instructions.txt`**, the one line this item contributes to the diagnostics entry of the
question-keyed routing table R584 shipped:

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
