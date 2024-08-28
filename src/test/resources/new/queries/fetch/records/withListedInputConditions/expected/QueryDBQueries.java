package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerInput;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext ctx, List<CustomerInput> in,
                                             SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                .from(CUSTOMER)
                .where(
                        in != null && in.size() > 0 ?
                                DSL.row(
                                        CUSTOMER.getId(),
                                        DSL.trueCondition(),
                                        DSL.trueCondition()
                                ).in(
                                        in.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getId()),
                                                        no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, internal_it_.getId()),
                                                        no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerString(CUSTOMER, internal_it_.getFirst())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(CUSTOMER))
                .fetchOne(it -> it.into(Customer.class));
    }

    public static List<Customer> customerOverrideForQuery(DSLContext ctx, List<CustomerInput> in,
                                                           SelectionSet select) {
        return ctx
                .select(DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition.customerJOOQRecordList(CUSTOMER))
                .orderBy(CUSTOMER.getIdFields())
                .fetch(it -> it.into(Customer.class));
    }
}
