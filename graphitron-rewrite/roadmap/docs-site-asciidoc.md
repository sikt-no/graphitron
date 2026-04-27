---
title: "Fold graphitron.sikt.no into the Maven build (AsciiDoc + GitHub Pages)"
status: Spec
bucket: architecture
priority: 20
---

# Plan: AsciiDoc docs site, built by Maven, published via GitHub Pages

Goal: replace the standalone Docusaurus app (separate repo, currently serving `graphitron.sikt.no`) with an AsciiDoc-based site that lives inside this repo, builds as part of the Maven reactor, and publishes to the same domain via GitHub Pages. Net effect: one source of truth, doc rot is caught by CI, contributors edit `.adoc` files next to the code they describe, and we drop the Node toolchain entirely.

Out of scope: rewriting the existing site's content (it's "very bare bones, does not contain much of value except the design", per the user). The migration step is mechanical, not editorial.

## Why AsciiDoc, not Docusaurus / Jekyll / MkDocs

- We're already a Maven shop. `asciidoctor-maven-plugin` slots into the reactor exactly like every other plugin we run; broken includes, dead xrefs, and missing assets fail `mvn install` the same way a compile error does.
- AsciiDoc's technical-doc affordances (admonitions, includes, callouts on code blocks, automatic TOC, conditional sections) are a better fit for generator/codegen documentation than markdown.
- GitHub renders `.adoc` natively in the web UI, so in-repo browsing is "good enough" without the Pages build (caveat: `include::` doesn't resolve in GitHub's preview; see "Authoring conventions" below).
- Single-source: no separate Node/JS toolchain, no separate repo, no separate CI, no separate review flow.

The light alternative considered and rejected was GitHub Pages' built-in Jekyll on plain markdown. It would be lower-touch (zero local build), but it lives outside the Maven build, so doc breakage doesn't fail CI; and it ties us to Jekyll's quirks if we ever want richer behaviour. Given the explicit "fold it into our workflow" goal, the Maven-integrated path wins.

## Module layout

New module `graphitron-rewrite/graphitron-docs/`, added to `graphitron-rewrite-parent`'s `<modules>` list.

```
graphitron-rewrite/graphitron-docs/
├── pom.xml
└── src/
    └── docs/
        └── asciidoc/
            ├── index.adoc                  # landing page
            ├── getting-started.adoc
            ├── _includes/                  # shared snippets, partials
            ├── images/
            └── css/
                └── custom.css              # optional theme overrides
```

- Packaging: `pom` (no Java code, no jar). Just plugin executions.
- Output: `target/generated-docs/` (asciidoctor-maven-plugin default).
- The module has no runtime dependencies; it depends on no other module in the reactor. It builds early and in parallel with everything else.

## Build wiring

`graphitron-docs/pom.xml`:

- `org.asciidoctor:asciidoctor-maven-plugin` (current stable, pinned via a property in the parent pom alongside the other plugin versions).
- Bind `process-asciidoc` to the `compile` phase (so a stock `mvn install` builds the site without needing a separate goal).
- Backend: `html5`. Source highlighter: `rouge` (no extra dependency vs. `coderay`/`pygments`).
- Attributes: `:source-highlighter: rouge`, `:icons: font`, `:toc: left`, `:sectanchors:`, `:sectlinks:`, `:experimental:`, plus a `:graphitron-version: ${project.version}` so docs can reference the current artifact version.
- Optional: `asciidoctor-diagram` extension, gated behind a profile so `mvn install` doesn't pay for a Graphviz/PlantUML dependency by default. Turn on only if a doc actually needs a diagram.

The full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` command (per [`CLAUDE.md`](../../CLAUDE.md)) keeps working unchanged; it just builds one extra module.

## Theme and visual design

Default Asciidoctor HTML output is functional but plain. We have two choices, in increasing order of effort:

1. **`asciidoctor-default` + small custom stylesheet.** Override fonts, colors, and the header/footer to match Sikt branding. ~½ day. Looks clean and professional, indistinguishable to most readers from a Material/Docusaurus site for technical content.
2. **A community theme** like `asciidoctor-skins` (Foundation, Golo, Maker, Readthedocs styles) or pull the Antora UI bundle. More polish out of the box, but more moving parts to maintain.

Recommendation: start with option 1, salvage colors/typography from the existing Docusaurus design (which the user described as the only thing of value there). Revisit option 2 only if the result feels insufficient.

## Deployment

New workflow at `.github/workflows/deploy-docs.yml`:

- Trigger: push to `main` (and manual `workflow_dispatch` for one-off republishes).
- Job 1 (build):
  - Checkout, set up JDK 21, cache `~/.m2`.
  - Run `mvn -f graphitron-rewrite/pom.xml -pl graphitron-docs -am package -Plocal-db` (the `-am` is mandatory per `CLAUDE.md`'s "footgun" note).
  - Upload `graphitron-rewrite/graphitron-docs/target/generated-docs/` as a Pages artifact via `actions/upload-pages-artifact`.
- Job 2 (deploy): `actions/deploy-pages@v4`, depends on job 1.
- Permissions: `pages: write`, `id-token: write`, scoped to this workflow only.

Repo-level setup (one-time, has to be done in GitHub UI by a maintainer, not by Claude):

1. Settings → Pages → Source: GitHub Actions.
2. Settings → Pages → Custom domain: `graphitron.sikt.no`, enforce HTTPS.
3. DNS: confirm or create the `CNAME` record on `sikt.no` pointing `graphitron` → `sikt-no.github.io`. (The user owns this; it may already exist, since the domain is referenced in the README. If it currently points at the Docusaurus host, the cutover happens here.)

A `CNAME` file at the site root is emitted by the asciidoctor build (copy a static `src/docs/asciidoc/CNAME` containing `graphitron.sikt.no` to the output directory) so Pages keeps the custom domain on each deploy.

## Content migration from the Docusaurus repo

Sequenced as Phase 2 (see "Phases" below) so the pipeline can be proven on a stub site first.

1. Inventory the existing Docusaurus repo (the user will need to point at it; not yet identified in this plan). Capture:
   - Page list and sidebar structure.
   - Custom React components, MDX features, plugins. Anything non-trivial gets noted as a conversion question rather than auto-converted.
   - Theme assets (logo, colors, fonts) we want to carry over.
2. Convert each `.md` / `.mdx` to `.adoc`. For straight markdown, `kramdoc` (a markdown-to-asciidoc converter) handles the bulk; spot-check by hand.
3. MDX-only features (React components inline) get rewritten as AsciiDoc passthroughs, AsciiDoc Diagram blocks, or just static HTML, depending on what they were doing.
4. Rewrite cross-references to AsciiDoc xref syntax (`xref:page.adoc#anchor[label]`).
5. Move images into `src/docs/asciidoc/images/`, update paths.
6. Verify the local build (`mvn -pl graphitron-docs -am package`) produces a clean site with no AsciiDoc warnings.

Once Phase 2 is in, archive the Docusaurus repo with a README pointing here.

## Authoring conventions

A short `graphitron-docs/README.adoc` (rendered by GitHub) for contributors:

- File extension: `.adoc` (not `.asciidoc`, not `.asc`), for consistency.
- One H1 per file, set as the page title via `= Title`.
- Use `include::` for shared snippets. Acknowledge that `include::` does not resolve in GitHub's web preview, so prefer self-contained pages where possible; reserve includes for genuine reuse (e.g., a "Prerequisites" block shared across getting-started variants).
- Code blocks: `[source,java]` (or `xml`, `yaml`, etc.) with optional callouts.
- Cross-page links: `xref:` not raw URLs.
- Images live next to the page that uses them, or in a shared `images/` folder; never hot-link to the deployed site.

## Workflow integration

This plan touches the conventions documented in [`workflow.md`](../docs/workflow.md) and [`CLAUDE.md`](../../CLAUDE.md):

- `CLAUDE.md` should grow a brief "Documentation site" section noting that `graphitron-docs/` is the source for `graphitron.sikt.no` and that doc changes ship through the same trunk-based flow as code.
- `workflow.md`'s "Plans with a user-visible surface" rule already mandates that user-facing changes include a draft of the user docs in the plan. Once this site is live, that rule binds against `graphitron-docs/` (the draft moves into the real page when the feature ships).
- The existing `.github/workflows/maven-build.yml` doesn't need to change; it already builds the full reactor, and the docs module is part of that.

## Phases

Two phases, picked because the cutover from Docusaurus is observable on the live domain between them.

### Phase 1: Pipeline (skeleton site)

End state: `graphitron.sikt.no` serves a one-page AsciiDoc site built by `mvn install` and deployed by the new GitHub Action. The page can be a placeholder ("Documentation is being migrated"), but the build, deploy, custom domain, and HTTPS all work end-to-end.

Deliverables:

- `graphitron-rewrite/graphitron-docs/pom.xml`.
- `graphitron-rewrite/pom.xml` updated `<modules>` list.
- `src/docs/asciidoc/index.adoc` and `CNAME`.
- Minimal custom CSS.
- `.github/workflows/deploy-docs.yml`.
- One-time GitHub Pages settings change (manual, by a maintainer).
- DNS verified by maintainer.

Verification: push to `main`, observe deploy succeed, hit `https://graphitron.sikt.no/`, confirm HTTPS valid and content served.

### Phase 2: Content migration

End state: every page that existed on the old Docusaurus site (with content worth keeping) is present, navigable, styled, and free of AsciiDoc warnings. Old repo archived.

Deliverables:

- Inventory of source pages (committed as a one-shot file, deleted once the migration lands).
- Converted `.adoc` pages, images, and assets.
- Sidebar / nav updated.
- `graphitron-docs/README.adoc` for contributors (authoring conventions above).
- Update root `README.md` to confirm the "Online documentation" link still resolves correctly (no actual URL change).

Verification: side-by-side comparison against the archived Docusaurus deploy (if reachable) for any page intentionally dropped.

## Open questions for the reviewer

1. **Module location.** This plan puts `graphitron-docs/` under `graphitron-rewrite/`, in line with the AI scope rule in `CLAUDE.md`. The site documents Graphitron-the-product, not just the rewrite. If the preferred home is the repo root (e.g., `/docs-site/`), the plan needs a small adjustment (root pom, not rewrite parent pom; a different scope discussion in `CLAUDE.md`).
2. **Existing Docusaurus repo.** Where does it live? Phase 2 needs the inventory, so I need a pointer.
3. **Theme effort.** Default Asciidoctor + custom CSS, or invest in `asciidoctor-skins` / Antora UI from the start? Current recommendation: start light.
4. **Old `docs/` at repo root.** Files like `vision-and-goal.md`, `graphitron-principles.md`, `security.md` live there today. Migrate into the new site, or leave alone? My read: migrate, since they're public-facing prose; but they're in the legacy area which AI is scoped out of, so this needs a human decision.

## Risks and mitigations

- **Docusaurus has features we can't easily port** (live demos, search, versioned docs). Mitigation: enumerate during Phase 2 inventory; for search, AsciiDoc sites can use `lunr.js` or similar client-side search added in a follow-up; versioning is unlikely to be needed pre-1.0.
- **Default Asciidoctor styling looks dated.** Mitigation: phase 1 ships with custom CSS borrowing the Docusaurus design's typography and palette; if it still feels off, escalate to option 2 in "Theme and visual design".
- **CNAME / DNS cutover races.** Mitigation: keep the Docusaurus deploy live until phase 1 is verified on a temporary `*.github.io` URL, then flip DNS in one step.
- **The Maven build now fails when docs are broken.** This is the *intent* (catch rot in CI), but it means a typo in an `.adoc` file blocks a release. Mitigation: AsciiDoc warnings vs. errors are configurable in the plugin; we set "fail on error, warn on missing xref" until the workflow is settled, then tighten.

## Roadmap entries

- This file (`docs-site-asciidoc.md`): keep through both phases. Collapse Phase 1 to a "shipped at `<sha>`" note when it lands; remove the file when Phase 2 lands.
- No spinoff items expected; if Phase 2 surfaces them (e.g., search, versioning), they'll be added as Backlog items at that time.
