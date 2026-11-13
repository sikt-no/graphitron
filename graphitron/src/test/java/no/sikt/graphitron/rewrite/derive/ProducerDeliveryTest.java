package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields {@code graphitron_producer} says run a statement of their own, against the walk's own
 * account of which fields leave their parent's statement.
 *
 * <p>The walk's gathered delivery relation ({@link GatheredFacts#delivery()}) reads the split and
 * fan-out markers off the assembled schema, independently of the captured rows the producer rule
 * reads, and every field it names is a {@code SELECT} producer. It goes when the walk does.
 *
 * <p>What the producer rule makes of given rows is stated in the fact documents
 * {@code fetcher-producers.graphqls}, {@code producer-out-of-the-parent-statement.graphqls} and
 * {@code producer-root-by-binding.graphqls}.
 */
@PipelineTier
class ProducerDeliveryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

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
        var producers = FACTS.readInline("producer-delivery", sdl, jooq(),
            graph -> producersOf(graph.handle().dsl(), graph.handle().graphName(), "Film"));
        var delivered = gather(sdl).delivery().rows().stream()
            .filter(r -> r.splitQuery() || r.tenantFanOut())
            .map(r -> r.parentTypeName() + "." + r.fieldName())
            .toList();
        assertThat(delivered)
            .as("the fixture authors both markers, so the comparison is not vacuous")
            .containsExactlyInAnyOrder("Film.languageSplit", "Film.fannedLanguages");
        assertThat(producers)
            .containsExactlyInAnyOrderElementsOf(delivered.stream()
                .map(coordinate -> coordinate + " SELECT")
                .toList());
    }

    // ===== Helpers =====

    /** The producers a type declares, as {@code Type.field KIND}. */
    private static List<String> producersOf(DSLContext dsl, String graphName, String typeName) {
        return dsl.select(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME,
                GRAPHITRON_PRODUCER.KIND)
            .from(GRAPHITRON_PRODUCER)
            .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_PRODUCER.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_PRODUCER.FIELD_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3());
    }

    private static GatheredFacts gather(String sdl) {
        var nodes = TestSchemaHelper.nodeDeclaration();
        return GatheredFacts.gather(TestSchemaHelper.walkBundle(sdl).assembled(),
            (s, v) -> SchemaReachability.walk(s, nodes, v));
    }

    private static CatalogInput jooq() {
        var ctx = testContext();
        return CatalogInput.of(ctx);
    }
}
