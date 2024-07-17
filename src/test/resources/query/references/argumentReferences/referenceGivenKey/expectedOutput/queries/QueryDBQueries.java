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
    public static List<Address> addressForQuery(DSLContext ctx, String cityID, String lastName,
            SelectionSet select) {
        var address_address_customer_left = CUSTOMER.as("address_1331386265");
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId()
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(ADDRESS)
                .leftJoin(address_address_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .and(lastName != null ? address_address_customer_left.LAST_NAME.eq(lastName) : DSL.noCondition())
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
