Graphitron is a code generation tool that creates source code by linking GraphQL schemas to underlying database models.
Developed by **<a href="https://www.sikt.no"> Sikt – the Norwegian Agency for Shared Services in Education and Research</a>**,
the tool generates precise source code for our systems, ensuring maintainability over time.

Graphitron is used as a Maven plugin, offering two main functionalities:
-   **Java Code Generation**: Creates Java source code from GraphQL schemas.
-   **Schema Transformation**: Modifies GraphQL schemas based on various configurations.

## Documentation

**New to Graphitron?** Start with the [documentation guide](./docs/README.md) for conceptual understanding:
-   [Vision and Goal](./docs/VISION-AND-GOAL.md) - What problem Graphitron solves and why
-   [Graphitron Principles](./docs/GRAPHITRON-PRINCIPLES.md) - Design philosophy for building systems that last decades
-   [What Graphitron Generates](./docs/WHAT-GRAPHITRON-GENERATES.md) - Understanding the generated code structure
-   [Code Generation Triggers](./docs/CODE-GENERATION-TRIGGERS.md) - Quick reference for schema-to-code mapping

**Ready to use Graphitron?** See the technical reference documentation:
-   [Online documentation](https://graphitron.sikt.no/)
-   [Example project README](./graphitron-example/README.md) - Quickstart guide with working example
-   [Java Code Generator README](./graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
-   [Schema Transform README](./graphitron-schema-transform/README.md) - Schema transformation features
-   [Common Module README](./graphitron-common/README.md) - Exception handling framework and shared utilities
