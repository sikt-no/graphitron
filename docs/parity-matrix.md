# Legacy vs Rewrite Parity Matrix

As of 2026-04-19 on `claude/graphitron-rewrite`.

**Legend**
- ✅ — working
- ⚠️ — partial: compiles and passes `ValidateMojo`, but throws at runtime
- ⏳ — stubbed: classifier exists; `ValidateMojo` rejects the schema at build time with a diagnostic
- ❌ — no classifier: schema fails with an unclassified error (or feature is deliberately excluded)

---

## Root query fields

| Feature | Legacy | Rewrite |
|---|---|---|
| Table query | ✅ | ✅ `QueryTableField` |
| Lookup query (`@lookupKey` arg) | ✅ | ✅ `QueryLookupTableField` |
| Paginated query (`@asConnection`) | ✅ | ✅ |
| Relay `node` query | ✅ | ⏳ `QueryNodeField` |
| Federation `_entities` query | ✅ | ⏳ `QueryEntityField` |
| Interface query (`@table` + `@discriminate`) | ✅ | ⏳ `QueryTableInterfaceField` |
| Multi-table interface query | ✅ | ⏳ `QueryInterfaceField` |
| Union query | ✅ | ⏳ `QueryUnionField` |
| Service query (`@service`, table return) | ✅ | ⏳ `QueryServiceTableField` |
| Service query (`@service`, record return) | ✅ | ⏳ `QueryServiceRecordField` |
| Table method query (`@tableMethod`) | ✅ | ⏳ `QueryTableMethodTableField` |

## Mutations

| Feature | Legacy | Rewrite |
|---|---|---|
| INSERT | ✅ | ⏳ `MutationInsertTableField` |
| UPDATE | ✅ | ⏳ `MutationUpdateTableField` |
| DELETE | ✅ | ⏳ `MutationDeleteTableField` |
| UPSERT | ✅ | ⏳ `MutationUpsertTableField` |
| Service mutation, table return | ✅ | ⏳ `MutationServiceTableField` |
| Service mutation, record return | ✅ | ⏳ `MutationServiceRecordField` |

## Child fields on `@table` parent — columns

Emitted into `Type.$fields(sel, table, env)` as projected expressions.

| Feature | Legacy | Rewrite |
|---|---|---|
| Column mapping (`@field` or matching name) | ✅ | ✅ `ColumnField` |
| Relay node-id synthesis (`@nodeId`, no typeName) | ✅ | ✅ `NodeIdField` |
| Platform-id synthesis | ✅ | ✅ `PlatformIdField`¹ |
| FK column reference (`@reference` on scalar) | ✅ | ⏳ `ColumnReferenceField` |
| Node-id reference (`@nodeId(typeName:)`) | ✅ | ⏳ `NodeIdReferenceField` |
| Computed / external field (`@externalField`) | ✅ | ⏳ `ComputedField` |

## Child fields on `@table` parent — inline table joins

Correlated subqueries emitted into `Type.$fields`.

| Feature | Legacy | Rewrite |
|---|---|---|
| FK child table join | ✅ | ✅ `TableField` |
| FK child table + lookup narrowing (`@lookupKey`) | ✅ | ✅ `LookupTableField` |
| Multi-table reference (`@multitableReference`) | ✅ | ⏳ `MultitableReferenceField` |
| Interface child (`@table` + `@discriminate`) | ✅ | ⏳ `TableInterfaceField` |
| Multi-table interface child | ✅ | ⏳ `InterfaceField` |
| Union child | ✅ | ⏳ `UnionField` |
| Nesting (non-`@table` object, inherits parent scope) | ✅ | ⏳ `NestingField` |
| Constructor-mapped field (`@experimental_constructType`) | ✅ | ⏳ `ConstructorField` |

## Child fields on `@table` parent — DataLoader (`@splitQuery`)

| Feature | Legacy | Rewrite |
|---|---|---|
| Split table | ✅ | ✅ `SplitTableField` |
| Split lookup (`@splitQuery` + `@lookupKey`) | ✅ | ✅ `SplitLookupTableField` |
| Paginated split (`@splitQuery` + `@asConnection`) | ✅ | ❌ classifier rejects; per-parent pagination deferred |
| Service DataLoader (`@service`, table return) | ✅ | ⚠️ `ServiceTableField` — rows method stub² |
| Service record (`@service`, non-table return) | ✅ | ⏳ `ServiceRecordField` |
| Table method (`@tableMethod`) | ✅ | ⏳ `TableMethodField` |

## Child fields on `@record` parent

| Feature | Legacy | Rewrite |
|---|---|---|
| Scalar / property field | ✅ | ⏳ `PropertyField` |
| FK child table join | ✅ | ⏳ `RecordTableField` |
| FK child table + lookup narrowing | ✅ | ⏳ `RecordLookupTableField`³ |
| Non-table object return | ✅ | ⏳ `RecordField` |
| Service DataLoader (`@service`, table return) | ✅ | ⚠️ `ServiceTableField` — same as above² |
| Service record (`@service`, non-table return) | ✅ | ⏳ `ServiceRecordField` |

## Directives

| Directive | Legacy | Rewrite |
|---|---|---|
| `@table(name:)` | ✅ | ✅ |
| `@record` | ✅ | ✅ |
| `@field(name:)` | ✅ | ✅ |
| `@reference(path:)` | ✅ | ✅ |
| `@splitQuery` | ✅ | ✅ |
| `@lookupKey` (scalar arg) | ✅ | ✅ |
| `@lookupKey` (composite input type) | ✅ | ⏳ argres Phase 3 |
| `@asConnection` (root or inline child) | ✅ | ✅ |
| `@asConnection` on `@splitQuery` field | ✅ | ❌ classifier rejects; per-parent pagination deferred |
| `@condition` on field | ✅ | ✅ |
| `@condition` on argument | ✅ | ✅ |
| `@condition` on input field | ✅ | ⏳ argres Phase 4 |
| `@orderBy` / `@defaultOrder` | ✅ | ✅ |
| `@nodeId` | ✅ | ✅ |
| `@node(typeId:, keyColumns:)` | ✅ | ⏳ |
| `@mutation(type:)` | ✅ | ⏳ |
| `@service` | ✅ | ⚠️ rows stub |
| `@tableMethod` | ✅ | ⏳ |
| `@externalField` | ✅ | ⏳ |
| `@discriminate` / `@discriminator` | ✅ | ⏳ |
| `@multitableReference` | ✅ | ⏳ |
| `@notGenerated` | ✅ | ✅ |
| `@error` | ✅ | ❌ |
| `@enum` | ✅ | ❌ |
| Apollo Federation `@key` | ✅ | ⏳ |

## Architecture differences (not parity gaps)

The rewrite deliberately omits some generated artifacts from the legacy:

- **No DTOs.** DataFetchers return `Result<Record>` directly; legacy generates a `*DTO` class per GraphQL type and `*Transformer` / `*RecordMapper` / `*JavaRecordMapper` for mapping.
- **No `*Conditions` class per type.** Filter conditions are inlined into the fetcher body; legacy generates a separate `<Type>Conditions` class holding the WHERE-clause methods.

---

¹ `PlatformIdField` is being replaced by synthesized `NodeIdField` via `docs/planning/legacy-platform-id.md`; both work during the transition.  
² `ServiceTableField` passes `ValidateMojo` but the rows method body throws `UnsupportedOperationException` at runtime. Unlike the ⏳ entries, the build does **not** reject schemas using it.  
³ `RecordLookupTableField` is also blocked on the `BatchKey.ObjectBased` model decision (roadmap Backlog).
