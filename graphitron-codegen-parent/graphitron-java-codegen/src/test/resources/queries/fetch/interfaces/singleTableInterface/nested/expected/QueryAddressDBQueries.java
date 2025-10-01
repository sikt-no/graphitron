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

public class QueryAddressDBQueries {

    public static List<Address> addressForQuery(DSLContext ctx, SelectionSet select) {
        var _address = ADDRESS.as("address_2030472956");
        var address_2030472956_city = _address.city().as("city_4105557412");
        var address_2030472956_customer = _address.customer().as("customer_2337142794");

        var orderFields = _address.fields(_address.getPrimaryKey().getFieldsArray());
        return ctx.select(
                        _address.DISTRICT.as("_discriminator"),
                        _address.POSTAL_CODE.as("postalCode"),
                        DSL.field(
                                DSL.select(DSL.row(address_2030472956_city.getId()).mapping(Functions.nullOnAllNull(City::new)))
                                        .from(address_2030472956_city)
                        ).as("city"),
                        DSL.row(
                                DSL.multiset(
                                        DSL.select(DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                                .from(address_2030472956_customer)
                                                .orderBy(address_2030472956_customer.fields(address_2030472956_customer.getPrimaryKey().getFieldsArray()))
                                )
                        ).mapping(a0 -> a0.map(Record1::value1)).as("customers")
                )
                .from(_address)
                .where(_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(orderFields)
                .fetch(
                        internal_it_ -> {
                            var _discriminatorValue = internal_it_.get("_discriminator", _address.DISTRICT.getConverter());
                            if (_discriminatorValue.equals("ONE")) {
                                return internal_it_.into(AddressInDistrictOne.class);
                            } else if (_discriminatorValue.equals("TWO")) {
                                return internal_it_.into(AddressInDistrictTwo.class);
                            } else {
                                throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _discriminatorValue));
                            }
                        }
                );
    }
}
