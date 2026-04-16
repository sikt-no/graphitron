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
    if (cls != null && method != null) return cls + "#" + method;
    if (cls != null) return cls;
    return "unknown";
}
```

These two fixes are independent one-liners; ship them first so every subsequent build run produces actionable output.

<review>
The `#` separator in `cls + "#" + method` (e.g. `"no.sikt.Conditions#myCondition"`) — check that this matches the format used in other error messages in the codebase that refer to class+method pairs. If `ServiceCatalog` or the validator uses `.` (e.g. `"no.sikt.Conditions.myCondition"`) then `#` will be inconsistent. Pick one format and use it everywhere. This is cosmetic but worth standardising since P3 will produce many of these messages.
</review>

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

<review>
Check whether `@index` can also appear on the *enum type itself* (not just individual values) in the legacy schema. If `QueryEmnerOrderByField` has `@index` at the type level, the values-stream check won't catch it and `isSortEnum` will still be `false`. The fix above is correct for value-level `@index`; just confirm the legacy usage is value-level before closing this one.
</review>

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

`buildParticipantList()` currently accepts only `TableBackedType` members. The fix is to also accept `ErrorType`:

```java
if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
    // existing table-backed path
} else if (gt instanceof ErrorType) {
    result.add(new ParticipantRef(typeName, null, null));  // no table, no discriminator
} else {
    errors.add("implementing type '" + typeName + "' is not table-bound (missing @table directive)");
}
```

`ParticipantRef` needs to accommodate a null `TableRef` for error members. The validator and generator for `InterfaceType` / `UnionType` already skip generation for error-union and error-interface contexts — the `ErrorType` branch in the validator is `{}` (no checks). The generator side needs to recognise that a union/interface whose members are all `ErrorType` is handled entirely at runtime (error dispatch), not by generated SQL.

A new sealed variant `ErrorUnionType` (parallel to `ErrorType`) may be cleaner than patching `UnionType` to carry mixed members. Decision: introduce `ErrorUnionType` if the generator needs to distinguish "pure error union" from "mixed table/error union". For the SIS graph all error unions are pure, so start with `ErrorUnionType` and revisit if mixed unions appear.

**Implementation steps:**
1. Add `ErrorUnionType` to the `GraphitronType` sealed hierarchy
2. In `enrichUnionType()`: if all members are `ErrorType`, return `ErrorUnionType` instead of calling `buildParticipantList()`
3. In `enrichInterfaceType()`: accept `ErrorType` members in `buildParticipantList()` (they carry no table; they are structural members only)
4. Add `case ErrorUnionType ignored -> {}` to the validator's type switch
5. Add `case ErrorUnionType ignored -> type` to the second-pass `replaceAll` switch in `buildTypes()`

<review>
Three concerns:

**Null TableRef downstream.** `ParticipantRef(typeName, null, null)` for error members is noted but the plan doesn't audit where `ParticipantRef.tableRef()` is dereferenced. Any generator or validator that switches on participants and unconditionally calls `.tableRef().javaFieldName()` (or similar) will NPE at generation time even though classification succeeds. Before step 3, grep for all `.tableRef()` call sites on `ParticipantRef` and confirm each either null-checks or is only reached for table-backed members.

**Mixed unions left open.** The plan handles pure error unions (`ErrorUnionType`) and error-interface members (step 3), but says nothing about a union that mixes table-backed and error types (e.g., a mutation result union that is either a data type or an error). That case still hits the original `buildParticipantList()` rejection path. Either document that mixed unions are not expected in the SIS graph and add a clear error for them, or extend the design to cover them.

**Potential unification with `Datoperiode`.** The structural interface issue (see "Remaining Unclassified Errors") has the same root cause: `buildParticipantList()` rejects non-table-bound implementing types. If step 3 is generalised from "accept `ErrorType`" to "accept any non-table-bound type that has no generated SQL", `Datoperiode` is fixed for free. Consider whether to generalise at this step or handle structural interfaces separately — but note it so the two fixes don't get implemented independently and then have to be merged.
</review>

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

Implement `resolveConditionRef()` using the existing `reflectServiceMethod()` path for class-form references. For name-form references, the resolution requires looking up the registered class for the symbolic name — the same mechanism needed for named `@service` references (see next section).

Condition methods typically return `org.jooq.Condition` and receive table aliases as parameters. The reflection must classify those parameters into `MethodRef.Param` entries — the same `ParamSource` classification already used for service and tableMethod parameters.

**Implementation steps:**
1. Read `className` (class form) or look up name (name form) from the condition map
2. Reflect the method via `reflectServiceMethod()` (or a shared helper) and build a `MethodRef`
3. Store the resolved `MethodRef` on `ConditionJoin` (already present in the model)
4. Generator: emit the condition call using the `MethodRef` in the JOIN `ON` clause

This work is coupled to the named-reference resolution (next section) for the name form.

<review>
Two prerequisites to verify before starting:

**`ConditionJoin` model field.** Step 3 says "`MethodRef` already present in the model" but this should be confirmed. Open `ConditionJoin.java` and check whether it currently carries a `MethodRef` or just the raw strings (`className`, `methodName`). If it carries raw strings, adding a `MethodRef` component is the actual step 0 — and it means `FieldBuilder` (or wherever `ConditionJoin` is constructed) must be updated to resolve the method at build time rather than deferring to the generator.

**`ParamSource` coverage for table-alias parameters.** Condition methods typically receive jOOQ table alias parameters — e.g. `Condition myCondition(FilmTable film, ActorTable actor)`. The existing `ParamSource` taxonomy (`Arg`, `Context`, `Sources`, `TextMapLookup`) covers service and tableMethod parameters, none of which are table aliases. `reflectServiceMethod()` will classify an unrecognised parameter type as... what? Check whether it errors, returns `null`, or produces `UnclassifiedField`. A new `ParamSource.TableAlias` variant (carrying the table's Java type) is likely needed, and the generator must emit the corresponding alias at the call site. This may be a significant addition to the `ParamSource` taxonomy.
</review>

---

## Named References — `name:` Form of `ExternalCodeReference`

**Observed:** `service method could not be resolved — service reference is incomplete`

**Root cause:** `parseExternalRef()` reads `ARG_CLASS_NAME` but ignores the deprecated `name` field. When a field uses `@service(service: {name: "SERVICE_PERSONBILDE"})` or a reference uses `condition: {name: "CONDITION_EMNE", method: "…"}`, `parseExternalRef()` returns `ExternalRef(null, method)` and `reflectServiceMethod(null, …)` fails immediately.

**Legacy mechanism:** The old codegen uses `ExternalReferences` — a wrapper around a `Map<String, Class<?>>` built from the plugin's `externalReferences` config list. `CodeReference` reads both `name` (→ `schemaClassReference`) and `className`. `ExternalReferences.getClassFrom()` resolves: className → `Class.forName` + import-path lookup; name → map lookup. The map itself is not present in the rewrite at all; it's missing config plumbing.

**Fix:**
1. Add a `Map<String, String> namedReferences` field to `RewriteConfig` — the same `externalReferences` list from the Maven plugin, stored as `name → fqcn`
2. Pass it into `GraphitronSchemaBuilder` (via constructor or field on `JooqCatalog`/config)
3. In `parseExternalRef()`, when `ARG_CLASS_NAME` is null but `ARG_NAME` is present, look up the name in the map to obtain the class name; if not found, produce an explicit error
4. Log a deprecation warning whenever the `name` form is used: `"ExternalCodeReference 'name' is deprecated; use 'className' instead"`. These are schema-side strings in a large graph so individual warnings per-field are useful here

**Note:** This also unblocks the `name` form in `@condition` reference paths (see previous section).

<review>
Step 2 is underspecified. "Via constructor or field on `JooqCatalog`/config" leaves the decision open. The established pattern for cross-cutting configuration is `RewriteConfig.setProperties()` — it already carries `outputPackage`, `jooqPackage`, and the feature-flag set. Adding `namedReferences` there is consistent and avoids threading a new parameter through `GraphitronSchemaBuilder`'s constructor (which is likely called from the Maven plugin). Commit to this approach or explain why a different entry point is better.

Also: step 3 says "produce an explicit error" for an unresolved name, but doesn't say what kind. This should be a build-time `ValidationError` that fails the build — not a runtime exception or a log warning. A field using an unresolvable named reference is classified as `UnclassifiedField`, which already causes a build failure; the error message just needs to be actionable (name + "not found in namedReferences config").
</review>

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

The validator should then check, for each field that uses an unbound input as `@lookupKey` or filter, that all input fields can be resolved against the field's return table.

<review>
The validator check is under-specified. "Resolve the column mapping at field-classification time" needs a concrete description: for each argument of an unbound input type on a `@lookupKey` or filter field, iterate the input's `InputField` list and verify that each name maps to a column on the field's `returnType().table()`. If any input field name has no matching column, produce a `ValidationError` naming the unresolved field and the table. Without this check, an unbound input with a stale or mismatched field name will silently produce bad SQL or an NPE at generation time. The check should live in `FieldBuilder.resolveFilters()` or wherever `@lookupKey` argument types are resolved — not deferred to the generator.
</review>

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

For now: if these types are intentionally non-generated (no SQL generated for them), annotate them `@notGenerated` to suppress classification errors.

<review>
The `@notGenerated` workaround is a schema-side patch for a builder-side gap. If E1's fix to `buildParticipantList()` is generalised from "accept `ErrorType`" to "accept any non-table-bound member" (see the E1 review comment), `Datoperiode` is fixed at the same time for free. Before recommending `@notGenerated`, decide whether to generalise E1 at that step. If the decision is to keep E1 `ErrorType`-only, then `@notGenerated` is reasonable but should be documented as a known workaround — not a permanent design.
</review>

### Apollo Federation `_service` field

`Field '_service': could not be classified — return type '_Service' is not a @table, interface, or union Graphitron type`

`_service` is a built-in Federation field that returns schema SDL. It should be classified as `NotGeneratedField` (the runtime provides it). Fix: detect `__`-prefixed or known Federation built-in field names in the field classifier and emit `NotGeneratedField`.

<review>
`_service` has a single underscore, not double — the "detect `__`-prefixed" wording is wrong for this case. The existing `_entities` Federation field is handled as `QueryEntityField` (presumably by exact name match). Check how that classification is triggered: if it's an `if (name.equals("_entities"))` branch, then add `"_service"` as another named exception in the same block. The "detect `__`-prefix" suggestion would only work if there are genuinely `__`-prefixed fields causing problems, which are introspection fields and shouldn't appear in the schema. Also worth checking: is the actual root cause `_service` being unclassified, or is it `_Service` (the return type) being unclassified? If `_Service` type is what fails type classification, the fix belongs in `TypeBuilder`, not the field classifier.
</review>

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

<review>
Two prerequisites to confirm:

**`TableRef` record class access.** Step 2 requires reflecting the jOOQ record class from `TableRef`. Check whether `TableRef` currently carries the record class (as a `Class<?>` or `String fqcn`). If it doesn't, this is a prerequisite: `JooqCatalog` must expose the record class for a given table, and `TableRef` must carry it. This is non-trivial — look at what `JooqCatalog` exposes today and whether `Table.getRecordType()` is available on the jOOQ `Table<?>` object before it's abstracted away.

**Failure mode.** If `catalog.findColumn()` returns empty for an `id` field and there are no `getId`/`setId` methods on the record class, the current path falls through to `UnclassifiedType`. That's acceptable, but the error message should say "field 'id' has no matching column and no platformId methods (getId/setId) — use @nodeId for Relay IDs" rather than a generic "could not be classified" message. Without a clear message, this failure mode looks the same as any other unclassified field.
</review>

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
