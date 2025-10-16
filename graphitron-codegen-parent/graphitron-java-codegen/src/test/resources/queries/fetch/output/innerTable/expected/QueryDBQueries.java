package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Address;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer queryForQuery(DSLContext ctx, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
        return ctx
                .select(queryForQuery_customer(_a_customer, _a_customer_2168032777_address))
                .from(_a_customer)
                .fetchOne(it -> it.into(Customer.class));
    }

    private static SelectField<Customer> queryForQuery_customer(
            no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer _a_customer,
            Address _a_customer_2168032777_address) {
        return DSL.row(
                _a_customer.getId(),
                DSL.field(
                        DSL.select(queryForCustomer_address(_a_customer_2168032777_address))
                                .from(_a_customer_2168032777_address)

                )
        ).mapping(Functions.nullOnAllNull(Customer::new));
    }

    private static SelectField<fake.graphql.example.model.Address> queryForCustomer_address(
            Address _a_address) {
        return DSL.row(_a_address.getId()).mapping(Functions.nullOnAllNull(fake.graphql.example.model.Address::new));
    }
}