---
id: R170
title: "Sakila execute-tier fixture for the Jakarta ValidationHandler channel (R94-blocked)"
status: Backlog
bucket: testing
priority: 4
theme: mutations-errors
depends-on: []
created: 2026-05-16
last-updated: 2026-05-16
---

# Sakila execute-tier fixture for the Jakarta ValidationHandler channel (R94-blocked)

Split out from R12 (`error-handling-parity`)'s "Test fixture updates for
source-direct dispatch" bullet. R12 lifted `ValidationHandler` channel
support and the Jakarta pre-execution validation step into the per-fetcher
catch path. Execute-tier coverage is blocked on R94
(`emit-input-records`): the pre-step today calls
`validator.validate(env.getArgument(name))`, which receives a `Map<?, ?>`
(or a raw scalar); neither carries Jakarta annotations, so the validator
returns no violations and the wire path is a no-op end-to-end. R94 emits
each SDL `input` type as a graphitron-internal Java record under
`<outputPackage>.inputs`, coerces the map at the fetcher boundary, and
gives the validator a real annotated bean to inspect; once R94 lands this
fixture becomes a one-line addition to the sakila execute-tier driver.

Scope: an `@service` mutation whose input declares Jakarta constraints, an
`@error` type with `{handler: VALIDATION}`, and an execute-tier driver
covering the violation path (constraint produces a typed error via
`ConstraintViolations.toGraphQLError`) and the happy path. Pipeline-tier
coverage of the emit shape is already pinned in `TypeFetcherGeneratorTest`
and `FetcherPipelineTest`.
