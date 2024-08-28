package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext ctx, CustomerRecord inRecord,
                                             SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId()
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(inRecord != null ? CUSTOMER.hasId(inRecord.getId()) : DSL.noCondition())
                .and(inRecord != null ? CUSTOMER.FIRST_NAME.eq(inRecord.getFirstName()) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, inRecord != null ? inRecord.getFirstName() : null))
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJOOQRecord(CUSTOMER, inRecord))
                .fetchOne(it -> it.into(Customer.class));
    }

    public static List<Customer> customerListedForQuery(DSLContext ctx,
                                                         List<CustomerRecord> inRecordList, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId()
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(
                        inRecordList != null && inRecordList.size() > 0 ?
                                DSL.row(
                                        CUSTOMER.getId(),
                                        CUSTOMER.FIRST_NAME,
                                        DSL.trueCondition()
                                ).in(
                                        inRecordList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getId()),
                                                        DSL.inline(internal_it_.getFirstName()),
                                                        no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, internal_it_.getFirstName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(CUSTOMER, inRecordList))
                .orderBy(CUSTOMER.getIdFields())
                .fetch(it -> it.into(Customer.class));
    }
}
