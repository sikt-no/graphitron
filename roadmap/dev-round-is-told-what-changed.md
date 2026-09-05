---
id: R922
title: "A dev round is told what changed instead of rediscovering it"
status: Spec
bucket: architecture
priority: 2
theme: dev-loop
depends-on: []
created: 2026-09-04
last-updated: 2026-09-05
---

# A dev round is told what changed instead of rediscovering it

## Goal

A `graphitron:dev` round starts from a record of what the developer changed, rather than working it
out again by reading the workspace. The dev goal watches three populations of files and re-runs part
of the generator when one moves; a *gatherer* is any of the stages that then reads those files and
writes facts into the store, the per-workspace cache of what a consumer's schema, database and
classpath contain. Today every watcher knows exactly which file fired it and discards that before
calling anything, so five separate stages downstream each rediscover the same answer by reading
bytes: hashing, stat-walking or re-parsing whole populations to find the one file that moved. When
this lands, the watchers write what they saw into a ledger, each gatherer reads the ledger at its own
pace, and a gatherer that can prove its population did not move does no work at all. Two things
change for a developer in a session. The console names the file that triggered each round instead of
saying only that a round happened, and a round on one cadence stops stat-walking the population
belonging to another: measured below at 7 ms of a quiet round on this repo's own example consumer,
and proportional to the consumer's compiled output rather than to the edit, so it is tens of
milliseconds on a large one.

The larger change is what the ledger lets the *next* items do. Three filed items each propose their
own private cache to remove their own stage's rediscovery, and each has to argue a fresh
invalidation heuristic to do it. Those three arguments collapse into one mechanism here, argued once.

The ledger must be able to say "I do not know", and that arm is the design rather than a caveat: it
is what lets every gatherer keep today's rediscovery as its fallback, so the mechanism can only ever
narrow work and never widen the risk of being wrong, and can be adopted one gatherer at a time.

## The information exists and is thrown away

Three times, in three different ways, and each one is a line of code rather than an inference.

`SchemaWatcher.dispatch` receives the watch event, resolves the changed file against the watched
directory (`dir.resolve(relative)`), tests the filename suffix, and then calls
`debounce.schedule(onTrigger)`. `onTrigger` is a bare `Runnable`. The resolved path and the event
kind go out of scope on the next line.

`DevMojo.buildSaveListener` is handed a `Consumer<String>` by the language server, which knows
precisely which document an editor saved, because it is holding the buffer:
`GraphitronTextDocumentService.didSave` passes `params.getTextDocument().getUri()` straight into it.
The whole body is `uri -> { if (suffixes.stream().anyMatch(uri::endsWith)) debounce.schedule(regen); }`.
The URI is used as a predicate and dropped. This is the strongest case of the three: the information
is not merely available, it is passed in as an argument.

`DebounceExecutor` coalesces `Runnable`s, so even if a payload survived the first two there would be
nowhere to accumulate it across a debounce window.

## What rediscovers it

Five stages, each correct in isolation, each paying to learn something a watcher already knew.

1. **`ClasspathCensus`** content-hashes every jar on the compile classpath and stat-walks every
   `target/classes` file, on every round of both cadences, to decide what to re-parse. Measured on
   this workstation over `graphitron-sakila-example`'s reactor output: three roots, 1,383 class
   files, 6.3 MB, one `Files.walk` plus a size and a modification-time stat per file costs 24 ms on
   the first pass and 7 ms steady over thirty consecutive passes. That is 5.1 µs per class file, so
   the figure is a property of the consumer's compiled output rather than of this example. The jar
   half of the same read costs 33 to 34 ms warm over fifteen jars, measured while specifying R620.
2. **`StoreRefresh.freshSources`** hashes the same jars again, to decide which of the store's
   classpath partitions it can keep. A *partition* is the rows one source owns in a relation shared
   with other sources. So a warm round pays that 33 ms twice, which with the walk above is most of a
   quiet round.
3. **`SchemaLoader.parsePerSource`** re-parses every schema document every round. There is no cache
   at all here, though the populations either side of it have one.
4. **`JavaSourceFacts.refresh`** content-hashes every `.java` file under the compile source roots on
   every source-watcher fire, to find the one that moved.
5. **`StoreRefresh`**'s graph-scoped clear deletes and rewrites the whole of the round's graph
   partition regardless of what changed, which then forces a full re-derivation of the
   materialization register downstream of it.

Items 2, 3 and 5 are the passes R857 enumerates; item 4 is R921; item 1's jar half is R620. Each of
those is the same defect seen from one stage. This item is the shared cause, and it takes item 1's
directory half itself, for the reasons under "What this item adopts" below.

## The API

Two types in `graphitron-model`, under `no.sikt.graphitron.model.sources` beside `ClasspathSources`
and `SourceWalker`, which are the two gatherers that already live there. Neither type knows what a
`WatchService` is; the plugin owns the instances and the watchers that fill them, and a gatherer that
holds a subscription can be unit-tested without the plugin.

```java
/** What changed in one watched population since a reader last looked. */
public sealed interface SourceDelta {

    /**
     * Exactly these moved under these roots, and nothing else under them did.
     *
     * @param scope   the roots this delta speaks for; a path outside them is not described here
     * @param changed files created or modified since the reader's last commit
     * @param removed files deleted since it
     */
    record Known(Set<Path> scope, Set<Path> changed, Set<Path> removed) implements SourceDelta {

        /** Whether this delta describes {@code root} at all. */
        public boolean covers(Path root) { ... }

        /** Whether anything it names lies at or under {@code root}. */
        public boolean touched(Path root) { ... }
    }

    /** We cannot say. Whoever reads this rediscovers, exactly as a cold process does. */
    record Unknown(String reason) implements SourceDelta {}
}
```

```java
/** One watched population's append-only record, and the cursors that read it. */
public final class SourceLedger {

    public SourceLedger(Set<Path> scope) { ... }

    public void changed(Path file);
    public void removed(Path file);

    /** Records that this population moved in a way the ledger cannot describe. */
    public void unknown(String reason);

    /** A reader's own cursor, minted once per consumer at session start. */
    public Subscription subscribe(String consumer);

    public final class Subscription {

        /** What has been recorded since this cursor's last commit. */
        public SourceDelta look();

        /** Consumes exactly what the most recent {@link #look} reported, and no more. */
        public void commit();
    }
}
```

Every path is stored absolute and normalised, on record and in the scope, so "is this path under that
root" is a string test rather than a filesystem call on a hot path.

The load-bearing choice is the **cursor**, not the record. A destructive drain, which is the obvious
shape and the one this item was filed with, breaks on the two cases that actually occur:

* **Two consumers, one population.** The class census reads the classpath population from *both*
  cadences, because a `.graphqls` round builds a catalog too. A drain by whichever round arrives
  first leaves the other round told that nothing moved while a class file it has not read sits on
  disk. Per-consumer cursors make each reader's question independent of which cadence woke it, which
  is the property the census needs and cannot get any other way.
* **A round that fails.** A drain has to be undone by hand when the consumer throws, and every
  consumer has to remember to. A cursor advances only on `commit()`, so a gatherer that throws simply
  has not committed, and the next round sees the same delta plus whatever arrived since. Nothing
  needs to record `Unknown` on failure, and no gatherer can forget to.

`look()` returns `Unknown` in four cases, each with its reason carried so a report can name it: the
cursor has never committed (the first round of a session, which is the round that reconciles against
a store some other process may have written), an `unknown(...)` marker sits in the unconsumed range,
the log was trimmed past this cursor, or the ledger is disabled. Otherwise it returns `Known` with
this ledger's scope.

The ledger trims events below the minimum committed cursor across its subscriptions, and collapses
its whole log into a single `unknown("event log full")` marker once the unconsumed span passes a cap.
The cap is what keeps a session that watches a `mvn clean install` in another terminal from
accumulating one entry per class file written; degrading to `Unknown` there costs a full walk, which
is exactly what that round would have paid anyway.

## What the mechanism guarantees

A delta can be read two ways, and they are not equally safe. The distinction is the heart of this
item, and every adopter has to say which one it is using.

**The negative reading: "nothing under this root moved."** A consumer that skips a root the delta
proves untouched keeps every property it has today for the rounds where the root *did* move, because
on those rounds it does exactly what it does now. In particular it keeps *self-healing*: today, if
the OS watcher silently drops the event for class `C` but delivers the one for class `D`, the round
that `D` triggers walks the whole root and picks up `C` as well. Under the negative reading that is
still true, because a delta that names `D` sends the consumer down the walking path.

**The positive reading: "exactly these moved, so read only these."** This is strictly stronger and
strictly more useful, and it forfeits the self-healing above: a dropped event for `C` is never
noticed, and the session serves stale facts about `C` until it restarts. Adopting it is a decision
each gatherer makes for itself, with its own argument, and this item deliberately does not make that
decision on any gatherer's behalf.

Three ways a watcher can be wrong, and where each is answered. A design that does not answer all
three trades a correct dev loop for a fast one, which is not a trade this item is willing to make.

**Watch overflow.** `WatchService` drops events under burst. `SchemaWatcher.dispatch` already handles
`OVERFLOW` by rescheduling rather than ignoring it, so the honest translation exists: it records
`unknown("watch overflow")`.

**A subtree registered mid-session.** `dispatch` registers newly created directories on the fly, and
files created between the directory appearing and the registration completing are never seen.
`SchemaWatcher.addRoot`, which `regeneratePass` calls when a re-expansion of `<schemaInputs>` finds a
new root, has the same hole. That is harmless today because everything is rediscovered anyway; under
a delta it is a silent miss, so both sites record `unknown(...)`.

**A root the ledger does not speak for.** The watchers resolve their roots once at session start
(`DevMojo.resolveClasspathRoots`, `resolveSchemaRoots`, `resolveSourceRoots`); a round later in the
session rebuilds its `RunContext` and can name a directory none of them watch, a reactor module built
for the first time being the ordinary way. `Known.covers` is what a consumer asks before trusting a
delta about a root, and a root outside the scope is rediscovered.

**And an escape hatch, because filesystem watchers do lie.** `-Dgraphitron.dev.rediscover=always`
makes every `look()` return `Unknown("rediscovery forced")`, which is today's behaviour exactly. A
developer on a filesystem where the watcher under-reports, a container bind mount and a network share
being the usual suspects, gets a working session back with one flag, and a bug report against this
mechanism has a one-line bisect. The round's own report says which path it took, so a stale session
is diagnosable rather than silent; that is the same argument `ClasspathCensus.Round` already makes
for reporting what a round re-read.

## What this item adopts

Two clients, chosen because between them they exercise both readings of the API and neither can
produce a wrong generated file.

**The round says what changed** (positive reading). `regeneratePass`, `rebuildCatalog` and
`refreshSourceFacts` each hold a subscription, look at the top of the round, and name what they found
on the console beside the banner they already print: the changed files where the delta is `Known`
(the first few, then a count), or the reason where it is `Unknown`. Today a developer watching the
log sees that a round happened and has to guess why, which matters most in the case where the guess
is wrong: an editor writing a stray file, or another terminal's build, triggers a round that looks
like it came from the save just made. The positive reading is safe here because the worst a dropped
event can do is leave a file out of a log line.

**The class census stops walking a population that did not move** (negative reading).
`ClasspathCensus` takes a subscription over the classpath population and, for a directory root it
holds a cached reading of, skips the walk entirely when the delta is `Known`, `covers` that root and
`touched` nothing under it. It returns the references it already composed, in the order it composed
them. Any other case walks, including a `Known` delta that names one file under the root: the census
pays exactly what it pays today on the rounds where that population moved, and pays nothing on the
rounds where it did not.

That is the whole rule, and it is worth saying why it is not the more obvious one. Applying a delta's
named files to the cached directory map, rather than using it only as a proof of stillness, would let
the census re-read one file instead of walking. It would also put a newly created file at the end of
a map whose order is the walk's, and that order decides which of two classes declaring the same
fully-qualified name the census keeps. The negative reading has no such hazard, needs no argument
about walk order, and saves the cost on the cadence that matters: a `.graphqls` save cannot change a
class file, and that is the round a developer waits on.

`ClasspathCensus.Round` gains a `rootsProvenUnchanged` count and a clause in `report()`, so the
saving is visible on the console and a regression that starts walking again is visible too. A census
with no subscription behaves exactly as it does today, which is what the one-shot goals get.

## What this item does not do

It does not remove the jar hash, and that limit is structural rather than an omission.
`resolveClasspathRoots` watches each reactor project's `target/classes` and nothing else; no watcher
covers the local Maven repository, so for jars the content hash is the only detector that exists. A
later adopter can decide *whether* to hash from a delta, but the cold round still pays, and making
the cold round cheaper is R620's, through classpath entries that carry an identity their builder
already knows. The two compose: R620 takes a quiet round's jar cost from two hash passes down to one
pass over the reactor's own snapshots, and this item takes the directory walk beside it to nothing.

It does not adopt the delta in `SchemaLoader.parsePerSource`, `JavaSourceFacts.refresh` or
`StoreRefresh`. Each of those is the positive reading, each is owned by an item that is already
specifying the surrounding change, and each needs an argument this item cannot make for it. What they
get from here is the mechanism and the vocabulary: R857's per-document schema parse becomes "look,
re-parse what the delta names, commit", with the store's own per-document stamp still the ground
truth on the first round of a session; R921's source hash becomes the same shape one cadence over,
and its "the session record belongs to the writer, not to the walker" finding is answered by the
cursor, since a file whose store write failed is a file whose consumer did not commit.

Widening the watch into the local Maven repository was considered and is under "Other solutions"
below.

## Implementation

`graphitron-model`, `no.sikt.graphitron.model.sources`:

* `SourceDelta`, new. Sealed on `Known` and `Unknown` as sketched above, with `covers` and `touched`
  on `Known` doing the normalised-prefix test.
* `SourceLedger`, new. An append-only list of events under a monotonic sequence, a map of named
  cursors, all state guarded by the ledger's monitor because the three watch threads and the debounce
  threads are distinct. `Subscription.look` folds the events after its cursor and remembers the
  position it folded to; `commit` applies that position and trims.

`graphitron-model`, `no.sikt.graphitron.model.classpath`:

* `ClasspathCensus` gains `observing(SourceLedger.Subscription)`, registered rather than passed to
  `read` for the same reason `reportTo` is: every read must see it, including the reads inside a
  cadence the owner does not otherwise think about. `read` looks once at the top, before the
  `jooqPackage` comparison that clears the cache (a cleared cache walks regardless, and the round
  still commits, so the delta is not replayed), passes the delta to `readDirectory`, and commits after
  the reading is composed. `readDirectory` gains the skip arm. `Round` and `report()` gain
  `rootsProvenUnchanged`.

`graphitron-maven-plugin`:

* `SchemaWatcher` takes a `SourceLedger` beside its `DebounceExecutor`. `dispatch` records before it
  schedules: the resolved path on a suffix match, as `removed` for `ENTRY_DELETE` and `changed`
  otherwise; `unknown("watch overflow")` on `OVERFLOW`; `unknown(...)` where it registers a new
  subdirectory. `addRoot` records `unknown(...)` too. The four existing constructors keep their shapes
  with the ledger added; `SchemaWatcherTest` and `CatalogRefreshTest` are the two other construction
  sites.
* `DevMojo.buildSaveListener` takes the ledger and records the saved document before scheduling,
  resolving the LSP's URI with `Path.of(URI.create(uri))` and recording `unknown(...)` for a URI that
  is not a resolvable `file:` path, which is what an unsaved editor buffer produces.
* `DevMojo` holds three ledgers beside the three debounce executors it already holds, scoped to the
  roots each watcher resolved, and mints the subscriptions: one per cadence for the announcement, one
  for `sessionCensus` over the classpath ledger. The ledgers are created before `bindServer`, since
  the save listener is built there. `graphitron.dev.rediscover` is a `@Parameter` that disables them.
* The three round entry points announce, as described above.

`docs/architecture/how-to/dev-loop-internals.adoc`, "Dev loop: how the goal is wired internally",
gains the ledger as a component, since that list is where a contributor learns what the dev JVM is
made of and it currently describes the watchers as signalling the dispatch and nothing more.

## Tests

`SourceLedgerTest`, `graphitron-model` unit tier. The mechanism's own contract, and every arm of it: a
first `look` is `Unknown` and says why; after a commit a `look` names exactly what was recorded since;
an uncommitted `look` is not consumed, so a consumer that throws sees the same delta again; an event
recorded between a `look` and its `commit` survives the commit and arrives at the next one; an
`unknown` marker anywhere in the unconsumed range makes the `look` `Unknown` and carries the reason;
two subscriptions advance independently; a delete arrives in `removed` and not in `changed`; the log
collapses to `Unknown` rather than growing once the cap is passed; `covers` and `touched` answer for
nested roots and for a path outside the scope.

`ClasspathCensusTest`, `graphitron` unit tier, four cases beside the eight it already has. A delta
that names nothing under a root leaves the root unwalked (`rootsProvenUnchanged` is 1, `filesReused`
is 0) and answers a census equal to the walking round's, entry for entry and in the same order. **A
delta that names one file under a root walks it and picks up a sibling the delta never named**; this
is the self-healing property stated above, and it is the test that fails if someone later "optimises"
the skip into a per-file apply. An `Unknown` delta walks every root. A root outside the delta's scope
is walked although the delta is `Known` and names nothing. A census with no subscription is unchanged,
which the existing eight already assert and which must keep passing untouched.

`SchemaWatcherTest`, plugin unit tier: a suffix-matching modify records the resolved absolute path and
then schedules; a delete records it as removed; `OVERFLOW` records `Unknown` and still schedules;
registering a new subdirectory records `Unknown`. `DevMojoTest`: `buildSaveListener` records the saved
document's path before scheduling, and a non-`file:` URI records `Unknown`.

`DevMojoTest`, driving a round through `regeneratePass` as it already does, asserts the console names
the file the round was told about. That is the completeness evidence for the visible half of the goal;
the census assertions above are the evidence for the other half. No timing assertion appears anywhere
in this item: a timing assertion passes on hardware fast enough to hide the regression, and
`rootsProvenUnchanged` is the same fact stated as a count.

## Why this is worth building rather than fixing five times

Each of the five stages can be fixed alone, and three items already propose to. The reason to build
the shared mechanism is that each local fix buys a cache whose invalidation is a second heuristic, and
the heuristics do not compose: R921 has to argue modification times are safe, R857 has to argue a
recorded stamp can be read for a population it was not written for, and R620 has to thread one round's
hashes from one consumer to another. Told what changed, none of those arguments is needed, and the two
that remain, the overflow and the cold start, are answered once in one place.

There is also a pattern here worth naming, because it predicts where the next instance appears. Every
incremental cache in this tree justified its detector against the cost of the work it protects.
`ClasspathCensus` argues hashing a jar set "costs roughly an eighth of parsing it". `JavaSourceFacts`
argues that "hashing source files is cheap beside the parse it is protecting". Both were true against
a cold parse, and both stopped being true the moment a later item cached the parse: the verification
became the entire cost of the round. Any detector argued as cheap relative to work that later gets
cached is the next one of these.

## Other solutions we've considered

**A destructive drain rather than per-consumer cursors.** The shape this item was filed with: a round
takes the accumulated delta and resets the ledger to empty. Rejected on the two cases under "The API"
above, either of which is a wrong answer rather than a slow one.

**The watchers push into the gatherers.** A watcher that calls `census.invalidate(path)` needs no
ledger at all. Rejected because it inverts the dependency, putting knowledge of every gatherer into
the plugin's watch wiring, and because it has no answer for a consumer that fails: the notice is
delivered once and gone, where a cursor keeps it until the consumer says it read it.

**Carry the delta on `RunContext`.** It is the value a round already threads everywhere. Rejected
because the lifetimes do not match: `RunContext` is rebuilt per round from configuration, while the
consumers are session-lived objects (the census, the facts writer) whose cursors have to outlive any
one round to mean anything.

**Watch the local Maven repository too.** Registering the classpath jars' parent directories would let
the delta cover jars as well, and a steady round would hash nothing at all. Rejected for now on two
grounds: it makes the census's cache validity depend on watch soundness where today the census is
self-validating by construction, and it extends the watch surface outside the project tree to a
directory shared with every other build on the machine. The cheaper route to the same steady state is
R620's, which establishes a jar's identity from what the resolver already recorded beside it.

**A per-source content-hash cache in each gatherer, and no shared mechanism.** This is what R857 and
R921 propose in their own scopes, and it works: it is strictly more trustworthy than a watcher,
because it reads the bytes. It is also strictly more expensive, because it reads the bytes, and on the
cadences those items are about the read is the whole cost. The two approaches are complementary rather
than exclusive, which is why the fallback here *is* the per-source check.

## Provenance

Found while specifying R620, whose narrow question is why one round hashes the same jar set twice.
Asked why the round has to hash at all, given that a watcher fired it and knew which file moved, the
answer was that nothing carries the answer from one to the other. The dev session's own architecture
discards it three times over. R857 and R921 are the same finding reached from two other stages, and
both shrink substantially if this lands first.
