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

public class QueryCustomersDBQueries {
    public static List<Customer> customersForQuery(DSLContext ctx, NodeIdStrategy nodeIdStrategy,
                                                   SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(DSL.row(nodeIdStrategy.createId("Customer", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new)))
                .from(_customer)
                .orderBy(orderFields)
                .fetch(it -> it.into(Customer.class));
    }
}
