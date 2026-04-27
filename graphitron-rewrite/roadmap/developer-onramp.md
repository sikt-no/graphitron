---
title: New-developer on-ramp pass
status: Backlog
bucket: cleanup
priority: 4
---

# Plan: New-developer on-ramp pass

A consolidating sweep over docs, READMEs, and a few high-touch source files so a
new contributor can go from "I cloned the repo" to "I can locate where to make
my change" without bouncing between four files. The rewrite is stabilising and
we have no external users yet, so this is the right window to fix the
documentation drift that has accumulated and to add the orientation pieces we
have been deferring.

The findings below come from a survey of `graphitron-rewrite/docs/`,
`graphitron-rewrite/roadmap/`, the rewrite source tree, and a reading of the
last ~50 commits on trunk. None of the items below is novel research; they are
captured here so they can land independently as small commits.

---

## A. Documentation drift (correctness)

These docs describe a state of the world that no longer matches the code. They
are the highest priority because they actively mislead readers.

### A1. `runtime-extension-points.md` describes the legacy runtime, not the rewrite

The doc currently says:

- "Location: `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java`"
  (line 19). The rewrite emits `GraphitronContext` per app under
  `<outputPackage>.rewrite.schema.GraphitronContext` via
  `GraphitronContextInterfaceGenerator`. There is no shared
  `graphitron-common` interface in scope.
- The example puts the context with the string key
  `Map.of("graphitronContext", new DefaultGraphitronContext(ctx))` and reads it
  with `env.getGraphQlContext().get("graphitronContext")` (lines 39, 49). The
  generator emits `env.getGraphQlContext().get(GraphitronContext.class)` (see
  `TypeFetcherGenerator.buildGraphitronContextHelper`), and
  `getting-started.md` already uses the typed-key form.
- The interface is shown with `getDataLoaderName(env)` as the third method
  (lines 25, 108-112). The actual generated interface has `getTenantId(env)`
  and Graphitron concatenates the tenant id with the field path to build
  DataLoader names internally.
- "See also" links to `graphitron-common/README.md` as the API reference
  (line 215) — also legacy.

**Action.** Rewrite around the rewrite-emitted interface: link to
`getting-started.md` for the wiring example, document the three actual methods
(`getDslContext`, `getContextArgument`, `getTenantId`), and lift the
RLS / `ExecuteListener` / multi-tenant `DSLContext` patterns underneath that.
Coordinate with the existing
[`graphitroncontext-extension-point-docs.md`](graphitroncontext-extension-point-docs.md)
backlog item: that one was filed before the runtime stabilised; either fold it
into this rewrite or close it after the doc lands.

### A2. Stale legacy paths in `code-generation-triggers.md` and design principles

- `code-generation-triggers.md:289` points at
  `graphitron-common/src/main/resources/directives.graphqls` for the directive
  SDL. The rewrite ships its own copy at
  `graphitron-rewrite/.../resources/directives.graphqls` (auto-injected by
  `RewriteSchemaLoader`, per changelog entry `c31771d`). Update the link.
- `rewrite-design-principles.md:113` says "builds the **five** rewrite modules
  (`graphitron-javapoet`, `graphitron`, `graphitron-fixtures`, `graphitron-maven`,
  `graphitron-test`)". The aggregator now has eight modules
  (`graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`,
  `graphitron-fixtures`, `graphitron-maven`, `graphitron-test`,
  `graphitron-lsp`, `roadmap-tool`). Update the count and list.

### A3. `graphitron-rewrite/docs/README.md` reads as a fragment

The file starts at numbered item **#4**, with no "what is graphitron-rewrite"
preamble. The numbering is intended to continue from
`/docs/README.md`, but a contributor following a roadmap link, a code search
hit, or a GitHub directory listing lands here without that context and sees
items 4-7 with no anchor.

**Action.** Either drop the numbering (renumber 1-4 within this file) and add
a one-paragraph "you are here" intro, or replace with a short section graph
that names the audience (contributors implementing generators) and lists the
docs by purpose, not position. Keep the link from `/docs/README.md` so the
"start here" path still works.

---

## B. Onboarding gaps (missing pieces)

### B1. No "30-second tour" of the pipeline

The rewrite docs cover principles, vocabulary, model diagrams, and individual
features, but there is no single page that walks the pipeline end-to-end:

```
.graphqls files
  → RewriteSchemaLoader (parse + inject directives.graphqls)
  → GraphitronSchemaBuilder (classify into GraphitronSchema)
  → GraphitronSchemaValidator (reject UnclassifiedField/Type, etc.)
  → Generators (TypeFetcherGenerator, TypeClassGenerator, …)
  → JavaFile.writeToPath (idempotent writes + sweep)
  → consumer compile
```

`code-generation-triggers.md` has a small four-line diagram inside the
"How Classification Works" section, but it stops at the boundary of
`GraphitronSchema → Generators` and does not name the validator, the schema
loader, or the writer-and-sweep contract.

**Action.** Add a top-of-`docs/README.md` (or new `docs/pipeline.md`) section
with the labelled diagram, two sentences per stage, and a "the only place
that …" anchor for each (e.g. "the only place that reads directives" → builder).
Cross-link from each detail doc.

### B2. No module map

Eight modules under `graphitron-rewrite/`. Only `graphitron-javapoet` has a
README. A new contributor cannot answer "which module owns X?" without grepping
poms.

**Action.** A short table somewhere near the top of `graphitron-rewrite/docs/README.md`:

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

Decide separately whether per-module `README.md` files carry their weight; the
mini-table inline in the docs index probably does most of the work.

### B3. No "anatomy of a generated file" walkthrough

Readers know `*Fetchers`, `*Type`, `*Conditions` exist (per
`code-generation-triggers.md`) but do not have an annotated example of any
single emitted file. A small fixture (e.g. the Sakila `Film` `*Fetchers`)
captured as a static doc with section-by-section commentary would compress
the "what does the output actually look like?" question into one read.

**Action.** Add `docs/generated-output-walkthrough.md` with a frozen snippet
plus annotations. Keep it small; link to a real generated file under
`graphitron-test/target/` for the full picture, with a note that the snippet
may drift and the test suite is the source of truth.

### B4. Test-tier guide is implicit

`rewrite-design-principles.md` documents the test pyramid (unit, pipeline,
compilation against jOOQ, execution against Postgres) and bans body-string
assertions. `claude-code-web-environment.md` documents the build commands.
Per-test conventions live in javadoc on individual test classes. There is no
single doc that says "if you are adding test X, here is which tier it goes in,
what shape it takes, and what you can/cannot assert."

**Action.** A short `docs/testing.md` cross-referencing the existing
material; Active item
[`rebalance-test-pyramid.md`](rebalance-test-pyramid.md) covers the policy
side. The on-ramp gap here is purely "where does this test belong and how
do I write it."

### B5. Workflow doc is good; an exemplar pointer would close the loop

`workflow.md` describes the canonical Backlog → Spec → Ready → In Progress →
In Review → Done path. Pointing at one or two recent small examples (e.g. the
`@asConnection` totalCount thread, captured in changelog entry at the top of
[`changelog.md`](changelog.md)) as "this is what a clean cycle looks like"
would help newcomers calibrate plan size and commit style without rummaging
through git.

---

## C. Code shape (readability)

### C1. Class-level Javadoc is thin on the central builders

`GraphitronSchemaBuilder` has a good 11-line top comment naming itself as the
directive-reading boundary. `FieldBuilder` (2 172 lines) and `TypeBuilder`
(709 lines) have only a couple of lines each. A new contributor opening
`FieldBuilder.java` to find "where is `@service` classified?" gets a long file
with minimal orientation.

**Action.** Three or four sentences at the top of each: scope, the entry-point
methods, the model output, and a pointer to
[`code-generation-triggers.md`](code-generation-triggers.md). Same treatment
for `BuildContext`, `JooqCatalog`, `ServiceCatalog`, and the Generator-suffix
classes.

### C2. No `package-info.java` files

None of the rewrite packages (`model`, `generators`, `generators/schema`,
`generators/util`, `selection`, `schema`, `catalog`) have a
`package-info.java`. That is the IDE-native place for orientation; a reader
hovering on a package import gets nothing today. Two-or-three-line blurbs per
package, linking to the relevant doc, would carry their weight.

### C3. `TypeFetcherGenerator` is a 1 646-line single class

Counterpart to the existing
[`decompose-fieldbuilder.md`](decompose-fieldbuilder.md) backlog item.
`TypeFetcherGenerator` has one public entry point and a sealed switch
delegating into ~30 private methods, several of which are long enough to
warrant their own files. Worth deciding whether a follow-up decomposition
plan is justified, or whether method-level Javadoc + a `## Layout` section
in the class doc is sufficient. (Today's Active
[`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md) is
adding more methods here; the question is timing, not direction.)

### C4. The `selection/` hand-rolled lexer/parser has no contributor-facing context

Backlog item [`selection-parser-audit.md`](selection-parser-audit.md) covers
the question of whether to keep it. Independent of that, a one-paragraph
package note explaining what it parses and why graphql-java's parser is
insufficient would prevent a new contributor from getting lost in `Lexer.java`
when looking for argument-resolution code. Folds into B1 / C2.

---

## D. Cross-cutting

### D1. Coordinate with related Active and Backlog items

To avoid duplication when this item is picked up:

- A1 supersedes parts of
  [`graphitroncontext-extension-point-docs.md`](graphitroncontext-extension-point-docs.md);
  pick one to keep.
- B4 leans on
  [`rebalance-test-pyramid.md`](rebalance-test-pyramid.md) (Backlog) for
  policy; this item only surfaces existing rules in one place.
- B1 and the still-Ready
  [`docs-as-index-into-tests.md`](docs-as-index-into-tests.md) both touch
  `code-generation-triggers.md`. The on-ramp pass should land before the
  test-index rewrite so we do not edit the same paragraphs twice.
- C3 should be deferred until Active stub work
  ([`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md))
  finishes touching `TypeFetcherGenerator`.

### D2. Splitting strategy

Items A1, A2, A3 are small and independent; each is a one-commit fix.
B1, B2, B3, B4, B5 are doc additions; they can land independently.
C1, C2, C4 are mechanical sweeps and can land as one commit each.
C3 is a separate planning question and should not block the rest.

This plan is explicitly a list of independently-landable threads; it does not
need to ship as one PR. Promote the highest-leverage subset (A1, A3, B1, B2)
to Spec first; everything else can stay in this Backlog file as a follow-up
checklist.

---

## Out of scope

- **Rewriting `argument-resolution.md` for general readability.** It is dense
  but accurate; rework should be a separate plan.
- **Per-directive reference docs.** That is the legacy
  `graphitron-codegen-parent/graphitron-java-codegen/README.md`'s job; until
  the rewrite owns its own directive reference, we link to the legacy file.
- **A "first commit" tutorial.** Workflow doc + an exemplar pointer (B5)
  is enough; tutorials drift faster than reference docs.
