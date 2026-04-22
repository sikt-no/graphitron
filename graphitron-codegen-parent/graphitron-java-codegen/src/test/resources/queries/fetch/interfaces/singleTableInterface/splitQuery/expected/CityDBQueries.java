package fake.code.generated.queries.city;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressInDistrictOne;
import fake.graphql.example.model.AddressInDistrictTwo;
import fake.graphql.example.model.Customer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CityRecord;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CityDBQueries {

    public static Map<CityRecord, List<Address>> addressForCity(DSLContext _iv_ctx, Set<CityRecord> _rk_city, SelectionSet _iv_select) {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _a_address_609487378_customer = _a_city_760939060_address.customer().as("customer_3287501498");

        var _iv_orderFields = _a_city_760939060_address.fields(_a_city_760939060_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx.select(
                        DSL.row(_a_city.CITY_ID).convertFrom(_iv_it ->
                                QueryHelper.intoTableRecord(_iv_it, List.of(_a_city.CITY_ID))),
                        DSL.multiset(
                                DSL.select(
                                                _a_city_760939060_address.DISTRICT.as("_iv_discriminator"),
                                                _a_city_760939060_address.POSTAL_CODE.as("postalCode"),
                                                DSL.row(
                                                        DSL.multiset(
                                                                DSL.select(DSL.row(_a_address_609487378_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                                                        .from(_a_address_609487378_customer)
                                                                        .orderBy(_a_address_609487378_customer.fields(_a_address_609487378_customer.getPrimaryKey().getFieldsArray()))
                                                        )
                                                ).mapping(_iv_e -> _iv_e.map(Record1::value1)).as("customers")
                                        )
                                        .from(_a_city_760939060_address)
                                        .where(_a_city_760939060_address.DISTRICT.in("ONE", "TWO"))
                                        .orderBy(_iv_orderFields)
                        )
                )
                .from(_a_city)
                .where(DSL.row(_a_city.CITY_ID).in(_rk_city.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetchMap(
                        Record2::value1,
                        _iv_r -> _iv_r.value2().map(_iv_it -> {
                            var _iv_discriminatorValue = _iv_it.get("_iv_discriminator", _a_city_760939060_address.DISTRICT.getConverter());
                            if (_iv_discriminatorValue.equals("ONE")) {
                                return _iv_it.into(AddressInDistrictOne.class);
                            } else if (_iv_discriminatorValue.equals("TWO")) {
                                return _iv_it.into(AddressInDistrictTwo.class);
                            } else {
                                throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _iv_discriminatorValue));
                            }
                        })
                );
    }
}