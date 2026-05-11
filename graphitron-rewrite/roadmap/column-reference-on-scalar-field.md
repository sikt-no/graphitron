---
id: R42
title: "Stub: `@reference` on a scalar (FK column) field (`ColumnReferenceField`)"
status: Ready
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

Out of scope, lifted to validator-time rejections in this item (no emission):

- `CallSiteCompaction.NodeIdEncodeKeys` — rooted-at-parent NodeId reference. Lifting the emission requires `$fields()`-side JOIN-with-projection machinery that does not exist today; that's the scope of R24 (`nodeidreferencefield-join-projection-form`), which is explicitly waiting on a forcing-function schema before re-spec. Until then, R42's validator rejects this compaction with `Rejection.Deferred` keyed to the `nodeidreferencefield-join-projection-form` slug, replacing the existing `FetcherEmitter` runtime stub at `FetcherEmitter.java:157-169` for `ColumnReferenceField`. The `FetcherEmitter` stub itself stays in place (it's the path the validator says is unimplemented; defence-in-depth and parallel `CompositeColumnReferenceField` carrier still routes through it).
- `joinPath` containing any `JoinStep.ConditionJoin` step. Blocked on R3 (`classification-vocabulary-followups`) item 5 resolving the target table of a `@condition` method; the same upstream blocks `InlineTableFieldEmitter`'s ConditionJoin path at `InlineTableFieldEmitter.java:53-60`. R42's validator rejects this case with `Rejection.Deferred` keyed to the newly allocated R129 (`column-reference-on-scalar-field-condition-join`) stub slug. The follow-up item is allocated in *this* commit so the slug is named and reviewable up front.

Both rejections live in the validator, not the emitter — *Validator mirrors classifier invariants* (rewrite-design-principles `§ Validator mirrors classifier invariants`) makes build-time the right surfacing site for any classifier shape an emitter doesn't handle. The `InlineColumnReferenceFieldEmitter` therefore only needs to handle the lifted Direct + FK-only case; the unreachable arms (post-validate) become defensive `IllegalStateException`s rather than `Rejection.Deferred` producers.

## Implementation

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineColumnReferenceFieldEmitter.java` (new).** Builds the switch-arm body for one `ChildField.ColumnReferenceField` in `TypeClassGenerator`'s `$fields` method. Mirrors `InlineTableFieldEmitter` with the inner SELECT collapsed to a single column:
  - Entry assertion: `JoinPathEmitter.hasConditionJoin(f.joinPath())` and `f.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys` are both classifier-validated unreachable here (validator rejects both ahead of emission); guard with `throw new IllegalStateException(...)` rather than producing a runtime `Rejection.Deferred` — the load-bearing classifier guarantee is what makes the rest of the emitter's narrow shape correct (per *Classifier guarantees shape emitter assumptions*).
  - FK-only / `Direct` path: declare aliased target tables via `JoinPathEmitter.generateAliases` and the same alias-prefix-with-parent pattern (`InlineTableFieldEmitter` `lines 78-84`); assemble inner SELECT as `DSL.select(<terminalAlias>.<COLUMN>).from(<terminalAlias>).join(...).where(<step-0 correlation>).limit(1)`; project as `fields.add(DSL.field(<inner select>).as("<field-name>"))`.
  - Single-column expression wrap (`DSL.field(<subquery>)`), not `DSL.multiset(...)` — the result is a scalar.
  - WHERE-filter chaining and per-hop `JoinStep.FkJoin.whereFilter()` reuse `JoinPathEmitter.emitCorrelationWhere` and `JoinPathEmitter.emitTwoArgMethodCall` unchanged.

- **`TypeClassGenerator.java`.** Add a `List<ChildField.ColumnReferenceField> columnReferenceFields` parameter to `buildTypeSpec` and `build$FieldsMethod`, populate it in `generateForType` with the same filter-sort pattern used by the other leaves (around `lines 60-89`), and append to the `flat` list passed to `emitSelectionSwitch` (around `lines 193-199`). Add a new `case ChildField.ColumnReferenceField crf` arm in `emitSelectionSwitch` (alongside the `ComputedField` arm around `lines 268-272`) that calls `InlineColumnReferenceFieldEmitter.buildSwitchArmBody(crf, tableArg, sf, outputPackage)`. No compaction gate at the call site — the validator guarantees only `Direct` reaches projection; the emitter's entry assertion catches any classifier regression.

- **`TypeFetcherGenerator.java`.**
  - Delete the `ChildField.ColumnReferenceField` entry from `STUBBED_VARIANTS` (the `Map.entry(ChildField.ColumnReferenceField.class, deferredFor(...))` block).
  - Replace the case arm at `lines 398-406` with: `case ChildField.ColumnReferenceField ignored -> { }` (projection-only, mirroring `TableField`/`LookupTableField`/`CompositeColumnField` at `lines 411-413`). The `NodeIdEncodeKeys`-specific carve-out inside the existing arm becomes unreachable post-validator and is removed with the arm; the `FetcherEmitter`-side stub still exists for `CompositeColumnReferenceField`, which is unchanged by R42.
  - Add `ChildField.ColumnReferenceField.class` to **`PROJECTED_LEAVES`** (the set declared around `lines 212-214` alongside `ChildField.TableField.class` and `ChildField.LookupTableField.class`). Its `case … -> { }` arm and inline `$fields()` projection exactly match the `PROJECTED_LEAVES` contract ("no per-field fetcher method is generated"); it does not belong in `IMPLEMENTED_LEAVES`.

- **`FetcherEmitter.java`.** Add a Direct-only arm before the existing `NodeIdEncodeKeys` arm at line 157, mirroring the `ComputedField` wiring at `lines 136-142`:
  ```java
  if (field instanceof ChildField.ColumnReferenceField crf
          && crf.compaction() instanceof CallSiteCompaction.Direct) {
      var columnFetcherClass = ClassName.get(outputPackage + ".util",
          ColumnFetcherClassGenerator.CLASS_NAME);
      return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
  }
  ```
  The `NodeIdEncodeKeys` arm at `157-169` stays as-is (`CompositeColumnReferenceField` still routes through the same stub-shape arm at `170-179`); the new arm above is narrower and runs first.

- **`GraphitronSchemaValidator.java`.** Extend `validateColumnReferenceField` (currently at `lines 447-457`) to reject the two non-lifted shapes with `Rejection.Deferred`:
  - `field.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys` → `Rejection.deferred("ColumnReferenceField NodeIdEncodeKeys (rooted-at-parent NodeId reference) not yet implemented — requires JOIN-with-projection emission", "nodeidreferencefield-join-projection-form", ColumnReferenceField.class)`.
  - `JoinPathEmitter.hasConditionJoin(field.joinPath())` → `Rejection.deferred("ColumnReferenceField with @condition-method step in path not yet implemented — pending classification-vocabulary item 5", "column-reference-on-scalar-field-condition-join", ColumnReferenceField.class)`.
  - The existing `field.joinPath().isEmpty()` structural check stays; both deferred shapes run *after* the structural check (an empty path is reported as `@reference path is required`, not as a deferred shape).
  - `validateVariantIsImplemented` (`lines 155-168`) automatically stops emitting the blanket deferred error once the `STUBBED_VARIANTS` entry is removed; the per-shape `Rejection.Deferred`s above are the surfacing site after R42.

## Tests

- **`ColumnReferenceFieldValidationTest.java`.** Update the four enum cases to match the new validator shape:
  - `RESOLVED_IMPLICIT`, `RESOLVED_EXPLICIT` (`Direct` compaction, FK-only path): drop the `stubbedError(...)` expectation; expect no errors.
  - `CONDITION_METHOD` (`Direct` compaction, `ConditionJoin` in path): replace `stubbedError(...)` with the R129-slug deferred message produced by the new `validateColumnReferenceField` branch.
  - `MISSING_PATH` (empty `joinPath`): drop the `stubbedError(...)` expectation; keep the `@reference path is required` expectation (structural check at line 451 is unaffected).
  - Add a new `RESOLVED_NODEID_ENCODE` case (`NodeIdEncodeKeys` compaction, FK-only path): expects the R24-slug deferred message produced by the new branch.

- **`GeneratorCoverageTest.java`.** `everyGraphitronFieldLeafHasAKnownDispatchStatus` and `notImplementedReasonsContainsOnlyConcreteSealedLeaves` flip automatically once `ChildField.ColumnReferenceField` moves from `STUBBED_VARIANTS` to `PROJECTED_LEAVES`. No manual case-list edit required; the membership assertion is data-driven.

- **`GraphitronSchemaBuilderTest.ColumnReferenceFieldCase`.** No change; classification is independent of emission.

- **`VariantCoverageTest`.** No change required. The existing classification coverage in `ColumnReferenceFieldCase` carries forward; `VariantCoverageTest` does not gate emission.

- **New pipeline-tier cases in `FetcherPipelineTest.java`** (or a co-located new `ColumnReferenceFieldPipelineTest.java` if the section gets large enough to warrant its own class). Each case drives a small SDL through `GraphitronSchemaBuilder` and asserts on the generated `MethodSpec`/`TypeSpec` shape — no code-string body assertions (banned by *Pipeline tests are the primary behavioural tier*).
  - SDL fragment: `type Film @table(name: "film") { languageName: String @reference(path: [{key: "film_language_id_fkey", field: "name"}]) }` plus the required `Language` type and `Query.films`.
  - `directColumnReference_singleHop_fetchersClassHasNoMethod`: assert that the generated `FilmFetchers` class does **not** declare a `languageName` method (projection-only; matches `TableField`/`LookupTableField`'s behaviour as documented at `TypeFetcherGenerator.java:411-413`).
  - `directColumnReference_singleHop_fetcherValueIsColumnFetcher`: assert the `DataFetcher` value emitted for `Film.languageName` is a `ColumnFetcher` over `DSL.field("languageName")` (read off the registration code emitted by `FetcherEmitter`; structural assertion on the registration `MethodSpec`'s parameters, not on the body source).
  - `directColumnReference_multiHop_typeClassFieldsMethodIncludesLanguageName`: assert the generated `Film` class's `$fields` method exists and the switch over selected fields routes `languageName` (structural shape via `TypeSpecAssertions`, not body text).

- **Compilation-tier coverage** (`mvn compile -pl :graphitron-sakila-example -Plocal-db`). Add `languageName: String @reference(...)` to the existing Film fixture in `graphitron-sakila-example/.../schema.graphqls`. The compile step against real jOOQ catches any DSL type mismatch in the emitted correlated subquery without a hand-written assertion (per *Compilation against real jOOQ is a test tier*).

- **Execution-tier coverage**. Add a query under `graphitron-sakila-example/.../approval/queries/` that selects `Film.languageName` and asserts the resolved value (`"English"`, `"Italian"`, …). Runs against PostgreSQL via `-Plocal-db` — same gate as `films_isEnglish_resolvesViaExternalFieldExpression` from R48.

## Roadmap entries

- This file: `status: Backlog` → `Spec` (commit `25e8782` / trunk `8098924`) → `Spec` (this revise commit) → `Ready` on next-session reviewer sign-off → `Ready` → `In Progress` → `In Review` → `Done`. Regenerate `roadmap/README.md` via `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q` in the same commit that flips status.
- R129 (`column-reference-on-scalar-field-condition-join`, bucket `stubs`, allocated in this revise commit) holds the `ConditionJoin`-in-path deferral. Its body explains the upstream block is R3 (`classification-vocabulary-followups`) item 5, the same block that gates `InlineTableFieldEmitter`'s `ConditionJoin` arm. R129's natural disposition is to fold into R3 when R3 lifts; for now it serves as the named slug for R42's validator rejection.
- R24 (`nodeidreferencefield-join-projection-form`, `Backlog`, `priority: 13`) is the existing home for the `NodeIdEncodeKeys` lift on `ColumnReferenceField`/`CompositeColumnReferenceField`. R24's body explicitly says "Re-spec when a real schema reaches one of the shapes"; R42 does not pull that forward. R42's validator rejection uses R24's slug so when R24 re-specs the slug-to-item linkage is already in place.

## Notes (non-blocking)

- **Correlated subquery shape.** R42 emits the inner SELECT as a correlated subquery (`DSL.select(...).from(...).where(<correlation>).limit(1)` wrapped with `DSL.field(...)`), mirroring `InlineTableFieldEmitter`. A LEFT JOIN approach would require propagating join state out of `$fields()` (which today returns `List<Field<?>>` only), so the projection-time correlated subquery is the right slot for R42's footprint. R24's eventual JOIN-with-projection lift is the place where the `$fields()` API change happens.
- **Multi-hop performance.** For deep `joinPath` chains the correlated-subquery shape compounds. Known consumer cases at `opptak-subgraph` are single-hop; defer to a perf follow-up if 3+ hop scalar references show up in real schemas.
