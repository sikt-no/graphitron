---
id: R1047
title: "An @error GENERIC handler can name the generated GraphitronClientException on a clean build"
status: Ready
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An @error GENERIC handler can name the generated GraphitronClientException on a clean build

## Goal

A consumer can catch graphitron's own client errors into an `@error` payload type with a `GENERIC` handler that names `<outputPackage>.schema.GraphitronClientException`, and the build accepts that handler on a clean checkout exactly as it does after an earlier compile. `GraphitronClientException` is the class graphitron's generated code throws for client mistakes: a tenant outside the request's set, a NodeId that decodes to the wrong type, a bad page size or cursor. The same generator run that checks `@error` handlers also emits that class into the consumer's output package. Today the check only accepts the name when an earlier compile left the class in `target/classes`. After `mvn clean`, or on a fresh clone, the build rejects the handler as "not carried by this module's own classes". After this item the generator knows the name of the class it is about to emit, so the handler classifies the same way whatever the state of `target/`, and its extra-field check runs against the same class every time.

From the multi-tenant Sakila example schema (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, output package `no.sikt.graphitron.generated.multitenant`), a payload whose union claims client errors next to database errors:

```graphql
type FilmWriteClientError @error(handlers: [{
    handler: GENERIC,
    className: "no.sikt.graphitron.generated.multitenant.schema.GraphitronClientException"
}]) {
    path: [String!]!
    message: String!
}

union FilmWriteClaimedError = FilmWriteDbError | FilmWriteClientError
```

Today this schema builds only when a previous compile left the class in `target/classes`. After the item it builds from clean. A mutation whose payload carries `[FilmWriteClaimedError]`, called with an id naming tenant 2 under a request tenant set of `[1]`, returns `errors: [{__typename: "FilmWriteClientError", message: "Tenant '2' is not permitted for this request."}]` inside the payload. That is the Graphitron 9 shape sis wants to keep.

## Evidence

Reported from sis. A GENERIC handler on the generated `GraphitronClientException` on `UgyldigInput` passed only against a stale `target/classes` and was rejected on `mvn clean generate-sources`. The `GraphqlErrorException` workaround passes a clean build and the affected integration tests, and puts the foreign-institution tenant error into the payload as `UgyldigInput`, which is v9's shape. That workaround needs graphql-java declared directly in the consuming module, and it matches every `GraphqlErrorException`, not only graphitron's client errors.

## Background

- `TypeBuilder.parseErrorHandler` reads one `@error` handler entry into an `ErrorType.Handler`, a sealed type with one record per handler kind. A `GENERIC` entry becomes `ErrorType.ExceptionHandler(exceptionClassName, matches, clientMessage)` once `TypeBuilder.validateExceptionClass` has accepted the name. That method asks two things: `ctx.nameability().verdictFor(className)` (`ClasspathNameability`, the rule that a schema may only name classes this module or its declared dependencies carry) and `Class.forName(className, false, ctx.codegenLoader())`, which loads the class and confirms it extends `Throwable`. Neither knows about a class this run is about to emit.
- `GraphitronClientExceptionClassGenerator` emits the class unconditionally, once per run, as a `public final` subclass of `graphql.GraphqlErrorException` that declares only a `String` constructor. `EmitPlan` registers it as `units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronClientException")`, with the literal string rather than the generator's `CLASS_NAME` constant. The generator's own javadoc already promises that "the stable name is also the `instanceof` anchor a `GENERIC` `@error` handler matches".
- The emitters need nothing more than the string. `ErrorMappingsClassGenerator.buildMappingEntry` writes `new ExceptionMapping(X.class, ...)` and `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver` writes `src instanceof X`, both via `ClassName.bestGuess`. The generated class compiles in the same source set, so both compile once the classifier accepts the name.
- Three other sites load the class reflectively, and on a clean build each quietly gets an answer that depends on `target/`:
  - `HandlerAccessorCheck.resolveHandlerSourceClass` (the `ErrorChannelWalker` path) and `FieldBuilder.resolveHandlerSourceClass` (the DML payload path) are two copies of one four-arm switch. Each turns a handler into its *accessor source class*: the class whose getters must cover the `@error` type's extra fields, the ones besides `path` and `message`. That is the named class for `GENERIC`, `java.sql.SQLException` for `DATABASE`, and `graphql.GraphQLError` for `VALIDATION`. When the class is missing, both return `null` and the check is skipped. So on a clean build, an extra field that the class cannot fill passes unchecked, while a dirty build checks it against the stale class.
  - `CheckedExceptionMatcher.covers` returns `false` when the class does not load. For this class that answer is right, because `GraphitronClientException` is unchecked and so can never be the supertype of a declared checked exception. But it is right because of what the class happens to be, not because the code knows.

## Implementation

The classifier settles once whether a `GENERIC` className names the client exception this run emits. It records the answer on the handler, and every later site reads it from there.

- **Model** (`GraphitronType.ErrorType`). Give `ExceptionHandler` a sealed component in place of its bare `String exceptionClassName`: `exceptionClass`, of type `ExceptionClass` with two arms.
  - `ClasspathClass(String className)`: a class the classifier has resolved on the codegen classpath. Both lift targets land here: an author's `GENERIC` className, and the no-discriminator `DATABASE` lift to `java.sql.SQLException`.
  - `GeneratedClientException(String className)`: the client exception this run emits, which no classpath carries yet.

  The arms are named for what each one is, not for who wrote the name. `java.sql.SQLException` from the `DATABASE` lift is a classpath class that no author wrote. `ExceptionHandler.exceptionClassName()` stays as an accessor that delegates to the component. That way `ErrorMappingsClassGenerator`, `GraphitronSchemaClassGenerator`, `MappingsConstantNameDedup`, `ChannelRuleChecks` and `FieldBuilder.describeHandler` keep reading the string and do not change. Add `accessorSourceClassName()` to `Handler`: `SqlStateHandler` / `VendorCodeHandler` give `java.sql.SQLException`, `ValidationHandler` gives `graphql.GraphQLError`, and `ExceptionHandler` delegates to its component. `ClasspathClass` gives its own name, and `GeneratedClientException` gives `GeneratedClientException.SUPERCLASS`, a `public static final String` constant holding `"graphql.GraphqlErrorException"`. The model package must not import the generators package, which is why the constant lives on the model arm and the generator reads it from there.
- **Classifier** (`TypeBuilder.parseErrorHandler`, `GENERIC` arm). Before calling `validateExceptionClass`, compare the className with `new GeneratedUnits(ctx.outputPackage()).clientException().fqcn()`. If they are equal, build `ExceptionHandler(new GeneratedClientException(className), ...)` and skip both the nameability gate and `Class.forName`. The `Throwable` check holds by construction, because the generator chooses the superclass. Any other name follows the existing path and lands in `ClasspathClass`. The comparison is plain string equality, and only this run's own output package qualifies: a consumer with two graphitron executions who names the other execution's client exception goes through the ordinary classpath rule, as now. The no-discriminator `DATABASE` lift builds `ClasspathClass("java.sql.SQLException")`.
- **One name for the unit** (`GeneratedUnits`). Add `clientException()`, returning `singleton(SUB_SCHEMA, GraphitronClientExceptionClassGenerator.CLASS_NAME)`, alongside `errorRouter()` and `outcome()`. Three places read it: `EmitPlan` (which replaces its literal), `ErrorRouterClassGenerator.buildSurfaceClientErrorOrRedactMethod` (which already builds this ref inline), and the classifier comparison. With that, the address the run writes to and the address the classifier recognises come from one derivation. The other sites that spell `ClassName.get(outputPackage + ".schema", CLASS_NAME)` (`RecordDecodeFragments`, `NodeIdDecodeFailure`, `SelectionOccurrencesClassGenerator`, `ConnectionHelperClassGenerator`, `ConnectionRuntimeClassGenerator`) only emit throws, so they stay out of scope.
- **Reflection sites.** The two `resolveHandlerSourceClass` copies collapse into a single `Class.forName(handler.accessorSourceClassName(), false, loader)`. Their four-arm switches are deleted, and each site keeps its existing `nameability: exempt` comment. A `GeneratedClientException` handler is then checked against `graphql.GraphqlErrorException`, which the plugin realm always carries (it is the same realm that resolves `graphql.GraphQLError` for `VALIDATION` today). The result is the same on clean and dirty builds. `CheckedExceptionMatcher.covers` switches on `eh.exceptionClass()`: `GeneratedClientException` yields `false` because the class is unchecked, and `ClasspathClass` keeps today's `Class.forName` body.
- **Enforcing the redirect.** The accessor check on the generated arm is correct only while the emitted class extends `GeneratedClientException.SUPERCLASS` and adds no public accessors. `GraphitronClientExceptionClassGenerator` builds its superclass `ClassName` from that constant, so the two cannot drift. A generator-tier test (under Tests) fails if the class ever gains a public non-constructor method.
- **Javadoc.** Update the `ExceptionHandler` record javadoc to cover the component. Update `validateExceptionClass` and the two `resolveHandlerSourceClass` javadocs, which claim a GENERIC className "would already have been rejected at parse time" (now false for the generated arm). Update the emitted class javadoc in `GraphitronClientExceptionClassGenerator`, which still calls the `@error` match "a future query `@error` handler".

This is a component on an existing record: no new `Handler` variant, no new leaf. It sits on the classified model, which is being drained into the fact store rather than extended, so it should stay that small. The store has no resolved-handler relation yet, only the as-written `graphitron_ast_error_generic_handler_entry`. The store-native form of this fact would join that entry with the configured output package. That join crosses two corpora, so it belongs in the anchoring (derivation) phase and must not run during capture. Building that relation is not this item's work.

`ExceptionHandler` has twenty-two construction sites, two in `TypeBuilder` and the rest in tests (`ErrorMappingsClassGeneratorTest`, `CheckedExceptionMatcherTest`, `MappingsConstantNameDedupTest`, `ChannelCatchArmEmitterTest`, `ErrorChannelWalkerTest`, `ServiceFieldValidationTest`, `MutationServiceTableFieldValidationTest`, `QueryServiceTableFieldValidationTest`). The test sites migrate mechanically to `new ExceptionClass.ClasspathClass(fqn)`. A secondary constructor that defaults the arm is deliberately left out, since it would let a caller skip the decision this item makes explicit.

## Tests

- **Pipeline tier, the acceptance evidence** (`GraphitronSchemaBuilderTest`). The test codegen loader never carries `<DEFAULT_OUTPUT_PACKAGE>.schema.GraphitronClientException`, so this tier reproduces a clean build directly. Add three cases:
  - An `@error` type with `{handler: GENERIC, className: "fake.code.generated.schema.GraphitronClientException"}` (the test context's output package) classifies as an `ErrorType` whose handler's `exceptionClass()` is `GeneratedClientException`. Today the same SDL is rejected with "could not be loaded", the same text `REJECT_GENERIC_WITH_UNRESOLVABLE_CLASS_NAME` pins.
  - The minimal pair: `other.pkg.schema.GraphitronClientException` is still rejected, since only this run's own name is recognised.
  - The no-discriminator `DATABASE` lift classifies to `ClasspathClass("java.sql.SQLException")`.
- **Pipeline tier, the accessor check runs on a clean build** (`ErrorChannelWalkerTest`, and the DML payload path's classification test beside it). On a GENERIC handler naming the generated class, an `@error` type with an extra field `localizedMessage: String` classifies cleanly, since `GraphqlErrorException` inherits `getLocalizedMessage`. The same type with `bogus: String` is rejected, naming source class `graphql.GraphqlErrorException`. Today both are accepted, because the check is skipped.
- **Unit tier.** `CheckedExceptionMatcherTest`: a `GeneratedClientException` handler covers no checked exception. Add a `Handler.accessorSourceClassName()` row for each of the five arms.
- **Generator tier** (`GraphitronClientExceptionClassGeneratorTest`). `emits_singleFinalSubclassOfGraphqlErrorException` asserts the superclass equals `GeneratedClientException.SUPERCLASS` instead of a literal. A new case asserts that the emitted `TypeSpec` declares no methods other than the constructor.
- **Execution tier, the sis shape end to end** (`TenantDivinedRoutingExecutionTest`). In `multitenant.graphqls`, add the `FilmWriteClientError` type and the `FilmWriteClaimedError` union from the Goal, plus a payload and a tenant-routed UPDATE mutation that returns it (beside `retitleFilmsChecked`, whose DATABASE-only payload R1046's tests rely on and so stays as it is). Send a film id naming tenant 2 under the request tenant set `List.of(1)`. Assert:
  - the payload's `errors` holds one `FilmWriteClientError` carrying `"Tenant '2' is not permitted for this request."`;
  - the data field is `null`;
  - the response carries no top-level errors;
  - `TENANT_2_OPENED` is zero.

  CI builds from a fresh checkout, so this module's generate step runs against an empty `target/classes` there, and the test then also covers the clean-build path end to end.

## User documentation

`docs/manual/reference/directives/error.adoc`, the `className` row of the Parameters table, gains a sentence: "To catch Graphitron's own client errors, name `<outputPackage>.schema.GraphitronClientException`, using your configured output package. Graphitron generates that class in the same run, so the name is accepted on a clean build."

`docs/manual/how-to/error-channel.adoc` gains a section after "Handler dispatch", draft:

> == Catching Graphitron's own client errors
>
> Generated code reports a client mistake by throwing `GraphitronClientException`. Examples are a tenant outside the request's tenant set, a node id that decodes to the wrong type, and an out-of-range page size or an invalid cursor. Graphitron generates this class into your output package, at `<outputPackage>.schema.GraphitronClientException`. To deliver these errors as data in a payload, rather than as top-level errors, give an `@error` type a `GENERIC` handler that names it:
>
> ```graphql
> type InvalidInput @error(handlers: [{
>     handler: GENERIC,
>     className: "com.example.generated.schema.GraphitronClientException"
> }]) {
>     path: [String!]!
>     message: String!
> }
> ```
>
> The build accepts the name before the class has ever been compiled, so a clean checkout works. Extra fields on the type read from `graphql.GraphqlErrorException`, the class it extends. Prefer this over a handler on `graphql.GraphqlErrorException` itself: that also catches every other `GraphqlErrorException` your services throw, and it requires graphql-java as a direct dependency of your module.
>
> Coming from Graphitron 9: this is how to keep the in-payload shape Graphitron 9 gave these errors on mutations.

The first-client check reads simply: one class name, spelled from configuration the consumer already has, with no new handler kind to learn.

## Other solutions we've considered

- **A dedicated handler kind** (`{handler: CLIENT}`, a fifth `Handler` variant) that matches "graphitron client errors" without a class name. It still matches by class identity, so it would be a second mechanism next to `ExceptionHandler` doing the same thing. It also touches the directive SDL, `ErrorHandlerType`, and every `Handler` switch. Its one advantage is that the SDL does not spell the output package. The recognised name does spell it, but that is safe: if the configured package is renamed, the old name falls through to the classpath rule and fails the build loudly, rather than silently matching nothing.
- **A stable supertype in a runtime jar.** This breaks the generator's no-runtime-jar invariant, which `GraphitronClientExceptionClassGenerator`'s javadoc names as the reason the class is generated in the first place.
- **Teach `ClasspathNameability` that every class this run emits is nameable.** That fixes only the first of the two checks: `Class.forName` would still miss on a clean build, and the accessor check would still be skipped. No other generated class is a sensible `@error` target, so the general rule would buy nothing this item needs.
- **A shared helper instead of a model component.** Both `resolveHandlerSourceClass` copies, and `CheckedExceptionMatcher`, would call a helper with the output package. That means passing configuration into `ErrorChannelWalker` so it can redo a decision the classifier has already made, and the duplicated four-arm switch would gain a fifth input instead of going away.

## Related

R1046 makes an unmatched `GraphitronClientException` surface as a top-level error on `@error`-channel fetchers instead of being redacted. It decides only the no-match case; this item lets an authored match name the class precisely. Neither depends on the other: the mapping loop runs before the fall-through, so a handler this item enables claims the exception before R1046's fallback is reached.
