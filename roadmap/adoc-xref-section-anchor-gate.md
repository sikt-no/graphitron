---
id: R582
title: "Cross-file .adoc section xrefs must use the site idseparator; gate dangling anchors"
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Cross-file .adoc section xrefs must use the site idseparator; gate dangling anchors

The docs module renders with `<idprefix/>` and `<idseparator>-</idseparator>` (`docs/pom.xml:321`), so an
auto-generated section id is kebab-case (`several-node-types-over-one-table`), not AsciiDoc's default
underscore form (`_several_node_types_over_one_table`). A cross-file `xref:<file>.adoc#_underscore_form`
therefore renders a link to an anchor that does not exist in the target page: the reader lands at the top
of the right page instead of the section. Nothing catches it, so the mistake reproduces. Three live
instances at time of filing:

* `docs/manual/reference/directives/nodeId.adoc:21` → `node.adoc#_several_node_types_over_one_table`
  (target renders `id="several-node-types-over-one-table"`).
* `docs/manual/reference/directives/field.adoc:5` and `:109` → `error.adoc#_extra_fields`
  (target renders `id="extra-fields"`).

Two fixes, one mechanical and one structural. Repoint the three xrefs, either to the kebab-case
auto-generated id or (better, matching what the working cross-file xrefs in the manual already do:
`diagnostics-glossary.adoc#kind-author-error`, `reference.adoc#schema-qualified-keys`,
`dev-loop-internals.adoc#dev-loop-detail`) to an explicit `[[anchor]]` on the target section, which
survives a heading rewording. Then add a build gate: a roadmap-tool step in the spirit of
`check-adoc-tables` that resolves every `xref:...adoc#anchor` against the target file's explicit
`[[...]]` ids plus its headings-under-the-site-idseparator, and fails on a dangling one. That is the
`.adoc` counterpart to the javadoc `{@link}` reference gate, and the reason this is worth a gate rather
than a one-off sweep: the underscore form looks right, renders without a warning, and only shows up
when a human clicks the link on the published site.
