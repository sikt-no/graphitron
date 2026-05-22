---
id: R228
title: Build-time rejection for inline TableField / LookupTableField with condition-join step
status: Ready
bucket: validation
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Build-time rejection for inline TableField / LookupTableField with condition-join step

> `InlineTableFieldEmitter` and `InlineLookupTableFieldEmitter` both emit a
> runtime-throwing stub for fields whose `joinPath` contains a
> `JoinStep.ConditionJoin`, but `GraphitronSchemaValidator` lets these fields
> pass at build time. The five other variants that share the same emit-block
> predicate surface it as a `Rejection.Deferred`. This item closes the gap on
> the two inline-emitter variants so the SDL author hits a build-time error
> rather than a runtime `UnsupportedOperationException`.

---

## Motivation

A real consumer schema landed in inbox carrying:

```graphql
studiekurv: Studiekurv @override(from: "admissio")
    @reference(path: [{
        condition: {
            className: "no.sikt.fs.opptak.soknad.studiekurv.StudiekurvConditions",
            method: "studiekurvForSoker"
        }
    }])
```

The schema compiled, the generator produced a fetcher, and the first request
hit:

```
java.lang.UnsupportedOperationException: Inline TableField 'Soker.studiekurv'
with a condition-join step cannot be emitted until classification-vocabulary
item 5 resolves condition-method target tables
    at no.sikt.fs.opptak.generated.graphitron.types.Soker.$fields(Soker.java:69)
```

This directly contradicts the validator's own javadoc at
`GraphitronSchemaValidator.java:202-205`:

> "Without this check the build succeeds and the generated stub throws
> `UnsupportedOperationException` at the first request hitting the variant;
> the principle in `graphitron-principles.md` is that problems caught at build
> time are cheaper, so we surface it here."

The validator already enforces this contract for five sibling variants that
share the same predicate; the two inline-emitter variants were missed.

### Current coverage matrix

[cols="1,1,1"]
|===
| Variant | Emitter stub | Build-time rejection

| `ChildField.SplitTableField`
| `SplitRowsMethodEmitter.buildForSplitTable`
| yes (`ConditionJoinReportable` channel via `validateVariantIsImplemented`)

| `ChildField.SplitLookupTableField`
| `SplitRowsMethodEmitter.buildForSplitLookupTable`
| yes (same channel)

| `ChildField.RecordTableField`
| `SplitRowsMethodEmitter.buildForRecord*`
| yes (same channel)

| `ChildField.RecordLookupTableField`
| `SplitRowsMethodEmitter.buildForRecord*`
| yes (same channel)

| `ChildField.ColumnReferenceField`
| `InlineColumnReferenceFieldEmitter`
| yes (explicit arm at `GraphitronSchemaValidator.java:575`)

| `ChildField.TableField` *(this item)*
| `InlineTableFieldEmitter:53-60`
| no

| `ChildField.LookupTableField` *(this item)*
| `InlineLookupTableFieldEmitter:64-71`
| no
|===

The two inline-emitter variants carry the predicate inline
(`JoinPathEmitter.hasConditionJoin(...)`) rather than going through the
`ConditionJoinReportable` capability the other four share. That is why
`validateVariantIsImplemented` does not pick them up: it dispatches via
`SplitRowsMethodEmitter.unsupportedReason(field)`, which gates on
`field instanceof ConditionJoinReportable`.

---

## Design

The `ConditionJoinReportable` interface was originally documented as
"implemented by the four ChildField variants that share the condition-join
predicate" (`ConditionJoinReportable.java:6-13`). The four-variant count was
historic, `TableField` and `LookupTableField` share the same predicate
mechanically; they were not declared `ConditionJoinReportable` only because at
the time the interface was introduced, their inline emitters were already
carrying the stub independently. The shape grew six variants worth of stub but
only four variants worth of validator coverage.

The natural fix extends the capability to its full set of participants: two
new arms on `Rejection.EmitBlockReason` and two new `implements
ConditionJoinReportable` clauses, with the shared predicate at
`SplitRowsMethodEmitter.unsupportedReason` picking them up automatically.

### Extend `ConditionJoinReportable` to the two inline variants

```java
// ChildField.java
public record TableField(
    // existing components unchanged
) implements TableTargetField, BatchKeyField, ConditionJoinReportable {

    @Override public Rejection.EmitBlockReason emitBlockReason() {
        return Rejection.EmitBlockReason.TABLE_FIELD_CONDITION_JOIN_STEP;
    }
    @Override public String displayLabel() { return "Inline TableField"; }
}

public record LookupTableField(
    // existing components unchanged
) implements TableTargetField, BatchKeyField, LookupField, ConditionJoinReportable {

    @Override public Rejection.EmitBlockReason emitBlockReason() {
        return Rejection.EmitBlockReason.LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP;
    }
    @Override public String displayLabel() { return "Inline LookupTableField"; }
}
```

The `joinPath()` accessor is already present on both records, so the third
interface contract is satisfied without renames. `displayLabel()` mirrors the
prose the inline emitters' stubs use today ("Inline TableField '...' with a
condition-join step cannot be emitted until ..."), so the build-time message
matches the runtime message byte-for-byte for the not-yet-lifted runtime case.

### Two new `EmitBlockReason` enum values

```java
// Rejection.java
enum EmitBlockReason {
    SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP,
    SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP,
    RECORD_TABLE_FIELD_CONDITION_JOIN_STEP,
    RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP,
    TABLE_FIELD_CONDITION_JOIN_STEP,                  // new
    LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP            // new
}
```

Per the enum's own contract (`Rejection.java:391-395`: "one value per
`SplitRowsMethodEmitter.unsupportedReason` arm today; a new value lands when a
new emit-block predicate is introduced"). The naming convention copies the
existing four arms.

### Inline emitters consult the shared predicate

The duplicated `JoinPathEmitter.hasConditionJoin(...)` check inside
`InlineTableFieldEmitter.buildSwitchArmBody` and
`InlineLookupTableFieldEmitter.buildSwitchArmBody` becomes a call into the
same `SplitRowsMethodEmitter.unsupportedReason` the validator uses. The
emitter and validator share the predicate, matching the pattern at
`SplitRowsMethodEmitter.java:344-346`:

```java
public static CodeBlock buildSwitchArmBody(ChildField.TableField tf, ...) {
    var stubReason = SplitRowsMethodEmitter.unsupportedReason(tf);
    if (stubReason.isPresent()) {
        return CodeBlock.builder()
            .addStatement("throw new $T($S)",
                UnsupportedOperationException.class, stubReason.get().message())
            .build();
    }
    return buildFkOnlyArm(tf, parentAlias, sfName, outputPackage);
}
```

The runtime stub stays as defense-in-depth (isolated emitter unit tests that
bypass `GraphitronSchemaBuilder` still exercise the emitter without the
validator's preflight), but the predicate is single-sourced.

### Why this rather than explicit validator arms

An alternative: two `if (JoinPathEmitter.hasConditionJoin(...))
emitDeferredError(...)` blocks added inline to `validateTableField` and
`validateLookupTableField`, mirroring `validateColumnReferenceField` at
`GraphitronSchemaValidator.java:575`. This is smaller and avoids the model
change, but:

- The condition-join stub message and key would live in three places (two
  inline-emitter stubs, two validator arms, two enum values' tag) rather than
  one shared predicate.
- The `ConditionJoinReportable` interface exists precisely to collapse this
  shape across variants; widening it from four arms to six matches the
  interface's design intent.
- `Rejection.EmitBlockReason`'s growth contract anticipates new values per
  predicate arm; the enum is the natural place to register the two new arms.

`ColumnReferenceField`'s inline arm exists because that variant has a
different shape of `Rejection.Deferred` and a different stub key (the slug
`column-reference-on-scalar-field-condition-join`); it does not share the
`Rejection.EmitBlockReason` taxonomy. The inline-`TableField` and
inline-`LookupTableField` cases are structurally the same as the four
existing `ConditionJoinReportable` participants, so they collapse there.

The implementer may fall back to the explicit-arm approach if the
capability-extension path turns out to ripple beyond the model and validator
files (e.g. if `displayLabel()` consumers downstream make assumptions that
need updating). Either approach satisfies the acceptance criteria; the
capability-extension path is preferred for end-state cleanliness.

---

## Tests

### Pipeline-tier (the primary behavioural tier)

`GraphitronSchemaBuilderTest` gains two fixtures, mirroring the shape of
existing `SPLIT_QUERY_ON_RECORD_PARENT_WARNS_*` cases:

- `TABLE_FIELD_CONDITION_JOIN_REJECTED_AT_BUILD_TIME`: an SDL with
  `@reference(path: [{condition: {...}}])` on a plain (non-`@record`,
  non-`@splitQuery`) field returning a `@table` type at single or list
  cardinality. Assert `schema.rejections()` contains a `Rejection.Deferred`
  on the field coordinate whose message contains
  `"Inline TableField '<Type>.<field>' with a condition-join step cannot be emitted"`
  and whose `StubKey` is
  `EmitBlock(EmitBlockReason.TABLE_FIELD_CONDITION_JOIN_STEP)`.
- `LOOKUP_TABLE_FIELD_CONDITION_JOIN_REJECTED_AT_BUILD_TIME`: same shape but
  with a `@lookupKey` directive making the field a `LookupTableField`. Assert
  the `LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP` reason value.

### Unit-tier

`TableFieldValidationTest` and `LookupTableFieldValidationTest`, mirroring
the existing `RecordTableFieldValidationTest` and
`RecordLookupTableFieldValidationTest`. One enum case per cardinality
(single, list) per variant, asserting the validator emits the deferred
message for the condition-join joinPath and emits no error for the FK-only
joinPath.

### Acceptance gate

The `studiekurv`-shaped consumer schema in the error report from inbox
would, after this item ships, produce a build error at schema-load time
rather than a runtime exception at first request. The implementer adds a
one-line pipeline-tier fixture (a self-join `@reference(path:
[{condition: {...}}])` on a synthetic `@table` type with an existing
`@condition` method) and asserts the build emits the expected
`Rejection.Deferred` rather than completing and producing a runtime stub.

---

## Implementation sites

- `model/ChildField.java`: add `ConditionJoinReportable` to the `implements`
  clause on `TableField` and `LookupTableField`; implement `emitBlockReason()`
  and `displayLabel()` on each.
- `model/Rejection.java`: add `TABLE_FIELD_CONDITION_JOIN_STEP` and
  `LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP` to `EmitBlockReason`.
- `generators/InlineTableFieldEmitter.java:53-60`: replace inline
  `hasConditionJoin` check with `SplitRowsMethodEmitter.unsupportedReason(tf)`
  dispatch; emit runtime stub from the returned message.
- `generators/InlineLookupTableFieldEmitter.java:63-71`: same change for
  `LookupTableField`.
- `validation/TableFieldValidationTest.java` and
  `validation/LookupTableFieldValidationTest.java`: new unit-tier files in
  the shape of `RecordTableFieldValidationTest`.
- `GraphitronSchemaBuilderTest`: two new pipeline-tier fixtures (see *Tests*
  above).
- `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`: no
  change expected (the two variants remain in `IMPLEMENTED_LEAVES`; the
  intra-variant emit-block does not move them to `STUBBED_VARIANTS`).
- `LoadBearingClassifierCheck` audit: no new classifier check is introduced;
  the new enum values are pure tagging on the existing predicate.

---

## Out of scope

- *Lifting the condition-join restriction.* Remains owned by R3
  (`classification-vocabulary-followups`) item 5 and the parallel R129
  (`column-reference-on-scalar-field-condition-join`). When R3 item 5 ships,
  all six variants' validator arms come out together with the two emitter
  stubs in `InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter` and
  the four runtime stubs in `SplitRowsMethodEmitter`. This item explicitly
  does *not* attempt the lift; it only closes the build-time-error coverage
  gap so the six-variant lift can happen as a single pass later without
  surprises.
- *Renaming or relocating `SplitRowsMethodEmitter.unsupportedReason`.* The
  static method is class-named after its first caller. Now that six variants
  use it across both split-rows and inline-multiset emitters, the name is
  structurally a `ConditionJoinReportable` predicate; the move to a more
  neutral home (a static on `ConditionJoinReportable`, or `JoinPathEmitter`)
  is a separate cleanup. File a Backlog stub when this item lands if the
  name drift is still bothering the implementer.

---

## Non-goals

- Widening `ConditionJoinReportable` to non-condition-join emit-block
  predicates. The capability is single-purpose ("condition-join in joinPath
  blocks emission"); other emit-block reasons that may surface later get
  their own capability rather than overloading this one.
- Changing the runtime stub message format. The existing two inline-emitter
  stubs already use the "Inline {Variant} '{coord}' with a condition-join
  step cannot be emitted until classification-vocabulary item 5 ..." form;
  the shared predicate keeps the same prose so existing operator-side log
  matchers do not break.

---

## Review feedback (In Review → Ready, 2026-05-22)

The implementation at de25b0a is well-shaped: capability extended to six
variants, two new `EmitBlockReason` values, both inline emitters routed
through the shared `SplitRowsMethodEmitter.unsupportedReason` predicate, the
"four variants" javadoc/comment trio bumped to "six", and the pipeline +
unit-tier tests assert the build-time `Rejection.Deferred` shape per the spec.
The sakila example cleanup (drop `Category.similar` + `CategoryConditions`)
is the right consequence of moving runtime-stub → build-time-rejection on that
shape.

Two issues block approval:

### 1. Build red — `DiagnosticsDocCoverageTest` fails

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` fails at
`graphitron-sakila-example` with:

```
[ERROR]   DiagnosticsDocCoverageTest.everyDiagnosticEnumValueHasADocSectionAndViceVersa:68
  [diagnostic enum values without a matching heading in diagnostics-glossary.adoc;
   add the missing paragraph(s)]
  Expecting empty but was: ["lookup-table-field-condition-join-step",
                            "table-field-condition-join-step"]
```

Per `docs/manual/reference/diagnostics-glossary.adoc:169` ("Drift protection"),
every `Rejection.EmitBlockReason` value must have an anchored
`=== <kebab-case-name>` heading. The four sibling values
(`split-table-field-condition-join-step`,
`split-lookup-table-field-condition-join-step`,
`record-table-field-condition-join-step`,
`record-lookup-table-field-condition-join-step`) already have entries at
`diagnostics-glossary.adoc:147-165`. Add two paragraphs in the same shape
for the new arms:

```adoc
[#emit-block-table-field-condition-join-step]
=== table-field-condition-join-step

An inline `TableField` (no `@splitQuery`, no `@record` parent) reaches a
`@reference` path with a `@condition` step the inline emitter does not yet
emit; surfaced as a build-time error mirroring the runtime stub at
`InlineTableFieldEmitter`. Same family as the split / record arms.

[#emit-block-lookup-table-field-condition-join-step]
=== lookup-table-field-condition-join-step

An inline `LookupTableField` (list `@lookupKey` site) reaches a `@reference`
path with a `@condition` step the inline lookup emitter does not yet emit;
surfaced as a build-time error mirroring the runtime stub at
`InlineLookupTableFieldEmitter`. The lookup-shape sub-arm.
```

### 2. Spec body addition: list the glossary file under "Implementation sites"

The original "Implementation sites" list does not name
`docs/manual/reference/diagnostics-glossary.adoc`, so the implementer can be
forgiven for missing it. Add a bullet there before the next implementation
pass so the gate is visible up front:

> - `docs/manual/reference/diagnostics-glossary.adoc`: add a
>   `=== table-field-condition-join-step` paragraph and a
>   `=== lookup-table-field-condition-join-step` paragraph in the
>   "Deferred emit-block reasons" section, in the shape of the four
>   existing entries at lines 147-165. `DiagnosticsDocCoverageTest`
>   enforces drift; the build is red without these.

### Notes (not blocking)

- *Test placement.* The spec called for fixtures in `GraphitronSchemaBuilderTest`
  using `schema.rejections()`, but `GraphitronSchema` carries no such accessor;
  the implementer correctly redirected to `R58TypedRejectionPipelineTest`
  ("build schema + run validator + assert typed rejection") and called this out
  in the commit body. The redirect is correct; the spec's mention of
  `schema.rejections()` was stale. Leave the test placement as landed.
- *Sakila example trim.* Removing `Category.similar` and `CategoryConditions`
  is consistent with the move to build-time rejection. The acceptance gate the
  spec called for (a `studiekurv`-shaped pipeline-tier fixture) is satisfied
  by the new `inlineTableField_conditionJoinStep_rejectedAtBuildTime` /
  `inlineLookupTableField_conditionJoinStep_rejectedAtBuildTime` tests in
  `R58TypedRejectionPipelineTest`; no separate fixture needed.

### What's needed to re-enter In Review

Add the two glossary paragraphs and confirm `mvn -f graphitron-rewrite/pom.xml
install -Plocal-db` is green end-to-end. Status: In Review → Ready;
the four-from-six and other code-side changes at de25b0a are fine and do
not need redoing.
