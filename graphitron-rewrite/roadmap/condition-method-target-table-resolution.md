---
id: R232
title: Resolve @condition-method target table on inner-SELECT FROM
status: In Progress
bucket: architecture
priority: 10
theme: structural-refactor
depends-on: []
created: 2026-05-22
last-updated: 2026-05-23
---

# Resolve @condition-method target table on inner-SELECT FROM

## Problem

`@reference(path: [{condition: {…}}])` synthesises `JoinStep.ConditionJoin(MethodRef, alias)` with **no target `TableRef`** (`BuildContext.java:1349-1359`, `JoinStep.java:191-207`). The condition method returns `Condition`; its second parameter is typically the concrete jOOQ table class in real-world code (`graphitron-example/.../CityConditions.customersForCityViaAddresses(Address, Customer)` is representative), though the rewrite-side `TestConditionStub.join(Table<?>, Table<?>)` exercises the wildcard shape too. Neither the field's return-type `@table` binding nor the condition method's parameter signature is consulted by today's parser, so the target table stays unresolved on `ConditionJoin` even when the schema and Java code clearly imply it. The SDL author can write a perfectly classifiable condition-join path that the emitter cannot lower.

Seven `ChildField` variants emit a build-time deferred rejection when their `@reference` path contains any `ConditionJoin`:

* Inline `ChildField.TableField` / `ChildField.LookupTableField` — validator-rejected since R228 via the centralised `GraphitronSchemaValidator.validateVariantIsImplemented` (`:230`) that consults `SplitRowsMethodEmitter.unsupportedReason` for every `GraphitronField` variant; the inline emitters at `InlineTableFieldEmitter.java:53-60` and `InlineLookupTableFieldEmitter.java:64-70` carry the corresponding runtime stub branches that consult the same predicate.
* `ChildField.SplitTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` — all four route through the same `validateVariantIsImplemented` consult (build-time rejection) and through `SplitRowsMethodEmitter.unsupportedReason` (`:324-335`) at each variant's emitter entry point (`buildForSplitTable`, `buildForSplitLookupTable`, `buildForRecordTable`, `buildForRecordLookupTable`).
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

### 1. Model — split `WithTarget`; `ConditionJoin` carries a target

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java`

Split the existing `WithTarget` capability so target-read and slot-iteration sit on separate interfaces, matching "Capability interfaces and sealed switches serve different roles" (`rewrite-design-principles.adoc:43-47`) and the worked example at `JoinStep.java:81-123`:

```java
/** Hop pre-resolves a target table the prelude joins to. */
interface HasTargetTable {
    TableRef targetTable();
    String alias();
}

/** Hop also pairs source/target columns for FK-correlation predicates. */
interface WithTarget extends HasTargetTable {
    Iterable<? extends JoinSlot> slots();
    int slotCount();
    default List<ColumnRef> sourceSideColumns() { /* existing body */ }
    default List<ColumnRef> targetSideColumns() { /* existing body */ }
}
```

`FkJoin` and `LiftedHop` continue to implement `WithTarget` (now via inheritance from `HasTargetTable`). `ConditionJoin` implements `HasTargetTable` directly. Target-only consumers (`JoinPathEmitter.targetJavaClassName`, the alias-declaration loops in inline and split-rows emitters, `validateReferenceLeadsToType`) read uniformly through `HasTargetTable` with no sealed switch. FK-correlation consumers (`emitCorrelationWhere`, `SplitRowsMethodEmitter.emitParentInputAndFkChain`'s slot-based correlation) stay typed against `WithTarget` and route ConditionJoin first hops through the `ParentCorrelation` carrier (step 2).

`ConditionJoin`'s record shape:

```java
record ConditionJoin(MethodRef condition, TableRef targetTable, String alias)
        implements JoinStep, HasTargetTable {
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

The producer-side `@LoadBearingClassifierCheck(key = "condition-join.target-table-resolved-at-parse", …)` lives on the `BuildContext.resolveConditionJoinTarget` helper introduced in step 3 (single producer per key; the compact constructor above is the structural safety net the helper's description names). Consumers that read `targetTable()` from any hop type carry the matching `@DependsOnClassifierCheck(key = "condition-join.target-table-resolved-at-parse", reliesOn = "…")` so the audit harness's producer/consumer net stays balanced (see step 7's `LoadBearingGuaranteeAuditTest` rule).

### 2. Model — `ParentCorrelation` sub-taxonomy

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ParentCorrelation.java` (new)

Lift the binary fork between FK-slot correlation and ConditionJoin-method correlation into the model so the five emitter sites (inline `TableField` / `LookupTableField` / `ColumnReferenceField` step-0 correlation; split-rows `buildListMethod` / `buildSingleMethod` / `buildConnectionMethod`) all read variant identity from one carrier instead of evaluating `instanceof JoinStep.ConditionJoin` over `joinPath.get(0)`. Sealed over two arms:

```java
public sealed interface ParentCorrelation
        permits ParentCorrelation.OnFkSlots, ParentCorrelation.OnConditionJoin {

    /** First hop is an FK or lifter; existing slot-based correlation. */
    record OnFkSlots(JoinStep.WithTarget firstHop) implements ParentCorrelation {
        public OnFkSlots { if (firstHop == null) throw new NullPointerException(); }
    }

    /** First hop is a condition method; correlation is the condition method call.
     *  {@code parentTable} is the carrier field's parent type's @table binding;
     *  {@code parentPkCols} is the parent's PK column list (populated for split-rows
     *  emission paths, empty list for inline emission paths where parentInput is not
     *  materialised). */
    record OnConditionJoin(
        JoinStep.ConditionJoin firstHop,
        TableRef parentTable,
        List<ColumnRef> parentPkCols
    ) implements ParentCorrelation {
        public OnConditionJoin {
            if (firstHop == null) throw new NullPointerException("ParentCorrelation.OnConditionJoin.firstHop must not be null");
            if (parentTable == null) throw new NullPointerException(
                "ParentCorrelation.OnConditionJoin.parentTable must not be null; the parser routes the "
                + "no-parent-table case through Rejection.AuthorError upstream.");
            parentPkCols = List.copyOf(parentPkCols);
        }
    }
}
```

Each `ChildField` variant whose `joinPath` enters the affected emitter sites gains a `ParentCorrelation parentCorrelation` field on its record header. The seven variants:

* `ChildField.TableField`, `ChildField.LookupTableField` (inline emitters; `parentPkCols` empty)
* `ChildField.SplitTableField`, `ChildField.SplitLookupTableField`, `ChildField.RecordTableField`, `ChildField.RecordLookupTableField` (split-rows emitters; `parentPkCols` populated from `sourceKey.columns()`)
* `ChildField.ColumnReferenceField` (inline emitter; `parentPkCols` empty)

The field name is `parentCorrelation`; classifier-time guarantee: `parentCorrelation.firstHop() == joinPath.get(0)` (compact-constructor invariant on each variant). The carrier is a denormalised view of data already on `joinPath.get(0)` + `sourceKey` (where present) + the carrier field's parent type's `@table` binding — pre-resolved once at parse time so consumers don't re-derive at each emit site.

`HasTargetTable` (step 1) handles the orthogonal "read this hop's target table" axis; `ParentCorrelation` handles the "what shape does parent correlation take at this path" axis. The two axes are decoupled: any `ChildField` variant can carry `ParentCorrelation.OnConditionJoin` regardless of which intermediate hops appear in the joinPath.

### 3. Path-element parser — resolve `ConditionJoin.targetTable` and synthesise `ParentCorrelation`

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`

In `parsePathElement` (the `condition:`-only arm currently at `BuildContext.java:1349-1361`):

1. **Terminal-hop case (last element of `path`).** Look up the carrier field's return-type `@table` binding (already in `BuildContext` scope via the `currentSourceSqlName` context and the field's typed return). Build the `TableRef` from the catalog. Surface `AUTHOR_ERROR` when the return type has no `@table` binding, with the message in the Design section.
2. **Intermediate-hop case.** Reflect on the condition method's second parameter. If it is a concrete generated jOOQ table class (`Class<?>` that `JooqCatalog.findTableByClass(c)` resolves), build a `TableRef` from the catalog entry. If it is `Table<?>` (wildcard), surface `AUTHOR_ERROR` per the Design section.

Construct `new ConditionJoin(methodRef, targetTable, alias)` with the resolved target. The arms for `{key:}`, `{table:}`, `{key:, condition:}`, `{table:, condition:}`, `{table:, key:}`, `{table:, key:, condition:}` are not touched; their semantics stay verbatim. Factor out a small `resolveConditionJoinTarget(carrierField, methodRef, isTerminal)` helper so the two arms in the chain stay close to each other and the error messages live in one place. Wear `@LoadBearingClassifierCheck(key = "condition-join.target-table-resolved-at-parse", description = "BuildContext.parsePathElement resolves ConditionJoin.targetTable at parse time from the carrier field's return-type @table binding (terminal hop) or by reflecting on the condition method's second parameter (intermediate hop). Both unresolvable cases route through Rejection.AuthorError upstream; the ConditionJoin compact constructor (step 1) is the structural safety net. Emitters consume cj.targetTable() without null-checks.")` on the helper. This is the sole producer for the key (the compact constructor above is not annotated, to avoid the duplicate-producer audit fail in `LoadBearingGuaranteeAuditTest`).

After the path's `JoinStep` list is built, synthesise `ParentCorrelation` for each carrier field whose variant carries it. Two arms:

* `joinPath.get(0)` is `WithTarget` (FkJoin or LiftedHop): `new ParentCorrelation.OnFkSlots(firstHop)`.
* `joinPath.get(0)` is `ConditionJoin`: resolve the parent type's `@table` binding (already in scope as the `currentSourceSqlName` context); read parent PK cols from `sourceKey.columns()` for split-rows variants, empty list for inline variants. Construct `new ParentCorrelation.OnConditionJoin(cj, parentTable, parentPkCols)`. Surface `AUTHOR_ERROR` if the parent type has no `@table` binding (the same condition the variant classifier would have flagged; centralise the message at the synthesis site).

`JooqCatalog` gains one new accessor: `Optional<TableEntry> findTableByClass(Class<?> jooqTableClass)`. Class-keyed lookups are schema-unique by construction (each generated jOOQ table class maps to exactly one catalog entry), so the return shape matches `findTable(String, String)` rather than the unqualified `findTable(String)`'s `TableResolution` sub-taxonomy.

### 4. Alias emitter — uniform `HasTargetTable` read

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JoinPathEmitter.java`

`targetJavaClassName` (`JoinPathEmitter.java:56-66`) reads through `HasTargetTable` (step 1) with no sealed switch:

```java
return ((HasTargetTable) step).targetTable().tableClass().simpleName();
// or, more idiomatic:
return step instanceof HasTargetTable ht
    ? ht.targetTable().tableClass().simpleName()
    : throw new IllegalStateException("JoinStep variant " + step.getClass() + " is not HasTargetTable");
```

All three current `JoinStep` permits implement `HasTargetTable` (FkJoin and LiftedHop via `WithTarget`, ConditionJoin directly), so the `instanceof` is exhaustive over the sealed permit list at compile time. Annotate the helper with `@DependsOnClassifierCheck(key = "condition-join.target-table-resolved-at-parse", reliesOn = "Reads ht.targetTable().tableClass() without null-check; depends on the parser's ConditionJoin.targetTable resolution chain. The compact constructor on ConditionJoin is the structural safety net.")`.

The `terminalTable` parameter on `generateAliases` is no longer needed; callers drop it. Same on the `targetJavaClassName` helper. The `hasConditionJoin` predicate retires entirely — its three consumers (validators in step 7, the `InlineColumnReferenceFieldEmitter` defensive arm in step 8) all delete in this pass.

### 5. Inline emitters — read `ParentCorrelation` for step-0; widen JOIN chain dispatch

**Files:**
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineTableFieldEmitter.java`
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineLookupTableFieldEmitter.java`

Both inline emitters take symmetric changes. `buildFkOnlyArm` becomes `buildArm` (no longer FK-only). Three changes per emitter:

* **Table-alias declaration** (`InlineTableFieldEmitter.java:77-83` and the parallel block in `InlineLookupTableFieldEmitter`): drop the `(JoinStep.FkJoin) path.get(i)` cast; read `targetTable()` through `HasTargetTable` (step 4's shape). Both `FkJoin` and `ConditionJoin` produce the same `Tables.X.as(parent.getName() + "_xN")` declaration. `LiftedHop` stays an `IllegalStateException` in the inline emitters because no current variant routes a lifted hop through them.
* **JOIN chain** (`buildInnerSelect:117-122` and the parallel block in `InlineLookupTableFieldEmitter`) for hops 1..N: widen the loop to dispatch on step type. `FkJoin` keeps `.join(alias).onKey(fk.keysClass(), fk.constantName())`; `ConditionJoin` emits `.join(alias).on(<method>(prevAlias, alias))` via `JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), prevAlias, alias)`.
* **Step-0 parent correlation**: read `field.parentCorrelation()` from the carrier field (step 2's record-field). Sealed switch:

  ```java
  switch (field.parentCorrelation()) {
      case ParentCorrelation.OnFkSlots fk ->
          where.add(JoinPathEmitter.emitCorrelationWhere(fk.firstHop(), firstAlias, parentAlias));
      case ParentCorrelation.OnConditionJoin cj ->
          // No separate WHERE; the JOIN's ON clause carries the condition method call.
          // The inner-SELECT's step-0 .join(firstAlias).on(method(parentAlias, firstAlias))
          // already covers the correlation.
          ;
  }
  ```

  The inline emitters use `parentAlias` (the SDL-context parent table) directly; `OnConditionJoin.parentTable` matches it by classifier-time guarantee but isn't re-read because the caller already passed it in. `parentPkCols` is unused on the inline path.

The single-cardinality `.limit(1)`, WHERE filters, ORDER BY, and pagination clauses are step-type-agnostic and unchanged.

The `unsupportedReason` stub branches at `InlineTableFieldEmitter.java:53-60` and the parallel block at `InlineLookupTableFieldEmitter.java:64-70` both delete.

### 6. Split-rows emitter — read `ParentCorrelation` for step-0; materialise parent-alias on the ConditionJoin arm

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

The split-rows variants (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`) join `parentInput` (a VALUES-derived `Table<Record<N+1>>` keyed by `(idx, pk_col1, …)`) to the FK chain on PK columns. The `ParentCorrelation` sealed switch (step 2) drives the parent-correlation emission shape.

Shape for `ParentCorrelation.OnFkSlots` (unchanged):

```
FROM terminalAlias
JOIN (intermediate FkJoin hops...)
JOIN parentInput ON firstHop.targetCols = parentInput.pkCols
```

Shape for `ParentCorrelation.OnConditionJoin`:

```
FROM terminalAlias
JOIN parentAlias ON conditionMethod(parentAlias, terminalAlias)        // first-hop condition
JOIN (intermediate FkJoin hops... if any)
JOIN parentInput ON parentAlias.pk_col1 = parentInput.field("pk_col1", ...)
                AND parentAlias.pk_col2 = parentInput.field("pk_col2", ...)
                ...
```

Both the parent-alias declaration (`<ParentTable> parentAlias = Tables.<PARENT>.as(fieldName + "_parent")`) and the parent-PK column list come from the `ParentCorrelation.OnConditionJoin` record (`parentTable`, `parentPkCols`) — no carrier-field-side derivation at emit time.

Implementation details in `SplitRowsMethodEmitter.emitParentInputAndFkChain`:

* The step-0 branch at `:204-208` replaces its `firstStep instanceof JoinStep.ConditionJoin` check with a sealed switch on `field.parentCorrelation()`. The `OnFkSlots` arm keeps the existing slot-based correlation; the `OnConditionJoin` arm emits the parent-alias declaration, the `.join(parentAlias).on(cj.firstHop().condition(...))` clause, and the parent-PK JOIN to parentInput.
* The alias-declaration loop at `:260-266` drops the `(JoinStep.WithTarget)` cast; reads through `HasTargetTable`.
* The bridging-JOIN loop in `buildListMethod` (`:683-688`) and the analogous loops in `buildSingleMethod` and the connection method widen to dispatch on step type as in the inline JOIN chain.

`unsupportedReason` (`SplitRowsMethodEmitter.java:324-335`) deletes outright. The six `Rejection.EmitBlockReason` values it produced — `TABLE_FIELD_CONDITION_JOIN_STEP`, `LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP` — delete with their last producers. The `RecordTableMethodField` arm at `:421-440` is independent (it predates `unsupportedReason` and has its own `unsupportedPath` check); it keeps its own ConditionJoin rejection or gets the same widening — pick consistently with the other Record* variants in this commit.

### 7. Validator — drop deferred-rejection arms

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

* `validateColumnReferenceField` (`GraphitronSchemaValidator.java:575-583`) — drop the `hasConditionJoin` branch. The `@LoadBearingClassifierCheck(key = "column-reference-field-no-condition-join-step", …)` annotation at lines 546-552 deletes with the branch (no remaining producer).
* `validateVariantIsImplemented` (`GraphitronSchemaValidator.java:219-232`) — drop the `SplitRowsMethodEmitter.unsupportedReason` consult on line 230. This is the single centralised dispatcher that surfaces the deferred rejection for all six condition-join-affected variants (`TableField`, `LookupTableField`, `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`); the per-variant validators (`validateTableField`, `validateLookupTableField`, etc.) carry no condition-join branch of their own. The deletion lands together with step 6's removal of `SplitRowsMethodEmitter.unsupportedReason` itself; the consult site at `:230` is what forces the compile fail that drags step 6 here. The accompanying javadoc on `validateVariantIsImplemented` (`:200-218`) gets rewritten to drop the "intra-variant emit-block" / `ConditionJoinReportable` paragraph since both delete in this pass.

`validateReferenceLeadsToType` (`:615-630`) currently special-cases `ConditionJoin` because it had no pre-resolved target. After step 1, `ConditionJoin implements HasTargetTable`; the special-case folds and the check reads `((HasTargetTable) lastStep).targetTable()` uniformly. For terminal-hop ConditionJoin the check is tautological (parser fills target from the same return-type binding the validator compares against) but the unified shape removes the dead code.

The classification-time guards in `FieldBuilder` and `ConditionResolver` are unaffected; they classify `ConditionJoin` correctly today and only emission was deferred.

### 8. Inline `ChildField.ColumnReferenceField` — widen the join-loop dispatch; read `ParentCorrelation`

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineColumnReferenceFieldEmitter.java`

The defensive `IllegalStateException` at `InlineColumnReferenceFieldEmitter.java:59-63` deletes. The join-loop widens with the same step-type dispatch as the inline TableField emitter (step 5); the step-0 correlation reads `field.parentCorrelation()` through the same sealed switch as step 5. The `@DependsOnClassifierCheck(key = "column-reference-field-no-condition-join-step", …)` annotation at lines 52-56 deletes with the early-return (no remaining producer; symmetric with the `@LoadBearingClassifierCheck` removal in step 7).

R129 (`column-reference-on-scalar-field-condition-join`) closes — the slug folds into R232's implementation per its own body's recommendation. The file deletes on Done; `changelog.md` records the R129 → R232 absorption.

### 9. Capability and stale-reference cleanup

In the same set of commits:

* `ConditionJoinReportable` capability (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ConditionJoinReportable.java`) and its three accessors (`joinPath`, `emitBlockReason`, `displayLabel`) delete — no remaining consumers after step 6. The six `ChildField` variants that implement it drop the `implements ConditionJoinReportable` from their record headers (`ChildField.java:337`, `:358`, `:382`, `:404`, `:745`, `:779`) and the `displayLabel()` / `emitBlockReason()` method bodies on each.
* `R58TypedRejectionPipelineTest.conditionJoinReportable_implementedByExpectedSixVariants` deletes with the capability.
* `column-reference-on-scalar-field-condition-join.md` (R129) — file deletes per step 8.
* `lsp-diagnostic-redundant-splitquery-on-record.md:13` (R121) — drop the "R3 (`classification-vocabulary-followups`) lands the build-tier warning when …" sentence; the warning has shipped per changelog `162`.
* `nestingfield-multiparent-tablefield.md:56` and `:134` — replace the dangling `plan-classification-vocabulary-followups.md §7` link with prose stating the convention directly.
* `fkjoin-alias-dead-storage.md:20` (R120) — keep the "originally item 5 of `classification-vocabulary-followups`" attribution as historical provenance for the open Backlog item.

In source comments:

* `InlineTableFieldEmitter.java:29-31` javadoc — delete the "owned by classification-vocabulary item 5" sentence.
* `InlineLookupTableFieldEmitter.java:36-37` javadoc — delete the analogous "ConditionJoin anywhere in the path triggers a runtime-throwing stub arm" sentence.
* `JoinPathEmitter.java:60-66` and `:68-76` — delete the `ConditionJoin` empty-string arm, the `hasConditionJoin` predicate, and its "G5 cannot yet emit" javadoc.
* `GraphitronSchemaValidator.java:545-552` and `:575-583` — `@LoadBearingClassifierCheck` and the deferred-rejection branch delete together (covered in step 7).
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

`R58TypedRejectionPipelineTest.inlineTableField_conditionJoinStep_rejectedAtBuildTime` and `inlineLookupTableField_conditionJoinStep_rejectedAtBuildTime` rename to `..._emitsCorrelatedSubquery` (or delete if the per-variant validation cases above cover the shape adequately). `conditionJoinReportable_implementedByExpectedSixVariants` deletes with the capability per step 9.

### New pipeline-tier coverage — model invariants

* `HasTargetTableInvariantTest` — every `JoinStep` permit implements `HasTargetTable`; reading `targetTable()` from each variant under a `JoinStep`-typed local returns a non-null `TableRef`. Pins the split capability shape (step 1).
* `ParentCorrelationFirstHopInvariantTest` — for each affected `ChildField` variant in `GraphitronSchemaBuilderTest`, `field.parentCorrelation().firstHop() == field.joinPath().get(0)`. Pins the cross-axis invariant from step 2.
* `LoadBearingGuaranteeAuditTest` (existing scan) picks up the new `condition-join.target-table-resolved-at-parse` key automatically; the spec adds no new bookkeeping, just verifies the producer/consumer net is balanced.

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

1. The inner `FROM terminalAlias JOIN parentAlias ON conditionMethod(...) JOIN parentInput ON parentAlias.pk = parentInput.pkCols` shape (step 6's emission).
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
