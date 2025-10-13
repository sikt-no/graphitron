package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.util.List;
import java.util.stream.IntStream;

import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryCustomerDBQueries {
    public static CustomerTable customerForQuery(DSLContext ctx, List<CustomerRecord> inRecordList,
                                                 SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        DSL.trueCondition(),
                                        DSL.trueCondition(),
                                        DSL.trueCondition()
                                ).in(
                                        IntStream.range(0, inRecordList.size()).mapToObj(internal_it_ ->
                                                DSL.row(
                                                        _customer.hasId(inRecordList.get(internal_it_).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, inRecordList.get(internal_it_).getId()),
                                                        no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, inRecordList.get(internal_it_).getFirstName())
                                                )
                                        ).toList()
                                ) : DSL.noCondition()
                )
                .and(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
