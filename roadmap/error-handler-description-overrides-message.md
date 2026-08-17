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
`no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator#messageMethod`.
That method forks on the source's runtime shape, a `GraphQLError` arm and a `Throwable` arm
over a `null` fall-through, but both live arms return `getMessage()` and nothing else is
consulted. The matched `Mapping` is out of scope by the time the field is fetched, so the
override has nothing to act through.

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

Replace `Optional<String> description()` with a resolved sealed slot,
`sealed interface ClientMessage permits ClientMessage.Static, ClientMessage.FromSource`,
decided once in `TypeBuilder`. `Static` carries the string; `FromSource` carries nothing.

The slot goes on the three dispatch records (`ExceptionHandler`, `SqlStateHandler`,
`VendorCodeHandler`), not on the `ErrorType.Handler` interface. Move 2 rejects
`description:` on a `ValidationHandler`, so that variant has no client message to carry and
declaring `clientMessage()` one level up would force it to fake one. This is the opposite
choice from `matches()`, which stays on the interface with `ValidationHandler` overriding it
to `Optional.empty()`; leave `matches()` alone, the two are not a pair to keep symmetric.
Do not mint an intermediate `permits`-level seal for the three dispatch variants either: a
consumer holding a bare `Handler` already switches on arms to reach the discriminator, and
one accessor does not pay for a new layer in the hierarchy.

The `Optional` is currently re-branched by three build-time consumers in the same shape:
`ErrorMappingsClassGenerator` across three arms via `literalOrNull`, its `HandlerKey.of`
across four, and `MappingsConstantNameDedup.handlerLine` across four more, the last a
near-verbatim duplicate of `HandlerKey.of`. A read-side design adds a fourth consumer, and
the naive version of this fix makes that fourth branch run *at runtime* inside every
generated `message()` body as a `description != null` test. That
deferred null check is the tell: the classifier fully resolves this at build time, and
generated code carrying a defensive guard over a decided fact reads as noise to a consumer
who has never seen the generator. With the sealed slot each `message()` arm emits either
`return "...";` or `return thr.getMessage();` with no test, and every consumer's handling is
compile-checked.

### 2. Reject `description:` on a VALIDATION handler at validate time

`ValidationHandler` does not carry a `ClientMessage` at all, and a `description:` alongside
`handler: VALIDATION` becomes an author-facing rejection. The site is
`TypeBuilder.parseErrorHandler`'s VALIDATION arm, whose `disallowed` list already rejects
`className` / `sqlState` / `code` / `matches` on the same entry; `description` joins that
list and inherits its mechanism.

Not its message text, though. That arm's single shared tail reads "validation runs as a wrapper
pre-execution step against jakarta.validation.Validator; SQL discriminators do not apply", which
explains the four existing entries and explains nothing about a rejected `description`; inheriting
it verbatim would tell an author their description was refused because SQL discriminators do not
apply. Extend the tail so it covers both reasons, and name
the constraint annotation's own `message` attribute as the place that authoring surface lives,
which is what "Settled questions" says the rejection is for. One message covering five fields is
still one message; the point is that the prose has to be true of whichever subset fired.

That is a *structural* rejection, not a typed arm with an LSP code. The two are different
mechanisms and only one of them lives at the lift. Every per-handler rule in
`parseErrorHandler` appends prose to `rejectReasons`, and the type comes back as
`UnclassifiedType(..., Rejection.structural("@error type rejected: ..."))`. The typed family
with `lspCode()`, `ErrorChannelWalkerError`, is raised by the `OutcomeType` classification
and `ErrorChannelWalker`: a later pass, keyed on channels rather than on `@error` types, and
its javadoc names those two as its raisers. Routing this rejection there would mean either
deferring a per-entry SDL-shape rule to a channel-grained pass or widening that family's
documented raiser set, for a rule whose four siblings sit untyped one line away. Take the
sibling shape. If the per-handler rules are worth promoting to typed arms, that is one
change covering all five, not a carve-out for this one. Rationale for rejecting rather than
honouring or ignoring is under "Settled questions" below.

### 3. Mint the `Mapping[]` per `@error` type, and compose the channel arrays from them

`description:` is authored at the `@error` type's handler entry, so it is definition-keyed.
The existing `Mapping[]` constants are keyed on `ErrorChannel.mappingsConstantName()`, a
use-site coordinate naming which fetcher's payload. Reading a definition-keyed value off a
use-keyed constant is what forces the overlap, and the confusion is already producing an
artifact: `description` sits in the *channel dedup identity*, so two channels with identical
dispatch behaviour but different author descriptions are split into separate constants even
though `description` does not participate in dispatch at all.

That identity is spelled twice, and only one of the two spellings produces the split.
`MappingsConstantNameDedup.handlerLine` appends `description` to each handler's fingerprint
line, `canonicalHash` digests those lines, and a differing digest is what mints the
`_A1B2C3D4` suffix that splits the constant name. `ErrorMappingsClassGenerator.HandlerKey`
carries `description` too, but only feeds `sameHandlerShape`, an internal sanity check run
across channels that already share a name. Dropping `description` from `HandlerKey` alone
changes nothing an author can see; the dedup pass is the site that has to change, and the
two have to stay in agreement or `sameHandlerShape` starts throwing its internal-bug
`IllegalStateException`.

Mint one `Mapping[]` per `@error` type instead, and derive each channel constant as the
ordered concatenation of the per-type arrays it maps. `ErrorChannel.mappedErrorTypes()`
already carries exactly that list in exactly that order, so the derivation is total, and the
entries are the `ErrorIndex` fixed point's records (`BuildContext.detectErrorsFieldShape`
resolves each union member through `errors.forName`), so an `@error` type name determines its
array content. One row population, read at two grains. `description` then drops out of both
dedup spellings on its own.

Membership for the per-type grain is `schema.types()`, not the channel walk
`ErrorMappingsClassGenerator.generate` does today. An `@error` type that no channel maps is a
live shape rather than a hypothetical: every `GraphitronSchemaBuilderTest.ErrorTypeCase` fixture
returns its `@error` type straight off `Query` instead of through a payload's errors slot, and
`TypeUnitCommands.fetchersRows` mints a `FetchersUnit` for every `ErrorType` in `schema.types()`
which `TypeFetcherGenerator` then renders unconditionally. The fetchers class therefore exists
for an unmapped type, and move 4's `message()` body would name a constant a channel-keyed mint
never emits, which is invalid generated Java rather than a diagnostic. Mint per `ErrorType`,
deriving each array from that type's own `handlers()`; channel reach is not a precondition,
which is the same fact as the content being name-determined. Emitting the channel-side
concatenation needs a form too, since Java array initializers do not concatenate: a private
varargs helper on `ErrorMappings` is the obvious one.

Both grains land in one `ErrorMappings` class, so say what names the per-type constants. The
channel-keyed ones are `SCREAMING_SNAKE` of an SDL outcome type name
(`ErrorChannelWalker.toScreamingSnake`), a payload class simple name (the private
`FieldBuilder.toScreamingSnake`), or a wrapper SDL type name on the `LocalContext` arm
(`BuildContext.toScreamingSnake`); the obvious per-type spelling is `SCREAMING_SNAKE` of the
`@error` type name, which shares that namespace.
Two SDL type names cannot collide, but a payload class simple name and an `@error` type name can
(a `com.example.NotAllowed` payload class alongside an `@error type NotAllowed` mints
`NOT_ALLOWED` twice), and a duplicate field is invalid generated Java rather than a diagnostic.
Either disambiguate the per-type names by construction (a suffix, or a nested holder class) or
fail loudly on a clash; pick one in the plan rather than at the keyboard.

*Opportunity, the author's call:* with content determined by type name, the channel constant's
identity reduces to the ordered list of mapped `@error` type names, so the whole per-handler
fingerprint is redundant, not just its `description` field. `canonicalHash` could digest the name
list (or the pass could key on the list directly and drop the digest), and `sameHandlerShape`
could compare name lists. That shrinks `handlerLine` and `HandlerKey` out of existence rather than
editing four arms each, and it is the same edit site this move already opens. Not required for the
defect; noted because move 3 is where it would be cheapest.

This changes emitted constant *names*: two channels that today differ only by author
description get one shared bare constant where they previously got a bare name plus a
hash-suffixed sibling. That is the artifact being removed, so the rename is the fix rather
than a side effect. Nothing in the suite observes it today, though, which is the point:
`MappingsConstantNameDedupTest` never varies `description`, so the change lands silently
unless the pipeline assertion below is written for it.

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
hand-spelling `outputPackage + ".schema"` there would add one more copy of a formula already
open-coded at 37 sites across `graphitron/src/main`, six of them in the error family alone
(`ErrorRouterClassGenerator.noChannelRouterCall` and the `clientException` lookup below it,
`ErrorMappingsClassGenerator.generate`, and `ChannelCatchArmEmitter`'s `errorRouterClass` /
`errorMappingsClass` / `errorListClass`). Minting removes the error family's copies rather than
adding a seventh. Sweeping the other 31 is not this item's business; do not widen into it.

### 5. Make the two manual pages agree

`docs/manual/reference/directives/error.adoc` becomes true as written, plus an explicit
sentence on the VALIDATION rejection, which is not inferable from the directive signature.
The how-to's "captured but currently unused" section and its matching pitfall bullet come
out, replaced by the resolution order and by the one thing that stays true: the *other*
fields on the error type still read off the live exception.

"True as written" has to mean the whole page, not just the `description` row of its `ErrorHandler`
field table. Four other statements are already false against the tree, three of them in the same
five-row table this move edits, so leaving them would make the move's own success criterion unmet:

* The `handler` row says `DATABASE` "matches `org.jooq.exception.DataAccessException` (or a
  configured subclass)". `TypeBuilder`'s DATABASE arm lifts a no-discriminator entry to
  `ExceptionHandler("java.sql.SQLException")`, and `SqlStateHandler` / `VendorCodeHandler` match any
  `java.sql.SQLException` in the cause chain. No `@error` path names `DataAccessException`, and
  nothing makes the base class configurable.
* The `className` row says it "defaults to `org.jooq.exception.DataAccessException` for `DATABASE`".
  The DATABASE arm rejects `className` outright, so there is no default to state.
* The same row says `className` is "ignored for `VALIDATION`". That arm's `disallowed` list rejects
  it.
* Outside the table, the "Semantics" bullet at line 129 says "`code:` and `sqlState:` may be
  combined; both must match on the exception". Rule 3 in `parseErrorHandler` rejects a `DATABASE`
  entry carrying both outright ("cannot carry both 'sqlState' and 'code'"), so this promises the
  author a build that fails on the arrangement the page recommends.

The `VALIDATION` row, the `DATABASE` default, and the combined-discriminator bullet matter more than
the `handler` row: they promise an author a build that fails. The `handler` row reads as harmless
because jOOQ wraps the driver's `SQLException` as its cause and the matcher walks the chain, so the
documented class routes anyway right up until someone relies on the documented `className:`.

Two further repeats of that same `DataAccessException` slip sit off the table and go with it, since
they are the same fact restated: the `className` comment in the directive signature block ("defaults
to `org.jooq.exception.DataAccessException` for `DATABASE`"), and the "Semantics" bullet claiming a
no-discriminator `DATABASE` routes "every `DataAccessException` on the channel" when the arm lifts to
`ExceptionHandler("java.sql.SQLException")`. Both are one-phrase edits.

Correcting these in a file already open is smaller than a second pass over the same page; the facts
above are the whole change, so no re-derivation is needed. Nothing else on the page is in scope, and
no behaviour changes here.

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

**What happens to the emitted `Mapping.description()` slot.** Decide this before writing move 4;
it changes the diff and the test list either way, and the item's own Problem section is about this
accessor having no reader. Move 1 rules out the runtime `description != null` test, so the fetcher
resolves each arm at build time, and the question is where the resolved `Static` string comes from:

1. *Read the mapping.* Emit `return ErrorMappings.FILM_LOOKUP_INVALID[0].description();` for a
   `Static` arm and `return thr.getMessage();` for `FromSource`. No runtime test (the arm chose the
   statement), the string stays interned once in the mappings constant, the accessor the Problem
   section calls unread becomes read, and `ErrorRouterClassGeneratorTest`'s three
   `description`-naming assertions stay green (the `Mapping` method set and `ExceptionMapping`'s
   field set are exact-set; `ExceptionMapping`'s method set is a `contains`).
   Costs an index-literal coupling between the fetcher and the array, and note the indices are the
   array's, not `et.handlers()`': `buildMappingArrayInitializer` skips `ValidationHandler`, so an
   `@error` type mixing VALIDATION with a dispatch handler shifts them.
2. *Inline the literal.* Emit `return "authored string";`. No index coupling, but then nothing reads
   the emitted `description()` and the slot has to go: the `Mapping` interface method, the field
   plus constructor parameter plus override on all three concrete mapping classes, and
   `ErrorMappingsClassGenerator`'s three `literalOrNull(...)` third arguments, which turns the
   emitted constructors into two-argument calls. That also rewrites the three
   `ErrorRouterClassGeneratorTest` assertions above and the two test method names that spell
   "Description".
3. *Keep it and read nothing.* Not a resolution. The slot would be written by the generator,
   documented by a freshly rewritten javadoc, and read by nobody, which is the artifact this item
   exists to remove, one layer down.

Option 1 is the recommendation: it is the smaller diff, it keeps one spelling of the string, and it
closes the "nothing calls that accessor" sentence literally. Whichever lands, the "Retired
vocabulary" list below covers the *javadoc text* on `Mapping.description()` only; under option 2 the
accessor itself is retired too and that bullet needs to say so.

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
* Pipeline tier: the VALIDATION rejection (an `UnclassifiedType` carrying the `@error type
  rejected: ...` structural rejection, asserted the way the four sibling rules on that arm
  are), the per-type/per-channel `Mapping[]` composition (that a channel array equals the
  concatenation of its types' arrays in declaration order), and that two channels differing
  only by author description now share one constant instead of splitting into a bare name
  plus a hash-suffixed sibling.
* Pipeline tier, an inversion rather than an addition: `GraphitronSchemaBuilderTest`'s
  `ErrorTypeCase.VALIDATION_LIFTS_TO_VALIDATION_HANDLER` feeds
  `{handler: VALIDATION, description: "input invalid"}` and asserts today that it lifts cleanly and
  that `h.description()` carries the string. Move 2 makes that same SDL a rejection, so the row's
  fixture loses its `description:` and its description assertion; the rejection gets its own row.
  Worth naming because this section's thesis is that every layer already has a passing test of its
  own shape: this is the one whose passing assertion states the behaviour being removed, and it is
  also the only `description()` call in the suite made through the `Handler` interface rather than
  through a variant, so it does not merely change verdict, it stops compiling. A second, purely
  mechanical compile break rides along: move 1 leaves `ValidationHandler` with no components at all,
  so the five `new ValidationHandler(Optional.empty())` sites lose their argument
  (`ErrorMappingsClassGeneratorTest` twice, `TypeFetcherGeneratorTest`,
  `CheckedExceptionMatcherTest`, `ErrorChannelWalkerTest`). Named so the arity change is expected
  rather than discovered.
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
* `description()` as an accessor name on the handler variants, if move 1 lands: the
  replacement is `clientMessage()` on the three dispatch records, and nothing at all on
  `ValidationHandler` or on the `Handler` interface.

## Out of scope

* The fact store already captures `description` (`GraphitronFactCapture` writes it to
  `graphitron_error_handler`), so the LSP surface needs no change at all: the VALIDATION
  rejection rides the existing `@error type rejected: ...` structural diagnostic, and mints
  no new wire code.
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
