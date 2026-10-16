---
id: R1055
title: "GraphitronTransactionProvider closes the enclosing scope when a begin fails"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# GraphitronTransactionProvider closes the enclosing scope when a begin fails

## Goal

A `begin` that fails on the emitted `GraphitronTransactionProvider` leaves graphitron's nesting bookkeeping intact for the rest of the GraphQL operation, the way R1054 makes a failed `commit` do. jOOQ 3.20.11's `DefaultDSLContext.transactionResult0` calls `provider.begin(ctx)` inside the same `try` as the lambda and the commit, so a `begin` that throws is followed by `provider.rollback(ctx)` for a scope that never opened. The emitted `begin` increments `depth` and pushes its savepoint only after the call that can fail, so that rollback closes the *enclosing* scope instead: a nested `begin` whose `setSavepoint()` fails (on Postgres, any nested `transaction` opened after service code caught a failed statement, SQLState `25P02`) runs `settle(true)` on the outer transaction, and the outer scope's own rollback then pops an empty stack and leaves `depth` at -1, so every later mutation field in the operation starts from the wrong depth. A failing top-level `setAutoCommit(false)` takes the same path from depth 0. Found while reviewing R1054, whose plan fixes only the commit side and leaves `begin` as it is.
