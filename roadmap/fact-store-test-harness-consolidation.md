---
id: R680
title: "Consolidate the hand-rolled fact-store test harnesses onto one shared utility"
status: Spec
bucket: cleanup
priority: 3
theme: testing
depends-on: [lsp-reads-the-fact-store]
created: 2026-08-14
last-updated: 2026-08-15
---

# Consolidate the hand-rolled fact-store test harnesses onto one shared utility

## Problem

Eight `@PipelineTier` test classes each hand-roll their own harness for the same thing: open a fact
store, capture one SDL fixture into it, run assertions against the resulting `DSLContext`. The
helper is called `withCapturedStore` in seven of them and is close to verbatim across those seven:

* `derive/ColumnMatchClaimTest`, `derive/DemandShadowTest`, `derive/InputOccurrenceShadowTest`,
  `derive/ReferenceStepTargetTest`, `derive/AuthoredClaimConflictsTest`
* `derive/FieldColumnTableTest`, which has already hit the variation problem internally and carries a
  `withCapturedStoreAndClaimDomain` sibling over a private boolean-flag core
* `derive/ClassMemberSlotTest`, whose copy takes no SDL argument, because its fixture SDL is a
  placeholder constant and its subject is the classpath, and which feeds capture a real
  `ClasspathScanner` census
* `diagnostics/DiagnosticFactsTest.withStore`, the same shape with the capture step removed

Alongside them, `private static Path write(Path directory, String sdl)` (writing `fixture.graphqls`)
is duplicated verbatim across all eight, and each of the eight declares its own
`private static final String GRAPH = "<OwnClassName>"`.

The utility these classes want **already exists**. `capture/CapturedStore` is an `AutoCloseable`
handle offering `of(Path, String)`, `ofPipeline(Path, String)`, `registryOf`, `attributionOf` and
`fixtureFile`, and nine test classes inside `capture/` use it happily. It is package-private,
so `derive/` and `diagnostics/` cannot see it at all. Each of them reinvented it independently.

A ninth copy lives one module over: `no.sikt.graphitron.lsp.StoreFixture`, in `graphitron-lsp`'s test
sources, read by 28 LSP test classes. It is in scope, and it is a different kind of entry on this
list from the eight, because it is not a degraded copy: it is largely this item's target design,
reached independently. "The cross-module copy, and what of it moves" below is about it.

The inventory is bounded rather than exhaustive: it counts the classes that capture an SDL fixture
into a store they then assert against. Three classes in this module open a store without capturing
anything and are deliberately outside it: `compile/CompileFactsTest`,
`capture/CommentRenderabilityGateTest` and `capture/JavaSourceFactsTest`. All three are `@UnitTier`,
and none calls `FactCapture` at all; they open a store and write to it directly, which is not the
harness this item consolidates. Stating the edge is part of the item, because an inventory with no
edge is the thing that rots.

**But the duplication is the symptom, not the item.** The decision being re-made at every site above
is *which capture inputs the store under assertion is built from*, and the sites disagree without
anything saying why. Past the 5-arg `FactCapture.capture` default (which fixes `jooq = null`,
`extensions = List.of()`, `nodes = new NodeDeclaration(null)`), these shapes are live in the tree:

| Shape | Sites |
|---|---|
| bare | `AuthoredClaimConflictsTest.capture`, `DiagnosticFactsTest`, `CapturedStore.of`, `StoreFixture.of` |
| bare, real classpath census | `StoreFixture.of(directory, sdl, classpath)`, `StoreFixture.ofClasspath` |
| catalog, node inference off | `ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest`, `StoreFixture.ofCatalog`, `StoreFixture.ofMultiSchemaCatalog` |
| catalog, node inference on | `DemandShadowTest`, `InputOccurrenceShadowTest` |
| catalog, node inference off, real classpath census | `ClassMemberSlotTest`, `StoreFixture.ofCatalog(directory, sdl, classpath)` |

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

An earlier draft of this item treated the census as a one-off, and said a second census-carrying test
appearing later would be the trigger to mint an arm for it. The second one already exists, and it is
`StoreFixture`; two independent fixtures reached for the same argument. That does not overturn the
conclusion, it sharpens it: the census is settled as an argument on the factories that take one,
carried the way `StoreFixture` already carries it, rather than as an axis the factory set forks on.

## Implementation

### Promote the store handle, keep the existing split of responsibility

Widen `CapturedStore` from package-private and move it beside the shared test support in
`no.sikt.graphitron.rewrite`, which already hosts `TestSchemaHelper`, `TestFixtures` and the
`*RenderTestSupport` classes. That package is also tier-neutral, which matters: the incoming
`graphitron` consumers are all `@PipelineTier`, while the nine that already read the handle straddle
both tiers (`FactCaptureAgreementTest`, `TaggedCaptureStampTest` and `WarmStartRefreshTest` are
`@PipelineTier`, the other six `@UnitTier`). A home that reads as belonging to one tier's family
invites the next reader to infer a tier rule that does not exist, and the mixed readership is already
the status quo rather than something this item introduces.

The LSP readership settles it. The tier annotations are `graphitron`'s own test vocabulary, and
`graphitron-lsp`'s tests carry none of them; the handle is about to be read from a module where the
question does not arise. A tier-suggestive home would have been misleading before and would be
plainly wrong after.

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
module. A test that needs a store shape no existing factory produces adds a factory *there*, rather
than hand-rolling a helper in its own class. That is the rule this item establishes, and it outranks
the individual shape decisions below. Reaching across the module line is what the next section is
about; the rule would be worth little if the largest reinvention in the tree sat outside it.

The failure mode being designed against is fragmentation, not accretion. A spread of private copies
that have quietly diverged is the expensive state, and it is expensive because nothing points a new
test author at the existing answer. A `CapturedStore` carrying more factories than any one reader needs is
the cheap state: the factories are in one file, visible together, and consolidating two that turned
out to be the same is a mechanical afternoon. Growth is expected and fine. If the set gets unwieldy,
clean it then, with the whole set in view, which is exactly the vantage point the current eight
copies deny.

So the shape set below is a starting point, not a closed taxonomy, and a later item adding a factory
is the design working rather than failing.

The rule needs a carrier, or it is a sentence in a plan nobody reads twice. The per-factory javadoc
notes required below do not carry it: those explain a shape to someone already in the file, and the
author this rule is aimed at is the one who never opens it. So the rule is stated in
`CapturedStore`'s class javadoc, as the orientation note a reader meets first. That is the whole of
the enforcement this item ships, deliberately; the Tests section says why no guard beyond it.

### The cross-module copy, and what of it moves

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

**The name and the call sites stay.** 28 LSP test classes call `StoreFixture`, so the move is not a
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

**What is deliberately not moved** is the post-capture writers: `withBuildWarnings`,
`withJavaSource` and `refreshJavaSources`. They do qualify under the gathering rule, and there is a
real consolidation waiting in them, since `capture/JavaSourceFactsTest` drives the same
`JavaSourceFacts` writer by hand. They are also the exact surface the LSP item is still growing.
Moving a target that is still moving is how a merge conflict turns into a design regression, so they
stay put and the consolidation is named here as the follow-up rather than smuggled in.

The mechanism is small. `graphitron` already publishes a test-jar; `graphitron-lsp` adds it at test
scope, the way `graphitron-sakila-example` already consumes it. The jOOQ fixture packages
`StoreFixture` captures against come from `graphitron-sakila-db`, which `graphitron-lsp` already
depends on at test scope, so the catalog and multi-schema shapes need no new dependency at all.

One objection deserves answering rather than ignoring, because the pom states it outright: the
comment on `graphitron-lsp`'s `graphitron` dependency says the direction of travel is that this
module sheds `graphitron` one type at a time. A test-jar edge appears to cut against that. It does
not, and the distinction is worth being precise about. `StoreFixture` already imports `FactCapture`,
`RewriteSchemaLoader`, `JooqCatalog`, `NodeDeclaration`, `ClasspathScanner`, `SourceWalker`,
`BuildWarningFacts` and `JavaSourceFacts` from `graphitron`'s main sources. The edge this item adds
is a second scope on a dependency that is already thick in exactly these files, not a new one, and
capture lives in `graphitron`, so any fixture standing a store up by real capture depends on
`graphitron` whatever module it sits in. What the pom comment is about is the *main*-source
dependency, and this item does not touch it.

### Sequencing against the LSP item

The LSP item that is moving the LSP onto the fact store is In Progress and has touched
`StoreFixture` in most of its recent commits, which is why this item declares it in `depends-on`.
The expected case needs no special handling: this item is in Spec and that one is In Progress, so it
should land first and this item picks up a settled file.

If it has not landed when work starts here, take the two halves in order: the `graphitron` module
half stands on its own and can go first, and the `StoreFixture` half follows once the other item is
Done. What must not happen is the two halves landing as one commit on top of a file still being
rewritten. This item does not reach Done until both halves are in.

### Name the shapes; do not flag them

Give the handle named factories, one per capture shape in the first three rows above, rather than
nullable arguments or a `boolean`. Named entry points are what make the set legible enough to prune
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

### Layered: a closure convenience over a resource handle over the primitives

`capture/` uses `try (var store = CapturedStore.of(tmp, FIXTURE))`; `derive/` uses
`withCapturedStore(sdl, dsl -> {...})`. Ship both, layered over the exposed primitives, with the
closure form as the one most tests reach for.

The goal here is good common tools, not a single sanctioned way to open a store. The closure form is
genuinely the nicer call site for the common case, which is most of the eight classes: hand it SDL,
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

Nothing asserts on the literal `fixture.graphqls`, in either module, so the rename is free. The
sweeps then stop minting subdirectories, which is a small hand-rolled step deleted rather than
moved.

One line of the eight copies has to survive the merge: each of them calls `Files.createDirectories`
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

An earlier draft put these in a separate `SeededStore` type, to keep the reach for an
unreachable-by-production store state visible at the call site. That is the wrong trade under the
gathering rule: a second type is a second thing to fail to discover, and seeding is exactly the
capability a test author is most likely to hand-roll if they do not find it. Seeding lives on
`CapturedStore` with everything else.

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

`FactCaptureAgreementTest`'s class javadoc cites seven of the migrated classes by name as the
registered per-view agreement anchors: `ColumnMatchClaimTest`, `ReferenceStepTargetTest`,
`FieldColumnTableTest`, `ClassMemberSlotTest`, `DemandShadowTest`, `InputOccurrenceShadowTest` and
`AuthoredClaimConflictsTest`, each as a fully qualified `{@code}` name.
`docs/architecture/explanation/fact-model.adoc` cites four of them the same way
(`AuthoredClaimConflictsTest`, `ColumnMatchClaimTest`, `DemandShadowTest`,
`InputOccurrenceShadowTest`) plus `DiagnosticFactsTest` on the diagnostics stratum. Because the
citations are `{@code}` and not `{@link}`, the javadoc reference gate will not catch a rename or move,
so any class rename in this item must update both surfaces by hand.

## Tests

This item changes test-support code only, so its acceptance is the existing suite, across both
modules: the eight migrated classes, the nine `capture/` classes that already read `CapturedStore`,
and the 28 `graphitron-lsp` classes that read `StoreFixture`, all passing with their assertion
content unchanged. An assertion that has to change to accommodate the shared harness is the signal
that an axis was load-bearing after all and must stay expressible, not that the assertion should be
relaxed. The node-inference axis is the one to watch here.

The LSP half carries a second, sharper acceptance, because its call sites are not being migrated:
`StoreFixture`'s own consumers should be untouched by this item. A diff that edits LSP test classes
is reporting that the shared handle cannot express a shape the LSP needs. Treat that as the finding
and widen the handle, rather than adjusting the test to suit it.

`mvn install -Plocal-db` is the gate, and it has to be the full reactor build here rather than a
`-pl :graphitron` run, since the LSP half is downstream of the module the handle moves in. The inner
loop for the first half stays `mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution`; for
the second, `mvn test -pl :graphitron-lsp -am -Plocal-db`.

No new test tier and no new meta-test. A guard forbidding future hand-rolled copies was considered
and rejected: the failure mode is a test author not knowing the utility exists, and a public handle
sitting next to `TestSchemaHelper` in a package they already import addresses that directly. A
grep-based ratchet would add a maintenance surface to enforce what discoverability already buys.

The LSP author reaches the handle by a different route, and it is worth being honest that it is a
longer one: they meet `StoreFixture` first, and the shared handle only behind it. That is the right
layering anyway, since what an LSP test wants is usually the reader-side surface `StoreFixture`
keeps. The discoverability this item buys them is narrower than for a `graphitron` author: not
"here is the utility", but "the shape you need is one delegation away, and adding it there serves
both modules." `StoreFixture`'s class javadoc should say so, which is the same carrier requirement
the gathering rule takes on `CapturedStore`.

## Out of scope

* Changing what any of the nine classes asserts, and editing `StoreFixture`'s 28 call sites at all.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
* Pruning the factory set to some minimal basis. Per the gathering rule, arriving with more factories
  than strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be
  taken once the whole set is visible in one file.
* Moving the post-capture writers `withBuildWarnings`, `withJavaSource` and `refreshJavaSources` off
  `StoreFixture`, and the consolidation with `capture/JavaSourceFactsTest`'s hand-rolled driver of
  the same writer. Named as the follow-up in "The cross-module copy" above; deferred because the LSP
  item is still growing that surface.
* Touching `graphitron-lsp`'s main-source dependency on `graphitron`. This item adds a test-scoped
  edge on an artifact the module already depends on, and leaves the shedding direction the pom
  records exactly where it is.
