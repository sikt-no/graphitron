package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Integer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countQueryForQuery(DSLContext ctx) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.count())
                .from(_customer)
                .fetchOne(0, Integer.class);
    }
}
