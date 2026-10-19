# Where a dev round spends its time on sis

Measured 2026-10-17 to 2026-10-19 on a copy of `sis`, `sis-graphql-spec` run under `graphitron:dev`
and `graphitron:generate` offline, from plugins built at trunk `ab6b49c34`, `fa37996c0` and
`2bde0cda2`. Per-gatherer times are `store_graph_progress`; per-statement times are the gaps between
lines of a timestamped `-X` log; relations were timed alone on a copy of the store with query
statistics on and result reuse off. Filed as an audit so the numbers survive the item.

sis rejects its own schema with author errors, so a round that is not broken is a round that fails
validation and still writes its report. The failed round below is a syntax error.

## Four rounds, quiet machine

`fa37996c0`, load average about 1.5 for the failed round and 8 to 9 for the others; the failed
round is the clean figure. Seconds.

| | startup | one-line edit | classpath change | failed |
|---|---|---|---|---|
| wall | 150 | 163 | 159 | 91 |
| graphitron | 34 | 67 | 67 | 42 |
| derivation | 73 | 48 | 45 | 31 |
| graphitron-ast | 8.7 | 13 | 17 | 6.4 |
| graphql-ast | 5.6 | 4.0 | 6.7 | 3.3 |
| jooq | 3.2 | 3.6 | 4.3 | 2.4 |
| assembly to sdl, no gatherer | 5.7 | 9.4 | 7.3 | 4.3 |
| capture to report, no gatherer | 16 | 14 | 8 | |

Before the federation fix below, at `ab6b49c34` under heavy load, the same four rounds took 572, 430,
514 and 509 seconds, `graphitron-ast` alone 250 to 370.

## What each cost is

**The federation link's added imports** (fixed, `2bcb259ff`). The `added` CTE of
`graphitron_federation_link_import_rule` drove from 15,225 directive applications, met each against
the graph's 49 claimed sources before the version's imports filtered them (420,420 rows), and ran
its `NOT EXISTS` against the inlined `authored` CTE per row. Alone: `authored` 2 ms, `added` 0.7 s
without the `NOT EXISTS` and 162 s with it, returning no rows. Driven from the version's imports it
is 0.15 to 0.24 s and `graphitron-ast` went to 8 s.

**`graphitron_field_source_table`'s write**, 32 of the `graphitron` gatherer's 44 s on a warm
generate. The rule alone answers in 0.7 s; filtered to the graph, as its `MERGE` filters it, 23.7 s;
under a plain derived table 43 s, the filter still pushed in; fenced with `OFFSET 0 ROWS`, 1.6 s.
Every one of its 3,878 rows is the graph's, so the filter removes nothing and changes the plan.

**The configured-tag narrowing read**, about 4 s before `sdl` starts, 9 to 12.5 s alone.
`EmittedRegistry.narrowings` joins `graphitron_minted_coinage` to `graphitron_type_minted`,
`graphitron_field` and `graphql_field`. Coinage alone is 1 ms, with the minted type 78 ms, with the
field 1 ms, all three 8.9 s; fencing coinage alone leaves 8 s. Coinage and its minted type joined
first and fenced, then the two field tables, is 68 ms for the same 436 rows.

**The walk's catalog lookups**, about 8 s with no statement between derivation and the first
diagnostic read. Five of eight main-thread samples were in `JooqCatalog.findUnqualifiedTable`,
`findColumn` and `findForeignKeyRef`, reached from `NodeDeclaration.isNodeType` and
`NodeIdLeafResolver`; `findUnqualifiedTable` builds a `TableEntry` by reflection for every table of
every schema on each call and compares names. The other three were the walk itself, an H2 page read
and graphql-java coercing input literals.

**Smaller**: `graphitron_field_path_resolution_rule` read 6 s (4.5 s alone), the insert into
`graphitron_field_reference_step_hop_keyed` 3.2 s; the forward walk retires both. Derivation is 31 s
on a quiet round.

## What a round does that its change did not ask for

* A comment line added to one schema file reran every gatherer: `jooq` on an unchanged catalog,
  every document through `graphql-ast`, and all of anchoring and derivation.
* One touched class in a sibling module's output reran the whole schema pipeline, though no document
  changed. A classpath round that succeeds logs nothing; only a failing one does.
* A syntax error was reported after the full round, 91 s in, though the document fails to parse
  about 5 s in. The report was withheld, correctly.

## Reads after a round

`status` over MCP answered in 3 to 9 ms warm, `diagnostics` in 80 to 155 ms. `diagnostic` alone on the
store averaged 107 ms, 78 ms without its classpath rows; `build_warning_stale_module` 2 to 10 ms warm
and its hint 2 ms. The report write was 70 to 440 ms.

## Found on the way

On Ctrl+C the dev store's close-time compaction failed with "Database is already closed", the
database having been closed by H2's own shutdown hook first, so the file is never compacted: 405 MB
after four rounds against 48 MB after one generate.
