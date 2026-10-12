---
id: R1014
title: "Custom-scalar default values reach the runtime schema as literals"
status: Backlog
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# Custom-scalar default values reach the runtime schema as literals

## Goal

A default value on an input field or argument typed as a custom scalar (`scalar BigDecimal @scalarType(...)`, `poenggrenseForOpptak: BigDecimal = 0`) reaches the emitted runtime schema as a value the real scalar accepts, so `Graphitron.buildSchema` validates and the consuming app deploys. Today it is emitted as `defaultValueProgrammatic("IntValue{value=0}")`, which fails at runtime with `InvalidSchemaException: Invalid default value IntValue{value=0} for type BigDecimal`; the build reports nothing. This blocks the sis port (one stable-API field, so no consumer-side workaround).

## Diagnosis

At build time, `SchemaAssembly` wires every custom scalar as `EchoingWiringFactory.fakeScalar`, whose `parseLiteral` returns the AST node unchanged. So `GraphQLInputObjectField.getInputFieldDefaultValue(field)` (and `GraphQLArgument.getArgumentDefaultValue`) coerce a built-in default to a Java value but hand a custom-scalar default back as a `graphql.language.Value`. `GraphQLValueEmitter.emit` has no arm for that and falls back to `$S` of `toString()`. Three sites share the path: `InputTypeGenerator` (input field defaults), `ObjectTypeGenerator` (argument defaults), `DirectiveDefinitionEmitter` (directive-definition argument defaults).

## Direction (for the Spec)

- Fix at the call sites, not inside `emit`: a literal-state default should go out as `defaultValueLiteral(Parser.parseValue("<AstPrinter.printAst(v)>"))`, the shape `AppliedDirectiveEmitter` already uses; programmatic-state defaults (e.g. the synthesized connection `first` default) keep the current path.
- Design fork: emit literal for every literal-state default (uniform, churns approval output on ~every default) versus only when the coerced value is still an AST node (minimal).
- Replace the `toString` fallback in `GraphQLValueEmitter` with a build-time refusal naming the value class and coordinate, so the next unhandled shape fails the build instead of the deploy.
- Regression coverage in a tier that actually builds and validates the runtime schema with a real custom scalar.
