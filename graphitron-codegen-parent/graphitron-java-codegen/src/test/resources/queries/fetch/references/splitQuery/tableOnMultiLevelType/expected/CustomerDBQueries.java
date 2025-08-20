import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Country;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Row1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<Row1<Long>, Address> addressForCustomer(DSLContext ctx,
            Set<Row1<Long>> customerResolverKeys, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var customer_2952383337_address = _customer.address().as("address_1214171484");
        var address_1214171484_city = customer_2952383337_address.city().as("city_2554114265");
        var city_2554114265_country = address_1214171484_city.country().as("country_1106577650");
        var orderFields = customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_customer.CUSTOMER_ID),
                        DSL.row(
                                customer_2952383337_address.getId(),
                                customer_2952383337_address.ADDRESS,
                                DSL.field(
                                        DSL.select(
                                                   DSL.row(
                                                           address_1214171484_city.getId(),
                                                           address_1214171484_city.CITY,
                                                           DSL.field(
                                                                   DSL.select(
                                                                              DSL.row(
                                                                                      city_2554114265_country.getId(),
                                                                                      city_2554114265_country.COUNTRY
                                                                              ).mapping(Functions.nullOnAllNull(Country::new))
                                                                      )
                                                                      .from(city_2554114265_country)
                                                           )
                                                   ).mapping(Functions.nullOnAllNull(City::new))
                                           )
                                           .from(address_1214171484_city)
                                )
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(DSL.row(_customer.CUSTOMER_ID).in(customerResolverKeys))
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
    }
}
