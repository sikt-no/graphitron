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

4. **INSERT does not require `@lookupKey`.**  `fieldBindings` may be empty for INSERT.

5. **Nested `@table` input fields (`InputField.NestingField`) are deferred.**  If any field in
   `tia.fields()` is a `NestingField`, emit an `UnclassifiedField` at classify time
   (`"nested input types in @mutation fields are not yet supported"`).  This keeps the initial
   scope flat; nesting can land in a follow-up.

6. **`InputField.PlatformIdField` in a mutation input is deferred.**  Same gate:
   `"PlatformIdField in @mutation inputs is not yet supported — use @nodeId"`; tracked under the
   `plan-nodeid-directives.md` mutation-binding step.

7. **`InputField.ColumnReferenceField` (cross-table reference) in a mutation input is deferred.**
   Gate: `"ColumnReferenceField in @mutation inputs is not yet supported"`.

8. **Listed inputs (`in: [CustomerInputTable]`) are deferred for Phase 1.**  The initial
   implementation handles only single-record inputs.  Listed-input support is a follow-up
   (see Non-goals).

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
// Find the single @table input argument.
ArgumentRef.InputTypeArg.TableInputArg tia = classifyMutationInput(fieldDef, parentTypeName, name, location, errors);
if (tia == null) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef, errors.get(0));
}
```

`classifyMutationInput` walks `fieldDef.getArguments()`, calling the existing
`classifyArgument(arg, ...)` for each.  It collects `TableInputArg` results and:
- Zero found → error "no `@table` input argument found on `@mutation` field"
- Two or more found → error per Invariant #1
- Exactly one found → check `fields()` for `NestingField`, `PlatformIdField`,
  `ColumnReferenceField` per Invariants #5–7
- For UPDATE/DELETE/UPSERT, check `fieldBindings()` is non-empty per Invariants #2–3

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
| `deleteFilm(in: FilmInput): ID @mutation(typeName: DELETE)` with `@lookupKey` | `MutationDeleteTableField` |
| `upsertFilm(in: FilmInput): ID @mutation(typeName: UPSERT)` with `@lookupKey` | `MutationUpsertTableField` |
| Any DML variant with `NestingField` in input | `UnclassifiedField` with Invariant #5 message |
| `@service` + `@mutation` together | `UnclassifiedField` (existing check at `:1513-1515`) |

---

### Phase 2 — INSERT emission

**Goal:** emit a real fetcher for `MutationInsertTableField`.

#### Shape of emitted method

```java
public static String createFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .insertInto(Tables.FILM, Tables.FILM.FILM_ID, /* … more columns … */)
        .values(DSL.val((Long) in.get("filmId")), /* … */)
        .returningResult(/* return expression */)
        .fetchOne(r -> r.get(/* return column */));
}
```

- **Column list**: every `InputField.ColumnField` in `tia.fields()`, in declaration order.
  `@lookupKey` fields are included — INSERT does not treat them specially.
- **Values list**: parallel to the column list.  Each value is
  `DSL.val((<JavaType>) in.get("<sdlFieldName>"))` where `sdlFieldName` is
  `InputField.ColumnField.name()` and `<JavaType>` is derived from `ColumnRef.columnClass()`.
- **Return expression** (see below).

#### Return expression

The return type of the mutation field (`tia.returnType()`) drives this:

| `ReturnTypeRef` | `.returningResult(…)` | `.fetchOne(…)` / `.fetch(…)` |
|---|---|---|
| `ScalarReturnType` with SDL type `ID` | `Tables.FILM.FILM_ID` (the PK column) | `r -> r.get(Tables.FILM.FILM_ID)` |
| `ScalarReturnType` with other SDL type (e.g. `Int`) | `DSL.val(1)` (affected row sentinel) | `r -> r.get(0, Integer.class)` |
| `TableBoundReturnType` | `FilmTypes.$fields(env.getSelectionSet(), Tables.FILM, env)` | `r -> r` (returns `Record`) |

NodeId-encoded IDs (`NodeIdStrategy.createId(...)`) are deferred to the `plan-nodeid-directives.md`
mutation-binding step.  For now, scalar `ID` returns emit the raw PK column value.

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
  with `input FilmInput @table(name: "film") { title: String! }`.
- Test: calls `createFilm(in: {title: "Test"})`, asserts returned ID matches the inserted film's
  PK column value.
- Compile gate: `mvn compile -pl :graphitron-rewrite-test -Plocal-db`.

---

### Phase 3 — DELETE emission

#### Shape of emitted method

```java
public static String deleteFilm(DataFetchingEnvironment env) {
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
public static String updateFilm(DataFetchingEnvironment env) {
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
  `tia.fieldBindings()` (i.e. not `@lookupKey`) contributes one `.set(col, val)`.
- **WHERE clause**: `fieldBindings()` entries, same as DELETE.
- **Return expression**: same helper as Phases 2–3.

If there are no SET-clause fields (every field is `@lookupKey`), emit `UnclassifiedField` at
classifier time: `"@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"`.  Add
this check to `classifyMutationInput`.

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
  gate (Invariant #8).  Unblocking listed inputs requires iterating over the input list and
  constructing a batch INSERT / batch UPDATE / DELETE / UPSERT.  The legacy code uses jOOQ
  `batchInsert` / `batchUpdate` / `batchDelete` / `batchStore` for this.  Tracked as a follow-up.
- **Nested input types** (`NestingField`): deferred per Invariant #5.
- **`PlatformIdField` in mutation inputs**: deferred to `plan-nodeid-directives.md`
  mutation-binding step (Invariant #6).
- **`ColumnReferenceField` in mutation inputs**: deferred (Invariant #7).
- **NodeId-encoded IDs in return values** (`NodeIdStrategy.createId(...)`): the returned scalar `ID`
  is the raw PK column value until `plan-nodeid-directives.md` adds the `NodeIdBinding` step.
- **Transaction wrapping**: the emitted DML statements run within whatever transaction context
  the caller provides via `dsl`.  Explicit transaction wrapping (e.g. `dsl.transactionResult(...)`)
  is not added here; service variants already control their own transactions through the
  developer-supplied method.
- **Non-PostgreSQL dialects**: `RETURNING` and `ON CONFLICT DO UPDATE` are PostgreSQL-specific.
  The rewrite targets PostgreSQL; no cross-dialect abstraction is added.
- **`@mutation` + `@service` combination**: already rejected at classifier time
  (`FieldBuilder.java:1513-1515`).

---

## Open decisions

- **Return type when no `RETURNING` value is meaningful** (e.g. a mutation that returns
  `Boolean` or `Int`): the initial implementation emits `DSL.val(1)` as a sentinel for the
  `.returningResult(...)` expression and coerces via graphql-java.  If a consumer hits a case
  where this is wrong, escalate and add a `BooleanReturnType` / `IntReturnType` arm to the
  `buildMutationReturnExpression` helper.
- **Transactional integrity for multi-step mutations** (e.g. INSERT + immediate re-fetch):
  both calls share the same `dsl` obtained from `GraphitronContext`, which may or may not be
  inside an active transaction depending on the caller's setup.  Document this as the
  caller's responsibility; no wrapper is added in this plan.
