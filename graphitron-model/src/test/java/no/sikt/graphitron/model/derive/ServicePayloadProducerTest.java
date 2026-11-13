package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A table-bound child of a payload a service hands back re-reads by the keys the service's record
 * carries, so it is a producer of its own with nothing authored at it. The payload's backing is the
 * premise rather than the scenery: it is what keeps the rule from joining to a relation nothing in a
 * real pipeline populates in the shape it reads.
 */
class ServicePayloadProducerTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String PRODUCER =
        "no.sikt.graphitron.rewrite.test.services.HandedFilmService";

    @Test
    void aTableChildOfAServicesPayloadIsAProducer() {
        String sdl = """
            type Query {
                payload: Payload @service(service: {className: "%s", method: "make"})
            }
            type Payload {
                name: String
                film: Film
            }
            type Film @table(name: "film") { title: String }
            """.formatted(PRODUCER);
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, CatalogInput.of(TestRunContext.of()),
                ClasspathCorpus.entries())) {
            var dsl = store.dsl();
            assertThat(dsl.select(GRAPHITRON_TYPE_BACKING.CLASS_NAME, GRAPHITRON_TYPE_BACKING.DECLARED_VIA)
                .from(GRAPHITRON_TYPE_BACKING)
                .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq("Payload"))
                .fetch(r -> r.value1() + " " + r.value2()))
                .as("the premise: the backing the rule joins to is a writer's row, not a fixture's")
                .containsExactly("no.sikt.graphitron.rewrite.test.services.HandedFilm PRODUCER");
            assertThat(dsl.select(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME,
                    GRAPHITRON_PRODUCER.KIND)
                .from(GRAPHITRON_PRODUCER)
                .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_PRODUCER.TYPE_NAME.eq("Payload"))
                .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3()))
                .containsExactly("Payload.film LOOKUP");
        }
    }
}
