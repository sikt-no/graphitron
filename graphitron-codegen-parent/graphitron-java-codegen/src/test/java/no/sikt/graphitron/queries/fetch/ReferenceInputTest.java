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
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_district"
        );
    }

    @Test
    @DisplayName("Reverse table path - uses EXISTS to avoid row duplication from one-to-many LEFT JOIN")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                "DSL.exists(DSL.selectOne().from(_a_address_223244161_customer_left).where(",
                "customer_left.ADDRESS_ID.eq(_a_address.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_district"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".leftJoin(_a_film_2185543202_filmoriginallanguageidfkey_left",
                ".where(_a_film_2185543202_filmoriginallanguageidfkey_left.NAME.eq(_mi_name"
        );
    }

    @Test
    @DisplayName("Reverse key path - uses EXISTS to avoid row duplication from one-to-many LEFT JOIN")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                "DSL.exists(DSL.selectOne().from(_a_address_223244161_customer_left).where(",
                "customer_left.ADDRESS_ID.eq(_a_address.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_district_customer_left",
                ".district(_a_customer, _a_customer_district_customer_left)",
                ".where(_a_customer_district_customer_left.DISTRICT.eq(_mi_district"
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
                ".leftJoin(_a_customer_2168032777_address_left).where(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_district)).and(",
                ".district(_a_customer, _a_customer_2168032777_address_left)" // Note, no condition override unlike table case.
        );
    }

    @Test
    @DisplayName("Reference used inside an input type")
    void insideInputType() {
        assertGeneratedContentContains(
                "insideInputType", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_in.getDistrict()"
        );
    }

    @Test // TODO: This behaviour is undefined and results in illegal code.
    @DisplayName("Reference used on an input type")
    void onInputType() {
        assertGeneratedContentContains("onInputType", Set.of(CUSTOMER_TABLE), "customer.DISTRICT.eq(_mi_in.getDistrict())");
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains("multipleToSameTable", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_district1",
                ".and(_a_customer_2168032777_address_left.DISTRICT.eq(_mi_district2" // Silly case, but valid.
        );
    }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                "= _a_address.city()",
                ".and(_a_address_223244161_city_left.CITY_.eq(_mi_filter.getCity()))",
                ".leftJoin(_a_address_223244161_city_left)"
        );
    }

    @Test
    @DisplayName("Input with table and return type with field having reference")
    void inputWithTableReturnTypeWithScalarReference() {
        var generated = generateFiles("inputWithTableReturnTypeWithScalarReference");
        contains(generated,
                "_a_city_760939060_country = _a_city.country()",
                "_a_city_760939060_country.COUNTRY_"
        );

        doesNotContain(generated, "city()");
    }

    @Test
    @DisplayName("Split query with input parameters - helper methods exclude input parameters")
    void keyWithMultiplePathsAndNestedSplitQuery() {
        assertGeneratedContentContains(
                "keyWithMultiplePathsAndNestedSplitQuery",
                "DSL.select(filmsForLanguage_film())",
                "private static SelectField<Film> filmsForLanguage_film()"
        );
    }

    @Test
    @DisplayName("Reverse condition path - one-to-many with condition uses EXISTS with condition inside")
    void conditionBackwards() {
        assertGeneratedContentContains("conditionBackwards",
                "DSL.exists(DSL.selectOne().from(_a_address_email_customer_left).where(",
                ".email(_a_address, _a_address_email_customer_left)"
        );
    }

    @Test
    @DisplayName("Reverse key+condition path - condition appears inside EXISTS")
    void keyAndConditionBackwards() {
        assertGeneratedContentContains("keyAndConditionBackwards",
                "DSL.exists(DSL.selectOne().from(_a_address_223244161_customer_left).where(",
                ".email(_a_address, _a_address_223244161_customer_left)");
    }

    @Test
    @DisplayName("Multi-step path with one-to-many as final step uses EXISTS for the last join")
    void multiStepEndingInOneToMany() {
        assertGeneratedContentContains("multiStepEndingInOneToMany", Set.of(CUSTOMER_TABLE),
                ".leftJoin(_a_customer_2168032777_address_left)",
                "DSL.exists(DSL.selectOne().from(_a_address_2138977089_staff_left).where("
        );
    }

    @Test
    @DisplayName("Reverse key path with bidirectional FKs uses EXISTS when key disambiguates direction")
    void reverseWithMultiplePaths() {
        // Store and Staff have FKs in both directions (STAFF__STAFF_STORE_ID_FKEY and STORE__STORE_MANAGER_STAFF_ID_FKEY).
        // The explicit key ensures the reverse (one-to-many) direction is detected and wrapped in EXISTS.
        assertGeneratedContentContains(
                "reverseWithMultiplePaths",
                "DSL.exists(DSL.selectOne().from(_a_store_",
                "_left).where(",
                ".STORE_ID.eq(_a_store.STORE_ID)"
        );
    }

    @Test
    @DisplayName("Table path to table with self-referencing FK")
    void referencingTableWithSelfReference() {
        assertGeneratedContentContains(
                "referencingTableWithSelfReference",
                ".where(_a_filmactor_3175015397_film_left.FILM_ID.eq(_mi_filmId))"
        );

        resultDoesNotContain(
                "referencingTableWithSelfReference",
                "_a_filmactor_3175015397_film_left.film()"
        );
    }

    @Test
    @DisplayName("Reverse key path to table with self-referencing FK")
    void referencingTableWithSelfReferenceReverse() {
        assertGeneratedContentContains(
                "referencingTableWithSelfReferenceReverse",
                "selectOne().from(_a_language_3571151285_filmlanguageidfkey_left)"
        );

        resultDoesNotContain(
                "referencingTableWithSelfReferenceReverse",
                ".film()"
        );
    }
}
