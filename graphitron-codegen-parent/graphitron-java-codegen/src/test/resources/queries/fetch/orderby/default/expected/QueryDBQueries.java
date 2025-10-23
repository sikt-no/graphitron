package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import java.util.List;
import java.util.Map;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> queryForQuery(DSLContext ctx, Order orderBy, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = orderBy == null
                ? _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())
                : QueryHelper.getSortFields(_a_customer, Map.ofEntries(Map.entry("NAME", "IDX_LAST_NAME"))
                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString());
        return ctx
                .select(queryForQuery_customerTable(orderBy, _a_customer))
                .from(_a_customer)
                .orderBy(orderFields)
                .fetch(it -> it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> queryForQuery_customerTable(Order orderBy,
                                                                          Customer _a_customer) {
        return DSL.row(_a_customer.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
