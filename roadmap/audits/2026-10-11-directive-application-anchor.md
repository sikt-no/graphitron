# Why the graphql-ast gatherer spent most of its anchor numbering directive repeats

Measured 2026-10-11 on a copy of the store a sis capture wrote, replaying the gatherer's two halves
in a fresh JVM against it with a jOOQ listener timing each statement. sis held 30 documents, 35,975
entries, 2,113 type declarations and 6,892 authored directive applications. Two runs each; the
machine was quiet.

## Where the time went

`store_graph_progress` had put the gatherer at 31.8 s in a capture taken under a load average of 7
to 12. Replayed quietly:

| Half | Time |
|---|---|
| The per-document entries, all 30 documents | 3.2 to 6.1 s |
| The anchor | 7.9 to 9.6 s |
| of which `GraphQLAstCapture.directiveApplications` | 5.6 s |

`directiveApplications` numbers each application's repeat within its coordinate and directive name.
It climbs from the application to the type declaration it was written inside and reads that
declaration's merge ordinal, so repeats number in merge order. The last hop looks a declaration up
by position, and `graphql_type_declaration` is keyed type name first, so no key serves it: `EXPLAIN
ANALYZE` shows a scan count of 14,569,688, every declaration of the graph read once per application.

## Statistics do not explain it

The replay store had been analysed, a capture running `ANALYZE` at its end. Reset to H2's default
selectivity on every column, the state a capture's anchor runs in:

| Anchor | Analysed | Default statistics |
|---|---|---|
| As is | 7.9 to 9.6 s | 11.8 to 12.8 s |
| With an index on the declaration's position | 3.0 to 4.6 s | 2.8 to 5.2 s |

`directiveApplications` with the index takes 0.36 s either way. The plan had no index to choose, so
statistics could only make the scan's neighbours slower.

## What the ordinal is for

The ordinal is in the application's key, and two relations hold a foreign key on it. Its order is
read by the deprecated `intent_authored_*_claim` views alone; the field chain, the one order the
manual calls load-bearing, ranks by source position and keeps the ordinal as a tie-break it says
never fires. What makes the merge order necessary is agreement: the SDL decode numbers repeats by
walking the merged registry and writes that number into the `graphitron_` decoded rows, and the
anchor must compute the same number in SQL for those rows to find their application.

On sis one application of 6,892 has an ordinal above zero, and none lacks a position.

## Why a unique key on the position did not land

No two type declarations start at one place, and on sis 2,113 declarations sit at 2,113 positions.
Stated as a unique key it failed 40 test classes: 38 because `SeededStore` writes every seeded
declaration at one position, and two because jOOQ renders H2's upsert as a merge matching any unique
key, so a recapture that puts another type at a position updated the old row in place. An index
carries the lookup without changing what the writer matches.
