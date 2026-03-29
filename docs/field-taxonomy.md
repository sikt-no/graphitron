# Graphitron Field Taxonomy

## Core Vocabulary

### Source Context

Every field has a source context — the type on which it is defined. Source context determines how much Graphitron can do.

| Source context | Directive | What Graphitron does |
|---|---|---|
| **Unmapped** | *(none — root types: Query, Mutation)* | Entry point. No SQL yet. |
| **Table-mapped** | `@table` | Full Graphitron mode: SQL generation, projection, joins. |
| **Result-mapped** | `@record` | Validation + RuntimeWiring only, until a scope transition. |

### Scope Transitions

A Graphitron scope is one SQL statement. Transitions are the key structural events.

| Transition | Trigger | Mechanism |
|---|---|---|
| **Enter** | First table-mapped type reached from an unmapped root | New Graphitron scope starts |
| **Split** | `@splitQuery` on any child field | New scope, connected via DataLoader |
| **Lift** | `TableField` (or any Carries field) on a result-mapped type | New scope, connected via DataLoader + LiftCondition. `@splitQuery` is redundant here and an error. |

### Scope Interaction

Every field interacts with the Graphitron scope in two dimensions.

**Creates vs Reuses** is derived from context — it is not an intrinsic property of the field type:

| Context | Creates / Reuses |
|---|---|
| Root field (unmapped) | Always Creates |
| Result-mapped child field | Always Creates — no scope exists to reuse |
| Table-mapped child field | Reuses by default; `@splitQuery` promotes to Creates |
| `ServiceField` (any context) | Always Creates — private scope |

**Carries vs Terminates** is intrinsic to the field type — it determines whether child fields can participate in the current scope:

| | Meaning |
|---|---|
| **Carries** | The scope remains available — child fields can participate in the same SQL statement |
| **Terminates** | Graphitron's projection control ends here — child fields cannot participate in the current scope |

| Field type | Carries / Terminates |
|---|---|
| Root query fields, `InsertMutationField`, `UpdateMutationField`, `UpsertMutationField` | Carries |
| `DeleteMutationField`, `ServiceQueryField`, `ServiceMutationField` | Terminates |
| `TableField`, `TableMethodField`, `InterfaceField`, `UnionField`, `NestingField` | Carries |
| `ColumnField`, `ColumnReferenceField`, `RelayNodeIdField`, `RelayNodeIdReferenceField` | Terminates |
| `ComputedField`, `ConstructorField`, `ServiceField`, `PropertyField` | Terminates |

LiftCondition applies when a field Terminates and its return type is table-mapped, or when there is no active scope and the return type is table-mapped.

### Conditions and structural properties

Conditions and structural properties are **properties of fields**, not field types.

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | Structural join — how two types are related | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |
| **LiftCondition** | Reconnects a result to a target table | `@condition` on lift, FK match, or automatic (TableRecord) |
| **`@splitQuery`** | Promotes a Reuses field to Creates — DataLoader instead of join | `@splitQuery` directive |
| **`@lookupKey`** | Enables lookup semantics: strict row-to-key ordering, exact count enforcement, no pagination | `@lookupKey` on an argument |

Any field with a table target can carry a reference condition and/or a filter condition. LiftCondition applies when a field Terminates and its return type is table-mapped, or when there is no active scope and the return type is table-mapped. If the return type is result-mapped, lift occurs later when a Carries field on the result-mapped type is reached.

`@splitQuery` and `@lookupKey` are orthogonal. A field can have neither, either, or both.

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
│   │   ├── RelayNodeQueryField
│   │   ├── EntityQueryField
│   │   ├── InterfaceQueryField
│   │   │   ├── SingleTableInterfaceQueryField
│   │   │   └── MultiTableInterfaceQueryField
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
│   ├── RelayNodeIdField
│   ├── RelayNodeIdReferenceField
│   ├── TableField
│   ├── TableMethodField
│   ├── InterfaceField
│   │   ├── SingleTableInterfaceField
│   │   └── MultiTableInterfaceField
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
| `RelayNodeQueryField` | `Query.node(id:)` — Relay spec | Table-mapped via global ID |
| `EntityQueryField` | `Query._entities(representations:)` — Apollo Federation | Table-mapped |
| `SingleTableInterfaceQueryField` | Target interface has `@table` + `@discriminate`; implementing types have `@table` + `@discriminator` | Single-table interface, cardinality is spec property |
| `MultiTableInterfaceQueryField` | Target interface has no directives; implementing types have `@table` | Multi-table interface, cardinality is spec property |
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

#### Carries

| Field type | Valid source contexts | Description |
|---|---|---|
| `TableField` | Table-mapped, result-mapped | Table-mapped target. Graphitron handles projection, ordering, pagination, and nested scopes. In result-mapped context, always Creates via DataLoader + LiftCondition. Cardinality is a spec property. |
| `TableMethodField` | Table-mapped, result-mapped | `@tableMethod` — developer provides a filtered `Table<?>`. Graphitron joins it using the same logic as `TableField`. Preferred over `ServiceField` when the logic can be expressed as a filtered table. Cardinality is a spec property. |
| `SingleTableInterfaceField` | Table-mapped, result-mapped | Single-table interface target. Cardinality is a spec property. |
| `MultiTableInterfaceField` | Table-mapped, result-mapped | Multi-table interface target. Cardinality is a spec property. |
| `UnionField` | Table-mapped, result-mapped | Union target. Cardinality is a spec property. |
| `NestingField` | Table-mapped | Target inherits the source table context, producing a level of nesting. |

#### Terminates

| Field type | Valid source contexts | Description |
|---|---|---|
| `ColumnField` | Table-mapped | Bound to a column on the source table. |
| `ColumnReferenceField` | Table-mapped | Bound to a column on a joined target table. |
| `RelayNodeIdField` | Table-mapped | Encodes the Relay `Node.id` for the source table row. |
| `RelayNodeIdReferenceField` | Table-mapped | Encodes the Relay `Node.id` for a joined target table row. |
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
| Single-table Interface | Any | `SingleTableInterfaceQueryField` |
| Multi-table Interface | Any | `MultiTableInterfaceQueryField` |
| Union | Any | `UnionQueryField` |
| Special | Single | `RelayNodeQueryField`, `EntityQueryField` |
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
| Interface | Table-mapped or result-mapped | `SingleTableInterfaceField`, `MultiTableInterfaceField` | — |
| Union | Table-mapped or result-mapped | `UnionField` | — |
| Inherited table | Table-mapped | `NestingField` | — |
| Scalar (own table) | Table-mapped | — | `ColumnField`, `RelayNodeIdField` |
| Scalar (via join) | Table-mapped | — | `ColumnReferenceField`, `RelayNodeIdReferenceField` |
| jOOQ Field<?> | Table-mapped | — | `ComputedField` |
| Service | Table-mapped or result-mapped | — | `ServiceField` |
| Record property | Result-mapped | — | `PropertyField` |
| Planned | Table-mapped | — | `ConstructorField` |
