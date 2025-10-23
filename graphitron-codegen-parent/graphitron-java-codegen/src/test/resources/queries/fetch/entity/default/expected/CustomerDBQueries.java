package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Object;
import java.lang.String;
import java.util.Map;
import no.sikt.graphql.helpers.query.QueryHelper;
import org.jooq.DSLContext;

public class CustomerDBQueries {
    public static Map<String, Object> customerAsEntity(DSLContext _iv_ctx, Map<String, Object> _iv_inputMap) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_result = _iv_ctx
                .select(QueryHelper.objectRow("id", _a_customer.getId()))
                .from(_a_customer)
                .where(_a_customer.hasId((String) _iv_inputMap.get("id")))
                .fetchOneMap();
        return _iv_result != null ? (Map<String, Object>) _iv_result.get("nested") : Map.of();
    }
}
