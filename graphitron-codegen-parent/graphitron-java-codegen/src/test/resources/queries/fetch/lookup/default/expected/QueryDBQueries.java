package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Map<String, CustomerTable> queryForQuery(DSLContext ctx, List<String> id, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(
                        _customer.getId(),
                        DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_customer)
                .where(id.size() > 0 ? _customer.hasIds(id.stream().collect(Collectors.toSet())): DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
