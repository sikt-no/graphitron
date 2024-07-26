package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class CustomerDBQueries {
    public static Map<String, List<Address>> historicalAddressesForCustomer(DSLContext ctx,
            Set<String> customerIds, SelectionSet select) {
        var customer_customeraddress_address_left = ADDRESS.as("customer_3932835938");
        return ctx
                .select(
                        CUSTOMER.getId(),
                        DSL.row(
                                customer_customeraddress_address_left.getId(),
                                select.optional("address", customer_customeraddress_address_left.ADDRESS)
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(CUSTOMER)
                .leftJoin(customer_customeraddress_address_left)
                .on(no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions.customerAddress(CUSTOMER, customer_customeraddress_address_left))
                .where(CUSTOMER.hasIds(customerIds))
                .orderBy(customer_customeraddress_address_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
