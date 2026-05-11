---
id: R42
title: "Stub: `@reference` on a scalar (FK column) field (`ColumnReferenceField`)"
status: Spec
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: `@reference` on a scalar (FK column) field (`ColumnReferenceField`)

Lift `ChildField.ColumnReferenceField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@reference` on a scalar field (mapping the field to a column on a joined target table) fail validation with `Deferred: ColumnReferenceField not yet implemented`. Carved out of R37 for independent prioritisation. The classifier side already produces the leaf with a resolved `joinPath` (`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase`); the gap is purely projection + fetcher emission.

The change slots into the existing `@reference`/`joinPath` infrastructure: same shape as `ChildField.TableField`'s inline correlated-subquery emission (`InlineTableFieldEmitter`), reduced to a single-column SELECT and wrapped with the `ChildField.ComputedField` fetcher wiring (`new ColumnFetcher<>(DSL.field("<name>"))`). No new abstractions, no reflection, no fixture method. Mirrors the R48 (`computed-field-with-reference`) lift in structure.

## Scope

In-scope variants (Direct compaction):

- `CallSiteCompaction.Direct` with `joinPath` containing only `JoinStep.FkJoin` steps (single-hop and multi-hop). This is the variant emitted by `@reference(table:)`/`@reference(key:)` on a scalar field, e.g. `Film.languageName: String @reference(table: "language")` projecting `language.NAME`.

Out of scope, deferred to follow-ups (no behavioural change in this item):

- `CallSiteCompaction.NodeIdEncodeKeys`: rooted-at-parent NodeId reference. The `FetcherEmitter` arm at lines 157–169 already emits a runtime stub keyed to the `nodeidreferencefield-join-projection-form` slug; the `TypeFetcherGenerator` arm is already a no-op (lines 399–404). Both remain unchanged. Lifting requires `$fields()`-side JOIN-with-projection machinery that does not exist on a `TableType` parent today and is independently owned by R44.
- `joinPath` containing any `JoinStep.ConditionJoin` step. `InlineTableFieldEmitter` already defers this for `TableField` (lines 53–60), pending classification-vocabulary item 5's target-table resolution for condition methods. `ColumnReferenceField` parallels that deferral: detection in the new emitter routes to a fresh `Rejection.Deferred` keyed to a new slug (a stub-shape follow-up; not blocking on R42 itself).

## Implementation

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineColumnReferenceFieldEmitter.java` (new).** Builds the switch-arm body for one `ChildField.ColumnReferenceField` in `TypeClassGenerator`'s `$fields` method. Mirrors `InlineTableFieldEmitter` with the inner SELECT collapsed to a single column:
  - `JoinPathEmitter.hasConditionJoin(f.joinPath())` ⇒ emit the deferred-stub arm (UnsupportedOperationException at runtime; new slug — see *Roadmap entries* below).
  - FK-only path: declare aliased target tables via `JoinPathEmitter.generateAliases` and the same alias-prefix-with-parent pattern (`InlineTableFieldEmitter` lines 78–84); assemble inner SELECT as `DSL.select(<terminalAlias>.<COLUMN>).from(<terminalAlias>).join(...).where(<step-0 correlation>).limit(1)`; project as `fields.add(DSL.field(<inner select>).as("<field-name>"))`.
  - Single-column expression wrap (`DSL.field(<subquery>)`), not `DSL.multiset(...)` — the result is a scalar.
  - WHERE-filter chaining and per-hop `JoinStep.FkJoin.whereFilter()` reuse `JoinPathEmitter.emitCorrelationWhere` and `JoinPathEmitter.emitTwoArgMethodCall` unchanged.

- **`TypeClassGenerator.java`.** Add a `List<ChildField.ColumnReferenceField> columnReferenceFields` parameter to `buildTypeSpec` and `build$FieldsMethod`, populate it in `generateForType` with the same filter-sort pattern used by the other leaves (lines 60–89), and append to the `flat` list passed to `emitSelectionSwitch` (lines 193–199). Add a new `case ChildField.ColumnReferenceField crf` arm in `emitSelectionSwitch` (alongside the `ComputedField` arm at lines 268–272) that calls `InlineColumnReferenceFieldEmitter.buildSwitchArmBody(crf, tableArg, sf, outputPackage)`. Gate the arm on `crf.compaction() instanceof CallSiteCompaction.Direct` so the `NodeIdEncodeKeys` variant continues to flow through the runtime stub.

- **`TypeFetcherGenerator.java`.**
  - Delete the `ChildField.ColumnReferenceField` entry from `STUBBED_VARIANTS` (lines 248–250).
  - Replace the case arm at lines 398–406 with: `case ChildField.ColumnReferenceField ignored -> { }` (projection-only, mirroring `TableField`/`LookupTableField`/`CompositeColumnField` at lines 411–413). The `NodeIdEncodeKeys` half stays a no-op via the same arm because the fetcher value comes from `FetcherEmitter`'s existing rooted-at-parent stub at lines 157–169.

- **`FetcherEmitter.java`.** Add a Direct-only arm before the existing `NodeIdEncodeKeys` arm at line 157, mirroring the `ComputedField` wiring at lines 136–142:
  ```java
  if (field instanceof ChildField.ColumnReferenceField crf
          && crf.compaction() instanceof CallSiteCompaction.Direct) {
      var columnFetcherClass = ClassName.get(outputPackage + ".util",
          ColumnFetcherClassGenerator.CLASS_NAME);
      return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
  }
  ```
  The `NodeIdEncodeKeys` arm at 157–169 stays as-is; the new arm above is narrower and runs first.

- **`GraphitronSchemaValidator.java`.** No change. `validateColumnReferenceField` (lines 447–457) already enforces the `joinPath` non-empty rule and walks the path via `validateReferencePath`; `validateVariantIsImplemented` (lines 155–168) automatically stops emitting the deferred error once the `STUBBED_VARIANTS` entry is removed.

## Tests

- **`ColumnReferenceFieldValidationTest.java`.** Flip the four enum cases:
  - `RESOLVED_IMPLICIT`, `RESOLVED_EXPLICIT`: drop the `stubbedError(...)` expectation; expect no errors.
  - `CONDITION_METHOD`: replace `stubbedError(...)` with the new `ConditionJoin`-deferred message (see *Roadmap entries*).
  - `MISSING_PATH`: drop the `stubbedError(...)` expectation but keep the `@reference path is required` expectation — that one is the per-field validator's structural check (line 451) and is unaffected.

- **`GeneratorCoverageTest.java`.** `everyGraphitronFieldLeafHasAKnownDispatchStatus` and `notImplementedReasonsContainsOnlyConcreteSealedLeaves` flip automatically once the `STUBBED_VARIANTS` entry is removed and the new projection arm exists; no manual edit required. If `IMPLEMENTED_LEAVES` is the membership oracle, add `ChildField.ColumnReferenceField.class` there (matching where `TableField`/`LookupTableField` sit — projection-only leaves are listed there per the doc comment at lines 145–151).

- **`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase`.** No change; classification is independent of emission.

- **`VariantCoverageTest`.** No change required. `ChildField.ColumnReferenceField.class` is not in the allowlist today; the existing `ColumnReferenceFieldCase` covers classification.

- **New unit test: `InlineColumnReferenceFieldEmitterTest.java`.** Mirrors the existing `InlineTableFieldEmitter` test shape (if one exists; otherwise add alongside): cover the FK-only single-hop, FK-only multi-hop, `@field(name:)`-overridden column, and the `ConditionJoin`-present deferred-stub paths. Each case asserts the emitted `CodeBlock` against a string snapshot.

- **New end-to-end approval test.** Add a `Film.languageName: String @reference(table: "language")`-style fixture to `graphitron-test/schema.graphqls` (or the rewrite-side equivalent currently exercising `@reference`), and a query under `graphitron-example/.../approval/queries/` that selects it. Run against PostgreSQL via `-Plocal-db` (same gate as `films_isEnglish_resolvesViaExternalFieldExpression` from R48).

## Roadmap entries

- This file: `status: Backlog` → `Spec` (this commit), then `Spec` → `Ready` on reviewer sign-off (separate session), then `Ready` → `In Progress` → `In Review` → `Done`. Regenerate `roadmap/README.md` via `mvn -pl :graphitron-roadmap-tool exec:java -q` (or `mise r roadmap`) in the same commit that flips status.
- **New stub follow-up (allocate in *In Progress* commit, not now): `column-reference-on-scalar-field-condition-join`** (bucket `stubs`, `depends-on: column-reference-on-scalar-field`). Parking ID for the `ConditionJoin`-in-path deferral. Mirrors how `TableField` defers `ConditionJoin` pending classification-vocabulary item 5. The new `Rejection.Deferred` slug emitted by `InlineColumnReferenceFieldEmitter` points here.
- **Cross-reference R44 (`nodeidreferencefield-join-projection-form`)**: when R44 ships the JOIN-with-projection machinery for rooted-at-parent NodeId references, the `NodeIdEncodeKeys` arm of `ColumnReferenceField` lifts as part of *that* item; R42 only handles `Direct` compaction.

## Open questions for the reviewer

1. **Correlated subquery vs. LEFT JOIN.** The Spec picks a correlated subquery (mirroring `InlineTableFieldEmitter`) because `$fields()` only assembles a `List<Field<?>>` — there is no FROM/JOIN tree to extend at projection time. A LEFT JOIN approach would require either propagating join state back to the fetcher or a `TableInterfaceType`-style query-level rewrite, both larger than what R42 should carry. Confirm this is the intended slot.
2. **Multi-hop performance.** For deep `joinPath` chains the subquery shape compounds; if downstream consumers commonly use 3+ hop scalar references, a LEFT JOIN lift may be worth scheduling. The known consumer cases at `opptak-subgraph` are single-hop. Defer the question to a perf follow-up if it surfaces.
3. **`IMPLEMENTED_LEAVES` membership convention.** `TableField`/`LookupTableField` are projection-only with no fetcher method but *do* have a `FetcherEmitter` value. `ColumnReferenceField` (Direct) matches that pattern. Confirm membership goes in `IMPLEMENTED_LEAVES` rather than a separate "projected-only" set.
