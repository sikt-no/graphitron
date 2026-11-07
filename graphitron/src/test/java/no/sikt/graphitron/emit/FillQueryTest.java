package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FillQuery}'s rows over a captured schema: each call's fills in position order, an argument
 * fill's landing as the names from its argument down with the kind of type found there, and a call
 * whose every position is filled marked complete.
 */
@PipelineTier
class FillQueryTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          titleUppercase: String
            @service(service: {className: "%1$sFilmService", method: "titleUppercase"})
        }
        input FilmIdsInput { ids: [Int!] }
        input FilmDraftIn { title: String }
        input FilmIdItem { id: Int! }
        input FilmIdsByItemInput { items: [FilmIdItem!]! }
        type FilmDraftsCountedPayload { count: Int }
        type Query {
          films: [Film!]!
          callFilms(ids: [Int!]): [Film]
            @service(service: {className: "%1$sSampleQueryService", method: "filmsByService"})
          callFilmsByPath(input: FilmIdsInput): [Film]
            @service(service: {className: "%1$sSampleQueryService", method: "filmsByPath",
                               argMapping: "filmIds: input.ids"})
          callFilmsByListPath(input: FilmIdsByItemInput!): [Film]
            @service(service: {className: "%1$sSampleQueryService", method: "filmsByListPath",
                               argMapping: "filmIds: input.items.id"})
          callGreeting: String @service(service: {className: "%1$sUserGreetingService",
                                                  method: "greet"}, contextArguments: ["userId"])
          countDrafts(in: [FilmDraftIn!]!): FilmDraftsCountedPayload
            @service(service: {className: "%1$sFilmRecordDraftService", method: "count"})
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static Map<String, FillQuery.Row> rows;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            rows = FillQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).stream()
                .collect(Collectors.toMap(FillQuery.Row::coordinate, Function.identity()));
        }
    }

    @Test
    void aCallsFillsComeInPositionOrderWithTheirParameterTypes() {
        var row = rows.get("Query.callFilms");
        assertThat(row.complete()).isTrue();
        assertThat(row.fieldCoordinate()).isEqualTo("Query.callFilms");
        assertThat(row.fills()).extracting(FillQuery.Fill::position, FillQuery.Fill::fill,
                FillQuery.Fill::parameterType)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, "DSL_CONTEXT", "org.jooq.DSLContext"),
                org.assertj.core.groups.Tuple.tuple(1, "ARGUMENT", "java.util.List<java.lang.Integer>"));
        assertThat(row.fills().get(1).landing())
            .contains(new FillQuery.Landing(List.of("ids"), Set.of(0), "SCALAR", false));
    }

    @Test
    void anInputFieldsLandingIsThePathFromItsArgument() {
        assertThat(rows.get("Query.callFilmsByPath").fills().get(1).landing())
            .contains(new FillQuery.Landing(List.of("input", "ids"), Set.of(1), "SCALAR", false));
    }

    @Test
    void aLandingSaysWhichStepsAreLists() {
        assertThat(rows.get("Query.callFilmsByListPath").fills().get(1).landing())
            .contains(new FillQuery.Landing(List.of("input", "items", "id"), Set.of(1), "SCALAR", false));
    }

    @Test
    void anInputObjectsLandingSaysSoForBuildingToTake() {
        assertThat(rows.get("Query.countDrafts").fills().getFirst().landing())
            .contains(new FillQuery.Landing(List.of("in"), Set.of(0), "INPUT_OBJECT", false));
    }

    @Test
    void aContextKeyAndASourceNameNoLanding() {
        var greeting = rows.get("Query.callGreeting").fills().getFirst();
        assertThat(greeting.fill()).isEqualTo("CONTEXT_KEY");
        assertThat(greeting.contextKey()).isEqualTo("userId");
        assertThat(greeting.landing()).isEqualTo(Optional.empty());
        assertThat(rows.get("Film.titleUppercase").fills().getFirst().fill()).isEqualTo("SOURCE");
    }
}
