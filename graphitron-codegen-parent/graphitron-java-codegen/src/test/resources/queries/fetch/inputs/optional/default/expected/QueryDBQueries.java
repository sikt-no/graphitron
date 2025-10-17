package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable queryForQuery(DSLContext ctx, String id, SelectionSet select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_a_customer)
                .where(id != null ? _a_customer.hasId(id) : DSL.noCondition())
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
