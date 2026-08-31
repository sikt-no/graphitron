---
id: R687
title: "A DML carrier payload with an errors field loses its return-derived write target"
status: Ready
bucket: structural
priority: 5
theme: mutation-write
depends-on: []
created: 2026-08-17
last-updated: 2026-08-31
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
`CarrierBinding.NotACarrier` (carrying the DML scan's decoded facts, see "what the arm carries"
below), and have `validateReturnType`'s scalar arm switch over that instead of re-scanning.

Split it *inside* `NotACarrier`, not beside it. Make `NotACarrier` a sealed interface over two
arms (a plain arm, and an ungrounded-DML-carrier arm carrying decoded scan facts) rather than adding
a fourth sibling to `CarrierBinding`. Every reader of the coarse question still wants the
answer "not a producer-backed carrier" for an ungrounded carrier, and none of the five
existing read sites is an edit the compiler forces:

* `TypeBuilder.carrierVerdict` (both its `instanceof` early return and the exhaustive `switch`
  below it): a `null` verdict either way. The `switch` arm is a type pattern on `NotACarrier`
  itself (`case CarrierBinding.NotACarrier ignored -> null`), which under the split matches the
  whole sealed subtree, so the switch stays exhaustive and compiles untouched.
* `TypeBuilder.isDirectivelessNestingTarget`: an ungrounded carrier must stay a nesting target,
  as today.
* `FieldBuilder.scanServiceCarrierShape`: must keep yielding `ServiceCarrierShape.NotApplicable`
  rather than computing a shape verdict for an unbound payload.
* `FieldBuilder`'s orphan-`@service`-carrier arm (the `carrierBinding(...) instanceof NotACarrier`
  + `scanStructuralServiceCarrierPayload` Admit guard behind `orphanServiceCarrierReason`): must
  keep firing, or that diagnostic silently regresses to the generic dangling-type-reference
  cascade.

A sibling arm would compile clean at all four of those `instanceof` sites while flipping three
of them; the nested split keeps `instanceof NotACarrier` true at every one, and no read site
breaks at compile time under it either, `carrierVerdict`'s `switch` included (see above). The
one forced edit is the construction site: the single `new CarrierBinding.NotACarrier()` in
`TypeBuilder.carrierBinding` is the sole producer of the value, so sealing `NotACarrier` breaks
exactly there and makes the author choose an arm at the point where the fact is made. The
hand-walk of the five read sites is therefore the whole of the read-side enforcement, not a
supplement to a compiler signal. Do not plan on the compiler enumerating the sites.

That orphan-`@service` arm is the same predicate this section argues against re-spelling, one
family over: it probes a scan in the classifier because the recognizer publishes no reason.
Folding it onto the new arm is deliberately out of scope here and filed as R725: the arm this
item mints is DML-specific by construction, the `@service` diagnostic needs a service-family
fact, and the three scans disagree by construction (they differ on forbidden data-field
directives), so subsuming it needs the recognizer to publish per-family facts, not a mechanical
edit, and it changes a live `@service` diagnostic that carries its own pins.

#### What the arm carries

Two decoded components, both taken from the *DML* scan's `Admit` at the construction site: the
data field's name (a `String`), and the `DmlElementKind`. The element arms disagree on whether
they can name the offending data field (`RecordElement(String fieldName)` can,
`Table(TableRef, String elementTypeName)` names the element type instead, and `IdElement()` is
a no-component record), so the name is read off `Admit.dataField()`, the one source that holds
for all three populations, and decoded to the field *name* at construction rather than
republishing the `Admit` itself: `Admit` carries a live `GraphQLFieldDefinition`, and a
published recognizer fact should hand its consumers neither a graphql-java handle to reach into
nor the scan's own result vocabulary, which stays the recognizer's gathering detail.

Mint the arm only when the DML scan admitted. `carrierBinding` runs the scans in DML → routine
→ `@service` order and its DML arm tests the `Admit` without binding it (only the `@service`
arm binds today), so add the pattern variable and carry it to the fall-through construction:
a bind, not a new derivation. A payload
only the routine or `@service` scan admits stays the plain arm, which keeps the generic
write-target message at the DML seat, the behaviour that seat wants anyway (its precondition is
a non-`Reject` DML scan). This is also what keeps the arm out of R725's way: a first-wins
"which scan admitted" slot would depend on evaluation order, would name the wrong family at the
orphan-`@service` site, and would need the private `CarrierFamily` enum widened into the
published fact. A DML-specific arm is R725's per-family publication realized for the one family
this item needs, so R725 extends it rather than retires it.

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
  question keeps its answer. The arm carries the DML scan's decoded facts (the data field's
  name and its `DmlElementKind`) and is minted only when the DML scan admitted, per "What the
  arm carries" above; the DML arm's tested `Admit` reaches the fall-through construction as a
  pattern-variable bind. The only compile-time break is the construction site, the single
  `new CarrierBinding.NotACarrier()` in this method, where the arm gets chosen; no read site
  breaks (`carrierVerdict`'s `switch` arm is a type pattern on `NotACarrier` and stays
  exhaustive over the subtree). So walk all five read sites by hand (the four listed in the
  Design section plus `carrierVerdict`'s `switch`) and confirm each keeps today's behaviour;
  that walk is the whole of the read-side verification.
* `MutationInputResolver.validateReturnType`: the scalar arm's new case switches over the
  recognizer's published fact, wording forked per element kind as above. The method is static
  over `(ReturnTypeRef, DmlKind, boolean, BuildContext)`, so it reaches the recognizer through
  `BuildContext.typeBuilder` rather than a signature change across its six call sites (all in
  `FieldBuilder`, two per verb: the `resolveInput` path and the inline path); that
  field is null for unit-tier harnesses, so null-guard it the way `BuildContext.lookAheadVerdict`
  and `FieldBuilder`'s binding accessors already do. Name the guard's fall-through: a null
  `typeBuilder` keeps today's generic message. Only unit-tier harnesses can observe that branch,
  since every production path wires the builder before classification starts
  (`lookAheadVerdict`'s javadoc already states this), and the pipeline fixtures below are the
  enforcement that the new diagnostic is live on the shipped path.
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
  fix, so correct it here rather than leaving it to the next reader.

  The same phantom rung is spelled in two forms, a prose form naming the bridge as a precedence
  entry and a rung-number form ("rung-1-vs-rung-3"), and three consecutive review rounds each
  found the previous round's site list one short, so the sweep's instruction is an invariant and
  a grep, not a closed list. The invariant: `resolveDmlWriteTableRef` and
  `FieldBuilder.resolveReturnCapableWriteTarget` each have exactly two rungs (the return-derived
  table, then `@mutation(table:)`), and the must-agree cross-check sits between rung 1 and
  rung 2, where `resolveReturnCapableWriteTarget`'s javadoc, the authority on the lattice,
  locates it. No comment may name a third rung, an input-`@table` rung, or a rung-1-vs-rung-3
  match. Sweep the main sources with three greps: `rung-1-vs-rung-3`, `bridge`, `input's`. The
  first is precise; the other two over-match on live uses (the `@table` input-shape bridge that
  produces a `TableInputArg`, as in the `@lookupKey` derivations, and
  `groundDmlMutationField`'s cardinality comment naming the bridge-shaped *input*), so each hit
  is a candidate to judge against the invariant, not an entry on a to-fix list: a comment
  describing what the bridge is stays, a comment placing it in the write-target precedence goes.

  What the greps find as of this writing, as worked examples, not the measure of done (anchor
  on the symbols; line numbers have already staled twice in review):

  * `RecordBindingResolver.groundDmlMutationField`'s javadoc, above (its paraphrase also omits
    rung 1 entirely).
  * `MutationInputResolver.RETURN_DERIVED_TABLE_VERBS`'s javadoc ("preferred over
    `@mutation(table:)` and the input `@table` bridge").
  * `FieldBuilder.classifyMutationField`'s INSERT-dispatch comment ("return-derived rung
    preferred, then `@mutation(table:)`, then the deprecated input `@table` bridge").
  * `FieldBuilder.classifyUpdateTableField`'s javadoc, same three-rung paraphrase.
  * `FieldBuilder.resolveUpdateWriteTarget`'s javadoc, same three-rung paraphrase.
  * `FieldBuilder.classifyDeleteTableField`'s javadoc, which spells the bridge as DELETE's
    second rung ("`@mutation(table:)`, then the input's `@table`"). DELETE has one rung.
  * `FieldBuilder.classifyUpdatePayloadField`, a body comment: "the rung-1-vs-rung-3 table
    match (the payload's `@table`-element table vs the input's deprecated `@table`) is owned by
    `resolveUpdateWriteTarget`". The deferral is sound (the check runs in the shared
    `resolveReturnCapableWriteTarget` behind that call); the rung pair is the wrong part.
  * `FieldBuilder.classifyInsertPayloadField`'s javadoc: "`resolveInsertWriteTarget`, which
    owns the rung-1-vs-rung-3 table-match check".
  * A body comment inside that same `classifyInsertPayloadField` ("resolve the write target
    (and the rung-1-vs-rung-3 table-match)").

  All nine describe paths that bottom out in `resolveDmlWriteTableRef`, directly or through
  `FieldBuilder.resolveReturnCapableWriteTarget`, and neither of those has a third rung.

  The fix at each defective site is subtraction, not a corrected paraphrase. Nine corrected
  copies of a two-rung enumeration would still be nine hand-maintained spellings of one fact
  with no enforcer, which is the drift that has already cost three review rounds; a
  copy-one-template remedy dies of the disease it treats. Instead the rung enumeration lives
  once, on its owners: `resolveDmlWriteTableRef`'s javadoc states the precedence it implements,
  and `resolveReturnCapableWriteTarget`'s javadoc states the cross-check against it. Every
  other site defers. A javadoc site drops its rung list and points with `{@link}` at the
  resolver it actually calls, which the Javadoc reference gate keeps live; a body comment names
  the resolver in prose and enumerates nothing. `resolveInsertWriteTarget`'s javadoc, correct
  today, is still a copy: strike its rung list too and let its existing
  `{@link #resolveReturnCapableWriteTarget}` carry the deferral.

  One of the owners needs a correction of its own. `resolveReturnCapableWriteTarget`'s step-3
  block re-derives rung 1 inline (`scanStructuralDmlPayload` + `DmlElementKind.Table`), a
  second spelling of `MutationInputResolver.resolveReturnDerivedTable`'s carrier branch,
  sitting directly under comments this sweep rewrites to assert single-sourcing. Route that
  rung-1 read through `resolveReturnDerivedTable`: the method is static, package-visible, and
  takes the `fieldDef` the caller already holds, and the step-3 comment itself documents the
  inline read as "equal to the helper's return-derived rung"; verify that equivalence at
  implementation (both reads run post-index once the ordering move lands). If a blocker turns
  up, the fallback is honesty over silence: the comment must state that the cross-check
  re-reads rung 1 off the classified return type, rather than implying one producer.

  Done means: rerun the three greps, and every remaining hit is either an owner's javadoc or a
  live-bridge description; no comment outside the owners enumerates the rungs a `{@link}` could
  defer to.

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
* Pin rung agreement on the added `tableArg = true` fixture: the grounded
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

## Reviewer findings

**Spec -> Ready, 2026-08-24: revisions, two findings.** Both are against question 1: the plan makes
checkable claims about code that exists, and two of them do not hold. The diagnosis, the ordering
move, the memo write-gate and the recognizer-publishes-the-fact design all check out against the
tree, so question 2 is answered; the findings are in the Implementation section, which is the part
an implementer executes literally.

*What was checked.* Every symbol the spec names exists under the name it gives. The causal chain
reproduces by reading: `ctx.errors` is initialised to `ErrorIndex.EMPTY` (`BuildContext:211`),
`detectErrorsFieldShape` resolves each union member through `errors.forName` and returns null when
the index is empty (`BuildContext:1113`), `scanStructuralPayload` then falls past its errors `continue`
into the data-field dispatch and counts the field as a second data channel, and
`prepareForWalk` calls `bindings.resolveAll()` at line 220 against `buildClassificationIndices()` at
232 and `groundRoutineCarriers()` at 236, with the trailing `lookAheadMemo.clear()` at 251. The
safety argument for the move holds at every step: `groundDmlMutationField` writes nothing but
`dmlEmittedMemo.putIfAbsent`, `dmlEmittedBinding` is read only from `TypeBuilder.carrierBinding` and
field-classify time, the `recordBackingClasses` pump reads `resolveResult` / `resolveInput` only,
`buildClassificationIndices` drives membership off `classifyType`, and neither
`emitDirectiveIgnoredWarning` nor `surfaceMultiProducerRejections` reaches `lookAheadVerdict`.
`scanStructuralPayload` does probe `lookAheadVerdict` directly at `BuildContext:1020`, so the
grounding passes are transitive probes as stated. The scalar arm of `validateReturnType` carries
exactly the two carrier-adjacent cases the spec describes before the quoted generic message, and its
six call sites are all in `FieldBuilder`, two per verb. `DmlPayloadScan.Admit` and the three
`DmlElementKind` arms have the shapes the "what the arm carries" section reasons from. All five
`NotACarrier`-sensitive read sites exist and behave as described, `orphanServiceCarrierReason`
included. In the test class, the three named fixtures exist, all three pass `tableArg = true`, and
they are indeed every errors-bearing `tableArg = true` fixture, the other two being the `alsoFilms`
and `description` broken shapes. The manual documents return-derivation with two rungs and no
errors-field carve-out, so "docs need no change" holds.

*Finding 1: the split's compile-time enforcement does not exist as described.* The Design section
says the enforcement is "the sealed subtype keeping `instanceof NotACarrier` true, plus the one
forced `switch` edit", and Implementation says "only the `switch` breaks at compile time". It does
not break. `TypeBuilder.carrierVerdict`'s switch reads `case CarrierBinding.NotACarrier ignored ->
null;`, a type pattern on the third of `CarrierBinding`'s three permitted subtypes. Turning
`NotACarrier` into a sealed interface over two arms leaves that pattern matching the whole subtree,
so the switch stays exhaustive and compiles untouched. The one place that stops compiling is the
single construction site, `new CarrierBinding.NotACarrier()` at `TypeBuilder:388`, which is the only
producer of the value and therefore forces the author to choose an arm exactly where the fact is
made. That is better enforcement than the claimed one, but the plan as written sends the implementer
looking for a compiler signal that never arrives, and anchors its "walk all five read sites by hand"
instruction on a break that will not happen.

What would satisfy it: state the construction site as the forced edit and say plainly that no read
site breaks, so the hand-walk of all five is the whole of the enforcement rather than a supplement to
a switch error. The nested-over-sibling choice itself survives unchanged; it is the enforcement
sentence that is wrong, not the design.

*Finding 2: the phantom-rung sweep still under-enumerates, and the prescribed grep cannot find the
remainder.* The Implementation section pins the count at six sites and prescribes driving the sweep
off "a grep for the bridge". Two further sites spell the same phantom rung, and neither contains the
word "bridge":

* `FieldBuilder.java:5768`, in `classifyUpdatePayloadField`: "the rung-1-vs-rung-3 table match (the
  payload's `@table`-element table vs the input's deprecated `@table`) is owned by
  `resolveUpdateWriteTarget`".
* `FieldBuilder.java:6335`, javadoc on `classifyInsertPayloadField`: "`resolveInsertWriteTarget`,
  which owns the rung-1-vs-rung-3 table-match check".

Both name a rung 3 that `resolveDmlWriteTableRef` does not have, and both attribute a
rung-1-vs-rung-3 cross-check to a resolver whose own javadoc states the check correctly:
`resolveReturnCapableWriteTarget` locates it at rung 1 against rung 2. They are the sharper version
of the same defect the six carry, because a rung number is a harder claim than the prose form, and
they sit on the two payload classifiers an implementer of this item reads anyway.

This is the second consecutive round on this enumeration, which is the argument for changing its
form rather than its number. What would satisfy it: state the invariant the sweep enforces
(`resolveDmlWriteTableRef` has two rungs, so no comment may name a third or an input-`@table` rung),
give a grep that reaches every spelling of it (`rung.3`, `bridge`, `input's {@code @table}`), and
keep the site list as worked examples of what the grep finds rather than as the closed set the
implementer is measured against. `resolveInsertWriteTarget`'s javadoc stays the wording template.

**Spec -> Ready, 2026-08-25: revisions, the round above is unanswered and its second finding
under-enumerates by one.** Question 1 again, for the same reason: the plan body has not changed
since the round above was appended, so both of that round's findings stand exactly as filed. No
commit anywhere in the repo touches this file after the one that appended them, and the Design
section still reads "plus the one forced `switch` edit" while Implementation still reads "only the
`switch` breaks at compile time" and "spelled at six sites, not two ... driven off a grep for the
bridge". Nothing here reopens what that round settled; this round exists to correct one count in it
so the next revision closes both findings in one pass instead of a fourth.

*Finding 1 re-verified, nothing to add.* `CarrierBinding` permits three subtypes
(`TypeBuilder:348`), `NotACarrier` is a component-less record (`:354`), `carrierVerdict`'s switch arm
is the type pattern `case CarrierBinding.NotACarrier ignored -> null` (`:410`), and `:388` is the
sole `new CarrierBinding.NotACarrier()` in the tree. Sealing `NotACarrier` over two arms leaves that
pattern matching the whole subtree, so the switch stays exhaustive; the construction site is the
only forced edit. The prior round's remedy is the right one.

*Finding 3: three sites carry the rung-number spelling, not two, and all three predate the round
that named two.* The round above cites `FieldBuilder:5768` and `:6335`. At its own commit the file
carried three `rung-1-vs-rung-3` occurrences, at 5754, 6321 and 6353, and it carries the same three
today:

* `FieldBuilder:5755`, a body comment in `classifyUpdatePayloadField`: "the rung-1-vs-rung-3 table
  match (the payload's `@table`-element table vs the input's deprecated `@table`) is owned by
  `resolveUpdateWriteTarget`".
* `FieldBuilder:6321`, the javadoc on `classifyInsertPayloadField`: "`resolveInsertWriteTarget`,
  which owns the rung-1-vs-rung-3 table-match check".
* `FieldBuilder:6353`, a body comment inside that same method: "resolve the write target (and the
  rung-1-vs-rung-3 table-match)".

Both citations are also fourteen lines off the real positions at that commit, so the site list was
stale in the round that filed it. That is the third consecutive round to miscount this one sweep,
which retires the list form rather than adjusting its number.

The full population is nine: the six prose-form sites the Implementation section already names
(`MutationInputResolver:134`, `RecordBindingResolver:611`, `FieldBuilder:5525`, `:5548`, `:5872`,
`:6179`, all confirmed present and all wrong) plus the three above. Three greps reach all nine:
`rung-1-vs-rung-3` finds the rung-number form, `bridge` finds four of the prose form, and `input's`
finds the remaining two. The last two over-match on unrelated live bridges
(`FieldBuilder:1844`, `:2104`, `TypeBuilder:1033`, `RecordBindingResolver:660`), which is the
argument for the invariant being the instruction and the hits being candidates to judge:
`resolveDmlWriteTableRef` and `FieldBuilder.resolveReturnCapableWriteTarget` each have exactly two
rungs, so no comment may name a third rung or an input-`@table` rung. `resolveInsertWriteTarget`'s
javadoc (`FieldBuilder:6210`) stays the wording template, and
`resolveReturnCapableWriteTarget`'s own javadoc is the authority the three rung-number sites
contradict: it enumerates rung 1 and rung 2 and locates the must-agree cross-check between them.

*Also checked, and holding.* The diagnosis and both design moves survive an independent read.
`prepareForWalk` still runs `resolveAll()` at 220, `buildClassificationIndices()` at 232,
`groundRoutineCarriers()` at 236 and the trailing `lookAheadMemo.clear()` at 251, with the comment
above 236 naming the ErrorIndex precondition the DML family never got.
`resolveDmlWriteTableRef` has the two rungs its javadoc enumerates and still asserts the
phase-portability this bug falsifies. `carrierBinding` runs the DML, routine and `@service` scans in
that order, so "the first that admits" needs no rule of its own, and its DML and routine arms
discard the `Admit` they test, so carrying it is a pattern-variable bind rather than a new
derivation. `BuildContext.typeBuilder` exists as a nullable field with the null-guard precedent the
plan points at (`BuildContext:276`). The three fixtures the Tests section flips exist under the
names it gives them. Question 2 is answered: the ordering move extends the treatment
`groundRoutineCarriers` already has, the write-gate turns a positional invariant into a structural
one, and the sealed split inside `NotACarrier` keeps one derivation with one publisher.

Status stays Spec.
