---
id: R585
title: "Typed rejections on the input-field resolution path"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Typed rejections on the input-field resolution path

`InputFieldResolution.Unresolved(String fieldName, String lookupColumn, String reason)` carries prose
where every sibling builder-step result carries a typed `Rejection`. `FieldRegistry` records the
consequence in a comment: "Unresolved carries no Rejection variant (the failure path doesn't produce an
UnclassifiedField; it's a transient resolution outcome consumed by the caller)". The development
principles put this the other way round under "Builder-step results are sealed, not strings or
out-params": rejection is "a typed variant with a stable LSP code, never a string or out-param", and
never "prose composed at the detection site". This path is the standing exception.

It is not only the record. Both consumers **fan many failures into one prose rejection**:

- `InputFieldResolver.resolve` joins every failure's `reason` with `"; "` into a single
  `Rejection.structural`. It already reaches for a typed arm when it can, lifting to
  `Rejection.unknownColumn` only when there is exactly one column-miss failure and no competing
  `@condition` errors, and comments that everything else "folds to structural prose". That guard is the
  shape of the problem: the typed arm is reachable only in the degenerate single-failure case.
- `TypeBuilder.resolveInputFields` joins into `InputFieldsResolution.Failed(String)`, whose reason is
  then re-wrapped as `Rejection.structural` at three `FieldBuilder` sites plus the resolver fold, each
  with its own prefix.

So a rejection's identity is destroyed twice over: once when the producer writes prose instead of a
variant, and again when n failures on one input type collapse into one string. Sixteen
`new InputFieldResolution.Unresolved` sites in `BuildContext` produce into it; three consumers read it.

Why it matters beyond tidiness. The retired-directive rejections `@notGenerated` and `@lookupKey` on a
mutation input field each have three separate identities today, and the split is not cosmetic: the
`FieldBuilder` and `BuildContext` spellings of the `@lookupKey` sentence agree, while
`MutationInputResolver.rejectInputFieldDirectives` words it differently ("remove it (the field is a
filter by default)"), so no message-template heuristic fuses them even now. Any consumer that wants to
count or cluster rejections by cause sees one cause as three, and the LSP sees prose where it could see
a stable code.

Scope sketch, in dependency order:

1. Give `Unresolved` a typed `Rejection` (replacing or alongside `reason`), over the sixteen producers.
2. Settle the fan-in fork, which is the one real design decision here: when several input-field failures
   land on one type, either emit several rejections (changing what the build reports per input type, and
   therefore what a diagnostics consumer counts: per-field defects rather than one per input type) or
   keep a fold and accept that a typed identity survives only in the single-failure case. The existing
   `canLiftToUnknownName` guard is today's implicit answer and it chose the second; the first is
   probably right, but it changes observable validator output and needs its own decision.
3. Land the retired-directive convergence on top: route `@notGenerated` and `@lookupKey` through
   `Rejection.directiveConflict` from all sites, so each cause has one identity carrying the directive
   name. `@multitableReference` needs no work; its retirement already routes through
   `directiveConflict` from a single site, which is the target shape.
4. Pin what `DirectiveConflict.directives` means, since step 3 makes it load-bearing. Its javadoc
   promises only "the bare directive names (no leading `@`) for downstream tooling", and the sites do
   not agree: ten name directives present on the declaration, while `FieldBuilder`'s `@asConnection`
   on an inline `TableField` lists `splitQuery`, which is *absent* and is the remedy. So the component
   is today a bag mixing causes with fixes. State the contract as "every listed directive is present on
   the declaration" and pin it; the anomalous site then either drops `splitQuery` from the list and
   keeps "add `@splitQuery`" in its prose where it belongs, or is declared an exception on purpose.
   The aggregated-diagnostics item is the first consumer to depend on the answer: it groups
   diagnostics on this component, and it can only offer a per-directive count once the contract holds,
   so it currently groups on the whole set instead.

Carved out of the aggregated-diagnostics MCP item (`mcp-aggregated-diagnostics`) at Spec review, which
had this convergence as an in-item step sized "small" across three files. It is neither: the identity
cannot move without the record and the fan-in moving first. That item now depends on this one, because
its `directives` pivot dimension counts only rejections that carry a typed directive list, so before the
convergence it would report one row for `@notGenerated` where three rejections concern it. A confidently
wrong count is the failure mode that item exists to remove.

Blast radius, measured at carve-out (re-measure at pickup): `InputFieldResolution`,
`BuildContext.classifyInputFieldInternal` and the other fifteen producers, `InputFieldResolver.resolve`,
`TypeBuilder.resolveInputFields`, `FieldRegistry`'s trace arm, `MutationInputResolver`, and the
`FieldBuilder` re-wrap sites. Any test asserting on the joined prose of a multi-failure input type moves
with the fork decision. Spans `graphitron` alone, plus `graphitron-lsp` if the new arms take
`lspCode()`s.
