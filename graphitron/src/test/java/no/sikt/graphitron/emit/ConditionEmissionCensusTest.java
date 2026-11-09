package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the sakila example's field occurrences the store emits a condition for, and why each of
 * the rest is not emitted yet: a census the condition flip moves through, one reason at a time. Every
 * condition emitted compiles.
 */
@PipelineTier
class ConditionEmissionCensusTest {

    private static final String EMITTED = "EMITTED";

    @TempDir
    Path tmp;

    @Test
    void theCensusOfTheExampleSchemasConditions() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        List<ConditionQuery.Row> rows;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, sdl,
                CatalogInput.of(testContext()), sakilaClasspath())) {
            rows = ConditionQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
        var census = new TreeMap<String, List<String>>();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        int n = 0;
        for (var row : rows) {
            var key = row.coordinate() + " " + row.table().sqlName();
            switch (ConditionEmitter.emit(row, "condition" + n++)) {
                case ConditionEmitter.Result.Emitted emitted -> {
                    census.computeIfAbsent(EMITTED, k -> new ArrayList<>()).add(key);
                    type.addMethod(emitted.method());
                }
                case ConditionEmitter.Result.Nothing ignored ->
                    census.computeIfAbsent("NOTHING", k -> new ArrayList<>()).add(key);
                case ConditionEmitter.Result.NotYet notYet ->
                    census.computeIfAbsent(notYet.reason().name(), k -> new ArrayList<>()).add(key);
            }
        }
        EmittedCodeHarness.compileStrictly(Map.of("census.Conditions", type.build())).close();
        assertThat(census.get(EMITTED)).as("the conditions the store emits").containsExactly(
            "Film.actorsBySplitLookupGenerated actor", "Film.actorsGenerated actor",
            "Query.addressesByCountryName address", "Query.citiesByCountryFilter city",
            "Query.citiesByCountryName city", "Query.citiesFaceted city", "Query.computedTitleFilm film",
            "Query.converterCampusesByOrgCode converter_campus",
            "Query.converterCampusesByOrgCodes converter_campus", "Query.customerByPk customer",
            "Query.customers customer", "Query.customersByCityFilter customer",
            "Query.customersByCityId customer", "Query.customersByConditionDistrict customer",
            "Query.customersByTwoNames customer", "Query.fanItem fan_base",
            "Query.filmActorsFaceted film_actor", "Query.filmsByBridgedActorFirstName film",
            "Query.filmsByEffectiveNullability film", "Query.filmsByJunctionActorId film",
            "Query.filmsByOneOf film", "Query.filmsWithImplicitInput film",
            "Query.filmsWithMultiImplicit film", "Query.filmsWithNestedImplicit film",
            "Query.languagesByKeyGenerated language", "Query.occupantsByName customer",
            "Query.occupantsByName staff", "Query.occupantsByNameConnection customer",
            "Query.occupantsByNameConnection staff", "Query.occupantsByStoreId customer",
            "Query.occupantsByStoreId staff", "Query.presenceDetail presence_detail",
            "Query.presenceDetailNode presence_detail", "Query.presenceDetailRequired presence_detail",
            "Query.presenceHostRefs presence_host", "Query.presenceHosts presence_host",
            "Query.splitFilterParentsByTargetLabel split_filter_parent");
        assertThat(census.get("CONSUMED")).as("fields whose call or write takes their arguments").hasSize(93);
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
            .containsEntry("ENUM", List.of("Query.films film", "Query.filmsFaceted film", "Query.filmsOrderedConnection film"))
            .containsEntry("NODE_ID", List.of("Query.actorsByFilmFilter actor", "Query.actorsByFilmFilterConnection actor",
                "Query.actorsByFilmIds actor", "Query.addressesByCustomerIds address",
                "Query.addressesByCustomerIdsInferred address",
                "Query.converterCampusTermByNodeId converter_campus_term",
                "Query.converterCampusTermsByNodeIds converter_campus_term",
                "Query.converterIdChangeByNodeId converter_id_change",
                "Query.customersByNullableAddressIds customer", "Query.endorsementsByNodeId film_endorsement",
                "Query.filmActorBySingularCompositeNodeId film_actor",
                "Query.filmActorsByCompositeNodeIds film_actor", "Query.filmActorsByNullableNodeIds film_actor",
                "Query.filmsByNodeIdArg film", "Query.filmsByNodeIdArgWithTitleFilter film",
                "Query.filmsByNullableNodeIds film", "Query.filmsBySameTableNodeId film",
                "Query.filmsConnectionByOptionalIds film", "Query.filmsConnectionByRequiredIds film",
                "Query.inventoryItemsByIds inventory", "Query.levelIntervals level_interval",
                "Query.occupantById customer", "Query.occupantById staff", "Query.occupantByOptionalId customer",
                "Query.occupantByOptionalId staff", "Query.occupantsByAddress customer",
                "Query.occupantsByAddress staff", "Query.occupantsByIds customer", "Query.occupantsByIds staff",
                "Query.occupantsByIdsConnection customer", "Query.occupantsByIdsConnection staff",
                "Query.occupantsByNullableIds customer", "Query.occupantsByNullableIds staff",
                "Query.rentalsByInventoryItem rental", "Query.stockByLanguage film",
                "Query.stockByLanguage inventory", "Query.xlatChildByParentId xlat_child",
                "Query.xlatChildrenByFilter xlat_child", "Query.xlatChildrenByParentIds xlat_child",
                "Query.xlatCompChildrenByParentIds xlat_comp_child", "Store.customersByNodeId customer",
                "Store.customersByNodeIdSplit customer"))
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
            .containsOnlyKeys("AUTHORED", "CONSUMED", EMITTED, "ENUM", "NODE_ID", "NOTHING", "THROUGH_LIST");
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
