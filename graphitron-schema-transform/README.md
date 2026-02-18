# Graphitron Schema Transform

Graphitron schema transform is an integrated part of `graphitron-maven-plugin`,
providing GraphQL schema transformation capabilities.

For complete plugin documentation including all goals and configuration options, see the [Maven Plugin README](../graphitron-maven-plugin/README.md).

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc (https://github.com/thlorenz/doctoc) TO UPDATE -->
**Table of Contents**

- [Usage](#usage)
- [Functionality](#functionality)
  - [Feature Flag Transformation](#feature-flag-transformation)
    - [How it Works](#how-it-works)
    - [Multiple Feature Flags Handling](#multiple-feature-flags-handling)
  - [Automatic Feature Directive Application](#automatic-feature-directive-application)
  - [Schema Splitting](#schema-splitting)
  - [Apollo Federation Support](#apollo-federation-support)
    - [Tags](#tags)
    - [Removing Federation Definitions](#removing-federation-definitions)
  - [Relay Connection Expansion](#relay-connection-expansion)
    - [Example Usage](#example-usage)
    - [Customization](#customization)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## Usage

Schema transformation is available through the `graphitron-maven-plugin`. For most use cases,
use the `generate` goal with `<transform>` configuration - this handles both transformation and code generation in a single execution.

For configuration options and examples, see the [Maven Plugin README](../graphitron-maven-plugin/README.md).

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

This behavior allows you to create implicit hierarchies of features. For example, if you want to ensure that all "experimental" features must include "beta" features, 
you could annotate experimental features with `@feature(flags: ["beta", "experimental"])`. This would ensure that when requesting "experimental" features, you always need to request "beta" features as well.

While feature flags don't have an inherent hierarchy, this technique can be used to simulate dependencies between feature sets.

### Automatic Feature Directive Application

The transform goal automatically applies the `@feature` directive to fields, arguments, and enums
based on the file structure of the provided schemas. This works in tandem with feature flag transformation to include
the correct schema parts based on active feature flags.

To enable this, place schema files in the following structure:

```plaintext
schema-root/
    features/
        f0/
            schema.graphql
        f1/
            schema.graphql
        f2/
            schema0.graphql
            schema1.graphql
```

Important notes:
* Files placed in subdirectories under `features/` are annotated with feature flags matching their directory name
* Files placed outside the feature folders will not have feature flags applied
* Multiple schemas can be placed in the same directory
* This process is not recursive — only the top-level feature directories are recognized
* If an element already has explicit flags set then this takes precedence and automatic flags will not be added

### Schema Splitting

You can generate multiple schema variants with different feature flag combinations using `outputSchemas`:

```xml
<outputSchemas>
  <schema>
    <fileName>schema-stable.graphql</fileName>
    <flags/>
  </schema>
  <schema>
    <fileName>schema-beta.graphql</fileName>
    <flags>
      <flag>beta</flag>
    </flags>
  </schema>
  <schema>
    <fileName>schema-all.graphql</fileName>
    <flags>
      <flag>beta</flag>
      <flag>experimental</flag>
    </flags>
  </schema>
</outputSchemas>
```

For complete configuration examples, see the [Maven Plugin README](../graphitron-maven-plugin/README.md#examples).

### Apollo Federation Support

The transformer will automatically add the necessary types and fields to your schema to support Apollo Federation, 
given that you have applied the `@link` directive to your schema type:
```graphql
extend schema
@link(url: "https://specs.apollo.dev/federation/v2.3",
    import: ["@key", "@shareable"])
```
See the [Apollo Federation documentation](https://www.apollographql.com/docs/graphos/schema-design/federated-schemas/reference/directives#importing-directives) for more details.

This transforms your schema to be compatible with Apollo Federation by:
- Adding the imported directive definitions to the schema
- Adding federation types like `_Entity` and `_Any`
- Adding federation fields like `_entities` to the Query type
- Setting up Apollo Federation 2 by default

The federation transformation maintains applied federation directives like `@key` while adding the necessary federation types, directive definitions and fields.

#### Tags
If [Feature Flag Transformation](#Feature-Flag-Transformation) is enabled and federation is used, federation `@tag`
directives will be placed alongside the `@feature` directives. These are placed in exactly the same locations, with the exception that
`@tag` will also be placed on union type declarations. This is needed for the tags schema filtering to work.

#### Removing Federation Definitions
The Federation library does not like transforming schemas that already contain certain 
Apollo Federation definitions, such as `_Entity`, `_Any`, and `_Service`.

```java
com.apollographql.federation.graphqljava.Federation.transform(...);
```

Thus, you may need to remove these definitions from your schema if you want to apply the federation transformation at a later stage.
This is done by setting the `removeFederationDefinitions` parameter to `true` in your Maven configuration.

If you are using Apollo Federation, you want to have the Apollo Federation definitions in the schema that is used for code generation,
but not on the schema hosted by your GraphQL server, if that schema is to be transformed by the Federation library at run time.

We will implement a more elegant solution to this in the future, but for now you can use the `removeFederationDefinitions` parameter to remove these definitions from the schema that is hosted by your GraphQL server.

### Relay Connection Expansion

The [GraphQL Cursor Connections Specification](https://relay.dev/graphql/connections.htm) requires a significant amount of boilerplate code. 
To simplify this, we've created a directive that automatically expands list return types into proper Relay connection patterns.

The `@asConnection` directive transforms a field returning a list into a complete Relay connection when the `expandConnections` parameter is set to `true`.

#### Example Usage

```graphql
interface Node { id: ID! }

type Query {
    someType(
        param: String!
    ): [SomeType] @asConnection
}

type SomeType implements Node {
    id: ID!
    field: String!
}
```

This generates the following schema:

```graphql
interface Node {
    id: ID!
}

type PageInfo {
    hasPreviousPage: Boolean!
    hasNextPage: Boolean!
    startCursor: String
    endCursor: String
}

type Query {
    someType(param: String!, first: Int = 100, after: String): QuerySomeTypeConnection
}

type QuerySomeTypeConnection {
    edges: [QuerySomeTypeConnectionEdge]
    pageInfo: PageInfo
    nodes: [SomeType]
    totalCount: Int
}

type QuerySomeTypeConnectionEdge {
    cursor: String
    node: SomeType
}

type SomeType implements Node {
    id: ID!
    field: String!
}
```

This approach eliminates the need to manually define connection types, edges, and page info for each paginated field in your schema,
significantly reducing boilerplate while ensuring consistent implementation of the Relay connection pattern.


#### Customization

You can customize the default pagination size with the `defaultFirstValue` argument:

```graphql
type Query {
    someType(param: String!): [SomeType] @asConnection(defaultFirstValue: 50)
}
```
