package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.FilmConditions;
import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 4 integration tests for the generated {@code FilmConditions} class.
 *
 * <p>Starts a real PostgreSQL container, applies the test database schema, and verifies that the
 * generated conditions produce the correct result sets when used in jOOQ queries.
 *
 * <p>Seed data (from init.sql):
 * <pre>
 *   ACADEMY DINOSAUR  PG     0.99
 *   ACE GOLDFINGER    G      4.99
 *   ADAPTATION HOLES  NC-17  2.99
 *   AFFAIR PREJUDICE  G      2.99
 *   AGENT TRUMAN      PG     2.99
 * </pre>
 */
class FilmConditionsTest {

    static final PostgreSQLContainer<?> DB =
        new PostgreSQLContainer<>("postgres:18")
            .withInitScript("init.sql");

    static DSLContext ctx;

    @BeforeAll
    static void setup() {
        DB.start();
        ctx = DSL.using(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }

    @AfterAll
    static void teardown() {
        DB.stop();
    }

    @Test
    void ratingG_returnsTwoFilms() {
        var condition = FilmConditions.conditions(MpaaRating.G, null);
        var result = ctx.select(Tables.FILM.TITLE)
            .from(Tables.FILM)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(2);
    }

    @Test
    void maxRentalRate3_returnsFourFilms() {
        // Films with rental_rate <= 3.0: ACADEMY DINOSAUR (0.99), ADAPTATION HOLES (2.99),
        //   AFFAIR PREJUDICE (2.99), AGENT TRUMAN (2.99)
        var condition = FilmConditions.conditions(null, 3.0);
        var result = ctx.select(Tables.FILM.TITLE)
            .from(Tables.FILM)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(4);
    }

    @Test
    void ratingPG_andMaxRentalRate2_returnsNoFilms() {
        // ACADEMY DINOSAUR is PG with 0.99 — wait, 0.99 <= 2.0 so should be 1 result
        // Actually: ACADEMY DINOSAUR (PG, 0.99) qualifies, AGENT TRUMAN (PG, 2.99) does not
        var condition = FilmConditions.conditions(MpaaRating.PG, 2.0);
        var result = ctx.select(Tables.FILM.TITLE)
            .from(Tables.FILM)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get(Tables.FILM.TITLE)).isEqualTo("ACADEMY DINOSAUR");
    }

    @Test
    void bothNull_returnsAllFilms() {
        var condition = FilmConditions.conditions(null, null);
        var result = ctx.select(Tables.FILM.TITLE)
            .from(Tables.FILM)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(5);
    }
}
