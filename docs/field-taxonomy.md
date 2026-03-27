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

### Conditions

Conditions are **properties of fields**, not field types.

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | Structural join — how two types are related | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |
| **LiftCondition** | Reconnects a result to a target table after a mutation or service call | `@condition` on lift, FK match, or automatic (TableRecord) |

Any field with a table target can carry a reference condition and/or a filter condition. LiftCondition applies to any service field whose return type is table-mapped. If the return type is result-mapped, no lift occurs on the service field itself — it occurs later via `LiftChildField` on the result-mapped type's fields.

---

## Field Naming Convention

Field type names encode their source context:

- Fields on `Query` → `*QueryField`
- Fields on `Mutation` → `*MutationField`
- Fields on non-root types → `*ChildField`

`RootField` exists as a structural intermediate in the hierarchy but does not appear in leaf type names. Subscription is out of scope.

Fields whose name contains `Query` start a new Graphitron scope.

**Only mutation fields are permitted to make changes to the database.** Query and child fields are read-only.

---

## Sealed Interface Hierarchy

```
FieldSpec
├── RootField
│   ├── QueryField        (fields on Query)
│   └── MutationField     (fields on Mutation)
├── ChildField
│   ├── TableMappedChildField
│   └── ResultMappedChildField
└── UnclassifiedField
```

---

## Field Types

### `UnclassifiedField`

A field that does not match any known type. A schema containing `UnclassifiedField`s is invalid — Graphitron terminates with an error identifying which fields need to be fixed. No code is generated.

---

### Query fields — unmapped source, read-only

Fields on the `Query` type. They have no source context. All start a new Graphitron scope or enter service mode.

#### Graphitron scope entry

| Field type | Trigger | Target |
|---|---|---|
| `LookupQueryField` | `@lookupKey` on an argument | Single or list of table-mapped |
| `List/ConnQueryField` | List or Relay Connection | Table-mapped |
| `RelayNodeLookupQueryField` | `Query.node(id:)` — Relay spec | Table-mapped via global ID |
| `EntityLookupQueryField` | `Query._entities(representations:)` — Apollo Federation | Table-mapped |
| `List/ConnSingleTableInterfaceQueryField` | Target interface has `@table` + `@discriminate`; all implementing types have `@table` + `@discriminator` | Single-table interface |
| `List/ConnMultiTableInterfaceQueryField` | Target interface has no directives; all implementing types have `@table` | Multi-table interface |
| `List/ConnMultiTableUnionQueryField` | Target union; all member types have `@table` | Multi-table union |

#### Service mode

| Field type | Trigger | Target |
|---|---|---|
| `ServiceQueryField` | `@service` | Private scope. LiftCondition applies if return type is table-mapped; if result-mapped, lift occurs on child fields. |

---

### Mutation fields — unmapped source, write

Fields on the `Mutation` type. These are the only fields permitted to write to the database. `ServiceMutationField` is the `@service` equivalent — the service method is permitted to mutate. The same lift rule applies as for all service fields: LiftCondition if return type is table-mapped, otherwise lift occurs on child fields.

| Field type | Operation |
|---|---|
| `InsertMutationField` | `@mutation(typeName: INSERT)` |
| `UpdateMutationField` | `@mutation(typeName: UPDATE)` |
| `DeleteMutationField` | `@mutation(typeName: DELETE)` |
| `UpsertMutationField` | `@mutation(typeName: UPSERT)` |
| `ServiceMutationField` | `@service` — write logic too complex for Graphitron to generate directly |

---

### Child fields — table-mapped source

Fields on a `@table` type. They operate within the current Graphitron scope unless noted.

#### Scalar fields (in scope)

| Field type | Description |
|---|---|
| `ColumnChildField` | Bound to a column on the source table |
| `ColumnReferenceChildField` | Bound to a column on a joined target table |
| `RelayNodeIdChildField` | Encodes the Relay `Node.id` for the source table row |
| `RelayNodeIdReferenceChildField` | Encodes the Relay `Node.id` for a joined target table row |

#### Reference fields — object target (in scope)

| Field type | Target |
|---|---|
| `ReferenceChildField` | Single table-mapped object |
| `List/ConnReferenceChildField` | Collection of table-mapped objects |
| `List/ConnSingleTableInterfaceReferenceChildField` | Single-table interface (mirrors query variant) |
| `List/ConnMultiTableInterfaceReferenceChildField` | Multi-table interface (mirrors query variant) |
| `List/ConnMultiTableUnionReferenceChildField` | Multi-table union (mirrors query variant) |

#### Structural fields (in scope)

| Field type | Description |
|---|---|
| `NestingChildField` | Target inherits the source table context, producing a level of nesting without a new scope |
| `ConstructorChildField` | *(planned)* Populates the target object based on constructor mapping |

#### New scope fields

| Field type | Trigger | Mechanism |
|---|---|---|
| `LookupKeyQueryChildField` | `@splitQuery` + `@lookupKey` on argument | New scope via DataLoader |
| `ChildQueryChildField` | `@splitQuery` + list-wrapped table target | New scope via DataLoader |
| `TableRecordServiceChildField` | `@service` returning a `TableRecord` | New scope via DataLoader + automatic LiftCondition |

#### Escape fields (exit scope, no re-entry)

| Field type | Trigger | Description |
|---|---|---|
| `ServiceChildField` | `@service` | Private scope. LiftCondition applies if return type is table-mapped; if result-mapped, lift occurs on child fields. Graphitron controls the input from table-mapped source and can adapt what is passed to the service. |
| `FieldMethodChildField` | `@externalField` | Static method call; no scope |

---

### Child fields — result-mapped source

Fields on a `@record` type. Graphitron only validates types and generates RuntimeWiring — no SQL is generated — until a scope transition is encountered.

| Field type | Description |
|---|---|
| `RecordPropertyChildField` | Reads a scalar or nested record property from the result object. Generates a trivial data fetcher. |
| `LiftChildField` | `@splitQuery` pointing to a table-mapped type. Generates a DataLoader + LiftCondition, starting a new Graphitron scope. |
| `ServiceChildField` | `@service` — same type as from table-mapped source, but the input is locked to whatever the record carries; Graphitron cannot adapt it. |

---

## Field Matrix

### Query fields

| Target | Single | List / Connection |
|---|---|---|
| Table-mapped | `LookupQueryField` | `List/ConnQueryField`, `LookupQueryField` (plural) |
| Single-table Interface | — | `List/ConnSingleTableInterfaceQueryField` |
| Multi-table Interface | — | `List/ConnMultiTableInterfaceQueryField` |
| Multi-table Union | — | `List/ConnMultiTableUnionQueryField` |
| Special | `RelayNodeLookupQueryField`, `EntityLookupQueryField` | — |
| Service / scalar | `ServiceQueryField` | — |

### Mutation fields

| Field type | Operation |
|---|---|
| `InsertMutationField` | INSERT |
| `UpdateMutationField` | UPDATE |
| `DeleteMutationField` | DELETE |
| `UpsertMutationField` | UPSERT |
| `ServiceMutationField` | @service (permitted to write) |

### Child fields — table-mapped source

| Target | Single | List / Connection |
|---|---|---|
| Scalar (own table) | `ColumnChildField`, `RelayNodeIdChildField` | — |
| Scalar (via join) | `ColumnReferenceChildField`, `RelayNodeIdReferenceChildField` | — |
| Table-mapped | `ReferenceChildField` | `List/ConnReferenceChildField` |
| Single-table Interface | — | `List/ConnSingleTableInterfaceReferenceChildField` |
| Multi-table Interface | — | `List/ConnMultiTableInterfaceReferenceChildField` |
| Multi-table Union | — | `List/ConnMultiTableUnionReferenceChildField` |
| Inherited table | `NestingChildField` | — |
| New scope (@splitQuery) | `LookupKeyQueryChildField` | `ChildQueryChildField` |
| New scope (lift) | `TableRecordServiceChildField` | — |
| Escape | `ServiceChildField`, `FieldMethodChildField` | — |
| Planned | `ConstructorChildField` | — |

### Child fields — result-mapped source

| Target | Single | List / Connection |
|---|---|---|
| Scalar / nested record | `RecordPropertyChildField` | — |
| Table-mapped (lift) | `LiftChildField` | `LiftChildField` |
| Service | `ServiceChildField` | — |
