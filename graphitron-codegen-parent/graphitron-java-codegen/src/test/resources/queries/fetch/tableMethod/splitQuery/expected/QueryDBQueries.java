import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import no.sikt.graphitron.codereferences.services.CustomerTableMethod;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var customerTableMethod = new CustomerTableMethod();
        var _a_customer = CUSTOMER.as("customer_2168032777");
        _a_customer = customerTableMethod.customerTable(_a_customer);
        return _iv_ctx
                .select(DSL.row(DSL.row(_a_customer.CUSTOMER_ID)).mapping(Functions.nullOnAllNull(Customer::new)))
                .from(_a_customer)
                .fetchOne(_iv_it -> _iv_it.into(Customer.class));
    }
}