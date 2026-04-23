# Rewrite Roadmap

Tracks remaining generator work. For the model taxonomy, see [Code Generation Triggers](../code-generation-triggers.md). For design principles, see [Rewrite Design Principles](../rewrite-design-principles.md).

---

## Active

| Item | Status | Plan |
|---|---|---|
| Platform-id as synthesized NodeId | Ready | [plan](legacy-platform-id.md) |
| Argument-resolution unification, Phase 4 | Ready | [plan](argument-resolution.md) |
| `BatchKey.ObjectBased` removal | Spec | [plan](plan-batchkey-remove-objectbased.md) |
| Service-backed and method-backed root fetchers | Spec | [plan](plan-service-root-fetchers.md) |
| `IdReferenceField` input filter variant | Spec | [plan](plan-id-reference-input-field.md) |
| Classification vocabulary follow-ups | Spec | [plan](plan-classification-vocabulary-followups.md) |
| KjerneJooqGenerator — emit NodeId metadata constants | Spec | [plan](plan-kjerne-jooq-generator.md) |
| Multi-parent NestingField sharing — `TableField` arm | Spec | [plan](plan-nestingfield-multiparent-tablefield.md) |
| Faceted search on `@asConnection` | Spec | [plan](plan-faceted-search.md), [spike](spike-faceted-search-sql.md) |
| Rewrite emitter + classifier hygiene sweep | Spec | [plan](plan-rewrite-hygiene-sweep.md) |
| Per-type `*Wiring` classes | Ready | [plan](plan-per-type-wiring-classes.md) |
| Consolidate rewrite modules under `graphitron-rewrite/` | Ready | [plan](plan-consolidate-rewrite-modules.md) |
| Docs as an index into classification tests | Ready (deferred) | [plan](plan-docs-as-index-into-tests.md) |

**Notes:** KjerneJooqGenerator is an external Sikt repo change (scratch-only here); unblocks Platform-id steps 2–6 at release time. Classification vocabulary follow-ups covers five independent cleanups — none is a release blocker. Docs-as-index is parked on steps 3–4 until the sealed hierarchy stabilises (Active work and Stubs still in motion); steps 1–2 shipped.

---

## Backlog

Pick an item, draft a plan, move to Active.

### Production impact snapshot (2026-04-22)

Rewrite rejections observed in production, ranked by distinct-occurrence count. The roadmap item in the right column is what closes that rejection; where multiple counts map to the same item, the top-ranked row is authoritative for prioritization. Schema-author errors (bad `@lookupKey`, unresolvable columns, etc.) are listed separately at the bottom — no generator work closes them; they are a diagnostics-UX signal only.

| Count | Rejection | Closes via |
|---:|---|---|
| 45 | Mutation update | Stubs #4 |
| 44 | `MutationServiceRecordField` | Stubs #4 |
| 41 | `ColumnReferenceField` | Stubs #8 |
| 32 | `RecordTableField` / `RecordLookupTableField` missing FK path + typed backing class | Active: *`BatchKey.ObjectBased` removal* (+ *`BatchKey` lifter directive* for DTO parents) |
| 23 | Mutation delete | Stubs #4 |
| 21 | `ComputedField` | Stubs #8 |
| 20 | `QueryTableInterfaceField` | Stubs #3 |
| 19 | Mutation insert | Stubs #4 |
| 16 | `@splitQuery` with condition-join step | Active: *Classification vocabulary follow-ups* §5 |
| 12 | `SplitTableField` under `NestingField` | Priority #1: *`SplitTableField` under `NestingField`* |
| 3 | Nested type shared across parents with `TableField` | Active: *Multi-parent NestingField sharing — `TableField` arm* |
| 1 | `QueryInterfaceField` | Stubs #3 |
| 0 | `QueryServiceRecordField`, `QueryNodeField`, `QueryEntityField`, `@asConnection` on inline `TableField`, service-method unrecognized-sources param, `key does not connect`, `_Service` return type, and other nil-count stubs | various (no consumer pressure today) |

**By area aggregate (close-with-one-plan totals):** Mutation bodies 131 (Stubs #4) · non-table / scalar child leaves 62 (Stubs #8) · interface / union 21 (Stubs #3) · remaining split-query pain 28 (Active §5 + Priority #1 together).

**Schema-author errors (diagnostics UX, not generator gaps):** `@lookupKey` with no resolved argument 32 · `@condition` parameter unresolvable 7 · service method reference incomplete 4 · no FK between tables 2 · type mapped to `@table` has unresolvable fields 2 · column not in jOOQ table (typo-suggest) 1 · argument's column unresolvable 1.

### Priority

Backlog items ranked by production impact first (see snapshot above), then by architectural / structural concerns. Items that have been promoted past Backlog live in the Active table at the top of this doc. Numbered prefix `#1` ties the row to the snapshot.

- **#1 (prod: 12) — `SplitTableField` under `NestingField`** **[Done]** — lift the `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES` gate for `SplitTableField` / `SplitLookupTableField`; emit a nested-type `<NestedTypeName>Fetchers` class carrying the DataLoader-backed rows method; thread the class name through `GraphitronWiringClassGenerator` so the wiring's `$L::$L` fallback reaches it; walk `NestingField.nestedFields()` when collecting `$fields` BatchKey columns so nested Split leaves project the outer parent's PK into the SELECT ([plan-splittablefield-nestingfield.md](plan-splittablefield-nestingfield.md)).

Architecture / structural (no direct production-count attribution; ordered by rough dependency):

- **Dissolve `graphitron-schema-transform` module** **[Backlog]** — fold the transform pipeline into `graphitron-rewrite` so every schema pass has a single code-owner; retire the standalone module. Trigger: the faceted-search plan would otherwise split facet synthesis (schema-transform) and facet classification + emission (rewrite) across two modules, reintroducing the "two places must agree on `{Scalar}FacetValue` naming" class of problem this umbrella exists to prevent. Sub-items below are independently shippable in rough dependency order; the existing "Drop `graphitron-common` build dependency from `graphitron-rewrite`" (Cleanup) item and the "Apollo Federation via federation-jvm transform" item above both land as part of this effort. LOC figures are schema-transform source only, excluding tests.
  - **Rewrite owns schema loading + directive auto-injection** — inline `SchemaReader` equivalent, pulling `directives.graphqls` from rewrite's own resources (~80 LOC). Prerequisite for every item below; unblocks dropping the `graphitron-common` dep.
  - **Rewrite owns type-extension merging** — migrate `MergeExtensions` as a registry-level pre-pass (~65 LOC).
  - **Rewrite owns `@asConnection` → Connection synthesis** — migrate `MakeConnections` as a registry-level pre-pass (~365 LOC); unblocks facet synthesis running in-module.
  - **Rewrite owns `@notGenerated` element removal** — migrate `ElementRemovalFilter` including reachability re-scan (~150 LOC).
  - **Rewrite owns directive stripping in the emitted client SDL** — migrate `DirectivesFilter`; consolidate `GenerationDirective` into a rewrite-internal list (~50 LOC). Kills the cross-module enum-sync step `@facet` and every future generator directive would otherwise need.
  - **Rewrite emits the client SDL as generated output** — write `schema.graphql` (and any `<outputSchemas>` variants) under `target/generated-sources/...` alongside the generated Java, packaged as a classpath resource for `Graphitron.getTypeRegistry()`. Kills the "is the shipped client schema in sync with what fetchers were compiled against?" class of bug since both fall out of the same generator run.
  - **Rewrite owns feature-flag SDL splits** — migrate `FeatureConfiguration` + `SchemaFeatureFilter` + `splitFeatures` + the Mojo's `<outputSchemas>` plumbing (~500 LOC; the biggest item in the umbrella).
  - **Rewrite owns federation SDL integration** — migrate `Federation.transform` + `KeyFilter` + `reloadSchema`; bundled with the "Apollo Federation via federation-jvm transform" item above.
  - **Retire `graphitron-schema-transform` + `TransformMojo` + `SchemaTransformRunner`** — landing marker once the above have shipped and no non-rewrite consumer remains (confirm the legacy generator path at retirement time).
- **`BatchKey` lifter directive** **[Backlog]** — mechanism for schema authors to supply a DTO→key conversion, enabling DataLoader batching on DTO parents; feeds the existing column-keyed path once `BatchKey.ObjectBased` removal lands. (Co-closes the 32-count `RecordTableField` / `RecordLookupTableField` missing-FK-path rejection for DTO parents.)
- **Decompose `FieldBuilder`** **[Backlog]** — split 1,750-line builder along field taxonomy; blocked on Argument-resolution unification. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` + shared argument-classification module.
- **Extract semantic-check helpers from `classifyQueryField`** **[Backlog]** — the codebase rejects malformed fields at classifier time by returning `UnclassifiedField` (polymorphic `@service` at `FieldBuilder.java:1305-1306`; single-cardinality `@splitQuery @lookupKey` and multi-hop single-cardinality `@splitQuery` per `plan-single-cardinality-split-query.md` §1b/§1c; Connection / Sourced-param rejection on `@service` / `@tableMethod` per `plan-service-root-fetchers.md` §Classifier additions). The pattern is consistent and better than validator-time rejection for the "emitter sees only well-formed leaves" property, but it means `classifyQueryField` accumulates semantic checks alongside shape dispatch. Refactor: extract per-directive helpers like `rejectInvalidService(fieldDef, svcResult) → Optional<UnclassifiedField>` and `rejectInvalidTableMethod(fieldDef, tb) → Optional<UnclassifiedField>`, so each classifier arm reads as "run semantic gates, then dispatch to the leaf". Orthogonal to "Decompose `FieldBuilder`" above — that splits by field taxonomy; this refactors within each arm. Not urgent; do it when a new rejection would push the file past a readability threshold.
- **Composite-key `@lookupKey` on list-of-input-object arguments** **[Backlog]** — add `ArgumentRef.CompositeLookupArg` carrying `(input-field-name, target-column)` pairs resolved from `@field(name:)` directives; `buildInputRowsMethod` already handles arbitrary-arity VALUES + JOIN.
- **Apollo Federation via federation-jvm transform** **[Backlog]** — replace `QueryEntityField` stub with a `GraphitronSchemaBuilder` post-step wrapping the Graphitron schema via `Federation.transform`; deletes the stub after migration.
- **`DSLContext` on `@condition` / `@tableMethod` methods** **[Backlog]** — lift `reflectTableMethod` gate; requires `ArgCallEmitter` to walk `params()` instead of `callParams()` so the injected DSLContext lands at its declaration-index slot.
- **`Set<T>` parent-keys on `@service` methods** **[Backlog]** — decide: require `List<T>` (predictable batching order, current direction) or broaden `BatchKey`; one known offender (`navnAlleSprak`).
- **Rebalance test pyramid** **[Backlog]** — shift new test investment from per-variant structural tests toward SDL→classification→emission pipeline tests keyed off `graphitron-rewrite-test-fixtures`.
- **Audit custom pagination-arg-name support** **[Backlog]** — decide: remove `PaginationSpec` plumbing for non-default `first`/`after` names (likely dead code) or document and add an execution fixture.
- **Clarify `FkJoin` direction semantics** **[Backlog]** — `JoinStep.FkJoin.sourceTable` is written to the traversal-origin table in `BuildContext.synthesizeFkJoin:473` and `parsePathElement:559-560`, contradicting the docstring at `JoinStep.java:70-72` (which claims it resolves to the FK-holder table). Currently dead data — zero readers today — but was a bug magnet for the first candidate reader (see `plan-single-cardinality-split-query.md` §1a). Options: fix construction to match the docstring (low risk, field unread); rename to `originTable` and add a derived `fkOnSource()` / `parentHoldsFk()` helper; or remove the raw field altogether since no reader needs it. Add a construction-time invariant check whichever direction wins.

### Generator stubs

Enumerated from `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Priority numbers `#3`–`#4` are referenced by emitted reason strings and must stay stable. Aggregate production counts from the snapshot are listed where applicable; ordering within this section should follow those counts (highest-impact first) once an item is promoted to Active.

3. **Interface / union fetchers** (prod: 21 — `QueryTableInterfaceField` 20 + `QueryInterfaceField` 1) — `QueryField.QueryInterfaceField`, `QueryTableInterfaceField`, `QueryUnionField`, `ChildField.InterfaceField`, `UnionField`, `TableInterfaceField`.
4. **Mutation bodies** (prod: 131 aggregate — update 45, `MutationServiceRecordField` 44, delete 23, insert 19) — `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`.
5. **Apollo Federation `_entities` resolver** (prod: 0) — `QueryField.QueryEntityField`; superseded by "Apollo Federation via federation-jvm transform" in Priority above.
6. **Relay `Query.node` resolver** (prod: 0) — `QueryField.QueryNodeField`; blocked on Platform-id as synthesized NodeId (Active).
7. **Service-backed and method-backed root fetchers** **[Tracked]** (prod: 0) — `QueryServiceTableField`, `QueryServiceRecordField`, `QueryTableMethodTableField`. Plan: [plan-service-root-fetchers.md](plan-service-root-fetchers.md).
8. **Non-table / scalar / reference child leaves** (prod: 62 — `ColumnReferenceField` 41 + `ComputedField` 21) — `ChildField.ColumnReferenceField`, `NodeIdReferenceField` (blocked on Platform-id), `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField`.

### Cleanup

- **Unify `rowsMethodName()`** **[Backlog]** — lift `"rows" + capitalize(name())` copy-paste from four `BatchKeyField` leaves to a default method on the interface.
- **Unify `FkJoin` construction in `parsePathElement`** **[Backlog]** — `{key:}` branch at `BuildContext.java:557-564` hand-builds `FkJoin`; delegate to `synthesizeFkJoin` for the source-validated success path, keeping the null-source fallback and connectivity-error arms bespoke.
- **Collapse `TableTargetField` structural redundancy** **[Backlog]** — six `Table*Field` variants share identical components; evaluate sealed intermediates (`StandardTableField`, `RecordBoundField`).
- **Shared interface for `QueryField` / `ChildField` table-bound parallels** **[Backlog]** — root variants drop `joinPath` but share `filters · orderBy · pagination`.
- **`JoinConditionRef` wrapper** **[Backlog]** — distinguish `ConditionJoin`/`FkJoin` calling convention from `ConditionFilter` at the type level.
- **Paginated-fields transform coexistence** **[Backlog]** — document or wire `defaultPageSize` loss when `@asConnection` strip precedes the builder.
- **Selection parser audit** **[Backlog]** — `selection/` hand-rolls ~500 LOC; audit whether re-parsing is needed given what graphql-java already provides.
- **`GraphitronContext` extension-point docs** **[Backlog]** — document what belongs in `GraphitronContext` vs jOOQ `ExecuteListener` vs schema directive.
- **Drop `graphitron-common` build dependency from `graphitron-rewrite`** **[Backlog]** — inline `MultiSourceReader` + auto-inject `directives.graphqls`; emitted code runtime dependency unchanged.

---

## Done

- `89dfea8` — `DSLContext` params on `@service` methods: `ServiceCatalog.reflectServiceMethod` classifies `org.jooq.DSLContext` parameters as `ParamSource.DslContext`; four `ServiceCatalogTest` cases + one `GraphitronSchemaBuilderTest` pipeline case. `reflectTableMethod` intentionally unchanged — tracked as backlog.
- `3357928` — Sealed-switch dispatch: `TypeFetcherGenerator.generateTypeSpec` exhaustive over all `GraphitronField` leaves; stubbed leaves via `NOT_IMPLEMENTED_REASONS`.
- `15f9f61e` — Variant-coverage Phase 1: `IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` partition invariant enforced by `GeneratorCoverageTest`.
- `1e48c4ee` — Argument-resolution Phase 1: VALUES + JOIN lookup emission for `QueryLookupTableField`.
- G5 — Inline `TableField` emission: `TypeClassGenerator.$fields` via `DSL.multiset`; seven execution tests.
- `aaadb78b` — Argument-resolution Phase 2a: inline `LookupTableField` via `InlineLookupTableFieldEmitter`; six execution tests.
- `7417f53` — Body-substring test rewrite: `TypeSpecAssertions` helper; 28 → 3 intentionally-marked body-assertion sites.
- `34359b4` — Argument-resolution Phase 2b: rows-method bodies for `SplitTableField` + `SplitLookupTableField`; exact JDBC round-trip counts asserted.
- Record-fields Phase 1: `ResultType` parents; `PropertyField`, `RecordField`, `ConstructorField`, `RecordTableField` with execution tests.
- Record-fields Phase 2: `RecordLookupTableField` via `deriveBatchKeyForResultType`; five execution tests.
- `9ba498bc` + `7cf568f4` — Stubbed-variant validator: `validateVariantIsImplemented` reads `NOT_IMPLEMENTED_REASONS`; build fails on rewrite validation errors by default.
- `@table` + `@record` input-type fix: `@record` dominates on input types; introduces `BuildContext.warnings()` channel.
- `d33ace9` — Variant-coverage Phase 2: `ClassificationCase` interface; 26 enums retrofitted with `variants()` sets.
- Java-17 output ratchet: `graphitron-rewrite-test-spec` compile goal pinned to `release=17`.
- `0b2e4e9` + `49d7879` — Nesting-field emission: `ChildField.NestingField` out of stubs; eight execution tests.
- `1abc31ed` + `0c449fef` + `a3afd651` — Implicit `@reference` path inference: `BuildContext.parsePath` synthesizes single-hop `FkJoin` from the jOOQ catalog when `@reference` is absent; deletes four `SplitRowsMethodEmitter` EMPTY_PATH stub branches and the duplicate FK-count logic in `GraphitronSchemaValidator`.
- `2530b93` + `f8df839` + `a063d3e` + `ef89bfb` + `1900453` — Generated-fetcher quality pass: `ConnectionHelper.pageRequest` + emitted `PageRequest` carrier own the full pagination dance (first/last guard, backward/pageSize/cursor derivation, cursor decode, reverse ordering, selection ∪ extraFields name-dedup), with `reverseOrderBy` lifted from per-`*Fetchers`-class to one shared copy; `QueryConditionsGenerator` extracts env-aware condition orchestration into a parallel generated class so entity `*Conditions` stay pure; `$T` substitution replaces every `var`-emitting site in the generator; table-local rename from `table` → `<entity>Table` with `srcAlias` threaded through `ArgCallEmitter` + all `buildCallArgs` callers, breaking the mapper/table name collision; `FieldWrapper.DEFAULT_PAGE_SIZE` unifies four fallback sites; `seekFields: Field<?>[]` matches `decodeCursor`'s declared return type; `ConnectionResult` gains a 2-arg delegating constructor. Three emitted-source lint ratchets (`GeneratedSourcesLintTest`): no `var`, no full-package jOOQ qualification in fetcher bodies, no `graphql.*` imports in entity `*Conditions`. ([plan](plan-generated-fetcher-quality.md))
- `86ff568` + `3246fd7` + `75e6340` — Single-cardinality `@splitQuery` support: `FieldBuilder.deriveSplitQueryBatchKey` picks FK-column `BatchKey` for single cardinality / parent-PK `BatchKey` for list (cardinality is the direction signal); classifier rejects `@splitQuery @lookupKey` at single and multi-hop single at classifier time; `SplitRowsMethodEmitter.buildSingleMethod` emits a flat terminal-JOIN returning `List<Record>` with a `scatterSingleByIdx` scatter; `TypeClassGenerator.$fields` always appends each Split* child's BatchKey columns (deduped at runtime); `TypeFetcherGenerator` threads a null-FK short-circuit (single-cardinality fetchers extract the FK to a typed local and return `CompletableFuture.completedFuture(null)` before DataLoader dispatch); scatter-helper emission gated so `scatterByIdx` / `scatterSingleByIdx` are emitted only when the class actually uses them. `JoinStep.FkJoin` docstring corrected to describe `sourceTable` as the traversal-origin table. Coverage: 4 new `GraphitronSchemaBuilderTest` cases (positive + negative §1b / §1c), `ScatterSingleByIdxTest` (reflective unit), 3 pipeline tests in `SplitTableFieldPipelineTest`, 5 execution tests in `GraphQLQueryTest` covering shared-FK dedup (2 round-trips for 5 customers), null-FK short-circuit, non-null-FK resolution, and scatter alignment across mixed-null batches. Closes the 280-count production rejection. ([plan](plan-single-cardinality-split-query.md))
- `3821842` + `62b51c3` + `76887cf` + `c40afb4` — Lift `@asConnection` rejection on `@splitQuery` fields: `SplitRowsMethodEmitter.buildConnectionMethod` emits the `ROW_NUMBER() OVER (PARTITION BY fk ORDER BY …)` envelope over a `parentInput` VALUES + FK-chain aliased subquery, filtered on outer `__rn__` range, so per-parent Relay pagination works inside DataLoader batches; §2 lifts the fixed-ordering restriction by parameterizing `TypeFetcherGenerator.buildOrderByHelperMethod` on the aliased `Table` so root (`filmTable`) and Split (`a1`) call sites share one helper shape; helper-emission gate adds `SplitTableField+Connection+Argument` alongside the root-field case; classifier permanently rejects `@asConnection` + `@lookupKey` at `FieldBuilder.java:252-257` / `:266-271` (composite lookup keys disambiguate batches, but cursor pagination requires lockstep batches). `ConnectionResult` storage narrowed from `Result<Record>` to `List<Record>`. Coverage: classifier/pipeline/execution tiers all green (545 rewrite + 94 test-spec). Closes the 68-count production rejection; plan file deleted on reviewer sign-off per the plan's own instruction.
