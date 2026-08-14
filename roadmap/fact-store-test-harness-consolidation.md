---
id: R680
title: "Consolidate the hand-rolled fact-store test harnesses onto one shared utility"
status: Spec
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
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

**But the duplication is the symptom, not the item.** The decision being re-made at nine sites is
*which capture inputs the store under assertion is built from*, and the sites disagree without
anything saying why. Past the 5-arg `FactCapture.capture` default (which fixes `jooq = null`,
`extensions = List.of()`, `nodes = new NodeDeclaration(null)`), four shapes are live in the tree:

| Shape | Sites |
|---|---|
| bare | `AuthoredClaimConflictsTest.capture`, `DiagnosticFactsTest`, `CapturedStore.of` |
| catalog, node inference off | `ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest` |
| catalog, node inference on | `DemandShadowTest`, `InputOccurrenceShadowTest` |
| catalog, node inference off, real classpath census | `ClassMemberSlotTest` |

That split is not cosmetic. `NodeDeclaration` changes what capture writes, and `DemandShadowTest`'s
own sweep comment says its equality is "also the enforcer for the node-inference seed's
over-approximation." Whether the column-match and reference-step views are node-inference-sensitive
is currently unstated and unasserted. A consolidation that silently picks one default answers that
question by accident; one that carries a boolean flag defers it forever. The item's job is to make
the shape a named choice, and the deduplication follows from that.

The fourth row is a different kind of axis from the first three, and saying so is part of the job.
The `extensions` argument the 5-arg default fixes at `List.of()` is not a shape a factory should own:
`ClassMemberSlotTest` hands capture the output of a real `ClasspathScanner.scan` filtered to its own
three fixture classes, which is per-fixture data, as the SDL string is. So it is a caller of the
handle rather than a fourth factory, and the axis it exercises is served by the exposed primitives
(below) rather than by a `with(...)` arm. If a second census-carrying test ever appears, the
gathering rule below covers minting an arm then.

## Implementation

### Promote the store handle, keep the existing split of responsibility

Widen `CapturedStore` from package-private and move it beside the shared test support in
`no.sikt.graphitron.rewrite`, which already hosts `TestSchemaHelper`, `TestFixtures` and the
`*RenderTestSupport` classes. That package is also tier-neutral, which matters: the incoming
consumers are all `@PipelineTier`, while the nine that already read the handle straddle both tiers
(`FactCaptureAgreementTest`, `TaggedCaptureStampTest` and `WarmStartRefreshTest` are `@PipelineTier`,
the other six `@UnitTier`). A home that reads as belonging to one tier's family invites the next
reader to infer a tier rule that does not exist, and the mixed readership is already the status quo
rather than something this item introduces.

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

`CapturedStore` is the home for fact-store test utilities. A test that needs a store shape no
existing factory produces adds a factory *there*, rather than hand-rolling a helper in its own class.
That is the rule this item establishes, and it outranks the individual shape decisions below.

The failure mode being designed against is fragmentation, not accretion. Eight private copies that
have quietly diverged is the expensive state, and it is expensive because nothing points a new test
author at the existing answer. A `CapturedStore` carrying more factories than any one reader needs is
the cheap state: the factories are in one file, visible together, and consolidating two that turned
out to be the same is a mechanical afternoon. Growth is expected and fine. If the set gets unwieldy,
clean it then, with the whole set in view, which is exactly the vantage point the current eight
copies deny.

So the shape set below is a starting point, not a closed taxonomy, and a later item adding a factory
is the design working rather than failing.

### Name the shapes; do not flag them

Give the handle named factories, one per capture shape in the first three rows above, rather than
nullable arguments or a `boolean`. Named entry points are what make the set legible enough to prune
later; a growing pile of flags is not. Each factory carries a one-line javadoc note saying what its
shape carries that its sibling cannot. Writing those notes is the work: if the note for the
node-inference-off shape turns out to read "nothing, these tests just never needed nodes," that is
the finding, and the set collapses to two.
Resolving the `new NodeDeclaration(null)` versus `TestSchemaHelper.nodeDeclaration()` split is in
scope and must not be preserved silently on the grounds that it is what the copies did.

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

Migrating without lifting the name to a parameter would either leave the sweeps hand-rolled, which
is where the duplication is largest, or flatten the partition they assert on.

The directory cannot be dropped either, and for a reason worth stating because it fails silently.
`RewriteSchemaLoader.load` takes `Collection<SchemaSource.File>`, the file arm specifically, so SDL
held as a string literal has to be materialized before anything can parse it. The path it is written
to then *is* its identity downstream: `SchemaSource.File.sourceName()` is the absolute normalized
path, which is the string handed to the parser, the string graphql-java echoes back as
`SourceLocation.getSourceName()`, and the key `SchemaInputAttribution`'s map and capture's stamp
lookup are both read on. Since `fixtureFile` hardcodes the name `fixture.graphqls`, the directory is
the whole of a fixture's identity: one directory, one fixture, one graph. A handle that took a graph
name but reused one directory would have each capture overwrite the previous fixture with no error,
because the load would still succeed against whatever text landed there last. So the entry points
take a `(name, directory)` pair, or mint per-graph subdirectories themselves the way the sweeps
already do by hand (`Files.createDirectories(tmp.resolve(example.id()))`).

One line of the eight copies has to survive the merge: each of them calls `Files.createDirectories`
before writing and `CapturedStore.write` does not, because every `capture/` caller hands it a
`@TempDir` that already exists. Every incoming caller writes into a directory it named itself, so the
shared `write` needs that line or the sweeps and the sibling-partition negatives fail on the first
subdirectory.

### Make the registry source an explicit arm

All seven `derive/` helpers feed capture a bare `RewriteSchemaLoader.load` registry, as does
`DiagnosticFactsTest` in each of its own capture calls. Production capture reads the attribution
pipeline's pre-synthesis registry, and `CapturedStore.ofPipeline` already exists for that, with
javadoc stating the hazard: a bare parse lets capture's macro expansion mint
what the rewrite has already put there in the pipeline, so the store agrees with the model for the
wrong reason.

Today that choice is invisible because it is made identically eight times by accident. After
consolidation it is one line, and whichever arm the factory defaults to becomes the pipeline tier's
implicit claim about which registry these views are derived from. No current derive fixture is
federation-shaped, so nothing is broken; name it as an explicit arm now while the decision is cheap,
rather than leaving a default nobody revisits when the first federation-shaped derive fixture lands.

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

This item changes test-support code only, so its acceptance is the existing suite: the eight migrated
classes and the nine `capture/` classes that already read `CapturedStore` pass with their assertion
content unchanged. An assertion that has to change to accommodate the shared harness is the signal
that an axis was load-bearing after all and must stay expressible, not that the assertion should be
relaxed. The node-inference axis is the one to watch here.

`mvn install -Plocal-db` is the gate; the inner loop is
`mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution`.

No new test tier and no new meta-test. A guard forbidding future hand-rolled copies was considered
and rejected: the failure mode is a test author not knowing the utility exists, and a public handle
sitting next to `TestSchemaHelper` in a package they already import addresses that directly. A
grep-based ratchet would add a maintenance surface to enforce what discoverability already buys.

## Out of scope

* Changing what any of the eight classes asserts.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
* Pruning the factory set to some minimal basis. Per the gathering rule, arriving with more factories
  than strictly necessary is the acceptable outcome; consolidating them is a later, cheap pass to be
  taken once the whole set is visible in one file.
