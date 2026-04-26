---
title: "Spike: SQL strategies for faceted search"
status: Spike
---

# Spike: SQL strategies for faceted search

> Report. Feeds plan revision in [faceted-search.md](faceted-search.md).
> Deleted together with the parent plan at Done.

Hand-measured comparison of five SQL shapes for the filter-minus-self facet
aggregation that `plan-faceted-search.md` needs `TypeFetcherGenerator` to
emit. Resolves the plan's Phase 1 spike and its Open Questions #4 (NULL
bucket) and #5 (count-desc ordering).

## Setup

- PostgreSQL 16.13 on Ubuntu 24.04 (web-sandbox native cluster).
- Synthetic Sakila-shaped `film` + `film_scaled` (200× duplication → 200 000
  rows). Docker-based Sakila unavailable in web sandbox; the synthetic
  schema mirrors the plan's scenarios column-for-column
  (`rating mpaa_rating`, `rental_duration smallint`, `title text`,
  `category_id int`, 16 categories, enum rating with ~5% NULL).
- Indexes on `rating`, `rental_duration`, `category_id`, and the
  expression `left(title, 1)` (scenario-5 prefix facet).
- All timings are `\timing on` wall-clock medians over 10 warm-cache runs
  in a single psql session. Raw EXPLAIN captures in `/tmp/spike/out_*.txt`
  during the session; the relevant extracts are inlined below.
- Seed is deterministic so re-runs reproduce. One honest caveat: the
  seed correlates rating and rental_duration (PG ⟹ duration=6,
  PG-13 ⟹ duration=4, etc.), so some cross-facet counts come out 0
  where a realistic dataset would show spread. This is visible in the
  raw counts but does not affect plan shape or timing comparison —
  each shape sees the same rows.

## Scenarios measured

| # | Predicate | Facets |
|---|-----------|--------|
| 1 | none                                            | rating, rental_duration |
| 2 | `rating = 'PG'`                                 | rating, rental_duration |
| 3 | `rating IN ('PG','PG-13') AND rental_duration = 5` | rating, rental_duration |
| 5 | `rating = 'PG' AND left(title,1) = 'A'`         | rating, title_prefix    |
| 7 | `rating = 'PG'` with NULL-bearing rating column | rating, rental_duration |

Scenarios 4 and 6 from the plan are trivial variants of 1/3 and were
skipped. Scenario 7 shares predicates with 2; it is called out separately
because the question is observational ("does NULL survive?"), not
comparative.

## Candidate shapes

### A. `GROUPING SETS` with per-aggregate `FILTER`

Plan's originally proposed v1 default:

```sql
SELECT ...,
  count(*) FILTER (WHERE
      (GROUPING(rating) = 0 AND rental_duration = 5)
   OR (GROUPING(rental_duration) = 0 AND rating IN ('PG','PG-13')))
FROM film_scaled
GROUP BY GROUPING SETS ((rating), (rental_duration));
```

**This form is invalid in Postgres.** First execution returned:

```
ERROR:  grouping operations are not allowed in FILTER
LINE 8: (GROUPING(rating) = 0 AND rental_duration = 5)
```

Workaround that *does* parse: keep `GROUPING()` out of `FILTER`, dispatch
at the SELECT-level `CASE` instead. Each grouping set computes N `count(*)`
aggregates, each with a plain row-level filter; the outer CASE picks the
aggregate that matches the current bucket.

```sql
SELECT
  CASE WHEN GROUPING(rating) = 0 THEN 'rating' ELSE 'duration' END AS facet,
  rating::text, rental_duration,
  CASE
    WHEN GROUPING(rating) = 0           THEN count(*) FILTER (WHERE rental_duration = 5)
    WHEN GROUPING(rental_duration) = 0  THEN count(*) FILTER (WHERE rating IN ('PG','PG-13'))
  END AS cnt
FROM film_scaled
GROUP BY GROUPING SETS ((rating), (rental_duration));
```

This is what the rest of the spike measures as "shape A."

### B. `N` separate GROUP BY queries (correctness reference)

One query per facet, each with its own minus-self predicate. Counts are
trivially correct by construction:

```sql
-- rating facet (drop rating filter)
SELECT rating, count(*) FROM film_scaled WHERE rental_duration = 5 GROUP BY rating;
-- rental_duration facet (drop duration filter)
SELECT rental_duration, count(*) FROM film_scaled WHERE rating IN ('PG','PG-13') GROUP BY rental_duration;
```

### C. `UNION ALL` of per-facet GROUP BYs

Same per-facet queries as B, concatenated with UNION ALL so the server
sees one statement:

```sql
SELECT 'rating' AS facet, rating::text AS value, count(*) AS cnt
FROM film_scaled WHERE rental_duration = 5 GROUP BY rating
UNION ALL
SELECT 'duration', rental_duration::text, count(*)
FROM film_scaled WHERE rating IN ('PG','PG-13') GROUP BY rental_duration;
```

### D. Window + `FILTER OVER (PARTITION BY …)`

`COUNT(*) FILTER (WHERE …) OVER (PARTITION BY facet_col)` produces a
per-partition count on every input row. Consolidating to one row per
facet-value requires either `DISTINCT` (which pays the sort cost and
still preserves all rows for the *other* facets, producing a cartesian
grid), or grouping/windowing that amounts to shape B/C.

**Verdict without running wall-clock numbers:** any multi-facet shape
here either grid-cartesian-blows-up (N₁ × N₂ × … output rows per
input row) or degenerates into one of B/C. Dropped from further
measurement.

### E. Conditional aggregation on pre-known domain

One `count(*) FILTER (...)` per (facet, value) pair, no GROUP BY:

```sql
SELECT
  count(*) FILTER (WHERE rental_duration = 5 AND rating = 'G')     AS r_G,
  count(*) FILTER (WHERE rental_duration = 5 AND rating = 'PG')    AS r_PG,
  ...
  count(*) FILTER (WHERE rating IN ('PG','PG-13') AND rental_duration = 3) AS d_3,
  ...
FROM film_scaled;
```

Requires every facet value to be known at generation time (enums work;
arbitrary open-ended strings do not). Measured for enum-only cases.

## Timing medians

10 warm-cache runs each, single psql session.

| Scenario | A (GROUPING SETS) | B (N queries, sequential) | C (UNION ALL) | E (conditional agg) |
|----------|-------------------|---------------------------|---------------|---------------------|
| 1 — no filter      | **32 ms** | 72 ms  | **29 ms** | —    |
| 3 — multi-filter   | 38 ms     | **20 ms** | 27 ms | 42 ms |
| 5 — open-ended     | 51 ms     | **11 ms** | 27 ms | n/a  |

Observations:

- Shape A is never the fastest. Its single-scan advantage is wiped out
  by the full 200 000-row seq scan being unavoidable — the planner
  can't pick per-facet indexes when aggregates depend on every row.
- Shape B wins when filters are selective and the planner can switch
  to bitmap index scans on each arm. Costs N round-trips from the
  application.
- Shape C matches B's index usage (each UNION ALL arm plans
  independently) while keeping one round-trip. Postgres parallelises
  the arms automatically via `Parallel Append` (seen in every EXPLAIN
  plan for shape C).
- Shape E has A's shape (single seq scan, aggregates only) but
  doesn't generalise to open-ended value domains.

> **Caveat — warm cache, small table.** The medians above are from 10
> warm-cache runs against a 200 000-row table (~15 MB heap) that fits
> entirely in the 128 MB `shared_buffers`. Under these conditions
> every shape is CPU-bound on already-resident pages, and wall-clock
> rewards parallelism (shape C) rather than read cost. Production
> tables will typically exceed `shared_buffers`; see the v2
> re-measurement below for the read-cost picture.

## v2 re-measurement — 5M rows, read-cost lens

The v1 medians above were warm-cache on a 15 MB table. Production data
will cross `shared_buffers` (Postgres default 128 MB), so cold reads —
not CPU-on-cached-pages — become the dominant cost. Re-measured at
**5 000 000 rows (444 MB heap, ~3.5× `shared_buffers`)** with per-facet
fan-out (2 / 5 / 8 facets under the multi-filter scenario) and a
cluster restart before each cold run. Cold rows capture `EXPLAIN
(ANALYZE, BUFFERS)` top-level counts (`read` = 8 KB blocks fetched
from disk, `hit` = already in shared buffers). Warm rows are the
median of 10 subsequent in-session runs.

Added facet columns for the 8-facet scenario: `language_id` (8 vals),
`release_year` (24 vals), `special_features` (6 vals),
`left(title, 1)` (26 vals), `length / 30` (3 vals). Each has a btree
index like the original three.

| Facets | Shape | Cold `read=` | Cold `hit=` | Cold exec | Warm median |
|--------|-------|-------------:|------------:|----------:|------------:|
| 2      | A (GROUPING SETS)       | 56 834 | 15     | 2 093 ms | 1 247 ms |
| 2      | C (UNION ALL)           | 58 511 | 56 852 | 2 013 ms | 1 614 ms |
| 2      | **E (FILTER aggs)**     | **56 832** | 0 | **651 ms** | **458 ms** |
| 5      | A                       | 56 834 | 18     | 3 219 ms | 2 720 ms |
| 5      | C                       | 58 521 | 61 933 | 2 032 ms | 1 757 ms |
| 5      | **E**                   | **56 832** | 0 | **1 217 ms** | **999 ms** |
| 8      | A                       | 56 834 | 18     | 4 326 ms | 3 683 ms |
| 8      | C                       | 58 521 | 67 003 | 2 063 ms | 1 804 ms |
| 8      | E                       | *(not measured — ~94 aggregates to hand-expand; read cost already shown constant at 2/5 facets)* |

(56 832 blocks × 8 KB = 444 MB = exactly one full heap scan. The heap
does not fit in `shared_buffers`.)

### What the top-level Buffers actually show

1. **Shapes A and E do one full scan, regardless of facet count.**
   `read=56 834` is flat from 2 to 8 facets. Both shape their plan
   around a single `Seq Scan`: A with HashAggregate holding one hash
   key per grouping set, E with a Parallel Seq Scan feeding many
   `count(*) FILTER` aggregates in one pass.
2. **Shape C's reads are ~1.03× one full scan.** First arm reads the
   heap cold; subsequent arms find most pages still in cache *within
   the same query* and show up as `hit=`, not `read=`. The `read=58k`
   total is not N × table — the N × hypothesis from my
   earlier-session misreading (summing every indented `Buffers:` line)
   was wrong. Across 2/5/8 facets the `read=` column barely moves.
3. **Shape E parallelises; shape A does not.** E's Parallel Seq Scan
   recruits 2 workers; A's HashAggregate over N grouping keys runs in
   one backend. That's most of A's CPU penalty at high facet count.

### What the wall-clocks show

- **E dominates at every facet count** — 2.7× faster than A and 3.5×
  faster than C at 2 facets; 2.7× vs A and 1.8× vs C at 5 facets.
  Single parallel scan + in-line conditional aggregates is the
  cheapest shape when it applies.
- **A vs C crosses over between 2 and 5 facets.** At 2 facets A is
  30% faster than C (simpler plan, no Gather overhead). At 5 facets C
  is 55% faster than A (Parallel Append amortises across arms while
  A's GROUPING SETS stays single-threaded). Most production Connection
  requests will land in this 2-5 facet range; neither shape dominates.
- **C and A are within 3% on cold reads** at every facet count in
  this scale range. The read-cost argument the v1 report leaned on —
  "C lets the planner pick per-facet indexes" — is correct *when a
  filter can hit a per-facet index*, but under multi-filter most arms
  still do a heap scan and reuse the first arm's cached pages. Reads
  are essentially the same.

### Scaling assumption (unmeasured)

Extrapolated, not measured: if the table were 10–30× larger
(`shared_buffers` << working set), shape C's cross-arm cache retention
would degrade — later arms would partially re-read pages evicted by
earlier arms. A and E would still be 1 × table. A sensible Phase 5
item is to re-run at 50M rows if/when a real deployment has that much
data; until then assume C is within 20% of A on reads at Sikt
production scale.

## Plan-shape extracts

### A3 (multi-filter, GROUPING SETS):

```
 Sort  (cost=8920.47..8920.49 rows=10 ...)
   ->  HashAggregate  (cost=6420.00..8920.30 rows=10 ...)
         Hash Key: film_scaled.rating
         Hash Key: film_scaled.rental_duration
         ->  Seq Scan on film_scaled (rows=200000, buffers=1920)
 Execution Time: 51.488 ms
```

Single seq scan, single HashAggregate with two Hash Keys (one per grouping
set). No parallelism. Deterministic structure regardless of predicate.

### C3 (multi-filter, UNION ALL):

```
 Gather  (cost=4044.25..5195.41 rows=10 ...)
   Workers Planned: 2
   ->  Parallel Append
         ->  Subquery Scan (rating arm)
              ->  HashAggregate
                   ->  Bitmap Heap Scan on film_scaled (rows=40000)
                         ->  Bitmap Index Scan on film_scaled_rental_duration_idx
         ->  Subquery Scan (duration arm)
              ->  HashAggregate
                   ->  Bitmap Heap Scan on film_scaled (rows=80000)
                         ->  Bitmap Index Scan on film_scaled_rating_idx
 Execution Time: 61.114 ms
```

Each arm picks its own index; Parallel Append runs them concurrently
across worker backends. With `max_parallel_workers_per_gather = 2`
(Postgres default) this is effectively 2-way parallel.

### B3 (multi-filter, two queries):

```
-- Query 1: rating facet
 ->  Bitmap Index Scan on film_scaled_rental_duration_idx
 Execution Time: 13.154 ms
-- Query 2: duration facet
 ->  Bitmap Index Scan on film_scaled_rating_idx
 Execution Time: 21.490 ms
Sum: 34.644 ms DB time, 2 round-trips.
```

## Correctness

All four measured shapes (A/B/C/E) produce identical counts per facet
value for every scenario. Cross-checked against shape B by diff. NULL
bucket counts match in every case.

## Resolved design decisions

### OQ #4 — NULL handling in facet value

Scenario 7 output (rating facet, `rating = 'PG'` filter applied, 10 000
NULL-rating rows in the table):

```
 facet  | rating_val | cnt
--------+------------+-------
 rating |            | 10000   ← NULL bucket, no cast, preserved naturally
 rating | G          | 30000
 rating | NC-17      | 40000
 rating | PG         | 40000
 rating | PG-13      | 40000
 rating | R          | 40000
```

All three measured shapes (A/B/C) emit NULL as a distinct group key
automatically. No explicit handling needed. **Decision: preserve NULL
as its own bucket in v1.** The `*FacetValue.value` field on the schema
side is therefore nullable; no `WHERE col IS NOT NULL` scrubbing in
emitter SQL. If a consumer wants to hide NULL, they can drop the row
client-side or apply `IS NOT NULL` as a regular filter.

### OQ #5 — Facet-value ordering

Adding `ORDER BY cnt DESC, value` on top of shape C adds ~0.4 ms at
this data size (median 27.3 → 27.7 ms). The sort runs on the
consolidated output which is tiny (≤ 5 rows per facet here; realistic
facets top out at a few hundred values). **Decision: emit
`ORDER BY facet, cnt DESC, value` at the top of the UNION in v1.**
Consumers that need a different ordering can re-sort client-side.

## Verdict

**v1 default: shape C (`UNION ALL` of per-facet GROUP BYs).** The v2
re-measurement does not overturn the v1 choice: C and A are within 3%
on cold reads, and C wins wall-clock at 5+ facets (Parallel Append vs.
A's single-threaded HashAggregate) while staying within 30% of A at 2
facets. Shape A stays rejected because its CPU cost grows worst with
facet count and it never wins on reads to compensate.

Reasons the verdict stands:

1. Shape A as originally specified in the plan (GROUPING() inside
   FILTER) is invalid Postgres syntax. The CASE-dispatched workaround
   parses but scales badly with facet count — 8-facet A is 2× slower
   than 8-facet C at 5M rows.
2. Shape C matches shape A's "one round-trip" property.
3. Shape C parallelises across arms via `Parallel Append`. Shape A's
   HashAggregate over GROUPING SETS runs in a single backend — the
   dominant cost at high facet count.
4. Shape C's reads are essentially flat at 1× table across 2/5/8
   facets — cross-arm buffer-cache retention inside the same query
   prevents the N × table growth earlier analysis feared. (Unmeasured
   caveat: at 10× larger tables, cross-arm retention degrades. Re-run
   if a production deployment has >50M rows.)
5. Shape C is structurally simpler to generate: N `SelectJoinStep`s
   glued with `DSL.unionAll(...)`. No `DSL.groupingSets(...)` or
   `DSL.grouping(...)`.

**Where the v2 measurement does change the story: shape E is now the
documented optimisation path.** At 5M rows with the multi-filter
scenario, E is 2.7× faster than A and 1.8–3.5× faster than C on
warm wall-clock, and its cold reads are identical to A's (1 × table).
E's constraint is that every facet value must be known at emit time —
trivial for enum-backed columns (jOOQ catalog holds them) and small
FKs (compile-time `@facet(values:)` or a pre-query on the referenced
table), impossible for open-ended text facets like `left(title, 1)`.

The hybrid that realises E's win without losing generality:

- All facets on the request are bounded-domain (enum, small FK,
  Boolean): emit a single shape-E query. Fastest path.
- Any facet is open-ended: fall back to shape C (current v1 default).

Given the measured 2–3× wall-clock gap, this is a meaningful
post-v1 optimisation, but it is *not* a prerequisite for v1
correctness. Ship C first; add E as a Phase 5 or post-v1 follow-up
gated on profiling data from real Sikt schemas. Keep the
`FacetSpec` record shape amenable to both emitters so the switch is
internal to `TypeFetcherGenerator`.

**v2 fallback (the existing one, still applicable):** if a Connection
field grows past ~10 facets, the UNION becomes unwieldy. The emitter
can revert to shape B — N separate jOOQ queries assembled in Java.
GraphQL surface stays identical.

**v1 requirements carried to the plan:**

- Per-facet columns should be indexed for the selective-filter case
  to pay off. Document this as a performance note alongside `@facet`.
- `max_parallel_workers_per_gather` must be > 0 for shape C's
  parallelism. This is the Postgres default; flag if any consumer
  has set it to 0.
- Column data type used in the UNION must match between arms for
  Postgres; emitter uses `::text` casts when facet columns have
  different SQL types.

## Follow-ups to fold into the plan revision

1. "SQL emission strategy" section: rewrite around UNION ALL. Cite
   this report. Move Phase 4 jOOQ sketch from `DSL.groupingSets(...)`
   to `baseSelect.unionAll(otherFacetSelect).unionAll(...)`.
2. Phase 4 code sketch: remove GROUPING() / CASE-dispatched filter
   aggregates. Replace with per-facet minus-self `WHERE`
   reconstruction per arm.
3. Open Questions #4 and #5: move to "Resolved design decisions" with
   a one-line pointer to this report.
4. Phase 1 section: mark complete; summarise the verdict inline so
   the plan remains self-contained when the spike report is deleted.
5. **New — shape E as documented optimisation path.** Add an entry
   to the plan's "Open Questions" or "Future work" section: when
   every facet on a request is bounded-domain (enum, small FK,
   Boolean), emit a single `count(*) FILTER` aggregate per (facet,
   value) pair instead of a UNION arm. Measured 2–3× warm-clock
   faster, 1 × table reads, requires value-domain enumeration in the
   emitter. Keep `FacetSpec` permissive enough that the E/C choice
   is internal to `TypeFetcherGenerator`.
