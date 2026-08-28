---
id: R857
title: "A dev start evaluates the whole materialization register twice, the second pass producing identical rows"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: [capture-moves-below-the-generator, capture-without-the-materialization-refresh]
created: 2026-08-27
last-updated: 2026-08-27
---

# A dev start evaluates the whole materialization register twice, the second pass producing identical rows

`DevMojo.execute` runs the initial generator pass, whose capture already refills every registered
materialization for the graph it captured, and then calls `Materializations.refreshAll` on the
session store. `refreshAll` refills every registration for every graph the store holds,
unconditionally. On the ordinary case, one graph and an initial run that was not skipped, that is the
entire register evaluated a second time to produce the rows the first pass just wrote.

## Why nothing flags it

`refreshAll` is correct, and its javadoc says why it exists: it is "the entry point for a reader that
opens a store it did not capture into", correct whether or not a capture ever ran, and idempotent.
Every word of that is true. The redundancy is not a property of `refreshAll` but of the one caller
that reaches it immediately after a capture in the same JVM, where the precondition it is defensive
about cannot hold.

Idempotence is what hides it. The second pass is invisible in the output because it changes nothing,
and it is invisible in the log because the refresh emits nothing at all, which is a sibling item.

## What it costs

One full evaluation of the register, on the cadence of every `graphitron:dev` start. What that
evaluation costs is bounded from below rather than known, and the figure has to be quoted with the
fence its source puts on it. R856's price list prices positions 1 to 14 of a consumer schema's
refresh at 199 seconds, marks positions 15 and 16 unmeasured at that scale, notes that those
fourteen are exactly the registrations its populated store holds, and states outright that the total
was measured post-commit against a settled store and is to be read as a price list rather than as an
account of where an hour goes. So one pass over that schema costs at least those 199 seconds and
plausibly a good deal more, and nothing here invents a figure for the tail. The register has grown
since that measurement, to twenty registrations, and on a small schema the pass is small. The
error direction matters more than the number: the motivation only needs the pass to be expensive,
and 199 seconds is a floor. The cost scales with the store rather than with the
session: `refreshAll` loops graphs in its inner loop, so a store holding several graphs pays one
evaluation per graph per registration, where the capture paid one for the session's own graph. A
shared store holding several graphs is the ordinary state of a multi-module workspace, not an
exotic one.

There is a second-order effect worth stating because it bears on the fix. `refreshAll` calls
`analyse` inline and the capture path calls it after its transaction closes, so both passes also
re-gather statistics.

## The pass count is three, and only one of them is this item's

Worth stating so the fix is not credited with more than it does. A dev start captures twice, because
`DevMojo.execute` calls `runGeneratorPass` and then `buildOutputQuietly`, and every generator entry
point captures: `generateIncremental` and `buildOutput` both reach
`GraphQLRewriteGenerator.captureAndRead`, and each capture ends with `Materializations.refresh` for
its graph inside its own transaction. So a dev start evaluates the register twice before `refreshAll`
makes it three times, and `DevMojo.regenerate` pays the first two again on every schema save, which
is the cadence a developer actually feels.

The double capture is a separate defect with a separate fix (one pass producing both the emitted
tree and the catalog, or the catalog read off the pass that already ran), filed as R859. This item is
the third pass only: the one evaluation that no capture asked for.

## What changes for a consumer

A `graphitron:dev` start over a store that already holds the graphs it opens stops re-deriving the
materialization register at all. The language server and MCP ports bind one full register pass
sooner, plus one further pass per graph the store holds whose partitions no capture has disturbed.
Nothing about generated output changes.

What a reader may observe is worth stating exactly rather than sweepingly, because the first draft
of this item promised more than it could deliver and three review rounds said so. Within one JVM,
which is every cadence a build or a dev session produces on its own, no reader observes a stale row:
a partition is refilled unless something recorded that it was already filled from rows nothing has
touched since. Two cases fall outside that, both of them named and argued under "What the rule gives
up" below rather than left to be discovered: a target somebody emptied by hand, and two JVMs
capturing into one shared store at the same moment.

The same change makes `-Dgraphitron.dev.skipInitial` over a warm store genuinely cheap, which it is
not today: a start that captures nothing currently still pays the whole register.

## Where the knowledge belongs: in the store

The narrow fix is a condition at the call site: skip the refresh when a capture in this session has
already refreshed this graph. Reject that shape, for four reasons, and put the currency question in
the store instead.

First, the mojo cannot answer it. Capture demotes to a private in-memory store in two cases, an
unopenable cache (`FactCapture` logs `DEMOTED_TO_MEMORY`) and a graph name already recorded against
another base directory (`FactCapture.ownsGraph`), and neither is reported back through
`GraphQLRewriteGenerator` to the mojo. A caller-side condition would therefore skip a refresh the
session's capture never performed, unless a new return channel is threaded from capture up through
the generator to the mojo purely to carry it.

Second, it answers only for one graph. The store's other graphs are the larger half of the cost, and
the mojo knows nothing about them; a condition that skips its own graph and refreshes the rest is
`refreshAll` gaining an exclusion parameter, which is session knowledge pushed into the store's API
by another door.

Third, the knowledge outlives the process. A graph's partition is filled by whichever module's build
captured it, in another JVM, possibly days ago. The only place a fact about it can be recorded so
that this session can read it is the store, which is where every other fact about a capture already
lives.

Fourth, the shape. A `refreshAll` that is safe to call and ruinous to call twice invites this bug at
the next caller, and today's caller is its only production caller. The fix that removes the trap is
the one that makes the cheap answer the default answer.

This is the project's standing move rather than a new one: decide once and record the decision as a
fact, then let the reader ask instead of assume. Two relations about the materializer already work
that way, `meta_materialize` for the registrations and `meta_materialize_dependency` for the refresh
order derived from the stored view definitions. Both sit in `meta_`, because both are a function of
the DDL alone; what this item records is a function of what a run did, which is why it lands in
`store_` instead, and the Implementation section quotes the family comment that draws that line.

The cheapest alternative of all, deleting the call and trusting that every capture refreshed what it
wrote, is rejected on the same grounds and one more. A cold store, and a store holding a graph no
capture ever reached, genuinely need the pass, so the call cannot go; and with it gone the argument
that the targets are current would live only in prose, which is the state this item is a report of.

**What the rule gives up.** Three properties, and all three are traded knowingly. Today's
unconditional pass silently holds each of them, so each is a real loss rather than an oversight.

*A hand-damaged target stops being repaired by a restart.* Somebody who empties a target through the
store console the dev session exposes, or otherwise, no longer gets it back on the next start,
because the claim recorded for it still stands. That is the ordinary standing of a cache whose
contents were hand-damaged, and the remedy is the ordinary one: delete the store directory and let
the next build refill it.

*A re-walk that changed nothing still invalidates.* The rule below records that a partition was
filled and deletes that record when anything the partition reads is rewritten, without asking
whether the rewrite changed a row. So a sibling graph in a shared store refills after any capture
that re-walks a source it names, even where the rows came back byte-identical. This is the price of
recording a claim rather than a content stamp, and the store cannot currently be made to pay less:
`store_source.stamp` is NULL by design for exactly the two kinds that hold these rows, jOOQ schema
packages and directory roots, and `last_seen` moves on every run that merely names a source. It is
also strictly less work than today, which refills that partition unconditionally whether or not any
capture ran at all.

*Two JVMs capturing into one store at the same moment can leave a claim that should have been
deleted.* The reader-side pass refills a partition and records its claim in one small transaction,
but it does not serialize against a capture in another process. A foreign capture that deletes this
graph's claims before the pass inserts one, and commits its rewrite after the pass has read the
rows, leaves a claim recorded against rows that have since changed, and unlike every case above the
wrong answer then persists instead of being recomputed on the next start. It needs two JVMs sharing
one store simultaneously, with one capturing a source the other's graph names. Closing it wants
cross-process serialization the store does not offer, and the obvious instrument, locking the
graph's source rows for the pass, invites a deadlock against a capture that locks the same hundreds
of rows in its own order. If it is ever observed, the cheap detector is a watermark: read
`max(last_seen)` over the graph's sources before the pass and again before each claim, and withhold
the claim if it moved. That reuses the column dismissed above, correctly, because a currency key may
not err and a race detector may err toward doing more work. This item leaves it unbuilt rather than
building a mechanism against a failure nobody has seen, and states the window here so that the
promise in "What changes for a consumer" is one the rule actually keeps.

## What makes a partition current: a claim, and whoever falsifies it deletes it

This section replaces the rule the first three review rounds refused, and the reason it was refused
decides the shape of what replaces it. That rule recorded the graph's `last_captured` stamp at fill
time and refilled a partition when the stamp no longer matched. Currency, though, is a property of
the rows a partition reads, and not all of those rows are graph-keyed: `intent_spelled_table_live`
joins `store_graph_source` to `sql_table`, whose key is `(source_name, table_schema, table_name)`,
and `jvm_class` is `(source_name, class_name)`. `store_source`'s own comment says why no stamp of a
graph's can speak for them, that it "can say what a file hashed to, never which graph read it". A
capture of graph A re-walks a shared jOOQ package and rewrites those rows inside A's transaction,
while `FactCapture.writeGraph` moves `last_captured` for A alone, so B's stamp still equals the one
its fill row recorded against rows that no longer exist.

**So this draft stops comparing values.** A partition's currency is recorded as a claim, and every
writer that falsifies a claim deletes it in the same transaction that falsifies it. That inverts
where the argument has to hold, which is the whole gain. A stamp rule is sound only if the recorded
value covers everything the partition reads, which is what three rounds could not establish and what
round 3 established the store holds no material for. A claim is sound if every writer of what the
partition reads deletes it, which is a statement about writers, and there are two kinds of them.

**One relation, two columns.** `store_materialized_partition (source_view_name, graph_name)`: a row
claims that this graph's partition of this registration's target holds exactly the rows its source
view computes. No stamp column and no timestamp, deliberately, because a value nothing compares is a
value nothing keeps correct; the row's presence is the entire claim. The family placement changes
with it. The first draft put this relation in `meta_`, which that family's own comment forbids: "Not
store_, because these rows are a statement of what this file declares, never a record of what a run
read." A claim is precisely a record of what a run did, and `store_`'s comment opens with "Every run
of the generator leaves a record of itself here."

**Who writes a claim.** Only a refresh, and only for a partition it has just refilled.
`Materializations.refresh`, the capture-cadence entry point, stays unconditional and records; the
reader-side pass refills the pairs with no claim and records those. A writer never consults a claim:
a writer that has just rewritten a partition's inputs knows they changed, and only a reader has a
question.

**Who deletes one, and why that is only two writers.**

1. *The graph's own rows.* A capture rewrites its own graph's graph-keyed rows, so its
   `Materializations.refresh` deletes that graph's claims ahead of the pass and inserts one per
   registration it refills, inside the capture's transaction. The claim therefore becomes visible in
   the same commit as the rows it vouches for. `StoreRefresh`'s graph-scoped clear reaches the
   relation independently, by the `graph_name` column and the derivation whose stated purpose is
   that "a new graph-keyed relation is ownership-scoped by default", so the two agree rather than
   either relying on the other.

2. *Source-keyed rows, which are the half the stamp rule missed.* Every rewrite of a source's
   partition is preceded, in the same transaction, by one upsert of that source's `store_source`
   row. `ClasspathSources.upsert` is the single site for all three kinds: the classpath entries
   through `ClasspathSources.record`, the jOOQ schema package through `CatalogFactCapture`, and the
   schema files through `SdlFactCapture`. Its javadoc already states the invariant this leans on,
   that "this run is about to (re)write the source's partition", which is also why the stamp it
   writes there is null. So that upsert additionally deletes the claims of every graph that names
   the source, read off `store_graph_source`. A source whose partition survives unexamined is never
   upserted (`StoreRefresh.prepare` pre-claims its `store_source` row, so `record` returns early),
   and it correctly invalidates nothing.

**Why membership is the right reach and not an approximation of one.** The relation the invalidation
reads to find the affected graphs is the same relation the affected views read to scope themselves.
`store_graph`'s comment states that rule: any derivation joining an SDL fact to a catalog or
classpath fact "is underdetermined in a shared store until a membership relation says which sources
are the joining graph's; store_graph_source below is that relation", and "such a join scopes its
catalog side through it". A registered view that obeys that rule sees exactly the source-keyed rows
of the sources its graph names, which is exactly the set of graphs the upsert invalidates. Soundness
is therefore not a second rule to keep beside the first; it is the first rule read from the other
end. A view that disobeys it is already wrong, resolving a sibling module's tables into its own
answers, and the gate below is where that stops being prose.

**What the hooks cost.** One `DELETE` per upserted source, over a relation holding at most one row
per registration per graph, against a capture that already pays a full unconditional refresh of its
own graph. Nothing on the capture cadence gets measurably dearer, which matters because that cadence
is the one R856 is about.

Four properties are what make the rule sound rather than merely plausible.

**A missing claim means refill, and absence is always the safe direction.** Three ways a claim can be
absent, all of them ending in the conservative answer. A partition filled before a registration
existed lives in a different store file, the directory being stamped with the DDL hash and generator
version (`GraphitronModelStore`), so it is not merely unrecorded here but elsewhere. A graph minted
outside any capture, which `CompileFacts` and `OwnedGraphPartition` both do by inserting a
`store_graph` anchor row, has no claims and refills; round 1 flagged those two writers as a hole in
the old family roster with a benign outcome, and under a claim there is nothing there to get wrong.
And a scratch store with no `store_graph` row records nothing at all, the foreign key declining it,
so it refills exactly as today.

**Invalidation is per graph rather than per registration, and that is what keeps the dependency order
honest.** A source rewrite deletes all of a graph's claims instead of working out which registrations
read that source. Conservative in the cheap direction, over a relation of twenty rows per graph, and
it forecloses a failure the per-registration alternative would have had: a dependent still claiming
currency while the prerequisite its view reads is refilled underneath it.

**Partial progress is safe.** The reader-side pass holds no transaction over the whole pass, which it
cannot anyway because `analyse` commits, but each refill and its claim share one small transaction,
so a pass that dies leaves claims only for the partitions it finished and the next pass finishes the
rest. Ordering holds in the safe direction too: the pass refills prerequisites first, so a death
mid-pass leaves a prerequisite fresher than its dependent and never the reverse.

**Both refresh shapes survive unchanged.** A target with no `graph_name` in its shape has no
partition to claim and is refreshed whole and unconditionally, as today. All twenty production
targets are graph-keyed, so the register is covered; the whole-target arm has residents only in the
scratch fixtures of `MaterializationOrderTest`, which is what keeps those cases passing untouched.
Note what changed in this argument since the first draft: the keying of the *target* now decides only
the refresh shape, as it always did, and no longer stands in for an argument about what the target's
view reads. That substitution was the finding.

## The premise, and its enforcer

The premise is now a closure statement rather than a cadence one, which is the second half of what
the review rounds asked for. **Every base relation a registered source view reads is covered by one
of the two hooks.** A relation is covered when it is graph-keyed and rewritten only inside a capture
transaction of that graph, or source-keyed and rewritten only inside a transaction that upserts that
source's `store_source` row. A relation that is neither, or one written on a cadence no capture owns,
serves stale rows under this rule and does so silently.

The closure itself is cheap to compute and needs no new production API.
`ViewReferences.relationsReadBy` answers what one stored view definition reads, parsed out of the
definition rather than scanned for textually, and `MaterializeDependencies` already recurses over it
to reach the registrations a view depends on. This wants the same recursion stopped at base tables
instead, which is a handful of lines over that primitive in the test.

Three assertions over that closure, and the first is the instrument three rounds asked for: one that
fails on the class of registration that breaks the rule, rather than one that passes while the rule
is unsound.

1. **Shape.** Every base relation in the closure carries `graph_name` or `source_name`. Read off
   `INFORMATION_SCHEMA`, so it is derived rather than rostered, and it fails on a read of a relation
   neither hook can reach. That is not a hypothetical shape: the `java_` family is keyed on `file`
   and is deliberately not `store_source`-anchored, its charter saying so, and a registered view
   reading it would fail here. The assertion also catches the wholesale-cleared relations for free,
   since `StoreRefresh.wholesale` empties every base relation that is neither graph-keyed nor
   source-partitioned, so a registered view reading a relation that any run empties outright fails
   this assertion too. Nothing today detects that at all.

2. **Scoping.** A registered view whose closure contains a source-keyed relation also reads
   `store_graph_source`. Necessary and not sufficient, and stated as such: it asserts the presence
   of the membership relation, not the correctness of the join. The soundness argument above leans
   on that membership, so the gate says at least that much rather than nothing, and it makes the
   gate a second reader of a rule `store_graph`'s comment already states rather than a new rule of
   its own.

3. **Cadence.** The closure is disjoint from the families written off the capture cadence: `walk_`,
   `rejection_`, `lint_`, `build_warning_` and `javac_`, every one of them graph-keyed and therefore
   invisible to assertion 1, plus `java_`, which assertion 1 catches on shape as well. Their writers are the
   dev session's `CompileFacts`, `JavaSourceFacts`, `RejectionFacts` and `BuildWarningFacts`, on
   their own cadences, and `FactCapture.detect`, which writes the walk-side backing rows after the
   capture transaction has committed. A roster in the test with the writer named per prefix, which
   is the shape this gate already uses for its index exemptions.

Rounds 2 and 3 objected to a roster twice, on the ground that `sql_` and `jvm_` sit on it as
capture-written families while the rule was unsound over exactly those reads. That objection does
not carry against assertion 3, and the reason is not that the roster improved. A source-keyed read
is now *covered* rather than exempted: hook 2 invalidates it, so there is nothing for a gate to
catch there. What the roster is left holding is the narrow question a prefix genuinely answers, each
of those families having exactly one writer, and the question a prefix cannot answer has moved to
assertion 1, where a column answers it.

The gate is not debt this item introduces. A registration reading an off-cadence family is already
wrong on the build path, where nothing calls `refreshAll` at all and the target is therefore never
filled from the rows written after the transaction; the dev session's unconditional pass is the only
thing that would have hidden it, and it hides it on one goal out of several. So the premise is one
the register already depends on, stated and enforced here because this is where it becomes
load-bearing.

## Implementation

**`graphitron-model.sql`.** New table `store_materialized_partition (source_view_name, graph_name)`,
primary key on both columns, foreign keys to `meta_materialize (source_view_name)` and to
`store_graph (graph_name)`. Its comment states what a row claims, names the two writers that delete
one, and states in one sentence the premise above, since that is where a future registration's author
will meet it. Column comments per the schema's own convention; the `store_` prefix places it in the
family census with no exemption row needed, and the generated schema reference picks it up from the
comments. No secondary index: the relation holds one row per registration per graph, twenty times the
graphs in the store, so both reads over it are cheaper as a scan than as an index descent, and the
gate that demands an index or a stated reason applies to registered targets rather than to this.

The foreign key into `meta_` needs no `meta_family_bridge` row. That roster covers normalization
crossings and says so explicitly, that "a foreign key is already a declared, engine-checked join
path" and carries no rule anything could fork.

Adding a table changes the DDL hash, so the first build after this lands opens a new store directory
and captures cold once, and the directory it stopped using stays behind. That is the standing cost of
any DDL edit here rather than anything this item introduces, and it is R858's subject.

**`Materializations.refresh(DSLContext, String, RefreshProgress)`.** Unchanged in effect, plus the
claim: delete this graph's rows ahead of the pass, and insert one per registration whose partition it
filled. Both statements run on the caller's `DSLContext`, which is how the claim comes to be
published in the same commit as the rows it vouches for. A graph with no `store_graph` row records
nothing, the foreign key declining it, which is the scratch-store case and correctly leaves the
partition unclaimed.

**`Materializations.refreshAll(DSLContext, RefreshProgress)`.** Keeps its name and its
postcondition, every target current on return, and gains the claim check: read the graphs and the
claims once, refill the pairs with no claim in the existing order (registrations outer, graphs
inner, so the dependency order is untouched), insert each claim in the same small transaction as the
refill it vouches for, and call `analyse` only if something was filled. Returns the number of
partitions refilled, which is the test observable, on the precedent `analyse` set by returning a
count rather than logging. Its javadoc states the premise it now rests on and the concurrency window
named under "What the rule gives up".

**`Materializations.invalidate(DSLContext, String sourceName)`,** new and public: deletes the claims
of every graph naming that source, one statement over `store_graph_source`. It lives here rather than
in the capture package because the relation is this class's, and it is plain-name jOOQ like the rest
of this class, for the reason stated there: this module's hand-written half does not reference its own
generated half.

**`ClasspathSources.upsert`.** One added call to that method. The javadoc sentence that already says
"this run is about to (re)write the source's partition" gains the consequence, so the site states why
it is the invalidation's home rather than leaving that argument only in this item.

**`RefreshProgress`.** One new sealed arm, `Event.RegistrationSkipped(registration, position, total,
graph)`, emitted where the reader-side pass declines to refill, and two counts on
`Event.PassFinished` so the pass-boundary tier says what the pass decided rather than falling silent.
This is not decoration. R855 exists because an anonymous pass is unreadable, and a pass that skips
everything and reports nothing would reintroduce exactly that by a new route: a person watching a
warm dev start would see the same two lines as a person watching a stuck one. The sealed interface
names every switch site when the arm is added, which is its stated purpose, and the skip arm keeps
the name-before-statement property trivially, there being no statement.

**`DevMojo.execute`.** No code change. The comment at the call site says the refresh is there because
a warm store whose capture was skipped would otherwise serve stale rows; that stays true and becomes
precise, so it gains a sentence saying the currency question is now answered in the store and what
the call costs on the ordinary path.

**No production change for the gate.** The reach it needs is `ViewReferences.relationsReadBy` closed
over the views it returns, plus two `INFORMATION_SCHEMA` reads, all computed in the test. Worth
stating because the first draft of this spec proposed exposing a base-relation reach from
`MaterializeDependencies`, and the public primitive that landed with the re-evaluation metric makes
that unnecessary.

**`SeededStore.derive`.** Clears `store_materialized_partition` before refreshing. The fixture seeds
rows directly, without a capture and without upserting a source, so it is precisely the writer the
premise excludes; clearing the claims is that fixture stating its own irregularity in one line, and
it keeps the production surface at one entry point rather than adding an unconditional variant for
tests to reach for. Its javadoc says so, next to the sentence that already explains why the helper
refreshes unconditionally.

## Tests

- **`MaterializationOrderTest`**, or a sibling class if that one's fixtures stay graph-free: a
  graph-keyed scratch registration, refreshed at capture cadence, after which `refreshAll` refills
  nothing and returns zero. Then the same store after `Materializations.invalidate` for a source the
  graph names, where it refills.
- **Two graphs, one claimed and one not**: `refreshAll` refills only the unclaimed graph's
  partition. Asserted on rows and not only on the count, by planting a row in the claimed partition
  that the source view does not produce and showing it survives while the other partition fills.
- **The sibling-invalidation case, which is the finding's own scenario**, in the capture tier: two
  graphs in one store naming a shared source, both captured, then graph A captured again. The pass
  refills B's partitions and none of A's, A's claims having been rewritten by its own refresh and B's
  deleted by A's upsert of the shared source. **This is the case the rejected design fails**, so it
  is the test that pins the difference rather than the design's own restatement, and it is worth
  writing as the finding writes it: the shared jOOQ package of two modules in one workspace store.
- **The end-to-end claim, in the capture tier over `CapturedStore`**: capture a fixture schema into a
  real store, then `Materializations.refreshAll` refills nothing and returns zero. This is the item's
  goal in one assertion, and the one that fails if a future registration breaks the premise in a way
  the gate below does not catch. Beside it, the two-graph shape `WarmStartRefreshTest` already
  captures for its sibling-partition cases: capture both graphs, then assert that the pass refills
  exactly the pairs whose claims the second capture deleted, with the expectation derived from
  `store_graph_source` in the test rather than hardcoded. Deriving it is the point: whether that
  fixture's two graphs share a source decides the answer, and a test that hardcoded "nothing refills"
  would either be asserting the fixture's source layout by accident or be wrong.
- **The premise gate, in `MaterializeRegistryGateTest`**: the three assertions above over the closure
  of the registered source views' reads, computed with `ViewReferences.relationsReadBy`. Shape and
  scoping are derived; the off-cadence prefixes are a roster in the test with the writer named per
  prefix, which is the shape that gate already uses for its index exemptions. Lifting the cadence
  into a `meta_family` column is a bigger question and is out of scope here.
- **`MaterializationProgressTest`**: the new skip arm and the widened pass-finished event, on the
  same terms the existing cases hold for the two registration events. A reader-side pass that skips
  everything emits one skip per pair and a pass-boundary line saying so, which is the assertion that
  fails if a later change makes a warm start silent again.
- `MaterializationOrderTest`'s existing `refreshAll` case and the seeded-store fixture's callers are
  the regression surface for the two arms deliberately left unconditional; they pass unchanged, which
  is the point, so no new case is owed there beyond the graph-keyed ones above.

## Building on R855, which has landed

The first draft asked for R855 to land first. It has: R855 is `Done`, its item file gone with the
state and its account in `roadmap/changelog.md`, and its shape is in the tree, so `refresh` and
`refreshAll` already take a `RefreshProgress`, `RefreshProgress.lines` renders the two tiers, and
both `DevMojo` and `FactCapture` already pass one. The sequencing preference is
therefore settled by the tree rather than argued here, and the Implementation section above names the
observer-carrying signatures because those are the ones that exist.

That also closes a question the first draft left open. A pass that skips every partition and says
nothing is the anonymity R855 exists to remove, arriving by a new route, so a skipped partition is an
observation the observer reports rather than an absence. The first draft left the vocabulary for it to
R855; R855 has decided the vocabulary, so this item fits into it: one sealed arm at the
per-registration tier and two counts at the pass boundary, per the Implementation section.

## Out of scope

- **The capture-cadence cost R856 is about.** This item removes an evaluation nobody asked for; it
  does nothing about the one the capture itself performs, which is where that hour goes.
- **The double capture per pass**, filed as R859 and described above.
- **Whether the register needs to be this large**, which is R848 and upstream of how many times it is
  evaluated.
- **A cadence column on `meta_family`.** The premise gate states the cadence in the test rather than
  in the store. Making it a relational fact is defensible and is a change to the family roster's
  charter, so it wants its own item if the gate's roster ever grows a second reader.
- **Anything about eviction of stamped store directories**, which is R858.
- **Content stamps for the two source kinds that carry none.** Round 3 named this as one of the three
  answers available: a stamp covering the source-keyed partitions, so a re-walk that changed nothing
  would leave a claim standing. It is a capture-path and DDL change with a measurement of its own,
  since hashing a jOOQ package or a directory root is the cost it exists to avoid, and the rule here
  does not need it: the conservative answer already does strictly less work than today. If the
  re-walk case above is ever measured and found to matter, that is the item to file.
- **The cross-process window** under "What the rule gives up". Named, argued, and left open, with the
  detector that would close it described rather than built.

## Related

The sibling logging item R855 would have made this visible without reading the source, which is how
both sessions that found it found it instead; it has now landed, and the section above says what this
item builds on and what it owes it.

`depends-on` names R864 and R865, and the reason is a correctness interaction rather than a merge
conflict. R855's shape is in the tree and nothing is owed there any more; what is owed is to the two
items that change who decides the refresh cadence.

**This item and R864 agree, which is why the sequencing is worth getting right.** The rule below
already draws the line R864 states as an API constraint: a writer never consults a claim, because a
writer that has just rewritten a partition's inputs knows they changed, and only a reader has a
question. R864 says the same thing from the other end, that a consumer needing current targets asks
and a consumer that does not pays nothing, and makes the cadence belong to whoever opened the store
rather than to capture. Two statements of one rule, so the risk between them is not disagreement.

**The risk is R865, and it is specific.** That item makes `Materializations.refresh` declinable, so
a capture can commit having refilled nothing. This item's rule says the capture-cadence entry point
stays unconditional and records a claim per partition it refills. Those two sentences are compatible
only if a capture that declined the refresh records no claims: otherwise it commits rows claiming
partitions it never refilled, the reader-side pass believes them, and the dev session serves stale
targets with a claim vouching for them. That is the one failure this item's whole design exists to
prevent, arriving through a door that did not exist when the rule was written.

**The direction is this way round because the reverse is already safe.** A capture that recorded no
claims leaves partitions with no claim, which the reader-side pass refills; that is the cold-store
case the rule handles by construction. So R865 landing first costs this item nothing. This item
landing first leaves a rule stating that refresh records unconditionally, for R865's implementer to
find and reconcile without the context that produced it.

R864 adds a second, weaker reason: it moves capture and every caller of `Materializations` across a
module boundary, and this item adds a relation and two writers in exactly that area. Writing them
against the final module shape costs nothing; writing them against today's costs a migration.

R848 asks whether the register needs to be this large at all, and R859 is the double capture this
item's fix leaves in place. Neither is a dependency.

## Reviewer findings

### Round 1: Spec -> Ready, revisions requested

The report half is sound and the goal reads clearly: a warm dev start stops re-deriving a register it
already holds, and the language server binds a full pass sooner. The pass-count arithmetic, the twenty
registrations, and the graph-keying of all twenty targets check out against the tree, as does every
symbol and test class the spec names. One finding blocks.

**Blocking, question 2: `store_graph.last_captured` does not stamp every change a registered source
view reads, so the equality rule skips partitions that are genuinely stale.** Not every relation a
registered view reads is graph-keyed. `intent_spelled_table_live`, a registered view, joins
`store_graph_source` to `sql_table`, and `sql_table` is keyed on `source_name` with no graph column at
all; `jvm_class` and its family are the same shape. Registered views read the `sql_` family in eighty
odd places and the `jvm_` family in two dozen, always scoped to a graph through `store_graph_source`
rather than by a graph column of their own.

Those source-keyed rows are shared between the graphs that name the source, and `StoreRefresh` says so
outright: a run rewrites the stale partitions of the sources in *its own* input set, and a directory
root is never stamped, so it is re-walked on every run. `FactCapture.writeGraph` stamps only
`graph.name()`. So a capture of graph A can rewrite rows that graph B's registered partitions derive
from, inside A's transaction, leaving B's `last_captured` untouched. B's fill row still equals B's
stamp, the reader-side pass skips B, and B's targets serve rows derived from the previous walk of a
source that has since changed. That is a shared jOOQ package or a sibling module's `target/classes`
under two graphs in one store, which is the same multi-module workspace the cost argument in "What it
costs" leans on, and it lands hardest on the case "What changes for a consumer" advertises as the new
win: under `-Dgraphitron.dev.skipInitial` no capture stamps B at all, so nothing else repairs it. It
also falsifies that section's claim that no reader may observe a stale row.

The premise gate as specified does not catch this, because the gate reasons from family prefixes and
cadence is a property of the writer rather than of the prefix. `sql_` and `jvm_` are on the roster of
capture-written families, so the disjointness assertion passes while the rule is unsound. The same
prefix-versus-writer gap shows up a second time in the roster: `store_` is listed as written by
capture, and `CompileFacts` and `OwnedGraphPartition` both insert a `store_graph` anchor row outside
any capture transaction. That second one happens to be harmless, since both are insert-if-absent and
never rewrite an existing stamp and a minted graph has no fill row, but it is the same reasoning error
with a benign outcome, and it is worth stating because the gate is the only thing the spec offers
against this class of defect.

What would satisfy the finding is a currency key that covers what a partition actually reads, not a
scope reduction. The material is in the tree: `store_source` already carries `stamp` and `last_seen`
per source, and `store_graph_source` already names which sources a graph reads, so "current" can be
stated over the graph's stamp together with the stamps of the sources it names. Deciding that shape
is the author's, as is whether the premise gate then becomes a statement about which relations a
registered view may read rather than about family prefixes. Accepting the staleness instead is also a
defensible answer if it is argued and stated in the "What the rule gives up" paragraph on the same
terms as the hand-emptied-target trade, but it cannot stay implicit while the spec promises no reader
observes a stale row.

> *Author response, revision 1.* Accepted, and the rule is replaced rather than patched. The finding
> is right on both halves: the equality rule skips genuinely stale partitions, and the family-prefix
> gate cannot see it. What replaces it is none of the three answers the three rounds enumerated,
> because all three keep the stamp comparison and hunt for a value wide enough to compare. "What
> makes a partition current" now records a *claim* rather than a value, and every writer that
> falsifies a claim deletes it in the transaction that falsifies it. That moves the burden of proof
> from "the recorded stamp covers everything the partition reads", which round 3 established the
> store holds no material for, to "every writer of what the partition reads deletes the claim", where
> there are two kinds of writer and the second is one method, `ClasspathSources.upsert`, whose own
> javadoc already states the invariant it needs. This finding's material is used, just not as a
> stamp: `store_graph_source` carries the invalidation's reach, and it is the same relation those
> views already scope themselves through, so soundness is that rule read from the other end rather
> than a second rule to maintain. Consequently: the currency argument now runs over what a partition
> reads and never over the target's keying; the gate gains a catalog-derived shape assertion that
> fails on this class of read; "What changes for a consumer" stops promising what the rule does not
> deliver, with two residual cases argued under "What the rule gives up" beside the hand-emptied
> target. The relation also moved out of `meta_` into `store_`, on that family's own stated
> discriminator, being a record of what a run did rather than of what the DDL declares. The second
> half of this finding, `store_graph` anchor rows minted by `CompileFacts` and `OwnedGraphPartition`,
> is now covered by construction and said so: such a graph has no claims, and absence means refill.

**Non-blocking, precision on the cost figure.** "One pass over sixteen registrations is about 200
seconds" attributes to sixteen registrations a total R856 measures over fourteen. R856's table prices
positions 1 to 14 at 199 seconds and marks positions 15 and 16 unmeasured, notes that those fourteen
are exactly the registrations its populated store holds, and fences the figure explicitly: measured
post-commit against a settled store, to be read as a price list rather than as a statement about where
an hour goes, with no figure to be invented for the tail. The error is conservative, since the true
per-pass cost on that schema is at least 199 seconds and plausibly much more, so nothing in the
motivation weakens. Left to the author rather than corrected here because restating it accurately is
more than swapping a numeral.

> *Author response, revision 1.* Fixed in "What it costs", and restated rather than renumbered. The
> paragraph now says what R856 measured (positions 1 to 14 at 199 seconds, the tail marked unmeasured
> at that scale, the total fenced as a price list read post-commit against a settled store), that
> those fourteen are exactly the registrations its populated store held, and that one pass therefore
> costs at least that and plausibly a good deal more. No figure is invented for the tail, and the
> sentence naming the error direction is there so a later reader does not "correct" the floor back
> into a point estimate.

Verdict: stays in Spec.

### Round 2: Spec -> Ready, revisions requested

The plan body is unchanged since round 1: the most recent commit on this file is round 1 itself, so its
blocking finding is still open. Question 1 passes on its own terms. Without reading the phase list, what
a consumer gets is this: a `graphitron:dev` start over a store that already holds the graphs it opens
stops re-deriving the materialization register, so the language server and MCP ports bind a full
register pass sooner, and `-Dgraphitron.dev.skipInitial` over a warm store becomes genuinely cheap
rather than nominally so. That reads clearly and the outcome is reachable. Question 2 still fails on the
currency rule. This round re-verified the finding from the source rather than inheriting it, and what
that turned up narrows the space of fixes enough to be worth stating.

**Blocking, question 2: the soundness argument is made over the target's keying, but currency is a
property of what the source view reads.** The section "The rule covers graph-keyed targets only" tests
for `graph_name` in the *target's* shape. All twenty targets carry it (checked, every target table of
every row in `meta_materialize` has a `graph_name` column), so the whole-target arm has no production
resident and the spec reads that as complete coverage. It is not, because a graph-keyed target can be
computed from relations that carry no graph at all. `intent_spelled_table_live` joins
`store_graph_source` to `sql_table`, whose key is `(source_name, table_schema, table_name)`;
`jvm_class` is `(source_name, class_name)`. Both are anchored to `store_source`, which its own comment
calls store-global rather than graph-keyed: "it can say what a file hashed to, never which graph read
it." `store_graph.last_captured` cannot speak for either, so the equality rule can hold while the rows
underneath a partition have changed.

The trigger is ordinary rather than contrived, and every step of it is in the tree.
`GraphitronModelStore` describes the file-backed store as "a per-user cache directory shared by one
workspace's modules", and a reactor build's modules share it in the Maven JVM, so two graphs naming one
generated jOOQ package is the normal multi-module layout. `CatalogFactCapture` deletes that package's
`sql_table` partition and re-walks it on every capture of any graph that names it, and
`FactCapture.writeGraph` stamps `graph.name()` and nothing else. So: the database schema changes, jOOQ
regenerates, module A rebuilds and captures, module B is up to date and is never re-captured. A's
transaction rewrote the very `sql_table` rows B's `intent_spelled_table` partition was resolved
against, B's `last_captured` never moved, B's fill row still matches it, the reader-side pass skips B,
and the dev session answers from resolutions against a catalog that no longer exists. Today's
unconditional pass is the only thing that repairs that, and under `skipInitial` nothing else does. It is
a real property being traded away, and "What changes for a consumer" currently promises the opposite:
that no reader may observe a stale row.

**What round 1's suggested material does not cover.** Round 1 pointed at `store_source.stamp` together
with `store_graph_source`. The membership half is right, the stamp half does not carry:
`store_source.stamp` is NULL by design for exactly the two source kinds that hold these rows.
`ClasspathSources.upsert` writes it explicitly NULL for the `JOOQ_SCHEMA` kind, and `commitStamps` sets
it only where the entry hashes, which a directory root does not (`SourceStamp.ofFile` returns null when
the path will not open as a stream), just as the column's comment states as intent. Only `JAR` sources
carry a stamp. So a currency key that reads source stamps proves nothing about the shared jOOQ package
or a sibling module's `target/classes`, which is the whole of the case, and `last_seen` is worse than
useless here: it moves on every run that names the source, so an equality over it marks every sibling
graph permanently stale and returns the win to zero in precisely the multi-module store that "What it
costs" leans on.

**A cheaper shape that does close it, offered rather than chosen.** `store_graph_source` alone answers
a narrower question: whether any other graph in the store names any source this graph names. Where none
does, only this graph's own captures rewrite its source-keyed rows, and each of those moves its
`last_captured` in the same transaction, so the equality rule is sound exactly as the spec writes it.
That keeps the single-graph win and the `skipInitial` win intact and falls back to an unconditional
refresh for a graph with a shared source. Whether that, or a content stamp on the catalog partition so
an unchanged re-walk leaves the stamp still, or an argued acceptance of the staleness, is the answer is
the author's call.

What a revision needs, whichever way that goes: "What makes a target current" states currency over what
a partition reads rather than over the target's keying; "What changes for a consumer" stops promising
that no reader may observe a stale row unless the rule earns it, or states the trade in "What the rule
gives up" on the same terms as the hand-emptied target; and the premise gate becomes an instrument that
could fail on this class of registration. The family-prefix roster cannot, and this is the second round
saying so: `sql_` and `jvm_` are capture-written families, so the disjointness assertion passes while
the rule is unsound. The gate is the spec's only defence against a future registration breaking the
rule, so it has to be able to see the failure it is guarding.

> *Author response, revision 1.* Accepted; answered by the same replacement, filed in full under
> round 1. Three points this round contributed specifically. Its refutation of round 1's suggested
> material is what ruled out the stamp family altogether, `store_source.stamp` being NULL by design
> for the two kinds involved and `last_seen` moving on every run that merely names a source; both
> facts are now stated in the item, the first under "What the rule gives up" as the reason the
> conservative answer cannot be sharpened today, and the second as the reason a `last_seen`
> comparison is unfit as a currency key while being exactly fit as a race detector, which is the one
> place the item still offers it. The `store_graph_source` disjointness shape offered here is not the
> answer taken, and the reason is that it forfeits the multi-module win the cost section leans on:
> the claim design falls back for the affected graph rather than for every graph that shares a
> source, and it falls back only after a capture actually rewrote something. And the three revision
> requirements are each met: currency is stated over what a partition reads, "What changes for a
> consumer" is bounded to the single-JVM cadences with the residuals argued beside the hand-emptied
> target, and the gate's first assertion is catalog-derived and can fail on this class of read.

**Non-blocking, still open from round 1.** The 200-second figure and its attribution to sixteen
registrations. R856 prices positions 1 to 14 at 199 seconds, marks 15 and 16 unmeasured, and fences the
total explicitly. Left to the author again rather than corrected here, for round 1's reason.

> *Author response, revision 1.* Fixed; see the response under round 1's copy of this finding.

**Verified clean this round,** so a revision need not re-argue any of it: `Materializations.refresh`,
`refreshAll` and `analyse` with `DevMojo` as `refreshAll`'s only production caller;
`ViewReferences.relationsReadBy` as a public primitive with `MaterializeDependencies` recursing over
it; `FactCapture.writeGraph`, `ownsGraph`, `DEMOTED_TO_MEMORY` and `detect`; `StoreRefresh`'s
owned-graph and owned-sources contract; `store_graph.last_captured`, `store_source`,
`store_graph_source`, `meta_materialize` with twenty rows, and `meta_materialize_dependency`;
`GraphitronModelStore`; `MaterializationOrderTest`, `MaterializeRegistryGateTest`,
`WarmStartRefreshTest`, `CapturedStore` and `SeededStore.derive`; and R848, R855, R856, R858 and R859
with the statuses and shapes the spec attributes to them, R855 at priority 1.

Verdict: stays in Spec.

### Round 3: Spec -> Ready, revisions requested

The plan body is unchanged since round 1, so both prior rounds' blocking finding is still open on the
same words. This round re-derived it from the source rather than reading the rounds above, and reached
the same place, so the short version is that nothing here is new and the finding is not going to go
away by being reviewed again. What is new is a boundary on the search for a fix, below.

Question 1 passes. Stated without the phase list: today a `graphitron:dev` start pays a full derivation
of the materialization register that nobody asked for, on top of the ones its own captures already
paid, and after this lands a start over a store that already holds its graphs pays none of it, so the
language server and MCP ports bind sooner and `-Dgraphitron.dev.skipInitial` over a warm store becomes
cheap in fact and not only in name. That is a clear consumer-visible outcome and it is reachable here.

**Blocking, question 2: unchanged.** `intent_spelled_table_live` joins `store_graph_source` to
`sql_table`, `CatalogFactCapture.clearSchemaSources` deletes the whole `sql_table` partition of a
source and re-walks it on any capture naming that source, `FactCapture.writeGraph` moves
`last_captured` for `graph.name()` alone, and `StoreRefresh`'s own contract says a run owns its graph
and its crawled sources and that a directory root is never stamped. So a graph whose partition was
resolved against catalog rows another graph's capture has since replaced still matches its own stamp,
and the reader-side equality skips it. The spec's soundness argument, "The rule covers graph-keyed
targets only", tests the target's key and therefore cannot see this, and "What changes for a consumer"
still promises that no reader may observe a stale row.

**New this round: the store holds no other material for a content-keyed answer, so the option set is
closed.** Round 2 established that `store_source.stamp` is NULL for `JOOQ_SCHEMA` and for directory
roots and that `last_seen` moves every run. Reading the rest of the store's currency vocabulary rather
than only that column: `store_source` has exactly four columns, `source_name`, `source_kind`, `stamp`
and `last_seen`, and the only other stamping relation in the schema is `store_stamp`, whose singleton
row carries the DDL hash and generator version and is the identity of the store file itself, not of
anything inside it. There is no third relation to reach for. So a revision has three answers available
and no fourth: record membership overlap and fall back to unconditional where a source is shared
(round 2's `store_graph_source` disjointness shape); introduce a new stamp that covers the source-keyed
partitions, which is a DDL and capture-path change the spec would have to own; or accept the staleness
and argue it in "What the rule gives up" beside the hand-emptied-target trade, with "What changes for a
consumer" amended to match. Choosing among those is the author's call, and continuing to look for an
existing column that closes it is not going to pay.

> *Author response, revision 1.* This is the round that unlocked the revision, and it did it by
> closing the option set. Having no third stamping relation to reach for is what made clear that the
> question itself was wrong: the design was asking which recorded value proves a partition current,
> when the store's own idiom is to record the decision and let a writer retract it. So the answer is a
> fourth one, outside the three listed here because it is not a stamp at all, and the two the round
> ruled out stay ruled out for the reasons given. On the round's own three: membership overlap is
> used, as the invalidation's reach rather than as a fallback trigger; a new stamp is out of scope
> with a reason, filed under "Out of scope" as the item to open if the identical-re-walk case is ever
> measured; and staleness is accepted only where it is argued, in the two named residuals rather than
> across the rule.

The premise gate still needs to become an instrument that could fail on this class of registration.
`sql_` and `jvm_` are capture-written families, so the family-prefix disjointness assertion passes
while the rule is unsound, and the gate is the spec's only stated defence against a future
registration breaking it.

**Non-blocking, still open.** The 200-second figure attributed to sixteen registrations. Third round of
saying so; still not corrected here, because restating it accurately is a change to the motivation's
prose rather than a numeral swap.

> *Author response, revision 1.* Fixed, as the prose change it needed rather than a numeral swap; see
> the response under round 1's copy of this finding.

**Verified independently this round**, so a revision still need not re-argue any of it: the twenty
`meta_materialize` registrations; `DevMojo.execute` as `refreshAll`'s only production caller, with
`SeededStore` and `MaterializationOrderTest` the only other namings anywhere; `Materializations.refresh` / `refreshAll`
/ `analyse`, the `graphKeyed` arm the spec's whole-target paragraph describes, and `analyse`'s
count-returning precedent including the `MaterializeRegistryGateTest` assertion the spec cites for it;
`ViewReferences.relationsReadBy`, `MaterializeDependencies`, `FactCapture.ownsGraph`,
`DEMOTED_TO_MEMORY`, `GraphQLRewriteGenerator.captureAndRead`, `generateIncremental`, `buildOutput`,
`runGeneratorPass`, `buildOutputQuietly`, `GraphitronModelStore`, `CompileFacts`, `JavaSourceFacts`,
`RejectionFacts`, `BuildWarningFacts`, `CapturedStore`, `SeededStore.derive`, `WarmStartRefreshTest`
and `MaterializeRegistryGateTest`; and `meta_materialize_fill` absent from the schema, as a new
relation should be.

Verdict: stays in Spec.

### Round 4: Spec -> Ready, revisions requested

Revision 1 answers rounds 1 to 3. The stamp comparison is gone, and the claim rule it is replaced with
is the better shape: it moves the burden of proof from "the recorded value covers everything the
partition reads", which the store holds no material for, onto "every writer of what the partition
reads deletes the claim", which is a statement about a small, enumerable set of writers. That
inversion is right, and the two writers it names check out. `ClasspathSources.upsert` really is the
single site: three callers in main sources, exactly the three kinds the item lists, `record` for
classpath entries, `CatalogFactCapture.clearSchemaSources` for the jOOQ package and
`SdlFactCapture` for schema files, and in `clearSchemaSources` the upsert sits in the same loop body
as the per-source `sql_` deletes. `FactCapture.capture` wraps the whole capture in one
`dsl.transaction`, so "in the same transaction that falsifies it" holds. `StoreRefresh.prepare`
pre-claims a surviving source's `store_source` row, so `record` returns early and an unexamined
partition correctly invalidates nothing.

Question 1 passes. Stated without the phase list: today a `graphitron:dev` start pays a full
derivation of the materialization register that no capture asked for, on top of the two its own
captures already paid, and after this lands a start over a store that already holds its graphs pays
none of it, so the language server and MCP ports bind a full pass sooner and
`-Dgraphitron.dev.skipInitial` over a warm store becomes cheap in fact rather than in name.

Question 2 fails, on the premise and its enforcer rather than on the claim rule.

**Blocking, question 2: four registered source views read two relations that every warm capture
empties for every source, and neither hook reaches them.** The premise says a relation is covered
when it is "source-keyed and rewritten only inside a transaction that upserts that source's
`store_source` row". `sql_node_metadata` and `sql_node_key_column` are source-keyed and are not
only rewritten that way. `StoreRefresh.PARTITIONED` does not list them. It lists ten `sql_`
relations, the nine `jvm_` ones and the four `java_` ones, and these two are absent, as are
`sql_routine` and `sql_routine_parameter`. A relation absent from that set, carrying no
`graph_name`, falls through every exclusion in `StoreRefresh.wholesale` and is emptied by
`clear`'s wholesale arm with a predicate-free `deleteFrom(table).execute()`, on every warm capture
of any graph. `CatalogFactCapture` then re-inserts rows for the sources this run's census names and
for no others. So a capture of graph A destroys graph B's rows in both relations even where A and B
share no source at all, which is the ordinary two-modules-two-jOOQ-packages workspace and not the
shared-package case the item's own scenario turns on. A upserts only A's sources, B's claims are
not deleted, the reader-side pass skips B, and B's partitions stand claiming currency over rows
that no longer exist.

This is not a distant reachability argument. Four of the twenty registrations reach both relations,
through one junction view:

- `intent_node_id_instruction_live` and `intent_input_field_filter_role_live`, via
  `intent_node_type` and `intent_inferred_node_type` into `intent_node_metadata_defect`.
- `intent_mutation_payload_refusal_live` and `intent_mutation_payload_column_live`, via
  `intent_input_field_carrier_role`, `intent_node_id_decode_column` and
  `intent_resolved_node_key_column` into the same `intent_node_metadata_defect`.

**The enforcer cannot see it, and the sentence that says it can is wrong about the code.** Assertion 1
asks whether each base relation in the closure carries `graph_name` or `source_name`. Both relations
carry `source_name`, so both pass. The item argues that this is nonetheless sufficient: "the
assertion also catches the wholesale-cleared relations for free, since `StoreRefresh.wholesale`
empties every base relation that is neither graph-keyed nor source-partitioned, so a registered view
reading a relation that any run empties outright fails this assertion too." `wholesale()` does not
compute "not source-partitioned" from any column. It subtracts a hand-maintained `Set<Table<?>>`
constant. Source-keyed and source-partitioned are therefore two different predicates in this tree,
and every relation on which they disagree is a registered read that assertion 1 waves through while
a run empties it outright. Assertion 3 does not close the gap either: `sql_` is deliberately off its
roster, because hook 2 is supposed to cover it.

The shape of this is the same as rounds 1 to 3: a source-keyed read whose invalidation the rule
misses, arriving through a different door. What is different, and worth saying plainly, is that the
claim design is not what introduces the underlying damage. `StoreRefresh` destroys B's rows in these
two relations today, and today's unconditional `refreshAll` merely recomputes B's four targets from
the emptied relations rather than repairing them. The item does not have to own that defect. What it
does have to own is that its stated premise is false over the register as it stands, and that the
gate it offers as the premise's enforcer passes on the four registrations that falsify it. The gate
is the item's whole answer to how the rule stays sound as registrations are added, so it has to be
able to fail on the reads that break it.

What would satisfy the finding, any of these, and the choice is the author's:

- Make assertion 1 read the predicate that actually decides the clear. `StoreRefresh.PARTITIONED`
  and `wholesale()` live in `graphitron`, the closure computation in `graphitron-model`, so this is
  a placement question as much as an assertion one; a relation being emptied wholesale is what has
  to fail, not a column being absent. Then say what happens to the four registrations that fail it,
  which is likely a dependency on fixing the `PARTITIONED` omission rather than work in this item.
- Or state the omission as a fourth thing the rule gives up, on the same terms as the hand-emptied
  target and the cross-process window, and amend "What changes for a consumer" so the single-JVM
  promise is one the rule keeps. That is the cheap answer, and it is defensible only if the gate
  still names the two relations so a fifth registration reaching them is not silent.
- Or file the `PARTITIONED` omission as its own Backlog item and depend on it, which is the honest
  reading of what was found: `sql_routine` and `sql_routine_parameter` sit in the same hole with no
  registered reader yet, so the next registration that names a routine walks into it.

**Non-blocking, precision in hook 2's statement.** "Every rewrite of a source's partition is
preceded, in the same transaction, by one upsert of that source's `store_source` row." Preceded is
not what the code does in two of the three sites, and does not need to be: `StoreRefresh.clear`
deletes the stale `jvm_` partitions before any upsert, and `clearSchemaSources` deletes the `sql_`
partition and upserts after it. Same transaction is the property the rule needs and the property the
tree has, so the fix is to drop "preceded".

**Non-blocking, a corner in hook 1's reach.** `captureExtensions` calls `sources.record` only after
`sink.claim(JVM_CLASS, className)` succeeds, so a source whose every class name was already claimed
by an earlier entry in the same census gets its partition cleared by `StoreRefresh.clear` and never
upserted. It needs a source all of whose classes are shadowed by a duplicate earlier on the
classpath, so it is rare rather than impossible, and it is worth a sentence somewhere rather than a
mechanism.

**Verified this round,** beyond what rounds 2 and 3 list: every class the item names exists at the
path it implies, all twenty-four of them; `meta_materialize` holds twenty rows and all twenty target
tables carry `graph_name`; `store_materialized_partition` is absent from the schema, as a new
relation should be; every comment the item quotes exists verbatim at the place it attributes it to,
including `store_`'s and `meta_`'s family charters, `store_source`'s and `store_graph`'s table
comments, `meta_family_bridge`'s foreign-key sentence, `StoreRefresh.graphScoped`'s
ownership-scoped-by-default sentence and `ClasspathSources.upsert`'s "about to (re)write" sentence;
`refreshAll` loops registrations outer and graphs inner, holds no transaction of its own and is
called from `DevMojo` alone in production; `analyse` returns a count; `RefreshProgress.Event` is
sealed over the four arms the item extends; `ViewReferences.relationsReadBy` is public and
`MaterializeDependencies` recurses over it; the five off-cadence families are each graph-keyed and
`java_` is keyed on `file` with neither `graph_name` nor `source_name`, so assertion 1 does fail on
a `java_` read as claimed; `MaterializeRegistryGateTest` holds its exemptions as a named `Set.of`
roster, which is the shape assertion 3 borrows; the cost paragraph now states R856's price list
accurately, positions 1 to 14 at 199 seconds with 15 and 16 unmeasured and the total fenced; and
R848, R856, R858, R859, R864 and R865 carry the statuses and shapes the item attributes to them,
with R855 Done and its account in the changelog.

Verdict: stays in Spec.
