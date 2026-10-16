---
id: R1053
title: "Validation pre-check casts a list input argument to Map and crashes outside the error channel"
status: Spec
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Validation pre-check casts a list input argument to Map and crashes outside the error channel

## Goal

An `@service` mutation that takes a list of input objects, such as `opprettStudieprogrammer(input: [OpprettStudieprogrammerInput!]!)`, keeps working when its payload's error channel (the payload's `errors` field, whose union members' `@error` handlers decide which failures reach the client as typed errors) carries a `{handler: VALIDATION}` member. Today every call to such a field fails, happy path included, with a raw `ClassCastException` message, so a consumer cannot put `VALIDATION` on an error type that any list-input `@service` mutation shares. After this lands the field runs; its pre-execution validation (the Bean Validation pass graphitron runs before the service method) checks each list element on its own and reports an element's violations under that element's path, `["opprettStudieprogrammer", "input", 2, "kode"]`; a null or empty list yields none; and anything that goes wrong inside the validation pass is handled by the field's error channel like any other failure, rather than escaping the fetcher.

The schema shape, from the sis port:

```graphql
type Mutation {
  opprettStudieprogrammer(input: [OpprettStudieprogrammerInput!]!): OpprettStudieprogrammerPayload
    @service(service: {className: "...", method: "opprettStudieprogrammer"})
}
type OpprettStudieprogrammerPayload {
  studieprogrammer: [Studieprogram!]
  errors: [OpprettStudieprogrammerError!]
}
union OpprettStudieprogrammerError = UgyldigInput | ...
type UgyldigInput @error(handlers: [{handler: VALIDATION}, ...]) {
  path: [String!]!
  message: String!
}
```

What this unlocks today is the channel itself. sis's `UgyldigInput` is shared across mutations, so declaring `VALIDATION` on it is what turns on the column-length check (the `size must be between 0 and n` errors a payload-returning `@mutation` raises before writing, `docs/manual/how-to/error-channel.adoc` § column-length-check) on sis's DML mutations, and that declaration currently breaks every `@service` mutation with a list input. Bean Validation violations on a service input are a separate gap: the class the pre-check validates carries no constraint annotations, so the pass finds no violations on any input, list or not; R747 owns that. This item makes the list case shape-correct, so per-element violations land under the right path the moment R747 gives the pass something to find.

## Context

Reported from the sis port (trunk `02b6a7f3d`). The generated fetcher reads:

```java
Map<String, Object> arg_input_raw = env.getArgument("input");
OpprettStudieprogrammerInput arg_input = arg_input_raw == null ? null : OpprettStudieprogrammerInput.fromMap(arg_input_raw);
```

At runtime the argument is an `ArrayList`, so this line throws. On sis, adding the handler broke 43 ITs across every `@service` mutation with a list input; sis has dropped the handler until this lands.

Three defects at one site, `TypeFetcherGenerator.validatorPreStep`, called from `buildServiceFetcherCommon` only when the channel `hasValidationHandler()`:

1. **The read ignores list wrapping.** `resolveInputArgClass` reads the argument's type off the assembled `GraphQLSchema` at emit time and `unwrapAll`s it, discarding the list wrappers, so every input-object argument gets the `Map` read and one `fromMap` call.
2. **It runs outside the `try`.** `buildServiceFetcherCommon` adds the pre-step before `beginControlFlow("try")`, so an exception in it bypasses `ChannelCatchArmEmitter` (dispatch and redaction) and graphql-java's default handler surfaces the raw message. The comment beside the call claims the opposite. `lengthCheckPreStep`, the DML column-length check, already runs inside its fetcher's `try`, and loops a list argument by index with the index in the error path; it is the shape to match.
3. **It iterates bindings, not arguments.** The loop walks the carrier's `MappingEntry.FromArg` entries (one per service-method parameter bound to an argument) and recovers each one's outer argument through `outerArgOfValueShape`. Two parameters binding into one argument (a bean parameter for `input` plus a scalar parameter for `input.name`) validate the argument twice and report every violation twice.

Glossary for the plan below. The *carrier* is the model record a `@service` field's fetcher is emitted from, `ServiceMethodCall` (`Static` / `Instance`), built at classification by `ServiceMethodCallWalker.walk`, which has the field's `GraphQLFieldDefinition` in hand. A `ValueShape` is the carrier's description of how one service parameter is built from the wire value; it describes the parameter binding, not the argument, so it is not the source for this decision (see Other solutions).

## Implementation

**Model: one validation target per argument the call reads.** Add a sealed `ServiceMethodCall.ValidatedArgument` in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ServiceMethodCall.java`, with arms:

- `Raw(String argName)`: a scalar or enum argument, at any list depth. The pre-step validates the raw value, as today.
- `InputObject(String argName, String inputTypeName)`: an unwrapped input-object argument.
- `InputObjectList(String argName, String inputTypeName)`: an input-object argument under exactly one list wrapper.
- `InputObjectNestedList(String argName, String inputTypeName)`: an input-object argument under two or more list wrappers, which the pre-step cannot materialise and which classification rejects under a `VALIDATION` channel (below). The arm exists so the walker states the fact and the rejection reads it, rather than the emitter discovering the depth.

The arm names the SDL input type, not a Java class: the `<outputPackage>.inputs.<TypeName>` class is emit vocabulary and render mints it, as `InputRecordGenerator` does.

Both carrier records gain a `List<ValidatedArgument> validatedArguments()` component (on the interface too), defensively copied like `methodArgs`. `ServiceMethodCallWalker.walk` fills it: for each `MappingEntry.FromArg` it emits, take the outer argument name (the head of the `ParamSource.Arg` path it already converts with `toArgPath`), dedupe by name in first-appearance order, and classify each name against `fieldDef.getArgument(name).getType()`: count `GraphQLList` wrappers through `GraphQLNonNull`, and test the unwrapped type for `GraphQLInputObjectType`. An argument name the field does not declare is a walker invariant failure (`IllegalStateException`), since `ParamSource.Arg` resolution already required it; no silent `Raw` fallback. Classification reading the field definition is the walker's existing input, so this adds a fact to the carrier and no new schema read at emit.

**Classification: reject the nested-list case under a `VALIDATION` channel.** In `FieldBuilder`, at the `walkerResult` switch where the channel and the carrier first meet (`builder.apply(channel, ok.carrier())`), when the channel `hasValidationHandler()` and any target is `InputObjectNestedList`, return an `UnclassifiedField` whose author-facing message names the argument and says that validation of a list of lists of input objects is not supported. Where `GraphitronSchemaValidator`'s `@service` arm mirrors classifier invariants, mirror this one there too. The walker builds no nested `ValueShape.ListOf` for a bean binding (`ServiceMethodCallEmitter` carries a defensive "unsupported nested ListOf" arm for it), and under a `VALIDATION` channel any list argument crashes the pre-step today, so the rejection takes away nothing that works.

**Emit: read the target, loop lists, run inside the `try`.** In `TypeFetcherGenerator`:

- `validatorPreStep` walks `carrier.validatedArguments()` instead of `methodArgs()`, switching on the arm:
  - `Raw`: `Object arg_<n> = env.getArgument(...)`, validate when non-null, prefix `List.of("<n>")`. Unchanged behaviour.
  - `InputObject`: the existing `Map` read and `fromMap`, prefix `List.of("<n>")`.
  - `InputObjectList`: read `List<?> arg_<n>_raw = env.getArgument(...)`; when non-null, loop by index; skip a null element; materialise `<Input>.fromMap((Map<String, Object>) element)`; validate; prefix `List.of("<n>", i)`. The unchecked element cast is the one `fromMap`'s own list components already make for nested input lists.
  - `InputObjectNestedList`: `throw new IllegalStateException` naming the classification rejection it should have hit; unreachable.
- `buildServiceFetcherCommon` moves the `validatorPreStep` call to be the first statement inside the `try`, ahead of the tenant hand-down declaration, so validation still runs before tenant resolution and the service call, and its early return (`ChannelEarlyReturnEmitter`) stays a return from inside the `try` as `lengthCheckPreStep`'s is. Rewrite the comment above it to say what is now true: a failure in the pass reaches the channel's catch arm.
- `resolveInputArgClass` and its `ctx.assembledSchema()` read are deleted; so is the pre-step's use of `outerArgOfValueShape` (the method stays if other callers remain). With them goes the model-only-build fallback described in `validatorPreStep`'s javadoc, which validated raw values when no assembled schema was present; update the javadoc.

**Generated helper: one path-prefix shape.** In `ConstraintViolationsClassGenerator`, `toGraphQLError(violation, env, String argName)` becomes `toGraphQLError(violation, env, List<Object> argPath)`, seeding its path list from `argPath` before appending the violation's property-path nodes. This is the parameter shape `sizeExceeded` already takes, so both pre-steps hand the helper an argument path built the same way. Update the generated class's javadoc.

**User docs.** In `docs/manual/how-to/error-channel.adoc`, the `VALIDATION` paragraph above `[#column-length-check]` gains one sentence: a list input is validated element by element, and a violation's `path` carries the element's index (`["createThings", "in", 1, "name"]`), matching the column-length check's example. It does not claim that violations occur today; that sentence is R747's to write.

## Tests

- **Execution tier, the acceptance test.** Add to the sakila example a `@service` mutation taking `in: [X!]!` (an existing film-shaped input fits) whose payload's errors union includes a `{handler: VALIDATION}` member, backed by a small service in `graphitron-sakila-service` that returns a payload echoing the element count. `GraphQLQueryTest` cases: a two-element list returns data and no errors (fails today with the `ClassCastException`); an empty list returns data and no errors. This fixture is also the one R170 and R747 want; whichever of the three lands first adds it, and the others extend it.
- **Generated helper, unit tier against compiled output.** `ConstraintViolationsWireShapeTest` (sakila example, `internal`) gets a case that hands `toGraphQLError` a prefix of `List.of("in", 1)` and a hand-built bean violation on a property, asserting the path `[..., "in", 1, "<property>"]`. This is where the per-element path is demonstrated while no constraint reaches the generated input classes; the existing cases move to the list-prefix signature.
- **Pipeline tier.** `TypeFetcherGeneratorTest.mutationServiceRecordField_withValidationHandler_emitsValidatorPreStep` flips its ordering assertion: `getValidator(env)` now follows `try`. New cases: a list input-object argument emits the `List<?>` read, the index loop and a two-element prefix, and no `Map` cast of the argument itself; two `FromArg` parameters binding into one argument emit one validation block for it. `TestFixtures.stubServiceCall` builds a field definition with no arguments, so it gains an overload that takes the field's argument definitions, and the walker's invariant failure is what keeps a fixture from silently passing without one.
- **Walker, unit tier.** `ServiceMethodCallWalker` cases: scalar, list-of-scalar, input object, list of input objects and list of lists of input objects map to the four arms; duplicate bindings dedupe.
- **Classification.** A `GraphitronSchemaBuilderTest` (or classified-corpus) case: a `[[X!]!]!` input-object argument on a `@service` field under a `VALIDATION` channel is rejected with the message above; the same field without `VALIDATION` classifies.

## Other solutions we've considered

- **Read list-ness off the binding's `ValueShape.ListOf`.** The `FromArg` the pre-step already walks carries `ListOf` for a list bound to a bean or jOOQ-record parameter. But a `ValueShape` describes one parameter binding, not the argument's wire value: a `List`- or `Map`-typed parameter bound directly is a `Scalar` leaf, and a nested-field binding reaches the argument only through `outerArgOfValueShape`. It also keeps the per-binding grain that double-validates. If R747 takes its route 2 (validate the consumer's bound instance instead of the generated input), that is a new `ValidatedArgument` arm for which the binding's `ListOf` is the right list source; it does not redefine the arms above.
- **Keep the emit-time schema read and stop discarding the wrappers.** The smallest diff: have `resolveInputArgClass` return the wrapper depth along with the class. It deepens the generator's one emit-time read of the assembled schema for a fact classification already holds, and keeps the model-only-build fallback that validated a raw list and passed, the untested fork this bug shipped through.
- **Validate a list of lists by nesting loops.** Supportable, but no bean binding of such an argument exists on a `@service` field today, so the extra emitter branch would have no fixture; a classification rejection is loud and loses nothing that works.
