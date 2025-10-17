package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.IntStream;

import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, DummyRecord inRecord,
                                                 SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_a_customer)
                .where(inRecord != null ? _a_customer.hasId(inRecord.getId()) : DSL.noCondition())
                .and(inRecord != null ? _a_customer.FIRST.eq(inRecord.getFirst()) : DSL.noCondition())
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecord != null ? inRecord.getFirst() : null))
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    public static CustomerTable customerListedForQuery(DSLContext ctx,
                                                       List<DummyRecord> inRecordList, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_a_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        _a_customer.FIRST,
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, inRecordList.size()).mapToObj(internal_it_ ->
                                                DSL.row(
                                                        _a_customer.hasId(inRecordList.get(internal_it_).getId()),
                                                        DSL.val(inRecordList.get(internal_it_).getFirst()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(internal_it_).getFirst())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecordList(_a_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
