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
5. **[Rewrite Model](rewrite-model.md)** — Visual Mermaid diagrams of the sealed type hierarchy: field variants, type variants, and support/composition types.
6. **[Rewrite Design Principles](rewrite-design-principles.md)** — Architectural and technical principles governing the rewrite pipeline (generation-thinking, sealed hierarchies, classification boundaries, test tiers, etc.).
7. **[Rewrite Roadmap](planning/rewrite-roadmap.md)** — Remaining generator work. Full set of in-flight plans lives under [planning/](planning/).

Active implementation plans (read when you're working on the specific feature):
- **[Argument Resolution](planning/argument-resolution.md)** — Unified argument classification, @condition support, lookup VALUES generation.
- **[Legacy PlatformId](planning/legacy-platform-id.md)** — Classifying legacy `id: ID!` mutation input fields that bind to composite platform keys via record-level `getId`/`setId`.

---

## Other Documentation

- **[Main README](/README.md)** — Project overview and getting started
- **[Schema Transform README](/graphitron-schema-transform/README.md)** — Schema transformation features (feature flags, Federation, Relay)
- **[Common Module README](/graphitron-common/README.md)** — Exception handling framework and shared utilities
