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

    public static Address addressForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx.select(
                        _a_address.DISTRICT.as("_iv_discriminator"),
                        _a_address.POSTAL_CODE.as("postalCode"),
                        _a_address.getId().as("id"),
                        _a_address.POSTAL_CODE.as("postalCodeDuplicate")
                )
                .from(_a_address)
                .where(_a_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(_iv_orderFields)
                .fetchOne(
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
