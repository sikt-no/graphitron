---
id: R1123
title: "The bulk UPDATE zero-row guard reports how many input rows matched nothing"
status: Ready
bucket: feature
priority: 5
theme: mutation-write
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# The bulk UPDATE zero-row guard reports how many input rows matched nothing

## Goal

When a list-input `@mutation(typeName: UPDATE)` that returns a payload has input rows whose key matches no row, the call still fails and rolls back as it does today, but it first runs every input row's statement and then throws one exception that says how many of the input rows matched nothing, out of how many, and at which positions. The exception is a generated class with accessors, so a consumer's `FetcherErrorResponse` (the hook that decides the client-facing text of a fetcher exception no `@error` handler caught) can tell the client "2 of 5 rows were not found" without parsing a message. Its message keeps the substring "matched zero rows" and the class stays an `IllegalStateException`, so every `@error` handler that catches the guard today still catches it.

The shape this changes, from the sakila example schema:

```graphql
type FilmsPayload {
    films: [Film!]
}

extend type Mutation {
    updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE)
}
```

```graphql
mutation {
  updateFilmsPayload(in: [
    { filmId: 1, title: "a" },
    { filmId: 999901, title: "b" },   # no such film
    { filmId: 2, title: "c" },
    { filmId: 999902, title: "d" },   # no such film
    { filmId: 3, title: "e" }
  ]) { films { filmId } }
}
```

Today the call stops at index 1 and throws an `IllegalStateException` naming only that index. After this item it runs all five statements, rolls back, and throws a `ZeroRowsMatchedException` whose `missCount()` is 2, `rowCount()` is 5 and `missedRowIndices()` is `[1, 3]`. What the client sees is unchanged unless the consumer's `FetcherErrorResponse.unmatchedMessage` or an `@error` handler reads the new accessors.

## Where it happens today

`TypeFetcherGenerator.buildBulkRecordTwoStepFetcher` is the shared skeleton for the payload-returning bulk DML fetchers: inside one `dsl.transactionResult(tx -> …)` it allocates `acc`, a typed `Result` of returned keys, loops `for (Map<?, ?> row : in)` and splices in a per-row body supplied through the `BulkPerRowBodyFn` seam, then returns `acc`. `buildMutationBulkDmlRecordFetcher` picks the per-row body by write arm; the UPDATE arm is `buildCarrierBulkPerRowUpdateBody`. Each row runs one `UPDATE … RETURNING <pk>` (or, for an empty patch, the key read) through `fetchOne()`, and on a `null` record throws:

```
IllegalStateException("@mutation(typeName: UPDATE) bulk row matched zero rows; key filter found no target for input row at index <i> of <Type.field>")
```

`<i>` is `acc.size()`, which equals the row index only because the loop stops at the first miss. Fail-fast is also what keeps the order-preservation invariant (`acc` position `i` is input row `i`, which the follow-up projection relies on): a loop that carried on past a miss would leave `acc` shorter than `in`. The message names the row by index and the field by coordinate and never echoes input content, so client data stays out of logs; `DmlBulkMutationsExecutionTest.updateFilmsPayload_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow` pins that.

The exception leaves the transaction lambda unwrapped (jOOQ rethrows a `RuntimeException` from `transactionResult` as itself), reaches the fetcher's catch arm, is tried against the payload's `@error` handlers (a `GENERIC` handler matches by `Class.isInstance` plus a `matches:` substring of the message), and, when none matches, goes to `ErrorRouter.redact`, which hands the same `thrown` to `FetcherErrorResponse.unmatchedMessage`.

## Design

**A generated, typed exception.** The fetcher throws `<outputPackage>.schema.ZeroRowsMatchedException`, generated once per run beside `GraphitronClientException`, because generated code may reference only its own output package, jOOQ and graphql-java: there is no runtime jar to put a hand-written class in. It extends `IllegalStateException`, so a `GENERIC` handler naming `java.lang.IllegalStateException` (or `RuntimeException`) keeps matching by `isInstance`. It is not a `GraphitronClientException`: a miss stays an unmatched fault that is redacted and logged with a reference, as today, because a key that matches nothing may be a row the current user is not allowed to see, and surfacing Graphitron's own text by default would change what 48 sis mutations show their clients.

The class carries:

* `String fieldCoordinate()`: the field, as `Mutation.updateFilmsPayload`.
* `int rowCount()`: the number of input rows.
* `List<Integer> missedRowIndices()`: zero-based input positions whose key matched no row, ascending, unmodifiable.
* `int missCount()`: `missedRowIndices().size()`.

Its single constructor takes `(String verb, String fieldCoordinate, List<Integer> missedRowIndices, int rowCount)` and builds the message itself, so the message shape is owned by one generated class rather than spelled at each throw site:

```
@mutation(typeName: UPDATE) bulk input matched zero rows for 2 of 5 input rows of Mutation.updateFilmsPayload; first at index 1
```

The message keeps "matched zero rows" (sis's `FantIngenOppforingAaEndre` handlers in 12 unions and its `SanertFeillogg` match on it) and still names the first missed index, so logs read as before. It lists no further indices, which keeps the line bounded on a large input; the accessor carries all of them. Indices and counts carry no input content. The class is verb-neutral (the `verb` argument renders into the message only) so the bulk DELETE guard, which sis tracks separately, can adopt it by switching its arm from `FailFast` to `CountThenThrow`; this item changes only UPDATE.

**The skeleton owns the miss.** All three per-row bodies the skeleton splices in already end the same way: they declare `rec` from a `fetchOne()` and append it to `acc`, and differ only in what a `null` means. That difference moves into the value the seam returns. `BulkPerRowBodyFn.build` returns `PerRow(CodeBlock statement, MissPolicy onMiss)`: `statement` is the body up to and including the `rec` declaration, with no `acc.add` and no miss branch; `MissPolicy` is sealed:

* `CannotMiss`: INSERT, whose `RETURNING` always yields a row. The skeleton emits a plain `acc.add(rec)`.
* `FailFast`: DELETE without `missingAsDeleted`. The skeleton emits today's throw, with the same message.
* `Echo(CodeBlock echo)`: DELETE with `missingAsDeleted`. The body supplies the echo expression (`missingRowEcho`, which needs the body's `whereChunk`); the skeleton emits `if (rec == null) rec = <echo>;` and the append.
* `CountThenThrow`: UPDATE. The skeleton emits `if (rec == null) misses.add(rowIndex); else acc.add(rec);`.

The skeleton iterates by index (`for (int rowIndex = 0; rowIndex < in.size(); rowIndex++) { Map<?, ?> row = in.get(rowIndex); … }`) and, when any row's policy is `CountThenThrow`, declares `List<Integer> misses` before the loop and emits, between the loop and `return acc;`:

```java
if (!misses.isEmpty()) {
    throw new ZeroRowsMatchedException("UPDATE", "Mutation.updateFilmsPayload", misses, in.size());
}
```

The verb in the messages (`UPDATE`, `DELETE`) comes from the write arm the skeleton is called for, which it already receives through `buildMutationBulkDmlRecordFetcher`'s switch; the policies carry no verb string.

This makes the order-preservation invariant structural rather than a promise each body keeps. The skeleton is the only code that appends to `acc`, so every row either appends or takes its policy's miss branch; under `CountThenThrow` the only `return acc;` sits after the throw, which fires exactly when some row appended nothing, so a skewed `acc` has no path out of the lambda. The policy travels in the same value as the body it describes, so an UPDATE body cannot be paired with the wrong guard at the call site, and no body refers to a name only the skeleton declares beyond the `row` and `txd` it already reads. The DELETE fail-fast message reads the index off `rowIndex` instead of `acc.size()` (the same value under fail-fast), so the loop has one source for the row index. INSERT's and DELETE's emitted behaviour is unchanged.

**What still fails fast.** Only a key that matches no row is deferred. Anything that throws inside a row (a constraint violation, a type mismatch, a row-level-security error, the per-row cross-partition agreement check) still aborts the loop at that row and propagates as today, so a real error in a later row wins over the miss count and the call fails with that error. An `UPDATE` that matches zero rows is not an SQL error, so the transaction is still usable for the remaining rows' statements on every dialect the arm admits.

**Cost.** A failing call now runs every row's statement before rolling back, one round trip per row, where today it stops at the first miss. That is what Graphitron 9 did, it is bounded by the input size, and it only costs on a call that fails.

**Scope.** Only the payload-returning bulk UPDATE (the `MutationBulkDmlRecordField` UPDATE arm built by `buildMutationBulkDmlRecordFetcher`). The bulk UPDATE that returns `@table` rows or ids directly (`buildBulkUpdateFetcher`, one PostgreSQL `UPDATE … FROM (VALUES …)` statement, `KeyStatement.ValuesJoinUpdate`) has no zero-row guard: a missed key just writes and returns nothing, so the list is shorter than the input. That is its documented contract, pinned by `DmlBulkMutationsExecutionTest.updateFilms_missedRowShrinksPayloadToWrittenRows`; giving it a guard would turn calls that succeed today into failures, and R242 (positional input/output alignment for DML payloads) owns what a miss there should render. Single-row UPDATE (a miss renders the payload `null`) and the bulk DELETE guard are out of scope.

## Implementation

* `graphitron/.../generators/schema/ZeroRowsMatchedExceptionClassGenerator.java` (new): renders the class above, modelled on `GraphitronClientExceptionClassGenerator`: `CLASS_NAME`, `generate()` returning one `TypeSpec`, superclass `IllegalStateException`, private final fields, the constructor building the message and copying the index list with `List.copyOf`, the four accessors, `serialVersionUID`. The emitted source must compile at Java 17.
* `GlobalUnitKind`: a new constant for the class, added to the javadoc's list of kinds that never appear in a plan ("Six kinds" becomes seven). `FixedRuntimeEmitter.render` adds it, and its class javadoc's list of fixed classes names it. `GeneratedUnits` gains its address (`zeroRowsMatchedException()`, `<pkg>.schema.ZeroRowsMatchedException`). The kind joins `CLIENT_EXCEPTION` in three places: the fixed-kind case list in `GraphQLRewriteGenerator`, the `addFixedKindEdges` switch in `PlanCompileGraph` (compiler-checked), and `PlanCompileGraph.FROZEN_SCAFFOLD_KINDS`, an `EnumSet` the compiler does not check and the only source of the edge from a fetchers unit to the class (through `addBlanketEdges`). Missing the last one surfaces only when the incremental oracle finds an emitted reference outside the graph.
* `TypeFetcherGenerator`: the `PerRow` record and sealed `MissPolicy` (four records); `BulkPerRowBodyFn` returns `PerRow`; `buildBulkRecordTwoStepFetcher` emits the indexed loop, the per-policy miss branch and append, and (under `CountThenThrow`) the `misses` declaration and post-loop throw; `buildBulkRecordPerRowInsertBody` (through its dispatcher `buildBulkRecordPerRowBody`), `buildBulkRecordPerRowDeleteBody` and `buildCarrierBulkPerRowUpdateBody` drop their `acc.add` and miss branches and return their policy. Rewrite the javadoc on the skeleton, the UPDATE body and the DELETE body, which describe fail-fast as what preserves the invariant and the UPDATE and DELETE messages as sharing a shape. The skeleton's javadoc links `buildMutationBulkUpdatePayloadFetcher` and the DELETE body's links `buildMutationBulkDeletePayloadFetcher`, neither of which exists; repoint both to `buildMutationBulkDmlRecordFetcher` in the same edit.
* `docs/manual/reference/directives/mutation.adoc` and `docs/manual/how-to/error-channel.adoc`: the user documentation below.

## User documentation (first-client check)

In `mutation.adoc`, under the UPDATE section beside the empty-patch paragraph that already mentions the "matched zero rows" error, a short subsection:

> **When a key matches no row.** A list-input `UPDATE` that returns a payload fails the whole call when any input row's key matches no row: nothing is written, and the field reports an error, a typed one if a handler in the payload's errors field matches, otherwise a redacted one. Graphitron runs every row before failing, so the error counts all of them. The exception is `<outputPackage>.schema.ZeroRowsMatchedException`, generated into your output package; it is an `IllegalStateException` whose message contains "matched zero rows", and it tells you which rows missed:
>
> ```java
> @Override
> public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
>     if (thrown instanceof ZeroRowsMatchedException miss) {
>         return miss.missCount() + " of " + miss.rowCount()
>             + " rows in this write were not found, or are not writable for you.";
>     }
>     return FetcherErrorResponse.super.unmatchedMessage(thrown, reference, env);
> }
> ```
>
> `missedRowIndices()` gives the zero-based input positions. A key that matches no row may be a row the caller is not allowed to see, so decide how much of this to show. A list-input `UPDATE` that returns the `@table` type or ids directly does not fail on a missed key: it returns the rows it wrote.

In `error-channel.adoc` § "Choosing what the client reads", one sentence pointing at the subsection as the example of an exception worth branching on in `unmatchedMessage`.

The draft reads simply: one class, four accessors, an `instanceof`. Nothing about the classifier or the skeleton leaks into it.

## Tests

* **Execution tier, `DmlBulkMutationsExecutionTest`** (the acceptance evidence): `updateFilmsPayload` with five rows, misses at indices 1 and 3, run through `runWithLog`. Assert the logged `thrown` is a `ZeroRowsMatchedException` with `missCount() == 2`, `rowCount() == 5`, `missedRowIndices()` equal to `[1, 3]`, a message containing "matched zero rows", "2 of 5" and `Mutation.updateFilmsPayload` and not the sentinel titles; and that the three existing films, including the two after the first miss whose statements ran, keep their original titles (the rollback). A second case with one miss at the last index shows the earlier rows' statements ran and rolled back. The existing `updateFilmsPayload_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow` keeps passing unchanged (still an `IllegalStateException`, still "at index 1"), which is the backward-compatibility evidence for handlers matching on class and substring.
* **Execution tier, `FetcherErrorResponseExecutionTest`**: a configured `FetcherErrorResponse` whose `unmatchedMessage` branches on `ZeroRowsMatchedException` produces "2 of 5 …" as the client's top-level error message, the sis use exactly.
* **Pipeline tier**: a `ZeroRowsMatchedExceptionClassGeneratorTest` beside `GraphitronClientExceptionClassGeneratorTest` (superclass, accessors, constructor); `FixedRuntimeEmitterTest` and `EmitPlanTest` gain the new unit in their expected lists. The bulk-DELETE (both the failing and the `missingAsDeleted` cases) and bulk-INSERT execution tests stay green unchanged, which shows moving their miss handling into the skeleton changed nothing for them.

No assertion on generated method bodies: the behaviour is pinned at the execution tier.

## Other solutions we've considered

* **A stable message shape and nothing else.** Enough for sis to parse "2 of 5", but it makes the message text a contract every consumer parses, and Graphitron's messages are documented as free to reword. The typed class costs one small generated file and keeps the message human-only.
* **A guard parameter beside the unchanged seam**: the skeleton declares `misses` and emits the post-loop throw, and the UPDATE body writes `misses.add(rowIndex); continue;` itself. Smaller diff, but each body still owns `acc.add`, so "every row appends or records a miss" stays a promise the body makes, and the guard and the body are two arguments that can be mismatched at the call site with nothing failing until the emitted code compiles. Returning the policy with the body fixes both.
* **Naming the new class in `@error(className:)`.** The classifier recognises `GraphitronClientException` by name before it compiles; teaching it a second generated name is a separate change with no requester. Handlers keep matching through `IllegalStateException` and the message substring.

## Provenance

Requested 2026-10-29 by the sis Graphitron 10 port session, which tracks it as C33 in `GRAPHITRON10_STATUS.md`. 48 sis UPDATE mutations have no `@error` handler for this guard, so their clients get `FetcherErrorResponse.unmatchedMessage`; sis wants Graphitron 9's text there ("N of M statements in this write affected no rows. The rows may not exist, or may not be writable for the current user."), which Graphitron 9 threw as `NoRowsAffectedException` after every statement had run. sis ships "1 of M" meanwhile, so the priority is low. The bulk DELETE guard is sis row C34 and not part of this item.
