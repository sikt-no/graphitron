---
id: R914
title: "The fact store cache grows without bound, and a large store stalls every build that opens it"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---
# The fact store cache grows without bound, and a large store stalls every build that opens it

## Goal

A workspace's fact store stays a cache: bounded in size, cheap to refresh, and never the reason a
build waits. When this lands, a consumer running `graphitron:generate` (including Quarkus dev mode,
which runs the goal on every start) pays seconds for the store rather than minutes, the cache on a
developer's machine stays a size they would not think to delete, and the log names the store when it
is the cost. This is [issue 544](https://github.com/sikt-no/graphitron/issues/544).

## What is wrong

**A store file is almost entirely dead space.** A 443 MB store from a real workspace holds 193,863
rows across 151 tables, a few tens of MB of live facts. The rest is chunks that MVStore has not
reclaimed. A file-backed store closes with a plain connection close, so H2 gets its default 200 ms of
compaction, which at these sizes reclaims nothing, and every clear-and-recapture cycle leaves more
behind. On the reporting consumer the file reached 21 GB after nine days.

**The cache is bounded by a count of directories, not by bytes.** `StoreReaper.sweep` keeps
`RETAINED_STAMPS` (three) stamped directories per workspace, the live one included, and evicts the
rest by recency. That caps how many stores a workspace holds and says nothing about how large any of
them may grow. The sweep also runs only on the home the current build opens, so a workspace nobody
builds in any more is never revisited and keeps its three stores indefinitely.

**A stamp rotation multiplies that across every workspace at once.** `stampSegment()` is the first
sixteen hex digits of the DDL hash plus the generator version. A consumer rotates it by taking a new
release; this repo rotates it on any edit to `graphitron-model.sql`. Each rotation pushes a fresh
full-size store into every workspace's three retained slots.

**A large store costs the build on the write path and the editor on the read path.** On the write
path, `StoreRefresh.clear` deletes row by row with `SOURCE_NAME IN (...)` per table; on the
reporting consumer that took 2 min 18 s of a 3 min 28 s run, ended in `Timeout trying to lock table
"JVM_METHOD"` at the 60 s `FILE_LOCK_MILLIS` budget, and demoted the run to an in-memory capture
that then paid another minute capturing cold.

On the read path, a query joining `intent_resolved_field_claim` and `intent_column_match_claim` per
field, alongside `java_class_declaration`'s javadoc and source positions, ends in H2 `57014`. That
code is `StoreReader.STATEMENT_CANCELLED`, raised when a bounded reader's `ReadBudget` expires
(`DevMojo` sets 3 s interactive, 30 s session, 60 s MCP); a `generate` run reads through
`RunStore.handle()` over the store's unbudgeted `dsl()` and cannot raise it. The javadoc and
position columns place it further still: `CatalogFactCapture` keeps those out of capture because
they "live on the LSP source walker's cadence and are joined at request time". So this is an editor
hover or completion giving up on a bloated store, not a `generate` stalling. It is a second surface
the same bloat degrades, and it is why bounding the file is not only a build-time concern; it is not
evidence about the clear.

## What the measurements show

Measured 2026-09-03 with the pinned H2 2.4.240, against copies of real stores.

| Store | Size | After `SHUTDOWN COMPACT` | Reclaimed | Time |
|---|---|---|---|---|
| A workspace store, idle | 443 MB | 26.7 MB | 94% | 829 ms |
| The largest store on the machine | 864 MB | 59.9 MB | 93% | 4.1 s |

A machine carrying ten workspaces held 7.4 GB across 21 `store.mv.db` files, no workspace holding
more than the three the reaper retains, and 1.81 GB of it in a worktree last built on 2026-08-22.

Compaction cost grows faster than the file does across those two points, so a 21 GB file may still
cost minutes to compact. That is the reason for the size pre-check below rather than an argument
against compacting.

## Plan

1. **Compact at the last handle's release.** Issue `SHUTDOWN COMPACT` when the final
   `GraphitronModelStore` on a file lets it go, in place of the plain close. No schema or protocol
   change, and a consumer picks it up by taking the release.

   What represents ownership is the substance of this step rather than a caveat on it. H2 gives one
   process one database per file however many connections reach for it, which `close()` already
   records, so a `SHUTDOWN` from one holder closes the database under every other holder in the JVM.
   The holders are ordinary: `AbstractRewriteMojo.runGenerator` opens one
   `CapturePort.holding(ctx.storeDirectory())` per mojo execution, so a reactor opens the store once
   per module in one Maven JVM, and `SWEPT_HOMES`'s javadoc records two modules reaching `openAt`
   concurrently under `-T 1C`. `PersistentStoreTest`'s holder and writer pairs are the same shape at
   the test tier. A separate process is not the risk: `openAt` refuses a second process outright and
   falls back in well under a second.

   So the step adds a per-file open count as JVM-wide state beside `SWEPT_HOMES`, and compacts when
   it reaches zero. The case for it is correctness rather than arithmetic: one holder must not close
   the database under another. How often two holders meet depends on the build's shape rather than
   on its module count, since a plugin runs where it is configured. A consumer typically binds the
   goal in one module (opptak declares the plugin under `<pluginManagement>` and binds it only in
   `opptak-subgraph`), so its store is opened once per build. The shapes that do put several handles
   on one file are this repo's own reactor, a `graphitron:dev` session sharing a JVM with the LSP and
   MCP readers, and `PersistentStoreTest`'s holder and writer pairs.
2. **Fail fast on a store too large to service.** A cheap pre-check on file size sends a run straight
   to a fresh store rather than attempting a clear, or a compaction, that will not finish in a time a
   build may spend. Whether the per-source delete should be a partition drop rather than a row-by-row
   `DELETE` belongs here too.
3. **Bound the cache root in bytes, and reach the workspaces no build opens.** Two levels have to be
   kept apart here, because the tree already uses "home" for the lower one.
   `AbstractRewriteMojo.resolveStoreDirectory` returns
   `<userCacheRoot>/graphitron/model/<workspace-segment>`, and that per-workspace directory is the
   home an opener hands `StoreReaper.sweep`. The level that actually held the measured 7.4 GB is its
   parent, `<userCacheRoot>/graphitron/model`, the *cache root*: a directory no opener is ever given
   and that `graphitron-model` cannot see, since the only resolver that knows it lives in
   `graphitron-maven-plugin`. A byte budget over it therefore needs an owner that does not exist
   today, which is what makes this larger than the sweep it sits beside, and the step has to say
   which module gets it. It also has to say what the budget means when a consumer pins
   `-Dgraphitron.store.directory`, which `resolveStoreDirectory` takes verbatim: there is no sibling
   workspace set under a pinned home, so the budget either degrades to that one home or does not
   apply.
4. **Let two processes in one checkout both keep warm.** `graphitron:dev` and `quarkus:dev` (which
   runs `generate`) lose warm start to each other every round. The second process is already refused
   in under a second, so the question is whether the loser can keep a warm store of its own, not what
   the lock budget is. The budget still matters for the in-JVM case where a concurrent or leaked
   connection holds a table.
5. **Make the cost visible.** Log time spent in the store per run and the file's size, so a developer
   reading a slow build sees the store named rather than inferring it from a thread dump.

## What Ready covers

Ready authorises steps 1 and 5: compact at the last handle's release, and report what the store
cost. They are the smallest pair that changes a consumer's build, and step 5 is what makes step 1's
effect legible.

Steps 2 to 4 return through Spec once step 1 has shipped and been measured. They are an axis rather
than a proposal, and each carries a fork this item does not settle: the threshold that makes a store
too large to service (step 2), which module owns a cache-root budget (step 3), and whether a second
process can keep a warm store of its own (step 4). Compact-on-close also moves the ground under all
three by changing how large a store ever gets, so specifying them now would be specifying against
the wrong baseline.

## Verification

A growth curve across repeated real captures with compact-on-close enabled, measured on this repo,
which reproduces the mechanism without any consumer checkout. The store's size after each capture is
the measurement; a bounded curve is the pass.

Record the time each close spends compacting alongside the size, because the size alone prices the
wrong thing. Both figures in the table above are a *first* compaction of a long-accumulated store,
and the steady state is what the goal's "seconds rather than minutes" actually rests on. Measured on
the already-compacted 26.7 MB store, a further `SHUTDOWN COMPACT` still costs 849 ms to 1.5 s,
because it rewrites the live set whether or not there is garbage to reclaim. Once per build that is
affordable, but it is not free and it scales with the live set rather than with the garbage, so a
graph that grows pays more at every close. That is the number the goal's promise rests on, and
nothing measures it today.

## Considered and rejected

`RETENTION_TIME=0` on the store's JDBC URL, on the theory that a run shorter than H2's 45 s retention
window can never reclaim the chunks it writes. Twelve open, clear and recapture cycles against a
single-table file store are bounded with and without it: the baseline settles at 8.8 MB against
4.2 MB of live rows, and the flag only lowers the transient peak from 55 MB to 19 MB. A reopened
store restarts its retention clock, so the following run reclaims the previous run's chunks.

## Open questions

* Which property of a 151-table capture defeats the reclamation that a single-table control shows
  working. Compact-on-close makes this moot for the fix, but the answer decides whether compaction
  must run on every close forever or is covering for something addressable.
* What threshold makes a store "too large to service", and whether it is stated in bytes or as a
  multiple of what a cold capture of that graph produces.
* Whether a bounded reader's budget is the right guard for the editor surface at all, or whether the
  claim views want their own size guard. Compact-on-close should lift them back under budget, and if
  it does not, that is a separate item rather than more of this one.
* Whether the hard-killed-store item (R757), which also ends in "discard a file no run can warm
  from", shares the discard mechanism this item needs.

## Out of scope

The efficiency of the dev session's index and refresh loop, which is R916. That is a question about
how much the loop re-reads per round; this item bounds the cache the loop fills.

## Author response, round 1

Findings 1, 3, 4 and 5 are taken and the body is revised for each: step 1 now names a per-file open
count as what represents ownership and states the JVM-wide state it costs; step 3 separates the
per-workspace home from the cache root above it and asks which module owns a budget over the root;
a "What Ready covers" section says Ready authorises steps 1 and 5 and returns steps 2 to 4 through
Spec; and the read-path paragraph now names `intent_resolved_field_claim`, `intent_column_match_claim`
and the bounded reader that raises `57014`, and no longer offers that observation as evidence about
the clear.

Two qualifications, neither changing what was owed. Finding 5's lexical claim is too strong: the
claim views exist as `intent_resolved_field_claim` and `intent_column_match_claim`, so "intent
claims" was the live vocabulary rather than an invented term. The convention point stands and is
what the revision acts on, since prose should anchor on the identifier an implementer can grep.

Finding 2's withdrawal is right, and its arithmetic was wrong twice over. Re-measuring gives the
half the correction assumed rather than measured: a further compaction of the already-compacted
26.7 MB store still costs 849 ms to 1.5 s, because `SHUTDOWN COMPACT` rewrites the live set whether
or not there is garbage, so later closes are cheaper than the first but not cheap. The premise under
both the finding and its withdrawal is the weaker part: a plugin runs where it is configured, not
once per module, and the reporting consumer binds `graphitron-maven-plugin` in one module only, with
the root pom carrying it under `<pluginManagement>`. A twenty-module reactor does not open the store
twenty times, so no version of the multiplication describes a real build. The handle count stays in
the plan on finding 1's correctness grounds alone. The Verification note is taken.

## Workaround for consumers until it lands

Delete the cache (`~/Library/Caches/graphitron` on macOS, `$XDG_CACHE_HOME/graphitron` or `~/.cache/graphitron` on Linux, `%LOCALAPPDATA%\graphitron` on Windows);
the store rebuilds on the next run. Or point `-Dgraphitron.store.directory` at a path under the build
directory so `mvn clean` clears it. A checkout running `graphitron:dev` beside `quarkus:dev` can pass
`-Dgraphitron.dev.skipInitial=true` to the dev goal so only one of them captures at start.

## Reviewer findings

### Round 1 (2026-09-03, Spec -> Ready, reviewer session 01NawxZuKWXYC5ik4QRYSyRV)

Verdict: withhold. Three findings on gate two (does the plan extend a shape already in the tree),
one on what Ready would authorise, one on an anchor an implementer cannot re-find.

*What was checked and holds.* Every symbol the item names exists under the name it gives:
`StoreReaper.sweep`, `GraphitronModelStore.RETAINED_STAMPS` (three), `stampSegment()` (sixteen hex
digits of the DDL hash plus the generator version), `StoreRefresh.clear`'s per-table
`SOURCE_NAME IN (...)` deletes with `JVM_METHOD` among them, and `FILE_LOCK_MILLIS` at 60 000. The
sweep really is opener-driven and per-home (`GraphitronModelStore.sweepOnce` guarded by
`SWEPT_HOMES`), so a workspace nobody builds in is never revisited, exactly as the item says. The
consumer workaround's three cache paths match `AbstractRewriteMojo.userCacheRoot`,
`-Dgraphitron.store.directory` matches `resolveStoreDirectory`, and `-Dgraphitron.dev.skipInitial`
matches `DevMojo.skipInitial`. R757 and R916 exist and say what this item says they say. The goal
paragraph stands on its own and gate one passes on it. The diagnosis is the strongest thing here:
a store file that is dead space rather than facts, a retention shaped as a count over a problem
shaped as bytes, and a stamp rotation that multiplies it across every workspace at once. The
measurement discipline behind it (real store copies, the pinned H2, and a control that talks the
item out of `RETENTION_TIME=0`) is what a spec resting on a performance claim should look like. None
of that is in dispute below.

**Finding 1 (gate two). The ownership check step 1 rests on is the step's whole risk, and the party
the item names is not the one at risk.** The item guards `SHUTDOWN COMPACT` with "a check that the
closing run owns the store ... wrong in the middle of a held dev session". A dev session holds the
file in its own *process*, and `GraphitronModelStore.openAt` refuses a second process outright and
falls back in well under a second, so a dev session is never the handle a compacting close would
shut down. The party at risk is a second `GraphitronModelStore` on the same file *in the same JVM*,
which is the ordinary shape of a consumer build: `AbstractRewriteMojo.runGenerator` opens one
`CapturePort.holding(ctx.storeDirectory())` per mojo execution, so a reactor opens the store once
per module in the Maven JVM, and `SWEPT_HOMES`'s own javadoc records that two modules reach
`openAt` concurrently under `-T 1C`. `close()` already carries the comment that names this exactly:
issuing `SHUTDOWN` on a file-backed store "would close the database for every other handle in the
JVM, since H2 gives one process one database per file however many connections reach for it". The
plan needs to say what represents ownership, because that is what decides whether step 1 is really
"one call site, no schema or protocol change": nothing in the tree counts open handles per file
today, and the shape the tree suggests is a per-file open count living beside `SWEPT_HOMES`, which
is a new piece of JVM-wide state rather than an edit to one method. This is not hypothetical at the
test tier either: `PersistentStoreTest`'s `holder` / `writer` pairs open two stores on one directory
in one JVM, and compact-on-close without a handle count closes the database under them.

**Finding 2 (gate two). Compaction is priced once, and a close-triggered compaction is paid once per
module.** The measurements price one compaction at 829 ms for a 443 MB store and 4.1 s for an
864 MB one, and the plan reads as though a build pays that once. With one store open and closed per
mojo execution it is paid once per module, so a twenty-module consumer reactor pays the 829 ms
twenty times against a goal sentence that says the store must not be the reason a build waits. This
is the same ownership question from the other side, and the plan should answer both together: name
whether the compaction is per close or once per JVM at the last handle's release, and if the latter,
what triggers it.

*Reviewer correction, same round.* Withdrawn as a blocking finding; the arithmetic above is wrong
and nothing is owed on it. The 829 ms priced reclaiming 94% dead space from a 443 MB store, which is
the cost of clearing days of accumulation once, not a constant per close. Once the first close in a
reactor has compacted, every later one rewrites an already-compact store holding one module's churn,
so the sequence is one expensive compaction and then cheap ones rather than twenty of the first.
Finding 1 also mostly absorbs it: if ownership resolves as a per-file handle count compacting at the
last handle's release, a reactor compacts once per build by construction.

What survives is a note on Verification rather than on the plan, and the item is free to take it or
leave it. Both measurements price a *first* compaction of a long-accumulated store, and nothing
prices the steady state, which is the cost the goal's "seconds rather than minutes" rests on once
compact-on-close is in place. The growth curve records the store's size after each capture; recording
the time each close spends alongside it costs nothing and closes that gap.

**Finding 3 (gate two). Step 3's "cache home ... spanning its workspaces" names a level nothing in
the tree owns, and the word already means the level below it.** In the tree a *home* is
per-workspace: `resolveStoreDirectory` returns `<cache>/graphitron/model/<workspace-segment>`, its
javadoc is explicit that the value means "home", and that is the unit `StoreReaper.sweep` is handed
along with a live segment. A byte budget "spanning its workspaces" is therefore a budget on
`<cache>/graphitron/model/`, a directory no opener is ever given and which `graphitron-model` cannot
see at all, since the only resolver that knows it lives in `graphitron-maven-plugin`. Two things
follow that the step should settle. Name the root as its own concept rather than reusing "home",
or the implementer will read the step as a change to `StoreReaper.sweep`'s existing argument. And
say what the budget means when a consumer pins `-Dgraphitron.store.directory`, which
`resolveStoreDirectory` takes verbatim as "already scoped to whatever the consumer meant it to be
scoped to": there is no sibling-workspace set under a pinned home, so either the budget degrades to
that one home or the step does not apply. The same asymmetry is what makes "reach the workspaces no
build opens" a larger change than the sweep it sits next to: the current sweep is reachable only
because an opener hands it the one home it opened.

**Finding 4 (what Ready would authorise). The item pulls in two directions about its own scope.**
Step 1 says compact-on-close "ships first and alone"; step 3 says the cache bound "is not a
follow-up to the file-level fix". Both cannot be what Ready means. Two of the four open questions
are not detail but the central design fork of the step they belong to: what threshold makes a store
"too large to service" is step 2, and whether the read-path stall is bounded by the same guard is
what decides whether step 2 and step 3 are one answer or two. Step 1 and step 5 are Ready-shaped
once findings 1 and 2 land; steps 2 to 4 are an axis rather than a proposal. Either say in the body
that Ready covers step 1 and step 5 and that steps 2 to 4 return through Spec once step 1 has
shipped and been measured, or split them into their own items, or settle the two forks here. Which
of the three is the author's call; leaving it implicit is what I am withholding on.

**Finding 5 (anchoring). "The per-field query that hydrates intent claims" names nothing an
implementer can find.** `intent claim` appears nowhere in the tree; the live vocabulary is the
`intent_*` derived view family in `graphitron-model.sql`. The item's own convention (workflow.adoc,
Item file conventions) is that a code reference is anchored on a greppable identifier, and this one
carries weight: it is the sole evidence for "a fix aimed only at the clear leaves the read path
standing", which is the argument that steps 2 and 3 exist. Name the relation and the surface it was
observed on. The surface matters to the remedy: `57014` is `StoreReader.STATEMENT_CANCELLED`, which
is a bounded reader's `ReadBudget` expiring (`DevMojo`'s 3 s interactive, 30 s session, 60 s MCP),
while a `generate` run reads through `RunStore.handle()` over the store's own unbudgeted `dsl()` and
cannot raise it. If the observation is a dev-session or MCP reader hitting its budget rather than a
`generate` stalling, the read-path half of the item is a different problem from the clear, which
sharpens the third open question rather than answering it.
