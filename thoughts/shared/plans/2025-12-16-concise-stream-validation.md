# Concise Stream-Based Column Validation Implementation Plan

## Overview

Refactor the generated mutation column conflict validation code to be more concise by using Java streams and a helper method, while preserving the existing error message format.

## Current State Analysis

The current implementation in `OperationMethodGenerator.validateInputs()` (lines 358-466) generates O(n²) pairwise comparison code:

```java
var _val_id_ACTOR_ID = _unpacked_id != null ? ... : null;
var _val_secondId_ACTOR_ID = _unpacked_secondId != null ? ... : null;
var _val_actor_ACTOR_ID = _nit__mi_in.getActor();
if (_val_id_ACTOR_ID != null && _val_secondId_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_secondId_ACTOR_ID)) {
    throw new IllegalArgumentException("Field id and field secondId differs in value but writes to the same column.");
}
if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
    throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
}
if (_val_secondId_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_secondId_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
    throw new IllegalArgumentException("Field secondId and field actor differs in value but writes to the same column.");
}
```

### Key Discoveries:
- `OperationMethodGenerator.java:430-458` - Current pairwise comparison generation
- `ResolverHelpers.java` - Existing utility class for generated resolver code
- `JavaPoetClassName.java:87` - `RESOLVER_HELPERS` enum for importing the helper class
- Error message format: `"Field X and field Y differs in value but writes to the same column."`

## Desired End State

Generated code using a stream-based helper method:

```java
var _unpacked_id = _mi_in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues(...) : null;
var _unpacked_secondId = _mi_in.getSecondId() != null ? _iv_nodeIdStrategy.unpackIdValues(...) : null;
ResolverHelpers.assertSameColumnValues(
    Map.of(
        "id", _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null,
        "secondId", _unpacked_secondId != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_secondId[0]) : null,
        "actor", _nit__mi_in.getActor()));
```

### Verification:
- All existing tests pass with updated expected output
- Error messages preserve the exact field names that conflict
- Generated code is significantly more concise (single method call vs multiple if-blocks)

## What We're NOT Doing

- Changing the validation logic (null handling, equality semantics)
- Changing when validation is performed (still runtime)
- Adding compile-time validation
- Changing the error message format

## Implementation Approach

1. Add a helper method to `ResolverHelpers` that takes a `Map<String, Object>` of field names to values
2. The helper uses streams to find conflicting values and throws with the existing error message format
3. Modify `OperationMethodGenerator.validateInputs()` to generate a `Map.of(...)` call instead of pairwise comparisons

## Phase 1: Add Helper Method to ResolverHelpers

### Overview
Add the `assertSameColumnValues` method that validates all values are equal (ignoring nulls) and throws with the existing error message format.

### Changes Required:

#### 1. ResolverHelpers.java
**File**: `graphitron-common/src/main/java/no/sikt/graphql/helpers/resolvers/ResolverHelpers.java`
**Changes**: Add new static method

```java
/**
 * Validates that all non-null values in the map are equal.
 * Used to ensure multiple GraphQL fields mapping to the same database column have consistent values.
 *
 * @param fieldNameToValue map of GraphQL field names to their values
 * @throws IllegalArgumentException if two non-null values differ
 */
public static void assertSameColumnValues(Map<String, Object> fieldNameToValue) {
    var nonNullEntries = fieldNameToValue.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .toList();

    if (nonNullEntries.size() < 2) {
        return;
    }

    var firstEntry = nonNullEntries.get(0);
    var conflictingEntry = nonNullEntries.stream()
            .skip(1)
            .filter(e -> !e.getValue().equals(firstEntry.getValue()))
            .findFirst()
            .orElse(null);

    if (conflictingEntry != null) {
        throw new IllegalArgumentException(
                "Field " + firstEntry.getKey() + " and field " + conflictingEntry.getKey() +
                " differs in value but writes to the same column.");
    }
}
```

#### 2. ResolverHelpersTest.java (new file)
**File**: `graphitron-common/src/test/java/no/sikt/graphql/helpers/resolvers/ResolverHelpersTest.java`
**Changes**: Add unit tests for the new method

```java
package no.sikt.graphql.helpers.resolvers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ResolverHelpers - assertSameColumnValues")
class ResolverHelpersTest {

    @Test
    @DisplayName("Should pass when all values are equal")
    void shouldPassWhenAllValuesEqual() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(
                Map.of("id", 1, "actor", 1, "other", 1)
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should pass when all values are null")
    void shouldPassWhenAllValuesNull() {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", null);
        map.put("actor", null);
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(map))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should pass when only one non-null value")
    void shouldPassWhenOnlyOneNonNullValue() {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", 1);
        map.put("actor", null);
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(map))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when two values differ")
    void shouldThrowWhenValuesDiffer() {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", 1);
        map.put("actor", 2);
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field id and field actor differs in value but writes to the same column.");
    }

    @Test
    @DisplayName("Should throw with first conflicting pair when multiple differ")
    void shouldThrowWithFirstConflictingPair() {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", 1);
        map.put("secondId", 2);
        map.put("actor", 3);
        assertThatThrownBy(() -> ResolverHelpers.assertSameColumnValues(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field id and field secondId differs in value but writes to the same column.");
    }

    @Test
    @DisplayName("Should pass with empty map")
    void shouldPassWithEmptyMap() {
        assertThatCode(() -> ResolverHelpers.assertSameColumnValues(Map.of()))
                .doesNotThrowAnyException();
    }
}
```

### Success Criteria:

#### Automated Verification:
- [x] Unit tests for `assertSameColumnValues` pass
- [x] `mvn test -pl :graphitron-common` passes

#### Manual Verification:
- [x] Review that error message format matches existing format exactly

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Update Code Generator

### Overview
Modify `OperationMethodGenerator.validateInputs()` to generate calls to `ResolverHelpers.assertSameColumnValues()` instead of pairwise if-blocks.

### Changes Required:

#### 1. OperationMethodGenerator.java
**File**: `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java`
**Changes**: Replace lines 430-458 (pairwise comparison generation) with Map.of() call generation

The current code:
```java
for (var entry : overlappingColumns) {
    var columnName = entry.getKey();
    var mappings = entry.getValue();

    // Extract values into variables for cleaner comparison code
    var valueVarNames = new ArrayList<String>();
    for (var mapping : mappings) {
        var valueVarName = VariablePrefix.valuePrefix(mapping.field.getName() + "_" + columnName);
        valueVarNames.add(valueVarName);
        code.addStatement("var $N = $L", valueVarName, getValueExtractionCode(mapping, itemVarName, type));
    }

    for (int i = 0; i < mappings.size() - 1; i++) {
        for (int j = i + 1; j < mappings.size(); j++) {
            // ... pairwise comparison if-blocks
        }
    }
}
```

Should become:
```java
for (var entry : overlappingColumns) {
    var mappings = entry.getValue();

    // Build Map.of() call with field names to value extraction expressions
    var mapEntries = CodeBlock.builder();
    for (int i = 0; i < mappings.size(); i++) {
        var mapping = mappings.get(i);
        if (i > 0) {
            mapEntries.add(",\n");
        }
        mapEntries.add("$S, $L", mapping.field.getName(), getValueExtractionCode(mapping, itemVarName, type));
    }

    code.addStatement("$T.assertSameColumnValues($T.of($L))",
            RESOLVER_HELPERS.className,
            MAP.className,
            mapEntries.build());
}
```

**Note**: Need to add static import for `RESOLVER_HELPERS` and `MAP` from `JavaPoetClassName`.

### Success Criteria:

#### Automated Verification:
- [x] Code generator compiles
- [x] `mvn test -pl :graphitron-java-codegen` passes (after updating test expectations in Phase 3)

#### Manual Verification:
- [x] Generated code is readable and concise

**Implementation Note**: Phase 2 and Phase 3 must be done together since tests will fail until expectations are updated.

---

## Phase 3: Update Test Expectations

### Overview
Update the test assertions in `NodeIdResolverTest.java` to match the new generated code format.

### Changes Required:

#### 1. NodeIdResolverTest.java
**File**: `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/datafetchers/standard/edit/NodeIdResolverTest.java`

**Test: assertInputColumns** (lines 49-65)
Change from:
```java
var _unpacked_id = _mi_in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues("FilmActor", _mi_in.getId(), FilmActor.FILM_ACTOR.getPrimaryKey().getFieldsArray()) : null;
var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
var _val_actor_ACTOR_ID = _mi_in.getActor();
if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
    throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
}
```

To:
```java
var _unpacked_id = _mi_in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues("FilmActor", _mi_in.getId(), FilmActor.FILM_ACTOR.getPrimaryKey().getFieldsArray()) : null;
ResolverHelpers.assertSameColumnValues(Map.of(
    "id", _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null,
    "actor", _mi_in.getActor()));
```

**Test: assertListedInputColumns** (lines 67-86)
Similar update, wrapped in the for loop.

**Test: assertOverlappingNodeIds** (lines 88-105)
Update for two columns (ACTOR_ID and FILM_ID) each with their own `assertSameColumnValues` call.

**Test: assertMultipleOverlappingFields** (lines 107-130)
Update for multiple fields mapping to same columns.

### Success Criteria:

#### Automated Verification:
- [x] All tests in `NodeIdResolverTest` pass
- [x] Full build passes: `mvn clean install -Pquick`

#### Manual Verification:
- [x] Review that generated code is significantly more concise than before
- [x] Error messages still correctly identify the conflicting fields

**Implementation Note**: After completing this phase and all automated verification passes, the implementation is complete.

---

## Testing Strategy

### Unit Tests:
- `ResolverHelpersTest` - Tests the helper method in isolation
- All values equal → no exception
- All values null → no exception
- Single non-null value → no exception
- Two different values → exception with correct field names
- Three+ values with conflicts → exception with first conflicting pair

### Integration Tests:
- `NodeIdResolverTest.assertInputColumns()` - Regular field + nodeId overlap
- `NodeIdResolverTest.assertListedInputColumns()` - Array inputs
- `NodeIdResolverTest.assertOverlappingNodeIds()` - Two nodeIds sharing columns
- `NodeIdResolverTest.assertMultipleOverlappingFields()` - 3+ fields on same column

### Manual Testing Steps:
1. Run the example server and test a mutation with conflicting values
2. Verify the error message shows the correct field names

## Performance Considerations

The stream-based approach with `Map.of()` has similar performance to the current pairwise approach:
- Both iterate through all values to find conflicts
- The stream approach uses `distinct()` which is O(n)
- For typical use cases (2-4 fields per column), the difference is negligible

## References

- Research document: `thoughts/shared/research/2025-12-16-mutation-column-conflict-validation.md`
- Current implementation: `OperationMethodGenerator.java:358-466`
- Helper class: `ResolverHelpers.java`
- Test file: `NodeIdResolverTest.java:49-130`