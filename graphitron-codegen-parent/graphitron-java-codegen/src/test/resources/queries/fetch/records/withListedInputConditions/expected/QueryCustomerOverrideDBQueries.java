package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;

import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryCustomerOverrideDBQueries {
    public static CustomerTable customerOverrideForQuery(DSLContext ctx,
                                                         List<CustomerRecord> inRecordList, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
