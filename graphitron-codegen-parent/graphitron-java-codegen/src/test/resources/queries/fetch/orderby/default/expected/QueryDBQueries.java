package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import java.util.List;
import java.util.Map;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> queryForQuery(DSLContext ctx, Order orderBy, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = orderBy == null
                ? _customer.fields(_customer.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(_customer, Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString());
        return ctx
                .select(DSL.row(_customer.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .orderBy(orderFields)
                .fetch(it -> it.into(CustomerTable.class));
    }
}
