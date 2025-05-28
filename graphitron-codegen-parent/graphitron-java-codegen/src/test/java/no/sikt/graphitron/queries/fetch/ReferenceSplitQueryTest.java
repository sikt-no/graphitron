package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.REFERENCE_CUSTOMER_CONDITION;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.REFERENCE_FILM_CONDITION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch queries - Fetching through referenced tables with splitQuery-directive")
public class ReferenceSplitQueryTest extends ReferenceTest {

    // Disabled until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/splitQuery";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(REFERENCE_CUSTOMER_CONDITION, REFERENCE_FILM_CONDITION);
    }

    @Test
    @DisplayName("Foreign key columns should be selected in previous query")
    void previousQuery() {
        assertGeneratedContentContains(
                "previousQuery", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_customer.ADDRESS_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query on paginated fields")
    void previousQueryPaginated() {
        assertGeneratedContentContains(
                "previousQueryPaginated", Set.of(CUSTOMER_CONNECTION),
                "DSL.row(DSL.row(_store.STORE_ID)).mapping(Functions.nullOnAllNull(Store::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query on condition path")
    void previousQueryConditionPath() {
        assertGeneratedContentContains(
                "previousQueryConditionPath", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_customer.CUSTOMER_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Foreign key columns should be selected in previous query in nested type")
    void previousQueryNested() {
        assertGeneratedContentContains(
                "previousQueryNested", Set.of(CUSTOMER_QUERY),
                "row(customer_2952383337_address.CITY_ID), customer_2952383337_address.getId())" +
                        ".mapping(Functions.nullOnAllNull(Address::"
        );
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
                ".where(_customer.CUSTOMER_ID.eq(customer_address.CUSTOMER_ID"
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
    @DisplayName("Table path on a list with split query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address)",
                "DSL.multiset(DSL.select(",
                ".fetchMap(Record2::value1, r -> r.value2().map(Record1::value1))"
        );
    }

    @Test
    @DisplayName("Table path on a nullable list with split query")
    void nullableList() {
        assertGeneratedContentContains(
                "nullableList", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(customer_2952383337_address)"
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

    @Test
    @DisplayName("Reference from multi table interface")
    void fromMultitableInterface() {
        assertGeneratedContentContains(
                "fromMultitableInterface", Set.of(CUSTOMER_TABLE),
                "_payment.getId(), DSL.field(",
                "CustomerTable::new))).from(payment_425747824_customer)",
                ".from(_payment).where(_payment.hasIds(paymentIds))"
        );
    }
}
