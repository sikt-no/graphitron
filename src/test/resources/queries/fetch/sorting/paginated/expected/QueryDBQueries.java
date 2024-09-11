package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, CustomerTable>> queryForQuery(DSLContext ctx, Order orderBy, Integer pageSize,
                                                    String after, SelectionSet select) {
        var orderFields = orderBy == null
                ? CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(CUSTOMER.getIndexes(), Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(CUSTOMER, orderFields),
                        DSL.row(CUSTOMER.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(CUSTOMER)
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }
}
