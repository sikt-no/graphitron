package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.QueryCustomerJavaRecord;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer customersForQuery(DSLContext ctx, QueryCustomerJavaRecord inRecord,
                                             SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId()
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(inRecord != null ? CUSTOMER.ID.eq(inRecord.getSomeID()) : DSL.noCondition())
                .and(inRecord != null ? CUSTOMER.FIRST_NAME.eq(inRecord.getName()) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.customerString(CUSTOMER, inRecord != null ? inRecord.getName() : null))
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .fetchOne(it -> it.into(Customer.class));
    }

    public static List<Customer> customersListedForQuery(DSLContext ctx,
                                                         List<QueryCustomerJavaRecord> inRecordList, SelectionSet select) {
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
                                        CUSTOMER.ID,
                                        CUSTOMER.FIRST_NAME,
                                        DSL.trueCondition()
                                ).in(
                                        inRecordList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getSomeID()),
                                                        DSL.inline(internal_it_.getName()),
                                                        no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.customerString(CUSTOMER, internal_it_.getName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.customerJavaRecordList(CUSTOMER, inRecordList))
                .orderBy(CUSTOMER.getIdFields())
                .fetch(it -> it.into(Customer.class));
    }
}
