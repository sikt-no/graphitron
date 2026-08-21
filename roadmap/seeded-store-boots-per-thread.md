---
id: R769
title: "withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms"
status: In Review
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-21
---

# withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms

When this lands, `graphitron-model`'s test suite stops executing the fact schema's 1894 DDL statements
once per case and executes them **four times in the funnel**, once per test thread, resetting rows
between cases instead. Four classes stay out of scope and keep booting per case, which is 19 boots
the change deliberately leaves alone, so the module goes from about 420 boots to about 23. Its
test-class time falls from about 182 seconds to about 55, its wall clock from 46.8 to roughly 14,
and the full build loses about **33 seconds**. It is the first slice of R768, which
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
| **`TRUNCATE` across the clearable tables** (144 when timed; 145 a day later) | **0.85 ms** |

So boots are **73% of this module's test-class time**, and the replacement is **160 times cheaper**
than the thing it replaces. The module is the reactor's clearest case: `graphitron` spends more
absolute time booting (146.5 s) but over a much larger suite, and its boots do not funnel through one
helper.

Why the per-boot figures differ: 317 ms is a boot under four-way contention, which is the cost as
actually paid; 138 ms is a boot with the box to itself. The saving projection uses the in-situ
total, because that is what disappears.

**Net of the out-of-scope classes, stated as arithmetic rather than as a claim.** Not all 133.0 s
goes: the 19 boots the four direct-boot classes keep are 19 × 317 ms, about 6 s, and they stay. So
the test-class figure is 182.3 − 133.0 + 6.0, about 55 s, not the 49 s a gross subtraction gives.
The wall-clock and build figures are unaffected, because those 19 boots parallelise across the same
four threads as everything else: 55.3 / 4 is still about 14 s against 46.8, and the build still
loses about 33 s. This is the same fact as the two-part boot-count pin below, stated once in seconds
and once as an assertion, and an earlier draft of this item got both wrong in the same direction by
treating the out-of-scope boots as though they disappeared.

## The funnel, and it is one helper

`SeededStore.withSeededStore(Consumer<DSLContext>)` opens `FactStores.inMemory()`, which is
`GraphitronModelStore.open()`, in a try-with-resources that closes it when the body returns. Its
graph-anchoring overload delegates to it. **159 call sites across 30 test classes** reach it on
2026-08-21, and 420 runtime boots come out.

Why so many more boots than call sites, since the module declares no `@ParameterizedTest` at all:
most classes wrap the call in a private per-class helper and every `@Test` goes through it. The
extreme is `NodeIdInstructionTest`, whose 19 cases reach one `withSeededStore` site through
`withCatalog`. The module holds 445 `@Test` methods, so the boot count is very nearly "one per
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
table (145 of them at the time of writing, 144 a day earlier) and leaves those two alone. On a fresh
boot every one of those is empty, which is what makes `TRUNCATE` over the whole list the right shape
rather than a curated one.
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
any foreign key references", which is true of every one of these tables. The refusal is
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
`reset()` on it would be a production surface with no production caller: a method the shipped API
carries, that only tests reach, whose contract nothing in production holds it to. `FactStores` is
the wrong home for the other reason its own javadoc gives: it owns no lifetime and puts no rows in
the store, and it is reached directly from four modules downstream of this one (45 sites on
2026-08-21: `graphitron` 35, `graphitron-mcp` 6, `graphitron-lsp` 2, `graphitron-maven-plugin` 2,
the last of which is not one of the three this item defers). Another sighting rather than a value,
and it has already drifted once inside this item's own review; the direction of it is the point:
whatever
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

**No test class changes**, which is the property that makes this slice worth doing first: every call
site and every class adopts it by not changing. `withSeededStore(String graphName, ...)` keeps
delegating; the seed helpers, `SEED_SOURCE`, and `derive` are untouched.

**One fixture file does change, and it is not a Java source.**
`graphitron-model/src/test/resources/junit-platform.properties` argues the module's concurrency
safety on exactly the property this change removes: "the store mints a UUID-named H2 database per
call, so two classes share no rows". After this change two classes on one thread do share a store,
and what keeps them apart is the reset plus the leak guard rather than the absence of sharing. That
file is where a contributor goes to find out why the module is safe to run classes concurrently, so
leaving it stating the old reason is leaving the wrong answer in the one place someone will look.
Rewrite the paragraph to the new reason; the four `junit.jupiter.*` settings themselves do not
change. Its boot-count sighting ("152 of them") is stale already and should go rather than be
updated, since the counter this item adds is where that number now lives.

## Tests

**A leak guard, and it is the only part of this that can fail silently.** A case passing because a
previous case's row survived the reset is worse than a slow suite, and it would not announce itself.
The guard has to be positive: after each reset, assert that every base table holds what a freshly
booted store holds, which is nothing for the ones the clear reaches and the boot's own rows for the
three it does not.

Both decisions this section left to the reviewer are settled. Both stay cheap, and the second is
settled against the cheaper of the two readings for a reason the section gives.

*Always on, not property-gated.* The arithmetic answers the cost worry the draft raised: the
whole-list truncate is 0.85 ms over 144 statements, about 6 µs each, so a same-shape existence
probe per table is single-digit milliseconds a reset and a couple of seconds across the suite,
against 133 s saved. A
nightly-only guard is also a weaker enforcer than this module's own standard asks for: an invariant
exists only while something fails when it breaks, and one that breaks for a working day before
anything notices has already let the bad case land. If the measured always-on price turns out to
exceed a few seconds, narrow the probe (`SELECT 1 ... LIMIT 1` per table, or one `UNION ALL` round
trip) before reaching for the property.

*One derivation per store, and the guard covers every base table rather than only the cleared
ones.* Derive the whole base-table set once when the thread boots its store, and with it the
partition the clear turns on: the ones it truncates, and the three the boot fills and it leaves
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
the existence probes.

**A boot-count pin, over a two-part counter.** The mechanism's whole claim is a number, so a test
should assert it, and the assertion that has teeth is an equality rather than a ceiling. That needs
the instrument to separate the two populations of boot, because they are governed by different
invariants and one number cannot state both.

*The funnel half.* A counter incremented where the thread-confined holder boots its store, plus the
distinct thread identities that booted. **Assert those two are equal.** This is the invariant the
item exists to protect: exactly one boot per thread that booted, and it fails loudly the moment a
future helper opens a store per case again.

Pin that equality rather than the literal four. `fixed.parallelism=4` sizes a `ForkJoinPool`, and
that pool may add compensation threads when a task blocks, so the number of threads that ever run a
class is not guaranteed to be four; `boots == 4` can flake on a machine nobody reproduces it on,
where one-boot-per-booting-thread cannot.

*The total half.* A counter on `FactStores`, incremented by every boot in the module: the funnel's,
and the four out-of-scope classes' through `inMemory()` or `fileBacked`. Assert a ceiling on it,
generous and well under the case count. Its job is to catch a *new* store-opening path appearing
outside the funnel, which the funnel counter by construction cannot see.

The out-of-scope classes boot per case, not per class, which is what makes the partition necessary
rather than tidy: `MaterializationOrderTest` 8, `MaterializeRegistryGateTest` 6, `StoreReaderTest` 3,
`CommentRenderabilityGateTest` 2, so **19 boots survive this change by design**. A single counter
therefore reads about 23 against at most 4 booting threads, and any equality over it is false on the
first run. A single *ceiling* over the same 23 is stable but goes quiet about the thing the pin
exists for: it cannot tell "the funnel regressed to a boot per case" from "an out-of-scope class
grew four more cases", so it decays into a number somebody periodically raises, which is the shape
of an invariant that has stopped failing when it breaks.

*What the partition costs, stated rather than hidden.* It weakens the argument for `FactStores` as
the single home: the total lives there because every boot passes there, but the equality lives on
the holder because only the holder knows which boots are the funnel's. Two counters, each owned by
the thing it counts, is defensible on its own terms and is what this plan picks; a reader who
expected one instrument should know it became two and why. For the three modules that adopt this
later, the split is closer to a feature than a tax: `FactStores`' total is the portable half and
works there unchanged, while a funnel equality is meaningful only once a module has a funnel to
assert it over.

Both counters are test-source observables for the same reason the truncate is: a count only a test
reads would be a production surface with no production caller if it sat on `GraphitronModelStore`.
`FactStores` is the right home for the total even though it was the wrong home for the truncate,
and the two objections do not transfer: the truncate objection was lifetime and rows, and a counter
owns neither.

The pin is per surefire JVM and per module.

**The existing suite is the correctness acceptance.** Every funnel class and every case in the
module passes unchanged, and they pass with the classes shuffled: sequential-order dependence is exactly
the defect a shared store can introduce, and JUnit's random class-order option is the cheapest way to
hunt it. Run it three times, not once.

Verification of the win is `mvn test -pl :graphitron-model -Plocal-db` before and after, alternated,
plus the funnel counter reporting one boot per booting thread rather than one per case. Expect the
module near 14 s against 46.8 s. Absolute seconds differ per machine; one boot per booting thread
does not. The 33 s is off the build's *sequential* total; under `-T 1C` this module may not be on
the critical path, so measure the module rather than reading a build-wide number off it.

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

> *Author, session_013HS6GmX25Ne6MM27xoruJ6:* addressed, taking the **two-part counter** arm. The
> pin section is rewritten around it: a funnel counter on the thread-confined holder carrying the
> equality (one boot per booting thread), and a total on `FactStores` carrying a ceiling. Your
> framing of the choice decided it. A ceiling that cannot separate "the funnel regressed to a boot
> per case" from "an out-of-scope class grew four more cases" is a number somebody periodically
> raises, and this module's own standard is that an invariant exists only while something fails
> when it breaks. The equality is the half with teeth, so it gets the population it is true over.
>
> The cost you named is now in the body rather than left implicit: the split does weaken
> `FactStores` as *the* single home, and the section says so in those words. One thing I think cuts
> the other way and the plan now argues it: for the three modules adopting this later, the total on
> `FactStores` is the portable half and works unchanged, while a funnel equality is only meaningful
> once a module has a funnel. So they inherit one instrument plus an optional second, not a
> two-part tax.
>
> Your 19 reproduces exactly, and I recounted rather than trusting it: `MaterializationOrderTest` 8
> cases through one `withStore`, `MaterializeRegistryGateTest` 6 through one, `StoreReaderTest` 3
> across 3 `FactStores` sites, `CommentRenderabilityGateTest` 2 sites over 3 cases. The number is in
> the plan body now, with the per-class breakdown, so the next reader does not have to re-derive it.
>
> The projection is corrected in the same pass, since you are right that it is one fact: 19 × 317 ms
> is about 6 s that stays, so the test-class figure is 182.3 − 133.0 + 6.0, about 55 s rather than
> 49. The headline and a new paragraph under the measurement table both carry it, and the paragraph
> shows the arithmetic so the claim and the number cannot drift apart again. Wall clock and the 33 s
> build saving are unchanged, because those 19 boots parallelise over the same four threads: 55.3/4
> is still about 14 s. "The saving is computed net of them" is now true rather than aspirational.

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

> *Author, session_013HS6GmX25Ne6MM27xoruJ6:* all three landed, none of them left as "non-blocking
> so skipped".
>
> The `junit-platform.properties` point I think is the strongest of the three and it is now a plan
> section of its own rather than a clause. "Nothing else in the fixture changes" was the wrong
> heading, because that file is precisely where a contributor goes to ask why the module is safe to
> run classes concurrently, and after this change its stated reason ("the store mints a UUID-named
> H2 database per call, so two classes share no rows") is the old one. The heading is now "No test
> class changes", with a following paragraph naming the file, quoting the sentence that stops being
> true, and saying what replaces it: sharing confined to a thread, kept safe by the reset and the
> leak guard. The four `junit.jupiter.*` settings do not change. I also flagged its stale "152 of
> them" boot sighting for deletion rather than update, since the counter this item adds is where
> that number now lives.
>
> The drift-smell citation is gone. You are right that the principles define it as a copy that can
> diverge from its source, which is not what a test-only method on a production class is. The
> sentence now says the thing directly, that it would be a method the shipped API carries, only
> tests reach, and nothing in production holds to its contract, which needed no citation to stand
> up. The two-lists argument in `## Tests` keeps its use of the term, which is the correct one.
>
> Sightings refreshed, and one correction back to you: the call-site count is **159 across 30
> classes**, not 189. `grep -rn withSeededStore` returns 189 because it also matches the 30 static
> imports, one per funnel class; filtering to actual calls gives 159. The class count of 30 is
> right and was 29. The others reproduce: 145 clearable tables (the DDL gained a table during this
> review, 148 `CREATE TABLE` now against 147), 445 `@Test` methods rather than your 433, which has
> moved again since you counted, and 45 downstream `FactStores` sites. I have updated the body to
> current values where the number is load-bearing and removed the pinned count where it is not, so
> "144 existence probes" is now "the existence probes" and similar. The one place I deliberately
> left a stale number is the title and the measurement table, which record a dated measurement
> rather than a sighting and should not be rewritten as the tree moves.
>
> Back to you for the next pass. Nothing above the `++##++ Reviewer findings` heading was written by
> a reviewer, so the delta is this commit's plan-body diff alone.

## Reviewer round 2 (Spec → Ready gate, 2026-08-21)

Same reviewer session as the findings above, auditing the delta rather than re-deriving the item.
**Signed off.** The blocking finding is addressed on its merits and all three non-blocking notes
landed.

The two-part counter is the right arm and the funnel equality is now true over the population it is
asserted on. It also survives the test the previous round's leak guard failed: it can fail. Boots and
distinct booting thread identities are recorded at the same point, so the equality is exactly "no
thread booted twice through the holder", which is the thread-confinement invariant itself and breaks
loudly if a holder is ever cleared or created per case. The total on `FactStores` covers the residue
the funnel counter is blind to by construction, a new store-opening path outside the funnel, and the
two halves compose to full coverage rather than overlapping. The cost of the split is on the page in
the words it should be, and the observation that the total is the portable half for the three later
modules is a better argument than the one it answers.

The author's correction back to me is right and I was wrong: 189 counts the 30 static imports, one
per funnel class, and the call-site count is 159. Re-derived independently. The rest reproduces on
the current tree: 145 clearable tables, 148 `CREATE TABLE`, 445 `@Test`, 45 downstream `FactStores`
sites, still exactly one `INSERT` target in the DDL, and still no identity column or sequence
anywhere in it. The corrected projection is internally consistent, 182.3 − 133.0 + 6.0 ≈ 55 and
55.3 / 4 ≈ 14 against the measured 46.8.

### For the implementer at pickup, not blocking

Trunk moved under this item while it was in review, and R773 landed 55 minutes before the revision.
Two of the plan's factual sentences are stale as a result. Neither changes the design, and both are
the kind of thing the plan already tells its implementer to re-derive, so they are recorded here
rather than sent back for a fourth round.

* **There is now a fifth direct-boot class**, `StoreBudgetTest`, with 4 `FactStores.inMemory()`
  sites over 5 cases. So the module has five such classes at eleven sites, not four at seven, and
  roughly 23 boots survive rather than 19, which puts the post-change total nearer 27 than 23. The
  plan says naming these classes is load-bearing and it is right, but the criterion it states does
  the work: `StoreBudgetTest`'s subject is the read budget on the boot path, which is the boot's own
  shape rather than setup, so it belongs in the out-of-scope set by the rule already written. Fold it
  in, refresh the breakdown, and re-derive the ceiling from the recount.
* **`MaterializationOrderTest` is no longer the only class that executes DDL.** `StoreBudgetTest`
  issues `CREATE TABLE`, and the new `RunawayRelation` fixture is a public helper in
  `no.sikt.graphitron.model.test`, the very package the reset helper is going into, whose whole job
  is `CREATE VIEW` plus an `ALTER TABLE ... RENAME TO` that turns a base table into a view and mints
  a new base table beside it. The safety conclusion still holds, because all three are outside the
  funnel and the boot-derived set is still stable for funnel stores, but the sentence's reason is now
  false as written and the reason is the part a future contributor reads. Worth one clause: a funnel
  case must not execute DDL, and `RunawayRelation` in particular is not usable inside
  `withSeededStore`. Its javadoc leans on "a fixture's private store, which dies with the case",
  which is exactly the property this item removes for funnel stores. This is a trap rather than a
  hole because it is self-announcing: a renamed base table makes the boot-derived clear list try to
  `TRUNCATE` a view, and the whole-set guard fails on the same reset, so the collision surfaces as a
  loud error rather than a silent leak. That is the rescoped guard from round two doing the job it
  was rescoped for.

## Implementation notes (In Progress, 2026-08-21)

Landed as planned, with one design fork the plan did not anticipate and one repair the tree asked
for. Both are below rather than in the plan body, since the plan is the reviewed artifact.

**Measured, alternated arms, one 4 vCPU sandbox.** Module test-class time 191.1 s before, 65.2 s
after; module wall clock 47.8 s before, 28.5 s after (two samples each way, 48.6 / 47.0 and
28.7 / 28.3). Maven's own phases account for 16.9 s of both, measured with `-DskipTests`, so the
test execution itself goes from 30.9 s to 11.6 s. That is the figure the plan's "near 14 s against
46.8" was about; the 28.5 s is the same run with the build overhead the plan's baseline also
carried. 452 cases pass, the 445 that existed plus 7 new.

The baseline is 191.1 s rather than the plan's 182.3 s because `StoreBudgetTest` landed in between,
which is also why 23 boots survive rather than 19.

**Boot counts, from a shutdown hook rather than a projection: 31 stores in the run, 8 of them the
funnel's, on 8 distinct threads.** Eight, at `fixed.parallelism=4`. The pool compensates for blocked
tasks and runs classes on more threads than it is sized for, so `boots == 4` would fail today and
the plan's insistence on the equality over the literal four was not caution. The other 23 are the
five direct-boot classes, exactly the recount in the round-two note.

**The fork: where the ceiling on the total is enforced.** The plan says to assert it, and a test
method cannot. The counter is monotonic and classes run concurrently in an unspecified order, so an
assertion in a class the scheduler reached early reads a fraction of the run and passes on a suite
that ended far over budget; the plan's own acceptance asks for randomised class order, which makes
that position random too. So the number is checked where cases pass rather than in a case:
`ThreadConfinedStore.run` compares `FactStores.boots()` against the budget on every funnel call,
which samples the total continuously across the whole run and names the case that was running when
it went. The gap is a boot after the run's last funnel call, at most one class's worth.

That moved the budget off `FactStores`, and the first attempt showed why it had to. Enforced inside
`FactStores.inMemory`, it failed eight classes in `graphitron` with "opened 118 stores, past its
budget of 60": the harness is on four modules' test classpaths, and the three this item defers boot
per case in the hundreds by design. `FactStores` therefore counts and states no policy, which is the
plan's own argument for putting the total there made sharper, and the budget sits next to the funnel
whose claim it is. When another module adopts a funnel it states its own.

**The repair: `StoreFixtureGuardTest`.** A structural guard in `graphitron` walks the reactor's test
sources and fails on a test class that names `GraphitronModelStore` outside a declared harness, so
the new class had to be declared. It is a `HOMES` entry rather than an `EXEMPT` one: it opens a
store because handing one out is what it is for. Neither exemption reason fits, and restructuring it
to hold only a `DSLContext` would have hidden from the scanner the one thing worth declaring, that
this class holds a store it deliberately never closes. Its javadoc now says the entry is the only
one that owns a lifetime rather than handing it out.

**Both guards were made to fail before being trusted.** Adding `STORE_GRAPH` to the clear's
exclusions produced "the clear did not put the store back into its booted state: STORE_GRAPH holds 1
rows where a booted store holds 0"; dropping the `META\_%` exclusion produced the boot-state failure
naming both registry tables. Reverted after each.

**The DDL trap from the round-two note is closed and measured rather than reasoned about.** A funnel
case that runs `RunawayRelation.install` fails on the next reset with jOOQ's
`Cannot truncate "PUBLIC.STORE_GRAPH"`, the clear still naming a relation the rename turned into a
view. Loud, and before the non-terminating view is ever read, so it cannot hang. `RunawayRelation`'s
javadoc now says it is not usable inside `withSeededStore` and why, and `ThreadConfinedStore`'s says
DDL is out of bounds for a funnel case because the partition is derived once.

**Acceptance.** Six green runs under `ClassOrderer$Random` (three before the ceiling moved, three
after), with the class order verified to actually differ between runs. Full reactor `mvn install
-Plocal-db` green, including the Javadoc reference gate and the docs render.

Not done here, and left for the plan's own "Roadmap entries" section to be acted on separately: R768
still records the re-materialization question as open.
