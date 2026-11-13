---
id: R1131
title: "A clean build runs in under five minutes"
status: In Progress
bucket: dx
priority: 5
theme: tooling
depends-on: [tests-read-shared-fact-stores]
created: 2026-10-30
last-updated: 2026-11-13
---

# A clean build runs in under five minutes

## Goal

The verification build, a clean `mvn install` of the whole reactor, finishes in under five minutes
on the workstation it is run on, an 8-core, 16-thread Intel Core i9-9880H with 32 GB of memory, with
the machine to itself and every test and gate it runs today. On 2026-11-11 it took 33:56.

What changes is the working day of everyone on the project, agents included:
- a contributor or an agent session verifies a change in the time it takes to read the diff;
- several sessions can verify on one machine without queueing for it;
- a change to the model is no longer a 35-minute commitment.

Nothing is dropped to get there: no test tier, gate or fixture is removed or skipped. A test retires
only when what it tests retires, as the comparisons against the classification walk do when R876
removes the walk.

## Why the plan is rewritten

The first plan (2026-10-30, at `fab5e9eb6e` and before) tuned a build that was 23 minutes long. It
took the build to about 20 minutes on a 4-core machine. Meanwhile the store grew: the per-depth
occurrence loop landed 2026-11-04 to 11-08, and the build went to 34 minutes.

What the first plan landed and measured, and what still holds:
1. **The statement cache.** It is sized by what the caller does with the store (`StoreUse`):
   `LONG_LIVED` holds a capture's statements, saving 23% on `FactExpectationTest`. `ONE_CAPTURE`
   holds one depth's statements; it went from 8 to 64 in `300edfb296` and saves about 9% of
   `graphitron`'s test CPU. Readers end in a commit, so a reader's cache survives.
2. **The `{@link}` gate moved into javac,** which took 121 s off the model's clean build.
3. **The corpus is captured once per JVM,** and its `@Isolated` tail is gone: 44 s.
4. **The test pools are capped at their parallelism** (`max-pool-size=4`): 56 to 146 s per module
   on a 4-core machine.

Closed on measurement: splitting `FactExpectationTest` (the noise was larger than the effect) and
booting test stores from a template (re-opening a copy costs more than a boot).

Not done: parallel modules, and taking the slow suites off the critical path.

The rewrite starts from a new measurement on the workstation:
`roadmap/audits/2026-11-11-the-clean-build-measured-on-the-workstation.md`. It changes three
things about the plan:
- **Parallelism has a floor.** The build burns 9,711 CPU-seconds, so even perfect use of 16 threads
  leaves about 16 minutes. The work has to shrink by about two thirds.
- **The work is H2 planning.** It is not executing queries or running our code. In `graphitron`'s
  test JVM, 66% of samples are H2 planning (52% exhaustive join-order search, 34% re-planning nested
  views for each candidate order), 9% parsing and 14% executing. Our own code's self time is under
  1%. The model's split is the same shape.
- **The planning is paid per capture, and captures repeat.** About 1,000 captures run in one build.
  The roughly 100 scenario fact documents are captured at least three times. The cache saves a
  statement within one store, but every new store plans everything again.

## What was measured, 2026-11-11

From the audit, on trunk `8d34bcd6a3`, sequential, `BUILD SUCCESS`, 33:56:

| | |
|---|---|
| total CPU | 9,711 CPU-s; on average 4.76 of 16 threads busy, so 70% idle |
| `graphitron` module | 1,097 s wall, 4,663 CPU-s (tests 814 s; `generate-test-schemas` 255 s on 1.7 cores) |
| `graphitron-model` | 303 s, 1,853 CPU-s |
| sakila-example / LSP / plugin / MCP | 194 / 172 / 138 / 49 s |
| critical path | fixtures, sakila-db, model, graphitron, plugin, sakila-example: 1,759 s |
| serial tails under 2 cores | 480 s: `generate-test-schemas` 230, `EmittedRegistryAgreementTest` 160 (one method, 302 s in all), `GeneratorDeterminismTest` 40, the rest small |
| inside `graphitron`'s test JVM | H2 planning 65.8%, parsing 9.1%, executing 13.5%; GC 22% of the fork's CPU; heap at its 6 GB ceiling |
| planning by stage | `PathNeeds.write` 20% of samples, `OccurrenceSources.write` 7%, `OccurrenceProducers` 4%, `TypeBackings` 4%, `EntryDefects` 4%, `OccurrenceConditions` 3% |
| captures per build | at least 695 in `graphitron`'s tests, 210 in LSP, 92 in `generate-test-schemas`, 36 in MCP; 1,256 inline capture sites in `graphitron`'s tests |

### Re-measured at `7737fe1f`, 2026-11-12

`audits/2026-11-12-the-clean-build-re-measured-at-7737fe1f.md` repeats the recorded build after 117
more commits, on the 4-core agent sandbox, with every fork recorded for the first time (B6): 36:45
wall, 6,745 CPU-s, 3.06 of 4 cores busy. Against the earlier build on the same box, CPU is 6% lower
and `graphitron` is flat (3,554 CPU-s, 53% of the build, 73% of its Java CPU in H2 preparation, 26%
of it through `TestSchemaHelper`). Whole-build numbers from the two machines are not comparable;
the CPU split is.

## The plan

It runs in three tracks, which do not wait for each other.
- **Track A** removes most of the work, and decides whether five minutes is reachable at all.
- **Track B** uses the machine, cheaply, now.
- **Track C** is R876 work that shrinks the work further, ranked by this profile.

Each step is priced before it lands, by the method under "Measuring".

### Track A: plan each statement once per test JVM, not once per capture

The mechanism exists already: R1160's `FactGraphs` population. It is captured once per JVM, lazily
and per graph, into long-lived stores (`StoreUse.LONG_LIVED`, a cache holding a whole capture), and
kept in JUnit's root `ExtensionContext` store, closed when the run ends. What costs the build is the
captures that do not go through it, so this track prices that gap and closes it. It invents no second
mechanism.

**A1. Price it first.** This is the decisive experiment, and it comes before anything below is
committed to. Measure the CPU per capture in a test that captures inline (a store booted for the
test, `ONE_CAPTURE`) against the same graph read from the `FactGraphs` population, on a sample of
`graphitron`'s and the model's store-heavy classes. Run interleaved pairs, the first discarded. With
the capture counts in the 2026-11-11 audit, that says how much of the planning share the migration
removes.
- If it is most of it, Track A is the main road to five minutes.
- If it is not, the target is restated from what A1 shows, and this item says so before going on.

*Result (2026-11-11, `roadmap/audits/2026-11-11-what-a-capture-costs-by-store.md`).* It is most of
it, and the target stands. A capture costs about 2.3 s of CPU in an empty store and about 0.3 to
0.45 s in a store that already holds other graphs and has kept its statement cache.
- **Every inline arm lands in the expensive case.** That includes the thread's long-lived store,
  whose clear empties it and invalidates its cache: the referential-integrity toggle the clear
  truncates under throws the cache away.
- **The real run shows the same split.** In `graphitron` and the model, inline captures are 797 of
  1,108 captures and 1,941 of 2,111 thread-seconds. At the populated price they would cost about
  540 s in all.
- **The lever is a populated store, not the population as such,** so A2 gains a shorter road, A2a.
- **Duplicates are second order.** At 0.45 s a capture, A3 is priced against that figure, not 2.3 s.

**A2. The inline captures move onto the population, heaviest first.** This is R1160's migration (the
`fact-documents` skill, each module's `InlineCaptureGateTest` ceiling, 294 call sites in `graphitron`
today). This item adds only the order: classes ranked by the CPU their captures cost in the latest
profile, so the build gains early. A test that needs a store of its own keeps one, as R1160's "What
keeps a store of its own" says.

**A2a. Scoped inline captures go into a store that accumulates instead of clearing.** This is the
harness change A1 points at. It comes ahead of A2's class-by-class order and does not replace it:
- each capture goes in under a graph name of its own;
- each thread keeps one store per catalog and classpath, as the population's shards do, and replaces
  it after about 100 graphs;
- nothing is cleared.

*Priced 2026-11-12 (the A1 audit, § "A2a, priced").* In a spike, three scoped classes went from about
528 to 122 CPU-seconds in interleaved pairs, 77% less. A store retains about 0.9 MB per graph, and
its capture cost doubles over 300 graphs, which sets the limit.

Which tests move:
- **Classes reading only their own graph move.** That is 37 of the 97 classes that borrow the thread
  store, and 12 more that read only relations no capture changes. They carry most of the CPU:
  `EmittedRegistryAgreementTest` alone is 27% of it.
- **The rest keep the clearing store, or a store of their own.** That is 31 classes with an unscoped
  read, and those that write or assert store-wide relations.

*Decided 2026-11-12 (Alf): the route is the `FactGraphs` population.* The ruling is in
`docs/architecture/how-to/testing.adoc`.
- `FactGraphs.Reader.readInline` takes inline SDL into a store lent from a pool kept per catalog and
  classpath, seeded with a ballast graph, and reads it through the select-only connection.
  `EmittedRegistryAgreementTest`, `NodeIdDecodeDefectsTest` and `ArgmappingProjectionDefectsTest`
  read there, and in interleaved pairs they cost 78% less CPU (the A1 audit, § "The `FactGraphs`
  route, priced").
- `InlineCaptures` counts `readInline`, so a moved site stays inline until its SDL becomes a
  document.
- The stores are a pool, not one per thread (Alf, 2026-11-12). A capture borrows a store no
  other capture holds, so concurrent captures never queue on one writing connection, whichever
  threads the engine uses.
- **The rest of the scoped classes move under R1160, not here (Alf, 2026-11-12).** That is about 35
  that read only their own graph and 12 that read only relations no capture changes. R1160 moves
  them onto fact documents, which the population already reads cheaply. A test that has to keep its
  schema inline captures through `readInline`, never through the thread store that clears between
  tests.

The stores booted per capture (`ownStore*`, `BuiltStore`) are a separate 807 s. They follow where
their reason for a store of their own allows.

The spike also found R1177: `reclaim` fails a capture in a store where another graph still names a
vanished schema file.

**A3. Each scenario is captured once per build, not once per module.** The population is per JVM,
so the model's tests, `generate-test-schemas`, `graphitron`, LSP, MCP and the plugin each capture the
same documents again. This step goes beyond R1160: the model's run writes its shards to store files
under `graphitron-model/target`, published with the test jar, and the downstream populations and
`generate-test-schemas` open those instead of capturing. A file whose DDL hash or document set does
not match is refused and captured afresh.

**A4. Capture code lives in the model, and `graphitron` captures only through its harnesses (Alf,
2026-11-13).** The test stores are then managed in one place, so A2a's pool and A3's shared files
reach every capture. The ruling is in `docs/architecture/how-to/testing.adoc`. In small commits:
- `TestSchemaHelper` captures through `FactGraphs.inline()`, the pool `readInline` uses;
- the tests of what capture writes move to `graphitron-model`, about 31 classes and parts of four
  more. A test that compares a store relation with the walk stays in `graphitron` and retires with
  the walk;
- the generator tests and helpers that still capture take their store from a model harness;
- a structural guard, in the style of `StoreClientBoundaryTest`, fails `graphitron`'s build when its
  main or test code captures directly.

Whether `GraphitronStore.captured`, the call a run starts from, counts as capturing directly is with
Alf.

### Track B: use the machine

**B1. Build independent modules in parallel.** Add `.mvn/maven.config` with `-T 1C`, pinning the
release deploy to one thread. The audit of 2026-11-01 found nothing that collides. LSP, MCP,
roadmap-tool and docs then run beside `graphitron`: about 280 s, estimated.

**B2. Run `generate-test-schemas` in parallel.** `GenerateTestSchemas.main` loops over 92 scenarios
in one thread at 1.5 cores. Give it a pool, or read the scenarios from A3's store: about 200 s,
estimated.

**B3. Split the single-thread tails.**
- `EmittedRegistryAgreementTest` becomes one dynamic test per document, so the pool can spread it:
  about 120 to 160 s. It is deleted with the walk under R876.
- `GeneratorDeterminismTest` and `GraphCountGrowthGateTest` are split by their inputs: about 40 to
  60 s together.

**B4. Run the invoker in parallel.** Eight nested builds run one after another in
`graphitron-maven-plugin`: about 50 s with `parallelThreads`.

**B5. Size the test pools to the machine once A has cut the work.** The pools are fixed at 4, and the
two big forks already run at their 6 GB heap ceiling with 22% of `graphitron`'s CPU in GC. So the
pool and heap are raised together, after A2, and priced. Raising them before Track A only queues
more work for the same heap.

**B6. Let the forks be recorded.** Done 2026-11-12, with no change to the build.
- *The cause.* LSP, MCP and the plugin hard-set `<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>`,
  and a module that hard-sets its argument line makes surefire ignore `-DargLine`. The `@{argLine}`
  in front is evaluated late against the project's properties, where a command-line `-D` is not, so
  it expands to the root pom's empty `<argLine/>`. `graphitron` and the model set none, and honour it.
  Measured from the "Forking command line" in `mvn -X test` with `-DargLine=-Dgraphitron.b6marker=cli`:
  the marker reached none of the three forks, and the module's own flag did.
- *The route that works everywhere.* The `java` launcher reads `JDK_JAVA_OPTIONS` in every JVM it
  starts, so `JDK_JAVA_OPTIONS='-XX:StartFlightRecording=filename=<dir>/jfr-%p.jfr,settings=default,dumponexit=true'`
  in front of `mvn` writes one recording per process, `%p` expanding on JDK 25: the Maven JVM, each
  surefire fork, and what the build execs, which includes the `generate-test-schemas` JVM and MCP's
  docs index builder. Tell them apart by `jdk.JVMInformation.javaArguments`; the Maven JVM's file is
  the one with the Maven events. Pass the flag this way instead of through `MAVEN_OPTS`, or the Maven
  JVM is recorded twice. Checked on LSP, MCP and the plugin: each wrote a fork recording with samples.
- *Not done, on purpose.* Making `-DargLine` reach those three modules. A root-owned argument line
  with a new property, which the architect consultation raised as the structural fix, would change
  `-DargLine` for the two modules that honour it and rewrite `CoverageAgentWiringCheck`, for a
  profiling convenience. File it separately if a uniform knob is wanted.
- *Owed.* The `build-profile` skill still describes `MAVEN_OPTS` only. Adding the route above belongs
  with the next change that carries a full build, since the skill is outside `roadmap/`.

### Track C: smaller statements (R876)

The planning that survives Track A is the cost of each statement's shape. H2 searches join orders
exhaustively up to seven tables and re-plans every nested view for each order it tries. The store's
grain ruling (`docs/architecture/explanation/fact-model.adoc` § "Every relation has a grain") is
therefore also this item's largest lever: no view reads a view, rule views stay simple, and a
derivation that would need a complex rule is stored upstream.

This item does not do that work. R876 does, under:
- "No view reads a view, and every rule view is simple";
- "A per-depth rule is planned once per depth";
- the per-depth writer nodes F leads.

This item ranks them by measured weight, `PathNeeds` (20% of `graphitron`'s samples) and
`OccurrenceSources` (7%) first, and prices each one when it lands, using `store_graph_write.prepare_ms`
and a cold capture.

R876's subtractions also retire the comparisons against the walk: 364 s of `graphitron` class time,
302 s of it one test.

### Not in scope, and why

* **An incremental build cache** (the Maven Build Cache Extension) makes an unchanged module free,
  which is what most agent verifications want. It does not make a clean build faster, and it changes
  what "verification build" means. That is a policy question for Alf, filed separately if wanted.
* **A different store engine.** If H2's planner still dominates after Tracks A and C, the next
  question is what the same statements cost in another embedded engine. It is not asked until A1 and
  the first Track C steps are priced.
* **Raising JUnit parallelism alone.** It is ruled out by the heap ceiling and the CPU floor; see B5.
* **Dropping or sampling tests.** It is out of scope by the goal.

## Projection (estimates, to be replaced by measurements)

| after | clean build |
|---|---|
| today | 33:56 |
| Track B | about 14 to 16 minutes |
| Track B and the first Track C steps | about 10 to 12 minutes |
| Tracks A, B and C, if A1 shows planning collapsing | about 5 to 8 minutes |

Five minutes needs total CPU of about 2,500 to 3,000 CPU-s, kept at 8 or more busy cores. A1 says
whether that is reachable.

## Measuring

The `build-profile` skill owns the method, and this item follows it:
* Whole-build figures come from a forked, recorded `mvn clean install` on the workstation, with the
  load average under 3 at the start and no other build running. The recording covers JFR in the
  Maven JVM and in every fork, `/proc/stat`, and `ps`, as the 2026-11-11 audit did.
* A step's saving is priced by interleaved pairs, before and after, discarding the first pair, and
  quoted with its spread and in CPU-seconds as well as wall clock.
* A recording locates where the time goes. It never decides what to delete.

## Tests

Nothing about what is tested changes. The build stays green with every test passing, and each step
keeps the counts surefire reports per module. A2 and A3 change where a test's store comes from, by R1160's rules: the population is read-only to
tests, and a test that asserts something about its own boot or lifecycle keeps a store of its own.
