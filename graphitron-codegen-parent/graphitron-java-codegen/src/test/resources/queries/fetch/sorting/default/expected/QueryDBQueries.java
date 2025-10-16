package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(queryForQuery_customerTable())
                .from(_a_customer)
                .orderBy(_iv_orderFields)
                .fetch(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> queryForQuery_customerTable() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
