---
id: R254
title: Generated GraphitronSchema emission must have bounded chain depth
status: Spec
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Generated GraphitronSchema emission must have bounded chain depth

## Problem

Generated `GraphitronSchema.java` is one fluent chained expression whose depth scales with schema element count. Every root type, additional type, scalar registration, directive definition, and applied schema directive folds another `.with…(...)` / `.additionalType(...)` link onto the same `schemaBuilder` call. Applied directives on per-type classes have the same shape: `.withAppliedDirective(...)` chained once per surviving directive, each carrying a `.argument(...).argument(...)` sub-chain whose length scales with the directive's argument count.

Under `quarkus:dev` + `graphitron:dev`, any regen-then-incremental-compile cycle blows javac's stack. Both observed traces show the canonical chained-call attribution loop (`Attr.attribTree → visitApply → visitSelect → attribTree → visitApply → …` repeating); the top frames are incidental class-load / name-table work that happened to push it over the edge. Cold `mvn install` builds mask the problem because batch compilation primes name tables and class symbols, so per-frame work is cheap; the dev loop's single-file incremental compile pays those costs cold and tips over. A federation `@link` version bump just happened to surface it by expanding the imported directive set — but any regen of a non-trivial schema is over the safe budget.

Bumping `-Xss` treats the symptom, not the cause: the chain still grows linearly with schema size, just past a higher cliff.

## Emission sites in scope

Inventoried in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/`:

1. **`schema/GraphitronSchemaClassGenerator.java` lines 185–225** — `schemaBuilder` assembly. One `CodeBlock` builds `newSchema().query(...).mutation(...).additionalType(...) × N .additionalType(scalar) × M .additionalDirective(...) × K .withSchemaAppliedDirectives(...).codeRegistry(...)` as a single expression-statement. Cardinality: schema types + scalars + directives + schema-applied directives. This is the primary failure site.

2. **`schema/AppliedDirectiveEmitter.java` lines 100–122** — `buildApplication()` returns one `CodeBlock` per applied directive: `newDirective().name(...).argument(...).argument(...) × A .build()`. Cardinality: arguments per applied directive. Callers fold its output back into a parent chain.

3. **Caller integration in per-type generators** — `ObjectTypeGenerator.java:140–142`, `InputTypeGenerator.java:79–80`, `EnumTypeGenerator.java:56–57` each loop `.applicationsFor(type)` and append every block into a single `.withAppliedDirective(...).withAppliedDirective(...) × D` chain on the type's builder. Cardinality: applied directives per type. Per-type classes (`*Type.java`) carry the same hazard as `GraphitronSchema.java`, just at smaller scale.

Out of scope (already statement-shaped or bounded by GraphQL type nesting depth, which is structurally small):

- `AppliedDirectiveEmitter.emitInputType` lines 166–202 (recursive `nonNull(list(...))`) — bounded by `[[[T!]!]!]`-style nesting depth, which schemas don't push.
- `QueryConditionsGenerator.java:160–173` — already `.addStatement()` per filter param.
- `ArgCallEmitter.buildMapChain` / `buildNestedInputFieldExtraction` — ternaries emitted inside `addStatement(...)` context, bounded by input-field path depth.

## Approach

Two complementary moves. Both apply to all three sites above; the principle is the same and the emission helpers should look alike.

**Move 1: statement-per-element on a shared builder var.** Replace the single fluent chain with one declared local plus N short statements.

Before (`GraphitronSchemaClassGenerator`):
```java
GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema()
    .query(QueryType.type())
    .additionalType(FooType.type())
    .additionalType(BarType.type())
    // …repeated per type, scalar, directive…
    .codeRegistry(codeRegistry.build());
```

After:
```java
GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema();
schemaBuilder.query(QueryType.type());
schemaBuilder.additionalType(FooType.type());
schemaBuilder.additionalType(BarType.type());
// …repeated per type, scalar, directive…
schemaBuilder.codeRegistry(codeRegistry.build());
```

Same for `withAppliedDirective` on per-type builders, same for `.argument(...)` on per-directive applied-directive builders.

**Move 2: factor each non-trivial value into a `private static` builder method.** For applied-directive applications (which themselves have a sub-chain that scales with argument count), emit each as its own method so the call site at the schema/type builder is a single name reference:

```java
private static GraphQLAppliedDirective linkDirective() {
    GraphQLAppliedDirective.Builder b = GraphQLAppliedDirective.newDirective().name("link");
    b.argument(GraphQLAppliedDirectiveArgument.newArgument().name("url").type(...).valueLiteral(...).build());
    b.argument(GraphQLAppliedDirectiveArgument.newArgument().name("import").type(...).valueLiteral(...).build());
    return b.build();
}
```

Call site is then `schemaBuilder.withSchemaAppliedDirectives(List.of(linkDirective(), ...))` — one entry per directive, all flat. Same shape for synthesised scalars (currently inlined as a `newScalar().name(...).coercing(...).build()` sub-chain at the registration callsite). Factoring per element also gives each piece a name that shows up in stack traces if anything goes wrong at schema-build time, which is a small readability win on top.

The two moves together give us O(1) chain depth per statement regardless of schema size, generated code that reads as a flat list of "register X, register Y, register Z" (which mirrors how a human would write the same schema by hand), and per-element helper methods that a developer can jump to to see what each piece is.

## Test strategy

Test the structural property, not the symptom. The bug is "the generator emits one expression whose chain depth scales with schema size"; we can assert that directly without invoking javac and without depending on `-Xss` / JVM version / warm-vs-cold compile state.

New generator-side test (a sibling to `AppliedDirectiveEmitterTest` and the existing schema-class generator tests):

- Run the generator on the existing pipeline-tier fixtures (covers a realistic mix of types, directives, federation `@link` imports).
- Parse each emitted `.java` source — javapoet's `MethodSpec`/`CodeBlock` doesn't expose a tree, so use `JavaParser` or a string-level chain counter — and assert: **no expression-statement in any emitted file contains more than a fixed N chained method calls**. N = 16 is plenty conservative (cold-stack budget tolerates several hundred); pick something tight enough that a regression that re-introduces a long chain trips the test immediately.
- Smaller complementary test: emit the same schema at two sizes (few types vs many types) and assert statement count in `GraphitronSchema.build()`'s body grows with schema size. That pins the shape: "many short statements", not "one long chain that happens to be under N today".

Existing semantic tests (presence of `.argument(...)` calls with expected values, etc.) must keep passing — the change is shape-preserving at the build-result level.

## Acceptance criteria

- No expression-statement in any generated `.java` file under the pipeline fixtures exceeds the chain-depth bound (asserted by new test).
- Statement count in `GraphitronSchema.build()` scales with schema element count (asserted by new test).
- All existing generator and pipeline tests pass.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.
- Manual verification: regen + javac on a representative schema with default `-Xss` does not throw `StackOverflowError`. Not a CI gate (too fragile), but a hand-check the implementer runs at the end.

## Out of scope

- Bumping `-Xss` in the maven-compiler-plugin or in Quarkus dev's forked JVM.
- Restructuring the `AppliedDirectiveEmitter.emitInputType` recursion (bounded by GraphQL type nesting depth).
- Restructuring statement-shaped sites already inventoried as safe.
- Any wider readability refactor of generated code beyond the bounded-chain-depth shape change.
