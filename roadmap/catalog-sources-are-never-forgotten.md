---
id: R1186
title: "A catalog source no graph claims is never forgotten"
status: Backlog
bucket: cleanup
theme: model-cleanup
depends-on: []
created: 2026-11-15
last-updated: 2026-11-15
---

# A catalog source no graph claims is never forgotten

## Goal

A generated jOOQ package is a `store_source` row of kind `JOOQ_SCHEMA`, and its `sql_` rows hang on it. The jOOQ gatherer lets a graph drop its claim on a package it no longer reads, but nothing deletes the source row itself, so a package every graph has let go of keeps its rows in the store for good. `docs/architecture/explanation/fact-model.adoc` says each gatherer owns the source rows of the kinds it reads and forgets one once no graph's `store_graph_source` claims it; the classpath reading does that for its jars and class directories, and the jOOQ gatherer does not for its packages. When this lands, the jOOQ gatherer forgets an unclaimed package on the same terms, and its `sql_` rows go with it by cascade. Found while making the catalog reading skip an unchanged catalog.
