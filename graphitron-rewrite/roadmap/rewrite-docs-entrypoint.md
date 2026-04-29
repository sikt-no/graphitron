---
title: Make `graphitron-rewrite/docs/README.md` a real entry point
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: [docs-site-asciidoc]
---

# Make `graphitron-rewrite/docs/README.md` a real entry point

The rewrite docs index is currently a fragment: it starts at numbered item
**#4** with no preamble, because the numbering is intended to continue from
`/docs/README.md` at the repo root. Readers landing here directly via a
roadmap link, a code search hit, or a GitHub directory listing see items 4-7
with no anchor.

This plan reshapes that file into an entry point that stands alone, and adds
the two pieces of orientation that contributors most often need on day one
but have to reconstruct from scratch today: a pipeline tour and a module
map.

## Scope

### 1. Drop the inherited numbering

Renumber items 1-N within the file, or drop the numbering entirely and
restructure as a "by purpose" list. Add a one-paragraph preamble naming the
audience (contributors implementing generators) and a link back to
`/docs/README.md` for the broader project context.

### 2. Add a 30-second pipeline tour

A labelled diagram and one or two sentences per stage:

```
.graphqls files
  → RewriteSchemaLoader        (parse + auto-inject directives.graphqls)
  → GraphitronSchemaBuilder    (classify into GraphitronSchema; the only
                                place that reads directives)
  → GraphitronSchemaValidator  (reject UnclassifiedField/Type, etc.)
  → Generators                 (TypeFetcherGenerator, TypeClassGenerator,
                                TypeConditionsGenerator, schema/* family)
  → JavaFile.writeToPath       (idempotent writes + sweep of orphans)
  → consumer compile           (graphitron-test verifies type correctness;
                                Postgres execution verifies behaviour)
```

`code-generation-triggers.md` has a similar four-line diagram inside the
"How Classification Works" section, but it stops at the
`GraphitronSchema → Generators` boundary and does not name the loader, the
validator, or the writer-and-sweep contract. The new diagram lives in the
docs index so it is the first thing a contributor sees; the existing
classification-focused diagram can stay as is, or shrink to a "see the
end-to-end tour above" pointer.

### 3. Add a module map

Eight modules under `graphitron-rewrite/`. Today only `graphitron-javapoet`
has a README. Add a short table somewhere near the top of the docs index:

```
graphitron-javapoet         — internal Square JavaPoet fork
graphitron                  — generator core
graphitron-fixtures-codegen — produces test-database jOOQ classes
graphitron-fixtures         — packages those classes for downstream tests
graphitron-maven            — Maven Mojos (generate, validate, watch, dev)
graphitron-test             — compiles + executes generated code (real jOOQ + Postgres)
graphitron-lsp              — editor LSP server
roadmap-tool                — regenerates roadmap/README.md
```

Per-module `README.md` files are out of scope for this plan; the inline
table covers the orientation question without inviting per-module README
drift.

### 4. Add a one-line pointer to a clean Backlog → Done exemplar

`workflow.md` describes the canonical state machine. A new contributor
calibrating "what does a clean cycle look like in practice?" has to read
git history. Add one sentence pointing at a recent small example (e.g. the
`@asConnection` totalCount thread, captured as the top entry in
[`changelog.md`](../roadmap/changelog.md)) so the workflow doc has a
concrete anchor.

## Out of scope

- Per-module READMEs. The inline table is the cheaper answer.
- Rewriting `code-generation-triggers.md`. Active item
  [`docs-as-index-into-tests.md`](docs-as-index-into-tests.md) covers that
  file. This plan should land before that one, so the two passes do not
  edit overlapping paragraphs.
- `runtime-extension-points.md`. Tracked separately in
  [`runtime-extension-points-rewrite.md`](runtime-extension-points-rewrite.md).
- Stale legacy references in other rewrite docs. Tracked separately in
  [`fix-legacy-refs-in-rewrite-docs.md`](fix-legacy-refs-in-rewrite-docs.md).
