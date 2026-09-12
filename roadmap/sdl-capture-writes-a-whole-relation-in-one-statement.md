---
id: R945
title: "SDL capture writes a whole relation in one statement and H2's parser exhausts the heap"
status: In Review
bucket: bug
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-11
last-updated: 2026-09-11
---

# SDL capture writes a whole relation in one statement and H2's parser exhausts the heap

## Goal

A consumer with a large schema can capture it at all. Today the FS platform's `sis-graphql-spec`,
about 27000 lines of SDL across 30 files carrying roughly 7900 field declarations, cannot run
`graphitron:dev`: every attempt dies with `java.lang.OutOfMemoryError: Java heap space` about 90
seconds in, on a 16 GB default heap. Raising the heap is not a workaround, because the cost is
quadratic in the number of rows one relation holds. When this lands, the SDL half of capture costs
memory linearly in schema size, the way the jOOQ and classpath halves already do, and no writer in
the module can reintroduce the whole-relation statement without failing the build.

Two terms, glossed once. *Capture* is the pass that reads a consumer's schema documents, jOOQ
catalog and classpath and writes what it found as rows into the *fact store*, the H2 database the
generator then answers its verdicts out of by SQL. The SDL writers are the capture stage for the
schema documents: one relation per kind of SDL node, one row per node, written by `SdlEntries`,
`GraphitronEntries` and `SdlSchemaProblems`.

Priority 1 because it blocks a consumer's development process outright and has no workaround: the
quadratic term means the largest relation on a schema of this size needs tens of GB for a single
statement, so no heap setting reaches it. Schema size is the only thing that decides who hits it,
which makes it a wall every consumer meets eventually rather than a property of this one schema.

## What fails and why

Every writer in `no.sikt.graphitron.model.capture` writes a relation as one jOOQ
`insertInto(...).valuesOfRows(rows).onDuplicateKeyUpdate()...execute()` over the whole row list.
That statement shape is load-bearing: the upsert is how a re-read of a file refreshes each row's
`touched_at`, and the mark-and-sweep contract `SdlEntries`' class javadoc states (every row carries
its reading's instant, the reading ends by deleting its file's older rows) rests on it. What is not
load-bearing is how many rows one statement carries.

H2 has no `ON DUPLICATE KEY UPDATE`, so jOOQ 3.20 emulates it. Rendered against a keyed H2 table and
checked in this session, three rows become:

```sql
merge into "T" using (select cast(? as varchar) "K", cast(? as varchar) "V"
                      union all select cast(? as varchar), cast(? as varchar)
                      union all select cast(? as varchar), cast(? as varchar)) "t"
on "T"."K" = "t"."K"
when matched then update set "T"."V" = "t"."V"
when not matched then insert ("K", "V") values ("t"."K", "t"."V")
```

One `SELECT` per row, joined by `UNION ALL`. A plain multi-row `VALUES` is a flat list H2 parses in
a loop; the union chain is not. H2's parser recurses once per arm, and `Parser.setSQL` copies the
statement's token list at every level of that recursion, so a relation of N rows allocates O(N^2)
tokens before a single row is written. The reporting consumer's stack, with the middle of the
repeating cycle elided:

```
java.lang.OutOfMemoryError: Java heap space
    at java.util.ArrayList.<init> (ArrayList.java:157)
    at org.h2.command.Parser.setSQL (Parser.java:2959)
    at org.h2.command.Parser.parseQueryExpressionBodyAndEndOfQuery (Parser.java:2524)
    at org.h2.command.Parser.parseQueryPrimary (Parser.java:2688)
    at org.h2.command.Parser.parseQueryTerm (Parser.java:2550)
    at org.h2.command.Parser.parseQueryExpressionBody (Parser.java:2529)
    ... the four frames above repeat for the depth of the UNION chain
```

A live (post-GC, so reachable) class histogram taken near the ceiling on the reporting consumer:

```
1:  89 690 336   2 152 568 064  org.h2.command.Token$KeywordToken
2:  24 603 065     590 473 560  org.h2.command.Token$IdentifierToken
5:  15 857 256     380 574 144  org.h2.command.Token$ParameterToken
```

The statement recovered from a heap dump of that run carries the column list of
`graphql_ast_type_declaration_entry`, the relation `SdlEntries.typeDeclarations` writes. Every
`valuesOfRows` site in the module has the same shape, so the one that overflows first is an
accident of which relation is largest on a given consumer; the classfile census met the same chain
as a `StackOverflowError` at around twenty thousand rows before the fix below was applied to it.

The bound already exists. `RowChunks.of` in `no.sikt.graphitron.model.sink` cuts a row list into
runs of 500, and its javadoc names this failure. The commit that introduced it wrapped the 14
statements in `JooqFactCapture` and the 6 in `ClasspathFactCapture`, whose censuses were the
relations that grew large first, and stopped there. The 26 statements in the three SDL writers, the
4 in `StoreEntries` and the two `sources()` writers were left as they were. Measured on the
reporting consumer with the 26 SDL sites wrapped in the same loop: peak RSS falls from 16471 MB and
an OOM at 1:38 to a flat 1050 MB with the capture completing (7689 classes, 6874 class files).

## Delivered (2026-09-11)

Spec straight to In Review, with no Ready and no In Progress, which is a bypass rather than an
oversight and is recorded so nobody reads the state as a gate that ran. The spec's author is a
different session; the reviewer rule for Spec to Ready was not exercised, and this item still owes
its In Review to Done review to a third party, which the bypass does not touch.

Implemented as specified, from the branch retiring the SDL walk, because the two are the same
sequencing question: every entry relation that arc adds is another site, and the walk it removes is
the *bounded* writer, the sink batching where the entries do not. Fixing this after the walk went
would have meant meeting the quadratic on a tree with no fallback.

`RowChunks.execute(rows, chunk -> statement)` walks `of(rows)` and executes per chunk, and all 86
`valuesOfRows` sites in `graphitron-model` main sources go through it: 19 in `SdlEntries`, 21 in
`GraphitronFieldEntries`, 12 in `GraphitronInputValueEntries`, 7 in `GraphitronTypeEntries`, 15 in
`JooqFactCapture`, 7 in `ClasspathFactCapture`, 4 in `StoreEntries` and 1 in `SdlSchemaProblems`.
The `rows.isEmpty()` guards are gone, `execute` running no statement for an empty list; the guards
on input collections that precede row construction stay, not being about the statement.

One correction to the spec's own count. It says 46 sites, which was true when it was written and is
not now: the entry migration has landed the input-value site's twelve and the field site's step
split since, and both are exactly the kind of writer this bounds. The number is not load-bearing,
but "all of them, no roster" is, so it is restated against the tree rather than carried forward.

Transparency is measured rather than argued. The claim that splitting one statement into several
is the same write rests on no two rows in one call sharing a primary key, which the spec establishes
by construction. Setting the bound to 1, the most extreme split there is, leaves all 1172
`graphitron-model` tests passing, so every writer here agrees with itself one row per statement.

`MultiRowWritesAreChunkedTest` is the gate, and it discriminates on enclosure as the spec asks: each
`valuesOfRows` must sit lexically inside a `RowChunks.execute` call, decided by walking back over
balanced parentheses to the innermost call still open. Watched failing both ways it must. Unwrapping
one statement reports it by file and line. And the trap the spec names, a writer whose whole row
list is simply called `chunk`, is reported too, where a scan keying on the argument's name would
have passed it.

## Implementation

**One way to write a relation.** `RowChunks` gains
`execute(List<R> rows, Function<List<R>, ? extends Query> statement)`, which walks `of(rows)` and
executes the statement built for each chunk. `of` stays public for the unit test. Every
`valuesOfRows` site in `graphitron-model` main sources, all 46 of them, becomes:

```java
RowChunks.execute(rows, chunk -> dsl.insertInto(t, t.GRAPH_NAME, /* ... */)
    .valuesOfRows(chunk)
    .onDuplicateKeyUpdate()
    .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
    /* ... */);
```

The 20 sites already looping `for (var chunk : RowChunks.of(rows))` migrate to the same call. That
is in scope for a reason beyond tidiness: the gate below is roster-free only if the rule has no
exceptions, and one spelling of the rule is what makes "inside a `RowChunks.execute` lambda" a
structural fact a scan can check. The 6 sites the Backlog draft of this item called deliberately
small (`StoreEntries`, both `sources()` writers) are chunked too. Their size is a property of the
reporting consumer, not of the relation: `StoreEntries.schemaInputs` is one row per schema file and
`ClasspathFactCapture.sources` one per classpath entry, both consumer-controlled and unbounded in
principle, and a five-row list through `execute` is one statement, so uniformity costs nothing.
`WrittenStatementCoverageTest`'s own javadoc states the module's position on exception lists: a
roster would be the thing that rots.

**The empty guards go.** `RowChunks.of` yields no chunks for an empty list, so the
`if (rows.isEmpty()) return;` that precedes most sites is dead under `execute` and is removed.
Guards on the *input* collection that precede row construction (`seen.isEmpty()`,
`named.isEmpty()` in `JooqFactCapture`) are not about the statement and stay as they are.

**Chunking is transparent, and the body says where that comes from.** Splitting one MERGE into
several is only the same write if no two rows in one call share a primary key; otherwise a
collision that one statement would surface becomes upsert-over-upsert across chunks. Every writer
here has that property by construction, and the implementer confirms rather than argues it: the 18
`SdlEntries` relations and the 7 `GraphitronEntries` relations are keyed by the position a node
was written at, so two rows from one parse of one file cannot collide; `SdlSchemaProblems` and the
four `StoreEntries` relations are keyed by `(graph_name, ordinal)` with ordinals assigned by
enumeration; `JooqFactCapture.sources` keys on a source name drawn from a set. The one site where
the property is not yet by construction is `ClasspathFactCapture.sources`, which writes the census's
entry list in classpath order, and a classpath can name one path twice; the implementer makes that
writer dedupe its rows (or `ClassfileCensus.read` its entries) so the same argument holds at all 46
sites, rather than reasoning about what two identical arms of one MERGE do today. All chunks of a
relation land in the transaction the writer already runs in (`FactCapture.capture`'s `txDsl` for
the SDL and configuration writers, the `ModelCapture` pass for the two censuses), so no reader
sees a relation half-written that could not before.

**The bound stays at 500, and `RowChunks` owns the reason.** The cost model is one line and belongs
in the class javadoc next to the failure it names: tokens copied per statement are about
rows^2 x tokens-per-row, so at 500 rows of a 40-token row the copy is on the order of 10 million
token references per statement, negligible; relation width is a small constant factor on that, so
the bound is not made width-aware. Someone meeting a slow capture later can price raising the
number from that sentence rather than re-deriving it.

**The gate.** A new test class in `graphitron-model` beside `WrittenStatementCoverageTest`,
`ChunkedStatementGateTest`, scans the module's `src/main/java` the way `GathererIsolationTest`
does (comments stripped before the text is read) and fails on any `valuesOfRows(` whose nearest
enclosing call is not `RowChunks.execute(`. The discriminator is enclosure, not the argument's
spelling: a lambda parameter named `entries` passes, a local named `chunk` outside the helper
fails. The scope is this module's main sources and the javadoc says why the same token elsewhere
is out of it: `TypeFetcherGenerator` and `MutationInputResolver` in `graphitron` emit
`valuesOfRows` into generated consumer code against the consumer's own database, where this rule
is simply false. The failure message names the file and the method, and says that a relation's
size is the consumer's to decide and a whole-relation statement is O(rows^2) to parse. The symptom
of a regression is an OOM in a consumer's build and never a red test here, because no fixture in
the tree writes more rows than the bound; that is why the gate is lexical and size-independent
rather than a runtime assertion in `FactStores`.

## Tests

- `RowChunksTest` (new, `sink/`): `of` on an empty list yields no chunks, on 1001 rows yields
  three with sizes 500, 500 and 1; `execute` on an empty list builds and executes nothing (the
  function is never called); `execute` on 1001 rows calls the function three times and executes
  each result once.
- `RowChunksTest.aWriterLandsEveryRowAndNoStatementExceedsTheBound`: drives one real writer,
  `SdlEntries.write`, with a synthetic document of more field definitions than the bound, over a
  `DSLContext` derived with a jOOQ `ExecuteListener` that records each statement's row count
  against `graphql_ast_field_definition_entry`. Asserts every row is stored, more than one
  statement was issued, and no statement carried more rows than `RowChunks` exposes as its bound.
  The count of statements is derived from the bound, never written as a literal, so the test stays
  true when the number moves. This lives beside `RowChunks` rather than in `SdlEntriesTest`
  because bounding is the sink's fact shared by 46 writers, not the document reader's.
- `ChunkedStatementGateTest.everyMultiRowInsertIsChunked`: the lexical gate above over the real
  sources, passing on landing day by construction. Paired with
  `ChunkedStatementGateTest.theGateDetectsWhatItClaimsTo`: the scan's predicate run over three
  inline source snippets, one bare `valuesOfRows(rows)`, one inside `RowChunks.execute`, one bare
  call whose argument happens to be named `chunk`, reporting the first and third and not the
  second. This is what shows the gate is a live predicate rather than a scan that passes because
  nothing matches.
- Existing `SdlEntriesTest`, `GraphitronEntriesTest`, `SdlSchemaProblemsTest`, `StoreEntriesTest`,
  `JooqFactCaptureTest`, `ClasspathFactCaptureTest` and `WrittenStatementCoverageTest` pass
  unchanged; they are the assertion that the write's content did not move when its shape did.

Completion is demonstrated by the behavioural pin passing with more than one statement observed
and every row present, by the gate's seeded case reporting the bare sites and not the wrapped one,
and by `grep -rn 'valuesOfRows(' graphitron-model/src/main/java` showing every hit inside a
`RowChunks.execute` lambda. The consumer-scale evidence in the section above is what the item is
for and is re-run at pickup against the reporting consumer; it is not what the build asserts,
because a fixture of that size has no other reader.

## Relation to the refresh pass

Not the same defect as the materialization-refresh slowness on the same capture path (the
in-transaction recursive-view reads behind R854 through R857, and the dev-round pass R943 prices).
The two are independent: this one is quadratic SQL *parsing* and is fixed by bounding statement
size, that one is repeated uncached recursive *evaluation*. Both are paid by the same
`FactCapture.capture` call, and with the OOM removed the reporting consumer's `graphitron:dev` went
straight from the capture into a `Materializations.refreshPartition` that had not finished 17
minutes later, so fixing this item makes that one the next thing a large consumer hits.

## Other solutions we've considered

- **A bind batch, the way `FactSink` and `FactWrites` already write.** One prepared statement bound
  once per row is linear by construction and needs no bound, no constant and no gate; `FactWrites`'
  javadoc names that as the property the load's cost depends on. The honest price is not "losing
  one statement per relation", which chunking gives up anyway, but 46 sites trading the typed
  `Rows.toRowList` spelling for per-row `bind(...)` lists, and a commit's worth of re-verifying
  every column binding the coverage gate cannot see through a bind batch. That is a second write
  path converging on the first, a real question with a real cost, and not what a priority-1
  unblock should carry. This item extends the shape already at 20 sites; whether the two paths
  should converge afterwards is left open here rather than decided in passing.
- **Drop the upsert for delete-then-insert.** A plain multi-row `VALUES` parses flat, so the
  quadratic term vanishes. It also breaks the mark-and-sweep contract: the sweep finds the rows a
  reading did not touch by their older `touched_at`, and a delete-then-insert has no untouched rows
  to find, so every re-read of one file would have to be a re-read of the graph.
- **A temporary table and one MERGE from it.** Linear, and one statement per relation again. It
  adds a relation the schema does not declare and a second statement shape for the coverage gate to
  learn, to reach a property the existing helper already gives.
- **Raise the heap.** Refused: the cost is quadratic in rows, so the largest relation on a schema
  of the reporting consumer's size needs tens of gigabytes for one statement and the next larger
  schema needs more.
- **Chunk the 26 SDL sites only, leave the other 20 loops and 6 whole-list writes alone.** The
  smallest diff, and the shape the Backlog draft measured. It leaves three spellings of one rule in
  one module and makes the gate carry a roster of six exceptions whose only argument is that the
  reporting consumer's classpath was short.
