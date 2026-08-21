---
name: store-performance
description: Diagnose a slow fact-store relation as a database question: time each relation in isolation against a populated store, read EXPLAIN ANALYZE scan counts, run a same-fixture control before believing a hypothesis, then pick a lever. Use when a derived view or a store read is slow, a build got slower after a view landed, a query hangs or times out, or you are about to reach for a thread dump, a profiler, or a reactor wall-clock comparison. Refuses those three as evidence, and does not tune generated SQL, JVM flags, or Maven build time.
---

# store-performance

The fact store is a relational database, so slowness in a derived relation is a database question and
it is diagnosed inside the database. This skill is the order of operations for that. The rules it
leans on live on `docs/architecture/explanation/fact-model.adoc` under "Derived reads are views, not
stored facts"; that page is the source and this is the procedure that gets you there in the right
order.

**The failure this prevents** is reaching for Java instincts at a relational problem: a thread dump
of a killed build read as a plan, a profiler frame taken for a query, two reactor runs on one machine
believed to differ, a bespoke Java measurement program written before anything has been timed.

## 1. Posture first

Four conclusions were taken back on one investigation of this store, each caught before it cost a
wrong line of code and none of them caught cheaply. Two were readings of thread dumps of a killed build:
that a recursion was being re-entered once per outer row, when isolating the recursive CTE put it at
about two evaluations in total, and that a family of recursive reference-target views was the
expensive term, when timing each relation on its own said the term was somewhere else entirely. Two
were differences read off reactor runs that were not comparable: one module run came in at 1:10 and
was believed, where three repeats of the same code put that module at 2:39, 2:43 and 2:48, and the
improvement it was taken to show was then claimed twice in figures computed against it.

So, in order of what counts as evidence:

- **A per-relation timing against a populated store reproduces.** It is the measurement to take
  first and the only one every later step rests on.
- **`EXPLAIN ANALYZE` says which relation is being expanded and how often.** A profiler frame names
  the call site, not the plan, so no sampling depth answers that question. If you do reach for JFR
  anyway, know that its default 64-frame depth is shallower than the H2 view stack, so every sample
  truncates inside `org.h2` and the profile reads as a dead end that is really a truncation.
- **Reactor wall-clock is not evidence.** Five runs of one module across code differing by at most
  one registration spanned 1:10 to 2:48 on one machine, which is more spread than any change you are
  likely to be measuring. Do not quote a reactor pair, and be suspicious of any figure in an older
  note that was computed from one.
- **A thread dump of a killed build is a guess about a plan.** It says which frames are on a stack,
  which is compatible with several plans, and the two readings above picked the wrong one twice.
- **A bespoke Java measurement program is the last resort, not the first move.** Everything up to
  and including scan counts is reachable from a test method over a harness store.

## 2. Get a store

The harness ladder is documented per subject, and the authority is the "Where a store-backed test
gets its store" table in `docs/architecture/how-to/testing.adoc`. Read that table and take the row
your subject sits on; this skill deliberately does not restate it, because a partial ladder in a
second document is a wrong answer the moment a harness is added.

Two facts that table does not carry, because they are about debugging rather than about testing:

- **For a population worth timing against, you want a real capture.** `CapturedStore.ofCatalog` in
  `graphitron`'s test tree captures a schema document against a generated jOOQ catalog, and the
  sakila example's own schema against the sakila catalog is the realistic population every measured
  rule on the page was established against. Where the rows only a pipeline run writes are what your
  relation reads, `BuiltStore` is the row instead. A seeded store of a dozen rows will tell you
  nothing about cost: every shape on the page is cheap at that size.
- **A derived read against a seeded store returns nothing until `SeededStore.derive` has run.**
  Materialized targets hold rows only once something fills them, and that call is the stratum that
  fills them.

Standing rules that bite here: `StoreFixtureGuardTest` fails the build on a test that opens a
`GraphitronModelStore` itself, so take the store from a harness. And every Maven command carries
`-Plocal-db`, or the jOOQ catalog jar is silently emptied and you will spend the session reading
unrelated resolution failures.

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

The capture itself is around eight seconds, which is the floor of every run and not part of what you
are measuring, so time relations inside one capture rather than one per test method.

```bash
mvn test -pl :graphitron -Plocal-db -Dtest=YourProbeTest -DexcludedGroups=execution
```

Surefire swallows stdout into `target/surefire-reports/<FQN>-output.txt` and interleaves jOOQ's
DEBUG logging with it, so prefix every line you print with a token you can grep for. Expect to wait:
if the relation is one of the expensive ones, a single probe method is minutes, and a JUnit timeout
of your own would just hide the number you came for.

## 3. Measure relationally

Time relations in isolation, one at a time, against that population. A relation's own cost and the
cost of a reader that names it are different numbers, and the second is not informative until you
have the first.

Rank suspects statically before you time them:

```bash
mvn -pl roadmap-tool exec:java -q -Dexec.args='report-inline-multiplicity .'
```

That prints, per derived relation, how many relation instantiations one read of it expands to, and
then the heaviest relation's direct children by contribution, which is where to look next. Read what
it is: a count of textual references multiplied down the view tree. It ranks breadth. Breadth is not
cost, and the page says so with the numbers, so the ranking names suspects and your timings price
them.

Then get the plan. A SQL console against the live dev-loop store is the better surface for this step
wherever the dev loop offers one, since it needs no probe and no rebuild; check
`docs/architecture/how-to/dev-loop-internals.adoc` first, and fall back to the recipe below. Beside
the read under test, behind an environment-variable guard so it is off by default:

```java
if (System.getenv("STORE_EXPLAIN") != null) {
    String plan = dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))
        .fetchOne(0, String.class);
    plan.lines().filter(l -> l.contains("scanCount")).forEach(System.out::println);
}
```

Take the plan as a string. Printing the `Result` instead renders it as a formatted table and
truncates the one column you came for to about fifty characters, which looks like an empty plan
rather than like a truncation. A plan of any size is worth reducing on the way out: over a deep
derivation it runs to hundreds of scan nodes and more than a megabyte of text, so filter, sort or
count the `scanCount` lines rather than reading it whole.

H2 annotates each plan node with a `scanCount`, and that is what turns "this read is slow" into
"this view is expanded 469 times". Two shapes are worth naming, because they call for different
levers: one enormous scan count on a single node is a relation being re-evaluated per driving row,
while a few hundred nodes each carrying the same middling count is inlining, the same relation
expanded once per naming down the tree. Run it against the populated store rather than a seeded one;
a plan over a dozen rows is a different plan.

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

Do not copy a number out of any of those into a new document. They are gated where they are, and a
copy drifts unobserved.

## 5. Controls before conclusions

Every hypothesis gets a control on the same fixture that would refute it. This is the step the
retracted conclusions skipped, and two of three controls on the investigation that introduced the
discipline refuted the reading taken first.

The controls that keep paying:

- **Time every child in isolation.** The cheapest control there is, and it refutes the hypothesis
  you are most likely to start from. A relation whose every child answers in milliseconds and which
  itself takes minutes has no expensive child: its cost is the expansion, and no amount of
  registering something underneath it will help.
- **Snapshot the suspect into a table and re-run.** If the cost does not move, the suspect is not
  the term. This is also the cheapest preview of what a registration would buy.
- **Join on the bare column.** Replaces an expression-shaped key with a column one, which
  distinguishes an expression key from a row count.
- **Materialize in a `WITH` and re-run.** Expected to change nothing, and it is worth running
  precisely for that: a `WITH` that appears to fix something means your model of the query is wrong
  somewhere else.
- **Remove the suspect from the statement entirely.** The floor. A query that is still slow without
  it was never about it.

Report a control that refuted you. A note that records only the surviving hypothesis leaves the next
reader to re-run the same dead ends.

## 6. The lever hierarchy

Once you know what is expensive and why, the levers are ordered, and the order is on the page beside
the rules: **a captured fact, then a `meta_materialize` registration, then a rewrite.** A captured
fact has no refresh to pay for at all. A registration says the rule is right as a view and only too
slow to evaluate per naming, and it has a trade to win, a refresh against the re-evaluations it
avoids. A rewrite is last because it usually changes nothing the planner cares about.

Read the page for the argument. What this step adds is the order in which to reach, and one warning:
the rung that feels most like engineering is the one that pays least often.

## 7. Choose what to materialize, and push it down

A registration is a shared investment, not a local patch. The page states the rule and carries the
measured case; the test to apply is:

1. **Count the candidate's readers.** How many relations name it, and how many of them anything
   actually exercises today. A candidate with one reader that nothing exercises yet has every
   refresh buying nothing, and that registration belongs in the increment that adds the reader.
2. **Price its refresh.** The source view is evaluated once per refresh, whole or per graph, so the
   refresh is a cost you are adding and not only one you are avoiding.
3. **Prefer the deepest relation whose materialization removes re-evaluation for more readers than
   the one you started from.** Materialize the relation the cost multiplies *through*, not the one
   that looked slow from where you stood. Stopping short of that depth is measurably not a fix,
   which the page's counter-case shows.

Register it by adding a row to `meta_materialize` in the schema DDL, whose `reason` column is
required and is where the case you just made belongs, in this relation's own terms and with its own
arithmetic. Refresh ordering is derived from the stored view definitions and is not something you
state.

## Citation policy

This document cites doctrine pages, class names and relation names that a reader can find, and it
restates no measured per-relation number: the page and the `reason` rows are gated surfaces and this
one is scanned by nothing, so a copy here is a copy that rots in silence. The reactor-spread figures
in step 1 are the exception and they are quoted deliberately, because their whole content is that
such a number means nothing, and there is no surface where they would ever be checked.
