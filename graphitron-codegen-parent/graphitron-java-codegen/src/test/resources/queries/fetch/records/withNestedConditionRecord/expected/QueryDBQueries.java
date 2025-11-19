package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext _iv_ctx, DummyRecord _mi_inRecord,
                                                 SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerForQuery_customerTable(_mi_inRecord))
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, _mi_inRecord))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> customerForQuery_customerTable(DummyRecord _mi_inRecord) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
