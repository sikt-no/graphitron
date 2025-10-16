package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import java.util.List;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Customer> customersForQuery(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy,
                                                   SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(customersForQuery_customer(_iv_nodeIdStrategy))
                .from(_a_customer)
                .orderBy(_iv_orderFields)
                .fetch(_iv_it -> _iv_it.into(Customer.class));
    }

    private static SelectField<Customer> customersForQuery_customer(NodeIdStrategy _iv_nodeIdStrategy) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_iv_nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new));
    }
}
