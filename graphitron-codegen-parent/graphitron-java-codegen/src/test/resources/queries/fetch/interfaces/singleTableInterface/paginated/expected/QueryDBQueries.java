package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressInDistrictOne;
import fake.graphql.example.model.AddressInDistrictTwo;
import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;

public class QueryDBQueries {

    public static List<Pair<String, Address>> addressForQuery(DSLContext _iv_ctx, Integer _iv_pageSize, String _mi_after, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx.select(
                        _a_address.DISTRICT.as("_iv_discriminator"),
                        QueryHelper.getOrderByToken(_a_address, _iv_orderFields).as("_iv_token"),
                        _a_address.POSTAL_CODE.as("postalCode"),
                        _a_address.getId().as("id")
                )
                .from(_a_address)
                .where(_a_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(_iv_orderFields)
                .seek(QueryHelper.getOrderByValues(_iv_ctx, _iv_orderFields, _mi_after))
                .limit(_iv_pageSize + 1)
                .fetch(
                        _iv_it -> {
                            var _iv_discriminatorValue = _iv_it.get("_iv_discriminator", _a_address.DISTRICT.getConverter());
                            var _iv_token = _iv_it.get("_iv_token", String.class);
                            Address _iv_data;
                            if (_iv_discriminatorValue.equals("ONE")) {
                                _iv_data = _iv_it.into(AddressInDistrictOne.class);
                            } else if (_iv_discriminatorValue.equals("TWO")) {
                                _iv_data = _iv_it.into(AddressInDistrictTwo.class);
                            } else {
                                throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _iv_discriminatorValue));
                            }
                            return Pair.of(_iv_token, _iv_data);
                        }
                );
    }
}
