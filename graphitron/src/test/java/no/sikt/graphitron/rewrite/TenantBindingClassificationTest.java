package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.GraphitronField;
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
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Query", "films");
        assertThat(binding).isInstanceOf(TenantBinding.ArgumentBound.class);
        var bound = (TenantBinding.ArgumentBound) binding;
        assertThat(bound.bindings()).hasSize(1);
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void coBindingsResolveIntoOneArmWithDeclarationOrderPrimary() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id"),
                      altFilm: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Query", "films");
        assertThat(bound.bindings()).hasSize(2);
        assertThat(bound.primary().slotName()).isEqualTo("filmId");
        assertThat(bound.bindings().get(1).slotName()).isEqualTo("altFilm");
    }

    // ===== A slot the client may omit cannot route the field on its own =====

    private static final String OPTIONAL_ONLY = "takes its tenant only from optional values";
    private static final String FAN_OUT_HINT = "Mark the field @tenantFanOut";

    private static void assertRejectsOptionalOnly(GraphitronSchema schema, String coordinate,
                                                  String slots, boolean fanOutHint) {
        var type = coordinate.split("\\.")[0];
        var field = coordinate.split("\\.")[1];
        assertThat(schema.tenantBindingOf(type, field)).isNull();
        assertThat(schema.tenantBindings().rejections())
            .filteredOn(e -> e.coordinate().equals(coordinate))
            .singleElement()
            .satisfies(e -> {
                var detail = ((Rejection.AuthorError.NoTenantBinding) e.rejection()).detail();
                assertThat(detail).contains("'" + coordinate + "' " + OPTIONAL_ONLY + " (" + slots + ")");
                if (fanOutHint) {
                    assertThat(detail).contains(FAN_OUT_HINT);
                } else {
                    assertThat(detail).doesNotContain(FAN_OUT_HINT).contains("Make one of them non-null.");
                }
            });
    }

    @Test
    void aNullableTopLevelSlotRejectsNamingTheFanOutFixFirst() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { filmsMaybe(filmId: Int @field(name: "film_id")): [Film!]! }
            """);

        assertRejectsOptionalOnly(schema, "Query.filmsMaybe", "filmId", true);
        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(((Rejection.AuthorError.NoTenantBinding) e.rejection()).detail())
                .endsWith("Mark the field @tenantFanOut to query every tenant the request may see when"
                    + " none is given, or make one of them non-null."));
    }

    private static final String FILM_ACTOR_FILTER = """
        type FilmActor implements Node @table(name: "film_actor")
                @node(keyColumns: ["actor_id", "film_id"]) {
            id: ID! @nodeId
        }
        input FilmActorFilter { id: %s @nodeId(typeName: "FilmActor") }
        type Query { filmActors(filter: FilmActorFilter%s): [FilmActor!]! }
        """;

    @Test
    void aNullableOuterInputOrANullableInnerFieldLeavesTheSlotOptional() {
        assertRejectsOptionalOnly(build(FILM_ACTOR_FILTER.formatted("ID!", "")),
            "Query.filmActors", "id", true);
        assertRejectsOptionalOnly(build(FILM_ACTOR_FILTER.formatted("ID", "!")),
            "Query.filmActors", "id", true);
    }

    @Test
    void aNonNullPathAllTheWayDownIsRequiredAndRoutes() {
        var schema = build(FILM_ACTOR_FILTER.formatted("ID!", "!"));

        var bound = argumentBound(schema, "Query", "filmActors");
        assertThat(bound.primary().required()).isTrue();
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void oneRequiredSlotRoutesAndCarriesTheOptionalOneBesideIt() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id"),
                      altFilm: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var bound = argumentBound(schema, "Query", "films");
        assertThat(bound.bindings())
            .extracting(TenantBinding.BoundSlot::slotName, TenantBinding.BoundSlot::required)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("filmId", true),
                org.assertj.core.groups.Tuple.tuple("altFilm", false));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aListSlotIsRequiredOnlyWhenItsElementsAreNonNullToo() {
        var sdl = """
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            type Query { filmsByNodeId(ids: %s @nodeId(typeName: "Film")): [Film!]! }
            """;

        assertRejectsOptionalOnly(build(sdl.formatted("[ID]!")), "Query.filmsByNodeId", "ids", true);
        assertThat(argumentBound(build(sdl.formatted("[ID!]!")), "Query", "filmsByNodeId")
            .primary().required()).isTrue();
    }

    @Test
    void aContextArgumentSlotIsRequired() {
        // The consumer supplies context arguments server-side; a missing one is a wiring bug, not
        // a choice the client makes, so it guarantees the tenant like a non-null argument.
        assertThat(TenantBindingIndex.slotRequired(null, "tenant",
            TenantBinding.SlotRead.ContextArg.INSTANCE)).isTrue();
    }

    @Test
    void theArmsRefuseASlotListThatBreaksTheirPresenceRule() {
        var optional = new TenantBinding.BoundSlot("filmId", TenantBinding.SlotRead.TopLevelArg.INSTANCE,
            TenantBinding.SlotProjection.Raw.INSTANCE, false);
        var required = new TenantBinding.BoundSlot("filmId", TenantBinding.SlotRead.TopLevelArg.INSTANCE,
            TenantBinding.SlotProjection.Raw.INSTANCE, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new TenantBinding.ArgumentBound(List.of(optional)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one required slot");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new TenantBinding.FanOut(List.of(optional, required)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("optional slots only");
    }

    @Test
    void aMutationOrAConnectionWithOptionalOnlySlotsRejectsWithoutTheFanOutHint() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            input InventoryCreateInput {
                filmId: Int @field(name: "film_id")
                storeId: Int! @field(name: "store_id")
            }
            type Query {
                filmsPage(filmId: Int @field(name: "film_id")): [Film!]! @asConnection
                    @defaultOrder(primaryKey: true)
            }
            type Mutation {
                createInventory(in: InventoryCreateInput!): Inventory @mutation(typeName: INSERT)
            }
            """);

        assertRejectsOptionalOnly(schema, "Query.filmsPage", "filmId", false);
        assertRejectsOptionalOnly(schema, "Mutation.createInventory", "filmId", false);
    }

    @Test
    void aConnectionBoundServiceWithAnOptionalTenantMarkerRejectsAsAnUnroutedCall() {
        // The payload is no @table, so the field reaches no tenant-scoped table: the
        // noTenantBinding opening would be false, and the service rejection carries the detail.
        var schema = build(SERVICE_TYPES + """
            input RateByFilmIdInput { filmId: Int @tenant  rating: Int }
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        assertThat(schema.tenantBindingOf("Mutation", "rateByFilmId")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .filteredOn(e -> e.coordinate().equals("Mutation.rateByFilmId"))
            .singleElement()
            .satisfies(e -> {
                var unrouted = (Rejection.AuthorError.UnroutedServiceCall) e.rejection();
                assertThat(unrouted.declines()).singleElement().asString()
                    .contains("'Mutation.rateByFilmId' " + OPTIONAL_ONLY + " (in.filmId)")
                    .doesNotContain(FAN_OUT_HINT);
            });
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
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Film", "inventories");
        assertThat(binding).isInstanceOf(TenantBinding.Inherited.class);
        assertThat(((TenantBinding.Inherited) binding).parentTypeName()).isEqualTo("Film");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== Cycles and unreached parents: every path from a root, over the walk's domain =====

    /** {@link #childBelowBoundAncestorYieldsInherited}'s fixture closed into a cycle. */
    private static final String FILM_INVENTORY_CYCLE = """
        type Film @table(name: "film") {
            title: String
            inventories: [Inventory!]!%s
        }
        type Inventory %s@table(name: "inventory") {
            inventoryId: Int
            film: Film%s
        }
        type Query {
            films(filmId: Int! @field(name: "film_id")): [Film!]!%s
        }
        """;

    private static String filmInventoryCycle(String inventoriesDirectives, String inventoryHead,
                                             String inventoryFields, String queryFields) {
        return FILM_INVENTORY_CYCLE.formatted(
            inventoriesDirectives, inventoryHead, inventoryFields, queryFields);
    }

    private static void assertInherited(GraphitronSchema schema, String type, String field) {
        assertThat(schema.tenantBindingOf(type, field)).isEqualTo(new TenantBinding.Inherited(type));
    }

    private static void assertFanOutRejects(GraphitronSchema schema, String coordinate, String detail) {
        assertThat(schema.tenantBindingOf(coordinate.split("\\.")[0], coordinate.split("\\.")[1]))
            .isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.message().contains("'" + coordinate + "'")
                && conflict.message().contains(detail));
    }

    @Test
    void aCycleEnteredOnlyThroughABindingRootInheritsOnEveryMember() {
        var schema = build(filmInventoryCycle("", "", "", ""));

        assertInherited(schema, "Film", "inventories");
        assertInherited(schema, "Inventory", "film");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aCycleAlsoEnteredThroughARejectedUnboundRootRejectsOnlyAtThatRoot() {
        // Every fix the root's rejection offers binds it, so the cycle is judged as entered
        // through two binding roots: both members inherit, and the root's error stands alone.
        var schema = build(filmInventoryCycle("", "", "", "\n    inventories: [Inventory!]!"));

        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.rejection())
                .isInstanceOfSatisfying(Rejection.AuthorError.NoTenantBinding.class, r -> {
                    assertThat(r.coordinate()).isEqualTo("Query.inventories");
                    assertThat(r.detail()).contains("no argument or input field maps to tenant column");
                }));
        assertInherited(schema, "Film", "inventories");
        assertInherited(schema, "Inventory", "film");
    }

    @Test
    void anUnreachedParentDoesNotDenyItsTargetAContext() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]!
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Language @table(name: "language") { films: [Film] }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertInherited(schema, "Film", "inventories");
        assertThat(schema.tenantBindingOf("Language", "films")).isNull();
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aDispatchVetoOnACycleMemberRejectsOnceAndTheCycleInherits() {
        // No Query.node: the unroutable node key is the only dispatch entry into the cycle. Every
        // fix that keeps the entry resolvable routes it, so the cycle is judged as entered routed,
        // and the node key's own text is the one error (not repeated as a federation key).
        var schema = build(filmInventoryCycle("",
            "implements Node @node(keyColumns: [\"inventory_id\"]) ", "\n    id: ID! @nodeId", ""));

        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.rejection())
                .isInstanceOfSatisfying(Rejection.AuthorError.NoTenantBinding.class, r -> {
                    assertThat(r.coordinate()).isEqualTo("Inventory");
                    assertThat(r.detail()).contains("node id key")
                        .endsWith("Add tenant column 'film_id' to @node(keyColumns:).");
                }));
        assertInherited(schema, "Film", "inventories");
        assertInherited(schema, "Inventory", "film");
    }

    @Test
    void aFanOutMarkerOnACycleEdgeReadsAsNested() {
        // The marked field is its own fanned ancestor through Inventory.film.
        var schema = build(filmInventoryCycle(" @tenantFanOut", "", "", ""));

        assertFanOutRejects(schema, "Film.inventories", "double-fan an already fanned context");
        assertInherited(schema, "Inventory", "film");
    }

    @Test
    void aFanOutMarkerLeavingACycleUnderABindingRootRejectsOnEveryMember() {
        // Asked in either order, each marker finds the binding root through the cycle.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]!
                categories: [FilmCategory!]! @tenantFanOut
            }
            type Inventory @table(name: "inventory") { inventoryId: Int film: Sequel }
            type Sequel @table(name: "film") {
                title: String
                filmActors: [FilmActor!]!
                categories: [FilmCategory!]! @tenantFanOut
            }
            type FilmActor @table(name: "film_actor") { actorId: Int film: Film }
            type FilmCategory @table(name: "film_category") { categoryId: Int }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertFanOutRejects(schema, "Film.categories", "sits under a tenant-bound ancestor");
        assertFanOutRejects(schema, "Sequel.categories", "sits under a tenant-bound ancestor");
        assertInherited(schema, "Film", "inventories");
        assertInherited(schema, "Inventory", "film");
        assertInherited(schema, "Sequel", "filmActors");
        assertInherited(schema, "FilmActor", "film");
    }

    // ===== Dispatch entries: an untenanted entity is entered with no tenant =====

    private static final String KEY_DIRECTIVE =
        "directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE\n";

    @Test
    void aCycleEnteredOnlyThroughAnUntenantedEntityRejectsOnEveryMember() {
        // Language is reached only through _entities, which serves it from the default source;
        // the cycle through Film.language must not stand in for an entry that carries a tenant.
        var schema = build(KEY_DIRECTIVE + """
            type Language @table(name: "language") @key(fields: "languageId") {
                languageId: Int @field(name: "language_id")
                films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                title: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Category @table(name: "category") { name: String }
            type Query { categories: [Category!]! }
            """);

        assertRejects(schema, "Language.films", "no ancestor established a tenant context");
    }

    @Test
    void anUntenantedEntityUnderABindingRootDeniesItsChildrenAContext() {
        // Query.films hands Language a tenant, but _entities reaches it with none.
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") {
                title: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") @key(fields: "languageId") {
                languageId: Int @field(name: "language_id")
                films: [LanguageFilm!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type LanguageFilm @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertRejects(schema, "Language.films", "no ancestor established a tenant context");
    }

    @Test
    void anUntenantedNodeTypeReachedThroughQueryNodeDeniesItsChildrenAContext() {
        // Node dispatch into an untenanted node type serves it from the default source.
        var schema = build("""
            type Language implements Node @table(name: "language") @node {
                id: ID! @nodeId
                films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { title: String }
            type Query { node(id: ID!): Node }
            """);

        assertRejects(schema, "Language.films", "no ancestor established a tenant context");
    }

    // ===== Dispatch entries: no resolvable alternative, no dispatch =====

    /**
     * {@link #anUntenantedEntityUnderABindingRootDeniesItsChildrenAContext}'s fixture with
     * {@code Language}'s key directives swapped in.
     */
    private static String languageUnderFilms(String languageKeys) {
        return KEY_DIRECTIVE + """
            type Film @table(name: "film") {
                title: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") %s {
                languageId: Int @field(name: "language_id")
                films: [LanguageFilm!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type LanguageFilm @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """.formatted(languageKeys);
    }

    @Test
    void aNonResolvableUntenantedEntityUnderABindingRootLetsItsChildrenInherit() {
        // _entities never dispatches a type with no resolvable key, so Query.films is the only
        // way in and its tenant reaches Language.films.
        var schema = build(languageUnderFilms(
            "@key(fields: \"languageId\", resolvable: false)"));

        assertInherited(schema, "Language", "films");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aNonResolvableTenantScopedEntityUnderABindingRootLetsItsChildrenInherit() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "title", resolvable: false) {
                title: String
                inventories: [Inventory!]!
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertInherited(schema, "Film", "inventories");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void anEntityWithOneResolvableAlternativeStillDeniesItsChildrenAContext() {
        // Any resolvable alternative lets _entities dispatch the type, with no tenant.
        var schema = build(languageUnderFilms(
            "@key(fields: \"languageId\", resolvable: false) @key(fields: \"name\")")
            .replace("languageId: Int @field(name: \"language_id\")",
                "languageId: Int @field(name: \"language_id\")\n    name: String"));

        assertRejects(schema, "Language.films", "no ancestor established a tenant context");
    }

    @Test
    void aNonResolvableNodeTypeUnderABindingRootLetsItsChildrenInherit() {
        // An explicit non-resolvable id key opts the node type out of Query.node dispatch too,
        // which reaches it only through the same per-alternative skip as _entities.
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") {
                title: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language implements Node @table(name: "language") @node
                    @key(fields: "id", resolvable: false) {
                id: ID! @nodeId
                films: [LanguageFilm!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type LanguageFilm @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertInherited(schema, "Language", "films");
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
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        var binding = schema.tenantBindingOf("Film", "mountedPrincipal");
        assertThat(binding).isInstanceOf(TenantBinding.Inherited.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void sessionBoundServiceAtARootNamingNoTenant_rejects() {
        // The complement: a root has no tenant context to inherit, and the service's own SQL is
        // opaque, so a $session binding with nothing in the arguments naming a tenant would run
        // on the default source whatever the service touches. The build refuses it.
        var schema = build("""
            type Query {
                sessionPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """);

        assertThat(schema.tenantBindingOf("Query", "sessionPrincipal")).isNull();
        assertUnroutedWithoutDeclines(schema, "Query.sessionPrincipal");
    }

    /**
     * The no-declines form of the unrouted-service refusal: one rejection at the coordinate,
     * naming the tenant column and both fixes.
     */
    static Rejection.AuthorError.UnroutedServiceCall assertUnroutedWithoutDeclines(
            GraphitronSchema schema, String coordinate) {
        var unrouted = schema.tenantBindings().rejections().stream()
            .filter(e -> e.coordinate().equals(coordinate))
            .map(e -> e.rejection())
            .filter(r -> r instanceof Rejection.AuthorError.UnroutedServiceCall)
            .map(r -> (Rejection.AuthorError.UnroutedServiceCall) r)
            .toList();
        assertThat(unrouted).hasSize(1);
        var rejection = unrouted.get(0);
        assertThat(rejection.declines()).isEmpty();
        assertThat(rejection.message())
            .contains("'" + coordinate + "' is a @service that is handed a connection")
            .contains("tenant column 'film_id'")
            .contains("@nodeId(typeName:)")
            .doesNotContain("reaches tenant-scoped table");
        if (rejection.evidence().isEmpty()) {
            assertThat(rejection.message()).contains("mark the field @globalData");
        }
        return rejection;
    }

    @Test
    void globalTableFieldYieldsAGlobalRead() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "languages"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
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
                media(filmId: Int! @field(name: "film_id")): [Media!]!
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

    // ===== The Untenanted leaves: a global read follows the default tenant, a write or service does not =====

    @Test
    void aGlobalBatchedChildOfATenantRowIsAGlobalRead() {
        var schema = build("""
            type Film @table(name: "film") { inventories: [Inventory!]! }
            type Inventory @table(name: "inventory") {
                inventoryId: Int @field(name: "inventory_id")
                store: Store @splitQuery
            }
            type Store @table(name: "store") { storeId: Int @field(name: "store_id") }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertThat(schema.tenantBindingOf("Inventory", "store"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aDmlWriteToAGlobalTableKeepsTheDefaultSource() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            input LanguageCreateInput { name: String! @field(name: "name") }
            type Query { languages: [Language!]! }
            type Mutation {
                createLanguage(in: LanguageCreateInput!): Language @mutation(typeName: INSERT)
            }
            """);

        assertThat(schema.tenantBindingOf("Mutation", "createLanguage"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindingOf("Query", "languages"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
    }

    @Test
    void aChildServiceOnAGlobalParentKeepsTheDefaultSource() {
        var schema = build("""
            type Language @table(name: "language") {
                name: String
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TenantServiceStub",
                    method: "ratingWithDsl"})
            }
            type Query { languages: [Language!]! }
            """);

        assertThat(schema.tenantBindingOf("Language", "rating"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void nodeDispatchOverOnlyGlobalNodeTypesIsAGlobalRead() {
        var schema = build("""
            interface Node { id: ID! }
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId }
            type Query { node(id: ID!): Node }
            """);

        assertThat(schema.tenantBindingOf("Query", "node"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
    }

    @Test
    void polymorphicRootOverGlobalParticipantsYieldsAGlobalRead() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Address @table(name: "address") { postalCode: String @field(name: "postal_code") }
            union Reference = Language | Address
            type Query { references: [Reference!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "references"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
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
            .anyMatch(slot -> slot.source().equals(
                new TenantBinding.EntityRepBound.TenantSource.KeyColumn(1)));
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
            .containsExactly(new TenantBinding.EntityRepBound.AlternativeSlot(0,
                new TenantBinding.EntityRepBound.TenantSource.KeyColumn(1)));
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
                filmActorsByNodeId(ids: [ID!]! @nodeId(typeName: "FilmActor")): [FilmActor!]!
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

    private static final String FILM_SCENE_SDL = """
        type FilmScene implements Node @table(name: "film_scene")
                @node(keyColumns: ["film_id", "scene_no"]) {
            id: ID! @nodeId
            label: String @field(name: "label")
        }
        type Language @table(name: "language") { name: String }
        type Query { languages: [Language!]! }
        type Mutation {
            updateFilmSceneParent(in: UpdateFilmSceneParentInput!): ID
                @mutation(typeName: UPDATE, table: "film_scene")
            updateFilmSceneParents(in: [UpdateFilmSceneParentInput!]!): [ID!]!
                @mutation(typeName: UPDATE, table: "film_scene")
        }
        input UpdateFilmSceneParentInput {
            id: ID! @nodeId(typeName: "FilmScene")
            parent: ID @nodeId(typeName: "FilmScene") @reference(path: [{key: "film_scene_parent_fk"}])
            label: String @field(name: "label")
        }
        """;

    @Test
    void selfFkReferenceSharingTheTenantColumnCoBindsAfterTheWhereSlot() {
        // parent's self-FK (film_id, parent_scene_no) lands film_id in SET, but the walker has
        // already checked it equal to id's film_id, so it names the same tenant: it co-binds
        // rather than declining, and the agreement fold refuses a parent in another tenant.
        var schema = build(FILM_SCENE_SDL);
        assertThat(schema.tenantBindings().rejections()).isEmpty();

        for (var field : List.of("updateFilmSceneParent", "updateFilmSceneParents")) {
            var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", field);
            assertThat(bound.bindings()).extracting(TenantBinding.BoundSlot::slotName)
                .containsExactly("id", "parent");
            for (var slot : bound.bindings()) {
                assertThat(slot.read())
                    .isEqualTo(new TenantBinding.SlotRead.NestedInput("in", List.of(slot.slotName())));
                assertThat(slot.projection())
                    .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                        decoded -> assertThat(decoded.slot()).isZero());
            }
        }
    }

    @Test
    void decodedReferenceWritingAnOutOfKeyTenantColumnStillRejects() {
        // inventory's key is inventory_id alone, so the Film reference lifts film_id into SET with
        // nothing in WHERE to agree with: a real move between tenants. The rule is keyed on the
        // walker's agreement obligation, not on the carrier decoding a node id.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                updateInventory(in: UpdateInventoryFilmRefInput!): Inventory
                    @mutation(typeName: UPDATE, table: "inventory")
            }
            input UpdateInventoryFilmRefInput {
                inventoryId: Int! @field(name: "inventory_id")
                filmRef: ID! @nodeId(typeName: "Film")
            }
            """);

        assertThat(schema.tenantBindingOf("Mutation", "updateInventory")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.updateInventory")
                && r.detail().contains("SET clause"));
    }

    @Test
    void idReturningWriteToATenantScopedTableWithNoBindingRejects() {
        // A DML write returning an encoded id has no Record return target, so its reach comes
        // from the write target alone. Without that, this DELETE touches no table as far as the
        // fold can tell, classifies Untenanted, and runs on the default source.
        var schema = build("""
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                deleteInventory(in: DeleteInventoryInput!): ID
                    @mutation(typeName: DELETE, table: "inventory")
            }
            input DeleteInventoryInput { inventoryId: Int! @field(name: "inventory_id") }
            """);

        assertThat(schema.tenantBindingOf("Mutation", "deleteInventory")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.deleteInventory")
                && r.detail().contains("no argument or input field maps to tenant column"));
    }

    @Test
    void insertWithArityOneNodeIdCarrierDivinesTheDecodedSlot() {
        // The transform-blind site: an INSERT input field whose @nodeId decodes to a key that is
        // the tenant column alone. Before the projection axis the input-field walk read the
        // column off the carrier and minted a raw read of the base64 id as the tenant value.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createFilm(in: CreateKeyedFilmInput!): ID @mutation(typeName: INSERT, table: "film")
            }
            input CreateKeyedFilmInput {
                id: ID! @nodeId(typeName: "Film")
                title: String! @field(name: "title")
            }
            """);

        var bound = (TenantBinding.ArgumentBound) schema.tenantBindingOf("Mutation", "createFilm");
        assertThat(bound.primary().slotName()).isEqualTo("id");
        assertThat(bound.primary().read())
            .isEqualTo(new TenantBinding.SlotRead.NestedInput("in", List.of("id")));
        assertThat(bound.primary().projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isZero());
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void insertReferenceCarrierReachingTheTenantThroughAJoinNeverReachesTheFold() {
        // A Remote-bound reference carrier names the tenant column on the joined table, so the
        // INSERT's own table has no column to route on. The fold has no decline for it because
        // the mutation input gate refuses every Remote carrier first: the write never classifies,
        // so the coordinate carries no tenant verdict and no tenant rejection of its own.
        var schema = build("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createInventory(in: InventoryCreateThroughJoinInput!): Inventory
                    @mutation(typeName: INSERT, table: "inventory")
            }
            input InventoryCreateThroughJoinInput {
                filmId: Int! @field(name: "film_id") @reference(path: [{key: "inventory_film_id_fkey"}])
                storeId: Int! @field(name: "store_id")
            }
            """);

        assertThat(schema.field("Mutation", "createInventory"))
            .isInstanceOfSatisfying(GraphitronField.UnclassifiedField.class,
                field -> assertThat(field.reason()).contains("written by a @mutation"));
        assertThat(schema.tenantBindingOf("Mutation", "createInventory")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.createInventory"));
    }

    @Test
    void pruningNodeIdLeafOnAPolymorphicRootRejectsWithItsOwnMessage() {
        // A bare @nodeId over Film | FilmActor means a different node type per branch: each
        // participant keeps only the ids it can decode, so there is no one decode to route the
        // statement's single connection on, although both keys embed the tenant column.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            union FilmThing = Film | FilmActor
            type Query { filmThingById(id: ID! @nodeId): FilmThing }
            """);

        assertThat(schema.tenantBindingOf("Query", "filmThingById")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Query.filmThingById")
                && r.detail().contains("no single decode to route the statement on"));
    }

    @Test
    void routineWriteMintsNoSlotSoItsTenantIsNeverDecodedAtTheEntryPoint() {
        // A @routine write's only operation member is the routine call, which carries no filter,
        // lookup or input surface, so no argument ever mints a bound slot for it: even an argument
        // whose value is the tenant key leaves a tenant-scoped routine write unbound. That is why
        // the routine-write acquisition never meets a decoded projection.
        var schema = TestSchemaHelper.buildSchema("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, TestConfiguration.testContext().withTenantColumn("inventory_id"));

        assertThat(schema.tenantBindingOf("Mutation", "rentFilm")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.rentFilm")
                && r.detail().contains("no argument or input field maps to tenant column"));
    }

    // ===== A column-bound filter slot divines whatever @condition does to its predicate =====

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    /** A field-level {@code @condition(override: true)} whose method binds no argument. */
    private static final String FIELD_OVERRIDE =
        "@condition(condition: {className: \"" + STUB + "\", method: \"lifterFieldCondition\"}, override: true)";

    private static TenantBinding.BoundSlot onlySlot(GraphitronSchema schema, String field) {
        assertThat(schema.tenantBindings().rejections()).isEmpty();
        var binding = schema.tenantBindingOf("Query", field);
        assertThat(binding).isInstanceOf(TenantBinding.ArgumentBound.class);
        var bound = (TenantBinding.ArgumentBound) binding;
        assertThat(bound.bindings()).hasSize(1);
        return bound.primary();
    }

    private static void assertNoTenantBinding(GraphitronSchema schema, String field, String detail) {
        assertThat(schema.tenantBindingOf("Query", field)).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Query." + field)
                && r.detail().contains(detail));
    }

    @Test
    void siblingArgumentOverrideLeavesTheTenantArgumentBound() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id"),
                      title: String @field(name: "title")
                        @condition(condition: {className: "%s", method: "argConditionRenamed",
                            argMapping: "city: title"}, override: true)): [Film!]!
            }
            """.formatted(STUB));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void fieldLevelOverrideLeavesTheTenantArgumentBound() {
        // The field-level override suppresses every argument's implicit predicate; the argument
        // still names the tenant column, and routing reads the binding, not the predicate.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]! %s
            }
            """.formatted(FIELD_OVERRIDE));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void tenantArgumentsOwnOverrideLeavesItBound() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")
                    @condition(condition: {className: "%s", method: "filmIdArgCondition"}, override: true)): [Film!]!
            }
            """.formatted(STUB));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void tenantArgumentsOwnConditionWithoutOverrideStaysBound() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")
                    @condition(condition: {className: "%s", method: "filmIdArgCondition"})): [Film!]!
            }
            """.formatted(STUB));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void filterInputFieldUnderFieldLevelOverrideIsBound() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            input FilmFilter { filmId: Int! @field(name: "film_id") }
            type Query {
                films(filter: FilmFilter!): [Film!]! %s
            }
            """.formatted(FIELD_OVERRIDE));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("filter", List.of("filmId")));
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void filterInputFieldCarryingItsOwnConditionIsBound() {
        // An input field's own @condition replaces its implicit predicate rather than stacking with
        // it, so this shape lost the binding with no override anywhere in the schema.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                filmId: ID! @field(name: "film_id")
                    @condition(condition: {className: "%s", method: "inputColumnCondition"})
            }
            type Query { films(filter: FilmFilter!): [Film!]! }
            """.formatted(STUB));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("filter", List.of("filmId")));
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void filterInputFieldCarryingItsOwnOverrideIsBound() {
        // The one suppression made at classification: the field classifies ConditionOwnedField,
        // which emits no predicate at all, so only its resolved column says it binds film_id.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                filmId: ID! @field(name: "film_id")
                    @condition(condition: {className: "%s", method: "inputColumnCondition"}, override: true)
            }
            type Query { films(filter: FilmFilter!): [Film!]! }
            """.formatted(STUB));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("filter", List.of("filmId")));
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void lookupKeyTenantArgumentUnderFieldLevelOverrideStaysBound() {
        // The lookup mapping owns this slot; it never came from a predicate.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: [Int!]! @field(name: "film_id") @lookupKey): [Film]! %s
            }
            """.formatted(FIELD_OVERRIDE));

        var slot = onlySlot(schema, "films");
        assertThat(slot.slotName()).isEqualTo("filmId");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    @Test
    void decodedLookupKeyStaysRejectedWithNoSuppressionActive() {
        // Each decoded @lookupKey id carries its own tenant, so the batch has no single tenant to
        // route one statement on. With a plain @condition and no override anywhere, the lookup
        // clause of the column-binding question is the only thing keeping this slot out of the
        // ledger; without it a cross-tenant ids: batch would read one tenant's database.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Query {
                filmActorsByIds(ids: [ID!]! @nodeId(typeName: "FilmActor") @lookupKey): [FilmActor]!
                    @condition(condition: {className: "%s", method: "lifterFieldCondition"})
            }
            """.formatted(STUB));

        assertNoTenantBinding(schema, "filmActorsByIds", "no argument or input field maps to tenant column");
        var error = schema.tenantBindings().rejections().stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Query.filmActorsByIds"))
            .findFirst().orElseThrow();
        assertThat(error.rejection()).isInstanceOf(Rejection.AuthorError.NoTenantBinding.class);
    }

    @Test
    void nodeIdFilterInputFieldDivinesTheDecodedSlot() {
        // The filter-input half of the decoded-key read: the nested @nodeId field's slot comes
        // from the column-binding ledger, reading the input path and decoding the key.
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            input FilmActorFilter { id: ID! @nodeId(typeName: "FilmActor") }
            type Query { filmActors(filter: FilmActorFilter!): [FilmActor!]! }
            """);

        var slot = onlySlot(schema, "filmActors");
        assertThat(slot.slotName()).isEqualTo("id");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("filter", List.of("id")));
        assertThat(slot.projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isEqualTo(1));
    }

    @Test
    void sameTableNodeIdArgumentUnderFieldLevelOverrideDivinesTheDecodedSlot() {
        // The two axes compose: the location comes from the suppressed carrier the ledger
        // recorded, the transform from its extraction. Arity 1 on purpose, the shape a
        // column-count discriminator would get wrong.
        var schema = build("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query {
                filmsByNodeId(ids: [ID!]! @nodeId(typeName: "Film")): [Film!]! %s
            }
            """.formatted(FIELD_OVERRIDE));

        var slot = onlySlot(schema, "filmsByNodeId");
        assertThat(slot.slotName()).isEqualTo("ids");
        assertThat(slot.read()).isEqualTo(TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(slot.projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class,
                decoded -> assertThat(decoded.slot()).isZero());
    }

    private static final String DECLINE =
        "no single decode to route the statement on";

    private static String occSchema(String members, String fieldDirectives) {
        return """
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            union Occ = %s
            type Query { occ(id: ID! @nodeId): [Occ!]! %s }
            """.formatted(members, fieldDirectives);
    }

    @Test
    void multiTablePolymorphicNodeIdDeclinesInBothMemberOrders() {
        // Inventory's key omits film_id and FilmActor's embeds it; putting Inventory first is the
        // order that tells a per-participant read from a coordinate-wide one.
        for (String members : List.of("Inventory | FilmActor", "FilmActor | Inventory")) {
            assertNoTenantBinding(build(occSchema(members, "")), "occ", DECLINE);
        }
    }

    @Test
    void multiTablePolymorphicNodeIdUnderFieldLevelOverrideDeclinesWithItsOwnMessage() {
        // The suppressed carrier still reaches the fold, and the decline channel, not a mint-side
        // clause, keeps it from routing.
        for (String members : List.of("Inventory | FilmActor", "FilmActor | Inventory")) {
            var schema = build(occSchema(members, FIELD_OVERRIDE));
            assertNoTenantBinding(schema, "occ", DECLINE);
            assertThat(schema.tenantBindings().rejections())
                .noneMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                    && r.coordinate().equals("Query.occ")
                    && r.detail().contains("no argument or input field maps to tenant column"));
        }
    }

    // ===== @service: a root divines from its arguments, a connection-binding child inherits =====

    private static final String TENANT_SERVICE = "no.sikt.graphitron.rewrite.TenantServiceStub";
    private static final String TENANT_HOLDER = "no.sikt.graphitron.rewrite.TenantHolderServiceStub";

    /** Film keyed on the tenant column alone, a tenant-scoped child, and a non-table wrapper. */
    private static final String SERVICE_TYPES = """
        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID! @nodeId
            title: String
            inventories: [Inventory!]!
        }
        type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
        type RateFilmsPayload { films: [Film!]! }
        input RateFilmInput { film: ID! @nodeId(typeName: "Film") }
        type Query { x: String }
        """;

    private static String service(String method) {
        return "@service(service: {className: \"" + TENANT_SERVICE + "\", method: \"" + method + "\"})";
    }

    private static TenantBinding.ArgumentBound argumentBound(GraphitronSchema schema, String type, String field) {
        var binding = schema.tenantBindingOf(type, field);
        assertThat(binding).as(type + "." + field).isInstanceOf(TenantBinding.ArgumentBound.class);
        return (TenantBinding.ArgumentBound) binding;
    }

    private static void assertDecodedSlot(TenantBinding.BoundSlot slot, String name,
                                          TenantBinding.SlotRead read, int position) {
        assertThat(slot.slotName()).isEqualTo(name);
        assertThat(slot.read()).isEqualTo(read);
        assertThat(slot.projection()).isInstanceOfSatisfying(
            TenantBinding.SlotProjection.DecodedKeySlot.class,
            decoded -> assertThat(decoded.slot()).isEqualTo(position));
    }

    private static void assertRejects(GraphitronSchema schema, String coordinate, String detail) {
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals(coordinate)
                && r.detail().contains(detail));
    }

    @Test
    void wrapperServiceWithANodeIdRecordMemberDivinesAndItsChildrenInherit() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateFilm(in: RateFilmInput!): RateFilmsPayload %s }
            """.formatted(service("rateFilm")));

        var bound = argumentBound(schema, "Mutation", "rateFilm");
        assertThat(bound.bindings()).hasSize(1);
        assertDecodedSlot(bound.primary(), "in.film",
            new TenantBinding.SlotRead.NestedInput("in", List.of("film")), 0);
        assertThat(schema.tenantBindingOf("RateFilmsPayload", "films"))
            .isEqualTo(new TenantBinding.Inherited("RateFilmsPayload"));
        assertThat(schema.tenantBindingOf("Film", "inventories"))
            .isEqualTo(new TenantBinding.Inherited("Film"));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aBatchOfBeansReadsTheSamePathOffEveryElement() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateFilms(ratings: [RateFilmInput!]!): RateFilmsPayload %s }
            """.formatted(service("rateFilms")));

        assertDecodedSlot(argumentBound(schema, "Mutation", "rateFilms").primary(), "ratings.film",
            new TenantBinding.SlotRead.NestedInput("ratings", List.of("film")), 0);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aJooqRecordParameterBindingTheTenantColumnDivinesRaw() {
        var schema = build(SERVICE_TYPES + """
            input ModifyFilmInput {
                filmId: Int! @field(name: "film_id")
                title: String @field(name: "title")
            }
            type Mutation { modifyFilms(in: [ModifyFilmInput!]!): RateFilmsPayload %s }
            """.formatted(service("modifyFilms")));

        var slot = argumentBound(schema, "Mutation", "modifyFilms").primary();
        assertThat(slot.slotName()).isEqualTo("in.filmId");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("in", List.of("filmId")));
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
        assertThat(schema.tenantBindingOf("RateFilmsPayload", "films"))
            .isInstanceOf(TenantBinding.Inherited.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aJooqRecordParameterDecodingACompositeKeyReadsTheTenantPosition() {
        var schema = build(SERVICE_TYPES + """
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            input ModifyFilmActorInput { id: ID! @nodeId(typeName: "FilmActor") }
            type Mutation { modifyFilmActor(in: ModifyFilmActorInput!): RateFilmsPayload %s }
            """.formatted(service("modifyFilmActor")));

        assertDecodedSlot(argumentBound(schema, "Mutation", "modifyFilmActor").primary(), "in.id",
            new TenantBinding.SlotRead.NestedInput("in", List.of("id")), 1);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    /**
     * A code table with catalog node metadata (type id {@code Baz}, key {@code id}) exposed as a
     * plain {@code @table} type: no {@code implements Node}, no {@code @node}, and no tenant column.
     */
    private static final String TABLE_ONLY_BAZ = """
        type Baz @table(name: "baz") { code: String @field(name: "id") }
        """;

    /**
     * The field classified, bound no tenant slot, and drew no tenant rejection of its own. Its
     * payload's tenant-scoped child is what makes the fold ask whether the root establishes a
     * context, which is the path that reaches the decode; with nothing naming a tenant, the child
     * rejects as it would beside any other argument that names none.
     */
    private static void assertBuildsUnbound(GraphitronSchema schema, String field) {
        assertThat(schema.field("Mutation", field)).isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(schema.tenantBindingOf("Mutation", field))
            .isNotNull()
            .isNotInstanceOf(TenantBinding.ArgumentBound.class);
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.coordinate().equals("Mutation." + field));
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void aJooqRecordParameterDecodingATableOnlyTypeWithoutTheTenantInItsKeySkipsTheDecode() {
        var schema = build(SERVICE_TYPES + TABLE_ONLY_BAZ + """
            input ModifyBazInput { id: ID! @nodeId(typeName: "Baz") }
            type Mutation { modifyBaz(in: ModifyBazInput!): RateFilmsPayload %s }
            """.formatted(service("modifyBaz")));

        assertBuildsUnbound(schema, "modifyBaz");
    }

    @Test
    void aJooqRecordParameterReferencingATableOnlyTypeWithoutTheTenantInItsKeySkipsTheDecode() {
        // The reported shape: a foreign key loaded from the id of a code table that is no longer
        // a node type in this schema, beside plain column bindings for the rest of the record.
        var schema = build(SERVICE_TYPES + TABLE_ONLY_BAZ + """
            input ModifyBarInput {
                bazId: ID @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
                name: String @field(name: "name")
            }
            type Mutation { modifyBar(in: ModifyBarInput!): RateFilmsPayload %s }
            """.formatted(service("modifyBar")));

        assertBuildsUnbound(schema, "modifyBar");
    }

    @Test
    void aRecordSlotDecodingATableOnlyTypeWithoutTheTenantInItsKeySkipsTheDecode() {
        var schema = build(SERVICE_TYPES + TABLE_ONLY_BAZ + """
            type Mutation { pickBaz(baz: ID! @nodeId(typeName: "Baz")): RateFilmsPayload %s }
            """.formatted(service("pickBaz")));

        assertBuildsUnbound(schema, "pickBaz");
    }

    @Test
    void aJooqRecordParameterDecodingATableOnlyTypeWithTheTenantInItsKeyDeclines() {
        // The node-type twin is aJooqRecordParameterDecodingACompositeKeyReadsTheTenantPosition:
        // same table, same key, but there the decode helper exists to route on.
        var schema = build(SERVICE_TYPES + """
            type FilmActorCode @table(name: "film_actor") { actorId: Int @field(name: "actor_id") }
            input PickByFilmActorInput { id: ID! @nodeId(typeName: "FilmActorCode") }
            type Mutation { pickFilmForFilmActor(in: PickByFilmActorInput!): Film %s }
            """.formatted(service("pickFilmForFilmActor")));

        assertThat(schema.tenantBindingOf("Mutation", "pickFilmForFilmActor")).isNull();
        assertRejects(schema, "Mutation.pickFilmForFilmActor",
            "the tenant is reached through @nodeId(typeName: 'FilmActorCode') at in.id, whose key"
                + " carries tenant column 'film_id', but 'FilmActorCode' is not a node type");
        assertRejects(schema, "Mutation.pickFilmForFilmActor",
            "Make 'FilmActorCode' a node type, or bind the tenant through a field mapping to tenant"
                + " column 'film_id'.");
        // The reason is a scope decision, not a missing helper: a leaf naming the same type gets a
        // catalog decode helper and routes on it.
        assertRejects(schema, "Mutation.pickFilmForFilmActor",
            "a record decode of a @table-only type does not route the statement on its tenant");
    }

    @Test
    void aScalarArgumentOrFilterFieldNamingATableOnlyTypeDecodesFromCatalogMetadata() {
        // A leaf naming a @table-only type over a metadata table decodes against that metadata,
        // through a decode helper minted under the type's own name. baz is global, so neither
        // field binds a tenant slot.
        var schema = build(TABLE_ONLY_BAZ + """
            input BazFilter { id: ID @nodeId(typeName: "Baz") }
            type Query {
                baz(id: ID! @nodeId(typeName: "Baz")): Baz
                bazes(filter: BazFilter): [Baz!]!
            }
            """);

        for (var field : List.of("baz", "bazes")) {
            assertThat(schema.field("Query", field)).as(field)
                .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
            assertThat(schema.tenantBindingOf("Query", field)).as(field)
                .isNotInstanceOf(TenantBinding.ArgumentBound.class);
            assertThat(schema.tenantBindings().rejections())
                .noneMatch(e -> e.coordinate().equals("Query." + field));
        }
        assertThat(TestSchemaHelper.diagnosticMessages(schema)).doesNotContain("'Baz' is not a node type");
        assertThat(schema.catalogNodeDecodes()).singleElement()
            .satisfies(decode -> assertThat(decode.methodName()).isEqualTo("decodeBaz"));
    }

    @Test
    void aFilterFieldNamingATableOnlyTypeWithTheTenantInItsKeyRoutesOnTheCatalogDecode() {
        // The node-type twin is nodeIdFilterInputFieldDivinesTheDecodedSlot: same table, same key,
        // and the catalog decode helper routes exactly as the node type's does.
        var schema = build("""
            type FilmActorCode @table(name: "film_actor") { actorId: Int @field(name: "actor_id") }
            input FilmActorCodeFilter { id: ID! @nodeId(typeName: "FilmActorCode") }
            type Query { filmActorCodes(filter: FilmActorCodeFilter!): [FilmActorCode!]! }
            """);

        var slot = onlySlot(schema, "filmActorCodes");
        assertThat(slot.slotName()).isEqualTo("id");
        assertThat(slot.read()).isEqualTo(new TenantBinding.SlotRead.NestedInput("filter", List.of("id")));
        assertThat(slot.projection())
            .isInstanceOfSatisfying(TenantBinding.SlotProjection.DecodedKeySlot.class, decoded -> {
                assertThat(decoded.slot()).isEqualTo(1);
                assertThat(decoded.decode().methodName()).isEqualTo("decodeFilmActorCode");
            });
    }

    @Test
    void aKeySlotOfANodeTypeWhoseKeyMissesTheTenantIsEvidenceOffTheDecodesTable() {
        // A root @service scalar slot taking the decoded key value: inventory carries the tenant
        // column, its key inventory_id does not, so the slot reads no tenant and the fold names it
        // as evidence, reading the table off the decode itself.
        var schema = build("""
            interface Node { id: ID! }
            type Inventory implements Node @table(name: "inventory") @node {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation {
                pickInventoryOnConnection(inventoryId: ID! @nodeId(typeName: "Inventory")): String %s
            }
            """.formatted(service("pickInventoryOnConnection")));

        var rejection = assertUnroutedWithoutDeclines(schema, "Mutation.pickInventoryOnConnection");
        assertThat(rejection.evidence()).containsExactly("Inventory id at inventoryId");
    }

    @Test
    void aTopLevelNodeIdArgumentReadsTheArgument() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateById(film: ID! @nodeId(typeName: "Film")): RateFilmsPayload %s }
            """.formatted(service("rateById")));

        assertDecodedSlot(argumentBound(schema, "Mutation", "rateById").primary(), "film",
            TenantBinding.SlotRead.TopLevelArg.INSTANCE, 0);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void twoTenantBearingIdsOfDifferentNodeTypesMintTwoSlots() {
        // Each decodes through its own node type's helper; the agreement fold then refuses a row
        // whose two ids name different tenants and skips the optional one when it is absent.
        var schema = build(SERVICE_TYPES + """
            type FilmFed implements Node @table(name: "film") @node(typeId: "FF", keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            input RatePairInput {
                film: ID! @nodeId(typeName: "Film")
                alsoFilm: ID @nodeId(typeName: "FilmFed")
            }
            type Mutation { ratePair(in: RatePairInput!): RateFilmsPayload %s }
            """.formatted(service("ratePair")));

        var bound = argumentBound(schema, "Mutation", "ratePair");
        assertThat(bound.bindings()).extracting(TenantBinding.BoundSlot::slotName)
            .containsExactly("in.film", "in.alsoFilm");
        assertThat(bound.bindings()).extracting(slot ->
                ((TenantBinding.SlotProjection.DecodedKeySlot) slot.projection()).decode().methodName())
            .containsExactly("decodeFilm", "decodeFilmFed");
    }

    @Test
    void twoIdsWithOneNameAtDifferentPathsMintTwoSlots() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateTwo(first: RateFilmInput!, second: RateFilmInput!): RateFilmsPayload %s }
            """.formatted(service("rateTwo")));

        assertThat(argumentBound(schema, "Mutation", "rateTwo").bindings())
            .extracting(TenantBinding.BoundSlot::slotName)
            .containsExactly("first.film", "second.film");
    }

    @Test
    void anUndecodedIdNamesNoTenantSoTheWrappersChildStillRejects() {
        // The accepted gap: a String parameter holding the encoded id carries no node type the
        // build can read, so the root stays on the default source and nothing below inherits.
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateByRawId(film: ID!): RateFilmsPayload %s }
            """.formatted(service("rateByRawId")));

        assertThat(schema.tenantBindingOf("Mutation", "rateByRawId"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void anUndecodedIdHandedAConnectionRejectsAtTheRootAndTheChildStillRejects() {
        // The same undecoded id, but the service is handed a DSLContext: it would write on the
        // default database, so the root itself is refused, beside the payload child's own text.
        // The child's error is not consequential: @globalData would be accepted on the root, and
        // after that fix the payload really has no tenant.
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateByRawIdOnConnection(film: ID!): RateFilmsPayload %s }
            """.formatted(service("rateByRawIdOnConnection")));

        assertThat(schema.tenantBindingOf("Mutation", "rateByRawIdOnConnection")).isNull();
        var rejection = assertUnroutedWithoutDeclines(schema, "Mutation.rateByRawIdOnConnection");
        assertThat(rejection.evidence()).isEmpty();
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void aTenantReachRootServiceHandedAConnectionGetsTheServiceText() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { pickFilmOnConnection(film: ID!): Film %s }
            """.formatted(service("pickFilmOnConnection")));

        assertThat(schema.tenantBindingOf("Mutation", "pickFilmOnConnection")).isNull();
        assertUnroutedWithoutDeclines(schema, "Mutation.pickFilmOnConnection");
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding
                && e.coordinate().equals("Mutation.pickFilmOnConnection"));
    }

    @Test
    void aTenantTablesRecordBindingNoTenantIsNamedAsEvidence() {
        var schema = build(SERVICE_TYPES + """
            input RetitleFilmInput { title: String @field(name: "title") }
            type Mutation { retitleFilm(in: RetitleFilmInput!): String %s }
            """.formatted(service("retitleFilm")));

        var rejection = assertUnroutedWithoutDeclines(schema, "Mutation.retitleFilm");
        assertThat(rejection.evidence()).containsExactly("FilmRecord at in");
        assertThat(rejection.message())
            .contains("Its arguments carry tenant-scoped values that bind no tenant: FilmRecord at in.")
            .doesNotContain("@globalData");
    }

    @Test
    void anUnroutedConnectionServiceWithTenantEvidenceRejectsAloneAndItsPayloadInherits() {
        // The @globalData ladder refuses the marker over the evidence, so every fix left binds a
        // tenant in the arguments, and the payload's tenant-scoped child is judged as it leaves it.
        var schema = build(SERVICE_TYPES + """
            input RetitleFilmInput { title: String @field(name: "title") }
            type Mutation { retitleFilmRated(in: RetitleFilmInput!): RateFilmsPayload %s }
            """.formatted(service("retitleFilmRated")));

        assertThat(schema.tenantBindingOf("Mutation", "retitleFilmRated")).isNull();
        assertThat(assertUnroutedWithoutDeclines(schema, "Mutation.retitleFilmRated").evidence())
            .containsExactly("FilmRecord at in");
        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.coordinate()).isEqualTo("Mutation.retitleFilmRated"));
        assertInherited(schema, "RateFilmsPayload", "films");
        assertInherited(schema, "Film", "inventories");
    }

    @Test
    void aPolymorphicRecordIdAtAnEmptyReachConnectionServiceRejectsOnceWithTheDecline() {
        // The widened decline gate: the service needs a tenant though its reach is empty, and the
        // shape naming the tenant cannot route it. One rejection, carrying the decline's own text.
        var schema = build(SERVICE_TYPES + FILM_THING + """
            input ThingInput { occupant: ID! @nodeId(typeName: "FilmThing") }
            type Mutation { pickForThing(in: ThingInput!): String %s }
            """.formatted(service("pickForThing")));

        assertThat(schema.tenantBindingOf("Mutation", "pickForThing")).isNull();
        var atField = schema.tenantBindings().rejections().stream()
            .filter(e -> e.coordinate().equals("Mutation.pickForThing"))
            .toList();
        assertThat(atField).hasSize(1);
        assertThat(atField.get(0).rejection())
            .isInstanceOfSatisfying(Rejection.AuthorError.UnroutedServiceCall.class, r -> {
                assertThat(r.declines()).hasSize(1);
                assertThat(r.message())
                    .contains("only through shapes that cannot route the call")
                    .contains("no single decode to route the service call on")
                    .doesNotContain("reaches tenant-scoped table")
                    .doesNotContain("nothing in its arguments names a tenant")
                    .doesNotContain("@globalData");
            });
    }

    @Test
    void aTableReturningServiceRootOverATenantTableDivines() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { pickFilm(in: RateFilmInput!): Film %s }
            """.formatted(service("pickFilm")));

        assertDecodedSlot(argumentBound(schema, "Mutation", "pickFilm").primary(), "in.film",
            new TenantBinding.SlotRead.NestedInput("in", List.of("film")), 0);
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.coordinate().equals("Mutation.pickFilm"));
    }

    /** A union over two tenant-scoped node types whose keys hold the tenant at different positions. */
    private static final String FILM_THING = """
        type FilmActor implements Node @table(name: "film_actor")
                @node(keyColumns: ["actor_id", "film_id"]) {
            id: ID! @nodeId
        }
        union FilmThing = Film | FilmActor
        """;

    @Test
    void aPolymorphicRecordIdAtATableReturningRootRejectsWithItsOwnText() {
        var schema = build(SERVICE_TYPES + FILM_THING + """
            input ThingInput { occupant: ID! @nodeId(typeName: "FilmThing") }
            type Mutation { pickFilmForThing(in: ThingInput!): Film %s }
            """.formatted(service("pickFilmForThing")));

        assertThat(schema.tenantBindingOf("Mutation", "pickFilmForThing")).isNull();
        assertRejects(schema, "Mutation.pickFilmForThing",
            "no single decode to route the service call on");
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("Mutation.pickFilmForThing")
                && r.detail().contains("no argument or input field maps to tenant column"));
        // The decline's fix lands in a slot, which hands Film a tenant: the decline is the one
        // error, and the returned row's child inherits rather than repeating it.
        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.coordinate()).isEqualTo("Mutation.pickFilmForThing"));
        assertInherited(schema, "Film", "inventories");
    }

    @Test
    void aPolymorphicRecordIdBesideADivingSiblingStopsTheFieldDivining() {
        // Left unchecked, the union's id could name another tenant's record while the sibling
        // routes the call, so the service would act on it in the wrong database.
        var schema = build(SERVICE_TYPES + FILM_THING + """
            input FilmAndThingInput {
                film: ID! @nodeId(typeName: "Film")
                thing: ID! @nodeId(typeName: "FilmThing")
            }
            type Mutation { rateFilmAndThing(in: FilmAndThingInput!): RateFilmsPayload %s }
            """.formatted(service("rateFilmAndThing")));

        assertThat(schema.tenantBindingOf("Mutation", "rateFilmAndThing"))
            .isNotInstanceOf(TenantBinding.ArgumentBound.class);
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void aPolymorphicRecordIdOverGlobalMembersNamesNoTenantAndLeavesTheSiblingDivining() {
        // Neither customer nor staff carries the tenant column, so the id names no tenant at all.
        var schema = build(SERVICE_TYPES + """
            type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
            }
            type Staff implements Node @table(name: "staff") @node(keyColumns: ["staff_id"]) {
                id: ID! @nodeId
            }
            union AddressOccupant = Customer | Staff
            input FilmAndThingInput {
                film: ID! @nodeId(typeName: "Film")
                thing: ID! @nodeId(typeName: "AddressOccupant")
            }
            type Mutation { rateFilmAndThing(in: FilmAndThingInput!): RateFilmsPayload %s }
            """.formatted(service("rateFilmAndThing")));

        assertThat(argumentBound(schema, "Mutation", "rateFilmAndThing").bindings())
            .extracting(TenantBinding.BoundSlot::slotName)
            .containsExactly("in.film");
        assertThat(schema.tenantBindingOf("RateFilmsPayload", "films"))
            .isInstanceOf(TenantBinding.Inherited.class);
    }

    /** A tenant-bound Film root the child services below hang off. */
    private static String boundFilmWith(String childFields) {
        return """
            type Film @table(name: "film") {
                title: String
                %s
            }
            type Language @table(name: "language") { name: String }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """.formatted(childFields);
    }

    @Test
    void aChildServiceTakingADslContextInheritsTheParentsTenant() {
        var schema = build(boundFilmWith("rating: String " + service("ratingWithDsl")));

        assertThat(schema.tenantBindingOf("Film", "rating"))
            .isEqualTo(new TenantBinding.Inherited("Film"));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aChildServiceOnAHolderConstructedWithTheDslContextInheritsToo() {
        var schema = build(boundFilmWith(
            "rating: String @service(service: {className: \"" + TENANT_HOLDER + "\", method: \"rating\"})"));

        assertThat(schema.tenantBindingOf("Film", "rating"))
            .isEqualTo(new TenantBinding.Inherited("Film"));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aChildServiceBindingNoConnectionKeepsTheDefaultSource() {
        var schema = build(boundFilmWith("rating: String " + service("ratingPlain")));

        assertThat(schema.tenantBindingOf("Film", "rating"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
    }

    @Test
    void aRootServiceReturningAGlobalTableKeepsTheDefaultSourceAndEstablishesNoContext() {
        // Graphitron re-reads the returned language rows on the service's connection, and global
        // tables live on the default source, so the tenant the FilmRecord names routes nothing.
        var schema = build(SERVICE_TYPES + """
            type Language @table(name: "language") {
                name: String
                films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Mutation { languageOfFilm(in: RateFilmInput!): Language %s }
            """.formatted(service("languageOfFilm")));

        assertThat(schema.tenantBindingOf("Mutation", "languageOfFilm"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertRejects(schema, "Language.films", "no ancestor established a tenant context");
    }

    @Test
    void aChildServiceReturningAGlobalTableStaysOnTheDefaultSource() {
        var schema = build(boundFilmWith("""
            language: Language %s
            sessionLanguage: Language @service(service: {className: "%s",
                method: "languageForSession", argMapping: "identity: $session"})
            """.formatted(service("languageWithDsl"), TENANT_SERVICE)));

        assertThat(schema.tenantBindingOf("Film", "language"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindingOf("Film", "sessionLanguage"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== @tenant: a root service's plain scalar the author marks as the tenant =====

    private static final String RATE_BY_FILM_ID = """
        input RateByFilmIdInput { filmId: Int! @tenant  rating: Int }
        """;

    private static void assertRawSlot(TenantBinding.BoundSlot slot, String name,
                                      TenantBinding.SlotRead read) {
        assertThat(slot.slotName()).isEqualTo(name);
        assertThat(slot.read()).isEqualTo(read);
        assertThat(slot.projection()).isEqualTo(TenantBinding.SlotProjection.Raw.INSTANCE);
    }

    /**
     * The marker sweep's rejection at {@code definition}, naming the use site and carrying
     * {@code fragment}.
     */
    private static void assertTenantMarkerRejection(GraphitronSchema schema, String definition,
                                                    String use, String fragment) {
        assertThat(schema.tenantBindings().rejections())
            .filteredOn(e -> e.coordinate().equals(definition))
            .anySatisfy(e -> assertThat(e.rejection())
                .isInstanceOfSatisfying(Rejection.InvalidSchema.DirectiveConflict.class, conflict -> {
                    assertThat(conflict.directives()).containsExactly("tenant");
                    assertThat(conflict.message())
                        .contains("'" + definition + "' declares @tenant, but at '" + use + "'")
                        .contains(fragment);
                }));
    }

    @Test
    void anUnmarkedScalarMemberHandedAConnectionRejectsAtTheRoot() {
        var schema = build(SERVICE_TYPES + """
            input RateByFilmIdInput { filmId: Int!  rating: Int }
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        assertThat(schema.tenantBindingOf("Mutation", "rateByFilmId")).isNull();
        var rejection = assertUnroutedWithoutDeclines(schema, "Mutation.rateByFilmId");
        assertThat(rejection.message())
            .contains("mark the scalar argument or input field that holds the tenant with @tenant");
        // A plain Int is no tenant evidence, so @globalData would be accepted on the root, and
        // the payload child keeps its own error.
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void aTenantMarkedScalarMemberDivinesRawAndItsChildrenInherit() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        var bound = argumentBound(schema, "Mutation", "rateByFilmId");
        assertThat(bound.bindings()).hasSize(1);
        assertRawSlot(bound.primary(), "in.filmId",
            new TenantBinding.SlotRead.NestedInput("in", List.of("filmId")));
        assertThat(schema.tenantBindingOf("RateFilmsPayload", "films"))
            .isEqualTo(new TenantBinding.Inherited("RateFilmsPayload"));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aTenantMarkedTopLevelArgumentReadsTheArgument() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateByFilmIdArgument(filmId: Int! @tenant): RateFilmsPayload %s }
            """.formatted(service("rateByFilmIdArgument")));

        assertRawSlot(argumentBound(schema, "Mutation", "rateByFilmIdArgument").primary(), "filmId",
            TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aTenantMarkedJavaRecordMemberDivinesLikeTheBeans() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmIdRecord(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmIdRecord")));

        assertRawSlot(argumentBound(schema, "Mutation", "rateByFilmIdRecord").primary(), "in.filmId",
            new TenantBinding.SlotRead.NestedInput("in", List.of("filmId")));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aTenantMarkedMemberOfAGroupingInputReadsEverySegment() {
        var schema = build(SERVICE_TYPES + """
            input FilmWhere { filmId: Int! @tenant }
            input RateGroupedInput { where: FilmWhere!  rating: Int }
            type Mutation { rateByFilmId(in: RateGroupedInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        assertRawSlot(argumentBound(schema, "Mutation", "rateByFilmId").primary(), "in.where.filmId",
            new TenantBinding.SlotRead.NestedInput("in", List.of("where", "filmId")));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aBatchOfMarkedBeansMintsOneSlotReadOffEveryElement() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmIds(in: [RateByFilmIdInput!]!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmIds")));

        var bound = argumentBound(schema, "Mutation", "rateByFilmIds");
        assertThat(bound.bindings()).hasSize(1);
        assertRawSlot(bound.primary(), "in.filmId",
            new TenantBinding.SlotRead.NestedInput("in", List.of("filmId")));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aMarkedScalarBesideANodeIdMemberMintsBothSlotsInDeclarationOrder() {
        var schema = build(SERVICE_TYPES + """
            input RateByFilmIdAndFilmInput {
                filmId: Int! @tenant
                film: ID! @nodeId(typeName: "Film")
            }
            type Mutation { rateByFilmIdAndFilm(in: RateByFilmIdAndFilmInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmIdAndFilm")));

        var bindings = argumentBound(schema, "Mutation", "rateByFilmIdAndFilm").bindings();
        assertThat(bindings).hasSize(2);
        assertRawSlot(bindings.get(0), "in.filmId",
            new TenantBinding.SlotRead.NestedInput("in", List.of("filmId")));
        assertDecodedSlot(bindings.get(1), "in.film",
            new TenantBinding.SlotRead.NestedInput("in", List.of("film")), 0);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void globalDataOverATenantMarkedArgumentRejects() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(globalService("rateByFilmId")));

        assertGlobalDataRejection(schema, "Mutation.rateByFilmId",
            "the arguments name a tenant (in.filmId), which contradicts @globalData", "globalData");
    }

    @Test
    void aMarkedValueNotOfTheTenantKeyTypeDeclinesAtTheFieldAndTheDefinition() {
        for (String scalar : List.of("String!", "ID!")) {
            var schema = build(SERVICE_TYPES + """
                input RateByFilmCodeInput { filmId: %s @tenant }
                type Mutation { rateByFilmCode(in: RateByFilmCodeInput!): RateFilmsPayload %s }
                """.formatted(scalar, service("rateByFilmCode")));

            assertThat(schema.tenantBindingOf("Mutation", "rateByFilmCode")).as(scalar).isNull();
            var unrouted = schema.tenantBindings().rejections().stream()
                .filter(e -> e.coordinate().equals("Mutation.rateByFilmCode"))
                .map(e -> e.rejection())
                .toList();
            assertThat(unrouted).as(scalar).singleElement()
                .isInstanceOfSatisfying(Rejection.AuthorError.UnroutedServiceCall.class, r ->
                    assertThat(r.declines()).singleElement().asString()
                        .contains("'RateByFilmCodeInput.filmId' is marked @tenant")
                        .contains("arrives as java.lang.String")
                        .contains("not the tenant key type java.lang.Integer"));
            assertTenantMarkerRejection(schema, "RateByFilmCodeInput.filmId",
                "Mutation.rateByFilmCode", "arrives as java.lang.String");
        }
    }

    @Test
    void aMarkerInASingleTenantBuildRejects() {
        var schema = TestSchemaHelper.buildSchema(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.coordinate().equals("RateByFilmIdInput.filmId")
                && e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().equals(List.of("tenant"))
                && conflict.message().contains("no <tenantColumn>"));
    }

    @Test
    void aMarkerOnAQueryFieldsArgumentRejectsNamingTheUseSite() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { films(filmId: Int! @field(name: "film_id") @tenant): [Film!]! }
            """);

        assertThat(schema.tenantBindingOf("Query", "films"))
            .isInstanceOf(TenantBinding.ArgumentBound.class);
        assertTenantMarkerRejection(schema, "Query.films(filmId:)", "Query.films",
            "is not a root @service: a query or @mutation field binds the tenant through"
                + " @field(name: \"film_id\")");
    }

    @Test
    void aMarkerOnAChildServicesArgumentRejects() {
        var schema = build(boundFilmWith(
            "rating(scale: Int @tenant): String " + service("ratingWithDsl")));

        assertTenantMarkerRejection(schema, "Film.rating(scale:)", "Film.rating",
            "a child service runs on its parent's tenant");
    }

    @Test
    void aMarkerOnAnInputObjectTypedFieldRejects() {
        var schema = build(SERVICE_TYPES + """
            input FilmWhere { filmId: Int! }
            input RateGroupedInput { where: FilmWhere! @tenant  rating: Int }
            type Mutation { rateByFilmId(in: RateGroupedInput!): RateFilmsPayload %s }
            """.formatted(service("rateByFilmId")));

        assertTenantMarkerRejection(schema, "RateGroupedInput.where", "Mutation.rateByFilmId",
            "it is not a scalar");
    }

    @Test
    void aMarkerBesideNodeIdRejects() {
        var schema = build(SERVICE_TYPES + """
            input MarkedRateFilmInput { film: ID! @nodeId(typeName: "Film") @tenant }
            type Mutation { rateFilmWithDsl(in: MarkedRateFilmInput!): RateFilmsPayload %s }
            """.formatted(service("rateFilmWithDsl")));

        assertThat(schema.tenantBindingOf("Mutation", "rateFilmWithDsl"))
            .isInstanceOf(TenantBinding.ArgumentBound.class);
        assertTenantMarkerRejection(schema, "MarkedRateFilmInput.film", "Mutation.rateFilmWithDsl",
            "it carries @nodeId");
    }

    @Test
    void aMarkerOnAJooqRecordMemberRejects() {
        var schema = build(SERVICE_TYPES + """
            input ModifyFilmInput {
                filmId: Int! @field(name: "film_id") @tenant
                title: String @field(name: "title")
            }
            type Mutation { modifyFilms(in: [ModifyFilmInput!]!): RateFilmsPayload %s }
            """.formatted(service("modifyFilms")));

        assertThat(schema.tenantBindingOf("Mutation", "modifyFilms"))
            .isInstanceOf(TenantBinding.ArgumentBound.class);
        assertTenantMarkerRejection(schema, "ModifyFilmInput.filmId", "Mutation.modifyFilms",
            "it is bound into a jOOQ record parameter");
    }

    @Test
    void aMarkerOnAServiceReturningAGlobalTableRejects() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Language @table(name: "language") { name: String }
            type Mutation { languageByFilmId(in: RateByFilmIdInput!): Language %s }
            """.formatted(service("languageByFilmId")));

        assertThat(schema.tenantBindingOf("Mutation", "languageByFilmId"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertTenantMarkerRejection(schema, "RateByFilmIdInput.filmId", "Mutation.languageByFilmId",
            "the service returns global @table type 'Language'");
    }

    @Test
    void oneInputTypeRoutesItsRootServiceAndRejectsAtAQueryUseSite() {
        var schema = build(SERVICE_TYPES + RATE_BY_FILM_ID + """
            type Mutation { rateByFilmId(in: RateByFilmIdInput!): RateFilmsPayload %s }
            extend type Query { ratedFilms(in: RateByFilmIdInput): [Film!]! }
            """.formatted(service("rateByFilmId")));

        assertThat(schema.tenantBindingOf("Mutation", "rateByFilmId"))
            .isInstanceOf(TenantBinding.ArgumentBound.class);
        assertTenantMarkerRejection(schema, "RateByFilmIdInput.filmId", "Query.ratedFilms",
            "is not a root @service");
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.coordinate().equals("RateByFilmIdInput.filmId")
                && e.rejection().message().contains("at 'Mutation.rateByFilmId'"));
    }

    /**
     * A payload's re-projection of a global table is its own coordinate and a global read, whatever
     * connection its producer ran on: here a tenant-routed service.
     */
    @Test
    void aPayloadsGlobalReprojectionUnderATenantRoutedServiceIsAGlobalRead() {
        var schema = build(SERVICE_TYPES + """
            type Language @table(name: "language") { name: String }
            type RatedFilmsLanguagePayload { films: [Film!]! language: Language }
            type Mutation { rateFilmsWithLanguage(ratings: [RateFilmInput!]!): RatedFilmsLanguagePayload %s }
            """.formatted(service("rateFilmsWithLanguage")));

        argumentBound(schema, "Mutation", "rateFilmsWithLanguage");
        assertThat(schema.tenantBindingOf("RatedFilmsLanguagePayload", "language"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== @globalData: a root service's statement that its data is global =====

    private static String globalService(String method) {
        return "@globalData " + service(method);
    }

    /**
     * The marker rejected with {@code fragment}, and with no other text: no other rung's, no
     * cross-scope or no-binding rejection at the coordinate, and no sweep rejection beside it.
     */
    private static void assertGlobalDataRejection(GraphitronSchema schema, String coordinate,
                                                  String fragment, String... directives) {
        assertThat(schema.tenantBindingOf(coordinate.split("\\.")[0], coordinate.split("\\.")[1]))
            .isNull();
        var atField = schema.tenantBindings().rejections().stream()
            .filter(e -> e.coordinate().equals(coordinate))
            .toList();
        assertThat(atField).hasSize(1);
        assertThat(atField.get(0).rejection())
            .isInstanceOfSatisfying(Rejection.InvalidSchema.DirectiveConflict.class, conflict -> {
                assertThat(conflict.directives()).containsExactlyInAnyOrder(directives);
                assertThat(conflict.message())
                    .contains("'" + coordinate + "'")
                    .contains(fragment)
                    .doesNotContain("never reached");
            });
    }

    @Test
    void aMarkedSessionServiceRunsOnTheDefaultSource() {
        var schema = build("""
            type Query {
                sessionPrincipal: String @globalData @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """);

        assertThat(schema.tenantBindingOf("Query", "sessionPrincipal"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    /**
     * The same re-projection below a default-source producer is still a global read: the
     * read-after-write consequence the user documentation states, pinned so a change is deliberate.
     */
    @Test
    void aPayloadsGlobalReprojectionUnderAGlobalDataServiceIsAGlobalRead() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type GlobalLanguagePayload { servedBy: String language: Language }
            type Query { globalLanguage: GlobalLanguagePayload %s }
            """.formatted(globalService("globalLanguage")));

        assertThat(schema.tenantBindingOf("Query", "globalLanguage"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindingOf("GlobalLanguagePayload", "language"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aMarkedDslContextServiceRunsOnTheDefaultSource() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { refreshLanguages: Boolean %s }
            """.formatted(globalService("refreshLanguages")));

        assertThat(schema.tenantBindingOf("Mutation", "refreshLanguages"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.coordinate().equals("Mutation.refreshLanguages"));
    }

    @Test
    void globalDataOnAChildFieldRejects() {
        var schema = build(boundFilmWith("rating: String " + globalService("ratingWithDsl")));

        assertGlobalDataRejection(schema, "Film.rating", "supported on root fields only",
            "globalData");
    }

    @Test
    void globalDataOnANonServiceRootRejects() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! @globalData }
            """);

        assertGlobalDataRejection(schema, "Query.languages",
            "only a @service field's SQL is opaque to the build", "globalData");
    }

    @Test
    void globalDataOnACrossScopeRootGetsItsRungsTextAlone() {
        // A cross-scope reach always holds a tenant-scoped table; the marker's ladder answers it
        // ahead of the cross-scope rejection, so neither that text nor the sweep's appears.
        var schema = build("""
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Language @table(name: "language") { name: String }
            union Media = Film | Language
            type Query { media: [Media!]! @globalData }
            """);

        assertGlobalDataRejection(schema, "Query.media",
            "only a @service field's SQL is opaque to the build", "globalData");
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.rejection().message().contains("cross-scope"));
    }

    @Test
    void globalDataOnAServiceHandedNoConnectionRejects() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateByRawId(film: ID!): RateFilmsPayload %s }
            """.formatted(globalService("rateByRawId")));

        assertGlobalDataRejection(schema, "Mutation.rateByRawId",
            "the service is handed no connection, so there is nothing to route", "globalData");
    }

    @Test
    void globalDataOnATenantScopedTableReturnRejects() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { pickFilmOnConnection(film: ID!): Film %s }
            """.formatted(globalService("pickFilmOnConnection")));

        assertGlobalDataRejection(schema, "Mutation.pickFilmOnConnection",
            "returns tenant-scoped @table type 'Film'", "globalData");
    }

    @Test
    void globalDataOnAGlobalTableReturnRejects() {
        var schema = build(SERVICE_TYPES + """
            type Language @table(name: "language") { name: String }
            type Mutation { languageOnConnection: Language %s }
            """.formatted(globalService("languageOnConnection")));

        assertGlobalDataRejection(schema, "Mutation.languageOnConnection",
            "returns global @table type 'Language', which already runs on the default source",
            "globalData");
    }

    @Test
    void globalDataOverArgumentsNamingATenantRejects() {
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateFilmWithDsl(in: RateFilmInput!): RateFilmsPayload %s }
            """.formatted(globalService("rateFilmWithDsl")));

        assertGlobalDataRejection(schema, "Mutation.rateFilmWithDsl",
            "the arguments name a tenant (in.film), which contradicts @globalData", "globalData");
    }

    @Test
    void globalDataOverADeclinedTenantShapeRejects() {
        var schema = build(SERVICE_TYPES + FILM_THING + """
            input ThingInput { occupant: ID! @nodeId(typeName: "FilmThing") }
            type Mutation { pickForThing(in: ThingInput!): String %s }
            """.formatted(globalService("pickForThing")));

        assertGlobalDataRejection(schema, "Mutation.pickForThing",
            "through a shape that cannot route the call), which contradicts @globalData",
            "globalData");
    }

    @Test
    void globalDataOverTenantEvidenceRejects() {
        var schema = build(SERVICE_TYPES + """
            input RetitleFilmInput { title: String @field(name: "title") }
            type Mutation { retitleFilm(in: RetitleFilmInput!): String %s }
            """.formatted(globalService("retitleFilm")));

        assertGlobalDataRejection(schema, "Mutation.retitleFilm",
            "the arguments carry tenant-scoped values (FilmRecord at in)", "globalData");
    }

    @Test
    void globalDataBesideTenantFanOutRejectsOnce() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @globalData @tenantFanOut }
            """);

        assertGlobalDataRejection(schema, "Query.films", "combines @globalData with @tenantFanOut",
            "globalData", "tenantFanOut");
    }

    @Test
    void globalDataOnAnInterfaceFieldRejectsThroughTheSweep() {
        var schema = build("""
            interface Subject @table(name: "jti_subject") @discriminate(on: "subject_kind") {
                subjectId: Int! @field(name: "jti_subject_id")
                subjectKind: String! @field(name: "subject_kind")
                label: String @globalData
            }
            type AppAccount implements Subject @table(name: "jti_subject") @discriminator(value: "APP") {
                subjectId: Int! @field(name: "jti_subject_id")
                subjectKind: String! @field(name: "subject_kind")
                label: String
            }
            type Query { subjects: [Subject!]! }
            """);

        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().equals(List.of("globalData"))
                && conflict.message().contains("'Subject.label'")
                && conflict.message().contains("never reached the tenant-binding classification"));
    }

    @Test
    void globalDataOnAFieldOfANestingTypeRejectsThroughTheSweep() {
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                meta: FilmMeta
            }
            type FilmMeta { title: String @globalData }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().equals(List.of("globalData"))
                && conflict.message().contains("'FilmMeta.title'")
                && conflict.message().contains("never reached the tenant-binding classification"));
    }

    @Test
    void globalDataInASingleTenantBuildRejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                sessionPrincipal: String @globalData @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """);

        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().equals(List.of("globalData"))
                && conflict.message().contains("'Query.sessionPrincipal'")
                && conflict.message().contains("no <tenantColumn>"));
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

    // ===== A reference whose foreign key lands on the tenant column routes on the parent row =====

    /**
     * {@code film_endorsement} is global and its foreign key {@code endorsed_film} lands on
     * {@code film.film_id}, the tenant column: each endorsement row names the tenant holding its
     * film. {@code %s} is the directive text on {@code FilmEndorsement.film}.
     */
    private static final String ENDORSEMENTS = """
        type FilmEndorsement @table(name: "film_endorsement") {
            note: String
            film: Film%s
        }
        type Film @table(name: "film") {
            title: String
            inventories: [Inventory!]! @splitQuery%s
        }
        type Inventory @table(name: "inventory") { inventoryId: Int }
        type FilmCategory @table(name: "film_category") { categoryId: Int }
        type Query { endorsements: [FilmEndorsement!]! }
        """;

    private static GraphitronSchema endorsements(String filmDirectives, String filmFields) {
        return build(ENDORSEMENTS.formatted(filmDirectives, filmFields));
    }

    @Test
    void aBatchedReferenceLandingOnTheTenantColumnRoutesOnTheParentRow() {
        var schema = endorsements(" @splitQuery", "");

        var binding = schema.tenantBindingOf("FilmEndorsement", "film");
        assertThat(binding).isInstanceOf(TenantBinding.ParentRowBound.class);
        var parentRow = (TenantBinding.ParentRowBound) binding;
        assertThat(parentRow.parentTable().tableName()).isEqualToIgnoringCase("film_endorsement");
        assertThat(parentRow.slot().sourceSide().sqlName()).isEqualToIgnoringCase("endorsed_film");
        assertThat(parentRow.slot().targetSide().sqlName()).isEqualToIgnoringCase("film_id");

        // The mint invariant the fetcher emission reads: the arm's slot is the field's own
        // first-hop slot, and its source side is a batch key column.
        var field = (no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField) schema.fields()
            .get(graphql.schema.FieldCoordinates.coordinates("FilmEndorsement", "film"));
        var correlation = (no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots)
            field.parentCorrelation();
        assertThat(correlation.slots().slots()).anyMatch(slot -> slot == parentRow.slot());
        assertThat(field.sourceKey().columns()).contains(parentRow.slot().sourceSide());

        assertInherited(schema, "Film", "inventories");
        assertThat(schema.tenantBindingOf("Query", "endorsements"))
            .isEqualTo(TenantBinding.Untenanted.GlobalRead.INSTANCE);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aListOrConnectionWhoseFirstHopLandsOnTheTenantColumnRoutesOnTheParentRow() {
        var schema = build("""
            type FilmEndorsement @table(name: "film_endorsement") {
                note: String
                inventories: [Inventory!]! @splitQuery @reference(path: [
                    {key: "film_endorsement_endorsed_film_fkey"}, {key: "inventory_film_id_fkey"}])
                inventoriesConnection: [Inventory!]! @asConnection @splitQuery @reference(path: [
                    {key: "film_endorsement_endorsed_film_fkey"}, {key: "inventory_film_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query { endorsements: [FilmEndorsement!]! }
            """);

        for (var field : List.of("inventories", "inventoriesConnection")) {
            assertThat(schema.tenantBindingOf("FilmEndorsement", field)).as(field)
                .isInstanceOfSatisfying(TenantBinding.ParentRowBound.class, parentRow ->
                    assertThat(parentRow.slot().sourceSide().sqlName()).isEqualToIgnoringCase("endorsed_film"));
        }
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void anInlineReferenceLandingOnTheTenantColumnNamesSplitQueryInItsRejection() {
        var schema = endorsements("", "");

        assertThat(schema.tenantBindingOf("FilmEndorsement", "film")).isNull();
        assertRejects(schema, "FilmEndorsement.film", "Mark it @splitQuery");
    }

    @Test
    void theParentRowDecidesEvenWhereEveryPathIsBound() {
        var schema = build("""
            type FilmEndorsement @table(name: "film_endorsement") {
                note: String
                film: Film @splitQuery
            }
            type Film @table(name: "film") {
                title: String
                endorsements: [FilmEndorsement!]! @splitQuery
            }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertThat(schema.tenantBindingOf("FilmEndorsement", "film"))
            .isInstanceOf(TenantBinding.ParentRowBound.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aConditionJoinedReferenceKeepsNoTenantBinding() {
        var schema = endorsements(" @splitQuery @reference(path: [{condition: {className: "
            + "\"no.sikt.graphitron.rewrite.TestConditionStub\", method: \"join\"}}])", "");

        assertThat(schema.tenantBindingOf("FilmEndorsement", "film")).isNull();
        assertRejects(schema, "FilmEndorsement.film", "no ancestor established a tenant context");
    }

    @Test
    void aFanOutBelowTheParentRowEdgeSitsUnderATenantBoundAncestor() {
        var schema = endorsements(" @splitQuery", "\n    categories: [FilmCategory!]! @tenantFanOut");

        assertFanOutRejects(schema, "Film.categories", "sits under a tenant-bound ancestor");
    }

    // ===== A field of a nesting type routes as it would on the enclosing @table type =====

    /**
     * {@code FilmStock} has no {@code @table}: it is reached through {@code Film.stock} and shares
     * the film row. {@code %s} is the extra SDL on {@code FilmStock}'s fields, {@code %s} the
     * remaining types.
     */
    private static final String FILM_STOCK = """
        type Film @table(name: "film") {
            title: String
            stock: FilmStock
        }
        type FilmStock {
            filmId: Int @field(name: "film_id")%s
        }
        type Inventory @table(name: "inventory") { inventoryId: Int }
        type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
        %s""";

    private static GraphitronSchema filmStock(String stockFields, String extra) {
        return build(FILM_STOCK.formatted(stockFields, extra));
    }

    @Test
    void aNestedSplitQueryFieldUnderATenantScopedParentInherits() {
        var schema = filmStock("""

                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
                conditioned: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey",
                    condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
                inventoriesConnection: [Inventory!]! @asConnection @splitQuery
                    @reference(path: [{key: "inventory_film_id_fkey"}]) @defaultOrder(primaryKey: true)""", "");

        assertInherited(schema, "FilmStock", "inventories");
        assertInherited(schema, "FilmStock", "conditioned");
        assertInherited(schema, "FilmStock", "inventoriesConnection");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aNestedSplitQueryFieldDeclaredInAnExtensionInherits() {
        var schema = filmStock("", """
            extend type FilmStock {
                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            """);

        assertInherited(schema, "FilmStock", "inventories");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aSplitQueryFieldTwoNestingLevelsDownInherits() {
        var schema = filmStock("\n    detail: FilmStockDetail", """
            type FilmStockDetail {
                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            """);

        assertInherited(schema, "FilmStockDetail", "inventories");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aNestedChildCarryingTenantFanOutKeepsItsUnreachedMarkerRejection() {
        var schema = filmStock("""

                inventories: [Inventory!]! @tenantFanOut @splitQuery
                    @reference(path: [{key: "inventory_film_id_fkey"}])""", "");

        assertThat(schema.tenantBindingOf("FilmStock", "inventories")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.message().contains("'FilmStock.inventories'")
                && conflict.message().contains("never reached the fan-out classification"));
    }

    /**
     * {@code EndorsementAbout} has no {@code @table} and shares the global endorsement row, whose
     * foreign key {@code endorsed_film} lands on the tenant column. {@code %s} is
     * {@code EndorsementAbout}'s fields.
     */
    private static final String ENDORSEMENT_ABOUT = """
        type FilmEndorsement @table(name: "film_endorsement") {
            note: String
            about: EndorsementAbout
        }
        type EndorsementAbout {
            note: String%s
        }
        type Film @table(name: "film") {
            title: String
            inventories: [Inventory!]! @splitQuery
        }
        type Inventory @table(name: "inventory") { inventoryId: Int }
        type Query { endorsements: [FilmEndorsement!]! }
        """;

    @Test
    void aNestedSplitQueryFieldUnderAGlobalParentRoutesOnTheParentRow() {
        var schema = build(ENDORSEMENT_ABOUT.formatted("\n    film: Film @splitQuery"));

        assertThat(schema.tenantBindingOf("EndorsementAbout", "film"))
            .isInstanceOfSatisfying(TenantBinding.ParentRowBound.class, parentRow -> {
                assertThat(parentRow.parentTable().tableName()).isEqualToIgnoringCase("film_endorsement");
                assertThat(parentRow.slot().sourceSide().sqlName()).isEqualToIgnoringCase("endorsed_film");
            });
        // The nested edge establishes context for its own subtree exactly as a flat one does.
        assertInherited(schema, "Film", "inventories");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aNestedSplitQueryFieldUnderAGlobalParentWithNoTenantKeyRejects() {
        var schema = build(ENDORSEMENT_ABOUT.formatted("\n    film: Film @splitQuery @reference(path:"
            + " [{condition: {className: \"no.sikt.graphitron.rewrite.TestConditionStub\","
            + " method: \"join\"}}])"));

        assertThat(schema.tenantBindingOf("EndorsementAbout", "film")).isNull();
        assertRejects(schema, "EndorsementAbout.film", "no ancestor established a tenant context");
        // The frontier field rejects alone: every fix it is offered hands Film a tenant.
        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.coordinate()).isEqualTo("EndorsementAbout.film"));
        assertInherited(schema, "Film", "inventories");
    }

    @Test
    void aNestedInlineReferenceUnderAGlobalParentNamesSplitQueryInItsRejection() {
        var schema = build(ENDORSEMENT_ABOUT.formatted("\n    film: Film"));

        assertThat(schema.tenantBindingOf("EndorsementAbout", "film")).isNull();
        assertRejects(schema, "EndorsementAbout.film", "Mark it @splitQuery");
    }

    @Test
    void aNestingTypeSharedByTwoParentsReportsEachDistinctRejectionOnce() {
        var schema = build("""
            type FilmEndorsement @table(name: "film_endorsement") {
                note: String
                about: FilmMention
            }
            type FilmBookmark @table(name: "film_bookmark") {
                about: FilmMention
            }
            type FilmMention {
                film: Film
            }
            type Film @table(name: "film") { title: String }
            type Query {
                endorsements: [FilmEndorsement!]!
                bookmarks: [FilmBookmark!]!
            }
            """);

        assertThat(schema.tenantBindingOf("FilmMention", "film")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .filteredOn(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding r
                && r.coordinate().equals("FilmMention.film"))
            .hasSize(1);
        assertRejects(schema, "FilmMention.film", "Mark it @splitQuery");
    }

    // ===== A parent that supplies a tenant outranks the child's own argument =====

    @Test
    void aChildArgumentNamingTheTenantColumnFiltersInsideTheInheritedTenant() {
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            input FilmActorFilter { actor: ID @nodeId(typeName: "FilmActor") }
            type Film @table(name: "film") {
                title: String
                byFilmId(filmId: Int @field(name: "film_id")): [Inventory!]! @splitQuery
                byFilter(filter: FilmActorFilter): [FilmActor!]! @splitQuery
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertInherited(schema, "Film", "byFilter");
        assertInherited(schema, "Film", "byFilmId");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aChildArgumentNamingTheTenantColumnDoesNotOutrankTheParentRow() {
        var bare = endorsements(" @splitQuery", "");
        var withArgument = build(ENDORSEMENTS.replace("film: Film%s",
            "film(filmId: Int @field(name: \"film_id\")): Film%s").formatted(" @splitQuery", ""));

        var expected = (TenantBinding.ParentRowBound) bare.tenantBindingOf("FilmEndorsement", "film");
        assertThat(withArgument.tenantBindingOf("FilmEndorsement", "film"))
            .isInstanceOfSatisfying(TenantBinding.ParentRowBound.class, parentRow -> {
                assertThat(parentRow.parentTable().tableName())
                    .isEqualToIgnoringCase(expected.parentTable().tableName());
                assertThat(parentRow.slot().sourceSide().sqlName())
                    .isEqualToIgnoringCase(expected.slot().sourceSide().sqlName());
                assertThat(parentRow.slot().targetSide().sqlName())
                    .isEqualToIgnoringCase(expected.slot().targetSide().sqlName());
            });
        assertThat(withArgument.tenantBindings().rejections()).isEmpty();
    }

    /**
     * {@code Language} is global and its {@code films} key lands on {@code film.language_id}, not
     * the tenant column, so only a handed-down tenant can outrank the argument. {@code %s} is
     * extra root fields.
     */
    private static final String LANGUAGE_FILMS = """
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Language @table(name: "language") {
            name: String
            films(filmId: Int @field(name: "film_id")): [Film!]! @splitQuery
                @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Query {
            films(filmId: Int! @field(name: "film_id")): [Film!]!%s
        }
        """;

    @Test
    void aGlobalParentReachedOnlyUnderATenantHandsItDownToAnArgumentBearingChild() {
        var schema = build(LANGUAGE_FILMS.formatted(""));

        assertInherited(schema, "Language", "films");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aGlobalParentAlsoReachedWithoutATenantLeavesARequiredArgumentRouting() {
        var schema = build(LANGUAGE_FILMS.replace("films(filmId: Int @", "films(filmId: Int! @")
            .formatted("\n    languages: [Language!]!"));

        assertThat(schema.tenantBindingOf("Language", "films"))
            .isInstanceOfSatisfying(TenantBinding.ArgumentBound.class,
                bound -> assertThat(bound.primary().slotName()).isEqualTo("filmId"));
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void anOptionalSlotOnAChildWhoseParentSuppliesNoTenantRejects() {
        // Nothing hands Language a tenant on the languages path, so only the argument could
        // route Language.films, and a request may leave it out.
        var schema = build(LANGUAGE_FILMS.formatted("\n    languages: [Language!]!"));

        assertThat(schema.tenantBindingOf("Language", "films")).isNull();
        assertRejects(schema, "Language.films",
            "'Language.films' takes its tenant only from optional values (filmId)");
        // The fan-out ladder would turn the marker away here (Language is also reached below the
        // bound films root), so the rejection offers only the non-null fix.
        assertRejects(schema, "Language.films", "Make one of them non-null.");
    }

    @Test
    void aNestedChildArgumentNamingTheTenantColumnFiltersInsideTheInheritedTenant() {
        var schema = filmStock("""

                byFilmId(filmId: Int @field(name: "film_id")): [Inventory!]! @splitQuery
                    @reference(path: [{key: "inventory_film_id_fkey"}])""", "");

        assertInherited(schema, "FilmStock", "byFilmId");
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    // ===== A rejected optional-only root rejects alone =====

    @Test
    void aRejectedOptionalOnlyRootLeavesItsSubtreeInheritingFromTheOtherRoots() {
        // Both repairs the rejection offers (non-null, or @tenantFanOut) make the root establish
        // a tenant, so its subtree is judged as if it already did and only the root itself rejects.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @splitQuery
                inventoriesBy(filmId: Int @field(name: "film_id")): [Inventory!]! @splitQuery
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
                filmsMaybe(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertRejectsOptionalOnly(schema, "Query.filmsMaybe", "filmId", true);
        assertInherited(schema, "Film", "inventories");
        assertInherited(schema, "Film", "inventoriesBy");
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
    }

    @Test
    void anOptionalOnlyRootThatNeedsNoTenantStillHandsDownNothing() {
        // The same service and input as wrapperServiceWithANodeIdRecordMemberDivinesAndItsChildrenInherit,
        // with the input made nullable: the decoded slot is optional, so the root reads
        // conditional. It is handed no connection and its own reach is empty, so it needs no
        // tenant and runs on the default source; its subtree really has none, and the payload's
        // tenant-scoped child rejects on its own.
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateFilm(in: RateFilmInput): RateFilmsPayload %s }
            """.formatted(service("rateFilm")));

        assertThat(schema.tenantBindingOf("Mutation", "rateFilm"))
            .isEqualTo(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        assertThat(schema.tenantBindings().rejections())
            .noneMatch(e -> e.coordinate().equals("Mutation.rateFilm"));
        assertRejects(schema, "RateFilmsPayload.films", "no ancestor established a tenant context");
    }

    @Test
    void anOptionalOnlyConnectionServiceRejectsAloneAndItsSubtreeInherits() {
        // The same nullable input, but the service is handed a DSLContext, so it needs a tenant:
        // the root rejects with the optional-only detail, and its subtree is judged as either fix
        // leaves it.
        var schema = build(SERVICE_TYPES + """
            type Mutation { rateFilmWithDsl(in: RateFilmInput): RateFilmsPayload %s }
            """.formatted(service("rateFilmWithDsl")));

        assertThat(schema.tenantBindingOf("Mutation", "rateFilmWithDsl")).isNull();
        assertThat(schema.tenantBindings().rejections())
            .singleElement()
            .satisfies(e -> {
                var unrouted = (Rejection.AuthorError.UnroutedServiceCall) e.rejection();
                assertThat(unrouted.declines()).singleElement().asString()
                    .contains("'Mutation.rateFilmWithDsl' " + OPTIONAL_ONLY + " (in.film)");
            });
        assertInherited(schema, "RateFilmsPayload", "films");
        assertInherited(schema, "Film", "inventories");
    }

    // ===== Any rejected field or dispatch entry that needs a tenant rejects alone =====

    @Test
    void anEntityKeyMissingTheTenantColumnRejectsAloneNamingItsFix() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "title") {
                title: String
                inventories: [Inventory!]! @splitQuery
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query { x: String }
            """);

        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.rejection())
                .isInstanceOfSatisfying(Rejection.AuthorError.NoTenantBinding.class, r -> {
                    assertThat(r.coordinate()).isEqualTo("Film");
                    assertThat(r.detail()).contains("federation key alternative #0")
                        .endsWith("Add a field mapped to tenant column 'film_id' to the key's"
                            + " fields, mark the alternative resolvable: false, or mark the type"
                            + " @defaultTenant to resolve it on the request's default tenant.");
                }));
        assertInherited(schema, "Film", "inventories");
    }

    /** Two tenant-scoped node types reached through {@code Query.node}; {@code %s} is Inventory's key. */
    private static final String TWO_NODE_TYPES = """
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID! @nodeId
            inventories: [Inventory!]!
        }
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: [%s]) {
            id: ID! @nodeId
        }
        type Query { node(id: ID!): Node }
        """;

    @Test
    void aNodeKeyMissingTheTenantColumnRejectsAloneAndOtherNodeTypesKeepTheirContext() {
        // One bad key no longer voids node dispatch for every node type: Film still routes.
        var schema = build(TWO_NODE_TYPES.formatted("\"inventory_id\""));

        assertThat(schema.tenantBindings().rejections()).singleElement()
            .satisfies(e -> assertThat(e.rejection())
                .isInstanceOfSatisfying(Rejection.AuthorError.NoTenantBinding.class, r -> {
                    assertThat(r.coordinate()).isEqualTo("Inventory");
                    assertThat(r.detail()).contains("node id key").doesNotContain("@defaultTenant");
                }));
        assertInherited(schema, "Film", "inventories");
        assertThat(schema.tenantBindingOf("Query", "node")).isEqualTo(TenantBinding.NodeIdBound.INSTANCE);
    }

    @Test
    void nodeDispatchOverOnlyUnroutableTenantScopedTypesIsStillNodeIdBound() {
        // The arm the fold's answer implies: once the key is fixed, node dispatch routes.
        var schema = build("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Query { node(id: ID!): Node }
            """);

        assertRejects(schema, "Inventory", "node id key");
        assertThat(schema.tenantBindingOf("Query", "node")).isEqualTo(TenantBinding.NodeIdBound.INSTANCE);
    }

    @Test
    void aFanOutMarkerBelowARejectedUnboundRootRejectsBesideIt() {
        // Every fix to Query.films binds it, after which the marked child sits under a bound
        // ancestor, so it rejects now.
        var schema = build("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @tenantFanOut
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query { films: [Film!]! }
            """);

        assertFanOutRejects(schema, "Film.inventories", "sits under a tenant-bound ancestor");
        assertRejects(schema, "Query.films", "no argument or input field maps to tenant column");
        assertThat(schema.tenantBindings().rejections()).hasSize(2);
    }

    @Test
    void aCrossScopeRootHandsItsSubtreeNothing() {
        // The scope mix offers no tenant fix, and restructuring the field may leave it global, so
        // the child below it keeps its own error.
        var schema = build("""
            type Film @table(name: "film") {
                filmId: Int @field(name: "film_id")
                inventories: [Inventory!]!
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Language @table(name: "language") { name: String }
            union Media = Film | Language
            type Query { media: [Media!]! }
            """);

        assertRejects(schema, "Query.media", "cross-scope");
        assertRejects(schema, "Film.inventories", "no ancestor established a tenant context");
    }

    // ===== @defaultTenant: an entity whose key cannot name the tenant routes by the request =====

    private static void assertDefaultTenantRejection(GraphitronSchema schema, String typeName,
                                                     String detail) {
        assertThat(schema.tenantBindings().rejections())
            .anyMatch(e -> e.rejection() instanceof Rejection.InvalidSchema.DirectiveConflict conflict
                && conflict.directives().equals(List.of("defaultTenant"))
                && e.coordinate().equals(typeName)
                && conflict.message().contains("'" + typeName + "' declares @defaultTenant")
                && conflict.message().contains(detail));
    }

    private static TenantBinding.EntityRepBound.TenantSource.RequestDefault requestDefault(
            TenantBinding.EntityRepBound.AlternativeSlot slot) {
        assertThat(slot.source())
            .isInstanceOf(TenantBinding.EntityRepBound.TenantSource.RequestDefault.class);
        return (TenantBinding.EntityRepBound.TenantSource.RequestDefault) slot.source();
    }

    @Test
    void aMarkedEntityRoutesItsTenantLessKeyByTheRequestDefaultAndItsSubtreeInherits() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "title") @defaultTenant {
                title: String
                inventories: [Inventory!]! @splitQuery
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query { x: String }
            """);

        assertThat(schema.tenantBindings().rejections()).isEmpty();
        var slots = schema.tenantBindings().byEntityType().get("Film").alternatives();
        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.alternativeIndex()).isZero();
            assertThat(requestDefault(slot).tenantColumn().sqlName()).isEqualToIgnoringCase("film_id");
        });
        assertInherited(schema, "Film", "inventories");
    }

    @Test
    void aMarkedEntityWithOneKeyOfEachKindRoutesEachByItsOwnSource() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "filmId") @key(fields: "title") @defaultTenant {
                filmId: Int @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            """);

        assertThat(schema.tenantBindings().rejections()).isEmpty();
        var slots = schema.tenantBindings().byEntityType().get("Film").alternatives();
        assertThat(slots).hasSize(2);
        assertThat(slots.get(0)).isEqualTo(new TenantBinding.EntityRepBound.AlternativeSlot(0,
            new TenantBinding.EntityRepBound.TenantSource.KeyColumn(0)));
        assertThat(slots.get(1).alternativeIndex()).isEqualTo(1);
        assertThat(requestDefault(slots.get(1)).tenantColumn().sqlName()).isEqualToIgnoringCase("film_id");
    }

    @Test
    void aMarkedNodeTypeRoutesItsNodeIdByTheIdAndItsKeyByTheRequestDefault() {
        var schema = build(KEY_DIRECTIVE + """
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"])
                    @key(fields: "id") @key(fields: "actorId") @defaultTenant {
                id: ID! @nodeId
                actorId: Int @field(name: "actor_id")
            }
            type Query { node(id: ID!): Node }
            """);

        assertThat(schema.tenantBindings().rejections()).isEmpty();
        var resolution = schema.entitiesByType().get("FilmActor");
        var slots = schema.tenantBindings().byEntityType().get("FilmActor").alternatives();
        assertThat(slots).hasSize(2).allSatisfy(slot -> {
            var alt = resolution.alternatives().get(slot.alternativeIndex());
            if (alt instanceof no.sikt.graphitron.rewrite.model.KeyAlternative.NodeId) {
                assertThat(slot.source())
                    .isEqualTo(new TenantBinding.EntityRepBound.TenantSource.KeyColumn(1));
            } else {
                requestDefault(slot);
            }
        });
        assertThat(schema.tenantBindingOf("Query", "node")).isEqualTo(TenantBinding.NodeIdBound.INSTANCE);
    }

    @Test
    void aMarkedNodeTypeWhoseOnlyTenantLessKeyIsItsNodeIdRejectsBesideTheNodeKeyRejection() {
        var schema = build("""
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id"]) @defaultTenant {
                id: ID! @nodeId
            }
            type Query { node(id: ID!): Node }
            """);

        assertDefaultTenantRejection(schema, "Inventory", "every key names its tenant");
        assertThat(schema.tenantBindings().rejections())
            .filteredOn(e -> e.rejection() instanceof Rejection.AuthorError.NoTenantBinding)
            .singleElement()
            .satisfies(e -> assertThat(((Rejection.AuthorError.NoTenantBinding) e.rejection()).detail())
                .contains("node id key").doesNotContain("@defaultTenant"));
        assertThat(schema.tenantBindings().rejections()).hasSize(2);
    }

    @Test
    void aMarkedEntityInASingleTenantBuildRejects() {
        var schema = TestSchemaHelper.buildSchema(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "title") @defaultTenant { title: String }
            type Query { x: String }
            """);

        assertDefaultTenantRejection(schema, "Film", "no <tenantColumn>");
    }

    @Test
    void aMarkedGlobalEntityRejects() {
        var schema = build(KEY_DIRECTIVE + """
            type Language @table(name: "language") @key(fields: "languageId") @defaultTenant {
                languageId: Int @field(name: "language_id")
            }
            type Query { x: String }
            """);

        assertDefaultTenantRejection(schema, "Language", "has no tenant column 'film_id'");
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
    }

    @Test
    void aMarkedTypeWithNoKeyRejectsThroughTheSweep() {
        var schema = build("""
            type Film @table(name: "film") @defaultTenant { title: String }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertDefaultTenantRejection(schema, "Film", "has no resolvable key");
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
    }

    @Test
    void aMarkedTypeWhoseEveryKeyIsNotResolvableRejectsThroughTheSweep() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "title", resolvable: false) @defaultTenant {
                title: String
            }
            type Query { films(filmId: Int! @field(name: "film_id")): [Film!]! }
            """);

        assertDefaultTenantRejection(schema, "Film", "has no resolvable key");
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
    }

    @Test
    void aMarkedTypeWhoseEveryKeyNamesItsTenantRejects() {
        var schema = build(KEY_DIRECTIVE + """
            type Film @table(name: "film") @key(fields: "filmId") @defaultTenant {
                filmId: Int @field(name: "film_id")
            }
            type Query { x: String }
            """);

        assertDefaultTenantRejection(schema, "Film", "every key names its tenant");
        assertThat(schema.tenantBindings().rejections()).hasSize(1);
        // Rejected, the type classifies as if unmarked: its key routes by its own value.
        assertThat(schema.tenantBindings().byEntityType().get("Film").alternatives())
            .containsExactly(new TenantBinding.EntityRepBound.AlternativeSlot(0,
                new TenantBinding.EntityRepBound.TenantSource.KeyColumn(0)));
    }
}
