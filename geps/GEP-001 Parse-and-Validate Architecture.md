# GEP-001: Parse-and-Validate Architecture

**Status:** Draft
**Version:** 2.0

-----

## Goal

Replace the monolithic `ProcessedSchema` with a two-phase architecture: parse schema directives into configuration, then validate that configuration. Eliminate silent failures. Provide rich error messages with source locations and suggestions.

-----

## Architecture Decision: Parse Then Validate

Separate concerns into two phases:

1. **Parse**: Build `CodeGenerationConfig` from schema. Record what was found or not found. Never throw.
2. **Validate**: Check all mappings. Collect structured errors. Fail gracefully with all errors.

```java
// Phase 1: Parse
CodeGenerationConfig config = new CodeGenerationConfigParser(jooqCatalog)
    .parse(schema);

// Phase 2: Validate
ValidationCollector result = new CodeGenerationConfigValidator()
    .validate(schema, config);

if (result.hasErrors()) {
    throw new SchemaValidationException(result.getErrors());
}

// Phase 3: Generate (feeds GEP-002 and GEP-003)
new CodeGenerator(schema, config).generate();
```

**Why separate?**

- Parser uses reflection; validator and generator don't
- Errors collected in one place, reported together
- Each phase has a single responsibility
- Easy to test in isolation

-----

## The 15 Directive Mappings

Graphitron directives map GraphQL to jOOQ/Java. Each mapping type knows whether it was found:

```java
public sealed interface Mapping permits
    // Type-level
    TableMapping,           // @table → jOOQ Table
    RecordMapping,          // @record → Java Record
    EnumMapping,            // @enum → Java Enum
    NodeMapping,            // @node → Node configuration
    DiscriminatorMapping,   // @discriminate → Union resolution
    ErrorMapping,           // @error → Exception handler
    
    // Field-level
    FieldMapping,           // @field → jOOQ TableField
    ServiceMapping,         // @service → Java method
    ReferenceMapping,       // @reference → FK path
    MultitableReferenceMapping,  // @multitableReference → Multiple FK paths
    ConditionMapping,       // condition in @reference → jOOQ Condition
    ExternalFieldMapping,   // @externalField → Field method
    TableMethodMapping,     // @tableMethod → Table method
    NodeIdMapping,          // @nodeId → Global ID
    IndexMapping            // @index → Enum index
{
    boolean found();
}
```

-----

## Configuration Structure

The parser builds a `CodeGenerationConfig` containing metadata for every type and field:

```java
public class CodeGenerationConfig {
    private final Map<String, TypeMetadata> types;
    private final Map<String, FieldMetadata> fields;
    
    public TypeMetadata getTypeMetadata(String typeName) { ... }
    public FieldMetadata getFieldMetadata(String typeName, String fieldName) { ... }
}

public class TypeMetadata {
    public Optional<TableMapping> tableMapping() { ... }
    public Optional<RecordMapping> recordMapping() { ... }
    public List<Mapping> allMappings() { ... }
}

public class FieldMetadata {
    public Optional<FieldMapping> fieldMapping() { ... }
    public Optional<ReferenceMapping> referenceMapping() { ... }
    public List<Mapping> allMappings() { ... }
}
```

Each mapping records both the result and what was available:

```java
public record FieldMapping(
    String columnName,
    Optional<TableField<?, ?>> jooqColumn,
    List<String> availableColumns
) implements Mapping {
    @Override 
    public boolean found() { 
        return jooqColumn.isPresent(); 
    }
}
```

-----

## Parser Implementation

Use GraphQL-Java's `SchemaTraverser` to walk the schema. Attach metadata via `TraverserContext`:

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
                    
                    TypeMetadata meta = parseType(type);
                    context.setVar(TypeMetadata.class, meta);
                    builder.addTypeMetadata(type.getName(), meta);
                    return CONTINUE;
                }
                
                @Override
                public TraversalControl visitGraphQLFieldDefinition(
                        GraphQLFieldDefinition field,
                        TraverserContext<GraphQLSchemaElement> context) {
                    
                    TypeMetadata parentMeta = context.getVarFromParents(TypeMetadata.class);
                    FieldMetadata meta = parseField(field, parentMeta);
                    
                    GraphQLObjectType parent = (GraphQLObjectType) context.getParentNode();
                    builder.addFieldMetadata(parent.getName(), field.getName(), meta);
                    return CONTINUE;
                }
            },
            schema
        );
        
        return builder.build();
    }
}
```

Reflection happens here. The config stores results.

-----

## Validator Implementation

Walk the config, check each mapping, collect errors:

```java
public class CodeGenerationConfigValidator {
    
    public ValidationCollector validate(GraphQLSchema schema, CodeGenerationConfig config) {
        var collector = new ValidationCollector();
        
        for (var entry : config.allTypes()) {
            TypeMetadata meta = entry.getValue();
            for (Mapping mapping : meta.allMappings()) {
                if (!mapping.found()) {
                    collector.addError(createError(mapping, entry.getKey()));
                }
            }
        }
        
        return collector;
    }
}
```

-----

## Rich Error Messages

Every error includes location and suggestions:

```java
public record ColumnNotFoundError(
    SourceLocation location,
    String typeName,
    String fieldName,
    String requestedColumn,
    List<String> availableColumns
) implements ValidationError {
    
    @Override
    public String message() {
        return String.format(
            "Column '%s' not found in table for %s.%s. Available: %s",
            requestedColumn, typeName, fieldName,
            String.join(", ", availableColumns)
        );
    }
}
```

Compare to the old error:
```
Missing column EMAIL in table USER
```

-----

## Summary

| Aspect | Decision |
|--------|----------|
| Architecture | Parse then validate, never throw during parse |
| Reflection | Isolated to parser only |
| Mappings | 15 types in sealed hierarchy |
| Errors | Structured with location and suggestions |
| Config | Single source of truth for code generation |

-----

**See also:**
- GEP-002: Simplify Mapping with JooqRecordDataFetcher
- GEP-003: Selection-Set-Driven Query Generation
