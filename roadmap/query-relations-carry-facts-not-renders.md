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

Six places in the fact schema serialize a collection into a single scalar column, and one of
them goes further and assembles an English sentence out of the result. A *serialized
collection* here means a column like `@service,@table`: the names of several claims, joined
with a separator, stored as one value whose element grain sits inside the string where no
key, constraint or join reaches it.

**The discriminator is atomicity plus key-dependence, not "renders are bad".** An earlier
draft of this item argued that the store has no business holding a render. That framing is
wrong and R804 (the documentation sibling, which states the principle these changes enforce)
demonstrates why: the store legitimately holds renders today. `diagnostic.coordinate` is
`type_name || '.' || field_name`, the `_upper` generated columns are case folds, and five of
`diagnostic`'s six arms carry captured prose in `message`. The line those pass and the six
sites here fail is:

* A column may carry an opaque value the store did not compose, provided nothing joins,
  groups or filters on it. Four DDL comments already spell this as "display material, never
  a dimension"; the item should lift that vocabulary rather than mint a rival one.
* A column anything joins, groups or filters on must be atomic to the engine and a function
  of its relation's own key. `intent_type_backing_conflict` carries the whole lesson in one
  relation: `candidates`, a count, passes; `class_names`, the same set serialized, fails.

That distinction is also what makes the Deliverable 2 denylist coherent. `ARRAY_AGG` is no
delimited string at all, but it is still a collection in a scalar, and that is the property
being denied.

## The columns defend themselves, and the defence is answerable

These are not oversights, and the item is weaker if it treats them as such. Each column's
comment argues a case: a sorted comma-join is one canonical spelling of a set, so two
readers grouping by it "cannot split a group on row order".
`intent_authored_claim_conflict.directives` calls itself "the canonical claim render for
grouping"; `class_names` says "Display and grouping only, never a dimension".

Three answers, in ascending order of how much they cost today:

1. **A serialized set answers strictly less than the rows do.** It answers equality of the
   whole set; the relational form answers that *and* membership, for one join. Group-splitting
   on row order is a query's problem, solved in the query by ordering the aggregation at read
   time, and the store owes the query rows rather than a pre-baked canonical spelling.
2. **The stated line is already broken by a live consumer.** `class_names` draws it at
   "never a dimension". `DiagnosticFacets.Dimension` is documented as "the `diagnostic` view
   column it groups **and filters on**", and `DIRECTIVES` sits in `TYPED_KEY_DIMENSIONS`,
   reaching both `groupBy(groupFields)` and `matchesStored`, which is `isNotDistinctFrom`,
   exact equality. So an author filtering diagnostics by
   `directives=service` gets only the conflicts whose entire set is exactly `service`, and
   silently sees none of the ones that involve it. That is a wrong answer on a live MCP
   surface, not a modelling preference.
3. **The encoding leaks into a consumer's types.** In `ClaimFacts`, `contested` is declared
   `Field<List<String>>` beside `grounded` and `reached`, which are genuine
   `multiset(selectDistinct(...CLASS_NAME))` row lists. `contested` is a one-element list
   holding a joined string, pulled out by `first(...)` into `TypeBlock.contested` and pasted
   into a hover by `DeclarationHovers`. Three sibling fields, one Java type, two meanings.
   (R804 describes this as two hand-maintained parsers in `ClaimFacts` and `SchemaQueries`.
   That is not what is there: neither parses, both pass the string through. The actual
   situation is worse, because nothing decodes and the encoding reaches the consumer surface
   intact.)

Two further tells, both in `intent_authored_claim_conflict`:

* The view joins claim triggers with `,` and then, in the same expression, calls
  `REPLACE(g.declared, ',', ', @')` to take them apart again. The `REPLACE` exists only
  because the aggregate destroyed the grain and the view has no way back to the elements
  it just consumed. A relation holding one row per claim would need neither call.
* The `ORDER BY` feeding that render is an eight-branch `CASE` ladder hand-copying
  `AuthoredClaim`'s Java declaration order into SQL, so that a sentence names directives in
  the right sequence. The view's own comment admits this, and the arrangement is pinned by
  `AuthoredClaimConflictsTest`'s hand-written expected strings rather than by anything
  comparing the two orders. This one is not a rendering point at all, and R804 is right to
  separate it: order is admissible as data (a captured ordinal, a `position` column) and
  inadmissible as a rule copied out of a consumer's vocabulary with nothing binding the
  copy. It is "every invariant has an enforcer", and it has to be fixed whichever way the
  message fork below resolves.

A secondary benefit, worth noting but not the reason: string aggregation is the single SQL
construct in `graphitron-model.sql` with no spelling that both H2 and DuckDB accept
(`LISTAGG ... WITHIN GROUP` is H2-only, `STRING_AGG(x, sep ORDER BY y)` works in both but
only by coincidence of two vendors' extensions). Presentation is where SQL dialects stop
agreeing, so a schema holding only facts is portable as a side effect. That matters if the
docs tooling ever grows a second, non-JVM reader of this file, but this item does not
depend on that happening and should not be reviewed as if it did.

## Deliverable 1: the five serialized columns become rows

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

**`diagnostic.directory`** (`REGEXP_REPLACE(file, '/[^/]*$', '')`, in all seven arms). This
one carries no aggregate and so trips no denylist, which is exactly why it belongs here: a
path is a sequence of segments, and the truncation keeps one of them and discards the rest.
`DIRECTORY` sits in `LOCATION_DERIVED_DIMENSIONS`, so it is grouped and filtered on, and it
answers precisely one question: immediate parent. Not module root, not `src/main` versus
`src/test`, not any other prefix. Those are questions the segments answer and the
truncation cannot, and choosing among them is the query's business. Drop the column; the row
already carries `file`, and a consumer grouping by directory does its own truncation at the
depth its question needs.

Distinguish this from `diagnostic.coordinate` (`type_name || '.' || field_name`), which is
also a composite, also a dimension, and **stays**. Its atoms ride along on the same row and
`TYPE` is its own dimension, so every question the parts answer is still answerable. That
pair is the item's own control: the rule is not "no composites", it is "no collection you
cannot get back out".

### The design fork: where the conflict message gets rendered

`diagnostic` unions seven arms. Six of them (`rejection_validation_error`, `lint_finding`,
`build_warning_no_rule`, `graphql_syntax_error`, `graphql_schema_error`,
`javac_diagnostic`) read `message` from a captured table column: the generator, the parser
or javac produced that text and the store recorded it, which makes the message a fact about
what was emitted. Only the `intent_authored_claim_conflict` arm manufactures its message
inside SQL. So the arm is the odd one out among its own siblings, and the target shape is
for it to look like them.

Two arms are available and the reviewer should press on which:

1. **Mint the message post-capture into a table**, on the capture cadence, from Java that
   sits beside `AuthoredClaim` so the declaration order it depends on cannot drift from the
   enum. `diagnostic` then reads it as a plain column exactly like its five siblings, and
   the diagnostics surface is unchanged from a consumer's point of view. This is the
   recommendation, and R804's closing finding is what makes it cheap: name the render's
   input correctly and this arm needs no new rule. The input is `AuthoredClaim`'s
   declaration order, which is not a captured fact and is not in the store at all, so no
   view can express the render. That is the schema header's *existing* first reason for a
   post-capture relation, and the header extension an earlier draft of this item posed as an
   open question is unnecessary. A reviewer should still check that reading rather than take
   it, since it is the whole justification for the arm.
2. **Render at the reading boundary**, in `AuthoredClaimConflicts`, and let the
   claim-conflict arm carry no message on `diagnostic`. Cheaper and needs no new relation,
   but it degrades the diagnostics surface: a row on `diagnostic` with a null message is a
   worse answer than the surface gives today, and every reader of that surface would have
   to special-case one arm.

Whichever arm wins, the `CASE` ladder restating `AuthoredClaim`'s declaration order leaves
the SQL. That is not optional and is not a consequence of the choice.

## Deliverable 2: a build gate forbidding collection-valued columns in the schema

A rule with no gate is a rule that holds until the next person who needs a quick string.
Add a schema gate that fails the build when `graphitron-model.sql` uses an aggregate that
serializes a collection into one scalar column.

"Collection in a scalar" rather than "row-to-string", because that is the property the
discriminator above actually denies, and it is what puts `ARRAY_AGG` on the list despite it
producing no string.

Denylist rather than heuristic, so the failure message can say what to do instead:
`LISTAGG`, `STRING_AGG`, `GROUP_CONCAT`, `ARRAY_AGG`, and the `WITHIN GROUP` clause that
introduces ordered-set aggregation. Name each one; do not try to detect "rendering" in
general, which would be both unimplementable and wrong, since the schema holds renders that
pass.

What the gate must **not** catch, and the test should pin each: the `_upper` generated
columns (`UPPER(...)` is a scalar function on one column of the same row),
`diagnostic.coordinate` (`||` concatenation of two columns of the same row), and ordinary
`COUNT`/`MAX`/`MIN`/`SUM` aggregates including `intent_type_backing_conflict.candidates`.
All are functions of the row's own key and atomic to the engine.

**A disclosed gap, and the item should not pretend otherwise.** The gate catches aggregates
and nothing else, so it would not have caught `diagnostic.directory`: a row-local
`REGEXP_REPLACE` that truncates a path is a collection in a scalar with no aggregate
anywhere. Deliverable 1 removes that column; nothing stops the next one. Detecting
"serialization" in a scalar expression is not mechanizable, which is why R804's prose is the
real enforcer for that half and the denylist covers only the half that is. State the gap in
the gate's own javadoc rather than leaving a reader to infer that a green gate means a clean
schema.

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

The failure message states the rule (a column anything joins, groups or filters on must be
atomic to the engine and a function of its relation's own key) and points at
`intent_type_backing_conflict`'s surviving `candidates` column as the worked example of what
to write instead. Once R804 lands, it should point at the fact-model page rather than
restate the rule; the gate's message and that page are the same rule with two audiences.

## Tests

* The gate's own test: a fixture DDL fragment using each denied construct fails; one whose
  `COMMENT ON` prose merely mentions the words passes; and one carrying each of the
  admissible shapes above (`UPPER` fold, `||` coordinate, `COUNT` arity) passes. The last
  two halves are what matter, since they are what a naive implementation gets wrong.
* A `DiagnosticFacets` test that filtering by a single directive returns the conflicts
  involving it. That case returns nothing today (`isNotDistinctFrom` against the joined
  set), so write it as a failing test first and let the conversion turn it green: it is the
  one place the modelling defect is visible as a wrong answer rather than as friction.
* A `DiagnosticFacets` test that grouping by directory at a depth other than immediate
  parent is expressible. `DiagnosticFactsTest` currently asserts `getDirectory()` directly,
  so that assertion moves to whatever the consumer computes.
* `AuthoredClaimConflictsTest` keeps asserting the verdict and coordinate set. Its
  hand-written message expectations follow the render to wherever Deliverable 1's fork puts
  it, and stay hand-written there.
* `TypeBackingTest` (graphitron-model) and whatever covers `ClaimFacts`'s contested-type
  path assert over rows instead of a joined string. `TypeBlock.contested` becomes a
  `List<String>` matching its `grounded` and `reached` siblings, and `DeclarationHovers`
  joins it for display at the point of display.
* A `diagnostic`-level test that the claim-conflict arm still carries the same message text
  it does today, so the fork is a move and not a rewrite. Capture the current strings before
  starting.

## Relationship to R804

R804 is the documentation sibling and states the discipline this item enforces. The
division: this item converts the sites and builds the gate, that one writes the principle
into `fact-model.adoc` and `naming-the-row.adoc`. Neither blocks the other, but R804 names
this item's gate as its enforcer and asks to land its paragraph in the same window as the
gate commit so the page never ships carrying a forward-looking claim. Whoever implements
this should say so on R804 when the gate lands.

Round 1 of this item owed R804 two corrections and **R804 has taken both**: the
`ClaimFacts` / `SchemaQueries` citation no longer claims two hand-maintained parsers (there
are none; both pass the string through, which makes the "boundaries decode and encode"
point stronger rather than weaker), and its `class_names` exemplar now carries the live
wrong answer on the MCP filter surface. Nothing outstanding there; a reviewer should not go
looking.

One correction is still owed, from round 2. R804's placement paragraph asks for "one
sentence scoping the `diagnostic` exemption", on the grounds that "the largest single
offender sits on the exempted relation". That sentence is right and can now be written from
an exemplar instead of asserted: `diagnostic.directory` fails the discipline and
`diagnostic.coordinate` passes, on the same exempted relation. Two columns of one relation
landing on opposite sides demonstrates that the exemption covers a relation's name and
population and not what its columns may hold, which is exactly the inference R804 is trying
to block.

## Notes

* R807 is the sibling for the `diagnostic` view's unbound `Rejection.*` string literals,
  found in the same pass over that view. Kept separate because it is an enforcer problem
  rather than a collection-in-a-scalar one, but sequenced behind this item: if the message
  fork mints post-capture from Java, the variant mints with it and R807 largely dissolves
  into this item's implementation. Say so on R807 when the fork resolves.
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
* `directory` as a column name on `diagnostic`, and "renderings of the stored pair and the
  stored file path", the phrase the view's comment uses to cover `coordinate` and
  `directory` together. `coordinate` survives and needs its own sentence saying why.
* "the canonical claim render for grouping", the phrase
  `intent_authored_claim_conflict.directives`'s comment uses for itself, and the "canonical
  render" / "cannot split a group on row order" argument it shares with `class_names` and
  `diagnostic.directives`. Three comments carry it; all three go with their columns.
