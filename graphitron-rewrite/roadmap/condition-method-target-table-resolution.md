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

`@reference(path: [{condition: {…}}])` synthesises `JoinStep.ConditionJoin(MethodRef, alias)` with **no target `TableRef`** (`BuildContext.java:1349-1359`, `JoinStep.java:191-207`). The condition method returns `Condition`, and its second-table parameter is conventionally typed `Table<?>` (see `TestConditionStub.join`), so neither the return type nor the parameter signature carries a concrete target table the way `@tableMethod` does. The SDL author can write a perfectly classifiable condition-join path that the emitter cannot lower.

Seven `ChildField` variants emit a build-time deferred rejection when their `@reference` path contains any `ConditionJoin`:

* Inline `ChildField.TableField` / `ChildField.LookupTableField` — validator-rejected since R228 (`GraphitronSchemaValidator.validateInlineTableField` / `validateInlineLookupTableField`; the inline emitters at `InlineTableFieldEmitter.java:53-60` and `InlineLookupTableFieldEmitter.java` consult `SplitRowsMethodEmitter.unsupportedReason`).
* `ChildField.SplitTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` — all four route through `SplitRowsMethodEmitter.unsupportedReason` (`SplitRowsMethodEmitter.java:324-335`).
* `ChildField.ColumnReferenceField` — `GraphitronSchemaValidator.java:575-583` surfaces a separate `Rejection.Deferred` keyed to slug `column-reference-on-scalar-field-condition-join` ([R129](column-reference-on-scalar-field-condition-join.md)).

A real-world example in `tilgangsstyring-app`'s experimental schema, `ApiTilgangForMaskinbrukerV2.roller`, hits the `SplitTableField` arm.

## Design

Extend the path-element parser to accept `table:` alongside `condition:`. The author declares the target table inline; the builder stores the resolved `TableRef` on `ConditionJoin`; the emitters consume it the same way they consume `FkJoin.targetTable()`.

SDL:

```graphql
type Film @table(name: "film") {
  actor: [Actor!]
    @reference(path: [{table: "actor", condition: {className: "X", method: "join"}}])
}
```

The new shape is symmetric with the existing `table:`-only and `key:`-only arms in `BuildContext.parsePathElement` (`BuildContext.java:1289-1361`). It uses information already in the catalog (the named target table) and keeps the SDL the single source of truth for join shape.

### Alternatives considered

* **Reflect the condition method's second parameter.** Today's convention types the target parameter as `Table<?>`. Reflection-based resolution would require migrating every existing condition method to a specialised second-parameter type (e.g. `static Condition join(Table<?> src, Actor tgt)`) before the lift could ship. We'd also need to keep the wildcard form rejected with a migration message. The constraint is invisible at the SDL site — a reader can't tell from the schema which table the join lands on without opening the Java source. Rejected.
* **Hybrid (accept either).** Doubles the surface area; defers the design choice rather than making it. Rejected.

### `table:` is required when `condition:` is the only connector

Today's path-element parser accepts `condition:` as a sole connector. After this change, `condition:` without `key:` and without `table:` is a build-time author error: the parser cannot synthesise a `ConditionJoin` without knowing where it lands. Existing schemas that use `condition:` alone (none in the example or tests beyond fixtures asserting the deferred-rejection shape) get an `AUTHOR_ERROR` rejection pointing at the new convention.

`key:` + `condition:` continues to mean "FK-derived hop with extra WHERE predicate" via `FkJoin.whereFilter` — unchanged. `table:` + `condition:` is the new pure-`ConditionJoin` shape.

## Implementation

### 1. Model — `ConditionJoin` carries a target

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/JoinStep.java`

```java
record ConditionJoin(MethodRef condition, TableRef targetTable, String alias) implements JoinStep {
    public ConditionJoin {
        if (condition == null) throw new NullPointerException("ConditionJoin.condition must not be null");
        if (targetTable == null) throw new NullPointerException(
            "ConditionJoin.targetTable must not be null; path-step @condition requires sibling `table:` "
            + "to name the target — BuildContext.parsePathElement rejects the unresolvable shape upstream.");
    }
}
```

`ConditionJoin` does **not** implement `WithTarget` — that interface requires `slots()` / `slotCount()` for FK-correlation predicates which condition joins do not produce. Emitters that need the target table read it directly off the record. `WithTarget`'s call sites do not widen.

### 2. Path-element parser — accept `table:` + `condition:`

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`

In `parsePathElement` (around lines 1289-1361):

* When `condition:` is present **and** `table:` is present: resolve the table via the existing catalog lookup (the same code path the `table:`-only arm uses to validate target existence), resolve the condition method, and emit `new ConditionJoin(res.ref(), targetTable, alias)`. Do not require an FK between source and target.
* When `condition:` is present and `table:` is absent: surface an `AUTHOR_ERROR` rejection with the message *"path element with 'condition' requires a sibling 'table:' naming the target table — condition methods do not carry a target type"*. Existing tests that assert the deferred-rejection downstream become parse-time-rejected upstream.
* `key:` + `condition:` is unchanged (`FkJoin.whereFilter`).

`TableRef` resolution: factor out a `resolveTargetTable(String tableName)` helper from the existing `table:`-only arm so both arms call it. The arm's existing "table not found in catalog" rejection prose ports verbatim.

### 3. Alias emitter — drop the terminal-table fallback

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JoinPathEmitter.java`

`targetJavaClassName` (`JoinPathEmitter.java:56-66`) becomes:

```java
case JoinStep.ConditionJoin cj -> cj.targetTable().tableClass().simpleName();
```

The `terminalTable` parameter on `generateAliases` is no longer needed; callers that pass it (`InlineTableFieldEmitter.buildFkOnlyArm:66`) drop the argument. Remove the parameter from the signature so the alias path is uniform across all three step variants.

`hasConditionJoin` stays as-is (used by validators and by `InlineColumnReferenceFieldEmitter` to branch). After step 6 below, its only consumer is the inline-projection emitter, which still wants to know whether the path includes a condition step for join-loop dispatch. Document the narrowed role on the method.

### 4. Inline emitter — emit `.join(targetTable).on(method(src, tgt))` for `ConditionJoin` steps

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineTableFieldEmitter.java`

`buildFkOnlyArm` is renamed `buildArm` (no longer FK-only). The two loops:

* **Table-alias declaration** (`InlineTableFieldEmitter.java:77-83`): widen from `(JoinStep.FkJoin) path.get(i)` to a `switch (step)` over both `FkJoin` and `ConditionJoin`. Both expose `targetTable()` (a record method on `ConditionJoin`, a `WithTarget` method on `FkJoin`); both yield the same `Tables.X.as(parent.getName() + "_xN")` declaration shape. `LiftedHop` continues to be a build-time error in this loop because no current variant routes a lifted hop through the inline emitter (the existing implicit cast would also have failed).
* **JOIN chain** (`buildInnerSelect:117-122`): widen the loop to dispatch on step type. `FkJoin` keeps `.join(alias).onKey(fk.keysClass(), fk.constantName())`; `ConditionJoin` emits `.join(alias).on(<method>(prevAlias, alias))` via `JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), prevAlias, alias)`. Step 0's correlation against the parent is unchanged for `FkJoin` (uses `emitCorrelationWhere`); for `ConditionJoin` at step 0 the condition method serves both as the join predicate *and* the parent-correlation (called with `parentAlias` as src, terminalAlias-at-step-0 as tgt).

The single-cardinality `.limit(1)`, WHERE filters, ORDER BY, and pagination clauses are step-type-agnostic and need no change.

Inline-emitter stub branch (`InlineTableFieldEmitter.java:53-60`) deletes: `SplitRowsMethodEmitter.unsupportedReason` will no longer fire for `ConditionJoinReportable` variants once their validator arms drop.

### 5. Split-rows emitter — drop `unsupportedReason`

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

`unsupportedReason` (`SplitRowsMethodEmitter.java:324-335`) deletes outright once the four `ConditionJoinReportable` variants and the two inline variants no longer branch on it. The `Rejection.EmitBlockReason` enum values it produced (`TABLE_FIELD_CONDITION_JOIN_STEP`, `LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP`, `SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_TABLE_FIELD_CONDITION_JOIN_STEP`, `RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP`) delete with their last producers.

Each variant's `buildFor*` method removes its `unsupportedReason.isPresent()` early-return arm and falls through to the regular emit path. The split-rows JOIN chain follows the same widened dispatch as inline (step 4): `FkJoin` keeps `.onKey(...)`, `ConditionJoin` emits `.on(method(src, tgt))`.

### 6. Validator — drop deferred-rejection arms

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

* `validateColumnReferenceField` (`GraphitronSchemaValidator.java:575-583`) — drops the `hasConditionJoin` branch; the FK-only validator path now succeeds for paths containing `ConditionJoin` because step 2 above resolved the target table.
* The four `ConditionJoinReportable` validation arms (`validateSplitTableField` / `validateSplitLookupTableField` / `validateRecordTableField` / `validateRecordLookupTableField`) — drop the deferred-rejection consult of `SplitRowsMethodEmitter.unsupportedReason`.
* The two inline-variant arms added in R228 (`validateInlineTableField` / `validateInlineLookupTableField`) — drop their deferred-rejection branches symmetrically.

The classification-time guards in `FieldBuilder` and `ConditionResolver` are unaffected — they classify `ConditionJoin` correctly today; only emission was deferred.

### 7. Inline `ChildField.ColumnReferenceField` — widen the join-loop dispatch

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineColumnReferenceFieldEmitter.java`

The early-return at `InlineColumnReferenceFieldEmitter.java:59-65` (defensive arm pending R129) deletes. The join-loop widens with the same `switch (step)` shape as the inline-TableField emitter (step 4).

R129 (`column-reference-on-scalar-field-condition-join`) closes — the slug folds into R232's implementation per its own body's recommendation ("the natural home may be to fold this slug into the R3 implementation rather than a separate item"). The file deletes on Done; `changelog.md` records the R129 → R232 absorption.

### 8. Stale R3 references — cleanup pass

In the same set of commits as the implementation:

* `column-reference-on-scalar-field-condition-join.md` — file deletes per step 7.
* `lsp-diagnostic-redundant-splitquery-on-record.md:13` (R121) — drop the "R3 (`classification-vocabulary-followups`) lands the build-tier warning when …" sentence; R121's body keeps the SDL-edit-time motivation without the R3 attribution (the build-tier warning has shipped per changelog `162`).
* `nestingfield-multiparent-tablefield.md:56` — replace the dangling `plan-classification-vocabulary-followups.md §7` link with prose stating the convention directly. `nestingfield-multiparent-tablefield.md:134` — same.
* `fkjoin-alias-dead-storage.md:20` (R120) — keep the "originally item 5 of `classification-vocabulary-followups`" attribution; it is real historical provenance for an open Backlog item and the trail explains why the slug exists.

In source comments:

* `InlineTableFieldEmitter.java:29-31` javadoc — delete the "owned by classification-vocabulary item 5" sentence (no longer applicable).
* `JoinPathEmitter.java:60-66` and `:69-73` — delete the `ConditionJoin` empty-string arm and `hasConditionJoin` "G5 cannot yet emit" prose.
* `GraphitronSchemaValidator.java:579` — message and deferred-rejection slug delete with their arms.
* `JoinStep.java:200-202` — replace the "target table is not pre-resolved here — condition method resolution (P3) will provide it once reflection over the method signature is implemented" sentence with a description of the new `table:` requirement.

## Tests

### Pipeline tier — flip from "rejected" to "compiles"

The seven `*ValidationTest` cases that pin "stub surfaces as build error" with the "classification-vocabulary item 5" message flip to "no rejection, expected `JoinStep.ConditionJoin` in path with non-null `targetTable`":

* `TableFieldValidationTest.WITH_CONDITION_ONLY`, `LIST_WITH_CONDITION_ONLY`
* `LookupTableFieldValidationTest.WITH_CONDITION_ONLY`, `LIST_WITH_CONDITION_ONLY`
* `SplitTableFieldValidationTest` condition-join case
* `RecordTableFieldValidationTest` condition-join case
* `RecordLookupTableFieldValidationTest` condition-join case
* `ColumnReferenceFieldValidationTest` condition-join case

`R58TypedRejectionPipelineTest.inlineTableField_conditionJoinStep_rejectedAtBuildTime` and its sibling become `inlineTableField_conditionJoinStep_emitsCorrelatedSubquery` (or delete if the pipeline-tier coverage is now redundant with the per-variant cases). `conditionJoinReportable_implementedByExpectedSixVariants` keeps as-is — the seal-tracking assertion doesn't change; only the per-variant emission behaviour does.

### New pipeline-tier coverage — parser arm

`GraphitronSchemaBuilderTest` gains fixtures that exercise the new parser shapes:

* `CONDITION_WITH_TABLE_OK` — `@reference(path: [{table: "actor", condition: {…}}])` classifies as a `ConditionJoin` with a non-null `targetTable`.
* `CONDITION_WITHOUT_TABLE_REJECTED` — `@reference(path: [{condition: {…}}])` surfaces an `AUTHOR_ERROR` with the new "requires sibling 'table:'" prose.
* `KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER` — `key:` + `condition:` still produces an `FkJoin` with non-null `whereFilter` (regression guard for the unchanged arm).
* The existing `WITH_CONDITION_PATH` fixture (`GraphitronSchemaBuilderTest.java:1221-1236`) updates its SDL to add `table: "actor"` so it continues to classify rather than newly fail.

### Compilation tier

Add a fixture under `graphitron-fixtures-codegen` that exercises a `SplitTableField` with a `ConditionJoin` path step. The fixture compiles; the generated correlated subquery is byte-for-byte stable in the snapshot.

### Execution tier — Sakila

Pick one variant (suggest `SplitTableField` since that is the variant hit by the real-world `tilgangsstyring-app` example) and add a condition-join path through Sakila. Candidate: an `Actor.connectedFilms` field via a synthesised condition method on `FilmActor` that filters on something the natural FK does not capture (e.g. release year). The execution-tier test exercises the inner SELECT, the JOIN ON clause, and the rows-method call shape.

## Roadmap entries

* This item (R232) — moves to Done on landing.
* R129 (`column-reference-on-scalar-field-condition-join`) — file deletes; absorbed per step 7. `changelog.md` records the absorption.
* No new follow-ups expected; the lift is structurally complete.

## Diagnostics doc

`docs/manual/reference/diagnostics-glossary.adoc`'s six condition-join-step entries (`=== table-field-condition-join-step`, `=== lookup-table-field-condition-join-step`, and the four split/record variants) delete with their emit-block reason values per the `DiagnosticsDocCoverageTest` convention. If the user-manual chapter on `@reference` paths gains a "condition join" subsection, that is a separate edit; this item closes the deferred-rejection coordinates only.

## Acceptance signals

* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.
* `grep -r "classification-vocabulary item 5" graphitron-rewrite/` returns nothing.
* `grep -r "classification-vocabulary-followups" graphitron-rewrite/` returns at most the historical attribution in `fkjoin-alias-dead-storage.md`.
* No `plan-classification-vocabulary-followups.md` links remain in the tree.
* The `ApiTilgangForMaskinbrukerV2.roller` external example, with `table: "<target>"` added to its `@reference` path, classifies and emits.
