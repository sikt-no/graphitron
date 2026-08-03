---
id: R566
title: "Reopen the @table-on-input deprecation window: accept, ignore, and warn instead of reject"
status: Spec
bucket: architecture
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Reopen the @table-on-input deprecation window: accept, ignore, and warn instead of reject

R519 Phase B (`7eef0ddd1`) turned `@table` on `INPUT_OBJECT` from a
deprecation warning into a classify-time rejection in one step. The window was
too short: a downstream subgraph picking up a newer `10-SNAPSHOT` hit six
type-level rejections at once, with no build that merely warned in between. Put
the warning back. `@table` on an input is accepted, **ignored**, and reported as
a per-usage non-fatal advisory that says it will break in a future release.

This is a deliberate reversal of R519's cutover decision, not a bug fix: the
consumer-derived write-target model that R519 built stays exactly as it is, and
the directive genuinely contributes nothing. Only the verdict on an author who
still carries it changes, from "build fails" to "build warns".

## Design

**Verdict.** `TypeBuilder.buildInputType` drops the `UnclassifiedType` arm and
falls straight through to `buildPlainInputType`. An input carrying `@table` gets
the same verdict it would get without the directive; the `name:` argument is
never read, so `@table(name: "no_such_table")` on an input warns and is
otherwise inert (today it rejects before resolution).

**Warning site.** Restore `GraphitronSchemaBuilder.emitTableOnInputDeprecationWarnings`
as a post-classification pass, recoverable near-verbatim from `7eef0ddd1^`
(`GraphitronSchemaBuilder.java:815`) together with its three
`{delete,insert,update}ConsumedInputTypes` helpers. Post-classification and not
inside `buildInputType` for two reasons: the per-verb replacement wording needs
the classified field registry to know who consumes the input, and
`buildInputType` is reached through the memoizing `lookAheadVerdict`, so
emitting there makes warning multiplicity a function of memo timing.

The `BuildWarning.NoRule` arm is the right one, unchanged from R332's reasoning:
a deprecation announcement is not a lint-engine finding. Verify the two accessors
the restored helpers read still exist post-R519 (`InputArgRef.inputTypeName()`
for the DELETE leaves, `tableInputArg().typeName()` for the INSERT ones) and
re-check the switch arms are still exhaustive over the DML leaves.

**Message.** One change from the R332 wording: say **ignored**, not only
deprecated. An author whose input `@table` named a *different* table than the
consuming field resolves is the one case where accept-and-ignore is not
behaviour-preserving against the author's intent, and today's rejection is what
catches it. The message is the whole mitigation, so it has to state that the
directive had no effect, alongside the existing per-verb replacement clause.

**Fix affordance.** Unlike R332 this warning has a safe deletion fix available
(`LintFix.deleteBareAppliedDirective` handles the bare applied-directive span,
which `@table(name:)` is). Worth attaching if `NoRule` can carry one; if the fix
field lives only on `LintFinding`, leave it off rather than reshaping the sealed
interface for this.

## Sweep

The retirement statement went out across 24 doc pages in R519 and has to come
back to "deprecated, ignored, warns":

- `directives.graphqls`: the `@table` description's "Retired on `INPUT_OBJECT`"
  paragraph.
- `docs/manual/reference/directives/table.adoc` (WARNING admonition),
  `docs/manual/reference/deprecations.adoc` (the `@table` on `INPUT_OBJECT` row
  plus the rejected-locations bullet below the table),
  `docs/manual/how-to/migrating-from-legacy.adoc` (the `@table` on input types
  section moves out of the hard-removal framing and into the "WARN today, error
  later" synthesis-shim section, which already houses the two other shims),
  `docs/architecture/reference/code-generation-triggers.adoc`.
- `TypeBuilder.emitDirectiveIgnoredWarning`: the comment at the shadowed-by-`@table`
  arm asserts an input carrying `@table` is rejected and that the rejection
  supersedes the `@record` warning. The `!isInput` guard itself stays correct
  (`@table` on an input contributes nothing to binding, so an input carrying
  both should reach the Matches / Disagrees arms); only the comment is wrong.
- `TableOnInputRejectionTest` becomes `TableOnInputDeprecationWarningTest`
  again: warning fires per usage with a source location, per-verb wording for
  DELETE / INSERT / UPDATE / filter-only, unknown table name warns rather than
  rejects, and the type's verdict is the plain one. The R332-era test is in
  history at `7eef0ddd1^` and is the starting point.
- `TypeClassificationProjectionTest` and `JooqRecordServiceParamPipelineTest`
  currently pin the rejection message and flip to the warning.
- `graphitron-sakila-example`: a fixture that actually carries `@table` on an
  input is what proves the pass fires end-to-end through the plugin. Adding one
  re-opens the `FixtureWarningsGateTest` carve-out R332 needed (the gate scopes
  itself to exactly one advisory category), so budget for that.
- LSP/MCP: `@table` stays offered as an `INPUT_OBJECT` completion, and the input
  application should surface as a squiggle rather than an error. R520 (the
  removal-housekeeping tail) has "drop `@table` from the `INPUT_OBJECT`-applicable
  directive list" in scope; that sub-goal is void once this lands, and R520 needs
  re-scoping or discarding as part of this item.

## Out of scope

The eventual re-removal. This item reopens the window; it does not schedule the
close. When that happens it is a fresh item, and the lesson from R519 is that the
warning has to have shipped in a release consumers actually built against before
the rejection lands.

## Acceptance

An input carrying `@table` classifies exactly as the same input without it, and
the build emits one non-fatal advisory per such input naming the type, the
per-verb replacement, and the fact that the directive was ignored. The reported
subgraph builds with warnings and no errors after removing nothing. No doc page
or SDL description still calls the location retired or rejected. Full reactor
green under `-Plocal-db`.

## Retired vocabulary

- "retired location" / "no longer supported" as applied to `@table` on
  `INPUT_OBJECT` (the phrasing R519 introduced across SDL descriptions, docs, and
  `TableOnInputRejectionTest`).
