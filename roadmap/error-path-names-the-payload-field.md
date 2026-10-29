---
id: R1126
title: "A matched error's path names the field that owns the payload"
status: Backlog
bucket: bug
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# A matched error's path names the field that owns the payload

## Goal

The `path` an `@error` type answers points at the field whose payload carries the error, `["createFilm"]` (or `["a", "createFilm"]` when that field is nested, using the alias when there is one), not at the `path` field's own response position. This is what Graphitron 9 did and what `error-channel.adoc` already promises. A client can tell which mutation an error belongs to without parsing `errors` indices out of the path. An error that carries a path of its own, a Bean Validation violation or a character-length rejection naming the input field, keeps that path. The change is client-visible on every payload error a mutation returns.

## What the code does today

`ErrorTypeFetcherClassGenerator.pathMethod` emits `<ErrorType>Fetchers.path`. For a `GraphQLError` source it returns `ge.getPath()`, or `[]` when the error has none. For anything else it returns `env.getExecutionStepInfo().getPath()` of the `path` field itself.

* A matched `Throwable` gets `[<field or alias>, "errors", "<i>", "path"]`. That tells the client where it is reading, which it already knows. `GraphQLQueryTest` pins this shape for `rentFilmPayload`, `filmLookup`, `submitFilmReview`, `submitFilmReviewWithFilm` and `createFilmWithErrors` (assertions ending `"errors", "0", "path"`).
* A `GraphQLError` with no path of its own that a handler claims gets `[]`. Graphitron's own client errors take this route when a handler matches them: a `GraphqlErrorException` for a tenant that is not permitted, a node id of the wrong type, a `NodeIdEncoder.requireColumnAgreement` conflict.
* A validation violation or a character-length rejection gets its input path (`["createFilm", "in", "title"]`). That is right and stays.

Graphitron 9 (`SchemaBasedErrorStrategy` in graphitron-common 9.4.4, as sis read it from the bytecode) returned `List.of(env.getFieldDefinition().getName())` for a matched exception and for the data-access default, the throwing field's `getExecutionStepInfo().getPath()` for `IllegalArgumentException`, and the input path for validation. It used the field name in one branch and the alias in the other; using the result path (alias) everywhere is fine for sis.

## The change

For a `Throwable` source, and for a `GraphQLError` source whose `getPath()` is null or empty, `path` returns the response path of the field whose payload holds the error. A `GraphQLError` with a path keeps it.

There are two ways to find the owning field. Spec picks one:

* **Walk up from the `path` field.** The errors field is a direct field of the outcome type (`OutcomeType` classification allows exactly one), so the owning field's step info is three parents up: the `path` field, the `errors[i]` element, then the errors list. No runtime state, but it relies on the errors list sitting directly on the type the owning field returns. Spec confirms that holds for every carrier (DML `@mutation` payloads, `@service` payloads, `@routine` payloads), including a payload reached through a list.
* **Record the path when the error is claimed.** The router has the owning field's `env` when it routes, but the payload's errors list holds the raw source, and extra fields on the `@error` type read accessors off that source. So the path would have to travel beside the source, not wrap it.

Spec also settles the manual's parenthetical: `["createFilm", "<some-subfield>"]` "if the throw happened during nested resolution". Under this rule an error is claimed by the field that owns the payload, so check whether any path can produce a deeper owner. If none can, drop the parenthetical.

## Docs

`docs/manual/how-to/error-channel.adoc` § "`path:`, `message:` field fetchers": the example sentence (`path: ["createFilm"]`) already states the target and stays. The code excerpt above it and the "synthesised fetcher reads `env.getExecutionStepInfo().getPath()`" bullet in the closing caveats describe today's mechanism and change with it. `migrating-from-legacy.adoc` gains a line only if the alias-versus-name difference from Graphitron 9 is worth stating.

## Tests

The seven `GraphQLQueryTest` assertions listed above flip to the owning field's path, plus one aliased and one nested case, and a claimed path-less `GraphQLError` (a wrong-type node id under a handler that matches it) answering the owning field's path rather than `[]`. The validation and character-length cases are unchanged and stay as the pin that a `GraphQLError`'s own path wins.

## Provenance

Requested 2026-10-29 by sis (the Graphitron 10 port, request C37), reversing R1085. R1085 proposed correcting the manual to match the code. sis wants the code corrected to match the manual, so R1085 is discarded in favour of this item. sis's ITs assert only `hasItem(<field>)` and pass with either shape.
