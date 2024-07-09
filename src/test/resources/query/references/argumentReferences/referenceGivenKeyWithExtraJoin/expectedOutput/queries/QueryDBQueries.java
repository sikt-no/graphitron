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
    public static List<Address> address0ForQuery(DSLContext ctx, String cityID, String storeID,
            SelectionSet select) {
        var address_customeraddressidfkey_customer_left = CUSTOMER.as("address_2097104879");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId()
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(ADDRESS)
                .leftJoin(address_customeraddressidfkey_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .and(storeID != null ? address_customeraddressidfkey_customer_left.store().STORE_ID.eq(storeID) : DSL.noCondition())
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
    public static List<Address> address1ForQuery(DSLContext ctx, String cityID, String storeID,
            SelectionSet select) {
        var address_customeraddressidfkey_customer = CUSTOMER.as("address_2452302987");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId()
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(ADDRESS)
                .join(address_customeraddressidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .and(address_customeraddressidfkey_customer.store().STORE_ID.eq(storeID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
