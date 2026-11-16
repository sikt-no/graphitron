---
id: R1193
title: "An @outbox(on: ERROR) tenant timeout is exercised end to end on the multi-tenant fixture"
status: Backlog
bucket: testing
priority: 7
theme: runtime-connection
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# An @outbox(on: ERROR) tenant timeout is exercised end to end on the multi-tenant fixture

## Goal

A multi-tenant execution test drives an `@outbox(on: ERROR)` field through GraphQL into a tenant timeout, with the field's worker still blocked on its own tenant connection, and shows the failure message written on a fresh connection while the old one is aborted at settle. R1113 (the `@outbox` directive) writes a failing field's `ERROR` messages after its transaction rolls back, on a fresh connection when the transaction itself cost the connection; that path is pinned today only by `OutboxFailureSettleTest`, on the emitted multi-tenant runtime over fake JDBC, because the multi-tenant fixture has no mutation that writes on a tenant and then fans out over the same tenant, so nothing can hold the worker on the field's own connection.

What it needs: in `multitenant.graphqls`, a tenant write carrying `@outbox(on: ERROR)` whose payload has a child fanning out over the same tenant; tenant databases with the outbox table and the mount routines; and a connection wrapper that stalls a statement on demand, so the timeout fires while the worker is still executing.
