---
id: R769
title: "withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-21
---

# withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms

When this lands, `graphitron-model`'s test suite stops executing the fact schema's 1894 DDL statements
420 times and executes them **four times**, once per test thread, resetting rows between cases
instead. The module's test-class time falls from about 182 seconds to about 49, its wall clock from
46.8 to roughly 14, and the full build loses about **33 seconds**. It is the first slice of R768, which
measured the same waste across four modules at 1051 boots and 395.8 seconds; this one takes the module
where the waste is the highest share of the work and where it flows through a single helper, so the
mechanism is proved once and cheaply before the other three modules adopt it.

Everything below restates the facts it needs rather than pointing into another item's body, because
those bodies are deleted when they reach Done.

## The measurement

One 4 vCPU, 15 GB sandbox, warm local repository. Boots were counted with an env-guarded `AtomicLong`
pair around `GraphitronModelStore.create`, incremented per completed schema application and dumped by a
shutdown hook, so the figure is executions rather than call sites. Reset and boot costs were measured
against a booted in-memory store over 20 warm rounds.

| | |
|---|---|
| boots in `mvn test -pl :graphitron-model -Plocal-db` | **420** |
| time inside the DDL | **133.0 s** |
| per boot, in situ, four threads on four cores | 317 ms |
| per boot, measured alone | 138 ms |
| module wall clock | 46.8 s |
| module test-class time | 182.3 s |
| **`TRUNCATE` across the clearable tables** (144 on 2026-08-21) | **0.85 ms** |

So boots are **73% of this module's test-class time**, and the replacement is **160 times cheaper**
than the thing it replaces. The module is the reactor's clearest case: `graphitron` spends more
absolute time booting (146.5 s) but over a much larger suite, and its boots do not funnel through one
helper.

Why the per-boot figures differ: 317 ms is a boot under four-way contention, which is the cost as
actually paid; 138 ms is a boot with the box to itself. The saving projection below uses the in-situ
total, because that is what disappears.

## The funnel, and it is one helper

`SeededStore.withSeededStore(Consumer<DSLContext>)` opens `FactStores.inMemory()`, which is
`GraphitronModelStore.open()`, in a try-with-resources that closes it when the body returns. Its
graph-anchoring overload delegates to it. **158 call sites across 29 test classes** reach it on
2026-08-21, and 420 runtime boots come out.

Why so many more boots than call sites, since the module declares no `@ParameterizedTest` at all:
most classes wrap the call in a private per-class helper and every `@Test` goes through it. The
extreme is `NodeIdInstructionTest`, whose 19 cases reach one `withSeededStore` site through
`withCatalog`. The module holds 423 `@Test` methods, so the boot count is very nearly "one per
case", which is the figure to re-derive at pickup rather than the site count.

Four classes in the module boot directly instead, at seven sites, and every one of them has the boot or
the schema's shape as its subject rather than as setup: `StoreReaderTest` (which also takes the
`fileBacked` and `reader()` paths), `MaterializationOrderTest`, `MaterializeRegistryGateTest` and
`CommentRenderabilityGateTest`. **They are out of scope and stay exactly as they are.** Naming them is
load-bearing: the saving is computed net of them, and a change that routed them through a shared store
would delete the coverage they exist for.

## What a reset has to do, and what it does not

A booted store holds rows in exactly two places outside the fact relations: the `meta_` family, whose
authored rows the DDL seeds with an `INSERT`, and `store_stamp`. A reset clears every other base
table (144 of them on 2026-08-21) and leaves those two alone. On a fresh boot every one of those is
empty, which is what makes `TRUNCATE` over the whole list the right shape rather than a curated one.
`meta_materialize` is the only relation the DDL inserts into, so "empty apart from the two" is a
property of the schema rather than a list to maintain. The count is derived from
`INFORMATION_SCHEMA` at run time and the DDL is edited most days; treat any number written here as
a sighting, never as a value to assert against.

**Re-deriving the dependency materialization is not part of a reset, and establishing that is what
makes this slice cheap.** R768 left this open as a 10x swing on its own premise, so it is settled here.
`GraphitronModelStore.open` calls `MaterializeDependencies.populate`, which costs 8.4 ms, ten times the
clear. But `populate` derives its edges from `Materializations.registrations` and from the stored view
definitions it reads out of `INFORMATION_SCHEMA`; it reads no fact relation. So no row a test writes
can invalidate `meta_materialize_dependency`, and clearing rows cannot either. Verified twice: by
reading `populate`, and by observing the relation byte-identical across a clear. A reset is therefore
the clear alone, 0.85 ms, and not 9.3 ms.

The *other* derivation is already handled and must not be confused with it. `SeededStore.derive`, which
every case calls once its rows are seeded, is `Materializations.refreshAll`, and that one does depend on
fact rows. It is called per case today and will be called per case after this change, so it is neither
new cost nor a reset concern.

## Implementation

**`withSeededStore` takes a thread-confined store from a `ThreadLocal` rather than opening one.** First
call on a thread boots and keeps it; every call resets first, then runs the body. The store is not
closed when the body returns, which is the whole change: an in-memory H2 dies with the JVM, so nothing
leaks beyond the fork. Under the module's four-thread class-level parallelism that is four boots per
surefire JVM.

**The reset is a new mechanism, and there is no existing one to extend.** It needs the table list
from `INFORMATION_SCHEMA` (base tables in `PUBLIC`, excluding `META\_%` and `STORE_STAMP`), `SET
REFERENTIAL_INTEGRITY FALSE` around the truncates, and the list computed once per store rather than
per reset.

`SET REFERENTIAL_INTEGRITY FALSE` is doing more here than waving off constraint checks, and an
implementer will meet a comment that reads as though this plan cannot work. `Materializations`'
javadoc says refreshes are `DELETE` rather than `TRUNCATE` because "H2 refuses to truncate a table
any foreign key references", which is true of every one of these 144 tables. The refusal is
conditional on the flag: H2 asks whether referential integrity is on before it declines, so
turning it off is what makes the truncate legal rather than merely unchecked. That is why the
measured 0.85 ms is a real number and not a projection. Restore the flag afterwards. Leaving it
off would not corrupt anything, the flag being database-wide and each thread's store being its own
database, but it would break every case that asserts a foreign key rejecting a row, and it should
break at the reset rather than there. The nearest existing use of the same idiom is
`WrittenStatementCoverageTest.relax` in `graphitron`, which relaxes a store the same way for an
unrelated reason and is not reachable from here either.

`StoreRefresh` is the class that looks like the shape to extend and is not reachable. It is
`no.sikt.graphitron.rewrite.capture.StoreRefresh`, package-private in **`graphitron`**, which
depends on `graphitron-model` and on its test-jar; a call from this module's fixture would invert
the module dependency. Its clear is also a different thing than this one wants: `StoreRefresh.prepare`
takes a `FactSink`, a `ClasspathSources` and a class census, and deletes what one capture run owns,
scoped by `graph_name` and by crawled source. Nothing there is a per-store clear. So this item
stands a second row-clearing mechanism beside that one deliberately, the two being unrelated:
`StoreRefresh` answers "what does this run own", and this answers "make this store look freshly
booted".

That leaves one real fork, and it is where the truncate lives. Put it in **test sources**, as a
package-private helper next to `withSeededStore` in `no.sikt.graphitron.model.test`, rather than as
a method on `GraphitronModelStore`. `GraphitronModelStore` is the store's production bootstrap and a
`reset()` on it would be a production surface with no production caller, which is the drift smell
the principles name. `FactStores` is the wrong home for the other reason its own javadoc gives: it
owns no lifetime and puts no rows in the store, and it is reached directly from four modules
downstream of this one (40 sites on 2026-08-21: `graphitron` 30, `graphitron-mcp` 6,
`graphitron-lsp` 2, `graphitron-maven-plugin` 2, the last of which is not one of the three this
item defers). Another sighting rather than a value, but the direction of it is the point: whatever
this change touches, `FactStores.inMemory()` keeps opening and closing a fresh store, because
every one of those sites depends on it doing exactly that. No downstream module calls
`withSeededStore`, which is what confines this change to one module.

**A re-entrant call has to fail loudly.** Today a nested `withSeededStore` would open a second
store; after this change it would reset the outer body's rows out from under it and hand back the
same store, which the leak guard cannot see because the rows are already gone. Nothing in the module
nests today. Keep it that way with an in-use flag on the thread's holder that throws on re-entry,
rather than relying on nobody trying. Mind the one legitimate case that looks like re-entry:
`withSeededStore(String graphName, ...)` reaches the store through the one-argument form, so the flag
has to sit below that delegation or the whole graph-anchored half of the suite trips it.

**Nothing else in the fixture changes.** `withSeededStore(String graphName, ...)` keeps delegating; the
seed helpers, `SEED_SOURCE`, and `derive` are untouched. No test class changes, which is the property
that makes this slice worth doing first: every call site and every class adopts it by not changing.

## Tests

**A leak guard, and it is the only part of this that can fail silently.** A case passing because a
previous case's row survived the reset is worse than a slow suite, and it would not announce itself.
The guard has to be positive: after each reset, assert that every base table holds what a freshly
booted store holds, which is nothing for the ones the clear reaches and the boot's own rows for the
three it does not.

Both decisions this section left to the reviewer are settled. Both stay cheap, and the second is
settled against the cheaper of the two readings for a reason the section gives.

*Always on, not property-gated.* The arithmetic answers the cost worry the draft raised: the whole
144-statement truncate is 0.85 ms, about 6 µs a statement, so a same-shape existence probe per table
is single-digit milliseconds a reset and a couple of seconds across the suite, against 133 s saved. A
nightly-only guard is also a weaker enforcer than this module's own standard asks for: an invariant
exists only while something fails when it breaks, and one that breaks for a working day before
anything notices has already let the bad case land. If the measured always-on price turns out to
exceed a few seconds, narrow the probe (`SELECT 1 ... LIMIT 1` per table, or one `UNION ALL` round
trip) before reaching for the property.

*One derivation per store, and the guard covers every base table rather than only the cleared
ones.* Derive the whole base-table set once when the thread boots its store, and with it the
partition the clear turns on: the 144 it truncates, and the three the boot fills and it leaves
alone. The clear takes its half of the partition; the guard takes the whole set. For a cleared
table the guard asserts empty, and for one of the three it asserts the row count that table held
at boot.

Handing the guard the clear's own list instead, which the draft reached for as the fix for two
lists disagreeing, makes it assert a tautology. After a `TRUNCATE` over exactly those tables,
"those tables are empty" is entailed; the only thing left that could fail it is `TRUNCATE` not
emptying a table. But the leak the guard exists to catch is a row surviving in a table the clear
does not reach, and the clear's exclusion pattern is exactly what decides which tables those are,
so a guard whose scope is that pattern's output cannot see the pattern being wrong. Nothing in the
funnel writes to an excluded table today, which is what makes this about the guard that stays
behind rather than a leak now: a `meta_` table added later that a seeded case writes rows into
passes a shared-list guard silently and fails a whole-set one. Covering the whole set is also the
honest answer to the two-lists worry rather than a narrowing of it. One derivation, one partition,
and the partition itself is what the guard asserts against, so there is no second list to keep in
agreement with the first.

Safe here because no test in the funnel executes DDL, so the set derived at boot stays the set:
the only class in the module that creates or drops relations is `MaterializationOrderTest`, which
boots its own stores. The price over the shared-list version is three row counts a reset on top of
the 144 existence probes.

**A boot-count pin.** The mechanism's whole claim is a number, so a test should assert it: after the
suite, boots per JVM are bounded by the thread count plus the four direct-boot classes' own opens. That
turns the counter from a throwaway instrument into a standing invariant, and it is what catches a
future helper that opens a store per case again. It needs the counter to become a real, if internal,
observable rather than the env-guarded patch the reactor-wide measurement used.

The counter belongs in test sources for the same reason the truncate does, and specifically on
`FactStores`: a count only a test reads is as much a production surface with no production caller
as a `reset()` on `GraphitronModelStore` would be. `FactStores` being the wrong home for the
truncate does not make it the wrong home for this. The objection there was that it owns no
lifetime and puts no rows in a store, and a counter does neither of those things; what it needs is
to sit where every boot passes, which is what `FactStores` is. Every boot in the module does pass
there: the funnel through `inMemory()`, and all seven direct-boot sites in the four out-of-scope
classes through `inMemory()` or `fileBacked`.

The pin is per surefire JVM and per module, which is also what lets the other three modules read
their own count off the same instrument when they adopt this.

Pin the invariant, not the literal four. `fixed.parallelism=4` sizes a `ForkJoinPool`, and that pool
is allowed to add compensation threads when a task blocks, so the count of threads that ever run a
class is not guaranteed to be exactly four and a `boots == 4` assertion can flake on a machine that
never reproduces it. What is exactly true is one boot per thread that booted: count the distinct
thread identities alongside the boots and assert the two are equal, plus a generous ceiling well
under the case count. That fails just as loudly on a helper that boots per case, and cannot fail on a
pool that grew.

**The existing suite is the correctness acceptance.** All 29 funnel classes and the module's 423
cases pass unchanged, and they pass with the classes shuffled: sequential-order dependence is exactly
the defect a shared store can introduce, and JUnit's random class-order option is the cheapest way to
hunt it. Run it three times, not once.

Verification of the win is `mvn test -pl :graphitron-model -Plocal-db` before and after, alternated,
plus the boot counter reporting one per thread rather than 420. Expect the module near 14 s against
46.8 s. Absolute seconds differ per machine; one boot per thread does not. The 33 s is off the
build's *sequential* total; under `-T 1C` this module may not be on the critical path, so measure the
module rather than reading a build-wide number off it.

## Roadmap entries

* R768 carries the reactor-wide measurement, 1051 boots and 395.8 s, and the remaining three modules.
  It should record that the re-materialization question this item settles is settled, and in which
  direction, because its saving estimate was computed on the conservative reading.
* R759 cuts the price of a boot, this cuts their number, and the two compose. R759's alias removal is
  already in the 138 ms and 317 ms figures above rather than double-counted against them.

## What this item deliberately does not do

**It does not touch `graphitron`, `graphitron-lsp` or `graphitron-mcp`**, which are the other 631 boots
and 262.8 seconds. Their boots do not funnel through one helper, so adopting this needs a per-module
read of how each reaches the store, and it should happen after the mechanism has run green here for a
while. Doing all four at once would put the leak guard's design and three modules' fixture surgery in
one review.

**It does not make the store shared across threads.** One store for four threads would serialize every
fixture write behind a lock and trade a measured cost for an unmeasured one. Per thread is the shape
that keeps cases independent, and four boots is already 99% of the saving that one boot would give.

## How to re-measure

```bash
# Boot count. Add an AtomicLong pair around the statement loop in GraphitronModelStore.create
# plus a shutdown hook that appends "<count> <millis>" to the file an environment variable names,
# then install the model and run the module. Revert before trusting anything else on the tree.
mvn install -pl :graphitron-model -Plocal-db -Pquick
GRAPHITRON_BOOT_COUNT_FILE=/tmp/boots.txt mvn test -pl :graphitron-model -Plocal-db
awk '{c+=$1; t+=$2} END {print c" boots, "t" ms"}' /tmp/boots.txt

# Reset cost. Open one store through GraphitronModelStore.open(), list INFORMATION_SCHEMA.TABLES
# for BASE TABLE in PUBLIC excluding META\_% and STORE_STAMP, and time TRUNCATE over that list
# with SET REFERENTIAL_INTEGRITY FALSE around it. Twenty rounds; the first is cold and reads high.
```

## Reviewer findings (Spec → Ready gate, 2026-08-21)

Independent reviewer session, status stays `Spec`, plan body untouched. The findings-only
deliverable is deliberate: the defect below is the one an earlier reviewer already identified, and
the reason it is still in the body is that reviewers have been landing fixes instead of findings.
That is R775's subject, and this section extends the emergent convention it documents rather than
adding a fifth voice to the plan.

**The first gate question passes without reservation**, and the plan's load-bearing premise was
re-derived from the tree rather than trusted. `MaterializeDependencies.populate` reads
`Materializations.registrations` (over `meta_materialize`), `INFORMATION_SCHEMA.VIEWS` and
`INFORMATION_SCHEMA.TABLES`, and nothing else; no fact relation is touched, so no row a case writes
can invalidate `meta_materialize_dependency` and a reset really is the clear alone. Two further
claims that would have broken the mechanism are also clean: the DDL declares no identity column and
no sequence, so `TRUNCATE` without `RESTART IDENTITY` genuinely reproduces a freshly booted store;
and the four materialization targets are `intent_`-prefixed, so the `META\_%` exclusion does not
accidentally leave a previous case's derived rows behind. `StoreRefresh` is package-private in
`graphitron`, which depends on `graphitron-model` and its test-jar, so the plan is right that it is
unreachable and right to stand a second clear beside it. `withSeededStore(String, ...)` does reach
the store through the one-argument form, so the re-entrancy note about where the flag sits is
correct and worth keeping.

**What blocks is the second question, and it is confined to the boot-count pin.** The pin as spelled
cannot pass, and the two sentences that state it disagree with each other.

The counter is placed on `FactStores` specifically so that the out-of-scope classes' boots pass
through it, and the plan cites that as the virtue that makes the home right. Those classes boot per
case, not per class: `MaterializationOrderTest` calls its private `withStore` from 8 of its cases,
`MaterializeRegistryGateTest` from 6, `StoreReaderTest` opens 3 stores across its 3 cases (two
`inMemory`, one `fileBacked`), and `CommentRenderabilityGateTest` opens 2. That is 19 boots that
survive this change by design. So after the change the instrument reads roughly 23 boots (4 from the
funnel plus those 19) against at most 4 distinct booting threads, and:

* "count the distinct thread identities alongside the boots and assert the two are equal" is false
  by about 19 on the first run;
* "the boot counter reporting one per thread rather than 420", in the verification paragraph, is not
  what the instrument will report either.

The author's original formulation, still present higher in the same section ("bounded by the thread
count plus the four direct-boot classes' own opens"), is the correct shape and is what the two later
sentences replaced.

**What would satisfy the gate is not restoring the bound verbatim, because the two arms differ in
what the invariant catches, and picking between them is a design decision the plan should make
rather than the implementer.** A loose ceiling over all boots is stable but goes quiet about the
thing the pin exists for: it cannot distinguish "the funnel regressed to a boot per case" from "one
of the four out-of-scope classes grew four more cases", so it degrades into a number somebody
periodically raises. The equality invariant is the one with teeth, and it is stateable, but only if
the counter separates the two populations, for instance a funnel counter incremented where the
thread-local holder boots and a separate total on `FactStores`, with the equality asserted on the
funnel half and the ceiling on the total. That partition also has a cost the plan should name: it
weakens the argument for `FactStores` as the single home, and it means the other three modules
inherit a two-part instrument rather than a one-part one. Either arm is defensible. What the
implementer cannot be handed is a section that asserts both.

One consequence worth folding in while the pin is being rewritten, since it has the same root: the
headline projection subtracts the whole 133.0 s of in-situ boot time, which is where "about 182
seconds to about 49" comes from, but those 19 surviving boots are roughly 6 s of it. The plan says
elsewhere that the saving is computed net of the out-of-scope classes; it is not. The number stays
inside its own "about", so this is not separately blocking, but the corrected pin and the corrected
projection are the same fact stated twice.

### Non-blocking

* `graphitron-model/src/test/resources/junit-platform.properties` argues the module's concurrency
  safety on exactly the property this change removes: "the store mints a UUID-named H2 database per
  call, so two classes share no rows". After this change two classes on one thread share a store and
  are kept apart by the reset instead. "Nothing else in the fixture changes" should say that this
  comment does, because it is the file a contributor reads to find out why the module is safe to run
  concurrently, and it would then be stating the old reason.
* "a production surface with no production caller, which is the drift smell the principles name":
  the principles define the drift smell as a copy that can diverge from its source, which is a
  different thing. The conclusion is well supported without the citation, on `FactStores`' own
  javadoc and on the argument the plan gives in the next sentence. The one place the drift smell is
  named correctly is the two-lists argument in `## Tests`.
* Sightings that have drifted since the measurement, all of them pre-disclaimed by the plan and none
  affecting a conclusion: 145 clearable base tables rather than 144, 189 `withSeededStore` sites
  across 30 classes rather than 158 across 29, 433 `@Test` methods rather than 423, and 45
  `FactStores` sites downstream rather than 40. The seven direct-boot sites and the four class names
  are exact.
