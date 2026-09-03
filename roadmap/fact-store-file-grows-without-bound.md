---
id: R914
title: "The fact store cache grows without bound, and a large store stalls every build that opens it"
status: In Progress
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
which runs the goal on every start) pays seconds for the store rather than minutes, a store file
stays within a small multiple of the facts in it however many times it is recaptured, and the log
names the store when it is the cost. This is
[issue 544](https://github.com/sikt-no/graphitron/issues/544). Bounding what the cache costs a
machine *across* its workspaces is R918, which this fix takes most of the way on its own.

## What is wrong

**A store file is almost entirely dead space.** A 443 MB store from a real workspace holds 193,863
rows across 151 tables, a few tens of MB of live facts. The rest is chunks that MVStore has not
reclaimed. A file-backed store closes with a plain connection close, so H2 gets its default 200 ms of
compaction, which at these sizes reclaims nothing, and every clear-and-recapture cycle leaves more
behind. On the reporting consumer the file reached 21 GB after nine days.

**Nothing bounds the cache in bytes.** `StoreReaper.sweep` keeps `RETAINED_STAMPS` (three) stamped
directories per workspace and evicts the rest by recency, which caps a count and says nothing about
size; it also runs only on the home the current build opens, so a workspace nobody builds in is never
revisited. A machine carrying ten workspaces held 7.4 GB across 21 `store.mv.db` files. That axis is
R918's, and it is recorded here only because compacting each file is most of its answer: at the
reclamation rates below the same machine holds a few hundred MB.

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
cost minutes to compact. That is not an argument against compacting: a store this fix has been
compacting all along never reaches that size, and the one that already did is not reopened, for the
reason the next section gives. A guard for the file that grew before the fix is R917.

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
2. **Make the cost visible.** Log time spent in the store per run and the file's size, so a developer
   reading a slow build sees the store named rather than inferring it from a thread dump.

## Why this is the whole fix

The item ships two steps and nothing else. Three further asks were carried here while the mechanism
was being diagnosed (a size guard that fails fast on a store too large to service, a byte budget over
the cache root with a way to reclaim workspaces no build opens, and letting two processes in one
checkout both keep warm). None of them is needed to close the reported error, and each is filed as
its own Backlog item with the measurements that motivate it: R917, R918 and R919.

The reported error is a store that grew to 21 GB, a warm clear that took 2 min 18 s and hit the 60 s
lock budget, and a demotion that then paid another minute capturing cold. Compacting at the last
handle's release removes the growth that produces all three, and it also settles what happens to the
21 GB file already on that consumer's disk, which is the part a size guard would otherwise have to
answer. `generatorVersion()` reads the jar manifest's implementation version and `stampSegment()` is
the DDL hash plus that version, so a consumer picks this fix up only by taking a release, and taking
a release rotates the stamp. The run that carries the fix therefore opens a fresh empty directory
rather than the 21 GB one, captures cold once, and compacts on the way out. The old file is never
opened again, and `StoreReaper` evicts it once two further stamps have passed it in recency.

That leaves the three cut asks as what they are: a guard against a state this fix stops producing
(R917), a disk-footprint bound rather than a stall (R918, whose measured 7.4 GB was 21 uncompacted
files, so compaction alone takes the same machine to a few hundred MB), and a warm-start convenience
that was never part of the reported failure (R919). Each is worth doing on its own evidence. None is
worth holding this fix behind, and specifying any of them now would be specifying against a baseline
this fix moves.

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
* Whether compacting at every close leaves the editor's claim-view reads back under a bounded
  reader's budget. If it does not, that is R917's evidence rather than more of this item.

## Out of scope

The efficiency of the dev session's index and refresh loop, which is R916. That is a question about
how much the loop re-reads per round; this item bounds the cache the loop fills.

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

### Author response to round 1

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

### Round 2 (2026-09-03, Spec -> Ready, reviewer session 01NawxZuKWXYC5ik4QRYSyRV)

Verdict: sign off. All four findings taken, and both corrections to round 1 are accepted.

*Audited as a delta.* Step 1 now names the per-file open count as the substance of the step rather
than a caveat, says it is JVM-wide state beside `SWEPT_HOMES` rather than one call site, and rests
the case on correctness. That is implementable as described: `RunStore.Borrowed.close()` is a no-op
("the lender closes what the lender opened"), so a lent store does not decrement a count its lender
still holds. Step 3 separates the per-workspace home from the cache root above it, names
`<userCacheRoot>/graphitron/model` as the level that held the 7.4 GB, asks which module owns a
budget over a directory `graphitron-model` cannot see, and says what a pinned
`-Dgraphitron.store.directory` does to the question. "What Ready covers" settles what this sign-off
authorises. The read-path paragraph's new symbols all exist: `intent_resolved_field_claim` and
`intent_column_match_claim` are views in `graphitron-model.sql`, `java_class_declaration` is a table
there, and `CatalogFactCapture` carries the quoted sentence about the LSP source walker's cadence.
Attributing `57014` to a bounded reader rather than to `generate` is right, and demoting the
observation from evidence about the clear to a second surface the bloat degrades is the better
reading.

*Both corrections accepted.* The re-measurement is the more useful one: a further `SHUTDOWN COMPACT`
on the already-compacted 26.7 MB store still costing 849 ms to 1.5 s is the half my withdrawal
assumed rather than measured, and it makes the steady-state number worth carrying in Verification on
its own account. The premise correction is also right, and it undercuts the original finding more
cleanly than my withdrawal did: a plugin runs where it is configured, so a module count was never
the multiplier.

*Relocated, not rewritten.* The author's response moved from a section of its own into this section
beneath the round it answers, per the item-file convention that a returning reviewer should audit a
delta rather than cross-reference for it. The text is unchanged.

**One note for the implementer, not a condition of this sign-off.** The count is over
`GraphitronModelStore` handles, and a `StoreReader` minted by `reader(ReadBudget)` is a separate
connection that the count will not see. Today that is harmless: a plain close leaves the database up
for whatever connections remain, so closing a store under a live reader costs nothing on a
file-backed store, which is why `close()`'s javadoc calls the ordering the one that matters only for
the in-memory shape. Compacting at zero makes the same ordering fatal for a file-backed store too,
since the reader's database goes with the `SHUTDOWN`. `DevMojo` already tears down in the safe order
(`lspStore`, then `mcpStore`, then `sessionCapture`, then `sessionStore`), so nothing in the tree is
broken by this today; it is an invariant the change creates and that the javadoc on `reader` and
`close` should state once it exists.

### Round 3 (2026-09-03, Ready -> Spec, reviewer session 01NawxZuKWXYC5ik4QRYSyRV)

Not a finding. Recording a scope cut the user directed, and the sign-off it invalidates.

Steps 2 to 4 are struck from the plan and filed as R917 (a guard for a store too large to service),
R918 (a byte budget over the cache root, and reclaiming quiet workspaces) and R919 (two processes in
one checkout both keeping warm), each carrying the measurements that motivated it here. The claim the
cut rests on is that none of the three is needed to close the reported error, and the new "Why this
is the whole fix" section argues it: `generatorVersion()` reads the jar manifest's implementation
version and `stampSegment()` is the DDL hash plus that version, so a consumer picks this fix up only
by taking a release and taking a release rotates the stamp, which means the run carrying the fix
opens a fresh directory rather than the oversized one. That is what removes the need for a size guard
in this item rather than merely deferring it.

Round 2's sign-off does not carry to this body. It authorised steps 1 and 5, which is what remains,
so the cut narrows the item to what was approved rather than widening it; but this session made the
edits, and a reviewer session that lands substantive edits on a plan body cannot approve the result.
The new scope argument is also load-bearing prose nobody has reviewed. So the item is back at `Spec`
and the next `Spec -> Ready` needs a session that is neither the author nor this reviewer.

*User decision, same round.* Restored to `Ready` on the user's call. Round 2's sign-off stands: it
authorised steps 1 and 5, and after the cut the plan is steps 1 and 5 and nothing else, so the body
an implementer executes is exactly the body that was approved. The paragraph above overstated what
this session's edits cost. Deleting three steps and arguing why they are not needed changes what the
implementer does *not* build; it adds no design for a reviewer to check, and "Why this is the whole
fix" is a scope justification rather than a plan section. This is the existing sign-off surviving a
narrowing, not a new one, and this session is not claiming to have given it.
