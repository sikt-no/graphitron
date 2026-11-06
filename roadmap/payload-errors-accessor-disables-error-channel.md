---
id: R1133
title: "A single @error type as the errors field's element is not recognised as an error channel"
status: Spec
bucket: bug
priority: 2
theme: error-channel
depends-on: []
created: 2026-10-30
last-updated: 2026-11-06
---

# A single @error type as the errors field's element is not recognised as an error channel

## Goal

A `@service` field whose payload type lists `@error` types in its `errors` field routes a handled
exception through those types' handlers, and the payload's backing Java class (the class the
service returns) having its own `getErrors()` or `errors()` accessor changes nothing about that:
the channel always wins, the accessor is never read, and the manual says so. The errors field's
element may be a single `@error` type as well as a union or interface of them, as
`error-channel.adoc` ("Carrier shapes: union vs single type") already promises. Today a single
`@error` type is not recognised as a carrier at all: the build is clean, no channel forms, and every
handled exception is redacted. That is the defect; the accessor the item was filed under is a
symptom of it, not its cause. The fix holds for every producer family that carries an errors
channel (a `@service` field, a payload-returning DML `@mutation`, a payload-returning `@routine`
write), since the manual's promise is not `@service`-specific.

```graphql
type KregUgyldigInput
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
top-level error, with `errors:` typed by the single `@error` type exactly as written above. The terms: the *channel* is the carrier-side wiring that catches a thrown
exception and hands it to the payload's `errors` field, resolved from the SDL alone; an *outcome*
is the typed wrapper (`Outcome.Success` or `Outcome.ErrorList`) a channel-bearing fetcher returns
in place of the bare payload.

## What the code does today (verified)

* The single-type carrier is refused, silently. `BuildContext.detectErrorsFieldShape` returns a
  carrier only when the errors field's element resolves to a `ReturnTypeRef.PolymorphicReturnType`
  (a union, or an interface's implementations) whose members are all `@error` types; a single
  `@error` object type resolves otherwise and the method returns `null`, so the field is classified
  as a plain data field and the fetcher takes `noChannelCatchArm`. `FieldBuilder.liftToErrorsField`
  mirrors the same rule. The manual documents both shapes as equivalent.
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
  `graphitron/src/main`. `ErrorMappings.ByType` is present on trunk
  (`ErrorMappingsClassGenerator`, `BY_TYPE_HOLDER`), beside the per-field constants named through
  `TypeFetcherEmissionContext.mappingsConstant`; an earlier reading of this item said otherwise.
* Existing coverage already has the shape: `SakPayload` (a record with an `errors` component) in
  `ErrorChannelClassificationTest`, and in `graphitron-sakila-example` the
  `submitSetterShapeFilmReview` fixture, whose backing class `SetterShapeFilmReviewPayload` has
  `getErrors()` and whose thrown `FilmReviewBadRatingException` arrives as a typed
  `FilmReviewBadRating` entry (`GraphQLQueryTest.submitSetterShapeFilmReview_invalidRating_routesThroughBadRatingErrorType`).
  None is named for the property, so a regression back to an accessor read would fail only by
  accident.
* Reproduced on trunk. The kreg port re-ran its 30 recorded v9 requests against a trunk snapshot
  (`claude/graphitron-rewrite` at `06d71b62b`, 2026-11-03) and got the same result as on 10.0.0-RC46
  and RC47: cases 17, 18, 25 and 27 redact. Its variants locate the cause:
  ** as written, `errors: [KregUgyldigInput!]`, a single `@error` type: no channel, the catch is
     `ErrorRouter.surfaceClientErrorOrRedact`, and `errors` reads the payload's `getErrors()`;
  ** the payload's `getErrors()` removed: the build fails with "no accessor ... matched SDL field",
     so `errors` was classified as a plain data field all along;
  ** `errors: [KregStatusFeil!]` with `union KregStatusFeil = KregUgyldigInput`, the accessor kept:
     the channel forms, the fetcher returns `Outcome<...>` claimed through `ErrorMappings`, `errors`
     reads `Outcome.ErrorList`, and the handled exception arrives as data (v9's case 18 shape). So
     the channel already wins over the accessor;
  ** the single type without `implements Error`: no channel, as written. `implements Error` is
     kreg's own interface and plays no part: `ErrorIndex` holds whatever `classifyType` classifies
     as an `ErrorType`, which keys off the `@error` directive alone.
  The union is not a workaround for a consumer: it changes the client contract, and existing
  `errors { path message }` selections fail validation against the union.
* The fact store states the same rule a second time, and emission reads the store's copy. The
  view `graphitron_errors_field_rule` fills `graphitron_errors_field` (one row per errors field,
  anchored by `Fetchers`) only for a nullable list whose element is a container in
  `graphql_poly_member` whose every member carries `@error`; its `container_name` is documented as
  "the union or interface the list holds". `graphitron_producer_outcome` holds a foreign key into
  it. `no.sikt.graphitron.emit.ErrorChannelRelation` reads both: the outcome type each producer
  catches into, and each outcome type's members in match order, by joining `graphql_poly_member` on
  `container_name`. `ErrorMappingsClassGenerator` emits one dispatch constant per outcome type from
  that read, and `TypeFetcherEmissionContext.mappingsConstant` throws "the walk and the store have
  drifted" when the walk gave a field a channel the store states no outcome for. The errors-field
  entry defects (`ERRORS_FIELD_MIXED_MEMBERS`, `ERRORS_FIELD_NOT_A_LIST`, `ERRORS_FIELD_NON_NULL`)
  are derived in the store as well, from fields whose element is a container with an `@error`
  member. `ErrorChannelRelation` is the only reader of `container_name`; every other reader
  (`graphitron_producer_outcome_rule`, the DML and routine carrier derivations, the
  `ERRORS_IN_A_ROW` and `MULTIPLE_ERRORS_FIELDS` defects, `OccurrenceSources`, the LSP's
  `CarrierDataField`) only asks whether a field has a row. `OutcomeAgreementTest` holds the walk
  and the store to the same producers and errors fields, and the fact document
  `graphitron-model/src/test/resources/facts/defects/producer-outcome.graphqls` pins today's rule
  outright: its `DirectPayload { text: String, errors: [FilmInvalid] }` is "the value's own field".
  So a classifier-only fix would pass classification and then fail generation at
  `mappingsConstant`.
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
  common and harmless case (the accessor exists, nothing populates it). The handlers the item was
  filed over are unreachable because of the single-type carrier, not the accessor, so once the
  carrier is fixed the only true thing to say about the accessor is "it is unused", which is noise.
* The population that does lose data (a service hand-filling errors on success) is a service
  contract the manual can state in one sentence, with the supported spelling: throw, and let a
  handler claim it.
* One claimed exception yields one error, and this item does not change that. Graphitron 9 could
  fan one exception out into several errors, each path suffixed with a message code (kreg's case
  27: one `MeldingerException` carrying four validation messages rendered as four errors). Graphitron
  10 has no equivalent today, and the only v9-shaped workaround, a service returning one error per
  message on the success path, is the route the first bullet closes. The manual says so in a
  "Coming from Graphitron 9" note (below). Letting one exception produce several errors would change
  the handler and `ErrorRouter` contract and is out of scope here; settled with the user 2026-11-06,
  with no follow-up item filed yet.

## Implementation

* The carrier: one member-resolution rule, read by both classifier paths. Today the two paths
  each repeat the same union/interface switch, and the field-level path is reached only for a
  polymorphic return. A single `@error` object type resolves through
  `BuildContext.resolveReturnType` to `ReturnTypeRef.ScalarReturnType` (`TypeBuilder.lookAheadVerdict`
  returns an `ErrorType`, which none of that method's four arms takes), and every caller of
  `FieldBuilder.liftToErrorsField` gates on `instanceof ReturnTypeRef.PolymorphicReturnType` first:
  the DML, `@service` and `@routine` carrier blocks in `classifyChildFieldOnResultType`, that
  method's record-parent switch (whose `ScalarReturnType` arm goes to
  `recordReadFieldOrUnclassified`, the "no accessor ... matched SDL field" kreg hit), and
  `ServiceDirectiveResolver.resolve` before `classify`. So the predicate alone would leave the lift
  unreached. The change:
  ** A `BuildContext` helper resolves an errors-shaped element's candidate members from the
     element's SDL type: a union's members, an interface's implementations, or the type itself when
     it is an object type the `ErrorIndex` holds. `detectErrorsFieldShape` and `liftToErrorsField`
     both read it, replacing their two copies of the switch.
  ** `liftToErrorsField` takes the field's resolved `ReturnTypeRef` of any variant rather than a
     `PolymorphicReturnType`, and its callers call it whenever the helper finds `@error` members,
     not only on a polymorphic return: the five sites above change their gate, keeping their place
     in the classification order. The non-outcome fallback (a `RecordReadField` over the given
     return type, read with `ValueLocator.DefaultRead`) is unchanged in shape and now also covers
     the single type. That is deliberately the union's treatment, not the accessor-resolved read a
     single `@error` element gets today through `recordReadFieldOrUnclassified`: one rule for every
     errors-shaped field, whatever its element, so the two shapes stay equivalent as the manual
     says.
  ** The single type gets the union's precise rejections, not a fallback to a data field: a
     non-list `errors: X` gets "must be a list", and a non-null list `[X!]!` gets
     `ErrorChannelWalkerError.NonNullableErrorsField`. The mixed-members rejection cannot arise for
     one member.
  ** No new `ReturnTypeRef` variant. One would make every exhaustive switch over `ReturnTypeRef`
     take an arm that only the errors lift reads; the helper keeps the change inside the two methods
     whose javadoc already ties them together.

  Widening `detectErrorsFieldShape` widens every consumer: `BuildContext.scanStructuralPayload`
  (DML, `@service` and `@routine` emitted carriers), the DML DELETE errors-field pass in
  `GraphitronSchemaBuilder`, and `MutationInputResolver`. That is intended (see Goal).

* The store: the same widening, in the relation the walk's rule is a copy of. The grain stays
  "one row per errors field", the owner stays `Fetchers`' anchor of the rule view, and mark and
  sweep are unchanged, so this is a wider rule, not a new relation.
  ** `graphitron_errors_field_rule` admits a second arm beside the container arm: a nullable list
     whose element is an object type carrying `@error`. `container_name` holds the element type in
     both arms. Its column comment, the table comment, the view's column comment and the
     `meta_relation` row's grain sentence say so: "the type the list holds: a union or interface
     whose members are the `@error` types an error is matched to, or the one `@error` type itself".
  ** `ErrorChannelRelation.read` resolves members with a left join: a container's members in match
     order as today, or the element itself when `graphql_poly_member` has no row for it. Its javadoc
     states the single-type arm.
  ** The errors-field entry-defect arm also takes a field whose element is an object type carrying
     `@error`, so `errors: X` is `ERRORS_FIELD_NOT_A_LIST` and `[X!]!` is `ERRORS_FIELD_NON_NULL`,
     the store's halves of the walk's precise rejections. `ERRORS_FIELD_MIXED_MEMBERS` cannot arise
     for one type.
  ** The existence-only readers listed under "What the code does today" widen with the table and
     need no edit; that is the cross-family scope the Goal asks for, the LSP included.
  ** The walk keeps its own copy of the rule (the `BuildContext` helper above) rather than reading
     the store; the tree's guard against the two drifting is `OutcomeAgreementTest`, which gains a
     single-type producer (Tests). Moving the walk onto the store's answer is a larger change than
     this bug fix and is not proposed here.

  The rest of the channel (handler rules, the per-type `ErrorMappings.ByType`, the catch arm, the
  `Outcome` wrapper, the errors fetcher) works from a `List<ErrorType>` on the walk side and from
  `ErrorChannelRelation`'s member lists on the emission side, so with both widened it needs no
  change beyond what a one-member list exercises. No type resolver is registered for a single-type field, and none is needed:
  `GraphitronSchemaClassGenerator.isErrorOnlyPolymorphic` keys off `UnionType` and `InterfaceType`.
  `ErrorMappings.ByType` and the per-type `<ErrorType>Fetchers` are emitted per `ErrorType`
  whatever carries it.

* `docs/manual/how-to/error-channel.adoc`: after the paragraph ending "The payload class needs no
  errors slot", add the rule. Wording:
  "If your payload class already has an errors accessor, for example a `getErrors()` kept from
  Graphitron 9, Graphitron does not read it. The `errors:` field is filled from the exceptions
  your `@error` handlers claim, and is `null` when none were thrown, whatever the accessor holds.
  An errors list your service returns on success is therefore dropped; to report a business error
  as data, throw it and let a handler claim it."
  Add the same sentence, trimmed to one line, to the "Pitfalls" list ("Unmatched flows redact"
  neighbours) as *An errors accessor on the payload class is ignored.*
  Then, as the next paragraph, a note in the shape the page already uses for migration notes:
  "Coming from Graphitron 9: one exception the handlers claim becomes one entry in `errors:`, with
  the response path described under `path:` below. Graphitron 9 could split one exception into
  several errors, each with its own path suffix; Graphitron 10 has no equivalent. A service that
  needs several errors reported for one request has no supported way to do it yet."
* `docs/manual/how-to/result-types.adoc`: after the sentence "The class only has to expose the data
  fields the payload declares.", add: "An errors accessor it happens to carry is not read; see
  xref:error-channel.adoc[the errors channel]."
* Javadoc hygiene while there: the `SetterShapeFilmReviewPayload` and
  `FieldRenamedSetterShapeFilmReviewPayload` class comments, the comments over
  `submitSetterShapeFilmReview` and `submitFieldRenamedSetterShapeFilmReview` in the sakila
  `schema.graphqls`, and the `submitSetterShapeFilmReview` test comments in `GraphQLQueryTest`
  (including the one over the defaulted `title` slot) still describe a "catch-arm payload-factory"
  that Graphitron no longer emits; reword them to the outcome wrapper so the fixtures that carry the
  pins do not teach the retired mechanism. A grep for `payload-factory` across
  `graphitron-sakila-service` and `graphitron-sakila-example` finds them all.

## Tests

The carrier, first. Pipeline tier (`ErrorChannelClassificationTest`), named cases:

* `singleErrorTypeElement_isAnErrorsCarrier`: a `@service` payload whose `errors` field lists one
  `@error` type (no `implements` clause), asserting `errorChannel()` is present and the field
  classifies as `ChildField.ErrorsField`.
* `singleNonErrorObjectElement_staysADataField`: the negative, the same payload with the element a
  plain object type carrying no `@error`; no channel, and the field is not an `ErrorsField`.
* `singleErrorTypeElement_nonNullList_isRejected` and `singleErrorTypeElement_nonList_isRejected`:
  `[X!]!` classifies to an `UnclassifiedField` carrying `NonNullableErrorsField`, and `errors: X`
  to one whose reason says it must be a list, matching the union's existing cases.
* `singleErrorTypeElement_onDmlPayload_isAnErrorsCarrier`: a payload-returning DML `@mutation`
  whose payload's `errors` lists one `@error` type classifies the field as an `ErrorsField`, pinning
  the widening of `scanStructuralPayload` beyond `@service`.

Existing case to re-check, not rewrite: `GraphitronSchemaBuilderTest`'s
`DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED` already declares `errors: [ValidationErr]` over a single
`@error` type. After the fix that field is a carrier rather than a data-field candidate, so the
rejection must still come from `data: String`; confirm the assertion still holds for the reason its
description gives.

The store, against the walk. Pipeline tier, `OutcomeAgreementTest`: the fixture SDL gains a root
`@service` field returning a payload whose `errors` lists `SimpleErr` directly (beside the existing
`union CarrierError = SimpleErr`, which stays as the one-member-union case), and that producer joins
the `walkProducers` expectation, so the walk's channel and the store's `graphitron_producer_outcome`
row are compared for the single type too. Fact document,
`graphitron-model/src/test/resources/facts/defects/producer-outcome.graphqls`: the file description
states the widened rule; `DirectPayload`'s comment says a list of one `@error` type is an errors
field; `DirectPayload.errors` (container `FilmInvalid`) joins the `graphitron_errors_field` rows and
`Mutation.direct` joins the `graphitron_producer_outcome` rows; and two new types,
`SingleErrorTypePayload { text: String, error: FilmInvalid }` and
`StrictSingleErrorTypePayload { text: String, errors: [FilmInvalid]! }`, join the entry-defect
expectation as `ERRORS_FIELD_NOT_A_LIST` and `ERRORS_FIELD_NON_NULL`.

Second existing fixture to re-check: `graphitron-model/src/test/resources/corpus/error-type.graphqls`
declares `SakPayload { errors: [ExtraFieldError] }` under the `@service` field `Query.sak`, so it
becomes a carrier. `ExtraFieldError` has a `severity` field its `GENERIC` handler's
`java.lang.IllegalArgumentException` has no accessor for, which `HandlerAccessorCheck` reports once
a channel forms. If it does, change the fixture so it still says what its description says (the
`@error` type reaching the schema through a payload's errors slot), for example a handler class
that has the accessor; do not loosen the check.

Execution tier (`graphitron-sakila-example`, `GraphQLQueryTest`): a new Mutation field
`submitSingleErrorFilmReview(filmId: Int!, rating: Int!): SingleErrorFilmReviewPayload` over a new
`FilmReviewService.submitSingleError` method, with
`type SingleErrorFilmReviewPayload { reviewId: Int errors: [FilmReviewBadRating] }` reusing the
existing `FilmReviewBadRating` `@error` type and a new backing class `SingleErrorFilmReviewPayload`
in `graphitron-sakila-service` (data component `reviewId` only; the method delegates to the existing
`submit` validation so an invalid rating throws `FilmReviewBadRatingException`). The case
`submitSingleErrorFilmReview_invalidRating_isTypedDataUnderErrors` asserts the error arrives under
`errors` with its `message`, `reviewId` is null, and the response has no top-level error.
Behaviour, not generated text.

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

Build: the verification build is the full `mvn install -Plocal-db`, since generator code, tests,
fixtures and the manual change.

## Other solutions we've considered

* *Build diagnostic naming the accessor.* Rejected above: warns on the benign common case, and the
  unreachable handlers it would explain are the single-type carrier's, fixed at their cause.
* *Read the accessor when the channel has nothing.* Rejected above: two sources for one field and a
  schema whose behaviour depends on the service's success path.
* *A member relation in the store* (`graphitron_errors_field` plus a derived row per member, in
  match order), so `ErrorChannelRelation` reads members instead of joining `graphql_poly_member`
  and ordering them itself. It would state the match order once in the store, but it is a new
  relation (owner, grain, mark and sweep, registration) for a bug fix whose only member reader is
  that one query; the left join keeps the change inside the existing relation and its reader.
* *Renaming `container_name`.* After the fix it can hold a type that contains nothing. The column
  comment says what it holds; a rename would touch the reference docs, the fact documents and the
  reader for no change in meaning, and can be its own refactor if wanted.

## Open questions for the user

* Settled 2026-11-03: the trunk re-run happened, the cases still redact, and the cause is the
  single-type carrier above, not `lookAheadVerdict`. kreg's schema is the repro.
* Settled 2026-11-06, option (b): a service-populated errors list stays dropped, and the manual's
  "Coming from Graphitron 9" note says fan-out with a path suffix has no v10 equivalent (see the
  Decision and Implementation sections). Option (a) is not filed as an item for now. The question as
  it stood:
  Is dropping a service-populated errors list acceptable, or should a populated success-path list
  surface? The recommendation is to keep it dropped and say so. The kreg port objects: its baseline
  case 27 is one thrown `MeldingerException` carrying four validation messages, which Graphitron 9
  rendered as four errors, each path suffixed with the message code
  (`{"message": "Saksbehandler mangler.", "path": ["annullerVgdok", "KM-1-055"]}`). Under channel-wins
  one exception yields one error with a fixed path, and the only v9-shaped workaround (the service
  returning one error per message on the success path) is the route this decision closes. Two ways
  out: (a) let one claimed exception produce several errors, for example an exception exposing a
  list of errors or a handler hook that fans out with a per-error path (the reporter's preference,
  kreg being the concrete consumer); (b) keep the decision and state in the error-channel manual's
  Graphitron 9 migration notes that fan-out plus path suffix has no v10 equivalent.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.

## Reviewer findings

### Round 1: Spec → Ready (session_01FY5UvvUTnAY9rzFZp6gVWc, 2026-11-06)

Verdict: withhold. The goal reads cleanly and is viable: the defect is real and located where the
spec says. `BuildContext.detectErrorsFieldShape` admits only a `ReturnTypeRef.PolymorphicReturnType`,
the manual's "Carrier shapes: union vs single type" promises the single type, and channel-wins is
what the tree does. No sakila fixture uses a single `@error` list element today (checked every
`.graphqls` under `graphitron-sakila-example`), so nothing existing changes behaviour. One blocking
finding on the item's own open question, two on question two (fit), two smaller ones on the tests.

**Finding 1 (question one: viability). The open question is a design fork the spec itself says must
settle before Ready.** The fan-out question (kreg case 27: one exception, four errors, path suffixed
with the message code) decides whether the Decision section stands as written, and with it the manual
sentence "An errors list your service returns on success is therefore dropped". Option (a) also
reopens the `ErrorRouter` / handler contract, which is far larger than this item. Recommendation for
the author and user: take (b) here (state in the manual's Graphitron 9 migration notes that fan-out
with a path suffix has no v10 equivalent yet) and file (a) as its own Backlog item, so this item stays
a bug fix whose title and goal match its scope.

**Finding 2 (question two: fit). "`liftToErrorsField` takes the same rule" understates the classifier
change, and the seam needs naming.** A single `@error` object type resolves through
`BuildContext.resolveReturnType` to `ReturnTypeRef.ScalarReturnType`: `TypeBuilder.lookAheadVerdict`
returns an `ErrorType`, which is none of the four arms there. `liftToErrorsField` takes a
`PolymorphicReturnType` parameter, and every call site gates on that type before calling it:
`FieldBuilder` at the DML, `@service` and `@routine` carrier blocks and the record-parent switch's
`PolymorphicReturnType` arm, and `ServiceDirectiveResolver` before `classify`. A `ScalarReturnType`
goes to `recordReadFieldOrUnclassified` instead, which is the "no accessor ... matched SDL field" the
kreg variant hit. So fixing the predicate in `detectErrorsFieldShape` alone leaves the field-level
lift unreached. The spec should say which shape the fix extends, for example (i) an
`ErrorType` arm in `resolveReturnType` (a new `ReturnTypeRef` variant, which every exhaustive switch
over it then has to take), or (ii) one shared member-resolution helper (union members, interface
implementations, or the single `@error` object) used by both `detectErrorsFieldShape` and a
`liftToErrorsField` that accepts any `ReturnTypeRef`, with the call sites reached for the scalar case
too. (ii) keeps the change inside the two methods the javadoc already ties together; the author's
call. Either way the spec should also say what `liftToErrorsField`'s non-outcome fallback (a
`RecordReadField` over the given return type) and its precise rejections become for the single type:
a non-list `errors: X` and a non-null list `[X!]!` should get the same "must be a list" and
`NonNullableErrorsField` diagnostics the union gets, not silently fall back to a data field.

**Finding 3 (question two: fit). Widening `detectErrorsFieldShape` widens every carrier family, and
the Tests section covers only `@service`.** The predicate is also read by
`BuildContext.scanStructuralPayload` (DML, `@service` and `@routine` emitted carriers),
`GraphitronSchemaBuilder`'s DML DELETE errors-field pass, and `MutationInputResolver`. That is the
right outcome, since the manual's promise is not `@service`-specific, but the spec should state it and
pin one non-`@service` case (a DML payload with a single-type `errors` field classifies an
`ErrorsField`). The `GraphitronSchemaBuilderTest` case `DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED`
already uses `errors: [ValidationErr]` with a single `@error` type; after the fix its `errors` field is
a carrier rather than a data-field candidate, so the implementer should confirm the case still asserts
what its description says.

**Finding 4 (tests). The negative pipeline case is aimed at the wrong discriminator.** `implements
Error` plays no part in recognising an `@error` type: `ErrorIndex` is built from `classifyType`
verdicts, which key off the `@error` directive, and neither the code nor the manual names an `Error`
interface. After the fix, the same type without `implements Error` is a carrier too; the kreg fourth
variant showed "no channel" only because no single type forms one today. The negative should be a list
whose element is a single non-`@error` object type, still a plain field. Consider dropping
`implements Error` from the Goal's example so it does not read as a requirement.

**Finding 5 (tests, smaller).** The single-type execution test needs a new sakila SDL type, payload
and service method, as no fixture has the shape; name them the way the accessor-populated case does.
The type-resolver "check" can be a statement: `GraphitronSchemaClassGenerator.isErrorOnlyPolymorphic`
keys off `UnionType` / `InterfaceType`, so a single-type field registers none. The javadoc-hygiene
bullet misses `FieldRenamedSetterShapeFilmReviewPayload`, whose class comment carries the same
"catch-arm payload-factory" wording, and the `GraphQLQueryTest` comment near the `title` defaulted-slot
assertion.

Author response (session_01FY5UvvUTnAY9rzFZp6gVWc, 2026-11-06). This session reviewed round 1
and then, at the user's request, revised the plan, so it may not sign off the revision. Finding 1:
settled with the user as option (b), no item filed for (a); Decision and Implementation carry it.
Finding 2: Implementation names the seam (a shared member-resolution helper on `BuildContext`, the
five call sites' gate, the single type's precise rejections, no new `ReturnTypeRef` variant).
Finding 3: the Goal states the cross-family scope; Tests add a DML case and the re-check of
`DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED`. Finding 4: the negative is a plain object element, and
the Goal's example drops `implements Error`. Finding 5: the execution fixture is named, the
type-resolver check is a statement, and the hygiene list covers every `payload-factory` comment.

### Round 2: Spec → Ready (session_01FY5UvvDHUCucMtrxBma63e, 2026-11-06)

Verdict: withhold. The revision answers round 1 on the classifier side: the five gated call sites,
the single type resolving to `ReturnTypeRef.ScalarReturnType`, the shared member-resolution helper
and the cross-family scope all check out against trunk, and the goal is clear (a single `@error`
element becomes a carrier, as the manual promises; an errors accessor on the backing class is
ignored and the manual says so). Question one holds. Question two fails on one finding: the
carrier rule lives in two places, and the plan changes one.

**Finding 1 (question two: fit; it also bears on viability). The fact store states the carrier
rule too, emission reads the store's answer, and the plan leaves the store's rule as it is.**
`graphitron_errors_field` (filled from the view `graphitron_errors_field_rule` in
`graphitron-model.sql`) admits a field only when its element is a container in
`graphql_poly_member` whose every member is `@error`; `container_name` is `NOT NULL` and documented
as "the union or interface the list holds". `graphitron_producer_outcome` holds a foreign key into
it. `no.sikt.graphitron.emit.ErrorChannelRelation` reads both tables (producer to outcome type, and
outcome type to members by joining `graphql_poly_member` on `container_name`) and feeds
`ErrorMappingsClassGenerator`'s per-outcome dispatch constants and
`TypeFetcherEmissionContext.mappingsConstant`. That method throws "the walk and the store have
drifted" when the walk gave a field a channel and the store states no outcome for it. So under the
plan as written, the first single-type carrier passes classification and fails generation with a
generator-bug `IllegalStateException`; the sentence "the rest of the channel (handler rules,
`ErrorMappings`, the catch arm ...) already works from a `List<ErrorType>` and needs no change" does
not hold for the per-outcome constants. The errors-field entry defects (`ERRORS_FIELD_NOT_A_LIST`,
`ERRORS_FIELD_NON_NULL`, `ERRORS_FIELD_MIXED_MEMBERS`) are derived in the store as well, so the
"precise rejections" bullet has a store half.

Two tests already pin the current rule and will fail or need changing, and neither is named:
`OutcomeAgreementTest` (walk against store, whose SDL spells the single-member-union workaround,
`union CarrierError = SimpleErr`), and the fact document
`graphitron-model/src/test/resources/facts/defects/producer-outcome.graphqls`, whose description
states the union-or-interface rule and whose `DirectPayload { errors: [FilmInvalid] }` case says
the opposite of this item: "A list of one @error object rather than of a union or an interface:
the value's own field, as anywhere else."

What would satisfy it: the Implementation names the store-side change and how it is shaped. How
does `graphitron_errors_field` represent a single-type element (for example `container_name`
holding the `@error` type itself, with `ErrorChannelRelation`'s member read resolving a
non-container to itself; or a member relation of its own), and does that keep the relation's
grain, column comment and `NOT NULL`? That is a fact-store modeling decision (see
`docs/architecture/explanation/modeling-discipline.adoc`) and the author's to make. Then the plan
says whether the walk-side helper is a second copy of the store's rule or reads it, so the two
cannot drift again; updates `producer-outcome.graphqls` (the `DirectPayload` case and the file
description) and the entry-defect expectations for a single type; and adds a single-type producer to
`OutcomeAgreementTest`.

**Finding 2 (non-blocking, re-check list).** One more fixture changes verdict under the fix:
`graphitron-model/src/test/resources/corpus/error-type.graphqls` declares
`SakPayload { errors: [ExtraFieldError] }` under the `@service` field `Query.sak`, so it becomes a
carrier. Its `ExtraFieldError` carries a `severity` field that its `GENERIC` handler's
`java.lang.IllegalArgumentException` has no accessor for, which `HandlerAccessorCheck` reports once
a channel forms. List it beside `DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED`. Separately, the
non-outcome fallback the plan extends to the single type (`RecordReadField` with
`ValueLocator.DefaultRead(name)`) is weaker than what that field gets today through
`recordReadFieldOrUnclassified` (an accessor resolved against the backing class, `@field(name:)`
honoured); the union already has this, so it is consistent, but the plan should say it is meant.

Author response (session_01FY5UvvDHUCucMtrxBma63e, 2026-11-06). This session reviewed round 2
and then, at the user's request, revised the plan, so it may not sign off the revision. Finding 1:
"What the code does today" names the store's copy of the rule, its one member reader and the
existence-only readers; Implementation widens `graphitron_errors_field_rule` with a single-type arm
(same grain, owner, mark and sweep; `container_name` holds the element type), gives
`ErrorChannelRelation.read` a left join that resolves a non-container to itself, and widens the
errors-field entry-defect arm; the walk keeps its copy, held to the store by an extended
`OutcomeAgreementTest`. Tests update `producer-outcome.graphqls` and add two single-type defect
cases. A member relation and a column rename are recorded as considered. Finding 2: the corpus
fixture is on the re-check list, and the non-outcome fallback is stated as deliberate.
