---
id: R400
title: "Withhold not-in-use directives from the v1 advertised directive surface"
status: In Review
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

**Bijection test carve-out (R68).** `DirectiveDocCoverageTest`
(`graphitron-sakila-example`) pins a declared-directive ↔ reference-page bijection. Deleting the
withheld trio's pages while AC5 keeps them declared in `directives.graphqls` breaks it: the trio
becomes declared-but-page-less. The fix narrows the invariant to "a directive needs a page only if
it is on the *advertised* surface", and derives the exempt (withheld) set from the generated
`supported-directives.adoc` fragment that `DirectiveSupportReport` renders (a declared directive
absent from that fragment is withheld) rather than duplicating `WITHHELD_FROM_V1`, so the test
cannot drift from the report that owns the policy. When a withheld directive is re-advertised
(R403/R404/R69) its page reappears and the bijection tightens automatically. Test-infra only.

> Rework verification: full `mvn -f graphitron-rewrite/pom.xml clean install -Plocal-db` is **green**
> after the fix, including `DirectiveDocCoverageTest` (ran in-reactor, 1 test, 0 failures). The
> `graphitron`-core `Query.allParties` / joined-table `UnclassifiedType` cascades seen on a first
> pass were the environmental stale-`rewrite_test`-DB issue the reviewer flagged (the web sandbox
> seeded the DB before R389's `party*` / `jti_*` tables landed): re-seeding from
> `graphitron-sakila-db/src/main/resources/init.sql` and a `clean install` regenerates the jOOQ
> catalog and clears them. Not an R400 defect, and not trunk code breakage.

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
2. No `xref` to a removed page remains anywhere under `docs/`, and the build is green. "Green" is
   the full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` (which runs
   `DirectiveDocCoverageTest`), not just the AsciiDoctor render; the docs render passing on
   `fail-on-WARN` (the dangling-`xref` guard) is necessary but not sufficient.
3. The three reference pages and `how-to/source-row.adoc` are gone from the tree.
4. Recovery is captured by R403 / R404 / R69 with anchor-free (`git log --diff-filter=D`) restore
   instructions.
5. No generator behaviour change: `directives.graphqls` is untouched and no classify-time rejection
   is added. The Java touched is test/tool infrastructure only: `roadmap-tool`'s
   `DirectiveSupportReport` (Stage 1: the two policy sets `REJECTED_ON_USE` / `WITHHELD_FROM_V1`
   and the `renderMigration` filtering that consumes them) and, in Stage 2, the
   `DirectiveDocCoverageTest` carve-out (see Stage 2 note below). No emitter, classifier, or
   validator code changes.

## In Review -> Ready: review feedback (2026-06-30)

**Rework requested. Build is not green.** `mvn -f graphitron-rewrite/pom.xml install
-Plocal-db` fails at `graphitron-sakila-example`:

    DirectiveDocCoverageTest.everyDirectiveHasAReferencePageAndViceVersa:82
    Expecting empty but was: ["experimental_constructType", "sourceRow", "tableMethod"]

`DirectiveDocCoverageTest` (R68 Phase 2, at
`graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/internal/DirectiveDocCoverageTest.java`)
is a bidirectional drift seam: every directive declared in `directives.graphqls` must
have a `reference/directives/<name>.adoc` page, and vice versa. Its Javadoc is explicit:
"a PR that removes a directive must remove its page (or the build fails)." Stage 2 deleted
the withheld trio's reference pages (AC3) while AC5 deliberately keeps the trio **declared**
in `directives.graphqls`; that combination breaks the bijection in the missing-page
direction. The failure is deterministic and fully attributable to R400 (the test is
untouched by this item; it was green before Stage 2).

The In Review commit claimed "Docs build green on fail-on-WARN; AC1-AC5 verified" ; that
verified the AsciiDoctor render (the dangling-`xref` guard, which is genuinely clean), but
the full `install` test suite was evidently not run, so this invariant slipped through.
**The "build green" acceptance bar is the full `install -Plocal-db`, not just the docs
render.** AC2 should be reworded to say so, or a new AC added.

Suggested fix (cheapest, architecturally consistent): teach `DirectiveDocCoverageTest` to
subtract the withheld set from the directives-that-require-a-page, the same way
`DirectiveSupportReport` already filters `WITHHELD_FROM_V1` out of the advertised "Supported"
list. A withheld-but-declared directive intentionally has no reference page, so it should be
exempt from the missing-page assertion (the stale-page direction stays as-is). This is
test-infra, not generator behaviour, so it does not touch AC5's "no behaviour change /
`directives.graphqls` untouched / no classify-time rejection" guarantee. Factor the withheld
set so the test and the report cannot drift apart. (The rejected pair `notGenerated` /
`multitableReference` keep their pages and are unaffected; `@record` keeps its page.)

Everything else in the diff checks out and should carry forward unchanged: AC1 (the generated
`supported-directives.adoc` correctly drops the trio + rejected pair from "Supported", lists
the rejected pair under "Removed / rejected", keeps `@record`); AC2's dangling-`xref` guard
(zero `xref`s to deleted pages remain); AC3 (the three reference pages + `how-to/source-row.adoc`
are gone); AC4 (recovery tickets R403 / R404 / R69 exist with anchor-free restore); AC5 (the
only generator-side change is `roadmap-tool`'s `DirectiveSupportReport` + its test;
`directives.graphqls` untouched). The deliberate deviation from the spec's literal strip-list
(leaving bare-prose mentions in `add-custom-conditions` / `condition-cascade` / `test-your-schema`
because they carry no `xref` to a deleted page and the directives are withheld-not-removed) is
sound and documented in `2ea7900`; keep it.

Note for the next implementer (environmental, not an R400 bug): a stale web-sandbox
`rewrite_test` DB seeded before R389's `party*` tables landed makes the `graphitron` module's
`Query.allParties` corpus tests fail with `UnclassifiedType` cascades. Re-seed from
`graphitron-sakila-db/src/main/resources/init.sql` and `clean install` so the jOOQ catalog
regenerates before reading the `DirectiveDocCoverageTest` failure above.

## Out of scope

Actually retiring `@experimental_constructType` (R69) or `@multitableReference`; any change to
`@tableMethod` / `@sourceRow` / `@record` behaviour; and `@record` itself (kept as-is for v1). The
`@tableMethod` rethink (keep-and-improve vs retire-to-`@routine`/`@service`) is **R403**, not this
item; this item only withholds it from the v1 surface.
