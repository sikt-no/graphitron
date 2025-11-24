package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<String, Customer> customerForNode(DSLContext _iv_ctx, Set<String> _mi_id, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(
                        _a_customer.getId(),
                        customerForNode_customer()
                )
                .from(_a_customer)
                .where(_a_customer.hasIds(_mi_id))
                .fetchMap(Record2::value1, Record2::value2);
    }

    private static SelectField<Customer> customerForNode_customer() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new));
    }
}
