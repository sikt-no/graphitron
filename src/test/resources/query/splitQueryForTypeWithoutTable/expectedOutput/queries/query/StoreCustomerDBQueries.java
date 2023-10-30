package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Customer;
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
        var store_customer_mostvaluablecustomer = CUSTOMER.as("STORE_3066483439");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(
                                store_customer_mostvaluablecustomer.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customer")
                )
                .from(STORE)
                .join(store_customer_mostvaluablecustomer)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.mostValuableCustomer(STORE, store_customer_mostvaluablecustomer))
                .where(STORE.hasIds(storeCustomerIds))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
