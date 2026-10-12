---
id: R1014
title: "Custom-scalar default values reach the runtime schema as literals"
status: In Progress
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

The same holds wherever a custom-scalar value sits inside a default: in a list default and in an input-object default. The minimal pair, on the sakila example's existing `BigDecimal` (bound with `@scalarType` to `ExtendedScalars.GraphQLBigDecimal`):

```graphql
# deploys today: no default
input RentalRateRange { fra: BigDecimal  til: BigDecimal }

# fails Graphitron.buildSchema today, deploys once this lands
input RentalRateRange { fra: BigDecimal = 0  til: BigDecimal = 1 }
type Query { films(filter: FilmFilter = {range: {fra: 0, til: 1}}): [Film!]! }
```

After the fix, omitting `fra` in a query hands the fetcher `java.math.BigDecimal` `0`, exactly as if the client had written `fra: 0`.

## Diagnosis

At build time, `SchemaAssembly.of` wires every non-spec scalar as `EchoingWiringFactory.fakeScalar`, a stand-in coercer whose `parseLiteral` returns the AST node unchanged (the assembled schema is read, never executed). Every SDL default is held by graphql-java as an `InputValueWithState` in the *literal* state, meaning it carries the parsed AST node (`graphql.language.Value`) rather than a Java value. The three emit sites read it through `GraphQLInputObjectField.getInputFieldDefaultValue` / `GraphQLArgument.getArgumentDefaultValue`, which coerce the literal through the scalar: a built-in scalar yields a Java value, but a fake scalar yields the AST node back. `GraphQLValueEmitter.emit` has no arm for an AST node and falls through to `$S` of `toString()`.

Verified against graphql-java 25.0 with the build-time wiring (`fakeScalar("BigDecimal")`), coerced value per SDL default:

| SDL default | coerced value handed to `emit` |
|---|---|
| `p: BigDecimal = 0` | `IntValue{value=0}` (AST node) |
| `l: [BigDecimal] = [1, 2]` | `ArrayList[IntValue{value=1}, IntValue{value=2}]` |
| `r: Range = {fra: 1.5, til: 2}` | `LinkedHashMap{fra=FloatValue{...}, til=IntValue{...}}` |
| `n: Int = 3` | `Integer 3` |
| `m: Mode = A` | `String "A"` |

So the AST node also leaks *nested* inside lists and maps, where `emitList` / `emitMap` recurse into the same fallback. Against the real scalar, `defaultValueProgrammatic("IntValue{value=0}")` fails schema validation with the message in the Goal, while `defaultValueLiteral(Parser.parseValue("0"))` validates and coerces to `java.math.BigDecimal 0`.

The three sites sharing the path: `InputTypeGenerator.buildFieldDefinitionMethod` (input-field defaults), `ObjectTypeGenerator`'s per-argument loop in the field-definition method (argument defaults), `DirectiveDefinitionEmitter.buildDefinitionMethod` (directive-definition argument defaults). All three call `GraphQLValueEmitter.emit` on the coerced value inside `defaultValueProgrammatic(...)`.

The only programmatic-state default in the assembled schema is the synthesized connection `first` argument, set by `ConnectionPromoter.rewriteCarrierField` as `defaultValueProgrammatic(rewrite.defaultPageSize())` (an `Integer`), whose invariant `FieldWrapper` documents. It keeps the programmatic path.

## Implementation

Emit what the author wrote. A literal-state default already carries the exact AST the SDL declared, so the emitter prints that node and re-parses it at consumer class-init, the shape `AppliedDirectiveEmitter.emitAstLiteralValue` already uses for applied-directive arguments (`Parser.parseValue("<AstPrinter.printAst(node)>")`). No coercion is involved on the build side, so the fake scalar is never consulted, and the real scalar's `parseLiteral` does the coercion at runtime, exactly as it would for the same literal in a query.

- `GraphQLValueEmitter`: add `emitDefault(InputValueWithState state, String coordinate)`, returning the builder call as a `CodeBlock`:
  - literal state: `defaultValueLiteral($T.parseValue($S))` with `graphql.parser.Parser` and `AstPrinter.printAst((Value<?>) state.getValue())`;
  - programmatic state: `defaultValueProgrammatic($L)` with the existing `emit(state.getValue())`;
  - any other state (external, or not set): `IllegalStateException` naming the coordinate and the state. Graphitron sets neither today; the throw keeps the switch total.
  The `Parser` `ClassName` constant currently private to `AppliedDirectiveEmitter` moves to (or is shared with) `GraphQLValueEmitter` rather than being declared twice.
- `InputTypeGenerator`, `ObjectTypeGenerator`, `DirectiveDefinitionEmitter`: replace the `getXDefaultValue(...)` + `defaultValueProgrammatic(emit(...))` pair with `body.addStatement("$L.$L", var, GraphQLValueEmitter.emitDefault(x.getXDefaultValue(), coordinate))`, where `x.getXDefaultValue()` is the instance accessor returning the `InputValueWithState` (`GraphQLInputObjectField.getInputFieldDefaultValue()`, `GraphQLArgument.getArgumentDefaultValue()`). Coordinate is `Type.field`, `Type.field(arg:)` and `@directive(arg:)` respectively.
- `GraphQLValueEmitter.emit`: replace the `toString` fallback with an `IllegalStateException` naming the value's class and the value. After the call-site change `emit` sees only values graphitron's own code set programmatically, so an unhandled shape is a generator defect and fails the build rather than the deploy. Update the class javadoc, which today says `emit` is used for all input-field and argument defaults and that the fallback "keeps emission total".

Every SDL default changes emitted shape (`defaultValueProgrammatic(100)` becomes `defaultValueLiteral(Parser.parseValue("100"))`), built-in scalars and enums included. That is deliberate: one rule for every literal default, with no per-type arm to forget and no deep walk over lists and maps to decide which defaults are "still AST". There are no golden files of emitted schema code, so the churn is the handful of unit assertions listed under Tests. Runtime behaviour for built-in scalars and enums is unchanged: graphql-java coerces the literal through the same `Int` / `String` / enum coercer that already validates the programmatic value. One visible nuance: introspection's `defaultValue` and printed SDL now render the default as authored (`Float = 1` prints `1`, not `1.0`), since graphql-java prints a literal default as-is.

## Tests

- Unit tier, `DefaultValueEmissionTest`: retarget the existing assertions to the literal shape (`.defaultValueLiteral(` + `Parser.parseValue("100")` etc.), and add cases for a custom scalar (`scalar BigDecimal`, `x(p: BigDecimal = 0)`), a custom scalar inside an input-field default, inside a list default (`[BigDecimal] = [1, 2]`), and inside an input-object argument default; each asserts the printed literal (`"0"`, `"[1, 2]"`, `"{fra : 0, til : 1}"`) and asserts no `Value{value=` text anywhere in the rendered method. Add a case pinning that the synthesized connection `first` default still emits `defaultValueProgrammatic(<n>)`.
- Unit tier, `DirectiveDefinitionEmitterTest`: retarget the two default assertions, add a custom-scalar directive argument default.
- Unit tier, `GraphQLValueEmitterTest`: `emit` on an unsupported value (a `graphql.language.IntValue`) throws, naming the class; `emitDefault` on each state.
- Execution tier, `graphitron-sakila-example` (the tier that builds `Graphitron.buildSchema` with the real `GraphQLBigDecimal`): add a defaulted sibling of the existing rental-rate fixture rather than editing `RentalRateRange`, whose undefaulted `null` bounds `GraphQLQueryTest`'s range test relies on:
  ```graphql
  input RentalRateRangeDefaulted { fra: BigDecimal = 0  til: BigDecimal = 1 }
  input FilmRentalRangeFilterDefaulted {
      rentalRateRange: RentalRateRangeDefaulted = {}
          @condition(condition: {className: "...InputFieldConditionFixtures", method: "rentalRateRange"}, override: true)
  }
  # Query
  filmsByRentalRateRangeDefaulted(filter: FilmRentalRangeFilterDefaulted = {rentalRateRange: {fra: 0, til: 1}}): [Film!]!
  ```
  plus `directive @rateTolerance(min: BigDecimal = 0) on FIELD_DEFINITION`, which needs no application: `SchemaDirectiveRegistry.isSurvivor` strips only graphitron's own directives, so `DirectiveDefinitionEmitter` emits every author-declared definition into the runtime schema. The fixture alone is the regression: before the fix, schema build fails and with it every execution test in the module. One named test in `GraphQLQueryTest` then pins behaviour: `{ filmsByRentalRateRangeDefaulted { title } }` and `{ filmsByRentalRateRangeDefaulted(filter: {rentalRateRange: {}}) { title } }` both return the seed's `0.99` films only (contains `ACADEMY DINOSAUR`, excludes `ACE GOLDFINGER` at `4.99` and the three `2.99` films the range test names), proving the defaults arrived at `InputFieldConditionFixtures.rentalRateRange` as `BigDecimal` bounds, through both the argument-default and the input-field-default paths.

## Other solutions we've considered

- *Emit literal only when the coerced value is still an AST node.* Smaller diff, but the leak is nested (table above), so the check is a deep walk over lists and maps, and it leaves two emission shapes for one kind of thing. Rejected for the uniform rule.
- *Teach `emit` an AST arm* (print-and-parse any `graphql.language.Value` it meets). Fixes the top-level case but nested AST nodes inside a `List.of(...)` / `Map.of(...)` programmatic value would need per-element literal-to-Java translation, which is the real scalar's job and not available at build time.
- *Wire real scalars at build time* (load the `@scalarType` reference in `SchemaAssembly`) so coercion yields Java values. Couples assembly to the consumer classpath, which the fake-scalar wiring exists to avoid, and still needs a Java-literal emitter for arbitrary scalar output types (`BigDecimal`, `LocalDate`, consumer types).

## Reviewer findings
