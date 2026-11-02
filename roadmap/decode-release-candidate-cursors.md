---
id: R1144
title: "A cursor a Graphitron 10 release candidate issued keeps its place after the upgrade"
status: Backlog
bucket: enhancement
priority: 2
theme: pagination
depends-on: []
created: 2026-11-02
last-updated: 2026-11-02
---

# A cursor a Graphitron 10 release candidate issued keeps its place after the upgrade

## Goal

A client that stored a connection cursor from a Graphitron 10 release candidate keeps paging from it after its subgraph upgrades to the next release. Every release candidate from `10.0.0-RC14`, the earliest checked, through `10.0.0-RC46`, the latest, writes cursors in a format trunk no longer reads, so the first release that carries R1088 answers each such stored cursor with `cursor is not valid` and the client restarts from the first page. After this item the decoder reads both kinds of cursor, the release candidates' and the JSON form Graphitron 9 and trunk write, and the writer keeps writing only the JSON form.

A cursor is the opaque string a connection hands out per row (`edges.cursor`, `pageInfo.endCursor`) and takes back as `after` or `before`. Clients store them: sis's event-feed consumers keep the last one as a checkpoint for days, which is why R1088 made trunk read Graphitron 9's cursors. Several Sikt subgraphs already run release candidates (four in the fs-plattform tree as of 2026-10-21, on RC25 to RC41); whether their clients store cursors across a release is not known yet, and is what decides how much this item matters to them. The same row in each format, sakila's first film ordered by `title, film_id`:

```
RC14 to RC46:            QUNBREVNWSBESU5PU0FVUgAx         ACADEMY DINOSAUR, U+0000, 1; standard Base64
trunk and Graphitron 9:  WyJBQ0FERU1ZIERJTk9TQVVSIiwxXQ   ["ACADEMY DINOSAUR",1]; URL-safe Base64, no padding
```

The item should ship in the same release as R1088 and R1142, the first one after RC46. A release that carries R1088 without it is the break.

## What the release candidates wrote

Read 2026-11-02 from `ConnectionHelperClassGenerator` in the published RC14, RC20, RC28, RC39 and RC45 jars, identical in all five, and in the source at `e99388f85` ("roadmap: R1083 Spec -> Ready"), the commit RC46 was cut from. R1088's delivery (`04f8bd139`, 2026-10-29) and R1142's (`9022a2c85`) are not ancestors of it, so no published release writes or reads the JSON form.

* **Encode.** For each order column, `record.get(column).toString()`, or U+0001 for SQL `NULL`; the tokens joined by U+0000; the result encoded with `Base64.getEncoder()`, the standard alphabet with `=` padding.
* **Decode.** `Base64.getDecoder()`, split on U+0000 keeping trailing empty tokens, strict arity, U+0001 as a typed `NULL`, every other token through `column.getDataType().convert(token)`, and a `null` from that rejected as not coercible.
* **The token is the user-side value.** `record.get` answers the value after the column's converter, where trunk writes the database value through `ColumnValues`. A release candidate's cursor on a converter-backed column already failed on its second page in the release candidate itself (R1088's changelog entry), so a decoder that reads the release candidates' cursors exactly as RC46 did loses nothing there.

## Telling the formats apart

Two checks separate them, the first decisive in most cases:

* **The Base64 alphabet.** The standard encoder can emit `+`, `/` and `=`; the URL-safe unpadded encoder trunk and Graphitron 9 use can emit `-` and `_` and never `=`. The other 62 characters decode identically under both, and both JDK decoders accept a missing pad, so the bytes come out right whichever decoder matches the characters present.
* **The decoded text.** A JSON cursor opens with `[` and never holds a raw U+0000: the writer escapes control characters and the reader rejects them unescaped. A release-candidate cursor over two or more columns always holds U+0000.

What neither check settles is a single-column release-candidate cursor whose value is itself the text of a one-element JSON array (`[5]`, `["x"]`), whose byte length is a multiple of three (so no `=`) and whose Base64 happens to hold no `+` or `/`. That needs a string-typed sort column holding values shaped like JSON arrays. The Spec decides whether to accept the residue or to fall back to the release-candidate reading when the JSON reading fails to coerce.

## Plan sketch

* **`decodeCursor`** (the emission in `ConnectionHelperClassGenerator`) gains a format branch ahead of the JSON read and a release-candidate branch that reproduces RC46's decode above. Either branch failing still surfaces the one `cursor is not valid (was: "…")` client error. `encodeCursor` is unchanged.
* **Comments.** The class javadoc and the comment above the `decodeCursor` emission name the second read format and why it is read, without citing the item (`RoadmapReferenceGuardTest`).
* **Manual.** `docs/manual/how-to/connections.adoc` § Cursor encoding says "Cursors earlier Graphitron 10 releases issued, which wrote every value as a string, are still read." No release issued that form; the sentence becomes one saying that cursors the Graphitron 10 release candidates issued are still read, subject to the same order caveats as Graphitron 9's. `docs/architecture/reference/emitter-conventions.adoc` § Cursor encode/decode gains the decode branch.
* **Tests.** In `CursorFormatExecutionTest`, release-candidate cursors built the way RC46 built them (two columns; a `NULL` token; a cursor whose Base64 holds `+`, `/` or `=`) page to the same rows as the trunk cursor for the same row, forward and backward; a malformed cursor in either alphabet still gives the client error.

## Open for Spec

* **Multi-table connections.** What RC46 wrote for an interface or union connection, and whether trunk's order for one has the same columns. Where it does not, the release candidates' cursors from such connections join Graphitron 9's on the manual's list of cursors a client drops.
* **Changed orders.** Whether any connection's order columns changed between RC46 and trunk. A changed order fails arity whatever the format. R1122 completed plain-list orders with the row key "as on a connection", and `ConnectionTiebreakerExecutionTest` exists at `e99388f85`, so connections likely kept theirs; the Spec confirms it.
* **Enum columns.** RC46 wrote an enum column's `toString()` and read it through `DataType.convert`; R1088's `651f5e7` changed trunk to write the literal. Check what RC46 actually round-tripped before reproducing it.

## Provenance

Raised 2026-11-02 by a developer on a team already running a release candidate, replying to R1142: the cursor change is backwards-incompatible for teams already on Graphitron 10. The break they named is R1088's, which replaced the release candidates' format on both sides; R1142 changed only the writer and adds no break of its own to a published release. The release-candidate format and the RC46 base were checked the same day against the published jars and git history.
