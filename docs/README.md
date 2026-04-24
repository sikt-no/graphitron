# Graphitron Documentation

This folder contains documentation about Graphitron's design, philosophy, and how the code generator works.

## Start Here (everyone)

Read these three in order. They're short (~4 pages total) and give you the vocabulary for everything else.

1. **[Vision and Goal](vision-and-goal.md)** — What problem Graphitron solves and how it approaches the solution.
2. **[Graphitron Principles](graphitron-principles.md)** — The design philosophy behind Graphitron. Explains the long-term thinking that shapes architectural decisions.
3. **[Dependencies](dependencies.md)** — Why we chose jOOQ and GraphQL-Java as foundational dependencies, and what that means for you.

After these three, your next step depends on what you're doing:

---

## Using Graphitron (schema authors)

You're writing a GraphQL schema and want Graphitron to generate the wiring code.

4. **[Java Codegen README](/graphitron-codegen-parent/graphitron-java-codegen/README.md)** — Complete directive reference with examples. This is where you learn `@table`, `@field`, `@service`, `@splitQuery`, and every other directive.
5. **[Security](security.md)** — Graphitron's security model and why we chose database-level enforcement.
6. **[Runtime Extension Points](../graphitron-rewrite/docs/runtime-extension-points.md)** — How to wire `GraphitronContext`, jOOQ `ExecuteListener`, and PostgreSQL RLS into your application.
7. **[Example README](/graphitron-example/README.md)** — Working example with the Sakila database.

## Working on Graphitron internals (contributors)

See **[graphitron-rewrite/docs/README.md](../graphitron-rewrite/docs/README.md)** for the full rewrite-development index: classification taxonomy, model diagrams, design principles, roadmap, and active plans.

---

## Other Documentation

- **[Main README](/README.md)** — Project overview and getting started
- **[Schema Transform README](/graphitron-schema-transform/README.md)** — Schema transformation features (feature flags, Federation, Relay)
- **[Common Module README](/graphitron-common/README.md)** — Exception handling framework and shared utilities
