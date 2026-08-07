---
id: R585
title: "Typed rejections on the input-field resolution path"
status: In Review
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-07
---

# Typed rejections on the input-field resolution path

`InputFieldResolution.Unresolved` carried prose (`fieldName, lookupColumn, reason`) where every
sibling builder-step result carries a typed `Rejection`, and three fan-ins joined the failures of k
input fields into one sentence reported at the consuming field's location. So the rejection got
*less* structured as the schema got *more* broken, and five broken input fields produced one squiggle
in the wrong place. The carrier now holds a `Rejection` and a `SourceLocation`, and each failure is
reported as its own located fact.

## Shipped

- **Slice 1, `eb27e57`** — type the carrier, preserve every message. The record widening, the
  `.message()` deletions, the boundary wraps, the retired-directive convergence on
  `directiveConflict`, the FK-target key mismatch onto `deferred`, and the condition out-param as a
  typed accumulator. Rendered text stayed byte-identical; zero test churn.
- **Slice 2, `ee9f4af`** — dissolve the folds. Per-failure minting into `BuildContext.addDiagnostic`
  (idempotent by value, so dedup happens at the mint and `FieldBuilder`'s contains-guard goes), the
  location move, the typed `InputFieldsResolution.Failed`, the two `getFirst()` tail drops, and the
  `DirectiveConflict.directives` contract with the `@asConnection` site settled. Eighteen assertions
  moved from the consuming field's message to the cause's new home.
- **Rework, `dc9b232`** — a nested `@condition` failure minted under the fold level's input type
  rather than its declaring type, which named a coordinate the schema does not have and broke dedup
  across two consumers of one nested input. Plus the retirement sweep on three test javadoc
  paragraphs. See the review section below.

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

Eight corrections to the plan's factual claims, all found by doing the work:

- **One accumulator spans the nesting recursion, so its entries outlive their fold.** The plan treated
  the condition accumulator as a per-level buffer, which is true of the resolution failures (the
  nesting branch mints its own before returning a consequence) but not of the condition ones: they are
  threaded down and surface at the outermost fold. A fact must therefore name the type that declares
  it rather than the fold that drains it, or the coordinate is one the schema does not have and one
  fact minted from two consumers is two unequal values, which is exactly the input dedup exists to
  collapse. Found in review, fixed in `dc9b232`, count-asserted in
  `InputFieldFanInDiagnosticsTest.nestedConditionFailure_mintsUnderTheDeclaringTypeAndDedups`.
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

## In Review → Ready rework (2026-08-07), all points addressed in `dc9b232`

**Resolution.** (1) Fixed: `InputFieldConditionFailure` carries the declaring type and the mint reads
it, with the nested-consumer case count-asserted. (2) Fixed: all three javadoc paragraphs rewritten;
no term in this item's Retired vocabulary survives in sources or docs. (3) Done: this body now
carries the shipped SHAs and the learnings, not the instructions. Of the two non-blocking
observations, the `DirectiveConflict` contract sweep is filed as a fresh Backlog item rather than
widened here, since it covers eleven producer sites this item does not own; the `untypedUpstream`
observation is a correct reading of the boundary rule's cost and needs no change. The reviewer's
findings follow verbatim.

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
