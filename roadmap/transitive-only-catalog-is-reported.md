---
id: R1200
title: "A graph whose catalog only a transitive entry holds is told so"
status: Backlog
bucket: cleanup
theme: model-cleanup
depends-on: []
created: 2026-11-18
last-updated: 2026-11-18
---

# A graph whose catalog only a transitive entry holds is told so

## Goal

A graph whose `jooqPackage` names a catalog that none of its own classpath entries holds is told
so once, at its configuration. A catalog is part of a graph only when one of the graph's
non-transitive entries holds it (that is, an entry the graph declares rather than inherits through
a dependency). A graph that names a catalog it holds only on a transitive entry, or nowhere, therefore
claims no catalog. Today the author sees only `TABLE_UNRESOLVED` at every `@table`, which points
at the tables rather than at the configuration that caused it.

## Open question

The store has no defect at the grain of a graph's configuration. `graphitron_entry_defect` needs an
SDL position, and `graphql_schema_problem` allows only its four reading stages. There are two ways
in:

* A new code reported at every `@table`, the way `OUTBOX_NO_TARGET` is reported at every `@outbox`.
* A new problem relation keyed by graph, with a stated grain, an owner and a sweep.
