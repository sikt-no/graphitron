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
    public static List<Pair<String, CustomerTable>> customerForQuery(DSLContext _iv_ctx, CustomerRecord inRecord,
                                                   Integer _iv_pageSize, String after, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        QueryHelper.getOrderByToken(_a_customer, _iv_orderFields),
                        DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .orderBy(_iv_orderFields)
                .seek(QueryHelper.getOrderByValues(_iv_ctx, _iv_orderFields, after))
                .limit(_iv_pageSize + 1)
                .fetch()
                .map(_iv_it -> new ImmutablePair<>(_iv_it.value1(), _iv_it.value2()));
    }

    public static Integer countCustomerForQuery(DSLContext _iv_ctx, CustomerRecord inRecord) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(DSL.count())
                .from(_a_customer)
                .where(no.sikt.graphitron.codereferences.conditions.RecordCustomerCondition.customerJavaRecord(_a_customer, inRecord))
                .fetchOne(0, Integer.class);
    }
}
