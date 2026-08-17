---
id: R680
title: "Gather the fact-store test harnesses of all four modules onto one shared home"
status: Spec
bucket: cleanup
priority: 3
theme: testing
depends-on: [lsp-reads-the-fact-store]
created: 2026-08-14
last-updated: 2026-08-17
---

# Gather the fact-store test harnesses of all four modules onto one shared home

## Problem

Four modules stand a fact store up in their tests, and each arrived at its own way of doing it. No
two modules share a line of it.

### The copies inside `graphitron`, and the rate they arrive at

Fifteen test classes each hand-roll their own harness for the same thing: open a fact store, capture
one SDL fixture into it, run assertions against the resulting `DSLContext`. The helper is called
`withCapturedStore` in most of them and is close to verbatim across those. Eight were counted when
this section was first written; the other seven landed in the three days after, which is the fact
this item should be read on rather than the total. The list below is the original eight, kept because
each entry carries a reason; treat it as a sample of a population the counts under it measure:

* `derive/ColumnMatchClaimTest`, `derive/DemandShadowTest`, `derive/InputOccurrenceShadowTest`,
  `derive/ReferenceStepTargetTest`, `derive/AuthoredClaimConflictsTest`
* `derive/FieldColumnTableTest`, which has already hit the variation problem internally and carries a
  `withCapturedStoreAndClaimDomain` sibling over a private boolean-flag core
* `derive/ClassMemberSlotTest`, whose copy takes no SDL argument, because its fixture SDL is a
  placeholder constant and its subject is the classpath, and which feeds capture a real
  `ClasspathScanner` census
* `diagnostics/DiagnosticFactsTest.withStore`, the same shape with the capture step removed

The seven that arrived after: `derive/AccessorHopTest`, `derive/SeparateFetchTest`,
`derive/ProducerCardinalityTest`, `derive/FieldProducerMethodTest`, `derive/ClassAssignableTest`,
`derive/TypeBackingClassTest`, `derive/TypeBackingShadowTest`. Three of them
(`AccessorHopTest`, `ClassAssignableTest`, `FieldProducerMethodTest`) also feed capture a real
`ClasspathScanner` census, which is the shape the census-as-an-argument decision below rests on: four
independent sites now, not two.

Alongside them, `private static Path write(Path directory, String sdl)` (writing `fixture.graphqls`)
is duplicated verbatim across **eighteen** classes, and **fifteen** declare their own
`private static final String GRAPH = "<OwnClassName>"`. The counts as of 2026-08-17, all four
verifiable in one grep each:

| Population | When specced | Now |
|---|---|---|
| classes hand-rolling a capture harness in `graphitron` | 8 | 15 |
| classes duplicating `private static Path write(` | 8 | 18 |
| classes declaring their own `GRAPH` constant | 8 | 15 |
| files matching `CapturedStore`: the type's ten readers, plus the hand-rolled `withCapturedStore` copies | 9 | 24 |
| test classes reading `graphitron-lsp`'s `StoreFixture` | 28 | 35 |

**The rate is the argument, not the total.** Roughly two to three new copies a week, all of them
inside `graphitron` and none of them in either dependency's scope, means this item's cost grows while
it waits and its inventory rots while it is read. Two consequences are designed for below: the guard
lands *first* rather than last, so no further copy can arrive during the item's own lifetime, and the
inventory becomes a count the guard maintains rather than a list a reader has to trust.

The utility these classes want **already exists**. `capture/CapturedStore` is an `AutoCloseable`
handle offering `of(Path, String)`, `ofPipeline(Path, String)`, a third arm
`ofPipeline(Path, String, String tag)` that puts a tag on the input so `TagLinkSynthesiser` fires (its
only caller is `capture/TaggedCaptureStampTest`, and it has to survive the move intact rather than be
folded into the two-argument arm), `registryOf`, `attributionOf` and `fixtureFile`. Ten test classes
inside `capture/` use it happily. It is package-private,
so `derive/` and `diagnostics/` cannot see it at all. Each of them reinvented it independently.

### The three modules downstream

`graphitron-lsp` has `no.sikt.graphitron.lsp.StoreFixture`, read by 35 test classes. It is not a
degraded copy: it is largely this item's target design, reached independently, and the section on it
below treats it as prior art rather than as debt.

`graphitron-mcp` has `no.sikt.graphitron.mcp.StoreBackedBuild`, read by four test classes
(`GraphitronMcpServerTest`, `LintSuppressionDiagnosticsParityTest`, `DiagnosticsAggregateTest`,
`ServerInstructionsTest`; the fifth reference to the name is the sibling `StoreFixture`'s javadoc,
pointing at it rather than calling it). This one is
*not* another copy of the capture harness, and the difference is the most important structural fact in
this item. It stands up a **file** store and runs a real `GraphQLRewriteGenerator.buildOutput()` into
it, then plays `DevMojo`'s part over the result. Its javadoc says why the substrate is the point:
"Tests over hand-built reports cannot survive the substrate: the loaders read the walk's own streams,
so the rows a test asserts on have to come from a real pipeline run." It shares the bottom of the
stack with the others and nothing above it.

**And now a second fixture beside it, which is this item's thesis demonstrating itself.** The
catalog-facts item added `no.sikt.graphitron.mcp.StoreFixture` while migrating the catalog tools: an
in-memory store plus a direct `FactCapture.capture` call with a `JooqCatalog`, named after the LSP's
fixture and arriving at its `ofCatalog` / `ofMultiSchemaCatalog` / second-graph shapes independently for
the second time. It exists for a good reason, which is that a census read does not need a build and
`StoreBackedBuild` was pricing the generator into every catalog case, so the fixture is not the mistake;
building it a fourth time is. It is a capture-level copy, so it belongs in the L1 row of the table below
rather than beside `StoreBackedBuild`, and it is a fresh test-source `GraphitronModelStore.open()` site
this item's guard would fail. Two things follow. The inventory above understates the drift by one
module, and the prediction this item made about the catalog-facts item, that it would grow
`StoreBackedBuild`, was wrong in a direction worth noting: what a consumer reaches for when the shared
home cannot express its shape is not the nearest existing fixture but a new one.

`graphitron-maven-plugin` has no fixture type at all. `DevMojoTest:291` and
`dev/CatalogRefreshTest:133` open `GraphitronModelStore.open()` inline and write to it directly, two
sites. Small, and exactly the shape
that becomes another named harness the moment a third site appears.

### What the inventory does not count

It counts what stands a store up **for a test to assert a captured view against**. A much larger
population opens a store for some other reason, and the edge has to be drawn around all of it,
because the guard below is sized against this set and not against the capture-level inventory.

**Store-mechanics tests.** `capture/PersistentStoreTest` (14 opens), `capture/StoreReaderTest` (5) and
`capture/WarmStartRefreshTest` (13) have the store's own lifetime, its warm start, its reader and its
file-backed home as their subject. They open and reopen the same directory deliberately, compare cold
against warm, and hold two handles at once. These cannot adopt an L0 that owns the store's lifetime,
because the lifetime is the thing under assertion. They are **permanent** exceptions, not migration
targets. `capture/BrokenSourceStillCapturesPipelineTest` (2) sits beside them: its subject is what is
true of the store when a run fails, so it too holds the store across the failure itself.

**Capture-oracle tests.** `capture/FactCaptureAgreementTest` (26 opens) and `capture/FactSchemaGateTest`
(8) drive `FactCapture` per view, per arm, with the store's population as the subject. They already read
`CapturedStore` where a factory fits and open directly where none does, which is the primitives layer
this item ships working as intended. In scope for L0 in principle, low value, and last in any order.

**Direct writers.** `compile/CompileFactsTest` (7), `capture/CommentRenderabilityGateTest` (2),
`capture/JavaSourceFactsTest` (6) and `capture/SourceGraphScopingTest` (3) open a store and write rows
to it directly rather than capturing SDL into it. All are `@UnitTier`. `CompileFactsTest` references
`FactCapture.GraphIdentity` for the graph key but never calls `capture`. They are in scope for the
bottom of the stack and out of scope for everything above it.

Downstream, the same edge exists and is likewise outside the capture-level inventory:
`graphitron-lsp`'s `RejectionSeverityCoverageTest`, and `graphitron-mcp`'s `DiagnosticsAggregateTest`
(3 opens) and `DiagnosticsToolCompileSourceTest`.

`roadmap-tool`'s `SchemaReferencePagesTest` opens a store too and is out of every scope here, because
`GuardScope.IN_SCOPE_MODULES` excludes that module by design.

Stating the edge is part of the item, because an inventory with no edge is the thing that rots, and
because the guard's drain-to-empty acceptance is only meaningful once the permanent residue is named.

### The duplication is the symptom, not the item

The decision being re-made at every capture-level site above is *which capture inputs the store under
assertion is built from*, and the sites disagree without anything saying why. Past the 5-arg
`FactCapture.capture` default (which fixes `jooq = null`, `extensions = List.of()`,
`nodes = new NodeDeclaration(null)`), these shapes are live in the tree. The 5-arg default is
`FactCapture:378` and the production paths are `GraphQLRewriteGenerator:416` and `:445`, both passing
`new NodeDeclaration(jooq)`:

| Shape | Sites |
|---|---|
| bare | `AuthoredClaimConflictsTest.capture`, `DiagnosticFactsTest`, `CapturedStore.of`, `StoreFixture.of` |
| bare, real classpath census | `StoreFixture.of(directory, sdl, classpath)`, `StoreFixture.ofClasspath` |
| catalog, node inference off | `ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest`, `StoreFixture.ofCatalog`, `StoreFixture.ofMultiSchemaCatalog` |
| catalog, node inference on | `DemandShadowTest`, `InputOccurrenceShadowTest` |
| catalog, node inference off, real classpath census | `ClassMemberSlotTest`, `StoreFixture.ofCatalog(directory, sdl, classpath)` |

The table above is a sample rather than a census, and the population it was drawn from has since
doubled. What the additions do not do is add a shape: every one of the seven passes a `JooqCatalog`,
and all but the shadow tests pass `new NodeDeclaration(null)`, so the dominant shape in the tree is
now catalog-with-inference-off, which is the deviation from production rather than the norm. That
sharpens the arm question below rather than changing it.

That split is not cosmetic. `NodeDeclaration` changes what capture writes, and `DemandShadowTest`'s
own sweep comment says its equality is "also the enforcer for the node-inference seed's
over-approximation." Whether the column-match and reference-step views are node-inference-sensitive
is currently unstated and unasserted. A consolidation that silently picks one default answers that
question by accident; one that carries a boolean flag defers it forever. The item's job is to make
the shape a named choice, and the deduplication follows from that.

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

### The shared home is a stack of levels, not one type

The four fixtures are not four copies of one thing, and treating them as such would produce a type
that does everything and explains nothing. They are four points on a stack, and naming the levels is
what keeps the shared home from becoming that type:

| Level | What it is | Who needs it |
|---|---|---|
| L0 | The store's lifetime, and writing an SDL fixture to a file with an identity | All four modules |
| L1 | Capture-level population: one or more `FactCapture.capture` calls into an open store | `CapturedStore`, LSP's `StoreFixture`, MCP's `StoreFixture` |
| L2 | Build-level population: a real `buildOutput()` run into a file store | `StoreBackedBuild` |
| L3 | The module's own read boundary over a populated store | LSP's `handle()` / `reader()`, MCP's `Workspace` and server wiring |

**L0 through L2 are shared. L3 is always local.** Each module keeps its named fixture as a thin L3
layer over the level below it, which is why `StoreFixture` and `StoreBackedBuild` survive this item
under their own names rather than being deleted into a common type. It is also what keeps the call
sites still: the thing 35 LSP tests and four MCP tests are calling is L3, and L3 is not moving.

The levels are ordered, so the work is ordered. L0 is the floor everything else stands on, L1 is the
consolidation with the most copies behind it, and L2 is one fixture rehomed onto the floor. An
implementer who tries L2 before L0 is settled will be rebuilding it.

### Why the home is `graphitron`'s test-jar, and why a fixtures module is impossible

The obvious shape for reactor-wide shared test support is a dedicated `graphitron-test-fixtures`
module. It cannot exist, and the reason is worth stating so nobody spends a week rediscovering it.

The harness needs `FactCapture`, which lives in `graphitron`'s main sources. `graphitron`'s own
fifteen copies need the harness. So a fixtures module would have to depend on `graphitron`, while
`graphitron`'s tests depend on the fixtures module: a reactor cycle Maven cannot express, because a
module is a single build unit and cannot be both before and after another. The only escape would be
moving capture down into `graphitron-model`, which is a far larger change and the wrong one anyway,
since capture reads SDL and the jOOQ catalog and those are `graphitron`'s business.

So the home is `graphitron`'s test sources, published through the test-jar it already builds and that
`graphitron-sakila-example` already consumes. The dependency chain runs
`graphitron-model` to `graphitron` to `graphitron-lsp` to `graphitron-mcp` to
`graphitron-maven-plugin`, so all three downstream modules can consume it; each adds the test-jar at
test scope. The jOOQ fixture packages these fixtures capture against come from `graphitron-sakila-db`,
which `graphitron-lsp` and `graphitron-mcp` already depend on at test scope, so the catalog shapes
need no new dependency at all.

One objection deserves answering rather than ignoring, because the pom states it outright: the
comment on `graphitron-lsp`'s `graphitron` dependency says the direction of travel is that this
module sheds `graphitron` one type at a time. A test-jar edge appears to cut against that. It does
not. `StoreFixture` already imports `FactCapture`, `RewriteSchemaLoader`, `JooqCatalog`,
`NodeDeclaration`, `ClasspathScanner`, `SourceWalker`, `BuildWarningFacts` and `JavaSourceFacts` from
`graphitron`'s main sources, and `StoreBackedBuild` imports `GraphQLRewriteGenerator` itself. The edge
this item adds is a second scope on a dependency already thick in exactly these files, not a new one.
What the pom comment is about is the main-source dependency, and this item does not touch it.

### Promote the store handle, keep the existing split of responsibility

Widen `CapturedStore` from package-private and move it beside the shared test support in
`no.sikt.graphitron.rewrite`, which already hosts `TestSchemaHelper`, `TestFixtures` and the
`*RenderTestSupport` classes. That package is also tier-neutral, which matters: the incoming
`graphitron` consumers are all `@PipelineTier`, while the ten that already read the handle straddle
both tiers (`FactCaptureAgreementTest`, `TaggedCaptureStampTest` and `WarmStartRefreshTest` are
`@PipelineTier`, the other seven `@UnitTier`). A home that reads as belonging to one tier's family
invites the next reader to infer a tier rule that does not exist, and the mixed readership is already
the status quo rather than something this item introduces.

The downstream readership settles it. The tier annotations are `graphitron`'s own test vocabulary, and
the tests in `graphitron-lsp`, `graphitron-mcp` and `graphitron-maven-plugin` carry none of them; the
handle is about to be read from three modules where the question does not arise. A tier-suggestive
home would have been misleading before and would be plainly wrong after.

Do **not** let this become a third shared-test home. `TestSchemaHelper` already owns the parse-side
primitives (`attribution`, `nodeDeclaration`, `buildSchema`) and the derive tests already call it.
The store handle owns the fixture file and the store's lifetime. Keep that split; the move must not
duplicate `TestSchemaHelper.attribution` under a second name.

Expose `registryOf` / `attributionOf` / `fixtureFile` publicly alongside the factories. The corpus
sweeps drive `FactCapture.capture` themselves, per example, and need the primitives without a
factory arm for every combination; so does `ClassMemberSlotTest` with its census, and so does
`DiagnosticFactsTest` with the one case that passes real `SdlVerdicts` and an explicit `warm` flag.
That is the third layer, under the handle: a test whose axis combination no factory names writes the
capture call itself off shared primitives instead of hand-rolling the file, the attribution and the
store lifetime with it. Naming the primitives as a layer is what keeps the factory set from having to
be the cross-product of catalog, nodes, registry source, extensions and verdicts.

### The governing rule: `CapturedStore` is where these utilities gather

`CapturedStore` is the home for fact-store test utilities, across the reactor rather than within one
module. A test that needs a store shape no existing level produces adds it *there*, at the level it
belongs to, rather than hand-rolling a helper in its own class or its own module. That is the rule
this item establishes, and it outranks the individual shape decisions below. The rule would be worth
little scoped to one module, since three of the four reinventions are outside it.

The failure mode being designed against is fragmentation, not accretion. A spread of private copies
that have quietly diverged is the expensive state, and it is expensive because nothing points a new
test author at the existing answer. A `CapturedStore` carrying more factories than any one reader needs is
the cheap state: the factories are in one file, visible together, and consolidating two that turned
out to be the same is a mechanical afternoon. Growth is expected and fine. If the set gets unwieldy,
clean it then, with the whole set in view, which is exactly the vantage point the current fifteen
copies deny.

So the shape set below is a starting point, not a closed taxonomy, and a later item adding a factory
is the design working rather than failing.

The rule needs two carriers, because it has two audiences. For the author already in the file, the
rule is stated in `CapturedStore`'s class javadoc, as the orientation note a reader meets first. For
the author in another module who will never open it, javadoc is worth nothing, and that is the
audience this item is really about. They get a guard.

### The guard lands first, as a ratchet

The guard is written below as the item's enforcer, which reads as something that arrives once the
migration is done. Take it first instead, and take it before any other level, for a reason the counts
in the Problem section make concrete: copies arrive at two or three a week, so an item that migrates
fifteen classes over several sessions will be migrating seventeen by the time it finishes, and a
reader of its inventory cannot tell which number is current.

A ratchet inverts that. It lands with every existing site named in its allow-list, each entry carrying
its reason, and it fails on any site not named. From that moment the population is frozen: no new copy
can land while the item is in flight, the inventory is a machine-maintained count rather than a list
somebody has to re-grep, and every subsequent slice's acceptance is arithmetic, so many entries
removed. Two ratchet shapes are already in the tree and the right one is the allow-list, not the
count. `CommandSeamRatchetTest` pins measured *numbers* and lowers them commit by commit, which suits
a boundary with no enumerable site list; `RetiredVocabularyGuardTest` holds a named allow-list of
sites that drains to empty, with a record per entry and a staleness assertion on each. This item has
an enumerable list of 33 sites, so `RetiredVocabularyGuardTest` is the precedent to follow.

The count at the moment of landing is what the item is sized against, so the guard slice states it in
its own message: an author who trips it reads how many sites are still allow-listed and where the
shared home is, which is a better first contact with this item than its title.

### What the guard counts, which is both halves of L0

**The store half is the type, not one factory method.** `GraphitronModelStore` has two entry points:
`open()` mints a private in-memory database, `openAt(Path)` opens a file-backed one. Counting only
`open()` would miss the file-store arm entirely, and the file-store arm is the half this item's L0 is
explicitly required to carry named rather than flagged: `StoreBackedBuild` reaches it through
`openAt(storeHome)`, and so does every store-mechanics test. So the recogniser is a test-source
reference to the `GraphitronModelStore` type outside the shared home, which also survives a third
factory being added.

**The fixture-write half needs a recogniser of its own, and a bare `resolve` is not it.** A class can
take its store from the shared home and still hand-roll the fixture file, the graph constant and the
capture call, which is what the eighteen `write(` copies and fifteen `GRAPH` constants become the
moment L0 lands, so the write is worth counting. But a bare `resolve("....graphqls")` in a test source
is not that signal: it matches 44 classes and 124 occurrences across the four modules, and 19 of those
classes reference no store route at all. `SchemaWatcherTest`, `GenerateMojoTest`, `SchemaSdlEmitterTest`,
`CatalogBuilderSnapshotTest` and `MethodClosureOracleTest` each write an SDL file for a watcher, an
emitter, a mojo or a parse test and never stand a store up. Three more do stand one up, through
`StoreFixture`, but the `.graphqls` paths they resolve are negative-case URIs that nothing ever writes:
`LintQuickFixTest`'s `elsewhere.graphqls`, `StoreAccessTest`'s `written-since-the-last-capture.graphqls`
and `CompletionStoreWiringTest`'s `unstored.graphqls`. Allow-listing any of the eight would put
permanent entries in a list whose acceptance is that it drains to empty.

**The obvious qualifier is the one to avoid, because it makes the counter vacuous.** Firing the write
counter only inside a class the store counter already reaches, a `.graphqls` write *in a class that also
references `GraphitronModelStore`*, is a conjunction whose first conjunct is the store counter itself.
Its reached set is therefore a subset of the store counter's and it can never contribute a class: 22 of
the 44 also reference `GraphitronModelStore`, and all 22 sit inside the 33 below. Worse, it is silent on
the one shape it exists for. A class that takes its store from `CapturedStore` stops naming
`GraphitronModelStore`, so from S2 onward, which is the whole migration window, the half-migrated class
the counter is meant to hold falls outside the counter's own qualifier.

**Two changes make it independent, and cost nothing.** Key it on an actual write, `Files.writeString` or
`Files.write` to a `.graphqls` path, rather than on a bare `resolve`; and qualify it on the class
standing a store up *by any route*, `GraphitronModelStore` **or** the shared home. Measured against the
tree as it stands, that recogniser reaches 21 classes, every one already inside the 33, so S1's partition
is untouched. The store-free SDL writers stay excluded by construction rather than by allow-list, and the
three negative-URI classes drop out because they never write. What it newly admits, a class referencing
the shared home while writing its own `.graphqls`, is empty today and becomes non-empty exactly when a
migration does half the job. That is the counter earning its place: it lands green and is a live tripwire
for the state this item spends its whole life passing through.

If the recogniser turns out not to be worth its cost, the honest alternative is to drop the write counter
and say the guard has one. What must not ship is the vacuous form, which is a claim the guard cannot keep.

It deliberately does **not** count `FactCapture.capture` calls in test sources. The primitives layer
below the factories exists precisely so a test whose axis combination no factory names can write that
call itself, and a guard forbidding it would forbid the layer this item ships. The two counters above
catch the same authors by the resources they stand up rather than by the call they make, which is the
distinction that keeps the guard from policing the design it is protecting.

### The allow-list is three lists, and only one of them drains

With the recognisers above, the guard reaches **33** test classes outside the shared home, and the
number is exact rather than approximate because the ratchet's acceptance is arithmetic: 25 inside
`graphitron` (every file there referencing `GraphitronModelStore` except `CapturedStore` itself), two
in `graphitron-lsp`, four in `graphitron-mcp`, two in `graphitron-maven-plugin`. The entries are not
all of one kind, and conflating them is what would make the acceptance unreadable. Every one of the 33
carries exactly one of these three markers, and the guard fails if a reached site carries none:

* **Draining (20).** The fifteen capture harnesses in `graphitron` (the fourteen in `derive/` plus
  `diagnostics/DiagnosticFactsTest`), `graphitron-lsp`'s `StoreFixture`, `graphitron-mcp`'s
  `StoreFixture` and `StoreBackedBuild`, and the maven plugin's `DevMojoTest` and
  `dev/CatalogRefreshTest`. Each slice below removes some of these, and this count reaching zero is
  the item's completion.
* **Permanent (11).** The store-mechanics classes (`capture/PersistentStoreTest`,
  `capture/StoreReaderTest`, `capture/WarmStartRefreshTest`,
  `capture/BrokenSourceStillCapturesPipelineTest`), the direct writers (`compile/CompileFactsTest`,
  `capture/CommentRenderabilityGateTest`, `capture/JavaSourceFactsTest`,
  `capture/SourceGraphScopingTest`), and the three downstream edge classes
  (`graphitron-lsp`'s `RejectionSeverityCoverageTest`, `graphitron-mcp`'s `DiagnosticsAggregateTest`
  and `DiagnosticsToolCompileSourceTest`). These are exceptions on the merits and stay after the item
  is Done. Each carries its reason, and the reason is what makes it an exception rather than a
  backlog.
* **Deferred (2).** The capture-oracle pair, `capture/FactCaptureAgreementTest` (26 opens) and
  `capture/FactSchemaGateTest` (8). The edge section places them in scope for L0 in principle, low
  value, and last in any order, and that is a third disposition rather than either of the two above:
  making them draining would put 34 open sites the item itself calls low-value inside its completion
  gate, and calling them permanent would assert an exception on the merits that the edge section does
  not claim. So they are permitted to stay and not required to drain. Migrating either is a bonus a
  slice may take and no slice owes.

The failure message names the draining count only, since that is the number an author tripping the
guard is being asked about; the permanent and deferred sets are properties of the guard rather than of
the migration.

**Every entry is self-validating, or the list rots exactly as the inventory did.** The precedent is
`RetiredVocabularyGuardTest`, whose `ALLOWED` set holds a record per entry and then asserts each
entry is still real (the file exists, the term is still registered) so a stale entry fails the build
rather than lingering. Take that shape: an allow-list entry naming a class that no longer stands a
store up must fail, which is what makes the draining count a measurement instead of a claim, and what
stops a migrated class keeping its entry and hiding the progress.

Note that the walk covers all ten of `GuardScope.IN_SCOPE_MODULES`, not just the four modules this
item is about. Nothing in the other six references `GraphitronModelStore` in test sources today, so
the guard lands green over them, and that breadth is the point: a fifth module standing a store up
trips the guard on its first commit rather than becoming the next entry in this inventory.

### Why a guard at all, since discoverability alone has been tried and lost

Within one module, "the utility is public and sits next to `TestSchemaHelper` in a package you
already import" would be enough, and a grep ratchet would be maintenance surface bought for nothing.
Across four modules the argument collapses: an MCP test author imports nothing from
`no.sikt.graphitron.rewrite`, and no amount of javadoc on a type they cannot see will reach them. The
evidence is the item itself: every module that needed a store built its own way in, and the pattern is
still running. The post-capture writers have already twinned between `StoreFixture` and
`capture/JavaSourceFactsTest`, and the maven-plugin's two inline sites are a harness that has not been
named yet.

So this item ships an enforcer, and the machinery for it already exists.
`no.sikt.graphitron.rewrite.GuardScope` enumerates every in-scope module root and locates the
repository root by walking up to the `roadmap/workflow.adoc` anchor; `RoadmapReferenceGuardTest` and
`RetiredVocabularyGuardTest` are two guards already walking it. Its javadoc states the reason it is
shared: "One definition, so a new module cannot silently join one guard's scope and not the other's",
which is exactly the failure mode a store-fixture rule has.

The new guard is a third walker over that same scope, with the two recognisers the section above
settles, and the two-part allow-list it describes. Every entry carries its reason, and the permanent
half is marked as such, so the list reads as an exception list rather than as a second, quieter copy
of the inventory. The failure message should name the shared home and say to add a factory there,
since the guard's job is to route an author to the answer, not merely to refuse them.

`GuardScope` is package-private in `no.sikt.graphitron.rewrite`, which is where the shared home is
moving anyway, so the new guard sits beside its two siblings with no visibility change.

The guard is scoped to the store, not to test hygiene in general. It answers one question, "did you
stand a store up outside the shared home", and it should never grow a second. A guard that starts
policing adjacent things is the maintenance surface the single-module version of this item was right
to refuse.

### `graphitron-lsp`: what of `StoreFixture` moves

`StoreFixture` is the strongest evidence the gathering rule is right, because it is what happens
without one. Its class javadoc opens on almost the same sentence as `CapturedStore`'s. It carries
named factories rather than flags (`of`, `ofClasspath`, `ofCatalog`, `ofMultiSchemaCatalog`), each
with the one-line note on what its shape carries that a sibling cannot that the section below
prescribes. It takes a caller-supplied graph name over a shared default, captures a second graph into
an already-open store (`andGraph`, `andGraphSharingTheFile`), and takes the classpath census as a
factory argument rather than an axis. Its placeholder SDL constant is character-for-character
`ClassMemberSlotTest`'s, and it makes the same unexamined `new NodeDeclaration(null)` choice. Two
files, no contact, converging on the same answers and disagreeing on the rest by accident: that is
the state this item exists to end, and it does not stop at a module line.

**The name and the call sites stay.** 35 LSP test classes call `StoreFixture`, so the move is not a
migration of those call sites. `StoreFixture` remains, under its own name, as `graphitron-lsp`'s
local layer: it keeps its factory signatures and delegates the store's lifetime, the fixture file and
the capture call to the shared handle. Its own tests should not need editing, and the references to
it in the LSP item's body stay live. If a call site has to change, that is a signal the shared handle
cannot express a shape the LSP needs, which is the finding, not a licence to edit the test.

**What moves** is the capture half: the store's lifetime, the fixture write, `registryOf` /
`attributionOf` / `fixtureFile`, the named capture factories, the caller-supplied graph name, capture
of a further graph into an open store, and the classpath-census argument.

**What stays in `graphitron-lsp`** is everything whose subject is the LSP's own read boundary rather
than the store's shape: `handle()`, `handleFor()`, `reader()`, `tableClassFqn`, `keysClassFqn`, the
`CompletionData.ExternalReference` builders (`jarClass`, `reactorClass`, `scalarHolder`), the
`no.sikt.graphitron.lsp.fixtures` census and the jOOQ fixture-package constants.

**The post-capture writers move too, and they are the reason the LSP item is a dependency.**
`withBuildWarnings`, `withJavaSource` and `refreshJavaSources` write into the store after capture, and
`capture/JavaSourceFactsTest` already drives the same `JavaSourceFacts` writer by hand, so they are a
consolidation of exactly the kind this item exists for. They are also the surface the LSP item is
still actively growing, which is a sequencing constraint rather than a reason to leave them: they move
once that item is Done, not before.

### `graphitron-mcp`: `StoreBackedBuild` onto the shared floor

`StoreBackedBuild` keeps its name, its `run(...)` factories and all ten of its `run` call sites across
the four test classes named above. What it
stops owning is L0: the store's lifetime and the schema file it writes. Today it resolves
`tmp.resolve("schema.graphqls")` and manages its own `GraphitronModelStore`, which is the third
independent answer in the tree to "where does the fixture file go and who closes the store."

It does **not** move to L1. It has no `FactCapture.capture` call to share, because its population is a
real `buildOutput()` run, and pushing it onto the capture factories would destroy the property its
javadoc is built on. L2 is its level, and L2 stands on L0 like everything else. If it turns out a
second build-level fixture is wanted later, L2 is where it gathers.

One thing to settle while there: `StoreBackedBuild` uses a **file** store, the others in-memory. That
is a real axis, not an accident, since its subject is the dev loop's own wiring. L0 has to carry both,
named, the same way the capture shapes are named rather than flagged.

### `graphitron-maven-plugin`: two inline sites, converted

`DevMojoTest` and `dev/CatalogRefreshTest` open a store inline and write to it directly. They adopt L0
and nothing else; neither needs a capture factory. This is the smallest part of the item and the one
most likely to be dropped for being small, which is precisely why it is written down: the guard will
fail on these two sites, and an allow-list entry added to silence it would be this item defeating
itself on its first day.

### Sequencing against the in-flight items

Both dependencies are live in code this item touches. Only the LSP one is declared in `depends-on`, for
the reason the paragraphs below give.

* The LSP item is In Progress and has touched `StoreFixture` in most of its recent commits, including
  the post-capture writers this item moves.
* The catalog-facts item is In Progress. It began by moving the MCP catalog tests onto
  `StoreBackedBuild` and then moved them off again onto a capture-level fixture of its own, for the
  reason the inventory above records, so what it hands over is one more L1 copy rather than a grown L2
  one.

**Only the LSP slice waits, and `depends-on` says so now.** The catalog-facts dependency is dropped: its
MCP fixture stopped moving when its catalog slices landed, and everything this item does inside
`graphitron` touches no file either in-flight item holds. Waiting on both was costing what the counts
above measure. The LSP dependency stays, because that item is still growing the post-capture writers
this one moves, and its slice is last anyway.

The price of waiting is also concrete rather than a preference. The catalog-facts item has five tool
slices left and each needs capture shapes the LSP's fixture already carries: the classpath census its
code-tools slice reads is `StoreFixture.ofClasspath`, the source locations it renders are
`withJavaSource` / `refreshJavaSources`, and the diagnostics axes its status slice reads are
`withBuildWarnings`. Left alone, that item re-derives most of the LSP's fixture inside `graphitron-mcp`
one slice at a time, and this item's job grows by a copy per slice on top of the two or three a week
already arriving in `graphitron`.

### Name the shapes; do not flag them

Give the L1 handle named factories, one per capture shape in the table above, with the census carried
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

**An arm nothing distinguishes is decoration, so the resolution owes a discriminating case.** The
counts above sharpen this: eleven of the fifteen sites pass `new NodeDeclaration(null)` while every
production capture path passes `new NodeDeclaration(jooq)`, so inference-off is both the deviation and
the majority. Keeping it as a named arm is only honest if at least one case in the suite *fails* when
the arm flips. `DemandShadowTest` is the natural home, its own sweep comment already claiming to be
"the enforcer for the node-inference seed's over-approximation". If no such case can be written, that
is the finding and the two arms collapse into one, which is the outcome this section says it is willing
to reach.

### Layered: a closure convenience over a resource handle over the primitives

`capture/` uses `try (var store = CapturedStore.of(tmp, FIXTURE))`; `derive/` uses
`withCapturedStore(sdl, dsl -> {...})`. Ship both, layered over the exposed primitives, with the
closure form as the one most tests reach for.

The goal here is good common tools, not a single sanctioned way to open a store. The closure form is
genuinely the nicer call site for the common case, which is most of the fifteen classes: hand it SDL,
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

One site asserts on the literal `fixture.graphqls` and the first draft of this section said none did.
`capture/WarmStartRefreshTest` writes its own fixture under that name and then asserts the re-expansion
finds it, comparing the absolute normalized path as a string (`containsExactlyInAnyOrder(tmp.resolve(
"fixture.graphqls")...)`). Its subject is the stamp and the recipe expansion, so the filename is
incidental to what it pins and the assertion moves with the rename; what matters is that the
implementer meets this site knowingly. It is also the one place where this item's acceptance rule, that
an assertion needing to change signals a load-bearing axis, does not apply: here the changed assertion
is the fixture's own name, which is what the rename is. Say so at the call site when it changes.

Fifteen further classes hardcode `directory.resolve("fixture.graphqls")` inside their own `write`
copies (the fourteen `derive/` harnesses plus `diagnostics/DiagnosticFactsTest`; seventeen files carry
the literal in all, the other two being `CapturedStore` itself and `WarmStartRefreshTest`), so the
rename is not one line but one line per copy, taken as each copy migrates. The sweeps then stop
minting subdirectories, which is a small hand-rolled step deleted rather than moved.

One line of the fifteen copies has to survive the merge: each of them calls `Files.createDirectories`
before writing and `CapturedStore.write` does not, because every `capture/` caller hands it a
`@TempDir` that already exists. Every incoming caller writes into a directory it named itself, so the
shared `write` needs that line or the sweeps and the sibling-partition negatives fail on the first
subdirectory.

### Make the registry source an explicit arm

All seven `derive/` helpers feed capture a bare `RewriteSchemaLoader.load` registry, as does
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

### Seeding gathers here too, and stays legible by naming

`ColumnMatchClaimTest` carries `withSeededStore` plus `seedGraph` / `seedSource` / `seedField` /
`seedTable` / `seedSchema` / `seedColumn`; `ReferenceStepTargetTest` carries `withCollidingKeySeed`
plus `seedTable` / `seedRootType` / `seedStep`. These insert store rows directly and bypass capture,
because they construct states capture cannot reach. The hazard that governs them is already stated in
two class javadocs, from opposite ends. `ReferenceStepTargetTest` states the permission and its
price: "a fixture is free to seed a chain the catalog cannot connect, and the case then documents
behaviour no build can produce," and it then names the two conditions under which its own cases seed.
`FieldColumnTableTest` reaches the other conclusion from the same premise, that a seeded fixture could
assert a combination no schema produces, and so seeds nothing at all. Each seeded case carries its own
escape note on top of that.

A separate `SeededStore` type is the tempting shape, since it would keep the reach for an
unreachable-by-production store state visible at the call site. Reject it: under the gathering rule a
second type is a second thing to fail to discover, and seeding is exactly the capability a test author
is most likely to hand-roll if they do not find it. Seeding lives on `CapturedStore` with everything
else.

The call-site signal comes from naming instead, which is cheaper and does the same job. Seeded entry
points say so in their names (`seeded...` rather than a neutral `of...`), so a reviewer scanning a
diff and a `grep` over the tree both still separate the two populations. The "only reachable by
seeding" justification becomes a per-method javadoc requirement rather than the class-level paragraph
it would become if the methods sat anonymously beside the capture-backed ones.

One concrete consolidation inside the seeded set is worth calling out: `ColumnMatchClaimTest` and
`ReferenceStepTargetTest` each spell a full minimal `graphql_type` / `graphql_field` /
`graphql_type_declaration` row by hand, differently. Those are hand-written twins of what capture
writes, and a DDL change adding a non-null column should break one place rather than two.

### Update the agreement anchors

`FactCaptureAgreementTest`'s class javadoc cites the migrated classes by name as the registered
per-view agreement anchors, and the list has grown with the population: **thirteen** distinct fully
qualified `{@code}` names in the class javadoc as of 2026-08-17, the original seven
(`ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest`, `ClassMemberSlotTest`,
`DemandShadowTest`, `InputOccurrenceShadowTest`, `AuthoredClaimConflictsTest`, the last of which is
cited twice) plus `AccessorHopTest`, `ClassAssignableTest`, `FieldProducerMethodTest`,
`ProducerCardinalityTest`, `TypeBackingClassTest` and `TypeBackingShadowTest`. A fourteenth,
`TypeBackingClassesTest`, is cited the same way but in a *method* javadoc further down the file
(`FactCaptureAgreementTest:1689`), so an implementer sweeping the class javadoc alone will miss it.
`SeparateFetchTest` is a harness copy but is not an anchor, so it does not appear.

Convert them to `{@link}` while touching them, which is the cheap half of this finding. The reason
this section exists is that `{@code}` is invisible to the javadoc reference gate, so a rename or move
rots the citation silently; every one of the fourteen names a live class today, so the conversion is
mechanical and the gate maintains the list from then on. Note that the gate runs javadoc against main
sources, so it will not maintain a citation living in a test class; converting to `{@link}` still buys
the compiler's own resolution of the reference, which is what stops the silent rot. Doing it in this item is what stops the same
paragraph having to be written again for the next consolidation.
`docs/architecture/explanation/fact-model.adoc` cites four of them the same way
(`AuthoredClaimConflictsTest`, `ColumnMatchClaimTest`, `DemandShadowTest`,
`InputOccurrenceShadowTest`) plus `DiagnosticFactsTest` on the diagnostics stratum. Because the
citations are `{@code}` and not `{@link}`, the javadoc reference gate will not catch a rename or move,
so any class rename in this item must update both surfaces by hand.

## Slices

Six slices, ordered so each lands on settled files and each has an arithmetic acceptance. The item is
too large for one session: fifteen classes in `graphitron`, thirty-five reader classes in the language
server, four modules and an open design question.

**S1: the ratchet.** The guard, both counters, all 33 current sites allow-listed with their reasons
and marked draining, permanent or deferred, each entry self-validating, and the draining count in its
failure message. Nothing migrates. Acceptance: the build fails on a new site, proved by a negative
case; it fails on a stale entry, proved the same way; the write counter has a negative case of its own,
a class taking its store from the shared home while writing its own `.graphqls`, since that arm reaches
nothing in the tree as it stands and would otherwise ship unobserved; it passes on the tree as it stands;
and the three markers partition the 33 with nothing unmarked, so 20 + 11 + 2 is the number the guard
itself reports.

**S2: L0.** The store's lifetime, the fixture file with the filename keyed on the graph name, the
caller-supplied graph identity, `registryOf` / `attributionOf` / `fixtureFile` public, and
`CapturedStore` widened and moved beside `TestSchemaHelper`. Its current `capture/` readers move with
it. Acceptance: those readers pass untouched except for the import, and the `WarmStartRefreshTest`
filename assertion moves knowingly.

**S3: L1 inside `graphitron`.** The named capture factories, the census as an argument, the registry
arm, and the fifteen hand-rolled harnesses drained in batches. This is the slice that resolves the
node-inference arm, and it owes the discriminating case. Acceptance: allow-list entries removed per
batch, and no assertion content changed.

**S4: `graphitron-mcp`.** Its `StoreFixture` becomes an L3 delegator and `StoreBackedBuild` adopts L0,
keeping its file-store arm named rather than flagged. Acceptance: no MCP test class is edited, and two
allow-list entries go.

**S5: `graphitron-maven-plugin`.** The two inline sites adopt L0. Acceptance: two allow-list entries go,
and neither is silenced instead.

**S6: `graphitron-lsp`.** `StoreFixture` keeps its name and its thirty-five reader classes and delegates
its capture half, including the post-capture writers, which is the part that waits on the LSP item. This
is also where the seeding consolidation and the hand-written `graphql_type` / `graphql_field` twins land,
both of them cross-cutting enough to want the whole set in view. Acceptance: the allow-list's draining
section is empty, only the permanent and deferred entries remain, and no LSP test class is edited.

What must not happen is a rehome landing on top of a file another item is still rewriting, which the
slice order above is arranged to avoid. This item does not reach Done until all four modules are on the
shared home and the allow-list's draining section is empty. The permanent and deferred entries stay,
which is why the guard reports the draining count rather than the list's length.

## Tests

This item changes test-support code only, so its acceptance is the existing suite across all four
modules, passing with its assertion content unchanged: the fifteen migrated `graphitron` classes, the
ten `capture/` classes that already read `CapturedStore`, the 35 `graphitron-lsp` classes that read
`StoreFixture`, the four `graphitron-mcp` classes that read `StoreBackedBuild`, and the
`graphitron-maven-plugin` pair. Those five numbers are the same populations the Problem section's
table measures, and they are the ones each slice's acceptance is counted against; a slice that
declares itself done against the pre-review counts (eight, nine, 28) is done against a population
that no longer exists. An assertion that has to change to accommodate the shared home is the
signal that an axis was load-bearing after all and must stay expressible, not that the assertion
should be relaxed. The node-inference axis is the one to watch.

The three downstream modules carry a second, sharper acceptance, because their call sites are not
being migrated: `StoreFixture`'s and `StoreBackedBuild`'s own consumers should be untouched. A diff
that edits an LSP or MCP test class is reporting that the shared level cannot express a shape that
module needs. Treat that as the finding and widen the shared level, rather than adjusting the test to
suit it.

The guard is the one genuinely new test, and it needs a negative case per counter: a fixture source that
opens a store outside the shared home must fail it, and so must one that takes its store from the shared
home and writes its own `.graphqls`. The second matters more than it looks, because that arm reaches
nothing in the tree as it stands; without a negative case it would ship unobserved and stay that way
until the migration it is meant to police walks into it. A guard whose passing state is the only state
ever observed is a guard nobody knows is wired up.

`mvn install -Plocal-db` is the gate, and it has to be the full reactor build rather than any
`-pl` run, since three of the four modules are downstream of the one the shared home lives in. Inner
loops per level: `mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution` for L0 and the
`graphitron` half of L1, then `-pl :graphitron-lsp -am`, `-pl :graphitron-mcp -am` and
`-pl :graphitron-maven-plugin -am` as each module adopts.

Discoverability is not symmetric across the modules, and the item should not pretend otherwise. A
`graphitron` author meets the shared home directly. A downstream author meets their own L3 fixture
first and the shared level only behind it, which is the right layering anyway, since what those tests
want is usually the reader-side surface their own fixture keeps. What the item buys them is not "here
is the utility" but "the shape you need is one delegation away, and adding it there serves every
module." Each L3 fixture's class javadoc says so, the same carrier requirement the gathering rule
takes on `CapturedStore`, and the guard is what catches the author who reads neither.

## Out of scope

* Changing what any migrated class asserts, and editing the L3 call sites in `graphitron-lsp` or
  `graphitron-mcp` at all.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
* Pushing `StoreBackedBuild` onto the L1 capture factories. Its population is a real `buildOutput()`
  run and that is the property its tests stand on; it adopts L0 and stays at L2.
* Pruning the factory set to some minimal basis. Per the gathering rule, arriving with more factories
  than strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be
  taken once the whole set is visible in one file.
* Touching `graphitron-lsp`'s main-source dependency on `graphitron`. This item adds a test-scoped
  edge on an artifact the module already depends on, and leaves the shedding direction the pom
  records exactly where it is.
