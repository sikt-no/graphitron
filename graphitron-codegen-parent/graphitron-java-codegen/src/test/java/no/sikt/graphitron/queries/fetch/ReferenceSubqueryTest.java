package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

// Note that these are mostly copies from ReferenceQueryTest. Many cases from there are omitted here.
@DisplayName("Fetch queries - Fetching rows through referenced tables in correlated subqueries")
public class ReferenceSubqueryTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/subquery";
    }

    @Test
    @DisplayName("Table path")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address"
        );
    }

    @Test
    @DisplayName("Key path with only one possible path between the tables")
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_QUERY),
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
    @DisplayName("Condition path")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_QUERY),
                "join(_a_customer_address_addresscustomer_address).on(",
                ".addressCustomer(_a_customer_address, _a_customer_address_addresscustomer_address)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_address.CUSTOMER_ID"
        );
    }

    @Test
    @DisplayName("Indirect table path")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address",
                ".join(_a_address_2138977089_city"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_QUERY),
                ".join(_a_customer_city_citycustomer_city).on(",
                ".cityCustomer(_a_customer_city, _a_customer_city_citycustomer_city)",
                ".where(_a_customer.CUSTOMER_ID.eq(_a_customer_city.CUSTOMER_ID"
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
    @DisplayName("Table path on a list")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_TABLE, CUSTOMER_QUERY),
                ".from(_a_customer_2168032777_address)"
        );
    }

    @Test
    @DisplayName("Table path on nested lists")
    void nestedLists() {
        assertGeneratedContentContains(
                "nestedLists", Set.of(CUSTOMER_QUERY),
                """
                            public static Customer queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
                                return _iv_ctx
                                        .select(queryForQuery_customer())
                                        .from(_a_customer)
                                        .fetchOne(_iv_it -> _iv_it.into(Customer.class));
                            }
                            private static SelectField<Customer> queryForQuery_customer() {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
                                return DSL.row(
                                        DSL.row(
                                                DSL.multiset(
                                                        DSL.select(queryForQuery_customer_address(_a_customer_2168032777_address))
                                                        .from(_a_customer_2168032777_address)
                                                        .orderBy(_a_customer_2168032777_address.fields(_a_customer_2168032777_address.getPrimaryKey().getFieldsArray()))
                                                )
                                        ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                                ).mapping(Functions.nullOnAllNull(Customer::new));
                            }
                            private static SelectField<Address> queryForQuery_customer_address(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Address _a_address) {
                                var _a_address_223244161_store = _a_address.store().as("store_2901185776");
                                return DSL.row(
                                        _a_address.getId(),
                                        DSL.row(
                                                DSL.multiset(
                                                        DSL.select(queryForQuery_customer_address_stores(_a_address_223244161_store))
                                                        .from(_a_address_223244161_store)
                                                        .orderBy(_a_address_223244161_store.fields(_a_address_223244161_store.getPrimaryKey().getFieldsArray()))
                                                )
                                        ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                                ).mapping(Functions.nullOnAllNull(Address::new));
                            }
                            private static SelectField<Store> queryForQuery_customer_address_stores(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Store _a_store) {
                                return DSL.row(_a_store.getId()).mapping(Functions.nullOnAllNull(Store::new));
                            }
                        """
        );

//        assertGeneratedContentContains(
//                "nestedLists", Set.of(CUSTOMER_QUERY),
//                ".from(_a_address_2138977089_store)",
//                ".from(_a_customer_2168032777_address)"
//        );
    }

}
