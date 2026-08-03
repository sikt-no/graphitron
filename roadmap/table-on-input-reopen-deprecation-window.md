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
otherwise inert (today it rejects before resolution). That inertness is not free:
see the raw-directive readers below.

**Raw-directive readers.** Dropping the classify-time arm is necessary but not
sufficient. R519 retired the *verdict* and left two main-source readers of raw
`@table` on an `INPUT_OBJECT` standing, harmless only because the rejection
short-circuited them. Both go live the moment the input classifies, and both
have to be handled here or the headline claim is false:

- `RecordBindingResolver.groundRootProducers` registers an input-axis
  `ProducerBinding.RootTable` off `@table(name:)`, the direct sibling of the
  `GraphQLObjectType` arm above it. With that arm in place the directive is
  *honored*, not ignored: `bindings.resolveInput` hands `buildPlainInputType` the
  declared table's jOOQ record class, so a `@table`-carrying input lands on
  `JooqTableRecordInputType` where the same input without the directive lands on
  `PojoInputType`. Worse, an input that is also a `@service` method param picks up
  a second input observation, and `RecordBindingResolver.fold` turns two
  disagreeing classes into a `RecordBindingMultiProducer` rejection: the build
  still fails, now with a backing-class diagnostic instead of a migration
  message. Delete the arm. It is the same class of straggler as the
  `MutationInputResolver.singleTableInputType` reader R519 did delete, and no
  compile error points at either.
- `InputBeanResolver.collectJooqBindings` rejects a *nested* input carrying
  `@table` ("a nested `@table` input is a second DML target"). Pre-R519 that
  reading was true; once the directive means nothing, the type is an ordinary
  directiveless grouping input and should flatten onto the parent record. This
  site is reached while collecting column bindings for the enclosing param, so it
  is independent of the nested type's own verdict and hard-fails a schema this
  item promises only warns. Design fork for the author: delete the rejection
  outright (one more accept-and-ignore site) or keep it and accept that `@table`
  on an input is not uniformly inert. Recommend deleting; an "ignored except when
  nested" contract is both harder to document and harder to close later.

Grepping `DIR_TABLE` reads under an `INPUT_OBJECT`-typed guard is the
completeness check for this sweep; every other read is object- or
interface-typed and unaffected.

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

**Fix affordance: none, and no work to do.** Checked, so the implementer does not
re-derive it. `BuildWarning.NoRule(String message, SourceLocation location)`
carries no fix field; `Optional<LintFix>` lives on the `LintFinding` arm alone,
deliberately (the sealed interface's javadoc: a finding's rule is a type and its
fix lives only on the arm that has one). Independently,
`LintFix.deleteBareAppliedDirective` returns empty for any applied directive that
*carries arguments*, which `@table(name: "…")` does, so it would yield no fix even
on the `LintFinding` arm. Ship the warning fix-less and do not reshape the sealed
interface for it.

## Sweep

The retirement statement went out across 24 doc pages in R519 and has to come
back to "deprecated, ignored, warns":

- `directives.graphqls`: the `@table` description's "Retired on `INPUT_OBJECT`"
  paragraph. Also the `@mutation` description's `table:` paragraph ten lines
  down, which still reads "It supersedes the deprecated `@table` on the DELETE's
  input type; when both are present the field-level `table:` wins and the input's
  `@table` is outranked". That has been false since R519 (both present is a build
  failure, not an outranking) and stays false here (ignored, not outranked), and
  it is the one surface that would actively contradict the new warning's
  "had no effect" clause. The acceptance criterion below does not catch it,
  because it never says "retired" or "rejected".
- `docs/manual/reference/directives/table.adoc` (WARNING admonition, plus the
  `== Retired on input types` heading, its `[[_retired_on_input_types]]` anchor,
  and the line-14 sentence and xref that point at it, all of which move in
  lockstep or the xref dangles),
  `docs/manual/reference/deprecations.adoc` (the `@table` on `INPUT_OBJECT` row
  plus the rejected-locations bullet below the table),
  `docs/architecture/reference/code-generation-triggers.adoc` (the
  `Input type with @table` row, which pins `UnclassifiedType` + "build fails"),
  and `docs/manual/reference/directives/mutation.adoc` ("on input types it is a
  retired location").
- `docs/manual/how-to/migrating-from-legacy.adoc` needs more than one section
  move, and the "WARN today, error later" section is the wrong destination as
  written: it is titled *Synthesis shims*, opens with "Two paths still work", and
  points readers at the synthesis-shim retirement item. Nothing is synthesized
  here; the directive is inert. Prefer keeping the existing
  `=== @table on input types` section and reframing its verdict sentence from
  rejected to accepted-ignored-warns, then reconciling the page's other five
  touchpoints: the `:description:` and the intro paragraph's four-category
  taxonomy, the `@field(name:)`-shim parenthetical calling input `@table` "a hard
  removal, covered above", the directive-matrix line saying the same, and the
  migration-steps block, where step 1 ("Run a `mvn install` to surface every …
  `@table`-on-input rejection … the build won't compile until this lands") plus
  the "Steps 1 is non-optional (the build fails)" summary both stop being true.
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
- Three further test surfaces pin the rejection, not two.
  `TypeClassificationProjectionTest` is a message re-pin.
  `GraphitronSchemaBuilderTest.InputFieldResolutionCase.TABLE_ON_INPUT_RETIRED` is
  a verdict inversion, not a re-pin: it casts `schema.type("CustomerInput")` to
  `UnclassifiedType`, so it has to assert the plain verdict instead, and its
  `_RETIRED` case name is itself retired vocabulary.
  `JooqRecordServiceParamPipelineTest.tablePresentOnServiceRecordParamInput_rejectsAtTheType`
  is worth more than a message swap: its `AssignFilmActorTableInput @table(name:
  "film_actor")` fixture already sits beside a directiveless twin (`PURE_FK_SDL`)
  that classifies to the JooqRecord carrier, so rewrite it as an *equivalence*
  assertion (same carrier, same column bindings, plus the warning). That makes it
  the regression home for both raw-directive reader deletions above and for
  R519's claim that the `InputBeanResolver` type-identity arm stays correctly
  deleted, a claim R519 justified on the `@table`-plus-jOOQ-record-param
  conjunction being unauthorable, which this item makes authorable again.
- `graphitron-sakila-example`: a fixture that actually carries `@table` on an
  input is what proves the pass fires end-to-end through the plugin. Adding one
  re-opens the `FixtureWarningsGateTest` carve-out R332 needed, so budget for
  that. Confirmed: the gate filters `LintFinding`s by `rule().source()` (ENGINE,
  CODEGEN) and then asserts `hasSize(1)`, so a `NoRule` advisory has no filter to
  ride and trips the size assertion.
- `DeprecationsDocCoverageTest` needs no change: `WHOLE_DIRECTIVE_DEPRECATIONS`
  already carries `"table"` (R332 added it and R519 left it), and it only requires
  a row in `deprecations.adoc`, not a particular verdict in that row. Noted so the
  implementer does not go looking.
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

An input carrying `@table` classifies exactly as the same input without it,
verdict and backing class both, including when it is a `@service` jOOQ-record
param and when it is nested inside another input. `@table(name:)` naming a
resolvable table changes nothing, and naming an unresolvable one changes nothing
either. The build emits one non-fatal advisory per such input naming the type, the
per-verb replacement, and the fact that the directive was ignored. The reported
subgraph builds with warnings and no errors after removing nothing. No doc page or
SDL description still calls the location retired or rejected, or describes the
input directive as outranked-but-live. Full reactor green under `-Plocal-db`.

## Retired vocabulary

- "retired location" / "no longer supported" as applied to `@table` on
  `INPUT_OBJECT` (the phrasing R519 introduced across SDL descriptions, docs, and
  `TableOnInputRejectionTest`), including the
  `GraphitronSchemaBuilderTest.InputFieldResolutionCase.TABLE_ON_INPUT_RETIRED`
  case name and the `_retired_on_input_types` AsciiDoc anchor.
- "the input's `@table` is outranked" as applied to a DELETE's input type
  (`directives.graphqls`, the `@mutation` `table:` paragraph). The directive is
  not outranked by anything; it is ignored.
