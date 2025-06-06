package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerUnion;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer queryForQuery(DSLContext ctx, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(DSL.row(_customer.EMAIL).mapping(Functions.nullOnAllNull(CustomerUnion::new))).mapping((a0_0) -> new Customer(a0_0)))
                .from(_customer)
                .fetchOne(it -> it.into(Customer.class));
    }
}