---
id: R9
title: "Fold graphitron.sikt.no into the Maven build (AsciiDoc + GitHub Pages)"
status: In Progress
bucket: architecture
priority: 20
theme: docs
depends-on: []
---

# Plan: AsciiDoc docs site, built by Maven, published via GitHub Pages

Goal: consolidate every public-facing documentation artifact in this repo into a single Maven-built AsciiDoc site published to `graphitron.sikt.no`, replacing the separate Docusaurus app at `github.com/alf/graphitron-landingsside` (currently serving the live domain from Sikt's internal Kubernetes via GitLab CI). Net effect: one source of truth, doc rot is caught by CI, contributors edit `.adoc` files next to the code they describe, the Node toolchain goes away, and our roadmap and changelog become first-class citizens of the public site.

Four sources of content fold into the site:
- **`/docs/`** (repo root): the canonical home for product-level docs (vision, principles, security, dependencies, FAQ, quick-start, etc.). Existing `.md` files convert to `.adoc` as part of this plan; future authoring is `.adoc`.
- **`/graphitron-rewrite/docs/`**: the canonical home for rewrite-specific architecture, design principles, and contributor docs (workflow, design principles, model, code-generation triggers, runtime extension points). Same treatment: existing `.md` files convert to `.adoc`.
- **`/graphitron-rewrite/roadmap/`**: per-item plan files plus `changelog.md`. Stays `.md` (authoring convention pinned by `workflow.md`); `roadmap-tool` extension converts at build time and emits the rendered pages into the site.
- **`alf/graphitron-landingsside`** (old Docusaurus site): treated as a fallback. Most of its content is likely already covered by the in-repo docs above; the absorption phase audits gap-by-gap and ports only what's genuinely missing (FAQ probably; some "why" framing maybe).

The site must remain Sikt-branded: the existing visual identity is built on the Sikt Design System (`@sikt/sds-core` CSS tokens), and that's the one piece of the current site genuinely worth carrying over. SDS integration is a hard requirement, not an optional theming decision.

Hosting target: **GitHub Pages**, deployed throughout development to the default `sikt-no.github.io/graphitron/` URL (or similar non-custom path). This keeps the live `graphitron.sikt.no` (currently Sikt-internal K8s nginx via GitLab CI) untouched while we build, lets us iterate visually on a real deployed URL, and reduces the cutover at the end to a single DNS flip and a one-time Pages settings change. The current Sikt K8s deployment + Matomo analytics + GitLab CI all get retired in the cutover phase.

Out of scope:
- Rewriting the existing site's content (it's "very bare bones, does not contain much of value except the design", per the user). The migration is mechanical, not editorial.
- The custom **Integrasjonstester** page (Norwegian-language React component over `src/data/integrationTests.json`). Not linked from the rest of the site, the user wasn't aware of it, and the data is sparse. Leave behind on the old repo.
- The default Docusaurus blog (template posts, no real content).
- Filtering which roadmap items publish. The roadmap renders in full (max-transparency); a `public:` opt-in flag is a possible future refinement, not part of this plan.

## Why AsciiDoc, not Docusaurus / Jekyll / MkDocs

- We're already a Maven shop. `asciidoctor-maven-plugin` slots into the reactor exactly like every other plugin we run; broken includes, dead xrefs, and missing assets fail `mvn install` the same way a compile error does. (The rewrite reactor isn't currently built by any CI workflow; closing that gap is a Phase 1 deliverable, not a free side-effect of using Maven; see [CI integration](#ci-integration).)
- AsciiDoc's technical-doc affordances (admonitions, includes, callouts on code blocks, automatic TOC, conditional sections) are a better fit for generator/codegen documentation than markdown.
- GitHub renders `.adoc` natively in the web UI, so in-repo browsing works for plain prose. Caveats are real, though: GitHub's preview ignores `include::`, doesn't resolve `xref:` to other files, drops most attribute substitutions, and skips conditional blocks. Treat in-repo rendering as a fallback; the deployed site is canonical.
- Single-source: no separate Node/JS toolchain, no separate repo, no separate CI, no separate review flow.
- Authoring cost: `asciidoctor-maven-plugin` is JRuby-based and adds roughly 5-15 seconds of startup to a clean reactor build. Acceptable for CI; for local dev, see the `skip-docs` profile note in [Build wiring](#build-wiring).

The light alternative considered and rejected was GitHub Pages' built-in Jekyll on plain markdown. It would be lower-touch (zero local build), but it lives outside the Maven build, so doc breakage doesn't fail CI; and it ties us to Jekyll's quirks if we ever want richer behaviour. Given the explicit "fold it into our workflow" goal, the Maven-integrated path wins.

## Module layout

The Maven module lives at `/docs/` (repo root) with `<artifactId>graphitron-docs</artifactId>` (verified non-conflicting against the existing `graphitron`, `graphitron-rewrite-*`, and `graphitron-roadmap-tool` artifacts). Source files for the user-facing doc pages live directly at `/docs/<page>.adoc` for terminal/IDE discoverability: developers expect `cd repo && ls docs/` to work. The module is added to `graphitron-rewrite-parent`'s `<modules>` list as `<module>../docs</module>` (Maven supports relative `..` paths and emits a benign warning), so doc breakage fails the same `mvn install` that builds everything else.

The build draws from multiple source directories (see [Content sources](#content-sources)) and stages them into one location before AsciiDoctor runs:

```
docs/                                    # AUTHORED, repo-root, product docs
├── pom.xml
├── README.adoc                          # GitHub-rendered landing for the directory
├── index.adoc                           # site landing page
├── faq.adoc                             # if found missing during absorption phase
├── quick-start.adoc                     # if found missing during absorption phase
├── vision-and-goal.adoc
├── graphitron-principles.adoc
├── security.adoc
├── dependencies.adoc
├── _includes/                           # shared snippets, partials
├── _theme/                              # site theming (build-infrastructure, not content)
│   └── site.css                         # SDS-tokens-on-AsciiDoc styles (authored)
├── images/                              # logo.svg, Person2/3.svg, Personer.svg, Creature1-3.svg, favicon
└── target/                              # gitignored
    ├── staging/                         # build-time merged source tree
    │   ├── <copies of /docs/*.adoc>
    │   ├── css/
    │   │   ├── sds-core.css             # fetched from @sikt/sds-core via download-maven-plugin
    │   │   ├── sds-button.css           # fetched from @sikt/sds-button via download-maven-plugin
    │   │   ├── site.css                 # copied from /docs/_theme/
    │   │   └── LICENSE-sds-core.md      # only sds-core ships a LICENSE; sds-button has none
    │   ├── architecture/                # mirrors /graphitron-rewrite/docs/
    │   │   ├── README.adoc
    │   │   ├── workflow.adoc
    │   │   └── ...
    │   └── _generated/                  # roadmap-tool output
    │       ├── roadmap.adoc
    │       ├── changelog.adoc
    │       └── plans/<slug>.adoc        # one per roadmap item
    └── generated-docs/                  # AsciiDoctor's HTML output, served by Pages

graphitron-rewrite/docs/                 # AUTHORED, rewrite-specific architecture
├── README.adoc
├── workflow.adoc
├── rewrite-design-principles.adoc
├── rewrite-model.adoc
├── argument-resolution.adoc
├── code-generation-triggers.adoc
├── runtime-extension-points.adoc
├── getting-started.adoc
└── claude-code-web-environment.adoc

graphitron-rewrite/roadmap/              # AUTHORED, stays markdown
├── <slug>.md                            # one per item, YAML front-matter
├── changelog.md
└── README.md                            # GitHub-rendered roll-up, also stays markdown
```

- Packaging: `pom` (no Java code, no jar). Just plugin executions.
- Module has no compile-time dependencies on other modules; it depends on `roadmap-tool` only via a build-phase invocation (see [Build wiring](#build-wiring)).
- `target/` and everything under it is gitignored.
- The module path `../docs` in the rewrite parent pom is the only unusual bit; everything else is conventional Maven.

## Build wiring

`/docs/pom.xml` chains three plugins in `process-resources` and `compile`:

**1. `maven-resources-plugin` (process-resources):** copy authored `.adoc` and asset files into `target/staging/`. Three resource definitions:
- `${project.basedir}` → `target/staging/` (skip `pom.xml`, `target/`, `_theme/`; the `_*` prefix excludes by glob).
- `${project.basedir}/_theme/` → `target/staging/css/` (relocates authored `site.css` into the same `css/` directory as the SDS files fetched by `download-maven-plugin`, so AsciiDoctor's resource handling sees a single CSS directory).
- `${project.basedir}/../graphitron-rewrite/docs/` → `target/staging/architecture/` (every `.adoc` file).

**2. `exec-maven-plugin` invoking `roadmap-tool` (process-resources, after the copy):** trigger `roadmap-tool` to emit AsciiDoc into its **own** `roadmap-tool/target/generated-adoc/` directory via a new subcommand alongside the existing `generate`, `verify`, `next-id`, and `create`. Invocation follows the existing `-Dexec.args=...` / `commandlineArgs` convention introduced in `roadmap-tool/pom.xml` (so the new mode plugs into the same execution shape, no second exec stanza). A second `maven-resources-plugin` execution then copies the output directory into `target/staging/_generated/`. Each module writes only inside its own `target/`; the docs module never reaches into `roadmap-tool/target/`, only consumes its declared output. The reactor wiring guarantees ordering: `roadmap-tool` is listed as a build-scope `<dependency>` of the docs module, so Maven's reactor schedules it first.

**3. `asciidoctor-maven-plugin` (compile):** read from `target/staging/`, write HTML to `target/generated-docs/`. Config:
- `<sourceDirectory>${project.build.directory}/staging</sourceDirectory>`.
- `<resources>` copies `images/`, `css/`, `_includes/` from staging to output verbatim.
- Backend: `html5`. Source highlighter: `rouge`.
- Attributes: `:source-highlighter: rouge`, `:icons: font`, `:toc: left`, `:sectanchors:`, `:sectlinks:`, `:experimental:`, plus `:graphitron-version: ${project.version}`.
- Optional: `asciidoctor-diagram` extension, gated behind a profile so `mvn install` doesn't pay for a Graphviz/PlantUML dependency by default.

Why staging instead of multi-source AsciiDoctor: `asciidoctor-maven-plugin`'s `<sourceDirectory>` accepts one path. Could be worked around with multiple `<execution>` blocks, but that fragments output handling and complicates cross-section `xref:`. Staging keeps AsciiDoctor's mental model of "one tree in, one tree out" intact.

**`skip-docs` profile.** The AsciiDoctor execution is wrapped in a `<profile>` with `<activation><activeByDefault>true</activeByDefault></activation>` that's deactivated by `-P!docs` (or, equivalently, by activating a `skip-docs` profile via `-Pskip-docs`). Local dev loops that don't touch `.adoc` can opt out of the JRuby startup; CI always runs with the default profile so doc breakage is caught. Resources copy and roadmap-tool exec stay outside the profile (cheap, useful even when AsciiDoctor is skipped, and verifies that the staging tree is well-formed).

**Errors-vs-warnings policy.** AsciiDoctor's `<logHandler>` is configured `<failIf><severity>WARN</severity></failIf>` so missing xrefs, missing includes, and unresolved attributes fail the build. We don't tolerate "warnings" in docs the way we sometimes do in compiler output: a missing xref *is* doc rot.

The full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` command (per [`CLAUDE.md`](../CLAUDE.md)) keeps working unchanged; it just builds one extra module via the relative-path `<module>../docs</module>` entry in the rewrite parent pom.

## CI integration

Today neither GitHub Actions workflow in `.github/workflows/` builds the rewrite reactor: `maven-build.yml` runs `mvn ... --file pom.xml`, which is the **root** pom and only contains the legacy modules; `maven-publish.yml` runs on release-tag and also targets the root pom. So the rewrite reactor (and, by extension, the docs module added by this plan) is currently **not** verified in CI.

Phase 1 closes this gap with a new workflow rather than retrofitting `maven-build.yml`:

- **`.github/workflows/rewrite-build.yml`** (new): triggers on push and pull_request to both `main` and `claude/graphitron-rewrite`. Sets up JDK 25 (the rewrite floor; `maven-build.yml` is still on Java 21 for the legacy build), runs `mvn -f graphitron-rewrite/pom.xml verify -Plocal-db`. This is the workflow that catches doc breakage on PRs.
- The existing `maven-build.yml` (legacy, Java 21) stays unchanged.

This split is intentional: legacy and rewrite are different reactors with different Java floors and different test setups. Coupling them in one workflow has cost, separating them is closer to how they already work locally.

The new docs-deploy workflow (`.github/workflows/deploy-docs.yml`, see [Deployment](#deployment)) is a third workflow, distinct again.

## Content sources

The four sources from the Goal section, with each one's role and how it lands on the site:

| Source | Format | Audience | Site section | Mechanism |
|---|---|---|---|---|
| `/docs/*.adoc` | AsciiDoc (authored) | All users | Top-level (Quick start, FAQ, Why, Vision) | Direct copy to staging |
| `/graphitron-rewrite/docs/*.adoc` | AsciiDoc (authored) | Contributors / architects | "Architecture" subsection | Direct copy to staging under `architecture/` |
| `/graphitron-rewrite/roadmap/*.md` | Markdown (authored) | Curious users + contributors | "Roadmap" + "Plans" subsection | `roadmap-tool` generates `.adoc` to staging under `_generated/` |
| `alf/graphitron-landingsside` | Mixed | Historical | None directly; absorption only | One-time audit; gaps ported into `/docs/` as `.adoc` |

Per [Phase 4](#phase-4-roadmap-plans-and-changelog-rendering), the navigation distinguishes user-facing top-level pages from the architecture and roadmap sections. Casual visitors stay in the top-level. Contributors and the curious drill in.

## Sikt Design System integration

The current site is themed via the Sikt Design System: `@sikt/sds-core` (CSS custom properties for color, typography, spacing) and `@sikt/sds-button` (button styles). The existing `siktifisert.css` is mostly a thin layer of Docusaurus-specific selectors mapped onto SDS tokens (`--sds-color-*`, `--sds-typography-*`, `--sds-space-*`).

Our build needs the SDS CSS without dragging Node back in. Four approaches considered:

1. **Vendor the CSS files.** One-time copy of the compiled CSS from `node_modules/@sikt/sds-core/dist/` and `@sikt/sds-button/dist/` into `/docs/css/`. Pros: zero build-time dependencies, fully reproducible from a single git tree. Cons: stale unless a human re-vendors; needs refresh discipline tracked as a roadmap item.
2. **Maven `frontend-maven-plugin`.** Add Node + npm to the docs module build, run `npm install @sikt/sds-core @sikt/sds-button`, copy the dist CSS into the output. Pros: always fresh. Cons: re-introduces a Node toolchain (~30MB Node download on cold cache, plus `package.json` and `node_modules/` in the docs module) for what is ultimately two CSS files.
3. **Runtime CDN reference.** `<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@sikt/sds-core@<version>/dist/index.css">` from the rendered HTML. Pros: zero local copy, zero build dependency. Cons: every visitor's browser hits a third-party CDN (privacy / GDPR regression vs. today's same-origin Sikt K8s deployment); JSDelivr outage = unstyled site; old archived deploys silently rot if the CDN renames a path; CSP relaxation; SRI moves refresh discipline back to humans.
4. **Build-time fetch via `download-maven-plugin`.** The npm registry is a plain HTTP server, and JSDelivr / unpkg auto-mirror every npm package. `com.googlecode.maven-download-plugin` fetches an HTTP URL (with optional tarball unpack) and drops it into a target directory. Pros: pure Maven, no Node; pinned to an exact version in the pom; bumping SDS = changing one version string; output is baked into our Pages deploy so visitors hit Pages same-origin (no third-party at runtime); deploy is self-contained and reproducible. Cons: build now depends on `registry.npmjs.org` (or JSDelivr) being reachable at build time, but Pages deploy itself doesn't.

**Decision: option 4, `download-maven-plugin` against JSDelivr.** Specifically:

```xml
<plugin>
  <groupId>com.googlecode.maven-download-plugin</groupId>
  <artifactId>download-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>fetch-sds-core</id>
      <phase>generate-resources</phase>
      <goals><goal>wget</goal></goals>
      <configuration>
        <url>https://cdn.jsdelivr.net/npm/@sikt/sds-core@${sds.core.version}/dist/index.css</url>
        <outputDirectory>${project.build.directory}/staging/css</outputDirectory>
        <outputFileName>sds-core.css</outputFileName>
      </configuration>
    </execution>
    <!-- analogous execution for @sikt/sds-button -->
  </executions>
</plugin>
```

Versions are pinned via `<sds.core.version>` / `<sds.button.version>` properties in `/docs/pom.xml`. JSDelivr is preferred over the npm-registry tarball because it serves the CSS as a single direct file; the registry tarball would force an unpack step to extract `package/dist/*.css`. Either source is acceptable; the pinned version makes the choice of mirror philosophical.

This keeps the build pure-Maven, contributors don't need Node installed, the deployed site has no runtime third-party dependency, and SDS upgrades become a one-line version bump rather than a vendoring exercise.

License compliance: `@sikt/sds-core` ships a `LICENSE.md` (Sikt copyright notice plus a Haffer Font sub-notice). The build copies that file into `target/staging/css/` alongside the CSS so the deployed site preserves the attribution. `@sikt/sds-button` ships no `LICENSE` file (the published metadata is `UNLICENSED`); nothing to copy. Both packages are authorized for use on Sikt-owned sites by intra-Sikt scope (Graphitron is a Sikt project, mirror of the existing `graphitron.sikt.no` Docusaurus deployment); see the [Resolved](#open-questions-for-the-reviewer) entry for the full reasoning. The Haffer font itself is referenced at runtime from `https://static.sikt.no/Haffer-*.woff2` and never enters our deployment artifact.

Site-specific styles live in `site.css` and adapt the patterns from `siktifisert.css` onto Asciidoctor's default class names (`#header`, `#content`, `.sect1`, `.admonitionblock`, etc.) instead of Docusaurus's (`.navbar`, `.hero`, `.theme-doc-markdown`). The class-name remapping is mechanical; the design intent (SDS tokens for color/space/type, the `advantage-box` callout style, footer dark variant) carries over directly.

Asciidoctor's HTML structure is different enough from Docusaurus that a 1-to-1 stylesheet port isn't possible, but every SDS-token reference in the old CSS does port directly. Estimate: most of the visual work is rewiring selectors, not redesigning anything.

## Deployment

New workflow at `.github/workflows/deploy-docs.yml`:

- **Trigger:** push to `claude/graphitron-rewrite` (the rewrite trunk; this is where the docs source actually lives) plus `workflow_dispatch` for manual republishes. Pull requests get *built* but not deployed; see [PR preview](#pr-preview) below. We do not trigger on `main` because the rewrite reactor is not on `main` today and merging the rewrite into `main` is a separate decision outside the scope of this plan. If/when that decision lands, this trigger updates in one line.
- **Concurrency:** `concurrency: { group: "pages", cancel-in-progress: false }`, so two close-together pushes don't race the Pages API. Pages itself only accepts one in-flight deployment per environment.
- **Permissions** (workflow scope, not job scope, so both jobs inherit): `contents: read`, `pages: write`, `id-token: write`.
- **Job 1 (build):**
  - Checkout, set up JDK 25, cache `~/.m2`.
  - Run `mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am package` (no `-Plocal-db`: with `-am` rooted at `:graphitron-docs`, the dependency closure is `roadmap-tool` only, never `graphitron-fixtures`, so the fixtures-jar footgun does not apply; adding `-Plocal-db` here would only slow the deploy down to no purpose).
  - Upload `docs/target/generated-docs/` as a Pages artifact via `actions/upload-pages-artifact`.
- **Job 2 (deploy):** `actions/deploy-pages@v4`, depends on job 1.

### PR preview

Render quality should be observable before merge, not only after deploy. A separate workflow (`.github/workflows/preview-docs.yml`, or a job in `rewrite-build.yml`) builds the docs module on every PR that touches `/docs/**`, `/graphitron-rewrite/docs/**`, or `/graphitron-rewrite/roadmap/**` and uploads `target/generated-docs/` as a regular workflow artifact (not a Pages artifact, no deploy). Reviewers download and open `index.html` locally to spot-check; this is cheaper and lower-risk than per-PR Pages environments. We can revisit per-PR Pages environments later if download-and-open friction proves too high.

Two-stage hosting:

**During Phases 1-4:** Pages serves the built site at the default GitHub URL (`https://sikt-no.github.io/graphitron/`). No custom domain configured yet, no `CNAME` file in the build output. The live `graphitron.sikt.no` continues to serve the old Docusaurus build from Sikt's internal K8s; nothing changes for end users. This gives us a real deployed URL to iterate against without disrupting the live site.

The `baseUrl` for Asciidoctor needs to reflect the `/graphitron/` subpath while we're on the default Pages URL (relative-only links in the AsciiDoc source avoid the issue; absolute internal links would need rewriting at cutover). Prefer `xref:` over raw URLs throughout.

**At Phase 5a cutover:** Add `CNAME` containing `graphitron.sikt.no` to `/docs/`, configure the custom domain in GitHub Pages settings, flip DNS from Sikt K8s ingress to `sikt-no.github.io`, **pause** (do not dismantle) the GitLab CI pipeline and Sikt K8s deployment so rollback stays cheap. Matomo analytics is reattached via a small `<script>` injected into the AsciiDoctor template, or dropped; the call happens at 5a, not earlier.

**At Phase 5b decommission** (only after a buffer window with a stable 5a deploy): tear down the K8s manifests, retire the GitLab CI pipeline, archive the `alf/graphitron-landingsside` repo.

Repo-level setup (one-time, has to be done in GitHub UI by a maintainer, not by Claude):

- **Phase 1:** Settings → Pages → Source: GitHub Actions.
- **Phase 5a:** Settings → Pages → Custom domain: `graphitron.sikt.no`, enforce HTTPS, plus DNS update on `sikt.no` to point `graphitron` → `sikt-no.github.io`.

## Content migration

Two distinct migration efforts: converting in-repo `.md` to `.adoc` (mostly mechanical), and absorbing what's worth keeping from the old Docusaurus site (Phase 3, audit-driven).

### In-repo `.md` → `.adoc` (Phase 2)

**`/docs/`** (5 files):
- `/docs/README.md` → `/docs/README.adoc`. Stays the GitHub-rendered landing for the directory.
- `/docs/vision-and-goal.md` → `/docs/vision-and-goal.adoc`.
- `/docs/graphitron-principles.md` → `/docs/graphitron-principles.adoc`.
- `/docs/security.md` → `/docs/security.adoc`.
- `/docs/dependencies.md` → `/docs/dependencies.adoc`.

**`/graphitron-rewrite/docs/`** (8 files convert + 1 moves):
- `README.md` → `README.adoc`. Architecture overview.
- `workflow.md` → `workflow.adoc`. Backlog → Spec → Ready → ... pipeline.
- `rewrite-design-principles.md` → `rewrite-design-principles.adoc`.
- `rewrite-model.md` → `rewrite-model.adoc`.
- `argument-resolution.md` → `argument-resolution.adoc`.
- `code-generation-triggers.md` → `code-generation-triggers.adoc`.
- `runtime-extension-points.md` → `runtime-extension-points.adoc`.
- `getting-started.md` → `getting-started.adoc`.
- `claude-code-web-environment.md` → **moves to `.claude/web-environment.md`** (not converted to `.adoc`; not part of the public site). The `.claude/` directory is the existing Claude Code config home (`agents/`, `commands/`, `scripts/`, `skills/`, `settings.json`); the web-environment doc is purely AI-tooling content. Stays markdown for AI-agent readability. Inbound references in `CLAUDE.md`, root `README.md`, `graphitron-rewrite/roadmap/rewrite-test-tier-guide.md`, `graphitron-rewrite/roadmap/changelog.md`, and `.claude/scripts/session-start-web-env.sh` (banner comment + two error-message strings) are updated to the new path in the same commit. Root `README.md`'s "see [claude-code-web-environment.md] for the full rewrite build flow" reference is replaced with a pointer to the new public-facing `getting-started.adoc` instead, since the README is public-facing.

**Roadmap content** (`graphitron-rewrite/roadmap/*.md`, `changelog.md`): stays markdown, build-time conversion via `roadmap-tool` (see [Roadmap, plans, and changelog rendering](#roadmap-plans-and-changelog-rendering)).

Conversion mechanics:

1. `kramdoc` (markdown-to-asciidoc) handles the bulk of each `.md` file.
2. Spot-check each output: `<details>` to `[%collapsible]`, custom CSS classes preserved as inline passthroughs, links to other pages rewritten as `xref:`.
3. Update every reference to the original `.md` in `CLAUDE.md`, READMEs, and other docs to point at the new `.adoc`.
4. Delete the original `.md` once the `.adoc` is in place and rendering correctly.
5. Verify the build (`mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am package`) produces a clean site with no AsciiDoc warnings, then push and check the deployed Pages URL.

### Absorption from `alf/graphitron-landingsside` (Phase 3)

The user's framing: "the old site has some content that we might want to migrate, but most likely it's already covered by our current markdown content." Treat the old site as a fallback, not a primary source. Absorption is a gap-fill audit.

Old-site content inventory:
- `docs/why.md` (32 lines, "Why Graphitron"). Likely covered by `vision-and-goal.adoc`; check the framing and pull anything `vision-and-goal` lacks.
- `docs/faq.md` (50 lines). **Probably net-new on our side**; we have no FAQ today. Port to `/docs/faq.adoc`.
- `docs/quick_start_guide.md` (153 lines, with `<details>` collapsibles around code). Cross-reference against `/graphitron-rewrite/docs/getting-started.adoc`. If `getting-started` covers the same ground, drop the old one. If the old one has the user-facing quick-start framing and `getting-started` is contributor-oriented, port to `/docs/quick-start.adoc`.
- `docs/documentation.md` (53 lines, technical-component overview). Mostly describes legacy plugins (`graphitron-maven-plugin`, `graphitron-schema-transformer`); for the rewrite era this content is partially obsolete. Audit for anything still relevant; the rest gets dropped.

Visual / asset migration (Phase 1 deliverable, not absorption):
- SVGs we keep: `logo.svg`, `Favicon-Dark.svg`, `Person2.svg`, `Person3.svg`, `Personer.svg`, `Creature1.svg`, `Creature2.svg`, `Creature3.svg`. Move to `/docs/images/`.
- `src/css/siktifisert.css` design intent (SDS tokens, `.advantage-box`, footer dark style). Reimplemented in `/docs/_theme/site.css` against AsciiDoctor selectors.
- The homepage hero + 3-feature row from `src/pages/index.js` and `src/components/HomepageFeatures/index.js`. Reimplemented as a static AsciiDoc landing page in `/docs/index.adoc`.

**Drop entirely:**
- `src/pages/integrasjonstester.js`, `src/components/IntegrationTests/`, `src/data/integrationTests.json`. Out of scope per the user.
- Default Docusaurus `blog/` posts. Template content, no value.
- `static/img/undraw_docusaurus_*.svg` and `docusaurus.png`. Docusaurus boilerplate.
- `src/css/custom.old.css`. Already labelled stale by the source repo.
- `src/theme/Root.js`. Force-light-theme hack, won't be needed.
- `Dockerfile`, `deployment.yaml`, `.gitlab-ci.yml`. Replaced by GitHub Actions in the cutover phase.

Absorption deliverable: a one-shot audit document (committed and removed in the same Phase 3 PR) listing each old-site page with one of three verdicts: *already-covered-by `<page>`*, *gap, ported to `<page>`*, *dropped (reason)*.

## Roadmap, plans, and changelog rendering

Treats the roadmap as first-class content. `roadmap-tool` (the existing Maven module that today regenerates `graphitron-rewrite/roadmap/README.md` from per-item front-matter) gets an additional output mode: emit AsciiDoc into `/docs/target/staging/_generated/`, then the asciidoctor build picks it up automatically. No human keeps anything in sync.

### What gets generated

- **`_generated/roadmap.adoc`**: the rendered status board. Items grouped by `status:` (Backlog → Spec → Ready → In Progress → In Review). Within each group, sorted by `bucket:` (architecture, stubs, cleanup) then `priority:`. Each row shows `id:` (R<n>), title, bucket, priority, theme, and a link to the corresponding plan page. Active and Backlog rows pick up "blocked by" annotations linking each `depends-on:` slug, mirroring the rendered README behaviour. A short intro paragraph sets expectations: "this is our internal-and-external roadmap including refactors; substantive user-visible items are tagged `bucket: architecture`."
- **`_generated/by-theme.adoc`**: the cross-cutting "By theme" index already produced for the README, rendered as its own page so visitors can navigate via theme alongside status. Sources from the same `theme:` field; no duplicated logic.
- **`_generated/changelog.adoc`**: converted directly from `graphitron-rewrite/roadmap/changelog.md`. "Recently shipped" view.
- **`_generated/plans/<slug>.adoc`**: one page per item, body converted from the markdown plan. URL slug stays slug-based (more readable than `R<n>`); the `id:` is rendered inside the page, not in the path. Front-matter rendered as a small attribute box at the top (id, status, bucket, priority, theme, depends-on, deferred).

### Markdown-to-AsciiDoc conversion in `roadmap-tool`

Plans are written in markdown (per `workflow.md`'s authoring convention). `roadmap-tool` already parses front-matter as YAML and reads the body as a string. To emit AsciiDoc, two options:

1. **Pure pass-through with a header.** Wrap the markdown body in an AsciiDoc passthrough block, prepend an AsciiDoc header (title, attribute box). AsciiDoctor's markdown-compat backend renders the body acceptably. Pros: no conversion logic needed in `roadmap-tool`. Cons: subtle markdown features (e.g., GitHub-flavored task lists) may render imperfectly.
2. **Library conversion.** Use a Java markdown→AsciiDoc converter (CommonMark with a custom AsciiDoc renderer, or `kramdoc-java` if available). Pros: cleaner output, handles edge cases. Cons: adds a dependency to `roadmap-tool`.

Recommendation: **option 1** to start. The plans are mostly prose and code fences; AsciiDoctor handles those cleanly via its built-in markdown compatibility mode. Escalate to option 2 if rendering quality proves a problem.

### Navigation and discoverability

- **Top-level nav (main user docs):** What and why, Quick start, FAQ, Documentation index. Casual visitor stays here.
- **Secondary nav row or footer link:** *Architecture* (rewrite-internal docs), *Roadmap*, *Changelog*.
- **Plans pages reachable only from the roadmap index**, not in any nav. Determined readers click through; jargon-heavy pages don't pollute primary navigation.

This satisfies "max-transparency, full roadmap renders" while keeping casual visitors out of the deeply-internal plans.

### Coupling

The docs module's build depends on `roadmap-tool` running first. Three ways:

1. **Reactor order.** `roadmap-tool` builds before `docs` because docs lists it as a `<dependency>`. The docs `pom.xml` declares `<dependency>` on `roadmap-tool` (scope `provided` or as a plugin dep), and `exec-maven-plugin` invokes it.
2. **Build helper plugin.** Wrap `roadmap-tool`'s emit step in a small Maven plugin and bind it to `process-resources`. Heaviest, but most idiomatic.
3. **Bash step in `<exec>`.** `exec-maven-plugin` runs `mvn -pl roadmap-tool exec:java -q -Doutput=...` before AsciiDoctor. Simple but brittle.

Recommendation: **option 1** (reactor + exec). Keeps everything in one Maven invocation, no nested mvn, no plugin to maintain.

## Authoring conventions

A short `/docs/README.adoc` (rendered by GitHub) for contributors:

- File extension: `.adoc` (not `.asciidoc`, not `.asc`), for consistency.
- One H1 per file, set as the page title via `= Title`.
- Use `include::` for shared snippets. Acknowledge that `include::` does not resolve in GitHub's web preview, so prefer self-contained pages where possible; reserve includes for genuine reuse (e.g., a "Prerequisites" block shared across getting-started variants).
- Code blocks: `[source,java]` (or `xml`, `yaml`, etc.) with optional callouts.
- Cross-page links: `xref:` not raw URLs.
- Images live next to the page that uses them, or in a shared `images/` folder; never hot-link to the deployed site.

## Workflow integration

This plan touches the conventions documented in [`workflow.md`](../docs/workflow.adoc) and [`CLAUDE.md`](../../CLAUDE.md):

- **Scope carve-out for `/docs/`.** `CLAUDE.md`'s scope rule currently restricts AI work to `graphitron-rewrite/`. This plan moves AI-managed content into `/docs/` as well. The Phase 1 commit updates the scope rule to read "Scope: `graphitron-rewrite/` and `/docs/`" with a one-line note that `/docs/` is the source for the documentation site.
- **`.md` → `.adoc` ripple in `CLAUDE.md`.** Phase 2 deletes the `/graphitron-rewrite/docs/*.md` files. `CLAUDE.md` currently links to several of those (`workflow.md`, `claude-code-web-environment.md`, etc.). The Phase 2 commit `git grep`s for every reference and rewrites them to the new `.adoc` paths. Same applies to inter-doc references inside the docs themselves.
- **Documentation site reference.** `CLAUDE.md` gains a brief "Documentation site" section noting that `/docs/` is the source for `graphitron.sikt.no`, that `/graphitron-rewrite/docs/` and the roadmap also render there, and that doc changes ship through the same trunk-based flow as code.
- `workflow.md`'s "Plans with a user-visible surface" rule already mandates that user-facing changes include a draft of the user docs in the plan. Once this site is live, that rule binds against `/docs/` (the draft moves into the real page when the feature ships). `workflow.md` itself stays authoritative for plan-file conventions; the rendered version on the site is the same content.
- The existing `.github/workflows/maven-build.yml` is the **legacy** reactor's CI (builds the root pom only, JDK 21). It stays unchanged. The new `rewrite-build.yml` (added in Phase 1) is what verifies the rewrite reactor and, by extension, the docs module on PR. See [CI integration](#ci-integration).

## Phases

Six phases (Phase 5 split into 5a cutover and 5b decommission), each with an observable end state on a real URL or in deployed infrastructure. Each phase is independently shippable and trunk-bound; nothing in a later phase reaches back into an earlier one.

### Phase 1: Pipeline (skeleton site, deployed to default Pages URL) — shipped at `a4675bf`

`https://sikt-no.github.io/graphitron/` serves the placeholder AsciiDoc site, built by the rewrite reactor (`mvn -pl :graphitron-docs -am package`) and deployed by `.github/workflows/deploy-docs.yml`. SDS branding renders end-to-end. `rewrite-build.yml` and `preview-docs.yml` shipped with the same commit. `CLAUDE.md` scope extended to `/docs/`.

Two deviations worth flagging for later phases:

- **NPM tarballs instead of JSDelivr.** The plan called for `https://cdn.jsdelivr.net/npm/@sikt/sds-*@<v>/dist/index.css`; JSDelivr is firewalled in the Claude Code Web sandbox (returns 403 with `host_not_allowed`), so the build fetches `https://registry.npmjs.org/@sikt/sds-*/-/sds-*-<v>.tgz` with `<unpack>true</unpack>` and `maven-antrun-plugin` flattens `package/dist/index.css` to `target/staging/css/sds-{core,button}.css`. Same effective semantics: pinned version, no Node, no runtime third-party. If JSDelivr later becomes preferred (single direct file vs. tarball unpack), the switch is a one-line change in `/docs/pom.xml`.
- **Logo and favicon not yet in `/docs/images/`.** The plan listed them as a Phase 1 deliverable (sourced from `alf/graphitron-landingsside`). The implementing session didn't have access to that repo, so the placeholder runs without them. Phase 3 (absorption) is the natural place to bring them in; alternatively, drop them in any time before that and the build picks them up.

### Phase 2: In-repo content migration

End state: the deployed Pages URL serves all of the existing in-repo doc content. Both `/docs/*.md` and `/graphitron-rewrite/docs/*.md` have been converted to `.adoc`, the originals deleted, and `CLAUDE.md` (plus any inbound links) updated. The site has a primary nav for product docs and a secondary "Architecture" section for the rewrite-internal docs. The live `graphitron.sikt.no` still serves the old Docusaurus build.

Deliverables:

- 5 converted pages from `/docs/`: `vision-and-goal`, `graphitron-principles`, `security`, `dependencies`, plus updated `README.adoc`. Original `.md` files deleted.
- 8 converted pages from `/graphitron-rewrite/docs/`: `README`, `workflow`, `rewrite-design-principles`, `rewrite-model`, `argument-resolution`, `code-generation-triggers`, `runtime-extension-points`, `getting-started`. Original `.md` files deleted.
- `/graphitron-rewrite/docs/claude-code-web-environment.md` moved to `.claude/web-environment.md` (stays markdown, not on the public site; see [Content migration](#in-repo-md-adoc-phase-2)).
- `/docs/pom.xml` updated: staging step now also copies `/graphitron-rewrite/docs/` to `target/staging/architecture/`.
- `CLAUDE.md` and any other inbound links updated to point at the new `.adoc` paths.
- Authoring conventions added to `/docs/README.adoc`.
- Site nav: primary row (Vision, Quick start, FAQ, Documentation), secondary row or footer link to *Architecture*.
- Homepage hero + 3-feature layout reimplemented in static AsciiDoc, using SDS-styled blocks.

Verification: every link in `CLAUDE.md` and inter-doc references resolve; the deployed Pages URL renders both sections cleanly.

### Phase 3: Old-site absorption

End state: any content from `alf/graphitron-landingsside` worth keeping has been ported into `/docs/`; everything else is acknowledged and dropped. A short audit document accompanies the PR.

Deliverables:

- One-shot audit file (committed in this PR, deleted in the same PR after the listed changes land): for each old-site page, one of *already-covered-by `<page>`*, *gap, ported to `<page>`*, *dropped (reason)*.
- New pages where gaps exist; current expectation: `/docs/faq.adoc` is genuinely net-new; `/docs/quick-start.adoc` may or may not be net-new depending on whether the existing `getting-started.adoc` covers the same ground.
- Old-site visual assets (SVGs, logo, favicon) confirmed migrated. (May have already happened in Phase 1; absorption verifies completeness.)

Verification: side-by-side comparison of `https://sikt-no.github.io/graphitron/` vs `https://graphitron.sikt.no/`; confirm the new site has feature parity (minus integrasjonstester and blog) and the audit's *dropped* verdicts are defensible.

### Phase 4: Roadmap, plans, and changelog rendering

End state: the deployed Pages URL has a Roadmap section linked from the secondary nav, with the per-status status board, a "By theme" cross-cutting index, a "Recently shipped" changelog, and per-plan pages reachable only from the roadmap index. Roadmap content auto-updates on every push: edit a `.md` in `graphitron-rewrite/roadmap/`, push, the next deploy reflects the change.

Deliverables:

- `roadmap-tool` extension: new subcommand emitting AsciiDoc into a configurable output directory, sharing the existing parsing / validation path with `generate` and `verify` so the renderers can't drift on `id:`, `theme:`, or `depends-on:` semantics. Existing GitHub-README emission keeps working unchanged.
- `/docs/pom.xml` updated: staging step now also runs `roadmap-tool` to populate `target/staging/_generated/`. Invocation uses the `-Dexec.args=...` / `commandlineArgs` convention introduced for `next-id` and `create`. Reactor order ensures `roadmap-tool` builds before `docs`.
- Roadmap index page and "By theme" page wired into secondary nav; plan pages reachable from those entry points only.
- Recommended option 1 ("pass-through with header") used initially for markdown→AsciiDoc; revisit if rendering quality is poor.
- Documentation: short note in `/docs/README.adoc` and `workflow.adoc` that the roadmap renders publicly.

Verification: the deployed Roadmap page reflects current `graphitron-rewrite/roadmap/*.md` contents; flipping an item's `status:` and pushing causes the next deploy to show the new status.

### Phase 5a: Cutover (custom domain flip, old infra still warm)

End state: `https://graphitron.sikt.no/` serves the new Maven-built AsciiDoc site from GitHub Pages. The old Sikt K8s deployment and GitLab CI are paused but **still rebuildable** for a defined buffer window. The `alf/graphitron-landingsside` repo is **not yet** archived.

Deliverables (Claude-side, in this repo):

- Add `/docs/CNAME` containing `graphitron.sikt.no`.
- Update root `README.md` to keep the "Online documentation" link (URL doesn't change).
- Matomo decision: reattach via `<script>` injection in the AsciiDoctor template, or drop. Whatever's decided lands as part of this phase; a small follow-up roadmap item is fine if reattaching turns into more than a 5-line edit.

Deliverables (Sikt platform / DNS team, coordinated, **not** Claude-driven):

- Configure custom domain in repo Pages settings (Settings → Pages → Custom domain: `graphitron.sikt.no`, enforce HTTPS). Requires repo admin.
- DNS update on `sikt.no`: `graphitron` → `sikt-no.github.io`.
- **Pause** (don't dismantle) the GitLab CI pipeline and scale the Sikt K8s deployment to 0. Keep the manifests so a redeploy is one `kubectl apply` away.

Buffer window: at least one full working week with the new site live. Watch for broken links, missing pages, regressions, Matomo data discontinuities, and any internal Sikt links that turn out to point at old-site paths the new site doesn't have.

Verification: hit `https://graphitron.sikt.no/`, confirm HTTPS valid, confirm content matches Phase 4 deploy, run an external-link checker against the deployed site.

**Rollback during 5a:** flip DNS back; bring K8s deployment back to its prior replica count; the old site is again serving. The new site continues to live at `sikt-no.github.io/graphitron/` while the issue is investigated.

### Phase 5b: Decommission

End state: the old Sikt K8s deployment is gone, `alf/graphitron-landingsside` is archived with a README pointing here, and the cutover is irreversible-by-default.

Preconditions: Phase 5a's buffer window has elapsed without rollback; site is stable; no outstanding issues attributed to the cutover.

Deliverables (Sikt platform team):

- Remove the K8s Deployment/Service/Ingress objects.
- Decommission the GitLab CI pipeline.

Deliverables (Claude-driven, in `alf/graphitron-landingsside`):

- Add a redirect README pointing to `https://graphitron.sikt.no/`.
- Archive the repo (a maintainer action; not done by Claude).

Verification: GitLab pipeline shows no recent runs; K8s namespace is clean; archived repo's README displays the redirect.

## Open questions for the reviewer

These are not pre-blockers (Phases 1-4 can proceed without resolving them), but they need answers before Phase 5a:

- **Repo Pages settings ownership.** Who can flip Pages Source = GitHub Actions on `sikt-no/graphitron`? Same person needed for the Phase 5a custom-domain step. Identify them at Phase 1 so 5a doesn't stall on access.
- **DNS team coordination at Sikt.** Phase 5a depends on a `sikt.no` DNS update. Lead time? Change-management process? Identify the contact at Phase 1; queue the ticket at Phase 4.
- **Branch source-of-truth for the docs site.** Plan deploys from `claude/graphitron-rewrite` (the rewrite trunk). When/if rewrite merges to `main`, the deploy trigger updates with it. No action needed today; flagged so the eventual merge doesn't surprise anyone.
- **Matomo continuity.** Phase 5a-resolvable; flagged here so we're not surprised. If continuity-of-data matters, reattaching has to happen *at* cutover, not after, otherwise we get a gap.

Resolved (no longer open):
- Claude-internal docs: `claude-code-web-environment.md` moves to `.claude/web-environment.md`, not published.
- **SDS license.** Checked the published metadata: `@sikt/sds-core@5.3.0` ships a `LICENSE.md` containing only a "Copyright (c) Sikt … All rights reserved" notice plus a separate "Haffer Font Copyright (c) Displaay (Martin Vácha), All rights reserved" sub-notice; `@sikt/sds-button@4.6.1` is `"license": "UNLICENSED"` with no `LICENSE` file in the tarball. Strictly per the metadata, neither package grants redistribution rights. Resolved by intra-Sikt scope: Graphitron is itself a Sikt project, the existing `graphitron.sikt.no` Docusaurus deployment already bundles SDS publicly, and the Maven build is the same effective distribution via a different mechanism; intra-Sikt usage on a Sikt-owned site is authorized. Notes: (1) The Haffer font is referenced via `@font-face` URLs at `https://static.sikt.no/Haffer-*.woff2`; the font binaries are served at runtime by Sikt's static CDN and never enter our deployment artifact, so the Displaay sub-copyright doesn't extend to our redistribution. (2) Only `sds-core` ships a `LICENSE.md` to copy through; `sds-button` has none. (3) If at some future point Sikt publishes SDS under an explicit license, the `LICENSE-*` copy step in `/docs/pom.xml` should be revisited. (4) If Graphitron ever leaves Sikt's stewardship, the SDS bundling assumption needs to revisit too.

## Risks and mitigations

- **Build-time fetch depends on JSDelivr (or `registry.npmjs.org`) reachability.** A clean Maven build with no local cache fails if the mirror is unreachable. Mitigation: `download-maven-plugin` caches fetched files in `~/.m2/repository/.cache/download-maven-plugin/`, so warm builds are unaffected; CI caches `~/.m2`, so CI failures only happen on cold-cache runs while the mirror is down. The deployed Pages site itself has no runtime CDN dependency. Residual risk on SDS major releases breaking our `site.css` selectors is bounded by version pinning: nothing changes until someone bumps `<sds.core.version>`.
- **Default Asciidoctor styling looks dated.** Mitigation: that's exactly what `site.css` solves by porting the SDS-token usage from `siktifisert.css`. Phase 1 verifies the branding looks right *before* later phases pile content on top.
- **The Maven build now fails when docs are broken.** Intent (catch rot in CI), but a typo in `.adoc` blocks a release. Mitigation: AsciiDoc errors-vs-warnings is configurable in the plugin; start with "fail on error, warn on missing xref" until settled, then tighten.
- **Phase 5a DNS cutover requires Sikt platform / DNS team coordination.** Mitigation: Phases 1-4 are entirely independent of DNS; the new site is fully styled and content-complete on `sikt-no.github.io/graphitron/` before the cutover ticket is even raised. Identify the DNS contact at Phase 1 so Phase 4 can queue the ticket.
- **Cutover loses rollback once K8s is decommissioned.** Mitigation: Phase 5 is split into 5a (DNS flip with K8s paused but warm) and 5b (decommission after a buffer window). Rollback during 5a is a DNS reversal plus a `kubectl scale`.
- **Matomo loss.** If we drop Matomo at Phase 5a cutover we lose continuity in usage data. Mitigation: the decision lives at 5a (not later); reattaching is a 5-line `<script>` injection in the AsciiDoctor template, dropping is the trivial alternative. Either way it's chosen *at* the cutover, so there's no analytics gap.
- **Subpath base URL during Phases 1-4.** While on `sikt-no.github.io/graphitron/`, absolute internal links break. Mitigation: enforce `xref:` and relative-path links in the authoring conventions, and configure Asciidoctor `:imagesdir: images` so image paths stay relative.
- **Markdown→AsciiDoc conversion in `roadmap-tool` produces ugly output for some plans.** Mitigation: option-1 pass-through is the cheap default; if specific plans render poorly, escalate that one to option-2 library conversion or hand-tune the source `.md`. Plans deleted on Done means bad-render risk has a short half-life.
- **Convert-and-delete of `/graphitron-rewrite/docs/*.md` breaks every existing inbound link.** Mitigation: Phase 2's CLAUDE.md update step is mandatory, not optional; grep the repo for the old `.md` paths and update all hits in the same PR.
- **Scope creep on the docs site.** This plan grew from "replace Docusaurus" to "consolidate four sources." Risk: each new addition slows the cutover. Mitigation: phases 1-3 are the minimum viable replacement; phase 4 (roadmap rendering) is additive and could ship after phase 5 if needed. Don't let phase 4 block the cutover.

## Roadmap entries

- This file (`docs-site-asciidoc.md`): keep through all phases (1, 2, 3, 4, 5a, 5b). Collapse each phase to a "shipped at `<sha>`" note when it lands; remove the file when 5b lands and the cutover is verified.
- No periodic-refresh spinoff needed. Build-time fetch via pinned version means SDS upgrades are a one-line `<sds.core.version>` / `<sds.button.version>` bump in `/docs/pom.xml` plus any selector adjustments in `site.css` if a major release changes class names. Anyone touching the docs can do this; no separate tracking item.
- Spinoff (Backlog, possible after Phase 4 lands): **`roadmap-public-flag.md`**, opt-in `public: true` front-matter flag to filter which roadmap items appear on the site. Only worth doing if max-transparency proves too noisy in practice.
- Other spinoffs may surface during Phases 2-3 (e.g., client-side search, versioned docs, autogenerated integration-tests page from real test sources); add them as Backlog when they appear, don't pre-spec.
