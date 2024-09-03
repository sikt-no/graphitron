package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_QUERY;

@DisplayName("Fetch queries - Fetching fields through referenced tables")
public class ReferenceFieldTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/field";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains("table", Set.of(CUSTOMER_QUERY), ".leftJoin(customer_address_left");
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains("keyWithSinglePath", Set.of(CUSTOMER_QUERY), ".leftJoin(customer_address_left");
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains("keyWithMultiplePaths", ".leftJoin(film_filmoriginallanguageidfkey_left");
    }

    @Test
    @DisplayName("Condition path")
    void condition() { // Path exists but is overridden by condition.
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_customer_left).on(",
                ".district(CUSTOMER, customer_customer_left)"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_QUERY),
                "customer_address_left.DISTRICT",
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_district_address_left = ADDRESS.as(", // Note, no implicit join anymore.
                ".leftJoin(customer_district_address_left).on(",
                ".district(CUSTOMER, customer_district_address_left)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_address_left = CUSTOMER.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".leftJoin(customer_address_left).and(",
                ".district(CUSTOMER, customer_address_left)" // Note, this produces a strange result with an ".and" and no ".on" or ".where".
        );
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains(
                "multipleToSameTable", Set.of(CUSTOMER_QUERY),
                "\"district1\", customer_address_left.DISTRICT",
                "\"district2\", customer_address_left.DISTRICT",
                ".leftJoin(customer_address_left"
        );
    }
}
