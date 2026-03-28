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
| **Split** | `@splitQuery` on a table-mapped field | New scope, connected via DataLoader |
| **Lift** | Field on a result-mapped type points to a table-mapped type | New scope, connected via DataLoader + LiftCondition |

### Scope Interaction

Every field interacts with the Graphitron scope in two orthogonal dimensions.

**Creates vs Reuses** — mutually exclusive. Does the field start a new scope or participate in the current one?

| | Meaning |
|---|---|
| **Creates** | Starts a new Graphitron scope (new SQL statement) |
| **Reuses** | Contributes QueryParts to the current SQL statement |

**Carries vs Terminates** — mutually exclusive. What happens to the scope for child fields?

| | Meaning |
|---|---|
| **Carries** | Keeps the current scope available — child fields can participate in the same SQL statement |
| **Terminates** | Ends Graphitron's projection control for this branch — child fields cannot participate in the current scope |

| Field type | Creates / Reuses | Carries / Terminates |
|---|---|---|
| Root query fields (`TableQueryField`, `LookupQueryField`, etc.) | Creates | Carries |
| `ServiceQueryField`, `ServiceMutationField` | Creates | Terminates |
| Mutation fields (`InsertMutationField`, `UpdateMutationField`, `UpsertMutationField`) | Creates | Carries |
| `DeleteMutationField` | Creates | Terminates |
| `TableReferenceField`, `TableMethodField` | Reuses | Carries |
| `InterfaceReferenceField`, `UnionReferenceField` | Reuses | Carries |
| `NestingField` | Reuses | Carries |
| `ColumnField`, `ColumnReferenceField`, `RelayNodeIdField`, `RelayNodeIdReferenceField` | Reuses | Terminates |
| `FieldMethodField` | Reuses | Terminates |
| `ConstructorField` | Reuses | Terminates |
| `SplitField`, `SplitLookupField` | Creates | Terminates |
| `ServiceField` | Creates | Terminates |
| `LiftField` (result-mapped source) | Creates | Carries |
| `PropertyField` (result-mapped source) | Reuses | Terminates |

LiftCondition applies when a field Terminates and its return type is table-mapped, or when there is no active scope and the return type is table-mapped.

### Conditions

Conditions are **properties of fields**, not field types.

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | Structural join — how two types are related | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |
| **LiftCondition** | Reconnects a result to a target table | `@condition` on lift, FK match, or automatic (TableRecord) |

Any field with a table target can carry a reference condition and/or a filter condition. LiftCondition applies when a field Terminates and its return type is table-mapped, or when there is no active scope and the return type is table-mapped. If the return type is result-mapped, lift occurs later via `LiftField` on the result-mapped type's fields.

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
│   ├── TableMappedChildField
│   │   ├── ColumnField
│   │   ├── ColumnReferenceField
│   │   ├── RelayNodeIdField
│   │   ├── RelayNodeIdReferenceField
│   │   ├── TableReferenceField
│   │   ├── TableMethodField
│   │   ├── InterfaceReferenceField
│   │   │   ├── SingleTableInterfaceReferenceField
│   │   │   └── MultiTableInterfaceReferenceField
│   │   ├── UnionReferenceField
│   │   ├── NestingField
│   │   ├── ConstructorField
│   │   ├── SplitLookupField
│   │   ├── SplitField
│   │   ├── ServiceField
│   │   └── FieldMethodField
│   └── ResultMappedChildField
│       ├── PropertyField
│       ├── LiftField
│       └── ServiceField
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

### Child fields — table-mapped source

Fields on a `@table` type.

#### Reuses + Carries (in-scope, scope available to children)

| Field type | Description |
|---|---|
| `TableReferenceField` | Table-mapped target. Graphitron handles projection. Wrapping is a spec property. |
| `TableMethodField` | `@tableMethod` — developer provides a filtered `Table<?>` matching the target table type. Graphitron joins it using the same reference condition logic as `TableReferenceField`, then handles all projection, ordering, pagination, and nested scopes within the same scope. Preferred over `ServiceField` whenever the logic can be expressed as a filtered table. Wrapping is a spec property. |
| `SingleTableInterfaceReferenceField` | Single-table interface target. Wrapping is a spec property. |
| `MultiTableInterfaceReferenceField` | Multi-table interface target. Wrapping is a spec property. |
| `UnionReferenceField` | Union target. Wrapping is a spec property. |
| `NestingField` | Target inherits the source table context, producing a level of nesting. |

#### Reuses + Terminates (in-scope, no further projection)

| Field type | Description |
|---|---|
| `ColumnField` | Bound to a column on the source table. |
| `ColumnReferenceField` | Bound to a column on a joined target table. |
| `RelayNodeIdField` | Encodes the Relay `Node.id` for the source table row. |
| `RelayNodeIdReferenceField` | Encodes the Relay `Node.id` for a joined target table row. |
| `FieldMethodField` | `@externalField` — developer provides a jOOQ `Field<?>` (scalar, `row(...)`, or `multiset(...)`). Included in the current SELECT but Graphitron does not project through it. LiftCondition applies if return type is table-mapped. |
| `ConstructorField` | *(planned)* Populates the target object based on constructor mapping. Graphitron does not project through it. |

#### Creates + Terminates (new scope, exits current)

| Field type | Trigger | Mechanism |
|---|---|---|
| `SplitLookupField` | `@splitQuery` + `@lookupKey` on argument | New scope via DataLoader |
| `SplitField` | `@splitQuery` + table target | New scope via DataLoader |
| `ServiceField` | `@service` | Private scope. LiftCondition applies if return type is table-mapped; if result-mapped, lift occurs on child fields. From table-mapped source, Graphitron controls the input and can adapt what is passed to the service. |

---

### Child fields — result-mapped source

Fields on a `@record` type. Graphitron only validates types and generates RuntimeWiring — no SQL is generated — until a scope transition is encountered.

| Field type | Creates / Reuses | Carries / Terminates | Description |
|---|---|---|---|
| `PropertyField` | Reuses | Terminates | Reads a scalar or nested record property. Generates a trivial data fetcher. |
| `LiftField` | Creates | Carries | `@splitQuery` on a field with a table-mapped return type. Forces a new scope via DataLoader + LiftCondition. |
| `ServiceField` | Creates | Terminates | `@service` — input is locked to whatever the record carries; Graphitron cannot adapt it. |

---

## Field Matrix

### Query fields

| Target | Cardinality | Field type |
|---|---|---|
| Table-mapped | Any | `TableQueryField`, `LookupQueryField` (@lookupKey), `TableMethodQueryField` (@tableMethod) |
| Single-table Interface | List / Connection | `SingleTableInterfaceQueryField` |
| Multi-table Interface | List / Connection | `MultiTableInterfaceQueryField` |
| Union | List / Connection | `UnionQueryField` |
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

### Child fields — table-mapped source

| Target | Reuses+Carries | Reuses+Terminates | Creates+Terminates |
|---|---|---|---|
| Table-mapped | `TableReferenceField`, `TableMethodField` | — | `SplitField`, `SplitLookupField` |
| Interface | `SingleTableInterfaceReferenceField`, `MultiTableInterfaceReferenceField` | — | — |
| Union | `UnionReferenceField` | — | — |
| Inherited table | `NestingField` | — | — |
| Scalar (own table) | — | `ColumnField`, `RelayNodeIdField` | — |
| Scalar (via join) | — | `ColumnReferenceField`, `RelayNodeIdReferenceField` | — |
| jOOQ Field<?> | — | `FieldMethodField` | — |
| Service | — | — | `ServiceField` |
| Planned | — | `ConstructorField` | — |

### Child fields — result-mapped source

| Field type | Creates / Reuses | Carries / Terminates |
|---|---|---|
| `PropertyField` | Reuses | Terminates |
| `LiftField` | Creates | Carries |
| `ServiceField` | Creates | Terminates |
