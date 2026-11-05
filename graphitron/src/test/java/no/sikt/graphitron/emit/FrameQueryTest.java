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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FrameQuery}'s rows over a captured schema: a service at a root has a frame with no parent;
 * one under an object takes its key off its parent's table, the table's primary key in key order;
 * one whose result is an outcome names the outcome and its errors field.
 */
@PipelineTier
class FrameQueryTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          titleUppercase: String
            @service(service: {className: "%1$sFilmService", method: "titleUppercase"})
        }
        type FilmActor @table(name: "film_actor") {
          lastUpdate: String @field(name: "last_update")
        }
        type Query {
          filmCount: Int
            @service(service: {className: "%1$sSampleQueryService", method: "filmCount"})
          films: [Film!]!
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static Map<String, FrameQuery.Row> rows;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            rows = FrameQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).stream()
                .collect(Collectors.toMap(FrameQuery.Row::coordinate, Function.identity()));
        }
    }

    @Test
    void aServiceAtARootHasNoParent() {
        var row = rows.get("Query.filmCount");
        assertThat(row).isNotNull();
        assertThat(row.root()).isTrue();
        assertThat(row.declaringType()).isEqualTo("Query");
        assertThat(row.outcome()).isEmpty();
    }

    @Test
    void aServiceUnderAnObjectTakesItsParentsKey() {
        var row = rows.get("Film.titleUppercase");
        assertThat(row).isNotNull();
        assertThat(row.root()).isFalse();
        assertThat(row.declaringType()).isEqualTo("Film");
        var parent = row.parent().orElseThrow();
        assertThat(parent.sqlName()).isEqualTo("film");
        assertThat(parent.javaFieldName()).isEqualTo("FILM");
        assertThat(parent.key()).extracting(FrameQuery.KeyColumn::sqlName).containsExactly("film_id");
    }

    @Test
    void onlyServicesHaveAFrameHere() {
        assertThat(rows).doesNotContainKey("Query.films");
    }
}
