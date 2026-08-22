---
id: R803
title: "Query relations carry facts, not rendered strings"
status: Spec
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Query relations carry facts, not rendered strings

Six places in the fact schema flatten several rows into one delimited string, and one of
them goes further and assembles an English sentence out of the result. A *delimited string*
here means a column like `@service,@table`: the names of several claims, joined with a
separator, stored as a single value. That is a rendering step, not a derivation. It is the
one derivation the store cannot undo on read, which puts it directly against the schema's
own stated discipline that derived reads are views over rows.

The cost is not theoretical. `DiagnosticFacets.DIRECTIVES` offers `directives` as an MCP
facet dimension and documents it as "the directive names identifying a conflict, as one
value", so faceting groups by *combination*: an author cannot ask how many conflicts
involve `@service`, only how many are exactly `@service,@table`. `ClaimFacts` reads
`intent_type_backing_conflict.class_names` and immediately wraps the single string in a
jOOQ `multiset`, which is the consumer saying it wanted rows. And a relation that
aggregates a column stops being joinable on it, so every downstream question about the
aggregated thing has to be answered with string matching or not at all.

Two tells make this concrete rather than a matter of taste, both in
`intent_authored_claim_conflict`:

* The view joins claim triggers with `,` and then, in the same expression, calls
  `REPLACE(g.declared, ',', ', @')` to take them apart again. The `REPLACE` exists only
  because the aggregate destroyed the grain and the view has no way back to the elements
  it just consumed. A relation holding one row per claim would need neither call.
* The `ORDER BY` feeding that render is an eight-branch `CASE` ladder hand-copying
  `AuthoredClaim`'s Java declaration order into SQL, so that a sentence names directives in
  the right sequence. The view's own comment admits this, and the arrangement is pinned by
  `AuthoredClaimConflictsTest`'s hand-written expected strings rather than by anything
  comparing the two orders. A Java enum's declaration order is now schema data, maintained
  by hand in two places, for word order in prose.

The rule this item establishes: a relation in the fact schema states facts at a grain, and
a consumer that wants several of them rendered into one value does that rendering itself.
The store has no business holding prose.

A secondary benefit, worth noting but not the reason: string aggregation is the single SQL
construct in `graphitron-model.sql` with no spelling that both H2 and DuckDB accept
(`LISTAGG ... WITHIN GROUP` is H2-only, `STRING_AGG(x, sep ORDER BY y)` works in both but
only by coincidence of two vendors' extensions). Presentation is where SQL dialects stop
agreeing, so a schema holding only facts is portable as a side effect. That matters if the
docs tooling ever grows a second, non-JVM reader of this file, but this item does not
depend on that happening and should not be reviewed as if it did.

## Deliverable 1: the four rendered columns become rows

Every site is named by symbol; re-measure line positions at pickup.

**`intent_authored_claim_conflict.directives`** (both grains, two `LISTAGG` calls). Drop
the column. The claims themselves are already rows on `intent_authored_type_claim` and
`intent_authored_field_claim`, keyed by the same coordinate this view is keyed by, so a
consumer wanting the directive names joins them. The verdict (`CONFLICT` / `DEFERRED`),
the coordinate, the arity and the source location stay: those are facts, and the
routine-plus-lookup carve-out that decides the verdict is a genuine derivation that belongs
here. Consumers to update: `SchemaQueries` (graphitron-mcp) and
`AuthoredClaimConflicts` (graphitron).

**`intent_authored_claim_conflict.message`** (both grains, two more `LISTAGG` calls plus
the `REPLACE` surgery and the `CASE` ladder). This is the design fork, see below.

**`intent_type_backing_conflict.class_names`** (one `LISTAGG DISTINCT`). Drop the column.
`candidates` already carries the honest count and stays; the class names are rows on
`intent_type_backing` under the same key. Consumers to update: `ClaimFacts`
(graphitron-lsp), which gets simpler because it already wanted a multiset, and
`SchemaQueries` (graphitron-mcp).

**`diagnostic.directives`** (one correlated `LISTAGG` over
`rejection_validation_error_directive`). Those are already rows on their own relation under
the error's key. Drop the column and let `DiagnosticFacets` reach them by join, which is
also what makes the facet dimension answer the question an author actually asks. Removing
a dimension from the MCP facet surface is a consumer-visible change and needs a line in the
item's notes when it lands.

### The design fork: where the conflict message gets rendered

`diagnostic` unions six arms. Five of them (`rejection_validation_error`, `lint_finding`,
`build_warning_no_rule`, `graphql_syntax_error`, `graphql_schema_error`) read `message`
from a captured table column: the generator or the parser produced that text and the store
recorded it, which makes the message a fact about what was emitted. Only the
`intent_authored_claim_conflict` arm manufactures its message inside SQL. So the arm is the
odd one out among its own siblings, and the target shape is for it to look like them.

Two arms are available and the reviewer should press on which:

1. **Mint the message post-capture into a table**, on the capture cadence, from Java that
   sits beside `AuthoredClaim` so the declaration order it depends on cannot drift from the
   enum. `diagnostic` then reads it as a plain column exactly like its five siblings, and
   the diagnostics surface is unchanged from a consumer's point of view. This is the
   recommendation. The open question a reviewer should test it against: the schema file's
   header states two, and only two, reasons a relation may be post-capture (a derivation no
   view can express, or one a view expresses too slowly). "A view can express it but
   shouldn't" is a third reason, and if this arm is right then that header needs extending
   to say so, in the same change. If the reviewer thinks the header is right as written,
   this arm is wrong.
2. **Render at the reading boundary**, in `AuthoredClaimConflicts`, and let the
   claim-conflict arm carry no message on `diagnostic`. Cheaper and needs no new relation,
   but it degrades the diagnostics surface: a row on `diagnostic` with a null message is a
   worse answer than the surface gives today, and every reader of that surface would have
   to special-case one arm.

Whichever arm wins, the `CASE` ladder restating `AuthoredClaim`'s declaration order leaves
the SQL. That is not optional and is not a consequence of the choice.

## Deliverable 2: a build gate forbidding row-to-string aggregation in the schema

A rule with no gate is a rule that holds until the next person who needs a quick string.
Add a schema gate that fails the build when `graphitron-model.sql` uses an aggregate that
flattens rows into a scalar string.

Denylist rather than heuristic, so the failure message can say what to do instead:
`LISTAGG`, `STRING_AGG`, `GROUP_CONCAT`, `ARRAY_AGG`, and the `WITHIN GROUP` clause that
introduces ordered-set aggregation. Name each one; do not try to detect "rendering" in
general.

Placement: beside the existing schema gates in graphitron-model
(`CommentRenderabilityGateTest`, `MaterializeRegistryGateTest`) rather than in roadmap-tool,
whose `check-` steps are for prose and doc drift. The gate reads the DDL text, not the
booted catalog, because the point is to reject the construct at authoring time and a booted
store has already lost the spelling.

**The scan must be lexically scoped, and this is the part that is easy to get wrong.** The
schema file is roughly 90 percent `COMMENT ON` prose, and that prose discusses aggregation
in English: the existing view comments name `LISTAGG` directly while explaining why the
render is ordered the way it is. A naive grep over the file matches those and fails a clean
tree. The gate scans DDL statement regions only, excluding `COMMENT ON` bodies and `--`
comments, which is the same habitat distinction `RoadmapReferenceGuardTest` already draws
between comment regions and string literals. Reuse that reasoning; the splitter has to
respect `''` escapes inside string literals or it will mis-slice the file.

The failure message states the rule (a relation states facts at a grain; a consumer that
wants them rendered into one value renders them itself) and points at the two relations
this item converted as worked examples.

## Tests

* The gate's own test: a fixture DDL fragment using each denied construct fails, one whose
  `COMMENT ON` prose merely mentions the words passes. The second half is the case that
  matters, since it is the one a naive implementation gets wrong.
* `AuthoredClaimConflictsTest` keeps asserting the verdict and coordinate set. Its
  hand-written message expectations follow the render to wherever Deliverable 1's fork puts
  it, and stay hand-written there.
* `TypeBackingTest` (graphitron-model) and whatever covers `ClaimFacts`'s contested-type
  path assert over rows instead of a joined string.
* A `diagnostic`-level test that the claim-conflict arm still carries the same message text
  it does today, so the fork is a move and not a rewrite. Capture the current strings before
  starting.

## Notes

* Not in scope: the `collate` column name and the view column-alias lists, both of which
  are portability findings from the same investigation. They are a separate item if the
  second reader is ever built, and folding them in here would confuse a modelling cleanup
  with a portability change.
* Worth settling before the roadmap-tool and AsciiDoc generation are ported out of the
  Maven build, since the schema reference renderer reads whatever shape these relations end
  up with.

## Retired vocabulary

* `directives` as a column name on `intent_authored_claim_conflict` and `diagnostic`
* `class_names` as a column name on `intent_type_backing_conflict`
* "the sorted canonical render", the phrase `intent_authored_claim_conflict`'s comment uses
  for the `directives` column
