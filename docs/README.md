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
6. **[Runtime Extension Points](runtime-extension-points.md)** — How to wire `GraphitronContext`, jOOQ `ExecuteListener`, and PostgreSQL RLS into your application.
7. **[Example README](/graphitron-example/README.md)** — Working example with the Sakila database.

## Working on Graphitron internals (contributors)

You're implementing generators, adding field variants, or fixing bugs in the rewrite pipeline.

4. **[Code Generation Triggers](code-generation-triggers.md)** — The full classification taxonomy: schema patterns → sealed variants → generated output. This is the reference for what the code does.
5. **[Rewrite Roadmap](rewrite-roadmap.md)** — Design principles for the rewrite, remaining generator work, and known gaps.

Active implementation plans (read when you're working on the specific feature):
- **[Paginated Fields](paginated-fields.md)** — Dynamic ordering cursors, backward pagination, known bugs.
- **[ConditionFilter Builder Path](condition-filter-builder.md)** — Reading `@condition` directives in the builder, override flag, tests.

---

## Quick Reference

| Document | Audience | Purpose |
|----------|----------|---------|
| [Vision and Goal](vision-and-goal.md) | Everyone | Problem statement and design approach |
| [Graphitron Principles](graphitron-principles.md) | Everyone | Design philosophy and long-term thinking |
| [Dependencies](dependencies.md) | Everyone | Why jOOQ and GraphQL-Java |
| [Java Codegen README](/graphitron-codegen-parent/graphitron-java-codegen/README.md) | Schema authors | Directive reference with examples |
| [Security](security.md) | Schema authors | Security model explanation |
| [Runtime Extension Points](runtime-extension-points.md) | Schema authors | GraphitronContext, ExecuteListener, RLS |
| [Example README](/graphitron-example/README.md) | Schema authors | Working Sakila example |
| [Code Generation Triggers](code-generation-triggers.md) | Contributors | Schema → classification → generated code |
| [Rewrite Roadmap](rewrite-roadmap.md) | Contributors | Design principles, remaining work, known gaps |
| [Paginated Fields](paginated-fields.md) | Contributors | Pagination implementation plan |
| [ConditionFilter Builder Path](condition-filter-builder.md) | Contributors | @condition builder plan |

## Other Documentation

- **[Main README](/README.md)** — Project overview and getting started
- **[Schema Transform README](/graphitron-schema-transform/README.md)** — Schema transformation features (feature flags, Federation, Relay)
- **[Common Module README](/graphitron-common/README.md)** — Exception handling framework and shared utilities
