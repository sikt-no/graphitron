package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch queries - Alias usage")
public class ReferenceAliasTest extends ReferenceTest {
    @Test
    @DisplayName("Alias declaration for table path")
    void aliasTable() {
        assertGeneratedContentContains(
                "field/table", Set.of(CUSTOMER_QUERY),
                "customer_2952383337_address = _customer.address().as(\"address_1214171484\")",
                "customer_2952383337_address.DISTRICT",
                ".from(_customer",
                ".from(customer_2952383337_address"
        );
    }

    @Test
    @DisplayName("Alias declaration for key path with only one possible path between the tables")
    void aliasKeyWithSinglePath() {
        assertGeneratedContentContains(
                "field/keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "customer_2952383337_address.DISTRICT"
        );
    }

    @Test
    @DisplayName("Alias declaration for key path with multiple possible paths between the tables")
    void aliasKeyWithMultiplePaths() {
        assertGeneratedContentContains(
                "field/keyWithMultiplePaths",
                "film_3747728953_filmoriginallanguageidfkey = _film.filmOriginalLanguageIdFkey().as(\"filmOriginalLanguageIdFkey_789320123\")",
                "film_3747728953_filmoriginallanguageidfkey.NAME",
                ".from(film_3747728953_filmoriginallanguageidfkey))"
        );
    }

    @Test
    @DisplayName("Aliases declaration for two key paths using both at the same time")
    void aliasKeyUsingMultiplePaths() {
        assertGeneratedContentContains(
                "alias/keyUsingMultiplePaths",
                "film_3747728953_filmlanguageidfkey = _film.filmLanguageIdFkey().as(",
                "film_3747728953_filmoriginallanguageidfkey = _film.filmOriginalLanguageIdFkey().as(",
                "film_3747728953_filmoriginallanguageidfkey.NAME",
                "film_3747728953_filmlanguageidfkey.NAME",
                ".from(film_3747728953_filmlanguageidfkey",
                ".from(film_3747728953_filmoriginallanguageidfkey"
        );
    }

    @Test
    @DisplayName("Condition path")
    /**
     * TODO: This does not represent a valid schema because there is no two tables to join. Remove or modify test,
     *  perhaps?
     */
    void aliasCondition() {
        assertGeneratedContentContains(
                "field/condition", Set.of(CUSTOMER_QUERY),
                "customer_district_district_customer = CUSTOMER.as(",
                "customer_district_district_customer.DISTRICT",
                ".from(customer_district",
                ".where(_customer.CUSTOMER_ID.eq(customer_district.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Table path on a split query")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery/table", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer.address().as(",
                "DSL.row(customer_2952383337_address.getId()",
                ".from(_customer",
                ".where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))"
        );
    }

    @Test
    @DisplayName("Type containing a table type")
    void innerTable() {
        assertGeneratedContentContains(
                "alias/innerTable",
                "customer_2952383337_address = _customer.address().as(",
                "customer_2952383337_address.getId()"
        );
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "alias/innerTableSelfReference",
                "film_3747728953_film = _film.film().as(",
                ".row(DSL.row(film_3747728953_film.FILM_ID), film_3747728953_film.getId()"
        );
    }

    @Test
    @DisplayName("Type containing a table type with a reverse implicit path")
    void innerTableReverse() {
        assertGeneratedContentContains(
                "alias/innerTableReverse", Set.of(CUSTOMER_TABLE),
                "address_2030472956_customer = _address.customer().as(",
                "address_2030472956_customer.getId()"
        );
    }

    @Test
    @DisplayName("Both a table and a key set on the same path element")
    void tableAndKey() {
        assertGeneratedContentContains(
                "alias/tableAndKey", Set.of(CUSTOMER_QUERY),
                "_customer.address().as("
        );
    }

    @Test
    @DisplayName("Calls alias correctly when the type has over 22 fields")
    void over22Fields() {
        assertGeneratedContentContains(
                "alias/over22Fields",
                "film_3747728953_filmlanguageidfkey.NAME",
                "film_3747728953_filmlanguageidfkey.NAME.getDataType().convert(r[22])"
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type")
    void fromTypeWithoutTable() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTable", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2952383337_address = _customer.address().as("
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type and the target is not reachable without a reference")
    void fromTypeWithoutTableExtraStep() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTableExtraStep", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2952383337_address = _customer.address().as(",
                "address_1214171484_city = customer_2952383337_address.city().as("
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "splitQuery/throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer.address().as(", "customer_2952383337_address.city().as("
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "splitQuery/throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer.address().as(", "customer_2952383337_address.city().as("
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "splitQuery/throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as(\"customer_",
                "customer_city = CITY.as(\"city_"
        );
    }

    @Test
    @DisplayName("Path from a table to another table")
    void tableToTable() {
        assertGeneratedContentContains(
                "alias/tableToTable", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "customer_2952383337_address.city().as(",
                "address_1214171484_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a key")
    void tableToKey() {
        assertGeneratedContentContains(
                "alias/tableToKey", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "customer_2952383337_address.city().as(",
                "address_1214171484_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a condition")
    void tableToCondition() {
        assertGeneratedContentContains(
                "alias/tableToCondition", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(\"address_",
                "CITY.as(\"city_",
                "customer_2952383337_address_cityaddress_city.CITY",
                ".cityAddress(customer_2952383337_address, customer_2952383337_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to another table")
    void tableToTableReverse() {
        assertGeneratedContentContains(
                "alias/tableToTableReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "city_1887334959_address.customer().as(",
                "address_1356285680_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a key")
    void tableToKeyReverse() {
        assertGeneratedContentContains(
                "alias/tableToKeyReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "city_1887334959_address.customer().as(",
                "address_1356285680_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a condition")
    void tableToConditionReverse() {
        assertGeneratedContentContains(
                "alias/tableToConditionReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "CUSTOMER.as(",
                "city_1887334959_address_email_customer.EMAIL",
                ".email(city_1887334959_address, city_1887334959_address_email_customer)"
        );
    }

    @Test
    @DisplayName("Path from a key to another key")
    void keyToKey() {
        assertGeneratedContentContains(
                "alias/keyToKey", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "customer_2952383337_address.city().as(",
                "address_1214171484_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a table")
    void keyToTable() {
        assertGeneratedContentContains(
                "alias/keyToTable", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "customer_2952383337_address.city().as(",
                "address_1214171484_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a condition")
    void keyToCondition() {
        assertGeneratedContentContains(
                "alias/keyToCondition", Set.of(CUSTOMER_QUERY),
                "_customer.address().as(",
                "CITY.as(",
                "customer_2952383337_address_cityaddress_city.CITY",
                ".cityAddress(customer_2952383337_address, customer_2952383337_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to another key")
    void keyToKeyReverse() {
        assertGeneratedContentContains(
                "alias/keyToKeyReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "city_1887334959_address.customer().as(",
                "address_1356285680_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a table")
    void keyToTableReverse() {
        assertGeneratedContentContains(
                "alias/keyToTableReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "city_1887334959_address.customer().as(",
                "address_1356285680_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a condition")
    void keyToConditionReverse() {
        assertGeneratedContentContains(
                "alias/keyToConditionReverse", Set.of(CITY_QUERY),
                "_city.address().as(",
                "CUSTOMER.as(",
                "city_1887334959_address_email_customer.EMAIL",
                ".email(city_1887334959_address, city_1887334959_address_email_customer)"
        );
    }

    @Test
    @DisplayName("Path from a condition to another condition")
    void ConditionToCondition() {
        assertGeneratedContentContains(
                "alias/conditionToCondition", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.as(\"customer_29",
                "CUSTOMER.as(\"customer_20",
                "ADDRESS.as(\"address_",
                "CITY.as(\"city_",
                "customer_city_addresscustomer_address_cityaddress_city.CITY",
                ".addressCustomer(customer_city, customer_city_addresscustomer_address)",
                ".cityAddress(customer_city_addresscustomer_address, customer_city_addresscustomer_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a table")
    void conditionToTable() {
        assertGeneratedContentContains(
                "alias/conditionToTable", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(\"address_",
                "customer_city_addresscustomer_address.city().as(\"city_",
                "address_1981026222_city.CITY",
                ".addressCustomer(customer_city, customer_city_addresscustomer_address)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a key")
    void conditionToKey() {
        assertGeneratedContentContains(
                "alias/conditionToKey", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(\"address_",
                "customer_city_addresscustomer_address.city().as(\"city_",
                "address_1981026222_city.CITY",
                ".addressCustomer(customer_city, customer_city_addresscustomer_address)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to another condition")
    void conditionToConditionReverse() {
        assertGeneratedContentContains(
                "alias/conditionToConditionReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(\"address_",
                "CUSTOMER.as(\"customer_",
                "city_email_addresscity_address_email_customer.EMAIL",
                ".addressCity(city_email, city_email_addresscity_address)",
                ".email(city_email_addresscity_address, city_email_addresscity_address_email_customer)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a table")
    void conditionToTableReverse() {
        assertGeneratedContentContains(
                "alias/conditionToTableReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(\"address_",
                "city_email_addresscity_address.customer().as(\"customer_",
                "address_3210818138_customer.EMAIL",
                ".addressCity(city_email, city_email_addresscity_address)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a key")
    void conditionToKeyReverse() {
        assertGeneratedContentContains(
                "alias/conditionToKeyReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(\"address_",
                "city_email_addresscity_address.customer().as(\"customer_",
                "address_3210818138_customer.EMAIL",
                ".addressCity(city_email, city_email_addresscity_address)"
        );
    }
}
