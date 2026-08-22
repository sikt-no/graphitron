---
id: R810
title: "Retire the classification taxonomy page and lead Reference with the schema"
status: Backlog
bucket: docs
priority: 5
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Retire the classification taxonomy page and lead Reference with the schema

A contributor looking for the fact store's schema reference reaches it last or not at all. Every
path into the architecture docs opens on `code-generation-triggers.adoc`, the classification
taxonomy of the transitional walk: it is the first thing the Reference quadrant blurb names on
the architecture index, the first bullet on the reference index with the schema reference sixth
and last, and one of three pages in the first-time-contributor reading order in both `README.md`
and `roadmap/README.md`. The walk is being strangled and the pipeline overview says so in as many
words, so the page a reader meets first describes the part of the system that is leaving, while
the model of record is the page they have to scroll past it to find.

The page is not dead prose, which is what makes this an item rather than a deletion. It is a
rendered view over the live `@classified` corpus: `ClassifiedDocTest` asserts that every doc
example's rendered SDL appears in it verbatim, so removing the file fails the build, and the
`classified-corpus` authoring loop treats it as the corpus's one published surface. Roughly half
its 756 lines are legacy taxonomy prose and half are generated from live fixtures, and the two
halves have opposite lifetimes: the taxonomy retires with the walk, the corpus view outlives it.

So the item has to answer what happens to each half before it can answer whether the file goes.
Candidate shapes, in ascending order of how much they touch: reorder the entry points so the
schema reference leads Reference and the taxonomy is marked transitional where it is named; split
the corpus view onto its own page so the legacy half can be deleted on its own clock; delete the
taxonomy half outright and rehome the corpus rendering, which needs `ClassifiedDocTest`'s target
path and the `classified-corpus` skill's "The files" list to move with it. The Spec should also
settle the wider reading of the request, whether this is only about the page or about the
transitional classification model behind it, since retiring the walk itself is a strangler
completion of a different size and belongs in its own item.
