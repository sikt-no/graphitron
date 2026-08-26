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

  **Amended by slice 3, honestly: it is two new files plus one line, not one plus one.** The second
  file is the generated fragment, and it is in the diff because slice 3 committed it rather than
  staging it, for the reasons that slice records. What the form was protecting is intact: neither new
  file is Java, neither is hand-written, and the fragment is produced by running a test rather than by
  an author typing it. What is lost is the sharper claim that a reader of the diff sees exactly one
  authored artifact, and the fragment is the price of the emitted names having an oracle at all.
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
resolution the documentation guards already use for the page, rather than as a classpath resource
directory. What is
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

**The assertion form shipped at `317b08b`.** The directive is declared in the prelude, all 57
documents carry blocks for `intent_resolved_field_claim` and `intent_bound_table` and the 33 with
authored claims carry `intent_authored_field_claim`, `CorpusExpectations` reads the applications out of
the store and compares them by anti-join in both directions, and `CorpusExpectationTest` carries the
four floors with `CorpusExpectationsTest` planting a regression under each. What the rest of this
section describes and the shipped work does not yet do, in the order it should be picked up:

1. **The emitted-names approval files.** *Shipped in slice 3, as one artifact rather than two.* The
   verdict half of an outcome block is an `@expectEquals` table, and the emitted names were left
   pinned only by the page comparison. Landing an approval file as a second generation sweep beside
   that test would have doubled a 133-second cost for the interim, so the cheaper shape was to land
   it with the page collapse and have one generation run serve both. Slice 3 took that further: the
   approval file and the page's included fragment are the same file, so there is one artifact, one
   sweep and no second copy of the emitted names.
2. **The per-axis `@classified` retirement.** The claim relations are asserted beside the tuple
   directives rather than instead of them, because no `@classified` axis is *covered* by a claim
   relation alone: the claim relations spell which classifier claimed a coordinate and at what tier,
   while the tuple's axes are source, operations, target and the two shapes. So the enum wall and the
   mirror family survive slice 2 as landed, and what retires an axis is a relation that spells it,
   which is R682's to produce. The plan said "each mirror dies in slice 2 with the enum it mirrors";
   the honest form of that is "with the axis it mirrors", and no axis moved yet.

Two smaller findings from the landed work:

- **The prelude is part of every document's expectation.** `CorpusAnchor` contributes one bound table
  and one inferred claim to every graph, so every block carries those rows. Masking them out in the
  harness would be exactly the skip-list coordinating two passes that this item's own text refuses,
  so they are declared like any other row.
- **Values are compared as text.** The comparison casts both sides to VARCHAR and matches on
  `IS NOT DISTINCT FROM`, so an empty cell is how a document spells NULL and a boolean column reads as
  `TRUE` / `FALSE`. That is a property of the block being a text literal, and it is worth stating
  because it decides what an author writes.

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
checked-in expectation: an approval file per document holding exactly what `OutcomeBlockRenderer`
renders for the emitted column, in the `ApprovalQueryExampleTest` sense, with the corpus test
comparing against it. That is the oracle the page comparison was, with the page taken out of the loop.
The corpus folder was the assumed home; slice 3 put it in the documentation tree instead, because the
approval file and the page's fragment turned out to be one file, and only one of the two directories
can be included from a page.

## Slice 3: the page becomes a view (shipped)

Only now does the page stop holding expectations. This is the correction that reordered the slices.
Doing the collapse first would delete two approval oracles and put nothing in their place, because
generated-not-committed removes the comparison rather than relocating it, and the emitted names of
the 32 doc examples are pinned nowhere else in the tree. The `_command-relations.adoc` precedent does
not cover that: it renders a census carrying no behavioural claim, so there is nothing for it to
approve.

**What landed.** One AsciiDoc fragment per document carrying a projection, at
`docs/architecture/reference/_example-<id>.adoc`: the rendered SDL block and the outcome table,
joined by `CorpusFragmentRenderer`. The page keeps its narrative, its teaching order and its section
headings, and carries one `include::_example-<id>.adoc[]` line per example and no block of its own;
it went from 2474 lines to 1205. `CorpusFragmentTest` compares every fragment against what the
corpus renders now and carries the three placement floors, with a planted regression under each in
`CorpusFragmentRendererTest`. `ClassifiedDocTest` and `OutcomeBlockDocTest` are deleted.

**The arm is a fourth one, and the reason the three recorded arms all lost is the same.** The plan
sent whoever picked this up to choose between a main-side capture entry point, a staged fragment
written by the test tier, and collapsing only the outcome tables. All three are answers to one
question, where the fragment is generated at build time, and all three pay for it in the same coin:
the render captures and generates, so it needs the corpus's reflection stubs, and those are test-tree
classes behind `maven.test.skip`. A main-side entry point is cheaper than the plan feared (capture
needs only `GraphitronModelStore.open`, `RewriteSchemaLoader.load` and `FactCapture.capture`, all
main-side, and `TestConfiguration.testContext` is a `RewriteContext` over two string constants), but
it does not reach the stubs, and a docs render that cannot resolve a `@service` FQN does not fail:
generation rejects the pattern, the block renders "generates nothing", and the page is quietly wrong
for 22 of the 57 documents. Moving the stubs to main sources ships test fixtures to consumers, which
this item's own boundary paragraph refuses.

**So the fragment is not generated at build time. It is generated, committed, and approved.** That
follows from the plan's own reasoning about the emitted names, read one step further: the plan wanted
never-committed page fragments *and* checked-in approval files for the emitted names, on the grounds
that the fragment holds no expectation while the approval does. But the outcome table *is* the
emitted names, so the fragment holds an expectation, so the premise for never-committed fails and the
two artifacts were always one. The fragment is committed because it is an oracle; being committed, it
serves the page directly, and an unconditional `include::` resolves in every build including the ones
that skip tests. This is the contract `roadmap/README.md` already has, and the approval idiom
`ApprovalQueryExampleTest` already documents: generated, committed, never hand-edited, regenerated by
the test that owns it. Slice 2's remaining emitted-names approval is therefore not a separate
deliverable; it is this file, which is also what the plan asked for when it said to land the two
together so one generation sweep serves both.

**The loop the paste loop became.** A disagreeing render is written to
`graphitron/target/corpus-fragments/` and the failure message gives the `cp` line onto the approved
file. Under this module's own `target` and not beside the approved file on purpose: the fragments live
in the published documentation tree, so a stray `.adoc` there would be staged and scanned as an
untracked page. Nothing is copied out of a failure message, which is the fourth measurable form.

Three properties of the placement worth stating, because each was a choice:

- **The fragments share a directory with the page that includes them.** A relative `xref:` written
  inside a fragment then resolves identically whether it is read as part of the including page or on
  its own, which is what the staged-site xref check reads it as. A `_examples/` subdirectory would
  have made the rejection note's `xref:../explanation/typed-rejection.adoc` wrong by one level in one
  of the two readings.
- **The `_` prefix is load-bearing three times, and one existing gate had to learn it.** Asciidoctor
  renders every staged `.adoc` that does not start with one, so without it the site would publish 32
  title-less pages beside the page including them; the corpus's own include floor uses it to tell a
  fragment from an authored page; and `HowToIndexCoverageTest`, which requires every page in a
  section directory to be listed in that section's `index.adoc`, was counting fragments as pages. It
  now excludes underscore files, with a case asserting the exclusion has a population, because an
  exclusion guarding nothing reads as a general escape hatch. That gate is the one thing in the tree
  that already had an opinion about what a file in `docs/architecture/reference` is, and it is worth
  noting that neither the staged-site xref check nor the architecture symbol scan needed changing:
  the fragments sit in the page's own directory, so their relative `xref:` targets resolve, and the
  region marker on the first line is what the symbol scan reads.
- **The generated-region marker moved from the block to the fragment's first line.** The architecture
  symbol scan skips from the marker to the close of the table inside the region, so one marker at the
  head covers both halves, and it is also the line telling a human not to edit the file.

The floor that replaces the two guards' discovery half: **a document carrying a projection that no
authored page includes fails the build**, an include naming no document fails it too, and so does a
fragment whose document is gone. Those read the authored pages and the loaded corpus, so they live in
the `graphitron` test tier beside the corpus, on the placement reasoning R814 settled for the
architecture symbol gate (`roadmap-tool` cannot see the classes such a check needs).

**What the docs module did not need.** No new dependency, no exec, no pom change at all. The plan
priced the chosen arm at two provided-scope dependencies (`graphitron-sakila-db` for the catalog,
`graphitron`'s test-jar for the stubs) and a coupling it named as deliberate; a committed fragment
buys all three back. The coupling's consequence survives and is still true: an example needing a new
stub method is not a one-file diff.

## Tests

Every gate below is pipeline tier: capture and generation over the corpus need neither PostgreSQL nor
the jOOQ codegen beyond the catalog the tier already has, which is where `ClassifiedDslTest` and the
documentation guards sit. The one exception, landed: the placement floors are set comparisons over
three lists of names, so they are unit tier and need no store.

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
- The emitted-names approval comparison, one per document with a projection, replacing the page
  comparison with a file comparison. Landed in slice 3 rather than slice 2, over the same file the
  page includes: `CorpusFragmentTest` holds both halves of a worked example at once, so there is one
  approval per example instead of one per half.
- The placement floors (slice 3): a document with a projection and no fragment fails, a fragment with
  no document fails, and a fragment no page includes fails. A planted regression under each, plus one
  for the near-miss the include floor has to reject: a page naming the fragment's filename in prose
  is not a page showing it.
- Unchanged and load-bearing throughout: `VariantCoverageTest`'s corpus obligation, which R682 owns
  re-keying. This item must not narrow what it covers. Its `coveredLeaves()` derivation moves from
  `ClassifiedCorpus` to the loader in slice 1 and is otherwise untouched.

The completeness question for the Done gate ("how do we know the item is complete") is the Goal's
four measurable forms, and the strongest of them is the first: the review can read the `git log` of
the last example added and see one file plus one line. The second-strongest is a negative one, and
the reviewer should ask it explicitly: for each expectation the two retired documentation guards
held, name what fails now if it breaks. The answer the landed tree gives: the rendered SDL and the
outcome table both fail `CorpusFragmentTest`'s approval, an example that stops being shown fails its
include floor, and an example that stops existing fails the orphan floor. What no longer fails is a
hand-edit to the page's copy of a block, because the page has no copy.

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
  into the page", "copy the block from the failure message") (slice 3, their expectations having
  moved into the committed fragment). Also retired with them: "the doc-bridge guard" and "the page
  holds it verbatim". The page holds nothing; an approval file does. Note for whoever runs the
  retirement sweep: "drift guard" itself stays live, naming the schema-identifier guard and a dozen
  other mechanisms, so it is not a registry candidate.
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

- **Landed in slice 3: the skill's page steps are the successor loop.** Step 4 renders the fragment
  and gives a `cp`, step 5 places one include line, and both carry the rule that a fragment is never
  hand-edited. The testing how-to gains the fragment as the tree's second approval-style fixture home
  and states why it is generated-and-committed. The reference page's own intro now says it holds no
  expectation of its own, and the stale slice-1 sentence claiming its descriptions come from
  projection comments is gone.
- **The `classified-corpus` skill is rewritten**, not amended: its eight-step loop is a Java-editing
  procedure (author an `Example`, run the drift test, paste the block) and every step changes. The
  successor loop is: write a document, run the corpus test, done. It is rewritten in the slice that
  invalidates each step (slice 1 for authoring, slice 2 for the assertion, slice 3 for the page), not
  as a follow-up, because a skill describing the retired loop is worse than no skill.
- `docs/architecture/how-to/testing.adoc` gains the corpus folder as a named fixture home, since it
  is the first folder-of-documents fixture set in the tree (there are no `.graphqls` test resources
  in `graphitron` today).
- **Decided while landing slice 1: no principles displacement is worth making.** The doctrine landed
  where the plan's own fallback puts it, in the testing how-to and beside the mechanism it governs
  (`CorpusDocumentsTest`'s javadoc states the executed-never-surveyed rule and what each floor exists to
  catch). Displacing 3,500 words of principles prose to restate it at altitude would have cost a
  section that carries more, and the corollary is not general: it is a rule about fixture containers,
  which is what the testing how-to is for. The paragraph below records what was considered.
- A doctrine corollary for `development-principles.adoc`, with its displacement named. Most of the
  candidate sentence is already there: "Principles are stated at altitude" carries the
  anything-enumerable-is-a-materialized-view rule, and "Documentation names only live tests/code"
  carries the honest forms. What is new is narrower and worth exactly one corollary: **spec files are
  executed, never surveyed**, with R346's survey-versus-execution instance as its exemplar. The file
  budgets itself at 3,500 words under `DocSizeBudgetTest`, so the corollary lands only with a stated
  displacement; if none is worth making, the doctrine stays here and in the testing how-to, and the
  principles file is left alone.
