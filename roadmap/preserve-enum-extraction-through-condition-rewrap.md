---
id: R862
title: "Input-field @condition rewrap drops the enum leaf extraction"
status: Backlog
bucket: correctness
priority: 3
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Input-field @condition rewrap drops the enum leaf extraction

A `@condition` on an input field is resolved like any other condition and then *rewrapped*, so that
the generated call reads its parameter values out of the enclosing argument's map rather than off a
top-level argument. `ConditionResolver.rewrapForNested` performs that rewrap by replacing each
value parameter's extraction with a `CallSiteExtraction.NestedInputField`, and it builds one through
the two-argument convenience constructor, whose javadoc states that it defaults the leaf to
`Direct`. The parameter's own leaf transform is therefore discarded.

For every parameter this has ever been exercised on that leaf was already `Direct`, so nothing
changed. It is not `Direct` for one population. `ServiceCatalog.legacyArgExtraction`, the only
producer of a condition parameter's extraction, returns `EnumValueOf` when the parameter's declared
Java type is an enum class, which is the jOOQ-generated enum a `@condition` method naturally takes
(`static Condition byRating(Film table, MpaaRating rating)`). Written on a field argument, that
method receives the enum. Written on an input field, the same method receives the wire string cast
to the enum type, which is a `ClassCastException` at request time rather than a build failure.

Two things make this worth stating rather than merely fixing in place. The rewrap has been repaired
once before for a neighbouring loss: the same method used to drop each parameter's own path descent,
so a dotted binding cast the whole wrapper map to the leaf type, and the repair appended the path
tail without revisiting the leaf. And the loss is silent by construction on both occasions, because
the extraction is replaced rather than composed; a rewrap that carried the existing extraction in as
the leaf could not lose one, and the three-argument constructor already refuses the one leaf shape
that would not compose (another `NestedInputField`).

Scope worth settling at Spec: whether the fix is simply passing `arg.extraction()` as the leaf, and
what a regression test looks like at which tier. The execution tier is where a wrong cast surfaces,
and the fixture needs a jOOQ enum column reachable from an input-field `@condition`.

## What is left, after the composition shipped elsewhere

R874, which needed the same composition to carry a decoded `@nodeId` key down to an input-field
`@condition`, shipped it: `ConditionResolver.rewrapForNested` now passes each parameter's own
extraction as the `NestedInputField` leaf rather than defaulting it to `Direct`. The renderer arm
that the enum case additionally needed shipped there too, `ConditionGlueRenderer.nestedExtraction`
having had no enum arm, so an `EnumValueOf` leaf fell through to the same cast-to-declared-type the
`Direct` default produced.

So the defect above is fixed and this item is now its coverage: a fixture that proves a jOOQ enum
column reaches an input-field `@condition` method as the enum. The tier question in the scope
paragraph stands unanswered, and is the whole of what Spec has to settle; the pipeline tier bans
code-string body matching, so the fixture belongs at compile or execution.

## Provenance

Found by counting the producers of a condition binding's extraction while narrowing the authored
condition carrier off the legacy model, not by a failing test or a user report.
