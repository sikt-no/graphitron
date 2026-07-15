---
id: R202
title: "Honor @field(name:) in @error type extra-field accessor matching against handler source class"
status: Ready
bucket: bug
priority: 5
theme: error-channel
depends-on: []
created: 2026-05-20
last-updated: 2026-07-15
---

# Honor @field(name:) in @error type extra-field accessor matching against handler source class

## Problem

An `@error` object type's extra fields (everything except `path` / `message`, which get synthesised per-type DataFetchers) must be populated from each handler's source class: the named exception class for a `GENERIC` handler, `java.sql.SQLException` for `DATABASE`, `graphql.GraphQLError` for `VALIDATION`. The accessor-coverage check calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` with the raw SDL name as the accessor base. When the source class's accessor diverges from the SDL field name (an exception exposing `getErrorCode()` mapped to an SDL field named `code`, a Norwegian-named accessor under an English SDL, or `SQLException.getSQLState()` under an SDL field `state`), the resolver returns `Rejected` and the type fails classification with no author escape hatch. `@field(name:)` on the SDL extra field is the natural override: this is structurally the R191 case (free-form Java class as the logical parent, SDL field bound by accessor name, divergent names need a directive remap), and the directive's docstring already covers the site under the "underlying-binding target" reading.

## Corrections to the original Backlog note (verified against the code 2026-07-15)

Two claims in the Backlog body were wrong and reshape the fix:

1. **There are two live check sites, not one.** Besides `FieldBuilder.checkErrorTypeSourceAccessors` (called from `resolveErrorChannel`, which serves the class-backed payload and DML mutation paths), R244 absorbed a copy into `no.sikt.graphitron.rewrite.walker.internal.HandlerAccessorCheck`, which `ErrorChannelWalker` runs for the `@service` outcome path (`FieldBuilder.resolveServiceOutcomeChannel`). Both call `ClassAccessorResolver.resolve` with `sdlField.getName()`; both must honor the directive, without creating a second copy of the remap rule.
2. **"No emitter change needed" is false.** Both check sites discard the `Resolved` arm; they are validation-only. At runtime the error router places the matched source object (throwable or `GraphQLError`) directly in the payload's errors list, and extra fields are read by graphql-java's **default `PropertyDataFetcher`, keyed on the SDL field name** (see the dispatch contract in `ErrorRouterClassGenerator` and the `GraphitronType.ErrorType` javadoc). Only `path` / `message` get registered fetchers (`<ErrorType>Fetchers::path` / `::message`, wired by `GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers`). A check-only remap would classify cleanly and then silently resolve `null` at runtime. The fix must register a remapped property fetcher for every directive-carrying extra field.

## Design

Read the directive once, at the parse boundary, and let the model carry it to every consumer.

### Model: `GraphitronType.ErrorType` carries the overrides

`ErrorType` (a record of `name`, `location`, `handlers`) gains a component holding the extra-field accessor overrides, populated by `TypeBuilder.buildErrorType`, which already has the `GraphQLObjectType` in hand and already admits extra fields structurally. Shape: `List<FieldAccessorOverride>` where `FieldAccessorOverride(String sdlFieldName, String accessorBase)` is a small nested record, in SDL declaration order, entries only for extra fields that carry `@field`, defensively copied with `List.copyOf` (mirroring `handlers`; a `Map` component would lose declaration order under `Map.copyOf` and make the emitter's output order non-deterministic). A defaulting convenience `accessorBaseFor(String sdlFieldName)` returns the override or the field name itself. `buildErrorType` reads the directive with the house idiom (`argString(f, DIR_FIELD, ARG_NAME)`).

Rejections added at `buildErrorType` (joining the existing `rejectReasons` collection):

- a present-but-blank `@field(name: "")` on an extra field (mirrors R200's blank rejection);
- `@field` applied to `path` or `message`: those fields are populated by synthesised fetchers, so the directive can never take effect there; rejecting is honest where silently ignoring would recreate exactly the failure mode this item fixes.

This keeps `BuildContext.DIR_FIELD` / `argString` (package-private to `no.sikt.graphitron.rewrite`) out of `walker.internal`, which otherwise could not use the house directive-reading idiom without visibility widening.

All `new ErrorType(...)` construction sites gain the new component (`TypeBuilder.buildErrorType` plus the test fixture helpers in `ErrorChannelWalkerTest`, `ErrorMappingsClassGeneratorTest`, `ChannelCatchArmEmitterTest`, `MappingsConstantNameDedupTest`, `CheckedExceptionMatcherTest`, `ErrorTypeValidationTest`).

### Check sites: one-line remap each

Both `FieldBuilder.checkErrorTypeSourceAccessors` and `HandlerAccessorCheck.check` pass `errorType.accessorBaseFor(sdlField.getName())` to `ClassAccessorResolver.resolve` instead of `sdlField.getName()`. The remap rule lives in the model, so the two bodies cannot drift on it. (Consolidating the legacy body onto `HandlerAccessorCheck`, the `ChannelRuleChecks` delegation pattern, remains R244's own pending cleanup and is out of scope here.)

`ErrorChannelWalkerError.HandlerSourceAccessorMissing` gains an `accessorBaseName` component (equal to the field name when no directive is present); `message()` appends a `(remapped to '<base>' by @field)` parenthetical when it diverges, so a failed remap is diagnosable from the reject text. `FieldBuilder.checkErrorTypeSourceAccessors`'s reject string gains the same parenthetical.

### Runtime wiring: register a property fetcher per override

`GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers` receives the `ErrorType` (today just the type name) and, after the `path` / `message` registrations, emits per override entry:

```java
codeRegistry.dataFetcher(FieldCoordinates.coordinates(typeName, sdlFieldName),
    PropertyDataFetcher.fetching(accessorBase));
```

`PropertyDataFetcher.fetching`'s candidate lookup (getter, `is`-getter, record-style method, public field) mirrors `ClassAccessorResolver`'s `POJO_FIRST` candidate set, so the classify-time check and the runtime read use the same name-derivation rules — but note honestly: this parity is a review-time claim, not mechanically enforced, and it is inherent to the `@error` surface rather than introduced here (the non-directive path already pairs `ClassAccessorResolver` against the default `PropertyDataFetcher`, and the accessor genuinely cannot be resolved once on the model because it varies per runtime source object across a polymorphic error union). The execution-tier test below pins one end-to-end instance; a meta-test binding the two candidate sets is candidate Backlog material, not R202 scope. Reusing `PropertyDataFetcher.fetching` rather than extending the generated `<ErrorType>Fetchers` class is deliberate: it keeps the remapped path on the same runtime resolver as the default path (only the carried base name varies) instead of introducing a third resolver. Extra fields without the directive keep resolving through the unregistered default (by SDL name), unchanged. While touching these sites, fix the stale javadocs on `buildErrorTypeFieldFetchers` and `ErrorTypeFetcherClassGenerator` that still claim `@error` types are restricted to exactly `path` + `message` (extras were admitted when the accessor check landed).

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc` currently documents no extra fields at all; it gains a short "Extra fields" section that is also the doc surface for this item:

> Beyond the required `path` and `message`, an `@error` type may declare extra fields. Each extra field is read from the matched exception (or, for `VALIDATION`, the `GraphQLError`) through an accessor matching the field name: `code: String` reads `getCode()`, `code()`, or a public `code` field. When the Java accessor name diverges from the GraphQL field name, `@field(name:)` names the accessor to use instead:
>
> ```graphql
> type FilmLookupInvalid @error(handlers: [{
>         handler: GENERIC,
>         className: "no.sikt.graphitron.rewrite.test.services.FilmLookupInvalidIdException"
>     }]) {
>     path: [String!]!
>     message: String!
>     attempted: Int @field(name: "attemptedId")   # reads getAttemptedId()
> }
> ```
>
> The build fails when a declared extra field cannot be populated from every handler's source class, listing the accessors the class does expose. `@field` on `path` or `message` is rejected: those two fields are populated by Graphitron itself.

The `@field` docstring in `directives.graphqls` (FIELD_DEFINITION free-form bullet) gains one sentence naming `@error` extra fields as an instance of the free-form accessor axis, matched against each handler's source class; `field.adoc` cross-links to the new `error.adoc` section.

Docs sequencing: the `error.adoc` example above is pinned end-to-end by this item's execution test, so it may land with R202. But the broader `@field`-on-the-`@error`-surface story is only coherent once R201 (payload construction) also honors the directive; if R201 has not landed in the same or immediately-following commit, keep the docstring/manual prose scoped to the extra-field read (do not generalise to "the errors channel honors @field") so the docs never advertise a half-implemented behaviour.

## Tests

- **Pipeline tier** (`ErrorChannelClassificationTest`, `@service` path): positive remap using a JDK accessor so no new fixture is needed (`detail: String @field(name: "localizedMessage")` resolves `Throwable.getLocalizedMessage()` where the undirected spelling rejects), asserting the classified `ErrorType` carries the override; remapped-but-still-missing rejects with a message naming both the SDL field and the directive value; regression floor: divergent accessor without the directive still rejects (existing `extraField_missingAccessorOnGenericSourceClass_rejectsCarrier` stays green).
- **Pipeline tier, legacy path**: one positive remap test through `resolveErrorChannel` (class-backed payload / DML mutation carrier), since the two check sites are distinct code paths.
- **Pipeline tier, parse rejections**: blank `@field(name: "")` on an extra field rejects; `@field` on `path` / `message` rejects (SDL-to-`UnclassifiedType` assertions, existing `@error` validation-test home).
- No generator-tier wiring test: asserting the emitted `PropertyDataFetcher.fetching(...)` registration would be a code-string match on a generated body, which the test-tier rules ban; the execution-tier test below is the enforcer for the wiring. `ErrorChannelWalkerTest` fixture helpers get the new `ErrorType` component mechanically; the remap and diagnostic facts are pinned at the pipeline tier rather than by new walker unit tests.
- **Execution tier** (`graphitron-sakila-example`): `FilmLookupInvalidIdException` (in `graphitron-sakila-service`) gains a divergently-named getter (e.g. `getAttemptedId()`); the example schema's `FilmLookupInvalid` gains `attempted: Int @field(name: "attemptedId")`; the existing `filmLookup_invalidId_routesThroughInvalidIdErrorType` flow gains an assertion that the value round-trips. This is the first execution-tier coverage of `@error` extra fields at all, and it is the enforcer for both the runtime wiring and (one instance of) the check-to-runtime candidate-set parity.

## Out of scope

- R244's pending consolidation of `FieldBuilder.checkErrorTypeSourceAccessors` onto `HandlerAccessorCheck`.
- The FieldBuilder payload-construction site (`resolvePayloadConstructionShape`): that is R201's half.
- Restructuring `@field(name:)` itself or the three-option rejection texts elsewhere.

## Relationship to R201

R201 (`honor-field-directive-in-payload-construction-shape`) honors the same directive at the *payload construction* site; this item honors it at the *source-accessor read* site plus the runtime property read. Ship the two together or back-to-back: if only one ships, an author who writes `@field(name:)` on an `@error`-adjacent field gets it honored on one path and silently ignored on the other.
