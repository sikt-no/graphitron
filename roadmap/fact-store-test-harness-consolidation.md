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

### The copies inside `graphitron`

Most of the `derive/` test classes, and `diagnostics/DiagnosticFactsTest` beside them, hand-roll their
own harness for the same thing: open a fact store, capture one SDL fixture into it, run assertions
against the resulting `DSLContext`. The helper is called `withCapturedStore` in most of them and is
close to verbatim across those. Each also carries its own
`private static Path write(Path directory, String sdl)` writing `fixture.graphqls`, and its own
`private static final String GRAPH = "<OwnClassName>"`. New copies keep arriving, because there is
nothing for a new test author to reach for instead.

Four of them are worth naming, because each states a constraint the shared home has to meet rather
than being one more copy:

* `derive/FieldColumnTableTest` has already hit the variation problem internally, and carries a
  `withCapturedStoreAndClaimDomain` sibling over a private boolean-flag core. That is what a
  hand-rolled harness turns into when the variation is left to grow inside one class.
* `derive/ClassMemberSlotTest` takes no SDL argument, because its fixture SDL is a placeholder
  constant and its subject is the classpath. It feeds capture a real `ClasspathScanner` census, as do
  `derive/AccessorHopTest`, `derive/ClassAssignableTest` and `derive/FieldProducerMethodTest`. Four
  independent sites want the census, which is what the census-as-an-argument decision below rests on.
* `diagnostics/DiagnosticFactsTest.withStore` is the same shape with the capture step removed, so each
  case drives capture itself. It is the primitives layer this item ships, already existing in the tree.
* The corpus sweeps in `derive/ColumnMatchClaimTest`, `derive/DemandShadowTest` and
  `derive/InputOccurrenceShadowTest` capture one graph per example into a single store and read the
  partition as the assertion, which is what forces the graph identity to be caller-supplied.

The utility these classes want **already exists**. `capture/CapturedStore` is an `AutoCloseable`
handle offering `of(Path, String)`, `ofPipeline(Path, String)`, a third arm
`ofPipeline(Path, String, String tag)` that puts a tag on the input so `TagLinkSynthesiser` fires (its
only caller is `capture/TaggedCaptureStampTest`, and it has to survive the move intact rather than be
folded into the two-argument arm), `registryOf`, `attributionOf` and `fixtureFile`. The test classes
inside `capture/` use it happily. It is package-private, so `derive/` and `diagnostics/` cannot see it
at all. Each of them reinvented it independently.

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
fixture and arriving at its `ofCatalog` / `ofMultiSchemaCatalog` / second-graph shapes independently for
the second time. It exists for a good reason, which is that a census read does not need a build and
`StoreBackedBuild` was pricing the generator into every catalog case, so the fixture is not the mistake;
building it a fourth time is. It is a capture-level copy, so it belongs in the L1 row of the table below
rather than beside `StoreBackedBuild`. The prediction this item first made about the catalog-facts item,
that it would grow `StoreBackedBuild`, was wrong in the direction that matters: what a consumer reaches
for when the shared home cannot express its shape is not the nearest existing fixture but a new one.

`graphitron-maven-plugin` has no fixture type at all. `DevMojoTest` and `dev/CatalogRefreshTest` open
`GraphitronModelStore.open()` inline and write to it directly, one site each. Small, and exactly the
shape that becomes another named harness the moment a third site appears.

### Where the shared home stops

A larger population opens a store for reasons that are not "populate a view and assert against it",
and the edge matters because it tells the implementer which classes the levels below are *not*
designed for.

**Store-mechanics tests.** `capture/PersistentStoreTest`, `capture/StoreReaderTest` and
`capture/WarmStartRefreshTest` have the store's own lifetime, its warm start, its reader and its
file-backed home as their subject. They open and reopen the same directory deliberately, compare cold
against warm, and hold two handles at once. These cannot adopt an L0 that owns the store's lifetime,
because the lifetime is the thing under assertion. They stay as they are.
`capture/BrokenSourceStillCapturesPipelineTest` sits beside them: its subject is what is true of the
store when a run fails, so it too holds the store across the failure itself.

**Capture-oracle tests.** `capture/FactCaptureAgreementTest` and `capture/FactSchemaGateTest` drive
`FactCapture` per view, per arm, with the store's population as the subject. They already read
`CapturedStore` where a factory fits and open directly where none does, which is the primitives layer
this item ships, working as intended. Nothing here needs to change for the item to be finished.

**Direct writers.** `compile/CompileFactsTest`, `capture/CommentRenderabilityGateTest`,
`capture/JavaSourceFactsTest` and `capture/SourceGraphScopingTest` open a store and write rows to it
directly rather than capturing SDL into it. They want L0 and nothing above it. Downstream,
`graphitron-lsp`'s `RejectionSeverityCoverageTest` and `graphitron-mcp`'s
`DiagnosticsToolCompileSourceTest` are the same shape.

Stating the edge is part of the item: an L1 that tried to serve these classes would be the
does-everything type the next section exists to avoid.

### The duplication is the symptom, not the item

The decision being re-made at every capture-level site above is *which capture inputs the store under
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
sites still: what the downstream tests are calling is L3, and L3 is not moving.

The levels are ordered, so the work is ordered. L0 is the floor everything else stands on, L1 is the
consolidation with the most copies behind it, and L2 is one fixture rehomed onto the floor. An
implementer who tries L2 before L0 is settled will be rebuilding it.

### Why the home is `graphitron`'s test-jar, and why a fixtures module is impossible

The obvious shape for reactor-wide shared test support is a dedicated `graphitron-test-fixtures`
module. It cannot exist, and the reason is worth stating so nobody spends a week rediscovering it.

The harness needs `FactCapture`, which lives in `graphitron`'s main sources, and `graphitron`'s own
copies need the harness. So a fixtures module would have to depend on `graphitron`, while
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
`graphitron` consumers are all `@PipelineTier`, while the `capture/` classes that already read the
handle straddle both tiers (`FactCaptureAgreementTest`, `TaggedCaptureStampTest` and
`WarmStartRefreshTest` are `@PipelineTier`, the rest `@UnitTier`). A home that reads as belonging to
one tier's family invites the next reader to infer a tier rule that does not exist, and the mixed
readership is already the status quo rather than something this item introduces.

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
clean it then, with the whole set in view, which is exactly the vantage point the scattered private
copies deny.

So the shape set below is a starting point, not a closed taxonomy, and a later item adding a factory
is the design working rather than failing.

The rule needs two carriers, because it has two audiences. For the author already in the file, the
rule is stated in `CapturedStore`'s class javadoc, as the orientation note a reader meets first. For
the author in another module who will never open it, javadoc is worth nothing, and that is the
audience this item is really about. They get a guard.

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
mode a store-fixture rule has. `GuardScope` is package-private in `no.sikt.graphitron.rewrite`, which
is where the shared home is moving anyway, so the new guard sits beside its siblings with no
visibility change.

**One recogniser: a test-source reference to `GraphitronModelStore` outside the shared home.** The
type, not one factory method, because `open()` mints an in-memory database and `openAt(Path)` a
file-backed one, and the file-backed arm is the half L0 is explicitly required to carry named rather
than flagged. Naming the type also survives a third factory being added.

Resist the temptation to add a second recogniser over hand-rolled `.graphqls` writes. Qualifying it by
the store makes it a subset of the recogniser above, so it can never reach a class that one does not;
leaving it unqualified sweeps in every watcher, emitter, mojo and parse test that writes an SDL file
without going near a store, which is a large permanent exception list bought for nothing. The guard
answers one question, "did you stand a store up outside the shared home", and it should never grow a
second. The failure message names the shared home and says to add a factory there, because the guard's
job is to route an author to the answer rather than merely to refuse them.

**Exceptions are named, with reasons, and there is no arithmetic over them.** The classes in "Where
the shared home stops" stand a store up on purpose and stay that way, so each gets an entry saying
which of the three reasons it is: the lifetime is the subject, the test writes rows directly, or the
test is a capture oracle driving `FactCapture` per arm. Follow `RetiredVocabularyGuardTest`'s shape,
a record per entry with an assertion that the entry is still real, so an entry naming a class that no
longer stands a store up fails the build instead of lingering. That is the only bookkeeping the guard
owes: the list stays honest, and nobody has to count it.

Nothing in the migration depends on when the guard lands, so the Slices section puts it last, where its
exception list is only the classes that stay.

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

**The name and the call sites stay.** Most of the module's test classes call `StoreFixture`, so the
move is not a migration of those call sites. `StoreFixture` remains, under its own name, as `graphitron-lsp`'s
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

`StoreBackedBuild` keeps its name, its `run(...)` factories and every one of its call sites. What it
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
fail on these two sites, and an exception entry added to silence it would be this item defeating
itself.

### Sequencing against the in-flight items

Both dependencies are live in code this item touches. Only the LSP one is declared in `depends-on`, for
the reason the paragraphs below give.

* The LSP item is In Progress and has touched `StoreFixture` in most of its recent commits, including
  the post-capture writers this item moves.
* The catalog-facts item is In Progress. It began by moving the MCP catalog tests onto
  `StoreBackedBuild` and then moved them off again onto a capture-level fixture of its own, for the
  reason the Problem section records, so what it hands over is one more L1 copy rather than a grown L2
  one.

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

Six slices, following the level order above so each lands on files the previous one has settled. The
item is too large for one session: four modules, a stack of levels, and one open design question.

**S1: L0.** The store's lifetime, the fixture file with the filename keyed on the graph name, the
caller-supplied graph identity, `registryOf` / `attributionOf` / `fixtureFile` public, and
`CapturedStore` widened and moved beside `TestSchemaHelper`. Its current `capture/` readers move with
it. Acceptance: those readers pass untouched except for the import, and the `WarmStartRefreshTest`
filename assertion moves knowingly.

**S2: L1 inside `graphitron`.** The named capture factories, the census as an argument, the registry
arm, and the hand-rolled harnesses migrated in batches. This is the slice that resolves the
node-inference arm, and it owes the discriminating case. Acceptance: every `derive/` and
`diagnostics/` harness calls the shared home, no `private static Path write(` copy survives there, and
no assertion content changed.

**S3: `graphitron-mcp`.** Its `StoreFixture` becomes an L3 delegator and `StoreBackedBuild` adopts L0,
keeping its file-store arm named rather than flagged. Acceptance: no MCP test class is edited.

**S4: `graphitron-maven-plugin`.** The two inline sites adopt L0. Acceptance: neither is left inline
on the grounds that it is only two, which is the way this slice fails.

**S5: `graphitron-lsp`.** `StoreFixture` keeps its name and its readers, and delegates its capture
half, including the post-capture writers, which is the part that waits on the LSP item. This is also
where the seeding consolidation and the hand-written `graphql_type` / `graphql_field` twins land, both
cross-cutting enough to want the whole set in view. Acceptance: no LSP test class is edited.

**S6: the guard.** Last, so its exception list is only the classes that stay: the store-mechanics
tests, the direct writers and the capture oracles, each with its reason. Acceptance: it fails on a
source that stands a store up outside the shared home, proved by a negative case; it fails on an
exception entry whose class no longer stands a store up, proved the same way; and it passes on the tree.
An implementer who wants the guard earlier may take it earlier, carrying temporary entries for the
modules not yet migrated, but nothing later depends on that choice.

What must not happen is a rehome landing on top of a file another item is still rewriting, which the
slice order is arranged to avoid. The item reaches Done when all four modules are on the shared home
and the guard is green over the tree.

## Tests

This item changes test-support code only, so its acceptance is the existing suite across all four
modules, passing with its assertion content unchanged. An assertion that has to change to accommodate
the shared home is the signal that an axis was load-bearing after all and must stay expressible, not
that the assertion should be relaxed. The node-inference axis is the one to watch.

The three downstream modules carry a second, sharper acceptance, because their call sites are not
being migrated: `StoreFixture`'s and `StoreBackedBuild`'s own consumers should be untouched. A diff
that edits an LSP or MCP test class is reporting that the shared level cannot express a shape that
module needs. Treat that as the finding and widen the shared level, rather than adjusting the test to
suit it.

The guard is the one genuinely new test, and it needs its own negative case: a fixture source that
stands a store up outside the shared home must fail it. A guard whose passing state is the only state
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
