package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.City;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class StoreDBQueries {
    public Map<String, City> cityOfMostValuableCustomerForStore(DSLContext ctx,
                                                                Set<String> storeIds, SelectionSet select) {
        var store_customer_mostvaluablecustomer = CUSTOMER.as("STORE_3066483439");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(
                                store_customer_mostvaluablecustomer.address().city().getId().as("id"),
                                select.optional("name", store_customer_mostvaluablecustomer.address().city().CITY).as("name")
                        ).mapping(Functions.nullOnAllNull(City::new)).as("cityOfMostValuableCustomer")
                )
                .from(STORE)
                .join(store_customer_mostvaluablecustomer)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.mostValuableCustomer(STORE, store_customer_mostvaluablecustomer))
                .where(STORE.hasIds(storeIds))
                .fetchMap(Record2::value1, Record2::value2);
    }
}