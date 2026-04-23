# Implicit column conditions for `@table` input types

> **Status:** Spec
>
> Legacy-parity feature for `@table` input types used as query arguments.
> A `@table` input field without `@condition` / `@lookupKey` today classifies
> as `InputField.ColumnField` but emits nothing at runtime, silently
> ignoring the value the client sent. Legacy contributes an implicit
> column-equality predicate (`table.COLUMN.eq(input.field)`) for every
> such field; the rewrite needs the same to reach parity on 63 `@table`
> call sites observed on alf's production schema.
>
> The binding from input field to column already exists at classify time
> (each field lands as `InputField.ColumnField` / `ColumnReferenceField`
> / `PlatformIdField`). What's new is emission of an *implicit
> `@condition`* at projection time, alongside the explicit `@condition`
> predicates that shipped in argres Phase 4.
>
> Depends on the `NestedInputField` extraction landed in argres Phase 4b;
> reuses `walkInputFieldConditions` as its walk site. Composes with the
> 6-row override truth table in
> [`argument-resolution.md`](../argument-resolution.md) §Truth table.

## Why now

argres Phase 4 delivered `@condition` on `INPUT_FIELD_DEFINITION` (explicit
per-field predicate methods). It did not deliver legacy's implicit "every
`@table` input column is a WHERE predicate" behaviour. Divergence-scan on
alf finds:

- 63 `@table` input call sites with un-annotated columns. All rely on
  implicit conditions.
- 0 `@table` inputs with inner `@condition`. Authors never needed the
  explicit path for `@table` inputs because the implicit conditions
  already covered the common case.
- 62 plain inputs with inner `@condition` (covered by Phase 4).
- 3 of the 62 under outer-level `@condition(override: true)`
  (`Query.emner`, `Query.emnerV2`, `Query.studenter`).

Without this plan the rewrite cannot replace the legacy generator on any
schema that uses `@table` inputs as query filters, which is the
dominant `@table` input use case.

## Goal

For every `InputField.ColumnField` on a `TableInputArg` that does not
carry `@condition` or sit under a `@lookupKey` binding, emit an
*implicit condition* `srcAlias.COLUMN.eq(<nested-arg extraction for the
field>)` as an additional `WhereFilter` during `projectFilters`. Compose
with enclosing overrides per the 6-row truth table in
[`argument-resolution.md`](../argument-resolution.md) §Truth table.

Null input values omit the predicate (match legacy "absent means
unconstrained" semantics, same as the fixture treatment Phase 4b
adopted for explicit conditions).

## Design

### Which fields emit an implicit condition

- **`InputField.ColumnField`** without `condition().isPresent()` and
  without a `@lookupKey` binding already consumed by
  `buildLookupBindings`. This is the primary case.
- **`InputField.ColumnReferenceField`** without `condition().isPresent()`.
  The `@reference` field resolves to a column reachable via a
  non-trivial join path. Legacy emits predicates for these as joined
  column equality; rewrite matches. Treat the same as `ColumnField` at
  projection; the reference's join path has already been resolved at
  classify time and baked into the `ColumnRef`. Emission is against the
  caller's aliased source table.
- **`InputField.PlatformIdField`** without `condition().isPresent()`.
  Legacy has a `hasId(id)` shortcut for PK column equality; rewrite
  emits `srcAlias.PRIMARY_KEY.eq(platformDecodedId)`. The decode path
  is the same one used by `PlatformId` classification elsewhere.
  (Revisit if a production schema surfaces multi-column primary keys;
  none does today.)
- **`InputField.NestingField`**: recurse into `fields`, carrying the
  path extension; implicit conditions at any level are emitted against
  the caller's source table (`NestingField` binds to the parent's
  table, per its existing semantics).

### Emission shape

`FieldBuilder.walkInputFieldConditions` is the walk site for per-field
behaviour. Extend it to also collect implicit conditions, gated by an
`enclosingOverride` accumulator:

```
walkInputFieldConditions(fields, outerArgName, pathPrefix,
                        enclosingOverride, out):
  for f in fields:
    leafPath = pathPrefix + [f.name()]
    switch f:
      case ColumnField cf:
        if cf.condition().isPresent():
          out.add(rewrapForNested(cf.condition().filter(), outer, leafPath))
        if !enclosingOverride
           && !cf.condition().isPresent()
           && !isLookupKeyBound(cf):
          out.add(implicitConditionFilter(cf, outer, leafPath))
      case ColumnReferenceField / PlatformIdField: analogous
      case NestingField nf:
        if nf.condition().isPresent():
          out.add(rewrapForNested(nf.condition().filter(), outer, leafPath))
        newOverride = enclosingOverride || nf.condition().map(c->c.override()).orElse(false)
        recurse(nf.fields(), outer, leafPath, newOverride, out)
```

`implicitConditionFilter` produces a `WhereFilter` whose body is
`srcAlias.COLUMN.eq(<NestedInputField extraction>)` or `noCondition()`
when the nested value is null. The concrete `WhereFilter` shape
(reuse `GeneratedConditionFilter` vs new `ImplicitConditionFilter`
variant) is open: see §Open decisions D5.

### Override propagation (the accumulator)

Callers of `walkInputFieldConditions` seed `enclosingOverride` from two
places outside the input type:

- **Outer Query-field-level** `@condition(override: true)` on the
  GraphQL field that owns the argument (e.g. `Query.emner`).
- **Arg-level** `@condition(override: true)` on the argument itself
  (the `TableInputArg`).

If either is true, implicit predicates from every nested level inside
the input are suppressed (truth table rows 4-6). Explicit `@condition`
methods are never suppressed (all rows' "Explicit method" column).

Each nested `NestingField`'s own `override: true` flips the accumulator
for its descendants only, matching the truth table's "Query-field ⊇
arg ⊇ nesting-field" propagation rule.

### Interaction with `@lookupKey`

`@lookupKey` fields already produce `InputColumnBinding` entries
consumed by `LookupValuesJoinEmitter` for the VALUES+JOIN path. Those
fields must NOT also emit an implicit condition, or the predicate
fires twice. Check before emitting: if the owning `TableInputArg`
has a binding whose `inputFieldName` matches the current field, skip.

### Plain inputs

Plain (non-`@table`) inputs resolve per call site; a field classifies
to `InputField.ColumnField` against the outer field's return table.
The same implicit-condition logic could apply, but legacy does not
emit implicit predicates for plain inputs (the "implicit-table"
heuristic only covers `@condition`, not column equality). Keep plain
inputs explicit-only to match legacy and avoid a behavioural change
on the 62 alf plain-input call sites (all of which already use
explicit `@condition`).

## Scope boundaries

**In scope.**

- Implicit conditions for `ColumnField`, `ColumnReferenceField`, and
  `PlatformIdField` on un-annotated fields of `@table` inputs.
- `enclosingOverride` accumulator in `walkInputFieldConditions`.
- Execution tests exercising the 6-row truth table, including
  divergence-pinning rows that pair implicit predicates with explicit
  methods.
- Pipeline-tier tests confirming an implicit condition appears in
  `filters()` only under the correct override conditions.

**Out of scope.**

- Implicit conditions on plain (non-`@table`) inputs (legacy doesn't;
  see §Design).
- Multi-column primary-key `PlatformIdField` expansion (no production
  demand).
- Non-equality implicit predicate shapes (IS NULL on null, range
  predicates from ranged input types, etc.). Stick to `eq` or
  `noCondition()`.

## Tests

Follows `docs/rewrite-design-principles.md`.

**Pipeline.** `GraphitronSchemaBuilderTest` cases:

- `TABLE_INPUT_IMPLICIT_CONDITION_EMITTED`: `@table` input with one
  un-annotated `ColumnField`. Assert `filters()` size is 1, shape is
  the implicit-condition variant (distinguishable from explicit
  `ConditionFilter`; see D5).
- `TABLE_INPUT_IMPLICIT_CONDITION_SUPPRESSED_UNDER_OVERRIDE`: outer
  `@condition(override: true)` over a `@table` input with un-annotated
  fields. Assert no implicit-condition contribution; only the outer
  explicit method.
- `TABLE_INPUT_IMPLICIT_AND_EXPLICIT_CONDITION_COEXIST`: one field with
  `@condition`, one without. Both contribute when no enclosing
  override.
- `TABLE_INPUT_LOOKUPKEY_FIELD_NO_DUPLICATE_IMPLICIT_CONDITION`:
  `@lookupKey`-bearing field does not also emit an implicit condition.

**Execution.** `graphitron-rewrite-test-spec`:

- `implicitCondition_tableInput_filtersByColumn`: `@table` input with
  un-annotated `filmId`, assert `containsExactly(1)` for
  `filter: {filmId: "1"}`.
- `implicitCondition_tableInput_multipleFields_allCompose`: `@table`
  input with two un-annotated columns, assert AND-composition.
- `implicitCondition_tableInput_outerOverride_suppressesImplicit`:
  outer `@condition(override: true)` + inner un-annotated column;
  assert implicit predicate suppressed (only the outer explicit method
  fires).
- `implicitCondition_tableInput_fieldLevelOverride_suppressesOnlyItself`:
  one field with `@condition(override: true)`, one without; assert
  the override field suppresses its own implicit predicate but the
  sibling's implicit condition still fires.
- `implicitCondition_platformId_filterById`: a `PlatformIdField` case
  with decoded IDs.
- `implicitCondition_nestedTwoLevel_firesOnLeafLevel`: nested input
  structure, un-annotated leaf column; assert path walks through and
  predicate fires on the leaf's parent table.

Ideally reuse existing `FilmConditionInput` / `InnerFilmInput` schema
variants; add a sibling `FilmImplicitInput @table(name: "film") { filmId }`
(no `@condition`) plus a platform-id fixture.

## Open decisions

- **D1. Should `ColumnReferenceField` emit an implicit condition?**
  Provisional yes (legacy does). Closes once one production call site
  with `@reference` is identified and fixtured; drop from scope
  otherwise.
- **D2. `PlatformIdField` decoding.** Provisional: use the same decode
  path as `PlatformId` classification; the accessor name
  (`getPersonId` / `setPersonId`) is already on the `PlatformIdField`
  record. Confirm by checking one legacy-generated example.
- **D3. Null handling.** Provisional: null input → `DSL.noCondition()`
  (matches Phase 4b's `filmIdCondition` convention). Confirm matches
  legacy's `hasId(null)` behaviour (which returns `noCondition()`
  at the time of writing).
- **D4. Where does implicit-condition emission live?** Two options:
  - (A) Inline in `walkInputFieldConditions` (same loop as explicit
    conditions). Simpler, one traversal.
  - (B) Separate pass in `projectFilters` over `tia.fields()`.
    Cleaner separation; two traversals.

  Recommend (A): the override accumulator and the walk topology are
  shared, so splitting doubles both.
- **D5. `WhereFilter` shape.** Two options:
  - (A) Reuse `GeneratedConditionFilter` by generating a synthetic
    per-field method into `<ReturnType>Conditions`. Minimises new
    types; the generated fetcher looks identical to explicit
    `@condition`.
  - (B) New `WhereFilter` variant (e.g. `ImplicitColumnCondition`)
    carrying `(TableRef src, TableField<?, ?> column,
    CallSiteExtraction extraction)`. Emits the predicate inline at the
    call site, no synthetic method. Pipeline tests can assert on the
    shape directly.

  Recommend (B): pipeline tests need a stable handle to assert "one
  implicit, one explicit" coexistence, and the inline shape avoids
  polluting `<ReturnType>Conditions` with generator-internal methods.
  Revisit if emission cost differs materially.

## Deliverable

Single commit. Scope:

1. `FieldBuilder.walkInputFieldConditions`: add `enclosingOverride`
   parameter, implicit-condition emission per §Emission shape.
2. `FieldBuilder.projectFilters`: seed `enclosingOverride` from the
   outer-Query-field-level and arg-level `@condition(override: true)`
   flags.
3. `WhereFilter` shape for implicit conditions (per D5) and the emitter
   for it.
4. `FieldBuilder.walkInputFieldConditions` skips implicit-condition
   emission on fields already consumed as `@lookupKey` bindings.
5. `isUsedWithOverrideCondition` interaction: no change; implicit
   conditions don't introduce new validator gates.
6. Pipeline + execution tests per §Tests.

Bisectable via the touched-files list.
