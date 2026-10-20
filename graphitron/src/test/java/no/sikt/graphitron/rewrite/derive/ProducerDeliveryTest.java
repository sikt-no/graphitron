package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields {@code graphitron_producer} says run a statement of their own, against the walk's own
 * account of which fields leave their parent's statement.
 *
 * <p>Two questions. The first is agreement: the walk's gathered delivery relation
 * ({@link GatheredFacts#delivery()}) reads the split and fan-out markers off the assembled schema,
 * independently of the captured rows the producer rule reads, and every field it names is a
 * {@code SELECT} producer. The second is reach: a table-bound child of a payload a service hands
 * back is a producer, the payload's backing being a row the graphitron gatherer anchored from a
 * captured census rather than anything an author wrote.
 *
 * <p>What the producer rule makes of given rows is stated in the fact documents
 * {@code fetcher-producers.graphqls}, {@code producer-out-of-the-parent-statement.graphqls} and
 * {@code producer-root-by-binding.graphqls}.
 */
@PipelineTier
class ProducerDeliveryTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String PRODUCER =
        "no.sikt.graphitron.rewrite.test.services.HandedFilmService";

    /**
     * Every field the walk delivers apart is a {@code SELECT} producer, and the inlined sibling and the
     * column are not producers at all, so an over-reaching rule fails as loudly as a missing one.
     */
    @Test
    void theFieldsTheWalkDeliversApartAreSelectProducers() {
        String sdl = """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                title: String
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                languageInline: Language @reference(path: [{key: "film_language_id_fkey"}])
                fannedLanguages: [Language!]! @tenantFanOut
            }
            type Query { films: [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var dsl = store.dsl();
            var delivered = gather(sdl).delivery().rows().stream()
                .filter(r -> r.splitQuery() || r.tenantFanOut())
                .map(r -> r.parentTypeName() + "." + r.fieldName())
                .toList();
            assertThat(delivered)
                .as("the fixture authors both markers, so the comparison is not vacuous")
                .containsExactlyInAnyOrder("Film.languageSplit", "Film.fannedLanguages");
            assertThat(producersOf(dsl, "Film"))
                .containsExactlyInAnyOrderElementsOf(delivered.stream()
                    .map(coordinate -> coordinate + " SELECT")
                    .toList());
        }
    }

    /**
     * A table-bound child of a payload a service hands back re-reads by the keys the service's
     * record carries, so it is a producer of its own with nothing authored at it. The payload's
     * backing is the premise rather than the scenery: it is what keeps the rule from joining to a
     * relation nothing in a real pipeline populates in the shape it reads.
     */
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
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, jooq(),
                ClasspathCorpus.entries())) {
            var dsl = store.dsl();
            assertThat(dsl.select(GRAPHITRON_TYPE_BACKING.CLASS_NAME, GRAPHITRON_TYPE_BACKING.DECLARED_VIA)
                .from(GRAPHITRON_TYPE_BACKING)
                .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq("Payload"))
                .fetch(r -> r.value1() + " " + r.value2()))
                .as("the premise: the backing the rule joins to is a writer's row, not a fixture's")
                .containsExactly("no.sikt.graphitron.rewrite.test.services.HandedFilm PRODUCER");
            assertThat(producersOf(dsl, "Payload")).containsExactly("Payload.film LOOKUP");
        }
    }

    // ===== Helpers =====

    /** The producers a type declares, as {@code Type.field KIND}. */
    private static List<String> producersOf(DSLContext dsl, String typeName) {
        return dsl.select(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME,
                GRAPHITRON_PRODUCER.KIND)
            .from(GRAPHITRON_PRODUCER)
            .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHITRON_PRODUCER.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_PRODUCER.FIELD_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3());
    }

    private static GatheredFacts gather(String sdl) {
        var nodes = TestSchemaHelper.nodeDeclaration();
        return GatheredFacts.gather(TestSchemaHelper.buildBundle(sdl).assembled(),
            (s, v) -> SchemaReachability.walk(s, nodes, v));
    }

    private static CatalogInput jooq() {
        var ctx = testContext();
        return CatalogInput.of(ctx);
    }
}
