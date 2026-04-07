# ADR: jOOQ-free sealed interface taxonomy

## Context

The rewrite module represents a GraphQL schema as a taxonomy of sealed interfaces
(`GraphitronField`, `GraphitronType`, `TableRef`, `ColumnRef`, `InputFieldRef`,
`ArgumentRef`, `ReferencePathElementRef`). This taxonomy is produced by
`GraphitronSchemaBuilder` from a `TypeDefinitionRegistry` and a jOOQ catalog, then
consumed by validators and code generators.

## Decision

**Taxonomy records must not store raw jOOQ types.**
`org.jooq.Table<?>`, `org.jooq.Field<?>`, and `org.jooq.ForeignKey<?,?>` are
permitted only inside `JooqCatalog` and `GraphitronSchemaBuilder` — the single
schema-parsing boundary. Downstream code (validators, spec builders, code
generators) works exclusively with primitives, strings, and booleans.

The specific changes:

| Was | Replaced with |
|-----|--------------|
| `ResolvedTable.table()` → `Table<?>` | `ResolvedTable.hasPrimaryKey()` → `boolean` |
| `ColumnEntry.column()` → `Field<?>` | `ColumnEntry.columnClass()` → `String` |
| `ResolvedColumn.column()` → `Field<?>` | `ResolvedColumn.columnClass()` → `String` |
| `TableInputField.column()` → `Field<?>` | `TableInputField.columnClass()` → `String` |
| `ColumnArg.column()` → `Field<?>` | `ColumnArg.columnClass()` → `String` |
| `FkRef.key()` → `ForeignKey<?,?>` | `FkRef.fkName/keyTableSqlName/fkTableSqlName` → `String` |
| `FkWithConditionRef.key()` → `ForeignKey<?,?>` | same string fields |

Factory methods in `GraphitronSchemaBuilder` accept jOOQ types to extract the
needed information at construction time, then discard the jOOQ object.

## Reasoning

### Completeness of the taxonomy

The primary motivation is to **force a complete taxonomy**. When a record carries
a raw `Table<?>` or `Field<?>`, any downstream code that needs new information can
bypass the taxonomy by calling `.getPrimaryKey()`, `.getType()`, or any other jOOQ
API method directly on the stored object. This creates an implicit dependency on
jOOQ's reflection-heavy API throughout the pipeline.

By removing jOOQ types from records, every piece of information a generator or
validator needs must be **explicitly declared as a record component**. If something
is missing, the right fix is to extend the taxonomy — not to reach for a stored
jOOQ handle.

### Enforces the reflection boundary structurally

`JooqCatalog` is the only permitted entry point for reflection. Storing jOOQ
objects in taxonomy records made it possible to call reflective methods anywhere
a record was in scope. Removing them makes the boundary machine-enforceable: code
outside `JooqCatalog` and `GraphitronSchemaBuilder` simply cannot perform
reflection because it has no jOOQ handles to reflect on.

### Simpler tests and a cleaner data model

Records containing only strings, booleans, and primitives are trivial to
construct in tests without a live database or jOOQ catalog. The taxonomy becomes
a pure intermediate representation that could in principle be produced from a
different database layer.

## Consequences

- `GraphitronSchemaBuilder` is the only class that imports `org.jooq.Table`,
  `org.jooq.Field`, or `org.jooq.ForeignKey` in production code.
- Adding new information to the taxonomy requires adding a record component and
  updating the builder — which is the correct place to do it.
- Tests for validators and spec builders no longer depend on a jOOQ catalog; they
  construct taxonomy records directly with literal values.
