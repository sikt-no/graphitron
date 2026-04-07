package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.FilmLookup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the generated {@code FilmLookup.toInputRows} method.
 *
 * <p>{@code toInputRows} is a pure function: given a {@code Map<String, Object>} carrying
 * {@code "film_id"} → {@code List<Integer>}, it returns one {@code Record2<Integer, Integer>}
 * per input ID, where the first value is the 1-based row index and the second is the film ID.
 * No database is needed to test this.
 */
class FilmLookupTest {

    @Test
    void singleId_returnsOneRowWithIndex1() {
        var rows = FilmLookup.toInputRows(Map.of("film_id", List.of(3)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value1()).isEqualTo(1);  // 1-based index
        assertThat(rows.get(0).value2()).isEqualTo(3);  // film_id
    }

    @Test
    void multipleIds_preservesOrderAndAssignsConsecutiveIndex() {
        var rows = FilmLookup.toInputRows(Map.of("film_id", List.of(2, 5, 1)));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).value1()).isEqualTo(1);
        assertThat(rows.get(0).value2()).isEqualTo(2);
        assertThat(rows.get(1).value1()).isEqualTo(2);
        assertThat(rows.get(1).value2()).isEqualTo(5);
        assertThat(rows.get(2).value1()).isEqualTo(3);
        assertThat(rows.get(2).value2()).isEqualTo(1);
    }

    @Test
    void emptyList_returnsEmptyList() {
        var rows = FilmLookup.toInputRows(Map.of("film_id", List.of()));

        assertThat(rows).isEmpty();
    }
}
