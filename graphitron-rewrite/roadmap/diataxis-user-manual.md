---
id: R68
title: "Diataxis user manual: absorb legacy README into the docs site"
status: Ready
bucket: architecture
priority: 15
theme: docs
depends-on: []
---

# Diataxis user manual: absorb legacy README into the docs site

## Problem

The canonical user-facing reference for Graphitron is still the legacy
`graphitron-codegen-parent/graphitron-java-codegen/README.md`: ~1900 lines of
features, Maven configuration, every directive, dozens of worked examples for
`@condition`, the polymorphism patterns, sorting, global node identification,
mutations, services, the lot. It is comprehensive, but it sits in a module
that this repo's AI-development scope (`CLAUDE.md`) excludes from edits, so
every fix has to leave the rewrite branch and round-trip through the legacy
modules. New rewrite-only directives (`@asConnection`, `@key`, the
`@externalField` rename in R54, faceted search in R13) have nowhere to land.
Worse, none of it is on the deployed site: `docs/quick-start.adoc` points
readers _back_ at the legacy README on GitHub for directive details, breaking
the "one source of truth" goal of R9.

The deployed site (R9, In Progress) has a hero, principles, FAQ, vision,
security, dependencies, and a thin quick-start. Together these are ~6 short
prose pages. The rewrite-internal docs at `graphitron-rewrite/docs/` are
contributor-facing (architecture, classification, design principles, runtime
extension points, dev loop). There is no user manual: nothing that takes a
beginner from zero to a working query, no structured how-to recipes for the
dozen-plus day-to-day tasks the directives unlock, and no reference page
that enumerates every directive without leaving the site.

This plan establishes a Diataxis-shaped user manual on the deployed site
that absorbs the legacy README in full, exceeds it on the dimensions the
legacy doc is weak on (orientation, learning path, cross-links, drift
prevention), and becomes the canonical home for every directive and every
Mojo configuration option. Once it lands, the legacy README's content is
fully covered on the site and that file becomes a stub redirect.

This plan composes with `R67` (`rewrite-example-quarkus-jaxrs`), which
promotes the rewrite's `graphitron-test` module into a public
`graphitron-sakila-example` with a Quarkus runtime and a documented
consumer test pattern (`src/test/java/.../querydb/`). R67 is the *runnable
artifact* a reader copies; R68 is the *manual* that teaches them to. The
tutorial chapter rebuilds R67's example incrementally on a clean
checkout (page 1: prerequisites; page 2: the starter schema; ...); the
how-to recipes cross-link "verified by" pointers into R67's `querydb/`
tests rather than into a test-internal module name. The two plans are
deliberately split: R67 is the artifact, R68 is the docs.

## What Diataxis is, in two paragraphs

Diataxis (https://diataxis.fr) organises technical documentation into four
quadrants, each serving a different reader need:

- *Tutorials* (learning-oriented). Step-by-step lessons that take a complete
  beginner from zero to a small but real working result. Reader trusts the
  author; success is measured in "did the reader finish and feel competent
  to continue".
- *How-to guides* (task-oriented). Recipes for solving specific problems the
  reader already knows they have. Assumes basic competence; success is "did
  the reader achieve the stated outcome".
- *Reference* (information-oriented). Authoritative, complete, neutral
  description of the system: every directive, every parameter, every Mojo
  option. Reader knows what they are looking up; success is "is the answer
  here, accurate, and findable".
- *Explanation* (understanding-oriented). Background and rationale: design
  decisions, comparisons with alternatives, conceptual framing. Reader is
  trying to build a mental model; success is "does the reader leave with a
  better grasp of the why".

The four quadrants are kept separate because mixing them produces docs that
serve no reader well, the failure mode the legacy README hits: it interleaves
"here is a worked example for a beginner" with "here is the exhaustive
parameter list" with "here is the rationale for our split-query model" with
"here is the constraint on lookupKey". A reader scanning for the parameter
list reads the worked example anyway; a beginner picking up the doc gets
buried in edge cases.

## What we have today

- `/docs/index.adoc`, `/docs/quick-start.adoc`,
  `/docs/vision-and-goal.adoc`, `/docs/graphitron-principles.adoc`,
  `/docs/dependencies.adoc`, `/docs/security.adoc`, `/docs/faq.adoc`. Six
  short pages; mostly Diataxis *Explanation*. No tutorial path, no how-tos,
  no reference.
- `/graphitron-rewrite/docs/getting-started.adoc`. Worked snippets for the
  rewrite runtime: hello world, scalars, federation, dev loop. This is
  Diataxis *How-to* in spirit but lives in the contributor section of the
  site, not the user manual section. Solid material to lift forward.
- `/graphitron-rewrite/docs/argument-resolution.adoc`,
  `/graphitron-rewrite/docs/code-generation-triggers.adoc`,
  `/graphitron-rewrite/docs/runtime-extension-points.adoc`. Mixed
  reference/explanation, but contributor-facing. Some pieces (the
  classification truth tables, the directive triggers) double as user-facing
  reference if reframed; most stays internal.
- `/graphitron-codegen-parent/graphitron-java-codegen/README.md`. The legacy
  master reference. Out of edit scope for AI work, but in scope to *read*
  and *port* on this plan.
- `/graphitron-example/README.md`. Legacy Sakila-backed app on the
  `graphitron-servlet` runtime. After R67 ships this is "courtesy
  reference for in-flight migrators only" and `quick-start.adoc` no longer
  points at it; the new tutorial chapter does *not* anchor on this module.
- *Post-R67* `graphitron-rewrite/graphitron-sakila-example/`. The
  rewrite-flavoured public example with a Quarkus runtime, an
  `application.yaml`, and a documented test pattern under
  `src/test/java/.../querydb/`. This is the tutorial's anchor project and
  the cross-link target for "verified by" pointers in the how-to recipes.
  R67 is in scope for *consuming* on this plan, not authoring; R68 simply
  picks up the artifact R67 ships.

## Target information architecture

The user manual lives under the existing `/docs/` AsciiDoc tree (the home of
the published site, deployed by R9) and adds five top-level sections,
exactly mirroring the Diataxis quadrants plus an introduction:

```
/docs/
├── index.adoc                    # site landing (existing)
├── manual/
│   ├── index.adoc                # what the manual is, who it's for, how to navigate
│   ├── tutorial/
│   │   ├── index.adoc            # path overview
│   │   ├── 01-prerequisites.adoc
│   │   ├── 02-first-schema.adoc
│   │   ├── 03-first-query.adoc
│   │   ├── 04-joining-tables.adoc
│   │   ├── 05-mutations.adoc
│   │   └── 06-going-further.adoc
│   ├── how-to/
│   │   ├── index.adoc            # task-indexed map
│   │   ├── map-types-to-tables.adoc
│   │   ├── join-with-references.adoc
│   │   ├── add-custom-conditions.adoc
│   │   ├── handle-services.adoc
│   │   ├── batch-lookups.adoc
│   │   ├── pagination-and-sorting.adoc
│   │   ├── polymorphic-types.adoc
│   │   ├── error-handling.adoc
│   │   ├── global-node-identification.adoc
│   │   ├── computed-fields.adoc
│   │   ├── apollo-federation.adoc
│   │   ├── custom-scalars.adoc
│   │   ├── tenant-scoping.adoc
│   │   └── test-your-schema.adoc      # post-R67: the documented querydb/ pattern
│   ├── reference/
│   │   ├── index.adoc            # alphabetical directive index, Mojo index
│   │   ├── directives/
│   │   │   ├── table.adoc
│   │   │   ├── field.adoc
│   │   │   ├── reference.adoc
│   │   │   ├── multitableReference.adoc
│   │   │   ├── splitQuery.adoc
│   │   │   ├── notGenerated.adoc
│   │   │   ├── condition.adoc
│   │   │   ├── enum.adoc
│   │   │   ├── mutation.adoc
│   │   │   ├── service.adoc
│   │   │   ├── tableMethod.adoc
│   │   │   ├── error.adoc
│   │   │   ├── externalField.adoc       # → computedField rename per R54
│   │   │   ├── lookupKey.adoc
│   │   │   ├── orderBy.adoc
│   │   │   ├── defaultOrder.adoc
│   │   │   ├── order.adoc
│   │   │   ├── discriminate.adoc
│   │   │   ├── discriminator.adoc
│   │   │   ├── node.adoc
│   │   │   ├── nodeId.adoc
│   │   │   ├── record.adoc
│   │   │   ├── experimental_constructType.adoc
│   │   │   ├── asConnection.adoc        # rewrite-only; faceted search per R13
│   │   │   ├── key.adoc                 # rewrite-only; federation key
│   │   │   └── ...                      # one file per surviving directive
│   │   ├── mojo-configuration.adoc      # full Maven plugin parameter reference
│   │   ├── runtime-api.adoc             # Graphitron, GraphitronContext, NodeIdStrategy
│   │   ├── special-interfaces.adoc      # Node, Error
│   │   ├── diagnostics-glossary.adoc    # AUTHOR_ERROR codes, common failure messages
│   │   └── deprecations.adoc            # generated from @Deprecated on the model
│   └── explanation/
│       ├── index.adoc
│       ├── why-database-first.adoc      # cross-link to graphitron-principles
│       ├── how-it-works.adoc            # the pipeline at 30 seconds, user-facing
│       ├── why-jooq-and-graphql-java.adoc # cross-link to dependencies
│       ├── batching-model.adoc          # how DataLoaders are wired and why
│       ├── classifier-mental-model.adoc # what the classifier does, in user terms
│       └── design-decisions.adoc        # why @condition methods take a table; why
│                                         # @lookupKey forbids two-layer lists; etc.
└── (existing top-level pages stay where they are; the manual links *into* them
   from explanation/ where appropriate)
```

The shape is intentionally mechanical: one directive, one reference page;
one task, one how-to. Long pages encourage drift; the legacy README's
current "scroll for 1900 lines" UX is exactly what we are leaving behind.

The four-quadrant split is exposed in the top nav: a new "Manual" item
sits between "Quick Start" and "Architecture", and its landing page is a
visible map of the four quadrants with one-line descriptions of each.
Quick Start stays as the existing high-level pre-tutorial orientation page;
the manual's tutorial chapter is what readers move to when they want the
guided experience.

## How this exceeds the legacy README

The legacy README is a single 1900-line file with a doctoc-generated TOC.
That structure has known weaknesses; the new manual addresses each:

. *No learning path*. Legacy starts at "Features" then jumps to a
  configuration section; a true beginner has nowhere to begin. The manual's
  tutorial chapter takes a reader from "git clone" to "I have a query
  resolving". The example project is the spine; the tutorial rebuilds
  it incrementally, one directive per page.
. *Worked examples interleave with parameter lists*. Legacy `@condition`
  alone has 12 worked examples woven into the parameter description. The
  manual splits them: `reference/directives/condition.adoc` has parameters
  and the canonical example only; `how-to/add-custom-conditions.adoc` has
  the recipe-shaped variants ("I want to override only the input parameter
  condition", "I want to add a method that runs after default conditions").
  The how-to cross-links to the reference and vice versa.
. *No machine-checked drift protection*. Legacy directive examples have
  rotted (some now mention deprecated `RowN` keys, the `@nodeId service
  unsupported` note is stale, etc.). The manual's reference pages are
  pinned to the rewrite's own directive registry by a build-time coverage
  check (see Phase 2) so a directive added to the model fails the build
  until its reference page lands, and an `<name>.adoc` for a removed
  directive fails the build until it is deleted.
. *No cross-links into source-of-truth tests*. Each how-to ends with a
  "verified by" pointer into the rewrite test suite (the same pattern R8 is
  building for the classification doc). A reader who wants to be sure the
  example actually compiles runs the named test. Post-R67, the test
  surface a how-to links to is *public-facing*: it lives under
  `graphitron-sakila-example/src/test/java/.../querydb/`, with a README
  that explicitly names it as the recommended consumer test pattern. The
  legacy README has nothing comparable to point at: its examples cite no
  test at all, and the tests that do exercise them sit in legacy modules
  with no narrative explaining "this is the pattern, copy it."
. *No diagnostics glossary*. When a build fails with `AUTHOR_ERROR:
  REFERENCE_PATH_NOT_FOUND`, the legacy README has nothing to say. The
  manual ships `reference/diagnostics-glossary.adoc`, an alphabetical list
  of every error code emitted by the validator with one paragraph of
  guidance per code. The list is generated from the validator's enum so it
  cannot drift.
. *No deprecation surface*. The legacy README has scattered "this will be
  removed in 9.0.0" warnings with no central index. The manual surfaces
  every deprecated directive parameter in `reference/directives/<name>.adoc`
  under a "Deprecated parameters" section, and aggregates them in a single
  `reference/deprecations.adoc` table.
. *Federation, Mojo, runtime API are split across three files in two
  modules*. The manual gathers them in `reference/mojo-configuration.adoc`
  and `reference/runtime-api.adoc`, with the federation-specific bits
  cross-linked from `how-to/apollo-federation.adoc`.

The legacy README is the upper bound on content; the manual is the upper
bound on _findability_, _accuracy_, and _learnability_.

## Implementation

The implementation is multi-phase because each phase ships independently
useful artifacts and has different drift-protection seams. The phases are
not sub-PRs; each is one or more commits depending on what bundles cleanly.

### Phase 1a: Scaffold (IA-only) — shipped at `3afc278`

Five `.adoc` files under `/docs/manual/` plus wiring: four-quadrant
landing (`.quadrants` 2x2 table) at `/docs/manual/index.adoc`,
placeholder index pages in each of `tutorial/`, `how-to/`, `reference/`,
`explanation/`. The `reference/` index doubles as the directive-index
read-simply check (alphabetical + by-category, two compact lists side
by side). `/docs/index.adoc` grew a Manual row in the Documentation
table; `/docs/_theme/docinfo-header.html` grew a Manual nav link
between Quick start and FAQ; `/docs/_theme/site.css` added
`.quadrants`/`.quadrant-title` styles plus the responsive collapse.
`/docs/pom.xml` extended `stage-adoc` to include `manual/**/*.adoc`
and the antrun task to fan CSS + docinfo into each new subdirectory.

Read-simply test runs on the deployed
`https://sikt-no.github.io/graphitron/manual/` once the next deploy
lands. If the four-quadrant page or the directive index fails the
test, revise here in 1a; do not start 1b until both pass.

### Phase 1b: Tutorial prose and Quick Start rework

Author the tutorial chapter once Phase 1a's IA has cleared the
read-simply test. Files:

- `/docs/manual/tutorial/` six pages, anchored to
  `graphitron-rewrite/graphitron-sakila-example/`. The tutorial's goal
  is "a reader who has only run `mvn -version` finishes with a query
  like `customers { firstName email address { addressLine1 } }`
  returning real data, served over HTTP via `mvn quarkus:dev`." Each
  page ends with a "you have just learned" line and a "next" link to
  the following page. Page-by-page: prerequisites and `mvn quarkus:dev`;
  the three-table starter schema with directives; running the first
  query in the Quarkus dev UI; adding `@reference` to traverse to a
  joined table; one mutation via `@mutation`; and a "going further"
  page that points at the four how-to recipes most relevant to the
  worked example (`add-custom-conditions`, `pagination-and-sorting`,
  `error-handling`, `test-your-schema`).
- `/docs/quick-start.adoc`: surface a "Tutorial" link as the
  recommended first stop after Quick Start. R67's Stage 3 already
  repointed `quick-start.adoc:21,64` from the legacy example to
  `graphitron-sakila-example`; Phase 1b picks the page up from there
  and keeps it a true *orientation* page (~one screen).
- `/docs/manual/tutorial/index.adoc`: replace the placeholder
  paragraph from 1a with a real path overview.
- The tutorial smoke test from [Tests](#tests) lands in 1b's last
  commit, so the smoke test is real before the manual claims a
  working tutorial.

Phase 1b ships when a beginner can complete the tutorial against
`graphitron-sakila-example` on a clean checkout: `mvn install
-Plocal-db` once, then `mvn quarkus:dev` from the example module,
then follow the six pages to a working query. No rewrite of legacy
content yet; the tutorial is new prose because the legacy README has
nothing tutorial-shaped to lift.

### Phase 2: Reference (directives) absorbs the legacy README — shipped at `8f7d412`

Port every legacy README directive section into `/docs/manual/reference/
directives/<name>.adoc`, one file per directive. Each page has a fixed
shape:

- *Synopsis* (one sentence)
- *SDL signature* (the directive declaration as it appears in
  `directives.graphqls`)
- *Parameters* table (name, type, default, description)
- *Canonical example* (one schema snippet, one generated-output snippet,
  with a callout cross-linking to the test that asserts it)
- *Constraints* (validator rules in plain prose, with the validator-test
  link)
- *See also* (link to the matching how-to page, link to the explanation if
  the design decision is non-obvious)
- *Deprecated parameters* (only if any exist; explicitly marked)

The directive list to port comes from the rewrite's `directives.graphqls`
(the canonical file the loader auto-injects). A new build-time check
asserts coverage between that file (plus the classifier's directive
registry) and the contents of `reference/directives/`; the check fails if
a directive exists in the schema but has no `<name>.adoc` page, or if a
`<name>.adoc` exists for a directive not in the schema. This is the
drift-protection seam: a future PR that adds a directive cannot land
green without adding the doc page. `reference/directives/index.adoc`
itself is hand-curated (its categorical view is editorial; see the
first-client-check section) and Phase 1a ships its first draft for the
read-simply test before any directive page is authored in Phase 2.

The check belongs in the docs build (the `asciidoctor-maven-plugin`
execution defined by R9) so doc breakage fails the same `mvn install` that
builds everything else. Implementation is straightforward: the rewrite
already exposes the directive registry; we add a small `roadmap-tool`-style
verifier or a separate `docs-verifier` module reading the registry and the
file system.

This phase ships when the deployed site has every legacy-README directive
section reproduced as a focused reference page, plus the verifier preventing
new drift.

### Phase 3: How-to recipes

Port the worked examples and sequenced material out of the legacy README
into task-shaped how-to pages. For each how-to, the source material is a
specific stretch of the legacy README plus, where relevant, the
`getting-started.adoc` snippets that already exist for the rewrite runtime:

- `map-types-to-tables.adoc` ← legacy "Tables, joins and records"
- `join-with-references.adoc` ← legacy "@reference" + "@multitableReference"
- `add-custom-conditions.adoc` ← legacy "@condition" examples (12 of them)
- `handle-services.adoc` ← legacy "@service" + nested-input + Java records +
  context arguments + response mapping. This is the densest legacy section
  (~200 lines); it splits into recipe-shaped sub-sections rather than one
  monolith.
- `batch-lookups.adoc` ← legacy "@lookupKey"
- `pagination-and-sorting.adoc` ← legacy "Sorting" + the rewrite's
  `@asConnection` + faceted-search work as it lands (R13)
- `polymorphic-types.adoc` ← legacy "Polymorphic queries"
- `error-handling.adoc` ← legacy "@error" + `Error` interface
- `global-node-identification.adoc` ← legacy "@node" / "@nodeId" / `Node`
- `computed-fields.adoc` ← legacy "@externalField"; renamed inline per R54
  when that ships
- `apollo-federation.adoc` ← rewrite `getting-started.adoc` federation
  section, lifted forward and expanded
- `custom-scalars.adoc` ← rewrite `getting-started.adoc` scalar section
- `tenant-scoping.adoc` ← rewrite `getting-started.adoc` tenant section
- `test-your-schema.adoc` ← *new prose*: the recommended consumer test
  pattern. Anchored on R67's `graphitron-sakila-example/src/test/java/.../querydb/`,
  this recipe walks a reader through (1) wiring a `Graphitron`-built
  schema into a JUnit test, (2) executing GraphQL via `graphql-java`
  in-process, (3) asserting against rows pulled from the live `DSLContext`,
  and (4) approval-style snapshots for queries that return more than a
  handful of rows. The legacy README has no equivalent; this is one of
  R68's net-new contributions, made possible by R67's documented surface.

Each how-to is recipe-shaped (problem → solution → variations → caveats →
links) and stays under ~300 lines. The "verified by" pointer at the foot of
each page links to the test that exercises the recipe end-to-end. The
preferred target is a test in `graphitron-sakila-example/src/test/java/.../querydb/`
because that directory is the public test surface a reader would copy;
fall back to `graphitron-sakila-service/` (services + conditions
fixtures) or `graphitron-sakila-db/` (catalog + DDL) when the recipe
exercises generator-internal mechanics that don't have a public-facing
test. R8's work on classification-test cross-links is a useful
precedent; this phase extends that pattern from contributor docs to user
docs.

This phase ships per-page; the whole set is not one big-bang commit.

### Phase 4: Explanation and Mojo / runtime reference

Two parallel deliverables:

- *Explanation pages*. The `explanation/` chapter. New material plus
  cross-links into the existing `graphitron-principles`, `dependencies`,
  `security`, `vision-and-goal` pages. The new chapters cover reader-level
  framing of the pipeline (lighter than `code-generation-triggers.adoc`),
  the batching/DataLoader model, and the design decisions that surface in
  user-visible behaviour (why `@condition` methods take a table; why
  `@lookupKey` forbids nested lists; why mutations require `@table` on the
  input type). Some material lifts from
  `graphitron-rewrite/docs/rewrite-design-principles.adoc`, but rewritten
  for users (no "the classifier emits an `UnclassifiedField`" jargon).
  *Shipped at `6042e66`.* Six pages: `why-database-first.adoc`,
  `why-jooq-and-graphql-java.adoc`, `how-it-works.adoc`,
  `classifier-mental-model.adoc`, `batching-model.adoc`,
  `design-decisions.adoc`, plus an updated `index.adoc` mapping them.
  No verifier, by intent: explanation prose is curated voice, not a
  surface that drifts mechanically against the code (the directive
  reference, Mojo reference, diagnostics glossary, and deprecations
  index are the ones that need verifiers; explanation cross-links into
  them).
- *Mojo + runtime reference*. `reference/mojo-configuration.adoc` lists
  every Maven plugin parameter (legacy "General settings", "Validation",
  "Query generation", "Code references" plus the rewrite-mojo additions
  R18 lands). `reference/runtime-api.adoc` covers the generated
  `Graphitron`, `GraphitronContext`, and `NodeIdStrategy` surface. The
  Mojo parameter list is verified at build time against the Mojo's own
  `@Parameter`-annotated fields (see Tests). The runtime-API page has no
  comparable verifier: the runtime classes are emitted into consumer
  generated-sources by `GraphitronContextInterfaceGenerator` and the
  schema generators, and there is no "runtime module" to read from. The
  page is hand-written prose; if drift becomes a real problem, a
  follow-up can lift signatures from `graphitron-sakila-example`'s
  generated `Graphitron.java` via an AsciiDoc include.
  *Shipped at `868593a` (runtime-api) and `d796c4c` (mojo + verifier).*

Phase 4 status: both halves shipped (mojo + runtime at `868593a` / `d796c4c`; explanation at `6042e66`).

### Phase 5: Diagnostics glossary and deprecation index — shipped

`reference/diagnostics-glossary.adoc`: alphabetical list of every error
code the validator emits, with one paragraph of guidance per code. The
list is generated from the validator's enum (`Diagnostic` /
`AuthorErrorCode` or its successor) so a new code added in the rewrite
lands in the doc automatically and an obsolete code disappears.
*Shipped at `3f6ec55`*; the closed-set surface is `RejectionKind` (3) +
`Rejection.AttemptKind` (9) + `Rejection.EmitBlockReason` (4) = 16
codes after the R58 lift to the sealed `Rejection` hierarchy. The
verifier (`DiagnosticsDocCoverageTest`) reflects on the three enums
and asserts bidirectional coverage.

`reference/deprecations.adoc`: aggregator table of every deprecated
directive parameter with target removal version. Generated from the
`@Deprecated` annotations on the directive-classification model.
*Shipped at `23c2056`*. Two deviations from the plan: (1) the rewrite's
source of truth is the SDL `@deprecated()` marker in
`directives.graphqls`, not Java `@Deprecated` annotations on a
classification model; the legacy classification model lives in the
out-of-AI-scope legacy modules. (2) The plan's "target removal version"
column is dropped: the rewrite's `@deprecated(reason:)` markers do not
carry a structured removal version, and there is no separate
directive-surface versioning cadence to anchor it on. The page leads
with an honest deviation note acknowledging both. The verifier
(`DeprecationsDocCoverageTest`) extracts qualified
`<parent>.<member>` keys from the SDL and asserts every key has a row;
whole-directive deprecations (which the GraphQL spec disallows
`@deprecated` on) are covered via a small allow-list (currently
`@index`).

Both pages are the natural successors of the failure modes the legacy
README cannot handle (no centralised error reference, scattered deprecation
notes); the rewrite's classifier-driven model makes them cheap to keep
honest because the source of truth is code.

### Phase 6: Cutover and stub

Gated on Phase 2 only; can land before Phases 3-5 complete. When Phase 2
ships, `/docs/quick-start.adoc`'s pointer to the legacy README on GitHub
(line 15: "the directive reference is in the source repository...") flips
to the new manual reference. The legacy README itself is out of scope for
AI edits, but the user (a Sikt maintainer) can update it in a companion
legacy-modules commit to a one-paragraph "this directive reference has
moved to graphitron.sikt.no/manual/reference/" stub. That companion
commit lives outside this plan; this plan is responsible for making the
migration possible by ensuring the new reference is complete and correct.

## Tests

The drift-protection seams are the tests:

. *Directive coverage* (Phase 2). New `DirectiveDocCoverageTest` (in the
  docs-verifier module if we build one, otherwise in
  `graphitron-sakila-example` per R67's renaming) asserts every directive
  in `directives.graphqls` has a matching
  `reference/directives/<name>.adoc` and vice versa. Failure prints the
  missing pages or stale files.
. *Mojo parameter coverage* (Phase 4). `MojoDocCoverageTest` asserts every
  `@Parameter`-annotated field on the rewrite Mojo class has a row in
  `reference/mojo-configuration.adoc`'s parameter table.
. *Diagnostics coverage* (Phase 5). `DiagnosticsDocCoverageTest` asserts
  every `AuthorErrorCode` enum case has a paragraph in the glossary.
. *AsciiDoc cross-link integrity*. The asciidoctor-maven-plugin already
  fails the build on broken `xref:` and missing includes (this is what R9
  buys us); the only addition here is making sure the build is wired into
  CI, which R9 already covers.
. *Tutorial smoke test*. A `tutorial-smoke-test` module copies the tutorial
  chapter's commands into a shell script and runs them against a clean
  checkout in CI. The script's shape mirrors the tutorial pages
  one-for-one: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`,
  then a `mvn quarkus:dev` invocation backgrounded against the
  `graphitron-sakila-example` module, then a `curl -X POST` of the
  tutorial's first query against `localhost:8080/graphql`, then a kill of
  the dev server. If a tutorial step references `mvn` flags, an HTTP
  endpoint shape, or a directive that no longer exists, the smoke test
  breaks before the docs ship. This is the heaviest-weight test and is
  deferred to Phase 1b (after the tutorial prose exists to test
  against); the test is added in Phase 1b's last commit.

These tests do not duplicate the existing classification, validation, or
codegen tests; they only cross-check that documented surface = code
surface. The unit/pipeline/compilation/execution tier separation
(`graphitron-rewrite/docs/testing.adoc`) places them in the unit tier:
they run on string sets, not generated code.

## Roadmap entries

This plan is `R68`. Related items:

- `R9` (`docs-site-asciidoc`, In Progress): provides the AsciiDoc build
  and the deployment to GitHub Pages. R9's content phases (1-4) have
  shipped (`a4675bf`, `c38ea0f`, `562e732`, `aa3511e`); the deployed
  site at `sikt-no.github.io/graphitron/` is the host R68 writes into.
  R9's remaining phases (5a DNS cutover, 5b old-infra decommission) are
  Sikt platform work and do not gate R68 — moving to a custom domain
  doesn't change what we author. Hence `depends-on: []`, not R9.
- `R67` (`rewrite-example-quarkus-jaxrs`, Spec): renames `graphitron-test`
  → `graphitron-sakila-example`, splits `graphitron-fixtures` into
  `graphitron-sakila-db` + `graphitron-sakila-service`, and adds a Quarkus
  runtime plus a documented `querydb/` consumer test pattern to the
  example module. R68's tutorial chapter anchors on the post-R67 example;
  R68's how-to recipes use post-R67 module names and cross-link into
  `querydb/`. Because R67 also repoints `quick-start.adoc:21,64`, R68
  Phase 1b's Quick Start rework picks up the post-R67 page rather than
  competing with R67 on the same lines. R67 has shipped, so this
  dependency is satisfied. Phase 1a (IA scaffold) does not anchor on
  the example module either way; Phase 1b (tutorial prose) and Phase 3
  (how-to recipes) do.
- `R8` (`docs-as-index-into-tests`, Ready, deferred): the cross-link-to-
  tests pattern R68 reuses. R68 generalises R8's "doc points into tests"
  to the user-manual surface.
- `R15` (`fix-legacy-refs-in-rewrite-docs`, Spec): unrelated drift sweep
  in contributor docs. Surfaces the same problem (doc rot) at the
  contributor level; R68 surfaces it at the user level.
- `R17` (`generated-output-walkthrough`, Backlog): an annotated walkthrough
  of one generated file, contributor-facing. R17's content does not move;
  if it is judged user-relevant after R68 Phase 4 ships, lift the
  walkthrough into `manual/explanation/`.
- `R54` (`rename-externalfield-directive`, Backlog): the
  `@externalField` → `@computedField` rename. R68 Phase 2 names the
  directive page from `directives.graphqls` so the rename is automatic;
  the how-to page filename changes once.
- `R13` (`faceted-search`, Spec): adds faceted search to `@asConnection`.
  Phase 3's `pagination-and-sorting.adoc` should not block on R13; it
  documents the surface that exists at write time and grows when R13
  ships.
- `R18` (`graphitron-lsp`, Ready): introduces the new Mojo. Phase 4's
  Mojo reference pulls from the new Mojo, not the legacy one; ordering
  matters only if the new Mojo's parameter set differs materially from
  the legacy one.

## User documentation (first-client check)

The artifact this plan produces _is_ the user docs, so the first-client
check is intrinsic: each phase ships exactly the docs it describes. Two
specific drafts that need to read simply for the design to be sound:

. The four-quadrant landing page (`/docs/manual/index.adoc`) must let a
  reader land cold and find the right quadrant in three seconds. Sketch:

   ```adoc
   = Graphitron User Manual

   This manual covers Graphitron from four angles. Pick the one that
   matches what you're trying to do right now.

   [.quadrants,cols="1,1",frame=none,grid=none]
   |===
   | xref:tutorial/index.adoc[Tutorial]
   | xref:how-to/index.adoc[How-to guides]
   | "I'm new and want to learn by doing."
   | "I have a specific task and want a recipe."

   | xref:reference/index.adoc[Reference]
   | xref:explanation/index.adoc[Explanation]
   | "I know what I'm looking for and want the facts."
   | "I want to understand why Graphitron works the way it does."
   |===
   ```

   If a reviewer cannot self-route from this page in three seconds, the
   IA is wrong and Phase 1a's index has to change before Phase 1b
   (tutorial prose) or Phase 2 (directive reference) starts.

. The directive reference index (`reference/directives/index.adoc`) must
  surface the alphabetical list and a "by category" view (mapping,
  joining, querying, mutating, sorting, error-handling, federation,
  global-id) without the reader scrolling. The legacy README's TOC is the
  failure mode: alphabetical or categorical, never both, and 80 entries
  deep. The new index uses two compact tables side by side.

Both drafts land in Phase 1a, before any tutorial prose or directive
page is authored. If either one fails the read-simply test, the IA
changes inside Phase 1a; Phase 1b does not start until both pass.

## Pending content additions

Documentation gaps surfaced after a phase shipped, to be folded in when
the touched chapter is next under hand.

(none currently open; the R78 qualified-`@table` delta landed in this
plan's Phase 2 + Phase 3 commits. The structured ambiguity rejection
the original carry-forward note quoted (`@table(name: 'film') is
ambiguous: defined in schemas [public, archive]; qualify as
'public.film', 'archive.film'`) was wired in alongside the docs via
the new `BuildContext.unknownTableRejection` helper, which routes
through `JooqCatalog.findCandidateSchemasFor` before falling back to
`Rejection.unknownTable`. The three `@table`-directive sites in
`TypeBuilder` (`buildTableType`, `buildTableInterfaceType`,
`buildTableInputType`) all use it, so the better message reaches
authors at every site that resolves a directive `name:` argument.)

## Out of scope

- Editing the legacy README itself. Legacy modules are out of AI edit
  scope (`CLAUDE.md`); the legacy-side stub redirect happens in a
  human-authored companion commit.
- Translating any pages to Norwegian. The existing site is English-only;
  the manual stays English-only.
- Adding a search index, in-page TOC widget, or other navigation
  improvements beyond what AsciiDoctor's built-in `:toc: left` provides.
  If the manual surfaces a search need, file a follow-up; do not block
  R68 on it.
- The rewrite-internal contributor docs at `graphitron-rewrite/docs/`.
  R68 cross-links to them but does not move them; they stay under
  `Architecture` in the top nav. R15 owns drift in that surface.
- The roadmap rendering on the site. R9 already covers it; R68 inherits
  whatever R9 produces.

## Estimate

Phase 1a (IA scaffold + read-simply checks): ~half a day; five short
files plus one nav-row edit. Cheap by design so the IA review is the
gating cost.
Phase 1b (tutorial prose + smoke test): ~2.5 days for a single author,
mostly prose plus the smoke-test wiring.
Phase 2 (directive reference + verifier): ~5 days; 25 directives × ~30
min each plus verifier wiring.
Phase 3 (how-to recipes): ~5–6 days; 14 recipes × ~half-day each, mostly
porting and reshaping legacy README prose. The 14th recipe
(`test-your-schema.adoc`) is new prose against R67's `querydb/` pattern.
Phase 4 (explanation + Mojo/runtime): ~3 days.
Phase 5 (diagnostics glossary + deprecations index): ~1 day if the
generators land cheaply, ~2 days if the validator exposure needs work.
Phase 6 (cutover): ~half a day; mostly link-flipping.

Total: ~17–19 days author-time, spread across multiple cycles. Phase
1a is the immediate next slice (IA validation, half a day). Phases 1b
and 2 are the user-visible cliff that follows; Phases 3–6 ship
continuously after that.
