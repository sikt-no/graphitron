package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Store;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static City cityForQuery(DSLContext ctx, String id, SelectionSet select) {
        var city_city_address_left = ADDRESS.as("city_1934405614");
        var city_address_customer_left = CUSTOMER.as("city_1281872448");
        var city_city_address = ADDRESS.as("city_534742598");
        var city_address_customer = CUSTOMER.as("city_4159535636");
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                DSL.row(
                                        city_address_customer_left.store().getId(),
                                        DSL.row(
                                                city_address_customer_left.store().address().getId()
                                        ).mapping(Functions.nullOnAllNull(Address::new)),
                                        DSL.row(
                                                city_address_customer_left.store().address().getId()
                                        ).mapping(Functions.nullOnAllNull(Address::new))
                                ).mapping(Functions.nullOnAllNull(Store::new)),
                                DSL.row(
                                        city_address_customer.store().getId(),
                                        DSL.row(
                                                city_address_customer.store().address().getId()
                                        ).mapping(Functions.nullOnAllNull(Address::new)),
                                        DSL.row(
                                                city_address_customer.store().address().getId()
                                        ).mapping(Functions.nullOnAllNull(Address::new))
                                ).mapping(Functions.nullOnAllNull(Store::new))
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .leftJoin(city_city_address_left)
                .onKey(ADDRESS__ADDRESS_CITY_ID_FKEY)
                .leftJoin(city_address_customer_left)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .join(city_city_address)
                .onKey(ADDRESS__ADDRESS_CITY_ID_FKEY)
                .join(city_address_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(CITY.ID.eq(id))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(city_address_customer_left, city_address_customer_left.store()))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(city_address_customer, city_address_customer.store()))
                .fetchOne(it -> it.into(City.class));
    }
}
