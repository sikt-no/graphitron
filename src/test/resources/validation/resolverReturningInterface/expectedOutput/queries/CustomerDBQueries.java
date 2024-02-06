package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public Map<String, Customer> loadCustomerByIdsAsNodeRef(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        return ctx
                .select(
                        CUSTOMER.getId(),
                        DSL.row(
                                CUSTOMER.getId().as("id"),
                                DSL.row(
                                        CUSTOMER.address().getId().as("id")
                                ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("id")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}