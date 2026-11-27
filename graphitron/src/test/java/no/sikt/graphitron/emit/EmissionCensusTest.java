package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import org.junit.jupiter.api.BeforeAll;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the sakila example's connections, conditions and projection units the store emits, and
 * why each of the rest stays with the classified model's emitters: censuses the flips move through,
 * one exclusion at a time, over one capture of the example's schema. Every condition emitted
 * compiles. The services have no census: the store emits every one it accepts, and
 * {@link ServiceEmission#emit} throws as a generator bug on one it cannot.
 */
@PipelineTier
class EmissionCensusTest {

    private static final String EMITTED = ServiceEmission.EMITTED;
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> com.palantir.javapoet.CodeBlock.of("env.getGraphQlContext().get($S)", key),
        coordinate -> com.palantir.javapoet.CodeBlock.of("null"),
        (member, coordinate) -> com.palantir.javapoet.CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(ClassName.get("census.util", NodeIdEncoderClassGenerator.CLASS_NAME), "census"));

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** What each census reads of the sakila example's schema, captured once for all of them. */
    private static ConnectionsQuery.Shapes connections;
    private static List<ConditionQuery.Row> conditions;
    private static NodeIdDecodeQuery decodes;
    private static Map<BuildQuery.Root, BuildQuery.Result> builds;
    private static Map<String, ProjectionQuery.Unit> projections;
    private static Map<String, RootQuery.Result> roots;
    /** The multi-tenant example's root reads, under the tenant column its build configures. */
    private static Map<String, RootQuery.Result> tenantRoots;
    /** The connection the store states for each of the multi-tenant example's producers. */
    private static Map<String, ConnectionQuery.Row> tenantConnections;

    @BeforeAll
    static void capture() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        FACTS.readInline("sakila-example", sdl, CatalogInput.of(testContext()),
            TestSchemaHelper.sakilaExampleClasspath(), graph -> {
                var handle = graph.handle();
                connections = ConnectionsQuery.read(handle);
                conditions = ConditionQuery.read(handle);
                decodes = NodeIdDecodeQuery.read(handle);
                builds = BuildQuery.read(handle);
                projections = ProjectionQuery.read(handle);
                roots = RootQuery.read(handle);
                return null;
            });
        // Under the tenant column the example's multi-tenant build sets in its pom.
        var tenantContext = testContext().withTenantColumn("film_id");
        String tenantSdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main", "resources",
            "graphql", "multitenant.graphqls"));
        FactGraphs.inline().read("sakila-multitenant", tenantSdl, CatalogInput.of(tenantContext),
            TestSchemaHelper.sakilaExampleClasspath(),
            no.sikt.graphitron.model.run.SubjectConfig.of(tenantContext), graph -> {
                tenantRoots = RootQuery.read(graph.handle());
                tenantConnections = ConnectionQuery.read(graph.handle());
                return null;
            });
    }

    @Test
    void theCensusOfTheExampleSchemasConnections() {
        var shapes = connections;
        var runtime = new ConnectionsEmitter.Runtime(
            ClassName.get("census.util", no.sikt.graphitron.rewrite.generators.util.PageInfoClassGenerator.CLASS_NAME),
            ClassName.get("census.util", no.sikt.graphitron.rewrite.generators.util.FacetsClassGenerator.CLASS_NAME),
            ClassName.get("census.schema", "ErrorRouter"));
        var units = new java.util.HashMap<String, TypeSpec>();
        // The router a faceted connection's registration routes a failure through, as a stand-in
        // with the one member it calls: what the router decides is its generator's.
        units.put("census.schema.ErrorRouter", TypeSpec.classBuilder("ErrorRouter")
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .addMethod(com.palantir.javapoet.MethodSpec.methodBuilder("surfaceClientErrorOrRedact")
                .addModifiers(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC)
                .returns(Object.class)
                .addParameter(Throwable.class, "thrown")
                .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
                .addStatement("return null")
                .build())
            .build());
        for (TypeSpec spec : ConnectionsEmitter.emit(shapes, "census", runtime)) {
            units.put("census.util." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.PageInfoClassGenerator.generate()) {
            units.put("census.util." + spec.name(), spec);
        }
        // The facets records the connections name, as empty records: what a record holds is the
        // facets generator's, and what the connections need is its name.
        var facets = shapes.connections().stream()
            .map(ConnectionsQuery.Connection::facetsTypeName).filter(java.util.Objects::nonNull)
            .map(name -> new no.sikt.graphitron.command.GlobalCommand.FacetsRecord(name, List.of()))
            .toList();
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.FacetsClassGenerator.generate(
                new no.sikt.graphitron.command.GlobalCommand.Facets(
                    new no.sikt.graphitron.command.UnitRef("census.util", "Facets"), List.of(), facets),
                "census")) {
            units.put("census.util." + spec.name(), spec);
        }
        EmittedCodeHarness.compileStrictly(units).close();
        assertThat(shapes.connections()).as("the example's connection types").isNotEmpty();
        assertThat(shapes.edges()).as("the example's edge types").isNotEmpty();
        assertThat(facets).as("the example's faceted connections").isNotEmpty();
    }

    @Test
    void theCensusOfTheExampleSchemasConditions() throws Exception {
        var rows = conditions;
        var census = new TreeMap<String, List<String>>();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        int n = 0;
        for (var row : rows) {
            var key = row.coordinate() + " " + row.table().sqlName();
            switch (ConditionEmitter.emit(row, "condition" + n++, decodes, RUNTIME,
                    no.sikt.graphitron.render.TableAliasFragments.tableAliasesClass("census"), builds)) {
                case ConditionEmitter.Result.Emitted emitted -> {
                    census.computeIfAbsent(EMITTED, k -> new ArrayList<>()).add(key);
                    type.addMethod(emitted.method());
                    emitted.helpers().forEach(type::addMethod);
                }
                case ConditionEmitter.Result.Nothing ignored ->
                    census.computeIfAbsent("NOTHING", k -> new ArrayList<>()).add(key);
                case ConditionEmitter.Result.NotYet notYet ->
                    census.computeIfAbsent(notYet.reason().name(), k -> new ArrayList<>()).add(key);
            }
        }
        var units = new java.util.HashMap<String, TypeSpec>();
        units.put("census.Conditions", type.build());
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), "census", List.of())) {
            units.put("census.util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put("census.schema." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator.generate("census")) {
            units.put("census.util." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put("census.util." + spec.name(), spec);
        }
        EmittedCodeHarness.compileStrictly(units).close();
        assertThat(census.get(EMITTED)).as("the conditions the store emits").containsExactly(
            "Actor.filmsConnection films_for_actor", "Actor.filmsConnectionSplit films_for_actor",
            "Film.actorsBySplitLookupGenerated actor", "Film.actorsGenerated actor",
            "Film.inlineBundle.languageFiltered language", "Language.filmsByMixedFilter film",
            "Language.filmsByMixedFilterSplit film", "Query.actorFilmsFiltered films_for_actor",
            "Query.actorsByFilmFilter actor",
            "Query.actorsByFilmFilterConnection actor", "Query.actorsByFilmIds actor",
            "Query.addressesByCountryName address", "Query.addressesByCustomerIds address",
            "Query.addressesByCustomerIdsInferred address", "Query.citiesByCountryFilter city",
            "Query.citiesByCountryName city", "Query.citiesFaceted city", "Query.computedTitleFilm film",
            "Query.converterCampusTermByNodeId converter_campus_term",
            "Query.converterCampusTermsByNodeIds converter_campus_term",
            "Query.converterCampusesByOrgCode converter_campus",
            "Query.converterCampusesByOrgCodes converter_campus",
            "Query.converterIdChangeByNodeId converter_id_change", "Query.customerByPk customer",
            "Query.customers customer", "Query.customersByAddressDistrict customer",
            "Query.customersByAddressDistrictActive customer", "Query.customersByAddresses customer",
            "Query.customersByCityFilter customer", "Query.customersByCityId customer",
            "Query.customersByConditionDistrict customer", "Query.customersByDistrictBean customer",
            "Query.customersByMultiFieldFilter customer", "Query.customersByNullableAddressIds customer", "Query.customersByTwoNames customer",
            "Query.customersOfPrincipal customer", "Query.customersSeenByUser customer",
            "Query.endorsementsByNodeId film_endorsement", "Query.fanItem fan_base",
            "Query.filmActorBySingularCompositeNodeId film_actor", "Query.filmActorsByCompositeNodeIds film_actor",
            "Query.filmActorsByNullableNodeIds film_actor", "Query.filmActorsByPairList film_actor",
            "Query.filmActorsByPairs film_actor", "Query.filmActorsFaceted film_actor", "Query.films film",
            "Query.filmsByBridgedActorFirstName film", "Query.filmsByCastNames film",
            "Query.filmsByEffectiveNullability film", "Query.filmsByJunctionActorId film",
            "Query.filmsByLanguagePairs film", "Query.filmsByNodeIdArg film",
            "Query.filmsByNodeIdArgWithTitleFilter film", "Query.filmsByNullableNodeIds film",
            "Query.filmsByOneOf film", "Query.filmsByOptionalLanguage film", "Query.filmsByOptionalLanguages film",
            "Query.filmsByOriginalLanguages film",
            "Query.filmsByPlainInput film", "Query.filmsByRentalRateBounds film",
            "Query.filmsByRentalRateRange film",
            "Query.filmsByRentalRateRangeDefaulted film", "Query.filmsByRentalRateRangeExplicit film",
            "Query.filmsBySameTableNodeId film", "Query.filmsBySearchBean film", "Query.filmsByTitleBean film",
            "Query.filmsByTitleBeans film", "Query.filmsByTitlePrefix film",
            "Query.filmsConnectionByOptionalIds film", "Query.filmsConnectionByRequiredIds film",
            "Query.filmsFaceted film", "Query.filmsNestedInput film", "Query.filmsOfLanguageIds film",
            "Query.filmsOfLanguageIdsFilter film", "Query.filmsOfLanguageIdsWithOriginal film",
            "Query.filmsOfPickedLanguageIds film", "Query.filmsOrderedConnection film",
            "Query.filmsOuterOverrideFilterInput film", "Query.filmsOuterOverridePlainInput film",
            "Query.filmsWithActors film",
            "Query.filmsWithImplicitInput film", "Query.filmsWithImplicitInputOuterOverride film",
            "Query.filmsWithInputFieldCondition film", "Query.filmsWithInputFieldOverride film",
            "Query.filmsWithMultiImplicit film", "Query.filmsWithNestedImplicit film",
            "Query.inventoryItemsByIds inventory", "Query.languagesByKeyFiltered language",
            "Query.languagesByKeyGenerated language", "Query.levelIntervals level_interval", "Query.namedCitiesByCountryName city",
            "Query.occupantById customer", "Query.occupantById staff",
            "Query.occupantByOptionalId customer", "Query.occupantByOptionalId staff",
            "Query.occupantsByAddress customer", "Query.occupantsByAddress staff",
            "Query.occupantsByFilter customer", "Query.occupantsByFilter staff",
            "Query.occupantsByIds customer", "Query.occupantsByIds staff",
            "Query.occupantsByIdsConnection customer", "Query.occupantsByIdsConnection staff",
            "Query.occupantsByMixedNamePrefix customer", "Query.occupantsByMixedNamePrefix staff",
            "Query.occupantsByName customer", "Query.occupantsByName staff",
            "Query.occupantsByNameConnection customer", "Query.occupantsByNameConnection staff",
            "Query.occupantsByNamePrefix customer", "Query.occupantsByNamePrefix staff",
            "Query.occupantsByNullableIds customer", "Query.occupantsByNullableIds staff",
            "Query.occupantsByStoreId customer", "Query.occupantsByStoreId staff",
            "Query.occupantsByTypedNamePrefix customer", "Query.occupantsByTypedNamePrefix staff",
            "Query.occupantsStartingWithM customer", "Query.occupantsStartingWithM staff",
            "Query.presenceDetail presence_detail", "Query.presenceDetailNode presence_detail",
            "Query.presenceDetailRequired presence_detail", "Query.presenceHostRefs presence_host",
            "Query.presenceHosts presence_host", "Query.projectNotesByPlainFilter project_note",
            "Query.projectNotesByPlainFilterConnection project_note", "Query.projectNotesByProject project_note",
            "Query.rentalsByInventoryItem rental",
            "Query.splitFilterParentsByTargetLabel split_filter_parent", "Query.stockByLanguage film",
            "Query.stockByLanguage inventory", "Query.stockByLanguageOverride film",
            "Query.stockByLanguageOverride inventory",
            "Query.tilgangerAdmin tilganger_for_feidebruker_med_fs_fiktivt_fnr",
            "Query.xlatChildByParentId xlat_child",
            "Query.xlatChildrenByFilter xlat_child", "Query.xlatChildrenByParentIds xlat_child",
            "Query.xlatCompChildrenByParentIds xlat_comp_child", "SessionStore.customersOfPrincipal customer",
            "Store.customersByAddressDistrict customer", "Store.customersByAddressDistrictSplit customer",
            "Store.customersByFirstName customer", "Store.customersByFirstNameSplit customer",
            "Store.customersByNodeId customer", "Store.customersByNodeIdSplit customer",
            "Store.customersSeenByUser customer");
        // Every condition the example filters by is emitted from the store or has nothing to compare;
        // a shape the store accepted and the emitter does not emit would be a generator bug.
        assertThat(census)
            .containsEntry("NOTHING", List.of("Film.actors actor", "Film.actorsBySplitLookup actor", "Film.actorsOrderedBySplitLookup actor",
                "Film.filmDetails.actorsByLookup actor", "Film.info.castByKey actor",
                "Film.inlineBundle.actorsByKey actor", "Film.inlineBundle.actorsByKeyViaJunctionCondition actor",
                "Query.categoryById category", "Query.customerById customer",
                "Query.customerByIdKey customer", "Query.customersByAddressKey customer",
                "Query.customersByEmailAndAddress customer", "Query.customersByIdKey customer",
                "Query.filmActorByNodeId film_actor", "Query.filmActorsByIdKey film_actor",
                "Query.filmActorsByKey film_actor", "Query.filmById film", "Query.filmDetailsBatch.actorsByLookup actor",
                "Query.filmsByNodeIdArgWithLookupKey film", "Query.inventoryById inventory",
                "Query.languageByKey language", "Query.languageBySingleKey language",
                "Query.presenceDetailByKey presence_detail", "Query.storeById store",
                "RoleHolder.rolesByCode role_assignment"))
            .containsOnlyKeys(EMITTED, "NOTHING");
    }

    /**
     * Which of the example's table-bound types the store renders a projection unit for, and why
     * each of the rest is not yet, by the occurrences that keep it back. Every unit emitted
     * compiles beside the selection adapters it reads its fields through.
     */
    @Test
    void theCensusOfTheExampleSchemasProjectionUnits() {
        var units = new java.util.HashMap<String, TypeSpec>();
        var emitted = new ArrayList<String>();
        var refused = new TreeMap<String, List<String>>();
        for (var result : ProjectionEmitter.emitAll(projections, "census").entrySet()) {
            switch (result.getValue()) {
                case ProjectionEmitter.Result.Emitted e -> {
                    emitted.add(result.getKey());
                    units.put("census." + e.unit().name(), e.unit());
                }
                case ProjectionEmitter.Result.NotYet n -> n.refusals().forEach(refusal ->
                    refused.computeIfAbsent(refusal.reason().name(), k -> new ArrayList<>()).add(refusal.coordinate()));
            }
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator.generate("census")) {
            units.put("census.util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put("census.schema." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator.generate("census")) {
            units.put("census.util." + spec.name(), spec);
        }
        EmittedCodeHarness.compileStrictly(units).close();
        java.util.Collections.sort(emitted);
        refused.values().forEach(java.util.Collections::sort);
        assertThat(emitted).as("the units the store emits").containsExactly("ActorCondMember", "ActorMember",
            "ActorNode", "Address", "AppAccount", "Catalogue", "CatalogueItem", "CatalogueShelf", "Category",
            "CategoryLabel", "CategoryNode", "City", "Company", "Content", "ConverterCampus",
            "ConverterCampusTerm", "ConverterIdChange", "ConverterOrg", "ConverterSite", "DisplayAccount",
            "DisplayPerson", "DivergedCampusRef", "DivergedChildRef", "DivergedLabelSide", "DivergedOrgSide",
            "DivergedRefChild", "DivergedRefOrg", "Email", "FanDetail", "FanMark", "FanOwner", "FanTarget",
            "FilmBookmark", "FilmCategoryNode", "FilmContent", "FilmEndorsement", "FilmEndorsementNode",
            "FilmInventory", "HostLabelsRow", "Individual", "InvCondMember", "InventoryItem",
            "InventoryUnit", "KeyedNode", "LanguageAlias", "LanguageNode", "LevelInterval", "Mailbox", "NamedCity",
            "PagedA", "PagedB", "Party", "PaymentLegacyProbe", "Person", "PresenceDetail",
            "PresenceDetailNode", "PresenceHost", "PresenceHostRefs", "PresenceItem", "Project",
            "ProjectEvent", "ProjectNote", "Rental", "RoleAssignment", "RoleHolder", "SecureNote",
            "SessionCustomer", "ShortContent", "SplitFilterParent", "SplitFilterTarget", "SplitKeyChild",
            "SplitKeyParent", "SplitParent", "SplitParentTag", "SquadMember", "Staff", "StockFilm",
            "StockInventory", "StorageBin", "StoreLocation", "StoreManager", "StudyLevel", "Subject",
            "ValidityPeriod", "XlatChild", "XlatCompChild", "XlatCompParent", "XlatParent");
        // Each line goes as its fact lands; an occurrence moving between lines is a change to look at,
        // not to re-pin.
        assertThat(refused)
            .containsEntry("EXPRESSION", List.of("ComputedTitleFilm.title", "Customer.location.occupantAddressId",
                "FanLabelled.label", "FanLabelledAlpha.label", "FanLabelledBeta.label", "Film.isEnglish",
                "Film.projected.inner.isEnglish", "Film.projected.isEnglish", "Film.titleByName",
                "HostLabelsComputedRow.nb", "HostLabelsComputedRow.nn", "HostLanguageNames.englishName",
                "HostLanguageNames.italianName", "Inventory.filmCardData", "Inventory.filmCardDataMaybeMissing",
                "Inventory.filmRef", "PivotNestingHost.labelsComputed.nb", "PivotNestingHost.labelsComputed.nn",
                "PivotNestingHost.labelsMixed.nb", "Store.location.occupantAddressId"))
            .containsEntry("INLINE_CHILD", List.of("Customer.addressByCondition",
                "Film.Length", "Film.castActorsViaChain", "Film.filmDetails.language",
                "Film.inlineBundle.language", "Film.inlineBundle.languageFiltered", "Film.original.language",
                "Film.sameRow", "Film.sameRowNested.row", "Film.spoken.language", "FilmActor.actor",
                "FilmSameRow.film", "FilmSameRow.language", "HostLabelsWithFilms.languageFilms",
                "Language.filmsInlineByOriginalLanguage",
                "Language.filmsInlineByOriginalLanguageDesc", "PivotNestingHost.labelsComputedRow",
                "PivotNestingHost.labelsWithFilms", "PivotNestingHost.languageNames",
                "PivotNestingHost.origin.language", "Store.customers"))
            .containsEntry("NARROWED_LIST", List.of("Actor.filmCards", "Actor.films", "Actor.filmsNested", "Actor.recentFilms",
                "Actor.recentFilmsInferred",
                "FanAlpha.details", "FanAlpha.marks", "FanBeta.details", "FanBeta.marks", "FanItem.details",
                "Film.actors", "Film.actorsGenerated", "Film.castFilms", "Film.castRecentFilms", "Film.filmDetails.actorsByLookup",
                "Film.inlineBundle.actorsByKey", "Film.inlineBundle.actorsByKeyViaJunctionCondition",
                "Language.filmsByMixedFilter", "SessionStore.customersOfPrincipal",
                "Store.customersByAddressDistrict", "Store.customersByFirstName", "Store.customersByNodeId",
                "Store.customersFirstN"))
            .containsEntry("PIVOT", List.of("Film.prices", "Film.pricesSplit", "Film.taglineTranslations",
                "Film.titleTranslations", "Film.titleTranslationsSplit", "FilmActor.noteTranslations"))
            .containsEntry("SCALAR_PATH", List.of("Customer.addressNodeId", "Customer.districtByCondition",
                "Film.soleActorFirstName"))
            .containsOnlyKeys("EXPRESSION", "INLINE_CHILD", "NARROWED_LIST", "PIVOT", "SCALAR_PATH");
    }

    /**
     * Which of the example's root reads the store states whole, so their fetchers are read off it,
     * and why each of the rest stays the walk's, by the stored fact that holds it back.
     */
    @Test
    void theCensusOfTheExampleSchemasRootReads() {
        var census = RootCensus.of(roots);
        var emitted = census.emitted();
        var nodes = census.nodes();
        var multiTables = census.multiTables();
        var held = census.held();
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(nodes).as("the node fetchers the store emits")
                .containsExactly("Query.internalNode", "Query.node", "Query.nodes");
            soft.assertThat(multiTables).as("the roots over several tables the store emits").containsExactly(
                "Query.converterPlacesConnection", "Query.displayNamedConnection", "Query.documents",
                "Query.documentsConnection", "Query.occupantById", "Query.occupantByOptionalId",
                "Query.occupantsByAddress", "Query.occupantsByFilter", "Query.occupantsByIds",
                "Query.occupantsByIdsConnection", "Query.occupantsByLastName", "Query.occupantsByMixedNamePrefix",
                "Query.occupantsByName", "Query.occupantsByNameConnection", "Query.occupantsByNamePrefix",
                "Query.occupantsByNullableIds", "Query.occupantsByStoreId", "Query.occupantsByTypedNamePrefix",
                "Query.occupantsOrdered", "Query.occupantsOrderedByKey", "Query.occupantsOrderedConnection",
                "Query.occupantsStartingWithM", "Query.pagedItems",
                "Query.pagedItemsByName", "Query.search", "Query.searchConnection", "Query.stockByLanguage",
                "Query.stockByLanguageOverride");
            soft.assertThat(emitted).as("the roots the store emits").containsExactly(
                "Query.actorsByFilmFilter", "Query.actorsByFilmFilterConnection", "Query.actorsByFilmIds",
                "Query.addressesByCountryName", "Query.addressesByCustomerIds",
                "Query.addressesByCustomerIdsInferred", "Query.allActors", "Query.allContent",
                "Query.allContentByFilmTitle", "Query.allContentConnection",
                "Query.allContentConnectionByFilmTitle", "Query.allFanItems", "Query.allFanLabelled",
                "Query.allFanOwners", "Query.allIndividuals", "Query.allParties", "Query.allPartiesConnection",
                "Query.allSubjects", "Query.categoryById", "Query.citiesByCountryFilter",
                "Query.citiesByCountryName", "Query.citiesFaceted", "Query.computedTitleFilm",
                "Query.converterCampusTermByNodeId", "Query.converterCampusTerms",
                "Query.converterCampusTermsByNodeIds", "Query.converterCampuses",
                "Query.converterCampusesByOrgCode", "Query.converterCampusesByOrgCodes",
                "Query.converterIdChangeByNodeId", "Query.converterIdChanges",
                "Query.converterIdChangesConnection", "Query.converterOrgs", "Query.customerById",
                "Query.customerByIdKey", "Query.customerByPk", "Query.customers", "Query.customersByAddressDistrict",
                "Query.customersByAddressDistrictActive", "Query.customersByAddressKey", "Query.customersByAddresses",
                "Query.customersByCityFilter", "Query.customersByCityId", "Query.customersByConditionDistrict",
                "Query.customersByDistrictBean", "Query.customersByEmailAndAddress", "Query.customersByIdKey",
                "Query.customersByMultiFieldFilter",
                "Query.customersByNullableAddressIds", "Query.customersByTwoNames", "Query.customersOfPrincipal",
                "Query.customersSeenByUser", "Query.divergedRefChildren", "Query.divergedRefOrgs",
                "Query.endorsementsByNodeId", "Query.fanItem", "Query.filmActorByNodeId",
                "Query.filmActorBySingularCompositeNodeId", "Query.filmActorsByCompositeNodeIds",
                "Query.filmActorsByIdKey", "Query.filmActorsByKey", "Query.filmActorsByNullableNodeIds", "Query.filmActorsByPairList",
                "Query.filmActorsByPairs", "Query.filmActorsFaceted", "Query.filmById", "Query.filmSameRows",
                "Query.films", "Query.filmsByBridgedActorFirstName", "Query.filmsByCastNames",
                "Query.filmsByEffectiveNullability", "Query.filmsByJunctionActorId",
                "Query.filmsByLanguagePairs", "Query.filmsByLanguages", "Query.filmsByLanguagesList",
                "Query.filmsByNodeIdArg", "Query.filmsByNodeIdArgWithLookupKey",
                "Query.filmsByNodeIdArgWithTitleFilter", "Query.filmsByNullableNodeIds", "Query.filmsByOneOf",
                "Query.filmsByOptionalLanguage", "Query.filmsByOptionalLanguages",
                "Query.filmsByOriginalLanguage", "Query.filmsByOriginalLanguageDescList",
                "Query.filmsByOriginalLanguageId", "Query.filmsByOriginalLanguageList",
                "Query.filmsByOriginalLanguages", "Query.filmsByPlainInput", "Query.filmsByRateDescTitleAsc",
                "Query.filmsByRentalRate", "Query.filmsByRentalRateBounds", "Query.filmsByRentalRateRange",
                "Query.filmsByRentalRateRangeDefaulted", "Query.filmsByRentalRateRangeExplicit",
                "Query.filmsBySameTableNodeId", "Query.filmsBySearchBean", "Query.filmsByTitleBean",
                "Query.filmsByTitleBeans", "Query.filmsByTitlePrefix", "Query.filmsConnection",
                "Query.filmsConnectionByOptionalIds", "Query.filmsConnectionByRequiredIds",
                "Query.filmsConnectionDesc", "Query.filmsFaceted", "Query.filmsNestedInput",
                "Query.filmsOfLanguageIds", "Query.filmsOfLanguageIdsFilter",
                "Query.filmsOfLanguageIdsWithOriginal", "Query.filmsOfPickedLanguageIds", "Query.filmsOrdered",
                "Query.filmsOrderedByOneKeyConnection",
                "Query.filmsOrderedConnection", "Query.filmsOuterOverrideFilterInput",
                "Query.filmsOuterOverridePlainInput", "Query.filmsWithActors", "Query.filmsWithImplicitInput",
                "Query.filmsWithImplicitInputOuterOverride", "Query.filmsWithInputFieldCondition",
                "Query.filmsWithInputFieldOverride", "Query.filmsWithMultiImplicit",
                "Query.filmsWithNestedImplicit", "Query.hostLabelRows", "Query.inventoryById",
                "Query.inventoryItemsByIds", "Query.languageByKey", "Query.languageBySingleKey",
                "Query.languagesByKeyFiltered", "Query.languagesByKeyGenerated", "Query.levelIntervals",
                "Query.namedCitiesByCountryName", "Query.pivotHosts", "Query.presenceDetail",
                "Query.presenceDetailByKey", "Query.presenceDetailNode", "Query.presenceDetailRequired",
                "Query.presenceHostRefs", "Query.presenceHosts", "Query.projectNotesByPlainFilter",
                "Query.projectNotesByPlainFilterConnection", "Query.projectNotesByProject", "Query.projects",
                "Query.recentComedyFilmsForActor", "Query.recentFilmsForActor",
                "Query.recentFilmsForActorInferred", "Query.rentalsByInventoryItem", "Query.roleHolders", "Query.secureNotes", "Query.sessionStores",
                "Query.splitFilterParents", "Query.splitFilterParentsByTargetLabel", "Query.splitParents",
                "Query.squadMembers", "Query.storeById", "Query.stores", "Query.studyLevels",
                "Query.tilganger", "Query.tilgangerAdmin", "Query.validityPeriodsByValidTo", "Query.validityPeriodsByValidToDesc",
                "Query.validityPeriodsByValidToDescList", "Query.validityPeriodsOrdered",
                "Query.xlatChildByParentId", "Query.xlatChildrenByFilter", "Query.xlatChildrenByParentIds",
                "Query.xlatCompChildrenByParentIds");
            // Each line goes as its shape is stated; a root moving between lines is a change to look at,
            // not to re-pin.
            soft.assertThat(held).as("the roots the walk keeps").containsExactly(
                Map.entry("ROUTINE_ARGUMENT", List.of("Query.actorFilmsFiltered", "Query.filmsForOptionalActor",
                    "Query.filmsForOptionalActorFilter")),
                Map.entry("ROUTINE_SHAPE", List.of("Query.recentFilmsForActorConnection", "Query.tilgangerConnection",
                    "Query.tilgangerSortert")));
        });
    }

    /**
     * Which of the multi-tenant example's root reads the store states whole, which of those it routes
     * by the tenant their arguments name, as their stored connection says, and why each of the rest
     * stays the walk's. A read of a tenant's rows marked {@code @tenantFanOut} has no stored connection
     * and is held back as a fan-out.
     */
    @Test
    void theCensusOfTheMultiTenantExampleSchemasRootReads() {
        var census = RootCensus.of(tenantRoots);
        var routed = census.emitted().stream()
            .filter(coordinate -> tenantConnections.containsKey(coordinate)
                && tenantConnections.get(coordinate).outcome() == ConnectionQuery.Outcome.ROUTED)
            .toList();
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(census.nodes()).as("the node fetchers the store emits")
                .containsExactly("Query.node", "Query.nodes");
            soft.assertThat(census.multiTables()).as("the roots over several tables the store emits")
                .containsExactly("Query.globalThings");
            soft.assertThat(routed).as("the roots the store emits and routes by their tenant")
                .containsExactly("Query.filmActorsByNodeId", "Query.filmActorsWhere", "Query.films",
                    "Query.filmsByKey", "Query.filmsByWireId", "Query.filmsConnectionScoped",
                    "Query.filmsUnderOverride");
            soft.assertThat(census.emitted()).as("the roots the store emits").containsExactly(
                "Query.bookmarks", "Query.endorsements", "Query.filmActorsByNodeId", "Query.filmActorsWhere",
                "Query.films", "Query.filmsByKey", "Query.filmsByWireId", "Query.filmsConnectionScoped",
                "Query.filmsUnderOverride", "Query.languages", "Query.languagesConnection");
            soft.assertThat(census.held()).as("the roots the walk keeps").containsExactly(
                Map.entry("TENANTED", List.of("Query.filmHoldings")),
                Map.entry("TENANT_FAN_OUT", List.of("Query.filmsEverywhere", "Query.filmsEverywhereStrict",
                    "Query.filmsNarrowable", "Query.filmsNarrowableByIds", "Query.filmsNarrowableByWireId")));
        });
    }

    /** One schema's root reads, sorted by what the store states of each. */
    private record RootCensus(List<String> emitted, List<String> nodes, List<String> multiTables,
                              Map<String, List<String>> held) {
        static RootCensus of(Map<String, RootQuery.Result> results) {
            var census = new RootCensus(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new TreeMap<>());
            for (var result : results.values()) {
                switch (result) {
                    case RootQuery.Emitted e -> census.emitted().add(e.coordinate());
                    case RootQuery.NodeFetcher n -> census.nodes().add(n.coordinate());
                    case RootQuery.MultiTable m -> census.multiTables().add(m.coordinate());
                    case RootQuery.NotYet n -> census.held().computeIfAbsent(n.reason().name(),
                        k -> new ArrayList<>()).add(n.coordinate());
                }
            }
            java.util.Collections.sort(census.emitted());
            java.util.Collections.sort(census.nodes());
            java.util.Collections.sort(census.multiTables());
            census.held().values().forEach(java.util.Collections::sort);
            return census;
        }
    }
}
