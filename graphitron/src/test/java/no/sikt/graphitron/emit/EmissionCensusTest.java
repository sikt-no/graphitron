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
import no.sikt.graphitron.model.config.ClasspathEntry;
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
    private static final NodeIdDecodeEmitter.Runtime RUNTIME = new NodeIdDecodeEmitter.Runtime(
        ClassName.get("census.util", NodeIdEncoderClassGenerator.CLASS_NAME), "census");

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
        sakila = CapturedStore.ofCatalogWith(shared, CapturedStore.GRAPH, sdl,
            CatalogInput.of(testContext()), sakilaClasspath());
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
        assertThat(byVerdict.get(ServiceEmission.EMITTED)).as("the services the store emits").hasSize(104);
        // Each line goes as its shape is emitted from the store; a service moving between lines is
        // a change to look at, not to re-pin.
        assertThat(byVerdict).containsEntry("RECORD_BUILD", List.of("Film.modifiedLanguage", "Mutation.agreeStorageBin", "Mutation.describeEmailReply",
                "Mutation.describeEndorsement", "Mutation.endorseFilm", "Mutation.modifyFilmActorRecord",
                "Mutation.modifyFilmRecord", "Mutation.modifyFilmRecords", "Mutation.staffAliasUpsert",
                "Mutation.upsertAddress"))
            .containsEntry("BUILD_REFUSED", List.of("Mutation.assignFilmActorRecordList",
                "Mutation.assignFilmRecordList", "Mutation.assignOccupantByArgument", "Mutation.assignOccupantRecord",
                "Mutation.assignOccupantRecordList", "Mutation.customerUpsert", "Query.filmsByNodeIdService"))
            .containsEntry("VALIDATION", List.of("Mutation.countFilmRecordDraft", "Mutation.countFilmRecordDrafts"))
            .containsOnlyKeys(ServiceEmission.EMITTED, "RECORD_BUILD", "BUILD_REFUSED",
                "VALIDATION");
    }


    @Test
    void theCensusOfTheExampleSchemasConditions() throws Exception {
        var rows = ConditionQuery.read(handle());
        var decodes = NodeIdDecodeQuery.read(handle());
        var census = new TreeMap<String, List<String>>();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        int n = 0;
        for (var row : rows) {
            var key = row.coordinate() + " " + row.table().sqlName();
            switch (ConditionEmitter.emit(row, "condition" + n++, decodes, RUNTIME)) {
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
        var schema = TestSchemaHelper.buildSchema("type Query { x: Int }");
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(schema, "census", List.of())) {
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
            "Query.actorsByFilmFilter actor", "Query.actorsByFilmFilterConnection actor",
            "Query.actorsByFilmIds actor", "Query.addressesByCountryName address",
            "Query.addressesByCustomerIds address", "Query.addressesByCustomerIdsInferred address",
            "Query.citiesByCountryFilter city", "Query.citiesByCountryName city", "Query.citiesFaceted city",
            "Query.computedTitleFilm film", "Query.converterCampusesByOrgCode converter_campus",
            "Query.converterCampusesByOrgCodes converter_campus", "Query.customerByPk customer",
            "Query.customers customer", "Query.customersByCityFilter customer",
            "Query.customersByCityId customer", "Query.customersByConditionDistrict customer",
            "Query.customersByNullableAddressIds customer", "Query.customersByTwoNames customer",
            "Query.endorsementsByNodeId film_endorsement", "Query.fanItem fan_base",
            "Query.filmActorBySingularCompositeNodeId film_actor",
            "Query.filmActorsByCompositeNodeIds film_actor", "Query.filmActorsByNullableNodeIds film_actor",
            "Query.filmActorsFaceted film_actor", "Query.films film",
            "Query.filmsByBridgedActorFirstName film", "Query.filmsByEffectiveNullability film",
            "Query.filmsByJunctionActorId film", "Query.filmsByNodeIdArg film",
            "Query.filmsByNodeIdArgWithTitleFilter film", "Query.filmsByNullableNodeIds film",
            "Query.filmsByOneOf film", "Query.filmsBySameTableNodeId film",
            "Query.filmsConnectionByOptionalIds film", "Query.filmsConnectionByRequiredIds film",
            "Query.filmsFaceted film", "Query.filmsOrderedConnection film",
            "Query.filmsWithImplicitInput film", "Query.filmsWithMultiImplicit film",
            "Query.filmsWithNestedImplicit film", "Query.inventoryItemsByIds inventory",
            "Query.languagesByKeyGenerated language", "Query.levelIntervals level_interval",
            "Query.occupantsByName customer", "Query.occupantsByName staff",
            "Query.occupantsByNameConnection customer", "Query.occupantsByNameConnection staff",
            "Query.occupantsByStoreId customer", "Query.occupantsByStoreId staff",
            "Query.presenceDetail presence_detail", "Query.presenceDetailNode presence_detail",
            "Query.presenceDetailRequired presence_detail", "Query.presenceHostRefs presence_host",
            "Query.presenceHosts presence_host", "Query.rentalsByInventoryItem rental",
            "Query.splitFilterParentsByTargetLabel split_filter_parent",
            "Query.xlatChildByParentId xlat_child", "Query.xlatChildrenByFilter xlat_child",
            "Query.xlatChildrenByParentIds xlat_child", "Query.xlatCompChildrenByParentIds xlat_comp_child",
            "Store.customersByNodeId customer", "Store.customersByNodeIdSplit customer");
        assertThat(census.get("CONSUMED")).as("fields whose call or write takes their arguments").hasSize(105);
        // Each line goes as its shape is emitted from the store; a field moving between lines is a
        // change to look at, not to re-pin.
        assertThat(census)
            .containsEntry("AUTHORED", List.of("Film.inlineBundle.languageFiltered language", "Language.filmsByMixedFilter film",
                "Language.filmsByMixedFilterSplit film", "Query.customersByAddressDistrict customer",
                "Query.customersByAddressDistrictActive customer", "Query.customersByDistrictBean customer",
                "Query.customersByMultiFieldFilter customer", "Query.filmsByOptionalLanguage film",
                "Query.filmsByPlainInput film", "Query.filmsBySearchBean film", "Query.filmsByTitleBean film",
                "Query.filmsByTitleBeans film", "Query.filmsNestedInput film",
                "Query.filmsOuterOverrideFilterInput film", "Query.filmsOuterOverridePlainInput film",
                "Query.filmsWithImplicitInputOuterOverride film", "Query.filmsWithInputFieldCondition film",
                "Query.filmsWithInputFieldOverride film", "Query.languagesByKeyFiltered language",
                "Query.occupantsByFilter customer", "Query.occupantsByFilter staff",
                "Query.occupantsByMixedNamePrefix customer", "Query.occupantsByMixedNamePrefix staff",
                "Query.occupantsByNamePrefix customer", "Query.occupantsByNamePrefix staff",
                "Query.occupantsByTypedNamePrefix customer", "Query.occupantsByTypedNamePrefix staff",
                "Query.projectNotesByPlainFilter project_note",
                "Query.projectNotesByPlainFilterConnection project_note",
                "Query.projectNotesByProject project_note", "Query.stockByLanguageOverride film",
                "Query.stockByLanguageOverride inventory", "Store.customersByAddressDistrict customer",
                "Store.customersByAddressDistrictSplit customer"))
            .containsEntry("NODE_ID", List.of("Query.converterCampusTermByNodeId converter_campus_term",
                "Query.converterCampusTermsByNodeIds converter_campus_term",
                "Query.converterIdChangeByNodeId converter_id_change"))
            .containsEntry("NODE_ID_PARTICIPANT", List.of("Query.occupantById customer", "Query.occupantById staff", "Query.occupantByOptionalId customer",
                "Query.occupantByOptionalId staff", "Query.occupantsByAddress customer",
                "Query.occupantsByAddress staff", "Query.occupantsByIds customer", "Query.occupantsByIds staff",
                "Query.occupantsByIdsConnection customer", "Query.occupantsByIdsConnection staff",
                "Query.occupantsByNullableIds customer", "Query.occupantsByNullableIds staff",
                "Query.stockByLanguage film", "Query.stockByLanguage inventory"))
            .containsEntry("NOTHING", List.of("Film.actors actor", "Film.actorsBySplitLookup actor", "Film.actorsOrderedBySplitLookup actor",
                "Film.filmDetails.actorsByLookup actor", "Film.info.castByKey actor",
                "Film.inlineBundle.actorsByKey actor", "Film.inlineBundle.actorsByKeyViaJunctionCondition actor",
                "Query.categoryById category", "Query.customerById customer", "Query.filmActorByNodeId film_actor",
                "Query.filmById film", "Query.filmDetailsBatch.actorsByLookup actor",
                "Query.filmsByNodeIdArgWithLookupKey film", "Query.inventoryById inventory",
                "Query.languageByKey language", "Query.languageBySingleKey language",
                "Query.presenceDetailByKey presence_detail", "Query.storeById store",
                "RoleHolder.rolesByCode role_assignment"))
            .containsEntry("THROUGH_LIST", List.of("Query.customersByAddresses customer", "Query.filmActorsByKey film_actor",
                "Query.filmActorsByPairList film_actor", "Query.filmActorsByPairs film_actor",
                "Query.filmsByCastNames film", "Query.filmsByLanguagePairs film",
                "Query.filmsByOptionalLanguages film", "Query.filmsByOriginalLanguages film"))
            .containsOnlyKeys("AUTHORED", "CONSUMED", EMITTED, "NODE_ID", "NODE_ID_PARTICIPANT", "NOTHING", "THROUGH_LIST");
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
                CatalogInput.of(testContext()), sakilaClasspath())) {
            var services = ServiceEmission.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH));
            assertThat(services.census()).containsEntry("Film.greeting", "CALL_REFUSED");
            assertThat(services.emits("Film.greeting")).isFalse();
        }
    }

    /** The sakila service and catalog modules read as the sakila example reads them. */
    private static List<ClasspathEntry> sakilaClasspath() {
        return TestSchemaHelper.testClasspath().stream()
            .map(e -> e.path().toString().contains("graphitron-sakila-")
                ? new ClasspathEntry(e.path(), ClasspathEntry.Origin.REACTOR, null, null)
                : e)
            .toList();
    }
}
