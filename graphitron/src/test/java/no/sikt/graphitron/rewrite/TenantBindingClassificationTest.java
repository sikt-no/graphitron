package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 classification coverage for the per-field {@link TenantBinding} axis
 * ({@link TenantBindingIndex}): one SDL fixture per arm, built over the real fixture catalog
 * with {@code film_id} as the configured tenant column ({@code film}, {@code inventory},
 * {@code film_actor} and friends carry it; {@code language} does not).
 */
@UnitTier
class TenantBindingClassificationTest {

    private static GraphitronSchema build(String sdl) {
        return TestSchemaHelper.buildSchema(
            sdl, TestConfiguration.testContext().withTenantColumn("film_id"));
    }

    @Test
    void argumentMappingToTenantColumnYieldsArgumentBound() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Query", "films");
        assertThat(binding).isInstanceOf(TenantBinding.ArgumentBound.class);
        var bound = (TenantBinding.ArgumentBound) binding;
        assertThat(bound.bindings()).hasSize(1);
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(bound.primary().column().sqlName()).isEqualTo("film_id");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void coBindingsResolveIntoOneArmWithDeclarationOrderPrimary() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id"),
                      altFilm: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Query", "films");
        assertThat(bound.bindings()).hasSize(2);
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(bound.bindings().get(1).slotName()).isEqualTo("altFilm");
    }

    @Test
    void childBelowBoundAncestorYieldsInherited() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]!
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Film", "inventories");
        assertThat(binding).isInstanceOf(TenantBinding.Inherited.class);
        assertThat(((TenantBinding.Inherited) binding).parentTypeName()).isEqualTo("Film");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void sessionBoundServiceChildUnderTenantContext_yieldsInherited() {
        // A $session-bound service call reads per-connection state (the mounted handle), so
        // although its own SQL reach is empty, it classifies Inherited under a tenant context:
        // the call must run on the inherited tenant's connection to observe that tenant's
        // handle, not the default source's.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                mountedPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOfBatch",
                    argMapping: "identity: $session"
                })
            }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Film", "mountedPrincipal");
        assertThat(binding).isInstanceOf(TenantBinding.Inherited.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void sessionBoundServiceAtAnUntenantedRoot_staysUntenanted() {
        // The complement: with no tenant context to inherit, the $session binding changes
        // nothing; the call runs on the default source and reads its handle.
        var schema = build("""
            type Query {
                sessionPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """);

        assertThat(schema.tenantBindingOf("Query", "sessionPrincipal"))
            .isEqualTo(TenantBinding.Untenanted.INSTANCE);
    }

    @Test
    void globalTableFieldYieldsUntenanted() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "languages"))
            .isEqualTo(TenantBinding.Untenanted.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void insertInputFieldMappingToTenantColumnYieldsArgumentBound() {
        // INSERT's TableInputArg carries a structurally empty fieldBindings (VALUES emission
        // walks fields() directly), so the divining slot must come from the fields() envelope.
        var schema = build("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            input InventoryCreateInput {
                filmId: Int! @field(name: "film_id")
                storeId: Int! @field(name: "store_id")
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createInventory(in: InventoryCreateInput!): Inventory @mutation(typeName: INSERT)
            }
            """);

        var binding = schema.tenantBindingOf("Mutation", "createInventory");
        assertThat(binding).isInstanceOf(TenantBinding.ArgumentBound.class);
        var bound = (TenantBinding.ArgumentBound) binding;
        assertThat(bound.bindings()).hasSize(1);
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(bound.primary().column().sqlName()).isEqualTo("film_id");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== Non-Record-backed shapes: the reach covers every table the SQL touches =====

    @Test
    void polymorphicRootOverTenantScopedParticipantsWithTenantFilterYieldsArgumentBound() {
        // Multi-table polymorphic fields return DomainReturnType.Plain; their reach is the
        // participant set, and the filter surface lives per participant.
        var schema = build("""
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Inventory @table(name: "inventory") { filmId: Int @field(name: "film_id") }
            union Media = Film | Inventory
            type Query {
                media(filmId: Int @field(name: "film_id")): [Media!]!
            }
            """);

        var binding = schema.tenantBindingOf("Query", "media");
        assertThat(binding).isInstanceOf(TenantBinding.ArgumentBound.class);
        assertThat(((TenantBinding.ArgumentBound) binding).primary().slotName()).isEqualTo("filmId");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void polymorphicRootOverTenantScopedParticipantsWithoutBindingRejects() {
        var schema = build("""
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Inventory @table(name: "inventory") { filmId: Int @field(name: "film_id") }
            union Media = Film | Inventory
            type Query { media: [Media!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "media")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Query.media"));
    }

    @Test
    void polymorphicRootMixingTenantAndGlobalParticipantsRejectsAsCrossScope() {
        // One statement cannot span the per-tenant and default sources, so no binding could
        // make this routable; it rejects on the scope mix itself.
        var schema = build("""
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Language @table(name: "language") { name: String }
            union Media = Film | Language
            type Query { media: [Media!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "media")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Query.media")
                && r.detail().contains("cross-scope"));
    }

    @Test
    void polymorphicRootOverGlobalParticipantsYieldsUntenanted() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Address @table(name: "address") { postalCode: String @field(name: "postal_code") }
            union Reference = Language | Address
            type Query { references: [Reference!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "references"))
            .isEqualTo(TenantBinding.Untenanted.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void tenantScopedFieldWithNoBindingInScopeRejects() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { allFilms: [Film!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "allFilms")).isNull();
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
        var error = schema.tenantBindings().rejections().get(0);
        assertThat(error.rejection()).isInstanceOf(Rejection.AuthorError.NoTenantBinding.class);
        var rejection = (Rejection.AuthorError.NoTenantBinding) error.rejection();
        assertThat(rejection.coordinate()).isEqualTo("Query.allFilms");
        assertThat(rejection.tableName()).isEqualTo("film");
        assertThat(rejection.message())
            .contains("tenant-scoped table 'film'")
            .contains("film_id");
    }

    @Test
    void nodeIdKeyEmbeddingTenantColumnYieldsNodeIdBound() {
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Query {
                node(id: ID!): Node
            }
            """);

        var binding = schema.tenantBindingOf("Query", "node");
        assertThat(binding).isInstanceOf(TenantBinding.NodeIdBound.class);
        // The decoded tenant position rides on the entity-side facts the dispatcher reads
        // (node dispatch synthesises reps and resolves through the entity surface).
        assertThat(schema.tenantBindings().byEntityType().get("FilmActor").alternatives())
            .anyMatch(slot -> slot.decodedPosition() == 1);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects() {
        var schema = build("""
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Query {
                node(id: ID!): Node
            }
            """);

        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Inventory")
                && r.detail().contains("node id key"));
    }

    @Test
    void entityRepresentationCarryingTenantColumnYieldsEntityRepBound() {
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Query { unused: FilmActor }
            """);

        var entityBinding = schema.tenantBindings().byEntityType().get("FilmActor");
        assertThat(entityBinding).isNotNull();
        assertThat(entityBinding.alternatives())
            .containsExactly(new TenantBinding.EntityRepBound.AlternativeSlot(0, 1));
    }

    // ===== The decoded-key projection: the tenant sits inside a node id =====

    @Test
    void deleteKeyedByCompositeNodeIdDivinesTheDecodedSlot() {
        // film_actor's node key is (actor_id, film_id), so decoding the id the DELETE already
        // decodes for its WHERE clause yields the tenant at slot 1.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                deleteFilmActor(in: DeleteFilmActorInput!): ID
                    @mutation(typeName: DELETE, table: "film_actor")
            }
            input DeleteFilmActorInput { id: ID! @nodeId(typeName: "FilmActor") }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "deleteFilmActor");
        assertThat(bound.primary().slotName()).isEqualTo("id");
        assertThat(bound.primary().column().sqlName()).isEqualTo("film_id");
        assertThat(bound.primary().read())
            .isEqualTo(new TenantBinding.SlotRead.NestedInput("in", List.of("id")));
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isEqualTo(1));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void bulkDeleteKeyedByNodeIdDivinesTheSameSlot() {
        // The batch form: one slot read over a list of inputs, whose values the generated
        // agreement fold flattens and requires to agree before any SQL runs.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                deleteFilmActors(in: [DeleteFilmActorInput!]!): [ID!]!
                    @mutation(typeName: DELETE, table: "film_actor")
            }
            input DeleteFilmActorInput { id: ID! @nodeId(typeName: "FilmActor") }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "deleteFilmActors");
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isEqualTo(1));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void updateKeyedByArityOneNodeIdDivinesTheDecodedSlot() {
        // The arity-1 carrier: the decoded key is one column and that column is the tenant. Before
        // the projection axis this minted a raw read of the base64 id as the tenant value.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                updateFilm(in: UpdateFilmInput!): Film @mutation(typeName: UPDATE, table: "film")
            }
            input UpdateFilmInput {
                id: ID! @nodeId(typeName: "Film")
                title: String @field(name: "title")
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "updateFilm");
        assertThat(bound.primary().slotName()).isEqualTo("id");
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isZero());
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void insertWithFkTargetNodeIdReferenceDivinesTheLiftedColumn() {
        // The decoded Film key lifts onto inventory.film_id through inventory_film_id_fkey, and
        // that lifted own-table column is the tenant column; the INSERT never names film_id.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createInventory(in: InventoryCreateByRefInput!): Inventory
                    @mutation(typeName: INSERT, table: "inventory")
            }
            input InventoryCreateByRefInput {
                filmRef: ID! @nodeId(typeName: "Film")
                storeId: Int! @field(name: "store_id")
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "createInventory");
        assertThat(bound.primary().slotName()).isEqualTo("filmRef");
        assertThat(bound.primary().column().sqlName()).isEqualTo("film_id");
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isZero());
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void sameTableNodeIdFilterDivinesTheDecodedSlot() {
        // The read-side half of the same omission: before the projection axis this minted a slot
        // that handed the encoded ids to the tenant lookup and blew up at request time.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Query {
                filmActorsByNodeId(ids: [ID!] @nodeId(typeName: "FilmActor")): [FilmActor!]!
            }
            """);

        var bound = (TenantBinding.ArgumentBound)
            schema.tenantBindingOf("Query", "filmActorsByNodeId");
        assertThat(bound.primary().slotName()).isEqualTo("ids");
        assertThat(bound.primary().read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isEqualTo(1));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void plainTenantColumnOnADeleteDivinesRaw() {
        // The gap the walker-carrier verbs had was the arm, not the node-id-ness: a DELETE whose
        // plain input field maps to the tenant column was equally unbound.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                deleteFilmActor(in: DeleteFilmActorByColumnsInput!): ID
                    @mutation(typeName: DELETE, table: "film_actor")
            }
            input DeleteFilmActorByColumnsInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "deleteFilmActor");
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(bound.primary().projection())
            .isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void childBelowANodeIdKeyedWriteInherits() {
        // The cascade: the every-path fold reads the same direct-binding predicate, so a write
        // that now divines establishes a context for its whole subtree instead of cascading the
        // root's rejection down it.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
                inventories: [Inventory!]! @splitQuery
                    @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                updateFilm(in: UpdateFilmInput!): Film @mutation(typeName: UPDATE, table: "film")
            }
            input UpdateFilmInput {
                id: ID! @nodeId(typeName: "Film")
                title: String @field(name: "title")
            }
            """);

        assertThat(schema.tenantBindingOf("Film", "inventories"))
            .isInstanceOf(TenantBinding.Inherited.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void tenantColumnInAnUpdateSetPartitionRejects() {
        // Routing on a SET-side tenant column would send the statement to the destination tenant
        // and update a row that is not there; under database-per-tenant no UPDATE can move a row
        // between databases at all.
        var schema = build("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                updateInventory(in: UpdateInventoryFilmInput!): Inventory
                    @mutation(typeName: UPDATE, table: "inventory")
            }
            input UpdateInventoryFilmInput {
                inventoryId: Int! @field(name: "inventory_id")
                filmId: Int! @field(name: "film_id")
            }
            """);

        assertThat(schema.tenantBindingOf("Mutation", "updateInventory")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.updateInventory")
                && r.detail().contains("SET clause"));
    }

    @Test
    void noTenantColumnMeansNoAxis() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { allFilms: [Film!]! }
            """);

        assertThat(schema.tenantBindings()).isSameAs(TenantBindingIndex.EMPTY);
        assertThat(schema.tenantBindingOf("Query", "allFilms")).isNull();
    }
}
