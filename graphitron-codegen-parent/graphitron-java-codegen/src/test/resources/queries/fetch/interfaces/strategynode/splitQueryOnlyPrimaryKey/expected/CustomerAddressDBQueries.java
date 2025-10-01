package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Address;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Row1;
import org.jooq.Record2;
import org.jooq.impl.DSL;


public class CustomerAddressDBQueries {
    public static Map<Row1<Long>, Address> addressForCustomer(DSLContext ctx, NodeIdStrategy nodeIdStrategy,
                                                          Set<Row1<Long>> customerResolverKeys, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var customer_2952383337_address = _customer.address().as("address_1214171484");
        var orderFields = customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.field(
                                DSL.select(DSL.row(nodeIdStrategy.createId("Address", customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address::new)))
                                        .from(customer_2952383337_address)
                        )
                )
                .from(_customer)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
    }
}