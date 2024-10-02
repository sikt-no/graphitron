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
                ".from(customer_2952383337_address"
        );
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
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_NOT_GENERATED),
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
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".from(address_2030472956_customer"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_NOT_GENERATED),
                "join(customer_address_addresscustomer_address).on(",
                ".addressCustomer(customer_address, customer_address_addresscustomer_address)",
                ".where(_customer.customer_id.eq(customer_address.customer_id"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_address_addresscustomer_address = ADDRESS.as(", // Note, no implicit join anymore.
                ".join(customer_address_addresscustomer_address).on(",
                ".addressCustomer(customer_address, customer_address_addresscustomer_address)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2952383337_address = _customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".from(customer_2952383337_address).where(",
                ".addressCustomer(_customer, customer_2952383337_address)", // Note, no condition override unlike table case.
                ".where(_customer.hasIds(customerIds"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address",
                ".join(address_1214171484_city"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address",
                ".join(address_1214171484_city"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                ".join(customer_city_citycustomer_city).on(",
                ".cityCustomer(customer_city, customer_city_citycustomer_city)"
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
    void onList() {
        assertGeneratedContentContains(
                "onList", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address).orderBy(orderFields",
                "DSL.multiset(",
                "orderFields = customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Table path on a nullable list")
    void onNullableList() {
        assertGeneratedContentContains(
                "onNullableList", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Table path to the same table as source")
    void selfTableReference() {
        assertGeneratedContentContains(
                "selfTableReference",
                "_film.film().as(",
                "film_3747728953_film.getId()",
                ".from(film_3747728953_film"
        );
    }

    @Test
    @DisplayName("Key path to the same table as source")
    void selfKeyReference() {
        assertGeneratedContentContains(
                "selfKeyReference",
                "_film.film().as(",
                "film_3747728953_film.getId()",
                ".from(film_3747728953_film"
        );
    }

    @Test
    @DisplayName("Condition path to the same table as source")
    void selfConditionReference() {
        assertGeneratedContentContains(
                "selfConditionReference",
                "FILM.as(",
                "film_sequel_sequel_film.getId()",
                ".join(film_sequel_sequel_film).on(",
                ".sequel(film_sequel, film_sequel_sequel_film"
        );
    }
}
