---
id: R1046
title: "An @error-channel fetcher redacts a GraphitronClientException no mapping claims, where a channel-less fetcher surfaces it"
status: Backlog
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

The fallback swap also removes the misleading log line. `redact` logs at ERROR ("Unmatched exception in fetcher"), while `surfaceClientErrorOrRedact` returns a client error without entering `redact`, so a client mistake stops being logged as an unmatched fault. That is not separate work.

## Evidence

Reported from sis against trunk `e4cfef7c1`. `aktiverStudieprogram(input: [{id: <Studieprogram id with INSTITUSJONSNR_EIER 9999>}])`, with 9999 outside the request's tenant set, logs `Unmatched exception in fetcher; correlation id = ...: ...GraphitronClientException: Tenant '9999' is not permitted for this request.` and returns `errors: [{message: "An error occurred. Reference: ...", classification: DataFetchingException}]`. The same tenant check on a query (`studieprogramkull(eierOrganisasjonskode: "1233")`) surfaces the message. Six sis mutations hit it: `aktiverStudieprogram`, `deaktiverStudieprogram`, `endreStudieprogram`, `endreStudieprogramGodkjenningOgRapportering`, `endreStudieprogramPublisering`, `endreStudieprogramKvalifikasjon`. Graphitron v9 put the equivalent message into the payload as `UgyldigInput` on mutations and as a top-level error on queries.

## Related

- R1036 is a sibling, not the same bug: there the wrong exception class (`GraphqlErrorException`) reaches the right fallback; here the right class reaches the wrong fallback.
- sis plans to keep v9's in-payload shape with a `GENERIC` `@error` handler whose `className` is the generated `GraphitronClientException`. That is fragile today: `TypeBuilder.validateExceptionClass` gates the name through `ClasspathNameability` and `Class.forName` on the codegen loader, and nothing recognises a class the same run emits. It resolves only when an earlier compile left the class in the module's own `target/classes`, so a clean checkout or `mvn clean` rejects the handler. Making the generated client exception a first-class match target is R1047. In the meantime sis matches `graphql.GraphqlErrorException`, its graphql-java superclass, with a GENERIC handler, which holds on a clean build. For those fetchers the R1046 fallback never fires, so the change must leave authored matches ahead of the fallback.
