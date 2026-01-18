# GEP-001: Parse-and-Validate Architecture

**Status:** Draft
**Version:** 3.0

-----

## The Real Problem

Graphitron's current architecture mixes parsing, validation, and code generation in a tightly-coupled God Object.

### Current State: ProcessedSchema

```
ProcessedSchema (1,323 lines, 70+ methods)
    ↓
Called 248 times across 39 generator files
    ↓
Parsing + Validation + Type Inference + Query Interface all in one
```

**Key Statistics:**
- **ProcessedSchema**: 1,323 lines with 70+ query methods
- **Usage**: 248 method calls to `processedSchema.` across 39 files
- **Directive parsing**: Scattered across 10 definition classes (27 `hasDirective()` calls)
- **Reflection**: Static initialization, runtime failures, workarounds ("FS HACK")
- **Technical debt**: 15+ TODOs indicating architectural pain points

### Example: Current Directive Parsing

**File**: `GenerationSourceField.java` (10 directive checks in one constructor)

```java
public GenerationSourceField(T field, FieldType fieldType, String container) {
    super(field, fieldType, container);

    // Directives parsed inline - no configuration object
    if (field.hasDirective(REFERENCE.getName())) { /* parse */ }
    if (field.hasDirective(MULTITABLE_REFERENCE.getName())) { /* parse */ }
    condition = field.hasDirective(CONDITION.getName()) ? new SQLCondition(field) : null;
    serviceWrapper = field.hasDirective(SERVICE.getName()) ? new ServiceWrapper(field) : null;
    hasFieldDirective = field.hasDirective(FIELD.getName());
    isExternalField = field.hasDirective(EXTERNAL_FIELD.getName());
    isGenerated = !field.hasDirective(NOT_GENERATED.getName());
    isResolver = field.hasDirective(SPLIT_QUERY.getName()) || ...;
    hasTableMethod = field.hasDirective(TABLE_METHOD.getName());
    hasService = field.hasDirective(SERVICE.getName());
    // ... continues
}
```

### Example: Repeated Queries

**File**: `OperationMethodGenerator.java` (same query called twice in one method)

```java
public MethodSpec generate(ObjectField target) {
    // Line 54
    var isMutationReturningData = processedSchema.isDeleteMutationWithReturning(target)
        || processedSchema.isInsertMutationWithReturning(target);

    // ... 50 lines later

    // Line 131 - SAME QUERY AGAIN
    if (processedSchema.isDeleteMutationWithReturning(target)
        || processedSchema.isInsertMutationWithReturning(target)) {
        // ...
    }
}
```

**Pattern**: 72 calls to `getTable()`/`hasTable()` across 23 files, many redundant.

### Example: Reflection Workarounds

**File**: `TableReflection.java`

```java
/**
 * NEW FS hack! Some methods do not use getId(), but getId_() methods for ID...
 */
public static boolean recordUsesFSHack(String tableName) {
    return getRecordClass(tableName)
            .map(it -> Stream.of(it.getMethods())
                .anyMatch(m -> m.getName().equalsIgnoreCase("getId_")))
            .orElse(false);
}
```

Used in `MapperContext.java`:
```java
// FS HACK - Account for get/set ID methods with an underscore at the end...
if (previousContext.targetIsType && previousContext.targetType.hasTable()
    && recordUsesFSHack(previousContext.targetType.getTable().getName())) {
    // Special handling
}
```

-----

## Goal

Create a proper configuration layer that:
1. **Separates parsing from generation** - Parse schema once into immutable config
2. **Enables testability** - Mock/stub FieldConfig and TypeConfig without schema
3. **Improves error messages** - Collect all errors before code generation starts
4. **Eliminates repeated queries** - Config built once, passed down
5. **Isolates reflection** - Reflection happens in parse phase only
6. **Provides foundation for future improvements** - Clean base for GEP-002 and GEP-003

-----

## Architecture Decision: Parse → Validate → Generate

Three separate phases with clear responsibilities:

```java
// Phase 1: Parse schema into configuration
CodeGenerationConfig config = new CodeGenerationConfigParser(jooqCatalog)
    .parse(schema);

// Phase 2: Validate configuration
ValidationResult result = new CodeGenerationConfigValidator()
    .validate(schema, config);

if (result.hasErrors()) {
    throw new SchemaValidationException(result.getErrors());
}

// Phase 3: Generate code using configuration
new CodeGenerator(schema, config).generate();
```

**Why separate?**
- **Parse once, use many times** - No repeated queries to ProcessedSchema
- **Reflection isolated** - Only happens in parser, validator and generator are pure
- **Testable** - Each phase can be tested in isolation
- **Fail fast** - All validation errors collected before code generation
- **Immutable config** - Thread-safe, cacheable, easier to reason about

-----

## Configuration Structure

### Type-Level Configuration

```java
public sealed interface TypeConfig permits
    ObjectTypeConfig,
    InterfaceTypeConfig,
    UnionTypeConfig,
    EnumTypeConfig,
    InputTypeConfig { }

public record ObjectTypeConfig(
    String typeName,
    Optional<TableMapping> tableMapping,
    Optional<RecordMapping> recordMapping,
    Optional<NodeMapping> nodeMapping,
    Optional<DiscriminatorMapping> discriminatorMapping,
    List<FieldConfig> fields,
    boolean isGenerated
) implements TypeConfig { }
```

### Field-Level Configuration

```java
public record FieldConfig(
    String fieldName,
    String typeName,
    Optional<FieldMapping> fieldMapping,           // @field directive
    Optional<ReferenceMapping> referenceMapping,   // @reference directive
    Optional<ServiceMapping> serviceMapping,       // @service directive
    Optional<ConditionMapping> conditionMapping,   // @condition directive
    List<ArgumentConfig> arguments,
    boolean isSplitQuery,
    boolean isGenerated
) { }
```

### Mapping Records

Each directive mapping is a record that knows if it succeeded:

```java
public record TableMapping(
    String tableName,
    Optional<Table<?>> jooqTable,
    List<String> availableTables
) {
    public boolean found() {
        return jooqTable.isPresent();
    }
}

public record FieldMapping(
    String columnName,
    Optional<TableField<?, ?>> jooqColumn,
    List<String> availableColumns
) {
    public boolean found() {
        return jooqColumn.isPresent();
    }
}

public record ReferenceMapping(
    List<ReferencePathElement> path,
    boolean resolved
) {
    public boolean found() {
        return resolved;
    }
}
```

-----

## Parser Implementation

Use GraphQL-Java's `SchemaTraverser` to walk schema once, build immutable config:

```java
public class CodeGenerationConfigParser {
    private final JooqCatalog jooqCatalog;

    public CodeGenerationConfig parse(GraphQLSchema schema) {
        var builder = CodeGenerationConfig.builder();

        new SchemaTraverser().depthFirstFullSchema(
            new GraphQLTypeVisitorStub() {
                @Override
                public TraversalControl visitGraphQLObjectType(
                        GraphQLObjectType type,
                        TraverserContext<GraphQLSchemaElement> context) {

                    ObjectTypeConfig config = parseObjectType(type);
                    builder.addType(config);
                    return CONTINUE;
                }

                @Override
                public TraversalControl visitGraphQLFieldDefinition(
                        GraphQLFieldDefinition field,
                        TraverserContext<GraphQLSchemaElement> context) {

                    FieldConfig config = parseField(field, context);
                    builder.addField(config);
                    return CONTINUE;
                }
            },
            schema
        );

        return builder.build();
    }

    private ObjectTypeConfig parseObjectType(GraphQLObjectType type) {
        // Parse @table directive
        var tableMapping = parseTableDirective(type);

        // Parse @record directive
        var recordMapping = parseRecordDirective(type);

        // Parse @node directive
        var nodeMapping = parseNodeDirective(type);

        // Check @notGenerated
        boolean isGenerated = !type.hasDirective(NOT_GENERATED.getName());

        return new ObjectTypeConfig(
            type.getName(),
            tableMapping,
            recordMapping,
            nodeMapping,
            Optional.empty(),  // discriminator
            List.of(),  // fields added separately
            isGenerated
        );
    }

    private Optional<TableMapping> parseTableDirective(GraphQLObjectType type) {
        if (!type.hasDirective(TABLE.getName())) {
            return Optional.empty();
        }

        String tableName = getDirectiveArgument(type, TABLE, NAME)
            .orElse(type.getName().toUpperCase());

        // Reflection happens here - lookup jOOQ table
        Optional<Table<?>> jooqTable = jooqCatalog.findTable(tableName);
        List<String> availableTables = jooqCatalog.getAllTableNames();

        return Optional.of(new TableMapping(tableName, jooqTable, availableTables));
    }
}
```

**Key points:**
- Reflection isolated to parser
- Single pass over schema
- Config is immutable after building
- Records what was found AND what was available (for error messages)

-----

## Validator Implementation

Walk config, collect all errors before throwing:

```java
public class CodeGenerationConfigValidator {

    public ValidationResult validate(GraphQLSchema schema, CodeGenerationConfig config) {
        var errors = new ArrayList<ValidationError>();

        for (ObjectTypeConfig typeConfig : config.getObjectTypes()) {
            // Validate table mappings
            if (typeConfig.tableMapping().isPresent()) {
                var mapping = typeConfig.tableMapping().get();
                if (!mapping.found()) {
                    errors.add(new TableNotFoundError(
                        schema.getType(typeConfig.typeName()).getDefinition().getSourceLocation(),
                        typeConfig.typeName(),
                        mapping.tableName(),
                        mapping.availableTables()
                    ));
                }
            }

            // Validate field mappings
            for (FieldConfig fieldConfig : typeConfig.fields()) {
                if (fieldConfig.fieldMapping().isPresent()) {
                    var mapping = fieldConfig.fieldMapping().get();
                    if (!mapping.found()) {
                        errors.add(new ColumnNotFoundError(
                            /* source location */,
                            typeConfig.typeName(),
                            fieldConfig.fieldName(),
                            mapping.columnName(),
                            mapping.availableColumns()
                        ));
                    }
                }
            }
        }

        return new ValidationResult(errors);
    }
}
```

-----

## Rich Error Messages

Every error includes location, context, and suggestions:

```java
public record TableNotFoundError(
    SourceLocation location,
    String typeName,
    String requestedTable,
    List<String> availableTables
) implements ValidationError {

    @Override
    public String message() {
        var suggestions = findClosestMatches(requestedTable, availableTables, 3);

        return String.format(
            "%s:%s: Table '%s' not found for type %s\n" +
            "  Available tables: %s\n" +
            "  Did you mean: %s?",
            location.getLine(),
            location.getColumn(),
            requestedTable,
            typeName,
            String.join(", ", availableTables.stream().limit(10).toList()),
            String.join(" or ", suggestions)
        );
    }
}
```

**Before:**
```
Missing column EMAIL in table USER
```

**After:**
```
schema.graphqls:42:3: Column 'EMAIL' not found for User.email
  Table: USERS
  Available columns: ID, NAME, EMAIL_ADDRESS, CREATED_AT, UPDATED_AT
  Did you mean: EMAIL_ADDRESS?
```

-----

## Migration Strategy (Low Risk)

### Phase 1: Build Config, Use for Validation Only
**Goal:** Prove the concept without touching code generation

```java
// Current code
ProcessedSchema schema = new ProcessedSchema(typeRegistry);
new ProcessedDefinitionsValidator(schema).validate();
new CodeGenerator(schema).generate();

// Phase 1 (parallel)
ProcessedSchema schema = new ProcessedSchema(typeRegistry);
CodeGenerationConfig config = new CodeGenerationConfigParser(jooqCatalog).parse(graphqlSchema);
new CodeGenerationConfigValidator().validate(graphqlSchema, config);  // NEW - runs in parallel
new ProcessedDefinitionsValidator(schema).validate();  // OLD - still runs
new CodeGenerator(schema).generate();  // OLD - still uses ProcessedSchema
```

**Risk:** LOW - Config generation runs in parallel, old code path unchanged
**Benefit:** Validates parser correctness, improves error messages immediately
**Effort:** 2-3 weeks

### Phase 2: Start Using Config in Generators
**Goal:** Reduce queries to ProcessedSchema

```java
ProcessedSchema schema = new ProcessedSchema(typeRegistry);
CodeGenerationConfig config = new CodeGenerationConfigParser(jooqCatalog).parse(graphqlSchema);
new CodeGenerationConfigValidator().validate(graphqlSchema, config);
new CodeGenerator(schema, config).generate();  // UPDATED - receives config
```

Inside generators:
```java
// Before
if (processedSchema.hasTable(typeName)) {
    var table = processedSchema.getTable(typeName);
    // ...
}

// After
var typeConfig = config.getObjectType(typeName);
if (typeConfig.tableMapping().isPresent()) {
    var table = typeConfig.tableMapping().get().jooqTable().get();
    // ...
}
```

**Risk:** MEDIUM - Touching code generation, but config is validated
**Benefit:** Eliminate repeated queries, clearer code
**Effort:** 4-6 weeks (migrate one generator at a time)

### Phase 3: Remove ProcessedSchema
**Goal:** Delete 1,323 lines of legacy code

```java
CodeGenerationConfig config = new CodeGenerationConfigParser(jooqCatalog).parse(graphqlSchema);
new CodeGenerationConfigValidator().validate(graphqlSchema, config);
new CodeGenerator(config).generate();  // CLEAN - only config
```

**Risk:** LOW - By this point, config is proven to work
**Benefit:** Simpler architecture, less code to maintain
**Effort:** 1-2 weeks (cleanup and tests)

**Total Migration Time: 7-11 weeks**

-----

## Benefits vs Current Architecture

| Aspect | Current | After GEP-001 |
|--------|---------|---------------|
| **Lines of code** | ProcessedSchema: 1,323 | Config classes: ~800 total |
| **Query pattern** | 248 calls to ProcessedSchema | Config passed down, no queries |
| **Reflection** | Scattered, static init, runtime errors | Isolated in parser, fails fast |
| **Testability** | Need full schema + jOOQ setup | Mock FieldConfig/TypeConfig |
| **Error messages** | "Missing column EMAIL in table USER" | Full context, location, suggestions |
| **Coupling** | Every generator needs ProcessedSchema | Generators receive specific config |
| **Validation** | Mixed with generation | Separate, all errors at once |

-----

## What This Enables

### 1. Easier Testing
```java
@Test
void shouldGenerateFieldAccessor() {
    var fieldConfig = new FieldConfig(
        "email",
        "String",
        Optional.of(new FieldMapping("EMAIL_ADDRESS", Optional.of(USERS.EMAIL_ADDRESS), List.of())),
        // ... other config
    );

    var code = new FieldAccessorGenerator().generate(fieldConfig);

    assertThat(code).contains("record.get(USERS.EMAIL_ADDRESS)");
}
```

No need to create full GraphQL schema, parse it, set up jOOQ, etc.

### 2. Better Performance
```java
// Current: Query ProcessedSchema 248 times during generation
for (var generator : generators) {
    generator.generate(processedSchema);  // Each queries repeatedly
}

// After: Config built once, immutable
var config = parser.parse(schema);  // Once
for (var generator : generators) {
    generator.generate(config);  // Just reads config
}
```

### 3. Foundation for GEP-002 and GEP-003

**GEP-002** (Remove DTOs): Needs clean field mappings
```java
// With config, easy to generate wiring
for (var field : typeConfig.fields()) {
    if (field.fieldMapping().isPresent()) {
        var mapping = field.fieldMapping().get();
        generateWiring(field.fieldName(), mapping.jooqColumn());
    }
}
```

**GEP-003** (Selection-aware queries): Needs to know which fields are database-mapped
```java
// With config, easy to filter
var dbFields = typeConfig.fields().stream()
    .filter(f -> f.fieldMapping().isPresent())
    .toList();

generateSelectionAwareQuery(dbFields);
```

-----

## Technical Debt This Solves

From the codebase analysis, GEP-001 addresses:

1. **JOOQMapping.java** line 106: "FIXME: If schema refers to a table that doesn't exist, we should stop generation"
   - **Solved:** Validation phase catches this before generation

2. **ProcessedDefinitionsValidator.java** scattered TODOs about incomplete validation
   - **Solved:** Structured validation with all errors collected

3. **TableReflection workarounds** ("FS HACK")
   - **Solved:** Reflection isolated, can be tested and fixed properly

4. **Repeated queries** (same method called twice in one function)
   - **Solved:** Config contains answer, no need to query

5. **Constructor complexity** (10 directive checks per constructor)
   - **Solved:** Directives parsed once in parser, results in config

-----

## Risks and Mitigations

### Risk: Config Structure Doesn't Cover All Cases
**Likelihood:** MEDIUM
**Impact:** HIGH (need to redesign)
**Mitigation:**
- Start with Phase 1 (validation only)
- Keep ProcessedSchema running in parallel
- Discover gaps early before committing to migration

### Risk: Performance Regression
**Likelihood:** LOW
**Impact:** MEDIUM
**Mitigation:**
- Config is immutable, can be cached
- No repeated reflection (current approach does reflection on every query)
- Benchmark before/after

### Risk: Breaking Changes During Migration
**Likelihood:** MEDIUM
**Impact:** HIGH
**Mitigation:**
- Phase 1 runs in parallel (zero risk)
- Phase 2 migrates one generator at a time with tests
- Keep old code path until Phase 3

-----

## Summary

| Aspect | Decision |
|--------|----------|
| **Architecture** | Parse → Validate → Generate (three separate phases) |
| **Reflection** | Isolated to parser only |
| **Configuration** | Immutable records with sealed interface hierarchy |
| **Errors** | Structured with location, context, suggestions |
| **Migration** | Three phases over 7-11 weeks, low risk |
| **Benefits** | Better errors, testability, foundation for GEP-002/003 |

-----

## Estimated Effort

| Phase | Effort | Risk | Value |
|-------|--------|------|-------|
| **Phase 1:** Build config, validate only | 2-3 weeks | LOW | Better error messages |
| **Phase 2:** Use config in generators | 4-6 weeks | MEDIUM | Cleaner code, testability |
| **Phase 3:** Remove ProcessedSchema | 1-2 weeks | LOW | Delete 1,323 lines |
| **Total** | 7-11 weeks | LOW-MEDIUM | Foundation for future |

-----

## Recommendation

**IMPLEMENT in phases as described.**

**Why this is worth doing:**
1. **Solves real problems** - 15+ TODOs, workarounds, repeated queries
2. **Low risk migration** - Phase 1 runs in parallel, validates approach
3. **Enables future work** - Clean base for GEP-002 and GEP-003
4. **Improves maintainability** - Replace 1,323-line God Object with clean config
5. **Better DX** - Error messages with context and suggestions

**When to do it:**
- **Before GEP-002** - Need clean config layer for DTO removal
- **Before GEP-003** - Need to know which fields are DB-mapped
- **Now** - Current architecture is causing pain (15+ TODOs prove this)

-----

**See also:**
- GEP-002: Simplify Mapping with JooqRecordDataFetcher
- GEP-003: Selection-Set-Driven Query Generation
