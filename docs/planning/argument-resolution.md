# Argument Resolution: Phase 4

> **Status:** Ready
>
> Foundation + Phases 1–3 shipped; Phase 4 adds `@condition` on
> `INPUT_FIELD_DEFINITION`. Plan revised from the previous deferred-phase wording
> so implementation can proceed.

## Shipped (context)

- `FieldBuilder.classifyArguments` → `List<ArgumentRef>` (sealed: `ColumnArg`,
  `UnboundArg`, `TableInputArg`, `PlainInputArg`, `OrderByArg`, `PaginationArgRef`,
  `UnclassifiedArg`).
- Projection helpers: `projectFilters`, `projectOrderBySpec`,
  `projectPaginationSpec`, `projectForLookup`.
- `@condition` on `FIELD_DEFINITION` and `ARGUMENT_DEFINITION`; `contextArguments`
  flow through `ServiceCatalog.reflectTableMethod` into trailing
  `ParamSource.Context` parameters.
- `ArgConditionRef(ConditionFilter filter, boolean override)`: reusable as-is
  for Phase 4; no schema change to the record.
- `TableInputArg.fieldBindings: List<InputColumnBinding>`: `@lookupKey`-only
  bindings; composite-key lookups wired end-to-end via
  `LookupValuesJoinEmitter`.
- `LookupField` capability with non-`Optional` `LookupMapping lookupMapping()`.
- `TypeBuilder.isUsedWithOverrideCondition` (TypeBuilder.java:543-559) skips
  table-column validation on inputs used with outer `@condition(override: true)`.
  Phase 4 extends this to input-field overrides.

## Goal

Support `@condition` at the third legal position per `directives.graphqls`
(already listed on the directive declaration): `INPUT_FIELD_DEFINITION`.
Each `@condition`-carrying field *inside* an input type contributes its own
predicate when the input is used at a call site. Nested input-field
conditions compose. Outer-level overrides propagate downward.

**Scope:** both `@table`-annotated input types (primary case) and plain
input types used under the legacy "implicit-table" heuristic, where the
input's fields resolve against the enclosing query field's target table.
The scope increase is motivated by a divergence-scan of alf's production
schema (`alf/graphitron-rewrite:graphitron-rewrite/generator-schema.graphql`,
not committed to trunk): zero `@table` inputs carry inner `@condition` on
that schema, while 62 plain inputs do, 3 of them under an outer field-level
`@condition(override: true)` (`Query.emner`, `Query.emnerV2`,
`Query.studenter`). Restricting Phase 4 to `@table` inputs would leave the
feature with no real-world usage on the one schema we checked.

## Design

### Data model

Extend three `InputField` variants
(`graphitron-rewrite/.../model/InputField.java`) with
`Optional<ArgConditionRef> condition`:

- `InputField.ColumnField` (line 30)
- `InputField.ColumnReferenceField` (line 51)
- `InputField.NestingField` (line 91)

The same variants cover both resolution sources: `@table`-input fields
(classified at type-build time against the input's own declared table) and
plain-input fields (classified at argument-classify time against the
enclosing query field's target table). The variant doesn't need to know
which source produced it; the carrying argument record (`TableInputArg` or
`PlainInputArg`) remembers that.

`PlatformIdField` is intentionally excluded; see Out of Scope. `ArgConditionRef`
is reused verbatim; its `override` flag becomes the input-field-level override
(matching legacy semantics: `override: true` on an input field replaces that
field's auto-predicate with the explicit method).

### Classification: reading the directive at type-build and call-site time

`@table` inputs and plain inputs classify at different times because their
resolution tables differ:

- **`@table` inputs.** Classified once at type-build time by
  `TypeBuilder.buildInputField` (TypeBuilder.java:562-652) against the
  input's own `@table(name:)`.
- **Plain inputs.** Classified per call site by
  `FieldBuilder.classifyArgument` (FieldBuilder.java:683-741) against the
  enclosing query field's target table (`rt`). Same plain input used at N
  call sites classifies N times, one per resolved table. Classification
  is cheap; reclassification is simpler than caching and a per-site cache
  would complicate invalidation without a measured need.

**Shared per-field classifier.** Extract the column / @reference / nesting
decision tree from `TypeBuilder.buildInputField:569-651` into a helper
method (name suggestion: `classifyInputField(field, tableRef, expandingTypes,
errors) -> InputFieldResolution`) accessible from both `TypeBuilder` and
`FieldBuilder`. `TypeBuilder` calls it with the input type's declared
table; `FieldBuilder` calls it with the call-site's `rt` when it
encounters a plain-input arg. No semantic change to the existing
`NestingField` handling: a plain input nested inside a `@table` input
still resolves against the parent `@table`'s table via the existing
recursive call. The new path is plain input used directly as a field
argument.

**Condition helper.** Add `buildInputFieldCondition(GraphQLInputObjectField
field, String inputFieldName, List<String> errors) -> Optional<ArgConditionRef>`,
modeled on `FieldBuilder.buildArgCondition` (FieldBuilder.java:849-863):

- Delegate directive parsing to `readConditionDirective`, already
  `GraphQLDirectiveContainer`-generic (FieldBuilder.java:825-841), so
  `GraphQLInputObjectField` works without modification.
- Reflect via `ServiceCatalog.reflectTableMethod(className, method,
  Set.of(inputFieldName), Set.copyOf(contextArguments))`. The method's
  primary argument is the single input-field value, named after the
  SDL field name (matches legacy; see `withListedInputConditions`
  fixture: `customerString(table, input.getId())`).
- On reflection failure, append to `errors` and return `Optional.empty()`,
  mirroring the `buildArgCondition` error contract.

The helper is agnostic to `@table` vs. plain source; both paths call it
with the same shape. It lives alongside `classifyInputField` in whichever
home D1 picks.

**Constructor call sites to update.** Three constructor call sites
(TypeBuilder.java:582, 621, 629) extended to pass the optional ref as
the final argument.

`readConditionDirective` currently lives in `FieldBuilder` (private). Per
D1 (resolved below) it moves to `BuildContext` so both `FieldBuilder`,
`TypeBuilder`, and the new shared classifier reach it via `ctx`.

### Projection: threading conditions to the call site

`FieldBuilder.projectFilters` (FieldBuilder.java:1034-1088) currently handles
only outer-arg-level `@condition` on both `TableInputArg` and `PlainInputArg`:

```java
case ArgumentRef.InputTypeArg.TableInputArg tia -> {
    tia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
}
case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
}
```

Extend both cases to also walk the input's classified `InputField` records
and append each present condition, applying the override-propagation rule.
The walking logic is identical; differences live in the carrying record.

**Data model: both variants carry a classified field list.** Per D2
(resolved below), `TableInputArg` gains `List<InputField> fields` populated
at classify time. Apply the same extension to `PlainInputArg` (new field,
also `List<InputField> fields`). `TableInputArg.fieldBindings` is
`@lookupKey`-only, insufficient since condition-carrying fields aren't
necessarily lookup keys; `PlainInputArg` has no prior field structure, so
this is an additive extension in both cases.

The alternative was to read the field list out of a registry at projection
time. Rejected: re-couples projection to builder context, breaks the
"projection is a pure function of `List<ArgumentRef>`" invariant from
`docs/argument-resolution.md`.

### Override propagation

Three directive levels can co-exist at one call site:

- **Field**: `fieldDef @condition`
- **Argument**: `arg @condition`
- **Input field**: `inputField @condition` (new in Phase 4)

Nesting adds a fourth tier: an input type contains an input field whose type is
itself another input type, which has its own fields. Each nested level can
carry its own `@condition`.

**Propagation rule (downward inheritance).** `override: true` at any enclosing
level (field ⊇ arg ⊇ nesting-field) suppresses every nested *auto-predicate*
(jOOQ `table.COLUMN.eq(input.getField())`). Explicit `@condition` methods are
never suppressed by ancestor overrides; they're independent declarations by
the schema author, and a level's own `override` flag affects only that level's
auto-predicate.

#### Legacy behavior reference (and intentional divergence)

The rule above (downward inheritance, explicit methods survive) is the rule
the **rewrite** will enforce. It is NOT the rule the legacy generator
implements. Reviewers and implementers should know the delta before signing
off on §Override propagation.

**Legacy schema, `withListedInputConditions`**
([schema.graphqls](../../graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/queries/fetch/records/withListedInputConditions/schema.graphqls)):

```graphql
type Query {
  customer(in: [CustomerInput]):         CustomerTable @condition(..., method: "customerJOOQRecordList")
  customerOverride(in: [CustomerInput]): CustomerTable @condition(..., method: "customerJOOQRecordList", override: true)
}
input CustomerInput @table(name: "CUSTOMER") {
  id:    ID!     @condition(..., method: "customerString")
  first: String! @field(name: "FIRST_NAME")
                 @condition(..., method: "customerString", override: true)
}
```

**Legacy output, same fixture's**
[`expected/QueryDBQueries.java`](../../graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/queries/fetch/records/withListedInputConditions/expected/QueryDBQueries.java):

- `customerForQuery` (no outer override, :17-37) emits the full stack:
  row-IN containing `hasId(id)` + `customerString(table, id)` +
  `customerString(table, firstName)`, AND-ed with `customerJOOQRecordList`.
  Inner `id` (no override) contributes both auto-predicate AND explicit
  method; inner `first` (`override: true`) contributes only the explicit
  method (its own auto-predicate suppressed at the input-field level).
  No explicit method is dropped by the outer level.
- `customerOverrideForQuery` (outer override, :40-48) emits **only**
  `customerJOOQRecordList`. Every inner contribution is dropped: `id`'s
  auto-predicate, `id`'s explicit `customerString`, and `first`'s explicit
  `customerString`. There is no row-IN construct at all.

**The legacy rule is total-replace: an outer `override: true` substitutes its
own explicit method for everything below it, regardless of whether inner
fields carry their own explicit `@condition` methods.** The rewrite's
proposed rule preserves inner explicit methods across the boundary. That is
a **deliberate divergence** from legacy.

**Rationale for diverging.** The legacy behavior couples auto-predicates and
explicit methods into a single "outer owns everything" toggle, which means a
schema author can't declaratively compose an outer replacement condition with
inner explicit side-conditions. The rewrite treats each level's `override`
flag as affecting only that level's auto-predicate, which lets
`@condition(override: true)` replace auto-binding without also silencing
explicit input-field conditions written by the schema author.

**Coverage requirement before Done.** §4c includes a dedicated
divergence-pinning execution test: outer field-level
`@condition(override: true)` over a `@table` input whose field carries its
own `@condition` (no input-field override). Assertions: the explicit
input-field method fires, the inner auto-column binding is suppressed by
the outer override, and the outer explicit method fires alongside. A
future regression to the legacy total-replace rule breaks this test by
name.

**Out of scope for Phase 4.** The legacy total-replace rule is not
reproduced. If a downstream consumer relies on the legacy coupling, that's
a migration note for the cutover, not a Phase 4 requirement; it would be
handled by an author-side schema edit (drop the inner `@condition` methods
that should not run under outer override) or promoted to its own backlog
item.

**Additional legacy fixtures to audit during implementation:**
`multiLevelInputJavaRecordOverrideCondition`,
`nestedListInputJavaRecordOverrideCondition`,
`listInputJavaRecordAndFieldOverrideCondition` under the same
`queries/fetch/records/` directory. Each one is schema-only in the legacy
tree; the audit is to confirm none of them encode a propagation shape not
covered by the truth table above.

### Truth table (per input-field, per call site)

"Any enclosing override" = field-level OR arg-level OR any intermediate
nesting-field's `override: true`.

| Any enclosing override | Input field `@condition` | Auto-predicate | Explicit method |
|---|---|---|---|
| No  | Absent                  | Emitted     | n/a     |
| No  | Present (no override)   | Emitted     | Emitted |
| No  | Present (override:true) | Suppressed  | Emitted |
| Yes | Absent                  | Suppressed  | n/a     |
| Yes | Present (no override)   | Suppressed  | Emitted |
| Yes | Present (override:true) | Suppressed  | Emitted |

"Emitted" in the explicit-method column means the method call lands in the
`List<WhereFilter>` returned by `projectFilters`; downstream emitters AND
all present filters together (see §Emission). The earlier column label
"Replaces" was inherited from column-arg vocabulary and is misleading here,
since rows 5-6 have no auto-predicate left to replace.

Six rows, not nine: the previous draft's "outer `override: false`" row is
indistinguishable from "outer absent" since `false` is the directive default.
Confirmed against `BuildContext.argBoolean` (which defaults `ARG_OVERRIDE` to
`false`) and the SDL declaration in `directives.graphqls` (`override: Boolean
= false`).

### Emission: no new emitters

`projectFilters` output is `List<WhereFilter>`; each `ConditionFilter` is
already a callable reference carrying `Table<?>` + arg-value parameters. The
downstream emitters (`LookupTableFieldEmitter`, `InlineLookupTableFieldEmitter`,
`SplitRowsMethodEmitter`) already AND-in each `ConditionFilter` without
knowing its provenance. Input-field conditions land alongside field-level
and arg-level conditions in the same filter list.

**List-typed inputs (composite-key lookups).** `LookupValuesJoinEmitter` emits
VALUES+JOIN rows; per-row condition evaluation already reads fields via
`input.get(i).get<FieldName>()`. Input-field conditions piggyback on the same
loop; projection just hands them as additional filters. Verify round-trip
count with an execution test (see §Test strategy).

**Nested non-`@table` input types.** `InputField.NestingField` resolves its
own fields against the parent's table. A condition on the nesting field is
reflected with the nesting field's SDL name as the sole arg (same shape as a
scalar input field's condition); projection walks `NestingField.fields`
recursively to pick up inner conditions. No new emitter shape.

### Validator

1. **`TypeBuilder.isUsedWithOverrideCondition` (lines 543-559).** Currently
   returns true when any consuming field or argument declares
   `@condition(override: true)` against the input type. Extend to also
   return true when the input type itself has any field with
   `@condition(override: true)`. Preserves the "skip table-column
   validation when overridden" escape hatch for per-field overrides.
   Plain inputs do not pass through `isUsedWithOverrideCondition` (it
   gates table-column validation for `@table` inputs), so no plain-input
   branch is needed; the per-call-site classifier handles plain-input
   column resolution directly against the outer field's table with the
   existing `catalog.findColumn` + `@field(name:)` path.

2. **`GraphitronSchemaValidator`.** No new structural validation:
   graphql-java enforces `on INPUT_FIELD_DEFINITION` placement at
   schema-parse time. Reflection errors surface through the existing
   `errors` list in `TypeBuilder.buildInputField` → `UnclassifiedType`
   fallback for `@table` inputs, and through the per-call-site
   classifier's `errors` list for plain inputs (same `UnclassifiedArg`
   fallback already used for other classify-time failures).

## Sub-items

- **4a.** `InputField` records + shared per-field classifier (Data model +
  Classification §). One commit: `model/InputField.java` (three record
  extensions), `TypeBuilder.java` (extract `classifyInputField` helper
  usable by both callers; three constructor-call updates),
  `readConditionDirective` relocated to `BuildContext` (see D1). Tests:
  `InputFieldClassificationTest`, one case per variant with and without
  `@condition`; reflection-failure case producing `UnclassifiedType` (for
  `@table` inputs) and `UnclassifiedArg` (for plain inputs).

- **4b.** Projection + plain-input argument classification (Projection +
  Override §). One commit: `ArgumentRef.TableInputArg` and
  `ArgumentRef.PlainInputArg` both gain `List<InputField> fields` (see D2);
  `FieldBuilder.classifyArguments` populates `TableInputArg.fields` from the
  registry as before, and populates `PlainInputArg.fields` by invoking
  `classifyInputField` per call site against the enclosing field's `rt`.
  `FieldBuilder.projectFilters` extends both cases to walk their `fields`
  lists applying the 6-row truth table. Tests: `ProjectFiltersTest`, one
  classification-tier case per row of the truth table, validating the
  produced `List<WhereFilter>` for each variant.

- **4c.** Validator extension + execution coverage. One commit:
  `TypeBuilder.isUsedWithOverrideCondition` update; one
  `GraphitronSchemaBuilderTest` pipeline case per input variant
  (`@table` input, plain input) exercising the override short-circuit;
  **six execution-tier tests** against the PostgreSQL fixture schema:
  - `@table` single-level: `@condition` on a `ColumnField` inside a
    `@table` input, list-typed outer arg, asserts correct WHERE clause
    at runtime.
  - `@table` input-field override: `@condition(override: true)` on one
    field, plain on another, asserts auto-predicate suppression for the
    override-carrying field only.
  - `@table` outer-override composition (divergence-pinning; see §Legacy
    behavior reference): outer field-level `@condition(override: true)`
    over a `@table` input whose field carries `@condition` with no
    input-field override. Asserts the inner explicit method runs (not
    suppressed), the inner auto-column binding is suppressed, and the
    outer explicit method runs alongside. Documents the delta from
    legacy total-replace semantics.
  - `@table` nested: two-level input nesting with a condition at each
    level, asserts both run and composite cardinality is correct.
  - Plain input single-level: `@condition` on a field inside a plain
    (non-`@table`) input type used directly as a query arg, asserts
    correct WHERE clause at runtime under implicit-table resolution
    against the outer field's target table.
  - Plain input outer-override composition: outer field-level
    `@condition(override: true)` over a plain input whose field carries
    `@condition`. Assertions mirror the `@table` outer-override case;
    models the three alf-schema call sites (`Query.emner`,
    `Query.emnerV2`, `Query.studenter`).

## Test strategy

Per-tier coverage, following `docs/rewrite-design-principles.md` (no body-string
assertions on emitted methods):

- **Unit (classifier).** `TestConditionStub` already carries minimal condition
  methods; add `inputFieldCondition(Table<?> table, String value)` to cover
  Phase 4. `InputFieldClassificationTest` exercises the record-shape and
  `ArgConditionRef` population; `ProjectFiltersTest` exercises the 6-row truth
  table at the `List<WhereFilter>` level.
- **Pipeline (SDL → classification).** One `GraphitronSchemaBuilderTest`
  case per variant (`ColumnField`, `ColumnReferenceField`, `NestingField`)
  with `@condition` on a `@table` input, asserting the resolved
  `TableInputType` carries the expected per-field refs. One additional
  case for a plain input used as a direct field arg, asserting
  `PlainInputArg.fields` is populated via the call-site classifier. One
  case for validator short-circuit.
- **Execution.** Six tests in `graphitron-rewrite-test-spec` against the
  Sakila PostgreSQL schema (see §4c). Assert:
  - JDBC round-trip count matches expectation (catches spurious extra queries).
  - Returned row IDs match the hand-authored expected set.
  - WHERE-clause shape via a jOOQ `ExecuteListener` capturing the generated
    SQL (not via string match; compare tokens/structure).

Fixture extension: add a `@condition`-carrying `@table` input and a
`@condition`-carrying plain input to
`graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls`
(around line 16 where `FilmActorKey` lives today). The conditions fixture
directory already exists at
`graphitron-rewrite-test/graphitron-rewrite-test-fixtures/src/main/java/no/sikt/graphitron/rewrite/test/conditions/`
with `CategoryConditions.java` as the existing template; add a new
sibling class (suggested name: `InputFieldConditionFixtures`) carrying the
condition methods referenced by the test SDL. `RewriteConfig.namedReferences`
is the existing indirection for schema → class; no new plumbing. One
condition-method implementation serves both the `@table` and plain-input
paths; the two fixtures differ only in whether the input type carries
`@table`.

## Open decisions

- **D1. `readConditionDirective` home. Resolved: promote to `BuildContext`.**
  Alternatives considered: new `ConditionDirectives` utility, or keep in
  `FieldBuilder` and duplicate a minimal version in `TypeBuilder`.
  `BuildContext` already houses `DIR_CONDITION` (line 71), `ARG_OVERRIDE`
  (line 108), `argBoolean` (line 189), `argStringList` (line 163). Lands in
  4a as a single-file move; no public API change. Callers (`FieldBuilder.
  buildArgCondition`, `FieldBuilder.buildFieldCondition`, new
  `TypeBuilder.buildInputFieldCondition`) all reach it via the existing
  `ctx` handle.

- **D2. Projection access to `InputField` list. Resolved: Option (A);
  carry `List<InputField> fields` on both `TableInputArg` and
  `PlainInputArg`.** Alternative (B) was to look up the input type
  from the registry at projection time. (A) preserves the "projection is
  a pure function of `List<ArgumentRef>`" invariant from
  `docs/argument-resolution.md` and keeps `projectFilters` registry-free.
  For `PlainInputArg` there is no registry entry to read from anyway; per
  §Classification, plain-input fields are classified per call site against
  the outer field's `rt`, so (A) is the only coherent option there.
  Change to `ArgumentRef` is a single field addition on each variant,
  with no consumers outside the rewrite package (verified via
  `grep -rn TableInputArg graphitron-rewrite/` and
  `grep -rn PlainInputArg graphitron-rewrite/`).

- **D3. Condition-method signature for nested `NestingField` conditions.
  Provisionally resolved: single arg named after the SDL field** (mirrors
  scalar input-field conditions). If the method needs inner values, it
  traverses the passed object. Kept provisional pending the 4a fixture:
  if the `InputFieldClassificationTest` `NestingField`+`@condition` case
  reveals a call-site that naturally wants per-leaf params, revisit
  before 4b. No other design choice is blocked on this.

- **D4. Error behaviour when reflection fails. Resolved: mirror arg-level
  behaviour.** The field-level `buildArgCondition` (FieldBuilder.java:855-857)
  appends the error and returns `Optional.empty()` so the arg behaves as
  unconditioned while the rest of the field classifies cleanly. The
  input-field equivalent does the same: append error, leave
  `condition()` empty. Alternative (promote the whole `TableInputType` to
  `UnclassifiedType`) is rejected on blast-radius grounds: a reflection
  failure is a caller-fixable error, not a schema-structural one, so it
  should not invalidate the input type's other fields.

- **D5. Plain-input classification failure fallback. Resolved: (B).**
  When the per-call-site classifier fails to resolve a plain input's
  field against the outer field's table (e.g., column not found,
  `@reference` path invalid), produce `UnclassifiedArg` for the whole
  plain-input arg; `projectFilters`'s existing `UnclassifiedArg` error
  path short-circuits the field cleanly. Consistent with `@table` input
  behaviour (TypeBuilder.java:608-619 coalesces failures and returns
  `Unresolved`). (A) (partial classification) would leave a
  half-classified argument in an inconsistent state; (C) (silent drop)
  undoes the improvement `GraphitronSchemaValidator` was created to
  provide.

## Out of Scope

- **Mutations.** Input-type arguments for DML use a different mapping.
  Mutations get their own plan.
- **`PlatformIdField` with `@condition`.** Platform IDs are legacy accessors; if
  a real schema surfaces this we'll promote it to its own backlog item.

(Plain / non-`@table` input types were previously out of scope. That
exclusion was removed after a divergence-scan on alf's production schema
showed 62 plain inputs with inner `@condition` versus zero `@table`
inputs; see §Goal and §Classification for the call-site resolution
approach.)
