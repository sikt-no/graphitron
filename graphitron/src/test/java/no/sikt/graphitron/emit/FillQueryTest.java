package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FillQuery}'s rows over a captured schema: each call's fills in position order, an argument
 * fill's landing as the names from its argument down with the kind of type found there, and a call
 * whose every position is filled marked complete. Read over the
 * {@code scenarios/occurrence-call-parameter} fact document, whose claims state which fill each
 * position takes.
 */
@PipelineTier
class FillQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static Map<String, FillQuery.Row> rows;

    @BeforeAll
    static void read() {
        rows = FACTS.read("scenarios/occurrence-call-parameter", graph -> FillQuery.read(graph.handle())
            .stream().collect(Collectors.toMap(FillQuery.Row::coordinate, Function.identity())));
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
}
