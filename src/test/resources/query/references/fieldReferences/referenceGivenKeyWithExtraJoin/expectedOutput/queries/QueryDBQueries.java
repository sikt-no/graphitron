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
    public static List<Address> addressForQuery(DSLContext ctx, String cityID, SelectionSet select) {
        var address_address_customer_left = CUSTOMER.as("address_1331386265");
        var address_address_customer = CUSTOMER.as("address_179789877");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId(),
                                select.optional("customerStores0", address_address_customer_left.store().STORE_ID),
                                select.optional("customerStores1", address_address_customer.store().STORE_ID)
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(ADDRESS)
                .leftJoin(address_address_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .join(address_address_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
