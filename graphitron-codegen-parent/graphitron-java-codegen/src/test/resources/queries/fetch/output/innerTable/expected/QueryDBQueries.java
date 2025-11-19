package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
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
                _a_customer.getId(),
                DSL.field(
                        DSL.select(_1_queryForQuery_customer_address())
                                .from(_a_customer_2168032777_address)

                )
        ).mapping(Functions.nullOnAllNull(Customer::new));
    }

    private static SelectField<Address> _1_queryForQuery_customer_address() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
        return DSL.row(_a_customer_2168032777_address.getId()).mapping(Functions.nullOnAllNull(Address::new));
    }
}