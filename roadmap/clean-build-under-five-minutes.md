---
id: R1131
title: "A clean build runs in under five minutes"
status: In Progress
bucket: dx
priority: 5
theme: tooling
depends-on: [tests-read-shared-fact-stores]
created: 2026-10-30
last-updated: 2026-11-27
---

# A clean build runs in under five minutes

## Goal

The verification build, a clean `mvn install` of the whole reactor, finishes in under five minutes
on the workstation it is run on, an 8-core, 16-thread Intel Core i9-9880H with 32 GB of memory, with
the machine to itself and every test and gate it runs today. On 2026-11-11 it took 33:56.

Two milestones come first (Alf, 2026-11-15): the whole build back to 20 minutes before the walk
dissolves, and 5 to 10 minutes after. Performance is the project's first priority until the first is
met; feature work waits for it.

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

### Re-measured on the workstation, 2026-11-16

Quiet machine, one build at a time, recorded with the `build-profile` settings and compared module
by module with the 2026-11-11 recording. The 40 to 70 minute builds of 2026-11-15 were builds running
beside each other, not a regression.

| module, seconds | 11-11 | 11-15 trunk | 11-16, after the call-scoped fit |
|---|---|---|---|
| `graphitron-model` | 303 | 663 | 630 |
| `graphitron` | 1,097 | 491 | 456 |
| LSP | 172 | 209 | 219 |
| sakila-example | 194 | 293 | 187 |
| whole build | 2,039 | 1,962 | 1,797 (29:58) |

Most of the model's growth is tests moved in from `graphitron` on 11-14; the two together got faster.
One regression was found and fixed: a capture wrote about 208,000 parameter-fit rows for every
method on the classpath, about 6 s per capture, until the fit was held per graph for the methods a
call names.

Inside `graphitron-model`'s tests (counted run, 47 classes opening 335 stores):
- **Preparing statements is 1,383 s of about 2,100 class-seconds.** Store opening is 161 s.
- **`graphitron_type_backing_rule` is 543 s of it,** prepared 842 times. Its first prepare in any
  database, empty or full, takes about 1 s, against a few milliseconds for other rule views: it is the
  only rule sampled that is `WITH RECURSIVE`.
- **57 classes open stores of their own,** 65% of the fork's samples; about 20% are classes the
  store-fixture guard exempts, and their cost is the fixed price of a fresh store's first capture,
  not the size of what they capture.
- **The live heap is 2.0 to 2.6 GB, half of it compiled plans;** the 5 GB ceiling is reached by
  churn, about 476 GB allocated per run, mostly planning and store creation. No store is held after
  its class ends.
- **H2 caches the upserts the stages write** (`MergeUsing` is cacheable); the cost is the first
  prepare in each fresh store, and re-preparing where a cache evicts.
- **Eight test threads instead of four saved 45 s of 649,** paid for with three times the garbage
  collection; not taken (B5).

## Work queue

The steps below, from either track or from R876, in the order their measured effect puts them.
Whoever works this item takes the top open step, prices it before it lands, and re-ranks the queue
from the next recording.

graphitron and graphitron-model are throughput-bound, so moving work around does not pay; only
removing it does. Two trials on 2026-11-17 say so. Warming the population on threads of its own cut
the time model's classes waited from 393 to 179 s and left the module's wall where it was, and
splitting `EmittedRegistryAgreementTest` into concurrent tests took `graphitron` from 7:36 to 7:16,
inside the noise, each comparison slowing about threefold. A step that schedules, splits or
parallelises a module's tests is priced against that before it is tried.

1. **`graphitron_type_backing_rule` stops recursing.** Done: the closure is a stage stepping one
   hop a round, and its first compile fell from about 1,000 ms to about 11 ms.
2. **The thread store's clear keeps its thread's statements.** Measured 2026-11-16 by binning every
   prepare of the model suite by what H2's session held before it (826 s of prepare time summed over
   forks): a re-prepare after a wipe costs 1.0 ms and builds almost no view index, but
   `ThreadConfinedStore`'s clear toggled `SET REFERENTIAL_INTEGRITY`, which empties every session's
   statement cache, so its 354 clears threw away about 1,250 statements each: 442 thousand
   re-prepares and 443 s, 54% of the total. The clear without a setting deletes in foreign-key order
   instead: the suite's prepare time fell from 827 s to 389 s, and the delete costs back about 45 s
   of it, 137 ms a clear against the truncate's 4 ms, all of it the rows a case wrote, since clearing
   only the tables holding rows changed nothing.
3. **The thread store goes: its borrowers move onto the population's pool, to fact documents, or
   to a store of their own** (A2a, R1160's migration). The clear exists only because a thread's
   cases share one store. What replaces it is the population: a test's inline schema becomes a fact
   document the population captures (testing.adoc § "Keep a store of your own only for a reason",
   Alf 2026-11-14, which deprecates inline SDL and so `FactGraphs.Reader.readInline` with it, a
   waypoint at most). No per-thread store that stops clearing is built beside it.
   Recounted 2026-11-16 by a reading of every class that borrows the thread store: 94 classes, 45
   reading only their own graph, 15 only relations no capture changes, 17 reading a graph-keyed
   relation without a graph filter, and 17 writing or asserting what the whole store shares.
   `graphitron` borrows it no longer. In this order:
   1. **The 17 unscoped classes read only their own graph.** Done: each case captures under a graph
      named for it and every read filters to it, proven with the clear switched off.
   2. **The 17 store-wide classes**, by what they are about:
      - what a schema captures goes to a fact document: graph-keyed rows as `@expectEquals`,
        rows the store shares (`sql_`, `federation_version_`) as `@assertContains` or
        `@assertAbsent`, and a count over the whole store as the rows it means
        (`EnumBindingCaptureTest`, `NodeMetadataCaptureTest`, `CaptureCorpusIsolationTest` with
        `EntryFamilyFixture` becoming a two-file document where the pass writes what its
        hand-run writers do, `CapturedStoreTest`'s remaining claims, and
        `RejectionSeverityCoverageTest`);
      - what a store does keeps a store of its own, its schema from a document, under R1160's
        "What keeps a store of its own": writes, locks, lifecycle and cost
        (`FederationCaptureTest`, `SourceStampTest`, `StaleModuleTest`,
        `DiagnosticsStatementCountTest`, `SurfaceScanCountTest`, `StoreBootCountTest`,
        `GraphitronMcpServerTest`'s cross-graph status);
      - `ThreadConfinedStoreTest` goes with its subject; the boot budget it pinned is
        `FactStores`', stated per module as `graphitron.test.bootBudget` and checked at every boot.
      Five LSP classes (`FieldCompletionsTest`, `SourceCadenceHoverAndDefinitionTest`,
      `TableCompletionsTest`, `TextDocumentServiceTest`, `DeclTargetTest`) meet other cases'
      `java_` declarations because the readers of that family key on the class name alone, which is
      R876's "A source declaration is read through the graph's own source roots"; until it is done
      they hold a store of their own.
   3. **The borrowers move, and the thread store and every borrowing arm go.** R1160's migration,
      worked under its node; this item ranks the order by cost. About 107 sites in
      30 files call `ThreadConfinedStore.run`, 81 in 13 call `withSeededStore`, and `CapturedStore`'s
      borrowing arms and the LSP and MCP `StoreFixture` case arms have about 230 more. A case that
      captures inline SDL once and reads it becomes a fact document, its assertions claims where a
      claim can state them and a read of the document's graph where one cannot; one that reads
      twice, or drives a gatherer by hand to pin what a second reading sweeps, becomes a scenario
      document with its readings; one that
      writes the store keeps a store of its own. The arms that capture under the one default name
      are removed rather than given a name, and `CapturedStore.GRAPH` stays only on the arms that
      own their store. `SeededStore` dissolves with its callers, under `SeedingDissolutionGateTest`,
      ending at R876's "`SeededStore` is deleted". `SeededStore.derive` empties four `graphitron_`
      relations whole and recomputes every graph, so a seeded case shares a store only once it no
      longer needs the derive, or the derive is scoped to its graph.
      Only a class whose subject is stored rows becomes documents; one whose subject is something
      else waits for what it tests. Three are left unconverted on that rule:
      `ReferenceForParticipantDefectsTest` tests a store-side check that refuses in Java, and goes
      with R876's "Each node-id rule is an entry defect", whose codes its cases become;
      `NameMatchedKeyPairTest` retires with R876's "`sql_name_matched_key_column` is gone"; and
      `ClassificationDomainTest` with the `intent_` family it tests. `ClaimConflictsTest` keeps its
      subject, the build report's messages over stored claim defects, and reads the claim documents'
      graphs rather than capturing inline.
   4. **The classes with fresh stores of their own.** Prepares on a session that never saw the
      statement cost 278 s (2.2 ms each, where the view indexes are built), heaviest in
      `WarmStartRefreshTest` 58 s, `PersistentStoreTest` 36 s, `FactSchemaGateTest` 20 s,
      `StoreNodeTablesTest` 12 s and `SourceGraphScopingTest` 10 s, besides the population's own
      39 s. Each takes its schema from a document and keeps the store only where its subject is the
      store.
4. **H2's view-index cache of 64** (A5). Measured: it is what repeats the type-backing compile, and
   step 1 removes that cost; no other rule view costs more than a few milliseconds to compile again.
   Nothing to change unless the re-measure after step 1 shows another view paying it.
5. **A fresh store opens cheaply** (A6). Set aside 2026-11-17, priced under A6: about 4 s of class
   time at today's 142 boots.
6. **A test's catalog is read once** (A7). The catalog skip needs the catalog's classpath entry, and
   tests pass none, so every capture re-reads it.
7. **Planning per statement** (Track C: R876's view nodes and per-depth nodes), by `prepare_ms` on a
   cold capture.
8. **Track B**, priced after the above, since it divides what is left. B1 and B4 are done; B2 was tried
   and set aside. For `graphitron`'s chain A3 comes first: `generate-test-schemas` is 151 s of it under
   `-T 1C`, and only removing its captures shortens it.
9. **A test hands its run the classified classpath.** Done 2026-11-17. The two method-graph oracles
   built their `RunContext` from the test classpath's paths, which reads every entry as the
   project's own, so each capture read every jar on it whole: 25,862 classes and 151,409 methods,
   about 44 s per `graphitron` JVM, one oracle paying and the other waiting (76 and 65 s in the
   build). Handed the classified list instead, they run alone in 17 and 18 s against 48.5 and
   51.8 s. Production was never affected: sis's capture store holds no class from its 42 transitive
   dependencies.
10. **The long own-work classes start first.** Done 2026-11-17. graphitron-model's four workers were
    busy for 340 of a 402 s test phase, three long classes that wait on no population starting in its
    last 80 s and running nearly alone. `@Order` on the five such classes, and the orderer named in the
    module's `junit-platform.properties`, put the phase at 382 and 375 s against 392 and 396 s by
    default in interleaved pairs, the module at 460.2 and 454.0 s against 468.2 and 474.9 s.
11. **The slow model classes hold only claims nothing else holds, more cheaply.** Done 2026-11-17, by
    a case-by-case reading of `FactSchemaGateTest`, `PersistentStoreTest`, `CodeCaptureTest` and
    `WarmStartRefreshTest` for coverage elsewhere, claims the assertions do not hold, and cheaper
    forms. Read-only fixture claims became per-graph claims over the fact documents in
    `CapturedGraphInvariantsTest`; duplicate and vacuous cases went; cases that captured only to have
    rows seed one; read-only cases share one capture per fixture; a stage's scoping is held by one
    sibling snapshot over populated stages. Two claims over the documents turned up what one fixture
    could not: an undeclared directive is a refusal its graph records, and a link's imports are told
    apart by name. The model's suite went from 468 to 410 s, its class time from 1,536 to 1,309 s.
    `GraphCountGrowthGateTest` was replaced by a plan check, Alf's ruling: see step 13.
12. **Unmeasured candidates from a code reading of one capture,** each to be measured before it is
   ranked: a stage that runs with no input for the schema. The two `ANALYZE`s are step 14's.
13. **A plan check replaces the growth gate.** Done 2026-11-18 (1d1597c077).
    `GraphKeyedScanGateTest` plans every statement the documents' captures ran with `EXPLAIN ANALYZE`
    and the binds it first ran with, each rolled back, and reads each table reference within its own
    query block: a full scan of a graph-keyed table, of the table or of an index walked whole, is a
    finding unless its block narrows that reference's `graph_name` by `=` or `IN`. A statement that
    meets a key in the warm store has its read lifted out (an insert's `SELECT`, a merge's source, a
    delete's `WHERE`). On the merged tree: 956 statements planned, 40 lifted, 2 explained bare, no
    finding, 12 filtered full scans. Bare `EXPLAIN` was ruled out first: H2 optimises a view per set of
    index conditions when it runs, and reading by alias text was ambiguous in one plan in five where
    the per-block reading is in one in a hundred. The recording the gate reads is always on; the model
    module took 7:00 against 7:06 in the build before it.
14. **An unchanged reading costs only its checks** (A3's precondition, and the dev loop's). Measured
    2026-11-17 on `generate-test-schemas`' 143 scenarios, each captured cold and then again unchanged
    under one graph name: the warm capture cost 0.73 of the cold one.
    - The two `ANALYZE`s are skipped when every gatherer vouches its reading unchanged, the
      configuration given a vouch of its own (`store_graph.configuration_stamp`). Done 2026-11-17
      (a0cdf2b573): warm 0.73 to 0.51 of cold. H2 keeps column selectivity across a reopen.
    - A catalog left alone re-derives neither its spellings nor its name-matched keys. Done 2026-11-18
      (061ad67af0): about 1.0 s over the 143 warm captures.
    - A snapshot's store stamp is a hash of graphitron-model's code (R876), so a reused store is never
      one older code derived; the precondition for the next step.
    - Tier 1, open: a reading every gatherer vouches unchanged, whose previous capture completed,
      skips graphitron's anchor phase and the derivation, and does not move
      `store_graph.last_captured`, which keeps naming the reading whose marks the rows carry.
    - Tier 2, parked: a stage skipped because its inputs did not change. Its readers select the
      current reading's rows by mark, so a skipped stage would hand a running one nothing; priced on a
      one-document edit before any writer changes, and what a mark means is Alf's and Filip's.
    - For later: a warm sweep delete is now and then 100 to 1,000 times its cold cost
      (`GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN` 2.45 s against 2 ms, `SQL_COLUMN_SPELLING` 3.0 s against
      0.43 s), with and without the `ANALYZE`s. One look with the store-performance skill.
15. **The population's stores are held in memory.** Done 2026-11-19. On disk they cost more and
    saved nothing. Recorded interleaved pairs of graphitron-model and graphitron on 2df1c2027, JFR in
    every fork, the backing the only difference: the model fork's CPU 1,679 s on disk against 1,286 s
    in memory, graphitron's 1,150 s against 705 s, wall 1,045 s against 784 s, and the peak heap after
    GC the same (4,861 against 4,859 MB, 3,229 against 3,244 MB). File I/O was 31.6% of graphitron's
    samples on disk and 0.8% in memory: H2 reading pages back through its small default page cache,
    and serializing a rewritten page for every one a capture changed.
    - Measured with H2's own instruments: a 40-graph store dumped with `SCRIPT TO`, loaded with
      `RUNSCRIPT` into an in-memory store, a file store and a file store with a 1 GB page cache, and
      the same captures timed under `QUERY_STATISTICS` and `org.h2.util.Profiler` at depth 256. The
      default file store read 178 MB back to capture a few; with the large cache it read nothing, and
      its appended writes (chunks 40% live) still cost 11 to 17%. Manual `ANALYZE` was 80% of the
      file store's extra statement time and about a third of a capture's work even in memory.
    - Tried and set aside: letting H2 analyse itself (no `ANALYZE_AUTO=0`, no manual `ANALYZE`). It
      removes the `ANALYZE`s, 5.2% of the model fork's samples and 11.6% of graphitron's, but the plans
      it makes without statistics cost as much again: the model fork 1,256 s against 1,241 s,
      graphitron 648 s against 658 s, within the noise. `RulePlanGateTest` caught three rules probing
      by `graph_name` alone (`graphitron_argmapping_step_head_rule` and `_next_rule` over
      `graphitron_occurrence`, `graphitron_join_path_step_rule` over `sql_table`), where the indexes
      they need exist but go unpicked without statistics.
16. **A capture analyses only the tables it wrote** (Alf 2026-11-19: we know what changes, so analyse
    with precision). Done 2026-11-19. `WriteTimes` keeps the tables a statement changed a row of, and
    the capture analyses each with `ANALYZE TABLE`; a created store analyses the rows it seeded. Fork
    CPU against the whole-store pass in interleaved pairs: the model 1,286 against 1,301 s (pair 0:
    1,242 against 1,280 s), graphitron 668 against 716 s (669 against 700 s). It also keeps
    `GraphPartition`'s declaration, which a whole-store `ANALYZE` reset on every empty table.
    `audits/2026-11-19-what-h2-spends-a-capture-on.md` holds the research; the engine facts are in the
    `store-performance` skill.
17. **The cold planning of the widest rules.** Open. In a fresh store the 29 entry-defect rules cost
    515 ms to plan, 400 of it three of them: `occurrence-producers`, `occurrence-depth-defects` and
    `occurrence-builds`, unions whose arms join many inputs per query block. Rewriting the stage's
    `MERGE` as `INSERT ... SELECT` was measured and buys nothing. `ViewComplexityTest` now ranks every
    view and arm statically on every model build (`target/view-complexity/`), and picked those three.
    Their arms are bisected cold (the audit, § "The three rules, arm by arm"): in each, a few arms
    joining seven inputs with correlated guards carry most of the cost, and they are handed to R876's
    node "No view reads a view, and every rule view is simple" with the two levers. The report's weight
    now follows H2's own search bound and counts each block's conditions (the audit, § "The weight,
    corrected against the arms"): Spearman over the measured arms 0.69 to 0.82, over the rules 0.92 to
    0.87. Next here: a first gate over the report, once a measure has held against a few changes.

18. **The model fork's growth under the 64-statement population cache.** Open. The model module went
    from 7:51 (11-18) to 10:13 (11-19, `-Xmx5g`, cache 1,024) to about 13:30 after `19031ddd38` pinned
    both forks at 4 GB and cut the population's cache to 64 a session, read from the reactor logs and
    the surefire reports of three trees with no build run (2026-11-20):
    - 11-18 to 11-19 (class time summed 1,535 to 2,104 s): spread over small classes that are cheap
      alone and returned to their 11-18 times after the pin (`DiagnosticFactsTest` 31, 141, 28 s;
      `StoreReaderTest` 1.5, 17, 1.9 s), the classes' own work flat. A tax on every class, which reads
      as the 1,024 cache filling the 5 GB heap with plans, as `19031ddd38` measured.
    - After the pin (2,104 to 2,869 s): in the classes that re-plan rule views over many captures,
      `CaptureCorpusIsolationTest` 13 to 279 s, `DetailRuleAgreementTest` 149 to 444 s,
      `CapturedGraphInvariantsTest` 204 to 479 s, `StageAnswerAgreementTest` 364 to 541 s,
      `NodeTypeIdDerivationTest` 21 to 73 s, none of them changed. `graphitron`, whose population kept
      1,024, shows no class growth (913 to 885 s).
    - The heap is ruled out: the model module at 5 GB and 64, the fork's `MaxHeapSize` read at
      5,368,709,120, took 17:53 against 13:23 and 13:39 at 4 GB and 64, every class about 30% slower
      on a desktop under load, and in no case the 10 minutes a heap cost predicts.
    - `roadmap-tool`'s 3:30 under `-T 1C` is not on the chain: it depends on the model alone and ends
      about 8 minutes before `graphitron`, its cost the cores it shares.
    - Next, on an idle machine and interleaved with a 4 GB and 64 control: the model at a population
      cache of 512. `19031ddd38` put 64 and 256 within the noise of each other at 4 GB.
    - At RC58 (`f6e16dd534`), 2026-11-26, `FactGraphsTest` and `FactExpectationTest` alone, two
      interleaved rounds: 784 to 860 s at 64 a session and 471 to 557 s at 1,024, where the 4 GB fork
      sat at its ceiling after every collection with 116 to 130 full collections (2 to 8 at 64).
      Even at 1,024 the two classes take three times their 108 to 127 s in CI at RC57, so 64 is not
      the whole of the growth.
    - RC57 (`b334863b90`) measured alike, the population then `LONG_LIVED` at 1,024: the two classes
      took 157 to 167 s at the pom's 5 GB, and 188 to 257 s at 4 GB with 24 to 28 full collections,
      against RC58's 374 to 450 s and 116 to 130. So CI's 8:55 to 18:09 is three causes together:
      the heap from 5 to 4 GB, the cache from 1,024 to 64, and a population live set that grew
      between them.
    - On `a5c84f9e78`, which carries `ea755c1a26`'s cut of about a gigabyte, the two classes at
      4 GB: 744 to 772 s at 64, 761 to 836 s at 256, 804 to 836 s at 512, and 364 to 419 s at 1,024
      with 60 to 102 full collections. A capture's statements fit only between 512 and 1,024, so a
      cache below that saves no planning. But the whole model module at 4 GB and 1,024 ran out of
      heap after 25:45, with 617 full collections, so 1,024 needs either more heap or step 19.
    - The test-schema generator in `graphitron` captures into the population too, in an exec JVM that
      never saw surefire's 1,024: 444 to 468 s at 64 against 148 to 159 s at 1,024 at RC58, which
      the generate-test-schemas execution now gives it.
19. **A large population cache without the heap following it.** Open. H2 keeps the cache per session,
    so what the population's plans hold grows with its stores times their sessions times the cache's
    size, and the cache is the one lever turned so far: step 18 measures what 1,024 buys and what it
    costs the 4 GB fork. The other factors are unmeasured: fewer, fuller population stores, and fewer
    sessions holding plans in each, so one capture's plans serve the next. Count first, in a model
    fork: the population's stores, the sessions each opens, and the distinct statements a capture
    prepares.
20. **The per-tier coverage page builds nightly.** Done 2026-11-26, as Alf decided. GitHub's trunk run
    reran graphitron's suite per tier under the coverage agent after the reactor, 11.1 min for the
    unit tier and 21.0 min for the pipeline tier (run 45867383154). Those reruns and the
    source-coverage page now run nightly from trunk, and a trunk push publishes the last nightly
    page, so it is up to a day old. The combined coverage stays in every push build.
21. **What the population keeps on the heap.** Open. Alf, 2026-11-26: a test fork should not need 3
    to 4 GB, so before more tuning, find out what the heap holds and why, by fast experiments. The
    tool is `PopulationHeapProbe`, a test class that runs only when `heap.probe.documents` is set: it
    warms an evenly spread slice of the population, collects, and writes the live heap and a class
    histogram grouped by owning library to `target/heap-probe/`. One variable at a time, each
    hypothesis with its prediction written here before the run, and a refuted one kept as refuted.
    - H1, the heap follows the open stores, not the documents: at a fixed store count, halving the
      documents leaves the live heap within 20% of where it was.
    - H2, H2's parsed statements and plans dominate: at a population cache of 1,024 the
      `h2-statements` group holds over half of the live heap, at 64 under a fifth, and the two runs
      differ mostly in that group.
    - H3, MVStore's page cache per store: `h2-mvstore` is a fixed amount a store, the same at 64 and
      1,024.
    - H4, our own objects dominate: `graphitron` is the largest group. Predicted refuted, under a
      tenth.
    - H5, the RC57 to RC58 growth sits in one group: the same probe at both, diffed by group.
    - H6, the statement texts kept beside the plans: each store keeps up to 20,000 texts in H2's
      query statistics and every text it ran in the shard's own map, so `jdk-and-arrays` (String,
      byte[]) grows with the distinct statements and not with the cache.
    - Results, 2026-11-26, live heap after collections, the population alone:

      | run | stores | live MB | per store |
      |---|---|---|---|
      | one booted store, no graph (334 views) | 1 | 60 | 60 |
      | 1 document, cache 64 | 1 | 97 | 97 |
      | 60 documents, cache 64 / 0 / 1,024 | 7 | 605 / 582 / 1,062 | 86 / 83 / 152 |
      | 120 documents, cache 64, 4 shards / 1 shard | 10 / 6 | 853 / 567 | 85 / 94 |
      | all 445 documents, cache 64 | 18 | 1,557 | 87 |

      H1 holds: the heap follows the stores, about 60 MB of each fixed before its first capture
      (H7, added: H2's parsed SQL for the schema's views and rules), and about 1 MB a document on
      top. H2 holds in part: 1,024 adds about 65 MB a store, nearly all of it parse tokens, while
      0 and 64 differ by little. H3 is small, `h2-mvstore` 52 MB over 18 stores. H4 is refuted,
      `graphitron` under 1 MB. H6 is unmeasured apart: `jdk-and-arrays` is 767 MB at 445
      documents, byte[] 397 MB beside 480 MB of `Token` objects, so most of it reads as parsed
      SQL too. One shard a catalog instead of four cut 120 documents from 853 to 567 MB and made
      warming 2.7 times slower, its captures serialized.
    - What it adds up to: at 64 the whole population holds about 1.6 GB, two thirds of it the
      18 stores' fixed parse; at 1,024, predicted, about 2.8 GB. The model fork's 4 GB peak is
      therefore more than the population, and the next experiment is what holds the rest, starting
      with the test classes that open stores of their own beside it.
    - The whole module at 4 GB and 64, sampled from outside once a minute: a live class histogram,
      the `.mv.db` files the fork holds open, and the test classes on its threads. Predictions:
      P1 (H6), at the peak the fork holds more `org.h2.engine.Database` instances than the
      population's 18, the extra ones in memory and opened by the classes that open stores of their
      own; P2, those extras hold 0.5 to 1.4 GB, 60 to 140 MB each; P3, the population still holds
      about 1.6 GB, and what is neither, results and schemas held by running tests, is under 0.5 GB.
    - Results, `cfbc5af520` with the probe's commit, 752 tests passed in about 19 minutes (inflated
      by a full collection a minute). From the third minute the fork held 2.0 to 2.07 GB in 23 or 24
      databases, 18 of them the population's files and 5 or 6 in memory, for ten minutes. In the last
      four it rose to 2.98 GB as the databases went to 32, three more population files for inline
      schemas and the rest in memory, and the sessions from about 70 to 127, while
      `FactCaptureAgreementTest`, `ArgMappingCandidateTest`, `RulePlanGateTest`,
      `NameMatchedKeyPairTest`, `CapturedStoreTest`, `DefectStageGateTest`, `StoreNodeTablesTest`,
      `FactGraphsTest`, `ModelCaptureMembershipTest` and `GraphitronStoreTest` ran. With only
      `StoreConsoleTest` left running the fork still held 32: a store opened by a test class lives
      to the end of the fork. P1 and P2 hold, about nine stores of about 100 MB each over the
      plateau; P3 holds in its numbers, the population and the plateau's in-memory stores about
      2 GB. Next: which classes open the in-memory stores and keep them past their own end.
    - Who opens them, by a stack logged at each store's open and a line at each close, over the
      classes that ran at the rise and on the plateau. Predictions: Q1, the plateau's in-memory
      stores are one a JUnit worker thread, the thread's shared store; Q2, the late ones are opened
      through `GraphitronStore`'s factories by the classes that open stores of their own, and more
      than half of them are never closed.
    - Results over 16 of the classes that ran at the rise and on the plateau, 122 tests: 107 stores
      opened, 104 closed. Q2 is refuted: the classes with stores of their own close them within
      their own run. Q1 holds and says more. The stores that outlive their class are
      `ThreadConfinedStore`'s, one booted per thread and never closed, which its comment justifies
      by an in-memory database dying with the fork. It does not die with its thread: an in-memory
      store keeps `DB_CLOSE_DELAY=-1`, so H2's engine holds the database after the thread and its
      `ThreadLocal` are gone. Hypothesis R, to test next: JUnit's pool retires idle workers and
      starts new ones, each new worker boots a store, and the old one stays, which would be the 5
      or 6 in-memory stores of the plateau growing to 11 at the end. Prediction: a whole run boots
      more thread stores than its four workers, about one for each in-memory store over the
      population at the end.
    - R is refuted. A whole run on `9161e3881d` with the trace, 752 tests passed: four thread
      stores, one each on `ForkJoinPool-1-worker-1` to `-4`, no worker replaced. Of 269 stores
      opened, 264 were closed; the five never closed are the four thread stores, by design, and one
      `CapturedStore.ownedStore` from `NodeTypeIdDerivationTest`, a leak. So the claim that stores
      live to the fork's end holds only for those five and the population. The rise to 32 databases
      at the end reads instead as four concurrent classes each holding a store of its own
      (`FactStores` class stores 79, `GraphitronStore.inMemory` 46, owned stores 23 over the run)
      beside three more population files for inline schemas. A trace that writes the time and the
      count of live stores at each open and close would show that rather than infer it.
    - Shards by size, counted from the documents' `@withConfiguration`: about 430 documents share
      the fixture catalog and the corpus over four stores; `jooqPackage: null` has 4,
      `singleschemafixture` 2, and six named classpath fixtures 1 or 2 each. So some 14 of the 18
      stores hold about 13 documents, 60 MB apiece. One store for each small combination saves
      perhaps four or five stores, about 0.3 GB; the combinations of one document still need a store
      each, since their catalog and classpath rows are keyed by no graph.
    - What the fixed cost is, on one booted store with no graph (`heap.probe.views`). Predictions:
      V1, dropping every view frees at least 40 of the 60 MB; V2, the cost follows the views' text,
      so dropping views holding half of it frees about half; V3, with no views the `Token` objects
      are nearly gone, which puts the tokens in the views H2 keeps parsed.
    - Results, one empty store: 60 MB with its 315 views (1.34 million characters of view SQL as H2
      keeps it), 36 MB with the longest views holding half the text dropped, 20 MB with every view
      dropped, `h2-statements` then at 0. V1 holds, the views are 40 of the 60 MB; V2 holds near
      enough, about 30 bytes a character, so the cost follows the text and no single view
      dominates (the 30 longest hold 40% of it); V3 holds. V4, added: H2 keeps a view's parsed query
      (`QueryExpressionTable.viewQuery`) with its token list (`Prepared.sqlTokens`), and an H2 that
      dropped that list after the parse would save the tokens. Refuted as the lever: emptying the
      list on all 315 views freed 7 MB, 345,000 keyword tokens remaining in the queries nested
      inside them. The cost is the parsed views themselves, so its levers are less view text in the
      schema and fewer stores, not a setting.
22. **Fewer population stores with captures in parallel inside one.** Open, a spike. The population
    keeps four stores a catalog and classpath because a store has one writing connection and a
    capture holds it for its whole transaction, and each store costs about 60 MB before its first
    capture (step 21). H2's MVStore runs transactions of separate sessions side by side, and the
    graph-keyed relations keep captures apart; what stands in the way is the single writer
    `GraphitronModelStore` is built on, the catalog and classpath families every capture writes
    under no graph, and anchoring and sweeping that run over the whole store. The spike: capture a
    store's catalog and classpath once, then its graphs on sessions of their own in parallel, and
    see whether they conflict or sweep each other's rows. If it holds, six stores instead of 18,
    about 0.7 GB, with the warming as parallel as now.
    - The spike, a test opening its stores itself and so not kept (`StoreFixtureGuardTest` refuses
      that outside a harness): the same documents of the fixture catalog and the corpus
      captured serially into one store, and into another first one alone, then the rest on four
      handles of their own in parallel, the rows each graph holds in every graph-keyed relation
      compared. Predictions: S1, the parallel captures finish with no lock timeout or failure; S2,
      every graph holds the same rows in both stores; S3, the parallel part takes at most half the
      serial time for the same documents.
    - First result, 16 documents spread over the fixture catalog's and the corpus's, four handles:
      S1 holds, no failure and no lock timeout. S3 holds: 80 s serially, 31 s in the parallel store
      (4 s for the first capture, 26 s for the other 15), 2.6 times faster. S2 holds for every
      relation holding facts: of 1,891 graph and relation pairs, the 12 that differ are all
      `store_graph_read`, whose rows are the reads that took a millisecond or more, so they follow
      timing rather than facts. Not yet measured: the heap of one store holding a whole catalog's
      documents against four, and the claims checked over the parallel store.
    - Next arms, predictions first. Whole: every document of the fixture catalog and the corpus,
      about 430, in one store on four handles; F1, no failure; F2, it warms no slower than today's
      four stores of them; F3, it holds about 60 MB plus about 1 MB a document, some 0.5 GB against
      about 0.67 GB for four. Mixed: the documents of every other combination with a few of the main
      one, in one store against a store each, since the catalog (`sql_`) and classpath (`code_`)
      families are keyed by `source_name`, a classpath entry's path, which the fixtures do not
      share with different contents; M1, every graph holds the same rows in both, and a graph that
      differs names a rule reading those families without scoping them to the graph's sources.
    - Results. Whole, 433 documents on four handles: 908 s, about today's warming; F1 refuted, 35
      captures (8%) failed, every one on a concurrent write to `store_source`, the one relation every
      capture touches keyed by its source alone, `merge` or `update` of rows another handle held;
      the 16-document run missed it by luck. F3 refuted the other way: the one store with all 433
      documents held 111 MB live, against 1,557 MB for the population's 18 stores, so a document
      costs the store about 0.1 MB and the population's heap is nearly all its stores' fixed cost.
      Mixed, 22 documents of 9 combinations: one capture failed on `store_source` again, and M1 is
      refuted where it was meant to look: six graphs of the combinations with another catalog or
      none (`catalog-isolation-bare`, `entry-family-bare`, `federation-key-inferred-bare`,
      `column-match-claim-own-catalog`, `result-shapes-other-catalog-record`,
      `entry-defect-write-rules`) held 58 to 60 more `code_graph_class` rows in the shared store,
      classes of a catalog they do not read. The report printed the first 40 of 149 differences,
      most of them the failed graph's, so a full listing comes next.
    - So two things stand between the population and fewer stores: the capture's write to
      `store_source` conflicts under parallel captures, and `code_graph_class` reaches classes of
      sources outside the graph's own. Both are store defects a parallel dev loop or a shared store
      would meet too, and the prize is large: one store's fixed cost instead of eighteen.
    - The second is fixed (R876, 2026-11-27): which classes are a catalog's is now read off the entry,
      a concrete `org.jooq.impl.SchemaImpl` subclass's package, not off `sql_schema` across the
      store, so a jar's code rows no longer depend on the graphs beside it. Two blockers remain for
      parallel captures in one store: `store_source`'s write, which gets a fix of its own, and the
      store-wide delete of `sql_name_matched_key_column`, which goes when R876 dissolves that
      relation and is not patched before. Fewer stores waits for both.
    - `store_source`'s write is fixed (`aead40ea84`, R876): a row every graph shares is read first
      and written only where it is missing or changed, and the spike's full arm ran with no failure
      against 35 before. Two cold captures finding one shared row missing at once still race; that
      and the rest of the way to parallel captures is R876's "Each gatherer commits its own
      consistent boundary", whose first two nodes are statements to settle before any code. Fewer
      stores waits on that branch and on `sql_name_matched_key_column`.
23. **The coverage agent instruments only `no.sikt`.** Done 2026-11-27. CI's build job runs
    `-Pcoverage` on every PR and trunk push, and the agent, given no includes, instrumented every
    class a test fork loaded, H2's planner, jOOQ and graphql-java among them. `graphitron-model`'s
    tests on the 4-core sandbox at `5bc97d383a`: 17:15 with no agent, 1 h 45 min under the agent as
    it was (every slow class about seven times slower), and 17:26 with
    `<include>no.sikt.*</include>`. CI's model step had grown to 63 to 66 minutes of a 96-minute
    reactor step on 11-26, so the estimate is 45 to 55 minutes off every CI run; the local
    verification build, which runs no agent, is unchanged. Every report analyses only its own
    module's classes, all under `no.sikt`, so no figure moves. `check-coverage-agent-wiring` now
    holds both halves: every `prepare-agent` execution carries the include, and no module's
    sources or generated packages leave `no.sikt`, which would otherwise report as uncovered.
    For Alf and Filip, not a step: step 20's 11.1 and 21.0 minute tier reruns were measured under
    the unrestricted agent, so the nightly decision rests on figures this changes.
24. **The agreement oracles compare a batch of graphs per statement.** Refuted 2026-11-27.
    `DetailRuleAgreementTest` and `StageAnswerAgreementTest`, among the model's slowest classes at
    about 640 and 220 s, compare one graph per statement, about 6,400 and 221,000 statements over
    the population. Prediction: batching graphs by `GRAPH_NAME IN` removes most of that. Priced in one
    JVM at `bb5c79d150` over the warmed population (454 graphs, 18 stores), each form twice, every
    form finding the same 1,350 rows:

    | form | detail rules | stage answers |
    |---|---|---|
    | per graph, as now | 5.7, 4.3 s | 122.9, 112.0 s |
    | 25 graphs a statement | 2.1, 2.9 s | 150.2, 146.4 s |
    | a store a statement | 0.6, 0.6 s | 169.9, 162.5 s |

    The detail rules' own comparisons are about 5 s: the class's 640 s is the population's warm,
    which took 1,018 s alone in that JVM. The stage answers are fastest per graph, as the class's
    `eachReading` says: a rule view over many graphs costs more than the statements it saves. The
    cost is the warm, which steps 18, 21 and 22 reach.
25. **Rule views out of the store's schema.** Open, a probe. Alf, 2026-11-27, leaning: a stage's rule
    moves out of `graphitron-model.sql` into a named SQL file the stage runs as `INSERT ... SELECT` or
    `MERGE`, and views are created only on an opt-in for debugging, which tests leave off. Test the
    hypothesis first. Measured on an empty store with `PopulationHeapProbe` at 0 documents, three
    arms: (a) every view; (b) the rule views `meta_stage_rule` registers dropped; (c) only those of
    them nothing but their stage reads, no other view and no main source naming them, dropped. Each
    reports the live heap and the view text dropped, in characters.
    - H4, the rule views are most of the parsed-view cost: (b) frees most of the ~40 MB step 21 put
      on the views, over 25 MB, and in proportion to the characters dropped, at about 30 bytes a
      character.
    - H5, most rule views are read by their stage alone: (c) frees at least two thirds of what (b)
      frees.
    - H6, a rule runs as well as a statement as it does as a view: one stage's rule text, run as
      `INSERT ... SELECT` against a store without its view, writes the same rows as the view-based
      write.
    - Measured 2026-11-27, `PopulationHeapProbe` at 0 documents (`heap.probe.views` `none`, `rules`,
      `lonerules`), one run each. An empty store is 62 MB live (70 against 8 before). `meta_stage_rule`
      registers 275 rule views, not 248, holding 1,283,455 of the schema's 1,370,197 characters of
      view text (94%). Dropping all 275 leaves 22 MB: 40 MB freed, 31 bytes a character, `h2-statements`
      falling from 24 MB to 1. Dropping the 271 that no other module's main source names (the four
      kept are named in `graphitron`, `graphitron-mcp` or `graphitron-lsp`) leaves 23 MB: 39 MB freed.
      No rule view is read by another view (`StageRuleGateTest` already holds that), so no drop was
      refused. H4 holds, H5 holds: (c) frees 97% of what (b) frees.
    - H6 holds for the one rule tried (`RuleStatementProbe`, `rule.statement.probe=true`):
      `graphitron_outbox_entry_defect_rule` over `defects/outbox-payload-defects`, its view dropped,
      run through `EntryDefects.derive` as a derived table over its DDL text, writes the same row as
      the view did. One row only, so it shows the shape works and not that every rule does. Two
      things the text needs that a view gives for free: its column list, since the body's own
      column names differ from the view's (`CREATE VIEW v (a, b) AS ...` puts names on positions),
      so a rule file carries that list or the body names its columns; and its `WITH`, which H2
      takes inside a derived table.
    - Typed through jOOQ's `ResultQuery.coerce(Table)` (3.20.11 has no `Table.coerce`, so a typed
      source is a fetch into `Result<TRecord>`, not a server-side `INSERT ... SELECT`): the same row
      again, once three things were done. `coerce` lines columns up by position, not by name, so
      the projection must name the table's columns in the table's order; this rule projects
      `..., code, detail, stage` against the table's `..., code, stage, detail`, and with equal counts
      the two `VARCHAR`s would swap silently. No rule states the mark, so `touched_at` is bound into
      the projection. And a fetched record is not touched, so it is marked before `batchInsert`, or
      the insert writes default values.
    - Every registered rule against the relation it writes, by name, order and type: 6 match
      exactly, 142 match but for `touched_at` in the same order, 57 but for `touched_at` in another
      order (the entry-defect rules, `stage` and `detail` swapped), and 101 differ otherwise. Of those,
      the table holds what the stage adds: a constant (`stage` 11, `priority` 28), or generated columns
      (the `_ref` columns, `path_head`, `coordinate`); or the rule holds what the stage reads to write
      something else (`table_name`, `table_schema`, `table_source_name` 17, `class_name`,
      `method_descriptor`). So a gate that a rule matches its table checks the rule's columns
      against the table's less its mark, its generated columns and the stage's constants, by name,
      and the 17 rules whose stage resolves a column in Java stay outside it until that resolution
      moves into the rule.
    - The write shape Alf ruled (2026-11-27): no `coerce` and no `T.fields()`, every column explicit
      on both sides, `insertInto(T, T.F1, ...).select(T.F1, ...)` over the rule's SQL text. Tried on
      the same rule (`RuleStatementProbe#theExplicitWriteShape`). jOOQ renders `T.F1` schema-qualified,
      `"PUBLIC"."T"."F1"`, so over a bare `table(ruleSQL)` and over `table(ruleSQL).as(T)` alike H2
      finds no such column. Two shapes resolve and write the view's row: `T.as("r")`, the generated
      table aliased so its typed fields render as `"r"."F1"`, selected from `table(ruleSQL).as("r")`;
      and `.as(T)` under `renderSchema(false)`. The first needs no setting, so it is the one. And
      `table(sql)` renders its text as it is, so the rule's text is parenthesised to stand as a
      derived table.
    - Parsed instead, `parseSelect(text).asTable("r", columns)`: 268 of the 275 rules parse, render
      back and run; 7 are refused by jOOQ's parser on an `IN` list (the outbox rule above among
      them). Parsing would also run jOOQ's rendering of a rule rather than its text, so the text goes
      unparsed through `table(...)`. The seven point at input parsed or classified at query time,
      which is an R876 node of its own.

A question for production rather than a step here: a bounded `ReadBudget` installs itself with
`SET QUERY_TIMEOUT` when a reader is minted, and that setting empties the statement cache of every
session on the database, the writer's included. The model suite runs it four times, so it costs the
build nothing; what it costs the language server and the tool server depends on how often they mint
a bounded reader, which nobody has counted.

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
not match is refused and captured afresh. Priced on 2026-11-17 from `StoreCostExtension`'s totals
over model and `graphitron` under `-T 1C`: the model captures the 363 documents once, about 110 s of
class time at 0.27 s a capture, on the population's shard threads; `graphitron`'s classes capture
the 192 documents they read again, about 50 s of class time, 15 to 25 s of wall; and
`generate-test-schemas` runs 144 scenarios in 59 s on one thread, capture and generation together.
Opening the files instead takes 40 to 65 s off `graphitron`'s chain, less the opening, which is not
measured. It removes work rather than moving it: warming the population on threads of its own from
the JVM's start, measured the same day, cut the time classes spent waiting from 393 to 179 s and left
the module's wall where it was, the classes' own work slowing by what the waiting fell.

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

`GraphitronStore.captured`, the call a run starts from, counts as capturing directly (Alf,
2026-11-13): a test driving a whole run takes its captured graph from `CapturedGraphs.open`, a lease
whose store the `FactGraphs` population owns.

**A5. H2's view-index cache of 64.** In the 2026-11-16 counted run, stores reused across captures
prepared the type-backing statement 51 to 97 times at about 1 s each, and a thread-confined store
paid it again on every case. The statement caches are not the cause: a capture prepares 700 to 3,900
distinct statement texts, so its statements leave a 1,024-statement cache between captures anyway,
and planning one again costs milliseconds. What H2 keeps per session is the compiled index of each
view, in a `SmallLRUCache` of `Constants.VIEW_INDEX_CACHE_SIZE`, a constant 64 in h2-2.5.250 that no
property raises. A capture reads hundreds of rule views, so the type-backing view is evicted within
the capture and compiled again by the next. Measured on an empty store built from the DDL, one
session, preparing `graphitron_type_backing_rule` again after N other rule views:

| Prepared after | statement cache 1,024 | statement cache 0 |
|---|---:|---:|
| nothing (first) | 931 ms | 1,062 ms |
| itself | 0.1 ms | 0.3 ms |
| 40 other rule views | 0.0 ms | 0.1 ms |
| 80 other rule views | 0.0 ms | 1,204 ms |
| 160 other rule views | 0.0 ms | 875 ms |

The thread-confined store's clear is not the cause either: its truncate of every base table under
the referential-integrity toggle, the selectivity reset, `ANALYZE`, commits and row writes each leave
the next prepare under a millisecond. Only the type-backing view, the one recursive rule, costs a
second to compile; the others cost 1 to 3 ms, so the eviction matters for it alone. Queue step 1
removes the cost.

**A6. A fresh store opens cheaply.** Set aside 2026-11-17: no longer a lever. Opening took 161 s
over 335 stores in the model's tests on 2026-11-15; the model now boots 142 stores a run and
`graphitron` 20. Timed over 20 opens in a warm JVM, a fresh in-memory store opens in 292 ms: views
106, tables 79, the 3,708 `COMMENT ON` statements 42, the rest under 20 each; a file-backed one in
459 ms, hashing the DDL twice. The comments stay, `StoreCatalog` reading them for the MCP's relation
descriptions. Run as one JDBC batch they cost 38 ms against 42; the DDL cannot run as one script,
H2 recursing once per remaining statement; hashing once and keeping the split DDL saves about 30 ms
an open; opening a copy of a fresh store's file costs 241 ms against 459. Over 142 boots the best of
these is about 4 s of class time.

**A7. A test's catalog is read once.** The catalog skip vouches a catalog by the stamp of the
classpath entry it is generated into. Tests that capture with no classpath pass no entry, so every
capture reflects and upserts the whole catalog again. Measured from a second capture's
`store_graph_write` rows for the `jooq` gatherer.

### Track B: use the machine

**B1. Build independent modules in parallel.** Done 2026-11-17, as a flag on the documented
verification command, `mvn install -Plocal-db -T 1C`, rather than `.mvn/maven.config`: `.mvn/` is
gitignored as the contributor's own, the web-session log redirect plans a file there, and
maven-invoker-plugin's nested builds under `graphitron-maven-plugin/target/it` would inherit it. CI
already ran `-T 1C`, so the gain is the local build's. Measured on trunk bfec416cae, one quiet run
each: 1,483 s serial against 1,378 s parallel, 105 s where 280 s was estimated. LSP, MCP,
roadmap-tool and docs left the chain, but `graphitron` slowed from 437 s to 545 s sharing the
machine with them, and they slowed too (LSP 105 to 174 s, roadmap-tool 50 to 136 s). The chain the
build now waits for is graphitron-model, graphitron, graphitron-maven-plugin and
graphitron-sakila-example, so time off those counts in full and time elsewhere only as it frees
the machine. The release deploy needs no pin, nothing setting `-T` for it.

**B2. Run `generate-test-schemas` in parallel.** `GenerateTestSchemas.main` loops over the
scenarios in one thread: 144 scenarios in 59 s on 2026-11-17, plus about 6 s of JVM start. Give it a
pool, or read the scenarios from A3's store. Most of the 59 s is the same capture A3 removes, so the
two do not add: either takes most of it, and the estimate of about 200 s is withdrawn. Tried 2026-11-17 and set aside: a pool of four took the step from about 71 to about 32 s alone, its
output byte for byte the serial run's, and under `-T 1C` from 151.3 to 101.5 s; but `graphitron`'s tests
then started beside the LSP's and roadmap-tool's and took 332.2 s against 292.1, so the module's span
went from 494.3 to 499.7 s and the build from 1,315 to 1,313 s. The work moved rather than left, and A3
is what takes it out.

**B3. Split the single-thread tails.**
- `EmittedRegistryAgreementTest` becomes one dynamic test per document, so the pool can spread it.
  Tried 2026-11-17 and not landed: the 63 documents ran about three wide, their times summing to
  413 s inside 133 s where the serial sweep took 130 s, since each comparison captures through the
  population's pooled stores and queues on a shard's one writing connection. `graphitron`'s wall went
  from 7:36 to 7:16, one run each, inside the noise. It is deleted with the walk under R876.
- `GeneratorDeterminismTest` is split by its inputs. `GraphCountGrowthGateTest`, which was paired
  with it here, went with step 13.

**B4. Run the invoker in parallel.** Done 2026-11-17: `parallelThreads` 4 on the invoker. Its thirteen
ITs ran one after another, 76 s of the chain; at four threads, interleaved pairs of
`verify -pl :graphitron-maven-plugin` went from 127.4 to 105.7 s, and under `-T 1C` the plugin's span
went from 141.8 to 119.6 s. Four IT Mavens peaked at 2,633 MB together, which a 16 GB runner holds.

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

**B7. Give the chain the machine while LSP, MCP and roadmap-tool run beside it.** Tried 2026-11-17
and set aside; do not try ordering again.
- *What was seen.* The build at a034cb5705 took 1,378 s against 1,316 s at 12a9c3a380, with
  `graphitron`'s span 611 s against 494. Standalone `clean verify -pl :graphitron` at 12a9c3a380,
  6f76a53544 and a034cb5705, interleaved, two runs each, gave 455, 460 and 457 s, tests 335, 335 and
  337 s and `generate-test-schemas` 76, 80 and 72 s: no commit moved it. In the reactor
  `generate-test-schemas` took 151 to 197 s, running beside LSP's and roadmap-tool's tests and MCP's
  docs index.
- *Order.* LSP, MCP and roadmap-tool depend on `graphitron-model` only; the plugin and docs depend on
  them. Starting them after `graphitron` takes a dependency the modules do not have, and Maven 3.9's
  builder has no priority to give instead.
- *Priority and caps.* Their forks and MCP's docs index were pointed at a wrapper `java`, one
  recorded `-T 1C` build per arm on one tree: control 1,360 s, `nice 19` 1,394 s, and
  `-XX:ActiveProcessorCount=2` 1,535 s (that run drifted about 100 s in `graphitron-model`, which it
  cannot touch). `generate-test-schemas` took 165, 161 and 171 s.
- *Why.* Not memory: in the reactor the JVMs' resident sets peaked at 12.4 GB of 30, with 98 MB
  swapped in over the step and none out, and GC paused 3.4 s. The step's own JVM did the same work
  in about 350 CPU-seconds against 205 standalone. The workstation's eight cores carry two hardware
  threads each and turbo above their all-core clock, so a sibling JVM on the paired thread, and the
  clock falling, slow each instruction; nothing waits, so priority has nothing to hand over, and a
  processor count only sizes pools.
- *Why it stops here.* Pinning the siblings to cores (`taskset`) or a temporary dependency could
  still win on this machine, but both tune one laptop's shape, which CI's four vCPUs and other
  developers' machines do not share. The gain left in that window is cutting work, which is A3.

### Track C: smaller statements (R876)

The planning that survives Track A is the cost of each statement's shape. H2 searches join orders
exhaustively up to seven tables and re-plans every nested view for each order it tries. The store's
grain ruling (`docs/architecture/explanation/fact-model.adoc` § "Every relation has a grain") is
therefore also this item's largest lever: no view reads a view, rule views stay simple, and a
derivation that would need a complex rule is stored upstream.

This item does not do that work. R876 does, under:
- "No view reads a view, and every rule view is simple, claimed by B";
- "A per-depth rule is planned once per depth";
- "The type-backing closure is stored, not recursed, claimed by B";
- the per-depth writer nodes F leads.

This item ranks them by measured weight, `PathNeeds` (20% of `graphitron`'s samples) and
`OccurrenceSources` (7%) first, and prices each one when it lands, using `store_graph_write.prepare_ms`
and a cold capture.

R876's subtractions also retire the comparisons against the walk: 364 s of `graphitron` class time,
302 s of it one test.

**C1. "H2 brute-force planning of big CTE views dominates test heap"** (filed 2026-11-14, B). The
same planning costs heap as well as time, and in `graphitron` the heap is what fails first. At trunk
`ac48c2b126`, `graphitron` alone at `-Xmx2g` dies at about minute 6, after 2,184 tests: the live heap
after a full GC climbs from 1.2 GB at minute 1 to 2.04 GB at minute 5, thirty full GCs a minute at
the end, and the OutOfMemoryError is H2 planning a CTE (`Optimizer.calculateBruteForceAll` into
`Plan.<init>`, from `CTE.createIndex` under `SelectUnion.preparePlan`). CI gives this fork about
4 GB (a public repository's `ubuntu-latest` has 16 GB and the module pins no heap), so it passes
there; `-Xmx2g` is the margin.

*Measured 2026-11-14 at `a9cb37b7c9`* (`graphitron` alone at `-Xmx2g`, class histograms each minute
from the surefire fork's own pid). The heap is held by parsed SQL, not by data: H2's
`Token$KeywordToken` and `Token$IdentifierToken` hold 500 MB at minute 1 and 790 MB by minute 8, of a
live heap of 1.2 to 2.0 GB, with as much again in `byte[]`. In H2 2.5.250 every parsed statement
(`org.h2.command.Prepared`) keeps its token list in `sqlTokens`, and parsed statements live in each
connection's query cache, which `StoreUse.LONG_LIVED` sizes at 1,024. That run died after 3,149 tests.
With `-Dgraphitron.queryCacheSize=64` the same run passes, 4,588 tests and no OutOfMemoryError, the
live heap at 0.7 to 1.3 GB and the tokens at 290 to 530 MB; but it took 29:41, against 10:01 for the
1,024 cache at `-Xmx4g` under other load. The cache count matters, and the size of what is cached
matters as much: at 64 entries a connection the tokens are still 40% of the heap, so a few cached
statements are individually huge. Smaller statements both plan better and cache fewer tokens (Alf,
2026-11-14), so the lever is the views, with the cache setting chosen after. R876 took
`graphitron_entry_defect_rule` first, 87k characters in 65 branches, moving each into the rule view
of the stage whose facts it reads; the rule and its stage are gone.

*Measured 2026-11-15 on the first step of that.* With 60 of the rule's 65 branches moved into the rule
views of the stages whose facts they read, every view they read replaced by stored relations, and
the statement cache left at 1,024, `graphitron` alone at `-Xmx2g` passes: 4,588 tests, no
OutOfMemoryError, where the same trunk died at 3,149. The live heap after a full GC stays at 0.9 GB
for the first two minutes and 1.4 to 1.6 GB after, against 1.2 to 2.0 GB; the fork ran 46 full
GCs costing 35 s of 776 s, against 331 costing 306 s of 1,058. So the view, not the cache size, was
what held the heap, and the cache keeps its 1,024.

*Measured 2026-11-25: deep unions.* `graphitron-model`'s fork, capped at 4,096 MB, ran out of heap
twice once four arms were added to `graphitron_occurrence_depth_entry_defect_rule`. A heap dump of
it (MAT over every `org.h2.command.Prepared`, 190,457 of them, 78.1 M tokens in 58 sessions) showed
why: H2 parses a chain of `UNION ALL` arms left-deep, and every level of the nesting is a parsed
statement holding the tokens of every arm beneath it, so a chain of n arms keeps about n²/2 arms of
parsed text per session holding its plan. The depth rule alone, 31 arms in 76 copies, held about a
quarter of all tokens. That made deep unions a rule of the fact model (fact-model.adoc's views
section: no view nests a union deeper than seven, gated by `ViewComplexityTest`), and the eighteen
views over it were split into their natural families or bracketed as a balanced tree. The fork's live
heap after a full GC went from a mean of 3,388 MB and a peak of 4,024 MB (142 full GCs) to 2,354 MB
and 3,036 MB (27 full GCs).

Six of those views were bracketed rather than split, each being one statement its writer or reader
takes whole: `graphitron_field_minted_candidate_rule`, `graphitron_occurrence_taken_rule`,
`graphitron_occurrence_node_id_rule`, `graphitron_occurrence_condition_parameter_rule`,
`graphitron_join_path_step_rule` and `lint_violation`. Whether splitting them by family would pay off
was measured before deciding. Method: a private copy of opptak RC58 (graph `opptak-subgraph`), the
views from the built DDL in both forms with identical rows (checked by `EXCEPT` both ways), the
database held open by one connection and a fresh session per statement; times are the median of five
runs after a discarded first, on an idle machine; the plan heap is a separate pass after filling the
page cache, the heap a session holds after preparing. `taken`'s filter-term arm ran over an empty
relation, RC58 predating it.

| View | Form | Statements | Plan heap, MB (sum / largest) | Prepare, ms | Execute, ms |
|---|---|---|---|---|---|
| minted candidate | balanced | 1 | 0.5 | 12 | 11 |
| minted candidate | split | 4 | 0.4 / 0.2 | 15 | 9 |
| taken | balanced | 1 | 0.3 | 6 | 89 |
| taken | split | 4 | 0.2 / 0.1 | 11 | 168 |
| node id | balanced | 1 | 0.8 | 68 | 72 |
| node id | split | 3 | 0.7 / 0.4 | 76 | 148 |
| condition parameter | balanced | 1 | 1.4 | 50 | 166 |
| condition parameter | split | 2 | 2.5 / 1.3 | 90 | 163 |
| join path step | balanced | 1 | 0.8 | 26 | 37 |
| join path step | split | 3 | 1.0 / 0.6 | 9 | 40 |
| lint violation | balanced | 1 | 0.9 | 7 | 275 |
| lint violation | split | 3 | 0.9 / 0.5 | 7 | 221 |

The step rule's real reader, `Steps.position`, run per position per depth, took 34 / 114 ms balanced
against 69 / 249 ms with a query per family and 47 / 131 ms with the families unioned in the read, at
position 0; `LintFindings`' read was 6 / 210 ms balanced against 8 / 225 and 9 / 252. A split also
plans a shared `WITH` once per family (the condition parameter rule's recursive one doubles its
prepare) and turns one write into two to four.

The conclusion (Alf agreed, 2026-11-25): all six stay balanced. The cost was left-deep nesting, which
balancing removes; a plan this size gains nothing from being split. Family splits are for clarity in
defect rules, where a family names what it judges, not for performance.

*Profiled 2026-11-15, `932638c2b7` (before the dissolve) against `924c0f0341`* (the dissolve and the
first view-over-view steps, with every other session's commits between). The machine was otherwise
idle, load 2 to 7. `graphitron` alone at `-Xmx4g`, 4,590 tests on both: H2's parse tokens peak at 818
MB before and 536 MB after (a third less), the live heap at 2.06 GB and 1.64 GB, with 14 and 12 full
GCs and wall time 9:21 and 9:30, so at CI's fork size the collector costs neither side anything.
Cold sis captures, three a side interleaved, medians with the range: the graphitron stages prepare
in 2,139 ms (2,076 to 2,141) before and 2,343 ms (2,329 to 2,419) after, and execute in 37.2 s (36.1
to 38.3) and 28.4 s (28.0 to 29.3). So the smaller statements kept their promise on heap and broke it
on planning time, which more and smaller statements sum to more of. The execution gain is not
statement size: 6.7 s of it is `graphitron_type_backing_rule` reading the stored
`graphitron_code_reference` where it climbed a seven-join view three times, the dissolve itself nets
about 1.7 s (3.5 s gone, 1.8 s back in the stages that took its branches), and a stored call fill
another 0.1 s. Execution pays where a rule stops deriving again what a stage could store. The
oversized rule views cost heap and planning, not time: `occurrence-depth-defects`, holding the 40k
character rule, runs in 0.9 s, while `occurrence-depths` takes 9.4 s, `fetchers` 6.2 s and
`occurrence-bindings` 3.6 s. `occurrence-depths` grew 1.1 s in the span, both from one rule's window
rewritten as a correlated `NOT EXISTS`, six times slower on sis until the priority it compares is
stored.

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
