---
id: R514
title: "Ground DmlEmitted from @mutation(table:) so DELETE carrier payloads survive @table-on-input removal"
status: Spec
bucket: bug
priority: 4
theme: mutation-write
depends-on: []
created: 2026-07-23
last-updated: 2026-07-23
---

# Ground DmlEmitted from @mutation(table:) so DELETE carrier payloads survive @table-on-input removal

A `@mutation(typeName: DELETE, table:)` field that returns a carrier payload rejects with the
generic "return type 'X' is not yet supported; use ID or a @table type" as soon as `@table`
moves off the input type, even though the same payload shape classifies and executes today via
the deprecated `@table`-on-input bridge. R457 made the `@table`-on-input deprecation warning
(R332) actionable for DELETE by letting the field name its write target, but the migration only
works for bare-ID returns; every payload-returning DELETE is a dead end for the warning:

```graphql
input FilmDeleteInput { filmId: ID! @nodeId(typeName: "Film") }   # @table removed per R332
type DeleteFilmPayload { film: ID, errors: [DeleteFilmError!] }
deleteFilm(in: FilmDeleteInput!): DeleteFilmPayload!
    @mutation(typeName: DELETE, table: "film")

# → [author-error] @mutation(typeName: DELETE) return type 'DeleteFilmPayload'
#   is not yet supported; use ID or a @table type
```

Observed on 10.0.0-RC28; the gap is still present on trunk. Consequence in the field: a
subgraph whose DELETEs return error-channel payloads (all five payload-returning DELETEs in
fs-plattform's opptak-subgraph: `{ opptak: [ID!] }`, `{ grunnlag: ID, errors }`, ...) cannot
act on the deprecation warning at all; `@table` must stay on the input.

## Root cause

`RecordBindingResolver.groundDmlMutationField` grounds the payload's
`ProducerBinding.DmlEmitted` result-axis observation exclusively from the single
`@table`-bearing input argument:

```java
GraphQLArgument tableArg = findSingleTableInputArg(field);
if (tableArg == null) return;    // silent skip; @mutation(table:) never consulted
```

With no `DmlEmitted` binding, `GraphitronSchemaBuilder.registerProducerBackedCarrier` never
registers the payload as a producer-backed carrier (`TypeBuilder.carrierVerdict` resolves
nothing), so the field's `resolveReturnType` classifies down the `ScalarReturnType` arm instead
of `ResultReturnType`, and `MutationInputResolver.validateReturnType` falls through its
`scanStructuralDmlPayload` Admit to the generic rejection. The downstream machinery is all in
place and unreachable: `FieldBuilder.classifyDeletePayloadField` already resolves the write
target through `resolveDeleteWriteTarget` (which owns the `@mutation(table:)` > input-`@table`
precedence) and runs the structural payload scan. R457 wired the field-level table into the
input/WHERE side but not into the binding grounder.

## Fix sketch

`groundDmlMutationField` resolves the write target by the same precedence as
`resolveDeleteWriteTarget`: `@mutation(table:)` (via the already-shared
`MutationInputResolver.parseMutationTableArg`) preferred, the input's `@table` as the
deprecated bridge. Mirroring the precedence, not merely falling back, matters: when both are
present and disagree, `resolveDeleteWriteTarget` silently outranks the input `@table`, so a
grounder that read the input first would ground a `DmlEmitted` on the wrong table and the two
resolvers would disagree on the write target.

A phase fact constrains the shape (surfaced by principles-architect consult): the grounder
runs in `prepareForWalk()` *before* the classification walk, and
`registerProducerBackedCarrier` reads its output *during* the walk, while
`resolveDeleteWriteTarget` runs *inside* the walk. The grounder therefore cannot consume
`resolveDeleteWriteTarget`'s output; only the pure precedence sub-decision is shareable.

Settled design positions (architect-reviewed):

1. **Single-source the precedence in a phase-portable helper.** Extract the table-ref
   precedence only (parsed `@mutation(table:)` string preferred, else the input `@table`
   name, resolved through `svc.resolveTable`) into one helper keyed on
   `GraphQLFieldDefinition` plus the catalog service; it depends on nothing but SDL
   directives and the catalog, both available in the binding walk. `groundDmlMutationField`
   calls it for the `TableRef`; `resolveDeleteWriteTarget` calls the same helper and keeps
   its classify-phase-only work (input-field resolution, the `validateTableInputType`
   rejection mirror). Two independent precedence copies would be the "two producers of one
   fact" drift smell the design itself anticipates; sharing the sub-decision respects the
   phase split while collapsing the producers to one.
2. **Verb gate reads the classifier's support set, not a re-hardcoded DELETE.**
   `@mutation(table:)` support is single-sourced in `FieldBuilder.TABLE_ARG_SUPPORTED_VERBS`
   (currently `{DELETE}`), and the classifier rejects the arg on other verbs
   (`MutationTableArgError.UnsupportedVerb`). The grounder's field-level rung must gate on
   that same set: either lift the set to a shared home, or have the precedence helper of
   position 1 own the verb gate, so a future verb gaining support flows to the grounder
   automatically. Grounding from `@mutation(table:)` on an unsupported verb would produce a
   spurious binding for a field the classifier then rejects. INSERT/UPDATE/UPSERT grounding
   is unchanged (input `@table` only).
3. **Arity derivation is a non-goal; do not add a third derivation site.**
   `DmlEmitted.arrival` is mandatory-non-null but currently unread by every consumer
   (consumers read `isPresent()`, `kind()`, `tableRef()` only; the DELETE payload classifier
   re-derives bulk-vs-single from `inputArg.list()` at classify time). On the field-derived
   route, lift `Arity` from the field's single input-object argument (the same argument
   `resolveDmlWalkerInputArg`'s `RawArg` arm surfaces); with zero or multiple input-object
   arguments, skip silently as today. Do not invent a richer rule: `arrival` is a candidate
   for a follow-up collapse, not a fact to deepen here.
4. **Silent-skip posture stays, with one dispatch dependency to pin.** An unresolvable
   `@mutation(table:)` name stays a silent skip in the grounder; the invariant's enforcer is
   `resolveDeleteWriteTarget` (`unknownTableRejection`), and the grounder's contract is
   observation-grounding, not diagnostics. The implementation must confirm (and the tests
   pin) that a DELETE whose payload resolves as `ScalarReturnType` for want of a carrier
   still lands in a classifier arm that calls `resolveDeleteWriteTarget`
   (`classifyDeleteTableField` or `classifyDeletePayloadField`), so the loud rejection fires
   on both dispatch arms.
5. **No new validator rule.** The fix removes a spurious rejection rather than adding a
   classifier branch, and `resolveDeleteWriteTarget` already mirrors the
   `validateTableInputType` input-field rejections on the field-derived path, so the
   validator-mirror obligation is already discharged.

## Coverage

- Pipeline tier (extend `MutationTableArgClassificationTest`): payload-returning DELETE with
  `@mutation(table:)` and no `@table` on the input classifies equivalently to the
  `@table`-on-input route, for both the singleton (`{ deletedId: ID, errors }`) and bulk
  (`{ deletedIds: [ID!] }`) carrier shapes; both-present precedence (field table wins);
  unknown `@mutation(table:)` still rejects loudly via `resolveDeleteWriteTarget` on the
  payload-returning arm (no regression to a silent misground; pins position 4's dispatch
  dependency). "Equivalently" means the classified-model verdict (the `GraphitronField` /
  registered carrier type), never a string diff of generated method bodies.
- Execution tier: flip one payload-returning DELETE fixture (or add one beside
  `deleteStorageBinByCode` in `DmlBulkMutationsExecutionTest`) to the `@mutation(table:)`
  route and round-trip it against PostgreSQL.
- No code-string assertions on generated bodies (per R457's precedent).
