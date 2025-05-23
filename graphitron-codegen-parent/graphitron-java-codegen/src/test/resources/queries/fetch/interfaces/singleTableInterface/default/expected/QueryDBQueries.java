package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressInDistrictOne;
import fake.graphql.example.model.AddressInDistrictTwo;
import java.lang.RuntimeException;
import java.lang.String;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

public class QueryDBQueries {

    public static Address addressForQuery(DSLContext ctx, SelectionSet select) {
        var _address = ADDRESS.as("address_2030472956");
        var orderFields = _address.fields(_address.getPrimaryKey().getFieldsArray());
        return ctx.select(
                        _address.DISTRICT.as("_discriminator"),
                        _address.POSTAL_CODE.as("postalCode"),
                        _address.getId().as("id"),
                        _address.POSTAL_CODE.as("postalCodeDuplicate")
                )
                .from(_address)
                .where(_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(orderFields)
                .fetchOne(
                        internal_it_ -> {
                            var _discriminatorValue = internal_it_.get("_discriminator", _address.DISTRICT.getConverter());
                            switch (_discriminatorValue) {
                                case "ONE":
                                    return internal_it_.into(AddressInDistrictOne.class);
                                case "TWO":
                                    return internal_it_.into(AddressInDistrictTwo.class);
                                default:
                                    throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _discriminatorValue));
                            }
                        }
                );
    }
}
