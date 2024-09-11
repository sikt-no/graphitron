package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, DummyRecord inRecord,
                                                 SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
