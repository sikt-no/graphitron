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
-   [Code Generation Triggers](./docs/architecture/reference/code-generation-triggers.adoc) - Schema patterns → what gets generated

**Ready to use Graphitron?** See the technical reference documentation:
-   [Online documentation](https://graphitron.sikt.no/)
-   [Tutorial](./docs/manual/tutorial/index.adoc) - A fresh checkout to a working GraphQL query against the Sakila example
-   [How-to guides](./docs/manual/how-to/index.adoc) - Day-to-day recipes: joins, conditions, mutations, services, pagination, errors, federation
-   [Reference](./docs/manual/reference/index.adoc) - Every directive, Maven plugin parameter, runtime API entry point, and diagnostic code
-   [Example project README](./graphitron-sakila-example/README.md) - A runnable Quarkus + JAX-RS reference application

## Building

The repo root is a single Maven reactor. Build it with the `local-db` profile
(which switches jOOQ codegen to a native PostgreSQL; see `CLAUDE.md` for the
catalog-jar footgun):

```bash
mvn install -Plocal-db
```

See the [Tutorial](./docs/manual/tutorial/index.adoc) for the end-to-end build and query flow.
