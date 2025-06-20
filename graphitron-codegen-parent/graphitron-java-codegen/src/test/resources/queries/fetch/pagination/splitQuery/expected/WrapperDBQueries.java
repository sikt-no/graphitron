package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.Integer;
import java.lang.Long;
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
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class WrapperDBQueries {
    public static Map<Record1<Long>, List<Pair<String, CustomerTable>>> queryForWrapper(DSLContext ctx,
                                                                                 Set<Record1<Long>> wrapperResolverKeys, Integer pageSize, String after, SelectionSet select) {
        var _address = ADDRESS.as("address_2030472956");
        var address_2030472956_customer = _address.customer().as("customer_2337142794");
        var orderFields = address_2030472956_customer.fields(address_2030472956_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_address.ADDRESS_ID),
                        DSL.multiset(
                                DSL.select(
                                                QueryHelper.getOrderByToken(address_2030472956_customer, orderFields),
                                                DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                                        .from(address_2030472956_customer)
                                        .orderBy(orderFields)
                                        .seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                                        .limit(pageSize + 1)
                        )
                )
                .from(_address)
                .where(DSL.row(_address.ADDRESS_ID).in(wrapperResolverKeys.stream().map(Record1::valuesRow).toList()))
                .fetchMap(
                        Record2::value1,
                        it ->  it.value2().map(r -> r.value2() == null ? null : new ImmutablePair<>(r.value1(), r.value2()))
                );
    }
}
