# Graphitron: Foundational Dependencies

Graphitron is built on two foundational dependencies. These aren’t incidental implementation choices—they shape how Graphitron works and what it can do.

## jOOQ

**What it is.** jOOQ generates type-safe Java code from your database schema and provides a fluent API for building SQL queries. It stays close to SQL rather than hiding it behind abstractions.

**Why we chose it.** jOOQ has been actively maintained since 2009. It solves a fundamental problem—type-safe database access—in a way that aligns with our philosophy: embrace SQL rather than replace it. The generated code is readable and debuggable. When something goes wrong, you can see exactly what SQL is being built.

**Licensing.** jOOQ uses dual licensing. The open source edition supports open source databases (PostgreSQL, MySQL, SQLite, and others). Commercial databases require a commercial license.

Graphitron works with either edition. If you’re using PostgreSQL or another supported open source database, you can use Graphitron with jOOQ’s open source version at no cost.

We use the commercial license, which includes rights to the source code. If jOOQ’s maintainers disappeared tomorrow, we could continue.

**How we use it.** We embrace jOOQ fully. Its types and patterns permeate our generated code. We’re not wrapping it; we’re building on it.

## GraphQL-Java

**What it is.** GraphQL-Java is the reference implementation of GraphQL for the Java ecosystem. It parses GraphQL schemas, executes queries, and provides the runtime infrastructure for GraphQL servers.

**Why we chose it.** GraphQL-Java implements an open specification governed by the GraphQL Foundation, not a single company. Multiple implementations exist across languages. We’re betting on the standard as much as the library.

It’s fully open source and has been the standard Java implementation since GraphQL gained adoption.

**How we use it.** We use GraphQL-Java directly without abstraction layers. Graphitron generates code that plugs into GraphQL-Java’s DataFetcher and TypeResolver interfaces.

## Why These Matter

These dependencies aren’t hidden implementation details you can ignore. Understanding them helps you understand Graphitron:

- The generated code uses jOOQ’s DSL to build queries. Reading it requires basic familiarity with jOOQ.
- The generated code implements GraphQL-Java interfaces. Debugging it benefits from understanding how GraphQL-Java executes queries.

We document these dependencies explicitly because they’re foundational. They’re the bet we’ve made.