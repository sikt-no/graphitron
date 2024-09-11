package no.fellesstudentsystem.graphitron.queries.fetch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

// Note that these are mostly copies from ReferenceQueryTest. Many cases from there are omitted here.
@DisplayName("Fetch queries - Fetching rows through referenced tables")
public class ReferenceRowTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/row";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".leftJoin(film_filmoriginallanguageidfkey_left"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                "leftJoin(customer_addresscustomer_address_left).on(",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_address_left",
                ".leftJoin(address_166982810_city_left"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_address_left",
                ".leftJoin(address_166982810_city_left"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_QUERY),
                ".leftJoin(customer_citycustomer_city_left).on(",
                ".cityCustomer(CUSTOMER, customer_citycustomer_city_left)"
        );
    }

    @Test
    @DisplayName("Indirect reverse table path")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(city_address_left",
                ".leftJoin(address_2545393164_customer_left"
        );
    }

    @Test
    @Disabled("Need multiset or subqueries to make queries on listed fields without @splitQuery")
    @DisplayName("Table path on a list")
    void onList() {
        assertGeneratedContentContains(
                "onList", Set.of(CUSTOMER_TABLE),
                ".join(customer_address"
        );
    }

    @Test
    @Disabled("Need multiset or subqueries to make queries on listed fields without @splitQuery")
    @DisplayName("Table path on a nullable list")
    void onNullableList() {
        assertGeneratedContentContains(
                "onNullableList", Set.of(CUSTOMER_TABLE),
                ".leftJoin(customer_address_left"
        );
    }
}
