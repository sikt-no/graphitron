package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.package.model.City;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;


public class StoreCustomerCityDBQueries {
    public Map<String, City> cityForStoreCustomerCity(DSLContext ctx,
                                                      Set<String> storeCustomerCityIds, SelectionSet select) {
        var store_customerstoreidfkey_customer = CUSTOMER.as("store_393720061");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(
                                store_customerstoreidfkey_customer.address().city().getId().as("id")
                        ).mapping(Functions.nullOnAllNull(City::new)).as("city")
                )
                .from(STORE)
                .join(store_customerstoreidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_STORE_ID_FKEY)
                .where(STORE.hasIds(storeCustomerCityIds))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_customerstoreidfkey_customer))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
