---
id: R582
title: "Gate dangling cross-file .adoc xref anchors, which render silently and rot invisibly"
status: Spec
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-07
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
the same mistake**, and every one has an exact kebab-case sibling that exists. The counts are the whole
staged population with nothing set aside: this item's own quoted examples contribute none of them, because
the renderer emits every markdown code span inert, so they are not references for the gate to collect.

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
sibling checks (`check-adoc-tables`, `check-transient-citations`, `check-module-enumeration`). Part of that
shape is how failure is signalled: throw `BuildFailure` rather than return non-zero, because `Main` turns a
non-zero return into `System.exit` and the binding below is `exec:java` in the Maven JVM, where that kills
Maven before `BUILD FAILURE` prints. `AdocMarkdownTableCheck.run` carries the same note.

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

**Quoted xref syntax counts as a reference in hand-authored `.adoc`, and that is correct.** Staging includes
`staging/roadmap/`, so roadmap prose is in the scan, and prose about xrefs quotes xref syntax. In a page an
author wrote as AsciiDoc, a single-backtick span applies the normal substitution group with macros included,
so wrapping an xref macro in backticks renders a live link, not literal text; only a passthrough inside the
span suppresses it. The gate should therefore treat it as a reference and the *prose* is what gets fixed,
rather than teaching the check to skip inline monospace, which would blind it to the same mistake in
ordinary prose.

**On md-sourced pages that premise no longer holds, and the collector must read the emitter's definition
rather than keep its own.** The roadmap renderer now emits every markdown code span in an inert form, so a
backticked reference in a roadmap item body or in the changelog is literal by construction; the live-span
class survives only where an author wrote AsciiDoc by hand, under `docs/`. The collector classifies span
forms on exactly the pages that renderer generates, so it must ask `InertSpans` whether a form is inert
instead of carrying a second list that drifts the next time the emitter gains a form. Concretely: a bare
backtick span still counts as a reference (the hand-authored habitat), and both inert forms do not.

**The paragraph, not the code span, is the unit of containment, and that is why "no attrlist, therefore no
macro" is not a safe reading.** Settled by rendering the site and reading this page's own HTML, not by
probing the forms in isolation, and the isolated probes are what got it wrong: a bare
`xref:node.adoc#anchor` forms no macro on its own, but the paragraph that quoted it also quoted a bracketed
`xref:<file>.adoc#anchor[label]` two lines further down, and Asciidoctor's inline-xref match spanned the gap
between the two code spans. It took the target from the first quote and the attrlist from the second,
emitted a malformed anchor tag where the first placeholder stood, and left the closing tag's residue as
literal text inside the second: one placeholder disappeared from the published prose, another gained
garbage, and the render logged nothing. Neither backtick quoting nor the absence of an attrlist contains a
macro; only a passthrough does. The anchor-declaration syntax the rule above names had the same problem for
the same reason: backtick-quoted, the double-bracket form was substituted into a real empty anchor element,
so the sentence defining the rule showed the reader nothing where the syntax should be and stamped the same
`id` on the page twice.

This body used to hand-write a plus-delimited passthrough at each of those sites. It no longer does: the
renderer emits one per span, so the quotes above are plain markdown backticks and nothing on this page
reaches the gate. That also retires the one-per-paragraph hazard the hand-written form carried, where a
second identical anchor-declaration quote in the same paragraph made Asciidoctor warn that the id was
already in use and, under `failIf severity=WARN`, failed the build. The per-span passthrough is emitted for
every span rather than for the ones an author remembered, so identical quotes no longer collide.

That has one consequence for the gate itself: a check keying on `xref:<target>[attrlist]` within a single
line is *not* Asciidoctor-faithful in this corner. On the paragraph that produced the observation above it
would have flagged the bracketed placeholder, whose `<file>.adoc` target does not exist, and missed the
reference Asciidoctor actually built a link from. Under-report there is the right trade (the same-line rule
catches every reference an author writes on purpose, and modelling Asciidoctor's cross-span matching is the
id-algorithm mistake in another costume),
but the item should not claim a faithfulness it does not have. The dangling-anchor class in quoted prose is
real all the same: a quoted example naming a page that does exist beside the item (another plan's
`plans/<slug>.adoc`, say) resolves and would fail the gate. Not every authored `.adoc` under `roadmap/`
reaches staging, so a target being authored does not make it resolvable: `roadmap/workflow.adoc` is never
staged, while `roadmap/inference-axis-coverage.adoc` is.

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

The reported population is the *anchored* references the gate already collects, not every `xref:` in the
tree, and on today's tree that count is zero, because the renderer emits this item's own quoted examples
inert. The wider population is worth knowing about because it is not empty: staging carries 9 unanchored
cross-file references whose target page does not exist, 7 of them from `roadmap/changelog.adoc` naming plan
pages that were deleted when their items shipped and 2 from
`roadmap/plans/service-short-classname-resolution.adoc:21,66` naming `computed-field-with-reference.adoc`.
Those are live 404s on the published site, but they are a different failure (wrong path, not wrong anchor)
with a different fix, so they stay out of this item and land in R596 rather than widening this one.

Two things to keep straight about scanning roadmap prose. First, the alternative of scoping
`staging/roadmap/` out of the scan is worse than it looks: roadmap items are published pages on the site,
so their links rot like any other, and this item would have exempted the one page it was written about.
Second, the general fix lived one level down, was filed separately as R587, and has shipped: markdown code
spans are literal by definition while AsciiDoc code spans are substituted, and the md-to-adoc render used
to pass backticks straight through, so every roadmap author had to know AsciiDoc passthrough syntax to
quote a macro safely. The renderer now emits an inert form per span, which retires the whole class for
md-sourced pages. This item no longer carries a hand-written workaround for it, and this body quotes with
plain markdown backticks throughout.

## Acceptance

* The four dangling references above repointed to explicit `[#anchor]` block ids, with the anchors added
  to the three target sections (`node.adoc`, `error.adoc`, `05-mutations.adoc`). Each added anchor repeats
  the kebab id that section already renders, so no inbound deep link anywhere changes target.
* The five auto-generated targets gain explicit anchors repeating the ids already in use, so the six
  references leaning on them satisfy the rule without being edited: `[#schema-qualified-keys]` in
  `reference.adoc`, and `[#sealed-hierarchies-over-enums-for-typed-information]`,
  `[#acceptances-classifier-guarantees-shape-emitter-assumptions]`,
  `[#rejections-validator-mirrors-classifier-invariants]`,
  `[#builder-step-results-are-sealed-not-strings-or-out-params]` in `development-principles.adoc`.
  `reference.adoc:38`'s same-file `<<Schema-qualified keys>>` natural-language reference keeps working and
  needs no change.
* No `xref:`, `<<...>>` or `[[id]]` quoted in this item's body survives as markup on the published page. The
  renderer emits an inert form per span, so this is the regression check, not new work: render the site and
  confirm `docs/target/generated-docs/roadmap/plans/adoc-xref-section-anchor-gate.html` contains no `<a>`
  element derived from an `xref:` in the body and no stray tag residue as literal text. On this page the
  invariant is now the emitter's to hold, and its own corpus gate holds it; what still needs the
  rendered-HTML look is any hand-authored `.adoc` this item edits, where the paragraph, not the code span,
  remains the unit of containment. The site render currently emits no
  `possible invalid reference` INFO lines at all, so that is the baseline to preserve rather than a count
  to reduce. The same check applies to every *other* page this item edits, not only to this one: any page
  that gains quoted reference or anchor syntax gets the same rendered-HTML inspection, because the trap is
  the markup, not the page.
* `check-adoc-xrefs` fails the build on a dangling cross-file anchor, verified by planting one and
  observing the failure, not only by unit test.
* The rule is written down where an author reads before the gate teaches it by failing:
  `docs/README.adoc`'s "Authoring conventions" list gains the strict rule, and its "Errors-vs-warnings"
  paragraph is corrected. That paragraph currently claims `failIf severity=WARN` (live at
  `docs/pom.xml:362`) means "missing xrefs ... fail the build", which the probes above disprove for both
  anchored cases under exactly that setting: the cross-file one renders silent, and the same-file one logs at
  INFO and still reaches `BUILD SUCCESS`. No missing xref fails the build today.
  `docs/README.adoc` is itself a staged, rendered, scanned page (`stage-adoc` copies root-level `docs/*.adoc`,
  so `staging/README.adoc` is in the gate's own population), so the syntax that bullet has to quote goes in as
  plus-delimited passthroughs under the paragraph-scope rule in the Design note. Backtick-only quoting there is
  not a cosmetic slip: a quoted reference whose target page exists resolves and publishes a live link, and if
  its anchor happens to exist the gate passes it, which is the exact silent-publication class this item exists
  to close; a backtick-only double-bracket declaration publishes a real anchor and can duplicate an id.
* The check reports a count of the anchored references it could not resolve to a staged `.adoc`, rather than
  passing over them silently, so under-coverage is visible without the count itself failing the build. That
  count is zero on today's tree, because the renderer emits this item's own quoted examples inert, so a
  non-zero count after the change is a finding, not noise.
* Every finding names its authored source alongside the staged path, since all of `staging/` is build output
  an author cannot edit: `staging/manual/...` and `staging/architecture/...` are `stage-adoc` copies and map
  back by stripping the staging root onto `docs/` (root-level `staging/*.adoc` included), while
  `staging/roadmap/plans/<slug>.adoc` is generated by `render-roadmap-adoc` and maps back to
  `roadmap/<slug>.md`, a different file in a different markup. The `<slug>.md` rule is the *plans* rule and
  must not be applied to the rest of `staging/roadmap/`, which `render-roadmap-adoc` populates two other ways:
  `inference-axis-coverage.adoc` and `source-coverage.adoc` are verbatim copies of authored `roadmap/*.adoc`
  and map back to those, while `index.adoc` and `by-theme.adoc` are written from item front-matter with no
  single authored source and `changelog.adoc` comes from `roadmap/changelog.md`. No anchored reference lands
  in any of those five today, so this is a mapping to state, not a bug to chase; getting it wrong reports a
  path that does not exist, in the one criterion whose whole point is correct provenance.
* `AdocXrefAnchorCheckTest` covers: a resolving explicit anchor in each of the two declaration forms
  (`[#id]` and `[[id]]`, both live in the tree, the latter at `table.adoc:39` and `routine.adoc:75,110`, so
  a collector that reads only `[#id]` would false-fail rather than under-report), a dangling anchor, the
  underscore-vs-kebab case specifically, a `../`-relative target (15 of the 27 references, per the Design
  note), a reference inside a `|===` table cell (live at `mojo-configuration.adoc:117`), an anchor inside a
  `----` listing or `////` comment block (must not count as a reference), a reference in a single-backtick
  span (*does* count, per the Design note above) versus one in either inert form (does not, and the
  collector asks `InertSpans` rather than matching the forms itself), and a target page outside the tree
  (counted as unresolvable, neither silently passed nor failed). Pin the
  known under-report too: a bare `xref:` target on one line whose activating attrlist sits on a later line
  is *not* collected, which Asciidoctor would nonetheless link, so the limit is asserted rather than
  discovered later.

## Not in scope

* Same-file `<<...>>` and `xref:#...` references. Asciidoctor already reports these at INFO and none are
  currently broken; promoting that INFO to a build failure is a separate argument about asciidoctor's log
  level, not about the unreported class this item exists for.
* Following `include::` when collecting a page's anchors. A per-file scan is correct on today's tree: the
  only real includes are `migrating-from-legacy.adoc:16,18`, the two `manual/_generated/` fragments they
  pull carry sections but no *explicit* anchors (so nothing the gate resolves against), and nothing xrefs
  into that page. Keep the failure mode straight if that ever
  changes, because it is a *false failure* rather than an under-report, the direction this item elsewhere
  treats as the worse one. The fragments are staged in their own right, so a reference straight at
  `_generated/<fragment>.adoc#anchor` resolves and passes; a reference at
  `migrating-from-legacy.adoc#anchor` finds the host page and not the anchor, and fails a link that
  actually works. The unresolvable count does not surface that case, since it counts only references whose
  target `.adoc` is absent from staging. The cheap fix when it fires is to fold the anchors of each
  `include::`-ed file into the including page's set; today there is nothing to fold.
* Unanchored `xref:<file>.adoc[...]` path validity. A wrong *path* is silent at build time too, so the
  distinction is not silence: Asciidoctor never resolves a cross-document target at all, it only rewrites the
  extension, and a full site render with two references pointing at files that do not exist logged nothing.
  What separates the two classes is the reader's experience and the fix. A wrong path 404s on the first click,
  so it is self-reporting once anyone follows it and gets fixed by correcting a path; a wrong anchor lands the
  reader on a plausible page and is never noticed. Failing the build on a wrong path also breaks every quoted
  example in roadmap prose, which is why this item counts those references instead. The 9 live cases in
  staging today are R596.
* Adopting Antora, which resolves cross-document xrefs natively as a side effect of its component model.
  That is a site-toolchain migration and would be its own item.
