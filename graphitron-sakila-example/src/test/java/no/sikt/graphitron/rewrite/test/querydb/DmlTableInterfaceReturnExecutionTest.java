package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage: a DML {@code @mutation(typeName: INSERT|UPDATE)} returning a
 * single-table discriminated interface ({@code Content} over the shared {@code content} table,
 * {@code CONTENT_TYPE} discriminator, {@code FilmContent} / {@code ShortContent} pinned by
 * {@code @discriminator}). The write is a plain single-{@code @table} write; the load-bearing proof
 * is on the <em>return</em> half: the follow-up SELECT re-projects by the {@code RETURNING} primary
 * key through the discriminator re-projection, so the returned row routes to the correct implementer
 * by its live discriminator, populates the discriminator-gated cross-table {@code FilmContent.rating}
 * join, isolates the same-table {@code ShortContent.description}, and yields {@code null} for a row
 * whose committed discriminator is outside the known participant set (the documented write/read
 * asymmetry: the INSERT commits, but graphitron cannot name the subtype).
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class DmlTableInterfaceReturnExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    /**
     * The mutations under test commit to the shared {@code content} table, so remove every row this
     * test created (they share the common title prefix matched by the {@code LIKE} filter below)
     * after each method. Without this, the committed rows leak into read-side fixtures (e.g.
     * {@code allContent}) that assert on the exact seeded row set.
     */
    @AfterEach
    void cleanUpCreatedContent() {
        dsl.deleteFrom(DSL.table("content"))
            .where(DSL.field("title", String.class).like("R406 %"))
            .execute();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    /** A film carrying a non-null MPAA rating (seeded in init.sql), so FilmContent.rating has a
     *  value to join back through content.film_id → film.film_id. */
    private org.jooq.Record ratedFilm() {
        return dsl.select(
                DSL.field("film_id", Integer.class),
                DSL.field("CAST(rating AS varchar)", String.class).as("rating"))
            .from(DSL.table("film"))
            .where(DSL.field("rating").isNotNull())
            .orderBy(DSL.field("film_id"))
            .limit(1)
            .fetchOne();
    }

    private long contentRowsOfType(String contentType) {
        return dsl.selectCount().from(DSL.table("content"))
            .where(DSL.field("content_type", String.class).eq(contentType))
            .fetchOne(0, long.class);
    }

    @Test
    void insertFilmContent_routesToFilmContentAndPopulatesCrossTableRating() {
        var film = ratedFilm();
        int filmId = film.get("film_id", Integer.class);
        String rating = film.get("rating", String.class);
        Map<String, Object> data = execute("""
            mutation {
              createContent(in: { title: "R406 Film Row", contentType: "FILM", filmId: %d, length: 118 }) {
                __typename
                contentId
                title
                ... on FilmContent { length rating }
                ... on ShortContent { description }
              }
            }
            """.formatted(filmId));
        Map<String, Object> payload = (Map<String, Object>) data.get("createContent");
        // Routed by the live discriminator, the observable proof a runtime-class dispatch could not
        // produce (the DML row is a plain jOOQ Record; only CONTENT_TYPE distinguishes it).
        assertThat(payload.get("__typename")).isEqualTo("FilmContent");
        assertThat(payload.get("title")).isEqualTo("R406 Film Row");
        assertThat(payload.get("length")).isEqualTo(118);
        // rating sourced from the joined film row through the discriminator-gated LEFT JOIN.
        assertThat(payload.get("rating")).isEqualTo(rating);
        // ShortContent.description sits on the same content row but must be null on a FILM row.
        assertThat(payload.get("description")).isNull();
    }

    @Test
    void insertShortContent_routesToShortContentAndPopulatesSameTableDescription() {
        Map<String, Object> data = execute("""
            mutation {
              createContent(in: { title: "R406 Short Row", contentType: "SHORT", description: "a short blurb" }) {
                __typename
                contentId
                title
                ... on FilmContent { length rating }
                ... on ShortContent { description }
              }
            }
            """);
        Map<String, Object> payload = (Map<String, Object>) data.get("createContent");
        assertThat(payload.get("__typename")).isEqualTo("ShortContent");
        assertThat(payload.get("description")).isEqualTo("a short blurb");
        // No FilmContent participant fields on a SHORT row: the FILM-gated cross-table join never fired.
        assertThat(payload.get("rating")).isNull();
        assertThat(payload.get("length")).isNull();
    }

    @Test
    void insertUnknownDiscriminator_commitsRowYetReturnsNull() {
        long before = contentRowsOfType("PODCAST");
        Map<String, Object> data = execute("""
            mutation {
              createContent(in: { title: "R406 Podcast Row", contentType: "PODCAST" }) {
                __typename
                contentId
              }
            }
            """);
        // Write/read asymmetry: the INSERT has already committed in its transaction before the
        // follow-up discriminator re-projection runs, so the row exists...
        assertThat(contentRowsOfType("PODCAST")).isEqualTo(before + 1);
        // ...yet the re-projection's discriminator filter drops a row whose CONTENT_TYPE is outside
        // the known participant set, so graphitron cannot name the subtype and returns null. The
        // field is nullable, so this surfaces cleanly rather than as a non-null violation.
        assertThat(data.get("createContent")).isNull();
    }

    @Test
    void updateContent_reprojectsAndRoutesByLiveDiscriminator() {
        // Seed a FILM content row directly, then UPDATE it and assert the return routes to FilmContent
        // off the live discriminator, re-projected by the RETURNING primary key.
        var film = ratedFilm();
        int filmId = film.get("film_id", Integer.class);
        String rating = film.get("rating", String.class);
        int contentId = dsl.insertInto(DSL.table("content"))
            .set(DSL.field("content_type"), "FILM")
            .set(DSL.field("title"), "R406 Update Before")
            .set(DSL.field("film_id"), filmId)
            .returningResult(DSL.field("content_id", Integer.class))
            .fetchOne().value1();

        Map<String, Object> data = execute("""
            mutation {
              updateContent(in: { contentId: %d, title: "R406 Update After" }) {
                __typename
                contentId
                title
                ... on FilmContent { rating }
              }
            }
            """.formatted(contentId));
        Map<String, Object> payload = (Map<String, Object>) data.get("updateContent");
        assertThat(payload.get("__typename")).isEqualTo("FilmContent");
        assertThat(payload.get("contentId")).isEqualTo(contentId);
        assertThat(payload.get("title")).isEqualTo("R406 Update After");
        assertThat(payload.get("rating")).isEqualTo(rating);
    }
}
