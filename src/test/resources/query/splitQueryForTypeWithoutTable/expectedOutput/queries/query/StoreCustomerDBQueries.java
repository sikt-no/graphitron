package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class StoreCustomerDBQueries {
    public Map<String, Customer> customerForStoreCustomer(DSLContext ctx,
                                                          Set<String> storeCustomerIds, SelectionSet select) {
        var store_customerstoreidfkey_customer = CUSTOMER.as("store_393720061");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(
                                store_customerstoreidfkey_customer.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customer")
                )
                .from(STORE)
                .join(store_customerstoreidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_STORE_ID_FKEY)
                .where(STORE.hasIds(storeCustomerIds))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_customerstoreidfkey_customer))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
