package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import no.sikt.graphql.NodeIdStrategy;

public class CustomerDBQueries {
    public static Map<String, Customer> customerForNode(DSLContext ctx, NodeIdStrategy nodeIdStrategy, Set<String> ids, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(
                        nodeIdStrategy.createId("Customer", _customer.fields(_customer.getPrimaryKey().getFieldsArray())),
                        DSL.row(nodeIdStrategy.createId("Customer", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(_customer)
                .where(nodeIdStrategy.hasIds("Customer", ids, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
