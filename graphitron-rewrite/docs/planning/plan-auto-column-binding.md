# Auto-column binding for `@table` input types

> **Status:** Spec
>
> Legacy-parity feature for `@table` input types used as query arguments.
> A `@table` input field without `@condition` / `@lookupKey` today classifies
> as `InputField.ColumnField` but emits nothing at runtime, silently
> ignoring the value the client sent. Legacy generates a column-equality
> predicate (`table.COLUMN.eq(input.field)`) implicitly; the rewrite needs
> the same to reach parity on 63 `@table` call sites observed on alf's
> production schema.
>
> Depends on the `NestedInputField` extraction landed in
> `argres` Phase 4b; reuses `walkInputFieldConditions` as its walk site.

## Why now

`argres` Phase 4 delivered `@condition` on `INPUT_FIELD_DEFINITION` (explicit
per-field predicate methods). It did not deliver legacy's implicit "every
`@table` input column is a WHERE predicate" behaviour. Divergence-scan on
alf finds:

- 63 `@table` input call sites with un-annotated columns. All rely on
  auto-binding.
- 0 `@table` inputs with inner `@condition`. Authors never needed the
  explicit path for `@table` inputs because auto-binding already worked.
- 62 plain inputs with inner `@condition` (covered by Phase 4).
- 3 of the 62 under outer-level `@condition(override: true)`
  (`Query.emner`, `Query.emnerV2`, `Query.studenter`).

Without this plan the rewrite cannot replace the legacy generator on any
schema that uses `@table` inputs as query filters, which is the
dominant `@table` input use case.

## Goal

For every `InputField.ColumnField` on a `TableInputArg` that does not
carry `@condition` or sit under a `@lookupKey` binding, emit
`srcAlias.COLUMN.eq(<nested-arg extraction for the field>)` as an
additional `WhereFilter` during `projectFilters`. Compose with enclosing
overrides per the 6-row truth table in `argument-resolution.md`
§Truth table.

Null input values omit the predicate (match legacy "absent means
unconstrained" semantics, same as the fixture treatment Phase 4b
adopted for explicit conditions).

## Design

### What gets auto-bound

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
  path extension; auto-bindings at any level are emitted against the
  caller's source table (`NestingField` binds to the parent's table,
  per its existing semantics).

### Emission shape

`FieldBuilder.walkInputFieldConditions` is the walk site for per-field
behaviour. Extend it to also collect auto-bindings, gated by an
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
          out.add(autoBindingFilter(cf, outer, leafPath))
      case ColumnReferenceField / PlatformIdField: analogous
      case NestingField nf:
        if nf.condition().isPresent():
          out.add(rewrapForNested(nf.condition().filter(), outer, leafPath))
        newOverride = enclosingOverride || nf.condition().map(c->c.override()).orElse(false)
        recurse(nf.fields(), outer, leafPath, newOverride, out)
```

`autoBindingFilter` produces a jOOQ `GeneratedConditionFilter` (or a new
`AutoColumnFilter` variant on `WhereFilter`) whose body is
`srcAlias.COLUMN.eq(<NestedInputField extraction>)` or `noCondition()`
when the nested value is null.

### Override propagation (the accumulator)

Callers of `walkInputFieldConditions` seed `enclosingOverride` from:

- field-level `@condition(override: true)` (true when the `fieldDef`
  directive has `override: true`)
- arg-level `@condition(override: true)` on the `TableInputArg`

If true, auto-predicates from every nested level are suppressed (truth
table rows 4-6). Explicit methods are never suppressed (all rows'
"Explicit method" column).

Each nested `NestingField`'s own `override: true` flips the accumulator
for its descendants only, matching the truth table's "field ⊇ arg ⊇
nesting-field" rule.

### Interaction with `@lookupKey`

`@lookupKey` fields already produce `InputColumnBinding` entries
consumed by `LookupValuesJoinEmitter` for the VALUES+JOIN path. Those
fields must NOT also emit auto-bindings, or the predicate fires twice.
Check before adding the auto-binding: if the owning `TableInputArg`
has a binding whose `inputFieldName` matches, skip.

### Plain inputs

Plain (non-`@table`) inputs resolve per call site; a field classifies
to `InputField.ColumnField` against the outer field's return table.
The same auto-binding logic could apply, but legacy does not auto-bind
plain inputs (the "implicit-table" heuristic only covers `@condition`,
not column equality). Keep plain inputs explicit-only to match legacy
and avoid a behavioural change on the 62 alf plain-input call sites
(all of which already use explicit `@condition`).

## Scope boundaries

**In scope.**

- Auto-emit `ColumnField`, `ColumnReferenceField`, `PlatformIdField`
  predicates for un-annotated fields of `@table` inputs.
- `enclosingOverride` accumulator in `walkInputFieldConditions`.
- Execution tests exercising the 6-row truth table, including
  divergence-pinning rows that pair auto-predicates with explicit
  methods.
- Pipeline-tier tests confirming auto-binding appears in `filters()`
  only under the correct override conditions.

**Out of scope.**

- Plain-input auto-binding (legacy doesn't; see §Design).
- Multi-column primary-key `PlatformIdField` expansion (no production
  demand).
- Custom auto-binding predicate shapes (IS NULL on null, range
  predicates from ranged input types, etc.). Stick to `eq` or
  `noCondition()`.

## Tests

Follows `docs/rewrite-design-principles.md`.

**Pipeline.** `GraphitronSchemaBuilderTest` cases:

- `TABLE_INPUT_AUTO_BINDING_EMITTED`: `@table` input with one un-annotated
  `ColumnField`. Assert `filters()` size is 1, shape is
  auto-binding (not `ConditionFilter`).
- `TABLE_INPUT_AUTO_BINDING_SUPPRESSED_UNDER_OVERRIDE`: outer
  `@condition(override: true)` over a `@table` input with un-annotated
  fields. Assert no auto-binding contribution; only the outer explicit
  method.
- `TABLE_INPUT_AUTO_BINDING_COEXISTS_WITH_CONDITION`: one field with
  `@condition`, one without. Both contribute when no enclosing
  override.
- `TABLE_INPUT_LOOKUPKEY_FIELD_NO_DUPLICATE_AUTO_BINDING`:
  `@lookupKey`-bearing field does not also auto-bind.

**Execution.** `graphitron-rewrite-test-spec`:

- `autoBinding_tableInput_filtersByColumn`: `@table` input with
  un-annotated `filmId`, assert `containsExactly(1)` for
  `filter: {filmId: "1"}`.
- `autoBinding_tableInput_multipleFields_allCompose`: `@table` input
  with two un-annotated columns, assert AND-composition.
- `autoBinding_tableInput_outerOverride_suppressesAutoPredicate`:
  outer `@condition(override: true)` + inner un-annotated column;
  assert auto-predicate suppressed (only the outer explicit method
  fires).
- `autoBinding_tableInput_overrideFieldLevel_suppressesOnlyItself`:
  one field with `@condition(override: true)`, one without; assert
  the override field suppresses its own auto-predicate but the
  sibling's auto-binding still fires.
- `autoBinding_platformId_filterById`: a `PlatformIdField` case with
  decoded IDs.
- `autoBinding_nestedTwoLevel_autoBindsAtLeafLevel`: nested input
  structure, un-annotated leaf column; assert path walks through and
  predicate fires on the leaf's parent table.

Ideally reuse existing `FilmConditionInput` / `InnerFilmInput` schema
variants; add a sibling `FilmAutoBindInput @table(name: "film") { filmId }`
(no `@condition`) plus a platform-id fixture.

## Open decisions

- **D1. Should `ColumnReferenceField` auto-bind?** Provisional yes (legacy
  does). Closes once one production call site with `@reference` is
  identified and fixtured; drop from scope otherwise.
- **D2. `PlatformIdField` auto-binding decoding.** Provisional: use the
  same decode path as `PlatformId` classification; the accessor name
  (`getPersonId` / `setPersonId`) is already on the `PlatformIdField`
  record. Confirm by checking one legacy-generated example.
- **D3. Null handling.** Provisional: null input → `DSL.noCondition()`
  (matches Phase 4b's `filmIdCondition` convention). Confirm matches
  legacy's `hasId(null)` behaviour (which returns `noCondition()`
  at the time of writing).
- **D4. Where does auto-binding live?** Two options:
  - (A) Inline in `walkInputFieldConditions` (same loop as conditions).
    Simpler, one traversal.
  - (B) Separate pass in `projectFilters` over `tia.fields()`.
    Cleaner separation; two traversals.

  Recommend (A): the override accumulator and the walk topology are
  shared, so splitting doubles both.

## Deliverable

Single commit. Scope:

1. `FieldBuilder.walkInputFieldConditions`: add `enclosingOverride`
   parameter, auto-binding emission per §Emission shape.
2. `FieldBuilder.projectFilters`: seed `enclosingOverride` from the
   field-level and arg-level `@condition(override: true)` flags.
3. New `WhereFilter` variant (or `GeneratedConditionFilter` reuse) for
   auto-bindings; emitter for the chosen shape.
4. `FieldBuilder.walkInputFieldConditions` skips auto-binding on fields
   that are already consumed as `@lookupKey` bindings.
5. `isUsedWithOverrideCondition` interaction: no change; auto-binding
   doesn't introduce new validator gates.
6. Pipeline + execution tests per §Tests.

Bisectable via the touched-files list.
