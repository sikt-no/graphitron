---
id: R569
title: "Violation facts in the store; the MCP aggregate is their first reader"
status: In Progress
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-11
---

# Violation facts in the store; the MCP aggregate is their first reader

The `diagnostics` tool is entry-at-a-time only. It projects every validation error, build
warning, and generated-code compile diagnostic into a flat list, filters on exactly two axes
(`severity` and one exact `coordinate`), and pages 100 entries at a time. On a healthy schema
that is the right shape. On a large consumer schema mid-migration, where the error count runs
to the hundreds, it is the wrong shape: the first question an agent has is "what is broken,
in what proportion", and the tool can only answer it by handing over every entry and letting
the agent re-derive the shape in prose.

Two things ship here, and the model half leads, which is why it leads the title. The target
pipeline is fact gathering, then validation, then planning, then generation, each phase reading
the store and enriching it with derived facts the later phases consume. A violation is the
validation phase's derived fact, and today it is the one product of that phase that never
becomes one: verdicts are minted as values of the sealed `Rejection` hierarchy, carried in a
`ValidationReport`, and the store never hears of them. That hierarchy is transitional
vocabulary the strangler dissolves arm by arm, so an aggregate designed against the report
would be a new reader of a retiring surface. This item therefore owns violations as a fact
family: it lands the store's diagnostics stratum (a derivation view for the first store-native
family, per-vocabulary transcription relations for the rest, one union view over all of them
plus the shipped compile arm) and builds the aggregate as that stratum's first reader.

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

Most of the aggregation needs no new validate-time arm. The typed components below are the
column inventory: what the residue loader destructures into the store during the transition,
and what a store-native detection mints directly once its family migrates:

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
example, but the residue the `messageTemplate` reviewer decision measures is smaller again, which is one
more argument for that decision's default of omitting the dimension.

## The sealed hierarchy is not the investment surface

An earlier revision of this section found four places where the model "knows a fact and
discards it" and prescribed Java lifts on the sealed hierarchy for three of them: an
author-fixable projection on `RejectionKind`, a two-permit `StubKey` split, and a
`CodedRejection` capability interface across the nine `lspCode()`-bearing sub-seals. All three
are dropped, and since that reverses a settled reviewer decision, the reversal is recorded in
Reviewer decisions rather than edited away.

The reason is direction, not effort. The sealed `Rejection` hierarchy is the legacy walk's
verdict vocabulary, scheduled to dissolve as detections migrate store-native; a capability
interface or a permit split is an investment in the surface being deleted, and widens exactly
the churn the fact-base pivot exists to end. Each dropped lift keeps its invariant, paid for
where the architecture is going instead of where it has been:

1. **The actionable / deferred binary becomes a view column**, a `CASE` over the stored
   `kind`. That is a second evaluation of the predicate `Diagnostics.severityOf` records in a
   comment ("Deferred is Error rather than Warning"), so it gets the same one-row parity
   assertion this design already gives the compile arm's `severity` against
   `CompileDiagnostic.severity()`: the SQL spelling and the Java spelling pinned to each
   other, and no new Java projection on the hierarchy.
2. **The `StubKey` split is dropped clean.** The residue loader reads the producer's
   discrimination at its single switch site and stores the absent arm as SQL `NULL`, the
   store's uniform absence discipline. One loader site is not a duplicate until a second
   reader exists, which is verbatim the reasoning that carved out the sealed `Coordinate`
   lift.
3. **`lspCode()` membership binds in the test tier, not the type system.** The loader's
   exhaustive leaf switch catches a new leaf; it does not catch an existing leaf *gaining* an
   `lspCode()` that `Diagnostics.lspCodeOf`'s nine-arm chain does not know, and today nothing
   fails on that: `RejectionSeverityCoverageTest` walks every leaf permit reflectively but
   asserts only severity. Extend that walk so every leaf declaring an `lspCode()` surfaces it
   both as the LSP diagnostic's code and as the loader's `lsp_code` column: one test binds
   both readers of the retiring hierarchy to one membership fact, with no production
   interface on a surface being deleted.
4. **`coordinate` stays carved out** into `validation-error-coordinate-sealed`, unchanged.
   The residue stores the nullable `(type_name, field_name)` pair at the DDL's universal
   grain, the loader reads the grain off `ValidationError`'s constructors in one place, and
   the rendered string is a view column, so no dot-split exists anywhere on this item's path;
   when that item lands, the loader reads a sealed switch instead of the constructors'
   string-plus-null and no column changes.

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
`diagnostic` view (the diagnostics-stratum section below): `severity`, `source`, `actionable`,
`kind`, `variant`, `lspCode`, `attemptKind`, `attempt`, `stubKey`, `directives`, `lintRule`,
`coordinate`, `type`, `file`, `directory`, `messageTemplate`. `where` filters on the same
columns that `groupBy` groups on, which collapses this item's part 3 (drill-down filters) and
part 1 (the aggregate) into a single mechanism instead of two parallel filter implementations.
A preset report is then literally a named `(groupBy, where)` tuple, so a report and an ad-hoc
pivot cannot drift in behaviour.

**An enum of labels is the right shape here, and the sealed switches live in the residue
loader.** This module already settled the question: `EdgeKind`'s javadoc calls itself
"legitimately an `enum` (a label), not a sealed hierarchy" because the varying-shape part lives
in `NodeRef`, so the enum "carries no kind-dependent nullability", and names that as the
resolution of the sealed-over-enum tension. A pivot dimension is the same: every value has the
identical shape, a mapping from dimension name to one column of the `diagnostic` view, no value
carries different data, so a sealed hierarchy buys nothing. The constraints that keep it from
smuggling in a stringly side-channel now hold at two sites, the residue loader that writes the
transcription columns and the views that compute the derived ones (see the diagnostics-stratum
section below):

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
job, deferred to its own item; see the diagnostics-stratum section below.

**Enabling refactor.** `DiagnosticsTool` currently builds wire `LinkedHashMap`s inline from
three sources; the compile source now arrives typed (`CompileDiagnostic`, shipped with the
`javac_` family), while validator errors and build warnings still project straight off the
report with no intermediate typed representation. The union the earlier draft gave to a
package-private `DiagnosticRow` record is
now the `diagnostic` view (the diagnostics-stratum section below), and both
tools read it: the existing per-entry wire mapping projects off the view's rows, and the widened
filters and the aggregate share one null-safe `where` translation. The old sequencing constraint
(model lifts before DDL) dissolves with the lifts themselves: the DDL types its columns off the
rejection records' own components, which the residue loader destructures directly, and the one
wide shape left (`ValidationError`'s string-plus-null coordinate) is absorbed at the loader per
the `Coordinate` carve-out, so nothing wide bakes into the store.

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

## The store's diagnostics stratum

Between this item's first draft and now, the substrate shipped: the store itself (R595), the
graph partition dimension (R610), the `javac_` oracle family (R603), and R589's claim views
with `AuthoredClaimConflicts`, the store's first reader (all Done; see `roadmap/changelog.md`).
Everything the earlier draft hand-built is native SQL over a relation: `groupBy` is
`GROUP BY`, `where` is `WHERE`, `minCount` is `HAVING`, the tail accounting is a second
aggregate over the elided remainder, and the `DiagnosticRow` record and the Java grouping
engine are never built. The wire contract does not move: the closed dimension set, the
zero-argument triage preset, exact counts, tail honesty, and the single-valued grain are
fixed, and every invariant pin asserts on tool answers, so the contract cannot tell which
substrate answered. That this item is no longer the store's first reader is a straight
improvement in its risk: the query vocabulary here (`GROUP BY`, `HAVING`, a union view) is
already exercised in production by a reader whose wrong answer would cost wrong generated
code, where a wrong answer here costs a bad triage.

What this item adds is the diagnostics stratum: violations as facts. Five arms sit behind one
`diagnostic` union view, and nothing reads a base relation directly.

**One relation per vocabulary arm, no writer at all for derivation arms, and the sealed switch
as the family boundary's enforcer.** The union view, with `source` carried per arm in the
shipped `graphql_directive_site` mould, is what the aggregate and the widened `diagnostics`
filters read. The arm-to-`source` mapping is many-to-one and stated so it is reviewed: the
pilot view, the residue, the lint arm and the advisory arm all carry `source = 'schema'`,
only `javac_diagnostic` carries `compile`, so a new arm grows neither the closed `source`
taxonomy nor the wire vocabulary. Behind the view:

- **The store-native pilot: `intent_authored_claim_conflict`**, a derivation view over R589's
  claim views carrying the claim-conflict family's violations as rows (the pilot section
  below). A view has no writer, so the one-writer rule is satisfied trivially, and the
  staleness clause below does not apply to it: a derivation is exactly as fresh as the
  transcriptions under it.
- **The transcription residue: the `rejection_` family**, the legacy walk's rejections loaded
  per snapshot at the dev session's cadence, one loader, transitional by construction (the
  residue section below).
- **The lint arm: `lint_finding`, in the linter's vocabulary, with its own relation and
  writer.** The family is `lint_`, named in the doctrine's mould for the oracle whose words
  the rows are written in (`lint_rule` is `LintRule.id()`), parallel to `javac_`. An earlier
  revision put build warnings in the residue relation, and that was a nullable bag with two
  vocabularies: `kind`, `variant`, `lsp_code`, `attempt_kind`, `stub_key` NULL on every
  warning row and `lint_rule` NULL on every rejection row, the null pattern encoding which
  arm a row is on, which is the sealed-over-shared-fields smell in DDL form. The severity
  argument this spec already makes against fusing compile and schema applies unchanged (a
  lint finding's severity is a function of its `LintRule`, never a rejection kind). And lint
  rules are predicates over classified facts, a natural early candidate for a store-native
  derivation, which they cannot become independently while sharing a relation with the
  rejection residue. The union view is what makes the split free.
- **The advisory arm: `build_warning_no_rule`, in the sealed warning hierarchy's own
  vocabulary.** `BuildWarning.NoRule` is the deliberate arm for an advisory not attributable
  to a lint rule, with two live producers (`GraphitronSchemaBuilder`'s `@table`-on-input
  deprecation announcements and `EntityResolutionBuilder`'s federation compound-key
  advisory), and the shipped tool surfaces it, pinned (severity `warning`, no `lintRule`
  key), so a stratum without an arm for it is a shipped tool quietly dropping rows. It gets
  its own relation on the same per-arm asymmetries that split lint from the residue, and
  there are two of them, not one: severity (warning by construction, a third rule beside
  kind and rule) and suppressibility (`withLintFindings`'s filter is keyed on the rule id,
  so `lint_finding` rows are post-suppression survivors while advisory rows never met the
  filter, and one relation holding both would give "absent from this relation" two
  meanings). Folding it into `lint_finding` would move the NULL-encodes-the-arm smell
  inside the arm rather than removing it, and converting the two producers to `LintFinding`
  is not a refactor: it would make an unsuppressible deprecation announcement silenceable
  through `disabledRules`, a change in the announce posture, and it breaks
  `FixtureWarningsGateTest`'s `isInstanceOf(NoRule)` assertion in the example module. The
  family is `build_warning_`, named in the `rejection_` mould for the sealed hierarchy whose
  words the row is: message and location are `NoRule`'s entire component list, and the arm
  selector sits in the relation name per the `jvm_scalar_type_field` precedent, since the
  sibling arm lives in `lint_`. It is deliberately not in `walk_`, although the two shipped
  producers both live in the legacy walk: a family may not be named for its producer (the
  `validator_` rejection again), the loader forks on the sealed arm rather than on who
  minted the value, so a producer-named family would silently lie the day a non-walk site
  mints a `NoRule`, and unlike its three loaded neighbours this arm is permanent, since both
  producers outlive the walk (a deprecation announcement retires with its directive, the
  federation advisory with nothing). Its DDL comment therefore states that it has no
  removal criterion, instead of borrowing a retirement clock it would falsify.
- **The compile arm: the shipped `javac_diagnostic`** (R603, Done; see
  `roadmap/changelog.md`), read from day one; that item's dovetail picked the fork where this
  item's compile bridge is never built, so the fork is settled and its fallback moot. Behind
  the relation sits `CompileDiagnostic`, the single flattening at the javac boundary that the
  console block, the current MCP tool, and `CompileFacts` all read; this item's view becomes
  the fourth reader of the same spelling.

The per-vocabulary split keeps the `source` boundary honest: for rejection rows severity is a
function of the rejection's kind, for lint rows of the rule, for advisory rows warning by
construction, for compile rows javac's independent verdict, and one relation holding any two
would give one column two meanings. The
split also holds without anyone scheduled to replace an arm: a later detection that derives
its family store-native gets its own view arm rather than contending for an existing one.

**The store is shared and persistent now, and the loaded arms inherit both facts.**
R610 (shipped) moved the persisted store to a per-user cache shared by every module of a
workspace, keyed the SDL families by a leading `graph_name`, and made refresh
ownership-scoped. The `rejection_`, lint and advisory relations inherit the dimension on the
same reasoning `javac_diagnostic` shipped with: `graph_name` leads their keys with the structural
FK to `store_graph`, every loader statement is scoped to the session's graph (an unscoped
delete in a shared store is one module erasing another's diagnostics), and the relations pass
R610's schema gate without an exemption. The view's arms carry `graph_name` through, and the
MCP read site filters to the reading session's graph; a graph dimension on the wire waits for
a multi-graph workspace to want it. Persistence adds one honesty clause, not a mechanism:
loaded rows from a previous session survive a restart until the first snapshot's graph-scoped
delete-and-reload replaces them, so the tool's existing snapshot availability and freshness
axes are what keeps a stale aggregate from reading as current. The clause covers loaded arms
only: the pilot view derives from transcriptions the capture itself refreshes, and the
compile arm's lifecycle shipped with its relation (rows exist only between a dev session's
compile round and the graph's next generation, and a batch run's partition stays empty rather
than claiming anything it cannot know, so an empty compile arm is honest emptiness, never
staleness).

**The reader goes through the session's handle, never the file.** R610 opens the shared store
in mixed mode, falls back to a module-local in-memory store on any cache trouble, and stamps
the store's compatibility into the file path, so a reader that opened the persisted file
itself could be reading a different store than the one the session writes. The shipped
`CompileFacts` already holds this contract on the write side (it takes the dev session's
store handle); the residue and warning loaders hold it beside `CompileFacts`, and the aggregate
and the widened filters hold the same contract on the read side.

**Who owns that handle, since this item is the first reader outside `graphitron`.** The owner
is `DevMojo`, not the workspace: it opens `sessionStore` once at startup, closes it in
`cleanup`, and already hands `sessionStore.dsl()` to `CompileFacts` at construction. `Workspace`
holds no handle and should not start: it is a sink for the report and the compile round alike,
and giving a sink the store handle is what would make "one handle, shared by every writer and
reader" false by adding a second owner. So the plumbing is explicit rather than assumed, and it
is the one part of this design that reaches a module the rest of the item does not touch:

- The write side needs one seam and no new owner. The loaders home in `graphitron` (see the
  placement note below) and take a `DSLContext` the way `FactCapture.capture` does, so
  `DevMojo` constructs them beside `CompileFacts` at the existing site and calls them where it
  already calls `Workspace.setBuildOutput`. Their inputs are the two pre-fuse lists, each for
  a different reason. The residue loader takes the walk's error stream, never the assembled
  report, so the pilot family's detection-minted errors are structurally absent from its
  input. The warning loader takes the suppression-filtered warning list `withLintFindings`
  returns, never a pre-suppression stream: suppression is applied there over the combined
  list, before the report is assembled, which is the whole reason build-side suppression
  rides to the tool for free, and a loader reading an earlier stream would resurrect disabled
  findings on the wire. `buildOutput` holds both lists separate one line before it fuses them
  into `ValidationReport.from(errors, warnings)`, so `BuildOutput` exposes both alongside the
  fused report and each loader's partition is structural at that single site (the residue
  section below).
- The read side needs one edit: `GraphitronMcpServer`'s full constructor gains the handle, and
  `DevMojo`'s single construction site passes `sessionStore.dsl()`. `graphitron-mcp` imports
  neither `no.sikt.graphitron.model` nor `DSLContext` in main sources today, so this is that
  module's first store dependency and its generated-classes containment argument applies here
  first.
- The live path never degrades, but the two unit-tier boots are store-less by design and set
  the tool's one degraded answer. `DevMojo` opens the store unconditionally and `openAt` falls
  back to an in-memory store rather than failing, so a dev session always has a handle. The
  handle-less servers are the test boots this item's own pins make mandatory:
  `GraphitronMcpServerTest`'s tool-list boot (`new GraphitronMcpServer(loopback(0), new
  Workspace())`) and `ServerInstructionsTest`'s helper (the full constructor with nulls and a
  bare `Workspace`), both of which must advertise `diagnostics.aggregate` after this item. So
  the short constructor passes no handle, and a call without one **refuses**: a tool error
  naming the missing store handle, never a count. An earlier resolution reused the snapshot
  axes for this answer, and the reviewer's counterexample kills it: `writeSnapshotAxes` is
  exhaustive over `LspSchemaSnapshot` and says nothing about a store handle, so on a
  workspace that published a build with no handle wired (exactly what the parity-test
  template builds before its adaptation) the aggregate would report a built, current
  snapshot beside zero groups, and zero groups from a missing store reads identically to
  zero groups from a clean schema, breaking "a truncated aggregate never reads as complete"
  at the one place it exists to protect. Refusal makes the wiring fact loud instead. It
  covers the store-backed paths of both tools, and no production path meets it, since
  `DevMojo` always passes the handle.

**The residue is transitional, and this passage has now reversed twice, so the lineage is
recorded.** The first draft called the load a bridge: registered under a new `BRIDGED`
agreement arm with a retirement countdown living in a test. That failed because nothing on the
board was scheduled to empty it, so the countdown could never fire: a javadoc claiming
transience over a test that cannot. The revision swung to "permanent, `ORACLE`", and that
failed in the other direction: it promoted a transitional surface into the architecture just
as the fact-base pivot settled the sealed hierarchies as retiring vocabulary. The honest
framing is between the two: the residue is transitional *with a drainage mechanism this item
itself builds*. Each rejection family that migrates store-native gets its own derivation view
arm and leaves the residue, and the pilot below drains the first family on day one.

**Named `rejection_`, for the vocabulary its rows are written in.** The earlier leaning,
`validator_`, names the producing component, which is a role, the form the DDL header's
doctrine explicitly rejects (its own counterexamples are `jooq_` and `extension_`). The rows
are written in the sealed `Rejection` hierarchy's spellings and no other vocabulary: `kind`,
`variant`, `lsp_code`, `attempt_kind`, `stub_key` are all that hierarchy's words. The role
name also has to stay free: after drainage, violations still arise in the validation phase,
so "validator" is a name the permanent store-native side may one day want, and the residue
must not squat on it. Naming the residue for the vocabulary being deleted makes the family
name carry its own retirement clock: when `Rejection` is gone, the name has no referent,
which is exactly the honest signal.

**Its input is the walk's error stream, never the assembled report.** An earlier revision had
the loader read the finished `ValidationReport` and argued complete coverage from it. With a
detection-owned family in the view, that shape needs an exclusion set (which report entries
were detection-minted) that must agree with the view's family coverage, and per-family
drainage means the pair changes on every migration with nothing binding them: a family
migrated in the view but not yet excluded double-counts, the reverse loses rows, and both are
silent. `buildOutput` still holds the streams separate one line before it fuses them
(`GraphitronSchemaValidator`'s list, then `detection.violations()` appended), so the loader
takes the walk's list and the partition is structural at a single call site: whatever a
detection minted was never in the loader's input, and no skip-list exists to drift. The
residue's coverage claim reads accordingly: the walk's own rejections, not "complete coverage
of the report".

**It registers `ORACLE`, honestly, while it lives.** R603 defined the arm for relations a
post-capture oracle writer owns, where no independent second walk can re-derive the verdict
without re-running the oracle; a rejection cannot be re-derived without re-running the legacy
classifier, the write is post-capture by construction, and both of the arm's shipped anchors
are satisfiable by a shrinking relation. What the registration may not claim is permanence,
and what a comment may not claim is transience without an enforcer, which were this passage's
two prior failures. So the drainage gets the enforcer: **a declared-set pin enumerating the
rejection families still routing through the residue**, in the mould of the dimension
partition below. Migrating a family store-native must edit the declaration, and a new
rejection cause cannot silently enlarge the residue. The relation's DDL comment states the
removal criterion structurally and names no roadmap item: a family that acquires a derivation
arm leaves this relation, and the relation retires with the sealed hierarchy whose vocabulary
it transcribes.

**The pilot: the claim-conflict family goes store-native.** R589 shipped the detection as the
store's first reader, but its violations exit sideways: `AuthoredClaimConflicts` reads the
claim views and mints `ValidationError` values into the report, so the one family whose facts
are already relations still reaches every consumer as Java values. This item turns the
derivation into a resident: `intent_authored_claim_conflict`, a view in the `intent_`
stratum, named for the rule in the stratum's own mould (the `intent_` header already
anticipates rules as residents; a `violation_` prefix would be a role name of exactly the
rejected kind). Three constraints shape it:

- **A view, not a table.** The `intent_` header states the rule for its residents: views,
  never tables, so a derivation can never drift stale against the transcriptions it is
  derived from, with materialization admitted only on a stated impossibility
  (`intent_type_domain`'s cyclic recursion). A conflict reduction has no such impossibility.
  Two places the SQL must carry logic that lives in Java today, named so they are checked
  rather than discovered: the routine-plus-lookup carve-out predicate (the
  recognised-but-unsupported pair that mints `Deferred` instead of a conflict), and the
  ordered claim render, which for the view is an aggregate over the grouped claim rows, so
  the residue's `directives` child relation is a residue-only mechanism, not a shared one.
- **`ClaimDomain` reifies as rows in the same commit, and the view joins those rows.** The
  detection's minting is gated on `ClaimDomain` membership, a Java test over the walked model
  whose own javadoc calls it the unreified demand relation. Leaving the gate in Java makes
  the view over-report relative to the report, exactly the divergence the parity pin claims
  to exclude; joining the `intent_` demand views instead would perform the gate-flip
  `ClaimDomain`'s javadoc reserves for follow-up work, over a population `DemandShadowTest`
  measures as *not equal*, which moves the accept line and is out of scope here. So the
  walk's reach lands as rows: **`walk_claim_domain`, its own family**, named for the legacy
  walk whose reach the rows transcribe. It cannot share `rejection_`, whose warrant is that
  its rows carry the `Rejection` hierarchy's spellings and no other vocabulary; membership
  rows carry none of those words. The `walk_` name keeps the same retirement clock: when the
  walk is gone, the family has no referent. **Its writer is the capture-and-detect pass, at
  capture cadence**, and the precise site is
  `FactCapture.detect(DSLContext, GraphIdentity, ClaimDomain)`: `runInternal` has three
  capture-then-detect pairings, while the `detect` overload is one site holding exactly what
  the write needs, and its existing `domain == null` guard makes "no domain, no rows"
  structural. The rows write graph-scoped with the capture's ownership scope, and a batch
  run's store carries them, which is what keeps the pilot view answering in a batch store
  and the claim-conflict family minting in batch builds after the cutover (dev-session
  cadence would have moved the accept line exactly as Out of scope forbids). **Its
  registration widens the `ORACLE` javadoc, and the widening is this item's to write.** Half
  the arm fits exactly: no independent second walk can re-derive the walk's reach from store
  rows, which is what `DemandShadowTest` measures, and `DERIVED` cannot take a relation that
  is non-derivable by design (its own capture-cadence resident, `intent_type_domain`, is
  pinned as a relation whose "cadence and clearing follow the derivation"). The other half
  does not: the arm's lead sentence reads "a post-capture oracle writer", shaped for
  `javac_`, while this writer is capture itself. The arm's anchors are already
  cadence-relative ("the same round reduced two ways, at the oracle's cadence"), so the
  honest fix is the R603 shape, arguing the registration on the record: this item widens the
  lead to an oracle writer at the oracle's own cadence (javac after capture, the walk inside
  it), rather than minting a one-resident arm and surrendering the "no new arm" property.
  The view joins it, the pilot's population is unchanged by construction, and the later
  gate-flip re-points one join at `intent_resolved_*_demand` and drains the relation with
  the gate.
- **Shadowed first, then the cutover.** The claim-view arms' `DERIVED` registrations are
  non-vacuous today only because the Java reduction is an independent second evaluation.
  Flip the report to project the view in the same motion and the anchor collapses to the
  view compared against a projection of itself, which the arm's own javadoc forbids for a
  semantic derivation. So the view lands shadowed against the surviving Java reduction, with
  corpus agreement in the shipped shadow mould, and the report flips only once the anchor is
  re-aimed at an expectation the view does not produce (the corpus-level agreement
  `AuthoredClaimConflictsTest` already uses per arm is the nearest shape). At the cutover,
  `Detection` derives the family's `ValidationError` values from the view's rows and the
  `Conflicted` projection overlay keeps reading the detection unchanged. After it, the
  report *is* a projection of the store for this family, the first of the per-family flips
  the residue's drainage counts, and the walk's stream never contained these errors, so the
  residue partition is untouched by the flip.

**Stored columns are facts; derived columns live in the view.** The `rejection_` relation
carries what the channel's own typed data states: the rejection's kind and variant,
`lsp_code`, `(attempt_kind, attempt)`, the stub key, the location, and a nullable
`(type_name, field_name)` pair at the DDL's universal grain rather than a rendered coordinate
string; the lint arm carries the rule id and location in its own vocabulary; the advisory arm
carries the message and location, `NoRule`'s entire component list. The loader reads
that grain off `ValidationError`'s constructors instead of dot-splitting a rendering back
apart, which also gives the carved-out sealed-`Coordinate` item a cheap landing later (a
loader simplification, no column change). The view computes what is a function of stored
columns: `actionable` off kind (the deferred-versus-rest predicate, pinned by the one-row
parity assertion against `Diagnostics.severityOf`),
`type` and `directory` as the declared coarse grains of the coordinate pair and `file`, the
rendered `coordinate`, and the canonical `directives` render over an ordered child relation
(the DDL's standing pattern for multi-valued decodes, which also gives the deferred
per-directive dimension a home with no re-key). `message` is a rendering column: display only,
never a dimension, never an agreement anchor, and expected to change text as detections take
over rejection families, because the legacy report splices coordinates into prose at construction.
Two compile-arm consequences of the shipped relation belong here. The view derives the
compile rows' `severity` from `kind` mirroring `CompileDiagnostic.severity()`, the model's
one home for that projection (`ERROR` to error, every other `Kind` to warning); the shipped
`Diagnostic.Kind` partition pin already fails a build when javac grows a kind, and a one-row
parity assertion between the record's spelling and the view's keeps the two from drifting.
And compile-row absence is javac's own sentinels rather than `NULL` (`"(no source)"` for the
file, `-1` for a `NOPOS` position, the key admitting no `NULL`), so the view's location and
`file`/`directory` projections compare against the sentinels, never `IS NULL`.
The schema-side arms have no oracle sentinels to borrow, so their absence stays SQL `NULL`
outside the key, and the key question for location-less rows is settled here rather than at
implementation time, where a surrogate counter is what an implementer reaches for. Two
shipped lint producers carry a `null` location deliberately (`SessionStateWarnings` and
`DependencyVersionWarnings` mint whole-build facts with no SDL coordinate), so `file` and
`directory` need the stated absent bucket as much as `coordinate` does, and those rows take
the nearest shipped key shape: an emit-order ordinal under the graph-scoped partition,
`javac_diagnostic`'s tie-breaker.
Closed `CHECK`s cover only the small projections the model owns (`severity`, `source`,
`kind`); `variant` stays an open column, since a `CHECK` enumerating sealed-hierarchy leaves
would be a hand-maintained second copy of a taxonomy the compiler already enforces.

**The loader is the single exhaustive-switch site.** Deleting the extractors would otherwise
delete the property that a new `Rejection` arm forces a decision in the aggregate, so the
property moves: the residue loader fills the typed columns through exhaustive sealed switches
with no `default`, at one site, destructuring the records' own components
(`UnknownName`'s `(attemptKind, attempt, candidates)`, `Deferred`'s stub key, the nine
`lspCode()`-bearing sub-seals matched explicitly), and the rejection-leaf partition pin
re-aims at the loader's column population. Absence discipline moves with it: the extractors' uniform
`Optional<String>` becomes SQL `NULL`, and every comparison in the shared `where` mechanism
uses null-safe equality (`IS NOT DISTINCT FROM`), or the aggregate / drill-down parity pin
passes on the aggregate side and fails on the drill-down side.

**Provenance columns stay off the residue; the pilot inherits them by derivation.** R589
shipped `classifier`, `trigger` and `witness` as per-relation columns on its claim views,
having rejected them as columns on one universal record. The rule still binds and is now
asymmetric in this item's favour: the residue, a transcription of walk-minted values, restates
nothing the claim views own, while the pilot view, being a derivation *over* those views,
carries claim provenance by joining its own sources, which is the fact-base answer the
universal record was rejected for. Every fact that survives a rejection is a candidate
dimension by joining its relation, never by widening the residue.

Boundaries and placement, so they are reviewed rather than discovered. `unified-diagnostic-stream`
(R601) collapses the schema-side report channels, which under this design simplifies the
loaders (one load instead of two slots plus a never-added third), not the
aggregate; it stays non-blocking. The aggregate's jOOQ queries live in `graphitron-mcp` beside
the tool (the generated classes are the containment: R589 rejected a typed store facade because
mediating all access would reconstruct the fixed method vocabulary the store was chosen to
escape and duplicate every relation into a second hand-written surface, while jOOQ's generated
classes already give typed access with no strings and no JDBC; `graphitron-model` hosts model
code only), and the residue and warning loaders live in `graphitron` beside the report's producer.

That placement is the correction of an earlier draft's, and the correction matters enough to
state rather than quietly apply. The draft put the loader at the workspace layer and claimed it
was mirroring `CompileFacts`; R603 decided the opposite and argued it, homing `CompileFacts`
with the round's producer *rather than* at the workspace layer, because the producer owning its
transcription is what makes "one flattening, several sinks" true. The reason transfers whole.
`ValidationReport` is produced by `GraphQLRewriteGenerator.buildOutput`, which returns it in
`BuildOutput`; `Workspace` is one of its sinks, holding it exactly as it holds
`compileDiagnostics`. Homing the loader at a sink would make the store a fourth thing the report
is copied into by a consumer, which is the shape R603 rejected. So the loader sits in
`graphitron`, and `graphitron-lsp` leaves the write side entirely.

One cadence, not two, and the reason is an ordering fact worth stating because it reads the other
way at first. `FactCapture` offers both shapes, `run(Path, ...)` opening and closing its own store
for a self-contained run and `capture(DSLContext, ...)` taking a live handle, and an earlier draft
of this section had the loader take the same pair. The loaders take only the handle arm.
`buildOutput` reaches the store *before* the report exists: it calls
`FactCapture.runWithDetections` (whose violations feed the error stream), that call opens and closes
the store, and only then are `errors`, `warnings` and `ValidationReport.from` assembled. A
self-contained arm would therefore have to reopen the store purely to write, after the pass had
already closed it. So the loaders take a live handle and write at the dev session's cadence
through `DevMojo`'s `sessionStore`, exactly beside `CompileFacts`, and a batch run's loaded
partitions stay empty. That costs nothing real: the only reader is the MCP server, which exists
only in a dev session, and it is the same honest-emptiness posture the compile arm shipped with
rather than a new one. The pilot view needs no cadence at all, which is the quiet payoff of
violations as derivations: a view answers in any store whose facts are captured, and its
`walk_claim_domain` join writes at capture cadence, so even a batch run's store carries the
claim-conflict family. A read-only SQL surface
over the whole fact store, which the H2 spike floats as an agent capability, stays a separate
future item: the derived stratum is only now gaining residents, and its design question is
different in kind; see the query-language section above. The whole-board context is
`roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.

## Phasing

Ordered by dependency, not by module. The pilot leads because the cutover is what proves the
report can project from the store; the residue and the tool follow.

| # | What | Where | Size |
|---|---|---|---|
| 1 | `ClaimDomain` reified as `walk_claim_domain` (its own retiring-vocabulary family, written by the capture-and-detect pass at capture cadence); the `intent_authored_claim_conflict` derivation view, landing shadowed against the surviving Java reduction with corpus agreement | `graphitron-model`, `graphitron` | small-medium |
| 2 | The cutover: `Detection` derives the claim-conflict family's `ValidationError` values from the view's rows, the `DERIVED` anchor re-aims at an expectation the view does not produce, the `Conflicted` projection overlay keeps reading the detection | `graphitron` | small |
| 3 | DDL: the graph-keyed `rejection_` residue and its `directives` child, the `lint_finding` and `build_warning_no_rule` relations, and the prefix-less `diagnostic` union view over all five arms; registrations (transcription arms `ORACLE`, derivation arm `DERIVED`) and the residue's declared-set drainage pin | `graphitron-model`, `graphitron` | small-medium |
| 4 | The residue and warning loaders beside the report's producer: exhaustive-switch sites over the two pre-fuse lists (the walk's error stream for the residue, the suppression-filtered warning list for the warning arms; never the fused report; `BuildOutput` exposes both), every statement graph-scoped, live `DSLContext` only; `DevMojo` constructs them beside `CompileFacts` and calls them where it sets the build output | `graphitron`, `graphitron-maven-plugin` | small-medium |
| 5 | The aggregate and the widened `diagnostics` filters as jOOQ over the view; the dimension enum as the wire-name-to-view-column mapping; tail rule via `HAVING` plus the elided-remainder aggregate; the new counts-only tool; the handle reaching it through `GraphitronMcpServer`'s constructor from `DevMojo`'s one construction site | `graphitron-mcp`, `graphitron-maven-plugin` | medium |

Steps 1 and 2 are the violations-as-facts pilot and could carve into their own item if
parallelism is wanted; steps 3 to 5 depend on nothing in them except the view's existence for
its union arm, so the carve is clean. Step 5 stays one step: the widened filters and the
aggregate share the view and one null-safe `where` translation, and splitting them is how the
two parallel filter implementations this design exists to prevent get built.

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

**The dependency chain: everything this leans on has shipped.** The store (R595), the graph
partition dimension (R610), the `javac_` oracle family (R603), and R589's claim relations are
all Done (see `roadmap/changelog.md`), which is why `depends-on` is empty: the loaded arms are
born with R610's dimension, the compile arm reads the shipped `javac_diagnostic`, and the
pilot derives over relations already in the tree. R589 is more than substrate here: the pilot
is a derivation over its claim views, its detection's typed `Detection` product is the
cutover's seam, and its landing widens the dimension set on its own, since every fact that
survives a rejection is a candidate dimension by joining its relation (the pivot a measured
session actually wanted, "the diagnostics on my DELETE mutations", becomes expressible). Two
corrections an earlier draft recorded about that relationship each collapse to a sentence:
this item is not the store's first reader (`AuthoredClaimConflicts` is, which proved the query
vocabulary in production where a wrong answer costs wrong generated code), and R589 displaced
nothing here (its violations exit as Java values, which is exactly the sideways exit the pilot
closes).

One caution survives from R589's input-field slice: it re-keyed some mints, definition-keyed
for the malformed shape and use-keyed for the cascade, and that moves counts. The `coordinate`
and `type` dimensions keep their meaning; their values for input-field rejections shifted
under it. Every count this item reports is true on today's model.

**Carved out at review: the sealed `Coordinate` component.** A sealed
`Coordinate { SchemaWide | TypeLevel | FieldLevel }` on `ValidationError`, deleting
`WatchErrorFormatter`'s `isTypeLevel` / `typeOf`, now lives in its own Backlog item,
`validation-error-coordinate-sealed`. It is the right lift and its reasoning stands, but it is
not a prerequisite here, and it was the only lift reaching outside the diagnostics
path. Two reasons it separates cleanly:

- The aggregate does not need it. The `rejection_` relation stores a nullable `(type_name,
  field_name)` pair at the DDL's universal grain, and the loader reads the grain off
  `ValidationError`'s constructors in *one* place; the rendered `coordinate` and coarse `type`
  are view columns computed from the pair, so no dot-split exists anywhere. The objection the
  carve-out answers is to *duplicating* the predicate, and one loader site is
  not a duplicate until the `Coordinate` lift exists; when that item lands, the loader reads a
  sealed switch instead of the constructors' string-plus-null and no column changes. The absent
  case is uniform under the SQL `NULL` discipline, so "rows with no coordinate are not silently
  lost" pins identically either way.
- Its blast radius is the widest and the least related. Beyond `WatchErrorFormatter` it reaches
  `DiagnosticsTool`'s filter comparison and wire `putIfNotNull`, and six `graphitron` test files
  assert on coordinate strings (`GraphitronSchemaBuilderTest`, `ConditionCommandsPipelineTest`,
  `ConnectionTypeValidationTest`, `TenantScopeValidationTest`, `NodeIdPipelineTest`, and
  `R58TypedRejectionPipelineTest`). That reason carries the carve-out on its own, and it has to,
  because the draft's second reason no longer holds: it argued that the lift would pull in
  `graphitron-maven-plugin`, "which nothing else here touches", and the store-handle plumbing
  this item needs touches it.

Dropping it leaves this item spanning `graphitron`, `graphitron-model`, `graphitron-mcp`, and
`graphitron-maven-plugin` in main sources, with `graphitron-lsp` touched only in its test tier
(the membership-binding extension of the reflective permit walk). Three modules carry real
work (the pilot, the cutover and the loaders in `graphitron`, the DDL in `graphitron-model`,
the tool in `graphitron-mcp`); `graphitron-maven-plugin` is construction-site edits at sites
the mojo already owns. An earlier revision counted five modules because the model lifts
reached `graphitron-lsp` in main sources; the drops removed that. A reviewer who wanted the
pilot (steps 1 and 2) carved into its own item would have a fair case, and the Phasing note
above already permits it.

## Tests

The earlier draft pinned at unit tier in `graphitron-mcp` with a hand-built `ValidationReport`;
a store-backed aggregate needs a booted store, so the fixture moves to an SDL schema run
through the real pipeline into the bootstrapped store, which "behaviour is pinned at the
pipeline tier and above" counts as an improvement, not a cost. The tests that carry weight are
the invariant pins, not per-dimension unit tests. One rule binds them all: every pin asserts on
tool answers, never on the engine's internals, and that rule is exactly what makes the tier
move and every later drainage step (a residue family migrating to its own derivation arm)
invisible to this suite:

- **Aggregate / drill-down parity.** Filtering `diagnostics` to a group's key returns exactly that
  group's count. This is the pin that makes the per-cluster examples a sample rather than a lossy
  summary, and it is the one test that would catch the two-filter-implementation drift.
  `graphitron-mcp`'s `LintSuppressionDiagnosticsParityTest` is the precedent for this cross-view
  shape, and it is more than that: it already drives the real `GraphQLRewriteGenerator.buildOutput`
  and publishes the result onto a `Workspace` the way the dev loop does, from this module. That is
  the fixture shape the tier move above calls for, working today in the target module, so take it
  as the template, with three steps the template does not carry: its `RewriteContext` leaves
  `storeDirectory` null, so `FactCapture.runInternal` opens a private in-memory store and closes
  it inside `buildOutput()` with nothing surviving for a reader; `RewriteContext.withStoreDirectory`
  was deleted at R610, so the fixture sets the directory through the canonical constructor; and
  with `DevMojo` not in play, the fixture opens its own session handle, invokes the loaders
  itself, and hands the handle to the server.
- **The substrate move breaks four shipped tests, not one, and the migration is stated so the
  cheap escape is closed.** Beyond the tool-list assertion named below, the three
  `GraphitronMcpServerTest` diagnostics cases
  (`diagnosticsReturnsMappedErrorsAndReportsSnapshotFreshness`,
  `diagnosticsProjectsLintRuleIdForLintFindings`, `diagnosticsFiltersBySeverity`) boot the
  short constructor over a hand-built `ValidationReport` and assert on returned rows, and
  `LintSuppressionDiagnosticsParityTest` calls `diagnostics` handle-less over a published
  build. After the move all four go through the store, so under the refusal all four get a
  tool error, and wiring a handle does not repair the three unit-tier ones: the loaders read
  the pre-fuse lists, and a hand-built report has no walk behind it. All four migrate onto
  the pipeline-run template above: the parity test gains the store directory, the session
  handle, and the loader invocations; the three unit cases rebuild their seeded reports as
  SDL fixtures whose real pipeline run produces the rows they assert, including the seeded
  rule-less advisory, which the rebuilt fixture produces through a real producer (`@table`
  on an input type is the cheap one) and which must keep surfacing with `severity: warning`
  and no `lintRule` key. The escape an implementer under time pressure will reach for,
  keeping the report projection for handle-less callers, is rejected here by name: a second
  projection beside the view is exactly the two-implementation drift the aggregate /
  drill-down parity pin exists to catch, so the refusal has no fallback.
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
  (via the membership-binding walk, not a capability interface), and no typed-key arm reachable
  from the prose path (which the directive-convergence dependency delivers; this pin is what
  stops it regressing afterwards). The pin aims at the residue loader's column population, the
  single switch site, because an unwritten column cannot be observed from stored strings.
- **Pilot shadow agreement, then a re-aimed anchor.** The `intent_authored_claim_conflict` view
  lands corpus-agreed against the surviving Java reduction; at the cutover the agreement
  re-aims at an expectation the view does not produce, so the `DERIVED` registration never
  becomes the view compared against a projection of itself.
- **Residue drainage declared set.** The rejection families still routing through the
  `rejection_` residue are enumerated in one declaration; migrating a family store-native must
  edit it, and a new rejection cause cannot silently enlarge the residue. The set is scoped
  to the `rejection_` residue alone: the advisory arm is permanent and sits outside it, so
  the declaration's count means "still awaiting migration" and nothing else.
- **Rows with no coordinate are not silently lost.** Warnings and compile diagnostics carry no
  coordinate, so coordinate-reading dimensions yield a stated absent bucket rather than dropping
  rows out of the totals. The bucket is wider than coordinate alone: the two whole-build lint
  producers carry no location either, so `file` and `directory` state the same absent bucket
  (the DDL section carries the key convention for those rows).
- Live-server tier: the `GraphitronMcpServerTest` tool-list assertion gains
  `diagnostics.aggregate` (it pins the list with `containsExactlyInAnyOrder`, so it fails until
  updated), plus one end-to-end call asserting the structured shape and the snapshot axes; the
  three diagnostics cases' rebuild is the substrate-move bullet above.
- `ServerInstructionsTest` needs no new case, but it gates this item's discovery surface and is the
  reason the routing line and the manual row are not optional: see Implementation sites. Its existing
  assertions cover the new tool by construction, since it derives the advertised surface from a booted
  server rather than a hand-written list.

The dropped model lifts leave two pins in their place: the `actionable` one-row parity
assertion against `Diagnostics.severityOf`'s deferred-versus-rest predicate, and the
membership-binding extension of `RejectionSeverityCoverageTest`'s reflective permit walk. That
walk asserts only severity today, so an `lspCode()` missing from `Diagnostics.lspCodeOf`
passes silently; the extension surfaces every declared code through both readers, closing the
hole the dropped capability lift would have closed in the type system.

## Implementation sites

- `graphitron-model.sql`: the `intent_authored_claim_conflict` view and `walk_claim_domain`,
  the reified walk-reach rows it joins; the graph-keyed `rejection_` residue and its
  `directives` child; the `lint_finding` and `build_warning_no_rule` relations; and the
  prefix-less `diagnostic` union view over all five arms, its comment stating why it carries
  no family prefix (a read-side union across vocabularies has no family, and no naming gate
  says so mechanically). The DDL header's family enumeration gains the four new families
  (`rejection_`, `lint_`, `build_warning_`, `walk_`); the header names every family and its
  count is part of its prose, so the edit is not optional. All commented per the model
  conventions (the comment-coverage gate reads them).
- The agreement driver in `graphitron`'s capture test root: the `rejection_` residue,
  `lint_finding`, `build_warning_no_rule`, and `walk_claim_domain` under the existing
  `ORACLE` arm (transcriptions of
  verdicts no derivation reproduces, inheriting the arm's two shipped anchors), the pilot
  view under `DERIVED` with its anchor re-aimed at the cutover. No new arm; the cost of
  keeping that property is the `ORACLE` javadoc widening the pilot section argues (the lead
  sentence's "post-capture" becomes the oracle's own cadence).
- `FactCapture.detect(DSLContext, GraphIdentity, ClaimDomain)`: writes `walk_claim_domain`;
  the one site holding exactly what the write needs (`runInternal` has three
  capture-then-detect pairings), its `domain == null` guard making "no domain, no rows"
  structural, inside the capture's graph-scoped ownership.
- `AuthoredClaimConflicts` / `Detection`: the cutover site. The family's `ValidationError`
  values derive from the view's rows; the `Conflicted` projection overlay keeps its seam.
- `ClaimDomain`: reified as rows the pilot view joins; the record's javadoc already carries
  the gate's rationale and removal criterion, which move to the relation comment.
- The residue and warning loader classes in `graphitron` beside the report's producer: the
  exhaustive-switch sites, writing per snapshot (schema channels only; the compile channel's
  writer, `CompileFacts`, already ships), every statement graph-scoped, taking a live
  `DSLContext` and the pre-fuse lists: the walk's error stream for the residue, the
  suppression-filtered warning list for the warning arms, never the fused report.
- `GraphQLRewriteGenerator.BuildOutput`: exposes the walk's error stream and the
  suppression-filtered warning list alongside the fused report, so each loader's input
  partition is structural.
- `DevMojo`: construct the loaders beside the existing `CompileFacts` construction, call them
  where the mojo already publishes the build output to the workspace, and pass
  `sessionStore.dsl()` into the MCP server's constructor. Edits at sites the mojo already
  owns; the store handle and its lifetime are already there.
- `GraphitronMcpServer`'s full constructor: accept the store handle. This is `graphitron-mcp`'s
  first store dependency, so the generated-classes containment argument lands here rather than
  being inherited. The short constructor passes no handle, which is the store-less test-boot
  path; the handle-less answer is stated in the handle-ownership section.
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
  `directives` resource, so the line joins that table beside the existing `diagnostics` entry and
  takes its shape: a question, the tool, and at most one follow-on sentence. The ceiling is real
  but not tight, and the measurement is here so the line does not get over-compressed to fit a
  budget it comfortably clears: `ServerInstructionsTest`'s `AMBIENT_CHARACTER_BUDGET` is 3600, the
  composed string measures 2976 (2722 base, plus the two-newline join and the 252-character
  execute tail), and the draft below is 171, which lands the composed string at about 3148.
  Roughly 620 characters of headroom before the line, 450 after it. Re-measure at implementation
  rather than trusting these figures: the pair this bullet carried until the Spec review (2564
  base, 2817 composed) was R584's own landing measurement, correct when written and falsified by
  R589's sixth slice growing the base file.
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

The decisions below, settled at Spec review so the implementer inherits decisions rather
than leanings. Each takes the draft's leaning except where noted. Two re-decisions postdate this
review: the substrate re-decision re-homes the mechanics behind three of these (the
`directives` canonical render is a view column over a child relation, the shared `where` is a
null-safe jOOQ translation, drill-down filters read the view), and the violations-as-facts
re-argue reverses one decision, recorded in place below. The rest survive with their reasoning
intact: the tool boundary, the preset, pure counts, the set-valued `directives` group, and the
`messageTemplate` default.

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
- **Reversed: the three model lifts are dropped, not kept.** The decision as reviewed kept
  them in this item, reasoning that each "directly removes a prose fallback or an `instanceof`
  copy from the aggregate"; that reasoning assumed the sealed hierarchy was the investment
  surface. The fact-base direction settled it the other way: the hierarchy is retiring
  vocabulary, the lifts are churn on a surface being deleted, and each invariant they carried
  is paid for in the test tier or the view instead (see "The sealed hierarchy is not the
  investment surface"). Recorded as a reversal rather than edited away. The `Coordinate`
  carve-out and the discharged directive-convergence dependency stand unchanged; blast radius
  lands at four modules in main sources, `graphitron-lsp` in test tier only.
- **"Measure then decide" is acceptable for `messageTemplate`, with the rule stated up front.**
  Deferring is legitimate here because the measurement is cheap and the fallback is strictly smaller
  and safe. What a Spec may not defer is *who decides and on what basis*, so: once the
  directive-convergence dependency has landed, measure the surviving `AuthorError.Structural` /
  `InvalidSchema.Structural` residue over the reactor's own fixture corpus. Ship `messageTemplate` only if that residue does not read usefully off `variant`
  alone; otherwise omit the dimension and let those rows cluster on `variant`. Either way the
  implementer records the measurement in the In Review note, so the Done reviewer can check the call
  rather than re-derive it. Omitting is the default, not the exception. The advisory arm widens
  the measurement's basis, and it points the other way, so the implementer inherits the fact
  rather than re-deriving it: advisory rows carry no typed dimension at all (no `variant`, no
  `lspCode`, no `lintRule`, no `attemptKind`), so on any typed `groupBy` they collapse into one
  NULL bucket, and unlike the `Structural` residue this population is permanent. Measure both
  populations; if the dimension is still omitted, the dimension gloss says outright that
  advisory rows group by location only, and the typed / location-derived partition declares it
  rather than implying it. The fact-base direction adds
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

**`mcp/instructions.txt`**, the one line this item contributes to the question-keyed routing table
R584 shipped, sitting directly under its `What is broken right now: diagnostics` entry and matching
that table's shape rather than the tool description's:

> - What is broken in proportion, when there is more than a page of it: `diagnostics.aggregate`.
>   Its group keys feed back into `diagnostics` to read one cluster's entries.

The mechanics (exact counts, the tail rule, `groupBy` and `where`) deliberately stay out of it.
R584's thesis is that ambient carries the question-to-tool mapping and local carries the rest, and
the tool description below already states all of it.

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
  of an enforcer. Typed-key exposure over existing arms is in scope precisely so the aggregate
  can stay a projection; what stays out is inventing new causes or moving the accept / reject
  line. On the pilot the constraint holds by construction: the conflict view joins the reified
  walk-reach rows, never the demand views, so its population is the detection's today, and the
  demand gate-flip stays its own follow-up.
- **The per-family flips beyond the pilot.** This item lands the mechanism (the drainage
  declaration, the residue partition, the derivation-arm mould) and the first flip; each
  further rejection family migrates with its own detection, edits the drainage declaration,
  and inherits the mould.
- LSP-side bulk application of fixes. The workspace-scoped bulk-quick-fix tier is
  `nodeid-migration-quickfix`'s to decide.
- Aggregation over anything but the two channels `diagnostics` already unions (validator
  output and `graphitron:dev` compile diagnostics).

## Review lineage

Three Spec → Ready gate passes so far (all 2026-08-11), each holding the item in Spec; every
finding is folded into the body above, which is the authoritative text, and this note exists
so the next pass knows what not to redo. Pass one verified every named symbol, relation, and
count against the tree and found them right (including the forty-two `lspCode()` sites across
exactly the nine sub-seals listed, the agreement driver's closed four-arm set, and
`buildOutput` holding the walk's list separate one line before the fuse), so that sweep needs
no repeat; its four findings were the two unnamed relations plus the mis-familied walk reach,
that relation's unstated writer and cadence, a no-degradation claim false for the two
store-less test boots, and stale ambient measurements, corrected in place with a re-measure
instruction. Pass two held two of the resulting resolutions to the fire: the
`walk_claim_domain` registration now widens the `ORACLE` javadoc's lead instead of
contradicting it, and the handle-less call refuses instead of answering a count that reads as
a clean schema; it also moved the write to the `FactCapture.detect` overload and prompted
collapsing the in-file review log into this note. Pass three verified pass two's resolutions
against the tree (the widening's grounding, the refusal's shipped `ExecuteTool.error` shape,
the `detect` overload and its `domain == null` guard) and found two holes, both closed above:
`BuildWarning.NoRule` had no arm in the stratum, closed with the `build_warning_no_rule`
fifth arm in the sealed hierarchy's own family, permanent and outside the drainage set (a
principles consult killed the author's first leaning, `walk_advisory`, as a producer-named
family with a false retirement clock, and surfaced suppressibility as the second per-arm
asymmetry); and the substrate move's four-test breakage was unstated, now named in Tests
with the keep-the-report-projection escape rejected by name, alongside the correction that
the warning loader's input is the suppression-filtered list, not a pre-suppression stream.

**Signed off to Ready on the fourth pass**, both pass-three holes verified closed. The
advisory arm's every precedent checks out as cited: `jvm_scalar_type_field`'s comment states
the selector-in-name rule in those words, `FixtureWarningsGateTest` asserts
`isInstanceOf(BuildWarning.NoRule.class)` in the example module, `SessionStateWarnings` and
`DependencyVersionWarnings` both mint `LintFinding`s with a `null` location and say so in
their own javadoc, `javac_diagnostic`'s key ends in the `ordinal` tie-breaker the key
convention borrows, and the shipped `DiagnosticsTool` already spells a warning row as
`source: schema` / `severity: warning` with `lintRule` only on the tagged arm, so the advisory
arm changes no wire vocabulary. The decisive check for the hole was completeness rather than
placement: `DiagnosticsTool` has exactly three loops (`report.errors()`,
`report.warnings()`, `compileDiagnostics()`), and the five arms partition all three with no
channel left over, so the stratum is total against the tool it replaces rather than patched
where a reviewer happened to look. Two non-blocking notes were left with the author, neither
worth another pass: the location-less key sentence reads as a per-row key shape where the
relation has one key throughout (`(graph_name, ordinal)`, already forced by the preceding
NULL-outside-the-key clause), and the `build_warning_` family could add half a sentence on
why not `graphitron_`, whose header definition is narrow enough (decoded directives and macro
provenance) that the exclusion is already mechanical.

