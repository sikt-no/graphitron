---
id: R1020
title: "A @condition parameter typed as a plain Java class is built or rejected, never cast from a Map"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A @condition parameter typed as a plain Java class is built or rejected, never cast from a Map

## Goal

A `@condition` method whose parameter is bound (through `argMapping`) to a GraphQL input argument either receives a value of its declared Java type or fails the build. Today, when the parameter is a plain Java class and the input type has no `@record`, the generated condition call casts the raw argument map straight to that class: `(QueryPersonProfilerFilterRecord) args.get("filter")`. That compiles cleanly and throws `ClassCastException` (`LinkedHashMap`) on every request. When this lands Graphitron either builds the class from the map or rejects the binding at build time with a message that names the parameter, its type, and the accepted shapes. The rejection is the floor: sis may switch the parameter to a `Map`, so a build-time error is what it most needs.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). Schema:

```graphql
personProfiler(filter: QueryPersonProfilerFilterInput!, ...)
  @condition(condition: {className: "...PersonConditions",
                         method: "queryPersonProfilerFilterCondition",
                         argMapping: "input: filter"},
             override: true)
```

Java: `PersonConditions.queryPersonProfilerFilterCondition(Person, QueryPersonProfilerFilterRecord)`, where `QueryPersonProfilerFilterRecord` is a POJO with setters and nested POJOs. The failing cast is in the generated `QueryConditions.personProfilerCondition`.

## Where it lives

The emitter of the cast is not yet located; `ArgCallEmitter` is the condition argument-extraction home and the first place to look. For the "build the class" branch there is precedent: `InputBeanInstantiationEmitter` already builds input beans from argument maps for `@service` parameters, so the Spec can weigh reusing it against rejecting.
