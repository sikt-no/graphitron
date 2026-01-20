# Graphitron: Foundational Dependencies

Graphitron is built on two foundational dependencies. These aren’t incidental implementation choices—they shape how Graphitron works and what it can do.

## jOOQ

**What it is.** jOOQ generates type-safe Java code from your database schema and provides a fluent API for building SQL queries. It stays close to SQL rather than hiding it behind abstractions.

**Why we chose it.** jOOQ has been actively maintained since 2009. It solves a fundamental problem—type-safe database access—in a way that aligns with our philosophy: embrace SQL rather than replace it. The generated code is readable and debuggable. When something goes wrong, you can see exactly what SQL is being built.

**Licensing.** jOOQ uses dual licensing. The open source edition supports recent versions of open source databases (PostgreSQL, MySQL, SQLite, and others). If you use old versions or commercial databases you'll need to purchase a license.

**How we use it.** We embrace jOOQ fully. Its types and patterns permeate our generated code. We’re not wrapping it; we’re building on it.

**Where you write code.** When using Graphitron, custom logic builds on the code jOOQ generates from your database schema. You write methods that return jOOQ types—mostly `Condition` methods for filtering, sometimes `Field` methods for calculated values—and map these into the GraphQL schema.

This means jOOQ isn’t just an implementation detail of Graphitron. It’s the abstraction layer where your team works. Familiarity with jOOQ’s DSL is essential for extending and customizing what Graphitron generates.

## GraphQL-Java

**What it is.** GraphQL-Java is the reference implementation of GraphQL for the Java ecosystem. It parses GraphQL schemas, executes queries, and provides the runtime infrastructure for GraphQL servers.

**Why we chose it.** GraphQL-Java implements an open specification governed by the GraphQL Foundation, not a single company. Multiple implementations exist across languages. We’re betting on the standard as much as the library.

It’s fully open source and has been the standard Java implementation since GraphQL gained adoption.

**How we use it.** Graphitron generates code that plugs into GraphQL-Java’s DataFetcher and TypeResolver interfaces. You won’t typically write GraphQL-Java code directly—Graphitron handles that.

## Why These Matter

These dependencies shape your experience with Graphitron differently:

**jOOQ is where you work.** Custom logic is written using jOOQ’s DSL. You’ll write Conditions, work with generated table classes, and think in terms of jOOQ’s model. Familiarity with jOOQ is essential.

**GraphQL-Java is under the hood.** You won’t write GraphQL-Java code directly, but if you want to understand what Graphitron generates—or debug it—knowing how GraphQL-Java executes queries helps.

We document these dependencies explicitly because they’re foundational.
