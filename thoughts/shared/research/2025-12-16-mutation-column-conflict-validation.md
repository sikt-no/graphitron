---
date: 2025-12-16T11:14:55+0100
researcher: Claude Code
git_commit: 5fbfcf976b0c158d62c408d397f61c1679f7b866
branch: Validate-jooq-columns
repository: graphitron
topic: "Validation of multiple mutation fields writing to the same jOOQ column"
tags: [research, codebase, mutation, validation, jooq, column-mapping]
status: complete
last_updated: 2025-12-16
last_updated_by: Claude Code
---

# Research: Validation of Multiple Mutation Fields Writing to the Same jOOQ Column

**Date**: 2025-12-16T11:14:55+0100
**Researcher**: Claude Code
**Git Commit**: 5fbfcf976b0c158d62c408d397f61c1679f7b866
**Branch**: Validate-jooq-columns
**Repository**: graphitron

## Research Question

How to validate that if multiple fields in a mutation write to the same jOOQ column, they must write the same value?

## Summary

**Graphitron already has this validation implemented.** The validation is performed at **runtime** (not compile-time/code-generation-time) through generated code in the mutation resolvers. The implementation is in `OperationMethodGenerator.validateInputs()` method (lines 358-466). When multiple GraphQL input fields map to the same database column, the generated resolver code checks that they provide equal values and throws an `IllegalArgumentException` if they differ.

## Detailed Findings

### Existing Implementation Location

The validation logic exists in:
- **File**: `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java`
- **Method**: `validateInputs()` (lines 358-466)
- **Supporting Record**: `ColumnFieldMapping` (lines 341-356)

### How the Validation Works

#### 1. Column Mapping Detection (Lines 361-394)

The method iterates through all jOOQ record inputs and builds a map of database columns to the fields that write to them:

```java
var columnsToFieldMappings = new LinkedHashMap<String, List<ColumnFieldMapping>>();
```

For each input field, it determines which column(s) it writes to:

- **Regular fields**: Uses `recordField.getUpperCaseName()` to get the column name from the `@field` directive
- **NodeId fields**: Unpacks composite keys via `FormatCodeBlocks.getReferenceNodeIdFields()` or `processedSchema.getKeyColumnsForNodeType()` to determine all columns the nodeId maps to

#### 2. Overlap Detection (Lines 396-402)

Filters to find columns that have more than one field writing to them:

```java
var overlappingColumns = columnsToFieldMappings.entrySet().stream()
    .filter(e -> e.getValue().size() > 1)
    .toList();
```

#### 3. Generated Validation Code (Lines 430-458)

For each overlapping column, the generator produces code that:
1. Extracts values from each field into local variables
2. Compares all pairs of values
3. Throws `IllegalArgumentException` if values differ while both are non-null

Example generated code:
```java
var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
var _val_actor_ACTOR_ID = _mi_in.getActor();
if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
    throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
}
```

### Supported Scenarios

The implementation handles these cases:

| Scenario | Description | Test File |
|----------|-------------|-----------|
| Regular field + NodeId | A plain field and a nodeId both map to same column | `assertInputColumns/schema.graphqls` |
| Multiple NodeIds | Two nodeId fields with overlapping key columns | `assertOverlappingNodeIds/schema.graphqls` |
| Three+ fields | More than two fields mapping to same column | `assertManyOverlappingFields/schema.graphqls` |
| Listed inputs | Array of input records, validated per-item | `assertListedInputColumns/schema.graphqls` |

### Example Schema Triggering Validation

```graphql
input FilmActorInput @table(name: "film_actor") {
    id: ID!                              # Composite nodeId includes ACTOR_ID
    actor: String @field(name: "actor_id")  # Explicit mapping to ACTOR_ID
}
```

Both `id` (unpacked from nodeId) and `actor` map to the `ACTOR_ID` column. The generated code validates they have the same value.

### Field-to-Column Mapping Mechanism

#### The @field Directive

Defined in `graphitron-common/src/main/resources/directives.graphqls`:

```graphql
directive @field(name: String!, javaName: String) on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
```

The `name` parameter specifies the database column name.

#### AbstractField Processing

In `AbstractField.java` (lines 32-40):
- GraphQL field name stored in `name`
- Column name from `@field(name: ...)` stored via `getUpperCaseName()`
- If no `@field` directive, defaults to uppercasing the GraphQL field name

#### NodeId Column Resolution

For `@nodeId` fields, columns are determined by:
1. Getting the referenced node type's key columns
2. For cross-table references, finding the foreign key and mapping through it

Relevant code in `validateInputs()` lines 374-381:
```java
if (!nodeType.getTable().getName().equalsIgnoreCase(targetTable)) {
    var foreignKey = NodeIdReferenceHelpers.getForeignKeyForNodeIdReference(recordField, processedSchema);
    columns = FormatCodeBlocks.getReferenceNodeIdFields(targetTable, nodeType, foreignKey);
} else {
    columns = processedSchema.getKeyColumnsForNodeType(nodeType).orElseGet(LinkedList::new);
}
```

## Code References

- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java:341-356` - ColumnFieldMapping record definition
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java:358-466` - validateInputs() method
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java:468-484` - getValueExtractionCode() helper
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/definitions/fields/AbstractField.java:32-40` - Field name resolution
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/datafetchers/standard/edit/NodeIdResolverTest.java:49-130` - Test cases for column conflict validation

## Architecture Documentation

### Code Generation Flow for Mutations

1. **Schema Parsing**: GraphQL schema processed into `InputDefinition` objects
2. **Field Analysis**: Each field's column mapping determined via `@field` directive or naming convention
3. **Validation Code Generation**: `OperationMethodGenerator.generate()` calls `validateInputs()` to generate runtime checks
4. **Runtime Execution**: Generated resolver throws `IllegalArgumentException` on conflict

### Key Classes in the Validation Pipeline

| Class | Responsibility |
|-------|----------------|
| `InputParser` | Parses mutation arguments and identifies jOOQ records |
| `OperationMethodGenerator` | Generates mutation resolver methods including validation |
| `ColumnFieldMapping` | Data structure tracking field-to-column mappings |
| `FormatCodeBlocks` | Generates jOOQ field access code blocks |
| `NodeIdReferenceHelpers` | Resolves foreign keys for cross-table nodeId references |

### Validation Characteristics

- **Type**: Runtime validation (not compile-time)
- **Timing**: Before database operation executes
- **Error Type**: `IllegalArgumentException`
- **Error Message Format**: "Field X and field Y differs in value but writes to the same column."
- **Null Handling**: Only validates when both values are non-null

## Test Coverage

Tests are located in `NodeIdResolverTest.java`:

| Test Method | Description |
|-------------|-------------|
| `assertInputColumns()` | Tests nodeId + regular field overlap |
| `assertListedInputColumns()` | Tests validation in array inputs |
| `assertOverlappingNodeIds()` | Tests two nodeIds with shared columns |
| `assertMultipleOverlappingFields()` | Tests 3+ fields on same column |

Test schemas in `src/test/resources/datafetchers/edit/standard/`:
- `assertInputColumns/schema.graphqls`
- `assertListedInputColumns/schema.graphqls`
- `assertOverlappingNodeIds/schema.graphqls`
- `assertManyOverlappingFields/schema.graphqls`

## Related Research

No previous research documents found on this topic.

## Open Questions

1. **Compile-time validation**: Should there also be compile-time validation that warns developers when field mappings overlap? Currently, overlaps are only caught at runtime.

2. **Error message improvement**: The current error message doesn't include the actual conflicting values. This could aid debugging.

3. **Performance consideration**: For inputs with many overlapping fields, the pairwise comparison generates O(n^2) checks. For typical use cases this is negligible, but could be optimized if needed.