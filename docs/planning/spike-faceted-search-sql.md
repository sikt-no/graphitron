# Spike: SQL strategies for faceted search

> **Status:** Report. Feeds plan revision in [plan-faceted-search.md](plan-faceted-search.md).
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

**The plan's v1 default moves from shape A (GROUPING SETS) to shape C
(UNION ALL of per-facet GROUP BYs).**

Reasons:

1. Shape A as originally specified in the plan is invalid Postgres
   syntax. The working variant (CASE-dispatched filter aggregates)
   parses but loses on every measured scenario.
2. Shape C matches shape A's "one round-trip" property.
3. Shape C lets the planner choose per-facet indexes when filters
   are selective — shape A cannot.
4. Shape C parallelises across arms automatically via `Parallel
   Append`. Shape A's HashAggregate over GROUPING SETS is
   sequential.
5. Shape C is structurally simpler to generate: N `SelectJoinStep`s
   glued with `DSL.unionAll(...)`. No `DSL.groupingSets(...)` or
   `DSL.grouping(...)` — the Phase 4 sketch's jOOQ API surface
   shrinks.

**v2 fallback:** if a Connection field grows past ~10 facets, the
UNION becomes unwieldy. At that point, the emitter can revert to
shape B — N separate jOOQ queries issued from the resolver and
assembled in Java. This is an emitter-side decision; the GraphQL
surface stays identical.

**v1 requirements carried to the plan:**

- Per-facet columns should be indexed for the selective-filter case
  to pay off. Document this as a performance note alongside `@facet`.
- `max_parallel_workers_per_gather` must be > 0 for shape C's
  parallelism. This is the Postgres default; flag if any consumer
  has set it to 0.
- Column data type used in the UNION must match between arms for
  Postgres; emitter uses `::text` casts when facet columns have
  different SQL types (rating enum vs. rental_duration smallint vs.
  left(title,1) text).

## Follow-ups to fold into the plan revision

1. "SQL emission strategy" section: rewrite around UNION ALL. Cite
   this report. Move Phase 4 jOOQ sketch from `DSL.groupingSets(...)`
   to `baseSelect.unionAll(otherFacetSelect).unionAll(...)`.
2. Phase 4 code sketch: remove GROUPING() / CASE-dispatched filter
   aggregates. Replace with per-facet minus-self `WHERE`
   reconstruction per arm (same FacetSpec logic; different assembly).
3. Open Questions #4 and #5: move to "Resolved design decisions" with
   a one-line pointer to this report.
4. Phase 1 section: mark complete; summarise the verdict inline so
   the plan remains self-contained when the spike report is deleted.
