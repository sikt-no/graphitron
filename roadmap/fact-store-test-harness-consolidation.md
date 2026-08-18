---
id: R680
title: "Give each layer its own fact-store test harness, and test each thing where it lives"
status: Spec
bucket: cleanup
priority: 3
theme: testing
depends-on: [lsp-reads-the-fact-store]
created: 2026-08-14
last-updated: 2026-08-18
---

# Give each layer its own fact-store test harness, and test each thing where it lives

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
The second is cleanup, moving the tests that are already in the wrong place.

### Most of `graphitron`'s harnesses are testing `graphitron-model`

Fourteen test classes in `graphitron/rewrite/derive/` hand-roll the same harness: open a fact store,
capture one SDL fixture into it, assert against the resulting `DSLContext`. The helper is called
`withCapturedStore` in most of them and is close to verbatim across those. Each also carries its own
`private static Path write(Path directory, String sdl)` writing `fixture.graphqls`, and its own
`private static final String GRAPH = "<OwnClassName>"`. New copies keep arriving, because there is
nothing for a new test author to reach for instead.

What each one actually asserts is an `intent_*` relation, and the DDL says which kind:

| Test class | Asserts on | Declared as |
|---|---|---|
| `ColumnMatchClaimTest` | `intent_column_match_claim` and three feeders | view |
| `ReferenceStepTargetTest` | `intent_field_reference_step_target`, `intent_spelled_table` | view |
| `FieldColumnTableTest` | `intent_field_column_scope`, `intent_field_column_table` | view |
| `ClassMemberSlotTest` | `intent_class_member_slot` | view |
| `ClassAssignableTest` | `intent_class_assignable` | view |
| `FieldProducerMethodTest` | `intent_field_producer_method` | view |
| `AccessorHopTest` | `intent_field_accessor_hop` and four feeders | view |
| `ProducerCardinalityTest` | `intent_producer_cardinality_conflict` | view |
| `AuthoredClaimConflictsTest` | `intent_authored_claim_conflict`, `intent_authored_type_claim` | view |
| `DemandShadowTest` | the demand views, **and** `intent_type_domain` | view + table |
| `InputOccurrenceShadowTest` | `intent_input_occurrence_override`, **and** the path tables | view + table |
| `SeparateFetchTest` | `intent_field_separate_fetch`, **and** `intent_type_backing_class` | view + table |
| `TypeBackingClassTest` | `intent_type_backing`, `_seed`, `_conflict`, **and** `_class` | view + table |
| `TypeBackingShadowTest` | `intent_type_backing_seed`, **and** `intent_type_backing_class` | view + table |

Nine of the fourteen assert only on SQL views declared in `graphitron-model.sql`. They run a crawler
they are not testing in order to populate rows a view reads, and the crawler is in a different module
from the thing under assertion. The remaining five are mixed: `intent_type_domain`,
`intent_input_occurrence_path` / `_step` and `intent_type_backing_class` are `CREATE TABLE`, written by
`ReachabilityRows`, `InputOccurrencePaths` and `TypeBackingRows` in `graphitron`'s main sources. Those
halves are genuinely `graphitron`'s, and testing them through a real capture is right.

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
returns.

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
the second time. It carries two shapes the LSP's does not, and both are requirements on G1 rather than
curiosities: `withoutCatalog`, and `recaptureCatalog(String jooqPackage)`, which re-captures a graph
into an already-populated store by passing `warm = true` to `FactCapture.capture`.
`CatalogSearchIndexTest` calls the latter four times, once with a null package. A warm re-capture into
an already-open store is
therefore a live G1 shape with named callers, not an oddity to be discovered mid-migration, and the
factory set below has to carry it from the first day of that slice.

The MCP fixture exists for a good reason, which is that a census read does not need a build and
`StoreBackedBuild` was pricing the generator into every catalog case, so the fixture is not the mistake;
building it a fourth time is. It is a capture-level copy, so it belongs at G1 in the table below
rather than beside `StoreBackedBuild`. The prediction this item first made about the catalog-facts item,
that it would grow `StoreBackedBuild`, was wrong in the direction that matters: what a consumer reaches
for when no shared harness expresses its shape is not the nearest existing fixture but a new one.

`graphitron-maven-plugin` has no fixture type at all. `DevMojoTest` and `dev/CatalogRefreshTest` open
`GraphitronModelStore.open()` inline and write to it directly, one site each. Small, and exactly the
shape that becomes another named harness the moment a third site appears.

### The direct writers, which want M0 and nothing else

A second population opens a store and writes rows to it directly, with no SDL captured into it at all:
`compile/CompileFactsTest`, `capture/CommentRenderabilityGateTest`, `capture/JavaSourceFactsTest` and
`capture/SourceGraphScopingTest` inside `graphitron`, and downstream `graphitron-lsp`'s
`RejectionSeverityCoverageTest` and `graphitron-mcp`'s `DiagnosticsToolCompileSourceTest` and
`DiagnosticsAggregateTest` (which is a `StoreBackedBuild` reader *and* a direct writer, opening its own
store in three cases and driving `RejectionFacts` by hand).

They are not a separate design problem from the maven-plugin's two inline sites. They are the same
code. `DevMojoTest` opens a store and constructs `new CompileFacts(store.dsl(), new GraphIdentity(…))`,
and `compile/CompileFactsTest` opens a store and constructs the same writer the same way.
`dev/CatalogRefreshTest` opens a store and constructs `new JavaSourceFacts(store.dsl())`;
`capture/JavaSourceFactsTest` does the same. Whatever the item does with the maven-plugin pair it has
to do with these, or it is routing identical code two opposite ways on the accident of which module it
sits in.

So they migrate, and the level they migrate to is M0, the store's lifetime, and nothing above it.
What they get out of it is modest, an opened store and a `GraphIdentity` they currently hand-build, and
that is worth stating plainly rather than overselling. The reason to convert them anyway is that the
alternative is a guard exception list holding seven classes that are not exceptions on the merits,
merely unconverted, which is the quiet second inventory this item exists to stop.

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

Those two groups are the whole of the exception list the guard carries. A harness that tried to serve them
would be the does-everything type the next section exists to avoid.

### What the capture-shaped tests still disagree about

The section above removes most of `derive/` from the capture level. What remains capture-shaped is
real and still has a shared problem: the `capture/` tests, `diagnostics/DiagnosticFactsTest`, the
derivation-writer halves of the five mixed classes, and both downstream fixtures.

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
about.** If the subject is a view or a constraint, seed the rows it reads. If the subject is the
crawler, run the crawler. If the subject is the dev loop's wiring, run a build.

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

### Two homes, one per layer

| Home | Level | What it carries |
|---|---|---|
| `graphitron-model` test-jar | M0 | the store's lifetime, in-memory and file-backed, as named entry points |
| `graphitron-model` test-jar | M1 | seeding: named row-inserting helpers over the generated model tables |
| `graphitron` test-jar | G1 | capture-level population: `FactCapture.capture` into an open store, with the fixture file and its graph identity |
| `graphitron` test-jar | G2 | build-level population: a real `buildOutput()` run into a file store |
| each module, local | L | the module's own read boundary over a populated store |

`graphitron`'s home stands on the model's: G1 takes its store from M0 rather than opening one itself.
Each module keeps its named fixture as a thin local layer, which is why `StoreFixture` and
`StoreBackedBuild` survive under their own names rather than being deleted into a common type, and what
keeps the downstream call sites still.

**M0 and M1 have to be usable apart, and M0 alone has to be usable.** The direct writers want an open
store and a graph key and never seed or capture; a view test wants M0 and M1; a crawler test wants M0
and G1. A store handle that always came with rows already in it would leave the direct writers exactly
where they are.

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
unreachable by construction. And `capture/StoreReaderTest`, which imports `GraphitronModelStore` and a
tier annotation and nothing else, is a `graphitron-model` test currently living in `graphitron`; it can
go home.

The guard needs a matching change: `GuardScope.IN_SCOPE_MODULES` does not list `graphitron-model`
today, so a module that is about to become a test home sits outside the walk. Add it.

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
handle straddle both tiers (`FactCaptureAgreementTest`, `TaggedCaptureStampTest` and
`WarmStartRefreshTest` are `@PipelineTier`, the rest `@UnitTier`). A home that reads as belonging to
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

**The failure message routes by layer, and that is the guard's whole job.** It should not say "use the
shared home", because there are two and picking the wrong one is exactly the mistake this item is
correcting. It should ask the question the architecture answers: if what you are testing is a view or a
constraint, seed it with the model's harness; if it is the crawler, capture with `graphitron`'s; if it
is your module's own reads, put a fixture over one of those. An author who trips this guard should come
away knowing which layer their test belongs to, not merely that they typed the wrong class name.

**Exceptions are named, with reasons, and there is no arithmetic over them.** Only the classes in
"Where the harnesses stop" earn an entry, and there are exactly two reasons: the store's lifetime is
the subject, or the class is a capture oracle driving `FactCapture` per arm. The direct writers are not
on this list, because they migrate. Follow `RetiredVocabularyGuardTest`'s shape, a record per entry with
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

**The post-capture writers move too, and they are the reason the LSP item is a dependency.**
`withBuildWarnings`, `withJavaSource` and `refreshJavaSources` write into the store after capture, and
`capture/JavaSourceFactsTest` already drives the same `JavaSourceFacts` writer by hand, so they are a
consolidation of exactly the kind this item exists for. They are also the surface the LSP item is
still actively growing, which is a sequencing constraint rather than a reason to leave them: they move
once that item is Done, not before.

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
M0 and nothing else; neither needs a capture factory. This is the smallest part of the
item and the one most likely to be dropped for being small, which is precisely why it is written down:
the guard will fail on these two sites, and an exception entry added to silence it would be this item
defeating itself.

They are also the reason the direct writers elsewhere are converted rather than excepted. These two are
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

**Only the LSP slice waits, and `depends-on` says so.** The catalog-facts dependency is dropped: its
MCP fixture stopped moving when its catalog slices landed, and everything this item does inside
`graphitron` touches no file either in-flight item holds. The LSP dependency stays, because that item
is still growing the post-capture writers this one moves, and its slice is last anyway.

The cost of waiting on the catalog-facts item would have been concrete rather than a preference. Its
remaining tool slices each need capture shapes the LSP's fixture already carries: the classpath census
its code-tools slice reads is `StoreFixture.ofClasspath`, the source locations it renders are
`withJavaSource` / `refreshJavaSources`, and the diagnostics axes its status slice reads are
`withBuildWarnings`. Left to wait, that item re-derives most of the LSP's fixture inside
`graphitron-mcp` one slice at a time, which is this item's own thesis running again.

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
open store. `withCapturedStoreAndClaimDomain`, the two hand-rolled re-opens, `DiagnosticFactsTest`
and `ClassMemberSlotTest` are the first callers of the handle. Neither layer is capped; per the rule
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
nothing checks at all, so any class rename in this item must update that surface by hand.

## Slices

Two stages. Stage 1 builds the architecture and proves it, so that from its last commit onward a test
author has a right place to put a new test and a harness shaped for it. Stage 2 moves the tests that
are already in the wrong place, one population per slice, and none of it blocks anybody.

The split matters because the stages have different value profiles. Stage 1 stops the bleeding and is
worth landing even if stage 2 stalls; stage 2 is cleanup that can be taken a slice at a time by
whoever has the appetite.

### Stage 1: the architecture

**S1: M0 and M1, the model's harness.** `graphitron-model` gains test sources and a test-jar. M0 is the
store's lifetime with `open()` and `openAt(Path)` as named entry points; M1 is the seeding harness,
merged from `ColumnMatchClaimTest`'s six seed helpers and `ReferenceStepTargetTest`'s three, with the
`graphql_type` / `graphql_field` / `graphql_type_declaration` twins reconciled into one spelling.
`GuardScope.IN_SCOPE_MODULES` gains `graphitron-model`.

Proof, not assertion: this slice also moves `capture/StoreReaderTest` down, and migrates **one** pure
view test end to end, from capture-driven to seeded, keeping its assertions. `ClassAssignableTest` is
the natural pick, being the smallest single-view mover. If that migration cannot be done without
reaching for something M1 does not have, the harness is not finished and the finding belongs in this
slice rather than in stage 2.

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
stale entry, proved the same way; it passes on the tree; and its message names the three layers rather
than one home.

Stage 1 is done when a new test has an obvious home. That is also the point at which this item could be
stopped without leaving a mess.

### Stage 2: cleanup

**S4: the nine pure-view classes.** `ColumnMatchClaimTest`, `ReferenceStepTargetTest`,
`FieldColumnTableTest`, `ClassMemberSlotTest`, `ClassAssignableTest` (already moved in S1),
`FieldProducerMethodTest`, `AccessorHopTest`, `ProducerCardinalityTest` and `AuthoredClaimConflictsTest`
move to `graphitron-model` and become seeded. Take them in batches. Acceptance: each keeps its assertion
content, and each loses its `write(`, its `GRAPH` constant, its census plumbing and its `NodeDeclaration`
argument, because a seeded view test needs none of them. A class that cannot lose those is telling you
it was not a pure view test after all, which is a finding worth recording rather than working around.

**S5: the five mixed classes.** `DemandShadowTest`, `InputOccurrenceShadowTest`, `SeparateFetchTest`,
`TypeBackingClassTest` and `TypeBackingShadowTest` split: the view assertions go down and become seeded,
the assertions on `intent_type_domain`, `intent_input_occurrence_path` / `_step` and
`intent_type_backing_class` stay in `graphitron` on G1, because those rows are written by
`ReachabilityRows`, `InputOccurrencePaths` and `TypeBackingRows` and the crawler is the subject.
Acceptance: each half asserts what it always asserted, and the `graphitron` half still runs a real
capture.

**S6: the direct writers.** `graphitron`'s four, the maven plugin's two, and downstream
`RejectionSeverityCoverageTest`, `DiagnosticsToolCompileSourceTest` and `DiagnosticsAggregateTest`'s
three own-store cases adopt M0 and nothing else. Acceptance: none of them still names
`GraphitronModelStore`, and the maven-plugin pair is not left inline on the grounds that it is only two,
which is the way this slice fails.

**S7: `graphitron-mcp`.** Its `StoreFixture` becomes a local layer over G1, keeping `withoutCatalog` and
`recaptureCatalog` intact; `StoreBackedBuild` adopts M0 and sits at G2, keeping its file-store arm named
rather than flagged. Acceptance: no class that *calls* `StoreFixture` or `StoreBackedBuild` is edited.

**S8: `graphitron-lsp`.** `StoreFixture` keeps its name and its readers and delegates its capture half,
including the post-capture writers, which is the part that waits on the LSP item. Acceptance: no class
that calls `StoreFixture` is edited.

Two notes for whoever takes stage 2. Folding the corpus sweeps off their per-example subdirectories
moves their `GraphIdentity.baseDir` from `tmp/<id>` to `tmp`, and `FactCapture.ownsGraph` compares a
recorded `base_dir` per graph name; the sweeps use distinct graph names, so one shared `baseDir` is
fine. It reads risky and it is not. And a rehome must not land on a file another item is still
rewriting, which is why S8 is last.

The item reaches Done when every test is at the layer that owns its subject and the guard's exception
list holds only the classes that stay.

## Tests

This item changes test-support code and where tests live. It changes no main sources, so its acceptance
is the existing suite across all five modules, passing with its assertion content unchanged. A test
that moves to another module is still the same test: same cases, same expectations, a different way of
getting rows in front of them.

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

The rule is about consumers, and the direct writers downstream are not consumers: they stand their own
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

* Changing what any migrated class asserts, and editing the call sites of `graphitron-lsp`'s or
  `graphitron-mcp`'s own fixtures at all. The downstream direct writers are not such call sites; they
  call no fixture, and converting them is in scope per the slices above.
* Changing any main source. Nothing here moves production code between modules, including
  `FactCapture.GraphIdentity`, which stays a nested record in `graphitron` and is passed to M0 by
  callers that need it rather than being pushed down.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
* Pushing `StoreBackedBuild` onto the G1 capture factories. Its population is a real `buildOutput()`
  run and that is the property its tests stand on; it adopts M0 and stays at G2.
* Re-examining whether `graphitron-lsp` and `graphitron-mcp` should seed rather than capture. Their
  queries read across many relations at once and the FK chains are deep, so capture is plausibly the
  honest fixture-builder there even under this architecture. Both keep capturing here; whoever finds
  seeding cheaper first can file the question.
* Pruning the helper sets to some minimal basis. Per the governing rule, arriving with more helpers
  than strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be
  taken once each set is visible in one file.
* Touching `graphitron-lsp`'s main-source dependency on `graphitron`. This item adds test-scoped edges
  on artifacts the module already depends on, and leaves the shedding direction the pom records
  exactly where it is.
