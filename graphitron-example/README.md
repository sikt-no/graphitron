# Graphitron Example

This example demonstrates how to use Graphitron to generate GraphQL resolvers from a given GraphQL schema. The example is based on the Sakila test database. For more information, see [Sakila](https://www.jooq.org/sakila).
The `graphitron-example-server` is a Quarkus application that runs a GraphQL server with the generated resolvers.

[Integration tests](#Integration-tests) are included to ensure that Graphitron generates resolvers that work as expected. 

## Requirements

- Docker - for running the Postgres database
- [Mise](https://mise.jdx.dev/) - for task management (Recommended)
  - Alternatively, you can use maven directly with java 17


## Quickstart

To quickly get started with the Graphitron example, follow these steps (requires Mise) from this directory (`graphitron/graphitron-example`):

1. **Build the project:**

    ```sh
    mise r build
    ```

2. **Start the Graphitron example server:**

    ```sh
    mise r start
    ```

## Submodule structure

### graphitron-example-db

This module uses the JOOQ code generator to generate Java classes from the Sakila database schema. These generated classes are required by Graphitron when generating the resolvers.

#### JOOQ code generation

The JOOQ code generation task is disabled by default.
If you need to regenerate the JOOQ classes, you will need to build the module with the `generate-jooq` profile.
This requires a running Postgres database with the Sakila schema.

1. **Start the Sakila Postgres database:**
    ```sh
    mise r sakila
    ```
2. **Regenerate the JOOQ classes:**
    ```sh
    mise r jooq
    ```

### graphitron-example-spec

This module contains the schema that Graphitron will use to generate the resolvers. The GraphQL schema maps GraphQL types to the JOOQ generated classes based on the Sakila database schema.

When building this module, the `graphitron-maven-plugin` will generate the resolvers based on the schema and the JOOQ classes.

### graphitron-example-server

This module contains the server that will run the generated resolvers. It is based on Quarkus and will start a GraphQL server that can be queried at [http://localhost:8088/graphql](http://localhost:8088/graphql).

GraphiQL is available at [http://localhost:8088/graphiql](http://localhost:8088/graphiql). This is a graphical interface that can be used to query the server.

Quarkus will start the server in development mode, which means that it will automatically reload when changes are made to the code.
Also _devservices_ is enabled, which means that the server will automatically run a Postgres database with the Sakila schema when started.

### graphitron-example-service
This module contains code necessary for more advanced features of Graphitron, such as custom service classes and conditions.

## Integration tests
The project includes integration tests to verify the functionality of the generated GraphQL resolvers. These tests are located in the [graphitron-example-server](graphitron-example-server/src/test/java) module.
There are two types of tests:
 - **Approval** - tests that verify queries against the server and compare the results to expected results. To add a new test, simply add a new query to the `src/test/resources/approval/queries` directory. 
 - **Match** - these tests are more flexible and can be used to verify the results of a query against a custom matcher, e.g. hamcrest. 

The tests use the Quarkus test framework which automatically starts the server before running the tests.