package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import java.util.List;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Customer> customersForQuery(DSLContext ctx, NodeIdStrategy nodeIdStrategy,
                                                   SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(DSL.row(nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new)))
                .from(_a_customer)
                .orderBy(orderFields)
                .fetch(it -> it.into(Customer.class));
    }
}
