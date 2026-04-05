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

| Boundary | Trigger |
|---|---|
| **Enter** | An unmapped root field reaches a table-mapped type — the first scope starts |
| **Split** | `@splitQuery` on a `TableField` — new scope via DataLoader |
| **Lift** | A `TableField` on a result-mapped type — new scope via DataLoader, connected using a LiftCondition |

`@service` fields use a **private scope** — they create their own SQL statement independently and do not participate in any Graphitron-managed scope.

### Conditions

| Kind | Purpose | Source |
|---|---|---|
| **Reference condition** | How two tables are joined | `@reference` directive, FK metadata |
| **Filter condition** | Narrows the result set | `@condition` directive, arguments, cursor |
| **LiftCondition** | Reconnects a result-mapped type back to a target table to start a new scope | FK match, `@condition`, or automatic for `TableRecord` returns |

### Structural Properties

| Property | Effect |
|---|---|
| **`@splitQuery`** | Forces a new scope via DataLoader instead of a SQL join. Valid on `TableField`; an error on result-mapped fields, which always start a new scope implicitly. |
| **`@lookupKey`** | Adds strict lookup semantics: 1:1 row-to-key matching, count enforcement, no pagination. Orthogonal to `@splitQuery` — can be used with or without it. |

---

## Sealed Interface Hierarchies

### `GraphitronType`

See [`GraphitronType.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/type/GraphitronType.java). Every named GraphQL type is classified into a `GraphitronType`. This is the Java materialisation of the Source Context table above — it determines what Graphitron generates for a type and is the authoritative source of source context for all fields defined on it.

```
GraphitronType
├── TableType          (@table — full SQL generation)
├── ResultType         (@record — runtime wiring only)
├── RootType           (Query / Mutation — unmapped entry points)
├── TableInterfaceType (@table + @discriminate on interface)
├── InterfaceType      (no directives; implementors have @table)
├── UnionType          (all member types have @table)
├── ErrorType          (@error — maps Java exceptions to GraphQL error responses)
└── InputType          (GraphQL input object — carries field list for argument inspection)
```

### `GraphitronField`

See [`GraphitronField.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/field/GraphitronField.java), [`QueryField.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/field/QueryField.java), [`MutationField.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/field/MutationField.java), [`ChildField.java`](../graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/record/field/ChildField.java). Field names encode source: `*QueryField` (Query), `*MutationField` (Mutation), no suffix (child fields). Only mutation fields may write to the database. Subscription is out of scope.

```
GraphitronField
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
│   ├── PropertyField
│   └── MultitableReferenceField
├── NotGeneratedField
└── UnclassifiedField
```

---

## Field Types

### `NotGeneratedField`

`@notGenerated` — Graphitron classifies the field and includes it in the spec but produces no data fetcher. The developer registers wiring externally. Valid in any source context.

### `UnclassifiedField`

A field that does not match any known type. Graphitron terminates with an error. No code is generated.

---

### Query fields — unmapped source, read-only

All create a new Graphitron scope or enter private service scope. Five carry a wrapper spec property (Single / List / Connection) inside their `returnType`; fields noted as always-single do not.

| Field type | Trigger | Target | Wrapper |
|---|---|---|---|
| `LookupQueryField` | `@lookupKey` on an argument | Table-mapped | Always single |
| `TableQueryField` | General table query | Table-mapped | Single / List / Connection |
| `TableMethodQueryField` | `@tableMethod` — developer provides a filtered `Table<?>` | Table-mapped. Preferred over `ServiceQueryField` when logic fits a filtered table. | Single / List / Connection |
| `NodeQueryField` | `Query.node(id:)` — Relay spec | Table-mapped via global ID | Always single |
| `EntityQueryField` | `Query._entities(representations:)` — Apollo Federation | Table-mapped | Always single |
| `TableInterfaceQueryField` | Interface has `@table` + `@discriminate`; implementors have `@table` + `@discriminator` | Single-table interface | Single / List / Connection |
| `InterfaceQueryField` | Interface has no directives; implementors have `@table` | Multi-table interface | Single / List / Connection |
| `UnionQueryField` | All union member types have `@table` | Multi-table union | Single / List / Connection |
| `ServiceQueryField` | `@service` | Private scope. LiftCondition applies if return type is table-mapped; lift on child fields if result-mapped. | Always single |

---

### Mutation fields — unmapped source, write

The only fields permitted to write to the database. All support access back into the graph via their return type (LiftCondition if table-mapped, lift on child fields if result-mapped), except `DeleteMutationField` — deleted rows cannot be queried back. Its return is a success flag, count, or ordered input echo; batch ordering follows lookup field rules for positional consistency.

| Field type | Operation | Return |
|---|---|---|
| `InsertMutationField` | `@mutation(typeName: INSERT)` | Table-mapped or result-mapped (lift applies) |
| `UpdateMutationField` | `@mutation(typeName: UPDATE)` | Table-mapped or result-mapped (lift applies) |
| `DeleteMutationField` | `@mutation(typeName: DELETE)` | Success flag, count, or ordered input echo. No lift. |
| `UpsertMutationField` | `@mutation(typeName: UPSERT)` | Table-mapped or result-mapped (lift applies) |
| `ServiceMutationField` | `@service` — write logic too complex for Graphitron to generate | Lift rule applies as for service fields |

---

### Child fields

For each child field, source context is derived at generation time from `schema.type(parentTypeName)` — `TableType` means table-mapped, `ResultType` means result-mapped. Source context determines whether the field contributes to an existing scope or starts a new one.

#### Graphitron projects through these fields

| Field type | Valid source contexts | Description |
|---|---|---|
| `TableField` | Table-mapped, result-mapped | Table-mapped target. Graphitron handles projection, ordering, pagination, and nested scopes. In result-mapped context, starts a new scope via DataLoader + LiftCondition. Wrapper (Single / List / Connection) embedded in `returnType`. |
| `TableMethodField` | Table-mapped, result-mapped | `@tableMethod` — developer provides a filtered `Table<?>`. Graphitron joins it using the same logic as `TableField`. Preferred over `ServiceField` when the logic fits a filtered table. Wrapper embedded in `returnType`. |
| `TableInterfaceField` | Table-mapped, result-mapped | Single-table interface target. Wrapper embedded in `returnType`. |
| `InterfaceField` | Table-mapped, result-mapped | Multi-table interface target. Wrapper embedded in `returnType`. |
| `UnionField` | Table-mapped, result-mapped | Union target. Wrapper embedded in `returnType`. |
| `NestingField` | Table-mapped | Target inherits the source table context, producing a level of nesting. |

#### Graphitron does not project through these fields

| Field type | Valid source contexts | Description |
|---|---|---|
| `ColumnField` | Table-mapped | Bound to a column on the source table. |
| `ColumnReferenceField` | Table-mapped | Bound to a column on a joined target table. |
| `NodeIdField` | Table-mapped | `@nodeId` — encodes a globally unique Relay ID from the source type's key columns (`@node(keyColumns:...)`). Can be passed to `Query.node` to re-fetch this object. Source type must have `@node`. |
| `NodeIdReferenceField` | Table-mapped | `@nodeId(typeName: ...)` — joins to the target type's table and encodes a Relay ID for that row. Parallel to `ColumnReferenceField`. |
| `ComputedField` | Table-mapped | `@externalField` — developer provides a jOOQ `Field<?>` (scalar, `row(...)`, or `multiset(...)`). Included in the SELECT; Graphitron does not project through it. LiftCondition applies if return type is table-mapped. |
| `ConstructorField` | Table-mapped | *(planned)* A directive carries field-to-constructor-parameter mapping. Graphitron does not project through it. |
| `ServiceField` | Table-mapped, result-mapped | `@service` — private scope. From table-mapped source, Graphitron controls what is passed to the service. From result-mapped source, input is locked to what the record carries. LiftCondition applies if return type is table-mapped. |
| `PropertyField` | Result-mapped | Reads a scalar or nested record property. Trivial data fetcher. No SQL interaction. |
| `MultitableReferenceField` | — | `@multitableReference` — not supported in record-based output. Validator always reports an error. Users must convert to `@service` or equivalent. |

---

## Child Field Matrix

Quick reference: which field type applies given target and source context.

| Target | Source context | Projects through | Stops here |
|---|---|---|---|
| Table-mapped | Table-mapped or result-mapped | `TableField`, `TableMethodField` | — |
| Interface | Table-mapped or result-mapped | `TableInterfaceField`, `InterfaceField` | — |
| Union | Table-mapped or result-mapped | `UnionField` | — |
| Inherited table | Table-mapped | `NestingField` | — |
| Column (own table) | Table-mapped | — | `ColumnField`, `NodeIdField` |
| Column (via join) | Table-mapped | — | `ColumnReferenceField`, `NodeIdReferenceField` |
| SQL expression | Table-mapped | — | `ComputedField` |
| Service | Table-mapped or result-mapped | — | `ServiceField` |
| Record property | Result-mapped | — | `PropertyField` |
| Planned | Table-mapped | — | `ConstructorField` |
| Unsupported | — | — | `MultitableReferenceField` |

`@splitQuery` is valid on `TableField` with table-mapped source context. `@lookupKey` is orthogonal and can be combined with or without it.

---

## Generator Architecture

### Per-type select methods

Every `@table` type generates static field methods for its nested relationships, analogous to jOOQ's static `Field<T>` instances (`FILM.FILM_ID`, `FILM.TITLE`). These return `Field<Result<Record>>` (multiset, one-to-many) or `Field<Record>` (row, one-to-one) expressions composable into any SELECT clause.

Methods use GraphQL Java's native `SelectedField` — which carries both `getSelectionSet()` and `getArguments()` — with no custom wrapper.

```java
// Generated on a companion class, e.g. FilmFields
public static Field<Result<Record>> actors(Film film, SelectedField field) {
    return DSL.multiset(
        DSL.select(ActorFields.fields(ACTOR, field.getSelectionSet()))
           .from(ACTOR)
           .join(FILM_ACTOR).on(FILM_ACTOR.ACTOR_ID.eq(ACTOR.ACTOR_ID))
           .where(FILM_ACTOR.FILM_ID.eq(film.FILM_ID)
               .and(actorCondition(field.getArguments())))
           .orderBy(actorOrderBy(field.getArguments()))
    ).as("actors");
}

public static Field<Record> language(Film film, SelectedField field) { ... }
```

The projection method assembles the SELECT list, passing each sub-field's `SelectedField` directly. It takes the table alias so that correlated joins and aliased column references are correct:

```java
List<Field<?>> fields(Film film, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    sel.getFields("title").forEach(f -> fields.add(film.TITLE));
    sel.getFields("actors").forEach(f -> fields.add(actors(film, f)));
    sel.getFields("language").forEach(f -> fields.add(language(film, f)));
    return fields;
}
```

Two scope-establishing methods delegate to `filmFields`:

```java
// Starts a new SQL statement. Used by root queries, DataLoaders (split + lift), mutation read-back.
SelectFinalStep<Record> filmSelect(
    DSLContext ctx,
    DataFetchingFieldSelectionSet selectionSet,
    Condition condition,
    List<SortField<?>> orderBy
    // + pagination args for connection types
)

// Contributes to an existing statement as a multiset subquery.
Field<Result<Record>> filmNested(
    DataFetchingFieldSelectionSet selectionSet,
    Condition condition,
    List<SortField<?>> orderBy
)

// Overload for @tableMethod — developer supplies a filtered table.
Field<Result<Record>> filmNested(
    Table<FilmRecord> table,
    DataFetchingFieldSelectionSet selectionSet,
    Condition condition,
    List<SortField<?>> orderBy
)
```

Results are jOOQ `Record` types. Scalars via `record.get(TABLE.FIELD)`; nested via `record.get(nestedField)` where `nestedField` is `Field<Result<Record>>` or `Field<Record>`.

### Field type to method mapping

| Field type | Method |
|---|---|
| `TableQueryField` | `filmSelect` |
| `LookupQueryField` — single | `filmSelect` with key condition |
| `LookupQueryField` — batch DataLoader | `filmNested` per key (see below) |
| `TableField` — table-mapped, no `@splitQuery` | `filmNested` |
| `TableField` — table-mapped, `@splitQuery` | DataLoader → `filmSelect` with batch condition |
| `TableField` — result-mapped (lift) | DataLoader → `filmSelect` with LiftCondition |
| `TableMethodField` | `filmNested(developerTable, ...)` overload |
| `InterfaceField` | Union wrapper over each implementing type's `filmNested` |
| Mutation read-back | `filmSelect` with LiftCondition |

### LookupQueryField batch mapping

Batch DataLoader lookups use `filmNested` rather than `filmSelect`. Each input key drives one row in the outer query; the nested multiset produces the matching result. Positional alignment between input keys and output rows is guaranteed — even missing keys produce a row.

```sql
SELECT key, (SELECT film_fields FROM film WHERE film.id = key)
FROM (VALUES (1), (2), (3)) AS keys(key)
```

### InterfaceField union wrapper

`InterfaceField` generates a union wrapper that calls each implementing type's `filmNested` variant. The wrapper is itself a multiset subquery and can be nested into a parent statement like any other `filmNested` call.

### @defer support

A `TableField` with table-mapped source context follows a check-then-fetch pattern. The static field method (e.g. `actors(SelectedField)`) produces a named `Field<Result<Record>>` constant — `FILM_ACTORS_FIELD` below — used as the key both when embedding the multiset in the parent SELECT and when reading the result back in the resolver.

1. **Check**: `record.get(FILM_ACTORS_FIELD)` — non-null means the parent pre-fetched via `filmNested`. Return immediately.
2. **Fetch**: `null` means the field was not in the parent SELECT (deferred or standalone). Call `filmSelect`.

```java
// Generated resolver for Film.actors (TableField, table-mapped, no @splitQuery)
env -> {
    Record source = env.getSource();
    Result<Record> actors = source.get(FILM_ACTORS_FIELD);
    if (actors != null) return actors;
    return actorSelect(ctx, env.getSelectionSet(), referenceCondition(source), noOrder);
}
```

`filmNested` is an optimisation; `filmSelect` is the correctness guarantee. For `TableField` with result-mapped source context, the resolver is a separate data fetcher backed by a DataLoader — no parent record to check. jOOQ naturally distinguishes not-fetched (`null`) from fetched-but-empty (empty `Result<Record>`), so no custom result type is needed.
