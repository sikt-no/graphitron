---
id: R482
title: "Purge transient roadmap references from javadoc; guard against reintroduction"
status: In Review
bucket: cleanup
priority: 12
theme: docs
depends-on: []
created: 2026-07-15
last-updated: 2026-07-16
---

# Purge transient roadmap references from javadoc; guard against reintroduction

Generator-module javadoc is riddled with citations to roadmap items (`R398`, `R408`, `R279 slice 5`, `R246 / R258 / R266`), prose pointers ("tracked on the rewrite roadmap", "would warrant its own roadmap item"), and hard links to slug files (`roadmap/nodeidreferencefield-join-projection-form.md`). Roadmap items are transient: they get renumbered, ship and leave a gap, or get discarded, so a doc comment that leans on one is stale the moment that item moves. Documentation should reference the published docs site (the user manual for author-facing behavior, `docs/architecture/` for contributor-facing rationale) or a live symbol, never a roadmap item. This item purges the references and installs a guard so agents stop reintroducing them.

The purge is deliberately bounded to keep the guard (its actual payload) from being held hostage by open-ended docs authoring. For each citation, in priority order:

1. **Strip and keep.** If the sentence carries real content and the `R<n>`/slug was decoration, delete only the citation and keep the prose. This is the default.
2. **Relink.** If the citation stands for a linkage to a live symbol, convert it to a `{@link}` (compiler- and javadoc-tracked, so it is self-enforcing against future drift) or to a reference to an existing docs page, following the convention already in `ClassAccessorResolver` (quoted section title + `.adoc` path).
3. **Delete outright.** If the sentence carries *nothing but* the pointer, delete the sentence.

**Promotion of genuine design rationale that has no code/test/docs home is out of scope for R482.** Each such case is filed as its own follow-on item so R482 stays a mechanical, compiler-checkable purge. This also protects R483: R482 must never delete a sentence whose only defect was a transient pointer but which carried a real design claim, doing so would leave an authoritative-but-unpinned sentence (the worst state) and destroy R483's raw material. Rule 1 (strip and keep, do not delete load-bearing prose) is the seam that keeps the two passes cooperating.

## The guard

The soft half is a "Javadoc conventions" rule in CLAUDE.md (comments reference the docs site or a live symbol, never a roadmap id/slug; prefer terse over verbose). The hard half is a build-time check that fails on reintroduction. Two design decisions the Spec fixes before Ready:

- **Home (decided): a test-tier meta-test co-located in `graphitron/src/test`.** It matches the project's canonical "documentation names only live symbols" enforcers (`SealedHierarchyDocCoverageTest`, `GeneratedSourcesLintTest`), self-excludes roadmap-tool's own sources for free, and fails at the tier where the sibling doc guards already fail. The alternative considered was a `roadmap-tool` `verify`-phase subcommand mirroring `check-adoc-tables`; rejected because generator-module javadoc is not roadmap-tool's domain, and the meta-test is the more idiomatic host.
- **Lexical scoping (not negotiable).** A bare `\bR\d+\b` regex over `.java` source fails the way `AdocMarkdownTableCheck` would without its block tracker: it fires on register-like tokens and cannot separate the three habitats the corpus actually has, (a) javadoc/`{@code}` citations, (b) `//` implementation comments, (c) string literals. The guard must scan only comment/javadoc lexical regions and strip string literals, the same discipline the table check applies with its structural-block tracker.
- **Allowlist.** Permanent roadmap artifacts (`roadmap/changelog.md`, `roadmap/workflow.adoc`, `roadmap/README.md`) are not transient items; e.g. `QueryNodeFetcherClassGenerator` legitimately cites `changelog.md`. The slug matcher allowlists these, or it trains authors to suppress it.

## Scope

- **In scope:** javadoc and implementation comments in the generator and runtime modules (`graphitron`, and the runtime/support modules); the soft rule; the hard guard.
- **Explicitly separated:** roadmap slugs in *user-facing rejection and deprecation message string literals* (e.g. `GraphitronSchemaValidator`, `FieldBuilder` deprecation text) are a distinct defect in a different habitat (string literals, which the comment-only guard deliberately does not scan) and are tracked as **R484** (`rejection-message-roadmap-slug-purge`), not folded into R482.
- **Out of scope:** authoring new docs pages for promoted rationale (each filed as a follow-on); `roadmap-tool`'s own sources; the drift audit (R483).

## Done

- No `R<n>` or `roadmap/<slug>` citation survives in in-scope comment/javadoc regions (verified by the new guard running green).
- The guard is wired into the build and fails on a planted reintroduction (a guard test asserts this).
- The CLAUDE.md rule is in place.
- Any genuine-promotion cases encountered are filed as follow-on items, not silently dropped or force-fit.
