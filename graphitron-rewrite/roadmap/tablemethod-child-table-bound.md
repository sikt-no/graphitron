---
id: R43
title: "Stub: child `@tableMethod` with table-bound return (`TableMethodField`)"
status: Ready
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: child `@tableMethod` with table-bound return (`TableMethodField`)

Lift `ChildField.TableMethodField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@tableMethod` on a child field of a `@table`-bound parent fail the build with `[deferred] child @tableMethod (table-bound return) not yet implemented`, even though classification succeeds and the directive's reflection path already ships at the root site (`QueryField.QueryTableMethodTableField` in `IMPLEMENTED_LEAVES`).

This item was originally scoped as the carved-out scalar/enum-return form of `TableMethodField`. That carve-out is closed: `@tableMethod` returning a non-table type is rejected by `TableMethodDirectiveResolver` as a schema error (`"@tableMethod requires a @table-annotated return type"`) regardless of root vs child site, since the directive's whole purpose is to bind a developer-authored jOOQ table method (which by construction returns a generated jOOQ table class). What remains is the table-bound child case, the only shape the classifier produces for `TableMethodField` after the resolver narrows it.

R43 keeps its ID across this scope change rather than spinning off an `R<n+1>` because nothing shipped against the original scalar/enum scope: the item stayed in Backlog from allocation through the scope rewrite, no spec was authored, no fixture was built, no entry was retired from `STUBBED_VARIANTS`. The "IDs are never reused" rule in `workflow.adoc` protects post-`Done` provenance; a single in-place rewrite of a Backlog stub before any artefact references it is a strictly weaker case than the rule targets.

## Progress to date

Preparatory work landed on trunk (commits `a868125` + `46ed616`) and is recorded in `changelog.md` as the R43 scoping-change milestone:

- `TableMethodDirectiveResolver` rejects non-`TableBoundReturnType` at both root and child sites; the sealed `Resolved` collapses to `{TableBound, Rejected}` and the dead `NonTableBound` arms in `FieldBuilder` are gone.
- `ChildField.TableMethodField.returnType()` is narrowed to `ReturnTypeRef.TableBoundReturnType`. The `@LoadBearingClassifierCheck` key `tablemethod-resolver-return-is-table-bound` pins the producer/consumer pair (resolver → `ChildField.TableMethodField` + `QueryField.QueryTableMethodTableField`).
- Two `GraphitronSchemaBuilderTest` cases pin the rejection message at root and child sites; `TableMethodFieldValidationTest` fixtures use `TestFixtures.tableBoundFilm`.

What still ships under R43 is the actual lift. The In Review → Ready review flagged that the diff narrowed scope but did not retire the deferred entry: `TypeFetcherGenerator.STUBBED_VARIANTS` still maps `ChildField.TableMethodField.class`, and the dispatch in `generateChildFetcher` at `TypeFetcherGenerator.java:433` still routes to `stub(f)`.

## Plan

### Implementation

Two parent shapes are in scope. The invariant across both: the table the developer's method returns is the final target, so the last hop in the resolved path must equal `field.returnType().table()`.

**Table-bound parent (the existing `ChildField.TableMethodField`).**

- `TypeFetcherGenerator`: introduce `buildChildTableMethodFetcher(ChildField.TableMethodField, ...)` modelled on the root-site `buildQueryTableMethodFetcher` (`TypeFetcherGenerator.java:985`). The root emit is the closer cognate than the batched `ChildField.ServiceTableField` path: `TableMethodField` carries no `parentSourceKey` / `loaderRegistration` (cf. `ChildField.java:301-309`), so the emission is per-row, not a DataLoader-keyed batched call. The developer's static method is invoked the same way at child as at root, passed the return-type table alias (compare `TestTableMethodStub.getLanguage(Language table)`). The `@LoadBearingClassifierCheck`-pinned `field.returnType().table().tableClass()` lets the emitted local declare the specific generated jOOQ table class without a cast.
- The difference vs. root is the join: at child, the returned table has to be filtered by the parent row's key, and `field.joinPath()` is the resolved chain from the parent table to the returned table. The path is populated by `ctx.parsePath` (`FieldBuilder.java:3875`) from `@reference(path:)`. See the existing classification fixture `WITH_REFERENCE_PATH` in `GraphitronSchemaBuilderTest` for the populated shape.
- Replace the `STUBBED_VARIANTS` entry for `ChildField.TableMethodField.class` with an `IMPLEMENTED_LEAVES` entry. The dispatch case in `generateChildFetcher` (`builder.addMethod(stub(f))` at `TypeFetcherGenerator.java:433`) flips to call the new emit method.
- Join-path rule: parent-to-target join must be expressible. Two cases. (a) Exactly one FK exists between the parent table and the returned table: the classifier auto-infers that single hop, no `@reference` needed. (b) Zero or multiple candidate FKs: the schema author writes `@reference(path:)` explicitly. Reject any `@reference` whose final hop lands on a table other than `field.returnType().table()`. The auto-inference uses `JooqCatalog.findUniqueFkToTable(sourceTableSqlName, targetTableSqlName)` (the same helper `NodeIdLeafResolver` calls for single-hop FK auto-discovery at `NodeIdLeafResolver.java:495`); reuse it rather than open-coding a candidate-FK search.

**DTO / non-table-bound parent (new branch).**

- A `@record` or otherwise non-table-bound parent has no parent-table alias to join from, so the parent → target join needs a `SourceKey` lifting the batch key out of the parent DTO, exactly as in the existing `ChildField.RecordTableField` / `RecordLookupTableField` pattern (see `rewrite-design-principles.adoc` § "DTO-parent batching" for the `@sourceRow` + auto-derived contract).
- Two sub-cases mirror the table-parent shape:
  - (a) Parent backed by a jOOQ `TableRecord` and exactly one FK exists from the parent's table to the returned table: the classifier auto-derives the `SourceKey` (analogous to the `RecordTableField` FK-auto-derive arm at `FieldBuilder.java:3266` `deriveFkRecordParentSource`).
  - (b) Free-form DTO parent (no `TableRecord` backing) or ambiguous FK: the schema author supplies `@sourceRow(className:, method:)`, optionally composed with `@reference(path:)`; the classifier reflects the lifter and resolves the path the same way it does for `RecordTableField` today.
- Today's `TableMethodField` record carries no `SourceKey` / `LoaderRegistration` and is dispatched only from the table-parent branch in `FieldBuilder`. The DTO-parent branch needs a separate variant (recommended name `ChildField.RecordTableMethodField`, paralleling `RecordTableField` vs `TableField`) carrying `SourceKey`, `LoaderRegistration`, and the resolved `joinPath`. Emission follows the existing DTO-parent fetcher pattern (DataLoader-keyed batch) with the developer's static method call substituted for the direct table fetch.
- The new variant joins `STUBBED_VARIANTS` initially if R43 ships the table-parent branch first, so the deferred error remains coherent for the DTO case while it lands; if both branches ship together, both go straight to `IMPLEMENTED_LEAVES`.

### Tests

- Pipeline tier (primary, per `rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier"). Table-bound-parent: two `FetcherPipelineTest` cases asserting the generated `TypeSpec` contains the expected fetcher and dispatches to the developer's static table method, one for the explicit `@reference` path (mirror `WITH_REFERENCE_PATH`), one for the single-FK auto-inference path. DTO-parent: matching pair, one for the FK-auto-derive arm against a `TableRecord`-backed `@record` parent, one for the explicit `@sourceRow` (optionally + `@reference`) arm against a free-form DTO parent.
- Pipeline tier (rejection cases). Table-bound-parent: `GraphitronSchemaBuilderTest.UnclassifiedFieldCase` entries for (i) ambiguous FK without `@reference`, (ii) no FK + no `@reference`, (iii) `@reference` whose last hop lands on a table other than `field.returnType().table()`. DTO-parent: free-form parent without `@sourceRow` (paralleling the existing `RecordTableField` rejection at `FieldBuilder.java:3281`), and the same last-hop-target mismatch for `@reference`-composed paths.
- Compilation tier: covered automatically by the `graphitron-sakila-example` compile step, since the new fetcher must type-check against real jOOQ classes.
- Execution tier: a `graphitron-sakila-example` execution test against `rewrite_test` exercising the table-parent and DTO-parent branches end-to-end (one auto-inferred-FK + one explicit-path case per branch, with developer-authored static table-method class and lifter where applicable).
- Validation/structural: `TableMethodFieldValidationTest`'s three `stubbedError` cases (`NO_PATH`, `WITH_FK_PATH`, `WITH_CONDITION_ONLY`) all assert the deferred-error path. Once the lift lands they either flip to positive (no error) or are replaced by pipeline-tier emit assertions.

### Roadmap entries

- On landing the table-bound-parent branch, `STUBBED_VARIANTS` ends up shorter by one and `IMPLEMENTED_LEAVES` longer by one. If the DTO-parent branch lands in the same commit, the new variant goes straight to `IMPLEMENTED_LEAVES`; if split off, it lands in `STUBBED_VARIANTS` with a follow-up roadmap item and its own slug under the same `tablemethod-` family.
- `inference-axis-coverage.adoc` updates accordingly for whichever variants are no longer deferred.
- `changelog.md` gets a new entry for the lift commit referencing the preparatory milestone (`a868125` + `46ed616`) and whichever branches ship.

### Out of scope

- Re-opening the scalar/enum-return carve-out (closed by the structural rejection above).
- Any change to the root-site `QueryTableMethodTableField` shipping in `IMPLEMENTED_LEAVES`.

### Open questions

These need answers from the user before implementation starts. Captured here so the next session does not invent a position.

- *Method signature at child sites.* Root stubs take the return-type table as first param (`Language getLanguage(Language table)`). Is the same shape required at child, or does the child convention pass the parent table instead (or both)? The answer pins what `MethodRef.Param.Sourced` slots the resolver must allow.
- *`@reference(condition:)` arm.* `TableMethodFieldValidationTest.WITH_CONDITION_ONLY` uses a developer-supplied `Condition` instead of an FK. Stays in R43 (third branch alongside auto-inferred FK and explicit `@reference(path:)`), or deferred to a follow-up?
- *DTO-parent variant shape.* Recommended: a new `ChildField.RecordTableMethodField` variant (parallel to `RecordTableField` vs `TableField`), keeping the existing `TableMethodField` narrow to table-bound parents. Confirm the new-variant route over extending `TableMethodField` with `Optional<SourceKey>`.
- *Single-commit vs split.* Ship both parent shapes (table-bound and DTO) in one cycle, or land the table-bound branch first and split the DTO branch into a new roadmap item?

Not currently a blocker for any in-flight migration.
