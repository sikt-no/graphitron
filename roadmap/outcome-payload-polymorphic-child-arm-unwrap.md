---
id: R873
title: "Polymorphic child fields under an Outcome payload never emit the wrapper arm-unwrap"
status: Ready
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-08-28
last-updated: 2026-08-31
---

# Polymorphic child fields under an Outcome payload never emit the wrapper arm-unwrap

A payload type carrying an errors field on the `WrapperArm` transport is a *flipped
outcome payload*: at run time its children receive an `Outcome` object as
`env.getSource()`, not the payload instance the author wrote. Every other child-field
emit path knows this. The multi-table polymorphic paths do not: they read the parent off
`env.getSource()` unconditionally, so the cast throws `ClassCastException` on every
request, on both arms, for every polymorphic child field of such a payload.

The generated read is byte-identical whether or not the payload has an errors field. It
happens to be correct only when no wrapper transport is in play.

## Reproduction

Confirmed on trunk (10-SNAPSHOT, after the `@nodeId` dispatch work). Consumer-reported
against 10.0.0-RC35 in
https://github.com/sikt-no/graphitron/issues/526#issuecomment-5449126087, where it
surfaces as

```
class ...schema.Outcome$Success cannot be cast to class ...records.DeaktiverApplikasjonerPayload
```

Generating from SDL where one payload carries both a monomorphic and a polymorphic child
field next to a `WrapperArm` errors field puts the defect and its control in the same
emitted class. The monomorphic sibling narrows:

```java
public static CompletableFuture<DataFetcherResult<Record>> language(DataFetchingEnvironment env) {
  if (!(env.getSource() instanceof Outcome.Success<?> success)) {
    return CompletableFuture.completedFuture(null);
  }
  ...
  Row1<Integer> key = DSL.row(((Record) success.value()).get(Tables.FILM.LANGUAGE_ID));
```

The polymorphic field, same payload, same class, does not:

```java
public static CompletableFuture<DataFetcherResult<List<Record>>> referrers(DataFetchingEnvironment env) {
  ...
  Row1<Integer> key = DSL.row(((Record) env.getSource()).get(Tables.FILM.FILM_ID));
```

The single-valued record-backed arm reproduces the reporter's snippet verbatim, off a
Pojo payload with a typed hub accessor:

```java
Record parentRecord = ((AccessorPayloads.SinglePayload) env.getSource()).film();
```

Removing the errors field from the payload, changing nothing else, makes the same schema
work: the discriminator query, the per-participant projections and the per-type dispatch
are all correct. Only the source binding is wrong.

## Where it comes from

`TypeFetcherGenerator` computes `sourceIsOutcome` once per type from
`FetcherEmitter.hasWrapperArmErrors(fields)` and threads it into
`FetcherEmitter.bind` and `buildBatchedDataFetcher`. None of the eight
`MultiTablePolymorphicEmitter` call sites in the same dispatch switch receive it, so the
emitter has no way to know the parent is wrapped. Two sites then hardcode the source:

- `MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` binds `parentRecord` off
  `env.getSource()` in both its `KeyLift.Accessor` and its table-backed arm.
- The batched fetchers call `GeneratorUtils.buildRecordParentKeyExtraction` on the
  overload that defaults `sourceExpr` to `SOURCE_FROM_ENV`.

The machinery for the fix already exists and is unused on this path:
`buildRecordParentKeyExtraction` has a source-bound overload taking the expression the
backing object is read from, added precisely so an arm-switching caller can repoint the
field's own key extraction at `success.value()` without re-deriving it.

`GraphitronSchemaValidator.validateOutcomeChildArmSwitch` does not catch this either. It
covers the `PropertyDataFetcher` registration-escape family only (per
`FetcherEmitter.resolvesViaPropertyDataFetcher`), and a polymorphic child field registers
a real emitted fetcher, so it passes the guard while still reading off the wrong object.

## What changes when this lands

A payload type that carries an errors field on the wrapper transport can hold polymorphic
child fields (interface- or union-typed fields resolved across multiple participant
tables), and they resolve correctly on both arms: the success arm reads the parent off
`Outcome.Success.value()` and delivers the children; the error arm resolves the child to
null while the sibling errors field renders the error list. Today every such field throws
`ClassCastException` on every request (the consumer report in
https://github.com/sikt-no/graphitron/issues/526). Alongside the fix, the parent-source
read becomes one seam with an enforcer, so the next child-field emit path cannot hardcode
`env.getSource()` again without a build failure asking it the outcome question.

Affected shapes, all reproduced or read off the emitter: batched list and connection forms
on both table-backed and record-backed parents, and the single-valued inline form on both.
The connection form of the *inline* polymorphic child (the degenerate all-unbound
participant set) routes to the root-shaped connection emitter, which reads no parent
source; it is unaffected, and the design below records that fact as a declared membership
rather than a comment.

## Design

### One seam for the parent-source read

The defect is not a missing prelude and not a wrong source expression in isolation; it is
that the two halves are mintable independently. The narrowing prelude (the statement that
narrows `env.getSource()` to `Outcome.Success` and escapes on the `ErrorList` arm) and the
source expression (what the parent's backing object is read from: `env.getSource()`
directly, or `success.value()` after narrowing) only mean anything as a pair. The
monomorphic batched builder mints the pair correctly but inline; `buildScalarPerParentFetcher`
binds a source with no prelude; the two batched polymorphic fetchers take the
key-extraction overload whose source defaults to `SOURCE_FROM_ENV`, also with no prelude.
Splitting the pair is the bug, so the fix makes the pair a value.

Introduce a sealed parent-source binding, minted by **one producer** and consumed by every
child-field fetcher builder that reads a parent backing object. Three arms, spliced out of
facts the model already carries (the parent's source shape, and whether the type owns a
`WrapperArm` errors field, the predicate `FetcherEmitter.hasWrapperArmErrors` already
computed once per type at `TypeFetcherGenerator.generateForType`):

- **Table row**: the parent is this type's own projected table row. Never null mid-query,
  never Outcome-wrapped. Empty prelude, source is the environment source.
- **Direct record**: a producer-handed backing object, no wrapper transport in play.
  Prelude is the null-source guard (the LocalContext errors transport fires the data
  fetcher with `data(null)`, so the guard is load-bearing, not defensive). Source is the
  environment source.
- **Outcome record**: a producer-handed backing object behind the wrapper transport.
  Prelude narrows `Outcome.Success` and escapes on the `ErrorList` arm; source is
  `success.value()`.

The one thing that varies per consumer is the escape expression the prelude returns
(`completedFuture(null)` in the async fetchers, the sync null payload in
`buildScalarPerParentFetcher`, plain null in inline reads), so that is the producer's
parameter and everything else is derived. Consumers:

- `buildScalarPerParentFetcher`: both `parentRecord` bindings (the `KeyLift.Accessor` arm
  and the table-backed arm) read the binding's source expression, prefixed by its prelude.
- `buildBatchedListFetcher` and `buildBatchedConnectionFetcher`: emit the binding's
  prelude ahead of the loader registration and pass its source expression to the
  source-bound `buildRecordParentKeyExtraction` overload.
- `buildBatchedDataFetcher` (the monomorphic site that mints the pair inline today)
  migrates to the same producer, so the two families cannot drift.
- `FetcherEmitter.armSwitchedInlineDataFetcher` keeps its shape but derives its prelude
  and source from the same producer where that folds cleanly; at minimum the
  `Outcome`/`Success`/`ErrorList` names in every prelude route through
  `OutcomeClassGenerator.CLASS_NAME`/`SUCCESS_CLASS`/`ERROR_LIST_CLASS` (today
  `TypeFetcherGenerator` spells them as string literals at the batched prelude site, a
  fourth spelling of a name the generator already mints).

Delete the defaulting four-argument `buildRecordParentKeyExtraction` overload (three
callers, all in scope here), so every key extraction states its source expression. After
this change no child-field parent read spells `env.getSource()` at its own site: an
emitter that has not obtained a binding has no source expression to write.

### Where the deciding fact lives

Decision: the binding is an emit-side derivation with a single producer, not a new
component on the four polymorphic leaves. `SourceEnvelope` (the classify-time envelope
fact on the two carrier leaves) is the model's name for this fork, and its javadoc names
exactly the gap this item closes ("the batched re-fetch path never carries it; there the
generator derives the same fork at the type level as `sourceIsOutcome`"). Carrying it onto
four more leaves would resolve that gap in the model, but the strangler rule says new
capability lands as fact relations, never as new leaf components or walk-side plumbing,
and the binding is a pure function of facts the model already carries first-class (the
sibling errors field's `WrapperArm` transport, the parent's source shape). Deriving a view
at one seam is what code generation is; the failure mode this item fixes was never
"derived twice, differently", it was "not derived at all at five sites", which the single
producer plus the enforcement below closes. The trade-off is owned in Coverage: with no
leaf-carried fact, the pipeline tier cannot assert the envelope as data for these fields,
so behavioural coverage lands at the execution tier for both parent backings.

Two predicates currently answer "is the source Outcome-wrapped" on different populations:
`FieldBuilder.carrierPayloadHasErrorsField` (any errors-shaped field; mints
`SourceEnvelope` on the carrier leaves) and `FetcherEmitter.hasWrapperArmErrors` (the
`WrapperArm` transport specifically). The binding producer reads the transport fact, the
same signal both of today's consumers use. The implementation documents the relationship
between the two predicates at the producer (with `{@link}`s, so it cannot rot silently)
and updates the `SourceEnvelope` javadoc sentence and its echo in
`docs/architecture/explanation/dispatch-axes.adoc` once the type-level derivation has a
name.

### Enforcement: a declared partition, not a widened escape check

`GraphitronSchemaValidator.validateOutcomeChildArmSwitch` keeps its current invariant
unchanged: the `PropertyDataFetcher` registration-escape family is a real and different
population, and folding a second question into it would conflate the two. The widening the
item asks for lands as a sibling mechanism on the pattern the generator already runs for
dispatch status (`IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` / `STUBBED_VARIANTS`,
pinned exhaustive and disjoint by
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`):

- A second partition over the same leaf vocabulary declares, for every field leaf, its
  parent-source posture: **reads the parent through the binding seam**, **reads no parent
  source** (root fetchers, the errors field's own transports), or **cannot appear under a
  wrapper payload** (combinations the classifier or validator rejects before emission,
  e.g. a `ComputedField` needs a SELECT-projected parent and is inventory-absent under a
  class-backed payload).
- A coverage test pins the partition exhaustive and disjoint, so a new field leaf fails
  the build until its author answers the outcome question.
- A sibling validator rule rejects an SDL that places a third-bucket leaf beside a
  `WrapperArm` errors field, turning "cannot appear" from folklore into a rejection. For
  first-bucket leaves no validator is needed: obtaining the binding is how a fetcher
  builder gets a source expression at all after the overload deletion, so the compiler
  enforces consumption.

A lexical marker-comment guard over emitted `env.getSource()` literals (the
`CodegenClassForNameGuardTest` mold) was considered and rejected: the template-literal
population is roughly thirty sites of which only a handful genuinely face the question, so
it would mint dozens of exemption markers to guard four sites, and it would census-gate a
spelling this same change removes from the child-field paths.

### The LocalContext sibling

The direct-record arm's null-source guard means every consumer of the binding gets the
LocalContext parity the monomorphic batched path already has (today the polymorphic
fetchers would NPE on a null source rather than CCE). Whether a LocalContext payload can
actually carry a polymorphic child today is to be established during implementation: if
admissible, the guard is live and gets an execution-tier pin on a LocalContext fixture; if
the combination is rejected upstream, the leaf's partition bucket and the validator rule
above are the enforcer, not a javadoc sentence.

## Implementation notes

Two facts the design left to be established during implementation, now established:

- **The LocalContext sibling is rejected upstream.** `validateLocalContextErrorsFieldGuards`
  admits only `BatchedTableField` (record-sourced, no lookup) and
  `SingleRecordIdFieldFromReturning` beside a LocalContext errors field; the polymorphic leaves
  are not on that allow-list, so a LocalContext payload cannot carry a polymorphic child today.
  Per the design's fork, the enforcer is that existing rule plus the posture partition; no
  LocalContext execution fixture exists to pin. The direct-record arm's null-source guard still
  lands on every binding consumer, so the parity holds structurally if the allow-list ever widens.
- **The child `@service` leaves land in the third bucket.** `ChildField.ServiceTableField` and
  `ChildField.ServiceRecordField` are mintable on class-backed parents but their key sources read
  `env.getSource()` unconditionally (the same defect family this item fixes for the polymorphic
  leaves), so beside a `WrapperArm` errors field they were a request-time `ClassCastException`.
  Migrating them through the binding seam is out of this item's declared scope; the partition
  declares them wrapper-inadmissible and the new sibling validator rule turns the combination
  into a build-time rejection. Lifting that rejection by routing their key sources through the
  seam is follow-up work for whoever needs the combination.

## Coverage

Execution tier (`graphitron-sakila-example`), the tier that would have caught a CCE on
every request. New fixture payload types over the existing `AddressOccupant` union
(Customer | Staff, both FK back to address); deliberately *new* types, so the existing
`AddressOccupantCarrier` pins keep their meaning:

- A root `@service` Pojo payload with a typed hub accessor (`address()` returning the
  `AddressRecord`), the reporter's exact shape: a single-valued `AddressOccupant` child,
  a batched list child, and a `WrapperArm` errors union. One test per arm: the success
  arm resolves both children against the fixture rows; the error arm renders both null
  with the errors list populated.
- A root `@service` payload backed by the jOOQ record itself (the service returns
  `AddressRecord`), covering the `KeyLift.FkColumns` lift under the wrapper: an
  `@asConnection` child plus a single-valued child, success and error arms. This is the
  parent backing the first fixture cannot reach, and leaving it pipeline-only would leave
  the shape that broke without coverage of the thing that broke.

Pipeline tier (`FetcherPipelineTest`, beside the existing monomorphic outcome pins):
presence, return-type shape, and `METHOD_REFERENCE` wiring for the polymorphic children
under a wrapper payload. Stated honestly: these pass against today's broken output (the
tier bans code-string body matching, and the defect lives in the body), so they are
hygiene pins that hold the classification and registration shape, not regression coverage
for this item.

Unit/pipeline tier, the enforcement pieces: the partition coverage pin (exhaustive and
disjoint over field leaves), and validator tests for the sibling rule (a third-bucket leaf
beside a `WrapperArm` errors field rejects; the fixed combinations pass validation).

Docs: update the `SourceEnvelope` javadoc gap sentence and the matching paragraph in
`dispatch-axes.adoc`.

## Reviewer findings

### Round 1 (In Review → Ready)

Both gate questions pass. The implementation is the change the spec approved, and the
completeness evidence is the right evidence and holds: `OutcomePolymorphicChildExecutionTest`
covers both parent backings on both `Outcome` arms against real fixture rows, and it is green
in a full `mvn install -Plocal-db` (all 14 modules SUCCESS). One approval precondition fails,
and that is the whole of the rework.

**Blocking: a code-string assertion on a generated method body.**
`RecordParentMultiTablePolymorphicPipelineTest.childUnionField_recordParent_matchesTableParent`
pins the `DirectRecord` prelude as a Java source literal:

```java
String nullSourceGuard = """
        if (env.getSource() == null) {
          return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        """.indent(2);
assertThat(recordReferrers.toString()).contains(nullSourceGuard);
```

`development-principles.adoc`, "Behaviour is pinned at the pipeline tier and above", bans this
at every tier ("they test implementation, not behaviour, and break on every refactor"), and
names review as the enforcement point. The literal pins javapoet's rendering choices: the
two-space indent, the fully-qualified `CompletableFuture` rather than an import, and the exact
statement form. Any of those moving breaks a test that is not about them. The rest of the
delivery honours the rule deliberately (the new `FetcherPipelineTest` pins carry a comment
saying the tier does not string-match bodies), so this is a lone inconsistency, not a stance.

**What satisfies it.** Keep the test's real claim, that the record parent's fetcher differs
from the table parent's by exactly the `DirectRecord` prelude, but derive the guard from the
production producer instead of transcribing it, so the assertion compares two generated
artifacts:

```java
String nullSourceGuard = new ParentSourceBinding.DirectRecord()
    .prelude(CodeBlock.of("env.getSource()"),
             CodeBlock.of("$T.completedFuture(null)", COMPLETABLE_FUTURE))
    .toString();
```

with the existing `contains` / `replace` comparison unchanged. That cannot rot on a formatting
or naming change, and it fails for the one reason the test exists. A roadmap-only diff will not
cover this; the fix touches `graphitron` test sources, so it owes a full verification build.

**Non-blocking, recorded not requested.**

- The design's line "no child-field parent read spells `env.getSource()` at its own site" is
  true of the source expression but not of the literal: emitters still spell `env.getSource()`
  as the `subject` they hand the binding (`MultiTablePolymorphicEmitter.ENV_SOURCE`,
  `TypeFetcherGenerator.envSource`). The subject parameter is a sound refinement, it is what
  lets a `LightFetcher` read pass `source` instead, and the load-bearing invariant (the source
  expression is only ever obtained from a binding) holds. The design sentence is the thing that
  is now slightly wrong, not the code.
- Five prose sites still name the type-level derivation `sourceIsOutcome`
  (`ChildField.java:193`, `KeyLift.java:79`, `FieldBuilder.java:6650`, `:6730`, `:6775`).
  The retirement sweep passes as declared, since the retired item was the boolean *parameter
  threading* and the local feeding the producer survives under that name. But the derivation now
  has a proper name, and the spec updated two of the seven places that state this claim. Worth a
  follow-up sweep rather than a gate.
- `ParentSourceBinding.of` is the single producer, but the `parentTable != null ? Table : Record`
  splice ahead of it is written twice (`TypeFetcherGenerator.generateTypeSpec`,
  `FetcherRegistrationsEmitter.parentSourceBinding`). Both sites duplicated the equivalent
  `hasWrapperArmErrors` call before this change too, so nothing regressed.

Verified during review and needing no action: the `buildBatchedDataFetcher` table-arm fork moved
from the per-leaf `field.sourceShape() == Table` to the per-type `parentSource instanceof
TableRow`. The two agree, because `parentTable != null` holds exactly when the type is a
`TableBackedType`, which `SourceShapeProjectionTest` pins as equivalent to the child's
`sourceShape() == Table`. Both "Implementation notes" settlements were checked against the code
and honour the design's forks: `validateLocalContextErrorsFieldGuards` really does admit only
`BatchedTableField` and `SingleRecordIdFieldFromReturning`, and declaring the child `@service`
leaves wrapper-inadmissible is what the design's enforcement section prescribes for any leaf not
routed through the seam.

## Retired vocabulary

To be swept at the Done gate:

- `GeneratorUtils.buildRecordParentKeyExtraction(SourceKey, KeyLift, TableRef, ResultType)`
  (the defaulting overload) and `GeneratorUtils.SOURCE_FROM_ENV` as its silent default.
- The `sourceIsOutcome` boolean *parameter threading* into fetcher builders, where the
  sealed binding replaces it (the per-type predicate `hasWrapperArmErrors` itself stays;
  it feeds the producer).

