package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A root list ordered by a single-valued {@code @orderBy} argument whose every choice sorts by
 * columns of its own table, against a real PostgreSQL database: the client's pick orders it, its
 * runtime direction flipping a uniformly ascending choice, a choice with directions of its own
 * keeping them, and the default order reading where the client picks none. Every order ends on
 * film_id, so tied rows answer in one order.
 *
 * <p>Other classes insert films into the database this one shares, between the request and the read
 * it is compared with, so both are judged on the seeded films, as {@code SortKeyOrderExecutionTest}
 * judges its own.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class RootOrderChoiceExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    private static final List<Integer> SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5);

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    /** The seeded film ids {@code Query.filmsOrderedByOne} answers under {@code arguments}, in its order. */
    private List<Integer> filmIds(String arguments) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ filmsOrderedByOne" + arguments + " { filmId } }").build();
        ExecutionResult result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        var films = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("filmsOrderedByOne");
        return films.stream().map(film -> (Integer) film.get("filmId")).filter(SEEDED_FILM_IDS::contains).toList();
    }

    /** The seeded film ids the database answers in {@code order}. */
    private static List<Integer> inOrder(SortField<?>... order) {
        return dsl.select(FILM.FILM_ID).from(FILM).where(FILM.FILM_ID.in(SEEDED_FILM_IDS)).orderBy(order)
            .fetch(FILM.FILM_ID);
    }

    @Test
    void noPick_readsTheDefaultOrder() {
        assertThat(filmIds("")).isEqualTo(inOrder(FILM.FILM_ID.asc()));
    }

    @Test
    void aUniformlyAscendingChoice_takesTheClientsDirection() {
        assertThat(filmIds("(order: {field: RENTAL_RATE, direction: ASC})"))
            .isEqualTo(inOrder(FILM.RENTAL_RATE.asc(), FILM.FILM_ID.asc()));
        assertThat(filmIds("(order: {field: RENTAL_RATE, direction: DESC})"))
            .isEqualTo(inOrder(FILM.RENTAL_RATE.desc(), FILM.FILM_ID.desc()));
    }

    @Test
    void aChoiceWithDirectionsOfItsOwn_keepsThem() {
        assertThat(filmIds("(order: {field: RATE_DESC_TITLE_ASC, direction: DESC})"))
            .isEqualTo(inOrder(FILM.RENTAL_RATE.desc(), FILM.TITLE.asc(), FILM.FILM_ID.asc()));
    }
}
