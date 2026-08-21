package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
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
 * Drift protection for the user-manual tutorial under {@code docs/manual/tutorial/}: replays each
 * page's query against the generated schema and asserts on the response shape the prose promises.
 * If a directive disappears or a generated resolver narrows differently, the corresponding
 * tutorial page fails before the docs ship.
 *
 * <p>The tutorial posts its queries with {@code curl}, and this class does not: running a GraphQL
 * operation against a generated schema needs no container, and the endpoint the tutorial posts to
 * is held by {@code GraphqlResourceSmokeTest}, which asserts on the wire. What is left here is the
 * half a container cannot help with, which is every claim the pages make about the response.
 *
 * <p>One method per page (or per query within a page). New tutorial queries land here in the same
 * commit as the prose.
 */
@ExecutionTier
class TutorialSmokeTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        String localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            dsl = DSL.using(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void page1_introspectionVerification() {
        // Page 1 tells the reader that `{"data":{"__typename":"Query"}}` means the engine is wired
        // up. The field is answered by GraphQL-Java off the schema, so what it actually pins is
        // that the generated schema builds and its query root is named Query.
        var data = execute("{ __typename }");
        assertThat(data).containsEntry("__typename", "Query");
    }

    @Test
    void page3_customersBasicSelection() {
        // The tutorial's first real query, and the claim under it is that Query.customers projects
        // the three selected columns from the five seeded rows.
        var data = execute("{ customers { firstName lastName email } }");
        var customers = customersOf(data);
        assertThat(customers).extracting(c -> c.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Patricia", "Linda", "Barbara", "Elizabeth");
        assertThat(customers)
            .filteredOn(c -> "Mary".equals(c.get("firstName")))
            .singleElement()
            .satisfies(c -> {
                assertThat(c).containsEntry("lastName", "Smith");
                assertThat(c).containsEntry("email", "mary.smith@example.com");
            });
    }

    @Test
    void page3_activeFilter() {
        // The page's stated split: three active customers and two inactive. The argument is
        // @field(name: "ACTIVEBOOL"), so a regression that binds the wrong column returns all five.
        var data = execute("{ customers(active: true) { firstName } }");
        assertThat(customersOf(data)).extracting(c -> c.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Patricia", "Linda");
    }

    @Test
    void page4_singleHopReference() {
        // One @reference hop, customer.address_id -> address. The page prints the joined address
        // row alongside each customer, so both halves of the row are asserted.
        var data = execute("{ customers { firstName address { address district } } }");
        assertThat(customersOf(data))
            .filteredOn(c -> "Mary".equals(c.get("firstName")))
            .singleElement()
            .extracting(c -> c.get("address"))
            .isEqualTo(Map.of("address", "47 MySakila Drive", "district", "Alberta"));
    }

    @Test
    void page4_multiHopReference() {
        // Two hops: customer.store_id -> store.address_id -> address. The page's claim is which
        // customers land on which store address, and a chain that collapses to one hop cannot
        // produce the second address at all.
        var data = execute("{ customers { firstName storeAddress { address district } } }");
        var customers = customersOf(data);
        assertThat(storeAddressOf(customers, "Mary")).isEqualTo("47 MySakila Drive");
        assertThat(storeAddressOf(customers, "Linda")).isEqualTo("28 MySQL Boulevard");
    }

    @Test
    void page5_createAndUpdateFilm() {
        // Page 5's INSERT then UPDATE, run as the page runs them: createFilm returns the
        // server-assigned key from its RETURNING clause, and updateFilm is addressed by that key.
        // The row is this case's own, so it deletes it by id rather than resetting the table.
        Integer filmId = null;
        try {
            var insert = execute(
                "mutation { createFilm(in: { title: \"MY FIRST FILM\", languageId: 1 }) "
                    + "{ filmId title } }");
            @SuppressWarnings("unchecked")
            Map<String, Object> created = (Map<String, Object>) insert.get("createFilm");
            assertThat(created).containsEntry("title", "MY FIRST FILM");
            filmId = (Integer) created.get("filmId");
            assertThat(filmId)
                .as("createFilm returns the key PostgreSQL assigned, past the five seeded films")
                .isNotNull()
                .isGreaterThan(5);

            var update = execute(
                "mutation { updateFilm(in: { filmId: " + filmId + ", title: \"RENAMED FILM\" }) "
                    + "{ filmId title } }");
            @SuppressWarnings("unchecked")
            Map<String, Object> updated = (Map<String, Object>) update.get("updateFilm");
            assertThat(updated)
                .containsEntry("filmId", filmId)
                .containsEntry("title", "RENAMED FILM");
        } finally {
            if (filmId != null) {
                dsl.deleteFrom(DSL.table("film")).where(DSL.field("film_id").eq(filmId)).execute();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> customersOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("customers");
    }

    @SuppressWarnings("unchecked")
    private static Object storeAddressOf(List<Map<String, Object>> customers, String firstName) {
        return customers.stream()
            .filter(c -> firstName.equals(c.get("firstName")))
            .map(c -> ((Map<String, Object>) c.get("storeAddress")).get("address"))
            .findFirst().orElseThrow();
    }

    private Map<String, Object> execute(String query) {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
