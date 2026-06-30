---
id: R400
title: "Withhold not-in-use directives from the v1 advertised directive surface"
status: Spec
bucket: feature
theme: docs
depends-on: []
created: 2026-06-30
last-updated: 2026-06-30
---

# Withhold not-in-use directives from the v1 advertised directive surface

For the first release the advertised directive surface should be as small and clean as
what consumers actually use. Several declared directives should not be presented as *available*
in v1, for two distinct reasons:

* **Not in use, withheld from the v1 surface.** `@tableMethod` and `@sourceRow` are fully
  implemented and test-covered but used by no consumer schema; `@experimental_constructType` is
  declared and parsed but has no emitter (R69). These are withheld from the advertised surface
  with no migration note: using them is neither an error nor something to remove, they are simply
  outside the v1 surface.
* **Wrongly listed as supported.** `@notGenerated` and `@multitableReference` are *rejected on
  use* (hard build error, declared only so the parser can emit a friendly migration message), yet
  the generated "supported directives" list called them supported. They move to a "Removed /
  rejected" section that tells migrating consumers to delete them.

`@record` is deliberately left **as-is**: deprecated and silently ignored (warn, not rejected),
kept for v1 per the user decision (2026-06-30). It is neither withheld nor moved to the rejected
list.

This item **absorbs and supersedes the parallel "Remove the @tableMethod directive" proposal** (an
upstream R400 this branch's R400 took over during reconciliation). That proposal would have retired
`@tableMethod` outright; the decision instead is "the idea is good, it is just not in use, so
withhold it and take the opportunity to rethink it" rather than delete a real capability under
release pressure. The rethink agenda (and the absorbed capability/migration analysis) lives on
**R403**; this item only withholds `@tableMethod` from the v1 surface.

The decision is surface-only: **no behaviour change, no classify-time rejection added, no test
churn on the generator.** Every directive stays declared in `directives.graphqls` and keeps its
current behaviour; this only governs what the documentation advertises, so re-advertising one
later is a doc-only edit.

## Plan

### Stage 1 — Generated migration fragment (landed)

`docs/manual/_generated/supported-directives.adoc` is produced by
`DirectiveSupportReport.renderMigration`, which listed *every* rewrite-declared directive as
"supported" with no exclusion. Two curated sets now gate it:

* `REJECTED_ON_USE` = {`notGenerated`, `multitableReference`} — rejected at classify time. Moved out
  of "Supported" into a new "Removed / rejected directives" section telling consumers to delete them.
* `WITHHELD_FROM_V1` = {`tableMethod`, `sourceRow`, `experimental_constructType`} — excluded from
  "Supported" silently (not errors, just outside the v1 surface). Arg-shape changes skip both sets.

Covered by a `DirectiveSupportReportTest` case that asserts the *exclusion took effect* (the
withheld trio and rejected pair are absent from "Supported", the rejected pair present under
"Removed / rejected"). The regen also corrected pre-existing drift (`@routine`/`@scalarType` were
missing; `@mutation`'s `multiRow` arg-shape change was absent). Dovetails with R346 (the
regen-guard, which should guard the post-exclusion content).

Two hardening notes for whoever next touches this (both surfaced in the pre-srp self-review):

* `exec:java` runs the *compiled* class, so the fragment must be regenerated **after** a `compile`
  of `roadmap-tool`, never straight after a source edit — otherwise stale bytecode rewrites the
  old wording. (This bit once: the "Removed / rejected" wording landed stale and was corrected.)
* Nothing yet pins that a name in `WITHHELD_FROM_V1` / `REJECTED_ON_USE` actually exists in
  `directives.graphqls`; a typo would silently no-op the exclusion. A small guard (assert each set
  member is a declared rewrite directive) is worth adding, ideally folded into R346's regen-guard.

### Stage 2 — Remove the withheld directives' dedicated documentation (remaining)

Now that recovery is ticketed (see *Reintroduction & recovery* below), the withheld trio's
dedicated docs are removed cleanly rather than left half-advertised. The docs build fails on a
dangling `xref`, which is the guardrail that keeps this complete:

* Delete the reference pages `docs/manual/reference/directives/{tableMethod,sourceRow,experimental_constructType}.adoc`.
* Delete the dedicated recipe `docs/manual/how-to/source-row.adoc` and its `how-to/index.adoc` entry.
* Remove all index entries in `reference/directives/index.adoc`. This is two distinct edits, and
  the second is easy to miss because it bundles all three on two lines: (a) the alphabetical
  entries for `tableMethod`/`sourceRow`/`experimental_constructType` with their interim
  `_(not part of the first release)_` annotations, and (b) the entire "Not part of the first
  release" category block — every `xref` in that block dangles once the pages are deleted. This
  supersedes the interim demotion that landed in Stage 1's commit. Also drop the entries in
  `reference/index.adoc`.
* Strip the `@tableMethod` / `@sourceRow` teaching passages and `xref`s from the recipes that frame
  them (`how-to/handle-services.adoc`, `result-types.adoc`, `external-code.adoc`,
  `add-custom-conditions.adoc`, `condition-cascade.adoc`, `batch-lookups.adoc`, `test-your-schema.adoc`)
  and the `@experimental_constructType` mentions in `how-to/{federation-keys,apollo-federation}.adoc`.
  **Including `how-to/migrating-from-legacy.adoc`**, which carries two live `xref`s into deleted
  pages: line ~40 (`@notGenerated` migration table routing a class-backed-parent case to
  `sourceRow.adoc` — keep the row prose, drop/repoint the `@sourceRow` link) and line ~214
  (`@tableMethod` listed among "unchanged surface" directives — remove it and its `xref` from that
  list). Sibling reference pages that cross-link the deleted pages
  (`directives/{service,routine,record,notGenerated,enum,condition}.adoc`,
  `reference/{runtime-api,deprecations,mojo-configuration,diagnostics-glossary}.adoc`) lose the
  dangling `xref` (keep the surrounding prose where it still reads, drop the link).

`@notGenerated` / `@multitableReference` need no *prose* rewrite: their hard-removal migration is
already documented in `how-to/migrating-from-legacy.adoc` and they are labelled "rejected" in the
directive index. Note this is only about their explanatory prose — that same file still needs the
two withheld-trio `xref` fixes called out above, so it is in Stage 2's strip list, not exempt from it.

## Reintroduction & recovery

The removal is non-destructive: every removed page stays in git history, recoverable without
re-authoring. Recovery is **anchor-free** (no hardcoded SHA to go stale): for each removed file,
`git log --oneline --diff-filter=D -- <path>` finds the commit that deleted it and
`git checkout <that-commit>^ -- <path>` restores it. Restoration is ticketed:

* **R403** (`reintroduce-tablemethod-docs`) — rethink `@tableMethod`, and recover its docs if/when
  it re-enters the supported surface. Carries the absorbed capability/migration analysis. Deferred.
* **R404** (`reintroduce-sourcerow-docs`) — recover `@sourceRow` docs (incl. the dedicated recipe)
  when it enters the supported surface. Deferred.
* **R69** (`experimental-construct-type`) — owns `@experimental_constructType` doc reintroduction,
  gated on implementing the directive (a doc with no feature behind it is the thing we are removing).

## Acceptance criteria

1. `supported-directives.adoc`: the withheld trio and the rejected pair are absent from "Supported";
   the rejected pair appears under "Removed / rejected"; `@record` still appears under "Supported".
2. No `xref` to a removed page remains anywhere under `docs/` (the docs build is green).
3. The three reference pages and `how-to/source-row.adoc` are gone from the tree.
4. Recovery is captured by R403 / R404 / R69 with anchor-free (`git log --diff-filter=D`) restore
   instructions.
5. No generator behaviour change: `directives.graphqls` is untouched; no classify-time rejection
   added; the only Java change is the two `DirectiveSupportReport` sets.

## Out of scope

Actually retiring `@experimental_constructType` (R69) or `@multitableReference`; any change to
`@tableMethod` / `@sourceRow` / `@record` behaviour; and `@record` itself (kept as-is for v1). The
`@tableMethod` rethink (keep-and-improve vs retire-to-`@routine`/`@service`) is **R403**, not this
item; this item only withholds it from the v1 surface.
