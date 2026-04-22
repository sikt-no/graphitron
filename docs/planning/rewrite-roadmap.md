# Rewrite Roadmap

Tracks remaining generator work. For the model taxonomy, see [Code Generation Triggers](../code-generation-triggers.md). For design principles, see [Rewrite Design Principles](../rewrite-design-principles.md).

---

## Active

| Item | Status | Plan |
|---|---|---|
| Platform-id as synthesized NodeId | In Progress | [plan](legacy-platform-id.md) |
| Argument-resolution unification | In Review | [plan](argument-resolution.md) |
| `BatchKey.ObjectBased` removal | Spec | [plan](plan-batchkey-remove-objectbased.md) |
| Service-backed and method-backed root fetchers | Spec | [plan](plan-service-root-fetchers.md) |
| `IdReferenceField` input filter variant | Spec | [plan](plan-id-reference-input-field.md) |
| Single-cardinality `@splitQuery` support | In Progress | [plan](plan-single-cardinality-split-query.md) |
| Classification vocabulary follow-ups | Spec | [plan](plan-classification-vocabulary-followups.md) |
| KjerneJooqGenerator — emit NodeId metadata constants | Spec | [plan](plan-kjerne-jooq-generator.md) |
| Multi-parent NestingField sharing — `TableField` arm | Spec | [plan](plan-nestingfield-multiparent-tablefield.md) |
| Faceted search on `@asConnection` | Spec | [plan](plan-faceted-search.md), [spike](spike-faceted-search-sql.md) |

**Notes:** KjerneJooqGenerator is an external Sikt repo change (scratch-only here); unblocks Platform-id steps 2–6 at release time. Classification vocabulary follow-ups covers five independent cleanups — none is a release blocker.

---

## Backlog

Pick an item, draft a plan, move to Active.

### Priority

Production parity gaps and architecture blockers, in rough order.

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
- **`BatchKey` lifter directive** **[Backlog]** — mechanism for schema authors to supply a DTO→key conversion, enabling DataLoader batching on DTO parents; feeds the existing column-keyed path once `BatchKey.ObjectBased` removal lands.
- **Decompose `FieldBuilder`** **[Backlog]** — split 1,750-line builder along field taxonomy; blocked on Argument-resolution unification. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` + shared argument-classification module.
- **Lift `@asConnection` rejection on `@splitQuery` fields** **[Backlog]** — emit `ROW_NUMBER() OVER (PARTITION BY fk)` envelope to support per-parent Relay pagination inside DataLoader batches; scope: `SplitTableField` and `SplitLookupTableField`.
- **Composite-key `@lookupKey` on list-of-input-object arguments** **[Backlog]** — add `ArgumentRef.CompositeLookupArg` carrying `(input-field-name, target-column)` pairs resolved from `@field(name:)` directives; `buildInputRowsMethod` already handles arbitrary-arity VALUES + JOIN.
- **Apollo Federation via federation-jvm transform** **[Backlog]** — replace `QueryEntityField` stub with a `GraphitronSchemaBuilder` post-step wrapping the Graphitron schema via `Federation.transform`; deletes the stub after migration.
- **`DSLContext` on `@condition` / `@tableMethod` methods** **[Backlog]** — lift `reflectTableMethod` gate; requires `ArgCallEmitter` to walk `params()` instead of `callParams()` so the injected DSLContext lands at its declaration-index slot.
- **`Set<T>` parent-keys on `@service` methods** **[Backlog]** — decide: require `List<T>` (predictable batching order, current direction) or broaden `BatchKey`; one known offender (`navnAlleSprak`).
- **Rebalance test pyramid** **[Backlog]** — shift new test investment from per-variant structural tests toward SDL→classification→emission pipeline tests keyed off `graphitron-rewrite-test-fixtures`.
- **Audit custom pagination-arg-name support** **[Backlog]** — decide: remove `PaginationSpec` plumbing for non-default `first`/`after` names (likely dead code) or document and add an execution fixture.
- **Clarify `FkJoin` direction semantics** **[Backlog]** — `JoinStep.FkJoin.sourceTable` is written to the traversal-origin table in `BuildContext.synthesizeFkJoin:473` and `parsePathElement:559-560`, contradicting the docstring at `JoinStep.java:70-72` (which claims it resolves to the FK-holder table). Currently dead data — zero readers today — but was a bug magnet for the first candidate reader (see `plan-single-cardinality-split-query.md` §1a). Options: fix construction to match the docstring (low risk, field unread); rename to `originTable` and add a derived `fkOnSource()` / `parentHoldsFk()` helper; or remove the raw field altogether since no reader needs it. Add a construction-time invariant check whichever direction wins.

### Generator stubs

Enumerated from `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Priority numbers `#3`–`#4` are referenced by emitted reason strings and must stay stable. Items marked **[Tracked]** already have an Active plan.

3. **Interface / union fetchers** — `QueryField.QueryInterfaceField`, `QueryTableInterfaceField`, `QueryUnionField`, `ChildField.InterfaceField`, `UnionField`, `TableInterfaceField`.
4. **Mutation bodies** — `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`.
5. **Apollo Federation `_entities` resolver** — `QueryField.QueryEntityField`; superseded by "Apollo Federation via federation-jvm transform" in Priority above.
6. **Relay `Query.node` resolver** — `QueryField.QueryNodeField`; blocked on Platform-id as synthesized NodeId (Active).
7. **Service-backed and method-backed root fetchers** **[Tracked]** — `QueryServiceTableField`, `QueryServiceRecordField`, `QueryTableMethodTableField`. Plan: [plan-service-root-fetchers.md](plan-service-root-fetchers.md).
8. **Non-table / scalar / reference child leaves** — `ChildField.ColumnReferenceField`, `NodeIdReferenceField` (blocked on Platform-id), `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField`.

### Cleanup

- **Generated-fetcher quality pass** **[In Progress]** — four cleanups to `TypeFetcherGenerator` connection-fetcher emission: extract pagination boilerplate to a `ConnectionHelper.pageRequest(...)` helper, extract condition orchestration into a new generated `QueryConditions` class, never emit `var` in generated code, and rename the local jOOQ-table variable from `table` to `<entity>Table` to break the mapper/table name collision. ([plan-generated-fetcher-quality.md](plan-generated-fetcher-quality.md))
- **Unify `rowsMethodName()`** **[Backlog]** — lift `"rows" + capitalize(name())` copy-paste from four `BatchKeyField` leaves to a default method on the interface.
- **Unify `FkJoin` construction in `parsePathElement`** **[Backlog]** — `{key:}` branch at `BuildContext.java:557-564` hand-builds `FkJoin`; delegate to `synthesizeFkJoin` for the source-validated success path, keeping the null-source fallback and connectivity-error arms bespoke.
- **Collapse `TableTargetField` structural redundancy** **[Backlog]** — six `Table*Field` variants share identical components; evaluate sealed intermediates (`StandardTableField`, `RecordBoundField`).
- **Shared interface for `QueryField` / `ChildField` table-bound parallels** **[Backlog]** — root variants drop `joinPath` but share `filters · orderBy · pagination`.
- **`JoinConditionRef` wrapper** **[Backlog]** — distinguish `ConditionJoin`/`FkJoin` calling convention from `ConditionFilter` at the type level.
- **Paginated-fields transform coexistence** **[Backlog]** — document or wire `defaultPageSize` loss when `@asConnection` strip precedes the builder.
- **Selection parser audit** **[Backlog]** — `selection/` hand-rolls ~500 LOC; audit whether re-parsing is needed given what graphql-java already provides.
- **`GraphitronContext` extension-point docs** **[Backlog]** — document what belongs in `GraphitronContext` vs jOOQ `ExecuteListener` vs schema directive.
- **Drop `graphitron-common` build dependency from `graphitron-rewrite`** **[Backlog]** — inline `MultiSourceReader` + auto-inject `directives.graphqls`; emitted code runtime dependency unchanged.
- **Consolidate rewrite modules under `graphitron-rewrite/`** **[Backlog]** — move four root-level modules under a single submodule tree to eliminate the fixtures-jar-clobber footgun; schedule after legacy-rewrite parity stabilises.

### Deferred

- **Docs-as-index stabilization** **[Ready — deferred]** — [plan](plan-docs-as-index-into-tests.md). Steps 1–2 shipped; steps 3–4 (re-section renames + doc rewire) deferred until sealed hierarchy stabilises.

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
