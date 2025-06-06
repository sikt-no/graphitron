package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, CustomerTable>> queryForQuery(DSLContext ctx, Integer pageSize,
                                                                  String after, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(_customer, orderFields),
                        DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_customer)
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }
}