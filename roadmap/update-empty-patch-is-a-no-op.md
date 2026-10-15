---
id: R1040
title: "A DML UPDATE row that sends only its key fields is a no-op that still returns the row"
status: Spec
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A DML UPDATE row that sends only its key fields is a no-op that still returns the row

## Goal

A `@mutation(typeName: UPDATE)` row that carries only its key fields issues no UPDATE for that row and still appears in the payload exactly as an updated row would: read back by key, under the same tenant routing, in the same position in the batch, while the other rows in the batch update normally. A key that matches no row gives the same outcome as a non-empty patch on that key. Today the generated runtime PATCH guard throws `IllegalArgumentException("@mutation(typeName: UPDATE) call has no settable fields present; only key fields were provided")` when the row's SET map is empty. The throw happens inside the transaction, so it rolls back the whole batch, and no payload error type maps it, so the client gets the redacted "An error occurred. Reference: <uuid>" (the R1036 shape). Clients use a "send only what changed" PATCH contract, so a patch with nothing in it is a legitimate request, not a malformed one: a UI that always submits the id plus whatever the user touched sends one whenever the user saves without editing. Graphitron 9 handled this by going through jOOQ `store()` with changed flags, which issues no statement for an unchanged record and echoes the row. The build-time guarantee that the input type has at least one settable column stays as it is.

The sakila fixture already carries the shape. With

```graphql
input FilmUpdateNestedDetails {
    title:       String @field(name: "title")
    description: String @field(name: "description")
}
input FilmUpdateNestedInput {
    filmId:  Int! @field(name: "film_id")
    details: FilmUpdateNestedDetails
}
type Mutation {
    updateFilmsNestedPayload(in: [FilmUpdateNestedInput!]!): FilmsPayload @mutation(typeName: UPDATE)
}
```

the request

```graphql
mutation {
  updateFilmsNestedPayload(in: [
    { filmId: 7 },
    { filmId: 9, details: { title: "Renamed" } }
  ]) { films { filmId title } }
}
```

returns film 7 unchanged in position 0 and film 9 renamed in position 1, writes only film 9, and fires no UPDATE (and so no UPDATE trigger) against film 7. Today it fails the whole batch with the redacted error.

The patch is empty when the row's SET map is empty. That covers every way a row can claim nothing about its settable columns: every optional set-side field absent, a nullable nested group absent or sent as `null` (the "no claim about the group" rows of the tutorial's absent-vs-null table), or any combination. An explicit `null` on a plain nullable column is a write of NULL, not an empty patch.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`. sis's generated `MutationFetchers` carries 60 of these guards. Example: `endreStudieprogramOkonomi(input: [EndreStudieprogramOkonomiInput!]!)` with `@mutation(typeName: UPDATE, table: "studieprogram")`, sent `[{id: "<existing id>"}]`. sis expects no top-level errors, a null payload `errors`, the id echoed in `studieprogram`, and the row unchanged in the database. Six sis integration tests fail on the guard, all `EndreStudieprogram*MutationIT` cases that leave every optional field out (`..._ingenFelterSatt_...`, `..._beggeFeltgrupperUtelatt_...`, `..._utenFelt_...`, `..._alleFelterUtelatt_...`).

The guard came in with the dynamic-SET UPDATE work (`36a4e2db9`). Graphitron's own tests never exercised the throw: `GraphQLQueryTest.updateFilm_onlyLookupKeyFields_raisesError` and `DmlBulkMutationsExecutionTest.updateFilms_onlyLookupKeyFields_raisesError` send `FilmUpdateInput` without `title`, which is `String!`, so the request fails GraphQL validation before the fetcher runs and both assertions ("errors non-empty", "zero queries") pass for the wrong reason. Only `NestedInputDmlExecutionTest`'s two `*_emptySetGuardFires` cases reach the guard, through the nullable `details` group.

## Where the guard lives

A *write arm* below is one generated fetcher shape for UPDATE; a *terminator* is the code that appends the key-returning tail (`.returningResult(<key columns>)` and `.fetchOne(...)` / `.fetch(...)`) to the statement the arm builds. The four arms, and the four guard sites, are all in `TypeFetcherGenerator` (the direct arms each have two terminators, projected and encoded `ID`):

| Arm | Builder | Terminator | No-match outcome today |
|---|---|---|---|
| direct `@table` / `ID` return, single row | `buildMutationUpdateFetcher` (single-row branch) | `emitKeysTransaction` (projected) or `emitEncoded` (`ID`) | `fetchOne` answers `null`; the field resolves `null`, no error |
| direct return, list input | `buildBulkUpdateFetcher` (`UPDATE ... FROM (VALUES ...) v`, Postgres only) | `emitKeysTransaction` or `emitEncoded`, list form | the row is absent from the returned keys, no error |
| payload carrier, single row | `buildCarrierUpdateChainSingle`, through `buildSingleRecordTwoStepFetcher` | `buildSingleRecordTwoStepFetcher`'s PK `returningResult` | `payload == null`; the payload resolves `null` |
| payload carrier, list input | `buildCarrierBulkPerRowUpdateBody`, through `buildBulkRecordTwoStepFetcher` | inline per-row `returningResult(pk).fetchOne()` | throws `IllegalStateException("... bulk row matched zero rows ...")`, which a payload error handler may map (sis maps it to `FantIngenOppforingAaEndre`) |

Two more guards with the same shape sit on the UPSERT emitters (`buildMutationUpsertFetcher`, `buildRecordUpsertChain`). They are unreachable: `FieldBuilder` refuses `@mutation(typeName: UPSERT)` at dispatch as deferred, and the execution-tier UPSERT tests are `@Disabled`. They stay exactly as they are. What an empty patch means on UPSERT ("insert if absent, otherwise leave the row as it is") is a separate contract, and it belongs to whichever item designs UPSERT; this item does not pre-decide it.

The bulk direct arm cannot see a mix of empty and non-empty rows: its uniform-shape guard (`buildUniformShapeGuard`, plus `buildNestedShapeGuards`) already rejects a batch whose rows disagree on their present keys, and the SET map is built from the first row's keys. So on that arm an empty patch means every row is empty, and the whole statement is replaced. The bulk payload arm is a per-row loop with no uniform-shape guard, so mixed batches reach it, and each row decides independently.

## Design

**The empty-patch row runs a key read in place of the UPDATE, and the read is the same statement with the write removed.** Where an arm today builds `update(t).set(sets).where(W)` and the terminator appends `.returningResult(K)`, the arm builds `select(K).from(t).where(W)` when `sets.isEmpty()`. `W` is the same WHERE expression (same decode locals, same key predicates) and `K` is the same key column list, so both branches are `ResultQuery`s over the same `RecordN<...>` and the terminator's `.fetchOne(...)` / `.fetch(...)` reads either. Every arm's no-match outcome in the table above then holds unchanged, because it is a property of the terminator and the record it gets back, not of the verb: a missing key on the bulk payload arm still throws "matched zero rows" and still reaches the mapped error; a missing key on a single arm still resolves `null`. That is the parity the goal asks for, by construction rather than by a second no-match rule.

The read issues on the same `DSLContext` the UPDATE would have used (`dsl` on the encoded arm, `DSL.using(tx)` / `txd` inside the transaction on the others). Tenant routing is a property of that context (`TenantDslEmitter.resolve` declares it before either branch), so the read routes exactly as the write would; nothing tenant-specific is added.

On the bulk direct arm the read keeps the VALUES table: `select(K).from(t).join(v).on(W)`, where `W` is the existing `t.k.eq(v.field(t.k))` conjunction and `v` carries only the key columns (no SET column is present in the first row). It returns each matched row once, as the UPDATE's `RETURNING` does; neither form orders by input position, and the reentry companion's `idx` ordering downstream is untouched. The duplicate-lookup-key guard and the Postgres dialect guard stay in front of both branches.

The emitted Java chooses the statement in statement form, per the generated-code readability rule ("statement form over expression tricks": a developer cannot breakpoint a ternary arm). A typed local is assigned in an `if` / `else` and the terminator reads the local, for example on the single-row projected arm:

```java
Record1<Integer> keys = dsl.transactionResult(tx -> {
    ResultQuery<Record1<Integer>> statement;
    if (sets.isEmpty()) {
        statement = DSL.using(tx).select(FILM.FILM_ID).from(film).where(FILM.FILM_ID.eq(...));
    } else {
        statement = DSL.using(tx).update(film).set(sets).where(FILM.FILM_ID.eq(...)).returningResult(FILM.FILM_ID);
    }
    return statement.fetchOne();
});
```

The transactional arms become block lambdas to hold the local; the encoded arm, which runs on `dsl` with no transaction, declares the local in the fetcher body. The bulk payload arm already sits in a block lambda and declares it per row.

**The read takes no row lock and fires nothing.** No UPDATE trigger runs for an empty-patch row and no row version is written, which is what Graphitron 9's `store()` did and what sis's "row unchanged" assertion needs: an audit trigger that stamps a modified-at column on every UPDATE would otherwise change a row the client did not touch.

**The predicate is the SET map, not input presence.** `sets.isEmpty()` is the one place that already knows what the row will write: it is downstream of every presence gate, nested-group rule and `CarrierNullRule` disposition. Whatever a sibling item decides about an explicit `null` on a `@nodeId` UPDATE field (R1039 leaves that open), the resulting SET map is what this item reads, so the two compose without an edge between them.

## Implementation

All in `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java` unless named otherwise.

- **One rendering of the branch, shared by the four terminators.** Today the terminators take an opaque `CodeBlock dmlChain` (and `DmlChainAndGuards.chain`) and append `.returningResult(K)` themselves. Widen that seam to a small private sealed statement type that renders the key-returning statement given a receiver expression and `K`, one record per render shape, each holding only what its render needs: the existing chain plus `returningResult` (INSERT, DELETE and the unreachable UPSERT, rendered byte-identically to today); the keyed UPDATE (table local and `W`), whose read is `from(t).where(W)`; and the VALUES-join UPDATE (table local, `v` and `W`), whose read is `from(t).join(v).on(W)`. The two UPDATE records render the `if` / `else` above; the renderer switches on the record, never on whether a `v` is present. `emitEncoded`, `emitKeysTransaction`, `buildSingleRecordTwoStepFetcher` and `buildCarrierBulkPerRowUpdateBody` take the statement type; `emitDmlReturnExpression` and `buildDmlFetcher` thread it through. This keeps the empty-patch read in one helper rather than four inline copies, and makes the INSERT / DELETE emit provably unchanged.
- **Remove the two UPDATE throws.** The `if (sets.isEmpty()) throw ...` blocks in `buildMutationUpdateFetcher`, `buildBulkUpdateFetcher`, `buildCarrierUpdateChainSingle` and `buildCarrierBulkPerRowUpdateBody` go; the branch replaces them. The UPSERT copies stay.
- **Prose that names the guard.** `buildBulkUpdateFetcher`'s javadoc lists "No-set-fields-present" as one of three guards; `buildDmlFetcher`'s javadoc says `postInGuard` runs "the no-set-fields-present runtime check"; `buildCarrierUpdateChainSingle`'s javadoc lists "a runtime empty-SET guard". Rewrite each to describe the empty-patch read. `emitSetVFieldPuts`'s javadoc warns that removing the self-FK cross-partition put "reintroduces the empty-SET throw on the minimal self-FK shape"; after this item the same removal would turn a valid self-FK update into a silent no-op read, which is worse, so that warning is rewritten to say so rather than deleted.
- **Fixture comment.** The `FilmUpdateNestedInput.details` comment in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` says an absent or null group makes "the runtime empty-SET guard" fire; it now makes the row an empty patch. Same for the bulk-fixture comment that lists "no-set-fields" among the VALUES-join guards.
- **User manual.** `docs/manual/reference/directives/mutation.adoc`, the bullet that says `UPDATE` requires at least one input field outside the matched key: keep the build-time rule and add the runtime contract (draft below). `docs/manual/tutorial/05-mutations.adoc`'s absent-vs-null table needs no change; its "no claim about the group" rows are exactly the case this item makes legal.
- **Unchanged:** `UpdateRowsError.NoSetFields` and the `UpdateRows.Identified` non-empty-`setColumns` invariant (the schema still has to declare something settable); the uniform-shape, duplicate-lookup-key, length-check and dialect guards; every SQL string a non-empty UPDATE renders.

## User documentation (first-client check)

The `mutation.adoc` bullet becomes:

> `UPDATE` requires at least one input field outside the matched key (otherwise the `SET` clause is empty). That is a rule about the input *type*. A *request* may still leave every one of those fields out, or send a nested group as absent or `null`: the row is then an empty patch. Graphitron issues no `UPDATE` for it, reads the row back by its key, and returns it in the payload in the row's position, as it would an updated row; other rows in the same list input update normally. An empty patch on a key that matches no row behaves like any `UPDATE` on that key: a single-row mutation resolves `null`, and a list input with a payload raises the same "matched zero rows" error its error handlers already see.

It reads simply, and states the rule from the client's side; no new directive or argument is involved.

## Tests

The acceptance evidence is the execution tier, one case per arm, each asserting the response *and* the statements issued (a SQL-capturing `ExecuteListener`, the idiom `DmlSqlBaselineTest` already uses): for an empty-patch row no UPDATE, the key read as the only statement inside the write transaction, and the row's columns unchanged in the database. (The projected and payload arms then run their usual follow-up re-select of the payload outside the transaction; that statement is the same as for a non-empty patch and is not what the case pins.)

New execution-tier class `EmptyPatchUpdateExecutionTest` in `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/`, driving the fixtures whose set-side fields are all optional (`FilmUpdateNestedInput`, nullable `details`):

- `updateFilmNested(in: {filmId})`: direct projected, single. Film echoed with its current `title`, no errors, no UPDATE.
- `updateFilmsNested(in: [{filmId: a}, {filmId: b}])`: direct projected, list (the VALUES arm). Both films returned; the key read joins the VALUES table; no UPDATE.
- `updateFilmNestedPayload(in: {filmId})`: payload carrier, single. `film` echoed.
- `updateFilmsNestedPayload(in: [{filmId: a}, {filmId: b, details: {title: "X"}}, {filmId: c}])`: payload carrier, list, mixed. Order a, b, c preserved; only b written; inside the one write transaction the statements are key read, UPDATE, key read, in row order.
- Missing-key parity, one per no-match shape: `updateFilmNested(in: {filmId: <absent>})` resolves `null` with no errors, matching the same call with `details: {title: "X"}`; `updateFilmsNestedPayload` with one absent key returns the same error response as the non-empty patch on that key, and the batch's other rows are rolled back in both.

In `TenantDivinedRoutingExecutionTest`, beside the existing `updateFilmByNodeId` / `retitleFilmsChecked` cases (`UpdateFilmByNodeIdInput.title` is optional): an empty patch on a film owned by one tenant routes to that tenant's database and echoes it; and `updateFilmActorNoteByNodeId(in: {id})` covers the encoded `ID` single arm under routing. `updateFilmSceneParents(in: [{id}])` covers the encoded `ID` list arm; it can live in either class.

Flipped or removed:

- `NestedInputDmlExecutionTest.updateNested_absentOuterGroup_skipsWholeGroup_emptySetGuardFires` and `..._nullOuterGroup_...`: renamed and inverted to assert no errors, the film echoed, and `description` unchanged (their database assertion already holds).
- `GraphQLQueryTest.updateFilm_onlyLookupKeyFields_raisesError` and `DmlBulkMutationsExecutionTest.updateFilms_onlyLookupKeyFields_raisesError`: deleted. They test GraphQL validation of `title: String!`, not graphitron, and the new class covers what their names claim. The two `@Disabled` UPSERT siblings stay with the UPSERT emitters.
- `FetcherPipelineTest`: the two "no-set-fields-present runtime check" assertion groups (the single-row `updateFilm` and the bulk `updateFilms` cases) are deleted, not flipped. They are code-string matches on generated bodies, which `docs/architecture/how-to/testing.adoc` bans at every tier; writing new ones would also pin the emitted form. The execution-tier cases above pin the behaviour, and `graphitron-sakila-example`'s release-17 compile pins that the emitted form compiles.

Regression guard: every existing `DmlSqlBaselineTest` pin passes unedited, which shows non-empty UPDATE, INSERT and DELETE render the same SQL as before.

## Retired vocabulary

The two runtime messages `@mutation(typeName: UPDATE) call has no settable fields present; only key fields were provided` and `@mutation(typeName: UPDATE) bulk call has no settable fields present in the input rows`, and the phrases "runtime PATCH guard", "empty-SET guard" / "empty-SET throw" and "no-set-fields-present" (runtime check or guard) where they describe UPDATE. The Done sweep excludes the UPSERT emitters and their `@Disabled` tests, which keep their own copy, and `UpdateRowsError.NoSetFields` / `graphitron.update-rows.no-set-fields`, which name the build-time rule this item keeps.

## Other solutions we've considered

- **Self-assign a key column (`UPDATE t SET k = k WHERE ...`).** Keeps every terminator untouched, since the statement is still an UPDATE. Rejected because it is a real write: it takes a row lock, writes a row version, fires UPDATE triggers (an audit trigger would stamp a row nobody changed), and needs UPDATE privilege on a key column that column-level grants or row-level security may withhold.
- **Echo the key from the input with no statement at all.** What Graphitron 9 effectively did. Rejected: it cannot tell a missing row from a present one, so a no-op and a not-found would differ by nothing, and the returned keys are the primary key (or the reentry correlation's columns) while the matched key may be a unique key or a decoded `@nodeId`, so the key the payload needs is not always in the input.
- **Decide emptiness from input presence instead of the SET map.** Would let a "client sent a field but the emitter dropped it" bug keep throwing. Rejected because presence alone does not decide what is written (a `null` nested group, a `CarrierNullRule` disposition), so the predicate would re-derive the SET walk's gating; the SET map already is that answer, and the per-arm execution tests above catch an emitter that drops a write.
- **`SELECT ... FOR UPDATE` for the read.** Would serialize the empty patch against concurrent writers as an UPDATE does. Nothing the client sees depends on it, since the payload is re-read after the transaction either way, so the lock buys contention and nothing else.
