package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;

import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryCustomerDBQueries {
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
}
