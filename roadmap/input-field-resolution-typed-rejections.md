---
id: R585
title: "Typed rejections on the input-field resolution path"
status: Ready
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-06
---

# Typed rejections on the input-field resolution path

`InputFieldResolution.Unresolved(String fieldName, String lookupColumn, String reason)` carries prose
where every sibling builder-step result carries a typed `Rejection`. `FieldRegistry` records the
consequence in a comment: "Unresolved carries no Rejection variant (the failure path doesn't produce an
UnclassifiedField; it's a transient resolution outcome consumed by the caller)". The development
principles put this the other way round under "Builder-step results are sealed, not strings or
out-params": rejection is "a typed variant with a stable LSP code, never a string or out-param", and
never "prose composed at the detection site". This path is the standing exception, and
`R58TypedRejectionPipelineTest` names it as one of five carriers whose widening is not yet done.

## The typed rejection usually already exists

The framing "give this path typed rejections" understates what is on the ground. At eight sites the
classifier **has a `Rejection` object in hand and calls `.message()` on it** because the carrier cannot
hold it. Five feed `Unresolved`:

| Site | Rejection discarded |
|---|---|
| `BuildContext`, id-reference shim, ambiguous FK | `ambiguousForeignKeyRejection(...)` |
| `BuildContext`, shim `FkJoinResolution.UnknownTable` | `unknownTableRejection(...)` |
| `BuildContext`, shim `FkJoinResolution.UnknownForeignKey` | `unknownForeignKeyRejection(...)` |
| `BuildContext`, shim target table unresolved | `unknownTableRejection(...)` |
| `BuildContext.inputFieldFromNodeIdResolved`, on `NodeIdLeafResolver.Resolved.Rejected` | the record's own `rejection()` component |

and three feed the sibling prose channel, `buildInputFieldCondition`'s `List<String> errors` out-param:
`ArgBindingMap.Result.UnknownArgRef.message()`, `ArgBindingMap.Result.PathRejected.message()`, and
`ServiceCatalog.reflectTableMethod`'s `result.rejection().message()`. The out-param is the second half
of the same defect, and the principles doc names it in the same breath as the string; it is not a
separate concern.

So the first slice is mostly subtraction. Delete eight `.message()` calls, widen one record component,
and eight causes recover an identity they already had at the point of detection.

Two producers forward another *untyped* carrier's prose rather than a rejection:
`ParsedPath.errorMessage` (the `@reference` path parse failure) and
`FieldBuilder.translatedFkRejectionReason`, a shared prose helper. `ParsedPath` is itself one of the
five carriers `R58TypedRejectionPipelineTest` lists, and its blast radius is far wider than this item
(eighteen-plus `hasError()` sites across `FieldBuilder`, `TypeBuilder`, `NodeIdLeafResolver`,
`SourceRowDirectiveResolver`, `BuildContext`), so it stays out of scope; see the boundary rule below.
The remaining producers compose prose at the detection site and need an arm chosen from the existing
`Rejection` inventory.

## Three fan-ins, two arbitrary firsts, and a doubled hint

The fold is not one site. Failures collapse at three levels, and the outer levels embed the inner
level's already-joined prose, so a nested defect renders as quoted strings inside quoted strings:

- `BuildContext.classifyInputFieldInternal`, nesting branch: k nested failures join into **one further
  `Unresolved`** whose reason is `"nested input type 'T' has unresolvable fields: ..."`.
- `InputFieldResolver.resolve`: joins with `"; "` into `Rejection.structural`, after a
  `canLiftToUnknownName` guard that reaches the typed arm only when there is exactly one column-miss
  failure and no competing `@condition` error. The guard is the shape of the problem, stated in code.
- `TypeBuilder.resolveInputFields`: joins into `InputFieldsResolution.Failed(String)`, re-wrapped as
  `Rejection.structural` at three `FieldBuilder` sites plus the resolver fold, each with its own prefix.

Two further sites drop failures outright rather than joining them. The `@table` write-target paths take
`GraphitronSchemaValidator.collectInputFieldRejections(inputFields).getFirst().rejection()`, discarding
every mirrored rejection after the first. And both prose folds compute their "did you mean" hint with
`findFirst()` over the failures' `lookupColumn`, so with two column misses only one gets a hint.

The hint machinery is doubled and disagrees with itself. `Rejection` has a private `candidateHint` that
`UnknownName.message()` renders; `BuildContext` has a second static `candidateHint` that the folds call.
Worse, the three sites disagree on the candidate space for the same defect: `InputFieldResolver` and
`TypeBuilder` pass `columnJavaNamesOf`, the nesting fold passes `columnSqlNamesOf`.
`columnJavaNamesOf`'s own javadoc settles it ("use this for error hints where the schema author is
expected to supply a Java field name, e.g. `@field(name: "FILM_ID")`"), which makes the nesting fold's
SQL names a slip rather than a considered difference. A single typed carrier renders the hint once, from
one implementation, over one candidate space.

## Adding a second defect removes the first one's type

The cheapest demonstration. `GraphitronSchemaBuilderTest` pins the single-failure case twice
(`plainInput_bareUnresolvedField_rejectsAsUnclassifiedFieldWithUnknownName` and its `@condition`
sibling): one unresolvable field on a plain input yields
`Rejection.AuthorError.UnknownName(attempt: "no_such_column", candidates: [...])`. Add a second
unresolvable field to that same input and `canLiftToUnknownName` goes false, both causes flatten into
one `Rejection.structural`, and the first field's typed shape is gone. The rejection gets *less*
structured as the schema gets *more* broken, which is exactly backwards: the author who needs machine
help most is the one who gets prose.

Location degrades the same way, and the asymmetry with the sibling path proves it is not inherent.
Column-miss was already migrated to the target shape: it lifts to `InputField.UnboundField`, which
retains a `location`, and `GraphitronSchemaValidator.validateInputUnboundField` reports it at that
location, per field. Every other cause stays `Unresolved`, which retains no location at all, so the
fold reports it at the *consuming* field's location. `BuildContext.classifyInputField` even receives the
input field's `SourceLocation` and hands it to `FieldRegistry.classifyInput` for the trace, then drops
it. Five broken input fields therefore produce one squiggle on the mutation field with five names inside
one string, where the already-migrated cause would have produced five squiggles in the right places.

## Design

**`Unresolved` carries a `Rejection` and a `SourceLocation`; `reason` and `lookupColumn` both go.**
`reason` is subsumed by `rejection.message()`. `lookupColumn` is subsumed by `UnknownName.attempt()`
under `AttemptKind.COLUMN`: its only four main-source readers are the `canLiftToUnknownName` guard and
the two `findFirst()` hint computations, all of which delete. The location is already in hand at every
producer.

**Boundary rule for causes whose upstream is still untyped.** At the `ParsedPath` site and the
`ConditionDirective.argMappingError` site, wrap the prose in a `Rejection` at the boundary rather than
widening the upstream carrier. The arm then says "this cause is not yet typed" in one identifiable place
instead of leaving `Unresolved` polymorphic between prose and type. This is the additive-then-cutover
discipline: `Unresolved`'s contract becomes total immediately, and typing `ParsedPath` is a separate
item that deletes one wrap.

**`translatedFk` is `Rejection.deferred`, not `Rejection.structural`.** The message says so itself
("This pathological case is deferred until output-side JOIN-with-projection emission ships"), and
`Rejection.Deferred` exists for exactly that, with `Diagnostics.severityOf` treating it as its own case.
Today the same helper produces a `structural` rejection at the `FieldBuilder` argument site and raw
prose at the `BuildContext` input-field site, so one cause has two identities and neither is the right
arm. Converging both on `deferred` fixes the identity and the classification in one move. Use the
no-`StubKey` factory: there is no stubbed variant class to anchor on at an input-field coordinate.

**The fan-in resolves as one located violation per failure, minted into `ctx.addDiagnostic`.** This is
the item's one real design decision, and the sibling item `validation-adds-facts` (R589) already fixes
the direction by doctrine: violations are facts, one per failure. The mechanism is the additive channel
that already ships. `BuildContext.diagnostics` is documented as accumulating findings "instead of
demoting a classified verdict", the validator drains it, and sixteen sites across
`GraphitronSchemaBuilder`, `TypeBuilder`, `FieldBuilder` and `ConnectionPromoter` already mint into it.
So no new `Rejection` composite arm and no new carrier: each failure becomes a `ValidationError` at the
input field's own location, and the three folds stop composing prose. The channel's javadoc frames it as
the *validate* phase's accumulator and will need widening to say classify-time minting is intended;
`TypeBuilder` and `FieldBuilder` already mint from build paths, and R589's slice 4 proposes the same
widening, so this is a documentation correction rather than a new liberty.

Two consequences to hold onto, because they are what make the mechanism work rather than merely look
tidy:

- **The consuming field keeps exactly one rejection, and it states the consequence, not the causes.**
  "Input type 'FilmInput' against table 'film': 2 fields could not be bound" belongs on the consuming
  coordinate; the two causes belong at the two input fields. An author sees three diagnostics where
  they saw one, which is the shape a compiler uses for "cannot instantiate" plus two member errors. A
  typed cascade arm carrying the count is deliberately deferred: it earns its keep only once the LSP
  renders related-information links, and `Structural` is honest for a consequence.
- **Per-field diagnostics must carry no consuming-coordinate prefix.** Input fields are resolved once
  per consuming field, never in a registry type walk, so one input type used by five mutations
  classifies five times. `ValidationError` is a record and `Rejection` arms are records, so a
  `distinct()` at the drain collapses repeats by structural equality, but only if the per-field
  rejection is built from the input field's own facts. This is the mechanism's best property: when the
  five uses resolve against the same table the fact is identical and dedup fires; when they resolve
  against different tables the candidates differ, the rejections are unequal, and both survive. Value
  equality dedups exactly when the fact is the same. Dedup is available only *because* the rejection is
  typed, which is why the location move and the typing are one change rather than two.

**Retired-directive convergence.** Route `@notGenerated` and `@lookupKey` through
`Rejection.directiveConflict` from all sites, so each cause has one identity carrying the directive
name. Today `@lookupKey` on a mutation input field has three spellings: the `FieldBuilder` and
`BuildContext` sentences agree, while `MutationInputResolver.rejectInputFieldDirectives` words it
differently ("remove it (the field is a filter by default)"), so no message-template heuristic fuses
them even now. Any consumer counting or clustering rejections by cause sees one cause as three.
`@multitableReference` needs no work; its retirement already routes through `directiveConflict` from a
single site, which is the target shape.

**Pin what `DirectiveConflict.directives` means,** since the convergence makes it load-bearing. Its
javadoc promises only "the bare directive names (no leading `@`) for downstream tooling", and the sites
do not agree: of eleven `directiveConflict` call sites, ten name directives present on the declaration,
while `FieldBuilder`'s `@asConnection`-on-an-inline-`TableField` site lists `splitQuery`, which is
*absent* and is the remedy. So the component is today a bag mixing causes with fixes. State the contract
as "every listed directive is present on the declaration" and pin it with a test; the anomalous site
then either drops `splitQuery` from the list and keeps "add `@splitQuery`" in its prose where it
belongs, or is declared an exception on purpose. Two independent lines of reasoning reach the same
verdict on that site: `mcp-aggregated-diagnostics` needs the contract to offer a per-directive count,
and R589's purity rule rejects a claim describing a counterfactual rather than the schema as authored.

## Implementation

- `InputFieldResolution`: `Unresolved(String fieldName, SourceLocation location, Rejection rejection)`.
  Update the record javadoc, which currently documents `lookupColumn` as the "did you mean" carrier.
- `BuildContext.classifyInputFieldInternal` and its two helpers (`inputFieldFromNodeIdResolved`,
  `buildInputNodeIdReference`): sixteen producers. Five drop a `.message()`; two retired-directive arms
  move to `directiveConflict`; one wraps `ParsedPath.errorMessage` at the boundary; one moves
  `translatedFk` to `deferred`; the rest pick an arm from the existing inventory (`unknownColumn` for
  the `@reference`-path column miss, `unknownTable` / `unknownForeignKey` for the shim arms,
  `structural` for the genuinely structural ones: repeated `@reference`, circular nesting, decode-helper
  and key-column resolution failures).
- `BuildContext.buildInputFieldCondition`: the `List<String> errors` out-param becomes a typed
  accumulator. Three of its four `errors.add` calls drop a `.message()`; the fourth
  (`cond.argMappingError()`) is prose from `ConditionDirective` and takes the boundary wrap.
- `BuildContext`, nesting branch: stop composing a further `Unresolved`; mint each nested failure and
  return a consequence rejection for the nesting field.
- `InputFieldResolver.resolve`: delete `canLiftToUnknownName` and the prose join; mint per failure. The
  `condErrors` buffer folds into the same accumulator.
- `TypeBuilder.resolveInputFields`: same; `InputFieldsResolution.Failed(String)` becomes
  `Failed(Rejection)`. Delete the `findFirst()` hint and the `BuildContext.candidateHint` call.
- `FieldBuilder`: the three `Failed`-to-`structural` re-wraps become prefix applications on a typed
  rejection (`Rejection.prefixedWith` already exists for this). The two
  `collectInputFieldRejections(...).getFirst()` sites mint the tail instead of dropping it.
- `MutationInputResolver.rejectInputFieldDirectives`: converge onto `directiveConflict`.
- `BuildContext.candidateHint`: delete if the folds prove to be its only callers; measure at pickup.

## Tests

- **Extend `R58TypedRejectionPipelineTest`** rather than starting a new file: its javadoc lists
  `InputFieldResolution.Unresolved.reason` as out of scope, and this item is what removes that line.
  The regression that matters is the one above: two unresolvable fields on one plain input yield two
  typed rejections, where today the second costs the first its type.
- **A partition test over the sixteen producers**, asserting each yields a non-`structural` arm except
  an explicitly declared allowlist (repeated `@reference`, circular nesting, the boundary wraps). The
  allowlist is the honest record of what remains untyped, and it shrinks as `ParsedPath` lands.
- **A `DirectiveConflict.directives` contract test**: every listed directive is applied at the
  rejection's declaration. Expected to fail on the `@asConnection` site until that site is settled.
- **A dedup test**: one plain input type consumed by two fields resolving against the same table yields
  one diagnostic per failing input field, not two; against different tables, two.
- **Location assertions** on the per-field diagnostics, since moving them off the consuming field is the
  author-visible half and nothing pins it today.
- Existing prose assertions churn in sixteen places across `GraphitronSchemaBuilderTest`,
  `NodeIdPipelineTest` and `MutationTableArgClassificationTest`. Most are `.contains(...)` on message
  text and survive if the typed arms render the same sentence, which the first slice should preserve
  deliberately; the ones asserting a *joined* message change with the fan-in.

## Sequencing

Two slices, because the seam is real: the first is unobservable to an author, the second is not.

1. **Type the carrier, preserve every message.** The record widening, the eight `.message()` deletions,
   the boundary wraps, the retired-directive convergence, the `deferred` correction, the out-param. The
   folds stay. Rendered text stays byte-identical where it can, so test churn is near zero and the diff
   reviews as pure identity recovery.
2. **Dissolve the folds.** Per-failure minting, the location move, dedup, the two `getFirst()` sites.
   This changes what the validator reports and how much of it, so it carries the churn and the
   acceptance fixtures.

Slice 1 is worth landing alone even if slice 2 slips: it is what `mcp-aggregated-diagnostics` needs, and
it is what makes slice 2's dedup possible.

## Out of scope

- **Typing `ParsedPath`.** Its own item; eighteen-plus consumers across four builders. This item wraps
  it at one boundary and deletes the wrap when that lands.
- **The other three carriers** `R58TypedRejectionPipelineTest` names
  (`ArgumentRef.ScalarArg.UnboundArg.reason`, `EnumValidation.Mismatch`, `keyColumnErrors`).
- **Changing what the build accepts or rejects.** A schema that fails today still fails, at the same
  causes. What changes is the identity, the location, and the count of the diagnostics describing it.
- **`UnboundField`'s demotion-target role**, and retaining classifier facts across a failure. That is
  `validation-adds-facts`; this item does not touch the `Resolved` wrapper's contents.
- **A typed cascade arm** for the consuming field's consequence rejection, pending LSP
  related-information plumbing.

## Retired vocabulary

- `InputFieldResolution.Unresolved.reason` and `InputFieldResolution.Unresolved.lookupColumn`.
- `InputFieldResolver.resolve`'s `canLiftToUnknownName` guard and its `condErrors` buffer.
- `TypeBuilder.InputFieldsResolution.Failed(String)` as a prose carrier.
- `BuildContext.candidateHint`, if the folds prove to be its only callers.
- The `List<String> errors` out-param on `BuildContext.buildInputFieldCondition` and
  `classifyInputField`.

## Review notes (Spec → Ready sign-off)

Every named symbol, count, and quotation was verified against the tree at sign-off: the sixteen
producers, the five discarded rejections plus three out-param `.message()` calls, the eleven
`directiveConflict` sites and the `@asConnection`/`splitQuery` anomaly, the sixteen
`ctx.addDiagnostic` minting calls, the doubled `candidateHint`, the Java-vs-SQL candidate-space
disagreement, and the two `getFirst()` drops all hold as written. Non-blocking notes for pickup,
from an independent principles consult; each is the implementer's call:

- **Dedup at the mint boundary, not the drain.** Making `BuildContext.addDiagnostic` idempotent by
  value (ordered set behind the list) dedups once for every reader of `diagnostics()`, not only the
  validator's drain, and lets `FieldBuilder`'s existing `contains`-guard delete. It also gives the
  channel-javadoc widening its principled form: append-only, never read back.
- **`InputFieldsResolution.Failed(Rejection)` re-introduces the cause/consequence polymorphism** the
  boundary rule refuses for `Unresolved`. Carrying the consequence facts typed (input type name,
  table, minted count) needs no LSP plumbing; only the related-information rendering is deferred.
- **Name the sole producer per cause.** Slice 2's classify-time minting coexists with the
  validator's `UnboundField` walks; value-equality dedup does not collapse the same fact built by
  two passes. The dedup and location tests should assert counts, which the Tests section already
  implies.
- One stale cross-reference: R589 was re-sliced on trunk after this spec was written, so "R589's
  slice 4" for the channel-javadoc widening now corresponds to R589's premise paragraph and
  slice 5.
- `BuildContext.candidateHint` has many callers outside the folds (EnumMappingResolver,
  InputBeanResolver, ArgBindingMap, TypeBuilder, FieldBuilder), so the conditional deletion will
  resolve to keep; the "measure at pickup" hedge already covers this.

## Relationships

- **`mcp-aggregated-diagnostics` (R569) depends on this item.** Its `directives` pivot dimension counts
  only rejections carrying a typed directive list, so before the convergence it would report one row for
  `@notGenerated` where three rejections concern it. A confidently wrong count is the failure mode that
  item exists to remove. It also groups on the whole directive set rather than per directive precisely
  because the contract above is not yet pinned.
- **`validation-adds-facts` (R589)** overlaps on this carrier and settles the fan-in fork by doctrine.
  It also wants this item to land first, being smaller and already scoped. The two are complementary
  rather than sequential in substance: this item gives the carrier a typed identity, that one gives it
  retained facts. Nothing here anticipates the claims relation.
- Carved out of `mcp-aggregated-diagnostics` at its Spec review, which had the convergence as an
  in-item step sized "small" across three files. It is neither: the identity cannot move without the
  record and the fan-in moving first.

Blast radius, measured at carve-out and re-measured at Spec (re-measure again at pickup):
`InputFieldResolution`, `BuildContext.classifyInputFieldInternal` plus its two helpers and
`buildInputFieldCondition`, `InputFieldResolver.resolve`, `TypeBuilder.resolveInputFields`,
`FieldRegistry`'s trace arm, `MutationInputResolver`, the `FieldBuilder` re-wrap and `getFirst()` sites,
and the consumers of `GraphitronSchemaValidator.collectInputFieldRejections`. Spans `graphitron` alone,
plus `graphitron-lsp` if the new arms take `lspCode()`s. Note that `UnknownName` publishes no
`lspCode()` today, so no editor behaviour rides on the typed shape yet; its consumers are the message
render and the pipeline tests.

## Fact-base note (2026-08-06)

Sequencing: R589's doctrine (violations are facts, one per failure) settles this item's fan-out fork, and R589's Relationships section asks this item to land first if both are In Progress together. Do not harden the `Resolved` wrapper while here; that carrier sits on the surface the strangler frame retires.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
