package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Address> addressForQuery(DSLContext ctx, String cityID, SelectionSet select) {
        var address_customeraddressidfkey_customer = CUSTOMER.as("address_2452302987");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId().as("id"),
                                select.optional("customersLastNames", address_customeraddressidfkey_customer.LAST_NAME).as("customersLastNames")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                )
                .from(ADDRESS)
                .join(address_customeraddressidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(0, Address.class);
    }
}
