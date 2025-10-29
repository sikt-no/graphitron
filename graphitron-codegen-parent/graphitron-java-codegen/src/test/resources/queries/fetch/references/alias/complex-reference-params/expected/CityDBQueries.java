import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Payment;
import java.lang.Long;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Row1;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CityDBQueries {
    public static Map<Row1<Long>, List<Address>> addressesForCity(DSLContext _iv_ctx,
                                                                  Set<Row1<Long>> _rk_city, SelectionSet _iv_select) {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _iv_orderFields = _a_city_760939060_address.fields(_a_city_760939060_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_city.CITY_ID),
                        DSL.multiset(
                                DSL.select(addressesForCity_address(_a_city_760939060_address))
                                        .from(_a_city_760939060_address)
                                        .orderBy(_iv_orderFields)
                        )
                )
                .from(_a_city)
                .where(DSL.row(_a_city.CITY_ID).in(_rk_city))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), _iv_r -> _iv_r.value2().map(Record1::value1));
    }

    private static SelectField<Address> addressesForCity_address(
            no.sikt.graphitron.jooq.generated.testdata.public_.tables.Address _a_city_760939060_address) {
        var _a_address_609487378_city = _a_city_760939060_address.city().as("city_3747560710");
        return DSL.row(
                _a_city_760939060_address.getId(),
                DSL.field(
                        DSL.select(addressesForCity_address_city(_a_address_609487378_city))
                                .from(_a_address_609487378_city)

                )
        ).mapping(Functions.nullOnAllNull(Address::new));
    }

    private static SelectField<City> addressesForCity_address_city(
            no.sikt.graphitron.jooq.generated.testdata.public_.tables.City _a_city) {
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        var _a_address_609487378_customer = _a_city_760939060_address.customer().as("customer_3287501498");
        var _a_customer_3287501498_payment = _a_address_609487378_customer.payment().as("payment_51871264");
        return DSL.row(
                DSL.row(_a_city.CITY_ID),
                _a_city.getId(),
                DSL.row(
                        DSL.multiset(
                                DSL.select(addressesForCity_address_city_payments(_a_customer_3287501498_payment))
                                        .from(_a_city_760939060_address)
                                        .join(_a_address_609487378_customer)
                                        .join(_a_customer_3287501498_payment)
                                        .orderBy(_a_customer_3287501498_payment.fields(_a_customer_3287501498_payment.getPrimaryKey().getFieldsArray()))
                        )
                ).mapping(_iv_e -> _iv_e.map(Record1::value1))
        ).mapping(Functions.nullOnAllNull(City::new));
    }

    private static SelectField<Payment> addressesForCity_address_city_payments(
            no.sikt.graphitron.jooq.generated.testdata.public_.tables.Payment _a_customer_851721210_payment) {
        return DSL.row(_a_customer_851721210_payment.AMOUNT).mapping(Functions.nullOnAllNull(Payment::new));
    }
}