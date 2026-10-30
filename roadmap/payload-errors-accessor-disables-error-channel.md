---
id: R1133
title: "A @service payload class with its own errors accessor silently loses its @error channel"
status: Spec
bucket: bug
priority: 2
theme: error-channel
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A @service payload class with its own errors accessor silently loses its @error channel

## Goal

A `@service` field whose payload type lists `@error` types in its `errors` field routes a handled
exception through those types' handlers, and the payload's backing Java class (the class the
service returns) having its own `getErrors()` or `errors()` accessor changes nothing about that:
the channel always wins, the accessor is never read, and the manual says so. The defect as
reported (a clean build, every handled exception redacted) is not reproducible on trunk, where the
route that produced it no longer exists; what the item delivers is the pin that keeps it that way,
the manual rule a consumer migrating from Graphitron 9 needs, and one explicit decision about the
accessor, recorded below.

```graphql
type KregUgyldigInput implements Error
    @error(handlers: [{handler: GENERIC, className: "no.kreg.MeldingerException"}]) {
  path: [String!]!
  message: String!
}
type DokumentStatusResultat {
  status: String
  errors: [KregUgyldigInput!]     # the channel; the backing class's getErrors() is not consulted
}
type Query {
  dokumentStatus(id: ID!): DokumentStatusResultat
    @service(service: {className: "no.kreg.DokumentService", method: "status"})
}
```

A thrown `MeldingerException` arrives as `errors: [{message: ...}]` data, not as a redacted
top-level error. The terms: the *channel* is the carrier-side wiring that catches a thrown
exception and hands it to the payload's `errors` field, resolved from the SDL alone; an *outcome*
is the typed wrapper (`Outcome.Success` or `Outcome.ErrorList`) a channel-bearing fetcher returns
in place of the bare payload.

## What the code does today (verified)

* The channel is decided from the schema, never from the class.
  `FieldBuilder.resolveServiceOutcomeChannel` reads the payload type's own field definitions
  through `BuildContext.detectErrorsFieldShape` and runs `ErrorChannelWalker`; neither reflects on
  the backing class for an errors member. `HandlerAccessorCheck` reflects on the *error*
  classes, not the payload.
* `TypeFetcherGenerator.buildServiceFetcherCommon` forks on the resolved
  `Optional<ErrorChannel.Mapped>` only: present means `ChannelCatchArmEmitter.emit` (claim through
  `ErrorMappings`, else `ErrorRouter.surfaceClientErrorOrRedact`), absent means
  `noChannelCatchArm`. The redact-only catch the item reports is the channel-less arm.
* The errors field's fetcher is `FetcherEmitter.bind`'s `ChildField.ErrorsField` branch:
  `source instanceof Outcome.ErrorList<?> errorList ? errorList.errors() : null`. The success arm
  yields `null`. `FieldBuilder.liftToErrorsField` returns an `ErrorsField` when
  `isOutcome(parentType)` holds (a type an `@service` returns, at a root or below one, or an
  emitted-carrier payload); the classes `Transport` and `Transport.PayloadAccessor`, and the
  `PropertyDataFetcher.fetching(name)` registration the item names, are gone from
  `graphitron/src/main`. `ErrorMappings.ByType` is likewise absent; the constant is named per
  field through `TypeFetcherEmissionContext.mappingsConstant`.
* Existing coverage already has the shape: `SakPayload` (a record with an `errors` component) in
  `ErrorChannelClassificationTest`, and in `graphitron-sakila-example` the
  `submitSetterShapeFilmReview` fixture, whose backing class `SetterShapeFilmReviewPayload` has
  `getErrors()` and whose thrown `FilmReviewBadRatingException` arrives as a typed
  `FilmReviewBadRating` entry (`GraphQLQueryTest.submitSetterShapeFilmReview_invalidRating_routesThroughBadRatingErrorType`).
  None is named for the property, so a regression back to an accessor read would fail only by
  accident.
* Provenance correction: `origin/main` and the trunk are the same commit as this item's filing, and
  neither carries the `PayloadAccessor` arm, so the reporter's RC46 build (which has the
  `ErrorMappings.ByType` shape) is older than trunk.
* The sibling item that reifies the `PayloadAccessor` errors fetcher (`reify-error-payload-accessor-fetcher`)
  and the `PropertyDataFetcher` wording in `pin-defaultread-outcome-rejection` describe that arm
  and are stale for the same reason; neither overlaps this item, and both want a Discarded or
  rewrite pass of their own, not an edit here.

The one real consequence of the accessor being ignored: a service that returns a payload whose
`getErrors()` is already populated (the success path, not a throw) has that list dropped, because
the errors field resolves `null` on `Outcome.Success`. Nothing in the build says so.

## Decision: the channel wins, and there is no diagnostic

The accessor is not read, and the build does not warn about it.

* Channel-wins is what trunk already does and what `error-channel.adoc` and `result-types.adoc`
  promise ("Graphitron never constructs the payload class", "the payload class needs no errors
  slot"). Reading the accessor "when the channel has nothing" would reintroduce the accessor read
  the outcome wrapper retired, give one field two sources, and make the same schema behave
  differently by whether the success path set a list.
* A diagnostic would fire on every Graphitron 9 payload a consumer keeps as is, which is the
  common and harmless case (the accessor exists, nothing populates it). The reported failure, the
  handlers being unreachable, is false on trunk, so a diagnostic naming "unreachable handlers"
  would be wrong; the only true thing to say is "your accessor is unused", which is noise.
* The population that does lose data (a service hand-filling errors on success) is a service
  contract the manual can state in one sentence, with the supported spelling: throw, and let a
  handler claim it.

## Implementation

* `docs/manual/how-to/error-channel.adoc`: after the paragraph ending "The payload class needs no
  errors slot", add the rule. Wording:
  "If your payload class already has an errors accessor, for example a `getErrors()` kept from
  Graphitron 9, Graphitron does not read it. The `errors:` field is filled from the exceptions
  your `@error` handlers claim, and is `null` when none were thrown, whatever the accessor holds.
  An errors list your service returns on success is therefore dropped; to report a business error
  as data, throw it and let a handler claim it."
  Add the same sentence, trimmed to one line, to the "Pitfalls" list ("Unmatched flows redact"
  neighbours) as *An errors accessor on the payload class is ignored.*
* `docs/manual/how-to/result-types.adoc`: after the sentence "The class only has to expose the data
  fields the payload declares.", add: "An errors accessor it happens to carry is not read; see
  xref:error-channel.adoc[the errors channel]."
* Javadoc hygiene while there: the `SetterShapeFilmReviewPayload` class comment and the
  `submitSetterShapeFilmReview` test comments in `GraphQLQueryTest` still describe a
  "catch-arm payload-factory" in setter form that Graphitron no longer emits; reword them to the
  outcome wrapper so the fixture that carries the pin does not teach the retired mechanism.
* No generator change.

## Tests

Pipeline tier (`ErrorChannelClassificationTest`, unit tier by its annotation, SDL to classified
model): a named case `payloadBackingClassWithErrorsAccessor_stillPopulatesErrorChannel` that
builds the existing `SakPayload` fixture (record with an `errors` component) and a second
fixture whose backing class has a bean `getErrors()` (reuse `SetterShapeFilmReviewPayload` through
the sakila schema if the module boundary forbids a new stub), asserting
`errorChannel()` is present and the `Payload.errors` field classifies as `ChildField.ErrorsField`,
not a `RecordReadField`. This is the classification-level pin for "channel wins".

Execution tier (`graphitron-sakila-example`, `GraphQLQueryTest`, the canonical class named by
`testing.adoc`): two named cases against `submitSetterShapeFilmReview`, renaming nothing already
there. `..._backingClassHasErrorsAccessor_claimedThrowIsTypedData` is the existing
`invalidRating` assertion under the name that states the property. A new case
`..._accessorPopulatedOnSuccess_isNotRead` needs a service method returning
`SetterShapeFilmReviewPayload` with a non-empty `errors` list and a field using it (a new
`FilmReviewService` method and SDL field on the sakila schema, per "a shape a test needs and cannot
find is a method to write in the service module"), and asserts `errors` is `null` on the wire, which
pins the sentence added to the manual.

Build: this is a documentation plus test item; the verification build is the full
`mvn install -Plocal-db` because a test and fixture file outside `roadmap/` change.

## Other solutions we've considered

* *Build diagnostic naming the accessor.* Rejected above: warns on the benign common case, and its
  stated reason (unreachable handlers) is false on trunk.
* *Read the accessor when the channel has nothing.* Rejected above: two sources for one field and a
  schema whose behaviour depends on the service's success path.

## Open questions for the user

* The kreg port reproduced this on 10.0.0-RC46, whose generated code names `ErrorMappings.ByType`,
  which trunk does not have. Before this goes to Ready, someone should re-run kreg baseline cases
  17, 18, 25 and 27 against a trunk build. If they still redact there, the cause is a
  classification path this reading missed (the likeliest seam is
  `resolveServiceOutcomeChannel` answering `NoChannel` because `lookAheadVerdict` does not return a
  `ResultType` for the payload) and this item needs a repro schema, not just the pin.
* Is dropping a service-populated errors list acceptable, or should a populated success-path list
  surface? The recommendation is to keep it dropped and say so. The kreg port objects: its baseline
  case 27 is one thrown `MeldingerException` carrying four validation messages, which Graphitron 9
  rendered as four errors, each path suffixed with the message code
  (`{"message": "Saksbehandler mangler.", "path": ["annullerVgdok", "KM-1-055"]}`). Under channel-wins
  one exception yields one error with a fixed path, and the only v9-shaped workaround (the service
  returning one error per message on the success path) is the route this decision closes. Two ways
  out: (a) let one claimed exception produce several errors, for example an exception exposing a
  list of errors or a handler hook that fans out with a per-error path (the reporter's preference,
  kreg being the concrete consumer); (b) keep the decision and state in the error-channel manual's
  Graphitron 9 migration notes that fan-out plus path suffix has no v10 equivalent. This is a design
  fork for the author and user to settle before Ready; (a) may deserve its own item.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
