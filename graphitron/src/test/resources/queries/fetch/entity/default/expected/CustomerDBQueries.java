package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Object;
import java.lang.String;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<String, Object> customerAsEntity(DSLContext ctx, Map<String, Object> _inputMap) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var _result = ctx
                .select(DSL.row(new Object[]{_customer.getId()}).mapping(Map.class, _r -> Arrays.stream(_r).allMatch(Objects::isNull) ? null : Map.ofEntries(Map.entry("id", _r[0]))))
                .from(_customer)
                .where(_customer.hasId((String) _inputMap.get("id")))
                .fetchOneMap();
        return _result != null ? (Map<String, Object>) _result.get("nested") : Map.of();
    }
}
