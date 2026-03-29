# Graphitron Field Taxonomy

## Core Vocabulary

### Source Context

Every field has a source context — the type on which it is defined.

| Source context | Directive | What Graphitron generates |
|---|---|---|
| **Unmapped** | *(none — Query, Mutation)* | Entry point. No SQL yet. |
| **Table-mapped** | `@table` | Full SQL generation — queries, joins, projections. |
| **Result-mapped** | `@record` | Runtime wiring only. Graphitron validates types and wires data fetchers, but generates no SQL until a new scope starts. |

### Scope

A Graphitron scope corresponds to one SQL statement. Fields within a scope contribute to the same query. When a scope boundary is crossed, Graphitron starts a new SQL statement and connects results via a DataLoader.

Scope boundaries:

| Boundary | Trigger |
|---|---|
| **Enter** | An unmapped root field reaches a table-mapped type — the first scope starts |
| **Split** | `@splitQuery` on a child field — new scope via DataLoader |
| **Lift** | A child field on a result-mapped type has a table-mapped return type — new scope via DataLoader, connected using a LiftCondition |

### Conditions

Conditions are properties of fields, not field types.

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | How two tables are joined | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |
| **LiftCondition** | Reconnects a result-mapped type back to a target table to start a new scope | FK match, `@condition`, or automatic for `TableRecord` returns |

### Structural Properties

| Property | Effect |
|---|---|
| **`@splitQuery`** | Forces a new scope via DataLoader instead of a SQL join. Valid on table-mapped child fields; an error on result-mapped fields, which always start a new scope implicitly. |
| **`@lookupKey`** | Adds strict lookup semantics: 1:1 row-to-key matching, count enforcement, no pagination. Can be used with or without `@splitQuery`. |

---

## Field Naming Convention

Field type names encode their source context:

- Fields on `Query` → `*QueryField`
- Fields on `Mutation` → `*MutationField`
- Fields on non-root types → named by what they do (no suffix)

`RootField` exists as a structural intermediate in the hierarchy but does not appear in leaf type names. Subscription is out of scope.

**Only mutation fields are permitted to make changes to the database.** Query and child fields are read-only.

---

## Sealed Interface Hierarchy

```
FieldSpec
├── RootField
│   ├── QueryField
│   │   ├── LookupQueryField
│   │   ├── TableQueryField
│   │   ├── TableMethodQueryField
│   │   ├── NodeQueryField
│   │   ├── EntityQueryField
│   │   ├── TableInterfaceQueryField
│   │   ├── InterfaceQueryField
│   │   ├── UnionQueryField
│   │   └── ServiceQueryField
│   └── MutationField
│       ├── InsertMutationField
│       ├── UpdateMutationField
│       ├── DeleteMutationField
│       ├── UpsertMutationField
│       └── ServiceMutationField
├── ChildField
│   ├── ColumnField
│   ├── ColumnReferenceField
│   ├── NodeIdField
│   ├── NodeIdReferenceField
│   ├── TableField
│   ├── TableMethodField
│   ├── TableInterfaceField
│   ├── InterfaceField
│   ├── UnionField
│   ├── NestingField
│   ├── ConstructorField
│   ├── ServiceField
│   ├── ComputedField
│   └── PropertyField
├── NotGeneratedField
└── UnclassifiedField
```

---

## Field Types

### `NotGeneratedField`

A field annotated with `@notGenerated`. Graphitron recognises it, classifies it, and includes it in the spec — but produces no data fetcher and no runtime wiring entry for it. The developer is responsible for registering wiring externally. Valid in any source context.

---

### `UnclassifiedField`

A field that does not match any known type. A schema containing `UnclassifiedField`s is invalid — Graphitron terminates with an error identifying which fields need to be fixed. No code is generated.

---

### Query fields — unmapped source, read-only

Fields on the `Query` type. They have no source context. All create a new Graphitron scope or enter service mode.

| Field type | Trigger | Target |
|---|---|---|
| `LookupQueryField` | `@lookupKey` on an argument | Table-mapped, cardinality is spec property |
| `TableQueryField` | General table query | Table-mapped, cardinality is spec property |
| `TableMethodQueryField` | `@tableMethod` — developer provides a filtered `Table<?>` | Table-mapped. Graphitron handles all projection, ordering, pagination, and nested scopes within the created scope. Preferred over `ServiceQueryField` when the logic can be expressed as a filtered table. Cardinality is spec property. |
| `NodeQueryField` | `Query.node(id:)` — Relay spec | Table-mapped via global ID |
| `EntityQueryField` | `Query._entities(representations:)` — Apollo Federation | Table-mapped |
| `TableInterfaceQueryField` | Target interface has `@table` + `@discriminate`; implementing types have `@table` + `@discriminator` | Single-table interface, cardinality is spec property |
| `InterfaceQueryField` | Target interface has no directives; implementing types have `@table` | Multi-table interface, cardinality is spec property |
| `UnionQueryField` | Target union; all member types have `@table` | Multi-table union, cardinality is spec property |
| `ServiceQueryField` | `@service` | Private scope. LiftCondition applies if return type is table-mapped; if result-mapped, lift occurs on child fields. |

---

### Mutation fields — unmapped source, write

Fields on the `Mutation` type. These are the only fields permitted to write to the database. `ServiceMutationField` is the `@service` equivalent — the service method is permitted to mutate. All mutation fields can provide access back into the graph via their return type: LiftCondition if the return type is table-mapped, lift on child fields if result-mapped.

`DeleteMutationField` is the one exception: deleted rows no longer exist in the database, so querying them back is not possible. The return type is therefore a simple confirmation — a success flag, a count, or an ordered echo of the input. Input/output ordering follows plural identifying root field rules so batch deletes are positionally consistent.

| Field type | Operation | Return |
|---|---|---|
| `InsertMutationField` | `@mutation(typeName: INSERT)` | Table-mapped or result-mapped (lift applies) |
| `UpdateMutationField` | `@mutation(typeName: UPDATE)` | Table-mapped or result-mapped (lift applies) |
| `DeleteMutationField` | `@mutation(typeName: DELETE)` | Success flag, count, or ordered input echo. No lift. |
| `UpsertMutationField` | `@mutation(typeName: UPSERT)` | Table-mapped or result-mapped (lift applies) |
| `ServiceMutationField` | `@service` — write logic too complex for Graphitron to generate directly | Lift rule applies as for all service fields |

---

### Child fields

Child fields carry a `sourceContext` property — table-mapped (`@table`) or result-mapped (`@record`). Source context determines Creates/Reuses (see Scope Interaction above). Carries/Terminates is intrinsic to the field type.

`@splitQuery` is valid on any table-mapped child field, promoting it from Reuses to Creates. It is an error on result-mapped child fields — those always Create implicitly. `@lookupKey` adds strict lookup semantics on top of `@splitQuery`.

#### Graphitron projects through these fields

| Field type | Valid source contexts | Description |
|---|---|---|
| `TableField` | Table-mapped, result-mapped | Table-mapped target. Graphitron handles projection, ordering, pagination, and nested scopes. In result-mapped context, starts a new scope via DataLoader + LiftCondition. Cardinality is a spec property. |
| `TableMethodField` | Table-mapped, result-mapped | `@tableMethod` — developer provides a filtered `Table<?>`. Graphitron joins it using the same logic as `TableField`. Preferred over `ServiceField` when the logic can be expressed as a filtered table. Cardinality is a spec property. |
| `TableInterfaceField` | Table-mapped, result-mapped | Single-table interface target. Cardinality is a spec property. |
| `InterfaceField` | Table-mapped, result-mapped | Multi-table interface target. Cardinality is a spec property. |
| `UnionField` | Table-mapped, result-mapped | Union target. Cardinality is a spec property. |
| `NestingField` | Table-mapped | Target inherits the source table context, producing a level of nesting. |

#### Graphitron does not project through these fields

| Field type | Valid source contexts | Description |
|---|---|---|
| `ColumnField` | Table-mapped | Bound to a column on the source table. |
| `ColumnReferenceField` | Table-mapped | Bound to a column on a joined target table. |
| `NodeIdField` | Table-mapped | `@nodeId` — encodes a globally unique Relay ID for a row of the source type by composing its key columns (from `@node(keyColumns:...)`). The encoded ID can be passed to `Query.node` to re-fetch this object. The source type must have `@node`. |
| `NodeIdReferenceField` | Table-mapped | `@nodeId(typeName: ...)` — joins to the target type's table and encodes a globally unique Relay ID for that row. The ID can be passed to `Query.node` to fetch the related object. Requires a join; parallel to `ColumnReferenceField`. |
| `ComputedField` | Table-mapped | `@computed` — developer provides a jOOQ `Field<?>` (scalar, `row(...)`, or `multiset(...)`). Included in the current SELECT but Graphitron does not project through it. LiftCondition applies if return type is table-mapped. |
| `ConstructorField` | Table-mapped | *(planned)* A new directive carries the field-to-constructor-parameter mapping. Graphitron does not project through it. |
| `ServiceField` | Table-mapped, result-mapped | `@service` — always Creates (private scope). From table-mapped source, Graphitron controls the input and can adapt what is passed to the service. From result-mapped source, input is locked to whatever the record carries. LiftCondition applies if return type is table-mapped. |
| `PropertyField` | Result-mapped | Reads a scalar or nested record property. Generates a trivial data fetcher. No SQL interaction. |

---

## Field Matrix

### Query fields

| Target | Cardinality | Field type |
|---|---|---|
| Table-mapped | Any | `TableQueryField`, `LookupQueryField` (@lookupKey), `TableMethodQueryField` (@tableMethod) |
| Single-table Interface | Any | `TableInterfaceQueryField` |
| Multi-table Interface | Any | `InterfaceQueryField` |
| Union | Any | `UnionQueryField` |
| Special | Single | `NodeQueryField`, `EntityQueryField` |
| Service | Any | `ServiceQueryField` |

### Mutation fields

| Field type | Operation |
|---|---|
| `InsertMutationField` | INSERT |
| `UpdateMutationField` | UPDATE |
| `DeleteMutationField` | DELETE |
| `UpsertMutationField` | UPSERT |
| `ServiceMutationField` | @service (permitted to write) |

### Child fields

`@splitQuery` can be applied to any table-mapped Carries field, promoting it to Creates. `@lookupKey` adds lookup semantics on top. `ServiceField` always Creates regardless of source context.

| Target | Source context | Carries | Terminates |
|---|---|---|---|
| Table-mapped | Table-mapped or result-mapped | `TableField`, `TableMethodField` | — |
| Interface | Table-mapped or result-mapped | `TableInterfaceField`, `InterfaceField` | — |
| Union | Table-mapped or result-mapped | `UnionField` | — |
| Inherited table | Table-mapped | `NestingField` | — |
| Column (own table) | Table-mapped | — | `ColumnField`, `NodeIdField` |
| Column (via join) | Table-mapped | — | `ColumnReferenceField`, `NodeIdReferenceField` |
| jOOQ Field<?> | Table-mapped | — | `ComputedField` |
| Service | Table-mapped or result-mapped | — | `ServiceField` |
| Record property | Result-mapped | — | `PropertyField` |
| Planned | Table-mapped | — | `ConstructorField` |
