package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.model.DeliveryFact;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery-fact agreement pin: over every flat classified output coordinate in the corpus
 * plus the marker coverage fixture, the materialized relation's verdict
 * ({@link DeliveryFactRelation}, read through {@link GraphitronSchema#deliveryOf}) must equal
 * the leaf-derived crosswalk ({@link DeliveryFact#leafDerivedOf}). The two sides read the same
 * marker, source-shape and verdict facts during the additive window, so this pins for
 * regression rather than independence, the discipline the keystone's record states for the
 * shared-input member productions; the crosswalk stays the walk-less fallback and the
 * comparison side, never a production source.
 *
 * <p>The domain is the flat index, the relation's own boundary: a nesting type's fields keep
 * the leaf-derived crosswalk and are deliberately outside this scan, per the mixed-reach
 * reasoning on {@link OperationMemberRelation}.
 */
@PipelineTier
class DeliveryFactPinTest {

    /**
     * Marker coverage beside the corpus: an authored split child riding a table parent, so the
     * authored trigger is pinned even where the corpus is thin. The record-handed trigger
     * rides the corpus's DML payload carriers (the record-sourced re-fetch children), and the
     * polymorphic fan-in rides the corpus's batched polymorphic connection; the floors below
     * fail loudly if either corpus population disappears.
     *
     * <p>The two {@code @splitQuery}-marked discriminated interface children are here rather than
     * in the corpus for two reasons. The relation's marker arms read the directive independently
     * of the cardinality fork that decides this shape's delivery, so a marked coordinate is where
     * the two sites could diverge without the plain one noticing; and a marked coordinate carries
     * a redundancy warning, which a {@code @classified} verdict row would have to reconcile.
     * Both cardinalities appear: the marked list must read batched on both sides with the
     * <em>fan-in</em> trigger (not the authored one, the cardinality rule preceding the marker
     * arms), and the marked single must read inline on both.
     */
    private static final String MARKER_FIXTURE = """
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
            title: String @field(name: "TITLE")
        }
        type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
            title: String @field(name: "TITLE")
        }
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            splitLanguages: [Language!]! @splitQuery
                @reference(path: [{key: "film_language_id_fkey"}])
            splitContents: [Content!]! @splitQuery
                @reference(path: [{key: "content_film_id_fkey"}])
            splitContent: Content @splitQuery
                @reference(path: [{key: "content_film_id_fkey"}])
        }
        type Query {
            films: [Film!]!
        }
        """;

    @Test
    void computedDeliveryEqualsTheLeafCrosswalkOverTheCorpusAndFixture() {
        var schemas = new LinkedHashMap<String, GraphitronSchema>();
        for (var example : CorpusDocuments.documents()) {
            schemas.put(example.id(), ClassifiedHarness.classify(example.sdl()).schema());
        }
        schemas.put("marker-fixture", TestSchemaHelper.buildSchema(MARKER_FIXTURE));

        int coordinates = 0;
        var triggerHistogram = new TreeMap<String, Integer>();
        for (var entry : schemas.entrySet()) {
            var schema = entry.getValue();
            for (var leaf : flatOutputLeaves(schema)) {
                coordinates++;
                var coord = FieldCoordinates.coordinates(leaf.parentTypeName(), leaf.name());
                var computed = schema.deliveryOf(coord);
                assertThat(computed)
                    .as("computed vs leaf-derived delivery at %s.%s (%s in %s)",
                        leaf.parentTypeName(), leaf.name(), leaf.getClass().getSimpleName(),
                        entry.getKey())
                    .isEqualTo(DeliveryFact.leafDerivedOf(leaf));
                if (computed instanceof DeliveryFact.Batched batched) {
                    triggerHistogram.merge(batched.trigger().getClass().getSimpleName(), 1, Integer::sum);
                }
            }
        }

        assertThat(coordinates).as("the agreement scan must not be vacuous").isGreaterThan(100);
        assertThat(triggerHistogram)
            .as("per-trigger floors: the agreement must be tested where the corpus is thin;"
                + " observed histogram %s", triggerHistogram)
            .hasEntrySatisfying("Authored", c -> assertThat(c).isGreaterThanOrEqualTo(2))
            .hasEntrySatisfying("RecordHandedParent", c -> assertThat(c).isGreaterThanOrEqualTo(1))
            .hasEntrySatisfying("PolymorphicFanIn", c -> assertThat(c).isGreaterThanOrEqualTo(1));
    }

    private static List<OutputField> flatOutputLeaves(GraphitronSchema schema) {
        var leaves = new ArrayList<OutputField>();
        for (var field : schema.fields().values()) {
            if (field instanceof OutputField out) {
                leaves.add(out);
            }
        }
        return leaves;
    }
}
