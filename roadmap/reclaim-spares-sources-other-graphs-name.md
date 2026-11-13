---
id: R1177
title: "A capture does not reclaim a source another graph still names"
status: Ready
bucket: bug
priority: 5
theme: tooling
depends-on: []
created: 2026-11-12
last-updated: 2026-11-13
---

# A capture does not reclaim a source another graph still names

## Goal

A store holding several graphs keeps capturing when a file one graph reads disappears from disk:
capturing a different graph no longer aborts on a foreign key because it tried to forget a source
the first graph still reads. This holds for schema files and for classpath entries (jars and class
directories) alike.

A store is the H2 database a capture writes its facts into; a graph is one module's partition of
it, and one store can hold many. `store_source` is the store's registry of every file any graph has
read, shared by all of them, and `store_graph_source` is a graph's claim on a source: one row per
source that graph's last capture read. Reclaiming is the step at the end of a capture that deletes
registry rows for files that have vanished from disk.

## What happens today

Two gatherers reclaim, each over the source kinds it owns:

- `GraphQLSourceCapture.reclaim` (kind `SCHEMA_FILE`), called by `ModelCapture.capture` after the
  assembly capture.
- `ClasspathSourceCapture.reclaim` (kinds `JAR` and `DIRECTORY`), called inside
  `ClasspathSourceCapture.capture` after `release` and `claim`.

Each selects the sources of its kinds that this reading did not read and whose file is gone, then
runs one `DELETE FROM store_source WHERE source_name IN (...)`. Neither looks at other graphs'
claims. `store_graph_source.source_name` references `store_source` without a delete action, so a
vanished source another graph still claims fails the whole statement, and the capture of the
unrelated graph aborts.

The javadocs say the opposite was intended. `GraphQLSourceCapture.dropMembership`, the comment
above the classpath `reclaim(dsl, claimed)` call and `ClasspathSourceCapture.reclaim` all describe
the claim as "refusing the delete" of a source another graph still holds, as though the refusal
skipped that one source. It does not: a refused row fails the statement, and the statement carries
every vanished source of the run.

Reproduced on 2026-11-12 while pricing R1131's A2a, a test store holding many graphs: an earlier
test's `@TempDir` had been deleted, and the next test's capture failed with H2's
`CONSTRAINT_9531E`. A production store is partitioned by graph, so a store where one graph's schema
file or classpath entry is deleted and a different graph is captured next fails the same way. The
classpath half has not been seen failing yet; it has the same shape.

## Implementation

Each reclaim deletes only vanished sources that no graph claims. The claim relation already is the
reference count, so the check is an anti-join against it, put in the delete's own `WHERE` so the
check and the delete are one statement:

```sql
DELETE FROM store_source s
 WHERE s.source_name IN (:vanished)
   AND NOT EXISTS (SELECT 1 FROM store_graph_source m WHERE m.source_name = s.source_name)
```

The graph whose claim names a vanished file drops that claim on its own next capture
(`dropMembership` for schema files, `release` for classpath entries, both already scoped to their
own graph), and that capture's reclaim then removes the source. Until then the source row, and that
graph's rows read from it, stay. This is the store's mark-and-sweep discipline: each owner sweeps
its own, and a capture writes only its own graph's rows (the `store_graph_source` table comment says
so in those words; `docs/architecture/explanation/modeling-discipline.adoc` § "What a stored
relation owes").

Files:

- `GraphQLSourceCapture` and `ClasspathSourceCapture`: each `reclaim` keeps its own kinds and its
  own Java-side `vanished` list (`Files.exists` cannot move into SQL) and adds the anti-join to its
  delete. The anti-join is stated once, as a `Condition` both reclaims use (an `unclaimed()` beside
  the store-bookkeeping code, its exact home picked at implementation). Shared at predicate altitude
  only: each gatherer keeps its own reclaim method over its own slice of `store_source`, so the two
  owners stay distinguishable.
- Javadoc: rewrite `GraphQLSourceCapture.reclaim`, `GraphQLSourceCapture.dropMembership`,
  `ClasspathSourceCapture.reclaim` and the comment above the classpath `reclaim(dsl, claimed)` call
  to say what now happens: a source another graph claims is left for that graph's next capture, not
  refused.
- `docs/architecture/explanation/fact-model.adoc`, the "*The file is named twice.*" paragraph: it
  says removing a file from the registry nulls `source_ref` on every row that read it, and that
  `source_ref IS NULL` finds "a source removed under a reading". Since `store_graph_source` landed,
  a graph's claim keeps the registry row for as long as that graph holds rows from it, so the null
  fires only under rows whose graph has already let go of its claim (a capture stopped between the
  claim drop and the gatherers' sweep). Update the paragraph to say so; the closing sentence (the
  owner reaps its own on its next refresh) stays and is now the reason, not a side effect. No Java
  reader of `source_ref IS NULL` exists today; the check constraints in `graphitron-model.sql` are
  unaffected.

No DDL change. The foreign key from `store_graph_source` to `store_source` stays without a delete
action: it is now a backstop that should never fire rather than a mechanism the code relies on.

## Tests

Pipeline tier (store capture behaviour, asserted on store rows):

- `DocumentStateCaptureTest`: two graphs read one schema file from a shared directory. The file is
  deleted. Capturing the second graph's other files (its claim on the deleted file dropped by its
  own recipe, or the second graph capturing a corpus that does not include it) succeeds, and the
  first graph's claim and the `store_source` row both survive. The first graph then recaptures:
  its claim goes, and the source row goes with it. Today the middle step throws.
- `CodeCaptureTest`, beside "an entry gone from disk is forgotten": the same two-graph shape for a
  classpath entry. Graph A and graph B both claim an entry; the entry is deleted; B's capture
  without it succeeds and the source row survives A's claim; A's recapture forgets it.

The existing single-graph tests ("a document the author deleted loses its rows and its source row",
"an entry gone from disk is forgotten, and every row read from it goes with it") must stay green
unchanged: with one graph, its own claim drop precedes its own reclaim, so nothing about them moves.

## The ruling this implements

`docs/architecture/explanation/fact-model.adoc`, the paragraph after "A source is shared by every
graph that reads it" (ruled 2026-11-13): each gatherer owns the `store_source` rows of the kinds it
reads, its sweep forgets a source only when the file is gone and no graph's `store_graph_source`
claims it, and the foreign key is a backstop rather than the mechanism. This item makes the code
say what that paragraph says.

## Other solutions we've considered

- **Cascade the claim from the registry** (`ON DELETE CASCADE` on `store_graph_source.source_name`):
  one graph's capture would delete another graph's claim and its stamp, which the table comment
  rules out ("a capture writes exactly its own graph's rows").
- **Catch the foreign-key failure:** control flow by exception, and still all-or-nothing per
  statement, so it would need one delete per source to keep the unclaimed ones.
- **One shared reclaim method parameterised by kinds:** blurs which gatherer owns which slice of
  `store_source`. Sharing the predicate states the one common fact without that coupling.
- **Move reclaim into a store-side sweep in the anchoring phase:** `Files.exists` is the deciding
  input and cannot be read from SQL, so the Java side keeps the vanished list regardless; the
  anti-join is already the store-side half.
