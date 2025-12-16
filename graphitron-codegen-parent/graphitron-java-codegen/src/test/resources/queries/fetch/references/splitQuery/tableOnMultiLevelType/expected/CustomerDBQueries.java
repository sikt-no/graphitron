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
    public static Map<Row1<Long>, Address> addressForCustomer(DSLContext _iv_ctx,
            Set<Row1<Long>> _rk_customer, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
        var _a_address_2138977089_city = _a_customer_2168032777_address.city().as("city_605147663");
        var _a_city_605147663_country = _a_address_2138977089_city.country().as("country_2368004539");
        var _iv_orderFields = _a_customer_2168032777_address.fields(_a_customer_2168032777_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_customer.CUSTOMER_ID),
                        DSL.row(
                                _a_customer_2168032777_address.getId(),
                                _a_customer_2168032777_address.ADDRESS,
                                DSL.field(
                                        DSL.select(
                                                   DSL.row(
                                                           _a_address_2138977089_city.getId(),
                                                           _a_address_2138977089_city.CITY,
                                                           DSL.field(
                                                                   DSL.select(
                                                                              DSL.row(
                                                                                      _a_city_605147663_country.getId(),
                                                                                      _a_city_605147663_country.COUNTRY
                                                                              ).mapping(Functions.nullOnAllNull(Country::new))
                                                                      )
                                                                      .from(_a_city_605147663_country)

                                                           )
                                                   ).mapping(Functions.nullOnAllNull(City::new))
                                           )
                                           .from(_a_address_2138977089_city)

                                )
                        ).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_a_customer)
                .join(_a_customer_2168032777_address)
                .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2);
    }
}
