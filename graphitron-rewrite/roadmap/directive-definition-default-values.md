---
id: R248
title: DirectiveDefinitionEmitter drops argument default values
status: Spec
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# DirectiveDefinitionEmitter drops argument default values

`DirectiveDefinitionEmitter.buildDefinition` reconstructs each survivor directive
through `GraphQLArgument.newArgument().name(...).type(...).description(...).build()`
and never calls `.defaultValueProgrammatic(...)`. When the SDL declares an argument
default (canonical example: `directive @key(fields: FieldSet!, resolvable: Boolean = true)
repeatable on OBJECT | INTERFACE`), the emitted `additionalDirective(...)` call drops
the `= true` and the directive registered in the programmatic schema differs from the
SDL it was loaded from. The sibling path for field arguments
(`ObjectTypeGenerator.buildArgument`,
`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/ObjectTypeGenerator.java:258-261`)
already round-trips defaults via `arg.hasSetDefaultValue()` +
`GraphQLArgument.getArgumentDefaultValue(arg)` + `GraphQLValueEmitter.emit(...)`;
`DirectiveDefinitionEmitter` should mirror it. The existing test
`DirectiveDefinitionEmitterTest.buildDefinition_emitsNameLocationsAndArguments`
uses `mode: String = "strict"` but only asserts `.name("mode")` is present, which
is why the regression slipped in; the fix should add a pin that the default makes
it to the emitted `CodeBlock`.

---

## Fix

Mirror `ObjectTypeGenerator.buildArgument`'s default-value branch in
`DirectiveDefinitionEmitter.buildDefinition`. Inside the per-argument loop
(`DirectiveDefinitionEmitter.java:63-74`), between the description block and the
closing `.build())`, emit:

```java
if (arg.hasSetDefaultValue()) {
    Object defaultValue = graphql.schema.GraphQLArgument.getArgumentDefaultValue(arg);
    block.add(".defaultValueProgrammatic(")
         .add(GraphQLValueEmitter.emit(defaultValue))
         .add(")");
}
```

`GraphQLValueEmitter` is already on the classpath of this package; no new imports
beyond the static method reference. The deprecated-on-arguments path is not added
here (directive-definition arguments do not carry deprecation in any survivor
directive Graphitron emits today); keeping the diff minimal also avoids broadening
the regression surface for an unrelated capability.

## Test

Extend `DirectiveDefinitionEmitterTest.buildDefinition_emitsNameLocationsAndArguments`
(`graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/DirectiveDefinitionEmitterTest.java:52-67`):
the SDL fixture already declares `mode: String = "strict"`, so add a new assertion
that the emitted block contains `.defaultValueProgrammatic(` and the string
literal `"strict"`. This pins the missing default at the emitter's output.

Add a second test arm with a non-string default (a `Boolean` default mirroring the
canonical `@key(resolvable: Boolean = true)` shape that surfaced the bug) so the
pin covers both the literal-string and the literal-boolean cases that
`GraphQLValueEmitter.emit(...)` routes through different branches.

## Out of scope

- The `FieldSet!` argument type being rendered as `Scalars.GraphQLString` in the
  same directive. This is documented as deliberate at
  `ScalarTypeResolver.java:83-95` and `AppliedDirectiveEmitter.java:125-131`:
  federation-namespace scalars (`federation__FieldSet`, `_FieldSet`,
  `federation__Scope`, `federation__Policy`, `federation__ContextFieldValue`,
  `link__Import`, `link__Purpose`) resolve to `Scalars.GraphQLString` at emit
  time because the scalar type is not yet registered when `additionalDirective(...)`
  runs, and `Federation.transform()` replaces the directive after the base schema
  is built. Lifting the placeholder requires also threading the scalar
  registration through; out of scope for this fix.
- Federation-side end-to-end verification that the `resolvable` default now
  reaches `Federation.transform()`. The unit-tier pin on the emitted `CodeBlock`
  is structurally sufficient; the federation transform consumes the directive
  through standard graphql-java reflection and reads the default through the
  same `GraphQLArgument.getArgumentDefaultValue` accessor we now emit.
