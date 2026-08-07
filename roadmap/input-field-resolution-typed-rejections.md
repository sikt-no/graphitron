---
id: R585
title: "Typed rejections on the input-field resolution path"
status: In Progress
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-07
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
`TypeBuilder` and `FieldBuilder` already mint from build paths, and `validation-adds-facts` proposes the
same widening, so this is a documentation correction rather than a new liberty. The widening's principled
form is *append-only, never read back*: see the dedup consequence below, which makes that phrasing
enforceable rather than aspirational.

Two consequences to hold onto, because they are what make the mechanism work rather than merely look
tidy:

- **The consuming field keeps exactly one rejection, and it states the consequence, not the causes.**
  "Input type 'FilmInput' against table 'film': 2 fields could not be bound" belongs on the consuming
  coordinate; the two causes belong at the two input fields. An author sees three diagnostics where
  they saw one, which is the shape a compiler uses for "cannot instantiate" plus two member errors.
  The consequence's *facts* are typed even so, on the builder-step result rather than on the
  `Rejection`: `InputFieldsResolution.Failed` carries the input type name, the table, and the minted
  count as components, and the consuming coordinate's sentence is rendered from them at the
  `FieldBuilder` sites. Widening `Failed` to a bare `Failed(Rejection)` would re-introduce exactly the
  cause-versus-consequence polymorphism the boundary rule refuses for `Unresolved`, and would lose the
  count that a related-information renderer will want. Only a `Rejection` *arm* for the cascade stays
  deferred (it earns its keep once the LSP renders related-information links); `Structural` is honest
  for the rendered consequence in the meantime.
- **Per-field diagnostics must carry no consuming-coordinate prefix.** Input fields are resolved once
  per consuming field, never in a registry type walk, so one input type used by five mutations
  classifies five times. `ValidationError` is a record and `Rejection` arms are records, so structural
  equality collapses the repeats, but only if the per-field rejection is built from the input field's
  own facts. This is the mechanism's best property: when the five uses resolve against the same table
  the fact is identical and dedup fires; when they resolve against different tables the candidates
  differ, the rejections are unequal, and both survive. Value equality dedups exactly when the fact is
  the same. Dedup is available only *because* the rejection is typed, which is why the location move and
  the typing are one change rather than two.

  **Dedup belongs at the mint boundary, not at the drain.** Make `BuildContext.addDiagnostic`
  idempotent by value (an insertion-ordered set behind the list, so `diagnostics()` keeps its order and
  its `List` type). A `distinct()` in the validator's drain would fix only that one reader, while the
  channel has others; idempotence at the mint fixes every reader at once, and it is what licenses the
  javadoc's "append-only, never read back" framing. It also deletes an existing workaround:
  `FieldBuilder`'s shared-nesting sweep guards its own mint with a `ctx.diagnostics().contains(...)`
  check, which is precisely this dedup done once, by hand, by a caller reading the channel back.

- **Name the sole producer per cause.** Slice 2's classify-time minting coexists with
  `GraphitronSchemaValidator`'s own `UnboundField` walk, and value-equality dedup does *not* collapse
  the same fact minted by two different passes with different coordinates or prefixes. Each cause needs
  one nominated producer, and the dedup and location tests must assert counts rather than mere presence,
  or a double-report passes them.

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
  `Failed(String inputTypeName, TableRef tableRef, int mintedCount)`, the typed consequence facts. Its
  two returns (unresolvable fields, bad `@condition`) both fit that shape once each failure is minted.
  Delete the `findFirst()` hint and the `BuildContext.candidateHint` call.
- `FieldBuilder`: the three `Failed`-to-`structural` re-wraps render the consequence sentence from
  `Failed`'s typed components, applying their prefix with `Rejection.prefixedWith` where they carry one.
  The two `collectInputFieldRejections(...).getFirst()` sites mint the tail instead of dropping it. The
  `ctx.diagnostics().contains(...)` guard in the shared-nesting sweep deletes with `addDiagnostic`'s
  idempotence.
- `BuildContext.addDiagnostic`: idempotent by value, insertion-ordered. Widen the javadoc to say
  classify-time minting is intended and the channel is append-only and never read back.
- `MutationInputResolver.rejectInputFieldDirectives`: converge onto `directiveConflict`.
- `BuildContext.candidateHint`: measured at Ready. It has callers well outside the folds
  (`EnumMappingResolver`, `InputBeanResolver`, `ArgBindingMap`, `TypeBuilder`, `FieldBuilder`), so it
  stays; only the folds' calls to it go. Reconciling it against `Rejection`'s private twin is separate
  work and not filed here.

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
  one diagnostic per failing input field, not two; against different tables, two. Assert exact counts,
  not presence: a second producer for the same cause (the validator's own `UnboundField` walk) shows up
  only in a count.
- **Location assertions** on the per-field diagnostics, since moving them off the consuming field is the
  author-visible half and nothing pins it today. Count-asserted for the same reason.
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
- The `List<String> errors` out-param on `BuildContext.buildInputFieldCondition` and
  `classifyInputField`.
- `FieldBuilder`'s `ctx.diagnostics().contains(...)` mint guard in the shared-nesting sweep.
- `FieldBuilder.translatedFkRejectionReason`, replaced by `translatedFkRejection`.

## Implementation notes

Both slices have landed. Seven corrections to the plan's factual claims, all found by doing the work:

- **Six sites had a `Rejection` in hand, not eight.** Of the three out-param `.message()` calls, only
  `ServiceCatalog.reflectTableMethod`'s carries a rejection; `ArgBindingMap.Result.UnknownArgRef` and
  `PathRejected` are prose-only records whose sole component *is* the message. They take the boundary
  wrap alongside `ParsedPath` and `argMappingError`, so the wrap has four call sites rather than two.
- **The retired-directive causes had five spellings, not three.** Beyond `FieldBuilder`,
  `BuildContext` and `MutationInputResolver`, a pre-emptive scan over the argument's input type in
  `FieldBuilder.classifyArgument` carries its own `@notGenerated` and `@lookupKey` rejections and
  short-circuits the classifier for plain-input args, so it is the producer an author actually hits on
  the non-nested case. All five now route through `directiveConflict`.
- **`prefixedWith` is not context-preserving across arms.** Ten typed sub-seals (`ReflectionError`
  and siblings) define it as a deliberate no-op, since their rendered context is the renderer's job.
  So the condition accumulator carries the coordinate context itself
  (`InputFieldConditionFailure.message`) rather than prefixing it onto the rejection, and the minted
  diagnostic relies on `ValidationError.coordinate` rather than on prose surviving a prefix.
- **The `@asConnection` site is settled by dropping `splitQuery` from its list.** The contract
  ("every listed directive is applied at the rejection's own declaration; a remedy belongs in the
  prose") is now stated on `DirectiveConflict`'s javadoc and pinned by a test that checks the listed
  names against the rejection's own `definition()`.
- **The `@reference` column-miss candidate space was wrong in the fold and is now the path's terminal
  table.** Both folds hinted with the *resolving* table's columns, but the column is looked for at the
  path's terminal, which `ServiceCatalog.terminalTableForReference` already answers.
- **A coordinate can legitimately carry two facts.** In a circular input chain the same field is both
  the cause (the cycle, detected on the second visit) and the consequence (its own nesting failure).
  Dedup is by value, so both survive, correctly; the producer-partition test therefore selects a row's
  fact rather than assuming one fact per coordinate.
- **`BuildContext.candidateHint` stays**, as measured at Ready. Only the folds' calls to it went.

The producer-partition test covers the eight producers reachable from the default fixture catalog,
each row declaring its arm and, where the arm is `Structural`, why. The remaining producers (the
id-reference synthesis shim's four arms, the FK-target key mismatch, and the two NodeId decode-helper
failures) need `KjerneJooqGenerator` node metadata and are covered against the fixture context by
`NodeIdPipelineTest` and `NodeInferencePipelineTest`, including the key mismatch's `Deferred` arm.

## In Review → Ready rework (2026-08-07)

Independent-session review of `eb27e57` (slice 1) and `ee9f4af` (slice 2). Full
`mvn install -Plocal-db` SUCCESS on the reviewed tree; 3185 `graphitron` tests green; no
code-string assertions on generated bodies anywhere in the delivered tests. The mechanism is the
one the spec asked for and the delivery is close. Three things to settle before the next gate.

### 1. Blocking: a nested `@condition` failure is minted under the wrong input type

`InputFieldConditionFailure` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldConditionFailure.java:15`)
carries `(fieldName, location, rejection)` but not the type that *declares* the field, while the
accumulator is threaded through the nesting recursion unchanged
(`BuildContext.java:2645`). `BuildContext.mintInputFieldFailure` (`BuildContext.java:2482`) then
builds the coordinate from the *fold level's* type name, so a nested field's condition failure is
minted at a coordinate that does not exist. Reproduced on this tree:

```graphql
input Inner {
  filmId: Int! @field(name: "film_id")
    @condition(condition: {className: "...NoSuchClass", method: "nope"})
}
input FilterA { inner: Inner }
input FilterB { inner: Inner }
type Query { a(filter: FilterA): [Film!]!  b(filter: FilterB): [Film!]! }
```

yields two diagnostics, `FilterA.filmId` and `FilterB.filmId`, both at the correct line. Neither
coordinate exists: `filmId` is declared on `Inner`. Both halves of the design are lost — the
coordinate is not "built from the input field's own facts", and because it borrows the consumer's
type name the two mints are unequal values, so the mint-boundary dedup that the Design section
makes load-bearing does not fire on one fact minted twice. The javadoc on `mintInputFieldFailures`
("Nothing here carries the consuming coordinate, deliberately") states the intended contract and
is currently false on this path.

The `Unresolved` path is correct: the nesting branch mints its nested failures with the nested
type's own name (`BuildContext.java:2655`), so only the condition accumulator is affected.

Fix is contained: thread the declaring type onto `InputFieldConditionFailure` (every one of the
eight `buildInputFieldCondition` call sites is inside `classifyInputFieldInternal`, which has
`parentTypeName` in scope, and the nesting recursion already passes the nested type name as
`parentTypeName`), and have `mintInputFieldFailure` read it instead of the fold's argument. Add the
nested case to the Tests section's dedup bullet, count-asserted: one nested input consumed by two
outer types yields one diagnostic at `Inner.<field>`, not two at the consumers'.

### 2. Retirement sweep: two retired terms survive in test javadoc

Both are prose only, both describe the retired mechanism as if it were live:

- `GraphitronSchemaBuilderTest.java:4664` — "the gate's placeholder Unresolved (lookupColumn null)"
  and ":4666" — "the actionable diagnostic is the condition error in `condErrors`". Neither
  `lookupColumn` nor `condErrors` exists; the paragraph also predates the location move, since the
  diagnostic is no longer on the consuming field.
- `NodeIdPipelineTest.java:1553` — "surfaces the deferred-emission hint via its Unresolved reason".
  `Unresolved.reason` is retired; the hint now rides the record's `rejection`.

### 3. The spec body still reads as prospective

`roadmap/workflow.adoc` § Item file conventions asks a shipped phase to collapse into a one-line
"shipped at `<sha>`" note. The Sequencing section still carries both slices in full, and Design /
Implementation / Tests still read as instructions ("Delete eight `.message()` calls", which the
Implementation notes then correct to six). The Implementation notes capture the learnings well;
fold the shipped narrative into them and leave the two SHAs plus whatever genuinely remains.

### Not blocking, for the author's judgment

- The `DirectiveConflict.directives` contract is now stated on the record's javadoc and pinned by
  `InputFieldFanInDiagnosticsTest.directiveConflict_listsOnlyDirectivesTheAuthorApplied`, but at
  exactly one of the eleven producer sites. The Tests section asked for a contract test, and one
  site is a spot check rather than a contract; a sweep asserting the property over every
  `DirectiveConflict` a corpus schema produces would make it build-enforced. Fine as a follow-up.
- `BuildContext.untypedUpstream` is `Rejection.structural` under a name, so the boundary wraps are
  invisible to any consumer and the producer-partition test has to record "boundary wrap" by hand
  in a note column. That is the honest cost of the additive-then-cutover discipline and reads as
  intended, but it means the allowlist, not the type system, is what shrinks when `ParsedPath`
  lands.

## Spec → Ready sign-off (2026-08-07)

Every named symbol, count, and quotation was verified against the tree: the sixteen producers, the five
discarded rejections plus three out-param `.message()` calls, the eleven `directiveConflict` sites and the
`@asConnection`/`splitQuery` anomaly, the sixteen `ctx.addDiagnostic` minting calls, the doubled
`candidateHint`, the Java-vs-SQL candidate-space disagreement, and the two `getFirst()` drops all hold as
written. `classifyInputField` was confirmed unreachable from the memoized `lookAheadVerdict` type walk, so
classify-time minting is never speculative. Four non-blocking opportunities raised at review are now
folded into the sections above: mint-boundary dedup, `Failed`'s typed consequence components, the
sole-producer-per-cause rule with count-asserted tests, and `candidateHint` measured and kept.

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
