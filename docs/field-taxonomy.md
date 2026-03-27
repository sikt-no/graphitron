# Graphitron Field Taxonomy

## Core Vocabulary

### Source Context

Every field has a source context — the type on which it is defined. Source context determines how much Graphitron can do.

| Source context | Directive | What Graphitron does |
|---|---|---|
| **Unmapped** | *(none — root types: Query, Mutation, Subscription)* | Entry point. No SQL yet. |
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
| **LiftCondition** | Reconnects a service result to a target table | `@condition` on lift, FK match, or automatic (TableRecord) |

Any field with a table target can carry a reference condition and/or a filter condition. LiftCondition is specific to lift fields.

---

## Field Naming Convention

All field types are named by **source context suffix**:

- Fields on root types (unmapped source): `*RootField`
- Fields on non-root types: `*ChildField`

Fields whose name contains `Query` start a new Graphitron scope.

---

## Field Types

### `UnclassifiedField`

A field that does not match any known type. A schema containing `UnclassifiedField`s is invalid — Graphitron terminates with an error identifying which fields need to be fixed. No code is generated.

---

### Root fields — unmapped source

These are fields on `Query`, `Mutation`, or `Subscription`. They have no source context.

#### Graphitron scope entry

| Field type | Trigger | Target |
|---|---|---|
| `LookupQueryRootField` | `@lookupKey` on an argument | Single or list of table-mapped |
| `List/ConnQueryRootField` | List or Relay Connection | Table-mapped |
| `RelayNodeLookupQueryRootField` | `Query.node(id:)` — Relay spec | Table-mapped via global ID |
| `EntityLookupQueryRootField` | `Query._entities(representations:)` — Apollo Federation | Table-mapped |
| `List/ConnSingleTableInterfaceQueryRootField` | Target interface has `@table` + `@discriminate`; all implementing types have `@table` + `@discriminator` | Single-table interface |
| `List/ConnMultiTableInterfaceQueryRootField` | Target interface has no directives; all implementing types have `@table` | Multi-table interface |
| `List/ConnMultiTableUnionQueryRootField` | Target union; all member types have `@table` | Multi-table union |

#### Service mode

| Field type | Trigger | Target |
|---|---|---|
| `ServiceRootField` | `@service` | Result-mapped or scalar; private scope |

---

### Child fields — table-mapped source

These are fields on a `@table` type. They operate within the current Graphitron scope unless noted.

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
| `List/ConnSingleTableInterfaceReferenceChildField` | Single-table interface (mirrors root variant) |
| `List/ConnMultiTableInterfaceReferenceChildField` | Multi-table interface (mirrors root variant) |
| `List/ConnMultiTableUnionReferenceChildField` | Multi-table union (mirrors root variant) |

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
| `ServiceChildField` | `@service`, non-liftable result | Private scope; no SQL from Graphitron |
| `FieldMethodChildField` | `@externalField` | Static method call; no scope |

---

### Child fields — result-mapped source

These are fields on a `@record` type. Graphitron only validates types and generates RuntimeWiring — no SQL is generated — until a scope transition is encountered.

| Field type | Description |
|---|---|
| `RecordPropertyChildField` | Reads a scalar or nested record property from the result object. Generates a trivial data fetcher. |
| `LiftChildField` | `@splitQuery` pointing to a table-mapped type. Generates a DataLoader + LiftCondition, starting a new Graphitron scope. |
| `ServiceChildField` | `@service` on a result-mapped type. New service call; same mechanism as from table-mapped source. |

---

## Field Matrix

### Root fields (unmapped source)

| Target | Single | List / Connection |
|---|---|---|
| Table-mapped | `LookupQueryRootField` | `List/ConnQueryRootField`, `LookupQueryRootField` (plural) |
| Single-table Interface | — | `List/ConnSingleTableInterfaceQueryRootField` |
| Multi-table Interface | — | `List/ConnMultiTableInterfaceQueryRootField` |
| Multi-table Union | — | `List/ConnMultiTableUnionQueryRootField` |
| Special | `RelayNodeLookupQueryRootField`, `EntityLookupQueryRootField` | — |
| Service / scalar | `ServiceRootField` | — |

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
