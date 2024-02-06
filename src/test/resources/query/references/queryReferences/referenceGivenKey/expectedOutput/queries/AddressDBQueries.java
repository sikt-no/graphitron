package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
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
    public Map<String, List<Customer>> customersForAddress(DSLContext ctx, Set<String> addressIds,
                                                           SelectionSet select) {
        var address_customeraddressidfkey_customer = CUSTOMER.as("address_2452302987");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                address_customeraddressidfkey_customer.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customers")
                )
                .from(ADDRESS)
                .join(address_customeraddressidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.hasIds(addressIds))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
