package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
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

public class CustomerDBQueries {
    public static Map<String, Customer> loadCustomerByIdsAsNode(DSLContext ctx, Set<String> ids, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(
                        _customer.getId(),
                        DSL.row(
                                _customer.getId()
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(_customer)
                .where(_customer.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
