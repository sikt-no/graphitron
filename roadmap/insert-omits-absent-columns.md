---
id: R1017
title: "An INSERT leaves absent input fields out of its column list instead of writing DEFAULT"
status: Spec
bucket: bug
priority: 5
theme: mutation-write
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# An INSERT leaves absent input fields out of its column list instead of writing DEFAULT

## Goal

A generated `@mutation(typeName: INSERT)` works against a view on Oracle. Today, for each input field the client leaves out, the INSERT still names the column and writes the SQL keyword `DEFAULT` into its `VALUES` cell (rendering `values (:1, :2, :3, default, cast(:4 as date), ...)`). Oracle rejects an explicit column default against a view (ORA-32575, "Explicit column default is not supported for modifying views"), and most sis API tables are views. When this lands, a single-row INSERT and a bulk INSERT whose rows all leave out the same fields name only the columns the client supplied, so the database applies its own default without being asked to by name. This is what Graphitron 9 did, and what the user manual already promises: `docs/manual/reference/directives/mutation.adoc` § Constraints says absent optional fields "are omitted from the column list (`INSERT`)".

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

- **Every row supplies the same fields** (the common case, and always true for a one-element list): no `DEFAULT` is written anywhere. This is the case the Oracle view needs.
- **Rows differ** (mixed shape): the statement means exactly what it means today, one round trip, one atomic statement, `RETURNING` in input order, with `DEFAULT` only in the gap cells. Against an Oracle table this works; against an Oracle view it still fails with ORA-32575, loudly, at the database. That is the one named residual of this item.

INSERT deliberately accepts a mixed batch where bulk UPDATE rejects one (`buildUniformShapeGuard`, "bulk input rows must share the same present-key set"). The asymmetry is principled, not drift: a gap in an INSERT row has a per-row meaning (that row takes the column default), while bulk UPDATE shares one `SET` clause across all rows, so a field present in one row and absent in another has no single meaning for it. Nobody should later "fix" one to match the other.

An INSERT whose every field is absent (reachable only when every input field is nullable) has an empty column list; jOOQ renders it as `insert into t default values`. Whether jOOQ's Oracle rendering of that shape names columns with `DEFAULT` (and so hits ORA-32575 on a view) is checked at implementation time with the jOOQ edition sis builds against; if it does, it is a second named residual, recorded in the manual line below, not worked around here.

## Implementation

All edits are in `TypeFetcherGenerator`.

**One emitter for column list and cells, paired by construction.** Today `buildInsertColumnList` / `buildInsertColumnListDeduped` emit a static, typed column list (`insertInto(t, T.C1, T.C2, ...)`) and `buildPerCellValueList` / `buildPerCellValueListDeduped` / `emitInsertCell` emit the parallel `present ? DSL.val(...) : DSL.defaultValue(...)` cells; the two stay aligned only because both walk `flattenInsertLeaves`. Replace the four with one emitter that walks the INSERT column plan once (`insertColumnPlan`, which already yields one entry per distinct column in a fixed order, deduping columns written by more than one input field) and, per column, emits one presence-gated `put` of column to cell into a `LinkedHashMap<Field<?>, Field<?>>`. The column list is the map's `keySet()` and the cells its `values()`, so they cannot misalign, which replaces the compile-time alignment check the typed `insertInto(t, c1..cN).values(...)` overload gave. This is the shape UPDATE's dynamic `SET` map already has (`emitSetMapPuts`, `setColumnPresenceGate`); reuse its presence-gate helpers where the semantics coincide rather than adding a third presence walk. INSERT has no `@onExplicitNull` rule to apply, so the UPDATE put walk is a model, not necessarily a drop-in.

Per column, the gate and the cell follow the existing carriers:

- A plain column field (`InputField.ColumnBackedField` with `CallSiteExtraction.Direct`): gate is `nestedContainsKeyExpr` over the leaf's access path, cell is `DSL.val(<wire value>, T.C.getDataType())`. The access path covers nested grouping inputs (an input-typed field that only groups columns of the same table), as today.
- A NodeId-decoded field or FK reference field (`ColumnBackedField` with `NodeIdDecodeKeys`, `ColumnBackedReferenceField`), whose encoded ID decodes to one or more key columns: gate is the source field's presence, cells read `insertKey_<fi>.value<slot+1>()` from the decode locals `buildInsertDecodeLocals` already emits.
- A shared column (one written by more than one input field, `OverlapColumn.shared()`): gate is the disjunction of its contributors' presence; the cell is the coalesced value `emitInsertAgreementPrep` already computes after `requireColumnAgreement`. The prep's "no writer present" arm (`DSL.defaultValue`) goes away on the single-row path, since the column is then simply not put.

**Single row.** The map is built in `postInGuard` beside the decode locals and the chain becomes `.insertInto(t, cols.keySet()).values(cols.values())` (jOOQ's `InsertValuesStepN`). `DSL.defaultValue` is never emitted.

**Bulk.** First compute, per plan column, whether any row supplies it (`in.stream().anyMatch(row -> <gate(row)>)`), then build each row's cells in plan order over the included columns, a present cell binding its value and a gap cell `DSL.defaultValue(...)`, wrapped in `DSL.row(Collection)`; the chain becomes `.insertInto(t, <included columns>).valuesOfRows(<rows>)`. The bulk lambda becomes a block lambda unconditionally (the `anyNodeIdCarrier` single-expression arm retires). For a uniform batch no gap cell exists, so `DEFAULT` is never rendered.

**Call sites.** Every INSERT-values site moves onto the shared emitter, so there is one INSERT path:

- `buildMutationInsertFetcher` (single and bulk);
- `buildMutationUpsertFetcher` (single and bulk). UPSERT is rejected on Oracle by its `DialectRequirement`, so this is consistency rather than the fix; the `.onConflict(...).doUpdate().set(setsUpdate)` half is untouched, and its bulk arm already runs the uniform-shape guard in `doUpdate` mode;
- `buildRecordInsertChain` and `buildRecordUpsertChain`, the payload-returning variants;
- `buildBulkRecordPerRowInsertBody`, which issues one statement per row and therefore takes the single-row shape per row: it never writes `DEFAULT`, even for a mixed batch.

**Manual.** Rewrite the last bullet of `docs/manual/reference/directives/mutation.adoc` § Constraints to say what ships: absent optional fields are omitted from the column list of a single-row INSERT and of a bulk INSERT whose rows supply the same fields; in a bulk INSERT whose rows differ, the column list covers every field some row supplies and a row that leaves one out writes `DEFAULT` for it, which Oracle rejects against a view. Add the all-absent residual there too if the implementation-time check finds it.

## Tests

The behaviour is pinned at the execution tier by observing the rendered SQL, not by asserting strings in the generated Java. `DmlBulkMutationsExecutionTest` already installs an `ExecuteListener` (it counts statements); extend it to capture the rendered SQL, and add:

- `createFilm` with `rentalDuration` omitted: one statement, its column list does not name `rental_duration`, it contains no `default` cell, and the row reads back `rentalDuration: 3`.
- `createFilms` with both rows omitting `rentalDuration`: the same three assertions for the bulk statement.
- `createFilms` with one row `rentalDuration: 5` and one omitting it: one statement, column list names `rental_duration`, results `[5, 3]` in input order. This pins the mixed-shape rule.
- `createFilmsPayload` (the bulk per-row record path) with the same mixed input: no statement contains a `default` cell.
- One overlap case on `insertEndorsementOverlap` (the `film_endorsement` INSERT where `endorsed_film` is written by two input fields, exercised in `NodeIdValueAgreementExecutionTest`) with every writer of the shared column absent: the column is not named. If the fixture's nullability does not allow both writers to be absent, add a sibling fixture that does.

The existing `createFilms_omittedFieldUsesColumnDefault` and `createFilms_explicitNullRaisesError` (a mixed batch whose present cell is an explicit null, still one statement) must pass unchanged, along with `NestedInputDmlExecutionTest`, `SelfFkNodeIdInsertExecutionTest`, `NodeIdValueAgreementExecutionTest` and `SingleRecordPayloadDmlTest`, which cover the nested, NodeId, overlap and payload shapes the emitter rewrite touches. `FetcherPipelineTest`'s assertion that the bulk INSERT body `.contains("org.jooq.impl.DSL.defaultValue(")` (the "per-cell missing-vs-null dispatch on row.containsKey" block) asserts the retired cell shape; replace it with a pipeline-tier check that the fetcher compiles and classifies, leaving the SQL shape to the execution tier.

The execution tier runs on PostgreSQL, which accepts `DEFAULT` against views, so ORA-32575 itself is not reproduced in-tree. The property the tests pin (no `DEFAULT` keyword for single rows and uniform batches) is dialect-independent; the sis Oracle IT run is the confirming evidence at the Done gate.

## Retired vocabulary

- "per-cell missing-vs-null dispatch" (and the `containsKey ? DSL.val : DSL.defaultValue` ternary it names) for INSERT; the term survives for nothing.
- `buildPerCellValueList`, `buildPerCellValueListDeduped`, `emitInsertCell`, `buildInsertColumnList`, `buildInsertColumnListDeduped` (replaced by the one paired emitter).
- "DEFAULT-resolved cell" in `buildInsertDecodeLocals`' javadoc, and the INSERT half of `DmlBulkMutationsExecutionTest`'s class javadoc ("containsKey-gated DEFAULT/val dispatch").

## Other solutions we've considered

- **One INSERT per distinct row shape.** Removes `DEFAULT` from mixed batches too, but turns one statement into several: the fetcher's single `dsl.insertInto(...)...returningResult(...).fetch(...)` chain would need a transaction scope for atomicity and a reassembly step to restore input order for `RETURNING`. The cost lands on every bulk INSERT to serve the mixed-shape-against-an-Oracle-view case, which has not been observed. Revisit if sis hits it.
- **Reject mixed batches, as bulk UPDATE does.** Loud, and simple, but it turns batches that work today on PostgreSQL and on Oracle tables into errors everywhere, and the INSERT gap has a meaning UPDATE's does not (see The bulk rule).
- **Write the catalog default instead of the keyword** (`DataType.defaultValue()` from the jOOQ catalog). A view's columns carry no default in the catalog, so a gap would silently bind null or nothing; where a default is known it inlines a copy of the expression that can drift from the database. Both are a quiet change of meaning.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). Seen on `insert into "STUDENTKORT"`.

## Roadmap entries

R103 (lift jOOQ column defaults onto input fields) assumed the `DSL.defaultValue` emission was the correct runtime story; its body already carries a retraction note pointing here. Its Spec should be read against the rule above: an absent field is omitted (single row, uniform batch) or written as `DEFAULT` (mixed-batch gap), and in neither case does Graphitron know the default's value.
