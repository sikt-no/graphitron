package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Address;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public Map<String, List<Address>> historicalAddressesForCustomer(DSLContext ctx,
                                                                     Set<String> customerIds, SelectionSet select) {
        return ctx
                .select(
                        CUSTOMER.getId(),
                        DSL.row(
                                CUSTOMER.address().getId().as("id"),
                                select.optional("address", CUSTOMER.address().ADDRESS).as("address")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("historicalAddresses")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.hasIds(customerIds))
                .and(no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions.customerAddressJoin(CUSTOMER, ADDRESS))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}