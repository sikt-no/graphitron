---
id: R596
title: "Cross-file xref paths naming deleted plan pages publish as 404 links"
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-05
last-updated: 2026-08-05
---

# Cross-file xref paths naming deleted plan pages publish as 404 links

The published site carries 9 cross-file `xref:` links whose target page does not exist, so they render as
ordinary links and 404 on click. Seven come from `roadmap/changelog.adoc` (lines 653, 657, 661 twice, 739,
787, 789), which names plan pages that were deleted when their items shipped
(`load-bearing-annotations-r78-classifier-checks`, `method-name-binding-enclosing-directive-context`,
`fkjoin-alias-dead-storage`, `lsp-diagnostic-redundant-splitquery-on-record`, `id-reference-input-field`,
`plan-generated-fetcher-quality`, `plan-single-cardinality-split-query`); two come from
`roadmap/plans/service-short-classname-resolution.adoc:21,66` naming `computed-field-with-reference.adoc`.
Nothing reports them: Asciidoctor never resolves a cross-document target, it only rewrites the extension, so
a missing page is as silent at build time as a missing anchor. Census taken over `docs/target/staging` by
resolving each `xref:` target path against the staged tree.

Two halves to decide between (or combine) at Spec time: repointing or delinking the dead references, and
whether an unresolvable target should fail the build the way a dangling *anchor* does. The anchor gate
deliberately reports rather than fails on unresolvable targets, because roadmap prose quotes xref syntax as
examples and failing on those would break the build; a path gate needs an answer to that same problem, most
likely by resolving only references the md-to-adoc renderer emitted rather than ones an author quoted. The
changelog case also raises whether a shipped item's plan page should keep a stable published URL at all,
which may be the cheaper fix.
