package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Map<String, CustomerTable> queryForQuery(DSLContext ctx, List<String> id, SelectionSet select) {
        return ctx
                .select(
                        CUSTOMER.getId(),
                        DSL.row(CUSTOMER.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(CUSTOMER)
                .where(id.size() > 0 ? CUSTOMER.hasIds(id.stream().collect(Collectors.toSet())) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
