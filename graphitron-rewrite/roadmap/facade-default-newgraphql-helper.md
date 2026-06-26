---
id: R391
title: "Graphitron facade: default-case newGraphQL() helper"
status: In Review
bucket: feature
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Graphitron facade: default-case newGraphQL() helper

> **Shipped (In Review).** `newGraphQL()` emitted in `GraphitronFacadeGenerator`
> (returns `GraphQL.Builder`, body `GraphQL.newGraphQL(buildSchema(customizer -> {}))`);
> 16/16 unit tests in `GraphitronFacadeGeneratorTest` green (added
> `newGraphQL_isPublicStaticReturningGraphQLBuilder`,
> `newGraphQL_isPresentExactlyOnceInFederationBuild`, no body-string assertion).
> Federation correctness pinned by `FederationBuildSmokeTest.newGraphQLBuildsFederationWrappedEngine`.
> Sweep: `GraphqlEngine` plus 23 default-case execution-tier sites converted to
> `Graphitron.newGraphQL().build()` (enumerated in the landing commit); two-arg
> `buildSchema(b -> {}, fed -> {})` federation sites and the raw-SDL spike left as-is.
> `mvn clean install -Plocal-db` green end-to-end.

> Add a default-case `Graphitron.newGraphQL()` factory to the generated facade,
> returning a `graphql.GraphQL.Builder` pre-wired from the schema, so consumers
> with no extra scalars/types/directives can write `Graphitron.newGraphQL().build()`
> instead of `GraphQL.newGraphQL(Graphitron.buildSchema(b -> {})).build()`. Emitted
> by `GraphitronFacadeGenerator` alongside the existing `buildSchema` and
> `newExecutionInput` methods; returning a builder (not a built engine) mirrors the
> `newExecutionInput(...)` convention and leaves instrumentation/execution-strategy
> configuration open without a second overload.

## Motivation

The generated `Graphitron` facade (`GraphitronFacadeGenerator`) is the
hand-written-feeling entry point for consumers: `buildSchema(customizer)` for the
schema and `newExecutionInput(...)` for per-request wiring. It stops one step short
of the engine, so every consumer that adds no extra scalars, types, or directives
repeats the same two lines:

```java
var schema = Graphitron.buildSchema(b -> {});
graphql = GraphQL.newGraphQL(schema).build();
```

This shows up in the consumer-facing exemplar (`graphitron-sakila-example`'s
`GraphqlEngine`, `src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlEngine.java:16-17`)
and across the execution-tier test suite. Graphitron already owns scalar
registration and all default wiring; in the no-extra-wiring case the customizer
lambda is empty and carries no information the facade can't supply itself. The
empty `b -> {}` is pure ceremony, and `GraphQL.newGraphQL(...)` forces the consumer
to import and name a graphql-java type the facade could hand them directly.

## Design

Add one public static method to the emitted facade:

```java
public static GraphQL.Builder newGraphQL() {
    return GraphQL.newGraphQL(buildSchema(customizer -> {}));
}
```

Emitted in `GraphitronFacadeGenerator.generate(...)`, appended to `classBuilder`
after `newExecutionInput` (so the method order becomes `buildSchema`,
`newExecutionInput`, `newGraphQL`, plus the federation `buildSchema` overload last
when `federationLink`). Type references via javapoet `ClassName`:
`graphql.GraphQL` and its nested `graphql.GraphQL.Builder` (new; the facade already
references `graphql.schema.GraphQLSchema` and `graphql.ExecutionInput`, so no new
module dependency: graphql-java is already on the generated module's classpath).

The body calls the facade's own single-arg `buildSchema(b -> {})` rather than
re-deriving the schema, keeping `buildSchema` the single producer of the schema.

**Return a builder, not a built engine.** `newGraphQL()` returns
`GraphQL.Builder`; the consumer chains `.build()`. This mirrors `newExecutionInput`,
which returns `ExecutionInput.Builder` rather than a built `ExecutionInput`, and
preserves the extension point (instrumentation, execution strategies) without
forcing a second overload later. The call site reads `Graphitron.newGraphQL().build()`.

**Federation correctness.** No federation-specific overload is needed. When
`federationLink` is true the single-arg `buildSchema(b -> {})` already returns the
federation-wrapped schema (the federation branch of `buildSchemaJavadoc`: "the
returned GraphQLSchema is wrapped with Federation.transform ... Do not wrap it
again"), and `GraphQL.newGraphQL(wrapped).build()` is the correct call there. A
consumer who needs a custom entity fetcher still uses the two-arg
`buildSchema(schemaCustomizer, federationCustomizer)` + `GraphQL.newGraphQL(...)`
path explicitly; `newGraphQL()` is the default-case convenience only.

This is the one place `newGraphQL()` could be silently wrong (a build that loses or
double-applies the federation wrap), and it is the case the unit-tier facade test
cannot reach (the generator emits the same method regardless of `federationLink`).
The wrapping is a behaviour of the emitted `GraphitronSchema.build(...)`, not of
`newGraphQL()` itself, so the claim must be pinned by an execution assertion, not
prose: `graphitron-sakila-example` is a federation-linked module (its
`FederationBuildSmokeTest` exercises the two-arg path), so one assertion there must
build the engine via `Graphitron.newGraphQL().build()` and assert `_service { sdl }`
resolves with no errors. That proves single-arg `buildSchema` → `newGraphQL()`
yields a correctly federation-wrapped engine end-to-end. See Test coverage.

**Javadoc.** Document that it is the zero-config convenience equivalent to
`GraphQL.newGraphQL(buildSchema(b -> {}))`, that it returns a builder the caller
`.build()`s, and that consumers needing schema customization (extra scalars, custom
directives, a federation entity fetcher) should use `buildSchema(...)` directly.

## Call-site sweep

Two distinct intents, deliberately separated so the reviewer knows the minimum that
must convert for coverage versus what is readability cleanup.

**Load-bearing (validates the helper, must convert):**

- `graphitron-sakila-example` `GraphqlEngine` (the documented consumer exemplar);
  this is the canonical default-case consumer the helper exists for.
- One non-federation execution-tier site (e.g. `GraphQLQueryTest:80-81`), so the
  generated `newGraphQL()` is exercised against a real schema + database.
- One federation assertion in `FederationBuildSmokeTest` building via
  `Graphitron.newGraphQL().build()` (see Federation correctness).

**Cleanup (deduplication, may ride along or defer):** the remaining execution-tier
sites under `graphitron-sakila-example/src/test/.../querydb/` and `.../internal/`
that follow the single-arg pattern. This is readability churn, not coverage; the
helper is already proven by the load-bearing conversions plus the compile tier. The
In Progress commit must **enumerate the exact file:line sites it converts**, so the
sweep is auditable at the In Review gate rather than re-derivable from a grep.

**Explicitly NOT swept (enumerate as excluded in the commit):** sites that pass a
non-empty customizer, sites where the `schema` local is reused for anything besides
the engine, and the two-arg `buildSchema(b -> {}, fed -> {})` federation sites in
`FederationBuildSmokeTest` (except the single added `newGraphQL()` assertion above),
which deliberately exercise the two-arg federation path. The decision predicate is
"empty single-arg customizer AND `schema` used for nothing but `GraphQL.newGraphQL`";
a site that grep-matches `GraphQL.newGraphQL(schema).build()` but reuses `schema`
must not be swept.

The example app's `README.md` mentions `Graphitron.buildSchema(b -> {})`; update the
"Recommended test pattern" / `GraphqlEngine` prose to show `Graphitron.newGraphQL()`.

## Test coverage

Unit-tier, in `GraphitronFacadeGeneratorTest`, **structural assertions only**. Code-string
matching on the generated method body is banned at every tier (`testing.adoc`), so
there is deliberately no assertion on the body's delegation spelling; that the body
delegates correctly is proven by the load-bearing call-site conversions compiling
and executing (below), not by string-matching `MethodSpec.code()`.

- `generatedClass_exposesBuildSchemaNewExecutionInputAndNewGraphQL` replaces the
  current `..._exposesBuildSchemaAndOneNewExecutionInputMethod` (lines 35-39), whose
  `containsExactly("buildSchema", "newExecutionInput")` would otherwise fail; assert
  `containsExactly("buildSchema", "newExecutionInput", "newGraphQL")` for
  non-federation.
- `newGraphQL_isPublicStaticReturningGraphQLBuilder`: modifiers `PUBLIC, STATIC`,
  return type `graphql.GraphQL.Builder`, zero parameters.
- Federation case: assert `newGraphQL` is present exactly once (it does not
  duplicate per federation overload; the federation build adds only a second
  `buildSchema`).

Execution tier (the behavioural proof that the method works against a real schema +
database, replacing any body-string assertion):

- The non-federation load-bearing conversion (`GraphQLQueryTest` and the
  `GraphqlEngine` exemplar) exercises `Graphitron.newGraphQL().build()` end-to-end.
- The federation assertion in `FederationBuildSmokeTest` pins that single-arg
  `buildSchema` → `newGraphQL()` yields a correctly federation-wrapped engine
  (`_service { sdl }` resolves, no errors).

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` (build-fixtures → test →
compile-spec → execute-spec) is the gate.

## Out of scope

- Any overload taking a customizer (`newGraphQL(Consumer<GraphQLSchema.Builder>)`).
  The customizer path is `buildSchema(customizer)` + `GraphQL.newGraphQL(...)`; this
  item is the zero-config convenience only.
- A federation-specific `newGraphQL` overload (entity-fetcher customization stays on
  the two-arg `buildSchema`).
- Returning a built `GraphQL` instead of a builder (rejected above).
- Touching the legacy root modules (out of scope per CLAUDE.md).

