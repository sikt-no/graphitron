---
id: R966
title: "Write inputs keyed by a decoded node id divine the tenant"
status: In Progress
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-23
---

# Write inputs keyed by a decoded node id divine the tenant

## Goal

A mutation that identifies its rows by a node id builds, and routes its write to the tenant sitting inside that id. Today it does not build at all: the generator rejects it with "no argument or input field maps to tenant column 'X'", although the tenant value is right there in the key the mutation already decodes. *Tenant binding* is how a generated resolver knows which tenant database to route a statement to; a *tenant-scoped* table is one carrying the column named by the Mojo's `<tenantColumn>`; a *node id* is graphitron's opaque global identifier, which encodes a row's key columns and is decoded back into them at the call site. When a tenant-scoped table's key includes the tenant column, decoding the id yields the tenant, and the write routes on it.

The shape, using the in-tree `FilmActor` node type whose key is `["actor_id", "film_id"]` with `film_id` playing the tenant column (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, and the `DeleteFilmActorByNodeIdInput` fixture in `schema.graphqls`):

```graphql
input DeleteFilmActorByNodeIdInput {
    id: ID! @nodeId(typeName: "FilmActor")
}

type Mutation {
    # Rejected today. After this item: the id decodes to (actor_id, film_id), film_id is the
    # tenant, and the DELETE runs on that tenant's database.
    deleteFilmActorsByNodeId(in: [DeleteFilmActorByNodeIdInput!]!): [ID!]!
        @mutation(typeName: DELETE, table: "film_actor")
}
```

The contrast that shows what the classifier is missing is the same mutation with the tenant column named directly, which already builds and routes:

```graphql
input InventoryCreateInput {
    filmId: Int! @field(name: "film_id")
    storeId: Int! @field(name: "store_id")
}

type Mutation {
    createInventory(in: InventoryCreateInput!): Inventory @mutation(typeName: INSERT)
}
```

Both name the same tenant. One spells it as a column value, the other encodes it inside a key. Only the first routes.

What a consumer sees when this lands: the three write verbs keyed by a node id generate, and one mutation call routes to one tenant. Every id in a batch must decode to the same tenant; a batch mixing tenants is refused before any SQL runs, with an error naming the disagreement. That is agreement-guarded routing, not per-row partitioning: a write is one statement on one connection, so the mixed batch has no correct execution and is a client error rather than a fan-out. One read-side shape is carried along, because it turns out to be the same omission: a query filtering a tenant-scoped table by node ids whose key embeds the tenant builds today but fails at request time, and after this item it routes.

The rejection received for the first sis shape (2026-09-22 spike, `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`): "'Mutation.slettPermisjoner' reaches tenant-scoped table 'PERMISJON' with no tenant binding in scope: no argument or input field maps to tenant column 'INSTITUSJONSNR_EIER', and no ancestor established a tenant context."

## Mechanism (verified against the tree, 2026-09-22)

`TenantBindingIndex.Fold.directSlots` is the single chokepoint for "this field divines a tenant". Its result decides the `TenantBinding.ArgumentBound` arm in `classify`, and it is also what `edgeDivinesTenant` and `edgeEstablishesOrTransmitsContext` read, so the every-path fold in `tenantContextOf` cascades a missing slot down the whole subtree. That is why 105 unbound roots produced 875 rejections: fixing `directSlots` fixes the children with no further work.

Underneath the four symptoms below sits one omission. A bound slot records *where* its value is read (`TenantBinding.SlotRead`: a top-level argument, a path into an input object, a context argument) and nothing about *what transform turns the wire value into the tenant value*. For every shape the axis was built on, the wire value is the tenant value, so the second question never came up. A node id is the shape where it is not: the wire value is an opaque encoded key, and the tenant is one slot of what decoding it returns. The producer type already separates the two. `CallSiteExtraction.NestedInputField(outerArgName, path, leaf)` is the location and its `leaf` (`Direct`, `NodeIdDecodeKeys`, `EnumValueOf`, `JooqConvert`) is the transform, and `TenantBindingIndex.readOf` reads the location off it and drops the leaf on the floor. That dropped leaf is the fact this item needs.

Four sites, three verbs and one read path.

**The walker-carrier verbs mint nothing.** `directSlots` switches over the operation's members and handles `Condition`, `Lookup`, `Write.Insert` and `Write.Upsert`; `Write.Update(inputArg, UpdateRows)` and `Write.Delete(inputArg, DeleteRows)` fall to `default -> List.of()`. Both carriers already hold what the fold needs: `UpdateRows.keyColumns()` and `DeleteRows.whereColumns()` are lists of `KeyColumn(sdlFieldName, targetColumn, extraction, decodeSlot)`, where a composite `@nodeId` input field contributes one row per decoded column, all sharing an `sdlFieldName`, each stating its own `decodeSlot`. `KeyColumn.extraction()` is a bare `CallSiteExtraction.NodeIdDecodeKeys` for a top-level leaf and a `NestedInputField` wrapping one for a leaf inside a nested grouping input, so the access path is stated too, and `InputArgRef.name()` supplies the outer argument name. The gap is the arm rather than the node-id-ness: an UPDATE or DELETE whose *plain* input field maps to the tenant column is equally unbound today.

**The INSERT arm drops two carrier shapes.** `collectFromInputFields` walks the `TableInputArg`'s `fields()` envelope, matches `InputField.ColumnBackedField cf when !cf.isComposite()` and sends everything else to `default -> { }`. So an `InputField.ColumnBackedReferenceField` never mints a slot, which is the FK-target `@nodeId` reference shape (`studieprogramId: ID! @nodeId(typeName: "Studieprogram")` on an INSERT input) whose `FilterBinding.Local` own-table columns are the FK columns the decoded key lifts onto this table, permuted into node-key order, and can be or include the tenant column.

**The arm it does match is transform-blind.** It reads `cf.columns().get(0)` and mints a `SlotRead.NestedInput` without looking at `cf.extraction()`. An arity-1 `@nodeId` carrier whose single key column is the tenant column (the `ColumnBackedField`-with-`NodeIdDecodeKeys` shape `CreateKeyedNodeInput` exercises) emits a read of the base64 id string as the tenant value, which the generated `divinedTenant` hands to `Integer.valueOf` at request time.

**The condition arm has the same blindness, on the read side, today.** `readOf`'s `default` arm folds `NodeIdDecodeKeys` in with `Direct` and the coercing leaves and returns `TopLevelArg`, on the stated reasoning that those "all read the raw top-level argument". True of `JooqConvert` and `EnumValueOf`, whose wire value is the tenant value needing a cast; false of a node id. So a query root filtering on a same-table composite `@nodeId` whose key embeds the tenant column, which reaches `collectFromBodyParam` as a `BodyParam.RowEq` or `RowIn`, already mints a slot that reads the encoded ids as tenant keys. It fails closed rather than routing to the wrong tenant, because `divinedTenant`'s numeric coercion throws on the base64 text, but it fails at request time on a schema that should route. No fixture has that shape, which is why nothing has caught it.

Two corrections to the Backlog reading, so the implementer does not go looking for work that is not there. `slotsFromTableInput`'s skip of `InputColumnBindingGroup.DecodedRecordGroup` is *not* the INSERT gap: `FieldBuilder.resolveInsertWriteTarget` constructs the INSERT `TableInputArg` with an empty `fieldBindings` list ("For INSERT the binding set is structurally empty (VALUES emission walks fields() directly, never fieldBindings())"), and `Write.Upsert` is never constructed in main sources at all (`ExemptionRegistry` records it as retired). Both group arms of `slotsFromTableInput` are unreachable from the arms that call it; the whole INSERT surface is `collectFromInputFields`. And `collectFromInputFields`'s `default` comment, which defers composite node-id tuples to "the per-row family and the deliberate fan-out arm", points at nothing: `TenantBinding.NodeIdBound` is assigned only where the members carry `OperationMember.Kind.NODE_RESOLVE`, which a mutation never does.

## Implementation

Shipped at `4bb378e`, with the round 2 rework following it. The rework changes no design. It adds evidence and updates the record, and makes the one generated-code fix the evidence turned up (the mixed-tenant refusal reaches the client, see Tests). Where the delivery departs from the plan below, the paragraph says so in place.

**Add the transform as its own axis, rather than a fourth location arm.** `TenantBinding.SlotRead` keeps its three arms and its meaning, pure location. `BoundSlot` gains a second component:

```java
record BoundSlot(String slotName, ColumnRef column, SlotRead read, SlotProjection projection) {}

sealed interface SlotProjection {
    /** The read's value is the tenant value. Every shape the axis was built on. */
    record Raw() implements SlotProjection {}
    /** The read's value is an encoded node id; the tenant is slot {@code slot} of the decode. */
    record DecodedKeySlot(HelperRef.Decode decode, int slot) implements SlotProjection {}
}
```

The alternative, a `SlotRead.DecodedNodeId(outerArgName, path, decodeMethod, decodeSlot)` arm, fuses the two axes into one permit name and makes the permit set their cross-product: a decoded id at a top-level argument is a fourth arm, one reached through a context argument a fifth, and the next coercing leaf that has to survive to the routing site a sixth. Two components instead of one leaf keeps them independent, and it is why all four sites above are one fix.

**One resolver, every minting site.** `readOf(CallSiteExtraction)` widens from returning a `SlotRead` to returning both axes: it reads the location as it does today, and reads the leaf it currently discards into a `SlotProjection`. Every site that mints a `BoundSlot` calls it: `collectFromBodyParam` (which closes the read-side defect), `slotsFromLookup`, and `collectFromInputFields`. The transform-blindness above is then unwritable rather than fixed, because no minting site reads one axis without the other. That is the reason the arity-1 defect rides this item: under this shape it is not a separable fix, it is the same line.

**`TenantBindingIndex.Fold`, the write arms.**

- `UpdateRows.keyColumns()` and `DeleteRows.whereColumns()` are one fact under two names; `KeyColumn`'s own javadoc already says the two families share the carrier. Expose it uniformly on `OperationMember.Write.Dml`, which is exactly what that interface exists for ("The two facts every DML reader needs are exposed uniformly here, once, over the arms' structurally different input surfaces", today `table()` and `listInput()`): a `whereKeyColumns()` default returning the carrier's rows for `Update` / `Delete` and an empty list for `Insert` / `Upsert`, plus `outerArgName()`. The fold then has one body for both WHERE-keyed verbs, and a third WHERE-bearing write shape is covered on arrival instead of falling to `default -> List.of()` the way these two did.
- That body: for each `KeyColumn` whose `targetColumn` matches the tenant column, resolve both axes through the widened `readOf`, defaulting the location to the `Dml`'s outer argument name and `[sdlFieldName]` where the extraction is a bare leaf, and take the projection's slot from `KeyColumn.decodeSlot()` rather than from the row's position.
- `collectFromInputFields` gains a `ColumnBackedReferenceField` arm over the `FilterBinding.Local` own-table columns, minting through the same resolver. A `FilterBinding.Remote` carrier is declined: it reaches its value through a join and has no own-table value for a statement that must already be on one connection.

**UPDATE divines from the WHERE partition only, and rejects a tenant column in SET.** `UpdateRows` partitions its input columns: the matched key goes to WHERE, everything else to SET. Routing on a SET-side tenant column would send the statement to the destination tenant and update a row that is not there. Under database-per-tenant a row cannot change tenant by an UPDATE at all, since the destination row lives in another database, so an UPDATE whose `setColumns()` include the tenant column rejects. This rung exists because this item creates the exposure: today such an UPDATE rejects for want of any binding, and once the WHERE side binds, the SET side would run.

**Every declined shape gets its own rejection message.** A root mutation coordinate whose shape this change declines still rejects rather than leaking, because `tenantContextOf` short-circuits on root types, so the `Inherited` and `Untenanted` rungs below are unreachable from `Mutation`. What is at stake is the message, and the `@tenantFanOut` ladder in the same class is the precedent for giving each rung its own text instead of one generic detail. Three shapes need one: a `FilterBinding.Remote` reference carrier reaching the tenant column, a `CallSiteExtraction.PruneOnMismatch` leaf reaching a tenant slot (that arm's whole definition is that there is no single decode to route on), and the command-side arm below if it takes the rejection option.

*As shipped, one of the three.* Only the `PruneOnMismatch` decline is reachable, and it carries its own message. The other two were written at `4bb378e` and removed in the rework, because writing their tests showed nothing reaches them. A `FilterBinding.Remote` reference carrier never reaches the fold on a write: `MutationInputResolver` refuses a Remote-bound carrier on every `@mutation` before the write classifies, and the UPDATE and DELETE walkers do the same, since the write has no own-table column to put the decoded key in. The `Remote` arm in `collectFromInputFields` is now an empty arm naming that gate. The routine-write decline is covered under Emission below. Both gates are pinned by classification tests, so a later change that opens either one fails a test instead of silently regaining a shape with no message.

**Emission: one home for the decode, two emitters.** The decode-and-project is not new generated code. `CompositeDecodeHelperRegistry` already mints per-node-type decode helpers on a host class, carrying the `wire instanceof String` / list-shaped guards and a mismatch branch that raises the generated `GraphitronClientException` through `NodeIdDecodeFailure`, whose javadoc states the invariant directly: "several host families decode a node id and one bad id must fail the same way at every grain … a message that differed between them would tell a client that one spelling of a filter validates its ids and another does not." Tenant routing is a fourth such host and it runs *first*, before the carrier reads its input, so whatever it does is what the client sees. The registry projects `value1()` at arity 1 and `valuesRow()` above it; a `DecodedKeySlot` projection needs one more projection mode, "slot N", and gets the guards and the failure for free. The call site stays `<helper>(TenantConnections.tenantSlot(env.getArgument("in"), "id"))`.

That deliberately drops the earlier sketch of a `decodedTenantSlot(Object, String, Function, int)` member on `TenantConnections`. It would have been the first higher-order member on a class whose every other member is a plain value walk, and a consumer breakpointing generated routing would step through a lambda to reach the decode. More decisively, it would restate the mismatch message at a site that runs ahead of the one home for it.

Two emitters read `SlotRead`, not one, and the second sits behind a build-enforced package boundary. `TenantDslEmitter.slotReads` renders the fetcher-site declaration; `RoutineWriteCommands.slotReadOf` restates every arm into `TenantAcquisition.SlotRead`, which `TenantAcquisitionFragments.slotRead` renders for the routine-write entry points. Both switches are exhaustive, so the compiler forces the second one open. `no.sikt.graphitron.command` may not import `HelperRef` (the allowlist in its `package-info` is closed and names seven ref types, and `HelperRef.Decode` carries javapoet types besides), so the command-side projection carries the decode as plain data: the encoder class as a string, the helper method name, and the slot. `TenantAcquisitionFragments` lives in `render`, which may import `HelperRef` and already does so in the registry, and reconstructs what it needs there. The facts are carried, not recomputed. If that restatement turns out to cost more than it is worth, the honest fallback is to reject a routine write whose tenant slot needs a decode, with its own message under the rung above; a bare `throw` there would be a latent trap.

*As shipped, neither was needed.* `4bb378e` took the fallback and added a classifier decline for a `@routine` write with a projected slot. The rework removed it, because nothing can reach it: a `@routine` write's only operation member is `OperationMember.Write.RoutineWrite`, which has no filter, lookup or input surface, so the fold mints no bound slot for it at all. A tenant-scoped routine write rejects with the generic "no argument or input field maps to tenant column" text, even when an argument carries the tenant value, and `routineWriteMintsNoSlotSoItsTenantIsNeverDecodedAtTheEntryPoint` pins that. So no plain-data projection crosses into `no.sikt.graphitron.command`, and `TenantAcquisition.SlotRead` is unchanged. `RoutineWriteCommands.slotReadOf` keeps a guard that throws on a `DecodedKeySlot`, and its message now names the real invariant: a routine-write slot surface was added without teaching the restatement the decode. That guard is the latent trap the paragraph above warns about, but kept as a generator-bug invariant it fails the build loudly where a dropped projection would route the encoded id. Giving routine writes a tenant slot surface at all is a separate question, and this item does not take it up.

**The arm stays in the `ArgumentBound` family**, not the per-row family (`NodeIdBound`, `EntityRepBound`). Two structural reasons, beyond the "one statement" intuition. `NodeIdBound` is the verdict alone, and the decoded positions its consumers read live in `byEntityType`, keyed by *type name*; a write's tenant slot is keyed by coordinate and argument, which that map cannot hold without growing a second payload shape. And the per-row family's stated reason for existing is that a single batch at a *dispatch surface* spans tenants, so its consumers partition. Per-row-ness is a property of the dispatch, not of the decode. A DML UPDATE or DELETE is one statement on one connection.

**Two reads of one wire value, deliberately.** The routing decode and the carrier's own decode both read the same encoded id. That is not redundancy to clean up later: the agreement guard has to fold the whole batch before any SQL is issued, while the carrier decodes per row inside its stream lambda. Hoisting the carrier's local to serve routing would mean materialising every row's decode before the tenant is known, on a connection not yet acquired. The registry's deduplication means both reads go through one helper.

**Scope added in delivery: the write target counts as reached.** `tablesOf` gains an arm for `MutationField.DmlTableField` that adds `f.write().table()` to the coordinate's reach. A DML write whose return is an encoded id (`: ID`, `: [ID!]!`) has no `Record` return target, so before this arm such a mutation reached no table, classified `Untenanted`, and ran on the default source even when it wrote a tenant-scoped table. That was a silent cross-tenant write, and every `@nodeId`-keyed DELETE in this item returns `ID`, so the goal needed the arm. It changes behaviour for consumers: a tenant-scoped DML write that returns an id and names no tenant built before and now rejects with `NoTenantBinding`. The manual already states the rule this enforces ("A tenant-scoped field that nothing routes is a build error, never a silent read of the wrong database"), so the manual is not changed. Before this arm the sentence was false for id-returning writes. A consumer who hits the new rejection is looking at a write that was going to the wrong database.

Generated sources target Java 17; everything above is available there.

## Tests

The fixture gap is why none of this was caught: `multitenant.graphqls` carries no UPDATE or DELETE at all, its one INSERT names the tenant column directly, and no fixture anywhere filters a tenant-scoped read by a node id whose key embeds the tenant. The fixture already has most of what the new cases need, since `FilmActor` is `@node(keyColumns: ["actor_id", "film_id"])` with the tenant at position 1, and `film_actor.last_update` is a writable non-PK column.

- **Classification** (`TenantBindingClassificationTest`): DELETE and UPDATE keyed by a composite `@nodeId` each yield `ArgumentBound` whose slot carries a `DecodedKeySlot` projection at the right slot; an INSERT with an FK-target `@nodeId` reference yields one too; the arity-1 `@nodeId` INSERT carrier yields a projected slot rather than today's raw read; a same-table composite `@nodeId` *filter* on a query root yields one, which is the read-side defect; a plain tenant-column input field on an UPDATE or DELETE yields a `Raw` projection. On the rejecting side: a tenant column in an UPDATE's SET partition, a `FilterBinding.Remote` reference carrier, and a `PruneOnMismatch` leaf each reject with their own message. One case must assert the cascade explicitly, since a child below a node-id-keyed write classifying `Inherited` rather than rejecting is the 750-rejection half of the sis count.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`): the rendered fetcher calls the registry-minted decode helper on the `tenantSlot` walk, then `dslFor(env, _divinedTenant)`, and contains no `getDslContext(env)`, matching how `insertMutationDivinesFromItsInputFieldAndRoutes` pins the existing arm. A routine-write entry point with a projected slot pins the second emitter, since that path renders through `TenantAcquisitionFragments` rather than `TenantDslEmitter`.
- **Decode helper** (`CompositeDecodeHelperRegistry`'s own tests): the new slot-N projection mode, and that a tenant-slot helper and a predicate helper for the same node type dedupe or stay distinct as the `Key` says they should.
- **Compilation** (`graphitron-sakila-example`): `multitenant.graphqls` gains the `@nodeId`-keyed DELETE in both single and bulk form, the `@nodeId`-keyed UPDATE setting `last_update`, an INSERT into `inventory` carrying `@nodeId(typeName: "Film")` as an FK-target reference over `inventory_film_id_fkey`, and a query root filtering on `[ID!] @nodeId(typeName: "FilmActor")`. The last two need `Film` declared a `@node` type in the fixture, which it is not today; its key is then the tenant column alone, so it also widens the existing `NodeIdBound` dispatch coverage from a composite key to a single-column one. This tier is the proof that the emitted routing is valid Java 17.
- **Execution** (`TenantDivinedRoutingExecutionTest`): a bulk DELETE whose ids all decode to one tenant deletes only in that tenant's database and opens no other tenant's `DataSource`; a bulk DELETE mixing two tenants' ids returns an error and opens *neither*, since `divinedTenant` throws before `dslFor` runs. That second one is the assertion that makes the pre-SQL refusal a claim rather than a hope, and it is asserted on the observable (the error and the untouched connection counters), never on emitted method-body text. A malformed id surfaces `NodeIdDecodeFailure`'s message, which is the proof that the routing site did not mint a second failure vocabulary. The test class already builds ids with `NodeIdEncoder.encodeFilmActor(actorId, filmId)`, so the mixed batch is two calls to it.

**As shipped, where the tests depart from the list above.**

- *Pipeline and decode-helper tiers assert structure, not body text.* Code-string assertions on generated method bodies are banned at every tier (`docs/architecture/principles/development-principles.adoc`), and this list's "matching how `insertMutationDivinesFromItsInputFieldAndRoutes` pins the existing arm" pointed at an older test that predates the rule. `TenantRoutedFetcherPipelineTest` pins that the fetcher class which routes on a decoded slot declares the `decode<Type>TenantSlot<N>OrThrow` helper, `private static` with one `Object` parameter and an `Object` return, for the DELETE and for the node-id filtered read. `CompositeDecodeHelperRegistryTest` pins the same signature for the slot-N mode at arity 1 and arity 2, and shows the `Key` dedup through names and `emit()` counts. The body's decode, list flattening and failure message are left to the compilation and execution tiers, which exercise all three.
- *No routine-write pipeline test, because there is no projected routine-write slot to render.* See Emission above. The classification test named there is the evidence instead.
- *The rejecting side is one message, not three.* `pruningNodeIdLeafOnAPolymorphicRootRejectsWithItsOwnMessage` covers the reachable decline. `insertReferenceCarrierReachingTheTenantThroughAJoinNeverReachesTheFold` pins the upstream gate that stands in for the Remote decline. The SET-partition rejection is covered as planned.
- *The arity-1 INSERT carrier is its own case.* `insertWithArityOneNodeIdCarrierDivinesTheDecodedSlot` drives `collectFromCarrier` with a `NodeIdDecodeKeys` extraction, which is the transform-blind site the Mechanism section names. The arity-1 UPDATE case goes through `collectFromWhereKeys` instead.
- *The compile-tier UPDATEs are an arity-1 `Film` UPDATE setting `title` and a composite `FilmActorNote` UPDATE setting `note_txt`, not a `FilmActor` UPDATE setting `last_update`.* `film_actor.last_update` is a `timestamp`, and the multitenant fixture declares no scalar for it. The execution tier's hand-built tenant databases also give `film_actor` no `last_update` column. `film_actor_note` has a three-column key with the tenant at slot 1 and a writable `varchar` column, so it covers the composite UPDATE at both the compile and execution tiers (`updateKeyedByNodeId_routesOnTheCompositeKeysMiddleSlot`), and it adds arity-3 coverage as well.
- *The mixed-batch refusal asserts its message, and asserting it found a gap in the delivery.* `bulkDeleteByNodeId_idsMixingTenants_refusedBeforeAnySql` now checks that the error says the bindings "disagree", which is what the Goal promises. At `4bb378e` it did not: the generated `agreeOnTenant` threw a plain `IllegalArgumentException`, which the error router redacts, so the client read "An error occurred. Reference: …". The Goal calls a mixed batch a client error, so the rework makes it one. `agreeOnTenant` now raises the generated `GraphitronClientException`, which the router surfaces unredacted, the same way the node-id decode failure reaches the client. The guard is shared with co-bound arguments that name different tenants, which are the caller's mistake in the same way, so they get the visible message too. That is the rework's one change to generated code.

## User documentation (first-client check)

`docs/manual/how-to/tenant-scoping.adoc` states the routing rules in one paragraph, whose middle clause currently reads "node ids and federation representations carry their tenant inside the key and partition per row". That is true of dispatch and false of every other place a node id can arrive, so the clause splits on where the id lands rather than on what it is: a node id carries its tenant inside the key; at a *dispatch* surface (`node`, `nodes`, `_entities`) the batch partitions per row, and anywhere else, a write keyed by ids or a filter reading them, every id in the call must decode to the same tenant, with a mixed batch refused before any SQL. If that distinction cannot be stated in a sentence a reader accepts, the design is wrong; the reading to check it against is that a read can be split across databases and a write cannot.

## Out of scope, and why

**Whether a tenant-scoped *read* keyed by node ids should partition rather than agree.** The condition-arm fix above gives a query root filtering on a same-table composite `@nodeId` the same agreement guard the writes get, which is strictly better than the request-time `NumberFormatException` it produces today. But a read genuinely can be split across databases, which is what `nodes()` already does, so agreement may be the wrong long-run answer there even though it is the right immediate one. The same question stands over `slotsFromLookup`'s `LookupArg.DecodedRecord` arm, which declines a decoded lookup key today on the reasoning that it belongs to the per-row family, although a lookup root carries no `NODE_RESOLVE` member and so reaches no per-row arm at all. Both want their own item: the change is behavioural for reads, the machinery is per-row partitioning rather than a projection, and neither is on the path to making the 105 sis mutations build.

## Other solutions we've considered

**A fourth `SlotRead` arm carrying the decode.** The shape this item was filed with: `DecodedNodeId(outerArgName, path, decodeMethod, decodeSlot)` beside `TopLevelArg`, `NestedInput` and `ContextArg`. Rejected because it splices two independent axes into one permit name, so the permit set becomes their cross-product and each further combination (a decoded id at a top-level argument, one behind a context argument, the next coercing leaf that must survive to the routing site) arrives as another arm. It would also have left the arity-1 transform-blindness as a separate fix, since the site that has it reads the location axis and never asks about the transform. Under the two-component shape that bug cannot be written.

**A `decodedTenantSlot(Object, String, Function, int)` member on the generated `TenantConnections`.** Composes neatly with the existing `tenantSlot` and `divinedTenant`, and was the first sketch here. Rejected on two counts: it is the first higher-order member on a class whose every other member is a plain value walk, so a consumer breakpointing generated routing steps through a lambda to reach the decode; and it restates the node-id mismatch message at a site that runs ahead of `NodeIdDecodeFailure`, which exists precisely so that one bad id fails the same way at every grain. Routing the projection through `CompositeDecodeHelperRegistry` gets the wire guards, the list flattening and the message without any of that.

**Falling through to `divinedTenant`'s absent-binding error on a bad id.** Would need no message decision at all. Rejected: a `NoSuchElementException` reading "the tenant binding value is absent" is not a `GraphQLError`, names neither the field nor the id, and would be what every malformed id on a routed write produced, since routing runs before the carrier's own decode.

**Per-row partitioning of a mixed-tenant write batch.** The per-row family's posture, applied to writes: split the batch by decoded tenant and run one statement per tenant. Rejected as the default because it makes one mutation call a multi-database write with no transaction spanning it, so a partial failure leaves some tenants written and others not and nothing in the response says which. The agreement guard makes that shape impossible to reach by accident. The precedent is the hand-written sis v9 `QueryInspector.getUniqueId`, which threw on a mixed-tenant batch; sis does not need partitioning, and this item states that rather than leaving the arm open. A later item can add it behind an explicit opt-in if a consumer ever wants it.

**Reusing `NodeIdBound` for the write.** Would avoid touching `BoundSlot`, but `NodeIdBound` is the verdict alone: it carries no read, and its consumers are the dispatch generators, which partition. Making it carry a coordinate-keyed payload would give one arm two meanings.

## Retired vocabulary

Private names inside `TenantBindingIndex.Fold`, renamed because each now returns or accumulates
both axes rather than a list of reads: `directSlots` -> `directBinding`, `readOf` -> `accessOf`,
`slotsFromFilters` -> `collectFromFilters`, `slotsFromLookup` -> `collectFromLookup`,
`slotsFromTableInput` -> `collectFromTableInput`. Nothing outside the class named any of them.

Removed in the round 2 rework, never replaced: `declineRoutineWriteDecodes`, the private pass
that declined a `@routine` write with a decoded slot. It could not fire; see Emission. Its name
survives only in R965's reviewer rounds, which record the tree as it stood when they were written.

## Provenance

A sis spike on 2026-09-22 configured `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>` against the sis schema and counted 875 `Rejection.AuthorError.NoTenantBinding` errors, against 0 on the same tree and database without the setting. 121 are root fields; the other 750 are children cascaded by the every-path fold in `TenantBindingIndex.tenantContextOf`. 105 of the 121 roots are this item: 59 UPDATE, 26 DELETE and 20 INSERT mutations.

11 more are R965, the sibling gap where a field-level `@condition(override: true)` hides a column-bound argument from the fold. The two are independent. R965 is the smaller change and unblocks the wider cascade, so it is worth taking first.

Every one of the 875 rejections printed without file:line coordinates, because the fold's rejections carry `SourceLocation.EMPTY`. That is R523; sis is its motivating multi-file case.

## Reviewer findings

### Round 1: Spec -> Ready, signed off. 2026-09-22, session_01VwNA15ya6xGVDSock8YxDn

Both gate questions pass; no blocking findings.

Question 1 (goal communicated and viable). The goal states the consumer change without
reference to the plan: a mutation that names its rows by a node id instead of by a
column-mapped input field currently fails the build under a configured `<tenantColumn>`,
and after this item it builds and routes to the tenant sitting inside the decoded id, with
a batch whose ids disagree on tenant refused before any SQL. The `DeleteFilmActorByNodeIdInput`
/ `InventoryCreateInput` minimal pair carries that contrast better than the prose does. Every
code claim behind it checks out against the tree: the three named defects reproduce by reading
the source, and the quoted javadoc and comment fragments are verbatim.

Question 2 (architectural fit). Two independent components on `BoundSlot` rather than a fourth
`SlotRead` arm is the "Orthogonal facts are independent axes" principle applied directly, and
the rejection of the spliced-permit alternative is argued on that ground rather than on taste.
`whereKeyColumns()` / `outerArgName()` as `Dml` defaults extends what that interface already
does for `table()` and `listInput()`, over the same structurally different input surfaces its
javadoc names. Routing the projection through `CompositeDecodeHelperRegistry` rather than a new
generated `TenantConnections` member preserves `NodeIdDecodeFailure`'s stated one-message
invariant. Nothing here stands a parallel mechanism beside an existing one.

Non-blocking, bearing on question 2 only as a caution to the implementer, not as a design
change: the Emission section's "Both switches are exhaustive, so the compiler forces the second
one open" does not hold under the design this item chooses. `SlotRead` keeps its three arms, so
`RoutineWriteCommands.slotReadOf`'s switch over them is undisturbed by a new `BoundSlot`
component and would keep compiling while dropping the projection. A compiler force does exist
under the same paragraph's proposal, since adding plain-data projection components to
`TenantAcquisition.SlotRead`'s arms breaks the three constructions in `slotReadOf` on arity, but
that is a different mechanism. The site is named, the package constraint is stated, the fallback
is stated, and the Tests section pins the routine-write path separately, so nothing about what
gets built changes.

### Round 2: In Review -> Ready, rework. 2026-09-23, session_01XP2xPNumaRmB4WAEbriGYa

The design shipped is the one approved, and it works: `BoundSlot` carries `SlotProjection` beside
`SlotRead`, one resolver (`accessOf`) reads both axes at every site that mints a slot,
`Dml.whereKeyColumns()` / `outerArgName()` give UPDATE and DELETE one shared body, the slot-N
projection goes through `CompositeDecodeHelperRegistry`, and the generated
`multitenant` fetchers call `decode<Type>TenantSlot<N>OrThrow(tenantSlot(...))` inside
`divinedTenant`. The execution tests show the goal behaviour against PostgreSQL: a bulk DELETE
routes to one tenant, a mixed batch is refused with no connection opened, a malformed id raises
the carrier's own message, and the UPDATE, FK-reference INSERT and node-id filtered read route.
The manual paragraph is split the way the spec asked. The verification build passed:
`mvn install -Plocal-db` was green apart from `DevMojoTest.runGeneratorPass_reportsWhatTheClasspathCensusCost`,
which is outside this item's diff and passed on the resumed `-rf :graphitron-maven-plugin` run.
The resumed run also covered `graphitron-sakila-example`, where all 13 tests in
`TenantDivinedRoutingExecutionTest` passed. The rework is about evidence and the record, not the
code.

**Blocking, precondition (no code-string assertions on generated method bodies).** Four new tests
match text inside generated `MethodSpec` bodies, which development-principles.adoc bans at every
tier:
`TenantRoutedFetcherPipelineTest.deleteKeyedByNodeIdRoutesThroughTheClassOwnDecodeHelper`,
`.theTenantSlotHelperLandsOnTheFetcherClassThatCallsIt`,
`.nodeIdFilteredReadRoutesThroughTheDecodedSlot` (each `render(...)` is `MethodSpec::toString`
checked with `.contains`), and
`CompositeDecodeHelperRegistryTest.emit_tenantSlotHelper_flattensABatchAndProjectsTheNamedSlot`
(`helper.code().toString()` checked for `return key.value2()` and similar). The Tests section's
"matching how `insertMutationDivinesFromItsInputFieldAndRoutes` pins the existing arm" pointed at
an older test that already uses this pattern; that makes it an inherited pattern, not an
exemption. To satisfy: assert structure only (the helper method exists on the calling class by
name, with return type `Object` and one `Object` parameter; the registry `Key` dedup shown
through names and `emit()` counts, which the other two new registry tests already do). Leave
the body behaviour to the compilation and execution tiers, which already cover it.

**Blocking, question 2 (evidence the spec named is missing).** The Tests section names these
classification cases, and none exists:
- A `FilterBinding.Remote` reference carrier reaching the tenant column rejects with its own
  message ("through a join").
- A `PruneOnMismatch` leaf reaching a tenant slot rejects with its own message ("no single
  decode to route the statement on").
- The arity-1 `@nodeId` **INSERT** carrier (`ColumnBackedField` with `NodeIdDecodeKeys`, the
  `CreateKeyedNodeInput` shape) yields a `DecodedKeySlot`. This was the "transform-blind" site the
  Mechanism section named. The arity-1 test that shipped is an UPDATE, which goes through
  `collectFromWhereKeys`, so no test drives `collectFromCarrier` with a node-id extraction.
- The routine-write path. The spec's Tests section asked for a pipeline test of a routine-write
  entry point with a projected slot. The delivery took the spec's fallback instead (reject a
  routine write whose tenant slot needs a decode), which the spec allows, but then the evidence
  is a test that shows the rejection and its message (`declineRoutineWriteDecodes`), and none
  exists. Without it, nothing shows that the `IllegalStateException` in
  `RoutineWriteCommands.slotReadOf` cannot be reached.
Each decline message is new code whose only reason to exist is its text, so an untested decline
is not delivered.

**Blocking, precondition (spec body reflects what shipped).** The spec body has not changed since
Ready except for the new `## Retired vocabulary` section. It should record:
- that the routine-write fallback was taken: the Emission section still describes carrying the
  decode as plain data as the plan, with the fallback as the alternative;
- the `MutationField.DmlTableField` reach arm in `tablesOf`. This is scope nobody approved, and it
  is behaviour-bearing: before it, a tenant-scoped DML write that returns an encoded id was
  classified untenanted and ran on the default source. After it, such a write either binds or is
  rejected. That is correct and needed for the goal, but a consumer schema that built before can
  now be rejected, so it belongs in the body (and probably the manual);
- the compilation fixture substitution: the spec named a composite `FilmActor` UPDATE setting
  `last_update`, and the fixture has an arity-1 `Film` UPDATE setting `title` instead. The
  substitute is reasonable, since it also covers arity 1, but it leaves a composite-key UPDATE
  without any compile-tier coverage, so either add the composite UPDATE or say why not;
- a one-line "shipped at `4bb378e`" note on the Implementation section, with the rework named as
  the work that remains.

**Non-blocking, cheap to fold into the same rework.** The Goal says a mixed batch is refused
"with an error naming the disagreement". `bulkDeleteByNodeId_idsMixingTenants_refusedBeforeAnySql`
asserts only that there are errors and that no connection opened. `agreeOnTenant`'s message is
"Tenant bindings disagree within one operation", so add a `contains("disagree")` to make the
test check the stated goal.

Retirement sweep: none of the five retired private names appears in javadoc, comments, `.adoc`,
fixtures, or test names. The only hits are in R965's spec body, and R965's own round-3 finding
already blocks on them, so they are that item's to fix, not this one's.
