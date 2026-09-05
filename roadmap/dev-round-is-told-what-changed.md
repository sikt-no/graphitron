---
id: R922
title: "A dev round is told what changed instead of rediscovering it"
status: In Progress
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
of the generator when one moves; a *gatherer* is one of the passes that then reads those files and
writes facts into the store, the per-workspace cache of what a consumer's schema, database and
classpath contain, and the store already rosters the seven of them by name. Today every watcher knows
exactly which file fired it and discards that before calling anything, so five separate stages
downstream each rediscover the same answer by reading bytes: hashing, stat-walking or re-parsing
whole populations to find the one file that moved.

What this item delivers is the mechanism that carries the answer across, one gatherer converted to it
as proof that it holds against a real crawler, and the one change a developer sees from either: the
console names the file that triggered a round instead of saying only that a round happened. The
mechanism is two timestamps and one comparison. The store records, on the row it already keeps per
input, when the content behind that row began being read. A session records when it began watching
each population, and which instances it has seen move since. An input is re-read unless it was read
after this session began watching and has not moved since, and the fallback wherever that cannot be
said is exactly today's behaviour, hashing the bytes.

The converted gatherer is `java-source`, which stops content-hashing every source file under the
module's compile roots to find the one that was saved: 30 to 46 ms of every debounced save on this
repo's own example consumer, measured below, and proportional to the consumer's sources rather than
to the edit. That figure is real and it is not the case for this item, because the pass it comes off
is not one a developer waits on. The case is that the same comparison serves the passes they do wait
on, and that those are specified elsewhere and shrink to adoptions of this once it exists.

Three properties keep it small. Stale is what a session starts in, because a floor written at boot
outranks every timestamp a previous session left, so nothing needs initialising and a workspace
edited while the loop was down is re-read rather than believed. "I cannot say what moved" is not a
third state but a population dropping out of observation, which raises its floor and is the same as
being cold. And a gatherer can only ever be told to do *less* than it does today, never something
different, so this is adoptable one gatherer at a time and an answer wrong in the safe direction
costs a re-read.

The larger change is what it lets the *next* item do. Three filed items each propose their own private
cache to remove their own stage's rediscovery, and each has to argue a fresh invalidation heuristic to
do it. Those three arguments collapse into one mechanism here, argued once, against a soundness
condition the store already states and gates.

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
   half of the same read cost 33 to 34 ms warm over fifteen jars when it was measured for R620, whose
   implementation has since landed: an entry now carries a `ClasspathEntry.suppliedStamp` the plugin
   fills from the resolver's own record, so most of that population is verified without being opened.
   The directory walk beside it is untouched by that change and is the figure above.
2. **`StoreRefresh.freshSources`** asks the same question of the same jars a second time, to decide
   which of the store's classpath partitions it can keep. A *partition* is the rows one source owns
   in a relation shared with other sources. R620 closed the duplicate hash by seeding
   `ClasspathSources` with the stamps the census established for the round, so the second pass now
   reads a memo rather than the bytes; what remains unconditional here is the question itself, asked
   of every entry on every round.
3. **`SchemaLoader.parsePerSource`** re-parses every schema document every round. There is no cache
   at all here, though the populations either side of it have one.
4. **`JavaSourceFacts.refresh`** content-hashes every `.java` file under the compile source roots on
   every source-watcher fire, to find the one that moved. Measured over the same example consumer's
   source roots, generated sources included: 1,316 files, 6.0 MiB, 151 ms on the first pass and 30 to
   46 ms warm over twenty-five consecutive passes. `SourceWalker`, one call frame away, already
   re-parses only the files whose modification time moved, so on a warm save the hash is not cheap
   beside the parse it protects; it is the entire cost of the refresh. This is the stage this item
   adopts.
5. **`StoreRefresh`**'s graph-scoped clear deletes and rewrites the whole of the round's graph
   partition regardless of what changed, which then forces a full re-derivation of the
   materialization register downstream of it.

Items 2, 3 and 5 are the passes R857 enumerates; item 1's jar half was R620's and has landed. Each of
those is the same defect seen from one stage, and this item is the shared cause: it builds the
mechanism and takes item 4, which R921 filed, as its first client. Why that one rather than item 1 is
under "What this item adopts" below.

## The mechanism

Two timestamps, one comparison, and a session that holds the observation.

**What the store records.** Each family that partitions by input keeps a row per instance carrying a
content stamp: `store_source` for schema files, jars and jOOQ packages, `java_file` for source files.
Neither row says *when* the content behind that stamp began being read. `store_source.last_seen`
comes closest and is explicitly not it: its comment calls itself "the age half of the age/currency
distinction", recording when a run last named the source in its input set rather than when its bytes
were read. This item writes the currency half. Both relations gain `read_at`, taken *before* the
content is read and written for every instance the pass verified, whether or not its rows changed.

Verification is the read, not the rewrite. A file hashed and found equal to the stamp the store holds
has had its content read as surely as one that was rewritten, and the rows it already carries are what
that read vouches for. A mechanism that moved `read_at` only where rows moved would establish trust for
nothing at all over a store that already agrees, which is every dev session after the first on a
workspace, and it is the arm every later adopter will also take most of the time.

Before rather than at commit, because that is the whole of the concurrency argument. A change landing
between the read and the commit is later than `read_at` and is correctly distrusted; a `read_at`
taken at commit would swallow that window in silence.

**What the session records.** A floor per corpus, the instant this process began watching it, raised
to now whenever observation breaks. And a mark per instance, the instant a watcher saw it move. Both
are in memory, and both are per process by nature: no other process can act on our watcher's
coverage, and a gap between sessions is a gap nobody observed.

Declaring a corpus and beginning to watch it are two events, and the floor belongs to the second. A
gatherer declares its corpus and its fold when it is constructed, which is early in a session's
startup; the floor is raised when that corpus's watcher starts, which is later. Between the two the
corpus has no floor, so nothing in it is trusted and a pass that runs there costs exactly what it
costs today. Hanging the floor on the declaration instead would let a read taken while nothing was
watching be believed for the rest of the session, which is the between-sessions hole reappearing
inside one session, and with an editor already attached to answer from it.

**The comparison.**

> An instance is trusted without re-reading its bytes exactly when its `read_at` is above its
> corpus's floor and above every mark against it. Comparisons are strict, so two events inside one
> clock tick resolve to distrust.

What the mechanism claims follows from that rather than being asserted beside it.

* **Stale is the initial state.** A session's floor is later than every `read_at` a previous session
  wrote, so a cold session trusts nothing and verifies everything, which is today's behaviour and
  needs no initialisation. A workspace edited while the loop was down is re-read for the same
  reason, and that case, a `git pull` or a branch switch between sessions, is the one the rule exists
  to get right.
* **Trust is only ever established under a running watcher.** A pass establishes trust for a corpus
  only where that corpus's watcher was already running when the pass began, because only then is the
  `read_at` it writes above the floor. That falls out of the comparison rather than being a rule
  somebody has to remember, so a startup wired in the wrong order loses the saving and never the
  soundness.
* **"I cannot say" is not a state.** An overflow, a subtree registered mid-session, or a watcher that
  cannot resolve an event raises that corpus's floor, which is the same as being cold for it. Trust
  then rebuilds instance by instance as the next verifying pass writes fresh `read_at` values, so an
  overflow costs one pass rather than the rest of the session. The reason is carried as a diagnostic
  string for the console line, not as a case any consumer switches on.
* **An unobserved instance is permanently verified.** No watcher covers the local Maven repository,
  so nothing there is ever read under an established floor and a jar's stamp is never trusted on
  observation alone. That arrives without an exception being written for it.
* **A mark cannot fail.** It is a map write on the watch thread. There is no store transaction to
  block on the round's, no second connection to open, and no error path in which a lost mark leaves
  an instance reading current for the rest of the session.
* **A crash cannot leave a wrong answer behind.** A session that dies between a mark and the re-read
  it should have caused leaves no durable mark and needs none: the next session's floor outranks the
  `read_at` that mark was about.
* **A false mark is cheap.** Distrust does not discard the stamp, so an instance marked by a save
  that changed nothing is hashed, compared to the stamp it still carries, and skipped. That is what
  keeps a coarse fold adoptable later, and it is the property an earlier draft lost by expressing the
  mark as a null stamp.

Cross-process, the rule is sound rather than merely conservative. If another process captured an
instance while we were watching its corpus, that `read_at` is above our floor, and any change since
would have reached our watcher, so its work counts as ours without either process knowing about the
other.

## Why there is no relation of its own

An earlier shape here was a `gatherer_current (gatherer_name, corpus_name, instance_key)` relation in a
new `gatherer_` family, with an `observation` gatherer to own it. Three things are wrong with it, and
they are worth recording because each one is a general test rather than a detail.

**The key carried a derivable column.** `meta_gatherer_corpus` maps each corpus to exactly one crawler:
`classpath` and `catalog` to the `catalog` gatherer, `sdl` to `sdl`, `java-source` to `java-source`,
`javac` to `compile`, `configuration` to `configuration`. Corpus determines gatherer, so
`gatherer_name` in that key asserted something the roster already implies, and a key with a functional
dependency inside it is a normalisation defect however good the prose around it reads.

**It duplicated a fact the instance's own row carries.** With the gatherer dimension gone the key is
`(corpus_name, instance_key)`, which is the key of the row the store already keeps for that instance.
Currency is an attribute of the instance rather than a relationship between two things, so it belongs
beside the stamp whose content it dates, which is what `read_at` is: one column on a relation that
already exists. A second relation recording the same predicate is a second thing to keep in step, and
the store's own doctrine is that a hand-written derivation must argue that no simpler form expresses
its rule. This one cannot.

**Its owner did not exist.** The `observation` gatherer needed to own the relation was a gatherer that
transcribes no corpus and reads no captured rows, which satisfies
`MetaDeclarationGateTest.ownerAndGrainAgreeAboutTheCorpus` and fails `meta_gatherer_corpus`'s own
sentence about what a corpus-less gatherer is. Needing to invent a roster entry to house a relation is
the roster saying the relation does not belong. It also forced `meta_grain` to admit a grain with no
corpus, which is R923; that item stands on its own evidence and this one no longer depends on it.

## Why there is no race to arbitrate

A watcher can see an instance move while a gatherer is mid-read of it. An earlier draft arbitrated
that with a token handed out when a read began and a stamp write made conditional on no mark having
landed since. The machinery is gone, and it is worth saying why rather than leaving a reader to
wonder whether the race was noticed: the comparison already resolves it.

`read_at` is taken before the content is read, so a change during the read carries a mark above it
and the instance is distrusted next round. A change *before* the read whose event arrives after it is
distrusted too, on the same comparison, and costs one unnecessary re-read. Both directions err
towards reading, which is the direction that is never wrong.

What went with that machinery was a hole in it. The watcher was to skip marking an instance it had
already marked, which is a sound thing to do to a write and an unsound thing to do to an observation:
a second change arriving after a read, against an instance already marked before it, leaves the
arbitration nothing to see and the stamp is written as current. A timestamp the watcher always
advances cannot form that hole, because advancing it is the mark.

## Grain, and the one law that is easy to lose

The grain of a mark is the grain the store partitions that corpus at, which is not the same everywhere.
For `sdl` and `java-source` an instance is one file, so a mark is per document and per source file. For
`classpath` an instance is one jar or one `target/classes` root, so a mark on a root covers every class
under it, which is coarse and is the same grain `StoreRefresh` already retains partitions at.

**The fold from a watch event to an instance belongs to the gatherer**, because the instance key is the
key of the row the gatherer stamps, and one corpus can feed readers wanting different grains: the
`classpath` corpus feeds the class census at root grain and, eventually, the consumer-class recompile
invalidation `rebuildCatalog` does conservatively today at file grain. A gatherer registers its fold,
and the observation applies the registered folds as events arrive.

And the law, which belongs in the observation's javadoc rather than only here, because a mechanism this
small invites a later simplification that does not know the precondition is load-bearing:

> A mark at grain G is safe exactly when the consumer's unit of work is at grain G or coarser.

The reason is dropped events. If the watcher loses the event for file `C` but delivers `D`, a consumer
whose unit is the whole containing instance re-reads it and picks `C` up; the loss heals. A consumer
whose unit is one file re-reads `D` alone and `C` stays stale until the session restarts.

The adoption below is at file grain, so it is on the losing side of that law and argues for itself
rather than resting on it: within one process an editor writing a file moves it through the watcher,
and a branch switch or a `git checkout` moves every file it touches. The events this loses are the ones
the OS drops, and the recovery for those is the escape hatch below, since a coarser fold here would
re-read the whole population and buy nothing.

**And an escape hatch, because filesystem watchers do lie.**
`-Dgraphitron.dev.rediscover=always` registers no corpus at all, so no floor is ever established,
nothing is ever trusted and the session behaves exactly as it does today. A developer on a bind mount or a network
share where the watcher under-reports gets a working session back with one flag, and a bug report has a
one-line bisect. It joins the documented `graphitron.dev.*` property table in
`docs/manual/reference/mojo-configuration.adoc` beside `port`, `debounceMs`, `skipInitial` and
`compile`, with one row saying what it costs and when to reach for it.

## What this item adopts

One gatherer and one console line, chosen so the item delivers the mechanism against a real crawler and
neither client can produce a wrong generated file.

**The `java-source` gatherer stops hashing every source file.** `JavaSourceFacts.refresh` today computes
`ClasspathSources.hash(file)` for every file the walk returned, compares it against `java_file.stamp`,
and rewrites the ones that differ. Measured on this workstation over `graphitron-sakila-example`'s
reactor source roots, generated sources included, which is what `AbstractRewriteMojo.compileSourceRootsOf`
walks: 1,316 `.java` files and 6.0 MiB, 151 ms on the first pass and 30 to 46 ms warm over twenty-five
consecutive passes, on every debounced save. Under observation the refresh hashes only the files the comparison
distrusts and skips the rest, and every file it did hash carries away a fresh `read_at`. On the
mismatched arm that rides the transaction `rewrite` already opens; on the matched arm, which is the
common one and the only one a warm store takes, it is one batched update at the end of the pass. A file
whose rewrite the store refused is on neither arm, so it keeps the `read_at` it had and is re-read next
save rather than silently skipped for the rest of the session. The pass's instant is taken once, before
`SourceWalker.walkFiles` runs, because the walk's parse is a read of the same content the rows
describe.

It is the right first client for three reasons: it is a declared crawler, so the soundness condition
applies to it by roster; its corpus is at file grain with no graph dimension, so its instance is the
simplest possible; and it already owns both halves of its own lifecycle on its own cadence.

What it is not is the most valuable cadence, and the item should not imply otherwise. `refreshSourceFacts`
maintains goto-definition positions on the source watcher, and a developer is not blocked on it the way
they are on a regeneration. The 30 to 46 ms is real and off the critical path. The case for this item is
the mechanism plus a crawler that proves it end to end; R857's schema parse and the census's directory
walk are where the latency a developer feels actually lives, and both become adoptions of this once it
exists.

**The round says what changed.** `regeneratePass`, `rebuildCatalog` and `refreshSourceFacts` each name
what they were told at the top of the round, beside the banner they already print: the changed files
where observation can say, the reason where it cannot. Today a developer sees that a round happened and
has to guess why, which matters most when the guess is wrong, an editor writing a stray file or another
terminal's build producing a round that looks like it came from the save just made. This reads a bounded
ring of recent paths rather than the stamps, because it wants the file and a mark is folded to its
instance; the ring is diagnostic and never authoritative.

`JavaSourceFacts` gains a count of the files it hashed and the files it skipped, reported the way
`ClasspathCensus.Round` reports, so the saving is visible and a regression that starts hashing everything
again is visible too. A `JavaSourceFacts` with no observation behaves exactly as it does today, which is
what the one-shot goals get.

## What this item does not do

**It does not adopt the class census.** The census is an in-process cache rather than a stamp writer, and
it feeds the `catalog` gatherer without being it, so dating a read on its behalf would assert something
about rows `CatalogFactCapture` may not have written. Its directory walk, measured above at 7 ms per
round, is left where it is.

**It does not trust a jar on observation.** No watcher covers the local Maven repository, so a jar is
never under continuous observation and its stamp is always verified. Establishing that identity cheaply
is R620's, which has landed: a classpath entry carries a stamp its builder recorded beside the artifact,
so the identity is read rather than computed. The two are complementary by construction, one supplying
identity where nothing observes and the other supplying observation where identity is expensive.

**It does not adopt `SchemaLoader.parsePerSource` or `StoreRefresh`.** Both are R857's, which is
specifying the surrounding change. A schema document is a `store_source` instance of the `sdl` corpus,
already stamped by the SDL walk through `ClasspathSources.noteRegularFile`, so R857's per-document parse
is this same shape one corpus over.

**It absorbs R921**, which is this adoption reached as a private cache keyed on modification time. Its
central finding survives and is answered by the schema rather than by a second record: a file whose store
write failed is a file whose `read_at` did not move. R921 is a Backlog tombstone naming this item and deletes
when this one reaches Done.

**It does not do propagation.** The comparison says an instance's partition is not trustworthy; it does
not say which registrations, derived rows or generated units must re-run because of it. That is R924, which
walks the foreign keys the schema declares, an edge naming the column tuple on both ends so it says which
rows of the child a given set of parent rows reaches.

## Implementation

`graphitron-model`, the store schema:

* `store_source` and `java_file` each gain `read_at TIMESTAMP`, nullable, with a column comment
  naming `last_seen` as the other half of the age/currency distinction so neither drifts into the
  other's job. Null reads as never, which is the conservative answer for a row that exists before
  anything has read its content. This is a DDL change, so `store_stamp.ddl_hash` moves and every
  persisted store is rebuilt once. That is the store's designed response to a schema change and costs
  one cold capture per workspace; it is also the reason the mark is not a null stamp, which
  "Other solutions" states.

`graphitron-model`, `no.sikt.graphitron.model.sources`:

* The observation, new, and the only new type in the item. Holds each corpus's floor, the roots it
  covers, the registered folds, and the marks. `register(corpus, scope, fold)` validates the corpus
  against `meta_gatherer_corpus` read from the store, so only a corpus some crawler declares can be
  observed, and records its scope and fold. It establishes no floor: `observing(corpus)` does, called
  when that corpus's watcher starts, and until it is called `trusts` answers false for everything in
  the corpus. `mark(Path)` folds a path to an instance and records the instant, on the watch thread,
  with no store access. `lose(corpus, reason)` raises the floor and records why.
  `trusts(corpus, instanceKey, readAt)` is the comparison. `pass(corpus)` hands out the instant a pass
  writes into every row it verifies, taken before any of that pass's reads, so "before the content is
  read" has one implementation rather than one per caller. The grain law goes on `register`, where a
  later reader meets it before choosing a fold.
* `ClasspathSources` writes `store_source.read_at` beside the stamp, carrying the instant its caller
  took before the round's reads rather than the clock at commit. No reader consults the column until
  R857 adopts it, and writing it here rather than there is what makes that adoption a reader-side
  change against a column populated all along; taking the instant correctly here is what stops the
  column being born holding the value the read-window test exists to forbid.

`graphitron-model`, `no.sikt.graphitron.model.capture.java`:

* `JavaSourceFacts` takes an optional observation and the pass instant, registers the `java-source`
  corpus at file grain, selects `read_at` beside the stamp it already selects, and hashes only the
  files the comparison distrusts. A hashed file whose content differed is rewritten as today, with
  `read_at` set inside the transaction `rewrite` already opens; the hashed files whose content matched
  their stamp are collected and given the same instant in one batched update at the end of the walk.
  The instant is a parameter rather than something `refresh` takes for itself, because the walk that
  produced its input has already read the files, so it belongs to the caller that ran the walk.
  `prune` keeps taking the whole walked set, which the walk supplies regardless of what was hashed.
  The class javadoc's sentence about recomputing the hash for every walked file deliberately is
  rewritten rather than deleted: it is the right argument about the persisted stamp and the wrong one
  about this cadence, and the next reader needs both halves stated.

`graphitron-maven-plugin`:

* `SchemaWatcher` takes the observation beside its `DebounceExecutor` and marks before it schedules: the
  resolved path on a suffix match or a delete, `lose` on `OVERFLOW` and where it registers a new
  subdirectory. `addRoot` calls `lose` too. The three existing public constructors keep their shapes with
  the parameter added; `SchemaWatcherTest` and `CatalogRefreshTest` are the two other construction sites.
  A watcher calls `observing` for its corpus once its `WatchService` registrations are in place, which
  is the event the floor is hung on.
* `DevMojo.buildSaveListener` takes it and marks the saved document before scheduling, resolving the LSP's
  URI with `Path.of(URI.create(uri))` and calling `lose` for a URI that is not a resolvable `file:` path.
* `DevMojo` constructs the observation once the store is open, since registration reads the roster, and
  before `bindServer`, since the save listener is built there. Registering that early is harmless now
  that the floor is the watcher's to raise. It also holds the bounded ring of recent paths the
  announcement reads. `graphitron.dev.rediscover` is a `@Parameter`.
* `DevMojo` moves the seed refresh after `startSourceWatcher`. Today `refreshSourceFacts(initialCtx,
  false)` runs at startup so goto-definition answers before the first edit, and `startSourceWatcher`
  runs later, after the warm compiler is built. In that order the seed reads a corpus nothing is
  watching, so its `read_at` values fall below the floor and a warm store's session establishes no
  trust until its first save has paid for it. After the move both still sit inside startup and before
  the "LSP listening" line, so nothing a developer waits on changes, and the warm session is cheap from
  its first save. This is a scheduling choice, not a correctness one: the previous order is safe and
  merely slower, which is the point of hanging the floor on the watcher.
* The three round entry points announce, as described above.

Documentation: `docs/manual/reference/mojo-configuration.adoc` gains the `graphitron.dev.rediscover` row,
and `docs/architecture/how-to/dev-loop-internals.adoc`'s component list gains the observation, since that
list is where a contributor learns what the dev JVM is made of and it currently describes the watchers as
signalling the dispatch and nothing more. That document opens "The dev goal runs five cooperating
components", a count in prose beside the list, so the sentence moves with the list.

Nothing is written to the store off the round's thread, so the session's single shared connection is
used exactly as it is today.

## Tests

`ObservationTest`, in `graphitron-model`, whose own tests carry no tier annotation. A cold session
trusts nothing, its floor outranking every `read_at` a previous session wrote, which is the case the
mechanism's correctness turns on. An instance read above the floor and unmarked is trusted; a mark
after its `read_at` distrusts it; a mark *before* its `read_at` does not, which is what proves marks are
not sticky and that a corpus recovers. Equal timestamps distrust. `lose` raises the floor, so everything
read before it is distrusted and an instance read after it is trusted again, which is the recovery an
overflow needs. A corpus no crawler declares is refused at `register`. An instance outside the
registered scope is never trusted. A fold at root grain maps every path under the root to the root. A
thousand marks under one root cost one entry and no store access.

**A registered corpus that nothing is watching yet trusts nothing**, however recent its rows'
`read_at`, and starts trusting only once `observing` has been called. That is the startup window's
case, and it is the one that separates declaring a corpus from watching it.

**The read window gets its own case, because it is the one place a wrong answer is reachable**: an
instance whose `read_at` was taken before its content was read, marked while that read was in flight,
is distrusted afterwards. The test asserts that ordering rather than the outcome alone, so a later
simplification that stamps `read_at` at commit fails here instead of passing quietly.

`JavaSourceFactsTest`, in `graphitron` at
`graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/JavaSourceFactsTest.java` and carrying
`@UnitTier`, beside its existing lifecycle anchors.

**The warm-store arm is the one the adoption is judged on**, because it is the arm a developer is
actually in and the arm on which an earlier draft of this item saved nothing. The fixture seeds the
store so that the first refresh rewrites no file, and then asserts that the refresh still leaves every
file trusted and that one save afterwards hashes the saved file alone. Written against a cold store the
same assertions pass for the wrong reason, every file having been rewritten, which is why the seeding
is the test rather than a detail of it.

Beside it: a second refresh over an unchanged walk under observation hashes nothing and writes nothing,
where today it hashes everything. An edited file whose instance was marked is hashed and rewritten while
its neighbours are not. A marked file whose content did not actually change is hashed, compared to the
stamp it still carries, not rewritten, and left trusted again, which is the property that keeps a false
mark cheap rather than permanent. A file whose rewrite the store refused keeps its old `read_at` and is
hashed again on the next refresh, which is the finding absorbed from R921. A refresh with no observation
hashes everything, which is the existing behaviour and the one-shot path.

`ClasspathCensusTest` and the census are untouched, which is the evidence that the mechanism is additive.

`SchemaWatcherTest`, plugin unit tier: a suffix-matching modify marks the resolved absolute path before
scheduling; a delete marks it; `OVERFLOW` loses the corpus and still schedules; registering a new
subdirectory loses it; and a watcher calls `observing` once its registrations are in place, not before.
`DevMojoTest`: `buildSaveListener` marks the saved document before scheduling, and a non-`file:` URI
loses the corpus.

`DevMojoTest`, driving a round through `regeneratePass` as it already does, asserts the console names the
file the round was told about. That is the completeness evidence for the visible half of the goal; the
`JavaSourceFacts` counts are the evidence for the other half. No timing assertion appears anywhere: a
timing assertion passes on hardware fast enough to hide the regression, and the hashed-versus-skipped
counts are the same fact stated as counts.

Beside the store's existing schema gates, one case holds `read_at` to the relations that carry a stamp,
so a family that gains a stamp later and no currency column beside it fails rather than quietly falling
outside the mechanism.

## Why this is worth building rather than fixing five times

Each of the five stages can be fixed alone, and three items already propose to. The reason to build the
shared mechanism is that each local fix buys a cache whose invalidation is a second heuristic, and the
heuristics do not compose: R921 has to argue modification times are safe, R857 has to argue a recorded
stamp can be read for a population it was not written for, and R620 had to thread one round's hashes
from one consumer to another and to argue that a resolver's record may stand in for the bytes. Against
this comparison none of those arguments is needed, and the condition that replaces them is not a fourth
heuristic but the crawler property the store already declares and gates.

There is also a pattern here worth naming, because it predicts where the next instance appears. Every
incremental cache in this tree justified its detector against the cost of the work it protects.
`ClasspathCensus` argues hashing a jar set "costs roughly an eighth of parsing it". `JavaSourceFacts`
argues that "hashing source files is cheap beside the parse it is protecting". Both were true against a
cold parse, and both stopped being true the moment a later item cached the parse: the verification
became the entire cost of the round. Any detector argued as cheap relative to work that later gets
cached is the next one of these.

## Other solutions we've considered

**A claim relation of its own.** `gatherer_current (gatherer_name, corpus_name, instance_key)` in a new
`gatherer_` family, owned by a minted `observation` gatherer. Rejected under "Why there is no relation
of its own" above, on three counts that are each a general test: the key carried a column
`meta_gatherer_corpus` already determines, it restated a currency that belongs beside the stamp on the
instance's own row, and it needed a roster entry invented to house it. The last is the one worth carrying
forward as a habit: a relation that has to mint an owner is a relation the model is refusing.

**Express the mark as a null stamp.** The store already reads a null stamp as "not to be trusted":
`ClasspathSources.upsert` nulls it while a partition is being rewritten and `StoreRefresh.freshSources`
skips a null. An earlier draft of this item took that as the mark itself, which made the mechanism a
schema change of no columns at all. Rejected on three counts, in increasing order of severity. It is
lossy: nulling discards the content identity, so an instance marked by a save that changed nothing must
be rewritten where a surviving stamp would have let it be skipped, which is what would have made a
coarse fold unadoptable. It puts a store write on the watch thread, where the store is one JDBC
connection shared with the round, so the mark either joins the round's open transaction or waits behind
it on a minute-long lock budget, and a watcher that waits is a watcher whose queue overflows, costing
the observation the mark was there to protect. And for the client this item actually adopts it cannot be
done at all: `java_file.stamp` is NOT NULL, and its comment argues the constraint.

**Keep the whole record in memory, the currency included.** A field on the Mojo and no schema change:
the session remembers what it verified as well as what moved. Rejected because the verification is not
the session's to own. The stamp it dates outlives the process and is shared between the graphs of one
workspace and between processes over one workspace, so a capture in another process is work this
session should be able to count, and a `read_at` on the row is what lets it. What genuinely is
per-process is the *continuity* of observation and the marks it produces, and those are the only parts
this item keeps in the session.

**An event log with a per-consumer cursor.** Record every path a watcher sees, let each reader fold the
events after its own position, and advance the position on success. Strictly more informative than a
mark, and strictly more machinery for information no planned consumer reads: sequence numbers, a trim
below the minimum committed position, and a cap that collapses the log when a burst outruns it. All
three exist to bound a per-event record, where a mark is one instant per instance that a later event
simply overwrites. The cursor's two real properties, that two readers over one population stay
independent and that a failed reader loses nothing, hold here too: a reader that failed wrote no
`read_at`, so it is distrusted next round without anything having to remember that it failed.

**A destructive drain.** A round takes the accumulated changes and resets. Rejected because the class
census reads the `classpath` corpus from both cadences, so a drain by whichever round arrives first
leaves the other told that nothing moved while an unread class file sits on disk, and because a
consumer that throws has to undo the drain by hand.

**The watchers fold, and hand gatherers a key.** Simpler wiring, and wrong on ownership: the key is the
key of the fact the gatherer writes, so the gatherer knows it and the watcher does not, and one corpus
can feed readers at different grains. The `classpath` corpus feeding the census at root grain and the
consumer-class recompile invalidation at file grain is the case that decides it.

**The gatherers own the debounce as well as the fold.** They have the knowledge for the fold and not
for the debounce: a gatherer is called by a round rather than deciding when to run, so it has no
cadence of its own, and giving each an executor would put scheduling policy into `graphitron-model` and
dissolve the no-overlap guarantee `DebounceExecutor`'s single thread provides. What the question did
surface is that the debounce sits in the wrong place relative to the marking, which is fixed here
whoever owns it.

**Carry the marks on `RunContext`.** It is the value a round already threads everywhere. Rejected on
lifetimes: `RunContext` is rebuilt per round from configuration, while the floors and the marks have to
outlive any one round to mean anything.

**Watch the local Maven repository too.** Registering the classpath jars' parent directories would let
jars be observed, and a steady round would hash nothing. Rejected for now on two grounds: it makes the
census's cache validity depend on watch soundness where today the census is self-validating by
construction, and it extends the watch surface outside the project tree into a directory shared with
every other build on the machine. R620 has already reached the same steady state more cheaply, by
reading the identity the resolver recorded beside the artifact instead of watching for it to change.

**A per-source content-hash cache in each gatherer, and no shared mechanism.** What R857 and R921
propose in their own scopes, and it works: it is strictly more trustworthy than an observation, because
it reads the bytes. It is also strictly more expensive, because it reads the bytes, and on the cadences
those items are about the read is the whole cost. The two are complementary rather than exclusive,
which is why the fallback here *is* the per-source check: an instance the comparison distrusts is read
exactly as it is read today.

## Provenance

Found while specifying R620, whose narrow question is why one round hashes the same jar set twice.
Asked why the round has to hash at all, given that a watcher fired it and knew which file moved, the
answer was that nothing carries the answer from one to the other. The dev session's own architecture
discards it three times over. R857 and R921 are the same finding reached from two other stages; R921's
scope is taken over here, and R857 shrinks substantially if this lands first.

## Reviewer findings

### Round 1 (2026-09-05, Spec -> Ready, reviewer session 01EBHF6eU9hfN88dLezaQ64k)

Verdict: withhold. Two blocking findings, one on each gate question, and they are one seam seen from
two sides: the mechanism is coherent, and the wiring the item specifies for its one adopting client
does not yet realise it. Every symbol, method, relation, column and test class the spec names exists
as named, bar the four small slips in the non-blocking list; the verification narrative is in this
commit's message.

The goal communicates. Stated without the plan sections: a developer running `graphitron:dev` will see
each round's console line name the file that caused it rather than only announcing that a round
happened, and the `.java` save cadence will stop content-hashing the whole source tree to find the one
file the watcher already identified. The item is also honest about which half a developer feels, which
is the console line and not the milliseconds, and that honesty is what makes the second half's real
case (the mechanism, proved against a declared crawler) legible rather than oversold.

**Blocking, question 1: `read_at` is written only where `rewrite` runs, so a file that is verified and
found unchanged never becomes trusted, and over a warm store the adoption saves nothing.**
`JavaSourceFacts.refresh` hashes each walked file, `continue`s past it when the hash equals the stamp
the store recorded, and opens `dsl.transaction(tx -> rewrite(tx.dsl(), file, stamp))` only on a
mismatch. "Implementation" has `JavaSourceFacts` write "the new `read_at` through the transaction
`rewrite` already opens", and "Tests" confirms the marked-but-unchanged file is "not rewritten". So the
only files that ever carry a `read_at` above this session's floor are the ones whose content differed
from what the store held.

Three things follow, in increasing order of severity. First, the mechanism's own claim that "trust then
rebuilds instance by instance as the next verifying pass writes fresh `read_at` values, so an overflow
costs one pass rather than the rest of the session" requires a verifying pass to write `read_at` for
files it verified without rewriting. It does not, so an overflow in a workspace nobody is editing costs
exactly what that bullet says it does not, the rest of the session. "A false mark is cheap" is cheap
once and permanent thereafter: the file marked by a save that changed nothing is re-hashed on every
later refresh for the life of the session, never regaining trust.

Second, and this is the one that decides the finding, the item saves nothing at all in the session shape
it is written for. A dev session over a warm store is the case `JavaSourceFacts`' class javadoc calls out
by name, "what makes a cold dev session over a warm store cheap ... the store already agrees and nothing
is written". The startup seed (`refreshSourceFacts(initialCtx, false)`) hashes all 1,316 files, rewrites
none, and writes no `read_at`. Every save after it therefore finds every file's `read_at` below the boot
floor, distrusts the whole corpus, and hashes the whole tree exactly as today. The 30 to 46 ms the item
is measured against survives untouched wherever the store was already warm, which is every dev session
after the first on a workspace. R620's changelog entry states the outcome this item was expected to
reach, "a steady-state round hashes nothing"; as specified it reaches that only in a session whose store
started cold, and there only for the files that first pass happened to rewrite.

Third, the specified test cannot see any of it. "A second refresh over an unchanged walk under
observation hashes nothing and writes nothing" passes when the first refresh ran against a cold store,
because then every file was rewritten and every `read_at` written. It is green on the arm that works and
silent on the arm that does not, which is worse than no coverage.

What would satisfy the finding is stating where `read_at` moves for a file that was hashed and found
equal to its stamp. That file's content *was* read, and the stamp it still carries is vouched for by that
read, so the currency the column records is genuinely established; what is missing is a write, on a path
that today writes nothing on its common arm. The shape is a design call and the author's: a transaction
per verified file, a single batched update at the end of the walk against instants taken before each hash,
or something else. Whichever it is has to preserve what the current sentence buys, that a file whose
rewrite the store refused keeps the `read_at` it had, because the verified-unchanged file and the
refused-rewrite file now sit on the same path and need opposite outcomes. The test that separates the two
is a refresh over a *warm* store, where nothing is rewritten, followed by one save: the neighbours must
not be hashed.

**Blocking, question 2: the floor is established where the item wires registration, which is not where
watching begins, so the startup seed writes trusted `read_at` for a corpus nothing is watching yet.** The
mechanism defines a floor as "the instant this process began watching" a corpus, and `register` both
validates the corpus and establishes its floor. The implementation then places registration somewhere
else: `JavaSourceFacts` "registers the `java-source` corpus at file grain", and `DevMojo` constructs
`JavaSourceFacts` off `sessionStore.dsl()` early, before `bindServer`, while the source watcher is
constructed much later in the watcher-start block. The seed `refreshSourceFacts(initialCtx, false)` runs
between the two.

Over a cold store that seed writes a fresh `read_at` for every source file, above a floor established for
a corpus with no watcher behind it. A `.java` file saved in that window is unobserved, and the LSP is
already bound by then so an editor is already attached. That file reads as trusted for the rest of the
session while the store holds declarations from before the edit, and since this family feeds
goto-definition and hover, the symptom is positions that silently disagree with the file, healed only by
restarting the session. This is precisely the hole the floor rule closes correctly one paragraph earlier
for the between-sessions case, reappearing inside a session because two events the mechanism treats as
one are wired apart.

What would satisfy it is making the floor and the start of watching the same event, so that no `read_at`
written while a corpus's watcher is not yet running can ever sit above that corpus's floor. Registering a
corpus when its watcher starts, holding the seed until after the watchers are up, or having watcher start
raise the floor are all available; choosing among them is the author's, and the choice interacts with the
first finding, since the seed is also where the warm-store case would otherwise establish trust.

**Non-blocking.** Four slips in the implementation instructions, left for the author rather than
corrected here only because they sit in the same sections being revised. `JavaSourceFactsTest` is in the
`graphitron` module, not `graphitron-model`
(`graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/JavaSourceFactsTest.java`, carrying
`@UnitTier`); `graphitron-model`'s own tests carry no tier annotation at all, so `ObservationTest` there
is that module's plain unit tests. `ClasspathSources` lives in `no.sikt.graphitron.model.sources`, not
the `no.sikt.graphitron.model.capture` heading it is filed under. `SchemaWatcher` has three public
constructors, not four. The DDL declares 209 foreign keys, not 205.

Two smaller things, neither of which changes what gets built. `dev-loop-internals.adoc` opens "The dev
goal runs five cooperating components", a count in prose beside the list the item plans to extend. And
`ClasspathSources` "writes `store_source.read_at` where it writes the stamp", which is commit time,
while the item's own rule takes the instant before the read; nothing reads that column until R857 adopts
it, so it is inert here, but the column would be born holding the value `ObservationTest`'s read-window
case exists to forbid.

### Round 1, author response (2026-09-05)

Both findings accepted, and neither needed a design fork to answer.

Finding 1 is answered by moving `read_at` off the rewrite and onto the verification: every file a pass
hashed carries the pass's instant, through `rewrite`'s transaction on the mismatched arm and through one
batched update on the matched arm. "The mechanism" now states the general form, that verification is the
read and not the rewrite, because the same slip is available to every later adopter and stating it only
in the adoption would leave it there. The instant moved with it. It is taken before
`SourceWalker.walkFiles`, the walk's parse being a read of the same content, and it is a parameter of
`refresh` rather than something `refresh` takes for itself.

Finding 2 is answered by splitting the two events the item had wired as one. `register` declares a
corpus's scope and fold and establishes no floor; `observing(corpus)` does, called by a watcher once its
registrations are in place. Correctness now holds under any startup order, since a pass that runs before
a watcher is up writes `read_at` below the floor and establishes nothing. That leaves the startup order
as a scheduling choice, and the item makes it: the seed refresh moves after `startSourceWatcher`, so a
warm store's session is cheap from its first save rather than from its second.

The four slips are corrected, and the two smaller notes with them: `dev-loop-internals.adoc`'s "five
cooperating components" is named as prose that moves with the list, and `ClasspathSources` carries the
instant its caller took rather than the clock at commit. The foreign-key count is now stated without a
bare number here; R924 carries the count and is repointed at `meta_relation_reference`, since a DDL grep
and the review's figure disagree by one and the view is that item's own source of truth either way.

The warm-store arm the finding named is now the case the adoption is judged on.

### Round 2 (2026-09-05, Spec -> Ready, reviewer session 01EBHF6eU9hfN88dLezaQ64k)

Verdict: sign off. Both round-1 findings are answered at the level they were raised, and each answer is
stated where the next reader will need it rather than only where the finding landed.

Finding 1 is resolved by making verification the write, not the rewrite. "The mechanism" now carries the
general form, which is the right home for it: the same slip is available to every later adopter, and the
paragraph names the arm that made it invisible, a store that already agrees. The adoption has two write
arms, `rewrite`'s own transaction where the content differed and one batched update where it matched, and
they are disjoint in exactly the way the refused-write property needs, so a file whose rewrite the store
rejected is on neither and keeps the `read_at` it had. Traced against the tree: the pass instant taken in
`refreshSourceFacts` before `SourceWalker.walkFiles` and passed into `refresh` precedes every read the
pass makes, including the hash, since `ClasspathSources.hash` reads bytes whether or not the walker's
mtime cache re-parsed the file. The warm-store sequence now closes: watcher up, floor raised, seed hashes
all 1,316 files, matches every stamp, and writes the pass instant above the floor, so the next save hashes
the saved file alone. Making the seeded warm store the case the adoption is judged on, with the seeding
named as the test rather than as fixture detail, is what stops the earlier green-for-the-wrong-reason
assertion from coming back.

Finding 2 is resolved by splitting declaration from observation, and the split is better than the fix the
finding asked for. `register` records scope and fold; `observing(corpus)` raises the floor once a
watcher's `WatchService` registrations are in place; until then `trusts` answers false for the whole
corpus. Soundness now holds under any startup order, and the new claim bullet says so as a consequence of
the comparison rather than as a rule to remember, which is the same move the rest of that section makes.
The seed's move is then a scheduling choice and is argued as one. Checked against `DevMojo`: the seed sits
at `refreshSourceFacts(initialCtx, false)` before `maybeStartIncrementalCompiler`, the three watcher
starts follow, and the "LSP listening" line follows those, so the move keeps the seed inside startup and
ahead of the line a developer waits for, as the item claims.

Every corrected slip checks out: `JavaSourceFactsTest` at the path and annotation given,
`ClasspathSources` in `no.sikt.graphitron.model.sources`, three public `SchemaWatcher` constructors, and
the foreign-key count deferred to `meta_relation_reference`, which is the right answer to a number two
counts disagreed on. `refresh`'s new instant parameter has one main-source caller and one test-support
caller, so the parameter is cheap.

Two things left to the implementer, neither a fork: how a `SchemaWatcher` names the corpus it calls
`observing` and `lose` for, since one class serves three corpora and the registered scope already maps
roots to corpora; and what instant the no-observation path passes, that path hashing everything by
definition.
