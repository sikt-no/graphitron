# Mutation bodies

> **Status:** Spec
>
> Lift all six mutation leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`:
> `MutationField.MutationInsertTableField`, `MutationUpdateTableField`,
> `MutationDeleteTableField`, `MutationUpsertTableField`,
> `MutationServiceTableField`, `MutationServiceRecordField`.
> Closes generator stub #4 — the highest-aggregate production rejection (131 combined).

---

## Current state

### Model

`MutationField.java` (`model/MutationField.java`) declares six permits.
The four DML variants carry only `(parentTypeName, name, location, returnType)` — no classified
arguments, no target-table reference.  The two service variants additionally carry `MethodRef
method` (already populated by `classifyMutationField`).

### Classifier

`FieldBuilder.classifyMutationField` (`FieldBuilder.java:1509`) already routes mutation fields:
`@service` → service variants; `@mutation(typeName: ...)` → INSERT / UPDATE / DELETE / UPSERT
switch.  For the four DML variants, the classifier resolves `returnType` but does **not** classify
the field's arguments.  The arguments carry the `@table` input type that identifies the target
table and the column bindings — this data is currently discarded at classify time.

### Generator stubs

`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` has entries for all six variants
(`:226-237`); `generateTypeSpec` routes each to `stub(f)` (`:347-352`).

### Neighbouring references

- `ArgumentRef.InputTypeArg.TableInputArg` — carries `inputTable` (`TableRef`), `fieldBindings`
  (`List<InputColumnBinding>`, `@lookupKey`-bound columns), `fields` (`List<InputField>`, all
  input fields).  Already populated by `FieldBuilder.classifyArgument` (`FieldBuilder.java:697-702`)
  for `@table` input types.
- `InputField.ColumnField` — carries `name` (GraphQL field name), `column` (`ColumnRef` with
  `sqlName`, `javaName`, `columnClass`), and `nonNull`.  The `javaName` field is the jOOQ column
  constant (e.g. `CUSTOMER_ID`).
- `InputColumnBinding` — maps a `@lookupKey` input field name to a `ColumnRef` and a
  `CallSiteExtraction`.  This is the same binding the argres pipeline uses for query lookups;
  for mutations it identifies the WHERE-clause columns.
- `GeneratorUtils.ResolvedTableNames` — resolves `tablesClass`, `jooqTableClass`, `typeClass` from
  a `TableRef`.  Used by every SQL-generating emitter.
- `plan-service-root-fetchers.md` — adds `buildMethodBackedCallArgs`, a declaration-order
  param-list emitter needed by the service-mutation variants.  The mutations plan
  **depends on that plan landing first** for Phases 2 and 5–6.

---

## Invariants

1. **Each DML mutation has exactly one `@table` input argument.**  This is the record that
   drives the DML statement.  If no `@table` input arg is found during classification, produce
   `UnclassifiedField` with a descriptive reason.  Multiple `@table` input args are also
   rejected at classifier time (`"@mutation field has more than one @table input argument"`).

2. **UPDATE and DELETE require at least one `@lookupKey` binding.**  `fieldBindings` must be
   non-empty; if empty, return `UnclassifiedField` with
   `"@mutation(typeName: UPDATE/DELETE) requires at least one @lookupKey field in the input type"`.

3. **UPSERT requires at least one `@lookupKey` binding** (the ON CONFLICT key), same gate as #2.

4. **UPDATE requires at least one non-`@lookupKey` field.**  If every `ColumnField` in `tia.fields()`
   has its name in `tia.fieldBindings()`, the SET clause would be empty and the statement degenerates.
   Gate at classifier time: `"@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"`.
   UPSERT is exempt: an UPSERT whose SET clause is empty is semantically `INSERT ... ON CONFLICT DO NOTHING`,
   which is a legitimate pattern; UPSERT's non-empty-SET check is therefore not enforced here.

5. **INSERT does not require `@lookupKey`.**  `fieldBindings` may be empty for INSERT.

6. **INSERT column coverage is a runtime contract, not a classifier invariant.**  The classifier
   does **not** verify that every NOT-NULL, no-default column of the target table is covered by an
   input field.  The target DB rejects the INSERT at execute time if coverage is incomplete; this
   is the declared contract.  Rationale: computing "NOT-NULL without default" from the jOOQ catalog
   at classify time is feasible but non-trivial (default metadata is not always preserved through
   the generator) and the DB error is actionable.  A follow-up item may promote this to a classifier
   gate once the catalog metadata is reliably available; tracked in Non-goals.

7. **Nested `@table` input fields (`InputField.NestingField`) are deferred.**  If any field in
   `tia.fields()` is a `NestingField`, emit an `UnclassifiedField` at classify time
   (`"nested input types in @mutation fields are not yet supported"`).  This keeps the initial
   scope flat; nesting can land in a follow-up.

8. **`InputField.PlatformIdField` in a mutation input is deferred.**  Same gate:
   `"PlatformIdField in @mutation inputs is not yet supported — use @nodeId"`; tracked under the
   `plan-nodeid-directives.md` mutation-binding step.

9. **`InputField.ColumnReferenceField` (cross-table reference) in a mutation input is deferred.**
   Gate: `"ColumnReferenceField in @mutation inputs is not yet supported"`.

10. **Listed inputs (`in: [CustomerInputTable]`) are deferred for Phase 1.**  The initial
    implementation handles only single-record inputs.  Listed-input support is a follow-up
    (see Non-goals).

11. **Non-`TableInputArg` arguments on DML `@mutation` fields are rejected.**  `classifyMutationInput`
    (see Phase 1b) returns `UnclassifiedField` if any argument is anything other than a
    `TableInputArg`, including `PlainInputArg`, scalar args, pagination args, orderBy args.
    Mutations have no parent table context, so those argument shapes are nonsensical here and
    would mis-classify against a sentinel `TableRef` if fed through the generic classifier.
    Message: `"@mutation fields only accept @table input arguments; found '<argName>' of shape '<ArgumentRef variant>'"`.

12. **DML `@mutation` return type must be `ID` (Single), `T` (Single of `TableBoundReturnType`),
    or `[T]` (List of `TableBoundReturnType`).**  Other shapes (`Int`, `Boolean`, any non-`ID`
    scalar, `PolymorphicReturnType`, `ResultReturnType`, or `Connection` wrapper) are rejected
    at classifier time with
    `"@mutation(typeName: INSERT|UPDATE|DELETE|UPSERT) return type '<T>' is not yet supported; use ID or a @table type"`.
    Rationale: `.returningResult(...)` cannot honestly express an affected-row count (the semantics
    users typically expect from `Int` / `Boolean` returns), and polymorphic / result-record returns
    would require projection bridges we have no fixture for.  Service-mutation variants (Phase 6)
    accept a wider return-type range via the developer-supplied method's return type; this
    invariant applies only to the four DML variants.  `buildMutationReturnExpression` in Phase 2
    assumes this gate is enforced.

---

## Plan

### Phase 1 — Model extension + classifier

**Goal:** populate the DML variants with the data the emitter needs.  No emission changes.

#### 1a. Extend the four DML `MutationField` variants

Add `TableInputArg tableInputArg` to each record:

```java
record MutationInsertTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg
) implements MutationField {}

// Same addition on MutationUpdateTableField, MutationDeleteTableField, MutationUpsertTableField.
```

#### 1b. Extend `classifyMutationField`

Before the `switch (typeName)` at `FieldBuilder.java:1540`, add a `TableInputArg` extraction
step:

```java
// Find the single @table input argument; reject any other argument shape.
var tiaOrError = classifyMutationInput(fieldDef, typeName /* INSERT|UPDATE|DELETE|UPSERT */);
if (tiaOrError.error() != null) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef, tiaOrError.error());
}
ArgumentRef.InputTypeArg.TableInputArg tia = tiaOrError.value();
```

**`classifyMutationInput` is a purpose-built helper, not a caller of the existing
`classifyArgument`.**  `classifyArgument(FieldBuilder.java:673)` takes a `TableRef rt` and uses
it to bind un-directived scalar args against the parent table's columns (`:711`); a mutation
field has no parent `@table` (its parent is `Mutation`), so threading a sentinel would mis-bind.
Instead, `classifyMutationInput` walks `fieldDef.getArguments()` directly and, for each argument:

1. Unwraps the argument's GraphQL type to a named type.
2. Looks up `ctx.types.get(typeName)`.  If the resolved type is a `GraphitronType.TableInputType`,
   build a `TableInputArg` via the same path `classifyArgument` uses at `:700-702`
   (`buildLookupBindings` + `tit.inputFields()`).  Otherwise, record a rejection per Invariant #11
   (non-`TableInputArg` shape).
3. Collects `TableInputArg` results and reports:
   - Zero found → error "no `@table` input argument found on `@mutation` field" (Invariant #1).
   - Two or more found → error per Invariant #1.
   - Any non-`TableInputArg` argument → error per Invariant #11.
   - Exactly one `TableInputArg` → check `fields()` for `NestingField`, `PlatformIdField`,
     `ColumnReferenceField` per Invariants #7–9.
   - Check `tia.list()`; if true, error per Invariant #10 ("listed inputs not yet supported").
   - For UPDATE/DELETE/UPSERT, check `fieldBindings()` is non-empty per Invariants #2–3.
   - For UPDATE specifically, check that at least one `ColumnField` in `tia.fields()` has a
     name not present in `tia.fieldBindings()` per Invariant #4.

Return shape: a small record carrying either `(TableInputArg value, null error)` or `(null, String error)`.

Then the `switch (typeName)` passes `tia` to each constructor.

#### 1c. Validator additions

`validateMutationInsertTableField`, `validateMutationUpdateTableField`,
`validateMutationDeleteTableField`, `validateMutationUpsertTableField` are all currently empty
stubs.  They stay empty after Phase 1 — the classifier rejects bad shapes via `UnclassifiedField`
before validation is invoked.

#### 1d. Pipeline tests

`GraphitronSchemaBuilderTest` additions (no emission yet, just classifier assertions):

| SDL shape | Expected outcome |
|---|---|
| `createFilm(in: FilmInput): ID @mutation(typeName: INSERT)` where `FilmInput @table(name: "film")` | `MutationInsertTableField(tableInputArg.inputTable = TableRef("film", "FILM", "Film", [...]))` |
| `updateFilm(in: FilmInput): ID @mutation(typeName: UPDATE)` with `@lookupKey` on `FilmInput.id` | `MutationUpdateTableField` with `fieldBindings` non-empty |
| `updateFilm` with no `@lookupKey` in input | `UnclassifiedField` with Invariant #2 message |
| `updateFilm` where every input field is `@lookupKey` | `UnclassifiedField` with Invariant #4 message |
| `deleteFilm(in: FilmInput): ID @mutation(typeName: DELETE)` with `@lookupKey` | `MutationDeleteTableField` |
| `upsertFilm(in: FilmInput): ID @mutation(typeName: UPSERT)` with `@lookupKey` | `MutationUpsertTableField` |
| Any DML variant with `NestingField` in input | `UnclassifiedField` with Invariant #7 message |
| DML variant with a plain (non-`@table`) input arg | `UnclassifiedField` with Invariant #11 message |
| DML variant with listed input (`in: [FilmInput]`) | `UnclassifiedField` with Invariant #10 message |
| `@service` + `@mutation` together | `UnclassifiedField` (existing check at `FieldBuilder.java:1513-1516`) |

---

### Phase 2 — INSERT emission

**Goal:** emit a real fetcher for `MutationInsertTableField`.

#### Shape of emitted method

```java
public static Object createFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .insertInto(Tables.FILM, Tables.FILM.FILM_ID, /* … more columns … */)
        .values(DSL.val((Long) in.get("filmId")), /* … */)
        .returningResult(/* return expression */)
        .fetchOne(r -> r.get(/* return column */));
}
```

- **Return type of the emitted Java method**: always `Object`.  This mirrors the existing
  `QueryServiceRecordField` / stubbed-mutation precedent and sidesteps the ID-coercion mismatch:
  graphql-java's `ID` scalar accepts any `Object` and coerces to `String` via `toString`, so an
  emitted `Long` PK flows through the `ID` scalar without a conversion.  `Record` /
  `Result<Record>` similarly flow without signature churn.  The Javadoc on each emitter should
  note that the concrete runtime type matches the declared SDL return shape.
- **Column list**: every `InputField.ColumnField` in `tia.fields()`, in declaration order.
  `@lookupKey` fields are included — INSERT does not treat them specially.
- **Values list**: parallel to the column list.  Each value is
  `DSL.val((<JavaType>) in.get("<sdlFieldName>"))` where `sdlFieldName` is
  `InputField.ColumnField.name()` and `<JavaType>` is derived from `ColumnRef.columnClass()`.
- **Return expression** (see below).

#### Return expression: `buildMutationReturnExpression`

Shared helper used by Phases 2–5.  Signature (private static on `TypeFetcherGenerator`):

```java
private static ReturnBlock buildMutationReturnExpression(
    MutationField.DmlTableField f,   // sealed common supertype of the four DML variants; see note
    ResolvedTableNames names
);

record ReturnBlock(CodeBlock returningResult, CodeBlock terminalCall) {}
```

The caller splices `returningResult` into `.returningResult($L)` and `terminalCall` into the
final position (`.fetchOne($L)` or `.fetch($L)`).

Dispatch over `f.returnType()`:

| `ReturnTypeRef` | `returningResult` | `terminalCall` |
|---|---|---|
| `ScalarReturnType` with SDL type `ID`, wrapper `Single` | `Tables.FILM.FILM_ID` (PK column) | `.fetchOne(r -> r.get(Tables.FILM.FILM_ID))` |
| `ScalarReturnType` with SDL type `Int`/`Boolean`/other non-`ID` | **Reject at classifier time, Phase 1.** | (n/a) |
| `TableBoundReturnType`, wrapper `Single` | `<Type>.$fields(env.getSelectionSet(), table, env)` | `.fetchOne(r -> r)` |
| `TableBoundReturnType`, wrapper `List` | same | `.fetch(r -> r)` |
| `TableBoundReturnType`, wrapper `Connection` | **Reject at classifier time, Phase 1.** | (n/a) |
| `PolymorphicReturnType` | **Reject at classifier time, Phase 1.** | (n/a) |
| `ResultReturnType` | **Reject at classifier time, Phase 1.** | (n/a) |

The four "reject at classifier time" rows are enforced by Invariant #12.  This supersedes
an earlier sentinel approach (emit `DSL.val(1)` for non-`ID` scalar returns): the sentinel's
semantics differ between INSERT (always 1) and UPDATE/DELETE (affected-row count), and
`returningResult(DSL.val(1))` cannot express either honestly.  Future demand for `Int` returns
will be met by extending this helper with an `.execute()`-based arm (see Non-goals).

Note on the `DmlTableField` supertype: the four DML variants are already distinct `MutationField`
records.  Two implementation options: (a) add a sealed intermediate `MutationField.DmlTableField`
they all implement; (b) pass the four pieces the helper needs (`returnType`, `tableInputArg`,
`location`) as individual arguments.  The implementer picks; (a) is lighter at call sites, (b)
avoids a taxonomy change.  No downstream code reads the supertype today.

NodeId-encoded IDs (`NodeIdStrategy.createId(...)`) are deferred to the `plan-nodeid-directives.md`
mutation-binding step.  For now, scalar `ID` returns emit the raw PK column value wrapped in
`Object`; graphql-java's `ID` scalar handles the `Long → String` coercion.

**`RETURNING` with nested selections (`TableBoundReturnType`):** `Type.$fields` emits
`DSL.multiset(...)` for child `TableField`/`NestingField` selections, which run as correlated
subqueries.  PostgreSQL supports subqueries inside `RETURNING`, so this shape works in principle,
but no existing Graphitron fixture exercises `INSERT ... RETURNING (multiset)`, so Phase 2's
execution tests must include at least one case that selects a nested child on the returned record
(e.g. `createFilm { title, language { name } }`).  If PostgreSQL rejects the shape, fall back to
"RETURNING PK only, then a follow-up SELECT from the same `dsl`"; document the choice in the
phase's landing commit.

#### Implementation sites

- New `buildMutationInsertFetcher(MutationInsertTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm at `:347` changes from `stub(f)` to the new emitter call.
- `MutationInsertTableField` moves from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`.

#### Tests

**Pipeline** (`GraphitronSchemaBuilderTest` or a dedicated `MutationInsertPipelineTest`):
- Emitted method is present and named after the field.
- Method signature is `public static <ReturnType> createFilm(DataFetchingEnvironment env)`.
- Body contains `insertInto` and `returningResult`.

**Execution** (new `GraphQLMutationTest` in `graphitron-rewrite-test`):
- Fixture SDL: `type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT) }`
  with `input FilmInput @table(name: "film") { title: String!, languageId: Int!, rentalDuration: Int!, rentalRate: Float!, replacementCost: Float! }`.
  Sakila's `film` table has several NOT-NULL columns without defaults (`language_id`,
  `rental_duration`, `rental_rate`, `replacement_cost`); the fixture must cover them or the
  INSERT fails at execute time per Invariant #6.
- Test: calls `createFilm(in: {title: "Test", languageId: 1, rentalDuration: 3, rentalRate: 4.99, replacementCost: 19.99})`,
  asserts returned ID matches the inserted film's PK column value.
- Nested-selection test: `createFilm(...) { title, language { name } }` against a
  `TableBoundReturnType`-returning variant to exercise `RETURNING (multiset)` per the note above.
- Negative test: an SDL with `createFilm(...): Int @mutation(typeName: INSERT)` is rejected at
  build time per Invariant #12.
- Compile gate: `mvn compile -pl :graphitron-rewrite-test -Plocal-db`.

---

### Phase 3 — DELETE emission

#### Shape of emitted method

```java
public static Object deleteFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    Long filmId = (Long) in.get("filmId");
    return dsl
        .deleteFrom(Tables.FILM)
        .where(Tables.FILM.FILM_ID.eq(filmId))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> r.get(Tables.FILM.FILM_ID));
}
```

- **WHERE clause**: each `InputColumnBinding` in `tia.fieldBindings()` contributes one
  `.eq(value)` predicate, combined with `DSL.and(...)`.
- **Return expression**: same rules as INSERT above.

When `tia.fieldBindings()` has a single binding, emit `.where(col.eq(val))`.
When multiple, emit `.where(col1.eq(v1).and(col2.eq(v2)))`.

The same `buildMutationReturnExpression` helper (introduced in Phase 2) is reused.

#### Tests

Pipeline and execution tests following the same pattern as Phase 2.
Execution test: `deleteFilm(in: {filmId: 1})` — asserts the film is gone from the DB afterward
and the returned ID matches.

---

### Phase 4 — UPDATE emission

#### Shape of emitted method

```java
public static Object updateFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    Long filmId = (Long) in.get("filmId");         // @lookupKey → WHERE
    String title = (String) in.get("title");       // regular field → SET
    return dsl
        .update(Tables.FILM)
        .set(Tables.FILM.TITLE, title)
        .where(Tables.FILM.FILM_ID.eq(filmId))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> r.get(Tables.FILM.FILM_ID));
}
```

- **SET clause**: every `InputField.ColumnField` in `tia.fields()` whose `name()` is **not** in
  any `tia.fieldBindings()` entry's `inputFieldName()` (i.e. not `@lookupKey`-bound) contributes
  one `.set(col, val)`.  Invariant #4 guarantees at least one such field.
- **WHERE clause**: `fieldBindings()` entries, same as DELETE.
- **Return expression**: `buildMutationReturnExpression` (Phase 2).  The empty-SET classifier
  gate lives in Phase 1 per Invariant #4.

#### Tests

Execution test: `updateFilm(in: {filmId: 1, title: "Updated"})` — asserts the title changed and
the returned ID is 1.

---

### Phase 5 — UPSERT emission

#### Shape of emitted method

jOOQ supports `INSERT ... ON CONFLICT ... DO UPDATE SET ...` via:

```java
dsl.insertInto(Tables.FILM, cols...)
   .values(vals...)
   .onConflict(Tables.FILM.FILM_ID)   // @lookupKey columns
   .doUpdate()
   .set(Tables.FILM.TITLE, title)     // non-@lookupKey columns
   .returningResult(/* … */)
   .fetchOne(/* … */);
```

- **`onConflict` columns**: `fieldBindings()` entries.
- **`doUpdate().set(...)` columns**: non-`@lookupKey` fields, same as UPDATE.
- **INSERT columns**: all fields, same as INSERT.
- **Return expression**: same helper.

If the jOOQ table's dialect does not support `INSERT ... ON CONFLICT`, this will fail at runtime
on non-PostgreSQL databases; document this in the plan and accept it for now (the rewrite targets
PostgreSQL).

#### Tests

Execution test: `upsertFilm(in: {filmId: 1, title: "Upserted"})` — asserts correct insert-or-update
semantics.

---

### Phase 6 — Service mutations

**Prerequisite:** `plan-service-root-fetchers.md` must be complete (landed on trunk) before
starting this phase.  That plan introduces `ArgCallEmitter.buildMethodBackedCallArgs`, the
helper both service-query and service-mutation emitters share.

#### `MutationServiceTableField`

Same shape as `QueryServiceTableField` from the service-root-fetchers plan.  The developer method
returns a `Result<Record>` (list) or `Record` (single).  The generator emits a synchronous
method identical to `buildQueryServiceTableFetcher` — there is no special write semantics because
the service owns all SQL, including any transactions:

```java
public static Result<Record> activeRentals(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    return RentalService.createRentals(
        dsl,
        env.getArgument("customerId")
    );
}
```

Implementation: add `buildMutationServiceTableFetcher(MutationServiceTableField, ...)` in
`TypeFetcherGenerator` following the same structure as `buildQueryServiceTableFetcher`.

#### `MutationServiceRecordField`

Identical to `QueryServiceRecordField`: `Object` return type, developer method returns whatever
graphql-java can coerce.  Add `buildMutationServiceRecordFetcher(MutationServiceRecordField, ...)`.

Both move from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`; both use the shared
`buildMethodBackedCallArgs` helper.

#### Tests

Pipeline tests: one case per variant asserting the emitted method calls the service method and
does not emit `$fields`.
Execution tests: a fixture service method on the mutation type; assert the returned value flows
through graphql-java's registered fetchers.

---

## Non-goals

- **Listed inputs** (`in: [FilmInput]`): the `list` flag on `TableInputArg` causes a classifier
  gate (Invariant #10).  Unblocking listed inputs requires iterating over the input list and
  constructing a batch INSERT / batch UPDATE / DELETE / UPSERT.  The legacy code uses jOOQ
  `batchInsert` / `batchUpdate` / `batchDelete` / `batchStore` for this.  Tracked as a follow-up.
- **Nested input types** (`NestingField`): deferred per Invariant #7.
- **`PlatformIdField` in mutation inputs**: deferred to `plan-nodeid-directives.md`
  mutation-binding step (Invariant #8).
- **`ColumnReferenceField` in mutation inputs**: deferred (Invariant #9).
- **Non-`TableInputArg` arguments on DML fields**: deferred (Invariant #11).  A future plan could
  admit scalar context arguments alongside the `@table` input (e.g. a `reason: String` audit field
  routed to a column not in the input type); today we reject them rather than invent a precedence
  story.
- **Build-time INSERT column-coverage validation** (Invariant #6): deferred until the jOOQ
  catalog reliably exposes NOT-NULL + default metadata through the generator's `JooqCatalog`
  layer.  Today the DB rejects incomplete INSERTs at execute time; the rewrite does not try
  to beat the DB to the punch.  Promote to Active if three or more production rejections trace
  back to runtime incomplete-INSERT errors that a classifier check would have caught cheaply.
- **Non-`ID` / non-`TableBoundReturnType` return types on DML fields** (Invariant #12): deferred.
  The previous position (emit `DSL.val(1)` as an affected-row sentinel) is withdrawn: the
  semantics differ between INSERT (always 1) and UPDATE/DELETE (actual affected count), and
  `returningResult(DSL.val(1))` cannot express either honestly.  When a consumer needs an `Int`
  return, a follow-up plan will extend `buildMutationReturnExpression` with an arm that uses
  `.execute()` (returns affected-row count) and skips `returningResult` entirely.
- **NodeId-encoded IDs in return values** (`NodeIdStrategy.createId(...)`): the returned scalar `ID`
  is the raw PK column value until `plan-nodeid-directives.md` adds the `NodeIdBinding` step.
- **Transaction wrapping**: the emitted DML statements run within whatever transaction context
  the caller provides via `dsl`.  Explicit transaction wrapping (e.g. `dsl.transactionResult(...)`)
  is not added here; service variants already control their own transactions through the
  developer-supplied method.
- **Non-PostgreSQL dialects**: `RETURNING` and `ON CONFLICT DO UPDATE` are PostgreSQL-specific.
  The rewrite targets PostgreSQL; no cross-dialect abstraction is added.
- **`@mutation` + `@service` combination**: already rejected at classifier time
  (`FieldBuilder.java:1513-1516`).

---

## Open decisions

- **`MutationField.DmlTableField` sealed intermediate vs. individual helper arguments.**
  `buildMutationReturnExpression` needs `returnType`, `tableInputArg`, and `location` from any of
  the four DML variants.  Option (a): add a sealed intermediate the four variants implement; the
  helper takes it directly.  Option (b): pass the three pieces individually.  Implementer picks;
  the choice is isolated to the Phase-2 landing and can flip later without touching callers.
- **`RETURNING (multiset)` on PostgreSQL for `TableBoundReturnType` returns.** Phase 2's
  execution test must exercise at least one nested-selection return path.  If PostgreSQL rejects
  `INSERT ... RETURNING (multiset_subquery)`, fall back to "RETURNING PK, then a follow-up SELECT
  on the same `dsl`" within the emitted method; document the choice in the landing commit.  Both
  paths are indistinguishable from the caller's point of view.
- **Transactional integrity for multi-step mutations** (e.g. INSERT + immediate re-fetch):
  both calls share the same `dsl` obtained from `GraphitronContext`, which may or may not be
  inside an active transaction depending on the caller's setup.  Document this as the
  caller's responsibility; no wrapper is added in this plan.
