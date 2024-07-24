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
    public static Map<String, Customer> customerForStoreCustomer(DSLContext ctx,
            Set<String> storeCustomerIds, SelectionSet select) {
        var store_storecustomer_customer_left = CUSTOMER.as("store_802713965");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(store_storecustomer_customer_left.getId()).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(STORE)
                .leftJoin(store_storecustomer_customer_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_storecustomer_customer_left))
                .where(STORE.hasIds(storeCustomerIds))
                .orderBy(store_storecustomer_customer_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
