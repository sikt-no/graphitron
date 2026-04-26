# Rewrite Roadmap

Tracks remaining generator work. For the model taxonomy, see [Code Generation Triggers](../code-generation-triggers.md). For design principles, see [Rewrite Design Principles](../rewrite-design-principles.md).

**First time contributing?** Read in this order: [Workflow](../workflow.md) (state machine and plan conventions), [Rewrite Design Principles](../rewrite-design-principles.md) (the architectural rules every classifier and emitter obeys), [Code Generation Triggers](../code-generation-triggers.md) (how directives map to model variants and generator output). Then read an Active plan below to see the shape, and pick a `[Backlog]` item or take a Ready item off the Active table.

---

## Active

| Item | Status | Plan |
|---|---|---|
| `@asConnection` `totalCount` field (release blocker) | Spec | [plan](plan-asconnection-totalcount.md) |
| Rebase and squash rewrite branch onto main | Ready | [plan](plan-history-squash.md) |
| `BatchKey.ObjectBased` removal | Spec | [plan](plan-batchkey-remove-objectbased.md) |
| `IdReferenceField` input filter variant | Spec | [plan](plan-id-reference-input-field.md) |
| Classification vocabulary follow-ups | Spec | [plan](plan-classification-vocabulary-followups.md) |
| Multi-parent NestingField sharing — `TableField` arm | Spec | [plan](plan-nestingfield-multiparent-tablefield.md) |
| Faceted search on `@asConnection` | Spec | [plan](plan-faceted-search.md), [spike](spike-faceted-search-sql.md) |
| First-class Connection, Edge, PageInfo type variants | In Review | [plan](plan-firstclass-connection-types.md) |
| Mutation bodies | Spec | [plan](plan-mutations.md) |
| Docs as an index into classification tests | Ready (deferred) | [plan](plan-docs-as-index-into-tests.md) |
| Retire `graphitron-maven-plugin` + `graphitron-schema-transform` | In Progress | (see body) |
**Notes:** `@asConnection` `totalCount` field is a release blocker: synthesised Connections currently lack the field that legacy ships by default, so consumers migrating off the legacy generator would lose `totalCount` selection until this lands. Classification vocabulary follow-ups covers five independent cleanups — none is a release blocker. Docs-as-index is parked on steps 3–4 until the sealed hierarchy stabilises (Active work and Stubs still in motion); steps 1–2 shipped. `Retire graphitron-maven-plugin` is the umbrella tracker for what's left of the schema-transform / Maven-plugin migration; remaining sub-items are body bullets under the umbrella. Mutation bodies Phase 6 (service variants) builds on `FieldBuilder.validateRootServiceInvariants` and the structured `MethodRef.returnType` introduced by service-root-fetchers (Done).

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
| 3 | Nested type shared across parents with `TableField` | Active: *Multi-parent NestingField sharing — `TableField` arm* |
| 1 | `QueryInterfaceField` | Stubs #3 |
| 0 | `QueryNodeField`, `QueryEntityField`, `@asConnection` on inline `TableField`, service-method unrecognized-sources param, `key does not connect`, `_Service` return type, and other nil-count stubs | various (no consumer pressure today) |

**By area aggregate (close-with-one-plan totals):** Mutation bodies 131 (Stubs #4) · non-table / scalar child leaves 62 (Stubs #8) · interface / union 21 (Stubs #3) · remaining split-query pain 16 (Active §5).

**Schema-author errors (diagnostics UX, not generator gaps):** `@lookupKey` with no resolved argument 32 · `@condition` parameter unresolvable 7 · service method reference incomplete 4 · no FK between tables 2 · type mapped to `@table` has unresolvable fields 2 · column not in jOOQ table (typo-suggest) 1 · argument's column unresolvable 1.

### Priority

Backlog items in rough dependency order. Snapshot-backed items are promoted to the Active table at the top of this doc as plans get drafted; only architecture / structural items (no direct production-count attribution) remain here.

Architecture / structural:

- **Retire `graphitron-maven-plugin` + `graphitron-schema-transform`** — fold the remaining transform passes and the Maven surface into `graphitron-rewrite` so every schema pass has a single code-owner and consumers depend on `graphitron-rewrite-maven` only. End state: `mvn install -f graphitron-rewrite/pom.xml` produces a self-contained plugin jar and the legacy modules delete. Most of this umbrella has shipped; see [`changelog.md`](changelog.md) for the build surface (schema loading, tagged inputs, Maven plugin, aggregator-standalone, content-idempotent writes) and `@asConnection` emit-time synthesis. Remaining sub-items, all in scope of this umbrella:

  - **Java LSP rewrite + introspect retirement + `dev` goal** **[Spec]**: successor to the Rust `graphitron-lsp`, the legacy `graphitron-maven-plugin:introspect` goal, and the existing `graphitron-rewrite:watch` Mojo. Replaces them with a Java LSP under `graphitron-rewrite/graphitron-lsp` plus a single `graphitron-rewrite-maven:dev` goal that runs the LSP on a loopback socket and watches schema files in one JVM. Phase 0 foundation shipped (module wired, lsp4j scaffold, tree-sitter via bonede binding, incremental parse validated, end-to-end table-name completion through 5 passing tests). Phase 1 introduces the `dev` goal and retires the Phase 0 `lsp` Mojo plus the existing `watch` Mojo. Phases 2-7 cover catalog wiring via `MethodRef` / `TableReflection` / `ScalarUtils`, per-directive ports, goto-definition, `@service` / `@condition` + Javadoc, jtreesitter migration once Java 25 is the floor, and Rust LSP / introspect retirement. ([plan-graphitron-lsp.md](plan-graphitron-lsp.md))
  - **`@notGenerated` directive removed** **[In Review]**: the legacy `ElementRemovalFilter` skip-and-prune semantics are gone; the directive is dropped from the supported set. The classifier short-circuits any application to `UnclassifiedField` with reason "no longer supported", and the directive definition stays in `directives.graphqls` only so users get our error rather than a `unknown directive` parse failure. ([plan-remove-notgenerated.md](plan-remove-notgenerated.md))
  - **Federation SDL integration** — bundled with the **Apollo Federation via federation-jvm transform** Backlog item below; tracked there.
  - **Retire legacy + unnest the rewrite aggregator** — closing landing marker once every legacy consumer has migrated to the new plugin. Delete `graphitron-common`, `graphitron-codegen-parent/` (both submodules), `graphitron-maven-plugin`, `graphitron-schema-transform`, `graphitron-example`, `graphitron-servlet-parent` (if legacy-only), and the top-level `pom.xml`. Promote `graphitron-rewrite/` to the repo root: aggregator POM becomes root POM; modules relocate up one level; the duplicated javapoet copy becomes the only copy; the two parent POMs merge. One-time repo-topology change; no consumer-visible surface beyond git-history refs and CI path updates. Trigger: every legacy consumer migrated (cadence dictated by per-consumer feature work).

  **Architecture decisions that pruned scope.** The programmatic-schema architecture (`Graphitron.buildSchema(...)`, see changelog) made several originally-planned migrations moot and they have been removed from the umbrella: type-extension merging (`MergeExtensions`) is a non-issue without the registry-level bridge; directive stripping (`DirectivesFilter`, `GenerationDirective`) is irrelevant because rewrite no longer emits a client SDL; the runtime no longer reads `schema.graphql` at bootstrap so neither client-SDL emission nor feature-flag SDL splits (`FeatureConfiguration`, `SchemaFeatureFilter`, `<outputSchemas>`) are needed.
- **`BatchKey` lifter directive** — mechanism for schema authors to supply a DTO→key conversion, enabling DataLoader batching on DTO parents; feeds the existing column-keyed path once `BatchKey.ObjectBased` removal lands. (Co-closes the 32-count `RecordTableField` / `RecordLookupTableField` missing-FK-path rejection for DTO parents.)
- **Decompose `FieldBuilder`** — split 1,750-line builder along field taxonomy; blocked on Argument-resolution unification. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` + shared argument-classification module.
- **Extract semantic-check helpers from `classifyQueryField`** — the codebase rejects malformed fields at classifier time by returning `UnclassifiedField` (polymorphic `@service` at `FieldBuilder.java:1305-1306`; single-cardinality `@splitQuery @lookupKey` and multi-hop single-cardinality `@splitQuery` at `FieldBuilder.java:252-257` / `:266-271`; Connection / Sourced-param rejection on root `@service` and `@tableMethod` via `FieldBuilder.validateRootServiceInvariants`, plus the §3 strict-class and §5 strict-return checks inline in `classifyQueryField` and on `ServiceCatalog`). The pattern is consistent and better than validator-time rejection for the "emitter sees only well-formed leaves" property, but it means `classifyQueryField` accumulates semantic checks alongside shape dispatch. Refactor: extract per-directive helpers like `rejectInvalidService(fieldDef, svcResult) → Optional<UnclassifiedField>` and `rejectInvalidTableMethod(fieldDef, tb) → Optional<UnclassifiedField>`, so each classifier arm reads as "run semantic gates, then dispatch to the leaf". `validateRootServiceInvariants` is a partial example of this pattern; the broader extraction across all directive arms remains outstanding. Orthogonal to "Decompose `FieldBuilder`" above — that splits by field taxonomy; this refactors within each arm. Not urgent; do it when a new rejection would push the file past a readability threshold.
- **Composite-key `@lookupKey` on list-of-input-object arguments** — add `ArgumentRef.CompositeLookupArg` carrying `(input-field-name, target-column)` pairs resolved from `@field(name:)` directives; `buildInputRowsMethod` already handles arbitrary-arity VALUES + JOIN.
- **Apollo Federation via federation-jvm transform** — replace `QueryEntityField` stub with a `GraphitronSchemaBuilder` post-step wrapping the Graphitron schema via `Federation.transform`; deletes the stub after migration.
- **`DSLContext` on `@condition` / `@tableMethod` methods** — lift `reflectTableMethod` gate; requires `ArgCallEmitter` to walk `params()` instead of `callParams()` so the injected DSLContext lands at its declaration-index slot.
- **`Set<T>` parent-keys on `@service` methods** — decide: require `List<T>` (predictable batching order, current direction) or broaden `BatchKey`; one known offender (`navnAlleSprak`).
- **Checked exceptions on `@service` / `@tableMethod` for typed GraphQL errors**. Explore mapping developer-declared checked exceptions on service / table-method methods to typed GraphQL errors (`@error` types, mutation payload error unions). Today `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod` ignore `getExceptionTypes()`; the emitted fetcher has no `throws` clause, so a developer method declaring `throws SQLException` (or any checked exception) breaks the rewrite-test compile gate. Direction worth scoping: treat declared exceptions as a typed error channel that maps to GraphQL error shapes on the wire, classified against `@error` types or mutation payload union members so the generated fetcher catches the typed exception and yields a graphql-java error of the matching shape. Cheap stop-gap if the exploration stalls: a classify-time rejection with a clear message naming the offending exception class. Most relevant once mutation bodies (Stubs #4) lands, since service-method signatures are most commonly checked-exception-bearing on the write path.
- **Rebalance test pyramid** — shift new test investment from per-variant structural tests toward SDL→classification→emission pipeline tests keyed off `graphitron-rewrite-fixtures`.
- **Audit custom pagination-arg-name support** — decide: remove `PaginationSpec` plumbing for non-default `first`/`after` names (likely dead code) or document and add an execution fixture.
- **Clarify `FkJoin` direction semantics** — `JoinStep.FkJoin.sourceTable` is written to the traversal-origin table in `BuildContext.synthesizeFkJoin:473` and `parsePathElement:559-560`, contradicting the docstring at `JoinStep.java:70-72` (which claims it resolves to the FK-holder table). Currently dead data — zero readers today — but was a bug magnet for the first candidate reader (the cardinality-driven `deriveSplitQueryBatchKey` helper, shipped under "Single-cardinality `@splitQuery` support" in Done). Options: fix construction to match the docstring (low risk, field unread); rename to `originTable` and add a derived `fkOnSource()` / `parentHoldsFk()` helper; or remove the raw field altogether since no reader needs it. Add a construction-time invariant check whichever direction wins.

### Generator stubs

Enumerated from `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Priority numbers `#3`–`#4` are referenced by emitted reason strings and must stay stable. Aggregate production counts from the snapshot are listed where applicable; ordering within this section should follow those counts (highest-impact first) once an item is promoted to Active.

3. **Interface / union fetchers** (prod: 21 — `QueryTableInterfaceField` 20 + `QueryInterfaceField` 1) — `QueryField.QueryInterfaceField`, `QueryTableInterfaceField`, `QueryUnionField`, `ChildField.InterfaceField`, `UnionField`, `TableInterfaceField`.
4. **Mutation bodies** (prod: 131 aggregate — update 45, `MutationServiceRecordField` 44, delete 23, insert 19) — `MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`, `MutationServiceTableField`, `MutationServiceRecordField`.
5. **Apollo Federation `_entities` resolver** (prod: 0) — `QueryField.QueryEntityField`; superseded by "Apollo Federation via federation-jvm transform" in Priority above.
6. **Relay `Query.node` resolver** (prod: 0) — `QueryField.QueryNodeField`. Shipped under *`@nodeId` + `@node` directive support* (Done).
7. **Service-backed and method-backed root fetchers** (prod: 0) — `QueryServiceTableField`, `QueryServiceRecordField`, `QueryTableMethodTableField`. Shipped under *Service-backed and method-backed root fetchers* (Done).
8. **Non-table / scalar / reference child leaves** (prod: 62 — `ColumnReferenceField` 41 + `ComputedField` 21) — `ChildField.ColumnReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField`. (`NodeIdReferenceField` shipped under *`@nodeId` + `@node` directive support* (Done) for the FK-mirror case; non-FK-mirror form is a Cleanup item.)

### Cleanup

- **Unify `rowsMethodName()`** — lift `"rows" + capitalize(name())` copy-paste from four `BatchKeyField` leaves to a default method on the interface.
- **Unify `FkJoin` construction in `parsePathElement`** — `{key:}` branch at `BuildContext.java:557-564` hand-builds `FkJoin`; delegate to `synthesizeFkJoin` for the source-validated success path, keeping the null-source fallback and connectivity-error arms bespoke.
- **Collapse `TableTargetField` structural redundancy** — six `Table*Field` variants share identical components; evaluate sealed intermediates (`StandardTableField`, `RecordBoundField`).
- **Shared interface for `QueryField` / `ChildField` table-bound parallels** — root variants drop `joinPath` but share `filters · orderBy · pagination`.
- **`JoinConditionRef` wrapper** — distinguish `ConditionJoin`/`FkJoin` calling convention from `ConditionFilter` at the type level.
- **Paginated-fields transform coexistence** — document or wire `defaultPageSize` loss when `@asConnection` strip precedes the builder.
- **Selection parser audit** — `selection/` hand-rolls ~500 LOC; audit whether re-parsing is needed given what graphql-java already provides.
- **`GraphitronContext` extension-point docs** — document what belongs in `GraphitronContext` vs jOOQ `ExecuteListener` vs schema directive.
- **`PageInfo` wiring design decision** — `WiringClassGenerator` currently emits no `PageInfoWiring` class; `PageInfo` fields (`hasNextPage`, `hasPreviousPage`, `startCursor`, `endCursor`) resolve via graphql-java's default property fetcher against whatever object `ConnectionHelper.pageInfo()` returns. This works by convention today. Decision: document "PageInfo always uses default property fetching" explicitly in the generator, or emit an explicit `PageInfoWiring` that pins the property names. Choose before a schema adds complex `PageInfo` fields.
- **Drop the assembled-schema rebuild in favour of per-variant graphql-java forms** — Phase 5 of `plan-firstclass-connection-types` rebuilds the assembled `GraphQLSchema` via `SchemaTransformer` so directive-driven `@asConnection` carriers carry their rewritten return type and pagination args. The rebuild only runs at generate time and is never seen by the runtime (which reconstructs its schema from emitted `<TypeName>Type.type()` calls in `GraphitronSchema.build()`). Alternative: skip the rebuild; rebuild each carrier's parent `GraphQLObjectType` once via `parent.transform(b -> b.field(rewrittenField))` and stash it on the corresponding `GraphitronType` variant; emitters read per-variant `schemaType()` only. Already done for the synthesised types (Connection / Edge / PageInfo); this extends the pattern to rewritten parents. *Saves* the two-step `additionalType + SchemaTransformer` dance (~80 lines of classifier code) and the bundle-coherence overhead. *Costs* the bundle's "every type reference resolves on `assembled.getType()`" invariant — any future build-time consumer that wants a coherent `GraphQLSchema` (SDL printer for client schemas, an introspection-based validator, federation manifest emitter) would have to be re-engineered. Worth picking up when a concrete signal pushes the trade — e.g. an emitter explicitly preferring per-variant graphql-java forms over name-keyed `assembled.getType()` lookups, or schema-rebuild edge cases turning into recurring debugging cost. Until then, the rebuild is paying its rent and Phase 5 stays.
- **`TypeResolver` wiring for interface/union types** — `WiringClassGenerator` emits `DataFetcher` wiring only; GraphQL interface and union types also require a `TypeResolver` registered via `TypeRuntimeWiring.newTypeWiring("MyInterface").typeResolver(...)`. Currently no `TypeResolver` is wired for any interface or union type, so runtime would get `Can't resolve type for object` errors. Companion to stub #3 (Interface / union fetchers): the fetcher stub covers `QueryField` / `ChildField` variants; this item covers the `WiringClassGenerator` side. (`Node` interface is the exception — `QueryNodeFetcher.registerTypeResolver` is wired today via the `@nodeId` + `@node` plan.)
- **Retire `@nodeId` synthesis shim** — `TypeBuilder.buildTableType` promotes a metadata-carrying type to `NodeType` even when SDL lacks `implements Node @node`; `FieldBuilder` Path-2 and `BuildContext.classifyInputField` similarly synthesize `NodeIdField` for bare scalar `ID` fields without `@nodeId`. Each site fires a per-occurrence WARN today. Once consumer schemas declare the directives explicitly (production schema in alf is canonical; one external-consumer release window is the courtesy gate), delete the three shim branches and turn the WARN into a terminal classifier error. Test fixtures retain the synthesized cases until then; flip them to canonical `implements Node @node` + `@nodeId` SDL alongside the deletion.
- **`NodeIdReferenceField` JOIN-projection form** — the `@nodeId` + `@node` plan shipped the FK-mirror collapse path (single-hop FK whose target columns positionally match the target NodeType's `keyColumns`; the parent's FK source columns encode directly with no JOIN). Composite-FK that doesn't mirror, multi-hop, and condition-join cases emit a runtime `UnsupportedOperationException` stub today (`FetcherEmitter#dataFetcherValue`'s `NodeIdReferenceField` arm). Lift to a real correlated-subquery emission projecting the target's `nodeKeyColumns` under aliases when a real schema needs it; the test fixture would have to grow a multi-hop or non-mirroring FK shape to exercise it.

---

## Done

See [`changelog.md`](changelog.md) for the historical record of shipped rewrite work.
