package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;

import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, CustomerTable>> customerForQuery(DSLContext ctx, CustomerRecord inRecord,
                                                   Integer pageSize, String after, SelectionSet select) {
        var orderFields = CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(CUSTOMER, orderFields),
                        DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }

    public static Integer countCustomerForQuery(DSLContext ctx, CustomerRecord inRecord) {
        return ctx
                .select(DSL.count())
                .from(CUSTOMER)
                .where(no.fellesstudentsystem.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(CUSTOMER, inRecord))
                .fetchOne(0, Integer.class);
    }
}
