---
id: R28
title: Make `graphitron-rewrite/docs/README.adoc` a real entry point
status: In Review
bucket: cleanup
priority: 3
theme: docs
depends-on: [docs-site-asciidoc]
---

# Make `graphitron-rewrite/docs/README.adoc` a real entry point

The rewrite docs index used to be a fragment: it started at numbered item
**#4** with no preamble, because the numbering was intended to continue from
`/docs/README.md` at the repo root. Readers landing there directly via a
roadmap link, a code search hit, or a GitHub directory listing saw items 4-7
with no anchor.

R9 (`docs-site-asciidoc`) renumbered the file from `1` and migrated it to
AsciiDoc as `README.adoc`. What is still missing on day one is the
orientation a contributor reaches for first and has to reconstruct from
scratch today: where in the module tree does the code I'm changing live,
and what stages does it sit between? This plan adds both, in the same file
that CLAUDE.md and the `reviewer-prompt` skill point at as the canonical
architectural orientation.

## Scope

### 1. Drop the inherited numbering — *done in R9*

R9's AsciiDoc migration renumbered the file from `1` and gave it a real
top-of-file preamble. Carried here for traceability only; no further work.

### 2. Add a 30-second pipeline tour

A labelled diagram and one or two sentences per stage. The shape:

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

`code-generation-triggers.adoc` has a similar four-line diagram inside the
"How Classification Works" section, but it stops at the
`GraphitronSchema → Generators` boundary and does not name the loader, the
validator, or the writer-and-sweep contract. The new diagram lives in the
docs index so it is the first thing a contributor sees; the existing
classification-focused diagram stays as a zoomed-in view, with a one-line
pointer up to the end-to-end tour.

### 3. Add a module map

Eight modules under `graphitron-rewrite/`. Today only `graphitron-javapoet`
has a README. Add a short table near the top of the docs index:

```
graphitron-javapoet         — internal Square JavaPoet fork
graphitron                  — generator core
graphitron-fixtures-codegen — produces test-database jOOQ classes
graphitron-fixtures         — packages those classes for downstream tests
graphitron-maven            — Maven Mojos (generate, validate, dev)
graphitron-test             — compiles + executes generated code (real jOOQ + Postgres)
graphitron-lsp              — editor LSP server
roadmap-tool                — regenerates roadmap/README.md
```

Per-module `README.md` files are out of scope for this plan; the inline
table covers the orientation question without inviting per-module README
drift.

### 4. Add a one-line pointer to a clean Backlog → Done exemplar — *shipped*

`workflow.adoc`'s "Canonical path" section gains one sentence pointing at
the `computed-field-with-reference` close-out in `changelog.md` as a recent
worked example. The exemplar was picked by the `In Review` reviewer (an
independent agent session, not the docs author) per the rule encoded in the
original Spec: a single-feature `@externalField` → `ComputedField` lift
spanning classification, validation, generator emit, and execution-tier
coverage reads cleaner as "what does a clean cycle look like in practice?"
than the busier R53 / federation / R50 close-outs that surround it in the
changelog.

## Implementation status

- **Scope #2 (pipeline tour) and #3 (module map) landed** in
  `graphitron-rewrite/docs/README.adoc` together with the surrounding prose
  that turns the file into a standalone Architecture page (preamble, the
  module map "near the top", the pipeline tour, and the existing detailed
  reference list as a closing index). The pipeline tour text calls out the
  two non-obvious ordering invariants, `directives.graphqls` injection
  *before* classification and orphan sweep *after* every emit, that a
  contributor would otherwise have to reconstruct from `RewriteSchemaLoader`
  and `GraphQLRewriteGenerator.sweepOrphans` to understand why the stages
  can't be swapped.
- **Scope #4 landed** as a one-sentence pointer at the end of
  `workflow.adoc`'s "Canonical path" section; exemplar:
  `computed-field-with-reference`. All scopes are shipped; the plan is
  ready for `In Review → Done` close-out by a reviewer ≠ the implementers
  of #2/#3 (1b59f2e author) and ≠ #4 (this In Review flip).

## Out of scope

- Per-module READMEs. The inline table is the cheaper answer.
- Rewriting `code-generation-triggers.adoc`. Active item
  [`docs-as-index-into-tests.md`](docs-as-index-into-tests.md) covers that
  file. This plan should land before that one, so the two passes do not
  edit overlapping paragraphs.
- `runtime-extension-points.adoc`. The file already exists; reshaping its
  body, or deciding whether it joins the detailed-reference list at the
  bottom of `README.adoc`, is its own concern.
- Stale legacy references in other rewrite docs. Tracked separately in
  [`fix-legacy-refs-in-rewrite-docs.md`](fix-legacy-refs-in-rewrite-docs.md).
