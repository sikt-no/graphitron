package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<CustomerTable> customerForQuery(DSLContext ctx, CustomerRecord inRecord,
                                                   Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .orderBy(CUSTOMER.getIdFields())
                .seek(CUSTOMER.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(CustomerTable.class));
    }

    public static Integer countCustomerForQuery(DSLContext ctx, CustomerRecord inRecord) {
        return ctx
                .select(DSL.count())
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .fetchOne(0, Integer.class);
    }
}
