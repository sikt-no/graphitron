package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Address;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Address> addressForQuery(DSLContext ctx, String cityID, SelectionSet select) {
        var address_customer_customer__customer_address_id_fkey = CUSTOMER.as("ADDRESS_1017013635");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId().as("id"),
                                select.optional("customersLastNames", address_customer_customer__customer_address_id_fkey.LAST_NAME).as("customersLastNames")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                )
                .from(ADDRESS)
                .join(address_customer_customer__customer_address_id_fkey)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(0, Address.class);
    }
}
