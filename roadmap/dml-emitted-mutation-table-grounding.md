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

Decisions to settle in Spec (drafted positions below):

1. **Scope of the field-level rung.** `@mutation(table:)` is documented DELETE-only and
   rejects on other verbs (`MutationTableArgError.UnsupportedVerb`), so the grounder consults
   it only when `readDmlKind` yields DELETE. INSERT/UPDATE/UPSERT grounding is unchanged
   (input `@table` only).
2. **Arity derivation without a `@table` arg.** Today the bulk-vs-single `Arity` lifts from
   the `@table` input arg's list shape. On the field-derived route, lift it from the field's
   single input-object argument (the same argument `resolveDmlWalkerInputArg`'s `RawArg` arm
   surfaces); with zero or multiple input-object arguments, skip silently as today (the
   per-mutation diagnostics live downstream).
3. **Silent-skip posture.** An unresolvable `@mutation(table:)` name stays a silent skip in
   the grounder; `resolveDeleteWriteTarget` already rejects unknown tables loudly
   (`unknownTableRejection`), and the grounder's contract is observation-grounding, not
   diagnostics.
4. **Shared precedence vs local mirror.** The precedence is small (prefer parsed field table,
   else input `@table`), but two independent copies that could drift is a new failure mode.
   Prefer extracting a minimal shared helper (e.g. on `MutationInputResolver`, next to
   `parseMutationTableArg`) that both `RecordBindingResolver` and
   `FieldBuilder.resolveDeleteWriteTarget` consult for the *table-name choice*, leaving each
   caller its own resolution/diagnostic posture. If extraction fights the two call sites'
   shapes, a local mirror with `{@link}`-anchored cross-references is acceptable.

## Coverage

- Pipeline tier (extend `MutationTableArgClassificationTest`): payload-returning DELETE with
  `@mutation(table:)` and no `@table` on the input classifies byte-identical to the
  `@table`-on-input route, for both the singleton (`{ deletedId: ID, errors }`) and bulk
  (`{ deletedIds: [ID!] }`) carrier shapes; both-present precedence (field table wins);
  unknown `@mutation(table:)` still rejects via `resolveDeleteWriteTarget` (no regression to
  a silent misground).
- Execution tier: flip one payload-returning DELETE fixture (or add one beside
  `deleteStorageBinByCode` in `DmlBulkMutationsExecutionTest`) to the `@mutation(table:)`
  route and round-trip it against PostgreSQL.
- No code-string assertions on generated bodies (per R457's precedent).
