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


public class CustomerDBQueries {
    public static Map<Row1<Long>, Address> addressForCustomer(DSLContext _iv_ctx,
            NodeIdStrategy _iv_nodeIdStrategy, Set<Row1<Long>> _rk_customer,
            SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
        var _iv_orderFields = _a_customer_2168032777_address.fields(_a_customer_2168032777_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_customer.CUSTOMER_ID),
                        DSL.field(
                                DSL.select(DSL.row(_iv_nodeIdStrategy.createId("Address", _a_customer_2168032777_address.fields(_a_customer_2168032777_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address::new)))
                                .from(_a_customer_2168032777_address)

                        )
                )
                .from(_a_customer)
                .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2);
//                        DSL.row(_customer.CUSTOMER_ID),
//                        DSL.row(nodeIdStrategy.createId("ADDRESS", customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address::new))
//                )
//                .from(_customer)
//                .join(customer_2952383337_address)
//                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys.stream().map(Record1::valuesRow).toList()))
//                .fetchMap(Record2::value1, Record2::value2);
    }
}