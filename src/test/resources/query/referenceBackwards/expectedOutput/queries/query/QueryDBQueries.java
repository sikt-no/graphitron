package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Store;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static City cityForQuery(DSLContext ctx, String id, SelectionSet select) {
        var city_address_left = CITY.address().as("address_2545393164");
        var address_2545393164_customer_left = city_address_left.customer().as("customer_16076978");
        var city_customerstore_store_left = STORE.as("city_3312333495");
        return ctx
                .select(
                        DSL.row(
                                CITY.getId(),
                                DSL.row(city_customerstore_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(CITY)
                .leftJoin(city_address_left)
                .leftJoin(address_2545393164_customer_left)
                .leftJoin(city_customerstore_store_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(address_2545393164_customer_left, city_customerstore_store_left))
                .where(CITY.ID.eq(id))
                .fetchOne(it -> it.into(City.class));
    }
}
