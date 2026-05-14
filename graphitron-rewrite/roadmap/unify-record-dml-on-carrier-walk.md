---
id: R161
title: "Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path"
status: Spec
bucket: structural
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-14
---

# Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path

The hierarchy carries two parallel designs for `@record`-returning DML mutations today. R75 introduced the carrier-walk path (`MutationField.MutationDmlRecordField` / `MutationBulkDmlRecordField`); the older reflective-payload path (`MutationField.MutationInsertTableField` and the three DML siblings + `DmlReturnExpression.Payload`) stayed in place, and the two compete for the same SDL shapes at classify time. The visible consequence is that the `*TableField` naming convention silently overloads two unrelated axes: read-side and `@service`-side `*TableField` permits all carry `ReturnTypeRef.TableBoundReturnType returnType` (structurally enforced for the `ChildField.TableTargetField` family), but the four `MutationField.DmlTableField` permits don't carry a `returnType` at all — they carry a `DmlReturnExpression returnExpression` whose `Payload` arm is a `ResultReturnType`. A reader looking for "every `*TableField` returns a `@table` type" finds it true everywhere except inside the DML kinds, where the "Table" suffix tracks the input `@table` argument and the return can be a `@record` payload.

The duplication is load-bearing for nothing structural. Both paths consume a payload class with a constructor parameter typed as the DML's jOOQ TableRecord; they differ on whether the SDL-side payload shape exposes a single `@table`-element data field that the carrier walk recognises. The Path 2 emitter (`TypeFetcherGenerator` consumers of `DmlReturnExpression.Payload`) projects the row into a Java-side constructor slot built from `PayloadAssembly`; the Path 1 emitter hands the row to a data-field fetcher that ultimately projects it the same way. The cleanup is to retire Path 2 by routing every `@record`-returning DML through a `Mutation*RecordField` permit, then dropping the `Payload` arm from `DmlReturnExpression`. After that, `Mutation*TableField` permits are guaranteed to never carry a `@record` return, and the wider invariant "`*TableField` ⇒ never a `@record` return" holds across the hierarchy.

## Concrete state today

- `FieldBuilder.classifyMutationField` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:2886`) routes to `MutationDmlRecordField` / `MutationBulkDmlRecordField` when `BuildContext.tryResolveSingleRecordCarrier(returnTypeName) == Ok` (`:2957-2999`).
- Otherwise it falls through to `buildDmlField` which calls `buildDmlReturnExpression` (`:2724-2748`). The `ResultReturnType` branch (`:2739-2743`) constructs `new DmlReturnExpression.Payload(assembly)`, where `assembly` is the `PayloadAssembly` produced by `resolveDmlPayloadAssembly` (`:1991-2061`). The resulting `MutationInsertTableField` (or sibling) carries a `@record` return inside its `returnExpression`.
- `MutationInputResolver.validateReturnType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:169-260`) admits the `ResultReturnType` arm whenever `fqClassName != null`, or when `fqClassName == null` and the carrier walk says `Ok`. The "`fqClassName != null`" sub-case is the live trigger for Path 2; `fqClassName != null` always implies `PojoResultType.Backed`, which is non-candidate for the carrier walk by construction (`BuildContext.tryResolveSingleRecordCarrier` admits only `PlainObjectType` and `PojoResultType.NoBacking`).
- `TypeFetcherGenerator` consumes `DmlReturnExpression.Payload` at `:2851` and `:2919`; the body emits the payload-class constructor call directly inside the DML emitter, using `PayloadAssembly.rowSlot` and `PayloadAssembly.defaultedSlots`.

## Spec direction

- **Add a sibling `MutationField` permit for the reflective row-slot shape.** Not a widening of `tryResolveSingleRecordCarrier`. The SDL-single-data-field case and the reflective-row-slot case are genuinely different at emit time (data-field handoff vs. inline constructor projection); collapsing them onto one permit would push the same N-way switch we're removing from `DmlReturnExpression` into the permit's body. Put the dispatch in the permit identity.

  Proposed shape:

  ```java
  record MutationDmlPayloadField(
      String parentTypeName,
      String name,
      SourceLocation location,
      ReturnTypeRef.ResultReturnType returnType,
      ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
      DmlKind kind,
      PayloadAssembly assembly,
      Optional<ErrorChannel> errorChannel
  ) implements MutationField { ... }
  ```

  Single-input only; no bulk sibling. `tableInputArg.list() == true` paired with a `ResultReturnType` return is already rejected upstream by Invariant #15 in `MutationInputResolver.validateReturnType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:252-257`: `listInput && !returnType.wrapper().isList()` surfaces the "TooManyRowsException" diagnostic), and the emit body at `TypeFetcherGenerator.emitPayload` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:3066-3090`) hard-codes `.returning().fetchOne()`. Compact-constructor rejects `DmlKind.DELETE` (mirrors `MutationDmlRecordField`: the row is gone before the response SELECT can read it) and `tableInputArg.list() == true` (mirrors Invariant #15 at the type level so a hypothetical schema-author bypass would still fail at construction).

- **Reroute the classifier dispatch.** `classifyMutationField`'s current carrier-walk branch (`:2957-2999`) keeps its `Ok` arm. Add a second guard after that: if the return is `ResultReturnType` and `resolveDmlPayloadAssembly` returns `Assembly(assembly)`, route to `MutationDmlPayloadField`. Path 2's fall-through into `buildDmlField` for `ResultReturnType` goes away entirely; `buildDmlReturnExpression`'s `ResultReturnType` branch becomes unreachable and is deleted.

- **Shrink `DmlReturnExpression`.** Drop the `Payload` permit. The sealed interface collapses to four arms (`EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`). `buildDmlReturnExpression`'s signature loses its `Optional<PayloadAssembly>` parameter; the `payloadAssembly.orElseThrow(...)` assertion goes with it. Update the type's Javadoc: the "total over Invariant #14's admitted set" claim is rephrased without the `ResultReturnType` row.

- **Move the emit logic.** The current `TypeFetcherGenerator` arms keyed on `DmlReturnExpression.Payload` (`:2851`, `:2919`) move onto a new `MutationField` switch arm keyed on `MutationDmlPayloadField`. The emit body itself (constructor call, row-slot binding, defaulted-slot handling) is preserved verbatim; the dispatch is at a different level.

- **Validator mirrors classifier; probes are structurally disjoint.** `MutationInputResolver.validateReturnType`'s `ResultReturnType` arm post-lift admits the return iff one of `(a) fqClassName == null` and `tryResolveSingleRecordCarrier == Ok`, `(b) fqClassName != null` and `resolveDmlPayloadAssembly == Assembly`. The two probes do not overlap: `tryResolveSingleRecordCarrier` admits only `PlainObjectType` or `PojoResultType.NoBacking` candidates (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:509-510`), and `@record(record: {className: ...})` produces `PojoResultType.Backed`, which falls through to `NotCandidate`. So the path is fully determined by `fqClassName`'s presence; the validator surfaces the carrier-walk reason when `fqClassName == null` and the reflective-assembly reason when `fqClassName != null`. No composition-of-both-reasons branch needed.

- **Test migration.** Seven tests exercise Path 2 today and re-target on the lift. Four in `GraphitronSchemaBuilderTest`:
  - `DML_RECORD_PAYLOAD_RETURN_HAPPY` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:5972`) — happy path with row + errors slots
  - `DML_RECORD_PAYLOAD_ROW_ONLY_HAPPY` (`:6003`) — happy path with row slot only
  - `DML_RECORD_PAYLOAD_LIST_REJECTED` (`:6022`) — list-of-payload rejection (Invariant #14)
  - `DML_RECORD_PAYLOAD_NO_ROW_SLOT_REJECTED` (`:6039`) — constructor missing row-typed parameter

  Three in `FetcherPipelineTest`:
  - `dmlDeleteField_recordPayloadReturn_successArmConstructsPayloadAndCatchArmDispatches` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java:412`)
  - `dmlDeleteField_recordPayloadReturnNoErrorsField_successArmConstructsPayloadCatchArmRedacts` (`:433`)
  - `dmlMutation_setterShapePayload_emitsSetterFactory` (`:367`) — setter-shape row slot variant

  Classification assertions in `GraphitronSchemaBuilderTest` update from `(MutationField.MutationDeleteTableField) ... DmlReturnExpression.Payload` to `(MutationField.MutationDmlPayloadField) ... .assembly()`. Emitter assertions in `FetcherPipelineTest` keep their body checks (same `.deleteFrom`, `.returning()`, `.fetchOne()`, payload-ctor / payload-setter output) since the emit body is preserved verbatim; only the permit-identity entry point changes.

## Out of scope

- **Collapsing `DmlReturnExpression` into per-return-shape permit identity.** After this cleanup the four-arm sealed type still earns its keep (cardinality × projection-shape dispatch inside the four DML kinds). A further collapse into permit identity is a separate question with its own emit-shape audit.
- **Documentation lift of the `*TableField` ⇒ `TableBoundReturnType` rule.** Once the invariant holds uniformly, it earns a place in `graphitron-rewrite/docs/rewrite-design-principles.adoc` ("Narrow component types") and `graphitron-rewrite/docs/code-generation-triggers.adoc`. Document edit follows the structural lift, not vice versa.
- **The wider naming question.** Whether the `*TableField` suffix should ever signal "input-table-bound" (`DmlTableField` permit identities) vs. "return-table-bound" (`TableTargetField` and the read-side) is a renaming conversation, not a structural one. This item leaves the `MutationInsertTableField` family's names alone.

## Relationship to R158 / R159

R158 (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) and R159 (the semantic admission question for payload carriers) sit on the consumer-side data-field axis (`ChildField.SingleRecordTableField`'s producer). This item sits on the producer-side classifier axis (the DML mutation field itself). R158 inherits the cleaner invariant downstream — once landed, `MutationInsertTableField` etc. never carry `@record`, so any future reasoning about the `MutationField` ↔ `ChildField.SingleRecordTableField` producer-consumer pair lives entirely inside the `Mutation*RecordField` permits. But R158 doesn't *require* this cleanup: `TableTargetField`'s sealed-interface contract already enforces `TableBoundReturnType returnType` on `SingleRecordTableField`, which is the narrower invariant R158 actually leans on.

The three can land in any order. R161 carries no `depends-on`.

## Resolved positions on Backlog open questions

The Backlog body left five questions for Spec to nail down. All five are resolved against the codebase as of `cb57133`.

- **Bulk-input shape.** One permit. `MutationDmlPayloadField` admits `tableInputArg.list() == false` only. The bulk + reflective-payload cell is already rejected upstream by Invariant #15 (`validateReturnType:252-257`), and `TypeFetcherGenerator.emitPayload` (`:3066-3090`) hard-codes `.returning().fetchOne()` with an explicit comment that "list-payload returns rejected at validateReturnType, so .fetchOne() is the only shape here". A `MutationBulkDmlPayloadField` sibling would have no admitted SDL shape to classify and no emit body to differ on; declining the split is the principles-aligned move.

- **Admission predicate for the post-lift `ResultReturnType` arm.** The two probes are structurally disjoint, so the predicate is a disjunction with non-overlapping arms keyed on `fqClassName`'s presence (see the "Validator mirrors classifier" Spec direction bullet above). The "compose both rejection reasons" worry the Backlog body called out doesn't trigger: only one probe is in scope per `fqClassName`-state, and that probe's reason is the actionable hint.

- **Test inventory.** Seven tests, enumerated in the "Test migration" Spec direction bullet above. Four classification (`GraphitronSchemaBuilderTest.DML_RECORD_PAYLOAD_*`) plus three emit (`FetcherPipelineTest.dmlDeleteField_recordPayloadReturn_*` and `dmlMutation_setterShapePayload_*`). No other source file under `graphitron-rewrite/graphitron/src/test/java` references `DmlReturnExpression.Payload` or `PayloadAssembly` as the system under test (the dummy fixtures in `codereferences/dummyreferences/` are scaffolding, not Path 2 tests).

- **Load-bearing classifier check vs. structural narrowing.** Structural narrowing carries the invariant; no `@LoadBearingClassifierCheck` annotation needed. Once the `Payload` arm is gone from `DmlReturnExpression`, the remaining four arms by construction admit only ID-encoded scalars (`Encoded*`) or `TableBoundReturnType` projections (`Projected*`) — there is no `ResultReturnType` admission path through the type system, so a `MutationInsertTableField` etc. literally cannot be constructed with a `@record`-payload return. This is the "make illegal states unrepresentable" recipe from `rewrite-design-principles.adoc` ("Narrow component types") rather than the classifier-acceptance shape. A `@DependsOnClassifierCheck` annotation on the deleted `buildDmlReturnExpression.ResultReturnType` branch may earn its keep as a tombstone (pointing the reader at the new admission site), but that's documentation, not a load-bearing invariant.

- **DmlReturnExpression collapse follow-up.** Leave the four-arm sealed type as-is; do not file a follow-up Backlog item. Collapsing cardinality × projection-shape into permit identity would multiply the `MutationField` `DmlTableField` permits by four (`MutationInsertEncodedSingleField` / `EncodedList` / `ProjectedSingle` / `ProjectedList`, repeat for `Update`, `Delete`, `Upsert` — sixteen new permits to eliminate one orthogonal axis). The two axes (DML kind, output shape) are genuinely independent, and the 4-arm sealed type earns its keep dispatching the second axis in one place.
