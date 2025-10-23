package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressInDistrictOne;
import fake.graphql.example.model.AddressInDistrictTwo;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Customer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.impl.DSL;

public class QueryDBQueries {

    public static List<Address> addressForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        var _a_address_223244161_customer = _a_address.customer().as("customer_1589604633");

        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx.select(
                        _a_address.DISTRICT.as("_iv_discriminator"),
                        _a_address.POSTAL_CODE.as("postalCode"),
                        DSL.field(
                                DSL.select(DSL.row(_a_address_223244161_city.getId()).mapping(Functions.nullOnAllNull(City::new)))
                                        .from(_a_address_223244161_city)
                        ).as("city"),
                        DSL.row(
                                DSL.multiset(
                                        DSL.select(DSL.row(_a_address_223244161_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                                .from(_a_address_223244161_customer)
                                                .orderBy(_a_address_223244161_customer.fields(_a_address_223244161_customer.getPrimaryKey().getFieldsArray()))
                                )
                        ).mapping(_iv_e -> _iv_e.map(Record1::value1)).as("customers")
                )
                .from(_a_address)
                .where(_a_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(_iv_orderFields)
                .fetch(
                        _iv_it -> {
                            var _iv_discriminatorValue = _iv_it.get("_iv_discriminator", _a_address.DISTRICT.getConverter());
                            if (_iv_discriminatorValue.equals("ONE")) {
                                return _iv_it.into(AddressInDistrictOne.class);
                            } else if (_iv_discriminatorValue.equals("TWO")) {
                                return _iv_it.into(AddressInDistrictTwo.class);
                            } else {
                                throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _iv_discriminatorValue));
                            }
                        }
                );
    }
}
