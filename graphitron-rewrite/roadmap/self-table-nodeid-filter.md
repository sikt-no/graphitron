---
title: "Same-table `[ID!] @nodeId` filter → primary-key IN predicate"
status: In Review
priority: 6
---

# Same-table `[ID!] @nodeId` filter → primary-key IN predicate

Shipped: see commit on this branch.

## Result

A `[ID!] @nodeId(typeName: "X")` field on a `@table` input type whose `X` resolves
to the same SQL table as the input now classifies as `InputField.NodeIdInFilterField`
and emits a primary-key IN predicate. Generated SQL for the new
`filmsBySameTableNodeId(filter: {filmIds: [...]})` fixture in `graphitron-test`:

```sql
SELECT film.film_id, film.title FROM film
WHERE (film.film_id) IN ((2), (4))
```

## Implementation summary

- New `InputField.NodeIdInFilterField` variant added to the sealed permits clause.
- `BuildContext.classifyInputField` short-circuits before the `@reference` /
  `findUniqueFkToTable(t, t)` block when `targetTable.equalsIgnoreCase(resolvedTable.tableName())`.
  Falls back the same way `NodeIdReferenceField` does (catalog metadata → `ctx.types`
  NodeType → SDL `@node` + catalog PK).
- `BodyParam` migrated from a single record to a sealed interface with `ColumnEq`
  (existing scalar/IN predicate path) and `NodeIdIn` (new) variants. Every existing
  call site migrated to `BodyParam.ColumnEq`.
- `TypeConditionsGenerator.buildConditionMethod` switches on the variant; emits
  `NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)` for `NodeIdIn`.
  The generator now also takes `outputPackage` so it can fully-qualify
  `NodeIdEncoder`.
- `walkInputFieldConditions` in `FieldBuilder` emits a `BodyParam.NodeIdIn` for the
  new variant, guarded by `lookupBoundNames` so a future `@lookupKey`-bound combination
  still routes through `LookupMapping.NodeIdMapping` instead.
- `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and the `GraphitronSchemaValidator`
  exhaustive switch updated to acknowledge the new leaf.

## Deviations from spec

- The spec assumed `ArgCallEmitter.buildNestedInputFieldExtraction` already handled
  list inputs ("the value just flows through the pipeline that `ColumnArg` already
  uses"). It did not — the leaf cast was hard-coded to the scalar component class,
  which produced `(String) _m1.get("filmIds")` for our `[ID!]` field. Fixed by
  wrapping the cast in `List<...>` when `param.list()` is true.
- Pipeline tests live in `NodeIdPipelineTest.InputSameTableNodeIdCase` (using the
  `nodeidfixture` catalog) rather than `GraphitronSchemaBuilderTest.TableInputTypeCase`,
  because the same-table case requires `__NODE_KEY_COLUMNS` metadata that the standard
  Sakila tables lack. `VariantCoverageTest.NO_CASE_REQUIRED` carries an entry pointing
  there, parallel to how `NodeIdField` and `NodeIdReferenceField` are already handled.

## Tests

- `NodeIdPipelineTest.InputSameTableNodeIdCase`: composite-PK, single-PK, and
  target-not-`@node` (Unresolved) classification cases.
- `TypeConditionsGeneratorTest`: structural assertions on `buildConditionMethod`'s
  body emission for `NodeIdIn` (single column, composite columns, list-of-String
  signature, mixed `ColumnEq` + `NodeIdIn`).
- `VariantCoverageTest`: new entry in `NO_CASE_REQUIRED`.
- `GraphQLQueryTest.films_filteredBySameTableNodeId_*`: end-to-end against
  PostgreSQL, asserting both that filtered IDs return exactly those rows and that
  an empty list passes through to `noCondition()` (returning all rows).
