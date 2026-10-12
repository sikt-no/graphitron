---
id: R1014
title: "Custom-scalar default values reach the runtime schema as literals"
status: In Review
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

The synthesized connection `first` argument is set programmatically by `ConnectionPromoter.rewriteCarrierField` (`defaultValueProgrammatic(rewrite.defaultPageSize())`, the invariant `FieldWrapper` documents), but only on the walk's rebuilt schema. Emission reads the schema derived from the store, which carries every default as SDL text, so `first` reaches the emit sites in the literal state like any authored default (see Implementation notes).

## Implementation

Shipped at `dbc854c5`: `GraphQLValueEmitter.emitDefault(InputValueWithState, coordinate)` emits a literal-state default as `defaultValueLiteral(Parser.parseValue("<AstPrinter.printAst(node)>"))` and a programmatic one as `defaultValueProgrammatic(emit(value))`, and throws for any other state. `InputTypeGenerator`, `ObjectTypeGenerator` and `DirectiveDefinitionEmitter` call it in place of `defaultValueProgrammatic(emit(...))`. `emit`'s `toString` fallback throws, and the `Parser` constant is shared from `GraphQLValueEmitter`. Every SDL default now emits as its authored literal, built-in scalars and enums included. Introspection and printed SDL render a default as authored (`Float = 1` prints `1`). Directive-definition argument types shipped at `81a28b56` (see Implementation notes).

## Tests

The evidence is at the execution tier in `graphitron-sakila-example`, which builds `Graphitron.buildSchema` with the real `GraphQLBigDecimal`. Rework shipped at `1a1d189b`.

- Fixture. `RentalRateRangeDefaulted { fra: BigDecimal = 0  til: BigDecimal = 1 }` is a defaulted sibling of `RentalRateRange`, whose undefaulted `null` bounds the existing range test relies on. `FilmRentalRangeFilterDefaulted.rentalRateRange` carries it to `InputFieldConditionFixtures.rentalRateRange`. `Query.filmsByRentalRateRangeDefaulted(filter: ... = {rentalRateRange: {fra: 4, til: 5}})` gives the argument default bounds of its own. `directive @rateTolerance(min: BigDecimal = 0, steps: [BigDecimal] = [1, 2])` needs no application, because `DirectiveDefinitionEmitter` emits every author-declared definition. The fixture alone is the regression: before the fix, schema build fails, and every execution test in the module fails with it. That covers all three emit sites and the list default.
- `GraphQLQueryTest.inputFieldCondition_customScalarDefaults_bindAsBigDecimalBounds` pins behaviour. `{ filmsByRentalRateRangeDefaulted { title } }` returns exactly `ACE GOLDFINGER` (4.99, from the argument default's `[4, 5]`). `{ filmsByRentalRateRangeDefaulted(filter: {rentalRateRange: {}}) { title } }` returns exactly `ACADEMY DINOSAUR` (0.99, from the input-field defaults' `[0, 1]`). Because the bounds differ, each query pins its own path.
- `GraphQLQueryTest.customScalarDefaults_introspectAsTheAuthoredLiterals` reads `defaultValue` back through introspection and asserts the authored literals:
  - `RentalRateRangeDefaulted.fra` / `til` are `"0"` / `"1"`, and `RentalRateRange`'s fields are `null`;
  - the `filter` argument is `"{rentalRateRange : {fra : 4, til : 5}}"`;
  - `actorsByFilmFilterConnection(first:)` is `"10"`, the synthesized connection default;
  - `@rateTolerance(min:, steps:)` is `"0"` / `"[1, 2]"`, with both argument types naming `BigDecimal`, not a `String` placeholder.
- Unit tier, `GraphQLValueEmitterTest`: `emit` on an unsupported value (a `graphql.language.IntValue`) throws, naming the class. `emitDefault` is covered on each state: literal, internal, external, and not set.

No unit test matches generated method-body text. `DefaultValueEmissionTest` was deleted, and `DirectiveDefinitionEmitterTest`'s default-value assertions were removed (its boolean-default and custom-scalar-default cases with them).

## Other solutions we've considered

- *Emit literal only when the coerced value is still an AST node.* Smaller diff, but the leak is nested (table above), so the check is a deep walk over lists and maps, and it leaves two emission shapes for one kind of thing. Rejected for the uniform rule.
- *Teach `emit` an AST arm* (print-and-parse any `graphql.language.Value` it meets). Fixes the top-level case but nested AST nodes inside a `List.of(...)` / `Map.of(...)` programmatic value would need per-element literal-to-Java translation, which is the real scalar's job and not available at build time.
- *Wire real scalars at build time* (load the `@scalarType` reference in `SchemaAssembly`) so coercion yields Java values. Couples assembly to the consumer classpath, which the fake-scalar wiring exists to avoid, and still needs a Java-literal emitter for arbitrary scalar output types (`BigDecimal`, `LocalDate`, consumer types).

## Implementation notes

Landed as specified, with two deviations the spec did not foresee:

- *The synthesized connection `first` default is literal-state too.* Emission reads the schema `EmittedRegistry` derives from the store, which carries every default as SDL text (`defaultValueSdl`), so `first` reaches `emitDefault` as a literal and emits `defaultValueLiteral(Parser.parseValue("<n>"))`. `ConnectionPromoter.rewriteCarrierField`'s programmatic default lives only on the walk's rebuilt schema, which emission does not read. The programmatic arm of `emitDefault` stays (it keeps the switch total and is unit-tested); `customScalarDefaults_introspectAsTheAuthoredLiterals` pins it through a connection's `first`.
- *Directive-definition argument types now reference their custom scalar.* `AppliedDirectiveEmitter.emitInputType` typed every custom scalar as the `Scalars.GraphQLString` placeholder, so the `@rateTolerance(min: BigDecimal = 0)` fixture failed `Graphitron.buildSchema` with `Invalid default value IntValue{value=0} for type String` once the default became a literal. Before this item that definition only validated because its default was the string `"IntValue{value=0}"`, so the directive arm of the Goal was not reproducing the bug but masking a second one. `DirectiveDefinitionEmitter` now uses `AppliedDirectiveEmitter.emitDefinitionInputType`, which emits `typeRef("<scalar>")` for a custom scalar; applied directives keep the placeholder (their type slots are federation-transform metadata). `GraphitronSchemaBuilder.rejectUnregisteredScalarReferences` sweeps survivor directive definitions' custom-scalar arguments, the new reference site. Pinned by schema build and by the introspection test's argument-type assertion.

Also: `FixtureWarningsGateTest`'s pinned line for `filmsConnectionByRequiredIds` moved from 600 to 609 with the new fixture lines.

Evidence for the Goal: see Tests.

## Reviewer findings

### In Review → Ready (rework), reviewing `dbc854c5`..`706aeb1f` on trunk at `dd940c70`

The verification build (`mvn install -Plocal-db`) is green, and question 1 holds. The three emit sites, `emitDefault` and its state switch, the `Parser` constant move, the throwing `emit` fallback, and the rewritten class javadoc all match the plan. The two deviations are disclosed under Implementation notes, and both are sound. The literal-state `first` follows from emission reading the store-derived schema. The directive-definition type fix is a real second bug the old string default was masking. I probed the new scalar sweep with a scalar referenced only from a directive definition (`scalar BigDecimal @scalarType(...)` plus `directive @d(min: BigDecimal = 0, steps: [BigDecimal] = [1, 2])`, used nowhere else). It classifies, registers, and draws no diagnostic, so the sweep does not reject schemas that built before. The generated sakila sources carry `defaultValueLiteral(Parser.parseValue("0"))` and `"{rentalRateRange : {fra : 0, til : 1}}"` at the expected sites. No manual page describes default-value emission, so the user-facing-doc check has nothing to update. No retired vocabulary is declared. What sends the item back is question 2, together with the test-tier precondition.

1. **The new and retargeted cases in `DefaultValueEmissionTest` and `DirectiveDefinitionEmitterTest` are code-string assertions on generated method bodies.** They render a `TypeSpec` or `MethodSpec` to text and match `.defaultValueLiteral(graphql.parser.Parser.parseValue("0"))`, `.type(graphql.schema.GraphQLTypeReference.typeRef("BigDecimal"))`, `doesNotContain("Value{value=")` and similar. `development-principles.adoc` bans this at every tier, `testing.adoc` repeats the ban for the `generators/schema/` unit tests by name, and this gate holds it as an approval precondition. The Tests section prescribed exactly these assertions, so this is a plan defect as well as a delivery defect, the same shape the R992 Done round sent back. Amend the bullet. Older cases in those files already follow the pattern, but that does not license new or rewritten ones. Delete the cases this item added or retargeted, or reshape them so they assert something other than body text. `GraphQLValueEmitterTest` is not part of this finding: it unit-tests a value-to-`CodeBlock` function at its return value, the same shape as that file's existing `emit` cases, and its throw cases are plain behaviour.

2. **Once those strings go, two promised behaviours have no evidence, and a third is weaker than the spec claims.**
   - *A custom scalar inside a list default.* The Goal names it. Today only `fieldArgument_customScalarInsideListDefault_emitsAuthoredLiteral` covers it, and that is one of the strings above. The cheapest real evidence is a list argument on the existing directive definition, for example `directive @rateTolerance(min: BigDecimal = 0, steps: [BigDecimal] = [1, 2])`. `Graphitron.buildSchema` then validates the list default against the real `GraphQLBigDecimal` in every execution test.
   - *No AST `toString` reaches the runtime schema.* The `doesNotContain("Value{value=")` pins were the only statement of this. An execution-tier introspection case states it as behaviour, not text. Read `defaultValue` for `RentalRateRangeDefaulted.fra` / `til`, for `filmsByRentalRateRangeDefaulted(filter:)`, and for the `@rateTolerance` arguments, and assert the authored literals (`"0"`, `"1"`, `"[1, 2]"`, and the input-object default).
   - *The argument-default path is not distinguished from the input-field defaults.* The argument default `{rentalRateRange: {fra: 0, til: 1}}` spells the same bounds the input fields default to. So `{ filmsByRentalRateRangeDefaulted { title } }` returns the same 0.99 films even if the argument default's nested `BigDecimal`s never arrived and the field defaults filled them in. The Tests section claims the test proves both paths, and today it proves only the field-default path. Give the argument default bounds of its own, for example `{rentalRateRange: {fra: 4, til: 5}}`, which selects only the 4.99 films (ACE GOLDFINGER in, ACADEMY DINOSAUR out). Keep the `rentalRateRange: {}` query on the 0/1 field defaults. The two results then differ, and each one pins its own path.

3. **Spec bookkeeping (not blocking on its own, but due at the next pass).** Rewrite the Tests section to describe the evidence that ships after items 1 and 2. The Diagnosis paragraph saying the connection `first` default "keeps the programmatic path" is now contradicted by the Implementation notes. Correct it in place, or mark it superseded. Collapse Implementation into a one-line "shipped at `<sha>`" note.

What satisfies this: items 1 and 2 delivered, the Tests section amended to match, and a green verification build. Note for the next build: the trunk sync that brought this item also brought `init.sql` changes, so a sandbox seeded before it fails `FactExpectationTest` on `node_metadata_*` / `recordless_note` until it is re-seeded per `.claude/web-environment.md`. That failure is unrelated to this item.

**Rework response** (`1a1d189b`). Item 1: `DefaultValueEmissionTest` is deleted, and `DirectiveDefinitionEmitterTest` has no default-value assertions left. Item 2: the list default rides on `@rateTolerance(steps:)`. Introspection now states that defaults arrive as authored literals. The argument default spells `[4, 5]`, so it answers differently from the field defaults. Item 3: Diagnosis, Implementation and Tests above are rewritten to match what shipped.
