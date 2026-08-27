---
id: R857
title: "A dev start evaluates the whole materialization register twice, the second pass producing identical rows"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: []
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

One full evaluation of the register, on the cadence of every `graphitron:dev` start. On a consumer
schema measured for the sibling hang item, one pass over sixteen registrations is about 200 seconds,
so the doubling is not a rounding error; on a small schema it is small. The register has grown since
that measurement, to twenty registrations. The cost scales with the store rather than with the
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
sooner, plus one further pass per graph the store holds beyond the session's own. Nothing about
generated output changes, and no reader may observe a stale row: the pass that gets skipped is
exactly the pass that would have rewritten every row with the value it already held.

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
fact, then let the reader ask instead of assume. The store already carries two relations of exactly
this kind about the materializer, `meta_materialize` for the registrations and
`meta_materialize_dependency` for the refresh order derived from the stored view definitions.

The cheapest alternative of all, deleting the call and trusting that every capture refreshed what it
wrote, is rejected on the same grounds and one more. A cold store, and a store holding a graph no
capture ever reached, genuinely need the pass, so the call cannot go; and with it gone the argument
that the targets are current would live only in prose, which is the state this item is a report of.

**What the rule gives up.** A restart stops repairing a target somebody emptied by hand, through the
store console the dev session exposes or otherwise, because the fill row still says it is current.
That is the ordinary standing of a cache whose contents were hand-damaged, and the remedy is the
ordinary one: delete the store directory and let the next build refill it. Worth stating because
today's unconditional pass does silently repair it, so this is a real property being traded away
rather than an oversight.

## What makes a target current

A registered target is stale exactly when the rows its source view reads have changed since the
target was filled. The store already stamps that: `store_graph.last_captured` is rewritten by
`FactCapture.writeGraph`, which leads the capture transaction, so the stamp changes in the same
transaction as the rows and never apart from them.

So one relation closes it. `meta_materialize_fill` records, per registration and graph, the stamp
the graph stood at when the refresh last filled that partition. Every refresh writes it: the capture
path inside its own transaction, where the stamp it reads is the one its own `writeGraph` just set,
and the reader-side pass as it goes. The reader-side rule is then an equality: refill the partition
for a graph when no fill row matches that graph's current `last_captured`, skip it when one does.

Four properties are worth stating because they are what make the rule sound rather than merely
plausible.

**A writer never consults it.** `Materializations.refresh`, the capture-cadence entry point, stays
unconditional and only records. A writer that has just rewritten the partition's inputs knows they
changed; only a reader has a question. That also removes any dependence on the stamp being unique or
monotonic, since the rule compares for equality and the writer never compares at all.

**The rule covers graph-keyed targets only.** A target with no `graph_name` in its shape may read
rows no graph's stamp covers, so its currency cannot be argued from `store_graph` and it is refreshed
whole and unconditionally, as today. Every one of the twenty registered targets is graph-keyed, so the
production register is covered completely; the whole-target arm has residents only in the scratch
fixtures of `MaterializationOrderTest`, which is also what keeps those cases working unchanged.

**A missing fill row means genuinely stale, not merely unproven.** The store's file lives in a
directory stamped with the DDL hash and generator version (`GraphitronModelStore`), so a partition
filled before a registration existed is in a different file rather than in this one, unrecorded.

**Partial progress is safe, so the reader-side pass needs no transaction of its own,** which it
cannot have anyway because `analyse` commits. Two cases. A pass that dies after refilling some
registrations leaves those partitions filled under the current stamp and the rest unrecorded, and the
next pass finishes them. And a partition refilled while its stamp is unchanged is refilled with the
values it already held, so a dependent whose prerequisite was refilled under one stamp is not made
stale by it. Ordering holds the other direction too: the pass refreshes prerequisites first, so a
death mid-pass leaves a prerequisite fresher than its dependent, which is the safe direction.

## The premise, and its enforcer

The rule rests on one premise: every relation a registered source view reads changes only inside a
capture transaction, the transaction that stamps the graph and records the fill. That premise holds
today and nothing enforces it.

It is checkable from the store, with the walk that already exists.
`ViewReferences.relationsReadBy` answers what one stored view definition reads, parsed out of the
definition rather than scanned for textually, and `MaterializeDependencies` already recurses over it
to reach the registrations a view depends on. The premise wants the same recursion stopped at base
tables instead: the closure of a registered source view's reads, which is a handful of lines over
that primitive and needs no new production API. Today every registered source
view bottoms out in the `store_`, `graphql_`, `graphitron_`, `sql_`, `jvm_` and `intent_` families,
all of them written by capture. None reaches `java_`, `javac_`, `walk_`, `rejection_`, `lint_` or
`build_warning_`, which are the families written outside a capture transaction: the dev session's
`CompileFacts`, `JavaSourceFacts`, `RejectionFacts` and `BuildWarningFacts` write on their own
cadences, and the walk-side backing rows are written by `FactCapture.detect` after the capture
transaction has committed.

A registration whose view reached one of those would serve stale rows to the language server under
this rule, silently, and that is the gate to add: the read set of the registered source views is
disjoint from the families written off the capture cadence.

The gate is not debt this item introduces. Such a registration is already wrong on the build path,
where nothing calls `refreshAll` at all and the target is therefore never filled from the rows
written after the transaction; the dev session's unconditional pass is the only thing that would have
hidden it, and it hides it on one goal out of several. So the premise is one the register already
depends on, stated and enforced here because this is where it becomes load-bearing.

## Implementation

**`graphitron-model.sql`.** New table `meta_materialize_fill (source_view_name, graph_name,
filled_stamp)`, primary key on the first two columns, foreign keys to `meta_materialize` and
`store_graph`. Its comment states what a row claims and, in one sentence, the premise above, since
that is where a future registration's author will meet it. Column comments per the schema's own
convention; the `meta_` prefix places it in the family census with no exemption row needed, and the
generated schema reference picks it up from the comments.

Adding a table changes the DDL hash, so the first build after this lands opens a new store directory
and captures cold once, and the directory it stopped using stays behind. That is the standing cost of
any DDL edit here rather than anything this item introduces, and it is R858's subject.

**`Materializations.refresh(DSLContext, String)`.** Unchanged in effect, plus the fill record: one
read of the graph's `last_captured`, and one row written per registration whose partition it filled.
A graph with no `store_graph` row records nothing, which is the scratch-store case and correctly
leaves the partition unproven.

**`Materializations.refreshAll(DSLContext)`.** Keeps its name and its postcondition, every target
current on return, and gains the currency check: read the graphs and the fill rows once, refill the
pairs whose stamps do not match, record each fill, and call `analyse` only if something was filled.
Returns the number of partitions refilled, which is the test observable, on the precedent `analyse`
set by returning a count rather than logging. Its javadoc states the premise it now rests on.

**`DevMojo.execute`.** No code change. The comment at the call site says the refresh is there because
a warm store whose capture was skipped would otherwise serve stale rows; that stays true and becomes
precise, so it gains a sentence naming what the call now costs on the ordinary path.

**No production change for the gate.** The reach it needs is `ViewReferences.relationsReadBy` closed
over the views it returns, computed in the test. Worth stating because the first draft of this spec
proposed exposing a base-relation reach from `MaterializeDependencies`, and the public primitive that
landed with the re-evaluation metric makes that unnecessary.

**`SeededStore.derive`.** Clears `meta_materialize_fill` before refreshing. The fixture seeds rows
directly, without a capture and without touching a graph's stamp, so it is precisely the writer the
premise excludes; clearing the fill rows is that fixture stating its own irregularity in one line,
and it keeps the production surface at one entry point rather than adding an unconditional variant
for tests to reach for. Its javadoc says so, next to the sentence that already explains why the
helper refreshes unconditionally.

## Tests

- **`MaterializationOrderTest`**, or a sibling class if that one's fixtures stay graph-free: a
  graph-keyed scratch registration, refreshed at capture cadence, after which `refreshAll` refills
  nothing and returns zero. Then the same store with the graph's stamp moved, where it refills.
- **Two graphs, one recorded and one not**: `refreshAll` refills only the unrecorded graph's
  partition. Asserted on rows and not only on the count, by planting a row in the recorded partition
  that the source view does not produce and showing it survives while the other partition fills.
- **The end-to-end claim, in the capture tier over `CapturedStore`**: capture a fixture schema into a
  real store, then `Materializations.refreshAll` refills nothing. This is the item's goal in one
  assertion, and it is the one that fails if a future registration breaks the premise in a way the
  family gate below does not catch. Beside it, the two-graph shape `WarmStartRefreshTest` already
  captures for its sibling-partition cases: two graphs captured, and a refresh after both refills
  nothing for either.
- **The premise gate, in `MaterializeRegistryGateTest`**: the base relations reached by the
  registered source views, closed over `ViewReferences.relationsReadBy`, are disjoint from the
  families written off the capture cadence. The
  off-cadence prefixes are a roster in the test with the reason stated, which is the shape that gate
  already uses for its index exemptions; lifting the cadence into a `meta_family` column is a bigger
  question and is out of scope here.
- `MaterializationOrderTest`'s existing `refreshAll` case and the seeded-store fixture's callers are
  the regression surface for the two arms deliberately left unconditional; they pass unchanged, which
  is the point, so no new case is owed there beyond the graph-keyed ones above.

## Sequencing against R855

R855 is `Spec` at priority 1 and rewrites the same two methods: `refresh` and `refreshAll` gain a
`RefreshProgress` observer, and the position is threaded through the private helpers this item also
edits. Land R855 first and build on its shape rather than the shape in the tree today; taking them in
the other order means one of the two rewrites the other's edit.

Its shape also decides something this item would otherwise get wrong. A pass that skips every
partition and says nothing is the anonymity R855 exists to remove, arriving by a new route: a person
watching a warm dev start would see the same silence as a person watching a stuck one. So a skipped
partition is an observation the observer reports, not an absence, and the reader-side pass owes a
statement of what it skipped and why on the same terms as what it filled. Whether that is per
registration or one summary line is R855's vocabulary to decide, not this item's.

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

## Related

The sibling logging item R855 would have made this visible without reading the source, which is how
both sessions that found it found it instead; it is now specced, and the sequencing section above says
what this item owes it. R848 asks whether the register needs to be this large at all. R859 is the
double capture this item's fix leaves in place.

`depends-on` is left empty deliberately. The dependency on R855 is a sequencing preference between two
items that touch one pair of methods, not a blocker: this item is implementable against the tree as it
stands and would only have to be re-fitted afterwards.
