package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;

import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, DummyRecord inRecord,
                                                 SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(inRecord != null ? _customer.hasId(inRecord.getId()) : DSL.noCondition())
                .and(inRecord != null ? _customer.FIRST.eq(inRecord.getFirst()) : DSL.noCondition())
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, inRecord != null ? inRecord.getFirst() : null))
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_customer, inRecord))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    public static CustomerTable customerListedForQuery(DSLContext ctx,
                                                       List<DummyRecord> inRecordList, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        _customer.FIRST,
                                        DSL.trueCondition()
                                ).in(
                                        inRecordList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        _customer.hasId(internal_it_.getId()),
                                                        DSL.inline(internal_it_.getFirst()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, internal_it_.getFirst())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecordList(_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
