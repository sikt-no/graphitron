---
id: R1108
title: "The user manual documents the owned-connection engine as the primary path"
status: Backlog
bucket: docs
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# The user manual documents the owned-connection engine as the primary path

## Goal

A consumer who reads the user manual learns to wire graphitron the way the project recommends: the *owned-connection engine*, where graphitron pins one connection per GraphQL operation, mounts the caller's identity on it, and owns its transactions (`Graphitron.runtime(dataSource, dialect)`, `runtime.newGraphQL(schema)`, `Graphitron.newOwnedExecutionInput(...)`). Today the manual's runtime pages describe only the *escape hatch*, where the consumer passes its own `DSLContext` per request (`Graphitron.newExecutionInput(dsl, ...)`). The owned engine, session identity, a rejected identity's `SessionMountException`, and the `graphitron-jakarta-rest` adapter are documented only under `docs/architecture/` or in javadoc. After this item the manual states the current behaviour of connection handling, including what R1097 shipped, and gives the owned path one consumer-facing home that later runtime changes (R1101's per-field transaction scope among them) extend rather than work around.

## Findings

Audited on 2026-10-27 against trunk at `89cde0450`. R1097 and R1055 were Done, and R1101 was In Progress with no code landed.

### Out of date now

* **`manual/reference/runtime-api.adoc` predates the owned-connection engine.**
  * The `Graphitron` facade listing shows only `buildSchema`, while `GraphitronFacadeGenerator` also emits `runtime(...)`, `newGraphQL(...)` and `newOwnedExecutionInput(...)`.
  * `GraphitronContext` is described as "populated by `Graphitron.newExecutionInput(...)`".
  * The `getDslContext` listing shows a one-line `GraphQLContext` read. The emitted method (`GraphitronContextInterfaceGenerator`) routes through the per-operation connection carrier (`TenantConnections`) and falls back to a supplied `DSLContext`.
  * "Three hand-written-feeling classes" undercounts what is emitted.
  * The page never links to `architecture/reference/runtime-extension-points.adoc`, the only place the owned path is described.
* **The same escape-hatch-only framing elsewhere.** `quick-start.adoc` ("the emitted `buildSchema` / `newExecutionInput` surface"), `manual/explanation/how-it-works.adoc` (the fetcher reads the `DSLContext` that `newExecutionInput(dsl, ...)` populated), and `manual/explanation/batching-model.adoc`.
* **`manual/how-to/test-your-schema.adoc` teaches testing through the escape hatch only** (`GraphQL.newGraphQL(schema)` plus `newExecutionInput(dsl, ...)`). Tests written this way never exercise identity mounting or graphitron's transaction boundaries, so they diverge from production on exactly the behaviour R1097 and R1101 change. It needs an owned-path variant, built over a `DataSource` the way `graphitron-sakila-example`'s `SakilaGraphitronApplication` wires it.
* **`manual/reference/mojo-configuration.adoc` § The seam parameter**: "A throwing `mount` evicts the connection ... and fails the request before any of your query's SQL runs."
  * It should name `SessionMountException` and link `runtime-extension-points.adoc#rejected-mount`.
  * "Before any of your query's SQL runs" is not true across tenants: on a multi-tenant mutation, a field routed to a tenant whose mount succeeded may already have committed. `tenant-scoping.adoc` already states this correctly.
* **`graphitron-jakarta-rest` has no manual page.**
  * The `GraphitronApplication` seams (`newExecutionInput`, `engineBuilder`, `newReference`, `redactedMessage`, and R1097's `clientFault`) are documented in javadoc and `runtime-extension-points.adoc` only.
  * The same holds for the `ChainedInstrumentation` advice: a later `.instrumentation(...)` on the engine builder silently replaces graphitron's connection instrumentation.
  * `manual/how-to/error-channel.adoc` is the only manual page that mentions the module. It says a fault can occur "when `newExecutionInput` opens a connection", but on the owned path no connection is opened until a field needs one.
* **The MCP `execute` tool's rollback promise.** `manual/how-to/mcp-agent-context.adoc` says "Mutations always roll back" and exempts only query-path writes.
  * Read from the code, not run: the dev executor's `ROLLBACK_ONLY` operation transaction opens only when something reaches `GraphitronTransactionProvider.begin`, and the pinned connection is otherwise held in autocommit.
  * So a `@service` mutation that writes without opening its own `transactionResult`, as the operation's first mutation field, would persist.
  * `DevExecuteExecutionTest` has no `@service` mutation case.
  * Confirm with a test before deciding: the remedy is a documented caveat if R1101 has not landed, and nothing if it has, since R1101's field scope opens under `ROLLBACK_ONLY` too.

### Goes stale when R1101 lands, missing from R1101's Documentation list

R1101 already lists `service.adoc`, `mutation.adoc`, `error-channel.adoc`, `runtime-extension-points.adoc` and `mojo-configuration.adoc`. Two more pages make claims its field scope changes:

* `manual/reference/directives/routine.adoc`: "two statements with a transaction boundary between them", the post-commit re-read, and "a read error in statement 2 cannot undo the already-committed write". Under R1101 the re-read runs inside the field's transaction, and a failure rolls the write back.
* `manual/how-to/mcp-agent-context.adoc`: the rollback guarantee wording above.

### Current, no change needed

`architecture/reference/runtime-extension-points.adoc` (owned path, hook state contract, rejected identity, per-tenant routing), `manual/how-to/tenant-scoping.adoc` (the rejected-mount fan-out exception and its committed-writes caveat), and `security.adoc`. R1055 changed no consumer-documented behaviour.

## Open question for the Spec

Where the consumer-facing owned-path documentation lives. `runtime-extension-points.adoc` sits under `docs/architecture/`, the contributor docs, but it is written for consumers and is where R1097 put its reference and where R1101 plans its own. Two shapes:

* Move its consumer-facing sections into a manual page, a reference beside `runtime-api.adoc` or a how-to for wiring the engine, and leave the architecture page the contributor rationale.
* Keep it where it is and link it prominently from the manual.

Recommendation: move. The manual's readers arrive from search and should not have to cross into contributor docs to wire the engine. R1101's documentation would then target the manual page.

## Sequencing

Not a hard dependency on R1101. The rewrite of `runtime-api.adoc` and the new jakarta-rest page are independent of it, but the transaction story is best written once. Either land this after R1101 phase 1, or land it first and leave the per-field transaction section to R1101. If the page move above is chosen and lands first, R1101's Documentation section is retargeted in the same commit.
