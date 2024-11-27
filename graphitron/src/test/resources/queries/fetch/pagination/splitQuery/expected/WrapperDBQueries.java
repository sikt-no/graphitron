package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class WrapperDBQueries {
    public static Map<String, List<Pair<String, CustomerTable>>> queryForWrapper(DSLContext ctx,
                                                                                 Set<String> wrapperIds, Integer pageSize, String after, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        _customer.getId(),
                        DSL.multiset(
                                DSL.select(
                                                QueryHelper.getOrderByToken(_customer, orderFields),
                                                DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                                        .from(_customer)
                                        .orderBy(orderFields)
                                        .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                                        .limit(pageSize + 1)
                        )
                )
                .from(_customer)
                .where(_customer.hasIds(wrapperIds))
                .fetchMap(
                        Record2::value1,
                        it ->  it.value2().map(r -> r.value2() == null ? null : new ImmutablePair<>(r.value1(), r.value2()))
                );
    }
}
