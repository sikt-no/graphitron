---
title: "Fold graphitron.sikt.no into the Maven build (AsciiDoc + GitHub Pages)"
status: Spec
bucket: architecture
priority: 20
---

# Plan: AsciiDoc docs site, built by Maven, published via GitHub Pages

Goal: replace the standalone Docusaurus app (`github.com/alf/graphitron-landingsside`, currently serving `graphitron.sikt.no` from Sikt's internal Kubernetes via GitLab CI) with an AsciiDoc-based site that lives inside this repo, builds as part of the Maven reactor, and produces a hosting-agnostic static-site output. Net effect: one source of truth, doc rot is caught by CI, contributors edit `.adoc` files next to the code they describe, and we drop the Node toolchain entirely.

The site must remain Sikt-branded: the existing visual identity is built on the Sikt Design System (`@sikt/sds-core` CSS tokens), and that's the one piece of the current site genuinely worth carrying over. SDS integration is a hard requirement, not an optional theming decision.

Hosting target: **GitHub Pages**, deployed throughout development to the default `sikt-no.github.io/graphitron/` URL (or similar non-custom path). This keeps the live `graphitron.sikt.no` (currently Sikt-internal K8s nginx via GitLab CI) untouched while we build, lets us iterate visually on a real deployed URL, and reduces the cutover at the end to a single DNS flip and a one-time Pages settings change. The current Sikt K8s deployment + Matomo analytics + GitLab CI all get retired in Phase 3.

Out of scope:
- Rewriting the existing site's content (it's "very bare bones, does not contain much of value except the design", per the user). The migration step is mechanical, not editorial.
- The custom **Integrasjonstester** page (Norwegian-language React component over `src/data/integrationTests.json`). Not linked from the rest of the site, the user wasn't aware of it, and the data is sparse. Leave behind on the old repo.
- The default Docusaurus blog (template posts, no real content).

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
            ├── index.adoc                  # landing page (replaces the Docusaurus hero + features)
            ├── why.adoc                    # ported from docs/why.md
            ├── faq.adoc                    # ported from docs/faq.md
            ├── quick-start.adoc            # ported from docs/quick_start_guide.md
            ├── documentation.adoc          # ported from docs/documentation.md
            ├── _includes/                  # shared snippets, partials
            ├── images/                     # logo.svg, Person2/3.svg, Personer.svg, Creature1-3.svg, favicon
            └── css/
                ├── sds-tokens.css          # vendored from @sikt/sds-core
                ├── sds-button.css          # vendored from @sikt/sds-button
                └── site.css                # adapts the old siktifisert.css onto AsciiDoc's class names
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

## Sikt Design System integration

The current site is themed via the Sikt Design System: `@sikt/sds-core` (CSS custom properties for color, typography, spacing) and `@sikt/sds-button` (button styles). The existing `siktifisert.css` is mostly a thin layer of Docusaurus-specific selectors mapped onto SDS tokens (`--sds-color-*`, `--sds-typography-*`, `--sds-space-*`).

Our build needs the SDS CSS without dragging Node back in. Three approaches considered:

1. **Vendor the CSS files.** One-time copy of the compiled CSS from `node_modules/@sikt/sds-core/dist/` and `node_modules/@sikt/sds-button/dist/` into `src/docs/asciidoc/css/`. Refresh on SDS upgrades, tracked as a roadmap item. Pros: zero build-time dependencies, fast, fully reproducible. Cons: stale unless we re-vendor; needs a dated comment in the file noting the source SDS version.
2. **Maven `frontend-maven-plugin`.** Add Node + npm to the docs module build, run `npm install @sikt/sds-core @sikt/sds-button`, copy the dist CSS into the output. Pros: always fresh. Cons: re-introduces the exact Node toolchain we're leaving behind. Defeats half the point of the rewrite.
3. **CDN reference.** Link directly to a published SDS CSS URL. Pros: zero local copy. Cons: requires Sikt to publish SDS to a public CDN (unconfirmed); offline dev breaks; site breaks if CDN host changes.

**Decision: vendor.** Specifically: copy `dist/index.css` (or equivalent) from each SDS package into `src/docs/asciidoc/css/`, prepend a comment with `package@version, copied YYYY-MM-DD, source: <path>`, and add a roadmap item to refresh on SDS major releases. This keeps the build pure-Maven, the asset auditable, and contributors don't need Node installed to work on the docs.

Site-specific styles live in `site.css` and adapt the patterns from `siktifisert.css` onto Asciidoctor's default class names (`#header`, `#content`, `.sect1`, `.admonitionblock`, etc.) instead of Docusaurus's (`.navbar`, `.hero`, `.theme-doc-markdown`). The class-name remapping is mechanical; the design intent (SDS tokens for color/space/type, the `advantage-box` callout style, footer dark variant) carries over directly.

Asciidoctor's HTML structure is different enough from Docusaurus that a 1-to-1 stylesheet port isn't possible, but every SDS-token reference in the old CSS does port directly. Estimate: most of the visual work is rewiring selectors, not redesigning anything.

## Deployment

New workflow at `.github/workflows/deploy-docs.yml`:

- Trigger: push to `main` (and manual `workflow_dispatch` for one-off republishes).
- Job 1 (build):
  - Checkout, set up JDK 25, cache `~/.m2`.
  - Run `mvn -f graphitron-rewrite/pom.xml -pl graphitron-docs -am package -Plocal-db` (the `-am` is mandatory per `CLAUDE.md`'s "footgun" note).
  - Upload `graphitron-rewrite/graphitron-docs/target/generated-docs/` as a Pages artifact via `actions/upload-pages-artifact`.
- Job 2 (deploy): `actions/deploy-pages@v4`, depends on job 1.
- Permissions: `pages: write`, `id-token: write`, scoped to this workflow only.

Two-stage hosting:

**During Phases 1 and 2:** Pages serves the built site at the default GitHub URL (`https://sikt-no.github.io/graphitron/`). No custom domain configured yet, no `CNAME` file in the build output. The live `graphitron.sikt.no` continues to serve the old Docusaurus build from Sikt's internal K8s; nothing changes for end users. This gives us a real deployed URL to iterate against without disrupting the live site.

The `baseUrl` for Asciidoctor needs to reflect the `/graphitron/` subpath while we're on the default Pages URL (relative-only links in the AsciiDoc source avoid the issue; absolute internal links would need rewriting at cutover). Prefer `xref:` over raw URLs throughout.

**At Phase 3 cutover:** Add `CNAME` containing `graphitron.sikt.no` to `src/docs/asciidoc/`, configure the custom domain in GitHub Pages settings, flip DNS from Sikt K8s ingress to `sikt-no.github.io`, retire the GitLab CI pipeline, decommission the Sikt K8s deployment, archive the `alf/graphitron-landingsside` repo. Matomo analytics either gets reattached via a small `<script>` injected into the AsciiDoctor template, or dropped (decision deferred until the cutover plan is written).

Repo-level setup (one-time, has to be done in GitHub UI by a maintainer, not by Claude):

- **Phase 1:** Settings → Pages → Source: GitHub Actions.
- **Phase 3:** Settings → Pages → Custom domain: `graphitron.sikt.no`, enforce HTTPS, plus DNS update on `sikt.no` to point `graphitron` → `sikt-no.github.io`.

## Content migration from `alf/graphitron-landingsside`

Inventory complete (Phase 0 of this plan, already done while writing it). The full source-repo audit:

**Migrate:**
- `docs/why.md` (32 lines) → `why.adoc`. Includes one inline `<div class="advantage-box">` (bespoke SDS-styled callout); maps cleanly onto an AsciiDoc admonition or a passthrough block with the same class.
- `docs/faq.md` (50 lines) → `faq.adoc`. Plain markdown, no special features.
- `docs/quick_start_guide.md` (153 lines) → `quick-start.adoc`. Uses `<details>/<summary>` collapsibles around code samples; AsciiDoc has `[%collapsible]` blocks that produce equivalent HTML5 disclosure widgets.
- `docs/documentation.md` (53 lines) → `documentation.adoc`. Plain markdown.
- `static/img/` SVGs we want: `logo.svg`, `Favicon-Dark.svg`, `Person2.svg`, `Person3.svg`, `Personer.svg`, `Creature1.svg`, `Creature2.svg`, `Creature3.svg`. Move to `src/docs/asciidoc/images/`.
- `src/css/siktifisert.css` design intent (SDS tokens, `.advantage-box`, footer dark style). Reimplemented in `site.css` against AsciiDoctor selectors.
- The homepage hero + 3-feature row from `src/pages/index.js` and `src/components/HomepageFeatures/index.js`. Reimplemented as a static AsciiDoc landing page using a `[.hero]` block and a 3-column table or AsciiDoc `[.features]` blocks. The `<ButtonLink>` from `@sikt/sds-button` becomes a styled `<a>` with the SDS button class names.

**Drop (out of scope):**
- `src/pages/integrasjonstester.js`, `src/components/IntegrationTests/`, `src/data/integrationTests.json`. Per the user, this page wasn't part of the intended site and isn't linked.
- Default Docusaurus `blog/` posts. Template content, no value.
- `static/img/undraw_docusaurus_*.svg` and `docusaurus.png`. Docusaurus boilerplate.
- `src/css/custom.old.css`. Already labelled stale by the source repo.
- `src/theme/Root.js`. Force-light-theme hack, won't be needed (we control the AsciiDoctor template directly).
- `Dockerfile`, `deployment.yaml`, `.gitlab-ci.yml`. Replaced by GitHub Actions in Phase 3.

Conversion mechanics:

1. `kramdoc` (markdown-to-asciidoc) handles the bulk of each `.md` file.
2. Spot-check each output: `<details>` to `[%collapsible]`, custom CSS classes preserved as inline passthroughs, links to other pages rewritten as `xref:`.
3. Move images, update all references.
4. Verify the build (`mvn -f graphitron-rewrite/pom.xml -pl graphitron-docs -am package`) produces a clean site with no AsciiDoc warnings, then push and check the deployed Pages URL.

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

Three phases, each with an observable end state on a real URL.

### Phase 1: Pipeline (skeleton site, deployed to default Pages URL)

End state: `https://sikt-no.github.io/graphitron/` serves a one-page AsciiDoc site built by `mvn install`, styled with SDS tokens, deployed by the new GitHub Action. The live `graphitron.sikt.no` continues to serve the old Docusaurus build, untouched. The page is a placeholder ("Documentation is being migrated") but the build, SDS bundling, deploy, and the visual identity all work end-to-end on the deployed Pages URL.

Deliverables:

- `graphitron-rewrite/graphitron-docs/pom.xml`.
- `graphitron-rewrite/pom.xml` updated `<modules>` list.
- `src/docs/asciidoc/index.adoc` (placeholder content).
- `src/docs/asciidoc/css/sds-tokens.css`, `sds-button.css` (vendored from `@sikt/sds-core`, `@sikt/sds-button`, dated comment with source version).
- `src/docs/asciidoc/css/site.css` (initial port of `siktifisert.css` patterns onto AsciiDoctor classes).
- Logo and favicon copied into `src/docs/asciidoc/images/`.
- `.github/workflows/deploy-docs.yml`.
- One-time GitHub Pages settings change: enable Pages, source = GitHub Actions (manual, by a maintainer; no custom domain yet).

No `CNAME` file in this phase. No DNS changes. No touching the old Docusaurus deployment.

Verification: push to `main`, observe deploy succeed, hit `https://sikt-no.github.io/graphitron/`, confirm SDS branding renders correctly (Sikt colors, typography, header/footer match), HTTPS valid.

### Phase 2: Content migration

End state: the deployed Pages URL serves a fully-fledged Sikt-branded site with all of the in-scope content from `alf/graphitron-landingsside` ported to AsciiDoc. The live `graphitron.sikt.no` is still the old Docusaurus build, still untouched. Side-by-side review against the old site (browse both URLs) drives final polish.

Deliverables:

- Converted `.adoc` pages: `index`, `why`, `faq`, `quick-start`, `documentation`.
- Homepage hero + 3-feature layout reimplemented in static AsciiDoc.
- All in-scope SVGs and assets copied over.
- Site nav (top bar links: What and why, FAQ, Quick start guide, GitHub).
- `site.css` finalized: Sikt brand colors, the `.advantage-box` callout, the dark footer, hero spacing.
- `graphitron-docs/README.adoc` for contributors (authoring conventions).

Verification: open `https://sikt-no.github.io/graphitron/` and `https://graphitron.sikt.no/` side by side; confirm the new site reaches feature parity (minus integrasjonstester) and looks at least as Sikt-branded as the old. Iterate on Pages URL freely; no risk to live site.

### Phase 3: Cutover (custom domain, retire old infra)

End state: `https://graphitron.sikt.no/` serves the new Maven-built AsciiDoc site from GitHub Pages. The old Sikt K8s deployment is decommissioned. The `alf/graphitron-landingsside` repo is archived with a README pointing here.

Deliverables:

- Add `src/docs/asciidoc/CNAME` containing `graphitron.sikt.no`, configure custom domain in Pages settings, enforce HTTPS.
- DNS update: `graphitron.sikt.no` → `sikt-no.github.io` (Sikt platform / DNS team).
- Decision on Matomo: reattach via `<script>` injection in the AsciiDoctor template, or drop. Plan a small follow-up roadmap item if reattaching, since SDS may publish a tracking-snippet helper.
- Decommission: stop the GitLab CI pipeline on `alf/graphitron-landingsside`, scale K8s deployment to 0, remove the K8s Deployment/Service/Ingress, archive the repo with a redirect README.
- Update root `README.md` to keep the "Online documentation" link (URL doesn't change).

Verification: hit `https://graphitron.sikt.no/`, confirm HTTPS valid, confirm content matches Phase 2 deploy.

## Open questions for the reviewer

1. **Module location.** This plan puts `graphitron-docs/` under `graphitron-rewrite/`, in line with the AI scope rule in `CLAUDE.md`. The site documents Graphitron-the-product, not just the rewrite. If the preferred home is the repo root (e.g., `/docs-site/`), the plan needs a small adjustment (root pom, not rewrite parent pom; a different scope discussion in `CLAUDE.md`).
2. **Old `docs/` at repo root.** Files like `vision-and-goal.md`, `graphitron-principles.md`, `security.md` live there today. Migrate into the new site, or leave alone? My read: migrate, since they're public-facing prose; but they're in the legacy area which AI is scoped out of, so this needs a human decision.
3. **Matomo at cutover (Phase 3).** Reattach via `<script>` injection in the AsciiDoctor template, or drop analytics entirely with the move to GitHub Pages? Defer until Phase 3, but flagging now so it's not a surprise.

## Risks and mitigations

- **SDS is published as npm packages, not a public CDN.** Vendoring sidesteps this for the build; the residual risk is the vendored copy goes stale if SDS releases break compatibility. Mitigation: dated comment at the top of each vendored file noting `package@version` and source date; refresh as a roadmap item on SDS major releases.
- **Default Asciidoctor styling looks dated.** Mitigation: that's exactly what `site.css` solves by porting the SDS-token usage from `siktifisert.css`. Phase 1 verifies the branding looks right *before* Phase 2 piles content on top.
- **The Maven build now fails when docs are broken.** Intent (catch rot in CI), but a typo in `.adoc` blocks a release. Mitigation: AsciiDoc errors-vs-warnings is configurable in the plugin; start with "fail on error, warn on missing xref" until settled, then tighten.
- **Phase 3 DNS cutover requires Sikt platform / DNS team coordination.** Mitigation: Phase 1 and 2 are entirely independent of DNS; the new site is fully styled and content-complete on `sikt-no.github.io/graphitron/` before the cutover ticket is even raised. The cutover itself is then a single coordinated change.
- **Matomo loss.** If we drop Matomo we lose continuity in usage data. Mitigation: deferred decision, see Open question 3. If reattaching, the Matomo `<script>` is small and well-documented; injection into the AsciiDoctor template is a 5-line change.
- **Subpath base URL during Phases 1-2.** While on `sikt-no.github.io/graphitron/`, absolute internal links break. Mitigation: enforce `xref:` and relative-path links in the authoring conventions, and configure Asciidoctor `:imagesdir: images` so image paths stay relative.

## Roadmap entries

- This file (`docs-site-asciidoc.md`): keep through all three phases. Collapse each phase to a "shipped at `<sha>`" note when it lands; remove the file when Phase 3 lands and the cutover is verified.
- Spinoff (Backlog, to be created when Phase 1 lands): **`refresh-sds-vendored-css.md`**, periodic refresh of the vendored SDS CSS files on SDS major releases. Low priority, low effort.
- Other spinoffs may surface during Phase 2 (e.g., client-side search, autogenerated integrasjonstester from real test sources, versioned docs); add them as Backlog when they appear, don't pre-spec.
