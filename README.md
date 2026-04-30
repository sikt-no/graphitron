Graphitron is a code generation tool that creates source code by linking GraphQL schemas to underlying database models.
Developed by **<a href="https://www.sikt.no"> Sikt – the Norwegian Agency for Shared Services in Education and Research</a>**,
the tool generates precise source code for our systems, ensuring maintainability over time.

Graphitron is used as a Maven plugin, offering two main functionalities:
-   **Java Code Generation**: Creates Java source code from GraphQL schemas.
-   **Schema Transformation**: Modifies GraphQL schemas based on various configurations.

## Documentation

**New to Graphitron?** Start with the [documentation guide](./docs/README.adoc) for conceptual understanding:
-   [Vision and Goal](./docs/vision-and-goal.adoc) - What problem Graphitron solves and why
-   [Graphitron Principles](./docs/graphitron-principles.adoc) - Design philosophy for building systems that last decades
-   [Code Generation Triggers](./graphitron-rewrite/docs/code-generation-triggers.adoc) - Schema patterns → what gets generated

**Ready to use Graphitron?** See the technical reference documentation:
-   [Online documentation](https://graphitron.sikt.no/)
-   [Example project README](./graphitron-example/README.md) - Quickstart guide with working example
-   [Java Code Generator README](./graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
-   [Schema Transform README](./graphitron-schema-transform/README.md) - Schema transformation features
-   [Common Module README](./graphitron-common/README.md) - Exception handling framework and shared utilities

## Building

`mvn install` at the repo root builds the legacy plugin tree
(`graphitron-common`, `graphitron-java-codegen`, `graphitron-maven-plugin`,
`graphitron-schema-transform`, etc).

Rewrite-only work can build standalone from the rewrite aggregator without
any legacy module in the dependency graph:

```bash
mvn install -f graphitron-rewrite/pom.xml -Plocal-db
```

See [Getting Started](./graphitron-rewrite/docs/getting-started.adoc) for the rewrite build flow.
