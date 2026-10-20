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
 * Execution-tier proof that an asymmetric inline-fragment query on a multi-table polymorphic
 * field resolves each participant with exactly the fields its own fragment selected.
 *
 * <p>The {@code AddressOccupant} union resolves to {@code Customer | Staff}; both participant
 * tables expose {@code first_name} behind {@code firstName}. Each Stage-2 per-typename SELECT
 * sees the selection set restricted to its own participant through
 * {@code SelectionOccurrences.restrictTo}.
 *
 * <p>Fixture: active customers Mary (address 1), Patricia (address 2) and Linda (address 3).
 * Address 1 also houses inactive customer Barbara; address 2 houses inactive Elizabeth and
 * staff Jon; address 3 houses staff Mike.
 */
@ExecutionTier
class PolymorphicProjectionQueryTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
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
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void asymmetricFragment_customerOnly_staffResolvesWithoutFirstName() {
        var data = execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Customer { firstName }
            } } } }
            """);

        assertThat(occupantsPerCustomer(data)).containsExactly(
            List.of("Customer:Mary", "Customer:Barbara"),
            List.of("Customer:Patricia", "Customer:Elizabeth", "Staff"),
            List.of("Customer:Linda", "Staff"));
    }

    @Test
    void asymmetricFragment_staffOnly_customerResolvesWithoutFirstName() {
        // Inverse asymmetry: the restriction is per participant, not an always-drop of the
        // shared column.
        var data = execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Staff { firstName }
            } } } }
            """);

        assertThat(occupantsPerCustomer(data)).containsExactly(
            List.of("Customer", "Customer"),
            List.of("Customer", "Customer", "Staff:Jon"),
            List.of("Customer", "Staff:Mike"));
    }

    @Test
    void symmetricFragment_bothBranchesKeepFirstName() {
        var data = execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } } } }
            """);

        assertThat(occupantsPerCustomer(data)).containsExactly(
            List.of("Customer:Mary", "Customer:Barbara"),
            List.of("Customer:Patricia", "Customer:Elizabeth", "Staff:Jon"),
            List.of("Customer:Linda", "Staff:Mike"));
    }

    /**
     * Each customer's address occupants as {@code Typename}, or {@code Typename:firstName}
     * when the response carries a {@code firstName} key.
     */
    @SuppressWarnings("unchecked")
    private static List<List<String>> occupantsPerCustomer(Map<String, Object> data) {
        var customers = (List<Map<String, Object>>) data.get("customers");
        return customers.stream()
            .map(c -> ((List<Map<String, Object>>) ((Map<String, Object>) c.get("address")).get("occupants"))
                .stream()
                .map(o -> o.containsKey("firstName")
                    ? o.get("__typename") + ":" + o.get("firstName")
                    : (String) o.get("__typename"))
                .toList())
            .toList();
    }
}
