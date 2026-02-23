# Graphitron Maven Plugin

Maven plugin for GraphQL schema transformation and Java code generation.

## Goals Overview

The plugin provides three main goals that build on each other, plus a standalone introspection goal:

| Goal | Description | Includes |
|------|-------------|----------|
| `transform` | Transform GraphQL schemas (federation, connections, feature flags) | - |
| `validate` | Validate schemas against database mappings | transform |
| `generate` | Generate Java code from schemas | transform (if configured), validate |
| `introspect` | Generate LSP configuration JSON from jOOQ metadata | - |

```
transform → validate → generate

introspect (standalone)
```

### Goal: `generate`

The primary goal for most users. Generates Java code from GraphQL schemas.

When `<transform>` configuration is provided, schema transformation runs automatically before code generation. Only `schemaRootDirectories` is required — the plugin handles the rest:

```xml
<plugin>
    <groupId>no.sikt</groupId>
    <artifactId>graphitron-maven-plugin</artifactId>
    <version>${graphitron.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <transform>
                    <schemaRootDirectories>
                        <directory>src/main/resources/graphql</directory>
                    </schemaRootDirectories>
                </transform>
                <jooqGeneratedPackage>com.example.jooq</jooqGeneratedPackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

With this minimal configuration, the plugin automatically produces two schemas in `target/generated-resources/graphql_transformer/`:

- **`generator-schema.graphql`** — Used internally for code generation. Keeps Graphitron directives (`@table`, `@field`, etc.) and `@asConnection` expansions.
- **`schema.graphql`** — Client-facing schema with Graphitron directives removed. Suitable for exposing to GraphQL clients.

Without `<transform>` configuration, the goal expects pre-transformed schema files:

```xml
<configuration>
    <schemaFiles>
        <file>path/to/transformed-schema.graphql</file>
    </schemaFiles>
    <jooqGeneratedPackage>com.example.jooq</jooqGeneratedPackage>
</configuration>
```

### Goal: `validate`

Validates GraphQL schemas without generating code. Useful for fast feedback during development.

Automatically runs `transform` first (via Maven lifecycle).

```bash
mvn graphitron:validate
```

### Goal: `transform`

Standalone schema transformation. Use this when you only need transformed schemas without code generation (e.g., for federation router configuration).

```xml
<execution>
    <goals>
        <goal>transform</goal>
    </goals>
    <configuration>
        <transform>
            <schemaRootDirectories>
                <directory>src/main/resources/graphql</directory>
            </schemaRootDirectories>
            <outputSchema>federated-schema.graphql</outputSchema>
            <removeFederationDefinitions>false</removeFederationDefinitions>
        </transform>
    </configuration>
</execution>
```

### Goal: `introspect`

Generates a JSON file containing metadata for LSP tooling. The goal introspects jOOQ-generated classes to extract table structures, column types, and foreign key relationships, producing a machine-readable configuration file that enables IDE features like autocompletion and validation when writing Graphitron GraphQL schemas.

Runs as a standalone goal — it does not depend on `transform` or `generate`.

```bash
mvn graphitron:introspect
```

The goal requires `jooqGeneratedPackage` to locate the jOOQ classes. It triggers the `generate-resources` lifecycle phase before execution to ensure jOOQ classes are compiled.

```xml
<execution>
    <id>introspect</id>
    <goals>
        <goal>introspect</goal>
    </goals>
</execution>
```

By default, external reference classes (services, conditions, records used in the GraphQL schema) are included in the output when configured. To disable this:

```xml
<execution>
    <id>introspect</id>
    <goals>
        <goal>introspect</goal>
    </goals>
    <configuration>
        <includeExternalReferencesForLSP>false</includeExternalReferencesForLSP>
    </configuration>
</execution>
```

#### Output format

The output is a JSON file (default: `target/graphitron-lsp-config.json`) with the following structure:

```json
{
  "tables": [
    {
      "table_name": "FILM",
      "description": "",
      "definition": { "file": "file:///tables/FILM", "line": 1, "col": 1 },
      "references": [
        { "table": "LANGUAGE", "key": "FILM__FILM_LANGUAGE_ID_FKEY", "inverse": false },
        { "table": "FILM_ACTOR", "key": "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY", "inverse": true }
      ],
      "fields": [
        { "field_name": "FILM_ID", "field_type": "Int", "nullable": false },
        { "field_name": "TITLE", "field_type": "String", "nullable": false }
      ]
    }
  ],
  "types": [
    { "name": "String", "aliases": [], "description": "", "definition": { "file": "file:///builtin", "line": 1, "col": 1 } }
  ],
  "external_references": [
    {
      "name": "CustomerService",
      "class_name": "com.example.CustomerService",
      "methods": ["createCustomerEmail", "findCustomer"]
    }
  ]
}
```

- **`tables`** — One entry per jOOQ table. Each table lists its columns (with GraphQL type mappings) and foreign key relationships.
- **`references`** — `inverse: false` means this table owns the FK (outgoing reference); `inverse: true` means another table references this one (incoming reference).
- **`types`** — GraphQL scalar types derived from the ScalarUtils mapping.
- **`external_references`** — Classes and their public methods, included when `includeExternalReferencesForLSP` is `true`. Omitted from output when disabled.

## Configuration Reference

### Transform Configuration

| Parameter                     | Description                                                      | Required | Default |
|-------------------------------|------------------------------------------------------------------|----------|---------|
| `schemaRootDirectories`       | Directories containing `.graphqls` files                         | Yes      | -       |
| `outputSchema`                | Output filename for transformed schema                           | *        | -       |
| `outputSchemas`               | Multiple output schemas (for feature splitting)                  | *        | -       |
| `expandConnections`           | Expand `@asConnection` to Relay types                            | No       | `true`  |
| `addFeatureFlags`             | Add feature flag filtering                                       | No       | `false` |
| `removeFederationDefinitions` | Remove federation types (`_Entity`, `_Any`, etc.) from output ** | No       | `false` |
| `removeGeneratorDirectives`   | Strip Graphitron directives from output                          | No       | `true`  |
| `directivesToRemove`          | Additional directives to remove                                  | No       | -       |
| `descriptionSuffixFilename`   | File containing description suffixes                             | No       | -       |

\* One of `outputSchema` or `outputSchemas` is required for standalone `transform` goal. For `generate` goal, `schema.graphql` is produced automatically.

\*\* Federation types can clash with directive definitions already present in federation frameworks. Set to `false` if your schema needs to be complete for frameworks that require these definitions.

### Code Generation Configuration

| Parameter              | Description                                                 | Required | Default                    |
|------------------------|-------------------------------------------------------------|----------|----------------------------|
| `jooqGeneratedPackage` | Package containing jOOQ generated classes                   | Yes      | -                          |
| `outputPackage`        | Package for generated GraphQL code                          | No       | `no.sikt.graphql`          |
| `outputPath`           | Directory for generated sources                             | No       | `target/generated-sources` |
| `schemaFiles`          | Pre-transformed schema files (when not using `<transform>`) | No       | -                          |
| `userSchemaFiles`      | Schema files to expose to clients                           | No       | ***                        |
| `makeNodeStrategy`     | Enable Relay Global Object Identification                   | No       | `false`                    |
| `scalars`              | Custom scalar implementations                               | No       | -                          |
| `maxAllowedPageSize`   | Maximum page size for connections                           | No       | `1000`                     |

\*\*\* Defaults to `schema.graphql` when using `<transform>`, otherwise defaults to `schemaFiles`.

### Introspect Configuration

Inherits `jooqGeneratedPackage`, `externalReferences`, and `externalReferenceImports` from the shared plugin configuration.

| Parameter                         | Description                           | Required | Default                             |
|-----------------------------------|---------------------------------------|----------|-------------------------------------|
| `outputFile`                      | Path for the generated JSON file      | No       | `target/graphitron-lsp-config.json` |
| `includeExternalReferencesForLSP` | Include external references in output | No       | `true`                              |

## Examples

### Minimal Setup

```xml
<plugin>
    <groupId>no.sikt</groupId>
    <artifactId>graphitron-maven-plugin</artifactId>
    <version>${graphitron.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <transform>
                    <schemaRootDirectories>
                        <directory>src/main/resources/graphql</directory>
                    </schemaRootDirectories>
                </transform>
                <jooqGeneratedPackage>com.example.jooq</jooqGeneratedPackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Feature-based Schema Splitting

Split schema by feature flags for gradual rollout. See [Schema Transform README](../graphitron-schema-transform/README.md#feature-flag-transformation) for details on how feature flags work.

```xml
<configuration>
    <transform>
        <schemaRootDirectories>
            <directory>src/main/resources/graphql</directory>
        </schemaRootDirectories>
        <addFeatureFlags>true</addFeatureFlags>
        <outputSchemas>
            <outputSchema>
                <fileName>schema-stable.graphql</fileName>
                <excludeFeatures>
                    <feature>experimental</feature>
                </excludeFeatures>
            </outputSchema>
            <outputSchema>
                <fileName>schema-all.graphql</fileName>
            </outputSchema>
        </outputSchemas>
    </transform>
    <jooqGeneratedPackage>com.example.jooq</jooqGeneratedPackage>
</configuration>
```

## See Also

- [Example project](../graphitron-example/graphitron-example-spec/pom.xml) - Complete working configuration
- [Schema Transform documentation](../graphitron-schema-transform/README.md) - Details on transformation features
- [Code Generator documentation](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Details on code generation
