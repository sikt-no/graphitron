package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Store;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Store> storeForQuery(DSLContext ctx, String id, String name, SelectionSet select) {
        var store_customer_mostvaluablecustomer = CUSTOMER.as("STORE_3066483439");
        return ctx
                .select(
                        DSL.row(
                                STORE.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Store::new)).as("store")
                )
                .from(STORE)
                .leftJoin(store_customer_mostvaluablecustomer)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.mostValuableCustomer(STORE, store_customer_mostvaluablecustomer))
                .where(STORE.ID.eq(id))
                .and(name != null ? store_customer_mostvaluablecustomer.address().addressCityIdFkey().NAME.eq(name) : DSL.noCondition())
                .orderBy(STORE.getIdFields())
                .fetch(0, Store.class);
    }
}