package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Fetch queries - Alias usage")
public class ReferenceAliasTest extends ReferenceTest {
    @Test
    @DisplayName("Alias declaration for table path")
    void aliasTable() {
        assertGeneratedContentContains(
                "field/table", Set.of(CUSTOMER_QUERY),
                "customer_address_left = CUSTOMER.address().as(\"address_166982810\")",
                "\"district\", customer_address_left.DISTRICT",
                ".from(CUSTOMER"
        );
    }

    @Test // TODO: Produces invalid code. Should throw error.
    @DisplayName("Ambiguous table path")
    void tableWithMultiplePaths() {
        assertGeneratedContentContains("alias/tableWithMultiplePaths", "\"name\", FILM.NAME");
    }

    @Test
    @DisplayName("Alias declaration for key path with only one possible path between the tables")
    void aliasKeyWithSinglePath() {
        assertGeneratedContentContains(
                "field/keyWithSinglePath", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "\"district\", customer_address_left.DISTRICT"
        );
    }

    @Test
    @DisplayName("Alias declaration for key path with multiple possible paths between the tables")
    void aliasKeyWithMultiplePaths() {
        assertGeneratedContentContains(
                "field/keyWithMultiplePaths",
                "film_filmoriginallanguageidfkey_left = FILM.filmOriginalLanguageIdFkey().as(\"filmOriginalLanguageIdFkey_1523086221\")",
                "\"name\", film_filmoriginallanguageidfkey_left.NAME",
                ".from(FILM"
        );
    }

    @Test
    @DisplayName("Aliases declaration for two key paths using both at the same time")
    void aliasKeyUsingMultiplePaths() {
        assertGeneratedContentContains(
                "alias/keyUsingMultiplePaths",
                "film_filmlanguageidfkey_left = FILM.filmLanguageIdFkey().as(",
                "film_filmoriginallanguageidfkey_left = FILM.filmOriginalLanguageIdFkey().as(",
                "film_filmlanguageidfkey_left.NAME",
                "film_filmoriginallanguageidfkey_left.NAME",
                ".leftJoin(film_filmlanguageidfkey_left",
                ".leftJoin(film_filmoriginallanguageidfkey_left"
        );
    }

    @Test
    @DisplayName("Condition path")
    void aliasCondition() {
        assertGeneratedContentContains(
                "field/condition", Set.of(CUSTOMER_QUERY),
                "customer_customer_left = CUSTOMER.as(",
                "\"district\", customer_customer_left.DISTRICT",
                ".from(CUSTOMER"
        );
    }

    @Test
    @DisplayName("Table path on a split query")
    void splitQuery() {
        assertGeneratedContentContains(
                "query/table", Set.of(CUSTOMER_NOT_GENERATED),
                "CUSTOMER.address().as(",
                ",DSL.row(customer_address_left.getId()",
                ".from(CUSTOMER",
                ".where(CUSTOMER.hasIds(customerIds",
                ".orderBy(customer_address_left.getIdFields()"
        );
    }

    @Test
    @DisplayName("Type containing a table type")
    void innerTable() {
        assertGeneratedContentContains(
                "alias/innerTable",
                "customer_address_left = CUSTOMER.address().as(",
                "customer_address_left.getId()"
        );
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "alias/innerTableSelfReference",
                "film_film_left = FILM.film().as(",
                ".row(film_film_left.getId()"
        );
    }

    @Test
    @DisplayName("Type containing a table type with a reverse implicit path")
    void innerTableReverse() {
        assertGeneratedContentContains(
                "alias/innerTableReverse", Set.of(CUSTOMER_TABLE),
                "address_customer_left = ADDRESS.customer().as(",
                "address_customer_left.getId()"
        );
    }

    @Test
    @DisplayName("Both a table and a key set on the same path element")
    void tableAndKey() {
        assertGeneratedContentContains(
                "alias/tableAndKey", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as("
        );
    }

    @Test
    @DisplayName("Calls alias correctly when the type has over 22 fields")
    void over22Fields() {
        assertGeneratedContentContains(
                "alias/over22Fields",
                "\"name\", film_filmlanguageidfkey_left.NAME",
                "film_filmlanguageidfkey_left.NAME.getDataType().convert(r[22])"
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type")
    void fromTypeWithoutTable() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTable", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_address_left = CUSTOMER.address().as("
        );
    }

    @Test
    @DisplayName("Reference starting from type inheriting a table from an earlier type and the target is not reachable without a reference")
    void fromTypeWithoutTableExtraStep() {
        assertGeneratedContentContains(
                "alias/fromTypeWithoutTableExtraStep", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_address_left = CUSTOMER.address().as(",
                "address_166982810_city_left = customer_address_left.city().as("
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "query/throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                "CUSTOMER.address().as(", "customer_address_left.city().as("
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "query/throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                "CUSTOMER.address().as(", "customer_address_left.city().as("
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "query/throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_citycustomer_city_left = CITY.as("
        );
    }

    @Test
    @DisplayName("Path from a table to another table")
    void tableToTable() {
        assertGeneratedContentContains(
                "alias/tableToTable", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "customer_address_left.city().as(",
                "address_166982810_city_left.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a key")
    void tableToKey() {
        assertGeneratedContentContains(
                "alias/tableToKey", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "customer_address_left.city().as(",
                "address_166982810_city_left.CITY"
        );
    }

    @Test
    @DisplayName("Path from a table to a condition")
    void tableToCondition() {
        assertGeneratedContentContains(
                "alias/tableToCondition", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "CITY.as(",
                "customer_cityaddress_city_left.CITY",
                ".cityAddress(customer_address_left, customer_cityaddress_city_left)"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to another table")
    void tableToTableReverse() {
        assertGeneratedContentContains(
                "alias/tableToTableReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "city_address_left.customer().as(",
                "address_2545393164_customer_left.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a key")
    void tableToKeyReverse() {
        assertGeneratedContentContains(
                "alias/tableToKeyReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "city_address_left.customer().as(",
                "address_2545393164_customer_left.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a table to a condition")
    void tableToConditionReverse() {
        assertGeneratedContentContains(
                "alias/tableToConditionReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "CUSTOMER.as(",
                "city_email_customer_left.EMAIL",
                ".email(city_address_left, city_email_customer_left)"
        );
    }

    @Test
    @DisplayName("Path from a key to another key")
    void keyToKey() {
        assertGeneratedContentContains(
                "alias/keyToKey", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "customer_address_left.city().as(",
                "address_166982810_city_left.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a table")
    void keyToTable() {
        assertGeneratedContentContains(
                "alias/keyToTable", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "customer_address_left.city().as(",
                "address_166982810_city_left.CITY"
        );
    }

    @Test
    @DisplayName("Path from a key to a condition")
    void keyToCondition() {
        assertGeneratedContentContains(
                "alias/keyToCondition", Set.of(CUSTOMER_QUERY),
                "CUSTOMER.address().as(",
                "CITY.as(",
                "customer_cityaddress_city_left.CITY",
                ".cityAddress(customer_address_left, customer_cityaddress_city_left)"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to another key")
    void keyToKeyReverse() {
        assertGeneratedContentContains(
                "alias/keyToKeyReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "city_address_left.customer().as(",
                "address_2545393164_customer_left.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a table")
    void keyToTableReverse() {
        assertGeneratedContentContains(
                "alias/keyToTableReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "city_address_left.customer().as(",
                "address_2545393164_customer_left.EMAIL"
        );
    }

    @Test
    @DisplayName("Reverse path from a key to a condition")
    void keyToConditionReverse() {
        assertGeneratedContentContains(
                "alias/keyToConditionReverse", Set.of(CITY_QUERY),
                "CITY.address().as(",
                "CUSTOMER.as(",
                "city_email_customer_left.EMAIL",
                ".email(city_address_left, city_email_customer_left)"
        );
    }

    @Test
    @DisplayName("Path from a condition to another condition")
    void conditionToCondition() {
        assertGeneratedContentContains(
                "alias/conditionToCondition", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(",
                "CITY.as(",
                "customer_cityaddress_city_left.CITY",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)",
                ".cityAddress(customer_addresscustomer_address_left, customer_cityaddress_city_left)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a table")
    void conditionToTable() {
        assertGeneratedContentContains(
                "alias/conditionToTable", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(",
                "customer_addresscustomer_address_left.city().as(",
                "customer_2082681704_city_left.CITY",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)"
        );
    }

    @Test
    @DisplayName("Path from a condition to a key")
    void conditionToKey() {
        assertGeneratedContentContains(
                "alias/conditionToKey", Set.of(CUSTOMER_QUERY),
                "ADDRESS.as(",
                "customer_addresscustomer_address_left.city().as(",
                "customer_2082681704_city_left.CITY",
                ".addressCustomer(CUSTOMER, customer_addresscustomer_address_left)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to another condition")
    void conditionToConditionReverse() {
        assertGeneratedContentContains(
                "alias/conditionToConditionReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(",
                "CUSTOMER.as(",
                "city_email_customer_left.EMAIL",
                ".addressCity(CITY, city_addresscity_address_left)",
                ".email(city_addresscity_address_left, city_email_customer_left)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a table")
    void conditionToTableReverse() {
        assertGeneratedContentContains(
                "alias/conditionToTableReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(",
                "city_addresscity_address_left.customer().as(",
                "city_1715712748_customer_left.EMAIL",
                ".addressCity(CITY, city_addresscity_address_left)"
        );
    }

    @Test
    @DisplayName("Reverse path from a condition to a key")
    void conditionToKeyReverse() {
        assertGeneratedContentContains(
                "alias/conditionToKeyReverse", Set.of(CITY_QUERY),
                "ADDRESS.as(",
                "city_addresscity_address_left.customer().as(",
                "city_1715712748_customer_left.EMAIL",
                ".addressCity(CITY, city_addresscity_address_left)"
        );
    }
}
