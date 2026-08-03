---
id: R565
title: "@table-on-input rejection cascades into a misleading @mutation arg-shape error"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# @table-on-input rejection cascades into a misleading @mutation arg-shape error

When an input type is rejected as a type (today's live case: the retired
`@table`-on-input location, `TypeBuilder.buildInputType`), every consuming
`@mutation` field additionally reports `@mutation fields take exactly one
input-object argument; found '<arg>' of type '<Input>'`. The claim is false:
the argument *is* a single input object. `FieldBuilder.resolveDmlWalkerInputArg`
reads `lookAheadVerdict(typeName)`, gets the type's `UnclassifiedType`, and
falls into the not-an-input-object arm, so the arg-shape message stands in for
"this input type did not classify". The query/filter side already has the right
shape: `FieldBuilder.classifyArgument` computes `isInputLike` as
`InputType || (UnclassifiedType && SDL type is a GraphQLInputObjectType)` and
routes the second case through the plain-input path so the focused error
survives.

This doubles the author's error count and points the second error at the wrong
coordinate and the wrong fact. Observed on a downstream subgraph migrating off
`@table`-on-input: six real type-level rejections came with six phantom
arg-shape errors, and the phantom message contradicts the schema in front of
the author (the DELETE fields each had exactly one input argument).

`table-on-input-reopen-deprecation-window` removes the trigger that surfaced
this: once `@table` on an input classifies plainly and only warns, that input
stops producing an `UnclassifiedType` verdict. The conflation itself survives,
because any other type-level input rejection reaches the same arm, so this item
stays live on its own merits with the urgency taken out of it.

## Scope sketch

Distinguish "the argument's type is not an input object" (a genuine arg-shape
error) from "the argument's input type did not classify" in
`resolveDmlWalkerInputArg`, mirroring the `isInputLike` split. For the second
case, either suppress the field-level error (the type-level rejection already
names the fix and the coordinate) or surface the input type's own rejection so
the two errors agree. Worth checking whether the same conflation reaches the
other `lookAheadVerdict`-guarded field paths and the LSP diagnostics projector.
