package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<String, Customer> customerForNode(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy, Set<String> _mi_id, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(
                        _iv_nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())),
                        customerForNode_customer(_iv_nodeIdStrategy)
                )
                .from(_a_customer)
                .where(_iv_nodeIdStrategy.hasIds("Customer", _mi_id, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))
                .fetchMap(Record2::value1, Record2::value2);
    }

    private static SelectField<Customer> customerForNode_customer(NodeIdStrategy _iv_nodeIdStrategy) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_iv_nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new));
    }
}
