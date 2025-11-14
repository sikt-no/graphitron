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
    public static List<Pair<String, CustomerTable>> queryForQuery(DSLContext _iv_ctx, Order _mi_orderBy, Integer _iv_pageSize,
                                                    String _mi_after, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _mi_orderBy == null
                ? _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(_a_customer.getIndexes(), Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(_mi_orderBy.getOrderByField().toString()), _mi_orderBy.getDirection().toString());
        return _iv_ctx
                .select(
                        QueryHelper.getOrderByToken(_a_customer, _iv_orderFields),
                        DSL.row(_a_customer.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_a_customer)
                .orderBy(_iv_orderFields)
                .seek(QueryHelper.getOrderByValues(_iv_ctx, _iv_orderFields, _mi_after))
                .limit(_iv_pageSize + 1)
                .fetch()
                .map(_iv_it -> new ImmutablePair<>(_iv_it.value1(), _iv_it.value2()));
    }
}
