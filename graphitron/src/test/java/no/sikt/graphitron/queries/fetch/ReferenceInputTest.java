package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Inputs through referenced tables")
public class ReferenceInputTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/input";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains("table", Set.of(CUSTOMER_TABLE),
                ".leftJoin(customer_2952383337_address_left",
                ".where(customer_2952383337_address_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(address_2030472956_customer_left"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_TABLE),
                ".leftJoin(customer_2952383337_address_left",
                ".where(customer_2952383337_address_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".leftJoin(film_3747728953_filmoriginallanguageidfkey_left",
                ".where(film_3747728953_filmoriginallanguageidfkey_left.NAME.eq(name"
        );
    }

    @Test
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(address_2030472956_customer_left"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_customer_district_customer_left",
                ".district(_customer, _customer_district_customer_left)",
                ".where(_customer_district_customer_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_TABLE),
                "customer_2952383337_address_left.DISTRICT",
                ".leftJoin(customer_2952383337_address_left"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_TABLE),
                "_customer_district_address_left = ADDRESS.as(", // Note, no implicit join anymore.
                ".leftJoin(_customer_district_address_left).on(",
                ".district(_customer, _customer_district_address_left)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_TABLE),
                "customer_2952383337_address_left = _customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".leftJoin(customer_2952383337_address_left).where(customer_2952383337_address_left.DISTRICT.eq(district)).and(",
                ".district(_customer, customer_2952383337_address_left)" // Note, no condition override unlike table case.
        );
    }

    @Test
    @DisplayName("Reference used inside an input type")
    void insideInputType() {
        assertGeneratedContentContains(
                "insideInputType", Set.of(CUSTOMER_TABLE),
                ".leftJoin(customer_2952383337_address_left",
                ".where(customer_2952383337_address_left.DISTRICT.eq(in.getDistrict()"
        );
    }

    @Test // TODO: This behaviour is undefined and results in illegal code.
    @DisplayName("Reference used on an input type")
    void onInputType() {
        assertGeneratedContentContains("onInputType", Set.of(CUSTOMER_TABLE), "_customer.DISTRICT.eq(in.getDistrict())");
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains("multipleToSameTable", Set.of(CUSTOMER_TABLE),
                ".leftJoin(customer_2952383337_address_left",
                ".where(customer_2952383337_address_left.DISTRICT.eq(district1",
                ".and(customer_2952383337_address_left.DISTRICT.eq(district2" // Silly case, but valid.
        );
    }

    @Test
    @DisplayName("Table path on input on subquery")
    void subquery() {
        assertGeneratedContentContains("subquery", Set.of(CUSTOMER_TABLE),
                ".from(address_2030472956_customer).leftJoin(customer_2337142794_address_left)",
                ".where(customer_2337142794_address_left.DISTRICT.eq(district",
                ".from(_address).fetch"
        );
    }

    @Test
    @DisplayName("Condition path on input on subquery")
    void conditionOnSubquery() {
        assertGeneratedContentContains(
                "conditionOnSubquery", Set.of(CUSTOMER_TABLE),
                ".from(_customer).leftJoin(_customer_citycustomer_customer_left",
                ".cityCustomer(_customer, _customer_citycustomer_customer_left)",
                ".where(_customer_citycustomer_customer_left.COUNTRY_ID.eq(countryId)))"
        );
    }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                "= _address.city()",
                ".and(address_2030472956_city_left.CITY_.eq(filter.getCity()))",
                ".leftJoin(address_2030472956_city_left)");
    }
}
