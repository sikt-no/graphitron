---
id: R7
title: Decompose `TypeFetcherGenerator`
status: Backlog
bucket: architecture
priority: 11
theme: model-cleanup
depends-on: []
---

# Decompose `TypeFetcherGenerator`

`TypeFetcherGenerator.java` is a single multi-thousand-line file (6 900
lines as of 2026-07-13, up from 1 646 when this item was filed; re-measure
at pickup, the count only grows) with one public entry point
(`generate(GraphitronSchema)`) and well over a hundred private methods that
implement per-field-variant emitters plus shared helpers. It is the
counterpart to the now-shipped `FieldBuilder` decomposition (R6, see
[`changelog.md`](changelog.md)): a central generator that has accumulated
coverage faster than its file shape can absorb, and it has grown roughly
4x since filing, so the LOC profile proposed below needs to be re-run
against the real file rather than trusting any figure written here.

## Question to answer

Two options on the table; this is a planning item, not yet a fix.

- **Decompose along the field taxonomy** (parallel to the shipped R6
  `FieldBuilder` lift). Per-variant emitter classes
  (`QueryTableFieldEmitter`, `SplitTableFieldEmitter`, etc.) plus shared
  utilities. Existing `FetcherEmitter`, `InlineLookupTableFieldEmitter`,
  `LookupValuesJoinEmitter`, `SplitRowsMethodEmitter` already follow this
  shape and would absorb pieces.
- **Keep as one file, add a `## Layout` section to the class Javadoc.**
  A single sealed-switch dispatcher is also a defensible shape; the
  problem may be navigation, not coupling.

The question is which axis the file is actually long along: too many
variants (decompose), or too many emit-detail helpers per variant
(extract helpers, keep dispatcher). A 30-minute LOC profile per private
method would settle it.

## Coordinates with

- R6 `decompose-fieldbuilder` (shipped, see [`changelog.md`](changelog.md)):
  same shape question, different file. R6 set the precedent: lift each
  cross-cutting concern into a sibling resolver returning a sealed
  `Resolved`; the same shape applies here per-variant emitter.
- [`source-orientation-javadocs.md`](source-orientation-javadocs.md) (R35): a
  class-level Javadoc with a `## Layout` table is the partial-mitigation
  option if decomposition is deferred indefinitely.
