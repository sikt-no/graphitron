package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.City;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class StoreDBQueries {
    public static Map<String, City> cityOfMostValuableCustomerForStore(DSLContext ctx,
            Set<String> storeIds, SelectionSet select) {
        var store_storecustomer_customer_left = CUSTOMER.as("store_802713965");
        var store_802713965_address_left = store_storecustomer_customer_left.address().as("address_3872633320");
        var address_3872633320_city_left = store_802713965_address_left.city().as("city_2245622354");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(
                                address_3872633320_city_left.getId(),
                                select.optional("name", address_3872633320_city_left.CITY)
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(STORE)
                .leftJoin(store_storecustomer_customer_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_storecustomer_customer_left))
                .leftJoin(store_802713965_address_left)
                .leftJoin(address_3872633320_city_left)
                .where(STORE.hasIds(storeIds))
                .orderBy(address_3872633320_city_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
