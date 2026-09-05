---
id: R922
title: "A dev round is told what changed instead of rediscovering it"
status: Spec
bucket: architecture
priority: 2
theme: dev-loop
depends-on: [grain-declares-its-corpora]
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
whole populations to find the one file that moved. When this lands, an observation marks what moved,
each gatherer records in the store which of its inputs it has caught up with, and an input nothing
marked is not read again. Two things change for a developer in a session. A `.java` save stops
content-hashing every source file under the module's compile roots to find the one that was saved,
measured below at 30 to 46 ms of every debounced save on this repo's own example consumer and
proportional to the consumer's sources rather than to the edit; and the console names the file that
triggered each round instead of saying only that a round happened.

The mechanism is a dirty flag, and saying so is not a deflation. Everything below follows from taking
it literally: marks are idempotent so a burst of twenty thousand events is one mark, absence of a
claim is the stale state so a cold start needs no initialisation, and "I cannot say what moved" is not
a third state but every claim deleted at once. What the flag buys beyond the three items it retires is
that a gatherer can only ever be told to do *less* than it does today, never something different, so
it can be adopted one gatherer at a time and a wrong mark costs a re-read rather than a wrong answer.

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

## The mechanism, and where its rows live

A *currency claim*: one row saying that one gatherer has read one instance of one corpus and that
nothing has moved it since. Presence means current, absence means stale. The rows are store rows,
in a new `gatherer_` family, because they are written in the gatherer roster's vocabulary and a
family is named for whose vocabulary its rows are written in. They are not `meta_`, whose charter is
rows that are "a statement of what this file declares, never a record of what a run read", and they
are not `store_`, whose relations key on the graph and the source rather than on the gatherer.

```sql
CREATE TABLE gatherer_current (
  gatherer_name VARCHAR   NOT NULL,
  corpus_name   VARCHAR   NOT NULL,
  instance_key  VARCHAR   NOT NULL,
  claimed_at    TIMESTAMP NOT NULL,
  PRIMARY KEY (gatherer_name, corpus_name, instance_key),
  FOREIGN KEY (gatherer_name) REFERENCES meta_gatherer (gatherer_name),
  FOREIGN KEY (corpus_name)   REFERENCES meta_corpus (corpus_name)
);
```

Every property the design needs falls out of the polarity rather than being added to it:

* **Stale is the initial state**, so a cold session, a store written by another process, and an
  instance nobody has ever read all behave identically and need no initialisation.
* **Marking is a delete and is idempotent**, so twenty thousand `.class` events under one root are
  one delete of one row, and a watcher can mark without reading anything.
* **"I cannot say" is not a state.** An overflow, a subtree registered mid-session and an unreadable
  observation all delete every claim in scope, which is the same as being cold. The *reason* survives
  as a diagnostic string for the console line, because a reader who cannot tell an overflow from a
  first round cannot report usefully on either, but it is not a case any consumer switches on.
* **An unobserved instance is permanently stale**, because nothing ever claims it. A jar in the local
  Maven repository is not watched, so it never carries a claim and is always hashed. That is the
  right behaviour and it arrives without an exception being written for it.
* **Claiming is refused for what is not observed.** A gatherer may only claim an instance the
  observation actually covers; otherwise it would mint a claim nothing can ever invalidate, which is
  the one way this mechanism could manufacture a false current. The refusal lives in the mechanism,
  not in each caller.

**The relation is emptied when a process opens the store.** A claim rests on continuous observation
and nothing observes between processes, so an inherited claim is exactly the false-current case. This
is what makes the rows durable in mechanism and session-scoped in meaning, and it is why the relation
needs no observer column: a second session opening over the same workspace store clears the first
one's claims, which costs that session a round of rediscovery and never a wrong answer.

**A claim is taken in the transaction that writes the rows it vouches for.** A gatherer that throws
has claimed nothing and is simply stale next round; there is no restore-on-failure for anyone to
forget, and no ordering for anyone to get wrong. That is `ClasspathSources.commitStamps`'s existing
argument, that a stamp written after the flush means "these rows are all here" rather than "these rows
were started", made structural by the transaction rather than by call order.

**Marking is immediate and undebounced**, and that ordering is the single most important statement
here. Today's debounce sits between the observation and the action and is precisely what destroys the
information: a burst is coalesced into one bare `Runnable` before anything can record what was in it.
Marks accumulate across the debounce window; only the acting stays debounced, and it stays with
`DebounceExecutor` where it is, since a gatherer has no cadence of its own to debounce (it is called
by a round, it does not decide when to run) and moving scheduling policy into `graphitron-model` would
dissolve the no-overlap guarantee that executor's single thread provides. Marking writes through an
in-memory outbox that the round flushes in its own transaction before it reads, so the watch thread
never waits on the database and nothing is ever read before it is durable.

## Why the gatherer roster is what makes this sound

Using the store's declared vocabulary rather than a parallel one is what turns the mechanism's
soundness from an argument into a theorem.

`meta_corpus` rosters the outside inputs; the three this item touches are `classpath`, `sdl` and
`java-source`. `meta_gatherer` rosters the seven gatherers against the class that is each one's entry
point, and `meta_gatherer_corpus` binds gatherer to corpus. That junction's own comment defines the
word the whole design turns on:

> A gatherer with at least one row here is a crawler, and this junction is the store's definition of
> that word: a transcription pass whose rows about its own corpus may not vary with any other
> corpus's contents.

That is exactly the condition under which a currency claim is sound. For a crawler, "this instance of
my corpus has not moved, therefore my rows about it are still current" holds because its rows about
that instance are a function of that instance alone. For a gatherer with no corpus row it fails: the
`graphitron` gatherer's rows vary with what the `sdl` and `catalog` gatherers produced, so a
per-instance claim would read current while its inputs moved underneath it. The store has already
sorted every gatherer into the two classes and gates the sorting, so the mechanism reads the roster
and refuses the rest rather than deciding for itself.

An *instance* is one member of a corpus: one jar or one `target/classes` root for `classpath`, one
document for `sdl`, one file for `java-source`. Instances are already named in the store, as
`store_source.source_name` and `java_file.file`, so `instance_key` carries a name the store already
uses rather than minting an identifier of its own.

The relation's own owner is the one place this item extends the declared model rather than fitting
into it, and the extension is small and motivated. `gatherer_current` gets a new `meta_gatherer` row,
`observation`, with no `meta_gatherer_corpus` rows: it transcribes no corpus, its rows span every
corpus, and crossing corpora is exactly what a gatherer with no corpus row is for.
`MetaDeclarationGateTest.ownerAndGrainAgreeAboutTheCorpus` exempts such an owner in those words,
"an owner with no corpus rows is exempt, crossing being its job", so the declaration lands inside the
gate rather than beside it.

The grain side is where the declared model genuinely cannot hold this relation yet, and R923 is the
item that fixes it. `meta_grain.corpus_name` is NOT NULL, and the claim's grain names its corpus per
row, so it has no single corpus to declare. Admitting it by making the column nullable would be the
model saying the column does not belong on the relation, and an audit of the roster says the same from
the other direction: 12 of the 40 declared relations are owned by a corpus-less gatherer, so their
grain's corpus is never checked, and at least two of those values are not true. R923 replaces the
column with a `meta_grain_corpus` junction and rephrases the gate against a gatherer's *reach*, the
union of its own corpora and those of everything it depends on, which removes the exemption entirely.

That matters here for more than admission. Under the reach gate the `observation` gatherer reads no
corpus and depends on none, so its reach is empty and its grain is *required* to declare no corpus.
This relation is then correct by gate rather than by exemption, which is the difference between the
model tolerating it and the model asserting it. That is why this item depends on R923 and edits
`meta_grain` not at all.

## Grain, and the one law that is easy to lose

An instance's grain is the grain of the fact it invalidates, and it is not the same for every corpus.
For `classpath` the instance is the root, so twenty thousand file events fold to one. For `sdl` and
`java-source` the instance is the file, because that is what `store_source` and `java_file` are keyed
on.

**The fold belongs to the gatherer, not to the watcher.** The gatherer owns the key, because the key
is the key of the fact it writes, and one corpus can feed readers that want different grains: the
`classpath` corpus feeds the class census at root grain and, eventually, the consumer-class recompile
invalidation that `rebuildCatalog` today does conservatively at file grain. A single fold on the
watcher cannot serve both. So a gatherer registers its fold up front and the observation applies every
registered fold as events arrive, which keeps raw paths transient and needs no cap on anything.

And the law, which has to be written where the mechanism is defined rather than only here, because a
mechanism this small invites a later "simplification" that does not know the precondition is
load-bearing:

> A mark at grain G is safe exactly when the consumer's unit of work is at grain G or coarser.

The reason is dropped events. If the OS watcher silently loses the event for file `C` but delivers the
one for `D`, a consumer whose unit is the whole containing root re-reads that root and picks `C` up;
the loss heals. A consumer whose unit is one file re-reads `D` alone and `C` stays stale until the
session restarts; the loss is permanent. Self-healing is a property of grain, not of good intentions.

The adoption below is at file grain, so it is on the losing side of that law and needs its own
argument rather than the law's protection. It has one, and it is the same one `SourceWalker` already
relies on a call frame away: within a single process, an editor writing a file moves it through the
watcher, and the operations that replace a whole source tree underneath a session, a branch switch or
a `git checkout`, move every file they touch. The events this loses are the ones the OS drops, and the
recovery for those is the escape hatch below rather than a coarser grain, because at this grain a
coarser fold would re-read the whole population and buy nothing.

Three ways an observation can be wrong, and where each is answered, because a design that does not
answer all three trades a correct dev loop for a fast one.

**Watch overflow.** `SchemaWatcher.dispatch` already handles `OVERFLOW` by rescheduling rather than
ignoring it, so the honest translation exists: delete every claim in scope.

**A subtree registered mid-session.** `dispatch` registers newly created directories on the fly, and
files created between the directory appearing and the registration completing are never seen.
`SchemaWatcher.addRoot`, which `regeneratePass` calls when a re-expansion of `<schemaInputs>` finds a
new root, has the same hole. Harmless today because everything is rediscovered anyway; under a claim
it is a silent miss, so both sites delete every claim in scope.

**A root nothing watches.** The watchers resolve their roots once at session start
(`DevMojo.resolveClasspathRoots`, `resolveSchemaRoots`, `resolveSourceRoots`); a later round rebuilds
its `RunContext` and can name a directory none of them cover, a reactor module built for the first
time being the ordinary way. Nothing claims it, so it is permanently stale and permanently
rediscovered. This is the "claiming is refused for what is not observed" rule doing its work, and it
needs no separate check at any read site.

**And an escape hatch, because filesystem watchers do lie.** `-Dgraphitron.dev.rediscover=always`
deletes every claim on every round, which is today's behaviour exactly. A developer on a filesystem
where the watcher under-reports, a container bind mount and a network share being the usual suspects,
gets a working session back with one flag, and a bug report has a one-line bisect. The round's report
says which path it took, so a stale session is diagnosable rather than silent, which is the argument
`ClasspathCensus.Round` already makes for reporting what a round re-read.

## What this item adopts

One gatherer and one console line, chosen so the item delivers the mechanism against a real crawler
and neither client can produce a wrong generated file.

**The `java-source` gatherer stops hashing every source file.** `JavaSourceFacts.refresh` today
computes `ClasspathSources.hash(file)` for every file the walk returned, compares it against the
`java_file.stamp` the store recorded, and rewrites the ones that differ. Measured on this workstation
over `graphitron-sakila-example`'s reactor source roots, generated sources included, 1,316 `.java`
files and 6.0 MiB: 151 ms on the first pass and 30 to 46 ms warm over twenty-five consecutive passes,
paid on every debounced `.java` save. Under a claim the refresh hashes the files with no current claim
and skips the rest, and the claim is taken inside the transaction `rewrite` already opens, so a file
whose write the store refused holds no claim and is re-read next save rather than silently skipped for
the rest of the session.

This is the right first client for three reasons. It is a declared crawler, so the soundness condition
applies to it by roster rather than by argument. It reads one corpus at file grain with no graph
dimension, so its claim is the simplest possible instance of the shape. And it already owns both
halves of its own lifecycle on its own cadence, so nothing else has to change for it to work.

**The round says what changed.** `regeneratePass`, `rebuildCatalog` and `refreshSourceFacts` each name
what they were told at the top of the round, beside the banner they already print: the changed files
where the observation can say, the reason where it cannot. Today a developer watching the log sees
that a round happened and has to guess why, which matters most when the guess is wrong, an editor
writing a stray file or another terminal's build producing a round that looks like it came from the
save just made. This reads a bounded ring of recent raw paths rather than the claims, because it wants
the file and the claims are folded to grain; the ring is diagnostic and never authoritative, so the
worst a dropped event costs here is a name missing from a log line.

`JavaSourceFacts` gains a count of the files it hashed and the files it skipped, reported the way
`ClasspathCensus.Round` reports, so the saving is visible on the console and a regression that starts
hashing everything again is visible too. A `JavaSourceFacts` constructed without an observation
behaves exactly as it does today, which is what the one-shot goals get.

## What this item does not do

**It does not adopt the class census**, although the census's directory walk is the sibling defect and
is measured under "What rediscovers it" above at 7 ms per round. The census is an in-process cache
rather than a store writer, so a claim about it is a claim about JVM-local state, and it feeds the
`catalog` gatherer without being it: a claim filed under `catalog`/`classpath` by the census would
assert something about rows `CatalogFactCapture` may not have written. Untangling that deserves its own
argument, and R620 is already working in that neighbourhood.

**It does not remove the jar hash**, and the limit is structural. `resolveClasspathRoots` watches each
reactor project's `target/classes` and nothing else; no watcher covers the local Maven repository, so a
jar is never observed, never claimed, and therefore always stale. Establishing a jar's identity stays
the classpath's own business, and R620 has landed the cheap way to do it: a classpath entry carries a
stamp its builder already recorded beside the artifact, so the identity is read rather than computed.
The two are complementary by construction, one supplying identity where nothing observes and the other
supplying observation where identity is expensive.

**It does not adopt `SchemaLoader.parsePerSource` or `StoreRefresh`.** Both are R857's, which is
specifying the surrounding change; a schema document is a `store_source` instance of the `sdl` corpus,
so R857's per-document parse is this same shape one corpus over, and its argument for reading a
recorded stamp for a population it was not written for is replaced by a claim it takes itself.

**It absorbs R921.** That item is this adoption stated as a private cache: the same cadence, the same
files, the same skip, reached by a per-writer record of "files committed at a given size and mtime"
rather than by a claim. Its central finding survives and is answered structurally here, that the record
belongs to the writer rather than to the walker, because a file whose store write failed is a file that
holds no claim. R921 becomes a Backlog tombstone naming this item, and its file deletes when this one
reaches Done.

**It does not do propagation.** Marking an instance says its crawler is behind; it does not say which
registrations, partitions or generated units must re-run because of it. That is R924, which walks the
205 foreign keys the schema declares: an edge names the column tuple on both ends, so it says which
rows of the child a given set of parent rows reaches, not merely which relations depend on which. With
`gatherer_current` in the store beside them, "what must run" is a query over declared keys. Putting
the claims anywhere else would have made it hand-written Java over data that is already relational,
which is the whole reason this item pays the schema cost rather than deferring it.

## Implementation

`graphitron-model`, the DDL (`graphitron-model.sql`):

* `gatherer_current` as above, with the table and column comments the schema's own conventions require
  and the grain sentence its `meta_relation` row must echo verbatim.
* A `gatherer_` row in `meta_family` with its introduction and charter, plus a `meta_family_headline`
  entry. The prefix collides with nothing: `meta_gatherer` stays in `meta_`, being a declaration
  rather than a record of what a run read.
* A `meta_gatherer` row, `('observation', '<the observation class>')`, and no `meta_gatherer_corpus`
  row for it. `meta_gatherer_dependency`'s comment says "there are two of those" about corpus-less
  gatherers and becomes wrong with a third; it is rewritten in the same commit.
* A `meta_grain` row for the claim's grain, with no `meta_grain_corpus` row, and a `meta_relation` row
  owning the relation to `observation`. Both the junction and the empty-declaration reading arrive
  with R923, which is why this item depends on it; nothing here edits `meta_grain` itself.

The relation rosters a new base table has to join, each of which fails the build if missed:
`StoreRefresh.wholesale()`'s exemption list (it is written in exemption polarity, so a relation nobody
thought about is emptied every round, which here would delete every claim), the roster in
`FactCaptureAgreementTest`, `ThreadConfinedStore`'s non-empty-relation predicate, and
`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`, which needs a `gatherer_` arm naming
`gatherer_name` as the leading key column.

`graphitron-model`, `no.sikt.graphitron.model.sources`:

* The observation, new. Holds the registered folds, the observed scope and the outbox, and owns the
  claim relation: `register(gatherer, corpus, scope, fold)` validates the pair against
  `meta_gatherer_corpus` and rejects one the roster does not carry, which is the crawler condition
  enforced rather than assumed; `mark(Path)` applies every registered fold into the outbox;
  `markAll(reason)` records a scope-wide deletion and its reason; `flush(dsl)` applies the outbox in
  one transaction; `isCurrent(dsl, gatherer, corpus, key)` answers the read; `claim(dsl, gatherer,
  corpus, key)` inserts inside the caller's transaction and refuses a key outside the observed scope.
  `clearAll(dsl)` empties the relation and is called where the store opens. The grain law goes in this
  class's javadoc on `register`, where a later reader meets it before choosing a fold.

`graphitron-model`, `no.sikt.graphitron.model.capture.java`:

* `JavaSourceFacts` takes an optional observation, registers `java-source` at file grain, hashes only
  the walked files with no current claim, and calls `claim` inside the `dsl.transaction` that `rewrite`
  already opens. `prune` keeps taking the whole walked set, which the walk supplies regardless of what
  was hashed. The class javadoc's sentence about recomputing the hash for every walked file
  deliberately is rewritten rather than deleted: it is the right argument about the persisted stamp and
  the wrong one about this cadence, and the next reader needs both halves stated.

`graphitron-maven-plugin`:

* `SchemaWatcher` takes the observation beside its `DebounceExecutor` and marks before it schedules:
  the resolved path on a suffix match or a delete, `markAll` on `OVERFLOW` and where it registers a new
  subdirectory. `addRoot` calls `markAll` too. The four existing constructors keep their shapes with
  the parameter added; `SchemaWatcherTest` and `CatalogRefreshTest` are the two other construction
  sites.
* `DevMojo.buildSaveListener` takes it and marks the saved document before scheduling, resolving the
  LSP's URI with `Path.of(URI.create(uri))` and calling `markAll` for a URI that is not a resolvable
  `file:` path, which is what an unsaved editor buffer produces.
* `DevMojo` constructs the observation once the store is open, since registration reads the roster, and
  before `bindServer`, since the save listener is built there. It also holds the bounded ring of recent
  paths the announcement reads, one per cadence. `graphitron.dev.rediscover` is a `@Parameter`; set to
  `always` it makes every read stale.
* The three round entry points announce, as described above.

`docs/architecture/how-to/dev-loop-internals.adoc`, "Dev loop: how the goal is wired internally", gains
the observation as a component, since that list is where a contributor learns what the dev JVM is made
of and it currently describes the watchers as signalling the dispatch and nothing more. The fact-model
page gains the `gatherer_` family, which the generated schema reference renders from the `meta_family`
row.

The DDL change bumps `store_stamp.ddl_hash`, so every persisted store is rebuilt once on first use
after this lands. That is the designed behaviour of that column ("any schema edit at all invalidates a
persisted file") and needs no migration.

## Tests

`GathererCurrentTest`, `graphitron-model` unit tier. The mechanism's contract: an unclaimed instance
reads stale, so a fresh store answers stale for everything without initialisation; a claim makes it
current and a mark makes it stale again; marking is idempotent, so a thousand marks under one root
leave one instance stale; `markAll` deletes every claim in scope and carries its reason; a claim for a
key outside the observed scope is refused and cannot be read back as current; `register` rejects a
gatherer-and-corpus pair `meta_gatherer_corpus` does not carry and accepts one it does, which is the
crawler condition; opening the store empties the relation, so no claim is inherited across processes; a
claim taken in a transaction that then rolls back leaves the instance stale.

`JavaSourceFactsTest`, `graphitron-model` unit tier, beside its existing lifecycle anchors. A second
refresh over an unchanged walk hashes nothing and writes nothing, where today it hashes everything. An
edited file is hashed and rewritten while its neighbours are not. **A file whose rewrite the store
refused is hashed again on the next refresh**, which is the finding this absorbs from R921 and the case
a walker-side cache gets wrong. A refresh with no observation hashes everything, which is the existing
behaviour and the one-shot path.

`ClasspathCensusTest` and the census are untouched, and that is deliberate: the eight existing cases
must keep passing unchanged, which is the evidence that the mechanism is additive.

`SchemaWatcherTest`, plugin unit tier: a suffix-matching modify marks the resolved absolute path before
scheduling; a delete marks it; `OVERFLOW` marks everything and still schedules; registering a new
subdirectory marks everything. `DevMojoTest`: `buildSaveListener` marks the saved document before
scheduling, and a non-`file:` URI marks everything.

`DevMojoTest`, driving a round through `regeneratePass` as it already does, asserts the console names
the file the round was told about. That is the completeness evidence for the visible half of the goal;
the `JavaSourceFacts` counts are the evidence for the other half. No timing assertion appears anywhere
in this item: a timing assertion passes on hardware fast enough to hide the regression, and the
hashed-versus-skipped counts are the same fact stated as counts.

The schema gates carry the rest without new tests being written for them, which is the point of
declaring the relation rather than exempting it: `MetaDeclarationGateTest` fails if `gatherer_current`
arrives undeclared, if its comment and its declaration drift, or if its primary key disagrees with its
grain's key shape.

## Why this is worth building rather than fixing five times

Each of the five stages can be fixed alone, and three items already propose to. The reason to build the
shared mechanism is that each local fix buys a cache whose invalidation is a second heuristic, and the
heuristics do not compose: R921 has to argue modification times are safe, R857 has to argue a recorded
stamp can be read for a population it was not written for, and R620 had to thread one round's hashes
from one consumer to another and to argue that a resolver's record may stand in for the bytes. Against
a claim none of those arguments is needed, and the condition that replaces them is not a fourth
heuristic but the crawler property the store already declares and gates.

There is also a pattern here worth naming, because it predicts where the next instance appears. Every
incremental cache in this tree justified its detector against the cost of the work it protects.
`ClasspathCensus` argues hashing a jar set "costs roughly an eighth of parsing it". `JavaSourceFacts`
argues that "hashing source files is cheap beside the parse it is protecting". Both were true against a
cold parse, and both stopped being true the moment a later item cached the parse: the verification
became the entire cost of the round. Any detector argued as cheap relative to work that later gets
cached is the next one of these.

## Other solutions we've considered

**Hold the claims in memory rather than in the store.** No DDL, no family, no roster work, and the
mechanism is otherwise identical. Rejected on the next item rather than on this one: propagation turns
"this instance moved" into "therefore these registrations, partitions and generated units must re-run",
and the edges that answer it are relations. Marks in a field on a Mojo make that join hand-written Java
over data that is already relational, and the schema work this defers has to be done then anyway, with
a mechanism already shipped in the wrong place. Two things the store does *not* buy are worth stating
so nobody later assumes it does: it does not fix the cold start, because a claim rests on continuous
observation and observation ends with the process, which is why the relation is emptied at open; and it
is not what makes a claim crash-safe, since clearing only after the write commits gets that too, the
shared transaction making it impossible to get wrong rather than merely possible to get right.

**An event log with a per-consumer cursor.** Record every path a watcher sees, let each reader fold the
events after its own position, and advance the position on success. Strictly more informative than a
claim, and strictly more machinery for information no planned consumer reads: sequence numbers, a trim
below the minimum committed position, and a cap that collapses the log when a burst outruns it. All
three exist to bound a per-event record, and a claim is idempotent. The cursor's two real properties,
that two readers over one population stay independent and that a failed reader loses nothing, are
properties of a per-gatherer claim too.

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
lifetimes: `RunContext` is rebuilt per round from configuration, while a claim has to outlive any one
round to mean anything.

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
which is why the fallback here *is* the per-source check: an instance with no claim is read exactly as
it is read today.

## Provenance

Found while specifying R620, whose narrow question is why one round hashes the same jar set twice.
Asked why the round has to hash at all, given that a watcher fired it and knew which file moved, the
answer was that nothing carries the answer from one to the other. The dev session's own architecture
discards it three times over. R857 and R921 are the same finding reached from two other stages; R921's
scope is taken over here, and R857 shrinks substantially if this lands first.
