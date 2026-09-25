---
id: R977
title: "A self-FK reference writing the tenant column in an UPDATE SET is agreement-checked, not declined"
status: Spec
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# A self-FK reference writing the tenant column in an UPDATE SET is agreement-checked, not declined

## Goal

Under database-per-tenant routing (a build configured with `<tenantColumn>`, where each tenant's rows live in their own database and graphitron picks the database from the tenant value the operation carries), an `@mutation(typeName: UPDATE)` can repoint a row at a sibling row of the same table when the table's key and the parent pointer both carry the tenant column. The build accepts it, the statement runs on the row's own tenant database, and a call whose parent id names a row in another tenant is refused before any SQL runs, with the same "tenant bindings disagree" error a call mixing two tenants' ids already gets. Today the build rejects the mutation as an attempt to move the row between tenants. A plain input field that writes the tenant column stays a build error, because that one really is a move. For Sikt's sis this unblocks two mutations (`endreUndervisningsaktivitetHierarki`, `registrerFellesUndervisningForUndervisningsaktivitet`); low priority, both experimental.

The minimal pair, under the sakila fixture's `<tenantColumn>film_id</tenantColumn>`. `film_scene` is the table this item adds (below). Its key is `(film_id, scene_no)`, and its self-FK `film_scene_parent_fk (film_id, parent_scene_no) -> film_scene (film_id, scene_no)` has the same shape as sis's `UNDERVISNINGSAKTIVITET__EIER__UNDERVISNINGSAKTIVITET__FK`: the tenant column leads a composite foreign key that points back at the table's own key.

```graphql
# Accepted once this lands. `parent`'s key embeds film_id, so it names a tenant too; it must agree with `id`'s.
input UpdateFilmSceneParentInput {
    id: ID! @nodeId(typeName: "FilmScene")
    parent: ID @nodeId(typeName: "FilmScene") @reference(path: [{key: "film_scene_parent_fk"}])
}

# Still rejected: a plain value written into film_id, which inventory's key does not cover.
input UpdateInventoryFilmInput {
    inventoryId: Int! @field(name: "inventory_id")
    filmId: Int! @field(name: "film_id")
}
```

## Where it fails today

Verified against the generator source on 2026-09-25.

- **The decline is keyed on the column alone.** `TenantBindingIndex.Fold.collectFromWhereKeys` walks `UpdateRows.setColumns()` and declines any `SetColumn` whose `targetColumn` matches the tenant column, with the error "input field '<name>' writes tenant column '<col>' in the UPDATE's SET clause ... no UPDATE can move a row between tenants". It does not check whether the value is already forced equal to the WHERE side.
- **A self-FK routes wholly to SET.** In `UpdateRowsWalker` stage 6, a carrier of role `CarrierRole.SelfFk` goes through `addSetColumns` whatever its key membership. Its columns point at a sibling row, never at this row's identity. So `parent` lands `film_id` in `SET`, and the decline fires.
- **The walker already records the agreement the decline is missing.** Stage 6a of the same walker emits an `AgreementObligation(column, keySide, referenceSide)` for every self-FK column that is also a matched-key column. An agreement obligation is the walker's decision that two input fields decode a value for one column and must be checked equal. The four UPDATE emission arms in `TypeFetcherGenerator` lower each obligation to `NodeIdEncoder.requireColumnAgreement` before the DML. For the shape above, the obligation is `film_id` with key side `id` and reference side `parent`. The obligation's key side is by construction a `KeyColumn` (the walker reads it from `keyBySqlName`), so the WHERE partition always carries a slot on that column too.
- **A plain field reaches SET on the tenant column only when the matched key does not cover it.** An own-columns carrier whose columns are all in the key goes to WHERE. A plain field writing the tenant column therefore reaches SET only where nothing in WHERE pins the tenant: inventory's `film_id` above, or the out-of-key half of a cross-table straddler. The same holds for a self-FK whose tenant column sits outside the matched key; it gets no obligation. The decline is right for every one of these.

## Implementation

**`TenantBindingIndex.Fold.collectFromWhereKeys`: an agreement-checked SET slot co-binds instead of declining.** Reorder the method so that the WHERE loop runs first, followed by the SET loop. For a SET column on the tenant column:

- If `updateRows.isAgreementChecked(set)` holds, add it as a slot through the same call the WHERE loop makes: `collector.add(set.sdlFieldName(), set.targetColumn(), accessOf(set.extraction(), new NestedInput(dml.outerArgName(), List.of(set.sdlFieldName())), set.decodeSlot()))`.
- Otherwise, decline with today's message, unchanged.

Adding the SET slot after the WHERE slots keeps a WHERE slot as `ArgumentBound.primary()`, the binding that `TenantBinding.ArgumentBound` documents as taking precedence.

The co-binding is what makes this safe, beyond the build no longer refusing. `ArgumentBound` already carries every co-binding, and the generated `TenantConnections.divinedTenant` fold requires all non-null values to agree before `dslFor` acquires anything. A cross-tenant `parent` is therefore refused before a connection is pinned or mounted, on every UPDATE arm, because all four arms acquire through the shared `TenantDslEmitter.resolve`. The same property keeps the manual's "a call mixing tenants is refused before any SQL runs" sentence literally true. The runtime already handles the edge cases:

- `agreeOnTenant` skips null candidates, so an omitted or explicit-null `parent` contributes nothing and the call routes on `id` alone. An explicit null is then refused at runtime by the walker's existing `CarrierNullRule.OnExplicitNull.RefusedAsIdentity`, since clearing `parent` would null a key column. That behaviour is unchanged and out of scope.
- `tenantSlot` and the tenant-slot decode helper map over a list input, and the fold flattens the result. A batch in which any row's `parent` names another tenant is therefore refused as a whole call, before any connection.
- The tenant-slot decode helper is `Mode.THROW`. A malformed `parent` fails at routing with the same message the carrier's own decode would give, one statement earlier.

Reword the method's javadoc. "An UPDATE divines from its WHERE partition only" becomes: an UPDATE routes on its WHERE partition, and a SET-side tenant column joins the agreement fold only where the walker has already forced it equal to a WHERE column. Every other SET-side tenant column declines.

**`UpdateRows.isAgreementChecked(SetColumn)`: the predicate, on the carrier that owns both lists.** The method is true when some `agreementObligations()` row has `column().sqlName()` equal to the set column's and has `referenceSide()` equal to `new AgreementObligation.Side(set.sdlFieldName(), set.extraction(), set.decodeSlot())`. The join is exact because the walker builds both from the same contribution's `sdlFieldName`, `extraction` and column index, and record equality carries it. The javadoc links `AgreementObligation` and states the invariant the fold leans on: an obligation's key side is a matched-key column, so the WHERE partition carries a slot on the same column. Putting the predicate on `UpdateRows` rather than inline in the fold is what lets a future consumer ask the same question without restating the join.

**No emission change.** The obligation still lowers to `requireColumnAgreement` on every arm. On the tenant column the check is now redundant under routing, because the fold refuses first, and harmless. It stays load-bearing for single-tenant builds and for every non-tenant column.

**Not in scope: dropping the shared column from the emitted `SET` list.** For well-formed input, writing `film_id` to its own value is a no-op, and the bulk form already writes it from the WHERE-side value. This is today's behaviour for every self-FK key overlap regardless of tenancy. If it ever needs to change (for example to avoid firing update triggers on a key column), that is a walker change for all overlaps with its own tests. It can reuse `isAgreementChecked`.

**Fixture: `film_scene` in `graphitron-sakila-db`'s `init.sql`.** Add it beside the other `film_id`-keyed multi-tenant and pivot tables (`film_actor_note`, `film_price`), with a comment naming the shape it exists for:

```sql
CREATE TABLE film_scene (
    film_id         int NOT NULL REFERENCES film(film_id),
    scene_no        int NOT NULL,
    parent_scene_no int,
    label           varchar(100),
    PRIMARY KEY (film_id, scene_no),
    CONSTRAINT film_scene_parent_fk
        FOREIGN KEY (film_id, parent_scene_no)
        REFERENCES film_scene (film_id, scene_no)
);
```

It needs no seed rows in the default database; the execution test seeds the tenant databases itself. Nothing in the tree enumerates catalog tables (checked 2026-09-25 by grepping for the last-added `film_price`), so the addition is inert for the other builds. The existing `email` / `mailbox` self-FK fixture has the same shape on `mailbox_id`. Using it at the execution tier would need a second multi-tenant generate execution, whereas `film_scene` rides the existing `film_id` build and `TenantBindingClassificationTest`'s existing `film_id` context.

**`graphitron-sakila-example`'s `multitenant.graphqls`:** add `type FilmScene implements Node @table(name: "film_scene") @node(keyColumns: ["film_id", "scene_no"])` (with `id: ID! @nodeId`, `label`, `parentSceneNo`) and `UpdateFilmSceneParentInput` as in the goal, plus `label: String @field(name: "label")` so the omitted-`parent` case still has a SET field. Add two mutations: `updateFilmSceneParent(in: UpdateFilmSceneParentInput!): ID` and the bulk sibling `updateFilmSceneParents(in: [UpdateFilmSceneParentInput!]!): [ID!]!`, both `@mutation(typeName: UPDATE, table: "film_scene")`. Compiling this package is the compile-tier proof that the co-bound slot's read and decode render valid Java 17.

**User docs, `docs/manual/how-to/tenant-scoping.adoc`.** In the paragraph that begins "A node id (and a federation representation) carries its tenant inside the key", add after the sentence about a call mixing tenants:

> This includes a reference id on an update input. An update that repoints a row at a parent in the same table, where the parent's key carries the tenant, is fine as long as the parent lives in the row's own tenant; if it does not, the call is refused the same way. An update never moves a row between tenants, so an input field that writes the tenant column itself is a build error.

## Tests

Classification tier, `TenantBindingClassificationTest` (the existing `film_id` build):

- **`selfFkReferenceSharingTheTenantColumnCoBindsAfterTheWhereSlot`.** The single-row `updateFilmSceneParent` classifies `ArgumentBound` with no rejection. Its bindings are `id` first, then `parent`, each a `NestedInput` read with a `DecodedKeySlot` projection at slot 0. Assert the bulk `updateFilmSceneParents` the same way.
- **`tenantColumnInAnUpdateSetPartitionRejects`** (existing) keeps rejecting unchanged. It is the plain-field half of the minimal pair.
- **`decodedReferenceWritingAnOutOfKeyTenantColumnStillRejects`.** An `inventory` UPDATE keyed by `inventoryId` that sets `filmRef: ID! @nodeId(typeName: "Film")` lifts `film_id` into SET through a cross-table reference, with no obligation. It keeps the "SET clause" decline. This pins the rule as keyed on the walker's obligation, not on "the carrier decodes a node id".

Unit tier: one test for `UpdateRows.isAgreementChecked`, next to the walker's existing obligation assertions in `UpdateRowsWalkerTest`. On the `email` self-FK input, the `inReplyTo` SET column on `mailbox_id` is agreement-checked and its `in_reply_to_no` column is not.

Execution tier, `TenantDivinedRoutingExecutionTest`. Add `film_scene`, with its composite self-FK, to the per-tenant DDL. Seed `tenant_1` with scene `(1, 1)` and `tenant_2` with scenes `(2, 1)`, `(2, 2)`, `(2, 3)`, and reset `parent_scene_no` and `label` after each test as the siblings do.

- **Same-tenant parent.** `updateFilmSceneParent(id: (2,2), parent: (2,1))` sets `parent_scene_no = 1` in `tenant_2`, and `TENANT_1_OPENED` stays zero.
- **Cross-tenant parent.** `id: (2,2), parent: (1,1)` returns an error carrying "Tenant bindings disagree". The row is unchanged in `tenant_2`, and both `TENANT_1_OPENED` and `TENANT_2_OPENED` stay zero. The zero opens are the observable proof that the refusal precedes acquisition, and the error text proves the tenant fold refused it, not the per-tenant FK.
- **Omitted parent.** `id: (2,2), label: "x"` updates the label, routing on `id` alone.
- **Bulk.** `updateFilmSceneParents` with rows `(2,2)→(2,1)` and `(2,3)→(1,1)` is refused as a whole call with the same error. Neither row changes, and neither tenant is opened. A second bulk call with both parents in tenant 2 updates both rows.

## Related

- R976 (`service-tenant-binding`) and R975 (`tenant-routed-mount-authorization`), filed from the same sis port, are independent of this item.
