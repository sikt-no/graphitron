# Code Generation Utilities Reference

This document catalogs the utility classes, helper methods, and patterns available in the Graphitron code generation system. Use this as a reference when working on generators to avoid duplicating existing functionality.

## Table of Contents
- [Naming Utilities](#naming-utilities)
- [Field Classification](#field-classification)
- [Context & Parameter Management](#context--parameter-management)
- [Code Generation Utilities](#code-generation-utilities)
- [Pattern Detection](#pattern-detection)
- [Common Patterns](#common-patterns)

---

## Naming Utilities

### NameFormat
**Location**: `generators/codebuilding/NameFormat.java`

Core naming conventions for generated code:

- `asQueryMethodName(String field, String container)` - Generates query method names like "filmForQuery", "customerForFilm"
- `asRecordName(String name)` - Converts to record-style naming
- `asListedName(String name)` - Converts to list/plural naming
- `asRecordClassName(String name)` - Converts to record class naming
- `toCamelCase(String snakeCase)` - Converts snake_case to camelCase

**Usage Example**:
```java
var methodName = asQueryMethodName(field.getName(), getLocalObject().getName());
// field="films", container="Query" -> "filmsForQuery"
```

### VariableNames
**Location**: `generators/codebuilding/VariableNames.java`

Pre-defined constants for consistent variable naming:

- `VAR_CONTEXT` - "_iv_ctx" (DSLContext parameter)
- `VAR_RECORD_ITERATOR` - "_iv_r" (record iterator)
- `VAR_ITERATOR` - "_iv_e" (general iterator)
- `VAR_NODE_STRATEGY` - "_iv_nodeIdStrategy"
- `VAR_ORDER_FIELDS` - "_iv_orderFields"
- `resolverKeyParamName` - "_rk_[type]" (resolver key parameter)

### VariablePrefix
**Location**: `generators/codebuilding/VariablePrefix.java`

Namespace prefixes to prevent collision:

- `internalPrefix("_iv_")` - Internal variables
- `aliasPrefix("_a_")` - Table aliases
- `contextFieldPrefix("_cf_")` - Context fields
- `referenceKeyPrefix("_rk_")` - Reference keys

---

## Field Classification

### GenerationSourceField
**Location**: `definitions/fields/GenerationSourceField.java`

Base field interface with core properties:

- `isResolver()` - Field is a resolver (split query)
- `hasFieldReferences()` - Field uses @key/@references
- `isIterableWrapped()` - Field returns a list/array
- `hasNodeID()` - Field has Node ID
- `hasTableMethod()` - Field has @tableMethod directive
- `isExplicitlyNotGenerated()` - Field has @notGenerated directive
- `hasServiceReference()` - Field delegates to another service

### ObjectField
**Location**: `definitions/fields/ObjectField.java`

Extended field interface for object types:

- `getNonReservedArguments()` - Get non-GraphQL-reserved arguments
- `hasForwardPagination()` - Field supports Relay pagination
- `getOrderField()` - Get @orderBy field specification
- `getLookupKeys()` - Get lookup key definitions
- `getArguments()` - Get all field arguments
- `getFieldReferences()` - Get @references path

### LookupHelpers
**Location**: `generators/codebuilding/LookupHelpers.java`

Utility for detecting lookup patterns:

- `lookupExists(ObjectField field, ProcessedSchema schema)` - Returns true if field uses lookup keys

**Usage Example**:
```java
var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
if (lookupExists) {
    // Use simpler key-based fetching
}
```

---

## Context & Parameter Management

### FetchContext
**Location**: `generators/context/FetchContext.java`

Manages query building context and table joins:

- `getTargetAlias()` - Get the current target table alias
- `getTargetTable()` - Get the current target table mapping
- `getAliasSet()` - Get all table aliases in scope
- `getJoinSet()` - Get all joins needed
- `getConditionList()` - Get WHERE conditions
- `nextContext(ObjectField field)` - Create context for nested field
- `renderQuerySource(TableMapping table)` - Generate FROM clause
- `getReferenceObject()` - Get the reference object definition
- `hasNonSubqueryFields()` - Check if has fields beyond subqueries
- `hasApplicableTable()` - Check if context has applicable table

**Usage Pattern**:
```java
var context = new FetchContext(processedSchema, target, localObject, false);
var refContext = target.isResolver() ? context.nextContext(target) : context;
var targetAlias = refContext.getTargetAlias();
```

### InputParser
**Location**: `generators/context/InputParser.java`

Extracts and formats method input parameters:

- `getMethodInputsWithOrderField()` - Get parameters including order field
- `getMethodInputs()` - Get basic input parameters
- `getServiceInputString()` - Format as service call string

**Usage Example**:
```java
var parser = new InputParser(target, processedSchema);
methodBuilder.addParameters(parser.getMethodInputsWithOrderField());
```

### AliasWrapper
**Location**: `definitions/mapping/AliasWrapper.java`

Wraps table alias information:

- `hasTableMethod()` - Alias uses @tableMethod
- `getTableMethod()` - Get table method reference
- `getReferenceObjectField()` - Get the field this alias references
- `getAlias()` - Get underlying Alias

### Alias
**Location**: `definitions/mapping/Alias.java`

Represents a table alias in generated queries:

- `getMappingName()` - Get full alias name
- `getCodeName()` - Get short alias name (for Oracle compatibility)
- `getVariableValue()` - Get the table/method chain for variable declaration
- `isDerivedAlias()` - Check if alias is derived from join sequence vs base table
  - Returns `true` for relationship traversals (e.g., `_a_city.address()`)
  - Returns `false` for base table aliases (e.g., `CITY`)
- `getTable()` - Get the JOOQMapping for this alias

---

## Code Generation Utilities

### FormatCodeBlocks
**Location**: `generators/codebuilding/FormatCodeBlocks.java`

Utilities for consistent code block formatting:

- `createAliasDeclarations(Set<AliasWrapper>)` - Generate alias variable declarations
- `createSelectJoins(Set<JoinMapping>)` - Generate JOIN clauses
- `formatWhereContents(...)` - Generate WHERE clause
- `indentIfMultiline(CodeBlock)` - Conditionally indent based on content

### DBMethodGenerator (base class)
**Location**: `generators/db/DBMethodGenerator.java`

Base class for DB method generation:

- `getContextParameters()` - Get DSLContext and common parameters
- `getMethodParameters(InputParser)` - Get method signature parameters
- `getMethodParametersWithOrderField(InputParser)` - Include order field parameter
- `getSpecBuilder(ObjectField, TypeName, InputParser)` - Create MethodSpec.Builder with signature

---

## Pattern Detection

### Split Query Detection

**Pattern**: Resolver fields that reference a table object from a previous context.

**Detection Method** (in FetchMappedObjectDBMethodGenerator):
```java
private boolean isSplitQueryField(ObjectField field) {
    return field.isResolver() &&
        processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(field.getContainerTypeName());
}
```

**Characteristics**:
- Uses correlated subqueries
- Requires target table as parameter (not base table + aliases)
- Does NOT include input parameters in helper methods (applied at main method level)

### Container vs Wrapper Pattern

#### Container Pattern
**Definition**: Non-table type that exists solely to wrap input parameters for a nested table query.

**Example**:
```graphql
type FilmContainer {
  films(releaseYear: Int): [Film]  # Input params map to Film table
}
```

**Detection** (in FetchMappedObjectDBMethodGenerator):
```java
private boolean rootFieldHasInputTableArguments() {
    return currentRootField != null &&
        currentRootField.getArguments().stream()
            .anyMatch(processedSchema::hasInputJOOQRecord);
}
```

**Behavior**: Skip helper method generation, traverse directly to nested fields.

#### Wrapper Pattern
**Definition**: Non-table type that adds structural indirection for schema design.

**Example**:
```graphql
type Outer {
  customer: Customer  # No input params, pure structural wrapper
}
```

**Behavior**: Generate helper methods for proper nesting.

### Target Table Parameter Detection

**Pattern**: Determines whether a helper method should receive target table directly vs base table + aliases.

**Method** (in FetchMappedObjectDBMethodGenerator):
```java
private boolean shouldUseTargetTableParameter(ObjectField field) {
    var isSplitQuery = isSplitQueryField(field);
    var hasComplexReferencePath = field.hasFieldReferences() && field.getFieldReferences().size() > 1;
    return (field.isIterableWrapped() && (isSplitQuery || hasComplexReferencePath)) ||
        (!field.isIterableWrapped() && field.hasFieldReferences());
}
```

**Use target table for**:
- Split query multisets
- Complex reference multisets (multiple field references)
- Single reference fields (non-iterable with field references)

**Use base table + aliases for**:
- Simple table path multisets
- Single fields without references

---

## Common Patterns

### Circular Reference Prevention

**Always use** `visitedTypes` Set to track type names during recursive traversal. This is the canonical mechanism for preventing infinite recursion.

```java
private java.util.List<MethodSpec> generateNestedHelperMethods(
    ObjectField parentField,
    String parentHelperMethodName,
    java.util.Set<String> visitedTypes
) {
    var currentTypeName = recordType.getName();
    if (visitedTypes.contains(currentTypeName)) {
        return nestedMethods;  // Already visited, skip
    }
    visitedTypes.add(currentTypeName);
    // ... continue processing
}
```

**Note**: Do NOT parse method names or use string matching to detect circular references. Always rely on the `visitedTypes` Set which tracks actual GraphQL type names.

### Helper Method Naming

**Pattern**: `[callingMethod]_[returnType]_[nestedField]...`

**Example**:
```java
private String generateHelperMethodName(ObjectField target) {
    var callingMethodName = asQueryMethodName(target.getName(), getLocalObject().getName());
    var returnTypeName = processedSchema.getRecordType(target).getName();
    returnTypeName = returnTypeName.substring(0, 1).toLowerCase() + returnTypeName.substring(1);
    return callingMethodName + "_" + returnTypeName;
}
```

Produces names like:
- `filmsForQuery_film`
- `addressesForCity_address`
- `addressesForCity_address_city` (nested)

### Alias Declaration Pattern

For helper methods that receive base table parameter:

```java
var aliasesToDeclare = new java.util.LinkedHashSet<AliasWrapper>();
for (var alias : refContext.getAliasSet()) {
    if (!alias.hasTableMethod() && !aliasName.equals(tableParameterAlias)) {
        aliasesToDeclare.add(alias);
    }
}
if (!aliasesToDeclare.isEmpty()) {
    methodBuilder.addCode(createAliasDeclarations(aliasesToDeclare));
}
```

### Conditional Parameter Addition

```java
methodBuilder
    .addParameter(targetTable.getTableClass(), targetAlias)
    .addParameterIf(
        GeneratorConfig.shouldMakeNodeStrategy(),
        NODE_ID_STRATEGY.className,
        VAR_NODE_STRATEGY
    );
```

---

## When to Reuse vs Create New

### ✅ Always Reuse:
- **NameFormat** for all naming conventions
- **VariableNames** for standard variable names
- **FetchContext** for managing query context
- **InputParser** for extracting method parameters
- **LookupHelpers** for lookup detection
- **FormatCodeBlocks** for code formatting

### ⚠️ Consider Creating New:
- Pattern detection specific to your generator
- Complex business logic unique to your use case
- State management specific to your generation flow

### ❌ Don't Duplicate:
- String manipulation utilities (use NameFormat)
- Variable naming (use VariableNames constants)
- Alias management (use AliasWrapper and FetchContext)
- Parameter extraction (use InputParser)

---

## Related Documentation

- [README.md](./README.md) - Java codegen overview
- [JavaPoet README](../graphitron-javapoet/README.md) - Code generation utilities
- [CLAUDE.md](/CLAUDE.md) - Project instructions for Claude Code

---

*Last Updated: 2025-10-30*
*Related Branch: GG-297_extract-nested-jooq-fields*
