---
id: R1177
title: "A capture does not reclaim a schema source another graph still names"
status: Backlog
bucket: bug
priority: 5
theme: tooling
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# A capture does not reclaim a schema source another graph still names

## Goal

A store holding several graphs keeps capturing when one graph's schema file is deleted: capturing
another graph no longer aborts on a foreign key because it tried to forget a source the first graph
still names.

## What happens today

A capture ends by calling `GraphQLSourceCapture.reclaim`, which forgets schema files that have
vanished from disk. `store_source` is shared by every graph in the store, so `reclaim` looks at all
of them: it deletes every `SCHEMA_FILE` source whose file is gone, unless this reading read it. A
second graph whose `store_graph_source` row still names that source makes the delete fail on the
foreign key (`CONSTRAINT_9531E`), and the capture of the unrelated graph aborts.

It was reproduced on 2026-11-12 while pricing R1131's A2a, a test store holding many graphs: an
earlier test's `@TempDir` had been deleted, and the next test's capture failed. A production store
is partitioned by graph, so a store where one graph's schema file is deleted and a different graph is
captured next would fail the same way.

A likely shape, for the Spec: reclaim only the sources no graph's `store_graph_source` still names,
and leave a dangling link to that graph's own next sweep, which is the store's mark-and-sweep
discipline (`docs/architecture/explanation/modeling-discipline.adoc`).
