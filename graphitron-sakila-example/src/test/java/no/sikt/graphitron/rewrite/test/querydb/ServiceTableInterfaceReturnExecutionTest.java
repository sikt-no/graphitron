package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution tier: a {@code @service} field returning a single-table discriminated interface
 * ({@code Content}: shared {@code content} table, {@code CONTENT_TYPE} discriminator, {@code FilmContent}
 * / {@code ShortContent} subtypes). Proves the single-table service path end-to-end: the service hands
 * back PK-only {@code ContentRecord}s (whose runtime class is always {@code ContentRecord}, so a route
 * (a)-style record-class dispatch would misroute both subtypes to one type), and the generated fetcher
 * collects their PKs, re-fetches by PK projecting {@code __discriminator__} plus the discriminator-gated
 * cross-table {@code LEFT JOIN} for {@code FilmContent.rating}, and routes each row off the live
 * {@code CONTENT_TYPE} via the {@code Content} {@code TypeResolver}.
 *
 * <p>{@code contentSearchMany} returns content rows 1 ({@code FILM}), 3 ({@code SHORT}) and 999 (no live
 * row) so both the per-row routing and the drop contract are observable; {@code contentSearchOne} covers
 * single cardinality; {@code contentSearchManyMutation} pins the mutation-root path. All backed by
 * {@code ContentSearchService}.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ServiceTableInterfaceReturnExecutionTest {

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

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void contentSearchMany_routesEachRowByLiveDiscriminatorAndHonoursDropContract() {
        Map<String, Object> data = execute("""
            { contentSearchMany {
                __typename
                contentId
                title
                ... on FilmContent { length rating }
                ... on ShortContent { description }
            } }
            """);

        var items = (List<Map<String, Object>>) data.get("contentSearchMany");
        // The service returned three PKs (content 1, 3, and 999); 999 has no live row and drops, so
        // the surviving payload is two items (the by-PK re-map never puts a null hole in the list).
        assertThat(items).hasSize(2);

        var film = items.stream().filter(d -> "FilmContent".equals(d.get("__typename"))).findFirst().orElseThrow();
        var shortContent = items.stream().filter(d -> "ShortContent".equals(d.get("__typename"))).findFirst().orElseThrow();

        // Routing is off the LIVE CONTENT_TYPE, not the record class: content 1 is a FILM row, content 3
        // a SHORT row. A record-class dispatch would misroute both to one type (both are ContentRecord).
        assertThat(film.get("contentId")).isEqualTo(1);
        assertThat(shortContent.get("contentId")).isEqualTo(3);

        // FilmContent.rating is populated through the discriminator-gated cross-table join
        // (content.film_id → film; film 1 is rated 'PG'). It is not a column on the shared table.
        assertThat(film.get("rating")).isEqualTo("PG");
        assertThat(film.get("length")).isNotNull();
        // FilmContent does not declare `description`; only ShortContent does.
        assertThat(film).doesNotContainKey("description");

        // ShortContent.description is the shared table's SHORT_DESCRIPTION column, populated on SHORT rows.
        assertThat(shortContent.get("description")).isEqualTo("Dawn over a city");
        assertThat(shortContent).doesNotContainKey("rating");
        assertThat(shortContent).doesNotContainKey("length");
    }

    @Test
    void contentSearchOne_singleCardinalityRoutesToFilmContent() {
        Map<String, Object> data = execute("""
            { contentSearchOne {
                __typename
                contentId
                title
                ... on FilmContent { rating }
            } }
            """);

        var one = (Map<String, Object>) data.get("contentSearchOne");
        assertThat(one.get("__typename")).isEqualTo("FilmContent");
        assertThat(one.get("contentId")).isEqualTo(1);
        assertThat(one.get("rating")).isEqualTo("PG");
        assertThat(one.get("title")).isNotNull();
    }

    @Test
    void contentSearchManyMutation_routesEachRowByLiveDiscriminator() {
        Map<String, Object> data = execute("""
            mutation { contentSearchManyMutation {
                __typename
                contentId
                ... on FilmContent { rating }
                ... on ShortContent { description }
            } }
            """);

        var items = (List<Map<String, Object>>) data.get("contentSearchManyMutation");
        assertThat(items).hasSize(2);
        var film = items.stream().filter(d -> "FilmContent".equals(d.get("__typename"))).findFirst().orElseThrow();
        var shortContent = items.stream().filter(d -> "ShortContent".equals(d.get("__typename"))).findFirst().orElseThrow();
        assertThat(film.get("rating")).isEqualTo("PG");
        assertThat(shortContent.get("description")).isEqualTo("Dawn over a city");
    }
}
