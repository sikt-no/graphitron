package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Store;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class StoreDBQueries {
    public static Map<String, Store> flagshipStoreForStore(DSLContext ctx, Set<String> storeIds,
            SelectionSet select) {
        var store_store_left = STORE.as("store_1258081732");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(store_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(STORE)
                .leftJoin(store_store_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeStore(STORE, store_store_left))
                .where(STORE.hasIds(storeIds))
                .orderBy(store_store_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
    public static Map<String, List<Store>> popupStoresForStore(DSLContext ctx, Set<String> storeIds,
            SelectionSet select) {
        var store_store_left = STORE.as("store_1258081732");
        return ctx
                .select(
                        STORE.getId(),
                        DSL.row(store_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(STORE)
                .leftJoin(store_store_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.storeStore(STORE, store_store_left))
                .where(STORE.hasIds(storeIds))
                .orderBy(store_store_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
