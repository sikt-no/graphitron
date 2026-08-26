---
id: R736
title: "The classifier trace goes silent mid-fork: resetForTesting(null) in @AfterEach truncates leaf-coverage by test ordering"
status: Spec
bucket: bug
priority: 4
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# The classifier trace goes silent mid-fork: resetForTesting(null) in @AfterEach truncates leaf-coverage by test ordering

`ClassificationTrace` binds one process-global writer, and `resetForTesting(null)` sets
`writerInitialised = true` with `writer = null`, which is documented as disabling tracing for the
rest of the JVM. Two test classes call it from teardown: `ClassificationTraceTest.disableTracing`
(`@AfterEach`) and `SingleWalkClassificationOrderTest`. Surefire runs a module's classes in one
reused fork, so from the moment the first of those two finishes, every test class Surefire happens to
schedule afterwards emits nothing. The `leaf-coverage` profile is active on every default and CI
build and the records feed `LeafCoverageReport`, whose `roadmap/inference-axis-coverage.adoc` output
reports which leaves were observed and by which test classes; a leaf exercised only by a class
ordered after the teardown reads as unobserved. Nothing fails, so the report has been reading a
silently truncated input.

Measured on `mvn test -pl :graphitron -Plocal-db` at the reviewed revision: 17,476 records for 3,653
green tests. The same module run with class-level parallelism at 4 threads produced 16,933 records
from the same 3,653 tests, and the per-class diff is the tell. Records disappear where the writer
went null mid-class (`DeliveryFactPinTest` 1,187 to 482, `LintSuppressionPipelineTest` 67 to 7) and
appear for classes that emit nothing at all sequentially (`SchemaReachabilityTest` 0 to 116,
`MultiTablePolymorphicParentHoldsFkPipelineTest` 0 to 60, `ScalarReferenceClosurePipelineTest` 0 to
26). The parallel figures are not the defect; they are what
makes the sequential silence visible, because reordering changes which classes fall behind the
teardown.

The fix wants a writer lifecycle that a test can rebind and restore without a global one-way
disable: the two tests need their own binding for the duration of the test and the module's binding
back afterwards, which is the same shape as any other global-resource test seam here. Whatever the
mechanism, the invariant this item should leave behind is one an enforcer can hold: the record count
and per-class composition of a module's trace does not depend on test ordering. A meta-test that
runs a small set of classes in two orders and compares the emitted composition would pin it; today
nothing does, which is why the truncation survived.

Two consumers to check while fixing. `LeafCoverageReport`'s aggregates (trace count, distinct
fixtures, highest tier observed, exercising test classes) all change when composition changes, so
`roadmap/inference-axis-coverage.adoc` should be regenerated once the input is complete and the diff
read as a correction rather than as new coverage. And any item that turns on class-level test
parallelism shifts composition again for the same reason, so the two want to land in a known order
rather than concurrently.

---

## Decision: the binding becomes thread-scoped and self-restoring

The spec pass asked one question first, because a no would have discarded the item: does this defect
die with the leaf zoo? It does not, and the reason the item is worth more than its priority suggests
is that the report it corrupts is published.

### The defect is in the writer lifecycle, not in leaf keying

`ClassificationTrace` is emitted from `TypeRegistry.trace` and the `FieldRegistry` classify paths, on
every classify / enrich / demote / synthesize. The sealed leaf model that supplies the `leaf` field is
the transitional producer surface of the strangler migration described in
`docs/architecture/explanation/fact-model.adoc`, drained rather than switched off, so the walk keeps
emitting for the whole migration window.

What *is* transitional is the report's schema. `LeafCoverageReport` renders one row per sealed leaf
over a fixed `HIERARCHIES` list, and R333 already states that the coverage net "needs restating once
leaves are not the unit". So the row key is on borrowed time, while the walk under it keeps emitting.
Whether a re-keyed successor still reads this stream at all is a separate question, and the fact store
has since made it a live one; the next section answers it.

### The truncated report is the published one

The pom's `leaf-coverage` profile comment says the traces are "produced and discarded on CI" and that
"the only consumer is the manual `roadmap-tool leaf-coverage` regen". That was true when it was
written and is now false: the CI publish chain shipped, and the comment's forward reference points at
two item ids that no longer exist. In `.github/workflows/rewrite-build.yml` today, the `build` job's
`Regenerate leaf-coverage report` step runs the regen against the in-workspace traces on every trunk
push and uploads `roadmap/inference-axis-coverage.adoc` as the `inference-axis-coverage` artifact;
`docs-build` downloads that artifact over the committed file before rendering; `docs-deploy` publishes
the result. Every trunk push therefore publishes a coverage table whose rows depend on the order
Surefire happened to schedule test classes in.

### The damage is verifiable today, with no build

Three test classes that classify appear **zero times** in the committed report: grep
`roadmap/inference-axis-coverage.adoc` for `SchemaReachabilityTest`,
`MultiTablePolymorphicParentHoldsFkPipelineTest`, `ScalarReferenceClosurePipelineTest`. Under the
reordered run measured above they contribute 116, 60 and 26 records. They are absent from the
published table because they are scheduled behind the teardown, not because they exercise nothing.

That also sharpens which columns rot, and it is not primarily the trace count. Only one row of 71
(`PivotSlotField`) reads zero traces today, so the "leaf looks unexercised" failure is narrow. The
columns actually carrying the truncation are `Tests`, `Fixtures`, the highest-tier-observed value and
the `cross-cutting` flag: a leaf whose only non-cross-cutting exerciser is scheduled late reads as
covered at a lower tier, or as cross-cutting-only, when it is neither.

## What the fact store takes over, and what it does not

The trace and the `@classified` corpus were both designed before the fact store existed, so the
question is not only whether the defect outlives the leaf zoo but whether the *trace* does. Partly it
does not, and this item is scoped so that the part with a store successor is corrected rather than
deepened.

**The payload has a store home already.** The classification walk writes into the store as the
`walk_` family: `walk_claim_domain_type` and `walk_claim_domain_field` carry the coordinates the walk
registered, `walk_type_backing_class` what it bound each type to, all keyed on `(graph_name,
type_name [, field_name])` with a `store_graph` foreign key and a removal criterion stated in the
family header. Rejections have `rejection_` and `diagnostic_` homes. So the trace's `(coordinate,
verdict, source, rejection)` columns are a second and weaker transcription of data that now has a
keyed, constraint-checked home. The tell is `LeafCoverageReport`'s input list: JSONL, plus a
source-parsed sealed-leaf inventory, plus a roadmap-mention grep, joined in DuckDB. Not one of the
three is the model.

**The test axis has no store home and should not get one.** `test` and `tier` are facts about the
test suite, not about a consumer's schema. Carrying them in the same record as the verdict is the leaf
zoo's own mistake at the observability layer, two independent axes welded into one row. Under the fact
reading they are two relations joined on the coordinate, and only one of them is the store's subject.

**Suite-wide accumulation is the other half that does not transfer.** `CapturedStore` boots from
`FactStores.inMemory()` and `FactCapture.run` drops the store with the pass, so a test store is
per-test. "Coverage becomes a query over the store" therefore needs the suite to accumulate
somewhere, and that accumulator is the process-global shared resource this item is about. A shared H2
file reimports the hazard with worse ergonomics: R732's H2 ruling records that a global temporary
table shares its rows across every attached session, which is the wrong default for a store the LSP,
the MCP server and concurrent module builds all attach to. The trace's transport, per-module and
append-only over `O_APPEND` and unified at read time by a glob, is well matched to the aggregation
problem and is the part worth keeping.

So the reading to hand the reviewer: correct the writer lifecycle, keep the transport, expect the
`leaf` column and the report's row key to drain once the coverage net is restated, and expect the
test-attribution axis to outlive both. This item changes a lifecycle and adds no column, no consumer
and no new record kind, which is what keeps it from reading as an investment in the trace's
permanence.

## The mechanism

Two properties are wanted, and the second one is the half the Backlog body did not name:

1. **Restore.** A test's binding must not outlive the test's own scope.
2. **Scope.** A test's binding must not be visible to any other thread.

Restore alone leaves a diversion window: `ClassificationTraceTest.enableTracing` and
`SingleWalkClassificationOrderTest.enableTracing` rebind the *process* writer to a `@TempDir` path,
so for the duration of those classes every other thread's records land in a temp file that is deleted
afterwards. Sequentially that costs only those two classes' own records, which is what they want. Under
class-level parallelism it silently steals other classes' records, which is a second flavour of the
same bug.

Both properties fall out of one change: make the test binding a `ThreadLocal` override layered over the
module binding, rather than a rebind of it.

* `writer` and `writerInitialised` keep their current meaning as the **module** binding, opened lazily
  from the `graphitron.classification.trace` property and never closed by a test.
* A `ThreadLocal` override sits beside the existing `CURRENT` context `ThreadLocal` (same class, same
  shape, so this is a pattern the file already carries), holding either a test writer or an explicit
  disabled marker.
* `emit` resolves the override first and falls back to the module binding.
* The test entry point is scoped and closeable: a `bindForTesting(Path)` returning an `AutoCloseable`
  whose `close` closes the test writer and clears the override, plus a disabled-scope form for the
  no-op assertions. Naming is the implementer's call; the shape is not.
* `resetForTesting` is deleted. With it goes the only code path that sets `writerInitialised = true`
  while leaving `writer = null`, so the one-way latch cannot be reached rather than merely being
  avoided by convention.

The invariant to leave behind, stated so an enforcer can hold it: **no test's trace binding is visible
outside the scope that established it, in time or across threads.** Ordering-independence of a module's
record composition follows from that; it is the consequence, not the primitive, which is why the tests
below pin the primitive.

### What this does for R732 slice 3

Slice 3 plans `@Isolated` on the two `resetForTesting` callers to stop the `Stream closed` race, which
would be the tree's first use of that annotation. A thread-scoped override removes the race outright,
because no test closes the module writer, and removes the diversion, because other threads keep
writing to the module binding. So slice 3 probably needs no `@Isolated` at all.

**Verify that before claiming it.** A thread-scoped override captures only classification that runs on
the test's own thread. `SingleWalkClassificationOrderTest` builds a schema through
`TestSchemaHelper.buildSchema` and then reads its own temp file, so the implementer must confirm the
classification walk is synchronous on the calling thread. If any part of it runs off-thread, that class
keeps `@Isolated` and this item says so in its commit message rather than leaving slice 3 to discover
it.

## Implementation

* `ClassificationTrace`: add the thread-scoped override and the closeable binding API; make `emit` and
  `isEnabled` consult the override before the module binding; delete `resetForTesting` and the
  "disabling tracing for the rest of the JVM" contract in its javadoc.
* `ClassificationTraceTest`: hold the scope in the existing `@BeforeEach` / `@AfterEach` pair. The two
  tests that toggle mid-body (`emit_isNoOp_whenTracingDisabled`,
  `isEnabled_reflectsCurrentBindingState`) become nested scopes rather than latch flips, which is also
  the clearest demonstration of the new contract.
* `SingleWalkClassificationOrderTest`: same substitution; its assertions are unchanged.
* Root `pom.xml`, `leaf-coverage` profile comment: correct the stale claim. It currently tells the next
  reader that the traces are discarded on CI and cites two deleted item ids for a chain that has since
  shipped. This is one paragraph, and it is in scope because that comment is what makes the defect look
  harmless.
* Regenerate `roadmap/inference-axis-coverage.adoc` from a complete run and commit it. CI overwrites
  this file for trunk deploys, but the committed copy is what local doc builds and PR-preview renders
  read, so committing the corrected version is what fixes the table a reviewer sees. Say in the commit
  message that the diff is truncation repair, not coverage drift; the columns move for 60-odd rows and
  the next reader will otherwise read it as new coverage.

## Tests

**Rejected: the suite-level two-orders meta-test the Backlog body proposed.** Nothing in the reactor
depends on `junit-platform-launcher` or `junit-platform-testkit`, and no test uses `LauncherFactory` or
`EngineTestKit`, so it needs a new test dependency against the pinned-version rule in `CLAUDE.md`. It
also nests a JUnit engine inside the suite whose process-global state is the subject, and it pins the
symptom rather than the cause. Three cheaper tests pin the cause, and together they imply the
suite-level property.

1. **Restore.** After a scoped binding closes, a subsequent `emit` reaches the module binding again, and
   `isEnabled` reports true again after a disabled scope closes. Extends `ClassificationTraceTest`. This
   is the assertion whose absence let the latch survive review in the first place.
2. **Scope.** With a test binding active, an `emit` from a second thread lands in the module file and
   not in the test's file. This is the half R732 slice 3 leans on, so it is worth its own test rather
   than being implied.
3. **Structural guard**, in the `GuardScope` family alongside `RoadmapReferenceGuardTest`,
   `RetiredVocabularyGuardTest` and `StoreFixtureGuardTest`: fail if any test source names a
   `ClassificationTrace` binding entry point outside a try-with-resources or a paired lifecycle field.
   The failure mode being guarded is a future test reintroducing an unrestored binding, which no
   behavioural test can catch. Assert a nonzero scanned-file floor the way `RoadmapReferenceGuardTest`
   does, so a walk that reaches nothing fails instead of passing vacuously.

## Rejected: retire the report instead

The alternative considered was to leave the writer alone and fold the leaf-keyed report's retirement
into whichever item restates the coverage net. Rejected on timing: that restatement is not scheduled
(R333 is Ready and names the restatement as filed separately), the report is published from CI on every
trunk push in the meantime, and the fix here is a few dozen lines. Retiring a published report to avoid
a small seam change is the worst ratio of the three options. What the alternative was right about is
recorded above: this item deliberately does not touch the row key.

## Retired vocabulary

* `ClassificationTrace.resetForTesting`
* "disabling tracing for the rest of the JVM", and any prose describing the trace binding as a
  process-global one-way switch

## Not in scope

* Re-keying the report off sealed leaves, and moving any of the trace's payload onto the `walk_`
  family or a successor relation. R333's restatement owns both.
* The `@classified` corpus directives. Their grain has already migrated: the operation-member,
  synthesis and launcher arms are all stated against relation rows rather than against a welded leaf
  (see the corpus prelude document), and the leaf-keyed residue is `@classifiedType(as:
  TypeVerdict!)`, whose enum is drift-checked against the live `GraphitronType` leaf set. That residue
  and the corpus-side coverage net are one question, and it belongs to R333's restatement.
* R133's flip of the `leaf-coverage` profile to opt-in. This item corrects that profile's comment; it
  does not touch its activation.
* Turning on class-level test parallelism. That is R732 slice 3, which this item wants to precede.
* Widening what the trace records, and giving the store a test-attribution relation. The field set is
  unchanged and the store gains nothing here.

