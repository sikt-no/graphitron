# Argument Resolution

Plan for unified argument classification in the builder, `@condition` directive support, and lookup field generation with the VALUES derived table pattern.

## Problem

Arguments are currently processed in **three independent passes** that each iterate all arguments:

1. `buildFilters()` — skips `@orderBy`, pagination args, `@condition`, and input-type args; classifies the rest into `BodyParam`/`CallParam` on a `GeneratedConditionFilter`
2. `buildOrderBySpec()` — scans for `@orderBy` directive
3. `buildPaginationSpec()` — scans for `first`/`last`/`after`/`before` by hardcoded name

Coordination between the three is implicit: `buildFilters()` skips pagination args using the same `isPaginationArg()` check that `buildPaginationSpec()` uses. Input-type arguments are silently dropped (TODO). Field-level and argument-level `@condition` are unimplemented — the latter currently raises a hard error.

## Design

### `ArgumentRef` — builder-internal classification

A single `classifyArguments()` method replaces all three passes. It classifies every argument once into an `ArgumentRef` variant, then projects into generation-ready abstractions. `ArgumentRef` never appears on field records. Generators never see it.

```
GraphQL arguments → classifyArguments() → List<ArgumentRef>  (builder-internal)
                                              ↓
                              project into generation-ready views:
                                → GeneratedConditionFilter  (column-bound filter args)
                                → LookupMapping             (lookup args → VALUES table)
                                → OrderBySpec               (@orderBy args)
                                → PaginationSpec             (pagination args)
```

### `ArgumentRef` variants

```java
sealed interface ArgumentRef {
    String name();
    String typeName();
    boolean nonNull();
    boolean list();
}

sealed interface ScalarArg extends ArgumentRef {
    record ColumnArg(
        ..., ColumnRef column, CallSiteExtraction extraction,
        Optional<ArgConditionRef> argCondition,        // @condition on THIS argument
        boolean suppressedByFieldOverride              // field-level @condition(override: true)
    ) {}
    record UnboundArg(..., String attemptedColumnName, String reason) {}
}

sealed interface InputTypeArg extends ArgumentRef {
    record TableInputArg(
        ..., TableRef inputTable, List<InputColumnBinding> fieldBindings,
        Optional<ArgConditionRef> argCondition
    ) {}
    record PlainInputArg(..., Optional<ArgConditionRef> argCondition) {}
}

record OrderByArg(..., String sortFieldName, String directionFieldName) {}
record PaginationArgRef(..., PaginationRole role) {}  // "Ref" suffix avoids collision with PaginationSpec.PaginationArg
record UnclassifiedArg(..., String reason) {}

record ArgConditionRef(ConditionFilter filter, boolean override) {}
```

`ColumnArg.argCondition` carries a per-argument `@condition` directive (method + `override` flag). `suppressedByFieldOverride` is set when the field itself has `@condition(override: true)` — in that case the field's condition method replaces every auto-generated column predicate.

`TableInputArg` carries the resolved input table and its field→column bindings, giving the lookup generator what it needs for composite key VALUES construction. It also carries an optional arg-level `@condition` (the legacy "condition on the whole input record" case).

### Dispatch rule: project separately from classify (Option C)

`classifyArguments()` returns `List<ArgumentRef>` only — it does not know about field variants. Two projection helpers run afterward, each in its own call site:

- `projectForFilter(refs) → (List<WhereFilter>, OrderBySpec, PaginationSpec)` for non-lookup fields.
- `projectForLookup(refs) → (LookupMapping, OrderBySpec, PaginationSpec)` for lookup fields.

This avoids pushing the child-field-sequencing problem (`hasLookupKeyAnywhere()` runs after `resolveTableFieldComponents()` in `classifyObjectReturnChildField`) into `classifyArguments`. The caller already knows which path it's on when it calls the right projector.

### `LookupField` capability interface

Per the roadmap's "capability interfaces over dispatch chains" principle, `LookupMapping` is surfaced through a capability:

```java
sealed interface LookupField permits
    QueryField.QueryLookupTableField,
    ChildField.LookupTableField,
    ChildField.SplitLookupTableField,
    ChildField.RecordLookupTableField {
    LookupMapping lookupMapping();
}
```

The field record keeps `lookupMapping` as a narrow component (not `Optional<>` — these variants always have one). `TypeFetcherGenerator`'s single `instanceof LookupField lf` arm routes to the VALUES+JOIN emitter. Parallels `SqlGeneratingField`, `MethodBackedField`, `BatchKeyField`.

On lookup fields, `filters()` stops carrying the lookup args (they move to `LookupMapping`); it retains only non-lookup filters such as field-level `@condition` predicates. Step 8 updates the generator to read `LookupField.lookupMapping()` instead of iterating `filters()` for key args.

### `LookupMapping`

```java
record LookupMapping(
    List<LookupColumn> columns,
    TableRef targetTable
) {
    record LookupColumn(
        String argName,
        ColumnRef targetColumn,         // JOIN condition: input.col = target.col
        CallSiteExtraction extraction,  // how to extract the argument value
        boolean list                    // list → multiple VALUES rows; scalar → broadcast
    ) {}
}
```

The generator builds `VALUES(idx, col1, col2, ...)`, the JOIN condition (`input.col1 = target.col1 AND ...`), and `ORDER BY input.idx`.

### `@condition` on field and argument definitions

Read during classification. The directive is legal on `FIELD_DEFINITION`, `ARGUMENT_DEFINITION`, and `INPUT_FIELD_DEFINITION` (directives.graphqls); this plan covers the first two. `INPUT_FIELD_DEFINITION` (inside input types) is deferred to a follow-up under input-type classification.

**Field-level `@condition`:**
1. Check `fieldDef.hasAppliedDirective(DIR_CONDITION)` and read `override` + `contextArguments`.
2. If `override: true`, set `suppressedByFieldOverride` on every `ColumnArg` during classification.
3. Reflect the method via `ServiceCatalog.reflectTableMethod()`, passing `contextArguments` so they're resolved into trailing `ParamSource.ContextArg` entries (same path used by `@service` / `@tableMethod`).
4. Construct `ConditionFilter(className, methodName, params)` and append to the field's `filters()` list.

**Argument-level `@condition`:**
1. During `classifyArguments`, check `arg.hasAppliedDirective(DIR_CONDITION)` and read `override` + `contextArguments`.
2. Reflect the method with signature `(TABLE, argValue, ...contextArgs)` → `Condition`.
3. Record `ArgConditionRef(filter, override)` on the emitted `ColumnArg` / `TableInputArg` / `PlainInputArg`.

**Projection semantics** (per legacy README:558–643):

| State | Predicate emitted for the arg |
|---|---|
| `suppressedByFieldOverride = true` | none (field's own `ConditionFilter` replaces everything) |
| no `argCondition` | auto-predicate only (`.eq(col, val)` / `.in(col, list)`) |
| `argCondition` present, `override = false` | auto-predicate AND arg method |
| `argCondition` present, `override = true` | arg method only |

Field-level override dominates: when set, per-arg `override` is irrelevant. When field-level is non-override but one arg has `override: true`, only that arg's auto-predicate is suppressed; other args keep theirs; field method appends at the end.

**Shared prerequisite already landed:** commit `a5f56eb` added `TypeBuilder.isUsedWithOverrideCondition`, which detects both `FIELD_DEFINITION` and `ARGUMENT_DEFINITION` overrides for input-type classification. Step 4 reuses this scan for the "does this input need override treatment?" question; it does not reimplement override detection.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 0 | ✅ **Done.** Existing test-spec already exercises `@orderBy` (`filmsOrderedConnection.order`) and pagination (`first/last/after/before`) end-to-end. Added one `rating: MpaaRating @field(name:"RATING")` arg to `filmsOrderedConnection` plus a `filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree` test so *all three* passes are exercised on a single field — without this, a refactor that broke cross-pass interaction would slip through single-pass tests. `@asConnection` directive-driven wrapper is not exercised end-to-end because the schema transform strips it before generation; covered by rewrite unit tests instead. | Nothing |
| 1 | Define `ArgumentRef` + `ArgConditionRef` sealed hierarchy (builder-internal) | Nothing |
| 2 | Extract `classifyArguments()` replacing all three passes (`buildFilters`, `buildOrderBySpec`, `buildPaginationSpec`). Returns `List<ArgumentRef>` only — no projection coupling. | Step 1 |
| 3 | `projectForFilter(refs)` → `(List<WhereFilter>, OrderBySpec, PaginationSpec)` for non-lookup fields | Step 2 |
| 4 | Read field-level and arg-level `@condition` (with `contextArguments`) during classification; populate `ArgConditionRef` + `suppressedByFieldOverride` per the projection-semantics table above | Steps 2-3 |
| 5 | Define `LookupMapping` + `LookupColumn` in the model; introduce `LookupField` capability interface | Step 1 |
| 6 | `projectForLookup(refs)` → `LookupMapping`; populate on all four `LookupField` variants | Steps 2, 5 |
| 7 | VALUES + JOIN builder in `GeneratorUtils` | Step 5 |
| 8 | Replace condition-based lookup in `TypeFetcherGenerator.buildQueryLookupRowsMethod` with `LookupField.lookupMapping()` + VALUES + JOIN. `filters()` on lookup fields no longer carries lookup args. | Steps 6-7 |
| 9 | Handle `TableInputArg` in lookup mapping (composite keys via `InputColumnBinding`) | Steps 6-8 |
| 10 | Validation: `UnboundArg` → error, `UnclassifiedArg` → error | Step 2 |

Steps 1-3 unify the three passes. Step 4 unblocks both field-level and arg-level `@condition`. Steps 5-9 implement lookup generation.

## Test strategy

- **Builder tests:** SDL with each argument pattern → correct projection into `filters()` / `orderBy()` / `pagination()`. Existing `GraphitronSchemaBuilderTest.ArgumentParsingCase` rows continue to pass; their internal assertions on `GeneratedConditionFilter` structure move to reading the projected output rather than the intermediate `ArgumentRef` list.
- **`@condition` tests — FIELD_DEFINITION:** additive (no `override`) → auto-predicate AND method; `override: true` → method replaces all auto-predicates.
- **`@condition` tests — ARGUMENT_DEFINITION:** additive → auto-predicate AND per-arg method; `override: true` → that arg's auto-predicate replaced, others retained; combined with field-level override → field method only (per-arg override becomes irrelevant).
- **`@condition` `contextArguments`:** method reflected with trailing `ParamSource.ContextArg` entries; pipeline test verifies they appear in `ConditionFilter.params()`.
- **Lookup mapping tests:** lookup field with scalar + input-type args → correct `LookupMapping` columns and target table via `LookupField.lookupMapping()`.
- **Lookup execution test:** scalar key, composite key, verify result ordering matches input (new — existing `GraphQLQueryTest` uses `containsExactlyInAnyOrder` and must be upgraded to `containsExactly`).
- **Validation:** `UnboundArg` → error with candidate hint; `UnclassifiedArg` → error with reason.

## Out of scope

- **`@condition` on `INPUT_FIELD_DEFINITION`** — conditions on fields inside input types (per legacy README:645–674). Requires input-type classification work orthogonal to argument classification. Track as a follow-up once `InputField` variants stabilise.
- **`PlainInputArg` projection** — non-table input-type args without `@condition` are silently skipped, preserving current behavior. `PlainInputArg` with `@condition` is handled via `argCondition`; without `@condition`, no predicate is emitted and no error is raised. Documented so future work can revisit.
- **Child-level lookup fields** — same VALUES pattern in subquery or DataLoader context. The VALUES builder (step 7) is reusable; integration is roadmap G6 work.
- **Mutations** — input-type arguments for DML use different mapping. Separate concern.

## Cross-plan ownership

`InputColumnBinding` is referenced by this plan (step 9) and by the **legacy platform-id** plan (step 6 maps composite platform-key inputs to record-level `getId`/`setId` columns). Both require the same shape: input-field name → target `ColumnRef` + extraction strategy. This plan commits the canonical definition; platform-id reuses it. Tracked in the roadmap under P2 #5 (cross-plan ownership).

```java
record InputColumnBinding(
    String inputFieldName,      // field name on the GraphQL input type
    ColumnRef targetColumn,     // resolved column on the target table
    CallSiteExtraction extraction
)
```

`LookupColumn` (used inside `LookupMapping`) is deliberately kept distinct: it binds a scalar *argument name* to a column, while `InputColumnBinding` binds an *input-field name* inside a `TableInputArg`. Same shape, different semantic role — collapsing them would hide the distinction between "lookup key came from a top-level arg" and "lookup key came from a field on a composite input record".

---

## Decisions: execution readiness

Reviewed 2026-04-15, re-verified 2026-04-17, resolutions committed 2026-04-17. The first-pass
findings (three-pass scope, dispatch rule, TableInputArg components, PaginationArgRef naming,
override timing) were incorporated into the design above. The eight deeper issues that emerged
from verifying the plan against the actual code paths, generator behavior, test infrastructure,
and the old codegen's documented semantics have each been resolved — see the summary table at
the end of this section.

References below are identifier-level rather than line-number-level: line numbers in
`FieldBuilder` shift on nearly every PR (file is 1379 LOC as of this re-verification), so
citing methods and directive constants instead keeps the review durable.

### 1. `@condition` on arguments and input fields is unaddressed

The directive schema (`directives.graphqls`, `directive @condition`) declares `@condition` valid
on `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`. The plan covers only
`FIELD_DEFINITION` (step 4). The old codegen README
(`graphitron-java-codegen/README.md`, "Conditions" section) documents all three locations with
distinct semantics:

- **On FIELD_DEFINITION:** adds a condition method call receiving all argument values; `override: true` suppresses all auto-generated conditions for the field.
- **On ARGUMENT_DEFINITION:** adds a condition method call receiving that argument's value; `override: true` suppresses only that argument's auto-generated condition.
- **On INPUT_FIELD_DEFINITION:** same per-field scoping, but inside nested input types.

The plan originally flagged a blanket `suppressedByOverride` flag on `ColumnArg`, which cannot express per-argument override. `buildFilters()` currently rejects `@condition` on arguments outright (early arm in `FieldBuilder.buildFilters`).

**Resolution:** Support `@condition` on `ARGUMENT_DEFINITION` in step 4 alongside `FIELD_DEFINITION`. The `ColumnArg` shape above introduces `argCondition: Optional<ArgConditionRef>` for per-arg state and `suppressedByFieldOverride` for field-level override. The four-state projection table in the "`@condition` on field and argument definitions" section codifies the legacy semantics. The current error arm in `FieldBuilder.buildFilters` is removed as part of step 4. `INPUT_FIELD_DEFINITION` remains deferred (see Out of scope).

**Shared prerequisite:** commit `a5f56eb` added `TypeBuilder.isUsedWithOverrideCondition`, which scans both argument-level and field-level `@condition(override: true)`. Step 4 reuses it for input-type classification rather than reimplementing override detection inside `classifyArguments`.

### 2. `@condition` contextArguments parameter is unmentioned

The directive has `contextArguments: [String!]` — context values injected as trailing method parameters. The old codegen documents this (`graphitron-java-codegen/README.md`, "Conditions / contextArguments" section). The `@service` / `@tableMethod` reflection path already handles this pattern.

**Resolution:** Support `contextArguments` in step 4 for both field-level and arg-level `@condition`. The builder reads `arg.getValue(ARG_CONTEXT_ARGUMENTS)` and forwards the list to `ServiceCatalog.reflectTableMethod()`; the reflector resolves them into trailing `ParamSource.ContextArg` entries on the produced `ConditionFilter`.

### 3. Child field sequencing: lookup unknown at classification time

The plan's original dispatch rule assumed "the builder knows the field classification before projecting." This holds for query fields (`classifyQueryField` checks `hasLookupKeyAnywhere()` before `resolveTableFieldComponents()`) but not for child fields (`classifyObjectReturnChildField` runs components first, lookup check after).

**Resolution: Option C.** `classifyArguments()` returns `List<ArgumentRef>` only; projection is a separate step. Callers invoke `projectForLookup` or `projectForFilter` based on the field variant they're building, which they know at projection time regardless of sequencing. No reordering of existing call sites is required. This also aligns with the roadmap's "builder-internal sealed hierarchies for multi-target classification" principle — classification is a one-shot step, projection is multiple independent switches.

### 4. Current lookup generators use GeneratedConditionFilter, not LookupMapping

`TypeFetcherGenerator.buildQueryLookupRowsMethod` iterates `field.filters()`, casts to `GeneratedConditionFilter`, and uses `gcf.bodyParams()` for local variable declarations and `gcf.callParams()` for the condition call. The condition method body (in `TypeConditionsGenerator`) generates `.in()` for list params and `.eq()` for scalar params — each key dimension independently.

The plan replaces this with VALUES+JOIN. This is more than a refactoring — it's a semantic change:

- **Current behavior:** `(customer_id IN (1,2,4)) AND (store_id = 1)` — returns all rows matching ANY customer_id AND the store_id. Composite keys are treated as independent dimensions.
- **VALUES behavior:** `VALUES (1,1), (2,1), (4,1) AS input(customer_id, store_id) JOIN customer ON ...` — returns rows matching specific (customer_id, store_id) tuples. Keys are correlated.

For single-key lookups, the results are identical. For composite keys with one scalar key broadcast across a list key, the results are also identical. But the VALUES approach additionally preserves input ordering (via `ORDER BY input.idx`) and enables true correlated tuple matching if multiple list keys are ever needed.

**Resolution:** Document this semantic change explicitly (this section serves as the record). Step 8 includes the migration: `QueryLookupTableField.filters()` stops carrying lookup args (moved to `LookupMapping`); it retains only non-lookup filters (e.g. field-level `@condition` predicates). Execution-test upgrade: replace `containsExactlyInAnyOrder` with `containsExactly` to lock ordering semantics — added to the Test strategy above.

### 5. Where does LookupMapping live on the model?

The plan originally defined `LookupMapping` as a generation-ready projection (like `GeneratedConditionFilter`) but never said which field record component carries it.

**Resolution: `LookupField` capability interface.** See the "`LookupField` capability interface" subsection under Design. Per the roadmap's "capability interfaces over dispatch chains" principle, the four lookup variants (`QueryLookupTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordLookupTableField`) implement `LookupField` with a `lookupMapping()` accessor. `TypeFetcherGenerator` routes on the capability rather than via per-variant `instanceof` checks. The record component itself is declared on each variant directly (narrow-component-types principle — these variants always have a `LookupMapping`, so no `Optional<>`).

### 6. PlainInputArg has no projection target

`PlainInputArg` (non-table input type) appeared in the variant list with no projection target. Currently in `FieldBuilder.buildFilters`, non-table input types are silently skipped.

**Resolution:** `PlainInputArg` is silently ignored when it has no `@condition` — preserves current behavior, documented under Out of scope. `PlainInputArg` with `@condition` projects to a `ConditionFilter` via its `argCondition` (legacy semantics from README:653 — `@condition` on an input-typed argument receives the whole input record). Full support for auto-classifying non-table input-type arguments remains a future deliverable.

### 7. InputColumnBinding doesn't exist

**Resolution:** Defined in the "Cross-plan ownership" section above. Shape mirrors `LookupColumn` but is deliberately kept distinct to preserve the semantic difference between "lookup key came from a top-level arg" and "lookup key came from a field inside a composite input record". Shared with the legacy platform-id plan step 6 — tracked in the roadmap under P2 #5.

### 8. Test infrastructure gaps

The original plan claimed "existing tests pass unchanged" without accounting for:

- **`GraphitronSchemaBuilderTest.ArgumentParsingCase`** asserts on `GeneratedConditionFilter` structure from `buildFilters()`. Once `classifyArguments` + `projectForFilter` replace `buildFilters`, these cases assert on the projected output. They won't break silently — they'll fail to compile, which is the signal we want.
- **No `@condition` pipeline tests exist** (documented by `CONDITION_IS_ALWAYS_NULL` in `GraphitronSchemaBuilderTest`). Step 4 adds them.
- **No lookup ordering test exists** — existing `GraphQLQueryTest` uses `containsExactlyInAnyOrder`.
- **`graphitron-rewrite-test-spec` has no `@orderBy` or `@asConnection` fields**, so steps 1-3 regressions in those passes would only be caught by the (sparser) pipeline tests.

**Resolution:** Step 0 adds `@orderBy` and `@asConnection` to the test-spec schema *before* step 2. Test strategy above lists the `@condition` and lookup-ordering coverage as explicit new tests. `ArgumentParsingCase` migrations are expected to fall out of step 3.

### Summary: decisions resolved

| # | Decision | Resolution |
|---|---|---|
| 1 | `@condition` on ARGUMENT_DEFINITION | **Support** (in step 4). `INPUT_FIELD_DEFINITION` remains deferred. |
| 2 | `@condition` contextArguments | Support; reuse `ServiceCatalog.reflectTableMethod` contextArguments path. |
| 3 | Child field sequencing | **Option C** — classification and projection are separate steps. |
| 4 | VALUES vs IN semantic difference | Documented in §4; execution test upgrades to `containsExactly`. |
| 5 | Where `LookupMapping` lives | **`LookupField` capability interface** on the four lookup variants. |
| 6 | `PlainInputArg` fate | Silent skip without `@condition`; project to `ConditionFilter` when `argCondition` is present. |
| 7 | `InputColumnBinding` shape | Defined above; shared with legacy platform-id plan (roadmap P2 #5). |
| 8 | `@orderBy`/`@asConnection` test-spec coverage | **Step 0 — done.** `@orderBy` and pagination were already covered; added a combined filter+orderBy+pagination test. `@asConnection` end-to-end coverage is blocked by the transform (covered via unit tests only). |
