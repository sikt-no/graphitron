package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Store;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<City> cityForQuery(DSLContext ctx, String id, SelectionSet select) {
        var city_addresscityidfkey_address_left = ADDRESS.as("city_639528019");
        var city_customeraddressidfkey_customer_left = CUSTOMER.as("city_1129650000");
        var city_addresscityidfkey_address = ADDRESS.as("city_3990159062");
        var city_customeraddressidfkey_customer = CUSTOMER.as("city_3624136159");
        return ctx
                .select(
                        DSL.row(
                                CITY.getId().as("id"),
                                DSL.row(
                                        city_customeraddressidfkey_customer_left.store().getId().as("id"),
                                        DSL.row(
                                                city_customeraddressidfkey_customer_left.store().address().getId().as("id")
                                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address0"),
                                        DSL.row(
                                                city_customeraddressidfkey_customer_left.store().address().getId().as("id")
                                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address1")
                                ).mapping(Functions.nullOnAllNull(Store::new)).as("store0"),
                                DSL.row(
                                        city_customeraddressidfkey_customer.store().getId().as("id"),
                                        DSL.row(
                                                city_customeraddressidfkey_customer.store().address().getId().as("id")
                                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address0"),
                                        DSL.row(
                                                city_customeraddressidfkey_customer.store().address().getId().as("id")
                                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address1")
                                ).mapping(Functions.nullOnAllNull(Store::new)).as("store1")
                        ).mapping(Functions.nullOnAllNull(City::new)).as("city")
                )
                .from(CITY)
                .leftJoin(city_addresscityidfkey_address_left)
                .onKey(ADDRESS__ADDRESS_CITY_ID_FKEY)
                .leftJoin(city_customeraddressidfkey_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .join(city_addresscityidfkey_address)
                .onKey(ADDRESS__ADDRESS_CITY_ID_FKEY)
                .join(city_customeraddressidfkey_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(CITY.ID.eq(id))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(city_customeraddressidfkey_customer_left, city_customeraddressidfkey_customer_left.store()))

                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(city_customeraddressidfkey_customer, city_customeraddressidfkey_customer.store()))
                .orderBy(CITY.getIdFields())
                .fetch(0, City.class);
    }
}
