# Rewrite Roadmap

This document tracks remaining generator work.
For the model taxonomy (types, fields, directives, and what they generate), see [Code Generation Triggers](../code-generation-triggers.md).
For architectural and technical design principles, see [Rewrite Design Principles](../rewrite-design-principles.md).

---

## Active

Items with a plan in Draft, Approved, In Progress, or Pending Review. First line: state marker + plan link. Description below.

### `DSLContext` params on `@service` methods **[In Progress]** — [plan-service-dsl-context-param.md](plan-service-dsl-context-param.md)

Type-based classification branch in `ServiceCatalog.reflectServiceMethod` that recognises `org.jooq.DSLContext` parameters and produces a `MethodRef.Param.Typed` carrying `ParamSource.DslContext`. Mirrors the existing `org.jooq.Table` check in `reflectTableMethod`. Closes ten "unrecognized sources type: 'org.jooq.DSLContext'" validator failures surfaced by running the rewrite against a real production schema. `ParamSource.DslContext` is already declared and already constructed by a validator test fixture; nothing in reflection produces one today. No emitter consumes the new param yet (service emitters are stubs) — downstream `plan-service-root-fetchers.md` and the ChildField service follow-up pick it up when they ship. `reflectTableMethod` intentionally unchanged; lifting the `@condition`/`@tableMethod` gate requires a call-site rewrite tracked separately. The `Set<T>` parent-keys error from the same run is also out of scope.

### Argument-resolution unification **[Pending Review]** — [argument-resolution.md](argument-resolution.md)

Unified `ArgumentRef` classification + projection in the builder; gates `@condition`-on-fields, `InputColumnBinding`, and every future argument category. Phase 1 (classification + projection, VALUES+JOIN for `QueryLookupTableField`), Phase 2 (generator-side migration: 2a inline `LookupTableField`, 2b `Split(Lookup)TableField` DataLoader rows-methods, 2c `RecordLookupTableField`), and Phase 3 (composite keys via `TableInputArg` + `InputColumnBinding` — atomic binding population, 2-segment `LookupColumn.sourcePath`, composite `LookupValuesJoinEmitter` grouping by root arg) have all shipped. Phase 4 (`@condition` on `INPUT_FIELD_DEFINITION`) remains deferred.

### `BatchKey.ObjectBased` removal **[Draft]** — [plan-batchkey-remove-objectbased.md](plan-batchkey-remove-objectbased.md)

Collapse `BatchKey` to two variants (`RowKeyed`, `RecordKeyed`). The only remaining producer of `ObjectBased` is `ServiceCatalog.classifySourcesType` — a DTO parent handing a `List<Dto>` to an `@service` child. Split that arm: `TableRecord<?>` element types classify as `RowKeyed` from the parent table's PK (the record carries the columns); non-`TableRecord` element types return `Optional.empty()` → `UnclassifiedField` at validate time, with a message pointing at the future lifter directive. `GeneratorUtils` `ObjectBased` switch arms and the `validateServiceTableField` escape-hatch are deleted. Migration note: DTOs flowing through any DataLoader path must be backed by a jOOQ `TableRecord<>` with populated key columns until the lifter directive ships. Record-fields Phase 1/2 are not touched — they already use `RowKeyed` + FK metadata.

### Service-backed and method-backed root fetchers **[Draft]** — [plan-service-root-fetchers.md](plan-service-root-fetchers.md)

Lift three root-`Query` leaves out of `NOT_IMPLEMENTED_REASONS`: `QueryTableMethodTableField`, `QueryServiceTableField`, `QueryServiceRecordField`. All three are synchronous at root — no DataLoader. `@tableMethod` emits a projection SELECT over the developer-returned `Table<?>`; the two `@service` variants are direct method calls returning developer-populated records / DTOs with no framework projection. Validator gains cardinality checks on the two service variants and a rejection of `ParamSource.Sources` parameters at root (no parent-batching context). Closes Backlog item #7.

### `IdReferenceField` input filter variant **[Draft]** — [plan-id-reference-input-field.md](plan-id-reference-input-field.md)

`[ID!] @reference(path: ...)` filter inputs currently mis-classify as `UnclassifiedType` because the `@field(name:)` value is a method-accessor suffix (`hasTerminIds`), not a column. Add a new `InputField.IdReferenceField` permit classified via directive detection.

### Faceted search on `@asConnection` **[Draft]** — [plan-faceted-search.md](plan-faceted-search.md)

New `@facet` directive for filter-input fields. `MakeConnections` synthesizes a `facets: XFacets` field on the Connection type plus reusable `*FacetValue` types; `FieldWrapper.Connection` carries a `FacetSpec` list; `TypeFetcherGenerator.buildQueryConnectionFetcher` emits one `GROUPING SETS` aggregate query per Connection request, with per-aggregate `FILTER` clauses giving each facet its filter-minus-self predicate in a single table scan. Phase 1 is a measurement spike that validates or redirects this SQL strategy before emitter work. Covers Jira GG-335; resolves SOPP-141 (closed in favour of this).

### Classification vocabulary follow-ups **[Draft]** — [plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md)

Five independent doc/generator-behaviour cleanups around the "source context vs. target type" split. None is a release blocker; they can land in any order. Item 2 (build warning channel) is reusable by the `@table`+`@record` bug fix and by the stubbed-variant validator's "warn instead of fail" configurations. Item 5 (lookup-condition method signature docs + execution test) gates G5/G6 execution tests.

### Platform-id as synthesized NodeId **[In Progress]** — [legacy-platform-id.md](legacy-platform-id.md)

Pivot from treating platform-id as a separate sum-type variant (`PlatformIdField`) to synthesizing `NodeType` classification from `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants emitted by a coordinated KjerneJooqGenerator release. All downstream paths (projection, filter, mutation binding) flow through the existing `@nodeId` generator machinery — `NodeIdStrategy.createId` / `hasId` / `hasIds` / `setId`. Unifies what would have been two parallel classification-and-emission paths into one; deletes `InputField.PlatformIdField` / `ChildField.PlatformIdField` after migration. Co-lands the `ChildField.NodeIdField` emission currently stubbed in `NOT_IMPLEMENTED_REASONS`. Mutation binding still blocked on argres Phase 3 for `InputColumnBinding` population. Supersedes the previous four-item plan (prior Items 1-2 shipped as the parallel-variant approach and will be undone in the final commit of this plan).

### KjerneJooqGenerator rewrite — emit NodeId metadata constants **[Draft]** — [plan-kjerne-jooq-generator.md](plan-kjerne-jooq-generator.md)

Rewrite Sikt's externally-owned `KjerneJooqGenerator` so every platform-id table class additionally emits `public static final String __NODE_TYPE_ID` and `public static final Field<?>[] __NODE_KEY_COLUMNS`. Unblocks legacy-platform-id Steps 2–6 at release time. Scratch-only in this repo (proposed `scratch/kjerne-jooq/`); Sikt copies the final sources into their external repo and cuts a release.

### Nesting-field emission **[Pending Review]** — [plan-nesting-field.md](plan-nesting-field.md)

Lift `ChildField.NestingField` out of `NOT_IMPLEMENTED_REASONS`. Classify the nested type's fields at parse time against the outer parent's table, project them into the parent's `$fields` via a recursive switch, and emit a `TypeRuntimeWiring` per nested type (registered from the outer parent's Fetchers class) so every leaf — scalar, `@field(name:)` remap, and later `@reference`/`@computed`/nested `@table` arms — resolves identically to its top-level counterpart. Default property fetching is rejected because `@field(name:)` remap would silently return the wrong column. First arm of Backlog item #8.

### Implicit `@reference` path inference **[Draft]** — [plan-implicit-reference-inference.md](plan-implicit-reference-inference.md)

Make `@reference(path: ...)` optional at every child-field site that can use it. `BuildContext.parsePath` gains a target-table parameter and, when the resolved path is empty with both tables known, synthesizes a single-hop `FkJoin` from the one FK between source and target; zero or multiple FKs produce a classifier-time `UnclassifiedField` with "add a `@reference` directive to specify the join path". Deletes the four EMPTY_PATH stub branches in `SplitRowsMethodEmitter.unsupportedReason` (path-less `SplitTableField` / `SplitLookupTableField` / `RecordTableField` / `RecordLookupTableField` variants start working where the inference succeeds) and the duplicated FK-count rule in `GraphitronSchemaValidator.validateNodeIdReferenceField`. Matches legacy-generator behaviour.

## Backlog

Unplanned items. Pick one, draft a plan, then move to Active.

**Priority (architecture review, 2026-04-17):**

- **`BatchKey` lifter directive** **[Unplanned]** — mechanism (directive, interface, or fluent builder — TBD) for a schema author to supply a DTO → `RowN`/`RecordN` conversion, enabling DataLoader batching on result-type parents. Feeds the existing column-keyed path — `BatchKey` stays at two variants. Blocks nothing today; the DTO-parent service-child shape is rejected at validate time until this lands. See `plan-batchkey-remove-objectbased.md` for the rejection rule.
- **Rebalance test pyramid toward SDL→classified-model→emitted-code pipeline tests** **[Unplanned]** — per-variant structural validator tests (53 classes) dominate the surface; pipeline coverage lives in a single `GraphitronSchemaBuilderTest`. New test investment should go into the classification→emission chain keyed off `graphitron-rewrite-test-fixtures`.
- **Decompose `FieldBuilder`** **[Unplanned]** — split along the field taxonomy after argument-resolution Phase 2 lands. Blocked on Argument-resolution unification (Active). Architecture health check 2026-04-20 sized the pressure: 1,750 lines, 218 rejection-gate branches, concentrated in argument classification (`classifyArguments` → `projectForFilter`/`projectForLookup` duplicate decision logic, and each new argument category multiplies the gates). Proposed split: per-branch classifiers (`QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder`) plus a shared argument-classification module once argres Phase 3 stabilizes the `ArgumentRef` sub-taxonomy so the seams don't shift mid-decomposition.
- **Audit custom pagination-arg-name support** **[Unplanned]** — `PaginationSpec` accepts non-default arg names (`pageSize`/`cursor` instead of `first`/`after`), but no test-spec fixture exercises it and no documented public API lets a schema author opt in. Decide: (a) remove the classifier plumbing as dead code + delete the `connectionField_customPaginationArgNames_emittedInFetcher` body-assertion, or (b) document the mechanism and add an execution fixture. Surfaced during the body-substring test rewrite's OD 2; user leaned toward (a) but the investigation is deferred.
- **Docs-as-index stabilization** **[Approved — deferred]** — [plan-docs-as-index-into-tests.md](plan-docs-as-index-into-tests.md). Steps 1-2 shipped on `claude/review-docs-plan-adYJW`. Step 5 superseded by `GeneratorCoverageTest` + `VariantCoverageTest`. Steps 3-4 (re-section renames + doc rewire) deferred until the sealed hierarchy stabilises.

**Priority (validator audit against production schema, 2026-04-20):**

Three legacy-parity gaps surfaced by running the rewrite validator against a real `sis-graphql-spec` schema. Each corresponds to a feature the legacy generator supports; the rewrite rejects at classify/validate time. User-flagged as important.

- **Lift `@asConnection` rejection on `@splitQuery` fields** **[Unplanned]** — classifier rejects `@asConnection` + `@splitQuery` at `FieldBuilder.java:247–251` (and the inline `@splitQuery`-less variant at `:280–282`) with "deferred to a follow-up plan". Lift the gate by emitting a `ROW_NUMBER() OVER (PARTITION BY fk ORDER BY cursor_col)` envelope on the batched SELECT in `SplitRowsMethodEmitter`, with keyset seek translating to an inequality predicate inside the same partition. Legacy supports per-parent pagination inside a DataLoader batch; the rewrite does not. Scope: both `SplitTableField` and `SplitLookupTableField`. Unblocks schemas where per-parent child lists need Relay pagination (e.g. `Bygning.rom`).
- **Composite-key `@lookupKey` on list-of-input-object arguments** **[Unplanned]** — `[InputType!]! @lookupKey` where each list element's `@field(name:)`-bound scalar fields form a composite PK tuple against the target table. `FieldBuilder.hasLookupKeyAnywhere` detects the shape via the argument-level branch (line 1335), but `projectForLookup` only iterates `ArgumentRef.ScalarArg.ColumnArg` so `LookupMapping.columns()` comes back empty and the gate at `:856–860` fires. Fix adds an `ArgumentRef.CompositeLookupArg` variant (or equivalent) carrying the `(input-field-name, target-column)` pairs resolved from the input type's `@field(name:)` directives, a matching extraction strategy that walks each list element into one tuple, and a multi-column projection into `LookupMapping`. Emission (`LookupValuesJoinEmitter.buildInputRowsMethod`) already handles arbitrary-arity VALUES + JOIN; the work is feeding composite-extracted rows instead of flat-arg rows. Non-`@field`-bound fields on the input type are classify-time errors in v1. Legacy supports this pattern. Unblocks e.g. `Query.organisasjonsenheterGittOrganisasjonsenhetskoder` where a 4-tuple organisation code is the lookup key.
- **Apollo Federation via federation-jvm transform** **[Unplanned]** — supersedes the `QueryField.QueryEntityField` stub (Generator stubs item #5) and resolves the currently-unclassifiable `Query._service` / `_Service` type. Replace native classification of federation root fields with a `GraphitronSchemaBuilder` post-step that runs `com.apollographql.federation.graphqljava.Federation` over the Graphitron-derived schema. The transform injects `_entities([_Any!]!) → [_Entity]`, `_service { sdl: String! }`, the `_Any` / `_Entity` types, and the `__resolveReference` entry point. Graphitron's responsibility narrows to: (a) opting types into Federation via a schema directive (e.g. `@key`), (b) supplying per-type reference resolvers keyed off each type's PK columns. Deletes `QueryField.QueryEntityField` after migration. The transform runs *after* Graphitron's own generation — Graphitron builds a vanilla schema first, federation-jvm wraps it.

**Cleanup / drift:**

- **Unify `rowsMethodName()` across `BatchKeyField` leaves** **[Unplanned]** — `"rows" + capitalize(name())` is now copy-pasted into four records (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` — surfaced by record-fields Phase 2 review 2026-04-20). Lift to a default method on `BatchKeyField` (or a static helper on the interface). If the naming convention ever shifts, changing one record leaves the others silently divergent; only execution tests would catch it.
- **Collapse `TableTargetField` structural redundancy** **[Unplanned]** — `TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` all share the same component set (`returnType · joinPath · filters · orderBy · pagination`). They vary only by (parent context: table-mapped vs. result-mapped) × (split query y/n) × (lookup key y/n). Consider collapsing into fewer types with flags, or intermediate sealed interfaces (`StandardTableField permits TableField, SplitTableField`, `RecordBoundField permits RecordTableField, RecordLookupTableField`).
- **Shared interface for `QueryField` / `ChildField` table-bound parallels** **[Unplanned]** — `QueryTableField` / `QueryLookupTableField` / `QueryTableInterfaceField` / `QueryServiceTableField` / `QueryServiceRecordField` structurally mirror their `ChildField` counterparts; root variants just drop `joinPath`. Both root and child carry the same `filters · orderBy · pagination` triple via `SqlGeneratingField`. Evaluate a shared interface capturing `returnType · filters · orderBy · pagination`. `QueryTableMethodTableField` / `QueryServiceTableField` intentionally stay outside — developer-controlled methods replace SQL generation.
- **`JoinConditionRef` for shared `(source, target)` calling convention** **[Unplanned]** — `ConditionJoin.condition` and `FkJoin.whereFilter` both carry a `MethodRef` with a `SourceTable, Table → Condition` signature, while `ConditionFilter` (also `MethodRef`) uses `Table, Arg... → Condition`. The two groupings aren't expressed in the type system. Consider a `JoinConditionRef` wrapper for the join case so the contracts are distinguishable at the type level.

**Generator stubs to complete:**

Enumerated from `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`; the stubbed-variant validator (Done at `9ba498bc` + `7cf568f4`) fails the build by default when a schema lands on one. Priority numbers `#3`–`#4` are referenced by the reason strings emitted from the generator and must stay stable. Items marked **[Tracked]** already have an Active plan; the rest need drafts.

3. **Interface / union fetchers** — `QueryField.QueryInterfaceField`, `QueryField.QueryTableInterfaceField`, `QueryField.QueryUnionField`, `ChildField.InterfaceField`, `ChildField.UnionField`, `ChildField.TableInterfaceField`. Shared abstraction around `__typename` dispatch + per-implementor projection.
4. **Mutation bodies** — `MutationField.MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`. INSERT / UPDATE / DELETE / UPSERT + service-method mutations.
5. **Apollo Federation `_entities` resolver** — `QueryField.QueryEntityField`. Entity reference resolver dispatching by `__typename` to the registered representation fetchers. Superseded by "Apollo Federation via federation-jvm transform" in the priority list above; the transform injects `_entities` and `_service` together and this leaf is deleted after that lands.
6. **Relay `Query.node` resolver** — `QueryField.QueryNodeField`. Dispatches by platform-id → table lookup across every platform-id-capable table. Blocked on Platform-id as synthesized NodeId (Active).
7. **Service-backed and method-backed root fetchers** **[Tracked]** — `QueryField.QueryServiceTableField`, `QueryField.QueryServiceRecordField`, `QueryField.QueryTableMethodTableField`. Root query bodies that delegate to a user-supplied method (table-bound, record/scalar-bound, or pre-filtered `Table<?>` respectively). Plan: [plan-service-root-fetchers.md](plan-service-root-fetchers.md).
8. **Non-table / scalar / reference child leaves** — miscellaneous `ChildField` variants, each currently rejected at validate time:
   - `ChildField.NestingField` **[Tracked]** — nested object inheriting the parent's table context unchanged (no navigation, no SQL). Plan: [plan-nesting-field.md](plan-nesting-field.md).
   - `ChildField.ColumnReferenceField` — `@reference(path:)` resolving to a scalar column via a join path.
   - `ChildField.NodeIdReferenceField` — `@nodeId` scalar on a reference path. Blocked on Platform-id (Active).
   - `ChildField.ComputedField` — computed scalar, possibly with `joinPath` context.
   - `ChildField.TableMethodField` — child `@tableMethod`: user supplies a pre-filtered `Table<?>`.
   - `ChildField.ServiceRecordField` — `@service` child returning a non-table value (record/scalar).
   - `ChildField.MultitableReferenceField` — `@reference` targeting multiple possible tables (polymorphic).

**Miscellaneous:**

- **Paginated-fields transform coexistence** **[Unplanned]** — when the schema goes through both the transform and the builder, `@asConnection` is stripped before the builder sees it. The builder falls back to structural detection, which works but loses `defaultPageSize` (defaults to 100). Document or wire the default through.
- **Cursor format stability** **[Done]** — replaced `org.jooq.tools.json.JSONValue` with a NUL-delimited format (`\u0000` separator, `\u0001` for SQL NULL). Safe on PostgreSQL (NUL bytes rejected by the DB); Oracle allows NUL bytes in `VARCHAR2`/`CLOB`, so sort columns containing `\u0000` would corrupt the cursor — Oracle users should avoid such columns as connection sort keys.
- **Selection parser audit** **[Unplanned]** — `selection/` hand-rolls ~500 LOC that graphql-java already parses. Audit whether the runtime path really needs re-parsing.
- **`GraphitronContext` extension-point docs** **[Unplanned]** — document what belongs in a `GraphitronContext` method vs a jOOQ `ExecuteListener` vs a schema directive.
- **Drop `graphitron-common` build dependency from `graphitron-rewrite`** **[Unplanned]** — one production import + one test-classpath resource. Inline `MultiSourceReader` + auto-inject `directives.graphqls` from the module's own classpath. Emitted code's *runtime* dependency on `graphitron-common` is unchanged.
- **Consolidate rewrite modules under `graphitron-rewrite/` with local build profiles** **[Unplanned]** — `graphitron-rewrite`, `graphitron-rewrite-test/`, `graphitron-rewrite-test-fixtures/`, `graphitron-rewrite-test-spec/` live at the repo root alongside the legacy generator. Move all four under `graphitron-rewrite/` as a submodule tree (`graphitron-rewrite/parent/pom.xml`, plus `core/`, `test/`, `test-fixtures/`, `test-spec/`). Lift the DB-bound build knobs (`-Plocal-db`, Docker-vs-native Postgres, `jooq.codegen.skip`) to a rewrite-scoped profile so a single `mvn -f graphitron-rewrite/ install` can do the full rewrite loop without touching the legacy generator's testcontainers-backed `graphitron-java-codegen` build. Removes the fixtures-jar-clobber footgun noted in CLAUDE.md (no more broad installs can touch the populated fixtures jar) and cuts the agent-session bootstrap from "install legacy toolchain" to "install rewrite subtree". Schedule after the legacy-rewrite parity push stabilises — the two are still cross-wired via `graphitron-maven-plugin`'s `enableRewrite` flag.

## Done milestones

Short history — landings worth remembering when picking up related work.

- `3357928` — Sealed-switch generator dispatch. `TypeFetcherGenerator.generateTypeSpec` became an exhaustive sealed `switch` over all `GraphitronField` leaves; stubbed leaves route through `stub(f)` backed by `NOT_IMPLEMENTED_REASONS`.
- `15f9f61e` — Variant-coverage Phase 1. `IMPLEMENTED_LEAVES` + `NOT_DISPATCHED_LEAVES` sibling sets; partition invariant enforced by `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`.
- `1e48c4ee` — Argument-resolution Phase 1. VALUES + JOIN lookup emission for `QueryLookupTableField`.
- G5 — Inline `TableField` emission. `TypeClassGenerator.$fields` emits uniform `DSL.multiset` correlated subqueries; `TableField` and inline `LookupTableField` moved to `PROJECTED_LEAVES`. Seven execution tests.
- `aaadb78b` — Argument-resolution Phase 2a. Inline `ChildField.LookupTableField` emission via `InlineLookupTableFieldEmitter`; VALUES + explicit-ON keyset layered onto G5's correlated-subquery shape. `LookupTableField` moved to `PROJECTED_LEAVES`. Classifier rejects `@asConnection` and Single-cardinality on inline `@lookupKey`. Co-lands `LookupValuesJoinEmitter.buildChildInputRowsMethod` (SelectedField-based arg extraction). Six execution tests via `Film.actors(actor_id:)` junction fixture.
- `7417f53` — Body-substring test rewrite. `TypeSpecAssertions` helper (`hasFieldsArm`, `wiringFor(field) → DataFetcherKind`, `hasNoDataFetchers`) + 28-site migration across 6 test files. 28 → 3 intentionally-marked sites, each justified inline. C3 (lint gate) deferred per OD 3. Follow-up (post-Done): `filmsConnection_rejectsFirstAndLastTogether` execution test landed, deleting the Relay-validation kept-with-marker; remaining three markers are `queryLookupField_idListKey_bindsViaColumnDataTypeInInputRowsHelper`, `connectionField_customPaginationArgNames_emittedInFetcher`, `connectionField_withOrderByArg_extraFieldsComeFromOrderingResult`. CLAUDE.md ban now matches test-file reality.
- `34359b4` — Argument-resolution Phase 2b. DataLoader rows-method bodies for `SplitTableField` and `SplitLookupTableField`; VALUES derived table with `__idx__` scatter column. Execution tests assert exact JDBC round-trip counts vs. N+1.
- Record-fields Phase 1. `TypeFetcherGenerator` emits `*Fetchers` for `ResultType` parents; `PropertyField`, `RecordField`, `ConstructorField`, and `RecordTableField` all working with execution tests.
- Record-fields Phase 2. `RecordLookupTableField` lifted out of `NOT_IMPLEMENTED_REASONS`; `BatchKey` added via `deriveBatchKeyForResultType`; `buildRecordBasedDataFetcher<T>` generalised to serve both `RecordTableField` and `RecordLookupTableField`. Five execution tests.
- `9ba498bc` + `7cf568f4` — Stubbed-variant validator. `GraphitronSchemaValidator.validateVariantIsImplemented` reads `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` and appends a classification error when a field's variant is stubbed. `ValidateMojo` fails the build on rewrite validation errors by default (`-Dgraphitron.failOnRewriteValidationError=false` escape hatch). `FieldValidationTestHelper.stubbedError` lock-steps tests to production reason strings. `StubbedVariantPipelineTest` regression covers SDL → classifier → validator end-to-end. Closes the "schema validates but generated fetcher throws at request time" loop.
- `@table` + `@record` input-type fix. `@record` dominates `@table` on input types; classifier routes through `buildNonTableInputType` and emits a `BuildWarning` naming the shadowed directive. Introduces the reusable `BuildWarning` / `BuildContext.warnings()` / `GraphitronSchema.warnings()` channel surfaced by the mojos — consumed by classification-vocabulary item 2.
- `d33ace9` — Variant-coverage meta-test Phase 2. `ClassificationCase` interface + `VariantCoverageTest.everySealedLeafHasAClassificationCase` retrofit 26 classification enums in `GraphitronSchemaBuilderTest` with machine-readable `variants()` sets (plus five new enum constants for previously-uncovered leaves: `NodeType`, `RootType`, `TableInterfaceType`, `UnionType`, `ChildField.ConstructorField`). `NO_CASE_REQUIRED` allowlist carries four entries (two `PlatformIdField` leaves scheduled for deletion; two `JooqRecord` types without a non-`TableRecord` fixture). Phase 3 (narrow-component coverage — `BatchKey`, `ParamSource`, `CallSiteExtraction`, …) deferred until concrete demand; file a new plan then.
- Java-17 output ratchet. `graphitron-rewrite-test-spec`'s compile goal pinned to `release=17` (testCompile stays at parent's `release=21`). Java-21 syntax emitted by a generator emitter now fails this module's build rather than reaching a consumer's. `-Werror` deliberately omitted — conflates with pre-existing raw-type warnings in generated code.

---

## Reference: G6 split/lookup field categories

G6 covers four categories of DataLoader-backed field. Before implementing any category, verify the model is generation-ready. Execution-test prerequisite same as G5 above.

| Category | DataLoader | Derived tables | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **`LookupQueryField`** (root lookup) — now live via argres Phase 1 | No — synchronous | Derived target only | Blocked (lookup invariant) | Never — result count = M exactly |
| **Table-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, table-mapped parent) | No — correlated subquery | Derived target + correlated parent join | Blocked | Never |
| **Result-mapped `TableField`** (`@splitQuery`, no `@lookupKey`) | Yes | Derived source only | Allowed | Allowed |
| **Result-mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, result-mapped parent) | Yes | Both | Blocked | Never — result count = N × M |

---

## Known Gaps

### `ConditionFilter` has no builder path

`FieldBuilder` currently produces `GeneratedConditionFilter` entries for filterable arguments, but never produces `ConditionFilter` entries for `@condition` directives on fields. Field-level `@condition` annotations — WHERE predicates applied to the field's own target table — are not yet classified into the rewrite pipeline's filter list.

**Note:** This gap is about the `@condition` directive on *fields* (WHERE predicate). It is distinct from condition joins in `@reference` paths — `{condition: {className, method}}` in a `path:` element — which are fully resolved by the builder into `ConditionJoin` steps.

**Fix**: add `@condition` directive reading to `FieldBuilder.resolveFilters()`. `ConditionFilter` now implements `MethodRef` directly, so the builder constructs it with `(className, methodName, params)` and `callParams()` is derived automatically.

---

## Implementation Guidance

No DTOs, no TypeMappers. DataFetchers return `Result<Record>`; GraphQL-Java traverses the records using the registered field DataFetchers.

**Exception:** Connection fields return `ConnectionResult` — a generated carrier wrapping `Result<Record>` + pagination context.

### Selection-aware queries and multiset

`DataFetchingFieldSelectionSet` and `SelectedField` are already threaded through all table method signatures, structurally committing to selection-aware queries. When the table method bodies are implemented:

- **Top-level**: call `Type.$fields(sel, table, env)` for the column list, then `dsl.select(fields).from(table)...`
- **Inline nesting**: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")`, returned as `Field<?>` (type-erased). Use type erasure at every helper method boundary — jOOQ's generic types compound badly with nesting depth, causing slow compile times.
- **`@splitQuery`**: separate DataLoader; parent fetches the FK/PK columns, child batches by those keys.

### Query plan caching trade-off

Selection-driven queries produce different SQL per request (different column lists). The database cannot reuse cached query plans across requests. This is an acceptable cost for wide tables with large optional columns, but for narrow tables (≤10 columns) where most fields are always requested, selecting `TABLE.*` is simpler and the overhead of dynamic column selection exceeds the benefit.

### Error quality

`BuildContext` implements `candidateHint(attempt, candidates)` using Levenshtein distance to sort candidates by similarity. Used in 14 places (5 in `FieldBuilder`, 5 in `TypeBuilder`, 2 in `BuildContext`, 2 in `ServiceCatalog`). When adding new jOOQ existence checks in the validator or builder, follow the same pattern — pass the relevant candidate list from `JooqCatalog` to `candidateHint`.
