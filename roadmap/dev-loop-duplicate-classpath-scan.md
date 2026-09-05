---
id: R620
title: "A round hashes a jar only when it must, and never twice"
status: Spec
bucket: dx
priority: 2
theme: dev-loop
depends-on: []
created: 2026-08-10
last-updated: 2026-09-05
---

# A round hashes a jar only when it must, and never twice

## Goal

A `graphitron:dev` round stops paying to establish what a jar contains when Maven has already
established it, and stops establishing it twice when it does have to. The *class census* is the
generator's parsed view of every class on the compile classpath; a *partition* is the set of rows one
source owns in a fact-store relation shared with other sources, and the store keeps a content hash
per source so a later run can tell whether those rows still describe the file. Today a warm round
content-hashes every jar on the compile classpath twice, once for the census's own cache and once for
the store's retention decision, and neither hash knows the other ran. When this lands, the twelve of
fifteen jars that a repository resolved, and that therefore cannot have changed, are not hashed at
all, and the three a local build wrote get hashed once. Measured on this workstation over
`graphitron-sakila-example`, that is about 7 ms of hashing per round where a warm round pays 67 ms
today, against a round whose whole steady-state floor is 60 to 86 ms.

> **Reframed on 2026-09-05**, from "A dev round content-hashes the jar set twice, and R916 left it
> that way", itself a reframe of "The dev loop reads the whole classpath twice per pass". The
> duplicate hash is unchanged and is still here. What is added is the prior question the duplicate
> invited: not why the round hashes twice, but why it hashes at all, given that most of a classpath
> is release artifacts a repository has already identified. That turns out to be the larger half, and
> the one that survives R922, which otherwise takes this item's steady-state case entirely.
>
> **The slug stays `dev-loop-duplicate-classpath-scan`**, deliberately and against its accuracy. R609
> cites this item by slug as well as by id, for the route-not-taken section below, and renaming to
> suit a title is not worth editing another item's file. A future reader who finds the slug
> misleading is reading correctly; this note is the answer.

## What a round pays for a jar today

**Two sites, one question.** `ClasspathCensus.readJar` hashes every jar through
`ClasspathSources.hash`, compares that against the hash it cached, and reuses the parse when they
match. That is the census's own change detector, and it must recompute every round, because comparing
current bytes against a remembered hash is how a content-hash cache works at all.

`StoreRefresh.freshSources` then hashes the same jars again. A warm capture asks which classpath
partitions it can retain, and answers by comparing each census-named source against the
`store_source.stamp` recorded for it, through `ClasspathSources.stamp`. That method memoises, but the
instance is built per capture (`var sources = new ClasspathSources()` in `FactCapture.capture`), so
the memo is empty when the round reaches it and every jar is read a second time.

Every round after the first is warm: `RunStore.reconciles` is true once the graph's anchor row
exists, so `StoreRefresh.prepare` runs on every subsequent round of both cadences.

The second site's population is a subset of the first's. `freshSources` tests only census-named
sources carrying a non-null recorded stamp, and filters on `Files.isRegularFile`, so directory roots
never reach it and the `target/classes` stat walk is not duplicated. Whatever the census hashed
already covers everything the retention decision needs.

**Measured 2026-09-04**, on this workstation, over `graphitron-sakila-example`'s census-visible jar
set as `dependency:build-classpath` resolves it: fifteen jars, 12.6 MB, one `SourceStamp.ofFile` pass
costing 33 to 34 ms warm and 61 ms on the first pass over a cold page cache. A warm round pays that
twice. Stated as its own population rather than reconciled against R916's 55.2 ms over a 28.2 MB
eleven-jar set, which is different hardware and a different classpath; neither figure supersedes the
other.

**The population splits, and it splits in the useful direction.** Twelve of the fifteen carry a Maven
checksum sidecar (`<jar>.sha1`); three do not. The three without are exactly the reactor snapshots
this build installs (`graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-jakarta-rest`),
which are exactly the three that can change during a session. By bytes the sidecar-covered set is
9.8 MB of 12.6, so 78% of what a round hashes belongs to artifacts that cannot have changed. Inside a
reactor build the split is better still: siblings resolve to `target/classes` directories, which are
stat-walked rather than hashed, leaving a jar set that is entirely sidecar-covered.

## Why the sidecar is the right signal

Not because SHA-1 is a good hash. Because its *presence* means the artifact was resolved from a
repository, and repositories do not mutate published coordinates. A locally installed artifact gets
`_remote.repositories` but no checksum sidecar, so the sidecar is precisely the marker separating
"immutable, the path is the identity" from "built locally, may change underneath this session". That
is a claim about provenance, which is stronger than a snapshot of bytes, and it is the same kind of
claim `ClasspathEntry.Origin` already carries.

Observed on the real entries: `jooq-3.20.11.jar` has an mtime of 2026-03-04, six months stale and
matching its download, with its sidecar written 1 ms after it. `graphitron-10-SNAPSHOT.jar` is
stamped at today's build and has no sidecar at all.

The sidecar is written once at download and never maintained, so it goes stale if someone installs
over a release coordinate. Closed by two stats: **trust a sidecar only when it is not older than the
jar it describes.** A locally overwritten jar is newer than its sidecar and falls back to hashing.

`maven-metadata-local.xml` was considered as the signal instead and is worse: in the observed
snapshot directory it is stamped eleven minutes *newer* than the jar it describes, because Maven
touches it on resolution as well as on install, so keying on it would invalidate on rounds where
nothing was rebuilt. The jar's own file is the tighter signal.

## The two changes

**An entry carries the identity its builder already knows.** `ClasspathEntry` gains a supplied stamp,
filled by whoever resolved the classpath. The Maven plugin reads the sidecar and applies the
staleness rule; `graphitron-model` never learns that `.m2` exists, which is the layering the record's
own javadoc already describes: the plugin classifies once, every consumer projects from that one
list. A caller with no identity to give supplies none and everything hashes exactly as today, so a
non-Maven front end is unaffected and the change is opt-in per producer.

The classpath is resolved per round, `withCodegenScope` building a fresh `RunContext` whose
construction calls `resolveCompileClasspath`, so the staleness stats are re-taken every round for
free rather than being fixed at session start.

**The round's stamp travels from the census to the capture.** Whatever value the census used this
round, supplied or computed, is handed to the capture instead of being recomputed there. Two
constraints decide the shape, and both are about the difference between the two consumers.

It must be *this round's* value and never the census's cached one. The census holds a hash across
rounds and its reuse dies with the JVM. The retention decision writes into a store that survives the
build: a partition retained against a stale value keeps rows describing a jar that has since changed,
and nothing recomputes them, so the wrong answer persists.

And the store is not open when the census runs. The census runs at the top of `runPipeline`, the
store opens inside the capture, so the direction of travel is from the census to the capture, as a
value the round carries. `ClasspathCensus.Reading` already carries the census and the round's report,
and `CaptureRequest` already carries the census references, so the per-entry stamps riding the same
way is the smallest change that invents no channel.

There is a correctness gain in the same move, beyond the saved read. `commitStamps` hashes at commit
time today, so a jar rewritten between the census's parse and the flush is stamped with a hash of
bytes nobody parsed, and the partition then claims rows it does not hold. Seeding the memo makes the
recorded stamp describe the bytes the rows actually came from.

## Implementation

**`ClasspathEntry`.** A fourth component, `String suppliedStamp`, null where none. The record keeps a
three-argument constructor delegating to the canonical one with null, so all twenty-four existing
construction sites compile untouched and the real blast radius is the five in `AbstractRewriteMojo`
that have something to supply.

**`AbstractRewriteMojo.classifyElement`.** For a jar entry, resolve `<jar>.sha1`, and where it
exists, is readable, parses as hex, and is not older than the jar, supply it as that entry's stamp.
Anything else supplies null. This is the only code in the tree that knows what a Maven checksum
sidecar is.

**`SourceStamp`.** Stamps become scheme-tagged, `<scheme>:<hex>`, with `ofFile` and `of` producing
`sha256:` and a supplied value arriving as `sha1:`. The class already claims to be the one home for
how a stamp is spelled, and a column holding two algorithms without saying which is exactly the trap
its javadoc warns about. One consequence to declare rather than discover: an existing store's
untagged stamps match nothing after this lands, so the first round against a pre-existing store
re-walks every classpath partition once and is current from then on.

**`ClasspathSources`.** A constructor taking seed stamps, pre-populating the memo that `stamp`
already reads. `StoreRefresh.freshSources` and `commitStamps` are then untouched: both call
`sources.stamp` and hit a seeded memo.

**`ClasspathCensus`.** `readJar` prefers the entry's supplied stamp over computing one, and `Reading`
gains the per-entry stamps this round used, jars only. `Reading` is constructed in exactly one place.

**`CaptureRequest` and `FactCapture`.** The request gains the stamp map; four construction sites, two
of which are `FactCapture`'s own convenience entry points passing an empty map. `FactCapture` gains
one new widest `capture` overload taking the map, and the existing widest delegates with an empty
one, so the roughly twenty test call sites stay untouched.

The two changes are independently landable, in this order: supplied stamps shrink the population,
sharing removes the duplication over whatever population remains. Either alone is an improvement, and
the second is what R922 later narrows further.

## Tests

The observable is a count, not a timing. A timing assertion passes on a machine fast enough to hide
the regression, which is the failure mode R916's own round report exists to catch.

**A seeded `ClasspathSources` answers without reading.** Seed a stamp for a path that does not exist
on disk and assert `stamp` returns the seeded value, where an unseeded instance returns null for the
same path. That is a direct proof that no read happened, and it is the unit the whole sharing half
rests on.

**The retention decision uses the round's value, not a fresh read.** In `WarmStartRefreshTest`'s
shape: capture cold over a fixture jar, take a census reading, overwrite the jar with different
bytes, then run the warm capture with that reading's stamps. With sharing the partition is retained,
because the stamps describe the bytes the census parsed; without it `freshSources` re-reads, sees the
new bytes and rewrites. The mutation that fails this test is dropping the seed, and it cannot pass by
accident on fast hardware.

**A sidecar entry is not hashed, and a stale sidecar is.** Over a temp-dir fixture: a jar with a
valid sidecar contributes the sidecar's value to the reading and is never opened for hashing; the
same jar touched so that it is newer than its sidecar falls back to a computed stamp. Asserting "not
opened" wants a sidecar whose value differs from what the bytes would hash to, so the two cases are
distinguishable by value rather than by instrumentation.

**Census equality against a cold scan**, as every case in `ClasspathCensusTest` already pairs with
its count claim: the risk a cache carries is a stale answer, not a slow one.

**The stamp scheme has one reader that computes.** `SourceStamp.recordedMatches` is called from
exactly one place, `LintFixes`, and it passes an editor buffer, so it only ever asks about schema
files and never meets a supplied jar stamp. The whole scheme rests on that, so it is worth a guard
rather than a comment.

## What this does not do

**It does not stop the round asking.** Deciding whether a jar needs hashing at all, rather than how
cheaply, is R922: told what changed, a steady-state round hashes nothing. This item is what R922
cannot reach, because no watcher covers the local Maven repository, so the cold round and the
one-shot goals still have to establish a jar's identity from the jar. The two compose: under R922 the
census hashes only the jars a delta names, so the map handed to the capture is partial and the
retention decision reads absence as unchanged.

**It does not skip the scan for a retained partition.** That remains out of reach for the reasons
this item recorded when it was first refocused, which R609 cites it for. The retain decision is
derived from the scan's output, `freshSources` iterating the `sourceName` values the scan produced;
the store is not open when the scan runs; and keeping the census whole while skipping the read means
serving the language server a retained jar's classes out of the `jvm_` family, which is the
store-first channel between two components of one run that R612 rejected. R916 has since made the
point moot for a dev session, a jar whose hash matches not being re-parsed at all, so what a skip
would buy is now only the hash this item is about.

**It leaves the jOOQ catalog load alone**, which R916 states is untouched and still paid per round.

## Other solutions we've considered

**A second `store_source` column for the supplied identity**, leaving `stamp` uniformly SHA-256.
Avoids the heterogeneous column and the one-time re-walk, at the cost of a schema change and of
standing a parallel mechanism beside the one that already answers "how do we know this source did not
change". Tagging extends the existing shape where a second column duplicates it. Reconsider if the
migration re-walk turns out to cost more than it looks.

**Statting jars instead of hashing them**, the heuristic the census already applies to
`target/classes`. Sound within a session by the same argument, and cheaper than either half here. It
does not work for the store, because `store_source.stamp` is a content hash and a stat cannot be
compared against one; making it work needs a session-scoped claim that the store was already
reconciled, which is R922's ledger rather than this item's.

**Watching the local Maven repository** so that a delta covers jars too. Recorded and rejected under
R922, because it makes the census's cache validity depend on watch soundness where today the census
is self-validating by construction.

**Reading the sidecar's value without the staleness check.** Rejected: the sidecar is written once at
download and never maintained, so an install over a release coordinate would leave it vouching for
bytes that are gone. Two stats close that, and the check is what lets the presence of a sidecar mean
what this item claims it means.

## Provenance

The measurement and the sidecar split were taken while specifying this item, and they are what turned
it from "hash once instead of twice" into "mostly do not hash". The observation that a classpath is
overwhelmingly immutable artifacts whose identity Maven has already recorded is the user's; the item
had stood for a month framed around the duplication alone.

## Reviewer findings

### Round 1 (2026-09-05, Spec -> Ready, reviewer session 01GD61Lm13gxXbuPS6LqHTUK)

Verdict: withhold. One blocking finding on question one, one smaller finding wanted in the same
pass. Question two passes without reservation, and I would hand the plan to an implementer as
written once question one is settled.

What holds up, checked rather than taken on report. Every class, method, record and test the spec
names exists under that name. The structural claims the plan actually rests on are all true at
trunk: `ClasspathCensus.Reading` is constructed in exactly one place; `CaptureRequest` has exactly
four construction sites, exactly two of them `FactCapture`'s own convenience entry points;
`SourceStamp.recordedMatches` has exactly one caller, `LintFixes`, and it passes an editor buffer;
`new ClasspathSources()` appears once, inside `FactCapture.capture`, so the memo is empty when
`StoreRefresh.freshSources` reaches it; `freshSources` really does test only census-named sources
carrying a non-null recorded stamp behind a `Files.isRegularFile` filter, so the second site's
population is the subset the spec claims; `RunStore.reconciles` is `warm() ||` the anchor row
existing, so every round after the first is warm; `commitStamps` really does hash at commit time for
a source whose stamp was null, which is the latent bug the spec claims to fix in passing; and
`ClasspathCensusTest` really does pair every case with a `coldScan` equality, so the census test the
spec proposes extends a pattern rather than inventing one. The census-to-capture path is as
described and is the strongest part of the plan: `runPipeline` already holds `reading` at the top
and already threads `reading.references()` into `request(...)`, so the per-entry stamps ride an
existing channel and invent none. The cross-references resolve: R609 does cite this item by slug as
well as by id, which is what the slug-retention note claims; R922 exists, is Backlog, and its own
"What it does not do" hands the cold round to this item in the same terms this item uses.

On question two: supplying the stamp on `ClasspathEntry` extends the doctrine that record's own
javadoc already states, the plugin classifying once and every consumer projecting, and it keeps
`graphitron-model` ignorant of `.m2`, which is the capture-boundary principle applied exactly.
Seeding the existing memo rather than adding a channel, and the explicit rejection of a second
`store_source` column as a parallel mechanism, both land on the right side of the principles.

**Finding 1 (question one: is the goal well communicated). The Goal's magnitude claim mixes two
measurement populations, in the one sentence a reader uses to judge whether the item is worth
building, and the arithmetic contradicts itself.**

The Goal closes: "that is about 7 ms of hashing per round where a warm round pays 67 ms today,
against a round whose whole steady-state floor is 60 to 86 ms."

The 67 ms is population A: this workstation, `graphitron-sakila-example`, fifteen jars, 12.6 MB, two
passes of 33 to 34 ms. The 7 ms is derived from the same population and checks out arithmetically
(2.8 MB of 12.6 at 33.5 ms per pass gives 7.4 ms). The 60 to 86 ms floor is population B: R916's
figure over this repo's own thirteen-entry classpath on different hardware, as `roadmap/changelog.md`
records it. "What a round pays for a jar today" then declares, one section later, that population A
is "stated as its own population rather than reconciled against" population B, and that "neither
figure supersedes the other". The Goal performs precisely the reconciliation the plan forbids, and
uses population B as the denominator for a numerator from population A.

The arithmetic makes it visible rather than merely methodological: 67 ms of hashing cannot sit
inside a 60 to 86 ms round. Taken at face value the sentence says jar hashing is between 78% and
112% of a warm round, and that removing 60 ms of it makes the loop roughly twice as fast. Taken as
the modest framing the surrounding prose suggests, it says about 7 ms comes off a 60 to 86 ms round,
a tenth. A reader cannot tell which, and the two readings would justify very different priorities
for the item. The same tension exists inside population B on its own terms, since R916's 55.2 ms
single hash pass also does not fit twice inside a 60 to 86 ms round, so this is not a defect the
spec inherited quietly; it is one the Goal amplifies by making it the headline.

What would satisfy the finding: state the saving against a denominator from the same population.
Either measure a warm steady-state round on this workstation over `graphitron-sakila-example` and
use that as the floor, which also settles whether jar hashing dominates the round or is a tenth of
it, or drop the cross-population denominator and let the Goal say what was actually measured (jar
hashing per warm round falls from about 67 ms to about 7 ms over this jar set), leaving the
fraction-of-a-round claim out until one population supports it. Either is fine; the plan body and
the implementation are untouched by the choice, which is why this is the author's sentence to write
and not mine.

**Finding 2 (question one: the declared consequence is narrower than the change). Scheme-tagging
`SourceStamp` migrates three stamp populations, and the spec declares one.**

The Implementation section declares the migration cost deliberately, "One consequence to declare
rather than discover", and scopes it to "every classpath partition". Two other columns are written
through the same spellings and inherit the tag:

`JavaSourceFacts.refresh` stamps each parsed `.java` file with `ClasspathSources.hash`, which is
`SourceStamp.ofFile`, and compares that against the recorded `java_file.stamp` to decide whether to
rewrite the file's rows. Both sides tag consistently, so the behaviour is the same one-time re-walk
the spec already describes, but it is a second population and a second re-walk.

`FactCapture` writes `store_graph.build_file_stamp` through `sources.stamp(buildFile)`, so it tags
too. Nothing in the tree reads that column back today, so this one is inert, but R643 is specified
against it as a fitness signal that "rides beside every peer answer", and a reader arriving from
there should not have to rediscover which spelling the column holds.

Both are benign and neither changes the design. What would satisfy the finding is the declared
consequence naming its real scope, so the Done-gate changelog entry and anyone reading the migration
note afterwards get the whole of it.

**Non-blocking, no response needed unless you want one.**

Two counts in the Implementation section do not match the tree, and neither is load-bearing, since
the substance in both cases (existing sites keep compiling) holds regardless of the number. I have
left them rather than guessing at the measure you used. "All twenty-four existing construction
sites" of `ClasspathEntry`: I count 14 `new ClasspathEntry(` sites, 13 of them outside the record
itself, or 28 outside it if the `project` / `projectRoots` / `ClasspathEntry::project` factory uses
count as construction sites. No reading gives 24. "The roughly twenty test call sites" of
`FactCapture.capture`: there are 57 in test sources across all six overloads; if the twenty means
callers of the current widest overload specifically, saying so would keep the claim checkable.

The sidecar test as described spans two tiers in one bullet. "A jar with a valid sidecar contributes
the sidecar's value to the reading and is never opened for hashing" is a census-level assertion that
`ClasspathCensusTest` can make with a hand-built entry, while "the same jar touched so that it is
newer than its sidecar falls back to a computed stamp" exercises `AbstractRewriteMojo`'s staleness
rule and needs the plugin's own test tier. An implementer will split it without difficulty; flagging
it only so the split is expected rather than discovered.

`SourceStamp` is named as "the one home for how a stamp is spelled", and the plan has the plugin
supplying a `sha1:` value. Whether the plugin writes that prefix itself or goes through a
`SourceStamp` factory is unstated. The doctrine the spec already cites answers it, so this is a
seam an implementer closes rather than a fork, but a named factory would keep the spelling where the
plan says it lives.
