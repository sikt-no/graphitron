package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.IntStream;
import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext _iv_ctx, DummyRecord inRecord,
                                                 SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerForQuery_customerTable(inRecord))
                .from(_a_customer)
                .where(inRecord != null ? _a_customer.hasId(inRecord.getId()) : DSL.noCondition())
                .and(inRecord != null ? _a_customer.FIRST.eq(inRecord.getFirst()) : DSL.noCondition())
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecord != null ? inRecord.getFirst() : null))
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    public static CustomerTable customerListedForQuery(DSLContext _iv_ctx,
                                                       List<DummyRecord> inRecordList, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerListedForQuery_customerTable(inRecordList))
                .from(_a_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        _a_customer.FIRST,
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, inRecordList.size()).mapToObj(_iv_it ->
                                                DSL.row(
                                                        _a_customer.hasId(inRecordList.get(_iv_it).getId()),
                                                        DSL.val(inRecordList.get(_iv_it).getFirst()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(_iv_it).getFirst())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecordList(_a_customer, inRecordList))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> customerForQuery_customerTable(DummyRecord inRecord) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }

    private static SelectField<CustomerTable> customerListedForQuery_customerTable(
            List<DummyRecord> inRecordList) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
