---
id: R391
title: "Graphitron facade: default-case newGraphQL() helper"
status: Backlog
bucket: feature
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Graphitron facade: default-case newGraphQL() helper

The generated `Graphitron` facade exposes `buildSchema(customizer)` and `newExecutionInput(...)` but stops short of the GraphQL engine itself. Every consumer that wires up no extra scalars, types, or directives (the `graphitron-sakila-example` app's `GraphqlEngine`, and ~28 execution-tier tests) therefore repeats the same two lines:

```java
var schema = Graphitron.buildSchema(b -> {});
graphql = GraphQL.newGraphQL(schema).build();
```

Graphitron already owns scalar registration and all default wiring, so the no-extra-wiring case carries no information the facade can't supply. Add a default-case helper `Graphitron.newGraphQL()` returning a `graphql.GraphQL.Builder` (pre-wired from `buildSchema(b -> {})`), collapsing the boilerplate to `graphql = Graphitron.newGraphQL().build();`. Returning the builder (rather than a built `GraphQL`) mirrors the existing `newExecutionInput(...)` factory convention and leaves room for instrumentation / execution strategies without a second overload. The helper is correct for the federation case too, since `buildSchema` already returns the federation-wrapped schema. Emitted in `GraphitronFacadeGenerator`.

