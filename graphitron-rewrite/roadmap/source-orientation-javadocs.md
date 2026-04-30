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

A reader landing on `FieldBuilder.java` (2 172 lines) or
`TypeFetcherGenerator.java` (1 646 lines) gets minimal in-file orientation;
they have to bounce to the docs to learn what the class is for. The
rewrite tree also has zero `package-info.java` files, which is the
IDE-native place for "what is in this package" blurbs.

## Scope

Two parallel sweeps; either can land independently.

### 1. Class-level Javadoc on the central builders and generators

Three or four sentences per class: scope, the entry-point method(s), the
model output, and a pointer to the relevant doc. `GraphitronSchemaBuilder`
already has a good 11-line top comment; use it as the template.

Targets (existing Javadoc word-counts in parentheses, where shorter
indicates room to grow):

- `FieldBuilder` (~20 words today) — classifies fields per parent type;
  pointer to `code-generation-triggers.md`.
- `TypeBuilder` (~20 words) — classifies named types; two-pass shape;
  pointer to `code-generation-triggers.md`.
- `BuildContext` (~20 words) — shared state for builders; directive helpers;
  warnings channel.
- `JooqCatalog` — raw-jOOQ boundary; lazy resolution.
- `ServiceCatalog` — reflection boundary for `@service` / `@tableMethod`.
- `TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`,
  `GraphitronSchemaClassGenerator`, `GraphitronFacadeGenerator` — each
  states what file it emits and the input model shape.
- `GraphitronSchemaValidator` — invariants enforced; how it composes with
  the classifier (cross-link to the "Validator mirrors classifier
  invariants" principle).

### 2. `package-info.java` files

One per rewrite package, two or three lines each:

```
no.sikt.graphitron.rewrite              — pipeline entry, builders, validator
no.sikt.graphitron.rewrite.model        — sealed type and field hierarchies
no.sikt.graphitron.rewrite.generators   — generator core (Fetchers, Type, Conditions)
no.sikt.graphitron.rewrite.generators.schema — schema-class emitters (object, input, enum)
no.sikt.graphitron.rewrite.generators.util   — utility class emitters (helpers, registries)
no.sikt.graphitron.rewrite.schema       — schema input loader, directive auto-injection
no.sikt.graphitron.rewrite.selection    — hand-rolled selection-set parser; see selection-parser-audit
no.sikt.graphitron.rewrite.catalog      — jOOQ catalog wrappers
```

The selection package note explicitly references
[`selection-parser-audit.md`](selection-parser-audit.md) so a reader who
opens `Lexer.java` looking for argument-resolution code immediately learns
both what it is and that its long-term place in the tree is open.

## Out of scope

- `TypeFetcherGenerator` decomposition. Tracked separately under
  [`decompose-typefetchergenerator.md`](decompose-typefetchergenerator.md).
- `FieldBuilder` decomposition. Already tracked under
  [`decompose-fieldbuilder.md`](decompose-fieldbuilder.md).
- Method-level Javadoc within these classes. The class-level pass is
  the on-ramp gap; per-method docs can grow case by case.
