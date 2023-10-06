package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Customer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class AddressDBQueries {
    public Map<String, List<Customer>> customersForAddress(DSLContext ctx, Set<String> addressIder,
            SelectionSet select) {
        var address_customers = CUSTOMER.as("ADDRESS_1191842373");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                address_customers.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customers")
                )
                .from(ADDRESS)
                .join(address_customers)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.hasIds(addressIder))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}