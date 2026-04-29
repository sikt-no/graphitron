---
id: R40
title: "Argument-level `@nodeId` support"
status: Backlog
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) but `FieldBuilder.classifyArgument` (line 754) never inspects it. The scalar-ID branch at line 815 is gated on `!list` and fires only via `nodeIdMetadata` (synthesized route); an explicit `@nodeId(typeName: T)` on a `[ID!]` arg falls through to the column-binding fallthrough at line 826 and surfaces as `column 'X' could not be resolved in table 'Y'`. Reproducer from opptak: `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`.

## Scope

Same-table only: `typeName:` resolves to the field's own backing table, producing a primary-key IN predicate. FK-target args (`typeName:` resolves to a *different* table) need both an arg-level analog of `InputField.IdReferenceField` and a new emission path in `projectFilters`'s arg switch; R20 covers neither (it walks input-field leaves via `walkInputFieldConditions`, which is not entered for top-level args). FK-target argument-level support is its own future Backlog item, not "layered on R20". Scalar `ID @nodeId(typeName: SameTable)` also falls through today; Spec decides whether to fold the scalar same-table case into R40 or keep it on the existing `NodeIdArg` synthesized path.

## Shape

New sibling variant `ArgumentRef.ScalarArg.NodeIdInArg(name, typeName, nonNull, list, nodeTypeId, nodeKeyColumns)`, parallel to `InputField.NodeIdInFilterField`. The existing `NodeIdArg` keeps its single-ID lookup-shaped semantics; do **not** widen it to carry list semantics, mirroring the `BodyParam.ColumnEq` / `BodyParam.NodeIdIn` split. Same-table classification reuses the input-field three-tier fallback (catalog `nodeIdMetadata` → post-first-pass `ctx.types` → SDL `@node` with `catalog.findPkColumns` last resort) from `BuildContext.classifyInputField` lines 810-843. `projectFilters`'s arg switch grows a `NodeIdInArg` arm that adds a `BodyParam.NodeIdIn` with `extraction = CallSiteExtraction.Direct`; `walkInputFieldConditions` is not entered. Body emission reuses `TypeConditionsGenerator.buildConditionMethod`'s existing `NodeIdIn` arm verbatim. Classifier dispatch order: after the input-type arms (lines 783-811) and the existing scalar `NodeIdArg` block (lines 813-824), before the column-binding fallthrough at line 826.

## Spec-day-one questions

- **`@asConnection` composition.** `GraphitronSchemaBuilder.rewriteCarrierField` appends `first` / `after` before classifyArguments runs, so `isPaginationArg` claims those names first. Confirm filter + `PaginationSpec` compose (filter narrows, seek paginates within) with an execution test on the opptak reproducer.
- **`@lookupKey` policy.** Mirror the input-field rule (`buildLookupBindings` rejects `@lookupKey` on list-typed fields) at arg level: reject `[ID!] @nodeId @lookupKey`, by symmetry with the existing `@asConnection` + `@lookupKey` rejection at lines 331/346.
- **`@condition` policy.** `InputField.NodeIdInFilterField` carries no `condition` slot (per argument-resolution.md "Out of Scope"). Drop `argCondition` from `NodeIdInArg` to match, or document the divergence.
- **Validator + dispatch coverage.** Per "Same-table `[ID!] @nodeId` filter" (changelog), register `NodeIdInArg` in `GraphitronSchemaValidator` and `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`.
- **Stale-docstring cleanup landing alongside.** `ArgumentRef.ScalarArg.NodeIdArg`'s javadoc references "Step 5 (the reference variant)" and `FieldBuilder.projectFilters` line 1211 references a "Step 4 follow-up"; neither step exists in any plan. R40 closes the half-built design, so the same change strikes both stale references and points them at `NodeIdInArg` (or removes them entirely).
- **Edge cases the classifier must enumerate.** `typeName:` non-existent (UnclassifiedArg with candidate hint), non-`@table` target (UnclassifiedArg), same-table target with no metadata / no `@node` / no PK (UnclassifiedArg); all mirror the messages the input-field arm uses.

## Test surface

`nodeidfixture` catalog (Sakila lacks `__NODE_KEY_COLUMNS`). Pipeline-tier classification cases for composite-PK, single-PK, target-not-`@node` Unresolved, target-non-`@table` Unresolved, missing-`typeName` Unresolved. Execution-tier cases against PostgreSQL using the opptak reproducer shape: filter-by-ids returns exactly those rows; empty list passes through to `noCondition()`; combined with `first` / `after` returns the paginated subset of the filtered set; `QUERY_COUNT == 1`.
