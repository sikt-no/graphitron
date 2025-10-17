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
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".from(_a_film_2185543202_filmoriginallanguageidfkey"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                "join(_a_customer_address_addresscustomer_address).on(",
                ".addressCustomer(_a_customer_address, _a_customer_address_addresscustomer_address)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_address.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_QUERY),
                ".join(_a_customer_city_citycustomer_city).on(",
                ".cityCustomer(_a_customer_city, _a_customer_city_citycustomer_city)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_city.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Indirect reverse table path")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
                ".from(_a_city_760939060_address",
                ".join(_a_address_609487378_customer"
        );
    }

    @Test
    @DisplayName("Table path on a list")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_TABLE, CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address)"
        );
    }

    @Test
    @DisplayName("Table path on nested lists")
    void nestedLists() {
        assertGeneratedContentContains(
                "nestedLists", Set.of(CUSTOMER_QUERY),
                ".from(_a_address_2138977089_store)",
                ".from(_a_customer_2168032777_address)"
        );
    }

}
