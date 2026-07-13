---
id: R35
title: Class-level Javadoc and `package-info.java` sweep
status: Backlog
bucket: cleanup
priority: 8
theme: docs
depends-on: []
---

# Class-level Javadoc and `package-info.java` sweep

A reader landing on `FieldBuilder.java` (7 274 lines) or
`TypeFetcherGenerator.java` (6 900 lines; both counts measured 2026-07-13
and still growing) gets minimal in-file orientation;
they have to bounce to the docs to learn what the class is for. The
rewrite tree also has zero `package-info.java` files (still true as of
2026-07-13), which is the IDE-native place for "what is in this package"
blurbs.

## Scope

Two parallel sweeps; either can land independently.

### 1. Class-level Javadoc on the central builders and generators

Three or four sentences per class: scope, the entry-point method(s), the
model output, and a pointer to the relevant doc. `GraphitronSchemaBuilder`
already has a good 11-line top comment; use it as the template.

Targets (existing Javadoc word-counts in parentheses, where shorter
indicates room to grow):

- `FieldBuilder` (~20 words today): classifies fields per parent type;
  pointer to `docs/architecture/reference/code-generation-triggers.adoc`.
- `TypeBuilder` (~20 words): classifies named types; two-pass shape;
  pointer to `docs/architecture/reference/code-generation-triggers.adoc`.
- `BuildContext` (~20 words) — shared state for builders; directive helpers;
  warnings channel.
- `JooqCatalog` — raw-jOOQ boundary; lazy resolution.
- `ServiceCatalog` — reflection boundary for `@service` / `@tableMethod`.
- `TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`,
  `GraphitronSchemaClassGenerator`, `GraphitronFacadeGenerator` — each
  states what file it emits and the input model shape.
- `GraphitronSchemaValidator`: invariants enforced; how it composes with
  the classifier (cross-link to "Rejections: validator mirrors classifier
  invariants" in `docs/architecture/explanation/development-principles.adoc`).

### 2. `package-info.java` files

One per rewrite package, two or three lines each. Current package set
(as of 2026-07-13; sub-packages like `schema.federation`, `schema.input`,
`lint.rules`, and `walker.internal` ride along):

```
no.sikt.graphitron.rewrite                    pipeline entry, builders, validator
no.sikt.graphitron.rewrite.model              sealed type and field hierarchies
no.sikt.graphitron.rewrite.generators         generator core (fetcher, conditions, and helper emitters)
no.sikt.graphitron.rewrite.generators.schema  schema-class emitters (object, input, enum)
no.sikt.graphitron.rewrite.schema             schema input loader, directive auto-injection
no.sikt.graphitron.rewrite.selection          hand-rolled selection-set parser (stays per R30)
no.sikt.graphitron.rewrite.catalog            jOOQ catalog wrappers, LSP classification snapshot
no.sikt.graphitron.rewrite.compile            incremental compile engine (dev loop)
no.sikt.graphitron.rewrite.lint               lint engine and rules
no.sikt.graphitron.rewrite.session            session state config and warnings
no.sikt.graphitron.rewrite.walker             mutation/service shape walkers
```

The selection package note should point at the R30 outcome recorded in
[`changelog.md`](changelog.md): the audit concluded the parser IS needed
(`@experimental_constructType(selection:)` carries a generation-time string
argument that graphql-java's execution-time selection APIs cannot
substitute), so a reader who opens `Lexer.java` looking for
argument-resolution code immediately learns what it is and why it stays.

## Out of scope

- `TypeFetcherGenerator` decomposition. Tracked separately under
  [`decompose-typefetchergenerator.md`](decompose-typefetchergenerator.md).
- `FieldBuilder` decomposition. Shipped under R6; see
  [`changelog.md`](changelog.md).
- Method-level Javadoc within these classes. The class-level pass is
  the on-ramp gap; per-method docs can grow case by case.
