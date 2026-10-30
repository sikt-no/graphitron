---
id: R1133
title: "A @service payload class with its own errors accessor silently loses its @error channel"
status: Backlog
bucket: bug
priority: 2
theme: error-channel
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A @service payload class with its own errors accessor silently loses its @error channel

## Goal

A `@service` payload whose `errors` field lists an `@error` type routes a handled exception
through that type's handlers whether or not the payload's backing class has its own `errors`
accessor, or the build says, at the field's coordinate, that the accessor wins and the handlers
cannot be reached. Today the build is clean and every handled exception is redacted.

## The defect

kreg's payloads (`VgdokKvitteringsresultat`, `DokumentStatusResultat`, records in kreg-service)
declare `errors: [KregUgyldigInput!]`, where `KregUgyldigInput implements Error @error(handlers:
[{handler: GENERIC, className: "...MeldingerException"}, ...])`, and the backing classes keep a
`getErrors()` from Graphitron 9, where the error strategy filled the payload. The build emits
`ErrorMappings.ByType.KREG_UGYLDIG_INPUT` but no fetcher dispatches through it:
`QueryFetchers.dokumentStatus`, `MutationFetchers.annullerVgdok` and `importerVgdok` catch with a
plain `ErrorRouter.surfaceClientErrorOrRedact(e, env)`, and `VgdokKvitteringsresultatFetchers.errors`
is a property read of `getErrors()`. So the client gets "An error occurred. Reference: ..." where
the schema advertises an error channel. `error-channel.adoc` says the payload class needs no errors
slot, but not that having one turns the channel off.

The property read is the `Transport.PayloadAccessor` route that R304 (`reify-error-payload-accessor-fetcher`)
would reify; that item changes how the read is emitted, not whether the channel is chosen, so it
does not cover this.

## Direction

The Spec chooses between the channel winning (the accessor is ignored, or read only when the
channel has nothing) and a build diagnostic naming the coordinate, the accessor and the unreachable
handlers. Either way the manual states the rule. Pinned in kreg by its baseline cases 17, 18, 25
and 27 (Graphitron 9 payload errors, Graphitron 10 a redacted top-level error).

A wrong answer on a valid schema, so a regression ahead of planned work.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
