---
id: R479
title: "Connection cursor decode crashes redact into a 500 instead of a clean error"
status: Backlog
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Connection cursor decode crashes redact into a 500 instead of a clean error

The emitted `ConnectionHelper.decodeCursor(String cursor, List<Field<?>> orderByColumns)` (`ConnectionHelperClassGenerator`, lines ~207-228) turns a client-supplied `after`/`before` cursor into `Field<?>[]` seek values with no guard on the wire input, so a malformed cursor throws a raw runtime exception that graphql-java's default handler redacts into a correlation-id 500 rather than a controlled, message-bearing error. This is inconsistent with the two sibling paths that already get it right: `pageRequest` in the same class throws `GraphitronClientException` for every bad pagination argument (arity, negativity, overflow) so the real message reaches the client (R415), and the single-record node-id decode path emits a clean `"Invalid node id \"...\" for this argument: not a valid <Type> id"` via `CompositeDecodeHelperRegistry.failureMessageExpr`. Cursor decode should reach the same bar.

## Failure modes (against current source)

1. **`Base64.getDecoder().decode(cursor)`** (line 218) throws `IllegalArgumentException` for any input that is not valid Base64 (bad alphabet, length not a multiple of 4, etc.). No try/catch anywhere on the delegate.
2. **`tokens[i]`** (lines 221/224) throws `ArrayIndexOutOfBoundsException` whenever the decoded value splits into *fewer* tokens than `orderByColumns.size()`. More tokens than columns is silently tolerated (the loop runs `i < orderByColumns.size()` and never reads the extras), which is why a cursor that over-splits survives while an under-split one crashes.
3. **`col.getDataType().convert(tokens[i])`** (line 224) can throw (`DataTypeException`) when a token is present but not coercible to the column type, e.g. a non-numeric token for a numeric key column. Not reproducible on a connection whose order-by columns are all strings (every string round-trips), but the path is unguarded on any connection whose order-by includes a non-string column.

All three surface to the client as a redacted 500. The node-id argument path already does this correctly: a bad `@nodeId` argument yields a controlled `DataFetchingException` carrying `"Invalid node id \"...\" for this argument: not a valid <Type> id"`. A malformed cursor is the same class of "client handed us an opaque token it should treat as opaque" error and deserves the same treatment: a `GraphitronClientException` with a message that says the cursor is invalid and never leaks decode/coercion internals.

## Not yet decided (Spec to settle)

- Exact message wording and whether the three failure modes collapse to one "invalid cursor" message or distinguish (recommend collapsing: a cursor is opaque to the client, so the specific decode failure is not actionable and only risks leaking internals).
- Whether the guard lives inside the emitted `decodeCursor` (wrap the body in try/catch → `GraphitronClientException`) or at the `pageRequest` call site (line 168). Inside `decodeCursor` keeps the arity/coercion knowledge next to the code that has it; the `pageRequest` guards are the local precedent.
- Test tier: an execution-tier test in `graphitron-sakila-example` driving a connection field with a garbage `after:` cursor and asserting a clean client-visible error (empty-of-internals message, no redacted 500), mirroring the node-id garbage-input tests. A connection with a non-string order-by column is needed to exercise failure mode 3.
