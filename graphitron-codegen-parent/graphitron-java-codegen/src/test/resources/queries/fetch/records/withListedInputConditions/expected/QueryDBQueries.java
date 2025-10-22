import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.IntStream;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, List<CustomerRecord> inRecordList,
                                                 SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(customerForQuery_customerTable(inRecordList, _a_customer))
                .from(_a_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        DSL.trueCondition(),
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, inRecordList.size()).mapToObj(internal_it_ ->
                                                DSL.row(
                                                        _a_customer.hasId(inRecordList.get(internal_it_).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(internal_it_).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_a_customer, inRecordList.get(internal_it_).getFirstName())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_a_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    public static CustomerTable customerOverrideForQuery(DSLContext ctx,
                                                         List<CustomerRecord> inRecordList, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(customerOverrideForQuery_customerTable(inRecordList, _a_customer))
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_a_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    private static SelectField<CustomerTable> customerForQuery_customerTable(
            List<CustomerRecord> inRecordList, Customer _a_customer) {
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }

    private static SelectField<CustomerTable> customerOverrideForQuery_customerTable(
            List<CustomerRecord> inRecordList, Customer _a_customer) {
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}