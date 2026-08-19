---
id: R736
title: "The classifier trace goes silent mid-fork: resetForTesting(null) in @AfterEach truncates leaf-coverage by test ordering"
status: Backlog
bucket: bug
priority: 5
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

