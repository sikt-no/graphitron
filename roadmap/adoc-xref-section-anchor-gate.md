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
references (27 cross-file `xref:<file>.adoc#anchor`, 4 same-file `xref:#anchor`), **4 dangling, all four
the same mistake**, and every one has an exact kebab-case sibling that exists. The counts cover the
authored docs; this item's own two illustration macros are staged and scanned too, and are accounted for
separately below:

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

* Same-file `+<<zzz-no-such-anchor>>+` → `asciidoctor: INFO: possible invalid reference:
  zzz-no-such-anchor`. Reported, but at INFO, and the build still succeeds.
* Cross-file `xref:node.adoc#zzz-no-such-cross-anchor[label]` → **completely silent**. No INFO, no
  warning, `BUILD SUCCESS`.

So the form that is impossible for the author to self-check is also the form nothing reports. This is the
`.adoc` counterpart to the javadoc `{@link}` reference gate, and the argument for it is the same one that
gate already won: a reference that names a live target should be build-enforced so it cannot silently rot.

## Design

**The rule: a cross-file anchored xref must target an explicit block anchor** (`[#id]` or `[[id]]`) on the
target section, not an auto-generated heading id. The gate resolves against explicit anchors only.

This is already the house convention, which is the main argument for it. Of the 23 currently-working
cross-file anchored references, 17 target an explicit `[#id]`, across 11 distinct anchors:
`diagnostics-glossary.adoc` carries `[#kind-author-error]`, `[#kind-invalid-schema]`, `[#kind-deferred]`;
`dev-loop-internals.adoc` carries `[#dev-loop-detail]`, `[#native-runtime-dependency]`,
`[#federation-internals]`; plus `[#execute-tool]`, `[#compiled-classes]`, `[#hook-state-contract]`,
`[#session-identity]`, `[#helper-locality]`.

The remaining 6 lean on the auto-generated form, over 5 distinct anchors, and they are what the rule costs:

* `referenceFor.adoc:34` → `reference.adoc#schema-qualified-keys`
* `dispatch-axes.adoc:117` → `development-principles.adoc#sealed-hierarchies-over-enums-for-typed-information`
* `typed-rejection.adoc:91` → `development-principles.adoc#acceptances-classifier-guarantees-shape-emitter-assumptions`
* `typed-rejection.adoc:91` and `:196` → `development-principles.adoc#rejections-validator-mirrors-classifier-invariants`
* `typed-rejection.adoc:195` → `development-principles.adoc#builder-step-results-are-sealed-not-strings-or-out-params`

So adopting the rule costs **five added anchors across two files**, not one: one in `reference.adoc` and
four in `development-principles.adoc`. No referencing site needs to change, because each of those five
headings already slugs to exactly the id the references name, so an explicit `[#id]` repeating that id
is a pure addition. Four of the five land on principle headings that are cited from other pages, which is
precisely the population that should be rename-safe, so the cost buys the thing the rule is for.

Checking explicit anchors only also means the gate does not reimplement Asciidoctor's id-generation
algorithm, which would be a second source of truth free to drift from the renderer at every AsciidoctorJ
upgrade. The permissive alternative (accept explicit anchors *or* headings slugged under the site's
`idseparator`) needs that algorithm, buys no migration savings worth having against five one-line
additions, and leaves authors writing references that work today and break on a heading reword. Recommend
the strict rule; the alternative is the reviewer's to reopen.

**Where it runs.** Implement as `AdocXrefAnchorCheck` in `roadmap-tool` beside `AdocMarkdownTableCheck`,
with a `check-adoc-xrefs` mode in `Main` and a `AdocXrefAnchorCheckTest`, matching the shape of the three
sibling checks (`check-adoc-tables`, `check-transient-citations`, `check-module-enumeration`).

Invoke it, however, from the **docs** module against `${project.build.directory}/staging` once staging is
fully populated (after `stage-adoc` and after `render-roadmap-adoc`), not from `roadmap-tool` against the
source tree. This is the one real fork in the
item and the reason is path resolution: `stage-adoc` flattens two source roots into one tree (`docs/<rest>`
→ `staging/<rest>`, but repo-root `roadmap/` → `staging/roadmap/`, with roadmap `.md` rendered to `.adoc`),
and `docs/` genuinely references across that seam (`docs/index.adoc:89` and `docs/architecture/index.adoc:37`
both xref `roadmap/index.adoc`). A source-tree checker would need a mount table mirroring `stage-adoc` and
still could not resolve a target that only exists as `.adoc` post-render. Running on staging makes path
resolution free and exact, because it is the same tree asciidoctor resolves against. Note this inverts
`AdocMarkdownTableCheck`'s deliberate `target/` skip, so say why in the javadoc.

**Bind it in the docs module's base build, not inside the `docs` profile,** and there is no `-P!docs` hole
to accept. Only the `render-site` execution lives in that profile (`docs/pom.xml:281-373`); `stage-adoc`,
`stage-theme-css` and `render-roadmap-adoc` are all base-build steps that populate staging under `-P!docs`
too. A second `exec-maven-plugin` execution declared after `render-roadmap-adoc` at `process-resources`
therefore gates every build. The check reads staged `.adoc` sources rather than rendered HTML, so skipping
the AsciiDoctor render costs it nothing.

**Quoted xref syntax counts as a reference, and that is correct.** Staging includes `staging/roadmap/`, so
roadmap prose is in the scan, and prose about xrefs quotes xref syntax. A single-backtick span applies
AsciiDoc's normal substitution group, macros included, so wrapping an xref macro in backticks renders a
live link, not literal text; only a plus-delimited passthrough inside the span suppresses it. The gate
should therefore treat it as a reference and the *prose* is what gets fixed, rather than teaching the check
to skip inline monospace, which would blind it to the same mistake in ordinary prose.

Exactly two lines of this item's own body are such a macro, not three. Established by rendering probes
against the site's own configuration rather than read off the macro grammar: `xref:node.adoc#anchor` and
`xref:#anchor` render literally (no attrlist), and `xref:<file>.adoc#anchor[label]` renders literally even
*with* an attrlist, so the two `<file>.adoc#anchor` placeholders in this body are safe twice over and are
invisible to Asciidoctor and to any Asciidoctor-faithful gate. Scanning `docs/target/staging` picks up two
macros from this page: the `zzz-no-such-cross-anchor` illustration in "Why a gate and not just the sweep",
and the `zzz-nope` probe in the paragraph below. Both are live links on a published page (confirmed in the
generated HTML, which carries an `href` for each) and both should be rewritten as passthroughs on rendering
grounds, but neither is what fails the gate: staged at `staging/roadmap/plans/`, their targets resolve to no
file, so they land in the unresolvable count rather than the dangling-anchor failure. The class is real all
the same, since a quoted example naming a page that does exist beside the item (`workflow.adoc`, say) would
fail; this body simply does not instantiate it.

**What counts as a reference is wider than the target's first character suggests.** The same probes settle
it: `xref:../architecture/index.adoc#zzz-nope[label]` renders as a live link, silently, so a target opening
on `.` is a macro like any other. 15 of the 27 cross-file references in the census above are `../`-relative,
including the fourth dangling one (`mutation.adoc:30` → `../../tutorial/05-mutations.adoc`). Detection
therefore keys on the bracketed attrlist and accepts any relative path; narrowing it by the target's opening
character would skip the majority of the population and one of the four bugs the gate exists to catch.

**Unresolvable targets are reported, never failed.** A cross-file reference whose target `.adoc` is absent
from staging is counted and printed, not a build failure: a wrong path is out of scope for the reasons
below, and failing on one would turn every quoted example into a build break. Only a reference that
resolves to a real staged page and names an anchor that page does not publish fails the build.

Two things to keep straight about scanning roadmap prose. First, the alternative of scoping `staging/roadmap/` out of the
scan is worse than it looks: roadmap items are published pages on the site, so their links rot like any
other, and this item would have exempted the one page it was written about. Second, the general fix lives
one level down and is filed separately: markdown code spans are literal by definition while AsciiDoc code
spans are substituted, and the md-to-adoc render currently passes backticks straight through, so every
roadmap author has to know AsciiDoc passthrough syntax to quote a macro safely. Emitting a passthrough
span from the renderer would retire the whole class. This item does not wait on it; the one line here is
rewritten by hand, at the cost of literal plus signs in GitHub's markdown view of the item.

## Acceptance

* The four dangling references above repointed to explicit `[#anchor]` block ids, with the anchors added
  to the three target sections (`node.adoc`, `error.adoc`, `05-mutations.adoc`).
* The five auto-generated targets gain explicit anchors repeating the ids already in use, so the six
  references leaning on them satisfy the rule without being edited: `[#schema-qualified-keys]` in
  `reference.adoc`, and `[#sealed-hierarchies-over-enums-for-typed-information]`,
  `[#acceptances-classifier-guarantees-shape-emitter-assumptions]`,
  `[#rejections-validator-mirrors-classifier-invariants]`,
  `[#builder-step-results-are-sealed-not-strings-or-out-params]` in `development-principles.adoc`.
  `reference.adoc:38`'s same-file `+<<Schema-qualified keys>>+` natural-language reference keeps working and
  needs no change.
* Both live xref macros in this item's body rewritten as plus-delimited passthroughs, so the published page
  carries no link to nowhere: the `zzz-no-such-cross-anchor` illustration in "Why a gate and not just the
  sweep", and the `zzz-nope` probe in the "What counts as a reference" paragraph. Verify against the
  generated HTML, which today carries an `href` for each; fixing only the first leaves one link to nowhere.
  This is a rendering fix, not a gate fix: both targets resolve to no file as staged, so the gate counts
  them rather than failing them. The two backtick-quoted `<<...>>` forms elsewhere in the body (in the same
  "Why a gate" bullet, and in the `reference.adoc:38` bullet below) are *already* plus-delimited and render
  literally; leave them that way, and do not let a rewrite drop the plus signs. The site render currently
  emits no `possible invalid reference` INFO lines at all, so that is the baseline to preserve rather than a
  count to reduce.
* `check-adoc-xrefs` fails the build on a dangling cross-file anchor, verified by planting one and
  observing the failure, not only by unit test.
* The rule is written down where an author reads before the gate teaches it by failing:
  `docs/README.adoc`'s "Authoring conventions" list gains the strict rule, and its "Errors-vs-warnings"
  paragraph is corrected. That paragraph currently claims `failIf severity=WARN` (live at
  `docs/pom.xml:362`) means "missing xrefs ... fail the build", which the probes above disprove for both
  anchored cases under exactly that setting: the cross-file one renders silent, and the same-file one logs at
  INFO and still reaches `BUILD SUCCESS`. No missing xref fails the build today.
* The check reports a count of references it could not resolve to an authored `.adoc`, rather than passing
  over them silently, so under-coverage is visible without the count itself failing the build.
* Every finding names its authored source alongside the staged path, since all of `staging/` is build output
  an author cannot edit: `staging/manual/...` and `staging/architecture/...` are `stage-adoc` copies and map
  back by stripping the staging root onto `docs/`, while `staging/roadmap/plans/<slug>.adoc` is generated by
  `render-roadmap-adoc` and maps back to `roadmap/<slug>.md`, a different file in a different markup.
* `AdocXrefAnchorCheckTest` covers: a resolving explicit anchor in each of the two declaration forms
  (`[#id]` and `[[id]]`, both live in the tree, the latter at `table.adoc:39` and `routine.adoc:75,110`, so
  a collector that reads only `[#id]` would false-fail rather than under-report), a dangling anchor, the
  underscore-vs-kebab case specifically, a `../`-relative target (15 of the 27 references, per the Design
  note), a reference inside a `|===` table cell (live at `mojo-configuration.adoc:117`), an anchor inside a
  `----` listing or `////` comment block (must not count as a reference), a reference in a single-backtick
  span (*does* count, per the Design note above) versus one in a plus-delimited passthrough (does not), and
  a target page outside the tree (counted as unresolvable, neither silently passed nor failed).

## Not in scope

* Same-file `<<...>>` and `xref:#...` references. Asciidoctor already reports these at INFO and none are
  currently broken; promoting that INFO to a build failure is a separate argument about asciidoctor's log
  level, not about the unreported class this item exists for.
* Following `include::` when collecting a page's anchors. A per-file scan is correct on today's tree: the
  only real includes are `migrating-from-legacy.adoc:16,18` and nothing anchors into that page. If an
  included fragment ever carries an xref target, the unresolvable-count criterion above is what surfaces
  it.
* Unanchored `xref:<file>.adoc[...]` path validity. A wrong *path* produces a visibly broken link and an
  asciidoctor complaint, so it is not the silent class.
* Adopting Antora, which resolves cross-document xrefs natively as a side effect of its component model.
  That is a site-toolchain migration and would be its own item.
