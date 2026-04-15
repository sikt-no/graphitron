# Graphitron Documentation

This folder contains documentation about Graphitron's design, philosophy, and how the code generator works.

## Where to Start

**New to Graphitron?** Start with these in order:

1. **[Vision and Goal](vision-and-goal.md)** - What problem Graphitron solves and how it approaches the solution. Read this first to understand why Graphitron exists.

2. **[Graphitron Principles](graphitron-principles.md)** - The design philosophy behind Graphitron. Explains the long-term thinking that shapes architectural decisions.

3. **[Dependencies](dependencies.md)** - Why we chose jOOQ and GraphQL-Java as foundational dependencies, and what that means for you.

## Code Generation Reference

4. **[Code Generation Triggers](code-generation-triggers.md)** - What schema patterns and directives trigger what classification and generation. Organised around the rewrite pipeline's sealed type hierarchy.

## Security

5. **[Security](security.md)** - Graphitron's security model and why we chose database-level security enforcement.

## Document Summary

| Document | Purpose | When to Read |
|----------|---------|--------------|
| [Vision and Goal](vision-and-goal.md) | Problem statement and design approach | First, to understand the "why" |
| [Graphitron Principles](graphitron-principles.md) | Design philosophy and long-term thinking | To understand architectural decisions |
| [Dependencies](dependencies.md) | Why jOOQ and GraphQL-Java | When evaluating Graphitron or learning to extend it |
| [Code Generation Triggers](code-generation-triggers.md) | Schema → classification → generated code | When writing schemas, implementing generators, or troubleshooting |
| [Security](security.md) | Security model explanation | When designing access control |

## Active Development

- **[Rewrite Roadmap](rewrite-roadmap.md)** - Remaining generator work, design principles, and known gaps
- **[Call-Site Unification](call-site-unification.md)** - Design for unifying argument-extraction patterns across condition, service, and table method call sites
- **[Generator Building Blocks](generator-building-blocks.md)** - Common abstractions to extract from generators before implementing remaining stubs

## Other Documentation

- **[Main README](/README.md)** - Project overview and getting started
- **[Example README](/graphitron-example/README.md)** - Working example with Sakila database
- **[Java Codegen README](/graphitron-codegen-parent/graphitron-java-codegen/README.md)** - Complete directive reference with legacy examples
- **[Schema Transform README](/graphitron-schema-transform/README.md)** - Schema transformation features
