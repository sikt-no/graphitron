---
id: R687
title: "A DML carrier payload with an errors field loses its return-derived write target"
status: Backlog
bucket: structural
priority: 5
theme: mutation-write
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# A DML carrier payload with an errors field loses its return-derived write target

Adding an `errors` field to a DML mutation's payload type makes the whole mutation stop
classifying, with a rejection that names the wrong thing. The author sees

```
@mutation(typeName: UPDATE) return type 'OppdaterFeideApplikasjonDetaljerPayload'
is not yet supported; use ID or a @table type
```

although the payload is a perfectly well-formed carrier and the identical payload without
its `errors` field classifies clean. Because errors-as-data requires an `errors` field on
the payload, the practical read of this message is that typed `@error` failures do not work
on generated DML mutations at all. They do; the author just has to name the write table by
hand. Reported against 10.0.0-RC31 by the tilgangsstyring subgraph, whose schema is the
shape below.

```graphql
type Mutation {
  oppdaterFeideApplikasjonDetaljer(
    input: [OppdaterFeideApplikasjonDetaljerInput!]!
  ): OppdaterFeideApplikasjonDetaljerPayload @mutation(typeName: UPDATE)
}

type OppdaterFeideApplikasjonDetaljerPayload {
  applikasjoner: [FeideApplikasjon]
  errors: [OppdaterApplikasjonDetaljerError]
}
```

## Why it happens

A DML mutation that does not name its table with `@mutation(table:)` derives the write
target from its return type. For a carrier payload that means
`MutationInputResolver.resolveReturnDerivedTable` runs the structural carrier scan
`BuildContext.scanStructuralDmlPayload` over the payload and reads the table off its single
`@table`-element data field. The scan skips errors-shaped fields, recognising them through
`BuildContext.detectErrorsFieldShape`, which resolves each union or interface member against
the `ErrorIndex`.

The catch is when that scan runs. The write target is grounded in
`RecordBindingResolver.groundDmlMutationField`, reached from `resolveAll()`, and
`TypeBuilder.prepareForWalk` calls `resolveAll()` *before* `buildClassificationIndices()`
builds the `ErrorIndex`. At that point `ErrorIndex` is still `ErrorIndex.EMPTY`, so
`detectErrorsFieldShape` returns null for the `errors` field, the scan counts it as a second
data channel and rejects the payload as an unrecognised carrier shape, and the grounder
takes its silent-skip arm. No `DmlEmitted` binding is minted, so the payload never earns a
carrier verdict, so at classify time `BuildContext.resolveReturnType` falls through every
recognised arm to `ScalarReturnType`, and `validateReturnType`'s scalar arm emits the
misleading "not yet supported" message. The second, classify-time run of the same scan
admits the payload, which is why the failure is invisible from the scan's own behaviour.

`prepareForWalk` already carries the shape of the fix in a comment on the line above:
`groundRoutineCarriers()` was deliberately moved *after* `buildClassificationIndices()` for
exactly this reason, on the routine-carrier family. The DML family never got the same
treatment.

## What the report got right, and what it got wrong

The reporter concluded the gap was UPDATE-specific, because an identical wrapper payload on
`@mutation(typeName: INSERT)` passed validation in the same build. Reproduced against trunk,
the failure is verb-agnostic: INSERT and UPDATE both reject with the same message, and both
classify clean once the payload's `errors` field is removed or `@mutation(table:)` is
supplied. The verb asymmetry the reporter observed is therefore unexplained and worth
chasing at Spec, since it suggests a second difference in their schema that this account does
not cover.

## Scope

* Give the DML mutation grounding the same post-index ordering `groundRoutineCarriers()` has,
  so a carrier payload's errors field is recognised on the grounding pass and the write target
  return-derives. Whether that is a third pass, a reordering of `resolveAll()`, or making
  `ErrorIndex` available earlier is the Spec's call; the `lookAheadMemo.clear()` at the end of
  `prepareForWalk` exists because grounding-time verdicts must not stick, and any reordering has
  to keep that property.
* Decide whether the scalar arm's "not yet supported; use ID or a @table type" wording can
  detect this case at all. Once the ordering is fixed it stops firing for this shape, but the
  same message fires whenever a payload fails to ground for any reason, and it names the return
  type rather than the missing write target. `validateReturnType` already has precedent for
  a targeted diagnostic here in `diagnoseForbiddenCarrierDirective`.

## Workaround for consumers today

Name the write table on the field. This resolves the target from `@mutation(table:)` instead
of the return type, and the payload, the errors channel and the `@table`-element data field
then all classify normally.

```graphql
oppdaterFeideApplikasjonDetaljer(
  input: [OppdaterFeideApplikasjonDetaljerInput!]!
): OppdaterFeideApplikasjonDetaljerPayload
  @mutation(typeName: UPDATE, table: "feide_applikasjon")
```

## Acceptance sketch

Pipeline-tier coverage that the consumer shape classifies without `@mutation(table:)`: a
carrier payload with an errors field on both INSERT and UPDATE, single and bulk input,
landing on `MutationDmlRecordField` / `MutationBulkDmlRecordField` with the error channel
present. `SingleRecordPayloadPipelineTest` currently passes `tableArg = true` for every
errors-bearing fixture, and its `mutationDirective` javadoc states the workaround as though
it were the rule; those fixtures are the natural home and the javadoc needs correcting with
them.
