package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Fetching fields through referenced tables")
public class ReferenceFieldTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/field";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains("table", Set.of(CUSTOMER_QUERY), ".from(_a_customer_2168032777_address");
    }

    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                ".from(_a_address_223244161_customer"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains("keyWithSinglePath", Set.of(CUSTOMER_QUERY), ".from(_a_customer_2168032777_address");
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains("keyWithMultiplePaths", ".from(_a_film_2185543202_filmoriginallanguageidfkey");
    }

    @Test
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
                ".from(_a_address_223244161_customer"
        );
    }

    @Test
    @DisplayName("Condition path")
    void condition() { // Path exists but is overridden by condition.
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                ".join(_a_customer_district_district_customer).on(",
                ".district(_a_customer_district, _a_customer_district_district_customer)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_district.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_QUERY),
                "customer_2168032777_address.DISTRICT",
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_district_district_address = ADDRESS.as(", // Note, no implicit join anymore.
                ".join(_a_customer_district_district_address).on(",
                ".district(_a_customer_district, _a_customer_district_district_address)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_QUERY),
                "customer_2168032777_address = _a_customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".from(_a_customer_2168032777_address).where(",
                ".district(_a_customer, _a_customer_2168032777_address)" // Note, this produces a strange result with an ".and" and no ".on" or ".where".
        );
    }

    @Test
    @DisplayName("Multiple references to the same table")
    void multipleToSameTable() {
        assertGeneratedContentContains(
                "multipleToSameTable", Set.of(CUSTOMER_QUERY),
                "customer_2168032777_address.DISTRICT",
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Table path on a list of fields without split query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_QUERY),
                "DSL.multiset(",
                "customer_2168032777_address.ADDRESS_ID",
                ".from(_a_customer_2168032777_address)"
        );
    }

    @Test
    @DisplayName("Wrapper with @reference key to table type")
    void wrapperWithKey() {
        assertGeneratedContentContains(
                "wrapperWithKey",
                """
                          private static SelectField<Film> queryForQuery_film() {
                                var _a_film = FILM.as("film_2185543202");
                                var _a_film_2185543202_filmlanguageidfkey = _a_film.filmLanguageIdFkey().as("language_4253626089");
                                return DSL.row(
                                        DSL.row(
                                                DSL.field(
                                                        DSL.select(queryForQuery_film_language(_a_film_2185543202_filmlanguageidfkey))
                                                        .from(_a_film_2185543202_filmlanguageidfkey)
                                                )
                                        ).mapping(Functions.nullOnAllNull(LanguageWrapper::new))
                                ).mapping(Functions.nullOnAllNull(Film::new));
                            }
                        """,
                        """
                        private static SelectField<Language> queryForQuery_film_language(
                                no.sikt.graphitron.jooq.generated.testdata.public_.tables.Language _a_language) {
                            return DSL.row(_a_language.NAME).mapping(Functions.nullOnAllNull(Language::new));
                        }
                        """
        );
    }
}
