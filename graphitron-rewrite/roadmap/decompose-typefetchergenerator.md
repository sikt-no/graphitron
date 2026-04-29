---
title: Decompose `TypeFetcherGenerator`
status: Backlog
bucket: architecture
priority: 11
theme: structural-refactor
depends-on: [stub-interface-union-fetchers]
---

# Decompose `TypeFetcherGenerator`

`TypeFetcherGenerator.java` is 1 646 lines, one public entry point
(`generate(GraphitronSchema)`), and ~30 private methods that implement
per-field-variant emitters plus shared helpers. It is the counterpart to
[`decompose-fieldbuilder.md`](decompose-fieldbuilder.md): a central
generator that has accumulated coverage faster than its file shape can
absorb.

## Question to answer

Two options on the table; this is a planning item, not yet a fix.

- **Decompose along the field taxonomy** (parallel to the
  `decompose-fieldbuilder` proposal). Per-variant emitter classes
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

## Blocked on

Active item
[`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md) is
adding `buildTableInterfaceFieldFetcher` and the surrounding TypeResolver
wiring. Decomposing while methods are landing is wasted work; pick this up
after that plan moves to Done.

## Coordinates with

- [`decompose-fieldbuilder.md`](decompose-fieldbuilder.md) — same shape
  question, different file. Whichever lands first sets the precedent;
  consider doing them as a coordinated pair if both go to Spec close in
  time.
- [`source-orientation-javadocs.md`](source-orientation-javadocs.md) — a
  class-level Javadoc with a `## Layout` table is the partial-mitigation
  option if decomposition is deferred indefinitely.
