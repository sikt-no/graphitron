# Graphitron Example

This example demonstrates how to use Graphitron to generate GraphQL resolvers from a given GraphQL schema. The example is based on the Sakila test database. For more information, see [Sakila](https://www.jooq.org/sakila).
The `graphitron-example-server` is a Quarkus application that runs a GraphQL server with the generated resolvers.
Due to build time performance considerations, the example is not included in the main build of Graphitron.
When building the example, Maven is thus ran with the `include-graphitron-example` profile.

## Requirements

- Docker - for running the Postgres database)
- [Mise](https://mise.jdx.dev/) - for task management (Recommended)
  - Alternatively, you can use maven directly with java 17


## Quickstart

To quickly get started with the Graphitron example, follow these steps (requires Mise) from this directory (`graphitron/graphitron-example`):

1. **Start the Sakila Postgres database:**

    ```sh
    docker compose up -d
    ```

2. **Build the project:**

    ```sh
    mise run build
    ```

3. **Start the Graphitron example server:**

    ```sh
    mise run start
    ```

## Submodule structure

### graphitron-example-db

This module uses the JOOQ code generator to generate Java classes from the Sakila database schema. These generated classes are required by Graphitron when generating the resolvers.

To build this module, you need a running Postgres database with the Sakila schema.

### graphitron-example-spec

This module contains the schema that Graphitron will use to generate the resolvers. The GraphQL schema maps GraphQL types to the JOOQ generated classes based on the Sakila database schema.

When building this module, the `graphitron-maven-plugin` will generate the resolvers based on the schema and the JOOQ classes.

### graphitron-example-server

This module contains the server that will run the generated resolvers. It is based on Quarkus and will start a GraphQL server that can be queried at [http://localhost:8088/graphql](http://localhost:8088/graphql).

GraphiQL is available at [http://localhost:8088/graphiql](http://localhost:8088/graphiql). This is a graphical interface that can be used to query the server.

The server requires a running Postgres database with the Sakila schema, and will return data from this database based on the queries.