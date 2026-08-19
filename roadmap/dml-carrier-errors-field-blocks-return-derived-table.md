---
id: R687
title: "A DML carrier payload with an errors field loses its return-derived write target"
status: Spec
bucket: structural
priority: 5
theme: mutation-write
depends-on: []
created: 2026-08-17
last-updated: 2026-08-19
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

The sharper framing, for the next author deciding what class of change is dangerous here:
`resolveDmlWriteTableRef`'s javadoc claims it is phase-portable ("reads only SDL directives,
the catalog … all available before the classification walk") and that single-sourcing the
precedence means the grounded `DmlEmitted` and the classified write target "cannot diverge".
This bug falsifies both: the scan behind rung 1 reads the phase-varying `ctx.errors`, so one
pure-looking function returns two different answers depending on when it is called, and the
two call sites did diverge, with no duplicated copy anywhere. The root defect,
`ErrorIndex.EMPTY` conflating "no `@error` types declared" with "index not built yet" so a
pre-index reader gets a plausible wrong answer instead of a refusal, has now bitten twice
(routine family, then DML); the enforcer for it is filed separately as R689, and this item
corrects the falsified javadoc alongside the ordering move.

## What the report got right, and what it got wrong

The reporter concluded the gap was UPDATE-specific, because an identical wrapper payload on
`@mutation(typeName: INSERT)` passed validation in the same build. Reproduced against trunk,
the failure is verb-agnostic: INSERT and UPDATE both reject with the same message, and both
classify clean once the payload's `errors` field is removed or `@mutation(table:)` is
supplied. The verb asymmetry the reporter observed is therefore unexplained by this account,
which means a second difference in their schema that it does not cover. "Verb-asymmetry chase"
below bounds how far to pursue that.

## Scope

* Give the DML mutation grounding the same post-index ordering `groundRoutineCarriers()` has,
  so a carrier payload's errors field is recognised on the grounding pass and the write target
  return-derives. The Design section settles this as a fold into the existing post-index pass
  rather than a third pass or an earlier `ErrorIndex`. The binding constraint either way: the
  `lookAheadMemo.clear()` at the end of `prepareForWalk` exists because grounding-time verdicts
  must not stick, and the reordering has to keep that property.
* Replace the scalar arm's "not yet supported; use ID or a @table type" wording for the carrier
  shapes it misdescribes. Once the ordering is fixed that message stops firing for this shape,
  but it still fires whenever a payload fails to ground for any reason, and it names the return
  type rather than the missing write target. `validateReturnType` already has precedent for
  a targeted diagnostic here in `diagnoseForbiddenCarrierDirective`; the Design section takes
  the fact from the recognizer instead of re-deriving it in the validator.

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
the post-index grounding pass `RecordBindingResolver.groundRoutineCarriers()` already runs
after `TypeBuilder.buildClassificationIndices()` has built the `ErrorIndex`. Fold the two
into one pass, named for the *precondition* rather than the family set (something in the
shape of "grounding that requires the classification indices"): the property the pass
asserts, these grounders read the `ErrorIndex`, is a single fact, and two methods would make
it two prose claims that must agree. Do not name it `groundEmittedCarriers`: that overclaims
against the closed three-arm `EmittedCarrierBinding` set, since `ServiceEmitted` grounds
elsewhere, and a reader checking "do all emitted-carrier groundings run post-index?" would
find two of three. Inside the pass, keep the DML and routine walks as two sequential loops
(DML first, matching today's total order) rather than two calls per field: interleaving
would change the order in which `dmlEmittedMemo` and `routineEmittedMemo` become visible to
`carrierBinding` through `lookAheadVerdict` for a payload reachable from both a `@mutation`
and a `@routine` field. `TypeBuilder.prepareForWalk`'s comment above the call updates to
name both families.

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
  describes.

The "grounding verdicts must not stick" property currently hangs on one trailing
`lookAheadMemo.clear()` at the end of `prepareForWalk`, which any pass added after that
line silently defeats, and which today leaves mid-preparation entries live *between*
grounding passes (a verdict computed during `resolveAll`'s grounding is still memoized when
the post-index pass probes). Gate the write instead of clearing after the fact:
`lookAheadVerdict` consults the memo but does not populate it until a fixed-point flag is
set at the end of `prepareForWalk`. Preparation-time probes then cannot poison the memo at
all, the `clear()` retires, and a future pass inserted anywhere in `prepareForWalk`
inherits the property instead of having to know about it.

One expected consequence of the gate, so it does not read as a regression at implementation:
the entries the `clear()` discards today are the grounding passes' own. Both grounding passes
probe `lookAheadVerdict` transitively, through `BuildContext.scanStructuralPayload`'s element
lookup behind `scanStructuralDmlPayload` / `scanStructuralRoutineCarrierPayload`, and every
verdict they compute currently sticks in the memo until the trailing `clear()` drops it. Under
the gate those probes stop populating it, and the first post-walk reader of each type recomputes
once. Net behaviour is identical, since a populated-then-cleared memo has no observable effect;
the cost is one recompute per type that only a grounding probe had touched.

Nothing else inside `prepareForWalk` is affected, which is worth stating because it is the
natural place to look for fallout: the two passes between the grounding call and the `clear()`
(the directive-ignored warning loop `emitDirectiveIgnoredWarning`, and
`surfaceMultiProducerRejections`) never reach `lookAheadVerdict` at all. They read the binding
fixed point, SDL directives and the registry, so the gate is invisible to them.

The `ServiceEmitted` family stays in `groundRootProducers()`, deliberately. Its carrier
detection never reads the `ErrorIndex` (`groundServicePayloadBinding` and
`singleNonTableObjectDataField` exclude errors-shaped fields structurally, via the
must-be-a-GraphQL-Object check), and `groundServiceField` also grounds result- and
input-axis observations that must feed the fold, so it cannot move wholesale and has no bug
forcing a split. The renamed pass's javadoc records this asymmetry so the next reader doesn't
"complete" the migration by moving it.

### Diagnostic: publish the ungrounded-carrier fact once, in the recognizer

`MutationInputResolver.validateReturnType`'s `ScalarReturnType` arm currently distinguishes
two carrier-adjacent cases before falling to the generic "not yet supported; use ID or a
@table type" text: a scan `Reject` (surfaces the scan's reason) and
`diagnoseForbiddenCarrierDirective`. The missing case is the structurally well-formed
carrier that never earned a carrier verdict because no producer grounded it. Do not detect
it with a fourth `scanStructuralDmlPayload` probe in the validator: `TypeBuilder.carrierBinding`
already computes exactly this fact (scan `Admit` + empty `dmlEmittedBinding` is its
`NotACarrier` definition for a structurally valid payload), and a validator-side re-spelling
of the same predicate is the two-consumers-one-derivation smell.

Instead, widen the recognizer so it publishes *why*: split an ungrounded-carrier arm out of
`CarrierBinding.NotACarrier` (carrying the admitting scan's result, see "what the arm carries"
below), and have `validateReturnType`'s scalar arm switch over that instead of re-scanning.

Split it *inside* `NotACarrier`, not beside it. Make `NotACarrier` a sealed interface over two
arms (a plain arm, and an ungrounded-carrier arm carrying the scan result) rather than adding
a fourth sibling to `CarrierBinding`. Every reader of the coarse question still wants the
answer "not a producer-backed carrier" for an ungrounded carrier, and only one of the five
existing read sites is a switch the compiler would force:

* `TypeBuilder.carrierVerdict` (both its `instanceof` early return and the exhaustive `switch`
  below it): a `null` verdict either way. The `switch` is the sole compile-time break.
* `TypeBuilder.isDirectivelessNestingTarget`: an ungrounded carrier must stay a nesting target,
  as today.
* `FieldBuilder.scanServiceCarrierShape`: must keep yielding `ServiceCarrierShape.NotApplicable`
  rather than computing a shape verdict for an unbound payload.
* `FieldBuilder`'s orphan-`@service`-carrier arm (the `carrierBinding(...) instanceof NotACarrier`
  + `scanStructuralServiceCarrierPayload` Admit guard behind `orphanServiceCarrierReason`): must
  keep firing, or that diagnostic silently regresses to the generic dangling-type-reference
  cascade.

A sibling arm would compile clean at all four of those `instanceof` sites while flipping three
of them, so the enforcement is the sealed subtype keeping `instanceof NotACarrier` true, plus
the one forced `switch` edit. Do not plan on the compiler enumerating the sites.

That orphan-`@service` arm is the same predicate this section argues against re-spelling, one
family over: it probes a scan in the classifier because the recognizer publishes no reason.
Folding it onto the new arm is deliberately out of scope here and filed as R725: the three scans
disagree by construction (they differ on forbidden data-field directives), so for a `@service`
payload the DML scan can admit first and this arm's admitting-scan field would name the wrong
family for that site. Subsuming it needs a decision about how the recognizer publishes per-family
facts, not a mechanical edit, and it changes a live `@service` diagnostic that carries its own pins.

#### What the arm carries

Two components, because the arm is reachable from any of the three scans:

* **The whole `DmlPayloadScan.Admit`**, not a bare `DmlElementKind`. `IdElement` is a
  no-component record and `Table` carries only the element type name, so neither can name the
  offending data field, which the `IdElement` wording below wants; `Admit.dataField()` is the
  only source for it.
* **Which scan admitted.** More than one scan can admit the same payload, so the recorded family
  is the first that admits in `carrierBinding`'s existing DML → routine → `@service` order; that
  is the order the method already runs, so it needs no rule of its own. The DML seat below forks
  wording only for a DML-scan admit (the seat's precondition, a non-`Reject` DML scan, makes that
  the live case) and otherwise keeps the generic write-target message.

The wording forks on the element kind, because the three populations need different advice.
For each population this arm is the only diagnostic *when the payload does not ground*, which
post-fix means only when `@mutation(table:)` is also absent. The per-verb classifiers' existing
record-element / ID-element rejections stay live and stay reachable: `groundDmlMutationField`
gates on the payload being a non-`@table` SDL Object, never on its element kind, so a
record-element or ID-element payload that names its table on `@mutation(table:)` still grounds,
still classifies as a `ResultReturnType`, and still hits those rejections. Do not read them as
newly dead code.

* `Table` element: no write target was grounded; name it on `@mutation(table:)`, which is the
  usual fix. State it that way rather than asserting the target is missing, for the reason below
  (and where the shared precedence returned `WriteTableRef.UnknownTable`, the existing
  unknown-table wording already fires at the classify-time resolvers).
* `RecordElement`: needs an `@service` producer, not a table name.
* `IdElement` on INSERT / UPDATE: the PK-echo permit is DELETE-only; a table name does not
  fix it.

The `Table` bucket is the one that does not map cleanly to a single cause, so word it with that
in mind. `groundDmlMutationField` silently skips for several reasons past the write-target
lookup, and at least one of them leaves a payload whose data field is `@table`-element and whose
table resolved fine: an unloadable jOOQ record class (its `Class.forName` arm). That payload
arrives at this seat indistinguishable from a genuinely target-less one, because neither the
element kind nor the scan records why grounding stopped, and neither classify-time write-target
resolver reloads the record class, so nothing upstream catches it first. "Name the table on
`@mutation(table:)`" is inert advice there: the table is already nameable.

The bullet above takes the cheap way out: cause-neutral wording that holds for both, costing
nothing. The better diagnostic is to have the arm carry *why* grounding stopped, which is the
same "publish the fact once, in the producer" move this section already makes one level up. That
is larger than this item and is R725's neighbour rather than its own obvious shape, so it belongs
in a filed item, not in this one. Either way the thing to avoid is wording that asserts a missing
write target on a bucket that has more than one cause.

### Verb-asymmetry chase (bounded)

The reporter observed INSERT passing where UPDATE failed on 10.0.0-RC31; trunk reproduces
the failure verb-agnostically. Bound the chase to: (1) the flipped pipeline fixtures below,
which pin both verbs classifying without `@mutation(table:)`; (2) one checkable hypothesis
against the reporter's schema alone: whether their INSERT payload's data field is
`@table`-typed at all. A record-element or ID data field makes the return-derived rung
structurally unavailable regardless of the `ErrorIndex`, a verb-independent difference that
looks verb-dependent when the two payloads differ in shape; a one-line look at their INSERT
SDL closes the chase either way. Other plausible second differences (an already-present
`@mutation(table:)`, a legacy input `@table` bridge) are noted for the reporter but not
chased. This clone carries no RC31 tag to diff, and the fix supersedes the question for
both verbs. No further work unless the fixed build still rejects their real schema.

## Implementation

* `RecordBindingResolver.groundRootProducers`: drop `groundDmlMutationField` from the field
  loop.
* `RecordBindingResolver.groundRoutineCarriers`: rename for the precondition (post-index
  grounding), add the DML loop ahead of the routine loop. The precondition name is also the
  accurate one for what the pass already holds: its routine loop runs `groundRoutineReturnType`
  beside `groundRoutineMutationField`, and a routine read field's return binding is not a carrier
  grounding, so no family-accurate name was available even before the DML loop arrives. Rework
  the javadoc to cover both families and to carry the deliberate `ServiceEmitted` exception (its
  result-axis half must feed the fold, and its carrier detection is `ErrorIndex`-free by
  construction), so the next reader doesn't "complete" the migration by moving it.
* `TypeBuilder.lookAheadVerdict` / `prepareForWalk`: replace the trailing
  `lookAheadMemo.clear()` with a memo write-gate on a fixed-point flag; update the pass
  comment above the grounding call.
* `TypeBuilder.carrierBinding`: split the ungrounded-carrier arm out of
  `CarrierBinding.NotACarrier`, as a sealed subtype so the coarse `instanceof NotACarrier`
  question keeps its answer. The arm carries the whole `DmlPayloadScan.Admit` plus which of the
  three scans admitted, per "What the arm carries" above. Walk all five read sites by hand (the
  four listed in the Design section plus `carrierVerdict`'s `switch`) and confirm each keeps
  today's behaviour; only the `switch` breaks at compile time.
* `MutationInputResolver.validateReturnType`: the scalar arm's new case switches over the
  recognizer's published fact, wording forked per element kind as above. The method is static
  over `(ReturnTypeRef, DmlKind, boolean, BuildContext)`, so it reaches the recognizer through
  `BuildContext.typeBuilder` rather than a signature change across its six call sites (all in
  `FieldBuilder`, two per verb: the `resolveInput` path and the inline path); that
  field is null for unit-tier harnesses, so null-guard it the way `BuildContext.lookAheadVerdict`
  and `FieldBuilder`'s binding accessors already do.
* Stale-comment sweep, in the same change (these are the comments the next reader will use
  to judge whether a pre-index reader is safe): the `resolveAll`-DML-grounding rationale
  comment at the end of `prepareForWalk`, the `lookAheadVerdict` javadoc's
  during-`prepareForWalk` paragraph, `groundRoutineMutationField`'s "the same instance the DML
  grounding above already exercises" sentence and its positional "above" (the sentence is on
  the per-field grounder, not on `groundRoutineCarriers`), `groundDmlMutationField`'s
  "result-axis observation" opener (it contradicts `dmlEmittedMemo`'s own dedicated-axis doc),
  and `resolveDmlWriteTableRef`'s "phase-portable … all available before the classification
  walk" claim, which this bug falsified: the scan reads the phase-varying `ctx.errors`, so the
  sentence must state the post-index precondition instead of asserting phase-portability.
* One more stale claim in the same javadoc block, found while reviewing: `groundDmlMutationField`'s
  precedence paraphrase two lines under its opener reads "`@mutation(table:)` preferred on a
  supported verb, else the input's `@table`". That is already false independently of this bug.
  Rung 1 is the return-derived table, and the input `@table` bridge is not a rung of
  `resolveDmlWriteTableRef` at all. It sits in the middle of the comments this sweep exists to
  fix, so correct it here rather than leaving it to the next reader. The same phantom rung is
  spelled a second time on `MutationInputResolver.RETURN_DERIVED_TABLE_VERBS` ("preferred over
  `@mutation(table:)` and the input `@table` bridge"); correct both spellings or the sweep leaves
  the claim standing where the next reader will look for it.

The deeper fix, a typed not-yet-built arm on the indices so a pre-index read refuses instead of
answering, is filed as R689 and out of scope here.

## Tests

Pipeline tier, in `SingleRecordPayloadPipelineTest`:

* Flip all three errors-bearing fixtures (`payload_singleInput_withErrorsField_…`,
  `payload_bulkInput_withErrorsField_…`,
  `payload_withErrorsField_emittedFetcher_dispatchesThroughLocalContextRouter`) from
  `tableArg = true` to the return-derived form, and correct their "does not return-derive"
  comments. These land on `MutationDmlRecordField` / `MutationBulkDmlRecordField` with the
  `LocalContext` error channel present, on INSERT and UPDATE, single and bulk input: the
  consumer shape from the report, classifying without `@mutation(table:)`.
* Those three are *every* errors-bearing `tableArg = true` fixture in the class (the only other
  two, at the `alsoFilms` and `description` payloads, are deliberately-broken carrier shapes),
  so flipping them leaves the errors-bearing population with no explicit-argument coverage at
  all. Add one new errors-bearing fixture holding `tableArg = true` rather than sparing one of
  the three: the explicit argument must keep working alongside an errors channel, and it is what
  the rung-1-vs-rung-2 same-table agreement path below is asserted on. Sparing one of the three
  instead would cost a verb or a cardinality from the regression pins, which is the coverage this
  item exists to add.
* The flipped fixtures above are the regression pins that carry this item's weight: pre-fix,
  an errors-bearing carrier with no `@mutation(table:)` resolves neither rung at grounding
  time, so no `DmlEmitted` is minted and the field rejects. They fail before the ordering
  move and pass after it.
* Optionally pin rung agreement on the added `tableArg = true` fixture: the grounded
  `DmlEmitted.tableRef` equals the classified write target. Note what this can and cannot
  see. Pre-fix the two call sites diverged in *provenance* (grounding fell to rung 2 while
  classification took rung 1), but on an agreeing fixture both rungs resolve the same
  `TableRef`, so a value-equality assertion passes pre-fix too; and a *disagreeing* fixture
  cannot be used, because the classify-phase resolvers' must-agree cross-check rejects it.
  So this is a cheap standing invariant pin on the single-sourced precedence, not a
  regression pin for this bug. Do not let it stand in for the flipped fixtures.
* Correct the `mutationDirective` helper's javadoc: of its two stated reasons for
  `tableArg`, only "an errors-shaped sibling present" becomes false; "a deliberately broken
  carrier shape" remains, so the fix is a split, not a deletion. The added fixture above needs
  the replacement clause, since it passes `tableArg` on a payload that now return-derives
  perfectly well: the third reason is covering the explicit argument itself.
* Pin each population of the new ungrounded-carrier diagnostic, since for a payload that does
  not ground it is the sole message for these shapes: a `Table`-element carrier that cannot
  ground (write-target steer), a record-element carrier without `@service` (producer steer),
  and an ID-element carrier on INSERT without `@mutation(table:)` (DELETE-only permit steer).
  None of them may fall through to "not yet supported". Pin the counterpart too, one fixture
  is enough: the same record-element payload *with* `@mutation(table:)` still grounds and still
  gets the per-verb record-element rejection, which is what keeps that live rejection from
  being deleted as dead code later.
