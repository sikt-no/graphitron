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
    public static CustomerTable customerForQuery(DSLContext _iv_ctx, DummyRecord _mi_inRecord,
                                                 SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerForQuery_customerTable(_mi_inRecord))
                .from(_a_customer)
                .where(_mi_inRecord != null ? _a_customer.hasId(_mi_inRecord.getId()) : DSL.noCondition())
                .and(_mi_inRecord != null ? _a_customer.FIRST.eq(_mi_inRecord.getFirst()) : DSL.noCondition())
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, _mi_inRecord != null ? _mi_inRecord.getFirst() : null))
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, _mi_inRecord))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    public static CustomerTable customerListedForQuery(DSLContext _iv_ctx,
                                                       List<DummyRecord> _mi_inRecordList, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerListedForQuery_customerTable(_mi_inRecordList))
                .from(_a_customer)
                .where(
                        _mi_inRecordList != null && _mi_inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        _a_customer.FIRST,
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, _mi_inRecordList.size()).mapToObj(_iv_it ->
                                                DSL.row(
                                                        _a_customer.hasId(_mi_inRecordList.get(_iv_it).getId()),
                                                        DSL.val(_mi_inRecordList.get(_iv_it).getFirst()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, _mi_inRecordList.get(_iv_it).getFirst())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecordList(_a_customer, _mi_inRecordList))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> customerForQuery_customerTable(DummyRecord _mi_inRecord) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }

    private static SelectField<CustomerTable> customerListedForQuery_customerTable(
            List<DummyRecord> _mi_inRecordList) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
