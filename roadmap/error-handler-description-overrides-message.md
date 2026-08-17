---
id: R686
title: "Surface the @error handler description: as the client-facing message instead of the raw exception message"
status: Backlog
bucket: bug
priority: 4
theme: error-channel
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Surface the @error handler description: as the client-facing message instead of the raw exception message

## Problem

An `@error` handler entry may carry `description:`, a string the schema author writes to
give clients a stable, sanitised message in place of whatever the underlying exception
happens to say. Graphitron 9 honoured it. The rewrite parses it, carries it through the
model, and emits it into generated code, but never reads it back, so authoring it is a
silent no-op: the client sees the raw exception message instead.

The value survives the whole pipeline up to the point of use. `TypeBuilder` lifts it onto
every `ErrorType.Handler` variant as `description()`;
`no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator` emits it as the
third constructor argument of each emitted `Mapping` constant; and
`no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator` gives every
`Mapping` variant a `description()` accessor. Nothing calls that accessor.

The gap is the dispatch contract. Dispatch is source-direct: on a match, the matched
`Throwable` itself goes into the errors list, and the error type's `message:` slot is
resolved by
`no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator#messageMethod`,
which unconditionally returns `getMessage()` off the source. The matched `Mapping` and its
`description` are out of scope by the time the field is fetched, so the override has no
seam to act through. Whatever shape the fix takes (the emitted javadoc anticipates a
description-overriding facade wrapping the matched throwable, but that is one option, not a
decision), it has to preserve the throwable's identity for logging and observability while
changing only what the `message:` field reads.

Two things make the no-op worse than a plain missing feature. There is no diagnostic: a
schema that sets `description:` builds clean and runs, and the author discovers the message
is wrong only from a client. And the manual contradicts itself, so a reader cannot resolve
the behaviour from the docs. `docs/manual/reference/directives/error.adoc` documents
`description` as "Static error message returned to the client. Defaults to the exception's
`getMessage()`", i.e. the Graphitron 9 contract, while
`docs/manual/how-to/error-channel.adoc` correctly documents it as captured-but-unused and
tells authors to write the client-facing string into the exception instead. The reference
page is the one that is wrong today; whichever way this item resolves, the two pages must
end up agreeing.

Open question for Spec: whether `ValidationHandler`'s `description` participates. Bean
Validation failures never enter the dispatch loop (the wrapper routes pre-built
`GraphQLError`s straight into the errors slot), so a `VALIDATION` handler's `description`
has a different, and possibly separate, seam from the three dispatch-capable variants.

