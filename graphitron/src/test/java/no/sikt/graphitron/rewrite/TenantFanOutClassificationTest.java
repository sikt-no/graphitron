package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 classification coverage for the {@link TenantBinding.FanOut} arm and the closed
 * {@code @tenantFanOut} rejection ladder, mirroring {@link TenantBindingClassificationTest}'s
 * per-arm discipline over the same fixture catalog ({@code film_id} as the configured tenant
 * column). Every marker rejection is validate-time and fan-out-specific: a marked field never
 * falls through to the generic cross-scope or {@code noTenantBinding} message.
 */
@UnitTier
class TenantFanOutClassificationTest {

    private static GraphitronSchema build(String sdl) {
        return TestSchemaHelper.buildSchema(
            sdl, TestConfiguration.testContext().withTenantColumn("film_id"));
    }

    private static void assertFanOutRejection(GraphitronSchema schema, String coordinate,
                                              String messageFragment, String... directives) {
        assertThat(schema.tenantBindingOf(coordinate.split("\\.")[0], coordinate.split("\\.")[1])).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().containsAll(List.of(directives))
                && conflict.message().contains("'" + coordinate + "'")
                && conflict.message().contains(messageFragment));
    }

    // ===== The arm =====

    @Test
    void markedRootFieldYieldsFanOut() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);

        assertThat(schema.tenantBindingOf("Query", "films"))
            .isEqualTo(TenantBinding.FanOut.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void markedChildOfUntenantedParentYieldsFanOut() {
        // The batched form: the parent is global reference data, and each parent batch fans the
        // child statement out per tenant.
        var schema = build("""
            type Language @table(name: "language") {
                name: String
                films: [Film] @reference(path: [{key: "film_language_id_fkey"}]) @tenantFanOut
            }
            type Film @table(name: "film") { title: String }
            type Query { languages: [Language!]! }
            """);

        assertThat(schema.tenantBindingOf("Language", "films"))
            .isEqualTo(TenantBinding.FanOut.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void childrenBelowAFannedFieldClassifyInherited_splitQueryIncluded() {
        // Per-element localContext stamping makes the fanned subtree tenant-homogeneous per
        // element, so children need nothing extra: a @splitQuery child classifies Inherited and
        // partitions per tenant through the existing loader-name seam.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @splitQuery
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query { films: [Film] @tenantFanOut }
            """);

        assertThat(schema.tenantBindingOf("Query", "films"))
            .isEqualTo(TenantBinding.FanOut.INSTANCE);
        var child = schema.tenantBindingOf("Film", "inventories");
        assertThat(child).isInstanceOf(TenantBinding.Inherited.class);
        assertThat(((TenantBinding.Inherited) child).parentTypeName()).isEqualTo("Film");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== The rejection ladder =====

    @Test
    void markerOnMutationFieldRejects() {
        var schema = build("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            input InventoryCreateInput @table(name: "inventory") {
                filmId: Int! @field(name: "film_id")
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createInventory(in: InventoryCreateInput!): Inventory
                    @mutation(typeName: INSERT) @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Mutation.createInventory",
            "a write fanned across every claimed tenant is not supported", "tenantFanOut");
    }

    @Test
    void markerOnServiceFieldRejects_aheadOfReachDerivedArms() {
        // A plain service return's reach is structurally empty; without the dedicated arm this
        // would misreport as "nothing to fan out over".
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
                serviceFilms: [Film]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
                    @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Query.serviceFilms",
            "the service fan-out combination is deferred", "tenantFanOut", "service");
    }

    @Test
    void markerOnTableMethodFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                language: Language
                    @tableMethod(
                        className: "no.sikt.graphitron.rewrite.TestTableMethodStub",
                        method: "getLanguage")
                    @reference(path: [{key: "film_language_id_fkey"}])
                    @tenantFanOut
            }
            type Language @table(name: "language") { name: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertFanOutRejection(schema, "Film.language",
            "the consumer-SQL fan-out combination is deferred", "tenantFanOut", "tableMethod");
    }

    @Test
    void markerOnRoutineFieldRejects() {
        // Without this rung a marked routine field (table-returning, tenant-scoped, unbound)
        // escapes every other rejection, classifies FanOut, and dies only at the
        // generation-time DSL-site throw — the same located-rejection gap the @tableMethod
        // rung closes.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
                @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Query.recentFilms",
            "a database routine's SQL is not graphitron's", "tenantFanOut", "routine");
    }

    @Test
    void markerOnAChildLookupFieldRejects() {
        // Child lookups report Operation.Lookup like root lookups, so one rung covers both.
        var schema = build("""
            type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
            type Film @table(name: "film") {
                title: String
                actorsByLookup(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
                    {key: "film_actor_film_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ]) @tenantFanOut
            }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertFanOutRejection(schema, "Film.actorsByLookup",
            "lookup enforces one row per input key", "tenantFanOut", "lookupKey");
    }

    @Test
    void markerOnAFieldOfANestingParentRejects_v1SupportsRootsAndTableParents() {
        // A nesting type's members never reach the fold as classified OutputField coordinates,
        // so the marker sweep (the completeness backstop) is what turns this into a rejection:
        // a silently ignored fan-out ask would be incomplete data presented as complete.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                meta: FilmMeta
            }
            type FilmMeta {
                inventories: [Inventory!] @reference(path: [{key: "inventory_film_id_fkey"}]) @tenantFanOut
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertFanOutRejection(schema, "FilmMeta.inventories",
            "never reached the fan-out classification", "tenantFanOut");
    }

    @Test
    void markerOnLookupFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: [Int!] @lookupKey @field(name: "film_id")): [Film!]! @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Query.films",
            "lookup enforces one row per input key", "tenantFanOut", "lookupKey");
    }

    @Test
    void markerOnConnectionFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @asConnection @tenantFanOut }
            """);

        assertFanOutRejection(schema, "Query.films",
            "pagination across a cross-tenant union is deferred", "tenantFanOut", "asConnection");
    }

    @Test
    void markerOnMultiTablePolymorphicFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Inventory @table(name: "inventory") { filmId: Int @field(name: "film_id") }
            union Media = Film | Inventory
            type Query {
                media(filmId: Int @field(name: "film_id")): [Media!]!
                fannedMedia: [Media!]! @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Query.fannedMedia",
            "polymorphic family is rejected in v1", "tenantFanOut");
    }

    @Test
    void markerOnSingleTableInterfaceFieldRejects() {
        // The single-table form has no UNION ALL staging conflict, but nothing motivates it and
        // relaxing later is additive: v1 rejects the whole polymorphic family.
        var schema = build("""
            interface Subject @table(name: "jti_subject") @discriminate(on: "subject_kind") {
                subjectId: Int! @field(name: "jti_subject_id")
                subjectKind: String! @field(name: "subject_kind")
            }
            type AppAccount implements Subject @table(name: "jti_subject") @discriminator(value: "APP") {
                subjectId: Int! @field(name: "jti_subject_id")
                subjectKind: String! @field(name: "subject_kind")
            }
            type Query { subjects: [Subject!]! @tenantFanOut }
            """);

        assertFanOutRejection(schema, "Query.subjects",
            "polymorphic family is rejected in v1", "tenantFanOut");
    }

    @Test
    void markerOnNonListFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film @tenantFanOut }
            """);

        assertFanOutRejection(schema, "Query.film",
            "must return a list", "tenantFanOut");
    }

    @Test
    void markerOnGlobalOnlyReachRejects() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! @tenantFanOut }
            """);

        assertFanOutRejection(schema, "Query.languages",
            "nothing to fan out over", "tenantFanOut");
    }

    @Test
    void markerOnFieldAlreadyBindingTheTenantColumnRejects() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]! @tenantFanOut
            }
            """);

        assertFanOutRejection(schema, "Query.films",
            "the tenant is already divined", "tenantFanOut");
    }

    @Test
    void markerUnderTenantBoundAncestorRejects() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @tenantFanOut
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertFanOutRejection(schema, "Film.inventories",
            "sits under a tenant-bound ancestor", "tenantFanOut");
    }

    @Test
    void nestedMarkerBelowAFannedFieldRejects() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @tenantFanOut
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query { films: [Film] @tenantFanOut }
            """);

        assertThat(schema.tenantBindingOf("Query", "films"))
            .isEqualTo(TenantBinding.FanOut.INSTANCE);
        assertFanOutRejection(schema, "Film.inventories",
            "double-fan an already fanned context", "tenantFanOut");
    }

    @Test
    void markerInASingleTenantBuildRejects() {
        // No <tenantColumn>: the axis is absent, but the marker must not be silently ignored.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);

        assertThat(schema.tenantBindingOf("Query", "films")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().contains("tenantFanOut")
                && conflict.message().contains("'Query.films'")
                && conflict.message().contains("no <tenantColumn>"));
    }
}
