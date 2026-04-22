# Multi-parent `NestingField` sharing — `TableField` arm

> **Status:** Spec

## Overview

Allow a plain-object (NestingField) type to be used under multiple `@table` parents when its fields include `ChildField.TableField` leaves, not just `ChildField.ColumnField` or nested `NestingField`. The validator currently hard-rejects every non-Column, non-Nesting leaf in the shared-shape check. Lift the gate for `TableField` only: add a shape-compatibility rule comparing the target GraphQL type, and accept divergent `joinPath` / `filters` / `orderBy` / `pagination` because those are legitimately per-parent — each parent's `$fields` emits its own correlated subquery. No emitter or wiring changes needed; today's codegen already supports this shape once the validator lets it through.

## Current state

`GraphitronSchemaValidator.compareNestedFieldsShape` (`GraphitronSchemaValidator.java:470-549`) allows exactly two leaf shapes when a NestingField type is used under multiple `@table` parents:

- `ColumnField` — compared by `sqlName()` + `columnClass()`. Relies on jOOQ's name-based `Record.get(Field)` fallback at runtime to project the same-named column across parents.
- Inner `NestingField` — recurses via `compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors)`, threading the outer parent names so deep errors still name the original tables.

Everything else — `TableField`, `LookupTableField`, `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, `ConstructorField`, `NodeIdField` — lands in the catch-all at `:525-535` with the message "classifies as X which is not yet supported across multiple parents — see rewrite-roadmap.md #8".

Two problems with the status quo:

1. **The `#8` pointer is wrong.** Roadmap `#8` enumerates *leaf type* stubs (`ColumnReferenceField`, `ComputedField`, …) — an orthogonal axis from multi-parent sharing. `TableField` is already fully implemented for single-parent use (G5 + `aaadb78b`); the only gap is the validator shape check.
2. **The gate is over-broad for `TableField`.** Emission and wiring for a nested `TableField` are parent-agnostic:
   - `TypeClassGenerator.emitSelectionSwitch` emits one `DSL.multiset(...)` arm per parent's `$fields` method, with parent-specific alias and joinPath. Each parent's generated SQL is independent.
   - `TypeFetcherGenerator.buildWiringEntry` for `ChildField.TableField` (lines 1301-1317) reads the multiset result by field name from `env.getSource()` — it doesn't consult the outer parent table. The same DataFetcher works for records produced by either parent's `$fields`.
   - `GraphQLRewriteGenerator.collectNestedTypes` (`:115-127`) registers one `NestedTypeWiring` per distinct nested type via `putIfAbsent`; the first-seen parent's entry wins, and for `TableField` that choice doesn't constrain runtime behaviour because the DataFetcher doesn't read `representativeParentTable`.

Real-world report: user running the rewrite validator against `sis-graphql-spec` hits this on `EmneStudieprogramKoblingPeriode` shared across `EmneStudieprogramkobling` and `StudieprogramEmnekobling`, where the shared `fraTermin` field classifies as `TableField`. The only workaround today is duplicating the nested type per parent in SDL.

## Desired end state

`compareNestedFieldsShape` recognises `ChildField.TableField` as a permitted multi-parent leaf. Added shape rule:

- Both sides must produce the same target GraphQL type name (`returnType().returnTypeName()`).

The wrapper cardinality (single vs list) is encoded in the shared nested-type's GraphQL field signature, so it's structurally identical between parents and doesn't need a runtime check. `joinPath`, `filters`, `orderBy`, `pagination` are legitimately per-parent and intentionally not compared.

Verification: a two-parent NestingField fixture where both parents' inline `TableField` projects to the same target `@table` via divergent FK paths classifies without error, compiles, and returns per-parent-correct rows at runtime.

## What we're NOT doing

- **BatchKey leaves under NestingField across parents.** `SplitTableField` / `LookupTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` all have per-field DataLoader or rows-method generation that's keyed off the outer parent context today. Reconciling those across a shared NestingField is a larger piece of work — separate Backlog entry (§3 below). The catch-all arm in the validator stays as the fallback; only the error-message pointer gets fixed.
- **Deeper inline recursion.** Already works via the existing `NestingField` recursion branch at `:520-524`.
- **`ConstructorField` / `NodeIdField` / reference-scalar leaves.** No known real-world demand; stay rejected.
- **Shape-compat of `filters` / `orderBy` / `pagination`.** Deliberately left per-parent. If a future schema author wants to enforce them matching, that's a separate opt-in directive — not part of this work.

## Implementation approach

### 1. Validator — add the `TableField` arm

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

Between the existing `ColumnField` arm (line 502) and the `NestingField` recursion arm (line 520), add:

```java
} else if (rf instanceof ChildField.TableField rtf && of instanceof ChildField.TableField otf) {
    // Target GraphQL type must match — the nested type's DataFetcher reads by field name
    // from env.getSource() and is parent-agnostic. joinPath / filters / orderBy / pagination
    // are legitimately per-parent: each outer parent's $fields emits its own DSL.multiset arm
    // with the correct parent-specific correlation, so divergent shapes across parents are
    // safe by construction.
    if (!rtf.returnType().returnTypeName().equals(otf.returnType().returnTypeName())) {
        errors.add(new ValidationError(
            "Nested type '" + nestedTypeName + "' shared across '" + repParent
                + "' and '" + otherParent + "': field '" + name
                + "' targets '" + rtf.returnType().returnTypeName() + "' on the first but '"
                + otf.returnType().returnTypeName() + "' on the second",
            other.location()
        ));
    }
}
```

Place it above the `NestingField` recursion so the more-specific record type wins over the sealed-interface fallback.

### 2. Fix the error-message pointer

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java:529-534`

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

**Pipeline test** (`GraphitronSchemaBuilderTest`): new case `MULTIPARENT_NESTING_TABLEFIELD`. Two `@table` parents declaring the same nested type; the nested type contains a `TableField` targeting a third `@table` via explicit `@reference` (different FK paths per parent — e.g. via separate directive declarations on each parent's field). Classifier emits no errors; both parents' `NestingField.nestedFields()` contain a `TableField` with the correct parent-specific `joinPath`.

**Pipeline test — negative case:** variant of the above where the two parents' shared nested type declares `fraTermin` targeting *different* GraphQL types. Expect the new "targets 'X' on the first but 'Y' on the second" error.

**Execution test** (`graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls`): add a two-parent fixture mirroring the Sakila schema. Candidate pattern: two tables that both FK to the same target — `Film` and `Rental` could both nest a `language: Language` field under a shared nested type (`MediaMeta` or similar). Verify via `GraphQLQueryTest` that a query against either parent returns the correct language record, exercising each parent's own FK-inferred (or explicit) joinPath.

**No unit test for `compareNestedFieldsShape`** — the method is private and tested transitively through `GraphitronSchemaBuilderTest`. Consistent with how other validator rules are covered.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes; includes the new positive and negative pipeline-test cases.
- `mvn test -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db` passes; includes the new execution-test fixture. `-Plocal-db` is required — see CLAUDE.md's fixtures-clobber note.
- Grepping for the old `#8` pointer in the validator returns zero hits.
- Roadmap has the new Backlog entry.

### Manual

- User's `EmneStudieprogramKoblingPeriode` / `fraTermin` case classifies without the "not yet supported across multiple parents" error when the rewrite runs against `sis-graphql-spec`. If any of that schema's shared nested types contain `BatchKey` leaves, they stay rejected with the updated (pointer-free) message — out of scope for this plan.

## References

- Error site: `GraphitronSchemaValidator.java:525-535`.
- Existing multi-parent shape check: `compareNestedFieldsShape` lines 465-549, landed with `0b2e4e9` + `49d7879` (Nesting-field emission).
- Parent-agnostic TableField wiring: `TypeFetcherGenerator.buildWiringEntry` lines 1301-1317.
- Per-parent `$fields` emission: `TypeClassGenerator.emitSelectionSwitch`; `InlineTableFieldEmitter.buildSwitchArmBody`.
- First-parent-wins wiring registration: `GraphQLRewriteGenerator.collectNestedTypes` (`:115-127`).
