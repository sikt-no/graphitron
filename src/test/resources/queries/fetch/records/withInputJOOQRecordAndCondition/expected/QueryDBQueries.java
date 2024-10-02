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
    public static CustomerTable customerForQuery(DSLContext ctx, CustomerRecord inRecord,
                                                 SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(inRecord != null ? _customer.hasId(inRecord.getId()) : DSL.noCondition())
                .and(inRecord != null ? _customer.FIRST_NAME.eq(inRecord.getFirstName()) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, inRecord != null ? inRecord.getFirstName() : null))
                .and(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecord(_customer, inRecord))
                .fetchOne(it -> it.into(CustomerTable.class));
    }

    public static CustomerTable customerListedForQuery(DSLContext ctx,
                                                       List<CustomerRecord> inRecordList, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        _customer.getId(),
                                        _customer.FIRST_NAME,
                                        DSL.trueCondition()
                                ).in(
                                        inRecordList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getId()),
                                                        DSL.inline(internal_it_.getFirstName()),
                                                        no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerString(_customer, internal_it_.getFirstName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .and(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(_customer, inRecordList))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
