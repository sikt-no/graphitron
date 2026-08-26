---
id: R840
title: "The corpus becomes a folder of self-describing, fact-first SDL documents"
status: Spec
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

The container is Java. Every new example is an edit to a hand-maintained 1781-line Java list,
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
- `ClassifiedCorpus.java` (1781 lines) and `ClassifiedDsl.java` (the Java prelude string) are gone;
  the corpus is 57 or more documents in one folder, and the prelude is one document beside them.
- The authored page's committed `[source,graphql]` blocks and `.What the pipeline makes of it`
  tables (32 of each today) go to zero, replaced by include lines; the page's line count falls by
  roughly half.
- No test failure message is the transport for a documentation update, and no oracle is lost buying
  that. Every expectation those pastes carried lives beside the document instead: the verdicts as
  typed assertion directives in the document, the emitted names as a checked-in approval file in the
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
    @resolvedFieldClaim(classifier: COLUMN_MATCH, tier: INFERRED)
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

{ city { country { name } countrySplit { name } } }
```

1. **The fixture**, unchanged from what the Java string holds today.
2. **Its per-coordinate prose, as descriptions.** The teaching sentence for a coordinate sits on that
   coordinate: the `#` comment in today's projection query and the part of the Java comment that is
   about one coordinate become one description here. The example-level narrative, which has no
   coordinate to sit on, stays on the authored page.
3. **Its assertions, as directive applications.** Today's four (`@classified`, `@classifiedType`,
   `@synthesises`, `@commits`) move verbatim in slice 1; slice 2 replaces them coordinate by
   coordinate with one typed directive per asserted relation.
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

## Slice 1: the container moves, additively

Additive-then-cutover, per `roadmap/workflow.adoc`: a big-bang swap of 57 fixtures and 19 readers in
one commit makes the item's own failure criterion unprovable until the last reader moves. Instead the
loader lands with two or three documents while `documents()` returns the folder unioned with the
surviving Java list, so **every reader keeps calling one accessor and never sees two sources**, and
"one new file, no `.java` edit" is provable at document number one. Then the list drains, document by
document, and `ClassifiedCorpus` is deleted when it is empty. This slice is orthogonal to the other
two: both could land against the Java strings today, so the container is neither's prerequisite, it
is the thing that makes them cheap.

- Move the 57 fixtures into `graphitron/src/test/resources/corpus/`, one document per current
  `Example`, each carrying its fixture, its assertion directives verbatim, its projection query where
  it has one, and its Java comment recast as descriptions on the coordinates the comment is about.
- Write `_prelude.graphqls` from `ClassifiedDsl.PRELUDE`. Keep the constants
  `ClassifiedDsl.CLASSIFIED` and friends only if a Java reader still needs the directive name by
  symbol; the prelude text itself has no Java home after this.
- Add `CorpusDocuments` with the four floors above, dual-sourced while the list drains. Delete
  `ClassifiedCorpus` at the end of the slice, when it holds nothing.
- Repoint the 19 readers (7 in the package, 12 outside) at the loader's accessors. Because the
  accessor is dual-sourced, this is one commit's worth of imports and is done before any document
  moves.
- The SDL-versus-Java mirror tests (`sourceWrapperMirrorsAdapterValues` and its four siblings, plus
  the `TypeVerdict`, `SynthesisedType`, `LauncherSource` and `LauncherResult` mirrors) read their SDL
  side out of the parsed prelude document instead of a Java string. They keep their meaning and lose
  their transport. They do not die: slice 2 re-points them from the walk's sealed leaves to the
  store's own verdict decoders, which is what keeps an SDL enum a top-rung claim.

Where the descriptions go is the one judgment call in this slice. A Java comment that explains a
minimal pair belongs on the two coordinates the pair contrasts, not on one of them; a comment about
the fixture as a whole belongs on the type the fixture is about. A comment that is really page prose
(the "Corpus-only" label R814 found stale on `mutation-roots`, for instance) is deleted rather than
transcribed.

## Slice 2: the assertion becomes a fact expectation

One typed directive per asserted relation, defined in the prelude document, never one generic
row-matcher. The generic form (`@expect(relation: String!, where: [{column:, is:}])`) was the first
draft and is refused here for the reason the development principles' directive corollary gives: an
input-object wrapper over several optional slots widens the failure surface from "the named thing did
not resolve" to a cross-product of missing and inconsistent slots, and a string-valued column name
forfeits the one property the prelude's enums exist for, which `ClassifiedDsl`'s own javadoc states:
a typo in a declared value is a schema-assembly error graphql-java rejects before the harness runs. A
`StoreCatalog` lookup catches the same typo one stage later and only when the harness runs.

```graphql
enum Classifier { SERVICE EXTERNAL_FIELD NODE_ID LOOKUP_KEY ROUTINE MUTATION COLUMN_MATCH }
enum ClaimTier { AUTHORED INFERRED }

directive @resolvedFieldClaim(classifier: Classifier!, tier: ClaimTier!)
  repeatable on FIELD_DEFINITION
```

Six properties make this the successor form rather than a re-spelling of the walk tuple.

**The site supplies the key; the directive supplies the rest.** An application on a field definition
keys `(graph_name, type_name, field_name)` from where it is written; on a type definition,
`(graph_name, type_name)`. Nothing in a document restates a coordinate it is already written at,
which is what makes an assertion survive a rename of the coordinate.

**Which relations may get a directive is a precondition, not a preference.** A directive sits at a
*definition* coordinate, so it can key a definition-keyed relation and cannot key a use-keyed one:
the fact model's own rule is that authored facts are definition-keyed and derived bindings are
use-keyed, and a relation like `intent_input_occurrence_path` is a definition-plus-consumer join. A
relation earns a directive only when its key is reachable from a directive location. Naming the
consumer as a directive argument to reach the rest is exactly the input-wrapper smell above and is
refused.

**A directive per relation, and no Java per relation.** The directive name maps to its relation and
each argument name to a column by convention (`@resolvedFieldClaim` to
`intent_resolved_field_claim`, `classifier:` to `classifier`), resolved through `StoreCatalog`, the
booted store's catalog reader that `SchemaIdentifierDriftCheck` and the generated schema reference
already share. A floor asserts the resolution: every assertion directive the prelude declares
resolves to exactly one relation, and every argument to a column of it, or the build fails. If the
convention proves lossy for some relation, the fallback is one declared mapping row per relation in
the harness, which is a vocabulary-change edit and not corpus growth.

**The comparison is set equality on the projection the document names.** For each
`(coordinate, relation)` pair a document declares, the declared rows equal the relation's rows at
that coordinate projected onto exactly the columns the directive carries. Two applications at one
coordinate pin cardinality. This extends the declared-equals-produced discipline `@commits` and
`@synthesises` already carry rather than introducing a second one.

**Asserted absence needs the relation's own permission.** A `@noRow`-shaped directive exists only for
relations whose comment says what their silence means, per the fact model's rule that "not reached"
is not "resolves to nothing" and that a relation whose absence is load-bearing owes that sentence.
Where the relation does not own its silence, the absence is not assertable and the document says
nothing.

**The harness never parses the directive.** Capture is total, so the assertion directives are
themselves captured (`graphql_field_directive` plus `graphql_field_directive_arg.value_sdl`). The
harness captures all 57 documents into one store, one graph per document, which is the pattern
`derive/ColumnMatchShadowTest` and `CapturedStore.andCatalogGraph` already give it, and then the
whole corpus's assertions are one query per asserted relation: an anti-join in both directions
between the captured expectation rows and the relation's rows. **A failure is a row**, naming the
document, the coordinate, the relation, and which side is missing it. This retires the
`Argument` / `EnumValue` / `ArrayValue` cast pile in `ClassifiedHarness`, which is the containment
rule pointed the right way: the assertion is data in the store, not a Java transcription of data in
the store.

**The assertable population is stated positively.** The corpus asserts over `intent_` and
`graphitron_` relations only, never over the `graphql_*_directive` families. That is what keeps the
assertion vocabulary outside the population it measures by construction, rather than by a second
name filter of the kind `QueryViewRenderer` already carries for the same reason (a skip-list
coordinating two passes implicitly is the shape the gathering principle refuses).

**What the value spaces are, honestly.** The enums above are still SDL enums mirrored against a Java
side, and the mirror family survives this slice with a new right-hand side. The vocabularies are not
uniformly DDL: for a base-table column the closed set is a `CHECK (x IN (...))` clause that
`StoreCatalog.checksByRelation` already reads off the catalog, so the mirror reads the catalog; for a
*view* column, which `intent_resolved_field_claim.classifier` is, there is no CHECK and the DDL
comment says outright that it is "a closed vocabulary the reading side decodes into a typed value",
so the mirror pins the SDL enum against the decoder's arm set. That is the same shape
`typeVerdictMirrorsGraphitronTypeLeaves` and `DiagnosticFactsTest` already use. An earlier draft
proposed a third statement, a `meta_column_vocabulary` VALUES view beside `meta_family`; it is
refused, because for base tables it would be a hand-kept copy of a catalog fact, which is the drift
smell the fact model names, and for views the non-circular closer is the decoder, not a second
roster.

**Whose ratchet.** R682's Coverage section already claims the re-key of the completeness gates onto
surviving vocabularies. This item therefore owns the **assertion form** and the mirror's re-point,
and R682 keeps the **coverage ratchet** over those vocabularies. Two mechanisms arriving over one
vocabulary is the thing the boundary exists to prevent; the boundary section below repeats it so
neither item has to infer it.

**Axis by axis, and the leftovers stay visible.** A `@classified` axis moves when a fact spelling for
it exists: the claim axes (`intent_resolved_field_claim`, `intent_authored_field_claim`,
`intent_column_match_claim`, `intent_bound_table`) can move as soon as this slice starts; the
operation-member arms, target shape and arrival shape wait on R682's relations. A coordinate keeps
its `@classified` for the axes with no spelling and gains a typed directive for the axes that have
one, which makes each remaining tuple axis a visible request for a relation rather than an invisible
dependency. `@commits` straddles the command tier R682 reshapes and gets the same scrutiny rather
than a mechanical port. The last `@classified` dies with the zoo, under R682, not here.

**The emitted-names half moves too, and this is why the slice precedes the doc collapse.** The
verdict half of an outcome block becomes a typed assertion directive in the document. The emitted
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
- The corpus assertion test (slice 2), renamed off the DSL vocabulary: per document, the captured
  expectations agree with the produced rows, reported as rows. Beside it, the directive-resolution
  floor (every prelude assertion directive resolves to exactly one relation and every argument to a
  column of it) and the assertable-population floor (no assertion ranges over the
  `graphql_*_directive` families).
- The emitted-names approval comparison (slice 2), one per document that generates, replacing
  `OutcomeBlockDocTest`'s page comparison with a corpus-folder one.
- The mirror tests (slice 2), re-pointed: an SDL enum against a base-table column's CHECK clause as
  `StoreCatalog.checksByRelation` reads it, or against the decoder's arm set for a view column, in
  `typeVerdictMirrorsGraphitronTypeLeaves`'s shape.
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
  item owns the **assertion form** and the mirror's re-point onto the store's own vocabularies, and
  R682 keeps the **coverage ratchet** over them. Neither item blocks the other. Slices 1 and 3 are
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
- `ClassifiedDocTest`, `OutcomeBlockDocTest`, and the paste loop they document ("paste this block
  into the page", "copy the block from the failure message") (slice 3, after their expectations have
  moved).
- "doc example" as a distinct kind of corpus entry, superseded by "a document with a projection".
- Per axis in slice 2, and only as each axis moves: `@classified`, `@classifiedType`,
  `DimensionTuple`, "the dimensional tuple", "the three-axis verdict". Whatever survives slice 2
  retires with R682's zoo and is listed there, not here.
- Explicitly **not** retired, correcting an earlier draft of this spec: the `*MirrorsAdapterValues`
  mirror family. It re-points rather than dying, because a view column's closed vocabulary is closed
  by its decoder and an SDL enum mirrored against that decoder is the top-rung claim available.

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
