---
id: R141
title: "Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field"
status: Spec
bucket: feature
priority: 2
theme: mutations-errors
depends-on: [error-handling-parity]
---

# Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field

## Target shape

```graphql
extend type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: [OpprettKvotesporsmalPreutfyllingInput!]!
    ): KvotesporsmalPreutfyllingPayload! @mutation(typeName: INSERT)
}
type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]   # data channel
    errors: [SomeError!]!                                      # error channel (R12)
}
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}
type KvotesporsmalPreutfylling implements Node @key(fields: "id") @node(keyColumns: ["kvotesporsmal_preutfylling_kode"]) @table(name: "kvotesporsmal_preutfylling") {
    id: ID! @nodeId
}
```

Bulk `@table` input, single payload carrier, list-shaped `@table`-element data field paired to the same `@table` as the input. This is the **main shape consumers want from a mutation payload**: the carrier is the natural seam for sibling fields the platform exposes alongside the read-back. R141 admits **exactly two channels** on the carrier — the **data channel** (the list-shaped read-back) and the **error channel** (R12's existing `errors: [SomeError!]!` machinery). Any other field on the carrier (e.g. `affectedRowCount`, `clientMutationId`) is **rejected at classify time** with a message naming the missing classifier rule and the future Backlog item that would admit it. This keeps `UnclassifiedField` meaning "the classifier found no home" everywhere; it does not become a tolerated steady state on payload carriers. Future Backlog items can promote individual sibling-field shapes (`affectedRowCount`, `clientMutationId`) to admitted classifications by extending the carrier classifier; R141 forecloses none of them and names the seam.

## Why R138's rejection doesn't apply

R138 rejects this shape via Invariant #15, naming `TooManyRowsException` as the runtime mechanism. That mechanism is real for the *singleton-data-field* carrier arm R138 closes (`Payload { film: Film }`): N inputs have no honest landing in a one-row slot, and `valuesOfRows(...).fetchOne()` throws. It is **not real here**: the data field is list-shaped (`kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]`), so N inputs map to N rows inside the carrier's list. There is no "drop", no `TooManyRowsException`, and no contract violation.

The two arms differ on a single classifier predicate — `dataField.wrapper().isList()` — and the existing carrier-resolution machinery (`SingleRecordCarrierShape`, `DataElement.Table.wrapper`) already carries that bit. R141 routes the list-data-field arm to a new sealed leaf; R138's singleton arm and Invariant #15 are unchanged.

## Position in the model

A new sealed leaf `MutationField.MutationBulkDmlRecordField`, sibling to `MutationDmlRecordField` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MutationField.java:141-159`). Components mirror the existing leaf with two cardinality flips and one preserved invariant:

```java
/**
 * Bulk-input DML carrier with a list-shaped @table data field. The classifier admits exactly
 * (tableInputArg.list() == true, dataField.wrapper().isList() == true, kind ∈ {INSERT, UPDATE, UPSERT})
 * and pairs the input cardinality to the data field's element type via the existing
 * load-bearing classifier check
 * {@code mutation-dml-record-field.data-table-equals-input-table}
 * (R141 extends this key to cover the new leaf).
 *
 * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
 * {@code input[i]} for all {@code i ∈ [0, N)}. The emitter satisfies this via batched
 * per-row DML inside one transaction (N+1 statements), collecting PKs in input order and
 * iterating a PK-keyed map of the response-SELECT in that order to build the response. Any
 * future single-statement emit refinement (e.g. an ordinal-preserving Postgres contract)
 * must preserve this contract; the leaf's documented invariant is the type-system anchor.
 *
 * <p><b>DELETE rejection.</b> Mirrors {@link MutationDmlRecordField}: DELETE-with-payload-return
 * is incorrect by construction (the row is gone before the response SELECT can read it).
 * The compact-constructor invariant is belt-and-braces under the upstream classifier check
 * that already rejects DELETE before this record is constructed.
 */
record MutationBulkDmlRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,        // carrier wrapper is single (non-null or nullable)
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,  // .list() == true (invariant)
    DmlKind kind,                                     // INSERT / UPDATE / UPSERT; DELETE rejected
    Optional<ErrorChannel> errorChannel               // R12 slot, unchanged
) implements MutationField {
    public MutationBulkDmlRecordField {
        if (kind == DmlKind.DELETE) {
            throw new IllegalArgumentException(
                "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — "
                + "DELETE-with-payload-return is rejected at classify time "
                + "(returning pre-deletion state is incorrect by construction).");
        }
        if (!tableInputArg.list()) {
            throw new IllegalArgumentException(
                "MutationBulkDmlRecordField requires bulk @table input "
                + "(tableInputArg.list() == true); single-input belongs on MutationDmlRecordField.");
        }
    }
}
```

**Why `DmlKind` enum, not sealed-on-kind permits.** `MutationDmlRecordField` (the singleton sibling) currently carries `DmlKind kind` as an enum (`MutationField.java:147`). R141 mirrors that style for intra-pair consistency: making R141 sealed-on-kind while the sibling stays flat creates a structural asymmetry between two leaves that share every classifier predicate except data-field cardinality. The principles-aligned shape (sealed-on-kind, mirroring `DmlTableField`'s `MutationInsertTableField` / `MutationUpdateTableField` / `MutationUpsertTableField` permits — see `changelog.md` R22 entry) is the right long-term target for **both** record-carrier leaves; a sibling Backlog item `dml-record-carrier-sealed-on-kind` lifts both `MutationDmlRecordField` and `MutationBulkDmlRecordField` together once R141 lands. Splitting the refactor from R141 avoids divergence and keeps each item focused.

**Why a flat sibling of `MutationField`, not a `DmlRecordCarrierField` sub-taxonomy.** The principles-aligned shape would introduce sealed `DmlRecordCarrierField extends MutationField permits MutationDmlRecordField, MutationBulkDmlRecordField` with the shared `(returnType, tableInputArg, kind, errorChannel)` component bag — the same move `DmlTableField` made for direct-return arms. R141 leaves both leaves as flat siblings of `MutationField` for the same reason as the enum-vs-sealed-on-kind decision above: the sub-taxonomy lift is a structural refactor that R141 should not be coupled to. A sibling Backlog item `dml-record-carrier-sub-taxonomy` lifts both leaves under the new sealed parent post-R141. The two refactor items (sub-taxonomy + sealed-on-kind) are independent and can land in either order.

Add `MutationBulkDmlRecordField` to the sealed `permits` list on `MutationField` (line 18-19). It does **not** join `DmlTableField`; that sealed supertype permits the four direct-DML records and shares the `(returnType, encodeReturn)` shape, which `MutationBulkDmlRecordField` does not have (its return is the carrier, not the DML's direct return).

## Carrier admission rules

In `FieldBuilder` carrier-resolution (the R75 Phase 1 path at `FieldBuilder.java:2664-2685`, which `MutationDmlRecordField` already uses), extend the data-field cardinality switch:

| `tableInputArg.list()` | `DataElement.Table.wrapper().isList()` | Outcome |
|---|---|---|
| `false` | `false` | `MutationDmlRecordField` (R75 Phase 1, existing) |
| `false` | `true`  | rejected — single input, list data field has nothing to fill it from |
| `true`  | `false` | rejected via R138's lifted Invariant #15 |
| `true`  | `true`  | **`MutationBulkDmlRecordField` (R141, new)** |

The single-input + list-data-field rejection is a fresh rule (not currently covered by R138 or R75). It is **a new predicate, not an extension of R138's lifted Invariant #15**: R138's predicate is `listInput && !returnType.wrapper().isList()` (the carrier-wrapper arm); R141's new rejection predicate is `!listInput && carrier.dataField.wrapper().isList()` (the data-field cardinality arm). The two predicates together complete the input-cardinality / data-field-cardinality 2×2 matrix and warrant **a new Invariant family** in the same spirit. Name it Invariant #16 ("carrier data-field cardinality matches input cardinality") and surface the rejection message as: `"@mutation(typeName: <kind>) with a single @table input cannot return a list-shaped data field on the carrier ('<carrierName>.<dataField>'); list-shaped output requires bulk input (Invariant #16)"`.

The same-`@table` invariant on the data-channel element type is already enforced by the load-bearing classifier check `mutation-dml-record-field.data-table-equals-input-table`. R141 extends the check key (or files a sibling key with the same predicate body) to cover the new sealed leaf; the check itself is unchanged in shape.

**Carrier sibling-field admission rule.** The carrier classifies cleanly iff its field set is **exactly** `{ data-channel field, errors-channel field }`, where each field's shape matches its channel's classifier rule (R141 for data; R12 for errors). The errors-channel field is optional (R12's existing `Optional<ErrorChannel>` semantics carry over). Any **additional** field on the carrier — `affectedRowCount`, `clientMutationId`, an unrelated scalar, a nested type — **rejects the carrier at classify time** with a descriptive message: `"Carrier '<carrierName>' has unrecognised field '<fieldName>'; only the data channel (one list-shaped @table data field) and the error channel (R12 errors-shaped field) are admitted. Adding support for '<fieldName>' requires a new classifier rule; file a roadmap item for the field shape."`. This keeps `UnclassifiedField` meaning "no classifier rule matches" everywhere; the carrier classifier does not look the other way at unknown siblings, in line with the "validator mirrors classifier invariants" principle. Future Backlog items extend the admitted field set (one item per sibling-field shape: `payload-carrier-affected-row-count`, `payload-carrier-client-mutation-id`); each such item adds a new admission rule to the carrier classifier alongside the data and error channels.

## Order preservation: invariant and emit strategy

**Contract**: `output.data[i]` corresponds to `input[i]` for all i ∈ [0, N). This is a critical correctness invariant. Consumers depend on it for cross-index correlation (e.g., reading `output.errors[i]` against `input[i]`, or pairing client-side state to server response state).

**Why single-statement `valuesOfRows(...).returning(PK).fetch()` doesn't satisfy this.** PostgreSQL's `INSERT ... RETURNING` does not guarantee row order against the input VALUES order — the SQL standard is silent, and `RETURNING` is documented as returning "the inserted, updated, or deleted rows" without an ordering claim. Empirically Postgres often preserves input order, but the planner is free to reorder, and parallelism (or RLS, triggers, or BEFORE-INSERT hooks rearranging rows) can break the pattern silently. Relying on it is an "it works on my machine" contract.

Single-statement workarounds are all sharp:

- **`unnest(...) WITH ORDINALITY` + writable CTE projecting ordinal back**: requires the target table to carry an ord column. Real tables don't.
- **Post-hoc correlation via input-data uniqueness**: brittle when input rows aren't unique on the columns RETURNING surfaces.
- **`array_position` over a captured array literal**: same uniqueness problem; quadratic.

**Emit strategy: batched per-row DML inside one transaction.** The fetcher loops the input list, executes one `INSERT ... RETURNING <pk>` / `UPDATE ... RETURNING <pk>` / `INSERT ... ON CONFLICT ... RETURNING <pk>` per row, collects results into a `List<RecordN<PK>>` whose iteration order is the input order (Java preserves this trivially). One follow-up `SELECT ... WHERE pk IN (...)` runs the response-projection for the data channel; the result is keyed back into a PK-indexed map, then iterated by the PK list in input order to build the response.

Cost: N+1 statements per mutation (N DML round-trips + 1 read-back SELECT), all inside one `dsl.transactionResult(...)`. For typical mutation N (≤ 50), this is acceptable; for N in the hundreds, this is still acceptable for the explicit-batch case GraphQL mutations represent. A future optimisation could lift to single-statement emit if Postgres adds a ordinal-preserving RETURNING contract; nothing in R141's design forecloses it.

This is a deliberate cost trade-off: correctness invariant first, throughput optimisation later if profiled.

## Response-SELECT (data channel)

After the DML batch, run one `SELECT <projectedColumns> FROM <table> WHERE <pkColumn> IN (?, ?, ..., ?)` against the PKs collected from the DML batch. Project into the data field's element type via the existing single-carrier read-back machinery (the `SingleRecordCarrierShape.DataElement.Table` projection path R75 Phase 1 introduced); the only difference is the loop iterates `pkList` (built from the DML batch) instead of a single `pk`.

The map-by-PK indirection handles the case where the response-SELECT returns rows in any order; the input-ordered iteration over `pkList` produces an input-ordered output `List<Map<?,?>>` (the data field's emit type), which graphql-java's value mapper serialises into the list-shaped data field.

For UPDATE / UPSERT, the input rows already carry `@lookupKey` columns identifying the target row; the DML emits `RETURNING <pk>` and the same response-SELECT runs. For INSERT, the PKs are generated by the DB; `RETURNING <pk>` surfaces them, the same response-SELECT runs.

## Error channel: reuse R12 unchanged

R12 (`error-handling-parity`, Ready) already specs the carrier-side `errors: [SomeError!]!` field shape, the `ErrorChannel` record, the `PayloadAssembly` reflection over a developer-supplied payload class, and the catch-arm wiring through `catchArm(outputPackage, errorChannel)`. R12 currently lifts `Optional<ErrorChannel> errorChannel` onto `MutationDmlRecordField` (already present at line 148); R141 adds the same slot to `MutationBulkDmlRecordField` with identical semantics.

**Load-bearing claim on R12's resolution shape.** R141's temporal decoupling from R12 ("R141 may land before or after R12") requires that R12's carrier-side `resolveErrorChannel` walk (the per-field carrier-classifier reflection described at `error-handling-parity.md` §2c) is **sealed-root-uniform over the data field's wrapper cardinality** — i.e., R12 keys on the carrier's payload-class shape and its `errors`-shaped field index, not on whether the data field beside it is single- or list-shaped. R12's spec body does not branch on data-field cardinality at the resolution layer; R141 inherits that uniformity by construction. If a future R12 revision adds a data-field-cardinality fork at resolution time, R141 would need a corresponding update — flag in R12's plan body as a load-bearing constraint, and add `@DependsOnClassifierCheck("carrier-error-channel.wrapper-uniformity")` (or equivalent key) pairing R12's producer and R141's consumer if that branch ever materialises.

**Semantics**: atomic-transaction, flat-error-list. If any row in the batched DML throws (constraint violation, type mismatch, RLS denial, etc.), the catch arm rolls back the entire transaction, maps the exception through `errorChannel`'s configured `ErrorRouter`, and emits a payload with the data channel empty (`[]`) and the error channel populated with the mapped error type(s). Partial application is **not** in scope: R12's flat error model and the transaction-rollback contract are load-bearing.

**Per-row error correlation is out of scope.** A future plan can extend the error channel to per-row error semantics (with index correlation matching the data-channel ordering); R141 explicitly defers this. The reasoning: per-row errors require either non-atomic application (rejected by R12's design) or in-flight validation before DML emission (a different kind of error path, also a separate plan). R141's contract is "all-N succeed (data channel) or none commit (error channel)".

The error-channel field on the carrier classifies via R12's existing machinery; R141 makes no changes to the carrier classifier's error-channel handling. The new sealed leaf is just another consumer of `Optional<ErrorChannel> errorChannel()`.

## Mutation-kind coverage

INSERT, UPDATE, UPSERT all admitted. DELETE rejected at classify time via the compact-constructor (same reasoning as `MutationDmlRecordField`: returning pre-deletion state is incorrect by construction; the row is gone before the response SELECT can read it). Three emit shapes, one shared response-SELECT:

- **INSERT**: per-row `dsl.insertInto(table, cols).values(vals).returningResult(pkCols).fetchOne()` inside a loop. PKs flow into the response-SELECT.
- **UPDATE**: per-row `dsl.update(table).set(cols).where(buildLookupWhere(tia, row)).returningResult(pkCols).fetchOne()`. Reuses the existing `@lookupKey`-driven WHERE builder from `MutationUpdateTableField`.
- **UPSERT**: per-row `dsl.insertInto(table, cols).values(vals).onConflict(<lookupKeys>).doUpdate().set(<nonLookup>).returningResult(pkCols).fetchOne()`. Empty-SET case emits `.doNothing()` (mirrors `MutationUpsertTableField`). Oracle-dialect runtime guard (jOOQ translates to `MERGE INTO` with semantics drift) carries over from the existing UPSERT machinery.

Three emitters in `TypeFetcherGenerator` — `buildMutationBulkDmlRecordInsertFetcher`, `...UpdateFetcher`, `...UpsertFetcher` — or one parameterised emitter dispatching on `DmlKind`. The parameterised shape is the natural choice (mirrors the R22 `buildDmlFetcher` skeleton at `TypeFetcherGenerator.java`); the three kind-specific helpers differ only in the per-row statement and the WHERE/SET clauses.

## Classifier-tier truth-table coverage

Three rows total — one admitted, two rejections covering the two new mismatch cells. Mirror R138's `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` (lines 5524-5537 region) structure for each.

```java
DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED(
    "DML INSERT with listed input + plain SDL Object payload return + list-shaped @table data field → MutationBulkDmlRecordField",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!] }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation {
        createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT)
    }
    """,
    schema -> {
        var f = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.kind()).isEqualTo(DmlKind.INSERT);
        assertThat(f.tableInputArg().list()).isTrue();
    }),
```

One admitted row covers the mechanism — same one-row-per-mechanism precedent as R138. UPDATE / UPSERT admitted-rows are not added; the kind-switch shares the same classifier path and execution-tier coverage exercises the per-kind emit differences.

The second row covers the new Invariant #16 rejection (single input + list data field):

```java
DML_INSERT_SINGLE_LIST_DATA_REJECTED(
    "DML INSERT with single input + list-shaped @table data field on carrier → UnclassifiedField (Invariant #16)",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!] }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation { createFilmPayload(in: FilmInput!): FilmsPayload @mutation(typeName: INSERT) }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmPayload");
        assertThat(f.reason())
            .contains("single @table input cannot return a list-shaped data field")
            .contains("Invariant #16");
    }),
```

The third row covers the carrier-sibling-field rejection (any field beyond the two admitted channels):

```java
DML_INSERT_LIST_PAYLOAD_UNKNOWN_SIBLING_REJECTED(
    "DML INSERT with bulk input + carrier carrying an unrecognised sibling field → UnclassifiedField (carrier-sibling)",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!], affectedRowCount: Int }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation {
        createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT)
    }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.reason())
            .contains("unrecognised field 'affectedRowCount'")
            .contains("file a roadmap item");
    }),
```

## Execution-tier coverage

One round-trip test in `DmlBulkMutationsExecutionTest` (the same fixture file R134's empty-input regression lives in). Schema fixture mirrors the spec's target shape; assertion structure:

1. Build a payload mutation against a real PostgreSQL via the Sakila harness.
2. Run with `N == 3` input rows whose primary keys / business keys are distinct and **deliberately not sorted** in the natural PK order (e.g., insert rows whose generated PKs would sort 1, 2, 3, but whose business-key columns sort `'c'`, `'a'`, `'b'`).
3. Assert the response's data-channel list is **in input order** (`'c'`, `'a'`, `'b'`), not PK order or natural-key order. This is the load-bearing assertion for the order-preservation contract.
4. Assert all N rows are present (no drops).

A second round-trip with `N == 1` to confirm the bulk emit path doesn't regress the single-input case (the bulk leaf must work for any `tia.list() == true && N >= 1`, including N=1, since the SDL admits it).

An error-channel test is **not** added in R141 — R12 owns the error-channel execution coverage, and R141 makes no changes to R12's machinery. If R12 ships before R141, R141 inherits R12's coverage; if R141 lands first against R12's "Ready" plan body, the error-channel slot is plumbed but the catch arm follows R12's eventual shape.

## R138 fixture status (post-landing)

R138 has shipped (see `changelog.md`). It converted four `SingleRecordCarrierPipelineTest` fixtures and one `GraphitronSchemaBuilderTest.MUTATION_DML_RECORD_FIELD` row from bulk input to single input, and lifted Invariant #15 to `MutationInputResolver.validateReturnType`. R141 does **not** revert those R138 changes — they keep their single-input shape and continue to pin the singleton-carrier path (`MutationDmlRecordField`). The list-data-field bulk case lands at a different sealed leaf (`MutationBulkDmlRecordField`) and gets its own fixtures; the two paths don't share test infrastructure.

R138's `DML_INSERT_LIST_PAYLOAD_REJECTED` row stays as the rejection coverage for the singleton-data-field bulk case (`Payload { film: Film }`). R141's three new rows (`DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` + `DML_INSERT_SINGLE_LIST_DATA_REJECTED` + `DML_INSERT_LIST_PAYLOAD_UNKNOWN_SIBLING_REJECTED`) cover the complementary cells of the cardinality matrix and the carrier-sibling rejection.

## Implementation order

R141 lands **after R138** (which has shipped). R138's lifted Invariant #15 predicate at `MutationInputResolver.validateReturnType` is the predicate R141 routes around: R141's new admitted arm must fire **before** the lifted check so that bulk-input + list-data-field cases are admitted rather than rejected. The classifier ordering is: (1) check whether the carrier shape resolves to `MutationBulkDmlRecordField` (R141); if yes, admit. (2) otherwise, fall through to the lifted Invariant #15 (R138), which rejects bulk-input + non-list-carrier-wrapper cases.

R141 may land **before or after R12**. The `Optional<ErrorChannel>` slot on `MutationBulkDmlRecordField` is structural; if R12 hasn't shipped, the slot is `Optional.empty()` for all carriers (matching R12's stated "`@table`-returning fetchers carry an empty channel" gate at `error-handling-parity.md:162-163`). If R12 has shipped, the slot populates via R12's existing carrier-classifier walk. R141 makes no changes to R12's classifier or runtime.

## Tests

- **L3 (classifier)**: three `GraphitronSchemaBuilderTest` truth-table rows as specified above (`DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED`, `DML_INSERT_SINGLE_LIST_DATA_REJECTED`, `DML_INSERT_LIST_PAYLOAD_UNKNOWN_SIBLING_REJECTED`). Load-bearing assertions: the admitted row resolves to `MutationBulkDmlRecordField` with `tia.list() == true` and `kind == INSERT`; the Invariant #16 row surfaces the cardinality-mismatch reason and family name; the carrier-sibling row surfaces the unrecognised-field message naming the missing classifier rule.
- **L4 (execution)**: one `DmlBulkMutationsExecutionTest` test with N=3 inputs in deliberately-non-PK-order, asserting input-ordered output and all-N presence; one N=1 sanity test against the same bulk leaf path.
- **L4 sealed-coverage**: `GeneratorCoverageTest` (at `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java`, line 43, asserts coverage over `MutationField.class`) picks up the new leaf automatically and fails the build if `TypeFetcherGenerator` lacks a corresponding emitter dispatch. `VariantCoverageTest` covers the model-side variant enumeration on the same axis. Both are existing tests; R141 adds no new sealed-coverage scaffolding.

No new classifier-check pairings are introduced — `mutation-dml-record-field.data-table-equals-input-table` (or its R141-extended key) inherits its existing producer/consumer pair. The compact-constructor invariants (DELETE-rejection, list-input-required) replace would-be classifier checks; the type system carries them.

## Acceptance criteria

- `MutationField` has a new sealed leaf `MutationBulkDmlRecordField` with components `(parentTypeName, name, location, returnType: ResultReturnType, tableInputArg: TableInputArg, kind: DmlKind, errorChannel: Optional<ErrorChannel>)`. Compact-constructor rejects `kind == DELETE` and `tableInputArg.list() == false`.
- The carrier-resolution classifier in `FieldBuilder` routes `(tia.list() == true, dataField.wrapper().isList() == true, kind ∈ {INSERT, UPDATE, UPSERT})` to `MutationBulkDmlRecordField`. The single-input + list-data-field cell (`tia.list() == false, dataField.wrapper().isList() == true`) is rejected with a descriptive Invariant #15-family message.
- The same-`@table` invariant on the data field's element type is enforced for the new leaf (via extension of `mutation-dml-record-field.data-table-equals-input-table` or a sibling key with the same predicate body).
- `TypeFetcherGenerator` has a `buildMutationBulkDmlRecordFetcher` (parameterised on `DmlKind`) that emits per-row DML inside `dsl.transactionResult(...)`, collects PKs in input order, runs one follow-up response-SELECT, and maps results back via a PK-keyed map iterated in input order.
- The error-channel slot is plumbed identically to `MutationDmlRecordField`; the catch arm follows R12's existing shape (no R141-specific error machinery).
- The classifier-tier truth-table carries `DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` (admitted) and `DML_INSERT_SINGLE_LIST_DATA_REJECTED` (rejection of the new mismatch cell).
- The execution-tier test `DmlBulkMutationsExecutionTest` carries one N=3 round-trip asserting input-order preservation against deliberately-non-PK-ordered inputs, and one N=1 sanity test.
- Sibling fields on the carrier beyond the data and error channels classify as `UnclassifiedField`; the carrier still resolves and produces a payload with those fields populated as `null` in the response (existing graphql-java default-value semantics).
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end with the new sealed leaf, the new truth-table rows, and the new execution test.

## Out of scope

- Per-row error correlation (the error channel stays flat-list under R12's contract; per-row errors are a future Backlog item).
- Carrier sibling fields beyond the data and error channels (carrier rejects at classify time; each new sibling shape is a separate Backlog item, e.g. `payload-carrier-affected-row-count`, `payload-carrier-client-mutation-id`).
- Single-statement order-preserving emit (the N+1 statement count is acceptable for mutation N; revisit only if profiling shows real cost; the leaf's documented order-preservation invariant is the type-system anchor any future emit refinement must satisfy).
- Cross-table read-back (data field's `@table` must match input's `@table`; the cross-table case is a separate plan if it surfaces).
- DELETE-with-list-data-field-payload (no read-back to surface; rejected at the compact-constructor).
- Bulk `@service` carrier with list-shaped data field (the R75 Phase 2 `@service` carrier path is the symmetric `@service` counterpart; if a real schema surfaces a need, file `bulk-input-single-carrier-list-data-field-service` as the sibling item — R141's design does not generalise to `@service` because the emit strategy is different, but the carrier-resolution shape is symmetric and could share an interface).
- Refactor of both `MutationDmlRecordField` and `MutationBulkDmlRecordField` to sealed-on-kind permits (mirroring `DmlTableField`'s `MutationInsertTableField` / `MutationUpdateTableField` / `MutationUpsertTableField` shape); file `dml-record-carrier-sealed-on-kind` as the follow-up. R141 mirrors `MutationDmlRecordField`'s current `DmlKind kind` enum field for intra-pair consistency.
- Refactor of both record-carrier leaves under a `DmlRecordCarrierField` sealed sub-taxonomy under `MutationField`; file `dml-record-carrier-sub-taxonomy` as the follow-up. R141 leaves both leaves as flat siblings of `MutationField`. The two refactor items are independent and can land in either order.

## Roadmap entries (siblings / dependencies)

- **Follow-up from** R138 (shipped, see `changelog.md`): R138 lifted Invariant #15 above the per-arm switch in `MutationInputResolver.validateReturnType` and shipped its sealed-coverage. R141's classifier ordering routes the new admitted arm *before* R138's lifted check so bulk-input + list-data-field cases land at `MutationBulkDmlRecordField` rather than the Invariant #15 rejection.
- **Depends on** [`error-handling-parity.md`](error-handling-parity.md) (R12) at the **shape** level only: R141 reuses R12's `Optional<ErrorChannel>` slot, `PayloadAssembly` machinery, and catch-arm wiring. R141 makes no changes to R12; R141 may land before R12 (slot stays `Optional.empty()`) or after (slot populates via R12's existing classifier).
- **Mirrors** R138's `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` with an admitted counterpart on the complementary cell of the cardinality matrix.
- **Defers** sibling-field classifiers (`affectedRowCount`, `clientMutationId`, per-row error correlation) to future Backlog items; each new sibling shape is one new admission rule in the carrier classifier, no re-admission of the carrier required.
- **Defers** `dml-record-carrier-sealed-on-kind` (sealed-on-kind refactor for both record-carrier leaves, mirroring `DmlTableField`).
- **Defers** `dml-record-carrier-sub-taxonomy` (sealed `DmlRecordCarrierField` sub-taxonomy under `MutationField`).
- **Defers** `bulk-input-single-carrier-list-data-field-service` (the symmetric `@service` carrier path, if needed; the carrier-resolution shape is symmetric but the emit strategy is `@service`-specific).
