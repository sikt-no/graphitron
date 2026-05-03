---
id: R70
title: "Support TableRecord-keyed Map returns on @service rows methods"
status: Spec
bucket: feature
priority: 5
theme: service
depends-on: [emit-record1-keys-instead-of-row1]
---

# Support TableRecord-keyed Map returns on @service rows methods

## Motivation

A child `@service` whose SOURCES parameter is typed `Set<X>` (X a jOOQ `TableRecord` subtype) classifies as `BatchKey.MappedRowKeyed` today; the variant's docstring says so explicitly: "Both `Set<RowN<...>>` and `Set<TableRecord>` classify here; the DataLoader key type stays `RowN` regardless of the user's declared element type" (`BatchKey.java:233-239`). The classifier accepts the typed-record shape on the input side, but the symmetric output side is not accepted: `RowsMethodShape.outerRowsReturnType` always builds the expected return type as `Map<RowN<...>, V>` from `BatchKey.keyElementType()` (`RowsMethodShape.java:85-96`), so the strict `TypeName.equals` check in `ServiceDirectiveResolver.validateChildServiceReturnType` (`ServiceDirectiveResolver.java:276-293`) rejects the natural counterpart `Map<X, V>` where the developer reused the same `TableRecord` across the parameter and the map key.

The motivating real-world fixture, surfaced from a consumer schema (`regelverk_exp.graphqls`):

```java
public Map<KvotesporsmalRecord, OversatteTekster>
        kvotesporsmalNavn(Set<KvotesporsmalRecord> keys) { ... }
```

is rejected with:

> method `kvotesporsmalNavn` ... must return `Map<Row3<String, String, String>, OversatteTekster>` ... got `Map<KvotesporsmalRecord, OversatteTekster>`

forcing the consumer to either rewrite the service in terms of `Row3<...>` (losing the typed-record ergonomics: the parent's PK is then a triple of strings rather than a typed `KvotesporsmalRecord`) or drop down to `Set<RowN<...>>` on the parameter and keep the asymmetry consistent. Both options are worse than letting the consumer write the typed-record shape end-to-end.

The emitter side has the same gap, even for the parameter half that classify already accepts. `TypeFetcherGenerator.buildServiceRowsMethod` (`TypeFetcherGenerator.java:2392-2435`) emits

```java
return ServiceClass.method(keys);
```

against a local `keys` whose declared type is `Set<RowN<...>>` (or `Set<RecordN<...>>` for the `MappedRecordKeyed` arm). When the developer's method signature says `Set<TableRecord>` instead of `Set<RowN<...>>`, that emission miscompiles at the Java call site. The R32 changelog entry calls this out as a known follow-up:

> element-shape conversion when the developer's `Sources` is `Set<TableRecord>` / `List<TableRecord>` (deferred until a real schema needs it; builds on top of R61)

The motivating schema is the "real schema" the deferral was waiting on. R70 ships the conversion at the rows-method body and the symmetric validator acceptance, so the typed-record shape works end-to-end on both halves.

## Why this depends on R61

R61 (`emit-record1-keys-instead-of-row1.md`) flips the framework's emitted DataLoader key type from `RowN<...>` to `RecordN<...>` for the five row-keyed `BatchKey` arms (`RowKeyed`, `MappedRowKeyed`, `LifterRowKeyed`, `AccessorRowKeyedSingle`, `AccessorRowKeyedMany`). That flip is the prerequisite for ergonomic `RowN ↔ TableRecord` conversion at the rows-method body, because `RecordN` exposes per-column value accessors (`record.value1()`, ...) and `record.into(Table)` for typed projection back into a `TableRecord`, while `Row1<T>` / `Row3<...>` are jOOQ SQL-expression types with no application-side accessors. The R61 plan-body §"open question" already commits to keeping `RowN` cardinality at the WHERE-clause boundary (where jOOQ's planner needs it for tuple-IN); only the developer-facing rows-method-parameter type changes.

R70 then writes the conversion using the value accessors R61 makes available. Implementing R70 before R61 would force a `record.field1()` lookup on every key plus a `(Class<T>) field.getType()`-style cast trail, neither of which is robust against arity changes or column-class drift in the catalog. The R61 → R70 sequence keeps each step's emission small and lets the conversion code read as if it were hand-written.

## Design

### Validator: accept the symmetric `TableRecord` map-key shape

`RowsMethodShape.outerRowsReturnType` is the single point of truth for "the rows-method's expected outer return type" (validator and emitter both call it; the `service-directive-resolver-strict-child-service-return` `@LoadBearingClassifierCheck` pairs them). The validator currently passes the per-key `V` from `RowsMethodShape.strictPerKeyType` and gets back exactly one expected `TypeName`; mismatches reject.

Lift the validator from "exactly one expected type" to "an enumerated set of accepted shapes" for the mapped arms. Two key shapes are accepted when `batchKey instanceof BatchKey.MappedRowKeyed mrk`:

1. The framework-internal shape: `Map<keyElementType, V>` (or `Map<keyElementType, List<V>>` for list cardinality). Today's behaviour, unchanged.
2. The symmetric typed-record shape: `Map<X, V>` where `X` is the concrete `TableRecord` for the parent table backing `mrk.parentKeyColumns()`. Same list-cardinality wrapping rule.

Both shapes are accepted because `BatchKey.MappedRowKeyed` already accepts both `Set<RowN<...>>` and `Set<TableRecord>` on the parameter side. The validator's job is to mirror the parameter classifier's tolerance on the return side, not to force the developer to pick one.

To resolve "the concrete `TableRecord` for the parent table backing the PK columns," the validator needs the parent table reference. Today `BatchKey.MappedRowKeyed` carries only `parentKeyColumns: List<ColumnRef>`, and `ColumnRef` itself does not name its parent table (`ColumnRef.java:20`). The simplest plumbing is to thread the parent `TableRef` through the validator's signature alongside the existing `(returnType, method)` pair: `ServiceDirectiveResolver.resolve()` already knows the parent type (`parentTypeName: String` parameter, `parentPkColumns: List<ColumnRef>` carrying the PK), and `BuildContext` exposes the catalog mapping to a `TableRef` from a parent type name. Pass the parent `TableRef` (or `null` for `@record`-typed parents where it is N/A) into `validateChildServiceReturnType`; let `RowsMethodShape.outerRowsReturnTypeChoices` (a new method, sibling to `outerRowsReturnType`) build a small ordered list of `TypeName`s the validator will iterate against `method.returnType().equals(...)` until one matches.

The same lift applies to `MappedRecordKeyed` (Set<RecordN<...>> → Set<TableRecord> equivalence is the same idea) and to the positional arms `RowKeyed` / `RecordKeyed` for completeness, since the developer can declare `List<X>` / `List<List<X>>` parameter shapes too. The cross-product is small (4 row-or-record-keyed arms × {framework-internal shape, typed-record shape} × {single, list}) and lives entirely in `RowsMethodShape.outerRowsReturnTypeChoices`.

The validator's rejection message changes from "must return X" to "must return one of: X, Y" so the developer sees both accepted shapes in the error. The validator's `service-directive-resolver-strict-child-service-return` `@LoadBearingClassifierCheck` description is updated to note the multi-shape acceptance.

### Emitter: convert `Set<RowN>` → `Set<TableRecord>` and `Map<TableRecord, V>` → `Map<RowN, V>`

`TypeFetcherGenerator.buildServiceRowsMethod` is the single emit site for child-`@service` rows methods. It currently emits:

```java
public static Map<RowN<...>, V> rowsMethodName(Set<RowN<...>> keys, DataFetchingEnvironment env) {
    return ServiceClass.method(keys);
}
```

R70 makes the emitter inspect `method.returnType()` and `method.params()` to detect when the developer wrote the typed-record shape on either side. Three cases:

1. **Both halves typed-record** (motivating case): emit pre-conversion of `keys` into a typed local `Set<X> recordKeys` and post-conversion of the returned `Map<X, V>` back into `Map<RowN<...>, V>`. The pre-conversion uses `keys.stream().map(k -> Tables.X.newRecord(k.value1(), k.value2(), ...)).collect(toSet())` (post-R61, where `k` is a `RecordN<...>` with value accessors); the post-conversion uses `result.entrySet().stream().collect(toMap(e -> DSL.row(e.getKey().fieldN()...), Map.Entry::getValue))` (or `e.getKey().into(Tables.X.PK_COL_1, ...)` if a typed-row form fits cleanly).
2. **Parameter typed-record, return framework-internal**: pre-conversion only.
3. **Parameter framework-internal, return typed-record**: post-conversion only.

The cross-product is encoded as a single sealed `RowsCallShape.{PassThrough | ConvertParam | ConvertReturn | ConvertBoth}` whose construction is a pure function of `(method.returnType(), method.params())` and the parent `TableRef`; the emitter switches on the variant and emits the right pre/post blocks. The shape construction lives in `model/RowsMethodShape.java` (so the validator's "what shapes are accepted" question and the emitter's "what conversion does each accepted shape need" question share a derivation path) and feeds an emit-side helper in `generators/RowsCallEmitter.java` (a new file, because `TypeFetcherGenerator.buildServiceRowsMethod` is already long enough that adding three branches inline would tip it over R6's "this method should be its own class" threshold). A `@DependsOnClassifierCheck` pairs the emitter with the new shape variants on the validator side.

The `Set<X>` ↔ `Map<X, V>` post-conversion's `DSL.row(record.field1(), ...)` call uses the column tuple from `mrk.parentKeyColumns()` to project the `TableRecord` back into a `RowN`; the column ordering matches the framework's expected `keyElementType()` ordering exactly (it is the same list, by construction).

### What stays unchanged

- The DataLoader key type is still `RowN<...>` (post-R61: `RecordN<...>`). The R70 conversion is purely application-side, inside the rows-method body, and never reaches the `DataLoaderFactory` boundary. The DataLoader's hashing contract (a stable, value-equality-keyed type) remains the framework's concern.
- The classifier's parameter-side acceptance of `Set<TableRecord>` (lines 626-630 of `ServiceCatalog.classifySourcesType`) is unchanged; today it succeeds in classify but miscompiles in emit, R70 closes the latter half.
- `BatchKey.parentKeyColumns()` is unchanged; the `TableRef` plumbing is added alongside, not into, the variant.
- Root-level `@service` validation (the `computeExpectedServiceReturnType` arm in `ServiceDirectiveResolver.java:220-249`) is unchanged; root has no DataLoader and no rows method, so the "TableRecord vs RowN" question doesn't exist there.

## Implementation

### Phase 1 — validator-side multi-shape acceptance (no emitter change yet)

- Add `RowsMethodShape.outerRowsReturnTypeChoices(perKey, returnType, batchKey, parentTable)` returning `List<TypeName>` of accepted outer types. For non-mapped variants and for `parentTable == null` it returns the singleton `[outerRowsReturnType(...)]` (today's shape); for mapped variants with a non-null `parentTable` it returns `[framework-internal shape, typed-record shape]`.
- Update `ServiceDirectiveResolver.validateChildServiceReturnType` to iterate the choices and build a "must return one of: X, Y" message on no-match. Thread the parent `TableRef` from `BuildContext` through the validator entry point.
- Tag the new acceptance with the `service-directive-resolver-strict-child-service-return` `@LoadBearingClassifierCheck`'s existing key (the description is rewritten; the key stays so paired emitter sites continue to refer to the same contract).
- **Tests**: Pipeline-tier (`GraphitronSchemaBuilderTest`) — add a positive cell exercising the typed-record map-key shape on `MappedRowKeyed` ("Set<TableRecord> + Map<TableRecord, V>" classifies cleanly to the existing `ServiceTableField` / `ServiceRecordField` arm); refresh the existing `CHILD_SERVICE_TABLE_BOUND_WRONG_RETURN_REJECTED` and `CHILD_SERVICE_SCALAR_WRONG_VALUE_TYPE_REJECTED` assertions to the new "must return one of: ..." wording. Validator-tier (`ServiceFieldValidationTest.java` and siblings) — one positive cell for each mapped variant covering the typed-record shape; each rejection cell continues to exercise a genuinely-mismatched shape (e.g. wrong V-type, wrong cardinality wrap).
- After Phase 1 ships, the validator no longer rejects the motivating fixture; the emitter still produces a miscompiling rows-method body; the build fails at the compile-spec tier (`graphitron-sakila-example` against real jOOQ) on the unmodified `return ServiceClass.method(keys)`. The strict ordering of phases keeps the pipeline tier honest before the compile tier closes, but the trunk-is-buildable invariant requires Phase 2 to land in the same trunk-bound push as Phase 1.

### Phase 2 — emitter-side `RowsCallShape` conversion

- Add `RowsCallShape` sealed in `model/`: `{PassThrough | ConvertParam(parentTable, recordType) | ConvertReturn(parentTable, recordType) | ConvertBoth(parentTable, recordType)}`. Construction is a pure function of `(method.returnType(), method.params(), batchKey, parentTable)`; tested at unit-tier with a small cross-product.
- Add `generators/RowsCallEmitter.buildCall(...)` returning `CodeBlock` for the body, taking the resolved `RowsCallShape`. `PassThrough` emits the existing one-liner; the three convert arms emit pre/post blocks against the `Set<X> recordKeys = ...` and `Map<X, V> result = ...` locals.
- `TypeFetcherGenerator.buildServiceRowsMethod` resolves the shape and delegates the body to `RowsCallEmitter`; the surrounding `methodBuilder.addModifiers / .returns / .addParameter` plumbing stays.
- The `@DependsOnClassifierCheck` on `buildServiceRowsMethod` gains a second `reliesOn` line covering the multi-shape acceptance the validator now provides.
- **Tests**: Pipeline-tier — add structural coverage that the emitted method body contains the right `Set<X> recordKeys = keys.stream()...` shape for each `RowsCallShape` arm (no body-string assertions; assert at the `MethodSpec.code()` block level via JavaPoet's structural traversal, the same shape `TypeFetcherGeneratorTest` uses today). Compile-tier — add a fixture in `graphitron-sakila-example` whose `@service` rows method writes `Map<X, V> method(Set<X>)` for some sakila `TableRecord`; the existing `mvn compile -pl :graphitron-sakila-example -Plocal-db` gate then catches any miscompile. Execution-tier — extend `GraphQLQueryTest` with one query that exercises the typed-record path end-to-end against PostgreSQL, mirroring `films_titleUppercase_resolvesViaServiceRecordFieldDataLoader`'s shape.

### Phase 3 — fixture parity sweep

- The R32 changelog entry's "open follow-ups (deferred or tracked elsewhere)" calls out the deferral for `Set<TableRecord>` / `List<TableRecord>` developer signatures; once Phase 2 ships the deferral closes. Update the changelog to point at R70's landing commit (the changelog is append-only; R70 gets one entry summarising both phases on Done). The R32 follow-up bullet stays in place for historical accuracy; R61's "out of scope" bullet pointing at this item gets a "see R70" link.
- `TestServiceStub`'s `getFilmsWithSetOfTableRecordSources` fixture currently exists only for parameter-side classification and throws on call. Once R70 ships, the same fixture is reused for the emit-side conversion test by adding one more stub method `Map<FilmRecord, String> filmTitleUppercaseRecordKeyed(Set<FilmRecord> keys)` on the existing dummy service class; both halves of the conversion are then under test from a single fixture.

## Tests

The phased plan above lists tests inline. Aggregated by tier:

- **Unit (L1)** — `RowsCallShapeTest`: cross-product of `(returnType, params, batchKey)` → expected variant. `RowsMethodShape.outerRowsReturnTypeChoicesTest`: choices size and ordering for each of the four mapped/positional `BatchKey.ParentKeyed` variants × `(parentTable null vs non-null)`.
- **Pipeline (L4)** — `GraphitronSchemaBuilderTest`: positive cell per mapped variant × typed-record vs framework-internal map-key shape; refreshed rejection-message wording on the two existing rejection cells. `TypeFetcherGeneratorTest`: structural body coverage per `RowsCallShape` variant.
- **Compile (L5)** — `graphitron-sakila-example` fixture: one `@service` rows method using `Map<TableRecord, V> method(Set<TableRecord>)`; the existing `mvn compile -Plocal-db` gate catches any miscompile.
- **Execution (L6)** — `GraphQLQueryTest`: one query exercising the typed-record path end-to-end against the sakila PostgreSQL fixture.

## Open questions

1. **Single-cardinality TableRecord parameter shape.** R70 covers `Set<X>` (mapped) and `List<X>` (positional). A future single-cardinality `@service` (`X method(X parentRecord)`) would also benefit from the same typed-record shape on the parameter side, but the rows-method shape there is positional `List<V>` / `List<List<V>>`, not `Map`, and the parent-key extraction emits `loader.load(key, env)` rather than batched fan-out. Confirm during Phase 2 whether the same `RowsCallShape` resolution covers single cardinality cleanly or whether single-cardinality is a separate item.
2. **Custom-scalar V-types in the typed-record map.** `RowsMethodShape.strictPerKeyType` returns `null` for custom GraphQL scalars and enums (the typed-context-value-registry from R45 fills that in later). Today the validator skips the strict check on null; under R70's multi-shape acceptance, the skip arm extends to the typed-record shape too — i.e. neither shape is strict-checked, both are emitted as-passing. This is consistent with today's contract and does not regress, but it means a developer writing `Map<X, MyCustomScalar>` against a custom-scalar field gets no validator help; the failure surfaces at compile-tier as a javac error on the generated `return service.method(...)` line. Acceptable until R45 lands.
3. **`MappedRecordKeyed` + `Set<TableRecord>` parameter classifier path.** Today `ServiceCatalog.classifySourcesType` maps `Set<TableRecord>` to `MappedRowKeyed` (line 628-629), not `MappedRecordKeyed`. The classification choice is structural (the parameter element is a `TableRecord`, not a `RecordN<...>`), so the typed-record shape always lands on the row-keyed arm by construction; `MappedRecordKeyed` is only reached when the developer explicitly writes `Set<RecordN<...>>`. R70 therefore touches `MappedRecordKeyed` only for the framework-internal-vs-typed-record symmetric acceptance on the return side; the parameter-side classification is unchanged. Document this asymmetry in `BatchKey.MappedRecordKeyed`'s javadoc on landing.

## Roadmap entries (siblings / dependencies)

- **Depends on** [`emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md) (R61): R61 ships the `RecordN` keys whose value accessors R70's conversion code reads from. Without R61, the conversion would have to either fish column values out via `record.field1()`-style reflection on a `Row1` (no application-side accessor) or duplicate the `record.into(...)` form across the framework and the developer side.
- **Closes deferral from** R32 (changelog entry under `service-rows-method-body`, commits `64b8e2c` + `e28540b` + `83bcfdf`): R32's "element-shape conversion when the developer's `Sources` is `Set<TableRecord>` / `List<TableRecord>` (deferred until a real schema needs it; builds on top of R61)" bullet is the open follow-up R70 closes.
- **Coordinates with** [`typed-context-value-registry.md`](typed-context-value-registry.md) (R45): when R45 surfaces typed Java classes for custom GraphQL scalars and enums, the `strictPerKeyType` null arm shrinks; R70's multi-shape acceptance reuses whatever R45 produces without further work.
