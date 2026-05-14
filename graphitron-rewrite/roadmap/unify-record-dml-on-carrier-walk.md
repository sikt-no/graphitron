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
- `MutationInputResolver.validateReturnType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:169-260`) admits the `ResultReturnType` arm whenever `fqClassName != null`, or when `fqClassName == null` and the carrier walk says `Ok`. The "`fqClassName != null` but carrier walk rejected" sub-case is the live trigger for Path 2.
- `TypeFetcherGenerator` consumes `DmlReturnExpression.Payload` at `:2851` and `:2919`; the body emits the payload-class constructor call directly inside the DML emitter, using `PayloadAssembly.rowSlot` and `PayloadAssembly.defaultedSlots`.

## Spec direction (to be confirmed Backlog → Spec)

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

  Compact-constructor rejection of `DmlKind.DELETE` mirrors `MutationDmlRecordField` (the row is gone before the response SELECT can read it). The bulk-input question (whether this leaf admits `tableInputArg.list() == true`, or whether it splits into `MutationDmlPayloadField` / `MutationBulkDmlPayloadField` mirroring R141) is a Spec-time decision: take whichever shape the emit paths fork on, the principles-aligned default is "split if the emit shapes differ".

- **Reroute the classifier dispatch.** `classifyMutationField`'s current carrier-walk branch (`:2957-2999`) keeps its `Ok` arm. Add a second guard after that: if the return is `ResultReturnType` and `resolveDmlPayloadAssembly` returns `Assembly(assembly)`, route to `MutationDmlPayloadField`. Path 2's fall-through into `buildDmlField` for `ResultReturnType` goes away entirely; `buildDmlReturnExpression`'s `ResultReturnType` branch becomes unreachable and is deleted.

- **Shrink `DmlReturnExpression`.** Drop the `Payload` permit. The sealed interface collapses to four arms (`EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`). `buildDmlReturnExpression`'s signature loses its `Optional<PayloadAssembly>` parameter; the `payloadAssembly.orElseThrow(...)` assertion goes with it. Update the type's Javadoc: the "total over Invariant #14's admitted set" claim is rephrased without the `ResultReturnType` row.

- **Move the emit logic.** The current `TypeFetcherGenerator` arms keyed on `DmlReturnExpression.Payload` (`:2851`, `:2919`) move onto a new `MutationField` switch arm keyed on `MutationDmlPayloadField`. The emit body itself (constructor call, row-slot binding, defaulted-slot handling) is preserved verbatim; the dispatch is at a different level.

- **Validator mirrors classifier.** `MutationInputResolver.validateReturnType`'s `ResultReturnType` arm post-lift admits the return iff one of `(a) tryResolveSingleRecordCarrier == Ok`, `(b) resolveDmlPayloadAssembly == Assembly`. Today's per-arm rejection text composes naturally — if both probes reject, surface both reasons.

- **Test migration.** Before the lift, identify tests under `graphitron-rewrite/graphitron/src/test/java` that today exercise Path 2 (the reflective-row-slot path). The pattern to grep for is `@record(record: {className: ...})` payloads whose SDL Object does not pass the single-data-field carrier walk. Each such test re-classifies post-lift; pipeline assertions on the `MutationField` permit identity update from `MutationInsertTableField` (etc.) to `MutationDmlPayloadField` (or its bulk sibling).

## Out of scope

- **Collapsing `DmlReturnExpression` into per-return-shape permit identity.** After this cleanup the four-arm sealed type still earns its keep (cardinality × projection-shape dispatch inside the four DML kinds). A further collapse into permit identity is a separate question with its own emit-shape audit.
- **Documentation lift of the `*TableField` ⇒ `TableBoundReturnType` rule.** Once the invariant holds uniformly, it earns a place in `graphitron-rewrite/docs/rewrite-design-principles.adoc` ("Narrow component types") and `graphitron-rewrite/docs/code-generation-triggers.adoc`. Document edit follows the structural lift, not vice versa.
- **The wider naming question.** Whether the `*TableField` suffix should ever signal "input-table-bound" (`DmlTableField` permit identities) vs. "return-table-bound" (`TableTargetField` and the read-side) is a renaming conversation, not a structural one. This item leaves the `MutationInsertTableField` family's names alone.

## Relationship to R158 / R159

R158 (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) and R159 (the semantic admission question for payload carriers) sit on the consumer-side data-field axis (`ChildField.SingleRecordTableField`'s producer). This item sits on the producer-side classifier axis (the DML mutation field itself). R158 inherits the cleaner invariant downstream — once landed, `MutationInsertTableField` etc. never carry `@record`, so any future reasoning about the `MutationField` ↔ `ChildField.SingleRecordTableField` producer-consumer pair lives entirely inside the `Mutation*RecordField` permits. But R158 doesn't *require* this cleanup: `TableTargetField`'s sealed-interface contract already enforces `TableBoundReturnType returnType` on `SingleRecordTableField`, which is the narrower invariant R158 actually leans on.

The three can land in any order. R161 carries no `depends-on`.

## Spec must address

- Whether `MutationDmlPayloadField` admits the bulk-input shape (`tableInputArg.list() == true`) or splits into `MutationDmlPayloadField` / `MutationBulkDmlPayloadField`. Check the emit paths: does the reflective row-slot construction differ between single-input and bulk-input today? `TypeFetcherGenerator`'s `:2851`/`:2919` arms tell.
- Exact admission predicate for `MutationInputResolver.validateReturnType`'s post-lift `ResultReturnType` arm. The composition of carrier-walk and reflective-assembly probes is structurally clear; the wording when both reject needs care so the schema author gets the actionable hint (likely the carrier-walk reason if the payload looks SDL-shaped, the reflective-assembly reason if not).
- The set of existing tests under `graphitron-rewrite/graphitron/src/test/java` that exercise Path 2 today. Inventory needed to size the test-migration work and confirm behavioural equivalence post-lift.
- Whether a load-bearing classifier check (`@LoadBearingClassifierCheck`) covers the new permit-identity invariant (no `@record` ever reaches `MutationInsertTableField` etc.) or whether the structural narrowing (`MutationInsertTableField` records gain no `returnType: ResultReturnType` admission path) is itself the guarantee. The classifier-acceptance shape from `rewrite-design-principles.adoc:107-118` is the model.
- Whether `DmlReturnExpression`'s remaining four arms should retain their dispatch role inside the cleanup, or whether a follow-up "collapse into permit identity" lift earns a separate Backlog item right now. Default position: leave as-is, file the follow-up if Spec review surfaces a real argument for collapse.
