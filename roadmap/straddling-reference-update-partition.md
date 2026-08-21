---
id: R784
title: "Partition a straddling cross-table @nodeId reference per column on UPDATE instead of rejecting it"
status: Spec
bucket: feature
priority: 2
theme: mutation-write
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Partition a straddling cross-table @nodeId reference per column on UPDATE instead of rejecting it

A generated UPDATE has two halves. `UpdateRowsWalker` asks which input fields cover a primary or unique key of the write target; the matched key's columns become the WHERE clause and every remaining input field populates the SET clause. A `@nodeId` input field is not one column: on a cross-table reference it expands into the foreign key's child columns on the write target. So a single input field can expand into columns that fall on both sides of the WHERE and SET boundary. Stage 6 of the walker calls that a *straddle* and rejects it with `UpdateRowsError.MixedCarrierKeyMembership`. There are real schemas whose only sensible spelling straddles, and for those the rejection has no workaround that preserves the global ID.

The shape, in the terms of a consumer schema found while migrating from Graphitron 9 to 10. The write target has a two-column primary key `(INSTITUSJONSNR_EIER, PLNR)`: an institution number plus a person number, because a person number is only unique within one institution. The same table has a foreign key to a library whose child columns are `(INSTITUSJONSNR_EIER, BIBSYSBESTSTEDKODE)`: the library code, qualified by the same institution number, because a student's library must belong to the student's own institution. An UPDATE input carries `id: ID!` (the row's own global ID, covering the primary key) and `bibliotekId: ID! @nodeId(typeName: "Bibliotek")` (the library to point at). `id` covers the key, so both its columns are the WHERE. `bibliotekId` then expands into one column already in the WHERE and one that is not, and the walker rejects.

The overlap is benign, and the statement the schema asks for is unambiguous. The two contributors to `INSTITUSJONSNR_EIER` cannot disagree for well-formed input, because the foreign key constraint forces them equal. The wanted statement is `UPDATE t SET BIBSYSBESTSTEDKODE = ? WHERE INSTITUSJONSNR_EIER = ? AND PLNR = ?`: the shared column is read as identity and never written, and only the non-key half of the foreign key is set. Graphitron 9 generated exactly this and checked agreement at runtime.

**Decided behaviour.** A cross-table reference carrier whose lifted columns straddle the matched key partitions per column rather than per field. Its in-key columns contribute to the WHERE side only, its out-of-key columns become SET writes, and where an in-key column already has another contributor the two decoded values are agreement-checked before any DML runs. Disagreement is a runtime error and can only be a runtime error, since both values arrive on the wire.

## Why this is not a from-scratch capability

Two pieces of the machinery are already in the tree. The walker already tolerates this overlap for a *self*-referencing foreign key: a self-FK carrier routes its columns wholly to SET regardless of key membership, on the stated rationale that its columns point at a sibling row and are never this row's identity, so a shared key column it writes is an ordinary SET write. Only the table-identity test (`containingTable` equals the FK's target table) separates that admitted case from this rejected one, and the rationale transfers: a cross-table FK is equally a pointer rather than an identity.

The cross-partition agreement check also already exists. `TypeFetcherGenerator.emitKeySetAgreementPreamble` re-decodes each side of a column that appears in both the WHERE and the SET partition, gathers the present values, and pairwise-checks them through `NodeIdEncoder.requireColumnAgreement`, throwing a `GraphqlErrorException` that names both contributing input fields before the DML runs. Its own worked example is this same shape on the self-FK side. The bulk (list-input) arm has the per-row analogue in `emitBulkKeySetAgreement`.

## The two costs the shape actually carries

**The decode-slot invariant has to become explicit first, and this is the bulk of the work.** Every emit site that reads a decoded composite `@nodeId` infers the record slot from the column's *position in its partition group*. `SetGroup.columns()` is documented as being in decode-record slot order; `keyGroupsOf` assigns `InputColumnBinding.RecordBinding.index()` positionally from the group it just built; there are six hardcoded `.value1()` reads that encode "a one-column group is arity 1, so slot 1", plus about a dozen positional `value<n>()` reads. All of that rests on one unstated invariant: a carrier's columns land in one partition, whole, in decode order. Partitioning per column breaks the invariant. In the consumer schema above the positional read is accidentally correct, because `INSTITUSJONSNR_EIER` happens to be the first child column of the foreign key; reverse the constraint's column order and the same code silently writes the decoded institution number into the library-code column. So the item is not "delete the reject and teach the preamble one more case". It is "carry the decode slot as an explicit datum on `SetColumn` and `KeyColumn` and replace every positional inference with it", after which the partition split is close to free. The lift stands on its own merits: it turns a comment-only invariant into a compiler-visible one.

**Deduplicating the WHERE side is mandatory rather than cosmetic.** With two contributors to one key column, the single-row arm would emit a redundant `.and()` predicate, which is harmless. The bulk arm builds `UPDATE t SET ... FROM (VALUES ...) AS v(...)`, and two contributors to one column put a duplicate name in the derived-table alias, which does not run.

That raises the model question the item has to settle: is the straddler's in-key column a `KeyColumn` that the emitters deduplicate, or a third kind of contribution? A third partition on the `UpdateRows` carrier, meaning "this carrier contributes this key column only to be agreement-checked, never to filter and never to write", looks preferable. It puts the walker's decision in the carrier instead of hiding it in an emitter's first-contributor-wins map; it lets the preamble reuse its existing two-sided compare rather than learning a general key-against-key intersection walk; and it falls out correctly when the straddler is the *sole* contributor of that key column (primary key `(A, B)`, the straddler covering `A` and `C`, another field covering `B`), where `A` is a genuine WHERE column and there is nothing to check. It also leaves the self-FK path untouched, where the shared column genuinely is written.

## The fork the shape opens: clearing a straddling reference

If the straddling carrier is nullable and a caller sends an explicit null, per-column partitioning emits `SET BIBSYSBESTSTEDKODE = NULL` and leaves the institution number alone, producing a half-null foreign key tuple. PostgreSQL's default `MATCH SIMPLE` treats a partially-null foreign key as satisfied, so the constraint does not catch it and the row keeps a dangling half-key instead of failing.

The coherent rule is that the in-key half of a straddling foreign key *is* the row's identity and is therefore immutable, so such a reference can be re-pointed only within the same key value and can never be cleared. That makes nullability a build-time rejection: a straddling cross-table reference carrier must be non-null. `InputField.ColumnBackedReferenceField` already carries `nonNull`, so the walker only needs to thread it onto its local `Contribution`. The alternative is admitting the nullable shape and throwing at runtime on an explicit null; build-time is preferable because the hazard is knowable from the schema alone.

## Scope notes

`MixedCarrierKeyMembership` survives, narrowed for the second time. It keeps a real subject: a straddling *own-columns* carrier, that is a same-table composite `@nodeId` whose columns span the matched key. Those columns are this row's own identity, so writing half of them means moving the row, which is a different act from re-pointing a sibling reference. The walker's local `Contribution` record currently carries only a `selfReference` flag and needs to distinguish own-columns from self-FK from cross-table FK. No retirement sweep is needed, which is a real saving over retiring the arm.

There is no fixture for this shape. The `email` and `mailbox` pair does not straddle: `email`'s foreign key to `mailbox` is single-column and lies wholly inside `email`'s primary key. This needs a new table pair rather than a new column on `email`, so the existing self-FK execution coverage and the `Email` record shape stay untouched. A minimal pair mirroring the consumer schema is a catalogue table keyed `(tenant_id, catalog_code)` and an item table keyed `(tenant_id, item_no)` carrying a composite foreign key `(tenant_id, catalog_code)` to it.

Route-wholly-to-SET was considered as a cheaper first step and rejected. It is not a smaller version of this change but a different emitted statement: the shared identity column becomes a live SET write with its jOOQ changed flag set, and an explicit null would null the row's own identity. It would need its own doctrine paragraph, its own execution coverage, and then a behaviour change to retire. The `typed-rejection.adoc` paragraph on this arm has already been narrowed once; narrowing it twice more in opposite directions is worse than narrowing it once correctly. A consumer needing an unblock before this lands has a schema-side one: drop `@nodeId` on the slot and bind the non-key foreign-key column directly with `@field`, at the cost of the caller sending a raw code instead of a global ID.

## Contract

Terms used below. The *walker* is `UpdateRowsWalker` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/UpdateRowsWalker.java`); it projects a mutation input onto the `UpdateRows.Identified` carrier, today a pair of flat lists: `List<KeyColumn>` (the WHERE side) and `List<SetColumn>` (the SET side). The *emitter* is `TypeFetcherGenerator`, which turns the carrier into generated jOOQ code. A *carrier field* is one input field; a composite `@nodeId` carrier field flattens into several `KeyColumn`/`SetColumn` rows sharing one `sdlFieldName()`. The *decode record* is the `Record<N>` a generated `NodeIdEncoder.decode<Type>` call returns; its column order is `HelperRef.Decode.outputColumnShape()`, and a *decode slot* is a column's 0-based position in it.

After this item, for an `@mutation(typeName: UPDATE)` input:

1. A cross-table composite `@nodeId` reference field whose lifted columns straddle the matched key is admitted. It partitions per column: each out-of-key column is a `SetColumn`; each in-key column becomes a genuine `KeyColumn` when the straddler is that column's only identity contributor, and otherwise contributes *only an agreement obligation*: it never filters and never writes, it is only checked against the WHERE contributor's value.
2. Before any DML runs, each agreement obligation compares the two contributors' decoded values for the same SQL column through the generated `NodeIdEncoder.requireColumnAgreement`; disagreement throws a `GraphqlErrorException` naming both input fields, and no row is touched. This is the mechanism the self-FK shape already ships (`TypeFetcherGenerator.emitKeySetAgreementPreamble` single-row, `emitBulkKeySetAgreement` bulk), consumed from the carrier instead of re-derived per emitter.
3. A *nullable* straddling reference field is rejected at build time with a new `UpdateRowsError` arm. Rationale in "The fork the shape opens" above: an explicit null would half-null the FK tuple, and `MATCH SIMPLE` would not catch it. The rule reads: the in-key half of a straddling reference is row identity, so the reference can be re-pointed only within the same key value and never cleared; spell the field `ID!`.
4. `UpdateRowsError.MixedCarrierKeyMembership` survives, narrowed to the straddling *own-columns* carrier (a same-table composite `@nodeId` spanning the matched key), where writing half the columns means moving the row.
5. The emitted single-row statement for the worked example is `UPDATE t SET <non-key FK half> = ? WHERE <key columns> = ?`, each key column appearing exactly once. The bulk arm's `VALUES ... AS v(...)` alias likewise names each column once, because obligation-only columns never enter the derived table.

## Design decisions settled in Spec

**The carrier carries agreement obligations, not a third column list.** `UpdateRows.Identified` grows a component `List<AgreementObligation>`, one row per shared SQL column, each row naming the column and its two contributing sides (SDL field name, extraction, decode slot for each). This is stronger than the Backlog draft's "third kind of column" for two reasons. First, the carrier has *four* emitter consumers in `TypeFetcherGenerator`, not two: the direct-return single-row and bulk arms, and the payload-returning single-row and bulk arms. Only the first two emit an agreement preamble today; a bare third list would be consumed by the sites the implementer touches and silently dropped by the others, with no compile error anywhere. An obligation row is the walker's *finished* decision, so every consumer folds over the same fact. Second, the two existing agreement emitters each hand-roll the same WHERE-and-SET intersection walk (`keyByColumn` maps, first-contributor-wins) plus a defensive encoder-class fallback; sourcing obligations from the walker lets both delete that walk, and the self-FK overlap rides the same component. The emitter-side alternative (first-contributor-wins dedup of a doubled `KeyColumn` list) hides a walker decision in emit arms and puts a duplicate column name one refactor away from the bulk alias crash.

**All four consumer sites are in scope.** Each of the four `updateRows()` readers either consumes obligations or is shown (by a pipeline assertion on the carrier plus its execution behaviour) to be unreachable for an obligation-carrying input. Implementation must verify whether the payload-returning arms can already receive a self-FK overlap today; if they can, the missing agreement check there is a live gap this item closes, with its own execution case.

**The transitional-surface tension, stated up front.** The pipeline doctrine says new facts land in the store, not on walk-side carriers; `SetColumn`/`KeyColumn` gaining a slot and `Identified` gaining obligations are extensions of a surface being drained. The honest position: the UPDATE emit path is fed entirely by the walker carrier today, no `intent_` relation states an UPDATE partition, and building one is a different item. This is a bounded extension with named re-sourcing targets: the slot's eventual home is the recorded-not-reconstructed ordinal doctrine the store already ships (`sql_node_key_column.position`, `intent_foreign_key_column_pair.position` in `graphitron-model.sql`), and straddle detection re-sources from `intent_foreign_key_column_pair` plus the resolved node key relations. The walker-local `Contribution` changes are not in tension at all: gathering scaffolding, discarded before the model.

**The self-FK asymmetry is deliberate residue, not inconsistency.** The item argues a cross-table FK is a pointer, not identity, which is the same rationale that admitted the self-FK all-SET route; yet the two shapes keep different emitted statements (self-FK writes the shared column and checks agreement; the straddler checks and never writes). The reason is scope, not semantics: agreement-only is the better disposition on both counts (no jOOQ changed-flag on an identity column, no explicit-null hazard), the new case takes it, and converging the shipped self-FK route onto it is a behaviour change with its own execution coverage and nullability rule, separately fileable.

**The decode-slot lift lands first, as its own stage, with byte-identical output.** Every positional slot inference is replaced by a slot datum carried on `SetColumn` and `KeyColumn`, before any behaviour changes. The correctness criterion (emitted output byte-identical) exists only while the lift is its own stage: fused with the behaviour change, "the output changed" no longer distinguishes a slot-threading bug from the new partitioning. The store already states this as doctrine for ordinals: `sql_node_key_column.position` is "recorded rather than reconstructed" because a reader that recovers order from context can recover a different order than the one identities were issued under; the lift brings the walker carrier into line with that.

**The slot's source of truth is the decode shape, not list position.** The slot is assigned where the carrier flattens into per-column rows (the walker's stage 6), as the column's index in the carrier field's column list. The alignment guarantee lives on the producer: `FilterBinding.Local.ownTableColumns` is documented as positionally aligned with the target node's key columns; stage 1 pins that chain, ordering or asserting the lifted list at `NodeIdLeafResolver` against `HelperRef.Decode.outputColumnShape()`, and leaves a consumer-to-producer `{@link}` at the minting site since the type system cannot carry the alignment. This is the "reverse the constraint's column order" hazard from the cost section above, made checkable.

**Two straddlers sharing an in-key column.** When every contributor of an in-key column is a straddling reference, the first in input-field order provides the `KeyColumn` and the rest contribute agreement obligations only. Deterministic, and the agreement check makes the choice observationally irrelevant for well-formed input.

## Implementation stages

**Stage 1: explicit decode slots, no behaviour change.**

- Add a decode-slot component to `SetColumn` and `KeyColumn` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`). For a raw map read (arity 1, no decode) the slot is 0.
- Assign it in `UpdateRowsWalker` stage 6 when flattening a `Contribution` into per-column rows, and pin the lifted-column-order invariant at `NodeIdLeafResolver` as above.
- Push it into the shared primitive: the slot becomes part of the `ColumnOverlap.ColumnWriter` contract (slot-bearing column pairs, or a slot accessor beside `targetColumns()`), and `ColumnOverlap.groupByColumn` mints `Contributor.slot` from the carried datum instead of the loop index. Stopping the lift at `SetColumn`/`KeyColumn` would leave the widest-fanout seam re-deriving slots positionally from a list that per-column partitioning makes non-contiguous. `ColumnOverlap`'s load-bearing-invariant javadoc is then deleted, not restated; the compiler is the enforcer. Note this contract reaches the INSERT and `@service` jOOQ-record adapters that also implement `ColumnWriter`, so the byte-identical gate spans those surfaces too.
- Consume it in `TypeFetcherGenerator`: `keyGroupsOf` builds `InputColumnBinding.RecordBinding(slot, ...)` from the carried datum instead of the loop index; `SetGroup` carries per-column slots and `SetGroupWriter` reads them; the positional `value<n>()` and `value1()` reads downstream of the UPDATE/DELETE partitions (in `emitSetMapPuts`, `appendAgreementValue`, `emitSetBulkCellAdds`, `emitBulkKeySetAgreement`, `buildLookupWhereSingleRow`, `appendMapBindingValueExpr`, `emitLookupKeyCellAdds`, `appendBulkRowCells`, and `render/LookupRows.slotValueExpr`) route through it as `value<slot+1>()`.
- Fix the fused discriminator in `keyGroupsOf`: today `group.size() == 1` selects `MapGroup` (and its consumers hardcode `value1()`), fusing "how many columns this partition received" with "the decode record's arity". Those are the same number only under the invariant this item breaks: a straddler can hand one column to a partition while its decode record has arity 2. The discriminator becomes "the group carries a decode", and a one-column decoded group stays a `DecodedRecordGroup` reading its carried slot.
- Out of the lift's scope: positional reads against the decode shape itself rather than a partition group (the INSERT per-cell loops, the read-path key wraps in `ReentryRowsFragments`, `BatchedRowsFragments`, `MultiTablePolymorphicEmitter`, `RoutineWriteFetcherRenderer`, and `ArgPathHelperRegistry`'s depth counter). Their position *is* their meaning.
- Exit gate: full install green; generate the fixture corpus and `graphitron-sakila-example` sources before and after the stage and diff to empty (`GeneratorDeterminismTest` and the SQL baselines alone do not prove byte-identity); `NodeIdPipelineTest`'s `RecordBinding::index` assertions unchanged.

**Stage 2: the walker partitions per column.**

- `Contribution` (walker-local record) replaces `boolean selfReference` with a sealed carrier role (own-columns, self-FK, cross-table FK), with `nonNull` a component of the cross-table arm only: it is dead by construction on the other two (own-columns straddles reject regardless; self-FK never consults it), and a sealed role makes the meaningless read impossible. The role projects exhaustively off the existing `classifyInto` leaf switch: `ColumnBackedField` is own-columns, `ColumnBackedReferenceField` is self-FK or cross-table FK by its existing `selfReference` flag; `nonNull` threads from `InputField.ColumnBackedReferenceField.nonNull()`.
- Stage 6, cross-table FK carrier whose columns straddle the matched key: nullable rejects with the new arm; non-null partitions per column per the contract, the walker emitting `AgreementObligation` rows for shared in-key columns (and, for uniformity, for the self-FK overlap, replacing the emitters' local intersection).
- The new arm is a new permit, not a widened `MixedCarrierKeyMembership`: `lspCode()` is the contract downstream tooling switches on, and "don't straddle your own key" and "make this reference non-null" are different fixes. Proposed name `UpdateRowsError.NullableStraddlingReference`, lspCode `graphitron.update-rows.nullable-straddling-reference`. It carries the field name, the in-key/out-of-key split, *and the matched key plus write target*: the rejection is not a property of the field alone (the same nullable reference is legal where the matched key does not intersect the FK), so the message must be able to say why the same spelling is fine elsewhere. It locates at the input field's declaration.
- One nullability edge, settled: a straddling carrier nested inside a *nullable grouping input* is safe under the leaf-level rule, because an absent or null grouping object yields absence for every leaf (PATCH semantics drop them), never a half-written FK tuple. Only the leaf's own nullability rejects.
- Own-columns straddle keeps `MixedCarrierKeyMembership`; its message and javadoc narrow to the own-columns subject.
- `UpdateRows.Identified` gains `agreementObligations` (interface accessor, compact-constructor copy; the non-empty-SET invariant is unaffected because a straddler always contributes at least one `SetColumn`).
- Sealed-hierarchy fallout, all build-enforced: the new arm needs its paragraph and roll-call mention in `docs/architecture/explanation/typed-rejection.adoc` (`SealedHierarchyDocCoverageTest`), a severity row (`RejectionSeverityCoverageTest`), and the drainage allowlist (`RejectionResidueDrainageTest`).

**Stage 3: the emitters consume obligations.**

- `emitKeySetAgreementPreamble` and `emitBulkKeySetAgreement` fold over `agreementObligations()` instead of rebuilding the WHERE-and-SET intersection: their `keyByColumn` first-contributor-wins maps and the defensive encoder-class fallback are deleted, the value reads staying on the existing `emitAgreementDecodeLocal`/`appendAgreementValue` seam and `requireColumnAgreement`. For self-FK-only inputs the emitted bytes stay identical (the obligations reproduce the same pairs); pin that with the before/after corpus diff from stage 1's gate.
- The two payload-returning UPDATE arms consume obligations the same way, or are proven unreachable for an obligation-carrying carrier (see the four-consumer decision above).
- Obligation columns enter neither `vColNames` nor the per-row cells (they are neither key groups nor set groups), so the bulk derived-table alias stays duplicate-free by construction.
- Sole-contributor case needs no emitter work: the straddler's in-key column arrives as an ordinary `KeyColumn` inside a decoded group, which `keyGroupsOf` already handles, now with the carried slot.

**Stage 4: fixture, schema, coverage, docs.** Detailed in the next two sections.

## Fixture and test plan

**New table pair** in `graphitron-sakila-db/src/main/resources/init.sql`, beside `mailbox`/`email`, mirroring the consumer shape:

```sql
CREATE TABLE catalogue (
    tenant_id      int NOT NULL,
    catalog_code   varchar(20) NOT NULL,
    catalogue_name varchar(100),
    PRIMARY KEY (tenant_id, catalog_code)
);

CREATE TABLE catalogue_item (
    tenant_id    int NOT NULL,
    item_no      int NOT NULL,
    catalog_code varchar(20) NOT NULL,
    item_name    varchar(100),
    PRIMARY KEY (tenant_id, item_no),
    CONSTRAINT catalogue_item_catalogue_fk
        FOREIGN KEY (tenant_id, catalog_code)
        REFERENCES catalogue (tenant_id, catalog_code)
);
```

The pair is deliberately slot-hostile: the `Catalogue` decode record is `(tenant_id, catalog_code)`, so the straddler's single SET column `catalog_code` sits at the second decode slot. Positional inference over the one-column SET group would read the first slot and write the tenant id into the catalogue code; the fixture fails loudly without stage 1. Seed two tenants with two catalogues each and a few items.

**Schema**: `Catalogue` and `CatalogueItem` `@node` types plus `updateCatalogueItem` (single-row) and a bulk list variant in `graphitron-sakila-service`'s `schema.graphqls`, the input carrying `id: ID!` and `catalogueId: ID! @nodeId(typeName: "Catalogue")`.

**Unit tier** (`UpdateRowsWalkerTest`): rework `compositeReferenceStraddlesKey_crossTableFk_rejectsWithMixedCarrierKeyMembership` into partition assertions (in-key column with a second contributor becomes an agreement obligation naming both sides, out-of-key becomes SET); new cases for the nullable straddler rejection (asserting the carried matched key and write target), the sole-contributor in-key column landing in WHERE, the own-columns straddle still rejecting, the two-straddlers-share-a-column tiebreak, and slot values on the flattened rows.

**Pipeline tier** (`MutationDmlNodeIdClassificationTest`): SDL through to an `UpdateRows.Identified` with populated `agreementObligations`, sibling to the existing self-FK case, covering each of the four carrier-consuming emit shapes (direct-return and payload-returning, single-row and bulk) so no consumer can silently drop the component. Partition shape and carried slots are pipeline-tier facts; the agreement call's *presence* is not asserted by grepping generated source (code-string assertions are banned), it is pinned at execution.

**Execution tier** (`graphitron-sakila-example`, new `querydb` test class beside `SelfFkNodeIdUpdateExecutionTest`): single-row repoint within the same tenant succeeds and writes only `catalog_code`; a disagreeing pair (item id and catalogue id from different tenants) throws before any write and names both input fields; the bulk arm covers the same two cases; a payload-returning variant covers whichever of those arms is reachable. Add the generated-SQL baseline to `DmlSqlBaselineTest`.

## Acceptance criteria

- The reworked and new `UpdateRowsWalkerTest` cases pass, including the nullable-straddler rejection and the own-columns narrow.
- The new execution test proves the emitted statement from the contract: same-tenant repoint writes exactly one column, cross-tenant input throws with no row touched, on both arms.
- `SealedHierarchyDocCoverageTest` passes with the new arm documented; `DmlSqlBaselineTest` carries the straddling baseline.
- Stage 1 lands with the before/after generated-source diff empty across the fixture corpus and `graphitron-sakila-example`, and with `ColumnOverlap`'s prose invariant deleted in favour of the carried slot.
- Every one of the four carrier consumers is covered or proven unreachable per the pipeline-tier plan.
- The docs below say what the contract says.

## Documentation

- `docs/manual/reference/directives/mutation.adoc`: the UPDATE partition rule paragraph gains the per-column rule for straddling references and the non-null requirement.
- `docs/manual/reference/directives/nodeId.adoc`: the cross-table FK reference bullet gains the UPDATE straddle behaviour and the immutability rule for the in-key half.
- `docs/architecture/explanation/typed-rejection.adoc`: the `MixedCarrierKeyMembership` paragraph narrows a second time (own-columns only); the new arm gets its paragraph and its roll-call entry.

## Open points for implementation

- Verify whether the UPSERT arm shares `UpdateRowsWalker`'s partition (its `ON CONFLICT` is keyed on the matched key). If it does, decide there: either the same per-column admit extends to it with coverage, or the straddle stays rejected on that arm with an explicit test pinning the rejection.
- Verify whether the payload-returning UPDATE arms can receive a self-FK overlap today. If they can, the absent agreement check there is a pre-existing gap this item closes; land its execution case with stage 3.
- `AgreementObligation` is a name proposal; if the tree already carries better vocabulary for a two-sided obligation row, rename before emitting.
