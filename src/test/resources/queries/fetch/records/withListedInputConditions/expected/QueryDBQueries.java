package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, List<CustomerRecord> inRecordList,
                                                 SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(CUSTOMER)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        CUSTOMER.getId(),
                                        DSL.trueCondition(),
                                        DSL.trueCondition()
                                ).in(
                                        inRecordList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getId()),
                                                        no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, internal_it_.getId()),
                                                        no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, internal_it_.getFirstName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .and(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(CUSTOMER, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    public static CustomerTable customerOverrideForQuery(DSLContext ctx,
                                                         List<CustomerRecord> inRecordList, SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(CUSTOMER, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
