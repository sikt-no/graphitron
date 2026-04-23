# Argument Resolution: Phase 4

> **Status:** In Progress
>
> Foundation + Phases 1–3 shipped. Phase 4a (classification, projection
> wiring, validator, pipeline tests) shipped in `9cf83463`. Phase 4b
> (runtime nested-arg access, override propagation, remaining execution
> and unit tests) is the remaining work before Done.

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
  `FieldBuilder.classifyArgument` (FieldBuilder.java:681-739) against the
  enclosing query field's target table (`rt`). Same plain input used at N
  call sites classifies N times, one per resolved table. Classification
  is cheap; reclassification is simpler than caching and a per-site cache
  would complicate invalidation without a measured need.

**Shared per-field classifier.** Extract the column / @reference / nesting
decision tree from `TypeBuilder.buildInputField:567-652` into a helper
method (name suggestion: `classifyInputField(field, parentTypeName, tableRef,
expandingTypes, errors) -> InputField`) accessible from both `TypeBuilder` and
`FieldBuilder`. `TypeBuilder` calls it with the input type's declared
table; `FieldBuilder` calls it with the call-site's `rt` when it
encounters a plain-input arg. No semantic change to the existing
`NestingField` handling: a plain input nested inside a `@table` input
still resolves against the parent `@table`'s table via the existing
recursive call. The new path is plain input used directly as a field
argument.

**Condition helper.** Add `buildInputFieldCondition(GraphQLInputObjectField
field, String inputFieldName, List<String> errors) -> Optional<ArgConditionRef>`,
modeled on `FieldBuilder.buildArgCondition` (FieldBuilder.java:847-861):

- Delegate directive parsing to `readConditionDirective`, already
  `GraphQLDirectiveContainer`-generic (FieldBuilder.java:823-839), so
  `GraphQLInputObjectField` works without modification.
- Reflect via `ServiceCatalog.reflectTableMethod(className, method,
  Set.of(inputFieldName), Set.copyOf(contextArguments))`. The method's
  primary argument is the single input-field value, named after the
  SDL field name (matches legacy; see `withListedInputConditions`
  fixture: `customerString(table, input.getId())`).
- On reflection failure, append to `errors` and return `Optional.empty()`,
  mirroring the `buildArgCondition` error contract.

The helper is agnostic to `@table` vs. plain source; both paths call it
with the same shape. Both `classifyInputField` and `buildInputFieldCondition`
live in `BuildContext` alongside `readConditionDirective`; callers in
`TypeBuilder` and `FieldBuilder` reach them via `ctx`.

**Constructor call sites to update.** Three constructor call sites
(TypeBuilder.java:582, 621, 629) extended to pass the optional ref as
the final argument.

`readConditionDirective` currently lives in `FieldBuilder` (private). Per
D1 (resolved below) it moves to `BuildContext` so both `FieldBuilder`,
`TypeBuilder`, and the new shared classifier reach it via `ctx`.

### Projection: threading conditions to the call site

`FieldBuilder.projectFilters` (FieldBuilder.java:1032-1086) currently handles
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
invariant that projection is a pure function of `List<ArgumentRef>` (no
builder state, no registry lookups).

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

**Coverage requirement before Done.** Execution test 3 in §Deliverable is
the dedicated divergence-pinning test: outer field-level
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

**Audit of other legacy override fixtures (done at plan time).** Three
additional fixtures under `queries/fetch/records/` were inspected to confirm
the 6-row truth table is complete:

- `multiLevelInputJavaRecordOverrideCondition`: three-level nesting
  `Input3 → Input2 → Input1`, with `@condition(override: true)` at the
  `Input2.input1` nesting field (not at outer arg, not at field). Confirms
  nesting-field-level override is a real production shape; already covered
  by the "any enclosing override (field ⊇ arg ⊇ nesting-field)" propagation
  rule. No new row.
- `nestedListInputJavaRecordOverrideCondition`: arg-level
  `@condition(override: true)` over `[Input1]` whose fields carry no
  `@condition`. Row 4 of the truth table.
- `listInputJavaRecordAndFieldOverrideCondition`: field-level
  `@condition(override: true)` composed with arg-level `@condition` (no
  override) on a sibling scalar-list arg. Field-level override propagates
  to the arg's auto-predicates; explicit arg method fires. Row 2 and row
  5 combined across two args; no new row for input-field semantics (no
  input type is involved).

All three fall within the 6-row table. No revisions needed before or
during implementation.

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
recursively to pick up inner conditions, threading a `boolean enclosingOverride`
accumulator: any level's `override: true` flips it to `true` for all
descendants. No new emitter shape.

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

## Landed in 9cf83463 (Phase 4a)

The following shipped as a single commit and is the current state on trunk.
All 105 `graphitron-rewrite-test-spec` tests pass.

1. **Data model.** `model/InputField.java`: `ColumnField`, `ColumnReferenceField`,
   `NestingField` carry `Optional<ArgConditionRef> condition`. `model/ArgumentRef.java`:
   `TableInputArg` and `PlainInputArg` each carry `List<InputField> fields`.
   `InputFieldResolution` extracted to its own file so `BuildContext` and
   `FieldBuilder` can both reference it.

2. **Classification.** `BuildContext.classifyInputField(field, parentTypeName,
   tableRef, expandingTypes, errors) -> InputFieldResolution` (D1 landing point;
   moved from `TypeBuilder`). `BuildContext.readConditionDirective` promoted
   from `FieldBuilder`. `FieldBuilder.classifyPlainInputFields` invokes the
   shared classifier per call site against the enclosing field's `rt`; fields
   that fail column resolution are excluded from `PlainInputArg.fields` with
   the reason appended to the field-level `errors` list (see D5 for the
   deviation from whole-arg `UnclassifiedArg`).

3. **Projection.** `FieldBuilder.projectFilters` extends both `TableInputArg`
   and `PlainInputArg` cases to walk `fields` via
   `walkInputFieldConditions(fields, out)`; recursive into `NestingField`.
   Explicit-method filters always fire (truth-table rows 2, 3, 5, 6
   in the "Explicit method" column).

4. **Validator.** `TypeBuilder.isUsedWithOverrideCondition` extended to
   also return true when the input type's own fields carry `@condition(override: true)`.

5. **Pipeline tests.** Six `GraphitronSchemaBuilderTest` cases added:
   `COLUMN_FIELD_WITH_CONDITION`, `COLUMN_REFERENCE_FIELD_WITH_CONDITION`,
   `NESTING_FIELD_WITH_CONDITION`, `INPUT_FIELD_CONDITION_OVERRIDE`
   (validator short-circuit), `TABLE_INPUT_ARG_FIELD_CONDITION_EMITTED`,
   `PLAIN_INPUT_ARG_FIELD_CONDITION_EMITTED` (includes the
   plain-input-on-non-matching-table skip).

6. **Spec schema and execution coverage (partial).** `FilmConditionInput`
   (`@table`) and `PlainFilmIdInput` (plain, used on Film and Language)
   added; `InputFieldConditionFixtures.filmIdCondition` stub;
   `filmsWithInputFieldCondition`, `filmsByPlainInput`, `languagesByPlainInput`
   execution tests assert the pipeline is wired (condition method called,
   all rows returned because the method stub is a no-op).

## Remaining for Done (Phase 4b)

The shipped slice wires the classifier and emits `ConditionFilter` method
calls, but the runtime values passed to those methods are `null` for nested
input fields, so no real filter executes. Closing Phase 4 requires:

1. ~~**Runtime nested-arg extraction (core blocker).**~~ **Landed.**
   `CallSiteExtraction.NestedInputField(String outerArgName, List<String> path)`
   added (D6 resolution: option A). `FieldBuilder.walkInputFieldConditions` now
   threads `(outerArgName, pathPrefix)` through the recursion; when it finds a
   condition, `rewrapForNested` replaces each `ParamSource.Arg` param's
   extraction with `NestedInputField(outerArgName, prefix + [fieldName])`.
   `ArgCallEmitter.buildArgExtraction` emits a null-safe nested
   `instanceof Map<?, ?>` chain to traverse from the top-level argument Map
   down to the leaf value. `InputFieldConditionFixtures.filmIdCondition` now
   returns a real `table.field(Film.FILM_ID).eq(?)` predicate; the three
   shipped execution tests assert on filtered results (not just wiring).

2. **Override propagation accumulator.** `walkInputFieldConditions` currently
   always appends explicit-method filters. Once auto-column binding for
   `@table` input types lands (deferred to step 9 of `classifyArguments`),
   thread a `boolean enclosingOverride` accumulator through the recursion.
   Any level's `override: true` flips it to `true` for descendants;
   auto-predicates are then suppressed under an accumulated override.
   Explicit methods remain unaffected (already correct today).

3. ~~**Remaining execution tests (four of the six originally planned).**~~
   **Landed** (commit 2).
   - `inputFieldCondition_tableInput_overrideFlagOnRealColumn_explicitMethodStillFires`:
     `@condition(override: true)` on a real-column input field. Override is inert
     at projection today (auto-predicate suppression arrives with step 9); pins
     that the flag parses and the explicit method still fires.
   - `inputFieldCondition_tableInput_outerOverride_preservesInnerExplicitMethod`:
     **divergence-pinning.** Outer `@condition(override: true)` on a `@table`
     input whose field carries its own `@condition`. Generates
     `(film_id = ?) AND (film_id >= 2)` with bind ?=1, matching zero rows.
     Legacy "outer owns everything" would drop `filmIdCondition` and return
     films 2..5; a regression breaks this test by name.
   - `inputFieldCondition_nestedTwoLevel_pathWalksThroughNestingField`:
     `NestedFilmInput` (outer `@table`) contains a `NestingField` holding a
     plain `InnerFilmInput`. Exercises the two-level
     `instanceof Map<?, ?>` chain emitted by `ArgCallEmitter` for
     path `["inner", "filmId"]`.
   - `inputFieldCondition_plainInput_outerOverride_preservesInnerExplicitMethod`:
     alf production shape. Outer `@condition(override: true)` composed with a
     plain input whose field has its own `@condition`. Same divergence-pin
     assertion as the `@table` case: inner explicit method survives.

   The three tests already on trunk (`filmsWithInputFieldCondition`,
   `filmsByPlainInput`, `languagesByPlainInput`) now assert on filtered
   results (row-ID match), not just wiring.

4. **Unit tests missing from the landed slice.** Plan's §Deliverable
   originally called for:
   - `InputFieldClassificationTest`: one case per variant (`ColumnField`,
     `ColumnReferenceField`, `NestingField`) with and without `@condition`;
     reflection-failure case producing `UnclassifiedType` for `@table`
     inputs and `UnclassifiedArg` for plain inputs.
   - `ProjectFiltersTest`: one case per row of the 6-row truth table,
     asserting the `List<WhereFilter>` shape per variant.

   The shipped `GraphitronSchemaBuilderTest` cases overlap some of this
   ground at the pipeline tier. Evaluate whether standalone unit tests
   are additive (cheaper feedback loop, narrower assertion scope) or
   whether the pipeline cases are sufficient; if the latter, remove the
   unit-test requirement from this section instead of shipping redundant
   coverage.

5. ~~**Fixture update.**~~ **Landed alongside #1.**
   `InputFieldConditionFixtures.filmIdCondition` returns
   `table.field(Film.FILM.FILM_ID).eq(Integer.parseInt(filmId))` for
   non-null `filmId` (null maps to `DSL.noCondition()`). Using
   `table.field(...)` rather than `Film.FILM.FILM_ID` directly anchors
   the predicate in the caller's aliased table.

**Sequencing.**

- ~~Commit 1: item #1 (ArgCallEmitter nested-arg) + item #5 (fixture returns
  real condition) + retrofit real-filter assertions on the three existing
  execution tests.~~ **Landed.**
- ~~Commit 2: item #3 (four remaining execution tests).~~ **Landed.**
- Item #4 (unit tests): evaluated against the in-fact-shipped pipeline cases;
  the pipeline tier (`GraphitronSchemaBuilderTest`) covers the per-variant
  classification + validator paths and the execution tier covers the
  projection + emitter paths end-to-end. Dropping the original standalone
  unit-test requirement; no additive coverage vs the pipeline cases.
- Item #2 (override accumulator): defer until step 9 (auto-column binding
  for `@table` inputs) lands. Until then, there are no auto-predicates to
  suppress, so the accumulator is unexercised. Pair the accumulator work
  with step 9 in that phase's plan.

**Remaining before Done.** Only item #2 (override accumulator) remains, and
it is bounded-deferred as part of step 9. The rest of Phase 4b has landed:
runtime nested-arg extraction works end-to-end, all six planned execution
tests exist (three pre-existing, three new plus the divergence-pin), and
`@condition` on `INPUT_FIELD_DEFINITION` is feature-complete for the
currently-implemented subset of the argument-resolution pipeline.

## Test assertions

Follows `docs/rewrite-design-principles.md`: no body-string assertions on
emitted method bodies. The six execution tests listed in §Deliverable
assert, for each case:

- JDBC round-trip count matches expectation (catches spurious extra queries).
- Returned row IDs match the hand-authored expected set.
- WHERE-clause shape via a jOOQ `ExecuteListener` capturing the generated
  SQL: compare structural tokens (column references, operator positions,
  AND/OR tree shape), not literal strings.

Unit (`TestConditionStub` gains `inputFieldCondition(Table<?> table, String
value)`) and pipeline tests assert on the classifier output directly
(`List<InputField>`, `List<WhereFilter>`), not on emitted code.

## Open decisions

- **D1. `readConditionDirective` home. Resolved: promote to `BuildContext`.**
  Alternatives considered: new `ConditionDirectives` utility, or keep in
  `FieldBuilder` and duplicate a minimal version in `TypeBuilder`.
  `BuildContext` already houses `DIR_CONDITION` (line 71), `ARG_OVERRIDE`
  (line 108), `argBoolean` (line 189), `argStringList` (line 163). Single-
  file move; no public API change. Callers (`FieldBuilder.
  buildArgCondition`, `FieldBuilder.buildFieldCondition`, new
  `TypeBuilder.buildInputFieldCondition`) all reach it via the existing
  `ctx` handle.

- **D2. Projection access to `InputField` list. Resolved: Option (A);
  carry `List<InputField> fields` on both `TableInputArg` and
  `PlainInputArg`.** Alternative (B) was to look up the input type
  from the registry at projection time. (A) preserves the invariant
  that projection is a pure function of `List<ArgumentRef>` (no
  builder state, no registry lookups) and keeps `projectFilters`
  registry-free.
  For `PlainInputArg` there is no registry entry to read from anyway; per
  §Classification, plain-input fields are classified per call site against
  the outer field's `rt`, so (A) is the only coherent option there.
  Change to `ArgumentRef` is a single field addition on each variant,
  with no consumers outside the rewrite package (verified via
  `grep -rn TableInputArg graphitron-rewrite/` and
  `grep -rn PlainInputArg graphitron-rewrite/`).

- **D3. Condition-method signature for nested `NestingField` conditions.
  Resolved: single arg named after the SDL field** (mirrors scalar
  input-field conditions). If the method needs inner values, it traverses
  the passed object. This matches the shape reflected through
  `ServiceCatalog.reflectTableMethod(className, method, Set.of(fieldName),
  ...)` for all existing input-field conditions. Per-leaf parameterization
  was considered and rejected as speculative: no legacy fixture or alf
  call site requires it. Per-leaf would also change the reflection key
  from a single field name to an ordered tuple, which does not round-trip
  through `ArgConditionRef` without schema changes. Revisit only if a
  concrete schema surfaces it.

- **D4. Error behaviour when reflection fails. Resolved: mirror arg-level
  behaviour.** The field-level `buildArgCondition` (FieldBuilder.java:855-857)
  appends the error and returns `Optional.empty()` so the arg behaves as
  unconditioned while the rest of the field classifies cleanly. The
  input-field equivalent does the same: append error, leave
  `condition()` empty. Alternative (promote the whole `TableInputType` to
  `UnclassifiedType`) is rejected on blast-radius grounds: a reflection
  failure is a caller-fixable error, not a schema-structural one, so it
  should not invalidate the input type's other fields.

- **D5. Plain-input classification failure fallback. Landed: per-field
  skip with error-recording; plan originally resolved (B).**
  `FieldBuilder.classifyPlainInputFields` skips individual fields that
  fail column resolution (not added to `PlainInputArg.fields`) but
  appends each failure reason to the field-level `errors` list; the
  plain-input arg itself stays classified. The original resolution
  (produce `UnclassifiedArg` for the whole arg on any field failure)
  was revised during implementation because plain inputs are legitimately
  reused across heterogeneous call sites: `PlainFilmIdInput` on both
  `films(filter: ...)` (resolves against `film`) and
  `languages(filter: ...)` (no `film_id` column in `language`).
  Whole-arg failure would reject the Language call site even though
  the Film call site is valid; per-field skip lets each call site
  retain the fields that resolve against its own table. `@table` inputs
  retain the stricter whole-type `UnclassifiedType` behavior via
  `TypeBuilder.buildInputField`.

- **D6. ArgCallEmitter shape for nested input-field extraction.** Blocker
  for Phase 4b (see §Remaining item 1). Today `ArgCallEmitter.buildArgExtraction`
  emits `env.getArgument(param.name())` for every extraction variant,
  which returns `null` for input-field conditions because the field is
  not a top-level arg. Three approaches:
  - (A) New sealed variant `CallSiteExtraction.NestedInputField(String
    outerArgName, List<String> path)`. Emitter generates
    `path.stream().reduce(env.<Map<String,Object>>getArgument(outerArgName),
    (m, key) -> m != null ? (Map<String,Object>) m.get(key) : null, (a, b) -> b)`
    or equivalent null-safe traversal. Type-safe, extends the existing
    hierarchy, makes the nested case explicit at every emitter switch.
  - (B) Optional `outerArgPath` field on `CallParam`; every extraction
    variant checks it and wraps its generated code in a lookup.
    Minimal structural change; couples every variant to the nested case.
  - (C) Pre-lift: projection emits a top-of-fetcher-body local
    `Object <slot> = env.getArgument(outerArgName) instanceof Map m ?
    m.get(fieldName) : null;` and references it. Clean at the call
    site; complicates projection with a new emission slot and doesn't
    compose with `NestingField` chains.

  **Recommend (A).** The sealed hierarchy is already the right place for
  extraction-shape variations (`Direct`, `EnumValueOf`, `TextMapLookup`,
  `ContextArg`, `JooqConvert`). Resolve during Phase 4b §Remaining item 1;
  `@table` and plain paths converge here since both land the same
  `ConditionFilter` shape in projection.

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
