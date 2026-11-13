---
id: R1183
title: "A @service nested-transaction failure that propagated to the router is reported once, not twice"
status: Backlog
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-11-13
last-updated: 2026-11-13
---

# A @service nested-transaction failure that propagated to the router is reported once, not twice

## Goal

On the owned-connection engine, a `@service` whose own `transaction(...)` / `transactionResult(...)` throws, with the exception propagating out of the service to `ErrorRouter`, gets one report for that one exception: a claimed `@error` payload with no top-level error beside it, or the router's redacted or surfaced error alone. The fixed "A nested transaction in this field failed; the field's writes were rolled back" error (`NestedScopeFailedException`) stays only for a nested failure the consumer's code caught, which is the silent-rollback trap the report exists for. The field still rolls back either way. Today the named error is added beside every propagated nested failure too, so an ordinary validation error claimed into a payload reaches clients as a top-level error and logs at ERROR.

## Reported by

The sis Graphitron 10 port (sis C46, RC47; the code is unchanged at trunk `f6403c10a`). 47 sis service classes behind 58 mutation fields wrap their writes in their own `ctx.transaction(...)` / `transactionResult(...)` and throw from inside the lambda (`EndreKullService`: `throw ErrorMapper.mapError(e)`, `FinnesIkkeException`, `IllegalArgumentException`), and an `@error` handler claims the exception into the payload (`UgyldigInput`, `FantIngenOppforingAaEndre`). The response carries the typed payload and the named error; v9 answered with the payload alone. The rollback matches v9. 13 sis fsapi-rest ITs fail on the extra error alone. sis asked that no item be filed on its side.

## Current behaviour

* `GraphitronTransactionProviderGenerator.markRollbackOnly` records `consumerFailure` and `cause` on the outermost scope when jOOQ rolls a joined consumer scope back, before anything knows whether the exception will be caught or claimed. Only the first consumer failure's cause is kept.
* `MutationFieldScopeEmitter.settleField` computes `caught` (the nested cause is not in the cause chain of any exception in `field.handled`) and `reported`, then the `if (nestedFailed)` arm returns a `NestedScopeFailedException` unconditionally, ahead of `return reported ? null : cause`.
* `GraphitronConnectionInstrumentationGenerator` adds `ErrorRouter.settleFailure(...)` for it every time; `caught` and `fieldReported` only pick the log message and whether the value is dropped (`!nested.fieldReported() || errorUnder`).
* `markFieldFailed` sets `field.failed` and appends to `field.handled` in the same call, so `!caught` implies `reported`: the condition sis proposes (`nestedFailed && !caught && reported`) reduces to `nestedFailed && !caught`.
* A failed *generated* scope already adds no second error, and a claimed generated failure keeps its payload with none (R1101's `aGeneratedWriteWhoseFailureAnErrorChannelClaims_keepsItsPayload_andAddsNoNestedFailureReport`). The proposal brings a propagated `@service` failure in line with that.

## The decision this revisits

R1101 settled the opposite deliberately. Review round 4 made the named error stand beside the field's other errors rather than be suppressed by them, motivated by PostgreSQL: a caught SQL failure aborts the transaction, the service's next statement fails with `25P02`, and that `25P02` is what reaches the router. Round 5 offered essentially this item's rule ("add it only when the recorded nested cause is not in the cause chain of an exception the router handled") and the user chose instead to keep the report in both cases with wording true in both. The `25P02` case still computes `caught == true` under this item (the propagating exception is the `25P02`, not the nested cause), so it keeps its report; what changes is only the case where the nested failure itself is what the router handled. The new evidence is the sis port's volume (58 fields, 13 ITs) and the client-visible effect on claimed validation errors.

## Fork to settle at Spec

* **Broad:** suppress whenever the nested cause propagated (`!caught`), whether the router claimed, redacted or surfaced it. One exception, one report; matches generated scopes. Changes the R1101 test pinning "uncaught: the router's redacted error and the named one beside it".
* **Narrow:** suppress only when the exception was claimed into a payload. `settleField` cannot tell a claim from a redaction today; `field.handled` would have to carry that.

## To check at Spec

* Settle's own `cause` (a timed-out tenant, a scope left open) rides on the report as a suppressed exception today; falling through to `return reported ? null : cause` drops it. Decide deliberately.
* Value handling when `settleField` returns `null`: the `else if (errorUnder)` arm must keep a claimed payload and null a redacted field, as `!fieldReported || errorUnder` does now.
* Exception identity: jOOQ rethrows a `RuntimeException` from `transactionResult` as is and wraps a checked one, so identity or the cause chain holds when the service throws inside the lambda. A service that catches outside the lambda and throws a mapped exception without chaining the original computes `caught == true` and keeps the report; pin a sis-shaped case and state the boundary in the docs.
* A service that swallows nested failure A and later lets B propagate: only A is recorded, so `caught` holds and the report stays. Conservative, and should be pinned.
* If `NestedScopeFailedException` is then only ever built with `caught == true`, its "propagated out of the field" message arm is dead: remove it and list it under Retired vocabulary.
* A `@service` on a Query field: check whether a propagated nested failure reaches the client as a second error by another route.
* Docs and tests: `error-channel.adoc` ("beside any payload"), the owned-path transactions section of `runtime-extension-points.adoc`, `service.adoc` transaction demarcation, the emitted javadoc in `MutationFieldScopeEmitter` and the class javadoc of `GraphitronConnectionInstrumentationGenerator`, a `changelog.md` line (consumer-visible), and the tests asserting the named error beside a claimed or redacted error (`OwnedBuildSettleSurfaceTest`, `TenantScatterSubstrateTest`, the sakila execution tests).
