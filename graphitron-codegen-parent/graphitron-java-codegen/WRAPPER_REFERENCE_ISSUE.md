# Wrapper Type with @reference Issue

## Problem Pattern Found in External Project

### Schema Pattern
```graphql
type Emne implements Node @table {
    id: ID!
    kode: String @field(name: "EMNEKODE")

    # Wrapper type field (deprecated)
    organisasjonsenhet: EmneAnsvarligeOrganisasjonsenheter @deprecated(...)

    # Direct reference (replacement for the wrapper)
    studieansvarligOrganisasjonsenhet: Organisasjonsenhet @reference(path: [{key: "EMNE__REGLEMENT__ORGANISASJONSENHET__FK"}])
}

type EmneAnsvarligeOrganisasjonsenheter {
    # No @table directive - this is a wrapper type

    studieAnsvarlig: Organisasjonsenhet @reference(path: [{key: "EMNE__REGLEMENT__ORGANISASJONSENHET__FK"}])
}

type Organisasjonsenhet implements Node @table {
    id: ID!
    # ... other fields
}
```

### The Issue

1. **Emne** has @table (maps to EMNE database table)
2. **EmneAnsvarligeOrganisasjonsenheter** has NO @table (wrapper type)
3. **EmneAnsvarligeOrganisasjonsenheter.studieAnsvarlig** has @reference with key **"EMNE__REGLEMENT__ORGANISASJONSENHET__FK"**

### What Happens During Code Generation

1. Generator creates method for Emne fields
2. Encounters `organisasjonsenhet` field (wrapper type without table)
3. Correctly skips generating helper for the wrapper itself
4. Recurses into wrapper's children to generate helper for `studieAnsvarlig`
5. **Calls `generateNestedHelperMethodWithParentName(parentHelperMethodName, studieAnsvarligField)`**
6. Creates `FetchContext(processedSchema, studieAnsvarligField, getLocalObject(), false)`
   - `getLocalObject()` returns Query type (or other root type)
   - This context has NO knowledge that we came from the EMNE table
7. FetchContext tries to resolve the `@reference` path with key "EMNE__REGLEMENT__ORGANISASJONSENHET__FK"
8. This key is a foreign key FROM the EMNE table
9. FetchContext calls `getJavaFieldNamesForKey()` to find the key fields
10. **FAILS**: Cannot find the key because it's looking in the wrong table context

### The Root Cause

When we skip wrapper types and recurse to their children, the nested fields with `@reference` lose the connection to the original table-based parent type (Emne). They need to know they're being queried in the context of the EMNE table, not the Query root type.

### Stack Trace Confirmation

```
Caused by: java.util.NoSuchElementException: No value present
    at no.sikt.graphitron.mappings.TableReflection.lambda$getJavaFieldNamesForKey$20 (TableReflection.java:309)
    at no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumn (FormatCodeBlocks.java:718)
    at no.sikt.graphitron.generators.db.FetchDBMethodGenerator.generateSelectRow (FetchDBMethodGenerator.java:257)
    at no.sikt.graphitron.generators.db.FetchMappedObjectDBMethodGenerator.generateNestedHelperMethodWithParentName (FetchMappedObjectDBMethodGenerator.java:579)
```

The error occurs when trying to get key column information for a reference path that expects the EMNE table context but receives the Query context instead.

## Why Our Previous Attempts Failed

### Attempt 1: Use `parentRecordType` as context
```java
var parentRecordType = processedSchema.getRecordType(parentField);
var context = new FetchContext(processedSchema, nestedField, parentRecordType, false);
```

**Problem**: This changes aliases for ALL nested fields, breaking consistency. The same helper method generated from different call sites would have different alias names, which defeats the purpose of extracting helpers.

**Test failures**: 10 tests failed because alias names changed (e.g., `_a_city_760939060_address` vs `_a_city_729694529_address`)

## Potential Solutions

### Option 1: Track the "effective table parent" separately
Instead of changing the FetchContext's `previousObject` parameter, track the nearest table-based ancestor separately and use it only for resolving @reference paths.

### Option 2: Make wrapper types pass through the table context
When recursing through wrapper types, pass along the parent table-based type explicitly so that @reference resolution can use it.

### Option 3: Detect this specific pattern and handle it specially
When a field has:
- A wrapper parent (no @table)
- A @reference directive
- The reference key name suggests it relates to an ancestor table

Then look up the ancestor chain to find the nearest table-based type and use that for context.

### Option 4: Don't extract helpers for fields reached through wrappers with @reference
This is the safest option but may reduce compilation performance benefits.

## Next Steps

Need to discuss with user which approach to take. The key constraint is:
- ✅ Must not break alias consistency (tests must pass)
- ✅ Must handle wrapper types with @reference correctly
- ✅ Must work with the external project's schema patterns
