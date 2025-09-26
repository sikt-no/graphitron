package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Inputs through referenced tables")
public class ReferenceInputTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/input";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains("table", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_address_223244161_customer_left"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".leftJoin(_a_film_2185543202_filmoriginallanguageidfkey_left",
                ".where(_a_film_2185543202_filmoriginallanguageidfkey_left.NAME.eq(name"
        );
    }

    @Test
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_address_223244161_customer_left"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_district_customer_left",
                ".district(_a_customer, _a_customer_district_customer_left)",
                ".where(_a_customer_district_customer_left.DISTRICT.eq(district"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_TABLE),
                "customer_2168032777_address_left.DISTRICT",
                ".leftJoin(_a_customer_2168032777_address_left"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_TABLE),
                "customer_district_address_left = ADDRESS.as(", // Note, no implicit join anymore.
                ".leftJoin(_a_customer_district_address_left).on(",
                ".district(_a_customer, _a_customer_district_address_left)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_TABLE),
                "customer_2168032777_address_left = _a_customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".leftJoin(_a_customer_2168032777_address_left).where(_a_customer_2168032777_address_left.DISTRICT.eq(district)).and(",
                ".district(_a_customer, _a_customer_2168032777_address_left)" // Note, no condition override unlike table case.
        );
    }

    @Test
    @DisplayName("Reference used inside an input type")
    void insideInputType() {
        assertGeneratedContentContains(
                "insideInputType", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(in.getDistrict()"
        );
    }

    @Test // TODO: This behaviour is undefined and results in illegal code.
    @DisplayName("Reference used on an input type")
    void onInputType() {
        assertGeneratedContentContains("onInputType", Set.of(CUSTOMER_TABLE), "customer.DISTRICT.eq(in.getDistrict())");
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains("multipleToSameTable", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(district1",
                ".and(_a_customer_2168032777_address_left.DISTRICT.eq(district2" // Silly case, but valid.
        );
    }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                "= _a_address.city()",
                ".and(_a_address_223244161_city_left.CITY_.eq(filter.getCity()))",
                ".leftJoin(_a_address_223244161_city_left)"
        );
    }

    @Test
    @DisplayName("Input with table and return type with field having reference")
    void inputWithTableReturnTypeWithReference() {
        var generated = generateFiles("inputWithTableReturnTypeWithReference");
        contains(generated,
                "_a_city_760939060_country = _a_city.country()",
                "_a_city_760939060_country.COUNTRY_"
        );

        doesNotContain(generated, "city()");
    }
}
