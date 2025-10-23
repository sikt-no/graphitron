package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> queryForQuery(DSLContext ctx, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(queryForQuery_customerTable(_a_customer))
                .from(_a_customer)
                .orderBy(orderFields)
                .fetch(it -> it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> queryForQuery_customerTable(Customer _a_customer) {
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
