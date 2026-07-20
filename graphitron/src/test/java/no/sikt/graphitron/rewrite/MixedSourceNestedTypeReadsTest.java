package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ReachableSourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The positive mixed-source reach: a directiveless type reached both as a nesting projection of a
 * {@code @table} parent and as a producer-backed class-backed result classifies both edges, registers the
 * {@code ResultType} type-level winner, and reifies the two-shape union on every coordinate. Both edges are
 * pinned as typed facts (not emitted-code assertions); the emitted dispatch is exercised at the compilation
 * and execution tiers.
 */
@PipelineTier
class MixedSourceNestedTypeReadsTest {

    // FilmDetails is a plain field of @table Film (nesting projection: rating -> film.rating) and the
    // @service result of prodFilmDetails, whose producer returns the FilmDetailsRating record (class-backed
    // accessor: rating()).
    private static final String DIRECT_FILM_FIRST = """
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query {
            film: Film
            prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
        }
        """;

    // Same schema, Query fields swapped so the walk reaches the producer edge before the nesting edge.
    private static final String DIRECT_PRODUCER_FIRST = """
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query {
            prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
            film: Film
        }
        """;

    // Two-hop: FilmDetails binds class-backed through a parent accessor (FilmHolder.details() ->
    // FilmDetailsRating) rather than a direct @service return, while also nesting off @table Film.
    private static final String TWO_HOP_ACCESSOR_CHAIN = """
        type Holder { details: FilmDetails }
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query {
            film: Film
            holder: Holder @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmHolder"})
        }
        """;

    // Single-reach: FilmDetails is only a nesting projection (no producer binds it), so it registers a
    // NestingType and carries no mixed-source coordinate.
    private static final String SINGLE_REACH_NESTING_ONLY = """
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query { film: Film }
        """;

    private static final Set<ReachableSourceShape> DUAL =
        Set.of(ReachableSourceShape.NESTING_RECORD, ReachableSourceShape.CLASS_BACKED_ACCESSOR);

    @Test
    void directVariant_classifiesBothEdgesAndReifiesTheUnion() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(DIRECT_FILM_FIRST);

        // Nesting edge: Film.details is a NestingField whose rating child reads the film.rating column.
        var details = schema.field("Film", "details");
        assertThat(details).isInstanceOf(ChildField.NestingField.class);
        var nested = ((ChildField.NestingField) details).nestedFields();
        assertThat(nested).singleElement().isInstanceOf(ChildField.ColumnField.class);
        assertThat(((ChildField.ColumnField) nested.get(0)).column().sqlName()).isEqualTo("rating");

        // Result edge: FilmDetails registers as the class-backed JavaRecordType and its own visit resolves
        // the accessor read.
        assertThat(schema.type("FilmDetails")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(schema.field("FilmDetails", "rating")).isInstanceOf(ChildField.PropertyField.class);

        // The reified union.
        assertThat(schema.reachableSourceShapes("FilmDetails", "rating")).isEqualTo(DUAL);
    }

    @Test
    void reachableSourceShapesIsWalkOrderIndependent() {
        var filmFirst = TestSchemaHelper.buildSchema(DIRECT_FILM_FIRST);
        var producerFirst = TestSchemaHelper.buildSchema(DIRECT_PRODUCER_FIRST);

        var coord = FieldCoordinates.coordinates("FilmDetails", "rating");
        // The registry winner and the shape-set union are order-independent typed facts.
        assertThat(producerFirst.type("FilmDetails")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(filmFirst.type("FilmDetails")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(producerFirst.reachableSourceShapes(coord)).isEqualTo(DUAL);
        assertThat(filmFirst.reachableSourceShapes(coord)).isEqualTo(DUAL);
        assertThat(producerFirst.mixedSourceCoordinates()).isEqualTo(filmFirst.mixedSourceCoordinates());
    }

    @Test
    void twoHopAccessorChain_classifiesBothEdges() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(TWO_HOP_ACCESSOR_CHAIN);

        assertThat(schema.field("Film", "details")).isInstanceOf(ChildField.NestingField.class);
        assertThat(schema.type("FilmDetails")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(schema.reachableSourceShapes("FilmDetails", "rating")).isEqualTo(DUAL);
    }

    @Test
    void singleReach_carriesNoMixedSourceCoordinate() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(SINGLE_REACH_NESTING_ONLY);

        // A pure nesting target registers a NestingType and no coordinate is dual-reached.
        assertThat(schema.type("FilmDetails")).isInstanceOf(GraphitronType.NestingType.class);
        assertThat(schema.mixedSourceCoordinates()).isEmpty();
        // The derived singleton for a pure nesting coordinate.
        assertThat(schema.reachableSourceShapes("FilmDetails", "rating"))
            .isEqualTo(Set.of(ReachableSourceShape.NESTING_RECORD));
    }
}
