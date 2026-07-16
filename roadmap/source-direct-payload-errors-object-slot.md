---
id: R490
title: "Narrow source-direct payload errors slot to List<Object>"
status: Backlog
bucket: cleanup
priority: 20
theme: mutation-write
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Narrow source-direct payload errors slot to List<Object>

The source-direct payload records (e.g. `FilmLookupPayload`) type their errors slot as `List<?>` to match the dispatch lambda's `Function<List<?>, P>` parameter. A migration to `List<Object>` is planned as part of narrowing the source-direct dispatch contract; until it lands, `List<?>` keeps the lambda substitutable. The rationale survives in the record's javadoc, but the migration itself lost its only tracking pointer when the transient roadmap citation was stripped from that comment (its former tracker has shipped), so it is filed here as its own item rather than dropped. Scope: audit source-direct payload error slots, switch to `List<Object>` once the dispatch contract is narrowed, and drop the interim note. Surfaced as a promotion candidate by the javadoc reference purge.
