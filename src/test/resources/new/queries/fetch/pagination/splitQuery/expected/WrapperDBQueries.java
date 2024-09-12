package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record3;
import org.jooq.impl.DSL;

public class WrapperDBQueries {
    public static Map<String, List<Pair<String, CustomerTable>>> queryForWrapper(DSLContext ctx,
                                                                                 Set<String> wrapperIds, Integer pageSize, String after, SelectionSet select) {
        var orderFields = CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        CUSTOMER.getId(),
                        QueryHelper.getOrderByToken(CUSTOMER, orderFields),
                        DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(CUSTOMER)
                .where(CUSTOMER.hasIds(wrapperIds))
                .orderBy(orderFields)
                .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                .limit(pageSize * wrapperIds.size() + 1)
                .fetchGroups(
                        Record3::value1,
                        it -> it.value3() == null ? null : new ImmutablePair<>(it.value2(), it.value3())
                );
    }
}
