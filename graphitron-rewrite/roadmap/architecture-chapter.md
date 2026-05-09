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

Existing files under `graphitron-rewrite/docs/` keep their slugs; the chapter is shaped around them. One new page (`typed-rejection.adoc`) earns its own file because it consolidates content from a new source; federation and dev-loop framing live as augmented sections in `getting-started.adoc`, since their contributor-level prose adds ~30-50 lines each — under the threshold where a page boundary pays for itself.

* `README.adoc` (rendered as `index.adoc`) — chapter landing. Rewritten as a navigation hub by intent: extending the runtime, integrating with federation, understanding rejections, the dev loop, reading the classification taxonomy. The existing "Modules" and "Publishing" sections stay; the "Pipeline at 30 seconds" ASCII diagram is replaced by D1. A "Pipeline at a glance" linked destination carries the contributor-onboarding narrative voice; the index itself stays intent-routed (one voice per page, per the architect review).
* `getting-started.adoc` — runtime wiring. The existing `## Federation` and `## Dev loop` sections each grow a marked contributor-rationale subsection (`=== How this is wired (for contributors)`) carrying the chapter's contribution: `@link` opt-in semantics, the `<schemaInput tag>` federation-implicit decision, the `fetchEntities` seam, why `Graphitron.buildSchema` does the wrap rather than the consumer; for dev-loop, the LSP server / schema watcher / classpath watcher / generator dispatch as one-JVM components and what each watches. The marked subsection is the voice seam: a reader on the consumer arc skims past it, a contributor arrives at it via the chapter index's intent hub. D9 (federation flow) anchors the federation contributor subsection; D7 (dev-loop runtime) anchors the dev-loop one.
* `runtime-extension-points.adoc` — `GraphitronContext` design. Opens with the "why per-app emission rather than a shared runtime jar" rationale (currently implicit). Keep the per-method design, complementary technologies (jOOQ Configuration, RLS), and the three-layer "where each concern belongs" decision tree. D4 (request lifecycle) anchors the page.
* `typed-rejection.adoc` — *new page*. Consolidates the sealed-`Resolved` narrative, the `Rejection` kinds taxonomy, and `BuildContext.candidateHint(attempt, candidates)` for Levenshtein-ranked fuzzy suggestions. Cross-links *out* to the user-facing `/manual/reference/diagnostics-glossary.adoc`. **Becomes the canonical source** for the sealed-result-and-structured-rejection narrative; the two relevant sections in `rewrite-design-principles.adoc` ("Builder-step results are sealed, not strings or out-params"; "Wire-format encoding is a boundary concern") collapse to one-line forward pointers into `typed-rejection.adoc`. The principles doc keeps its principle-list shape; the chapter page carries the consolidated story. D10 anchors the page (paired with the drift-protection seam below).
* `code-generation-triggers.adoc` — classification reference. Existing structure stays; D3 (scope state machine) anchors the `## Scope` section. The classification matrix and the N×M cross-product render as enriched AsciiDoc tables in-place; mermaid renders both poorly and a styled table is more readable than either toolchain.
* `rewrite-design-principles.adoc`, `argument-resolution.adoc`, `testing.adoc`, `workflow.adoc` — supporting reference. Untouched except for the two principles-doc forward pointers above.

## Visualizations

Mermaid for every diagram. The runtime-rendering pipeline (`_theme/docinfo-footer.html`) already wires it; no toolchain change. Pikchr was on the table for "where layout precision is load-bearing" but the principle pressure (toolchain proliferation, tcl-like syntax future authors won't know, JRuby-loaded extension) outweighs the precision gain on the three diagrams it would have served. Two of those three render better as enriched AsciiDoc tables than as either diagram language.

[cols="1,3,1"]
|===
| ID | Diagram | Page

| D1 | Build pipeline as a flowchart LR: `.graphqls` → loader → builder → `GraphitronSchema` → validator → generators → write → consumer compile. Replaces the ASCII art in the current `index.adoc`. | `index.adoc` § Pipeline at a glance
| D3 | Scope state machine: `enter` / `split` / `record handoff` / `exit` with the trigger labels (`@splitQuery`, `@record` boundary, `@service`). | `code-generation-triggers.adoc` § Scope
| D4 | Request lifecycle as a sequenceDiagram: client → graphql-java → DataFetcher → `GraphitronContext.getDslContext` → `DSLContext` → DB → projection → response. | `runtime-extension-points.adoc`
| D7 | Dev-loop runtime framing as a flowchart: LSP server, schema watcher, classpath watcher, generator dispatch, idempotent writer all in one JVM, with arrows showing wake-up events. | `getting-started.adoc` § Dev loop
| D9 | Federation entity-resolution flow as a sequenceDiagram: SDL `@link` → `buildSchema` → federation-wrap → `_entities` resolver → per-type batched `SELECT`; the alternate "custom fetcher" arm. | `getting-started.adoc` § Federation
| D10 | Typed-rejection sealed hierarchy as a classDiagram: `Resolved` with the `None` / `Ok` / `Rejected` permits plus the `Rejection` kinds, with one concrete resolver's variants overlaid as an example. | `typed-rejection.adoc`
|===

The classification matrix (parent context × return type) and the N×M derived-target cross-product render as enriched AsciiDoc tables at their existing locations in `code-generation-triggers.adoc`; both are inherently tabular and a styled table reads better than mermaid for either. D6 (module/package architecture) is deliberately not drawn; the modules table in `index.adoc` covers it.

The `failIf severity=WARN` log handler in `docs/pom.xml:351-353` already fails the build on any AsciiDoctor warning, so a broken xref or missing image fails the docs build automatically.

## Drift protection for sealed-hierarchy enumeration

D10 and the `typed-rejection.adoc` prose name closed sets (`Resolved.{None, Ok, Rejected}`; the `Rejection` kinds; optionally `BatchKey` axes if the page references them). The "Documentation names only live tests/code" principle (`rewrite-design-principles.adoc`) makes unenforced enumerations a drift surface: a new permit lands, the prose silently goes stale, readers trust a false invariant.

A `SealedHierarchyDocCoverageTest` lives next to `DirectiveDocCoverageTest` and walks the sealed hierarchies the chapter enumerates, asserting each permit's class name appears in the documenting page. Same shape as the existing four `DocCoverageTest` suites: bidirectional, tied to a closed set the compiler already exhaustivity-checks.

The rule is mechanical: *if the chapter prose names a permit, the test pins it; if the prose describes shape only ("rejection is a typed variant; see javadoc on `Resolved` for permits") and lets javadoc carry the per-permit detail, no test entry for that hierarchy.* Test scope follows prose enumeration; the spec does not punt the decision to the implementer because the rule is what governs both halves. As the chapter currently sketches, D10 + the typed-rejection narrative names `Resolved.{None | Ok | Rejected}` and the `Rejection` kinds, so those two are pinned; `BatchKey` is referenced but not enumerated (the principles doc enumerates it; that doc is not in the test's scope), so it is not pinned by this test.

## Restored xrefs from the manual

R68 stripped see-also bullets and rewrote inline mentions to file-path strings. Five live today (audit `grep -rn "graphitron-rewrite/docs" docs/`):

* `docs/README.adoc:33` — site landing's "rewrite-specific architecture docs live under" framing. Stays as prose; the chapter is reachable from the footer's `/architecture/` link.
* `docs/manual/explanation/index.adoc:31` — "For contributor-facing material … see the rewrite-internal docs at `graphitron-rewrite/docs/`". Convert to `xref:../../../architecture/index.adoc[the architecture chapter]`.
* `docs/manual/explanation/classifier-mental-model.adoc:5` — "rewrite-internal docs cover the contributor-facing detail". Convert to `xref:../../../architecture/code-generation-triggers.adoc[Code Generation Triggers]`.
* `docs/manual/explanation/how-it-works.adoc:5` — "for the contributor-level pipeline detail see `graphitron-rewrite/docs/code-generation-triggers.adoc`". Convert to the same target.
* `docs/manual/how-to/test-your-schema.adoc:9` — "rewrite-internal tier model … see `graphitron-rewrite/docs/testing.adoc`". Convert to `xref:../../../architecture/testing.adoc[the test-tier guide]`.

The rewritten `/architecture/index.adoc` is the load-bearing change for these: once it reads as a chapter landing rather than a module README, the manual's "see the architecture chapter" pointers terminate on something coherent.

## Chapter index draft (first-client check)

The first client of this design is the rewritten `index.adoc`. If it doesn't read simply, the design is wrong (per `workflow.adoc:71`). Sketch:

> = Architecture
>
> Contributor-facing documentation for the Graphitron generator: how the pipeline classifies a GraphQL schema, what code it emits, and the design principles that govern both. For end-user documentation, see xref:../manual/index.adoc[the user manual].
>
> == You came here because…
>
> You want to *extend the runtime* — implement `GraphitronContext`, route per-tenant `DSLContext`s, register custom scalars, hook in jOOQ listeners. → xref:runtime-extension-points.adoc[Runtime Extension Points].
>
> You're *integrating with Apollo Federation* — the `@link` opt-in, the `<schemaInput tag>` flag, providing a custom entity fetcher. → xref:getting-started.adoc#federation[Getting started → Federation].
>
> You want to *understand rejections* — what `AUTHOR_ERROR` / `INVALID_SCHEMA` / `DEFERRED` actually mean in the builder, why rejection is a typed variant rather than a string, how Levenshtein candidate hints get attached. → xref:typed-rejection.adoc[Typed rejection].
>
> You're *integrating an editor or agent with the dev loop* — what the `dev` Mojo wires up, what the LSP / schema watcher / classpath watcher each watch, why idempotent writes matter. → xref:getting-started.adoc#dev-loop[Getting started → Dev loop].
>
> You want to *read the classification taxonomy* — every variant the schema builder produces, every generator's input. → xref:code-generation-triggers.adoc[Code Generation Triggers].
>
> == Pipeline at a glance
>
> [the build-pipeline mermaid diagram D1 here, with a 3-paragraph narrative covering the loader's directive auto-injection, the classifier as the only place directives are read, and the writer's idempotency contract]
>
> == Modules
>
> [existing table stays]
>
> == Publishing
>
> [existing section stays]

This shape is what the chapter has to deliver against. If implementation discovers the index reads better with the modules table promoted higher, or with the "Pipeline at a glance" inlined into the intent hub instead of separated, the spec is wrong and the deviation is expected and welcome.

## Implementation

The order matters in exactly one place: `typed-rejection.adoc` lands before the `rewrite-design-principles.adoc` forward pointers (otherwise the principles doc ends up pointing at a dead xref). Everything else is independent and the implementer chooses commit shape.

* Rewrite `graphitron-rewrite/docs/README.adoc` to the index draft above. D1 (build pipeline mermaid) replaces the ASCII art. The existing "Modules" and "Publishing" sections stay verbatim.
* Augment `getting-started.adoc § Federation` with a marked `=== How this is wired (for contributors)` subsection carrying the contributor-level rationale (`@link` semantics, the `<schemaInput tag>` decision logic, the `Graphitron.buildSchema` federation-wrap rule, the `fetchEntities` seam). Add D9 (federation flow) inside the subsection.
* Augment `getting-started.adoc § Dev loop` with a marked `=== How this is wired (for contributors)` subsection carrying the runtime-framing prose (LSP + schema watcher + classpath watcher in one JVM, generator dispatch, idempotent-write coupling to IDE recompile). Add D7 (dev-loop runtime) inside the subsection.
* Reframe `runtime-extension-points.adoc`: prepend the "why per-app emission rather than a shared runtime jar" rationale to `## Where the interface comes from`. Add D4 (request lifecycle).
* Add D3 (scope state machine) to `code-generation-triggers.adoc § Scope`. Convert the existing `## Classification` and `## Derived tables` prose tables to enriched AsciiDoc tables (cell shading, inline directive examples) where readability improves.
* Create `typed-rejection.adoc`. Consolidate the sealed-`Resolved` narrative, the `Rejection` taxonomy, and `BuildContext.candidateHint`. D10 anchors. Cross-link out to `/manual/reference/diagnostics-glossary.adoc`. **This page becomes the canonical source** — write the prose under that frame.
* Replace the two affected sections in `rewrite-design-principles.adoc` ("Builder-step results …", "Wire-format encoding …") with one-line forward pointers into `typed-rejection.adoc`. Edit the framing line at `rewrite-design-principles.adoc:5` in the same commit: today it says "until [R86 lands], this contributor-facing reference is the canonical source"; the replacement names `typed-rejection.adoc` as canonical for the consolidated narrative, with the principles doc retaining its principle-list shape for the items not consolidated.
* Add `SealedHierarchyDocCoverageTest` against `Resolved.permits()` (and any other sealed hierarchy the chapter enumerates). Asserts each permit name appears in the documenting page. Lives next to `DirectiveDocCoverageTest`.
* Restore the four xrefs in `/docs/manual/` (`docs/README.adoc:33` stays as prose).

`docs/pom.xml`'s `stage-architecture` resource block already covers `*.adoc` so new pages stage automatically; no pom edit needed.

## Tests / verification

* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` succeeds. The AsciiDoctor warn-as-error gate (`docs/pom.xml:351-353`) catches broken xrefs, unrenderable mermaid blocks, and missing images.
* `DirectiveDocCoverageTest`, `MojoDocCoverageTest`, `DiagnosticsDocCoverageTest`, `DeprecationsDocCoverageTest`, `SealedHierarchyDocCoverageTest` (new) all pass. The chapter does not duplicate the closed-set surfaces those tests cover beyond what the new test pins.
* Manual smoke: render the docs site (`mvn -f docs/pom.xml compile`) and walk the chapter on a fresh browser session — every diagram renders, every xref resolves, every R86 named topic (federation / runtime extension points / typed rejection / dev loop) is reachable from the chapter index in one click.

## Out of scope

* The `/manual/explanation/` pages — R68 owns those; the chapter restores xrefs *from* them but does not edit them beyond the four surgical xref conversions named above.
* New rewrite-internal pages beyond `typed-rejection.adoc`. `testing.adoc`, `workflow.adoc`, `argument-resolution.adoc` stay as the supporting reference layer; the chapter index points into them but does not narrate them.
* The legacy modules at the repo root — out of AI scope per `CLAUDE.md`.
* Diagrams beyond the six enumerated above. New diagrams are the right way to extend the chapter later, but the spec commits to a closed set; "add another diagram" is its own follow-up plan if the chapter as shipped reveals a gap.
