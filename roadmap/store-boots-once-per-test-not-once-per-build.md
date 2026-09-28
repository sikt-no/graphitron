---
id: R768
title: "Capture the classification corpus once per test JVM, and stop graphitron-lsp booting a store per fixture"
status: Ready
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-09-28
---

# Capture the classification corpus once per test JVM, and stop graphitron-lsp booting a store per fixture

## Goal

A contributor's full build spends most of `graphitron`'s test time re-deriving the same facts. Seven
test classes each sweep the whole classification corpus: they capture it (the 60 SDL documents under
`graphitron-model/src/test/resources/corpus`, each one worked example of a classification verdict)
into a private fact store, and those seven classes alone account for 4618 s of the module's 15427 s
of test-class time. Six of them only read what they captured. When this item lands, those six read
one copy of the corpus captured once per test JVM into a shared read-only store, and
`graphitron-lsp`, the one module whose fixtures still boot (open and lay out the schema of) a fresh
store per case, boots one store per test thread the way `graphitron-model` and `graphitron-mcp`
already do. The outcome a contributor sees is a shorter
`mvn install`, measured per module against the figures below; the tests themselves assert exactly
what they assert today.

## What the tree looks like now

Three terms first. A *boot* is opening a fact store: connecting to a fresh in-memory H2 database and
executing the fact schema's DDL. A *capture* is filling a booted store from inputs: `ModelCapture`
reads SDL, a jOOQ catalog and a classpath census into fact rows and ends by running the *derivation
stratum*, the ordered list of SQL steps in `DerivationStratum` that computes derived relations from
those rows. A *population* is what one capture produces; two captures with the same inputs produce
the same population, since the stratum is deterministic over the rows it reads.

**The boot count has mostly been solved already, by the shared harness.** R769 landed
`ThreadConfinedStore` in `graphitron-model`'s test sources: one store per test thread, booted once,
emptied between cases by `TRUNCATE` and verified empty by a whole-base-table census
(`ThreadConfinedStore.verifyCleared`). `CapturedStore`'s ordinary factories (`of`, `ofCatalog`,
`ofFiles`, `ofRefusedSchema`) borrow that store rather than booting one, and `CapturedStore` is what
`graphitron` and `graphitron-mcp` reach the store through. So both of those modules already boot once
per test thread. Only the `ownStore*` factories boot per call, and they are meant for cases that
issue DDL or otherwise cannot share.

**`graphitron-lsp` is the exception.** Its `StoreFixture` builds every arm on `CapturedStore.ownStore`,
`ownStoreOfCatalog` or `ownStoreOfFiles`, so each of its 135 `StoreFixture.of*` call sites
boots a dedicated store. Its only borrowing arm is `ofRefusedSchema`. The module runs under
`graphitron-model`'s `junit-platform.properties`, which it inherits through the test-jar (classes
concurrent, `fixed.parallelism=4`), so the thread-store mechanism applies to it unchanged.

**In `graphitron`, the cost is one population captured seven times.** Each of these classes has a
sweep that captures every `CorpusDocuments.documents()` entry as its own graph into one store, then
compares a derived relation against the legacy producer:

| Class | Class time | How it captures |
|---|---|---|
| `ConditionMembershipShadowTest` | 882 s | `ofCatalog` then `andCatalogGraph`, passing `census()` and `testClassRoot()` |
| `InputOccurrenceShadowTest` | 814 s | `ofCatalog` then `andCatalogGraph`, bare `jooq` |
| `DemandShadowTest` | 654 s | `ofCatalog` then `andCatalogGraph`, bare `jooq` |
| `CorpusExpectationTest` | 635 s | the same, held in a `static CapturedStore` from `@BeforeAll` |
| `ColumnMatchShadowTest` | 552 s | `ofCatalog` then `andCatalogGraph`, bare `jooq` |
| `WriteRefusalShadowTest` | 550 s | `ofCatalog` then `andCatalogGraph`, bare `jooq` |
| `RoutineSpentInputShadowTest` | 531 s | `ofCatalog` then `andCatalogGraph`, bare `jooq` |

The class times include each class's other, small single-SDL cases and the legacy-producer half of
each comparison (the producer run per document through `TestSchemaHelper`), so 4618 s is an upper bound on what
sharing the capture can remove, not a projection. The capture tuples differ in two ways. The prelude
is applied in three spellings: `fullSdl` (ConditionMembership, ColumnMatch, WriteRefusal) and `full`
(`CorpusExpectationTest`) read `CorpusDocuments.prelude() + "\n" + sdl`, and `preluded` (Demand,
InputOccurrence, RoutineSpentInput) does the same and also appends `interface Node` when the text
lacks one, which is a no-op for corpus documents because `_prelude.graphqls` already declares
`Node`. And `ConditionMembershipShadowTest` is asymmetric: its first graph is captured with
`census()` and `testClassRoot()`, so a real classpath is scanned and `captureFiles` skips its stated
census, while graphs 2 to 60 get `census()` and no class root. `CorpusFragmentTest` (695 s) and `OutcomeBlockRendererTest` (233 s) are a second, different
shape over the same corpus: `OutcomeBlockRenderer.render` captures one document at a time and then
runs a generator over it through `GraphitronStore.captured`, so it is a per-document population and
it builds output, not only facts.

**`graphitron-mcp` has little left to take.** Its `StoreFixture` already uses only borrowing arms, and
its classes that capture sum to about 35 s. Its expensive classes (`GraphitronMcpServerTest` 305 s,
`CatalogSearchIndexTest` 76 s, the ONNX classes) are not store-bound as far as this item can tell, and
the ONNX ones already carry `@Tag("slow")`.

**Where these figures come from, and one that must not be used.** All class times are the surefire
XML reports of one full reactor install on 2026-09-28 (after `837069757`, 14 cores, modules
overlapping), so every figure carries in-reactor contention; `CatalogCorpusTest` ran 31 s alone and
68 s in that build, which is the size of the effect. The same build's `-output.txt` files are **not**
a usable capture count. Classes run four at a time, and a log line lands in the redirect of whichever
class the writing thread is attributed to: `ConditionMembershipShadowTest` performs 60 captures and
its file shows one `derivation stratum done in` line. The earlier count of 1327 captures in
`graphitron` was taken that way and is withdrawn; the counter under Implementation replaces it.

Prices measured in isolation, which stand: a warm DDL boot is about 0.5 s (4400 statements); an
emptying reset of a booted store is about 7 ms; one small capture spends about 3 s before the stratum
and about 1 s inside it.

## Implementation

### A capture counter, before anything else

`FactStores.boots()` counts boots; nothing counts captures, and the log-based count is broken. Add a
monotonic counter beside it in `graphitron-model`'s test sources, incremented where every
`CapturedStore` capture passes: the private `captureFiles` tail of the factories and `and*Graph`/
`recapture*` methods, and the public `CapturedStore.capture` primitive that `PipelineCapturedStore`
uses. It does not see captures made through `GraphitronStore.captured` (the generator's own path,
used by `BuiltStore` and `OutcomeBlockRenderer`); name that gap in the baseline rather than widening
the counter into production code. Add a JUnit extension,
registered for the three modules (autodetected through `META-INF/services`, switched on in
`graphitron`'s own `junit-platform.properties` and in `graphitron-model`'s, which `graphitron-lsp`
and `graphitron-mcp` inherit through the test-jar), that records captures and
boots per test class by differencing the counters around the class. It writes one line per class to
a file named by an environment variable and does nothing when the variable is unset, so it costs
nothing in an ordinary build. With classes concurrent, attribute by thread (the counter keeps a
per-thread tally alongside the global one) rather than by wall-clock interval. That attribution is
close, not exact, because the pool can lend a blocked thread to another class's task; the module
totals are exact.

Take the baseline with it, per module and alone (`mvn test -pl :<module>`), before either change
below, and record it in this item. The saving is judged against that baseline, not against the
in-reactor class times above.

### The shared corpus store in `graphitron`

A new public harness in `graphitron-model`'s test sources beside `CorpusDocuments`, `CorpusStore`:
one in-memory store per test JVM, booted and filled on first use with every corpus document captured
as its own graph (graph name = `Document.id()`, SDL = `CorpusDocuments.prelude() + "\n" + sdl`,
against the generated jOOQ catalog), and never closed. The shape is already in the tree: `graphitron-lsp`'s
`BundledVocabulary` is a lazily captured, JVM-lifetime, never-closed fixture, and this is the same
lifetime with the capture moved to the harness level. It is built on `CapturedStore`'s existing
arms, not beside them: the borrowing `ofCatalog`/`andCatalogGraph` would put the corpus on the
thread's store and the next borrow would empty it, so it starts from `ownStoreOfCatalog` and adds
graphs with `andCatalogGraph` on that owned handle.

What the implementer pins, in this order:

* **The population key.** Converge the three prelude spellings on one; the difference is a no-op for
  corpus documents, as above. The class-root asymmetry is load-bearing and is settled by reading
  `ConditionMembershipShadowTest`'s assertion: either show the other five sweeps' assertions hold
  over the population whose first graph scanned `testClassRoot()` and use that one, or keep two
  populations behind the same harness keyed by that difference. For the second arm, add the missing
  `ownStoreOfCatalog(..., census, classRoot)` overload beside the existing ones rather than writing
  the capture by hand.
* **Reads go through a reader, never through the capturing connection.** Classes run four wide, so
  the harness never hands out the `CapturedStore` or its `dsl()`. It hands out only a `StoreReader`
  per call: `GraphitronModelStore.reader(ReadBudget)` connects to the same named in-memory database
  (`jdbc:h2:mem:graphitron-model-<uuid>;DB_CLOSE_DELAY=-1`), so a second connection sees the capture.
  Initialization completes, capture and stratum included, before the first reader is issued, under
  the holder-class idiom or an equivalent that makes the happens-before explicit.
* **The store is read-only, and each way it could stop being so has an enforcer.** The primary one is
  already in the tree: `StoreReader.read` runs its query in a transaction and always rolls it back,
  so a row written through a handed-out reader never lands. Two things get past a rollback, and the
  guard aims at those: DDL, which H2 commits implicitly (taking any earlier DML in the transaction
  with it), and a `DSLContext` smuggled out of `read()`, which then runs on autocommit. The harness
  records the set of base tables and the whole-base-table row census (the single `UNION ALL`
  statement `ThreadConfinedStore` memoizes) when the capture finishes, and re-checks both at every
  handout, failing with the relation names that changed. Comparing the table *set* matters: a census
  keyed by the boot-time tables sees a dropped table but not a created one. Equal counts do not prove
  equal content on a populated store, so an `UPDATE` through a leaked context is not caught; that
  residue is accepted and named in the harness's javadoc, since it needs a context deliberately
  kept past `read()` and the rollback covers every ordinary use.
* **`CorpusExpectationTest` stays off `CorpusStore` and moves to an owned store.** It builds a
  `CREATE LOCAL TEMPORARY TABLE` and fills it in `@BeforeAll`, then reads it across its cases; on a
  reader the table would survive but its rows would be rolled back at the end of the `read()`. It
  also has a latent defect today: it captures with the borrowing `ofCatalog` and caches
  `captured.dsl()` in a static, which skips `CapturedStore.mine()`, so another class's borrow on the
  same thread would empty its store without an error. Move it to `ownStoreOfCatalog`. The saving
  from sharing is therefore six sweeps, not seven.
* **Migrate the six sweeps.** Each corpus sweep reads `CorpusStore` instead of capturing; its other,
  single-SDL cases keep `CapturedStore` untouched.
* **`StoreFixtureGuardTest`'s `HOMES` gains `CorpusStore`**, since it stands a store up by design.

### `graphitron-lsp`'s `StoreFixture` borrows the thread's store

Move each `StoreFixture` arm from the `ownStore*` factories to the borrowing ones, which is the whole
of what R769 did for `graphitron-model`, with the three kinds of exception named and kept on
`ownStore*`:

* cases that execute DDL: `StoreFixture.makeRunaway`, used by `StoreOutOfBudgetTest`, installs a
  non-terminating relation, and a case on the thread's store must not execute DDL because the clear
  list is derived once at boot;
* every fixture that outlives its case: each `@BeforeAll`-held or static `StoreFixture`, and
  `BundledVocabulary`, which holds one for the JVM. A borrow clears the thread's store, and the
  generation check in `CapturedStore.mine()` fires only on a call that goes back through the
  fixture's `dsl()` or `reader()`. A `StoreReader` already minted, the three readers
  `StoreFixture.access` mints into a `StoreAccess`, and a cached `DSLContext` do not re-check, so a
  long-lived fixture on the thread's store would read an emptied or refilled store without an error.
  The rule is therefore by lifetime, not by audit of call-backs: only fixtures opened and closed
  inside one case move to borrowing arms, which is the try-with-resources shape (`TypeReferencesTest`,
  `ValidatorDiagnosticsTest`, `ReferenceCompletionsTest`, `LintQuickFixTest`, `TableCompletionsTest`
  and others). A `StoreFixture` factory that serves both lifetimes needs the choice made at the call
  site, not inside the factory;
* anything whose subject is the store's lifecycle. None was found in `graphitron-lsp`; its direct
  `FactStores.inMemory()` uses (`RejectionSeverityCoverageTest`, `SdlDeprecations`) host a writer and
  can move to the thread store or stay, at the implementer's judgment.

The criterion R769 used holds: whether the boot or the schema's shape is the test's *subject*
rather than its setup. Derive the exception list from that at pickup rather than from this paragraph.

### Deliberately out of scope

`CorpusFragmentTest` and `OutcomeBlockRendererTest` capture one document and run a generator over
it. Whether the render can read its facts from `CorpusStore` instead of recapturing is a question
about `GraphitronStore.captured` taking a store it did not fill, and that is a change to production
code for a test's benefit. Measure its share with the counter; if it is large, file it as its own
item. `graphitron-mcp` gets the counter and its baseline and nothing else unless the baseline shows
a population worth sharing.

## Tests

* **Captures per build, pinned.** The counter's per-module totals become an assertion in the style of
  `ThreadConfinedStore.BOOT_BUDGET`: in `graphitron`, the corpus is captured exactly once per
  population per test JVM, pinned as an equality between `CorpusStore` initializations and the
  number of populations it keys rather than against a literal.
* **Boots in `graphitron-lsp`, pinned.** Boots equal distinct booting threads plus the named
  `ownStore*` exceptions, as R769 pinned it for `graphitron-model`. A literal of 4 is the wrong
  expectation: the fixed pool adds compensation threads when a task blocks.
* **The shared store is unwritten.** `CorpusStore`'s handout check is always on, as `verifyCleared`
  is, and a test in `graphitron-model` shows each arm fires with a write that gets past the reader's
  rollback: create a table through a handed-out reader and assert the next handout fails naming it,
  and insert a row through a `DSLContext` kept past `read()` and assert the next handout fails naming
  that relation. A third case pins the primary enforcer: an insert inside `read()` leaves the census
  unchanged.
* **`CorpusExpectationTest` owns its store.** It captures through `ownStoreOfCatalog`, so no borrow
  by another class can empty it.
* **Nothing the tests assert has changed.** Every migrated class passes unchanged in its assertions;
  the diff to each is its store acquisition only.
* **The saving, measured.** Each module run alone before and after, with the counter on, recorded in
  this item at In Review: captures, boots, test-class time and wall clock. The Done gate reads these,
  not the in-reactor figures above.

## Other solutions we've considered

* **`FactStores.perClass()`.** A store per class, shared by its cases. It reaches the wrong grain:
  the repeated population is shared across seven classes, and each sweep is one case, so a per-class
  store saves nothing for them.
* **A prebuilt template store on disk, copied and opened per class.** It skips the DDL and the stratum
  but floors at the catalog rebuild H2 does on every open, 171 ms measured for an empty template and
  more for one holding the corpus's rows, and it would still copy seven times what can be read once.
  It remains the right lever for the lifecycle-subject classes (`PersistentStoreTest`,
  `WarmStartRefreshTest`), which neither change here reaches; that is a separate item if their cost
  justifies one.
* **Capturing the corpus's graphs in parallel into separate stores.** It shortens the first class's
  wait but multiplies memory by the width and does not reduce the work; revisit only if the one
  shared capture turns out to sit on the module's critical path.

## Reviewer findings

### Round 1 (Spec → Ready): signed off

Both gate questions pass. Goal: a contributor's `mvn install` gets shorter because six corpus sweeps in `graphitron` read one JVM-lifetime capture instead of each capturing the corpus, and `graphitron-lsp`'s per-case fixtures stop booting a store each; no assertion changes. Fit: it extends `ThreadConfinedStore`/`CapturedStore`'s owned and borrowed arms, `BundledVocabulary`'s lifetime and `StoreReader`'s rollback rather than standing beside them. Corrected in passing: the corpus holds 60 documents (61 counted `_prelude.graphqls`), and `graphitron-lsp` has 135 `StoreFixture.of*` call sites.

Non-blocking, for the implementer:

* `StoreFixtureScanner` recognises a harness by the identifier `GraphitronModelStore` in code. A `CorpusStore` built only on `CapturedStore.ownStoreOfCatalog` and `reader()` may never spell it, and then its `HOMES` entry fails `everyDeclaredEntryStillDescribesSomething` as stale. Add the entry only if the file names the type.
* `TestSchemaHelper.buildBundle`, which `DemandShadowTest` and `InputOccurrenceShadowTest` call per document, also captures: an `ownStore` capture per distinct text, memoised JVM-wide in `EMITTED`. The counter will count those 60, and `CorpusStore` does not remove them. Say so when reading the baseline.
* Switching autodetection on in `graphitron-model`'s `junit-platform.properties` goes against that file's comment, which declines to set it. The same comment says `graphitron-lsp` and `graphitron-mcp` do not carry the file, but they do, through the test-jar. Rewrite the comment in the same change.
