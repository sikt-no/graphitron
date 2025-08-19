import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.services.CustomerTableService;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Pair<String, Customer>> customerForQuery(DSLContext ctx, String first_name,
                                                                Integer pageSize, String after, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var customerTableService = new CustomerTableService();
        _customer = customerTableService.customerTable(_customer, first_name);
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        QueryHelper.getOrderByToken(_customer, orderFields),
                        DSL.row(_customer.ID).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(_customer)
                .where(first_name != null ? _customer.FIRST_NAME.eq(first_name) : DSL.noCondition())
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize + 1)
                .fetch()
                .map(it -> new ImmutablePair<>(it.value1(), it.value2()));
    }

    public static Integer countCustomerForQuery(DSLContext ctx, String first_name) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.count())
                .from(_customer)
                .where(first_name != null ? _customer.FIRST_NAME.eq(first_name) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }
}