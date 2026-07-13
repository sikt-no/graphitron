---
id: R170
title: "Sakila execute-tier fixture for the Jakarta ValidationHandler channel (R98-blocked)"
status: Backlog
bucket: testing
priority: 4
theme: testing
depends-on: [multi-source-input-validation]
created: 2026-05-16
last-updated: 2026-07-13
---

# Sakila execute-tier fixture for the Jakarta ValidationHandler channel (R98-blocked)

Split out from R12 (`error-handling-parity`)'s "Test fixture updates for
source-direct dispatch" bullet. R12 lifted `ValidationHandler` channel
support and the Jakarta pre-execution validation step into the per-fetcher
catch path.

The original blocker, R94 (`emit-input-records`), has shipped: each SDL
`input` type is now emitted as a graphitron-internal Java class under
`<outputPackage>.inputs`, the fetcher boundary coerces the incoming map via
`fromMap`, and the validator pre-step walks the typed instance instead of a
raw `Map`. What remains missing is constraint *content*: the emitted classes
carry no Jakarta annotations, so `validator.validate(<typed>)` still returns
no violations and the wire path is a no-op end-to-end. Attaching constraints
(SDL validation directives merged into a `ConstraintSet`, registered
programmatically via `ConstraintMapping`) is R98
(`multi-source-input-validation`). Per R94's Done note, this fixture picks up
the live invalid-input round-trip the moment R98 ships its first SDL
constraint; at that point it becomes a one-line addition to the sakila
execute-tier driver.

Scope: an `@service` mutation whose input declares constraints (via whatever
surface R98 lands), an `@error` type with `{handler: VALIDATION}`, and an
execute-tier driver covering the violation path (constraint produces a typed
error via `ConstraintViolations.toGraphQLError`) and the happy path.
Pipeline-tier coverage of the emit shape is already pinned in
`TypeFetcherGeneratorTest` and `FetcherPipelineTest`.
