package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

// Note that these are mostly copies from ReferenceQueryTest. Many cases from there are omitted here.
@DisplayName("Fetch queries - Fetching rows through referenced tables in correlated subqueries")
public class ReferenceSubqueryTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/subquery";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_QUERY),
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".from(film_3747728953_filmoriginallanguageidfkey"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                "join(customer_address_addresscustomer_address).on(",
                ".addressCustomer(customer_address, customer_address_addresscustomer_address)",
                ".where(_customer.customer_id.eq(customer_address.customer_id"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_QUERY),
                ".from(customer_2952383337_address",
                ".join(address_1214171484_city"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_QUERY),
                ".from(customer_2952383337_address",
                ".join(address_1214171484_city"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_QUERY),
                ".join(customer_city_citycustomer_city).on(",
                ".cityCustomer(customer_city, customer_city_citycustomer_city)",
                ".where(_customer.customer_id.eq(customer_city.customer_id"
        );
    }

    @Test
    @DisplayName("Indirect reverse table path")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
                ".from(city_1887334959_address",
                ".join(address_1356285680_customer"
        );
    }

    @Test
    @DisplayName("Table path on a list")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_TABLE, CUSTOMER_QUERY),
                ".from(customer_2952383337_address)"
        );
    }

    @Test
    @DisplayName("Table path on nested lists")
    void nestedLists() {
        assertGeneratedContentContains(
                "nestedLists", Set.of(CUSTOMER_QUERY),
                ".from(address_1214171484_store)",
                ".from(customer_2952383337_address)"
        );
    }

}
