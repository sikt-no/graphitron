---
id: R384
title: "Emit GraphQL-coordinate provenance comments in generated SQL via jOOQ hint()"
status: Backlog
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Emit GraphQL-coordinate provenance comments in generated SQL via jOOQ hint()

A single GraphQL operation fans out into many SQL statements: the exact set and
shape depend on which fields the client projects, and the count depends on the
resulting data (batched child fetches). Consumers wiring OpenTelemetry get most
of what they need from off-the-shelf instrumentation: the GraphQL collector gives
the per-request resolver tree, and the JDBC collector gives per-execution SQL
spans with timing. What neither layer can supply is *provenance*: given a SQL span
(or an aggregated `pg_stat_statements` row, keyed by `queryid`), there is no
direct way to attribute it back to the generated fetcher that produced it. Today
that attribution is reverse-engineered from the SQL text by hand. This blocks the
performance workflow consumers actually want, "this field's query is slow, show
me which coordinate it came from and pull its plan," because the link from
statement to schema coordinate is missing.

The proposal is to have the generator bake a stable provenance marker into each
generated query, carrying the GraphQL coordinate (`Type.field`, e.g. `Query.films`
or `Film.actors`) that the fetcher serves. jOOQ's `hint()`
(`SelectHintStep.hint(String)` / `SelectQuery.addHint(...)`) is the primitive: it
injects a raw string immediately after the `SELECT` keyword and rides through the
renderer untouched, so a comment like `/* graphitron: Film.actors */` lands in the
emitted SQL. Two properties make this safe: the marker is *constant per generated
query*, so it does not perturb bind-parameter normalization and does not fragment
`queryid` (PostgreSQL jumbles the parse tree and ignores comments); and it is
orthogonal to any *dynamic*, per-request `traceparent` a runtime sqlcommenter layer
might inject. The marker carries the coordinate only. Note the coordinate is
exactly `Type.field`; it is not the full path used to reach the query. Capturing
that path is a possible extension but must not be expressed as dotted-coordinate
concatenation (`Query.films.actors` is ambiguous with a coordinate) and is
out of scope for this stub unless Spec decides otherwise.

Open questions for Spec: opt-in via Mojo configuration vs. always-on (default-on
risks surprising consumers who diff generated SQL or have hint-sensitive
dialects); marker syntax and stability contract (consumers will pattern-match it,
so it becomes a quasi-API); whether the same `hint()` seam should also carry
optimizer hints (`/*+ ... */`) as a separate concern or stay comment-only; and
how the coordinate is threaded to the emit site (it is already known at
generation time, but confirm the seam). Database/collector-side mechanics
(`EXPLAIN (GENERIC_PLAN)` on the parameterized text, `auto_explain` gated by
duration, exemplars) are consumer concerns and need nothing from the generator
beyond this marker.
