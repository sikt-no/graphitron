---
id: R760
title: "The assignability closure does not terminate on a real census"
status: Backlog
bucket: architecture
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The assignability closure does not terminate on a real census

`intent_class_assignable` closes the declared supertype relation transitively. On a real census it
does not return. Measured against a persisted store holding 2,066 supertype edges, it does not
complete in 90 seconds, and it does not complete anchored to a single class name either, H2 pushing
no predicate into the recursion. The cause is not the closure's size: the relation it denotes is
about 11,000 rows and a rewrite computes it in 0.81 seconds. The cause is that the recursion's
`UNION ALL` replicates every path once per duplicate edge row, and duplicate edge rows are the
normal case, so the frontier doubles per hop over chains that run 23 hops deep. Nothing reads the
view in production today, so this costs no wall clock; what it costs is that the first reader gets a
build that hangs with no diagnostic, which is the exact outcome the view's own comment says its
guard removed.

## The measurement

All figures from one 4 vCPU sandbox, against the persisted workspace store a full reactor build
leaves behind, and reproduced against a bare in-memory H2 holding nothing but the edge rows.

`SELECT COUNT(*) FROM intent_class_assignable` over 2,066 edges in `jvm_class_supertype`:

| Query | Result |
|---|---|
| whole relation | no result in 90 s |
| anchored, `WHERE class_name = '<one name>'` | no result in 90 s |
| whole relation, in a bare in-memory H2 with only the edge rows | no result in 120 s |
| the same, plus an index on `class_name` | no result in 120 s |

So it is neither the store, nor the store's size, nor a missing index. It is the recursion.

The cliff is sharp, taking prefixes of the same edge list:

| Edge rows | Whole relation |
|---|---|
| 800 | 4,803 rows in 0.38 s |
| 1,000 | 5,788 rows in 0.47 s |
| 1,200 | 6,714 rows in 2.36 s |
| 1,600 | no result in 60 s |
| 2,066 | no result in 60 s |

## Why, precisely

The 2,066 edge rows carry only 1,097 distinct `(class_name, supertype_name)` pairs. 969 of those
1,097 pairs, 88%, are declared by exactly two classpath entries. That is routine and the view's
comment says so: one workspace store holds every entry every graph ever read, so one class name
declared twice is expected.

The recursive term joins `ON s.class_name = r.supertype_name` and projects `r.source_name`
unchanged. Two edge rows differing only in `source_name` therefore produce **identical** output
rows, and `UNION ALL` keeps both, and both recurse. Each hop through a duplicated pair doubles the
frontier. Modelling the recursion row for row outside H2, with duplicates retained exactly as the
table holds them:

| Depth | Frontier | Cumulative |
|---|---|---|
| 1 | 2,066 | 2,066 |
| 6 | 47,104 | 101,642 |
| 12 | 622,592 | 1,950,154 |
| 18 | 12,320,768 | 26,001,866 |

Still doubling at depth 18, and the model runs out of a 10 GB heap there. Dedupe the edges first
and the same recursion terminates at depth 24 having produced 8,387 rows in total. So the shipped
form materialises upwards of 26 million intermediate rows, and unboundedly more, to return a
relation of about 11,000.

The view's comment reasons about the wrong hazard, and it is worth saying which, because the
reasoning is otherwise sound and a Spec pass should not simply delete it. It argues about cycles,
which the path guard does handle, and it concludes: "The guard enumerates simple paths, which over
the acyclic shape a census actually holds costs what the unguarded form costs." Simple paths in the
*name* graph are indeed few, 8,387 of them. But the recursion runs over *rows*, not names, and the
duplicate rows the comment identifies two sentences earlier are what multiply each simple path.
The comment names the duplication and the path guard and does not connect them.

Two adjacent claims in the same comment are false for the same reason and are the retired
vocabulary below: that a general transitive relation "is here because it is cheap over a census
relation that had to exist anyway", and that the guard "removes a build that hangs with no
diagnostic from the set of possible outcomes". It is the guard's interaction with duplicate rows
that produces exactly such a hang.

## The rewrite, validated

Recurse over the deduplicated name graph and re-attach `source_name` afterwards. This denotes the
same relation: hops already join on the name alone, and `source_name` is only ever carried from the
base row, never touched by the recursion.

```sql
CREATE VIEW intent_class_assignable (source_name, class_name, supertype_name) AS
WITH RECURSIVE edge (class_name, supertype_name) AS (
  SELECT DISTINCT class_name, supertype_name FROM jvm_class_supertype
),
reaches (class_name, supertype_name, path) AS (
  SELECT e.class_name, e.supertype_name,
         '/' || e.class_name || '/' || e.supertype_name || '/'
    FROM edge e
  UNION ALL
  SELECT r.class_name, e.supertype_name,
         r.path || e.supertype_name || '/'
    FROM reaches r
    JOIN edge e
      ON e.class_name = r.supertype_name
   WHERE POSITION('/' || e.supertype_name || '/' IN r.path) = 0
)
SELECT DISTINCT b.source_name, r.class_name, r.supertype_name
  FROM (SELECT DISTINCT class_name, supertype_name FROM reaches) r
  JOIN (SELECT DISTINCT source_name, class_name FROM jvm_class_supertype) b
    ON b.class_name = r.class_name;
```

Measured: **identical result sets** to the shipped form at every edge count where the shipped form
completes at all (4,803 rows at 800 edges, 6,714 at 1,200), and **0.81 s** over the full 2,066 where
the shipped form does not finish. `ClassAssignableTest`'s 7 cases pass unchanged, including
`aChainClosesAcrossClasspathEntries`, which is the case that pins the cross-entry semantics the
rewrite has to preserve. `mvn install -Plocal-db` green, 14/14 modules, with no wall-clock change,
as expected for a view nothing reads.

A Spec pass should still consider the alternative it rules out, because it is the obvious one and it
is wrong: adding `AND s.source_name = r.source_name` to the recursion also makes the view fast
(10,492 rows in 1.76 s) and is **not** semantics-preserving. It truncates every chain at a classpath
entry boundary, which is the ordinary shape rather than an edge case, and
`aChainClosesAcrossClasspathEntries` fails on it by design.

## What a Spec pass has to settle

* **Whether the relation should exist at all.** It has no production reader. The only reads are
  `ClassAssignableTest`'s, at a fixture scale where both forms are fast, which is why this went
  unnoticed. Its comment says it is "here because it is cheap over a census relation that had to
  exist anyway" and names a container test over `java.util.List` and `org.jooq.Result` as the
  motivating consumer, which no code performs yet. Deleting it and reinstating it with its first
  real consumer is a legitimate outcome and possibly the better one; the rewrite above is what to
  land if it stays.
* **Whether `jvm_class_supertype` should be captured, which is the same question from the other
  end.** That relation has no reader in production either: this view is its only consumer, and the
  view's comment says the dependency runs that way round, the closure being "the whole reason
  `jvm_class_supertype` records what it records". So the chain is dead end to end, 8,817 rows written
  on every capture for a view nothing reads and which could not serve a reader anyway. Deleting the
  view without settling the relation would leave the capture cost with nothing at all behind it.
  R762 carries the row counts and treats this as its own half of the same question; settle the two
  together.
* **Whether the fixture scale is the actual defect.** A derived relation whose only test runs at a
  scale three orders of magnitude below production is a testing gap, not just a bug. Whatever the
  verdict on the view, decide whether a census-scale pin belongs somewhere for the relations derived
  over `jvm_`, and where: `ClassAssignableTest` deliberately states its census row by row, for
  reasons its own class javadoc argues well and this item does not dispute.
* **Whether other recursive derivations share the shape.** `intent_type_domain` is the schema's
  other closure and is materialized rather than a view. Whether it recurses over a relation that can
  hold duplicate rows under `UNION ALL` is the same question and should be answered while the
  mechanism is in hand.

## How to re-measure

```bash
# Any persisted workspace store from a full build will do; the census is what matters, not the graph.
ls ~/.cache/graphitron/model/*/
# Copy it aside and open it read-only, then time the relation whole and anchored:
#   SELECT COUNT(*) FROM intent_class_assignable
#   SELECT COUNT(*) FROM intent_class_assignable WHERE class_name = '<any name>'
# Set a query timeout; without one the first of these does not come back.
```

Reproduce the cause in a bare in-memory H2 rather than the real store, which rules out the store,
its size and its indexes in one step: create `jvm_class_supertype` alone, insert the real rows, and
run the recursion with and without the leading `SELECT DISTINCT`.

## Retired vocabulary

For the Done-gate retirement sweep. All in the `intent_class_assignable` view comment, and all
currently asserted:

* "a general transitive relation is here because it is cheap over a census relation that had to
  exist anyway"
* "The guard enumerates simple paths, which over the acyclic shape a census actually holds costs
  what the unguarded form costs"
* "it removes a build that hangs with no diagnostic from the set of possible outcomes"
