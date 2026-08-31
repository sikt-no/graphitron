---
id: R880
title: "An explicit null clears a nullable reference on UPDATE, and a straddler is admitted when its identity half is pinned"
status: Spec
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

**Admission (a straddler's identity half must be pinned elsewhere).** A straddling cross-table `@nodeId` reference partitions per column exactly as R784 established. The `if (!nonNull)` rejection is replaced by a pinning test applied to nullable straddlers only: every in-key column the carrier lifts must already have an identity contributor other than this carrier. When it does, the carrier contributes SET writes for its out-of-key half and agreement obligations for its in-key half, and supplies no WHERE predicate. When some in-key column has no other contributor, `UpdateRowsError.NullableStraddlingReference` fires, naming the unpinned columns.

Claim resolution therefore runs in two stated phases rather than one pass. Whole carriers and non-null straddlers settle the WHERE partition first, in the existing contribution order; nullable straddlers are then measured against the settled set and either admitted or rejected, never claiming. The walker's current comment that two straddlers claiming one column "resolve in input-field order; the choice is observationally irrelevant" stops being true and must be corrected: with nullable straddlers in play, the phase split is what decides which carrier pins.

Nullable references still count toward key coverage in stage 3. They must, because straddling is defined against a matched key that does not exist yet, so excluding them would need the answer before the question. A schema whose only supplier of a key column is a nullable reference therefore matches a key and is then rejected by the pinning gate, which is the diagnostic that names the fix.

**What an explicit null means (per carrier, three answers).** For each carrier contributing to the SET partition the walker states one of three things, and the arm set is closed:

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

- `pinned` is today a window inside `intent_mutation_write_destination_live`'s `disposed` CTE, and the new admission rule gives it a second reader in `intent_mutation_write_refusal`. Because the destination view already reads the refusal view, copying the window would be a forced duplicate rather than an optional one. Lift it: carry `pinned` as a column on `intent_mutation_payload_key_membership` (computed in its `_live` view, alongside the carrier-grain `carrier_key_membership` its comment already argues for carrying down a carrier's columns), and have both readers join it.
- `intent_mutation_write_refusal`'s `NULLABLE_STRADDLING_REFERENCE` predicate becomes `carrier_role = 'CROSS_TABLE_FK' AND f.non_null = FALSE AND in_key AND NOT pinned`.
- The destination view's `claim` CTE gains the `f.non_null = TRUE` filter the walker's phase split implies, so a nullable straddler never wins a predicate slot.
- `intent_mutation_write_destination` gains the carrier's null rule beside its `destination` verdict, so a facts-reading emitter is not left able to say which columns a statement assigns but not what an explicit null on them means. Coordinate with R682, which is In Progress over these relations; the diff here is two views, one table column, and their comments.
- Every view and column comment touched carries argued prose in this file, and the retired MATCH SIMPLE premise (see Retired vocabulary) appears in `intent_mutation_write_refusal`'s comment verbatim.

**Fixture (`graphitron-sakila-db/src/main/resources/init.sql`, `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`).** The existing straddle fixture is the *reject-a-clear* case and stays: `catalogue_item.catalog_code` is NOT NULL, so nothing there can exercise an admitted clear. Add a sibling optional reference on the same table: a `catalogue_shelf` table keyed `(tenant_id, shelf_code)`, a nullable `catalogue_item.shelf_code` column, and a foreign key on `(tenant_id, shelf_code)`, which is a straddler over the same tenant column and whose out-of-key half is nullable. `UpdateCatalogueItemInput` gains `shelfId: ID @nodeId(typeName: "CatalogueShelf")` and `CatalogueItem` a `shelfCode` field for read-back. Adding an optional input field changes no existing emitted statement, because both arms decide SET membership by presence.

Add the payload-returning single-row and bulk `updateCatalogueItem` mutations to the same schema. That is the fixture R829 asks for; this item needs it because the clear routes through all four arms and two of them have no execution-tier coverage at all. Whether R829 then closes is R829's own call.

## Tests

Behaviour is pinned at the pipeline tier and above, and no test asserts on generated source strings.

- *Unit* (`UpdateRowsWalkerTest`): `nullableStraddlingReference_rejectsWithCarriedKeyAndWriteTarget` inverts. Its input already has `actorId` and `filmId` pinning the whole key, so the nullable straddler on `(actor_id, last_update)` is now admitted; assert the partition, the slot, the obligation and a *clears* rule. Add the unpinned sibling (the straddler as sole contributor of `actor_id`) as the surviving reject, asserting the named unpinned columns. Add a self-FK overlapping the matched key, asserting *refused as identity*, and a nullable non-straddling reference, asserting *clears*.
- *Pipeline* (`MutationDmlNodeIdClassificationTest`): `nullableStraddlingReference_update_rejectsAtBuildTime` inverts for the same reason. Extend `straddlingReference_update_allFourCarrierConsumers_seeTheSamePartitionAndObligations` to assert the null rule on all four carriers, which is what stops a consumer dropping it silently. `FetcherPipelineTest` gains the structural pin that a *clears* carrier emits a null branch distinct from its decode branch on the single-row and VALUES-join arms.
- *Derived facts*: `MutationWriteRefusalTest`'s `aNullableStraddlingReferenceIsRefused` narrows to the unpinned case and gains a pinned-and-admitted sibling; `MutationWriteDestinationTest` gains the null-rule rows. Add the missing shadow leg for this family under `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/` (the `ColumnMatchShadowTest` / `DemandShadowTest` / `InputOccurrenceShadowTest` pattern, over `MaterializedRegistryFixture`), scoped to the write-refusal causes. Without it the walker rule and the SQL rule agree only by hand, and a re-seeded expectation goes green while the two have diverged.
- *Execution* (`StraddlingReferenceUpdateExecutionTest`, extended or a sibling class on the shelf fixture): an explicit null clears `shelf_code` and leaves `tenant_id`, the row's location and `catalog_code` untouched; the foreign key stays satisfied afterwards, which is the MATCH SIMPLE claim this item rests on, so assert it by reading the row back rather than by trusting the absence of an error; an omitted `shelfId` leaves the existing value; a cross-tenant shelf still throws with nothing written. All four arms, single-row and bulk, direct-return and payload.
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
- The claim that a straddling reference "can never be cleared" and its advice to "spell the field non-null (`ID!`)" as the general fix, in `UpdateRowsError.NullableStraddlingReference`'s message and javadoc, `UpdateRowsWalker`'s stage-6 comment, `docs/manual/reference/directives/nodeId.adoc`, `docs/architecture/explanation/typed-rejection.adoc`, and the `intent_mutation_write_refusal` view comment.
- The premise that "MATCH SIMPLE accepts that half-null tuple, so the row would keep a dangling reference", which reads the constraint backwards: a half-null tuple imposes no referential obligation, so it is an absent reference and not a dangling one.

## Consumer note

`sis` is on Graphitron 10 and currently carries 31 schema errors in total, 15 of them this rejection. The walker reports one error per mutation, so those 15 diagnostics stand in for the 31 offending fields; the other 16 surface only as each preceding one is resolved. Graphitron 9 bound these fields to `kjerneapi-codegen` record accessors over column tuples rather than to columns, so there is no v9 statement to compare against; unlike R784, this is a shape v9 expressed differently rather than one it generated correctly. Related: R784, which established the per-column partition and the obligation-consuming emitters this item extends; R829, whose named fixture (payload-returning UPDATE mutations over an overlapping reference) this item adds because the clear routes through those arms; R881 and R882, the two axes split out of this one under "Column nullability is a separate axis" and "Out of scope"; R682, which is In Progress over the derived-fact views this item also edits.

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
