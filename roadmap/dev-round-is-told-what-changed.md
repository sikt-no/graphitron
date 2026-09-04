---
id: R922
title: "A dev round is told what changed instead of rediscovering it"
status: Backlog
bucket: architecture
priority: 2
theme: dev-loop
depends-on: []
created: 2026-09-04
last-updated: 2026-09-04
---

# A dev round is told what changed instead of rediscovering it

## Goal

A `graphitron:dev` round starts from a list of what the developer changed, rather than working it out
again by reading the workspace. The dev goal watches three populations of files and re-runs the
generator when one moves; a *gatherer* is any of the stages that then writes facts into the store,
the per-workspace cache of what a consumer's schema, database and classpath contain. Today every
watcher knows exactly which file fired it and discards that before calling anything, so five separate
stages downstream each rediscover the same answer by reading bytes: hashing, stat-walking or
re-parsing whole populations to find the one file that moved. When this lands, the watchers record
what they saw, a round takes that record, and each gatherer is handed the set it has to refresh. A
round that changed nothing reads nothing.

The record must be able to say "I do not know", and that arm is the whole design rather than a
caveat: it is what lets every gatherer keep today's rediscovery as its fallback, so the mechanism can
only ever narrow work and never widen the risk of being wrong, and can be adopted one gatherer at a
time.

## The information exists and is thrown away

Three times, in three different ways, and each one is a line of code rather than an inference.

`SchemaWatcher.dispatch` receives the watch event, resolves the changed file against the watched
directory (`dir.resolve(relative)`), tests the filename suffix, and then calls
`debounce.schedule(onTrigger)`. `onTrigger` is a bare `Runnable`. The resolved path and the event
kind go out of scope on the next line.

`DevMojo.buildSaveListener` is handed a `Consumer<String>` by the language server, which knows
precisely which document an editor saved, because it is holding the buffer. The whole body is
`uri -> { if (suffixes.stream().anyMatch(uri::endsWith)) debounce.schedule(regen); }`. The URI is
used as a predicate and dropped. This is the strongest case of the three: the information is not
merely available, it is passed in as an argument.

`DebounceExecutor` coalesces `Runnable`s, so even if a payload survived the first two there would be
nowhere to accumulate it across a debounce window.

## What rediscovers it

Five stages, each correct in isolation, each paying to learn something a watcher already knew.

1. **`ClasspathCensus`** content-hashes every jar on the compile classpath and stat-walks every
   `target/classes` file, on every round, to decide what to re-parse. Measured on this workstation
   over `graphitron-sakila-example`'s fifteen census-visible jars (12.6 MB): 33 to 34 ms per hash
   pass, warm.
2. **`StoreRefresh.freshSources`** hashes the same jars again, to decide which of the store's
   classpath partitions it can keep. A *partition* is the rows one source owns in a relation shared
   with other sources. So a warm round pays that 33 ms twice, which is most of a quiet round.
3. **`SchemaLoader.parsePerSource`** re-parses every schema document every round. There is no cache
   at all here, though the populations either side of it have one.
4. **`JavaSourceFacts.refresh`** content-hashes every `.java` file under the compile source roots on
   every source-watcher fire, to find the one that moved.
5. **`StoreRefresh`**'s graph-scoped clear deletes and rewrites the whole of the round's graph
   partition regardless of what changed, which then forces a full re-derivation of the
   materialization register downstream of it.

Items 2, 3 and 5 are the passes R857 enumerates; item 4 is R921; item 1's jar half is R620. Each of
those is the same defect seen from one stage. This item is the shared cause.

## The shape

One value the gatherers read, one ledger the watchers write.

```java
public sealed interface SourceDelta {
    /** Exactly these moved, and nothing else did. */
    record Known(Set<Path> changed, Set<Path> removed) implements SourceDelta {}
    /** We cannot say. Whoever reads this rediscovers, as today. */
    record Unknown(String reason) implements SourceDelta {}
}
```

The type belongs in `graphitron-model` so gatherers can name it; the ledger belongs in
`graphitron-maven-plugin` beside the watchers that fill it. Watchers record rather than schedule; a
round drains the ledger, which atomically takes the accumulated delta and resets to an empty
`Known`; a round that throws records `Unknown` so that nothing a failed round saw is lost.

The `Unknown` arm carries a reason because the reasons differ in what they should prompt, and a
reader that cannot tell an overflow from a cold start cannot report usefully on either.

## What makes it sound

Three ways a filesystem watcher can be wrong, and where each is answered. A design that does not
answer all three trades a correct dev loop for a fast one, which is not a trade this item is willing
to make.

**Watch overflow.** `WatchService` drops events under burst. `SchemaWatcher.dispatch` already handles
`OVERFLOW` by rescheduling rather than ignoring it, so the honest translation exists: it records
`Unknown`.

**A subtree registered mid-session.** `dispatch` registers newly created directories on the fly, and
files created between the directory appearing and the registration completing are never seen. That
is harmless today because everything is rediscovered anyway. Under a delta it is a silent miss, so
registering a directory records `Unknown`, or marks the whole subtree changed.

**A store older than the session.** The store survives the JVM and may have been written by another
process days ago, over a tree that has since moved in ways no live session observed. The recorded
`store_source.stamp`, a content hash, stays the ground truth across processes. The delta only ever
narrows *within* a session, after one reconciliation has happened. That is the same bargain
`ClasspathCensus` already argues for `target/classes` and that R921 argues for `.java` modification
times, so the tree has the precedent twice already.

## What it does not do

It does not remove the jar hash, and that limit is structural rather than an omission.
`resolveClasspathRoots` watches each reactor project's `target/classes` and nothing else; no watcher
covers the local Maven repository, so for jars the content hash is the only detector that exists. A
delta can decide *whether* to hash (a jar the delta names gets hashed, a delta naming none hashes
nothing, `Unknown` hashes all), which is enough to take a steady-state round to zero jar hashes, but
the cold round still pays. Making the cold round cheaper is R620's, through classpath entries that
carry an identity their builder already knows.

Widening the watch into the local repository was considered and is in "Other solutions" below.

## Why this is worth building rather than fixing five times

Each of the five stages can be fixed alone, and three items already propose to. The reason to build
the shared mechanism is that each local fix buys a cache whose invalidation is a second heuristic,
and the heuristics do not compose: R921 has to argue modification times are safe, R857 has to argue a
recorded stamp can be read for a population it was not written for, and R620 has to thread one
round's hashes from one consumer to another. Told what changed, none of those arguments is needed,
and the two that remain (overflow and the cold start) are answered once in one place.

There is also a pattern here worth naming, because it predicts where the next instance appears. Every
incremental cache in this tree justified its detector against the cost of the work it protects.
`ClasspathCensus` argues hashing a jar set "costs roughly an eighth of parsing it".
`JavaSourceFacts` argues that "hashing source files is cheap beside the parse it is protecting".
Both were true against a cold parse, and both stopped being true the moment a later item cached the
parse: the verification became the entire cost of the round. Any detector argued as cheap relative to
work that later gets cached is the next one of these.

## Other solutions we've considered

**Watch the local Maven repository too.** Registering the classpath jars' parent directories would
let the delta cover jars as well, and a steady round would hash nothing at all. Rejected for now on
two grounds: it makes the census's cache validity depend on watch soundness where today the census is
self-validating by construction, and it extends the watch surface outside the project tree to a
directory shared with every other build on the machine. The cheaper route to the same steady state is
the one above, where the delta decides whether to hash rather than replacing the hash.

**A per-source content-hash cache in each gatherer, and no shared mechanism.** This is what R857 and
R921 propose in their own scopes, and it works: it is strictly more trustworthy than a watcher,
because it reads the bytes. It is also strictly more expensive, because it reads the bytes, and on
the cadences those items are about the read is the whole cost. The two approaches are complementary
rather than exclusive, which is why the fallback here *is* the per-source check.

## Provenance

Found while specifying R620, whose narrow question is why one round hashes the same jar set twice.
Asked why the round has to hash at all, given that a watcher fired it and knew which file moved, the
answer was that nothing carries the answer from one to the other. The dev session's own architecture
discards it three times over. R857 and R921 are the same finding reached from two other stages, and
both shrink substantially if this lands first.
