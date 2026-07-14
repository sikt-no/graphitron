---
id: R479
title: "Connection cursor decode crashes redact into a 500 instead of a clean error"
status: Spec
bucket: bug
priority: 4
theme: pagination
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Connection cursor decode crashes redact into a 500 instead of a clean error

The emitted `ConnectionHelper.decodeCursor(String cursor, List<Field<?>> orderByColumns)` (emitted by `ConnectionHelperClassGenerator`, the `decodeCursor` MethodSpec) turns a client-supplied `after`/`before` cursor into `Field<?>[]` seek values with no guard on the wire input, so a malformed cursor throws a raw runtime exception that the no-channel disposition redacts into a correlation-id 500 rather than a controlled, message-bearing error. This is inconsistent with the two sibling paths that already get it right: `pageRequest` in the same class throws `GraphitronClientException` for every bad pagination argument (mutual exclusion, negativity, overflow) so the real message reaches the client (R415), and the single-record node-id decode path emits a clean `"Invalid node id \"...\" for this argument: not a valid <Type> id"` via `CompositeDecodeHelperRegistry.failureMessageExpr`. Cursor decode should reach the same bar. This is the exact gap the R415 changelog entry named as "out of scope, filed nowhere yet: malformed `after`/`before` cursor redaction (same family, different surface)".

## Failure modes (against current source)

1. **`Base64.getDecoder().decode(cursor)`** throws `IllegalArgumentException` for any input that is not valid Base64 (bad alphabet, length not a multiple of 4, etc.). No try/catch anywhere on the delegate.
2. **`tokens[i]`** throws `ArrayIndexOutOfBoundsException` whenever the decoded value splits into *fewer* tokens than `orderByColumns.size()`. More tokens than columns is silently tolerated (the loop runs `i < orderByColumns.size()` and never reads the extras), which is why a cursor that over-splits survives while an under-split one crashes.
3. **`col.getDataType().convert(tokens[i])`** can throw jOOQ's `DataTypeException` when a token is present but not coercible to the column type, e.g. a non-numeric token for a numeric key column. Not reproducible on a connection whose order-by columns are all strings (every string round-trips), but the path is unguarded on any connection whose order-by includes a non-string column.

All three surface to the client as a redacted 500. A malformed cursor is the same class of "client handed us an opaque token it should treat as opaque" error as a malformed node id and deserves the same treatment: a `GraphitronClientException` with a message that says the cursor is invalid and never leaks decode/coercion internals.

## Decisions

**One collapsed message for every decode failure.** A cursor is opaque to the client; which decode step failed is not actionable and distinguishing only risks leaking internals (column types, converter messages). Exact message:

```
cursor is not valid (was: "<echo>")
```

following R415's `(was: ...)` convention and the node-id path's echo-the-bad-input convention. `<echo>` is the raw cursor string capped at 100 characters with a trailing `…` when truncated: the cursor is unbounded client input, and while graphql-java JSON-escapes the message (no response-injection vector), echoing a multi-megabyte token back into the errors array and every log line that copies it is pure waste; the cap is one ternary in the emitted method. (The node-id path echoes uncapped; retrofitting it is out of scope.)

**The guard lives inside the emitted `decodeCursor`.** The null fast-path (no cursor → `DSL.noField` per column) stays untouched. The decode body (Base64 decode, split, token loop) is wrapped in one try with a multi-catch of exactly the two documented client-input failure signals:

```java
try {
    String[] tokens = new String(Base64.getDecoder().decode(cursor), UTF_8).split("\u0000", -1);
    if (tokens.length != orderByColumns.size())
        throw new IllegalArgumentException("cursor arity mismatch");
    for (int i = 0; i < orderByColumns.size(); i++) { /* existing NUL-sentinel / convert loop */ }
    return seekFields;
} catch (IllegalArgumentException | DataTypeException e) {
    // echo = cursor capped at 100 chars, "…" appended when truncated
    throw new GraphitronClientException("cursor is not valid (was: \"" + echo + "\")");
}
```

The narrow multi-catch (not a blanket `RuntimeException`) is deliberate blame classification at the boundary: `IllegalArgumentException` (Base64, arity) and `DataTypeException` (jOOQ's documented not-coercible signal) are pure functions of client input and collapse to the client error, while any other unchecked throw from `convert` (e.g. an NPE out of a buggy custom jOOQ `Converter`) is a genuine server fault and keeps propagating to the redacted 500 with its correlation id intact. `GraphitronClientException` is never thrown inside the try, so there is no self-catch and exactly one construction site for the client error. The `pageRequest`-call-site alternative (which knows `backward` and could name `after` vs `before` in the message) is rejected: a client passes exactly one cursor argument so naming it is marginal, and an in-method guard automatically covers any future decode step.

**Arity is strict in both directions.** `tokens.length != orderByColumns.size()` rejects, which makes over-split cursors (extra tokens, today silently tolerated) invalid too. Rationale is encode/decode symmetry: `encodeCursor` emits exactly N NUL-joined tokens and PostgreSQL strings cannot contain NUL (the encoding contract in the class javadoc), so a legitimate cursor always splits back to exactly N; any other arity is a forged, corrupted, or stale-across-schema-change token, never one this generator emitted. The decoder becomes the exact adapter for what its composer produces.

**No router or fetcher-emitter changes.** `pageRequest` is `decodeCursor`'s only caller in generated code, and every connection flavour funnels through `pageRequest`: standard, dynamic-ordering, and the polymorphic path (`MultiTablePolymorphicEmitter` builds its JSONB sort key and then calls the same `pageRequest`). R415 already unified the no-channel disposition (`ErrorRouterClassGenerator.noChannelRouterCall` → `surfaceClientErrorOrRedact`) on both sync and async catch arms, so the marker surfaces on root and nested (DataLoader-based) connections alike with zero disposition work here.

## Implementation

- `ConnectionHelperClassGenerator`: reshape the `decodeCursor` MethodSpec per the sketch above. `GraphitronClientException` is already imported for the `pageRequest` guards; add a `ClassName` for `org.jooq.exception.DataTypeException`. The class-level `@SuppressWarnings({"deprecation", "removal"})` and the deprecated `DataType.convert` call are unchanged (that migration is a separate concern, tracked by the comment above the annotation).
- Update the two prose surfaces that describe decode behaviour: the class-level javadoc's cursor-encoding paragraph ("Decoding splits on `\u0000` ...") gains the rejection contract, and the `decodeCursor` generator comment likewise.

## Tests

Execution tier only, in `graphitron-sakila-example` `GraphQLQueryTest`, as a block next to the R415 pagination-guard tests. The observable behaviour (clean client error vs. redacted 500) exists only at runtime; the only lower-tier assertion available would be a code-string pin on the emitted method body, which the development principles ban. This adds no model shape, so no pipeline-tier test is owed. Each test asserts the exact collapsed message and `doesNotContain("An error occurred. Reference:")`, mirroring `filmsConnection_negativeFirst_surfacesClientError`:

1. **Mode 1, bad Base64:** `filmsConnection(first: 2, after: "not-base64!!!")`.
2. **Mode 3, non-coercible token:** `filmsConnection` with `after:` set to `Base64.encode("abc")`: one token for the one order-by column `FILM_ID`, whose numeric type makes `convert("abc")` throw `DataTypeException`. (`filmsConnection` genuinely exercises the coerce path; a string column would round-trip silently.)
3. **Mode 2, under-split:** `filmsByRateDescTitleAsc` (two-column ordering `RENTAL_RATE`, `TITLE`) with `after:` set to `Base64.encode` of a NUL-free string: one token against two columns.
4. **Over-split strictness (new behaviour):** `filmsConnection` with `after:` set to `Base64.encode("1\u00002")`: two tokens against one column, rejected where it was previously tolerated.
5. **`before` variant:** `filmsConnection(last: 2, before: <garbage>)`: covers the `cursor = backward ? before : after` selection feeding the same guard.

Existing round-trip tests (`filmsConnection_withAfterCursor_returnsNextPage`, `filmsConnection_backward_withBeforeCursor_returnsPrevPage`, the `addressOccupantsConnection`/`projectItemsConnection` cursor tests) pin that legitimate cursors are unaffected. Not covered and deliberately so: a buggy custom `Converter` throwing a non-`DataTypeException` still redacts (the blame-classification decision above); sakila has no broken converter to drive that path and building one would test the absence of a catch clause.

## Out of scope

- R476: `totalCount` failures bypassing the redaction contract (sibling bug, different delegate).
- Capping the node-id path's uncapped echo of bad input (parity cleanup, file separately if wanted).
- A configurable maximum page size (DoS policy cap, already named out of scope by R415).
