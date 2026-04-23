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

Implicit conditions fold into the existing `GeneratedConditionFilter`
(GCF) rather than landing as a separate `WhereFilter` variant — see
§Open decisions D5. The upstream fetcher code calls one generated
method per bundle and gets one composed `Condition` back; the walk's
job is to contribute a `BodyParam` with a `NestedInputField` extraction
to the GCF's bodyParam list, alongside the existing column-bound scalar
`BodyParam`s. `GeneratedConditionFilter`'s emitter ANDs everything in
the method body.

`FieldBuilder.walkInputFieldConditions` threads `enclosingOverride` and
contributes both explicit `ConditionFilter`s and implicit `BodyParam`s:

```
walkInputFieldConditions(fields, outerArgName, pathPrefix,
                        enclosingOverride,
                        implicitBodyParams, explicitConditions):
  for f in fields:
    leafPath = pathPrefix + [f.name()]
    switch f:
      case ColumnField cf:
        if cf.condition().isPresent():
          explicitConditions.add(
              rewrapForNested(cf.condition().filter(), outer, leafPath))
        if !enclosingOverride
           && !cf.condition().isPresent()
           && !isLookupKeyBound(cf):
          implicitBodyParams.add(bodyParamForImplicit(cf, outer, leafPath))
      case ColumnReferenceField / PlatformIdField: analogous
      case NestingField nf:
        if nf.condition().isPresent():
          explicitConditions.add(
              rewrapForNested(nf.condition().filter(), outer, leafPath))
        newOverride = enclosingOverride
                   || nf.condition().map(c->c.override()).orElse(false)
        recurse(nf.fields(), outer, leafPath, newOverride,
                implicitBodyParams, explicitConditions)
```

`bodyParamForImplicit` returns a `BodyParam` whose `extraction` is the
`NestedInputField` variant landed in argres Phase 4b, whose `column`
is the input field's bound column, and whose `name` is synthesised
from the leaf path (collisions with scalar-arg names are disambiguated
by prefixing the outer arg name). `projectFilters` merges
`implicitBodyParams` into the same bodyParam list it already builds
from `ColumnArg` scalars before constructing the GCF; if the combined
list is empty, no GCF is emitted.

### Override propagation (the accumulator)

Callers of `walkInputFieldConditions` seed `enclosingOverride` from two
places outside the input type:

- **Parent-field-level** `@condition(override: true)` on the GraphQL
  field that owns the argument (any field — `Query.emner`,
  `Film.actors(filter: ...)`, etc.).
- **Arg-level** `@condition(override: true)` on the argument itself
  (the `TableInputArg`).

If either is true, implicit conditions from every nested level inside
the input are suppressed (truth table rows 4-6). Explicit `@condition`
methods are never suppressed (all rows' "Explicit method" column).

Each nested `NestingField`'s own `override: true` flips the accumulator
for its descendants only, matching the truth table's "parent-field ⊇
arg ⊇ nesting-field" propagation rule.

### Interaction with `@lookupKey`

`@lookupKey` fields already produce `InputColumnBinding` entries
consumed by `LookupValuesJoinEmitter` for the VALUES+JOIN path. Those
fields must NOT also contribute an implicit condition, or the
predicate fires twice. Check before contributing: if the owning
`TableInputArg` has a binding whose `inputFieldName` matches the
current field, skip.

### Plain inputs

Plain (non-`@table`) inputs resolve per call site; a field classifies
to `InputField.ColumnField` against the parent field's return table.
The same implicit-condition logic could apply, but legacy does not
emit implicit conditions for plain inputs (the "implicit-table"
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
  divergence-pinning rows that pair implicit conditions with explicit
  methods.
- Pipeline-tier tests confirming the GCF's bodyParam list contains
  the implicit contribution only under the correct override
  conditions.

**Out of scope.**

- Implicit conditions on plain (non-`@table`) inputs (legacy doesn't;
  see §Design).
- Multi-column primary-key `PlatformIdField` expansion (no production
  demand).
- Non-equality implicit condition shapes (IS NULL on null, range
  predicates from ranged input types, etc.). Stick to `eq` or
  `noCondition()`.

## Tests

Follows `docs/rewrite-design-principles.md`. The generated
`{Type}Conditions#{field}Condition(...)` method returns a composed
`Condition` that we can exercise directly against the test Postgres,
without going through the DataFetcher or GraphQL execution. This is
the primary test tier for this feature; end-to-end execution tests
add a thin wiring check on top.

**Generated-condition tests.** Call the generated method, run it as a
WHERE clause against a known row set, and compare returned IDs to the
expected set:

```java
Condition cond = SomeConditions.filmsByFilterCondition(
    Film.FILM, Map.of("filmId", "3") /* …or whatever extractable shape */);
List<Integer> got = dsl.select(Film.FILM.FILM_ID)
    .from(Film.FILM)
    .where(cond)
    .and(Film.FILM.FILM_ID.in(candidateIds))
    .fetch(Film.FILM.FILM_ID);
assertThat(got).containsExactlyElementsOf(expectedIds);
```

The `IN (candidateIds)` clamp keeps result sets bounded and makes
fixture state explicit; the assertion is on the generated condition's
selectivity, not on row ordering. Cases:

- `implicitCondition_oneField_filtersByColumn`: input `{filmId: "3"}`
  → returned IDs `[3]`.
- `implicitCondition_twoFields_andsProperly`: input `{filmId: "3",
  releaseYear: 2006}` → returned IDs intersect both predicates.
- `implicitCondition_nullField_omitsPredicate`: input `{filmId: null}`
  → returned IDs are the full candidate set (absent means
  unconstrained).
- `implicitCondition_parentFieldOverride_suppressed`: with
  `enclosingOverride = true` at the caller's seed, the generated
  method for the parent field's explicit `@condition` is what runs;
  the implicit one does not contribute. Assert equality with the
  explicit method's selectivity alone.
- `implicitCondition_inputFieldOverride_suppressesOnlyItself`: two
  un-annotated fields; one carries `@condition(override: true)` +
  explicit method. Returned IDs intersect (explicit method) and
  (implicit on the sibling), but not the suppressed field.
- `implicitCondition_lookupKeyField_notDuplicated`: `@lookupKey`
  field; assert the generated method's condition body does not
  reference the lookup-key column (the VALUES+JOIN path owns it).
- `implicitCondition_platformId_filterById`: decoded ID against
  `Film.FILM.FILM_ID`.
- `implicitCondition_nestedTwoLevel_firesAtLeaf`: nested input
  `{inner: {filmId: "3"}}` → returned IDs `[3]`; predicate binds to
  the innermost table (NestingField's parent).

**Pipeline.** One `GraphitronSchemaBuilderTest` spot-check to pin the
projection wiring independently of the DB run:

- `TABLE_INPUT_IMPLICIT_CONDITION_BODYPARAM_EMITTED`: `@table` input
  with one un-annotated field. Assert the GCF's `bodyParams` contains
  one param with `NestedInputField` extraction referencing the input
  field's leaf path. (The DB tier covers behaviour; this pins
  structure.)

**End-to-end execution.** `graphitron-rewrite-test-spec`: one happy-path
GraphQL query and one suppression query, both asserting on returned
`filmId` rows. Purpose is to catch fetcher-wiring regressions (arg
Map → generated method call → composed Condition → Result), not to
duplicate the generated-condition tier's coverage.

- `implicitCondition_tableInput_filtersByColumn`: GraphQL query with
  `filter: {filmId: "3"}`, assert one film returned.
- `implicitCondition_tableInput_parentFieldOverride_suppressesImplicit`:
  GraphQL query where the parent field's explicit method filters one
  way and the implicit would have filtered differently; assert the
  implicit did not fire.

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
- **D5. `WhereFilter` shape — fold into `GeneratedConditionFilter`
  (resolved).** Implicit column conditions contribute to the same
  `GeneratedConditionFilter` that already bundles column-bound scalar
  args. The walk appends a `BodyParam` with a `NestedInputField`
  extraction; the existing GCF emitter ANDs everything in the body of
  the generated `{field}Condition(...)` method. The fetcher calls one
  method, gets one `Condition`, puts it in the query — trivial
  upstream.

  Rejected alternative: a separate `ImplicitColumnCondition`
  `WhereFilter` variant that emits its predicate inline at the call
  site. Separate-variant scores higher on pipeline-test shape
  assertability (each implicit condition is a discrete `WhereFilter`)
  but pays for it with N+1 filters to compose at the call site and a
  second emitter. Pipeline assertability is recoverable by asserting
  on the GCF's `bodyParams` list (the implicit contributions are
  identifiable by their `NestedInputField` extraction) — see §Tests.

  Internal shape within the GCF — whether the generator emits one
  bundled `{field}Condition` method or splits into helpers — is an
  emitter-local concern, not a plan-level commitment.

## Deliverable

Single commit. Scope:

1. `FieldBuilder.walkInputFieldConditions`: add `enclosingOverride`
   parameter and an `implicitBodyParams` output list; contribute
   `BodyParam`s with `NestedInputField` extraction per §Emission shape.
2. `FieldBuilder.projectFilters`: seed `enclosingOverride` from the
   parent-field-level and arg-level `@condition(override: true)`
   flags; merge `implicitBodyParams` into the existing bodyParam list
   before constructing the GCF.
3. `GeneratedConditionFilter`'s existing emitter: no shape change,
   but it now receives `BodyParam`s with `NestedInputField`
   extractions. Verify the method-body AND-composition and the
   call-site argument extraction both handle the new extraction
   variant without change (they should, per Phase 4b's emitter
   coverage).
4. `FieldBuilder.walkInputFieldConditions` skips implicit contribution
   on fields already consumed as `@lookupKey` bindings.
5. `isUsedWithOverrideCondition` interaction: no change; implicit
   conditions don't introduce new validator gates.
6. Pipeline + execution tests per §Tests.

Bisectable via the touched-files list.
