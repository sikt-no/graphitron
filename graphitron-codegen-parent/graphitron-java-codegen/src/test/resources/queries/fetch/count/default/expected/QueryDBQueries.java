package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Integer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countQueryForQuery(DSLContext ctx) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return ctx
                .select(DSL.count())
                .from(_a_customer)
                .fetchOne(0, Integer.class);
    }
}
