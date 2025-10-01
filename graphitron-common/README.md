# Graphitron Common Module

Shared utilities and components for the Graphitron framework, including the exception handling framework, GraphQL helpers, and servlet utilities.

## Exception Handling Framework

Transforms Java exceptions into GraphQL-compliant error responses with user-friendly messages. The framework supports both **Top-Level Errors** (in the GraphQL response's `errors` array) and **Ad Hoc Error Fields** (errors as part of your schema).

### Top-Level Errors vs Ad Hoc Error Fields

#### Top-Level Errors
Top-level errors appear in the standard GraphQL `errors` array at the root of the response. These are best for:
- **Technical/exceptional issues**: Network problems, authentication failures, rate limiting
- **Developer errors**: Malformed queries, missing required arguments
- **System failures**: Database offline, service unavailable
- **Unhandled exceptions**: Unexpected runtime errors

Example response with top-level error (from approval tests):
```json
{
  "errors": [
    {
      "message": "An exception occurred. The error has been logged with id [UUID].",
      "locations": [{"line": 2, "column": 5}],
      "path": ["allCustomerEmails_topLevelError"],
      "extensions": {
        "classification": "DataFetchingException"
      }
    }
  ],
  "data": {
    "allCustomerEmails_topLevelError": null
  }
}
```

#### Ad Hoc Error Fields (Schema-Based Errors)
Ad hoc error fields are part of your GraphQL schema, typically in mutation payloads. These are best for:
- **Business logic errors**: Validation failures, constraint violations
- **User-facing errors**: Form validation, duplicate entries, invalid input
- **Expected error conditions**: Part of normal application flow
- **Partial success scenarios**: Some operations succeed while others fail

Example schema with ad hoc errors:
```graphql
type FilmMutationPayload {
    errors: [Error!]  # Ad hoc error field
    film: Film        # Can be returned even if errors exist
}
```

Example response with ad hoc error (from approval tests):
```json
{
  "data": {
    "updateFilmReleaseYear_handledError": {
      "errors": [
        {
          "__typename": "InvalidInput",
          "message": "Release year must be between 1901 and 2155",
          "path": ["updateFilmReleaseYear_handledError"]
        }
      ],
      "film": null
    }
  }
}
```

### Architecture Overview

```
GraphQL Execution
       ↓
CustomExecutionStrategy (intercepts exceptions)
       ↓
SchemaBasedErrorStrategy (attempts schema-based handling)
       ↓
SchemaErrorMapper (maps to schema errors) OR TopLevelErrorHandler (creates top-level errors)
       ↓
Error Payload (returned to client)
```

### How Graphitron Handles Both Error Types

The framework intelligently routes exceptions to either schema-based (ad hoc) errors or top-level errors:

1. **Schema-Based Errors (when configured)**: If an exception occurs in a field that returns a payload with an `errors` field, and the exception matches your `@error` directive configuration, it becomes a schema-based error
2. **Top-Level Errors (fallback)**: All other exceptions become top-level errors, including unmatched exceptions and exceptions in fields without error payloads

### Core Components

#### 1. CustomExecutionStrategy
- **Purpose**: Intercepts exceptions during field resolution and determines error type
- **Routes to Schema-Based**: When SchemaBasedErrorStrategy can handle the exception
- **Routes to Top-Level**: When exception is unrecognized or field has no error payload

#### 2. SchemaBasedErrorStrategy
- **Purpose**: Manages schema-based error handling for configured operations
- **Function**: Checks if exception matches `@error` directives and creates payload with errors array

#### 3. SchemaErrorMapper
- **Purpose**: Maps exceptions to schema-based error objects
- **Function**: Matches database/business logic exceptions to appropriate error types

#### 4. TopLevelErrorHandler
- **Purpose**: Handles top-level errors for unrecognized exceptions
- **Function**: Logs with unique IDs and creates standard GraphQL errors

### Generated Components

Graphitron generates exception handling code based on your GraphQL schema `@error` directives:

#### Generated Classes
- **GeneratedExceptionStrategyConfiguration**: Maps operations to exception types they handle
- **GeneratedExceptionToErrorMappingProvider**: Provides exception-to-error mappings per operation
- **ExceptionStrategyImpl**: Your custom implementation extending SchemaBasedErrorStrategy
- **Error type classes**: Java classes for each error type defined in the schema

These classes are generated in the `target/generated-sources` directory when you run `mvn graphitron:generate-code`.

### Exception Mapping System

The framework uses a two-tier mapping system:

#### 1. Matchers
Determine if an exception matches specific criteria:

- **GenericExceptionMatcher**: Matches exceptions by class type and optional message substring
- **DataAccessMatcher**: Extends GenericExceptionMatcher to also match SQL error codes

#### 2. Content-to-Error Mappings
Handle matched exceptions and create error objects:

- **ExceptionContentToErrorMapping**: Base interface for all mappings
- **GenericExceptionContentToErrorMapping**: Maps business logic exceptions
- **DataAccessExceptionContentToErrorMapping**: Maps database exceptions

### Special Exception Types

#### ValidationViolationGraphQLException
- **Purpose**: Thrown when Jakarta Bean Validation fails
- **Contains**: Field-level validation errors
- **Usage**: Automatically created when validation constraints are violated

### Exception Routing Flow

When an exception occurs during field resolution:

1. `CustomExecutionStrategy` intercepts the exception
2. Passes to `SchemaBasedErrorStrategy` to check for `@error` directive matches
3. **If matched → Schema-Based Error**:
   - `SchemaErrorMapper` creates error object based on `@error` configuration
   - Returns payload with populated `errors` array
   - Response has data with errors, no top-level errors
4. **If not matched → Top-Level Error**:
   - Falls back to `TopLevelErrorHandler`
   - Logs exception with unique ID
   - Response has null data with top-level errors array

### Schema-Driven Generation

Define error types in your GraphQL schema with `@error` directives:

```graphql
interface Error {
    path: [String!]!
    message: String!
}

type InvalidInput implements Error @error(handlers: [
    {
        handler: DATABASE, 
        code: "23514", 
        matches: "year_check", 
        description: "Release year must be between 1901 and 2155"
    },
    {
        handler: BUSINESS_LOGIC,
        className: "java.lang.IllegalArgumentException",
        matches: "invalid year",
        description: "Invalid year provided"
    }
]) {
    path: [String!]!
    message: String!
}

type FilmMutationPayload {
    errors: [Error!]
    film: Film
}

type Mutation {
    createFilm(input: FilmInput!): FilmMutationPayload
}
```

When you run `mvn graphitron:generate-code`, Graphitron generates:
- Error type classes (e.g., `InvalidInput.java`)
- Payload classes with errors field (e.g., `FilmMutationPayload.java`)
- Exception configuration mapping these errors to operations
- Resolver/Datafetcher methods that automatically use the exception handling

### Implementation Setup

See the [graphitron-example-server](../graphitron-example/graphitron-example-server/src/main/java/no/sikt/graphitron/example/server/GraphqlServlet.java) for a complete implementation.

Use the `ExceptionHandlingBuilder` to configure exception handling:

```java
private static ExecutionStrategy getCustomExecutionStrategy() {
    var dataAccessMapper = new DataAccessExceptionMapperImpl();
    var configuration = new GeneratedExceptionStrategyConfiguration();
    var mappingProvider = new GeneratedExceptionToErrorMappingProvider();
    
    return ExceptionHandlingBuilder.create()
        .withDataAccessMapper(dataAccessMapper)
        .withSchemaBasedStrategy(new SchemaBasedErrorStrategyImpl(
            configuration,
            mappingProvider,
            dataAccessMapper
        ))
        .build();
}

// In your servlet or GraphQL setup:
GraphQL graphQL = GraphQL.newGraphQL(schema)
    .queryExecutionStrategy(getCustomExecutionStrategy())
    .mutationExecutionStrategy(getCustomExecutionStrategy())
    .build();
```

You can provide your own implementation of `DataAccessExceptionMapper` to customize how database exceptions are mapped to user-friendly messages. You can use the `ErrorMessageFormatter` class provided in this module to convert SQL error codes into readable messages.

### Database Error Formatting

The `ErrorMessageFormatter` class provides user-friendly messages for common SQL errors:

| SQL State | Error Type | User Message |
|-----------|------------|--------------|
| 23505 | Unique constraint | "Duplicate value not allowed" |
| 23503 | Foreign key | "Referenced record does not exist" |
| 23502 | Not null | "Required field cannot be empty" |
| 23514 | Check constraint | "Value violates constraint: [name]" |
| 22001 | Data too long | "Value exceeds maximum length" |

For check constraints, the formatter attempts to extract and humanize the constraint name (e.g., "year_check" becomes "year").

### Best Practices

#### When to Use Ad Hoc Errors
- **Validation failures**: Invalid input that users can fix
- **Business rule violations**: Duplicate usernames, insufficient balance, etc.
- **Partial success**: When some operations succeed and others fail
- **User-actionable errors**: Errors users can understand and resolve

#### When to Use Top-Level Errors
- **Authentication/Authorization**: Not logged in, insufficient permissions
- **System failures**: Database offline, service unavailable  
- **Developer errors**: Malformed queries, type mismatches
- **Unexpected exceptions**: Null pointers, network timeouts

#### Configuration Tips
1. **Define all user-facing errors in schema** with `@error` directives
2. **Let technical errors fall through** to become top-level errors
3. **Use descriptive error messages** in `@error` descriptions
4. **Include path information** in error types to help locate issues
5. **Keep error messages user-friendly** without exposing internals

### Custom Exception Strategy Example

Extend `SchemaBasedErrorStrategy` to handle application-specific exceptions. See [ExceptionStrategyImpl.java](../graphitron-example/graphitron-example-server/src/main/java/no/sikt/graphitron/example/exceptionhandling/ExceptionStrategyImpl.java) in the example project:

```java
public class ExceptionStrategyImpl extends SchemaBasedErrorStrategy {
    
    @Override
    protected Object createDefaultDataAccessError(
            String operationName, 
            String message) {
        // Return your default error type for unmatched database exceptions
        return new GenericDatabaseError(List.of(operationName), message);
    }
    
    @Override
    protected Object createDefaultBusinessLogicError(
            String operationName, 
            String message) {
        // Return your default error type for unmatched business logic exceptions
        return new GenericError(List.of(operationName), message);
    }
}
```

## Other Components

### GraphQL Helpers
- **NodeIdStrategy**: Relay node ID encoding/decoding
- **QueryHelper**: jOOQ query building utilities
- **ResolverHelpers**: Data fetching utilities

### Servlet Support
- **GraphitronServlet**: Base servlet for GraphQL endpoints
- **GraphitronInstrumentation**: Performance monitoring and logging
