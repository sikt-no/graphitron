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
    public static List<Address> addressForQuery(DSLContext ctx, String cityID,
            SelectionSet select) {
        var address_customer_left = ADDRESS.customer().as("customer_2761894695");
        var customer_2761894695_store_left = address_customer_left.store().as("store_2053761122");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId(),
                                select.optional("customerStores0", customer_2761894695_store_left.STORE_ID),
                                select.optional("customerStores1", customer_2761894695_store_left.STORE_ID)
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(ADDRESS)
                .leftJoin(address_customer_left)
                .leftJoin(customer_2761894695_store_left)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
