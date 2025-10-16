import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.IntStream;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext _iv_ctx, List<CustomerRecord> inRecordList,
                                                 SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerForQuery_customerTable(inRecordList))
                .from(_a_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        DSL.trueCondition(),
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, inRecordList.size()).mapToObj(_iv_it ->
                                                DSL.row(
                                                        _a_customer.hasId(inRecordList.get(_iv_it).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(_iv_it).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(_iv_it).getFirstName())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_a_customer, inRecordList))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    public static CustomerTable customerOverrideForQuery(DSLContext _iv_ctx,
                                                         List<CustomerRecord> inRecordList, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(customerOverrideForQuery_customerTable(inRecordList))
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_a_customer, inRecordList))
                .fetchOne(_iv_it -> _iv_it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> customerForQuery_customerTable(
            List<CustomerRecord> inRecordList) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }

    private static SelectField<CustomerTable> customerOverrideForQuery_customerTable(
            List<CustomerRecord> inRecordList) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}