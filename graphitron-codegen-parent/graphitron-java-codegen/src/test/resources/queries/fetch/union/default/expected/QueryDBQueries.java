package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerUnion;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer queryForQuery(DSLContext ctx, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(queryForQuery_customer(_a_customer))
                .from(_a_customer)
                .fetchOne(it -> it.into(Customer.class));
    }

    private static SelectField<Customer> queryForQuery_customer(
            no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer _a_customer) {
        return DSL.row(DSL.row(_a_customer.EMAIL).mapping(Functions.nullOnAllNull(CustomerUnion::new))).mapping((a0_0) -> new Customer(a0_0));
    }
}