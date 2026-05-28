---
id: R254
title: Generated GraphitronSchema emission must have bounded chain depth
status: In Review
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-28
---

# Generated GraphitronSchema emission must have bounded chain depth

## Problem

Generated `GraphitronSchema.java` is one fluent chained expression whose depth scales with schema element count. Every root type, additional type, scalar registration, directive definition, and applied schema directive folds another `.with…(...)` / `.additionalType(...)` link onto the same `schemaBuilder` call. Applied directives on per-type classes have the same shape: `.withAppliedDirective(...)` chained once per surviving directive, each carrying a `.argument(...).argument(...)` sub-chain whose length scales with the directive's argument count.

Under `quarkus:dev` + `graphitron:dev`, any regen-then-incremental-compile cycle blows javac's stack. Both observed traces show the canonical chained-call attribution loop (`Attr.attribTree → visitApply → visitSelect → attribTree → visitApply → …` repeating); the top frames are incidental class-load / name-table work that happened to push it over the edge. Cold `mvn install` builds mask the problem because batch compilation primes name tables and class symbols, so per-frame work is cheap; the dev loop's single-file incremental compile pays those costs cold and tips over. A federation `@link` version bump just happened to surface it by expanding the imported directive set — but any regen of a non-trivial schema is over the safe budget.

Bumping `-Xss` treats the symptom, not the cause: the chain still grows linearly with schema size, just past a higher cliff.

## Emission sites in scope

Inventoried in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/`:

1. **`schema/GraphitronSchemaClassGenerator.java` lines 185–225** — `schemaBuilder` assembly. One `CodeBlock` builds `newSchema().query(...).mutation(...).additionalType(...) × N .additionalType(scalar) × M .additionalDirective(...) × K .withSchemaAppliedDirectives(...).codeRegistry(...)` as a single expression-statement. Cardinality: schema types + scalars + directives + schema-applied directives. This is the primary failure site.

2. **`schema/DirectiveDefinitionEmitter.java` lines 50–83** — `buildDefinition()` returns one `CodeBlock` per surviving directive definition: `newDirective().name(...).description(...).repeatable(...).validLocation(...) × L .argument(.name(...).type(...).description(...).defaultValueProgrammatic(...).build()) × A .build()`. Folded into the schema chain at site 1 via the `.additionalDirective(buildDefinition(dir))` loop (`GraphitronSchemaClassGenerator.java:213–215`). Cardinality: locations + arguments per directive. Federation imports (`@link`, `@composeDirective`, `@authenticated`, `@policy`, `@requiresScopes`, `@key`, `@shareable`) all surface here — the same import-set whose expansion surfaced the bug.

3. **`schema/AppliedDirectiveEmitter.java` lines 116–138** — `buildApplication()` returns one `CodeBlock` per applied directive: `newDirective().name(...).argument(.name(...).type(...).valueLiteral(...).build()) × A .build()`. Cardinality: arguments per applied directive. Callers fold its output back into a parent chain (the schema chain via `applicationsForSchema(...)`, the per-type chain via `applicationsFor(...)`).

4. **Per-type generator bodies** — each emits one fluent expression whose chain depth scales with that type's element count. Sites:
   - `ObjectTypeGenerator.java:127–143` (`buildObjectTypeSpec`) — `newObject().name(...).description(...).withInterface(...) × I .field(...) × F .withAppliedDirective(...) × D .build()`.
   - `ObjectTypeGenerator.java:165–190` (`buildInterfaceTypeSpec`) — `newInterface().name(...).description(...).field(...) × F .withAppliedDirective(...) × D .build()`. Same fields-per-type scaling as the object case.
   - `ObjectTypeGenerator.java:194–218` (`buildUnionTypeSpec`) — `newUnionType().name(...).description(...).possibleType(...) × M .withAppliedDirective(...) × D .build()`. Smaller cardinality (members are typically few), but the shape is identical and the per-*Type.java chain-depth assertion in the test plan will run against `<Name>Type.java` outputs from this method too.
   - `InputTypeGenerator.java:69–82` — `newInputObject().name(...).description(...).field(...) × F .withAppliedDirective(...) × D .build()`.
   - `EnumTypeGenerator.java:45–59` — `newEnum().name(...).description(...).value(...) × V .withAppliedDirective(...) × D .build()`.

   Field- and value-per-type cardinality (typically 5–30) dominates applied-directives-per-type (usually 0–1), so Move 1 must flatten the whole fluent expression in each body — not just the `.withAppliedDirective` tail. Per-type classes (`*Type.java`) carry the same hazard as `GraphitronSchema.java` at smaller scale, and the `.field(...)` / `.value(...)` / `.possibleType(...)` chain is the dominant contributor.

Out of scope (already statement-shaped or bounded by GraphQL type nesting depth, which is structurally small):

- `AppliedDirectiveEmitter.emitInputType` lines 182–215 (recursive `nonNull(list(...))`) — bounded by `[[[T!]!]!]`-style nesting depth, which schemas don't push.
- `QueryConditionsGenerator.java:160–173` — already `.addStatement()` per filter param.
- `ArgCallEmitter.buildMapChain` / `buildNestedInputFieldExtraction` — ternaries emitted inside `addStatement(...)` context, bounded by input-field path depth.

## Approach

Two complementary moves. Both apply to all four sites above; the principle is the same and the emission helpers should look alike.

**Move 1: statement-per-element on a shared builder var.** Replace the single fluent chain with one declared local plus N short statements. Apply to:

- `GraphitronSchemaClassGenerator` `schemaBuilder` assembly.
- All per-type bodies in `ObjectTypeGenerator` (object, interface, *and* union forms) / `InputTypeGenerator` / `EnumTypeGenerator` — flatten the *whole* chain (`.withInterface`, `.field`, `.value`, `.possibleType`, `.withAppliedDirective`), not only the trailing applied-directive loop. The `.field(...)` / `.value(...)` / `.possibleType(...)` loop is the dominant contributor at per-type scale.
- The `.argument(...)` loops inside `AppliedDirectiveEmitter.buildApplication` and `DirectiveDefinitionEmitter.buildDefinition` — flatten inside the helper-method bodies introduced by Move 2.

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

Same shape for `ObjectType.type()` / `InputObjectType.type()` / `EnumType.type()` per-type bodies:

```java
GraphQLObjectType.Builder b = GraphQLObjectType.newObject();
b.name("User");
b.withInterface(GraphQLTypeReference.typeRef("Node"));
b.field(<fieldDef_id>());
b.field(<fieldDef_name>());
// …one statement per field…
b.withAppliedDirective(<applied_0>());
return b.build();
```

**Move 2: factor each non-trivial value into a `private static` builder method.** For sub-chains whose depth scales with their own argument count, emit each as its own method so the call site at the schema/type builder is a single name reference. Apply to:

- Each applied-directive application from `AppliedDirectiveEmitter.buildApplication`.
- Each directive definition from `DirectiveDefinitionEmitter.buildDefinition`.
- Each synthesised scalar currently inlined as `newScalar().name(...).coercing(...).build()` at `GraphitronSchemaClassGenerator.java:206–210`.
- Each field / input-field / enum-value definition currently inlined as a sub-chain from `buildFieldDefinition` / `buildValueDefinition`.

```java
private static GraphQLAppliedDirective appliedDirective_0() {
    GraphQLAppliedDirective.Builder b = GraphQLAppliedDirective.newDirective().name("link");
    b.argument(GraphQLAppliedDirectiveArgument.newArgument().name("url").type(...).valueLiteral(...).build());
    b.argument(GraphQLAppliedDirectiveArgument.newArgument().name("import").type(...).valueLiteral(...).build());
    return b.build();
}
```

Call site is then `schemaBuilder.withSchemaAppliedDirectives(List.of(appliedDirective_0(), appliedDirective_1(), ...))` — one entry per application, all flat.

*Helper-method naming scheme.* Helper names are collision-prone because the same directive can be applied multiple times with different argument literals (e.g. `@auth(roles:["admin"])` and `@auth(roles:["ops"])` on different fields produce two distinct `GraphQLAppliedDirective` objects with name `"auth"`). Pick a scheme that names the *application*, not the *directive*:

- Applied directives: `appliedDirective_<n>` indexed by emission order within the enclosing type/schema. The order is deterministic (driven by `schema.getSchemaAppliedDirectives()` / `container.getAppliedDirectives()` iteration). Per-type generators emit them as private statics on the per-type class (covering object, interface, union, input-object, enum forms); schema-level applications emit on `GraphitronSchema`.
- Directive definitions: `directiveDefinition_<sdlName>` — directive definition names are unique within a schema, so a name-based scheme works (replace any non-identifier characters with `_`).
- Synthesised scalars: `scalar_<sdlName>` (same uniqueness rationale).
- Field / input-field / enum-value definitions: `fieldDef_<sdlName>` / `inputFieldDef_<sdlName>` / `enumValueDef_<sdlName>`. Field names are unique within their parent type, and each `*Type.java` class is its own naming scope, so collisions don't cross types.

Factoring per element also gives each piece a name that shows up in stack traces if anything goes wrong at schema-build time, which is a small readability win on top.

The two moves together give us O(1) chain depth per statement regardless of schema size, generated code that reads as a flat list of "register X, register Y, register Z" (which mirrors how a human would write the same schema by hand), and per-element helper methods that a developer can jump to to see what each piece is.

## Test strategy

Test the structural property, not the symptom. The bug is "the generator emits one expression whose chain depth scales with schema size"; we can assert that directly without invoking javac and without depending on `-Xss` / JVM version / warm-vs-cold compile state.

*Carve-out vs the "code-string assertions banned" principle.* `rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier" bans code-string assertions on generated method bodies because they test implementation, not behaviour, and break on every refactor. The proposed structural test is functionally a string-level inspection of emitted source. The carve-out is justified here because the defect *is* an implementation-shape property — chain depth has no behavioural correlate, only the (fragile, JVM-/stack-dependent) `StackOverflowError` symptom. A behavioural test does not exist. The assertion is also a single bound (depth ≤ N) that does not name specific call sequences, so it doesn't break on refactors that change *what* is emitted, only on regressions that re-introduce a long chain. Name the carve-out in the test class's javadoc.

New tests live at `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/SchemaEmissionChainDepthPipelineTest.java`, annotated `@PipelineTier`. Per `graphitron-rewrite/docs/testing.adoc` § "Pipeline tier", pipeline tests live at `graphitron/src/test/java/no/sikt/graphitron/rewrite/` with a `*PipelineTest.java` naming convention (siblings: `TableFieldPipelineTest`, `LookupTableFieldPipelineTest`, `SplitTableFieldPipelineTest`, etc.). The carve-out below replaces what would otherwise be a unit-tier shape assertion; pipeline tier is right because the bound has to be checked against SDL-driven fixtures (federation `@link` imports, the realistic mix in `graphitron-fixtures-codegen`), not against hand-built model fixtures.

The class adds:

- *Bounded chain depth.* Run the generator on the existing pipeline-tier fixtures (`graphitron-fixtures-codegen` covers a realistic mix of types, directives, federation `@link` imports). For each emitted `.java` source string, walk it with a string-level chain counter (count consecutive `.<ident>(...)` segments starting from a method call or `new ...()` expression and not separated by a `;`, a line of leading whitespace + `<ident> =`, or a `return ` token). Assert **no expression-statement in any emitted file contains more than a fixed N = 16 chained method calls**. N = 16 is plenty conservative (cold-stack budget tolerates several hundred) and tight enough that a regression re-introducing a long chain trips it immediately.

  Choice of counter (resolves the earlier JavaParser-vs-counter hedge): a string-level counter, not `JavaParser`. JavaParser is not a current dependency (grep confirms it's referenced in `graphitron-lsp` only as a future addition). One assertion is not worth a new dependency. The counter is a self-contained ~30-line helper in the test class.

- *Statement-count scales with schema size.* Emit a small synthetic schema at two sizes (few types vs many types) and assert statement count in `GraphitronSchema.build()`'s method body grows with schema size. That pins the shape: "many short statements", not "one long chain that happens to be under N today". A simple count of `;` at the top level of the method body suffices.

- *Per-type bodies bounded too.* Same chain-depth assertion applied to every emitted `*Type.java` file, not just `GraphitronSchema.java`. Same N bound.

Existing semantic tests (presence of `.argument(...)` calls with expected values, etc.) must keep passing — the change is shape-preserving at the build-result level.

## Acceptance criteria

- No expression-statement in any generated `.java` file under the pipeline fixtures exceeds the chain-depth bound (asserted by new test on both `GraphitronSchema.java` and every emitted `*Type.java`).
- Statement count in `GraphitronSchema.build()` scales with schema element count (asserted by new test).
- All existing generator and pipeline tests pass, including `AppliedDirectiveEmitterTest` and `DirectiveDefinitionEmitterTest` under `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/` (the shape-preserving change must not perturb their semantic assertions).
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green (covers the cross-module `graphitron-sakila-example` compile against real jOOQ).
- Manual verification: regen + javac on a representative schema with default `-Xss` does not throw `StackOverflowError`. Not a CI gate (too fragile), but a hand-check the implementer runs at the end.

## Out of scope

- Bumping `-Xss` in the maven-compiler-plugin or in Quarkus dev's forked JVM.
- Restructuring the `AppliedDirectiveEmitter.emitInputType` recursion (bounded by GraphQL type nesting depth).
- Restructuring statement-shaped sites already inventoried as safe.
- Any wider readability refactor of generated code beyond the bounded-chain-depth shape change.
