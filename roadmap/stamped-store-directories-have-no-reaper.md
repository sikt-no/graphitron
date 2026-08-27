---
id: R858
title: "Stamped store directories accumulate one per DDL hash and nothing ever removes them"
status: In Review
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

Every process that opens the fact store also releases the stamped directories under the same home
that nothing is using any more, keeping the three most recently used. A contributor doing model work
stops accumulating a several-hundred-megabyte directory per DDL edit, and within the retention count
gives up no warmth at all: a directory another process is holding is never touched, and a stamp still
in rotation is kept for being recently used rather than because anything reasoned about which stamps
are still producible. The workspace in the report that holds 13 stamped directories settles at 3 on
its next build.

That contributor is the beneficiary, and this is deliberately a much smaller feature for a consumer.
A consumer's stamped directories do not multiply with their own schema work: the stamp names
graphitron's fact schema and graphitron's version, so what accumulates in a consumer's home is one
directory per graphitron version that checkout has ever built with. The sweep bounds that too, at the
rate they upgrade rather than the rate they edit. The first-client check below is where the item
speaks to them, and it says that and not more.

What the sweep guarantees, in the terms it can actually keep: it cannot fail a build, whatever it
meets and however it fails; it never touches a directory another process holds; and it reads and
removes nothing outside a directory it has positively recognised as a store's own. What it cannot
guarantee is that no run ever goes cold, and no finite retention could. Past three stamps a genuine
alternation reaps its fourth and the next build on that stamp mints afresh, which is eviction working
rather than a defect: the cost is one cold build of several hundred megabytes, paid by whoever keeps
four stamps in rotation, in the same currency as the residual race priced under "What the lock probe
proves" below. A freshness floor to protect against it would be a second policy over the same
directories, which "What this deliberately does not do" declines for the same reason it declines a
size ceiling.

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

The three are counted per home, which is per checkout under the default cache convention and
whatever the consumer meant it to be under a pinned one. `AbstractRewriteMojo.resolveStoreDirectory`
returns a pinned `<storeDirectory>` verbatim, with no workspace segment, so several checkouts sharing
one pinned home share the three between them and alternate more deeply than three. The documented
uses of pinning are per-workspace and ephemeral CI, so this costs warmth in a configuration nothing
recommends, and the alternative, the store inferring a workspace under a home a consumer chose, is
the guessing the dead-workspace note below refuses for the same reason.

## What the lock probe proves, and the order that keeps it portable

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
* holding the lock and deleting the file in the same breath is permitted. That last result is a
  POSIX property rather than a portable one, which is what decides the deletion order below.

So the probe answers "does anybody hold this database at this instant", and any answer other than
"acquired" leaves the candidate alone. That is a proof about the instant it ran rather than an
inference from age, and it is the mechanism the item asked to see argued rather than assumed.

**The reaper probes, closes the channel, and only then unlinks.** It does not hold the lock across
the deletion, even though the fourth measurement says a POSIX kernel would let it. Unlinking a file
the process holds an open channel on is a POSIX guarantee; on Windows the JDK does not open channels
with `FILE_SHARE_DELETE`, so `Files.delete` on a path this process holds a channel on fails with
`AccessDeniedException`. Windows is a platform we resolve a cache home on
(`%LOCALAPPDATA%\graphitron\model\`, resolved by `AbstractRewriteMojo.resolveStoreDirectory` and
documented in the `storeDirectory` row), no CI job of ours runs there, and the reaper swallows its own
exceptions, so a hold-and-unlink order would have freed nothing on that platform, forever, silently,
with no diagnostic pointing at it, on the platform whose contributors have the same disk growth this
item is about. One order that works everywhere beats a platform-conditional one for a mechanism whose
failures are invisible by construction, and a POSIX-only sweep declared in the manual would be
answering the disk problem for some of the audience and documenting it for the rest.

What that order gives up is the strength of the proof, and the price is already in this plan. Between
the probe releasing the lock and the unlink landing, another process may open the candidate. Then
either the unlink wins, and the opener finds its store gone (on POSIX it keeps writing to an unlinked
inode and the next run boots cold; on Windows the unlink loses instead, throws
`AccessDeniedException`, is caught, and the candidate survives to the next sweep), or the opener wins
and nothing is lost. Every outcome costs warmth, which is the cache's ordinary failure mode and the
one every arm of `openAt` already takes. The window is two syscalls wide, against a candidate nobody
has opened in at least three sessions, so this is a priced risk and not a mitigated one.

## A candidate has to look like a store directory

The home is not always ours. A consumer that pins `<storeDirectory>` may point it at a directory
holding other things, so "every sibling directory of the one I opened" is not a safe candidate rule.

A candidate is a direct child directory of the home, other than the one this run opened, that holds
`store.mv.db`, and holds nothing else beyond files whose names begin with `store`. That prefix covers
every file H2 derives from the database name (the store, its trace log, its temporary files) plus the
recency marker, which is named under the prefix for exactly this reason; see the next section.
Anything else under the home is not this mechanism's business.

`store.mv.db` is *required* rather than accepted as one of two alternatives, and that requirement is
what makes two otherwise undefined states inert:

* **A directory holding only the marker** is not a candidate. That is a reachable state and not a
  corner: `GraphitronModelStore.DATABASE`'s javadoc tells a person that a hand cleanup means removing
  everything in the directory that starts with the database name, and doing exactly that to a swept
  cache leaves the marker behind. A rule admitting it would then have to take an exclusive lock on a
  database that is not there, and opening a channel on `store.mv.db` to find out would have the
  reaper create the file it was about to delete. Requiring the database instead leaves a stray marker
  as a few bytes nobody reads.
* **An empty directory** is not a candidate either, which is also what a store being minted by
  another process looks like for the instant between `createDirectories` and H2's first write.

Two ordering rules keep the mint race no wider than that, and the plan states them rather than leaving
the implementer to infer them from the claim. The marker is written strictly *after* a successful
open, never before, so a directory carrying a marker always carries a database H2 has opened. And
`store.mv.db` is the *last* file the reaper unlinks, so a deletion that fails part way leaves a
directory the next sweep still recognises and retries, rather than the marker-only residue it has just
declared inert; a deletion that gets the database and then fails to remove the directory itself leaves
an empty one, which is the harmless end of the same trade.

What is left is a window the width of H2's own open, where `store.mv.db` exists and the MVStore lock on
it is not yet taken, so the earlier claim that "the recognition rule makes that race structurally
impossible" is retired rather than restated: recognition narrows the race to that window, and the cost
inside it is the priced one from the section above, a store unlinked under its minting process and a
cold run. Recency narrows it again without being asked to: a directory being minted right now is the
most recently used one in the home under either recency source, so it sorts at the top of the retention
and never reaches the probe. That is a consequence of the ordering rather than a rule the reaper
enforces, which is why recognition is stated as the guard and this only as the reason the window is
hard to reach in practice.

## Recency is a fact the store records

Each store directory carries a `store.last-used` marker file, rewritten by every successful
file-backed open (strictly after the connection is open and its stamp checked, per the ordering above)
with the current timestamp as its text; its modification time is what the sweep reads, and the text is
for a person looking at the cache directory to answer "when did I last build this".

The name sits under the `store` prefix deliberately, rather than being `last-used` with the
recognition rule widened to admit it. Under the prefix, the marker is inside the set
`GraphitronModelStore.DATABASE`'s javadoc already tells a person to remove by hand, so the documented
cleanup and the recognition rule agree about the same set of files; and the "nothing else" clause stays
one prefix rather than a prefix plus an allowlist every future file has to be added to. A marker
*outside* the prefix is precisely what survives the documented cleanup, which is how it produced the
undefined case above. The cost is that the marker is not visually distinct from H2's own files in a
directory listing, which its own contents answer.

Recording it beats inferring it. H2's own file times answer "when was this store last written",
which is not the same question: a dev session that only reads keeps a store alive without writing
it. For a directory with no marker, which is every store predating this change, recency falls back to
`store.mv.db`'s modification time, so an existing cache sorts sensibly on the first run after the
upgrade rather than being uniformly ancient. There is no third fallback, and none is reachable: a
candidate holds `store.mv.db` by the recognition rule, so a directory whose recency cannot be read at
all is not a candidate in the first place.

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
through the session's own open. Those two are the whole set rather than a sample: they are the only
callers of `openAt` in main sources across the tree, so a third reporter is a thing a later caller
would have to add rather than one this plan might have missed.

## Implementation

* `graphitron-model/src/main/java/no/sikt/graphitron/model/boot/StoreReaper.java` (new). One static
  entry point taking the home, the live segment to spare, and the retention count, returning
  `Reaped`, which is nested here rather than declared beside the store: the reaper is what produces
  it. Holds the candidate recognition, the recency ordering, the lock probe, and the deletion, whose
  order is fixed by the two rules above (probe, close the channel, unlink the marker and H2's other
  `store*` files, unlink `store.mv.db` last, remove the directory). Catches everything it can throw
  and returns what it managed, so no failure of its own can reach a caller. The retention count is a
  parameter rather than a constant read here, so the test tier can exercise the ordering with small
  numbers.
* `GraphitronModelStore`: a `RETAINED_STAMPS` constant, a `MARKER` name of `store.last-used`, the
  marker write after every successful file-backed open, the once-per-home guard, the sweep call in
  `openAt`, and a `reaped()` accessor. The guard is a set of normalised homes whose check-and-set is
  atomic (`ConcurrentHashMap.newKeySet()` and the boolean its `add` returns), because CI builds the
  reactor with `-T 1C` and two modules in one Maven JVM can reach `openAt` concurrently. Nothing
  unsafe follows from a double sweep, every deletion race being caught, but the count and byte total
  would be split across two reports, and the report is the feature's whole user surface on an
  ordinary build. The private constructor takes the report; the fallback arms need a private
  `open(Reaped)` so the public `open()` keeps its current meaning and the field stays final.
* `GraphitronModelStore`'s javadoc, which carries three sentences this change falsifies rather than
  the one an earlier draft of this plan named. The class-level "A shared store is never deleted by
  this class" states the new rule instead: this class deletes only directories it has proved nobody
  held at the instant it asked, and never the one it opened. `openAt`'s own "It never fails, and it
  never deletes" keeps the half that stays true and drops the half that does not, `openAt` being the
  method that calls the sweep. And `openAt`'s "The stamped path is what makes never discarding safe"
  is now backwards: the stamped path is what makes discarding safe, which is this item's own opening
  claim.
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
  directory, a directory holding the marker and no `store.mv.db`, and a regular file among the
  candidates;
* reports zero and throws nothing for a home that does not exist, and for a home that is a regular
  file;
* leaves a candidate it cannot empty recognisable for the next sweep and reports it as not released,
  which is the total form of the property the unlink order protects: the reaper never leaves a residue
  its own recognition rule would reject. POSIX-only (mode 500 on the candidate directory), skipped
  elsewhere by assumption, since the shape being asserted is a deletion that fails part way and there
  is no portable way to arrange one.

Unit tier in `graphitron-model`, in a new `GraphitronModelStoreTest` in the `boot` package. There is
none today, `openAt`'s coverage living in `graphitron`'s `PersistentStoreTest` and the `FactStores`
helper, and these belong beside the class rather than in a module that consumes it, being assertions
about the sweep call site rather than about capture:

* a second `openAt` of the same home in one JVM reaps nothing, the guard being what stops a reactor
  build sweeping once per module;
* an `openAt` that falls back to memory still reaps, and still spares the live segment;
* a successful file-backed open leaves a `store.last-used` marker, and an open that falls back leaves
  none in the directory it abandoned, which is the marker-after-open ordering the candidacy rule rests
  on.

Unit tier in `graphitron` (`PersistentStoreTest`, reusing the forked-holder shape already there): a
candidate held by *another process* survives a sweep that recency alone would have released, and is
released by the next sweep after that process exits. The existing `HoldingWriter` holds the live
stamp, so this needs a sibling holder that opens an H2 file database at a named path and prints
`HELD`. This is the constraint the item asked to be argued, so it is pinned across a process boundary
rather than only within one JVM.

## User documentation (first-client check)

The `storeDirectory` row in `docs/manual/reference/mojo-configuration.adoc` today asserts "one store
per project checkout, shared by that checkout's modules", which is the claim the retention policy has
to be told against rather than after: a reader who meets a retention sentence one clause later reads a
policy over a population the same paragraph has just called a single object. So the row is corrected
and then extended, and the existing promise in it that deleting the store is always safe is what the
extension leans on:

> ... with one store home per project checkout, shared by that checkout's modules. Inside that home
> the store keeps a separate directory per graphitron version, so upgrading the plugin starts a fresh
> cache instead of reusing one the new version cannot read. Graphitron keeps the three most recently
> used of those directories and releases the older ones the next time it opens the store, so a
> checkout does not accumulate one cache per version it has ever built with. It only releases a
> directory no process is holding, so a `graphitron:dev` session in another window keeps its own cache
> for as long as it runs.

What the row deliberately does not say is that your own schema multiplies these directories, because
it does not. The stamp is a digest of graphitron's own fact schema plus graphitron's version, the home
above it is keyed on the checkout path, and every graph a checkout captures shares one file as a
partition inside it, which is `AbstractRewriteMojo.resolveStoreDirectory`'s own "one file per
workspace, holding every graph that workspace's modules capture". So a consumer with three schemas in
one checkout has one stamped directory rather than three, and abandoning a schema frees nothing at
all. Upgrading graphitron is what leaves a directory behind, the version segment moving on every
release and the fact-schema hash with most of them, and that is a population a reader can recognise in
their own cache listing.

The log line, which is the whole of the feature's user surface on an ordinary build:

> graphitron: released 4 unused fact-store caches (3.1 GB) under
> ~/.cache/graphitron/model/graphitron-a1b2c3d4e5f67890

It names the home rather than calling it "this workspace", because a pinned `<storeDirectory>` has no
workspace segment, and a person who has just lost gigabytes is owed the directory they came out of.

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
  bound that matches what accumulates: one directory per DDL edit for a contributor, one per
  graphitron version for a consumer, each roughly the same size either way. This is also what declines
  a freshness floor to protect the cold run "What lands" prices, a floor being an age policy wearing
  the other sign.
* **Dead workspaces stay dead.** A checkout that is never built again keeps its whole home, because
  nothing opens a store there to sweep it, and this is a real part of the reported 49 GB: 87
  directories spread over many workspace segments. Reaping across workspaces needs two facts the
  store does not have and must not guess, the knowledge that the home was resolved by our default
  convention rather than pinned by a consumer, and the workspace path to test for existence. Both
  live in `AbstractRewriteMojo.resolveStoreDirectory`, so the follow-up is a plugin-side caller of
  the same `StoreReaper` handed a list of homes, not a change to this mechanism. File it as Backlog
  at the Done gate.

## Roadmap entries

* File a Backlog item for the dead-workspace tier described above when this reaches Done. Filed
  during implementation rather than held to the gate, as `sweep-dead-workspace-store-homes`, so the
  reasoning that produced it did not have to be reconstructed later.

## Implementation notes

Two things the implementation settled that the plan left to it, both worth the reviewer's attention
because they are visible in the diff and not in the plan.

**The sweep runs before the open rather than after it.** Every arm of `openAt` needs the same report,
and computing it first is what lets one call site serve all five rather than each arm asking for its
own. Nothing depends on the ordering: the live segment is spared by name in every arm, so a sweep that
runs before the live directory even exists reaches the same answer, and the cost is a handful of
unlinks ahead of a database open.

**The report's wording lives on `Reaped`, not at the two callers.** `Reaped.report(home)` returns the
sentence or empty, so `FactCapture` and `DevMojo` each log one line and neither owns the wording. The
plan named both callers and the log line without saying where the line is built; two copies of it in
two modules would have been the drift the plan avoids everywhere else.

One test the plan prescribes does not run in a container-based agent session. The part-way-deletion
case rests on a directory permission, and a superuser bypasses it, so the test now asks whether an
unwritable directory actually refuses this process a deletion and skips itself when it does not.
GitHub's hosted runners execute as an unprivileged user, so it does run in CI. Everything else in the
Tests section runs everywhere, the cross-process holder included.

`StoreFixtureGuardTest` gained an entry for the new `GraphitronModelStoreTest` under its existing
`LIFETIME` reason, alongside `PersistentStoreTest`'s: a test whose subject is what opening the store
does cannot take a store from a harness that opens it.

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

*Author response.* Taken, on round 2's arm for the naming fork and on both of the two offered closures
for the marker-only case rather than one. The marker is `store.last-used`, and "Recency is a fact the
store records" now argues the name where the reader meets it: under the prefix it is inside the set the
documented hand cleanup removes, so cleanup and recognition agree about one set of files, and the
"nothing else" clause stays a prefix rather than a prefix plus a growing allowlist. Candidacy now
*requires* `store.mv.db`, so a marker-only directory is not a candidate and there is never a database
to lock that is not there; the two states the old rule admitted (marker-only, empty) are called out as
inert, with the reason each is reachable. Both orderings are stated: the marker is written strictly after
a successful open, and `store.mv.db` is the last file unlinked, which is round 2's clause about a
partially failed deletion leaving something the next sweep still recognises. On the claim itself: it is
retired rather than restated. Requiring the database narrows the mint race to the width of H2's own
open, and finding 2's arm (probe, close, unlink) reintroduces a deletion window by design, so
"structurally impossible" is not available to this plan on either count. What replaces it is the window
named and the cost inside it priced, in the same currency as the residual race.

**Question 1, smaller.** The `storeDirectory` sentence in the first-client check opens "Graphitron
keeps the three most recently used of these per checkout", and `these` has no antecedent on the page:
the row tells a consumer there is "one store per project checkout" and never mentions that a
checkout's home holds one directory per DDL hash. A consumer reading the row as it will stand learns
a retention policy over an object the documentation has not introduced, so they cannot tell what is
being kept or why there would be more than one. Introducing the per-schema directory in the same
breath satisfies this; it is a sentence of setup, not a rewrite.

*Author response.* Done, and it turned out to be more than a sentence of setup once round 3 established
that the population is not per-schema. The row now corrects "one store per project checkout" to "one
store home per project checkout" and introduces the per-version directory before any retention is
mentioned, so "those directories" has an antecedent the reader has just met. The section also states
what the row deliberately does not claim and why, since the population the draft implied is one a
consumer can check against their own cache listing and find absent.

Non-blocking, and genuinely not for this gate to settle: the second unit-tier test group is
"extending the store's own coverage" without naming a class, and `graphitron-model` has no
`GraphitronModelStoreTest` today (`openAt`'s coverage lives in `graphitron`'s `PersistentStoreTest`
and in the `FactStores` helper), so the implementer picks both the class and whether it is new. The
declaring type of `Reaped` is likewise unstated; nested in `StoreReaper` reads as the intent.

*Author response.* Both folded in rather than left to the implementer, since neither cost anything to
settle here. The second group is a new `GraphitronModelStoreTest` in `graphitron-model`'s `boot`
package, with the reason it belongs beside the class rather than in the module that consumes it, and
`Reaped` is nested in `StoreReaper`.

### Round 2 (2026-08-27, Spec -> Ready, reviewer session 01Sf8Rk5tvnvn7FMoV1Jai7k)

Verdict: withhold. The plan body is unchanged since the Spec transition, so round 1's two findings
are open on their own terms and I am not restating them; I confirmed both against the tree and
sharpened the first, and I have one new finding of my own on question 1.

What I checked. Every code symbol the plan names exists as named:
`GraphitronModelStore.openAt` / `fileUrl` (which does refuse `AUTO_SERVER`, with the reasoning the
plan leans on) and the class javadoc's "A shared store is never deleted by this class" sentence;
`FactCapture.runInternal` and the `DEMOTED_TO_MEMORY` warning it would log beside;
`DevMojo`'s own `openAt` at the session store, and the `skipInitial` branch that makes it the first
opener on that path, which is what the report's two-caller argument rests on;
`PersistentStoreTest.HoldingWriter`; `AbstractRewriteMojo.resolveStoreDirectory`, which is the only
home resolver and does hold the two facts the dead-workspace follow-up needs; H2 pinned at 2.4.240;
`docs/manual/reference/mojo-configuration.adoc`'s `storeDirectory` row and `dev-loop.adoc`'s
"nothing to clean up". `graphitron-model`'s `boot` package carries the `Store*` unit tier
`StoreReaperTest` would join, and has no recursive-delete helper for the reaper to reuse, so a new
class there is the right shape rather than a parallel one.

I also re-ran the lock probe myself rather than inheriting it, against H2 2.4.240 on Linux, on a
file a store URL of our shape opens: the directory holds `store.mv.db` and no lock file; `tryLock`
on a database this JVM holds through H2 throws `OverlappingFileLockException`; the lock is gone once
the connection closes; and `Files.delete` succeeds with the lock held. The premise holds, and the
recency-over-compatibility argument holds with it.

**Question 1. The lock probe's fourth result is a POSIX property, and the ordering the plan builds
on it is the one Windows refuses.** "A candidate is released only while the reaper holds an
exclusive lock on its database" requires an open `FileChannel` on `store.mv.db` at the moment of
deletion. Unlinking a file with an open handle is a POSIX guarantee, which is why my Linux run
showed it permitted; on Windows the JDK does not open channels with `FILE_SHARE_DELETE`, so
`Files.delete` on a path this process holds a channel on fails with `AccessDeniedException`. That
platform is not out of scope: the `storeDirectory` row documents `%LOCALAPPDATA%\graphitron\model\`
as the Windows cache home, `resolveStoreDirectory` resolves it, and the natives release ships
Windows binaries. No CI job runs there, and the reaper "catches everything it can throw and returns
what it managed", so the failure surfaces as a sweep that reports zero and frees nothing, forever,
on the platform whose contributors would have exactly the disk growth the item is about, with no
diagnostic pointing at it.

What would satisfy this: say what the reaper does when the hold-and-unlink order is refused. Probing
with `tryLock` and then closing the channel before unlinking is the obvious answer and costs only
the residual race the plan already accepts and prices ("the opener runs cold"), which makes it
strictly weaker as a proof and strictly portable; keeping the hold-and-delete order where the
platform allows it and falling back to close-then-delete is the other; declaring the sweep
POSIX-only and saying so in the `storeDirectory` row is a third. Any of the three settles it. What
cannot stand is the current text, which presents one measured platform's behaviour as the proof and
prescribes the ordering as if it were universal, because the implementer will write the ordering as
written and never see it fail.

*Author response.* Taken, on the first of the three arms. The section is retitled "What the lock probe
proves, and the order that keeps it portable", the fourth measurement is labelled a POSIX property at
the point it is stated, and the order is now probe, close the channel, unlink, spelled out both there
and in the Implementation bullet so it cannot be re-derived as hold-and-unlink. The reasoning for
choosing that arm over the other two is stated rather than left implicit: one order everywhere beats a
platform-conditional one for a mechanism whose failures are invisible by construction, which is the
finding's own argument turned around, and declaring the sweep POSIX-only would answer the disk problem
for part of the audience and document it for the rest. Windows is named as a platform we resolve a home
on and do not test on. The cost is now stated as a widened race rather than a strict proof: what
happens on each side of it (POSIX unlinks under the opener and the next run boots cold; Windows throws
`AccessDeniedException`, is caught, and the candidate survives to the next sweep), how wide it is, and
that it is priced rather than mitigated. "A candidate is released only while the reaper holds an
exclusive lock on its database" is gone from the plan.

**Round 1's question-2 finding, sharpened.** The marker-only directory is not a corner the
implementer can reason away, because the tree already documents the operation that produces one:
`GraphitronModelStore.DATABASE`'s javadoc says "a hand cleanup means removing everything in the
directory that starts with it", and a reader who does that to a swept cache leaves a directory
holding `last-used` and nothing else. Recognition admits it and the release rule has no lock to
take, which is round 1's point standing on a reachable state rather than a race. It also decides
round 1's naming fork on its own: folding the marker under the `store` prefix puts it inside the set
that documented cleanup removes, which keeps the two states aligned, whereas a marker outside the
prefix is exactly what survives the cleanup and produces the undefined case. Requiring
`store.mv.db` for candidacy is what makes the residue inert either way; if the plan takes that
route, saying that a partially failed deletion leaves a directory the next sweep must still
recognise (so the marker is not the last file unlinked) is worth one clause.

*Author response.* Taken whole, including the naming fork being decided by the cleanup alignment rather
than by preference; the marker's own section now carries that argument, so a later reader meets it where
the name is introduced rather than in a review round that dies at Done. `store.mv.db` is required for
candidacy, and the clause about partial deletion is there as an explicit rule (`store.mv.db` unlinked
last) with the residue each failure mode leaves, plus a unit-tier test for the total form of it: a
candidate the reaper cannot empty is reported as not released and is left recognisable.

**Round 1's question-1 finding, confirmed and slightly worse than stated.** The `storeDirectory` row
does not merely lack an antecedent for "these": it asserts "one store per project checkout, shared
by that checkout's modules". A reader meeting the new sentence immediately after that reads a
retention policy over a population the same paragraph has just told them is a single object. The
sentence of setup round 1 asked for has to correct that claim, not just precede it.

*Author response.* Corrected rather than preceded: the row's "one store per project checkout" becomes
"one store home per project checkout", and the per-version directory is introduced in the next clause,
before retention is mentioned at all. Round 3's finding is what decided what that clause says.

Non-blocking. The once-per-home guard is a set of normalised homes consulted from `openAt`, and CI
builds the reactor with `-T 1C`, so two modules in one Maven JVM can reach it concurrently: the
guard wants to be a set whose check-and-set is atomic, or the second sweep is not actually
prevented. Nothing unsafe follows if it is not (the same-JVM probe refuses one of the two sweeps and
the deletion races are all caught), but the reported count and byte total can be wrong, and the
report is the feature's whole user surface on an ordinary build. Implementation detail, not for this
gate.

*Author response.* Folded in, since the reason is worth carrying and the fix is one type choice: the
Implementation bullet now says the guard's check-and-set is atomic, names the shape that answers it, and
gives the `-T 1C` reason and the consequence (a split report, not an unsafe sweep).

### Round 3 (2026-08-27, Spec -> Ready, reviewer session 019Ne8e6nm9EEAQ2TpLb6H6A)

Verdict: withhold. The plan body is byte-identical to the Spec transition (`git diff 2f8cc30 HEAD`
on this file is 141 added lines, all of them rounds 1 and 2), so the three earlier findings are open
on their own terms and I am not restating them. I re-checked each against the tree and all three
hold; what I verified is in this commit's message. One new finding on question 1, and a smaller one
on the same question.

**Question 1. The documented sentence tells a consumer that their GraphQL schema is what multiplies
these directories, and nothing about their schema reaches the path.** The `storeDirectory` draft
closes "so a schema you have stopped working on does not keep its cache forever". The stamped path
is a function of two things, neither of them the consumer's schema: `stampSegment()` is
`ddlHash().substring(0, 16) + "-" + generatorVersion()`, where `ddlHash()` digests `DDL_RESOURCE`,
which is *graphitron's own* fact schema shipped inside `graphitron-model`; the home above it is
`userCacheRoot()` plus `graphitron/model/` plus `workspaceSegment(workspace)`, keyed on the checkout
path. Graph identity is a partition *inside* the file, which
`AbstractRewriteMojo.resolveStoreDirectory`'s own javadoc states: "one file per workspace, holding
every graph that workspace's modules capture". So a consumer with three schemas in one checkout has
one stamped directory rather than three, and abandoning a schema frees nothing at all.

What does multiply a consumer's directories is upgrading graphitron: the version segment moves on
every release and the fact-schema hash moves with most of them, so a checkout accumulates one
directory per version it has ever built with. That is the sentence the row wants, and it is a better
one than the draft, because it names a population the reader can recognise.

This is not a wording nit, for two reasons. It is the only place the item speaks to consumers at
all: the body's own account is honestly contributor-facing ("on this repository it changes
constantly: the fact schema is where the work happens"), so the manual draft is where the audience
switches, and it switches onto a false cause. And it blocks the fix rounds 1 and 2 already asked
for: the sentence of setup that has to correct "one store per project checkout" cannot be written
until this is settled, because the population being retained is "one per graphitron version this
checkout has built with", not one per schema. Settling it may also be worth a line in "What lands",
which today reads as though the beneficiary set were larger than the contributor doing model work.

*Author response.* Taken, and the sentence is rewritten onto the upgrade cause rather than patched. The
"schema you have stopped working on" clause is gone; the row now says the store keeps a directory per
graphitron version and that the retention stops a checkout accumulating one per version it has built
with. The section under the quote states positively what the row does not claim and why the path cannot
carry the consumer's schema, citing the three facts this finding assembled (the DDL digest is
graphitron's own, the home is keyed on the checkout path, graph identity is a partition inside the file
per `resolveStoreDirectory`'s javadoc), so a later reader cannot reintroduce the false cause by
reasoning from the row alone. "What lands" gains the paragraph you suggested: the contributor doing
model work is the beneficiary, a consumer gets a smaller version of the same feature bounded by their
upgrade rate rather than their edit rate, and the first-client check is named as where the item speaks
to them.

**Question 1, smaller. "Cannot make a run cold that would have been warm" is not true of any finite
retention, and the plan concedes as much two sections later.** "What lands" offers it as one of
three things that do not move, beside "cannot fail a build", which is a guarantee that does hold.
But "The policy" already draws the boundary: "anything in active alternation stays, *up to the
retention count*". Past three, alternation reaps, and the next build on the fourth stamp runs cold.
That is eviction working rather than a defect, which is exactly why the overstatement is worth a
round: an implementer reading that line as the invariant has been handed a promise they cannot keep,
and the shape of that mistake is a test asserting it or a freshness floor added to protect it. What
would satisfy this: state what the sweep does guarantee (no build fails, no directory another
process holds is touched, nothing outside a positively recognised store directory is read or
removed) and price the cold run at the retention boundary the way the residual race is already
priced.

*Author response.* Taken as prescribed. "Nothing about correctness moves" is replaced by a paragraph
that states the three guarantees in your terms, says outright that no finite retention could promise
more, and prices the cold run at the boundary: one cold build of several hundred megabytes, paid by
whoever keeps four stamps in rotation, in the same currency as the residual race. The paragraph also
names the mistake it is guarding against, a freshness floor, and points at the section that already
declines a second policy over the same directories, so the shape you were worried about has an argument
against it in the plan rather than only an absence.

Non-blocking, in descending order of how much I would care.

* The Implementation list names one javadoc sentence that becomes false, the class-level "A shared
  store is never deleted by this class". `openAt`'s own javadoc carries "It never fails, and it never
  deletes", and, more softly, "The stamped path is what makes never discarding safe". Naming one
  sentence and not the others reads as a claim that the others survive, and `openAt` is the method
  that will be calling the sweep.
* A pinned `<storeDirectory>` gets no workspace segment, `resolveStoreDirectory` returning a pinned
  home verbatim, so retention 3 is applied across however many checkouts share one pinned home. The
  documented uses are per-workspace and ephemeral CI, so this is unlikely rather than impossible, and
  the cost is warmth. Worth a clause only if the retention argument is being touched anyway.
* Confirming round 1's non-blocking note from a different angle: only `FactCapture.runInternal` and
  `DevMojo` call `openAt` in main sources across the whole tree, so the report's two-caller argument
  is not merely plausible, it is exhaustive today. Worth knowing that it is a closed set the
  implementer can rely on rather than a sample.

*Author response to all three.* All folded in. The Implementation section now has a bullet of its own
for the javadoc, naming all three sentences and what each becomes; the third one, "The stamped path is
what makes never discarding safe", is not merely softened but reversed, which is worth the reader's
attention because it is this item's own opening claim read the other way round. The pinned-home clause
is in "The policy", since the retention argument was being touched anyway, and it lands next to the
reason the store must not infer a workspace under a home a consumer chose, which is the same reason the
dead-workspace note gives. And "What it reports" now says the two callers are the whole set rather than
a sample, so an implementer reading it knows a third reporter would be something a later caller adds.
The log line changed while I was there, for a reason that came out of your pinned-home note: it names
the home rather than saying "for this workspace", which a pinned home does not have.

### Round 4 (2026-08-27, Spec -> Ready, reviewer session 01EMLyTVJ6cDXBLkwxRmTMmX)

Verdict: sign off. The revision closes all four findings from rounds 1 to 3 on their own terms, and
I checked each closure against the tree rather than against the author responses.

Both gate questions answer off the plan. What changes: today a stamped directory is minted whenever
graphitron's own fact-schema DDL or version moves and nothing ever removes the previous one, so a
contributor doing model work accumulates several hundred megabytes per DDL edit and a consumer
accumulates one directory per graphitron version their checkout has built with. After this lands,
any process that opens the store sweeps its own home: it keeps the directory it opened plus the two
most recently used others, and releases the rest, but only a directory it has positively recognised
as a store's own and has just taken an exclusive lock on, so a `graphitron:dev` session's cache in
another window is never touched. It reports once what it freed, and the `storeDirectory` row tells a
consumer the population and the retention. The outcome is reachable and the shape is one already
here: the sweep has to live behind `openAt` because the stamp segment is that method's private
knowledge, which is the same argument the class already makes for appending the segment there, and
the reaper's posture (catches everything, costs warmth and never correctness, never fails a build)
is the store's own posture rather than a new one.

What I verified. Every symbol exists as named: `GraphitronModelStore.openAt`, `fileUrl` (which does
refuse `AUTO_SERVER` for the reason the probe leans on), `stampSegment` as
`ddlHash().substring(0, 16) + "-" + generatorVersion()` over `DDL_RESOURCE`, `DATABASE` and its
hand-cleanup javadoc, and all three javadoc sentences the Implementation section says this change
falsifies, quoted accurately. The private constructor and final fields do make an `open(Reaped)`
overload necessary as stated. `FactCapture.runInternal` with `DEMOTED_TO_MEMORY` beside it, and
`DevMojo`'s session open, are the only `openAt` callers in main sources across the whole tree, so
the two-reporter set is closed. `AbstractRewriteMojo.resolveStoreDirectory` returns a pinned home
verbatim with no workspace segment and carries the "one file per workspace, holding every graph that
workspace's modules capture" javadoc the manual section cites; `userCacheRoot` does resolve
`%LOCALAPPDATA%` on Windows. The `storeDirectory` row asserts "one store per project checkout" as
quoted, and `dev-loop.adoc`'s "nothing to clean up" reads as the plan says. H2 is pinned at 2.4.240.
`graphitron-model`'s `boot` package carries the `Store*` unit tier and no `GraphitronModelStoreTest`;
`PersistentStoreTest.HoldingWriter` is the forked-holder shape, and it does open through `openAt` on
the live stamp, so a sibling holder is needed as the plan says. `StoreReaper`, `Reaped`,
`store.last-used` and `tryLock` appear nowhere in the tree yet.

Non-blocking, in descending order of how much I would care.

* The cross-process test asks for two sweeps of one home ("released by the next sweep after that
  process exits"), and the once-per-home-per-JVM guard is exactly what stops a second sweep of one
  home in one JVM. Driving both through `openAt` cannot express it. The plan already supplies the
  seam, `StoreReaper`'s static entry point with the retention as a parameter, and `reaped()`
  returning a nested `Reaped` forces the class public anyway, so calling the reaper directly for
  both sweeps settles it. Worth knowing before writing the test rather than after.
* `FactStores.fileBacked`'s javadoc says "The store never deletes what it finds", which joins the
  three sentences the Implementation section already lists. Its operative claim survives (a shared
  home's live stamp is spared, so a previous case's rows still carry), but the sentence as written
  does not.
* `CatalogSearchIndex.reapSiblings` in `graphitron-mcp` is prior art for the policy shape: keep the
  current hash plus `PRIORS_TO_KEEP` most-recently-modified siblings, delete the rest. It is over
  build output under `target/`, recurses, throws, and needs no lock probe, so there is nothing to
  share and no path where the two mechanisms meet. It is worth knowing that the tree already answers
  the recency-and-a-count question the same way.
* The marker write is a new IO call inside `openAt`, which promises never to fail. Swallowing its
  own failure is the obvious reading and matches the class's idiom, but wrapping it unchecked would
  land inside the existing `catch (RuntimeException e)` and demote a perfectly good warm store to
  memory. The one state where that is reachable is a full disk, which is this feature's own
  audience.
