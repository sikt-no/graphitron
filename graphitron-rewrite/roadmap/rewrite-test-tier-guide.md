---
title: Consolidated test-tier guide
status: Backlog
bucket: cleanup
priority: 7
---

# Consolidated test-tier guide

The rewrite has four test tiers (unit, pipeline, compilation against real
jOOQ, execution against real PostgreSQL). The conventions for each tier are
known and respected on trunk, but the documentation is scattered across
three sources, so a new contributor adding a test has to triangulate to
figure out which tier their test goes in, where the file lives, what
shape it takes, and what they can or cannot assert.

The three sources today:

- [`rewrite-design-principles.md`](../docs/rewrite-design-principles.md)
  states the policy ("Pipeline tests are the primary behavioural tier") and
  bans body-string assertions on generated method bodies.
- [`claude-code-web-environment.md`](../docs/claude-code-web-environment.md)
  documents the build commands and the local DB setup.
- Per-test conventions live in javadoc on individual test classes
  (`GraphitronSchemaBuilderTest`, `GraphQLQueryTest`, etc.).

## Scope

Add `graphitron-rewrite/docs/testing.md` with one section per tier:

- **Unit** — structural invariants on classifiers, builders, and emitters.
  Where: `graphitron/src/test/java/...`. Patterns: targeted constructor
  assertions, no full-pipeline plumbing.
- **Pipeline** — SDL → classified model → generated `TypeSpec`. Where:
  `*PipelineTest` files in `graphitron/src/test/`. Patterns: `TypeSpec`
  shape assertions, banned: code-string body matching.
- **Compilation against real jOOQ** — generated code compiles against the
  test catalog. Where: `graphitron-test` with `mvn compile -pl :graphitron-test
  -Plocal-db`. No hand-written assertions; the compiler is the assertion.
- **Execution against PostgreSQL** — full request → SQL → row round-trip.
  Where: `graphitron-test` with `mvn test -pl :graphitron-test -Plocal-db`.
  Patterns: JDBC round-trip count, returned-row-id sets, structural SQL
  shape via a jOOQ `ExecuteListener`.

Cross-link from each detail doc; the new file is the dispatcher, not a
duplicate of the policy.

## Coordinates with

- [`rebalance-test-pyramid.md`](rebalance-test-pyramid.md) (Backlog) is
  about the *policy* of where new test investment should go. This plan is
  about *surfacing the rules that already apply* in one place. Land
  independently.
- [`docs-as-index-into-tests.md`](docs-as-index-into-tests.md) (Ready,
  deferred) is about the classification doc pointing at specific test
  cases. Complementary; different file.
