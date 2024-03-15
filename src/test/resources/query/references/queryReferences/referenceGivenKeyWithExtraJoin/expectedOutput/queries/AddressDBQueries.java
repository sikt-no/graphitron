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
    public Map<String, List<Store>> stores0ForAddress(DSLContext ctx, Set<String> addressIds,
                                                      SelectionSet select) {
        var address_customeraddressidfkey_customer_left = CUSTOMER.as("address_2097104879");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                address_customeraddressidfkey_customer_left.store().getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Store::new)).as("stores0")
                )
                .from(ADDRESS)
                .leftJoin(address_customeraddressidfkey_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.hasIds(addressIds))
                .orderBy(address_customeraddressidfkey_customer_left.store().getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<Store>> stores1ForAddress(DSLContext ctx, Set<String> addressIds,
                                                      SelectionSet select) {
        var address_customeraddressidfkey_customer = CUSTOMER.as("address_2452302987");
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                address_customeraddressidfkey_customer.store().getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Store::new)).as("stores1")
                )
                .from(ADDRESS)
                .join(address_customeraddressidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(ADDRESS.hasIds(addressIds))
                .orderBy(address_customeraddressidfkey_customer.store().getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
