# Graph Build Fixes

Plan for resolving validation errors encountered when building a large production graph (the SIS subgraph). The errors are grouped by root cause; each section describes the problem, the fix, and the implementation steps.

Source material: `generator-schema-errors.org` and `generator-schema.graphql` on branch `claude/validation-test-coverage-plan-r8PAv`.

---

## Dogfooding: Error Message Quality

Two error message defects that make the build output harder to act on.

### F1 — Field errors omit parent type name

**Observed:** `error: Field 'emne'`

**Expected:** `error: Field 'AnbefaltForkunnskap.emne': could not be classified — …`

The field name alone is ambiguous in any real graph; dozens of types can have a field called `emne` or `id`. The `GraphitronField` hierarchy already carries `parentTypeName()` and `name()` — the information exists, it just isn't included in the message.

**Fix:** Wherever the validator or the builder constructs a `ValidationError` for a field, use `parentTypeName() + "." + name()` as the identifier. Check every `new ValidationError(…)` call that currently formats `"Field '" + name + "'"` and replace it with `"Field '" + parentTypeName + "." + name + "'"`.

### F2 — Condition error says "unknown" for className-based references

**Observed:** `condition method 'unknown' could not be resolved`

**Root cause:** `extractConditionQualifiedName()` reads `ARG_NAME` (the deprecated `name` field on `ExternalCodeReference`). When the schema uses the current `className` form — `{className: "…", method: "…"}` — `ARG_NAME` returns null and the fallback is the string `"unknown"`.

**Fix in `GraphitronSchemaBuilder.extractConditionQualifiedName()`:**
```java
private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
    Object name = conditionMap.get(ARG_NAME);
    if (name != null) return name.toString();
    String cls    = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
    String method = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
    if (cls != null && method != null) return "method '" + method + "' in class '" + cls + "'";
    if (cls != null) return "class '" + cls + "'";
    return "unknown";
}
```

The format `"method 'X' in class 'Y'"` matches `ServiceCatalog`'s existing failure messages (`"method 'X' not found in class 'Y'"`), keeping the style consistent across all reference-resolution errors.

These two fixes are independent one-liners; ship them first so every subsequent build run produces actionable output.

---

## @orderBy — Two Independent Gaps

### O1 — Sort enum values tagged with `@index` not recognised

**Observed:** `@orderBy input type 'QueryEmnerOrderByInput' must have exactly one direction field, but found multiple`

**Root cause:** `resolveOrderByArg()` identifies the sort-field enum by checking whether any enum value carries `@order`. The SIS schema uses the older `@index` directive (still present in `directives.graphqls`, marked deprecated). Because `QueryEmnerOrderByField` values have `@index` but not `@order`, `isSortEnum` evaluates to `false` for both fields in the input type, so both are classified as "direction fields".

**Fix in `resolveOrderByArg()`:**
```java
boolean isSortEnum = enumType.getValues().stream()
    .anyMatch(v -> v.hasAppliedDirective("order") || v.hasAppliedDirective("index"));
```

`@index` is declared `on ENUM_VALUE` only in `directives.graphqls` — it cannot appear at the enum type level. The value-stream check is correct and complete.

### O2 — `@orderBy` input types misclassified at the type level

**Observed:** `Type 'OrganisasjonsnavnOrderBy': could not be classified — field 'direction' column 'direction'`

**Root cause:** `buildInputType()` calls `findReturnTablesForInput()` to discover which table an input type belongs to. That method scans all query fields that accept the input as an argument and whose return type has `@table`. For an `@orderBy` argument, the field does have a `@table`-backed return type — so the input gets bound to that table, then `buildTableInputType()` fails to resolve `direction` and `orderByField` as columns.

**Fix in `findReturnTablesForInput()`:** skip arguments annotated with `@orderBy` when collecting table associations:
```java
boolean usesInput = fieldDef.getArguments().stream()
    .filter(arg -> !arg.hasAppliedDirective(DIR_ORDER_BY))   // ← add this
    .anyMatch(arg -> inputTypeName.equals(
        ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName()));
```

With this change, `@orderBy` input types are left without a table association and fall through to `buildNonTableInputType()` → `PojoInputType`, which is correct. The `@orderBy` argument's input type is not a table-row type; it is an instruction to the sort clause.

---

## @error — Classification Gaps

Three related problems all stem from the builder not fully supporting the `@error` pattern.

### E1 — `interface Error` fails because all implementing types are non-table-bound

**Observed:** `Type 'Error': could not be classified — implementing type 'UgyldigInput' is not table-bound (missing @table directive); …` (53 implementing types listed)

**Root cause:** The second-pass `enrichInterfaceType()` calls `buildParticipantList()`, which requires every implementing type to be `TableBackedType`. Error types (classified as `ErrorType`) are not table-backed, so the interface becomes `UnclassifiedType`.

### E2 — Error union types fail for the same reason

**Observed:** `Type 'AktiverFagpersonerError': could not be classified — implementing type 'UgyldigInput' is not table-bound`

Same root cause: `enrichUnionType()` → `buildParticipantList()` → rejects `ErrorType` members.

### E3 — Error type fields (`path`, `message`) become unclassifiable

**Observed:** `Field 'path': could not be classified — parent type 'AntallTilbudForLavt' has no supported Graphitron classification`

This is a cascade: once `interface Error` becomes `UnclassifiedType`, the validator reports an error for every field on every implementing type. Fixing E1 and E2 will eliminate E3 automatically.

### Design

**Generalise, not patch.** The root cause applies to three cases in the SIS graph: error types (`@error`), structural interfaces like `Datoperiode`, and error unions. Rather than fixing each independently and then merging, generalise `buildParticipantList()` once: accept any non-table-bound type whose fields require no SQL generation. The condition is: the implementing type is not `TableBackedType` AND is not itself `UnclassifiedType`. This covers `ErrorType`, any future structural types, and value types.

```java
if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
    // existing table-backed path
} else if (gt != null && !(gt instanceof UnclassifiedType)) {
    result.add(new ParticipantRef(typeName, null, null));  // no table, no discriminator
} else {
    errors.add("implementing type '" + typeName + "' is not table-bound (missing @table directive)");
}
```

**Null `TableRef` safety.** `ParticipantRef.table()` is currently a non-null `TableRef`. Allowing null requires auditing every call site. The current validator stub `validateParticipants()` does nothing — no dereference. The generators do not yet use `participants()` at all (those field variants are unimplemented). Before step 3, add a `boolean isTableBound()` method to `ParticipantRef` and switch all future generator code on it rather than null-checking `table()` directly:

```java
public record ParticipantRef(String typeName, TableRef table, String discriminatorValue) {
    public boolean isTableBound() { return table != null; }
}
```

**Mixed unions.** A union mixing table-backed and error members (e.g. `union Result = SomeEntity | UgyldigInput`) is not expected in the SIS graph but is not structurally invalid. With the generalised fix above, `buildParticipantList()` will classify it as a `UnionType` with mixed participants. The generator must check `participant.isTableBound()` and skip non-table participants in SQL generation; the type resolver still dispatches all members. This is acceptable — document it in the `UnionType` Javadoc.

**`ErrorUnionType` variant dropped.** With the generalised fix, there is no need for a separate `ErrorUnionType`. Pure error unions become `UnionType` with all-null-table participants, which the generator handles via `isTableBound()` checks. The validator's `UnionType` case remains a no-op (`{}`) since all validation is done at build time.

**Implementation steps:**
1. Add `isTableBound()` to `ParticipantRef`
2. Generalise `buildParticipantList()` to accept non-table-bound, non-unclassified types (covers `ErrorType`, structural types, any future non-SQL type)
3. In `enrichInterfaceType()` and `enrichUnionType()`: no additional changes needed — both call `buildParticipantList()` which now handles all cases
4. In any future generator code that iterates `participants()`, gate SQL-emitting paths behind `participant.isTableBound()`

---

## @condition — Reference Path Resolution (P3 stub)

**Observed:**
- `condition method 'unknown' could not be resolved` (className-form, fix F2 improves message)
- `condition method 'CONDITION_VURDERINGSOPPBYGNING' could not be resolved` (name-form)
- `condition method 'apiHendelseEmneJoinCondition' could not be resolved` (className-form, emne hendelse)

**Root cause:** `resolveConditionRef()` is an explicit stub (`return null`), documented as "Condition resolution via reflection is implemented in a later deliverable (P3)". Every `@reference(path:[{condition:{…}}])` in the schema therefore produces an `UnclassifiedField`.

This is the single largest source of errors in the SIS build — the graph makes heavy use of condition joins (same-table self-joins, cross-table joins without FK, filtered references).

`ExternalCodeReference` has two addressing forms:
- **Class form:** `{className: "no.fellesstudentsystem.…Conditions", method: "methodName"}` — resolved via reflection, same mechanism as `@service`
- **Name form:** `{name: "CONDITION_X", method: "methodName"}` — a symbolic name registered in the plugin configuration (same "available values are set in the plugin configuration" note as `@service` and `@tableMethod`)

### Design

**Prerequisites confirmed:**

`ConditionJoin` already carries `MethodRef condition` — `record ConditionJoin(MethodRef condition, String alias)`. The stub `resolveConditionRef()` returns null, so the builder currently emits an error instead of a `ConditionJoin`. No model change is needed; the fix is implementing the resolution.

Condition methods have the form `Condition foo(SomeTable t1, OtherTable t2)`. `ServiceCatalog.reflectTableMethod()` already classifies `org.jooq.Table<?>` parameters as `ParamSource.Sources` (line 228). Condition methods must therefore be reflected via `reflectTableMethod()`, not `reflectServiceMethod()` — using the wrong path would leave table-alias parameters unrecognised.

**Implementation steps:**
1. Implement `resolveConditionRef()`: resolve `className` from class form or name form (see Named References section); call `ServiceCatalog.reflectTableMethod()` to build the `MethodRef`
2. In `BuildContext.parsePathElement()`: the existing error path (`resolved == null → errors.add(…)`) stays; once `resolveConditionRef()` returns a real value, it is automatically used
3. Generator: emit the condition method call in the JOIN `ON` clause using `ConditionJoin.condition()` and its parameters

This work is coupled to the named-reference resolution (next section) for the name form.

---

## Named References — `name:` Form of `ExternalCodeReference`

**Observed:** `service method could not be resolved — service reference is incomplete`

**Root cause:** `parseExternalRef()` reads `ARG_CLASS_NAME` but ignores the deprecated `name` field. When a field uses `@service(service: {name: "SERVICE_PERSONBILDE"})` or a reference uses `condition: {name: "CONDITION_EMNE", method: "…"}`, `parseExternalRef()` returns `ExternalRef(null, method)` and `reflectServiceMethod(null, …)` fails immediately.

**Legacy mechanism:** The old codegen uses `ExternalReferences` — a wrapper around a `Map<String, Class<?>>` built from the plugin's `externalReferences` config list. `CodeReference` reads both `name` (→ `schemaClassReference`) and `className`. `ExternalReferences.getClassFrom()` resolves: className → `Class.forName` + import-path lookup; name → map lookup. The map itself is not present in the rewrite at all; it's missing config plumbing.

**Fix:**
1. Add a `Map<String, String> namedReferences` field to `RewriteConfig` (via `setProperties()`, consistent with how `outputPackage`, `jooqPackage`, and feature flags are carried — avoids threading a new parameter through `GraphitronSchemaBuilder`'s constructor)
2. In the Maven plugin's `GenerateMojo`, populate `namedReferences` from the existing `externalReferences` config list (same list that feeds the legacy `ExternalReferences` map)
3. In `parseExternalRef()`, when `ARG_CLASS_NAME` is null but `ARG_NAME` is present: look up in `RewriteConfig.namedReferences()`; if found, use the resolved class name; if not found, classify the field as `UnclassifiedField` with message `"named reference 'X' not found in namedReferences config"` — a build-failure error, not a warning
4. Log a deprecation warning per-field when the `name` form is used: `"ExternalCodeReference 'name' is deprecated; use 'className' instead"`

**Note:** This also unblocks the `name` form in `@condition` reference paths (see previous section).

---

## Input Type Polymorphism

**Observed:** `Type 'EmnekodeInput': could not be classified — used as argument on fields with conflicting return tables: 'emne', 'studentvurderingsforsokforemne'`

**Root cause:** `findReturnTablesForInput()` finds the same input type used as argument for query fields with different `@table`-backed return types. When more than one distinct table is found, it currently falls through to `UnclassifiedType`.

**Semantics:** `EmnekodeInput` carries a composite key (emnekode + versjonskode) that is a shared identifier across multiple tables. The current approach — binding the input type to one specific table — doesn't work here. The input is not a mutation input tied to one row type; it is a query key whose meaning depends on the field it's on.

**Fix:** When `findReturnTablesForInput()` finds conflicting tables, classify the input as `PojoInputType` (unbound) rather than `UnclassifiedType`. Validate that each individual field using the input supplies enough context to resolve the column mapping at field-classification time (i.e., the field's return table provides the column, not the input's own declared table).

**Implementation:** In `buildInputType()`, change the conflicting-tables branch:

```java
if (tables.size() > 1) {
    // Multiple return tables → unbound input; resolve columns per-field-usage
    return buildNonTableInputType(inputType, name, location);
}
```

At field-classification time, when a `PojoInputType` (unbound) appears as a `@lookupKey` or filter argument, the builder must validate it eagerly: iterate the input's `InputField` list and verify each field name resolves to a column on `returnType().table()`. Any unresolved field produces an `UnclassifiedField` with a message naming the input field and the table — not a deferred generator-time failure. This check belongs in `FieldBuilder` at the point where `@lookupKey` argument types are resolved, before the field is promoted to a lookup variant.

---

## `@lookupKey` with List Input Type

**Observed:** `Field 'organisasjonsenhetsrollerGittLegacyIder': result type does not match input cardinality`

```graphql
organisasjonsenhetsrollerGittLegacyIder(
  eierOrganisasjonskode: String! @field(name: "INSTITUSJONSNR_EIER"),
  legacyIder: [PersonrolleLegacyIdInput] @lookupKey
): [Organisasjonsenhetsrolle]
```

The argument is a **list of composite-key inputs** (`[PersonrolleLegacyIdInput]`) feeding into a list return type. This is the composite-key lookup pattern described in step 9 of `argument-resolution.md`.

This item has no new design; it is unblocked by implementing `TableInputArg` in the argument-resolution plan. The specific error message ("result type does not match input cardinality") suggests the current cardinality check is too strict — it should allow `List<InputType> @lookupKey` → `List<T>`.

Flag this as a known gap against the argument-resolution plan so it gets a test case when that work lands.

---

## Remaining Unclassified Errors

These errors exist in the SIS graph but are either schema-side issues or require separate investigation. They are catalogued here so they are not lost.

### @condition on arguments (already deferred)

`Field 'fagpersonerGittFodselsnumre': could not be classified — argument 'fodselsnumre': @condition is only supported on field definitions, not on arguments`

This is explicitly deferred in `argument-resolution.md` (decision table item 1). The error rejection stays until the argument-level @condition work is scheduled.

### Filter inputs with non-column fields

Several input types used as filter arguments have fields that don't map to table columns:

- `EmneBeskrivelserFilterInput.gjelderFraTerminer`
- `EmneUndervisningsoversiktUndervisningsterminerFilterInput.arstall`
- `FagpersonUndervisningsenhetsrollerFilterInput.terminkoder`
- `PubliseringsklartEmneForTerminBeskrivelserFilterInput.gjelderForTerminer`
- `StudentStudieprogramISemesterFilterInput.terminkoder`
- `StudentVedLarestedVurderingsmeldingerFilterInput.gjennomforesIInnevarendeTermin`

**Open question:** Are these fields supposed to be handled by `@condition` on the argument or input field? Or do they use a different pattern (e.g., an inline subquery via a helper table)? The answer determines whether these are fixed by the argument-resolution `@condition` work or require a separate classification.

### Structural (non-table) interfaces

`Type 'Datoperiode': could not be classified — implementing type 'EmnerolleGyldighetsperiode' is not table-bound`

`Datoperiode` is an interface for period types (fraTermin/tilTermin). Its implementing types are nested structural types, not table-backed entities — similar in spirit to the `Error` interface. If this is a recurring pattern (interfaces used purely for structural typing, not for polymorphic dispatch), the E1/E2 fix may need to be generalised to allow interfaces whose members are all non-table-bound.

The E1 fix generalises `buildParticipantList()` to accept any non-table-bound, non-unclassified type — `Datoperiode` is fixed for free by that change, with no schema annotation needed.

### Apollo Federation `_service` field

`Field '_service': could not be classified — return type '_Service' is not a @table, interface, or union Graphitron type`

`_service` is a built-in Federation field that returns schema SDL. It should be classified as `NotGeneratedField` (the runtime provides it).

**Root cause:** The error message says the *return type* `_Service` could not be classified, not the field itself. `_Service` is never annotated with `@table` or any Graphitron directive, so `TypeBuilder` produces `UnclassifiedType` for it, which then causes the field classification to fail.

**Fix options:**
- In `TypeBuilder`, add a guard: types whose name starts with `_` (or specifically `_Service`) are skipped and produce no `GraphitronType` entry — the type classifier simply ignores them.
- Alternatively, add `name.equals("_service")` alongside the existing `name.equals("_entities")` exact-match branch in `FieldBuilder` and emit `NotGeneratedField` there; `_Service` would still be classified as `UnclassifiedType` but the field would never reach the type-lookup step.

The `TypeBuilder` guard is cleaner because it prevents `_Service` from polluting the type map at all. The `FieldBuilder` guard is more targeted if other `_`-prefixed types should remain classifiable.

### Schema-side issues (not Graphitron bugs)

These appear to be incorrect foreign key references or missing FK registrations in the jOOQ catalog:

- `Field 'pakrevdeEmner': key 'EMNE_FORUTSATT__FORUTSETTES__EMNE__FK' does not connect to table 'FORKUNNSKAPSKRAV'`
- `Field 'plassertPaUndervisningspartier': no foreign key found between tables 'UTDANNINGSPLAN_EMNE' and 'STUDENT_PA_UNDERVISNINGSPARTI'`
- `Field 'protokollFoerteEmnesamlinger': no foreign key found between 'STUDENTVURDKOMBPROTOKOLL' and 'EMNE_OPNEMNESAMLPROT'`
- `Field 'studienivaintervallkode': no foreign key found between tables 'studieprogram' and 'STUDIENIVAINTERVALL'`

Investigate whether the FK exists in the database but is missing from the jOOQ generated classes, or whether the `@reference` path in the schema is wrong.

### Truncated column name

`Field 'kode': could not be classified — column 'TIDSENHET_'`

The column name `TIDSENHET_` appears truncated (missing suffix). Check whether the `@field(name:)` value in the schema is cut off, or whether the jOOQ catalog has a truncated column name due to a database identifier length limit.

---

## Legacy platformId (Low Priority)

Several mutation input types use `id: ID!` as the identifier field, but the underlying table has no column named `id`. Examples:

```graphql
input AngiBankkontonummerForPersonProfilInput @table(name: "PERSON") {
  id: ID!
  bankkontonummer: String @field(name: "BANKKONTONR")
}
```

The `PERSON` table uses a legacy composite platform key; there is no `id` column.

**How the legacy codegen handles it:** The project uses a custom jOOQ code generator that adds `getId()` / `setId(String)` convenience methods to every generated jOOQ table record. These methods encode/decode the composite primary key into a platform-compatible ID string. In `LookupHelpers.getKeyFieldBlock()`, when an `id: ID!` field has no `@nodeId`, the legacy codegen emits `record.getId()` / `record.setId(input)` directly via the field's `MethodMapping` (i.e. the GraphQL field name `id` is camel-cased to `getId`/`setId`). The custom jOOQ method handles the encoding/decoding internally.

**Design for the rewrite:**

At classification time, when `buildInputColumnField()` fails to find an `id` column in the jOOQ table, the builder should not immediately produce `UnclassifiedType`. Instead, check whether the jOOQ record class has a `setId(String)` / `getId()` method (detectable via reflection on the `TableRef`'s record class). If those methods exist, classify the field as a new `InputField.PlatformIdField` variant carrying the `TableRef` and the method names.

The generator then emits `record.setId(input.getId())` for mutation inputs using this variant, relying on the custom jOOQ method for the actual encoding.

**Implementation steps:**
1. Add `InputField.PlatformIdField` to the `InputField` sealed hierarchy (alongside the existing `ColumnField`)
2. In `buildInputColumnField()`, when `catalog.findColumn()` returns empty for an `id` field on an `ID` type, reflect the record class for `getId`/`setId` methods; if found, return `PlatformIdField`
3. Add `case PlatformIdField ignored -> {}` to the validator (no structural checks needed)
4. In the mutation generator, emit `record.setId(…)` for `PlatformIdField`

This is low priority since it only affects graphs that predate the `@nodeId` directive. New schemas should use `@node` + `@nodeId`.

**Prerequisite — `TableRef` does not carry the record class.** `TableRef` is `record(String tableName, String javaFieldName, String javaClassName, List<ColumnRef> primaryKeyColumns)` — no `Class<?>` component. Step 2 requires the record class to reflect `getId`/`setId`. The prerequisite is: `JooqCatalog` must expose `table.getRecordType()` at build time (available on jOOQ's `Table<?>` before abstraction), and pass it into `TableRef` as a `Class<?>` (or via a `JooqCatalog` lookup method keyed on `tableName`).

**Failure mode error message.** If `catalog.findColumn()` returns empty for an `id` field and `getId`/`setId` are not found on the record, the fallback should produce a specific error: `"field 'id' has no matching column and no platformId methods (getId/setId) found on record class — use @nodeId for Relay IDs"`, not the generic "could not be classified" message.

---

## Priority Order

| # | Item | Effort | Blocks |
|---|------|--------|--------|
| F1 | Field errors include parent type | Trivial | Better diagnostics for all other work |
| F2 | Condition error message "unknown" | Trivial | Clearer @condition errors |
| O1 | `@index` support in `resolveOrderByArg` | Small | @orderBy fields in SIS |
| O2 | `@orderBy` input type classification | Small | @orderBy input types in SIS |
| E1–E3 | @error interface/union classification | Medium | All error types and unions |
| IP | Input type polymorphism | Small | Shared key inputs (EmnekodeInput) |
| NR | Named reference resolution | Medium | Named @service + @condition (depends on config answer) |
| C1 | @condition in reference paths (P3) | Large | All condition joins in SIS |
| LK | @lookupKey list input (composite) | Medium | Covered by argument-resolution plan |
| Misc | _service, structural interfaces, FK issues | Small–Medium | Covered individually |
| LPid | Legacy platformId | Unknown | Needs design input first |
