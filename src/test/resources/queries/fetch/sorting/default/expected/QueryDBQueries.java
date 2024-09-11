package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> queryForQuery(DSLContext ctx, Order orderBy, SelectionSet select) {
        var orderFields = orderBy == null
                ? CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(CUSTOMER.getIndexes(), Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString());
        return ctx
                .select(DSL.row(CUSTOMER.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(CUSTOMER)
                .orderBy(orderFields)
                .fetch(it -> it.into(CustomerTable.class));
    }
}
