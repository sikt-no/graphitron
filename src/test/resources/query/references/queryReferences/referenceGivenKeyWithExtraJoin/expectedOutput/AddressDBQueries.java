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
public class AddressDBQueries {
    public static Map<String, List<Store>> stores0ForAddress(DSLContext ctx, Set<String> addressIds,
            SelectionSet select) {
        var address_customer_left = ADDRESS.customer().as("customer_2761894695");
        var customer_2761894695_store_left = address_customer_left.store().as("store_2053761122");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(customer_2761894695_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(ADDRESS)
                .leftJoin(address_customer_left)
                .leftJoin(customer_2761894695_store_left)
                .where(ADDRESS.hasIds(addressIds))
                .orderBy(customer_2761894695_store_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<Store>> stores1ForAddress(DSLContext ctx, Set<String> addressIds,
            SelectionSet select) {
        var address_customer = ADDRESS.customer().as("customer_2142662792");
        var customer_2142662792_store = address_customer.store().as("store_1458541819");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(customer_2142662792_store.getId()).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(ADDRESS)
                .join(address_customer)
                .join(customer_2142662792_store)
                .where(ADDRESS.hasIds(addressIds))
                .orderBy(customer_2142662792_store.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
