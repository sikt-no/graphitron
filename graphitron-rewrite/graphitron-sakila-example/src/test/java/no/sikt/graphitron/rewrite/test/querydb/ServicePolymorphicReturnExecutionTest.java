package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.jooq.Tables;
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
 * R365 route (a) execution tier: a {@code @service} field returning a polymorphic entity. Proves
 * the 9.3 regression end-to-end: the service hands back PK-populated {@code TableRecord}s, the
 * generated fetcher dispatches on each returned record's runtime class
 * ({@code FilmRecord} → {@code Film}, {@code ActorRecord} → {@code Actor}), tags {@code __typename},
 * and auto-fetches the selected columns by PK against PostgreSQL.
 *
 * <p>{@code searchManyService} exercises two distinct-table branches (a {@code FilmRecord} and an
 * {@code ActorRecord} in one list over the {@code Searchable} interface) so a misdispatch is
 * observable; {@code searchOneService} covers single cardinality. Both are backed by
 * {@code PolymorphicSearchService}, which returns records carrying only their primary key.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ServicePolymorphicReturnExecutionTest {

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
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
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
    void searchManyService_dispatchesBothBranchesByRecordClass() {
        String expectedFilmTitle = dsl.select(Tables.FILM.TITLE).from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(1)).fetchOne(Tables.FILM.TITLE);
        String expectedActorFirstName = dsl.select(Tables.ACTOR.FIRST_NAME).from(Tables.ACTOR)
            .where(Tables.ACTOR.ACTOR_ID.eq(1)).fetchOne(Tables.ACTOR.FIRST_NAME);

        Map<String, Object> data = execute("""
            { searchManyService {
                __typename
                ... on Film { filmId title }
                ... on Actor { actorId firstName }
            } }
            """);

        var docs = (List<Map<String, Object>>) data.get("searchManyService");
        assertThat(docs).hasSize(2);

        var film = docs.stream().filter(d -> "Film".equals(d.get("__typename"))).findFirst().orElseThrow();
        var actor = docs.stream().filter(d -> "Actor".equals(d.get("__typename"))).findFirst().orElseThrow();

        // The by-PK auto-fetch populated the right columns for each branch; a misdispatch would
        // surface as a swapped __typename or a null/absent field on the wrong arm.
        assertThat(film.get("filmId")).isEqualTo(1);
        assertThat(film.get("title")).isEqualTo(expectedFilmTitle);
        assertThat(film).doesNotContainKey("firstName");
        assertThat(actor.get("actorId")).isEqualTo(1);
        assertThat(actor.get("firstName")).isEqualTo(expectedActorFirstName);
        assertThat(actor).doesNotContainKey("title");
    }

    @Test
    void searchOneService_singleCardinalityRoutesToFilm() {
        String expectedFilmTitle = dsl.select(Tables.FILM.TITLE).from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(1)).fetchOne(Tables.FILM.TITLE);

        Map<String, Object> data = execute("""
            { searchOneService {
                __typename
                name
                ... on Film { filmId title }
            } }
            """);

        var one = (Map<String, Object>) data.get("searchOneService");
        assertThat(one.get("__typename")).isEqualTo("Film");
        assertThat(one.get("filmId")).isEqualTo(1);
        assertThat(one.get("title")).isEqualTo(expectedFilmTitle);
        // Searchable.name maps to Film.TITLE for the Film branch.
        assertThat(one.get("name")).isEqualTo(expectedFilmTitle);
    }
}
