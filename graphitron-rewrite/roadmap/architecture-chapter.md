---
id: R86
title: "Architecture chapter for the manual"
status: Spec
bucket: Documentation
depends-on: []
---

# Architecture chapter for the manual

The diataxis user-manual (R68) was authored with see-also and inline xrefs into a planned architecture chapter that was never finished. R68 stripped the broken xrefs at UX-review and pushed contributor-facing topics out by file-path string ("see `graphitron-rewrite/docs/...` in the source repository"), leaving the deployed `/architecture/` chapter as a set of pages without a coherent landing or visual anchor. This plan finishes that chapter.

The chapter already exists as a *container*: `graphitron-rewrite/docs/` stages into `staging/architecture/` (see `docs/pom.xml:110-131`), and `README.adoc` deploys as `index.adoc`. What's missing is (1) a chapter-shaped landing that names the four R86 topics (federation wiring, runtime extension points, typed-rejection design, dev-loop runtime framing) and routes a reader through them, (2) the visualizations that turn dense prose into a mental model, and (3) restored xrefs from the user manual.

## Audience and scope

Contributors and advanced users extending the runtime. The chapter is the opposite-quadrant counterpart to the manual's `/manual/explanation/` pages: where explanation answers "why does it work this way for me as a user", architecture answers "how is it built and where do I plug in if I'm extending it". Out of scope: the `/manual/` pages themselves (R68 owns those), and rewrite-internal mechanics that have no extension surface (`testing.adoc`, `workflow.adoc`, `argument-resolution.adoc` stay as-is — they're the supporting reference layer that the chapter index points into, not chapter-narrative material).

## Page layout

Existing files under `graphitron-rewrite/docs/` keep their slugs; the chapter is shaped around them:

* `README.adoc` (rendered as `index.adoc`) — chapter landing. Rewritten to introduce the four named topics, route readers by intent (federation / extension points / typed rejection / dev loop), and host the build-pipeline overview diagram (D1) that the rest of the chapter zooms into. The existing "Modules" and "Publishing" sections stay; the "Pipeline at 30 seconds" ASCII diagram is replaced by D1.
* `getting-started.adoc` — runtime wiring. Tightened: keep Hello world, custom scalar, tenant scoping, JWT context arguments, customizer safe surface. The existing `## Federation` and `## Dev loop` sections remain but get a one-line forward pointer to the dedicated topic pages below; redundant prose moves out.
* `runtime-extension-points.adoc` — `GraphitronContext` design. Reframed: opens with the "why per-app emission rather than a shared runtime jar" rationale (currently implicit). Keep the per-method design, complementary technologies (jOOQ Configuration, RLS), and the three-layer "where each concern belongs" decision tree; this is one of the page's strongest sections and gets D4 (request lifecycle) anchoring it.
* `federation.adoc` — *new page*. Lifts federation framing out of `getting-started.adoc` and consolidates the contributor-level surface: the `@link` opt-in, the `<schemaInput tag>` flag's federation-implicit behaviour, the custom entity fetcher seam (`fed -> fed.fetchEntities(...)`), and how `Graphitron.buildSchema(...)` decides whether to invoke `Federation.transform(...)`. D9 (federation entity-resolution flow) anchors the page. The user-facing wiring stays in `getting-started.adoc#federation` with a forward pointer.
* `typed-rejection.adoc` — *new page*. Consolidates the sealed-`Resolved` hierarchy, the `Rejection` kinds taxonomy (`AUTHOR_ERROR`, `INVALID_SCHEMA`, `DEFERRED`), and `BuildContext.candidateHint(attempt, candidates)` for Levenshtein-ranked fuzzy suggestions. Cross-links into the user-facing `/manual/reference/diagnostics-glossary.adoc` on one side and `rewrite-design-principles.adoc#builder-step-results-are-sealed-not-strings-or-out-params` on the other. D10 (sealed-hierarchy class diagram) anchors the page. The two principles sections (Builder-step results, Wire-format encoding) in `rewrite-design-principles.adoc` get a forward pointer to the consolidated narrative; their prose stays where contributor-eyes look for it.
* `dev-loop.adoc` — *new page*. Lifts the dev-loop content out of `getting-started.adoc` and frames it as the operational extract of the LSP server, schema watcher, and classpath watcher running in one JVM: what each component watches, how they wake the generator, why idempotent writes matter for IDE recompile. D7 (dev-loop runtime framing) anchors the page. Consumer-facing usage stays in `getting-started.adoc#dev-loop` with a forward pointer.
* `code-generation-triggers.adoc` — classification reference. Existing structure stays; D2 (parent × return classification matrix) replaces or augments the two-axis prose tables, D3 (scope state machine) anchors the `## Scope` section, D5 (N×M derived-target cross-product) anchors the `## Derived tables` section.
* `rewrite-design-principles.adoc`, `argument-resolution.adoc`, `testing.adoc`, `workflow.adoc` — supporting reference. Untouched except for forward pointers from the principles doc into the consolidated `typed-rejection.adoc`.

The rewritten `index.adoc` opens with a "you came here from" navigation hub: extending the runtime → `runtime-extension-points.adoc`; integrating with federation → `federation.adoc`; understanding rejections / writing a new validator → `typed-rejection.adoc`; the dev loop / LSP integration → `dev-loop.adoc`; reading the classification taxonomy → `code-generation-triggers.adoc`.

## Visualizations

Two toolchains. Mermaid for diagrams the runtime-rendering pipeline already supports (state, sequence, class diagrams); pikchr for diagrams where layout precision is load-bearing (the build pipeline, the classification matrix, the N×M cross-product).

[cols="1,3,1,1"]
|===
| ID | Diagram | Page | Tool

| D1 | Build pipeline: `.graphqls` → loader → builder → `GraphitronSchema` → validator → generators → write → consumer compile. Replaces the ASCII art in the current `index.adoc`. | `index.adoc` | pikchr
| D2 | Classification matrix: parent context (rows: unmapped / `@table` / `@record`) × return type (cols: target table / target record / target scalar / target polymorphic). Each cell labels the resulting category with a representative directive. | `code-generation-triggers.adoc` | pikchr
| D3 | Scope state machine: `enter` / `split` / `record handoff` / `exit` with the trigger labels (`@splitQuery`, `@record` boundary, `@service`). | `code-generation-triggers.adoc` | mermaid stateDiagram-v2
| D4 | Request lifecycle: client → graphql-java → DataFetcher → `GraphitronContext.getDslContext` → `DSLContext` → DB → projection → response. | `runtime-extension-points.adoc` | mermaid sequenceDiagram
| D5 | N×M batch shape: N parent rows × M lookup-argument values forming the derived target cross-product. | `code-generation-triggers.adoc` (Derived tables section) | pikchr
| D7 | Dev-loop runtime framing: LSP server, schema watcher, classpath watcher, generator dispatch, idempotent writer all in one JVM, with arrows showing wake-up events. | `dev-loop.adoc` | mermaid flowchart
| D9 | Federation entity-resolution flow: SDL `@link` → `buildSchema` → federation-wrap → `_entities` resolver → per-type batched `SELECT`; the alternate "custom fetcher" arm. | `federation.adoc` | mermaid sequenceDiagram
| D10 | Typed-rejection sealed hierarchy: `Resolved.{None | Ok | Rejected}` plus the `Rejection` kinds, with one concrete resolver's variants overlaid as an example. | `typed-rejection.adoc` | mermaid classDiagram
|===

D6 (module/package architecture) is deliberately *not* drawn; the modules table in `index.adoc` already covers it, and a graph adds noise without adding insight.

## Tooling

Pikchr support requires `asciidoctorj-diagram` on the asciidoctor-maven-plugin's classpath. One dependency added under `docs/pom.xml`'s `render-site` execution; the version that pairs with `asciidoctorj 3.0.1` (currently configured) is `asciidoctorj-diagram 3.0.x`. Pikchr ships pure-Java in that artifact, so CI on linux needs no extra binaries.

[source,xml]
----
<dependency>
    <groupId>org.asciidoctor</groupId>
    <artifactId>asciidoctorj-diagram</artifactId>
    <version>${asciidoctorj-diagram.version}</version>
</dependency>
----

Pikchr blocks become SVG at build time (`process-asciidoc` phase); generated SVGs land under each rendered page's `images/` adjacent to the asciidoctor output, cached by content hash so unchanged pages skip re-rendering. Mermaid stays runtime-rendered via `_theme/docinfo-footer.html` — the existing path already works on the deployed site, so no change there.

The `failIf severity=WARN` log handler in `docs/pom.xml:351-353` already fails the build on any AsciiDoctor warning, so a broken pikchr block, a broken xref, or a missing image fails the docs build automatically. No new verifier needed for diagram drift; if a diagram references a class that no longer exists or a directive that was renamed, the rendered page's prose will reference it too, and the existing `DirectiveDocCoverageTest` / `DiagnosticsDocCoverageTest` / `MojoDocCoverageTest` suite catches the prose half. Diagrams are anchored to slugs and section IDs that are part of the chapter narrative.

## Restored xrefs from the manual

R68 stripped see-also bullets and rewrote inline mentions to file-path strings. Five live today (audit `grep -rn "graphitron-rewrite/docs" docs/`):

* `docs/README.adoc:33` — site landing's "rewrite-specific architecture docs live under" framing. Stays as prose; the chapter is reachable from the footer's `/architecture/` link.
* `docs/manual/explanation/index.adoc:31` — "For contributor-facing material … see the rewrite-internal docs at `graphitron-rewrite/docs/`". Convert to xref into `xref:../../../architecture/index.adoc[the architecture chapter]` once the landing reads as a chapter.
* `docs/manual/explanation/classifier-mental-model.adoc:5` — "rewrite-internal docs cover the contributor-facing detail". Convert to xref into `xref:../../../architecture/code-generation-triggers.adoc[Code Generation Triggers]`.
* `docs/manual/explanation/how-it-works.adoc:5` — "for the contributor-level pipeline detail see `graphitron-rewrite/docs/code-generation-triggers.adoc`". Convert to xref into the same target.
* `docs/manual/how-to/test-your-schema.adoc:9` — "rewrite-internal tier model … see `graphitron-rewrite/docs/testing.adoc`". Convert to xref into `xref:../../../architecture/testing.adoc[the test-tier guide]`.

The rewritten `/architecture/index.adoc` is the load-bearing change for these: once it reads as a chapter landing rather than a module README, the manual's "see the architecture chapter" pointers terminate on something coherent.

## Implementation

. Add `asciidoctorj-diagram` to `docs/pom.xml`, render-site execution. One commit so the diagram tooling exists before any pikchr block does.
. Rewrite `graphitron-rewrite/docs/README.adoc` to be a chapter landing: navigation hub by intent, the four R86 topics surfaced, D1 (build pipeline) replacing the ASCII art. The existing "Modules" and "Publishing" sections stay.
. Add D2, D3, D5 to `code-generation-triggers.adoc` at their respective sections.
. Add D4 to `runtime-extension-points.adoc`; insert the "why per-app emission" rationale paragraph at the top of `## Where the interface comes from`.
. Create `graphitron-rewrite/docs/federation.adoc`: lift the federation prose from `getting-started.adoc`, add D9, frame the contributor-level surface (`fetchEntities` seam, the `<schemaInput tag>` decision logic, the federation-wrap rule on `Graphitron.buildSchema`).
. Create `graphitron-rewrite/docs/typed-rejection.adoc`: consolidate the sealed-`Resolved` narrative from `rewrite-design-principles.adoc`, add D10, link in/out of the user-facing diagnostics glossary.
. Create `graphitron-rewrite/docs/dev-loop.adoc`: lift the dev-loop runtime framing from `getting-started.adoc`, add D7. Cross-link to the consumer-facing `getting-started.adoc#dev-loop` for the "what you do" half.
. Trim `getting-started.adoc`'s federation and dev-loop sections to their consumer-facing essence with forward pointers to the new pages.
. Restore the five xrefs in `docs/manual/` (one prose mention in `README.adoc` stays as-is).
. Update `docs/pom.xml`'s `stage-architecture` resource block if any new file pattern needs explicit inclusion (current `<include>*.adoc</include>` covers any new page).

The ordering is real: tooling first (step 1), then the landing rewrite (step 2) so the chapter is shaped before content lands, then the per-page diagram and content additions (steps 3-8), then the cross-cutting xref restoration (step 9). Steps 3-8 are independent and can land in any order within their group.

## Tests / verification

* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` succeeds with the AsciiDoctor warn-as-error gate intact. A broken pikchr block, broken xref, or unrenderable mermaid block fails the build.
* `DirectiveDocCoverageTest`, `MojoDocCoverageTest`, `DiagnosticsDocCoverageTest`, `DeprecationsDocCoverageTest` continue to pass; the chapter does not duplicate the closed-set surfaces those tests cover.
* Manual smoke: render the docs site (`mvn -f docs/pom.xml process-asciidoc`) and walk the chapter on a fresh browser session — every diagram renders, every xref resolves, every R86 named topic (federation / runtime extension points / typed rejection / dev loop) is reachable from the chapter index in one click.
* No new drift-protection test; chapter prose is curated voice (same call as R68's explanation chapter — see the changelog entry for `R68 Phase 4 second half`), and the four existing `DocCoverageTest` suites carry the closed-set drift-protection seams.

## Out of scope

* The `/manual/explanation/` pages — R68 owns those; the chapter restores xrefs *from* them but does not edit them beyond the five surgical xref conversions named above.
* New rewrite-internal pages (`testing.adoc`, `workflow.adoc`, `argument-resolution.adoc`) — they stay as the supporting reference layer; the chapter index points into them but does not narrate them.
* The legacy modules at the repo root — out of AI scope per `CLAUDE.md`.
* Diagrams beyond the eight enumerated above. New diagrams are the right way to extend the chapter later, but the spec commits to a closed set; "add another diagram" is its own follow-up plan if the chapter as shipped reveals a gap.
