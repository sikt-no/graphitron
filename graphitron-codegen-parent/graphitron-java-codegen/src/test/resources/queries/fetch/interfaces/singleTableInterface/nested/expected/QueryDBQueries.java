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

    public static List<Address> addressForQuery(DSLContext ctx, SelectionSet select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        var _a_address_223244161_customer = _a_address.customer().as("customer_1589604633");

        var orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return ctx.select(
                        _a_address.DISTRICT.as("_discriminator"),
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
                        ).mapping(a0 -> a0.map(Record1::value1)).as("customers")
                )
                .from(_a_address)
                .where(_a_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(orderFields)
                .fetch(
                        internal_it_ -> {
                            var _discriminatorValue = internal_it_.get("_discriminator", _a_address.DISTRICT.getConverter());
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
