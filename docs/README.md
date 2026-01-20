# Graphitron Documentation

This folder contains documentation about Graphitron's design, philosophy, and how the code generator works.

## Where to Start

**New to Graphitron?** Start with these in order:

1. **[Vision and Goal](VISION-AND-GOAL.md)** - What problem Graphitron solves and how it approaches the solution. Read this first to understand why Graphitron exists.

2. **[Graphitron Principles](GRAPHITRON-PRINCIPLES.md)** - The design philosophy behind Graphitron. Explains the long-term thinking that shapes architectural decisions.

3. **[Dependencies](DEPENDENCIES.md)** - Why we chose jOOQ and GraphQL-Java as foundational dependencies, and what that means for you.

## Understanding Code Generation

**Ready to understand how Graphitron generates code?**

4. **[What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md)** - Vocabulary and taxonomy of generated code. Explains the classes, naming conventions, and patterns you'll see in generated output.

5. **[Query Taxonomy](QUERY-TAXONOMY.md)** - Vocabulary for discussing Queries (methods that execute SQL) and QueryParts (methods that build SQL fragments).

## Security

6. **[Security](SECURITY.md)** - Graphitron's security model and why we chose database-level security enforcement.

## Document Summary

| Document | Purpose | When to Read |
|----------|---------|--------------|
| [Vision and Goal](VISION-AND-GOAL.md) | Problem statement and design approach | First, to understand the "why" |
| [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) | Design philosophy and long-term thinking | To understand architectural decisions |
| [Dependencies](DEPENDENCIES.md) | Why jOOQ and GraphQL-Java | When evaluating Graphitron or learning to extend it |
| [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md) | Generated class structure | When learning the codebase or debugging |
| [Query Taxonomy](QUERY-TAXONOMY.md) | Queries and QueryParts vocabulary | When discussing query generation features |
| [Security](SECURITY.md) | Security model explanation | When designing access control |

## Other Documentation

- **[Main README](/README.md)** - Project overview and getting started
- **[Example README](/graphitron-example/README.md)** - Working example with Sakila database
- **[Java Codegen README](/graphitron-codegen-parent/graphitron-java-codegen/README.md)** - Complete directive reference
- **[Schema Transform README](/graphitron-schema-transform/README.md)** - Schema transformation features
