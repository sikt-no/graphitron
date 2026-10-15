---
id: R1046
title: "An @error-channel fetcher redacts a GraphitronClientException no mapping claims, where a channel-less fetcher surfaces it"
status: Ready
bucket: bug
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An @error-channel fetcher redacts a GraphitronClientException no mapping claims, where a channel-less fetcher surfaces it

## Goal

A `GraphitronClientException` that no `@error` mapping claims reaches the client the same way whether or not the fetcher's payload declares an `@error` channel: as a top-level error carrying its own message, with only true internal faults redacted to a correlation id. Today the outcome depends on the payload's shape. A channel-less fetcher ends in `ErrorRouter.surfaceClientErrorOrRedact`, which walks the cause chain and surfaces the client exception. A channel fetcher runs its mapping loop and, on no match, falls through to `ErrorRouter.redact`: the generated `return ErrorRouter.redact(e, env)` at the end of `ErrorDispatchFragments.outcomeArm`, and the "Unmatched: redact" arm of the generated `ErrorRouter.dispatch` (`ErrorRouterClassGenerator.buildDispatchMethod`). So "Tenant 'X' is not permitted for this request." reaches a query client verbatim and a mutation client as "An error occurred. Reference: <uuid>". Authored mappings still win; the change only decides what happens when none claims the exception.

Two mutations from the multi-tenant Sakila example schema (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`) make the minimal pair. Both are tenant-routed UPDATEs over the same input; only the second returns a payload with an `@error` channel (a payload field typed as a union of `@error` types, into which matched exceptions are placed as data):

```graphql
type Mutation {
    updateFilmByNodeId(in: UpdateFilmByNodeIdInput!): Film @mutation(typeName: UPDATE, table: "film")
    retitleFilmsChecked(in: [UpdateFilmByNodeIdInput!]!): FilmsRetitledPayload @mutation(typeName: UPDATE, table: "film")
}

type FilmsRetitledPayload {
    films: [Film]
    errors: [FilmWriteError]
}
type FilmWriteDbError @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
union FilmWriteError = FilmWriteDbError
```

Send each an id naming tenant 2 under a request whose tenant set is `[1]`. Today `updateFilmByNodeId` answers `errors: [{message: "Tenant '2' is not permitted for this request."}]` and `retitleFilmsChecked` answers `errors: [{message: "An error occurred. Reference: <uuid>."}]`, because its only handler is `DATABASE` and nothing claims the client exception. After this item both answer with the tenant message, `retitleFilmsChecked: null`, and no ERROR log line. A `DATABASE` failure on `retitleFilmsChecked` still lands in `errors` as a `FilmWriteDbError`, and an unclaimed `IllegalStateException` still redacts.

The fallback swap also removes the misleading log line. `redact` logs at ERROR ("Unmatched exception in fetcher"), while `surfaceClientErrorOrRedact` returns a client error without entering `redact`, so a client mistake stops being logged as an unmatched fault. That is not separate work.

## Evidence

Reported from sis against trunk `e4cfef7c1`. `aktiverStudieprogram(input: [{id: <Studieprogram id with INSTITUSJONSNR_EIER 9999>}])`, with 9999 outside the request's tenant set, logs `Unmatched exception in fetcher; correlation id = ...: ...GraphitronClientException: Tenant '9999' is not permitted for this request.` and returns `errors: [{message: "An error occurred. Reference: ...", classification: DataFetchingException}]`. The same tenant check on a query (`studieprogramkull(eierOrganisasjonskode: "1233")`) surfaces the message. Six sis mutations hit it: `aktiverStudieprogram`, `deaktiverStudieprogram`, `endreStudieprogram`, `endreStudieprogramGodkjenningOgRapportering`, `endreStudieprogramPublisering`, `endreStudieprogramKvalifikasjon`. Graphitron v9 put the equivalent message into the payload as `UgyldigInput` on mutations and as a top-level error on queries.

## Related

- R1036 is a sibling, not the same bug: there the wrong exception class (`GraphqlErrorException`) reaches the right fallback; here the right class reaches the wrong fallback.
- sis plans to keep v9's in-payload shape with a `GENERIC` `@error` handler whose `className` is the generated `GraphitronClientException`. That is fragile today: `TypeBuilder.validateExceptionClass` gates the name through `ClasspathNameability` and `Class.forName` on the codegen loader, and nothing recognises a class the same run emits. It resolves only when an earlier compile left the class in the module's own `target/classes`, so a clean checkout or `mvn clean` rejects the handler. Making the generated client exception a first-class match target is R1047. In the meantime sis matches `graphql.GraphqlErrorException`, its graphql-java superclass, with a GENERIC handler, which holds on a clean build. For those fetchers the R1046 fallback never fires, so the change must leave authored matches ahead of the fallback.

## Implementation

Background for the reader: `GraphitronClientException` is a class graphitron generates into the consumer's schema package; it extends `graphql.GraphqlErrorException`, so it is itself a `GraphQLError`, and the generated code throws it for client mistakes (a tenant outside the request's set, a malformed or wrong-type node id, a bad page size or cursor). `ErrorRouter` is the generated class every fetcher's catch arm calls. An `@error` channel has two transports, the two arms of the sealed `ErrorChannel`: `ErrorChannel.Mapped`, the `Outcome`-wrapped transport root `@service`, DML `@mutation` and `@routine` payload carriers ride, whose catch arm is inlined into the fetcher by `ErrorDispatchFragments.outcomeArm`; and `ErrorChannel.PayloadClass`, the developer-payload-class transport, whose catch arm calls the generated `ErrorRouter.dispatch`. These are the only two places a channel fetcher falls through when no mapping matches (a grep for `redact(` across `graphitron/src/main` finds no third), and both change the same way: the fall-through becomes the no-channel disposition.

- `ErrorDispatchFragments.outcomeArm`: replace the trailing `.add("return $T.redact(e, env);\n", errorRouterClass)` with `.add(redactArm(errorRouterClass, "e"))`, so the unmatched tail is spelled by the same fragment the no-channel arm uses (`ErrorRouter.surfaceClientErrorOrRedact(e, env)`). `ChannelCatchArmEmitter` and `RoutineWriteFetcherRenderer` both reach `outcomeArm`, so neither needs editing. The return type checks: `surfaceClientErrorOrRedact` is generic on `P` like `redact`, and the arm's target type is `DataFetcherResult<Outcome<X>>`, which infers `P = Outcome<X>`.
- `ErrorRouterClassGenerator.buildDispatchMethod`: the "Unmatched" tail becomes `return surfaceClientErrorOrRedact(thrown, env)`, with its comment renamed to match. Both the sync `dispatchCatchArm` and the async `asyncRouterCall` in `TypeFetcherGenerator` call `dispatch`, so this one edit covers both.
- Order is unchanged and is what keeps authored mappings winning: the mapping loop (every mapping against every cause, outermost first) runs to exhaustion before the fall-through is consulted, so a handler whose `className` matches the client exception or one of its superclasses still claims it into the payload.
- `ErrorRouter.redact` stays public and keeps its body; after the change only `surfaceClientErrorOrRedact` calls it. Narrowing its visibility touches the generated API for no gain this item needs.
- Javadoc that describes the old fall-through, in the files this item touches: the emitted javadoc on `dispatch` ("Falls through to {@link #redact} on no match") and on `surfaceClientErrorOrRedact` (which calls itself the no-channel counterpart and anticipates a future channel lift; it is now the unmatched disposition for every fetcher), the `ErrorRouter` class javadoc, the `outcomeArm` javadoc, and the `TypeFetcherGenerator.catchArm` javadoc, which still says an absent channel emits `ErrorRouter.redact`.

## Tests

- **Execution tier, the `Mapped` arm, which is the reported shape.** In `TenantDivinedRoutingExecutionTest`, beside `dmlPayload_onTheErrorChannel_aDatabaseErrorRendersTheTypedError`: `retitleFilmsChecked` with a film id naming tenant 2, executed under the request tenant set `List.of(1)`. Assert the response's top-level errors carry `"'2' is not permitted for this request"`, none matches `An error occurred. Reference:`, `retitleFilmsChecked` is `null`, and `TENANT_2_OPENED` is zero. A second case on `rateFilmsChecked` (the root `@service` producer on the same transport) under the same set pins the service half of `Mapped`. These two tests are the item's acceptance evidence: the first is the sis report reproduced in the example schema.
- **Execution tier, the `PayloadClass` arm.** No example-schema field on this transport can be made to throw a `GraphitronClientException` from client input, since its throw sites are the services' own code (`filmLookup`'s `FilmLookupService`) and the generated class is not nameable from `graphitron-sakila-service`. Instead call the generated `ErrorRouter.dispatch` directly from a sakila-example test, where the generated `ErrorRouter` and `GraphitronClientException` are on the test classpath: a `RuntimeException` wrapping a `GraphitronClientException`, a mapping array that does not match it, and a payload factory that fails the test if invoked. Assert the result has `data == null` and the client exception as its single error. A second call with an unwrapped `IllegalStateException` needs a `DataFetchingEnvironment` for `GraphqlErrorBuilder.newError(env)`; if building one is heavier than the assertion is worth, the generator-tier pin below covers that branch's unchanged body.
- **Generator tier.** `ErrorRouterClassGeneratorTest.dispatch_unmatchedFallsThroughToRedact` becomes `dispatch_unmatchedFallsThroughToSurfaceClientErrorOrRedact`, asserting `return surfaceClientErrorOrRedact(thrown, env)`. Its current `contains("redact(thrown, env)")` fails against the new text (the match is case-sensitive and the new call spells `OrRedact`), so replace it with the full statement and assert the body no longer contains `return redact(thrown, env)`. The matching pin on `ErrorDispatchFragments.outcomeArm`'s output already exists: `ChannelCatchArmEmitterTest.emit_mappedChannel_walksMappingsAndReturnsErrorList` asserts "unmapped fall-through stays a redact" with `contains("redact(e, env)")`, which fails on the new tail for the same reason; it becomes `ErrorRouter.surfaceClientErrorOrRedact(e, env)`.

## User documentation

`docs/manual/how-to/error-channel.adoc` documents the old behaviour in three places, and all three change in this item:

- § Handler dispatch: the code block's last line becomes `return surfaceClientErrorOrRedact(thrown, env);`.
- § "Unmatched exceptions: redact and correlate" gains an opening paragraph ahead of the `redactBody` excerpt, draft:

  > When no handler matches, the router first walks the exception's cause chain for a `GraphitronClientException`, the error graphitron itself raises for a client mistake: a tenant outside the request's tenant set, a malformed or wrong-type node id, an out-of-range page size or an invalid cursor. If it finds one, that error reaches the response's top-level `errors` with its own message and the field resolves to `null`, exactly as on a field without an error channel; nothing is logged. A handler you declare still wins, because handlers are tried first. Every other unmatched exception runs the redact path:

- § Pitfalls, "Unmatched flows redact": qualify it to "Unmatched flows redact, except graphitron's own client errors", keeping the advice to check the redaction log for a correlation id.

The first-client check reads simply: the rule is one sentence ("unclaimed client errors look the same with or without a channel"), and it removes a difference the manual never documented.

## Other solutions we've considered

- **Put the unclaimed client exception into the payload's `errors` list**, the shape Graphitron 9 gave mutations. The list's element type is a union of the author's `@error` types, and the union's type resolver picks a member by the source's class; with no member declared for the client exception there is nothing to resolve it to. Getting there means making the generated class a first-class match target, which is R1047, not a fallback change.
- **Implicitly append a client-exception mapping to every channel's table.** The same objection: a mapping places the throwable into the payload, and the union still has no member for it.
- **Surface every unmatched `GraphQLError`, not only `GraphitronClientException`.** That widens what reaches clients past the marker type the no-channel disposition settled on, on both transports at once, and an author's exception that happens to implement `GraphQLError` was never promised to surface. R1036 fixes its throw site to raise the marker type instead, which keeps the surface narrow.
