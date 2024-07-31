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
public class StoreCustomerCityDBQueries {
    public static Map<String, City> cityForStoreCustomerCity(DSLContext ctx,
            Set<String> storeCustomerCityIds, SelectionSet select) {
        var store_storecustomer_customer_left = CUSTOMER.as("store_802713965");
        var store_802713965_address_left = store_storecustomer_customer_left.address().as("address_3872633320");
        var address_3872633320_city_left = store_802713965_address_left.city().as("city_2245622354");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(address_3872633320_city_left.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(STORE)
                .leftJoin(store_storecustomer_customer_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_storecustomer_customer_left))
                .leftJoin(store_802713965_address_left)
                .leftJoin(address_3872633320_city_left)
                .where(STORE.hasIds(storeCustomerCityIds))
                .orderBy(address_3872633320_city_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
