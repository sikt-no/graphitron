package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Store;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class AddressDBQueries {
    public static Map<String, Store> storeForAddress(DSLContext ctx, Set<String> addressIds,
            String id, SelectionSet select) {
        var address_customer_left = ADDRESS.customer().as("customer_2761894695");
        var address_customerstore_store_left = STORE.as("address_783101058");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(address_customerstore_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(ADDRESS)
                .leftJoin(address_customer_left)
                .leftJoin(address_customerstore_store_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(address_customer_left, address_customerstore_store_left))
                .where(ADDRESS.hasIds(addressIds))
                .and(id != null ? address_customerstore_store_left.ID.eq(id) : DSL.noCondition())
                .orderBy(address_customerstore_store_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
