---
id: R22
title: "Mutation bodies"
status: Spec
priority: 9
theme: mutations-errors
depends-on: []
---

# Mutation bodies

> Lift all six mutation leaves out of `TypeFetcherGenerator.STUBBED_VARIANTS`:
> `MutationField.MutationInsertTableField`, `MutationUpdateTableField`,
> `MutationDeleteTableField`, `MutationUpsertTableField`,
> `MutationServiceTableField`, `MutationServiceRecordField`.
> Closes generator stub #4 — the highest-aggregate production rejection (131 combined).
>
> **Progress:** five of six landed (DELETE, INSERT, UPDATE, both service variants).
> UPSERT remains.

---

## R50 prerequisites — shipped

R22's dependency on R50 (Lift NodeId out of the model) has cleared, and R50's cleanup pass overshot the original R22 plan: the four DML records, the shared classifier helper, and the `DmlTableField` sealed supertype all landed as part of R50. Phase 1 of R22 (model + classifier across all four DML variants) is therefore complete. Concretely:

- **`DmlTableField` sealed supertype.** `MutationField.DmlTableField` permits `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`. All four records share an identical shape: `(parentTypeName, name, location, returnType, tableInputArg, encodeReturn, errorChannel)`. There are no per-variant fields; everything the emitters need lives on `tableInputArg`.
- **`encodeReturn: Optional<HelperRef.Encode>`.** Replaces the earlier `nodeIdMeta` slot. `Optional.of(...)` for `ScalarReturnType("ID")` returns, `Optional.empty()` otherwise. `HelperRef.Encode` carries `(encoderClass, methodName, paramSignature: List<ColumnRef>)`; the typeId is baked into the per-`@node`-type `encode<TypeName>` method name, so the emitter never reaches back into `JooqCatalog`. The DELETE emitter resolves the projection via `f.encodeReturn().orElseThrow().paramSignature()` and the lambda via `encode.encoderClass()` + `encode.methodName()`.
- **`TableInputArg.fieldBindings: List<InputColumnBinding.MapBinding>`.** Narrowed to the `MapBinding` arm of R50's `InputColumnBinding` seal (the only arm any DML call site produces). `MapBinding.fieldName()` is the input field name; `MapBinding.targetColumn()` is the `ColumnRef`. `TableInputArg` also carries `fields: List<InputField>` (every input field, in declaration order) and `argCondition: Optional<ArgConditionRef>`, which is what the classifier inspects for Invariant #12.
- **Shared `classifyMutationInput(fieldDef, typeName)` helper.** A single helper in `FieldBuilder` enforces Invariants #1–#14 across all four DML verbs in one pass. There are no per-verb `classifyMutationDeleteField` / `classifyMutationInsertField` helpers; the per-verb branching lives inside the shared helper (UPDATE/DELETE/UPSERT require non-empty `fieldBindings`; UPDATE requires non-empty non-`@lookupKey` fields; UPDATE/DELETE require full PK coverage). The mutation-arm switch in `classifyMutationField` builds the appropriate variant record from the resolved `tia` and `encodeReturn`.
- **Wire-shape variants retired.** R50 deleted `InputField.NodeIdField`, `InputField.NodeIdReferenceField`, `InputField.IdReferenceField`, and `InputField.NodeIdInFilterField`. The post-R50 input-side replacements are `ColumnField` (with `Direct` extraction or `NodeIdDecodeKeys` extraction) and `CompositeColumnField` / `CompositeColumnReferenceField`. The deferred-input arms in the shared classifier helper now gate `NestingField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`, and `NodeIdDecodeKeys`-extracted `ColumnField`; only `Direct`-extracted `ColumnField` is admitted.
- **Pipeline tests.** `GraphitronSchemaBuilderTest.rootFieldClassification` covers happy-path classifier scenarios for `INSERT_MUTATION_FIELD`, `UPDATE_MUTATION_FIELD`, `DELETE_MUTATION_FIELD`, `UPSERT_MUTATION_FIELD`, `SERVICE_MUTATION_FIELD`, and `MUTATION_SERVICE_RECORD_FIELD`, plus DELETE negatives `DELETE_MUTATION_NO_INPUT_ARG` and `DELETE_MUTATION_MISSING_LOOKUP_KEY`. Each variant resolves to its variant record in the post-R50 shape.

What remains for R22:

1. ~~**Phase 1B — model alignment.**~~ Landed. `DmlReturnExpression` carries the entire return-shape dispatch as a five-arm sealed sub-variant on `DmlTableField`; the `Payload` arm absorbs the R12-introduced `Optional<PayloadAssembly>` slot so each DML record dropped from 8 components to 6.
2. ~~**Phase 2 — INSERT emission.**~~ Landed. Same commit also extracted the verb-neutral `buildDmlFetcher` + `emitDmlReturnExpression` skeleton (shared between INSERT and DELETE; UPDATE / UPSERT will land against the same skeleton). Partition flipped, validator test `VALID`, execution test against PostgreSQL covers the `RETURNING $fields` shape end-to-end. INSERT closes the highest-volume DML rejection class.
3. **Phases 4 / 5 — DML emitters** (UPDATE, UPSERT).
4. ~~**Phase 6 — service emitters** (`MutationServiceTableField`, `MutationServiceRecordField`).~~ Landed: both emitters delegate to the shared `buildServiceFetcherCommon` helper, picking up R12's wrapper integration for free.

The *Consolidation* item that previously called for an emitter-side `buildMutationReturnExpression` helper is replaced by Phase 1B's model lift.

---

## Status

| Variant | Status | Notes |
|---|---|---|
| Phase 1A (initial model + classifier) | **Landed** | All four DML records carry the shared `(tableInputArg, returnType, encodeReturn, errorChannel)` shape via `DmlTableField`; shared `classifyMutationInput` enforces Invariants #1–#14. Both service variants populated by `classifyMutationField` via `resolveServiceField`. |
| Phase 1B (model alignment) | **Landed** | `DmlReturnExpression` sealed sub-variant carries the entire return-shape dispatch on `DmlTableField`. Five arms (`EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`, `Payload`) — `Payload` absorbs the R12-introduced `Optional<PayloadAssembly>` slot so the broad `(returnType, encodeReturn, payloadAssembly)` triple collapses to one slot. Records went from 8 components to 6. `@LoadBearingClassifierCheck(key = "dml-mutation-shape-guarantees")` on `buildDmlField`; `@DependsOnClassifierCheck` on `buildMutationDeleteFetcher`. See [Phase 1B](#phase-1b--model-alignment). |
| `MutationDeleteTableField` | **Landed** | `buildMutationDeleteFetcher` pattern-matches on `f.returnExpression()` (no `instanceof ScalarReturnType` / `wrapper().isList()` / `payloadAssembly().isPresent()` predicates). Per-arm bodies live in `emitDeleteEncoded` / `emitDeleteProjected` / `emitDeletePayload` helpers. |
| `MutationInsertTableField` | **Landed** | `buildMutationInsertFetcher` shares the verb-neutral `buildDmlFetcher` skeleton + `emitDmlReturnExpression` projection terminator with DELETE. Column list and parallel values list both walk `tia.fields()` once; values use `DSL.val(in.get(name), Tables.T.COL.getDataType())` for converter-mediated coercion. Partition flipped to `IMPLEMENTED_LEAVES`. Validation test `VALID`. Execution-tier test `createFilm_insertsRowAndReturnsProjectedFilm` against PostgreSQL verifies the `RETURNING $fields` shape end-to-end (resolves DELETE's deferred verification). |
| `MutationUpdateTableField` | **Landed** | `buildMutationUpdateFetcher` shares the verb-neutral `buildDmlFetcher` skeleton + `emitDmlReturnExpression` projection terminator with INSERT and DELETE. SET clause walks `tia.fields()` skipping `@lookupKey` names; WHERE clause reuses the shared `buildLookupWhere` helper. Partition flipped to `IMPLEMENTED_LEAVES`. Validation test `VALID`. Execution-tier test `updateFilm_updatesRowAndReturnsProjectedFilm` against PostgreSQL inserts a marker row, runs the mutation, asserts the SET clause wrote and the `RETURNING $fields` projection returned the new title with `languageId` carrying through unchanged. |
| `MutationUpsertTableField` | **Ready (Phase 5)** | Record + classifier done. Lands `buildMutationUpsertFetcher` against the post-1B shape. |
| `MutationServiceTableField` | **Landed** | `buildMutationServiceTableFetcher` delegates to the shared `buildServiceFetcherCommon` helper, inheriting the R12 §3 try/catch wrapper, §5 Jakarta validation pre-step, and §2c `resultAssembly` success-arm assembly. Wears `@DependsOnClassifierCheck(key = "service-catalog-strict-service-return", ...)` for the strict-return guarantee. Validation test flipped from `STUBBED` to `VALID`. |
| `MutationServiceRecordField` | **Landed** | Same shape as above for the non-table service variant; `buildMutationServiceRecordFetcher` mirrors `buildQueryServiceRecordFetcher` (handles `ResultReturnType` with `fqClassName`, `PojoResultType`, and `ScalarReturnType` via `computeMutationServiceRecordReturnType`). |

**Landing rule (post-Phase-1B).** All four DML records share `(tableInputArg, returnExpression, errorChannel)` via `DmlTableField`. Emitters read from `f.tableInputArg().{name(), inputTable(), fieldBindings(), fields()}` and pattern-match on `f.returnExpression()`. No emitter inlines an `instanceof ScalarReturnType` check or reads `f.returnType().wrapper().isList()`; the dispatch lives once, in the classifier.

---

## Current state

> Section reflects the codebase post-R50. See [Status](#status) for the per-variant
> implementation summary.

### Model

`MutationField.java` (`model/MutationField.java`) declares the sealed hierarchy: six permits with the four DML records (`MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`) sharing the inner `DmlTableField` sealed supertype, plus the two service variants (`MutationServiceTableField`, `MutationServiceRecordField`).

Every DML record currently has the same component list:

```
(String parentTypeName, String name, SourceLocation location, ReturnTypeRef returnType,
 ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
 Optional<HelperRef.Encode> encodeReturn,
 Optional<ErrorChannel> errorChannel)
```

> Phase 1B replaces the `(ReturnTypeRef returnType, Optional<HelperRef.Encode> encodeReturn)` pair with a single `DmlReturnExpression returnExpression` sealed sub-variant. The rationale is in [Phase 1B](#phase-1b--model-alignment); the rest of the *Current state* description reflects today's pre-1B shape.

Service variants additionally carry `MethodRef method` (populated by `resolveServiceField`). Every variant carries `Optional<ErrorChannel> errorChannel` (R12 contract); the classifier passes `Optional.empty()` and the C3 carrier pass populates it.

### Classifier

`FieldBuilder.classifyMutationField` routes mutation fields: `@service` → service variants via `resolveServiceField` + `validateRootServiceInvariants` + the strict service-return check; `@mutation(typeName: ...)` → a single shared `classifyMutationInput(fieldDef, typeName)` helper that walks the arguments, resolves the single `TableInputArg`, enforces Invariants #1–#14, looks up the per-`@node`-type `HelperRef.Encode` for `ScalarReturnType("ID")` returns, and returns either the resolved `TableInputArg` + `encodeReturn` or an `UnclassifiedField` reason. The mutation-arm switch then constructs the appropriate DML variant record from the same `(tia, encodeReturn)` tuple. There are no per-verb classifier helpers.

Phase 1B folds the encode-helper lookup and the `ScalarReturnType("ID")` / `TableBoundReturnType` / list-vs-single fork into a single `DmlReturnExpression` sub-variant computed once at classify time and stored on `DmlTableField.returnExpression()`. The classifier also gains a `@LoadBearingClassifierCheck` annotation describing the guarantees emitters then lean on.

### Generator stubs

`TypeFetcherGenerator.STUBBED_VARIANTS` has entries for the three not-yet-emitted DML variants (INSERT, UPDATE, UPSERT); `generateTypeSpec` routes each to `stub(f)`. DELETE plus both service-mutation variants are in `IMPLEMENTED_LEAVES` and route to `buildMutationDeleteFetcher` / `buildMutationServiceTableFetcher` / `buildMutationServiceRecordFetcher`.

### Neighbouring references

- `ArgumentRef.InputTypeArg.TableInputArg` — carries `inputTable: TableRef`, `fieldBindings: List<InputColumnBinding.MapBinding>` (`@lookupKey`-bound columns), `fields: List<InputField>` (every input field, in declaration order), and `argCondition: Optional<ArgConditionRef>`. Populated by `classifyMutationInput` for mutations and by `classifyArgument` for query-side use. `buildLookupBindings` takes a mutable `List<String> errors` and pushes per-field `@lookupKey` validation errors onto it (e.g. `@lookupKey` on a list-typed input field, on a non-`ColumnField`); `classifyMutationInput` threads these errors back into the `UnclassifiedField` reason.
- `InputField.ColumnField` — carries `name` (GraphQL field name), `column: ColumnRef` (with `sqlName`, `javaName`, `columnClass`), `extraction: CallSiteExtraction`, and `nonNull`. `extraction` is `Direct` for the canonical mutation-input shape and `NodeIdDecodeKeys` for the post-R50 `@nodeId`-typed inputs (gated as deferred).
- `InputColumnBinding.MapBinding` — `(fieldName: String, targetColumn: ColumnRef, extraction: CallSiteExtraction)`. The `MapBinding` arm of R50's `InputColumnBinding` seal; the `RecordBinding` arm carries a positional index into a decoded `Record<N>` and is not used by mutation `fieldBindings`.
- `HelperRef.Encode` — `(encoderClass: ClassName, methodName: String, paramSignature: List<ColumnRef>)`. Resolves to the per-`@node`-type `encode<TypeName>` helper on the generated `NodeIdEncoder`. The typeId is baked into `methodName`; the emitter never reaches back into `JooqCatalog`.
- `GeneratorUtils.ResolvedTableNames` — resolves `tablesClass`, `jooqTableClass`, `typeClass` from a `TableRef`. Used by every SQL-generating emitter.
- `ArgCallEmitter.buildMethodBackedCallArgs(MethodRef, CodeBlock, String)` — declaration-order param-list emitter. Service-mutation variants (Phase 6) reuse it directly.
- `FieldBuilder.validateRootServiceInvariants(ServiceResolution)` — shared classifier-time helper enforcing §1 (Connection rejection) and §2 (no `Sources` parameter at root) on both `@service` query and `@service` mutation arms. Already wired into `classifyMutationField`.
- `MethodRef.Basic.returnType()` — structured javapoet `TypeName` captured at reflection time. Mutation-`@service` strict-return validation already routes through `FieldBuilder.computeExpectedServiceReturnType`; only the emitter side remains for Phase 6.

---

## Invariants

1. **Each DML mutation has exactly one `@table` input argument.**  This is the record that
   drives the DML statement.  If no `@table` input arg is found during classification, produce
   `UnclassifiedField` with a descriptive reason.  Multiple `@table` input args are also
   rejected at classifier time (`"@mutation field has more than one @table input argument"`).

2. **UPDATE and DELETE `@lookupKey` bindings must cover the full primary key.**  `fieldBindings`
   must be non-empty; if empty, return `UnclassifiedField` with
   `"@mutation(typeName: UPDATE/DELETE) requires at least one @lookupKey field in the input type"`.
   Beyond non-empty: call `ctx.catalog.findPkColumns(tia.inputTable().tableName())` — this method
   works for all jOOQ-generated tables (not just `@node` types) by reading `Table.getPrimaryKey()`
   via reflection.  If the result is non-empty, every `ColumnEntry.sqlName()` in it must appear
   in `tia.fieldBindings()` (matched by `binding.targetColumn().sqlName()`); if any PK column is absent,
   return `UnclassifiedField` with
   `"@mutation(typeName: UPDATE/DELETE) @lookupKey fields do not cover all PK column(s); missing: <names>"`.
   If `findPkColumns` returns empty (table has no PK), the basic non-empty check suffices.
   Composite-PK tables where all PK columns appear as `@lookupKey` fields are fully supported:
   each PK column contributes one `.eq(val)` predicate, and the emitter chains them with `.and(...)`.

3. **UPSERT requires at least one `@lookupKey` binding** (the ON CONFLICT key), same gate as #2.

4. **UPDATE requires at least one non-`@lookupKey` field.**  If every `ColumnField` in `tia.fields()`
   has its name in `tia.fieldBindings()`, the SET clause would be empty and the statement degenerates.
   Gate at classifier time: `"@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"`.
   UPSERT is exempt: an UPSERT whose SET clause is empty is semantically `INSERT ... ON CONFLICT DO NOTHING`,
   which is a legitimate pattern; UPSERT's non-empty-SET check is therefore not enforced here.

5. **INSERT does not require `@lookupKey`.**  `fieldBindings` may be empty for INSERT.

6. **INSERT and UPSERT column coverage is a runtime contract, not a classifier invariant.**  The
   classifier does **not** verify that every NOT-NULL, no-default column of the target table is
   covered by an input field, nor that the UPSERT's ON CONFLICT key aligns with any particular
   unique index.  Whatever columns the schema author places in the input type are mapped to DML
   verbatim; the DB rejects at execute time if coverage is wrong.  This is the declared contract:
   computing "NOT-NULL without default" from the jOOQ catalog at classify time is non-trivial (default
   metadata is not always preserved through the generator) and the DB error is actionable.  A
   follow-up item may promote this to a classifier gate; tracked in Non-goals.

7. **Nested `@table` input fields (`InputField.NestingField`) are deferred.**  If any field in
   `tia.fields()` is a `NestingField`, emit an `UnclassifiedField` at classify time
   (`"nested input types in @mutation fields are not yet supported"`).  This keeps the initial
   scope flat; nesting can land in a follow-up.

8. **`@nodeId`-typed input fields are deferred.** R50 retired `InputField.NodeIdField` and replaced it with a `ColumnField` carrying `extraction = NodeIdDecodeKeys.*`. The classifier currently rejects the `NodeIdDecodeKeys`-extracted `ColumnField` arm with the message `"NodeId-decoded ColumnField (post-R50 successor of the retired NodeIdField) in @mutation inputs is not yet supported"`. Lifting this gate requires emitting the per-row decode lambda before the WHERE/SET binding, which is the open follow-up the R50 plan tracks.

9. **`InputField.ColumnReferenceField` and `InputField.CompositeColumnReferenceField` (cross-table references) in a mutation input are deferred.** Gate: `"ColumnReferenceField in @mutation inputs is not yet supported"` / `"CompositeColumnReferenceField in @mutation inputs is not yet supported"`. Cross-table refs would need a join-and-resolve step on the write side. R50 retired the standalone `NodeIdReferenceField` and `IdReferenceField` variants; their cross-table cases now appear as `ColumnReferenceField` with appropriate extraction, and the same gate covers them.

10. **`InputField.CompositeColumnField` in a mutation input is deferred.** Gate: `"CompositeColumnField in @mutation inputs is not yet supported"`. The currently-admitted set of input-field variants in a mutation `@table` input is therefore: `ColumnField` with `Direct` extraction only. `NestingField`, `NodeIdDecodeKeys`-extracted `ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, and `CompositeColumnReferenceField` are all gated. (R50 retired the legacy `NodeIdField`, `NodeIdReferenceField`, `IdReferenceField`, and `NodeIdInFilterField` variants entirely; they no longer exist in the model.)

11. **Listed inputs (`in: [CustomerInputTable]`) are deferred for Phase 1.**  The initial
    implementation handles only single-record inputs.  Listed-input support is a follow-up
    (see Non-goals).

12. **`@condition` on a DML `@mutation` input argument is not supported.**  If
    `tia.argCondition().isPresent()` after `buildLookupBindings`, return `UnclassifiedField` with
    `"@condition on a @mutation field argument is not supported"`.  `@condition` is a query-side
    directive whose projection into WHERE clauses is mediated by condition-method call sites;
    DML mutations emit inline jOOQ predicates directly from `fieldBindings` and have no
    concept of a condition method to invoke.  Gate: `classifyMutationInput` Pass 2.

13. **Non-`TableInputArg` arguments on DML `@mutation` fields are rejected.**  `classifyMutationInput`
    (see §1b) returns `UnclassifiedField` if any argument is anything other than a
    `TableInputArg`, including `PlainInputArg`, scalar args, pagination args, orderBy args.
    Mutations have no parent table context, so those argument shapes are nonsensical here and
    would mis-classify against a sentinel `TableRef` if fed through the generic classifier.
    Message: `"@mutation fields only accept @table input arguments; found '<argName>' of shape '<ArgumentRef variant>'"`.

    **Phase 1 compatibility check (gate before landing):** the legacy classifier silently skipped
    `PlainInputArg` on mutations (unless paired with `@condition`).  Before Phase 1 merges, grep
    production schemas for DML `@mutation` fields whose argument type is an input object without
    `@table` — e.g. a request-metadata struct carrying no column bindings.  If any exist, this
    is a breaking change, not a stub lift: either (a) admit a narrow `PlainInputArg` carve-out
    alongside the single `TableInputArg` (mirrors the legacy skip), or (b) migrate the schema
    authors to move those fields onto the `@table` input type.  Record the count and decision in
    the Phase 1 landing commit.

14. **DML `@mutation` return type must be `ID` (Single or List), `T` (Single of
    `TableBoundReturnType`), or `[T]` (List of `TableBoundReturnType`).**  Other shapes (`Int`,
    `Boolean`, any non-`ID` scalar, `PolymorphicReturnType`, `ResultReturnType`, or `Connection`
    wrapper) are rejected at classifier time with
    `"@mutation(typeName: INSERT|UPDATE|DELETE|UPSERT) return type '<T>' is not yet supported; use ID or a @table type"`.
    Rationale: `.returningResult(...)` cannot honestly express an affected-row count (the semantics
    users typically expect from `Int` / `Boolean` returns), and polymorphic / result-record returns
    would require projection bridges we have no fixture for.  Service-mutation variants (Phase 6)
    accept a wider return-type range via the developer-supplied method's return type; this
    invariant applies only to the four DML variants. Phase 1B's `DmlReturnExpression` sub-variant
    is total over exactly this admitted set; the classifier rejects everything else before the
    variant is constructed.

    **Phase 1 numbers check (gate before landing):** the roadmap's 131-count aggregate comes from
    the legacy generator, which accepted `Boolean` / `Int` via affected-row semantics.  Before
    Phase 1 merges, sample the SDLs producing those rejections and confirm that the majority
    return `ID`, `[ID]`, `T`, or `[T]`.  If a material slice returns `Int` / `Boolean`, the stub
    lift does not close those rejections — it swaps them for a new rejection class, and the
    `.execute()`-based arm (see Non-goals) needs to land in the same pass instead of being
    deferred.  Record the sample-size and return-type breakdown in the Phase 1 landing commit.

---

## Plan

### Phase 1A — Initial model + classifier

**Status: ✅ Landed across all four DML variants and both service variants.** The shape, the shared classifier helper, and the cross-variant classifier-test scaffolding all shipped as part of R50's cleanup pass. This section documents what's in the codebase; for the historical narrative of how the shape evolved (per-variant draft → DELETE-first reality → R50 unification), see commit `31c64a2` and the R50 series.

#### 1a. DML record shape

All four DML variants are identical. From `model/MutationField.java`:

```java
sealed interface DmlTableField extends MutationField
    permits MutationInsertTableField, MutationUpdateTableField,
            MutationDeleteTableField, MutationUpsertTableField {
    ReturnTypeRef returnType();
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
    Optional<HelperRef.Encode> encodeReturn();
    SourceLocation location();
}

record MutationDeleteTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    Optional<HelperRef.Encode> encodeReturn,
    Optional<ErrorChannel> errorChannel
) implements DmlTableField {}
```

> Phase 1B replaces `(ReturnTypeRef returnType, Optional<HelperRef.Encode> encodeReturn)` with a single `DmlReturnExpression returnExpression` slot — see [Phase 1B](#phase-1b--model-alignment). The shape above reflects the codebase as of the last R50-cleanup commit; the rest of this subsection describes how today's emitter consumes it.

There are no per-variant fields. Everything an emitter needs is on `tableInputArg`:
- `tableInputArg.name()` → the SDL argument name (used as the `env.getArgument(...)` key).
- `tableInputArg.inputTable()` → `TableRef` for the target table.
- `tableInputArg.fieldBindings()` → `List<MapBinding>`, the `@lookupKey`-bound subset (WHERE-clause source for UPDATE/DELETE/UPSERT).
- `tableInputArg.fields()` → `List<InputField>`, every input field in declaration order (column-list source for INSERT, SET-clause source for UPDATE/UPSERT).
- `f.encodeReturn()` → `Optional<HelperRef.Encode>`, populated for `ScalarReturnType("ID")` returns.

#### 1b. Shared classifier helper

`FieldBuilder.classifyMutationInput(GraphQLFieldDefinition fieldDef, String typeName)` enforces all DML invariants in one helper, returning either a `MutationInputResult` carrying the resolved `TableInputArg` or a non-null `error` string. The mutation-arm switch in `classifyMutationField` then constructs the appropriate variant record:

```java
case "INSERT" -> buildWithChannel(returnType, parentTypeName, name, location, fieldDef, ch ->
    new MutationField.MutationInsertTableField(parentTypeName, name, location, returnType, tia, enc, ch));
case "UPDATE" -> buildWithChannel(returnType, parentTypeName, name, location, fieldDef, ch ->
    new MutationField.MutationUpdateTableField(parentTypeName, name, location, returnType, tia, enc, ch));
case "DELETE" -> buildWithChannel(returnType, parentTypeName, name, location, fieldDef, ch ->
    new MutationField.MutationDeleteTableField(parentTypeName, name, location, returnType, tia, enc, ch));
case "UPSERT" -> buildWithChannel(returnType, parentTypeName, name, location, fieldDef, ch ->
    new MutationField.MutationUpsertTableField(parentTypeName, name, location, returnType, tia, enc, ch));
```

The helper does not call `classifyArgument` — that path takes a parent `TableRef` and binds un-directived scalar args against the parent's columns, but a mutation field has no parent table. Instead, it walks `fieldDef.getArguments()` directly:

**Pass 1 — shape gate (Invariants #1, #13).** For each argument, unwrap to a named type and look up `ctx.types.get(typeName)`. If the resolved type is a `GraphitronType.TableInputType`, build a `TableInputArg` via `buildLookupBindings` + `tit.inputFields()` and populate `argCondition` via `buildArgCondition`. Allocate a fresh `List<String> bindingErrors` per call and pass it into `buildLookupBindings`; if non-empty, join them with `"; "` and return `UnclassifiedField`. At the end of Pass 1:
- Zero `TableInputArg` found → error "no `@table` input argument found on `@mutation` field".
- Two or more found → error per Invariant #1.
- Any non-`TableInputArg` argument → error per Invariant #13.

**Pass 2 — invariant checks on the single `tia`.** Only reached when Pass 1 produced exactly one `TableInputArg`. The relevant checks vary per verb:

| Check | INSERT | UPDATE | DELETE | UPSERT |
|---|---|---|---|---|
| `tia.list()` (Invariant #11) | reject | reject | reject | reject |
| `tia.argCondition().isPresent()` (#12) | reject | reject | reject | reject |
| Any non-`Direct`-extracted-`ColumnField` input field (#7–#10) | reject | reject | reject | reject |
| `fieldBindings()` empty (#2–#3) | allowed | reject | reject | reject |
| Every field is `@lookupKey`-bound (#4) | n/a | reject | n/a | allowed |
| PK-coverage on non-empty `findPkColumns(tableName())` (#2) | n/a | required | required | n/a |

After Pass 2, validate `returnType` against Invariant #14 (input-shape errors surface first; return-type errors second). For `ScalarReturnType("ID")` returns, scan `ctx.types.values()` for the `NodeType` whose `table().tableName()` equals `tia.inputTable().tableName()` and pull its `encodeMethod()`; reject as `"@mutation field '<name>' returns ID but no @node type is declared for table '<tableName>'"` if absent. For other return types, `encodeReturn = Optional.empty()`.

`UnclassifiedField` rejection kind is `AUTHOR_ERROR` for schema-author mistakes; `INVALID_SCHEMA` is reserved for hard validity violations like the `@service` + `@mutation` mutual-exclusion check.

#### 1c. Validator additions

`validateMutationInsertTableField`, `validateMutationUpdateTableField`, `validateMutationDeleteTableField`, `validateMutationUpsertTableField` are empty stubs by design — the classifier rejects bad shapes via `UnclassifiedField` before validation is invoked. As each emitter lands, the corresponding `*ValidationTest` flips its `STUBBED` scenario to `VALID` (DELETE precedent in `MutationDeleteTableFieldValidationTest`).

#### 1d. Pipeline tests

**Shipped:** `GraphitronSchemaBuilderTest.rootFieldClassification` covers the happy path for each mutation variant — `INSERT_MUTATION_FIELD`, `UPDATE_MUTATION_FIELD`, `DELETE_MUTATION_FIELD`, `UPSERT_MUTATION_FIELD`, `SERVICE_MUTATION_FIELD`, `MUTATION_SERVICE_RECORD_FIELD` — plus DELETE negatives `DELETE_MUTATION_NO_INPUT_ARG` and `DELETE_MUTATION_MISSING_LOOKUP_KEY`. Each variant resolves to its expected variant record class.

**Coverage gap:** the cross-cutting negative-case rows below are still target coverage rather than shipped tests. Each emitter's landing should add at minimum the rows that exercise verb-specific invariants, and we should backfill the cross-cutting rows (deferred input variants, listed inputs, return-type rejections, composite-PK coverage failures) opportunistically. Aim for parity by the time UPSERT lands.

Target coverage table (`GraphitronSchemaBuilderTest.rootFieldClassification` — classifier assertions only, no emission):

| SDL shape | Expected outcome |
|---|---|
| `createFilm(in: FilmInput): ID @mutation(typeName: INSERT)` where `FilmInput @table(name: "film")` | `MutationInsertTableField(tableInputArg.inputTable = TableRef("film", "FILM", "Film", [...]))` |
| `updateFilm(in: FilmInput): ID @mutation(typeName: UPDATE)` with `@lookupKey` on `FilmInput.filmId` covering the single-column PK | `MutationUpdateTableField` with `fieldBindings` non-empty |
| `updateFilm` with no `@lookupKey` in input | `UnclassifiedField` with Invariant #2 message |
| `updateFilm` where every input field is `@lookupKey` | `UnclassifiedField` with Invariant #4 message |
| UPDATE on a composite-PK table where `@lookupKey` covers only one of two PK columns | `UnclassifiedField` with Invariant #2 PK-coverage message listing the missing column |
| UPDATE on a composite-PK table where `@lookupKey` covers all PK columns | `MutationUpdateTableField` with `fieldBindings` carrying both bindings; emitter emits `.and(col1.eq(...), col2.eq(...))` |
| DELETE on a composite-PK table where `@lookupKey` covers all PK columns | `MutationDeleteTableField` with `fieldBindings` carrying all key bindings |
| `deleteFilm(in: FilmInput): ID @mutation(typeName: DELETE)` with `@lookupKey` | `MutationDeleteTableField` |
| `upsertFilm(in: FilmInput): ID @mutation(typeName: UPSERT)` with `@lookupKey` on only one column of a composite PK | `MutationUpsertTableField` (UPSERT is exempt from the full-PK coverage check) |
| `upsertFilm(in: FilmInput): ID @mutation(typeName: UPSERT)` with `@lookupKey` covering the full PK | `MutationUpsertTableField` |
| Any DML variant with `NestingField` in input | `UnclassifiedField` with Invariant #7 message |
| Any DML variant with a `NodeIdDecodeKeys`-extracted `ColumnField` in input (post-R50 successor of the retired `NodeIdField`) | `UnclassifiedField` with Invariant #8 message |
| Any DML variant with `ColumnReferenceField` or `CompositeColumnReferenceField` in input | `UnclassifiedField` with Invariant #9 message |
| Any DML variant with `CompositeColumnField` in input | `UnclassifiedField` with the deferred-composite-input message (post-R50) |
| DML variant with two `@table` input arguments | `UnclassifiedField` with Invariant #1 message |
| DML variant with a plain (non-`@table`) input arg | `UnclassifiedField` with Invariant #13 message |
| DML variant with listed input (`in: [FilmInput]`) | `UnclassifiedField` with Invariant #11 message |
| DML variant whose `@table` input arg carries `@condition` | `UnclassifiedField` with Invariant #12 message |
| DML variant with `@lookupKey` on a list-typed input field (e.g. `ids: [ID!]! @lookupKey`) | `UnclassifiedField` whose reason includes the `buildLookupBindings` per-field error |
| DML variant with non-`ID`/non-`T` return (e.g. `: Int`, `: Boolean`, `Connection<T>`) | `UnclassifiedField` with Invariant #14 message |
| DML variant targeting a `@node` table with composite key and `ScalarReturnType(ID)` return | classified successfully; `encodeReturn.paramSignature()` carries both key columns |
| DML variant targeting a non-`@node` table with `ScalarReturnType(ID)` return | `UnclassifiedField` with "no @node type is declared for table" message |
| DML variant targeting a non-`@node` (or composite-key) table with `TableBoundReturnType` return | classified successfully (`encodeReturn` is `Optional.empty()`; no node restriction applies) |
| `@service` + `@mutation` together | `UnclassifiedField` (existing mutual-exclusion check) |
| `@mutation` with no `typeName` argument | `UnclassifiedField` with existing "both absent" message — `classifyMutationInput` does not fire before this fallthrough |

---

### Phase 1B — Model alignment

**Status: ✅ Landed.** The model is now aligned with the *Generation-thinking*, *Narrow component types*, and *Classifier guarantees shape emitter assumptions* principles. Two changes, shipped in one commit:

1. The return-expression dispatch is pre-resolved into a sealed `DmlReturnExpression` sub-variant carried on `DmlTableField`, replacing the broad `(returnType, encodeReturn, payloadAssembly)` triple. Five arms cover the entire admitted return-type set: `EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`, and `Payload`.
2. Load-bearing classifier guarantees are tagged via `@LoadBearingClassifierCheck(key = "dml-mutation-shape-guarantees")` on `FieldBuilder.buildDmlField` and the matching `@DependsOnClassifierCheck` on `TypeFetcherGenerator.buildMutationDeleteFetcher`.

#### 1B.a. `DmlReturnExpression` sub-variant

The four DML emitters all dispatched on the same predicates over the same field: `f.returnType() instanceof ScalarReturnType("ID")` vs `TableBoundReturnType`, `f.returnType().wrapper().isList()`, and (post-R12) `f.payloadAssembly().isPresent()`. By the *Generation-thinking* rule ("if two generators branch on the same predicate over a model field, the branch belongs in the model"), the fork lives in `model/DmlReturnExpression.java`:

```java
public sealed interface DmlReturnExpression {
    record EncodedSingle(HelperRef.Encode encode) implements DmlReturnExpression {}
    record EncodedList(HelperRef.Encode encode)   implements DmlReturnExpression {}
    record ProjectedSingle(String returnTypeName) implements DmlReturnExpression {}
    record ProjectedList(String returnTypeName)   implements DmlReturnExpression {}
    record Payload(PayloadAssembly assembly)      implements DmlReturnExpression {}
}
```

- `Encoded*` arms cover `ScalarReturnType("ID")` returns. `HelperRef.Encode` already carries `(encoderClass, methodName, paramSignature)`; emitters resolve the projection columns from `paramSignature()` and the lambda from `encoderClass()` + `methodName()`.
- `Projected*` arms cover `TableBoundReturnType` returns. The GraphQL return-type name is the only per-arm data the emitter needs; the jOOQ table reference is `tia.inputTable()` (already on the variant) and the `<TypeName>Type.$fields(...)` class is derived from `returnTypeName` + the output package.
- `Payload` covers R12 `ResultReturnType` returns (single only; list payloads rejected at classify time per Invariant #14). Carries the `PayloadAssembly` recipe for the success-arm payload-class constructor call.
- Single vs list is encoded in the variant choice, not in a separate `wrapper().isList()` field.

`DmlTableField` shape went from (pre-1B):

```java
sealed interface DmlTableField extends MutationField {
    ReturnTypeRef returnType();
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
    Optional<HelperRef.Encode> encodeReturn();
    Optional<PayloadAssembly> payloadAssembly();
    SourceLocation location();
}
```

to (post-1B):

```java
sealed interface DmlTableField extends MutationField {
    DmlReturnExpression returnExpression();
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
    SourceLocation location();
}
```

The four DML records dropped `(ReturnTypeRef returnType, Optional<HelperRef.Encode> encodeReturn, Optional<PayloadAssembly> payloadAssembly)` and gained `DmlReturnExpression returnExpression`. Component count went from 8 to 6, the broad-`ReturnTypeRef` slot disappeared, and the `Optional` / `isPresent()` ceremony at every emitter site is gone.

> *Narrow component types:* the post-1B `returnExpression()` slot type is exactly what the classifier guarantees. The pre-1B `returnType: ReturnTypeRef` slot was broader than Invariant #14 (excluded `PolymorphicReturnType` and the list arm of `ResultReturnType`); the pre-1B `payloadAssembly` slot was a separate dispatch axis duplicating the return-type fork. Phase 1B closes both gaps.

#### 1B.b. Classifier change

`FieldBuilder.classifyMutationField` does the @node-type lookup for ID returns; `MutationInputResolver.validateReturnType` runs Invariant #14. `FieldBuilder.buildDmlField` now resolves the channel and the payload assembly, then calls a small private helper `buildDmlReturnExpression(returnType, encodeReturn, payloadAssembly)` that folds them into the `DmlReturnExpression` arm:

```java
private static DmlReturnExpression buildDmlReturnExpression(
        ReturnTypeRef returnType,
        Optional<HelperRef.Encode> encodeReturn,
        Optional<PayloadAssembly> payloadAssembly) {
    boolean isList = returnType.wrapper().isList();
    if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
        HelperRef.Encode enc = encodeReturn.orElseThrow(...);
        return isList ? new DmlReturnExpression.EncodedList(enc)
                      : new DmlReturnExpression.EncodedSingle(enc);
    }
    if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
        return isList ? new DmlReturnExpression.ProjectedList(tb.returnTypeName())
                      : new DmlReturnExpression.ProjectedSingle(tb.returnTypeName());
    }
    if (returnType instanceof ReturnTypeRef.ResultReturnType) {
        return new DmlReturnExpression.Payload(payloadAssembly.orElseThrow(...));
    }
    throw new AssertionError(/* Invariant #14 already rejected anything else */);
}
```

The mutation-arm switch passes `returnExpression` to the variant constructor instead of `(returnType, encodeReturn, payloadAssembly)`.

#### 1B.c. Load-bearing classifier annotations

Per *Classifier guarantees shape emitter assumptions*, the DML classifier produces three guarantees emitters consume without defensive checks:

1. **`returnExpression` matches the SDL return type.** `Encoded*` ⇒ `ScalarReturnType("ID")` and a populated `HelperRef.Encode`; `Projected*` ⇒ `TableBoundReturnType`; `Payload` ⇒ `ResultReturnType` and a populated `PayloadAssembly`. Emitters pattern-match without `instanceof`, `Optional.orElseThrow()`, or `payloadAssembly().isPresent()`.
2. **Input arg is a `TableInputType`.** Lets emitters cast `env.getArgument(tia.name())` to `Map<?, ?>` without an `instanceof` guard.
3. **Every input field is a `Direct`-extracted `ColumnField`.** Lets the column-list emission walk `tia.fields()` without dispatching on `extraction` or on input-field variants.

The producer annotation lands on `FieldBuilder.buildDmlField` (the site that resolves channel + assembly and calls `buildDmlReturnExpression`):

```java
@LoadBearingClassifierCheck(
    key = "dml-mutation-shape-guarantees",
    description = "Resolves DmlReturnExpression to one of five arms (EncodedSingle / "
        + "EncodedList / ProjectedSingle / ProjectedList / Payload), so DML emitters "
        + "pattern-match a single sealed dispatch with no instanceof ScalarReturnType, no "
        + "wrapper().isList() lookup, no Optional.orElseThrow() on the encode helper, and "
        + "no payloadAssembly().isPresent() predicate. Combined with the input-shape "
        + "invariants (Invariants #1 and #7-#13 in mutations.md), the entire DML emitter "
        + "branch is total without defensive checks.")
private GraphitronField buildDmlField(...)
```

`buildMutationDeleteFetcher` carries the matching consumer tag:

```java
@DependsOnClassifierCheck(
    key = "dml-mutation-shape-guarantees",
    reliesOn = "Pattern-matches f.returnExpression() with no instanceof / "
        + "Optional.orElseThrow / payloadAssembly().isPresent() guard; casts "
        + "env.getArgument(tia.name()) to Map<?,?> with no guard; walks tia.fields() "
        + "without an extraction-arm dispatch.")
private static MethodSpec buildMutationDeleteFetcher(...)
```

`LoadBearingGuaranteeAuditTest` enforces the producer/consumer pairing; the build fails if any consumer key has no producer. The annotation pair also gives find-usages navigation between the classifier check and every emitter that depends on it.

#### 1B.d. DELETE emitter migration

`buildMutationDeleteFetcher` now pattern-matches on `f.returnExpression()`. The per-shape bodies live in three private helpers:

```java
return switch (rex) {
    case DmlReturnExpression.EncodedSingle es ->
        emitDeleteEncoded(es.encode(), valueType, tableRef, tablesOnly, whereExpr, /*isList=*/ false);
    case DmlReturnExpression.EncodedList el ->
        emitDeleteEncoded(el.encode(), valueType, tableRef, tablesOnly, whereExpr, /*isList=*/ true);
    case DmlReturnExpression.ProjectedSingle ps ->
        emitDeleteProjected(ps.returnTypeName(), valueType, tableRef, tablesOnly,
            outputPackage, jooqPackage, whereExpr, /*isList=*/ false);
    case DmlReturnExpression.ProjectedList pl ->
        emitDeleteProjected(pl.returnTypeName(), valueType, tableRef, tablesOnly,
            outputPackage, jooqPackage, whereExpr, /*isList=*/ true);
    case DmlReturnExpression.Payload p ->
        emitDeletePayload(p.assembly(), valueType, tableRef, tablesOnly, whereExpr);
};
```

Single vs list is a parameter to the per-shape helper because only the terminal `.fetchOne` / `.fetch` differs.

#### 1B.e. Implementation sites

- New file `model/DmlReturnExpression.java` declaring the sealed interface and the five records.
- `MutationField.java`: dropped `returnType`, `encodeReturn`, and `payloadAssembly` from each DML record and from the `DmlTableField` interface; added `returnExpression`.
- `FieldBuilder`: `buildDmlField` resolves channel + assembly, then folds them into a `DmlReturnExpression` via the new private helper `buildDmlReturnExpression`. Wears `@LoadBearingClassifierCheck(key = "dml-mutation-shape-guarantees")`.
- `TypeFetcherGenerator.buildMutationDeleteFetcher`: pattern-matches on `f.returnExpression()` via the three `emitDelete*` helpers. Wears `@DependsOnClassifierCheck`.
- `MappingsConstantNameDedup.withResolvedChannel`: each DML arm rebuilt with the new component list (returnExpression, tableInputArg, channel).
- `GraphitronSchemaBuilderTest.rootFieldClassification`: assertions on `f.returnExpression()` matching the expected arm (`ProjectedSingle("Film")`, `Payload(assembly)`, etc.).
- `MutationDmlNodeIdClassificationTest`: `EncodedSingle` arm assertions against `__NODE_KEY_COLUMNS` metadata.
- The four `Mutation*TableFieldValidationTest` cases construct DML records with the new shape (using `DmlReturnExpression.ProjectedSingle("Film")` / `EncodedSingle(encode)`).

#### 1B.f. Tests

This phase is a model refactor, not a behaviour change. All pipeline / validation / generator tests pass against the new shape. `LoadBearingGuaranteeAuditTest` enforces the new annotation pairing.

---

### Phase 2 — INSERT emission

**Status: ✅ Landed.** `buildMutationInsertFetcher` shares the verb-neutral `buildDmlFetcher` (try/catch envelope, table-local declaration, `dsl` chain, `payload` bind, `returnSyncSuccess` / `catchArm`) and `emitDmlReturnExpression` (projection terminator pattern-matching the `DmlReturnExpression` arm) with DELETE. The verb-specific portion is just the chain that goes between `dsl\n` and `.returningResult(...)`:

```java
var dmlChain = CodeBlock.builder()
    .add(".insertInto($L, ", tableLocal).add(colList).add(")\n")
    .add(".values(\n").indent().add(valList).add(")\n").unindent()
    .build();
```

UPDATE / UPSERT will land against the same skeleton with their own `dmlChain` shape.

#### Emitter (`buildMutationInsertFetcher`)

Shape:

```java
public static Object createFilm(DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .insertInto(Tables.FILM, Tables.FILM.TITLE, Tables.FILM.LANGUAGE_ID, /* … */)
        .values(
            DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()),
            DSL.val(in.get("languageId"), Tables.FILM.LANGUAGE_ID.getDataType()),
            /* … */)
        .returningResult(/* per f.returnExpression() arm */)
        .fetchOne(/* per f.returnExpression() arm */);
}
```

- **Java return type:** `Object`. Same rationale as DELETE — graphql-java coerces; declaring `Object` sidesteps the ID-vs-Long mismatch and lets `Record` / `Result<Record>` flow through without signature churn.
- **Column list:** every `InputField.ColumnField` in `f.tableInputArg().fields()`, in declaration order. `@lookupKey` fields are included (`@lookupKey` is not treated specially on INSERT). No extraction-arm dispatch — Phase 1B's load-bearing guarantee is "every input field is `Direct`-extracted `ColumnField`".
- **Values list:** parallel to the column list. Each value is `DSL.val(in.get("<sdlFieldName>"), Tables.T.<col.javaName()>.getDataType())`. The two-argument form delegates coercion to the column's registered `Converter` at bind time. See the "Column value binding" convention in `rewrite-design-principles.adoc`.
- **Return expression dispatch:** pattern-match on `f.returnExpression()`. After Phase 1B the four arms are pre-resolved — no `instanceof ScalarReturnType` check, no `wrapper().isList()` lookup, no `Optional.orElseThrow()`:

  ```java
  switch (f.returnExpression()) {
      case DmlReturnExpression.EncodedSingle es -> /* .returningResult(es.encode().paramSignature()).fetchOne(r -> es.encode().encoderClass().<methodName>(r.get(...), ...)) */
      case DmlReturnExpression.EncodedList   el -> /* same projection, .fetch(...) terminator */
      case DmlReturnExpression.ProjectedSingle ps -> /* .returningResult(<TypeName>Type.$fields(env.getSelectionSet(), table, env)).fetchOne(r -> r) */
      case DmlReturnExpression.ProjectedList   pl -> /* same projection, .fetch(r -> r) terminator */
  }
  ```

  Single-vs-list differs only in the terminal `.fetchOne` / `.fetch`; the per-shape projection is shared between the two arms of each shape.

`buildMutationInsertFetcher` wears `@DependsOnClassifierCheck(key = "dml-mutation-shape-guarantees", ...)` per Phase 1B's annotation contract.

#### `RETURNING` with nested selections (`TableBoundReturnType`)

`Type.$fields` emits `DSL.multiset(...)` for child `TableField`/`NestingField` selections, which run as correlated subqueries. PostgreSQL supports subqueries inside `RETURNING`, so this shape works in principle, but no existing Graphitron fixture exercises `INSERT ... RETURNING (multiset)` yet. **DELETE landed without an execution test, so this is unverified for either verb.** The fall-back, if PostgreSQL rejects the shape, is "RETURNING PK only, then a follow-up SELECT from the same `dsl`" — same `dsl` keeps the read inside any caller-managed transaction. Document the choice in the landing commit.

If INSERT's pipeline test stays compile-only (no execution path against PostgreSQL), explicitly note the verification gap in the commit message; otherwise we ship two unverified `RETURNING` emitters in a row.

#### Implementation sites (landed)

- `buildMutationInsertFetcher(MutationField.MutationInsertTableField, String outputPackage, String jooqPackage)` in `TypeFetcherGenerator.java`. Switch arm dispatches to it.
- `MutationInsertTableField.class` is in `IMPLEMENTED_LEAVES` (removed from `STUBBED_VARIANTS`).
- `MutationInsertTableFieldValidationTest` flipped from `STUBBED` to `VALID` (mirror of `MutationDeleteTableFieldValidationTest`).
- `StubbedVariantPipelineTest`'s `mutationInsertOnATableType_surfacesStubbedError` was repurposed to UPDATE (still stubbed) — INSERT no longer surfaces a stub error.
- `needsGraphitronContextHelper` widened from `MutationDeleteTableField` to `DmlTableField` so INSERT (and future UPDATE / UPSERT) get the helper too.
- Verb-neutral `buildDmlFetcher`, `emitDmlReturnExpression`, `emitEncoded`, `emitProjected`, `emitPayload` extracted from DELETE's per-arm helpers; INSERT and DELETE both call them. UPDATE / UPSERT will reuse them in Phases 4 / 5.

#### Tests (landed)

**Execution:** `GraphQLQueryTest.createFilm_insertsRowAndReturnsProjectedFilm` against PostgreSQL verifies the `RETURNING $fields` projection shape end-to-end on the `Projected` arm. The test selects `title`, `languageId`, and `rentalRate` — the last is a DB-side default, so it also verifies that defaulted columns flow back through `RETURNING`. Resolves the verification gap DELETE shipped with. Fixture: `schema.graphqls` gained a `type Mutation { createFilm(in: FilmCreateInput!): Film @mutation(typeName: INSERT) }` and a corresponding `FilmCreateInput @table(name: "film") { title, languageId }`; only the two NOT-NULL-no-default columns on `film` are exposed (everything else has a DB default).

**Pipeline (deferred):** the negative cases the schema-builder coverage table calls for (`INSERT_MUTATION_NO_INPUT_ARG`, `INSERT_MUTATION_LISTED_INPUT`, `Int`-return rejection) are tracked in [Open decisions](#open-decisions). Cross-cutting negative-case coverage applies symmetrically to all four DML verbs and is best added once across the matrix.

---

### Phase 3 — DELETE emission

**Status: ✅ Landed (Phase 1B retrofit complete).** Initial emitter shipped in commit `31c64a2`. Phase 1B migrated it to pattern-match on `f.returnExpression()`; the per-shape bodies live in `emitDeleteEncoded` / `emitDeleteProjected` / `emitDeletePayload`. The emitter source carries `@DependsOnClassifierCheck(key = "dml-mutation-shape-guarantees", ...)` and reads no `instanceof ScalarReturnType` / `wrapper().isList()` / `payloadAssembly().isPresent()` predicate. The shape of emitted code is unchanged from pre-1B.

#### Shape of emitted method

```java
public static Object deleteFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .deleteFrom(Tables.FILM)
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> NodeIdEncoder.encodeFilm(r.get(Tables.FILM.FILM_ID)));
}
```

- **WHERE clause:** each `MapBinding` in `tia.fieldBindings()` contributes one `.eq(value)` predicate, combined with `.and(...)`. Single binding → `.where(col.eq(val))`; multiple → `.where(col1.eq(v1).and(col2.eq(v2)))`. `val` is `DSL.val(in.get(binding.fieldName()), Tables.T.COL.getDataType())` — the two-argument form that delegates coercion to the column's `Converter`.
- **Return expression:** today's emitter uses an `instanceof ScalarReturnType` switch on `f.returnType()`; Phase 1B replaces this with a pattern-match on `f.returnExpression()` (see Phase 2's INSERT spec for the post-1B shape).

#### Empty-match semantics

When the WHERE clause matches no row, `.fetchOne(...)` returns `null`. The emitted method's `Object` return flows that `null` through graphql-java unchanged. For a nullable `ID` return type, this surfaces as GraphQL null; for `ID!`, graphql-java raises a non-null violation at the protocol layer. This is the declared contract — schema authors using `ID!` on UPDATE/DELETE are asserting that the WHERE clause always matches, and the protocol-layer error is the right signal when it doesn't. Same applies to Phase 4.

#### Deferred / open

- **No execution test against PostgreSQL.** `RETURNING (multiset)` for `TableBoundReturnType` returns is unverified; same for the scalar-`ID` `encode<TypeName>` lambda. INSERT's landing should resolve this gap.
- **No production-schema gates.** The original Phase 1 plan called for grepping production schemas before merge to (a) confirm the `PlainInputArg` carve-out for Invariant #13 is not load-bearing and (b) sample return-type breakdowns to confirm the Invariant #14 strict gate doesn't swap rejection classes. Neither was done. INSERT is the highest-volume mutation rejection — these checks matter more for it than they did for DELETE.
- **Pipeline-test coverage is partial.** Three classifier scenarios shipped (`DELETE_MUTATION_FIELD`, `DELETE_MUTATION_NO_INPUT_ARG`, `DELETE_MUTATION_MISSING_LOOKUP_KEY`). Composite-PK happy path, missing-PK-column error, listed-input gate, `@condition` gate, deferred-input variants, non-`@node`-table-with-`ID`-return error, and most return-type rejections are not covered yet.

---

### Phase 4 — UPDATE emission

**Status: ✅ Landed.** `buildMutationUpdateFetcher` shares the verb-neutral `buildDmlFetcher` (try/catch envelope, table-local declaration, `dsl` chain, `payload` bind, `returnSyncSuccess` / `catchArm`) and `emitDmlReturnExpression` (projection terminator) with INSERT and DELETE. The verb-specific portion is the chain that goes between `dsl\n` and `.returningResult(...)`:

```java
var dmlChain = CodeBlock.builder()
    .add(".update($L)\n", tableLocal)
    .add(setClause.build())                      // .set(col, val) per non-@lookupKey field
    .add(".where(").add(buildLookupWhere(...)).add(")\n")
    .build();
```

UPSERT will land against the same skeleton with its own `dmlChain` shape (`.insertInto(...).values(...).onConflict(...).doUpdate().set(...)`).

#### Emitter (`buildMutationUpdateFetcher`)

```java
public static Object updateFilm(DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .update(Tables.FILM)
        .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(/* per f.returnExpression() arm */)
        .fetchOne(/* per f.returnExpression() arm */);
}
```

- **SET clause:** every `InputField.ColumnField` in `f.tableInputArg().fields()` whose `name()` is **not** the `fieldName()` of any `f.tableInputArg().fieldBindings()` entry contributes one `.set(col, val)`. Invariant #4 guarantees this set is non-empty. The `Direct`-only-extraction guarantee from Phase 1B's load-bearing check means no per-field extraction-arm dispatch.
- **WHERE clause:** `f.tableInputArg().fieldBindings()` entries, chained with `.and(...)` exactly as DELETE does.
- **Return expression dispatch:** pattern-match on `f.returnExpression()` per Phase 2's INSERT spec. Same four arms, same per-arm code shape.
- **Empty-match semantics:** `.fetchOne(...)` returns `null` when the WHERE clause matches no row, same as DELETE.

`buildMutationUpdateFetcher` lands wearing `@DependsOnClassifierCheck(key = "dml-mutation-shape-guarantees", ...)`.

#### Implementation sites

- Add `buildMutationUpdateFetcher(MutationField.MutationUpdateTableField, String outputPackage, String jooqPackage)` in `TypeFetcherGenerator.java`. Update the switch arm from `stub(f)` to the new call.
- Move `MutationUpdateTableField.class` from `STUBBED_VARIANTS` to `IMPLEMENTED_LEAVES`.
- Flip `MutationUpdateTableFieldValidationTest` from `STUBBED` to `VALID`.
- Optionally tighten `UPDATE_MUTATION_FIELD` in `GraphitronSchemaBuilderTest.rootFieldClassification`.

#### Tests

Pipeline: at minimum a UPDATE-with-no-non-`@lookupKey`-field rejection (Invariant #4) and a composite-PK happy path. Execution: `updateFilm(in: {filmId: 1, title: "Updated"})` asserts the title changed and the returned ID is 1.

---

### Phase 5 — UPSERT emission

**Status: After 1B.** Record + classifier already done; only the emitter and the partition flip remain.

#### Emitter (`buildMutationUpsertFetcher`)

jOOQ supports `INSERT ... ON CONFLICT ... DO UPDATE SET ...` via:

```java
dsl.insertInto(Tables.FILM, cols...)
   .values(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType()), /* … */)
   .onConflict(Tables.FILM.FILM_ID)   // @lookupKey columns from f.tableInputArg().fieldBindings()
   .doUpdate()
   .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
   .returningResult(/* per f.returnExpression() arm */)
   .fetchOne(/* per f.returnExpression() arm */);
```

- **`onConflict` columns:** `f.tableInputArg().fieldBindings()` entries.
- **Conflict action:** if `f.tableInputArg().fields()` has at least one `ColumnField` whose `name()` is not in any `fieldBindings()` entry, emit `.doUpdate().set(...)` over those non-`@lookupKey` fields. If every field is `@lookupKey` (no SET-clause fields), emit `.doNothing()` instead — jOOQ rejects a `doUpdate()` with zero `.set(...)` calls; `doNothing()` is the required API path for `INSERT ... ON CONFLICT DO NOTHING`.
- **INSERT column list:** every `InputField.ColumnField` in `f.tableInputArg().fields()` in declaration order, identical to INSERT's. Includes `@lookupKey` fields (they supply the user-provided PK on the insert branch). Auto-generated PKs that schema authors don't expose never produce a `ColumnField` and are therefore absent — the correct shape for `ON CONFLICT`.
- **Return expression dispatch:** pattern-match on `f.returnExpression()` per Phase 2's INSERT spec.

`buildMutationUpsertFetcher` lands wearing `@DependsOnClassifierCheck(key = "dml-mutation-shape-guarantees", ...)`.

PostgreSQL-only — `ON CONFLICT` is a Postgres extension; the rewrite targets PostgreSQL.

#### Implementation sites

- Add `buildMutationUpsertFetcher(MutationField.MutationUpsertTableField, String outputPackage, String jooqPackage)` in `TypeFetcherGenerator.java`. Update the switch arm.
- Move `MutationUpsertTableField.class` to `IMPLEMENTED_LEAVES`.
- Flip `MutationUpsertTableFieldValidationTest` from `STUBBED` to `VALID`.

#### Tests

Pipeline: at minimum the all-`@lookupKey` case (`doNothing()` path) and a partial-PK-coverage case (UPSERT exemption from Invariant #2). Execution: `upsertFilm(in: {filmId: 1, title: "Upserted"})` asserts correct insert-or-update semantics.

---

### Phase 6 — Service mutations

**Status: Landed.** Both `MutationServiceTableField` and `MutationServiceRecordField` un-stubbed by delegating to the shared `buildServiceFetcherCommon` helper that already handles `QueryServiceTableField` / `QueryServiceRecordField`. The R12 §3 try/catch wrapper, §5 Jakarta validation pre-step, and §2c `resultAssembly` success-arm assembly all carry over for free on the mutation side; the un-stub doubled as the R12 "DML-side wrapper integration" follow-up. Both emitters wear `@DependsOnClassifierCheck(key = "service-catalog-strict-service-return", ...)`. Both variants moved from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`; the corresponding `MutationServiceTableFieldValidationTest` / `MutationServiceRecordFieldValidationTest` flipped from `STUBBED` to `VALID`. New unit tests in `TypeFetcherGeneratorTest` cover: emission shape, `Result<Record>` declaration for list-cardinality table returns, typed-`fqClassName` declaration on the record variant, dispatch-arm wiring under a present `ErrorChannel`, redact-arm fallback under an absent channel, and the validator pre-step under a `ValidationHandler`. (Service variants don't carry `DmlReturnExpression`; their return shape comes from the developer method's reflected return type. Phase 6 was therefore independent of Phase 1B.)

**Reference shape (table variant).**

```java
public static Result<Record> activeRentals(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    return RentalService.createRentals(
        dsl,
        env.getArgument("customerId")
    );
}
```

Root mutation fields have no parent-batching context, so the helper's pass-`null`-for-`tableExpression` path applies (already exercised by the query-side service emitters).

---

## Consolidation

All three pieces this section originally listed have either shipped or have been replaced by a stronger principle-aligned alternative.

1. **`buildMutationReturnExpression` emitter helper — replaced by Phase 1B's model lift.** The original consolidation step proposed an emitter-side helper that ran the four-arm return-type switch once and shared it across INSERT/UPDATE/DELETE/UPSERT. Phase 1B does better: it pre-resolves the dispatch into a sealed `DmlReturnExpression` sub-variant on `DmlTableField` so each emitter pattern-matches on a pre-resolved variant with no `instanceof ScalarReturnType` / `wrapper().isList()` / `Optional.orElseThrow()` checks. Per the *Generation-thinking* principle — "if two generators branch on the same predicate over a model field, the branch belongs in the model" — the model lift is the principle-aligned form of this consolidation.

2. **`classifyMutationInput(fieldDef, verb)` helper in `FieldBuilder` — ✅ shipped (R50 cleanup).** Single helper handles all four DML verbs; per-verb branching lives inside via the typeName parameter.

3. **`MutationField.DmlTableField` sealed supertype — ✅ shipped (R50 cleanup).** Permits the four DML records and declares the common accessors. Phase 1B narrows the accessor set from `(returnType, tableInputArg, encodeReturn, location)` to `(returnExpression, tableInputArg, location)`.

---

## Non-goals

- **Listed inputs** (`in: [FilmInput]`): the `list` flag on `TableInputArg` causes a classifier
  gate (Invariant #11).  Unblocking listed inputs requires iterating over the input list and
  constructing a batch INSERT / batch UPDATE / DELETE / UPSERT.  The legacy code uses jOOQ
  `batchInsert` / `batchUpdate` / `batchDelete` / `batchStore` for this.  Tracked as a follow-up.
- **Nested input types** (`NestingField`): deferred per Invariant #7.
- **`@nodeId`-typed input fields** (`NodeIdDecodeKeys`-extracted `ColumnField` post-R50): deferred (Invariant #8). Lifting the gate requires emitting the per-row decode lambda before the WHERE/SET binding.
- **`ColumnReferenceField` / `CompositeColumnReferenceField` in mutation inputs**: deferred (Invariant #9).
- **`CompositeColumnField` in mutation inputs**: deferred (Invariant #10).
- **Non-`TableInputArg` arguments on DML fields**: deferred (Invariant #13).  A future plan could
  admit scalar context arguments alongside the `@table` input (e.g. a `reason: String` audit field
  routed to a column not in the input type); today we reject them rather than invent a precedence
  story.
- **Build-time INSERT column-coverage validation** (Invariant #6): deferred until the jOOQ
  catalog reliably exposes NOT-NULL + default metadata through the generator's `JooqCatalog`
  layer.  Today the DB rejects incomplete INSERTs at execute time; the rewrite does not try
  to beat the DB to the punch.  Promote to Active if three or more production rejections trace
  back to runtime incomplete-INSERT errors that a classifier check would have caught cheaply.
- **Non-`ID` / non-`TableBoundReturnType` return types on DML fields** (Invariant #14): deferred.
  The previous position (emit `DSL.val(1)` as an affected-row sentinel) is withdrawn: the
  semantics differ between INSERT (always 1) and UPDATE/DELETE (actual affected count), and
  `returningResult(DSL.val(1))` cannot express either honestly.  When a consumer needs an `Int`
  return, a follow-up plan will add an `AffectedCount` arm to `DmlReturnExpression` that emits
  `.execute()` (returns affected-row count) and skips `returningResult` entirely.
- **`ScalarReturnType(ID)` on non-`@node` tables**: gated at classifier time with a descriptive
  message.  A follow-up could add a raw-PK fallback (no NodeId encoding) for tables that lack
  `__NODE_TYPE_ID` metadata, but the common case is that mutation target tables are node types.
- **Transaction wrapping**: the emitted DML statements run within whatever transaction context
  the caller provides via `dsl`.  Explicit transaction wrapping (e.g. `dsl.transactionResult(...)`)
  is not added here; service variants already control their own transactions through the
  developer-supplied method.
- **Non-PostgreSQL dialects**: `RETURNING` and `ON CONFLICT DO UPDATE` are PostgreSQL-specific.
  The rewrite targets PostgreSQL; no cross-dialect abstraction is added.
- **`@mutation` + `@service` combination**: already rejected at classifier time by `classifyMutationField`'s mutual-exclusion guard.

---

## Open decisions

- **`RETURNING (multiset)` on PostgreSQL for `TableBoundReturnType` returns** — *unresolved*.
  DELETE shipped without an execution test, so neither `DELETE … RETURNING (multiset)` nor
  `INSERT … RETURNING (multiset)` is verified yet. The landing-commit fallback rule still applies
  ("RETURNING PK, then a follow-up SELECT on the same `dsl`"), but we now have two emitters
  potentially carrying the same unverified shape. INSERT's pipeline should resolve this for both
  by adding the first execution test; if Postgres rejects the multiset shape, the fall-back lands
  in INSERT and DELETE gets an immediate follow-up.
- **Production-schema gates skipped for DELETE** — *carry forward to INSERT*. mutations.md
  originally required two pre-merge greps:
  (a) Invariant #13 compatibility check — does any production DML mutation pair the `@table`
      input arg with a separate non-`@table` input object that the legacy classifier silently
      skipped? If so, the strict rejection is a breaking change and the plan needs a narrow
      carve-out.
  (b) Invariant #14 numbers check — sample the SDLs producing `Mutation insert/update/delete/
      upsert not yet implemented` rejections and confirm the majority return `ID`/`[ID]`/`T`/`[T]`.
      If a material slice returns `Int`/`Boolean`, the strict gate just swaps rejection classes
      rather than closing them, and the `.execute()`-based arm needs to land in the same pass.
  Neither was done for DELETE. INSERT is the highest-volume rejection, so these checks matter
  more for it; record sample sizes and decisions in INSERT's landing commit.
- **Transactional integrity for multi-step mutations** (e.g. INSERT + immediate re-fetch) —
  unchanged. Both calls share the same `dsl` obtained from `GraphitronContext`, which may or
  may not be inside an active transaction depending on the caller's setup. Document this as the
  caller's responsibility; no wrapper is added in this plan.
- **DELETE inputs with non-`@lookupKey` `ColumnField` entries are silently ignored** —
  *resolve before UPDATE lands*. Today's DELETE classifier walks `tia.fields()` and only the
  `@lookupKey`-bound subset (`fieldBindings`) reaches the emitter; any other `ColumnField` on
  the input type sits unused. The schema author most likely intended for those fields to
  participate in the WHERE clause, but the current contract treats them as no-ops. UPDATE
  doesn't have this question (non-`@lookupKey` fields go in the SET clause) and INSERT doesn't
  either (every field goes in the column list), so DELETE is the variant where this needs an
  explicit answer. Options: (a) reject at classifier time ("DELETE input types may only carry
  `@lookupKey` fields"); (b) admit them as additional WHERE predicates; (c) keep the silent
  ignore. Pick before UPDATE lands so the consolidation phase has a consistent contract to
  generalise over; (a) reads cleanly to schema authors and is the recommended default.
- **Promoting `ArgumentRef` to `model/`** — *deferred*. `ArgumentRef.InputTypeArg.TableInputArg` is now public and crosses the model/generator boundary as a component of `MutationField.DmlTableField`; documented as the only argument-classification type that does so. Promoting the wider `ArgumentRef` hierarchy into `model/` is a possible follow-up but needs a real driver — keep it deferred until a second non-mutation call site needs it.
