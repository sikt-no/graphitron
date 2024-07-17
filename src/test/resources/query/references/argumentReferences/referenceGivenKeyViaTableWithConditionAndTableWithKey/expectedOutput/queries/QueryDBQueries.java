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
        var store_store_customer = CUSTOMER.as("store_1860923489");
        return ctx
                .select(
                        DSL.row(
                                STORE.getId()
                        ).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(STORE)
                .join(store_store_customer)
                .onKey(CUSTOMER__CUSTOMER_STORE_ID_FKEY)
                .where(STORE.ID.eq(id))
                .and(store_store_customer.address().city().NAME.eq(name))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeCustomer(STORE, store_store_customer))
                .fetchOne(it -> it.into(Store.class));
    }
}
