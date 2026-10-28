package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route (a) execution tier: a {@code @service} field returning a polymorphic entity. Proves
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

    /**
     * The seeded values, named rather than read back: another class temporarily renames film 1
     * while this one runs, so a read taken beside the service call can disagree with it.
     */
    private static final String SEEDED_FILM_1_TITLE = "ACADEMY DINOSAUR";
    private static final String SEEDED_ACTOR_1_FIRST_NAME = "PENELOPE";

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

    @Test
    void searchManyService_dispatchesBothBranchesByRecordClass() {
        String expectedFilmTitle = SEEDED_FILM_1_TITLE;
        String expectedActorFirstName = SEEDED_ACTOR_1_FIRST_NAME;

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
    void searchManyMutation_dispatchesBothBranchesByRecordClass() {
        // The headline shape: a @service MUTATION returning a multitable interface. Same
        // record-class dispatch as the query arm, but pins the MutationServicePolymorphicField
        // emit + registration + runtime path end-to-end.
        String expectedFilmTitle = SEEDED_FILM_1_TITLE;
        String expectedActorFirstName = SEEDED_ACTOR_1_FIRST_NAME;

        Map<String, Object> data = execute("""
            mutation { searchManyMutation {
                __typename
                ... on Film { filmId title }
                ... on Actor { actorId firstName }
            } }
            """);

        var docs = (List<Map<String, Object>>) data.get("searchManyMutation");
        assertThat(docs).hasSize(2);
        var film = docs.stream().filter(d -> "Film".equals(d.get("__typename"))).findFirst().orElseThrow();
        var actor = docs.stream().filter(d -> "Actor".equals(d.get("__typename"))).findFirst().orElseThrow();
        assertThat(film.get("title")).isEqualTo(expectedFilmTitle);
        assertThat(actor.get("firstName")).isEqualTo(expectedActorFirstName);
    }

    @Test
    void searchOneService_singleCardinalityRoutesToFilm() {
        String expectedFilmTitle = SEEDED_FILM_1_TITLE;

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
