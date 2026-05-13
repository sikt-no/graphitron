---
id: R43
title: "Stub: child `@tableMethod` with table-bound return (`TableMethodField`)"
status: In Progress
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
last-updated: 2026-05-13
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

**Commits 1–3 of the implementation plan have shipped** (changelog entries record each landing SHA):

- **Commit 1 (`1f70eff`) — Directive flattening + method-signature rewrite (root site).** `BuildContext.ARG_TABLE_METHOD_REF` retired; `@tableMethod` directive flattened in `directives.graphqls` to `(className, method, argMapping, contextArguments)`; `TableMethodDirectiveResolver.resolve` parses flat args inline; `ServiceCatalog.reflectTableMethod` gained a `TableSlotPolicy { REQUIRED, FORBIDDEN }` parameter so `@tableMethod` (FORBIDDEN) and `@condition` (REQUIRED) share the entry point without coupling; root emit (`buildQueryTableMethodFetcher`) drops the leading-table slot; all test fixtures + LSP overlay + user-manual reference docs migrated.
- **Commit 2 (`d172492`) — Path resolution + last-hop validation for `@tableMethod`.** `FieldBuilder.classifyChildFieldOnTableType`'s `@tableMethod` arm reorders the resolver and `parsePath` calls so the return-type table is known before path resolution; auto-FK inference fires for the no-`@reference` case; a structural check rejects explicit `@reference` paths whose last `FkJoin` hop lands on a table other than the return type's. Pipeline tests pin three accepted shapes (auto-FK, explicit FK, condition arm) and three rejections (ambiguous FK, missing FK, last-hop mismatch).
- **Sub-commit (post-commit-3) — FK-projection injection.** `TypeClassGenerator.collectRequiredProjectionColumns` (formerly `collectSourceKeyColumns`) extends to extract single-hop `FkJoin` source-side columns from `ChildField.TableMethodField`'s `joinPath` and inject them into the parent `$fields` SELECT regardless of SDL selection — analogous to the synthesis Split* fields already get for their `SourceKey` columns. Pipeline-tier `TableMethodFieldPipelineTest` pins the projection on both supported shapes (auto-FK + explicit `@reference` path) using `TypeSpecAssertions.appendsRequiredColumn`; execution-tier `GraphQLQueryTest` adds two cases that exercise the sakila fixtures end-to-end against PostgreSQL, closing the execution-tier gap commit 3 left open.
- **Commit 3 (`dcb6026`) — Child table-bound-parent lift.** `ChildField.TableMethodField` moved from `STUBBED_VARIANTS` to `IMPLEMENTED_LEAVES`; `TypeFetcherGenerator.buildChildTableMethodFetcher` emits a per-row fetcher with parent-row correlation via `buildTableMethodParentCorrelation` (single-hop `FkJoin` is the shipped emit shape; multi-hop / `ConditionJoin` paths stay classified but throw `UnsupportedOperationException` at runtime). `TableMethodFieldValidationTest`'s three `stubbedError` cases flipped to assert empty errors. New `TableMethodFieldPipelineTest` covers the auto-FK and explicit-`@reference` shapes. Sakila-example schema gained `Inventory.filmViaTableMethod` (auto-FK) and `Film.languageViaTableMethod` (explicit `@reference(path:)`) fixtures plus matching `SampleQueryService.tableMethodFilm` / `tableMethodLanguage` methods; the compile step type-checks the generated fetchers against real jOOQ classes.

### FK-projection sub-commit landed

The execution-tier gap on commit 3 is closed. `TypeClassGenerator.collectRequiredProjectionColumns` (renamed from `collectSourceKeyColumns`) now also walks `ChildField.TableMethodField` and projects the single-hop `FkJoin`'s source-side columns into the parent SELECT regardless of the user's SDL selection. `TableMethodFieldPipelineTest` gained two assertions (auto-FK on `Inventory.film_id`, explicit-path on `Film.language_id`) using the shared `TypeSpecAssertions.appendsRequiredColumn` helper. `GraphQLQueryTest` gained two execution-tier cases that exercise the sakila fixtures end-to-end against PostgreSQL: `inventoryById_filmViaTableMethod_correlatesParentRowViaInjectedFkProjection` (auto-FK arm, three inventory rows each correlating to their matching Film by `inventory.film_id`) and `filmById_languageViaTableMethod_correlatesParentRowViaExplicitReferencePathFk` (explicit single-hop `@reference` path arm, films correlating to `Language` via `film.language_id`).

### Remaining commits

- **Commit 4 — New variant `ChildField.RecordTableMethodField` (stubbed emit).** Add the record, slot into sealed permits, wear `@DependsOnClassifierCheck("tablemethod-resolver-return-is-table-bound")`. Classifier extends to the DTO-parent branch (FK-auto-derive + `@sourceRow` arms); emit stays stubbed: the new variant joins `STUBBED_VARIANTS` with its own deferred slug.
- **Commit 5 — DTO-parent emit.** Reuse the `RecordTableField` emit pattern (DataLoader-keyed batch) with the developer's static method call substituted for the direct table fetch. Move `RecordTableMethodField` to `IMPLEMENTED_LEAVES`; add DTO-parent pipeline + execution coverage.

## Plan

### Implementation

Two parent shapes are in scope. The invariant across both: the table the developer's method returns is the final target, so the last hop in the resolved path must equal `field.returnType().table()`.

#### Directive shape and method-signature contract (applies to both branches and to the root site)

The directive flattens to a two-argument form. Today's nested input object goes away:

```graphql
# before
language: Language @tableMethod(tableMethodReference: { className: "...", method: "..." })
# after
language: Language @tableMethod(className: "...", method: "...")
```

The developer's static method takes **no table parameter**: not the return-type table, not the parent table. The directive's whole point is that graphitron derives the target table; passing one in would let the developer hand graphitron a table that does not match the return type. Parent-table filtering is `@reference`'s job, not the method's. Parameters are sourced from field arguments only (composed with the existing `argMapping` rule and `contextArguments:` for context values).

Concrete shape:

```java
// schema:  films(rating: Rating): [Film] @tableMethod(className: "Methods", method: "filmsByRating")
public static Film filmsByRating(Rating rating) {
    return DSL.select().from(FILM).where(FILM.RATING.eq(rating)).asTable("filtered_film").as(FILM);
}
```

The method returns a derived `<Table>` parameterised by GraphQL args. Graphitron joins / filters it via `@reference` (table parent) or `SourceKey` (DTO parent) as described per-branch below.

Migration. The existing root-side test stubs `TestTableMethodStub.getLanguage(Language table)` / `getFilm(Film table)` / `getActor(Actor table)` no longer match the contract and are updated alongside the directive flattening. `parseExternalRef(DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF)` at `TableMethodDirectiveResolver.java:114` is rewritten against the flat `className:` / `method:` args; `ARG_TABLE_METHOD_REF` at `BuildContext.java:100` is retired. The root emit (`TypeFetcherGenerator.buildQueryTableMethodFetcher` at `:985`) drops the `tableExpression` argument it currently passes as the first call arg; `ArgCallEmitter.buildMethodBackedCallArgs` is updated to omit the leading-table slot for `@tableMethod`. All schema-builder fixtures using the nested form switch to the flat form. The user-manual page `docs/manual/reference/directives/tableMethod.adoc` updates to the new shape.

**Table-bound parent (the existing `ChildField.TableMethodField`).**

- `TypeFetcherGenerator`: introduce `buildChildTableMethodFetcher(ChildField.TableMethodField, ...)` modelled on the root-site `buildQueryTableMethodFetcher` (`TypeFetcherGenerator.java:985`). The root emit is the closer cognate than the batched `ChildField.ServiceTableField` path: `TableMethodField` carries no `parentSourceKey` / `loaderRegistration` (cf. `ChildField.java:301-309`), so the emission is per-row, not a DataLoader-keyed batched call. The developer's static method is invoked the same way at child as at root, with arguments sourced from field args only (no table parameter). The `@LoadBearingClassifierCheck`-pinned `field.returnType().table().tableClass()` lets the emitted local declare the specific generated jOOQ table class without a cast.
- The difference vs. root is the join: at child, the returned table has to be filtered by the parent row's key, and `field.joinPath()` is the resolved chain from the parent table to the returned table. The path is populated by `ctx.parsePath` (`FieldBuilder.java:3875`) from `@reference(path:)`. See the existing classification fixture `WITH_REFERENCE_PATH` in `GraphitronSchemaBuilderTest` for the populated shape.
- Replace the `STUBBED_VARIANTS` entry for `ChildField.TableMethodField.class` with an `IMPLEMENTED_LEAVES` entry. The dispatch case in `generateChildFetcher` (`builder.addMethod(stub(f))` at `TypeFetcherGenerator.java:433`) flips to call the new emit method.
- Join-path rule: the parent-to-target join is resolved by the existing `@reference` semantics, with `@tableMethod` taking the place of `Tables.LANGUAGE` (or whatever generated jOOQ table constant) as the source of the target table instance. Three accepted shapes (the same three `@reference` already supports): (a) no `@reference` and exactly one FK between the parent table and the returned table, the classifier auto-infers the single hop (`JooqCatalog.findUniqueFkToTable`, as `NodeIdLeafResolver.java:495`); (b) explicit `@reference(path: [{key: "..."}, ...])` for ambiguous or multi-hop FK chains; (c) explicit `@reference(path: [{condition: {className: "...", method: "..."}}])` with a developer-supplied `Condition` method, parameter and return types validated against the source / target tables per the existing `@reference` rules. Reject any resolved path whose final hop lands on a table other than `field.returnType().table()`.

**DTO / non-table-bound parent (new variant `ChildField.RecordTableMethodField`).**

- A `@record` or otherwise non-table-bound parent has no parent-table alias to join from, so the parent → target join needs a `SourceKey` lifting the batch key out of the parent DTO, exactly as in the existing `ChildField.RecordTableField` / `RecordLookupTableField` pattern (see `rewrite-design-principles.adoc` § "DTO-parent batching" for the `@sourceRow` + auto-derived contract).
- Two sub-cases mirror the table-parent shape:
  - (a) Parent backed by a jOOQ `TableRecord` and exactly one FK exists from the parent's table to the returned table: the classifier auto-derives the `SourceKey` (analogous to `FieldBuilder.deriveFkRecordParentSource` at `:3266`).
  - (b) Free-form DTO parent (no `TableRecord` backing) or ambiguous FK: the schema author supplies `@sourceRow(className:, method:)`, optionally composed with `@reference(path:)` (including the `condition:` arm); the classifier reflects the lifter and resolves the path the same way it does for `RecordTableField` today.
- A new variant `ChildField.RecordTableMethodField` carries `parentTypeName`, `name`, `location`, `ReturnTypeRef.TableBoundReturnType returnType`, `List<JoinStep> joinPath`, `MethodRef method`, `SourceKey sourceKey`, `LoaderRegistration loaderRegistration`, `Optional<ErrorChannel> errorChannel`. It joins the existing `TableTargetField`-adjacent permits in `ChildField.java` (model the placement on `RecordTableField`'s entry in the sealed lists). The existing `TableMethodField` stays narrow to table-bound parents. Both variants wear `@DependsOnClassifierCheck("tablemethod-resolver-return-is-table-bound")` (the existing key already covers the narrowed `TableBoundReturnType` component).
- Emission follows the existing DTO-parent fetcher pattern (DataLoader-keyed batch via `RecordTableField`'s emit) with the developer's static method call substituted for the direct table fetch.

### Tests

- Pipeline tier (primary, per `rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier"). Table-bound-parent: two `FetcherPipelineTest` cases asserting the generated `TypeSpec` contains the expected fetcher and dispatches to the developer's static table method, one for the explicit `@reference` path (mirror `WITH_REFERENCE_PATH`), one for the single-FK auto-inference path. DTO-parent: matching pair, one for the FK-auto-derive arm against a `TableRecord`-backed `@record` parent, one for the explicit `@sourceRow` (optionally + `@reference`) arm against a free-form DTO parent.
- Pipeline tier (rejection cases). Table-bound-parent: `GraphitronSchemaBuilderTest.UnclassifiedFieldCase` entries for (i) ambiguous FK without `@reference`, (ii) no FK + no `@reference`, (iii) `@reference` whose last hop lands on a table other than `field.returnType().table()`. DTO-parent: free-form parent without `@sourceRow` (paralleling the existing `RecordTableField` rejection at `FieldBuilder.java:3281`), and the same last-hop-target mismatch for `@reference`-composed paths.
- Compilation tier: covered automatically by the `graphitron-sakila-example` compile step, since the new fetcher must type-check against real jOOQ classes.
- Execution tier: a `graphitron-sakila-example` execution test against `rewrite_test` exercising the table-parent and DTO-parent branches end-to-end (one auto-inferred-FK + one explicit-path case per branch, with developer-authored static table-method class and lifter where applicable).
- Validation/structural: `TableMethodFieldValidationTest`'s three `stubbedError` cases (`NO_PATH`, `WITH_FK_PATH`, `WITH_CONDITION_ONLY`) all assert the deferred-error path. Once the lift lands they either flip to positive (no error) or are replaced by pipeline-tier emit assertions.

### Commit plan

Multi-commit; each commit is independently buildable and reviewable. R43 covers the full sequence.

1. **Directive flattening + method-signature rewrite (root site).** Retire `ARG_TABLE_METHOD_REF`; rewrite `TableMethodDirectiveResolver` parsing and `TestTableMethodStub` against the flat `className:` + `method:` shape; drop the leading-table slot from `buildMethodBackedCallArgs` and `buildQueryTableMethodFetcher`; migrate all `GraphitronSchemaBuilderTest` fixtures using the nested form; update `docs/manual/reference/directives/tableMethod.adoc`. No child-side work yet; root continues to ship in `IMPLEMENTED_LEAVES`.
2. **Path resolution + last-hop validation for `@tableMethod`.** Wire single-FK auto-inference (`JooqCatalog.findUniqueFkToTable`) and the last-hop-target check into the resolver / `parsePath` flow used by `@tableMethod`. Pipeline-test the three accepted shapes (auto-FK, explicit path, condition) and the rejections (ambiguous FK, missing FK + no `@reference`, last-hop-target mismatch). No emit change yet; the lift still routes to `stub(f)`.
3. **Child table-bound-parent lift.** Add `buildChildTableMethodFetcher`; move `ChildField.TableMethodField` from `STUBBED_VARIANTS` to `IMPLEMENTED_LEAVES`; flip the dispatch at `TypeFetcherGenerator.java:433`; flip the `stubbedError` assertions in `TableMethodFieldValidationTest`; add the pipeline + execution coverage described in **Tests**.
4. **New variant `ChildField.RecordTableMethodField`.** Add the record, slot it into the sealed permits, wear `@DependsOnClassifierCheck("tablemethod-resolver-return-is-table-bound")`. Classifier extends to the DTO-parent branch (FK-auto-derive + `@sourceRow` arms) but emit stays stubbed: the new variant joins `STUBBED_VARIANTS` with its own deferred slug.
5. **DTO-parent emit.** Reuse the `RecordTableField` emit pattern (DataLoader-keyed batch) with the developer's static method call substituted for the direct table fetch. Move `RecordTableMethodField` to `IMPLEMENTED_LEAVES`; add the DTO-parent pipeline + execution coverage.

Commits 1-3 may be revised into a single PR if review of the directive break is easier in context; 4 and 5 stay separate so the variant-add lands without the emit and the emit can be reviewed against the stable record shape. `inference-axis-coverage.adoc` updates with each membership change; `changelog.md` gets one entry per commit referencing both the preparatory milestone (`a868125` + `46ed616`) and the landing SHA.

### Out of scope

- Re-opening the scalar/enum-return carve-out (closed by the structural rejection above).
- Any change to the directive's *resolution* contract beyond the flattening (the existing strict expected-return-class invariant, Connection rejection at root, etc., all stand).

Not currently a blocker for any in-flight migration.
