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

Seven `@PipelineTier` test classes each hand-roll their own harness for the same thing: open a fact
store, capture one SDL fixture into it, run assertions against the resulting `DSLContext`. The
helper is called `withCapturedStore` in six of them and is close to verbatim across all six:

* `derive/ColumnMatchClaimTest`, `derive/DemandShadowTest`, `derive/InputOccurrenceShadowTest`,
  `derive/ReferenceStepTargetTest`, `derive/AuthoredClaimConflictsTest`
* `derive/FieldColumnTableTest`, which has already hit the variation problem internally and carries a
  `withCapturedStoreAndClaimDomain` sibling over a private boolean-flag core
* `diagnostics/DiagnosticFactsTest.withStore`, the same shape with the capture step removed

Alongside them, `private static Path write(Path directory, String sdl)` (writing `fixture.graphqls`)
is duplicated verbatim in eight test classes, and each of the seven declares its own
`private static final String GRAPH = "<OwnClassName>"`.

The utility these classes want **already exists**. `capture/CapturedStore` is an `AutoCloseable`
handle offering `of(Path, String)`, `ofPipeline(Path, String)`, `registryOf`, `attributionOf` and
`fixtureFile`, and roughly six test classes inside `capture/` use it happily. It is package-private,
so `derive/` and `diagnostics/` cannot see it at all. Each of them reinvented it independently.

**But the duplication is the symptom, not the item.** The decision being re-made at eight sites is
*which capture inputs the store under assertion is built from*, and the sites disagree without
anything saying why. Past the 5-arg `FactCapture.capture` default (which fixes `jooq = null`,
`extensions = List.of()`, `nodes = new NodeDeclaration(null)`), three shapes are live in the tree:

| Shape | Sites |
|---|---|
| bare | `AuthoredClaimConflictsTest.capture`, `DiagnosticFactsTest`, `CapturedStore.of` |
| catalog, node inference off | `ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest` |
| catalog, node inference on | `DemandShadowTest`, `InputOccurrenceShadowTest` |

That split is not cosmetic. `NodeDeclaration` changes what capture writes, and `DemandShadowTest`'s
own sweep comment says its equality is "also the enforcer for the node-inference seed's
over-approximation." Whether the column-match and reference-step views are node-inference-sensitive
is currently unstated and unasserted. A consolidation that silently picks one default answers that
question by accident; one that carries a boolean flag defers it forever. The item's job is to make
the shape a named, closed choice, and the deduplication follows from that.

## Implementation

### Promote the store handle, keep the existing split of responsibility

Widen `CapturedStore` from package-private and move it beside the shared test support in
`no.sikt.graphitron.rewrite`, which already hosts `TestSchemaHelper`, `TestFixtures` and the
`*RenderTestSupport` classes. That package is also tier-neutral, which matters: the new consumers
are `@PipelineTier` while `capture/SourceGraphScopingTest` is `@UnitTier`, and a home that reads as
belonging to one tier's family invites the next reader to infer a tier rule that does not exist.

Do **not** let this become a third shared-test home. `TestSchemaHelper` already owns the parse-side
primitives (`attribution`, `nodeDeclaration`, `buildSchema`) and the derive tests already call it.
The store handle owns the fixture file and the store's lifetime. Keep that split; the move must not
duplicate `TestSchemaHelper.attribution` under a second name.

Expose `registryOf` / `attributionOf` / `fixtureFile` publicly alongside the factories. The corpus
sweeps drive `FactCapture.capture` themselves, per example, and need the primitives without a
factory arm for every combination.

### Name the three shapes; do not flag them

Give the handle a closed set of named factories, one per shape above, rather than nullable arguments
or a `boolean`. Each factory carries a one-line javadoc note saying what its shape carries that its
sibling cannot. Writing those notes is the work: if the note for the middle shape turns out to read
"nothing, these tests just never needed nodes," that is the finding, and the set collapses to two.
Resolving the `new NodeDeclaration(null)` versus `TestSchemaHelper.nodeDeclaration()` split is in
scope and must not be preserved silently on the grounds that it is what the copies did.

### Resource handle only, no closure wrapper

`capture/` uses `try (var store = CapturedStore.of(tmp, FIXTURE))`; `derive/` uses
`withCapturedStore(sdl, dsl -> {...})`. Carrying both was considered and is rejected: they are two
spellings of one thing that agree exactly until one changes, and the callback form is the one that
cannot grow.

Every axis on the table is really "and then also write X after capture" (`ClaimDomainRows.write`, a
second graph's capture, the rejection and warning facts in `diagnostics/`). Against an open store
that is a call inside the `try` block and costs the utility nothing. Against a callback it is a new
parameter or a new overload every time, which is exactly how `FieldColumnTableTest` acquired its
boolean flag. The tree already shows the end state: `AuthoredClaimConflictsTest.detectionAgainstWalk`
needs capture, then `ClaimDomainRows.write`, then `AuthoredClaimConflicts.detect`, could not express
that through its own `withCapturedStore`, and re-opens the store by hand instead;
`siblingGraphConflictsDoNotLeak` does the same inline.

So: one resource handle. `withCapturedStoreAndClaimDomain` becomes `ClaimDomainRows.write(...)` at
the call site, and the two hand-rolled re-opens collapse onto the handle rather than staying as
exceptions to it.

### The graph identity is caller-supplied

`CapturedStore.graph(Path)` hardcodes the graph name to the literal `"CapturedStore"`. That is the
one thing that makes it unusable for the current consumers, and it must become a parameter.

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

### Make the registry source an explicit arm

All seven derive helpers feed capture a bare `RewriteSchemaLoader.load` registry. Production capture
reads the attribution pipeline's pre-synthesis registry, and `CapturedStore.ofPipeline` already
exists for that, with javadoc stating the hazard: a bare parse lets capture's macro expansion mint
what the rewrite has already put there in the pipeline, so the store agrees with the model for the
wrong reason.

Today that choice is invisible because it is made identically seven times by accident. After
consolidation it is one line, and whichever arm the factory defaults to becomes the pipeline tier's
implicit claim about which registry these views are derived from. No current derive fixture is
federation-shaped, so nothing is broken; name it as an explicit arm now while the decision is cheap,
rather than leaving a default nobody revisits when the first federation-shaped derive fixture lands.

### Keep the seeding seam separate and differently named

`ColumnMatchClaimTest` carries `withSeededStore` plus `seedGraph` / `seedSource` / `seedField` /
`seedTable` / `seedSchema` / `seedColumn`; `ReferenceStepTargetTest` carries `withCollidingKeySeed`
plus `seedTable` / `seedRootType` / `seedStep`. These insert store rows directly and bypass capture,
because they construct states capture cannot reach. The governing rule is already written twice in
class javadoc (`ReferenceStepTargetTest`: "a fixture is free to seed a chain the catalog cannot
connect, and the case then documents behaviour no build can produce"; `FieldColumnTableTest`
likewise), and each seeded case carries its own escape note.

Fold these onto the capture handle and `store.seedTable(...)` reads as the same species as
`store.capture(...)`, while the per-case justification becomes a shared paragraph nobody re-reads.
The failure mode is a test pinning a store state production cannot reach, with no signal at the call
site. So: two seams, differently named (`CapturedStore` versus a `SeededStore`), so the reach for
the unreachable-state one is greppable, and the "only reachable by seeding" note becomes a
per-method requirement rather than a class-level paragraph.

The consolidation inside the seeded seam is deliberately narrow. `ColumnMatchClaimTest` and
`ReferenceStepTargetTest` each spell a full minimal `graphql_type` / `graphql_field` /
`graphql_type_declaration` row by hand, differently. Those are hand-written twins of what capture
writes, and a DDL change adding a non-null column should break one place rather than two. Share
those base-row spellings; do not promote seeding to a peer of capture.

### Update the agreement anchors

`ColumnMatchClaimTest`, `ReferenceStepTargetTest`, `FieldColumnTableTest`, `DemandShadowTest`,
`InputOccurrenceShadowTest` and `AuthoredClaimConflictsTest` are cited by name in
`FactCaptureAgreementTest`'s class javadoc and in `docs/architecture/explanation/fact-model.adoc` as
the registered per-view agreement anchors. Those citations are `{@code}`, not `{@link}`, so the
javadoc reference gate will not catch a rename or move. Any class rename in this item must update
both surfaces by hand.

## Tests

This item changes test-support code only, so its acceptance is the existing suite: the seven
migrated classes and the six `capture/` classes pass with their assertion content unchanged. An
assertion that has to change to accommodate the shared harness is the signal that an axis was
load-bearing after all and must stay expressible, not that the assertion should be relaxed. The
node-inference axis is the one to watch here.

`mvn install -Plocal-db` is the gate; the inner loop is
`mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution`.

No new test tier and no new meta-test. A guard forbidding future hand-rolled copies was considered
and rejected: the failure mode is a test author not knowing the utility exists, and a public handle
sitting next to `TestSchemaHelper` in a package they already import addresses that directly. A
grep-based ratchet would add a maintenance surface to enforce what discoverability already buys.

## Out of scope

* Changing what any of the seven classes asserts.
* Promoting the direct row-seeding helpers to a general-purpose store-construction API.
* Reshaping `FactCapture`'s own overload set. The 5-arg overload is a reasonable public default and
  this item consumes it.
