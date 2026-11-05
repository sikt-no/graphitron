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
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LiftQuery}'s rows over a captured schema: a service whose target binds the table its
 * records belong to lifts them, one record or a list, at a root or under an object, keyed by the
 * table's primary key; a service whose records go down to a payload's data field, or which returns
 * a scalar, lifts nothing.
 */
@PipelineTier
class LiftQueryTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          languageByService: Language
            @service(service: {className: "%1$sFilmService", method: "languageByService"})
        }
        type Language @table(name: "language") { name: String }
        type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
        type FilmsPayload { films: [Film] }
        type Query {
          filmsByIds(ids: [Int!]!): [Film!]!
            @service(service: {className: "%1$sFilmCarrierService", method: "filmsByIds"})
          filmById(id: Int!): Film
            @service(service: {className: "%1$sFilmCarrierService", method: "filmById"})
          filmActorsByIds(ids: [Int!]!): [FilmActor!]!
            @service(service: {className: "%1$sFilmActorCarrierService", method: "filmActorsByIds"})
          filmsAsPayload(ids: [Int!]!): FilmsPayload
            @service(service: {className: "%1$sFilmCarrierService", method: "filmsByIds"})
          filmCount: Int
            @service(service: {className: "%1$sSampleQueryService", method: "filmCount"})
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static Map<String, LiftQuery.Row> rows;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            rows = LiftQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).stream()
                .collect(Collectors.toMap(LiftQuery.Row::coordinate, Function.identity()));
        }
    }

    @Test
    void aListOfRecordsOfTheBoundTableIsLiftedAtTheRoot() {
        var row = rows.get("Query.filmsByIds");
        assertThat(row).isNotNull();
        assertThat(row.list()).isTrue();
        assertThat(row.root()).isTrue();
        assertThat(row.targetType()).isEqualTo("Film");
        assertThat(row.table().sqlName()).isEqualTo("film");
        assertThat(row.table().javaFieldName()).isEqualTo("FILM");
        assertThat(row.key()).extracting(LiftQuery.KeyColumn::sqlName).containsExactly("film_id");
    }

    @Test
    void oneRecordIsLiftedAsOne() {
        var row = rows.get("Query.filmById");
        assertThat(row).isNotNull();
        assertThat(row.list()).isFalse();
    }

    @Test
    void aCompositeKeyIsReadInKeyOrder() {
        assertThat(rows.get("Query.filmActorsByIds").key())
            .extracting(LiftQuery.KeyColumn::sqlName)
            .containsExactly("actor_id", "film_id");
    }

    @Test
    void aServiceUnderAnObjectLiftsToo() {
        var row = rows.get("Film.languageByService");
        assertThat(row).isNotNull();
        assertThat(row.root()).isFalse();
        assertThat(row.table().sqlName()).isEqualTo("language");
    }

    @Test
    void recordsForAPayloadsDataFieldOrAScalarAreNoLift() {
        assertThat(rows).doesNotContainKeys("Query.filmsAsPayload", "Query.filmCount");
        assertThat(List.copyOf(rows.keySet())).containsExactlyInAnyOrder(
            "Query.filmsByIds", "Query.filmById", "Query.filmActorsByIds", "Film.languageByService");
    }
}
