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

    public static List<Pair<String, Address>> addressForQuery(DSLContext ctx, Integer pageSize, String after, SelectionSet select) {
        var _address = ADDRESS.as("address_2030472956");
        var orderFields = _address.fields(_address.getPrimaryKey().getFieldsArray());
        return ctx.select(
                        _address.DISTRICT.as("_discriminator"),
                        QueryHelper.getOrderByToken(_address, orderFields).as("_token"),
                        _address.POSTAL_CODE.as("postalCode"),
                        _address.getId().as("id")
                )
                .from(_address)
                .where(_address.DISTRICT.in("ONE", "TWO"))
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch(
                        internal_it_ -> {
                            var _discriminatorValue = internal_it_.get("_discriminator", _address.DISTRICT.getConverter());
                            switch (_discriminatorValue) {
                                case "ONE":
                                    return Pair.of(internal_it_.get("_token", String.class), internal_it_.into(AddressInDistrictOne.class));
                                case "TWO":
                                    return Pair.of(internal_it_.get("_token", String.class), internal_it_.into(AddressInDistrictTwo.class));
                                default:
                                    throw new RuntimeException(String.format("Querying interface '%s' returned row with unexpected discriminator value '%s'", "Address", _discriminatorValue));
                            }
                        }
                );
    }
}
