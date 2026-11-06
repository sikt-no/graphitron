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
 * {@link CallQuery}'s rows over a captured schema: a static method called on its class, an instance
 * method on an object made with the constructor taking the jOOQ context, and under an object the
 * parameter the batch's keys go into, with the type the method answers.
 */
@PipelineTier
class CallQueryTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          titleLowercase: String
            @service(service: {className: "%1$sFilmService", method: "titleLowercase"})
        }
        type Query {
          films: [Film!]!
          filmCount: Int @service(service: {className: "%1$sSampleQueryService", method: "filmCount"})
          titleByFilmIdViaInstance(filmId: Int!): String
            @service(service: {className: "%1$sSampleInstanceService", method: "titleByFilmId"})
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static Map<String, CallQuery.Row> rows;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            rows = CallQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).stream()
                .collect(Collectors.toMap(CallQuery.Row::coordinate, Function.identity()));
        }
    }

    @Test
    void aStaticMethodIsCalledOnItsClass() {
        var row = rows.get("Query.filmCount");
        assertThat(row.isStatic()).isTrue();
        assertThat(row.className()).isEqualTo(SERVICES + "SampleQueryService");
        assertThat(row.resultType()).isEqualTo("java.lang.Integer");
        assertThat(row.source()).isEmpty();
    }

    @Test
    void anInstanceMethodRunsOnAnObjectMadeWithTheContext() {
        var row = rows.get("Query.titleByFilmIdViaInstance");
        assertThat(row.isStatic()).isFalse();
        assertThat(row.holderTakesDsl()).isTrue();
    }

    @Test
    void aBatchedCallNamesTheParameterItsKeysGoInto() {
        var row = rows.get("Film.titleLowercase");
        assertThat(row.source()).hasValueSatisfying(source -> {
            assertThat(source.position()).isZero();
            assertThat(source.parameterType()).isEqualTo("java.util.Set<org.jooq.Row1<java.lang.Integer>>");
        });
        assertThat(row.resultType()).startsWith("java.util.Map<org.jooq.Row1<java.lang.Integer>");
    }
}
