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
    public static Map<String, Store> storeForAddress(DSLContext ctx, Set<String> addressIds, String id,
                                              SelectionSet select) {
        var address_address_customer = CUSTOMER.as("address_179789877");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                address_address_customer.store().getId()
                        ).mapping(Functions.nullOnAllNull(Store::new))
                )
                .from(ADDRESS)
                .join(address_address_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.hasIds(addressIds))
                .and(id != null ? address_address_customer.store().ID.eq(id) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(address_address_customer, address_address_customer.store()))
                .orderBy(address_address_customer.store().getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
