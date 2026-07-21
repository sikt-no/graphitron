package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

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
            input InventoryCreateInput @table(name: "inventory") {
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
