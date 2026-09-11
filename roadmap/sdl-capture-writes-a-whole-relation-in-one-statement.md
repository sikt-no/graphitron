---
id: R945
title: "SDL capture writes a whole relation in one statement and H2's parser exhausts the heap"
status: Backlog
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
memory linearly in schema size, the way the jOOQ and classpath halves already do.

The failure is in the parser, not the generator. The reporting consumer's stack, with the middle of
a repeating cycle elided:

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

`SdlEntries` writes each relation with a single `insertInto(...).valuesOfRows(rows)` over the whole
row list, and jOOQ renders that for H2 as one unioned `SELECT` per row. `Parser.setSQL` clones the
entire token list once per nesting level of the resulting chain, so N rows cost O(N^2) tokens. A
live (post-GC, so reachable) class histogram taken near the ceiling on the reporting consumer:

```
1:  89 690 336   2 152 568 064  org.h2.command.Token$KeywordToken
2:  24 603 065     590 473 560  org.h2.command.Token$IdentifierToken
5:  15 857 256     380 574 144  org.h2.command.Token$ParameterToken
```

The statement recovered from a heap dump of that run carries the column list `GRAPH_NAME`,
`SOURCE_NAME`, `SOURCE_LINE`, `SOURCE_COLUMN`, `SOURCE_REF`, `TOUCHED_AT`, `KIND`, `IS_EXTENSION`,
`NAME`, `DESCRIPTION`, which is `GRAPHQL_AST_TYPE_DECLARATION_ENTRY`, the relation
`SdlEntries.typeDeclarations` writes. Every `valuesOfRows` site in the file has the same shape, so
the one that overflows first is an accident of which relation is largest.

The suggested fix is already in the tree and needs no new mechanism. `RowChunks.of` in
`no.sikt.graphitron.model.sink.RowChunks` bounds a write to 500 rows per statement, and its javadoc
already names this exact failure ("a multi-row `VALUES` is parsed by recursing once per row group,
so a whole relation in one statement overflows H2's parser stack"). It was applied to
`JooqFactCapture` and `ClasspathFactCapture`, whose censuses are the ones that grew large first, and
never to the SDL-document writers. Wrapping each site in the loop those two files already use:

```java
for (var chunk : RowChunks.of(rows)) {
    dsl.insertInto(t, ...)
        .valuesOfRows(chunk)   // was: valuesOfRows(rows)
        ...
        .execute();
}
```

That is 26 sites: 18 in `SdlEntries`, 7 in `GraphitronEntries`, 1 in `SdlSchemaProblems`. The
remaining unchunked `valuesOfRows` callers look deliberate and should stay: `StoreEntries` writes
lint rules, schema bindings and extensions, all sized by the consumer's pom rather than its schema,
and the `sources()` method in each of the two fact-capture files writes one row per classpath entry
or jOOQ package.

Measured on the reporting consumer with those 26 sites wrapped: peak RSS falls from 16471 MB and an
OOM at 1:38 to a flat 1050 MB with the capture completing (7689 classes, 6874 class files). That is
the whole of the evidence for the suggestion; it is offered as a starting point rather than a
verified change, because the verification build on that tree did not pass for an unrelated reason
(a stale fixture container missing `catalogue_shelf`, so `graphitron-sakila-example` failed), and
the diff has not been reviewed by anyone.

Four things a spec should settle rather than inherit from here. Whether 500 is the right bound for
these relations, given that the two files already using it write rows of a different width.
Whether 26 near-identical loops is the right shape, or whether the writers want a shared helper
that takes the row list and the statement builder, since the `RowChunks` javadoc's own stated reason
for existing is that the writers "cannot drift apart on the number" and 26 hand-written call sites
is how drift arrives. Whether a guard test should fail the build on a bare `valuesOfRows(rows)` in
a schema-sized writer, since nothing today stops the next writer from reintroducing this, and the
symptom is an OOM in a consumer's build rather than a red test here. And whether the
`if (rows.isEmpty()) return;` guards that precede most of these sites should go, since
`RowChunks.of` yields no chunks for an empty list and makes them dead.

Priority 1 because it blocks a consumer's development process outright and has no workaround: the
quadratic term means the largest relation on a schema of this size needs tens of GB for a single
statement, so no heap setting reaches it. Schema size is the only thing that decides who hits it,
which makes it a wall every consumer meets eventually rather than a property of this one schema.

Not the same defect as the materialization-refresh slowness on the same capture path (the in-
transaction recursive-view reads behind R854 through R857). The two are independent: this one is
quadratic SQL *parsing* and is fixed by bounding statement size, that one is repeated uncached
recursive *evaluation*. Both are paid by the same `FactCapture.capture` call, and with the OOM
removed the reporting consumer's `graphitron:dev` went straight from the capture into a
`Materializations.refreshPartition` that had not finished 17 minutes later, so fixing this item
makes that one the next thing a large consumer hits.
