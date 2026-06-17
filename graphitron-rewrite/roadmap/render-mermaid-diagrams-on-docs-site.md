---
id: R326
title: "Render Mermaid diagrams on the published docs site"
status: Backlog
bucket: tech-debt
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Render Mermaid diagrams on the published docs site

R316's "model at a glance" section uses a Mermaid `classDiagram`. It renders on GitHub (where
roadmap specs are actually read) but not on the AsciiDoctor docs site at graphitron.sikt.no,
where it currently shows as a code listing. Future model specs will lean on diagrams; we want
them rendered on the site too.

## The gap

The docs pipeline is: roadmap `.md` to `.adoc` via `roadmap-tool`
(`no.sikt.graphitron.roadmap.Main` `render-adoc`, the `mdBodyToAdoc` method), then
`asciidoctor-maven-plugin` (`process-asciidoc`) to HTML. In `Main.mdBodyToAdoc` (around line 737)
a fenced `mermaid` block is converted to `[source,mermaid]` plus a `----` listing block, which
AsciiDoctor renders as rouge-highlighted text. There is no `asciidoctorj-diagram` dependency and
no diagram backend.

## What it requires

1. **roadmap-tool** (small): in `Main.mdBodyToAdoc`, special-case diagram languages (`mermaid`,
   possibly `plantuml`) to emit an asciidoctor-diagram block (`[mermaid]` + delimited block)
   rather than `[source,<lang>]`. Add a unit test asserting the emitted adoc.
2. **docs/pom.xml** (small): add `asciidoctorj-diagram` to the `asciidoctor-maven-plugin`
   dependencies and register it (`<requires>asciidoctor-diagram</requires>` or the attribute
   equivalent).
3. **A rendering backend** (the real cost, and the decision): asciidoctor-diagram's mermaid
   backend shells out to `mmdc` (mermaid-cli), needing Node + a headless Chromium in the
   `docs-build` and `preview-docs` CI jobs (`.github/workflows/rewrite-build.yml`,
   `.github/workflows/preview-docs.yml`); or adopt `asciidoctor-kroki` against a Kroki server
   (external `kroki.io`, subject to the network policy, or self-hosted, which the web sandbox
   cannot run since it has no Docker). Pick the backend that fits CI + the network policy and
   document the trade.

## Constraints / risks

- CI infra across two workflow jobs; both `docs-build` (trunk) and `preview-docs` (PR) must get
  the backend or diagrams break in one of them.
- Web sandbox has no Docker (per CLAUDE.md), so a self-hosted Kroki is not runnable locally;
  mermaid-cli pulls a heavy Chromium.
- External `kroki.io` may be blocked by the environment network policy.

## Acceptance

- The R316 "model at a glance" `classDiagram` renders as inline SVG on the generated site (the
  roadmap plan page), and both `docs-build` and `preview-docs` stay green.

## Out of scope

- GitHub rendering already works (no change needed there).
- Converting existing prose / ASCII diagrams to Mermaid.
