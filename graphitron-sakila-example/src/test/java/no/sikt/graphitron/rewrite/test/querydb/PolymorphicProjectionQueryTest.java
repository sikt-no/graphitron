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

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() throws Exception {
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
