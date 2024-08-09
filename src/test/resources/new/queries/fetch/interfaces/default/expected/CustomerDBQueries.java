package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<String, Customer> loadCustomerByIdsAsNode(DSLContext ctx, Set<String> ids, SelectionSet select) {
        return ctx
                .select(
                        CUSTOMER.getId(),
                        DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(CUSTOMER.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
