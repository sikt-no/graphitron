package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.REFERENCE_CUSTOMER_CONDITION;
import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.REFERENCE_FILM_CONDITION;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_NOT_GENERATED;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Fetching through referenced tables")
public class ReferenceQueryTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/query";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(REFERENCE_CUSTOMER_CONDITION, REFERENCE_FILM_CONDITION);
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_NOT_GENERATED),
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(address_customer_left"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_NOT_GENERATED),
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
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(address_customer_left"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_NOT_GENERATED),
                "leftJoin(customer_addresscustomer_address_left).on(",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_NOT_GENERATED),
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_addresscustomer_address_left = ADDRESS.as(", // Note, no implicit join anymore.
                ".leftJoin(customer_addresscustomer_address_left).on(",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_address_left = CUSTOMER.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".leftJoin(customer_address_left).where(CUSTOMER.hasIds(customerIds)).and(",
                ".addressCustomer(CUSTOMER, customer_address_left)" // Note, no condition override unlike table case.
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                ".leftJoin(customer_address_left",
                ".leftJoin(address_166982810_city_left"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                ".leftJoin(customer_address_left",
                ".leftJoin(address_166982810_city_left"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
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
    @DisplayName("Table path on a list")
    void onList() {
        assertGeneratedContentContains(
                "onList", Set.of(CUSTOMER_NOT_GENERATED),
                ".join(customer_address"
        );
    }

    @Test
    @DisplayName("Table path on a nullable list")
    void onNullableList() {
        assertGeneratedContentContains(
                "onNullableList", Set.of(CUSTOMER_NOT_GENERATED),
                ".leftJoin(customer_address_left"
        );
    }

    @Test
    @DisplayName("Table path to the same table as source")
    void selfTableReference() {
        assertGeneratedContentContains(
                "selfTableReference",
                "FILM.film().as(",
                "film_film_left.getId()",
                ".leftJoin(film_film_left"
        );
    }

    @Test
    @DisplayName("Key path to the same table as source")
    void selfKeyReference() {
        assertGeneratedContentContains(
                "selfKeyReference",
                "FILM.film().as(",
                "film_film_left.getId()",
                ".leftJoin(film_film_left"
        );
    }

    @Test
    @DisplayName("Condition path to the same table as source")
    void selfConditionReference() {
        assertGeneratedContentContains(
                "selfConditionReference",
                "FILM.as(",
                "film_film_left.getId()",
                ".leftJoin(film_film_left).on(",
                ".sequel(FILM, film_film_left"
        );
    }
}
