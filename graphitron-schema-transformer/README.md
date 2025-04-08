# Graphitron Schema Transformer

Graphitron schema transformer is a Maven plugin that can run independently of the main `graphitron-maven-plugin`.
It is used to transform GraphQL schemas in various ways.

## Plugin Configuration Parameters

The schema-transformer plugin provides a variety of configuration parameters to customize its behavior.
For full documentation of all available parameters, see the Javadoc in the [Plugin.java](./graphitron-schema-transformer-maven-plugin/src/main/java/no/fellesstudentsystem/schema_transformer/maven/Plugin.java) file.

Key configuration parameters include:

- `schemaRootDirectories`: Where to find the GraphQL schema files (required)
- `makeApolloFederation`: Whether to add [Apollo Federation](#apollo-federation-support) support
- `addFeatureFlags`: Whether to add [feature flags to the schema based on directory structure](#Feature-Flag-Transformation)
- `outputSchema`: Name of the output schema file (for single schema output)
- `outputSchemas`: Configuration for multiple output schemas with different feature flags
- `removeGeneratorDirectives`: Whether to remove directives used for code generation
- `expandConnections`: Whether to expand GraphQL connection types

## Functionality

### Feature Flag Transformation

This allows you to include or exclude parts of your GraphQL schema based on specified feature flags. 
This is useful for controlling the visibility of certain fields, types, or arguments in your schema depending on the active feature flags.

#### How it Works

1. **Schema Definition**: In your GraphQL schema, you can annotate fields, arguments, and enums with the `@feature` directive,
specifying the required feature flags.

    ```graphql
    input I0 {
        f0: String @feature(flags: "F0")
        f1: String @feature(flags: "F1")
        f2: String @feature(flags: "F2")
        f3: String @feature(flags: ["F0", "F1"])
        f4: String @feature(flags: ["F1", "F2"])
    }
    ```

2. **Transformation**: The `SchemaFeatureFilter` class processes the schema and removes elements that do not match the active feature flags. 
This ensures that only the relevant parts of the schema are included in the final output.

    ```java
    Set<String> activeFlags = Set.of("F0", "F1");
    SchemaFeatureFilter filter = new SchemaFeatureFilter(activeFlags);
    GraphQLSchema filteredSchema = filter.getFilteredGraphQLSchema(originalSchema);
    ```

3. **Result**: The resulting schema will only include elements that match a subset of the active feature flags.

    ```graphql
    input I0 {
        f0: String @feature(flags: "F0")
        f1: String @feature(flags: "F1")
        f3: String @feature(flags: ["F0", "F1"])
    }
    ```

#### Multiple Feature Flags Handling

When specifying multiple feature flags using the array syntax `@feature(flags: ["F0", "F1"])`, this creates an AND relationship between the flags. This means:

- All specified flags must be active for the element to be included in the schema
- If you only request one of the flags (e.g., just "F0"), the element will NOT be included

This behavior allows you to create implicit hierarchies of features. For example, if you want to ensure that all "beta" features include "experimental" features, 
you could annotate beta features with `@feature(flags: ["beta", "experimental"])`. This would ensure that when requesting "beta" features, you always get "experimental" features as well.

While feature flags don't have an inherent hierarchy, this technique can be used to simulate dependencies between feature sets.

### Automatic Feature Directive Application

The schema-transformer Maven plugin automatically applies the `@feature` directive to fields, arguments, and enums based on the file structure of the provided schemas. This works in tandem with feature flag transformation to include the correct schema parts based on active feature flags.

To enable this, place schema files in the following structure:

```plaintext
schema-root/
    # Production schemas (no feature flag applied)
    schema1.graphql
    schema2.graphql
    features/
        beta/
            schema.graphql
        experimental/
            schema.graphql
```

Important notes:
* Files placed at the root level are considered production schemas and don't have feature flags applied
* Files placed in subdirectories under `features/` are annotated with feature flags matching their directory name
* Multiple schemas can be placed in the same directory
* This process is not recursive - only the top-level feature directories are recognized

## Schema Splitting

The schema-transformer maven plugin offers flexible schema generation through configurable outputs:

- You can define multiple schema files using either:
    - The `outputSchema` parameter for a single output
    - The `outputSchemas` collection for multiple outputs with different feature flag combinations

```xml
<configuration>
  <addFeatureFlags>true</addFeatureFlags>
  <outputSchemas>
    <schema>
      <fileName>schema-prod.graphql</fileName>
      <flags/>
    </schema>
    <schema>
      <fileName>schema-beta.graphql</fileName>
      <flags>
        <flag>beta</flag>
      </flags>
    </schema>
    <schema>
      <fileName>schema-exp.graphql</fileName>
      <flags>
        <flag>beta</flag>
        <flag>experimental</flag>
      </flags>
    </schema>
  </outputSchemas>
</configuration>
```

Common schema outputs include:

- `generator-schema.graphql`: Used for code generation, contains all Graphitron directives
- `schema.graphql`: The full schema with all feature flags included
- `federation-schema.graphql`: Schema prepared for Apollo Federation

You can create separate Maven execution blocks to generate different types of schemas:

```xml
<executions>
  <execution>
    <id>transform-graphql-schema</id>
    <configuration>
      <addFeatureFlags>true</addFeatureFlags>
      <outputSchema>schema.graphql</outputSchema>
    </configuration>
  </execution>
  <execution>
    <id>transform-graphql-schema-generator</id>
    <configuration>
      <makeApolloFederation>true</makeApolloFederation>
      <removeGeneratorDirectives>false</removeGeneratorDirectives>
      <outputSchema>generator-schema.graphql</outputSchema>
    </configuration>
  </execution>
</executions>
```

### Apollo Federation Support

The transformer can add Apollo Federation support to your schema using the `makeApolloFederation` option:

```xml
<makeApolloFederation>true</makeApolloFederation>
```

This transforms your schema to be compatible with Apollo Federation by:
- Adding federation types like `_Entity` and `_Any`
- Adding federation fields like `_entities` to the Query type
- Setting up Apollo Federation 2 by default

The federation transformation maintains applied federation directives like `@key` while adding the necessary federation types, directive definitions and fields.