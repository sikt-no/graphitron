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


    //########  Validate previous query generation ########

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
    void previousQueryNestedOnlySplitQuery() {
        assertGeneratedContentContains(
                "previousQueryNested", Set.of(CUSTOMER_QUERY),
                "row(_a_customer_2168032777_address.ADDRESS_ID), _a_customer_2168032777_address.getId())" +
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


    // ######## Validate new resolver generation ########

    // ===== Reference directive with only tables =====

    /**
     * Given that A has a field referencing B, and this field includes a single reference directive with only the table
     * parameter B, and there exists a direct relation from A to B, when a new resolver is generated, a JOIN clause
     * should be created. The JOIN clause must include table B retrieved through A.
     */
    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
                """
        );
    }

    /**
     * Given that A has a field referencing B, and this field includes a single reference directive with only the table
     * parameter B, and there is no direct relation from A to B, but an inverse relation exists from B to A, when a new
     * resolver is generated, a JOIN clause should be created. The JOIN clause must include table B, retrieved through
     * the inverse relation.
     */
    @Test
    @DisplayName("Reverse table path")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
                "_address = ADDRESS.as",
                "address_2030472956_customer = _address.customer().as",
                """
                .select(
                        DSL.row(_address.ADDRESS_ID),
                        DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_address)
                .join(address_2030472956_customer)
                """
        );
    }

    /**
     * Given that A has a field referencing B and this field is nullable and has a single reference directive with only
     * the table parameter B, and a direct relation exists from A to B, when a new resolver is generated, then a JOIN
     * clause should be created. The JOIN clause must include table B retrieved through A.
     */
    @Test
    @DisplayName("Reference on a nullable field")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                """
        );
    }

    /**
     * Given that A has a field referencing C and this field has a single reference directive with only the table
     * parameter B, and there is no direct relation from A to C, but there are relations from A to B and from B to C,
     * when a new resolver is generated, multiple JOIN clauses should be created. The first JOIN clause must include
     * table B retrieved through A, and the second JOIN clause must include C retrieved through B.
     */
    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                "address_1214171484_city = customer_2952383337_address.city().as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(address_1214171484_city.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .join(address_1214171484_city)
                """
        );
    }

    /**
     * Given that A has a field referencing C, and this field includes a single reference directive with only the table
     * parameter B, and there is no direct relation from A to C, nor from A to B or B to C, but inverse relations
     * exists from B to A and from C to B, when a new resolver is generated, multiple JOIN clauses should be created.
     * The first JOIN clause must include table B retrieved through the inverse relation from B to A, and the second
     * JOIN clause must include table C retrieved through the inverse relation from C to B.
     */
    @Test
    @DisplayName("Indirect reverse table path")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
                "_city = CITY.as",
                "city_1887334959_address = _city.address().as",
                "address_1356285680_customer = city_1887334959_address.customer()",
                """
                .select(
                        DSL.row(_city.CITY_ID),
                        DSL.row(address_1356285680_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_city)
                .join(city_1887334959_address)
                .join(address_1356285680_customer)
                """
        );
    }

    /**
     * Given that A has a field referencing itself, and this field includes a single reference directive with only the
     * table parameter A, and there is a direct relation from A to itself, when a new resolver is generated, a JOIN
     * clause should be created. The JOIN clause must include table A retrieved through the self-relation.
     */
    @Test
    @DisplayName("Table path to the same table as source")
    void selfTableReference() {
        assertGeneratedContentContains(
                "selfTableReference",
                "_film = FILM.as",
                "film_3747728953_film = _film.film().as",
                """
                .select(
                        DSL.row(_film.FILM_ID),
                        DSL.row(
                                DSL.row(film_3747728953_film.FILM_ID),
                                film_3747728953_film.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(_film)
                .join(film_3747728953_film)
                """
        );
    }

    /**
     * Given that A has a field referencing a list of B, and this field includes a single reference directive with only
     * the table parameter B, and there is a direct relation from A to B, when a new resolver is generated, a JOIN
     * clause should be created. The JOIN clause must include table B retrieved through A.
     */
    @Test
    @DisplayName("Table path on a list with split query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                "orderFields = customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray())",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .orderBy(orderFields)
                .fetchGroups(r -> r.value1().valuesRow(), Record2::value2);
                """
        );
    }

    /**
     * Given that A has a field referencing a NULLABLE list of B, and this field includes a single reference directive
     * with only the table parameter B, and there is a direct relation from A to B, when a new resolver is generated, a
     * JOIN clause should be created. The JOIN clause must include table B retrieved through A.
     */
    @Test
    @DisplayName("Table path on a nullable list with split query")
    void nullableList() {
        assertGeneratedContentContains(
                "nullableList", Set.of(CUSTOMER_NOT_GENERATED),
                    "_customer = CUSTOMER.as",
                 "customer_2952383337_address = _customer.address().as",
                 "orderFields = customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray())",
                 """
                 .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .orderBy(orderFields)
                .fetchGroups(r -> r.value1().valuesRow(), Record2::value2);
                """
        );
    }

    @Test
    @DisplayName("Table reference on a multi-level type")
    void tableOnMultiLevelType() {
        assertGeneratedContentMatches("tableOnMultiLevelType");
    }


    // ===== Reference directive with only keys =====

    /**
     * Given that A has a field referencing B, and this field has a reference directive containing a key defined from
     * same A to B, while a relation already exists from A to B, when a new resolver is generated, we expect that an
     * explicit JOIN between these two tables using the key, is created.
     */
    @Test
    @DisplayName("Key path with only one possible path between the tables")
    // TODO: Alias name is not using name based on key. Is this correct? What is the rule here?
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                """
        );
    }

    /**
     * Given that A has a field referencing B, and this field includes a reference directive containing a key, and
     * multiple relations exist from A to B, when a new resolver is generated, we expect that the specified key will
     * be used to create an explicit JOIN between these two tables.
     */
    @Test
    @DisplayName("Key path with multiple possible paths between the tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
                "_film = FILM.as",
                "film_3747728953_filmoriginallanguageidfkey = _film.filmOriginalLanguageIdFkey().as",
                """
                .select(
                        DSL.row(_film.FILM_ID),
                        DSL.row(film_3747728953_filmoriginallanguageidfkey.getId()).mapping(Functions.nullOnAllNull(Language::new))
                )
                .from(_film)
                .join(film_3747728953_filmoriginallanguageidfkey)
                """
        );
    }

    /**
     * Given that A has a field that refers to B and that field has a reference directive containing a key defined from
     * the inverse B to A, and no relation exist from A to B, when a new resolver is generated, then an implicit JOIN
     * between these two tables using the key should be generated for the new resolver.
     */
    @Test
    @DisplayName("Reverse key path")
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
               "_address = ADDRESS.as",
                "address_2030472956_customer = _address.customer().as",
                """
                .select(
                        DSL.row(_address.ADDRESS_ID),
                        DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_address)
                .join(address_2030472956_customer)
                """
        );
    }

    /**
     * Given that A has a field referencing C, and this field has a reference directive containing a key defined from
     * A to B, and there is no direct relation from A to C, but there are a relation from A to B and from B to C, when
     * a new resolver is generated, we expect that implicit JOIN path is crated. The first JOIN clause must include
     * table B retrieved through A, and the second JOIN clause must include C retrieved thhrough B.
     */
    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                "address_1214171484_city = customer_2952383337_address.city().as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(address_1214171484_city.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .join(address_1214171484_city)
                """
        );
    }

    /**
     * Given that A has a field referencing itselt, and this field includes a reference directive containing a key that
     * is defined from A to A, and therefore creates a direct relation from A to itself, when a new resolver is
     * generated, we expect that an implicit JOIN path is created. The JOIN clause must include A retrieved through A.
     */
    @Test
    @DisplayName("Key path to the same table as source")
    void selfKeyReference() {
        assertGeneratedContentContains(
                "selfKeyReference",
               "_film = FILM.as",
               "film_3747728953_film = _film.film().as",
                """
                .select(
                        DSL.row(_film.FILM_ID),
                        DSL.row(
                                DSL.row(film_3747728953_film.FILM_ID),
                                film_3747728953_film.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(_film)
                .join(film_3747728953_film)
                """
        );
    }


    // ===== Reference directive with only conditions =====

    /**
     * Given that A has a field referencing B, and this field includes a single reference directive with only a
     * condition, and there exists a relation from A to B, when a new resolver is generated, we expect that JOIN and ON
     * clauses are created. The JOIN clause should contain table B, and the ON clause should use the condition method
     * with tables A and B as arguments.
     */
    @Test
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_address = ADDRESS.as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_address)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_address))
                """
        );
    }

    /**
     * Given that A has a field referencing C, and this field includes a single reference directive containing only a
     * condition, and there is no direct relation from A to C, when a new resolver is generated we expect that a JOIN
     * and ON cluase is created. The JOIN clause must include table C and the ON clause must include the condition
     * method with tables A and C as arguments.
     */
    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_city = CITY.as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_city.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_customer)
                .join(customer_city)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.cityCustomer(_customer, customer_city))
                """
        );
    }

    /**
     * Given that A has a field referencing itself, and this field includes a reference directive containing a
     * condition, and there is a direct relation from A to itself, when a new resolver is generated, we expect that a
     * JOIN clause and an ON clause is created. The JOIN clause must include table A and the ON clause must include the
     * condition method with tables A and itself as arguments.
     */
    @Test
    @DisplayName("Condition path to the same table as source")
    void selfConditionReference() {
        assertGeneratedContentContains(
                "selfConditionReference",
                "_film = FILM.as",
                "film_sequel = FILM.as",
                """
                .select(
                        DSL.row(_film.FILM_ID),
                        DSL.row(
                                DSL.row(film_sequel.FILM_ID),
                                film_sequel.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(_film)
                .join(film_sequel)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceFilmCondition.sequel(_film, film_sequel))
                """
        );
    }


    // ===== Reference directive with table and condition =====

    /**
     * Given that A has field referencing B, and this field includes a single reference directive containing both a
     * table B and a condition, and there is a direct relation from A to B, when a new resolver is generated, a JOIN
     * clause and an ON clause should be created. The JOIN clause must include table B, and the ON clause must use the
     * condition method with tables A and B as arguments.
     */
    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_address = ADDRESS.as",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_address)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_address))
                """
        );
    }


    // ===== Reference directive with key and condition =====

    /**
     * Given that A has a field referencing B, and this field includes a single reference directive containing both a
     * key to table B and a condition, and there is a direct relation from A to B, when a new resolver is generated, a
     * JOIN clause and a WHERE clause should be created. The JOIN clause must include table B retrieved through A, and
     * the WHERE clause must use the condition method with table A, as well as table B retrieved through A, as
     * arguments.
     */
    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as(",
                """
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .and(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_2952383337_address))
                """
        );
    }


    // ===== Other combinations of directives =====

    /**
     * Given that A has a field B, defined by the interface that A implements, and this field includes no reference
     * directive, and there is a direct relation from A to B, when a new resolver is generated, a JOIN clause should be
     * created. The JOIN clause must include table B retrieved through A.
     */
    @Test
    @DisplayName("Reference from multi table interface")
    void fromMultitableInterface() {
        assertGeneratedContentContains(
                "fromMultitableInterface", Set.of(CUSTOMER_TABLE),
               "_payment = PAYMENT.as",
               "payment_425747824_customer = _payment.customer().as",
                """
                .select(
                        DSL.row(_payment.PAYMENT_ID),
                        DSL.row(payment_425747824_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_payment)
                .join(payment_425747824_customer)
                """
        );
    }

    @Test
    @DisplayName("Temporary test for reference ID argument (not node strategy) outside root")
    // TODO: Uncertain about rules when input argument has reference directive. In this test, a new customer-alias
    //  is created, and the reference to store is made through this alias instead of the previous path
    //  (address->customer->store). The generated code for this test should be verified to see if it behaves as expected.
    void idArgumentOnNonRootQueryWithoutNodeStrategy() {
        assertGeneratedContentContains(
                "idArgumentOnNonRootQueryWithoutNodeStrategy", Set.of(CUSTOMER_TABLE),
                "customer_1589604633_store_left.hasStaffId(staffId)"
//                "customer_2952383337_store_left.hasStaffId(staffId)"
        );
    }

    @Test
    @DisplayName("splitQuery field after service returning java record")
    void afterJavaService() {
        assertGeneratedContentContains(
                "afterJavaService", Set.of(CUSTOMER_TABLE),
                "Set<Row1<Long>> _rk_customer",
                ".select(DSL.row(_a_address.ADDRESS_ID), DSL.row(_a_address",
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
                ".select(DSL.row(_a_address.ADDRESS_ID), DSL.row(_a_address"
        );
    }

    @Test
    @DisplayName("Nested splitQuery field after service returning java record")
    void afterJavaServiceNested() {
        assertGeneratedContentContains(
                "afterJavaServiceNested", Set.of(CUSTOMER_TABLE),
                ".select(DSL.row(_a_address.ADDRESS_ID), DSL.row(_a_address"
        );
    }
}
