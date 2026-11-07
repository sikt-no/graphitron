package no.sikt.graphitron.emit;

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
 * Which of the sakila example's services the store emits, and why each of the rest stays with the
 * classified model's emitters: a census the flip moves through, one exclusion at a time.
 */
@PipelineTier
class ServiceEmissionCensusTest {

    @TempDir
    Path tmp;

    @Test
    void theCensusOfTheExampleSchemasServices() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        Map<String, String> census;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, sdl,
                CatalogInput.of(testContext()), sakilaClasspath())) {
            census = ServiceEmission.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).census();
        }
        var byVerdict = new TreeMap<String, List<String>>(census.entrySet().stream().collect(
            Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList()))));
        byVerdict.values().forEach(java.util.Collections::sort);
        assertThat(byVerdict.get(ServiceEmission.EMITTED)).as("the services the store emits").hasSize(77);
        // Each line goes as its shape is emitted from the store; a service moving between lines is
        // a change to look at, not to re-pin.
        assertThat(byVerdict).containsEntry("UNDER_AN_OBJECT", List.of("City.cityLowercase", "City.cityUppercase",
                "Film.castMembers", "Film.durabilityError", "Film.languageByService", "Film.modifiedLanguage",
                "Film.sharedTranslations", "Film.titleCheck", "Film.titleLowercase", "Film.titleTitlecase",
                "Film.titleUppercase", "Query.filmDetailsBatch.titleTitlecase", "Query.filmKeySummaries.keyCensus"))
            .containsEntry("NODE_ID_DECODE", List.of("Mutation.assignFilmActorRecord", "Mutation.assignFilmRecord",
                "Mutation.assignInventoryRecord", "Mutation.assignLanguagePair", "Mutation.assignOccupantByArgument",
                "Query.filmActorsByIds", "Query.filmsByNodeIdService"))
            .containsEntry("RECORD_BUILD", List.of("Mutation.agreeStorageBin", "Mutation.describeEmailReply",
                "Mutation.describeEndorsement", "Mutation.endorseFilm", "Mutation.modifyFilmActorRecord",
                "Mutation.modifyFilmRecord", "Mutation.modifyFilmRecords", "Mutation.staffAliasUpsert",
                "Mutation.upsertAddress"))
            .containsEntry("VALIDATION", List.of("Mutation.countFilmBundleDraft", "Mutation.countFilmDrafts",
                "Mutation.countFilmRecordDraft", "Mutation.countFilmRecordDrafts", "Query.filmLookupRefused",
                "Query.filmLookupValidated"))
            .containsEntry("ARGUMENT_PATH", List.of("Query.filmsByListPath", "Query.filmsByNestedListPath",
                "Query.filmsByPath"))
            .containsEntry("DISCRIMINATED", List.of("Mutation.contentSearchManyMutation", "Query.contentSearchMany",
                "Query.contentSearchOne"))
            .containsEntry("POLYMORPHIC", List.of("Mutation.searchManyMutation", "Query.searchManyService",
                "Query.searchOneService"))
            .containsOnlyKeys(ServiceEmission.EMITTED, "UNDER_AN_OBJECT", "NODE_ID_DECODE", "RECORD_BUILD",
                "VALIDATION", "ARGUMENT_PATH", "DISCRIMINATED", "POLYMORPHIC");
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
