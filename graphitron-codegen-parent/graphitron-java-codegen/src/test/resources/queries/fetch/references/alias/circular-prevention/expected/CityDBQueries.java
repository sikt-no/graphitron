import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CityRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.SelectField;
import org.jooq.impl.DSL;
import no.sikt.graphql.helpers.query.QueryHelper;

public class CityDBQueries {
    public static Map<CityRecord, List<Address>> addressesForCity(DSLContext _iv_ctx,
                                                                  Set<CityRecord> _rk_city, SelectionSet _iv_select) {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _iv_orderFields = _a_city_760939060_address.fields(_a_city_760939060_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_city.CITY_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_city.CITY_ID))),
                        DSL.multiset(
                                DSL.select(addressesForCity_address())
                                        .from(_a_city_760939060_address)
                                        .orderBy(_iv_orderFields)
                        )
                )
                .from(_a_city)
                .where(DSL.row(_a_city.CITY_ID).in(_rk_city.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetchMap(Record2::value1, _iv_r -> _iv_r.value2().map(Record1::value1));
    }

    private static SelectField<Address> addressesForCity_address() {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _a_address_609487378_city = _a_city_760939060_address.city().as("city_3747560710");
        return DSL.row(
                _a_city_760939060_address.getId(),
                DSL.field(
                        DSL.select(_1_addressesForCity_address_city())
                                .from(_a_address_609487378_city)

                )
        ).mapping(Functions.nullOnAllNull(Address::new));
    }

    private static SelectField<City> _1_addressesForCity_address_city() {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _a_address_609487378_city = _a_city_760939060_address.city().as("city_3747560710");
        return DSL.row(
                DSL.row(_a_address_609487378_city.CITY_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address_609487378_city.CITY_ID))),
                _a_address_609487378_city.getId()
        ).mapping(Functions.nullOnAllNull(City::new));
    }
}