---
id: R1089
title: "A bulk DELETE with an id that matches no row rolls back the whole call, where Graphitron 9 deleted the rest"
status: In Progress
bucket: enhancement
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A bulk DELETE with an id that matches no row rolls back the whole call, where Graphitron 9 deleted the rest

## Goal

A consumer should be able to declare that a payload-returning `@mutation(typeName: DELETE)` treats an id that matches no row as already deleted, as Graphitron 9 did. In that mode a list-input call deletes the rows that exist, commits, and answers the payload with every input id in input order, with no error; a single-input call answers its payload with the input id instead of `null`. Today a list-input call throws "matched zero rows" on the first missing id and rolls the whole call back, and with no handler for that exception the client gets the redacted "An error occurred. Reference: …". Graphitron 10's behaviour stays the default. The new behaviour is opt-in per field.

The minimal pair, on the Sakila fixture that already covers the bulk payload-returning DELETE (`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`):

```graphql
input FilmDeleteInput {
    filmId: Int! @field(name: "film_id")
}
type DeletedFilmsIdPayload {
    deletedIds: [ID!]
}

# Today: one missing filmId rolls back every row and the field errors.
deleteFilmsIdCarrier(in: [FilmDeleteInput!]!): DeletedFilmsIdPayload
    @mutation(typeName: DELETE, table: "film")

# Opted in: the existing films are deleted, and deletedIds names every input film, missing ones included.
deleteFilmsIdCarrier(in: [FilmDeleteInput!]!): DeletedFilmsIdPayload
    @mutation(typeName: DELETE, table: "film", missingAsDeleted: true)
```

For `in: [{filmId: 1}, {filmId: 2147483000}]`, where film 2147483000 does not exist, the opted-in field deletes film 1, commits, and answers `{ deletedIds: ["<id of film 1>", "<id of film 2147483000>"] }`.

## Why

Reported by sis, whose Graphitron 9 deletes did not check row counts (9 failed only UPDATE and UPSERT on zero rows). Affected: stable `slettTimeplan` and `slettTimeplanroller`, beta `slettPersonbilder` and `trekkVurderingsmeldinger`, and 23 experimental deletes. None of them has a type for the zero-row case in its errors union. Two reasons to keep 9's behaviour:

- **Idempotency.** A client that retries a partly applied batch, or two clients that delete the same row, should not have the whole call rolled back because a row is already gone. After the call no row with that key exists, which is what the client asked for.
- **No existence signal.** sis's developers do not want a delete to tell the caller whether a row with that key existed. Tenant routing already refuses an id from another institution before any write, so what is left is whether a row visible and writable to the caller exists. Only echoing every input id hides that. Leaving the missing ids out of the payload leaks the same bit as the error does.

## What a DELETE does today with a key that matches no row

Three emission paths in `TypeFetcherGenerator`, three behaviours. The *payload-returning* shapes are the ones whose return is a payload type with an `ID` / `[ID!]` data field echoing the deleted primary keys; the *direct-return* shapes return `ID` / `[ID!]` themselves.

| Shape | Emitter | Key that matches no row |
|---|---|---|
| Payload-returning, list input (`MutationField.MutationBulkDmlRecordField`) | `buildBulkRecordTwoStepFetcher` with `buildBulkRecordPerRowDeleteBody`: one `deleteFrom(...).where(<key>).returningResult(<pk>).fetchOne()` per input row inside one `transactionResult` | Throws `IllegalStateException` "@mutation(typeName: DELETE) bulk row matched zero rows"; the transaction rolls back, and the catch arm redacts it unless an errors handler matches. This is the behaviour the item changes. |
| Payload-returning, single input (`MutationField.MutationDmlRecordField`) | `buildSingleRecordTwoStepFetcher` with `buildRecordDeleteChain` | `fetchOne()` answers `null` and the whole payload renders `null`, with or without an errors field. No error, nothing written. |
| Direct-return, single input (`MutationField.DmlTableField`) | `buildMutationDeleteFetcher` | The field resolves `null`, or a non-null violation on `ID!`. |
| Direct-return, list input (`MutationField.DmlTableField`) | `buildMutationDeleteFetcher`, one statement with `WHERE (<key>) IN (<rows>)` | The existing rows are deleted and only their ids are echoed; the missing ids are silently absent. No error. |

The opt-in applies to the two payload-returning rows. The direct-return rows reject it (see Rejections), so a consumer on a direct-return shape learns at build time rather than getting a silently ignored argument.

## Directive surface

`@mutation` gains one argument:

```graphql
directive @mutation(typeName: MutationType!, multiRow: Boolean = false, table: String,
                    missingAsDeleted: Boolean = false) on FIELD_DEFINITION
```

A Boolean rather than an `onMissingRow: FAIL | IGNORE` enum: today's default differs per shape (fail on the bulk payload, `null` on the single payload), so an enum default named `FAIL` would misdescribe half the shapes it covers. A Boolean names only the opt-in, and its sibling `multiRow:` already sets the style. The name says what the mode asserts, that a missing row counts as deleted. A name built on "ignore" would read as "skip it", which is what the direct-return list shape already does and what the goal's existence-signal reason rules out.

What `missingAsDeleted: true` means, stated once for both admitted shapes: an input key that matches no row counts as already deleted. Its slot in the payload holds the key the client sent, encoded the way a deleted row's key is; a row that was deleted echoes what `RETURNING` returned, exactly as today. Nothing is logged for a missing key, since it is not an error. Every other failure, such as a foreign-key violation on a row that does exist, still rolls the call back through the error channel as today.

Admission requires the input to identify rows by exactly the primary key: the walker's matched key is the primary key, and the input contributes no other column. Two reasons. The echo for a missing row is the primary key, and only an input that supplies the whole primary key can build it; a unique-key-only input cannot. And an extra filter column would let the echo claim a deletion that did not happen: `{filmId: 5, releaseYear: 2006}` against a film 5 from 2007 deletes nothing, film 5 still exists, and an echo of 5 would say otherwise. A composite primary key and a node-id input that decodes to the primary key both qualify.

## Implementation

- **Directive**: `graphitron-model/src/main/resources/no/sikt/graphitron/model/schema/directives.graphqls` gains `missingAsDeleted: Boolean = false` with a description paragraph beside `multiRow:`'s. `BuildContext` gains `ARG_MISSING_AS_DELETED`, and `GraphitronSchemaBuilder`'s `assertDirective(ctx, DIR_MUTATION, ...)` names it.
- **Capture, as written.** The argument is part of what a `@mutation` application says, so every relation whose grain is that application carries it, as written and `NULL` where the author wrote none, worded like the `multi_row` comments: a `missing_as_deleted BOOLEAN` column on `graphitron_ast_mutation_entry` (filled by `GraphitronFieldEntries.mutations`) and on `graphitron_mutation_entry` (derived in the anchoring phase by `GraphitronAnchor.mutations`, which copies it from `graphitron_ast_mutation_entry` beside `multi_row`, in both its select list and its `onDuplicateKeyUpdate`; `FactWrites.graphitronMutation` writes it too, as it writes `multi_row`), and a `missingAsDeleted` component on the in-memory `WriteFacts.Row` (filled by `WriteFactVisitor`). The `NULL`-versus-`false` distinction lets a reader tell a harmless written `false` on an INSERT from a written `true`. No resolved column is added to `graphitron_producer_write` or its rule view; nothing reads one yet. The corpus fixture `graphitron-model/src/test/resources/corpus/mutation-roots.graphqls` gains an application that writes the argument, so the as-written comparison in `CaptureCorpusIsolationTest` covers a non-empty column.
- **Read**: `MutationInputResolver.parseMissingAsDeleted(fieldDef)`, the twin of `parseMultiRow`. This is the classifier's existing read site for `@mutation` arguments. Moving the classifier onto `WriteFacts` or the store is a change for every argument at once, not for this one.
- **Verb gate**: `MutationInputResolver.MISSING_AS_DELETED_SUPPORTED_VERBS = Set.of(DmlKind.DELETE)`, single-sourced like `TABLE_ARG_SUPPORTED_VERBS`. `FieldBuilder`'s `@mutation` dispatch rejects `missingAsDeleted: true` on any other verb, beside the `table:` verb check. The rejection is a typed arm of a new sub-seal `MissingAsDeletedError` of `Rejection.AuthorError`, built like `MutationTableArgError`: `MissingAsDeletedError.UnsupportedVerb(verb, supportedVerbs)`, LSP code `graphitron.missing-as-deleted.unsupported-verb`.
- **Walker**: `DeleteRowsWalker.walk` takes the flag. With it set, the walker admits only an input whose covered key is the primary key and whose contributions bind each primary-key column exactly once and nothing else. It then returns the new carrier arm below, having picked the supplying `KeyColumn` for each primary-key slot. Any other outcome is a new typed arm `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey(table, inputColumns, primaryKeyColumns)` with LSP code `graphitron.delete-rows.missing-as-deleted-needs-primary-key`. Those outcomes are a `UniqueKey` match, extra filter columns, a broadcast, or a primary-key column bound twice (a node id and a plain column both filling it). The message names the table's primary key and the input's columns. The walker already owns row identification, and its typed errors already reach the LSP projector, so admission sits with it.
- **Carrier**: a third `DeleteRows` arm, `DeleteRows.IdempotentByPrimaryKey(MatchedKey.PrimaryKey key, List<KeyColumn> keyBinding)`. Its compact constructor asserts exactly one binding per primary-key column, in `key.columns()` order, and `whereColumns()` answers `keyBinding`. The arm types what the mode needs: the key is a primary key, and `keyBinding` names, for each primary-key column in key order, the one input contribution that supplies it, which is the order of the `RecordN` the echo builds. The absence of the arm is today's per-shape behaviour, which therefore needs no name. `Identified` and `Broadcast` are unchanged. Every `switch` over `DeleteRows` gains the arm; outside the emitters below, it reads exactly like `Identified`.
- **Leaf gate**: `MutationField.DmlTableField`'s compact constructor, which already pairs a Delete arm with an encoded-ID return, also rejects a Delete arm carrying `IdempotentByPrimaryKey`, so a direct-return leaf cannot hold it. `FieldBuilder.classifyDeleteTableField` meets the gate first with a typed `MissingAsDeletedError.UnsupportedReturnShape(fieldCoordinate)`, LSP code `graphitron.missing-as-deleted.unsupported-return-shape`, whose message names the payload-returning shape as the supported one. Admitting the direct-return shapes later is removing this gate and adding their emit arm, not a model change.
- **Key values**: `TypeFetcherGenerator.LookupWhereChunk` gains, beside `decodeLocals` and `whereExpr`, the value expression of each bound column as a Java value of the column's type, keyed by the target column. For a node-id binding that is the decode local's `valueN()`; for a plain one it is `<column>.getDataType().convert(<wire value>)`. `buildLookupWhereSingleRow` already computes both, and today only wraps them in `DSL.val`. The echo reads these by walking `keyBinding` and looking each column up, never by position. `buildLookupWhereSingleRow` emits per group, and `keyGroupsOf` orders groups by first occurrence with each node id's columns kept together, so its emission order is not key order: for a key `(a, b, c)`, a node id over `(a, c)` and a plain `b`, the WHERE compares `a, c, b`. Looking up by column makes the echo independent of that order, and the binding's one-entry-per-key-column guarantee makes every lookup succeed, so the echo encodes exactly the values the WHERE compared.
- **Fixture**: `graphitron-sakila-db/src/main/resources/init.sql` gains a pair in the `nodeidfixture` schema whose primary key interleaves a node id with a plain column: `split_key_parent (a integer, c integer, PRIMARY KEY (a, c))` and `split_key_child (a integer, b integer, c integer, PRIMARY KEY (a, b, c), FOREIGN KEY (a, c) REFERENCES split_key_parent (a, c))`, both `@node` types in the Sakila schema. Every key column is `integer`, so an echo that put the values in the wrong slots would still compile and only a test that decodes the echoed id can catch it. No existing fixture has this shape: `level_b` and `level_c` decode a contiguous run of their keys.
- **Bulk emit**: `buildMutationBulkDmlRecordFetcher`'s Delete arm passes the carrier to `buildBulkRecordPerRowDeleteBody`. On `IdempotentByPrimaryKey`, the `rec == null` arm assigns `rec = txd.newRecord(<pk fields>).values(<key values>)` instead of throwing. `acc.add(rec)` then runs as before, so `acc` keeps one entry per input row in input order, and the order-preservation invariant on `buildBulkRecordTwoStepFetcher` holds unchanged. On the other arms the body is today's.
- **Single emit**: on `IdempotentByPrimaryKey`, `buildRecordDeleteChain`'s `DmlChainAndGuards` carries the echo record expression, and `buildSingleRecordTwoStepFetcher` assigns it to `payload` when `fetchOne()` answers `null`. That happens ahead of the existing null-data check, which then never fires for this shape. The insert, upsert and update chains carry no echo and are unchanged.

## Rejections

| Application | Outcome |
|---|---|
| `missingAsDeleted: true` on `INSERT`, `UPDATE` or `UPSERT` | `MissingAsDeletedError.UnsupportedVerb` |
| On a direct-return DELETE (`ID`, `[ID!]`) | `MissingAsDeletedError.UnsupportedReturnShape` |
| On a DELETE whose input covers a unique key but not the primary key, contributes columns beyond the primary key, binds a primary-key column twice, or is a `multiRow: true` broadcast | `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey` |
| `missingAsDeleted: false`, or the argument omitted | Today's behaviour on every shape; nothing changes. |

## Tests

The tiers per `docs/architecture/how-to/testing.adoc`. The behaviour is runtime, so the execution tier carries the acceptance evidence; no test asserts generated code text.

- **Execution** (`DmlBulkMutationsExecutionTest`, against new Sakila fixtures beside `deleteFilmsIdCarrier`):
  - A bulk `missingAsDeleted: true` film delete with `[existing, missing]`: no errors, `deletedIds` holds both encoded ids in input order (the missing one decodes back to 2147483000), the existing film is gone, and a `FetcherErrorLog` installed as in `deleteFilmsIdCarrier_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow` is never called.
  - The same field with `[missing, existing]`, so the order is pinned when the missing row comes first.
  - The same field with a duplicate `[existing, existing]`: the second delete matches nothing, and both slots echo the id.
  - The `[existing, missing]` batch run twice: the second run deletes nothing, raises no error and answers the same `deletedIds` as the first. This is the retry case from the goal.
  - A bulk `missingAsDeleted: true` delete keyed by the composite-primary-key node id (`DeleteFilmActorByNodeIdInput`, a new payload type for `film_actor`), whose payload declares an errors field: the missing node id's slot holds an id that decodes to the same key and, for an id Graphitron issued, equals the one sent; this runs the `Outcome`-wrapped path.
  - A bulk `missingAsDeleted: true` delete on `split_key_child` keyed by `parent: ID! @nodeId(typeName: "SplitKeyParent")` and `b: Int!`, with `[existing, missing]` whose `a`, `b` and `c` values are all distinct: the missing row's echoed id decodes to exactly the `(a, b, c)` sent, and the existing row's to its own key.
  - A single-input `missingAsDeleted: true` film delete with a missing key: the payload is non-null and its id field echoes the input.
  - The existing `deleteFilmsIdCarrier_zeroRowRow_...` test stays, unchanged, as the proof that the default did not move.
- **Pipeline** (`GraphitronSchemaBuilderTest`, DELETE cases): each Rejections row is a classification case that asserts the typed arm. The admitted bulk and single payload cases classify to `MutationBulkDmlRecordField` / `MutationDmlRecordField` whose Delete arm carries `DeleteRows.IdempotentByPrimaryKey`, with a composite-key case pinning the binding order, and a `split_key_child` case asserting that `keyBinding` lists `a` and `c` from the node id and `b` from the plain field in key order `(a, b, c)`.
- **Capture**: the corpus application above appears with `missing_as_deleted = true` in both capture relations, and an omitted argument stays `NULL`.
- **Unit** (`DeleteRowsWalkerTest`): with the flag, a PK-only input yields `IdempotentByPrimaryKey` with the binding in key order. A UK-only input, a PK-plus-extra-column input, a twice-bound PK column and a broadcast each yield `MissingAsDeletedNeedsPrimaryKey`. Without the flag, every existing case is unchanged. The `IdempotentByPrimaryKey` compact constructor and the `DmlTableField` gate each get a case. `RejectionResidueDrainageTest` and any LSP-code registry that enumerates the `Rejection.AuthorError` sub-seals pick up `MissingAsDeletedError` and the new `DeleteRowsError` arm.

## User documentation (first-client check)

`docs/manual/reference/directives/mutation.adoc`:

- The SDL signature and the Parameters table gain the row:

  > `missingAsDeleted` | `Boolean` | `false` | On a payload-returning `DELETE`, an input key that matches no row counts as already deleted: the call deletes the rows that exist, commits, and the payload echoes every input key, in input order. Requires the input to identify rows by exactly the primary key. Rejected on `INSERT`, `UPDATE`, `UPSERT`, and on a `DELETE` that returns `ID` or `[ID!]` directly.

- Under "Payload-returning DELETE", a new subsection after "Single vs bulk":

  > === When a key matches no row
  >
  > By default a bulk `DELETE` whose input names a key that matches no row fails the whole call: nothing is deleted, and the field reports an error (a typed one if a handler in the payload's errors field matches, otherwise a redacted one). A single-row `DELETE` renders the payload `null`.
  >
  > Set `missingAsDeleted: true` to treat such a key as already deleted. The rows that exist are deleted, the call commits, and the payload's id field holds every input key in input order, whether or not its row existed. A retried batch, or two clients deleting the same row, then succeed, and the response does not tell the caller which rows existed.
  >
  > ```graphql
  > deleteFilmsIdCarrier(in: [FilmDeleteInput!]!): DeletedFilmsIdPayload
  >     @mutation(typeName: DELETE, table: "film", missingAsDeleted: true)
  > ```
  >
  > The input must identify rows by exactly the table's primary key, directly or through a node id. A unique key is not enough, because the response echoes the primary key and only the input can supply it for a row that is not there; an extra filter column is refused, because a key whose row survives the filter would be echoed as deleted.

- The "Echoing the deleted primary key" subsection's "The response contains exactly the IDs of rows the DML actually removed." gains ", unless the field sets `missingAsDeleted: true`".
- The errors-field rule "A single-row write whose key matches no row renders the whole payload `null`" gains the same exception.

`docs/manual/how-to/migrating-from-legacy.adoc` gains a short entry: Graphitron 9 did not check row counts on `DELETE`; a schema that relied on that adds `missingAsDeleted: true` to the field.

The directive's SDL description in `directives.graphqls` carries the Parameters-row text.

## Other solutions we've considered

- **A plugin-wide default instead of a per-field argument.** UPDATE keeps failing on zero rows either way, and one schema can want both kinds of DELETE (an admin delete that should report a stale id beside a client delete that should not), so the choice belongs to the field.
- **Leaving missing keys out of the payload instead of echoing them.** This is what the direct-return list path already does. It keeps the payload truthful about what was deleted, but it leaks whether a row existed exactly as the error does, which is the second half of the goal.
- **Admitting the direct-return shapes too.** The single direct-return shape would be a one-line echo, but the list shape is one `IN` statement whose `RETURNING` has no slot for a missing key, so it needs a second emit arm built from the input list. No consumer has asked for either; the rejection keeps the argument from being silently ignored there, and lifting it later is additive.
- **Admitting a unique-key or extra-filter input and echoing what is known.** A unique-key-only input has no primary key to echo for a missing row, and echoing a key whose row survived an extra filter misreports a deletion. Both would need a different echo contract; none is asked for.
- **A policy enum on `DeleteRows.Identified` instead of a third arm.** The opt-in only exists where the key is the primary key and the binding covers it exactly once. A flag on `Identified` leaves both of those untyped, so the emitter would trust a cross-field check and recover the primary-key slot order itself. Its default value would also mean something different on each leaf that reads it.
- **Reading the argument without capturing it in the store.** The two capture relations are the as-written decode of one `@mutation` application. A row that drops an argument the generator consumes no longer says what the application says, and the fact would be missing when the emitter is sourced from the store.

## Implementation notes

Two places where the implementation departs from the plan's letter:

- **Fixture schema.** `split_key_parent` / `split_key_child` are in `init.sql`'s `public` schema, not `nodeidfixture`. The execution tier (`graphitron-sakila-example`) types its fixtures over `public` tables, as the `xlat_*` twins of `nodeidfixture.parent_node` do, so a `nodeidfixture` pair would have had no execution coverage. The unit and pipeline tiers read the same catalog, so they reach the pair there too. `jooq.codegen.schema.version` moves to 2.44 for the DDL.
- **Capture test.** Besides `EntryFamilyFixture.deleteFilm` (per the round 2 note), the as-written assertions live in `graphitron-model/src/test/resources/facts/undecided/field-site-payloads.graphqls`, which already carried the `graphitron_mutation_entry` `@expectEquals`; it now asserts `missing_as_deleted` (`TRUE` written, `NULL` omitted) on both capture relations. `corpus/mutation-roots.graphqls` is unchanged, since every field there is a classification verdict and the admitted shape is a payload DELETE that file does not carry.
- **Plain key value.** A plain binding's echo value is `DSL.val(<wire value>, <column>.getDataType()).getValue()` rather than `<column>.getDataType().convert(<wire value>)`: jOOQ 3.20 deprecates `DataType.convert(Object)` for removal, which fails the example module's `-Werror` compile. The coercion is the same one the WHERE binds with.


### Round 1 (Spec → Ready): request revisions

Reviewer session_017VBR1uJP2A8xrFjrWghouP, 2026-10-22.

Question 1 passes. The goal reads cleanly without the phase list: a consumer can opt one payload-returning DELETE into treating a key that matches no row as already deleted, so a retried or overlapping batch commits instead of rolling back, and the payload echoes every input key, so it does not reveal which rows existed. The behaviour table matches the emitters: `buildBulkRecordPerRowDeleteBody` throws on `rec == null`, `buildSingleRecordTwoStepFetcher` returns null data, `buildMutationDeleteFetcher` keeps the `IN` shape. Every symbol the plan names exists under the name given. Question 2 mostly passes. The third `DeleteRows` arm extends the walker carrier rather than standing beside it, and the gates reuse the `MutationTableArgError` / `DeleteRowsError` shapes. Two points fail the "hand it over as-is" test.

1. **The key-value order claim is false for an input the admission rule admits (question 2).** The Key values bullet says the per-binding values from `LookupWhereChunk` "come out in primary-key order" over an `IdempotentByPrimaryKey` binding, and the Carrier bullet builds on that ("the emitter zips rather than matches columns"). But `buildLookupWhereSingleRow` iterates *groups*, and `TypeFetcherGenerator.keyGroupsOf` groups the `KeyColumn`s by access path in first-occurrence order. A node-id field that decodes to two primary-key columns, with a plain field binding a primary-key column between them in key order, is admitted (every primary-key column bound once, nothing else), yet comes out as both decoded values first, then the plain one. For a primary key `(a, b, c)` with a reference node id over `(a, c)` and a plain `b`, the values arrive `a, c, b`. Zipped into `newRecord(a, b, c).values(...)`, that either fails to compile in the generated source or, with same-typed columns, echoes a different key than the WHERE compared. That is the one wrong answer this feature must not give. None of the planned tests reaches the case: the composite fixture is one two-column node id, a single group. What satisfies it: the plan says how the echo pairs each value with its primary-key column so the result does not depend on group order (for example values keyed by target column and read in `key.columns()` order), and the Carrier bullet's rationale is restated to match. A pipeline or unit case with a non-contiguous decode group should pin it.

   *Author response:* the Key values bullet now keys each value expression by its target column, and the echo walks `keyBinding` and looks each column up; it states why emission order is not key order. The Carrier bullet's rationale no longer promises a zip. A new Fixture bullet adds `split_key_parent` / `split_key_child` with an all-`integer` key, so a slot swap compiles; an execution case decodes the missing row's echo against the `(a, b, c)` sent, and a pipeline case pins the binding order.

2. **The capture bullet names the wrong producer for `graphitron_mutation_entry` (question 1, a claim about existing code).** `GraphitronFactCapture` only claims the coordinate for that relation (`sink.claim(GRAPHITRON_MUTATION_ENTRY, ...)`) and writes no `multi_row`. The row is derived in the anchoring phase by `GraphitronAnchor.mutations`, which copies `MULTI_ROW` from `graphitron_ast_mutation_entry`. `FactWrites.graphitronMutation` is the registered writer for the relation. An implementer following the bullet adds the column to `GraphitronFactCapture` and leaves the anchoring `INSERT ... SELECT` and its `onDuplicateKeyUpdate` without it, so the derived column stays `NULL` and the Capture test fails with no pointer to the cause. What satisfies it: the bullet names `GraphitronAnchor.mutations` as what fills `graphitron_mutation_entry` (copying from the AST entry, as `multi_row` is copied), alongside `FactWrites.graphitronMutation`.

   *Author response:* the Capture bullet now names `GraphitronAnchor.mutations` as what derives the column, in both its select list and its `onDuplicateKeyUpdate`, with `FactWrites.graphitronMutation` as the writer.

Non-blocking: the Rejections and Tests sections are consistent with the plan; nothing else to add.

### Round 2 (Spec → Ready): sign off

Reviewer session_013Qj4MG74mmUTes7j1eF7bS, 2026-10-22.

Both round 1 findings are resolved as the author responses say. Question 1 passes: a consumer can mark one payload-returning DELETE `missingAsDeleted: true`, so a batch with an id that is already gone deletes the rest, commits, and echoes every input id, where today it rolls back with a redacted error. Question 2 passes: a third `DeleteRows` arm refines `Identified`, and the gates reuse the `MutationTableArgError` and `DeleteRowsError` shapes.

Non-blocking, for the implementer: the Capture bullet says adding the application to `corpus/mutation-roots.graphqls` makes `CaptureCorpusIsolationTest` cover a non-empty column. That test does not read the corpus file; its entry-half differential captures `EntryFamilyFixture` (`graphitron-model/src/test/java/no/sikt/graphitron/model/test/EntryFamilyFixture.java`), whose `deleteFilm` already writes `multiRow:` and `table:`. Add `missingAsDeleted: true` there as well, or the differential compares a column that is `NULL` on both arms. The corpus file is still the right home for the Capture test's `@expectEquals` over the two mutation-entry relations.
