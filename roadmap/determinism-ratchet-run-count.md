---
id: R742
title: "The determinism ratchet costs 229 seconds: too many generator runs, and each run too expensive"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The determinism ratchet costs 229 seconds: too many generator runs, and each run too expensive

`GeneratorDeterminismTest` is the second most expensive class in the reactor, at 229.0 seconds
across two test methods, which is a third of the whole build. It is not slow because it asserts
anything expensive. It is slow because it runs the entire generator over the entire fixture schema
four times, and each of those runs costs 57 seconds.

Both halves of that sentence are wrong and this item fixes both.

* **Lever one, what a run costs.** A run is 97% the fact store re-evaluating its own derived
  relations. The deepest of them expands to 2149 relation instantiations per read, because H2 inlines
  every view reference and eliminates no common subexpression. Reducing two relations takes the
  hottest read from 24.5s to 0.72s.
* **Lever two, how many runs there are.** The contract the class guards needs two runs at most, and
  the class performs four. The fourth buys a populated directory the first method already produced.

They are independent and they compound. **Measured together, with both tests green: 229.0s to
20.66s.** The generator's cost is not this test's problem alone, which is why lever one matters more
than the arithmetic here suggests: the same reads run in every consumer's build, six times per
reactor build, and once per `graphitron:generate` a consumer performs.

R733 carries the store's other read work and the two changes that are the baseline for every figure
below (a batched key-column read and one index). This item is the remaining derived-read cost and
the run count. Neither waits on the other.

## What the test is actually for

The generator's output contract has three clauses. Naming them first, because the rest of this item
turns on which clause needs what.

**Determinism.** Two independent runs of the generator over one schema produce byte-identical
output trees. Without it a consumer's build produces spurious diffs, and a generated tree cannot be
checked in or compared across machines.

**Minimal-change writes.** A run against a tree the generator already wrote leaves unchanged files
untouched *on disk*, rather than rewriting identical bytes. The file's modification time is the
observable: a consumer's incremental compiler decides what to recompile from it, so a generator that
rewrites every file on every run turns a one-field schema edit into a full recompile.

**Clean removal.** A compilation unit the schema no longer calls for is swept out of the output
rather than left behind as an orphan that still compiles and still resolves.

Two test classes cover these, at two different breadths:

| Class | Tier | Schema | Tests | Cost | Clauses covered |
|---|---|---|---|---|---|
| `IdempotentWriterTest` | unit | trivial two-type SDL | 6 | 4.3s | all three, as writer mechanics |
| `GeneratorDeterminismTest` | cross-cutting | the full fixture, 4024 lines, 215 type definitions | 2 | 229.0s | determinism, minimal-change writes |

The division is sound and each class says so in its own javadoc: the writer's mechanics do not
depend on emitter breadth, so they are pinned cheaply on a two-type SDL, while the cross-cutting
class exists to hold the clauses over *every* emitter at once (interfaces, unions, `@splitQuery`,
`@asConnection`, `@lookupKey`, input types, enums, federation). That breadth is the whole value and
this item does not propose reducing it.

**One thing is off, and it should be settled here.** `GeneratorDeterminismTest`'s class javadoc
names its subject as "the three-clause generator contract (determinism + minimal-change writes +
clean removal)" and then tests two. Clean removal has no cross-cutting case at all: it is pinned
only against the two-type SDL, where an orphan sweep has almost nothing to sweep and no chance to
sweep the wrong thing. So the Spec pass owes a decision, and both answers are defensible: add the
third case (which the run-count reduction below makes affordable, since a clean-removal case needs a
populated tree and a re-run, and this item produces a shared populated tree anyway), or correct the
javadoc to claim the two clauses it holds. What is not defensible is leaving a javadoc that asserts
coverage the class does not have, since the next reader takes it at its word.

## Lever one: what a run costs

One full run over the fixture costs 57.3 seconds, and 97 percent of it is the fact store
re-evaluating its own derived relations. Emission and the writing of 798 generated files is the
remaining second or two.

The test runs the generator four times, so 4 × 57.3 ≈ 229.0s. That is the arithmetic in full; there
is nothing else in the class.

The control that proves the cost is the fixture's size rather than the invocation:
`IdempotentWriterTest` constructs the generator eleven times and costs 4.3 seconds, because its
schema has two types.

For scale, the whole build pays for six full-fixture runs: four here, one in
`FixtureWarningsGateTest`, and one in the module's default `graphitron:generate` execution.

### The test itself costs nothing measurable

Worth establishing before looking for savings inside a run, because it decides where to look. With
R733's two changes applied and the run count at three, the class measures 62.91s and a run measures
21.0s. Three times 21.0 is 63.0. The test's own scaffolding, which is reading 798 files into a map
twice to compare them, copying a tree, and walking it twice for modification times, disappears into
the noise between those two figures.

So there is no version of this item that speeds the class up by tidying the test. Every second is a
generator run, and the only two levers are how many runs there are (the rest of this item) and what
a run costs (below).

### What a run is made of, after R733

Profiling one run with R733's two changes already applied, so this is the residual rather than the
original: **88.1% of a 21.0s run is four store reads**, and no single thing outside them reaches two
percent.

| Call site | Share of the run | Reads |
|---|---|---|
| `ArgmappingProjectionDefects.authorDefects` | 38.3% | `intent_argmapping_projection_defect` |
| `ArgmappingProjectionDefects.unemittableProjections` | 18.6% | `intent_resolved_node_key_projection` |
| `ResolvedKeyProjections.read` | 18.6% | `intent_resolved_node_key_projection`, again |
| `StoreNodeTables.bindings` | 12.6% | `intent_resolved_node_type_id` joined to `intent_resolved_type_binding` |
| `GraphitronModelStore.create` | 1.8% | the store's own DDL, 140 tables and 56 views per run |
| javapoet emission | 0.6% | |
| schema parse | 0.4% | |
| everything else | under 0.4% each | |

Nothing *outside* the store is worth attacking: store boot at 1.8% is 0.4 seconds, emission and the
writing of 798 files are together under one percent, and the schema parse and classpath scan do not
register. Everything below is therefore about the four store reads.

### Why those four reads are slow

H2 inlines a view wherever it is named and performs no common-subexpression elimination. The
`intent_` stratum is twenty-two views deep, so multiplicities compound down the tree: a view named
four times is evaluated far more than four times.

Reading `intent_argmapping_projection_defect` once expands to **2149 relation instantiations**.
Inside that single query `intent_argmapping_pair` appears **55 times** and `intent_spelled_table`
**39 times**. It scans about 2.57 million rows to return a handful of defects.

Three measurements rule out the alternative explanations, and each of them closes off a fix that
would otherwise look attractive.

**It is not query compilation.** `EXPLAIN` on that statement, which compiles without executing, costs
0.20 to 0.36 seconds against 24.5 seconds of execution. The enormous plan is a symptom, not the bill.

**It is not something a cache would absorb.** The identical statement executed twice in a row costs
24.52s then 24.53s. Nothing memoises and nothing can; each reader is a fresh statement.

**It is not predicate pushdown a rewrite would restore.** Several relations in the tree carry window
functions (`intent_spelled_table` a `COUNT(*) OVER`, `intent_resolved_node_key_column` and
`intent_resolved_node_type_id` a `DENSE_RANK() OVER`), and a window sees its whole partition whatever
predicate the reader applies outside it. `docs/architecture/explanation/fact-model.adoc` states this
rule already and states the remedy: take the relation once and pair it on its key. What that page
does not say, and what this item adds, is that the arithmetic applies just as much to a view a
*derivation* names repeatedly as to one a Java caller reads in a loop. The Java-side version of this
defect is visible at the call site. The SQL-side version is invisible at every call site.

### The selection rule, which is the reusable part

Inline multiplicity is computable statically from `graphitron-model.sql`: parse the `CREATE VIEW`
bodies, count each relation's textual references, multiply down the tree. No database, no profiler.
It ranked the two relations worth reducing before any of them were measured.

| Relation | Instantiations in one defect read |
|---|---|
| `intent_argmapping_pair` | 55 |
| `intent_spelled_table` | 39 |
| `intent_argmapping_segment_binding` | 14 |
| `intent_field_reference_step_hop` | 12 |
| `intent_name_matched_key_pair` | 12 |
| `intent_bound_table` | 8 |
| `intent_argmapping_binding_leaf` | 7 |

And where the 2149 come from, which prices what a rewrite could buy:

| Direct child of the defect view | Times named | Subtree size | Contribution |
|---|---|---|---|
| `intent_argmapping_key_column_candidate` | 2 | 518 | 1036 |
| `intent_argmapping_binding_leaf` | 5 | 149 | 745 |
| `intent_argmapping_bound_parameter_type` | 1 | 157 | 157 |
| `intent_argmapping_pair` | 6 | 18 | 108 |
| `diagnostic` | 1 | 42 | 42 |
| `intent_authored_claim_conflict` | 1 | 34 | 34 |

The five and six are the view's six `UNION ALL` arms, each re-joining the same driving relations with
a different `WHERE` and a different verdict literal.

**That metric should become a build gate.** It is the answer to the "every invariant has an enforcer"
gap R733 names for the derived-read rules: a `roadmap-tool` check in the family of
`AdocXrefAnchorCheck` and `ModuleEnumerationCheck` computes multiplicity from the DDL and fails on a
relation over a stated ceiling, reduced relations being exempt by construction since a table has no
subtree. Two things for the Spec pass to fix: the ceiling, and whether it reports or gates on first
landing. The metric is a static over-approximation, counting textual references without knowing which
arms a predicate prunes, which argues for a generous ceiling that catches order-of-magnitude cases
rather than a tight one.

### Two routes, and the trade is not close

**Route A: a registered materialization mechanism, and two relations registered into it.** The view
stays exactly as it is, keeping its name and its rule. What is added is a registry of views that
should be materialized after the crawl, and one mechanism that reads the registry and does it.

* **The registry is authored rows in a `meta_` view**, which is this schema's established way of
  describing itself: `meta_family`, `meta_prefixless_relation` and `meta_relation_family` are all
  `VALUES` views, "authored as constant rows stated as views, so the description is versioned with
  the DDL it describes and can never be refreshed apart from it". A materialization registry is the
  same kind of statement and belongs beside them, naming for each entry the view, its target table,
  and the views it must be materialized after.
* **The dependency edges are registered, not inferred**, so the materializer can topologically sort
  and populate in an order that is stated rather than derived. This closes the gap
  `fact-model.adoc` leaves open when it says to "write the population order explicitly rather than
  deriving it, since H2 offers no dependency catalog to derive it from": H2 has none, so the schema
  carries its own.
* **The materializer lives in `graphitron-model`**, the module whose DDL declares the relations, as
  one method that reads the registry and refreshes every target in order. It is called by the
  generator's capture pass, the dev loop, the language server, the MCP server, and the seeded test
  fixture. One entry point, so no caller invents its own ordering and no caller can forget a
  relation that was registered after it was written.
* **Two relations are registered by this item**, `intent_argmapping_pair` and
  `intent_spelled_table`, and the mechanism is what makes the third and fourth cheap.

The readers that want the fast path reference the target; readers that want on-demand evaluation
reference the view, and get the same rows either way because the target is populated by
`INSERT INTO <target> SELECT * FROM <view>`. That equality is by construction and a gate should pin
it rather than leave it as an argument. What keeps the two names from becoming two readings of one
population is that a reader picking the slow name is a performance bug and nothing else, and the
multiplicity check below is what finds it.

`fact-model.adoc` sanctions the mechanism and the store already has four written `intent_` tables,
with `ReachabilityRows.write` as the delete-by-graph-then-insert cadence. It does not sanction the
*justification*, which is the reviewable part and has its own section below.

Measured, with `intent_argmapping_pair` and `intent_spelled_table` reduced, on top of R733's two
changes:

| | Defect-view query | `FixtureWarningsGateTest` | `GeneratorDeterminismTest` |
|---|---|---|---|
| trunk | | 56.8s | 229.0s |
| R733's two changes | 24.5s | 23.5s | about 93s |
| plus these two reductions | **0.72s** | **6.85s** | **20.66s** |

The profile afterwards is healthy rather than merely smaller: H2 falls from 97% of a run to 67%, the
four store reads from 88% to about 48%, and the largest single remaining item is the store's own DDL
boot at 9%. No dominant pathology is left.

**Route B: flatten the six-arm union into one pass.** The defect view's five `binding_leaf`-driven
arms differ only in predicate and verdict literal, so one pass with a `CASE` verdict and a left join
to the segment relation would collapse them. It changes no read semantics and needs no freshness
reasoning, which makes it tempting. The decomposition table prices it: at most 4 × 149 = 596 of 2149
instantiations, about 28%, leaving the 1036 the two `key_column_candidate` references contribute.
Worth doing as a simplification on its own merits; not an alternative to Route A.

Take Route A. Route B is a follow-on.

### What Route A costs, which is less than the prototype made it look

The prototype broke **thirteen `graphitron-model` test classes**. They seed rows into base tables and
read a derived relation directly, with no capture pass anywhere in the fixture, so a reduction
nothing populated returns zero rows: `ArgmappingPairTest`, `ArgmappingProjectionDefectTest`, `ArgmappingBindingLeafTest`,
`ArgmappingSegmentBindingTest`, `ColumnMatchClaimTest`, `FieldColumnTableTest`,
`FieldRoutineMethodTest`, `NodeTypeTest`, `ReferenceStepTargetTest`, `ResolvedNodeKeyColumnTest`,
`ResolvedNodeKeyProjectionTest`, `SeparateFetchRuleTest`, `TypeBackingTest`.

**Those thirteen failures are a defect in the fixture, not a cost of the change, and the item should
not be specced as though they were the latter.** Nothing observable changed. A reduction is populated
by `INSERT INTO <target> SELECT * FROM <view>`, so it holds exactly the rows the view computed,
by construction. Every one of those tests asserts which rows a relation yields given a set of facts,
and every one of those assertions is still true. They failed anyway, which means they were coupled to
*when* the derivation runs rather than to what it returns. A change made purely for speed, that
alters no answer, should be able to reach into the implementation without a single one of them
noticing.

The reason they can notice has a name, and the store's own architecture already supplies it.
`fact-model.adoc` describes three strata: capture transcribes facts, derivation computes further
facts from them, and queries read. `SeededStore` models the first and the third. It has no
derivation phase, because under derive-on-read that phase is implicit and free, so its absence has
never cost anything. Materialise one derivation and the missing phase becomes visible at once. So
this is one missing concept in a fixture, not thirteen broken tests.

**Give the fixture the derivation boundary, once, and the tests never mention materialization.** The
seam already exists: every one of the thirteen classes reads through a per-class helper (`rows(dsl)`,
`only(dsl)`, `rowFor(dsl, ...)`), thirty read sites in total. Route those through `SeededStore`,
which calls the same `graphitron-model` materializer every other caller calls, and the thirteen
classes change by one line each and by nothing else, ever again. Registering a fourth or a fifth
relation later costs the fixture nothing, because the fixture calls the mechanism rather than naming
relations. Sizing the work as "thirty refresh calls the tests now have to remember" is the version
that keeps the coupling and pays for it repeatedly; it was the earlier reading here and it was wrong.

**The refresh belongs on the capture cadence, not the detection cadence.** The prototype populated
inside `FactCapture`'s detection pass, which is skipped when a run has no walk reach. A capture that
writes rows and leaves a materialized target stale is the failure mode the design has to exclude, so
the call goes immediately after capture, unconditionally. This is the same method the fixture calls,
which is the point: one entry point means the production boundary and the test boundary cannot drift
apart, and neither of them holds a list of relations that a later registration could invalidate.

Two ways to avoid touching the tests entirely were considered and both are worse. Base-table triggers
that recompute a reduction on write are fine on a seeded store of a dozen rows and catastrophic on a
capture writing tens of thousands, across the sixty-odd base tables these two reductions read.
Refreshing lazily on read, gated by a staleness stamp, puts a write on the read path and has to
answer for concurrent readers and read-only connections. The fixture boundary costs thirteen lines
and asks nothing of production.

**Every materialized target needs its own comment text.** `SchemaReferencePagesTest` fails the build
on any relation or column with a blank comment, and a target is a new relation carrying none. That is
the gate working: the view and its target are two relations a reader can land on and they owe
different sentences, the view saying what the rule is and the target saying that it is a
registered copy and when it is refreshed. Budget the prose, per registration.

**Do not split the readers.** The tempting shortcut is to let the seeded tests keep reading the
view while the runtime reads the target. That makes the tests exercise different SQL from
production, which is the "two readings of one population" drift this schema's own comments warn about
repeatedly. One new test pinning that a reduction equals its rule after a refresh is the honest
version of that instinct.

### The doctrinal question, which is the one a reviewer actually has to answer

The store already materialises a derivation on the capture cadence, so the *mechanism* needs no
argument. What needs one is the *justification*, and the existing precedent does not supply it.

`intent_type_domain` is the incumbent, and its comment states why it is a table in terms this item
cannot borrow: "Materialized, not a view: the closure over cyclic type graphs has no safe H2 view
form (a recursive UNION does not terminate on cycles, and the path-guarded form enumerates simple
paths)." That is materialisation justified by **impossibility**. Every relation this item registers
has a perfectly good view form that returns the right answer; it is merely expensive. So the proposal
extends the doctrine from "materialise what a view cannot express" to "materialise what a view
expresses too slowly", and that extension is the reviewable decision.

Two places say the narrow version today and both have to move, which is convenient, because it means
the doctrine is written down rather than assumed.

* The DDL header's cadence paragraph says a post-capture family has its own writer on its own
  cadence, and says nothing about why a family would be post-capture in the first place. The
  registry gives that answer a home.
* The `intent_` family charter in `meta_family` says the residents are "views plus the materialized
  derivations **whose table comments own why they cannot be views**". Under a registry the reason is
  no longer per-table prose and no longer "cannot": it is a registration, and the charter should say
  so.

The incumbent's shape also differs, and the registry is what reconciles them rather than leaving two
variants side by side. `intent_type_domain` is computed in Java by `ReachabilityRows` and written;
there is no view stating its rule, so there is nothing for a registry to point at. Whether it stays
outside the mechanism, keeping its Java derivation and its own comment, or acquires a view and joins
the registry, is a decision this item should take explicitly rather than leave to whoever notices the
asymmetry next.

### Gates the change has to clear, none of them optional

Four beyond the compile, and the item should not discover them one build at a time.

* **`FactSchemaGateTest`, comments.** Every relation and every column in `PUBLIC` must carry a
  non-null `REMARKS`. Each materialized target is a new relation and arrives uncommented.
* **`FactSchemaGateTest`, documentation home.** Every relation must resolve to exactly one family
  page through `meta_relation_family`, or carry an exemption row in `meta_prefixless_relation`, and
  no relation may match two family prefixes. An `intent_`-prefixed rule view should house itself,
  but that is a prediction and the gate is the authority.
* **`FactCaptureAgreementTest`, arm registration.** Every relation is classified into an arm, and the
  `DERIVED` arm's javadoc enumerates "views, and the materialized capture-cadence derivation
  `intent_type_domain`". Each new reduction joins that sentence and that registration.
* **`CommentRenderabilityGateTest` and `SchemaReferencePagesTest`.** The comment text has to render,
  not merely exist.

The registry earns gates of its own, and they are the reason to prefer it over per-relation writers.
Each is a closure of authored intent against observed schema, the shape `meta_relation_family`
already uses:

* every registered view exists, and every target table exists with a column list matching its view;
* every target's rows equal its view's rows on a settled store, which is the equality the whole
  design rests on and the one thing no amount of prose can substitute for;
* the registered dependency edges are acyclic, and agree with the edges a parse of the DDL finds, so
  a registration that forgets an edge fails rather than producing a target populated from stale
  inputs;
* nothing outside the registry is a materialized `intent_` relation, which is what stops the next
  bespoke writer from appearing beside the mechanism instead of inside it.

### Also unsettled

* **Which relations to reduce.** Two sufficed for the measured case; the multiplicity table names
  five more above eight. Reduce on measured need, and record what was left unreduced as a decision
  rather than an omission.
* **The gate's ceiling**, and whether the multiplicity check reports or fails on first landing.
* **How a target is named.** The view keeps its own name, so the target needs one. A stated pair in
  the registry needs no convention and no parsing; a suffix convention is terser and lets a gate
  check the pairing without reading the registry. Minor, but pick one and write it down.
* **Whether a target is graph-partitioned or whole.** Both registered relations lead with
  `graph_name`, so a per-graph `DELETE`/`INSERT` is the obvious refresh and it keeps a capture from
  disturbing a sibling graph's rows. A registry entry probably has to say which it is, since a
  relation with no graph in its key (`intent_class_member_slot` is the existing example of one) can
  only be refreshed whole.
* **Readers that arrive without a capture.** The language server and the MCP server open the store
  directly. They are already callers of the materializer in this design, but "call it on open" and
  "assume a capture ran" are different contracts, and the second one needs the "absence needs a
  stated meaning" treatment if it is chosen.
* **Warm and shared stores.** The persisted store is shared across a workspace's modules, which
  build in parallel under `-T 1C`. Partition-by-graph makes concurrent refresh safe on the face of
  it, but a warm start that skips capture because nothing changed must leave targets valid rather
  than empty, and `WarmStartRefreshTest` and `PersistentStoreTest` are where that gets pinned.
* **Whether `intent_type_domain` joins the mechanism**, per the doctrinal section above.

### Deliverables, in the order the numbers argue for

1. **The mechanism**: the `meta_` registry with its dependency edges, the `graphitron-model`
   materializer, its call from the capture pass and from `SeededStore`, the registry's own gates, and
   the two doctrine paragraphs. Lands with zero relations registered and changes no timing, which is
   what makes it reviewable on its design rather than on its numbers.
2. **The two registrations**, `intent_argmapping_pair` and `intent_spelled_table`, plus the LSP and
   MCP call sites. This is the 229.0s to 20.66s, and by this point it is two rows of authored data
   rather than a schema change.
3. **The multiplicity check**, reporting only, so the third and fourth registrations are chosen from
   data rather than from this item's table.
4. **The run-count reduction**, four runs to three. Worth doing because a test should not perform
   work its contract does not need, and worth doing *after* the above, because by then it saves about
   five seconds rather than about sixty.
5. **The clean-removal coverage decision**, and the javadoc correction it implies either way.
6. **The duplicate-read merge and the `Files.mismatch` cleanup**, both re-measured against the shape
   they would actually ship into rather than against today's.

### A smaller one on the same path: the same view is read twice per run

Rows three and two of that table read the *same relation* over the *same graph*, from two calls
`FactCapture.detect` makes back to back on the same `DSLContext`.

Reads of that view are fully additive, which is the fact that makes this worth fixing rather than a
curiosity. Two measurements pin it. Adding a redundant third read of it costs **+8.1s per run** (the
view plus the `StoreNodeTables.read` that `ResolvedKeyProjections.read` performs first). Removing
one of the two existing reads saves **4.5s per run**, which is 19% of the residual. Nothing caches,
nothing reuses a plan, and the view carries a window so no outer predicate prunes it.

The two reads are not identical, which is why this is a small piece of work rather than a deletion:

* `unemittableProjections` selects twelve columns and inner-joins `intent_argmapping_pair` on
  (graph, site, use site, position).
* `ResolvedKeyProjections.read` selects five columns and does not join.

One fetch serves both: select the union of the columns with the pair table `LEFT JOIN`ed, then
recover each caller's result in Java. The inner join becomes a filter on the pair columns being
present, and each caller re-applies its own `DISTINCT` over its own column set. That is sound rather
than approximate: a left join never drops a row of the view, so the five-column distinct is
unchanged, and it produces the same row multiset the inner join did for the rows that do match, so
the twelve-column distinct is unchanged too.

Order it after the reductions rather than before: 4.5s is 19% of a 21.0s run and a much smaller share
of a 5.2s one, and the merged query is the kind of change whose value should be re-measured against
the shape it will actually ship into.

### What the changes come to together

Every row measured on one 4 vCPU sandbox with both tests green, except where marked.

| Configuration | Runs | Class total | Per run |
|---|---|---|---|
| trunk | 4 | 229.0s | 57.3s |
| run reduction alone | 3 | 167.3s | 55.8s |
| R733's two changes alone | 4 | about 93s, derived | 23.3s |
| R733 + run reduction | 3 | 62.91s | 21.0s |
| R733 + the two reductions | 4 | **20.66s** | 5.2s |
| all three | 3 | about 16s, projected | 5.2s |

The last row is arithmetic rather than a measurement, and worth stating plainly: once a run costs
5.2 seconds instead of 57, removing the fourth run saves about five seconds rather than about sixty.
The run-count work is still worth doing, on the grounds that a test should not perform work its
contract does not need, but it stops being the lever it looks like today and the Spec pass should
sequence the store work first.

## Lever two: why four runs, and how many the contract needs

Reading the two methods for what each genuinely requires:

* `twoConsecutiveRunsProduceIdenticalOutputTrees` writes into two empty directories and compares the
  trees. Both runs are load-bearing: the assertion is that two *independent* pipeline runs agree, so
  neither can be a copy of the other.
* `secondRunAgainstSameOutputDirPreservesMtimes` generates into a directory, winds every file's
  modification time back two seconds, generates again, and asserts no time moved. Only the *second*
  run is load-bearing. The first one exists to produce a populated tree, and the assertion does not
  care where that tree came from.

So the fourth run is buying a populated directory that the first method already produced. Sharing
one canonical run across both methods, produced once per JVM and copied rather than written into so
the shared tree stays pristine, takes the class to three runs.

**Measured, with both tests still green: 229.0s to 167.3s.** Exactly one run's worth, as predicted.
The change is local to the test class and needs nothing from production code.

### The fork the Spec pass has to settle: is three the floor, or is it two?

Three is the floor *without touching production code*. Two is reachable, by two different routes,
and each gives something up. The Spec pass should pick one and record why, because "just get it to
two" hides a real trade.

**Route A: a seam that writes an already-built output.** `GenerationResult` already carries
`emittedUnits`, the full `Map<String, TypeSpec>` a run emitted, but the code that lands those units
on disk (`writeCommand` / `writeUnits`) is private. Given a public way to write a `GenerationResult`
into a directory, the minimal-change case needs no pipeline run at all: write the units once into an
empty directory, backdate, write the same units again, assert nothing moved. The class drops to the
two independent runs determinism genuinely needs.

What it gives up: the case stops asserting that a *second full generation* leaves the tree alone and
starts asserting that a *second write of one generation's output* does. Those are different
statements. They are equivalent given the determinism clause the sibling method proves, which is a
clean decomposition rather than a loophole, but it is a decomposition and the Spec should say so out
loud. It also adds public API surface whose only consumer is a test, which this repo is right to be
suspicious of.

**Route B: fold determinism into the mtime assertion.** Seed a directory from run one, backdate it,
run the generator into it, and assert no modification time moved. Because the writer's skip decision
*is* a content comparison, "nothing was rewritten" already says "run two produced byte-identical
content to run one". One run proves both clauses.

What it gives up: more, and this is why it is second. The determinism guarantee would then rest on
the writer's comparison being correct. A writer bug that skipped unconditionally would make both
assertions pass vacuously, and the current first method is valuable precisely because it is
independent of the writer: it writes into two empty directories and compares bytes itself. A ratchet
should not depend on the mechanism it guards. Route B would also need an explicit file-set assertion
bolted on, since a file that vanished in run two moves nobody's modification time.

**Recommendation.** Take the three-run version now: it is measured, it is confined to the test, and
it gives up nothing. Treat two as a separate question that the seam decision drives, and note that
after R733 lands, the remaining gap between three runs and two is about 21 seconds rather than 57,
which is a materially weaker case for adding public API.

## Adjacent, and smaller

* **The module runs nothing in parallel.** `graphitron-sakila-example` has no
  `junit-platform.properties` at all. The two methods here hold separate temporary directories and
  are independent once the shared run exists, so concurrent methods would overlap the two remaining
  runs. R733 already carries the rule for this kind of change: one module at a time, and each module
  answers its own shared-state question first. Note it here, do it there.
* **`readAll` slurps both trees into memory.** Two maps of 798 file contents, built to compare them
  entry by entry. This is *not* a performance item and the section above has the number that says
  so: the whole of the test's scaffolding is below measurement noise against the generator runs. It
  is a failure-message item. `Files.mismatch` per path allocates nothing and reports the differing
  byte offset, which beats an AssertJ string diff over a generated Java file when this ratchet
  actually fires, which is the moment it exists for. Worth doing while the class is open, on those
  grounds and not on speed.

## How to re-measure

```bash
# The class alone. -Dleaf-coverage.skip keeps the run from truncating the full-suite traces.
time mvn test -pl :graphitron-sakila-example -Plocal-db -Dleaf-coverage.skip \
  -Dtest=GeneratorDeterminismTest -Dsurefire.failIfNoSpecifiedTests=false
```

The per-run cost is the class total divided by the run count, the runs being uniform. Cross-check
against `FixtureWarningsGateTest`, which is one pipeline run with no emission, so the difference
between the two is emission plus the write of 798 files.

Two recipes the store half of this item needs, neither of which a profiler supplies.

**Splitting compile from execution, inside the real populated store.** Temporarily print, beside the
read under test and behind an environment-variable guard, an `EXPLAIN` of the same statement and two
consecutive executions of it. `dsl.resultQuery("EXPLAIN " + dsl.renderInlined(query))` compiles
without executing; run the statement twice to show whether anything memoises. Use
`EXPLAIN ANALYZE` instead when the question is which relation is being scanned and how often, since
H2 reports a `scanCount` per plan node, and read it with `fetchOne(0, String.class)` rather than
through a jOOQ `Result`, which truncates the plan text. Note that JFR's default `stackdepth=64`
truncates below the H2 frames and hides every caller, so pass
`-XX:FlightRecorderOptions=stackdepth=1024` when profiling instead.

**Inline multiplicity, which needs no build at all.** Parse the `CREATE VIEW` bodies out of
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, count each
relation's word-boundary references per body, and multiply down the tree from the relation under
study. This is the metric the selection-rule section proposes turning into a gate, and it is worth
running by hand first on any relation a reader suspects.

Standing caveats, both of which will produce nonsense if ignored: pass `-Plocal-db` or the jOOQ
catalog jar is silently emptied and the failures will be unrelated cascades, and measure against a
warm local repository or artifact downloads will dominate. Figures in this item were taken on one
4 vCPU sandbox; ratios transfer between machines and absolute seconds do not.
