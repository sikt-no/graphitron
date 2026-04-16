# Remaining SIS Graph Build Fixes

Remaining work from the SIS subgraph validation run. F1, F2, O1, O2, E1-E3, IP, and _service are done. The items below are what's left.

Source material: `generator-schema-errors.org` and `generator-schema.graphql` on branch `claude/validation-test-coverage-plan-r8PAv`.

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

**Note:** This also unblocks the `name` form in `@condition` reference paths (see next section).

---

## @condition — Reference Path Resolution (P3 stub)

**Observed:**
- `condition method 'CONDITION_VURDERINGSOPPBYGNING' could not be resolved` (name-form)
- `condition method 'apiHendelseEmneJoinCondition' could not be resolved` (className-form)

**Root cause:** `resolveConditionRef()` is an explicit stub (`return null`). Every `@reference(path:[{condition:{…}}])` in the schema therefore produces an `UnclassifiedField`.

This is the single largest source of remaining errors in the SIS build — the graph makes heavy use of condition joins (same-table self-joins, cross-table joins without FK, filtered references).

`ExternalCodeReference` has two addressing forms:
- **Class form:** `{className: "no.fellesstudentsystem.…Conditions", method: "methodName"}` — resolved via reflection, same mechanism as `@service`
- **Name form:** `{name: "CONDITION_X", method: "methodName"}` — a symbolic name registered in the plugin configuration (same as `@service` and `@tableMethod`)

**Prerequisites confirmed:**

`ConditionJoin` already carries `MethodRef condition` — `record ConditionJoin(MethodRef condition, String alias)`. No model change is needed; the fix is implementing the resolution.

Condition methods have the form `Condition foo(SomeTable t1, OtherTable t2)`. `ServiceCatalog.reflectTableMethod()` already classifies `org.jooq.Table<?>` parameters as `ParamSource.Sources`. Condition methods must be reflected via `reflectTableMethod()`, not `reflectServiceMethod()` — using the wrong path would leave table-alias parameters unrecognised.

**Implementation steps:**
1. Implement `resolveConditionRef()`: resolve `className` from class form or name form (requires Named References above for the name form); call `ServiceCatalog.reflectTableMethod()` to build the `MethodRef`
2. In `BuildContext.parsePathElement()`: the existing error path (`resolved == null → errors.add(…)`) stays; once `resolveConditionRef()` returns a real value, it is automatically used
3. Generator: emit the condition method call in the JOIN `ON` clause using `ConditionJoin.condition()` and its parameters

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

This item has no new design; it is unblocked by implementing `TableInputArg` in the argument-resolution plan. The current cardinality check is too strict — it should allow `List<InputType> @lookupKey` → `List<T>`. Flag for a test case when that work lands.

---

## Remaining Unclassified Errors

### @condition on arguments (deferred)

`Field 'fagpersonerGittFodselsnumre': could not be classified — argument 'fodselsnumre': @condition is only supported on field definitions, not on arguments`

Explicitly deferred in `argument-resolution.md` (decision table item 1). The error rejection stays until argument-level `@condition` work is scheduled.

### Filter inputs with non-column fields

Several input types used as filter arguments have fields that don't map to table columns:

- `EmneBeskrivelserFilterInput.gjelderFraTerminer`
- `EmneUndervisningsoversiktUndervisningsterminerFilterInput.arstall`
- `FagpersonUndervisningsenhetsrollerFilterInput.terminkoder`
- `PubliseringsklartEmneForTerminBeskrivelserFilterInput.gjelderForTerminer`
- `StudentStudieprogramISemesterFilterInput.terminkoder`
- `StudentVedLarestedVurderingsmeldingerFilterInput.gjennomforesIInnevarendeTermin`

**Open question:** Are these fields supposed to be handled by `@condition` on the argument or input field? Or do they use a different pattern (e.g., an inline subquery via a helper table)? The answer determines whether these are fixed by the argument-resolution `@condition` work or require a separate classification.

### Schema-side issues (not Graphitron bugs)

Likely incorrect foreign key references or missing FK registrations in the jOOQ catalog:

- `Field 'pakrevdeEmner': key 'EMNE_FORUTSATT__FORUTSETTES__EMNE__FK' does not connect to table 'FORKUNNSKAPSKRAV'`
- `Field 'plassertPaUndervisningspartier': no foreign key found between tables 'UTDANNINGSPLAN_EMNE' and 'STUDENT_PA_UNDERVISNINGSPARTI'`
- `Field 'protokollFoerteEmnesamlinger': no foreign key found between 'STUDENTVURDKOMBPROTOKOLL' and 'EMNE_OPNEMNESAMLPROT'`
- `Field 'studienivaintervallkode': no foreign key found between tables 'studieprogram' and 'STUDIENIVAINTERVALL'`

Investigate whether the FK exists in the database but is missing from the jOOQ generated classes, or whether the `@reference` path in the schema is wrong.

### Truncated column name

`Field 'kode': could not be classified — column 'TIDSENHET_'`

The column name `TIDSENHET_` appears truncated. Check whether the `@field(name:)` value in the schema is cut off, or whether the jOOQ catalog has a truncated column name due to a database identifier length limit.

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

**How the legacy codegen handles it:** The project uses a custom jOOQ code generator that adds `getId()` / `setId(String)` convenience methods to every generated jOOQ table record. In `LookupHelpers.getKeyFieldBlock()`, when an `id: ID!` field has no `@nodeId`, the legacy codegen emits `record.getId()` / `record.setId(input)` directly via the field's `MethodMapping`.

**Design for the rewrite:**

At classification time, when `buildInputColumnField()` fails to find an `id` column in the jOOQ table, check whether the jOOQ record class has a `setId(String)` / `getId()` method (detectable via reflection on the `TableRef`'s record class). If those methods exist, classify the field as a new `InputField.PlatformIdField` variant.

**Implementation steps:**
1. Add `InputField.PlatformIdField` to the `InputField` sealed hierarchy (alongside the existing `ColumnField`)
2. In `buildInputColumnField()`, when `catalog.findColumn()` returns empty for an `id` field on an `ID` type, reflect the record class for `getId`/`setId` methods; if found, return `PlatformIdField`
3. Add `case PlatformIdField ignored -> {}` to the validator (no structural checks needed)
4. In the mutation generator, emit `record.setId(…)` for `PlatformIdField`

This is low priority since it only affects graphs that predate the `@nodeId` directive. New schemas should use `@node` + `@nodeId`.

**Prerequisite — `TableRef` does not carry the record class.** `TableRef` is `record(String tableName, String javaFieldName, String javaClassName, List<ColumnRef> primaryKeyColumns)` — no `Class<?>` component. Step 2 requires the record class to reflect `getId`/`setId`. The prerequisite is: `JooqCatalog` must expose `table.getRecordType()` at build time and pass it into `TableRef` as a `Class<?>` (or via a `JooqCatalog` lookup method keyed on `tableName`).

**Failure mode error message.** If `catalog.findColumn()` returns empty for an `id` field and `getId`/`setId` are not found on the record, the fallback should produce: `"field 'id' has no matching column and no platformId methods (getId/setId) found on record class — use @nodeId for Relay IDs"`.

---

## Priority Order

| # | Item | Effort | Blocks |
|---|------|--------|--------|
| NR | Named reference resolution | Medium | Named @service + @condition (depends on config answer) |
| C1 | @condition in reference paths (P3) | Large | All condition joins in SIS |
| LK | @lookupKey list input (composite) | Medium | Covered by argument-resolution plan |
| Misc | Filter inputs, @condition on args, FK issues | Small–Medium | Covered individually |
| LPid | Legacy platformId | Unknown | Needs design input first |
