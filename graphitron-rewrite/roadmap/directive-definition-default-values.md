---
id: R248
title: "DirectiveDefinitionEmitter drops argument default values"
status: Backlog
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
