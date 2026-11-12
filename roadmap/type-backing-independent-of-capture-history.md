---
id: R1182
title: "A graph's type backings are the same whatever else its store holds"
status: Backlog
bucket: bug
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# A graph's type backings are the same whatever else its store holds

## Goal

What `graphitron_type_backing` records for a graph is a function of that graph's schema and classpath alone. Today it also depends on which graphs were captured into the same store before it: a class name that two classpath entries declare is resolved in classpath order, and an entry another graph has already read into the store is not read again, so a graph captured beside one that read the shared entry first can be backed by a class it does not reach when read alone. When this lands, reading a graph alone and reading it into a populated store give the same backings, and the store's answer stops depending on capture history.

## Evidence

* Found on 2026-11-12 while moving `TypeBackingPartitionTest` onto fact documents. The old test captured a corpus graph and then a graph whose classpath is the corpus followed by a library, both declaring `FilmBlurbHolder`; it asserted that `lib.Blurb` backed `Blurb` and `LibBlurb`. That held only because the corpus graph had been captured first. Read alone, as the document `scenarios/type-backing-partition-corpus-and-library.graphqls` now does, only the corpus's `FilmBlurb` is reached and `lib.Blurb` backs nothing.
* The document claims the alone answer, and `TypeBackingPartitionTest` leaves the corpus-and-library graph out of its one-store comparison, so neither pins the history-dependent answer.

## Open question

Which answer is right when two entries declare the same class: classpath order (the alone answer, and what the JVM would load), or every declaring entry. The fix follows from that ruling, not the other way round.
