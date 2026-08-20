---
id: R742
title: "The determinism ratchet costs 229 seconds: too many generator runs, and each run too expensive"
status: In Progress
bucket: dx
priority: 2
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
  relations. The deepest of them expands to 2066 relation instantiations per read, because H2 inlines
  every view reference and eliminates no common subexpression. Reducing two relations takes the
  hottest read from 24.5s to 0.72s.
* **Lever two, how many runs there are.** The contract the class guards needs two runs at most, and
  the class performs four. The fourth buys a populated directory the first method already produced.

They are independent and they compound, but no single run has measured both. **Lever one alone takes
the class from 229.0s to 20.66s**, at four runs, with R733's two changes underneath it. Lever two is
absent from that figure; all three together project to about 16s and nobody has run that
configuration. And the run that produced 20.66s left thirteen `graphitron-model` classes failing,
because the fixture work this item specs was not part of the prototype that measured it. The summary
table below carries the per-row reactor state; quote from there rather than from this paragraph.

The generator's cost is not this test's problem alone, which is why lever one matters more than the
arithmetic here suggests: the same reads run in every consumer's build, six times per reactor build,
and once per `graphitron:generate` a consumer performs.

R733 carries the store's other read work and the two changes that are the baseline for every figure
below (a batched key-column read and one index). This item is the remaining derived-read cost and
the run count. Neither waits on the other in the sense that either can land first, but the numbers
here are not independent of R733: every measurement below was taken with R733's two changes applied,
and **the reductions' standalone effect on a trunk without them was never measured.** `depends-on` is
empty deliberately, since nothing here requires R733 to land first; a reviewer wanting the standalone
figure should ask for it rather than infer it.

## How to read this item: a paving step, deliberately scoped

The obvious objection is that this is several items wearing one hat. A registry in the store's DDL, a
doctrine change, a build gate, a fixture refactor and a test's run count do not obviously belong to
one review. That breadth is a decision rather than an accretion, and the reasoning is worth having in
front of you before the evidence, because it changes what you are being asked to approve.

**We know the direction and not the road.** What the measurements settle is that the fact store's
derive-on-read, right as a default, is wrong for a relation that a deep derivation names dozens of
times, and that materialising on the capture cadence is the mechanism that fixes it. What they do not
settle is how far that goes: how many relations end up registered, whether ordering becomes load
bearing soon or never, whether a refresh ever wants to be incremental rather than a full re-derive of
the graph's partition, or whether the doctrine eventually swallows the four hand-written Java
derivations too. Waiting until those are known means either shipping nothing, or shipping two
bespoke reductions and discovering the mechanism later from a worse position.

**So the item builds the smallest thing that is correct for what it registers, and makes every
simplification visible.** The table below is the contract: each row is a place where this item
deliberately does less than the eventual design, with what makes the lesser version correct today and
where the successor lives. A reviewer's question is not "is this the final shape" but "is each row
safe now, and cheap to revisit".

| Simplified here | Correct today because | Revisited by |
|---|---|---|
| `meta_materialize` records no ordering | neither registered view is in the other's dependency closure; both closures are base tables only | R746, strictly additive |
| `meta_materialize` is a constrained table while the three older `meta_` relations stay `VALUES` views | a registry is where a key and `NOT NULL` earn their keep; converting the others is not this item's subject | R751, the family's form |
| Two relations registered | they are the two the profile named; the rest is guesswork until measured | deliverable 3, the multiplicity check |
| The multiplicity check reports, does not gate | the ceiling is unknown and a wrong one is worse than none | itself, once a few registrations give the ceiling a basis |
| The four Java-derived tables stay outside the mechanism | none has a view for a registry to point at, so including one is a rewrite rather than a registration | open question below |
| The defect view's six-arm union is left alone | worth 29% and independent of everything else | deliverable 6 |
| Four generator runs become three, not two | two needs a production seam whose value drops once a run is cheap | the fork section below |

**What makes it a safe step rather than a bet.** The mechanism lands with zero rows registered, so
step one changes no behaviour and can be reviewed on its design alone. No reader is edited, because
the canonical name a reader already uses is the one the target takes. A target's rows equal its
view's rows by construction and a gate pins it, so no answer changes anywhere. And every
simplification in the table is additive to undo: ordering adds a relation, more relations are more
rows.

**Two things are not cheap to reverse, and they are the parts to review hardest.** The fixture's
derivation boundary changes the shape of thirteen test classes, and the doctrine paragraphs change
what the schema says about itself in three places. Neither is large, but neither is a row of data, and
if either is wrong it is wrong for everything that follows rather than for this item.

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
| `GeneratorDeterminismTest` | cross-cutting | the full fixture, 4082 lines, 215 type definitions | 2 | 229.0s | determinism, minimal-change writes |

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

The recommendation is to add the case. Once the run-count work has produced one canonical populated
tree, a clean-removal case is that tree plus a single run over a schema with a type removed, which by
then costs about five seconds; and a clause pinned only against a two-type SDL, where an orphan sweep
has almost nothing to sweep and no chance to sweep the wrong thing, is the weakest of the three.

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
| `GraphitronModelStore.create` | 1.8% | the store's own DDL, 140 tables and 59 views per run |
| javapoet emission | 0.6% | |
| schema parse | 0.4% | |
| everything else | under 0.4% each | |

Nothing *outside* the store is worth attacking: store boot at 1.8% is 0.4 seconds, emission and the
writing of 798 files are together under one percent, and the schema parse and classpath scan do not
register. Everything below is therefore about the four store reads.

### Why those four reads are slow

H2 inlines a view wherever it is named and performs no common-subexpression elimination, so
multiplicities compound down a tree: a view named four times is evaluated far more than four times.
`intent_argmapping_projection_defect` nests views eight levels deep and reaches seventeen distinct
views, which is enough for the compounding to run away.

Reading it once expands to **2066 relation instantiations**.
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

And where the 2066 come from, which prices what a rewrite could buy. Subtree size excludes the
relation itself, so a contribution is `times named x (subtree + 1)`; the four contributions come to
2060, and the remaining six are the defect view's own direct references to a base table:

| Direct child of the defect view | Times named | Subtree size | Contribution |
|---|---|---|---|
| `intent_argmapping_key_column_candidate` | 2 | 518 | 1038 |
| `intent_argmapping_binding_leaf` | 5 | 149 | 750 |
| `intent_argmapping_bound_parameter_type` | 1 | 157 | 158 |
| `intent_argmapping_pair` | 6 | 18 | 114 |

The five and six are the view's six `UNION ALL` arms, each re-joining the same driving relations with
a different `WHERE` and a different verdict literal.

The metric is a textual count over `CREATE VIEW` bodies, so it must strip `--` line comments as well
as `COMMENT ON` statements before counting; a first pass that stripped only the latter attributed the
schema's prose section headers to the relation whose block preceded them, inflating this total by 83
and inventing two direct children. The check that lands should carry that as a test.

**That metric should become a build gate.** It is the answer to the "every invariant has an enforcer"
gap R733 names for the derived-read rules: a `roadmap-tool` check in the family of
`AdocXrefAnchorCheck` and `ModuleEnumerationCheck` computes multiplicity from the DDL and fails on a
relation over a stated ceiling, reduced relations being exempt by construction since a table has no
subtree. Two things for the Spec pass to fix: the ceiling, and whether it reports or gates on first
landing. The metric is a static over-approximation, counting textual references without knowing which
arms a predicate prunes, which argues for a generous ceiling that catches order-of-magnitude cases
rather than a tight one.

### Two routes, and the trade is not close

**Route A: a registered materialization mechanism, and two relations registered into it.**

A registration produces **three relations**, and stating them as DDL settles the question everything
else in this item hangs off. Taking `intent_argmapping_pair`:

```sql
-- Before: one view, named from sixteen FROM/JOIN sites in other view bodies.
CREATE VIEW intent_argmapping_pair (graph_name, site, ...) AS SELECT ... ;

-- After: the rule keeps its text verbatim and gives up the canonical name.
CREATE VIEW intent_argmapping_pair_live (graph_name, site, ...) AS SELECT ... ;
COMMENT ON VIEW intent_argmapping_pair_live IS '<the original comment, plus: this states the rule and
  is evaluated on demand; intent_argmapping_pair beside it is the materialized copy readers use>';

-- The canonical name becomes a table of the same shape. Every existing reader now hits this.
CREATE TABLE intent_argmapping_pair (graph_name VARCHAR NOT NULL, site VARCHAR NOT NULL, ...);
COMMENT ON TABLE intent_argmapping_pair IS '<what the relation means, plus: materialized from
  intent_argmapping_pair_live on the capture cadence, per graph; registered in meta_materialize,
  which carries why>';

-- And one authored row wires them together.
--   meta_materialize: ('intent_argmapping_pair_live', 'intent_argmapping_pair', '<reason>')
```

**The canonical name moving to the table is the whole mechanism, not an implementation detail.** The
cost being attacked is inline expansion inside *other view bodies*: `intent_argmapping_pair` is named
from sixteen `FROM`/`JOIN` sites in the DDL, and from one Java call site
(`ArgmappingProjectionDefects.unemittableProjections`, the same read the duplicate-read section
below is about). Populating a table under a
different name would leave every one of those bodies naming the view and would buy nothing. Readers
are untouched precisely because the name they already use is the one the target takes, and the rule
is still stated exactly once, in a view, under a name that says what it is.

This is also why `ArgmappingPairTest` failed in the prototype. It reads `INTENT_ARGMAPPING_PAIR`
directly, which is now a table, which nothing had populated. Under the alternative reading, where the
view kept the canonical name and consumers were repointed, that test would read the untouched view
and could not fail. The observed failure is the evidence for this shape.

* **The registry is `meta_materialize`, a table with constraints, populated by an `INSERT` in the
  same DDL file.**

  ```sql
  CREATE TABLE meta_materialize (
    source_view_name  VARCHAR NOT NULL,
    target_table_name VARCHAR NOT NULL,
    reason            VARCHAR NOT NULL,
    PRIMARY KEY (source_view_name)
  );
  COMMENT ON TABLE meta_materialize IS 'Which derived views are materialized after the crawl, and
    into which table. One row is one registration, read by the materializer in graphitron-model on
    the capture cadence: it empties the target and refills it from the source view, per graph where
    the relation is graph-keyed. The pair is directional and the direction is the whole point, which
    is why both columns say which end they are.';
  COMMENT ON COLUMN meta_materialize.source_view_name IS 'The view stating the rule, which is the
    relation the target''s rows are computed from. It carries the original view text unchanged and a
    name it did not previously have, the canonical name having moved to the target; no consumer names
    this relation, and one that does is asking for on-demand evaluation and will get it.';
  COMMENT ON COLUMN meta_materialize.target_table_name IS 'The table the rows are materialized into,
    under the name every existing reader already uses. That is what makes a registration invisible to
    consumers: the other view bodies naming this relation are not edited, they simply stop
    hitting a view and start hitting a table, and so does any Java reader of the same name. A registration that gave the target a new name would
    change no reader''s cost and would be pointless.';
  COMMENT ON COLUMN meta_materialize.reason IS 'Why this relation is materialized rather than left to
    derive on read. Required, because this column is where the materialization doctrine lives: the
    incumbent hand-written derivations each argue in their own table comment that they cannot be
    views, and a registration argues instead that a view would be too expensive, so a row that cannot
    say which is not a registration.';

  INSERT INTO meta_materialize VALUES
    ('intent_argmapping_pair_live', 'intent_argmapping_pair', '...'),
    ('intent_spelled_table_live',   'intent_spelled_table',   '...');
  ```

  The comments are not decoration. `FactSchemaGateTest` fails the build on any relation or column in
  `PUBLIC` carrying a null `REMARKS`, so every one of them is required, as is a comment on each
  target table and on each renamed source view. Since the direction of the pair is what a first
  reader gets wrong, the column comments are where that is worth spending words.

  It inherits the `meta_` family's *purpose*, which is the schema describing itself in rows authored
  beside the DDL they describe, and departs from the family's current *form*. The three existing
  `meta_` relations are `VALUES` views, which take no constraints at all: no key, no `NOT NULL`, and
  column types inferred from literals, which is why `meta_prefixless_relation` has to write
  `CAST(NULL AS VARCHAR)` to get a nullable column. A registry is precisely the case where those
  constraints earn their keep. `reason` is `NOT NULL` because the doctrine below moves "why this is
  not simply a view" out of a per-table comment and into the registration, so a registration that
  cannot say it is not a registration; the earlier draft of this item proposed a build gate for that,
  which was the wrong instrument and a symptom of the missing constraint.

  Two consequences to carry rather than discover. The store's boot loop executes split statements
  generically, so an `INSERT` needs no machinery, but the DDL today contains none and this would be
  the first. And `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension` walks every base
  relation carrying a primary key, expecting `graph_name` unless the name matches `sql_`, `jvm_`,
  `java_` or an enumerated `store_` case; a `meta_` table with a key falls into the `else` and is
  reported. The gate needs a `meta_` arm expecting the relation's own key, which is in keeping with
  its own javadoc, that `store_` "answers the question per relation rather than per prefix". No
  `meta_` relation has ever had a primary key for the gate to see, so this is a hole the first one
  opens rather than a rule it breaks. `meta_relation_family`, the third, is neither an authored roster nor a table: it is the census that
  closes the authored rows against `INFORMATION_SCHEMA`, which is the shape this registry's own gates
  take.
* **The materializer lives in `graphitron-model`.** That is forced rather than preferred:
  `SeededStore` is in `graphitron-model`'s test sources and cannot reach `graphitron`, where the
  existing derivation writers live, and the fixture has to call the same entry point everything else
  calls or the whole argument below collapses.
* **Two relations are registered by this item**, `intent_argmapping_pair` and `intent_spelled_table`,
  and the mechanism is what makes the third and fourth cheap.

**The refresh is per graph, inside the capture transaction, and there is already a place for it.**
`FactCapture.capture` ends with a block whose comment names the stratum this item is joining:

```java
sink.flush();
// The capture-cadence derivation stratum: materialized derivations re-derive from
// the flushed rows inside the same transaction, so they are current exactly when
// the partition they derive from is.
ClassificationDomainCapture.derive(txDsl, graph.name(), schema, synthesizedEdges);
InputOccurrencePaths.derive(txDsl, graph.name());
TypeBackingRows.derive(txDsl, graph.name());
```

The materializer is a fourth line there, on the same `txDsl` and the same `graph.name()`. Three
consequences follow and none of them is optional. The refresh runs **inside the capture transaction**,
so no reader ever observes an emptied target. It is scoped **per graph**,
`DELETE FROM <target_table_name> WHERE graph_name = ?` then
`INSERT INTO <target_table_name> SELECT * FROM <source_view_name> WHERE graph_name = ?`, because a
capture of one
graph has no business rewriting a sibling's rows and the comment above states that as the stratum's
invariant. And the materializer decides which of those two forms to use by whether the target carries
a `graph_name` column, which a graph-keyed relation must anyway to satisfy the anchor gate below; a
relation with no graph in its key (`intent_class_member_slot` is the existing one) refreshes whole.

**Do not write `CASCADE` on that delete.** H2 accepts a bare identifier as a table alias in
`DELETE FROM t <alias>`, so `DELETE FROM target CASCADE` parses, silently aliases the table to
`CASCADE`, and cascades nothing. On H2 2.4.240 `TRUNCATE TABLE t CASCADE` is a syntax error, and
plain `TRUNCATE TABLE t` is refused outright for a table any foreign key references. A plain
`DELETE` is correct and sufficient here, nothing holding a foreign key onto a materialized target.
Worth recording rather than rediscovering: the keyword reads as protection and supplies none.

**Ordering is deliberately out of scope, and safe to leave out for these two.** A target populated
from a view that reads *another* target must be refreshed after it, and `meta_materialize` as
specified records no such edge. It does not bite here: neither registered view appears in the other's
dependency closure, and both closures contain base tables only, zero views, so the two rows can be
materialized in any order. The general case is R746, strictly additive to this mechanism.

The readers that want the fast path reference the target; readers that want on-demand evaluation
reference the view, and get the same rows either way because the target is populated by
`INSERT INTO <target> SELECT * FROM <view>`. That equality is by construction and a gate should pin
it rather than leave it as an argument. What keeps the two names from becoming two readings of one
population is that a reader picking the slow name is a performance bug and nothing else, and the
multiplicity check below is what finds it.

`fact-model.adoc` sanctions the mechanism and the store already has four written `intent_` tables
behind three writers: `intent_type_domain` (`ClassificationDomainCapture.derive`),
`intent_type_backing_class` (`TypeBackingRows.derive`), and `intent_input_occurrence_path` with its
step sibling (`InputOccurrencePaths.derive`). `TypeBackingClassRows.write` is the delete-then-insert
shape to copy.

What `fact-model.adoc` does not sanction is the *justification*, which is the reviewable part and has
its own section below.

Measured, with `intent_argmapping_pair` and `intent_spelled_table` reduced, on top of R733's two
changes. The reactor column is the part to read first, and it is why the deliverables below put the
fixture work in the same step as the registrations:

| | Defect-view query | `FixtureWarningsGateTest` | `GeneratorDeterminismTest` | Full reactor |
|---|---|---|---|---|
| trunk | | 56.8s | 229.0s | green |
| R733's two changes | 24.5s | 23.5s | about 93s | green, twice |
| plus these two reductions | **0.72s** | **6.85s** | **20.66s** | **red: 13 classes** |

The last row's timings are real and the classes that produced them passed. The reactor was not green,
because the prototype registered two relations and did nothing about the fixture, which is the whole
subject of the next section. Nobody should quote 20.66s as a landed number until that is done.

The profile afterwards is healthy rather than merely smaller: H2 falls from 97% of a run to 67%, the
four store reads from 88% to about 48%, and the largest single remaining item is the store's own DDL
boot at 9%. No dominant pathology is left.

**Route B: flatten the six-arm union into one pass.** (Re-priced after the reductions: the five
`binding_leaf` arms now contribute 210 of the defect view's 765 instantiations, so flattening saves
4 x 42 = 168, or 22%. The percentage barely moved but the base did, and 22% of a store read that is
no longer the dominant cost of a four-second run is a simplification's worth of value rather than a
performance item's. Still worth doing on its own merits, and no longer worth sequencing for speed.) The defect view's five `binding_leaf`-driven
arms differ only in predicate and verdict literal, so one pass with a `CASE` verdict and a left join
to the segment relation would collapse them. It changes no read semantics and needs no freshness
reasoning, which makes it tempting. The decomposition table prices it: at most 4 × 150 = 600 of 2066
instantiations, about 29%, leaving the 1038 the two `key_column_candidate` references contribute.
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

**Give the fixture the derivation boundary, once, and the tests never mention materialization.**
This is the one part of the design with no evidence behind it, and a reviewer should treat it as the
item's main risk: the prototype demonstrated the breakage and did not fix it, so "one line per class"
is a reading of the code rather than a thing anyone has done. The
seam is thirty-seven read sites across the thirteen classes. Route those through `SeededStore`, which
calls the same `graphitron-model` materializer every other caller calls, and no test names a
materialized relation again. The sizing is not "one line each": only five classes funnel through a
uniform `rows`/`only`/`rowFor` shape, the rest use per-relation helper names (`backings` and
`allBackings` in `TypeBackingTest`, `rules` and `rulesFor` and `allRules` in `SeparateFetchRuleTest`,
`inferred` and `nodeTypes` in `NodeTypeTest`), several carry four or more helpers, and
`ColumnMatchClaimTest` reads inline inside test bodies with no helper at all. The design conclusion
is unchanged and the count is what an implementer plans against. Registering a fourth or a fifth
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

Three places say the narrow version today and all three have to move, which is convenient, because
it means the doctrine is written down rather than assumed.

* The DDL header's cadence paragraph says a post-capture family has its own writer on its own
  cadence, and says nothing about why a family would be post-capture in the first place. The
  registry gives that answer a home.
* The `intent_` family charter in `meta_family` says the residents are "views plus the materialized
  derivations **whose table comments own why they cannot be views**". Under a registry the reason is
  no longer per-table prose and no longer "cannot": it is a registration, and the charter should say
  so.
* The `meta_` family charter in `meta_family` says the family is the roster, the placement
  exemptions and the census, "authored as constant rows stated as views". The registry is a fourth
  resident and it is a table, so both clauses go stale the moment it lands. The charter has no
  enforcer, which is exactly why it has to move in the same change rather than wait: nothing would
  fail, and the generated schema reference would render the schema describing itself wrongly. What
  the two forms share is the property the sentence was reaching for, that the rows are versioned
  with the DDL and cannot be refreshed apart from it, so that is what it should say.

The incumbents' shape also differs, and the registry is what reconciles them rather than leaving two
variants side by side. This is four relations and three writers, not one: `intent_type_domain`,
`intent_type_backing_class`, `intent_input_occurrence_path` and its step sibling are all computed in
Java and written, with no view stating the rule, so there is nothing for a registry to point at.
Whether they stay outside the mechanism, keeping their Java derivations and their own comments, or
acquire views and join the registry, is a decision this item should take explicitly rather than leave
to whoever notices the asymmetry next. `intent_type_domain` answers itself: its writer was rewritten
out from under this item while it sat in Spec, so it should not be rewritten here again.

### Gates the change has to clear, none of them optional

Beyond the compile, and the item should not discover them one build at a time.

* **`FactSchemaGateTest`, comments.** Every relation and every column in `PUBLIC` must carry a
  non-null `REMARKS`. Each materialized target is a new relation and arrives uncommented.
* **`FactSchemaGateTest`, documentation home.** Every relation must resolve to exactly one family
  page through `meta_relation_family`, or carry an exemption row in `meta_prefixless_relation`, and
  no relation may match two family prefixes. An `intent_`-prefixed rule view should house itself,
  but that is a prediction and the gate is the authority.
* **`FactCaptureAgreementTest`, arm registration.** `everyRelationIsRegistered` is a total,
  bidirectional census: every relation needs an arm and every arm must name a live relation. A
  registration adds *two* relations to it, the `_live` view and the target, and `meta_materialize`
  is a third. The `DERIVED` arm's javadoc enumerates "views, and the materialized capture-cadence
  derivation `intent_type_domain`", which covers the `_live` views under "views" and the targets
  under the second clause but not an authored constant-row registry table, so the sentence widens
  in two directions rather than one.
* **`StoreRefresh.wholesale()`.** Written in exemption polarity, so it empties any base relation
  nobody excluded. The registry's rows are authored DDL that no run rewrites, so a warm capture
  would clear them and nothing would put them back. The `meta_` family needs an exemption whose
  reason is authorship rather than cadence. The family's three views were never at risk, being
  views; the register is the first `meta_` base table and the first to reach this code.
* **The boot's commit.** The DDL file gains its first `INSERT`. H2 commits a schema statement
  implicitly and ordinary DML not at all, so a file-backed store would open, create the schema,
  seed the register, close, and reopen to find the register empty.
* **`CommentRenderabilityGateTest` and `SchemaReferencePagesTest`.** The comment text has to render,
  not merely exist.
* **`FactSchemaGateTest.everyGraphKeyedRelationReachesTheAnchor`.** Every non-view relation carrying
  `graph_name` needs a foreign-key path to `store_graph` that itself threads `graph_name`. Both
  registered views carry `graph_name`, so both targets do, so both need that key. This is the gate the
  prototype's bare `CREATE TABLE` would have tripped had the run reached it.
* **`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`**, if a target carries a primary
  key. Whether it should is an unstated decision in its own right: `INSERT INTO target SELECT * FROM
  view` needs the view's rows to be uniquely keyed for a primary key to exist at all, and neither
  registered view has been checked for that. Check before deciding, and record the answer either way.

One gate that looks like it should fail and does not, worth stating so nobody re-litigates it:
`aRunWritesOnlyUnderItsOwnGraph` compares a sorted content snapshot of a sibling graph's partition
across a run. A per-graph refresh leaves that partition untouched, so it passes by construction; even
a whole-relation refresh would have passed it, being deterministic, which is precisely why the
argument for partitioning is the stratum's own invariant rather than this gate.

The registry earns gates of its own, and they are the reason to prefer it over per-relation writers.
Each is a closure of authored intent against observed schema, the shape `meta_relation_family`
already uses:

* every registered view exists, and every target table exists with a column list matching its view;
* every target's rows equal its view's rows on a settled store, which is the equality the whole
  design rests on and the one thing no amount of prose can substitute for;
* nothing outside the registry is a materialized `intent_` relation, which is what stops the next
  bespoke writer from appearing beside the mechanism instead of inside it.

### Also unsettled

* **Which relations to reduce.** Two sufficed for the measured case; the multiplicity table names
  five more above eight. Reduce on measured need, and record what was left unreduced as a decision
  rather than an omission.
* **The gate's ceiling**, and whether the multiplicity check reports or fails on first landing.
* **The `_live` suffix.** `meta_materialize` states the pair, so no convention is forced, but the
  view is the relation being renamed and it should read as the on-demand one. `_live` is the
  proposal; anything that does not read as "stale" or "old" works. Whether a gate gets to assume the
  convention is a separate question, and the answer is probably not, the registry being the authority.
* **Readers that arrive without a capture.** The language server and the MCP server open the store
  directly. They are already callers of the materializer in this design, but "call it on open" and
  "assume a capture ran" are different contracts, and the second one needs the "absence needs a
  stated meaning" treatment if it is chosen.
* **Warm and shared stores.** The persisted store is shared across a workspace's modules, which
  build in parallel under `-T 1C`. Partition-by-graph makes concurrent refresh safe on the face of
  it, but a warm start that skips capture because nothing changed must leave targets valid rather
  than empty, and `WarmStartRefreshTest` and `PersistentStoreTest` are where that gets pinned.
* **Whether the four Java-derived tables join the mechanism**, per the doctrinal section above.
* **Whether a target carries a primary key**, which decides whether
  `everyRelationLeadsWithItsPartitionDimension` applies and requires checking that each registered
  view's rows are uniquely keyed.

### What landed, against what was planned

The run count is the one place the plan and the outcome differ, and the difference is deliberate.
Deliverable 4 takes the class to three runs by sharing one canonical tree; deliverable 5 adds the
clean-removal case, which needs a run of its own. Together they leave the count at four and take the
clauses covered from two to three, which is the trade the item argued for when it said the shared
tree is what makes the third case affordable. The class is 18.13s rather than the 15.46s it would be
without the new case, against 229.0s on trunk, and the javadoc no longer claims coverage the class
does not have.

Deliverables 6 and 7 were both re-measured rather than done, which is what this item asked of them.
Their sections above carry the numbers and the reasoning.

### Deliverables, in the order the numbers argue for

Steps 1 and 2 are the paving; 3 onward are what the paving makes cheap. The simplification table near
the top of this item says which of these are the lesser version of something and where the fuller
version lives.

1. **The mechanism**: `meta_materialize`, the `graphitron-model` materializer, its call from the
   capture pass and from `SeededStore`, the registry's own gates, and the two doctrine paragraphs.
   Lands with zero rows registered and changes no timing, which is what makes it reviewable on its
   design rather than on its numbers.
2. **The two registrations**, `intent_argmapping_pair` and `intent_spelled_table`, plus the LSP and
   MCP call sites and the fixture's derivation boundary. Each registration is a view rename, a
   `CREATE TABLE` with the key the anchor gate requires, and one registry row; not two rows of
   authored data, which an earlier draft of this item claimed. This is the 229.0s to 20.66s, and the
   fixture work belongs in this step rather than after it, since without it the step lands red.
3. **The multiplicity check**, reporting only, so the third and fourth registrations are chosen from
   data rather than from this item's table.
4. **The run-count reduction**, four runs to three. Worth doing because a test should not perform
   work its contract does not need, and worth doing *after* the above, because by then it saves about
   five seconds rather than about sixty.
5. **The clean-removal coverage decision**, and the javadoc correction it implies either way.
6. **Route B**, flattening the defect view's five `binding_leaf` arms into one `CASE` pass. Priced at
   29% of the instantiations above and independent of everything else here, so it can land whenever;
   listed because an item that calls it worthwhile and then never schedules it is how a good idea
   goes missing.
7. **The duplicate-read merge and the `Files.mismatch` cleanup**, both re-measured against the shape
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

**Re-measured after the reductions landed, and the answer is not to do it.** The same probe, a
redundant third read of the view added to `detect`, now costs **0.49s per run** against the 8.1s it
cost before: the class goes from 18.13s to 20.10s over four runs. The view is 284 instantiations now
rather than the 518 it reached through `intent_argmapping_pair`, and the run it sits in is about four
seconds rather than twenty-one. So merging the two reads would buy roughly half a second per run, and
would pay for it with a wide intermediate row that two classes in two files pick apart in Java. That
is a worse trade than the duplicate read, and the duplicate stays. The measurement is the deliverable
here, not the merge.

The `Files.mismatch` half of the same deliverable did land, on the grounds the item gives for it: it
is a failure-message change and not a performance one.

### What the changes come to together

Every row measured on one 4 vCPU sandbox. "Class green" means this class's own two tests passed;
"reactor" is the state of a full `mvn install -Plocal-db`, which is the column that decides whether a
number is quotable as landed.

| Configuration | Runs | Class total | Per run | Reactor |
|---|---|---|---|---|
| trunk | 4 | 229.0s | 57.3s | green |
| run reduction alone | 3 | 167.3s | 55.8s | not run; the change is test-local |
| R733's two changes alone | 4 | about 93s, derived | 23.3s | green, twice |
| R733 + run reduction | 3 | 62.91s | 21.0s | green |
| R733 + the two reductions | 4 | **20.66s** | 5.2s | **red: 13 classes** |
| all three | 3 | about 16s, projected | 5.2s | not run |
| landed: the two registrations, on trunk | 4 | **15.46s** | 3.9s | **green** |
| landed: plus the clean-removal case | 4 | **18.13s** | 4.5s | **green** |

The landed row is the mechanism and both registrations as they shipped, with the fixture work in the
same step, measured on a full green `mvn install -Plocal-db`. It beats the prototype's 20.66s because
trunk moved underneath this item while it sat in Spec. `FixtureWarningsGateTest` fell from 56.8s to
2.63s in the same build, which is the same reads in a different consumer and the better evidence that
this was never one test's problem.

The projected row above it is arithmetic rather than a measurement, and worth stating plainly: once a
run costs about four seconds instead of 57, removing the fourth run saves about five seconds rather than about sixty.
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
