package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;

import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, CustomerTable>> customerForQuery(DSLContext ctx, CustomerRecord inRecord,
                                                   Integer pageSize, String after, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(_a_customer, orderFields),
                        DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }

    public static Integer countCustomerForQuery(DSLContext ctx, CustomerRecord inRecord) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(DSL.count())
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .fetchOne(0, Integer.class);
    }
}
