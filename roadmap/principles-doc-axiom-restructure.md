---
id: R434
title: "Restructure rewrite design principles around axioms with named enforcement"
status: Backlog
bucket: docs
priority: 4
theme: docs
depends-on: []
created: 2026-07-04
last-updated: 2026-07-04
---

# Restructure rewrite design principles around axioms with named enforcement

`docs/architecture/explanation/rewrite-design-principles.adoc` grew by appending a heading per
lesson learned and now carries 28 flat peer sections: an axiom like "classification belongs at the
parse boundary" has the same weight as "use two-arg `DSL.val`", the type-system family is stated
five times at different zoom levels, and the doc's actual central principle (R222's thesis:
orthogonal facts are slots, never permit cross-products) exists only as a preamble pointer while
five of its weaker cousins have their own sections. Meanwhile two patterns that are now load-bearing
across shipped items are absent entirely: the walker-carrier pattern (R238/R244/R246/R256: typed
call carrier + `AuthorError` sub-seal with stable LSP codes + collect-Err-exclude-field) and the
additive-then-cutover discipline for structural pivots (stated only inside R431/R222 item files).
The restructure: a small axiom set with corollaries, every principle carrying a uniform anatomy
(rule, exemplar, smell, and a named *enforcement*: compiler / meta-test / build tier / review-only,
extending R433's altitude discriminator from inventories to the principles themselves), descriptive
sections demoted to a constraints postscript, and the test-tier trio collapsed onto the
`testing.adoc` pointer the way typed-rejection already collapsed. Review-only enforcement labels
double as a visible gap list for future meta-test items. The spec carries the complete replacement
doc text so review happens on the actual result while the live doc stays untouched until cutover;
cutover includes an xref sweep of citing docs and item files (section anchors change).
