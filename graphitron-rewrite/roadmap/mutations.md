---
id: R22
title: "Mutation bodies"
status: Spec
priority: 9
theme: mutations-errors
depends-on: []
---

# Mutation bodies

> Lift all six mutation leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`:
> `MutationField.MutationInsertTableField`, `MutationUpdateTableField`,
> `MutationDeleteTableField`, `MutationUpsertTableField`,
> `MutationServiceTableField`, `MutationServiceRecordField`.
> Closes generator stub #4 — the highest-aggregate production rejection (131 combined).

---

## Status

| Variant | Status | Notes |
|---|---|---|
| `MutationDeleteTableField` | **Landed** | Commit `31c64a2` (`claude/graphitron-rewrite`). Per-variant inline classifier + emitter; no shared helpers yet. |
| `MutationInsertTableField` | Pending | Land next. Mirror DELETE's per-variant shape. |
| `MutationUpdateTableField` | Pending | Lands after INSERT. Same shape; reuses INSERT's value-binding pattern + DELETE's WHERE-clause pattern. |
| `MutationUpsertTableField` | Pending | Lands after UPDATE. Composite of INSERT (insert path) and UPDATE (DO UPDATE path). |
| `MutationServiceTableField` | Pending | Lands alongside `MutationServiceRecordField`. Reuses `buildServiceFetcherCommon`. |
| `MutationServiceRecordField` | Pending | See above. |
| Consolidation | Deferred | `DmlTableField` supertype, shared `classifyMutationInput`, shared `buildMutationReturnExpression`. Triggered when 2+ DML variants are real. See bottom of plan. |

**Landing rule.** Each variant ships with its own inline classifier (`classifyMutation<Verb>Field`) and emitter (`buildMutation<Verb>Fetcher`), copy-paste-modified from DELETE's. Shared helpers and the `DmlTableField` sealed supertype are a follow-up — introducing them up front, before any variant was real, would have over-fitted the abstraction. Consolidate when the second variant lands and the duplication is real.

---

## Current state

> Section reflects the codebase as of post-DELETE landing. See [Status](#status) for the
> per-variant implementation summary.

### Model

`MutationField.java` (`model/MutationField.java`) declares six permits.
`MutationDeleteTableField` carries the full per-variant data set
(`inputArgName`, `inputTable`, `fieldBindings`, `nodeIdMeta`, `errorChannel`) —
see §1a for the record shape.
The other three DML variants
(`MutationInsertTableField`, `MutationUpdateTableField`, `MutationUpsertTableField`)
still carry only `(parentTypeName, name, location, returnType, errorChannel)`; they are
extended per-variant when their emission lands.
The two service variants additionally carry `MethodRef method`
(populated by `classifyMutationField`).
Every variant carries `Optional<ErrorChannel> errorChannel` (R12 contract) with a no-channel
convenience constructor.

### Classifier

`FieldBuilder.classifyMutationField` (`FieldBuilder.java`) routes mutation fields:
`@service` → service variants; `@mutation(typeName: ...)` → INSERT / UPDATE / DELETE / UPSERT
switch. The `DELETE` arm delegates to `classifyMutationDeleteField`, which extracts the
single `@table` input arg, validates against Invariants #1-#14, and constructs the variant
record. INSERT / UPDATE / UPSERT arms still construct the bare 4-field record; the input
arg's classified data is discarded at classify time pending each variant's landing.

### Generator stubs

`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` has entries for all six variants
(`:224-235`); `generateTypeSpec` routes each to `stub(f)` (`:354-359`).

### Neighbouring references

- `ArgumentRef.InputTypeArg.TableInputArg` — carries `inputTable` (`TableRef`), `fieldBindings`
  (`List<InputColumnBinding>`, `@lookupKey`-bound columns), `fields` (`List<InputField>`, all
  input fields).  Already populated by `FieldBuilder.classifyArgument` (`FieldBuilder.java:779-783`)
  for `@table` input types.  `buildLookupBindings` (`FieldBuilder.java:906`) takes a mutable
  `List<String> errors` and pushes per-field `@lookupKey` validation errors onto it (e.g. lookupKey
  on a list-typed input field, lookupKey on a non-`ColumnField`).  Phase 1b's `classifyMutationInput`
  must thread these errors back into the `UnclassifiedField` reason — losing them would silently
  swallow schema-author mistakes.
- `InputField.ColumnField` — carries `name` (GraphQL field name), `column` (`ColumnRef` with
  `sqlName`, `javaName`, `columnClass`), and `nonNull`.  The `javaName` field is the jOOQ column
  constant (e.g. `CUSTOMER_ID`).
- `InputColumnBinding` — maps a `@lookupKey` input field name to a `ColumnRef` and a
  `CallSiteExtraction`.  This is the same binding the argres pipeline uses for query lookups;
  for mutations it identifies the WHERE-clause columns.
- `GeneratorUtils.ResolvedTableNames` — resolves `tablesClass`, `jooqTableClass`, `typeClass` from
  a `TableRef`.  Used by every SQL-generating emitter.
- `ArgCallEmitter.buildMethodBackedCallArgs(MethodRef, CodeBlock, String)` — declaration-order
  param-list emitter introduced by the service-root-fetchers work (now Done; see roadmap
  Done section). Service-mutation variants (Phases 2 and 5–6) reuse it directly.
- `FieldBuilder.validateRootServiceInvariants(ServiceResolution)` — shared classifier-time
  helper enforcing §1 (Connection rejection) and §2 (no `Sources` parameter at root) on
  both `@service` query and `@service` mutation arms. Already wired into `classifyMutationField`
  by service-root-fetchers (Done); the mutation-bodies plan inherits these checks for free.
- `MethodRef.Basic.returnType()` — structured javapoet `TypeName` captured at reflection time
  (was a string FQCN before service-root-fetchers landed). Mutation-`@service` strict-return
  validation already routes through `FieldBuilder.computeExpectedServiceReturnType` for the
  `parentPkColumns.isEmpty()` path; only the emitter side remains for Phase 6.

### Cross-cutting decisions discovered during the DELETE landing

- **`ArgumentRef` is package-private to `no.sikt.graphitron.rewrite`**, so a `MutationField`
  variant in `model/` cannot carry a `TableInputArg` directly. DELETE works around this by
  storing the data the emitter actually needs as separate fields on the variant:
  `(String inputArgName, TableRef inputTable, List<InputColumnBinding> fieldBindings,
  Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta)`. INSERT/UPDATE/UPSERT should follow the
  same pattern. Promoting `ArgumentRef` (or just `TableInputArg`) to `model/` is a possible
  consolidation step, but only worth doing if the field set diverges between variants enough
  that copying becomes a real maintenance burden — so far it doesn't.
- **`Optional<ErrorChannel> errorChannel` is now part of every `MutationField` record**
  (added by R12). Each variant carries it as its last component and exposes a no-channel
  convenience constructor. New variants must do the same; the classifier always passes
  `Optional.empty()` here, leaving population to R12's C3 carrier pass.
- **`TableRef.tableName()`, `ColumnRef.sqlName()`, `InputColumnBinding.targetColumn()`.** The
  earlier draft was inconsistent about which type carried the SQL name accessor; the actual
  accessors are `TableRef.tableName()` for table SQL names and `ColumnRef.sqlName()` for column
  SQL names. The binding's column accessor is `targetColumn()`, not `column()`.

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

8. **`InputField.NodeIdField` in a mutation input is deferred.**  Same gate:
   `"NodeIdField in @mutation inputs is not yet supported"`; the binding-time NodeId decode
   lands as part of argres Phase 3 (`InputColumnBinding` with a `NodeIdBinding` variant).

9. **`InputField.ColumnReferenceField` (cross-table reference) in a mutation input is deferred.**
   Gate: `"ColumnReferenceField in @mutation inputs is not yet supported"`.

10. **`InputField.NodeIdReferenceField` (cross-table NodeID reference) in a mutation input is
    deferred.**  Same rationale as #9 — cross-table refs would need a join-and-resolve step on the
    write side.  Gate: `"NodeIdReferenceField in @mutation inputs is not yet supported"`.  Full set
    of currently-allowed `InputField` variants in a mutation `@table` input is therefore
    `ColumnField` only; everything else (`NestingField`, `NodeIdField`, `NodeIdReferenceField`,
    `ColumnReferenceField`) is gated.

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
    (see Phase 1b) returns `UnclassifiedField` if any argument is anything other than a
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
    invariant applies only to the four DML variants.  `buildMutationReturnExpression` in Phase 2
    assumes this gate is enforced.

    **Phase 1 numbers check (gate before landing):** the roadmap's 131-count aggregate comes from
    the legacy generator, which accepted `Boolean` / `Int` via affected-row semantics.  Before
    Phase 1 merges, sample the SDLs producing those rejections and confirm that the majority
    return `ID`, `[ID]`, `T`, or `[T]`.  If a material slice returns `Int` / `Boolean`, the stub
    lift does not close those rejections — it swaps them for a new rejection class, and the
    `.execute()`-based arm (see Non-goals) needs to land in the same pass instead of being
    deferred.  Record the sample-size and return-type breakdown in the Phase 1 landing commit.

---

## Plan

### Phase 1 — Model extension + classifier

**Goal:** populate the DML variants with the data the emitter needs.  No emission changes.

#### 1a. Extend each DML `MutationField` variant as it lands

> **Revised (post-DELETE).** The earlier draft introduced a `DmlTableField` sealed
> supertype and extended all four DML records up front. We did not do that for DELETE,
> and the friction of doing it now (with one variant real and three still empty) outweighs
> the benefit. Each variant now grows its own per-variant fields when its emission lands;
> the sealed supertype is a [Consolidation](#consolidation) follow-up.

For a new DML variant landing, add the data the emitter needs directly to the record. DELETE's
shape is the precedent:

```java
record MutationDeleteTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    String inputArgName,
    TableRef inputTable,
    List<InputColumnBinding> fieldBindings,
    Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
    Optional<ErrorChannel> errorChannel
) implements MutationField {
    public MutationDeleteTableField(/* …8-arg form, errorChannel = Optional.empty() */) { … }
}
```

INSERT and UPSERT will need the full input-fields list as well (every `ColumnField` contributes
to the INSERT column/value list, not just the `@lookupKey`-bound ones); UPDATE needs the
input-fields list to compute the SET-clause columns. Add `List<InputField> fields` alongside
`fieldBindings` on those variants. Don't add it to DELETE retroactively (DELETE doesn't read
non-bound fields and the field would be unused).

`Optional<ErrorChannel> errorChannel` is mandatory on every variant (R12 contract). Keep the
no-channel convenience constructor so the classifier doesn't have to know about error channels.

#### 1b. Add a per-variant classifier helper

> **Revised (post-DELETE).** The earlier draft introduced a single `classifyMutationInput`
> helper called from the `switch (typeName)` arm. We did not write that helper; DELETE has
> its own `classifyMutationDeleteField`, and INSERT/UPDATE/UPSERT will start the same way.
> Folding them into a single helper is a [Consolidation](#consolidation) follow-up.

For a new DML variant landing, hook into `classifyMutationField` (`FieldBuilder.java`,
inside the existing `if (typeName != null)` gate, in the `switch (typeName)` arm) by
delegating to a per-variant helper:

```java
case "DELETE" -> classifyMutationDeleteField(fieldDef, parentTypeName, name, location, returnType);
case "INSERT" -> classifyMutationInsertField(fieldDef, parentTypeName, name, location, returnType);
// …
```

The helper walks `fieldDef.getArguments()` directly. **Do not call `classifyArgument`** —
it takes a `TableRef rt` and binds un-directived scalar args against the parent table's
columns; a mutation field has no parent `@table`, so threading a sentinel would mis-bind.

The shape of `classifyMutationDeleteField` (in `FieldBuilder.java`) is the precedent. It
runs two passes:

**Pass 1 — shape gate (Invariants #1, #13).** For each argument, unwrap its GraphQL type
to a named type and look up `ctx.types.get(typeName)`. If the resolved type is a
`GraphitronType.TableInputType`, build a `TableInputArg` via the same path `classifyArgument`
uses (`buildLookupBindings` + `tit.inputFields()`). Also populate `argCondition` from the
argument's SDL directives using `buildArgCondition`; without this, Pass 2's
`tia.argCondition().isPresent()` check for Invariant #12 will never fire. Allocate a fresh
`List<String> bindingErrors` per call and pass it into `buildLookupBindings`; if non-empty,
join them with `"; "` as the rejection reason and return `UnclassifiedField` (don't drop
them silently). At the end of Pass 1:
- Zero `TableInputArg` found → error "no `@table` input argument found on `@mutation` field".
- Two or more found → error per Invariant #1.
- Any non-`TableInputArg` argument → error per Invariant #13.

**Pass 2 — invariant checks on the single `tia`.** Only reached when Pass 1 produced
exactly one `TableInputArg`. The relevant checks vary per verb:

| Check | INSERT | UPDATE | DELETE | UPSERT |
|---|---|---|---|---|
| `tia.list()` (Invariant #11) | reject | reject | reject | reject |
| `tia.argCondition().isPresent()` (#12) | reject | reject | reject | reject |
| Any non-`ColumnField` input field (#7–#10) | reject | reject | reject | reject |
| `fieldBindings()` empty (#2–#3) | allowed | reject | reject | reject |
| Every field is `@lookupKey`-bound (#4) | n/a | reject | n/a | allowed |
| PK-coverage on non-empty `findPkColumns(tableName())` (#2) | n/a | required | required | n/a |

After Pass 2, validate `returnType` against Invariant #14 (input-shape errors surface
first; return-type errors second). For `ScalarReturnType("ID")` returns, look up
`ctx.catalog.nodeIdMetadata(tia.inputTable().tableName())`; reject as
`"@mutation field '<name>' returns ID but table '<tableName>' is not a @node type"` if
absent. For other return types, `nodeIdMeta = Optional.empty()`.

Then construct the variant record with the per-variant fields described in §1a.

**`UnclassifiedField` rejection kind.** Use `AUTHOR_ERROR` for schema-author mistakes
(missing/duplicate `@table`, deferred input variants, return-type violations).
`INVALID_SCHEMA` is reserved for hard schema-validity violations like the existing
`@service` + `@mutation` mutual-exclusion check.

#### 1c. Validator additions

`validateMutationInsertTableField`, `validateMutationUpdateTableField`,
`validateMutationDeleteTableField`, `validateMutationUpsertTableField` are all currently empty
stubs.  They stay empty after Phase 1 — the classifier rejects bad shapes via `UnclassifiedField`
before validation is invoked.

#### 1d. Pipeline tests

> **Coverage status (post-DELETE).** DELETE shipped with three classifier scenarios in
> `GraphitronSchemaBuilderTest.rootFieldClassification` (`DELETE_MUTATION_FIELD`,
> `DELETE_MUTATION_NO_INPUT_ARG`, `DELETE_MUTATION_MISSING_LOOKUP_KEY`) plus the `VALID`
> case in `MutationDeleteTableFieldValidationTest`. The full table below is the target
> coverage across all four DML variants; landing each variant should add at minimum the
> rows that exercise verb-specific invariants, and we should backfill the cross-cutting
> rows (deferred input variants, listed inputs, return-type rejections) opportunistically
> as variants land. Aim for cross-cutting parity by the time UPSERT lands.

`GraphitronSchemaBuilderTest` additions (no emission yet, just classifier assertions):

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
| Any DML variant with `NodeIdField` in input | `UnclassifiedField` with Invariant #8 message |
| Any DML variant with `NodeIdReferenceField` in input | `UnclassifiedField` with Invariant #10 message |
| Any DML variant with `ColumnReferenceField` in input | `UnclassifiedField` with Invariant #9 message |
| DML variant with two `@table` input arguments | `UnclassifiedField` with Invariant #1 message |
| DML variant with a plain (non-`@table`) input arg | `UnclassifiedField` with Invariant #13 message |
| DML variant with listed input (`in: [FilmInput]`) | `UnclassifiedField` with Invariant #11 message |
| DML variant whose `@table` input arg carries `@condition` | `UnclassifiedField` with Invariant #12 message |
| DML variant with `@lookupKey` on a list-typed input field (e.g. `ids: [ID!]! @lookupKey`) | `UnclassifiedField` whose reason includes the `buildLookupBindings` per-field error |
| DML variant with non-`ID`/non-`T` return (e.g. `: Int`, `: Boolean`, `Connection<T>`) | `UnclassifiedField` with Invariant #14 message |
| DML variant targeting a `@node` table with composite key and `ScalarReturnType(ID)` return | classified successfully; `nodeIdMeta.keyColumns()` carries both key columns |
| DML variant targeting a non-`@node` table with `ScalarReturnType(ID)` return | `UnclassifiedField` with "not a @node type" message |
| DML variant targeting a non-`@node` (or composite-key) table with `TableBoundReturnType` return | classified successfully (`nodeIdMeta` is `Optional.empty()`; no node restriction applies) |
| `@service` + `@mutation` together | `UnclassifiedField` (existing check at `FieldBuilder.java:1665-1668`) |
| `@mutation` with no `typeName` argument | `UnclassifiedField` with existing "both absent" message — Phase 1b's `classifyMutationInput` must not fire before this fallthrough |

---

### Phase 2 — INSERT emission ▶ Next up

**Goal:** lift `MutationInsertTableField` from `NOT_IMPLEMENTED_REASONS`. Mirror DELETE's
landing shape: per-variant record fields, per-variant classifier helper
(`classifyMutationInsertField`), per-variant emitter (`buildMutationInsertFetcher`).
No shared `DmlTableField` / `classifyMutationInput` / `buildMutationReturnExpression`
helpers yet — those are a [Consolidation](#consolidation) follow-up that fires once INSERT
joins DELETE in the implemented set.

#### Per-variant record extension

Following §1a, extend `MutationInsertTableField` directly:

```java
record MutationInsertTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    String inputArgName,
    TableRef inputTable,
    List<InputField> fields,                          // every ColumnField, in declaration order
    Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,  // populated only for ScalarReturnType("ID")
    Optional<ErrorChannel> errorChannel
) implements MutationField {
    public MutationInsertTableField(/* …8-arg form, errorChannel = Optional.empty() */) { … }
}
```

INSERT does not need `fieldBindings` — `@lookupKey` is not required (Invariant #5) and is not
treated specially (Invariant: every `ColumnField` contributes to the column/value list, including
those that happen to be `@lookupKey`-bound). Carry `List<InputField> fields` so the emitter can
walk them in declaration order.

#### Per-variant classifier (`classifyMutationInsertField`)

Following §1b, write the helper inline in `FieldBuilder`. Copy `classifyMutationDeleteField`
as a starting point and adjust per the table in §1b:
- Pass 1 is identical (single `@table` input arg, reject other shapes).
- Pass 2 drops the empty-`fieldBindings` check (Invariants #2–#3 don't apply) and the
  PK-coverage check.
- Return-type validation and NodeId metadata resolution are identical.

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
        .returningResult(/* dispatch on returnType, see below */)
        .fetchOne(/* dispatch on returnType, see below */);
}
```

- **Java return type:** `Object`. Same rationale as DELETE — graphql-java coerces; declaring
  `Object` sidesteps the ID-vs-Long mismatch and lets `Record` / `Result<Record>` flow through
  without signature churn.
- **Column list:** every `InputField.ColumnField` in `f.fields()`, in declaration order.
  `@lookupKey` fields are included.
- **Values list:** parallel to the column list. Each value is
  `DSL.val(in.get("<sdlFieldName>"), Tables.T.<col.javaName()>.getDataType())`. The two-argument
  form delegates coercion to the column's registered `Converter` at bind time; no Java-side cast,
  no explicit type check. See the "Column value binding" convention in `rewrite-design-principles.md`.
- **Return expression dispatch:** inline a switch on `f.returnType()`, mirroring DELETE's
  emitter (`buildMutationDeleteFetcher` in `TypeFetcherGenerator.java`):
  - `ScalarReturnType("ID")` (Single) → `.returningResult(<key cols>).fetchOne(r -> NodeIdEncoder.encode(typeId, r.get(...), ...))`
  - `ScalarReturnType("ID")` (List) → `.returningResult(<key cols>).fetch(r -> NodeIdEncoder.encode(...))`
  - `TableBoundReturnType` (Single) → `.returningResult(Type.$fields(env.getSelectionSet(), table, env)).fetchOne(r -> r)`
  - `TableBoundReturnType` (List) → `.returningResult(...).fetch(r -> r)`
  - All other shapes are rejected at classifier time per Invariant #14.

The duplication between this dispatch and DELETE's is deliberate — same precedent, two emitters.
Lift to `buildMutationReturnExpression(f, names)` only after both are real and the diff is
limited to "INSERT runs `insertInto(...).values(...)` before returningResult; DELETE runs
`deleteFrom(...).where(...)`". See [Consolidation](#consolidation).

`NodeIdEncoder.encode` is varargs over the key columns; simple-key and composite-key tables emit
identical code patterns. The `nodeIdMeta` is populated from
`JooqCatalog.nodeIdMetadata(tableName)` at classify time and reached via `f.nodeIdMeta().orElseThrow()`
in the `ScalarReturnType("ID")` arm (safe: the classifier rejects when absent).

#### `RETURNING` with nested selections (`TableBoundReturnType`)

`Type.$fields` emits `DSL.multiset(...)` for child `TableField`/`NestingField` selections,
which run as correlated subqueries. PostgreSQL supports subqueries inside `RETURNING`, so this
shape works in principle, but no existing Graphitron fixture exercises
`INSERT ... RETURNING (multiset)` yet. **DELETE landed without an execution test,
so this is unverified for either verb.** The fall-back, if PostgreSQL rejects the shape,
is "RETURNING PK only, then a follow-up SELECT from the same `dsl`" — same `dsl` keeps the
read inside any caller-managed transaction. Document the choice in the landing commit.

If INSERT's pipeline test stays compile-only (no execution path against PostgreSQL), explicitly
note the verification gap in the commit message; otherwise we ship two unverified `RETURNING`
emitters in a row.

#### Implementation sites

- Add `String inputArgName, TableRef inputTable, List<InputField> fields,
  Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta` to `MutationInsertTableField`. Keep the
  no-channel convenience constructor.
- Add `classifyMutationInsertField(...)` in `FieldBuilder.java`; route the
  `case "INSERT" ->` arm of `classifyMutationField` to it.
- Add `buildMutationInsertFetcher(MutationInsertTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator.java`. Update the switch arm from `stub(f)` to the new call.
- Move `MutationInsertTableField.class` from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`
  in the same commit (`GeneratorCoverageTest` enforces the partition).
- Update `MutationInsertTableFieldValidationTest`'s `STUBBED` case to a `VALID` case (mirror
  `MutationDeleteTableFieldValidationTest`).
- Update `INSERT_MUTATION_FIELD` in `GraphitronSchemaBuilderTest.rootFieldClassification`
  to use a real `@table` input arg (mirror the post-DELETE `DELETE_MUTATION_FIELD` shape).

#### Tests

**Pipeline (required):**
- `INSERT_MUTATION_FIELD` happy path classifies as `MutationInsertTableField`.
- Add at least the negative cases that DELETE shipped:
  `INSERT_MUTATION_NO_INPUT_ARG`, `INSERT_MUTATION_LISTED_INPUT`, and one
  return-type rejection (e.g. `Int` return).
- Composite-PK case is worth adding for INSERT too — exercises the multi-column key-projection
  path in the emitter.

**Execution (recommended, deferred allowed):** mutations.md originally required this. Given
DELETE shipped without one, INSERT's commit message should call out whether an execution test
landed. If deferred, file a follow-up in the roadmap. Fixture work needed:
`graphitron-test/src/main/resources/graphql/schema.graphqls` has no `Mutation` type yet — the
first variant adds one with `type Mutation { … }`; subsequent variants extend it with
`extend type Mutation { … }`. Sakila's `film` table has several NOT-NULL columns without
defaults (`language_id`, `rental_duration`, `rental_rate`, `replacement_cost`); the fixture
input type must cover them or the INSERT fails at execute time per Invariant #6.

---

### Phase 3 — DELETE emission ✅ Landed

> **Landed** in commit `31c64a2` on `claude/graphitron-rewrite`. Notes below describe the
> shipped form; original spec text retained as the precedent for INSERT/UPDATE/UPSERT.
>
> **Shipped:** `MutationDeleteTableField` extended with `(String inputArgName, TableRef inputTable,
> List<InputColumnBinding> fieldBindings, Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
> Optional<ErrorChannel> errorChannel)`. `classifyMutationDeleteField` enforces Invariants
> #1, #2 (incl. PK coverage), #7-#11, #12, #13, #14 inline. `buildMutationDeleteFetcher`
> emits `dsl.deleteFrom(table).where(<chained .eq().and()>).returningResult(<keys or $fields>).fetchOne(r -> …)`.
> 911 → 924 unit tests pass; full pipeline (`mvn install -Plocal-db`) builds clean.
>
> **Deferred / not done:**
> - **No execution test against PostgreSQL.** `RETURNING (multiset)` for `TableBoundReturnType`
>   returns is unverified; same for the scalar-`ID` `NodeIdEncoder.encode` lambda. INSERT's
>   landing should not extend this gap.
> - **No production-schema gates.** The original Phase 1 plan called for grepping production
>   schemas before merge to (a) confirm the `PlainInputArg` carve-out for Invariant #13 is
>   not load-bearing and (b) sample return-type breakdowns to confirm the Invariant #14
>   strict gate doesn't swap rejection classes. Neither was done. INSERT is the highest-volume
>   mutation rejection — these checks matter more for it than they did for DELETE.
> - **`DmlTableField` supertype, `classifyMutationInput` helper, `buildMutationReturnExpression`
>   helper.** Punted to [Consolidation](#consolidation).
> - **Pipeline-test coverage is partial.** Three classifier scenarios shipped
>   (`DELETE_MUTATION_FIELD`, `DELETE_MUTATION_NO_INPUT_ARG`,
>   `DELETE_MUTATION_MISSING_LOOKUP_KEY`). Composite-PK happy path, missing-PK-column
>   error, listed-input gate, `@condition` gate, deferred-input variants, non-`@node`-table-
>   with-`ID`-return error, and most return-type rejections are not covered yet.

DELETE was implemented before UPDATE because DELETE's emitter is a strict subset of UPDATE's
shape (WHERE clause only, no SET clause); getting the simpler WHERE-only path working first
reduces the surface area for Phase 4.

#### Shape of emitted method

```java
public static Object deleteFilm(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .deleteFrom(Tables.FILM)
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(Tables.FILM.FILM_ID)
        .fetchOne(r -> r.get(Tables.FILM.FILM_ID));
}
```

- **WHERE clause**: each `InputColumnBinding` in `tia.fieldBindings()` contributes one
  `.eq(value)` predicate, combined with `DSL.and(...)`.
- **Return expression**: same rules as INSERT above.

When `tia.fieldBindings()` has a single binding, emit `.where(col.eq(val))`.
When multiple, emit `.where(col1.eq(v1).and(col2.eq(v2)))`.
In both cases `val` is `DSL.val(in.get(binding.inputFieldName()), Tables.T.COL.getDataType())`
— the two-argument form that delegates coercion to the column's `Converter` (see "Values list"
description in Phase 2 and the "Column value binding" convention in `rewrite-design-principles.md`).

The same `buildMutationReturnExpression` helper (introduced in Phase 2) is reused.

#### Implementation sites

- New `buildMutationDeleteFetcher(MutationDeleteTableField, String outputPackage, String jooqPackage)`
  in `TypeFetcherGenerator`.
- Switch arm changes from `stub(f)` to the new emitter call.
- Remove `MutationDeleteTableField` from `NOT_IMPLEMENTED_REASONS` (line 212) and add it to
  `IMPLEMENTED_LEAVES` (line 142) in the same commit.

#### Empty-match semantics

When the WHERE clause matches no row, `.fetchOne(...)` returns `null`.  The emitted method's
`Object` return flows that `null` through graphql-java unchanged.  For a nullable `ID` return
type, this surfaces as GraphQL null; for `ID!`, graphql-java raises a non-null violation at the
protocol layer.  This is the declared contract — schema authors using `ID!` on UPDATE/DELETE
are asserting that the WHERE clause always matches, and the protocol-layer error is the right
signal when it doesn't.  Same applies to Phase 4.

#### Tests

Pipeline and execution tests following the same pattern as Phase 2.
Execution test: `deleteFilm(in: {filmId: 1})` — asserts the film is gone from the DB afterward
and the returned ID matches.
Negative execution test: `deleteFilm(in: {filmId: 99999})` on a nullable `ID` return — asserts
`null` response.

---

### Phase 4 — UPDATE emission

Same per-variant shape as INSERT (§Phase 2) and DELETE (§Phase 3). UPDATE combines INSERT's
value-binding pattern (the SET clause) with DELETE's WHERE-clause pattern. By the time UPDATE
lands, the duplication between the three emitters' return-expression dispatch should be the
trigger for [Consolidation](#consolidation) — extract `buildMutationReturnExpression` and
fold the three switches into it.

#### Per-variant record extension

```java
record MutationUpdateTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    String inputArgName,
    TableRef inputTable,
    List<InputField> fields,                          // for the SET clause
    List<InputColumnBinding> fieldBindings,           // for the WHERE clause
    Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
    Optional<ErrorChannel> errorChannel
) implements MutationField { /* no-channel convenience constructor */ }
```

UPDATE needs **both** `fields` (SET clause) and `fieldBindings` (WHERE clause). Invariant #4
guarantees there's at least one non-`@lookupKey` field for the SET clause.

#### Per-variant classifier (`classifyMutationUpdateField`)

Copy `classifyMutationDeleteField` and add Invariant #4 (UPDATE has no non-`@lookupKey` fields →
empty SET clause → reject). Everything else (single `@table` arg, listed-input gate, deferred
input-field variants, `@condition` rejection, PK coverage, return-type validation) is identical
to DELETE.

#### Emitter (`buildMutationUpdateFetcher`)

```java
public static Object updateFilm(DataFetchingEnvironment env) {
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    Map<?, ?> in = (Map<?, ?>) env.getArgument("in");
    return dsl
        .update(Tables.FILM)
        .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
        .where(Tables.FILM.FILM_ID.eq(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType())))
        .returningResult(/* dispatch on returnType */)
        .fetchOne(/* dispatch on returnType */);
}
```

- **SET clause:** every `InputField.ColumnField` in `f.fields()` whose `name()` is **not** the
  `inputFieldName()` of any `f.fieldBindings()` entry contributes one `.set(col, val)`.
- **WHERE clause:** `f.fieldBindings()` entries, chained with `.and(...)` exactly as DELETE does.
- **Return expression dispatch:** identical to INSERT and DELETE — same four cases, same code
  shape. If `buildMutationReturnExpression` has been extracted by the time UPDATE lands, call
  it; otherwise inline the switch.
- **Empty-match semantics:** `.fetchOne(...)` returns `null` when the WHERE clause matches no
  row, same as DELETE. For nullable returns this surfaces as GraphQL null; for `ID!`/`Type!`
  graphql-java raises a non-null violation. Schema authors using `!` are asserting the row
  exists.

#### Implementation sites

- Add the per-variant fields to `MutationUpdateTableField`.
- Add `classifyMutationUpdateField(...)` in `FieldBuilder.java`; route `case "UPDATE" ->` to it.
- Add `buildMutationUpdateFetcher(...)` in `TypeFetcherGenerator.java`. Update switch arm.
- Move `MutationUpdateTableField.class` from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`.
- Update `MutationUpdateTableFieldValidationTest` and `UPDATE_MUTATION_FIELD` schema test.

#### Tests

Execution test: `updateFilm(in: {filmId: 1, title: "Updated"})` — asserts the title changed and
the returned ID is 1.

---

### Phase 5 — UPSERT emission

UPSERT lands after UPDATE. Same per-variant pattern. The record needs both `fields` (INSERT
column list and ON CONFLICT DO UPDATE SET clause) and `fieldBindings` (ON CONFLICT key).

#### Per-variant record extension

Mirror UPDATE's record shape (same set of fields).

#### Per-variant classifier (`classifyMutationUpsertField`)

Same as UPDATE, **minus** Invariant #4 (UPSERT allows all-`@lookupKey` inputs as
`INSERT ... ON CONFLICT DO NOTHING`) and **minus** the PK-coverage check
(UPSERT's ON CONFLICT key is a unique-constraint choice, not required to match the full PK —
Invariant #2's UPSERT exemption).

#### Emitter (`buildMutationUpsertFetcher`)

jOOQ supports `INSERT ... ON CONFLICT ... DO UPDATE SET ...` via:

```java
dsl.insertInto(Tables.FILM, cols...)
   .values(DSL.val(in.get("filmId"), Tables.FILM.FILM_ID.getDataType()), /* … */)
   .onConflict(Tables.FILM.FILM_ID)   // @lookupKey columns from f.fieldBindings()
   .doUpdate()
   .set(Tables.FILM.TITLE, DSL.val(in.get("title"), Tables.FILM.TITLE.getDataType()))
   .returningResult(/* … */)
   .fetchOne(/* … */);
```

- **`onConflict` columns:** `f.fieldBindings()` entries.
- **Conflict action:** if `f.fields()` has at least one `ColumnField` whose `name()` is not in
  any `fieldBindings()` entry, emit `.doUpdate().set(...)` over those non-`@lookupKey` fields.
  If every field is `@lookupKey` (no SET-clause fields), emit `.doNothing()` instead — jOOQ
  rejects a `doUpdate()` with zero `.set(...)` calls; `doNothing()` is the required API path
  for `INSERT ... ON CONFLICT DO NOTHING`.
- **INSERT column list:** every `InputField.ColumnField` in `f.fields()` in declaration order,
  identical to INSERT's. Includes `@lookupKey` fields (they supply the user-provided PK on the
  insert branch). Auto-generated PKs that schema authors don't expose never produce a
  `ColumnField` and are therefore absent — the correct shape for `ON CONFLICT`.
- **Return expression dispatch:** same as INSERT/UPDATE/DELETE.

PostgreSQL-only — `ON CONFLICT` is a Postgres extension; the rewrite targets PostgreSQL.

#### Implementation sites

- Add per-variant fields to `MutationUpsertTableField` (mirror UPDATE).
- Add `classifyMutationUpsertField(...)` and `buildMutationUpsertFetcher(...)`.
- Move `MutationUpsertTableField.class` to `IMPLEMENTED_LEAVES`.
- Update validation and schema-builder tests.

#### Tests

Execution test: `upsertFilm(in: {filmId: 1, title: "Upserted"})` — asserts correct insert-or-update
semantics.

---

### Phase 6 — Service mutations

**Prerequisite (satisfied).** Service-root-fetchers (Done) introduced
`ArgCallEmitter.buildMethodBackedCallArgs`, the declaration-order param-list helper this phase
reuses verbatim. It also wired `validateRootServiceInvariants` (§1 / §2) and the strict
`@service` return-type check into the mutation arm of `classifyMutationField`, so this phase
inherits classifier-time rejection for connection returns, batch-keyed parameters, and
mismatched return types without further work.

**Synchronous, no DataLoader.**  Both service-mutation variants emit synchronous methods — same
as root service queries.  Root mutation fields have no parent-batching context; per-request
concurrency is irrelevant, and the developer-supplied method owns any transaction scope.

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
`TypeFetcherGenerator`.  Call `buildServiceFetcherCommon` directly (the same shared helper that
`buildQueryServiceTableFetcher` delegates to) rather than going through the query-flavoured
wrapper; the two variants have identical record shapes and the common helper already handles both.

#### `MutationServiceRecordField`

The classifier at `FieldBuilder.java:1682-1685` maps this variant to two distinct
`ReturnTypeRef` shapes, both of which this emitter must handle:

- **`ReturnTypeRef.ScalarReturnType`** — scalar (e.g. `Int`, `String`, `Boolean`), plain DTO,
  or any non-table non-record Java type.  Emitted body: `return SomeService.method(...);` with
  return type `Object`.  graphql-java coerces to the declared SDL type.
- **`ReturnTypeRef.ResultReturnType`** — a `@record`-annotated GraphQL type backed by a jOOQ
  `Record` subclass.  The service returns the record directly; graphql-java's registered
  property/record fetchers walk its fields.  No projection.

Both shapes share the same argument-list emission (`buildMethodBackedCallArgs` over
`method().params()`, with `DslContext` / `Arg` / `Context` expressions) and both compile against
`Object` as the method signature.  Add `buildMutationServiceRecordFetcher(MutationServiceRecordField, ...)`
with an internal switch on the `ReturnTypeRef` variant only if emission actually diverges (it
currently doesn't — the only observable difference is in graphql-java's coercion path, not the
generator output).

Both service variants: remove from `NOT_IMPLEMENTED_REASONS` (line 212) and add to
`IMPLEMENTED_LEAVES` (line 142) in the same commit (`GeneratorCoverageTest` enforces the
disjoint partition).  Both use the shared `buildMethodBackedCallArgs` helper.  Pass `null` for
`tableExpression` — root mutation
fields have no parent table context, so no `ParamSource.Table` slot will be present in the
method's param list.  The emitter already handles `null` there for root service queries.

#### Tests

Pipeline tests: one case per variant asserting the emitted method calls the service method and
does not emit `$fields`.
Execution tests: a fixture service method on the mutation type; assert the returned value flows
through graphql-java's registered fetchers.

---

## Consolidation

Triggered when **two or more DML variants are real** (i.e. live in `IMPLEMENTED_LEAVES`).
Goal: collapse the per-variant duplication into shared structure without abstracting on
guesses.

The pieces, in order of expected payoff:

1. **`buildMutationReturnExpression(field, names)` on `TypeFetcherGenerator`.** The return-type
   dispatch is identical across INSERT/UPDATE/DELETE/UPSERT (four cases: `ScalarReturnType("ID")`
   single/list, `TableBoundReturnType` single/list). Each emitter currently inlines that switch.
   The shared helper takes the per-variant data the dispatch needs (return type, `nodeIdMeta`,
   resolved table names) and emits two `CodeBlock`s — `returningResult(...)` and the terminal
   `.fetchOne(r -> ...)` / `.fetch(r -> ...)`. The caller splices them in. Highest payoff per
   line of churn; lift first.

2. **`classifyMutationInput(fieldDef, verb)` helper in `FieldBuilder`.** The Pass 1 / Pass 2
   structure is identical across the four DML variants; only the per-verb invariant table varies
   (see §1b). Lift after the second variant lands. The helper returns a small result type
   (resolved `TableInputArg` + per-verb errors); the caller still owns the variant construction
   so it can pass the per-verb extra data to the right record components.

3. **`MutationField.DmlTableField` sealed supertype.** Permits the four DML records, declares
   the common accessors `(returnType, inputArgName, inputTable, fields, fieldBindings,
   nodeIdMeta, location, errorChannel)`. Lift only after #1 and #2 land — the sealed level pays
   off when an external callsite (e.g. validation) wants to dispatch over "any DML mutation".
   Without that callsite, the supertype is purely additive ceremony.

Each step is independently shippable. Resist landing all three in one commit; they have
different review costs and the supertype is the easiest to get wrong.

**Lift triggers (any one):**
- Three of the four DML variants are real.
- A bug fix needs to be applied identically to two emitters.
- A new invariant lands on multiple variants in one PR.

---

## Non-goals

- **Listed inputs** (`in: [FilmInput]`): the `list` flag on `TableInputArg` causes a classifier
  gate (Invariant #11).  Unblocking listed inputs requires iterating over the input list and
  constructing a batch INSERT / batch UPDATE / DELETE / UPSERT.  The legacy code uses jOOQ
  `batchInsert` / `batchUpdate` / `batchDelete` / `batchStore` for this.  Tracked as a follow-up.
- **Nested input types** (`NestingField`): deferred per Invariant #7.
- **`InputField.NodeIdField` in mutation inputs**: deferred to argres Phase 3's
  `NodeIdBinding` variant of `InputColumnBinding` (Invariant #8).
- **`ColumnReferenceField` in mutation inputs**: deferred (Invariant #9).
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
  return, a follow-up plan will extend `buildMutationReturnExpression` with an arm that uses
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
- **`@mutation` + `@service` combination**: already rejected at classifier time
  (`FieldBuilder.java:1665-1668`).

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
- **Promoting `ArgumentRef` (or `TableInputArg`) to `model/`** — *deferred*. Today the model
  variants carry the data the emitter needs as separate fields (DELETE precedent). Promoting
  the ArgumentRef hierarchy to `model/` is a possible consolidation step but needs a real driver:
  pick it up if INSERT/UPDATE/UPSERT show field-set drift between variants that becomes painful
  to maintain by hand.
