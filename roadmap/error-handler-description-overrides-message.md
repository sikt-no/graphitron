---
id: R686
title: "Surface the @error handler description: as the client-facing message instead of the raw exception message"
status: Spec
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

The gap is at the read side. Dispatch is source-direct: on a match the matched `Throwable`
itself goes into the errors list, and the error type's `message:` slot is resolved by
`no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator#messageMethod`,
which unconditionally returns `getMessage()` off the source. The matched `Mapping` is out of
scope by the time the field is fetched, so the override has nothing to act through.

Two things make the no-op worse than a plain missing feature. There is no diagnostic: a
schema that sets `description:` builds clean and runs, and the author discovers the message
is wrong only from a client. And the manual contradicts itself, so a reader cannot resolve
the behaviour from the docs. `docs/manual/reference/directives/error.adoc` documents
`description` as "Static error message returned to the client. Defaults to the exception's
`getMessage()`", the Graphitron 9 contract, while `docs/manual/how-to/error-channel.adoc`
documents it as captured-but-unused and tells authors to write the client-facing string into
the exception instead.

## The constraint that shapes the design

The matched source object placed into the errors slot is read by four independent consumers,
not one:

1. The `@error`-only union/interface `TypeResolver`, which dispatches by `instanceof` on the
   source class (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`).
2. The synthesised `<ErrorType>Fetchers::path` and `::message`.
3. graphql-java's `PropertyDataFetcher`, for extra author-declared fields, honouring
   `@field(name:)` remaps recorded in `ErrorType.accessorOverrides`. The
   `FilmLookupInvalid.attempted -> getAttemptedId()` fixture in the sakila example exercises
   this.
4. `HandlerAccessorCheck.check`, at classify time. It resolves each handler's *declared*
   source class through `resolveHandlerSourceClass` (the named exception class,
   `java.sql.SQLException`, or `graphql.GraphQLError`) and reflects on it to prove every
   extra SDL field resolves to a real accessor.

Any design that changes *what object* reaches the errors slot has to keep all four working.
That is the whole difficulty, and it is what rules out the approach the current javadoc
anticipates.

## Why the anticipated facade is the wrong shape

`ErrorRouterClassGenerator` and the error-channel how-to both promise a
"description-overriding facade": wrap the matched exception in an adapter whose
`getMessage()` returns the description, preserving the throwable's identity for logging.

Against consumers 1 and 3 it simply does not work. A wrapper is by construction not
`instanceof` the author's exception class, so the `TypeResolver` ladder stops resolving and
the union member cannot be selected; and the extra-field reads would go to the wrapper, which
has no `getAttemptedId()`. Making a wrapper transparent to both would require a generated
per-exception-class delegating subclass, which is not possible in general: author exception
classes may be `final`, may lack an accessible constructor, and expose no interface to proxy.

Consumer 4 is the decisive objection. `HandlerAccessorCheck` is a *classify-time acceptance
stated over the declared source class*. A facade swaps the runtime class out from under a
check that already passed at build time, converting a build-time guarantee into a runtime
property-fetcher miss discovered days later. Shipping the facade would mean weakening a live
classifier acceptance, which drags in every emitter site that consumes the same shape. That
is a far larger blast radius than this item, and in the wrong direction.

So this item does not implement the promised facade. It retires that promise and takes the
read-side route instead. The vocabulary retirement is declared below.

## Implementation

Decide the override where the value is read, not where the exception is routed. Four moves,
in dependency order.

### 1. Resolve the branch once, in the model

Replace `Optional<String> description()` on `ErrorType.Handler` with a resolved sealed slot,
`sealed interface ClientMessage permits ClientMessage.Static, ClientMessage.FromSource`,
decided once in `TypeBuilder`. `Static` carries the string; `FromSource` carries nothing.

The `Optional` is currently re-branched by three consumers (`ErrorMappingsClassGenerator`
across three arms via `literalOrNull`, `HandlerKey.of` across four, and under any read-side
design a third time), and the naive version of this fix adds a fourth branch that runs *at
runtime* inside every generated `message()` body as a `description != null` test. That
deferred null check is the tell: the classifier fully resolves this at build time, and
generated code carrying a defensive guard over a decided fact reads as noise to a consumer
who has never seen the generator. With the sealed slot each `message()` arm emits either
`return "...";` or `return thr.getMessage();` with no test, and every consumer's handling is
compile-checked.

### 2. Reject `description:` on a VALIDATION handler at validate time

`ValidationHandler` does not carry a `ClientMessage` at all, and a `description:` alongside
`handler: VALIDATION` becomes a typed rejection with a stable LSP code, sitting beside the
existing per-channel error-channel rules. Rationale under "Settled questions" below.

### 3. Mint the `Mapping[]` per `@error` type, and compose the channel arrays from them

`description:` is authored at the `@error` type's handler entry, so it is definition-keyed.
The existing `Mapping[]` constants are keyed on `ErrorChannel.mappingsConstantName()`, a
use-site coordinate naming which fetcher's payload. Reading a definition-keyed value off a
use-keyed constant is what forces the overlap, and the confusion is already producing an
artifact: `ErrorMappingsClassGenerator.HandlerKey` puts `description` into the *channel
dedup identity*, so two channels with identical dispatch behaviour but different author
descriptions are split into separate constants even though `description` does not
participate in dispatch at all.

Mint one `Mapping[]` per `@error` type instead, and derive each channel constant as the
ordered concatenation of the per-type arrays it maps. `ErrorChannel.mappedErrorTypes()`
already carries exactly that list in exactly that order, so the derivation is total. One row
population, read at two grains. `description` then drops out of `HandlerKey` on its own.

### 4. Read the override at the message fetcher, reusing the one match spelling

`<ErrorType>Fetchers.message(env)` walks its own type's `Mapping[]` in source order: the
first mapping that matches the source resolves the message from its `ClientMessage` arm,
falling through to `getMessage()` when no mapping matches. The source object is never
touched, so all four consumers above are unaffected and dispatch keeps its source-direct
contract unchanged.

**This must reuse `ErrorRouter.Mapping`, not re-implement the predicates.** "Does handler H
fire on throwable T" already has two spellings in the tree: the `Mapping.match` family, used
by all three dispatch arms (`ErrorRouter.dispatch`, `ErrorRouter.dispatchToLocalContext`, and
`ChannelCatchArmEmitter`, which inlines the loop and returns an `Outcome.ErrorList` without
going through `ErrorRouter` at all), and a hand-rolled `instanceof` ladder in
`buildErrorPolymorphicResolver` that re-implements the same three discriminators in emitter
code. The two have already drifted: `ErrorMappingsClassGenerator.bestGuessOrObject` falls
back to `Object.class` on a malformed class name, while `buildErrorPolymorphicResolver` calls
`ClassName.bestGuess` bare and throws on the same input. A read-side override that grew its
own predicate would be a third spelling.

Also mint `ErrorRouter` and `ErrorMappings` through `GeneratedUnits.singleton(SUB_SCHEMA, ...)`
while here. `ErrorTypeFetcherClassGenerator` needs to name `ErrorRouter.Mapping`, and
hand-spelling `outputPackage + ".schema"` there would be the third copy of that formula after
`ErrorRouterClassGenerator.noChannelRouterCall` and `ChannelCatchArmEmitter.errorRouterClass`.
Minting it costs one method and removes a formula instead of adding a copy.

### 5. Make the two manual pages agree

`docs/manual/reference/directives/error.adoc` becomes true as written, plus an explicit
sentence on the VALIDATION rejection, which is not inferable from the directive signature.
The how-to's "captured but currently unused" section and its matching pitfall bullet come
out, replaced by the resolution order and by the one thing that stays true: the *other*
fields on the error type still read off the live exception.

## Settled questions

**A `VALIDATION` handler's `description:` is rejected, not honoured and not ignored.**
`ConstraintViolations.toGraphQLError` produces one `GraphQLError` per `ConstraintViolation`,
each with its own interpolated message and its own spliced property path. The description is
one authored string keyed on the handler declaration. Those are two facts at two grains
produced by two independent walks, and honouring the description would collapse every
violation on the channel to a single string, discarding exactly the per-violation detail Bean
Validation exists to produce. There is also no matched handler on that path to attribute an
override to: the VALIDATION arm never runs a match. Ignoring it, on the other hand, is the
defect this item exists to close, reintroduced at smaller blast radius. Rejecting says so at
build time and points the author at the constraint annotation's own `message` attribute,
which is where Jakarta already puts that authoring surface. With move 1 in place this falls
out structurally rather than by convention: `ValidationHandler` has no `ClientMessage`, and
the SDL-to-model lift is where the rejection lands.

## Open for the implementer

**Whether the per-type override table rides on the command row.** The `@error` fetchers
command row is `TypeUnitCommand.FetchersUnit(typeName, unit)`, name and unit only, and
`TypeFetcherGenerator` already reaches back into `schema.type(row.typeName())` to recover the
model. Move 4 deepens that seam: the renderer would iterate `et.handlers()`, decide which arm
contributes an override, and decide the walk order, all joins the planner is the place for.
The principled shape carries the resolved table (or just the `UnitRef` of the per-type
`Mapping[]`) on the row. That is a real plumbing change to `TypeUnitCommands.fetchersRows`,
so the implementer should scope it deliberately rather than absorb it silently. Doing it is
the recommendation; deferring it needs a note saying so.

**Re-sourcing `buildErrorPolymorphicResolver` onto `Mapping[]` is out of scope here.**
Collapsing the ladder to a `Mapping[]` walk would leave one spelling of the predicate and fix
the `bestGuess` drift noted above, but it is a separate refactor with its own blast radius
across the TypeResolver emission. File it as its own Backlog item rather than folding it in.
This item must not cite the ladder as precedent for re-deriving matches; the ladder is the
weaker of the two spellings, not the pattern to copy.

## Coverage

The reason this defect shipped is that every layer between `TypeBuilder` and the wire has a
passing test of its own shape: the value is lifted, emitted as a constructor argument, and
exposed through an accessor, and all of that is green. Only the execution tier distinguishes
"the description reached the generated constant" from "the description reached the client",
so that tier carries the acceptance.

* Execution tier, the acceptance: the sakila example already has the ideal paired fixture.
  `FilmLookupInvalid` and `FilmLookupNotFound` sit in one union, and
  `GraphQLQueryTest.filmLookup_invalidId_routesThroughInvalidIdErrorType` currently asserts
  `message == "invalid id: -7"`, the raw exception message. Add `description:` to
  `FilmLookupInvalid`'s handler only: that test flips to the authored string while
  `filmLookup_zeroId_routesThroughNotFoundErrorType` keeps asserting `getMessage()`, which
  proves the override is per-handler and not global. The `attempted` assertion in the same
  test is the regression guard that the extra-field read still goes to the live exception.
* Pipeline tier: the VALIDATION rejection, and the per-type/per-channel `Mapping[]`
  composition (that a channel array equals the concatenation of its types' arrays in
  declaration order).
* Unit tier: the `ClientMessage` lift in `TypeBuilder`. No code-string assertion on the
  emitted `message()` body at any tier; that form is banned, and here it would re-create
  exactly the false confidence that let this ship.

## Retired vocabulary

* "description-overriding facade" and the "follow-on emitter concern" framing. Five known
  occurrences: `ErrorRouterClassGenerator` lines 158 and 371-372 (the `Mapping.description()`
  javadoc and the `dispatch` javadoc), and `docs/manual/how-to/error-channel.adoc` lines 81
  and 85. The `Mapping` javadoc's "A future emitter pass will wrap the matched source"
  sentence goes with it.
* "`description:` is documentation today" as a statement of behaviour, in the how-to's
  pitfalls list.
* `Handler.description()` as an accessor name, if move 1 lands: the replacement is
  `clientMessage()`.

## Out of scope

* The fact store already captures `description` (`GraphitronFactCapture` writes it to
  `graphitron_error_handler`), so the LSP surface needs no change beyond the new rejection
  code.
* No change to dispatch, to the source-direct contract, to `redact`, or to the `path:` slot.
* Re-sourcing the `@error` union `TypeResolver` onto `Mapping[]`, per the note above.

## Provenance

Filed from a user report that `description:` worked in Graphitron 9 and appears ignored in
Graphitron 10. Confirmed as captured-but-unused rather than a parse failure by walking the
value from `TypeBuilder` through `ErrorMappingsClassGenerator` to the unread
`Mapping.description()` accessor. Design shaped by a `principles-architect` consult, which
supplied the keying analysis in move 3, the `HandlerAccessorCheck` objection to the facade,
the VALIDATION resolution, and the correction that the `TypeResolver` ladder is a drifted
second spelling rather than a precedent to follow.
