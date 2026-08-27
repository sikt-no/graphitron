---
id: R858
title: "Stamped store directories accumulate one per DDL hash and nothing ever removes them"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Stamped store directories accumulate one per DDL hash and nothing ever removes them

The fact store lives under a per-user cache home, in a per-workspace directory, in a subdirectory
stamped with the DDL hash and generator version. Editing the schema DDL changes the hash, so it opens
a different file rather than meeting a store other modules are warm on. Nothing deletes the one it
stopped using.

On one contributor machine this had reached 49 GB across 87 stamped directories, one workspace of
which held 13, the largest single file 2.7 GB. Every one of them is a cache with no state of record.

## This is by design, and the design is the reason there is no reaper

`mvn clean` does not remove these, deliberately: the store stopped being build output when it moved
to the cache home, and the resolver's own comment says the remedy for a damaged store is deleting the
directory by hand. So this is not a bug in `clean` and should not be filed against it. What is
missing is the other half of the decision. Making the store a cache rather than build output was
right; a cache with no eviction is the part that was never added.

The stamped path is what makes discarding safe in principle. A directory whose stamp names a DDL hash
no installed jar computes any more can never be opened again by any build, which is a stronger
statement than a heuristic about age.

## Why it bites more than a stale cache normally would

The size is a function of how often the schema DDL changes, and on this repository it changes
constantly: the fact schema is where the work happens. A contributor doing model work mints a fresh
several-hundred-megabyte store every time they edit the DDL and rebuild, and every one of those stays.

A store left by a run that failed part-way is the worst case and is not rare: a capture that does not
reach commit leaves a file of the full size holding almost nothing. One measured during the sibling
hang investigation was 124 MB and held 67 rows.

## What lands

Every process that opens the fact store also releases the stamped directories under the same
workspace that nothing is using any more, keeping the three most recently used. A contributor doing
model work stops accumulating a several-hundred-megabyte directory per DDL edit, and gives up no
warmth they would otherwise have had: a directory another process is holding is never touched, and a
stamp still in rotation is kept because it is recently used, not because anything reasoned about
which stamps are still producible. The workspace in the report that holds 13 stamped directories
settles at 3 on its next build.

Nothing about correctness moves. The sweep cannot fail a build, cannot make a run cold that would
have been warm, and touches nothing outside a directory it has positively recognised as a store's
own.

## The policy: keep the three most recently used, release what nothing holds

Two questions, answered by two independent mechanisms, and keeping them apart is the design.

**What is worth keeping** is answered by recency. Keep the directory this run opened plus the two
most recently used others; every further one is a candidate.

Recency rather than the item's original candidate, "discard sibling stamps this generator version
cannot produce". That rule reads as the stronger one, because a stamp naming a DDL hash nothing
computes any more is provably unopenable, but it is wrong in the one case that matters. One
workspace can legitimately alternate between stamps: two checked-out branches whose DDL differs, a
module pinning an older plugin version beside modules on the current one, a bisect. Under the
compatibility rule each build reaps the other's store and mints several hundred megabytes afresh,
which is worse than the accumulation it fixes. Recency degrades gracefully instead: anything in
active alternation stays, up to the retention count, whatever the reason for the alternation.

**What is safe to release** is answered by asking the operating system whether anybody holds the
file, and only then. Recency alone would be wrong about exactly the case the item names: a
`graphitron:dev` session opened three days ago holds its stamp and has not touched it since, so its
directory is the oldest in the home while being the one directory in the home that is genuinely in
use.

Three is the retention because three is the deepest alternation that shows up: the stamp this build
wants, the stamp a long-running dev session in the same checkout is holding, and one branch's worth
of history. It is a constant in the store, not a mojo parameter; see "What this deliberately does
not do".

## The lock probe is a proof, not a heuristic

`GraphitronModelStore.fileUrl` already refuses `AUTO_SERVER`, which means H2 writes no lock file and
takes the MVStore's own operating-system lock on `store.mv.db` instead. That lock is exactly the
question the reaper needs to ask, and it is askable from outside H2 with `FileChannel.tryLock`.

Measured against H2 2.4.240 (the pinned version) while writing this spec, on the file a store URL of
our shape opens:

* another process holding the database: `tryLock` returns `null`;
* this process holding it through H2: `tryLock` throws `OverlappingFileLockException`, so a store
  open in the same JVM is refused for the same reason, without the reaper knowing which JVM it is in;
* after the holder is `kill -9`'d: `tryLock` acquires immediately, because the lock is the operating
  system's and dies with the process. A dead holder cannot wedge the sweep the way a stale
  `AUTO_SERVER` lock file wedges an open;
* holding the lock and deleting the file in the same breath is permitted.

So a candidate is released only while the reaper holds an exclusive lock on its database, and any
answer other than "acquired" leaves it alone. That is a proof about the present instant rather than
an inference from age, and it is the mechanism the item asked to see argued rather than assumed.

There is a residual race: a process may open a candidate between the reaper releasing the lock and
deleting the file. Its cost is that the opener runs cold, which is the cache's ordinary failure mode
and the one every other arm of `openAt` already takes.

## A candidate has to look like a store directory

The home is not always ours. A consumer that pins `<storeDirectory>` may point it at a directory
holding other things, so "every sibling directory of the one I opened" is not a safe candidate rule.

A candidate is a direct child directory of the home, other than the one this run opened, that holds
`store.mv.db` or the recency marker below, and holds nothing else beyond files whose names begin with
`store` (which is every file H2 derives from the database name: the store, its trace log, its
temporary files). Anything else under the home is not this mechanism's business, including an empty
directory, which is also what a store being minted by another process looks like for the instant
between `createDirectories` and the first write. The recognition rule makes that race structurally
impossible rather than narrow.

## Recency is a fact the store records

Each store directory carries a `last-used` marker file, rewritten by every successful file-backed
open with the current timestamp as its text; its modification time is what the sweep reads, and the
text is for a person looking at the cache directory to answer "when did I last build this".

Recording it beats inferring it. H2's own file times answer "when was this store last written",
which is not the same question: a dev session that only reads keeps a store alive without writing
it. For a directory with no marker, which is every store predating this change, recency falls back to
`store.mv.db`'s modification time and then to the directory's own, so an existing cache sorts
sensibly on the first run after the upgrade rather than being uniformly ancient.

## Where the sweep lives

In `graphitron-model`, called by `GraphitronModelStore.openAt` itself, once per home per JVM.

It has to be there because the live stamp segment is `GraphitronModelStore`'s private knowledge, and
the whole reason `openAt` appends the segment rather than publishing it is that a second place
computing it would drift and reap the wrong directory. A caller-driven sweep would also be a rule
every opener has to remember; `openAt` calling it makes it an invariant of opening the store.

`openAt` sweeps in every arm that got as far as a usable home, including the arms that fall back to
an in-memory store: a home whose live stamp is held by a dev session is precisely a home whose
older stamps nobody is looking at. The live segment is excluded by name in those arms rather than by
the lock probe, since this run does not hold it.

Once per home per JVM, because a reactor build opens the store once per module and the second sweep
of a home has nothing left to find. A `graphitron:dev` session therefore sweeps at startup and not
again, which is correct: the next ordinary build sweeps.

Synchronously, not on a background thread. Releasing a directory is a handful of unlinks whatever the
file's size, and a thread would buy nothing while making the report racy.

## What it reports

The store carries what its open released, and whichever caller opened first reports it: a run that
quietly deletes gigabytes out of a person's cache home should say so once.

`GraphitronModelStore.reaped()` returns a `Reaped(int directories, long bytes)` record, zero for an
in-memory store and for every open of a home this JVM has already swept. Both openers report a
non-zero one: `FactCapture` at info, beside the demotion warning it already carries, and `DevMojo`
through the mojo log. Neither is redundant, because the once-per-JVM guard makes the reporter
whichever opener ran first, and that is not the same one on both paths: an ordinary build reaches the
store through the capture, while a `graphitron:dev` session whose initial run is skipped reaches it
through the session's own open.

## Implementation

* `graphitron-model/src/main/java/no/sikt/graphitron/model/boot/StoreReaper.java` (new). One static
  entry point taking the home, the live segment to spare, and the retention count, returning
  `Reaped`. Holds the candidate recognition, the recency ordering, the lock probe, and the deletion.
  Catches everything it can throw and returns what it managed, so no failure of its own can reach a
  caller. The retention count is a parameter rather than a constant read here, so the test tier can
  exercise the ordering with small numbers.
* `GraphitronModelStore`: a `RETAINED_STAMPS` constant, a `MARKER` name, the marker write on every
  successful file-backed open, the once-per-home guard (a set of normalised homes), the sweep call in
  `openAt`, and a `reaped()` accessor. The private constructor takes the report; the fallback arms
  need a private `open(Reaped)` so the public `open()` keeps its current meaning and the field stays
  final. The class javadoc's "A shared store is never deleted by this class" sentence is now false as
  written and states the new rule instead: this class deletes only directories it has proved nobody
  holds, and never the one it opened.
* `FactCapture.runInternal`: log the report when non-zero.
* `DevMojo`: the same at the session's own open.

## Tests

Unit tier in `graphitron-model` (`StoreReaperTest`), against `@TempDir` homes built by hand, since
the segment names are arbitrary to the reaper:

* keeps the live directory and the two most recently used others, releases the rest, and reports the
  count and the byte total it actually removed;
* never releases the live directory, even when its marker is the oldest in the home;
* leaves a candidate alone while a database in it is open in this JVM (an H2 file database opened
  directly at the candidate's `store` base name), and releases it once that connection closes. This
  is the dev-session case in miniature;
* leaves alone: a directory holding a subdirectory, a directory holding an unrelated file, an empty
  directory, and a regular file among the candidates;
* reports zero and throws nothing for a home that does not exist, and for a home that is a regular
  file.

Unit tier in `graphitron-model` (extending the store's own coverage):

* a second `openAt` of the same home in one JVM reaps nothing, the guard being what stops a reactor
  build sweeping once per module;
* an `openAt` that falls back to memory still reaps, and still spares the live segment.

Unit tier in `graphitron` (`PersistentStoreTest`, reusing the forked-holder shape already there): a
candidate held by *another process* survives a sweep that recency alone would have released, and is
released by the next sweep after that process exits. The existing `HoldingWriter` holds the live
stamp, so this needs a sibling holder that opens an H2 file database at a named path and prints
`HELD`. This is the constraint the item asked to be argued, so it is pinned across a process boundary
rather than only within one JVM.

## User documentation (first-client check)

The `storeDirectory` row in `docs/manual/reference/mojo-configuration.adoc` gains a sentence, and the
existing promise that deleting the store is always safe is what it leans on:

> Graphitron keeps the three most recently used of these per checkout and releases the older ones the
> next time it opens the store, so a schema you have stopped working on does not keep its cache
> forever. It only releases a directory no process is holding, so a `graphitron:dev` session in
> another window keeps its own cache for as long as it runs.

The log line, which is the whole of the feature's user surface on an ordinary build:

> graphitron: released 4 unused fact-store caches for this workspace (3.1 GB).

`docs/manual/how-to/dev-loop.adoc` already tells the reader there is "nothing to clean up" about the
warm-start cache. That sentence stops being a promise about the store's size being someone else's
problem and starts being true; it needs no edit.

## What this deliberately does not do

* **No `graphitron:clean-store` goal.** Deleting the cache directory by hand is already documented
  and already safe, and a goal that exists to do what the automatic sweep now does on every build is
  a surface with nothing left to answer for. Worth raising at the gate if a reviewer disagrees:
  the argument for one is a person who wants everything gone now, which `rm -rf` serves.
* **No opt-out parameter.** A knob whose only effect is unbounded disk growth is not a knob. The
  sweep releases only what it has proved nobody holds, in a directory tree whose contents are a cache
  by construction, so the case a knob would protect does not exist.
* **No size or age ceiling.** Both are second policies over the same directories, and a count is the
  bound that matches what accumulates: one directory per DDL edit, each roughly the same size.
* **Dead workspaces stay dead.** A checkout that is never built again keeps its whole home, because
  nothing opens a store there to sweep it, and this is a real part of the reported 49 GB: 87
  directories spread over many workspace segments. Reaping across workspaces needs two facts the
  store does not have and must not guess, the knowledge that the home was resolved by our default
  convention rather than pinned by a consumer, and the workspace path to test for existence. Both
  live in `AbstractRewriteMojo.resolveStoreDirectory`, so the follow-up is a plugin-side caller of
  the same `StoreReaper` handed a list of homes, not a change to this mechanism. File it as Backlog
  at the Done gate.

## Roadmap entries

* File a Backlog item for the dead-workspace tier described above when this reaches Done.

## Related

The sibling hang item is where the empty-store case came from, and its transaction-boundary finding
explains why a failing consumer accumulates full-size stores holding nothing.

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session 01HCCE9xRXVR2J6x3ar7rmDN)

Verdict: withhold. One finding on question 2 and one smaller one on question 1. Everything else in
this plan is in unusually good shape, and the strongest part is that the lock probe is argued from
measurement rather than asserted: I reproduced all four results against H2 2.4.240 while reviewing
(no lock file is written without `AUTO_SERVER`, a database this JVM holds through H2 refuses
`tryLock` with `OverlappingFileLockException`, the lock is released with the connection, and
deleting the file while holding the lock is permitted), so the mechanism's premise holds. So does
the reasoning for recency over compatibility: the alternating-stamp case is real and the
compatibility rule would indeed reap the other side of every alternation.

The goal reads off the plan without reconstructing it. Today a contributor editing the fact schema
DDL mints a fresh several-hundred-megabyte store directory per edit under their cache home and
nothing ever removes one, so the home grows without bound for as long as they do model work. After
this lands, every process that opens the store deletes the older stamped directories under the same
workspace home, keeping the three most recently used and skipping any directory a process is
holding, and says once how much it freed. A `graphitron:dev` session in another window keeps its own
cache. That is reachable in this codebase and it extends a shape already here rather than standing a
new one beside it: `openAt` already owns the stamp segment as private knowledge for exactly the
reason the sweep has to live there, `boot` already carries the `Store*` unit tier the reaper's tests
join, and the plan names the class javadoc's now-false "A shared store is never deleted by this
class" sentence rather than leaving it to rot.

**Question 2. The candidate recognition rule and the recency marker contradict each other, and
reconciling them is a safety decision, not a wording one.** Recognition says a candidate "holds
`store.mv.db` or the recency marker below, and holds nothing else beyond files whose names begin with
`store`". The marker is named `last-used`, which does not begin with `store`. So every directory this
mechanism itself produces, `store.mv.db` plus `last-used`, fails the second clause and is not a
candidate: read literally the sweep reaps the pre-upgrade cache once and then quietly stops working,
which is the failure mode that shows up as "it worked when I shipped it".

The reconciliation is a fork with consequences, which is why it is yours rather than mine to pick.
Naming the marker `store.last-used` folds it under the existing prefix and needs no second
allowlist, but it also puts the marker inside the set a hand cleanup is told to remove. Widening the
allowlist to the marker name keeps the marker legible as a separate thing but makes the "nothing
else" clause a two-entry list that a future file has to be added to.

Whichever way it goes, the same edit has to settle a second case the rule currently admits and the
probe has no answer for: a directory holding *only* the marker. Recognition accepts it, and the
release rule is "released only while the reaper holds an exclusive lock on its database", which is
undefined when there is no database file to lock, and answering it by opening a channel on
`store.mv.db` would have the reaper create the file it is about to delete. That case is not
hypothetical, and it reopens the race the plan says it closed structurally: if the marker write lands
before the connect in `openAt`, another process's directory holds the marker and no locked database
for the same instant that the empty-directory exclusion was written to cover. Requiring
`store.mv.db` for candidacy, or ordering the marker write strictly after a successful open, closes
it; the plan should say which, since "the recognition rule makes that race structurally impossible
rather than narrow" is a claim the implementer will otherwise inherit without the ordering that makes
it true.

**Question 1, smaller.** The `storeDirectory` sentence in the first-client check opens "Graphitron
keeps the three most recently used of these per checkout", and `these` has no antecedent on the page:
the row tells a consumer there is "one store per project checkout" and never mentions that a
checkout's home holds one directory per DDL hash. A consumer reading the row as it will stand learns
a retention policy over an object the documentation has not introduced, so they cannot tell what is
being kept or why there would be more than one. Introducing the per-schema directory in the same
breath satisfies this; it is a sentence of setup, not a rewrite.

Non-blocking, and genuinely not for this gate to settle: the second unit-tier test group is
"extending the store's own coverage" without naming a class, and `graphitron-model` has no
`GraphitronModelStoreTest` today (`openAt`'s coverage lives in `graphitron`'s `PersistentStoreTest`
and in the `FactStores` helper), so the implementer picks both the class and whether it is new. The
declaring type of `Reaped` is likewise unstated; nested in `StoreReaper` reads as the intent.
