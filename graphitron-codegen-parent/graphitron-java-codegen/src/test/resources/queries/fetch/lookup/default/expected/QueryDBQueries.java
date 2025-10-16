package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Map<String, CustomerTable> queryForQuery(DSLContext _iv_ctx, List<String> id, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
                .select(
                        _a_customer.getId(),
                        queryForQuery_customerTable(id)
                )
                .from(_a_customer)
                .where(id.size() > 0 ? _a_customer.hasIds(id.stream().collect(Collectors.toSet())) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    private static SelectField<CustomerTable> queryForQuery_customerTable(List<String> id) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}
