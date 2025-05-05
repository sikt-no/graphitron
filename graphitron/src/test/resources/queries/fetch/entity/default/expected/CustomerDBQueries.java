package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Object;
import java.lang.String;
import java.util.Map;
import no.sikt.graphql.helpers.query.QueryHelper;
import org.jooq.DSLContext;

public class CustomerDBQueries {
    public static Map<String, Object> customerAsEntity(DSLContext ctx, Map<String, Object> _inputMap) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var _result = ctx
                .select(QueryHelper.objectRow("id", _customer.getId()))
                .from(_customer)
                .where(_customer.hasId((String) _inputMap.get("id")))
                .fetchOneMap();
        return _result != null ? (Map<String, Object>) _result.get("nested") : Map.of();
    }
}
