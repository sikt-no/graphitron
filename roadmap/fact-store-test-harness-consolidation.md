---
id: R680
title: "Give each layer its own fact-store test harness, test each thing where it lives, and drop the rest"
status: In Review
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# Give each layer its own fact-store test harness, test each thing where it lives, and drop the rest

Written for review after the work landed. The Problem section is the diagnosis the item was filed on;
everything after it describes what now exists and why it took the shape it did.

## Problem

Four modules stood a fact store up in their tests, and each had arrived at its own way of doing it. No
two of them shared a line. That was the visible problem, and it was the smaller one.

The larger one was that most of those tests were not testing the module they lived in.
`graphitron-model` declares the fact store, over a hundred tables with their views, keys and check
constraints, in one DDL file. Most of the harnesses inside `graphitron` existed to assert what those
**views** return, and they got their rows there by running the whole crawler over SDL and a jOOQ
catalog. The subject was a view in a module upstream; the machinery was a pipeline in this one.

### Three subjects, one axis, and the sort falls out

Fourteen test classes in `graphitron/rewrite/derive/` hand-rolled the same harness: open a fact store,
capture one SDL fixture into it, assert against the resulting `DSLContext`. The helper was called
`withCapturedStore` in most of them and was close to verbatim across those. Each also carried its own
`private static Path write(Path directory, String sdl)` writing `fixture.graphqls`, and its own
`private static final String GRAPH = "<OwnClassName>"`. New copies kept arriving, because there was
nothing for a new test author to reach for instead.

What each class asserts is an `intent_*` relation, but the relation is not what decides where the test
belongs. **The subject does**, and across those fourteen classes there were exactly three subjects.
Naming them was the whole of the classification work, because every case in the population carries one
of them and the module boundary follows.

* **Algebra.** What a relation returns given rows: a view's joins, its outer edges, a check
  constraint's boundary. The subject is the DDL, and the inputs are stateable as rows.
* **Writer.** That a `graphitron` writer puts the right rows in a `CREATE TABLE` relation at its own
  cadence, or that a reach from an external input lands as a row at all. The subject is
  `graphitron`'s code, and the inputs are a real capture.
* **Walk agreement.** That a store-native relation and the transitional classification walk answer the
  same question the same way. The subject is the *equivalence of two implementations*, one of them
  `graphitron`'s.

Algebra goes to `graphitron-model` and gets seeded. Writer and walk agreement stay in `graphitron` and
keep a real capture. Where each of the fourteen ended up:

| Test class | Subject | Where it landed |
|---|---|---|
| `ReferenceStepTargetTest` | algebra | whole, `model/intent` |
| `FieldColumnTableTest` | algebra | whole, `model/intent` |
| `ClassAssignableTest` | algebra | whole, `model/intent` |
| `FieldProducerMethodTest` | algebra | whole, `model/intent` |
| `AccessorHopTest` | algebra | whole, `model/intent` |
| `ProducerCardinalityTest` | algebra | whole, `model/intent` |
| `ClassMemberSlotTest` | algebra + writer | split: `model/intent/ClassMemberSlotTest`, `derive/ClassMemberSlotScanTest` |
| `TypeBackingClassTest` | algebra + writer | split: `model/intent/TypeBackingTest` and `TypeBackingSeedTest`, `derive/TypeBackingClassTest` |
| `ColumnMatchClaimTest` | algebra + walk agreement | split: `model/intent/ColumnMatchClaimTest`, `derive/ColumnMatchShadowTest` |
| `AuthoredClaimConflictsTest` | algebra + walk agreement | split: `model/intent/AuthoredClaimTest`, `derive/AuthoredClaimConflictsTest` |
| `DemandShadowTest` | writer + walk agreement | split: `model/intent/DemandRuleTest`, `derive/DemandShadowTest` |
| `InputOccurrenceShadowTest` | writer + walk agreement | split: `model/intent/InputOccurrenceOverrideTest`, `derive/InputOccurrenceShadowTest` |
| `SeparateFetchTest` | writer + walk agreement | split: `model/intent/SeparateFetchRuleTest`, `derive/SeparateFetchTest` |
| `TypeBackingShadowTest` | walk agreement | whole, stays in `derive/` |

**Read the axis rather than the relation kind, because the two disagree.** Relation kind is a good
proxy and it is wrong in three places. `ColumnMatchClaimTest` and `AuthoredClaimConflictsTest` assert
only on views yet were the registered walk-agreement anchors, and `TypeBackingShadowTest` asserts on a
table and a view and has no algebra half at all. Sorting on relation kind puts the first two in the
whole-mover batch and splits the third, and all three are the wrong answer.

The seeded/captured choice follows from the subject and not the other way round. Algebra's inputs are
rows by definition, so it can be seeded and therefore should be. Walk agreement's inputs are an SDL
document both implementations read, so it cannot be seeded without deleting one side of the
comparison.

**Walk agreement is transient, and that is why it must not go down.** These anchors exist for the
strangler window. `FactCaptureAgreementTest`'s own javadoc says so: "These tests retire as consumers
migrate off `GraphitronSchema` piece by piece; they pin a shadow copy, and a shadow with a reader does
not need one." A walk-agreement half seeded into `graphitron-model` would be a permanent fixture in the
module that outlives the thing it polices. Keeping these halves in `graphitron`, thin and beside the
implementation they shadow, means they retire with it.

**The tree already contained the correction, and read it as a hazard.** `ColumnMatchClaimTest` carried
`withSeededStore` plus six seed helpers inserting straight into `STORE_GRAPH`, `STORE_SOURCE`,
`GRAPHQL_TYPE`, `GRAPHQL_TYPE_DECLARATION`, `GRAPHITRON_TABLE` and `SQL_TABLE`;
`ReferenceStepTargetTest` carried `withCollidingKeySeed` plus three more. Every table they touched is
generated from the model's own DDL and nothing in those helpers imported anything from `graphitron`.
They existed because capture cannot reach the states these views need, and each seeded case owed a
javadoc note justifying the reach. That was backwards. Seeding is the correct way to test a view;
reaching it through the crawler is the thing that needs justifying.

### The other three populations

**The facts writers, one population the tree split four ways.** A second population never captures SDL
at all: it opens a store and drives one of `graphitron`'s own facts writers by hand. There are exactly
four such writers, and the sites ran well past twenty across every one of the five modules, each
spelling `new <X>Facts(dsl, new FactCapture.GraphIdentity(name, dir))` and calling `write` or
`refresh`.

| Writer | Relations | Hand-driven from |
|---|---|---|
| `RejectionFacts` | the rejection family | `diagnostics/DiagnosticFactsTest`, `capture/FactCaptureAgreementTest`, LSP's `RejectionSeverityCoverageTest`, MCP's `DiagnosticsAggregateTest`, `StoreBackedBuild` |
| `CompileFacts` | the javac round | `compile/CompileFactsTest`, `capture/FactCaptureAgreementTest`, `diagnostics/DiagnosticFactsTest`, MCP's `DiagnosticsToolCompileSourceTest`, `DevMojoTest` |
| `JavaSourceFacts` | the `java_` declaration family | `capture/JavaSourceFactsTest`, `dev/CatalogRefreshTest`, both `StoreFixture`s |
| `BuildWarningFacts` | the warning family | `diagnostics/DiagnosticFactsTest`, `capture/FactSchemaGateTest`, `StoreBackedBuild`, LSP's `StoreFixture` |

The item as filed routed that one population four different ways: standalone tests were called direct
writers, the identical calls inside a downstream fixture were called "the capture half", the ones
inside `capture/` were left alone as oracles, and the maven plugin's two were called inline sites. They
are the same code. That is why the writers got a level of their own rather than being folded into the
capture handle: capture cadence and writer cadence are different facts about the store, and the model
itself draws the line. `FactCaptureAgreementTest`'s registry states it, the `java_` family being
"written by neither capture nor a graph, so its lifecycle anchor is partitioned by source file where
the oracle families' are partitioned by graph".

**The downstream fixtures, which are this item's thesis demonstrating itself.** `graphitron-lsp` had
`StoreFixture`, read by most of the module's test classes; it was not a degraded copy but largely this
item's target design, reached independently. `graphitron-mcp` had `StoreBackedBuild`, which is *not*
another copy of the capture harness: it stands up a **file** store and runs a real
`GraphQLRewriteGenerator.buildOutput()` into it, then plays `DevMojo`'s part over the result. And while
this item sat in Spec, the catalog-facts item added a *second* MCP fixture, `no.sikt.graphitron.mcp.StoreFixture`,
arriving at the LSP fixture's `ofCatalog` / `ofMultiSchemaCatalog` / `andGraph` shapes independently for
the second time. Independent files, no contact, converging on the same answers and disagreeing on the
rest by accident: that is the state this item exists to end, and it does not stop at a module line.

That fixture also set a rule the item followed rather than fought: **a surface another in-flight item is
actively growing must not be enumerated in a plan.** Two drafts of this section listed its factories and
both went stale within a day. What the levels owed it was a shape, not a census, and the shapes it kept
reaching for were combinations of axes already named, or writer calls.

**`graphitron-maven-plugin` had no fixture type at all.** `DevMojoTest` and `dev/CatalogRefreshTest`
opened a store inline and wrote to it directly, one site each. Small, and exactly the shape that becomes
another named harness the moment a third site appears. They are also the reason the facts writers
elsewhere were converted rather than excepted: these two are line-for-line what `compile/CompileFactsTest`
and `capture/JavaSourceFactsTest` do, down to the writer class each constructs. A rule that converts them
and excepts their twins is not a rule, it is a coincidence of which module the file happens to sit in.

### Where the harnesses stop

What is left over genuinely stays, and the edge matters because it says which classes the levels are
*not* designed for. These are the guard's exemptions, and the list is closed.

**Store-mechanics tests.** `capture/PersistentStoreTest` and `capture/WarmStartRefreshTest` have the
store's own lifetime, its warm start and its file-backed home as their subject. They reopen one
directory repeatedly, compare cold against warm, and hold two handles onto the same home at once, so the
reopen *is* the assertion and no entry point that hands out one store can express it.
`capture/BrokenSourceStillCapturesPipelineTest` sits beside them: its subject is what is true of the
store when a run fails, so it holds the store across the failure itself.

The line is narrower than "the lifetime is the subject", and `StoreReaderTest` is the instructive
counterexample: its subject is the reader, which is a lifetime question, and it took M0 anyway, because
one store per case is all it needs. What earns an exemption is needing more than one handle on one home,
not caring about lifetime.

**Capture-oracle tests.** `capture/FactCaptureAgreementTest` and `capture/FactSchemaGateTest` drive
`FactCapture` per view, per arm, with the store's population as the subject. They read the capture
handle where a factory fits and open directly where none does, which is the primitives layer working as
intended.

**And one group that does not stay, named here because it reads like an exception.**
`capture/CommentRenderabilityGateTest` opened a bare store and read the model's own metadata: the
`REMARKS` of every table and column in `INFORMATION_SCHEMA` and every character-typed value of every
`meta_` relation. It captures nothing, drives no writer, and asserts over no rows but the ones the DDL
ships with. Its subject is `graphitron-model`'s comment prose end to end, so the axis sent it down, and
it was the cheapest mover in the item. It is now `graphitron-model`'s, with its `@UnitTier` annotation
dropped, `graphitron`'s tier vocabulary not travelling to a module that has none.

## The rule

One rule decides everything else. **The way a test populates the store follows what the test is
about.** If the subject is algebra, seed the rows the relation reads. If it is a writer, drive the
writer. If it is agreement with the walk, run a real capture and compare. If it is the dev loop's
wiring, run a build.

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
is what lets it be excellent at that thing; the reason the old `derive/` harnesses were awkward is that
they were general.

**Read the rule as a boundary, not as a preference for seeding.** Seeding is the method in exactly one
module, the one whose subject is the DDL. Above it, in the generator, the language server and the MCP
server, **capture is the default**, and a test there that hand-seeds rows to avoid running the pipeline
is making the same mistake in the opposite direction: those modules exist to turn real inputs into real
rows, so a fixture that skips that step stops testing the thing. A seeded fixture above the model line
is occasionally right and owes a reason at the call site, which is precisely the obligation lifted from
seeded cases inside `graphitron-model`.

**The governing rule for growth: add the shape at the layer that owns the subject.** A test that needs a
store shape no existing harness produces adds it to the harness for the layer its *subject* belongs to,
rather than hand-rolling a helper in its own class. The failure mode designed against is fragmentation,
not accretion. A spread of private copies that have quietly diverged is the expensive state, and it is
expensive because nothing points a new test author at the existing answer. A harness carrying more
helpers than any one reader needs is the cheap state: they are in one file, visible together, and
consolidating two that turned out to be the same is a mechanical afternoon. A later item adding a helper
is the design working rather than failing.

## Where we landed

### Two homes, five levels

| Home | Level | Class | What it carries |
|---|---|---|---|
| `graphitron-model` test-jar | M0 | `FactStores` | the store's lifetime: `inMemory()` and `fileBacked(home)`, named rather than flagged |
| `graphitron-model` test-jar | M1 | `SeededStore` | seeding: named row-inserting helpers over the generated model tables |
| `graphitron` test-jar | G0 | `FactWriters` | the four facts writers over a store somebody else opened, graph identity as a name and a directory |
| `graphitron` test-jar | G1 | `CapturedStore` | a real `FactCapture` run over a fixture document, with the fixture file and its graph identity |
| `graphitron` test-jar | G2 | `BuiltStore` | a real `buildOutput()` run into a store on disk |
| each module, local | L | that module's own fixture | the module's own read boundary over a populated store |

**Every level is usable without the one above it.** A facts-writer test wants M0 and G0 and never seeds
or captures; a view test wants M0 and M1; a crawler test wants M0 and G1; a dev-loop test wants M0 and
G2. G1 does not stand on G0 either: the two are siblings over M0, because capture writes its own rows
and a writer test has no capture to run. A store handle that always came with rows already in it would
have left the writers exactly where they were.

`graphitron`'s home stands on the model's: G1 and G2 take their store from M0 rather than opening one.
Each downstream module keeps its named fixture as a thin local layer, which is why both `StoreFixture`s
and `StoreBackedBuild` survive under their own names rather than being deleted into a common type, and
what keeps the downstream call sites still.

**Why the split is by layer rather than one shared fixtures module.** Capture reads SDL and the jOOQ
catalog, so `FactCapture` cannot move down; a home for it in `graphitron-model` would drag
`RewriteSchemaLoader`, `JooqCatalog` and the schema-input machinery with it, which is the generator, not
the model. A single reactor-wide fixtures module for both levels cannot exist at all: it would have to
depend on `graphitron` while `graphitron`'s own tests depend on it, and Maven cannot express that cycle.
Splitting by layer removes the cycle, which is a second reason this is the right shape rather than
merely a tidier one.

`graphitron-model` had no test sources and published no test-jar, so M0 and M1 were created rather than
moved, and both are structurally free of `graphitron`. It now carries 26 test classes and a test-jar
every downstream module declares at test scope. `roadmap-tool` benefits by construction: it depends on
`graphitron-model` and not on `graphitron`, and `SchemaReferencePagesTest` opens a store, so a single
`graphitron`-hosted home would have left that site unreachable.

### The guard

`StoreFixtureGuardTest` is the one genuinely new test. **One recogniser: a test-source reference to
`GraphitronModelStore` outside a harness.** The type, not one factory method, because `open()` mints an
in-memory database and `openAt(Path)` a file-backed one and M0 carries both named rather than flagged;
naming the type also survives a third factory being added. It deliberately has no second recogniser over
hand-rolled `.graphqls` writes: qualified by the store that would be a subset of the first, and
unqualified it would sweep in every watcher, emitter, mojo and parse test that writes an SDL file
without going near a store.

**The failure message asks for the subject, and that is the guard's whole job.** It does not say "use the
shared home", because there are two and picking the wrong one is exactly the mistake being corrected. It
asks what the test is about and gives the answers, one per level, so an author who trips it comes away
having classified their own test rather than merely knowing they typed the wrong class name.

Two lists back it, with no arithmetic over either. `HOMES` names the three harnesses that stand a store
up because that is their job. `EXEMPT` names the five classes that stand one up and stay, on two reasons,
**both permanent**: the store's own lifetime is the subject (`PersistentStoreTest`,
`WarmStartRefreshTest`, `BrokenSourceStillCapturesPipelineTest`), or the class is a capture oracle
(`FactCaptureAgreementTest`, `FactSchemaGateTest`). A second test, `everyDeclaredEntryStillDescribesSomething`,
fails on an entry whose file is gone or has quietly stopped standing a store up, so an entry that
outlives its reason fails the build instead of lingering as a permission nobody rereads. That is the
only bookkeeping the guard owes.

`GuardScope.IN_SCOPE_MODULES` gained `graphitron-model`, which is shared, so the same line enrolled the
model's new test sources in `RoadmapReferenceGuardTest` and `RetiredVocabularyGuardTest` too. That was
wanted rather than tolerated: the model's tests meet the same citation and vocabulary rules as every
other module's from their first commit.

### The three levels in `graphitron`, and what shape each takes

**G1, `CapturedStore`, is layered three deep.** `withCapturedStore(directory, sdl, body)` is the closure
form and the shortest thing to type; the `AutoCloseable` handle is the primitive underneath it, for a
test that needs more than one step against the open store; and `registryOf` / `attributionOf` /
`fixtureFile` / `graph` are the primitives under that, for a test whose axis combination no factory names
and which drives `FactCapture.capture` itself. That third layer is what keeps the factory set from having
to be the cross-product of catalog, registry source, extensions and verdicts. The handle layer had to be
public rather than an implementation detail of the closure form, and the downstream fixtures are the
proof: each holds a store open across a capture, a writer call and a series of reads, which is the
multi-step shape the closure form deliberately does not serve.

**Named arms, not flags.** Each factory says in its own name what its shape carries: `of` and `ofCatalog`
for the catalog axis, `ofPipeline` for the registry source, `ofFiles` for a graph assembled from two
documents, `ofRefusedSchema` for a read that refused, `recapture` / `recaptureCatalog` for a warm round,
`andGraph` / `andCatalogGraph` / `andGraphSharingTheFile` for a second graph in the same store. The
classpath census is an argument rather than an axis, because it pairs with every shape and naming it
would double the set to say nothing. Two independent fixtures had already reached for it as an argument,
which settled it.

**G0, `FactWriters`, is a static facade over a `DSLContext` and owns nothing.** The store comes from
`FactStores`, whichever of its two shapes the case wants, and the caller closes it. That is what lets a
module's own fixture delegate its writer calls while keeping the store it already holds. The graph
identity is two arguments rather than a type, because those are the two values a case varies: a second
graph to say the partition holds, a second directory to say a checkout that does not own the graph writes
nothing.

**G2, `BuiltStore`, is a real generator run into a file store.** The dev loop's store lives in a directory
the build writes into and a session reopens, so that is the substrate, from `FactStores.fileBacked`. Every
other fixture in the tree is in-memory, and the difference is this level's subject rather than an accident.
It holds the build and the store; what a module then does with the pair, publishing the output onto its own
workspace or writing further facts beside it, is that module's local layer.

### The downstream fixtures

All three keep their names, their factory sets and every one of their call sites. Not one class that
*calls* a fixture was edited, which was the acceptance condition on both migrations and is the check a
reviewer can run mechanically.

* **`graphitron-mcp`'s `StoreFixture`** is a local layer over G1 and G0: each factory is one arm of the
  capture level with the module's own vocabulary in front of it, a generated jOOQ package name where the
  level takes a `JooqCatalog` and the module's class census where it takes a list.
* **`graphitron-mcp`'s `StoreBackedBuild`** sits on G2. What it stopped owning is the floor: the store's
  lifetime, the file substrate and the schema file it writes, which was the third independent answer in
  the tree to "where does the fixture file go and who closes the store". What is left local is the pair of
  writer calls the mojo makes after a build.
* **`graphitron-lsp`'s `StoreFixture`** is the same shape spanning two levels in one type: capture for
  every arm but `ofBuild`, whose rows only a real build produces and which therefore takes G2. Which level
  an arm came from is a matter of which field it filled rather than a flag.

`graphitron-maven-plugin`'s two inline sites adopted M0 and G0; neither needed a capture factory. This was
the part most likely to be dropped for being small, which is why it was written down: the guard fails on
those two sites, and an exception entry added to silence it would have been this item defeating itself.

## Decisions worth a reviewer's attention

**The graph identity became caller-supplied, and the fixture filename is keyed on it.** The capture
handle used to hardcode the graph name to a literal, which was the one thing that made it unusable for
the incoming consumers. The directory could not simply carry the identity instead:
`RewriteSchemaLoader.load` takes the file arm of `SchemaSource`, so SDL held as a string has to be
materialized, and the path it is written to *is* its identity downstream, being the string handed to the
parser, the string graphql-java echoes back as a source name, and the key both the attribution map and
capture's stamp lookup are read on. A handle that took a graph name but reused one directory would have
had each capture silently overwrite the previous fixture. So the filename is
`directory.resolve(graphName + ".graphqls")`. The alternative considered first, a subdirectory per graph,
cannot express `andGraphSharingTheFile` at all, because it makes the file's location a function of the
graph.

**Two assertions moved knowingly as a result, and they are the only two.** The rule everywhere else is
that an assertion which has to change to accommodate a harness signals a load-bearing axis rather than an
assertion to relax. These two are the stated exception, because the changed assertion *is* the fixture's
own name: `capture/WarmStartRefreshTest` compares the absolute path of its fixture file, and
`GraphitronMcpServerTest` asserts the tail of a diagnostic's URI. Both say so at the call site.

**The node-inference axis was not resolved; it stopped existing.** The item planned to settle
`new NodeDeclaration(null)` against production's `new NodeDeclaration(jooq)`, and to demand a
discriminating case before keeping both as arms. What happened instead is that nodehood moved to being
derived from the captured facts of both corpora rather than decided during the walk, so `FactCapture`
takes no `NodeDeclaration` at all and a fixture has no such axis to arm. The capture arms differ only in
whether the catalog facts are in the store to derive from. This is worth flagging rather than quietly
banking: the question the item asked was answered by a different change, and the arm set is smaller than
either outcome the item predicted.

**The registry source is carried by the naming convention, not by an argument.** A bare parse is the
unmarked name (`of...`, `withCapturedStore`) and the pipeline registry is the marked one (`ofPipeline...`).
The claim is legible because the marked name is what a `grep` separates on, not because the unmarked call
spells its choice out. Naming it now was cheap; no current fixture is federation-shaped, so nothing was
broken, and the alternative was a default nobody revisits when the first federation-shaped fixture lands.

**One exemption turned out to be mis-sorted, and was corrected at review rather than carried.**
`TypeBackingShadowTest` sat on the guard's list under a pending-seeding reason whose own note contradicted
it: the class is a walk-shadow differential, not algebra reached through a crawler, and its own javadoc says
the walk is not the specification. Reading its harness settled it, since the method was a hand-written
`CapturedStore.ofCatalog` call. It now takes the capture level like any other consumer and holds no
exemption, which is what empties the guard's pending list and lets the exemption reasons be two permanent
ones rather than four.

## Weighing a case was part of moving it

The subject of this item is the testing story rather than the file layout, so a case that did not carry
its weight was deleted rather than carried to a new module. Migration is the only cheap moment to decide
that, because it is the one time somebody has to read every case and restate it.

Two things sound alike here and are opposites. **Relaxing** an assertion so a harness can express it stays
forbidden: the harness is wrong, not the assertion. **Deleting** a case is the other claim entirely, that
the assertion was never pinning anything this project decides.

The bar for deleting is a mutation, not an opinion. Name the decision the case claims to pin, break that
decision in the main source, run the class, and put the result back. A case that still passes is not
pinning what it says it pins. A case that fails only on mutations another case in the same class also
fails on is dominated, and the dominating case is the one to keep. The mutations tried are recorded in the
commit message, so a reviewer can rerun them. Three guard rails: the default is to keep when no mutation
separates a case either way; a case that is slow or awkward to restate under a seeded harness is not
thereby weightless, and the awkwardness is a finding about the harness; and deleting is per case, never
per class.

`StoreReaderTest` is the worked example, and the only class where the bar removed more than an isolated
case. Four mutations, each breaking one decision `StoreReader` or `GraphitronModelStore` makes, run against
the class as it stood:

| Case | isolation line dropped | isolation set to read uncommitted | reader handed the writer's connection | reader opens a guessed URL |
|---|---|---|---|---|
| `aReaderSeesWhatTheWriterCommitted` | passes | passes | passes | passes |
| `aRoundStillInFlightIsInvisible` | passes | **fails** | **fails** | passes |
| `oneReadIsOneSnapshot` | **fails** | **fails** | **fails** | passes |
| `aPersistedStoreMintsAReaderOntoItsOwnFile` | passes | passes | passes | **fails** |
| `closingOneReaderLeavesTheStoreAndItsSiblingsReadable` | passes | passes | **errors** | passes |

Three cases went home to `graphitron-model`, each the only catcher of something.
`aRoundStillInFlightIsInvisible` is dominated by `oneReadIsOneSnapshot`, which also makes the stronger
claim: a committed round arriving mid-read is harder to hide than an uncommitted row.
`aReaderSeesWhatTheWriterCommitted` caught nothing at all, because every other case reads rows back and so
already fails if a reader cannot see what the writer committed. It was a baseline restated, not a pin.

## Verification

The item changes test-support code, where tests live, and which of them are worth living. It changes no
main sources, so its acceptance is the existing suite across all five modules, passing. A test that moved
to another module is otherwise the same test: same cases, same expectations, a different way of getting
rows in front of them.

`mvn install -Plocal-db` is the gate, and it has to be the full reactor build rather than any `-pl` run,
since every other module is downstream of `graphitron-model`.

Three checks a reviewer can run mechanically:

* **No consumer of a downstream fixture was edited.** A diff that edits a class *calling* `StoreFixture`
  or `StoreBackedBuild` would be reporting that a shared level cannot express a shape that module needs.
  The rule is about consumers: the facts writers downstream are not consumers, since they stand their own
  store up and never call the module's fixture, so converting them was the work rather than a violation of
  it.
* **The guard fails on a source that stands a store up outside a harness**, proved by a negative case in
  `StoreFixtureScannerTest` rather than only ever observed passing, and fails on a stale list entry the
  same way.
* **No seeded half names a `graphitron` type at all**, which is the mechanical check that each split was
  cut on the subject axis rather than on convenience.

Discoverability is not symmetric across the modules, and the item does not pretend otherwise. A
`graphitron-model` author meets `SeededStore` directly and it is the only harness in their module, so they
cannot pick wrong. A `graphitron` author has two homes visible and has to choose, which is what the class
javadoc on each is for: each says what it is for and what the other is for. A downstream author meets their
own fixture first and a shared level only behind it, which is the right layering anyway, since what those
tests want is usually the reader-side surface their own fixture keeps. What the item buys them is not "here
is the utility" but "the shape you need is one delegation away, and adding it there serves every module".
The guard catches the author who reads none of it.

## Retired vocabulary

For the Done-gate retirement sweep. Two names are gone and one habit is:

* `no.sikt.graphitron.rewrite.capture.CapturedStore`. The handle moved out of the capture package and up
  beside `TestSchemaHelper` when it became the module's shared harness; the old package is where a reader
  looking for a capture helper still guesses.
* `StoreFixtureGuardTest`'s `PENDING_MODULE_FLOOR` and `PENDING_SEEDING` exemption reasons, retired with
  the last entries that carried them. Both surviving reasons are permanent.
* "open a store in the test that needs one." Standing a fact store up in a test class is now a guard
  failure outside the declared harnesses and their five exemptions, so a review comment or a doc sentence
  that treats it as the ordinary thing to do is stale.

Relocations rather than retirements, worth knowing because the class names survive elsewhere:
`StoreReaderTest` is `graphitron-model`'s, and the seeded halves listed in the Problem section's table live
under `no.sikt.graphitron.model.intent` with their capture-side halves left behind in `graphitron` under
names of their own.

## Out of scope

* Editing the call sites of `graphitron-lsp`'s or `graphitron-mcp`'s own fixtures. The downstream facts
  writers are not such call sites; they call no fixture, and converting them was in scope. Deleting a case
  that carries no weight was also in scope, under the mutation bar above; what stayed out is relaxing an
  assertion a harness found inconvenient.
* Changing any main source. Nothing here moves production code between modules, including
  `FactCapture.GraphIdentity`, which stays a nested record in `graphitron` and is passed to the model's
  harness by callers that need it rather than being pushed down.
* Reshaping `FactCapture`'s own overload set. The public default is a reasonable one and this item consumes
  it.
* Pushing `StoreBackedBuild` onto the capture factories. Its population is a real `buildOutput()` run and
  that is the property its tests stand on.
* Converting `graphitron-lsp` or `graphitron-mcp` tests to seeded fixtures. Capture is the settled default
  for both, as it is for `graphitron` itself; their queries read across many relations at once and the FK
  chains are deep, and a fixture that skips the pipeline stops testing what those modules do.
* Pruning the helper sets to some minimal basis. Per the governing rule, arriving with more helpers than
  strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be taken once
  each set is visible in one file.
* Touching `graphitron-lsp`'s main-source dependency on `graphitron`. This item adds test-scoped edges on
  artifacts the module already depends on, and leaves the shedding direction the pom records exactly where
  it is.
