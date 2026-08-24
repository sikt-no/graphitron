---
name: store-performance
description: Diagnose a slow fact-store relation as a database question: start from the store a failing build already wrote, time each relation in isolation, bisect the body of whatever is expensive with cheap children, run a same-fixture control before believing a hypothesis, then pick a lever. Use when a derived view or a store read is slow, a build got slower after a view landed, a query hangs or never returns, or you are about to reach for a thread dump, a profiler, or a reactor wall-clock comparison. Refuses those three as evidence, and does not tune generated SQL, JVM flags, or Maven build time.
---

# store-performance

The fact store is a relational database, so slowness in a derived relation is a database question and
it is diagnosed inside the database. This skill is the order of operations for that. The rules it
leans on live on `docs/architecture/explanation/fact-model.adoc` under "Derived reads are views, not
stored facts"; that page is the source and this is the procedure that gets you there in the right
order.

**The failure this prevents** is reaching for Java instincts at a relational problem: a thread dump
of a killed build read as a plan, a profiler frame taken for a query, two reactor runs on one machine
believed to differ, a bespoke Java measurement program written before anything has been timed. One
more belongs on that list because it wears database clothing and is therefore harder to catch: a
scan count read as a cost.

## 1. Posture first

Four conclusions were taken back on one investigation of this store, each caught before it cost a
wrong line of code and none of them caught cheaply. Two were readings of thread dumps of a killed build:
that a recursion was being re-entered once per outer row, when isolating the recursive CTE put it at
about two evaluations in total, and that a family of recursive reference-target views was the
expensive term, when timing each relation on its own said the term was somewhere else entirely. Two
were differences read off reactor runs that were not comparable: one module run came in at 1:10 and
was believed, where three repeats of the same code put that module at 2:39, 2:43 and 2:48, and the
improvement it was taken to show was then claimed twice in figures computed against it.

A later investigation on a consumer schema added a fifth, and it is the one this skill previously
invited: the largest `scanCount` in a plan was taken for the cost, an index was proposed to prune
it, and the index changed nothing. Scanning a few hundred thousand rows of a small table is a
fraction of a second of work. The count was real and the reading was wrong.

So, in order of what counts as evidence:

- **A per-relation timing against a populated store reproduces.** It is the measurement to take
  first and the only one every later step rests on.
- **`EXPLAIN ANALYZE` says which relations the plan touches and how many rows it scanned at each
  node. It does not say where the time went.** H2 attaches no per-node timing, so a plan tells you
  shape and it never ranks cost. A profiler frame names the call site rather than the plan, so no
  sampling depth answers that question either. If you do reach for JFR anyway, know that its default
  64-frame depth is shallower than the H2 view stack, so every sample truncates inside `org.h2` and
  the profile reads as a dead end that is really a truncation.
- **A statement that never returns names itself, for free.** This is the cheapest evidence in the
  whole procedure and it arrives before any measurement: interrupt the build and the failure carries
  the SQL it was executing, or read the last `Executing query` line in jOOQ's DEBUG log. A hang is
  one statement, not a slow succession of them, and knowing which statement collapses the search
  before it starts. Any "database is open in exclusive mode" message trailing such a failure is the
  interrupt's own shutdown path, not the fault.
- **Reactor wall-clock is not evidence.** Five runs of one module across code differing by at most
  one registration spanned 1:10 to 2:48 on one machine, which is more spread than any change you are
  likely to be measuring. Do not quote a reactor pair, and be suspicious of any figure in an older
  note that was computed from one.
- **A thread dump of a killed build is a guess about a plan.** It says which frames are on a stack,
  which is compatible with several plans, and the two readings above picked the wrong one twice.
- **A bespoke Java measurement program is the last resort for *deriving a conclusion*, and the
  first-choice *instrument* once you have a store on disk.** The distinction matters: writing Java to
  invent a metric is the mistake, and a dozen lines of JDBC that time a statement against a real
  store file is the cheapest harness there is. See step 2.

## 2. Get a store

**If a real build is failing, its store is already on disk and that is the store to use.** This is
the fastest path to evidence in the whole procedure and it needs no probe, no fixture and no reactor
build. A run persists the store under the per-user cache home that `AbstractRewriteMojo`'s store-home
resolver computes, one directory per workspace and a compatibility-stamped subdirectory under it;
`mvn clean` does not remove it. Read that resolver for the current path rather than trusting a path
written here, then:

- Stop the build first. A running build holds the file in exclusive mode, so a second connection is
  refused while it lives.
- Copy the file out and work on the copy, so nothing you do perturbs a consumer's cache. Snapshot
  controls in step 5 write tables, and they should not write them into someone's real store.
- Query it with the H2 jar at the version the root pom pins, driving it from a single-file Java
  program over JDBC. Two mechanical traps: H2 rejects a relative database path, so pass an absolute
  one; and `org.h2.tools.Shell` renders results as a formatted table and truncates a long value, so
  it is the wrong tool for reading a plan, for the same reason a `Result` is (below).

This store is better than anything a fixture can build, and not only because it is free. It carries
the population the failure actually happened on, including the families a hand-built capture is
most likely to omit.

**Population fidelity is where a reproduction is usually lost.** A relation's cost is a function of
its inputs' cardinality, so a store missing a family is a store that cannot reproduce a cost that
multiplies through it. Two specific ways this bites:

- **The classpath census is part of the population.** `CapturedStore.ofCatalog` takes the census as
  an argument and every arm defaults it to empty, so a probe written the obvious way has no
  `jvm_` rows and no `store_graph_source` rows beyond the trivial. A consumer with many classpath
  entries has a large source membership, and a join that fans out across it is invisible in a probe
  that has one. If your reproduction does not reproduce, suspect this first.
- **A seeded store of a dozen rows will tell you nothing about cost**, because every shape on the
  fact-model page is cheap at that size. And a derived read against a seeded store returns nothing
  until `SeededStore.derive` has run, materialized targets holding rows only once something fills
  them.

When you must build a store rather than borrow one, the harness ladder is documented per subject and
the authority is the "Where a store-backed test gets its store" table in
`docs/architecture/how-to/testing.adoc`. Read that table and take the row your subject sits on; this
skill deliberately does not restate it, because a partial ladder in a second document is a wrong
answer the moment a harness is added. `CapturedStore.ofCatalog` captures a schema document against a
generated jOOQ catalog; where the rows only a pipeline run writes are what your relation reads,
`BuiltStore` is the row instead. `StoreFixtureGuardTest` fails the build on a test that opens a
`GraphitronModelStore` itself, so take the store from a harness.

A probe over that population, in `graphitron`'s own test tree, is about this much:

```java
String sdl = Files.readString(Path.of("..", "graphitron-sakila-example",
    "src", "main", "resources", "graphql", "schema.graphqls"));
try (var store = CapturedStore.ofCatalog(tmp, sdl, new JooqCatalog(DEFAULT_JOOQ_PACKAGE))) {
    var dsl = store.dsl();
    long t = System.nanoTime();
    Object rows = dsl.resultQuery("SELECT count(*) FROM " + relation).fetchOne(0);
    System.out.println(relation + " rows=" + rows + " ms=" + (System.nanoTime() - t) / 1_000_000);
}
```

The capture itself is seconds, which is the floor of every run and not part of what you are
measuring, so time relations inside one capture rather than one per test method.

```bash
mvn test -pl :graphitron -Dtest=YourProbeTest -DexcludedGroups=execution
```

Surefire swallows stdout into `target/surefire-reports/<FQN>-output.txt` and interleaves jOOQ's
DEBUG logging with it, so prefix every line you print with a token you can grep for. Expect to wait:
if the relation is one of the expensive ones, a single probe method is minutes, and a JUnit timeout
of your own would just hide the number you came for. Do not read a long build's output through
`tail`, which buffers until the pipeline ends; redirect to a file and read the file.

Sanity-check every Maven command against the local build conventions in `CLAUDE.md` before you lean
on a timing: a catalog-jar profile that a session needs and you omitted produces unrelated
resolution failures rather than a slow query, and a `-pl` build over a dirty upstream produces stale
results silently.

## 3. Measure relationally

Time relations in isolation, one at a time, against that population. A relation's own cost and the
cost of a reader that names it are different numbers, and the second is not informative until you
have the first.

Rank suspects statically before you time them. A `report-inline-multiplicity` subcommand on
`roadmap-tool` has served this; check that it is present on your branch before planning around it,
and if it is not, thirty lines over the schema DDL reproduce the ranking: collect each view's
`FROM`/`JOIN` references with `COMMENT ON` statements stripped so prose does not pollute the count,
then multiply the counts down the view tree from the relations nothing else references. Read what
such a ranking is either way: a count of textual references multiplied down the tree. It ranks
breadth. Breadth is not cost, and the fact-model page says so with the numbers, so the ranking names
suspects and your timings price them. Expect it to name the wrong ones sometimes; that is what
pricing is for.

Then get the plan, understanding what it can and cannot tell you.

```java
if (System.getenv("STORE_EXPLAIN") != null) {
    String plan = dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))
        .fetchOne(0, String.class);
    plan.lines().filter(l -> l.contains("scanCount")).forEach(System.out::println);
}
```

Take the plan as a string. Printing the `Result` instead renders it as a formatted table and
truncates the one column you came for to about fifty characters, which looks like an empty plan
rather than like a truncation; `org.h2.tools.Shell` does the same thing for the same reason. A plan of
any size is worth reducing on the way out: over a deep derivation it runs to hundreds of scan nodes
and more than a megabyte of text, so filter, sort or count the `scanCount` lines rather than reading
it whole.

Two constraints on this step, both of which have cost time:

- **`EXPLAIN ANALYZE` executes the statement.** So the query you most want a plan for, the one that
  never returns, is exactly the one you cannot get a plan for. Work up from children that do
  terminate, or from the bisection below, and use plain `EXPLAIN` when you only need the shape.
- **A `scanCount` is a row count, not a cost.** H2 gives no per-node timing, so the largest scan
  count in a plan is not the answer to "where did the time go" and must never be reported as if it
  were. Use scan counts for what they are: evidence of shape. One enormous count on a single node
  says a relation is being re-evaluated per driving row; a few hundred nodes each carrying the same
  middling count says inlining, the same relation expanded once per naming down the tree. Then price
  the shape by timing, and prefer arithmetic that closes: if driving cardinalities multiply out to
  roughly the count you see, you understand the plan, and if they do not, you do not yet. Run it
  against the populated store rather than a seeded one; a plan over a dozen rows is a different plan.

### When the relation is expensive and every child is cheap: bisect the body

Step 5's cheapest control will often tell you that a relation whose children all answer in
milliseconds takes minutes on its own. That conclusion is where this procedure used to stop and
where the lever hierarchy is least helpful, because "its cost is the expansion" names no line of SQL.
Bisect the body instead, and do it before trying any rewrite:

1. **Time each CTE of the relation on its own.** Lift the `WITH` block verbatim and count rows out of
   each name in turn. Cheap CTEs plus an expensive whole means the cost is in how they are combined.
2. **Time each arm of a top-level `UNION`/`UNION ALL` separately**, wrapping each in
   `SELECT COUNT(*) FROM ( <arm> ) x (<the relation's own column list>)`. H2 requires those explicit
   aliases where an arm's select list has unnamed or repeated columns, and the relation's declared
   column list is exactly the alias list you need.
3. **Read the result as a localisation, not a diagnosis.** One arm carrying essentially all of the
   time, and returning few rows while doing it, is the SQL to study. The others are noise you can now
   ignore.

This turns "somewhere in a two-hundred-line view" into one arm in a single run, and it composes with
the snapshot control: substituting a table for one inlined name inside the guilty arm prices exactly
what a registration of that name would buy.

## 4. Know the evaluation model

Go and read the rules before forming a hypothesis, because every one of them was paid for once
already and each has a shape you can recognise in a plan:

- `docs/architecture/explanation/fact-model.adoc`, under "Derived reads are views, not stored
  facts", carries the general forms with their measurements: view inlining with no
  common-subexpression elimination and multiplicities compounding down a tree, a non-recursive
  `WITH` inlined exactly like a view, a derived relation joined on an expression rather than a
  column evaluated once per driving row, a recursive term re-evaluating its step's input once per
  accumulated row, a view carrying a window function or a recursive term unprunable by an outside
  predicate, and the closing rule that what makes a relation expensive is being a view something
  reads many times rather than how a reader spells the read. The same page's materialized-view
  ruling says why `CREATE MATERIALIZED VIEW` is not available to you.
- `meta_materialize`'s `reason` column, one row per registration, carries the per-relation
  arithmetic for the relations that already earned one: which shape made each expensive, what one
  evaluation cost, and which rewrites were tried and measured first. Read the row beside the general
  form, not instead of it.
- A relation's own `COMMENT ON` carries its cost warning where it has one, because the cost is
  invisible at the call site. If the relation you are holding has one, it is the most specific thing
  written about your problem anywhere.

**A recorded measurement is evidence about the schema it was measured on, and general forms
generalise where measurements do not.** This is not a caveat, it is a live hazard: a reason row
naming the expensive term of one derivation has been carried forward onto a consumer schema where the
branch it blamed was empty and the term was elsewhere entirely, costing two hypotheses before the
timings contradicted it. Read a recorded number as "this is what the shape did there", re-measure the
shape here, and if your measurement disagrees with a stored reason, the stored reason needs
correcting rather than explaining away. Say so where it lives, because a wrong recorded measurement
is worse than none.

Do not copy a number out of any of those into a new document. They are gated where they are, and a
copy drifts unobserved.

## 5. Controls before conclusions

Every hypothesis gets a control on the same fixture that would refute it. This is the step the
retracted conclusions skipped, and two of three controls on the investigation that introduced the
discipline refuted the reading taken first. On the later consumer-schema investigation the count was
worse: most hypotheses died, including every one that came from reading a plan's shape without
pricing it.

The controls that keep paying:

- **Time every child in isolation.** The cheapest control there is, and it refutes the hypothesis
  you are most likely to start from. A relation whose every child answers in milliseconds and which
  itself takes minutes has no expensive child: its cost is the expansion, and no amount of
  registering something underneath it will help. When it lands this way, go to the bisection in
  step 3 rather than to a lever.
- **Snapshot the suspect into a table and re-run.** If the cost does not move, the suspect is not
  the term. This is also the cheapest preview of what a registration would buy, and the preview is
  quantitative: the snapshot's own build time is the refresh you would be adding, and the re-run is
  the read you would be buying. **Check the driving rows are not zero.** A control whose join
  produces no rows has multiplied nothing and measures one evaluation, which looks like a result and
  is not one.
- **Compare the materialized target against its source view in both directions.** After a
  registration, `source EXCEPT target` and `target EXCEPT source` must both be empty. A registration
  is supposed to change cost and nothing else, and this is the two-line proof of it.
- **Join on the bare column.** Replaces an expression-shaped key with a column one, which
  distinguishes an expression key from a row count.
- **Materialize in a `WITH` and re-run.** Expected to change nothing, and it is worth running
  precisely for that: a `WITH` that appears to fix something means your model of the query is wrong
  somewhere else.
- **Remove the suspect from the statement entirely.** The floor. A query that is still slow without
  it was never about it.

Report a control that refuted you, and report it where the next reader will meet the hypothesis
rather than only in conversation. A note that records only the surviving hypothesis leaves the next
reader to re-run the same dead ends; the `reason` column of a registration is the right home for the
rewrites that were tried and lost.

## 6. The lever hierarchy

Once you know what is expensive and why, the levers are ordered, and the order is on the page beside
the rules: **a captured fact, then a `meta_materialize` registration, then a rewrite.** A captured
fact has no refresh to pay for at all. A registration says the rule is right as a view and only too
slow to evaluate per naming, and it has a trade to win, a refresh against the re-evaluations it
avoids. A rewrite is last because it usually changes nothing the planner cares about.

Read the page for the argument. What this step adds is the order in which to reach, and two
warnings. The first: the rung that feels most like engineering is the one that pays least often. The
second is stronger than "usually changes nothing", because a rewrite is not merely a coin flip:
**measure every rewrite against the shape it replaces, and expect regressions.** On one arm, widening
an inner relation to carry its parent's columns and so drop a join, and separately reversing which
relation the arm drove from, both measured several times worse than the untouched original, while
respelling a hand-rolled null-safe comparison as `IS NOT DISTINCT FROM` measured as no change at all.
Two of those looked like obvious wins on the page. A rewrite you have not timed is a guess with
better handwriting.

## 7. Choose what to materialize, and push it down

A registration is a shared investment, not a local patch. The page states the rule and carries the
measured case; the test to apply is:

1. **Count the candidate's readers.** How many relations name it, and how many of them anything
   actually exercises today. A candidate with one reader that nothing exercises yet has every
   refresh buying nothing, and that registration belongs in the increment that adds the reader.
   Count Java readers as well as SQL ones, and check whether a reader is on the path that is actually
   slow: a relation with no reader at all can be pathological in isolation and contribute nothing to
   the failure you are chasing, which makes it the most tempting wrong answer available.
2. **Price its refresh.** The source view is evaluated once per refresh, whole or per graph, so the
   refresh is a cost you are adding and not only one you are avoiding. State the number in the
   `reason`, and state it as the trade it is when it is the most expensive refresh in the registry.
3. **Prefer the deepest relation whose materialization removes re-evaluation for more readers than
   the one you started from.** Materialize the relation the cost multiplies *through*, not the one
   that looked slow from where you stood. Stopping short of that depth is measurably not a fix,
   which the page's counter-case shows.
4. **Weigh the cheapest registration against the one you can land.** The deepest candidate is
   sometimes a CTE local to one view rather than a named relation, and registering it means promoting
   it to a first-class relation with a name and comments first. When the shallower registration
   removes the failure and the deeper one only makes the refresh cheaper, landing the first and
   recording the second in the `reason` as measured follow-up is a defensible split. Say which one
   you did and why, with both numbers.

Register it by adding a row to `meta_materialize` in the schema DDL, whose `reason` column is
required and is where the case you just made belongs, in this relation's own terms and with its own
arithmetic. Refresh ordering is derived from the stored view definitions and is not something you
state.

Registering an existing named view is the cheap shape and the one the convention is built for: the
view keeps its text under a `_live` name, a table takes the canonical name every reader already
spells, and no reader changes. Doing that touches more than the DDL, and the gates will tell you so
one build at a time, so expect all of it in one pass: the `_live` view and its columns need comments
in the established form, the canonical table inherits the original relation's comment plus the
standard materialization note, and a new `_live` view has to be added to the agreement-source list in
`FactCaptureAgreementTest` alongside the existing registrations. That last one fails a full build and
not a scoped one, so run the verification build before believing you are done.

## Citation policy

This document cites doctrine pages, class names and relation names that a reader can find, and it
restates no measured per-relation number: the page and the `reason` rows are gated surfaces and this
one is scanned by nothing, so a copy here is a copy that rots in silence. Two kinds of figure are
deliberate exceptions, and they share a justification: their whole content is that a number of that
kind means nothing, so there is no surface where they would ever be checked and nothing to drift
against. The reactor-spread figures in step 1 are one. The scan-count reading in step 1 and the
rewrite regressions in step 6 are the other, and both are stated in relative terms for the same
reason.
