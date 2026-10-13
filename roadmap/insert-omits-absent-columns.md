---
id: R1017
title: "An INSERT leaves absent input fields out of its column list instead of writing DEFAULT"
status: In Progress
bucket: bug
priority: 5
theme: mutation-write
depends-on: []
created: 2026-10-12
last-updated: 2026-10-13
---

# An INSERT leaves absent input fields out of its column list instead of writing DEFAULT

## Goal

A generated `@mutation(typeName: INSERT)` works against a view on Oracle. Today, for each input field the client leaves out, the INSERT still names the column and writes the SQL keyword `DEFAULT` into its `VALUES` cell (rendering `values (:1, :2, :3, default, cast(:4 as date), ...)`). Oracle rejects an explicit column default against a view (ORA-32575, "Explicit column default is not supported for modifying views"), and most sis API tables are views. When this lands, a single-row INSERT and a bulk INSERT whose rows all leave out the same fields name only the columns the client supplied (one edge aside, a batch of several rows that supplies no field at all; see The bulk rule), so the database applies its own default without being asked to by name. This is what Graphitron 9 did, and what the user manual already promises: `docs/manual/reference/directives/mutation.adoc` § Constraints says absent optional fields "are omitted from the column list (`INSERT`)".

Using the sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`), where `film.rental_duration` is `smallint NOT NULL DEFAULT 3`:

```graphql
input FilmCreateInput {
    title:          String! @field(name: "title")
    languageId:     Int!    @field(name: "language_id")
    rentalDuration: Int     @field(name: "rental_duration")
}

type Mutation {
    createFilm(in: FilmCreateInput!): Film @mutation(typeName: INSERT)
    createFilms(in: [FilmCreateInput!]!): [Film!]! @mutation(typeName: INSERT)
}
```

`createFilm(in: { title: "X", languageId: 1 })` today renders `insert into film (title, language_id, rental_duration) values (?, ?, default)`; after this item it renders `insert into film (title, language_id) values (?, ?)`. Both land `rental_duration = 3`. An explicit `rentalDuration: null` still names the column and binds a typed null, so the absent-versus-explicit-null distinction (graphql-java keeps an omitted field out of the argument map, while an explicit null is a present key with a null value) is unchanged.

## The bulk rule

A bulk INSERT (`in: [X!]!`) renders one statement with one `VALUES` list, and one statement has one column list. The rule is: the column list is the union, over all rows, of the columns some row supplies. A row that leaves out a column another row supplies gets `DEFAULT` in that one cell.

- **Every row supplies the same fields** (the common case, and always true for a one-element list): no `DEFAULT` is written anywhere, unless that shared set is empty and there is more than one row (below). This is the case the Oracle view needs.
- **Rows differ** (mixed shape): the statement means exactly what it means today, one round trip, one atomic statement, `RETURNING` in input order, with `DEFAULT` only in the gap cells. Against an Oracle table this works; against an Oracle view it still fails with ORA-32575, loudly, at the database. That is the first named residual of this item.
- **No row supplies any field, more than one row**: the union is empty, and jOOQ renders an empty column list as a single `insert into t default values` whatever the row count (checked against jOOQ 3.20.11, `POSTGRES` and `DEFAULT` dialects), so an N-row batch would insert one row and return one record. The empty union is therefore widened to every plan column, every cell `DEFAULT`: today's statement, N rows, `RETURNING` in input order. Against an Oracle view this fails with ORA-32575 like a mixed batch, and joins that residual. A one-row batch keeps the empty list and renders `default values`, which inserts the one row asked for, the same shape as the single-row path below.

INSERT deliberately accepts a mixed batch where bulk UPDATE rejects one (`buildUniformShapeGuard`, "bulk input rows must share the same present-key set"). The asymmetry is principled, not drift: a gap in an INSERT row has a per-row meaning (that row takes the column default), while bulk UPDATE shares one `SET` clause across all rows, so a field present in one row and absent in another has no single meaning for it. Nobody should later "fix" one to match the other.

An INSERT whose every field is absent (reachable only when every input field is nullable) has an empty column list; jOOQ renders it as `insert into t default values`. Whether jOOQ's Oracle rendering of that shape names columns with `DEFAULT` (and so hits ORA-32575 on a view) is checked at implementation time with the jOOQ edition sis builds against; if it does, it is a further named residual, recorded in the manual line below, not worked around here.

## Implementation

All generator edits are in `TypeFetcherGenerator`.

**One emitter for column list and cells, paired by construction.** Today `buildInsertColumnList` / `buildInsertColumnListDeduped` emit a static, typed column list (`insertInto(t, T.C1, T.C2, ...)`) and `buildPerCellValueList` / `buildPerCellValueListDeduped` / `emitInsertCell` emit the parallel `present ? DSL.val(...) : DSL.defaultValue(...)` cells; the two stay aligned only because both walk `flattenInsertLeaves`. Replace the four with one emitter that walks the INSERT column plan once (`insertColumnPlan`, which already yields one entry per distinct column in a fixed order, deduping columns written by more than one input field) and, per column, emits one presence-gated `put` of column to cell into a `LinkedHashMap<Field<?>, Field<?>>`. The column list is the map's `keySet()` and the cells its `values()`, so they cannot misalign, which replaces the compile-time alignment check the typed `insertInto(t, c1..cN).values(...)` overload gave. This is the shape UPDATE's dynamic `SET` map already has (`emitSetMapPuts`, `setColumnPresenceGate`); reuse its presence-gate helpers where the semantics coincide rather than adding a third presence walk. INSERT has no `@onExplicitNull` rule to apply, so the UPDATE put walk is a model, not necessarily a drop-in.

Per column, the gate and the cell follow the existing carriers:

- A plain column field (`InputField.ColumnBackedField` with `CallSiteExtraction.Direct`): gate is `nestedContainsKeyExpr` over the leaf's access path, cell is `DSL.val(<wire value>, T.C.getDataType())`. The access path covers nested grouping inputs (an input-typed field that only groups columns of the same table), as today.
- A NodeId-decoded field or FK reference field (`ColumnBackedField` with `NodeIdDecodeKeys`, `ColumnBackedReferenceField`), whose encoded ID decodes to one or more key columns: gate is the source field's presence, cells read `insertKey_<fi>.value<slot+1>()` from the decode locals `buildInsertDecodeLocals` already emits.
- A shared column (one written by more than one input field, `OverlapColumn.shared()`): gate is the disjunction of its contributors' presence; the cell is the coalesced value `emitInsertAgreementPrep` already computes after `requireColumnAgreement`. The prep's "no writer present" arm (`DSL.defaultValue`) goes away on the single-row path, since the column is then simply not put.

**Single row.** The map is built in `postInGuard` beside the decode locals and the chain becomes `.insertInto(t, cols.keySet()).values(cols.values())` (jOOQ's `InsertValuesStepN`). `DSL.defaultValue` is never emitted.

**Bulk.** First compute, per plan column, whether any row supplies it (`in.stream().anyMatch(row -> <gate(row)>)`), widen an empty set to every plan column when `in.size() > 1` (see The bulk rule), then build each row's cells in plan order over the included columns, a present cell binding its value and a gap cell `DSL.defaultValue(...)`, wrapped in `DSL.row(Collection)`; the chain becomes `.insertInto(t, <included columns>).valuesOfRows(<rows>)`. The bulk lambda becomes a block lambda unconditionally (the `anyNodeIdCarrier` single-expression arm retires). For a uniform batch that supplies at least one field no gap cell exists, so `DEFAULT` is never rendered.

**Call sites.** Every INSERT-values site moves onto the shared emitter, so there is one INSERT path:

- `buildMutationInsertFetcher` (single and bulk);
- `buildMutationUpsertFetcher` (single and bulk). UPSERT is rejected on Oracle by its `DialectRequirement`, so this is consistency rather than the fix; the `.onConflict(...).doUpdate().set(setsUpdate)` half is untouched, and its bulk arm already runs the uniform-shape guard in `doUpdate` mode;
- `buildRecordInsertChain` and `buildRecordUpsertChain`, the payload-returning variants;
- `buildBulkRecordPerRowInsertBody`, which issues one statement per row and therefore takes the single-row shape per row: it never writes `DEFAULT`, even for a mixed batch.

**Manual.** Rewrite the last bullet of `docs/manual/reference/directives/mutation.adoc` § Constraints to say what ships: absent optional fields are omitted from the column list of a single-row INSERT and of a bulk INSERT whose rows supply the same fields; in a bulk INSERT whose rows differ, the column list covers every field some row supplies and a row that leaves one out writes `DEFAULT` for it, which Oracle rejects against a view; a bulk INSERT of several rows that supply no field at all names every column with `DEFAULT`, with the same Oracle-view caveat. Add the single-row all-absent residual there too if the implementation-time check finds it.

## Tests

The behaviour is pinned at the execution tier by observing the rendered SQL, not by asserting strings in the generated Java. `DmlBulkMutationsExecutionTest` already installs an `ExecuteListener` (it counts statements); extend it to capture the rendered SQL, and add:

- `createFilm` with `rentalDuration` omitted: one statement, its column list does not name `rental_duration`, it contains no `default` cell, and the row reads back `rentalDuration: 3`.
- `createFilms` with both rows omitting `rentalDuration`: the same three assertions for the bulk statement.
- `createFilms` with one row `rentalDuration: 5` and one omitting it: one statement, column list names `rental_duration`, results `[5, 3]` in input order. This pins the mixed-shape rule.
- `createFilmsPayload` (the bulk per-row record path) with the same mixed input: no statement contains a `default` cell.
- A bulk INSERT of two empty rows inserts two rows and returns two, in one statement. No existing INSERT fixture fits: every all-nullable INSERT input in the sakila schema targets `film_endorsement`, whose `endorsed_film` is `NOT NULL` without a default. Add a sibling fixture on `film_bookmark` (every column nullable or defaulted, today declared only in `multitenant.graphqls`): a `FilmBookmark` type over `film_bookmark`, an input whose only field is `note: String @field(name: "note")`, and `createFilmBookmarks(in: [FilmBookmarkCreateInput!]!): [FilmBookmark!]! @mutation(typeName: INSERT)`. A one-row empty batch on the same fixture renders `default values` and inserts one row.
- One overlap case on `insertEndorsementOverlap` (the `film_endorsement` INSERT where `endorsed_film` is written by two input fields, exercised in `NodeIdValueAgreementExecutionTest`) with every writer of the shared column absent: the column is not named. If the fixture's nullability does not allow both writers to be absent, add a sibling fixture that does.

The existing `createFilms_omittedFieldUsesColumnDefault` and `createFilms_explicitNullRaisesError` (a mixed batch whose present cell is an explicit null, still one statement) must pass unchanged, along with `NestedInputDmlExecutionTest`, `SelfFkNodeIdInsertExecutionTest`, `NodeIdValueAgreementExecutionTest` and `SingleRecordPayloadDmlTest`, which cover the nested, NodeId, overlap and payload shapes the emitter rewrite touches. So must `GraphQLQueryTest`'s single-row `createFilm_omittedFieldUsesColumnDefault` and `createFilm_explicitNullRaisesError`, which pin the row values the single-row path lands. `FetcherPipelineTest`'s bulk INSERT block asserted the generated body's statement shape (a static column list, one `.valuesOfRows(in.stream()` chain, the per-cell `DSL.defaultValue(`). Those are code-string assertions on a generated method body, which `development-principles.adoc` bans at every tier, so they go rather than being rewritten for the new shape. The block keeps only what it asserted outside the INSERT statement: the bulk arm's `List<Map<?, ?>>` binding, the empty-list short-circuit, and the narrowed return type. The statement shape (which columns are named, where a gap cell writes `DEFAULT`) is pinned on the rendered SQL by the execution-tier cases above and by `DmlSqlBaselineTest`'s INSERT baselines.

The `film_bookmark` rows the empty-row cases insert carry no note and no film, so the note-keyed cleanup `TenantDivinedRoutingExecutionTest` uses cannot find them. Run those cases inside `DmlBulkMutationsExecutionTest`'s `inRolledBackTransaction`, as its seeded `film_actor` cases do, or delete by the returned keys, so the shared database is left as it was found.

The execution tier runs on PostgreSQL, which accepts `DEFAULT` against views, so ORA-32575 itself is not reproduced in-tree. The property the tests pin (no `DEFAULT` keyword for single rows and uniform batches) is dialect-independent; the sis Oracle IT run is the confirming evidence at the Done gate.

## Retired vocabulary

What retires:

- `buildPerCellValueList`, `buildPerCellValueListDeduped`, `emitInsertCell`, `buildInsertColumnList`, `buildInsertColumnListDeduped` (replaced by the one paired emitter).
- The static, typed INSERT column list (`insertInto(t, T.C1, T.C2, ...)`) at every INSERT-values site.
- The presence-gated `present ? DSL.val : DSL.defaultValue` cell on the single-row INSERT and UPSERT paths and on the bulk per-row record path. None of them emits `DSL.defaultValue` after this lands. The same goes for the "no writer present" arm of `emitInsertAgreementPrep` on those paths.
- The name "per-cell missing-vs-null dispatch", and its paraphrases "containsKey-gated DEFAULT" and "per-cell containsKey dispatch", everywhere, including on the bulk path. The mechanism it named survives only on the bulk path, under the name below. Current habitats: `TypeFetcherGenerator`'s `buildPerCellValueList` javadoc and the `buildMutationUpsertFetcher` comment ("per-row containsKey-gated INSERT cells"); `FetcherPipelineTest`'s bulk-DML section comment and `.as(...)` label; `DmlBulkMutationsExecutionTest`'s class javadoc and the comments in `createFilms_omittedFieldUsesColumnDefault` and `upsertFilms_omittedFieldOnInsertBranchUsesColumnDefault`; `GraphQLQueryTest`'s "missing-vs-null on single-row INSERT" section header and the comments in `createFilm_omittedFieldUsesColumnDefault` and `upsertFilm_omittedFieldOnInsertBranchUsesColumnDefault`; the `schema.graphqls` comment on the bulk mutations ("valuesOfRows + per-cell containsKey dispatch").
- "DEFAULT-resolved cell" in `buildInsertDecodeLocals`' javadoc. An absent key now means an omitted column on the single-row path, or a gap cell on the bulk path, and the javadoc says that.

What survives, and under which name:

- **Gap cell.** On the bulk INSERT and bulk UPSERT paths, a gap cell is the `DSL.defaultValue(<column>.getDataType())` cell written for a row that leaves out an included column: one some other row supplies, or, in a widened batch of several empty rows, every plan column. That includes the bulk arm of a shared column's agreement prep when no writer is present in the row. Which batches are uniform is a runtime fact, so the generated bulk body always contains the gap-cell branch; a uniform batch never takes it, and a bulk UPSERT in `doUpdate` mode never does either, because its uniform-shape guard has already run. Sweep query for the Done gate: in `TypeFetcherGenerator`, `DSL.defaultValue` is emitted only by the bulk gap-cell emission, and the prose there calls it a gap cell.

## Implementation notes

Landed as specified, with these points for the In Review reader:

- **Where the emitter lives.** `buildSingleRowInsertValues` and `buildBulkInsertValues` in `TypeFetcherGenerator` return an `InsertValues` pair: the prep statements, and the chain fragment (`.insertInto(t, insertCols.keySet()).values(insertCols.values())`, or `.insertInto(t, insertCols).valuesOfRows(insertRows)`). All five call sites take it, so there is one INSERT path. The shared-column gate is a new `columnPresenceDisjunction`, and the bulk-SET `setColumnPresenceGate` now delegates to it too, with byte-identical output. `emitInsertAgreementPrep` takes a `bulk` flag: on a single row it stops after the agreement check and the put reads `insertKeyAgree<ci>.get(0)` under the writers' disjunction; on the bulk path it keeps the coalesced `insertKeyCell<ci>` local with its gap arm.
- **One deviation in shape, not in meaning.** The bulk rows are built in an imperative `for (Map<?, ?> row : in)` loop in the prep, rather than a block lambda inside `.valuesOfRows(...)`. That is the shape bulk UPDATE already uses for its `vRows`. Presence is computed in one pass over the rows (`insertInc[ci] |= <gate>`) rather than one `anyMatch` per column. The statement is the same.
- **Overlap fixture.** `insertEndorsementOverlap` cannot drop both writers, because `film_endorsement.endorsed_film` is `NOT NULL` without a default. The sibling is `insertBookmarkOverlap` over `film_bookmark`, with `filmRef` (`@nodeId(typeName: "Film")`) and `bookmarkedFilm` both writing the nullable `bookmarked_film`.
- **Single-row all-absent residual.** The implementation-time check was made against the open-source jOOQ 3.20.11 jar, since the commercial edition is not in the tree. `InsertQueryImpl.acceptDefaultValuesEmulation` renders `values (default, …)` for dialects without a `DEFAULT VALUES` clause, and Oracle has none. An INSERT supplying no field therefore names its columns with `DEFAULT` on Oracle and hits ORA-32575 on a view. The manual records this alongside the two bulk residuals.
- **Ratchets.** `CommandSeamRatchetTest` pins lowered: instanceof sites 68 to 66 (the retired `anyNodeIdCarrier` probe), case patterns 61 to 57 (the retired column-list and cell-list carrier switches).
- **Retirement sweep, implementer's pass.** No habitat named under Retired vocabulary still carries the retired names. Two further habitats were rewritten: the `KeyedNode` / `createKeyedNode` fixture comments in `schema.graphqls`, and the `createKeyedNode_singlePkNodeIdDecodedInsert_writesRow` comment. The R420 body's pointer to `buildPerCellValueList` now names the new emitters. R103's body keeps its retraction note, which points here. `DSL.defaultValue` is now emitted in two places in `TypeFetcherGenerator`: the gap cell in `buildBulkInsertValues`, and the bulk arm of `emitInsertAgreementPrep`. Both are bulk gap cells.

- **Done-gate rework (round 3).** The `FetcherPipelineTest` bulk INSERT block no longer asserts the generated statement shape; see Tests.

## Other solutions we've considered

- **One INSERT per distinct row shape.** Removes `DEFAULT` from mixed batches too, but turns one statement into several: the fetcher's single `dsl.insertInto(...)...returningResult(...).fetch(...)` chain would need a transaction scope for atomicity and a reassembly step to restore input order for `RETURNING`. The cost lands on every bulk INSERT to serve the mixed-shape-against-an-Oracle-view case, which has not been observed. Revisit if sis hits it.
- **Reject mixed batches, as bulk UPDATE does.** Loud, and simple, but it turns batches that work today on PostgreSQL and on Oracle tables into errors everywhere, and the INSERT gap has a meaning UPDATE's does not (see The bulk rule).
- **For a batch that supplies no field at all: one `default values` statement per row, or reject it.** Per-row statements carry the same atomicity and reassembly cost as the per-shape split above, for an edge case. Rejecting turns a batch that inserts N rows today into an error. Widening to the full column list keeps today's statement and only costs the Oracle-view residual a mixed batch already has.
- **Write the catalog default instead of the keyword** (`DataType.defaultValue()` from the jOOQ catalog). A view's columns carry no default in the catalog, so a gap would silently bind null or nothing; where a default is known it inlines a copy of the expression that can drift from the database. Both are a quiet change of meaning.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). Seen on `insert into "STUDENTKORT"`.

## Roadmap entries

R103 (lift jOOQ column defaults onto input fields) assumed the `DSL.defaultValue` emission was the correct runtime story; its body already carries a retraction note pointing here. Its Spec should be read against the rule above: an absent field is omitted (single row, uniform batch) or written as `DEFAULT` (mixed-batch gap), and in neither case does Graphitron know the default's value.

## Reviewer findings

### Round 1 (Spec → Ready review): request revisions

Reviewer session `session_01UCwXDMXy66NFBUmoPeKTkN`, 2026-10-12.

The goal reads cleanly: an INSERT stops naming columns the client left out, so a single-row INSERT and a uniform bulk INSERT never send `DEFAULT`, and Oracle views accept them. Every symbol, call site, test class, fixture field and manual line the plan names exists as named, the five INSERT-values call sites are the complete set, and the paired `LinkedHashMap` emitter extends the shape UPDATE already has. One gap fails question 1 (viability of the bulk rule as written); it is a fork the author settles, so it is not settled here.

**Finding 1: a bulk INSERT whose rows all leave out every field loses rows.** The bulk rule makes the column list the union, over rows, of supplied columns. When every input field is nullable (the spec itself says this is reachable, and nothing in the classifier rejects it) and every row is `{}`, the union is empty. Checked against jOOQ 3.20.11 (the pinned version), `insertInto(t, List.of()).valuesOfRows(List.of(DSL.row(List.of()), DSL.row(List.of())))` renders `insert into "film" default values` for both `POSTGRES` and `DEFAULT` dialects: one row, not two. The fetcher would silently insert one row for an N-row input, and `RETURNING` would yield one record where the caller expects N. Today's static column list inserts N rows of `(default, ...)`, so this is a regression the plan introduces, and none of the listed tests would catch it. The single-row all-absent case is fine (`default values` inserts the one row asked for) and the spec already names its Oracle residual.

To satisfy: say what a bulk INSERT does when no row supplies any column, and pin it with an execution test (a `[X!]!` of two or more empty rows inserts and returns that many rows). Options the author might weigh, without prescribing one: fall back to the full plan column list with `DEFAULT` cells for that case (today's behaviour, with the same Oracle-view residual as a mixed batch, named in the manual); issue one `default values` statement per row; or reject at runtime. Whichever is chosen, the bulk rule's prose and the manual bullet should cover it.

*Author response (session `session_01UCwXDMXy66NFBUmoPeKTkN`, 2026-10-12):* settled on widening. The bulk rule gains a third case: a batch of more than one row whose union is empty names every plan column with `DEFAULT` cells (today's statement, N rows); a one-row batch keeps `default values`. The Implementation § Bulk step computes the widening, the manual bullet states it with the Oracle-view caveat, and Tests adds a two-empty-rows case on a new `film_bookmark` sibling fixture, since every all-nullable INSERT input in the sakila schema targets `film_endorsement`, whose `endorsed_film` is `NOT NULL`. Per-row statements and runtime rejection are recorded under Other solutions.

**Non-blocking.** The `FetcherPipelineTest` replacement is described as "a pipeline-tier check that the fetcher compiles"; the pipeline tier asserts on generated source, compilation is the sakila-example build. Asserting the new shape (`valuesOfRows` over a `DSL.row(` of a collection, no `DSL.defaultValue(` on the single-row arm) would keep the test meaningful.

*Author response:* taken. Tests now names the replacement assertions: included columns into `insertInto`, `DSL.row(` over a collection into `.valuesOfRows(` on the bulk arm, no `DSL.defaultValue(` on the single-row arm.

Verdict: stays in Spec.

### Round 2 (Spec → Ready review): request revisions

Reviewer session `session_01EWh2ntN1Nsqeq3Y2aThzQQ`, 2026-10-12.

The goal reads cleanly and is viable: a single-row INSERT, and a bulk INSERT whose rows supply the same fields, stop naming columns the client left out, so no `DEFAULT` reaches Oracle and an INSERT into a view stops failing; mixed batches keep today's statement and say so. Round 1's finding is resolved. The widened empty union, `insertInto(t, Collection)`, `.values(Collection)`, `.valuesOfRows(Collection<RowN>)` and `DSL.row(Collection)` all render as the spec says against jOOQ 3.20.11. The paired map emitter extends the UPDATE `SET` map shape rather than standing beside it. One contradiction fails question 2.

**Finding 1 (question 2): the Retired vocabulary retires a mechanism the bulk plan keeps.** The bulk rule and Implementation § Bulk keep a presence-gated cell on the bulk arm. For each included column, a row that supplies it binds its value; a row that leaves it out gets `DSL.defaultValue(...)`. Which batches are uniform is only known at runtime, so the generated bulk body still carries that `present ? DSL.val : DSL.defaultValue` choice for every included column. `emitInsertAgreementPrep`'s "no writer present" arm survives there too (the spec drops it only on the single-row path). Yet § Retired vocabulary declares "per-cell missing-vs-null dispatch" and its ternary retired for INSERT ("the term survives for nothing"). It also retires "DEFAULT-resolved cell" in `buildInsertDecodeLocals`' javadoc and the INSERT half of `DmlBulkMutationsExecutionTest`'s class javadoc, and both still describe the bulk arm accurately after this lands. § Tests has the same root: it calls `FetcherPipelineTest`'s `.contains("org.jooq.impl.DSL.defaultValue(")` the retired cell shape, but the bulk `createFilms` body keeps emitting it. The section is load-bearing. Under `roadmap/workflow.adoc` § Retirement sweep, the Done reviewer greps for these terms, and terms that keep surviving graduate into `RetiredVocabularyGuardTest`. As written, the implementer is told to remove prose that stays true, and the Done sweep flags code the plan requires.

To satisfy: scope the retirement to what actually goes. That is the ternary on the single-row and per-row-record paths, the static typed column list, and the five named emitters. Then say what survives on the bulk arm (the gap cell) and under which name, so the sweep has a correct query. Bring the `FetcherPipelineTest` sentence into line: the bulk body may still contain `DSL.defaultValue(`; the single-row body must not.

Verdict: stays in Spec. Nothing else blocks. With the section rescoped, the next pass should be a sign-off.

*Author response (session `session_01EWh2ntN1Nsqeq3Y2aThzQQ`, 2026-10-12):* taken. Retired vocabulary now separates what retires from what survives. Retiring: the five emitters, the static typed column list, and the presence-gated `DSL.defaultValue` cell on the single-row INSERT, UPSERT and per-row record paths. The name "per-cell missing-vs-null dispatch" and its paraphrases also retire everywhere; their current habitats are listed by file and symbol, including `GraphQLQueryTest` and the `schema.graphqls` comment that round 2 did not name. The surviving bulk mechanism is named the *gap cell*, and the section gives a sweep query (in `TypeFetcherGenerator`, `DSL.defaultValue` is emitted only by the bulk gap-cell emission). "DEFAULT-resolved cell" is rewritten rather than deleted. Tests now says the bulk `createFilms` body still contains `DSL.defaultValue(` and the single-row body must not, adds `GraphQLQueryTest`'s two single-row `createFilm` tests to the must-pass list, and keeps the `film_bookmark` empty-row cases out of the shared database.

### Round 3 (In Review → Done review): request rework

Reviewer session `session_015XkBS1F3mkRKspsdVUztd9`, 2026-10-13.

Question 1 holds. The delivered tree is the change the spec approved. `buildSingleRowInsertValues` and `buildBulkInsertValues` each walk `insertColumnPlan` once and pair every column with its cell by construction. All five INSERT-values sites go through them. `DSL.defaultValue` is emitted only by the two bulk gap cells. The empty-union widening, the single-row omission of a shared column with no writer present, and the per-row record path all match the plan. The manual bullet states the three Oracle-view residuals. The two deviations (an imperative row loop, one-pass presence) are recorded under Implementation notes and do not change the statement. The retirement sweep is clean: no retired name survives outside dated audits and R103's retracted premise. Question 2's evidence exists, and it is the right evidence. Seven new `DmlBulkMutationsExecutionTest` cases read the rendered SQL. The four rewritten `DmlSqlBaselineTest` baselines pin the uniform, mixed and discriminated INSERTs. The must-pass list passes. The sis Oracle IT run, which the Tests section names as the confirming evidence, cannot exist in-tree; this verdict does not rest on it.

**Finding 1 (approval precondition, test tier): the rewritten `FetcherPipelineTest` block adds code-string assertions on generated method bodies.** `dmlInsertField_bulkInput_emitsSuppliedColumnsWithGapCellsAndEmptyListShortCircuit` asserts `createFilms.code().toString()` contains `insertCols.add(`, `.insertInto(filmTable, insertCols)`, `org.jooq.impl.DSL.row(cells)`, `.valuesOfRows(insertRows)` and `org.jooq.impl.DSL.defaultValue(`. It adds a new assertion that the `createFilm` body contains `insertCols.put(`, `.insertInto(filmTable, insertCols.keySet())` and `.values(insertCols.values())`, and lacks `DSL.defaultValue(`. `development-principles.adoc` bans code-string assertions on generated method bodies at every tier, and the Done gate holds the delivery to that ban. The spec's Tests paragraph asked for exactly these assertions, following round 1's non-blocking note, so this is a spec defect that both Spec rounds missed, not implementer drift. It still lands in the delivered tests. These assertions also add nothing to the evidence: every property they claim is already pinned on the rendered SQL. The single row and the uniform batch carry no `default` cell (`createFilm_omittedFieldIsLeftOutOfTheColumnList`, `createFilms_uniformRowsOmittingAFieldLeaveItOutOfTheColumnList`). A mixed batch writes its gap as `DEFAULT` (the discriminated `DmlSqlBaselineTest` baseline). Meanwhile they bind the local names `insertCols`, `insertRows` and `cells`, so they would break on a rename that changes no behaviour.

To satisfy: remove the generated-body string assertions this item authored or rewrote in that test. These are the INSERT column, row and cell lines on the `createFilms` body and the whole `createFilm` body assertion. Leave the INSERT shape to the execution tier, which already pins it. Whether the test's older assertions (list cast, empty-list short-circuit) stay is outside this item. Amend the spec's Tests paragraph to say the pipeline block no longer asserts the INSERT statement shape, and why.

**Non-blocking.** The first verification build failed once, on `OptionalNodeIdProjectionExecutionTest.anOmittedNodeIdReachesAConditionMethodAsNull` (`expected [1, 2, 3, 4, 5, 82] but was [..., 82, 83]`), and passed on one rerun of `graphitron-sakila-example`. That test reads the shared `film` table across separate statements, so a film that a concurrently running test inserts and then deletes can appear in one read and be gone by the next. The failure is not this item's. This item's four new committed-film cases (`createFilm_omittedField…`, `createFilms_uniformRows…`, `createFilms_mixedRows…`, `createFilmsPayload_mixedRows…`) do widen that window. Running them in `inRolledBackTransaction`, as the bookmark cases already do, would shrink it, but the race itself belongs to that test.

Verdict: back to Ready.

*Implementer response (session `session_015XkBS1F3mkRKspsdVUztd9`, 2026-10-13):* taken. The bulk INSERT block in `FetcherPipelineTest` is now `dmlInsertField_bulkInput_bindsListArgumentAndShortCircuitsEmptyList`. It drops every INSERT statement-shape assertion (`insertCols`, `insertRows`, `DSL.row(cells)`, the presence and cell lines, `DSL.defaultValue(`) and the `createFilm` body assertion along with the `createFilm` field it was added for. It keeps the list binding, the empty-list short-circuit and the return type. Its comment, and the section comment above it, point the INSERT shape at the execution tier. The Tests paragraph says the same and why. The Implementation notes' ratchet line was also corrected to the shipped values (case patterns 61 to 57). This session implemented the rework, so it cannot review this item at Done. The non-blocking race is left to the test that owns it.
