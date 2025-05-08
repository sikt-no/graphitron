package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<String, Customer> customerForNode(DSLContext ctx, Set<String> ids,
                                                        SelectionSet select, NodeIdStrategy nodeIdStrategy) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(
                        nodeIdStrategy.createId("CUSTOMER", _customer.fields(_customer.getPrimaryKey().getFieldsArray())),
                        DSL.row(
                                DSL.row(_customer.ADDRESS_ID),
                                nodeIdStrategy.createId("CUSTOMER", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(_customer)
                .where(nodeIdStrategy.hasIds("CUSTOMER", ids, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Address> addressForCustomer(DSLContext ctx,
                                                          NodeIdStrategy nodeIdStrategy, Set<String> customerIds, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var customer_2952383337_address = _customer.address().as("address_1214171484");
        var orderFields = customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        nodeIdStrategy.createId("_customer", _customer.fields(_customer.getPrimaryKey().getFieldsArray())),
                        DSL.field(
                                DSL.select(DSL.row(nodeIdStrategy.createId("ADDRESS", customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address::new)))
                                        .from(customer_2952383337_address)
                        )
                )
                .from(_customer)
                .where(nodeIdStrategy.hasIds("CUSTOMER", customerIds, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))
                .fetchMap(Record2::value1, Record2::value2);
    }
}