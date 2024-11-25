package no.fellesstudentsystem.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Fetching fields through referenced tables")
public class ReferenceFieldTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/field";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains("table", Set.of(CUSTOMER_QUERY), ".from(customer_2952383337_address");
    }

    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                ".from(address_2030472956_customer"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains("keyWithSinglePath", Set.of(CUSTOMER_QUERY), ".from(customer_2952383337_address");
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains("keyWithMultiplePaths", ".from(film_3747728953_filmoriginallanguageidfkey");
    }

    @Test
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".from(address_2030472956_customer"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() { // Path exists but is overridden by condition.
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                ".join(customer_district_district_customer).on(",
                ".district(customer_district, customer_district_district_customer)",
                ".where(_customer.customer_id.eq(customer_district.customer_id"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_QUERY),
                "customer_2952383337_address.DISTRICT",
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_district_district_address = ADDRESS.as(", // Note, no implicit join anymore.
                ".join(customer_district_district_address).on(",
                ".district(customer_district, customer_district_district_address)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_2952383337_address = _customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".from(customer_2952383337_address).where(",
                ".district(_customer, customer_2952383337_address)" // Note, this produces a strange result with an ".and" and no ".on" or ".where".
        );
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains(
                "multipleToSameTable", Set.of(CUSTOMER_QUERY),
                "customer_2952383337_address.DISTRICT",
                "customer_2952383337_address.DISTRICT",
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Table path on a list of fields without split query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_QUERY),
                "DSL.multiset(",
                "customer_2952383337_address.ADDRESS_ID",
                ".from(customer_2952383337_address)"
        );
    }
}
