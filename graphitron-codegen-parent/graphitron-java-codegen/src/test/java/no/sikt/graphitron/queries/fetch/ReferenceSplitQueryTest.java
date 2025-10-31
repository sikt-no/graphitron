package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
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
        return makeReferences(REFERENCE_CUSTOMER_CONDITION, REFERENCE_FILM_CONDITION, JAVA_RECORD_CUSTOMER);
    }

    @Test
    @Disabled("Disabled until alwaysUsePrimaryKeyInSplitQueries-property is removed.")
    @DisplayName("Foreign key columns should be selected in previous query")
    void previousQuery() {
        assertGeneratedContentContains(
                "previousQuery", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_a_customer.ADDRESS_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query")
    void previousQueryOnlyPrimaryKey() {
        assertGeneratedContentContains(
                "previousQuery", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_a_customer.CUSTOMER_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query on paginated fields")
    void previousQueryPaginated() {
        assertGeneratedContentContains(
                "previousQueryPaginated", Set.of(CUSTOMER_CONNECTION),
                "DSL.row(DSL.row(_a_store.STORE_ID)).mapping(Functions.nullOnAllNull(Store::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query on condition path")
    void previousQueryConditionPath() {
        assertGeneratedContentContains(
                "previousQueryConditionPath", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_a_customer.CUSTOMER_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @Disabled("Disabled until alwaysUsePrimaryKeyInSplitQueries-property is removed.")
    @DisplayName("Foreign key columns should be selected in previous query in nested type")
    void previousQueryNested() {
        assertGeneratedContentContains(
                "previousQueryNested", Set.of(CUSTOMER_QUERY),
                "row(_a_customer_2168032777_address.CITY_ID), _a_customer_2168032777_address.getId())" +
                        ".mapping(Functions.nullOnAllNull(Address::"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query in nested type")
    void previousQueryNestedOnlySplitQuery() { //TODO alias
        assertGeneratedContentContains(
                "previousQueryNested", Set.of(CUSTOMER_QUERY),
                "row(_a_address.ADDRESS_ID), _a_address.getId())" +
                        ".mapping(Functions.nullOnAllNull(Address::"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query for field returning single table interface")
    void previousQuerySingleTableInterface() {
        assertGeneratedContentContains(
                "previousQuerySingleTableInterface", Set.of(ADDRESS_BY_DISTRICT),
                "row(DSL.row(_a_city.CITY_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query for field returning multitable interface")
    void previousQueryMultitableInterface() {
        assertGeneratedContentContains(
                "previousQueryMultitableInterface",
                "row(DSL.row(_a_filmcategory.FILM_ID, _a_filmcategory.CATEGORY_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query for field returning multitable union")
    void previousQueryMultitableUnion() {
        assertGeneratedContentContains(
                "previousQueryMultitableUnion",
                "row(DSL.row(_a_filmcategory.FILM_ID, _a_filmcategory.CATEGORY_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address"
        );
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
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                ".from(_a_film_2185543202_filmoriginallanguageidfkey"
        );
    }

    @Test
    @DisplayName("Key path with multiple possible paths between the tables in a nested setting")
    void keyWithMultiplePathsAndNestedSplitQuery() {
        assertGeneratedContentContains(
                "keyWithMultiplePathsAndNestedSplitQuery",
                """
                            private static SelectField<Language> filmsWithoutPaginationForLanguage_film_language(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Language _a_language_filmlanguageidfkey) {
                                return DSL.row(
                                        DSL.row(_a_language_filmlanguageidfkey.LANGUAGE_ID),
                                        _a_language_filmlanguageidfkey.getId(),
                                        _a_language_filmlanguageidfkey.TITLE
                                ).mapping(Functions.nullOnAllNull(Language::new));
                            }
                        """
        );
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
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_NOT_GENERATED),
                "join(_a_customer_address_addresscustomer_address).on(",
                ".addressCustomer(_a_customer_address, _a_customer_address_addresscustomer_address)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_address.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_address_addresscustomer_address = ADDRESS.as(", // Note, no implicit join anymore.
                ".join(_a_customer_address_addresscustomer_address).on(",
                ".addressCustomer(_a_customer_address, _a_customer_address_addresscustomer_address)" // Note, condition overrides as it uses "on".
        );
    }

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2168032777_address = _a_customer.address().as(", // Note, implicit join is present when we use a key, but not table.
                ".from(_a_customer_2168032777_address).where(",
                ".addressCustomer(_a_customer, _a_customer_2168032777_address)", // Note, no condition override unlike table case.
                ".where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                ".join(_a_customer_city_citycustomer_city).on(",
                ".cityCustomer(_a_customer_city, _a_customer_city_citycustomer_city)"
        );
    }

    @Test
    @DisplayName("Indirect reverse table path")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
                ".from(_a_city_760939060_address",
                ".join(_a_address_609487378_customer"
        );
    }

    @Test
    @DisplayName("Table path on a list with split query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address)",
                "DSL.multiset(DSL.select(",
                ".fetchMap(_iv_r -> _iv_r.value1().valuesRow(), _iv_r -> _iv_r.value2().map(Record1::value1))"
        );
    }

    @Test
    @DisplayName("Table path on a nullable list with split query")
    void nullableList() {
        assertGeneratedContentContains(
                "nullableList", Set.of(CUSTOMER_NOT_GENERATED),
                ".from(_a_customer_2168032777_address)"
        );
    }

    @Test
    @DisplayName("Table path to the same table as source")
    void selfTableReference() {
        assertGeneratedContentContains(
                "selfTableReference",
                "film.film().as(",
                "film_2185543202_film.getId()",
                ".from(_a_film_2185543202_film"
        );
    }

    @Test
    @DisplayName("Key path to the same table as source")
    void selfKeyReference() {
        assertGeneratedContentContains(
                "selfKeyReference",
                "film.film().as(",
                "film_2185543202_film.getId()",
                ".from(_a_film_2185543202_film"
        );
    }

    @Test
    @DisplayName("Condition path to the same table as source")
    void selfConditionReference() {
        assertGeneratedContentContains(
                "selfConditionReference",
                "FILM.as(",
                "film_sequel_sequel_film.getId()",
                ".join(_a_film_sequel_sequel_film).on(",
                ".sequel(_a_film_sequel, _a_film_sequel_sequel_film"
        );
    }

    @Test
    @DisplayName("Reference from multi table interface")
    void fromMultitableInterface() {
        assertGeneratedContentContains(
                "fromMultitableInterface", Set.of(CUSTOMER_TABLE),
                "DSL.row(_a_payment.PAYMENT_ID), DSL.field(",
                "CustomerTable::new))",
                ".from(_a_payment_1831371789_customer)",
                ".from(_a_payment).where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment"
        );
    }

    @Test
    @DisplayName("Temporary test for reference ID argument (not node strategy) outside root ")
    void idArgumentOnNonRootQueryWithoutNodeStrategy() {
        assertGeneratedContentContains(
                "idArgumentOnNonRootQueryWithoutNodeStrategy", Set.of(CUSTOMER_TABLE),
                "customer_1589604633_store_left.hasStaffId(staffId)"
        );

    }

    @Test
    @DisplayName("splitQuery field after service returning java record")
    void afterJavaService() {
        assertGeneratedContentContains(
                "afterJavaService", Set.of(CUSTOMER_TABLE),
                "Set<Row1<Long>> _rk_customer", // TODO: improve resolver keys variable name
                ".select(DSL.row(_a_address.ADDRESS_ID), addressForCustomer_address(",
                ".from(_a_address)" +
                        ".where(DSL.row(_a_address.ADDRESS_ID).in(_rk_customer))" +
                        ".fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2)"
        );
    }

    @Test
    @DisplayName("Listed splitQuery field after service returning java record")
    void afterJavaServiceListed() {
        assertGeneratedContentContains(
                "afterJavaServiceListed", Set.of(CUSTOMER_TABLE),
                "Map<Row1<Long>, Address> addressForCustomer(DSLContext _iv_ctx, Set<Row1<Long>> _rk_customer",
                ".select(DSL.row(_a_address.ADDRESS_ID), addressForCustomer_address("
        );
    }

    @Test
    @DisplayName("Nested splitQuery field after service returning java record")
    void afterJavaServiceNested() {
        assertGeneratedContentContains(
                "afterJavaServiceNested", Set.of(CUSTOMER_TABLE),
                ".select(DSL.row(_a_address.ADDRESS_ID), addressForWrapper_address("
        );
    }

    @Test
    @DisplayName("Split query helpers with complex paths declare only valid alias chains")
    void splitQueryComplexReferencePathAliasFiltering() {
        assertGeneratedContentContains(
                "viaTables", Set.of(CUSTOMER_TABLE),
                """
                            private static SelectField<Store> storesThatHaveThisFilmForFilm_store(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Store _a_inventory_744357058_store) {
                                return DSL.row(_a_inventory_744357058_store.getId()).mapping(Functions.nullOnAllNull(Store::new));
                            }"""
                ,
                            """
                            private static SelectField<City> citiesWhereFilmIsStockedForFilm_city(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.City _a_address_2813127756_city) {
                                var _a_city_3468131846_address = _a_address_2813127756_city.address().as("address_2618309002");
                                var _a_address_2618309002_customer = _a_city_3468131846_address.customer().as("customer_672486254");
                                var _a_customer_672486254_payment = _a_address_2618309002_customer.payment().as("payment_3112599430");
                                return DSL.row(
                                        _a_address_2813127756_city.getId(),
                                        DSL.row(
                                                DSL.multiset(
                                                        DSL.select(citiesWhereFilmIsStockedForFilm_city_payments(_a_customer_672486254_payment))
                                                        .from(_a_city_3468131846_address)
                                                        .join(_a_address_2618309002_customer)
                                                        .join(_a_customer_672486254_payment)
                                                        .orderBy(_a_customer_672486254_payment.fields(_a_customer_672486254_payment.getPrimaryKey().getFieldsArray()))
                                                )
                                        ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                                ).mapping(Functions.nullOnAllNull(City::new));
                            }"""
        );
    }
}
