Graphitron is a code generation tool that creates source code by linking GraphQL schemas to underlying database models.
Developed by **<a href="https://www.sikt.no"> Sikt – the Norwegian Agency for Shared Services in Education and Research</a>**,
the tool generates precise source code for our systems, ensuring maintainability over time.

Graphitron is used as a Maven plugin, offering two main functionalities:
-   **Java Code Generation**: Creates Java source code from GraphQL schemas.
-   **Schema Transformation**: Modifies GraphQL schemas based on various configurations.

## Quick Start

The simplest way to use Graphitron is with the `generate-all` goal, which combines schema transformation and code generation:

```xml
<plugin>
    <groupId>no.sikt</groupId>
    <artifactId>graphitron-maven-plugin</artifactId>
    <version>${graphitron.version}</version>
    <executions>
        <execution>
            <goals><goal>generate-all</goal></goals>
            <configuration>
                <transform>
                    <schemaRootDirectories>
                        <directory>src/main/resources/graphql</directory>
                    </schemaRootDirectories>
                </transform>
                <jooqGeneratedPackage>com.example.jooq</jooqGeneratedPackage>
                <outputPackage>com.example.graphql</outputPackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

For advanced use cases, the `transform` and `generate` goals can be used separately.

## Documentation

For more information, see:
-   [Vision and Goals](./VISION.md)
-   [Online documentation](https://graphitron.sikt.no/)
-   [Code-generator README](./graphitron-codegen-parent/graphitron-java-codegen/README.md) for detailed information on how to configure and use the Graphitron Java Code Generator.
-   [Schema Transform README](./graphitron-schema-transform/README.md) for information on how to transform GraphQL schemas.
-   [Common Module README](./graphitron-common/README.md) for exception handling framework and shared utilities.
-   [Example project README](./graphitron-example/README.md) for an example of how to use Graphitron.
