package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Store;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Store storeForQuery(DSLContext ctx, String id, String name, SelectionSet select) {
        var store_storecustomer_customer_left = CUSTOMER.as("store_802713965");
        var store_802713965_address_left = store_storecustomer_customer_left.address().as("address_3872633320");
        var address_3872633320_city_left = store_802713965_address_left.city().as("city_2245622354");
        return ctx
                .select(DSL.row(STORE.getId()).mapping(Functions.nullOnAllNull(Store::new)))
                .from(STORE)
                .leftJoin(store_storecustomer_customer_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_storecustomer_customer_left))
                .leftJoin(store_802713965_address_left)
                .leftJoin(address_3872633320_city_left)
                .where(STORE.hasId(id))
                .and(address_3872633320_city_left.NAME.eq(name))
                .fetchOne(it -> it.into(Store.class));
    }
}
