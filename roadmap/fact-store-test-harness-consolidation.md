---
id: R680
title: "Give each layer its own fact-store test harness, test each thing where it lives, and drop the rest"
status: In Progress
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-08-14
last-updated: 2026-08-18
---

# Give each layer its own fact-store test harness, test each thing where it lives, and drop the rest

## Problem

Four modules stand a fact store up in their tests, and each arrived at its own way of doing it. No two
modules share a line of it. That is the visible problem, and it is the smaller one.

The larger one is that most of those tests are not testing the module they live in. `graphitron-model`
declares the fact store: 136 tables, 36 views, 146 primary keys and a body of check constraints, in one
DDL file. Most of the harnesses inside `graphitron` exist to assert what those **views** return, and
they get their rows there by running the whole crawler over SDL and a jOOQ catalog. The subject is a
view in a module upstream; the machinery is a pipeline in this one.

So this item has two stages. The first establishes an architecture, so that the next person writing a
fact-store test has an obvious right place to put it and a harness shaped for what they are testing.
The second is cleanup, moving the tests that are already in the wrong place and dropping the ones that
turn out not to be worth a place, since reading every case in order to restate it is the only cheap
moment to notice which is which.

### Most of `graphitron`'s harnesses are testing `graphitron-model`

Fourteen test classes in `graphitron/rewrite/derive/` hand-roll the same harness: open a fact store,
capture one SDL fixture into it, assert against the resulting `DSLContext`. The helper is called
`withCapturedStore` in most of them and is close to verbatim across those. Each also carries its own
`private static Path write(Path directory, String sdl)` writing `fixture.graphqls`, and its own
`private static final String GRAPH = "<OwnClassName>"`. New copies keep arriving, because there is
nothing for a new test author to reach for instead.

### Three subjects, one axis, and the sort falls out

What each class asserts is an `intent_*` relation, but the relation is not what decides where the
test belongs. **The subject does**, and across these fourteen classes there are exactly three
subjects. Naming them is the whole of the classification work, because every case in the population
carries one of them and the module boundary follows.

* **Algebra.** What a relation returns given rows: a view's joins, its outer edges, a check
  constraint's boundary. The subject is the DDL, and the inputs are stateable as rows.
* **Writer.** That a `graphitron` writer puts the right rows in a `CREATE TABLE` relation at its own
  cadence, or that a reach from an external input lands as a row at all. The subject is
  `graphitron`'s code, and the inputs are a real capture.
* **Walk agreement.** That a store-native relation and the transitional classification walk answer
  the same question the same way. The subject is the *equivalence of two implementations*, one of
  them `graphitron`'s.

Algebra goes to `graphitron-model` and gets seeded. Writer and walk agreement stay in `graphitron`
and keep a real capture. That is the sort, and it is mechanical:

| Test class | Asserts on | Declared as | Subject |
|---|---|---|---|
| `ReferenceStepTargetTest` | `intent_field_reference_step_target`, `intent_spelled_table` | view | algebra |
| `FieldColumnTableTest` | `intent_field_column_scope`, `intent_field_column_table` | view | algebra |
| `ClassAssignableTest` | `intent_class_assignable` | view | algebra |
| `FieldProducerMethodTest` | `intent_field_producer_method` | view | algebra |
| `AccessorHopTest` | `intent_field_accessor_hop` and four feeders | view | algebra |
| `ProducerCardinalityTest` | `intent_producer_cardinality_conflict` | view | algebra |
| `ClassMemberSlotTest` | `intent_class_member_slot` | view | algebra + writer |
| `TypeBackingClassTest` | `intent_type_backing`, `_seed`, `_conflict`, **and** `_class` | view + table | algebra + writer |
| `ColumnMatchClaimTest` | `intent_column_match_claim` and three feeders | view | algebra + walk agreement |
| `AuthoredClaimConflictsTest` | `intent_authored_claim_conflict`, `intent_authored_type_claim` | view | algebra + walk agreement |
| `DemandShadowTest` | the demand views, **and** `intent_type_domain` | view + table | writer + walk agreement |
| `InputOccurrenceShadowTest` | `intent_input_occurrence_override`, **and** the path tables | view + table | writer + walk agreement |
| `SeparateFetchTest` | `intent_field_separate_fetch`, **and** `intent_type_backing_class` | view + table | writer + walk agreement |
| `TypeBackingShadowTest` | `intent_type_backing_seed`, **and** `intent_type_backing_class` | view + table | walk agreement |

Six classes are algebra and nothing else, and they move whole. Seven carry two subjects and split.
The fourteenth, `TypeBackingShadowTest`, is walk agreement end to end and stays whole where it is: it writes the
walk's reach with `TypeBackingClassRows.write(dsl, GRAPH, TypeBackingClasses.of(bundle.model()))`
and diffs `walk_type_backing_class` against `intent_type_backing_class` and `intent_type_backing_seed`,
so its view read is one side of a comparison rather than a claim about the view.

**Read the axis rather than the relation kind, because the two disagree.** Relation kind is a good
proxy and it is wrong in three places. `ColumnMatchClaimTest` and `AuthoredClaimConflictsTest` assert
only on views yet are the registered walk-agreement anchors:
`maskedClaimsAgreeWithTheColumnMatchArmOverTheCorpus` sweeps `ClassifiedCorpus.examples()` and
compares the view against the walk's `ColumnBackedField` carrying `CallSiteCompaction.Direct`, and
`AuthoredClaimConflictsTest` routes nearly every case through `AuthoredClaimConflicts.detect`,
asserting `ValidationError` and `Rejection` values with `GatheredFacts.gather` over
`SchemaReachability.walk` as the other side. `TypeBackingShadowTest` asserts on a table and a view
and still has no algebra half at all. Sorting on relation kind puts the first two in the whole-mover
batch and splits the third, and all three are the wrong answer.

The seeded/captured choice follows from the subject and not the other way round. Algebra's inputs
are rows by definition, so it can be seeded and therefore should be. Walk agreement's inputs are an
SDL document both implementations read, so it cannot be seeded without deleting one side of the
comparison.

**Walk agreement is transient, and that is why it must not go down.** These anchors exist for the
strangler window. `FactCaptureAgreementTest`'s own javadoc says so: "These tests retire as consumers
migrate off `GraphitronSchema` piece by piece; they pin a shadow copy, and a shadow with a reader
does not need one." A walk-agreement half seeded into `graphitron-model` would be a permanent
fixture in the module that outlives the thing it polices, and it would have to be deleted again
later by somebody with less context. Keeping these halves in `graphitron`, thin and beside the
implementation they shadow, means they retire with it.

**The tree already contains the correction, and reads it as a hazard.** `ColumnMatchClaimTest` carries
`withSeededStore` plus `seedGraph` / `seedSource` / `seedField` / `seedTable` / `seedSchema` /
`seedColumn`, inserting straight into `STORE_GRAPH`, `STORE_SOURCE`, `GRAPHQL_TYPE`,
`GRAPHQL_TYPE_DECLARATION`, `GRAPHITRON_TABLE` and `SQL_TABLE`; `ReferenceStepTargetTest` carries
`withCollidingKeySeed` plus three more. Every table they touch is generated from the model's own DDL and
nothing in those helpers imports anything from `graphitron`. They exist because capture cannot reach the
states these views need, and today each seeded case owes a javadoc note justifying the reach. That is
backwards. Seeding is the correct way to test a view; reaching it through the crawler is the thing that
needs justifying.

Two of the hardest questions in this item come from the same inversion and dissolve once the layer is
right. Whether the column-match view is node-inference-sensitive is a question about a view, and it
only arises because the test reaches the view through a crawler that takes a `NodeDeclaration`. The
classpath census has to be threaded through eight `derive/` classes only so capture will put
`graphql_class_*` rows in place. Seeded, you insert the rows the view reads and assert on what it
returns. Both questions are about the algebra subject, and the census and the `NodeDeclaration` are
capture plumbing that a seeded case does not have. Where the census *is* the subject, as in
`ClassMemberSlotTest`'s reach from a classfile to a slot row, the axis says writer and the scan stays.

The utility the *capture-shaped* tests want already exists. `capture/CapturedStore` is an
`AutoCloseable` handle offering `of(Path, String)`, `ofPipeline(Path, String)`, a third arm
`ofPipeline(Path, String, String tag)` that puts a tag on the input so `TagLinkSynthesiser` fires (its
only caller is `capture/TaggedCaptureStampTest`, and it has to survive intact rather than be folded
into the two-argument arm), `registryOf`, `attributionOf` and `fixtureFile`. The test classes inside
`capture/` use it happily. It is package-private, so `derive/` and `diagnostics/` cannot see it at all.
`diagnostics/DiagnosticFactsTest.withStore` is the same shape with the capture step removed, so each
case drives capture itself, which is the primitives layer already existing in the tree.

### The three modules downstream

`graphitron-lsp` has `no.sikt.graphitron.lsp.StoreFixture`, read by most of the module's test classes.
It is not a degraded copy: it is largely this item's target design, reached independently, and the
section on it below treats it as prior art rather than as debt.

`graphitron-mcp` has `no.sikt.graphitron.mcp.StoreBackedBuild`, read by `GraphitronMcpServerTest`,
`LintSuppressionDiagnosticsParityTest`, `DiagnosticsAggregateTest` and `ServerInstructionsTest`. This
one is *not* another copy of the capture harness, and the difference is the most important structural
fact in this item. It stands up a **file** store and runs a real
`GraphQLRewriteGenerator.buildOutput()` into it, then plays `DevMojo`'s part over the result. Its
javadoc says why the substrate is the point: "Tests over hand-built reports cannot survive the
substrate: the loaders read the walk's own streams, so the rows a test asserts on have to come from a
real pipeline run." It shares the bottom of the stack with the others and nothing above it.

**And a second fixture beside it, which is this item's thesis demonstrating itself.** The
catalog-facts item added `no.sikt.graphitron.mcp.StoreFixture` while migrating the catalog tools: an
in-memory store plus a direct `FactCapture.capture` call with a `JooqCatalog`, named after the LSP's
fixture and arriving at its `ofCatalog` / `ofMultiSchemaCatalog` / `andGraph` shapes independently for
the second time. **Its factory list is not reproduced here, on purpose.** Two earlier drafts of this
section enumerated it, and both went stale within a day: the catalog-facts item added `ofCodeFixtures`
after the first count and `ofSchema` after the second, while this item sat in Spec. Enumerating a
surface another in-flight item is actively growing is a promise to be wrong, so what this item owes
that fixture is a rule and not a census.

The rule, and the requirement it puts on the shared levels: **every shape that fixture has reached for
so far is a combination of axes already named below, or a writer call, and the implementer should
expect the same of whatever it has grown by the time S7 lands.** The three that decide something are
worth naming as requirements rather than as inventory:

* A warm re-capture into an already-open store. `recaptureCatalog(String jooqPackage)` passes
  `warm = true` to `FactCapture.capture`, and `CatalogSearchIndexTest` calls it four times, once with a
  null package. G1 carries this as an arm from the first day of that slice.
* A capture with no catalog at all, which `withoutCatalog` uses for the pre-codegen state where a graph
  is captured and its census is empty. That is the null-catalog end of the catalog axis, not a sixth
  shape.
* A `JavaSourceFacts` refresh beside a census capture, which `ofCodeFixtures` needs so two families on
  independent cadences can disagree. Census half G1, refresh half G0.

`ofSchema`, the shape that arrived last, is the existing catalog-with-census shape spelled a fifth
time and needs nothing new from either level, which is the rule working. The lesson is the one G0 and
the census-as-argument decision both answer: a population with no named home keeps being rebuilt, and
the fix is to name the home and size the levels so an arrival is a call site, not to time the
migration around when the copying happens to pause.

The MCP fixture exists for a good reason, which is that a census read does not need a build and
`StoreBackedBuild` was pricing the generator into every catalog case, so the fixture is not the mistake;
building it a fourth time is. It is a capture-level copy, so it belongs at G1 in the table below
rather than beside `StoreBackedBuild`. The prediction this item first made about the catalog-facts item,
that it would grow `StoreBackedBuild`, was wrong in the direction that matters: what a consumer reaches
for when no shared harness expresses its shape is not the nearest existing fixture but a new one.

`graphitron-maven-plugin` has no fixture type at all. `DevMojoTest` and `dev/CatalogRefreshTest` open
`GraphitronModelStore.open()` inline and write to it directly, one site each. Small, and exactly the
shape that becomes another named harness the moment a third site appears.

### The facts writers, which are one population the tree splits four ways

A second population never captures SDL at all. It opens a store and drives one of `graphitron`'s own
facts writers by hand. There are exactly four such writers, and the census is not small:

| Writer | Relations | Hand-driven from |
|---|---|---|
| `RejectionFacts` | the rejection family | `diagnostics/DiagnosticFactsTest`, `capture/FactCaptureAgreementTest`, LSP's `RejectionSeverityCoverageTest`, MCP's `DiagnosticsAggregateTest`, `StoreBackedBuild` |
| `CompileFacts` | the javac round | `compile/CompileFactsTest`, `capture/FactCaptureAgreementTest`, `diagnostics/DiagnosticFactsTest`, MCP's `DiagnosticsToolCompileSourceTest`, `DevMojoTest` |
| `JavaSourceFacts` | the `java_` declaration family | `capture/JavaSourceFactsTest`, `dev/CatalogRefreshTest`, both `StoreFixture`s |
| `BuildWarningFacts` | the warning family | `diagnostics/DiagnosticFactsTest`, `capture/FactSchemaGateTest`, `StoreBackedBuild`, LSP's `StoreFixture` |

The table names the files rather than counting the calls, and deliberately: two earlier drafts carried
a total, and the census is a moving surface like the MCP fixture's factory set above. Read the current
count off `grep -rn "new \(no\.sikt\.graphitron\.rewrite\.[a-z]*\.\)\?\(RejectionFacts\|CompileFacts\|JavaSourceFacts\|BuildWarningFacts\)("`
over the test sources. It runs well past twenty sites across every one of the five modules, every one
of them spelling `new <X>Facts(dsl, new FactCapture.GraphIdentity(name, dir))` and calling `write` or
`refresh`.

Three of those sites spell the writer's fully qualified name inline rather than importing it, which is
what a test does when the thing it needs has no home worth importing: LSP's
`RejectionSeverityCoverageTest`, and `capture/FactCaptureAgreementTest` and `capture/FactSchemaGateTest`
between them. The first migrates. The other two are on the exception list and keep their spelling,
which the S6 acceptance below has to say rather than leave to be inferred.

**The item currently routes this one population four different ways, and that is the mistake to fix
before anything else.** The standalone tests are called direct writers and sent to M0; the identical
calls inside `graphitron-lsp`'s `StoreFixture` are called "the capture half" and sent to G1; the ones
inside `capture/` and `diagnostics/` are left alone as oracles; the maven-plugin's two are called
inline sites. They are the same code. `DevMojoTest` constructs
`new CompileFacts(store.dsl(), new GraphIdentity(…))` and `compile/CompileFactsTest` constructs the
same writer the same way; `dev/CatalogRefreshTest` constructs `new JavaSourceFacts(store.dsl())` and
so do `capture/JavaSourceFactsTest` and both downstream fixtures.

So they get a level of their own, **G0: the facts writers over an open store**, sitting between M0
and G1. It needs `graphitron` (the writers and `GraphIdentity` are `graphitron`'s) and it needs no
SDL, no registry, no catalog and no capture. That is a real level rather than a convenience: the
model itself draws this line, and `FactCaptureAgreementTest`'s registry states it, the `java_` family
being "written by neither capture nor a graph, so its lifecycle anchor is partitioned by source file
where the oracle families' are partitioned by graph". Capture cadence and writer cadence are
different facts about the store, and the harness layering should mirror that rather than fold the
writers into the capture handle.

Naming G0 is what makes the rest of this item cheap. The writers stop being a scattered exception
population, the twenty-two sites become calls, `DiagnosticFactsTest` stops hand-rolling three
writers, and the two downstream fixtures keep their own named methods over a shared implementation
instead of each holding a copy. It also decouples two slices that had no business being coupled;
see the sequencing section.

`capture/SourceGraphScopingTest` reads like one of them and is not. It opens a store and never calls
a factory, but every case fills it through `CapturedStore.registryOf` and a `FactCapture.capture` per
graph name, which makes it capture-shaped and a G1 consumer. It is worth naming here because it is the
cleanest caller-supplied graph identity already in the tree: three graphs captured into one store from
one directory, which is exactly the shape the section below lifts to a parameter.

### Where the harnesses stop

What is left over genuinely stays, and the edge matters because it tells the implementer which classes
the layers below are *not* designed for.

**Store-mechanics tests.** `capture/PersistentStoreTest`, `capture/StoreReaderTest` and
`capture/WarmStartRefreshTest` have the store's own lifetime, its warm start, its reader and its
file-backed home as their subject. They open and reopen the same directory deliberately, compare cold
against warm, and hold two handles at once. These cannot adopt an M0 that owns the store's lifetime,
because the lifetime is the thing under assertion. `capture/BrokenSourceStillCapturesPipelineTest`
sits beside them: its subject is what is true of the store when a run fails, so it too holds the store
across the failure itself.

**Capture-oracle tests.** `capture/FactCaptureAgreementTest` and `capture/FactSchemaGateTest` drive
`FactCapture` per view, per arm, with the store's population as the subject. They already read
`CapturedStore` where a factory fits and open directly where none does, which is the primitives layer
this item ships, working as intended. Nothing here needs to change for the item to be finished.

**And one group that does not stay, which is why it is named beside them.** `capture/`
holds a third population that reads like an exception and is not: the DDL gates.
`capture/CommentRenderabilityGateTest` opens a bare store and reads the model's own metadata, the
`REMARKS` of every table and column in `INFORMATION_SCHEMA` and every character-typed value of every
`meta_` relation. It captures nothing, drives no writer, and asserts over no rows but the ones the DDL
ships with. Its subject is `graphitron-model`'s comment prose end to end, so the axis sends it down,
and it is the cheapest mover in the item: it needs M0 and nothing else, there being nothing to seed.
S1 takes it, and its `@UnitTier` annotation goes away with the move, `graphitron`'s tier vocabulary
not travelling to a module that has none.

`capture/FactSchemaGateTest` is the same family and stays anyway, which is worth stating because its
own javadoc calls its gates "siblings of the comment-coverage gate". Six of its seventeen cases are
bare-store DDL queries like that sibling's; the rest read `CapturedStore`, and the class is a capture
oracle first. It keeps one entry for the whole class on the capture-oracle reason rather than earning
the exception list a third reason, and its bare-store cases ride on that entry.

So the exception list carries two groups and this section names three, the third being where the
guard fires on a class the sort had not placed. A harness that tried to serve any of them would be
the does-everything type the next section exists to avoid.

### What the capture-shaped tests still disagree about

The section above removes the algebra half of `derive/` from the capture level. What remains
capture-shaped is real and still has a shared problem: the `capture/` tests,
`diagnostics/DiagnosticFactsTest`, the writer and walk-agreement halves of the seven splitting classes,
`TypeBackingShadowTest` whole, and both downstream fixtures.

The decision being re-made at every one of those sites is *which capture inputs the store under
assertion is built from*, and the sites disagree without anything saying why. Past the 5-arg
`FactCapture.capture` default (which fixes `jooq = null`, `extensions = List.of()`,
`nodes = new NodeDeclaration(null)`), these shapes are live in the tree, while the production paths in
`GraphQLRewriteGenerator` pass `new NodeDeclaration(jooq)`:

| Shape | Representative sites |
|---|---|
| bare | `AuthoredClaimConflictsTest.capture`, `DiagnosticFactsTest`, `CapturedStore.of`, `StoreFixture.of` |
| bare, real classpath census | `StoreFixture.of(directory, sdl, classpath)`, `StoreFixture.ofClasspath` |
| catalog, node inference off | `ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest`, `StoreFixture.ofCatalog`, `StoreFixture.ofMultiSchemaCatalog` |
| catalog, node inference on | `DemandShadowTest`, `InputOccurrenceShadowTest` |
| catalog, node inference off, real classpath census | `ClassMemberSlotTest`, `StoreFixture.ofCatalog(directory, sdl, classpath)` |

Those are the five shapes, and the copies that keep arriving land on them rather than adding a sixth:
almost all pass a `JooqCatalog`, and almost all pass `new NodeDeclaration(null)`. So
catalog-with-inference-off is the dominant shape in the tree and is also the deviation from
production, which sharpens the arm question below rather than changing it.

That split is not cosmetic. `NodeDeclaration` changes what capture writes, and `DemandShadowTest`'s
own sweep comment says its equality is "also the enforcer for the node-inference seed's
over-approximation", which is a claim about the derivation it writes rather than about a view. For the
tests that stay capture-shaped, the shape has to be a named choice rather than a silent default or a
boolean flag. For the tests that move down, the question does not arise: a seeded view test states its
inputs as rows.

The census rows are a different kind of axis from the rest, and saying so is part of the job. The
`extensions` argument the 5-arg default fixes at `List.of()` is per-fixture data, as the SDL string
is: `ClassMemberSlotTest` hands capture a real `ClasspathScanner.scan` filtered to its own three
fixture classes, and `StoreFixture` does the same over the LSP's own fixture package. So the census
is an argument, not a factory axis, and the cross-product it would otherwise force is exactly why:
it appears above beside both the bare and the catalog shapes, and pairing it with each of them as a
named arm doubles the set to say nothing.

Two independent fixtures reached for the census as an argument rather than an axis, which settles it:
it is carried the way `StoreFixture` already carries it, on the factories that take one.

## Implementation

### The goal architecture: each module tests what it owns

One rule decides everything below. **The way a test populates the store follows what the test is
about.** If the subject is algebra, seed the rows the relation reads. If it is a writer, drive the
writer. If it is agreement with the walk, run a real capture and compare. If it is the dev loop's
wiring, run a build. The three subjects named in the Problem section are the same three the harness
levels answer to, which is why the sort is mechanical rather than a judgment call per class.

| Module | What its tests are about | How its store gets populated |
|---|---|---|
| `graphitron-model` | the DDL it declares: constraints, functions, views | direct inserts, seeded per case |
| `graphitron` | the crawlers, the validator, the planner, commands, emitters | a real `FactCapture` run, or a real build |
| `graphitron-lsp` | its own queries over a populated store | its own fixture, over `graphitron`'s |
| `graphitron-mcp` | its own tool logic over a populated store | its own fixture, over `graphitron`'s |
| `graphitron-maven-plugin` | the mojos and the dev loop | writes rows itself, over the model's store |

The model harness is deliberately **limited to direct inserts**, and that limitation is the point. It
cannot run a crawler, so a test written against it cannot accidentally assert crawler behaviour, and it
can reach states no crawler can produce, which is what a view test needs. A harness that does one thing
is what lets it be excellent at that thing; the reason the current `derive/` harnesses are awkward is
that they are general.

**Read the rule as a boundary, not as a preference for seeding.** Seeding is the method in exactly one
module, the one whose subject is the DDL. Above it, in the generator, the language server and the MCP
server, **capture is the default**, and a test there that hand-seeds rows to avoid running the pipeline
is making the same mistake in the opposite direction: those modules exist to turn real inputs into real
rows, so a fixture that skips that step stops testing the thing. This is a decision, not a deferral. A
seeded fixture above the model line is possible and occasionally right, but it owes a reason at the
call site, which is precisely the obligation being lifted from seeded cases inside `graphitron-model`.

### Two homes, five levels

| Home | Level | What it carries |
|---|---|---|
| `graphitron-model` test-jar | M0 | the store's lifetime, in-memory and file-backed, as named entry points |
| `graphitron-model` test-jar | M1 | seeding: named row-inserting helpers over the generated model tables |
| `graphitron` test-jar | G0 | writer-level population: the four facts writers driven over an open store, with the graph identity |
| `graphitron` test-jar | G1 | capture-level population: `FactCapture.capture` into an open store, with the fixture file and its graph identity |
| `graphitron` test-jar | G2 | build-level population: a real `buildOutput()` run into a file store |
| each module, local | L | the module's own read boundary over a populated store |

`graphitron`'s home stands on the model's: G1 takes its store from M0 rather than opening one itself.
Each module keeps its named fixture as a thin local layer, which is why `StoreFixture` and
`StoreBackedBuild` survive under their own names rather than being deleted into a common type, and what
keeps the downstream call sites still.

**Every level has to be usable without the one above it.** A facts-writer test wants M0 and G0 and
never seeds or captures; a view test wants M0 and M1; a crawler test wants M0 and G1; a dev-loop test
wants M0 and G2. G1 does not stand on G0 either: the two are siblings over M0, because capture writes
its own rows and a writer test has no capture to run. A store handle that always came with rows
already in it would leave the writers exactly where they are.

### Why the model can host a harness, and why capture cannot move down

`graphitron-model` has no test sources today and publishes no test-jar, so M0 and M1 are being created
rather than moved. Both are structurally free of `graphitron`. `GraphitronModelStore` imports nothing
but H2, jOOQ and the JDK. The seeding prototypes in the tree insert only into tables generated from the
model's own DDL. Nothing either half needs lives above `graphitron-model`.

The reverse does not hold, and it is worth stating so nobody tries. Capture reads SDL and the jOOQ
catalog, so `FactCapture` cannot move down; a home for it in `graphitron-model` would drag
`RewriteSchemaLoader`, `JooqCatalog` and the schema-input machinery with it, which is the generator,
not the model. G1 therefore lives in `graphitron`, and a single reactor-wide fixtures module for *both*
levels cannot exist: it would have to depend on `graphitron` while `graphitron`'s own tests depend on
it, and Maven cannot express that cycle. Splitting the harness by layer is what removes the cycle,
which is a second reason the architecture above is the right shape rather than merely a tidier one.

`graphitron` publishes a test-jar already, consumed by `graphitron-sakila-example`; `graphitron-model`
gains one. The dependency chain runs `graphitron-model` to `graphitron` to `graphitron-lsp` to
`graphitron-mcp` to `graphitron-maven-plugin`, so every module can reach both homes; each adds the
test-jars it needs at test scope. The jOOQ fixture packages the capture-level fixtures work against
come from `graphitron-sakila-db`, which `graphitron-lsp` and `graphitron-mcp` already depend on at test
scope, so the catalog shapes need no new dependency at all.

Two beneficiaries fall out of putting M0 and M1 in `graphitron-model` rather than in `graphitron`.
`roadmap-tool` depends on `graphitron-model` and not on `graphitron`, and its
`SchemaReferencePagesTest` opens a store; under a single `graphitron`-hosted home that site is
unreachable by construction. What it gains is reach and not enforcement: `GuardScope` excludes
`roadmap-tool` by design, for a reason belonging to the roadmap-reference guard, and the store-fixture
guard inherits that exclusion because the module list is shared.

And `capture/StoreReaderTest` goes home, though not as it stands. Its subject is `StoreReader`, which
is `graphitron-model`'s own class. The reader mints a second connection, sets H2's snapshot level
explicitly at that moment, and makes `read` a transaction that ends in a rollback.

Which of its five cases pin that, and which pin H2, is measured rather than argued. Four mutations,
each breaking one decision `StoreReader` or `GraphitronModelStore` makes, run against the class as it
stands today:

| Case | isolation line dropped | isolation set to read uncommitted | reader handed the writer's connection | reader opens a guessed URL |
|---|---|---|---|---|
| `aReaderSeesWhatTheWriterCommitted` | passes | passes | passes | passes |
| `aRoundStillInFlightIsInvisible` | passes | **fails** | **fails** | passes |
| `oneReadIsOneSnapshot` | **fails** | **fails** | **fails** | passes |
| `aPersistedStoreMintsAReaderOntoItsOwnFile` | passes | passes | passes | **fails** |
| `closingOneReaderLeavesTheStoreAndItsSiblingsReadable` | passes | passes | **errors** | passes |

Three cases go home. `oneReadIsOneSnapshot` is the only catcher of the isolation level, which is
`StoreReader`'s own choice rather than H2's default. `aPersistedStoreMintsAReaderOntoItsOwnFile` is
the only catcher of a reader that opened a database its store never wrote to, which is the hazard
`GraphitronModelStore.reader()`'s javadoc is built around. And
`closingOneReaderLeavesTheStoreAndItsSiblingsReadable` is the only one that notices a reader closing
something it does not own.

Two are deleted rather than migrated, which is the answer to the question the item exists to ask
about them. `aRoundStillInFlightIsInvisible` fails on nothing `oneReadIsOneSnapshot` survives and
passes on a mutation `oneReadIsOneSnapshot` catches, so it is dominated, and the surviving case makes
the stronger claim anyway: a committed round arriving mid-read is harder to hide than an uncommitted
row. `aReaderSeesWhatTheWriterCommitted` caught nothing at all, and no mutation of this class's own
decisions reaches it alone, because every other case reads rows back and so already fails if a reader
cannot see what the writer committed. It is a baseline restated, not a pin.

What keeps the class in `graphitron` is scenery rather than subject. All five cases fill the store
through `CapturedStore.registryOf` and `FactCapture.capture`, but the assertions need only "a
multi-statement round commits here", and the class already writes one of those by hand: its
`writeGraphRow` is the in-flight writer in `aRoundStillInFlightIsInvisible`. Under M1 every capture
call in it restates as a seeded round inside `dsl.transaction(...)`, and the isolation claim survives
whole, because what a reader must not see mid-round is a set of inserts rather than an SDL file. This
is S4's migration applied to a class whose subject was never `graphitron`'s.

Two things must not be read into that. The class stays on the guard's exception list wherever it
lives, since the store's lifetime is still what it holds open. And it carries none of capture's own
atomicity with it: "one transaction end to end" is `FactCapture`'s property, this class never
asserted it, and wanting an anchor for it is a new test in `graphitron` rather than a reason to keep
this one there.

This class is also the item's worked example of the deletion bar, which the Tests section states in
general. It is written out here because it is the first class a slice touches and because the numbers
are already in hand.

The guard needs a matching change: `GuardScope.IN_SCOPE_MODULES` does not list `graphitron-model`
today, so a module that is about to become a test home sits outside the walk. Add it, knowing that
the list is shared and that adding it here adds it everywhere. `GuardScope`'s javadoc says so as the
reason it exists, "One definition, so a new module cannot silently join one guard's scope and not the
other's", so the same line enrols `graphitron-model` in `RoadmapReferenceGuardTest` and
`RetiredVocabularyGuardTest`. That is wanted rather than tolerated. The model's new test sources
should meet the same citation and vocabulary rules as every other module's from their first commit,
and the alternative, a per-guard module list, reintroduces exactly the drift the shared one was
written to stop. The cost lands in S1: two guards start walking a source root that has never been
scanned, so S1 owns whatever they find there rather than discovering it in a later slice.

One objection deserves answering rather than ignoring, because the pom states it outright: the comment
above `graphitron-lsp`'s `graphitron-model` dependency, referring forward to the `graphitron`
dependency below it, says the direction of travel is that this module sheds `graphitron` one type at a
time. A test-jar edge appears to cut against that. It does not, and the architecture above narrows the
edge rather than widening it: LSP tests that only need a populated store reach the model's home, not
`graphitron`'s. `StoreFixture` already imports `FactCapture`, `RewriteSchemaLoader`, `JooqCatalog`,
`NodeDeclaration`, `ClasspathScanner`, `SourceWalker`, `BuildWarningFacts` and `JavaSourceFacts` from
`graphitron`'s main sources, and `StoreBackedBuild` imports `GraphQLRewriteGenerator` itself. The edge
this item adds is a second scope on a dependency already thick in exactly these files, not a new one.
What the pom comment is about is the main-source dependency, and this item does not touch it.

### M1, the seeding harness, and what makes it good

M1 is the harness the model's own tests use, and the tree already holds two working drafts of it.
`ColumnMatchClaimTest` carries `withSeededStore` plus `seedGraph` / `seedSource` / `seedField` /
`seedTable` / `seedSchema` / `seedColumn`; `ReferenceStepTargetTest` carries `withCollidingKeySeed`
plus `seedTable` / `seedRootType` / `seedStep`. Build M1 by merging those two, not by inventing a
third: they already agree on the shape, a named helper per row family, and they disagree only in the
columns each happens to spell.

That disagreement is the first thing M1 fixes. Both spell a full minimal `graphql_type` /
`graphql_field` / `graphql_type_declaration` row by hand, differently, and both are hand-written twins
of what capture writes. A DDL change adding a non-null column should break one place, not two, and
under M1 it breaks in the module whose DDL changed.

**Seeding stops being a hazard and becomes the method.** Today `ReferenceStepTargetTest`'s javadoc
states a permission and a price, "a fixture is free to seed a chain the catalog cannot connect, and the
case then documents behaviour no build can produce", each seeded case carries an escape note, and
`FieldColumnTableTest` reaches the opposite conclusion and refuses to seed at all. That whole apparatus
exists because seeding was the exception inside a capture-shaped harness. In a module whose subject is
the DDL, seeding a state no crawler produces is not an escape, it is the test: a check constraint or a
view's outer join is exactly the thing you want to exercise at its edges.

What survives is the narrower obligation, and it belongs to the *reader* of the test rather than the
writer of the harness: a seeded case asserting behaviour no build can reach should say which real state
it stands in for, or say plainly that it is pinning the relation's own algebra. That is a javadoc habit,
not a harness feature.

**M1 needs no naming convention to separate it from capture.** The old plan gave seeded entry points a
`seeded...` prefix so a grep could tell the two populations apart on one type. Two homes make the module
boundary do that work for free: anything reached through the model's harness is seeded, and anything
reached through `graphitron`'s is captured. That is a stronger signal than a prefix, and it cannot be
got wrong by a test author who does not know the convention.

### G1: promote the store handle, and keep the existing split of responsibility

`CapturedStore` becomes G1. Widen it from package-private and move it beside the shared test support
in `no.sikt.graphitron.rewrite`, which already hosts `TestSchemaHelper`, `TestFixtures` and the
`*RenderTestSupport` classes. That package is also tier-neutral, which matters: the incoming
`graphitron` consumers are all `@PipelineTier`, while the `capture/` classes that already read the
handle straddle both tiers (`FactCaptureAgreementTest`, `TaggedCaptureStampTest`,
`WarmStartRefreshTest` and `BrokenSourceStillCapturesPipelineTest` are `@PipelineTier`, the rest
`@UnitTier`). A home that reads as belonging to
one tier's family invites the next reader to infer a tier rule that does not exist, and the mixed
readership is already the status quo rather than something this item introduces.

The downstream readership settles it. The tier annotations are `graphitron`'s own test vocabulary, and
the tests in `graphitron-lsp`, `graphitron-mcp` and `graphitron-maven-plugin` carry none of them; the
handle is about to be read from three modules where the question does not arise. A tier-suggestive
home would have been misleading before and would be plainly wrong after.

Do **not** let G1 absorb the parse side. `TestSchemaHelper` already owns the parse-side
primitives (`attribution`, `nodeDeclaration`, `buildSchema`) and the derive tests already call it.
G1 owns the fixture file and the capture call, and takes the store's lifetime from M0. Keep that
split; the move must not duplicate `TestSchemaHelper.attribution` under a second name.

Expose `registryOf` / `attributionOf` / `fixtureFile` publicly alongside the factories. The
capture-shaped sweeps drive
`FactCapture.capture` themselves, per example, and need the primitives without a factory arm for every
combination; so does `DiagnosticFactsTest` with the one case that passes real `SdlVerdicts` alongside
the `warm` flag.
(The `warm` flag itself is not a primitives-only concern: MCP's `recaptureCatalog` is a named factory
built on it, so G1 carries it as an arm and the primitives carry it for the cases no arm names.)
That is the third layer, under the handle: a test whose axis combination no factory names writes the
capture call itself off shared primitives instead of hand-rolling the file, the attribution and the
store lifetime with it. Naming the primitives as a layer is what keeps the factory set from having to
be the cross-product of catalog, nodes, registry source, extensions and verdicts.

### The governing rule: add the shape at the layer that owns the subject

A test that needs a store shape no existing harness produces adds it to the harness for the layer its
*subject* belongs to, rather than hand-rolling a helper in its own class. A view needs a new seed
helper: it goes in M1. A crawler case needs a new capture shape: it goes in G1. That is the rule this
item establishes, and it outranks every individual shape decision below.

The failure mode being designed against is fragmentation, not accretion. A spread of private copies
that have quietly diverged is the expensive state, and it is expensive because nothing points a new
test author at the existing answer. A harness carrying more helpers than any one reader needs is the
cheap state: they are in one file, visible together, and consolidating two that turned out to be the
same is a mechanical afternoon. Growth is expected and fine. If a set gets unwieldy, clean it then,
with the whole set in view, which is exactly the vantage point the scattered private copies deny.

So each shape set below is a starting point, not a closed taxonomy, and a later item adding a helper is
the design working rather than failing.

The rule needs two carriers, because it has two audiences. For the author already in the file, it is
stated in each harness's class javadoc, as the orientation note a reader meets first, naming what that
harness is for and what the *other* one is for. For the author in another module who will never open
either, javadoc is worth nothing, and that is the audience this item is really about. They get a guard.

### A guard, because javadoc cannot reach the author who never opens the file

Within one module, "the utility is public and sits next to `TestSchemaHelper` in a package you already
import" would be enough, and a guard would be maintenance surface bought for nothing. Across four
modules the argument collapses: an MCP test author imports nothing from `no.sikt.graphitron.rewrite`,
and no amount of javadoc on a type they cannot see will reach them. The evidence is the item itself.
Every module that needed a store built its own way in, and the pattern is still running: the
post-capture writers have already twinned between `StoreFixture` and `capture/JavaSourceFactsTest`,
and the maven-plugin's two inline sites are a harness that has not been named yet.

So the item ships one enforcer, and the machinery already exists.
`no.sikt.graphitron.rewrite.GuardScope` enumerates every in-scope module root and locates the
repository root by walking up to the `roadmap/workflow.adoc` anchor; `RoadmapReferenceGuardTest` and
`RetiredVocabularyGuardTest` already walk it. Its javadoc states why it is shared: "One definition, so
a new module cannot silently join one guard's scope and not the other's", which is exactly the failure
mode a store-fixture rule has. `GuardScope` is package-private in `no.sikt.graphitron.rewrite`, so the
new guard sits beside its siblings with no visibility change; the walk gains `graphitron-model` as
noted above, and the guard reads that module's test sources like any other.

**One recogniser: a test-source reference to `GraphitronModelStore` outside a harness.** The type, not
one factory method, because `open()` mints an in-memory database and `openAt(Path)` a file-backed one,
and M0 is required to carry both named rather than flagged. Naming the type also survives a third
factory being added.

Resist the temptation to add a second recogniser over hand-rolled `.graphqls` writes. Qualifying it by
the store makes it a subset of the recogniser above, so it can never reach a class that one does not;
leaving it unqualified sweeps in every watcher, emitter, mojo and parse test that writes an SDL file
without going near a store, which is a large permanent exception list bought for nothing. The guard
answers one question, "did you stand a store up outside a harness", and it should never grow a second.

**The failure message asks for the subject, and that is the guard's whole job.** It should not say "use
the shared home", because there are two and picking the wrong one is exactly the mistake this item is
correcting. It should ask the one question that decides the layer, *what is this test about*, and give
the four answers: a relation's algebra, seed it with the model's harness; a facts writer, drive it
through G0; the crawler or agreement with the walk, capture through G1; your module's own reads, put a
fixture over one of those. An author who trips this guard should come away having classified their own
test, not merely knowing they typed the wrong class name.

**Exceptions are named, with reasons, and there is no arithmetic over them.** Only the classes in
"Where the harnesses stop" earn an entry, and there are exactly two reasons: the store's lifetime is
the subject, or the class is a capture oracle driving `FactCapture` per arm. The facts writers are not
on this list, because they migrate to G0. Follow `RetiredVocabularyGuardTest`'s shape, a record per entry with
an assertion that the entry is still real, so an entry naming a class that no longer stands a store up
fails the build instead of lingering. That is the only bookkeeping the guard owes: the list stays
honest, and nobody has to count it.

The guard closes stage 1, because its message is the architecture stated to whoever needs it most, and
it cannot say "seed it with the model's harness" before that harness exists. It carries temporary
entries for the stage 2 populations, which drain as those slices land.

### `graphitron-lsp`: what of `StoreFixture` moves

`StoreFixture` is the strongest evidence the governing rule is right, because it is what happens
without one. Its class javadoc opens on almost the same sentence as `CapturedStore`'s. It carries
named factories rather than flags (`of`, `ofClasspath`, `ofCatalog`, `ofMultiSchemaCatalog`), each
with the one-line note on what its shape carries that a sibling cannot that the section below
prescribes. It takes a caller-supplied graph name over a shared default, captures a second graph into
an already-open store (`andGraph`, `andGraphSharingTheFile`), and takes the classpath census as a
factory argument rather than an axis. Its placeholder SDL constant is character-for-character
`ClassMemberSlotTest`'s, `ClassAssignableTest`'s and MCP's `StoreFixture`'s. Nine files declare that
literal as a constant, under three different names, with the same string written inline in a dozen more.
It also makes the same unexamined `new NodeDeclaration(null)` choice. Independent files, no contact,
converging on the same answers and disagreeing on the rest by accident: that is the state this item
exists to end, and it does not stop at a module line.

**The name and the call sites stay.** Most of the module's test classes call `StoreFixture`, so the
move is not a migration of those call sites. `StoreFixture` remains, under its own name, as
`graphitron-lsp`'s
local layer: it keeps its factory signatures and delegates the store's lifetime, the fixture file and
the capture call to the shared handle. Its own tests should not need editing, and the references to
it in the LSP item's body stay live. If a call site has to change, that is a signal the shared handle
cannot express a shape the LSP needs, which is the finding, not a licence to edit the test.

**What moves** is the capture half: the store's lifetime, the fixture write, `registryOf` /
`attributionOf` / `fixtureFile`, the named capture factories, the caller-supplied graph name, capture
of a further graph into an open store, and the classpath-census argument.

**What stays in `graphitron-lsp`** is everything whose subject is the LSP's own read boundary rather
than the store's shape: `handle()`, `handleFor()`, `reader()`, `tableClassFqn`, `keysClassFqn`,
`backingClasses()`, the whole family of `CompletionData` builders (`jarClass`, `reactorClass`,
`scalarHolder`, `jarRecord`, `reference`, `method`, `component`, `parameter`, `producing`,
`genericMethod`, `genericParameter`), the `no.sikt.graphitron.lsp.fixtures` census and the jOOQ
fixture-package constants. Treat that list as the shape of the answer rather than as exhaustive: the
test is whether a member's subject is the LSP's reads or the store's construction.

`sourceName()` is the one member that does not sort cleanly, and it is worth settling rather than
discovering. It returns `SchemaSource.file(file).sourceName()`, derived from the fixture file G1 is
taking over, so its value is a fact about the shared level while its callers are LSP reads. Keep the
method in `graphitron-lsp` and have it delegate to G1's `fixtureFile`, so the LSP does not hold a
second opinion about where the fixture lives.

**The post-capture writers move to G0, not to G1, and the distinction is what unblocks the slice.**
`withBuildWarnings`, `withJavaSource` and `refreshJavaSources` do not capture anything. They construct
`BuildWarningFacts` and `JavaSourceFacts` over an already-open store, which is G0's whole job, and
`capture/JavaSourceFactsTest`, `dev/CatalogRefreshTest` and MCP's own fixture drive the same two
writers the same way. Calling them a capture half was the error that made this slice look like it had
to wait for the LSP item: G1's factory set was going to have to grow arms for them, so it mattered
whether their signatures were still moving. At G0 they are three named methods delegating to one
writer layer, and a fourth arriving later is a call site rather than a redesign.

`withJavaSource` is the one that does not fully sort: it writes a `.java` file to disk and then
refreshes, so it is a fixture-authoring convenience over G0 rather than G0 itself. Keep it in
`graphitron-lsp` writing the file, and have it delegate the refresh, the same way `sourceName()`
delegates `fixtureFile`.

### `graphitron-mcp`: `StoreBackedBuild` onto the shared floor

`StoreBackedBuild` keeps its name, its `run(...)` factories and every one of its call sites. What it
stops owning is the floor: the store's lifetime and the schema file it writes. Today it resolves
`tmp.resolve("schema.graphqls")` and manages its own `GraphitronModelStore`, which is the third
independent answer in the tree to "where does the fixture file go and who closes the store."

It does **not** move to G1. It has no `FactCapture.capture` call to share, because its population is a
real `buildOutput()` run, and pushing it onto the capture factories would destroy the property its
javadoc is built on. G2 is its level, and G2 stands on M0 like everything else. If a second
build-level fixture is wanted later, G2 is where it gathers.

One thing to settle while there: `StoreBackedBuild` uses a **file** store, the others in-memory. That
is a real axis, not an accident, since its subject is the dev loop's own wiring. M0 has to carry both,
named, the same way the capture shapes are named rather than flagged.

### `graphitron-maven-plugin`: two inline sites, converted

`DevMojoTest` and `dev/CatalogRefreshTest` open a store inline and write to it directly. They adopt
M0 and G0; neither needs a capture factory. This is the smallest part of the
item and the one most likely to be dropped for being small, which is precisely why it is written down:
the guard will fail on these two sites, and an exception entry added to silence it would be this item
defeating itself.

They are also the reason the facts writers elsewhere are converted rather than excepted. These two are
line-for-line what `compile/CompileFactsTest` and `capture/JavaSourceFactsTest` do, down to the writer
class each constructs. A rule that converts them and excepts their twins is not a rule, it is a
coincidence of which module the file happens to sit in.

### Sequencing against the in-flight items

Both dependencies are live in code this item touches. Only the LSP one is declared in `depends-on`, for
the reason the paragraphs below give.

* The LSP item is In Progress and has touched `StoreFixture` in most of its recent commits, including
  the post-capture writers this item moves.
* The catalog-facts item is In Progress. It began by moving the MCP catalog tests onto
  `StoreBackedBuild` and then moved them off again onto a capture-level fixture of its own, for the
  reason the Problem section records, so what it hands over is one more capture-level copy rather than a
  grown build-level one.

**Neither dependency should be predicated on a fixture having stopped moving, and an earlier draft of
this section was.** It said the catalog-facts fixture had settled, and it had not: that item grew
`ofCodeFixtures` and a second `refreshJavaSources` afterwards. A premise about what another item is
currently typing decays between the Spec gate and the slice, so this item does not carry one. Two
structural facts carry the sequencing instead, and both survive the other items continuing to grow:

* **A local layer absorbs growth; a shared factory set does not.** S7 and S8 leave every call site
  standing and turn each fixture into a delegation. A shape the other item adds meanwhile is one more
  delegating method, not a redesign, so neither slice needs the other item to be finished. This is
  what the "no caller is edited" acceptance already buys, made explicit.
* **The levels are sized so growth lands on a call site.** G0 takes writers by name and G1 takes the
  census as an argument rather than an axis, which is exactly why `ofCodeFixtures` needed nothing new
  from either level when it appeared. A level that had enumerated combinations would have needed an
  arm per arrival.

So `depends-on` keeps the LSP item and drops the catalog-facts one, on file overlap alone: the LSP
item is actively rewriting `StoreFixture` and S8 would collide with it in the editor, while nothing
this item does inside `graphitron` touches a file either in-flight item holds. That is a merge
concern with a clear resolution, which is why S8 is last.

The cost of waiting on the catalog-facts item would have been concrete rather than a preference. Its
remaining tool slices each need shapes the LSP's fixture already carries: the classpath census its
code-tools slice reads is `StoreFixture.ofClasspath`, the source locations it renders are
`withJavaSource` / `refreshJavaSources`, and the diagnostics axes its status slice reads are
`withBuildWarnings`. Left to wait, that item re-derives most of the LSP's fixture inside
`graphitron-mcp` one slice at a time, which is this item's own thesis running again. It has now done
exactly that once, which is the argument holding rather than failing.

### Name the shapes; do not flag them

Give the G1 handle named factories, one per capture shape in the table above, with the census carried
as an argument rather than doubling the set. Not nullable arguments, and not a `boolean`. Named entry
points are what make the set legible enough to prune
later; a growing pile of flags is not. Each factory carries a one-line javadoc note saying what its
shape carries that its sibling cannot. Writing those notes is the work: if the note for the
node-inference-off shape turns out to read "nothing, these tests just never needed nodes," that is
the finding, and the set collapses to two.
Resolving the `new NodeDeclaration(null)` versus `TestSchemaHelper.nodeDeclaration()` split is in
scope and must not be preserved silently on the grounds that it is what the copies did. Start from
the production shape: `GraphQLRewriteGenerator` passes `new NodeDeclaration(jooq)` on both of its
capture paths, so catalog-backed inference is what every real capture sees, and the bare
`NodeDeclaration(null)` in three of these tests is the deviation. That makes inference-on the default
a factory should carry and inference-off the arm that owes a note naming the case that needs it. It
is the same argument the registry-source section below runs, applied to the other axis.

Watch the direction of evidence while resolving it. Switching a test to inference-on and finding the
suite still green does not show the axis is inert; it shows this fixture does not reach it, which is
the weaker claim and not the one a collapsed factory set would be asserting.

**An arm nothing distinguishes is decoration, so the resolution owes a discriminating case.**
Inference-off is both the deviation from production and the majority of the sites, which is what makes
it tempting to keep unexamined. Keeping it as a named arm is only honest if at least one case in the
suite *fails* when
the arm flips. `DemandShadowTest` is the natural home, its own sweep comment already claiming to be
"the enforcer for the node-inference seed's over-approximation". If no such case can be written, that
is the finding and the two arms collapse into one, which is the outcome this section says it is willing
to reach.

### Layered: a closure convenience over a resource handle over the primitives

`capture/` uses `try (var store = CapturedStore.of(tmp, FIXTURE))`; `derive/` uses
`withCapturedStore(sdl, dsl -> {...})`. Ship both, layered over the exposed primitives, with the
closure form as the one most tests reach for.

The goal here is good common tools, not a single sanctioned way to open a store. The closure form is
genuinely the nicer call site for the common case, which is most of the migrating classes: hand it SDL,
get a `DSLContext`, assert. It should stay, and it should be the shortest thing to type. The
resource handle sits underneath it as the primitive, for the tests that need more than one step
against the open store.

That layering is what keeps the convenience form from accreting. The pressure is real: every extra
axis is "and then also write X after capture" (`ClaimDomainRows.write`, a second graph's capture, the
rejection and warning facts in `diagnostics/`), and today those arrive as a new parameter or overload
because there is nowhere else for them to go. `FieldColumnTableTest` grew its boolean flag that way,
and `AuthoredClaimConflictsTest.detectionAgainstWalk` gave up and re-opened the store by hand, as
does `siblingGraphConflictsDoNotLeak` inline. With a primitive available, those stop being deformations
of the closure form and become ordinary calls in a `try` block, and the closure form gets to stay
simple because it no longer has to be the only door.

`diagnostics/DiagnosticFactsTest.withStore` is worth reading as the primitive layer already existing
in the tree rather than as another copy of the closure form: it opens a store, captures nothing, and
lets each case drive capture itself, which is how the one case carrying real `SdlVerdicts` and an
explicit `warm` flag stays expressible without either argument reaching the shared harness.

So: reach for the closure form first, drop to the handle when a test needs a second step against the
open store. `DiagnosticFactsTest`, `ClassMemberSlotTest`'s surviving scan case, and
`AuthoredClaimConflictsTest`'s two hand-rolled re-opens are the first callers of the handle.
`FieldColumnTableTest`'s `withCapturedStoreAndClaimDomain` is not among them: that class is a whole
mover to M1, where `ClaimDomainRows.write(dsl, GRAPH, ClaimDomain.of(...))` is replaced by seeding
the `intent_type_domain` rows it was there to produce, and the boolean flag disappears with it. It
earns a mention here only as the clearest example of the pressure the handle relieves. Neither layer is capped; per the rule
above, a genuinely common new shape earns a `with(...)` arm rather than being pushed down to the
handle on principle.

`StoreFixture` is a caller of the handle too, and the one that shows why the handle layer has to be
public rather than an implementation detail of the closure form. It is itself a layer, holding a
store open across a capture, a warning write, a Java-source refresh and a series of reads, which is
the multi-step shape the closure form deliberately does not serve. A handle that only existed
underneath `withCapturedStore` would have left it hand-rolling the store's lifetime exactly as it
does today.

### The graph identity is caller-supplied

`CapturedStore.graph(Path)` hardcodes the graph name to the literal `"CapturedStore"`. That is the
one thing that makes it unusable for the current consumers, and it must become a parameter. The
literal appears twice, and both occurrences have to move together: `ofPipeline` spells it again as
the `graphName` component of the `RewriteContext` it builds, so threading the name through
`graph(Path)` alone would leave the pipeline arm attributing under a name its caller never asked for.

The per-class `GRAPH = "<ClassName>"` value is incidental: `GraphitronModelStore.open()` mints a
private in-memory database per call, so the constant buys no cross-class isolation. What is
load-bearing is that the *caller* names the graph, for two populations:

* The corpus sweeps in `ColumnMatchClaimTest`, `DemandShadowTest` and `InputOccurrenceShadowTest`
  capture one graph per example into a single store (`new GraphIdentity(example.id(), dir)`) and read
  the partition dimension as the assertion itself.
* The sibling-partition negatives in `FieldColumnTableTest`, `ReferenceStepTargetTest`,
  `ColumnMatchClaimTest` and `AuthoredClaimConflictsTest` need a second, distinct name to have
  anything to assert against.
* `StoreFixture`'s `andGraph` and `andGraphSharingTheFile` capture a further graph into a store that
  is already open, which is a third population and the one that decides the shape below.

Migrating without lifting the name to a parameter would either leave the sweeps hand-rolled, which
is where the duplication is largest, or flatten the partition they assert on.

The directory cannot be dropped either, and for a reason worth stating because it fails silently.
`RewriteSchemaLoader.load` takes `Collection<SchemaSource.File>`, the file arm specifically, so SDL
held as a string literal has to be materialized before anything can parse it. The path it is written
to then *is* its identity downstream: `SchemaSource.File.sourceName()` is the absolute normalized
path, which is the string handed to the parser, the string graphql-java echoes back as
`SourceLocation.getSourceName()`, and the key `SchemaInputAttribution`'s map and capture's stamp
lookup are both read on. Since `fixtureFile` hardcodes the name `fixture.graphqls`, the directory is
today the whole of a fixture's identity: one directory, one fixture, one graph. A handle that took a
graph name but reused one directory would have each capture overwrite the previous fixture with no
error, because the load would still succeed against whatever text landed there last.

Take `StoreFixture`'s answer to this: key the fixture filename on the graph name
(`directory.resolve(graphName + ".graphqls")`), so the entry points take a `(name, directory)` pair
and the name carries the identity the directory was standing in for. The alternative this item
considered first, minting per-graph subdirectories the way the sweeps do by hand
(`Files.createDirectories(tmp.resolve(example.id()))`), is the weaker answer, and the third
population above is why: `andGraphSharingTheFile` captures two graphs from *one* schema file, one
document with two memberships, both true. Subdirectory-per-graph cannot express that at all, because
it makes the file's location a function of the graph. Filename-per-graph expresses it by letting a
caller hand over a file it already has.

One site asserts on the literal `fixture.graphqls`, and the implementer has to meet it knowingly.
`capture/WarmStartRefreshTest` writes its own fixture under that name and then asserts the re-expansion
finds it, comparing the absolute normalized path as a string (`containsExactlyInAnyOrder(tmp.resolve(
"fixture.graphqls")...)`). Its subject is the stamp and the recipe expansion, so the filename is
incidental to what it pins and the assertion moves with the rename. This is also the one place where
the acceptance rule below, that an assertion needing to change signals a load-bearing axis, does not
apply: here the changed assertion *is* the fixture's own name, which is what the rename is. Say so at
the call site when it changes.

Every hand-rolled `write` copy hardcodes `directory.resolve("fixture.graphqls")` too, so the rename is
one line per copy, taken as each copy migrates. The sweeps then stop minting per-graph subdirectories,
which is a hand-rolled step deleted rather than moved.

One line of those copies has to survive the merge: each calls `Files.createDirectories` before writing
and `CapturedStore.write` does not, because every `capture/` caller hands it a `@TempDir` that already
exists. Every incoming caller writes into a directory it named itself, so the shared `write` needs that
line or the sweeps and the sibling-partition negatives fail on the first subdirectory.

### Make the registry source an explicit arm

Every `derive/` helper feeds capture a bare `RewriteSchemaLoader.load` registry, as does
`DiagnosticFactsTest` in each of its own capture calls, and as does every `StoreFixture` factory.
Production capture reads the attribution pipeline's pre-synthesis registry, and
`CapturedStore.ofPipeline` already exists for that, with javadoc stating the hazard: a bare parse
lets capture's macro expansion mint what the rewrite has already put there in the pipeline, so the
store agrees with the model for the wrong reason.

Today that choice is invisible because every copy makes it identically, by accident rather than
agreement. After consolidation it is one line, and whichever arm the factory defaults to becomes the
implicit claim about which registry these views are derived from. No current derive or LSP fixture is
federation-shaped, so nothing is broken; name it as an explicit arm now while the decision is cheap,
rather than leaving a default nobody revisits when the first federation-shaped fixture lands.

"Explicit arm" here means the existing naming convention carries it, not that every call site passes
an argument: bare parse is the unmarked name (`of...`, `withCapturedStore`) and the pipeline registry
is the marked one (`ofPipeline...`), which is already how `capture/` reads and keeps the convenience
form the shortest thing to type. The claim is legible because the marked name is what a `grep`
separates on, not because the unmarked call spells its choice out.

### Update the agreement anchors

`FactCaptureAgreementTest`'s class javadoc cites the migrating classes by fully qualified name as the
registered per-view agreement anchors. Convert those citations from `{@code}` to `{@link}` while
touching them. `{@code}` is invisible to the javadoc reference gate, so a rename or move rots the
citation silently, and every cited name resolves to a live class today, which makes the conversion
mechanical.

Two traps. One anchor, `TypeBackingClassesTest`, is cited the same way but in a *method* javadoc
further down the same file, so an implementer sweeping the class javadoc alone will miss it. And the
gate runs javadoc against main sources, so it will not maintain a citation living in a test class;
converting to `{@link}` still buys the compiler's own resolution of the reference, which is what stops
the silent rot.

`docs/architecture/explanation/fact-model.adoc` cites several of the same classes in backticks, which
nothing checks at all, so any class rename in this item must update that surface by hand. It also
places them: "per-view anchors (`AuthoredClaimConflictsTest`, `ColumnMatchClaimTest`,
`DemandShadowTest`, `InputOccurrenceShadowTest` in `rewrite/derive`)". S4 and S5 falsify the location
as well as the citation, so both need the same pass.

This section has no slice of its own on purpose, because there is no moment at which it is the work.
It is acceptance on S4 and S5 instead: a batch that moves an anchor class updates
`FactCaptureAgreementTest`'s citation for it, in the class javadoc and in the `TypeBackingClassesTest`
method javadoc further down, and updates `fact-model.adoc` in the same commit. A moved class whose
citations still name the old home is the slice not finished.

## Slices

Two stages. Stage 1 builds the architecture and proves it, so that from its last commit onward a test
author has a right place to put a new test and a harness shaped for it. Stage 2 moves the tests that
are already in the wrong place, weighs each case as it passes through, and drops the ones that pin
nothing this project decides. One population per slice, and none of it blocks anybody.

The split matters because the stages have different value profiles. Stage 1 stops the bleeding and is
worth landing even if stage 2 stalls; stage 2 is cleanup that can be taken a slice at a time by
whoever has the appetite.

### Stage 1: the architecture

**S1: M0 and M1, the model's harness.** `graphitron-model` gains test sources and a test-jar. M0 is the
store's lifetime with `open()` and `openAt(Path)` as named entry points; M1 is the seeding harness,
merged from `ColumnMatchClaimTest`'s six seed helpers and `ReferenceStepTargetTest`'s three, with the
`graphql_type` / `graphql_field` / `graphql_type_declaration` twins reconciled into one spelling.
`GuardScope.IN_SCOPE_MODULES` gains `graphitron-model`.

Proof, not assertion: this slice also moves `capture/StoreReaderTest` down as three cases rather than
five, restating its capture calls as seeded rounds and deleting the two the mutation table above
condemns, and migrates **one** pure view test end to end, from capture-driven to seeded, keeping its
assertions. `ClassAssignableTest` is the natural pick, and not merely for being
small: `FactCaptureAgreementTest`'s registry already records that it binds `intent_class_assignable`
"to a census built reference by reference", the chains it needs being "ones a scan of compiled
fixtures cannot arrange". It is the one mover whose inputs are already stated as rows in all but
name, so the migration tests M1 rather than the migrator. If it cannot be done without reaching for
something M1 does not have, the harness is not finished and the finding belongs in this slice rather
than in stage 2.

`capture/CommentRenderabilityGateTest` moves in this slice too, and it is the one mover that tests M0
alone: it reads the DDL's own comment prose off an empty store, so it needs the store's lifetime and no
seeding at all. It drops its `@UnitTier` annotation on the way, and it goes here rather than in stage 2
because a class needing nothing but M0 proves M0 is reachable from another module the moment M0 exists.
Acceptance: it passes in `graphitron-model` with its assertions unchanged, and `graphitron` no longer
holds a test whose only subject is the model's DDL comments.

**S2: G1 and G2, `graphitron`'s harness.** `CapturedStore` widened and moved beside `TestSchemaHelper`,
taking its store from M0 rather than opening one. The fixture file with its filename keyed on the graph
name, the caller-supplied graph identity, `registryOf` / `attributionOf` / `fixtureFile` public, the
named capture factories with the census as an argument, the registry arm, and the warm re-capture arm
MCP's `recaptureCatalog` needs. G2 is `StoreBackedBuild`'s level, defined here even though its only
inhabitant moves onto it in stage 2. Its current `capture/` readers move with it. Acceptance: those
readers pass untouched except for the import, and the `WarmStartRefreshTest` filename assertion moves
knowingly.

**S3: the guard.** One recogniser, the layer-routing failure message, and an exception list carrying
both the permanent entries and temporary ones for every stage 2 population not yet migrated. Acceptance:
it fails on a source that stands a store up outside a harness, proved by a negative case; it fails on a
stale entry, proved the same way; it passes on the tree; and its message routes by subject rather than
naming one home.

Stage 1 is done when a new test has an obvious home. That is also the point at which this item could be
stopped without leaving a mess.

### Stage 2: cleanup

**S4: the six pure-algebra classes that move whole.** `ReferenceStepTargetTest`,
`FieldColumnTableTest`, `ClassAssignableTest` (already moved in S1), `FieldProducerMethodTest`,
`AccessorHopTest` and `ProducerCardinalityTest` move to `graphitron-model` and become seeded. Take
them in batches. Acceptance: every case is either kept with its assertion content intact or deleted
under the mutation bar with the mutations recorded, and each class loses its `write(`, its `GRAPH`
constant, its census plumbing and its `NodeDeclaration` argument, because a seeded view test needs
none of them. A class that cannot lose those is telling you the subject axis was read wrong for it,
which is a finding worth recording rather than working around.

**S5: the seven classes that split.** `ColumnMatchClaimTest`, `ClassMemberSlotTest`,
`AuthoredClaimConflictsTest`, `DemandShadowTest`, `InputOccurrenceShadowTest`, `SeparateFetchTest` and
`TypeBackingClassTest` split on the subject axis rather than on relation kind, which is the same
seam in every case: the algebra cases go down to M1 and become seeded; the writer and walk-agreement
cases stay in `graphitron` on G0 or G1 and keep a real capture. Take them one class at a time, since
each one's seam has to be read case by case. Acceptance: each half asserts what its cases always
asserted, minus whatever the mutation bar condemns; the `graphitron` half still runs a real capture;
and the seeded half names no `graphitron` type at all, which is the mechanical check that the seam was
cut in the right place.

`TypeBackingShadowTest` is not in this slice and does not move. It is walk agreement end to end:
`TypeBackingClassRows.write(dsl, GRAPH, TypeBackingClasses.of(bundle.model()))` puts the walk's own
reach in the store and the cases diff it against `intent_type_backing_class` and
`intent_type_backing_seed`. Its view read is one side of a comparison, so there is no algebra half to
send down. It stays where it is, unedited beyond the G0 call for its writer.

Where each seam runs, so the implementer is not rediscovering it:

* `ColumnMatchClaimTest`. Its seeded cases already exist under `withSeededStore` and are the algebra
  half nearly verbatim. Staying: `maskedClaimsAgreeWithTheColumnMatchArmOverTheCorpus`, which sweeps
  `ClassifiedCorpus.examples()` and compares the view against the walk's `ColumnBackedField` carrying
  `CallSiteCompaction.Direct`, and the `AuthoredClaim.values()` vocabulary round trip. Both name
  `graphitron` main-source types as the *expected* value, which is the tell.
* `AuthoredClaimConflictsTest`. Mostly walk agreement, and the exception to the usual proportion:
  nearly every case reads `AuthoredClaimConflicts.detect(dsl, GRAPH)` and asserts `ValidationError`
  and `Rejection` values, with `GatheredFacts.gather` over `SchemaReachability.walk` as the other
  side. Going down: only cases that read `intent_authored_field_claim` or `intent_authored_type_claim`
  and assert on rows. Expect that to be a small minority, and expect the class to stay in
  `graphitron` under its own name.
* `ClassMemberSlotTest`. The seam is the census, and `FactCaptureAgreementTest`'s registry already
  states the reason: it binds `intent_class_member_slot` "over a real classfile scan of its own
  fixtures rather than seeded census rows, because a rule that reads a class's declared form cannot
  be pinned against a fixture that declares its own." Cases that state member rows and assert what
  the view makes of them go down to M1, which is most of the class. One case stays on G1, running a
  real `ClasspathScanner.scan` over the three fixture classes and asserting the slots arrive from a
  class whose declared form the test did not write. Keeping it to one is the point:
  `FactCaptureAgreementTest`'s `EQUALITY` arm already pins the scanner census against the walk, so
  this is the end-to-end witness rather than a second census oracle.
* `DemandShadowTest`, `InputOccurrenceShadowTest`, `SeparateFetchTest`, `TypeBackingClassTest`. The
  staying halves are the assertions on `intent_type_domain`, `intent_input_occurrence_path` /
  `_path_step` and `intent_type_backing_class`, whose rows `ReachabilityRows`, `InputOccurrencePaths`
  and `TypeBackingRows` write, plus `DemandShadowTest`'s and `InputOccurrenceShadowTest`'s corpus
  sweeps and `SeparateFetchTest`'s `GatheredFacts.delivery` differential.

Acceptance for every split class: `FactCaptureAgreementTest`'s registry note for that anchor is
rewritten to say which half now carries the reason, since as written each describes a class that no
longer exists in one piece.

**S6: the facts writers adopt G0.** Every site in the writer census that is not already on the
exception list: `graphitron`'s `compile/CompileFactsTest`, `capture/JavaSourceFactsTest` and
`diagnostics/DiagnosticFactsTest`, the maven plugin's `DevMojoTest` and `dev/CatalogRefreshTest`, and
downstream `RejectionSeverityCoverageTest`, `DiagnosticsToolCompileSourceTest` and
`DiagnosticsAggregateTest`'s three own-store cases. Read the population off the census grep in the
Problem section rather than off this list, which is the same rule S7 follows for the MCP fixture.
Acceptance: none of the migrating sites still names `GraphitronModelStore` or constructs a `*Facts`
writer by hand, `RejectionSeverityCoverageTest` no longer spells its writer's fully qualified name
inline, and the maven-plugin pair is not left inline on the grounds that it is only two, which is the
way this slice fails. `capture/FactCaptureAgreementTest` and `capture/FactSchemaGateTest` keep their own
writer calls and their inline fully qualified spellings, being capture oracles on the exception list;
either may adopt G0 for the construction if that reads better, which is the implementer's call and not
an acceptance condition.

**S7: `graphitron-mcp`.** Its `StoreFixture` becomes a local layer over G1 and G0, keeping **whatever
factory set it has when the slice starts** intact, each one delegating its capture to G1 and its writer
calls to G0. Read the set off the file rather than off this item, per the Problem section;
`StoreBackedBuild` adopts M0 and sits at G2, keeping its file-store arm
named rather than flagged. Acceptance: no class that *calls* `StoreFixture` or `StoreBackedBuild` is
edited. This slice does not wait on S8: G0 is what both fixtures delegate their writers to, so neither
needs the other's file touched.

**S8: `graphitron-lsp`.** `StoreFixture` keeps its name and its readers and delegates its capture half
to G1 and its writer half to G0. Last, because the LSP item is actively rewriting this file and the
constraint is a merge conflict rather than a design dependency. Acceptance: no class that calls
`StoreFixture` is edited.

Two notes for whoever takes stage 2. Folding the corpus sweeps off their per-example subdirectories
moves their `GraphIdentity.baseDir` from `tmp/<id>` to `tmp`, and `FactCapture.ownsGraph` compares a
recorded `base_dir` per graph name; the sweeps use distinct graph names, so one shared `baseDir` is
fine. It reads risky and it is not. And a rehome must not land on a file another item is still
rewriting, which is why S8 is last.

The item reaches Done when every surviving test is at the layer that owns its subject and the guard's exception
list holds only the classes that stay.

## Tests

This item changes test-support code, where tests live, and which of them are worth living. It changes
no main sources, so its acceptance is the existing suite across all five modules, passing. A test that
moves to another module is otherwise the same test: same cases, same expectations, a different way of
getting rows in front of them.

### Weighing a case is part of moving it

The subject of this item is the testing story rather than the file layout, so a case that does not
carry its weight is deleted rather than carried to a new module. Migration is the only cheap moment to
decide that, because it is the one time somebody has to read every case and restate it.

Two things sound alike here and are opposites. **Relaxing** an assertion so a harness can express it
stays forbidden, and means what the paragraph below says it means: the harness is wrong, not the
assertion. **Deleting** a case is the other claim entirely, that the assertion was never pinning
anything this project decides.

The bar for deleting is a mutation, not an opinion. Name the decision the case claims to pin, break
that decision in the main source, run the class, and put the result back. A case that still passes is
not pinning what it says it pins. A case that fails only on mutations another case in the same class
also fails on is dominated, and the dominating case is the one to keep. Two outcomes earn a deletion,
then: the case caught nothing, or the case caught nothing uniquely. Record the mutations tried in the
commit message, so the next reader can see the deletion was measured rather than felt, and so a
reviewer can rerun them.

Three guard rails on that. The default is to keep: when no mutation separates a case either way, it
migrates. A case that is slow, ugly or awkward to restate under a seeded harness is not thereby
weightless, and the awkwardness is a finding about the harness. And deleting is per case, never per
class; a class whose every case is dominated is a result to report, not a licence to skip reading the
rest.

`capture/StoreReaderTest` above is the worked example, with its mutation table and its two deletions.

An assertion that has to change to accommodate a harness is the signal that an axis was load-bearing
after all and must stay expressible, not that the assertion should be relaxed. There is one place this
is likely to bite, and it is worth watching rather than being surprised by: a view test moving to seeded
rows asserts the same output from inputs stated as rows rather than as SDL. If a case cannot be restated
that way, its subject was not only the view, and it belongs on G1 with the mixed classes.

The three downstream modules carry a second, sharper acceptance, because their call sites are not
being migrated: `StoreFixture`'s and `StoreBackedBuild`'s own consumers should be untouched. A diff
that edits a class *calling* one of those fixtures is reporting that the shared level cannot express a
shape that module needs. Treat that as the finding and widen the shared level, rather than adjusting
the test to suit it.

The rule is about consumers, and the facts writers downstream are not consumers: they stand their own
store up and never call the module's fixture, so converting them is the work rather than a violation of
it. Reading the rule as "no downstream test class changes" would strand exactly the classes the
maven-plugin pair are being converted for.

The guard is the one genuinely new test, and it needs its own negative case: a fixture source that
stands a store up outside a harness must fail it. A guard whose passing state is the only state ever
observed is a guard nobody knows is wired up.

`graphitron-model` gains its first tests, which is a build-wiring change as much as a test one. Its
surefire run, its test-jar, and every module's declaration of that test-jar all have to work before S1
can claim anything, so S1 should stand a trivial test up in the new module and see it run before the
harness is written.

`mvn install -Plocal-db` is the gate, and it has to be the full reactor build rather than any `-pl`
run, since every other module is downstream of `graphitron-model`. Inner loops per layer:
`mvn test -pl :graphitron-model` for M0 and M1 and the migrated view tests,
`mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution -am` for G1 and G2, then
`-pl :graphitron-lsp -am`, `-pl :graphitron-mcp -am` and `-pl :graphitron-maven-plugin -am` as each
module adopts.

Discoverability is not symmetric across the modules, and the item should not pretend otherwise. A
`graphitron-model` author meets M1 directly, and it is the only harness in their module, so they cannot
pick wrong. A `graphitron` author has two homes visible and has to choose, which is what the class
javadoc on each is for: each says what it is for and what the other is for. A downstream author meets
their own fixture first and a shared layer only behind it, which is the right layering anyway, since
what those tests want is usually the reader-side surface their own fixture keeps. What the item buys
them is not "here is the utility" but "the shape you need is one delegation away, and adding it there
serves every module." The guard catches the author who reads none of it.

## Out of scope

* Editing the call sites of `graphitron-lsp`'s or `graphitron-mcp`'s own fixtures. The downstream
  facts writers are not such call sites; they call no fixture, and converting them is in scope per
  the slices above. Deleting a case that carries no weight is also in scope, under the mutation bar
  the Tests section sets; what stays out is relaxing an assertion a harness found inconvenient.
* Changing any main source. Nothing here moves production code between modules, including
  `FactCapture.GraphIdentity`, which stays a nested record in `graphitron` and is passed to M0 by
  callers that need it rather than being pushed down.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
* Pushing `StoreBackedBuild` onto the G1 capture factories. Its population is a real `buildOutput()`
  run and that is the property its tests stand on; it adopts M0 and stays at G2.
* Converting `graphitron-lsp` or `graphitron-mcp` tests to seeded fixtures. Capture is the settled
  default for both, as it is for `graphitron` itself; their queries read across many relations at once
  and the FK chains are deep, and more to the point a fixture that skips the pipeline stops testing
  what those modules do. Only the model line seeds by default.
* Pruning the helper sets to some minimal basis. Per the governing rule, arriving with more helpers
  than strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be
  taken once each set is visible in one file.
* Touching `graphitron-lsp`'s main-source dependency on `graphitron`. This item adds test-scoped edges
  on artifacts the module already depends on, and leaves the shedding direction the pom records
  exactly where it is.
