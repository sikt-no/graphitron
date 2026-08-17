---
id: R687
title: "A DML carrier payload with an errors field loses its return-derived write target"
status: Spec
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

## Design

The fix is the ordering treatment `groundRoutineCarriers()` already received, extended to
the DML family, plus a targeted diagnostic for the carrier shapes that still cannot ground
afterwards. Docs need no change: the user manual (`docs/manual/reference/directives/mutation.adoc`,
`table.adoc`, the tutorial) already documents return-derivation as the rule for INSERT /
UPDATE, so this item aligns the implementation with what the docs promise.

### Ordering: ground DML carriers after the classification indices

Move the `groundDmlMutationField` call out of `groundRootProducers()`'s per-field loop into
the post-index grounding pass. `RecordBindingResolver.groundRoutineCarriers()` already walks
every object's fields after `TypeBuilder.buildClassificationIndices()` has built the
`ErrorIndex`; fold the DML grounding into that same walk and rename the pass
`groundEmittedCarriers()` (it now grounds two of the three emitted-carrier families; the
javadoc carries the shared rationale, with per-family notes on `groundDmlMutationField` /
`groundRoutineMutationField`). `TypeBuilder.prepareForWalk`'s comment above the call updates
to name both families.

Why the move is safe, to be re-verified at implementation:

* `dmlEmittedMemo` is a dedicated axis. `resolveAll()`'s propagate / fold steps never read
  it (documented on `RecordBindingResolver.resolveDmlEmitted`), so grounding later cannot
  change any fold outcome.
* Nothing between `resolveAll()` and the current `groundRoutineCarriers()` call reads
  `resolveDmlEmitted`: the `recordBackingClasses` pump reads the result / input axes only,
  and `buildClassificationIndices` drives membership off `classifyType`, which never
  consults `dmlEmittedBinding` / `carrierVerdict`.
* The grounding's structural-scan probe of `TypeBuilder.lookAheadVerdict` becomes a
  post-fixed-point probe, the same sanctioned pattern `groundRoutineMutationField`'s javadoc
  describes. The `lookAheadMemo.clear()` at the end of `prepareForWalk` stays: verdicts
  computed during preparation still must not stick, and the moved pass runs before the
  clear, exactly as the routine pass does today.

The `ServiceEmitted` family stays in `groundRootProducers()`, deliberately. Its carrier
detection never reads the `ErrorIndex` (`groundServicePayloadBinding` and
`singleNonTableObjectDataField` exclude errors-shaped fields structurally, via the
must-be-a-GraphQL-Object check), and `groundServiceField` also grounds result- and
input-axis observations that must feed the fold, so it cannot move wholesale and has no bug
forcing a split. The `groundEmittedCarriers` javadoc records this asymmetry so the next
reader doesn't "complete" the migration by moving it.

### Diagnostic: name the missing write target when a well-formed carrier fails to ground

`MutationInputResolver.validateReturnType`'s `ScalarReturnType` arm currently distinguishes
two carrier-adjacent cases before falling to the generic "not yet supported; use ID or a
@table type" text: a scan `Reject` (surfaces the scan's reason) and
`diagnoseForbiddenCarrierDirective`. Add the third case: `scanStructuralDmlPayload` returns
`Admit`, i.e. the payload is a structurally well-formed carrier that never earned a carrier
verdict because no producer grounded it. Emit a message that names the actual gap, the write
target, rather than the return type: the payload is recognised as a carrier, its data field's
element (ID-element or record-element; a `@table`-element carrier return-derives once the
ordering fix lands) cannot name the write table, so name it with `@mutation(table:)`.
Tailor the sentence on `Admit.element()`. After the ordering fix this arm fires for
ID-element and record-element carriers on INSERT / UPDATE without `@mutation(table:)`, and
for residual silent-skip grounding cases (e.g. an unloadable record class); once grounding
succeeds the per-verb classifiers' existing element rejections take over, so the new text
only needs to steer toward the argument, not restate those rules.

### Verb-asymmetry chase (bounded)

The reporter observed INSERT passing where UPDATE failed on 10.0.0-RC31; trunk reproduces
the failure verb-agnostically. Bound the chase to: (1) the flipped pipeline fixtures below,
which pin both verbs classifying without `@mutation(table:)`; (2) a note to the reporter
that the asymmetry is unexplained against trunk and most plausibly a second difference in
their INSERT field's SDL (an already-present `@mutation(table:)`, or a legacy input
`@table` bridge). This clone carries no RC31 tag to diff, and the fix supersedes the
question for both verbs. No further work unless the fixed build still rejects their real
schema.

## Implementation

* `RecordBindingResolver.groundRootProducers`: drop `groundDmlMutationField` from the field
  loop.
* `RecordBindingResolver.groundRoutineCarriers` → `groundEmittedCarriers`: add the DML
  grounding to its walk; javadoc reworked to cover both families and the deliberate
  `ServiceEmitted` exception.
* `RecordBindingResolver.groundDmlMutationField` javadoc: the skip-cases paragraph gains the
  post-index timing note (mirroring `groundRoutineMutationField`'s probe paragraph).
* `TypeBuilder.prepareForWalk`: update the pass comment.
* `MutationInputResolver.validateReturnType`: the `Admit` diagnostic in the
  `ScalarReturnType` arm.

## Tests

Pipeline tier, in `SingleRecordPayloadPipelineTest`:

* Flip the errors-bearing fixtures (`payload_singleInput_withErrorsField_…`,
  `payload_bulkInput_withErrorsField_…`,
  `payload_withErrorsField_emittedFetcher_dispatchesThroughLocalContextRouter`) from
  `tableArg = true` to the return-derived form, and correct their "does not return-derive"
  comments. These land on `MutationDmlRecordField` / `MutationBulkDmlRecordField` with the
  `LocalContext` error channel present, on INSERT and UPDATE, single and bulk input: the
  consumer shape from the report, classifying without `@mutation(table:)`.
* Keep one errors-bearing fixture on `tableArg = true` (the explicit argument must keep
  working, and the rung-1-vs-rung-2 same-table agreement path stays covered).
* Correct the `mutationDirective` helper's javadoc, which states the workaround as the rule.
* New test pinning the `Admit`-arm diagnostic: an ID-element carrier with an errors field on
  INSERT without `@mutation(table:)` rejects with the new write-target message, not
  "not yet supported".
