---
id: R860
title: "The LSP trace test rebinds JVM-global state while the module's other classes run beside it"
status: In Review
bucket: testing
priority: 3
theme: lsp
depends-on: []
created: 2026-08-27
last-updated: 2026-08-28
---

# The LSP trace test rebinds JVM-global state while the module's other classes run beside it

`LspTraceTest.doubleCloseIsIgnored` opens one span, closes it twice, and asserts that the captured
output holds exactly one line containing `lsp-trace <`. The count ranges over every close line in the
sink, and the sink is not the test's own: `LspTrace` holds its enabled flag and its sink in static
fields, and the test's `redirectSink` rebinds that global for the whole JVM. Observed once, in a full
`mvn install -Plocal-db` on 2026-08-27, as `expected: 1L but was: 2L` at `LspTraceTest.java:142`; the
class passes in isolation and the module passes on a re-run.

The Backlog draft recorded that `graphitron-lsp` "declares no parallel execution at all today" and
reasoned from there. The module declares none and runs its test classes four at a time regardless,
because the settings arrive on its test classpath inside `graphitron-model`'s test-jar. R764 owns that
channel and measured what it is worth; this item takes the concurrency as a fact of the module and
makes the trace seam's tests hold under it. The split follows the one already in the tree between
R764 and the since-shipped R832: the channel is one item, a test that is not thread-safe under it is
another.

What changes when this lands: the two classes in `graphitron-lsp` that read and write `LspTrace`'s
process-global state stop being able to break each other or to be broken by the other 75 classes in
the module, and the one assertion that was broken this way says what it saw when it fails again.

## Vocabulary

**Class-level concurrency** is the arm this reactor's parallel modules choose:
`junit.jupiter.execution.parallel.mode.classes.default=concurrent` with
`junit.jupiter.execution.parallel.mode.default=same_thread`, so test classes overlap while the methods
inside one class do not. A class is then the unit that owns a fixture, which is what makes the setting
safe wherever state is per-class and unsafe wherever it is per-JVM.

**Process-global state** is state a fixture boundary cannot contain: a static field, a system
property, a rebound stream. `@Isolated` is JUnit's answer for a class that owns some: the annotated
class runs while nothing else does. `graphitron` carries it today on `ClassificationTraceTest` and
`SingleWalkClassificationOrderTest`, the two classes that rebind `ClassificationTrace`'s writer.

## That the module really is concurrent, measured

`mvn test -pl graphitron-lsp -Plocal-db` on this tree, 646 tests in 76 classes, against the same
command with `-Djunit.jupiter.execution.parallel.enabled=false`. A JVM system property outranks a
`junit-platform.properties` on the classpath in JUnit's precedence order, so the second arm is the
module as its own pom and test resources describe it. One pair, this session:

| Arm | Module total | Sum of the per-class elapsed times |
|---|---|---|
| As it runs today | 54.9 s | 591.1 s |
| `parallel.enabled=false` | 99.0 s | 96.6 s |

The second column is the proof rather than the first. A sum of per-class wall clocks that lands within
a couple of seconds of the module total means no two classes overlapped; a sum an order of magnitude
above it means they overlapped heavily. So the module's classes do run concurrently, and the
concurrency is worth 44 s of a 99 s test phase, which is why `@Isolated` below is load-bearing rather
than decorative: the likely settlement of R764 is that `graphitron-lsp` keeps this and says so
locally.

The system-property arm switch is also worth recording as a technique. R764 measured by stripping the
entry out of the installed test-jar, and `graphitron-model`'s own file describes measuring by deleting
`target/test-classes/junit-platform.properties` and warns that a stale copy makes both arms parallel.
The property has neither failure mode.

## Why a leaked background thread is not the mechanism

The draft proposed that a sibling class left live LSP machinery running (a diagnostics drain, a
debounce executor) into the window where this class turns tracing on. That cannot produce the line by
itself, for a reason in the seam's design: `LspTrace.span` decides at open time and returns the shared
no-op while the seam is off. A thread that opened its spans during another class's run is holding
no-ops and emits nothing later, however long it outlives that class. A foreign close line requires a
span **opened** while `enabled` is true, which means a thread doing new work inside this class's
window.

Under class-level concurrency that is the ordinary case. Any of the other classes in the module that
parses a buffer, mutates a workspace or computes diagnostics opens spans through
`WorkspaceFile.applyEdit`, `Workspace.mutate` or `Diagnostics.compute`, and while `LspTraceTest` holds
the flag on and the sink rebound, those lines land in its `ByteArrayOutputStream`. This matters for
the fix and not only for the record: chasing the executors would have left the actual exposure in
place.

## The other race in the same state, which no filter reaches

`LspTraceTest.offByDefault` asserts `LspTrace.enabled()` is false. `GraphitronLanguageServerTest`'s
nested `SetTrace` class sets it true, and its three cases assert `enabled()` is true while
`LspTraceTest.resetSeam` sets it false after every case. Either class can break the other, in both
directions, and scoping an assertion to a span name reaches neither. The comment already in
`SetTrace.resetSeam` says leaving the flag on "would fail LspTraceTest's off-by-default assertion
depending on class ordering": the hazard is interleaving, and ordering is the sequential model of a
module that is not sequential.

So the narrow fix the draft proposed, scoping the count, is necessary and not sufficient. It fixes one
assertion and leaves four exposed.

## The decision

Hold the process-global state the way this tree already holds it, and fix the assertion on its own
merits.

### `@Isolated` on `LspTraceTest`

`@Isolated("rebinds LspTrace's process-global sink and enabled flag")`, exactly as `graphitron`'s two
classes carry it for `ClassificationTrace`'s writer. Extending that shape is the point: this module
has the problem `graphitron` already solved, and a second mechanism beside it would be a second thing
to learn.

One annotation, not two. Isolation is symmetric, so excluding every other class while `LspTraceTest`
runs also keeps `SetTrace`'s `enabled()` assertions away from `LspTraceTest.resetSeam`. No third class
writes the flag: the other classes that drive a server reach `initialize` with no trace value, and the
handshake is enable-only, so an absent value leaves the flag as it was. `GraphitronLanguageServerTest`
keeps its reset, whose remaining job is to stop leaving tracing on for whatever runs next, and its
comment stops attributing the hazard to class ordering. While `SetTrace` holds tracing on, spans opened by classes running beside it go to the
default sink, which is stderr and is captured per class by surefire, so that direction is noise rather
than a failure.

If R764 settles `graphitron-lsp` as sequential, `@Isolated` becomes inert and stays: it states what
the class does to the JVM, and it is what keeps the class safe if concurrency is ever turned on
deliberately.

Rejected alternative: making the capture structurally immune by giving `LspTrace` a thread-scoped test
sink. It would put a test-shaped read on the emit path, and the statics are what make the seam free at
a per-keystroke call site. `@Isolated` costs the production path nothing.

### The assertion says what it saw

Two fixes to `doubleCloseIsIgnored`, each right independently of the concurrency question.

- **Scope the count to the span the case opened**, as every other case in the class already does
  through `lineContaining(marker, name)`. Rename the span from `phase` to `double-close` so the filter
  is a scope the case owns rather than a word that happens not to collide with a production span name
  today.
- **Assert over the filtered list, not over its size.** `expected: 1L but was: 2L` destroyed the one
  piece of evidence that would have named the foreign emitter, which is why this item reconstructed
  the mechanism from the code rather than reading it off the failure. A `hasSize(1)` over the list
  prints the lines it found, so the next breach of this invariant arrives with its cause attached.

## Implementation

Both files shipped at `7511442`. Nothing remains.

- `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/trace/LspTraceTest.java`: the `@Isolated`
  annotation, the span rename, the scoped list assertion, and the new case below. The class javadoc
  gains a sentence naming what the class does to the JVM and why that means isolation, in the shape
  `ClassificationTraceTest`'s javadoc already uses.
- `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/server/GraphitronLanguageServerTest.java`: the
  comment on `SetTrace.resetSeam`, which currently teaches the wrong execution model to the next
  reader of the class most likely to need the right one.

Two files. The item is small on purpose: what it buys is that a green build stops depending on which
class happened to be running.

One shape decision inside the contract, recorded because the two sections read differently on it.
The Tests section below places the foreign emitter "in the same case", and that is where it landed:
`doubleCloseIsIgnored` now opens `double-close`, closes it twice, joins a thread that closes
`foreign-emitter`, and asserts `hasSize(1)` over each name's close lines. So the class still holds
fourteen `@DisplayName` cases rather than fifteen; the Implementation bullet's "the new case below"
names the scenario the Tests section describes, not a fifteenth method. A separate method would have
had to restage the double close to exercise the same filter.

The reviewer's precision was taken as given: `linesContaining(marker, name)` is a new list-returning
helper, and the existing `lineContaining` now sits on top of it so one filter serves both.

## Tests

Unit tier, on the module's own test surface. No pipeline, compilation or execution work.

- **The double close is still one close.** The existing case, with its count scoped to its own span
  and asserted over the list.
- **A foreign emitter cannot break it.** In the same case, a second thread opens and closes a span
  under a different name and is joined before the assertion. The case then asserts that its own span
  closed exactly once and that the foreign close line is in the sink, so it fails if the filter
  reverts to the bare marker. Both halves are scoped on purpose: an assertion on the sink's total
  number of close lines would be the same JVM-global count this item is removing. Deterministic, a
  joined thread with no sleep and no timing. This is the case that pins the scoping, since `@Isolated`
  would otherwise make the old assertion pass again and hide the defect rather than fix it.
- **What must stay green.** `LspTraceTest`'s other thirteen cases and `SetTrace`'s three, in the
  verification build, which is the run where these classes meet the other 75.

How we know the item is delivered: the foreign-emitter case fails against today's filter and passes
against the scoped one, and `LspTraceTest` carries the annotation that says why it may not overlap
another class. Neither claim rests on a flake failing to recur.

Both directions were run before the commit, on the implementing session's tree. Against the
bare-marker filter restored in place, with everything else as shipped, `doubleCloseIsIgnored` fails
with `expected: 1L but was: 2L`: the foreign thread's close line is the second one, which is the
original failure reproduced on demand rather than waited for. Against the scoped filter the class is
14 of 14. In the verification build at `7511442`, `graphitron-lsp` runs 646 tests green, including
`LspTraceTest`'s fourteen and the six under `$/setTrace drives the trace seam`.

## Out of scope

The channel. Whether `graphitron-model`'s test-jar should ship its properties file, where the
exclusion belongs, whether the "Discovered 2" launcher warning should fail a build, and what
`graphitron-lsp`, `graphitron-mcp` and `graphitron-maven-plugin` should each declare, all belong to
R764, which already carries the options and the measurements. The numbers above are contributed to it
rather than acted on here. This item's two changes are correct under either settlement, which is why
it does not wait for one.

Documenting the reactor's test-execution model in `docs/architecture/how-to/testing.adoc`, which today
says nothing about parallel execution. That page's section should be written by whoever settles the
model, not by an item that only obeys it.

Awaiting executor termination in `TextDocumentServiceTest` or `DiagnosticsDrainThreadingTest`. Their
teardown interrupts without awaiting, which is untidy, and per the mechanism section the spans those
threads hold are no-ops, so nothing observable follows from it here.

Anything about `LspTrace`'s production behaviour: the seam, its output format, its enabling surface
and its statics are unchanged.

## Related

R764 owns the channel that makes this module concurrent, and its body now records this failure as the
second predicted flake to arrive through it. R832, since shipped, was the same relationship one module
over: it held a `graphitron-maven-plugin` test whose latch budget this concurrency broke, while R764
held the channel. R741 owns the stale "this module and only this module" claim in `graphitron`'s
properties file, which is the same misreading of the reactor's execution model that
`SetTrace.resetSeam`'s comment carries one module over.

## Reviewer findings

### Round 1 (2026-08-27, In Review -> Done, reviewer session 01C7m6N4LDvR1EW6VWsPqnTj)

Verdict: withhold, on question 2, and question 1 is unanswerable for the same reason. No
implementation reached the repository. The gate has nothing to review.

What the reviewer was handed: an implementation commit `76883ea` "R860 In Progress -> In Review".
That SHA resolves to nothing. It is absent from `claude/graphitron-rewrite`, from every one of the
remote's branch heads after a full `refs/heads/*` fetch, and from GitHub's own commit lookup, which
returns "No commit found for SHA". The two commits reachable for this item are `060266a`
(Backlog -> Spec) and `4627c67` (Spec -> Ready). There is no Ready -> In Progress commit either.

The delivered tree is the pre-implementation tree, at every one of the four sites the item names:

- `LspTraceTest` carries no `@Isolated`. The annotation appears nowhere under
  `graphitron-lsp/src/test/java/`.
- `doubleCloseIsIgnored` still opens `LspTrace.span("phase")`, not `"double-close"`.
- Its assertion is still the JVM-global count this item exists to remove:
  `assertThat(emitted().stream().filter(l -> l.contains("lsp-trace <")).count()).isEqualTo(1)`
  at `LspTraceTest.java:142`, the exact line and shape the item's opening paragraph quotes.
- `SetTrace.resetSeam`'s comment still reads "would fail LspTraceTest's off-by-default assertion
  depending on class ordering", the sentence the item promised to correct.
- The class holds fourteen `@DisplayName` cases, so the foreign-emitter case, the one piece of
  evidence the spec nominated as proof of delivery, does not exist.

The item's own completeness criterion is therefore unmet on its face: "the foreign-emitter case
fails against today's filter and passes against the scoped one" requires that case to exist, and
"`LspTraceTest` carries the annotation" requires the annotation. Neither is in the tree.

No verification build was run and none would inform this verdict: the tree under review is trunk
unchanged, which each of its own commits already covered. Status stays at `Ready` rather than being
flipped, because trunk never left `Ready`; the In Review state existed only in the unpushed session.

The next pass is one of two things. If the implementing session's branch still exists, push it and
re-request this gate against a reachable tree. Otherwise the implementation is simply unstarted and
the item runs Ready -> In Progress from here. Nothing in the spec needs revising for that: every
claim it makes about the tree was checked at this gate and holds. `ClassificationTraceTest:30` and
`SingleWalkClassificationOrderTest:44` both carry `@Isolated("rebinds ClassificationTrace's
process-global writer")`, so the precedent the decision extends is exactly as described; the
`SetTrace.resetSeam` comment is as quoted; and `LspTraceTest`'s other cases do scope through
`lineContaining(marker, name)` as claimed.

One precision for the implementer, not a finding: that helper returns a single `String` through
`findFirst().orElseThrow(...)`, so the specified "`hasSize(1)` over the list" needs a
list-returning sibling beside it rather than a call to `lineContaining` itself. The spec's intent is
unambiguous and the shape of the helper is the implementer's call.

**Answered (2026-08-28).** The implementation landed at `7511442`, reachable on
`claude/graphitron-rewrite` and on `r860-lsp-trace-isolation`. The item ran Ready -> In Progress from
here, which is the second of the two paths the round names: the previous session's branch was not
recoverable. All four sites the finding checked are now changed, and the precision above was taken as
specified. The finding's text stands as the record of the round that produced this pass.
