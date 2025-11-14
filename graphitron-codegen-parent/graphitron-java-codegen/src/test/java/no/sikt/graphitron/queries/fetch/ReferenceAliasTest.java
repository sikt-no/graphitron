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
                "_a_customer_2168032777_address = _a_customer.address().as(\"address_2138977089\")",
                "_a_customer_2168032777_address.DISTRICT",
                ".from(_a_customer",
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Alias declaration for key path with only one possible path between the tables")
    void aliasKeyWithSinglePath() {
        assertGeneratedContentContains(
                "field/keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "customer_2168032777_address.DISTRICT"
        );
    }

    @Test
    @DisplayName("Alias declaration for key path with multiple possible paths between the tables")
    void aliasKeyWithMultiplePaths() {
        assertGeneratedContentContains(
                "field/keyWithMultiplePaths",
                "film_2185543202_filmoriginallanguageidfkey = _a_film.filmOriginalLanguageIdFkey().as(\"language_3878631203\")",
                "film_2185543202_filmoriginallanguageidfkey.NAME",
                ".from(_a_film_2185543202_filmoriginallanguageidfkey))"
        );
    }

    @Test
    @DisplayName("Aliases declaration for two key paths using both at the same time")
    void aliasKeyUsingMultiplePaths() {
        assertGeneratedContentContains(
                "alias/keyUsingMultiplePaths",
                "film_2185543202_filmlanguageidfkey = _a_film.filmLanguageIdFkey().as(",
                "film_2185543202_filmoriginallanguageidfkey = _a_film.filmOriginalLanguageIdFkey().as(",
                "film_2185543202_filmoriginallanguageidfkey.NAME",
                "film_2185543202_filmlanguageidfkey.NAME",
                ".from(_a_film_2185543202_filmlanguageidfkey",
                ".from(_a_film_2185543202_filmoriginallanguageidfkey"
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
                ".from(_a_customer_district",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_district.CUSTOMER_ID"
        );
    }


    @Test
    @DisplayName("Type containing a table type")
    void innerTable() {
        assertGeneratedContentContains(
                "alias/innerTable",
                "customer_2168032777_address = _a_customer.address().as(",
                "customer_2168032777_address.getId()"
        );
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "alias/innerTableSelfReference",
                "film_2185543202_film = _a_film.film().as(",
                ".row(DSL.row(_a_film_2185543202_film.FILM_ID), _a_film_2185543202_film.getId()"
        );
    }

    @Test
    @DisplayName("Type containing a table type with a reverse implicit path")
    void innerTableReverse() {
        assertGeneratedContentContains(
                "alias/innerTableReverse", Set.of(CUSTOMER_TABLE),
                "address_223244161_customer = _a_address.customer().as(",
                "address_223244161_customer.getId()"
        );
    }

    @Test
    @DisplayName("Both a table and a key set on the same path element")
    void tableAndKey() {
        assertGeneratedContentContains(
                "alias/tableAndKey", Set.of(CUSTOMER_QUERY),
                "customer.address().as("
        );
    }

    @Test
    @DisplayName("Calls alias correctly when the type has over 22 fields")
    void over22Fields() {
        assertGeneratedContentContains(
                "alias/over22Fields",
                "film_2185543202_filmlanguageidfkey.NAME",
                "film_2185543202_filmlanguageidfkey.NAME.getDataType().convert(_iv_r[22])"
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type")
    void fromTypeWithoutTable() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTable", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2168032777_address = _a_customer.address().as("
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type and the target is not reachable without a reference")
    void fromTypeWithoutTableExtraStep() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTableExtraStep", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2168032777_address = _a_customer.address().as(",
                "address_2138977089_city = _a_customer_2168032777_address.city().as("
        );
    }

    @Test
    @DisplayName("Path from a table to another table")
    void tableToTable() {
        assertGeneratedContentContains(
                "alias/tableToTable", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "customer_2168032777_address.city().as(",
                "address_2138977089_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a key")
    void tableToKey() {
        assertGeneratedContentContains(
                "alias/tableToKey", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "customer_2168032777_address.city().as(",
                "address_2138977089_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a condition")
    void tableToCondition() {
        assertGeneratedContentContains(
                "alias/tableToCondition", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "CITY.as(",
                "customer_2168032777_address_cityaddress_city.CITY",
                ".cityAddress(_a_customer_2168032777_address, _a_customer_2168032777_address_cityaddress_city)"
//                "_customer.address().as(\"address_",
//                "CITY.as(\"city_",
//                "customer_2952383337_address_cityaddress_city.CITY",
//                ".cityAddress(customer_2952383337_address, customer_2952383337_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to another table")
    void tableToTableReverse() {
        assertGeneratedContentContains(
                "alias/tableToTableReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "city_760939060_address.customer().as(",
                "address_609487378_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a key")
    void tableToKeyReverse() {
        assertGeneratedContentContains(
                "alias/tableToKeyReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "city_760939060_address.customer().as(",
                "address_609487378_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a condition")
    void tableToConditionReverse() {
        assertGeneratedContentContains(
                "alias/tableToConditionReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "CUSTOMER.as(",
                "city_760939060_address_email_customer.EMAIL",
                ".email(_a_city_760939060_address, _a_city_760939060_address_email_customer)"
        );
    }

    @Test
    @DisplayName("Path from a key to another key")
    void keyToKey() {
        assertGeneratedContentContains(
                "alias/keyToKey", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "customer_2168032777_address.city().as(",
                "address_2138977089_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a table")
    void keyToTable() {
        assertGeneratedContentContains(
                "alias/keyToTable", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "customer_2168032777_address.city().as(",
                "address_2138977089_city.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a condition")
    void keyToCondition() {
        assertGeneratedContentContains(
                "alias/keyToCondition", Set.of(CUSTOMER_QUERY),
                "customer.address().as(",
                "CITY.as(",
                "customer_2168032777_address_cityaddress_city.CITY",
                ".cityAddress(_a_customer_2168032777_address, _a_customer_2168032777_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to another key")
    void keyToKeyReverse() {
        assertGeneratedContentContains(
                "alias/keyToKeyReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "city_760939060_address.customer().as(",
                "address_609487378_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a table")
    void keyToTableReverse() {
        assertGeneratedContentContains(
                "alias/keyToTableReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "city_760939060_address.customer().as(",
                "address_609487378_customer.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a condition")
    void keyToConditionReverse() {
        assertGeneratedContentContains(
                "alias/keyToConditionReverse", Set.of(CITY_QUERY),
                "city.address().as(",
                "CUSTOMER.as(",
                "city_760939060_address_email_customer.EMAIL",
                ".email(_a_city_760939060_address, _a_city_760939060_address_email_customer)"
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
                ".addressCustomer(_a_customer_city, _a_customer_city_addresscustomer_address)",
                ".cityAddress(_a_customer_city_addresscustomer_address, _a_customer_city_addresscustomer_address_cityaddress_city)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a table")
    void conditionToTable() {
        assertGeneratedContentContains(
                "alias/conditionToTable", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(",
                "customer_city_addresscustomer_address.city().as(",
                "address_4217265996_city.CITY",
                ".addressCustomer(_a_customer_city, _a_customer_city_addresscustomer_address)"
//                "ADDRESS.as(\"address_",
//                "customer_city_addresscustomer_address.city().as(\"city_",
//                "address_1981026222_city.CITY",
//                ".addressCustomer(customer_city, customer_city_addresscustomer_address)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a key")
    void conditionToKey() {
        assertGeneratedContentContains(
                "alias/conditionToKey", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(",
                "customer_city_addresscustomer_address.city().as(",
                "address_4217265996_city.CITY",
                ".addressCustomer(_a_customer_city, _a_customer_city_addresscustomer_address)"
//                "ADDRESS.as(\"address_",
//                "customer_city_addresscustomer_address.city().as(\"city_",
//                "address_1981026222_city.CITY",
//                ".addressCustomer(customer_city, customer_city_addresscustomer_address)"
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
                ".addressCity(_a_city_email, _a_city_email_addresscity_address)",
                ".email(_a_city_email_addresscity_address, _a_city_email_addresscity_address_email_customer)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a table")
    void conditionToTableReverse() {
        assertGeneratedContentContains(
                "alias/conditionToTableReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(",
                "city_email_addresscity_address.customer().as(",
                "address_3704850880_customer.EMAIL",
                ".addressCity(_a_city_email, _a_city_email_addresscity_address)"
//                "ADDRESS.as(\"address_",
//                "city_email_addresscity_address.customer().as(\"customer_",
//                "address_3210818138_customer.EMAIL",
//                ".addressCity(city_email, city_email_addresscity_address)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a key")
    void conditionToKeyReverse() {
        assertGeneratedContentContains(
                "alias/conditionToKeyReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(",
                "city_email_addresscity_address.customer().as(",
                "address_3704850880_customer.EMAIL",
                ".addressCity(_a_city_email, _a_city_email_addresscity_address)"
//                "ADDRESS.as(\"address_",
//                "city_email_addresscity_address.customer().as(\"customer_",
//                "address_3210818138_customer.EMAIL",
//                ".addressCity(city_email, city_email_addresscity_address)"
        );
    }

    // ===== Reference directive in combination with split query =====

    @Test
    @DisplayName("Table path on a split query")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery/table", Set.of(CUSTOMER_NOT_GENERATED),
                "customer.address().as(",
                "DSL.row(_a_customer_2168032777_address.getId()",
                ".from(_a_customer)",
                ".where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))"
//                "_customer = CUSTOMER.as(\"customer_2",
//                "customer_2952383337_address = _customer.address().as(\"address_1",
//                "DSL.row(customer_2952383337_address.getId()",
//                ".where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))"
        );
    }

    @Test
    @DisplayName("Reverse table path on a split query")
    void reversePathOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/tableBackwards", Set.of(CUSTOMER_TABLE),
                "_address = ADDRESS.as(\"address_2",
                "address_2030472956_customer = _address.customer().as(\"customer_2"
        );
    }

    @Test
    @DisplayName("Indirect reverse table path on a split query")
    void indirectReversePathOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/throughTableBackwards", Set.of(CUSTOMER_TABLE),
                "_city = CITY.as(\"city_1",
                "city_1887334959_address = _city.address().as(\"address_1",
                "address_1356285680_customer = city_1887334959_address.customer().as(\"customer_2");
    }

    @Test
    @DisplayName("Table path to the same table as source on a split query")
    void selfTableReferenceOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/selfTableReference",
                "_film = FILM.as(\"film_3",
                "film_3747728953_film = _film.film().as(\"film_2"
        );
    }

    @Test
    @DisplayName("Indirect table path on a split query")
    void throughTableOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                "customer.address().as(", "customer_2168032777_address.city().as("
//                "_customer = CUSTOMER.as(\"customer_2",
//                "customer_2952383337_address = _customer.address().as(\"address_1",
//                "address_1214171484_city = customer_2952383337_address.city().as(\"city_2"
        );
    }

    @Test
    @DisplayName("Indirect key path on a split query")
    void throughKeyOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                "customer.address().as(", "customer_2168032777_address.city().as("
//                "_customer = CUSTOMER.as(\"customer_2",
//                "customer_2952383337_address = _customer.address().as(\"address_1",
//                "address_1214171484_city = customer_2952383337_address.city().as(\"city_2"
        );
    }

    @Test
    @DisplayName("Key path to the same table as source on a split query")
    void selfKeyReferenceOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/selfKeyReference",
                "_film = FILM.as(\"film_3",
                "film_3747728953_film = _film.film().as(\"film_2"
        );
    }

    @Test
    @DisplayName("Indirect condition path on a split query")
    void throughConditionOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as(\"customer_",
                "customer_city = CITY.as(\"city_"
//                "_customer = CUSTOMER.as(\"customer_2",
//                "customer_city = CITY.as(\"city_2"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element on a split query")
    void tableAndConditionOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as(\"customer_2",
                "customer_address = ADDRESS.as(\"address_2"
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element on a split query")
    void keyAndConditionOnSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as(\"customer_2",
                "customer_2952383337_address = _customer.address().as(\"address_1"
        );
    }
}
