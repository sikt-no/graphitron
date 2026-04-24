# Multi-parent `NestingField` sharing — `TableField` arm

> **Status:** Spec

## Overview

Allow a plain-object (NestingField) type to be used under multiple `@table` parents when its fields include `ChildField.TableField` leaves, not just `ChildField.ColumnField` or nested `NestingField`. The validator currently hard-rejects every non-Column, non-Nesting leaf in the shared-shape check. Lift the gate for `TableField` only: the pair needs no additional shape check beyond the class-equality gate already applied in `compareNestedFieldsShape`, because `returnType()` derives from the single SDL declaration on the shared nested type. Divergent `joinPath` / `filters` / `orderBy` / `pagination` are legitimately per-parent — each parent's `$fields` emits its own correlated subquery. No emitter or wiring changes needed; today's codegen already supports this shape once the validator lets it through.

## Current state

`GraphitronSchemaValidator.compareNestedFieldsShape` allows exactly two leaf shapes when a NestingField type is used under multiple `@table` parents:

- `ColumnField` — compared by `sqlName()` + `columnClass()`. Relies on jOOQ's name-based `Record.get(Field)` fallback at runtime to project the same-named column across parents.
- Inner `NestingField` — recurses via `compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors)`, threading the outer parent names so deep errors still name the original tables.

Everything else — `TableField`, `LookupTableField`, `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, `ConstructorField`, `NodeIdField` — lands in the catch-all arm with the message "classifies as X which is not yet supported across multiple parents — see rewrite-roadmap.md #8".

Two problems with the status quo:

1. **The `#8` pointer is wrong.** Roadmap `#8` enumerates *leaf type* stubs (`ColumnReferenceField`, `ComputedField`, …) — an orthogonal axis from multi-parent sharing. `TableField` is already fully implemented for single-parent use (G5 + `aaadb78b`); the only gap is the validator shape check.
2. **The gate is over-broad for `TableField`.** Emission and wiring for a nested `TableField` are parent-agnostic:
   - `TypeClassGenerator.emitSelectionSwitch` dispatches each selected field through `InlineTableFieldEmitter.buildSwitchArmBody`, passing the current parent's `tableArg` and the field's own `joinPath`. Each parent's `$fields` method emits its own `DSL.multiset(...)` arm with parent-specific correlation, so divergent joinPaths are self-contained.
   - `TypeFetcherGenerator.buildWiringEntry`'s `ChildField.TableField` arm reads the multiset result by field name from `env.getSource()` (single branch: `(($T) env.getSource()).get($S, $T.class)`; list branch: `new ColumnFetcher<>(DSL.field($S))`). Neither branch references the `parentTable` parameter, so the same DataFetcher works for records produced by either parent's `$fields`.
   - `GraphQLRewriteGenerator.collectNestedTypes` registers one `NestedTypeWiring` per distinct nested type via `putIfAbsent`; the first-seen parent's entry wins. `GraphitronWiringClassGenerator` threads `representativeParentTable` into `buildWiringEntry`, but the `TableField` arm ignores it — so the "first-parent-wins" choice has no runtime effect for this leaf type. (It *does* affect `ColumnField` leaves, which rely on the jOOQ name-fallback noted above.)

Real-world report: user running the rewrite validator against `sis-graphql-spec` hits this on `EmneStudieprogramKoblingPeriode` shared across `EmneStudieprogramkobling` and `StudieprogramEmnekobling`, where the shared `fraTermin` field classifies as `TableField`. The only workaround today is duplicating the nested type per parent in SDL.

## Desired end state

`compareNestedFieldsShape` recognises `ChildField.TableField` as a permitted multi-parent leaf. No additional shape check is needed beyond the class-equality gate already applied upstream in the method: both `returnType().returnTypeName()` and `returnType().wrapper()` derive from the single SDL declaration of the field on the shared nested type, so they are identical by construction across parents. Fabricating a mismatch would require a classifier bug, not a schema error — which is not what this validator is for.

`joinPath`, `filters`, `orderBy`, `pagination` are legitimately per-parent and intentionally not compared — they come from directives on each *outer* parent's field declaration (which points to the shared nested type) and from the `@reference` resolution against that outer parent's table context.

### Why this is emitter-safe

No emitter or wiring changes are needed. Two properties of the existing pipeline make divergent per-parent `TableField` shapes safe today:

1. **Per-parent `$fields` emission is self-correlated.** `TypeClassGenerator.emitSelectionSwitch` is called once per parent's `$fields` method with that parent's `tableArg`. Each selected `ChildField.TableField` is emitted through `InlineTableFieldEmitter.buildSwitchArmBody(tf, tableArg, sf)`, which generates a `DSL.multiset(...)` keyed off the field's own `joinPath` + the caller's `tableArg`. Each parent's generated SQL embeds its own correlation.
2. **The nested DataFetcher is parent-agnostic.** `buildWiringEntry`'s `TableField` arm reads from `env.getSource()` by field name (see "Current state" bullet 2). The wiring registered via `GraphQLRewriteGenerator.collectNestedTypes` + `GraphitronWiringClassGenerator` is built once per nested type; the DataFetcher produced for a `TableField` leaf does not consult the outer parent's table.

Verification: a two-parent NestingField fixture where both parents' inline `TableField` projects to the same target `@table` via divergent FK paths classifies without error, compiles, and returns per-parent-correct rows at runtime.

## What we're NOT doing

- **BatchKey leaves under NestingField across parents.** `SplitTableField` / `LookupTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` all have per-field DataLoader or rows-method generation that's keyed off the outer parent context today. Reconciling those across a shared NestingField is a larger piece of work — separate Backlog entry (§3 below). The catch-all arm in the validator stays as the fallback; only the error-message pointer gets fixed.
- **Deeper inline recursion.** Already works via the existing `NestingField` recursion branch in `compareNestedFieldsShape`.
- **`ConstructorField` / `NodeIdField` / reference-scalar leaves.** No known real-world demand; stay rejected.
- **Shape-compat of `filters` / `orderBy` / `pagination`.** Deliberately left per-parent (see "Desired end state" rationale). If a future schema author wants to enforce them matching, that's a separate opt-in directive.
- **Inline-subquery alias rename.** Orthogonal to this plan. The `table` → `<entity>Table` rename tracked in [plan-generated-fetcher-quality.md](plan-generated-fetcher-quality.md) and the `ArgCallEmitter.buildCallArgs` hardcoded `"table"` fix tracked in [plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md) §7 both touch inline-subquery alias identity but neither interacts with the multi-parent shape check landing here.

## Implementation approach

### 1. Validator — add the `TableField` arm

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

In `compareNestedFieldsShape`, between the `ColumnField` arm and the inner-`NestingField` recursion arm, add a `TableField` arm that accepts the pair without additional shape comparison:

```java
} else if (rf instanceof ChildField.TableField && of instanceof ChildField.TableField) {
    // TableField is safe to share across parents: each parent's $fields emits its own
    // DSL.multiset arm (per-parent joinPath / filters / orderBy / pagination are
    // intentionally not compared), and the nested DataFetcher reads by field name from
    // env.getSource() without consulting the outer parent table. No further shape check
    // is needed — returnType() is derived from the single SDL declaration on the shared
    // nested type and is identical by construction.
}
```

The arm exists only to prevent the catch-all from firing. `TableField` and `NestingField` are disjoint concrete records; arm ordering between them is stylistic — place this one next to the `ColumnField` arm for readability.

### 2. Fix the error-message pointer

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java` (catch-all arm in `compareNestedFieldsShape`).

The catch-all message currently says "… which is not yet supported across multiple parents — see rewrite-roadmap.md #8". Drop the `#8` reference — replace with a self-contained sentence. Roadmap tracking for the remaining leaves lands in §3; the error text itself doesn't need to cite a roadmap item number that risks renumbering drift.

New message:
```
"Nested type '" + nestedTypeName + "' shared across '" + repParent
    + "' and '" + otherParent + "': field '" + name
    + "' classifies as " + rf.getClass().getSimpleName()
    + " which is not yet supported across multiple parents"
```

### 3. Roadmap entry — BatchKey leaves follow-up

**File:** `docs/planning/rewrite-roadmap.md`

Add to Backlog (Priority or Cleanup, implementer's call):

> **Multi-parent NestingField sharing of `BatchKey` leaves** **[Backlog]** — `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` under a NestingField shared across parents. DataLoader registration and per-parent rows-method emission need reconciling (each variant has its own considerations). `TableField` shipped separately via [plan-nestingfield-multiparent-tablefield.md](plan-nestingfield-multiparent-tablefield.md).

### 4. Tests

**Pipeline test** (`GraphitronSchemaBuilderTest`): new case `MULTIPARENT_NESTING_TABLEFIELD`. Two `@table` parents declaring the same nested type; the nested type contains a `TableField` targeting a third `@table`. Classifier emits no errors; both parents' `NestingField.nestedFields()` contain a `TableField` with the correct parent-specific `joinPath` (verifying the classifier resolved `@reference` against each outer parent's table context independently).

**No negative shape-equality test.** Per "Desired end state", a return-type mismatch between the two sides is unreachable from SDL (the shared nested type declares each field exactly once, and `returnType()` derives from that single declaration). The existing class-equality error at the top of `compareNestedFieldsShape` is already covered; no new negative case is authored here.

**Execution test** (`graphitron-rewrite/graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls`): add a two-parent fixture mirroring a real Sakila shape. **Concrete candidate:** `customer`, `staff`, and `store` all FK to `address`. Pick two of them (e.g. `Customer` and `Staff`) with a shared nested type exposing `address: Address` as a `TableField`. Verify via `GraphQLQueryTest` that a query against either parent returns the correct `address` record, exercising each parent's FK-inferred joinPath to `address` independently.

*Before landing:* confirm the chosen pair of Sakila tables is already present in the `graphitron-rewrite-test` schema (or add them), and that the shared-nested-type shape fits the existing `$fields` pipeline without requiring new directive plumbing.

**No unit test for `compareNestedFieldsShape`** — the method is private and tested transitively through `GraphitronSchemaBuilderTest`. Consistent with how other validator rules are covered.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes; includes the new `MULTIPARENT_NESTING_TABLEFIELD` pipeline-test case.
- `(cd graphitron-rewrite && mvn test -Plocal-db)` passes; includes the new execution-test fixture. `-Plocal-db` is required — see CLAUDE.md's fixtures-clobber note.
- Grepping for the old `#8` pointer in the validator returns zero hits.
- Roadmap has the new Backlog entry.

### Manual

- User's `EmneStudieprogramKoblingPeriode` / `fraTermin` case classifies without the "not yet supported across multiple parents" error when the rewrite runs against `sis-graphql-spec`. If any of that schema's shared nested types contain `BatchKey` leaves, they stay rejected with the updated (pointer-free) message — out of scope for this plan.

## References

Identifier-level references (line numbers drift; sibling plan [plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md) established this convention):

- Error site and existing multi-parent shape check: the catch-all arm in `GraphitronSchemaValidator.compareNestedFieldsShape`. Landed with `0b2e4e9` + `49d7879` (Nesting-field emission).
- Parent-agnostic `TableField` wiring: the `ChildField.TableField` arm of `TypeFetcherGenerator.buildWiringEntry`.
- Per-parent `$fields` emission: `TypeClassGenerator.emitSelectionSwitch` → `InlineTableFieldEmitter.buildSwitchArmBody`.
- First-parent-wins wiring registration: `GraphQLRewriteGenerator.collectNestedTypes` (uses `putIfAbsent` on nested-type name) → `GraphitronWiringClassGenerator.NestedTypeWiring.representativeParentTable`.
