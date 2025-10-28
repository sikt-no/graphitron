package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Order;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable queryForQuery(DSLContext _iv_ctx, Order _mi_orderBy, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(queryForQuery_customerTable(_mi_orderBy))
                .from(_a_customer)
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> queryForQuery_customerTable(Order _mi_orderBy) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.LAST_NAME).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
