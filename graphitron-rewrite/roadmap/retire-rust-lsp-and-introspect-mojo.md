---
id: R91
title: "Retire Rust graphitron-lsp + delete IntrospectMojo"
status: Backlog
bucket: Backlog
priority: 18
theme: legacy-migration
depends-on: []
---

# Retire Rust graphitron-lsp + delete IntrospectMojo

R18 carved this out of Phase 7. The graphitron-rewrite delivery
shipped the Java LSP that replaces the Rust one (R18 Phases 0–6;
see graphitron-rewrite/roadmap/changelog.md for the implementation
commit anchors). The LSP module ships through
graphitron-rewrite-parent's normal release cycle, so consumers can
pick it up the moment the next release tag lands. What remains is
the legacy retirement: archive the Rust LSP repo, delete the legacy
introspect Mojo, and document the consumer-side migration. None of
that work blocks graphitron-rewrite delivery, and it runs on a
different (consumer-facing) schedule than the rewrite roadmap.

What this item should deliver:

1. **Archive the Rust LSP repo** at
   [`gitlab.sikt.no/fs/graphitron-lsp`](https://gitlab.sikt.no/fs/graphitron-lsp).
   Update the README to point at the Java LSP under
   `graphitron-rewrite/graphitron-lsp/` and the
   `mvn graphitron:dev` goal recipe. Set the GitLab project to
   archived once the migration window closes.
2. **Delete `IntrospectMojo` from `graphitron-maven-plugin`**:
   `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java`
   (~327 LOC) plus the `LspConfig` record(s) it instantiates.
   The umbrella roadmap entry "Retire `graphitron-maven-plugin` +
   `graphitron-schema-transform`" (`retire-maven-plugin.md`)
   eventually deletes the whole legacy plugin module; this item
   is one sub-step under that umbrella.
3. **Document consumer migration.** Add a
   "Migrating from the Rust LSP" section to
   `graphitron-rewrite/docs/getting-started.adoc` (alongside the
   existing Phase 1 "Dev loop" recipe). Cover: editor-config
   change (stdio process spawn → TCP connect to
   `localhost:8487`), removal of the
   `graphitron-maven-plugin:introspect` `<execution>` from the
   consumer POM, and the timeline for archiving the Rust repo.

Out of scope: graphitron-rewrite-side code changes — the Java LSP
is feature-complete from R18's perspective. R90 (`lsp-javaparser-
javadoc-and-definitions.md`) tracks the LSP follow-up enhancements
(Javadoc surfacing, per-line definitions, `@externalField`
completion, `argMapping` autocomplete) and is independent of this
retirement work.

Predecessors: R18 (`graphitron-lsp.md`, the Java LSP rewrite plan
this item carved out of). Umbrella: `retire-maven-plugin.md`. The
order between archiving the Rust repo (item 1) and deleting
`IntrospectMojo` (item 2) does not matter; both can land
independently when the consumer schedule allows.
