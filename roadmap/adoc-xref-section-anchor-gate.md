---
id: R582
title: "Gate dangling cross-file .adoc xref anchors, which render silently and rot invisibly"
status: Spec
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Gate dangling cross-file .adoc xref anchors, which render silently and rot invisibly

A cross-file `xref:<file>.adoc#anchor` naming an anchor the target page does not publish renders as a
working link that goes nowhere useful: the reader lands at the top of the right page instead of the
section. Asciidoctor does not and cannot catch it, because the target is a separate document, so the
mistake ships and reproduces.

The recurring instance of it is an id-format mismatch. The site renders with `<idprefix/>` and
`<idseparator>-</idseparator>` (`docs/pom.xml:321`), so an auto-generated section id is kebab-case
(`several-node-types-over-one-table`), not AsciiDoc's default underscore form
(`_several_node_types_over_one_table`). The underscore form is what an author gets from reading
Asciidoctor's own documentation or from a habit formed outside this repo, and it looks right.

## What is actually broken

Census over `docs/target/staging` (the tree the renderer is pointed at), resolving each anchored
reference against the `id="..."` attributes in the page the build actually produced. 31 anchored
references, **4 dangling, all four the same mistake**, and every one has an exact kebab-case sibling that
exists:

[cols="3,2"]
|===
| Reference | Anchor that exists

| `manual/reference/directives/nodeId.adoc:21` → `node.adoc#_several_node_types_over_one_table`
| `several-node-types-over-one-table`

| `manual/reference/directives/field.adoc:5` → `error.adoc#_extra_fields`
| `extra-fields`

| `manual/reference/directives/field.adoc:109` → `error.adoc#_extra_fields`
| `extra-fields`

| `manual/reference/directives/mutation.adoc:30` → `../../tutorial/05-mutations.adoc#_grouping_fields_with_nested_input_types`
| `grouping-fields-with-nested-input-types`
|===

No dangling reference in the tree has any other cause, which is what makes both halves of this item
small: the sweep is four one-token substitutions and the gate has a single failure mode to describe.

## Why a gate and not just the sweep

Established empirically rather than assumed, by planting both forms in `nodeId.adoc` and rendering:

* Same-file `<<zzz-no-such-anchor>>` → `asciidoctor: INFO: possible invalid reference:
  zzz-no-such-anchor`. Reported, but at INFO, and the build still succeeds.
* Cross-file `xref:node.adoc#zzz-no-such-cross-anchor[label]` → **completely silent**. No INFO, no
  warning, `BUILD SUCCESS`.

So the form that is impossible for the author to self-check is also the form nothing reports. This is the
`.adoc` counterpart to the javadoc `{@link}` reference gate, and the argument for it is the same one that
gate already won: a reference that names a live target should be build-enforced so it cannot silently rot.

## Design

**The rule: a cross-file anchored xref must target an explicit block anchor** (`[#id]` or `[[id]]`) on the
target section, not an auto-generated heading id. The gate resolves against explicit anchors only.

This is already the house convention, which is the main argument for it. Eight of the nine currently-working
cross-file anchored references target an explicit `[#id]`: `diagnostics-glossary.adoc` carries
`[#kind-author-error]`, `[#kind-invalid-schema]`, `[#kind-deferred]`; `dev-loop-internals.adoc` carries
`[#dev-loop-detail]` and `[#native-runtime-dependency]`; plus `[#execute-tool]`, `[#compiled-classes]`,
`[#hook-state-contract]`. Only `referenceFor.adoc:34` → `reference.adoc#schema-qualified-keys` leans on the
auto-generated form, so adopting the rule costs exactly one added anchor and makes that target rename-safe
too.

Checking explicit anchors only also means the gate does not reimplement Asciidoctor's id-generation
algorithm, which would be a second source of truth free to drift from the renderer at every AsciidoctorJ
upgrade. The permissive alternative (accept explicit anchors *or* headings slugged under the site's
`idseparator`) needs that algorithm, buys no migration savings worth having at a one-file cost, and leaves
authors writing references that work today and break on a heading reword. Recommend the strict rule; the
alternative is the reviewer's to reopen.

**Where it runs.** Implement as `AdocXrefAnchorCheck` in `roadmap-tool` beside `AdocMarkdownTableCheck`,
with a `check-adoc-xrefs` mode in `Main` and a `AdocXrefAnchorCheckTest`, matching the shape of the three
sibling checks (`check-adoc-tables`, `check-transient-citations`, `check-module-enumeration`).

Invoke it, however, from the **docs** module against `${project.build.directory}/staging` after the
`stage-adoc` execution, not from `roadmap-tool` against the source tree. This is the one real fork in the
item and the reason is path resolution: `stage-adoc` flattens two source roots into one tree (`docs/<rest>`
→ `staging/<rest>`, but repo-root `roadmap/` → `staging/roadmap/`, with roadmap `.md` rendered to `.adoc`),
and `docs/` genuinely references across that seam (`docs/index.adoc:89` and `docs/architecture/index.adoc:37`
both xref `roadmap/index.adoc`). A source-tree checker would need a mount table mirroring `stage-adoc` and
still could not resolve a target that only exists as `.adoc` post-render. Running on staging makes path
resolution free and exact, because it is the same tree asciidoctor resolves against. Note this inverts
`AdocMarkdownTableCheck`'s deliberate `target/` skip, so say why in the javadoc.

Cost: the gate does not run under `-P!docs`. Acceptable and consistent, on the same footing as `-Pquick`
skipping the javadoc reference gate.

## Acceptance

* The four dangling references above repointed to explicit `[#anchor]` block ids, with the anchors added
  to the three target sections (`node.adoc`, `error.adoc`, `05-mutations.adoc`).
* `reference.adoc` gains `[#schema-qualified-keys]` so the ninth reference satisfies the rule too. Its
  same-file `<<Schema-qualified keys>>` natural-language reference at `reference.adoc:38` keeps working
  and needs no change.
* `check-adoc-xrefs` fails the build on a dangling cross-file anchor, verified by planting one and
  observing the failure, not only by unit test.
* The check reports a count of references it could not resolve to an authored `.adoc` rather than passing
  over them silently, so under-coverage is visible.
* `AdocXrefAnchorCheckTest` covers: a resolving explicit anchor, a dangling anchor, the underscore-vs-kebab
  case specifically, an anchor inside a `----` listing or `////` comment block (must not count as a
  reference), and a target page outside the tree (counted as unresolvable, not as a pass).

## Not in scope

* Same-file `<<...>>` and `xref:#...` references. Asciidoctor already reports these at INFO and none are
  currently broken; promoting that INFO to a build failure is a separate argument about asciidoctor's log
  level, not about the unreported class this item exists for.
* Unanchored `xref:<file>.adoc[...]` path validity. A wrong *path* produces a visibly broken link and an
  asciidoctor complaint, so it is not the silent class.
* Adopting Antora, which resolves cross-document xrefs natively as a side effect of its component model.
  That is a site-toolchain migration and would be its own item.
