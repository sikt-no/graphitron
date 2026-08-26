---
id: R840
title: "The corpus becomes a folder of self-describing, fact-first SDL documents"
status: In Progress
bucket: architecture
priority: 4
theme: testing
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The corpus becomes a folder of self-describing, fact-first SDL documents

The spec-by-example corpus is the right idea in the wrong container, asserting in a dying
vocabulary. The corpus (`ClassifiedCorpus`) holds 57 annotated fixture schemas that are
simultaneously tests (`ClassifiedDslTest`) and the source of the worked examples on
`reference/code-generation-triggers.adoc`; 32 of them carry a projection query and render onto that
page. Two defects, one per half of that sentence.

The container is Java. Every new example is an edit to a hand-maintained 1783-line Java list,
featuring it on the page is a manual paste loop (run the drift test, copy the block from the failure
message, paste), and the example's own story lives in three parallel habitats: a Java comment
nothing renders, `#` comments in the projection query, and hand prose on the page. The failure
criterion this item exists to close: Java gets written when the vocabulary changes, never when the
corpus grows. If adding a schema document forces a `.java` edit, the vocabulary has leaked into the
container.

The assertion vocabulary is the transitional walk's. `@classified` / `@classifiedType` declare
the walk's dimensional tuple, `ClassifiedHarness` runs "today's classifier" through
`GraphitronSchemaBuilder` (on R682's terminal-deletion list), and the test prelude's enums are
hand-copied mirrors of the walk's sealed enums. R682 already schedules the recast ("onto store
relations and emitted output, or retire with the walk") and names the classified-corpus programme
as the mechanism it follows; what it does not yet have is the successor form.

## Goal

**What changes for a consumer of graphitron when this lands: nothing.** No generated output moves,
no directive changes meaning, no error message changes. The consumer served here is the contributor
and the agent session: the corpus is the tree's shared fixture pool and the architecture reference's
only machine-checked evidence, and both are expensive to grow today. Anyone reading this item for a
schema-author-visible outcome should stop here.

Three things become true that are false today.

1. **Adding a worked example is one new file plus at most one include line, and `git log` can prove
   it.** No `.java` edit, no paste from a failure message, no second place to register the example.
2. **An example's story is written where a machine renders it.** The three habitats collapse to two,
   deliberately: per-coordinate teaching prose moves onto the coordinate as an SDL description, which
   capture transcribes into `graphql_field.description` and `graphql_type.description`, and the
   example-level narrative stays on the authored page, having no coordinate to sit on. What
   disappears is the habitat nothing renders, which is the one that rots: R814 found `mutation-roots`
   still labelled "Corpus-only" in a Java comment long after it had gained a projection query. A
   description is display material and never a dimension, per the fact model's own column rule, so
   the claim is one habitat fewer and a rendered one, not "the prose becomes queryable data".
3. **A coordinate's assertion names a fact the store holds**, so it survives the walk's deletion by
   construction rather than by a later port. Where an axis has no fact spelling yet, the gap is
   *visible* as a surviving `@classified` declaration on the coordinate, which is R814's rule that a
   verdict with no spelling in a surviving vocabulary marks a missing relation.

The measurable forms, each checkable on the landed tree:

- The diff for a new worked example is one new `.graphqls` file plus at most one `include::` line.
  The boundary, stated because R814's implementation record falsifies the unqualified claim: this
  holds for the container, not for the reflected-fixture surface. An example whose fixture names a
  Java class by FQN (a `@service` stub, a backing record) may still need a method on that stub, which
  is what `DummyRecord` gaining a property and `PlainJooqRecord`'s abstractness were in R814's
  slice 3.
- `ClassifiedCorpus.java` (1783 lines) and `ClassifiedDsl.java` (the Java prelude string) are gone;
  the corpus is 57 or more documents in one folder, and the prelude is one document beside them.
- The authored page's committed `[source,graphql]` blocks and `.What the pipeline makes of it`
  tables (32 of each today) go to zero, replaced by include lines; the page's line count falls by
  roughly half.
- No test failure message is the transport for a documentation update, and no oracle is lost buying
  that. Every expectation those pastes carried lives beside the document instead: the verdicts as
  expected-row tables in the document, the emitted names as a checked-in approval file in the
  corpus folder. `ClassifiedDocTest` and `OutcomeBlockDocTest` retire only once their expectations
  have that home, which is why the doc collapse is the last slice and not the first.

## The document

One example is one GraphQL document at `graphitron/src/test/resources/corpus/<id>.graphqls`. The
filename is the example's id, so the id has one home instead of being a string beside the fixture.
A document carries four things, in this order by convention:

```graphql
"A country row reached from city over the single city -> country FK."
type Country @table(name: "country") {
  "The country's name, an inline column read."
  name: String @field(name: "country")
}

type City @table(name: "city") {
  "Inlined as a correlated subquery folded into city's own SELECT."
  country: Country @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
  "The same verdict with @splitQuery, which flips it to a keyed query of its own."
  countrySplit: Country @splitQuery @commits(source: CorrelatedChain, result: SingleRecord)
    @classified(source: OnlyChild, operations: [Join, Select], target: Single, targetShape: Table)
}

extend type Query {
  city: City @commits(source: AnchorTable, result: SingleRecord)
}

extend schema @expectEquals(relation: "intent_resolved_field_claim", rows: """
  type_name, field_name,   classifier,   tier
  Country,   name,         COLUMN_MATCH, INFERRED
  City,      country,      COLUMN_MATCH, INFERRED
  City,      countrySplit, COLUMN_MATCH, INFERRED
  """)

{ city { country { name } countrySplit { name } } }
```

1. **The fixture**, unchanged from what the Java string holds today.
2. **Its per-coordinate prose, as descriptions.** The teaching sentence for a coordinate sits on that
   coordinate: the `#` comment in today's projection query and the part of the Java comment that is
   about one coordinate become one description here. The example-level narrative, which has no
   coordinate to sit on, stays on the authored page.
3. **Its assertions, as expected-row tables.** Today's four coordinate-level directives
   (`@classified`, `@classifiedType`, `@synthesises`, `@commits`) move verbatim in slice 1; slice 2
   replaces them with `extend schema @expectEquals(relation:, rows:)` applications, one per asserted
   relation, each carrying that relation's expected content for this document as CSV.
4. **Its projection operation, optionally**, in the same file: an anonymous query, or a bare
   `fragment F on Type` where the coordinate has no reachable root path (both forms
   `QueryViewRenderer` already accepts). A document with no operation is corpus-only: it pins a
   verdict and renders nowhere, which is 25 of the 57 today.

The loader splits the document: the type-system half goes to capture, classification and generation,
and the operation goes to the projection renderer. graphql-java's parser accepts a mixed document,
but `RewriteSchemaLoader.admit` refuses a non-SDL definition on purpose (`NonSDLDefinitionError`,
whose javadoc names exactly this case), so nothing downstream may see the operation. The split is
therefore a **truncation at the operation's source line**, and the convention it rests on is that the
operation is the document's last definition, asserted by the loader with a stated message when it is
not. Truncating rather than re-printing the type-system half is what keeps every other line's
captured `source_line` equal to the line an author is reading. The fallback, if a document ever needs
an operation somewhere other than last, is a second file per example
(`<id>.projection.graphql`), at the cost of the one-file acceptance form.

**The prelude is a document too.** `_prelude.graphqls` in the same folder holds what
`ClassifiedDsl.PRELUDE` holds today: the test-only directive definitions, the SDL enums, `interface
Node`, the `Query` root, and the `CorpusAnchor` type bound to a table no fixture uses. The leading
underscore keeps it out of the `*.graphqls` document glob, the same convention the generated
`_command-relations.adoc` fragment uses. This is what makes the container claim total: a new
assertion directive or a widened enum is a prelude edit, not a Java edit.

**Read from the source tree, not the classpath.** The loader resolves
`graphitron/src/test/resources/corpus` as a path, with the two-candidate working-directory
resolution `ClassifiedDocTest` already uses, rather than as a classpath resource directory. What is
asserted is then exactly what an author edits, and a stale copy under `target/test-classes` cannot
answer for a document that is no longer there.

## The loader, and the floors that keep it honest

One loader, `CorpusDocuments`, in the `classifieddsl` package. It walks the folder once and yields
`Document(id, sdl, projection)` records; `documents()` and `withProjection()` replace
`ClassifiedCorpus.examples()` and `docExamples()`. Every reader reads the loaded corpus, and nothing
else lists the directory: the 19 files that read the corpus today, 7 in the package and 12 outside it
(`ExemptionRegistry`, `VariantCoverageTest`, the three `derive/*ShadowTest` sweeps, and the rest),
change an import and a factory call, not their logic.

The floors exist because a folder is exactly the container that can pass while empty. R346's
decision 2 is the recorded instance: a generated fragment claimed each directive was "exercised by a
test fixture" on a signal the generator never computed, and the claim shipped. Documents are
therefore *executed, never surveyed*, and four floors say so mechanically:

- **Non-vacuity with a ratchet.** The folder must exist and hold at least a stated minimum of
  documents, pinned near today's count and raised when it grows, the way
  `CommandRelationFragment.MIN_RELATIONS` guards its own scan.
- **Listing agreement.** The set of files the loader admitted equals the set of `*.graphqls` files
  in the directory minus the underscore-prefixed prelude. A file the glob or the loader silently
  skipped fails here rather than becoming a document nothing reads.
- **Every document is asserted on.** The parameterized corpus tests derive their parameter list from
  the loader, and a meta-test asserts the parameter count equals the loaded document count, so a
  document cannot be loaded, counted, and never run.
- **Every document annotates at least one coordinate**, which is today's non-vacuity check on
  `ClassifiedDslTest` kept.

## Slice 1: the container moves (shipped at `bcad819` + `a457732`)

All 57 fixtures are documents under `graphitron/src/test/resources/corpus/`, the prelude is
`_prelude.graphqls` beside them, `CorpusDocuments` loads the folder from the source tree with its
floors in `CorpusDocumentsTest`, the 19 readers call `documents()` / `withProjection()` /
`coveredLeaves()`, and `ClassifiedCorpus` and `ClassifiedDsl.PRELUDE` are gone. Every projection
comment and every recast Java comment is now an SDL description on the coordinate it is about. The
page is untouched: all 32 rendered blocks are byte-identical, held by `ClassifiedDocTest`.

Five learnings, each of which changed the plan as written:

- **The additive dual-source phase was not needed, and skipping it bought a stronger proof.** The plan
  called for the loader to land with two or three documents while `documents()` returned the folder
  unioned with the surviving Java list, so that "one new file, no `.java` edit" was provable at
  document number one. Extracting all 57 mechanically instead made a better argument available: a
  one-shot check compared every id's SDL and projection, and the prelude, string by string against the
  Java list before it was deleted, and reported zero mismatches. That is equality, where the
  incremental path would have offered a green build per document.
- **The prose recast is page-neutral by construction, which the plan did not anticipate.** The
  renderer already stamped projection comments on as SDL descriptions, so moving the same text to the
  SDL side changes where the prose lives and not what prints. Only one added description touched a
  rendered block, and it was page prose by the plan's own rule.
- **A claim about coverage is not coordinate prose.** The one Java comment with nowhere to go said
  that its document is what keeps the source-shape mirror honest. That went into
  `SourceShapeProjectionTest`'s javadoc, beside the mirror it is about, rather than onto a coordinate.
- **The comment-to-description seam retires with the recast.** With prose in the documents,
  `QueryViewRenderer`'s `descriptionOf` / `applyDescription` and the two description maps on
  `Touched` have no author, and the renderer prints what the parsed SDL carries. Its four
  comment tests become two on the successor mechanism.
- **`AstPrinter` breaks a field's argument list onto its own line once the field carries a
  description.** A renderer unit test's expectation moved for that reason, not a behavioural one; the
  page's committed blocks have carried the multi-line form all along.

The mirror tests (`sourceWrapperMirrorsAdapterValues` and its four siblings, plus the `TypeVerdict`,
`SynthesisedType`, `LauncherSource` and `LauncherResult` mirrors) now read their SDL side out of the
parsed prelude document. They survive this slice because the walk-tuple directives they guard survive
it; each one dies in slice 2 with the enum it mirrors, since a CSV block declares no enum, and
whatever the walk still needs at that point dies with the zoo under R682.

## Slice 2: the assertion becomes a fact expectation

One directive, declared once in the prelude document, applied at the schema and repeated per
relation. Its payload is the relation's expected content as CSV:

```graphql
directive @expectEquals(relation: String!, rows: String!) repeatable on SCHEMA
```

Two earlier drafts are recorded because the reasons they lost are the reasons this one holds. The
first was a generic row-matcher, `@expect(relation: String!, where: [{column:, is:}])`, refused for
the reason the development principles' directive corollary gives: an input-object wrapper over
several optional slots widens the failure surface from "the named thing did not resolve" into a
cross-product of missing and inconsistent slots. The second was one typed directive per relation
with enum-typed arguments, which bought parse-time rejection of a typo and paid for it in exactly
the coin this item exists to stop spending: an SDL enum per vocabulary, hand-mirrored against a Java
or catalog side by a family of mirror tests. That is the "hand-copied mirrors" defect in this item's
own opening paragraph, re-erected one layer out. A CSV block has no enum to mirror, so the mirror
family retires here rather than acquiring a new right-hand side.

Eight properties make this the successor form rather than a re-spelling of the walk tuple.

**The block is a relation literal, so every relation is reachable.** The header names columns, each
line is a row, and the coordinate is a column like any other. `graph_name` is the one column a
document never spells: it is the document's own identity, supplied by the harness. Because the key
is spelled rather than implied by an application site, the use-keyed relations a coordinate-level
directive structurally cannot key (`intent_input_occurrence_path` and the other
definition-plus-consumer joins) are assertable on the same mechanism. The typed draft had to declare
them out of scope, and that exclusion was a property of the encoding rather than of the domain.

**We own no CSV parser.** The block reaches the harness through the store's own client:
`Parser.parseValue` recovers the text from `graphql_schema_directive_arg.value_sdl`, which holds the
`AstPrinter.printAstCompact` form of the block string rather than its raw text, and
`DSLContext.fetchFromCSV(text, true, ',')` turns it into a `Result<Record>` whose field names are the
header. Quoting, embedded separators and escaping are jOOQ's and graphql-java's problem. The harness
trims each cell, so a document may pad columns for legibility.

**Capture already carries it.** `SdlFactCapture.captureSchema` walks `registry.schemaDefinition()`
and `getSchemaExtensionDefinitions()` alike and writes `graphql_schema_directive` plus its `_arg`
child, assigning a per-name ordinal to each repeated application. A document needs no `schema`
definition of its own: `extend schema` is enough, and the prelude's root declaration stays where it
is. The decode path beside the generic write, `GraphitronFactCapture.captureSchemaDirective`, returns
immediately for any name but federation's `@link`, so an assertion application is transcribed and
interpreted by nothing.

**The comparison is set equality per document and relation.** The union of a document's blocks for
one relation equals that relation's rows in that document's graph, projected onto the named columns.
A coordinate that silently starts producing a row therefore fails, which is the property a
per-coordinate scoping would lose, and an empty block (header only) asserts the relation holds
nothing for this document. Exactness makes the block an approval table, which is the shape this
item's own direction endorses, and the cost is honest: a fixture gaining a coordinate updates the
block. That paste goes into the document that is also the source of truth, not into a third copy on
a page, which is the whole difference from the loop being retired.

**The name is the semantics, and the weaker sibling is not declared yet.** `@expectEquals` says what
it does, so a reader does not have to learn a convention to know whether an unlisted row passes. A
containment form, `@expectContains(relation:, rows:)`, is the obvious weaker sibling and is
deliberately absent: a directive nothing applies is a vocabulary with no consumer, and offering both
from the start invites the weaker one where the stronger is merely inconvenient. It gets declared if
and when some relation's per-document table proves unworkably large, in the increment that meets
that relation, with the reason exactness failed there recorded beside it.

**Asserted absence needs the relation's own permission.** An empty block is legal only for relations
whose comment says what their silence means, per the fact model's rule that "not reached" is not
"resolves to nothing". Where the relation does not own its silence, the document says nothing rather
than asserting emptiness the relation cannot mean.

**Names resolve, values are checked as far as the store allows.** `relation:` and every header cell
resolve through `StoreCatalog`, the booted store's catalog reader `SchemaIdentifierDriftCheck` and
the generated schema reference already share, so a misspelled relation or column fails loud. Values
get the strongest check the store supports for their column, and the ladder is stated rather than
uniform: for a base-table column the closed set is the `CHECK (x IN (...))` clause
`StoreCatalog.checksByRelation` already reads, so membership is checked against the DDL; for a view
column, which `intent_resolved_field_claim.classifier` is, there is no CHECK and the comment says
outright that the vocabulary lives in the reading side's decode, so no membership check exists and a
typo surfaces as a row mismatch. What recovers the diagnosis there is the failure message, not a
second roster: when a declared value appears in no row of that relation in any document, the report
says so, which reads as a typo rather than as a disagreement about behaviour. This is the loss the
CSV form accepts against the typed draft, and it is bounded to view columns and to the message.

**The assertions leave the rendered SDL alone.** Because the applications sit on a schema extension
rather than on field and type definitions, the types a projection renders carry no assertion
directives at all, so the doc fragment's SDL block needs nothing stripped from them. Today
`QueryViewRenderer` strips the internal directives out of the printed types; under this form there
is nothing to strip, and the block a reader might copy is the schema pattern and only that.

**The harness reads the assertion out of the store, not off the AST.** Capture is total, so the
applications are themselves rows (`graphql_schema_directive` plus
`graphql_schema_directive_arg.value_sdl`). The harness captures all 57 documents into one store, one
graph per document, which is the pattern `derive/ColumnMatchShadowTest` and
`CapturedStore.andCatalogGraph` already give it, and then the whole corpus's assertions are one query
per asserted relation: an anti-join in both directions between the expectation rows and the
relation's rows. **A failure is a row**, naming the document, the relation, the row, and which side
is missing it. This retires the `Argument` / `EnumValue` / `ArrayValue` cast pile in
`ClassifiedHarness`, which is the containment rule pointed the right way: the assertion is data in
the store, not a Java transcription of data in the store.

**The assertable population is stated positively.** The corpus asserts over `intent_` and
`graphitron_` relations only, never over the `graphql_*_directive` families. That is what keeps the
assertion vocabulary outside the population it measures by construction, rather than by a second
name filter of the kind `QueryViewRenderer` already carries for the same reason (a skip-list
coordinating two passes implicitly is the shape the gathering principle refuses).

**No third statement of a vocabulary.** An earlier draft proposed declaring the closed sets as DDL
rows, a `meta_column_vocabulary` VALUES view beside `meta_family`. It is refused: for a base table
that is a hand-kept copy of a catalog fact, which is the drift smell the fact model names, and for a
view the non-circular closer is the decoder that reads the column, not a second roster. The ladder in
the paragraph above is the whole of what the corpus checks a value against.

**Whose ratchet.** R682's Coverage section already claims the re-key of the completeness gates onto
surviving vocabularies. This item therefore owns the **assertion form**, and R682 keeps the
**coverage ratchet** over those vocabularies. Two mechanisms arriving over one vocabulary is the
thing the boundary exists to prevent; the boundary section below repeats it so neither item has to
infer it.

**Axis by axis, and the leftovers stay visible.** A `@classified` axis moves when a fact spelling for
it exists: the claim axes (`intent_resolved_field_claim`, `intent_authored_field_claim`,
`intent_column_match_claim`, `intent_bound_table`) can move as soon as this slice starts; the
operation-member arms, target shape and arrival shape wait on R682's relations. A document keeps
`@classified` on its coordinates for the axes with no spelling and gains an `@expectEquals` block for
each relation that has one, which makes every remaining tuple axis a visible request for a relation
rather than an invisible dependency. `@commits` straddles the command tier R682 reshapes and gets the same scrutiny rather
than a mechanical port. The last `@classified` dies with the zoo, under R682, not here.

**The emitted-names half moves too, and this is why the slice precedes the doc collapse.** The
verdict half of an outcome block becomes an `@expectEquals` table in the document. The emitted
unit and method names are not a store fact, so they get the other habitat the tree already has for a
checked-in expectation: one approval file per document in the corpus folder, holding exactly what
`OutcomeBlockRenderer` renders for the emitted column today, in the `ApprovalQueryExampleTest` sense.
The corpus test compares against it. That is the oracle `OutcomeBlockDocTest` is today, with the page
taken out of the loop.

## Slice 3: the page becomes a view

Only now does the page stop holding expectations, because by here it holds none: the verdicts are
asserted in the documents and the emitted names in the approval files. This is the correction that
reordered the slices. Doing the collapse first would delete two approval oracles
(`ClassifiedDocTest`, `OutcomeBlockDocTest`) and put nothing in their place, because
generated-not-committed removes the comparison rather than relocating it, and the emitted names of
the 32 doc examples are pinned nowhere else in the tree. The `_command-relations.adoc` precedent does
not cover that: it renders a census carrying no behavioural claim, so there is nothing for it to
approve.

With the expectations rehomed, the fragment is a pure render of already-asserted facts, and
generated-into-staging-never-committed is exactly right for it. One AsciiDoc fragment per doc
example: the coordinate prose read from the captured description rows, the rendered SDL block, and
the outcome table. The authored page keeps its narrative, its teaching order and its section
headings, plus one `include::_example-<id>.adoc[]` line per example. `ClassifiedDocTest` and
`OutcomeBlockDocTest` are then deleted, having nothing left to hold.

The floor that replaces their discovery half: **a document carrying a projection operation that no
authored page includes fails the build**, and an include naming no document fails it too. That check
reads the authored pages and the loaded corpus, so it lives in the `graphitron` test tier beside the
corpus, on the placement reasoning R814 settled for the architecture symbol gate (`roadmap-tool`
cannot see the classes such a check needs).

**Where the render runs: the docs module, over a main-side renderer.** This is
`CommandRelationFragment`'s shape exactly: a class in `no.sikt.graphitron.docs`, invoked from
`docs/pom.xml` at `process-resources` with `classpathScope=compile`, taking the corpus folder and the
output path as arguments the way that renderer takes a source root. `CorpusDocuments` and the two
renderers move from the test tree into that package; the harness goes on consuming them.

The arm this rejects, and why, because it looks cheaper: an `exec` in `graphitron/pom.xml` with
`classpathScope=test` would keep everything in the test tree and add no docs dependency, but
`-Pquick` sets `maven.test.skip`, which skips test *compilation*, so that execution has no classes to
run and fails outright rather than merely costing time. Gating it on the same flag makes the
fragment's existence conditional on a test-lifecycle flag while its `include::` consumer is
unconditional, which either dangles the include or forces R814's absent-fragment gate to be relaxed.
There is no exec in the reactor gated on a test-skip flag; every one of them runs at `compile` or
`process-*` with `classpathScope=compile`.

The price of the chosen arm is two provided-scope dependencies on the docs module and one coupling
worth naming outright:

- `graphitron-sakila-db`, for the jOOQ catalog the fixtures' tables resolve against. The docs build
  then needs `-Plocal-db` like every other build, and the catalog-jar clobber footgun fails it loudly
  rather than rendering a plausible empty table, which is the floor discipline the schema reference
  already applies.
- `graphitron`'s test-jar, for the Java classes corpus documents name by FQN (`TestServiceStub`,
  `DummyRecord`, `PlainJooqRecord`). Provided scope on a pom-packaged module that already sets
  `maven.deploy.skip` reaches no published artifact, which is the same reasoning the module's
  existing `provided` dependency on `graphitron` states.
- The coupling: those stubs are fixtures of the corpus as much as the documents are, and they stay in
  the test tree while the documents leave it. That is a deliberate split, not an oversight. Moving
  reflection stubs into main sources would ship test fixtures to consumers, and the acceptance form
  above already states the boundary it creates: an example needing a new stub method is not a
  one-file diff.

## Tests

Every gate below is pipeline tier: capture and generation over the corpus need neither PostgreSQL nor
the jOOQ codegen beyond the catalog the tier already has, which is where `ClassifiedDslTest` and
`OutcomeBlockDocTest` sit today.

- `CorpusDocumentsTest` (slice 1): the four loader floors, each with a planted regression (a removed
  document, a mis-globbed file, a document no parameterized test claims, a document with no annotated
  coordinate). While the list drains, one more: the union's two sources hold disjoint ids, so an
  id cannot exist twice with different content.
- The corpus assertion test (slice 2), renamed off the DSL vocabulary: per document and relation, the
  expected-row table equals the produced rows over its own columns, reported as rows. Beside it three
  floors: name resolution (`relation:` and every header cell resolve through `StoreCatalog`), block
  well-formedness (no ragged row, no duplicate header cell, no `graph_name` column, and an empty
  block only where the relation owns its silence), and the assertable population (no block ranges
  over the `graphql_*_directive` families).
- Value membership where the store closes the set (slice 2): a declared value outside a base-table
  column's `CHECK (x IN (...))` clause fails as a membership error rather than as a row mismatch,
  with a planted regression. For a view column, the weaker signal is the failure message's own
  "no row in any document carries this value" line, and the test for it is that the message says so.
- The emitted-names approval comparison (slice 2), one per document that generates, replacing
  `OutcomeBlockDocTest`'s page comparison with a corpus-folder one.
- The placement floor (slice 3): a document with a projection and no include fails; an include with
  no document fails. Planted regressions both ways.
- Unchanged and load-bearing throughout: `VariantCoverageTest`'s corpus obligation, which R682 owns
  re-keying. This item must not narrow what it covers. Its `coveredLeaves()` derivation moves from
  `ClassifiedCorpus` to the loader in slice 1 and is otherwise untouched.

The completeness question for the Done gate ("how do we know the item is complete") is the Goal's
four measurable forms, and the strongest of them is the first: the review can read the `git log` of
the last example added and see one file plus one line. The second-strongest is a negative one, and
the reviewer should ask it explicitly: for each expectation `ClassifiedDocTest` and
`OutcomeBlockDocTest` hold today, name what fails now if it breaks.

## Constraints and boundaries

- **Ownership boundary with R682.** R682 owns the walk's deletion and the re-key of the completeness
  gates (`VariantCoverageTest`'s corpus obligation must survive on surviving vocabularies); this item
  owns the container, the assertion form, and the doc collapse. The line inside slice 2 is worth
  stating twice because two mechanisms arriving over one vocabulary is exactly what it prevents: this
  item owns the **assertion form**, and R682 keeps the **coverage ratchet** over the vocabularies. Neither item blocks the other. Slices 1 and 3 are
  independent of R682 entirely; slice 2 proceeds per axis as R682 lands relations, and its remainder
  is R682's to finish.
- **Ownership boundary with R814.** R814 owns the page's rebuild onto the surviving vocabulary and
  the outcome block's existence, and is In Review. This item collapses the page's *authoring*
  mechanism and does not restructure its content. Slice 3 must rebase onto whatever R814 lands rather
  than racing it; if R814 is still open at pickup, start with slice 1, which touches the page not at
  all.
- **Do not deepen the walk dependency.** `QueryViewRenderer.render` reaches the schema through
  `GraphitronSchemaBuilder.buildBundle`, which R682 deletes. R814 already states the rule: avoid
  deepening it, and let the cutover belong to whichever increment retires the builder. Rendering the
  projection from store rows instead (the shape is pure schema data: fields, arguments, input-type
  closure, union and interface members, all of it captured) is the obvious successor and is
  deliberately **not** in this item's scope; it is filed as its own follow-up when slice 2 shows what
  the store read costs.
- **Register rule.** Descriptions in a consumer-facing schema render into introspection for API
  clients. Fixture-purpose prose lives in corpus documents only, which are test resources and reach
  no consumer's schema. The corpus documents are never inputs to a shipped build, and a description
  stays display material: nothing joins, groups or filters on one.
- **The rendered SDL block is a schema pattern a reader may copy.** Descriptions and assertion
  directives are stripped from the printed block, the way `QueryViewRenderer` already strips the
  internal directives; the prose reaches the page as AsciiDoc prose beside the block, not inside it.
- **Success-only, still.** The corpus asserts the happy path. A fixture that classifies and does not
  generate keeps rendering its verdicts with the "generates nothing" note R814 settled; rejection
  and input-side coverage stay on the enum table and are not this item's to move.
- **What the corpus may assert at all.** Rows reach the corpus store only through `FactCapture`, per
  `CapturedStore`'s own stated discriminator: a fixture here cannot encode a state capture never
  produces, and a question about what a relation returns *given* rows belongs to
  `SeededStore` in the store's own module. The corpus asserts what a real capture derives, never a
  view's joins in isolation.

## Retired vocabulary

- `ClassifiedCorpus`, `ClassifiedCorpus.Example`, `ClassifiedCorpus.examples()`,
  `ClassifiedCorpus.docExamples()` (slice 1).
- `ClassifiedDsl.PRELUDE` and the phrase "the test prelude" for a Java string (slice 1).
- `QueryViewRenderer`'s comment-to-description seam and the phrasing that names it: "a `#` comment
  line above a selected coordinate", "the projection query is the per-example place to say why a
  coordinate exists" (slice 1). A coordinate's prose is its own SDL description.
- `ClassifiedDocTest`, `OutcomeBlockDocTest`, and the paste loop they document ("paste this block
  into the page", "copy the block from the failure message") (slice 3, after their expectations have
  moved).
- "doc example" as a distinct kind of corpus entry, superseded by "a document with a projection".
- Per axis in slice 2, and only as each axis moves: `@classified`, `@classifiedType`,
  `DimensionTuple`, "the dimensional tuple", "the three-axis verdict". Whatever survives slice 2
  retires with R682's zoo and is listed there, not here.
- Per enum in slice 2, as its axis moves: the prelude's SDL enum wall (`SourceWrapper`, `Member`,
  `TargetWrapper`, `SourceShape`, `TargetShape`, `TypeVerdict`, `SynthesisedType`, `LauncherSource`,
  `LauncherResult`) and the `*MirrorsAdapterValues` family that pins it. An expected-row table
  declares no enum, so there is nothing left to mirror. Two earlier drafts of this spec said
  otherwise, one keeping the enums typed per relation and one re-pointing the mirrors at the store's
  decoders; both re-erected the hand-copied mirror this item's opening paragraph indicts, one layer
  out from where it found it.
- `ClassifiedHarness`'s `Argument` / `EnumValue` / `ArrayValue` directive-reading cast pile (slice 2).

## Documentation deliverables

- **The `classified-corpus` skill is rewritten**, not amended: its eight-step loop is a Java-editing
  procedure (author an `Example`, run the drift test, paste the block) and every step changes. The
  successor loop is: write a document, run the corpus test, done. It is rewritten in the slice that
  invalidates each step (slice 1 for authoring, slice 2 for the assertion, slice 3 for the page), not
  as a follow-up, because a skill describing the retired loop is worse than no skill.
- `docs/architecture/how-to/testing.adoc` gains the corpus folder as a named fixture home, since it
  is the first folder-of-documents fixture set in the tree (there are no `.graphqls` test resources
  in `graphitron` today).
- A doctrine corollary for `development-principles.adoc`, with its displacement named. Most of the
  candidate sentence is already there: "Principles are stated at altitude" carries the
  anything-enumerable-is-a-materialized-view rule, and "Documentation names only live tests/code"
  carries the honest forms. What is new is narrower and worth exactly one corollary: **spec files are
  executed, never surveyed**, with R346's survey-versus-execution instance as its exemplar. The file
  budgets itself at 3,500 words under `DocSizeBudgetTest`, so the corollary lands only with a stated
  displacement; if none is worth making, the doctrine stays here and in the testing how-to, and the
  principles file is left alone.
