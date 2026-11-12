---
id: R1176
title: "An edge whose item type is declared later in the folder stores a defect or its rows, never a foreign-key failure"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-11-12
last-updated: 2026-11-12
---

# An edge whose item type is declared later in the folder stores a defect or its rows, never a foreign-key failure

## Goal

A schema folder in which a connection's edge names an item type that a file read later declares is captured whole: the edge's rows land once the type is known, or, if the type is never declared, the reading stores a defect at the field. Today the capture stops with a referential-integrity violation on `graphitron_edge (graph_name, item_type_name)`, which reaches an author as a failed build with no position, not as a diagnostic. When this lands, the order in which a folder's files are read no longer decides whether a schema can be captured.

## Evidence

* Found on 2026-11-12 while writing fact documents. A scenario that pulled in a shape with `Film.languages: [Language]` in a connection, with `Language` declared only in the scenario's own file (written after the pulled-in one), failed its capture on that foreign key. The documents were made self-contained to work around it, so no fact document reproduces it yet; the reproduction is a scenario pulling in a shape whose edge item type it declares itself.
