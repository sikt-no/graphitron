package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ReachableSourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-reach control of the mixed-source reach: a directiveless type reached only as a nesting
 * projection of a {@code @table} parent registers a nesting type and no two-shape coordinate. The
 * two-shape reach itself, a row beside an object of a class read by accessor, is held by the
 * {@code defects/fetcher-readers} fact document.
 */
@PipelineTier
class MixedSourceNestedTypeReadsTest {

    // Single-reach: FilmDetails is only a nesting projection (no producer binds it), so it registers a
    // NestingType and carries no mixed-source coordinate.
    private static final String SINGLE_REACH_NESTING_ONLY = """
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query { film: Film }
        """;

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
