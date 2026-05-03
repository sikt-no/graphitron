---
id: R64
title: "buildRuntimeStub accepts Rejection.Deferred directly"
status: Backlog
bucket: cleanup
priority: 9
theme: model-cleanup
depends-on: []
---

# buildRuntimeStub accepts Rejection.Deferred directly

`SplitRowsMethodEmitter.unsupportedReason` returns `Optional<Rejection.Deferred>`
(per R58 Phase C, commit `68a062c`). Each of the four `buildFor*` callers
immediately calls `.message()` on the result to feed the `String reason`
parameter of `buildRuntimeStub`, discarding the typed `EmitBlockReason` the
sealed `Deferred` arm carries:

- `SplitRowsMethodEmitter.java:289` (SplitTableField)
- `SplitRowsMethodEmitter.java:341` (SplitLookupTableField)
- `SplitRowsMethodEmitter.java:375` (RecordTableField)
- `SplitRowsMethodEmitter.java:410` (RecordLookupTableField)

The lift: `buildRuntimeStub` accepts `Rejection.Deferred` directly (or the
`EmitBlockReason` it wraps) instead of a free-form `String`. Same shape as the
R58 Phase C consolidation that made the rest of the deferred-stub plumbing
typed end-to-end.

Surfaced during the R22 post-shipping review (commit `181c28f`), but lives in
R58's domain not R22's; tracked separately so it doesn't get lost when R22
closed.

Promote to Spec when an implementer picks it up; the design space is small
(swap a `String` parameter for a typed one and update four call sites).
