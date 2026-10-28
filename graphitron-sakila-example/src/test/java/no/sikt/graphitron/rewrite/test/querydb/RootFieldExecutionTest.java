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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Root query fields answered from the seed data: the plain list root with and without a filter,
 * the single-cardinality root, the argument-ordered root, and the discriminated interface roots
 * (single-table, joined-table, composite shared key, cross-table participant field, paginated
 * with totalCount).
 *
 * <p>Other classes insert films and content rows when they share one database, so the film and
 * content cases pick the seeded rows out by title before asserting order.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class RootFieldExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    /** The seeded films' titles, in {@code film_id} order. */
    private static final List<String> SEEDED_FILM_TITLES = List.of(
        "ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES", "AFFAIR PREJUDICE", "AGENT TRUMAN");

    /**
     * The seeded {@code content} rows of a known discriminator, in {@code content_id} order. The
     * seeded PODCAST row is absent: no participant claims that value, so no interface root returns it.
     */
    private static final List<String> SEEDED_CONTENT_TITLES = List.of(
        "ACADEMY DINOSAUR (extended)", "ACE GOLDFINGER (extended)", "Sunrise", "Interlude");

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @Test
    void filteredPlainListRoot_returnsTheMatchingRowsInKeyOrder() {
        var films = list(execute("{ films(rating: PG) { title } }"), "films");
        assertThat(seededTitles(films)).containsExactly("ACADEMY DINOSAUR", "AGENT TRUMAN");
    }

    @Test
    void filterlessPlainListRoot_returnsEveryRowInKeyOrder() {
        var actors = list(execute("{ allActors { firstName } }"), "allActors");
        assertThat(actors).extracting(a -> a.get("firstName"))
            .containsExactly("PENELOPE", "NICK", "ED", "JOAN");
    }

    @Test
    void singleCardinalityPlainRoot_returnsTheOneRow() {
        var data = execute("{ customerByPk(customerId: 1) { lastName } }");
        assertThat((Map<String, Object>) data.get("customerByPk")).containsEntry("lastName", "Smith");
    }

    @Test
    void argumentOrderedPlainListRoot_ordersByTheArgument() {
        var films = list(execute("{ filmsOrdered(order: [{field: TITLE, direction: DESC}]) { title } }"),
            "filmsOrdered");
        assertThat(seededTitles(films)).containsExactly(
            "AGENT TRUMAN", "AFFAIR PREJUDICE", "ADAPTATION HOLES", "ACE GOLDFINGER", "ACADEMY DINOSAUR");
    }

    @Test
    void interfaceRoot_singleTable_returnsKnownDiscriminatorsOnly() {
        var items = list(execute("{ allContent { title } }"), "allContent");
        assertThat(items).extracting(i -> i.get("title")).doesNotContain("ACE GOLDFINGER (podcast)");
        assertThat(seededContentTitles(items)).containsExactlyElementsOf(SEEDED_CONTENT_TITLES);
    }

    @Test
    void interfaceRoot_joinedTable_detailFieldResolvesAndNullsWithoutADetailRow() {
        var parties = list(execute("{ allParties { displayName ... on Individual { birthDate } } }"),
            "allParties");
        assertThat(parties).extracting(p -> p.get("displayName"))
            .containsExactly("Grace Hopper", "Sikt", "Detached Individual");
        assertThat(parties.get(0)).containsEntry("birthDate", "1906-12-09");
        assertThat(parties.get(2)).containsEntry("birthDate", null);
    }

    @Test
    void interfaceRoot_crossTableParticipantField_resolvesPerRowWithoutMultiplyingRows() {
        var items = list(execute("{ allContent { title ... on FilmContent { rating } } }"), "allContent");
        assertThat(items.stream().filter(i -> SEEDED_CONTENT_TITLES.contains(i.get("title"))).toList())
            .extracting(i -> i.get("title"), i -> i.get("rating"))
            .containsExactly(
                tuple("ACADEMY DINOSAUR (extended)", "PG"),
                tuple("ACE GOLDFINGER (extended)", "G"),
                tuple("Sunrise", null),
                tuple("Interlude", null));
    }

    @Test
    void interfaceRoot_compositeSharedKey_detailFieldResolves() {
        var subjects = list(execute("{ allSubjects { displayName ... on AppAccount { clientId } } }"),
            "allSubjects");
        assertThat(subjects).extracting(s -> s.get("displayName"), s -> s.get("clientId"))
            .containsExactly(
                tuple("Billing service", "billing-client-001"),
                tuple("Reporting service", "reporting-client-002"),
                tuple("Ada Lovelace", null),
                tuple("Alan Turing", null));
    }

    @Test
    void interfaceRootConnection_totalCountAgreesWithTheUnpagedRoot() {
        int unpaged = list(execute("{ allContent { title } }"), "allContent").size();
        var connection = (Map<String, Object>) execute(
            "{ allContentConnection(first: 2) { totalCount edges { node { title } } } }")
            .get("allContentConnection");
        assertThat(connection.get("totalCount"))
            .as("the count is restricted to known discriminators, as the rows are")
            .isEqualTo(unpaged);
        assertThat((List<Map<String, Object>>) connection.get("edges"))
            .extracting(e -> ((Map<String, Object>) e.get("node")).get("title"))
            .containsExactly("ACADEMY DINOSAUR (extended)", "ACE GOLDFINGER (extended)");
    }

    private static List<String> seededTitles(List<Map<String, Object>> films) {
        return films.stream().map(f -> (String) f.get("title")).filter(SEEDED_FILM_TITLES::contains).toList();
    }

    private static List<String> seededContentTitles(List<Map<String, Object>> items) {
        return items.stream().map(i -> (String) i.get("title")).filter(SEEDED_CONTENT_TITLES::contains).toList();
    }

    private static List<Map<String, Object>> list(Map<String, Object> data, String field) {
        return (List<Map<String, Object>>) data.get(field);
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
