package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, CustomerTable>> queryForQuery(DSLContext ctx, Order orderBy, Integer pageSize,
                                                    String after, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = orderBy == null
                ? _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(_a_customer, Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(_a_customer, orderFields),
                        DSL.row(_a_customer.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_a_customer)
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }
}
