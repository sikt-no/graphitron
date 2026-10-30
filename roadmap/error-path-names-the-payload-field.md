---
id: R1126
title: "A matched error's path names the field that owns the payload"
status: In Progress
bucket: bug
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-29
last-updated: 2026-10-30
---

# A matched error's path names the field that owns the payload

## Goal

The `path` an `@error` type answers points at the field whose payload carries the error, `["createFilm"]` (using the alias when there is one, and prefixed by its ancestors' path, list indices included, when that field is nested), not at the `path` field's own response position. This is what Graphitron 9 did and what `error-channel.adoc` already promises. A client can tell which field an error belongs to without stripping `errors`, an index and `path` off the end. An error that carries a path of its own, a Bean Validation violation or a character-length rejection naming the input field, keeps that path. The change is client-visible on every payload error a mutation or query returns.

Mined from the sakila example (`Mutation.createFilmWithErrors`, payload `FilmCreateCheckedPayload { film, errors: [FilmCreateError] }`):

```graphql
mutation { createFilmWithErrors(in: {...}) { film { title } errors { ... on FilmCreateConstraintViolation { path message } } } }
```

| | today | after |
|---|---|---|
| a matched exception | `["createFilmWithErrors", "errors", "0", "path"]` | `["createFilmWithErrors"]` |
| the same, aliased `made: createFilmWithErrors(...)` | `["made", "errors", "0", "path"]` | `["made"]` |
| a claimed `GraphitronClientException` (tenant not permitted) | `[]` | `["retitleFilmsClaimed"]` |
| an over-long `title` | `["createFilmWithErrors", "in", "title"]` | unchanged |

## What the code does today

`ErrorTypeFetcherClassGenerator.pathMethod` emits `<ErrorType>Fetchers.path`, one per `@error` type, wired by name onto the type's `path` field. For a `GraphQLError` source it returns `ge.getPath()`, or `[]` when the error has none. For anything else it returns `env.getExecutionStepInfo().getPath()` of the `path` field itself.

* A matched `Throwable` gets `[<field or alias>, "errors", "<i>", "path"]`. That tells the client where it is reading, which it already knows. Seven `GraphQLQueryTest` assertions pin this shape (`rentFilmPayload`, `filmLookup` twice, `submitFilmReview` twice, `submitFilmReviewWithFilm`, `createFilmWithErrors`; each ends `"errors", "0", "path"`).
* A `GraphQLError` with no path of its own that a handler claims gets `[]`. Graphitron's own client errors take this route when a handler matches them: `GraphitronClientException` extends `GraphqlErrorException` and is built with a message only (`GraphitronClientExceptionClassGenerator`), so its `getPath()` is null. The multitenant fixture `FilmWriteClientError` claims one for a tenant that is not permitted; `TenantDivinedRoutingExecutionTest.dmlPayload_aGenericHandlerOnTheGeneratedClientException_claimsTheTenantRefusal` selects only its `message`. `GraphQLQueryTest.filmLookup_bothShapesSource_prefersAuthoredDescriptionOverGraphQLErrorMessage` pins `[]` for a source that is a `Throwable` and a path-less `GraphQLError` at once.
* A validation violation or a character-length rejection gets its input path (`["createFilm", "in", "title"]`). That is right and stays.

Graphitron 9 (`SchemaBasedErrorStrategy` in graphitron-common 9.4.4, as sis read it from the bytecode) returned `List.of(env.getFieldDefinition().getName())` for a matched exception and for the data-access default, the throwing field's `getExecutionStepInfo().getPath()` for `IllegalArgumentException`, and the input path for validation. It used the field name in one branch and the alias in the other; using the response path (alias) everywhere is fine for sis.

## The rule

For a `Throwable` source, and for a `GraphQLError` source whose `getPath()` is null or empty, `path` returns the response path of the *holder*: the object whose field returned the error. Concretely, start at the `path` field's step, go to the error object's step, step over list-element steps up to the field that returned the error (the errors field), and take that field's parent. The holder's response path is the owning field's path, rendered as today (`String.valueOf` per segment), so it uses the alias, and it carries an index when the owning field is reached through a list (a child `@service` payload under a list of films answers `["<listField>", "0", "<childField>"]`). A `GraphQLError` with a non-empty path keeps it.

The rule is stated over the response tree, not over the error channel, on purpose. An `@error` object can be reached in shapes other than an outcome's errors list, and every `@error` type's `path` is wired to the same fetcher wherever its objects land:

* An errors-shaped list on a record-backed type that no producer returns classifies as a `ChildField.RecordReadField` (`FieldBuilder.liftToErrorsField`, the non-outcome tail): the developer's own list of error objects, read off the value. The holder is that value's position.
* A list of a single `@error` object (`errors: [MyError]`, not a union) and a single-valued `lastError: MyError` both resolve through `BuildContext.resolveReturnType` as a `ScalarReturnType` and classify as a record read on a record parent. For the single-valued one there is no list step to skip; the holder is the type declaring `lastError`.
* `[[MyErrorUnion]]` is accepted as an errors field (`BuildContext.buildWrapper` does not distinguish nested lists); skipping every list-element step makes it answer the same holder.

A fixed "three parents up" is right wherever the error sits exactly one list deep and wrong for `lastError` (no list) and for `[[MyErrorUnion]]` (two); the step-over-list-segments walk answers all of them with one body.

**No deeper owner exists.** An error lands in a payload's errors list only through the catch arm of the fetcher that produced the payload (`ErrorDispatchFragments.outcomeArm` on a `WithErrorChannel` field). A throw from a data field below the payload goes through that data field's own fetcher, not into its sibling errors list. So the manual's parenthetical, `["createFilm", "<some-subfield>"]` "if the throw happened during nested resolution", describes nothing that can happen and is dropped.

## Implementation

`ErrorTypeFetcherClassGenerator.pathMethod` is the whole code change. The emitted body becomes:

```java
public static Object path(DataFetchingEnvironment env) {
    Object src = env.getSource();
    if (src instanceof GraphQLError ge && ge.getPath() != null && !ge.getPath().isEmpty()) {
        return ge.getPath().stream().map(String::valueOf).toList();
    }
    ExecutionStepInfo step = env.getExecutionStepInfo().getParent();
    while (step.getPath().isListSegment()) {
        step = step.getParent();
    }
    return step.getParent().getPath().toList().stream().map(String::valueOf).toList();
}
```

`step` starts at the error object (whose path ends in the list index when it is a list element), the loop leaves it on the field that returned the error, and its parent is the holder. Neither `getParent()` can be null: the `path` field always has an error object as parent, and the field that returned the error always has at least the root step as parent. When the holder is the root (an errors-shaped field declared directly on `Query` or `Mutation`, see Out of scope) the answer is `[]`, as today for a path-less error. The emitted code compiles at Java 17 (pattern `instanceof` is 16).

The class javadoc's "`path` synthesises from the GraphQL execution context for non-`GraphQLError` sources" sentence changes to state the holder rule and the own-path precedence. No classifier, model or schema change: the fetcher reads only what graphql-java's step info already carries.

## Docs

`docs/manual/how-to/error-channel.adoc` § "`path:`, `message:` field fetchers":

* The code excerpt under "`path:` reads the GraphQL execution step path" is replaced by a sentence stating the rule: the response path of the field whose payload holds the error, with its alias and with a list index when the payload is a list element.
* The `VALIDATION` paragraph generalises to "a `GraphQLError` that carries its own path keeps it"; validation and the column-length check are the two producers named.
* The example sentence's target (`path: ["createFilm"]`) stays; its parenthetical about nested resolution is dropped (see The rule).
* The Pitfalls bullet "*`path:` is the response path.*" keeps its advice (encode input positions in the exception or an extra field) and loses its mechanism sentence about `env.getExecutionStepInfo().getPath()`.

`migrating-from-legacy.adoc` gains nothing: Graphitron 9 answered the owning field's name, this answers its response path, and the two differ only for an aliased field. sis asserts `hasItem(<field>)` and is indifferent; a line for a difference no known consumer depends on would be noise.

## Tests

All in the execution tier (`graphitron-sakila-example`, real PostgreSQL), since the change is in what a client reads off a response. The generator-tier wiring test (`GraphitronSchemaClassGeneratorTest`, which checks `<ErrorType>Fetchers::path` is registered) is unaffected, but its `build_errorTypeFieldFetchers_pathSynthesisRoutesGraphQLErrorThroughGetPath` pins the emitted body's `env.getExecutionStepInfo().getPath().toList()` and follows the new walk.

* The seven `GraphQLQueryTest` assertions ending `"errors", "0", "path"` flip to the owning field alone (`["rentFilmPayload"]`, `["filmLookup"]`, `["submitFilmReview"]`, `["submitFilmReviewWithFilm"]`, `["createFilmWithErrors"]`).
* `filmLookup_bothShapesSource_prefersAuthoredDescriptionOverGraphQLErrorMessage` flips from `isEmpty()` to `["filmLookup"]`, with its comment rewritten: a path-less `GraphQLError` now answers the holder too.
* `TenantDivinedRoutingExecutionTest.dmlPayload_aGenericHandlerOnTheGeneratedClientException_claimsTheTenantRefusal` adds `path` to its `FilmWriteClientError` selection and asserts `["retitleFilmsClaimed"]`: a real `GraphitronClientException`, claimed into a DML payload.
* New, aliased: `createFilmWithErrors` under an alias (`made: createFilmWithErrors(...)`) answers `["made"]`.
* New, nested under a list: no sakila fixture has a payload with an errors field below the root (every carrier is a `Query` or `Mutation` field). Add one child `@service` on `Film` returning a small payload with an errors union, its service throwing a mapped exception for one film id, modelled on an existing child `@service` fixture in `graphitron-sakila-service`. Querying it through a list of films asserts `["<rootField>", "<i>", "<childField>"]`, which pins the index segment and the walk past a non-root holder. A child carrier with an errors field must return a single payload (`FieldBuilder.buildMethodBackedWithChannel` rejects a list), so the fixture's field is single-valued.
* Unchanged, and kept as the pin that a `GraphQLError`'s own path wins: the validation case (`["countFilmDrafts", "in", "1", "title"]`) and the three character-length cases (`createFilmWithErrors`, `createFilmsWithErrors`, `updateFilmWithErrors`).

## Out of scope

Surveying the shapes an `@error` object can reach turned up four classifier gaps. None changes this rule, and none is fixed here:

* A root `@service` returning `[Payload]` where the payload has an errors field classifies (`ServiceRecordCompositeCarrierPipelineTest.listCarrier_classBacked_collectionProducer_admitsCoherentComposite` pins it with `errorChannel()` present) but its fetcher returns one non-iterable `Outcome` for a list field on both arms. DML `@mutation` rejects a list payload (`MutationInputResolver.validateReturnType`) and child `@service` does (`buildMethodBackedWithChannel`); `@routine` carriers have no check.
* An `@service` field returning an errors-shaped union list directly, with no payload, lifts through `ServiceDirectiveResolver` into `liftToErrorsField` and on `Query`/`Mutation` becomes a `RecordReadField` with the `@service` silently dropped.
* `[[MyErrorUnion]]` is accepted as an errors field.
* Outcome-ness is `BuildContext.isServiceProducedPayloadAnywhere` (some `@service` returns the type), not tied to a classified channel-bearing producer, so a payload reached through a non-producer field elsewhere has an errors fetcher that expects an `Outcome` source.

## Other solutions we've considered

**Record the path when the error is claimed.** The router has the owning field's `env` at `ErrorRouter.claim`, so it could stamp the path there. But the errors list holds the raw source, and extra fields on the `@error` type read accessors off that source through `PropertyDataFetcher`, so the path would have to travel beside the source rather than wrap it: a new carrier through `Outcome.ErrorList` and a second fetcher shape for extra fields. It would also cover only errors the router claims, leaving the record-read shapes above on a different answer. The step-info walk needs no runtime state and gives every shape one answer.

**Walk exactly three parents up.** Correct for an errors list on an outcome payload, wrong for a single-valued `@error` field and for a nested list. The list-segment loop costs one line more.

## Provenance

Requested 2026-10-29 by sis (the Graphitron 10 port, request C37), reversing R1085. R1085 proposed correcting the manual to match the code. sis wants the code corrected to match the manual, so R1085 is discarded in favour of this item. sis's ITs assert only `hasItem(<field>)` and pass with either shape.
