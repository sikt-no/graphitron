# Progress: Concise Stream-Based Column Validation

**Plan**: `thoughts/shared/plans/2025-12-16-concise-stream-validation.md`
**Date**: 2025-12-16
**Status**: ✅ Complete

## Summary

Refactoring generated mutation column conflict validation code to be more concise by using a helper method instead of O(n²) pairwise if-blocks.

## Completed Work

### Phase 1: Added Helper Method to ResolverHelpers ✅

**Files changed:**
- `graphitron-common/src/main/java/no/sikt/graphql/helpers/resolvers/ResolverHelpers.java`
  - Added `assertSameColumnValues(Map<String, Object>)` method (lines 51-72)
  - Added `assertSameColumnValues(Object... keysAndValues)` varargs overload (lines 81-90)
  - Added `java.util.LinkedHashMap` import

- `graphitron-common/src/test/java/no/sikt/graphql/helpers/resolvers/ResolverHelpersTest.java` (new file)
  - 8 unit tests for the helper methods

### Phase 2: Updated Code Generator ✅

**Files changed:**
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java`
  - Removed `MAP` import (no longer needed)
  - Replaced `Map.of()` call generation with varargs call generation (lines 432-448)
  - Changed `itemVarName` generation at line 366 from `namedIteratorPrefixIf(inputVarName, isListed)` to `namedIteratorPrefixIf(inputRecord.getName(), isListed)`

### Phase 3: Updated Test Expectations ✅

**Files changed:**
- `graphitron-codegen-parent/graphitron-java-codegen/src/test/java/no/sikt/graphitron/datafetchers/standard/edit/NodeIdResolverTest.java`
  - Updated 4 tests to match new generated code format (varargs instead of Map.of())
  - Changed variable names from `_nit__mi_in` to `_nit_in`

- `graphitron-example/graphitron-example-server/src/test/resources/approval/approvals/mutation_insert_film_actor_overlap-1.result.approved.json`
  - Updated expected error message field order

### Phase 4: Fixed Non-deterministic Field Order ✅

**Problem**: `Map.of()` doesn't guarantee iteration order, causing flaky integration tests.

**Solution implemented**: Added varargs overload that internally uses `LinkedHashMap` to preserve schema definition order.

**Files changed:**
- `graphitron-common/src/main/java/no/sikt/graphql/helpers/resolvers/ResolverHelpers.java`
  - Added varargs overload: `assertSameColumnValues(Object... keysAndValues)`
  - Uses `LinkedHashMap` internally to preserve insertion order

- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/generators/datafetchers/operations/OperationMethodGenerator.java`
  - Updated to generate varargs calls instead of `Map.of()` calls
  - Removed unused `MAP` import

## Generated Code Comparison

### Before (verbose):
```java
var _val_id_ACTOR_ID = _unpacked_id != null ? ... : null;
var _val_actor_ACTOR_ID = _mi_in.getActor();
if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
    throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
}
```

### After (concise with deterministic ordering):
```java
ResolverHelpers.assertSameColumnValues(
    "id", _unpacked_id != null ? ... : null,
    "actor", _mi_in.getActor());
```

## Test Results

- Unit tests: 8/8 passing in `ResolverHelpersTest`
- Code generator tests: 6/6 passing in `NodeIdResolverTest`
- Integration tests: All passing with deterministic field ordering