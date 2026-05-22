---
id: R232
title: Resolve @condition-method target table on inner-SELECT FROM
status: Spec
bucket: architecture
priority: 10
theme: structural-refactor
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Resolve @condition-method target table on inner-SELECT FROM

## Problem

`@reference(path: [{condition: {…}}])` synthesises `JoinStep.ConditionJoin(MethodRef, alias)` with **no target `TableRef`** (`BuildContext.java:1349-1359`, `JoinStep.java:191-207`). The condition method returns `Condition`; its second parameter is typically the concrete jOOQ table class in real-world code (`graphitron-example/.../CityConditions.customersForCityViaAddresses(Address, Customer)` is representative), though the rewrite-side `TestConditionStub.join(Table<?>, Table<?>)` exercises the wildcard shape too. Neither the field's return-type `@table` binding nor the condition method's parameter signature is consulted by today's parser, so the target table stays unresolved on `ConditionJoin` even when the schema and Java code clearly imply it. The SDL author can write a perfectly classifiable condition-join path that the emitter cannot lower.

Seven `ChildField` variants emit a build-time deferred rejection when their `@reference` path contains any `ConditionJoin`:

* Inline `ChildField.TableField` / `ChildField.LookupTableField` — validator-rejected since R228 (`GraphitronSchemaValidator.validateInlineTableField` / `validateInlineLookupTableField`; the inline emitters at `InlineTableFieldEmitter.java:53-60` and `InlineLookupTableFieldEmitter.java` consult `SplitRowsMethodEmitter.unsupportedReason`).
* `ChildField.SplitTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` — all four route through `SplitRowsMethodEmitter.unsupportedReason` (`SplitRowsMethodEmitter.java:324-335`).
* `ChildField.ColumnReferenceField` — `GraphitronSchemaValidator.java:575-583` surfaces a separate `Rejection.Deferred` keyed to slug `column-reference-on-scalar-field-condition-join` ([R129](column-reference-on-scalar-field-condition-join.md)).

A real-world example in `tilgangsstyring-app`'s experimental schema, `ApiTilgangForMaskinbrukerV2.roller`, hits the `SplitTableField` arm.

## Design

Resolve `ConditionJoin.targetTable` from sources that already exist, without adding SDL syntax. Two resolution paths, applied in `BuildContext.parsePathElement` for a `{condition:}`-only element:

1. **Terminal hop — read the field's return-type `@table` binding.** A field `actor: Actor` whose `Actor` type carries `@table(name: "actor")` already declares the target table. The parser consults the carrier field's return-type binding when filling the terminal hop's `targetTable`. No reflection, no SDL change.
2. **Intermediate hop — reflect on the condition method's second parameter.** When the parameter type is a generated jOOQ table class (e.g. `static Condition join(Film src, Actor tgt)`), look it up in `JooqCatalog`. The path classifier already crosses the reflection boundary in `ServiceCatalog` / `FieldBuilder` / `SourceRowDirectiveResolver` etc. for parameter classification; adding one more reflection site for the condition method's tgt parameter is on-axis.

The carrier field's return-type binding is the dominant case in real schemas (the audit of one large production schema found no intermediate-hop `{condition:}`-only paths; every condition-only path was a single step). Reflection on the method param covers the intermediate-hop case so it's not deferred.

### Why no SDL change

The condition method's second parameter is the natural target-table carrier in well-written code; the field's return type is the natural target-table carrier in any schema that has classified cleanly so far. Adding a new SDL field (`table:` sibling to `condition:`) would be redundant with information the model already carries, and would force a migration on every existing `{condition:}`-only `@reference` path. The legacy surface combines `{table:, key:, condition:}` freely; `table:` already has a settled meaning in the parser ("FK derived from current source to the named target table"). Reusing it for "ConditionJoin target" would silently flip that meaning for any existing `{table:, condition:}` element. The lift therefore declines new SDL syntax and lives entirely in the parser's resolution chain.

### Legacy compatibility

Every existing combination in `BuildContext.parsePathElement` keeps its current semantics. No semantic flips:

| Path element | Today and after this lift |
|---|---|
| `{key:}` | FK by named key |
| `{table:}` | FK derived from current source to the named target table |
| `{table:, key:}` | FK by named key; `table:` anchors the target |
| `{condition:}` | `ConditionJoin`; **target resolved from the chain above** (was: deferred-rejection) |
| `{key:, condition:}` | FK by named key, `condition:` becomes `FkJoin.whereFilter` |
| `{table:, condition:}` | FK derived from endpoints, `condition:` becomes `FkJoin.whereFilter` |
| `{table:, key:, condition:}` | FK by named key, `table:` anchors target, `condition:` becomes `FkJoin.whereFilter` |

The path-element surface (`@oneOf`-style cleanup of the `{table, key, condition}` combinations) is a separate Backlog item; this lift does not change the surface, only fills in the previously-deferred ConditionJoin target.

### Author-error surface

* **`{condition:}`-only terminal step on a field whose return type has no `@table` binding** — `AUTHOR_ERROR` with message *"condition-only `@reference` path on field '<qualifiedName>': cannot resolve target table because return type '<typeName>' has no `@table` binding. Add `@table(name: …)` to the return type, or rewrite the path to include `{table:}` or `{key:}`."* The classifier records this on the carrier field's `Resolved` path as a typed `Rejection.AuthorError`.
* **Intermediate `{condition:}`-only step whose condition method's second parameter is `Table<?>`** — `AUTHOR_ERROR` with message *"intermediate-hop `@condition` method '<className>.<method>' has wildcard target parameter `Table<?>`; the parser cannot infer the target table for this hop. Change the second parameter to the concrete jOOQ table type, or rewrite the path to use `{table:}` or `{key:}` for this hop."*

`Table<?>` condition methods are explicitly supported for the terminal-hop case (return-type resolution does not depend on the method signature). The error surface only covers the cases where neither resolver can supply a target.

## Implementation

### 1. Model — `ConditionJoin` carries a target

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java`

```java
record ConditionJoin(MethodRef condition, TableRef targetTable, String alias) implements JoinStep {
    public ConditionJoin {
        if (condition == null) throw new NullPointerException("ConditionJoin.condition must not be null");
        if (targetTable == null) throw new NullPointerException(
            "ConditionJoin.targetTable must not be null; BuildContext.parsePathElement resolves it from "
            + "the carrier field's return-type @table binding (terminal hop) or by reflecting on the "
            + "condition method's second parameter (intermediate hop). Both unresolvable cases route "
            + "through Rejection.AuthorError upstream.");
    }
}
```

`ConditionJoin` does **not** implement `WithTarget`. That interface bundles `targetTable()` with `slots()` / `slotCount()` / `sourceSideColumns()` / `targetSideColumns()` — the FK-correlation slot list. Condition joins do not produce slot pairings (the ON clause is the condition method call). Reading `targetTable()` uniformly across all three step variants is a sealed switch over `JoinStep`:

```java
TableRef target = switch (step) {
    case JoinStep.WithTarget wt -> wt.targetTable();
    case JoinStep.ConditionJoin cj -> cj.targetTable();
};
```

Exhaustive by the sealed permit list. Each consumer that needs the target table widens to this shape; consumers that need slot iteration (the FK-correlation predicate sites) stay typed against `WithTarget` and route ConditionJoin first hops through a separate arm (see step 5).

### 2. Path-element parser — resolve `ConditionJoin.targetTable` from the resolution chain

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`

In `parsePathElement` (the `condition:`-only arm currently at `BuildContext.java:1349-1361`):

1. **Terminal-hop case (last element of `path`).** Look up the carrier field's return-type `@table` binding (already in `BuildContext` scope via the `currentSourceSqlName` context and the field's typed return). Build the `TableRef` from the catalog. Surface `AUTHOR_ERROR` when the return type has no `@table` binding, with the message in the Design section.
2. **Intermediate-hop case.** Reflect on the condition method's second parameter. If it is a concrete generated jOOQ table class (`Class<?>` that `JooqCatalog.findTableByClass(c)` resolves), build a `TableRef` from the catalog entry. If it is `Table<?>` (wildcard), surface `AUTHOR_ERROR` per the Design section.

Construct `new ConditionJoin(methodRef, targetTable, alias)` with the resolved target. The arms for `{key:}`, `{table:}`, `{key:, condition:}`, `{table:, condition:}`, `{table:, key:}`, `{table:, key:, condition:}` are not touched; their semantics stay verbatim. Factor out a small `resolveConditionJoinTarget(carrierField, methodRef, isTerminal)` helper so the two arms in the chain stay close to each other and the error messages live in one place.

`JooqCatalog` gains one new accessor: `Optional<TableRef> findTableByClass(Class<?> jooqTableClass)`. The class-keyed lookup is symmetric with the existing `findTable(String)`; the catalog already indexes generated tables.

### 3. Alias emitter — sealed-switch target read; drop the terminal-table fallback

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JoinPathEmitter.java`

`targetJavaClassName` (`JoinPathEmitter.java:56-66`) collapses to a uniform read across all three variants:

```java
return switch (step) {
    case JoinStep.WithTarget wt -> wt.targetTable().tableClass().simpleName();
    case JoinStep.ConditionJoin cj -> cj.targetTable().tableClass().simpleName();
};
```

The `terminalTable` parameter on `generateAliases` is no longer needed; callers drop it. Same on the `targetJavaClassName` helper. The `hasConditionJoin` predicate retires entirely — its three consumers (validators in step 6, the `InlineColumnReferenceFieldEmitter` defensive arm in step 7) all delete in this pass.

### 4. Inline emitters — emit `.join(targetTable).on(method(src, tgt))` for `ConditionJoin` steps

**Files:**
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineTableFieldEmitter.java`
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineLookupTableFieldEmitter.java`

Both inline emitters take symmetric changes. `buildFkOnlyArm` becomes `buildArm` (no longer FK-only). The two loops in each:

* **Table-alias declaration** (`InlineTableFieldEmitter.java:77-83` and the parallel block in `InlineLookupTableFieldEmitter`): widen the cast from `(JoinStep.FkJoin) path.get(i)` to a sealed switch reading `targetTable()` (step 1's switch shape). Both `FkJoin` and `ConditionJoin` produce the same `Tables.X.as(parent.getName() + "_xN")` declaration shape. `LiftedHop` stays an `IllegalStateException` in the inline emitters because no current variant routes a lifted hop through them.
* **JOIN chain** (`buildInnerSelect:117-122` and the parallel block in `InlineLookupTableFieldEmitter`): widen the loop to dispatch on step type. `FkJoin` keeps `.join(alias).onKey(fk.keysClass(), fk.constantName())`; `ConditionJoin` emits `.join(alias).on(<method>(prevAlias, alias))` via `JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), prevAlias, alias)`. Step 0's correlation against the parent: `FkJoin` keeps `emitCorrelationWhere`; `ConditionJoin` at step 0 calls `method(parentAlias, terminalAliasAtStep0)`, which serves as both the JOIN ON predicate and the parent correlation (no separate WHERE clause).

The single-cardinality `.limit(1)`, WHERE filters, ORDER BY, and pagination clauses are step-type-agnostic and unchanged.

The `unsupportedReason` stub branches at `InlineTableFieldEmitter.java:53-60` and the parallel block at `InlineLookupTableFieldEmitter.java:64-70` both delete.

### 5. Split-rows emitter — materialise a parent-table alias for ConditionJoin-first paths

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

The split-rows variants (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`) join `parentInput` (a VALUES-derived `Table<Record<N+1>>` keyed by `(idx, pk_col1, …)`) to the FK chain on PK columns. For ConditionJoin-first paths, the condition method expects a concrete-typed parent table (`join(Film src, Actor tgt)`), so the emitter materialises a parent-table alias and routes the parent correlation through it.

Shape for the FkJoin-first / LiftedHop-first path (unchanged):

```
FROM terminalAlias
JOIN (intermediate FkJoin hops...)
JOIN parentInput ON firstHop.targetCols = parentInput.pkCols
```

Shape for ConditionJoin-first paths:

```
FROM terminalAlias
JOIN parentAlias ON conditionMethod(parentAlias, terminalAlias)        // ConditionJoin first hop
JOIN (intermediate FkJoin hops... if any)
JOIN parentInput ON parentAlias.pk_col1 = parentInput.field("pk_col1", ...)
                AND parentAlias.pk_col2 = parentInput.field("pk_col2", ...)
                ...
```

The parent-table alias is built from the carrier field's parent type's `@table` binding: `<ParentTable> parentAlias = Tables.<PARENT>.as(fieldName + "_parent")`. The PK columns for the parentInput JOIN come from `sourceKey.columns()` (the parent's PK, already on `sourceKey` for the data-loader path); the parentAlias side reads each by `parentAlias.<col.javaName()>`.

Implementation details in `SplitRowsMethodEmitter.emitParentInputAndFkChain`:

* The `JoinStep firstStep = joinPath.get(0)` branch at `:204-208` widens: if `firstStep instanceof JoinStep.ConditionJoin cj`, emit the parent-alias declaration and follow with the JOIN-to-parentInput on the parent's PK columns. Otherwise (FkJoin or LiftedHop), keep the existing `WithTarget` slot-based correlation.
* The alias-declaration loop at `:260-266` widens to the sealed switch from step 1 to read `targetTable()` from any step type.
* The bridging-JOIN loop in `buildListMethod` (`:683-688`) and the analogous loops in `buildSingleMethod` and the connection method widen to dispatch on step type as in the inline JOIN chain.

`unsupportedReason` (`SplitRowsMethodEmitter.java:324-335`) deletes outright. The six `Rejection.EmitBlockReason` values it produced — `TABLE_FIELD_CONDITION_JOIN_STEP`, `LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP` — delete with their last producers. The `RecordTableMethodField` arm at `:421-440` is independent (it predates `unsupportedReason` and has its own `unsupportedPath` check); it keeps its own ConditionJoin rejection or gets the same widening — pick consistently with the other Record* variants in this commit.

### 6. Validator — drop deferred-rejection arms

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

* `validateColumnReferenceField` (`GraphitronSchemaValidator.java:575-583`) — drop the `hasConditionJoin` branch. The `@LoadBearingClassifierCheck(key = "column-reference-field-no-condition-join-step", …)` annotation at lines 546-552 deletes with the branch (no remaining producer).
* `validateSplitTableField` / `validateSplitLookupTableField` / `validateRecordTableField` / `validateRecordLookupTableField` — drop the `unsupportedReason` deferred-rejection consult on each.
* `validateInlineTableField` / `validateInlineLookupTableField` (the two arms added in R228) — drop their deferred-rejection branches symmetrically.

`validateReferenceLeadsToType` (`:615-630`) currently special-cases `ConditionJoin` because it had no pre-resolved target. The check works without the special-case once ConditionJoin carries a target; the spec leaves the special-case in place because the resolution chain already guarantees the target equals the field's return-type table for terminal hops — making the check tautological — and dropping it is a no-op cleanup not worth the diff. Marked as a follow-up task in the same item file if desired.

The classification-time guards in `FieldBuilder` and `ConditionResolver` are unaffected; they classify `ConditionJoin` correctly today and only emission was deferred.

### 7. Inline `ChildField.ColumnReferenceField` — widen the join-loop dispatch

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineColumnReferenceFieldEmitter.java`

The defensive `IllegalStateException` at `InlineColumnReferenceFieldEmitter.java:59-63` deletes. The join-loop widens with the same `switch (step)` shape as the inline TableField emitter (step 4). The `@DependsOnClassifierCheck(key = "column-reference-field-no-condition-join-step", …)` annotation at lines 52-56 deletes with the early-return (no remaining producer; symmetric with the `@LoadBearingClassifierCheck` removal in step 6).

R129 (`column-reference-on-scalar-field-condition-join`) closes — the slug folds into R232's implementation per its own body's recommendation. The file deletes on Done; `changelog.md` records the R129 → R232 absorption.

### 8. Capability and stale-reference cleanup

In the same set of commits:

* `ConditionJoinReportable` capability (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ConditionJoinReportable.java`) and its three accessors (`joinPath`, `emitBlockReason`, `displayLabel`) delete — no remaining consumers after step 5. The six `ChildField` variants that implement it drop the `implements ConditionJoinReportable` from their record headers (`ChildField.java:337`, `:358`, `:382`, `:404`, `:745`, `:779`) and the `displayLabel()` / `emitBlockReason()` method bodies on each.
* `R58TypedRejectionPipelineTest.conditionJoinReportable_implementedByExpectedSixVariants` deletes with the capability.
* `column-reference-on-scalar-field-condition-join.md` (R129) — file deletes per step 7.
* `lsp-diagnostic-redundant-splitquery-on-record.md:13` (R121) — drop the "R3 (`classification-vocabulary-followups`) lands the build-tier warning when …" sentence; the warning has shipped per changelog `162`.
* `nestingfield-multiparent-tablefield.md:56` and `:134` — replace the dangling `plan-classification-vocabulary-followups.md §7` link with prose stating the convention directly.
* `fkjoin-alias-dead-storage.md:20` (R120) — keep the "originally item 5 of `classification-vocabulary-followups`" attribution as historical provenance for the open Backlog item.

In source comments:

* `InlineTableFieldEmitter.java:29-31` javadoc — delete the "owned by classification-vocabulary item 5" sentence.
* `InlineLookupTableFieldEmitter.java:36-37` javadoc — delete the analogous "ConditionJoin anywhere in the path triggers a runtime-throwing stub arm" sentence.
* `JoinPathEmitter.java:60-66` and `:68-76` — delete the `ConditionJoin` empty-string arm, the `hasConditionJoin` predicate, and its "G5 cannot yet emit" javadoc.
* `GraphitronSchemaValidator.java:545-552` and `:575-583` — `@LoadBearingClassifierCheck` and the deferred-rejection branch delete together (covered in step 6).
* `JoinStep.java:194-206` — rewrite the `ConditionJoin` record's javadoc to describe the resolution chain (return-type-binding for terminal, method-param reflection for intermediate) instead of the legacy "target not pre-resolved" stub language.

The path-element surface cleanup (the `@oneOf`-style cleanup of `{table, key, condition}` combinations) is filed as a separate Backlog item by the implementer at the start of the work.

## Tests

### Pipeline tier — `*ValidationTest` flips from "rejected" to "classifies and emits"

Each named case below flips from a deferred-rejection assertion to "no rejection, path contains a `JoinStep.ConditionJoin` with non-null `targetTable` resolved from the return-type binding":

* `TableFieldValidationTest.SINGLE_WITH_CONDITION_ONLY`, `LIST_WITH_CONDITION_ONLY`
* `LookupTableFieldValidationTest.LIST_WITH_CONDITION_ONLY` (no single-cardinality case exists — classifier rejects single-cardinality `@lookupKey`)
* `SplitTableFieldValidationTest.WITH_CONDITION_ONLY`
* `RecordTableFieldValidationTest.SINGLE_WITH_CONDITION_ONLY`, `LIST_WITH_CONDITION_ONLY`
* `RecordLookupTableFieldValidationTest.LIST_WITH_CONDITION_ONLY`
* `ColumnReferenceFieldValidationTest.CONDITION_METHOD`

`R58TypedRejectionPipelineTest.inlineTableField_conditionJoinStep_rejectedAtBuildTime` and `inlineLookupTableField_conditionJoinStep_rejectedAtBuildTime` rename to `..._emitsCorrelatedSubquery` (or delete if the per-variant validation cases above cover the shape adequately). `conditionJoinReportable_implementedByExpectedSixVariants` deletes with the capability per step 8.

### New pipeline-tier coverage — parser resolution chain

`GraphitronSchemaBuilderTest` gains fixtures that exercise the resolution chain:

* `CONDITION_ONLY_TERMINAL_RESOLVES_TARGET_FROM_RETURN_TYPE` — `actor: Actor @reference(path: [{condition: {…}}])` where `Actor @table(name: "actor")`. Asserts the path step is a `ConditionJoin` with `targetTable` equal to the `actor` table.
* `CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED` — return type has no `@table` binding (e.g. a `@record`-backed type). Asserts `Rejection.AuthorError` with the message from the Design section.
* `CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM` — `@reference(path: [{condition: {className: "X", method: "intermediate"}}, {table: "<final>"}])` where `intermediate` is `static Condition intermediate(Film src, Actor tgt)`. Asserts the first step's `targetTable` resolved to the `actor` table via reflection.
* `CONDITION_INTERMEDIATE_TABLE_WILDCARD_REJECTED` — same shape as the previous case but with `static Condition intermediate(Table<?> src, Table<?> tgt)`. Asserts `Rejection.AuthorError` with the wildcard message.
* `TABLE_WITH_CONDITION_PRESERVES_WHERE_FILTER` — `{table:, condition:}` still produces an `FkJoin` with non-null `whereFilter` (regression guard that the legacy combination semantics were not flipped).
* `KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER` — `{key:, condition:}` still produces an `FkJoin` with non-null `whereFilter` (regression guard for the unchanged arm).

The existing `WITH_CONDITION_PATH` fixture (`GraphitronSchemaBuilderTest.java:1221-1236`) **stays as-is** — its SDL is `actor: Actor @tableMethod(...) @reference(path: [{condition: {…}}])`, the field's return-type `Actor` carries an `@table(name: "actor")` binding, and the resolution chain fills `ConditionJoin.targetTable` accordingly. The fixture's existing assertion (`field.joinPath().get(0)` is a `ConditionJoin`) extends with a `targetTable()` non-null check.

### Compilation tier

Add a fixture under `graphitron-fixtures-codegen` that exercises a `SplitTableField` with a `ConditionJoin`-first path step. The fixture compiles against the real jOOQ catalog; the generated correlated subquery is byte-for-byte stable in the snapshot.

### Execution tier — Sakila

Add a condition-join Sakila fixture exercising the `SplitTableField` shape (the variant hit by the `tilgangsstyring-app` real-world example). Candidate: an `Actor.featuredFilms` field via a synthesised condition method on `FilmActor` whose body filters on something the natural FK does not capture (e.g. `release_year`). The execution-tier test exercises:

1. The inner `FROM terminalAlias JOIN parentAlias ON conditionMethod(...) JOIN parentInput ON parentAlias.pk = parentInput.pkCols` shape (step 5's emission).
2. The `Result<Record>` rows correctly scatter by `idx` across the parent batch.
3. The condition method's body actually fires (not a runtime stub).

A second execution-tier fixture covering the inline `TableField` + ConditionJoin shape is included to give the inline emission path execution-tier coverage too.

## Roadmap entries

* This item (R232) — moves to Done on landing.
* R129 (`column-reference-on-scalar-field-condition-join`) — file deletes; absorbed per step 7. `changelog.md` records the absorption.
* **New Backlog item: path-element surface cleanup.** During the spec review, the legacy combinations on `ReferenceElement { table, key, condition }` were found to mix join-shape (`key:` | `table:` | `condition:`-alone) with WHERE-filter (`condition:` combined with `key:` or `table:`). The conflated `condition:` role and the free-combinations surface invite cargo-culting. A separate Backlog item captures the cleanup (likely `@oneOf` for join shape + a separate WHERE-filter field, or equivalent). The implementer files the Backlog item at the start of work on this lift, before any of the legacy combinations are touched.

## Diagnostics doc

`docs/manual/reference/diagnostics-glossary.adoc`'s six condition-join-step entries (`=== table-field-condition-join-step`, `=== lookup-table-field-condition-join-step`, and the four split/record variants) delete with their emit-block reason values per the `DiagnosticsDocCoverageTest` convention. If the user-manual chapter on `@reference` paths gains a "condition join" subsection, that is a separate edit; this item closes the deferred-rejection coordinates only.

## Acceptance signals

* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.
* `grep -r "classification-vocabulary item 5" graphitron-rewrite/` returns nothing.
* `grep -r "classification-vocabulary-followups" graphitron-rewrite/` returns at most the historical attribution in `fkjoin-alias-dead-storage.md`.
* No `plan-classification-vocabulary-followups.md` links remain in the tree.
* `grep -rn "ConditionJoinReportable\|unsupportedReason\|EmitBlockReason\.\(TABLE\|LOOKUP_TABLE\|SPLIT_TABLE\|SPLIT_LOOKUP_TABLE\|RECORD_TABLE\|RECORD_LOOKUP_TABLE\)_FIELD_CONDITION_JOIN_STEP" graphitron-rewrite/` returns nothing.
* The `ApiTilgangForMaskinbrukerV2.roller` external example, **unchanged in SDL**, classifies and emits.
