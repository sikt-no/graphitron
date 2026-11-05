---
id: R1153
title: "Execution tests that identity never crosses requests through a real pool"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Execution tests that identity never crosses requests through a real pool

## Goal

The claim "identity never crosses requests" is pinned by an execution test that would fail if it stopped holding, end to end, through a real connection pool. Today it is covered piecewise: the generator tier checks mount/unmount/evict ordering against fake connections, and `SessionHookExecutionTest` checks the example's identity routines on a single raw connection (mount scopes reads, unmount clears to empty, a throwing mount evicts). Nothing sends two users through the engine and a pool and checks what the second one sees. When this lands, a consumer asking "do you test that `app.claims` cannot leak between requests?" gets a yes that points at a test.

## Plan sketch

Execution tier, in `graphitron-sakila-example`, against the existing RLS probe table keyed on `app.user_id`:

- A real pool (HikariCP, `maximumPoolSize=1`, so both requests are guaranteed the same physical connection) behind `Graphitron.runtime(...)`.
- Request A as alice reads her rows; request B as bob, and request B with an anonymous mount, see none of alice's rows.
- After each request, borrow the physical connection directly and assert `current_setting('app.user_id', true)` reads the empty string.
- The same three assertions after each failure path: a fetcher that throws, a mutation field that rolls back, a settle failure, a rejected mount, and a cancelled execution.

## Context

Found in an adversarial review of the owned-connection session lifecycle. Related items: R1151 (mount-only `SessionState`), R1152 (reset at release), R1154 (post-release acquisition in the single-tenant carrier); each of those should add its case to this suite rather than to a separate test.

