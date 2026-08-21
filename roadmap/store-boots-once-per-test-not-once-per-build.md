---
id: R768
title: "The build boots the fact schema 1051 times, and a reset costs a fraction of a boot"
status: Backlog
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The build boots the fact schema 1051 times, and a reset costs a fraction of a boot

A full `mvn install -Plocal-db` executes the fact schema's 1894 DDL statements **1051 times** and
spends **395.8 seconds** inside them. The build's sequential wall clock is 339 seconds, so the boots
outweigh the build: they are about **45% of all test-class CPU** in the four store-heavy modules, and
roughly **80 seconds of wall clock**, a quarter of the build. Nothing else measured in this reactor is
close. The lever is that almost none of those boots need to be boots: emptying every clearable table
costs **0.85 ms**, and a full reset including re-materialization costs **9.3 ms**, against a **138 ms**
boot.

This is the count question, deliberately scoped out of R759, which cut the per-boot cost instead. R759
is already banked here: these figures were taken after its alias removal, on a tree whose DDL no longer
compiles Java at boot.

**One of the four modules has since shipped.** R769 took `graphitron-model`, 420 boots and 133.0 s of
the totals above, and landed it as one store per test thread cleared between cases. Every count and
every table in this item is the four-module measurement as taken on 2026-08-20 and is left that way
deliberately, a dated measurement rather than a sighting to refresh; the live remaining scope is the
other three modules, **631 boots and 262.8 s**. What that slice settled, and the one place it does not
generalise, are folded into the bullets under "What a Spec pass has to settle" rather than restated
here.

## The measurement

One 4 vCPU, 15 GB sandbox, warm local repository. The counter is an env-guarded `AtomicLong` pair
around `GraphitronModelStore.create`, incremented per completed schema application and dumped by a
shutdown hook, so it counts what actually ran rather than what a call site suggests. Each module was
run alone with `mvn test -pl :<module> -Plocal-db`, so these are per-module totals under that module's
own test parallelism.

| Module | Boots | Time in DDL | Per boot | Module wall clock | Module class-time sum |
|---|---|---|---|---|---|
| `graphitron` | 348 | 146.5 s | 421 ms | 78 s | 380.2 s |
| `graphitron-model` | 420 | 133.0 s | 317 ms | 46.8 s | 182.3 s |
| `graphitron-lsp` | 188 | 90.5 s | 482 ms | 35.6 s | 231.4 s |
| `graphitron-mcp` | 74 | 18.8 s | 254 ms | 26.9 s | 82.1 s |
| `graphitron-sakila-example` | 16 | 4.9 s | 303 ms | 88 s | 38.8 s |
| `graphitron-maven-plugin` | 5 | 2.1 s | 423 ms | 32.7 s | 15.5 s |
| **total** | **1051** | **395.8 s** | | | |

Two readings of that table matter.

**The cost is concentrated in four modules and it is most of what they do.** Boots are 389 s of the
876 s of test-class time those four spend, 44%. `graphitron-lsp` is the extreme: 90.5 s of DDL in a
module whose entire class-time sum is 231.4 s.

**The per-boot figures are higher than a boot costs alone**, 254 to 482 ms against 138 ms measured
solo, because those modules run four test threads on four cores and a boot under contention takes
longer. That is not an artifact to correct for. It is the cost as actually paid.

The two low rows are the counter-evidence that keeps this honest: `graphitron-sakila-example` performs
16 boots for five `graphitron:generate` executions and a handful of tests, and
`graphitron-maven-plugin` 5. Consumer-facing generator runs are already frugal with boots. This is a
test-fixture problem, not a product one.

## What a reset costs instead

Measured against a booted in-memory store, 20 rounds, warm JVM:

| Operation | Cost |
|---|---|
| `GraphitronModelStore.open()`: connect, 1894 DDL statements, stamp, derive | **138.0 ms** |
| `TRUNCATE` across all 143 clearable tables, referential integrity toggled off and back | **0.85 ms** |
| `MaterializeDependencies.populate` after the clear | **8.41 ms** |
| reset total, clear plus re-materialize | **9.26 ms** |

So a reset is **15x** cheaper than a boot on the conservative reading, and **163x** cheaper if the
re-materialization turns out to be unnecessary. **That question is settled, in the direction that
makes this item cheaper**, and the open-questions section below carries the argument:
`MaterializeDependencies.populate` reads the registry and the catalog's stored view definitions and
no fact relation, so nothing a test writes and nothing a clear removes can invalidate
`meta_materialize_dependency`. A reset is the 0.85 ms clear alone. Every saving figure in this item
was computed on the conservative reading and is therefore a floor. A booted store holds zero rows
outside `meta_` and `store_stamp`, which is what makes `TRUNCATE` on everything else the right shape.

R769 has since landed the mechanism in `graphitron-model`, which is this item's first slice, so the
remaining three modules adopt a proven mechanism rather than a proposed one. Measured there: 31
boots where there were 420, module test-class time 191.1 s to 65.2 s, test execution wall clock
30.9 s to 11.6 s. The delivered mechanism is the reference rather than that item's plan body, which
was deleted at its Done gate: `ThreadConfinedStore` in `graphitron-model`'s test sources carries the
reset's shape and why DDL is out of bounds for a case on the funnel, its `verifyCleared` carries the
leak guard's scope and the argument for asserting over every base table rather than over the clear's
own list, and its `BOOT_BUDGET` carries the two-part counter. Read those before scoping a second
module, and note that `FactStores` deliberately counts boots without holding any module to a budget,
because the three modules here still boot per case in the hundreds by design.

## What it would save, and what that estimate rests on

Removing the boots from those four modules' class time and holding their observed parallelism:

| Module | Class time now | Minus boots | Wall clock now | Projected | Saved |
|---|---|---|---|---|---|
| `graphitron` | 380.2 s | 233.7 s | 78 s | about 48 s | 30 s |
| `graphitron-model` | 182.3 s | 49.3 s | 46.8 s | about 14 s | 33 s |
| `graphitron-lsp` | 231.4 s | 140.9 s | 35.6 s | about 22 s | 14 s |
| `graphitron-mcp` | 82.1 s | 63.3 s | 26.9 s | about 21 s | 6 s |

About **80 seconds of a 339-second build**, and the estimate's weakness should be stated rather than
buried: it assumes the surviving work parallelises as well as the current mix does, and boots may be
the *most* parallel part of that mix (146 independent H2 databases contend on nothing but CPU). If so
the real figure is lower. It also assumes nearly every boot is replaceable, which the next section
says it is not. Treat 80 seconds as an upper bound with a floor well above every other candidate:
even at half, it beats the next-largest item.

For ordering against the alternatives, all measured on the same tree and hardware: R763 is 23 s
(making `graphitron-sakila-example`'s tests concurrent), R767 is up to 18.6 s
(`graphitron-maven-plugin`'s duplicate descriptor and sequential integration projects), R766 is 16.7 s
(the module's five sequential generate executions), and R759 was 42.7 s and is already spent.

## What a Spec pass has to settle

* **Which boots must stay boots.** Some tests have the boot as their subject: `PersistentStoreTest`,
  `WarmStartRefreshTest`, `FactCaptureAgreementTest`, and anything asserting on the DDL-hash directory
  segment or the `store_stamp` integrity check. Those keep booting, and naming them is the first task
  because the saving is computed net of them. **R769 supplies the criterion to name them by, and the
  warning not to pin the list.** The rule that did the work there is whether the boot or the schema's
  shape is the test's *subject* rather than its setup, which is what settles a class without arguing
  about it. The list itself drifted twice inside that item's own review, once when a new class landed
  on trunk mid-review and once in the recount it forced, so derive the set from the criterion at
  pickup rather than trusting a count written here.
* **The scope of a shared store, which is per thread and not per JVM.** These modules run four test
  classes at once. One store shared across four threads would need every fixture write serialized,
  which trades the boot cost for a lock. A store per test thread is the shape that keeps the tests
  independent: a boot per test thread rather than 420. **Both halves are now answered by R769 and one
  of them corrects this bullet.** A `ThreadLocal` does survive the worker pool, so the mechanism works;
  but "4 boots per module JVM" is the wrong expectation and asserting it would have failed. At
  `fixed.parallelism=4` that module booted **eight** stores on eight distinct threads, because
  `fixed` sizes a `ForkJoinPool` and the pool adds compensation threads when a task blocks, so the
  number of threads that ever run a test class is not the configured parallelism. Budget for a boot
  per booting thread, some multiple of the configured width, and pin the invariant as an equality
  between boots and distinct booting threads rather than against a literal.
* ~~**How a reset is proved complete, because the failure mode is silent.**~~ **Settled by R769, and
  reuse its answer rather than re-deriving it**, because the first two shapes tried there both failed
  review. The guard is always on rather than opt-in: the probe is a single `UNION ALL` census in one
  round trip, which costs a few milliseconds against the 133 s of boots it protects, and a
  nightly-only guard lets a bad case land for a working day, which is an invariant that has stopped
  failing when it breaks. It asserts the **whole base-table set** and not the clear's own list: after a
  `TRUNCATE` over exactly those tables "those tables are empty" is entailed, so a guard scoped to the
  clear's exclusion pattern cannot see that pattern being wrong, and the leak worth catching is a row
  surviving in a table the clear does not reach. And it is positive, asserting each table's boot row
  count rather than emptiness, so the tables a boot legitimately fills are covered too. The
  `INFORMATION_SCHEMA` requirement in this bullet held up, with a consequence worth carrying: because
  the partition is derived once when the thread boots, **a case on the funnel must not execute DDL**,
  or the clear names a relation the schema has since turned into something else. Budget for finding
  the DDL-executing cases in each remaining module before routing that module's funnel.
* ~~**Whether `MaterializeDependencies.populate` belongs in a reset.**~~ **Settled: it does not**, so a
  reset is 0.85 ms rather than 9.3 ms and the ratio against a boot is about 160x rather than 15x.
  `populate` derives its edges from `Materializations.registrations` and from the stored view
  definitions it reads out of `INFORMATION_SCHEMA`, and reads no fact relation, so no row a test
  writes can invalidate `meta_materialize_dependency` and clearing rows cannot either. Established by
  reading `populate` and by observing the relation byte-identical across a clear. Note the other
  derivation is a different thing and already per-case: `Materializations.refreshAll` does depend on
  fact rows, and every seeded case already calls it. Every saving figure in this item was computed on
  the conservative reading and is therefore a floor rather than a projection.
* ~~**Whether `StoreRefresh` is the seam or a new one is.**~~ **Answered for `graphitron-model`: a new
  one, and the parallel mechanism is deliberate.** `StoreRefresh` is package-private in `graphitron`,
  which depends on `graphitron-model` and its test-jar, so a call from that module's fixture would
  invert the module dependency, and its clear is a different question anyway: `prepare` takes a
  `FactSink`, a `ClasspathSources` and a class census and deletes what one capture run owns, scoped by
  `graph_name` and by crawled source. `StoreRefresh` answers "what does this run own" and
  `ThreadConfinedStore` answers "make this store look freshly booted". Note the reachability half of
  that answer is module-scoped and does not carry: `StoreRefresh` lives in `graphitron`, so for that
  module it *is* reachable and the question is live again there, on the contract rather than on the
  dependency direction.
* **Whether the fixture helpers can carry this without every test changing.**
  `SeededStore.withSeededStore` in `graphitron-model` is one funnel for 420 boots; if `graphitron`'s
  382 test classes reach the store through a comparable helper, the change is small, and if they each
  call `open()` directly it is not. That count decides whether this is one item or a staged one.
  **Answered for `graphitron-model` and shipped: R769 took that module**, where 159 call sites across
  30 classes funnel through the one helper and five classes boot directly because the boot is their
  subject. 420 boots became 31, no test class changed, and this item keeps the other three modules,
  631 boots and 262.8 s.

* **New, and it is the thing to settle first: `graphitron`'s harnesses are populated by a pipeline, so
  R769's mechanism does not carry over unchanged.** That module is this item's biggest row, 348 boots
  and 146.5 s, and its store fixtures are not a funnel of the `withSeededStore` shape.
  `StoreFixtureGuardTest` enumerates them: `CapturedStore` fills a store by driving `FactCapture` over
  SDL fixtures, `BuiltStore` by running a whole `buildOutput()`, and thirteen further test classes
  reach `FactStores` directly. The premise R769 rested on was that a body seeds its own rows cheaply,
  so handing it an emptied store is as good as handing it a booted one. These two harnesses instead
  hold content that, in their own javadoc's words, cannot be arranged but only produced, and clearing
  to booted-empty throws away the expensive part rather than the cheap one. So the arithmetic to
  measure here is not the one in this item's reset table: it is whether a captured or built store can
  be re-populated more cheaply than re-booted, and if it cannot, the lever for those classes is a
  store shared across the cases that want the *same* population rather than a clear between them.
  `FactStores.perClass()`, which landed separately, is that shape for a class whose cases share one
  fixture, and its boots are already counted. Settle this before scoping `graphitron`, because a plan
  that assumes one funnel per module is a plan for `graphitron-lsp` and `graphitron-mcp` and not for
  the module holding most of the cost.

## How to re-measure

The counter is the durable part of this pass and it is six lines. Add to
`GraphitronModelStore.create` an `AtomicLong` pair, incremented and accumulated around the statement
loop, plus a static shutdown hook that appends `<count> <millis>` to the file named by an environment
variable and does nothing when that variable is unset. Then per module:

```bash
mvn install -pl :graphitron-model -Plocal-db -Pquick     # install the instrumented model
GRAPHITRON_BOOT_COUNT_FILE=/tmp/boots.txt \
  mvn test -pl :graphitron -Plocal-db
awk '{c+=$1; t+=$2} END {print c" boots, "t" ms"}' /tmp/boots.txt
```

One boot per forked JVM writes one line, so the `awk` sum is the module's total. Revert the
instrumentation and reinstall before trusting any other measurement on the tree.

For the reset comparison, open one store through the public `GraphitronModelStore.open()`, list
`INFORMATION_SCHEMA.TABLES` for `BASE TABLE` in `PUBLIC` excluding `META\_%` and `STORE_STAMP`, and
time `TRUNCATE` over that list with `SET REFERENTIAL_INTEGRITY FALSE` around it, then
`MaterializeDependencies.populate` separately so the two halves stay attributable.
