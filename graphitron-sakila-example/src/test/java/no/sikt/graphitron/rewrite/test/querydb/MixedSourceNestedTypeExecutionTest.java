package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for the mixed-source nested type reach. {@code FilmBlurb} is reached both
 * as a nesting projection of the {@code @table Film} parent ({@code Film.blurb}) and as the field of a
 * producer-backed result ({@code FilmBlurbHolder.blurb}, produced by {@code FilmBlurbHolderService}).
 * graphql-java wires one datafetcher for {@code (FilmBlurb, description)}; that fetcher dispatches on
 * {@code source instanceof org.jooq.Record}. This test drives both source shapes on a live request:
 * the nesting path hands the fetcher a jOOQ {@code Record} (the film row → column read), the service path
 * hands it a {@code FilmBlurb} POJO (→ accessor read). Both resolve the same {@code description} value.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class MixedSourceNestedTypeExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private Integer insertFilm(String title, String description) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("description"), description)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private void deleteFilmById(int filmId) {
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .execute();
    }

    @Test
    void bothSourceShapesResolveTheSameCoordinateThroughOneFetcher() {
        String marker = "blurb-" + UUID.randomUUID();
        int filmId = insertFilm("mixed-source " + UUID.randomUUID(), marker);
        try {
            // Nesting arm: Film.blurb is a source passthrough; FilmBlurb.description reads the column off
            // the parent film jOOQ Record (the `source instanceof org.jooq.Record` arm).
            Map<String, Object> nesting = execute(
                "{ filmById(film_id: [\"" + filmId + "\"]) { blurb { description } } }");
            var films = (java.util.List<Map<String, Object>>) nesting.get("filmById");
            var nestingBlurb = (Map<String, Object>) films.get(0).get("blurb");
            assertThat(nestingBlurb.get("description")).isEqualTo(marker);

            // Accessor arm: filmBlurbHolder returns a POJO carrying FilmBlurb; the same fetcher reads the
            // FilmBlurb.description() accessor (the else arm).
            Map<String, Object> service = execute(
                "{ filmBlurbHolder(filmId: " + filmId + ") { blurb { description } } }");
            var holder = (Map<String, Object>) service.get("filmBlurbHolder");
            var serviceBlurb = (Map<String, Object>) holder.get("blurb");
            assertThat(serviceBlurb.get("description")).isEqualTo(marker);
        } finally {
            deleteFilmById(filmId);
        }
    }
}
