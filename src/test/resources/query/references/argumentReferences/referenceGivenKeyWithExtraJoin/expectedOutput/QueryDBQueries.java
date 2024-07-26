package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Address> address0ForQuery(DSLContext ctx, String cityID, String storeID,
            SelectionSet select) {
        var address_customer_left = ADDRESS.customer().as("customer_2761894695");
        var customer_2761894695_store_left = address_customer_left.store().as("store_2053761122");
        return ctx
                .select(DSL.row(ADDRESS.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                .from(ADDRESS)
                .leftJoin(address_customer_left)
                .leftJoin(customer_2761894695_store_left)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .and(storeID != null ? customer_2761894695_store_left.STORE_ID.eq(storeID) : DSL.noCondition())
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
    public static List<Address> address1ForQuery(DSLContext ctx, String cityID, String storeID,
            SelectionSet select) {
        var address_customer_left = ADDRESS.customer().as("customer_2761894695");
        var customer_2761894695_store_left = address_customer_left.store().as("store_2053761122");
        return ctx
                .select(DSL.row(ADDRESS.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                .from(ADDRESS)
                .leftJoin(address_customer_left)
                .leftJoin(customer_2761894695_store_left)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .and(customer_2761894695_store_left.STORE_ID.eq(storeID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
