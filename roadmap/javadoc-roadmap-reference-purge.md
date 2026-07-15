---
id: R482
title: "Purge transient roadmap references from javadoc; guard against reintroduction"
status: Backlog
bucket: cleanup
priority: 12
theme: docs
depends-on: []
created: 2026-07-15
last-updated: 2026-07-15
---

# Purge transient roadmap references from javadoc; guard against reintroduction

Generator-module javadoc is riddled with citations to roadmap items (`R398`, `R408`, `R279 slice 5`, `R246 / R258 / R266`), prose pointers ("tracked on the rewrite roadmap", "would warrant its own roadmap item"), and hard links to slug files (`roadmap/nodeidreferencefield-join-projection-form.md`). Roadmap items are transient: they get renumbered, ship and leave a gap, or get discarded, so a doc comment that leans on one is stale the moment that item moves. Javadoc should reference the manual under `/docs/`, nothing else. Where a citation is standing in for real design content that only exists in a roadmap item, that content should be promoted to the manual and the doc comment should link the promoted page, per the project rule that anything worth referencing is worth documenting. This item purges the references (delete where they carry no information; promote-then-link where they do) and, crucially, installs a guard so agents stop reintroducing them: a soft rule in CLAUDE.md plus a hard build-side check modeled on the existing `AdocMarkdownTableCheck` / `check-adoc-tables` pattern in `roadmap-tool`, failing the build when generator-module javadoc cites an `R<n>` id or a `roadmap/…` slug. Scoped to javadoc/comments in the generator and runtime modules; `roadmap-tool`'s own javadoc legitimately discusses "roadmap" as its domain and is out of scope.
