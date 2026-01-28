package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    private static SelectField<Customer> customerForNode_customer(NodeIdStrategy _iv_nodeIdStrategy) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_iv_nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new));
    }
}
