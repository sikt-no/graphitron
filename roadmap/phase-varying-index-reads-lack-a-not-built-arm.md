---
id: R689
title: "Pre-index reads of ErrorIndex.EMPTY return a plausible wrong answer"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Pre-index reads of ErrorIndex.EMPTY return a plausible wrong answer

`BuildContext.errors` is initialised to `ErrorIndex.EMPTY`, and its siblings (`nodes`,
`tables`, `scalarVerdicts`) follow the same pattern: before `TypeBuilder.buildClassificationIndices()`
runs, a reader gets a populated-looking index that answers every membership question with
"no". The empty value conflates two different facts, "this schema declares no `@error`
types" and "the index has not been built yet", and a pre-index reader cannot tell them
apart, so it computes a plausible wrong answer instead of refusing. This has now bitten
twice through `BuildContext.detectErrorsFieldShape`: the routine-carrier grounding, and
then the DML-carrier grounding (R687). Both were fixed the same way, by moving the
grounding after the index build; R687 folded the two into one pass named for that
precondition, `RecordBindingResolver.groundIndexDependentBindings`, which is where the
former `groundRoutineCarriers()` now lives. In both cases the write target silently failed
to ground and the payload surfaced a misleading classify-time rejection. The only
enforcement today is prose comments on the call sites in `TypeBuilder.prepareForWalk`,
which is review-only and did not propagate the first time.

The fix shape to evaluate at Spec: give the not-yet-built state its own arm (a sealed
`NotBuilt` / `Built(map)` on the index types, or hand the built index to passes as a
parameter instead of letting them read it off the context), so a pre-index read is a typed
refusal or a compile failure rather than a silent empty. The fact-model principle applies
directly: an empty relation is a fact about the population, and "nothing captured yet" is a
different fact that deserves its own representation.
