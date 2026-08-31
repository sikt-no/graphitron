---
id: R880
title: "An explicit null clears a nullable reference on UPDATE, and a straddler is admitted when its identity half is pinned"
status: In Review
bucket: feature
priority: 2
theme: mutation-write
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# An explicit null clears a nullable reference on UPDATE, and a straddler is admitted when its identity half is pinned

## The problem

R784 taught `UpdateRowsWalker` to partition a straddling cross-table `@nodeId` reference per column instead of rejecting it: out-of-key columns become SET writes, in-key columns stay identity. It scoped that to non-null references only. A nullable one still takes `UpdateRowsError.NullableStraddlingReference` unconditionally (`UpdateRowsWalker`, the `CarrierRole.CrossTableFk(var nonNull)` arm, `if (!nonNull)`), and the message tells the author to spell the field `ID!`. On a schema whose owning-tenant column is part of every primary key, that rejection is not an edge case: it is the default outcome for every optional reference on every UPDATE.

The `sis` subgraph of `fs-plattform` hits it 31 times, across 15 mutations and 12 tables (`EKSAMENSTILPASNING`, `PERSON_SPESIALTILPASNING`, `PERMISJON`, `STUDIEPROGRAM`, `UNDERVISNINGSENHET`, `UNDERVISNINGSAKTIVITET`, `EMNEKOMBINASJON`, `EMNEKOMB_I_EMNEKOMB`, `EMNE_I_EMNEKOMBINASJON`, `EMNEVALG_I_EMNEKOMBINASJON`, `UTDANNINGSPLANELEMENT_I_EMNEKOMBINASJON`, `STUDENTKORT`). In all 31 the in-key intersection is exactly `{INSTITUSJONSNR_EIER}` and nothing else. FS keys almost every table on `(INSTITUSJONSNR_EIER, <local key>)` and spells almost every lookup FK as `(INSTITUSJONSNR_EIER, <code>)`, so the owner column is in the key on both sides of every reference by construction. The straddle is a property of the tenancy convention, not of the individual reference.

The advice the message gives is not available to those consumers. `ID!` in an UPDATE input does two things: it removes the ability to clear the reference, and it makes the field mandatory on every call. For 29 of the 31 the underlying column is nullable and NULL is the live encoding of "no reference": no LMS room template (`LMSROMMALKODE`), no campus (`CAMPUSKODE`), no study direction (`STUDIERETNINGKODE`), no exam room (`ROMKODE_SPESIELT`, `BYGNINGSKODE_SPESIELT`), and every optional validity window (`TERMINKODE_START_FRA`/`ARSTALL_START_FRA` and siblings), where "no bound" is the normal state. Making those mandatory would also force a caller renaming one field of a fourteen-field input to resend every reference it did not intend to touch. The remaining 2 (`STUDIEPROGRAM`'s `*_STUDIEANSV` quad, `EMNEKOMBINASJON`'s `*_FAGANSVARLIG` quad) are NOT NULL in the database. Those authors can keep spelling the field `ID!`, and under the rule below the nullable spelling of them is admitted too: a caller who actually sends null then gets the database's own NOT NULL error, which is what a nullable scalar `@field` bound to a NOT NULL column already does today. See "Column nullability is a separate axis" below for why the build does not decide that one for them.

**Clearing is broken well beyond the straddle, which is what fixes the framing.** An explicit null on *any* nullable cross-table `@nodeId` reference in an UPDATE input throws today, straddling or not. `TypeFetcherGenerator.emitSetMapPuts` guards each SET carrier on presence and then declares a decode local as `(wire instanceof String s) ? decode(s) : null`, followed by an unconditional `if (local == null) throw`. An omitted field drops out of SET under PATCH semantics, as intended; a present null reaches the decode, fails the `instanceof`, and surfaces as "Decoded NodeId did not match the expected type", which is not what happened. The bulk arm says the same thing from `emitBulkSetDecodeLocals`. So graphitron has no way to clear an optional reference on an UPDATE at all, and the straddling rejection is one visible corner of that.

**The identity half is the only genuine obstacle.** Relaxing `if (!nonNull)` and letting a nullable straddler fall into the existing per-column loop produces wrong SQL the moment the reference is explicitly null. That loop routes in-key columns to `identityClaims`, which resolve either into a `KeyColumn` (the sole-contributor case: the straddler supplies the WHERE predicate) or into an `AgreementObligation` (something else pins the column; the emitters check the two decoded values agree before any DML runs). Both assume the reference decodes to a present value. Given an explicit null the decode yields NULL in every slot, so the sole-contributor case emits `WHERE INSTITUSJONSNR_EIER = NULL` and matches no row, and the pinned case fails its agreement check against the identity field's real value.

The sole-contributor case is not an emitter problem with a solution. An optional field cannot be load-bearing identity: omitted, it leaves the row unidentifiable, and no per-row conditional recovers a WHERE conjunct that was never sent. So it stays rejected. The pinned case has no such difficulty, and once the sole-contributor case is out, an admitted nullable straddler never holds an identity claim at all. That is what dissolves the "conditional claim" design fork this item was filed with, and with it the bulk arm's feared hazard: a per-row null never drops a claim, never changes which columns the VALUES join names, and never moves a cell position. The agreement obligation is per row and already sits inside the row loop, so skipping it for a cleared row is local.

## What lands

An author can spell an optional cross-table reference on an UPDATE input as `ID`, and three calls behave the three different ways an author expects. Omitting the field leaves the reference alone, as PATCH semantics already promise. Sending an id re-points the reference. Sending an explicit `null` clears it: every column the reference writes is set to NULL in the same statement, and where the reference straddles the matched key, its in-key half is untouched because that half is the row's identity and was never the reference's to write.

Two things follow that an author can see. The 15 `sis` mutations stop failing the build, and the diagnostic that used to reject them narrows to the one shape that genuinely cannot work: an optional reference that is the *only* supplier of a matched-key column, where an omitted value would leave no way to find the row. And clearing works for every nullable reference carrier, not only straddling ones, because the rule the walker states is about what a carrier writes and never asks whether it straddles.

## The rule

Two facts, both decided by `UpdateRowsWalker` once the matched key is known, both stated on the `UpdateRows` carrier so no emitter re-derives either.

**Admission (a straddler's identity half must be pinned elsewhere).** A straddling cross-table `@nodeId` reference partitions per column exactly as R784 established. The `if (!nonNull)` rejection is replaced by a pinning test applied to nullable straddlers only: every in-key column the carrier lifts must have an identity contributor. An *identity contributor* to a column is a carrier that is guaranteed present on every call and whose decode supplies, or can supply, that column's WHERE predicate: a whole carrier other than a self-FK, or a non-null cross-table straddler lifting the column in its in-key half. That is the one definition; the walker's phase split and the SQL refusal predicate below both follow from it, and neither restates it. A nullable straddler is itself neither shape, so self-pinning is excluded by the definition rather than by an extra clause. When every in-key column has one, the carrier contributes SET writes for its out-of-key half and agreement obligations for its in-key half, and supplies no WHERE predicate. When some in-key column has none, `UpdateRowsError.NullableStraddlingReference` fires, naming the unpinned columns.

Claim resolution therefore runs in two stated phases rather than one pass. Whole carriers and non-null straddlers settle the WHERE partition first, in the existing contribution order; nullable straddlers are then measured against the settled set and either admitted or rejected, never claiming. The definition bites exactly where the two phases share a column: a nullable straddler whose in-key column no whole carrier supplies is still admitted when a non-null straddler lifts that column, because the non-null straddler's claim is the column's WHERE predicate and it cannot be absent. The walker's current comment that two straddlers claiming one column "resolve in input-field order; the choice is observationally irrelevant" stops being true as stated and must be requalified: the irrelevance still holds among phase 1's claimants, whose agreement checks run whichever wins, but which carriers may claim at all is now load-bearing, and the phase split is what decides it.

Nullable references still count toward key coverage in stage 3. They must, because straddling is defined against a matched key that does not exist yet, so excluding them would need the answer before the question. A schema whose only supplier of a key column is a nullable reference therefore matches a key and is then rejected by the pinning gate, which is the diagnostic that names the fix.

**What an explicit null means (per carrier, three answers).** For each carrier contributing to the SET partition the walker states one of three things, and the arm set is closed over those carriers. A carrier contributing nothing to SET holds no rule: in particular a nullable reference wholly inside the matched key gets none, so an explicit null on it keeps today's decode-mismatch throw, which is the neighbouring hazard filed as R882 and that item's diagnostic to fix.

- *cannot arrive*: the SDL field is non-null, so GraphQL rejects a null before graphitron sees one. Emit exactly what is emitted today.
- *clears*: the field is nullable and no column this carrier writes is a matched-key column. An explicit null writes NULL to every column the carrier contributes to SET, contributes no value to any agreement check, and leaves the WHERE partition alone.
- *refused as identity*: the field is nullable and some column this carrier writes *is* a matched-key column. Clearing would null the row's own identity, so a null is refused at runtime with a message naming those columns.

The predicate is uniform over all three `CarrierRole` arms and never consults straddling. It is what makes the self-FK case correct for free: a self-FK routes every lifted column to SET, so one overlapping the matched key (the `email.mailbox_id` shape `SelfFkNodeIdUpdateExecutionTest` covers) lands on *refused as identity*, which is right, because clearing it would orphan the row. A straddler's SET partition is its out-of-key half by construction, so a straddler admitted by the gate above is always *clears*.

## Implementation

**`UpdateRowsWalker`.** Lift `nonNull` off `CarrierRole.CrossTableFk` onto `Contribution`: the arm's javadoc justifies the current placement with "nullability rides only on the arm that reads it", which stops being true when all three arms read it. `CrossTableFk` becomes a marker arm and the sealed set is unchanged. Delete the `if (!nonNull)` reject from the straddle branch so every cross-table straddler partitions per column, tagging each in-key `ColumnSlot` with its owner's nullability. Split claim resolution into the two phases above. Compute each SET carrier's null rule once, where `keySqlNames` and the carrier's own SET columns are both in hand.

**Carrier (`no.sikt.graphitron.rewrite.model`).** `UpdateRows.Identified` gains `List<CarrierNullRule> nullRules()`, one row per distinct SET carrier keyed by SDL field name plus extraction, mirroring how `AgreementObligation.Side` names a contributor. `CarrierNullRule(String sdlFieldName, CallSiteExtraction extraction, OnExplicitNull rule)`; `OnExplicitNull` is sealed on `CannotArrive`, `Clears`, and `RefusedAsIdentity(List<ColumnRef> identityColumns)`, the last carrying the columns its message names. The names are this plan's proposal, not a constraint.

The fact deliberately does *not* ride on `SetColumn` or on the emitter's `SetGroup`. `SetGroup` is a shared adapter with three producers (`setGroupsOfFields` for INSERT, `setGroupsOf` for the UPDATE SET partition, `keyColumnsAsSetGroups` for the WHERE partition projected into the same shape), and a clear disposition is meaningless on two of them; a component on `SetColumn` would repeat one carrier-grain answer down N column rows with nothing able to see them disagree. One row per carrier keeps the fact at the grain the walker decided it. `UpdateRows.Identified`'s compact constructor rejects duplicate rules for one carrier, and the emit side resolves a group to its rule through the loud-failure lookup `requireGroup` already models, so a walker and emitter that disagree fail the build rather than silently emitting the wrong branch.

**Emitters, all four `UpdateRows` consumers in `TypeFetcherGenerator`.** Direct-return single-row (`buildMutationUpdateFetcher`), direct-return bulk (`buildBulkUpdateFetcher`), payload single-row (`buildCarrierUpdateChainSingle`) and payload bulk-per-row (`buildCarrierBulkPerRowUpdateBody`). The single-row and payload arms share `emitSetMapPuts`; the bulk VALUES-join arm has its own `emitBulkSetDecodeLocals` / `emitSetBulkCellAdds` pair.

- For a *clears* carrier, split the decode local's current two-way test into three statements rather than widening the existing ternary. `wire == null` is the clear, `wire instanceof String` is the decode, and anything else still throws. This is the load-bearing detail: today `(wire instanceof String s) ? decode(s) : null` collapses an explicit null, a non-string wire value and a wrong-type id onto one `null`, which is harmless only while all three throw. Widening that single branch would turn a malformed request into a silent column clear.
- A cleared carrier writes typed NULL to each of its columns: `sets.put(t.col, DSL.val(null, t.col.getDataType()))` on the single-row and payload arms, and `cells.add(DSL.val(null, t.col.getDataType()))` on the VALUES-join arm. Column membership in `v` is decided by first-row presence and not by nullness, so the alias and every cell position are unchanged; the existing uniform-shape guard already requires every row of a batch to carry the same key set, so a batch cannot mix "clear this row" with "omit on that row".
- For *refused as identity*, keep the throw and replace its message with one naming the field and the identity columns. For *cannot arrive*, emit byte-identical output to today.
- Every site that reads `<decodeLocal>.value<n>()` unguarded is now reachable with a legitimately null local: `emitSetMapPuts`' put loop, `emitSetBulkCellAdds`' disjoint arm, and `emitBulkKeySetAgreement`, which today would NPE on a cleared reference and needs a per-row null guard. `appendAgreementValue` already guards `decodeLocal != null`, but the guard's meaning changes from "a mismatched id, whose throw the other site surfaces" to "cleared, so there is nothing to agree about"; say which meaning each site carries at each site.
- Where a cleared carrier shares a SET column with another writer, it contributes an explicit null to the agreement list rather than being skipped. `NodeIdEncoder.requireColumnAgreement` compares `type.convert(a)` with `Objects.equals`, so null agrees with null and disagrees with a value, which is the wanted reading: one writer clearing while another sets is a conflict.

**Derived facts (`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`).** The same rule is stated a second time as SQL and must move with the walker, or the two derivations disagree exactly on the new case.

- The identity-contributor definition splits the pinning question in two, and the two answers have one reader each, so nothing is lifted. `intent_mutation_write_destination_live`'s existing `pinned` window (whole non-self-FK carriers over the column partition, inside its `disposed` CTE) answers "may a straddler claim this column", stays whole-carrier-only, and keeps its one reader. The refusal view needs the definition's full extension, `identity_pinned`: a window over the same column partition, `MAX` of (`carrier_key_membership = 'WHOLE' AND carrier_role <> 'SELF_FK'`) OR (`carrier_key_membership = 'STRADDLE' AND carrier_role = 'CROSS_TABLE_FK' AND in_key AND non_null`), computed in `intent_mutation_write_refusal`'s own `disposed` CTE where the `writes` disposition already is. Broadening the one window to serve both readers is exactly wrong: it would empty the destination's `claim` CTE for any column two non-null straddlers lift, silently dropping the column's WHERE conjunct.
- What both readers do now need is the carrier's SDL nullability. Carry `non_null` as a column on `intent_mutation_payload_key_membership` (carrier-grain, repeated down the carrier's columns exactly as `carrier_key_membership` is and under the same argument in that table's comment; the mirror of the walker lifting `nonNull` onto `Contribution`). The refusal view's `graphql_field` join exists only to fetch it and comes out.
- `intent_mutation_write_refusal`'s `NULLABLE_STRADDLING_REFERENCE` predicate becomes `carrier_role = 'CROSS_TABLE_FK' AND non_null = FALSE AND in_key AND NOT identity_pinned`. The view comment gains the clause that stops a later reader "fixing" the window's self-inclusion: a nullable reference wholly inside the key matches every conjunct but the last, and escapes only because its own rows are WHOLE and so self-pin; that shape is the neighbouring out-of-scope hazard, deliberately not this refusal's. A straddler is never WHOLE and so never self-pins, which is what makes the same window read as "some other carrier" for the rows this predicate is about.
- The destination view's `claim` CTE gains the `non_null = TRUE` filter the walker's phase split implies (read off the carried column), so a nullable straddler never wins a predicate slot. Between them the two CTE changes transcribe the phase split: claims settle among whole carriers and non-null straddlers, and nullable straddlers are measured against that settled set by the refusal predicate.
- `intent_mutation_write_destination` gains the carrier's null rule beside its `destination` verdict, so a facts-reading emitter is not left able to say which columns a statement assigns but not what an explicit null on them means. Coordinate with R682, which is In Progress over these relations; the diff here is two views, two table columns, and their comments.
- Every view and column comment touched carries argued prose in this file, and the retired MATCH SIMPLE premise (see Retired vocabulary) appears in `intent_mutation_write_refusal`'s comment verbatim.

**Fixture (`graphitron-sakila-db/src/main/resources/init.sql`, `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`).** The existing straddle fixture is the *reject-a-clear* case and stays: `catalogue_item.catalog_code` is NOT NULL, so nothing there can exercise an admitted clear. Add a sibling optional reference on the same table: a `catalogue_shelf` table keyed `(tenant_id, shelf_code)`, a nullable `catalogue_item.shelf_code` column, and a foreign key on `(tenant_id, shelf_code)`, which is a straddler over the same tenant column and whose out-of-key half is nullable. The optional reference is spelled `shelfId: ID @nodeId(typeName: "CatalogueShelf")` on its own `ShelveCatalogueItemInput`, driving `shelve*` mutations, rather than beside `catalogueId` on `UpdateCatalogueItemInput`: that input's `catalogueId` is `ID!` and so mandatory on every call, and an input carrying both would make every clearing call resend the catalogue, which is the cost this shape exists to remove. `CatalogueItem` gains a `shelfCode` field for read-back. Adding an input changes no existing emitted statement.

Add the payload-returning single-row and bulk `updateCatalogueItem` mutations to the same schema. That is the fixture R829 asks for; this item needs it because the clear routes through all four arms and two of them have no execution-tier coverage at all. Whether R829 then closes is R829's own call.

## Tests

Behaviour is pinned at the pipeline tier and above, and no test asserts on generated source strings.

- *Unit* (`UpdateRowsWalkerTest`): `nullableStraddlingReference_rejectsWithCarriedKeyAndWriteTarget` inverts. Its input already has `actorId` and `filmId` pinning the whole key, so the nullable straddler on `(actor_id, last_update)` is now admitted; assert the partition, the slot, the obligation and a *clears* rule. Add the unpinned sibling (the straddler as sole contributor of `actor_id`) as the surviving reject, asserting the named unpinned columns. Add the case that pins the identity-contributor definition itself, because it is the one where a narrower reading (whole carriers pin, straddlers do not) would answer differently: the `twoStraddlersSharingAnInKeyColumn_firstInInputOrderPinsIt` shape with `second` spelled nullable, where `actor_id` is in the matched key and no whole carrier supplies it. Assert admission, `first` still pinning `actor_id`, and the nullable carrier holding the agreement obligation and a *clears* rule. Add a self-FK overlapping the matched key, asserting *refused as identity*, and a nullable non-straddling reference, asserting *clears*.
- *Pipeline* (`MutationDmlNodeIdClassificationTest`): `nullableStraddlingReference_update_rejectsAtBuildTime` inverts for the same reason. Extend `straddlingReference_update_allFourCarrierConsumers_seeTheSamePartitionAndObligations` to assert the null rule on all four carriers, which is what stops a consumer dropping it silently. The fact that a *clears* carrier keeps a failed decode distinct from an explicit null is pinned at the execution tier rather than on the emitted source: an id that does not decode still throws and writes nothing, on the single-row and VALUES-join arms alike. Only the database distinguishes the two outcomes, and a source-shape assertion would pin javapoet's rendering rather than the behaviour.
- *Derived facts*: `MutationWriteRefusalTest`'s `aNullableStraddlingReferenceIsRefused` narrows to the unpinned case and gains two admitted siblings, one per arm of `identity_pinned`: pinned by a whole carrier, and pinned by a non-null straddler alone; `MutationWriteDestinationTest` gains the null-rule rows. Add the missing shadow leg for this family under `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/` (the `ColumnMatchShadowTest` / `DemandShadowTest` / `InputOccurrenceShadowTest` pattern: every document of `CorpusDocuments.documents()` captured as its own graph through `CapturedStore.ofCatalog`, then the walker's answer and the store's compared per graph; `MaterializedRegistryFixture` is the statistics tests' fixture and no shadow test's), scoped to the write-refusal causes: per corpus document, the set of `UpdateRowsError` values the walker returns must equal the set of `intent_mutation_write_refusal` rows, by coordinate and cause. The sweep proves nothing about this rule unless the corpus carries the shapes that discriminate it, and today it carries no straddling UPDATE at all, so the corpus gains a document with the two-straddler shape (an UPDATE input with two cross-table straddlers on one in-key column no whole carrier supplies, one of them nullable: admitted, both sides silent) and the surviving reject (a nullable straddler as a key column's sole supplier). That first shape is exactly the one where the walker rule and the SQL rule diverged in this item's own round-1 draft, which is what the shadow leg is for. Without it the walker rule and the SQL rule agree only by hand, and a re-seeded expectation goes green while the two have diverged.
- *Execution* (`StraddlingReferenceUpdateExecutionTest`, extended or a sibling class on the shelf fixture): an explicit null clears `shelf_code` and leaves `tenant_id`, the row's location and `catalog_code` untouched; the foreign key stays satisfied afterwards, which is the MATCH SIMPLE claim this item rests on, so assert it by reading the row back rather than by trusting the absence of an error; an omitted `shelfId` leaves the existing value; a cross-tenant shelf still throws with nothing written; a well-formed id of the wrong type throws too, and leaves the column at its old value rather than clearing it. All four arms, single-row and bulk, direct-return and payload.
- *Emitted SQL* (`DmlSqlBaselineTest`): the clear's statement on the single-row and bulk arms, so a regression that stops binding the null, or that moves a `v(...)` column, fails on the statement rather than on a value.
- *Permit coverage*: `RejectionSeverityCoverageTest`'s sample construction follows `NullableStraddlingReference`'s components, and `SealedHierarchyDocCoverageTest` pins the permit-to-documentation mapping, so the arm's javadoc rewrite is build-visible. `RejectionResidueDrainageTest` keeps the permit named.

## User documentation (first-client check)

`docs/manual/reference/directives/nodeId.adoc` currently closes its UPDATE paragraph with "Because that in-key half is identity, the reference can be re-pointed only within the same key value and can never be cleared, so it must be spelled `ID!`; the build rejects the nullable form where it straddles." That sentence is the feature this item removes, and its replacement is the whole user-facing surface. Draft:

> On an `@mutation(typeName: UPDATE)` input those child columns can overlap the row's own key, which happens whenever the two tables share a qualifying column such as a tenant or institution number. The field then partitions per column: the columns outside the matched key are written, and the ones inside it are the row's identity, never written and checked against whatever else supplies them before the statement runs. A nullable (`ID`) reference can be cleared: an explicit `null` writes `NULL` to the columns the reference owns, leaving the identity columns as the rest of the input set them. A half-null foreign key imposes no referential obligation under PostgreSQL's default `MATCH SIMPLE`, so a cleared reference is an absent one rather than a dangling one. The build rejects one shape only: an optional reference that is the sole supplier of one of the matched key's columns, since an omitted value would leave nothing to find the row by. Give that column another contributor, or spell the reference `ID!`.

`docs/architecture/explanation/typed-rejection.adoc` states the same rule for contributors in its `UpdateRowsError` paragraphs and moves with it.

## Column nullability is a separate axis

This plan does not gate admission on whether the written columns are NOT NULL, which is the criterion the item was filed with. The walker reads no column nullability anywhere today; a nullable scalar `@field` bound to a NOT NULL column is admitted and emits a typed NULL the database rejects, and `sql_column.nullable` is captured but no walker consumes it. Adding the check on this one arm would key a rejection on the cross product of (cross-table FK) and (straddles the matched key) and (writes a NOT NULL column), while the identical hazard on a plain field stays unchecked and the cross product falls to hand maintenance. Column nullability is an independent axis over every write carrier, and it is filed as R881, which carries the three candidate answers and the argument for each. This item is not blocked on it: the 2 NOT NULL quads keep the `ID!` spelling that works today, and the nullable spelling of them gets the database's own error.

## Out of scope

- A nullable carrier whose columns fall *wholly* inside the matched key is the same "optional field as load-bearing identity" hazard one step over, and is not touched here: generalising the gate would fail schemas that build today, which needs its own measurement. Filed as R882.
- `@mutation(typeName: INSERT)` and the `@service` jOOQ-record path have their own null handling and are unchanged.
- R829's remaining claim, that the payload-returning arms' agreement preamble is unexercised, is not resolved here; this item adds the fixture it names and covers the clear on those arms.

## Retired vocabulary

- `CarrierRole.CrossTableFk(boolean nonNull)`: the component moves to `Contribution.nonNull`, and the arm's javadoc claim that "nullability rides only on the arm that reads it" goes with it.
- The unqualified claim that which straddler pins a contested column is "observationally irrelevant", in `UpdateRowsWalker`'s claim-resolution comment, the body comment of `UpdateRowsWalkerTest.twoStraddlersSharingAnInKeyColumn_firstInInputOrderPinsIt`, and `intent_mutation_write_destination`'s table comment. The qualified form survives at all three sites: irrelevant among phase 1's claimants, whose agreement checks run whichever wins, while which carriers may claim at all is load-bearing.
- The claim that a straddling reference "can never be cleared" and its advice to "spell the field non-null (`ID!`)" as the general fix, in `UpdateRowsError.NullableStraddlingReference`'s message and javadoc, `UpdateRowsWalker`'s stage-6 comment, `docs/manual/reference/directives/nodeId.adoc`, `docs/architecture/explanation/typed-rejection.adoc`, and the `intent_mutation_write_refusal` view comment.
- The premise that "MATCH SIMPLE accepts that half-null tuple, so the row would keep a dangling reference", which reads the constraint backwards: a half-null tuple imposes no referential obligation, so it is an absent reference and not a dangling one.

## Consumer note

`sis` is on Graphitron 10 and currently carries 31 schema errors in total, 15 of them this rejection. The walker reports one error per mutation, so those 15 diagnostics stand in for the 31 offending fields; the other 16 surface only as each preceding one is resolved. Every one of the 15 inputs spells identity as a single bare `id: ID!`, a whole-key carrier, so in all 31 fields the in-key intersection `{INSTITUSJONSNR_EIER}` is pinned by a whole carrier and the admission gate turns on nothing subtler there; the two-straddler shape the unit tier pins is reachable in principle but occurs in no consumer currently measured. Graphitron 9 bound these fields to `kjerneapi-codegen` record accessors over column tuples rather than to columns, so there is no v9 statement to compare against; unlike R784, this is a shape v9 expressed differently rather than one it generated correctly. Related: R784, which established the per-column partition and the obligation-consuming emitters this item extends; R829, whose named fixture (payload-returning UPDATE mutations over an overlapping reference) this item adds because the clear routes through those arms; R881 and R882, the two axes split out of this one under "Column nullability is a separate axis" and "Out of scope"; R682, which is In Progress over the derived-fact views this item also edits.

## Reviewer findings

### Round 1 (2026-08-31, Spec -> Ready, reviewer session 015TFrZNvh6yW9xPCoT7iZaZ)

Verdict: withhold. One blocking finding on question two, one supporting finding on the same
question, three non-blocking notes.

*What was checked and holds.* Question one is answered well: an author gets to spell an optional
cross-table reference `ID` on an UPDATE input, an explicit `null` clears the columns that reference
owns while leaving the row's identity alone, and the only surviving rejection is the shape where the
optional reference is the sole thing pinning a key column. That reads off the item without
reconstructing it from the phase list, and the reframing from "gate the straddle" to "clearing is
broken for every nullable reference carrier" is the strongest thing in the item.

Every symbol the plan names exists as named. `UpdateRowsWalker`'s `CarrierRole.CrossTableFk(var
nonNull)` arm and its `if (!nonNull)` reject sit where the plan says (the straddle branch of stage
6), the javadoc does carry "Nullability rides only on the arm that reads it", and the stage-6 claim
comment does carry "resolve in input-field order; the choice is observationally irrelevant".
`appendDecodeLocal` is exactly the collapse the plan calls load-bearing: `(wire instanceof String
_s) ? decode(_s) : null` followed by an unconditional `if (local == null) throw "Decoded NodeId did
not match the expected type"`, and `emitBulkSetDecodeLocals` says the same with a presence-gated
throw. All four `UpdateRows` consumers exist under the given names, as do
`emitSetMapPuts`, `emitSetBulkCellAdds`, `emitBulkKeySetAgreement`, `appendAgreementValue` (which
does guard `decodeLocal != null`), `requireGroup`, and the three `SetGroup` producers the plan cites
as its reason not to hang the fact there. `requireColumnAgreement` is
`Objects.equals(type.convert(a), type.convert(b))`, so the null-agrees-with-null reading is right.
The bulk uniform-shape guard exists and throws on a differing present-key set, so the plan's claim
that a batch cannot mix a clear with an omission holds, and since an explicitly-null key is a
present key the first-row-presence argument about `v(...)` column membership holds too. On the SQL
side `pinned`, the `disposed` CTE, the `claim` CTE, `intent_mutation_write_refusal`'s
`NULLABLE_STRADDLING_REFERENCE` branch and `intent_mutation_payload_key_membership` (plus its
`_live` view) are all as described, and the destination view really does already read the refusal
view through its `admitted` CTE, so the forced-duplicate argument for lifting `pinned` is sound.
`catalogue_item.catalog_code` is NOT NULL as claimed, no `catalogue_shelf` or `shelf_code` exists
yet, and every named test class and test method exists. `sql_column.nullable` is captured and no
walker reads column nullability, so the R881 split is argued from the true state of the tree. The
two doc sentences the item retires exist verbatim, including the MATCH SIMPLE premise in
`typed-rejection.adoc`.

**Finding 1 (question two: the plan's central predicate is stated twice, with two different
extensions, and the difference is reachable).** The Rule section says a nullable straddler is
admitted when "every in-key column the carrier lifts must already have an identity contributor
other than this carrier", and the phase split names who settles that: "Whole carriers *and non-null
straddlers* settle the WHERE partition first ... nullable straddlers are then measured against the
settled set". So a non-null straddler that won its claim counts as a pin. The Implementation section
then states the same rule as `carrier_role = 'CROSS_TABLE_FK' AND f.non_null = FALSE AND in_key AND
NOT pinned`, over the existing `pinned` window lifted unchanged, and that window is `MAX(CASE WHEN
p.carrier_key_membership = 'WHOLE' AND p.carrier_role <> 'SELF_FK' THEN 1 ELSE 0 END)`: whole
carriers only. A non-null straddler pins under the walker rule and does not pin under the SQL rule.

The divergence is not hypothetical, and the tree already fixtures the shape it needs.
`UpdateRowsWalkerTest.twoStraddlersSharingAnInKeyColumn_firstInInputOrderPinsIt` walks `film_actor`
with `filmId` plus two straddlers on `(actor_id, last_update)`, where `actor_id` is in the matched
key and no whole carrier supplies it: `first` pins it and `second` contributes an obligation. Spell
`second` nullable and the walker admits it while the SQL refuses the whole payload. That case is
also the plausible consumer shape rather than a curiosity, since the item's own measurement says all
31 `sis` references straddle on `INSTITUSJONSNR_EIER` and the tenant column is typically supplied by
references rather than by a field of its own.

The two readings are not interchangeable, which is why this needs the author and not the
implementer. Under the broad reading the plan's "lift it ... and have both readers join it" does not
work: broadening `pinned` to count non-null straddlers empties the `claim` CTE for any column
carrying two or more of them, and the column silently loses its WHERE conjunct, so the refusal
reader needs a second and wider pinning expression rather than the same column. Under the narrow
reading the lift works as written, but "and non-null straddlers" has to come out of the phase-split
sentence, and the rejection surface is wider than the Rule section currently promises.

What would satisfy it: pick one reading, state it once in the Rule section as the definition of
"identity contributor", and make the Implementation section's SQL follow from it (one lifted column
under the narrow reading, two pinning notions under the broad one). Then add the unit case that
pins the answer, a nullable second straddler over an in-key column no whole carrier supplies. The
proposed `catalogue_shelf` fixture cannot see this: `UpdateCatalogueItemInput.id` is
`ID! @nodeId(typeName: "CatalogueItem")`, a whole-key carrier, so `tenant_id` is WHOLE-pinned there
and both readings agree on the whole execution tier.

*Author response (2026-08-31):* Resolved to the broad reading, defined once in the Rule section: an
identity contributor is a carrier guaranteed present on every call whose decode supplies or can
supply the column's WHERE predicate, which is a whole non-self-FK carrier or a non-null cross-table
straddler's in-key half. The broad reading is what the walker's mechanics already say (a straddler's
winning claim becomes the `KeyColumn` the WHERE reads, and a non-null field cannot be absent), and
the narrow one would refuse schemas whose statements are perfectly emittable. The Implementation
section now follows the reviewer's own analysis of the consequence: the lift is withdrawn, since the
two readers need different windows with one reader each; `pinned` stays whole-carrier-only in the
destination view, the refusal view gains its own wider `identity_pinned` window, and what moves onto
`intent_mutation_payload_key_membership` is `non_null`, which both readers need. The requested unit
case (the `twoStraddlersSharingAnInKeyColumn` shape with `second` nullable) is added to the Tests
section, with its derived-facts sibling and a corpus document carrying the shape so the shadow leg
sweeps it.

*Correction from the measuring session (relayed 2026-08-31):* the finding's plausibility argument
misreads the `sis` measurement. Every one of the 15 affected `sis` UPDATE inputs spells identity as
a single bare `id: ID!`, which is necessarily a whole-key carrier there (in
`OppdaterStudieoppbygningsdelerInput` the matched key is `{INSTITUSJONSNR_EIER,
EMNEKOMBINASJONSKODE}` and `EMNEKOMBINASJONSKODE` has no contributor other than `id`), so
`INSTITUSJONSNR_EIER` is WHOLE-pinned in all 31 fields and both readings of the predicate agree
across the entire motivating consumer. The references straddle on the tenant column; none supplies
it as its only source. This does not touch the finding's blocking status: the inconsistency was
real and reachable, exactly as the `film_actor` shape shows. It changes what carries the decision.
The broad reading rests on the walker's mechanics alone, not on consumer pressure, and the
reviewer's observation that the `catalogue_shelf` fixture cannot see the divergence extends
further than stated: no consumer shape currently known can, which is what makes the requested unit
case the only place the answer is ever pinned.

**Finding 2 (question two, supporting: the shadow leg is pointed at the wrong corpus).** The Tests
section describes the new shadow leg as "the `ColumnMatchShadowTest` / `DemandShadowTest` /
`InputOccurrenceShadowTest` pattern, over `MaterializedRegistryFixture`". Those three tests do not
use `MaterializedRegistryFixture`; they sweep `CorpusDocuments.documents()` captured through
`CapturedStore.ofCatalog`. `MaterializedRegistryFixture` is used by `DerivedReadCostTest`,
`RefreshPlanStatisticsTest` and `RefreshPrerequisiteStatisticsTest`, and by no shadow test. This is
more than a wrong name, because the shadow leg is the plan's own stated defence against the two
statements of the rule drifting, which is exactly what Finding 1 describes: whether it can see that
drift depends on the swept corpus carrying an UPDATE mutation with two straddlers on one in-key
column, one of them nullable. Naming the real fixture and saying what the corpus must contain is
what would satisfy this.

*Author response (2026-08-31):* The Tests section now names the real pattern (every document of
`CorpusDocuments.documents()` captured as its own graph through `CapturedStore.ofCatalog`, walker
and store compared per graph; `MaterializedRegistryFixture` corrected to the statistics tests'
fixture, used by no shadow test), states the shadow leg's comparison (the walker's
`UpdateRowsError` set against `intent_mutation_write_refusal`'s rows, by coordinate and cause), and
states what the corpus must gain: it carries no straddling UPDATE today, so it gets a document with
the two-straddler nullable-second shape and one with the sole-supplier reject.

*Non-blocking.* Three things noticed that do not bear on either question.

- The refusal predicate's silence about `carrier_key_membership` is sound but only by a subtlety
  worth writing down. `carrier_role = 'CROSS_TABLE_FK' AND f.non_null = FALSE AND in_key` also
  matches a nullable cross-table reference whose columns fall *wholly* inside the key, the shape
  handed to R882, and that shape escapes refusal only because `pinned` counts the carrier's own
  WHOLE rows and so self-pins. A straddler is never WHOLE, so it never self-pins, which is why the
  same window reads as "some other carrier" there. One clause in the view comment would stop a later
  reader from "fixing" the self-inclusion.
- A nullable carrier lying wholly inside the key gets no `CarrierNullRule` at all, since the rule is
  stated per carrier contributing to the SET partition, so an explicit null on it still lands on
  today's "Decoded NodeId did not match the expected type" throw. That is right for this item's
  scope and is R882's diagnostic to fix; noting it only because "the arm set is closed" reads as
  closed over all carriers rather than over SET carriers.
- The Retired vocabulary section names `UpdateRowsWalker`'s stage-6 comment as carrying the
  "observationally irrelevant" claim the phase split falsifies, but the same claim is also in the
  body of `UpdateRowsWalkerTest.twoStraddlersSharingAnInKeyColumn_firstInInputOrderPinsIt`, which
  the Tests section does not mention.

*Author response (2026-08-31):* All three taken. The self-inclusion clause is now a stated part of
the refusal view's comment in the Implementation section; the Rule section says the arm set closes
over SET carriers and names the wholly-in-key nullable reference as keeping today's throw; and the
Retired vocabulary entry now covers all three sites carrying the unqualified "observationally
irrelevant" claim (the walker's claim-resolution comment, the test body, and
`intent_mutation_write_destination`'s table comment), with the qualified form that survives.

### Round 1 (2026-08-31, In Review -> Done, reviewer session 01FdSYTkUbW5EpbRfT2iDNTo)

Verdict: rework. One blocking finding on question two, two further defects worth fixing in the same
pass, one non-blocking note. Landing SHA reviewed: `a7d7de9`. `mvn install -Plocal-db` passes
(BUILD SUCCESS, 12:35) on the delivered tree rebased onto trunk.

*What was checked and holds.* Question one is answered. The walker's pinning gate is the spec's
definition and nothing wider: a nullable straddler's in-key columns are collected aside, phase 1
settles `keyBySqlName` from whole non-self-FK carriers and non-null straddlers' winning claims
(`!claim.owner().nonNull()` is what keeps a nullable one from ever claiming), and phase 2 measures
membership in that map, which is the definition applied once rather than twice. Moving the stage's
early return below the gate is sound: a rejected own-columns straddler contributes to neither
partition, so the resolution runs over a smaller input, not an inconsistent one. Stage 7a's null
rule is uniform over the three roles and reads only SDL nullability against the carrier's own SET
columns, so the self-FK overlap lands on *refused as identity* and an admitted straddler on *clears*
by construction, exactly as argued. `CannotArrive` routes through the unchanged `appendDecodeLocal`
overload, so its output really is byte-identical. The three-statement split is there and is the
shape the plan called load-bearing.

The fourth `.value<n>()` site the plan did not list, `emitKeySetAgreementPreamble`, was checked and
needs nothing: it re-decodes into its own preamble local through `emitAgreementDecodeLocal` and
already guards `presence && local != null`, so a cleared reference drops out of the single-row
cross-partition check rather than NPE-ing. The bulk sibling's added `!= null` gate indexes
`bulkSetKey_<rgi>` off the same `setGroups` position `emitBulkSetDecodeLocals` names, so the locals
line up.

The SQL follows from the same definition. `identity_pinned` is the definition's full extension and
`pinned` stays whole-carrier-only with one reader each, as round 1 of the Spec gate concluded. The
second arm of the refusal predicate drops `carrier_key_membership` deliberately and the
wholly-in-key nullable reference escapes by self-pinning through the WHOLE arm, which matches the
walker (that shape reaches `addKeyColumns` and is never collected as a straddler). Retirement sweep
is clean: all three "observationally irrelevant" sites carry the qualified form, the
`CrossTableFk(boolean nonNull)` component and its javadoc claim are gone, and the "can never be
cleared" advice and the backwards MATCH SIMPLE premise are gone from the error arm, the walker, both
`.adoc` pages and the view comment. User-facing-doc check: `nodeId.adoc` carries the drafted
replacement and `typed-rejection.adoc` moved with it.

Question two's evidence is otherwise strong and was verified rather than assumed. The unit tier
carries the discriminating case the Spec gate demanded
(`nullableStraddler_pinnedByANonNullStraddlerAlone_isAdmitted`); `assertStraddlePartition` is the
shared helper all four consumer tests call, so the null rule really is asserted on all four; the new
corpus document is the two-straddler shape and its `@expectEquals` names exactly one refusal row, so
a narrow SQL reading would fail the build there; `WriteRefusalShadowTest` is honest about comparing
coordinates both ways and causes one way; the execution tier covers clear, omit, re-point and the
cross-tenant refusal across all four arms, reading the row back and asserting the shelf is
unreferenced afterwards rather than trusting the absence of an error; and the SQL baselines pin the
`v(...)` alias, which is the cell-position claim.

**Finding 1 (question two, blocking: the one piece of named evidence that is delivered by a banned
mechanism, and it is too weak to carry its own claim).** The Tests section asks `FetcherPipelineTest`
for "the structural pin that a *clears* carrier emits a null branch distinct from its decode branch".
What shipped is `dmlUpdateClearingReference_emitsANullBranchApartFromTheDecodeBranch`, which reads
`method(spec, "updateCatalogueItem").code().toString()` and asserts `.contains("Wire = ")`,
`.contains("!= null && ")`, `.contains("== null ? null : ")` and `.contains("cells.add(")`. Those are
code-string assertions on a generated method body, which
`docs/architecture/principles/development-principles.adoc` bans at every tier and says is
"review-enforced at test-review time"; this gate is that enforcement point, and R873 was sent back
for the same thing the day before, its fix being to derive the expected text from the production
producer rather than transcribe it.

Two things make this more than a rule citation. The fragments are precisely the "break on every
refactor" kind the principle names: rewriting the ternary as an if/else, or renaming the wire
local's suffix, fails the test with no behaviour change. And they do not assert what the test's own
name claims, because none of them is tied to the `shelfId` carrier: `!= null && ` matches any null
guard anywhere in the method, and the test would still pass with the null branch attached to the
wrong carrier. The neighbouring UPDATE tests in the same file carry the same pattern, but those
predate this item and are a separate matter; the one nearby test that argues for body-content
assertions does so on the ground that "the return-half re-projection has no structural equivalent",
which is not the case here.

What would satisfy it: drop the test, or restate the claim so it is derived rather than transcribed
(R873's `asRenderedMethodBody` shape, or an assertion over the emitted structure). Dropping it costs
the item nothing, which is the point: the behaviour is already pinned by
`clearingReferenceSingleUpdate_bindsTheNullAsAnOrdinarySetColumn` and
`clearingReferenceBulkUpdate_keepsTheColumnInTheValuesAlias` on the statement, by the four execution
tests on the database, and by the null-rule assertions at the model tier. This finding is about the
mechanism, not about a hole in the coverage.

*Implementer response (2026-08-31):* Test dropped, and the claim it was reaching for restated
behaviourally instead of derived on the source. The finding is right that the fragments assert
javapoet's rendering rather than the split, and right that dropping costs the item nothing; what the
drop does lose is the only statement anywhere that a *failed decode* on a clearing carrier is still
a refusal rather than a clear, which is the hazard the three-statement split exists for and the one
the listed coverage does not reach (the statement baselines and the four execution tests all send
either a valid id or an explicit null). That distinction is observable at the tier that can see it:
`StraddlingReferenceUpdateExecutionTest` gains `wrongTypeShelfId_throwsRatherThanClearing` and its
bulk sibling, which send a well-formed `Catalogue` id into `shelfId`, assert the call errors, and
assert the column still holds its old value rather than the null a collapsed branch would have
written. Both arms that declare their own decode local are covered. The Tests section's
`FetcherPipelineTest` sentence is replaced by that, and says why the tier moved.

**Finding 2 (the refusal view's comment states the rule backwards in its defining sentence).**
`intent_mutation_write_refusal`'s comment now reads "NULLABLE_STRADDLING_REFERENCE is a cross-table
reference in the same position, where the split itself is legitimate and the spelling is, but only
where the reference is the sole supplier of one of the key''s columns." Read as written, the
spelling is legitimate *only* where the reference is the sole supplier, which is the inverse of the
rule; the four sentences after it state the rule correctly. This is not a typo in a private comment:
the view comment is the cause's published definition and renders into
`docs/architecture/reference/schema/intent.adoc` on the docs site, so the contributor-facing page
currently opens its account of this cause with the rule reversed. Repair the clause (for example
"...the split itself is legitimate and so is the spelling, except where the reference is the sole
supplier of one of the key's columns").

*Implementer response (2026-08-31):* Taken as suggested; the clause now reads "the split itself is
legitimate and so is the spelling, except where the reference is the sole supplier of one of the
key''s columns", which is the rule the sentences after it already stated.

**Finding 3 (`UpdateRows.java` ships a raw NUL byte and is no longer a text file).**
`UpdateRows.Identified`'s duplicate-rule guard keys its `HashSet` on
`r.sdlFieldName() + "<U+0000>" + r.extraction()`, written as a literal NUL character in the source
rather than as the escape `"\0"`. The behaviour is right and it compiles, but git now classifies the
file as binary: `git diff` on it prints "Binary files differ" instead of a diff, and text tooling
stops seeing it. That is a durable cost on a carrier file every future reader of this area opens,
and it was invisible to this gate's own review of the change. Write the separator as `"\0"` (or any
ordinary delimiter) so the file is text again.

*Implementer response (2026-08-31):* Separator written as the escape `"\0"`, so the source is ASCII
again and `git diff` prints a diff. The behaviour is unchanged: the separator is still a character no
SDL field name can carry.

*Non-blocking.* The fixture deviates from the plan and deviates well: instead of adding `shelfId` to
`UpdateCatalogueItemInput` and payload-returning `updateCatalogueItem` mutations, it adds a separate
`ShelveCatalogueItemInput` and four `shelve*` mutations, on the argument that an `ID!` sibling on the
same input would force every clearing call to resend the catalogue, which is the cost this item
exists to remove. That argument is right and is recorded durably in the schema fixture's own comment
and in the commit message, so it is noted rather than charged; the delivery covers everything the
plan's fixture asked for and adds the two payload arms it named. The Implementation section's
"`UpdateCatalogueItemInput` gains `shelfId`" sentence is the one line of the spec body that no longer
describes the tree.

*Implementer response (2026-08-31):* That sentence now describes the delivered fixture and carries
the argument for the separate input, so the spec body and the tree agree again.
