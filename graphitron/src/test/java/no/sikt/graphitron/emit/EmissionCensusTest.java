package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the sakila example's services and conditions the store emits, and why each of the rest
 * stays with the classified model's emitters: censuses the flips move through, one exclusion at a
 * time, over one capture of the example's schema. Every condition emitted compiles.
 */
@PipelineTier
class EmissionCensusTest {

    private static final String EMITTED = ServiceEmission.EMITTED;
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> com.palantir.javapoet.CodeBlock.of("env.getGraphQlContext().get($S)", key),
        coordinate -> com.palantir.javapoet.CodeBlock.of("null"),
        (member, coordinate) -> com.palantir.javapoet.CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(ClassName.get("census.util", NodeIdEncoderClassGenerator.CLASS_NAME), "census"));

    @TempDir
    static Path shared;

    /** The sakila example's schema, captured once for every census. */
    private static CapturedStore sakila;

    @TempDir
    Path tmp;

    @BeforeAll
    static void capture() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        // A store of the class's own: a case capturing a schema of its own borrows the thread's
        // store, which would clear this capture under the censuses read after it.
        sakila = CapturedStore.ownStoreOfCatalogWith(shared, CapturedStore.GRAPH, sdl,
            CatalogInput.of(testContext()), TestSchemaHelper.sakilaExampleClasspath());
    }

    @AfterAll
    static void close() {
        if (sakila != null) sakila.close();
    }

    private static StoreHandle handle() {
        return new StoreHandle(sakila.dsl(), CapturedStore.GRAPH);
    }

    @Test
    void theCensusOfTheExampleSchemasServices() {
        Map<String, String> census = ServiceEmission.read(handle()).census();
        var byVerdict = new TreeMap<String, List<String>>(census.entrySet().stream().collect(
            Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList()))));
        byVerdict.values().forEach(java.util.Collections::sort);
        assertThat(byVerdict.get(ServiceEmission.EMITTED)).as("the services the store emits").hasSize(134);
        assertThat(byVerdict.get(ServiceEmission.EMITTED))
            .as("a node id decoded through a converter-backed key column")
            .contains("Query.converterIdChangeValue");
        assertThat(byVerdict.get(ServiceEmission.EMITTED))
            .as("a key column projected out of each of a list of node ids, one key and a composite's second")
            .contains("Query.filmsByProjectedIds", "Film.copiesAtStores");
        assertThat(byVerdict.get(ServiceEmission.EMITTED))
            .as("an id of several node types, at a parameter, a bean member and a member's list")
            .contains("Mutation.assignOccupantByArgument", "Mutation.assignOccupantRecord",
                "Mutation.assignOccupantRecordList");
        // A service the store does not emit is a change to look at, not to re-pin.
        assertThat(byVerdict).containsOnlyKeys(ServiceEmission.EMITTED);
    }


    @Test
    void theCensusOfTheExampleSchemasConnections() {
        var shapes = ConnectionsQuery.read(handle());
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
        assertThat(shapes.connections()).as("the example's connection types").hasSize(35);
        assertThat(shapes.edges()).as("the example's edge types").hasSize(35);
        assertThat(facets).as("the example's faceted connections").hasSize(3);
    }

    @Test
    void theCensusOfTheExampleSchemasConditions() throws Exception {
        var rows = ConditionQuery.read(handle());
        var decodes = NodeIdDecodeQuery.read(handle());
        var builds = BuildQuery.read(handle());
        var census = new TreeMap<String, List<String>>();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        int n = 0;
        for (var row : rows) {
            var key = row.coordinate() + " " + row.table().sqlName();
            switch (ConditionEmitter.emit(row, "condition" + n++, decodes, RUNTIME, builds)) {
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
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put("census.util." + spec.name(), spec);
        }
        EmittedCodeHarness.compileStrictly(units).close();
        assertThat(census.get(EMITTED)).as("the conditions the store emits").containsExactly(
            "Film.actorsBySplitLookupGenerated actor", "Film.actorsGenerated actor",
            "Film.inlineBundle.languageFiltered language", "Language.filmsByMixedFilter film",
            "Language.filmsByMixedFilterSplit film", "Query.actorsByFilmFilter actor",
            "Query.actorsByFilmFilterConnection actor", "Query.actorsByFilmIds actor",
            "Query.addressesByCountryName address", "Query.addressesByCustomerIds address",
            "Query.addressesByCustomerIdsInferred address", "Query.citiesByCountryFilter city",
            "Query.citiesByCountryName city", "Query.citiesFaceted city", "Query.computedTitleFilm film",
            "Query.converterCampusTermByNodeId converter_campus_term",
            "Query.converterCampusTermsByNodeIds converter_campus_term",
            "Query.converterCampusesByOrgCode converter_campus",
            "Query.converterCampusesByOrgCodes converter_campus",
            "Query.converterIdChangeByNodeId converter_id_change", "Query.customerByPk customer",
            "Query.customers customer", "Query.customersByAddresses customer",
            "Query.customersByCityFilter customer", "Query.customersByCityId customer",
            "Query.customersByConditionDistrict customer", "Query.customersByDistrictBean customer",
            "Query.customersByNullableAddressIds customer", "Query.customersByTwoNames customer",
            "Query.customersOfPrincipal customer", "Query.customersSeenByUser customer",
            "Query.endorsementsByNodeId film_endorsement", "Query.fanItem fan_base",
            "Query.filmActorBySingularCompositeNodeId film_actor", "Query.filmActorsByCompositeNodeIds film_actor",
            "Query.filmActorsByNullableNodeIds film_actor", "Query.filmActorsByPairList film_actor",
            "Query.filmActorsByPairs film_actor", "Query.filmActorsFaceted film_actor", "Query.films film",
            "Query.filmsByBridgedActorFirstName film", "Query.filmsByCastNames film",
            "Query.filmsByEffectiveNullability film", "Query.filmsByJunctionActorId film",
            "Query.filmsByLanguagePairs film", "Query.filmsByNodeIdArg film",
            "Query.filmsByNodeIdArgWithTitleFilter film", "Query.filmsByNullableNodeIds film",
            "Query.filmsByOneOf film", "Query.filmsByOptionalLanguages film", "Query.filmsByOriginalLanguages film",
            "Query.filmsByPlainInput film", "Query.filmsByRentalRateBounds film",
            "Query.filmsByRentalRateRange film",
            "Query.filmsByRentalRateRangeDefaulted film", "Query.filmsByRentalRateRangeExplicit film",
            "Query.filmsBySameTableNodeId film", "Query.filmsBySearchBean film", "Query.filmsByTitleBean film",
            "Query.filmsByTitleBeans film", "Query.filmsByTitlePrefix film",
            "Query.filmsConnectionByOptionalIds film", "Query.filmsConnectionByRequiredIds film",
            "Query.filmsFaceted film", "Query.filmsNestedInput film", "Query.filmsOrderedConnection film",
            "Query.filmsOuterOverrideFilterInput film", "Query.filmsOuterOverridePlainInput film",
            "Query.filmsWithImplicitInput film", "Query.filmsWithImplicitInputOuterOverride film",
            "Query.filmsWithInputFieldCondition film", "Query.filmsWithInputFieldOverride film",
            "Query.filmsWithMultiImplicit film", "Query.filmsWithNestedImplicit film",
            "Query.inventoryItemsByIds inventory", "Query.languagesByKeyFiltered language",
            "Query.languagesByKeyGenerated language", "Query.levelIntervals level_interval",
            "Query.occupantsByAddress customer", "Query.occupantsByAddress staff",
            "Query.occupantsByFilter customer", "Query.occupantsByFilter staff",
            "Query.occupantsByMixedNamePrefix customer", "Query.occupantsByMixedNamePrefix staff",
            "Query.occupantsByName customer", "Query.occupantsByName staff",
            "Query.occupantsByNameConnection customer", "Query.occupantsByNameConnection staff",
            "Query.occupantsByNamePrefix customer", "Query.occupantsByNamePrefix staff",
            "Query.occupantsByStoreId customer", "Query.occupantsByStoreId staff",
            "Query.occupantsByTypedNamePrefix customer", "Query.occupantsByTypedNamePrefix staff",
            "Query.occupantsStartingWithM customer", "Query.occupantsStartingWithM staff",
            "Query.presenceDetail presence_detail", "Query.presenceDetailNode presence_detail",
            "Query.presenceDetailRequired presence_detail", "Query.presenceHostRefs presence_host",
            "Query.presenceHosts presence_host", "Query.rentalsByInventoryItem rental",
            "Query.splitFilterParentsByTargetLabel split_filter_parent", "Query.stockByLanguage film",
            "Query.stockByLanguage inventory", "Query.xlatChildByParentId xlat_child",
            "Query.xlatChildrenByFilter xlat_child", "Query.xlatChildrenByParentIds xlat_child",
            "Query.xlatCompChildrenByParentIds xlat_comp_child", "SessionStore.customersOfPrincipal customer",
            "Store.customersByFirstName customer", "Store.customersByFirstNameSplit customer",
            "Store.customersByNodeId customer", "Store.customersByNodeIdSplit customer",
            "Store.customersSeenByUser customer");
        assertThat(census.get("CONSUMED")).as("fields whose call or write takes their arguments").hasSize(45);
        // Each line goes as its shape is emitted from the store; a field moving between lines is a
        // change to look at, not to re-pin.
        assertThat(census)
            .containsEntry("AUTHORED_NODE_ID", List.of("Query.customersByAddressDistrict customer", "Query.customersByAddressDistrictActive customer",
                "Query.customersByMultiFieldFilter customer", "Query.filmsByOptionalLanguage film",
                "Query.projectNotesByPlainFilter project_note",
                "Query.projectNotesByPlainFilterConnection project_note",
                "Query.projectNotesByProject project_note", "Query.stockByLanguageOverride film",
                "Query.stockByLanguageOverride inventory", "Store.customersByAddressDistrict customer",
                "Store.customersByAddressDistrictSplit customer"))
            .containsEntry("NODE_ID_PARTICIPANT", List.of("Query.occupantById customer", "Query.occupantById staff", "Query.occupantByOptionalId customer",
                "Query.occupantByOptionalId staff", "Query.occupantsByIds customer", "Query.occupantsByIds staff",
                "Query.occupantsByIdsConnection customer", "Query.occupantsByIdsConnection staff",
                "Query.occupantsByNullableIds customer", "Query.occupantsByNullableIds staff"))
            .containsEntry("NOTHING", List.of("Film.actors actor", "Film.actorsBySplitLookup actor", "Film.actorsOrderedBySplitLookup actor",
                "Film.filmDetails.actorsByLookup actor", "Film.info.castByKey actor",
                "Film.inlineBundle.actorsByKey actor", "Film.inlineBundle.actorsByKeyViaJunctionCondition actor",
                "Query.categoryById category", "Query.customerById customer", "Query.filmActorByNodeId film_actor",
                "Query.filmActorsByKey film_actor", "Query.filmById film", "Query.filmDetailsBatch.actorsByLookup actor",
                "Query.filmsByNodeIdArgWithLookupKey film", "Query.inventoryById inventory",
                "Query.languageByKey language", "Query.languageBySingleKey language",
                "Query.presenceDetailByKey presence_detail", "Query.storeById store",
                "RoleHolder.rolesByCode role_assignment"))
            .containsOnlyKeys("AUTHORED_NODE_ID", "CONSUMED", EMITTED, "NODE_ID_PARTICIPANT", "NOTHING");
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
        for (var unit : ProjectionQuery.read(handle()).values()) {
            switch (ProjectionEmitter.emit(unit, "census")) {
                case ProjectionEmitter.Result.Emitted e -> {
                    emitted.add(unit.type());
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
        EmittedCodeHarness.compileStrictly(units).close();
        java.util.Collections.sort(emitted);
        refused.values().forEach(java.util.Collections::sort);
        assertThat(emitted).as("the units the store emits").containsExactly("ActorCondMember", "ActorNode",
            "Catalogue", "CatalogueItem", "CatalogueShelf", "CategoryLabel", "CategoryNode", "Content",
            "ConverterCampusTerm", "ConverterIdChange", "ConverterSite", "DisplayAccount", "DisplayPerson",
            "DivergedCampusRef", "DivergedChildRef", "DivergedLabelSide", "DivergedOrgSide", "Email", "FanDetail",
            "FanMark", "FanTarget", "FilmBookmark", "FilmCategoryNode", "FilmEndorsement", "FilmEndorsementNode",
            "HostLabelsRow", "InvCondMember", "InventoryItem", "InventoryUnit", "KeyedNode", "LanguageAlias",
            "LanguageNode", "LevelInterval", "Mailbox", "PagedA", "PagedB", "Party", "PaymentLegacyProbe",
            "PresenceDetail", "PresenceDetailNode", "PresenceItem", "ProjectEvent", "ProjectNote", "Rental",
            "RoleAssignment", "SecureNote", "SessionCustomer", "ShortContent", "SplitFilterTarget", "SplitKeyChild",
            "SplitKeyParent", "SplitParentTag", "SquadMember", "Staff", "StockFilm", "StockInventory", "StorageBin",
            "StoreLocation", "StoreManager", "StudyLevel", "Subject", "ValidityPeriod", "XlatChild", "XlatCompChild",
            "XlatCompParent", "XlatParent");
        // Each line goes as its fact lands; an occurrence moving between lines is a change to look at,
        // not to re-pin.
        assertThat(refused)
            .containsEntry("EXPRESSION", List.of("ComputedTitleFilm.title", "FanLabelled.label",
                "FanLabelledAlpha.label", "FanLabelledBeta.label", "Film.isEnglish", "Film.titleByName",
                "HostLabelsComputedRow.nb", "HostLabelsComputedRow.nn", "HostLanguageNames.englishName",
                "HostLanguageNames.italianName", "Inventory.filmCardData", "Inventory.filmCardDataMaybeMissing",
                "Inventory.filmRef"))
            .containsEntry("NULL_CONTRACT", List.of("Customer.location", "FanAlpha.alphaPlacement",
                "FanBeta.betaPlacement", "Film.blurb", "Film.detailsForMethod", "Film.filmDetails", "Film.info",
                "Film.inlineBundle", "Film.original", "Film.projected", "Film.sameRowNested", "Film.spoken",
                "Film.summary", "PivotNestingHost.labels", "PivotNestingHost.labelsComputed",
                "PivotNestingHost.labelsMixed", "PivotNestingHost.origin", "PivotNestingHost.texts",
                "PresenceHost.detailRefs", "PresenceHost.holdings", "PresenceHost.holdingsSplit", "Store.location"))
            .containsEntry("CHILD_PRODUCER_COLUMNS", List.of("Actor.filmsSplit", "Actor.recentFilmsSplit",
                "ActorMember.films", "Address.occupants", "Address.occupantsConnection", "Category.childRefs",
                "Category.parentRef", "City.cityLowercase", "City.cityUppercase", "ConverterCampus.organisation",
                "ConverterOrg.campuses", "Customer.addressSplit", "Customer.storeAddressSplit",
                "DivergedRefChild.polyOrg", "DivergedRefChild.splitOrganisation", "DivergedRefOrg.polyChildren",
                "DivergedRefOrg.splitChildren", "FanAlpha.targetLoaded", "FanBeta.targetLoaded",
                "FanOwner.fanItemsConnection", "Film.actorsByCondition", "Film.actorsBySplitLookup",
                "Film.actorsBySplitLookupGenerated", "Film.actorsConnection", "Film.actorsOrderedBySplit",
                "Film.actorsOrderedBySplitLookup", "Film.actorsOrderedConnection", "Film.actorsViaJunctionCondition",
                "Film.castByCondition", "Film.castMembers", "Film.copiesAtStores", "Film.databaseError",
                "Film.durabilityError", "Film.filmContent", "Film.filmContents", "Film.filmContentsByFilmTitle",
                "Film.filmContentsConnection", "Film.firstByCondition", "Film.firstMemberViaJunction",
                "Film.languageByService", "Film.membersViaJunction", "Film.membersViaJunctionConnection",
                "Film.modifiedLanguage", "Film.sameRowSplit", "Film.sharedTranslations", "Film.titleCheck",
                "Film.titleLowercase", "Film.titleTitlecase", "Film.titleUppercase", "FilmInventory.films",
                "Language.films", "Language.filmsByLatestStock", "Language.filmsByMixedFilterSplit",
                "Language.filmsByOriginalLanguage", "Language.filmsByRentalRate",
                "Language.filmsListByOriginalLanguage", "Language.filmsListByOriginalLanguageDesc",
                "Language.filmsSplitByOriginalLanguageList", "PivotNestingHost.languageNamesSplit",
                "PresenceHost.detailSplit", "Project.items", "RoleHolder.roles", "RoleHolder.rolesByCode",
                "SplitFilterParent.targetSplit", "SplitParent.tags", "Store.contact",
                "Store.customersByAddressDistrictSplit", "Store.customersByFirstNameSplit",
                "Store.customersByNodeIdSplit", "Store.customersSeenByUser", "Store.manager"))
            .containsEntry("INLINE_CHILD", List.of("Actor.films", "Actor.filmsNested", "Actor.recentFilms",
                "Actor.recentFilmsInferred", "Category.children", "Category.parent", "Customer.address",
                "Customer.addressByCondition", "Customer.storeAddress", "DivergedRefChild.organisation",
                "DivergedRefOrg.children", "FanAlpha.details", "FanAlpha.marks", "FanAlpha.target", "FanBeta.details",
                "FanBeta.marks", "FanBeta.target", "FanItem.details", "Film.Length", "Film.actors",
                "Film.actorsGenerated", "Film.castActorsViaChain", "Film.castFilms", "Film.castRecentFilms",
                "Film.sameRow", "FilmActor.actor", "FilmSameRow.film", "FilmSameRow.language",
                "HostLabelsWithFilms.languageFilms", "Language.filmsByMixedFilter", "Language.filmsInlineByLatestStock",
                "Language.filmsInlineByOriginalLanguage", "Language.filmsInlineByOriginalLanguageDesc",
                "PivotNestingHost.labelsComputedRow", "PivotNestingHost.labelsRow", "PivotNestingHost.labelsWithFilms",
                "PivotNestingHost.languageNames", "PresenceHost.detail", "PresenceHost.detailNode",
                "PresenceHost.detailRequired", "PresenceHostRefs.detail", "SessionStore.customersOfPrincipal",
                "SplitFilterParent.targetInline", "Store.customers", "Store.customersByAddressDistrict",
                "Store.customersByFirstName", "Store.customersByNodeId", "Store.customersFirstN"))
            .containsEntry("PIVOT", List.of("Film.prices", "Film.pricesSplit", "Film.taglineTranslations",
                "Film.titleTranslations", "Film.titleTranslationsSplit", "FilmActor.noteTranslations"))
            .containsEntry("SCALAR_PATH", List.of("AppAccount.displayName", "Company.displayName",
                "Customer.addressNodeId", "Customer.districtByCondition", "FanAlpha.note", "Film.languageName",
                "Film.soleActorFirstName", "FilmContent.rating", "FilmSameRow.languageName", "Individual.displayName",
                "Inventory.filmTextRating", "Person.displayName"))
            .containsOnlyKeys("CHILD_PRODUCER_COLUMNS", "EXPRESSION", "INLINE_CHILD", "NULL_CONTRACT", "PIVOT",
                "SCALAR_PATH");
    }

    /**
     * A refusal the call emitter makes on its own, past every exclusion the census reads off the
     * store, is censused as one: a service under an object taking no keys is not emitted here, and
     * the generator leaves it to the classified model rather than failing on it.
     */
    @Test
    void aBodyTheCallEmitterRefusesIsCensusedAsRefused() throws Exception {
        String sdl = """
            type Film @table(name: "film") {
              title: String
              greeting: String @service(service: {
                className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService", method: "greet"},
                contextArguments: ["userId"])
            }
            type Query { films: [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, sdl,
                CatalogInput.of(testContext()), TestSchemaHelper.sakilaExampleClasspath())) {
            var services = ServiceEmission.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH));
            assertThat(services.census()).containsEntry("Film.greeting", "CALL_REFUSED");
            assertThat(services.emits("Film.greeting")).isFalse();
        }
    }
}
